package dev.hermes.android.voice

import dev.hermes.android.push.keepConnectedCallData
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** A call rings for 20 s from its arrival; the only way to change that is the debug-only test seam, which a test must put back. */
class RingWindowSeamTest {
    private val binding = RelayCallbackBinding("connection", "phone", "work", "session", "https://native.example", "owner")
    private fun payload(window: Long = 20_000L) = JSONObject().put("call_id", "15be9220-e148-4c6b-8a42-6a749502ef36").put("device_id", "phone")
        .put("profile", "work").put("session_id", "session").put("title", "Hermes").put("reason", "Check in")
        .put("created_at", 1000L).put("expires_at", 1000L + window).put("status", "ringing")
    private fun event() = JSONObject().put("source_ref", "source-opaque-1").put("event_id", "c0ffee00112233445566778899aabbcc")
        .put("conversation_ref", "conversation-1").put("expires_at", 2_000L)

    @After fun putTheWindowBack() = RelayCallback.ringWindowForTest(null)

    @Test fun `without the seam a call rings for twenty seconds from arrival and its expiry is exact`() {
        assertEquals(20_000L, RelayCallback.RING_WINDOW_MS)
        assertEquals(20_000L, RelayCallback.ringWindowMs())
        val call = RelayCallback.parse(payload(), binding, 1000, 50)!!
        assertEquals(20_000L, call.remainingRingMillis(1000, 50))
        assertEquals(1L, call.remainingRingMillis(1000, 20_049))
        assertEquals(0L, call.remainingRingMillis(1000, 20_050))
        assertNull("a wire window of 60 s is not a call", RelayCallback.parse(payload(60_000L), binding, 1000, 50))
        // The notice a Keep connected call leaves is bounded by the same window: arrival 1,000,000 ms plus 20 s is second 1020.
        assertEquals("1020", keepConnectedCallData(event(), 1_000_000L).getValue("expires_at"))
    }

    @Test fun `the widened window applies to what is prepared under it and ends with the test`() {
        RelayCallback.ringWindowForTest(60_000L)
        assertEquals(60_000L, RelayCallback.ringWindowMs())
        val call = RelayCallback.parse(payload(60_000L), binding, 1000, 50)!!
        assertEquals(60_000L, call.remainingRingMillis(1000, 50))
        assertEquals(0L, call.remainingRingMillis(1000, 60_050))
        assertNull("the 20 s wire window does not match a widened window", RelayCallback.parse(payload(), binding, 1000, 50))
        assertEquals("1060", keepConnectedCallData(event(), 1_000_000L).getValue("expires_at"))

        RelayCallback.ringWindowForTest(null)
        assertEquals(20_000L, RelayCallback.ringWindowMs())
        assertNull(RelayCallback.parse(payload(60_000L), binding, 1000, 50))
        assertEquals(20_000L, RelayCallback.parse(payload(), binding, 1000, 50)!!.remainingRingMillis(1000, 50))
        assertEquals("1020", keepConnectedCallData(event(), 1_000_000L).getValue("expires_at"))
    }

    @Test fun `a test can only widen the window`() {
        assertTrue(runCatching { RelayCallback.ringWindowForTest(5_000L) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(20_000L, RelayCallback.ringWindowMs())
    }
}
