package dev.hermes.android.voice

import org.json.JSONObject

/** One opt-in frame in memory. Permission and age are checked again at native submission. */
internal class NativeVoiceVisual(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    internal data class Frame(val bytes: ByteArray, val ticket: VisualTicket, val at: Long, val permitted: () -> Boolean)
    private val evidence = DirectVisualEvidence()
    private var latest: Frame? = null

    @Synchronized fun update(source: String, state: String, reason: String): JSONObject? {
        val result = evidence.update(source, state, reason)
        if (state != "starting" && state != "sharing" && latest?.ticket?.source == source) latest = null
        return result
    }
    @Synchronized fun capture(bytes: ByteArray, permitted: () -> Boolean): Boolean {
        val ticket = evidence.ticket() ?: return false
        if (bytes.size !in 3..GeminiWire.VISUAL_FRAME_MAX || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() || bytes[2] != 0xff.toByte() || !permitted() || !evidence.permits(ticket)) return false
        latest = Frame(bytes.copyOf(), ticket, clock(), permitted)
        return true
    }
    @Synchronized fun current(): Frame? = latest?.takeIf(::permits)
    @Synchronized fun active(): Boolean = evidence.ticket() != null
    @Synchronized fun permits(frame: Frame): Boolean =
        clock() - frame.at in 0..5000 && evidence.permits(frame.ticket) && frame.permitted()
    @Synchronized fun confirmed(frame: Frame): JSONObject? = evidence.sent(frame.ticket, clock())
    @Synchronized fun snapshot(): JSONObject = evidence.snapshot(clock())
    @Synchronized fun clear() { latest = null }
}
