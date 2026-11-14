package dev.hermes.android

import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.json
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A message typed while a reply runs (a steer) is saved by Hermes as its own user row with no submission identity. Synthetic ids only. */
class SteeredTurnTest {
    private val identity = TurnIdentity("submission-1", "admission-1")
    private val prompt = 13430L
    private val steer = 13441L
    private val reply = 13442L

    private fun rows() = listOf(
        json("row_id" to prompt, "role" to "user", "content" to "Use your search tool"),
        json("row_id" to 13435L, "role" to "assistant", "content" to "", "tool_calls" to JSONArray().put(json("id" to "call-1"))),
        json("row_id" to 13436L, "role" to "tool", "content" to "result", "tool_call_id" to "call-1"),
        json("row_id" to steer, "role" to "user", "display_kind" to "steer", "content" to "[OUT-OF-BAND USER MESSAGE note] Only reply with the word REDIRECTED [/OUT-OF-BAND USER MESSAGE]"),
        json("row_id" to reply, "role" to "assistant", "content" to "REDIRECTED"),
    )
    private fun steeredTurn() = json("complete" to false, "submission_id" to identity.submission, "user_row_id" to prompt,
        "user_row_ids" to JSONArray(listOf(prompt, steer)), "final_assistant_row_id" to reply, "row_ids" to JSONArray(listOf(prompt, 13435L, 13436L, steer, reply)))
    private fun done(turn: JSONObject = steeredTurn()) = json("admission_id" to identity.admission, "status" to "complete", "persisted_turn" to turn)
    private fun steerCopy(text: String = "Only reply with the word REDIRECTED", at: Long = 1, state: String = "accepted") =
        QueuedMessage(text, id = "steer-$at", createdAt = at, state = state, kind = "steer")

    @Test fun aSteeredTurnsDoneEventStillProvesTheOneReply() {
        val receipt = requireNotNull(doneEventReceipt(done(), identity, "runtime-1"))
        assertTrue(replyShownInHistory(receipt, rows()))
        assertEquals("the proof lists the steer row after the prompt", listOf(prompt, steer),
            receipt.getJSONObject("_confirmed_persisted_turn").getJSONArray("user_row_ids").let { list -> (0 until list.length()).map { list.getLong(it) } })
        // The streamed copy of that reply is then dropped, never kept beside the saved one.
        val ended = releaseTerminalChat(ChatState(sessionId = "chat", streamId = "runtime-1", liveText = "REDIRECTED"), "runtime-1", replyConfirmed = replyShownInHistory(receipt, rows()), terminalStatus = "")
        assertTrue(ended.raw.optJSONArray("_unconfirmed_replies") == null || ended.raw.getJSONArray("_unconfirmed_replies").length() == 0)
    }

    @Test fun onlyAFullyListedCommittedSteeredTurnIsProof() {
        fun proof(change: (JSONObject) -> Unit) = doneEventReceipt(done(steeredTurn().also(change)), identity, "runtime-1")
        assertNull("a single user row that is not complete is a partial turn", proof { it.put("user_row_ids", JSONArray(listOf(prompt))) })
        assertNull("the list must start with the prompt", proof { it.put("user_row_ids", JSONArray(listOf(steer, prompt))) })
        assertNull("the final reply must be one of the turn's rows", proof { it.put("final_assistant_row_id", 99999L) })
        assertNull("the final reply is not a user row", proof { it.put("final_assistant_row_id", steer) })
        assertNull("no final reply, no proof", proof { it.remove("final_assistant_row_id") })
        assertNull("a user row outside the turn's rows", proof { it.put("user_row_ids", JSONArray(listOf(prompt, 777L))) })
        assertNull("another submission", proof { it.put("submission_id", "someone-else") })
        assertNull("repeated rows", proof { it.put("user_row_ids", JSONArray(listOf(prompt, steer, steer))) })
    }

    @Test fun theSteerBubblePairsWithTheSavedRowTheTurnReceiptListsAfterThePrompt() {
        val pairs = steerRowPairs(listOf(steerCopy()), listOf(prompt, steer), rows())
        assertEquals(mapOf("steer-1" to steer.toString()), pairs)
        // The paired copy is hidden behind the saved row: one bubble for the steer.
        val paired = steerCopy().copy(rowId = steer.toString(), state = "sent")
        val shown = chatTimelineMessages(rows().mapIndexed { i, row -> Message.from(row, i, "chat") }, listOf(paired))
        assertEquals("the prompt and the steer, once each", 2, shown.count { it.role == "user" })
    }

