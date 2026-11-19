package dev.hermes.agentphone.helper.core

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One dialled connection: its streams, a read timeout and a close. */
interface Conn {
    val input: InputStream
    val output: OutputStream
    fun timeout(ms: Int)
    fun close()
}

/** Opens the one connection the helper ever makes. The Android side dials 10.0.2.2; the tests dial a fake host. */
fun interface Dialer {
    fun dial(port: Int): Conn
}

/**
 * The session rules of docs/agent-phone/channel-v1.md §1 and §8, helper end:
 * - a start intent carries a port and a 32-byte token; anything else is ignored;
 * - while a host link is live, every start intent is ignored (it never replaces the token of a live link or closes it);
 * - without one, a valid intent replaces an attempt whose dial or handshake has not finished;
 * - each token serves one link: it is forgotten after 3 failed dials 500 ms apart, a wrong host proof, or the link's end;
 * - only the current attempt can become the host link, and an attempt's cleanup clears only what it set.
 */
class Sessions(
    private val dialer: Dialer,
    private val helper: Helper,
    private val hello: () -> Map<String, Any?>,
    private val run: (Runnable) -> Unit = { Thread(it, "beacon-channel").start() },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val nonce: () -> ByteArray = { ByteArray(Wire.NONCE_BYTES).also { b -> SecureRandom().nextBytes(b) } },
    private val idle: () -> Unit = {},
) {
    private var attempt: Attempt? = null
    @Volatile private var link: Link? = null
    private val used = ArrayDeque<String>()
    private val events: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "beacon-channel-events").apply { isDaemon = true } }

    /** The host link, or null. */
    val hostLink: Link? get() = link

    val isLive: Boolean get() = link != null

    /** Handles one start intent; true when it started a new attempt. */
    @Synchronized
    fun start(port: Int, tokenText: String?): Boolean {
        if (link != null) return false
        if (port < 1024 || port > 65535) return false
        val token = Wire.tokenFromText(tokenText) ?: return false
        val tag = digest(token)
        if (tag in used) return false
        used.addLast(tag)
        while (used.size > 32) used.removeFirst()
        attempt?.cancel()
        val next = Attempt(port, token)
        attempt = next
        run(Runnable { next.run() })
        return true
    }

    /** Whether nothing is running: no host link and no attempt. */
    @get:Synchronized
    val isIdle: Boolean get() = link == null && attempt == null

    /** Sends an event to the host link off the caller's thread (autofill callbacks run on the main thread). */
    fun event(message: Map<String, Any?>) {
        val l = link ?: return
        events.execute { if (link === l) l.event(message) }
    }

    /** Sends an event on the caller's thread (already a background thread); false when no host link took it. */
    fun eventNow(message: Map<String, Any?>): Boolean = link?.event(message) == true

    /** Ends everything (the service is going away). */
    @Synchronized
    fun stopAll() {
        attempt?.cancel()
        attempt = null
        link?.close()
    }

    @Synchronized
    private fun goLive(a: Attempt, l: Link): Boolean {
        if (attempt !== a || a.cancelled || link != null) return false
        link = l
        return true
    }

    private fun finished(a: Attempt, l: Link?) {
        val nowIdle: Boolean
        synchronized(this) {
            if (l != null && link === l) link = null
            if (attempt === a) attempt = null
            nowIdle = link == null && attempt == null
        }
        if (nowIdle) idle()
    }

    private inner class Attempt(private val port: Int, private var token: ByteArray) {
        @Volatile var cancelled = false
        @Volatile private var current: Link? = null
        @Volatile private var conn: Conn? = null

        fun cancel() {
            cancelled = true
            current?.close()
            try { conn?.close() } catch (e: Exception) { }
        }

        fun run() {
            var l: Link? = null
            try {
                var c: Conn? = null
                for (n in 0 until DIALS) {
                    if (cancelled) return
                    if (n > 0) pause(DIAL_GAP_MS)
                    if (cancelled) return
                    c = try { dialer.dial(port) } catch (e: Exception) { null }
                    if (c != null) break
                }
                if (c == null) return
                conn = c
                if (cancelled) { c.close(); return }
                l = Link(token, helper, hello(), nonce)
                current = l
                if (cancelled) { l.close(); c.close(); return }
                l.run(c.input, c.output, { c.close() }, { c.timeout(it) }) { goLive(this, it) }
            } finally {
                token.fill(0)
                token = ByteArray(0)
                finished(this, l)
            }
        }
    }

    private fun digest(token: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(token).joinToString("") { "%02x".format(it) }

    companion object {
        const val DIALS = 3
        const val DIAL_GAP_MS = 500L
    }
}
