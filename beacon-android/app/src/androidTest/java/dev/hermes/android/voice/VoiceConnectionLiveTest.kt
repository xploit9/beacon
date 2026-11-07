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
import dev.hermes.android.liveTestChatBody
import dev.hermes.android.loginLiveTest
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

/**
 * Measures a real call from the chat's Call button on the demo Hermes: one cold call (nothing prepared), then three warm
 * calls (the Call button kept the conversation's context and a live session ready). Every stage is reported on the test's
 * tap clock: Hermes' admission, the context, the provider's setup, the microphone streaming, and the first audio of the live
 * model's own greeting. Fails when any audio is heard before the provider answered setup.
 */
class VoiceConnectionLiveTest {
    @get:Rule val rule = createAndroidComposeRule<BeaconActivity>()

    /** The live model answers a spoken question itself: no Hermes job is started for it, and the reply time is measured. */
    private suspend fun checkDirectReply(app: BeaconApplication, client: BeaconClient, call: String): JSONObject {
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
            // The owner's words are synthesized for the test only; the call itself never renders a greeting.
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
            // Counted before speaking: the live model can answer during the trailing silence, before the stream ends.
            val repliesBefore = app.calls.state.value.transcripts.count { it.role == "assistant" }
            var spoken = 0L
            fun answer() = app.calls.state.value.transcripts.filter { it.role == "assistant" }.drop(repliesBefore).joinToString(" ") { it.text }
            // The first reply is timed as it appears, even while the trailing silence is still being streamed.
            val firstReplyAt = java.util.concurrent.atomic.AtomicLong(0)
            val watcher = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                while (firstReplyAt.get() == 0L) { if (spoken > 0 && answer().isNotBlank()) firstReplyAt.set(android.os.SystemClock.elapsedRealtime()); delay(50) }
            }
            try { stream(ByteArray(19200)); stream(input); spoken = android.os.SystemClock.elapsedRealtime(); stream(ByteArray(70400)) } finally { input.fill(0) }
            val queue = DirectVoice::class.java.getDeclaredField("microphone").apply { isAccessible = true }.get(direct) as kotlinx.coroutines.channels.Channel<*>
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            suspend fun drained() = withTimeout(5000) { while (!queue.isEmpty) delay(10) }
            drained(); check(direct.send(GeminiWire.endAudio()))
            await { firstReplyAt.get() > 0 }
            watcher.cancel()
            val firstReplyMs = firstReplyAt.get() - spoken
            await { Regex("(?i)\\b(?:five|5)\\b").containsMatchIn(answer()) }
            val answerMs = android.os.SystemClock.elapsedRealtime() - spoken
            // The live model finishes transcribing the owner's turn just after it starts to answer.
            fun ownerText() = app.calls.state.value.transcripts.filter { it.role == "user" && it.final }.joinToString(" ") { it.text }
            await { Regex("(?i)\\b(?:two|2)\\b").containsMatchIn(ownerText()) && Regex("(?i)\\b(?:three|3)\\b").containsMatchIn(ownerText()) }
            // The owner's words are never relayed to the Hermes text chat as a job: the live model talks.
            assertEquals("A spoken question starts no Hermes job", 0, jobs().length())
            return JSONObject().put("native_jobs", 0).put("first_reply_ms", firstReplyMs).put("answer_ms", answerMs).put("synthetic_input", true)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
            }
        }
    }
    @Test(timeout = 300_000) fun geminiCallsMeasureTapToReady() = exercise("gemini")
    @Test(timeout = 300_000) fun grokCallsMeasureTapToReady() = exercise("grok")

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
        var keptScreenOn = false
        rule.runOnUiThread { keptScreenOn = rule.activity.window.decorView.keepScreenOn; rule.activity.window.decorView.keepScreenOn = true }
        var client: BeaconClient? = null
        var session = ""
        var previousSession = ""
        var call = ""
        try {
            voices.setSelection(voices.settings(provider))
            await { !vm.ui.value.restoringSession }
            phase = "reconnecting"
            val authFile = File(app.filesDir, "live-auth.json")
            val auth = if (authFile.isFile) JSONObject(authFile.readText()).also { check(authFile.delete()) } else null
            if (auth != null) loginLiveTest(app, auth.getString("origin"), auth)
            withContext(Dispatchers.Main.immediate) { vm.connect(auth?.getString("origin") ?: vm.ui.value.origin) }.join()
            await { vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession }
            client = requireNotNull(vm.client).detached()
            phase = "opening chat"
            previousSession = vm.chat.value.sessionId
            val chatBody = liveTestChatBody(client, "Voice connection timing " + java.util.UUID.randomUUID())
            withContext(Dispatchers.Main.immediate) { vm.tab("Chat"); vm.newChat(chatBody) }.join()
            await { vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previousSession &&
                vm.chat.value.projectionReady && !vm.ui.value.newChatOpening && vm.authenticatedScope()?.session == vm.chat.value.sessionId }
            session = vm.chat.value.sessionId
            val target = client.scope.session(session)
            repeat(4) { index ->
                val warm = index > 0
                phase = if (warm) "warm call $index" else "cold call"
                rule.waitForIdle()
                if (warm) {
                    // The Call button on screen keeps this conversation's call ready.
                    phase = "warm call $index (prewarm: waiting)"
                    try { withTimeout(30_000) { while (!app.calls.prewarm.ready(target)) { phase = "warm call $index (prewarm: ${app.calls.prewarm.status}; sources ${VoicePrewarm.sources()})"; delay(50) } } }
                    catch (error: TimeoutCancellationException) { throw AssertionError("The Call button did not keep the call ready: $phase", error) }
                    phase = "warm call $index"
                } else {
                    // Cold: no prepared context or session; the call reads and connects everything after the tap.
                    app.calls.prewarm.paused = true; app.calls.prewarm.forget(); app.calls.prewarm.changed()
                    delay(500); app.calls.prewarm.forget()
                }
                val previousCall = app.calls.state.value.callId
                val previousError = app.calls.state.value.error
                var attemptStarted = false
                var firstHeard = -1L
                fun observe() {
                    val state = app.calls.state.value
                    if (firstHeard < 0 && state.active && (state.remoteSpeaking || (state.remoteAudioLevel ?: 0f) > 0f)) firstHeard = SystemClock.elapsedRealtime()
                }
                val started = SystemClock.elapsedRealtime()
                rule.onNodeWithTag("chat-call-beacon").performClick()
                await(45_000) {
                    val state = app.calls.state.value
                    if (state.active || state.callId != previousCall || state.error != previousError) attemptStarted = true
                    if (attemptStarted) check(state.error.isBlank()) { "Voice connection failed: ${state.failureReason} ${state.error}" }
                    observe()
                    attemptStarted && state.active && state.callId.isNotBlank() && state.state in setOf("listening", "speaking")
                }
                val connected = SystemClock.elapsedRealtime() - started
                call = app.calls.state.value.callId
                assertEquals(provider, app.calls.state.value.voiceProvider)
                fun stageAt(name: String): Long? = app.calls.stageLog.firstOrNull { it.optString("stage") == name }?.optLong("elapsed_ms")
                fun trace() = app.calls.stageLog.joinToString(" ") { it.optString("stage") + "@" + it.optLong("elapsed_ms") +
                    listOf("error_code", "close_code", "cause_class", "failed_stage").mapNotNull { key -> it.opt(key)?.let { value -> "/$key=$value" } }.joinToString("") }
                try { withTimeout(45_000) { while (stageAt("first_model_audio") == null) { observe(); delay(20) } } }
                catch (error: TimeoutCancellationException) { throw AssertionError("No greeting audio ($phase): ${trace()}", error) }
                await(45_000) {
                    check(app.calls.state.value.error.isBlank()) { "Greeting failed: ${app.calls.state.value.failureReason}" }
                    observe()
                    app.calls.state.value.transcripts.any { it.role == "assistant" && it.text.isNotBlank() }
                }
                val complete = SystemClock.elapsedRealtime() - started
                // The controller's tap is a few milliseconds after the test's click; stages are moved onto the click's clock.
                val offset = app.calls.tappedAt - started
                val sample = JSONObject().put("provider", provider).put("warm", warm).put("ready_ms", connected)
                    .put("tap_offset_ms", offset).put("greeting_complete_ms", complete)
                for (name in listOf("grant_sent", "grant_ok", "context_sent", "context_ok", "provider_open", "provider_setup_ok", "mic_streaming", "first_model_audio"))
                    stageAt(name)?.let { sample.put(name + "_ms", it + offset) }
                app.calls.stageLog.firstOrNull { it.optString("stage") == "provider_setup_ok" }?.optBoolean("warm")?.let { sample.put("provider_warm", it) }
                if (firstHeard >= 0) sample.put("first_heard_ms", firstHeard - started)
                // When the model's first greeting audio reached the phone, before the playback buffer filled.
                (app.calls.service?.let { BeaconCallService::class.java.getDeclaredField("directVoice").apply { isAccessible = true }.get(it) as? DirectVoice })
                    ?.greetingArrivedMs?.takeIf { it >= 0 }?.let { sample.put("first_model_audio_arrived_ms", it + offset) }
                // When the start note went to a warm session (touch-down or the call's claim), on the test's clock.
                (app.calls.service?.let { BeaconCallService::class.java.getDeclaredField("directVoice").apply { isAccessible = true }.get(it) as? DirectVoice })
                    ?.let { DirectVoice::class.java.getDeclaredField("warm").apply { isAccessible = true }.get(it) as? LiveSession }
                    ?.cueSentAt?.takeIf { it > 0 }?.let { sample.put("start_note_sent_ms", it - started) }
                sample.put("greeting_words", app.calls.state.value.transcripts.first { it.role == "assistant" && it.text.isNotBlank() }.text.length)
                samples.put(sample)
                val setup = sample.optLong("provider_setup_ok_ms", -1)
                assertTrue("Every stage was reported: $sample", listOf("grant_ok_ms", "context_ok_ms", "provider_setup_ok_ms", "mic_streaming_ms", "first_model_audio_ms").all(sample::has))
                assertTrue("The greeting is the live model's audio, after its setup: $sample", sample.getLong("first_model_audio_ms") >= setup)
                assertTrue("Nothing is heard before the provider answered setup: $sample", firstHeard < 0 || firstHeard - started >= setup)
                val context = client.nativeCallContext(call)
                assertTrue(context.getString("backend") in setOf("hermes", "direct"))
                assertEquals(client.auth.profile, context.getString("profile"))
                assertEquals(session, context.getString("selected_session_id"))
                if (provider == "gemini" && index == 0) sample.put("direct_reply", checkDirectReply(app, client, call))
                app.calls.hangup()
                await { !app.calls.state.value.active && app.calls.service == null }
                client.nativeRequest("/api/plugins/beacon/mobile/calls/$call", "DELETE")
                call = ""
                if (!warm) { app.calls.prewarm.paused = false; app.calls.prewarm.changed() }
            }
            assertEquals(4, samples.length())
        } finally {
            withContext(NonCancellable) {
                app.calls.prewarm.paused = false
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
                rule.runOnUiThread { rule.activity.window.decorView.keepScreenOn = keptScreenOn }
            }
        }
    }
}
