package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.hermes.android.core.json
import org.json.JSONArray
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
        compose.onNodeWithText("Read-only status from this conversation").assertExists()
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
}
