package dev.hermes.android

import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.ui.SendTick
import dev.hermes.android.ui.localSendTick
import dev.hermes.android.ui.savedRepliesOnce
import dev.hermes.android.ui.sendTickLabel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** A finished turn leaves one message and one reply, whichever proof of it survives. Synthetic ids only. */
class FinishedTurnTest {
    private val submission = "submission-1"
    private val admission = "admission-1"
    private val identity = TurnIdentity(submission, admission)
    private val user = 51765L
    private val reply = 51766L

    private fun item(rowId: String = "", state: String = "accepted") = QueuedMessage("Synthetic question", id = "local-1", state = state,
        submissionId = submission, admissionId = admission, rowId = rowId)
    private fun rows(vararg extra: JSONObject) = listOf(json("row_id" to user, "role" to "user", "content" to "Synthetic question"),
        json("row_id" to reply, "role" to "assistant", "content" to "Synthetic answer")) + extra
    private fun turn(complete: Boolean = true, sub: String = submission) = json("complete" to complete, "submission_id" to sub, "user_row_id" to user,
        "user_row_ids" to JSONArray(listOf(user)), "final_assistant_row_id" to reply, "row_ids" to JSONArray(listOf(user, reply)))
    private fun done(persisted: JSONObject? = turn(), admissionId: String = admission, extra: JSONObject = JSONObject()) =
        json("admission_id" to admissionId, "status" to "completed").apply {
            persisted?.let { put("persisted_turn", it) }
            extra.keys().forEach { put(it, extra.opt(it)) }
        }
    private suspend fun prove(completion: JSONObject?, knownRow: String = "", rows: List<JSONObject> = rows(), clean: Boolean = true,
        lookup: (suspend () -> JSONObject?)? = { throw IOException("Synthetic lookup failure") },
        retry: (suspend () -> JSONObject?)? = { throw IOException("Synthetic retry failure") },
        failed: (String, Throwable?) -> Unit = { _, _ -> }) =
        proveFinishedTurn(identity, "runtime-1", completion, clean, knownRow, rows, lookup, retry, failed)

    @Test fun aFailedReceiptLookupWithACompleteDoneProofLeavesOneMessageAndOneReplyWithoutAStatusLine() = runBlocking {
        val steps = mutableListOf<String>()
        val proven = prove(done(), failed = { step, _ -> steps += step })
        assertEquals("done_event", proven.step)
        // The done event is used at once: the receipt is not even asked for.
        assertTrue(steps.isEmpty())
        val receipt = requireNotNull(proven.receipt)
        val linked = requireNotNull(boundQueuedInput(item(), receipt, rows(), listOf(item())))
        assertEquals(user.toString(), linked.rowId)
        assertEquals("settled", linked.state)
        assertTrue(replyShownInHistory(receipt, rows()))
        val shown = chatTimelineMessages(rows().mapIndexed { i, row -> Message.from(row, i, "chat") }, listOf(linked))
        assertEquals("one copy of the message", 1, shown.count { it.role == "user" })
        assertEquals("one copy of the reply", 1, shown.count { it.role == "assistant" })
        val running = ChatState(sessionId = "chat", streamId = "runtime-1", liveText = "Synthetic answer", status = "Working")
        val ended = releaseTerminalChat(running, "runtime-1", replyConfirmed = true, terminalStatus = "")
        assertEquals("", ended.status)
        assertTrue("a reply the history holds is not kept as a second copy", ended.raw.rows("_unconfirmed_replies").isEmpty())
        assertEquals("", ended.liveText)
    }

    @Test fun withoutALinkedLookupTheMessageStaysTwiceOnTheOldPath() {
        // The pre-fix state: no proof, so the local copy keeps no row and shows beside the real row.
        val shown = chatTimelineMessages(rows().mapIndexed { i, row -> Message.from(row, i, "chat") }, listOf(item()))
        assertEquals(2, shown.count { it.role == "user" })
    }

    @Test fun whenNoProofCanBeBuiltButHistoryHoldsTheTurnTheRowsReconcileIt() = runBlocking {
        val steps = mutableListOf<String>()
        val proven = prove(done(persisted = null), knownRow = user.toString(), failed = { step, _ -> steps += step })
        assertEquals("saved_rows", proven.step)
        assertEquals(listOf("receipt", "retry"), steps)
        val receipt = requireNotNull(proven.receipt)
        assertEquals(reply, receipt.getJSONObject("_confirmed_persisted_turn").getLong("final_assistant_row_id"))
        assertNotNull(boundQueuedInput(item(rowId = user.toString()), receipt, rows(), listOf(item(rowId = user.toString()))))
        assertTrue(replyShownInHistory(receipt, rows()))
    }

