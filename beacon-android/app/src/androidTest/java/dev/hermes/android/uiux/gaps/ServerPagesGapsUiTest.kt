package dev.hermes.android.uiux.gaps

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.json
import dev.hermes.android.data.NativeCheckpoint
import dev.hermes.android.data.NativeServerState
import dev.hermes.android.ui.NativeServerSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The four Server pages on a device, driven by plain state: failed reads, the notice row, Logs, the update command and the review path. */
class ServerPagesGapsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val pages = listOf("Health", "Logs", "Server updates", "Session checkpoints")

    private class Calls { var check = 0; var apply = 0; var review = 0; val files = mutableListOf<String>(); val searches = mutableListOf<String>() }

    private fun show(page: () -> String, state: () -> NativeServerState, calls: Calls, onSearch: (String) -> Unit = { calls.searches += it }) = compose.showPage {
        NativeServerSettings(page(), state(), { calls.check++ }, { calls.apply++ }, { calls.files += it }, onSearch, { calls.review++ })
    }

    @Test fun everyPageShowsAFailedReadWithRetryThatReadsAgain() {
        val calls = Calls()
        var page by mutableStateOf("Health")
        show({ page }, { NativeServerState(error = "Synthetic server failure") }, calls)
        pages.forEachIndexed { index, name ->
            compose.runOnIdle { page = name }
            compose.onNodeWithTag("server-error").assertIsDisplayed()
            compose.onNodeWithText("Synthetic server failure").assertIsDisplayed()
            compose.onNodeWithTag("server-retry").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("Retry on $name asks the page to read again", index + 1, calls.check) }
        }
    }

    @Test fun everyPageShowsANoticeRowOnlyWhenThereIsANotice() {
        var page by mutableStateOf("Health")
        var state by mutableStateOf(NativeServerState(notice = "Synthetic server notice"))
        show({ page }, { state }, Calls())
        for (name in pages) {
            compose.runOnIdle { page = name; state = NativeServerState(notice = "Synthetic server notice") }
            compose.onNodeWithTag("server-notice").assertIsDisplayed()
            compose.onNodeWithText("Synthetic server notice").assertIsDisplayed()
            compose.runOnIdle { state = NativeServerState() }
            compose.onNodeWithTag("server-notice").assertDoesNotExist()
            compose.onNodeWithTag("server-error").assertDoesNotExist()
        }
    }

    @Test fun healthThatCouldNotBeReadSaysSoAndShowsNoMetrics() {
        show({ "Health" }, { NativeServerState(error = "Synthetic server failure") }, Calls())
        compose.onNodeWithTag("server-health-hero").assertIsDisplayed()
        compose.onNodeWithText("Health isn't available right now.").assertIsDisplayed()
        compose.onNodeWithTag("server-health-metrics").assertDoesNotExist()
        compose.onNodeWithTag("server-components").assertDoesNotExist()
    }

    @Test fun logChipsNameTheFileAndTheSearchFieldReportsTyping() {
        val calls = Calls()
        var state by mutableStateOf(NativeServerState(logFile = "errors", logs = listOf("one line")))
        // The caller keeps the typed term, as the app's own state does; a page that is told nothing shows the field empty again.
        show({ "Logs" }, { state }, calls) { term -> calls.searches += term; state = state.copy(logSearch = term) }
        compose.onNodeWithText("Errors").assertIsSelected()
        compose.onNodeWithText("Gateway").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(listOf("gateway"), calls.files) }
        compose.onNode(hasSetTextAction() and hasText("Search log entries")).performTextInput("timeout")
        compose.runOnIdle { assertEquals("timeout", calls.searches.last()); assertEquals("Opening the page read the lines already", 0, calls.check) }
        // Typing narrows the lines: once it pauses, the page asks to read them again, once.
        compose.waitUntil(5_000) { calls.check == 1 }
        compose.runOnIdle { state = state.copy(logFile = "gateway", loading = true) }
        compose.onNodeWithText("Gateway").assertIsSelected()
        compose.onNodeWithText("Errors").assertIsNotEnabled()
    }

    @Test fun logLinesShowAnEmptyAnswerAndPlaceholdersWhileTheyLoad() {
        var state by mutableStateOf(NativeServerState(logs = listOf("first synthetic line", "second synthetic line")))
        show({ "Logs" }, { state }, Calls())
        compose.onNodeWithText("first synthetic line").assertIsDisplayed()
        compose.onNodeWithText("second synthetic line").assertIsDisplayed()
        compose.runOnIdle { state = NativeServerState(logSearch = "nothing like it") }
        compose.onNodeWithText("No matching log entries.").assertIsDisplayed()
        compose.onNodeWithTag("settings-skeleton").assertDoesNotExist()
        compose.runOnIdle { state = NativeServerState(loading = true) }
        compose.onNodeWithTag("settings-skeleton").assertIsDisplayed()
        compose.onNodeWithText("No matching log entries.").assertDoesNotExist()
        compose.runOnIdle { state = NativeServerState(loading = true, logs = listOf("kept line")) }
        compose.onNodeWithText("kept line").assertIsDisplayed()
        compose.onNodeWithTag("settings-refreshing").assertExists()
    }

    @Test fun anUpdateTheServerCannotApplyShowsItsCommandAndCopyPutsItOnTheClipboard() {
        val calls = Calls()
        val command = "hermes update --yes"
        show({ "Server updates" }, { NativeServerState(update = json("update_available" to true, "can_apply" to false, "current_version" to "1.0.0", "update_command" to command)) }, calls)
        compose.onNodeWithTag("server-update-hero").assertIsDisplayed()
        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNodeWithText("Update on your computer").assertIsDisplayed()
        compose.onNodeWithTag("server-update-apply").assertDoesNotExist()
        compose.onNodeWithTag("server-update-command").assertIsDisplayed()
        compose.onNodeWithText(command).assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy").performClick()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        compose.runOnUiThread { assertEquals(command, clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
        compose.runOnIdle { assertEquals("Copy never asks the server to update", 0, calls.apply) }
    }

    @Test fun anUpdateTheServerCanApplyShowsNoCommandAndOffersTheUpdate() {
        val calls = Calls()
        show({ "Server updates" }, { NativeServerState(update = json("update_available" to true, "can_apply" to true, "current_version" to "1.0.0", "update_command" to "hermes update --yes")) }, calls)
        compose.onNodeWithTag("server-update-command").assertDoesNotExist()
        compose.onNodeWithTag("server-update-apply").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, calls.apply) }
    }

    @Test fun anUnconfirmedUpdateOffersTheReviewAndNothingElseToApply() {
        val calls = Calls()
        val state = NativeServerState(
            requestedAt = "2026-11-01T00:00:00Z", action = json("running" to false),
            update = json("update_available" to true, "can_apply" to true, "current_version" to "1.0.0"),
        )
        assertTrue("The scenario is an unconfirmed update that may be reviewed", state.canReviewUnknown)
        show({ "Server updates" }, { state }, calls)
        compose.onNodeWithText("Not confirmed yet").assertIsDisplayed()
        compose.onNodeWithTag("server-update-apply").assertDoesNotExist()
        compose.onNodeWithTag("server-review").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, calls.review); assertEquals(0, calls.apply) }
    }

    @Test fun anUnconfirmedUpdateStillRunningOrUnknownToTheServerOffersNoReview() {
        val calls = Calls()
        val state = NativeServerState(requestedAt = "2026-11-01T00:00:00Z", update = json("update_available" to true, "can_apply" to true))
        assertFalse("Without the server's own answer the update cannot be reviewed", state.canReviewUnknown)
        show({ "Server updates" }, { state }, calls)
        compose.onNodeWithText("Not confirmed yet").assertIsDisplayed()
        compose.onNodeWithTag("server-review").assertDoesNotExist()
        compose.onNodeWithTag("server-check").assertIsDisplayed()
    }

    @Test fun checkpointsShowTheirSizeTheListAnEmptyAnswerAndPlaceholders() {
        var state by mutableStateOf(NativeServerState(checkpointBytes = 2048, checkpoints = listOf(NativeCheckpoint("synthetic-session", 3, 2048))))
        show({ "Session checkpoints" }, { state }, Calls())
        compose.onNodeWithTag("server-checkpoint-metrics").assertIsDisplayed()
        compose.onNodeWithTag("server-checkpoint-synthetic-session").assertIsDisplayed().assertTextContains("synthetic-session")
        compose.runOnIdle { state = NativeServerState() }
        compose.onNodeWithText("No session checkpoints are stored for this profile.").assertIsDisplayed()
        compose.runOnIdle { state = NativeServerState(loading = true) }
        compose.onNodeWithTag("settings-skeleton").assertIsDisplayed()
        compose.onNodeWithTag("server-checkpoint-metrics").assertDoesNotExist()
    }
}
