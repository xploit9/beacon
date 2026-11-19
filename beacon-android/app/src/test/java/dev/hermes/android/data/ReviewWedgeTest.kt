package dev.hermes.android.data

import dev.hermes.android.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * REVIEW-WEDGE's proofs (review-121b, on wedge-121.patch e00a385e), inverted for WEDGE-121 follow-up 1. The fixture plays Hermes' lost-turn
 * rules as the reviewer's did. Synthetic ids only.
 */
class ReviewWedgeTest {
    private fun row(id: String, status: String, generation: Long?) = json("admission_id" to id, "status" to status, "outcome" to JSONObject.NULL,
        "execution_generation" to (generation ?: JSONObject.NULL), "authority_epoch" to 125, "sequence" to 1, "input_id" to id, "text" to "synthetic $id",
        "ref" to json("session_id" to "stored-a", "profile_id" to "synthetic-profile"))

    private fun lostChat(resolve: (NativeOperationsTest.Fixture, JSONObject) -> JSONObject) = NativeOperationsTest.Fixture(canonical = true).apply {
        running = false
        pending = JSONArray().put(row("lost-1", "unknown", 8)).put(row("next-1", "queued", null))
        onRpc = { method, params ->
            when (method) {
                "prompt.resolve_unknown" -> resolve(this, params)
                "prompt.cancel" -> {
                    val rows = pending.objects()
                    val target = rows.firstOrNull { it.string("admission_id") == params.string("admission_id") && it.string("status") == "queued" }
                    if (target == null) json("error" to json("code" to 4001, "message" to "stale_generation"))
                    else { pending = JSONArray(rows.filter { it !== target }); target.copy().put("status", "terminal").put("outcome", "cancelled") }
                }
                else -> null
            }
        }
    }

    private fun hermesResolves(f: NativeOperationsTest.Fixture, params: JSONObject): JSONObject {
        val rows = f.pending.objects()
        val lost = rows.first { it.string("admission_id") == params.string("admission_id") }
        val rest = rows.filter { it !== lost }
        rest.firstOrNull { it.string("status") == "queued" }?.let { head -> head.put("status", "started").put("execution_generation", 9); f.running = true; f.generation = 9 }
        f.pending = JSONArray(rest)
        return lost.copy().put("status", "terminal").put("outcome", "interrupted")
    }

    /** P2-1 (Root's decision): a look never releases the message queued behind a lost turn; Hermes keeps it for the owner, and the chat says "lost". */
    @Test fun aPassiveStatusReadKeepsTheMessageQueuedBehindALostTurnWaiting() = runBlocking {
        val f = lostChat(::hermesResolves)
        try {
            val answer = f.client.recoveredNativeSession("stored-a")
            assertFalse("a look closes nothing while a message waits", f.methods.contains("prompt.resolve_unknown"))
            assertFalse("the follower did not start", f.running)
            assertEquals("lost", answer?.second)
            assertTrue(f.pending.objects().any { it.string("admission_id") == "next-1" && it.string("status") == "queued" })
        } finally { f.client.closeNative() }
    }

    /** P3-4: a Delete that fails after withdrawing hands the waiting message back (its words, for the phone's queue) and says so in one line. */
    @Test fun aDeleteThatFailsAfterWithdrawingGivesTheWaitingMessageBack() = runBlocking {
        val f = lostChat { _, _ -> json("error" to json("code" to 4001, "message" to "permission_denied")) }
        try {
            val failure = runCatching { f.client.post("/api/session/delete", json("session_id" to "stored-a")) }.exceptionOrNull()
            assertNotNull("Delete failed", failure)
            assertEquals("the failure carries the withdrawn message back", "DeleteKeptWaiting", failure!!.javaClass.simpleName)
            assertEquals("Delete didn't finish. Your waiting message is back in the queue.", appErrorText(failure))
            assertFalse("the chat was not deleted", f.methods.contains("session.mutate"))
        } finally { f.client.closeNative() }
    }

    /** P3-3: Stop on a lost reply with a message waiting (in Hermes' queue, or on the phone) starts nothing; the chat offers the choice. */
    @Test fun stopWithAMessageWaitingOffersTheChoiceAndStartsNothing() = runBlocking {
        for (onPhone in listOf(false, true)) {
            val f = lostChat(::hermesResolves).apply { if (onPhone) pending = JSONArray().put(row("lost-1", "unknown", 8)) }
            try {
                val stopped = f.client.get("/api/chat/cancel", mapOf("stream_id" to "stored-a") + if (onPhone) mapOf("waiting" to "1") else emptyMap())
                assertEquals("lost_waiting", stopped.string("stopped"))
                assertFalse(f.methods.any { it in setOf("prompt.resolve_unknown", "session.interrupt") })
                assertFalse(f.running)
            } finally { f.client.closeNative() }
        }
    }

    /** P3-1: nothing runs and a message waits with no lost reply: a lost task (a worker) holds the chat until Hermes' own reset. Delete says so and withdraws nothing. */
    @Test fun deleteWhenALostTaskHoldsTheChatSaysSoAndWithdrawsNothing() = runBlocking {
        val f = lostChat(::hermesResolves).apply { pending = JSONArray().put(row("next-1", "queued", null)) }
        try {
            val failure = runCatching { f.client.post("/api/session/delete", json("session_id" to "stored-a")) }.exceptionOrNull()
            assertEquals("A task cut off by a Hermes restart still holds this chat.", appErrorText(failure!!))
            assertFalse(f.methods.any { it in setOf("session.mutate", "prompt.cancel", "prompt.resolve_unknown") })
        } finally { f.client.closeNative() }
    }

    /** P3-1, as follow-up 2 refined it (WF-P3-4): Hermes' refusal names the lost reply and what lets it go, never "try again in a moment"; Delete's
     *  own lost-task case keeps the task line ([deleteWhenALostTaskHoldsTheChatSaysSoAndWithdrawsNothing]). */
    @Test fun hermesRefusingALostChatSaysWhyTruthfully() {
        assertEquals("Hermes restarted during the last reply. Send or delete it first.", appErrorText(ApiFailure(4001, "unknown_execution")))
    }
}
