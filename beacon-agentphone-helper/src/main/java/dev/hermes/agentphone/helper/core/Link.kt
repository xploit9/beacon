package dev.hermes.agentphone.helper.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A reply to one host request: its type and fields; the link adds `re`. */
class Reply(val t: String, val fields: Map<String, Any?> = emptyMap()) {
    companion object {
        fun error(code: String) = Reply("error", mapOf("code" to code))
    }
}

/** What the helper does for the host. The Android side implements it; the tests fake it. */
interface Helper {
    /** The reply to request [t]. Must return within the contract's 10 s; an exception becomes `error unavailable`. */
    fun request(t: String, message: Map<String, Any?>): Reply

    /** The host's answer to a release_request: the values to fill, or null for `deny`. */
    fun answer(req: String, values: Map<String, String>?)

    /** This link was the host link and has ended: drop the arm, pending releases, held values and the helper's clip. */
    fun linkEnded()
}

/**
 * One channel link, helper end (docs/agent-phone/channel-v1.md §2-5, §8): the token proof both ways, then sealed frames
 * in strict order. It becomes the host link only through [run]'s goLive callback, after the host's proof verified, so an
 * unauthenticated connection can never set, replace or clear the host link (the spike's S10 bug).
 */
class Link(
    private var token: ByteArray,
    private val helper: Helper,
    private val hello: Map<String, Any?>,
    private val nonce: () -> ByteArray,
) {
    private val writeLock = Any()
    private var out: OutputStream? = null
    private var kH2C: ByteArray? = null
    private var kC2H: ByteArray? = null
    private var sendSeq = 0L
    private var recvSeq = 0L
    @Volatile private var closed = false
    @Volatile var live = false
        private set
    @Volatile var ready = false
        private set
    private var closer: (() -> Unit)? = null

    /**
     * Runs the link to its end and returns why it ended (a fixed word). [goLive] is asked once, after the proofs: false
     * means this attempt was replaced and must not become the host link. [timeout] sets the read timeout in ms.
     */
    fun run(input: InputStream, output: OutputStream, close: () -> Unit, timeout: (Int) -> Unit, goLive: (Link) -> Boolean): String {
        out = output
        closer = close
        var reason = "closed"
        try {
            timeout(HANDSHAKE_MS)
            val nc = nonce()
            val ch = Wire.clientHello(nc)
            writeRaw(Wire.frame(ch))
            val sh = readBody(input, Wire.SH_LEN)
            val ka = Wire.authKey(token)
            val ns = Wire.checkServerHello(ka, ch, sh)
            writeRaw(Wire.frame(Wire.helperProof(ka, ch, sh.copyOfRange(0, 37), sh.copyOfRange(37, Wire.SH_LEN))))
            val (h2c, c2h) = Wire.linkKeys(token, nc, ns)
            ka.fill(0)
            forgetToken()
            kH2C = h2c
            kC2H = c2h
            if (!goLive(this)) return "replaced".also { reason = it }
            live = true
            timeout(IDLE_MS)
            if (!send(hello)) return "io".also { reason = it }
            while (true) {
                val plain = Wire.open(h2c, Wire.H2C, recvSeq, readBody(input, null))
                recvSeq++
                if (!dispatch(Messages.decode(plain, Wire.H2C))) return "bye".also { reason = it }
            }
        } catch (e: Closed) {
            reason = e.message ?: "closed"
        } catch (e: IOException) {
            reason = "io"
        } catch (e: RuntimeException) {
            reason = "error"
        } finally {
            close()
            if (live) helper.linkEnded()
        }
        return reason
    }

    /** Ends the link from outside (a newer start replacing an unfinished attempt, or the service stopping). */
    fun close() {
        if (closed) return
        closed = true
        ready = false
        forgetToken()
        synchronized(writeLock) {
            kH2C?.fill(0)
            kC2H?.fill(0)
        }
        try { closer?.invoke() } catch (e: Exception) { }
    }

    private fun forgetToken() {
        token.fill(0)
        token = ByteArray(0)
    }

    private fun dispatch(m: Map<String, Any?>): Boolean {
        val t = m["t"] as String
        if (!ready) {
            // Only the version agreement (or a bye) may come first (contract §5, §7).
            if (t == "bye") return false
            if (t != "hello_ok") throw Closed("order")
            if (m["proto"] != Messages.PROTOCOL.toLong()) throw Closed("version")
            ready = true
            return true
        }
        when (t) {
            "hello_ok" -> throw Closed("order")
            "bye" -> return false
            "release" -> {
                @Suppress("UNCHECKED_CAST")
                helper.answer(m["req"] as String, m["values"] as Map<String, String>)
            }
            "deny" -> helper.answer(m["req"] as String, null)
            else -> {
                val reply = try { helper.request(t, m) } catch (e: Exception) { Reply.error("unavailable") }
                val body = linkedMapOf<String, Any?>("t" to reply.t, "re" to m["id"])
                body.putAll(reply.fields)
                if (!send(body)) {
                    if (closed) return false
                    if (!send(linkedMapOf("t" to "error", "re" to m["id"], "code" to "unavailable"))) return false
                }
            }
        }
        return true
    }

    /** Seals and writes one message under the write lock (one sequence, strict order). False when it can't be sent. */
    fun send(message: Map<String, Any?>): Boolean {
        val plain = try { Messages.encode(message, Wire.C2H) } catch (e: Closed) { return false }
        synchronized(writeLock) {
            val key = kC2H
            val stream = out
            if (closed || !live || key == null || stream == null) return false
            try {
                stream.write(Wire.frame(Wire.seal(key, Wire.C2H, sendSeq, plain)))
                stream.flush()
                sendSeq++
            } catch (e: IOException) {
                close()
                return false
            }
        }
        return true
    }

    /** An event (helper to host) once the versions are agreed; dropped before that. */
    fun event(message: Map<String, Any?>): Boolean = ready && send(message)

    private fun writeRaw(bytes: ByteArray) {
        synchronized(writeLock) {
            val stream = out ?: throw IOException("closed")
            if (closed) throw IOException("closed")
            stream.write(bytes)
            stream.flush()
        }
    }

    private fun readBody(input: InputStream, expected: Int?): ByteArray {
        val length = Wire.frameLength(readExactly(input, 4), expected)
        return readExactly(input, length)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) throw EOFException()
            got += r
        }
        return buf
    }

    companion object {
        const val HANDSHAKE_MS = 5000
        /** The host says bye after 20 min without a frame; the helper gives up a minute later. */
        const val IDLE_MS = 21 * 60 * 1000
    }
}
