package dev.hermes.android.uiux.qa

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.json
import dev.hermes.android.loginLiveTest
import dev.hermes.android.ui.settingsRoute
import dev.hermes.android.ui.settingsSearchRoutes
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Opt-in visual tour against the isolated demo Hermes: opens every Settings page and the main screens through the
 * app's own navigation and saves one screenshot each. Read-only: nothing is saved on the server.
 * Arguments: beaconVisualTour=1, tourCase=<folder name>, tourTheme=dark|light.
 */
class VisualTourDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<BeaconActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var vm: BeaconViewModel
    private val shots = JSONArray()

    private fun capture(index: Int, name: String) {
        compose.waitForIdle()
        // Lets cache-first pages finish their background refresh before the picture.
        Thread.sleep(2_500)
        compose.waitForIdle()
        val dir = File(compose.activity.filesDir, "visual-tour/${args.getString("tourCase", "default")}").apply { mkdirs() }
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val file = "%02d-%s.png".format(index, name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'))
        File(dir, file).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        shots.put(JSONObject().put("file", file).put("screen", name))
        File(dir, "shots.json").writeText(shots.toString(2), Charsets.UTF_8)
    }

    @Test(timeout = 900_000) fun everySettingsPageAndMainScreen() {
        assumeTrue(args.getString("beaconVisualTour") == "1")
        val credentials = JSONObject(File(compose.activity.filesDir, "live-auth.json").readText(Charsets.UTF_8).removePrefix("﻿"))
        val app = compose.activity.application as BeaconApplication
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[BeaconViewModel::class.java] }
        // Only the isolated demo Hermes (served on 8446) is ever signed in to.
        val origin = credentials.getString("origin")
        require(android.net.Uri.parse(origin).let { it.scheme == "https" && it.port == 8446 }) { "The tour only runs against the demo Hermes" }
        runBlocking { loginLiveTest(app, origin, credentials) }
        compose.runOnIdle { vm.connect(origin) }
        compose.waitUntil(90_000) { vm.ui.value.authenticated && vm.client != null && !vm.ui.value.loading }
        // The isolated debug app starts without saved setup; finish it the way the wizard does (Keep connected stays off).
        compose.runOnIdle { vm.finishSetupWizard(keepConnected = false) }
        compose.waitUntil(30_000) { !vm.ui.value.setupWizard && !vm.ui.value.loading }
        assertEquals("default", vm.ui.value.profile)
        args.getString("tourTheme")?.let { theme ->
            require(theme in setOf("dark", "light"))
            lateinit var saved: kotlinx.coroutines.Job
            compose.runOnIdle { saved = vm.nativePreferences(json("theme" to theme, "reduced_motion" to true)) }
            runBlocking { saved.join() }
        }
        var index = 0
        fun screen(name: String, open: BeaconViewModel.() -> Unit) {
            compose.runOnIdle { vm.open() }
            capture(++index, name)
        }
        screen("Chat") { tab("Chat") }
        screen("Drawer") { toggleSessions(true) }
        screen("Model picker") { toggleSessions(false); tab("Chat"); openCatalog("Model & provider") }
        screen("Reasoning") { openCatalog("Reasoning") }
        screen("Assistant chat") { closeCatalog(); tab("Beacon") }
        screen("Settings") { tab("More") }
        for (name in settingsSearchRoutes) screen(name) { more(settingsRoute(name)) }
        screen("Commands") { more("Commands") }
        screen("Session usage") { more("Session usage") }
        screen("Bot Mode") { more("Bots") }
        screen("Schedules") { tab("Tasks") }
        screen("Files") { tab("Spaces") }
        screen("Projects") { tab("Projects") }
        screen("Kanban") { tab("Kanban") }
    }
}
