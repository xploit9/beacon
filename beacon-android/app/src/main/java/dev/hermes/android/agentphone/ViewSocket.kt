package dev.hermes.android.agentphone

import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.DeviceRemovedFailure
import dev.hermes.android.data.LoopbackSessionRefreshFailed
import dev.hermes.android.data.NativeAuthExpiredException
import dev.hermes.android.data.HERMES_LOOPBACK_SESSION_HEADER
import dev.hermes.android.data.nativeConnectionTicket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
 * The setup view's socket, protocol `beacon-agent-phone-v1` (EMU-DESIGN §4.2-§4.4; L2's CONTRACT.md §2).
 *
 * The app asks for a 20 s single-use ticket, opens the socket with exactly two subprotocols, shows each JPEG frame and acks it once it is
 * decoded (one frame in flight), and sends touches, keys, text and paste. Every message class has a client-side limit below the server's,
 * because the server closes the view (1008) on any message over its limit. Nothing typed, pasted or drawn is logged or kept: messages
 * live in memory until they are sent, and errors carry fixed words only.
 *
 * Everything here is free of android.* types so it runs in JVM tests; the activity supplies the clock, the frame decoder and the scope.
 */

internal object ViewProtocol {
    const val SUBPROTOCOL = "beacon-agent-phone-v1"
    const val TICKET_PREFIX = "beacon-ticket."
    const val PHONE_PATH = "/api/plugins/beacon/mobile/agent-phone"
    const val TICKET_PATH = "$PHONE_PATH/view-ticket"
    const val START_PATH = "$PHONE_PATH/start"
    const val HAND_BACK_PATH = "$PHONE_PATH/hand-back"
    /** How long a hand-back that cannot reach Hermes keeps trying. */
    const val HAND_BACK_MS = 60_000L
    const val SOCKET_PATH = "/api/plugins/beacon/agent-phone/view"
    const val FRAME_TYPE = 0x01
    const val HEADER_BYTES = 10
    const val TEXT_MAX = 500
    const val PASTE_MAX = 10_000
    const val CLOSE_DONE = 1000
    const val CLOSE_POLICY = 1008
    const val CLOSE_FAILED = 1011
    const val CLOSE_AGAIN = 1013
    val KEYS = setOf("back", "home", "recents", "enter", "backspace", "tab", "left", "right", "up", "down")
}

/** The header of a binary 0x01 frame message: `u8 type | u32 seq | u16 width | u16 height | u8 rotation`, big-endian; the JPEG follows. */
internal data class FrameHeader(val seq: Long, val width: Int, val height: Int, val rotation: Int)

internal fun readFrameHeader(data: ByteArray): FrameHeader? {
    if (data.size <= ViewProtocol.HEADER_BYTES || data[0].toInt() and 0xFF != ViewProtocol.FRAME_TYPE) return null
    fun u8(i: Int) = data[i].toInt() and 0xFF
    val seq = (u8(1).toLong() shl 24) or (u8(2).toLong() shl 16) or (u8(3).toLong() shl 8) or u8(4).toLong()
    val width = (u8(5) shl 8) or u8(6)
    val height = (u8(7) shl 8) or u8(8)
    if (width == 0 || height == 0) return null
    return FrameHeader(seq, width, height, u8(9) and 3)
}

/** Server -> app text messages, read leniently: an unknown `t` or field is ignored, so the server can add to the protocol. */
internal sealed interface ServerText {
    /** [keyboard]: the phone's keyboard is up; [password]: the field it serves is a password field (revision 5 §11.2). */
    data class State(val keyboard: Boolean, val rotation: Int, val helper: String, val prompt: String?, val password: Boolean = false,
                     val controlEpoch: Long? = null) : ServerText
    data class Refused(val what: String, val reason: String) : ServerText
    /** [reason] and [args]: the line's code and the names in it (revision 5 §11.1); the screen words a known code itself. */
    data class Notice(val kind: String, val line: String, val reason: String? = null, val args: Map<String, String> = emptyMap()) : ServerText
    data class Bye(val reason: String) : ServerText
    data object Other : ServerText
}

internal fun parseServerText(raw: String): ServerText {
    val message = runCatching { JSONObject(raw) }.getOrNull() ?: return ServerText.Other
    return when (message.optString("t")) {
        "state" -> ServerText.State(
            // revision 5 §11.2: false, true, or "password" (a password field has the phone's keyboard)
            keyboard = message.opt("keyboard").let { it == true || it == "password" },
            rotation = message.optInt("rotation", 0) and 3,
            helper = message.optString("helper").takeIf { it in setOf("starting", "ready", "off") } ?: "off",
            prompt = (message.opt("prompt") as? String)?.takeIf { it.isNotBlank() },
            password = message.opt("keyboard") == "password",
            controlEpoch = message.optString("epoch").toLongOrNull()?.takeIf { it > 0 && message.optString("lease") == "you" },
        )
        "refused" -> ServerText.Refused(message.optString("what"), message.optString("reason"))
        "notice" -> message.optString("line").takeIf { it.isNotBlank() }
            ?.let { ServerText.Notice(message.optString("kind"), it.take(200), message.code(), message.lineArgs()) } ?: ServerText.Other
        "bye" -> ServerText.Bye(message.optString("reason"))
        else -> ServerText.Other
    }
}

