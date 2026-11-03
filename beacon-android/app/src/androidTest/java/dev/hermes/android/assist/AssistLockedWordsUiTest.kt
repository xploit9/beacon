package dev.hermes.android.assist

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.hermes.android.ui.LocalAssistantName
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Strix vuln-0005: on a locked phone the overlay draws nothing the owner wrote. The answer card hides the typed question like the
 * reply, behind the same unlock row, and an unsent question in the text box is blank too; both come back when the phone unlocks.
 * The real card and text box run here, without a controller or a voice session.
 */
class AssistLockedWordsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var closes = 0
    private var reveals = 0
    private var phoneLocked by mutableStateOf(true)
    private val question = "What did the clinic say about my biopsy results?"
    private val reply = "Clear. No further tests until March."
    private val finished = OverlayAsk(question, answer = reply, streaming = false)

    private fun card(ask: OverlayAsk = finished) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalAssistantName provides "Beacon") {
                    val clock = remember { mutableFloatStateOf(0f) }
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.Bottom)) {
                        AnswerCard(ask, null, clock, motion = false, locked = phoneLocked, onClose = { closes++ }, onReveal = { reveals++ }, onOpen = {})
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun unlock() {
        compose.runOnIdle { phoneLocked = false }
        compose.mainClock.advanceTimeByFrame()
    }

    /** Nothing of the owner's words is anywhere in the tree, merged or not, for the screen or for a service that reads it. */
    private fun assertNoQuestionAnywhere() {
        compose.onAllNodesWithText("clinic", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("biopsy", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("assist-answer-question", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun lockedTheCardHidesTheQuestionAndTheReplyBehindTheUnlockRow() {
        card()
        compose.onNodeWithTag("assist-answer").assertIsDisplayed()
        assertNoQuestionAnywhere()
        compose.onAllNodesWithText("March", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("assist-unlock-to-see").assertIsDisplayed()
        compose.onNodeWithText("Unlock to see your answer").assertIsDisplayed()
        // The card keeps its two controls: the close, and the unlock that brings the answer back.
        compose.onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
        assertEquals(1, closes)
        compose.onNodeWithTag("assist-unlock").assertIsDisplayed().performClick()
        assertEquals(1, reveals)
    }

    @Test fun lockedWhileTheReplyStreamsTheCardOnlyThinks() {
        card(OverlayAsk(question, answer = "Clear. No further", streaming = true))
        assertNoQuestionAnywhere()
        compose.onAllNodesWithText("No further", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Thinking…").assertIsDisplayed()
    }

    @Test fun unlockedTheQuestionAndTheReplyShowAsBefore() {
        phoneLocked = false
        card()
        compose.onNodeWithTag("assist-answer-question").assertIsDisplayed()
        compose.onNodeWithText(question).assertIsDisplayed()
        compose.onNodeWithTag("assist-answer-text").assertIsDisplayed()
        compose.onNodeWithText(reply).assertIsDisplayed()
        compose.onNodeWithTag("assist-unlock-to-see").assertDoesNotExist()
    }

    @Test fun theQuestionAndTheReplyComeBackWhenThePhoneUnlocks() {
        card()
        assertNoQuestionAnywhere()
        unlock()
        compose.onNodeWithText(question).assertIsDisplayed()
        compose.onNodeWithText(reply).assertIsDisplayed()
        compose.onNodeWithTag("assist-unlock-to-see").assertDoesNotExist()
    }

    /** A question typed but not sent is the owner's words too: the inert text box keeps it for the unlock but draws none of it. */
    private fun textBox(draft: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalAssistantName provides "Beacon") {
                    var text by remember { mutableStateOf(TextFieldValue(draft)) }
                    val clock = remember { mutableFloatStateOf(0f) }
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.Bottom)) {
                        Pill(PillInput(
                            state = AssistOverlayState(surface = OverlaySurface.Pill, phase = OverlayPhase.Open),
                            draft = { text }, onDraft = { text = it }, screen = false, screenAttached = false, onAttach = {}, heardLevel = { 0f },
                            face = OrbFace.Talk, status = "", voice = { 0f }, onSend = {}, onDictate = {}, onOrb = {},
                            // As the overlay passes it: locked with no conversation running.
                            locked = pillInert(phoneLocked, live = false),
                        ), remember { FocusRequester() }, motion = false, clock = clock)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test fun lockedTheTextBoxDrawsNoUnsentQuestionAndItReturnsAfterTheUnlock() {
        val draft = "Is my biopsy result back from the clinic?"
        textBox(draft)
        compose.onNodeWithTag("assist-field").assertIsNotEnabled()
        compose.onAllNodesWithText("biopsy", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("clinic", substring = true, useUnmergedTree = true).assertCountEquals(0)
        unlock()
        compose.onNodeWithText(draft).assertIsDisplayed()
    }
}
