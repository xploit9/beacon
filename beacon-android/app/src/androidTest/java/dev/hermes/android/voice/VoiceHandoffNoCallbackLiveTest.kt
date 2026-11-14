package dev.hermes.android.voice

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.liveTestChatBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Owner trial 8 Nov 2026 (opt-in, emulator only): a long request typed into a real Grok call is handed to Hermes with NO
 * callback consent, the call is hung up, the Hermes job keeps running, and its result lands in the same chat.
 * Records ids, statuses and timings only; no conversation text other than the synthetic marker is retained.
 */
class VoiceHandoffNoCallbackLiveTest {
    @get:Rule val rule = createAndroidComposeRule<BeaconActivity>()

    @Test(timeout = 720_000) fun handedOffWorkRunsAfterHangupWithoutCallbackAndLandsInTheSameChat() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("beaconLiveService") == "1")
        require(args.getString("beaconNoHostAudio") == "1")
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        require(app.packageName.endsWith(".debug") && !app.calls.state.value.active)
        val voices = VoiceKeyStore(app)
        require(voices.hasKey("grok")) { "A saved Grok key on the test phone is required" }
        val previousVoice = voices.settings()
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        suspend fun await(ms: Long, what: String, condition: suspend () -> Boolean) {
            try { withTimeout(ms) { while (!condition()) delay(100) } }
            catch (error: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("handoff timeout waiting for $what", error) }
        }
        val evidence = JSONObject().put("test", "voice-handoff-no-callback")
        val output = File(app.filesDir, "voice-handoff-live.json")
        fun save(phase: String) { evidence.put("phase", phase); output.writeText(evidence.toString(), Charsets.UTF_8) }
        var client: BeaconClient? = null
        var call = ""
        var session = ""
        val marker = "LIVEHANDOFF" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        try {
            voices.setSelection(voices.settings("grok"))
            await(90_000, "connected session") { vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession }
            val api: BeaconClient = requireNotNull(vm.client).detached()
            client = api
            suspend fun callbacks() = api.get("/api/crons").rows("jobs").filter { it.optString("name").startsWith("Beacon callback ") }.map { it.getString("id") }.toSet()
            val callbacksBefore = callbacks()
            rule.runOnIdle { vm.tab("Chat") }
            await(60_000, "chat tab") { vm.ui.value.tab == "Chat" && !vm.ui.value.newChatOpening && (vm.chat.value.sessionId.isBlank() || vm.chat.value.projectionReady) }
            val previousSession = vm.chat.value.sessionId
            withContext(Dispatchers.Main.immediate) { vm.newChat(liveTestChatBody(api, "Live hand-off " + marker)) }.join()
            await(90_000, "new chat") { vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previousSession && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening && vm.authenticatedScope()?.session == vm.chat.value.sessionId }
            session = vm.chat.value.sessionId
            evidence.put("session_id", session).put("marker", marker); save("chat_ready")

            val tap = SystemClock.elapsedRealtime()
            rule.onNodeWithTag("chat-call-beacon").performClick()
            await(45_000, "call active") { app.calls.state.value.active && app.calls.state.value.state in setOf("thinking", "listening", "speaking") }
            call = app.calls.state.value.callId
            assertEquals("grok", app.calls.state.value.voiceProvider)
            evidence.put("call_id", call).put("tap_to_active_ms", SystemClock.elapsedRealtime() - tap); save("call_active")
            app.calls.service?.mute(true) // synthetic typed turn only; nothing is heard from the emulator
            await(60_000, "model greeting") { app.calls.state.value.transcripts.any { it.role == "assistant" && it.final } }

            suspend fun jobs() = api.nativeRequest("/api/plugins/beacon/mobile/calls/$call").rows("jobs")
            assertTrue("No Hermes work before the request", jobs().isEmpty())
            val request = "Hand this to Hermes as one background task in this same chat with hermes_work: run the harmless terminal command python -c \"import time; time.sleep(45)\" and then reply with only the word $marker. " +
                "This is a long request, so I am hanging up right after you have started it. Do not ask to call me back and do not schedule any callback; I will read the result in this chat."
            val typed = SystemClock.elapsedRealtime()
            assertTrue("The production typed call action accepts the request", app.calls.sendText(call, request))
            save("request_sent")
            await(150_000, "one admitted Hermes job") { jobs().isNotEmpty() }
            val admitted = jobs()
            assertEquals("Exactly one Hermes job is admitted", 1, admitted.size)
            val job = admitted.single()
            evidence.put("job_id", job.string("job_id")).put("job_status_at_admission", job.string("status"))
                .put("callback_consent_at_admission", job.string("callback_consent")).put("will_continue_at_admission", job.opt("will_continue").toString())
                .put("typed_to_job_ms", SystemClock.elapsedRealtime() - typed)
            assertEquals("The job is stored in this chat", session, job.string("stored_session_id"))
            assertTrue("No callback consent is requested for this hand-off", job.string("callback_consent") != "requested")
            save("job_admitted")

            val hangup = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main.immediate) { app.calls.hangup() }
            await(30_000, "call ended") { !app.calls.state.value.active }
            evidence.put("hangup_ms", SystemClock.elapsedRealtime() - hangup)
            val afterHangup = jobs().single()
            evidence.put("job_status_after_hangup", afterHangup.string("status")); save("hung_up")
            assertTrue("The job was still unfinished or done after hangup, never failed", afterHangup.string("status") !in setOf("failed", "cancelled"))

            await(300_000, "job done") {
                val state = jobs().single()
                check(state.string("status") !in setOf("failed", "cancelled")) { "Hermes job is ${state.string("status")}" }
                state.string("status") == "done"
            }
            val done = SystemClock.elapsedRealtime()
            evidence.put("hangup_to_done_ms", done - hangup); save("job_done")
            assertTrue("Done job carries the marker", jobs().single().string("result").contains(marker))

            await(60_000, "result in the same chat") {
                val read = api.detached()
                try {
                    read.nativeSession(session)
                    read.nativeSessionSnapshot().rows("messages").any { it.string("role") == "assistant" && it.string("content").contains(marker) }
                } finally { read.closeNative() }
            }
            evidence.put("done_to_chat_ms", SystemClock.elapsedRealtime() - done).put("result_in_same_chat", true)
            assertEquals("No callback was scheduled", callbacksBefore, callbacks())
            evidence.put("callbacks_created", 0); save("passed")
        } catch (error: Throwable) {
            evidence.put("failure", (error.javaClass.simpleName + ": " + (error.message ?: "")).take(300)); save("failed"); throw error
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { if (app.calls.state.value.active) app.calls.hangup() }
            voices.setSelection(previousVoice)
            // Delete the chat this test made, with the hand-off job's work in it. The plugin has no per-job purge route (finished jobs age out with their call); the ids stay in the evidence file.
            if (session.isNotBlank() && client != null) withContext(NonCancellable) {
                val removal = runCatching { client.detached().let { gone -> try { gone.post("/api/session/delete", json("session_id" to session)).string("deleted") == session } finally { gone.closeNative() } } }
                evidence.put("chat_deleted", removal.getOrDefault(false)).put("job_purge", "ages_out_with_call")
                if (!removal.getOrDefault(false)) evidence.put("cleanup_pending", true)
                save(evidence.optString("phase", "cleanup"))
            }
            client?.let { runCatching { it.closeNative() } }
        }
    }
}