internal data class TouchPoint(val id: Int, val x: Double, val y: Double, val phase: String)

/** App -> server messages in exactly the shapes the server checks: one `t`, only the named fields, nothing else. */
internal object ClientMessage {
    private fun of(t: String) = JSONObject().put("t", t)
    fun ack(seq: Long): String = of("ack").put("seq", seq and 0xFFFFFFFFL).toString()
    fun touch(points: List<TouchPoint>): String {
        require(points.size in 1..10 && points.map { it.id }.toSet().size == points.size && points.all { it.id in 0..9 && it.phase in PHASES })
        val list = JSONArray()
        points.forEach { p -> list.put(JSONObject().put("id", p.id).put("x", unit(p.x)).put("y", unit(p.y)).put("phase", p.phase)) }
        return of("touch").put("points", list).toString()
    }
    fun key(key: String): String { require(key in ViewProtocol.KEYS); return of("key").put("key", key).toString() }
    fun text(text: String): String { require(validText(text, ViewProtocol.TEXT_MAX)); return of("text").put("text", text).toString() }
    fun paste(text: String): String { require(validText(text, ViewProtocol.PASTE_MAX)); return of("paste").put("text", text).toString() }
    fun quality(low: Boolean): String = of("quality").put("level", if (low) "low" else "normal").toString()
    fun refresh(): String = of("refresh").toString()
    fun background(): String = of("background").toString()
    fun foreground(): String = of("foreground").toString()
    fun done(): String = of("done").toString()
    private val PHASES = setOf("down", "move", "up")
    // Four decimals place a point within a tenth of a pixel on a 2400-pixel screen and keep a touch message short.
    private fun unit(value: Double): Double = if (value.isFinite()) Math.round(value.coerceIn(0.0, 1.0) * 10_000.0) / 10_000.0 else 0.0
}

/** The server counts characters as code points: 1..[limit], no NUL. */
internal fun validText(text: String, limit: Int): Boolean = text.isNotEmpty() && '\u0000' !in text && text.codePointCount(0, text.length) <= limit

/** Printable ASCII is what the server types as paced keys; anything else goes whole through the helper on the agent phone. */
internal fun isPrintableAscii(text: String): Boolean = text.isNotEmpty() && text.all { it in ' '..'~' }

/** [text] cut at character boundaries into pieces of at most [limit] code points, so no emoji or accent is ever split. */
internal fun chunkText(text: String, limit: Int): List<String> {
    val clean = text.replace("\u0000", "")
    if (clean.isEmpty()) return emptyList()
    val breaks = java.text.BreakIterator.getCharacterInstance().apply { setText(clean) }
    val out = mutableListOf<String>()
    var start = 0
    var count = 0
    var at = breaks.first()
    var next = breaks.next()
    while (next != java.text.BreakIterator.DONE) {
        val points = clean.codePointCount(at, next)
        if (count + points > limit && count > 0) { out += clean.substring(start, at); start = at; count = 0 }
        count += points
        at = next
        next = breaks.next()
    }
    if (start < clean.length) out += clean.substring(start)
    return out
}

/** The server's message classes; the client keeps under each one's limit (server: touch 240/s, key 20, text 20 burst 40, paste 2 burst 4, ack 60, others 10). */
internal enum class Lane(val perSecond: Double, val burst: Int) {
    Touch(200.0, 200), Key(16.0, 16), Text(16.0, 32), Paste(1.5, 3), Ack(50.0, 50), Control(8.0, 8)
}

internal class TokenBucket(private val perSecond: Double, private val burst: Int, private val clock: () -> Long) {
    private var tokens = burst.toDouble()
    private var at = clock()
    private fun refill() { val now = clock(); tokens = minOf(burst.toDouble(), tokens + (now - at).coerceAtLeast(0) * perSecond / 1000.0); at = now }
    fun take(): Boolean { refill(); if (tokens < 1.0) return false; tokens -= 1.0; return true }
    /** Milliseconds until one token is there. */
    fun waitMs(): Long { refill(); return if (tokens >= 1.0) 0 else kotlin.math.ceil((1.0 - tokens) * 1000.0 / perSecond).toLong().coerceAtLeast(1) }
}

/**
 * Messages leave in the order they were made, each class within its limit; a message that has no token yet waits, and the ones after it
 * wait behind it (text, keys and touches must reach the phone in order). A touch move replaces a queued move of the same pointers. Acks
 * skip the line: their order against input does not matter.
 */
