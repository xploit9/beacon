package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.hermes.android.core.json
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BackgroundActivityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sheetShowsRealRowsAndRemainsReviewableAfterWorkEndsOrDisconnects() {
        var current by mutableStateOf(conversationBackgroundActivity(
            json("subagents" to JSONArray().put(json("subagent_id" to "child-a", "goal" to "Check synthetic notes", "status" to "running", "started_at" to 100))),
            json("processes" to JSONArray().put(json("session_id" to "process-a", "command" to "synthetic build", "status" to "running", "started_epoch" to 110))), 150))
        var open by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            ChatActivityStrip(current) { open = true }
            if (open) ChatActivitySheet(current) { open = false }
        } }
        compose.onNodeWithTag("chat-activity-strip").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Read-only status from this conversation").assertDoesNotExist()
        compose.onNodeWithText("Check synthetic notes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Running · 50s elapsed").assertExists()
        compose.onNodeWithText("synthetic build").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("watch", substring = true).assertCountEquals(0)
        compose.runOnIdle { current = reconcileBackgroundActivity(current, BackgroundActivitySummary(available = false)) }
        compose.onAllNodesWithText("Status unknown", substring = true).assertCountEquals(2)
        compose.runOnIdle { current = reconcileBackgroundActivity(current, conversationBackgroundActivity(json("subagents" to JSONArray()), json("processes" to JSONArray()), 200)) }
        compose.onNodeWithTag("chat-activity-strip").assertDoesNotExist()
        compose.onNodeWithTag("chat-activity-sheet").assertIsDisplayed()
        compose.onAllNodesWithText("No longer listed", substring = true).assertCountEquals(2)
        compose.onNodeWithTag("chat-activity-close").performClick()
        compose.onNodeWithTag("chat-activity-sheet").assertDoesNotExist()
    }

    @Test fun aLongRosterScrollsAndKeepsStatusAndCloseControlsAccessible() {
        val rows = JSONArray().apply { repeat(40) { index -> put(json("subagent_id" to "child-$index", "goal" to "Synthetic worker $index", "status" to "running")) } }
        val current = conversationBackgroundActivity(json("subagents" to rows), json("processes" to JSONArray()))
        compose.setContent { MaterialTheme { ChatActivitySheet(current) {} } }
        compose.onNodeWithText("Synthetic worker 39").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chat-activity-close").performScrollTo().assertHeightIsAtLeast(48.dp).assertIsDisplayed()
    }

    private fun process(status: String, completion: String? = null) = conversationBackgroundActivity(
        json("subagents" to JSONArray()),
        json("processes" to JSONArray().put(json("session_id" to "process-a", "command" to "synthetic build", "status" to status,
            "exit_code" to 1, "completion_reason" to completion, "started_epoch" to 110, "exited_at" to 140))), 150)

    @Test fun aRunningRowStopsOnlyAfterItsConfirmation() {
        var stops = 0
        compose.setContent { MaterialTheme { ChatActivitySheet(process("running"), stopBackground = { stops += 1; null }, onDismiss = {}) } }
        compose.onNodeWithTag("chat-activity-stop-process:process-a").assertIsDisplayed().performClick()
        compose.onNodeWithText("Stop this background process?").assertIsDisplayed()
        compose.onNodeWithText("synthetic build").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Stop this background process?").assertDoesNotExist()
        assertEquals("Cancel stops nothing", 0, stops)
        compose.onNodeWithTag("chat-activity-stop-process:process-a").performClick()
        compose.onNodeWithTag("chat-activity-stop-confirm").performClick()
        compose.waitForIdle()
        assertEquals(1, stops)
    }

    @Test fun aFinishedRowNeverOffersStop() {
        compose.setContent { MaterialTheme { ChatActivitySheet(process("exited", "exited"), stopBackground = { null }, onDismiss = {}) } }
        compose.onNodeWithTag("chat-activity-stop-process:process-a").assertDoesNotExist()
        compose.onNodeWithText("Failed", substring = true).assertExists()
    }

    @Test fun aSheetShownWithoutAStopCallbackStaysReadOnly() {
        compose.setContent { MaterialTheme { ChatActivitySheet(process("running"), onDismiss = {}) } }
        compose.onNodeWithText("synthetic build").assertExists()
        compose.onAllNodesWithTag("chat-activity-stop-process:process-a").assertCountEquals(0)
    }

    @Test fun aStopInFlightShowsStoppingAndCannotBeRepeated() {
        val gate = CompletableDeferred<String?>()
        compose.setContent { MaterialTheme { ChatActivitySheet(process("running"), stopBackground = { gate.await() }, onDismiss = {}) } }
        compose.onNodeWithTag("chat-activity-stop-process:process-a").performClick()
        compose.onNodeWithTag("chat-activity-stop-confirm").performClick()
        compose.onNodeWithText("Stopping…").assertExists()
        compose.onNodeWithTag("chat-activity-stop-process:process-a").assertIsNotEnabled()
        compose.runOnIdle { gate.complete(null) }
        compose.waitForIdle()
        compose.onAllNodesWithText("Stopping…").assertCountEquals(0)
        compose.onNodeWithTag("chat-activity-stop-process:process-a").assertIsEnabled()
    }

    @Test fun aRefusedStopSaysItMayStillBeRunningAndLeavesTheRowStoppable() {
        compose.setContent { MaterialTheme { ChatActivitySheet(process("running"), stopBackground = { "Couldn't stop this process. It may still be running." }, onDismiss = {}) } }
        compose.onNodeWithTag("chat-activity-stop-process:process-a").performClick()
        compose.onNodeWithTag("chat-activity-stop-confirm").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Couldn't stop this process. It may still be running.").assertExists()
        compose.onNodeWithTag("chat-activity-stop-process:process-a").assertIsEnabled().performClick()
        compose.onNodeWithText("Stop this background process?").assertIsDisplayed()
    }

    @Test fun theStopActionAndItsConfirmationStayReachableInRtlAtLargeText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { ChatActivitySheet(process("running"), stopBackground = { null }, onDismiss = {}) }
            }
        }
        compose.onNodeWithTag("chat-activity-stop-process:process-a").assertIsDisplayed().performClick()
        compose.onNodeWithText("Stop this background process?").assertIsDisplayed()
        compose.onNodeWithTag("chat-activity-stop-confirm").assertIsDisplayed()
    }
}
