package dev.hermes.android

import dev.hermes.android.ui.bubbleGroups
import dev.hermes.android.voice.interleaveCallMessages
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatAlertsTest {
    private fun row(id: String = "notice-1", text: String = "Daniel moved the review", created: Double = 1_000.5, expires: Long = 90_000, verdict: String = "") =
        JSONObject().put("notice_id", id).put("text", text).put("source", "Email").put("created_at", created).put("expires_at", expires).put("verdict", verdict)

    @Test fun aPluginRowBecomesAnAlertWithItsTimeInMilliseconds() {
        assertEquals(ChatAlert("notice-1", "Daniel moved the review", "Email", 1_000_500, 90_000, "important"), ChatAlert.from(row(verdict = "important")))
    }

    @Test fun aRowWithoutAnIdOrWordsIsDroppedAndAnUnknownVerdictIsOpen() {
        assertNull(ChatAlert.from(row(id = "not valid")))
        assertNull(ChatAlert.from(row(text = " ")))
        assertEquals("", ChatAlert.from(row(verdict = "maybe"))?.verdict)
    }

    @Test fun theSavedCopyKeepsEveryAlertAndItsChoice() {
        val alerts = listOf(ChatAlert("a", "One", "Email", 1_234, 99, "not_important"), ChatAlert("b", "Two", "", 5_678, 100))
        assertEquals(alerts, decodeChatAlerts(JSONArray(alerts.map { it.toJson() }).toString()))
        assertEquals(emptyList<ChatAlert>(), decodeChatAlerts("not json"))
    }

    @Test fun whatTheListSaysWinsButAlertsItNoLongerHasStay() {
        val saved = listOf(ChatAlert("old", "Gone from Hermes", "Email", 100, 200, "important"), ChatAlert("kept", "Still there", "Email", 300, 400))
        val fresh = listOf(ChatAlert("kept", "Still there", "Email", 300, 400, "not_important"), ChatAlert("new", "Just arrived", "Email", 500, 600))

        val merged = mergeChatAlerts(saved, fresh)

        assertEquals(listOf("old", "kept", "new"), merged.map { it.noticeId })
        assertEquals(listOf("important", "not_important", ""), merged.map { it.verdict })
    }

    @Test fun anAlertWhoseLearnedNoteIsGoneOpensAgain() {
        val merged = mergeChatAlerts(listOf(ChatAlert("a", "One", "Email", 1, 2, "important")), listOf(ChatAlert("a", "One", "Email", 1, 2)))

        assertEquals("", merged.single().verdict)
    }

    @Test fun theSavedListKeepsTheNewestHundred() {
        val merged = mergeChatAlerts((1..150).map { ChatAlert("n$it", "Alert $it", "Email", it.toLong(), 9_999) }, emptyList())

        assertEquals(100, merged.size)
        assertEquals("n51", merged.first().noticeId)
        assertEquals("n150", merged.last().noticeId)
    }

    @Test fun alertsSitAmongTheMessagesByTimeEachInItsOwnGroup() {
        val messages = listOf(Message("m1", "user", "Hi", recordedAt = 1_000), Message("m2", "assistant", "Hello", recordedAt = 3_000))
        val alerts = listOf(ChatAlert("a", "One", "Email", 2_000, 9), ChatAlert("b", "Two", "Email", 4_000, 9)).map { Message("alert:${it.noticeId}", "assistant", it.text, recordedAt = it.createdAt) }

        val timeline = interleaveCallMessages(messages, alerts)
        val groups = bubbleGroups(timeline, emptySet(), setOf("alert:a", "alert:b"))

        assertEquals(listOf("m1", "alert:a", "m2", "alert:b"), timeline.map { it.id })
        assertTrue(groups.getValue("alert:a").startsRun && groups.getValue("alert:a").tail)
        assertTrue(groups.getValue("m2").startsRun)
    }
}
