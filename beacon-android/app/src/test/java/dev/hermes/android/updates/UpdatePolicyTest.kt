package dev.hermes.android.updates

import android.app.ActivityManager.RunningAppProcessInfo
import dev.hermes.android.core.Release
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val signer = "a".repeat(64)
    private val sha = "b".repeat(64)
    private val release = Release(22, "0.4.1", "dev.hermes.android", "https://github.com/xploit9/beacon/releases/download/v0.4.1/Beacon-0.4.1.apk", sha, 1024, signer, "0.4.1", "Faster updates", "2026-10-24T09:00:00Z")
    private fun candidate(value: Release = release, installed: Long = 2): Release? = newerRelease(value, "dev.hermes.android", installed, signer)
    private fun rejected(value: Release, installed: Long = 2) {
        try { candidate(value, installed); fail("Untrusted release accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun newerOrInstalledOrOlder() {
        assertEquals(release, candidate())
        assertNull(candidate(installed = 22))
        assertNull(candidate(installed = 23))
    }

    @Test fun installedVersionDoesNotHideUntrustedMetadata() {
        rejected(release.copy(packageName = "another.app"), 22)
        rejected(release.copy(signer = "c".repeat(64)), 22)
        rejected(release.copy(sha = "bad"), 22)
        rejected(release.copy(size = 0), 22)
        rejected(release.copy(pluginVersion = "1.2"), 22)
        rejected(release.copy(url = ""), 22)
        rejected(release.copy(version = 0), 22)
        rejected(release.copy(name = ""), 22)
        rejected(release.copy(name = "rc3\nmisleading"), 22)
        rejected(release.copy(name = "0.4.1‮hidden"), 22)
        rejected(release.copy(name = "0.4.1\u007f"), 22)
    }

    @Test fun signedCandidateKeepsAllTrustBoundaries() {
        rejected(release.copy(packageName = "another.app"))
        rejected(release.copy(signer = "c".repeat(64)))
        rejected(release.copy(size = 256L * 1024 * 1024 + 1))
        rejected(release.copy(pluginVersion = "one.two.three"))
    }

    @Test fun aPublishedVersionKeepsItsBytes() {
        assertFalse(sameCodeDifferentBytes(0, "", release))
        assertFalse(sameCodeDifferentBytes(22, sha, release))
        assertFalse(sameCodeDifferentBytes(22, sha.uppercase(), release))
        assertTrue(sameCodeDifferentBytes(22, "c".repeat(64), release))
        // Another version code is a different release, whatever it hashes to.
        assertFalse(sameCodeDifferentBytes(21, "c".repeat(64), release))
        assertFalse(sameCodeDifferentBytes(23, "c".repeat(64), release))
    }

    @Test fun releasesSurviveTheirPhoneLocalCopy() {
        assertEquals(release, Release.decode(release.encode()))
        assertNull(Release.decode(null))
        assertNull(Release.decode("{}"))
        assertNull(Release.decode("not json"))
    }

    @Test fun checksRunEverySixHoursAndFailedAttemptsWaitFifteenMinutes() {
        val hour = 60 * 60_000L
        assertTrue(updateCheckDue(0, 1, false))
        assertFalse(updateCheckDue(1_000, 1_000 + 6 * hour - 1, false))
        assertTrue(updateCheckDue(1_000, 1_000 + 6 * hour, false))
        assertTrue(updateCheckDue(2_000, 1_000, false)) // the clock went backwards
        assertTrue(updateCheckDue(1_000, 2_000, true)) // a manual check is always due
        // The last check is old, but an attempt just failed: not again for 15 minutes.
        val now = 10 * hour
        assertFalse(updateCheckDue(1_000, now, false, lastAttempt = now - 14 * 60_000L))
        assertTrue(updateCheckDue(1_000, now, false, lastAttempt = now - 15 * 60_000L))
        assertTrue(updateCheckDue(1_000, now, true, lastAttempt = now))
    }

    @Test fun oneNotificationPerVersion() {
        assertTrue(updateNotificationDue(3, 0))
        assertFalse(updateNotificationDue(3, 3))
        assertFalse(updateNotificationDue(2, 3))
        assertTrue(updateNotificationDue(4, 3))
    }

    @Test fun externalIntentCannotSupplyUrlIdentityOrCredentials() {
        assertTrue(validUpdateToken("a".repeat(32)))
        listOf("", "a".repeat(31), "a".repeat(33), "A".repeat(32), "https://hermes.test/update", "owner-a", "cookie=secret").forEach { assertFalse(validUpdateToken(it)) }
    }

    @Test fun onlyAVisibleBeaconCountsAsOnScreen() {
        assertTrue(beaconVisible(RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertTrue(beaconVisible(RunningAppProcessInfo.IMPORTANCE_VISIBLE))
        // Keep connected's foreground service alone is not on screen.
        assertFalse(beaconVisible(RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        assertFalse(beaconVisible(RunningAppProcessInfo.IMPORTANCE_CACHED))
    }

    @Test fun pluginVersionsCompareAsPlainTriples() {
        assertTrue(olderVersion("1.1.1", "1.1.2"))
        assertTrue(olderVersion("1.9.9", "1.10.0"))
        assertTrue(olderVersion("0.9.9", "1.0.0"))
        assertFalse(olderVersion("1.1.2", "1.1.2"))
        assertFalse(olderVersion("1.1.3", "1.1.2"))
        // Anything else is never "older", so an odd version is never offered.
        listOf("", "1.1", "1.1.x", "01.1.1", "1.1.1-rc1", "v1.1.1").forEach {
            assertFalse(olderVersion(it, "9.9.9")); assertFalse(olderVersion("1.0.0", it))
        }
    }

    @Test fun sizesAndProgressReadAtAGlance() {
        val mb = 1024L * 1024
        assertEquals("31 MB", formatSize(31 * mb + 200_000))
        assertEquals("4.2 MB", formatSize(4 * mb + 200_000))
        assertEquals("<1 MB", formatSize(900_000))
        assertEquals("41% · 13 of 31 MB", progressLine(13 * mb, 31 * mb))
        assertEquals("50% · 2.0 of 4.0 MB", progressLine(2 * mb, 4 * mb))
        assertEquals(0, progressPercent(5, 0))
        assertEquals(100, progressPercent(11, 10))
    }

    @Test fun checkedTimeIsRelativeAndBlankWhenNeverChecked() {
        val now = 100 * 24 * 60 * 60_000L
        assertEquals("", checkedAgo(0, now))
        assertEquals("just now", checkedAgo(now - 59_000, now))
        assertEquals("just now", checkedAgo(now + 5_000, now))
        assertEquals("5 min ago", checkedAgo(now - 5 * 60_000, now))
        assertEquals("3 h ago", checkedAgo(now - 3 * 60 * 60_000, now))
        assertEquals("2 d ago", checkedAgo(now - 49 * 60 * 60_000, now))
    }
}
