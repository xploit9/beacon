package dev.hermes.android

import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Live check 8 Nov 2026: a photo with a caption was sent, Hermes was slow, and reopening the chat from the drawer while the turn still ran showed the
 * message twice (the phone's own "Sent" copy and Hermes' saved row), and it stayed doubled after Stop. Hermes had accepted the turn without naming its row,
 * so the copy kept no row and nothing paired it with the saved row until a completed turn proved it. Synthetic text and ids only.
 */
class ReopenedPhotoTurnTest {
    private val hash = "5d3c74026cd9dcf0d35468e783e47f6fe91445954ffadf38c5b96fb09bd1ba28"
    private val stored = "/home/example/.hermes/cache/documents/native-inputs/$hash/upload_cu6pt145.png"
    private val caption = "What color is the square in this image Reply in one word"
    private val note = "[The user sent an image~ Here's what I can see:\nA solid orange rectangle is centered on a dark background.]\n[If you need a closer look, use vision_analyze with image_url: $stored ~]"
    private val photo = json("id" to "11111111-2222-3333-4444-555555555555", "name" to "409.png", "mime" to "image/png")

    private fun earlier() = json("row_id" to 10L, "role" to "assistant", "content" to "Synthetic earlier answer")
    private fun saved(id: Long = 11L, text: String = "$note\n\n$caption") = json("row_id" to id, "role" to "user", "content" to text)
    private fun copy(id: String = "local-1", state: String = "accepted", outcome: String = "", at: Long = 1_000L, text: String = caption, after: String? = "10",
        attachments: List<JSONObject> = listOf(photo), kind: String = "queue", rowId: String = "") =
        QueuedMessage(text, attachments, id = id, createdAt = at, state = state, kind = kind, outcome = outcome, afterRowId = after, rowId = rowId,
            submissionId = "submission-$id", admissionId = "admission-$id")
    private fun shown(rows: List<JSONObject>, vararg local: QueuedMessage) =
        chatTimelineMessages(rows.mapIndexed { i, row -> Message.from(row, i, "chat") }, local.toList())

    @Test fun thePhoneCopyAndTheSavedRowAreTwoBubblesUntilTheyArePaired() {
        // The state the live check saw: the copy has no row, so it shows beside the saved row.
        val rows = listOf(earlier(), saved())
        assertEquals(2, shown(rows, copy()).count { it.role == "user" })
    }

    @Test fun aPhotoSendStillRunningPairsWithItsSavedRowAndIsOneBubbleWithTheCaptionAndThePhoto() {
        val rows = listOf(earlier(), saved())
        val item = copy()
        assertEquals(mapOf("local-1" to "11"), acceptedRowPairs(listOf(item), rows))
        val linked = item.copy(rowId = "11", state = "sent")
        val bubbles = shown(rows, linked).filter { it.role == "user" }
        assertEquals("one bubble", 1, bubbles.size)
        assertEquals(caption, bubbles.single().text)
        assertEquals("the photo stays with the row", 1, bubbles.single().media.size)
        assertFalse(bubbles.single().text.contains("vision_analyze") || bubbles.single().text.contains("native-inputs"))
    }

    @Test fun itStaysOneBubbleAfterStopAndAfterTheTurnSettles() {
        val running = listOf(earlier(), saved())
        val settled = running + json("row_id" to 12L, "role" to "assistant", "content" to "Orange")
        // Pairing happened while the turn ran, then the turn ends one way or another.
        for ((state, outcome) in listOf("sent" to "", "settled" to "cancelled", "settled" to "failed", "settled" to "completed")) {
            val linked = copy(state = state, outcome = outcome, rowId = "11")
            assertEquals("$state/$outcome after the row is known", 1, shown(running, linked).count { it.role == "user" })
            assertEquals("$state/$outcome once the reply is saved", 1, shown(settled, linked).count { it.role == "user" })
        }
        // Stop came before the chat was reopened: the copy is settled without a row, and the saved row still pairs with it.
        for (outcome in listOf("cancelled", "failed", "interrupted")) {
            val stopped = copy(state = "settled", outcome = outcome)
            assertEquals(outcome, mapOf("local-1" to "11"), acceptedRowPairs(listOf(stopped), settled))
        }
        // A completed turn is tied to its row by Hermes' receipt, never by words.
        assertTrue(acceptedRowPairs(listOf(copy(state = "settled", outcome = "completed")), settled).isEmpty())
    }

