package dev.hermes.android.agentphone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire format against L2's CONTRACT.md §2 and agent_phone_stream.parse_client: exact app messages, lenient server reading. */
class ViewProtocolTest {
    private fun frame(seq: Long, width: Int, height: Int, rotation: Int, type: Int = 1, jpeg: ByteArray = byteArrayOf(-1, -40, -1)): ByteArray =
        byteArrayOf(type.toByte(), (seq ushr 24).toByte(), (seq ushr 16).toByte(), (seq ushr 8).toByte(), seq.toByte(),
            (width ushr 8).toByte(), width.toByte(), (height ushr 8).toByte(), height.toByte(), rotation.toByte()) + jpeg

    @Test fun theFrameHeaderIsReadBigEndian() {
        assertEquals(FrameHeader(0xFFFFFFFEL, 720, 1600, 1), readFrameHeader(frame(0xFFFFFFFEL, 720, 1600, 1)))
        assertEquals(FrameHeader(7, 1600, 720, 3), readFrameHeader(frame(7, 1600, 720, 7)))
    }

    @Test fun anythingThatIsNotAFrameIsIgnored() {
        assertNull(readFrameHeader(frame(1, 720, 1600, 0, type = 2)))
        assertNull(readFrameHeader(frame(1, 0, 1600, 0)))
        assertNull(readFrameHeader(frame(1, 720, 1600, 0, jpeg = ByteArray(0))))
        assertNull(readFrameHeader(ByteArray(4)))
    }

    private fun keys(message: String) = JSONObject(message).keys().asSequence().toSet()

    @Test fun everyAppMessageHasExactlyTheFieldsTheServerChecks() {
        assertEquals(setOf("t", "seq"), keys(ClientMessage.ack(5)))
        assertEquals(setOf("t", "points"), keys(ClientMessage.touch(listOf(TouchPoint(0, .5, .5, "down")))))
        assertEquals(setOf("id", "x", "y", "phase"), JSONObject(ClientMessage.touch(listOf(TouchPoint(3, .1, .9, "up")))).getJSONArray("points").getJSONObject(0).keys().asSequence().toSet())
        assertEquals(setOf("t", "key"), keys(ClientMessage.key("recents")))
        assertEquals(setOf("t", "text"), keys(ClientMessage.text("Hi!")))
        assertEquals(setOf("t", "text"), keys(ClientMessage.paste("مرحبا")))
        assertEquals(setOf("t", "level"), keys(ClientMessage.quality(true)))
        for (bare in listOf(ClientMessage.refresh(), ClientMessage.background(), ClientMessage.foreground(), ClientMessage.done())) assertEquals(setOf("t"), keys(bare))
        assertEquals("low", JSONObject(ClientMessage.quality(true)).getString("level"))
        assertEquals("normal", JSONObject(ClientMessage.quality(false)).getString("level"))
        assertEquals(listOf("done", "background", "foreground", "refresh"), listOf(ClientMessage.done(), ClientMessage.background(), ClientMessage.foreground(), ClientMessage.refresh()).map { JSONObject(it).getString("t") })
    }

    @Test fun ackSequenceStaysAnUnsigned32BitNumber() {
        assertEquals(0xFFFFFFFFL, JSONObject(ClientMessage.ack(0xFFFFFFFFL)).getLong("seq"))
        assertEquals(0L, JSONObject(ClientMessage.ack(0x1_0000_0000L)).getLong("seq"))
    }

    @Test fun touchPointsAreFractionsOfTheFrameWithinZeroAndOne() {
        val points = JSONObject(ClientMessage.touch(listOf(TouchPoint(0, -0.2, 1.7, "down"), TouchPoint(1, 0.123456, 0.5, "move"), TouchPoint(2, Double.NaN, .5, "up")))).getJSONArray("points")
        assertEquals(0.0, points.getJSONObject(0).getDouble("x"), 0.0)
        assertEquals(1.0, points.getJSONObject(0).getDouble("y"), 0.0)
        assertEquals(0.1235, points.getJSONObject(1).getDouble("x"), 1e-9)
        assertEquals(0.0, points.getJSONObject(2).getDouble("x"), 0.0)
    }

    @Test fun badTouchesAreNeverBuilt() {
        for (bad in listOf(emptyList(), List(11) { TouchPoint(it % 10, .5, .5, "down") }, listOf(TouchPoint(1, .5, .5, "down"), TouchPoint(1, .4, .4, "move")),
                listOf(TouchPoint(10, .5, .5, "down")), listOf(TouchPoint(0, .5, .5, "cancel")))) {
            assertTrue(runCatching { ClientMessage.touch(bad) }.isFailure)
        }
        assertTrue(runCatching { ClientMessage.key("power") }.isFailure)
    }

