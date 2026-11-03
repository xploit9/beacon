package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hermes.android.BeaconTheme
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lane UX in the live call window: the newest caption is shown whole (the card scrolls, so cutting it hid words the owner could reach),
 * an older one is kept to six lines, and the notice for a screen share that is still starting says so in its own words.
 * Synthetic call state only; no call, sensor or provider is touched.
 */
@RunWith(AndroidJUnit4::class)
class LaneUxLiveCallUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val long = (1..60).joinToString(" ") { "word$it" }

    private fun layoutOf(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    private fun showCaptions(call: CallState, compact: Boolean = false) = compose.setContent {
        NativeLocalization("en") { BeaconTheme(dark = true) { Box(Modifier.requiredSize(360.dp, 800.dp)) { LiveCallCaptions(call, compact = compact) } } }
    }

    private fun twoLines() = CallState(active = true, state = "speaking", callId = "caps", transcripts = listOf(
        CallTranscript("caps:user:1", "user", long),
        CallTranscript("caps:assistant:1", "assistant", long),
    ))

    @Test fun theNewestCaptionIsWholeAndAnOlderOneStopsAtSixLines() {
        showCaptions(twoLines())
        val newest = layoutOf("beacon-assistant-caption")
        assertTrue("${newest.lineCount} lines", newest.lineCount > 6)
        assertFalse((0 until newest.lineCount).any { newest.isLineEllipsized(it) })
        val older = layoutOf("beacon-user-caption")
        assertEquals(6, older.lineCount)
        assertTrue(older.isLineEllipsized(5))
    }

    @Test fun inTheCompactCardTheNewestCaptionIsStillWholeAndAnOlderOneStopsAtTwoLines() {
        showCaptions(twoLines(), compact = true)
        val newest = layoutOf("beacon-assistant-caption")
        assertTrue("${newest.lineCount} lines", newest.lineCount > 2)
        assertFalse((0 until newest.lineCount).any { newest.isLineEllipsized(it) })
        val older = layoutOf("beacon-user-caption")
        assertEquals(2, older.lineCount)
        assertTrue(older.isLineEllipsized(1))
    }

    @Test fun aScreenShareThatIsStillStartingSaysSo() {
        val call = CallState(active = true, state = "listening", callId = "share", screenBusy = true)
        compose.setContent {
            NativeLocalization("en") { BeaconTheme(dark = true) {
                Box(Modifier.requiredSize(360.dp, 800.dp)) {
                    LiveCallSurface(call, CallUiActions(mute = {}, camera = {}, flip = {}, allowBubble = {}, end = {}, route = {}), false, Modifier, onConversation = {}) { Box(it) }
                }
            } }
        }
        compose.onNodeWithText("Starting screen share…").assertIsDisplayed()
        compose.onNodeWithText("Waiting for screen-sharing consent or first frame…").assertDoesNotExist()
    }
}