    // R7: Hermes settles "rejected" only for an admission it had started (settle_session_input), so a rejected turn's message reached the agent and its saved
    // row is the copy's own, like a failed or interrupted one. (Before R7 a rejected copy was assumed never saved and never paired.)
    @Test fun aRejectedTurnPairsWithItsSavedRowLikeAFailedOne() {
        val rows = listOf(earlier(), saved())
        for (outcome in listOf("rejected", "failed")) assertEquals(outcome, mapOf("local-1" to "11"), acceptedRowPairs(listOf(copy(state = "settled", outcome = outcome)), rows))
    }

    @Test fun nothingIsPairedOnDoubtSoNoMessageIsHidden() {
        val rows = listOf(earlier(), saved())
        // Not accepted by Hermes (or outcome unknown): the copy stays, with its resend choices.
        for (state in listOf("queued", "sending", "unknown", "sent")) assertTrue(state, acceptedRowPairs(listOf(copy(state = state)), rows).isEmpty())
        // A row that was already there when the message was sent is not its row.
        assertTrue(acceptedRowPairs(listOf(copy(after = "11")), rows).isEmpty())
        // No known place to look from.
        assertTrue(acceptedRowPairs(listOf(copy(after = null)), rows).isEmpty())
        assertTrue(acceptedRowPairs(listOf(copy(after = "99")), rows).isEmpty())
        // Other words.
        assertTrue(acceptedRowPairs(listOf(copy(text = "A different question")), rows).isEmpty())
        // A row another copy already holds.
        assertTrue(acceptedRowPairs(listOf(copy(), copy(id = "local-2", rowId = "11", state = "sent")), rows).isEmpty())
        // Steering has its own pairing.
        assertTrue(acceptedRowPairs(listOf(copy(kind = "steer")), rows).isEmpty())
        // An assistant row with the same words is not the message.
        assertTrue(acceptedRowPairs(listOf(copy()), listOf(earlier(), json("row_id" to 11L, "role" to "assistant", "content" to caption))).isEmpty())
    }

    @Test fun aPhotoWithoutACaptionPairsOnlyWithARowThatHoldsAPhoto() {
        val bare = copy(text = "")
        assertEquals(mapOf("local-1" to "11"), acceptedRowPairs(listOf(bare), listOf(earlier(), saved(text = note))))
        assertTrue("a row with no photo is somebody else's", acceptedRowPairs(listOf(bare), listOf(earlier(), saved(text = ""))).isEmpty())
        assertTrue("a copy with no photo and no words pairs with nothing", acceptedRowPairs(listOf(copy(text = "", attachments = emptyList())), listOf(earlier(), saved(text = note))).isEmpty())
    }

    @Test fun twoSendsWithTheSameWordsEachPairWithTheirOwnRowInOrder() {
        val rows = listOf(earlier(), saved(11L), json("row_id" to 12L, "role" to "assistant", "content" to "Orange"), saved(13L))
        val first = copy(id = "local-1", at = 1_000L)
        val second = copy(id = "local-2", at = 2_000L, after = "12")
        assertEquals(mapOf("local-1" to "11", "local-2" to "13"), acceptedRowPairs(listOf(second, first), rows))
        // Only the first row is saved so far: the second copy stays visible.
        assertEquals(mapOf("local-1" to "11"), acceptedRowPairs(listOf(first, second), rows.take(2)))
    }

