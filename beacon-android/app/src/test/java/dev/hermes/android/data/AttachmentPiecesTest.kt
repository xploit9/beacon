package dev.hermes.android.data

import dev.hermes.android.core.json
import kotlinx.coroutines.runBlocking
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * CHAT-121 follow-ups 4 and 6: a large file goes to the plugin in pieces, read and encoded from its kept copy as it goes, so the phone never holds the file's
 * base64 or the frame whole. The first piece is a header with the frame's text around the base64 and the base64's length; the rest is the base64 alone. The
 * header's text with the pieces between is exactly the frame Hermes reads.
 */
class AttachmentPiecesTest {
    private fun synthetic(size: Int) = File.createTempFile("synthetic", ".pdf").apply { writeBytes(ByteArray(size) { (it * 31 % 251).toByte() }); deleteOnExit() }
    private fun envelope(params: JSONObject) = json("jsonrpc" to "2.0", "id" to "id-1", "method" to "file.attach", "params" to params)
    /** The frame the plugin passes on: the header's head, the base64 pieces, the header's tail (canonical_transport.HeldFile). */
    private fun reassembled(pieces: List<ByteString>): String {
        assertEquals(PIECE_HEAD, pieces.first()[0])
        val header = JSONObject(pieces.first().substring(1).utf8())
        assertEquals(setOf("head", "tail", "body"), header.keys().asSequence().toSet())
        assertEquals(listOf(PIECE_MORE), pieces.drop(1).dropLast(1).map { it[0] }.distinct().ifEmpty { listOf(PIECE_MORE) })
        assertEquals(PIECE_LAST, pieces.last()[0])
        val body = pieces.drop(1).fold(okio.Buffer()) { buffer, piece -> buffer.write(piece.substring(1)) }.readUtf8()
        assertEquals(header.getLong("body"), body.length.toLong())
        assertTrue("only base64 between the head and the tail", body.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "+/=" })
        return header.getString("head") + body + header.getString("tail")
    }

    @Test fun thePiecesAreTheWholeFrameAndEachIsSmall() = runBlocking {
        val file = synthetic(3 * 1024 * 1024 + 7)
        val inline = InlineFile(file, "data_url", "data:application/pdf;base64,")
        val frame = envelope(json("session_id" to "s", "name" to "ملف تجريبي \"draft\".pdf", "profile" to "default"))
        val pieces = mutableListOf<ByteString>()
        assertTrue(streamInlineFrame(frame, inline, send = { pieces += it; true }, queued = { 0 }, pause = {}, acked = { Long.MAX_VALUE }, sizer = PieceSizer(256 * 1024)))
        assertTrue(pieces.drop(1).all { it.size <= 1 + 256 * 1024 })
        val whole = reassembled(pieces)
        assertEquals(whole.toByteArray().size.toLong(), inlineFrameBytes(frame, inline))
        val params = JSONObject(whole).getJSONObject("params")
        assertEquals("data:application/pdf;base64," + Base64.getEncoder().encodeToString(file.readBytes()), params.getString("data_url"))
        assertEquals("ملف تجريبي \"draft\".pdf", params.getString("name"))
        assertEquals("id-1", JSONObject(whole).getString("id"))
    }

    @Test fun anImageGoesAsItsOwnBytesField() = runBlocking {
        val file = synthetic(70_000)
        val pieces = mutableListOf<ByteString>()
        val frame = json("jsonrpc" to "2.0", "id" to "id-2", "method" to "image.attach_bytes", "params" to json("session_id" to "s", "filename" to "photo.jpg"))
        assertTrue(streamInlineFrame(frame, InlineFile(file, "content_base64", ""), send = { pieces += it; true }, queued = { 0 }, pause = {}, acked = { Long.MAX_VALUE }))
        val whole = reassembled(pieces)
        assertEquals(Base64.getEncoder().encodeToString(file.readBytes()), JSONObject(whole).getJSONObject("params").getString("content_base64"))
    }

    @Test fun itWaitsWhileOkHttpsQueueIsFullAndStopsWhenAPieceIsRefused() = runBlocking {
        val file = synthetic(600_000)
        val frame = envelope(json("session_id" to "s", "name" to "a.pdf"))
        var waiting = 3
        var pauses = 0
        assertTrue(streamInlineFrame(frame, InlineFile(file, "data_url", "data:application/pdf;base64,"), send = { true },
            queued = { if (waiting-- > 0) 10L * 1024 * 1024 else 0L }, pause = { pauses++ }, acked = { Long.MAX_VALUE }, sizer = PieceSizer(100_000)))
        assertEquals(3, pauses)
        var sent = 0
        assertFalse(streamInlineFrame(frame, InlineFile(file, "data_url", "data:application/pdf;base64,"), send = { ++sent < 3 }, queued = { 0 }, pause = {}, acked = { Long.MAX_VALUE }, sizer = PieceSizer(100_000)))
        assertEquals("it stops at the refused piece", 3, sent)
    }

    /** The plugin answers "busy" at the header when it cannot take the file now: the phone stops there and drops what it sent, instead of streaming the rest. */
    @Test fun itStopsOnceTheRequestIsAnswered() = runBlocking {
        val file = synthetic(600_000)
        val frame = envelope(json("session_id" to "s", "name" to "a.pdf"))
        val pieces = mutableListOf<ByteString>()
        assertFalse(streamInlineFrame(frame, InlineFile(file, "data_url", "data:application/pdf;base64,"), send = { pieces += it; true }, queued = { 0 }, pause = {},
            answered = { pieces.size >= 2 }, acked = { Long.MAX_VALUE }, sizer = PieceSizer(100_000)))
        assertEquals(listOf(PIECE_HEAD, PIECE_MORE), pieces.map { it[0] })
    }

    @Test fun aFileThatChangesWhileItIsSentIsNotSentAsIfWhole() {
        val file = synthetic(500_000)
        val frame = envelope(json("session_id" to "s", "name" to "a.pdf"))
        val failure = runCatching { runBlocking {
            streamInlineFrame(frame, InlineFile(file, "data_url", "data:application/pdf;base64,"), send = { file.appendBytes(ByteArray(10)); true }, queued = { 0 }, pause = {}, acked = { Long.MAX_VALUE }, sizer = PieceSizer(100_000))
        } }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalArgumentException)
    }

    @Test fun theLimitsAreWhatTheConnectionAndHermesGive() {
        // A 1.2.0 plugin, or no plugin: one text frame of 8 MiB, about 6.19 MB of file (a 6,187,008-byte PDF went live in follow-up 3).
        val old = AttachmentLimits()
        assertFalse(old.pieces)
        val textCap = attachmentLimitBytes(old, "Synthetic report 2026.pdf", "application/pdf")
        assertTrue("$textCap", textCap in 6_187_008L..6_220_000L)
        assertEquals("6", megabytes(textCap))
        // A follow-up-8 plugin: pieces in this phone's form, Hermes' own caps (256 MiB a file, 25 MiB an image) and the phone's own.
        val new = advertisedAttachmentLimits(json("attach_frame_limit" to 384L * 1024 * 1024, "attach_pieces" to ATTACHMENT_PIECES_FORM,
            "attach_file_limit" to 256L * 1024 * 1024, "attach_image_limit" to 25L * 1024 * 1024))
        assertTrue(new.pieces)
        // A plugin whose pieces have another form (follow-up 4's had no header, and named none): one text frame, as with a 1.2.0 plugin.
        for (form in listOf(null, 1, 2, "3")) {
            val other = advertisedAttachmentLimits(json("attach_frame_limit" to 384L * 1024 * 1024, "attach_pieces" to form, "attach_file_limit" to 256L * 1024 * 1024))
            assertFalse("$form", other.pieces)
            assertEquals("$form", attachmentLimitBytes(old, "a.pdf", "application/pdf"), attachmentLimitBytes(other, "a.pdf", "application/pdf"))
        }
        assertEquals(minOf(PHONE_ATTACHMENT_MAX_BYTES, 256L * 1024 * 1024), attachmentLimitBytes(new, "a.pdf", "application/pdf"))
        assertEquals(25L * 1024 * 1024, attachmentLimitBytes(new, "a.jpg", "image/jpeg"))
        // Without Hermes' caps, what one frame carries; anything malformed or out of range is not given.
        assertEquals(rawBytesForFrame(64L * 1024 * 1024, "a.pdf", "application/pdf"),
            attachmentLimitBytes(advertisedAttachmentLimits(json("attach_frame_limit" to 64L * 1024 * 1024, "attach_pieces" to ATTACHMENT_PIECES_FORM, "attach_file_limit" to JSONObject.NULL)), "a.pdf", "application/pdf"))
        assertEquals(AttachmentLimits(), advertisedAttachmentLimits(json("attach_frame_limit" to "big", "attach_pieces" to ATTACHMENT_PIECES_FORM, "attach_file_limit" to -1)))
        assertEquals(AttachmentLimits(), advertisedAttachmentLimits(json("attach_frame_limit" to 1024, "attach_pieces" to ATTACHMENT_PIECES_FORM)))
        assertNull(advertisedAttachmentLimits(json("attach_frame_limit" to 64L * 1024 * 1024, "attach_pieces" to ATTACHMENT_PIECES_FORM, "attach_image_limit" to (2L shl 30))).imageBytes)
    }

    /** The plugin's acknowledgment as canonical_transport writes it (test_relay_file_frames reads the same bytes): the request id and the base64 held. */
    @Test fun theAcknowledgmentIsReadAsThePluginWritesIt() {
        assertEquals("f1" to 16384L, pieceAcknowledgment("0400000000000040006631".decodeHex()))
        assertNull(pieceAcknowledgment("0300000000000040006631".decodeHex()))
        assertNull(pieceAcknowledgment("040000000000004000".decodeHex()))
        assertNull(pieceAcknowledgment("04ff000000000040006631".decodeHex()))
    }

    /** A small file kept in the draft as base64 goes the same way through a plugin that acknowledges pieces, starting with the smallest piece. */
    @Test fun aSmallFileKeptAsBase64GoesInPiecesToo() = runBlocking {
        val file = synthetic(40_000)
        val encoded = Base64.getEncoder().encodeToString(file.readBytes())
        val frame = envelope(json("session_id" to "s", "name" to "a.pdf"))
        val inline = InlineFile(null, "data_url", "data:application/pdf;base64,", encoded)
        val pieces = mutableListOf<ByteString>()
        assertTrue(streamInlineFrame(frame, inline, send = { pieces += it; true }, queued = { 0 }, pause = {}, acked = { Long.MAX_VALUE }))
        val whole = reassembled(pieces)
        assertEquals("data:application/pdf;base64,$encoded", JSONObject(whole).getJSONObject("params").getString("data_url"))
        assertEquals(whole.toByteArray().size.toLong(), inlineFrameBytes(frame, inline))
        assertEquals(1 + ATTACHMENT_PIECE_FIRST_BYTES, pieces[1].size)
    }

    /**
     * A phone's upload link, as follow-up 8 measured it on Hermes' own server (fu8_measure_link.py), on a fake clock ([pause] moves it on 10 ms). Everything
     * the phone sends is taken at once (OkHttp's queue and the system's buffer) and arrives in order at the link's speed: [rates] says from which millisecond
     * it runs at how many bytes a second. The plugin takes a piece when all of it has come and acknowledges it half a round trip later; it gives up on a file
     * whose next piece has not come in 60 s. Hermes' dashboard pings every 20 s and closes the socket when the pong, which waits behind everything the phone
     * already sent, is over 20 s late. [anyPhasePong] is how late the pong would be with a ping at the worst moment, just after a piece went. [lostAt] is
     * when the socket is lost (a link that drops: the try ends there).
     */
    private class SlowLink(private val rates: List<Pair<Double, Double>>, private val roundTripMs: Long = 200, private val lostAt: Long = Long.MAX_VALUE) {
        constructor(bytesPerSecond: Double) : this(listOf(0.0 to bytesPerSecond))
        var now = 0L
            private set
        var anyPhasePong = 0.0
            private set
        private var free = 0.0
        private val arrivals = ArrayDeque<Pair<Double, ByteString>>()
        private val acks = ArrayDeque<Pair<Double, Long>>()
        var held = 0L
            private set
        private var taken = 0L
        val body = okio.Buffer()
        private var open = false
        private var lastPiece = 0.0
        var longestWait = 0.0
            private set
        var latestPong = 0.0
            private set
        private var nextPing = 20_000.0
        private var closeAt = Double.MAX_VALUE
        var failure: String? = null
            private set
        var largest = 0
            private set

        /** When [bytes] that start through the link at [start] have all gone. */
        private fun through(start: Double, bytes: Int): Double {
            var at = start
            var left = bytes.toDouble()
            var i = rates.indexOfLast { it.first <= at }
            while (true) {
                val rate = rates[i].second
                val end = rates.getOrNull(i + 1)?.first ?: Double.MAX_VALUE
                if (at + left * 1000.0 / rate <= end) return at + left * 1000.0 / rate
                left -= (end - at) * rate / 1000.0
                at = end; i++
            }
        }

        fun send(message: ByteString): Boolean {
            free = through(maxOf(now.toDouble(), free), message.size)
            anyPhasePong = maxOf(anyPhasePong, through(free, 6) + roundTripMs - now)
            arrivals.addLast(free + roundTripMs / 2.0 to message)
            if (message[0] != PIECE_HEAD) largest = maxOf(largest, message.size - 1)
            return true
        }

        fun pause() {
            val to = now + 10.0
            while (true) {
                val arrival = arrivals.firstOrNull()?.first
                if (nextPing <= to && (arrival == null || nextPing <= arrival)) { ping(nextPing); nextPing += 20_000.0; continue }
                if (arrival != null && arrival <= to) { take(arrivals.removeFirst()); continue }
                break
            }
            now = to.toLong()
            while (acks.isNotEmpty() && acks.first().first <= now) held = acks.removeFirst().second
            if (failure == null && now >= closeAt) failure = "the dashboard closed the socket: keepalive ping timeout"
            if (failure == null && now >= lostAt) failure = "the file's socket was lost"
            if (failure == null && open && now - lastPiece > 60_000) failure = "the plugin gave up on the file: stalled"
            failure?.let { throw java.io.IOException(it) }
        }

        private fun ping(at: Double) {
            val pong = through(maxOf(at + roundTripMs / 2.0, free), 6)
            free = pong
            val late = pong + roundTripMs / 2.0 - at
            latestPong = maxOf(latestPong, late)
            if (late > 20_000) closeAt = minOf(closeAt, at + 20_000)
        }

        private fun take(arrival: Pair<Double, ByteString>) {
            val (at, message) = arrival
            when (message[0]) {
                PIECE_HEAD -> { open = true; lastPiece = at; taken = 0 }
                PIECE_MORE, PIECE_LAST -> {
                    if (open && at - lastPiece > 60_000 && failure == null) failure = "the plugin gave up on the file: stalled"
                    longestWait = maxOf(longestWait, at - lastPiece)
                    lastPiece = at
                    body.write(message.substring(1))
                    taken += message.size - 1
                    acks.addLast(at + roundTripMs / 2.0 to taken)
                    if (message[0] == PIECE_LAST) open = false
                }
            }
        }
    }

    private fun sendOver(link: SlowLink, file: File, sizer: PieceSizer): Boolean = runBlocking {
        streamInlineFrame(envelope(json("session_id" to "s", "name" to "a.pdf")), InlineFile(file, "data_url", "data:application/pdf;base64,"),
            send = link::send, queued = { 0 }, pause = { link.pause() }, acked = { link.held }, sizer = sizer)
    }

    /**
     * CHAT-121 follow-up 8: a 1 MiB file finishes on a 2 KB/s link (in about 12 minutes): every piece comes well within the plugin's 60 s and every pong well
     * within the dashboard's 20 s. chat-121-8's pieces (1 MiB, sent as fast as OkHttp takes them) on the same link have the socket closed at 40 s, as measured
     * on the real server. On a fast link the pieces grow to the most one carries.
     */
    @Test fun aMegabyteFileFinishesOnATwoKilobyteASecondLink() {
        val file = synthetic(1024 * 1024)
        val link = SlowLink(2000.0)
        assertTrue(sendOver(link, file, PieceSizer(now = { link.now })))
        assertNull(link.failure)
        assertEquals(Base64.getEncoder().encodeToString(file.readBytes()), link.body.readUtf8())
        val seconds = link.now / 1000.0
        assertTrue("took $seconds s", seconds in 699.0..800.0)
        assertTrue("longest wait for a piece ${link.longestWait} ms", link.longestWait < 15_000)
        assertTrue("latest pong ${link.latestPong} ms, at the worst moment ${link.anyPhasePong} ms", link.anyPhasePong < 15_000)
        assertTrue("largest piece ${link.largest}", link.largest in ATTACHMENT_PIECE_MIN_BYTES..24 * 1024)

        val control = SlowLink(2000.0)
        val fixed = object : PieceSizer(now = { control.now }) {
            override fun next() = ATTACHMENT_PIECE_BYTES
            override fun fits(unacked: Long, size: Int) = true
        }
        val failure = runCatching { sendOver(control, file, fixed) }.exceptionOrNull()
        assertEquals("the dashboard closed the socket: keepalive ping timeout", failure?.message)
        assertTrue("closed at ${control.now} ms", control.now in 40_000L..40_100L)

        val big = synthetic(9 * 1024 * 1024)
        val fast = SlowLink(1_000_000.0)
        assertTrue(sendOver(fast, big, PieceSizer(now = { fast.now })))
        assertEquals(ATTACHMENT_PIECE_BYTES, fast.largest)
        assertTrue("took ${fast.now} ms", fast.now < 18_000)
        println("2 KB/s: ${seconds} s, longest wait ${link.longestWait} ms, latest pong ${link.latestPong} ms (worst moment ${link.anyPhasePong} ms), " +
            "largest piece ${link.largest}; fixed 1 MiB pieces: ${failure?.message} at ${control.now} ms; 1 MB/s: 9 MiB in ${fast.now} ms, largest piece ${fast.largest}")
    }

    /**
     * CHAT-121 follow-up 8: a link that slows down mid-file still takes it whole, with every pong in time even for a ping at the worst moment: to a fifth
     * (10 KB/s to 2 KB/s, and 50 KB/s to 10 KB/s), and to a twenty-fifth after a fast start (50 KB/s for 2 s, then 2 KB/s): the pieces never outgrow what
     * the acknowledgments have shown, and only about 3 s of the measured speed is on the way at once.
     */
    @Test fun aFileStillFinishesWhenTheLinkSlowsDownMidFile() {
        for ((name, rates, size) in listOf(
            Triple("10 KB/s, 2 KB/s from 60 s", listOf(0.0 to 10_000.0, 60_000.0 to 2000.0), 1024 * 1024),
            Triple("50 KB/s, 10 KB/s from 20 s", listOf(0.0 to 50_000.0, 20_000.0 to 10_000.0), 4 * 1024 * 1024),
            Triple("50 KB/s for 2 s, then 2 KB/s", listOf(0.0 to 50_000.0, 2_000.0 to 2000.0), 1024 * 1024),
        )) {
            val file = synthetic(size)
            val link = SlowLink(rates)
            assertTrue(name, sendOver(link, file, PieceSizer(now = { link.now })))
            assertNull(name, link.failure)
            assertEquals(name, Base64.getEncoder().encodeToString(file.readBytes()), link.body.readUtf8())
            assertTrue("$name: a pong at the worst moment ${link.anyPhasePong} ms", link.anyPhasePong < 20_000)
            assertTrue("$name: longest wait for a piece ${link.longestWait} ms", link.longestWait < 20_000)
            println("$name: ${link.now / 1000.0} s, worst-moment pong ${link.anyPhasePong} ms, longest wait ${link.longestWait} ms, largest piece ${link.largest}")
        }
    }

    /**
     * CHAT-121 follow-up 9: a phone's upload is one queue for all its connections, so a pong on the chat's socket waits behind what the file has on the way
     * as much as one on the file's own socket does (this model's [SlowLink.anyPhasePong]). On a 0.5 KB/s link a 64 KiB file still goes, a small piece at a
     * time, with a pong at the worst moment well inside the dashboard's 20 s; follow-up 8's 16 KiB pieces put 32 s ahead of it and had the socket closed.
     */
    @Test fun onAHalfKilobyteASecondLinkWhatTheFileHasOnTheWayNeverHoldsAPongPastTheDashboardsLimit() {
        val file = synthetic(64 * 1024)
        val link = SlowLink(500.0)
        assertTrue(sendOver(link, file, PieceSizer(now = { link.now })))
        assertNull(link.failure)
        assertEquals(Base64.getEncoder().encodeToString(file.readBytes()), link.body.readUtf8())
        assertTrue("a pong at the worst moment ${link.anyPhasePong} ms", link.anyPhasePong < 8_000)
        assertTrue("largest piece ${link.largest}", link.largest <= ATTACHMENT_PIECE_FIRST_BYTES)
        val control = SlowLink(500.0)
        val followUp8 = object : PieceSizer(now = { control.now }) { override fun next() = maxOf(16 * 1024, super.next()) }
        val failure = runCatching { sendOver(control, file, followUp8) }.exceptionOrNull()
        assertEquals("the dashboard closed the socket: keepalive ping timeout", failure?.message)
        println("0.5 KB/s, 64 KiB: ${link.now / 1000.0} s, worst-moment pong ${link.anyPhasePong} ms, longest wait ${link.longestWait} ms, largest piece " +
            "${link.largest}; 16 KiB pieces: ${failure?.message} at ${control.now} ms")
    }

    /**
     * CHAT-121 follow-up 10: a file whose first try was lost early (its socket gone 300 ms in, after a piece or two) goes again from a small piece and grows
     * as a first try does, as far as the retry's own acknowledgments show: on a fast link it finishes about as fast as a first try on the same link.
     * Follow-up 9's retry was held to a quarter of the lost try's largest piece, here 1 KiB, so it moved about 2 KiB a round trip. On a 0.5 KB/s link the
     * retry's pieces stay as small as a first try's, and a pong at the worst moment stays well inside the dashboard's 20 s.
     */
    @Test fun aFileWhoseFirstTryWasLostEarlyStillGoesAtTheLinksSpeed() {
        for ((name, rate, size) in listOf(Triple("1 MB/s", 1_000_000.0, 4 * 1024 * 1024), Triple("100 KB/s", 100_000.0, 1024 * 1024),
            Triple("0.5 KB/s", 500.0, 32 * 1024))) {
            val file = synthetic(size)
            val whole = Base64.getEncoder().encodeToString(file.readBytes())
            val clean = SlowLink(listOf(0.0 to rate))
            assertTrue(name, sendOver(clean, file, PieceSizer(now = { clean.now })))
            var clock: () -> Long = { 0L }
            val lost = SlowLink(listOf(0.0 to rate), lostAt = 300)
            clock = { lost.now }
            val first = PieceSizer(now = { clock() })
            assertEquals(name, "the file's socket was lost", runCatching { sendOver(lost, file, first) }.exceptionOrNull()?.message)
            val retry = SlowLink(listOf(0.0 to rate))
            clock = { retry.now }
            val next = first.nextTry()
            assertTrue("$name: the retry starts small (${next.next()} after a largest of ${first.largest})",
                next.next() <= minOf(ATTACHMENT_PIECE_FIRST_BYTES, maxOf(ATTACHMENT_PIECE_MIN_BYTES, first.largest / 4)))
            assertTrue(name, sendOver(retry, file, next))
            assertNull(name, retry.failure)
            assertEquals(name, whole, retry.body.readUtf8())
            assertTrue("$name: the retry took ${retry.now} ms, a first try on a clean link ${clean.now} ms", retry.now <= clean.now * 1.1 + 500)
            assertTrue("$name: a pong at the worst moment ${retry.anyPhasePong} ms", retry.anyPhasePong < 8_000)
            // Follow-up 9's retry, for the record: a quarter of the lost try's largest piece was its cap.
            val held = SlowLink(listOf(0.0 to rate))
            clock = { held.now }
            assertTrue(name, sendOver(held, file, PieceSizer(maxOf(ATTACHMENT_PIECE_MIN_BYTES, first.largest / 4), now = { clock() })))
            if (rate >= 100_000.0) assertTrue("$name: follow-up 9's retry ${held.now} ms", held.now > 5 * retry.now)
            println("$name, ${size / 1024} KiB: first try on a clean link ${clean.now} ms; lost at 300 ms (largest piece ${first.largest}); retry ${retry.now} ms, " +
                "largest piece ${retry.largest}, worst-moment pong ${retry.anyPhasePong} ms; follow-up 9's retry ${held.now} ms, largest piece ${held.largest}")
        }
    }
}
