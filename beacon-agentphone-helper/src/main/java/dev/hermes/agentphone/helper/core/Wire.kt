package dev.hermes.agentphone.helper.core

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The link must close now, with nothing sent back. The reason is a fixed word, never data from the wire. */
class Closed(reason: String) : Exception(reason)

/**
 * The channel's bytes (docs/agent-phone/channel-v1.md §2-4): the token proof both ways, the per-direction AES-256-GCM
 * keys and the sealed frames. Pure functions; the test vectors pin every one of them.
 */
object Wire {
    private val MAGIC = byteArrayOf(0x42, 0x41, 0x50, 0x43) // "BAPC"
    const val HANDSHAKE_VERSION: Byte = 1
    const val H2C: Byte = 1
    const val C2H: Byte = 2
    const val TOKEN_BYTES = 32
    const val NONCE_BYTES = 32
    const val CH_LEN = 37
    const val SH_LEN = 69
    const val CP_LEN = 32
    const val MIN_SEALED = 25
    const val MAX_FRAME = 262144
    private const val LABEL = "beacon-agent-phone-channel/1 "
    private val INFO_AUTH = ascii(LABEL + "auth")
    private val INFO_H2C = ascii(LABEL + "host->helper")
    private val INFO_C2H = ascii(LABEL + "helper->host")
    private val HOST_PROOF = ascii(LABEL + "host proof")
    private val HELPER_PROOF = ascii(LABEL + "helper proof")
    private val TOKEN_TEXT = Regex("[A-Za-z0-9_-]{43}")

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (part in parts) mac.update(part)
        return mac.doFinal()
    }

    /** RFC 5869 with SHA-256. An empty salt is 32 zero bytes, as the RFC says. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            block = hmac(prk, block, info, byteArrayOf(counter.toByte()))
            val n = minOf(block.size, length - filled)
            System.arraycopy(block, 0, out, filled, n)
            filled += n
            counter++
        }
        return out
    }

    fun authKey(token: ByteArray) = hkdf(token, ByteArray(0), INFO_AUTH)

    /** (K_h2c, K_c2h) for one link. */
    fun linkKeys(token: ByteArray, nc: ByteArray, ns: ByteArray): Pair<ByteArray, ByteArray> {
        val salt = nc + ns
        return hkdf(token, salt, INFO_H2C) to hkdf(token, salt, INFO_C2H)
    }

    fun clientHello(nc: ByteArray): ByteArray {
        require(nc.size == NONCE_BYTES)
        return MAGIC + byteArrayOf(HANDSHAKE_VERSION) + nc
    }

    fun hostProof(ka: ByteArray, ch: ByteArray, sh0: ByteArray) = hmac(ka, HOST_PROOF, ch, sh0)

    fun helperProof(ka: ByteArray, ch: ByteArray, sh0: ByteArray, ps: ByteArray) = hmac(ka, HELPER_PROOF, ch, sh0, ps)

    /** The host's nonce from an SH body whose proof holds; [Closed] for anything else. */
    fun checkServerHello(ka: ByteArray, ch: ByteArray, body: ByteArray): ByteArray {
        if (body.size != SH_LEN || !hasMagic(body)) throw Closed("hello")
        val sh0 = body.copyOfRange(0, 37)
        val ps = body.copyOfRange(37, SH_LEN)
        if (!MessageDigest.isEqual(ps, hostProof(ka, ch, sh0))) throw Closed("proof")
        return body.copyOfRange(5, 37)
    }

    /** Host side of the CH check (the fake host in the tests uses it). */
    fun readClientHello(body: ByteArray): ByteArray {
        if (body.size != CH_LEN || !hasMagic(body)) throw Closed("hello")
        return body.copyOfRange(5, CH_LEN)
    }

    private fun hasMagic(body: ByteArray) =
        body[0] == MAGIC[0] && body[1] == MAGIC[1] && body[2] == MAGIC[2] && body[3] == MAGIC[3] && body[4] == HANDSHAKE_VERSION

    fun nonceFor(seq: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(seq).array()

    fun aadFor(direction: Byte, seq: Long): ByteArray =
        ByteBuffer.allocate(14).put(MAGIC).put(HANDSHAKE_VERSION).put(direction).putLong(seq).array()

    fun seal(key: ByteArray, direction: Byte, seq: Long, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonceFor(seq)))
        cipher.updateAAD(aadFor(direction, seq))
        return ByteBuffer.allocate(8).putLong(seq).array() + cipher.doFinal(plaintext)
    }

    /** The plaintext of a sealed body that is exactly the next one expected; [Closed] for anything else. */
    fun open(key: ByteArray, direction: Byte, expectedSeq: Long, body: ByteArray): ByteArray {
        if (body.size < MIN_SEALED || body.size > MAX_FRAME) throw Closed("length")
        val seq = ByteBuffer.wrap(body, 0, 8).long
        if (seq != expectedSeq) throw Closed("sequence")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonceFor(seq)))
            cipher.updateAAD(aadFor(direction, seq))
            cipher.doFinal(body, 8, body.size - 8)
        } catch (e: AEADBadTagException) {
            throw Closed("tag")
        } catch (e: GeneralSecurityException) {
            throw Closed("tag")
        }
    }

    fun frame(body: ByteArray): ByteArray = ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()

    /** The body length a 4-byte header announces: the fixed handshake length when [expected] is given, else the sealed range. */
    fun frameLength(header: ByteArray, expected: Int? = null): Int {
        if (header.size != 4) throw Closed("length")
        val length = ByteBuffer.wrap(header).int.toLong() and 0xffffffffL
        if (expected != null && length != expected.toLong()) throw Closed("length")
        if (expected == null && (length < MIN_SEALED || length > MAX_FRAME)) throw Closed("length")
        return length.toInt()
    }

    /** The 32 bytes a start intent's token stands for (base64url, no padding), or null. */
    fun tokenFromText(text: String?): ByteArray? {
        if (text == null || !TOKEN_TEXT.matches(text)) return null
        val raw = try { Base64.getUrlDecoder().decode(text) } catch (e: IllegalArgumentException) { return null }
        return if (raw.size == TOKEN_BYTES) raw else null
    }
}
