package dev.hermes.android.agentphone

import android.os.Bundle
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.voice.DemoCallSignIn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * R11 repro on the isolated demo Hermes: a real Beacon call, then the call screen's own phone button. Each step is recorded, so the
 * record says which layer stops the phone opening: the button, the call's client for this call, the panel, the watched socket
 * (state or first picture), or the window in front. The owner's screen semantics are read through UiAutomation, as TalkBack reads them.
 * Opt-in: `--arg beaconLiveService=1` with the demo sign-in staged (demo-fixture.py prepare).
 */
class CallPhoneLiveDeviceTest {
    @get:Rule(order = 0) val signIn = DemoCallSignIn()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<BeaconActivity>()

    private val app get() = rule.activity.application as BeaconApplication
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val steps = JSONObject()

    private fun record(key: String, value: Any?) {
        steps.put(key, value ?: JSONObject.NULL)
        instrumentation.sendStatus(2, Bundle().apply { putString(key, value.toString().take(170)) })
    }

    private fun save() = runCatching { File(app.filesDir, "r11-call-phone.json").writeText(steps.toString(2), Charsets.UTF_8) }

    /** Every labelled node in every interactive window, as the accessibility tree exposes it (pop-ups and the secure view included). */
    private fun windowLabels(): String = runCatching {
        val windows = instrumentation.uiAutomation.windows
        windows.joinToString(" || ") { window ->
            val root = window.root ?: return@joinToString "?"
            val out = mutableListOf<String>()
            fun walk(node: android.view.accessibility.AccessibilityNodeInfo?) {
                if (node == null || !node.isVisibleToUser) return
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val label = text.ifBlank { desc }
                if (label.isNotBlank()) out += label.take(60)
                for (index in 0 until node.childCount) walk(node.getChild(index))
            }
            walk(root)
            (window.title?.toString() ?: "window") + ": " + out.distinct().joinToString(" | ")
        }
    }.getOrElse { "unreadable ${it.javaClass.simpleName}" }

    private fun has(tag: String) = runCatching { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)

    /** A picture of what the owner sees, kept with the record; a secure window refuses one and that is said plainly. */
    private fun shot(name: String, tag: String = "call-phone-panel"): String = runCatching {
        val bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(app.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        "saved $name ${bitmap.width}x${bitmap.height}"
    }.getOrElse { "refused ${it.javaClass.simpleName}" }

    /** The activity in front, and whether it is the full agent-phone emulator. */
    private fun frontActivity(): String = runCatching {
        val mgr = app.getSystemService(android.app.ActivityManager::class.java)
        mgr.appTasks.joinToString(",") { it.taskInfo.topActivity?.className.orEmpty().substringAfterLast('.') }
    }.getOrElse { "unreadable" }

    private suspend fun awaitCall(timeoutMs: Long = 60_000): Boolean {
        val end = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < end) {
            val call = app.calls.state.value
            if (call.active && call.callId.isNotBlank() && call.state in setOf("listening", "speaking", "connecting", "thinking")) return true
            if (call.error.isNotBlank()) { record("call_error", call.error); return false }
            delay(200)
        }
        return false
    }

