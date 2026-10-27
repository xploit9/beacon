package dev.hermes.android.voice

import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RelayCallbackTest {
    private val id = "15be9220-e148-4c6b-8a42-6a749502ef36"
    private val binding = RelayCallbackBinding("connection", "phone", "work", "session", "https://native.example", "owner")
    private val prefs = RelayCallbackPreferences("connection", calls = true, keepConnected = true)
    private fun payload(callId: String = id) = JSONObject().put("call_id", callId).put("device_id", "phone")
        .put("profile", "work").put("session_id", "session").put("title", "Hermes").put("reason", "Check in")
        .put("created_at", 1000L).put("expires_at", 21000L).put("status", "ringing")
    private fun envelope(type: String = "phone.call.invite", data: JSONObject = payload(), envelopeId: String = "event") =
        RelayCallbackWire.envelope(type, data, envelopeId)
    private fun invite(protocol: RelayCallbackProtocol = RelayCallbackProtocol(), now: Long = 1000, elapsed: Long = 50): RelayCallbackProtocol {
        assertNotNull(protocol.receive(envelope(), binding, prefs, now, elapsed))
        return protocol
    }

    @Test fun `wire decoder rejects foreign channels types and malformed envelope fields`() {
        assertNotNull(RelayCallbackWire.parse(envelope()))
        for (message in listOf(envelope().put("channel", "voice"), envelope().put("type", "unknown"),
            envelope().put("payload", "{}"), envelope().put("id", JSONObject.NULL), envelope().put("id", 123),
            envelope().put("id", ""), envelope().put("id", "x".repeat(257)), JSONObject(),
            envelope("phone.call.state", payload()), envelope("phone.call.error", JSONObject().put("error", "unauthorized")),
            envelope("phone.call.action", JSONObject().put("call_id", id).put("action", "answer")))) {
            assertNull(RelayCallbackWire.parse(message))
        }
    }

    @Test fun `malformed payloads and foreign device profile or session fail closed`() {
        val malicious = listOf("call_id" to "no", "call_id" to "1-1-1-1-1", "call_id" to JSONObject.NULL,
            "device_id" to "foreign", "device_id" to JSONObject.NULL, "profile" to "other", "profile" to JSONObject(),
            "profile" to "", "session_id" to "other", "session_id" to JSONObject.NULL, "session_id" to 3,
            "reason" to JSONObject(), "title" to JSONObject.NULL, "created_at" to "1000", "created_at" to 1000.5,
            "created_at" to -1L, "created_at" to Long.MAX_VALUE, "expires_at" to "21000", "expires_at" to 21000.1,
            "expires_at" to 21001L, "expires_at" to Long.MIN_VALUE, "status" to "connected")
        for ((key, value) in malicious) {
            assertNull("$key=$value", RelayCallback.parse(payload().put(key, value), binding, 1000, 50))
        }
        for (key in listOf("call_id", "device_id", "created_at", "expires_at", "status", "profile", "session_id")) {
            assertNull(key, RelayCallback.parse(payload().apply { remove(key) }, binding, 1000, 50))
        }
        for (foreign in listOf(binding.copy(connectionId = ""), binding.copy(deviceId = " "), binding.copy(profile = ""),
            binding.copy(profile = "p".repeat(257)), binding.copy(sessionId = ""), binding.copy(sessionId = "s".repeat(513)),
            binding.copy(owner = ""), binding.copy(origin = "http://native.example"),
            binding.copy(origin = "https://user:password@native.example"), binding.copy(origin = "https://native.example/path"))) {
            assertNull(RelayCallback.parse(payload(), foreign, 1000, 50))
        }
    }

    @Test fun `optional default profile null session and bounded display fields preserve upstream wire`() {
        val data = payload().apply { remove("profile"); remove("session_id"); remove("title"); remove("reason") }
        val trusted = binding.copy(profile = "default", sessionId = null)
        val call = RelayCallback.parse(data, trusted, 1000, 50)!!
        assertEquals("Hermes", call.title)
        assertEquals("", call.reason)
        assertNotNull(RelayCallback.parse(data.put("session_id", JSONObject.NULL), trusted, 1000, 50))
        assertNull(RelayCallback.parse(data.put("session_id", ""), trusted, 1000, 50))
        val bounded = RelayCallback.parse(payload().put("title", "t".repeat(200)).put("reason", "r".repeat(2000)), binding, 1000, 50)!!
        assertEquals(120, bounded.title.length)
        assertEquals(1000, bounded.reason.length)
        assertEquals("Hermes", RelayCallback.parse(payload().put("title", " "), binding, 1000, 50)!!.title)
    }

    @Test fun `Beacon event ids stay opaque while Relay UUID casing remains canonical`() {
        val eventId = "c0ffee00112233445566778899aabbcc"
        assertEquals(eventId, RelayCallback.callId(eventId))
        assertNull(RelayCallback.callId("beacon-event.01:abc"))
        assertEquals(id, RelayCallback.callId(id.uppercase()))
        assertNotNull(RelayCallback.parse(payload().put("call_id", eventId), binding, 1000, 50))
    }

    @Test fun `late delivery expiry and rollback cannot extend monotonic ringing`() {
        val call = RelayCallback.parse(payload(), binding, 20000, 50)!!
        assertEquals(1000L, call.remainingRingMillis(20000, 50))
        assertEquals(1L, call.remainingRingMillis(20999, 1049))
        assertEquals(0L, call.remainingRingMillis(20000, 1050))
        assertEquals(0L, call.remainingRingMillis(19999, 51))
        assertEquals(0L, call.remainingRingMillis(20001, 49))
        assertNull(RelayCallback.parse(payload(), binding, 21000, 50))
        assertNull(RelayCallbackProtocol().receive(envelope(), binding, prefs, 21000, 50))
        assertNull(RelayCallback.parse(payload(), binding, -1, 50))
        assertNull(RelayCallback.parse(payload(), binding, 1000, -1))
        assertNull(RelayCallback.parse(payload(), binding, 1000, Long.MAX_VALUE))
    }

    @Test fun `five second future skew is bounded without overflow or a longer ring`() {
        val future = payload().put("created_at", 6000L).put("expires_at", 26000L)
        val call = RelayCallback.parse(future, binding, 1000, 50)!!
        assertEquals(20000L, call.remainingRingMillis(1000, 50))
        assertEquals(0L, call.remainingRingMillis(1000, 20050))
        assertNull(RelayCallback.parse(future.put("created_at", 6001L).put("expires_at", 26001L), binding, 1000, 50))
        assertNull(RelayCallback.parse(payload().put("created_at", Long.MAX_VALUE - 100).put("expires_at", Long.MAX_VALUE), binding, 1000, 50))
        val edge = payload().put("created_at", Long.MAX_VALUE - 20000).put("expires_at", Long.MAX_VALUE)
        assertNotNull(RelayCallback.parse(edge, binding, Long.MAX_VALUE - 20000, 50))
    }

    @Test fun `receive requires current connection bound calls consent`() {
        for (snapshot in listOf(null, prefs.copy(connectionId = null), prefs.copy(connectionId = "foreign"),
            prefs.copy(calls = false), prefs.copy(keepConnected = false))) {
            val protocol = RelayCallbackProtocol()
            assertNull(protocol.receive(envelope(), binding, snapshot, 1000, 50))
            assertNull(protocol.current)
        }
        val protocol = invite()
        assertEquals(RelayCallbackPhase.Ringing, protocol.current!!.phase)
    }

    @Test fun `duplicate UUID casing and busy calls do not replace local capability`() {
        val protocol = invite()
        val call = protocol.current!!
        assertNull(protocol.receive(envelope(), binding, prefs, 1000, 50))
        assertNull(protocol.receive(envelope(data = payload(id.uppercase())), binding, prefs, 1000, 50))
        val busy = protocol.receive(envelope(data = payload(UUID.randomUUID().toString())), binding, prefs, 1000, 50)!!
        assertEquals(RelayCallbackPhase.Finished, busy.call.phase)
        assertEquals(call, protocol.current)
    }

    @Test fun `an expired ring ends once and cannot be resurrected`() {
        val expired = invite(now = 20000)
        assertEquals(RelayCallbackPhase.Finished, expired.expire(20000, 1050, binding, prefs)!!.call.phase)
        assertNull(expired.expire(21000, 1050, binding, prefs))
        assertNull(expired.receive(envelope(), binding, prefs, 1000, 50))
    }

    @Test fun `clock rollback retires a ringing call`() {
        assertEquals(RelayCallbackPhase.Finished, invite().expire(999, 51, binding, prefs)!!.call.phase)
    }

    @Test fun `opt out profile session or connection loss retires authority without foreign actions`() {
        for (other in listOf(null, binding.copy(connectionId = "other"), binding.copy(deviceId = "other"),
            binding.copy(profile = "other"), binding.copy(sessionId = "other"),
            binding.copy(owner = "other"), binding.copy(origin = "https://other.example"))) {
            val protocol = invite()
            assertEquals(RelayCallbackPhase.Finished, protocol.invalidate(other, prefs)!!.call.phase)
            assertEquals(RelayCallbackPhase.Finished, protocol.current!!.phase)
        }
        val protocol = invite()
        assertEquals(RelayCallbackPhase.Finished, protocol.invalidate(binding, prefs.copy(calls = false))!!.call.phase)
    }

    @Test fun `unknown preferences keep a ringing call while a lost binding still retires it`() {
        val protocol = invite()

        assertNull(protocol.invalidate(binding, null))
        assertEquals(RelayCallbackPhase.Ringing, protocol.current!!.phase)

        assertNotNull(protocol.invalidate(null, null))
        assertEquals(RelayCallbackPhase.Finished, protocol.current!!.phase)
    }

    @Test fun `an invitation after a retired one gets its own action token`() {
        val protocol = invite()
        val old = protocol.current!!
        assertNotNull(protocol.invalidate(null, null))
        val nextId = UUID.randomUUID().toString()
        assertNotNull(protocol.receive(envelope(data = payload(nextId)), binding, prefs, 1000, 50))
        assertNotEquals(old.actionToken, protocol.current!!.actionToken)
        assertEquals(nextId, protocol.current!!.callId)
        assertEquals(RelayCallbackPhase.Ringing, protocol.current!!.phase)
    }

    @Test fun `replay memory capacity fails closed without evicting old terminal UUIDs`() {
        val protocol = RelayCallbackProtocol()
        repeat(256) {
            val callId = if (it == 0) id else UUID.randomUUID().toString()
            assertNotNull(protocol.receive(envelope(data = payload(callId)), binding, prefs, 1000, 50))
            assertNotNull(protocol.invalidate(null, null))
        }
        assertNull(protocol.receive(envelope(data = payload(UUID.randomUUID().toString())), binding, prefs, 1000, 50))
        assertNull(protocol.receive(envelope(), binding, prefs, 1000, 50))
        assertEquals(RelayCallbackPhase.Finished, protocol.current!!.phase)
    }
}
