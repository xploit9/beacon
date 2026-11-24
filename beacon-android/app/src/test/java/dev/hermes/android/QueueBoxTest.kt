package dev.hermes.android

import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only what was sent to the agent is in the chat; what the phone still holds, and what failed, is in the box above the composer. Synthetic text only. */
class QueueBoxTest {
    private val history = listOf(Message("1", "user", "Earlier question"), Message("2", "assistant", "Earlier answer"))
    private fun item(state: String, outcome: String = "", kind: String = "queue", rowId: String = "", createdAt: Long = 100, id: String = "m-$state-$outcome-$rowId") =
        QueuedMessage("Hello $id", state = state, outcome = outcome, kind = kind, rowId = rowId, id = id, createdAt = createdAt)
    private fun inChat(items: List<QueuedMessage>, rows: List<Message> = history) = chatTimelineMessages(rows, items).map { it.id }
    private fun bubble(item: QueuedMessage) = "queued:${item.id}"

    @Test fun aQueuedMessageIsInTheBoxAndNotInTheChat() {
        val queued = item("queued")
        assertEquals(listOf(queued), queueBoxItems(listOf(queued)))
        assertFalse(bubble(queued) in inChat(listOf(queued)))
    }

    @Test fun aMessageSentAtOnceIsInTheChatAndNotInTheBox() {
        for (sent in listOf(item("sending", kind = "steer"), item("accepted"), item("unknown"), item("settled", "completed"))) {
            assertTrue(sent.state, queueBoxItems(listOf(sent)).isEmpty())
            assertTrue(sent.state, bubble(sent) in inChat(listOf(sent)))
        }
    }

    // Hermes settles failed, rejected and interrupted only for an admission it started (settle_session_input), and cancelled only for one that never
    // started (cancel_session_input). So the phone has proof Hermes never took a message only from a cancelled receipt; a refusal before any admission
    // never gets a settled state on the phone (the send returns to "queued" in the box).
    @Test fun aMessageHermesNeverStartedLeavesTheChatAndIsInTheBoxAtTheSameTime() {
        val cancelled = item("settled", "cancelled")
        assertTrue(cancelled.notSent())
        assertEquals(listOf(cancelled), queueBoxItems(listOf(cancelled)))
        assertFalse(bubble(cancelled) in inChat(listOf(cancelled)))
        assertEquals(QueueBoxAction.Retry, queueBoxAction(cancelled, replying = false))
    }

    @Test fun aMessageHermesRanStaysInTheChatWhateverItsTurnDid_andIsNeverRetried() {
        for (outcome in listOf("failed", "rejected", "interrupted")) {
            val ran = item("settled", outcome)
            assertFalse(outcome, ran.notSent())
            assertFalse(outcome, ran.inBox())
            assertTrue(outcome, queueBoxItems(listOf(ran)).isEmpty())
            assertEquals(outcome, 1, inChat(listOf(ran)).count { it == bubble(ran) })
            for (replying in listOf(false, true)) assertEquals(outcome, QueueBoxAction.None, queueBoxAction(ran, replying))
        }
    }

    @Test fun theSameMessageGoingFromAcceptedToFailedStaysInTheChatOnceWithNoGapAndNoCopy() {
        val accepted = item("accepted", id = "m1")
        val failed = accepted.copy(state = "settled", outcome = "failed")
        assertEquals(1, inChat(listOf(accepted)).count { it == bubble(accepted) })
        assertTrue(queueBoxItems(listOf(accepted)).isEmpty())
        assertEquals("still the same one bubble after the turn failed", 1, inChat(listOf(failed)).count { it == bubble(failed) })
        assertTrue("and nothing is offered in the box", queueBoxItems(listOf(failed)).isEmpty())
    }

    @Test fun aFailedTurnWhoseMessageHermesSavedStaysInTheChatAsItsSavedRow() {
        val failed = item("settled", "failed", rowId = "1")
        assertFalse(failed.notSent())
        assertTrue(queueBoxItems(listOf(failed)).isEmpty())
        assertEquals(listOf("1", "2"), inChat(listOf(failed)))
    }

    private fun savedRow(id: String, text: String) = json("row_id" to id, "role" to "user", "content" to text)

