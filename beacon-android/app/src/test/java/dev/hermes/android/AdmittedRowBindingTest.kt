package dev.hermes.android

import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.copy
import dev.hermes.android.core.json
import dev.hermes.android.ui.SendTick
import dev.hermes.android.ui.historySendTick
import dev.hermes.android.ui.localSendTick
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHAT-121 follow-up 5: the phone's copy of a message Hermes admitted is tied to the user row Hermes saved for it, by the submission id Hermes stamps on
 * that row (`display_metadata.submission_id`), as soon as the row is saved, and the chat shows Hermes' row in its place. The copy keeps its state, so the
 * turn's own receipt still settles and retires it, and the rules of the other lanes read it as before. Synthetic text only.
 */
class AdmittedRowBindingTest {
    private fun copy(text: String, submission: String, state: String = "accepted", rowId: String = "") = QueuedMessage(text, id = "copy-$submission", state = state,
        admissionId = "admission-$submission", submissionId = submission, afterRowId = "2", admissionStatus = "started", rowId = rowId)
    private fun row(id: String, text: String, role: String = "user", stamp: String? = null) = json("row_id" to id, "role" to role, "content" to text).apply {
        if (stamp != null) put("display_metadata", json("submission_id" to stamp))
    }
    private val earlier = listOf(row("1", "Earlier question"), row("2", "Earlier answer", "assistant"))
    private fun messages(rows: List<JSONObject>) = rows.mapIndexed { i, r -> Message.from(r, i, "chat-a") }

    /** FU4's live case: a document's row carries Hermes' attachment note below the words, so no words pairing could find it; Hermes' stamp does. */
    @Test fun theRowHermesStampedIsTheCopysRowWhateverItsWordsSay() {
        val sent = copy("Synthetic document check", "phone-send-1").copy(attachments = listOf(json("name" to "Synthetic report.pdf", "mime" to "application/pdf")))
        val saved = row("3", "Synthetic document check\n\n--- Attached Context ---\n\nSynthetic note about a document", stamp = "phone-send-1")
        val rows = earlier + saved
        assertEquals("the words pairing finds nothing", emptyMap<String, String>(), acceptedRowPairs(listOf(sent), rows))
        assertEquals(mapOf(sent.id to "3"), submittedRowPairs(listOf(sent), rows))
        val named = namedCopy(sent, "3")
        assertEquals("accepted", named.state); assertEquals("3", named.rowId); assertEquals("no words marker: Hermes named the row", "", named.outcome)
        // The chat: Hermes' row once, and no bubble of the phone's copy.
        assertEquals(listOf("1", "2", "3"), chatTimelineMessages(messages(rows), listOf(named)).map { it.id })
        // Before the row is saved the copy shows, once.
        assertEquals(listOf("1", "2", "queued:${sent.id}"), chatTimelineMessages(messages(earlier), listOf(sent)).map { it.id })
    }

    @Test fun theStampAsTextIsReadTheSameWay() {
        val sent = copy("Book the 11:45 instead", "phone-send-1")
        val saved = row("3", "Book the 11:45 instead").put("display_metadata", json("submission_id" to "phone-send-1", "gateway_input_owner" to "synthetic").toString())
        assertEquals("phone-send-1", rowSubmissionId(saved))
        assertEquals(mapOf(sent.id to "3"), submittedRowPairs(listOf(sent), earlier + saved))
        assertEquals("", rowSubmissionId(row("4", "No stamp")))
        assertEquals("", rowSubmissionId(row("4", "Unreadable").put("display_metadata", "{not json")))
    }