    @Test fun aSavedRowThatCarriesAFileNoteIsPairedByItsCaptionToo() {
        val doc = "[The user sent a document: 'Report [final].pdf'. It is saved at: /home/example/cache/documents/doc_ab12_Report [final].pdf. Its text is not inlined here (it's a binary format such as PDF or DOCX). " +
            "To read it, extract the document's text yourself — for example with the terminal tool or the ocr-and-documents skill — before answering, instead of asking the user to paste the contents.]"
        val file = json("id" to "99999999-2222-3333-4444-555555555555", "name" to "Report [final].pdf", "mime" to "application/pdf")
        assertEquals(mapOf("local-1" to "11"), acceptedRowPairs(listOf(copy(text = "Please summarise this", attachments = listOf(file))), listOf(earlier(), saved(text = "$doc\n\nPlease summarise this"))))
    }

    @Test fun theLiveCheckSourceLabelNeverNamesTheCommandLineForAChatThisPhoneMade() {
        // Hermes' own core stores "cli" for a chat the app opened (it drops the app's source there), so that source says nothing about where the chat came from.
        assertFalse(dev.hermes.android.ui.showsSourceCaption("cli", thread = false))
        assertFalse(dev.hermes.android.ui.showsSourceCaption("CLI ", thread = false))
        assertFalse(dev.hermes.android.ui.showsSourceCaption("android", thread = false))
        assertTrue(dev.hermes.android.ui.showsSourceCaption("telegram", thread = false))
        assertTrue(dev.hermes.android.ui.showsSourceCaption("tui", thread = false))
    }

    // ---- review round 5

    private fun receipt(user: Long, final: Long, item: QueuedMessage) = json("status" to "terminal", "outcome" to "completed", "admission_id" to item.admissionId,
        "_confirmed_persisted_turn" to json("complete" to true, "submission_id" to item.submissionId, "user_row_id" to user, "user_row_ids" to JSONArray(listOf(user)),
            "final_assistant_row_id" to final, "row_ids" to JSONArray(listOf(user, final))))

    @Test fun aPhotoCopyNeverPairsWithAPlainTextRowAndATextCopyNeverWithAPhotoRow() {
        // Another sender wrote the same words with no photo: the photo must not land on that message and the copy must not vanish behind it.
        val plain = listOf(earlier(), saved(text = caption))
        assertTrue(acceptedRowPairs(listOf(copy()), plain).isEmpty())
        assertEquals(2, shown(plain, copy()).count { it.role == "user" })
        // The other way round: a text copy is not a photo row.
        assertTrue(acceptedRowPairs(listOf(copy(attachments = emptyList())), listOf(earlier(), saved())).isEmpty())
        // A text copy pairs with a text row, as before.
        assertEquals(mapOf("local-1" to "11"), acceptedRowPairs(listOf(copy(attachments = emptyList())), plain))
        // A photo-only copy needs a photo row, a words-only row never serves it.
        assertTrue(acceptedRowPairs(listOf(copy(text = "")), listOf(earlier(), saved(text = "Something typed elsewhere"))).isEmpty())
    }

    @Test fun onlyTheFirstUnclaimedUserRowAfterTheBoundaryCanBeTheCopysRowSoAnInterleavedMessageLeavesItVisible() {
        val foreign = json("row_id" to 11L, "role" to "user", "content" to "A message from another device")
        val rows = listOf(earlier(), foreign, saved(12L))
        val item = copy()
        // Not the first row after the boundary, so it is not looked for further down: the copy stays beside both rows.
        assertTrue(acceptedRowPairs(listOf(item), rows).isEmpty())
        assertEquals(3, shown(rows, item).count { it.role == "user" })
        // The completed turn's receipt then ties the copy to its real row and the copy leaves.
        val bound = requireNotNull(boundQueuedInput(item, receipt(12L, 13L, item), rows, listOf(item)))
        assertEquals("12", bound.rowId)
        assertEquals(2, shown(rows, bound).count { it.role == "user" })
        // A row another copy already holds is skipped: the next user row is then the first unclaimed one.
        val other = copy(id = "local-2", rowId = "11", state = "sent", at = 500L)
        assertEquals(mapOf("local-1" to "12"), acceptedRowPairs(listOf(item, other), rows))
    }

