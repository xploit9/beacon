package dev.hermes.android.ui

import dev.hermes.android.voice.CallTranscript
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScreenCallTranscriptTest {
    @Test fun searchMatchesAnyLineAndKeepsTheWholeCallGroup() {
        val groups = groupCallTranscriptEvents(listOf(
            CallTranscript("call-one:user:1", "user", "Hidden orchid clue", turnId = "1"),
            CallTranscript("call-one:assistant:2", "assistant", "First reply", turnId = "2"),
            CallTranscript("call-one:user:3", "user", "Second question", turnId = "3"),
            CallTranscript("call-one:assistant:4", "assistant", "Second reply", turnId = "4"),
            CallTranscript("call-one:user:5", "user", "Third question", turnId = "5"),
        ) + listOf(
            CallTranscript("call-two:user:1", "user", "Another call", turnId = "1"),
        ))

        val matching = callTranscriptGroupsMatchingSearch(groups, "ORCHID")

        assertEquals(listOf("call-one"), matching.map { it.callId })
        assertEquals(5, matching.single().lines.size)
        assertEquals("Hidden orchid clue", matching.single().lines.first().text)
    }

    @Test fun blankSearchKeepsEveryCallGroup() {
        val groups = groupCallTranscriptEvents(listOf(
            CallTranscript("call-one:user:1", "user", "One", turnId = "1"),
            CallTranscript("call-two:user:1", "user", "Two", turnId = "1"),
        ))

        assertEquals(groups, callTranscriptGroupsMatchingSearch(groups, "  "))
    }
}