    @Test fun savedRowsNeedBothEndsOfTheTurnAndAKnownMessageRow() = runBlocking {
        assertNull("no assistant row follows the message", prove(done(persisted = null), user.toString(), rows = rows().take(1)).receipt)
        assertNull("the message row is not in history", prove(done(persisted = null), "999", rows = rows()).receipt)
        assertNull("without the message's row identity no row is claimed by its words", prove(done(persisted = null), "", rows = rows()).receipt)
        assertNull("a failed or stopped turn is not settled from its rows", prove(done(persisted = null), user.toString(), clean = false).receipt)
        val nextTurn = rows(json("row_id" to 51767L, "role" to "user", "content" to "Next"), json("row_id" to 51768L, "role" to "assistant", "content" to "Next answer"))
        val receipt = requireNotNull(prove(done(persisted = null), user.toString(), rows = nextTurn).receipt)
        assertEquals("the next turn's reply is not this turn's", reply, receipt.getJSONObject("_confirmed_persisted_turn").getLong("final_assistant_row_id"))
    }

    @Test fun theLookupIsAskedAgainOnceWithAFreshLeaseBeforeFallingBackToRows() = runBlocking {
        var first = 0; var second = 0
        val settled = json("status" to "terminal", "outcome" to "completed", "admission_id" to admission, "stream_id" to "runtime-1",
            "_confirmed_persisted_turn" to turn(), "_confirmed_persisted_input" to turn())
        val proven = prove(done(persisted = null), lookup = { first++; throw IOException("Synthetic") }, retry = { second++; settled })
        assertEquals("retry", proven.step)
        assertEquals(1, first)
        assertEquals(1, second)
        assertSame(settled, proven.receipt)
        val steps = mutableListOf<String>()
        prove(done(persisted = null), lookup = { throw IOException("Synthetic") }, retry = { second++; throw IOException("Synthetic") }, failed = { step, _ -> steps += step })
        assertEquals("the retry is made once, never again", 2, second)
        assertEquals(listOf("receipt", "retry"), steps)
    }

    @Test fun aReceiptThatProvesTheTurnNeedsNoFallbackAndNoIncident() = runBlocking {
        val settled = json("status" to "terminal", "outcome" to "completed", "admission_id" to admission, "stream_id" to "runtime-1",
            "_confirmed_persisted_turn" to turn(), "_confirmed_persisted_input" to turn())
        val steps = mutableListOf<String>()
        var retried = false
        val proven = prove(null, lookup = { settled }, retry = { retried = true; null }, failed = { step, _ -> steps += step })
        assertEquals("receipt", proven.step)
        assertFalse(retried)
        assertTrue(steps.isEmpty())
    }

    @Test fun aDoneEventIsProofOnlyForTheSameCompleteSubmission() = runBlocking {
        suspend fun step(completion: JSONObject?) = prove(completion, knownRow = "").step
        assertEquals("none", step(done(persisted = turn(complete = false))))
        assertEquals("none", step(done(persisted = turn(sub = "someone-else"))))
        assertEquals("none", step(done(admissionId = "another-admission")))
        assertEquals("none", step(done(extra = json("status" to "interrupted"))))
        assertEquals("none", step(done(extra = json("error" to "boom"))))
        assertEquals("none", step(done(persisted = turn().apply { remove("row_ids") })))
        assertEquals("none", step(null))
        assertEquals("done_event", step(done()))
    }

    @Test fun aSendWithoutAQueuedCopyUsesTheDoneEventAndNeverAsksForAReceipt() = runBlocking {
        val proven = proveFinishedTurn(identity, "runtime-1", done(), true, "", rows(), lookup = null, retry = null, failed = { _, _ -> fail("no lookup was made") })
        assertEquals("done_event", proven.step)
    }

    @Test fun chatShowsNoStateWordsOnlyTicks() {
        // The words under a bubble are gone; every state a message can be in maps to a tick, and no state is left without one.
        val states = listOf("queued", "sending", "accepted", "unknown", "sent", "settled")
        val outcomes = listOf("", "completed", "failed", "interrupted", "cancelled", "rejected")
        for (state in states) for (outcome in outcomes) for (kind in listOf("queue", "steer")) {
            val tick = localSendTick(QueuedMessage("Hello", state = state, outcome = outcome, kind = kind), answered = false)
            assertTrue("$state/$outcome: $tick", tick in SendTick.entries && sendTickLabel(tick).isNotBlank())
        }
        assertEquals(SendTick.Delivered, localSendTick(QueuedMessage("Hello", state = "accepted"), answered = false))
        // Hermes settles a turn only after it started it, so a settled message is two blue ticks whether or not a reply row follows.
        assertEquals(SendTick.Answered, localSendTick(QueuedMessage("Hello", state = "settled", outcome = "completed"), answered = false))
        assertEquals(SendTick.Pending, localSendTick(QueuedMessage("Hello", state = "sending"), answered = false))
    }

