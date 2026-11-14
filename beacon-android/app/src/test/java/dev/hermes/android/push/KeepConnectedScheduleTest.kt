package dev.hermes.android.push

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * The poll schedule that decides how late a Keep connected alert can arrive, in deep idle too (the service holds a partial wake lock and polls in a loop; there is no alarm).
 * The device case that waits for the alert during forced deep idle used to allow 15 s; it now allows 45 s, so these values (and the same-cycle delivery) are pinned here instead:
 * a long poll hold (25 s), a held-alert retry (30 s) or a backoff of 16 s or more would have failed the 15 s wait and would pass a 45 s one.
 */
class KeepConnectedScheduleTest {
    private fun cursors(): KeepConnectedCursors {
        val values = mutableMapOf<String, String>()
        return KeepConnectedCursors({ values[it] }, { key, value -> values[key] = value; true }).apply { reset("https://native.example", "owner", "default", 0) }
    }

    @Test fun `the intervals between polls are pinned`() {
        assertEquals(25, KEEP_CONNECTED_LONG_POLL_SECONDS)
        assertEquals(35_000L, KEEP_CONNECTED_POLL_TIMEOUT_MILLIS)
        assertTrue(KEEP_CONNECTED_POLL_TIMEOUT_MILLIS > KEEP_CONNECTED_LONG_POLL_SECONDS * 1_000L)
        assertEquals(500L, KEEP_CONNECTED_MIN_CYCLE_MILLIS)
        assertEquals(30_000L, KEEP_CONNECTED_HELD_RETRY_MILLIS)
        assertEquals(120_000L, KEEP_CONNECTED_CPU_LEASE_MILLIS)
        assertEquals(30_000L, KEEP_CONNECTED_CPU_RENEW_MILLIS)
        assertTrue("the wake lock is renewed before its lease ends", KEEP_CONNECTED_CPU_RENEW_MILLIS < KEEP_CONNECTED_CPU_LEASE_MILLIS)
        // After failures the wait doubles from one second: 1, 2, 4, 8 s, so four failures in a row still total 15 s, and the fifth step is 16 s.
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), (1..5).map { keepConnectedBackoffMs(it) })
    }

    @Test fun `an alert a poll returns is delivered in that cycle with no wait`() = runTest {
        var deliveredAt = -1L
        val event = JSONObject().put("kind", "task_result").put("event_id", "e1").put("seq", 1)
        val poller = KeepConnectedPoller("https://native.example", "owner", cursors(), { listOf("default") }, { "default" },
            nowMillis = { testScheduler.currentTime },
            poll = { _, after -> if (after == 0L) KeepConnectedPage(listOf(event), 1) else KeepConnectedPage(emptyList(), after) },
            onEvent = { _, _ -> deliveredAt = testScheduler.currentTime })
        assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
        assertEquals("delivered at virtual time 0, not after any retry or hold", 0L, deliveredAt)
        // The loop then waits at most the minimum cycle before it asks again.
        assertEquals(500L, keepConnectedPollDelay(0))
    }
}
