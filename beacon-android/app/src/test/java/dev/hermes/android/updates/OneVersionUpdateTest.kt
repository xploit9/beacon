package dev.hermes.android.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner, 3 Nov: the app and the plugin are one version. The app installs only once the plugin runs the release's version; a plugin
 * that did not update, or still waits for a restart, holds the app back. A Hermes without the plugin has nothing to match.
 */
class OneVersionUpdateTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }

    @Test fun theAppGoesOnOnlyWhenThePluginRunsTheReleasesVersion() {
        val allowed = setOf(PluginStatus.Hidden, PluginStatus.UpToDate, PluginStatus.Updated, PluginStatus.Newer)
        for (status in PluginStatus.entries) {
            assertEquals(status.name, status in allowed, pluginAllowsApp(PluginRow(status, "1.1.26", "1.1.27")))
        }
        // The states the owner meets when Hermes would not restart or the update failed all hold the app back.
        for (status in listOf(PluginStatus.Waiting, PluginStatus.Failed, PluginStatus.RestartFailed, PluginStatus.DashboardFailed, PluginStatus.DashboardRestart, PluginStatus.Restart))
            assertFalse(status.name, pluginAllowsApp(PluginRow(status, "1.1.26", "1.1.27")))
    }

    @Test fun theUpdateChecksThePluginBeforeItDownloadsOrInstallsTheApp() {
        val source = File(app, "src/main/java/dev/hermes/android/updates/UpdateManager.kt").readText().replace("\r\n", "\n")
        val flow = source.substring(source.indexOf("private suspend fun runUpdate(")).substringBefore("\n    }\n")
        val gate = flow.indexOf("pluginAllowsApp(_state.value.plugin)")
        assertTrue("The update reads the plugin's outcome", gate > flow.indexOf("pluginStep(client)"))
        assertTrue("A plugin that did not update fails the app's update with its own reason", flow.indexOf("fail(UpdateFailure.Plugin)") > gate)
        assertTrue("Nothing is downloaded or installed before that", flow.indexOf("install(retained ?: download(release))") > gate)
    }

    @Test fun theTapNeedsNoGitHubCallOfItsOwnAndOnePluginChangeRunsAtATime() {
        val source = File(app, "src/main/java/dev/hermes/android/updates/UpdateManager.kt").readText().replace("\r\n", "\n")
        val step = source.substring(source.indexOf("private suspend fun pluginStep(")).substringBefore("\n    }\n")
        assertTrue("The shared change is taken before anything is read or posted", step.indexOf("PluginChange.begin()") in 0 until step.indexOf("runPluginUpdate("))
        assertTrue("and given back on every way out", step.contains("finally { PluginChange.finish() }"))
        assertTrue("The commit is the one the check kept with the release", step.contains("_state.value.latest?.takeIf { it.pluginVersion == target }?.commit"))
        assertTrue("GitHub is asked only when none was kept", step.contains("known ?: releases.latestCommit(target).commit"))
        // A 304 may vouch only for a stored release that carries its commit.
        assertTrue(source.contains("stored != null && stored.commit.isNotEmpty()"))
    }

    @Test fun repairAndTheMissingPluginInstallPinTheReleaseTheLastCheckFound() {
        val viewModel = File(app, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText()
        // Both controllers are handed the release the update check kept, commit included, so their taps ask GitHub for nothing.
        assertEquals(2, Regex("""known = \{ app\.updates\.state\.value\.latest \}""").findAll(viewModel).count())
        assertTrue(viewModel.contains("PluginInstallController(viewModelScope"))
        assertTrue(viewModel.contains("PluginRepairController(viewModelScope"))
    }

    @Test fun theReasonIsShortAndTranslated() {
        assertEquals("Beacon on Hermes didn't update, so the app didn't either.", UpdateFailure.Plugin.text)
        val locales = org.json.JSONObject(File(app, "src/main/assets/native-locales.json").readText())
        for (language in locales.keys().asSequence().filter { it != "en" })
            assertTrue(language, locales.getJSONObject(language).optString(UpdateFailure.Plugin.text.lowercase()).isNotBlank())
    }
}
