package dev.hermes.android.health

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.BuildConfig
import dev.hermes.android.core.json
import dev.hermes.android.loginLiveTest
import dev.hermes.android.ui.HealthTarget
import dev.hermes.android.ui.PLUGIN_HEALTH_PATH
import dev.hermes.android.ui.pluginHealthOf
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Opt-in, against the isolated demo Hermes only (served on 8446): Settings › Connection › Beacon plugin shows the values the plugin itself reports,
 * Check now shows the round trip to Hermes, and one screenshot per case is saved for review. Read-only: nothing is saved on the server.
 * Arguments: beaconPluginHealth=1. The cases change the phone's font scale and this app's language; the runner restores them afterwards.
 */
class PluginHealthLiveTest {
    @get:Rule val compose = createAndroidComposeRule<BeaconActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val shots = JSONArray()
    private val appPackage = "dev.hermes.android.debug"

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun capture(case: String, name: String) {
        compose.waitForIdle()
        Thread.sleep(1_500)
        compose.waitForIdle()
        val dir = File(compose.activity.filesDir, "plugin-health/$case").apply { mkdirs() }
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val file = "$name.png"
        File(dir, file).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        shots.put(JSONObject().put("case", case).put("file", file))
        File(compose.activity.filesDir, "plugin-health/shots.json").writeText(shots.toString(2), Charsets.UTF_8)
    }

    private fun mergedText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()

