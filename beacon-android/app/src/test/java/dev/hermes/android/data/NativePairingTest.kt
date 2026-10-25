package dev.hermes.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePairingTest {
    @Test fun deviceIdsSeparateProvidersAndKeepProfilesInOneAccount() {
        val legacy = dev.hermes.android.core.Scope("https://native.example", "alice", "default")
        val basic = legacy.copy(nativeProvider = "basic")
        val oidc = legacy.copy(nativeProvider = "oidc")
        assertTrue(NativePairing.accountKey(basic) != NativePairing.accountKey(oidc))
        assertTrue(NativePairing.accountKey(basic) != NativePairing.accountKey(legacy))
        assertEquals(NativePairing.accountKey(basic), NativePairing.accountKey(basic.copy(profile = "work")))
    }

    @Test fun deviceIdSavedByTheEarlierVersionIsCopiedToTheProviderKeyOnce() {
        val scope = dev.hermes.android.core.Scope("https://native.example", "alice", "default", nativeProvider = "basic")
        val oldKey = NativePairing.accountKey(scope.copy(nativeProvider = ""))
        val newKey = NativePairing.accountKey(scope)
        val values = mutableMapOf(oldKey to "phone-1")
        val writes = mutableListOf<Pair<String, String>>()
        fun lookup(forScope: dev.hermes.android.core.Scope = scope) =
            NativePairing.storedDeviceId(forScope, values::get) { key, id -> values[key] = id; writes += key to id }

        assertEquals("phone-1", lookup())
        assertEquals(listOf(newKey to "phone-1"), writes)
        assertEquals("phone-1", lookup())
        assertEquals(1, writes.size)
        // An id already under the provider key wins; another account or a provider-less scope never inherits the old one.
        values[newKey] = "phone-2"
        assertEquals("phone-2", lookup())
        assertNull(lookup(scope.copy(owner = "bob")))
        assertNull(lookup(scope.copy(nativeProvider = "")))
        // A damaged id is never copied.
        values.clear(); values[oldKey] = "not a device id"
        assertNull(lookup())
        assertEquals(1, writes.size)
    }

    @Test fun theLinkRequestNamesThisPhoneAndSendsItsStoredIdOnlyOnceItHasOne() {
        val first = deviceLinkBody("Pixel 9", null)
        assertEquals("Pixel 9", first.getString("device_name"))
        assertFalse("the first link lets Hermes create the record", first.has("device_id"))
        val later = deviceLinkBody("Pixel 9", "phone-1")
        assertEquals("phone-1", later.getString("device_id"))
        assertEquals(setOf("device_name", "device_id"), later.keys().asSequence().toSet())
    }

    @Test fun anOldPairingBlockIsReducedToItsAddress() {
        // What the dashboard used to print: the code is dropped, only the Hermes address is kept.
        assertEquals("https://hermes.example", pairingBlockAddress("Dashboard address: https://hermes.example\nPairing code: abc-123\nExpires: 10 minutes"))
        assertEquals("https://hermes.example.ts.net", pairingBlockAddress("Dashboard: https://hermes.example.ts.net\nPairing code: K7Q-4ZP\nexpires in 10 min"))
        assertEquals("https://hermes.example", pairingBlockAddress("Open (https://hermes.example), code: K7Q4ZP."))
    }

    @Test fun anAddressWithoutAPairingCodeIsLeftAlone() {
        assertNull(pairingBlockAddress("https://hermes.example without a code"))
        assertNull(pairingBlockAddress("https://hermes.example"))
    }
}
