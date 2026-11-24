package dev.hermes.android.agentphone

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.digest
import dev.hermes.android.core.rows
import dev.hermes.android.data.NativePairing
import dev.hermes.android.ui.AGENT_PHONE_ROUTE
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in against the coordinator's isolated phone. No keys, frames, transcripts or tool result bodies are recorded. */
class CallPhoneWatchDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<BeaconActivity>()
    private fun node(tag: String) = compose.onNodeWithTag(tag)
    private fun waitForTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun callDrivesTheWatchedPhoneUntilExplicitTakeover() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val expectedOrigin = args.getString("isolatedOriginSha256").orEmpty()
        assumeTrue(args.getString("fixtureSerial") == "emulator-5578" && expectedOrigin.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BeaconApplication
        val activity = compose.activity
        val vm = compose.runOnIdle { ViewModelProvider(activity)[BeaconViewModel::class.java] }
        compose.waitUntil(20_000) { vm.client != null && vm.ui.value.authenticated && !vm.ui.value.restoringSession }
        val client = checkNotNull(vm.client)
        check(client.auth.origin.digest() == expectedOrigin) { "App is signed in to a different Hermes" }
        check(NativePairing.devices(client).single().id == NativePairing.deviceId(app, client.scope)) { "Expected this emulator alone" }
        val path = "/api/plugins/beacon/mobile/agent-phone"
        suspend fun status() = client.nativeRequest(path)
        suspend fun newest() = client.nativeRequest("$path/activity").rows("activity").maxOfOrNull { it.optLong("seq") } ?: 0L
        fun holder(status: JSONObject) = status.getJSONObject("lease").getString("holder")
        val initial = status()
        check(initial.optBoolean("yours") && initial.getString("phone") == "on" && holder(initial) == "agent")
        check(!app.calls.state.value.active)
        compose.runOnIdle { vm.more(AGENT_PHONE_ROUTE) }
        waitForTag("agent-phone-open")
        node("agent-phone-open").performScrollTo().performClick()
        waitForTag("agent-phone-live")
        assertEquals("agent", holder(status()))
        val baseline = newest()
        // The independent deadline also applies if the UI test clock is waiting for a frame.
        val watchdog = CoroutineScope(Dispatchers.Main + SupervisorJob())
        var ownedStart = 0L
        var takenEpoch: Long? = null
        try {
            node("agent-phone-call").performClick()
            compose.waitUntil(15_000) { app.calls.state.value.active }
            ownedStart = app.calls.state.value.startedAt
            watchdog.launch {
                delay((165_000 - (System.currentTimeMillis() - ownedStart)).coerceAtLeast(0))
                if (app.calls.state.value.active && app.calls.state.value.startedAt == ownedStart) app.calls.hangup()
            }
            node("agent-phone-live").assertIsDisplayed()
            assertEquals("agent", holder(status()))
            waitForTag("agent-phone-show-call")
            node("agent-phone-show-call").performClick()
            waitForTag("beacon-call-phone")
            node("beacon-call-phone").assertIsEnabled().performClick()
            waitForTag("call-phone-panel")
            waitForTag("agent-phone-live")
            val prompt = "On your virtual phone, use agent_phone to open the Clock app and tap its Timer tab. Leave the timer stopped."
            if (args.getString("phoneToolInput") == "chat") {
                check(vm.chat.value.sessionId == app.calls.state.value.conversationScope?.session)
                compose.runOnIdle { vm.composer(TextFieldValue(prompt)); vm.send() }
            } else {
                node("call-phone-ask").performClick()
                node("call-phone-message").performTextInput(prompt)
                node("call-phone-send").performClick()
            }
            withTimeout(90_000) {
                while (true) {
                    val rows = client.nativeRequest("$path/activity").rows("activity")
                    if (rows.any { it.optLong("seq") > baseline && it.optString("outcome") == "done" &&
                            it.optString("app").contains("clock", ignoreCase = true) }) break
                    delay(1250)
                }
            }
            assertTrue(app.calls.state.value.active)
            assertEquals("agent", holder(status()))
            node("agent-phone-live").assertIsDisplayed()
            node("agent-phone-take-control").performClick()
            withTimeout(12_000) {
                while (takenEpoch == null) {
                    val lease = status().getJSONObject("lease")
                    if (lease.optBoolean("you")) takenEpoch = lease.getLong("epoch") else delay(200)
                }
            }
            waitForTag("agent-phone-done")
            node("agent-phone-done").performClick()
            withTimeout(10_000) { while (holder(status()) != "agent") delay(200) }
        } finally {
            val call = app.calls.state.value
            if (call.active && call.startedAt == ownedStart) app.calls.hangup()
            watchdog.cancel()
            takenEpoch?.let { epoch ->
                val lease = status().getJSONObject("lease")
                if (lease.optBoolean("you") && lease.optLong("epoch") == epoch) {
                    client.nativeRequest("$path/hand-back", "POST", JSONObject().put("epoch", epoch))
                }
            }
        }
    }
}