    @Test fun textLimitsCountCodePointsAsTheServerDoes() {
        val emoji = "😀"
        assertTrue(validText(emoji.repeat(500), 500))
        assertFalse(validText(emoji.repeat(501), 500))
        assertFalse(validText("", 500))
        assertFalse(validText("a\u0000b", 500))
        assertTrue(runCatching { ClientMessage.text("x".repeat(501)) }.isFailure)
        assertTrue(runCatching { ClientMessage.paste("x".repeat(10_000)) }.isSuccess)
    }

    @Test fun longTextIsCutBetweenCharactersNeverInsideOne() {
        val family = "👨‍👩‍👧" // one character, five code points
        val pieces = chunkText("ab" + family + "cd", 4)
        assertEquals("ab$family" + "cd", pieces.joinToString(""))
        assertTrue(pieces.none { it.contains(family) && it != family && it.codePointCount(0, it.length) > 5 })
        assertTrue(pieces.all { piece -> piece == family || piece.codePointCount(0, piece.length) <= 4 })
        assertEquals(listOf("ab"), chunkText("a\u0000b", 500))
        assertEquals(emptyList<String>(), chunkText("\u0000", 500))
        val long = chunkText("é".repeat(1200), 500)
        assertEquals(listOf(500, 500, 200), long.map { it.codePointCount(0, it.length) })
    }

    @Test fun printableAsciiIsWhatTheServerTypesAsKeys() {
        assertTrue(isPrintableAscii("user${'@'}example.com!~ "))
        assertFalse(isPrintableAscii("café"))
        assertFalse(isPrintableAscii("tab\there"))
        assertFalse(isPrintableAscii(""))
    }

    @Test fun serverMessagesAreReadLenientlySoTheServerCanAddFields() {
        assertEquals(ServerText.State(true, 1, "ready", null), parseServerText("""{"t":"state","keyboard":true,"rotation":1,"helper":"ready","lease":"you","later":42}"""))
        assertEquals(ServerText.State(false, 0, "off", "usb_debugging"), parseServerText("""{"t":"state","keyboard":false,"rotation":0,"helper":"broken","lease":"you","prompt":"usb_debugging"}"""))
        assertEquals(ServerText.State(false, 0, "starting", null), parseServerText("""{"t":"state","helper":"starting","prompt":null}"""))
        assertEquals(ServerText.Refused("paste", "helper"), parseServerText("""{"t":"refused","what":"paste","reason":"helper"}"""))
        assertEquals(ServerText.Notice("saved", "Saved: Kanto Foods · k${'@'}example.com"), parseServerText("""{"t":"notice","kind":"saved","line":"Saved: Kanto Foods · k${'@'}example.com"}"""))
        assertEquals(ServerText.Bye("replaced"), parseServerText("""{"t":"bye","reason":"replaced"}"""))
        assertEquals(ServerText.Other, parseServerText("""{"t":"cursor","x":1}"""))
        assertEquals(ServerText.Other, parseServerText("not json"))
        assertEquals(ServerText.Other, parseServerText("""{"t":"notice","kind":"saved","line":""}"""))
        assertEquals(200, (parseServerText("""{"t":"notice","kind":"needs_you","line":"${"x".repeat(400)}"}""") as ServerText.Notice).line.length)
    }

    @Test fun tokenBucketsKeepUnderTheServerLimits() {
        for (lane in Lane.entries) {
            val server = mapOf(Lane.Touch to 240.0, Lane.Key to 20.0, Lane.Text to 20.0, Lane.Paste to 2.0, Lane.Ack to 60.0, Lane.Control to 10.0).getValue(lane)
            val burst = mapOf(Lane.Touch to 240, Lane.Key to 20, Lane.Text to 40, Lane.Paste to 4, Lane.Ack to 60, Lane.Control to 10).getValue(lane)
            assertTrue("$lane rate", lane.perSecond < server)
            assertTrue("$lane burst", lane.burst < burst)
        }
        var now = 0L
        val bucket = TokenBucket(16.0, 16) { now }
        repeat(16) { assertTrue(bucket.take()) }
        assertFalse(bucket.take())
        assertEquals(63L, bucket.waitMs())
        now += 63
        assertTrue(bucket.take())
    }
}
