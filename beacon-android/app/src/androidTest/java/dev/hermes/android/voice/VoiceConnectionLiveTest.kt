package dev.hermes.android.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.core.json
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Measures the actual chat button -> Telecom service -> authenticated provider readiness. */
class VoiceConnectionLiveTest {
    @get:Rule val rule = createAndroidComposeRule<BeaconActivity>()

    @Test(timeout = 240_000) fun geminiCallsMeasureTapToReady() = exercise("gemini")
    @Test(timeout = 240_000) fun grokCallsMeasureTapToReady() = exercise("grok")

    private fun exercise(provider: String) = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("beaconLiveService") == "1")
        require(args.getString("beaconNoHostAudio") == "1")
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        require(app.packageName.endsWith(".debug") && !app.calls.state.value.active)
        require(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val voices = VoiceKeyStore(app)
        val previousVoice = voices.settings()
        require(voices.hasKey(provider)) { "Live timing requires the selected provider's saved phone key" }
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        var phase = "restoring"
        suspend fun await(ms: Long = 30_000, condition: () -> Boolean) = withTimeout(ms) {
            try { while (!condition()) delay(20) }
            catch (error: TimeoutCancellationException) { throw AssertionError("Connection timing phase=$phase authenticated=${vm.ui.value.authenticated} connected=${vm.ui.value.connected}", error) }
        }
        val samples = JSONArray()
        var client: BeaconClient? = null
        var session = ""
        var previousSession = ""
        var call = ""
        try {
            voices.setSelection(voices.settings(provider))
            await { !vm.ui.value.restoringSession }
            phase = "reconnecting"
            withContext(Dispatchers.Main.immediate) { vm.connect(vm.ui.value.origin) }.join()
            await { vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession }
            client = requireNotNull(vm.client).detached()
            phase = "opening chat"
            previousSession = vm.chat.value.sessionId
            withContext(Dispatchers.Main.immediate) { vm.tab("Chat"); vm.newChat(json("title" to "Voice connection timing")) }.join()
            await { vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previousSession &&
                vm.chat.value.projectionReady && !vm.ui.value.newChatOpening && vm.authenticatedScope()?.session == vm.chat.value.sessionId }
            session = vm.chat.value.sessionId
            repeat(3) {
                phase = "call ${it + 1}"
                rule.waitForIdle()
                val started = SystemClock.elapsedRealtime()
                rule.onNodeWithTag("chat-call-beacon").performClick()
                await(45_000) {
                    check(app.calls.state.value.error.isBlank()) { "Voice connection failed: ${app.calls.state.value.failureReason}" }
                    app.calls.state.value.active && app.calls.state.value.state in setOf("listening", "speaking")
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                call = app.calls.state.value.callId
                samples.put(JSONObject().put("tap_ready_ms", elapsed).put("provider", app.calls.state.value.voiceProvider))
                assertEquals(provider, app.calls.state.value.voiceProvider)
                val context = client.nativeCallContext(call)
                assertEquals("hermes", context.getString("backend"))
                assertEquals(client.auth.profile, context.getString("profile"))
                assertEquals(session, context.getString("selected_session_id"))
                app.calls.hangup()
                await { !app.calls.state.value.active && app.calls.service == null }
                client.nativeRequest("/api/plugins/beacon/mobile/calls/$call", "DELETE")
                call = ""
            }
            assertEquals(3, samples.length())
        } finally {
            withContext(NonCancellable) {
                app.calls.hangup()
                withTimeoutOrNull(60_000) { while (app.calls.service != null) delay(100) }
                if (call.isNotBlank()) runCatching { client?.nativeRequest("/api/plugins/beacon/mobile/calls/$call", "DELETE") }
                if (session.isNotBlank()) runCatching { client?.nativeRpc("session.set_hidden", json("session_id" to session, "hidden" to true)) }
                voices.setSelection(previousVoice)
                if (previousSession.isNotBlank()) withTimeoutOrNull(30_000) {
                    withContext(Dispatchers.Main.immediate) { vm.selectSession(previousSession) }
                    while (vm.chat.value.sessionId != previousSession) delay(20)
                }
                File(app.filesDir, "voice-connection-$provider.json").writeText(JSONObject().put("samples", samples).put("phase", phase).put("versions", runCatching { client?.nativeRequest("/api/plugins/beacon/mobile/plugin/version") }.getOrNull()).toString(), Charsets.UTF_8)
                client?.closeNative()
            }
        }
    }
}