    @Test(timeout = 420_000) fun theCallScreenPhoneButtonOpensTheWatchedPhone() = runBlocking<Unit> {
        assumeTrue("Opt-in: --arg beaconLiveService=1", InstrumentationRegistry.getArguments().getString("beaconLiveService") == "1" && signIn.staged)
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        withTimeout(90_000) { while (!(vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession)) delay(100) }
        var callId = ""
        val voice = VoiceKeyStore(app)
        val previousVoice = voice.settings()
        try {
            // The call needs a key for the selected provider; this phone keeps one (its own), so select that provider as the owner would.
            if (voice.key(previousVoice.provider) == null) {
                val withKey = listOf("gemini", "grok", "openai").firstOrNull { voice.key(it) != null }
                record("voice_key", "provider=${previousVoice.provider} hasKey=false chosen=${withKey ?: "none"}")
                checkNotNull(withKey) { "No voice provider key on this phone" }
                voice.setSelection(voice.settings(withKey))
            }
            // The Beacon tab, as the owner opens it, then that chat's own call button.
            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { vm.tab("Beacon") }
            withTimeout(60_000) { while (vm.ui.value.tab != "Beacon" || vm.chat.value.sessionId.isBlank() || !vm.ui.value.connected) delay(200) }
            record("beacon_tab", "tab=${vm.ui.value.tab} session=${vm.chat.value.sessionId.isNotBlank()} ready=${vm.ui.value.connected}")
            rule.onNodeWithTag("chat-call-beacon").performClick()
            val started = awaitCall()
            val call = app.calls.state.value
            callId = call.callId
            record("call_started", "started=$started id=${callId.take(8)} state=${call.state} provider=${call.voiceProvider} " +
                "service=${app.calls.service != null} error=${call.error.take(120)} notice=${vm.ui.value.notice.take(120)}")
            record("call_window", windowLabels())
            val phone = runCatching {
                val node = rule.onNodeWithTag("beacon-call-phone").fetchSemanticsNode()
                "present bounds=${node.boundsInRoot} disabled=${node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}"
            }.getOrElse { "absent ${it.javaClass.simpleName}" }
            record("phone_button", phone)
            // The one object that decides whether any phone can be shown for this call.
            val handed = runCatching { app.calls.service?.clientForHandoff(callId) != null }.getOrElse { "threw ${it.javaClass.simpleName}: ${it.message}" }
            record("handoff_client", handed)
            rule.onNodeWithTag("beacon-call-phone").performClick()
            rule.waitUntil(12_000) { has("call-phone-panel") }
            record("panel", "opened=${has("call-phone-panel")} active=${app.calls.state.value.active} same_call=${app.calls.state.value.callId == callId}")
            val live = runCatching { rule.waitUntil(45_000) { has("agent-phone-live") }; true }.getOrElse { false }
            record("watched", "live=$live frame=${has("agent-phone-frame")} active=${app.calls.state.value.active} " +
                "same_call=${app.calls.state.value.callId == callId} state=${app.calls.state.value.state}")
            record("watched_shot", shot("r11-phone-open.png"))
            record("watched_window", windowLabels())
            androidx.test.espresso.Espresso.pressBack()
            delay(2_000)
            record("after_back", "panel=${has("call-phone-panel")} call_button=${has("beacon-call-phone")} active=${app.calls.state.value.active} " +
                "state=${app.calls.state.value.state}")
            record("after_back_shot", shot("r11-call-still-live.png", tag = "beacon-live-call-surface"))
            record("after_back_window", windowLabels())
            save()
        } finally {
            withContext(NonCancellable) {
                runCatching { app.calls.hangup() }
                delay(3_000)
                record("after_hangup", "active=${app.calls.state.value.active} state=${app.calls.state.value.state}")
                runCatching { voice.setSelection(previousVoice) }
                save()
            }
        }
    }

    /**
     * A tap while the call has not handed its service to the screen yet: the call is live, the service attaches a moment later.
     * The tap used to be lost for the rest of the call (the button disabled, or the panel closed at once); it must open the phone.
     */
    @Test(timeout = 420_000) fun aTapWhileTheCallIsTakingOverOpensThePhoneWhenItIsReady() = runBlocking<Unit> {
        assumeTrue("Opt-in: --arg beaconLiveService=1", InstrumentationRegistry.getArguments().getString("beaconLiveService") == "1" && signIn.staged)
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        withTimeout(90_000) { while (!(vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession)) delay(100) }
        val voice = VoiceKeyStore(app)
        val previousVoice = voice.settings()
        var callId = ""
        try {
            if (voice.key(previousVoice.provider) == null) {
                val withKey = listOf("gemini", "grok", "openai").firstOrNull { voice.key(it) != null }
                checkNotNull(withKey) { "No voice provider key on this phone" }
                voice.setSelection(voice.settings(withKey))
            }
            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { vm.tab("Beacon") }
            withTimeout(60_000) { while (vm.ui.value.tab != "Beacon" || !vm.ui.value.connected) delay(200) }
            withTimeout(60_000) { while (vm.chat.value.sessionId.isBlank()) delay(200) }
            record("beacon_tab", "tab=${vm.ui.value.tab} session=${vm.chat.value.sessionId.isNotBlank()} ready=${vm.ui.value.connected}")
            rule.onNodeWithTag("chat-call-beacon").performClick()
            val up = runCatching { rule.waitUntil(90_000) { app.calls.state.value.active && app.calls.state.value.callId.isNotBlank() }; true }.getOrElse { false }
            callId = app.calls.state.value.callId
            check(up) { "The call did not start: ${app.calls.state.value.error}" }
            val real = app.calls.service
            checkNotNull(real) { "The call has no service to hand off" }
            // The screen does not have the call's service yet: the exact moment a tap can land before the phone can answer.
            app.calls.service = null
            record("detached", "active=${app.calls.state.value.active} id=${callId.take(8)} state=${app.calls.state.value.state} service=false")
            val tap = runCatching { rule.onNodeWithTag("beacon-call-phone").performClick() }.exceptionOrNull()?.let { "failed ${it.javaClass.simpleName}" } ?: "clicked"
            record("tap", tap)
            delay(1_500)
            app.calls.service = real
            val panel = runCatching { rule.waitUntil(20_000) { has("call-phone-panel") }; true }.getOrElse { false }
            val live = runCatching { rule.waitUntil(45_000) { has("agent-phone-live") }; true }.getOrElse { false }
            record("opened", "panel=$panel live=$live active=${app.calls.state.value.active} same_call=${app.calls.state.value.callId == callId} " +
                "state=${app.calls.state.value.state}")
            record("opened_shot", shot("r11-seam-phone-open.png"))
            save()
            check(panel && live) { "The tap was lost: panel=$panel live=$live" }
        } finally {
            withContext(NonCancellable) {
                runCatching { app.calls.hangup() }
                delay(3_000)
                runCatching { voice.setSelection(previousVoice) }
                record("cleanup", "active=${app.calls.state.value.active}")
                save()
            }
        }
    }

