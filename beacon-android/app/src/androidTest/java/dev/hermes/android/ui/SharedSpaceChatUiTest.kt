package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.hermes.android.data.BotModeEvent
import dev.hermes.android.data.BotModeMember
import dev.hermes.android.data.NativeBotModeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The shared space's group-chat parts on a fixture space: two bots and the owner. Local state only; no backend is involved. */
class SharedSpaceChatUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val roster = listOf(BotModeMember("coder", "coder", "coder"), BotModeMember("academic", "academic", "academic"))
    private val day = 1_800_000_000L
    private var state by mutableStateOf(NativeBotModeState(selectedSpace = "room", roster = roster, members = listOf("coder", "academic")))

    private fun show() = compose.setContent {
        MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                SharedSpaceHeader("Review room", state, emptyList(), null) { }
                SharedSpaceChat(state, emptyList(), onDraft = { state = state.copy(draft = it) }, onSend = { }, onAction = { _, _ -> }, onReviewProfiles = { }) { text, _ -> Text(text) }
            }
        }
    }

    private fun message(id: String, speaker: String, at: Long = day) = BotModeEvent(id, speaker, "Message $id", at = at)

    @Test fun theHeaderAndATypingRowSayWhoIsAnswering() {
        state = state.copy(events = listOf(message("1", "You")), typing = listOf("coder"), working = true)
        show()
        compose.onNodeWithText("coder is typing…").assertIsDisplayed()
        compose.onNodeWithTag("space-typing:coder").assertIsDisplayed()
        compose.onAllNodesWithTag("space-typing:academic").assertCountEquals(0)

        compose.runOnIdle { state = state.copy(typing = listOf("coder", "academic")) }
        compose.onNodeWithText("coder and academic are typing…").assertIsDisplayed()
        compose.onNodeWithTag("space-typing:academic").assertIsDisplayed()

        // The reply lands and the activity ends: the rows go and the header counts the bots again.
        compose.runOnIdle { state = state.copy(events = state.events + message("2", "coder"), typing = emptyList(), working = false) }
        compose.onNodeWithText("2 bots").assertIsDisplayed()
        compose.onAllNodesWithTag("space-typing:coder").assertCountEquals(0)
        compose.onNodeWithText("Message 2").assertIsDisplayed()
    }

    @Test fun aMentionChipInsertsItsHandleAndTypingAnAtNarrowsTheChips() {
        show()
        compose.onNodeWithTag("space-chip:coder").assertIsDisplayed()
        compose.onNodeWithTag("space-chip:academic").assertIsDisplayed()
        compose.onNodeWithTag("space-chip:all").assertIsDisplayed()
        compose.onNodeWithTag("space-chip:coder").performClick()
        compose.runOnIdle { assertEquals("@coder ", state.draft) }

        compose.runOnIdle { state = state.copy(draft = "") }
        compose.onNodeWithTag("bot-shared-composer").performTextInput("@ac")
        compose.onAllNodesWithTag("space-chip:coder").assertCountEquals(0)
        compose.onNodeWithTag("space-chip:academic").performClick()
        compose.runOnIdle { assertEquals("@academic ", state.draft) }
    }

    @Test fun consecutiveMessagesShowOneAvatarAndTheSenderNameOnce() {
        state = state.copy(events = listOf(message("1", "coder"), message("2", "coder"), message("3", "coder"), message("4", "academic"), message("5", "You"), message("6", "You")))
        show()
        // One avatar under each run of a bot's messages, none beside the owner's; the name only on a run's first bubble.
        compose.onAllNodesWithTag("space-message-avatar").assertCountEquals(2)
        // A bubble is one TalkBack stop, so its sender line lives in the unmerged tree.
        compose.onAllNodesWithTag("space-sender", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithTag("date-chip").assertCountEquals(1)
        compose.onNodeWithText("Message 6").assertIsDisplayed()
    }

    @Test fun anEmptySpaceKeepsItsPromptAndAFailureIsACompactNotice() {
        show()
        compose.onNodeWithText("Send the bots a task to start the discussion.").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(events = listOf(BotModeEvent("f1", "coder", "This bot’s turn was cancelled.", failed = true, at = day))) }
        compose.onNodeWithText("coder · failed").assertIsDisplayed()
        compose.onNodeWithText("Review bot profiles").assertIsDisplayed()
    }
}
