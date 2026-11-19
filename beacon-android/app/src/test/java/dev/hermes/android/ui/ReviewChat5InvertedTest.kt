package dev.hermes.android.ui

import dev.hermes.android.data.visibleSearchMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHAT-121 follow-up 6: REVIEW-121B's proof for "Follow-up 2: chat-121-5" (ReviewChat5Test, F2-P3-3 and F2-P3-4), inverted. The one cleaner every path uses
 * to turn a stored user row into the owner's words (splitAttachedMedia) now drops Hermes' footer itself, so "Edit and branch" (which puts its text in the
 * composer) and History search (which looks in it) see the owner's words only. Synthetic text and paths only.
 */
class ReviewChat5InvertedTest {
    /** Hermes' stored user row after a document send: the words, the @file: reference, then its own footer (agent/context_references.py:244-248, 658). */
    private val stored = "Synthetic document check @file:attachments/report.pdf\n\n--- Attached Context ---\n\n" +
        "📎 @file:attachments/report.pdf (PDF document, 1.0 KB) — binary file. It is available on disk at " +
        "`C:\\Users\\example\\.hermes\\attachments\\report.pdf`. Read it with a tool."

    @Test fun editAndBranchNoLongerPutsHermesFooterOrTheHostPathInTheComposer() {
        val bubble = safeHistoryMessagePresentation("user", stored).text
        assertFalse(bubble, bubble.contains("Attached Context") || bubble.contains("available on disk"))
        for (row in listOf(stored, stored.replace("\n", "\r\n"))) {
            val composer = splitAttachedMedia(row).text
            assertFalse(composer, composer.contains("--- Attached Context ---"))
            assertFalse(composer, composer.contains("C:\\Users\\example\\.hermes\\attachments\\report.pdf"))
            assertEquals("Synthetic document check @file:attachments/report.pdf", composer)
        }
        // Only Hermes' own footer, on its own line below the words: the same words inside a line the owner typed stay.
        assertEquals("Notes --- Attached Context --- here", splitAttachedMedia("Notes --- Attached Context --- here").text)
    }

    @Test fun historySearchLooksOnlyAtTheOwnersWords() {
        // NativeOperations.visibleContentHit: a user-row hit stands only when the words the owner sees match (any one word, as Hermes relaxes a query).
        for (hidden in listOf("available on disk", "Attached Context", "example", "binary")) assertFalse(hidden, visibleSearchMatch(splitAttachedMedia(stored).text, hidden))
        assertTrue(visibleSearchMatch(splitAttachedMedia(stored).text, "Synthetic document"))
    }
}
