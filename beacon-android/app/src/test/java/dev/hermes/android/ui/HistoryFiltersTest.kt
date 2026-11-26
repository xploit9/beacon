package dev.hermes.android.ui

import dev.hermes.android.core.json
import dev.hermes.android.data.BEACON_THREAD_SOURCE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryFiltersTest {
    private val rows = listOf(
        json("id" to "a", "title" to "beta", "source" to "android"),
        json("id" to "b", "title" to "Alpha", "source" to "telegram", "pinned" to true),
        json("id" to "c", "title" to "gamma", "source" to "android_thread"),
        json("id" to "d", "title" to "Delta", "source" to "phone"),
        json("id" to "e", "title" to "Echo", "source" to "webui"),
    )

    @Test fun threadsAreBeaconAndRelayThreadsOnly() {
        assertEquals(listOf("c", "d"), historyRows(rows, HistoryView.Threads, HistoryOrder.Recent).map { it.getString("id") })
        assertTrue(isThreadSource(" Android_Thread "))
        assertFalse(isThreadSource("android"))
        assertFalse(isThreadSource(null))
    }

    @Test fun loadedTitlesFilterImmediatelyAndEmptyServerMatchesNeverRestoreUnfilteredRows() {
        assertEquals(listOf("b"), historySearchRows(rows, "ALP").map { it.getString("id") })
        assertTrue(historySearchRows(rows, "no such title", emptyList()).isEmpty())
        assertEquals(rows, historySearchRows(rows, ""))
    }

    @Test fun matchingServerRowsSupplementLocalTitlesWithoutDuplicatingProfileIdentity() {
        val local = json("id" to "shared", "title" to "Trip", "profile" to "default")
        val otherProfile = json("id" to "shared", "title" to "Server match", "profile" to "coder")
        assertEquals(listOf(local, otherProfile), historySearchRows(listOf(local), "Trip", listOf(local, otherProfile), "default"))
    }

    @Test fun pinnedViewAndTitleOrderKeepPinnedFirst() {
        assertEquals(listOf("b"), historyRows(rows, HistoryView.Pinned, HistoryOrder.Recent).map { it.getString("id") })
        // Pinned chats stay on top in every order (Telegram); the rest keep the server order for recent/created.
        assertEquals(listOf("b", "a", "c", "d", "e"), historyRows(rows, HistoryView.All, HistoryOrder.Created).map { it.getString("id") })
        assertEquals(listOf("b", "a", "c", "d", "e"), historyRows(rows, HistoryView.All, HistoryOrder.Recent).map { it.getString("id") })
        assertEquals(listOf("b", "a", "d", "e", "c"), historyRows(rows, HistoryView.All, HistoryOrder.Title).map { it.getString("id") })
        assertEquals(HistoryOrder.Recent, HistoryOrder.of("unknown"))
    }

    @Test fun aLegacyNumericPinCountsForThePinnedViewAndSort() {
        val legacy = json("id" to "legacy", "title" to "Legacy", "pinned" to 1)
        assertEquals(listOf("legacy"), historyRows(listOf(legacy), HistoryView.Pinned, HistoryOrder.Recent).map { it.getString("id") })
        assertEquals(listOf("legacy", "a"), historyRows(listOf(rows.first(), legacy), HistoryView.All, HistoryOrder.Recent).map { it.getString("id") })
    }

    @Test fun assistantConversationIsHiddenAndAllProfileRowsHaveDistinctKeys() {
        val assistant = json("id" to "assistant", "profile" to "coder", "source" to "cli", "pinned" to true)
        val other = json("id" to "chat", "profile" to "coder", "source" to "android", "pinned" to true)
        val shown = historyRows(
            listOf(assistant, other), HistoryView.Pinned, HistoryOrder.Recent,
            currentProfile = "default", assistantProfile = "coder", assistantSessionId = "assistant",
        )
        assertEquals(listOf("chat"), shown.map { it.getString("id") })
        assertEquals("default:shared", historyRowKey(json("id" to "shared", "profile" to "default")))
        assertEquals("coder:shared", historyRowKey(json("id" to "shared", "profile" to "coder")))
        // A cached row without a profile keeps its key once a refresh adds the current profile.
        assertEquals(historyRowKey(json("id" to "shared", "profile" to "default"), "default"), historyRowKey(json("id" to "shared"), "default"))
    }

    @Test fun assistantConversationIsHiddenEvenWhenItsProfileIdDiffersOnlyByCaseOrIsMissing() {
        val rows = listOf(
            json("id" to "assistant", "profile" to "Coder "),
            json("session_id" to "assistant", "profile" to ""),
            json("id" to "chat", "profile" to "coder"),
        )
        val shown = historyRows(rows, HistoryView.All, HistoryOrder.Recent, currentProfile = "coder", assistantProfile = "coder", assistantSessionId = "assistant")
        assertEquals(listOf("chat"), shown.map { it.getString("id") })
    }

    @Test fun cronPostPreviewsDropTheLabelWhetherOrNotTheLineBreakSurvived() {
        assertEquals("Patch PHP urgently", chatPreviewText("[Cron delivery: Morning security brief] - Patch PHP urgently"))
        assertEquals("Patch PHP urgently", chatPreviewText("[Cron delivery: Morning security brief]\n- Patch PHP urgently"))
    }

    @Test fun sourceFilterOffersListedAndHiddenSourcesButNotThreadsOrCron() {
        val offered = historySources(rows + json("id" to "f", "source" to "cron"), hidden = setOf("discord"))
        assertEquals(listOf("android", "discord", "telegram", "webui"), offered)
        assertEquals("Beacon", historySourceLabel("android"))
        assertEquals("Beacon threads", historySourceLabel(BEACON_THREAD_SOURCE))
        assertEquals("Web", historySourceLabel("webui"))
        assertEquals("Api server", historySourceLabel("api server"))
        assertEquals("Matrix", historySourceLabel("matrix"))
    }

    @Test fun repeatedThreadNamesGetANumberBecauseHermesRejectsDuplicateTitles() {
        assertEquals("Trip planning", uniqueThreadTitle("  Trip   planning ", listOf("Other")))
        assertEquals("Trip planning 2", uniqueThreadTitle("Trip planning", listOf("trip PLANNING")))
        assertEquals("Trip planning 3", uniqueThreadTitle("Trip planning", listOf("Trip planning", "Trip planning 2")))
        assertEquals(100, uniqueThreadTitle("x".repeat(300), emptyList()).length)
        assertEquals("", uniqueThreadTitle("   ", emptyList()))
    }

    // Hermes lists a chat it compressed under its newest session's id; the Beacon chat is saved under its first, so it must still be recognised.
    @Test fun theBeaconChatNeverShowsAsASecondChatAfterHermesMovedItToANewSession() {
        val moved = json("id" to "beacon-chat-3", "_lineage_root_id" to "beacon-chat", "_lineage_ids" to org.json.JSONArray(listOf("beacon-chat", "beacon-chat-2", "beacon-chat-3")), "title" to "hi", "profile" to "default")
        val midway = json("id" to "beacon-chat-2", "_lineage_ids" to org.json.JSONArray(listOf("beacon-chat", "beacon-chat-2")), "profile" to "default")
        val other = json("id" to "chat-a", "_lineage_root_id" to "chat-a", "_lineage_ids" to org.json.JSONArray(listOf("chat-a", "chat-a-2")), "title" to "Weekend", "profile" to "default")
        val shown = historyRows(listOf(moved, midway, other), HistoryView.All, HistoryOrder.Recent, currentProfile = "default", assistantProfile = "default", assistantSessionId = "beacon-chat")
        assertEquals(listOf("chat-a"), shown.map { it.getString("id") })
        assertTrue(rowIsChat(moved, "beacon-chat"))
        assertFalse(rowIsChat(other, "beacon-chat"))
        assertFalse(rowIsChat(moved, ""))
        // Another profile's chat that happens to carry the same lineage id is not the Beacon chat of this one.
        val foreign = json("id" to "beacon-chat-3", "_lineage_root_id" to "beacon-chat", "profile" to "coder")
        assertEquals(1, historyRows(listOf(foreign), HistoryView.All, HistoryOrder.Recent, currentProfile = "default", assistantProfile = "default", assistantSessionId = "beacon-chat").size)
    }
}
