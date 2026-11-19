package dev.hermes.android.data

import dev.hermes.android.SilentStream
import dev.hermes.android.core.*
import dev.hermes.android.silentStreamAction
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * REVIEW-WEDGE "Follow-up: wedge-121-2" (review-121b), for WEDGE-121 follow-up 2. The reviewer's proof
 * (aCancelThatFailsStillHandsBackAMessageHermesKeeps) inverted, and the other P3s. Synthetic ids only.
 */
class ReviewWedgeFollowupTest {
    private fun row(id: String, status: String, generation: Long?) = json("admission_id" to id, "status" to status, "outcome" to JSONObject.NULL,
        "execution_generation" to (generation ?: JSONObject.NULL), "authority_epoch" to 125, "sequence" to 1, "input_id" to id, "text" to "synthetic $id",
        "ref" to json("session_id" to "stored-a", "profile_id" to "synthetic-profile"))

    private fun lostChat(cancel: (NativeOperationsTest.Fixture, JSONObject) -> JSONObject) = NativeOperationsTest.Fixture(canonical = true).apply {
        running = false
        pending = JSONArray().put(row("lost-1", "unknown", 8)).put(row("next-1", "queued", null))
        onRpc = { method, params -> if (method == "prompt.cancel") cancel(this, params) else null }
    }

    /** WF-P3-2: Hermes refuses the withdrawal, so it still holds the message: Delete hands nothing back (no second copy to send). */
    @Test fun aCancelThatFailsHandsBackNothingHermesStillHolds() = runBlocking {
        val f = lostChat { _, _ -> json("error" to json("code" to 4001, "message" to "permission_denied")) }
        try {
            val failure = runCatching { f.client.post("/api/session/delete", json("session_id" to "stored-a")) }.exceptionOrNull()
            assertNotNull("Delete failed", failure)
            assertFalse("nothing handed back while Hermes holds it", failure is DeleteKeptWaiting)
            assertTrue("Hermes still holds the message", f.pending.objects().any { it.string("admission_id") == "next-1" && it.string("status") == "queued" })
            assertFalse(f.methods.contains("session.mutate"))
        } finally { f.client.closeNative() }
    }

    /** WF-P3-2: the withdrawal happened but its answer was lost: the message is gone from Hermes, so it is handed back. */
    @Test fun aWithdrawalWhoseAnswerWasLostIsHandedBack() = runBlocking {
        val f = lostChat { fixture, params ->
            fixture.pending = JSONArray(fixture.pending.objects().filter { it.string("admission_id") != params.string("admission_id") })
            json("error" to json("code" to 503, "message" to "Synthetic answer lost"))
        }
        try {
            val failure = runCatching { f.client.post("/api/session/delete", json("session_id" to "stored-a")) }.exceptionOrNull()
            assertEquals(listOf("next-1" to "synthetic next-1"), (failure as DeleteKeptWaiting).withdrawn)
        } finally { f.client.closeNative() }
    }

    /** WF-P3-3: the quiet-stream watcher acts on a lost reply instead of waiting forever. */
    @Test fun theQuietStreamWatcherDoesNotWaitOnALostReply() {
        assertNotEquals(SilentStream.Wait, silentStreamAction("lost"))
        assertEquals(SilentStream.Wait, silentStreamAction(null))
    }

    /** WF-P3-4: Hermes' refusal while a reply its restart cut holds the chat names that reply, not a task. */
    @Test fun aChangeRefusedWhileALostReplyHoldsTheChatSaysSo() {
        assertEquals("Hermes restarted during the last reply. Send or delete it first.", appErrorText(ApiFailure(4001, "unknown_execution")))
    }

    /** WF-P3-4: Delete refused by Hermes with nothing pending (a lost background task) still says it is a task. */
    @Test fun aDeleteRefusedForALostTaskStillSaysTask() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply {
            running = false
            onRpc = { method, _ -> if (method == "session.mutate") json("error" to json("code" to 4001, "message" to "unknown_execution")) else null }
        }
        try {
            val failure = runCatching { f.client.post("/api/session/delete", json("session_id" to "stored-a")) }.exceptionOrNull()
            assertEquals("A task cut off by a Hermes restart still holds this chat.", appErrorText(failure!!))
        } finally { f.client.closeNative() }
    }
}
