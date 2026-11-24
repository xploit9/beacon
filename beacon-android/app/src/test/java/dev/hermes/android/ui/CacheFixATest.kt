package dev.hermes.android.ui

import dev.hermes.android.data.EarlyRead
import dev.hermes.android.data.KeptProfileData
import dev.hermes.android.data.earlyReadShows
import dev.hermes.android.data.savedChatIds
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CACHEFIX-A: saved data shows at once on a cold start and offline, and sign-out drops every kept row. */
class CacheFixATest {
    private fun answer(vararg pairs: Pair<String, Any>) = JSONObject().also { o -> pairs.forEach { o.put(it.first, it.second) } }

    // P1-4: the full "Reconnect to Hermes" page stays only where nothing is saved.
    @Test fun aPageWithASavedAnswerKeepsItOffline() {
        val saved = mapOf("crons" to answer("jobs" to 1), "files" to answer("path" to "/"), "kanban:boards" to answer("n" to 1), "settings:skills" to answer("n" to 1),
            "vault" to answer("n" to 1), "settings:bots" to answer("bots" to 1), "picker:models" to answer("n" to 1), "native-tools" to answer("n" to 1),
            "slash:catalog" to answer("n" to 1), "native-projects" to answer("n" to 1), "history-retention:abc" to answer("days" to 7), "settings:memory" to answer("n" to 1))
        listOf("Tasks", "Spaces", "Kanban", "Skills", "Vault", "Profiles", "Bots", "Providers & models", "Tools & commands", "Commands", "Projects", "Notifications", "Memory")
            .forEach { assertTrue("$it keeps its saved copy", savedPageHasCopy(it, saved)) }
    }
    @Test fun aPageWithNothingSavedKeepsTheReconnectPage() {
        val saved = mapOf("crons" to answer("jobs" to 1))
        listOf("MCP servers", "Health", "Logs", "Server updates", "Session checkpoints", "Session usage").forEach { assertFalse("$it has no copy", savedPageHasCopy(it, saved)) }
        assertFalse(savedPageHasCopy("Skills", saved))
        assertFalse(savedPageHasCopy("Projects", emptyMap()))
    }
    @Test fun redactedMcpAndServerSummariesCanOpenOfflineEvenAfterAnEmptyAnswer() {
        mapOf("MCP servers" to "native-mcp", "Health" to "settings:health", "Logs" to "settings:logs:errors", "Server updates" to "settings:update-check",
            "Session checkpoints" to "settings:backups").forEach { (page, key) ->
            assertTrue(page, savedPageHasCopy(page, mapOf(key to JSONObject())))
            assertFalse(page, savedPageHasCopy(page, mapOf("unrelated" to JSONObject())))
        }
    }

    @Test fun callHistoryDiskPageContainsOnlyFieldsRenderedByTheList() {
        val row = answer("id" to "call-one", "conversation_id" to "one", "started_at" to 12L, "reason" to "Test call",
            "transcript" to "Private detail", "payload" to answer("data" to "Other detail"))
        val page = callHistoryPage(answer("calls" to org.json.JSONArray().put(row).put(answer("reason" to "invalid row")), "next" to "older"))
        val calls = page.getJSONArray("calls")
        assertEquals(1, calls.length())
        assertEquals("Test call", CallHistoryEntry.from(calls.getJSONObject(0))?.reason)
        assertFalse(calls.getJSONObject(0).has("transcript"))
        assertFalse(calls.getJSONObject(0).has("payload"))
        assertEquals("older", page.getString("next"))
    }
    @Test fun anErrorOrALoadingMarkIsNotASavedCopy() {
        assertFalse(savedPageHasCopy("Tasks", mapOf("crons" to answer("_error" to "Reconnect to refresh this page."))))
        assertFalse(savedPageHasCopy("Tasks", mapOf("crons" to answer("_loading" to true))))
        assertTrue(savedPageHasCopy("Tasks", mapOf("crons" to answer("_stale" to true, "jobs" to 2))))
    }

    // P3-6: a normal cold start reconnects quietly; the banner speaks after a failed read, during a Reconnect tap, or when Hermes stopped answering.
    @Test fun theBannerStaysQuietOnANormalColdStart() {
        assertFalse(offlineBannerShown(connected = false, loading = true, cantReach = false, failedToConnect = false))
        assertFalse(offlineBannerShown(connected = true, loading = false, cantReach = false, failedToConnect = false))
    }
    @Test fun theBannerSpeaksOnceAReadFailedAndWhileAReconnectRuns() {
        assertTrue(offlineBannerShown(connected = false, loading = false, cantReach = false, failedToConnect = true))
        assertTrue("a Reconnect tap shows Reconnecting…", offlineBannerShown(connected = false, loading = true, cantReach = false, failedToConnect = true))
        assertTrue(offlineBannerShown(connected = true, loading = false, cantReach = true, failedToConnect = false))
    }

