package dev.hermes.android.agentphone

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.digest
import dev.hermes.android.data.NativePairing
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.voice.DemoCallSignIn
import dev.hermes.android.voice.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in against the coordinator's isolated phone. The call screen watches the agent phone with a watch ticket; with the phone
 *  off that view must start it exactly as the phone page does. No keys, frames, transcripts or tool result bodies are recorded. */
class AgentPhoneWatchStartDeviceTest {
    @get:Rule(order = 0) val signIn = DemoCallSignIn("beaconWatchStart")
    @get:Rule(order = 1) val compose = createAndroidComposeRule<BeaconActivity>()

    private object NoFrames : FrameSink {
        override fun submit(header: FrameHeader, data: ByteArray, done: (Boolean) -> Unit) = done(false)
        override fun clear() = Unit
    }

    @Test fun aWatchViewStartsAPhoneThatIsOff() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val expectedOrigin = args.getString("isolatedOriginSha256").orEmpty()
        assumeTrue(args.getString("fixtureSerial") == "emulator-5578" && expectedOrigin.matches(Regex("[a-f0-9]{64}")))
        val activity = compose.activity
        val app = activity.application as BeaconApplication
        val vm = compose.runOnIdle { ViewModelProvider(activity)[BeaconViewModel::class.java] }
        compose.waitUntil(20_000) { vm.client != null && vm.ui.value.authenticated && !vm.ui.value.restoringSession }
        val client = checkNotNull(vm.client)
        check(client.auth.origin.digest() == expectedOrigin) { "App is signed in to a different Hermes" }
        val device = NativePairing.deviceId(app, client.scope.copy(session = "")) ?: NativePairing.link(app, client)
        checkNotNull(device) { "This phone is not linked" }
        val path = "/api/plugins/beacon/mobile/agent-phone"
        suspend fun status() = client.nativeRequest(path)
        if (status().optString("phone") != "off") {
            client.nativeRequest("$path/stop", "POST", JSONObject())
            withTimeout(60_000) { while (status().optString("phone") != "off") delay(500) }
        }
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        try {
            val session = ViewSession(BeaconViewTransport(client.detached(), watching = true), NoFrames, scope, preflight = true)
            val phases = mutableListOf<ViewPhase>()
            val watching = scope.launch { session.ui.collect { if (phases.lastOrNull() != it.phase) phases += it.phase } }
            session.open()
            try { withTimeout(150_000) { while (status().optString("phone") != "on") delay(1_000) } }
            catch (t: kotlinx.coroutines.TimeoutCancellationException) {
                assertTrue("phases=$phases ui=${session.ui.value} phone=${status().optString("phone")}", false)
            }
            assertTrue("phases=$phases", ViewPhase.Starting in phases)
            withTimeout(90_000) { while (session.ui.value.phase != ViewPhase.Live) delay(500) }
            watching.cancel()
            session.dispose()
        } finally {
            scope.cancel()
            runCatching { client.nativeRequest("$path/stop", "POST", JSONObject()) }
        }
    }

    @Test(timeout = 420_000) fun theCallScreenStartsAPhoneThatWasOff() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val expectedOrigin = args.getString("isolatedOriginSha256").orEmpty()
        assumeTrue(args.getString("fixtureSerial") == "emulator-5578" && expectedOrigin.matches(Regex("[a-f0-9]{64}")))
        val activity = compose.activity
        val app = activity.application as BeaconApplication
        val vm = compose.runOnIdle { ViewModelProvider(activity)[BeaconViewModel::class.java] }
        compose.waitUntil(90_000) { vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession }
        val client = checkNotNull(vm.client)
        check(client.auth.origin.digest() == expectedOrigin) { "App is signed in to a different Hermes" }
        val device = NativePairing.deviceId(app, client.scope.copy(session = "")) ?: NativePairing.link(app, client)
        checkNotNull(device) { "This phone is not linked" }
        val path = "/api/plugins/beacon/mobile/agent-phone"
        suspend fun status() = client.nativeRequest(path)
        suspend fun awaitPhone(want: String, timeoutMs: Long = 120_000) = withTimeout(timeoutMs) {
            while (status().optString("phone") != want) delay(1_000)
        }
        val voice = VoiceKeyStore(app)
        val previousVoice = voice.settings()
        var callId = ""
        try {
            if (voice.key(previousVoice.provider) == null) {
                val withKey = listOf("gemini", "grok", "openai").firstOrNull { voice.key(it) != null }
                checkNotNull(withKey) { "No voice provider key on this phone" }
                voice.setSelection(voice.settings(withKey))
            }
            if (status().optString("phone") != "on") {
                client.nativeRequest("$path/start", "POST", JSONObject())
                awaitPhone("on")
            }
            withContext(Dispatchers.Main.immediate) { vm.tab("Beacon") }
            withTimeout(60_000) { while (vm.ui.value.tab != "Beacon" || !vm.ui.value.connected) delay(200) }
            withTimeout(60_000) { while (vm.chat.value.sessionId.isBlank()) delay(200) }
            compose.onNodeWithTag("chat-call-beacon").performClick()
            compose.waitUntil(90_000) { app.calls.state.value.active && app.calls.state.value.callId.isNotBlank() }
            callId = app.calls.state.value.callId
            check(runCatching { app.calls.service?.clientForHandoff(callId) != null }.getOrDefault(false)) { "No handoff client for the call" }
            client.nativeRequest("$path/stop", "POST", JSONObject())
            awaitPhone("off")
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("beacon-call-phone").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("beacon-call-phone").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("call-phone-panel").fetchSemanticsNodes().isNotEmpty() }
            awaitPhone("on")
            compose.waitUntil(90_000) { compose.onAllNodesWithTag("agent-phone-live").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            withContext(NonCancellable) {
                runCatching { app.calls.hangup() }
                delay(3_000)
                runCatching { voice.setSelection(previousVoice) }
                runCatching { client.nativeRequest("$path/stop", "POST", JSONObject()) }
            }
        }
    }

}
