package dev.hermes.android.uiux.drawer

import dev.hermes.android.core.json
import dev.hermes.android.ui.HistoryOrder
import dev.hermes.android.ui.HistoryView
import dev.hermes.android.ui.chatPreviewLine
import dev.hermes.android.ui.chatPreviewText
import dev.hermes.android.ui.cronDeliveryLabel
import dev.hermes.android.ui.historyRows
import dev.hermes.android.ui.isAssistantSessionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPreviewDrawerTest {
    @Test fun aSingleLineCronPreviewLosesItsLabelAndBullet() {
        // Hermes' list preview is one line: the label's line break is already a space before the post's "- " bullet.
        assertEquals("Patch PHP urgently", chatPreviewText("[Cron delivery: Morning security brief] - Patch PHP urgently"))
        assertEquals("Patch PHP urgently", chatPreviewText("  [Cron delivery: Morning security brief] – Patch PHP urgently"))
        assertEquals("Patch PHP urgently", chatPreviewText("[Cron delivery: Morning security brief]: Patch PHP urgently"))
        assertEquals("Patch PHP urgently", chatPreviewText("[Cron delivery: Morning security brief] • Patch PHP urgently"))
        assertEquals("Patch PHP urgently", chatPreviewText("[Cron delivery: Morning security brief] Patch PHP urgently"))
    }

    @Test fun theLineBreakFormStillWorksAndOtherBracketsStay() {
        assertEquals("Three new advisories.", chatPreviewText("[Cron delivery: Cybersecurity news]\nThree new advisories."))
        assertEquals("[Draft: plan] - keep", chatPreviewText("[Draft: plan] - keep"))
        // Only a label at the very start goes; the same words later in the message are the owner's text.
        assertEquals("See [Cron delivery: News] - above", chatPreviewText("See [Cron delivery: News] - above"))
        // A job name never spans two lines, so that is not a label.
        assertEquals("[Cron delivery: a b] - c", chatPreviewText("[Cron delivery: a\nb] - c"))
    }

    @Test fun theBubbleKeepsItsStrictLabelPattern() {
        // The list pattern is looser on purpose; the bubble still needs the label alone on its line.
        assertNull(cronDeliveryLabel.find("[Cron delivery: Morning security brief] - Patch PHP urgently"))
        assertEquals("[Cron delivery: News]\n", cronDeliveryLabel.find("[Cron delivery: News]\nBody")?.value)
    }

    @Test fun theChatListLineShowsThePostNotTheLabel() {
        val row = json("title" to "Morning security brief", "preview" to "[Cron delivery: Morning security brief] - Patch PHP urgently, then rotate keys.", "message_count" to 2)
        assertEquals("Patch PHP urgently, then rotate keys.", chatPreviewLine(row, null))
    }

    private val assistantId = "20261031_101500_ab12"

    @Test fun theAssistantSessionNeverAppearsAsAnOrdinaryRow() {
        val aliases = listOf(
            json("session_id" to assistantId, "profile" to "default", "source" to "android"),
            json("session_id" to assistantId, "profile" to "Default", "source" to "android"),
            json("session_id" to assistantId, "profile" to " DEFAULT ", "source" to "android"),
            json("session_id" to assistantId, "profile" to "", "source" to "android"),
            json("id" to assistantId, "source" to "android"),
        )
        for (row in aliases) {
            assertTrue("$row", isAssistantSessionRow(row, "default", "default", assistantId))
            assertEquals(emptyList<Any>(), historyRows(listOf(row), HistoryView.All, HistoryOrder.Recent, "default", "default", assistantId))
        }
    }

    @Test fun otherSessionsAndOtherProfilesStayListed() {
        val ordinary = json("session_id" to "chat-1", "profile" to "default")
        val sameIdOtherProfile = json("session_id" to assistantId, "profile" to "coder")
        val rows = listOf(ordinary, sameIdOtherProfile, json("session_id" to assistantId, "profile" to "default"))
        val shown = historyRows(rows, HistoryView.All, HistoryOrder.Recent, "default", "default", assistantId)
        assertEquals(listOf(ordinary, sameIdOtherProfile), shown)
        // Without a known assistant session nothing is hidden.
        assertEquals(rows, historyRows(rows, HistoryView.All, HistoryOrder.Recent, "default", "default", ""))
        assertFalse(isAssistantSessionRow(ordinary, "default", "default", assistantId))
    }

    @Test fun aRowWithoutAProfileBelongsToTheProfileTheListIsLoadedFor() {
        val bare = json("session_id" to assistantId)
        // Loaded for the assistant's profile: it is the assistant's session. Loaded for another: it is not.
        assertTrue(isAssistantSessionRow(bare, "work", "work", assistantId))
        assertFalse(isAssistantSessionRow(bare, "default", "work", assistantId))
        // An assistant profile that is not set follows the profile the list is loaded for.
        assertTrue(isAssistantSessionRow(bare, "work", "", assistantId))
    }
}
