package dev.hermes.android.data

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * A file sent inside one canonical frame (CHAT-121 follow-ups 4, 6 and 8). Hermes takes a whole file in one file.attach (or image.attach_bytes) frame, as its
 * own Desktop sends it; OkHttp sends at most 16 MiB in one message (okhttp3.internal.ws.RealWebSocket.MAX_QUEUE_SIZE), so the frame goes to the plugin in
 * binary pieces, and the plugin passes it on to Hermes whole (canonical_transport.PIECE_*): first a header (PIECE_HEAD) with the frame's text before and after
 * the file's base64 and the base64's length, which the plugin checks before it holds anything, then the base64 alone. The phone never holds the file's base64,
 * or the frame, whole.
 *
 * Form 3 (follow-up 8): after each piece the plugin says how much of the base64 it holds (PIECE_ACK). Hermes' dashboard pings the socket (20 s, and closes it
 * when the pong is 20 s late), and this phone's pong waits behind everything it already sent: a 1 MiB piece on a 2 KB/s link had the socket closed at 40 s, and
 * the plugin, which sees only whole messages, gives up on a file whose next piece takes over 60 s. So the pieces are sized from the speed the acknowledgments
 * show, and no more than about [ATTACHMENT_PIECE_SECONDS] of it is on the way at once ([PieceSizer]); every file frame goes this way on a plugin that
 * acknowledges, a small one too (one text frame of 1.4 MB would meet the same 40 s).
 */
internal const val PIECE_MORE: Byte = 0
internal const val PIECE_LAST: Byte = 1
internal const val PIECE_HEAD: Byte = 3
/** From the plugin: how much of the file's base64 it holds, as 8 bytes big-endian, then the frame's request id in UTF-8. */
internal const val PIECE_ACK: Byte = 4
/**
 * The form of the pieces above, as the plugin's ticket names it (attach_pieces): 4, form 3's acknowledged pieces on a socket of the file's own (CHAT-121
 * follow-up 9, NativeGateway.attachFile), so the chat's socket never carries a file's pieces. Another form (2's were not acknowledged, 3's went on the
 * chat's socket) is not used: the file goes in one text frame, as on a plugin without pieces.
 */
internal const val ATTACHMENT_PIECES_FORM = 4
/** The most one piece carries (a fast link). */
internal const val ATTACHMENT_PIECE_BYTES = 1024 * 1024
/**
 * The least one piece carries, and the first piece (sent alone, before any speed is known). A phone's upload is one queue for all its connections (its
 * radio's buffer), so what a file has on the way delays the chat socket's pong too, on its own socket or not: the chat's pong must stay well inside
 * the dashboard's 20 s. 16 KiB (follow-up 8) is 32 s at 0.5 KB/s; 2 KiB is 4 s there, and the pieces after it carry about [ATTACHMENT_PIECE_SECONDS] of
 * the measured speed, down to 1 KiB (CHAT-121 follow-up 9).
 */
internal const val ATTACHMENT_PIECE_MIN_BYTES = 1024
internal const val ATTACHMENT_PIECE_FIRST_BYTES = 2 * 1024
/**
 * How long one piece takes at the measured speed, and how much (in time) may be on the way, not yet acknowledged: a link that slows to a sixth of its speed
 * just after a piece went still answers the dashboard's ping within its 20 s.
 */
internal const val ATTACHMENT_PIECE_SECONDS = 3
/** How much larger than the largest piece acknowledged so far the next may be, and what may be on the way: a speed seen once is not trusted at once. */
internal const val ATTACHMENT_PIECE_GROWTH = 2
/** How much may wait in OkHttp's queue before the next piece is made: well under its 16 MiB, so a slow link never closes the socket. */
internal const val ATTACHMENT_QUEUE_BYTES = 4L * 1024 * 1024
/**
 * How long a file's socket may show no new acknowledgment, with the file still on the way, before that try is lost ([NativeGateway.attachFile]: the next try
 * goes on a new socket, and a file waiting for the one upload lock may go between). The plugin gives up on a phone that sent no piece for 60 s; this is the
 * phone's side of that, for a socket that stays open and answers pings but takes nothing (JOINBFIX-121 follow-up 1). A var only so a test can shorten it.
 */
internal var attachmentSilenceMs = 60_000L

/**
 * The file a frame carries: [field] of its params holds [prefix] and then the file's base64 (data_url: "data:<type>;base64,"; content_base64: ""), read and
 * encoded from [file] as it goes, or already encoded ([base64], a small file kept in the draft as base64).
 */
internal class InlineFile(val file: File?, val field: String, val prefix: String, val base64: String? = null) {
    init { require((file == null) != (base64 == null)) { "Invalid inline file" } }
    /** The length of the file's base64. */
    val encodedLength: Long get() = base64?.length?.toLong() ?: base64Length(file!!.length())
}

/** The size of the whole frame for [inline] with this [envelope], in bytes, without building it. */
internal fun inlineFrameBytes(envelope: JSONObject, inline: InlineFile): Long {
    val (head, tail) = inlineFrameEnds(envelope, inline)
    return head.size + inline.encodedLength + tail.size
}

