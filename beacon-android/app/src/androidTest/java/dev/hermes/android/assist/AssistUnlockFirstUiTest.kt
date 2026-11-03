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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * Lane LK: the side button on a locked phone asks for the unlock first. The overlay's own composables run without a voice session:
 * the one line "Unlock your phone first." with its Unlock action (asking again each tap, with no close), a text box with nothing to
 * type, send or dictate, and an unlocked or running conversation's text box exactly as before.
 */
class AssistUnlockFirstUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var unlockTaps = 0
    private var clears = 0
    private var orbTaps = 0
    private val sent = mutableListOf<String>()
    private var dictations = 0

    /** The overlay's stack as a locked phone arranges it: [line] above the text box, [live] the face of a conversation that runs. */
    private fun show(phoneLocked: Boolean, line: OverlayLine? = null, live: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalAssistantName provides "Beacon") {
                    var draft by remember { mutableStateOf(TextFieldValue()) }
                    val clock = remember { mutableFloatStateOf(0f) }
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
                        if (line != null) LineRowBody(line, null, onAction = { unlockTaps++ }, onClear = { clears++ })
                        Pill(PillInput(
                            state = AssistOverlayState(surface = if (live) OverlaySurface.Live else OverlaySurface.Pill, phase = OverlayPhase.Open, line = line),
                            draft = { draft }, onDraft = { draft = it }, screen = false, screenAttached = false, onAttach = {}, heardLevel = { 0f },
                            face = if (live) OrbFace.Listening else OrbFace.Talk, status = if (live) "Listening" else "", voice = { 0f },
                            onSend = { sent += draft.text; draft = TextFieldValue() }, onDictate = { dictations++ }, onOrb = { orbTaps++ },
                            // As the overlay passes it: locked with no conversation running.
                            locked = pillInert(phoneLocked, live),
                        ), remember { FocusRequester() }, motion = false, clock = clock)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test fun theLockedOverlayShowsOneLineAndItsUnlockActionAsksAgainEachTap() {
        show(phoneLocked = true, line = OverlayLine.Unlock)
        compose.onNodeWithTag("assist-line").assertIsDisplayed()
        compose.onNodeWithText("Unlock your phone first.").assertIsDisplayed()
        compose.onNodeWithTag("assist-line-action").assertIsDisplayed()
        compose.onNodeWithText("Unlock").assertIsDisplayed()
        compose.onNodeWithTag("assist-line-action").performClick()
        compose.onNodeWithTag("assist-line-action").performClick()
        assertEquals("every tap asks Android for the unlock again", 2, unlockTaps)
        // Closing the line would leave a locked phone with nothing said: it has no close, and the other lines keep theirs.
        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
        assertEquals(0, clears)
    }

    @Test fun otherLinesKeepTheirClose() {
        show(phoneLocked = false, line = OverlayLine.NotSent)
        compose.onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
        assertEquals(1, clears)
    }

    @Test fun theLockedTextBoxHasNothingToTypeSendOrDictate() {
        show(phoneLocked = true, line = OverlayLine.Unlock)
        compose.onNodeWithTag("assist-pill").assertIsDisplayed()
        compose.onNodeWithTag("assist-field").assertIsNotEnabled()
        // No hint of its own (the line above is the one thing said), no mic, no send.
        compose.onNodeWithText("Ask Beacon").assertDoesNotExist()
        compose.onNodeWithTag("assist-mic").assertDoesNotExist()
        compose.onNodeWithTag("assist-send").assertDoesNotExist()
        assertEquals(emptyList<String>(), sent)
        assertEquals(0, dictations)
        // The orb stays: a tap on it is the owner asking to talk, which on a locked phone asks for the unlock.
        compose.onNodeWithTag("assist-live").assertIsDisplayed().performClick()
        assertEquals(1, orbTaps)
    }

    @Test fun unlockedTheTextBoxIsAsBefore() {
        show(phoneLocked = false)
        compose.onNodeWithTag("assist-field").assertIsEnabled()
        compose.onNodeWithText("Ask Beacon").assertIsDisplayed()
        compose.onNodeWithTag("assist-mic").assertIsDisplayed().performClick()
        assertEquals(1, dictations)
        compose.onNodeWithTag("assist-line").assertDoesNotExist()
    }

    /** A conversation started unlocked is trusted and keeps its text box when the screen locks: typed turns still go into it. */
    @Test fun aRunningConversationKeepsItsTextBoxWhenThePhoneLocks() {
        show(phoneLocked = true, live = true)
        compose.onNodeWithTag("assist-field").assertIsEnabled()
        compose.onNodeWithText("Listening").assertIsDisplayed()
        compose.onNodeWithTag("assist-line").assertDoesNotExist()
        compose.onNodeWithTag("assist-live").performClick()
        assertEquals("the orb still ends it", 1, orbTaps)
    }
}
