package dev.hermes.android.uiux.modules

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.hermes.android.data.BotModeMember
import dev.hermes.android.data.NativeBotModeState
import dev.hermes.android.ui.ScheduleMenu
import dev.hermes.android.ui.ScheduleRunNow
import dev.hermes.android.ui.ScheduleStatusChip
import dev.hermes.android.ui.SharedSpaceChat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The modules' new parts on local fixtures (no backend): the schedule card's chip, Run now and overflow, and the shared space's chips and empty page. */
class ModulesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val roster = listOf(BotModeMember("coder", "coder", "coder"), BotModeMember("default", "default", "hermes"))
    private val profiles = listOf(JSONObject().put("name", "default").put("display_name", "Personal"))
    private var state by mutableStateOf(NativeBotModeState(selectedSpace = "room", roster = roster, members = listOf("coder", "default")))

    private fun showSpace() = compose.setContent {
        MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                SharedSpaceChat(state, profiles, onDraft = { state = state.copy(draft = it) }, onSend = { }, onAction = { _, _ -> }, onReviewProfiles = { }) { text, _ -> Text(text) }
            }
        }
    }

    @Test fun aMentionChipShowsTheDisplayNameAndTheMessageGetsTheHandle() {
        showSpace()
        compose.onNodeWithTag("space-chip:hermes").assertIsDisplayed()
        compose.onNodeWithText("Personal").assertIsDisplayed()
        compose.onNodeWithTag("space-chip:all").assertIsDisplayed()
        compose.onNodeWithTag("space-chip:hermes").performClick()
        compose.waitForIdle()
        assertEquals("@hermes ", state.draft)
    }

    @Test fun anEmptySpaceShowsTheBotsAndTheSharedEmptyState() {
        showSpace()
        compose.onNodeWithTag("space-empty").assertIsDisplayed()
        compose.onNodeWithText("No messages yet").assertIsDisplayed()
        compose.onNodeWithText("Send the bots a task to start the discussion.").assertIsDisplayed()
    }

    private fun showSchedule(paused: Boolean, calls: MutableList<String>) = compose.setContent {
        MaterialTheme {
            Column {
                ScheduleStatusChip(JSONObject().put("id", "a").put("state", if (paused) "paused" else "scheduled").put("next_run_at", "2026-11-01T08:00:00Z"))
                ScheduleMenu("a", paused, ended = false, canEdit = true, enabled = true, onPause = { calls += "pause" }, onEdit = { calls += "edit" }, onHistory = { calls += "history" }, onDelete = { calls += "delete" })
                ScheduleRunNow("a", true) { calls += "run" }
            }
        }
    }

    @Test fun theScheduleCardHasOneLabeledRunNowAndAnOverflowWithDeleteLast() {
        val calls = mutableListOf<String>()
        showSchedule(paused = false, calls)
        compose.onNodeWithTag("schedule-status:a").assertIsDisplayed()
        compose.onNodeWithText("Active").assertIsDisplayed()
        compose.onNodeWithText("Run now").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("schedule-menu:a").assertHeightIsAtLeast(48.dp).performClick()
        val tops = listOf("edit", "history", "pause", "delete").map { name ->
            compose.onNodeWithTag("schedule-action:a:$name").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("Edit, Run history, Pause and Delete run top to bottom: $tops", tops == tops.sorted())
        compose.onNodeWithTag("schedule-action:a:pause").performClick()
        assertEquals(listOf("pause"), calls)
        compose.onNodeWithText("Run now").performClick()
        assertEquals(listOf("pause", "run"), calls)
    }

    @Test fun aPausedScheduleOffersResumeAndDeleteAsksTheCaller() {
        val calls = mutableListOf<String>()
        showSchedule(paused = true, calls)
        compose.onNodeWithText("Paused").assertIsDisplayed()
        compose.onNodeWithTag("schedule-menu:a").performClick()
        compose.onNodeWithText("Resume").assertIsDisplayed()
        compose.onNodeWithTag("schedule-action:a:delete").performClick()
        assertEquals(listOf("delete"), calls)
    }
}
