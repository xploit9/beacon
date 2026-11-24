package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconApplication
import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import dev.hermes.android.data.ScreenCache
import dev.hermes.android.voice.callerDisplayName
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.io.File
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.hermes.android.BeaconTheme
import dev.hermes.android.IncomingCallContent
import dev.hermes.android.agentphone.AgentPhoneViewScreen
import dev.hermes.android.agentphone.ViewActions
import dev.hermes.android.agentphone.ViewPhase
import dev.hermes.android.agentphone.ViewUi
import dev.hermes.android.voice.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CallScreenStatesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun incomingNameAndActionsRemainReachableAtDoubleTextInArabic() {
        val size = InstrumentationRegistry.getArguments().getString("callMedia")
        var answered = 0
        var declined = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalAssistantName provides "نور", LocalDensity provides Density(density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl) {
                NativeLocalization("ar") { BeaconTheme(dark = true) {
                    val area = if (size in setOf("phone", "unfolded")) Modifier.fillMaxSize() else Modifier.width(320.dp).fillMaxHeight()
                    Surface(area, color = CallBackground, contentColor = Color.White) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                            IncomingCallContent("", .5f, "", uiText("Answer"), true, { answered++ }, { declined++ },
                                Modifier.widthIn(max = 520.dp), fullScreen = true)
                        }
                    }
                } }
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("مكالمة واردة", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("beacon-incoming-name", useUnmergedTree = true).assertTextEquals("نور")
        compose.onAllNodesWithText("00:", substring = true).assertCountEquals(0)
        val stateLabel = androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.StateDescription)
        compose.onAllNodes(stateLabel and hasAnyAncestor(hasTestTag("beacon-incoming-presence"))).assertCountEquals(0)
        if (size in setOf("phone", "unfolded")) {
            val folder = checkNotNull(compose.activity.getExternalFilesDir("callscreen-123"))
            File(folder, "$size-arabic-double-text.png").outputStream().use {
                check(compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        compose.onNodeWithTag("beacon-incoming-answer").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithTag("beacon-incoming-decline").assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(1, answered)
        assertEquals(1, declined)
    }

    @Test fun theLiveDialerKeepsItsControlsInArabicAtDoubleText() {
        var muted by mutableStateOf(false)
        var ended = false
        var chat = false
        val size = InstrumentationRegistry.getArguments().getString("callMedia")
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalAssistantName provides "نور", LocalDensity provides Density(density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl) {
                NativeLocalization("ar") { BeaconTheme(dark = true) {
                    val call = CallState(active = true, state = "listening", muted = muted, callId = "fixture",
                        routes = listOf(dev.hermes.android.voice.AudioRoute("speaker", "مكبر الصوت")), route = "speaker")
                    LiveCallSurface(call, CallUiActions({ muted = !muted }, {}, {}, {}, { ended = true }, {}),
                        false, Modifier.fillMaxSize(), onConversation = { chat = true }) { }
                } }
            }
        }
        for (tag in listOf("beacon-call-name", "beacon-call-status", "beacon-call-timer", "beacon-call-hero")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        for (tag in listOf("beacon-camera", "beacon-share-screen", "beacon-speaker", "beacon-mute", "beacon-hang-up", "beacon-view-conversation")) {
            compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
        }
        // D1: the three circles of the lower row share one centre line, whatever their captions need.
        val centres = listOf("beacon-mute", "beacon-speaker", "beacon-hang-up").map { tag ->
            compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { (it.top + it.bottom) / 2 }
        }
        assertTrue("The mute, speaker and hang-up circles sit at $centres", centres.all { (it - centres[0]).value in -1f..1f })
        compose.onNodeWithTag("beacon-mute").performClick()
        compose.onNodeWithTag("beacon-view-conversation").performClick()
        compose.onNodeWithTag("beacon-hang-up").performClick()
        check(muted && chat && ended)
        if (size in setOf("phone", "unfolded")) {
            val folder = checkNotNull(compose.activity.getExternalFilesDir("callscreen-123"))
            File(folder, "$size-arabic-live-double-text.png").outputStream().use {
                check(compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }

    @Test fun inLandscapeTheHeaderRunsOverBothPanesAndEndsAtTheScreenEdge() {
        compose.setContent {
            CompositionLocalProvider(LocalAssistantName provides "Beacon") {
                NativeLocalization("en") { BeaconTheme(dark = true) {
                    Box(Modifier.width(900.dp).height(420.dp)) {
                        LiveCallSurface(CallState(active = true, state = "listening", callId = "wide"), CallUiActions({}, {}, {}, {}, {}, {}), false,
                            Modifier.fillMaxSize(), onConversation = {}) { }
                    }
                } }
            }
        }
        val chat = compose.onNodeWithTag("beacon-view-conversation").getUnclippedBoundsInRoot()
        val hero = compose.onNodeWithTag("beacon-call-hero").getUnclippedBoundsInRoot()
        assertTrue("The chat button ends over the right pane, not at the middle (${chat.right})", chat.right > hero.right + 100.dp)
    }

    @Test fun watchingOffersExplicitTakeoverAndHasNoKeyboardControls() {
        var taken = 0
        compose.setContent {
            BeaconTheme { AgentPhoneViewScreen(ViewUi(phase = ViewPhase.Live), null, "Beacon", ViewActions(),
                onTakeControl = { taken++ }) }
        }
        compose.onNodeWithTag("agent-phone-take-control").assertIsDisplayed().performClick()
        compose.onNodeWithTag("agent-phone-text").assertDoesNotExist()
        assertEquals(1, taken)
    }

    @Test fun endedCallShowsDurationThenRevealsOriginalContent() {
        var state by mutableStateOf(CallState(active = true, callId = "sample", startedAt = System.currentTimeMillis() - 60000))
        compose.mainClock.autoAdvance = false
        compose.setContent { BeaconTheme { Text("Previous screen"); CallEndNotice(state) } }
        compose.runOnIdle { state = state.copy(active = false, state = "ending") }
        compose.mainClock.advanceTimeBy(96)
        compose.onNodeWithTag("beacon-call-ended").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1600)
        compose.onNodeWithTag("beacon-call-ended").assertDoesNotExist()
        compose.onNodeWithText("Previous screen").assertIsDisplayed()
    }

    @Test fun aRenamedCallerUsesOnlyItsOwnAccountsCachedProfileTitle() = runBlocking {
        val app = compose.activity.application as BeaconApplication
        val first = Scope("https://calls.invalid", "fixture-one", "study", nativeProvider = "synthetic")
        val other = first.copy(owner = "fixture-two")
        val cache = ScreenCache({ app.cache.read(it, "screens") }, { scope, value -> app.cache.write(scope, "screens", value) },
            { app.cache.delete(it, "screens") }, jobs = this)
        try {
            cache.open(first)
            cache.set(first, "navigation-profiles", json("profiles" to JSONArray().put(json("name" to "study", "display_name" to "Vega"))))
            cache.flush(first)
            assertEquals("Vega", callerDisplayName(app, first))
            check(callerDisplayName(app, other) != "Vega")
        } finally { cache.clear(first) }
    }

}