    private fun showPlugin(vm: BeaconViewModel) {
        compose.runOnIdle { vm.more("Connection") }
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("settings-connection-page").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings-connection-page").performScrollToNode(hasTestTag("settings-section:Plugin"))
        try {
            compose.waitUntil(60_000) { compose.onAllNodesWithTag("plugin-health-check").fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: ComposeTimeoutException) {
            capture("failure", "plugin-section")
            throw AssertionError("The Plugin section never showed Check now: " +
                compose.onNodeWithTag("settings-section:Plugin").printToString(maxDepth = 8), timeout)
        }
        compose.onNodeWithTag("settings-connection-page").performScrollToNode(hasTestTag("plugin-health-check"))
    }

    /** The node lies inside the window, not clipped at an edge. */
    private fun assertFullyInView(tag: String) {
        // The layout's own position and size: boundsInRoot is clipped to what is visible, so it cannot tell a cut-off row from a whole one.
        val window = compose.onRoot().fetchSemanticsNode()
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val top = node.positionInRoot.y
        val bottom = top + node.size.height
        assertTrue("$tag is in view: $top..$bottom in 0..${window.size.height}", top >= 0f && bottom <= window.size.height)
    }

    /** The last item of the page (Sign out) at the bottom of the list. */
    private fun scrollToEnd() {
        val page = compose.onNodeWithTag("settings-connection-page")
        val items = page.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount
        page.performScrollToIndex(items - 1)
        compose.waitForIdle()
    }

    @Test(timeout = 900_000) fun theConnectionPageShowsThePluginsRealHealthAndCheckNowTimesHermes() {
        assumeTrue(args.getString("beaconPluginHealth") == "1")
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

        // What the plugin itself reports right now, read apart from the page.
        val reported = runBlocking { vm.client!!.nativeRequest(PLUGIN_HEALTH_PATH, params = mapOf("fresh" to "1")) }
        val installed = reported.getJSONObject("plugin").getString("installed")
        assertEquals("The app and the plugin share one version", BuildConfig.VERSION_NAME.removeSuffix("-preview"), installed)
        val expected = requireNotNull(pluginHealthOf(reported, keepConnected = false, voiceKeySaved = null))

        val originalScale = shell("settings get system font_scale").trim().ifBlank { "1.0" }
        try {
            for ((case, theme, locale, scale) in listOf(
                listOf("dark", "dark", "", ""), listOf("light", "light", "", ""),
                listOf("ar-dark", "dark", "ar", ""), listOf("large-light", "light", "", "1.3"),
            )) {
                lateinit var saved: kotlinx.coroutines.Job
                compose.runOnIdle { saved = vm.nativePreferences(json("theme" to theme, "reduced_motion" to true)) }
                runBlocking { saved.join() }
                // Every case sets both, so a case never inherits the language or text size of the one before it.
                shell("cmd locale set-app-locales $appPackage --locales ${locale.ifBlank { "en-US" }}")
                shell("settings put system font_scale ${scale.ifBlank { originalScale }}")
                compose.waitForIdle()
                showPlugin(vm)
                // The page from its top, so the hero's one-line address and the order of the cards are in a picture.
                compose.onNodeWithTag("settings-connection-page").performScrollToIndex(0)
                compose.waitForIdle()
                capture(case, "00-connection-top")
                compose.onNodeWithTag("settings-connection-page").performScrollToNode(hasTestTag("plugin-health-check"))
                // The page opens from what was kept last time, then reads again; the rows are there before Check now.
                for (tag in listOf("plugin-health-status", "plugin-health-version", "plugin-health-calls", "plugin-health-notifications")) {
                    compose.onNodeWithTag(tag).assertExists()
                }
                capture(case, "01-plugin")
                if (case == "dark") {
                    assertTrue(mergedText("plugin-health-version").contains(installed))
                    // Each row reads what the plugin reported (the words are the mapping's, in the phone's language, here English).
                    assertTrue(mergedText("plugin-health-calls").contains(expected.calls.text))
                    expected.dashboard?.takeIf { it.needsAttention }?.let { assertTrue(mergedText("plugin-health-dashboard").contains(it.text)) }
                    expected.gateway?.takeIf { it.needsAttention }?.let { assertTrue(mergedText("plugin-health-gateway").contains(it.text)) }
                    assertTrue(mergedText("plugin-health-status").contains(expected.overallText))
                }
                compose.onNodeWithTag("plugin-health-check").performClick()
                compose.waitUntil(60_000) { Regex("\\d+").containsMatchIn(mergedText("plugin-health-check")) }
                if (case == "dark") {
                    val milliseconds = Regex("Hermes answered in (\\d+) ms").find(mergedText("plugin-health-check"))?.groupValues?.get(1)?.toLong()
                    assertNotNull("Check now shows how fast Hermes answered: " + mergedText("plugin-health-check"), milliseconds)
                    assertTrue("A real round trip: $milliseconds ms", milliseconds!! in 1..30_000)
                }
                if (case == "ar-dark") assertTrue("The section is in Arabic", compose.onAllNodesWithText("لوحة تحكم", substring = true).fetchSemanticsNodes().isNotEmpty())
                // Check now has brought its own answer into view: no scrolling by hand, whatever the text size.
                assertFullyInView("plugin-health-check")
                capture(case, "02-checked")
                if (case == "dark") {
                    // A row that needs the owner leads to where it is fixed: with Keep connected off, Notifications opens, and Back returns here.
                    if (expected.notifications.target == HealthTarget.Notifications) {
                        compose.onNodeWithTag("plugin-health-notifications").performClick()
                        compose.waitUntil(60_000) { compose.onAllNodesWithTag("notification-settings").fetchSemanticsNodes().isNotEmpty() }
                        compose.runOnIdle { vm.backFromSettings() }
                        compose.waitUntil(60_000) { compose.onAllNodesWithTag("settings-connection-page").fetchSemanticsNodes().isNotEmpty() }
                    }
                }
                scrollToEnd()
                compose.onNodeWithTag("connection-sign-out").assertIsDisplayed()
                capture(case, "06-connection-end")
                // The other pages of this lane, in the same four looks.
                for ((page, tag, name) in listOf(Triple("Settings", "chat-preferences", "03-chat"), Triple("Appearance", "appearance-settings", "04-appearance"), Triple("Notifications", "notification-settings", "05-notifications"))) {
                    compose.runOnIdle { vm.more(page) }
                    compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
                    capture(case, name)
                }
            }
        } finally {
            shell("settings put system font_scale $originalScale")
        }
    }
}