private fun base64Length(bytes: Long) = (bytes + 2) / 3 * 4

/** The header piece of a file frame: PIECE_HEAD, then {"head", "tail", "body"} (the frame's text before and after the file's base64, the base64's length). */
internal fun inlineFrameHeader(envelope: JSONObject, inline: InlineFile): ByteArray {
    val (head, tail) = inlineFrameEnds(envelope, inline)
    val header = JSONObject().put("head", String(head, Charsets.UTF_8)).put("tail", String(tail, Charsets.UTF_8)).put("body", inline.encodedLength)
    return byteArrayOf(PIECE_HEAD) + header.toString().toByteArray(Charsets.UTF_8)
}

/** The frame's text before and after the file's base64: [envelope] as JSONObject writes any frame, with [InlineFile.field] added last to its params. */
private fun inlineFrameEnds(envelope: JSONObject, inline: InlineFile): Pair<ByteArray, ByteArray> {
    val params = envelope.getJSONObject("params")
    require(params.length() > 0 && !params.has(inline.field)) { "Invalid inline frame" }
    val text = envelope.toString()
    require(text.endsWith("}}")) { "Invalid inline frame" }
    val prefix = JSONObject.quote(inline.prefix).dropLast(1)
    return (text.dropLast(2) + "," + JSONObject.quote(inline.field) + ":" + prefix).toByteArray(Charsets.UTF_8) to "\"}}".toByteArray(Charsets.UTF_8)
}

/** A PIECE_ACK from the plugin as (the frame's request id, the base64 it holds); null for anything else. */
internal fun pieceAcknowledgment(bytes: ByteString): Pair<String, Long>? {
    if (bytes.size <= 9 || bytes[0] != PIECE_ACK) return null
    val held = okio.Buffer().write(bytes.substring(1, 9)).readLong()
    return if (held < 0) null else bytes.substring(9).utf8() to held
}

/**
 * Sizes a file's pieces from the plugin's acknowledgments (CHAT-121 follow-up 8). The first piece is [ATTACHMENT_PIECE_FIRST_BYTES], sent alone; then each piece
 * carries about [ATTACHMENT_PIECE_SECONDS] of the measured speed, at most [ATTACHMENT_PIECE_GROWTH] times the largest piece acknowledged so far (at least the
 * minimum, at most [cap]), and a piece goes only while what is on the way, with it, stays within both (or nothing is on the way). The speed is the acknowledged
 * bytes over the time since the last acknowledgment, or since a piece went into an empty pipe; a lower reading is taken at once, a higher one halfway. [first]
 * is the first piece's size: smaller for a try after a lost one ([nextTry]). Open for a test's fixed sizing.
 */
internal open class PieceSizer(cap: Int = ATTACHMENT_PIECE_BYTES, private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    first: Int = ATTACHMENT_PIECE_FIRST_BYTES) {
    val cap = cap.coerceIn(ATTACHMENT_PIECE_MIN_BYTES, ATTACHMENT_PIECE_BYTES)
    /** The first piece, sent alone before any speed is known. */
    val first = first.coerceIn(ATTACHMENT_PIECE_MIN_BYTES, ATTACHMENT_PIECE_FIRST_BYTES)
    /** The upload speed the acknowledgments show, in bytes a second; 0 before the first. */
    var speed = 0.0
        private set
    /** The largest piece sent. */
    var largest = 0
        private set
    private var since = 0L
    private var counted = 0L
    /** The largest piece acknowledged whole, and the pieces on the way (where each ends in the base64, its size). */
    private var proven = 0
    private var total = 0L
    private val onTheWay = ArrayDeque<Pair<Long, Int>>()

    /** How much may be on the way, the next piece with it. */
    private fun room(): Double =
        minOf(speed * ATTACHMENT_PIECE_SECONDS, maxOf(ATTACHMENT_PIECE_MIN_BYTES, ATTACHMENT_PIECE_GROWTH * proven).toDouble())

    /** The size of the next piece. */
    open fun next(): Int = if (speed <= 0.0) minOf(first, cap)
        else room().toLong().coerceIn(ATTACHMENT_PIECE_MIN_BYTES.toLong(), cap.toLong()).toInt()

    /** Whether a piece of [size] may go with [unacked] bytes on the way. */
    open fun fits(unacked: Long, size: Int): Boolean = unacked == 0L || unacked + size <= room()

    fun sent(size: Int, unacked: Long) {
        largest = maxOf(largest, size)
        if (unacked == 0L) since = now()
        total += size
        onTheWay.addLast(total to size)
    }

    fun acked(total: Long) {
        if (total <= counted) return
        while (onTheWay.isNotEmpty() && onTheWay.first().first <= total) proven = maxOf(proven, onTheWay.removeFirst().second)
        val at = now()
        val reading = (total - counted) * 1000.0 / maxOf(1L, at - since)
        speed = if (speed <= 0.0 || reading < speed) reading else (speed + reading) / 2
        counted = total
        since = at
    }

