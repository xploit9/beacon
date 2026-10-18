package dev.hermes.android.voice

import org.junit.Assert.*
import org.junit.Test

class RelayCallbackPolicyTest {
    @Test fun `unhydrated blank mismatched and null bindings never authorize calls`() {
        assertFalse(RelayCallbackPolicy.callsAllowed(null, "connection"))
        for (stored in listOf(null, "", " ", "connection", "other")) {
            for (active in listOf(null, "", " ", "connection")) {
                val prefs = RelayCallbackPreferences(stored, calls = true, keepConnected = true)
                val allowed = active == "connection" && stored == active
                assertEquals(allowed, RelayCallbackPolicy.callsAllowed(prefs, active))
            }
        }
        assertFalse(RelayCallbackPolicy.callsAllowed(RelayCallbackPreferences("connection ", calls = true, keepConnected = true), "connection"))
    }

    @Test fun `voice consent requires both calls and keep connected on the bound connection`() {
        for (calls in listOf(false, true)) for (keep in listOf(false, true)) {
            val prefs = RelayCallbackPreferences("connection", calls, keep)
            assertEquals(calls && keep, RelayCallbackPolicy.callsAllowed(prefs, "connection"))
        }
    }
}
