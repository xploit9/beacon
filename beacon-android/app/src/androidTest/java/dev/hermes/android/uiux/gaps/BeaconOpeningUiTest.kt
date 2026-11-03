package dev.hermes.android.uiux.gaps

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.AppState
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.ChatState
import dev.hermes.android.core.json
import dev.hermes.android.ui.ChatScreen
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.ui.LocalBeaconSettings
import dev.hermes.android.ui.NativeLocalization
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/**
 * Lane V2A item 1: while Beacon's conversation opens, the Beacon tab shows the chat skeleton with the composer in place (idle, nothing of the
 * chat that was open before), not a text page. An error or a stalled open keeps its own page with Retry. The ViewModel has no client, so
 * Beacon's conversation is never ready here.
 */
class BeaconOpeningUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(vm: BeaconViewModel, opening: Boolean, error: String = "") {
        compose.runOnIdle {
            // A different chat is still loaded, read-only, with a reply streaming: none of that may reach the composer.
            vm.chatFlow.value = ChatState(sessionId = "other-chat", title = "Other chat", raw = json("read_only" to true), streamId = "run-1")
            vm.appFlow.value = AppState(origin = "https://hermes.example.com", authenticated = true, tab = "Beacon", connected = true, restoringSession = false,
                beaconOpening = opening, beaconError = error, bootstrap = json("optional_features" to json("plugin" to "available")))
        }
        compose.setContent {
            NativeLocalization("en") { BeaconTheme {
                CompositionLocalProvider(LocalAssistantName provides "Beacon", LocalBeaconSettings provides JSONObject()) { ChatScreen(vm, beacon = true) }
            } }
        }
        compose.waitForIdle()
    }

    @Test fun whileTheConversationOpensTheChatShowsItsSkeletonAndAnIdleComposer() = withClientlessVm(compose) { vm ->
        show(vm, opening = true)
        compose.onNodeWithTag("chat-skeleton").assertIsDisplayed()
        compose.onNodeWithText("Your chats and drafts are saved.").assertDoesNotExist()
        compose.onNodeWithText("Opening Beacon’s conversation").assertDoesNotExist()
        compose.onNodeWithTag("message-input").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Message Beacon").assertExists()
        compose.onNodeWithText("Branch this conversation to reply").assertDoesNotExist()
        compose.onNodeWithTag("composer-attach").assertIsNotEnabled()
        compose.onNodeWithTag("composer-dictate").assertIsNotEnabled()
        compose.onNodeWithTag("composer-model-effort").assertIsNotEnabled()
    }

    @Test fun anErrorKeepsItsOwnPageWithRetryAndNoComposer() = withClientlessVm(compose) { vm ->
        show(vm, opening = false, error = "Synthetic failure")
        compose.onNodeWithTag("chat-skeleton").assertDoesNotExist()
        compose.onNodeWithText("Beacon’s conversation is unavailable").assertIsDisplayed()
        compose.onNodeWithText("Synthetic failure").assertIsDisplayed()
        compose.onNodeWithText("Retry Beacon").assertIsDisplayed()
        compose.onNodeWithTag("message-input").assertDoesNotExist()
    }

    @Test fun anOpenThatIsNotRunningKeepsItsRetryPageWithoutTheSavedLine() = withClientlessVm(compose) { vm ->
        show(vm, opening = false)
        compose.onNodeWithTag("chat-skeleton").assertDoesNotExist()
        compose.onNodeWithText("Opening Beacon’s conversation").assertIsDisplayed()
        compose.onNodeWithText("Your chats and drafts are saved.").assertDoesNotExist()
        compose.onNodeWithText("Retry Beacon").assertIsDisplayed()
    }
}
