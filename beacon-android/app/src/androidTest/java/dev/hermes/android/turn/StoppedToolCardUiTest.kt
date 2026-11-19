package dev.hermes.android.turn

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.hermes.android.ui.ToolCard
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/**
 * TURN-121 follow-up 5, item 52: the row of a tool call Hermes saved as interrupted says Stopped, not Completed.
 * The results are Hermes' own shapes (tools/environments/base.py appends "[Command interrupted]" and returns exit code 130; tools/terminal_tool_result.py saves it as JSON).
 */
class StoppedToolCardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun terminal(result: String) = JSONObject().put("tid", "t-terminal").put("name", "terminal").put("args", "sleep 60").put("result", result)

    @Test fun aCommandStoppedMidRunReadsStopped() {
        compose.setContent { MaterialTheme { ToolCard(terminal("""{"output": "partial\n[Command interrupted]", "exit_code": 130, "error": null}""")) } }
        compose.onNodeWithTag("tool-card-status", useUnmergedTree = true).assertContentDescriptionEquals("Stopped")
    }

    @Test fun aCommandThatFinishedStillReadsCompleted() {
        compose.setContent { MaterialTheme { ToolCard(terminal("""{"output": "done", "exit_code": 0, "error": null}""")) } }
        compose.onNodeWithTag("tool-card-status", useUnmergedTree = true).assertContentDescriptionEquals("Completed")
    }

    @Test fun aMarkerQuotedInOutputThatSucceededIsDataNotAStop() {
        compose.setContent { MaterialTheme { ToolCard(terminal("""{"output": "grep: [Command interrupted]\nnext", "exit_code": 0, "error": null}""")) } }
        compose.onNodeWithTag("tool-card-status", useUnmergedTree = true).assertContentDescriptionEquals("Completed")
    }
}
