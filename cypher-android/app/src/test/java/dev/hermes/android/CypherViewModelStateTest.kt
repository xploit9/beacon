package dev.hermes.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CypherViewModelStateTest {
    @Test fun registeredAssistantConversationIsAdoptedOnlyWhenItChanges() {
        assertEquals("shared-chat", assistantConversationToAdopt("phone-chat", "shared-chat"))
        assertNull(assistantConversationToAdopt("shared-chat", "shared-chat"))
        assertNull(assistantConversationToAdopt("phone-chat", ""))
        assertNull(assistantConversationToAdopt("phone-chat", null))
    }

    @Test fun serverRequestPromptIsShownAndAnExplicitNullPendingClearsIt() {
        val request = JSONObject("{\"approval_id\":\"queue-1\",\"command\":\"ls\"}")
        assertEquals("queue-1", pendingPrompt(request)?.optString("approval_id"))
        assertEquals("queue-2", pendingPrompt(JSONObject("{\"pending\":{\"approval_id\":\"queue-2\"}}"))?.optString("approval_id"))
        assertNull(pendingPrompt(JSONObject("{\"pending\":null}")))
    }

    @Test fun releasedAssistantTabValueMigrates() {
        assertEquals("Cypher", migratedTab("Xara"))
        assertEquals("Chat", migratedTab("Chat"))
    }

    @Test fun terminalCompletionReleasesStreamAndKeepsUnconfirmedOutputVisible() {
        val status = "Reply ended · final reply not confirmed; live output retained"
        val running = ChatState(
            sessionId = "chat",
            streamId = "runtime",
            liveText = "partial answer",
            liveReasoning = "working notes",
            liveTools = listOf(JSONObject("{\"name\":\"terminal\"}")),
            status = status,
        )

        val ended = releaseTerminalChat(running, "runtime", replyConfirmed = false, terminalStatus = status)

        assertEquals("", ended.streamId)
        assertEquals("partial answer", ended.liveText)
        assertEquals("working notes", ended.liveReasoning)
        assertTrue(ended.liveTools.isEmpty())
        assertEquals(status, ended.status)
        val newer = running.copy(streamId = "new-runtime")
        assertSame(newer, releaseTerminalChat(newer, "runtime", replyConfirmed = false, terminalStatus = status))
    }

    @Test fun confirmedTerminalReplyClearsTransientOutput() {
        val running = ChatState(streamId = "runtime", liveText = "complete", liveReasoning = "notes", liveTools = listOf(JSONObject()))

        val ended = releaseTerminalChat(running, "runtime", replyConfirmed = true, terminalStatus = "")

        assertEquals("", ended.streamId)
        assertEquals("", ended.liveText)
        assertEquals("", ended.liveReasoning)
        assertTrue(ended.liveTools.isEmpty())
    }
}
