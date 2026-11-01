package dev.hermes.android.uiux.chat

import dev.hermes.android.PREVIEW_PREFETCH_ROWS
import dev.hermes.android.previewPrefetchTargets
import dev.hermes.android.ui.ChatGlance
import dev.hermes.android.ui.ChatGlances
import dev.hermes.android.ui.ChatStrings
import dev.hermes.android.ui.chatPreviewLine
import dev.hermes.android.ui.conversationMenuGroups
import dev.hermes.android.ui.findRanges
import dev.hermes.android.ui.friendlyToolNames
import dev.hermes.android.ui.historySourceFilterShown
import dev.hermes.android.ui.savedConversation
import dev.hermes.android.ui.toolCardName
import dev.hermes.android.ui.toolDetails
import dev.hermes.android.ui.toolDisplayName
import dev.hermes.android.ui.toolFieldLabel
import dev.hermes.android.ui.toolFields
import dev.hermes.android.ui.toolStepsSummary
import dev.hermes.android.ui.transcriptStatusShown
import dev.hermes.android.withPrefetchedPreview
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lane chat (uiux-20261031): find, history extras, the chat menu, tool cards and chat-list previews. */
class ChatLaneTest {
    private fun tool(name: String, args: Any? = null) = JSONObject().put("name", name).apply { args?.let { put("arguments", it) } }

    @Test fun findMarksEveryMatchIgnoringCaseWithoutOverlap() {
        assertEquals(listOf(0..3, 11..14), findRanges("Fact and a fact", "FACT"))
        assertEquals(listOf(0..1, 2..3), findRanges("aaaa", "aa"))
        assertEquals(emptyList<IntRange>(), findRanges("anything", "  "))
        assertEquals(emptyList<IntRange>(), findRanges("short", "longer than the text"))
    }

    @Test fun theSourceFilterOnlyOffersAChoice() {
        assertFalse("One source is no choice", historySourceFilterShown(listOf("android"), emptySet()))
        assertTrue(historySourceFilterShown(listOf("android", "telegram"), emptySet()))
        assertTrue("A hidden source stays listed so it can be shown again", historySourceFilterShown(listOf("telegram"), setOf("telegram")))
        assertFalse(historySourceFilterShown(emptyList(), emptySet()))
    }

    @Test fun aSavedChatWithNothingVisibleIsNotANewChat() {
        assertFalse(savedConversation(loadedRows = 0, recordedCount = 0, listedCount = 0))
        assertTrue("Hidden steering or folded tool rows were loaded", savedConversation(3, 0, 0))
        assertTrue("Hermes counted messages", savedConversation(0, 4, 0))
        assertTrue("History lists messages for it", savedConversation(0, 0, 2))
    }

    @Test fun offlineTheBannerAloneSaysTheChatIsSaved() {
        assertFalse(transcriptStatusShown("Cached · reconnect to synchronize", streaming = false, connected = false))
        assertFalse(transcriptStatusShown("Cached · reconnecting", streaming = false, connected = false))
        assertTrue("Online, a failed refresh still shows", transcriptStatusShown("Cached · refresh failed", streaming = false, connected = true))
        assertTrue(transcriptStatusShown("Could not load conversation · retry", streaming = false, connected = false))
        assertFalse("A running turn speaks in its live row", transcriptStatusShown("Sending", streaming = true, connected = true))
        assertFalse(transcriptStatusShown("", streaming = false, connected = true))
    }

    @Test fun deleteSitsApartAtTheEndOfTheChatMenu() {
        val current = conversationMenuGroups(listOf(listOf("New conversation", "Rename", "Pin", "Archive", "Branch"), listOf("Delete conversation")))
        assertEquals(listOf(listOf("New conversation", "Rename", "Pin", "Archive", "Branch")), current.daily)
        assertEquals(listOf("Delete conversation"), current.destructive)
        // Wherever a list puts Delete, it is drawn last and apart; an emptied group draws nothing.
        val mixed = conversationMenuGroups(listOf(listOf("Rename", "Delete conversation"), listOf("Pin")), dropNewConversation = true)
        assertEquals(listOf(listOf("Rename"), listOf("Pin")), mixed.daily)
        assertEquals(listOf("Delete conversation"), mixed.destructive)
        assertEquals(listOf(listOf("Rename")), conversationMenuGroups(listOf(listOf("New conversation", "Rename")), dropNewConversation = true).daily)
    }

    @Test fun aTerminalCardShowsItsCommandAndExitCodeNotItsJson() {
        val args = JSONObject().put("command", "sleep 15").put("background", false).put("timeout", 180).put("persist_on_release", false)
        val shown = toolDetails("terminal", args, """{"output": "", "exit_code": 0, "error": null}""")
        assertEquals("sleep 15", shown.command)
        assertEquals(0, shown.exitCode)
        assertNull("Nothing was printed", shown.output)
        assertNull(shown.error)
        val failed = toolDetails("terminal", args.toString(), """{"output": "partial", "exit_code": 2, "error": "No such file"}""")
        assertEquals(2, failed.exitCode)
        assertEquals("partial", failed.output)
        assertEquals("No such file", failed.error)
        assertEquals("Plain-text output stays as it is", "ok\n", toolDetails("bash", JSONObject().put("command", "echo ok"), "ok\n").output)
    }

