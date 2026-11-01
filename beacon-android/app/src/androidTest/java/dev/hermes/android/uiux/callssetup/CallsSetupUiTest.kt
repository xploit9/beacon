package dev.hermes.android.uiux.callssetup

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height as boundsHeight
import androidx.compose.ui.unit.width as boundsWidth
import dev.hermes.android.BeaconTheme
import dev.hermes.android.IncomingCallContent
import dev.hermes.android.ui.CallBubbleAsk
import dev.hermes.android.ui.CallUiActions
import dev.hermes.android.ui.KeepConnectedSetupGroup
import dev.hermes.android.ui.LiveCallSurface
import dev.hermes.android.ui.LocalSetupStep
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.SETUP_CALL_PAGES
import dev.hermes.android.ui.SetupShell
import dev.hermes.android.voice.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Compose checks for the call dock, the incoming sheet's content, the call-bubble question and the setup bar. No call is placed and no permission is touched. */
class CallsSetupUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = CallUiActions(mute = {}, camera = {}, flip = {}, allowBubble = {}, end = {}, route = {})

    // ---- Incoming call sheet ----

    @Test fun incomingSheetShowsThePresenceTheReasonAndTwoRoundButtons() {
        var answered = 0
        var declined = 0
        compose.setContent {
            NativeLocalization("en") { BeaconTheme(dark = true) {
                IncomingCallContent("Your 3 pm is moved", .6f, "", "Answer", true, { answered++ }, { declined++ })
            } }
        }
        compose.onNodeWithTag("beacon-incoming-presence").assertIsDisplayed()
        compose.onNodeWithTag("beacon-incoming-ring").assertIsDisplayed()
        compose.onNodeWithText("Beacon is calling").assertIsDisplayed()
        compose.onNodeWithTag("beacon-incoming-reason").assertIsDisplayed().assertTextContains("Your 3 pm is moved")
        val decline = compose.onNodeWithTag("beacon-incoming-decline").assertIsDisplayed().getUnclippedBoundsInRoot()
        val answer = compose.onNodeWithTag("beacon-incoming-answer").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Decline is at least 64 dp", decline.boundsWidth >= 64.dp && decline.boundsHeight >= 64.dp)
        assertTrue("Answer is at least 64 dp", answer.boundsWidth >= 64.dp && answer.boundsHeight >= 64.dp)
        assertTrue("Decline sits beside Answer, on its start side", decline.right <= answer.left && kotlin.math.abs((decline.top - answer.top).value) < 2f)
        compose.onNodeWithText("Decline").assertIsDisplayed()
        compose.onNodeWithTag("beacon-incoming-decline").performClick()
        compose.onNodeWithTag("beacon-incoming-answer").performClick()
        assertEquals(1, declined)
        assertEquals(1, answered)
    }

    @Test fun incomingSheetHasNoReasonLineWithoutAReasonAndLocksBothButtonsWhileBusy() {
        compose.setContent {
            NativeLocalization("en") { BeaconTheme(dark = true) {
                IncomingCallContent("", 1f, "", "Please wait", false, {}, {})
            } }
        }
        compose.onAllNodesWithTag("beacon-incoming-reason").assertCountEquals(0)
        compose.onNodeWithTag("beacon-incoming-answer").assertIsNotEnabled()
        compose.onNodeWithTag("beacon-incoming-decline").assertIsNotEnabled()
        compose.onNodeWithText("Please wait").assertIsDisplayed()
    }

    // ---- Live call dock ----

    private fun setDock(call: CallState, fontScale: Float = 1f) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            NativeLocalization("en") { BeaconTheme(dark = true) {
                Box(Modifier.width(360.dp).height(900.dp)) {
                    LiveCallSurface(call, actions, false, Modifier.fillMaxSize(), onConversation = {}) { Box(it) }
                }
            } }
        }
    }

    @Test fun hangUpIsTheLargestAndLastControlAndCameraWithShareAreGroupedAbove() {
        setDock(CallState(active = true, state = "listening", callId = "dock-order", overlayPermissionMissing = true))
        val hang = compose.onNodeWithTag("beacon-hang-up").assertIsDisplayed().getUnclippedBoundsInRoot()
        val mute = compose.onNodeWithTag("beacon-mute").getUnclippedBoundsInRoot()
        val speaker = compose.onNodeWithTag("beacon-speaker").getUnclippedBoundsInRoot()
        val camera = compose.onNodeWithTag("beacon-camera").getUnclippedBoundsInRoot()
        val share = compose.onNodeWithTag("beacon-share-screen").getUnclippedBoundsInRoot()
        assertTrue("Hang up is at least 64 dp", hang.boundsWidth >= 64.dp && hang.boundsHeight >= 64.dp)
        assertTrue("Hang up is larger than Mute and Speaker", hang.boundsWidth > mute.boundsWidth && hang.boundsWidth > speaker.boundsWidth)
        assertTrue("Hang up comes last, after Mute and Speaker", hang.left > speaker.left && speaker.left > mute.left)
        assertTrue("Camera and Share are secondary and sit above the main row", camera.bottom <= mute.top && share.bottom <= mute.top)
        assertTrue("Camera comes before Share", camera.left < share.left)
        compose.onNodeWithTag("beacon-camera").assert(hasAnyAncestor(hasTestTag("beacon-call-share-group")))
        compose.onNodeWithTag("beacon-share-screen").assert(hasAnyAncestor(hasTestTag("beacon-call-share-group")))
    }

    @Test fun theDockHasNoBubbleChipAndTheHeaderNoCallTimeCaption() {
        setDock(CallState(active = true, state = "listening", callId = "dock-bubble", overlayPermissionMissing = true))
        compose.onAllNodesWithTag("beacon-allow-bubble").assertCountEquals(0)
        compose.onAllNodesWithTag("beacon-call-timer").assertCountEquals(1)
        compose.onNodeWithTag("beacon-call-timer").assertIsDisplayed()
        compose.onAllNodesWithText("Allow call bubble").assertCountEquals(0)
        compose.onAllNodesWithText("Call time").assertCountEquals(0)
    }

    @Test fun everyControlStaysOnScreenAtTwiceTheTextSize() {
        setDock(CallState(active = true, state = "listening", callId = "dock-large"), fontScale = 2f)
        for (tag in listOf("beacon-camera", "beacon-share-screen", "beacon-mute", "beacon-speaker", "beacon-hang-up")) {
            // 48 dp, allowing for px rounding at large font scales (47.99998 dp is a 48 dp target).
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag stays inside the 360 dp width", bounds.left >= 0.dp && bounds.right <= 360.dp)
            assertTrue("$tag keeps a 48 dp target (${bounds.boundsWidth} x ${bounds.boundsHeight})", bounds.boundsWidth >= 47.5.dp && bounds.boundsHeight >= 47.5.dp)
        }
    }

    // ---- Call bubble question ----

    @Test fun theBubbleIsAskedOnceAfterTheCallEndsAndNeverDuringIt() {
        var call by mutableStateOf(CallState(active = true, state = "listening", callId = "bubble-1", overlayPermissionMissing = true))
        var asked by mutableStateOf(false)
        var allowed = 0
        compose.setContent {
            NativeLocalization("en") { BeaconTheme(dark = true) { CallBubbleAsk(call, asked, onAsked = { asked = true }, onAllow = { allowed++ }) } }
        }
        compose.onAllNodesWithTag("beacon-allow-bubble-dialog").assertCountEquals(0)
        compose.runOnIdle { call = call.copy(active = false, overlayPermissionMissing = false) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("beacon-allow-bubble-dialog").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Show calls in a floating bubble.").assertIsDisplayed()
        compose.onNodeWithTag("beacon-allow-bubble").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("beacon-allow-bubble-dialog").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, allowed)
        assertTrue(asked)
        // Another call without the permission, once it was asked: no second question.
        compose.runOnIdle { call = call.copy(active = true, callId = "bubble-2", overlayPermissionMissing = true) }
        compose.runOnIdle { call = call.copy(active = false, overlayPermissionMissing = false) }
        compose.waitForIdle()
        compose.onAllNodesWithTag("beacon-allow-bubble-dialog").assertCountEquals(0)
    }

    @Test fun skippingTheBubbleQuestionIsRememberedToo() {
        var call by mutableStateOf(CallState(active = true, state = "listening", callId = "bubble-skip", overlayPermissionMissing = true))
        var asked by mutableStateOf(false)
        var allowed = 0
        compose.setContent {
            NativeLocalization("en") { BeaconTheme(dark = true) { CallBubbleAsk(call, asked, onAsked = { asked = true }, onAllow = { allowed++ }) } }
        }
        compose.runOnIdle { call = call.copy(active = false) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("beacon-allow-bubble-skip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("beacon-allow-bubble-skip").performClick()
        compose.waitUntil(5_000) { asked }
        assertEquals(0, allowed)
    }

    // ---- Setup ----

    @Test fun theSetupBarMovesOnEveryPageOfStepThree() {
        var key by mutableStateOf("signin")
        compose.setContent {
            NativeLocalization("en") { BeaconTheme(dark = true) {
                CompositionLocalProvider(LocalSetupStep provides key) { SetupShell(if (key == "signin") 2 else 3, "Title", content = {}) }
            } }
        }
        fun current() = compose.onNodeWithTag("setup-progress").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        val seen = mutableListOf(current())
        for (page in SETUP_CALL_PAGES) {
            compose.runOnIdle { key = page }
            compose.waitForIdle()
            compose.onNodeWithText("Step 3 of 4").assertIsDisplayed()
            seen += current()
        }
        assertEquals("a new position for every page", seen.size, seen.toSet().size)
        assertEquals(seen.sorted(), seen)
        assertEquals(3f, seen.last(), 0f)
    }

    @Test fun keepConnectedIsOneLineWithTheDetailsBehindTheInfoIcon() {
        var on = true
        compose.setContent {
            NativeLocalization("en") { BeaconTheme(dark = true) {
                KeepConnectedSetupGroup(keepConnected = on, notificationAllowed = true, backgroundActionVisible = false, onKeepConnectedChange = { on = it }, onOpenBackgroundSettings = {})
            } }
        }
        compose.onNodeWithText("Keep connected").assertIsDisplayed()
        compose.onNodeWithText("Calls even when Beacon is closed").assertIsDisplayed()
        compose.onAllNodesWithTag("setup-keep-connected-details").assertCountEquals(0)
        compose.onAllNodesWithText("Let Hermes reach this phone", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("setup-keep-connected-info").assertIsDisplayed().performClick()
        compose.onNodeWithTag("setup-keep-connected-details").assertIsDisplayed().assertTextContains("Shows a quiet notification", substring = true)
        compose.onNodeWithTag("setup-keep-connected").performClick()
        assertTrue("the switch still works", !on)
    }
}
