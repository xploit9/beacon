package dev.hermes.android.data

import dev.hermes.android.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * WEDGE-121 (CHATQA-121, 16 Nov 2026): Hermes restarted while a chat's reply ran a terminal tool. Hermes' next run fenced that turn `unknown`
 * (hermes_state_runtime.recover_session_inputs) and holds the chat until its person discards it: nothing queued behind it runs
 * (claim_session_input), Stop has nothing to interrupt and Delete is refused (unknown_execution). Hermes' own clients ask the person
 * (Desktop "Turn lost during restart" Discard, the dashboard chat's Ctrl+X); both call prompt.resolve_unknown. Beacon never did, so the
 * chat sat on "Reply status unknown" with no Stop and Delete said "Stop the reply before deleting this chat."
 *
 * The fixture plays Hermes' rules for that turn: resolve_unknown needs the generation stamped on the lost row and settles it interrupted,
 * then the queue's head starts; cancel retires a queued row.
 */
class NativeLostTurnTest {
    private fun row(id: String, status: String, generation: Long?) = json("admission_id" to id, "status" to status, "outcome" to JSONObject.NULL,
        "execution_generation" to (generation ?: JSONObject.NULL), "authority_epoch" to 125, "sequence" to 1, "input_id" to id, "text" to "synthetic $id",
        "ref" to json("session_id" to "stored-a", "profile_id" to "synthetic-profile"))

    /** A chat whose reply Hermes lost (generation 8, older than the session's current 7 shown at the top), optionally with a message queued behind it. */
    private fun lostChat(follower: Boolean = true, resolve: (NativeOperationsTest.Fixture, JSONObject) -> JSONObject? = ::hermesResolves) =
        NativeOperationsTest.Fixture(canonical = true).apply {
            running = false
            pending = JSONArray().put(row("lost-1", "unknown", 8))
            if (follower) pending.put(row("next-1", "queued", null))
            onRpc = { method, params ->
                when (method) {
                    "prompt.resolve_unknown" -> resolve(this, params)
                    "prompt.cancel" -> {
                        val rows = pending.objects()
                        val target = rows.firstOrNull { it.string("admission_id") == params.string("admission_id") }
                        if (target == null || target.string("status") != "queued") json("error" to json("code" to 4001, "message" to "stale_generation"))
                        else { pending = JSONArray(rows.filter { it !== target }); target.copy().put("status", "terminal").put("outcome", "cancelled") }
                    }
                    "prompt.receipt" -> pending.objects().firstOrNull { it.string("admission_id") == params.string("admission_id") }?.copy()
                    else -> null
                }
            }
        }

    private fun hermesResolves(f: NativeOperationsTest.Fixture, params: JSONObject): JSONObject {
        val rows = f.pending.objects()
        val lost = rows.firstOrNull { it.string("admission_id") == params.string("admission_id") }
        if (lost == null || lost.string("status") != "unknown" || params.optLong("execution_generation", -1) != lost.optLong("execution_generation"))
            return json("error" to json("code" to 4001, "message" to "stale_generation"))
        val rest = rows.filter { it !== lost }
        // The FIFO behind the lost turn resumes: its head starts at once.
        rest.firstOrNull { it.string("status") == "queued" }?.let { head -> head.put("status", "started").put("execution_generation", 9); f.running = true; f.generation = 9 }
        f.pending = JSONArray(rest)
        return lost.copy().put("status", "terminal").put("outcome", "interrupted")
    }

    private fun NativeOperationsTest.Fixture.sent(method: String) = calls.filter { it.string("method") == method }.map { it.getJSONObject("params") }

    // Follow-up 1 (Root's decision on REVIEW-WEDGE P2-1): a look never releases a message waiting behind the lost turn; the owner's Send does.
    @Test fun aLookLeavesTheLostTurnWhileAMessageWaitsBehindIt() = runBlocking {
        val f = lostChat()
        try {
            assertEquals("lost", f.client.recoveredNativeSession("stored-a")?.second)
            assertFalse(f.methods.contains("prompt.resolve_unknown"))
            assertFalse("The waiting message did not start", f.running)
        } finally { f.client.closeNative() }
    }

    @Test fun aWaitingMessageOnThePhoneAlsoKeepsALookFromClosing() = runBlocking {
        val f = lostChat(follower = false)
        try {
            assertEquals("lost", f.client.recoveredNativeSession("stored-a", LostTurnClose.Never)?.second)
            assertFalse(f.methods.contains("prompt.resolve_unknown"))
        } finally { f.client.closeNative() }
    }

    @Test fun theOwnersSendClosesTheLostTurnAndFollowsTheQueuedMessage() = runBlocking {
        val f = lostChat()
        try {
            val answer = f.client.recoveredNativeSession("stored-a", LostTurnClose.Decided)
            val resolved = f.sent("prompt.resolve_unknown").single()
            assertEquals("stored-a", resolved.string("session_id"))
            assertEquals("lost-1", resolved.string("admission_id"))
            assertEquals("The lost row's own generation, not the session's", 8L, resolved.getLong("execution_generation"))
            assertEquals("The queued message now runs and is followed", "working", answer?.second)
            assertFalse("Nothing is interrupted: nothing ran", f.methods.contains("session.interrupt"))
        } finally { f.client.closeNative() }
    }

    @Test fun withNothingQueuedTheChatIsIdleAgain() = runBlocking {
        val f = lostChat(follower = false)
        try {
            assertEquals("idle", f.client.recoveredNativeSession("stored-a")?.second)
            assertEquals(1, f.sent("prompt.resolve_unknown").size)
        } finally { f.client.closeNative() }
    }

    @Test fun aLostTurnAnotherClientClosedFirstIsReadAgain() = runBlocking {
        // Desktop's Discard won the race: Hermes answers stale_generation and the chat is simply read again.
        val f = lostChat(follower = false) { fixture, _ -> fixture.pending = JSONArray(); json("error" to json("code" to 4001, "message" to "stale_generation")) }
        try {
            assertEquals("idle", f.client.recoveredNativeSession("stored-a")?.second)
            assertEquals(2, f.sent("session.resume").count { it.optBoolean("omit_messages") })
        } finally { f.client.closeNative() }
    }

    @Test fun aRefusedCloseIsNotReportedAsAnIdleChat() = runBlocking {
        val f = lostChat { _, _ -> json("error" to json("code" to 4001, "message" to "permission_denied")) }
        try {
            assertTrue(runCatching { f.client.recoveredNativeSession("stored-a", LostTurnClose.Decided) }.isFailure)
            assertTrue("Still held: the queued message never ran", f.pending.objects().any { it.string("status") == "unknown" })
        } finally { f.client.closeNative() }
    }

    @Test fun stopClosesALostReply() = runBlocking {
        val f = lostChat(follower = false)
        try {
            val stopped = f.client.get("/api/chat/cancel", mapOf("stream_id" to "stored-a"))
            assertEquals("lost_turn", stopped.string("stopped"))
            assertEquals("lost-1", f.sent("prompt.resolve_unknown").single().string("admission_id"))
            assertFalse(f.methods.contains("session.interrupt"))
            assertTrue(f.pending.objects().isEmpty())
        } finally { f.client.closeNative() }
    }

    @Test fun stopOnARunningReplyStillInterruptsIt() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        try {
            f.client.get("/api/chat/cancel", mapOf("stream_id" to "stored-a"))
            assertEquals(7, f.sent("session.interrupt").single().getInt("execution_generation"))
            assertFalse(f.methods.contains("prompt.resolve_unknown"))
        } finally { f.client.closeNative() }
    }

    @Test fun deleteWithdrawsTheWaitingMessageThenClosesTheLostReplyThenDeletes() = runBlocking {
        val f = lostChat()
        try {
            assertEquals("stored-a", f.client.post("/api/session/delete", json("session_id" to "stored-a")).string("deleted"))
            val order = f.methods.filter { it in setOf("prompt.cancel", "prompt.resolve_unknown", "session.mutate") }
            assertEquals("The waiting message goes first, so it never starts when the lost turn closes", listOf("prompt.cancel", "prompt.resolve_unknown", "session.mutate"), order)
            assertEquals("next-1", f.sent("prompt.cancel").single().string("admission_id"))
            assertFalse("The waiting message never ran", f.running)
            assertEquals("delete", f.sent("session.mutate").single().string("operation"))
        } finally { f.client.closeNative() }
    }

    @Test fun deleteStillRefusesAChatThatIsRunning() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply { running = true }
        try {
            val failure = runCatching { f.client.post("/api/session/delete", json("session_id" to "stored-a")) }.exceptionOrNull()
            assertEquals("Stop the reply before deleting this chat.", appErrorText(failure!!))
            assertFalse(f.methods.any { it in setOf("session.mutate", "prompt.cancel", "prompt.resolve_unknown") })
        } finally { f.client.closeNative() }
    }

    @Test fun aDeleteHermesRefusesAfterWithdrawingGivesTheWaitingMessageBack() = runBlocking {
        val f = lostChat().apply {
            val hermes = onRpc!!
            onRpc = { method, params -> if (method == "session.mutate") json("error" to json("code" to 4001, "message" to "unknown_execution")) else hermes(method, params) }
        }
        try {
            val failure = runCatching { f.client.post("/api/session/delete", json("session_id" to "stored-a")) }.exceptionOrNull()
            assertEquals(listOf("next-1" to "synthetic next-1"), (failure as DeleteKeptWaiting).withdrawn)
        } finally { f.client.closeNative() }
    }

    @Test fun aReceiptBehindALostReplySaysSo() = runBlocking {
        val f = lostChat()
        try {
            val receipt = NativeOperations(f.client).call("/api/mobile/receipts/next-1", mapOf("session_id" to "stored-a", "admission_id" to "next-1"), "GET", null, 1024 * 1024)
            assertEquals("queued", receipt.string("status"))
            assertTrue(receipt.optBoolean("lost_turn"))
            assertFalse("A receipt is a read: it closes nothing", f.methods.contains("prompt.resolve_unknown"))
        } finally { f.client.closeNative() }
    }

    @Test fun anOrdinaryReceiptCarriesNoLostFlag() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply {
            pending = JSONArray().put(row("next-1", "queued", null))
            onRpc = { method, params -> if (method == "prompt.receipt") pending.objects().first { it.string("admission_id") == params.string("admission_id") }.copy() else null }
        }
        try {
            val receipt = NativeOperations(f.client).call("/api/mobile/receipts/next-1", mapOf("session_id" to "stored-a", "admission_id" to "next-1"), "GET", null, 1024 * 1024)
            assertFalse(receipt.has("lost_turn"))
        } finally { f.client.closeNative() }
    }

}