    @Test fun aWebSearchCardShowsTheQueryAndHowManyCameBack() {
        val web = JSONArray().put(JSONObject().put("title", "Octopus facts").put("url", "https://example.com/a")).put(JSONObject().put("url", "https://example.com/b"))
        val shown = toolDetails("web_search", JSONObject().put("query", "octopus hearts"), JSONObject().put("success", true).put("data", JSONObject().put("web", web)).toString())
        assertEquals("octopus hearts", shown.query)
        assertEquals(2, shown.resultCount)
        assertEquals(listOf("Octopus facts" to "https://example.com/a", "https://example.com/b" to "https://example.com/b"), shown.hits)
        assertNull("An unreadable result has no count", toolDetails("web_search", JSONObject().put("query", "x"), "plain text").resultCount)
    }

    @Test fun otherJsonReadsAsLabelledRowsWithoutTheEmptyOnes() {
        val shown = toolDetails("read_file", """{"path": "notes.md", "offset": 0, "follow": false, "note": null, "tags": ["a", "b"], "extra": {}}""", """{"content": "Hello"}""")
        assertEquals(listOf("Path" to "notes.md", "Offset" to "0", "Tags" to "a, b").sortedBy { it.first }, shown.arguments!!.sortedBy { it.first })
        assertEquals(listOf("Content" to "Hello"), shown.result)
        assertNull("Plain-text arguments keep their own block", toolDetails("sync_notes", "target=notes.md", null).arguments)
        assertEquals("Persist on release", toolFieldLabel("persist_on_release"))
        assertEquals("Max results", toolFieldLabel("maxResults"))
        assertEquals(emptyList<Pair<String, String>>(), toolFields(JSONObject().put("off", false).put("none", JSONObject.NULL)))
    }

    @Test fun toolsWithBadIdsGetPlainNamesInTheChatLanguage() {
        assertEquals("Call you", toolCardName(tool("beacon_call_user")))
        assertEquals("Ti chiama", toolCardName(tool("beacon_call_user"), translate = { if (it == "Call you") "Ti chiama" else it }))
        assertEquals("Hermes' meta-tool names the tool it ran", "Call you", toolCardName(tool("tool_call", JSONObject().put("name", "beacon_call_user"))))
        assertEquals("Web search", toolCardName(tool("web_search")))
        assertEquals("Search inbox · Personal e altri 1", toolDisplayName(tool("tool_call", JSONObject().put("calls", JSONArray()
            .put(JSONObject().put("name", "mcp__personal__search_inbox")).put(JSONObject().put("name", "mcp__personal__list_tasks")))), andMore = "e altri {0}"))
        assertEquals("1 step · Call you", toolStepsSummary(listOf(tool("beacon_call_user"))))
        assertTrue("Every plain name is checked for translations", ChatStrings.containsAll(friendlyToolNames.values))
    }

    private val profile = "personal"
    private fun row(id: String, lastActiveSeconds: Long = 1_790_000_000L, count: Int = 3, rowProfile: String = profile) =
        JSONObject().put("session_id", id).put("profile", rowProfile).put("last_active", lastActiveSeconds).put("message_count", count)

    @Test fun previewsAreReadOnlyForTopChatsWithAMissingOrOlderLine() {
        val activity = 1_790_000_000_000L
        val glances = ChatGlances(account = "a", rows = mapOf(
            "$profile:fresh" to ChatGlance(preview = "Newest line", role = "assistant", at = activity),
            "$profile:stale" to ChatGlance(seen = activity - 60_000, preview = "Old line", role = "assistant", at = activity - 60_000),
        ))
        val rows = listOf(row("fresh"), row("stale"), row("never"), row("empty-thread", count = 0), row("other", rowProfile = "work"), row("read"))
        val wanted = previewPrefetchTargets(rows, glances, profile) { id -> if (id == "read") activity else null }.map { it.getString("session_id") }
        assertEquals(listOf("stale", "never"), wanted)
        val many = (1..30).map { row("chat-$it") }
        assertEquals(PREVIEW_PREFETCH_ROWS, previewPrefetchTargets(many, ChatGlances(), profile) { null }.size)
        assertEquals("An id the history route rejects is never read", emptyList<JSONObject>(), previewPrefetchTargets(listOf(row("../x")), ChatGlances(), profile) { null })
    }

    @Test fun aReadPreviewFillsTheListLineButNeverTheReadMark() {
        val seen = 1_790_000_000_000L
        val glances = ChatGlances(account = "a", rows = mapOf("$profile:chat" to ChatGlance(seen = seen, preview = "Old", role = "assistant", at = seen - 10_000)))
        val after = withPrefetchedPreview(glances, profile, "chat", "Emperor penguin dads keep their eggs warm", "assistant", seen + 5_000)
        assertEquals(ChatGlance(seen = seen, preview = "Emperor penguin dads keep their eggs warm", role = "assistant", at = seen + 5_000), after.rows["$profile:chat"])
        assertSame("A blank line changes nothing", glances, withPrefetchedPreview(glances, profile, "chat", "", "assistant", seen + 5_000))
        assertSame("An older line never replaces a newer one", after, withPrefetchedPreview(after, profile, "chat", "Older", "user", seen))
        val mine = withPrefetchedPreview(ChatGlances(account = "a"), profile, "new", "Run the tests", "user", seen)
        assertEquals(0L, mine.rows["$profile:new"]!!.seen)
        assertEquals("Own messages read as You, as when the chat was opened here", "You: Run the tests", chatPreviewLine(row("new"), mine.rows["$profile:new"]))
    }
}
