package dev.hermes.android.push

import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import org.junit.Assert.*
import org.junit.Test

class NativePushBindingTest {
    private val auth = AuthContext("https://beacon.example", emptyList(), "owner", "default", backend = "hermes-dashboard")
    private fun binding() = json("origin" to auth.origin, "owner" to auth.owner, "profile" to "default", "backend" to "hermes-dashboard")

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
