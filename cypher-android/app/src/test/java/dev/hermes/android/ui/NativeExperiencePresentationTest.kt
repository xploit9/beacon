package dev.hermes.android.ui

import org.junit.Assert.*
import org.junit.Test

class NativeExperiencePresentationTest {
    @Test fun oversizedClipboardFailsBeforeParsingOrBinderSubmission() {
        assertTrue(runCatching { formattedClipboard("a".repeat(200_001)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ensureClipboardFits("a".repeat(150_000), "b".repeat(100_000)) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun nativeMenuNeverAdmitsRetiredMutations() {
        listOf("Pin", "Duplicate", "Retry last turn", "Approval mode", "Move to project", "Share snapshot", "Import", "Export").forEach { assertFalse(it, nativeConversationAction(it)) }
        listOf("Rename", "Archive", "Branch", "Undo last turn", "Usage").forEach { assertTrue(it, nativeConversationAction(it)) }
    }
    @Test fun sharedColumnsIncludeLongBodyCellsAndRaggedRows() {
        assertEquals(listOf(280, 100), tableColumnWidths(listOf(listOf("Name", "Value"), listOf("Long body ".repeat(50), "1"), listOf("Only one"))))
        assertTrue(tableColumnWidths(emptyList()).isEmpty())
    }
    @Test fun slashDiscoveryFiltersWithoutTreatingArgumentsAsCommands() {
        assertTrue(slashMatches("/help", "Commands", "/he"))
        assertFalse(slashMatches("/save", "Export the current conversation", "/he"))
        assertTrue(slashMatches("/lookup", "Search mailbox messages", "/mail"))
        assertFalse(slashMatches("/help", "Commands", "/help argument"))
        assertFalse(slashMatches("/help", "Commands", "literal /help"))
        assertNull(nativeSlashName("/path/to/file", listOf("help")))
        assertEquals("help", nativeSlashName("/help argument", listOf("help")))
    }
    @Test fun endedUnfinishedCallNeverClaimsLiveOrSaved() {
        assertEquals("Unfinished call transcript", archivedCallLabel(false, false, false))
        assertEquals("Live call transcript", archivedCallLabel(false, false, true))
        assertEquals("Interrupted call transcript", archivedCallLabel(true, true, false))
    }
    @Test fun formattedCopyEscapesHtmlAndUnsafeLinksPreservingSourceSeparately() {
        val source = "<script>alert(1)</script>\n\n[click](javascript:alert(1))\n\n| A | B |\n|---|---|\n| 1 | 2 |"
        val (_, html) = formattedClipboard(source)
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("href=\"javascript:"))
        assertTrue(html.contains("<table>"))
        assertTrue(source.startsWith("<script>"))
        assertFalse(formattedClipboard("literal a | b").second.contains("<table>"))
    }
}
