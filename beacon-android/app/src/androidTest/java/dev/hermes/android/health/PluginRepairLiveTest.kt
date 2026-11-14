package dev.hermes.android.health

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.loginLiveTest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Opt-in, against the isolated demo Hermes only (served on 8446): the real app taps Repair Beacon on a demo whose Beacon is not the release
 * (the runner put a previous release on it first), confirms, and waits until Beacon, the dashboard and the gateway all run the release.
 * Arguments: beaconPluginRepair=1 and expectedPlugin=<the release's plugin version>. It changes the demo's plugin; the runner restores it.
 */
class PluginRepairLiveTest {
    @get:Rule val compose = createAndroidComposeRule<BeaconActivity>()
    private val args get() = InstrumentationRegistry.getArguments()

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(1_000)
        val dir = requireNotNull(compose.activity.getExternalFilesDir("plugin-repair")).apply { mkdirs() }
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun versions(vm: BeaconViewModel): JSONObject = runBlocking { vm.client!!.nativeRequest("/api/plugins/beacon/mobile/plugin/version") }

    @Test(timeout = 1_200_000) fun repairPutsTheReleaseBackAndEverythingRunsIt() {
        assumeTrue(args.getString("beaconPluginRepair") == "1")
        val expected = args.getString("expectedPlugin").orEmpty()
        require(Regex("\\d+\\.\\d+\\.\\d+").matches(expected)) { "Say which release the demo must end on" }
        val credentials = JSONObject(File(compose.activity.filesDir, "live-auth.json").readText(Charsets.UTF_8).removePrefix("﻿"))
        val app = compose.activity.application as BeaconApplication
        val origin = credentials.getString("origin")
        require(android.net.Uri.parse(origin).let { it.scheme == "https" && it.port == 8446 }) { "Only the isolated demo Hermes is ever signed in to" }
        lateinit var vm: BeaconViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[BeaconViewModel::class.java] }
        runBlocking { loginLiveTest(app, origin, credentials) }
        compose.runOnIdle { vm.connect(origin) }
        compose.waitUntil(90_000) { vm.ui.value.authenticated && vm.client != null && !vm.ui.value.loading }
        compose.runOnIdle { vm.finishSetupWizard(keepConnected = false) }
        compose.waitUntil(30_000) { !vm.ui.value.setupWizard && !vm.ui.value.loading }

        // The demo starts on something other than the release: that is the damage this test repairs.
        val before = versions(vm)
        assertNotEquals("The demo must not already run the release", expected, before.getString("installed"))

        compose.runOnIdle { vm.more("Connection") }
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("settings-connection-page").fetchSemanticsNodes().isNotEmpty() }
        // The section is there at once; its rows (and Repair Beacon among them) once the plugin's answer has loaded.
        compose.onNodeWithTag("settings-connection-page").performScrollToNode(hasTestTag("settings-section:Plugin"))
        compose.waitUntil(90_000) { compose.onAllNodesWithTag("plugin-repair").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings-connection-page").performScrollToNode(hasTestTag("plugin-repair"))
        capture("01-before")
        compose.onNodeWithTag("plugin-repair").performClick()
        compose.onNodeWithTag("plugin-repair-confirm").assertIsDisplayed()
        capture("02-sheet")
        compose.onNodeWithTag("plugin-repair-confirm").performClick()

        // Install, then (only because the dashboard still runs older code) the guarded gateway restart and the wait for the dashboard.
        compose.waitUntil(900_000) {
            compose.onAllNodesWithTag("plugin-repair-done").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("plugin-repair-retry").fetchSemanticsNodes().isNotEmpty()
        }
        capture("03-after")
        assertTrue("Repair ended with a failure line instead of Beacon is repaired: ${vm.pluginRepair.state.value}", compose.onAllNodesWithTag("plugin-repair-done").fetchSemanticsNodes().isNotEmpty())

        // The section reads Beacon again when the repair ends, so its Version row names the release instead of the old answer.
        compose.waitUntil(60_000) { compose.onAllNodes(hasTestTag("plugin-health-version") and hasText(expected, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("04-version-refreshed")

        // Beacon answers, and the dashboard process, the files and the gateway all run the release.
        var after = versions(vm)
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && (after.getString("running") != expected || after.getString("gateway") != expected)) {
            Thread.sleep(2_000); after = versions(vm)
        }
        assertEquals(expected, after.getString("installed"))
        assertEquals("The dashboard runs the release", expected, after.getString("running"))
        assertEquals("The gateway runs the release", expected, after.getString("gateway"))
        File(requireNotNull(compose.activity.getExternalFilesDir("plugin-repair")), "result.json").writeText(JSONObject().put("before", before).put("after", after).toString(2), Charsets.UTF_8)
    }
}
