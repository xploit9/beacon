package dev.hermes.android.data

import dev.hermes.android.Message
import dev.hermes.android.core.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativeExperienceDataTest {
    @Test fun attachmentNamesCannotSplitTheNativeImagePathArgument() {
        listOf("", " ", ".", "..", "../image.png", "folder\\image.png", "image\" other.png", "image\n.png", "image\u0000.png", "image\u007f.png", "\$HOME.png", "%USERPROFILE%.png").forEach {
            assertFalse(it, validUploadName(it))
        }
        listOf("An image.png", "Owner's image.png", "صورة.png", "figure (2).webp", "literal`name!.png").forEach {
            assertTrue(it, validUploadName(it))
        }
    }
    @Test fun nativeTextAndDurableRowsSurviveReloadAndInsertion() {
        val literal = "Unicode \u0645\u0631\u062d\u0628\u0627 <memory-context>literal</memory-context> [Workspace::v1]"
        val row = json("role" to "user", "text" to literal, "row_id" to 42)
        assertEquals(literal, Message.from(row, 0).text)
        assertEquals("42", Message.from(row, 0).id)
        assertEquals(Message.from(row, 0).id, Message.from(row, 99).id)
        assertNotEquals(Message.from(row, 0).id, Message.from(row.copy().put("row_id", 43), 0).id)
        assertEquals("", Message.from(row.copy().put("display_text", ""), 0).text)
        assertEquals("tool-1", Message.from(json("role" to "tool", "text" to "result", "tool_call_id" to "tool-1"), 0).id)
    }
    @Test fun attachmentSyntaxDoesNotInventAnUploadReceipt() {
        val row = Message.from(json("role" to "user", "text" to "@image:literal.png"), 0)
        assertEquals("@image:literal.png", row.text)
        assertTrue(row.attachments.isEmpty())
    }
    @Test fun fastApiDetailsAreActionable() {
        assertEquals("Choose a valid timezone", nativeErrorMessage(json("detail" to "Choose a valid timezone"), 422))
        val validation = JSONArray().put(json("msg" to "Invalid time")).put(json("msg" to "Missing title"))
        assertEquals("Invalid time; Missing title", nativeErrorMessage(json("detail" to validation), 422))
        assertEquals("Server returned 500", nativeErrorMessage(json(), 500))
    }
}