internal class Outbox(private val clock: () -> Long, private val schedule: (Long, () -> Unit) -> Unit) {
    private class Item(val lane: Lane, val text: String, val moveKey: String?)
    private val queue = ArrayDeque<Item>()
    private val buckets = Lane.entries.associateWith { TokenBucket(it.perSecond, it.burst, clock) }
    private var waiting = false
    private var sender: ((String) -> Boolean)? = null
    val pending: Int get() = queue.size

    /** The socket messages go to; null holds them (none is sent while the view reconnects, and the queue is dropped by [clear]). */
    fun attach(send: ((String) -> Boolean)?) { sender = send; if (send != null) drain() }
    fun clear() { queue.clear() }

    fun offer(lane: Lane, text: String, moveKey: String? = null) {
        if (lane == Lane.Ack) {
            val send = sender ?: return
            if (buckets.getValue(Lane.Ack).take()) send(text) else queue.addFirst(Item(lane, text, null)).also { drainLater() }
            return
        }
        val last = queue.lastOrNull()
        if (moveKey != null && last != null && last.moveKey == moveKey) queue[queue.lastIndex] = Item(lane, text, moveKey)
        else {
            // A queue this long means the socket cannot keep up; old moves are dropped first (the fingers' newest positions stay).
            if (queue.size >= 400) queue.removeAll { it.moveKey != null }
            queue.addLast(Item(lane, text, moveKey))
        }
        drain()
    }

    private fun drain() {
        val send = sender ?: return
        while (queue.isNotEmpty()) {
            val head = queue.first()
            val bucket = buckets.getValue(head.lane)
            if (!bucket.take()) { drainLater(bucket.waitMs()); return }
            queue.removeFirst()
            if (!send(head.text)) { queue.clear(); return }
        }
    }

    private fun drainLater(ms: Long = 20) {
        if (waiting) return
        waiting = true
        schedule(ms) { waiting = false; drain() }
    }
}

/**
 * Whether the link is slow enough to lower the picture. It times a tap or key to the next frame (the ripple or the change starts
 * within a frame, so the gap is mostly the link: 58 ms on loopback, 143 ms at +80 ms, 236 ms at +150 ms in S3). Several slow samples
 * lower the picture; fast ones raise it again after a hold, so it never flaps.
 */
/**
 * Tap-to-picture times choose the picture's quality. The median of the last five over [lowerMs] lowers it. A tap answered later than
 * [windowMs] is the strongest slow sample, not a discarded one: [lateToLower] of them in a row lower the picture at once, so the
 * slowest links (seconds per picture) lower within the first taps (one alone may be a tap that changed nothing on screen, answered
 * by a later, unrelated picture). Raising keeps its rule: fast samples, and at least [holdMs] low.
 */
internal class LinkMeter(
    private val lowerMs: Long = 450, private val raiseMs: Long = 220, private val holdMs: Long = 20_000, private val windowMs: Long = 2_000,
    private val lateToLower: Int = 2,
) {
    var low = false
        private set
    private var probeAt = -1L
    private var lowAt = 0L
    private var late = 0
    private val samples = ArrayDeque<Long>()

    /** A tap or a key went out at [now]. */
    fun input(now: Long) { if (probeAt < 0) probeAt = now }
    /** A frame arrived at [now]; "low" or "normal" when the picture should change. */
    fun frame(now: Long): String? {
        val start = probeAt
        probeAt = -1
        if (start < 0) return null
        val took = now - start
        late = if (took > windowMs) late + 1 else 0
        samples.addLast(took)
        if (samples.size > 5) samples.removeFirst()
        val median = samples.sorted()[samples.size / 2]
        if (!low && (late >= lateToLower || samples.size >= 4 && median > lowerMs)) { low = true; lowAt = now; late = 0; samples.clear(); return "low" }
        if (low && samples.size >= 5 && median < raiseMs && now - lowAt >= holdMs) { low = false; samples.clear(); return "normal" }
        return null
    }
    fun newSocket() { probeAt = -1; late = 0; samples.clear() }
}

// -- the session -------------------------------------------------------------------------------------------------------------------------

internal enum class ViewPhase {
    /** First open: getting a ticket and the first picture. */
    Connecting,
    /** The phone was off: it is starting, then the view opens. */
    Starting,
    Live,
    /** The socket dropped: a new ticket is on its way. */
    Reconnecting,
    /** Hermes or Beacon is restarting or updating: "Back in a moment", retried by itself. */
    BackSoon,
    /** Another of the owner's phones opened the view. */
    Replaced,
    /** The lease ended (5 min away or 15 min without input): the agent has its phone back. */
    Ended,
    /** The phone was turned off. */
    Stopped,
    /** This phone was removed, or the owner, profile or sign-in changed. */
    Removed,
    /** The picture or input failed twice, or the server closed the view for a broken message. */
    Failed,
    /** Hermes did not answer for minutes. */
    HermesDown,
    NotSetUp,
    NotYours,
    /** The phone could not start: [ViewUi.line] says why (the server's own line). */
    StartRefused,
    /** Beacon is not signed in to Hermes on this phone. */
    SignedOut,
    /** The view is finished; the activity closes. */
    Closed,
}

