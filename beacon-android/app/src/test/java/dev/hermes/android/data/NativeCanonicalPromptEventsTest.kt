package dev.hermes.android.data

import dev.hermes.android.core.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * A chat on the canonical session authority never receives Hermes' `approval` / `clarify` server requests (those belong to the legacy
 * tui_gateway). Hermes publishes the same questions as events: `approval.request` / `clarify.request` carrying the prompt that
 * gateway/session_pending_controls.py PendingControls.register builds, and `approval.settled` / `clarify.settled` when it ends.
 * Dropping them left every approval invisible while the stream was attached (the any-site card payment waited its 300 s and was declined).
 * The prompt shapes below are exactly PendingControls.register's.
 */
class NativeCanonicalPromptEventsTest {
    private val promptId = "0123456789abcdef0123456789abcdef"
    private val cardCommand = "Fill payment card 'Test card' on http://127.0.0.1:8793"
    private val cardWarning = "The agent wants to enter your saved card details into this checkout page. The card number and CVC never enter the conversation. Approve only if you intend to pay here."

    private fun approvalPrompt(id: String = promptId, command: String = cardCommand, description: String = cardWarning) =
        json("kind" to "approval", "prompt_id" to id, "execution_generation" to 7, "command" to command, "description" to description,
            "choices" to JSONArray().put("once").put("deny"), "timeout_s" to 300)

    private fun clarifyPrompt(id: String = "fedcba9876543210fedcba9876543210") =
        json("kind" to "clarify", "prompt_id" to id, "execution_generation" to 7, "question" to "Which size?",
            "choices" to JSONArray().put("Small").put("Large"), "multi_select" to false)

    private fun settled(id: String) = json("prompt_id" to id, "execution_generation" to 7)