    @Test fun twoSteersPairInTheOrderTheyWereSent() {
        val second = 13450L
        val saved = rows() + json("row_id" to second, "role" to "user", "display_kind" to "steer", "content" to "Another change")
        val pairs = steerRowPairs(listOf(steerCopy("Another change", at = 2), steerCopy(at = 1)), listOf(prompt, steer, second), saved)
        assertEquals(mapOf("steer-1" to steer.toString(), "steer-2" to second.toString()), pairs)
    }

    @Test fun withoutAReceiptASteerRowIsToldByItsKindAndWordsAndPairsOnce() {
        assertEquals(mapOf("steer-1" to steer.toString()), steerRowPairs(listOf(steerCopy()), emptyList(), rows()))
        assertTrue("other words are another message", steerRowPairs(listOf(steerCopy("Something else")), emptyList(), rows()).isEmpty())
        assertTrue("a plain user row is never a steer", steerRowPairs(listOf(steerCopy("Use your search tool")), emptyList(), rows()).isEmpty())
        assertEquals("one row for one copy", 1, steerRowPairs(listOf(steerCopy(at = 1), steerCopy(at = 2)), emptyList(), rows()).size)
        val settled = steerCopy().copy(rowId = steer.toString(), state = "sent")
        assertTrue("a row that is already claimed stays claimed", steerRowPairs(listOf(settled, steerCopy(at = 2)), emptyList(), rows()).isEmpty())
        assertTrue("a plain queued message is not a steer", steerRowPairs(listOf(steerCopy().copy(kind = "queue")), listOf(prompt, steer), rows()).isEmpty())
    }

    @Test fun theNoteHermesWrapsAroundASteerIsNotShownOrCompared() {
        assertEquals("Only reply with the word REDIRECTED", steerRowText(rows()[3]))
        assertEquals("plain", steerRowText(json("role" to "user", "content" to "plain")))
        assertEquals("shown text", steerRowText(json("role" to "user", "display_text" to "shown text", "content" to "[OUT-OF-BAND USER MESSAGE x] hidden [/OUT-OF-BAND USER MESSAGE]")))
    }

    @Test fun aTurnSettledWithoutTheDoneEventStillFindsItsCopyByTheDisplayedIds() {
        val queued = listOf(QueuedMessage("Hello", id = "local-1", state = "accepted", submissionId = "submission-1", admissionId = "admission-1"))
        val (item, identity) = finishedTurnIdentity(queued, completion = null, displaySubmission = "submission-1", displayAdmission = "admission-1")
        assertEquals("local-1", item?.id)
        assertEquals(TurnIdentity("submission-1", "admission-1"), identity)
        val (none, plain) = finishedTurnIdentity(emptyList(), null, "submission-1", "admission-1")
        assertNull(none)
        assertEquals("a send with no copy is the displayed turn", TurnIdentity("submission-1", "admission-1"), plain)
        assertEquals(TurnIdentity("", ""), finishedTurnIdentity(emptyList(), null, "", "").second)
        assertEquals("another admission is not this turn", TurnIdentity("", ""), finishedTurnIdentity(emptyList(), done(json("submission_id" to "submission-1")).put("admission_id", "other"), "submission-1", "admission-1").second)
        assertNull("a blank admission never matches a copy with a blank admission", finishedTurnIdentity(listOf(QueuedMessage("x", id = "s", submissionId = "submission-1")), null, "submission-1", "").first)
    }

    @Test fun aQuietRunningReplyIsAskedAboutQuicklyEnoughToNotLinger() {
        assertTrue("first check after the reply goes quiet", STREAM_QUIET_START_MS <= 20_000L)
        assertTrue("how often it is looked at", STREAM_QUIET_CHECK_MS <= 5_000L)
    }

    @Test fun aSteeredTurnProvesByDoneEventWithoutAskingForAReceiptWhenThereIsNoQueuedCopy() = runBlocking {
        val proven = proveFinishedTurn(identity, "runtime-1", done(), true, "", rows(), lookup = null, retry = null, failed = { _, _ -> fail("no lookup is made") })
        assertEquals("done_event", proven.step)
    }
}
