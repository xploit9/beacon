package dev.hermes.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An action that started with one loopback session and finds a renewed one of the same account and profile in the store carries on; a real change of
 * account, profile or kind of sign-in still stops it. Synthetic origin and tokens only.
 */
class LoopbackRenewalMidActionTest {
    private val origin = "https://hermes.example"
    private fun session(token: String, owner: String = "local", verified: Boolean = true, persisted: Boolean = true) = LoopbackSessionRecord(token, origin, owner, verified, persisted)
    private fun loopback(token: String, owner: String = "local", profile: String = "default", verified: Boolean = true) =
        AuthContext(origin, emptyList(), owner, profile, backend = "hermes-dashboard", loopbackSession = session(token, owner, verified))
    private fun bearer(access: String, owner: String = "local") = AuthContext(origin, emptyList(), owner, "default", backend = "hermes-dashboard",
        bearer = NativeBearerRecord(access, "refresh-1", 4_000_000_000L, origin, owner, "default", "nous", 1L))

    @Test fun aRenewedLoopbackSessionOfTheSameAccountAndProfileIsTheSameSignIn() {
        val held = loopback("token-old")
        val stored = loopback("token-new")
        assertTrue("the stored record is a renewal of the held sign-in", stored.continuesSignInOf(held))
    }

    @Test fun theSameLoopbackSessionStillCounts() {
        assertTrue(loopback("token-same").continuesSignInOf(loopback("token-same")))
    }

    @Test fun anotherAccountProfileOrKindOfSignInStillStops() {
        val held = loopback("token-old")
        assertFalse("another owner", loopback("token-new", owner = "someone-else").continuesSignInOf(held))
        assertFalse("another profile", loopback("token-new", profile = "work").continuesSignInOf(held))
        assertFalse("a sign-in as another kind (a bearer)", bearer("access-1").continuesSignInOf(held))
        assertFalse("signed out", AuthContext(origin, emptyList()).continuesSignInOf(held))
        assertFalse("a stored session that was never verified", loopback("token-new", verified = false).continuesSignInOf(held))
        assertFalse("a held session that was never verified", loopback("token-new").continuesSignInOf(loopback("token-old", verified = false)))
    }

    @Test fun aRenewalNeverReachesAnotherOrigin() {
        val held = loopback("token-old")
        val elsewhere = AuthContext("https://other.example", emptyList(), "local", "default", backend = "hermes-dashboard",
            loopbackSession = LoopbackSessionRecord("token-new", "https://other.example", "local", true, true))
        assertFalse(elsewhere.continuesSignInOf(held))
    }
}
