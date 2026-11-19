package dev.hermes.android.chatqa

import dev.hermes.android.attachmentRecord
import dev.hermes.android.attachmentRecordFitsRow
import dev.hermes.android.legacyPendingAttachmentsKind
import dev.hermes.android.pendingAttachmentsKind
import dev.hermes.android.pendingRecordForRow
import dev.hermes.android.rowSubmissionId
import dev.hermes.android.core.QueuedMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CHATQA-121 follow-up 6: which kept record may be shown on which row. Synthetic data. */
class PendingFilesRecordTest {
    private fun file(name: String) = JSONObject().put("name", name).put("mime", "application/pdf")
    private fun item(text: String, submission: String, vararg names: String) = QueuedMessage(text, names.map(::file), submissionId = submission)
    private fun record(text: String, submission: String?, vararg names: String) =
        JSONObject().put("attachments", JSONArray(names.map(::file))).put("display_text", text).apply { if (submission != null) put("submission_id", submission) }.toString()
    private fun row(submission: String?) = JSONObject().put("row_id", "9101").put("role", "user").apply { if (submission != null) put("display_metadata", JSONObject().put("submission_id", submission)) }

    @Test fun eachSendHasItsOwnKindAndNeverTheChatStreams() {
        assertNotEquals(pendingAttachmentsKind("send-a"), pendingAttachmentsKind("send-b"))
        assertEquals("attachments-pending:sub:send-a", pendingAttachmentsKind("send-a"))
        assertEquals("attachments-pending:chat-a", legacyPendingAttachmentsKind("chat-a"))
    }

    @Test fun theRecordCarriesItsSubmission() {
        val saved = JSONObject(attachmentRecord(JSONArray().put(file("a.pdf")), "words", "send-a"))
        assertEquals("send-a", saved.getString("submission_id"))
        assertEquals("words", saved.getString("display_text"))
    }

    @Test fun aSendsOwnRecordLinksToItsOwnRow() {
        val own = record("words", "send-a", "a.pdf")
        assertEquals(own, pendingRecordForRow(own, null, item("words", "send-a", "a.pdf"), row("send-a")))
        assertEquals("a row that names no submission cannot contradict", own, pendingRecordForRow(own, null, item("words", "send-a", "a.pdf"), row(null)))
    }

    @Test fun aRecordIsNeverLinkedToARowOfAnotherSend() {
        assertNull(pendingRecordForRow(record("words", "send-a", "a.pdf"), null, item("words", "send-a", "a.pdf"), row("send-b")))
        // Nor does one of another send stand in for this one's.
        assertNull(pendingRecordForRow(record("other", "send-z", "z.pdf"), null, item("words", "send-a"), row("send-a")))
    }

    @Test fun a120RecordMovesOnlyWhenItIsExactlyThisSends() {
        val old = record("words", null, "a.pdf")
        assertEquals(old, pendingRecordForRow(null, old, item("words", "send-a", "a.pdf"), row("send-a")))
        assertNull("other words", pendingRecordForRow(null, record("other words", null, "a.pdf"), item("words", "send-a", "a.pdf"), row("send-a")))
        assertNull("other files", pendingRecordForRow(null, record("words", null, "b.pdf"), item("words", "send-a", "a.pdf"), row("send-a")))
        assertNull("a message with no files takes nothing", pendingRecordForRow(null, old, item("words", "send-a"), row("send-a")))
        assertNull("not onto a row of another send", pendingRecordForRow(null, old, item("words", "send-a", "a.pdf"), row("send-b")))
    }

    @Test fun aKeptReceiptFitsOnlyItsOwnRow() {
        assertTrue(attachmentRecordFitsRow(JSONObject(record("w", "send-a")), row("send-a")))
        assertTrue("a 1.2.0 receipt names no send", attachmentRecordFitsRow(JSONObject(record("w", null)), row("send-a")))
        assertTrue("a Hermes row without a stamp", attachmentRecordFitsRow(JSONObject(record("w", "send-a")), row(null)))
        assertTrue(!attachmentRecordFitsRow(JSONObject(record("w", "send-a")), row("send-b")))
    }

    @Test fun theRowsSubmissionIsReadFromItsMetadataObjectOrString() {
        assertEquals("send-a", rowSubmissionId(row("send-a")))
        assertEquals("send-a", rowSubmissionId(JSONObject().put("display_metadata", JSONObject().put("submission_id", "send-a").toString())))
        assertEquals("", rowSubmissionId(row(null)))
    }
}