internal enum class FlashKind { Lowered, Notice, TextRefused, PasteRefused }
internal data class Flash(val kind: FlashKind, val line: String, val id: Long, val reason: String? = null, val args: Map<String, String> = emptyMap())

internal data class ViewUi(
    val phase: ViewPhase = ViewPhase.Connecting,
    val hasFrame: Boolean = false,
    /** The agent phone's own keyboard is up (a text field there has focus). */
    val keyboard: Boolean = false,
    /** ...and that field is a password field (Android's own field info, revision 5 §11.2): the text row opens hidden. */
    val keyboardPassword: Boolean = false,
    val helper: String = "off",
    /** The agent phone shows Android's "Allow USB debugging?" prompt; [promptSince] is when it appeared on [ViewSession]'s clock. */
    val prompt: Boolean = false,
    val promptSince: Long = 0,
    val lowered: Boolean = false,
    val flash: Flash? = null,
    /** The server's line for [ViewPhase.StartRefused], its code and names (revision 5 §11.1). */
    val line: String = "",
    val lineReason: String? = null,
    val lineArgs: Map<String, String> = emptyMap(),
    val background: Boolean = false,
    /** Grows each time the text row must start over (a refused text, a new socket). */
    val textReset: Int = 0,
)

internal class ViewTicket(val value: String)
/** The ticket route said no: [status] and its plain detail (never a secret). */
internal class TicketRefused(val status: Int, val detail: String) : IOException("view ticket refused ($status)")

internal data class PhoneNow(val phone: String, val error: String, val errorReason: String?)

internal sealed interface StartAnswer {
    data object Starting : StartAnswer
    data object On : StartAnswer
    data class Refused(val line: String, val reason: String? = null) : StartAnswer
    data class Failed(val status: Int, val detail: String) : StartAnswer
}

internal interface ViewListener {
    fun onOpen()
    fun onText(text: String)
    fun onBinary(data: ByteArray)
    fun onClosed(code: Int)
    /** The socket broke, or the server refused the upgrade with [httpStatus]. */
    fun onFailure(httpStatus: Int?)
}

internal interface ViewConnection {
    fun send(text: String): Boolean
    fun close(code: Int)
    fun cancel()
}

/** What the session needs from Hermes; [BeaconViewTransport] in the app, a fake or a MockWebServer in tests. */
internal interface ViewTransport {
    suspend fun ticket(): ViewTicket
    fun open(ticket: ViewTicket, listener: ViewListener): ViewConnection
    suspend fun start(): StartAnswer
    /** The phone's state ("off", "starting", "on", "stopping") and its last start error line. */
    suspend fun phone(): Pair<String, String>
    /** The phone's state, its error line and that line's code (revision 5 §11.1 `error_reason`). */
    suspend fun phoneNow(): PhoneNow = phone().let { PhoneNow(it.first, it.second, null) }
    suspend fun handBack(epoch: Long)
}

/** Decodes frames off the main thread; [done] reports whether the frame is now shown, from any thread. */
internal interface FrameSink {
    fun submit(header: FrameHeader, data: ByteArray, done: (Boolean) -> Unit)
    fun clear()
}

/**
 * One setup view, from the first ticket to Done. Every method and callback runs on [scope]'s single thread (the main thread in the
 * app); the transport's threads hop onto it. [clock] is in milliseconds.
 */