    @Test fun aCancelledOrFailedCopyHermesNeverSavedIsNeverPairedWithALookAlikeRow_aCancelledOneWaitsInTheBoxAndAFailedOneStaysInTheChat() {
        val text = copy(text = "ok", attachments = emptyList(), state = "settled", outcome = "cancelled")
        val foreign = json("row_id" to 11L, "role" to "user", "content" to "Another message")
        val lookAlike = json("row_id" to 12L, "role" to "user", "content" to "ok")
        val rows = listOf(earlier(), foreign, lookAlike)
        for (outcome in listOf("cancelled", "failed")) {
            val never = text.copy(outcome = outcome)
            assertTrue(outcome, acceptedRowPairs(listOf(never), rows).isEmpty())
        }
        // Hermes cancelled it before it started, so it is no chat bubble next to the saved rows: it waits in the box above the composer as "Not sent".
        val cancelled = text.copy(outcome = "cancelled")
        assertEquals(2, shown(rows, cancelled).count { it.role == "user" })
        assertEquals(listOf(cancelled), queueBoxItems(listOf(cancelled)))
        // A failed turn was started by Hermes, so the message reached the agent: its copy stays in the chat (once) and is not offered again.
        val failed = text.copy(outcome = "failed")
        assertEquals(3, shown(rows, failed).count { it.role == "user" })
        assertTrue(queueBoxItems(listOf(failed)).isEmpty())
        // When the look-alike really is the first user row after the boundary, a stopped turn's saved row is told by its words.
        assertEquals(mapOf("local-1" to "12"), acceptedRowPairs(listOf(text), listOf(earlier(), lookAlike)))
    }

    @Test fun aReceiptCorrectsARowPairedByWordsButNeverOneHermesNamedItself() {
        val rows = listOf(earlier(), saved(11L), saved(12L))
        val paired = copy(state = "sent", rowId = "11", outcome = PAIRED_BY_WORDS)
        val proof = receipt(12L, 13L, paired)
        assertEquals("12", linkedQueuedInputRow(paired, proof, rows, listOf(paired)))
        val bound = requireNotNull(boundQueuedInput(paired, proof, rows, listOf(paired)))
        assertEquals("12", bound.rowId)
        assertEquals("settled", bound.state)
        assertEquals("completed", bound.outcome)
        assertEquals("one bubble per saved row, none for the copy", 2, shown(rows, bound).count { it.role == "user" })
        // Hermes named row 11 itself at submit time: a proof that says another row is refused, as before.
        val given = copy(state = "sent", rowId = "11")
        assertNull(linkedQueuedInputRow(given, receipt(12L, 13L, given), rows, listOf(given)))
        // The proof's own row never starts from a guessed one.
        assertEquals("", knownTurnRow(paired))
        assertEquals("11", knownTurnRow(given))
        assertEquals("", knownTurnRow(null))
    }

