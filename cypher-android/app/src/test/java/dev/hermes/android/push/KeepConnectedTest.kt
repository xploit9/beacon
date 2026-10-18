package dev.hermes.android.push

import dev.hermes.android.core.Scope
import dev.hermes.android.data.NativeAuthExpiredException
import dev.hermes.android.voice.RelayCallbackPhase
import dev.hermes.android.voice.RelayCallbackPolicy
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class KeepConnectedTest {
    private fun callEvent(
        owner: String = "owner",
        profile: String = "default",
        expiresAt: Long = 1_120,
    ) = JSONObject()
        .put("event_id", "c0ffee00112233445566778899aabbcc")
        .put("callback_id", "c0ffee00112233445566778899aabbcc")
        .put("source_ref", "source-opaque-1")
        .put("conversation_ref", "conversation-opaque-1")
        .put("kind", "incoming_call")
        .put("owner_id", owner)
        .put("profile", profile)
        .put("session_id", "session-1")
        .put("conversation_id", "session-1")
        .put("state", "pending")
        .put("expires_at", expiresAt)

    private fun cursors(values: MutableMap<String, String> = mutableMapOf()) = KeepConnectedCursors(
        read = values::get,
        write = { key, value -> values[key] = value; true },
    )

    @Test fun cursorPersistsPerOriginOwnerAndProfile() {
        val values = mutableMapOf<String, String>()
        val store = cursors(values)

        assertEquals(0L, store.read("https://one.example", "owner", "default"))
        store.advance("https://one.example", "owner", "default", 12)

        assertEquals(12L, cursors(values).read("https://one.example", "owner", "default"))
        assertEquals(0L, store.read("https://two.example", "owner", "default"))
        assertEquals(0L, store.read("https://one.example", "owner", "coder"))
        assertEquals(0L, store.read("https://one.example", "another-owner", "default"))
    }

    @Test fun backoffDoublesAndStopsAtOneMinute() {
        assertEquals(1_000L, keepConnectedBackoffMs(1))
        assertEquals(2_000L, keepConnectedBackoffMs(2))
        assertEquals(32_000L, keepConnectedBackoffMs(6))
        assertEquals(60_000L, keepConnectedBackoffMs(7))
        assertEquals(60_000L, keepConnectedBackoffMs(100))
    }

    @Test fun successfulPollPacingEnforcesAMinimumCycleWithoutStackingAfterLongPoll() {
        assertEquals(500L, keepConnectedPollDelay(0))
        assertEquals(300L, keepConnectedPollDelay(200))
        assertEquals(0L, keepConnectedPollDelay(500))
        assertEquals(0L, keepConnectedPollDelay(1_500))
        assertEquals(500L, keepConnectedPollDelay(-1))
    }

    @Test fun eachAuthorizedProfileIsPolledAndGetsItsOwnCursor() = runTest {
        val store = cursors().apply {
            advance("https://hermes.example", "owner", "default", 4)
            advance("https://hermes.example", "owner", "coder", 9)
        }
        val requests = mutableMapOf<String, Long>()
        val poller = KeepConnectedPoller(
            origin = "https://hermes.example",
            owner = "owner",
            cursors = store,
            profiles = { listOf("default", "coder") },
            poll = { profile, after ->
                requests[profile] = after
                KeepConnectedPage(emptyList(), after)
            },
            onEvent = { _, _ -> error("No events expected") },
        )

        assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
        assertEquals(mapOf("default" to 4L, "coder" to 9L), requests)
    }

    @Test fun replayedIncomingEventUsesTheExistingReceiptToAvoidAnotherRing() = runTest {
        val store = cursors()
        val event = JSONObject().put("event_id", "call-1")
        val receipts = mutableSetOf<String>()
        var rings = 0
        val poller = KeepConnectedPoller(
            origin = "https://hermes.example",
            owner = "owner",
            cursors = store,
            profiles = { listOf("default") },
            poll = { _, after -> KeepConnectedPage(listOf(event), after) },
            onEvent = { profile, item ->
                if (receipts.add("$profile:${item.getString("event_id")}")) rings += 1
            },
        )

        poller.pollOnce()
        poller.pollOnce()

        assertEquals(1, rings)
    }

    @Test fun refreshFailureStopsFurtherProfilePolling() = runTest {
        var requests = 0
        val poller = KeepConnectedPoller(
            origin = "https://hermes.example",
            owner = "owner",
            cursors = cursors(),
            profiles = { listOf("default", "coder") },
            poll = { _, _ -> requests += 1; throw NativeAuthExpiredException() },
            onEvent = { _, _ -> error("No events expected") },
        )

        assertEquals(KeepConnectedCycle.SignedOut, poller.pollOnce())
        assertEquals(KeepConnectedCycle.SignedOut, poller.pollOnce())
        assertTrue(requests <= 2)
    }

    @Test fun networkFailureBacksOffAndSuccessfulPollResetsIt() = runTest {
        var fail = true
        val poller = KeepConnectedPoller(
            origin = "https://hermes.example",
            owner = "owner",
            cursors = cursors(),
            profiles = { listOf("default") },
            poll = { _, after ->
                if (fail) { fail = false; throw IOException("offline") }
                KeepConnectedPage(emptyList(), after)
            },
            onEvent = { _, _ -> error("No events expected") },
        )

        assertEquals(KeepConnectedCycle.Reconnecting(1_000L), poller.pollOnce())
        assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
    }

    @Test fun cypherCallEventAdaptsToTheFailClosedRelayBindingAndKeepsTheTwentySecondRing() {
        val scope = Scope("https://hermes.example", "owner", "default")
        val prepared = prepareKeepConnectedCall(scope, callEvent(), enabled = true, now = 1_000_000L, elapsed = 500L)

        assertEquals("c0ffee00112233445566778899aabbcc", prepared!!.call.callId)
        assertEquals(scope.origin, prepared.binding.origin)
        assertEquals(scope.owner, prepared.binding.owner)
        assertEquals(scope.profile, prepared.binding.profile)
        assertEquals("source-opaque-1", prepared.binding.deviceId)
        assertEquals("session-1", prepared.binding.sessionId)
        assertEquals(RelayCallbackPhase.Ringing, prepared.call.phase)
        assertEquals(20_000L, prepared.call.remainingRingMillis(1_000_000L, 500L))
        assertTrue(RelayCallbackPolicy.callsAllowed(prepared.preferences, prepared.binding.connectionId))
        assertNull(prepared.protocol.answer("c0ffee00112233445566778899aabbcc", "forged", prepared.binding, prepared.preferences, 1_000_000L, 500L))
        assertEquals(RelayCallbackPhase.Answering,
            prepared.protocol.answer("c0ffee00112233445566778899aabbcc", prepared.call.actionToken, prepared.binding, prepared.preferences, 1_000_000L, 500L)?.call?.phase)
    }

    @Test fun cypherCallEventHonorsTheServerExpiryAndRejectsForeignOrMalformedScope() {
        val scope = Scope("https://hermes.example", "owner", "default")
        val short = prepareKeepConnectedCall(scope, callEvent(expiresAt = 1_005), true, 1_000_000L, 500L)
        assertEquals(5_000L, short!!.call.remainingRingMillis(1_000_000L, 500L))
        assertNull(prepareKeepConnectedCall(scope, callEvent(owner = "foreign"), true, 1_000_000L, 500L))
        assertNull(prepareKeepConnectedCall(scope, callEvent(profile = "coder"), true, 1_000_000L, 500L))
        assertNull(prepareKeepConnectedCall(scope, callEvent(), enabled = false, now = 1_000_000L, elapsed = 500L))
        assertNull(prepareKeepConnectedCall(scope, callEvent().put("conversation_ref", ""), true, 1_000_000L, 500L))
    }
}