internal class ViewSession(
    private val transport: ViewTransport,
    private val frames: FrameSink,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Whether this phone has a working connection at all; without one a drop is this phone's, never "Back in a moment". */
    private val online: () -> Boolean = { true },
    /**
     * Where the hand-back runs when the view closes without a socket: a scope that outlives the view (Closed finishes the activity and
     * cancels [scope] at once, and a cancelled hand-back would leave the phone the owner's for 5 more minutes).
     */
    private val lasting: CoroutineScope = scope,
) {
    private val state = MutableStateFlow(ViewUi())
    val ui: StateFlow<ViewUi> = state.asStateFlow()
    private val outbox = Outbox(clock) { ms, run -> scope.launch { delay(ms); run() } }
    private val meter = LinkMeter()
    private var connection: ViewConnection? = null
    private var generation = 0
    private var job: Job? = null
    private var bye: String? = null
    private var donePending = false
    private var controlEpoch: Long? = null
    private var downSince = -1L
    private var lastError = -1L
    private var lastUpgradeRefusal = -1L
    private var refreshAt = -1L
    private var flashes = 0L
    /** Sockets tried since the view was last live; a second failure in a row waits instead of retrying at once. */
    private var attempts = 0
    /** The socket dropped while the view was in the background: it reconnects when the owner comes back. */
    private var reconnectOnForeground = false

    private fun set(change: (ViewUi) -> ViewUi) = state.update(change)
    private fun phase(next: ViewPhase) = set { it.copy(phase = next) }
    private fun flash(kind: FlashKind, line: String = "", reason: String? = null, args: Map<String, String> = emptyMap()) =
        set { it.copy(flash = Flash(kind, line, ++flashes, reason, args)) }
    val phaseNow: ViewPhase get() = state.value.phase

    fun open() { if (phaseNow == ViewPhase.Connecting && connection == null && job == null) connect(ViewPhase.Connecting) }

    // -- input ----------------------------------------------------------------------------------------------------------------------------
    private val live get() = phaseNow == ViewPhase.Live && connection != null && !state.value.background

    fun touch(points: List<TouchPoint>) {
        if (!live || points.isEmpty()) return
        // While the debugging prompt shows nothing new is pressed, but a finger already down is still lifted.
        if (state.value.prompt && points.any { it.phase != "up" }) return
        if (points.any { it.phase == "down" }) meter.input(clock())
        val moves = points.all { it.phase == "move" }
        outbox.offer(Lane.Touch, ClientMessage.touch(points), if (moves) points.map { it.id }.sorted().joinToString(",") else null)
    }

    /** A key; while the debugging prompt shows only Back goes (it answers No). */
    fun key(name: String): Boolean {
        if (!live || name !in ViewProtocol.KEYS || state.value.prompt && name != "back") return false
        meter.input(clock())
        outbox.offer(Lane.Key, ClientMessage.key(name))
        return true
    }

    /** Text to type; false when it cannot go now (not live, or other alphabets while the helper is not ready). */
    fun text(text: String): Boolean {
        if (!live || state.value.prompt) return false
        val pieces = chunkText(text, ViewProtocol.TEXT_MAX)
        if (pieces.isEmpty()) return true
        if (!isPrintableAscii(text) && state.value.helper != "ready") return false
        pieces.forEach { outbox.offer(Lane.Text, ClientMessage.text(it)) }
        return true
    }

    enum class Paste { Sent, Empty, TooLong, NotReady }
    fun paste(text: String?): Paste {
        val clean = text?.replace("\u0000", "").orEmpty()
        if (clean.isEmpty()) return Paste.Empty
        if (clean.codePointCount(0, clean.length) > ViewProtocol.PASTE_MAX) return Paste.TooLong
        if (!live || state.value.prompt || state.value.helper != "ready") return Paste.NotReady
        outbox.offer(Lane.Paste, ClientMessage.paste(clean))
        return Paste.Sent
    }

    fun background(away: Boolean) {
        if (state.value.background == away) return
        set { it.copy(background = away) }
        val c = connection
        if (away) { if (c != null && phaseNow == ViewPhase.Live) outbox.offer(Lane.Control, ClientMessage.background()) }
        else if (reconnectOnForeground) { reconnectOnForeground = false; connect(again()) }
        else if (c != null && phaseNow == ViewPhase.Live) outbox.offer(Lane.Control, ClientMessage.foreground())
    }

    /** Done: hands the phone back to the agent at once and closes the view. */
    fun done() {
        if (phaseNow == ViewPhase.Closed || donePending) return
        val c = connection
        if (c != null && phaseNow == ViewPhase.Live) {
            donePending = true
            outbox.offer(Lane.Control, ClientMessage.done())
            job?.cancel()
            job = scope.launch { delay(2_000); handBackAndClose() }
        } else handBackAndClose()
    }

    /** Only the owner's Done or Return to call can start a hand-back after the socket goes away. */
    private fun handBackAndClose() {
        val mayHold = phaseNow in setOf(ViewPhase.Connecting, ViewPhase.Reconnecting, ViewPhase.BackSoon, ViewPhase.Failed, ViewPhase.HermesDown, ViewPhase.Live, ViewPhase.Starting)
        if (mayHold) controlEpoch?.let { epoch -> lasting.launch { handBack(epoch) } }
        close()
    }

    /**
     * The phone goes back to the agent after the view closed without its socket. Done is often pressed just as this phone lost its
     * connection, so a hand-back that cannot reach Hermes is tried again every 2 s for a minute (otherwise the phone stays the owner's
     * for the server's 5 minutes); any answer from Hermes, yes or no, ends it.
     */
    private suspend fun handBack(epoch: Long) {
        withTimeoutOrNull(ViewProtocol.HAND_BACK_MS) {
            while (true) {
                val answered = try { withTimeoutOrNull(10_000) { transport.handBack(epoch) } != null }
                    catch (e: CancellationException) { throw e } catch (e: TicketRefused) { true } catch (e: Exception) { false }
                if (answered) return@withTimeoutOrNull
                delay(2_000)
            }
        }
    }

    /** Try again from an end state; also "Open here" (takes the view back from another phone) and "Open again". */
    fun retry() {
        if (phaseNow in setOf(ViewPhase.Live, ViewPhase.Closed)) return
        downSince = -1; lastError = -1
        connect(again())
    }

    /** "Turn on" after the phone was turned off. */
    fun turnOn() { if (phaseNow in setOf(ViewPhase.Stopped, ViewPhase.StartRefused)) startPhone() }

    /** The phone's picture changed in a way it should redraw (a frame that could not be decoded). */
    private fun refresh() {
        val now = clock()
        if (refreshAt >= 0 && now - refreshAt < 2_000) return
        refreshAt = now
        outbox.offer(Lane.Control, ClientMessage.refresh())
    }

    // -- the socket -------------------------------------------------------------------------------------------------------------------------
    private fun dropSocket(code: Int = ViewProtocol.CLOSE_DONE) {
        generation++
        outbox.attach(null)
        outbox.clear()
        connection?.let { runCatching { it.close(code) } }
        connection = null
    }

    private fun connect(shown: ViewPhase) {
        job?.cancel()
        dropSocket()
        bye = null
        donePending = false
        reconnectOnForeground = false
        attempts++
        val gen = generation
        phase(if (phaseNow == ViewPhase.BackSoon && shown != ViewPhase.Starting) ViewPhase.BackSoon else shown)
        set { it.copy(textReset = it.textReset + 1, prompt = false) }
        job = scope.launch {
            val ticket = try { transport.ticket() } catch (e: CancellationException) { throw e } catch (e: TicketRefused) { ticketRefused(e); return@launch } catch (e: Exception) { unreachable(); return@launch }
            if (gen != generation) return@launch
            meter.newSocket()
            connection = transport.open(ticket, listener(gen))
        }
    }

    private fun listener(gen: Int) = object : ViewListener {
        private fun on(block: () -> Unit) { scope.launch { if (gen == generation) block() } }
        override fun onOpen() = on {
            outbox.attach { connection?.send(it) == true }
        }
        override fun onText(text: String) = on { serverText(parseServerText(text)) }
        override fun onBinary(data: ByteArray) = on { frame(gen, data) }
        override fun onClosed(code: Int) = on { closed(code) }
        override fun onFailure(httpStatus: Int?) = on { failed(httpStatus) }
    }

    private fun becameLive() {
        if (phaseNow == ViewPhase.Live) return
        downSince = -1
        attempts = 0
        phase(ViewPhase.Live)
        if (meter.low) outbox.offer(Lane.Control, ClientMessage.quality(true))
        if (state.value.background) outbox.offer(Lane.Control, ClientMessage.background())
    }

    private fun serverText(message: ServerText) {
        when (message) {
            is ServerText.State -> {
                controlEpoch = message.controlEpoch
                becameLive()
                val was = state.value
                set { it.copy(keyboard = message.keyboard, keyboardPassword = message.keyboard && message.password, helper = message.helper,
                    prompt = message.prompt == "usb_debugging",
                    promptSince = if (message.prompt == "usb_debugging") (if (was.prompt) was.promptSince else clock()) else 0) }
            }
            is ServerText.Refused -> {
                if (message.what == "paste") flash(FlashKind.PasteRefused)
                else { flash(FlashKind.TextRefused); set { it.copy(textReset = it.textReset + 1) } }
                if (message.reason == "helper") set { it.copy(helper = "off") }
            }
            is ServerText.Notice -> flash(FlashKind.Notice, message.line, message.reason, message.args)
            is ServerText.Bye -> bye = message.reason
            ServerText.Other -> Unit
        }
    }

    private fun frame(gen: Int, data: ByteArray) {
        val header = readFrameHeader(data) ?: return
        becameLive()
        when (meter.frame(clock())) {
            "low" -> { outbox.offer(Lane.Control, ClientMessage.quality(true)); set { it.copy(lowered = true) }; flash(FlashKind.Lowered) }
            "normal" -> { outbox.offer(Lane.Control, ClientMessage.quality(false)); set { it.copy(lowered = false) } }
        }
        frames.submit(header, data) { shown ->
            scope.launch {
                if (gen != generation) return@launch
                // Ack every frame, shown or not, or the next one waits 5 s; a frame that could not be decoded asks for a fresh one.
                outbox.offer(Lane.Ack, ClientMessage.ack(header.seq))
                if (shown) { if (!state.value.hasFrame) set { it.copy(hasFrame = true) } } else refresh()
            }
        }
    }

    private fun closed(code: Int) {
        val reason = bye
        val wasDone = donePending
        dropSocket()
        when {
            reason == "done" -> close()
            wasDone && reason == null -> handBackAndClose()
            wasDone -> close()
            reason == "replaced" -> phase(ViewPhase.Replaced)
            reason == "ended" -> phase(ViewPhase.Ended)
            reason == "stopped" -> phase(ViewPhase.Stopped)
            // Off when the view opens: start it (the owner opened the view to use it). Off after a picture was shown: someone turned it
            // off (the phone page, another phone; the frames can fail before Hermes says "stopped"): never turn it back on behind them.
            reason == "off" -> if (state.value.hasFrame) phase(ViewPhase.Stopped) else startPhone()
            reason == "removed" -> { frames.clear(); set { it.copy(hasFrame = false) }; phase(ViewPhase.Removed) }
            reason == "update" || code == ViewProtocol.CLOSE_AGAIN -> backSoon(20_000)
            reason == "error" || code == ViewProtocol.CLOSE_FAILED -> pictureFailed()
            // A 1008 without a bye: the server refused a message. A mobile stall can bunch a compliant burst past its per-second count
            // (it counts arrival), so one fresh ticket; a second within 30 s stops (a real fault would only repeat).
            code == ViewProtocol.CLOSE_POLICY -> pictureFailed()
            else -> dropped()
        }
    }

    private fun failed(httpStatus: Int?) {
        if (donePending) { handBackAndClose(); return }
        dropSocket()
        when {
            httpStatus == null -> dropped()
            httpStatus in 500..599 -> backSoon(3_000)
            // The upgrade was refused (the ticket ran out, or the binding changed): one fresh ticket, then stop.
            else -> {
                val now = clock()
                if (lastUpgradeRefusal >= 0 && now - lastUpgradeRefusal < 30_000) phase(ViewPhase.Failed)
                else { lastUpgradeRefusal = now; connect(again()) }
            }
        }
    }

    /** Connecting before the first picture, Reconnecting after it. */
    private fun again() = if (state.value.hasFrame) ViewPhase.Reconnecting else ViewPhase.Connecting

    private fun dropped() {
        if (state.value.background) { reconnectOnForeground = true; phase(ViewPhase.Reconnecting); return }
        if (attempts >= 2) unreachable() else connect(again())
    }

    private fun pictureFailed() {
        val now = clock()
        if (lastError >= 0 && now - lastError < 30_000) { phase(ViewPhase.Failed); return }
        lastError = now
        connect(again())
    }

    fun close() {
        job?.cancel()
        dropSocket()
        phase(ViewPhase.Closed)
    }

    // -- when Hermes is away ------------------------------------------------------------------------------------------------------------------
    private fun ticketRefused(e: TicketRefused) {
        when {
            e.status == 401 -> phase(ViewPhase.SignedOut)
            e.status == 404 -> phase(ViewPhase.NotSetUp)
            e.status == 403 && "another profile" in e.detail -> phase(ViewPhase.NotYours)
            e.status == 403 -> { frames.clear(); set { it.copy(hasFrame = false) }; phase(ViewPhase.Removed) }
            e.status == 503 && "Update" in e.detail -> backSoon(20_000)
            e.status in 500..599 || e.status == 429 -> unreachable()
            else -> phase(ViewPhase.Failed)
        }
    }

    /**
     * Hermes did not answer: "Back in a moment", tried again by itself for three minutes. When this phone itself has no connection the
     * view says "Reconnecting…" and keeps trying for as long as it takes.
     */
    private fun unreachable() {
        if (online()) { backSoon(null); return }
        downSince = -1
        phase(again())
        job?.cancel()
        job = scope.launch { delay(2_000); if (!state.value.background) connect(again()) else reconnectOnForeground = true }
    }

    private fun backSoon(firstDelayMs: Long?) {
        val now = clock()
        if (downSince < 0) downSince = now
        if (now - downSince > 180_000) { phase(ViewPhase.HermesDown); return }
        phase(ViewPhase.BackSoon)
        val wait = firstDelayMs ?: (1_000L shl minOf(3, ((now - downSince) / 4_000).toInt())).coerceAtMost(5_000)
        job?.cancel()
        job = scope.launch { delay(wait); if (!state.value.background) connect(again()) else reconnectOnForeground = true }
    }

    private fun startPhone() {
        phase(ViewPhase.Starting)
        job?.cancel()
        job = scope.launch {
            val answer = try { transport.start() } catch (e: CancellationException) { throw e } catch (e: TicketRefused) { ticketRefused(e); return@launch } catch (e: Exception) { unreachable(); return@launch }
            when (answer) {
                is StartAnswer.Refused -> { set { it.copy(line = answer.line, lineReason = answer.reason, lineArgs = emptyMap()) }; phase(ViewPhase.StartRefused); return@launch }
                is StartAnswer.Failed -> { ticketRefused(TicketRefused(answer.status, answer.detail)); return@launch }
                StartAnswer.On -> { connect(ViewPhase.Connecting); return@launch }
                StartAnswer.Starting -> Unit
            }
            val until = clock() + 240_000
            while (clock() < until) {
                delay(1_500)
                val now = try { transport.phoneNow() } catch (e: CancellationException) { throw e } catch (e: TicketRefused) { ticketRefused(e); return@launch } catch (e: Exception) { continue }
                when (now.phone) {
                    "on" -> { connect(ViewPhase.Connecting); return@launch }
                    "off" -> { set { it.copy(line = now.error, lineReason = now.errorReason, lineArgs = emptyMap()) }; phase(ViewPhase.StartRefused); return@launch }
                }
            }
            phase(ViewPhase.Failed)
        }
    }

    /** The view model is going away: the socket closes without Done, so the lease is kept 5 min (an accidental close can come back). */
    fun dispose() { job?.cancel(); dropSocket(); frames.clear() }
}

