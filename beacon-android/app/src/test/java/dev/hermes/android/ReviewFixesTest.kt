package dev.hermes.android

import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.json
import dev.hermes.android.ui.AttachedMedia
import dev.hermes.android.ui.isOwnUpload
import dev.hermes.android.ui.pairMediaWithAttachments
import dev.hermes.android.ui.savedRepliesOnce
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

// The placeholder home of the synthetic fixture paths, in two pieces so no source literal reads like a real Windows home path.
private val exampleHome = "C:\\" + "Users\\Example\\"

/** The independent review of the 1.1.34 chat changes: a bubble is never hidden on doubt, and a proof in hand is used at once. Synthetic ids only. */
class ReviewFixesTest {
    private fun user(id: Long, text: String, kind: String = "") = json("row_id" to id, "role" to "user", "content" to text).apply { if (kind.isNotBlank()) put("display_kind", kind) }
    private fun assistant(id: Long, text: String) = json("row_id" to id, "role" to "assistant", "content" to text)
    private fun bubble(text: String, at: Long = 1, state: String = "accepted", rowId: String = "") =
        QueuedMessage(text, id = "steer-$at", createdAt = at, state = state, kind = "steer", rowId = rowId)

    // ---- 1: a Send-now bubble is never linked to a row that is not its own

    @Test fun anOlderIdenticalSteerRowIsNotTakenWhileTheTurnRuns() {
        val rows = listOf(user(100, "First question"), user(101, "stop", "steer"), assistant(102, "Stopped"), user(110, "Second question"), assistant(111, "Working"))
        assertTrue("the old 'stop' belongs to an earlier turn", steerRowPairs(listOf(bubble("stop")), emptyList(), rows).isEmpty())
    }

    @Test fun anOlderIdenticalSteerRowIsNotTakenWhenTheReceiptListsRowsNotYetInHistory() {
        val rows = listOf(user(100, "First question"), user(101, "stop", "steer"), assistant(102, "Stopped"), user(110, "Second question"))
        assertTrue(steerRowPairs(listOf(bubble("stop")), listOf(110L, 121L), rows).isEmpty())
    }

    @Test fun aBubbleOfUnknownOutcomeIsNotLinkedToARowAnotherBubbleHolds() {
        val rows = listOf(user(110, "Question"), user(120, "stop", "steer"), assistant(121, "Stopped"))
        val held = bubble("stop", at = 1, state = "sent", rowId = "120")
        val unsure = bubble("stop", at = 2, state = "unknown")
        assertTrue("the held row stays with its own bubble", steerRowPairs(listOf(held, unsure), listOf(110L, 120L), rows).isEmpty())
        val shown = chatTimelineMessages(rows.mapIndexed { index, row -> Message.from(row, index, "chat") }, listOf(held, unsure))
        assertTrue("so its warning stays on screen", shown.any { it.id == "queued:${unsure.id}" })
    }

    @Test fun aSteerAfterThisTurnsPromptStillPairsByTheReceiptAndByWords() {
        val rows = listOf(user(100, "First question"), user(101, "stop", "steer"), user(110, "Second question"), user(120, "stop", "steer"), assistant(121, "Stopped"))
        assertEquals(mapOf("steer-1" to "120"), steerRowPairs(listOf(bubble("stop")), listOf(110L, 120L), rows))
        assertEquals(mapOf("steer-1" to "120"), steerRowPairs(listOf(bubble("stop")), emptyList(), rows))
    }

    // ---- 2: a proof already in hand ends the turn at once

    @Test fun aValidDoneProofIsUsedWithoutWaitingForTheReceiptLookup() = runBlocking {
        val identity = TurnIdentity("submission-1", "admission-1")
        val turn = json("complete" to true, "submission_id" to "submission-1", "user_row_id" to 10L, "user_row_ids" to JSONArray(listOf(10L)),
            "final_assistant_row_id" to 11L, "row_ids" to JSONArray(listOf(10L, 11L)))
        val completion = json("admission_id" to "admission-1", "status" to "complete", "persisted_turn" to turn)
        var asked = 0
        val proven = withTimeout(1_500) {
            proveFinishedTurn(identity, "runtime-1", completion, true, "", listOf(user(10, "Q"), assistant(11, "A")),
                lookup = { asked++; delay(60_000); null }, retry = { asked++; delay(60_000); null }, failed = { _, _ -> })
        }
        assertEquals("done_event", proven.step)
        assertEquals("the slow receipt lookup is not even started", 0, asked)
    }

