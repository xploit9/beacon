package dev.hermes.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHAT-121 follow-up 6 (REVIEW-121B F2-P3-3 and F2-P3-4): what "Edit and branch" puts in the composer (ChatScreen's call, editAndBranchDraft) and what find in
 * chat looks in (findableText) for a stored user row: what the bubble shows, never Hermes' footer or a path on the host. Synthetic text and paths only.
 */
class StoredRowShownTextTest {
    private val stored = "Synthetic document check @file:attachments/report.pdf\n\n--- Attached Context ---\n\n" +
        "📎 @file:attachments/report.pdf (PDF document, 1.0 KB) — binary file. It is available on disk at " +
        "`C:\\Users\\example\\.hermes\\attachments\\report.pdf`. Read it with a tool."

    @Test fun editAndBranchKeepsTheWordsAndTheFileReferenceOnly() {
        // The @file: reference the phone added stays: the edited message still carries its file (Hermes reads its kept copy again).
        assertEquals("Synthetic document check @file:attachments/report.pdf", editAndBranchDraft(stored))
        assertEquals("Synthetic document check @file:attachments/report.pdf", editAndBranchDraft(stored.replace("\n", "\r\n")))
        // Hermes' note in front of a photo or document row (with its path) goes too, as before.
        val photo = "[The user sent a document: 'notes.txt'. It is saved at: C:\\Users\\example\\notes.txt]\n\nRead this"
        assertEquals("Read this", editAndBranchDraft(photo))
        assertEquals("Plain words", editAndBranchDraft("Plain words"))
    }

    @Test fun findInChatSearchesOnlyWhatTheBubbleShows() {
        val shown = findableText("user", stored)
        for (hidden in listOf("Attached Context", "available on disk", "example", "C:\\Users", "PDF document", "binary file", ".hermes"))
            assertFalse(hidden, shown.contains(hidden, ignoreCase = true))
        assertTrue(shown.contains("Synthetic document check"))
        assertTrue("the file chip's name is shown", shown.contains("report.pdf"))
        assertFalse("the reference itself is a chip, not words", shown.contains("@file:"))
        // A reply is searched as it is.
        val reply = "A reply that mentions --- Attached Context --- and C:\\Users\\example"
        assertEquals(reply, findableText("assistant", reply))
    }
}
