package dev.hermes.android.data

import dev.hermes.android.core.Scope
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class SessionRestoreTest {
    private val owner = Scope("https://native.example", "owner", "work")
    private val now = System.currentTimeMillis()
    private fun token(name: String = "__Host-hermes_session_rt", expires: Long = now + 60_000) =
        Cookie.Builder().name(name).value("fixture").hostOnlyDomain("native.example").path("/")
            .secure().httpOnly().expiresAt(expires).build().toString()
    private fun saved(cookies: List<String>) = AuthContext(owner.origin, cookies, owner.owner, owner.profile, backend = "hermes-dashboard")

    @Test fun expiredAccessWithLiveRefreshRestoresAfterRelaunch() {
        val retained = saved(listOf(token("__Host-hermes_session_at", now - 1), token()))
        assertEquals(owner, retained.cachedScopeFor(owner.origin, false, now))
        assertEquals(1, ScopedCookies(owner.origin.toHttpUrl(), retained.cookies).loadForRequest(owner.origin.toHttpUrl()).size)
    }
    @Test fun routingHintExpiredTokensAndSignOutCannotOpenVault() {
        assertNull(saved(listOf(token("__Host-hermes_session_provider"))).cachedScopeFor(owner.origin, false, now))
        assertNull(saved(listOf(token(expires = now - 1))).cachedScopeFor(owner.origin, false, now))
        assertNull(saved(emptyList()).cachedScopeFor(owner.origin, false, now))
        assertNull(saved(listOf(token())).cachedScopeFor(owner.origin, true, now))
    }
    @Test fun originAndIdentityRemainBound() {
        val retained = saved(listOf(token()))
        assertNull(retained.cachedScopeFor("https://foreign.example", false, now))
        assertNull(retained.copy(owner = "").cachedScopeFor(owner.origin, false, now))
        assertNull(retained.copy(backend = "legacy").cachedScopeFor(owner.origin, false, now))
        listOf(owner.copy(owner = "other"), owner.copy(profile = "other"), owner.copy(origin = "https://foreign.example")).forEach {
            assertThrows(CachedIdentityMismatch::class.java) { retained.requireCachedIdentity(it) }
        }
    }
    @Test fun nativeCookieVariantsAndTrustAttributesAreRequired() {
        listOf("", "__Host-", "__Secure-").forEach { prefix ->
            assertEquals(owner, saved(listOf(token(prefix + "hermes_session_at"))).cachedScopeFor(owner.origin, false, now))
        }
        assertNull(saved(listOf(token().replace("; secure", ""))).cachedScopeFor(owner.origin, false, now))
        assertNull(saved(listOf(token().replace("; httponly", ""))).cachedScopeFor(owner.origin, false, now))
        assertFalse(nativeSessionCookie("__Host-__Secure-hermes_session_rt"))
    }
    @Test fun sameOwnerRecreationKeepsDetachedCallAcrossProfiles() {
        assertTrue(keepsRestoredCall(owner.session("call-session"), owner, false))
        assertTrue(keepsRestoredCall(owner.session("call-session"), owner.copy(profile = "personal"), false))
    }
    @Test fun accountOriginReplacementAndMissingAuthorityTerminateCall() {
        assertFalse(keepsRestoredCall(owner, owner.copy(owner = "foreign"), false))
        assertFalse(keepsRestoredCall(owner, owner.copy(origin = "https://foreign.example"), false))
        assertFalse(keepsRestoredCall(owner, null, false))
        assertFalse(keepsRestoredCall(owner, owner, true))
    }
    @Test fun authorityChangesInvalidateRefreshCheckpoint() {
        val epoch = CredentialEpoch()
        val version = epoch.readVersion()!!
        assertTrue(epoch.current(version))
        epoch.mutate { assertNull(epoch.readVersion()); assertFalse(epoch.current(version)) }
        assertFalse(epoch.current(version))
    }
}