// -- the app's transport ------------------------------------------------------------------------------------------------------------------

/** The plugin's routes through Beacon's signed-in client (Hermes sign-in, the linked device id, `beacon_profile`), and its socket. */
internal class BeaconViewTransport(private val client: BeaconClient, private val watching: Boolean = false) : ViewTransport {
    private val sockets: OkHttpClient = client.http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(10, TimeUnit.SECONDS).build()

    /** Beacon's own sign-in ended or this phone was removed: those are answers, not Hermes being away. */
    private suspend fun <T> answered(call: suspend () -> T): T = try { call() } catch (e: ApiFailure) { throw TicketRefused(e.status, e.detail) }
        catch (e: NativeAuthExpiredException) { throw TicketRefused(401, "signed out") }
        catch (e: LoopbackSessionRefreshFailed) { throw TicketRefused(401, "signed out") }
        catch (e: DeviceRemovedFailure) { throw TicketRefused(403, "Device removed") }

    override suspend fun ticket(): ViewTicket {
        val answer = answered { client.nativeRequest(ViewProtocol.TICKET_PATH, "POST", JSONObject().apply { if (watching) put("mode", "watch") }) }
        if (answer.optString("path") != ViewProtocol.SOCKET_PATH || answer.optString("subprotocol") != ViewProtocol.SUBPROTOCOL) throw TicketRefused(502, "unexpected ticket")
        return ViewTicket(runCatching { nativeConnectionTicket(answer.optString("ticket")) }.getOrElse { throw TicketRefused(502, "unexpected ticket") })
    }

