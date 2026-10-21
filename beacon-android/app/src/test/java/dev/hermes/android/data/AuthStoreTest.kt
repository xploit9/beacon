package dev.hermes.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthStoreTest {
    @Test fun olderAuthCopyKeepsNewerSameOwnerBearerAndIncomingProfile() {
        val origin = "https://native.example"
        val old = NativeBearerRecord("old-access", "old-refresh", 9000, origin, "owner", "work", issuedAt = 100)
        val stored = NativeBearerRecord("new-access", "new-refresh", 1000, origin, "owner", "assistant", issuedAt = 200)
        val auth = AuthContext(origin, emptyList(), "owner", "work", backend = "hermes-dashboard", bearer = old)

        val saved = auth.withNewestBearer(stored)

        assertEquals("new-access", saved.bearer?.accessToken)
        assertEquals("new-refresh", saved.bearer?.refreshToken)
        assertEquals(200L, saved.bearer?.issuedAt)
        assertEquals("work", saved.bearer?.profile)
    }

    @Test fun sameIssuedAtKeepsStoredOnWriteButLetsCheckedPersistChooseIncoming() {
        val origin = "https://native.example"
        val stale = NativeBearerRecord("stale-access", "stale-refresh", 4000, origin, "owner", "work", issuedAt = 300)
        val refreshed = NativeBearerRecord("refreshed-access", "refreshed-refresh", 2000, origin, "owner", "work", issuedAt = 300)
        val auth = AuthContext(origin, emptyList(), "owner", "work", backend = "hermes-dashboard", bearer = stale)

        assertEquals(refreshed, auth.withNewestBearer(refreshed).bearer)
        assertEquals(stale, auth.withNewestBearer(refreshed, preferIncomingOnIssueTimeTie = true).bearer)
    }

    @Test fun compareAndSetBearerKeepsRefreshWrittenWhenClockMovesBack() {
        val origin = "https://native.example"
        val expected = NativeBearerRecord("expected-access", "expected-refresh", 3000, origin, "owner", "work", issuedAt = 200)
        val refreshed = expected.copy(accessToken = "refreshed-access", refreshToken = "refreshed-refresh", expiresAt = 4000, issuedAt = 100)
        val current = AuthContext(origin, emptyList(), "owner", "work", backend = "hermes-dashboard", bearer = expected)

        assertEquals(refreshed, current.compareAndSetBearer(expected, refreshed)?.bearer)
    }

    @Test fun bearerFromAnotherOriginOrOwnerIsNotKept() {
        val origin = "https://native.example"
        val old = NativeBearerRecord("old-access", "old-refresh", 9000, origin, "owner", "work", issuedAt = 100)
        val auth = AuthContext(origin, emptyList(), "owner", "work", backend = "hermes-dashboard", bearer = old)

        assertEquals(old, auth.withNewestBearer(old.copy(origin = "https://other.example", accessToken = "foreign-access")).bearer)
        assertEquals(old, auth.withNewestBearer(old.copy(owner = "other-owner", accessToken = "foreign-access")).bearer)
    }

    @Test fun legacyRecordsUseExpiryAndTiesPreferIncoming() {
        val origin = "https://native.example"
        val stored = NativeBearerRecord("stored-access", "stored-refresh", 4000, origin, "owner", "work")
        val auth = AuthContext(origin, emptyList(), "owner", "work", bearer = stored.copy(expiresAt = 3000))

        assertEquals("stored-access", auth.withNewestBearer(stored).bearer?.accessToken)
        assertEquals("incoming-access", auth.copy(bearer = stored.copy(accessToken = "incoming-access", refreshToken = "incoming-refresh")).withNewestBearer(stored).bearer?.accessToken)
    }
}
