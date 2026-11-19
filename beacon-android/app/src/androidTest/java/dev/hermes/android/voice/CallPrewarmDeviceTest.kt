package dev.hermes.android.voice

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.*
import dev.hermes.android.assist.BeaconAssistSession
import dev.hermes.android.assist.OverlayPhase
import dev.hermes.android.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Whether calls start on the warm session (calls 1.2.1, "instant answering"): one real Gemini Live call per leg on the demo Hermes.
 * Leg A: a new chat, the prewarm ready for it, Call. Leg B: another new chat, the prewarm ready for it, Call. Legs O1/O2: the side
 * button (the assistant overlay) twice. Each leg records its stages (grant_ok, provider_open, provider_setup_ok, first_model_audio),
 * whether the live session was the warm one, and what the prewarm held. Run through `run_faults.py prewarm`; writes
 * files/calls-fault-prewarm.json. Asserts the chat legs started warm; the overlay legs are recorded.
 */
class CallPrewarmDeviceTest {
    @get:Rule(order = 0) val signIn = DemoCallSignIn()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<BeaconActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }.trim()
    private suspend fun <T : Any> poll(ms: Long, read: () -> T?): T? = withTimeoutOrNull(ms) { var value = read(); while (value == null) { delay(50); value = read() }; value }
    private fun stage(name: String): JSONObject? = app.calls.stageLog.firstOrNull { it.optString("stage") == name }
    /** A short, stable tag for a conversation id (the id itself is never written). */
    private fun prewarmTag(value: String?): String = if (value.isNullOrBlank()) "-" else Integer.toHexString(value.hashCode()).takeLast(5)

    /** One call's start as the phone saw it. */
    private fun legRow(label: String, session: String): JSONObject = JSONObject().put("leg", label).apply {
        for (name in listOf("grant_ok", "context_ok", "provider_open", "provider_setup_ok", "mic_streaming", "first_model_audio")) stage(name)?.let { put(name, it.optLong("elapsed_ms")) }
        // DirectVoice puts "warm" on provider stages only when the call adopted the prewarmed session: true when that session had
        // answered setup before the tap, false when it was still connecting (the call continues it instead of opening another).
        put("adopted_prewarmed_session", stage("provider_open")?.has("warm") == true)
        put("warm_session", stage("provider_setup_ok")?.takeIf { it.has("warm") }?.optBoolean("warm") ?: false)
        put("context_prefetched", stage("context_ok")?.optBoolean("warm") ?: false)
        put("call_chat_is_leg_chat", app.calls.state.value.conversationScope?.session == session)
    }

    private suspend fun endCall() {
        app.calls.hangup()
        withTimeoutOrNull(60_000) { while (app.calls.service != null || app.calls.state.value.active) delay(100) }
        delay(2_000)
    }

    @Test(timeout = 900_000) fun warmStarts() = runBlocking<Unit> {
        assumeTrue("Opt-in: run through run_faults.py prewarm", InstrumentationRegistry.getArguments().getString("beaconLiveService") == "1" && signIn.staged)
        val app = app
        val out = JSONObject().put("scenario", "prewarm")
        val legs = JSONArray()
        val failures = mutableListOf<String>()
        val store = File(app.noBackupFilesDir, "call-start-diagnostics.json")
        val storeBefore = store.takeIf { it.isFile }?.readBytes()
        val voiceStore = VoiceKeyStore(app); val previousVoice = voiceStore.settings()
        val pkg = app.packageName
        val holders = shell("cmd role get-role-holders android.app.role.ASSISTANT").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val recognizer = shell("settings get secure voice_recognition_service")
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        suspend fun await(ms: Long = 90_000, condition: suspend () -> Boolean): Boolean = withTimeoutOrNull(ms) { while (!condition()) delay(100); true } == true
        var client: BeaconClient? = null
        val sessions = mutableListOf<String>()
        val calls = mutableListOf<String>()
        try {
            voiceStore.setSelection(voiceStore.settings("gemini"))
            withContext(Dispatchers.Main.immediate) { rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            check(await { vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession }) { "The staged demo sign-in did not connect" }
            val active = requireNotNull(vm.client).detached().also { client = it }
            rule.runOnIdle { vm.tab("Chat") }
            check(await { vm.ui.value.tab == "Chat" && !vm.ui.value.newChatOpening && (vm.chat.value.sessionId.isBlank() || vm.chat.value.projectionReady) })
            out.put("first_chat", prewarmTag(vm.chat.value.sessionId))
            suspend fun newChat(label: String): String {
                val previous = vm.chat.value.sessionId
                withContext(Dispatchers.Main.immediate) { vm.newChat(liveTestChatBody(active, "Prewarm leg $label " + java.util.UUID.randomUUID().toString().take(6))) }.join()
                check(await { vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previous && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening })
                return vm.chat.value.sessionId.also { sessions += it }
            }
            // Compositions run on the compose test clock: without a sync nothing recomposes, so the chat's Call button (and its
            // prewarm hook) would see the new chat only at the click. A phone recomposes within a frame; the test syncs to match it.
            fun pump() = runCatching { rule.mainClock.advanceTimeByFrame() }
            suspend fun pumpUntil(ms: Long, condition: () -> Boolean): Boolean = withTimeoutOrNull(ms) { while (!condition()) { pump(); delay(100) }; true } == true
            suspend fun tapAndMeasure(label: String, session: String, switchedAt: Long) {
                val target = active.scope.session(session)
                val tapAt = android.os.SystemClock.elapsedRealtime()
                val readyAtTap = app.calls.prewarm.ready(target); val statusAtTap = app.calls.prewarm.status
                val heldAtTap = prewarmTag(VoicePrewarm.chatTarget()?.second)
                rule.onNodeWithTag("chat-call-beacon").performClick()
                check(await(45_000) { app.calls.state.value.active && app.calls.state.value.callId.isNotBlank() && app.calls.state.value.state in setOf("thinking", "listening", "speaking") }) { "Leg $label did not connect" }
                calls += app.calls.state.value.callId
                await(20_000) { stage("first_model_audio") != null }
                legs.put(legRow(label, session).put("chat", prewarmTag(session)).put("switch_to_tap_ms", if (switchedAt > 0) tapAt - switchedAt else JSONObject.NULL)
                    .put("ready_at_tap", readyAtTap).put("prewarm_status_at_tap", statusAtTap).put("prewarm_held_at_tap", heldAtTap))
                endCall()
            }
            suspend fun switchLeg(label: String, tapAfterMs: Long?): String {
                val session = newChat(label)
                pump()
                val switchedAt = android.os.SystemClock.elapsedRealtime()
                val target = active.scope.session(session)
                if (tapAfterMs == null) {
                    pumpUntil(45_000) { app.calls.prewarm.ready(target) }
                    out.put("ready_after_switch_ms_$label", android.os.SystemClock.elapsedRealtime() - switchedAt)
                } else { while (android.os.SystemClock.elapsedRealtime() - switchedAt < tapAfterMs) { pump(); delay(50) } }
                tapAndMeasure(label, session, if (tapAfterMs == null) 0 else switchedAt)
                return session
            }
            switchLeg("A", null)
            switchLeg("B", 300)
            val c = switchLeg("C", 1_500)
            // D: the same chat again once its next warm session is ready (the owner calling twice from one chat).
            pumpUntil(45_000) { app.calls.prewarm.ready(active.scope.session(c)) }
            tapAndMeasure("D", c, 0)
            // The side button: Beacon as the phone's assistant, the assist key, the conversation starts by itself.
            shell("cmd role add-role-holder android.app.role.ASSISTANT $pkg")
            check(await(20_000) { shell("settings get secure voice_interaction_service") == "$pkg/dev.hermes.android.assist.BeaconVoiceInteractionService" }) { "Beacon did not become the assistant" }
            shell("input keyevent KEYCODE_HOME"); delay(1_500)
            for (label in listOf("O1", "O2")) {
                app.calls.stageLog.clear()
                shell("input keyevent KEYCODE_ASSIST")
                val controller = poll(15_000) { BeaconAssistSession.current?.get()?.controller }
                val started = await(150_000) { pump(); app.calls.state.value.active && app.calls.state.value.callId.isNotBlank() && app.calls.state.value.state in setOf("thinking", "listening", "speaking") }
                if (!started) { legs.put(JSONObject().put("leg", label).put("error", "no call within 150 s; overlay=${controller?.state?.surface} line=${controller?.state?.line}")); }
                else {
                    calls += app.calls.state.value.callId
                    await(20_000) { stage("first_model_audio") != null }
                    val session = app.calls.state.value.conversationScope?.session.orEmpty()
                    legs.put(legRow(label, session).put("chat", prewarmTag(session)).put("prewarm_held_at_tap", prewarmTag(VoicePrewarm.chatTarget()?.second))
                        .put("sources", JSONArray(VoicePrewarm.sources().toList())))
                }
                controller?.let { c -> withContext(Dispatchers.Main) { runCatching { c.endCall() } } }
                endCall()
                // Close the overlay before the next summon, and give the overlay's own chat time to open (its first show restores it).
                BeaconAssistSession.current?.get()?.let { s -> withContext(Dispatchers.Main) { runCatching { s.hide() } } }
                await(10_000) { pump(); BeaconAssistSession.current?.get()?.controller?.state?.phase.let { it == null || it == OverlayPhase.Hidden } }
                delay(5_000)
            }
            for (index in 0 until legs.length()) {
                val leg = legs.optJSONObject(index) ?: continue
                if (leg.optString("leg") in setOf("A", "D") && !leg.optBoolean("warm_session")) failures += "leg ${leg.optString("leg")} started cold although its session was ready: $leg"
                // Never worse than cold: once the chat's context was prefetched a prewarmed session exists, and the call continues it.
                if (leg.optString("leg") in setOf("A", "B", "C", "D") && leg.optBoolean("context_prefetched") && !leg.optBoolean("adopted_prewarmed_session"))
                    failures += "leg ${leg.optString("leg")} opened a second session beside the prewarmed one: $leg"
            }
        } catch (error: Throwable) {
            failures += "ERROR: " + error.javaClass.simpleName + ": " + error.message.orEmpty().take(200)
        } finally { withContext(NonCancellable) {
            out.put("legs", legs).put("failures", JSONArray(failures))
            File(app.filesDir, "calls-fault-prewarm.json").writeText(out.toString(), Charsets.UTF_8)
            runCatching { app.calls.hangup() }
            runCatching { BeaconAssistSession.current?.get()?.let { s -> withContext(Dispatchers.Main) { s.hide() } } }
            if (holders.isEmpty()) shell("cmd role clear-role-holders android.app.role.ASSISTANT")
            else shell("cmd role add-role-holder android.app.role.ASSISTANT ${holders.first()}")
            if (recognizer.isNotBlank() && recognizer != "null" && shell("settings get secure voice_recognition_service") != recognizer) shell("settings put secure voice_recognition_service $recognizer")
            withTimeoutOrNull(60_000) { while (app.calls.service != null) delay(100) }
            runCatching { voiceStore.setSelection(previousVoice) }
            for (call in calls) runCatching { withTimeoutOrNull(20_000) { client?.nativeRequest("/api/plugins/beacon/mobile/calls/$call", "DELETE") } }
            for (session in sessions) runCatching { withTimeoutOrNull(20_000) { client?.nativeRpc("session.set_hidden", dev.hermes.android.core.json("session_id" to session, "hidden" to true)) } }
            runCatching { if (storeBefore == null) store.delete() else store.writeBytes(storeBefore) }
            client?.closeNative()
        } }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