    @Test fun aFailedMessageWithASavedRowIsPairedWithItAndShownOnce_andWithoutOneTheCopyShowsOnce() {
        for (outcome in listOf("failed", "rejected", "interrupted")) {
            val copy = item("settled", outcome, id = "m1").copy(afterRowId = "2")
            val saved = history + Message("3", "user", copy.text)
            // The saved history has its row: the existing pairing (the one accepted copies use) gives the copy that row, so only the row shows.
            val pairs = acceptedRowPairs(listOf(copy), listOf(savedRow("1", "Earlier question"), savedRow("2", "Earlier answer").put("role", "assistant"), savedRow("3", copy.text)))
            assertEquals(outcome, mapOf("m1" to "3"), pairs)
            val paired = pairedCopy(copy, "3")
            assertEquals(outcome, listOf("1", "2", "3"), inChat(listOf(paired), saved))
            // Without a saved row the local copy shows, once.
            assertEquals(outcome, 1, inChat(listOf(copy), history).count { it == bubble(copy) })
        }
    }

    @Test fun aCancelledMessageThatHermesSavedAnywayIsPairedAndStaysInTheChat() {
        val copy = item("settled", "cancelled", id = "m1").copy(afterRowId = "2")
        val pairs = acceptedRowPairs(listOf(copy), listOf(savedRow("1", "Earlier question"), savedRow("2", "Earlier answer").put("role", "assistant"), savedRow("3", copy.text)))
        assertEquals(mapOf("m1" to "3"), pairs)
        assertFalse(pairedCopy(copy, "3").notSent())
    }

    @Test fun aRowIdFromHermesNeverMakesAMessageNotSentEvenBeforeItsRowHasLoaded() {
        // Hermes gave this message row 99 but the chat has not loaded that row yet: the message is in the chat, not the box, so a retry cannot duplicate it.
        val held = item("settled", "failed", rowId = "99")
        assertFalse(held.notSent())
        assertTrue(queueBoxItems(listOf(held)).isEmpty())
        assertTrue(bubble(held) in inChat(listOf(held)))
    }

    @Test fun everyMessageThePhoneHoldsIsShownInExactlyOnePlace() {
        val states = listOf("queued", "sending", "accepted", "unknown", "sent", "settled")
        val outcomes = listOf("", "completed", "failed", "interrupted", "cancelled", "rejected")
        for (state in states) for (outcome in outcomes) for (kind in listOf("queue", "steer")) {
            val unsaved = item(state, outcome, kind, id = "u-$state-$outcome-$kind")
            val inBox = unsaved in queueBoxItems(listOf(unsaved))
            val inTheChat = bubble(unsaved) in inChat(listOf(unsaved))
            assertNotEquals("$state/$outcome/$kind is in both or neither", inBox, inTheChat)
        }
    }

    @Test fun theBoxListsTheOldestFirst() {
        val newest = item("queued", createdAt = 300, id = "c")
        val oldest = item("queued", createdAt = 100, id = "a")
        val failed = item("settled", "cancelled", createdAt = 200, id = "b")
        assertEquals(listOf("a", "b", "c"), queueBoxItems(listOf(newest, oldest, failed)).map { it.id })
    }

    @Test fun steerOnAQueuedMessageNeedsARunningReplyAndNoFiles() {
        val text = item("queued")
        assertTrue(canSteerQueued(text, replying = true))
        assertFalse(canSteerQueued(text, replying = false))
        assertFalse(canSteerQueued(text.copy(attachments = listOf(JSONObject().put("name", "photo.png"))), replying = true))
        assertFalse(canSteerQueued(text.copy(text = " "), replying = true))
        assertFalse(canSteerQueued(item("settled", "failed"), replying = true))
        assertFalse(canSteerQueued(item("unknown"), replying = true))
    }

    @Test fun aMessageThatIsSimplyWaitingSaysSend_andOnlyANotSentOneSaysRetry() {
        val waiting = item("queued")
        val withFile = waiting.copy(attachments = listOf(JSONObject().put("name", "photo.png")))
        // Waiting, no reply running: Send. Retry would imply it failed.
        assertEquals(QueueBoxAction.Send, queueBoxAction(waiting, replying = false))
        assertEquals(QueueBoxAction.Send, queueBoxAction(withFile, replying = false))
        // A reply is running: Steer for plain text; nothing for a message with files (it cannot be steered).
        assertEquals(QueueBoxAction.Steer, queueBoxAction(waiting, replying = true))
        assertEquals(QueueBoxAction.None, queueBoxAction(withFile, replying = true))
        // Retry only on a message Hermes never started (cancelled), whether or not a reply is running; a failed, rejected or interrupted one ran, so no Retry.
        for (replying in listOf(false, true)) {
            assertEquals("cancelled/$replying", QueueBoxAction.Retry, queueBoxAction(item("settled", "cancelled"), replying))
            for (outcome in listOf("failed", "rejected", "interrupted")) assertNotEquals("$outcome/$replying", QueueBoxAction.Retry, queueBoxAction(item("settled", outcome), replying))
        }
        for (replying in listOf(false, true)) assertNotEquals(QueueBoxAction.Retry, queueBoxAction(waiting, replying))
    }