    @Test fun theWrongRowsPhotoIsTakenBackWhenAReceiptMovesTheCopyToItsRealRow() {
        val withPhoto = saved(11L, caption).apply { put("attachments", JSONArray(listOf(photo))) }
        val chat = ChatState(sessionId = "chat", messages = listOf(Message.from(earlier(), 0, "chat"), Message.from(withPhoto, 1, "chat"), Message.from(saved(12L), 2, "chat")),
            raw = json("messages" to JSONArray(listOf(earlier(), withPhoto, saved(12L)))))
        val released = releasedAttachmentChat(chat, { true }, "11", setOf(photo.getString("id")))
        assertTrue(released.messages.first { it.id == "11" }.attachments.isEmpty())
        assertEquals(0, released.raw.rows("messages").first { it.optLong("row_id") == 11L }.optJSONArray("attachments")?.length())
        assertEquals("another row is untouched", chat.messages.first { it.id == "12" }, released.messages.first { it.id == "12" })
        // Attachments the row had of its own are never taken.
        val own = json("id" to "someone-elses-file", "name" to "x.png", "mime" to "image/png")
        val theirs = saved(11L, caption).apply { put("attachments", JSONArray(listOf(own))) }
        val kept = ChatState(sessionId = "chat", messages = listOf(Message.from(theirs, 0, "chat")), raw = json("messages" to JSONArray(listOf(theirs))))
        assertEquals(kept.messages, releasedAttachmentChat(kept, { true }, "11", setOf(photo.getString("id"))).messages)
        // Another chat now: nothing changes.
        assertEquals(chat.messages, releasedAttachmentChat(chat, { false }, "11", setOf(photo.getString("id"))).messages)
    }

    // ---- review round 6

    @Test fun aCopyPairedByWordsKeepsItsMarkerThroughSavingAndRestoring() {
        val paired = copy(state = "sent", rowId = "11", outcome = PAIRED_BY_WORDS)
        val restored = QueuedMessage.decode(paired.encode())
        assertEquals(PAIRED_BY_WORDS, restored.outcome)
        assertEquals("sent", restored.state)
        assertEquals("11", restored.rowId)
        // And through the saved draft the app restarts from.
        val draft = dev.hermes.android.core.Draft.decode(dev.hermes.android.core.Draft(queued = listOf(paired)).encode())
        assertEquals(PAIRED_BY_WORDS, draft.queued.single().outcome)
        assertEquals("11", draft.queued.single().rowId)
    }

    @Test fun pairingRecordsTheRowAndTheMarkerOnlyOnACopyWithNoRow() {
        val accepted = pairedCopy(copy(), "11")
        assertEquals(Triple("sent", "11", PAIRED_BY_WORDS), Triple(accepted.state, accepted.rowId, accepted.outcome))
        val stopped = pairedCopy(copy(state = "settled", outcome = "cancelled"), "11")
        assertEquals(Triple("settled", "11", "cancelled"), Triple(stopped.state, stopped.rowId, stopped.outcome))
        val named = copy(state = "sent", rowId = "7")
        assertSame("a row Hermes named is never replaced", named, pairedCopy(named, "11"))
        val queued = copy(state = "queued")
        assertSame(queued, pairedCopy(queued, "11"))
        val steer = copy(kind = "steer")
        assertSame(steer, pairedCopy(steer, "11"))
    }

    @Test fun aCopyPairedInTheSameRenderThatEndsTheTurnIsStillCorrected() {
        // 11 is another sender's photo with the same words, 12 the phone's own: the words alone lead to 11.
        val rows = listOf(earlier(), saved(11L), saved(12L))
        val stale = copy()
        val pairs = acceptedRowPairs(listOf(stale), rows)
        assertEquals(mapOf("local-1" to "11"), pairs)
        // The finish block read the copy before the render paired it; binding must start from the copy as the render left it.
        val afterRender = listOf(stale).map { pairedCopy(it, pairs.getValue(it.id)) }
        val item = currentCopy(afterRender, stale)
        assertEquals("11", item.rowId)
        assertEquals(PAIRED_BY_WORDS, item.outcome)
        val linked = requireNotNull(linkedQueuedInputRow(item, receipt(12L, 13L, item), rows, afterRender))
        assertEquals("12", linked)
        assertEquals("the picture the wrong row got is taken back", "11", wrongPairedRow(item, linked))
        assertNull("the stale copy would not have known", wrongPairedRow(stale, linked))
        // A copy already known as paired is used as given; an unpaired one takes the latest saved copy of the same send.
        assertSame(item, currentCopy(emptyList(), item))
        assertEquals(afterRender.single(), currentCopy(afterRender, stale.copy(text = stale.text)))
    }

