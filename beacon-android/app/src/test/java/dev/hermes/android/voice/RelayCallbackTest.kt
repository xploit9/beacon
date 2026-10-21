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
    private fun answer(protocol: RelayCallbackProtocol) =
        protocol.answer(id, protocol.current!!.actionToken, binding, prefs, 1000, 50)!!
    private fun accept(protocol: RelayCallbackProtocol): RelayCallbackProtocol {
        val outgoing = answer(protocol).outgoing!!
        assertNotNull(protocol.receive(envelope("phone.call.state", payload().put("status", "accepted"), outgoing.getString("id")),
            binding, prefs, 1000, 50))
        return protocol
    }
    private fun start(protocol: RelayCallbackProtocol): Boolean = protocol.beginStart(id, protocol.current!!.actionToken,
        binding, prefs, true, true, 1000, 50)
    private fun foreground(protocol: RelayCallbackProtocol): Boolean = protocol.foregroundReady(id, binding, prefs, true, true, 1000, 50)
    private fun capture(protocol: RelayCallbackProtocol) = protocol.captureStarted(id, binding, prefs, true, true, 1000, 50)
    private fun verb(effect: RelayCallbackTransition?) = effect?.outgoing?.getJSONObject("payload")?.getString("action")

    @Test fun `wire decoder rejects foreign channels types and malformed envelope fields`() {
        assertNotNull(RelayCallbackWire.parse(envelope()))
        for (message in listOf(envelope().put("channel", "voice"), envelope().put("type", "unknown"),
            envelope().put("payload", "{}"), envelope().put("id", JSONObject.NULL), envelope().put("id", 123),
            envelope().put("id", ""), envelope().put("id", "x".repeat(257)), JSONObject())) {
            assertNull(RelayCallbackWire.parse(message))
        }
        assertNotNull(RelayCallbackWire.parse(envelope("phone.call.error", JSONObject().put("error", "unauthorized"))))
        assertNotNull(RelayCallbackWire.parse(envelope("phone.call.action", JSONObject().put("call_id", id).put("action", "answer"))))
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

    @Test fun `receive requires current connection bound calls consent and cannot start voice`() {
        for (snapshot in listOf(null, prefs.copy(connectionId = null), prefs.copy(connectionId = "foreign"),
            prefs.copy(calls = false), prefs.copy(keepConnected = false))) {
            val protocol = RelayCallbackProtocol()
            assertNull(protocol.receive(envelope(), binding, snapshot, 1000, 50))
            assertNull(protocol.current)
            assertFalse(foreground(protocol))
        }
        val protocol = invite()
        assertEquals(RelayCallbackPhase.Ringing, protocol.current!!.phase)
        assertFalse(start(protocol))
        assertFalse(foreground(protocol))
        assertNull(capture(protocol))
    }

    @Test fun `duplicate UUID casing and busy calls do not replace local capability`() {
        val protocol = invite()
        val call = protocol.current!!
        assertNull(protocol.receive(envelope(), binding, prefs, 1000, 50))
        assertNull(protocol.receive(envelope(data = payload(id.uppercase())), binding, prefs, 1000, 50))
        val busy = protocol.receive(envelope(data = payload(UUID.randomUUID().toString())), binding, prefs, 1000, 50)!!
        assertEquals("decline", verb(busy))
        assertFalse(busy.stopVoice)
        assertFalse(busy.stopAlerting)
        assertEquals(call, protocol.current)
        assertFalse(busy.outgoing.toString().contains(call.actionToken))
    }

    @Test fun `answer rejects foreign identity forged tokens expired rings and repeat gestures`() {
        val protocol = invite()
        val call = protocol.current!!
        assertNull(protocol.answer("other", call.actionToken, binding, prefs, 1000, 50))
        assertNull(protocol.answer(id, "forged", binding, prefs, 1000, 50))
        assertNull(protocol.answer(id, call.actionToken, binding.copy(connectionId = "foreign"), prefs, 1000, 50))
        assertNull(protocol.answer(id, call.actionToken, binding.copy(sessionId = "foreign"), prefs, 1000, 50))
        assertNull(protocol.answer(id, call.actionToken, binding, prefs, 21000, 50))
        val effect = answer(protocol)
        assertEquals("answer", verb(effect))
        assertTrue(effect.stopAlerting)
        assertFalse(effect.stopVoice)
        assertEquals(2, effect.outgoing!!.getJSONObject("payload").length())
        assertFalse(effect.outgoing.toString().contains(call.actionToken))
        val retry = protocol.answer(id, call.actionToken, binding, prefs, 1000, 50)!!
        assertEquals(effect.call.startDeadlineElapsed, retry.call.startDeadlineElapsed)
        assertNull(retry.outgoing)
        assertNull(protocol.answer(id, call.actionToken, binding, prefs, 1000, effect.call.startDeadlineElapsed))
        assertFalse(start(protocol))
    }

    @Test fun `answer can be retried after local setup without extending the start deadline`() {
        val protocol = invite()
        val first = answer(protocol)
        val deadline = first.call.startDeadlineElapsed

        val retry = protocol.answer(id, first.call.actionToken, binding, prefs, 2_000, 1_050)

        assertNotNull(retry)
        assertEquals(RelayCallbackPhase.Answering, retry!!.call.phase)
        assertEquals(deadline, retry.call.startDeadlineElapsed)
        assertNull(retry.outgoing)
        val accepted = protocol.receive(
            envelope("phone.call.state", payload().put("status", "accepted"), first.outgoing!!.getString("id")),
            binding, prefs, 2_000, 1_050,
        )
        assertEquals(RelayCallbackPhase.Accepted, accepted!!.call.phase)
    }

    @Test fun `answering call can still be declined or ended before startup`() {
        val declined = invite()
        val token = answer(declined).call.actionToken
        val decline = declined.decline(id, token, binding, prefs, 2_000, 1_050)
        assertEquals("decline", verb(decline))
        assertFalse(decline!!.stopVoice)

        val ended = invite()
        val answering = answer(ended).call
        assertEquals("failed", verb(ended.end(id, answering.actionToken, binding, prefs)))
    }

    @Test fun `only correlated accepted acknowledgement advances an explicit answer`() {
        val protocol = invite()
        assertNull(protocol.receive(envelope("phone.call.state", payload().put("status", "accepted")), binding, prefs, 1000, 50))
        val ack = answer(protocol).outgoing!!.getString("id")
        for (data in listOf(payload().put("status", "accepted").put("device_id", "foreign"),
            payload().put("status", "accepted").put("profile", "other"),
            payload().put("status", "accepted").put("session_id", "other"),
            payload(UUID.randomUUID().toString()).put("status", "accepted"))) {
            assertNull(protocol.receive(envelope("phone.call.state", data, ack), binding, prefs, 1000, 50))
        }
        assertNull(protocol.receive(envelope("phone.call.state", payload().put("status", "accepted"), "unrelated"), binding, prefs, 1000, 50))
        assertEquals(RelayCallbackPhase.Answering, protocol.current!!.phase)
        // Server state supplies the original scope, or inherits it from the trusted invite.
        val data = payload().put("status", "accepted").apply { remove("profile"); remove("session_id") }
        assertNotNull(protocol.receive(envelope("phone.call.state", data, ack), binding, prefs, 1000, 50))
        assertEquals(RelayCallbackPhase.Accepted, protocol.current!!.phase)
        assertNull(protocol.receive(envelope("phone.call.state", data, ack), binding, prefs, 1000, 50))
    }

    @Test fun `accepted permission association and foreground admission are each single use`() {
        val protocol = accept(invite())
        val token = protocol.current!!.actionToken
        assertNull(protocol.permissionTarget("old", true, binding, prefs, 1000, 50))
        assertNull(protocol.permissionTarget(id, false, binding, prefs, 1000, 50))
        assertEquals(id, protocol.permissionTarget(id, true, binding, prefs, 1000, 50))
        assertFalse(protocol.beginStart(id, token, binding, prefs, false, true, 1000, 50))
        assertFalse(protocol.beginStart(id, token, binding, prefs, true, false, 1000, 50))
        assertFalse(protocol.beginStart(id, "forged", binding, prefs, true, true, 1000, 50))
        assertTrue(start(protocol))
        assertFalse(start(protocol))
        assertNull(capture(protocol))
        assertFalse(protocol.foregroundReady(id, binding, prefs, false, true, 1000, 50))
        assertTrue(foreground(protocol))
        assertFalse(foreground(protocol))
        assertEquals("started", verb(capture(protocol)))
        assertNull(capture(protocol))
        assertTrue(protocol.canContinue(id, binding, prefs, true, true, 1000, 30050))
        assertFalse(protocol.canContinue(id, binding, prefs, false, true, 1000, 30050))
        assertEquals(RelayCallbackPhase.Connected, protocol.current!!.phase)
    }

    @Test fun `decline timeout and terminal replay never authorize microphone or resurrection`() {
        val protocol = invite()
        assertNull(protocol.decline(id, "forged", binding, prefs, 1000, 50))
        val decline = protocol.decline(id, protocol.current!!.actionToken, binding, prefs, 1000, 50)!!
        assertEquals("decline", verb(decline))
        assertTrue(decline.stopAlerting)
        assertFalse(decline.stopVoice)
        assertFalse(start(protocol))
        assertNull(protocol.receive(envelope(), binding, prefs, 1000, 50))
        assertNull(protocol.receive(envelope("phone.call.state", payload().put("status", "accepted")), binding, prefs, 1000, 50))
        assertNull(protocol.decline(id, decline.call.actionToken, binding, prefs, 1000, 50))
        val expired = invite(now = 20000)
        val missed = expired.expire(20000, 1050, binding, prefs)!!
        assertTrue(missed.missed)
        assertNull(missed.outgoing)
        assertFalse(missed.stopVoice)
        assertFalse(start(expired))
        assertNull(expired.expire(21000, 1050, binding, prefs))
    }

    @Test fun `startup deadline and clock reset retire answering accepted and starting states`() {
        for (stage in 0..2) {
            val protocol = invite()
            if (stage == 0) answer(protocol) else accept(protocol)
            if (stage == 2) assertTrue(start(protocol))
            assertNull(protocol.expire(1000, 30049, binding, prefs))
            val failed = protocol.expire(1000, 30050, binding, prefs)!!
            assertEquals("failed", verb(failed))
            assertEquals(stage == 2, failed.stopVoice)
            assertFalse(foreground(protocol))
            assertNull(capture(protocol))
        }
        assertTrue(invite().expire(999, 51, binding, prefs)!!.missed)
        assertEquals("failed", verb(accept(invite()).expire(1000, 49, binding, prefs)))
    }

    @Test fun `startup expiry does not emit an action without current connection authority`() {
        val protocol = invite()
        answer(protocol)

        val expired = protocol.expire(1_000, 30_050, binding.copy(connectionId = "foreign"), prefs)

        assertNotNull(expired)
        assertNull(expired!!.outgoing)
    }

    @Test fun `only current action errors retire a call and terminal states stop owned voice once`() {
        val protocol = invite()
        val ack = answer(protocol).outgoing!!.getString("id")
        val error = JSONObject().put("error", "unauthorized")
        assertNull(protocol.receive(envelope("phone.call.error", error, "unrelated"), binding, prefs, 1000, 50))
        assertNull(protocol.receive(envelope("phone.call.error", JSONObject().put("device_id", "foreign"), ack), binding, prefs, 1000, 50))
        assertNotNull(protocol.receive(envelope("phone.call.error", error, ack), binding, prefs, 1000, 50))
        assertNull(protocol.receive(envelope("phone.call.error", error, ack), binding, prefs, 1000, 50))
        assertFalse(start(protocol))
        for (terminal in listOf("declined", "cancelled", "missed", "failed", "ended")) {
            val connected = accept(invite())
            assertTrue(start(connected)); assertTrue(foreground(connected)); assertNotNull(capture(connected))
            val message = envelope("phone.call.state", payload().put("status", terminal))
            val effect = connected.receive(message, binding, prefs, 1000, 50)!!
            assertTrue(effect.stopVoice)
            assertTrue(effect.stopAlerting)
            assertEquals(terminal == "missed", effect.missed)
            assertNull(connected.receive(message, binding, prefs, 1000, 50))
            assertNull(connected.receive(envelope("phone.call.state", payload().put("status", "accepted")), binding, prefs, 1000, 50))
            assertFalse(foreground(connected))
        }
    }

    @Test fun `opt out profile session or connection loss retires authority without foreign actions`() {
        for (other in listOf(null, binding.copy(connectionId = "other"), binding.copy(deviceId = "other"),
            binding.copy(profile = "other"), binding.copy(sessionId = "other"),
            binding.copy(owner = "other"), binding.copy(origin = "https://other.example"))) {
            val protocol = accept(invite())
            assertTrue(start(protocol))
            val effect = protocol.invalidate(other, prefs)!!
            assertTrue(effect.stopVoice)
            assertNull(effect.outgoing)
            assertFalse(protocol.canContinue(id, binding, prefs, true, true, 1000, 50))
        }
        val protocol = invite()
        val effect = protocol.invalidate(binding, prefs.copy(calls = false))!!
        assertTrue(effect.missed)
        assertFalse(effect.stopVoice)
        assertNull(effect.outgoing)
    }

    @Test fun `unknown preferences preserve active voice while lost binding still tears it down`() {
        val protocol = accept(invite())
        assertTrue(start(protocol))
        assertTrue(foreground(protocol))
        assertNotNull(capture(protocol))

        assertNull(protocol.invalidate(binding, null))
        assertEquals(RelayCallbackPhase.Connected, protocol.current!!.phase)

        val invalidated = protocol.invalidate(null, null)!!
        assertTrue(invalidated.stopVoice)
        assertNull(invalidated.outgoing)
    }

    @Test fun `hangup is capability bound and delayed old actions cannot end next call`() {
        val protocol = accept(invite())
        assertTrue(start(protocol)); assertTrue(foreground(protocol)); assertNotNull(capture(protocol))
        val old = protocol.current!!
        assertNull(protocol.end(id, "forged", binding, prefs))
        assertEquals("hangup", verb(protocol.end(id, old.actionToken, binding, prefs)))
        assertNull(protocol.end(id, old.actionToken, binding, prefs))
        val nextId = UUID.randomUUID().toString()
        assertNotNull(protocol.receive(envelope(data = payload(nextId)), binding, prefs, 1000, 50))
        assertNotEquals(old.actionToken, protocol.current!!.actionToken)
        assertNull(protocol.end(nextId, old.actionToken, binding, prefs))
        assertNull(protocol.end(id, old.actionToken, binding, prefs))
        assertEquals(RelayCallbackPhase.Ringing, protocol.current!!.phase)
    }

    @Test fun `foreign terminal identity and stale prior call errors cannot retire a newer invitation`() {
        val protocol = invite()
        val oldAnswer = answer(protocol).outgoing!!.getString("id")
        for (data in listOf(payload().put("device_id", "foreign"), payload().put("profile", "other"),
            payload().put("session_id", JSONObject.NULL), payload(UUID.randomUUID().toString()))) {
            assertNull(protocol.receive(envelope("phone.call.state", data.put("status", "ended")), binding, prefs, 1000, 50))
        }
        assertEquals(RelayCallbackPhase.Answering, protocol.current!!.phase)
        assertNotNull(protocol.end(id, protocol.current!!.actionToken, binding, prefs))
        val nextId = UUID.randomUUID().toString()
        assertNotNull(protocol.receive(envelope(data = payload(nextId)), binding, prefs, 1000, 50))
        assertNull(protocol.receive(envelope("phone.call.error", JSONObject().put("error", "unauthorized"), oldAnswer), binding, prefs, 1000, 50))
        assertEquals(nextId, protocol.current!!.callId)
        assertEquals(RelayCallbackPhase.Ringing, protocol.current!!.phase)
    }

    @Test fun `replay memory capacity fails closed without evicting old terminal UUIDs`() {
        val protocol = RelayCallbackProtocol()
        repeat(256) {
            val callId = if (it == 0) id else UUID.randomUUID().toString()
            assertNotNull(protocol.receive(envelope(data = payload(callId)), binding, prefs, 1000, 50))
            val call = protocol.current!!
            assertNotNull(protocol.decline(callId, call.actionToken, binding, prefs, 1000, 50))
        }
        assertNull(protocol.receive(envelope(data = payload(UUID.randomUUID().toString())), binding, prefs, 1000, 50))
        assertNull(protocol.receive(envelope(), binding, prefs, 1000, 50))
        assertEquals(RelayCallbackPhase.Finished, protocol.current!!.phase)
    }
}