    @Test fun twoTapsOnSteerMoveTheRowAndSteerOnlyOnce() {
        val waiting = item("queued", id = "q1")
        // The draft lock runs two taps one after the other on the same row; the second finds the row already sent.
        val first = claimSteer(waiting, replying = true)
        assertEquals("sending", first?.state)
        assertEquals("steer", first?.kind)
        assertNull("the second tap must do nothing, not report the row as moved again", claimSteer(first!!, replying = true))
        // Nothing else can be claimed either: a reply that ended, files, or a row that is not waiting.
        assertNull(claimSteer(waiting, replying = false))
        assertNull(claimSteer(waiting.copy(attachments = listOf(JSONObject().put("name", "photo.png"))), replying = true))
        assertNull(claimSteer(item("unknown"), replying = true))
    }

    private fun notice(call: Boolean = false, approval: Boolean = false, question: Boolean = false, uncertain: Boolean = false, replying: Boolean = false, head: String? = "queued") =
        queueSendBlockedNotice(call, approval, question, uncertain, replying, head)

    @Test fun sendInTheBoxIsNeverASilentNoOp() {
        // Nothing blocks it: it acts.
        assertNull(notice())
        assertNull(notice(head = null))
        // An open request or a call.
        for (blocked in listOf(notice(call = true), notice(approval = true), notice(question = true))) assertEquals("Finish the call or answer the request first.", blocked)
        // An earlier message the phone cannot confirm.
        assertEquals("Tap your message to check it.", notice(uncertain = true))
        assertEquals("Tap your message to check it.", notice(head = "unknown"))
        // A reply is still running, or the earlier message is still with Hermes.
        assertEquals("Wait for the current reply first.", notice(replying = true))
        assertEquals("Wait for the current reply first.", notice(head = "accepted"))
        assertEquals("Wait for the current reply first.", notice(head = "sending"))
    }

    @Test fun steeringAQueuedMessageSendsItAtOnceAsASteer() {
        val steered = item("queued", id = "q1").asSteer()
        assertEquals("sending", steered.state)
        assertEquals("steer", steered.kind)
        assertEquals("q1", steered.id)
        assertTrue(queueBoxItems(listOf(steered)).isEmpty())
        assertTrue(bubble(steered) in inChat(listOf(steered)))
    }

    @Test fun retryingAMessageThatDidNotGoThroughHoldsItAgainWithAFreshSubmission() {
        val failed = item("settled", "cancelled", id = "f1").copy(admissionId = "admission-1", submissionId = "old")
        val again = failed.heldAgain()
        assertEquals("queued", again.state)
        assertEquals("queue", again.kind)
        assertEquals("", again.admissionId)
        assertEquals("", again.outcome)
        assertNotEquals("old", again.submissionId)
        assertEquals("f1", again.id)
        assertEquals(failed.text, again.text)
        assertEquals(listOf(again), queueBoxItems(listOf(again)))
    }

    // What Hermes said about the admission (queued behind another turn, or started) is kept on the copy, because blue ticks need it.
    @Test fun onlyAQueuedOrStartedAdmissionIsRemembered() {
        assertEquals("queued", admissionStatusOf("queued"))
        assertEquals("started", admissionStatusOf("started"))
        for (other in listOf("", "terminal", "unknown", "rejected", "QUEUED")) assertEquals(other, "", admissionStatusOf(other))
    }

    @Test fun theAdmissionStatusSurvivesTheSavedDraft_andACopySavedEarlierHasNone() {
        val waiting = item("accepted", id = "w1").copy(admissionId = "admission-1", admissionStatus = "queued")
        assertEquals("queued", QueuedMessage.decode(waiting.encode()).admissionStatus)
        val draft = dev.hermes.android.core.Draft(queued = listOf(waiting))
        assertEquals("queued", dev.hermes.android.core.Draft.decode(draft.encode()).queued.single().admissionStatus)
        // A copy written before this field existed decodes with no status.
        val legacy = waiting.encode().apply { remove("admission_status") }
        assertEquals("", QueuedMessage.decode(legacy).admissionStatus)
    }

