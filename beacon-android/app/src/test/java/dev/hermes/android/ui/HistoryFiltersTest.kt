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
        assertEquals("Server order is kept for recent/created", rows, historyRows(rows, HistoryView.All, HistoryOrder.Created))
        assertEquals(listOf("b", "a", "d", "e", "c"), historyRows(rows, HistoryView.All, HistoryOrder.Title).map { it.getString("id") })
        assertEquals(HistoryOrder.Recent, HistoryOrder.of("unknown"))
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
}
