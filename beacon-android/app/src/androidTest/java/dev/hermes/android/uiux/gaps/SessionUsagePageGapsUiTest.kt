package dev.hermes.android.uiux.gaps

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.ChatState
import dev.hermes.android.Message
import dev.hermes.android.core.json
import dev.hermes.android.ui.NativeAdministration
import dev.hermes.android.ui.formatTokenCount
import org.json.JSONObject
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Session usage on a device, with the page reading a chat and a usage record that the test sets: the empty, loading and error states, the
 * figures with the context bar, Details, Refresh and Back. The page's own read ends at once with the offline message (no client).
 */
class SessionUsagePageGapsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val session = "synthetic-chat"
    private val offline = "Reconnect to refresh this page."
    private fun key(vm: BeaconViewModel, id: String = session) = "native-usage:${vm.ui.value.profile}:$id"
    private val refused = json("success" to false, "message" to "Synthetic refusal")

    private fun show(vm: BeaconViewModel, chat: String = session) {
        if (chat.isNotBlank()) compose.runOnIdle { vm.openSyntheticChat(chat, raw = json("model" to "synthetic-model", "model_provider" to "example")) }
        compose.showPage { NativeAdministration(vm, "Session usage") }
        compose.waitForIdle()
    }
    private fun serve(vm: BeaconViewModel, payload: JSONObject) = compose.runOnIdle { vm.storeData(key(vm), payload) }
    private val figures = json("input_tokens" to 1234, "output_tokens" to 560, "context_length" to 32000, "context_tokens" to 8000, "model" to "synthetic-model")

    @Test fun withNoChatOpenThePageSaysSoAndShowsNothingElse() = withClientlessVm(compose) { vm ->
        show(vm, chat = "")
        compose.onNodeWithText("Open a chat first").assertIsDisplayed()
        compose.onNodeWithTag("usage-hero").assertDoesNotExist()
        compose.onNodeWithTag("settings-skeleton").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun refreshIsDisabledWithNoChatOpen() = withClientlessVm(compose) { vm ->
        show(vm, chat = "")
        compose.onNodeWithContentDescription("Refresh usage").assertIsNotEnabled()
        // Nothing was read for a chat that does not exist.
        compose.runOnIdle { assertNull(vm.data.value[key(vm, "")]) }
    }

    /** REVIEW-FIXCHAT-121 P3: an untitled chat is "New chat", as in its header, and its model is named as the composer's chip names it (no raw ids). */
    @Test fun anUntitledChatAndItsModelReadAsTheHeaderAndTheChipSayThem() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { vm.openSyntheticChat(session, title = "New conversation", raw = json("model" to "synthetic-model", "model_provider" to "example")) }
        compose.showPage { NativeAdministration(vm, "Session usage") }
        compose.waitForIdle()
        compose.runOnIdle { vm.forget(key(vm)) }
        compose.onNodeWithText("New chat").assertIsDisplayed()
        compose.onNodeWithText("Synthetic Model").assertIsDisplayed()
        compose.onAllNodesWithText("New conversation").assertCountEquals(0)
        compose.onAllNodesWithText("example", substring = true).assertCountEquals(0)
        serve(vm, figures)
        compose.onNodeWithTag("usage-figures").assertIsDisplayed()
        compose.onNodeWithText("New chat").assertIsDisplayed()
        compose.onNodeWithText("Synthetic Model").assertIsDisplayed()
    }

    /** UX-121 P3-10: the chat and its model, which the phone knows, show at once; only the figures wait (placeholder rows). */
    @Test fun theFirstReadShowsPlaceholderRowsNotFiguresOrAnError() = withClientlessVm(compose) { vm ->
        show(vm)
        compose.runOnIdle { vm.forget(key(vm)) }
        compose.onNodeWithTag("settings-skeleton").assertIsDisplayed()
        compose.onNodeWithTag("usage-hero").assertIsDisplayed()
        compose.onNodeWithText("Synthetic Model").assertIsDisplayed()
        compose.onNodeWithTag("usage-figures").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText(offline).assertDoesNotExist()
    }

    @Test fun aFailedReadSaysWhyAndRetryReadsAgain() = withClientlessVm(compose) { vm ->
        show(vm)
        // The page's own read has already failed offline: the message and Retry are what it shows.
        compose.onNodeWithText(offline).assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        serve(vm, json("_error" to "Synthetic usage failure"))
        compose.onNodeWithText("Synthetic usage failure").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        // Retry ran the read again: the record was replaced by the offline answer.
        compose.onNodeWithText("Synthetic usage failure").assertDoesNotExist()
        compose.onNodeWithText(offline).assertIsDisplayed()
    }

    @Test fun theFiguresAreShortAndTheContextBarShowsTheShare() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, figures)
        compose.onNodeWithTag("usage-hero").assertIsDisplayed()
        compose.onNodeWithText("Synthetic chat").assertIsDisplayed()
        compose.onNodeWithText("Synthetic Model").assertIsDisplayed()
        compose.onNodeWithTag("usage-figures").assertIsDisplayed()
        for ((label, value) in listOf("Tokens in" to formatTokenCount(1234), "Tokens out" to formatTokenCount(560),
            "Context window" to formatTokenCount(32000), "Context used" to formatTokenCount(8000))) {
            compose.onNodeWithText(value).assertIsDisplayed()
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        compose.onNodeWithText("Cumulative tokens for this chat.").assertIsDisplayed()
        compose.onNodeWithTag("usage-context-bar").assertRangeInfoEquals(ProgressBarRangeInfo(0.25f, 0f..1f))
        compose.onNodeWithText("8K of 32K used").assertIsDisplayed()
        compose.onNodeWithText("25% of context").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    /** The owner's screenshot: the lifetime total must never become the context used. */
    @Test fun theOwnersLifetimeTotalDoesNotBecomeTheContextShare() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, json("input_tokens" to 24_000, "output_tokens" to 30_000, "total_tokens" to 8_700_000,
            "context_tokens" to 384_000, "context_length" to 1_000_000))
        compose.onNodeWithText("384K of 1M used").assertIsDisplayed()
        compose.onNodeWithText("38% of context").assertIsDisplayed()
        compose.onNodeWithTag("usage-context-bar").assertRangeInfoEquals(ProgressBarRangeInfo(0.38f, 0f..1f))
        compose.onNodeWithText("100% of context", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Context full", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("usage-details-toggle").performClick()
        compose.onNodeWithText("Total tokens").assertDoesNotExist()
        compose.onNodeWithText("8700000").assertDoesNotExist()
    }

    @Test fun anEstimatedRecordSaysSoBesideTheBar() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, json("input_tokens" to 24_000, "output_tokens" to 30_000, "context_used" to 384_000, "context_max" to 1_000_000,
            "context_estimated" to true))
        compose.onNodeWithText("384K of 1M used · estimated").assertIsDisplayed()
    }

    @Test fun detailsDrawsHermesBreakdownAndDoesNotRequestAgain() = withClientlessVm(compose) { vm ->
        show(vm)
        val breakdown = json("categories" to org.json.JSONArray()
            .put(json("id" to "system_prompt", "label" to "System prompt", "tokens" to 64_000, "color" to "var(--context-usage-system)"))
            .put(json("id" to "tool_definitions", "label" to "Tool definitions", "tokens" to 96_000, "color" to "var(--context-usage-tools)"))
            .put(json("id" to "conversation", "label" to "Conversation", "tokens" to 224_000, "color" to "var(--context-usage-conversation)")),
            "context_estimated" to true)
        serve(vm, json("input_tokens" to 24_000, "output_tokens" to 30_000, "context_used" to 384_000, "context_max" to 1_000_000,
            "context_breakdown" to breakdown))
        compose.onNodeWithTag("usage-details-toggle").performClick()
        compose.onNodeWithTag("usage-breakdown-bar").assertIsDisplayed()
        for ((label, value) in listOf("System prompt" to "64K · 17%", "Tool definitions" to "96K · 25%", "Conversation" to "224K · 58%")) {
            compose.onNodeWithText(label).assertIsDisplayed()
            compose.onNodeWithText(value).assertIsDisplayed()
        }
        compose.onNodeWithText("384K of 1M used · estimated").assertIsDisplayed()
        compose.runOnIdle { assertTrue("Details never starts another read", vm.refreshing.value.isEmpty()) }
    }

    @Test fun withoutAContextWindowThereIsNoBarAndWithoutAnyFigureNoFigureRow() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, json("input_tokens" to 5))
        compose.onNodeWithTag("usage-figures").assertIsDisplayed()
        compose.onNodeWithTag("usage-context-bar").assertDoesNotExist()
        compose.onNodeWithText("Context window").assertDoesNotExist()
        compose.onNodeWithText("Context used").assertDoesNotExist()
        serve(vm, json("model" to "synthetic-model"))
        compose.onNodeWithTag("usage-figures").assertDoesNotExist()
        compose.onNodeWithTag("usage-hero").assertIsDisplayed()
    }

    /** The owner's screenshot: 628K in, 67K out, a 1M window and 1M used, so a full bar and the full word. */
    @Test fun theOwnersFullWindowNamesTheUsedAndWindowFiguresAndSaysContextFull() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, json("input_tokens" to 628_000, "output_tokens" to 67_000, "context_window" to 1_000_000, "context_used" to 1_000_000))
        compose.onNodeWithText("628K").assertIsDisplayed()
        compose.onNodeWithText("67K").assertIsDisplayed()
        compose.onAllNodesWithText("1M").assertCountEquals(2)
        compose.onNodeWithText("Context window").assertIsDisplayed()
        compose.onNodeWithText("Context used").assertIsDisplayed()
        compose.onNodeWithTag("usage-context-bar").assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..1f))
        compose.onNodeWithText("1M of 1M used").assertIsDisplayed()
        compose.onNodeWithText("100% of context · Context full").assertIsDisplayed()
        compose.onNodeWithText("Nearly full", substring = true).assertDoesNotExist()
    }

    @Test fun aSeventyPercentWindowKeepsTheUsedOfWindowLabelWithoutAStatusWord() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, json("input_tokens" to 500_000, "output_tokens" to 40_000, "context_window" to 1_000_000, "context_used" to 700_000))
        compose.onNodeWithText("500K").assertIsDisplayed()
        compose.onNodeWithText("40K").assertIsDisplayed()
        compose.onNodeWithText("700K of 1M used").assertIsDisplayed()
        compose.onNodeWithText("70% of context").assertIsDisplayed()
        compose.onNodeWithTag("usage-context-bar").assertRangeInfoEquals(ProgressBarRangeInfo(0.7f, 0f..1f))
        compose.onNodeWithText("Nearly full", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Context full", substring = true).assertDoesNotExist()
    }

    @Test fun withoutBothContextFiguresThereIsNoBarAndNoInventedShare() = withClientlessVm(compose) { vm ->
        show(vm)
        // Hermes returned the window but not the used figure: the window is named, nothing claims a share.
        serve(vm, json("input_tokens" to 628_000, "output_tokens" to 67_000, "context_window" to 1_000_000))
        compose.onNodeWithText("Context window").assertIsDisplayed()
        compose.onNodeWithText("Context used").assertDoesNotExist()
        compose.onNodeWithTag("usage-context-bar").assertDoesNotExist()
        compose.onNodeWithText("of 1M used", substring = true).assertDoesNotExist()
        // Hermes returned the used figure but not the window: it is named, still with no bar.
        serve(vm, json("input_tokens" to 628_000, "output_tokens" to 67_000, "context_used" to 695_000))
        compose.onNodeWithText("Context used").assertIsDisplayed()
        compose.onNodeWithText("Context window").assertDoesNotExist()
        compose.onNodeWithTag("usage-context-bar").assertDoesNotExist()
        compose.onNodeWithText("695K of", substring = true).assertDoesNotExist()
    }

    @Test fun anUnreportedChatSaysSoAndStillNamesTheContextItDoesKnow() = withClientlessVm(compose) { vm ->
        compose.runOnIdle {
            vm.chatFlow.value = ChatState(sessionId = session, title = "Synthetic chat", raw = json("model" to "synthetic-model"),
                messages = listOf(Message.from(json("role" to "assistant", "content" to "Synthetic answer"), 0, session)))
        }
        compose.showPage { NativeAdministration(vm, "Session usage") }
        compose.waitForIdle()
        serve(vm, json("input_tokens" to 0, "output_tokens" to 0, "context_window" to 32_000, "context_used" to 4_200))
        compose.onNodeWithTag("usage-unreported").assertIsDisplayed()
        compose.onNodeWithText("Hermes didn't report usage for this chat.").assertIsDisplayed()
        compose.onNodeWithText("Tokens in").assertDoesNotExist()
        compose.onNodeWithText("Tokens out").assertDoesNotExist()
        compose.onNodeWithText("Cumulative tokens for this chat.").assertDoesNotExist()
        compose.onNodeWithText("Context window").assertIsDisplayed()
        compose.onNodeWithText("4.2K of 32K used").assertIsDisplayed()
        compose.onNodeWithTag("usage-context-bar").assertRangeInfoEquals(ProgressBarRangeInfo(0.13f, 0f..1f))
        compose.onNodeWithText("13% of context").assertIsDisplayed()
    }

    @Test fun detailsHoldsOnlyWhatTheFiguresDidNotUseAndHidesPrivateKeys() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, figures.apply {
            put("provider_note", "kept on the server"); put("api_key", "must-not-show"); put("_internal", "hidden"); put("empty_note", "")
        })
        compose.onNodeWithTag("usage-details").assertIsDisplayed()
        compose.onNodeWithText("Provider note").assertDoesNotExist()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Provider note").assertIsDisplayed()
        compose.onNodeWithText("kept on the server").assertIsDisplayed()
        for (hidden in listOf("Input tokens", "Output tokens", "Total tokens", "Api key", "must-not-show", "Internal", "Empty note")) {
            compose.onNodeWithText(hidden).assertDoesNotExist()
        }
        // The only "Context window" is the figure label; Details does not repeat the consumed key.
        compose.onAllNodesWithText("Context window").assertCountEquals(1)
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Provider note").assertDoesNotExist()
    }

    @Test fun theDetailsRowHasAChevronThatTurnsWithItsState() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, figures.apply { put("provider_note", "kept on the server") })
        val state = { text: String -> SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text) }
        compose.onNodeWithTag("usage-details-toggle").assertHeightIsAtLeast(48.dp).assertTextContains("Details").assert(state("Collapsed"))
        compose.onNodeWithTag("usage-details-toggle").performClick().assert(state("Expanded"))
        // A new read keeps the open Details and its rows on screen.
        serve(vm, figures.apply { put("provider_note", "kept on the server") })
        compose.waitForIdle()
        compose.onNodeWithTag("usage-details-toggle").assert(state("Expanded"))
        compose.onNodeWithText("Provider note").assertIsDisplayed()
        compose.onNodeWithTag("usage-details-toggle").performClick().assert(state("Collapsed"))
    }

    @Test fun detailsSaysWhenTheBreakdownIsUnavailableAndDrawsNoPlaceholderBar() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, json("input_tokens" to 1, "output_tokens" to 2, "context_used" to 50, "context_max" to 100))
        compose.onNodeWithTag("usage-figures").assertIsDisplayed()
        compose.onNodeWithTag("usage-details-toggle").performClick()
        compose.onNodeWithText("The context breakdown isn't available for this chat.").assertIsDisplayed()
        compose.onNodeWithTag("usage-breakdown-bar").assertDoesNotExist()
    }

    @Test fun refreshReadsAgainAndIsOffWhileARefreshRuns() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, json("_error" to "Synthetic usage failure"))
        compose.onNodeWithContentDescription("Refresh usage").assertIsEnabled().performClick()
        compose.onNodeWithText("Synthetic usage failure").assertDoesNotExist()
        compose.onNodeWithText(offline).assertIsDisplayed()
        serve(vm, figures)
        compose.runOnIdle { vm.refreshingFlow.value = setOf(key(vm)) }
        compose.onNodeWithContentDescription("Refresh usage").assertIsNotEnabled()
        compose.onNodeWithTag("settings-refreshing").assertExists()
        compose.onNodeWithTag("usage-figures").assertIsDisplayed()
        compose.runOnIdle { vm.refreshingFlow.value = emptySet() }
        compose.onNodeWithTag("settings-refreshing").assertDoesNotExist()
        compose.onNodeWithContentDescription("Refresh usage").assertIsEnabled()
    }

    @Test fun aFailedRefreshKeepsTheFiguresOnScreen() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, figures.apply { put("_error", "Synthetic refresh failure") })
        compose.onNodeWithTag("usage-figures").assertIsDisplayed()
        compose.onNodeWithTag("usage-hero").assertIsDisplayed()
        // The error is a row under the figures, with its Retry.
        compose.onNodeWithText("Synthetic refresh failure").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test fun aRecordThatOnlyRefusesShowsJustItsErrorRow() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, refused)
        compose.onNodeWithText("Synthetic refusal").assertIsDisplayed()
        compose.onNodeWithTag("usage-hero").assertDoesNotExist()
        compose.onNodeWithTag("usage-figures").assertDoesNotExist()
    }

    @Test fun theHeaderArrowLeavesSessionUsage() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { vm.tab("More"); vm.more("Session usage") }
        compose.waitUntil(5_000) { vm.ui.value.morePage == "Session usage" }
        show(vm)
        compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertNotEquals("Back leaves Session usage", "Session usage", vm.ui.value.morePage)
    }
}