    // P1-1: what a read asked for before the client exists shows.
    @Test fun anEarlyReadShowsItsSavedCopyNotAnError() {
        assertEquals(EarlyRead.SavedCopy, earlyReadShows(restoredOwner = true, hasSavedCopy = true, connecting = true))
        assertEquals(EarlyRead.SavedCopy, earlyReadShows(restoredOwner = true, hasSavedCopy = true, connecting = false))
        assertEquals(EarlyRead.Waiting, earlyReadShows(restoredOwner = true, hasSavedCopy = false, connecting = true))
        assertEquals(EarlyRead.Reconnect, earlyReadShows(restoredOwner = true, hasSavedCopy = false, connecting = false))
        assertEquals(EarlyRead.Reconnect, earlyReadShows(restoredOwner = false, hasSavedCopy = true, connecting = true))
    }

    // P2-7: a hop out of a profile and back keeps its screens; a profile never gets another's.
    @Test fun aHopBackToAProfileShowsItsOwnScreensMarkedStale() {
        val kept = KeptProfileData()
        val assistant = mapOf("native-mcp" to answer("servers" to 2, "_cached_at" to 5L), "crons" to answer("jobs" to 1))
        assertTrue("the first visit to a profile has nothing kept", kept.swap("default", "coder", assistant).isEmpty())
        val back = kept.swap("coder", "default", mapOf("crons" to answer("jobs" to 9)))
        assertEquals(setOf("native-mcp", "crons"), back.keys)
        assertTrue(back.getValue("native-mcp").optBoolean("_stale"))
        assertEquals(5L, back.getValue("native-mcp").getLong("_cached_at"))
        assertEquals(1, back.getValue("crons").getInt("jobs"))
        // The coder profile's own screens come back only to the coder profile.
        assertEquals(9, kept.swap("default", "coder", back).getValue("crons").getInt("jobs"))
    }
    @Test fun keptScreensNeverCarryAnErrorOrALoadingMarkAndNeverLeakToAnotherProfile() {
        val kept = KeptProfileData()
        kept.swap("default", "coder", mapOf("a" to answer("_error" to "x"), "b" to answer("_loading" to true), "c" to answer("ok" to 1)))
        val third = kept.swap("coder", "other", emptyMap())
        assertTrue("another profile gets nothing of default's", third.isEmpty())
        assertEquals(setOf("c"), kept.swap("other", "default", emptyMap()).keys)
        assertNull(kept.swap("default", "x", emptyMap())["c"])
    }
    @Test fun keptScreensAreBoundedAndClearedAtSignOut() {
        val kept = KeptProfileData(limit = 2)
        listOf("p1", "p2", "p3").forEach { kept.swap(it, "elsewhere", mapOf("k" to answer("ok" to 1))) }
        assertEquals(2, kept.size)
        assertTrue("the oldest profile fell out", kept.swap("elsewhere", "p1", emptyMap()).isEmpty())
        kept.clear()
        assertEquals(0, kept.size)
        assertTrue(kept.swap("elsewhere", "p3", emptyMap()).isEmpty())
    }

    // P2-6: sign-out names every saved chat of a profile: the listed ones and the pointers.
    @Test fun signOutNamesTheListedChatsAndThePointers() {
        val index = """{"sessions":[{"session_id":"chat-a"},{"id":"chat-b"},{"session_id":""}]}"""
        assertEquals(setOf("chat-a", "chat-b", "beacon-1", "open-1", "shell-1"), savedChatIds(index, listOf("beacon-1", null, "open-1", "", "shell-1", "chat-a")))
        assertEquals(setOf("only-pointer"), savedChatIds(null, listOf("only-pointer")))
        assertEquals("a damaged list names nothing and does not stop the pointers", setOf("p"), savedChatIds("{not json", listOf("p")))
    }

    // P3-5: the call list leaves memory at sign-out.
    @Test fun theCallListLeavesMemoryAtSignOut() {
        val call = CallHistoryEntry("c1", "in", "ended", 1L, 5L, null, "chat", null, null)
        CallHistoryMemory.remember("account-a", listOf(call), "next")
        assertEquals(listOf(call), CallHistoryMemory.calls("account-a"))
        CallHistoryMemory.clear()
        assertTrue(CallHistoryMemory.calls("account-a").isEmpty())
        assertNull(CallHistoryMemory.next("account-a"))
    }
}