    @Test fun aCancelledCopyTakesARowOnlyWhenTheSendThatIsStillRunningHasLeftOne() {
        val cancelled = copy(id = "local-1", state = "settled", outcome = "cancelled", at = 1_000L, text = "ok", attachments = emptyList())
        val resent = copy(id = "local-2", at = 2_000L, text = "ok", attachments = emptyList())
        val ok = { id: Long -> json("row_id" to id, "role" to "user", "content" to "ok") }
        // Cancelled before Hermes ran it, then the same words sent again: the new send gets the only row.
        assertEquals(mapOf("local-2" to "11"), acceptedRowPairs(listOf(cancelled, resent), listOf(earlier(), ok(11L))))
        // A second row with the same words is left over: a stopped copy may take it.
        val rows = listOf(earlier(), ok(11L), json("row_id" to 12L, "role" to "assistant", "content" to "Done"), ok(13L))
        assertEquals(mapOf("local-2" to "11", "local-1" to "13"), acceptedRowPairs(listOf(cancelled, resent), rows))
    }

    @Test fun aPairedSentCopyIsSettledByALaterReceiptAndRemovedByTheSweep() {
        val rows = listOf(earlier(), saved(11L), saved(12L), json("row_id" to 13L, "role" to "assistant", "content" to "Orange"))
        val paired = copy(state = "sent", rowId = "11", outcome = PAIRED_BY_WORDS)
        // The 60 s sweep asks about it; no other sent or accepted copy is its business.
        assertTrue(receiptSweepCandidate(paired))
        assertTrue(receiptSweepCandidate(copy(state = "settled", outcome = "completed")))
        assertFalse(receiptSweepCandidate(copy(state = "sent", rowId = "11")))
        assertFalse(receiptSweepCandidate(copy()))
        assertFalse(receiptSweepCandidate(copy(state = "settled", outcome = "cancelled")))
        assertFalse(receiptSweepCandidate(paired.copy(admissionId = "")))
        // The refresh treats it as matchable and eligible, before and after the receipt moves it.
        assertTrue(refreshableCopy(paired, paired, automatic = true))
        assertTrue(refreshableCopy(paired, paired, automatic = false))
        assertTrue(refreshableCopy(paired, copy(state = "settled", outcome = "completed", rowId = "12"), automatic = true))
        assertFalse("a sent copy nobody paired is not asked about", refreshableCopy(copy(state = "sent", rowId = "11"), copy(state = "sent", rowId = "11"), automatic = true))
        assertFalse(refreshableCopy(paired, copy(state = "queued"), automatic = true))
        // Still running, or a receipt nobody can read: nothing changes on doubt, and the marker stays for the later correction.
        assertEquals(paired, pairedCopyAfterReceipt(paired, "accepted", ""))
        assertEquals(paired, pairedCopyAfterReceipt(paired, "unknown", ""))
        // Ended some other way: settled with that outcome, its row kept.
        val stopped = pairedCopyAfterReceipt(paired, "settled", "cancelled")
        assertEquals(Triple("settled", "11", "cancelled"), Triple(stopped.state, stopped.rowId, stopped.outcome))
        // Completed: the receipt's row replaces the guess and the copy can then leave.
        val proof = receipt(12L, 13L, paired)
        val bound = requireNotNull(boundQueuedInput(paired, proof, rows, listOf(paired)))
        assertEquals(Triple("settled", "12", "completed"), Triple(bound.state, bound.rowId, bound.outcome))
        assertEquals("12", completedQueuedInputRow(bound, proof, rows, rows, listOf(bound)))
        assertEquals("one bubble per saved row", 2, shown(rows, bound).count { it.role == "user" })
    }
}
