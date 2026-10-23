package dev.hermes.android.push

import dev.hermes.android.data.ApiFailure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AuditR8aPollingTest {
    private fun cursors(profiles: List<String>): KeepConnectedCursors {
        val values = mutableMapOf<String, String>()
        return KeepConnectedCursors({ values[it] }, { key, value -> values[key] = value; true }).apply {
            profiles.forEach { reset("https://native.example", "owner", it, 0) }
        }
    }

    @Test fun failingSignedInProfileCannotDelaySuccessfulPeerPolls() = runTest {
        val profiles = listOf("default", "academic")
        var attempts = 0
        var peerPolls = 0
        val poller = KeepConnectedPoller("https://native.example", "owner", cursors(profiles), { profiles }, { "default" },
            nowMillis = { testScheduler.currentTime }, poll = { profile, after ->
                if (profile == "default") { attempts++; throw ApiFailure(403, "profile unavailable", true) }
                peerPolls++
                KeepConnectedPage(emptyList(), after)
            }, onEvent = { _, _ -> })
        repeat(8) {
            assertEquals(KeepConnectedCycle.Reconnecting(KEEP_CONNECTED_MIN_CYCLE_MILLIS), poller.pollOnce())
            assertTrue(poller.lastFailureReason.contains("refused"))
            testScheduler.advanceTimeBy(60_000)
        }
        assertEquals(8, attempts)
        assertTrue(peerPolls >= 8)
    }

    @Test fun allFailingProfilesRetainTheirBoundedExponentialBackoff() = runTest {
        val profiles = listOf("default")
        var now = 0L
        val poller = KeepConnectedPoller("https://native.example", "owner", cursors(profiles), { profiles }, { "default" },
            nowMillis = { now }, poll = { _, _ -> throw ApiFailure(403, "private policy", true) }, onEvent = { _, _ -> })
        repeat(8) { index ->
            val delay = (poller.pollOnce() as KeepConnectedCycle.Reconnecting).delayMillis
            assertEquals(keepConnectedBackoffMs(index + 1), delay)
            now += delay
        }
        assertEquals(60_000, keepConnectedBackoffMs(100).toInt())
    }

    @Test fun removedOrReplacedDeviceCannotUseOldPushAuthority() {
        assertTrue(pushDeviceAuthorized(false, "phone-a", "phone-a"))
        assertFalse(pushDeviceAuthorized(true, "phone-a", "phone-a"))
        assertFalse(pushDeviceAuthorized(false, "phone-a", null))
        assertFalse(pushDeviceAuthorized(false, "phone-a", "phone-b"))
        assertTrue(pushDeviceAuthorized(false, "", null))
    }
}