    @Test fun aReceiptCheckUpdatesTheAdmissionStatusOfAnAcceptedCopyOnly() {
        val waiting = item("accepted", id = "w1").copy(admissionStatus = "queued")
        assertEquals("started", waiting.afterReceipt("accepted", "", "started").admissionStatus)
        assertEquals("queued", waiting.afterReceipt("accepted", "", "queued").admissionStatus)
        // A receipt that could not be read (unknown) or a finished turn does not make up a status.
        assertEquals("queued", waiting.afterReceipt("unknown", "", "").admissionStatus)
        val settled = waiting.afterReceipt("settled", "completed", "terminal")
        assertEquals("settled", settled.state)
        assertEquals("completed", settled.outcome)
        assertEquals("queued", settled.admissionStatus)
    }

    /** R9 Nice 5: a receipt read at Hermes just before the claim and answered after the start frame must not turn a blue copy grey again. */
    @Test fun aLateQueuedReceiptNeverMovesAStartedCopyBackToQueued() {
        val started = item("accepted", id = "w1").copy(admissionId = "admission-1", admissionStatus = "started")
        assertEquals("started", started.afterReceipt("accepted", "", "queued").admissionStatus)
        // The other directions are unchanged: queued becomes started, and a started copy stays started on a started receipt.
        assertEquals("started", item("accepted", id = "w2").copy(admissionStatus = "queued").afterReceipt("accepted", "", "started").admissionStatus)
        assertEquals("started", started.afterReceipt("accepted", "", "started").admissionStatus)
        // A receipt that says nothing keeps what the copy has.
        assertEquals("started", started.afterReceipt("unknown", "", "").admissionStatus)
    }

    // A frame of the turn Hermes runs carries that turn's admission. Only a frame of THIS copy's admission shows Hermes started it.
    @Test fun aFrameOfTheCopysOwnAdmissionMarksItStarted() {
        val waiting = item("accepted", id = "w1").copy(admissionId = "admission-1", admissionStatus = "queued")
        assertEquals("started", waiting.afterTurnFrame("admission-1")?.admissionStatus)
        assertFalse(waiting.afterTurnFrame("admission-1")!!.queuedBehindAnotherTurn())
    }

    @Test fun aFrameOfAnotherTurnLeavesTheCopyWaiting() {
        val waiting = item("accepted", id = "w1").copy(admissionId = "admission-1", admissionStatus = "queued")
        assertNull(waiting.afterTurnFrame("admission-other"))
        // A frame with no stamp says nothing, and a copy without an admission id is never matched by a blank stamp.
        assertNull(waiting.afterTurnFrame(""))
        assertNull(item("accepted", id = "w2").copy(admissionStatus = "queued").afterTurnFrame(""))
    }

    @Test fun theFrameOfACancelledQueuedRowDoesNotMarkItStarted() {
        // Hermes publishes a cancelled row's completion under the row's own admission (session_authority._settle_cancelled); the row never ran.
        val waiting = item("accepted", id = "w1").copy(admissionId = "admission-1", admissionStatus = "queued")
        assertNull(waiting.afterTurnFrame("admission-1", outcome = "cancelled"))
    }

    @Test fun onlyAnAcceptedPlainCopyStillQueuedIsChanged() {
        val frame = "admission-1"
        assertNull(item("accepted", id = "a").copy(admissionId = frame, admissionStatus = "started").afterTurnFrame(frame))
        assertNull(item("accepted", id = "b").copy(admissionId = frame).afterTurnFrame(frame))
        assertNull(item("accepted", kind = "steer", id = "c").copy(admissionId = frame, admissionStatus = "queued").afterTurnFrame(frame))
        assertNull(item("settled", "failed", id = "d").copy(admissionId = frame, admissionStatus = "queued").afterTurnFrame(frame))
    }

    @Test fun theBoxButtonsNameTheFirst40CharactersOfTheMessage() {
        assertEquals("Edit Book the table", queueButtonLabel("Edit", "Book the table"))
        val pasted = "word ".repeat(200)
        val label = queueButtonLabel("Remove", pasted)
        assertEquals("Remove " + pasted.substring(0, 40).trimEnd(), label)
        assertTrue(label.length <= "Remove ".length + 40)
        // A pair of UTF-16 units (an emoji) is never cut in half at the limit.
        val emoji = "a".repeat(39) + "😀" + "tail"
        assertFalse(Character.isHighSurrogate(queueButtonLabel("Edit", emoji).last()))
    }