    /** The brief's second surface: the full emulator, opened from Beacon's phone page while the call runs. */
    @Test(timeout = 420_000) fun theFullEmulatorOpensFromSettingsDuringACall() = runBlocking<Unit> {
        assumeTrue("Opt-in: --arg beaconLiveService=1", InstrumentationRegistry.getArguments().getString("beaconLiveService") == "1" && signIn.staged)
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        withTimeout(90_000) { while (!(vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession)) delay(100) }
        val voice = VoiceKeyStore(app)
        val previousVoice = voice.settings()
        var callId = ""
        try {
            if (voice.key(previousVoice.provider) == null) {
                val withKey = listOf("gemini", "grok", "openai").firstOrNull { voice.key(it) != null }
                checkNotNull(withKey) { "No voice provider key on this phone" }
                voice.setSelection(voice.settings(withKey))
            }
            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { vm.tab("Beacon") }
            withTimeout(60_000) { while (vm.ui.value.tab != "Beacon" || !vm.ui.value.connected) delay(200) }
            withTimeout(60_000) { while (vm.chat.value.sessionId.isBlank()) delay(200) }
            record("beacon_tab", "tab=${vm.ui.value.tab} session=${vm.chat.value.sessionId.isNotBlank()} ready=${vm.ui.value.connected}")
            rule.onNodeWithTag("chat-call-beacon").performClick()
            val up = runCatching { rule.waitUntil(90_000) { app.calls.state.value.active && app.calls.state.value.callId.isNotBlank() } ; true }.getOrElse { false }
            callId = app.calls.state.value.callId
            record("call", "up=$up id=${callId.take(8)} state=${app.calls.state.value.state} error=${app.calls.state.value.error.take(100)}")
            check(up) { "The call did not start: ${app.calls.state.value.error}" }
            // Beacon's phone page, as the owner opens it while on the call.
            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { vm.more("Agent phone") }
            withTimeout(60_000) { while (has("agent-phone-open").not() && has("agent-phone-turn-on").not()) delay(250) }
            if (has("agent-phone-turn-on")) {
                record("settings_off", "page=${vm.ui.value.morePage} turn_on=true")
                rule.onNodeWithTag("agent-phone-turn-on").performScrollTo().performClick()
                withTimeout(300_000) { while (has("agent-phone-open").not()) delay(500) }
            }
            record("settings_page", "open_button=${has("agent-phone-open")} front=${frontActivity()}")
            rule.onNodeWithTag("agent-phone-open").performScrollTo().performClick()
            val emulator = runCatching {
                rule.waitUntil(90_000) {
                    frontActivity().contains("AgentPhoneActivity") && (has("agent-phone-view") || has("agent-phone-frame"))
                }
                true
            }.getOrElse { false }
            delay(4_000)
            record("emulator", "opened=$emulator front=${frontActivity()} live=${has("agent-phone-live")} frame=${has("agent-phone-frame")} " +
                "active=${app.calls.state.value.active} same_call=${app.calls.state.value.callId == callId} state=${app.calls.state.value.state} " +
                "return_to_call=${has("agent-phone-show-call")} " +
                "window=${if (emulator) "" else windowLabels().take(300)}")
            androidx.test.espresso.Espresso.pressBack()
            // Back leaves the emulator: with the call still live the app returns to the call's own live surface.
            val backSurface = runCatching { rule.waitUntil(20_000) { has("beacon-live-call-surface") && has("beacon-call-phone") }; true }.getOrElse { false }
            delay(1_500)
            record("after_back", "front=${frontActivity()} active=${app.calls.state.value.active} same_call=${app.calls.state.value.callId == callId} " +
                "call_button=${has("beacon-call-phone")} call_stage=${has("beacon-live-call-surface")} surface=$backSurface " +
                "state=${app.calls.state.value.state}")
            record("after_back_shot", shot("r11-call-after-settings.png", tag = "beacon-live-call-surface"))
            save()
            check(backSurface && app.calls.state.value.active && app.calls.state.value.callId == callId) {
                "Back from the emulator did not return to the live call: surface=$backSurface active=${app.calls.state.value.active} " +
                    "same_call=${app.calls.state.value.callId == callId}"
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { app.calls.hangup() }
                delay(3_000)
                runCatching { voice.setSelection(previousVoice) }
                record("cleanup", "active=${app.calls.state.value.active} front=${frontActivity()}")
                save()
            }
        }
    }
}
