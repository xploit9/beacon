package dev.hermes.android.agentphone

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.core.digest
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.NativePairing
import dev.hermes.android.preferences
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.DemoCallSignIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in against the isolated demo Hermes: the call screen's own phone window, with no call service to hand it an account. */
class CallPhoneDialogStartDeviceTest {
    @get:Rule(order = 0) val signIn = DemoCallSignIn("beaconWatchStart")
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

    @Test(timeout = 300_000) fun theDialogStartsAPhoneThatIsOffWithoutACallService() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val expectedOrigin = args.getString("isolatedOriginSha256").orEmpty()
        assumeTrue(args.getString("fixtureSerial") == "emulator-5578" && expectedOrigin.matches(Regex("[a-f0-9]{64}")))
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val origin = app.preferences.data.first()[stringPreferencesKey("origin")].orEmpty().ifBlank { signIn.origin }
        check(origin.isNotBlank() && origin.digest() == expectedOrigin) { "App is signed in to a different Hermes" }
        val client = BeaconClient(app.auth.read(origin), app.auth, persistContext = false)
        checkNotNull(NativePairing.deviceId(app, client.scope) ?: NativePairing.link(app, client)) { "This phone is not linked" }
        val path = "/api/plugins/beacon/mobile/agent-phone"
        suspend fun status() = client.nativeRequest(path)
        if (status().optString("phone") != "off") {
            client.nativeRequest("$path/stop", "POST", JSONObject())
            withTimeout(60_000) { while (status().optString("phone") != "off") delay(500) }
        }
        val call = CallState(active = true, state = "listening", callId = "fixture-call",
            conversationScope = client.scope.session("fixture-chat"))
        try {
            compose.setContent { BeaconTheme(dark = true) { CallPhoneView({ callPhoneClient(app, call, app.calls) }, call, app.calls, onDismiss = {}) } }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("call-phone-panel").fetchSemanticsNodes().isNotEmpty() }
            withTimeout(150_000) { while (status().optString("phone") != "on") delay(1_000) }
            compose.waitUntil(90_000) { compose.onAllNodesWithTag("agent-phone-live").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            runCatching { client.nativeRequest("$path/stop", "POST", JSONObject()) }
        }
    }
}