    /**
     * The sizing of the next try after this one was lost (its socket lost, or the plugin gave up on it). It starts again from a small piece, a quarter of
     * this try's largest (at least the minimum, at most a first try's first piece), then grows as a first try does, only as far as its own
     * acknowledgments show the link carries: so each try has no more on the way than the link has just shown it takes, and a retry on a fast link still
     * goes at the link's speed (CHAT-121 follow-up 10; follow-up 9 capped the whole retry at that quarter, about 2 KiB a round trip after an early loss).
     * A try that sent no piece (its socket never opened) leaves the next one's start as it was.
     */
    fun nextTry(): PieceSizer = PieceSizer(cap, now, if (largest > 0) maxOf(ATTACHMENT_PIECE_MIN_BYTES, largest / 4) else first)
}

/**
 * Streams the frame for [inline] to [send]: its header (PIECE_HEAD), then the file's base64 in pieces [sizer] sizes (each led by PIECE_MORE, the last by
 * PIECE_LAST), reading and encoding the file as it goes. [acked] is how much of the base64 the plugin says it holds. Before each piece it waits ([pause])
 * while [queued] says more than [queueBytes] is still in OkHttp's queue, or while [sizer] says enough is on the way, and only then sizes the piece, from the
 * latest acknowledgment; after the last piece, it waits until the plugin holds it all, so the answer's wait starts once the file is there. Returns false when [send] refused a piece, or when [answered] says the request was
 * answered before its last piece (the plugin answers "busy" at the header when it cannot take the file now, "stalled" when it gave up on it); the caller
 * then ends that file's socket (the plugin drops what it held). Throws when the file changed size while it was read, or what [pause] throws (a connection that failed).
 */
internal suspend fun streamInlineFrame(envelope: JSONObject, inline: InlineFile, send: (ByteString) -> Boolean, queued: () -> Long,
    pause: suspend () -> Unit, acked: () -> Long, answered: () -> Boolean = { false }, sizer: PieceSizer = PieceSizer(),
    queueBytes: Long = ATTACHMENT_QUEUE_BYTES): Boolean {
    val total = inline.encodedLength
    val piece = ByteArray(1 + sizer.cap)
    var target = 0
    var filled = 1
    var sent = 0L
    fun held(): Long = minOf(acked(), sent).also(sizer::acked)
    // Waits until the next piece may go, then sizes it.
    suspend fun room(): Boolean {
        while (true) {
            if (answered()) return false
            val unacked = sent - held()
            val size = sizer.next()
            if (queued() <= queueBytes && sizer.fits(unacked, size)) { target = size; return true }
            pause()
        }
    }
    fun emit(flag: Byte): Boolean {
        if (answered()) return false
        val size = filled - 1
        piece[0] = flag
        sizer.sent(size, sent - held())
        if (!send(piece.copyOf(filled).toByteString())) return false
        sent += size
        filled = 1
        return true
    }
    suspend fun write(bytes: ByteArray, count: Int): Boolean {
        var at = 0
        while (at < count) {
            if (filled - 1 >= target) { if (!emit(PIECE_MORE) || !room()) return false }
            val n = minOf(count - at, 1 + target - filled)
            System.arraycopy(bytes, at, piece, filled, n)
            filled += n; at += n
        }
        return true
    }
    if (!send(inlineFrameHeader(envelope, inline).toByteString())) return false
    if (!room()) return false
    val encoded = inline.base64
    if (encoded != null) {
        val chunk = ByteArray(64 * 1024)
        var at = 0
        while (at < encoded.length) {
            val n = minOf(chunk.size, encoded.length - at)
            for (i in 0 until n) chunk[i] = encoded[at + i].code.toByte()
            if (!write(chunk, n)) return false
            at += n
        }
    } else {
        val file = inline.file!!
        val expected = file.length()
        val encoder = java.util.Base64.getEncoder()
        val chunk = ByteArray(3 * 64 * 1024)
        val out = ByteArray(4 * 64 * 1024)
        var read = 0L
        file.inputStream().use { input ->
            while (true) {
                val n = readUpTo(input, chunk)
                if (n <= 0) break
                read += n
                val count = if (n == chunk.size) encoder.encode(chunk, out) else encoder.encode(chunk.copyOf(n), out)
                if (!write(out, count)) return false
                if (n < chunk.size) break
            }
        }
        require(read == expected) { "The file changed while it was sent" }
    }
    if (!emit(PIECE_LAST)) return false
    while (held() < total) { if (answered()) return true; pause() }
    return true
}

/** Fills [buffer] from [input] unless the file ends first; the count read. */
private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
    var at = 0
    while (at < buffer.size) {
        val n = input.read(buffer, at, buffer.size - at)
        if (n < 0) break
        at += n
    }
    return at
}

/** The raw file size whose frame fits [frameBytes]: base64 is 4/3 of it, and the envelope, name and type stay under 1 KiB plus 6 bytes a name character. */
internal fun rawBytesForFrame(frameBytes: Long, name: String, mime: String): Long =
    ((frameBytes - 1024 - name.length * 6L - mime.length * 2L).coerceAtLeast(0) / 4) * 3
