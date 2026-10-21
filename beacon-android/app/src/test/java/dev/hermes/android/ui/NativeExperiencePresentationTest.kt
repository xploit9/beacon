package dev.hermes.android.ui

import dev.hermes.android.core.copy
import dev.hermes.android.core.json
import dev.hermes.android.updatedHistoryRows
import org.junit.Assert.*
import org.junit.Test

class NativeExperiencePresentationTest {
    @Test fun oversizedClipboardFailsBeforeParsingOrBinderSubmission() {
        assertTrue(runCatching { formattedClipboard("a".repeat(200_001)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ensureClipboardFits("a".repeat(150_000), "b".repeat(100_000)) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun nativeMenuNeverAdmitsRetiredMutations() {
        listOf("Duplicate", "Retry last turn", "Approval mode", "Move to project", "Share snapshot", "Import", "Export").forEach { assertFalse(it, nativeConversationAction(it)) }
        listOf("Rename", "Pin", "Archive", "Branch", "Undo last turn", "Usage").forEach { assertTrue(it, nativeConversationAction(it)) }
    }
    @Test fun sharedColumnsIncludeLongBodyCellsAndRaggedRows() {
        assertEquals(listOf(280, 100), tableColumnWidths(listOf(listOf("Name", "Value"), listOf("Long body ".repeat(50), "1"), listOf("Only one"))))
        assertTrue(tableColumnWidths(emptyList()).isEmpty())
        // A measured amount sets its column's width, so it never wraps mid-number.
        assertEquals(listOf(134), tableColumnWidths(listOf(listOf("Total"), listOf("€484,968.26"))) { it.length * 10 })
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
    @Test fun chatModelAndEffortLabelsPreferConversationValuesThenProfileDefaults() {
        assertEquals("profile-model", chatModelLabel("", "profile-model"))
        assertEquals("conversation-model", chatModelLabel("conversation-model", "profile-model"))
        assertEquals("Model", chatModelLabel("", ""))
        assertEquals("low", chatReasoningLabel("low", "max", "max"))
        assertEquals("max", chatReasoningLabel("", "max", "low"))
        assertEquals("Effort", chatReasoningLabel("", "", ""))
    }
    @Test fun mobileCommandSuggestionsOmitTerminalOnlyCommands() {
        val commands = listOf(
            json("name" to "help", "description" to "Show available commands"),
            json("name" to "redraw", "description" to "Force a full UI repaint (recovers from terminal drift)"),
            json("name" to "debug", "description" to "CLI-only command"),
        )
        assertEquals(listOf("help"), mobileSlashCommands(commands).map { it.getString("name") })
    }
    @Test fun branchResponseFindsNestedSessionIdentityAndMenuLabelsStayCapitalized() {
        assertEquals("branch-2", branchSessionId(json("session" to json("session_id" to "branch-2"))))
        assertEquals("", branchSessionId(json("ok" to true)))
        assertEquals("Archive", conversationMenuTitle("archive"))
    }
    @Test fun profileDisplayNamesFallBackToTheUnchangedProfileId() {
        assertEquals("Personal", profileDisplayName("default", listOf(json("name" to "default", "display_name" to "Personal"))))
        assertEquals("coder", profileDisplayName("coder", listOf(json("name" to "coder", "display_name" to " "))))
        assertEquals("default", profileDisplayName("default", emptyList()))
        assertEquals("Loading profile…", profileDisplayLabel("default", emptyList(), loading = true))
    }
    @Test fun successfulHistoryActionsUpdateOrRemoveTheirRowsImmediately() {
        val row = json("id" to "chat-a", "title" to "Old title", "pinned" to false, "archived" to false)
        assertEquals("New title", updatedHistoryRows(listOf(row), "chat-a", "/api/session/rename", json("title" to "New title"), json(), false, "New", profile = "default").single().getString("title"))
        assertEquals("Old title", updatedHistoryRows(listOf(row.copy().put("profile", "coder")), "chat-a", "/api/session/rename", json("title" to "New title"), json(), false, "New", profile = "default").single().getString("title"))
        assertTrue(updatedHistoryRows(listOf(row), "chat-a", "/api/session/pin", json("pinned" to true), json("pinned" to true), false, "").single().getBoolean("pinned"))
        assertTrue(updatedHistoryRows(listOf(row), "chat-a", "/api/session/archive", json("archived" to true), json("hidden" to true), false, "").isEmpty())
        assertTrue(updatedHistoryRows(listOf(row), "chat-a", "/api/session/delete", json(), json("deleted" to "chat-a"), false, "").isEmpty())
    }
    @Test fun downloadErrorsAreShortAndDoNotExposeServerDetails() {
        assertEquals("This file is larger than the 256 MB download limit.", fileDownloadError(IllegalArgumentException("Download exceeds permitted size")))
        assertEquals("This file is no longer available.", fileDownloadError(dev.hermes.android.data.ApiFailure(404, "private path", plugin = false)))
        assertEquals("Couldn’t download this file. Check your connection and try again.", fileDownloadError(IllegalStateException("private path")))
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