    // ---- 3: a kept streamed reply is hidden only by a saved reply of its own turn

    @Test fun anOlderReplyWithTheSameWordsDoesNotHideTheKeptReplyOfThisTurn() {
        val saved = listOf(Message("1", "user", "First"), Message("2", "assistant", "Done."), Message("3", "user", "Second"))
        val kept = listOf(Message("unconfirmed:a", "assistant", "Done.", displayKind = "unconfirmed_reply"))
        assertEquals(listOf("unconfirmed:a"), savedRepliesOnce(kept, saved).map { it.id })
        val savedNow = saved + Message("4", "assistant", "Done.")
        assertTrue("a saved reply of this turn does hide it", savedRepliesOnce(kept, savedNow).isEmpty())
    }

    // ---- 11: a steer is a user row, the turn goes on past it

    @Test fun theLastResortProofDoesNotStopAtASteerRow() {
        val rows = listOf(user(10, "Question"), assistant(11, "Looking"), user(12, "Change course", "steer"), assistant(13, "The real answer"))
        val receipt = requireNotNull(savedRowsReceipt(TurnIdentity("submission-1", "admission-1"), "10", rows, "runtime-1"))
        val proof = receipt.getJSONObject("_confirmed_persisted_turn")
        assertEquals(13L, proof.getLong("final_assistant_row_id"))
        assertEquals("the steer row is part of the turn", listOf(10L, 11L, 12L, 13L), proof.getJSONArray("row_ids").let { list -> (0 until list.length()).map { list.getLong(it) } })
        assertTrue(replyShownInHistory(receipt, rows))
    }

    @Test fun aLaterPlainUserRowStillEndsTheTurn() {
        val rows = listOf(user(10, "Question"), assistant(11, "Answer"), user(12, "Next question"), assistant(13, "Next answer"))
        assertEquals(11L, requireNotNull(savedRowsReceipt(TurnIdentity("submission-1", "admission-1"), "10", rows, "runtime-1")).getJSONObject("_confirmed_persisted_turn").getLong("final_assistant_row_id"))
    }

    // ---- 4, 5, 6, 8: source guards (the chat's screens read as text)

    private fun source(name: String): String {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        return File(module, "src/main/java/dev/hermes/android/$name").readText()
    }

    @Test fun theApprovalShieldNeverBorrowsTheProfilesModeForAChat() {
        val menu = source("ui/NativeApprovalMenu.kt")
        assertFalse("the profile's mode is not this chat's mode", menu.contains("APPROVALS_MODE_KIND") || menu.contains("remembered"))
    }

    @Test fun noRawServerPathIsFetchedWithoutAnUploadCheckOrAByteCap() {
        val chat = source("ui/ChatScreen.kt")
        assertFalse("a photo is capped well below 12 MiB before it is downloaded", chat.contains("12L * 1024 * 1024"))
        assertFalse(chat.contains("message.attachments.drop(parts.media.size)"))
        assertFalse("Edit and branch starts from the text the owner sees", chat.contains("vm.selectSession(it, message.text,"))
    }

    @Test fun theChatNamesNoReceiptsAndNoImpossibleAdvice() {
        val chat = source("ui/ChatScreen.kt")
        assertFalse(chat.contains("\"Check receipt\""))
        assertFalse("there must be a way to select a smaller section before the app says to", chat.contains("Select a smaller section"))
    }

    // ---- 2 again: the receipt is capped, each ask and both together

