package dev.hermes.android.updates

import dev.hermes.android.core.Release
import dev.hermes.android.data.AuthContext
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val signer = "a".repeat(64)
    private val release = Release(22, "0.4.1", "dev.hermes.android", "/api/plugins/cypher/mobile/releases/${"b".repeat(64)}/download", "b".repeat(64), 1024, signer, 2, 2)
    private fun candidate(value: Release = release, installed: Long = 2): Release? = newerRelease(value, "dev.hermes.android", installed, signer)
    private fun rejected(value: Release, installed: Long = 2) {
        try { candidate(value, installed); fail("Untrusted release accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun newerOrInstalledOrOlder() {
        assertEquals(release, candidate())
        assertNull(candidate(installed = 22))
        assertNull(candidate(installed = 23))
    }

    @Test fun installedVersionDoesNotHideUntrustedManifest() {
        rejected(release.copy(packageName = "another.app"), 22)
        rejected(release.copy(signer = "c".repeat(64)), 22)
        rejected(release.copy(path = "https://evil.test/update.apk"), 22)
        rejected(release.copy(path = "/api/mobile/releases/%2e%2e/evil.apk"), 22)
        rejected(release.copy(sha = "bad"), 22)
        rejected(release.copy(size = 0), 22)
        rejected(release.copy(maxContract = 0), 22)
        rejected(release.copy(version = 0), 22)
        rejected(release.copy(name = ""), 22)
        rejected(release.copy(name = "rc3\nmisleading"), 22)
        rejected(release.copy(name = "0.4.1\u202ehidden"), 22)
        rejected(release.copy(name = "0.4.1\u007f"), 22)
    }

    @Test fun signedCandidateKeepsAllTrustBoundaries() {
        rejected(release.copy(packageName = "another.app"))
        rejected(release.copy(signer = "c".repeat(64)))
        rejected(release.copy(size = 256L * 1024 * 1024 + 1))
        rejected(release.copy(path = "/api/update.apk?owner=another"))
        rejected(release.copy(minContract = 1, maxContract = 1))
        rejected(release.copy(minContract = 3, maxContract = 3))
    }

    @Test fun logoutOwnerAndOriginReplacementRevokeScopeButProfileSwitchDoesNot() {
        val source = UpdateSource("https://hermes.test", "owner-a", "local-generation")
        val saved = AuthContext(source.origin, emptyList(), source.owner, "default", backend = "hermes-dashboard")
        assertTrue(source.authorizedBy(saved))
        assertTrue(source.authorizedBy(saved.copy(profile = "other")))
        assertFalse(source.authorizedBy(saved.copy(owner = "")))
        assertFalse(source.authorizedBy(saved.copy(owner = "owner-b")))
        assertFalse(source.authorizedBy(saved.copy(origin = "https://another.test")))
        assertFalse(source.authorizedBy(saved.copy(backend = "")))
        assertNotEquals(source, source.copy(id = "replacement-generation"))
        // Expired cookies fail at server bootstrap; they do not revoke local consent.
        assertTrue(source.authorizedBy(saved.copy(cookies = listOf("expired=1"))))
    }

    @Test fun notificationVersionDedupAndManualRetry() {
        assertTrue(updateNotificationDue(3, 0))
        assertFalse(updateNotificationDue(3, 3))
        assertFalse(updateNotificationDue(2, 3))
        assertTrue(updateNotificationDue(4, 3))
        assertTrue(updateCheckDue(0, 1, false))
        assertFalse(updateCheckDue(1_000, 2_000, false))
        assertTrue(updateCheckDue(1_000, 2_000, true))
        assertFalse(updateCheckDue(1_000, 1_000 + 12 * 60 * 60_000L - 1, false))
        assertTrue(updateCheckDue(1_000, 1_000 + 12 * 60 * 60_000L, false))
        assertTrue(updateCheckDue(2_000, 1_000, false))
    }

    @Test fun automaticUpdateDefaultsOnAndNeedsUnmeteredNetworkWithoutAnActiveCall() {
        // Background checks run every 12 hours whether or not automatic updates are on (off only announces).
        assertTrue(updateCheckDue(0,1,false))
        assertFalse(updateCheckDue(1_000,1_000+12*60*60_000L-1,false))
        assertTrue(updateCheckDue(1_000,1_000+12*60*60_000L,false))
        assertTrue(autoUpdateInstallAllowed(true, unmetered = true, callActive = false))
        assertFalse(autoUpdateInstallAllowed(false, unmetered = true, callActive = false))
        assertFalse(autoUpdateInstallAllowed(true, unmetered = false, callActive = false))
        assertFalse(autoUpdateInstallAllowed(true, unmetered = true, callActive = true))
    }

    @Test fun silentInstallWaitsWhileCypherIsOnScreenOrInACall() {
        val background = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        val visible = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        val foreground = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        assertTrue(silentInstallAllowed(31, canRequestInstalls = true, callActive = false, importance = background))
        assertFalse(silentInstallAllowed(31, canRequestInstalls = true, callActive = false, importance = foreground))
        assertFalse(silentInstallAllowed(31, canRequestInstalls = true, callActive = false, importance = visible))
        assertTrue(silentInstallAllowed(31, canRequestInstalls = true, callActive = false, importance = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        assertFalse(silentInstallAllowed(31, canRequestInstalls = true, callActive = true, importance = background))
        assertFalse(silentInstallAllowed(30, canRequestInstalls = true, callActive = false, importance = background))
        assertFalse(silentInstallAllowed(31, canRequestInstalls = false, callActive = false, importance = background))
    }

    @Test fun externalIntentCannotSupplyUrlIdentityOrCredentials() {
        assertTrue(validUpdateToken("a".repeat(32)))
        listOf("", "a".repeat(31), "a".repeat(33), "A".repeat(32), "https://hermes.test/update", "owner-a", "cookie=secret").forEach { assertFalse(validUpdateToken(it)) }
    }

    @Test fun pushIsOnlyANewerAvailabilityHint() {
        assertEquals(5L, pushReleaseVersion("5", 4, 0))
        assertNull(pushReleaseVersion("4", 4, 0))
        assertNull(pushReleaseVersion("5", 4, 5))
        listOf(null, "", "0", "-5", "+5", "05", "5.0", "9223372036854775808", "https://evil.test/update.apk").forEach {
            assertNull(pushReleaseVersion(it, 4, 0))
        }
        // Even a very large hint is never saved as a verified notification version.
        assertEquals(Long.MAX_VALUE, pushReleaseVersion(Long.MAX_VALUE.toString(), 4, 0))
        assertEquals(5L, pushReleaseVersion("5", 4, 0))
    }
}