    @Test fun aCopyHeldAgainHasNoAdmissionYet() {
        val again = item("settled", "cancelled", id = "f1").copy(admissionId = "admission-1", admissionStatus = "queued").heldAgain()
        assertEquals("", again.admissionStatus)
    }

    /**
     * CHAT-121 follow-up 9: a waiting message whose file could not get through (its tries failed) stays in the box and says "Not sent", with its Send, as
     * the offline outbox's message does; a message merely waiting says nothing. It is saved that way, so a restart keeps it, and the words are the box's
     * own ("Not sent", "Send"), already in every language of the catalog.
     */
    @Test fun aQueuedMessageWhoseFileCouldNotGetThroughSaysNotSentWithItsSend() {
        val stopped = QueuedMessage("Synthetic report attached", listOf(json("name" to "Synthetic report.pdf")), state = "queued", outcome = FILE_NOT_SENT_OUTCOME, id = "f9")
        val waiting = stopped.copy(outcome = "", id = "w9")
        assertTrue(stopped.fileNotSent() && stopped.inBox())
        assertTrue(queueBoxSaysNotSent(stopped))
        assertFalse(queueBoxSaysNotSent(waiting))
        assertEquals(QueueBoxAction.Send, queueBoxAction(stopped, replying = false))
        assertEquals(listOf("w9", "f9"), queueBoxItems(listOf(stopped.copy(createdAt = 200), waiting.copy(createdAt = 100))).map { it.id })
        val restarted = QueuedMessage.decode(JSONObject(stopped.encode().toString()))
        assertTrue("saved as not sent", restarted.fileNotSent() && queueBoxSaysNotSent(restarted))
        // A message Hermes never started keeps its own "Not sent" and Retry.
        assertTrue(queueBoxSaysNotSent(item("settled", "cancelled")))
        val app = listOf(".", "app", "beacon-android/app").map { java.io.File(it) }.first { java.io.File(it, "src/main/assets/native-locales.json").isFile }
        val catalog = JSONObject(java.io.File(app, "src/main/assets/native-locales.json").readText(Charsets.UTF_8).removePrefix("﻿"))
        for (language in catalog.keys()) for (words in listOf("not sent", "send"))
            assertTrue("$language: $words", catalog.getJSONObject(language).optString(words).isNotBlank())
    }

    @Test fun onlyAMessageInTheBoxCanBeRetriedEditedOrRemovedFromIt() {
        assertTrue(item("queued").inBox())
        assertTrue(item("settled", "cancelled").inBox())
        assertFalse(item("settled", "failed").inBox())
        assertFalse(item("accepted").inBox())
        assertFalse(item("unknown").inBox())
        assertFalse(item("settled", "failed", rowId = "1").inBox())
    }

    // H1b P2-14: a Not sent row says why, in a word the owner knows; a waiting row says nothing.
    @Test fun aNotSentRowSaysWhy() {
        assertEquals("Offline", notSentReason(item("queued", OFFLINE_OUTCOME)))
        assertEquals("Upload failed", notSentReason(item("queued", FILE_NOT_SENT_OUTCOME)))
        assertEquals("Didn’t arrive", notSentReason(item("settled", "cancelled")))
        assertEquals("Its chat was deleted", notSentReason(item("settled", CHAT_GONE_OUTCOME)))
        assertTrue("it waits in the box with Retry", item("settled", CHAT_GONE_OUTCOME).inBox() && queueBoxAction(item("settled", CHAT_GONE_OUTCOME), false) == QueueBoxAction.Retry)
        assertEquals("a held row's sentence stays the line above the box", "Offline", notSentReason(item("queued", OFFLINE_OUTCOME).copy(held = "Synthetic hold reason")))
        assertEquals("", notSentReason(item("queued")))
        assertEquals("", notSentReason(item("settled", "cancelled", rowId = "1")))
    }

    // H1b P1-3: back in a chat while its reply runs, the owner's message shows once: the row Hermes stamped with the copy's submission id IS that copy.
    @Test fun aCopyNeverShowsBesideTheRowHermesStampedWithItsSubmission() {
        val copy = item("accepted").copy(submissionId = "sub-1")
        val stamped = Message.from(json("row_id" to 3L, "role" to "user", "content" to "Hello again", "display_metadata" to json("submission_id" to "sub-1")), 2, "chat")
        assertEquals("sub-1", stamped.submission)
        assertEquals(listOf("1", "2", "3"), inChat(listOf(copy), history + stamped))
        assertEquals("another send's row hides nothing", listOf("1", "2", bubble(copy)), inChat(listOf(copy), history))
    }
}
