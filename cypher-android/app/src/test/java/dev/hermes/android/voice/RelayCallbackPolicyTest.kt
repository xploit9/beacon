package dev.hermes.android.voice

import org.junit.Assert.*
import org.junit.Test

class RelayCallbackPolicyTest {
    @Test fun `original null connection message defect fails closed`() {
        val unbound = RelayCallbackPreferences(null, messages = true, calls = true, keepConnected = true)
        val activeConnectionId: String? = null
        // This is the original Relay predicate: null == null accidentally authorizes messages.
        assertTrue(unbound.connectionId == activeConnectionId && unbound.messages)
        assertFalse(RelayCallbackPolicy.messagesAllowed(unbound, activeConnectionId))
        assertFalse(RelayCallbackPolicy.callsAllowed(unbound, null))
        assertNull(RelayCallbackPolicy.subscriptionEnvelope(unbound, null))
    }

    @Test fun `unhydrated blank mismatched and null bindings never authorize or subscribe`() {
        assertFalse(RelayCallbackPolicy.messagesAllowed(null, "connection"))
        assertFalse(RelayCallbackPolicy.callsAllowed(null, "connection"))
        assertNull(RelayCallbackPolicy.subscriptionEnvelope(null, "connection"))
        for (stored in listOf(null, "", " ", "connection", "other")) {
            for (active in listOf(null, "", " ", "connection")) {
                val prefs = RelayCallbackPreferences(stored, true, true, true)
                val allowed = active == "connection" && stored == active
                assertEquals("stored=$stored active=$active", allowed, RelayCallbackPolicy.messagesAllowed(prefs, active))
                assertEquals(allowed, RelayCallbackPolicy.callsAllowed(prefs, active))
                assertEquals(allowed, RelayCallbackPolicy.subscriptionEnvelope(prefs, active) != null)
            }
        }
        assertFalse(RelayCallbackPolicy.messagesAllowed(RelayCallbackPreferences("connection ", true, true, true), "connection"))
    }

    @Test fun `messages calls and keep connected remain independent on the bound connection`() {
        for (messages in listOf(false, true)) for (calls in listOf(false, true)) for (keep in listOf(false, true)) {
            val prefs = RelayCallbackPreferences("connection", messages, calls, keep)
            val envelope = RelayCallbackPolicy.subscriptionEnvelope(prefs, "connection")!!
            assertEquals("proactive", envelope.getString("channel"))
            assertTrue(envelope.getString("id").isNotBlank())
            assertEquals(messages, RelayCallbackPolicy.messagesAllowed(prefs, "connection"))
            assertEquals(calls && keep, RelayCallbackPolicy.callsAllowed(prefs, "connection"))
            if (!messages && !(calls && keep)) {
                assertEquals("proactive.unsubscribe", envelope.getString("type"))
                assertEquals(0, envelope.getJSONObject("payload").length())
            } else {
                assertEquals("proactive.subscribe", envelope.getString("type"))
                assertEquals(messages, envelope.getJSONObject("payload").getBoolean("messages"))
                assertEquals(calls && keep, envelope.getJSONObject("payload").getBoolean("voice_calls"))
            }
        }
    }

    @Test fun `consent can always be disabled but enabling requires pairing and keep connected`() {
        for (paired in listOf(false, true)) for (keep in listOf(false, true)) {
            assertTrue(RelayCallbackPolicy.canSetCallsConsent(false, paired, keep))
            assertEquals(paired && keep, RelayCallbackPolicy.canSetCallsConsent(true, paired, keep))
        }
    }
}
