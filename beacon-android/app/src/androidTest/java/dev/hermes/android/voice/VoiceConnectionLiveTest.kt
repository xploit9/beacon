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

    private suspend fun checkNativeReply(app: BeaconApplication, client: BeaconClient, call: String): JSONObject {
        val service = requireNotNull(app.calls.service)
        service.mute(true)
        val direct = BeaconCallService::class.java.getDeclaredField("directVoice").apply { isAccessible = true }.get(service) as DirectVoice
        val credentials = requireNotNull(VoiceKeyStore(app).session("gemini"))
        val http = okhttp3.OkHttpClient.Builder().cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        suspend fun jobs() = client.nativeRequest("/api/plugins/beacon/mobile/calls/$call").getJSONArray("jobs")
        suspend fun await(condition: suspend () -> Boolean) = withTimeout(120_000) { while (!condition()) delay(100) }
        suspend fun stream(bytes: ByteArray) {
            for (offset in bytes.indices step 640) {
                check(direct.pcm(bytes.copyOfRange(offset, minOf(offset + 640, bytes.size))) { true })
                delay(20)
            }
        }
        try {
            assertEquals("An untouched QA call must have no native requests", 0, jobs().length())
            delay(10_000)
            val source = withContext(Dispatchers.IO) {
                http.newCall(GeminiSpeech.request(credentials, "What is two plus three? Answer briefly.")).execute().use { response ->
                    check(response.isSuccessful)
                    val value = requireNotNull(response.body).bytes(); check(value.size <= GeminiSpeech.MAX_JSON_BYTES)
                    GeminiSpeech.pcm(JSONObject(value.toString(Charsets.UTF_8)))
                }
            }
            val input = ByteArray(source.size / 2 * 2 / 3 * 2)
            for (index in 0 until input.size / 2) {
                val at = index * 3 / 2 * 2
                input[index * 2] = source[at]; input[index * 2 + 1] = source[at + 1]
            }
            source.fill(0)
            try { stream(ByteArray(19200)); stream(input); stream(ByteArray(70400)) } finally { input.fill(0) }
            val queue = DirectVoice::class.java.getDeclaredField("microphone").apply { isAccessible = true }.get(direct) as kotlinx.coroutines.channels.Channel<*>
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            suspend fun drained() = withTimeout(5000) { while (!queue.isEmpty) delay(10) }
            drained(); check(direct.send(GeminiWire.endAudio()))
            await { jobs().length() == 1 }
            val ownerText = app.calls.state.value.transcripts.filter { it.role == "user" && it.final }.joinToString(" ") { it.text }
            assertTrue(Regex("(?i)\\b(?:two|2)\\b").containsMatchIn(ownerText) && Regex("(?i)\\b(?:three|3)\\b").containsMatchIn(ownerText))
            var result = ""
            await {
                val all = jobs(); assertEquals("A readout must never replay native work", 1, all.length())
                val job = all.getJSONObject(0)
                check(job.optString("status") !in setOf("failed", "cancelled"))
                if (job.optString("status") in setOf("done", "completed")) result = job.getString("result")
                result.isNotBlank()
            }
            assertTrue("The native arithmetic result must be relevant", Regex("(?i)\\b(?:five|5)\\b").containsMatchIn(result))
            await { app.calls.state.value.transcripts.filter { it.role == "assistant" && it.final }.joinToString(" ") { it.text }.contains(result) }
            return JSONObject().put("native_jobs", jobs().length()).put("native_result_presented", true).put("synthetic_input", true)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
            }
        }
    }
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
                val previousCall = app.calls.state.value.callId
                val previousError = app.calls.state.value.error
                var attemptStarted = false
                rule.onNodeWithTag("chat-call-beacon").performClick()
                await(45_000) {
                    val state = app.calls.state.value
                    if (state.active || state.callId != previousCall || state.error != previousError) attemptStarted = true
                    if (attemptStarted) check(state.error.isBlank()) { "Voice connection failed: ${state.failureReason}" }
                    attemptStarted && state.active && state.state in setOf("listening", "speaking")
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                call = app.calls.state.value.callId
                samples.put(JSONObject().put("tap_ready_ms", elapsed).put("provider", app.calls.state.value.voiceProvider))
                assertEquals(provider, app.calls.state.value.voiceProvider)
                val context = client.nativeCallContext(call)
                assertEquals("hermes", context.getString("backend"))
                assertEquals(client.auth.profile, context.getString("profile"))
                assertEquals(session, context.getString("selected_session_id"))
                await(45_000) {
                    check(app.calls.state.value.error.isBlank()) { "Greeting failed: ${app.calls.state.value.failureReason}" }
                    app.calls.state.value.transcripts.any { it.role == "assistant" && it.text.isNotBlank() }
                }
                val expected = context.optString("greeting").take(160).ifBlank { "I'm here." }
                assertTrue("The actual service must present its complete greeting", app.calls.state.value.transcripts.any { it.role == "assistant" && it.text == expected })
                samples.getJSONObject(samples.length() - 1).put("greeting_presented_ms", SystemClock.elapsedRealtime() - started)
                if (provider == "gemini" && it == 0) samples.getJSONObject(samples.length() - 1).put("native_reply", checkNativeReply(app, client, call))
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