    @Test fun aStreamedReplyIsShownOnceWhenHistoryHoldsTheSameWords() {
        val saved = listOf(Message("1", "user", "Synthetic question"), Message("2", "assistant", "Synthetic   answer\n"))
        val retained = listOf(Message("unconfirmed:a", "assistant", "Synthetic answer", displayKind = "unconfirmed_reply"),
            Message("unconfirmed:b", "assistant", "Another reply history never saved", displayKind = "unconfirmed_reply"))
        assertEquals(listOf("unconfirmed:b"), savedRepliesOnce(retained, saved).map { it.id })
        assertEquals("nothing saved, nothing hidden", retained.map { it.id }, savedRepliesOnce(retained, emptyList()).map { it.id })
    }

    /** Queued sends drain after a proven reply: the done event's proof puts the reply in the history, and that releases the queue. A turn nothing can prove holds it. */
    @Test fun aQueuedFollowUpIsSentOnlyOnceTheFinishedTurnIsProven() = runBlocking {
        val proven = prove(done())
        assertEquals("done_event", proven.step)
        assertTrue("a proven turn whose reply is in the saved history releases the queue", drainsQueueAfterTerminal(replyShownInHistory(proven.receipt, rows()), false, ""))
        assertFalse("the same proof without the reply in the history does not", drainsQueueAfterTerminal(replyShownInHistory(proven.receipt, rows().take(1)), false, ""))
        // A done event that names a submission but no admission is not a shape any Hermes sends, so nothing can prove the turn and the queue is held until the chat is opened again.
        val unnamed = proveFinishedTurn(TurnIdentity("", ""), "runtime-1", done(admissionId = ""), true, "", rows(), null, null, failed = { _, _ -> })
        assertEquals("none", unnamed.step)
        assertFalse(drainsQueueAfterTerminal(replyShownInHistory(unnamed.receipt, rows()), false, ""))
        // A stream that began after the turn had already ended releases the queue unless it ended in an error.
        assertTrue(drainsQueueAfterTerminal(false, true, ""))
        assertFalse(drainsQueueAfterTerminal(false, true, "Interrupted"))
        assertFalse(drainsQueueAfterTerminal(false, false, ""))
    }

    // A Hermes without session authority names no submission and no admission; its done event still lists the rows it committed for the turn (agent.persisted_turn_receipt).
    private val noName = TurnIdentity("", "")
    private fun legacyTurn(complete: Boolean = true, rowIds: List<Long> = listOf(user, reply)) = json("complete" to complete, "user_row_id" to user,
        "user_row_ids" to JSONArray(listOf(user)), "final_assistant_row_id" to reply, "row_ids" to JSONArray(rowIds))
    private fun legacyDone(persisted: JSONObject? = legacyTurn(), status: String = "complete") =
        json("status" to status).apply { persisted?.let { put("persisted_turn", it) } }
    private suspend fun proveLegacy(completion: JSONObject?, clean: Boolean = true, rows: List<JSONObject> = rows()) =
        proveFinishedTurn(noName, "runtime-1", completion, clean, "", rows, null, null, failed = { _, _ -> fail("a legacy turn has no receipt to ask for") })

    @Test fun aTurnOnAGatewayWithoutAdmissionsIsProvenByItsDoneEventAndTheSavedRows() = runBlocking {
        val proven = proveLegacy(legacyDone())
        assertEquals("done_event", proven.step)
        assertTrue("the queue is released once the history holds the turn", drainsQueueAfterTerminal(replyShownInHistory(proven.receipt, rows()), false, ""))
        assertEquals("a steer-free turn lists its one user row", listOf(user), proven.receipt!!.getJSONObject("_confirmed_persisted_turn").getJSONArray("user_row_ids").let { list -> (0 until list.length()).map { list.getLong(it) } })
    }

    @Test fun aTurnOnAGatewayWithoutAdmissionsKeepsTheQueueHeldWhenNothingProvesIt() = runBlocking {
        fun held(why: String, proven: FinishedTurn, rows: List<JSONObject> = rows()) =
            assertFalse(why, drainsQueueAfterTerminal(replyShownInHistory(proven.receipt, rows), false, ""))
        held("no done event", proveLegacy(null))
        held("a done event that lists no rows", proveLegacy(legacyDone(null)))
        held("a turn Hermes did not call complete", proveLegacy(legacyDone(legacyTurn(complete = false))))
        held("rows that do not hold both ends of the turn", proveLegacy(legacyDone(legacyTurn(rowIds = listOf(user)))))
        held("an interrupted turn", proveLegacy(legacyDone(status = "interrupted")))
        held("a turn that ended with an error", proveLegacy(legacyDone(), clean = false))
        held("a done event with an error in it", proveLegacy(legacyDone().put("error", "boom")))
        held("a history that does not hold the reply yet", proveLegacy(legacyDone(), rows = rows().take(1)), rows().take(1))
        held("a history that does not hold the user row", proveLegacy(legacyDone(), rows = rows().drop(1)), rows().drop(1))
        held("a done event that names an admission this phone never followed", proveFinishedTurn(noName, "runtime-1", done(persisted = legacyTurn()), true, "", rows(), null, null, failed = { _, _ -> }))
        held("a done event that names a submission but no admission", proveLegacy(legacyDone(legacyTurn().put("submission_id", submission))))
    }
}
