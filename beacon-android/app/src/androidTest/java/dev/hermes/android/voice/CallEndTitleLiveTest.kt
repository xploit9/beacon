package dev.hermes.android.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.json
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.liveTestChatBody
import dev.hermes.android.loginLiveTest
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * APPFIX-121 (backlog 47/66), live on the demo Hermes with Gemini Live: two voice-only calls (nobody speaks), each in its own untitled chat. The first title was "Call with
 * Beacon" for both, and Hermes refused the second (a dispatch crash in its errors.log per call). Now both chats are titled, differently, and the gateway logs no crash.
 * The titles and the session ids are written to files/callend-title.json for the report; no audio is kept.
 */
class CallEndTitleLiveTest {
    @get:Rule(order = 0) val signIn = DemoCallSignIn()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<BeaconActivity>()
    /** One line in the run's raw instrumentation output (INSTRUMENTATION_STATUS: appfix121=...). */
    private fun report(line: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("appfix121", line) })

    @Test(timeout = 420_000) fun twoVoiceOnlyCallsTitleTheirChatsDifferently() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in: beaconLiveService=1 and the staged demo sign-in (device-test.py --isolated)", args.getString("beaconLiveService") == "1" && signIn.staged)
        require(args.getString("beaconNoHostAudio") == "1")
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        require(app.packageName.endsWith(".debug") && !app.calls.state.value.active)
        require(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val voices = VoiceKeyStore(app)
        val previousVoice = voices.settings()
        require(voices.hasKey("gemini")) { "Needs the saved Gemini phone key" }
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        suspend fun await(ms: Long = 30_000, what: String, condition: () -> Boolean) = withTimeout(ms) {
            try { while (!condition()) delay(50) } catch (error: TimeoutCancellationException) { throw AssertionError("Timed out: $what (call=${app.calls.state.value.state} err=${app.calls.state.value.error})", error) }
        }
        val result = JSONObject().put("calls", JSONArray())
        val sessions = mutableListOf<String>()
        var keptScreenOn = false
        rule.runOnUiThread { keptScreenOn = rule.activity.window.decorView.keepScreenOn; rule.activity.window.decorView.keepScreenOn = true }
        try {
            voices.setSelection(voices.settings("gemini"))
            await(what = "the staged demo sign-in connects") { vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession && !vm.ui.value.setupWizard }
            await(what = "chat tab ready") { vm.ui.value.tab == "Chat" && !vm.ui.value.newChatOpening && (vm.chat.value.sessionId.isBlank() || vm.chat.value.projectionReady) }
            val client = requireNotNull(vm.client).detached()
            repeat(2) { index ->
                val previous = vm.chat.value.sessionId
                // An untitled chat: no title in the create (liveTestChatBody names one, so it is dropped). A plain New chat opens the empty chat this phone already made, so the first
                // call may use the one already open; the second needs another (the first now has a call title).
                val body = liveTestChatBody(client, "unused").apply { remove("title") }
                withContext(Dispatchers.Main.immediate) { vm.tab("Chat"); vm.newChat(body) }.join()
                await(what = "chat $index open") { vm.chat.value.sessionId.isNotBlank() && !vm.chat.value.sessionId.startsWith("local-") && (index == 0 || vm.chat.value.sessionId != previous) && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
                val session = vm.chat.value.sessionId; sessions += session
                assertEquals("The chat starts untitled", "New conversation", vm.chat.value.title)
                // The Call button stands where Send would be only while the message box is empty; a phone that kept an old draft shows Send instead.
                rule.runOnIdle { vm.composer(androidx.compose.ui.text.input.TextFieldValue("")) }
                rule.waitForIdle()
                try { rule.waitUntil(15_000) { rule.onAllNodesWithTag("chat-call-beacon").fetchSemanticsNodes().isNotEmpty() } }
                catch (error: Throwable) { throw AssertionError("No Call button: tab=${vm.ui.value.tab} wizard=${vm.ui.value.setupWizard} more='${vm.ui.value.morePage}' chat=${vm.chat.value.sessionId} status='${vm.chat.value.status}' composer='${vm.composer.value.text.length}' error='${vm.ui.value.error}' notice='${vm.ui.value.notice}'", error) }
                val previousCall = app.calls.state.value.callId
                rule.onNodeWithTag("chat-call-beacon").performClick()
                await(60_000, "call $index connects") { val s = app.calls.state.value; s.active && s.callId != previousCall && s.callId.isNotBlank() && s.state in setOf("listening", "speaking") }
                val call = app.calls.state.value.callId
                val startedAt = app.calls.state.value.startedAt
                // Nobody speaks: no user line, so the call has no spoken request. A moment of the model's own greeting is fine.
                delay(8_000)
                assertTrue("Nobody spoke", app.calls.state.value.transcripts.none { it.role == "user" && it.text.isNotBlank() })
                app.calls.hangup()
                await(60_000, "call $index ends") { !app.calls.state.value.active && app.calls.service == null }
                await(60_000, "chat $index titled") { vm.chat.value.sessionId == session && vm.chat.value.title != "New conversation" && vm.chat.value.title.isNotBlank() }
                (result.getJSONArray("calls")).put(JSONObject().put("session", session).put("call", call).put("title", vm.chat.value.title).put("startedAt", startedAt))
                // Also in the instrumentation output, so the run's log carries it.
                report("APPFIX121_CALL index=$index session=$session title='${vm.chat.value.title}' startedAt=$startedAt")
                runCatching { client.nativeRequest("/api/plugins/beacon/mobile/calls/$call", "DELETE") }
            }
            val titles = (0 until 2).map { result.getJSONArray("calls").getJSONObject(it).getString("title") }
            assertTrue("Both calls are named after the call: $titles", titles.all { it.startsWith("Call with ") })
            assertNotEquals("The two chats are told apart: $titles", titles[0], titles[1])
            val minutes = (0 until 2).map { result.getJSONArray("calls").getJSONObject(it).getLong("startedAt") / 60_000 }
            result.put("same_minute", minutes[0] == minutes[1])
            report("APPFIX121_TITLES titles=$titles same_minute=${minutes[0] == minutes[1]}")
        } finally {
            withContext(NonCancellable) {
                app.calls.hangup()
                withTimeoutOrNull(60_000) { while (app.calls.service != null) delay(100) }
                result.put("sessions", JSONArray(sessions))
                File(app.filesDir, "callend-title.json").writeText(result.toString())
                voices.setSelection(previousVoice)
                rule.runOnUiThread { rule.activity.window.decorView.keepScreenOn = keptScreenOn }
            }
        }
    }
}