    override fun open(ticket: ViewTicket, listener: ViewListener): ViewConnection {
        // As /gateway/ws: the ticket is the socket's only authorization, so the sign-in headers stay off it; the device id stays on.
        val request = client.request(ViewProtocol.SOCKET_PATH).removeHeader("Authorization").removeHeader(HERMES_LOOPBACK_SESSION_HEADER)
            .header("Sec-WebSocket-Protocol", "${ViewProtocol.SUBPROTOCOL}, ${ViewProtocol.TICKET_PREFIX}${ticket.value}").build()
        val socket = sockets.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onBinary(bytes.toByteArray())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); listener.onClosed(code) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(response?.code?.takeIf { it != 101 })
        })
        return object : ViewConnection {
            override fun send(text: String) = socket.send(text)
            override fun close(code: Int) { socket.close(code, null) }
            override fun cancel() = socket.cancel()
        }
    }

    override suspend fun start(): StartAnswer {
        // Read by hand: a refused start answers 409 {"phone": "off", "line": "..."}, and the owner should see that line.
        val request = client.request(ViewProtocol.START_PATH, mapOf("beacon_profile" to client.auth.profile), "POST",
            "{}".toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return answered { client.nativeResponse(request, 20_000) { response ->
            val body = runCatching { JSONObject(response.peekBody(16 * 1024L).string()) }.getOrDefault(JSONObject())
            when {
                response.code == 409 -> StartAnswer.Refused(body.optString("line").ifBlank { "The phone didn't start." }, body.code())
                response.code == 200 && body.optString("phone") == "on" -> StartAnswer.On
                response.code in 200..299 -> StartAnswer.Starting
                else -> StartAnswer.Failed(response.code, body.optString("detail"))
            }
        } }
    }

    override suspend fun phone(): Pair<String, String> = phoneNow().let { it.phone to it.error }

    override suspend fun phoneNow(): PhoneNow {
        val answer = answered { client.nativeRequest(ViewProtocol.PHONE_PATH) }
        return PhoneNow(answer.optString("phone"), (answer.opt("error") as? String).orEmpty(), answer.code("error_reason"))
    }

    override suspend fun handBack(epoch: Long) {
        if (!watching) answered { client.nativeRequest(ViewProtocol.HAND_BACK_PATH, "POST", JSONObject().put("epoch", epoch)) }
    }
}