    @Test fun aReceiptThatNeverAnswersIsCappedAndTheSavedRowsTakeOver() = runBlocking {
        val identity = TurnIdentity("submission-1", "admission-1")
        val rows = listOf(user(10, "Q"), assistant(11, "A"))
        val failures = mutableListOf<Pair<String, Throwable?>>()
        val proven = withTimeout(3_000) {
            proveFinishedTurn(identity, "runtime-1", null, true, "10", rows, lookup = { delay(60_000); null }, retry = { delay(60_000); null },
                failed = { step, error -> failures += step to error }, askMs = 40, totalMs = 500)
        }
        assertEquals("saved_rows", proven.step)
        assertEquals(listOf("receipt", "retry"), failures.map { it.first })
        assertTrue("a late answer is reported as a timeout", failures.all { it.second is java.net.SocketTimeoutException })
        val capped = withTimeout(3_000) {
            proveFinishedTurn(identity, "runtime-1", null, true, "10", rows, lookup = { delay(60_000); null }, retry = { delay(60_000); null }, failed = { _, _ -> }, askMs = 2_000, totalMs = 100)
        }
        assertEquals("both asks together stop at the overall cap", "saved_rows", capped.step)
    }

    // ---- 5: a photo is fetched only when it is one of Hermes' own uploads

    private val hash = "59375e11918c4ee4afd960c849bf4c76e2f3f5462b01c5d8299f3aed170f431b"

    @Test fun onlyHermesOwnImageUploadsAreFetched() {
        assertTrue(isOwnUpload("${exampleHome}AppData\\Local\\hermes\\cache\\documents\\native-inputs\\$hash\\upload_vwd6w720.png"))
        assertTrue(isOwnUpload("/home/example/.hermes/cache/documents/native-inputs/$hash/upload_a1B2.JPG"))
        assertTrue(isOwnUpload("/home/example/.hermes/cache/documents/native-inputs/$hash/upload_a-b_c.webp"))
        for (path in listOf("C:\\Windows\\System32\\config\\SAM", "/etc/passwd", "/home/example/notes.png",
            "/home/example/.hermes/cache/documents/native-inputs/$hash/upload_a.txt",
            "/home/example/.hermes/cache/documents/native-inputs/$hash/notes.png",
            "/home/example/.hermes/cache/documents/native-inputs/abc/upload_a.png",
            "/home/example/.hermes/cache/documents/native-inputs/../../$hash/upload_a.png",
            "/home/example/.hermes/cache/documents/other/$hash/upload_a.png", ""))
            assertFalse(path, isOwnUpload(path))
    }

    @Test fun aPhotoMarkerStandsForItsOwnTypedAttachmentAndNothingElse() {
        val upload = "/home/example/.hermes/cache/documents/native-inputs/$hash/upload_a1.png"
        val other = "/etc/passwd"
        val typed = listOf(json("name" to "report.pdf", "mime" to "application/pdf"), json("name" to "325.png", "mime" to "image/png"), json("name" to "second.png", "path" to "/x/second.png"))
        val oneUpload = pairMediaWithAttachments(typed, listOf(AttachedMedia(true, upload)))
        assertEquals("the first photo without a path of its own, not the first attachment", 1, oneUpload.single().second)
        assertNull("a marker that is not an upload hides nothing", pairMediaWithAttachments(typed, listOf(AttachedMedia(true, other))).single().second)
        assertNull("a marker with no photo attachment to stand for hides nothing", pairMediaWithAttachments(typed.take(1), listOf(AttachedMedia(true, upload))).single().second)
        val samePath = listOf(json("name" to "a.png", "mime" to "image/png"), json("name" to "b.png", "path" to upload))
        assertEquals("the same path wins over position", 1, pairMediaWithAttachments(samePath, listOf(AttachedMedia(true, upload))).single().second)
        val two = pairMediaWithAttachments(listOf(json("name" to "a.png", "mime" to "image/png"), json("name" to "b.png", "mime" to "image/png")),
            listOf(AttachedMedia(true, upload), AttachedMedia(true, upload.replace("a1", "a2"))))
        assertEquals(listOf(0, 1), two.map { it.second })
    }

    // ---- 6: partial copy is back, behind Beacon's own menu

    @Test fun textCanBeSelectedFromBeaconsMenuForOneBubbleAtATime() {
        val chat = source("ui/ChatScreen.kt")
        assertTrue(chat.contains("uiText(\"Select text\")"))
        assertTrue(chat.contains("message-select:\${message.id}"))
        assertTrue("Beacon's own long-press steps aside while a bubble is being selected", chat.contains("if (selecting) Modifier else Modifier.messageGestures("))
        assertTrue(chat.contains("select-text-done:"))
    }
}
