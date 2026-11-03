package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hermes.android.BeaconTheme
import dev.hermes.android.voice.AudioRoute
import dev.hermes.android.voice.CallState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Calls start on the speaker: once the service has moved the call there, the call screen's Speaker control names that route and the
 * output list marks it. Synthetic call state only; no call, audio route or device is touched.
 */
@RunWith(AndroidJUnit4::class)
class CallSpeakerRouteUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val earpiece = AudioRoute("earpiece-id", "Earpiece")
    private val speaker = AudioRoute("speaker-id", "Speaker")
    private var call by mutableStateOf(CallState(active = true, state = "listening", callId = "speaker-route", routes = listOf(earpiece, speaker), route = earpiece.id))
    private val picked = mutableListOf<String>()

    private fun show() = compose.setContent {
        NativeLocalization("en") { BeaconTheme(dark = true) {
            LiveCallSurface(call, CallUiActions(mute = {}, camera = {}, flip = {}, allowBubble = {}, end = {}, route = { picked += it }), false, Modifier.fillMaxSize(), onConversation = {}) { Box(it) }
        } }
    }

    private fun route(name: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, name)

    @Test fun theSpeakerControlNamesTheRouteTheCallMovedTo() {
        show()
        compose.onNodeWithTag("beacon-speaker").assertIsEnabled().assert(route("Earpiece"))
        // The service asked for the speaker and Android reported it: the state carries the new route.
        compose.runOnIdle { call = call.copy(route = speaker.id) }
        compose.waitForIdle()
        compose.onNodeWithTag("beacon-speaker").assert(route("Speaker"))
    }

    @Test fun theOwnersChoiceInTheOutputListIsPassedOnAndShown() {
        show()
        compose.runOnIdle { call = call.copy(route = speaker.id) }
        compose.onNodeWithTag("beacon-speaker").performClick()
        compose.onNodeWithText("Earpiece").performClick()
        assertEquals(listOf(earpiece.id), picked)
        // Android reports the owner's route: the control follows it.
        compose.runOnIdle { call = call.copy(route = earpiece.id) }
        compose.waitForIdle()
        compose.onNodeWithTag("beacon-speaker").assert(route("Earpiece"))
    }
}
