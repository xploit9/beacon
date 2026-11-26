package dev.hermes.android.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import dev.hermes.android.voice.CallState
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in device evidence for the motion lane: the real app root over synthetic rows and synthetic call state.
 * beaconMotionEvidence=record holds each state long enough for adb screenrecord; =stills saves the resting chat and call.
 * Nothing is sent anywhere: no client is attached.
 */
@RunWith(AndroidJUnit4::class)
class MotionEvidenceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var store: ViewModelStore
    private lateinit var vm: BeaconViewModel
    private lateinit var target: Scope
    private lateinit var calls: MutableStateFlow<CallState>
    private lateinit var ui: MutableStateFlow<AppState>

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(name: String) = BeaconViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(name: String, value: Any?) = BeaconViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(vm, value)

    @Suppress("UNCHECKED_CAST")
    private fun callFlow(): MutableStateFlow<CallState> = app.calls.javaClass.getDeclaredField("_state")
        .apply { isAccessible = true }.get(app.calls) as MutableStateFlow<CallState>

    private fun scope() = Scope("https://synthetic.example", "synthetic-owner", "default", nativeProvider = "hermes-dashboard").session("motion-evidence")

    private fun rows(): JSONArray = JSONArray().apply {
        repeat(14) { index ->
            put(json("row_id" to 200 + index, "role" to if (index % 2 == 0) "user" else "assistant",
                "content" to "Synthetic motion row ${index + 1}. The chat looks the same at rest; only the state changes move.",
                "timestamp" to 1_764_000_000.0 + index * 75))
        }
    }

    private fun host() {
        assertFalse("Never replace an active real call", app.calls.state.value.active || app.calls.service != null)
        target = scope()
        calls = callFlow()
        store = ViewModelStore()
        val raw = json("session_id" to target.session, "profile" to target.profile, "messages" to rows())
        compose.runOnUiThread {
            vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
            field("originGeneration", 100)
            field("client", null)
            field("sourceScope", target)
            ui = flow<AppState>("_ui")
            ui.value = AppState(origin = target.origin, authenticated = true, owner = target.owner, profile = target.profile, chatProfile = target.profile,
                tab = "Chat", connected = true, loading = false, restoringSession = false, initialChatReady = true, setupComplete = true)
            flow<ChatState>("_chat").value = ChatState(sessionId = target.session, title = "Motion", raw = raw,
                messages = raw.rows("messages").mapIndexed { index, row -> Message.from(row, index, target.session) }, projectionReady = true)
        }
        compose.setContent {
            NativeLocalization("en") { BeaconTheme { NativeWindowLayout(compose.activity) { Box(Modifier.fillMaxSize()) { BeaconRoot(vm) } } } }
        }
        compose.waitForIdle()
    }

    private fun hold(label: String, millis: Long = 2_600) {
        Log.i("MotionEvidence", "PHASE $label")
        compose.waitForIdle()
        Thread.sleep(millis)
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val dir = File(compose.activity.filesDir, "motion-evidence").apply { mkdirs() }
        File(dir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun recordEveryTransition() {
        assumeTrue(args.getString("beaconMotionEvidence") == "record")
        val prior = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 1")
        try {
            host()
            hold("chat-rest", 3_000)
            compose.runOnIdle { calls.value = CallState(active = true, state = "connecting", startedAt = System.currentTimeMillis(), callId = "motion-evidence", conversationScope = target) }
            hold("call-start", 3_000)
            compose.runOnIdle { calls.value = calls.value.copy(state = "listening") }
            hold("call-listening", 2_000)
            compose.onNodeWithTag("beacon-view-conversation").performClick()
            hold("view-chat", 3_000)
            compose.onNodeWithTag("beacon-conversation-toggle").performClick()
            hold("return-to-call", 3_000)
            compose.runOnIdle { calls.value = CallState() }
            hold("call-end", 3_000)
            compose.runOnIdle { vm.tab("Tasks") }
            hold("tab-tasks", 3_000)
            compose.runOnIdle { vm.tab("Chat") }
            hold("tab-chat", 3_000)
            compose.runOnIdle { ui.value = ui.value.copy(connected = false, loading = false) }
            hold("offline-appears", 2_600)
            compose.runOnIdle { ui.value = ui.value.copy(connected = true) }
            hold("offline-leaves", 2_600)
        } finally {
            shell(if (prior == "null" || prior.isBlank()) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $prior")
        }
    }

    @Test fun captureRestingScreens() {
        assumeTrue(args.getString("beaconMotionEvidence") == "stills")
        val prior = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 1")
        try {
            host()
            screenshot("chat-at-rest.png")
            compose.runOnIdle { calls.value = CallState(active = true, state = "listening", startedAt = System.currentTimeMillis(), callId = "motion-evidence", conversationScope = target) }
            compose.waitForIdle()
            screenshot("call-at-rest.png")
        } finally {
            shell(if (prior == "null" || prior.isBlank()) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $prior")
        }
    }

    @Test fun recordWideCall() {
        assumeTrue(args.getString("beaconMotionEvidence") == "wide")
        val prior = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 1")
        try {
            host()
            hold("wide-chat-rest", 3_000)
            compose.runOnIdle { calls.value = CallState(active = true, state = "connecting", startedAt = System.currentTimeMillis(), callId = "motion-wide-evidence", conversationScope = target) }
            hold("wide-call-start", 3_000)
            compose.runOnIdle { calls.value = calls.value.copy(state = "listening") }
            hold("wide-call-listening", 2_000)
            compose.runOnIdle { calls.value = CallState() }
            hold("wide-call-end", 3_000)
        } finally {
            shell(if (prior == "null" || prior.isBlank()) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $prior")
        }
    }

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }

    @After fun clean() {
        runCatching { compose.runOnUiThread { if (::store.isInitialized) store.clear() } }
        runCatching { app.calls.ownerScope = null }
        runCatching { if (::calls.isInitialized) calls.value = CallState() }
    }
}