    /** Attach a live stream to a canonical chat, run [emit], and return what the stream delivered once [expected] events arrived. */
    private fun delivered(expected: Int, prepare: NativeOperationsTest.Fixture.() -> Unit = {}, emit: NativeOperationsTest.Fixture.() -> Unit): List<SseEvent> = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply(prepare)
        val events = java.util.concurrent.CopyOnWriteArrayList<SseEvent>()
        val collector = launch(Dispatchers.IO) { f.client.nativeStream("/api/chat/stream", mapOf("session_id" to "stored-a")) { events += it } }
        try {
            withTimeout(5_000) { while (f.calls.count { it.string("method") == "session.events.since" } < 2) delay(10) }
            f.emit()
            withTimeout(5_000) { while (events.size < expected) delay(10) }
            events.toList()
        } finally { collector.cancelAndJoin(); f.client.closeNative() }
    }

    @Test fun aCanonicalApprovalRequestReachesTheChatAsTheApprovalCardWithHermesPromptId() {
        val event = delivered(1, emit = { emitEvent("stored-a", 1, "approval.request", approvalPrompt()) }).single()
        assertEquals("approval", event.type)
        val shown = event.payload()
        assertEquals(promptId, shown.string("approval_id"))
        assertEquals("the chat withdraws a card by this id", promptId, shown.string("id"))
        assertEquals(cardCommand, shown.string("command"))
        assertEquals(cardWarning, shown.string("description"))
        assertEquals(listOf("once", "deny"), shown.getJSONArray("choices").strings())
        assertEquals("stored-a", shown.string("session_id"))
        assertFalse("Hermes sends no surface on this route; only the plugin may add one", shown.has("surface"))
    }

    @Test fun aSurfaceTheRelayAddedIsKeptForTheCardFace() {
        val marked = approvalPrompt().put("surface", "vault-payment")
        val event = delivered(1, emit = { emitEvent("stored-a", 1, "approval.request", marked) }).single()
        assertEquals("approval", event.type)
        assertEquals("vault-payment", event.payload().string("surface"))
    }

    @Test fun aSettledApprovalWithdrawsTheCardByTheSameId() {
        val events = delivered(2, emit = {
            emitEvent("stored-a", 1, "approval.request", approvalPrompt())
            emitEvent("stored-a", 2, "approval.settled", settled(promptId))
        })
        assertEquals(listOf("approval", "request_cancel"), events.map { it.type })
        assertEquals(promptId, events[1].payload().string("id"))
        assertEquals(events[0].payload().string("id"), events[1].payload().string("id"))
    }

    @Test fun aCanonicalClarifyRequestAndItsSettleReachTheChatToo() {
        val clarifyId = "fedcba9876543210fedcba9876543210"
        val events = delivered(2, emit = {
            emitEvent("stored-a", 1, "clarify.request", clarifyPrompt(clarifyId))
            emitEvent("stored-a", 2, "clarify.settled", settled(clarifyId))
        })
        assertEquals(listOf("clarify", "request_cancel"), events.map { it.type })
        assertEquals(clarifyId, events[0].payload().string("clarify_id"))
        assertEquals(clarifyId, events[0].payload().string("id"))
        assertEquals("Which size?", events[0].payload().string("question"))
        assertEquals(clarifyId, events[1].payload().string("id"))
    }

    @Test fun aPromptEventWithoutAnIdIsNotTurnedIntoACard() {
        val events = delivered(2, emit = {
            emitEvent("stored-a", 1, "approval.request", approvalPrompt(id = ""))
            emitEvent("stored-a", 2, "approval.settled", json("execution_generation" to 7))
        })
        assertEquals(listOf("native_event", "native_event"), events.map { it.type })
    }

    @Test fun theCardShownFromTheEventIsAnsweredOnTheSameRequestThroughTheCanonicalRoute() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply { prompts = JSONArray().put(approvalPrompt()) }
        val events = java.util.concurrent.CopyOnWriteArrayList<SseEvent>()
        // The chat streams on its own detached connection and answers on the primary one, as the app does.
        val collector = launch(Dispatchers.IO) { f.client.stream("/api/chat/stream", mapOf("session_id" to "stored-a")) { events += it } }
        try {
            withTimeout(5_000) { while (f.calls.count { it.string("method") == "session.events.since" } < 2) delay(10) }
            f.emitEvent("stored-a", 1, "approval.request", approvalPrompt())
            withTimeout(5_000) { while (events.isEmpty()) delay(10) }
            val shown = events.single().payload()
            val body = json("session_id" to "stored-a", "scope_key" to f.client.scope.session("stored-a").key, "approval_id" to shown.string("approval_id"), "choice" to "once")
            assertEquals("resolved", f.client.post("/api/approval/respond", body).string("status"))
            val sent = f.calls.single { it.string("method") == "approval.respond" }.getJSONObject("params")
            assertEquals(promptId, sent.string("prompt_id"))
            assertEquals(7, sent.getInt("execution_generation"))
            assertEquals("once", sent.string("choice"))
        } finally { collector.cancelAndJoin(); f.client.closeNative() }
    }

    private val secondId = "11111111111111111111111111111111"

    /** Hermes holds both prompts at once (parallel tool calls): both reach the chat as their own card, Hermes lists the second behind the first, and each is answered on its own id. */
    @Test fun twoApprovalsAtOnceBothReachTheChatAndAreAnsweredEachOnItsOwnPromptId() = runBlocking {
        val second = approvalPrompt(secondId, command = "Run ls")
        val f = NativeOperationsTest.Fixture(canonical = true).apply { prompts = JSONArray().put(approvalPrompt()).put(second) }
        val events = java.util.concurrent.CopyOnWriteArrayList<SseEvent>()
        val collector = launch(Dispatchers.IO) { f.client.stream("/api/chat/stream", mapOf("session_id" to "stored-a")) { events += it } }
        try {
            withTimeout(5_000) { while (f.calls.count { it.string("method") == "session.events.since" } < 2) delay(10) }
            f.emitEvent("stored-a", 1, "approval.request", approvalPrompt())
            f.emitEvent("stored-a", 2, "approval.request", second)
            withTimeout(5_000) { while (events.size < 2) delay(10) }
            assertEquals(listOf("approval", "approval"), events.map { it.type })
            assertEquals(listOf(promptId, secondId), events.map { it.payload().string("approval_id") })
            val pending = f.client.get("/api/approval/pending", mapOf("session_id" to "stored-a"))
            assertEquals(promptId, pending.getJSONObject("pending").string("approval_id"))
            assertEquals(listOf(secondId), pending.rows("waiting").map { it.string("approval_id") })
            for (id in listOf(promptId, secondId)) {
                val body = json("session_id" to "stored-a", "scope_key" to f.client.scope.session("stored-a").key, "approval_id" to id, "choice" to "once")
                assertEquals(id, f.client.post("/api/approval/respond", body).string("prompt_id"))
            }
            assertEquals(listOf(promptId, secondId), f.calls.filter { it.string("method") == "approval.respond" }.map { it.getJSONObject("params").string("prompt_id") })
        } finally { collector.cancelAndJoin(); f.client.closeNative() }
    }

    @Test fun aClarificationBehindAnotherOneIsListedAsWaiting() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply { prompts = JSONArray().put(clarifyPrompt()).put(clarifyPrompt("22222222222222222222222222222222")) }
        try {
            val pending = f.client.get("/api/clarify/pending", mapOf("session_id" to "stored-a"))
            assertEquals("fedcba9876543210fedcba9876543210", pending.getJSONObject("pending").string("clarify_id"))
            assertEquals(listOf("22222222222222222222222222222222"), pending.rows("waiting").map { it.string("clarify_id") })
        } finally { f.client.closeNative() }
    }

    @Test fun anApprovalWithNothingBehindItListsNothingWaiting() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply { prompts = JSONArray().put(approvalPrompt()) }
        try {
            val pending = f.client.get("/api/approval/pending", mapOf("session_id" to "stored-a"))
            assertEquals(promptId, pending.getJSONObject("pending").string("approval_id"))
            assertTrue(pending.rows("waiting").isEmpty())
        } finally { f.client.closeNative() }
    }

    /** Hermes ended the prompt (its own 300 s wait, or it settled while the phone was away) and a snapshot no longer holds it: the tap is answered "ended", not as a lost connection, and sends nothing. */
    @Test fun aTapOnAnApprovalHermesAlreadyEndedSendsNothingAndSaysItEnded() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        try {
            val body = json("session_id" to "stored-a", "scope_key" to f.client.scope.session("stored-a").key, "approval_id" to promptId, "choice" to "once")
            val result = f.client.post("/api/approval/respond", body)
            assertEquals("ended", result.string("status"))
            assertEquals(promptId, result.string("prompt_id"))
            assertTrue(f.calls.none { it.string("method") in setOf("approval.respond", "clarify.respond") })
        } finally { f.client.closeNative() }
    }

    @Test fun aTapOnAQuestionHermesAlreadyEndedSendsNothingAndSaysItEnded() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        try {
            val body = json("session_id" to "stored-a", "scope_key" to f.client.scope.session("stored-a").key, "clarify_id" to "fedcba9876543210fedcba9876543210", "response" to "Small")
            val result = f.client.post("/api/clarify/respond", body)
            assertEquals("ended", result.string("status"))
            assertTrue(f.calls.none { it.string("method") in setOf("approval.respond", "clarify.respond") })
        } finally { f.client.closeNative() }
    }

    /** Guard, not part of the fix: without session authority a missing server request may be a dropped socket, so that gateway keeps its own failure. */
    @Test fun aGatewayWithoutSessionAuthorityKeepsItsFailureForARequestItDoesNotHold() = runBlocking {
        val f = NativeOperationsTest.Fixture()
        try {
            val body = json("session_id" to "stored-a", "scope_key" to f.client.scope.session("stored-a").key, "approval_id" to "req-gone", "choice" to "deny")
            val failure = runCatching { f.client.post("/api/approval/respond", body) }.exceptionOrNull()
            assertTrue("expected the server-request failure, got $failure", failure is NativeRequestEnded)
            assertTrue(f.calls.none { it.string("method") in setOf("approval.respond", "clarify.respond") })
        } finally { f.client.closeNative() }
    }
}
