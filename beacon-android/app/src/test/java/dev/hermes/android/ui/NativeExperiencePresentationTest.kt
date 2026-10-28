package dev.hermes.android.ui

import dev.hermes.android.core.copy
import dev.hermes.android.core.json
import dev.hermes.android.updatedHistoryRows
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativeExperiencePresentationTest {
    @Test fun attachmentExamplesFromTheAssistantAndToolsStayVerbatim() {
        val source = "Type @file:notes.md to attach it"
        for (role in listOf("assistant", "tool", "system")) {
            val shown = safeHistoryMessagePresentation(role, source)
            assertEquals(source, shown.text)
            assertTrue(shown.attachments.isEmpty())
        }
    }

    @Test fun inlineUserReferencesNeverConsumeTheRemainingProse() {
        val shown = safeHistoryMessagePresentation("user", "Read @file:notes.md then explain it")
        assertEquals("Read  then explain it", shown.text)
        assertEquals(listOf("notes.md"), shown.attachments.map { it.name })
        // Native Hermes quotes a path that has spaces.
        val whole = safeHistoryMessagePresentation("user", "@file:\"/workspace/plan notes.md\"")
        assertEquals("", whole.text)
        assertEquals(listOf("plan notes.md"), whole.attachments.map { it.name })
        val leading = safeHistoryMessagePresentation("user", "@file:notes.md summarize this")
        assertEquals("summarize this", leading.text)
        assertEquals(listOf("notes.md"), leading.attachments.map { it.name })
        assertEquals(listOf("a.md", "b.md"), historyAttachmentPresentation("@file:a.md @file:b.md").attachments.map { it.name })
    }

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
    @Test fun chatModelAndEffortLabelsPreferConversationValuesThenProfileDefaultsWithoutPlaceholders() {
        assertEquals("profile-model", chatModelLabel("", "profile-model"))
        assertEquals("conversation-model", chatModelLabel("conversation-model", "profile-model"))
        assertEquals("", chatModelLabel("", ""))
        assertEquals("low", chatReasoningLabel("low", "max", "max"))
        assertEquals("max", chatReasoningLabel("", "max", "low"))
        assertEquals("", chatReasoningLabel("", "", ""))
    }
    @Test fun historyAttachmentReferencesBecomeFilenameChipsWithoutPaths() {
        val content = historyAttachmentPresentation(
            "Look at these\n" +
                "@image:C:\\SyntheticOwner\\Documents\\.beacon-attachments\\123e4567-e89b-12d3-a456-426614174000-sunrise.jpg\n" +
                "@file:'C:\\SyntheticOwner\\Documents\\.beacon-attachments\\123e4567-e89b-12d3-a456-426614174000-plan notes.md'\n" +
                "Thanks"
        )
        assertEquals("Look at these\n\nThanks", content.text)
        assertEquals(listOf("sunrise.jpg", "plan notes.md"), content.attachments.map { it.name })
        assertEquals(listOf("image", "file"), content.attachments.map { it.kind })
        assertFalse(content.text.contains("C:\\SyntheticOwner"))
        assertFalse(safeHistoryMessageText("user", "@file:C:\\SyntheticOwner\\Documents\\.beacon-attachments\\123e4567-e89b-12d3-a456-426614174000-plan.md").contains("C:\\SyntheticOwner"))
    }
    @Test fun stoppedAssistantErrorsUseAQuietMarkerAndKeepOtherMessages() {
        assertEquals("Stopped", stoppedMessageMarker("assistant", "Operation interrupted: waiting for model response (6.8s elapsed).", false))
        assertEquals("Stopped", stoppedMessageMarker("assistant", "Partial answer", true))
        assertNull(stoppedMessageMarker("user", "Operation interrupted: private detail", false))
        assertNull(stoppedMessageMarker("assistant", "The task is complete.", false))
        assertEquals("", safeHistoryMessageText("assistant", "Operation interrupted: private server detail"))
    }
    @Test fun imagePasteAcceptsImageMimeTypesOnly() {
        assertTrue(imageMimeType("image/png"))
        assertTrue(imageMimeType("IMAGE/JPEG"))
        assertFalse(imageMimeType("text/plain"))
        assertFalse(imageMimeType("application/image"))
    }
    @Test fun backgroundActivitySummaryShowsCategoriesAndStaysEmptyWhenIdle() {
        val activity = BackgroundActivitySummary(agents = 2, processes = 1, cronJobs = 1, watches = 1)
        assertTrue(activity.active)
        assertEquals("2 agents · 1 process · 1 schedule running · 1 watch", backgroundActivityStatus(activity))
        assertFalse(BackgroundActivitySummary().active)
        assertEquals("", backgroundActivityStatus(BackgroundActivitySummary()))
        assertEquals("Background status unavailable", backgroundActivityStatus(BackgroundActivitySummary(available = false)))
        assertEquals("1 process · More may be active", backgroundActivityStatus(BackgroundActivitySummary(processes = 1, available = false)))
    }
    @Test fun activeSubagentCountIntersectsTheSessionRosterWithProfileStatus() {
        val session = json("subagents" to JSONArray()
            .put(json("subagent_id" to "sa-current"))
            .put(json("subagent_id" to "sa-stale")))
        val profile = json("active" to JSONArray()
            .put(json("subagent_id" to "sa-current"))
            .put(json("subagent_id" to "sa-other-session")))
        assertEquals(1, sessionScopedSubagentCount(session, profile))
        assertEquals(2, sessionScopedSubagentCount(session, null))
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
