package dev.hermes.android.journey

import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.loginLiveTest
import dev.hermes.android.updates.HermesPluginInstaller
import dev.hermes.android.updates.RepairFinding
import dev.hermes.android.updates.RepairStage
import dev.hermes.android.updates.RepairUi
import dev.hermes.android.updates.olderVersion
import dev.hermes.android.updates.repairFinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File

/**
 * Repair Beacon against the DEMO Hermes (never the owner's) when the demo's plugin is newer than the latest published release: the repair
 * must find that, install nothing and change nothing. The release is read from GitHub for real, the plugin from the demo for real.
 * Run only with `-e beaconDemoRepair 1` (plus the owner-runtime flags the live journeys use) under `device-test.py --isolated`, which stages
 * the demo's one-shot sign-in. If the demo plugin is not newer than the release the test stops before it asks for any repair.
 */
class DemoRepairLiveTest {
    private val compose = createAndroidComposeRule<BeaconActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ownerRuntimeOptIn).around(compose)

    /** The demo account is signed in from the one-shot file the isolation step places in the app's private storage; nothing secret is on a command line. */
    private suspend fun signInDemoWhenAsked() {
        val app = compose.activity.application as BeaconApplication
        val file = File(app.filesDir, "live-auth.json")
        if (!file.isFile) return
        val credentials = JSONObject(file.readText())
        check(file.delete() && credentials.string("purpose") in setOf("beacon_visual_tour_demo", "beacon_vault_demo"))
        val origin = credentials.getString("origin")
        loginLiveTest(app, origin, credentials)
        lateinit var vm: BeaconViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[BeaconViewModel::class.java] }
        withContext(Dispatchers.Main.immediate) { vm.connect(origin) }.join()
    }

    private fun say(line: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "DEMO_REPAIR $line\n") })

    @Test(timeout = 300_000) fun repairLeavesADemoBeaconThatIsNewerThanTheLatestReleaseAlone() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("beaconDemoRepair") == "1")
        signInDemoWhenAsked()
        val h = OwnerJourneyHarness(compose, "demorepair")
        // The harness hides the cause of a failure; one short line of it goes to the instrumentation output instead (no secrets on this page).
        val diagnose = { error: Throwable -> say("failed ${error.javaClass.simpleName}: ${error.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(220)}") }
        h.checked {
            // A freshly signed-in app shows its setup wizard first, which covers Settings.
            if (vm.ui.value.setupWizard) {
                compose.runOnIdle { vm.finishSetupWizard(keepConnected = false) }
                await(60_000) { !vm.ui.value.setupWizard && !vm.ui.value.loading }
            }
            try {
                val home = client.nativeRpc("config.get", json("key" to "profile")).optString("home").replace('\\', '/').trimEnd('/')
                check(home.endsWith("/hermes-demo") || home.contains("/hermes-demo/")) { "demo_home_required" }
                mark("R-01", "demo_home_verified", "passed")
                val installer = HermesPluginInstaller(client)
                val published = installer.published()
                val hub = checkNotNull(installer.hub()) { "demo_lists_no_beacon" }
                val before = checkNotNull(installer.versions()) { "demo_beacon_does_not_answer" }
                say("release=${published.version} commit=${published.commit.take(12)} hub=${hub.version} on_disk=${before.installed} dashboard=${before.running} gateway=${before.gateway}")
                // Nothing is asked of the demo unless the scenario really holds.
                check(olderVersion(published.version, before.installed)) { "demo_plugin_not_newer_than_release" }
                check(repairFinding(hub, before, published.version) == RepairFinding.Newer)
                mark("R-01", "demo_plugin_newer_than_release", "passed")

                // The owner's own path: Settings > Connection > Beacon plugin > Repair Beacon, confirm.
                compose.runOnIdle { vm.more("Connection") }
                await(60_000) { compose.onAllNodesWithTag("settings-connection-page").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("settings-connection-page").performScrollToNode(hasTestTag("settings-section:Plugin"))
                await(90_000) { compose.onAllNodesWithTag("plugin-repair").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("settings-connection-page").performScrollToNode(hasTestTag("plugin-repair"))
                compose.onNodeWithTag("plugin-repair").performClick()
                compose.onNodeWithTag("plugin-repair-confirm").assertIsDisplayed()
                compose.onNodeWithTag("plugin-repair-confirm").performClick()
                await(120_000) {
                    compose.onAllNodesWithTag("plugin-repair-done").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("plugin-repair-retry").fetchSemanticsNodes().isNotEmpty()
                }
                val result = vm.pluginRepair.state.value
                say("repair_stage=${result.stage} finding=${result.finding} problem=${result.problem}")
                check(result == RepairUi(RepairStage.Done, finding = RepairFinding.Newer)) { "repair_did_not_leave_it_alone" }
                // The row says it left Beacon alone, and never claims a repair that did not happen.
                compose.onNodeWithTag("plugin-repair-done").assertTextContains("Beacon is newer than the latest release.")
                check(compose.onAllNodesWithText("Beacon is repaired.").fetchSemanticsNodes().isEmpty())
                mark("R-01", "repair_found_newer_and_finished", "passed")

                val after = checkNotNull(installer.versions())
                val hubAfter = checkNotNull(installer.hub())
                check(after == before && hubAfter == hub) { "repair_changed_the_demo_plugin" }
                say("unchanged on_disk=${after.installed} dashboard=${after.running} gateway=${after.gateway}")
                mark("R-01", "demo_plugin_unchanged", "passed")
            } catch (error: Throwable) { diagnose(error); throw error }
        }
    }
}
