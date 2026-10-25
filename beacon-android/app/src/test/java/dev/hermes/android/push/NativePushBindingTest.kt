package dev.hermes.android.push

import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import org.junit.Assert.*
import org.junit.Test

class NativePushBindingTest {
    @Test fun keepConnectedCursorsAndRunningLoopsSeparateSameSubjectProviders() {
        val values = mutableMapOf<String, String>()
        val basic = KeepConnectedCursors(values::get, { key, value -> values[key] = value; true }, nativeProvider = "basic")
        val oidc = KeepConnectedCursors(values::get, { key, value -> values[key] = value; true }, nativeProvider = "oidc")
        basic.advance("https://native.example", "alice", "default", 12)
        assertEquals(12L, basic.read("https://native.example", "alice", "default"))
        assertEquals(0L, oidc.read("https://native.example", "alice", "default"))
        assertTrue(keepConnectedLoopNeedsRestart(true, "https://native.example", "alice", "https://native.example", "alice", "basic", "oidc"))
        assertFalse(keepConnectedLoopNeedsRestart(true, "https://native.example", "alice", "https://native.example", "alice", "basic", "basic"))
    }

    @Test fun delayedCleanupDoesNotRevokeATargetReusedByANewRegistration() {
        val replacement = binding().put("target", "same-target").put("native_provider", "oidc")
        assertTrue(pushTargetRetained("same-target", listOf(replacement)))
        assertFalse(pushTargetRetained("old-target", listOf(replacement)))
        assertFalse(pushTargetRetained("same-target", emptyList()))
    }

    @Test fun sameSubjectCannotUseAnotherProvidersOrUnstampedPushBinding() {
        val basic = auth.copy(nativeProvider = "basic")
        val source = binding().put("native_provider", "basic")
        assertTrue(nativePushBindingAuthorized(source, basic))
        assertFalse(nativePushBindingAuthorized(source, basic.copy(nativeProvider = "oidc")))
        assertFalse(nativePushBindingAuthorized(binding().apply { remove("native_provider") }, basic))
    }

    private val auth = AuthContext("https://beacon.example", emptyList(), "owner", "default", backend = "hermes-dashboard", nativeProvider = "basic")
    private fun binding() = json("origin" to auth.origin, "owner" to auth.owner, "native_provider" to auth.nativeProvider, "profile" to "default", "backend" to "hermes-dashboard")
    // What 1.1.1 stored for Keep connected: no native_provider and no push target.
    private fun legacySource() = binding().apply { remove("native_provider") }.put("source", "source-1").put("target", "").put("keep_connected", true)

    @Test fun unstampedKeepConnectedSourceIsRestampedOnlyForTheSameAccountProfileAndSource() {
        val scope = auth.scope()
        assertTrue(restampableKeepConnectedSource(legacySource(), scope, "source-1"))
        assertFalse("authorization itself stays strict", nativePushBindingAuthorized(legacySource(), auth))
        assertFalse(restampableKeepConnectedSource(legacySource(), scope, "source-2"))
        assertFalse(restampableKeepConnectedSource(legacySource().put("owner", "other"), scope, "source-1"))
        assertFalse(restampableKeepConnectedSource(legacySource().put("origin", "https://other.example"), scope, "source-1"))
        assertFalse(restampableKeepConnectedSource(legacySource().put("profile", "research"), scope, "source-1"))
        assertFalse(restampableKeepConnectedSource(legacySource().put("target", "push-target"), scope, "source-1"))
        assertFalse(restampableKeepConnectedSource(legacySource().put("native_provider", "oidc"), scope, "source-1"))
        assertFalse(restampableKeepConnectedSource(legacySource().put("native_provider", "basic"), scope, "source-1"))
    }

    @Test fun signOutAndRotationDropUnstampedBindingsOfOnlyTheSameOriginAndOwner() {
        val scope = auth.scope()
        assertTrue(unstampedBindingOfAccount(legacySource(), scope))
        assertTrue(unstampedBindingOfAccount(legacySource().put("profile", "research").put("target", "push-target"), scope))
        assertFalse(unstampedBindingOfAccount(legacySource().put("owner", "other"), scope))
        assertFalse(unstampedBindingOfAccount(legacySource().put("origin", "https://other.example"), scope))
        assertFalse(unstampedBindingOfAccount(legacySource().put("native_provider", "oidc"), scope))
        assertFalse(unstampedBindingOfAccount(binding(), scope))
    }

    @Test fun updatedPushIsRegisteredAgainOnlyForTheProfileThatHadIt() {
        val scope = auth.scope()
        assertTrue(legacyPushOfScope(legacySource().put("target", "push-target"), scope))
        assertFalse(legacyPushOfScope(legacySource().put("target", "push-target").put("profile", "research"), scope))
        assertFalse(legacyPushOfScope(legacySource().put("target", "push-target").put("owner", "other"), scope))
        assertFalse(legacyPushOfScope(legacySource().put("target", "push-target").put("origin", "https://other.example"), scope))
        assertFalse(legacyPushOfScope(binding(), scope))
    }

    @Test fun onlyExplicitNativeOwnerBindingMayResolveBackgroundWork() {
        assertTrue(nativePushBindingAuthorized(binding(), auth))
        assertTrue(nativePushBindingAuthorized(binding().put("profile", "research"), auth))
        assertFalse(nativePushBindingAuthorized(binding(), auth.copy(backend = "")))
        assertFalse(nativePushBindingAuthorized(binding().put("backend", ""), auth))
        assertFalse(nativePushBindingAuthorized(binding(), auth.copy(owner = "other")))
        assertFalse(nativePushBindingAuthorized(binding(), auth.copy(origin = "https://other.example")))
        assertFalse(nativePushBindingAuthorized(binding().put("profile", ""), auth))
        assertFalse(nativePushBindingAuthorized(binding(), auth.copy(owner = "")))
    }

    @Test fun onlyKeepConnectedInvitationsRequireAnInMemoryReceipt() {
        val keepConnected = requiresLocalKeepConnectedReceipt("local-action-token")
        val fcm = requiresLocalKeepConnectedReceipt(null)

        assertFalse(canAuthorizeWithoutKeepConnectedReceipt(keepConnected, "answer"))
        assertFalse(canAuthorizeWithoutKeepConnectedReceipt(keepConnected, "decline"))
        assertTrue(canAuthorizeWithoutKeepConnectedReceipt(fcm, "answer"))
        assertTrue(canAuthorizeWithoutKeepConnectedReceipt(fcm, "decline"))
        assertFalse(canAuthorizeWithoutKeepConnectedReceipt(fcm, "other"))
    }
}
