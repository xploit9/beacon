package dev.hermes.android.journey

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.NativePairing
import dev.hermes.android.liveTestChatBody
import dev.hermes.android.loginLiveTest
import dev.hermes.android.push.KeepConnectedService
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/** Coordinator setup inside demo-fixture isolation; the coordinator restores its original snapshot. */
class DemoLiveSetupTest {
    private val compose = createAndroidComposeRule<BeaconActivity>()
    private val optIn = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("beaconDemoSetup") == "1")
            base.evaluate()
        }
    } }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ownerRuntimeOptIn).around(optIn).around(compose)

    @Test(timeout = 120_000) fun selectDisposableDemoAndVerifyItsOnlyLocalRecipient() = runBlocking {
        val app = compose.activity.application as BeaconApplication
        val args = InstrumentationRegistry.getArguments()
        val profile = args.getString("beaconQaProfile").orEmpty()
        check(profile.matches(Regex("qa-[a-f0-9]{32}")) && args.getString("beaconDemoExclusiveRecipient") == "1")
        val file = File(app.filesDir, "live-auth.json")
        val credentials = JSONObject(file.readText())
        check(file.delete() && credentials.string("purpose") == "beacon_visual_tour_demo")
        val origin = credentials.getString("origin")
        loginLiveTest(app, origin, credentials)
        lateinit var vm: BeaconViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[BeaconViewModel::class.java] }
        withContext(Dispatchers.Main.immediate) { vm.connect(origin) }.join()
        withTimeout(60_000) { while (!vm.ui.value.connected || vm.ui.value.restoringSession || vm.client?.scope?.profile != profile) delay(100) }
        val client = checkNotNull(vm.client)
        val scope = client.scope.copy(session = "")
        check(!app.calls.state.value.active && vm.chat.value.streamId.isBlank())
        liveTestChatBody(client, "setup only")
        val device = checkNotNull(NativePairing.deviceId(app, scope) ?: NativePairing.link(app, client))
        check(NativePairing.devices(client).count { it.id == device } == 1)
        val targets = client.nativeRequest("/api/plugins/beacon/mobile/notifications/status").rows("targets")
            .filter { !it.optBoolean("revoked") && it.optLong("expires") > System.currentTimeMillis() / 1000 }
        check(targets.isEmpty())
        withContext(Dispatchers.Main.immediate) { vm.finishSetupWizard(keepConnected = true) }
        withTimeout(45_000) { while (vm.ui.value.setupWizard || vm.ui.value.loading || KeepConnectedService.status(app) != KeepConnectedService.STATUS_CONNECTED) delay(100) }
        check(KeepConnectedService.isEnabledFor(app, scope) && vm.client === client)
        File(app.filesDir, "demo-live-setup.json").writeText(json("run" to java.util.UUID.randomUUID().toString(), "profile" to profile, "origin_sha256" to scope.origin.digest(),
            "device_id" to device, "local_delivery" to true, "fcm_targets" to 0, "ready" to true).toString())
    }
}
