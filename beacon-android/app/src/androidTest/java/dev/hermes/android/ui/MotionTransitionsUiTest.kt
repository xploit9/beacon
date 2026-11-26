package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.AppState
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconRoot
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.ChatState
import dev.hermes.android.Message
import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.voice.CallState
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lane's short transitions: a call dissolves into the chat, the composer leaves and returns, a tab swaps its body once,
 * and the call status keeps one node at rest. Synthetic rows and a synthetic call state only; no account, provider or network.
 * The system animator scale is the same switch the app reads, so the reduced-motion cases are the real setting.
 */
@RunWith(AndroidJUnit4::class)
class MotionTransitionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private lateinit var store: ViewModelStore
    private lateinit var vm: BeaconViewModel
    private lateinit var target: Scope
    private lateinit var calls: MutableStateFlow<CallState>

    private fun scope() = Scope("https://synthetic.example", "synthetic-owner", "default", nativeProvider = "hermes-dashboard").session("motion-fixture")

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(name: String) = BeaconViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(name: String, value: Any?) = BeaconViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(vm, value)

    @Suppress("UNCHECKED_CAST")
    private fun callFlow(): MutableStateFlow<CallState> = app.calls.javaClass.getDeclaredField("_state")
        .apply { isAccessible = true }.get(app.calls) as MutableStateFlow<CallState>

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }

    /** The app reads this setting; put it back exactly as it was found. */
    private fun animations(scale: String, block: () -> Unit) {
        val prior = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale $scale")
        try {
            block()
        } finally {
            shell(if (prior == "null" || prior.isBlank()) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $prior")
        }
    }

    private fun rows(): JSONArray = JSONArray().apply {
        repeat(30) { index ->
            put(json("row_id" to 100 + index, "role" to if (index % 2 == 0) "user" else "assistant",
                "content" to "Synthetic motion row ${index + 1} keeps the transcript long enough to scroll.",
                "timestamp" to 1_764_000_000.0 + index * 60))
        }
    }

    /** Mounts the real chat (or the real app root) over a projection; no client is attached, so nothing can leave the phone. */
    private fun host(root: Boolean = false, draft: String = "", connected: Boolean = false, wide: Boolean = false) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        target = scope()
        calls = callFlow()
        store = ViewModelStore()
        val raw = json("session_id" to target.session, "profile" to target.profile, "messages" to rows())
        compose.runOnUiThread {
            vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
            field("originGeneration", 100)
            field("client", null)
            field("sourceScope", target)
            flow<AppState>("_ui").value = AppState(origin = target.origin, authenticated = true, owner = target.owner, profile = target.profile,
                chatProfile = target.profile, tab = "Chat", connected = connected, loading = false, restoringSession = false, initialChatReady = true)
            flow<ChatState>("_chat").value = ChatState(sessionId = target.session, title = "Motion", raw = raw,
                messages = raw.rows("messages").mapIndexed { index, row -> Message.from(row, index, target.session) }, projectionReady = true)
            if (draft.isNotEmpty()) flow<TextFieldValue>("_composer").value = TextFieldValue(draft)
        }
        compose.setContent {
            NativeLocalization("en") { BeaconTheme { NativeWindowLayout(compose.activity) {
                Box(Modifier.fillMaxSize()) {
                    if (root) BeaconRoot(vm)
                    else if (wide) CompositionLocalProvider(LocalAssistantName provides "Beacon", LocalBeaconSettings provides JSONObject(), LocalDensity provides Density(1f)) {
                        Box(Modifier.requiredSize(900.dp, 600.dp)) { ChatScreen(vm) }
                    } else CompositionLocalProvider(LocalAssistantName provides "Beacon", LocalBeaconSettings provides JSONObject(), LocalDensity provides Density(1f)) {
                        // A fixed phone window: the motion cases must not depend on the emulator's current physical rotation.
                        Box(Modifier.requiredSize(360.dp, 640.dp)) { ChatScreen(vm) }
                    }
                }
            } } }
        }
        compose.waitForIdle()
    }

    @After fun clean() {
        runCatching { compose.runOnUiThread { if (::store.isInitialized) store.clear() } }
        runCatching { app.calls.ownerScope = null }
        runCatching { if (::calls.isInitialized) calls.value = CallState() }
        runCatching { kotlinx.coroutines.runBlocking { app.cache.delete(scope(), "session"); app.cache.delete(scope(), "draft") } }
    }

    @Test fun motionOffSwapsTheCallStageAndComposerInOneFrame() = animations("0") {
        host()
        compose.onNodeWithTag("conversation-transcript").assertExists()
        compose.onNodeWithTag("message-input").assertExists()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { calls.value = CallState(active = true, state = "listening", callId = "motion-call", conversationScope = target) }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("beacon-call-stage").assertExists()
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(0)
            compose.onAllNodesWithTag("message-input").assertCountEquals(0)
            compose.runOnIdle { calls.value = CallState() }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("conversation-transcript").assertExists()
            compose.onNodeWithTag("message-input").assertExists()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun theCallStageReplacesTheTranscriptAndReturnsToItsPlace() = animations("1") {
        host()
        val transcript = compose.onNodeWithTag("conversation-transcript")
        transcript.performTouchInput { swipeUp() }
        compose.waitForIdle()
        val before = requireNotNull(TranscriptScrollProbe.state) { "the transcript must be on screen" }
        val place = before.firstVisibleItemIndex to before.firstVisibleItemScrollOffset
        compose.runOnIdle { calls.value = CallState(active = true, state = "listening", callId = "motion-call", conversationScope = target) }
        compose.waitForIdle()
        compose.onNodeWithTag("beacon-call-stage").assertExists()
        compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(0)
        compose.runOnIdle { calls.value = CallState() }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-transcript").assertExists()
        val after = requireNotNull(TranscriptScrollProbe.state) { "the transcript must be back" }
        assertSame("the transcript keeps its own list state across the call", before, after)
        assertEquals("the transcript keeps its scroll position across the call", place, after.firstVisibleItemIndex to after.firstVisibleItemScrollOffset)
    }

    @Test fun theComposerReturnsWithItsDraft() = animations("1") {
        host(draft = "Half-written note that must survive the call")
        compose.onNodeWithTag("message-input").assertTextContains("Half-written note that must survive the call")
        compose.runOnIdle { calls.value = CallState(active = true, state = "listening", callId = "motion-call", conversationScope = target) }
        compose.waitForIdle()
        compose.onAllNodesWithTag("message-input").assertCountEquals(0)
        compose.runOnIdle { calls.value = CallState() }
        compose.waitForIdle()
        compose.onNodeWithTag("message-input").assertTextContains("Half-written note that must survive the call")
    }

    @Test fun theCallStatusKeepsOneNodeAtRest() {
        var call by mutableStateOf(CallState(active = true, state = "listening", callId = "motion-status"))
        val actions = CallUiActions(mute = {}, camera = {}, flip = {}, allowBubble = {}, end = {}, route = {})
        compose.setContent {
            BeaconTheme(dark = true) {
                LiveCallSurface(call, actions, true, Modifier.fillMaxSize(), onConversation = {}) { }
            }
        }
        compose.onNodeWithTag("beacon-call-status").assertTextEquals("Listening")
        compose.runOnIdle { call = call.copy(state = "speaking") }
        compose.onNodeWithTag("beacon-call-status").assertTextEquals("Speaking")
        compose.onAllNodesWithTag("beacon-call-status").assertCountEquals(1)
        compose.runOnIdle { call = call.copy(state = "listening", muted = true) }
        compose.onNodeWithTag("beacon-call-status").assertTextEquals("You're muted")
        compose.onAllNodesWithTag("beacon-call-status").assertCountEquals(1)
    }

    @Test fun theCallFailurePanelAppearsAndLeaves() = animations("1") {
        host()
        compose.runOnIdle { calls.value = CallState(active = false, state = "ending", callId = "motion-failed", conversationScope = target, failureReason = "Synthetic call failure") }
        compose.waitForIdle()
        compose.onNodeWithTag("beacon-call-failure-panel").assertExists()
        compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(0)
        compose.runOnIdle { calls.value = CallState() }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-transcript").assertExists()
        compose.onAllNodesWithTag("beacon-call-failure-panel").assertCountEquals(0)
    }

    @Test fun theOfflineBannerAppearsAndLeaves() = animations("1") {
        host(root = true, connected = true)
        compose.onAllNodesWithTag("beacon-offline-banner").assertCountEquals(0)
        compose.runOnIdle { flow<AppState>("_ui").value = flow<AppState>("_ui").value.copy(connected = false, loading = false) }
        compose.waitForIdle()
        compose.onNodeWithTag("beacon-offline-banner").assertExists()
        compose.runOnIdle { flow<AppState>("_ui").value = flow<AppState>("_ui").value.copy(connected = true) }
        compose.waitForIdle()
        compose.onAllNodesWithTag("beacon-offline-banner").assertCountEquals(0)
    }

    @Test fun theOutgoingCallKeepsItsStatusAndTimerWhileItFades() = animations("1") {
        host()
        val startedAt = System.currentTimeMillis() - 65_000
        compose.runOnIdle { calls.value = CallState(active = true, state = "listening", startedAt = startedAt, callId = "motion-freeze", conversationScope = target) }
        compose.waitForIdle()
        compose.onNodeWithTag("beacon-call-status").assertTextEquals("Listening")
        compose.onNodeWithTag("beacon-call-timer").assertTextContains("01:0", substring = true)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { calls.value = CallState() }
            compose.mainClock.advanceTimeBy(50)
            compose.onNodeWithTag("beacon-call-status").assertTextEquals("Listening")
            compose.onNodeWithTag("beacon-call-timer").assertTextContains("01:0", substring = true)
            compose.mainClock.advanceTimeBy(250)
            compose.onNodeWithTag("conversation-transcript").assertExists()
            compose.onAllNodesWithTag("beacon-call-stage").assertCountEquals(0)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun theOutgoingFailureKeepsItsReason() = animations("1") {
        host()
        compose.runOnIdle { calls.value = CallState(active = false, state = "ending", callId = "motion-failed", conversationScope = target, failureReason = "Synthetic call failure") }
        compose.waitForIdle()
        compose.onNodeWithTag("beacon-call-failure-reason").assertTextEquals("Synthetic call failure")
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { calls.value = CallState() }
            compose.mainClock.advanceTimeBy(50)
            compose.onNodeWithTag("beacon-call-failure-reason").assertTextEquals("Synthetic call failure")
            compose.mainClock.advanceTimeBy(250)
            compose.onAllNodesWithTag("beacon-call-failure-panel").assertCountEquals(0)
            compose.onNodeWithTag("conversation-transcript").assertExists()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun hangingUpFromViewChatNeverSwapsBackToTheCallSurface() = animations("1") {
        host()
        compose.runOnIdle { calls.value = CallState(active = true, state = "listening", startedAt = System.currentTimeMillis(), callId = "motion-view", conversationScope = target) }
        compose.waitForIdle()
        Thread.sleep(300)
        compose.onNodeWithTag("beacon-view-conversation").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("beacon-conversation-toggle").assertExists()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { calls.value = CallState() }
            compose.mainClock.advanceTimeBy(50)
            compose.onAllNodesWithTag("beacon-view-conversation").assertCountEquals(0)
            compose.onAllNodesWithTag("beacon-conversation-toggle").assertCountEquals(0)
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(1)
            compose.mainClock.advanceTimeBy(250)
            compose.onNodeWithTag("conversation-transcript").assertExists()
            compose.onAllNodesWithTag("beacon-call-stage").assertCountEquals(0)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun aWideCallKeepsOneTranscriptAndItsPlace() = animations("1") {
        host(wide = true)
        compose.onNodeWithTag("conversation-transcript").performTouchInput { swipeUp() }
        compose.waitForIdle()
        // An item boundary keeps the pixel offset meaningful while the transcript changes width for the side pane.
        compose.onNodeWithTag("conversation-transcript").performScrollToIndex(15)
        compose.waitForIdle()
        val before = requireNotNull(TranscriptScrollProbe.state) { "the transcript must be on screen" }
        val place = before.firstVisibleItemIndex to before.firstVisibleItemScrollOffset
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { calls.value = CallState(active = true, state = "listening", startedAt = System.currentTimeMillis(), callId = "motion-wide", conversationScope = target) }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(50)
            compose.onNodeWithTag("beacon-call-stage").assertExists()
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(1)
            assertSame("the wide call reuses the one transcript state", before, TranscriptScrollProbe.state)
            compose.mainClock.advanceTimeBy(250)
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(1)
            Thread.sleep(300)
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { calls.value = CallState() }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(50)
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(1)
            compose.mainClock.advanceTimeBy(250)
            compose.onAllNodesWithTag("beacon-call-stage").assertCountEquals(0)
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(1)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        val after = requireNotNull(TranscriptScrollProbe.state) { "the transcript must be back" }
        assertSame("the transcript keeps its list state across a wide call", before, after)
        assertEquals("the transcript keeps its scroll position across a wide call", place, after.firstVisibleItemIndex to after.firstVisibleItemScrollOffset)
    }

    @Test fun aWideCallEndingInsideTheSettleKeepsOneTranscript() = animations("1") {
        host(wide = true)
        compose.onNodeWithTag("conversation-transcript").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-transcript").performScrollToIndex(15)
        compose.waitForIdle()
        val before = requireNotNull(TranscriptScrollProbe.state) { "the transcript must be on screen" }
        val place = before.firstVisibleItemIndex to before.firstVisibleItemScrollOffset
        compose.mainClock.autoAdvance = false
        try {
            // Hang up inside the 220 ms settle of the wide entry: the transition pair, not a delayed previous stage, decides.
            compose.runOnIdle { calls.value = CallState(active = true, state = "listening", startedAt = System.currentTimeMillis(), callId = "motion-wide-fast", conversationScope = target) }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(50)
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(1)
            compose.runOnIdle { calls.value = CallState() }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(50)
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(1)
            compose.mainClock.advanceTimeBy(300)
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(1)
            compose.onAllNodesWithTag("beacon-call-stage").assertCountEquals(0)
            assertSame("the wide fast call reuses the one transcript state", before, TranscriptScrollProbe.state)
            val after = requireNotNull(TranscriptScrollProbe.state) { "the transcript must be back" }
            // The item anchor survives; the pixel offset can shift when the transcript's width changes for the side pane.
            assertEquals("the wide fast call keeps its first visible item", place.first, after.firstVisibleItemIndex)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun aViewChatTapDuringAPhoneEntryKeepsOneTranscript() = animations("1") {
        host()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { calls.value = CallState(active = true, state = "listening", startedAt = System.currentTimeMillis(), callId = "motion-entry", conversationScope = target) }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(50)
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(1)
            compose.onNodeWithTag("beacon-view-conversation").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(1)
            // Still inside the 200 ms entry: the outgoing chat remains the one transcript while the tap waits for the settle.
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(1)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun aLaterFailedCallAfterViewChatHangUpStillAnimates() = animations("1") {
        host()
        compose.runOnIdle { calls.value = CallState(active = true, state = "listening", startedAt = System.currentTimeMillis(), callId = "motion-first", conversationScope = target) }
        compose.waitForIdle()
        Thread.sleep(300)
        compose.onNodeWithTag("beacon-view-conversation").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("beacon-conversation-toggle").assertExists()
        compose.runOnIdle { calls.value = CallState() }
        compose.waitForIdle()
        Thread.sleep(350)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { calls.value = CallState(active = false, state = "ending", callId = "motion-later-failed", conversationScope = target, failureReason = "Synthetic call failure") }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(50)
            compose.onNodeWithTag("beacon-call-failure-panel").assertExists()
            // The outgoing chat is still fading under the animating failure panel, not cut by a stale history flag.
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(1)
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("beacon-call-failure-panel").assertExists()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun aWideFailedCallStillAnimatesThePanel() = animations("1") {
        host(wide = true)
        compose.runOnIdle { calls.value = CallState(active = true, state = "listening", startedAt = System.currentTimeMillis(), callId = "motion-wide-fail", conversationScope = target) }
        compose.waitForIdle()
        Thread.sleep(350)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { calls.value = CallState(active = false, state = "ending", callId = "motion-wide-fail", conversationScope = target, failureReason = "Synthetic call failure") }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(50)
            // The wide call stage is still fading out while the failure panel fades/expands in: one transcript, both stages present.
            compose.onNodeWithTag("beacon-call-stage").assertExists()
            compose.onNodeWithTag("beacon-call-failure-panel").assertExists()
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(1)
            compose.mainClock.advanceTimeBy(300)
            compose.onAllNodesWithTag("beacon-call-stage").assertCountEquals(0)
            compose.onNodeWithTag("beacon-call-failure-panel").assertExists()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun theRootTabBodyIsOneAtRestAndSwapsOnce() = animations("1") {
        host(root = true)
        compose.onAllNodesWithTag("beacon-tab-body", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("conversation-transcript").assertExists()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { vm.tab("Tasks") }
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("beacon-tab-body", useUnmergedTree = true).assertCountEquals(1)
            compose.onAllNodesWithText("Reconnect to Hermes").assertCountEquals(1)
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(0)
            compose.mainClock.advanceTimeBy(200)
            compose.onAllNodesWithTag("beacon-tab-body", useUnmergedTree = true).assertCountEquals(1)
            compose.onAllNodesWithTag("conversation-transcript").assertCountEquals(0)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
