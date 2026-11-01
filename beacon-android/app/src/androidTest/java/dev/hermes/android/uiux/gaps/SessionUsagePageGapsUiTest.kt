package dev.hermes.android.uiux.gaps

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.json
import dev.hermes.android.ui.NativeAdministration
import dev.hermes.android.ui.formatTokenCount
import org.json.JSONObject
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    private val figures = json("input_tokens" to 1234, "output_tokens" to 560, "context_window" to 32000, "total_tokens" to 8000, "model" to "synthetic-model")

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

    @Test fun theFirstReadShowsPlaceholderRowsNotFiguresOrAnError() = withClientlessVm(compose) { vm ->
        show(vm)
        compose.runOnIdle { vm.forget(key(vm)) }
        compose.onNodeWithTag("settings-skeleton").assertIsDisplayed()
        compose.onNodeWithTag("usage-hero").assertDoesNotExist()
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
        compose.onNodeWithText("example · synthetic-model").assertIsDisplayed()
        compose.onNodeWithTag("usage-figures").assertIsDisplayed()
        for ((label, value) in listOf("Input" to formatTokenCount(1234), "Output" to formatTokenCount(560), "Context" to formatTokenCount(32000))) {
            compose.onNodeWithText(value).assertIsDisplayed()
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        compose.onNodeWithTag("usage-context-bar").assertRangeInfoEquals(ProgressBarRangeInfo(0.25f, 0f..1f))
        compose.onNodeWithText("25% of context").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun withoutAContextWindowThereIsNoBarAndWithoutAnyFigureNoFigureRow() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, json("input_tokens" to 5))
        compose.onNodeWithTag("usage-figures").assertIsDisplayed()
        compose.onNodeWithTag("usage-context-bar").assertDoesNotExist()
        serve(vm, json("model" to "synthetic-model"))
        compose.onNodeWithTag("usage-figures").assertDoesNotExist()
        compose.onNodeWithTag("usage-hero").assertIsDisplayed()
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
        for (hidden in listOf("Input tokens", "Output tokens", "Context window", "Total tokens", "Api key", "must-not-show", "Internal", "Empty note")) {
            compose.onNodeWithText(hidden).assertDoesNotExist()
        }
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Provider note").assertDoesNotExist()
    }

    @Test fun thereIsNoDetailsRowWhenEveryKeyWasUsed() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, json("input_tokens" to 1, "output_tokens" to 2, "context_window" to 100, "total_tokens" to 3))
        compose.onNodeWithTag("usage-figures").assertIsDisplayed()
        compose.onNodeWithTag("usage-details").assertDoesNotExist()
        compose.onNodeWithText("Details").assertDoesNotExist()
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
