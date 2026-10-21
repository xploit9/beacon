package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.hermes.android.ui.*
import dev.hermes.android.ui.sphere.MorphingSphere
import dev.hermes.android.ui.sphere.SphereState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Presentation checks with local state and callbacks only. Nothing here talks to a backend,
 * and the ViewModel-bound composer (attachments, model, approvals, voice) is not exercised.
 */
class ChatMigrationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun tool(name: String, vararg extra: Pair<String, Any>) = JSONObject().put("tid", "t-$name").put("name", name).also { o -> extra.forEach { o.put(it.first, it.second) } }

    private fun expandModalSheet() {
        val expand = compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Expand), useUnmergedTree = true)
        if (expand.fetchSemanticsNodes().isNotEmpty()) expand.onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Expand) { it() }
        compose.waitForIdle()
    }

    @Test fun historyToolCardIsFoldedThenShowsArgumentsAndResult() {
        compose.setContent { MaterialTheme { ToolCard(tool("terminal", "args" to "ls -la", "result" to "total 0")) } }
        compose.onNodeWithTag("tool-card-details").assertDoesNotExist()
        compose.onNodeWithTag("tool-card-toggle").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("tool-card-details").assertIsDisplayed()
        compose.onNodeWithText("ls -la").assertIsDisplayed()
        compose.onNodeWithText("total 0").assertIsDisplayed()
        compose.onNodeWithTag("tool-card-status", useUnmergedTree = true).assertTextContains("Completed", substring = true)
        compose.onNodeWithTag("tool-card-toggle").performClick()
        compose.onNodeWithTag("tool-card-details").assertDoesNotExist()
    }

    @Test fun toolWithoutAResultIsNeverShownAsCompleted() {
        compose.setContent { MaterialTheme { ToolCard(tool("read_file")) } }
        compose.onNodeWithTag("tool-card-toggle").performClick()
        compose.onNodeWithTag("tool-card-status", useUnmergedTree = true).assertTextContains("No result recorded", substring = true)
        compose.onAllNodesWithText("Status: Completed").assertCountEquals(0)
    }

    @Test fun failedToolShowsItsErrorFromIsError() {
        compose.setContent { MaterialTheme { ToolCard(tool("terminal", "is_error" to true, "result" to "exit 1")) } }
        compose.onNodeWithTag("tool-card-toggle").performClick()
        compose.onNodeWithTag("tool-card-status", useUnmergedTree = true).assertTextContains("Failed", substring = true)
        compose.onNodeWithText("exit 1").assertIsDisplayed()
    }

    @Test fun isErrorFalseIsNotFailureAndEmptyExplicitCompletionIsCompleted() {
        compose.setContent { MaterialTheme { ToolCard(tool("terminal", "result" to "", "is_error" to false)) } }
        compose.onNodeWithTag("tool-card-toggle").performClick()
        compose.onNodeWithTag("tool-card-status", useUnmergedTree = true).assertTextContains("Completed", substring = true)
        compose.onNodeWithTag("tool-card-empty-result", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun storedToolWithNoMarkerIsNotRunningEvenWhileSomethingElseStreams() {
        compose.setContent { MaterialTheme { Column {
            MessageActivity("", listOf(tool("read_file")))
            MessageActivity("", listOf(tool("terminal")), status = "Working", streaming = true)
        } } }
        compose.onAllNodesWithTag("tool-card-details").assertCountEquals(0) // the live step is one folded row
        compose.onNodeWithTag("tool-steps").performClick()
        compose.onAllNodesWithTag("tool-card-toggle")[0].performClick()
        compose.onAllNodesWithTag("tool-card-toggle")[1].performClick()
        val statuses = compose.onAllNodesWithTag("tool-card-status", useUnmergedTree = true)
        statuses.assertCountEquals(2)
        statuses[0].assertTextContains("No result recorded", substring = true)
        statuses[1].assertTextContains("Running", substring = true)
    }

    @Test fun storedRunningStatusWithNothingTerminalIsNoResultNotRunning() {
        compose.setContent { MaterialTheme { MessageActivity("", listOf(tool("terminal", "status" to "running"), tool("web_search", "status" to "generating"))) } }
        compose.onAllNodesWithTag("tool-card-details").assertCountEquals(0) // a stored "running" call does not auto-open
        compose.onAllNodesWithTag("tool-card-toggle")[0].performClick()
        compose.onAllNodesWithTag("tool-card-toggle")[1].performClick()
        val statuses = compose.onAllNodesWithTag("tool-card-status", useUnmergedTree = true)
        statuses[0].assertTextContains("No result recorded", substring = true)
        statuses[1].assertTextContains("No result recorded", substring = true)
    }

    @Test fun completionMergedIntoAStaleRunningStartIsCompleted() {
        compose.setContent { MaterialTheme { MessageActivity("", listOf(tool("terminal", "status" to "running", "result" to "total 0", "is_error" to false))) } }
        compose.onNodeWithTag("tool-card-toggle").performClick()
        compose.onNodeWithTag("tool-card-status", useUnmergedTree = true).assertTextContains("Completed", substring = true)
    }

    @Test fun liveToolWithOnlyAPreviewStaysRunningAndLabelsItPartial() {
        compose.setContent { MaterialTheme { MessageActivity("", listOf(tool("terminal", "status" to "running", "preview" to "partial output")), status = "Working", streaming = true) } }
        compose.onNodeWithTag("tool-steps").performClick()
        compose.onNodeWithTag("tool-card-toggle").performClick()
        compose.onNodeWithTag("tool-card-status", useUnmergedTree = true).assertTextContains("Running", substring = true)
        compose.onNodeWithTag("tool-card-preview", useUnmergedTree = true).assertTextEquals("partial output")
        compose.onAllNodesWithTag("tool-card-result", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun finishedToolShowsNativeDurationAndRecordedMessageTimeOnly() {
        val recorded = 1_776_421_800L
        val time = messageTimeLabel(recorded)!!
        compose.setContent { MaterialTheme { MessageActivity("", listOf(tool("terminal", "result" to "ok", "duration_s" to 3.14), tool("read_file")), messageTime = recorded) } }
        val meta = compose.onAllNodesWithTag("tool-card-meta", useUnmergedTree = true)
        meta.assertCountEquals(2)
        meta[0].assertTextEquals("3.1s · $time")
        // A call with no completion gets neither the message time nor a duration.
        meta[1].assertTextEquals("No result")
    }

    @Test fun finishedToolWithoutAnyRecordedTimeShowsNoMeta() {
        compose.setContent { MaterialTheme { ToolCard(tool("terminal", "result" to "ok")) } }
        compose.onAllNodesWithTag("tool-card-meta", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun storedAndLiveToolsInOneTranscriptKeepTheirOwnStates() {
        compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Column {
            MessageActivity("", listOf(tool("read_file")))
            MessageActivity("", listOf(tool("terminal")), status = "Working", streaming = true)
        } } }
        compose.onNodeWithTag("message-activity-status").assertTextEquals("Working")
        // Both stay folded; the live one sits behind its current-step row.
        compose.onAllNodesWithTag("tool-card-details").assertCountEquals(0)
        compose.onNodeWithTag("tool-steps").performClick()
        compose.onAllNodesWithTag("tool-card-toggle")[0].performClick()
        compose.onAllNodesWithTag("tool-card-toggle")[1].performClick()
        val statuses = compose.onAllNodesWithTag("tool-card-status", useUnmergedTree = true)
        statuses.assertCountEquals(2)
        statuses[0].assertTextContains("No result recorded", substring = true)
        statuses[1].assertTextContains("Running", substring = true)
    }

    @Test fun longResultIsReadableInFullWithNewlinesIntact() {
        val long = (1..80).joinToString("\n") { "line $it " + "x".repeat(20) }
        assertTrue(long.length > 700)
        compose.setContent { MaterialTheme { ToolCard(tool("terminal", "args" to "{\"cmd\":\"x\"}", "result" to long)) } }
        compose.onNodeWithTag("tool-card-toggle").performClick()
        val shown = compose.onNodeWithTag("tool-card-result", useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
        assertEquals(long, shown)
        assertTrue(shown.contains('\n'))
        assertTrue(shown.contains("line 80"))
    }

    @Test fun largeToolResultShowsMoreAndShowLessRestoresTheFirstPage() {
        val long = "x".repeat(ToolDetailPageSize + 500)
        compose.setContent { MaterialTheme { ToolCard(tool("terminal", "result" to long)) } }
        compose.onNodeWithTag("tool-card-toggle").performClick()
        val shown = { compose.onNodeWithTag("tool-card-result", useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text } }
        val firstPage = shown()
        assertTrue(firstPage.startsWith(long.take(ToolDetailPageSize)))
        assertTrue(firstPage.endsWith("500 more characters not shown"))
        compose.onNodeWithTag("tool-card-result-show-more").assertExists().performClick()
        assertEquals(long, shown())
        compose.onNodeWithTag("tool-card-result-show-less").assertExists().performClick()
        assertEquals(firstPage, shown())
    }

    @Test fun matchedToolResultAppearsInItsCardWithOutcomeAndCopyAction() {
        val call = tool("terminal", "tool_call_id" to "call-1")
        val result = JSONObject().put("id", "tool-result-1").put("role", "tool").put("tool_call_id", "call-1").put("tool_name", "terminal").put("content", "saved output")
        val messages = listOf(
            JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(call)),
            result,
            JSONObject().put("id", "tool-result-orphan").put("role", "tool").put("tool_call_id", "missing-call").put("tool_name", "read_file").put("content", "orphan output"),
        )
        val cards = listOf(
            Message("assistant-1", "assistant", "", tools = listOf(call)),
            Message("tool-result-1", "tool", "saved output"),
            Message("tool-result-orphan", "tool", "orphan output"),
        )
        val folded = foldToolResults(messages, cards)
        assertEquals(setOf("tool-result-1"), folded.foldedMessageIds)
        assertEquals("Terminal result", toolResultTitles(listOf(result))["tool-result-1"])
        var copied = ""

        compose.setContent { MaterialTheme { MessageActivity("", listOf(call), toolResultsByCallId = folded.byCallId, onCopyResult = { copied = it }) } }
        compose.onNode(hasTestTag("tool-card") and hasContentDescription("Terminal, Completed"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("tool-card-toggle").performClick()
        compose.onNodeWithTag("tool-card-status", useUnmergedTree = true).assertTextEquals("Status: Completed")
        compose.onNodeWithTag("tool-card-result", useUnmergedTree = true).assertTextEquals("saved output")
        compose.onNodeWithTag("tool-card-result-copy", useUnmergedTree = true).assertContentDescriptionEquals("Copy result").performClick()
        assertEquals("saved output", copied)
    }

    @Test fun dispatchedDelegationReadsAsDispatchedNotCompleted() {
        compose.setContent { MaterialTheme { ToolCard(tool("delegate_task", "status" to "completed", "result" to """{"status":"dispatched"}""")) } }
        compose.onNodeWithTag("tool-card-meta", useUnmergedTree = true).assertTextEquals("Dispatched")
        compose.onNodeWithTag("tool-card-toggle").performClick()
        compose.onNodeWithTag("tool-card-status", useUnmergedTree = true).assertTextContains("Dispatched", substring = true)
        compose.onAllNodesWithText("Status: Completed").assertCountEquals(0)
    }

    @Test fun nativeDurationIsShownAndNothingIsInventedWithoutIt() {
        compose.setContent { MaterialTheme { MessageActivity("", listOf(tool("terminal", "result" to "ok", "duration_s" to 3.14), tool("read_file", "result" to "ok"))) } }
        compose.onAllNodesWithTag("tool-card-meta", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("tool-card-meta", useUnmergedTree = true).assertTextContains("3.1s", substring = true)
    }

    @Test fun runningStepsStayCompactUntilOpenedAndArgumentsStayFolded() {
        val reported = mutableListOf<Boolean>()
        compose.setContent { MaterialTheme { MessageActivity("", listOf(tool("browser_navigate", "status" to "running", "arguments" to "{\"url\":\"https://example.test\"}")), status = "Working", streaming = true, onToolExpanded = { reported += it }) } }
        compose.onNodeWithTag("tool-steps").assertIsDisplayed()
        compose.onNodeWithTag("tool-card").assertDoesNotExist()
        compose.onNodeWithTag("tool-card-arguments").assertDoesNotExist()
        compose.onNodeWithTag("tool-steps").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tool-card").assertIsDisplayed()
        compose.onNodeWithTag("tool-card-arguments").assertDoesNotExist()
        compose.onNodeWithTag("tool-card-toggle").performClick()
        compose.onNodeWithTag("tool-card-arguments").assertIsDisplayed()
        assertEquals(listOf(true, true), reported)
    }

    @Test fun finishedStepsFoldIntoOneRowThatOpensToEachNamedCard() {
        compose.setContent { MaterialTheme { MessageActivity("", listOf(tool("terminal", "result" to "a"), tool("read_file", "result" to "b"), tool("web_search", "result" to "c"))) } }
        // Three or more finished steps fold, so the answer sits under the question.
        compose.onAllNodesWithTag("tool-card").assertCountEquals(0)
        compose.onNodeWithText("3 steps · Terminal, Read file, Web search").assertIsDisplayed()
        compose.onNodeWithTag("tool-steps").performClick()
        compose.onAllNodesWithTag("tool-card").assertCountEquals(3)
        compose.onNodeWithText("Terminal").assertIsDisplayed()
        compose.onNodeWithText("Read file").assertIsDisplayed()
        compose.onNodeWithText("Web search").assertIsDisplayed()
        compose.onNodeWithTag("tool-steps").assertContentDescriptionContains("3 steps", substring = true)
    }

    @Test fun toolCardsFitANarrowPhone() {
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) { MessageActivity("", listOf(tool("a_very_long_tool_name_for_a_narrow_screen", "result" to "x"))) } } }
        compose.onNodeWithTag("tool-card").assertIsDisplayed()
        compose.onNodeWithTag("tool-card-toggle").assertIsDisplayed()
        val card = compose.onNodeWithTag("tool-card").getUnclippedBoundsInRoot()
        val toggle = compose.onNodeWithTag("tool-card-toggle").getUnclippedBoundsInRoot()
        val cardWidth = card.right - card.left
        assertTrue("tool card is $cardWidth wide, wider than the 320dp phone", cardWidth <= 320.dp)
        // The long name must ellipsize inside the card instead of pushing the toggle row past its edges.
        assertTrue("toggle row starts left of the card", toggle.left >= card.left)
        assertTrue("toggle row ends right of the card", toggle.right <= card.right)
        assertTrue("toggle row is not 48dp tall", toggle.bottom - toggle.top >= 48.dp)
    }

    private fun snap(name: String, outcome: ToolOutcome) = ToolSnapshot(name, name, outcome)

    @Test fun activityStripOpensTheSheetWithOnlyRealWork() {
        val activity = chatActivity(listOf(snap("terminal", ToolOutcome.Running), snap("read_file", ToolOutcome.Completed)), queued = 1, approval = true, question = false)
        var open by mutableStateOf(false)
        var removed = -1
        compose.setContent { MaterialTheme {
            ChatActivityStrip(activity) { open = true }
            if (open) ChatActivitySheet(activity, listOf("next message"), onRemoveQueued = { removed = it }, onDismiss = { open = false })
        } }
        compose.waitForIdle() // no ticking clock: the screen must settle with running work shown
        compose.onNodeWithTag("chat-activity-strip").assertHeightIsAtLeast(48.dp).assertTextContains("1 running · 1 queued · Waiting for you", substring = true)
        compose.onNodeWithTag("chat-activity-sheet").assertDoesNotExist()
        compose.onNodeWithTag("chat-activity-strip").performClick()
        compose.onNodeWithTag("chat-activity-sheet").assertIsDisplayed()
        expandModalSheet()
        compose.onNodeWithText("Read-only live details from this chat").assertIsDisplayed()
        // The sheet scrolls, so bring each section into view before asserting on it.
        compose.onNodeWithText("Terminal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Approval needed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("next message").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("activity-process-unavailable").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Stop").assertCountEquals(0)
        compose.onNodeWithContentDescription("Remove queued message").performScrollTo().performClick()
        assertEquals(0, removed)
        compose.onNodeWithTag("chat-activity-close").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("chat-activity-sheet").assertDoesNotExist()
    }

    @Test fun idleSheetSaysNothingIsActiveAndStillNamesTheMissingProcessList() {
        compose.setContent { MaterialTheme { ChatActivitySheet(chatActivity(emptyList(), 0, false, false), emptyList(), onRemoveQueued = {}, onDismiss = {}) } }
        compose.onNodeWithTag("chat-activity-empty").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("activity-process-unavailable").performScrollTo().assertIsDisplayed()
    }

    @Test fun clarificationAloneOpensTheSheetAndNeverShowsFinishedToolsAsRunning() {
        val activity = chatActivity(listOf(snap("read_file", ToolOutcome.Completed), snap("terminal", ToolOutcome.NoResult)), queued = -2, approval = false, question = true)
        compose.setContent { MaterialTheme { ChatActivitySheet(activity, emptyList(), onRemoveQueued = {}, onDismiss = {}) } }
        compose.onNodeWithText("Question waiting").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Running", substring = true).assertCountEquals(0)
        compose.onNodeWithText("No result recorded", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun sphereBackdropSitsUnderContentAndDoesNotConsumeClicks() {
        var clicked by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize()) {
                MorphingSphere(Modifier.fillMaxSize().alpha(.65f).testTag("chat-sphere"), state = SphereState.Thinking, motionVisible = false)
                Button(onClick = { clicked = true }) { Text("Transcript action") }
            }
        } }
        compose.onNodeWithTag("chat-sphere").assertIsDisplayed()
        compose.onNodeWithText("Transcript action").performClick()
        assertTrue("Transcript controls remain interactive above the sphere", clicked)
    }

    @Test fun relayLicenseIsSelectableFromTheLicenseDocumentChoice() {
        var selected by mutableStateOf("hermes-LICENSE.txt")
        compose.setContent { MaterialTheme {
            Choice("Document", selected, licenseDocuments, { selected = it })
        } }
        compose.onNodeWithText("hermes-LICENSE.txt", substring = true).performClick()
        compose.onNodeWithTag("choice-option:Document:hermes-relay-LICENSE.txt").assertIsDisplayed().performClick()
        assertEquals("hermes-relay-LICENSE.txt", selected)
    }
}
