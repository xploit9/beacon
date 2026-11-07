package dev.hermes.android

import dev.hermes.android.core.Draft
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Test

/** Image sent in a user row must survive a reopen: the row's own receipt restores it. */
class AttachmentReceiptMergeTest {
    private fun image(name: String, path: String, row: String? = null) = json("name" to name, "path" to path,
        "mime" to "image/png").apply { row?.let { put("history_row_id", it) } }
    private fun receipt(vararg attachments: org.json.JSONObject) = json("attachments" to JSONArray(attachments.toList()),
        "display_text" to "[synthetic image]")

    @Test fun anImageOmittedByTheReopenedHistoryReturnsFromItsOwnRowReceipt() {
        val row = json("row_id" to 33L, "role" to "user", "attachments" to JSONArray())
        val merged = mergedRowAttachments(row, receipt(image("photo.png", "local-a", "33")), "scope")
        assertEquals(1, merged.length())
        assertEquals("photo.png", merged.getJSONObject(0).getString("name"))
    }

    @Test fun anAttachmentTaggedForAnotherRowIsNeverMovedIntoThisRow() {
        val row = json("row_id" to 33L, "role" to "user", "attachments" to JSONArray())
        assertEquals(0, mergedRowAttachments(row, receipt(image("photo.png", "local-a", "34")), "scope").length())
        assertEquals(0, mergedRowAttachments(row, receipt(image("photo.png", "local-a", "33").put("scope_key", "other")), "scope").length())
    }

    @Test fun aServerCopyIsNotDuplicatedAndKeepsItsReceiptName() {
        val row = json("row_id" to 33L, "role" to "user",
            "attachments" to JSONArray(listOf(json("path" to "native-a", "name" to "server.png", "mime" to "image/png"))))
        val merged = mergedRowAttachments(row, receipt(image("local-name.png", "native-a", "33")), "scope")
        assertEquals(1, merged.length())
        assertEquals("local-name.png", merged.getJSONObject(0).getString("name"))
        assertEquals("native-a", merged.getJSONObject(0).getString("path"))
    }

    @Test fun nonImageReceiptsStillMergeWithoutAServerCopy() {
        val row = json("row_id" to 33L, "role" to "user", "attachments" to JSONArray())
        val merged = mergedRowAttachments(row, receipt(json("name" to "notes.txt", "path" to "local-b", "mime" to "text/plain")), "scope")
        assertEquals(1, merged.length())
        assertEquals("notes.txt", merged.getJSONObject(0).getString("name"))
    }

    @Test fun everyAcceptanceShapeKeepsTheSameLocalAttachmentReceipt() {
        val native = JSONArray(listOf(json("path" to "native-a", "name" to "server.png", "mime" to "image/png")))
        val draft = Draft(attachments = listOf(image("draft.png", "local-a", "33")))
        assertEquals("native-a", sentAttachmentReceipts(json("attachment_receipts" to native), draft)!!.getJSONObject(0).getString("path"))
        assertEquals("local-a", sentAttachmentReceipts(json("attachment_receipts" to JSONArray()), draft)!!.getJSONObject(0).getString("path"))
        assertEquals("local-a", sentAttachmentReceipts(json(), draft)!!.getJSONObject(0).getString("path"))
        assertNull("no sent attachment is invented", sentAttachmentReceipts(json(), Draft()))
    }
}