    @Test fun nothingIsGuessed() {
        val sent = copy("Book the 11:45 instead", "phone-send-1")
        // No stamped row, a stamp for another send, two rows with the stamp, a row id that is not Hermes' row number, a row another copy holds.
        assertEquals(emptyMap<String, String>(), submittedRowPairs(listOf(sent), earlier + row("3", "Book the 11:45 instead")))
        assertEquals(emptyMap<String, String>(), submittedRowPairs(listOf(sent), earlier + row("3", "Book the 11:45 instead", stamp = "desktop-send-7")))
        assertEquals(emptyMap<String, String>(), submittedRowPairs(listOf(sent), earlier + row("3", "A", stamp = "phone-send-1") + row("4", "B", stamp = "phone-send-1")))
        assertEquals(emptyMap<String, String>(), submittedRowPairs(listOf(sent), earlier + row("local-3", "A", stamp = "phone-send-1")))
        val holder = copy("Other", "phone-send-0", state = "settled", rowId = "3").copy(outcome = "completed")
        assertEquals(emptyMap<String, String>(), submittedRowPairs(listOf(holder, sent), earlier + row("3", "A", stamp = "phone-send-1")))
        // Only a plain queued send Hermes holds: a steer, a waiting message, one of unknown outcome and one that already has its row are left alone.
        val stamped = earlier + row("3", "Book the 11:45 instead", stamp = "phone-send-1")
        for (other in listOf(sent.copy(kind = "steer"), sent.copy(state = "queued"), sent.copy(state = "unknown"), sent.copy(state = "sending"), sent.copy(rowId = "9"))) {
            assertEquals("${other.kind}/${other.state}/${other.rowId}", emptyMap<String, String>(), submittedRowPairs(listOf(other), stamped))
            assertSame(other, namedCopy(other, "3"))
        }
        // Two copies with one submission id (never made by the phone): neither.
        assertEquals(emptyMap<String, String>(), submittedRowPairs(listOf(sent, sent.copy(id = "copy-twin")), stamped))
    }

    @Test fun aSettledCopyGetsItsRowAndStaysSettled() {
        // A turn that failed or was stopped after Hermes saved the row: Hermes holds the message, so it is never "not sent", even one Hermes cancelled after saving it.
        for (outcome in listOf("failed", "interrupted", "cancelled", "completed")) {
            val settled = copy("Book the 11:45 instead", "phone-send-1", state = "settled").copy(outcome = outcome)
            assertEquals(outcome, mapOf(settled.id to "3"), submittedRowPairs(listOf(settled), earlier + row("3", "Book the 11:45 instead", stamp = "phone-send-1")))
            val named = namedCopy(settled, "3")
            assertEquals(outcome, "settled", named.state); assertEquals(outcome, outcome, named.outcome)
            assertFalse(outcome, named.notSent()); assertFalse(outcome, named.inBox())
        }
    }

    /** The turn's own receipt logic is unchanged: it proves the same row, settles the copy and retires it once both ends of the turn are shown. */
    @Test fun theNamedCopyIsStillSettledAndRetiredByItsTurnsReceipt() {
        val named = namedCopy(copy("Book the 11:45 instead", "phone-send-1"), "3")
        val proof = json("complete" to true, "submission_id" to "phone-send-1", "user_row_id" to 3, "user_row_ids" to JSONArray(listOf(3)),
            "final_assistant_row_id" to 4, "row_ids" to JSONArray(listOf(3, 4)))
        val receipt = json("status" to "terminal", "outcome" to "completed", "admission_id" to named.admissionId, "_confirmed_persisted_input" to proof, "_confirmed_persisted_turn" to proof)
        val rows = earlier + row("3", "Book the 11:45 instead", stamp = "phone-send-1") + row("4", "Booked.", "assistant")
        assertEquals("3", linkedQueuedInputRow(named, receipt, rows, listOf(named)))
        val settled = boundQueuedInput(named, receipt, rows, listOf(named))!!
        assertEquals("settled", settled.state); assertEquals("completed", settled.outcome); assertEquals("3", settled.rowId)
        assertEquals("3", completedQueuedInputRow(settled, receipt, rows, rows, listOf(settled)))
        // The row Hermes named is where a finished turn's saved-rows proof starts (a word pairing never is).
        assertEquals("3", knownTurnRow(named))
        // A receipt naming another row never moves a named copy.
        val other = json("status" to "terminal", "outcome" to "completed", "admission_id" to named.admissionId,
            "_confirmed_persisted_turn" to proof.copy().put("user_row_id", 5).put("user_row_ids", JSONArray(listOf(5))).put("row_ids", JSONArray(listOf(5, 4))))
        assertNull(linkedQueuedInputRow(named, other, rows + row("5", "Other", stamp = "x"), listOf(named)))
    }

    /** TURN's rules read the named copy as before: the saved row's ticks are the copy's, it is not in the box, and it is still the head that waits for its turn. */
    @Test fun ticksBoxAndDrainReadTheNamedCopyAsBefore() {
        val named = namedCopy(copy("Book the 11:45 instead", "phone-send-1"), "3")
        for (answered in listOf(false, true)) assertEquals(historySendTick(answered), localSendTick(named, answered))
        assertEquals(SendTick.Delivered, localSendTick(named, answered = false))
        assertTrue(queueBoxItems(listOf(named)).isEmpty())
        assertEquals("accepted", named.state)
        assertFalse(receiptSweepCandidate(named))
    }
}
