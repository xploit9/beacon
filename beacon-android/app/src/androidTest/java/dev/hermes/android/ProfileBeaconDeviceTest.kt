package dev.hermes.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.digest
import dev.hermes.android.core.string
import dev.hermes.android.voice.DemoCallSignIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in against the isolated demo Hermes: opening the assistant from another profile must never show its chat under that profile. */
class ProfileBeaconDeviceTest {
    @get:Rule(order = 0) val signIn = DemoCallSignIn("beaconProfileIsolation")
    @get:Rule(order = 1) val compose = createAndroidComposeRule<BeaconActivity>()

    @Test(timeout = 180_000) fun openingTheAssistantFromWorkSwitchesBeforeItsChatIsShown() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val expectedOrigin = args.getString("isolatedOriginSha256").orEmpty()
        assumeTrue(args.getString("fixtureSerial") == "emulator-5578" && expectedOrigin.matches(Regex("[a-f0-9]{64}")))
        val activity = compose.activity
        val vm = compose.runOnIdle { ViewModelProvider(activity)[BeaconViewModel::class.java] }
        compose.waitUntil(90_000) { vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession }
        val first = checkNotNull(vm.client)
        check(first.auth.origin.digest() == expectedOrigin) { "App is signed in to a different Hermes" }
        val beacon = first.nativeRequest("/api/plugins/beacon/mobile/assistant-conversation").string("conversation_id")
        check(beacon.isNotBlank()) { "The demo has no assistant chat" }
        compose.runOnIdle { vm.switchProfile("work") }
        compose.waitUntil(60_000) { vm.ui.value.profile == "work" && vm.client?.scope?.profile == "work" }
        compose.runOnIdle { vm.tab("Beacon") }
        val seen = mutableListOf<String>()
        withTimeout(60_000) {
            while (true) {
                val ui = vm.ui.value
                val chat = vm.chat.value
                seen += "${ui.profile}|${chat.sessionId.take(8)}|${ui.beaconOpening}"
                if (ui.profile != "default") assertNotEquals("assistant chat under ${ui.profile}: $seen", beacon, chat.sessionId)
                if (ui.profile == "default" && chat.sessionId == beacon && !ui.beaconOpening) break
                delay(50)
            }
        }
        assertEquals("default", vm.ui.value.profile)
        assertEquals(beacon, vm.chat.value.sessionId)
        assertFalse(vm.ui.value.beaconOpening)
    }
}
