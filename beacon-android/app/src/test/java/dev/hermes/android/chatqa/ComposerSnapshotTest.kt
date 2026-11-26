package dev.hermes.android.chatqa

import dev.hermes.android.ChatState
import dev.hermes.android.Message
import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.json
import dev.hermes.android.ui.ComposerChat
import dev.hermes.android.ui.composerChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer collects only the chat fields it reads, so a streaming token does not recompose its text field and menus. Adding a field read to
 * the composer without adding it to this snapshot would leave that control stale; these cases pin both halves of the rule.
 */
class ComposerSnapshotTest {
    private fun base() = ChatState(
        sessionId = "chat-a", raw = json("read_only" to false), queued = listOf(QueuedMessage(text = "waiting")),
        status = "Responding", streamId = "stream-1", uncertain = "", projectionReady = true, sessionReasoningEffort = "low",
    )

    @Test fun streamingTokensDoNotChangeTheComposerSnapshot() {
        val state = base()
        val before = composerChat(state)
        val after = composerChat(state.copy(liveText = "Basil wants light", liveReasoning = "thinking", liveTools = listOf(json("name" to "web")),
            liveSealed = listOf("earlier words"), liveSealedAt = listOf(3), liveBase = 2, liveBasePrompt = "p", liveOrdinal = 4,
            title = "Renamed while streaming", lastAnswerAt = 12L))
        assertEquals(before, after)
    }

    @Test fun everyFieldTheComposerReadsChangesTheSnapshot() {
        val base = composerChat(base())
        val changed = listOf(
            base.copy(sessionId = "chat-b"),
            base.copy(raw = json("read_only" to true)),
            base.copy(queued = emptyList()),
            base.copy(status = "Thinking"),
            base.copy(streamId = "stream-2"),
            base.copy(uncertain = "pending-1"),
            base.copy(projectionReady = false),
            base.copy(sessionReasoningEffort = "high"),
            base.copy(lostChoice = true),
            base.copy(sendInFlight = true),
            base.copy(hasReplies = true),
        )
        assertTrue(changed.all { it != base })
    }

    @Test fun anAssistantRowEnablesRepliesAndAStreamingTokenDoesNotChangeTheSnapshot() {
        val state = base().copy(messages = listOf(Message("assistant-1", "assistant", "A saved reply", sessionId = "chat-a")))
        val before = composerChat(state)
        assertTrue("an assistant row means this chat has replies", before.hasReplies)
        val after = composerChat(state.copy(liveText = "A token", liveReasoning = "thinking", liveTools = listOf(json("name" to "web")),
            liveSealed = listOf("earlier words"), liveSealedAt = listOf(2), liveBase = 1, liveBasePrompt = "p", liveOrdinal = 3))
        assertEquals(before, after)
        assertTrue(after.hasReplies)
    }

    @Test fun theTwoDerivedFlagsFollowTheChat() {
        assertTrue(composerChat(base().copy(lostWaiting = true, streamId = "")).lostChoice)
        assertEquals(false, composerChat(base().copy(lostWaiting = true, streamId = "stream-1")).lostChoice)
        assertTrue(composerChat(base().copy(uncertain = "pending-1", status = "Sending")).sendInFlight)
        assertEquals(false, composerChat(base().copy(uncertain = "pending-1", status = "Sending", queued = listOf(QueuedMessage(text = "", id = "pending-1")))).sendInFlight)
        assertNotEquals(composerChat(base()), ComposerChat())
    }
}
