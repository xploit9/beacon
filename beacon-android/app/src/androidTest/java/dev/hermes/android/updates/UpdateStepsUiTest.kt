package dev.hermes.android.updates

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import dev.hermes.android.BeaconTheme
import dev.hermes.android.core.Release
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The update checklist on screen. A fake Hermes holds each step open until the test lets it go, so what the screen shows at every point
 * is what the update has really reached; no network, no Hermes, no sign-in.
 */
class UpdateStepsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val mb = 1024L * 1024
    private val release = Release(123, "1.1.23", "dev.hermes.android.stable", "https://github.com/xploit9/beacon/releases/download/v1.1.23/Beacon-1.1.23.apk",
        "b".repeat(64), 40 * mb, "a".repeat(64), "1.1.23", "- Updates show every step", "2026-11-01T09:00:00Z")
    private val now = 1_000_000_000_000L
    private val ui = mutableStateOf(AppUpdateUi(phase = UpdatePhase.Available, release = null, latest = release, checkedAt = now - 60_000))
    private var tries = 0

    private fun show(dark: Boolean, installed: String = "1.1.23") = compose.setContent {
        dev.hermes.android.ui.NativeLocalization("en") { BeaconTheme(dark = dark) { Surface { Box(Modifier.widthIn(max = 411.dp)) {
            AppUpdateContent(ui.value, installed, false, now, onCheck = {}, onUpdate = { tries++ }, onRetry = {}, onCancel = {}, onAllow = {}, onReview = {})
        } } } }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = java.io.File(compose.activity.filesDir, "update-steps").apply { mkdirs() }
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun reveal(tag: String) = compose.onNodeWithTag("update-screen").performScrollToNode(hasTestTag(tag))
    private fun row(step: UpdateStep) = compose.onNodeWithTag("update-step:${step.name}")
    /** A step says a word on screen (its label, note or figure) or as its state ("Working", "Done"), which the glyph draws and TalkBack reads. */
    private fun says(word: String) = hasText(word, substring = true) or SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, word)
    private fun SemanticsNodeInteraction.shows(vararg words: String) = apply { words.forEach { assert(says(it)) } }
    private fun waitForRow(step: UpdateStep, word: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("update-step:${step.name}") and says(word)).fetchSemanticsNodes().isNotEmpty() }

    /** A Hermes that answers each request only when the test says so. */
    private class Gated(val versions: List<PluginVersions>) : PluginEndpoint {
        val update = CompletableDeferred<Unit>()
        val gateway = CompletableDeferred<Unit>()
        val dashboard = CompletableDeferred<Unit>()
        val reconnecting = CompletableDeferred<Unit>()
        private var read = 0
        override suspend fun version(): PluginVersions = versions[minOf(read++, versions.lastIndex)]
        override suspend fun update(acceptCapabilities: Boolean): JSONObject { update.await(); return JSONObject().put("ok", true) }
        override suspend fun restart(last: RestartMemo, remember: (RestartMemo) -> Unit): RestartResult { gateway.await(); return RestartResult.Completed }
        override suspend fun restartDashboard(onReconnecting: (Boolean) -> Unit): DashboardRestartResult {
            onReconnecting(true)
            reconnecting.await()
            onReconnecting(false)
            dashboard.await()
            return DashboardRestartResult.Completed
        }
    }

    private fun walkThrough(dark: Boolean) = runBlocking {
        show(dark)
        val fake = Gated(listOf(
            PluginVersions("1.1.22", "1.1.22", "1.1.22"), PluginVersions("1.1.22", "1.1.23", "1.1.22"),
            PluginVersions("1.1.22", "1.1.23", "1.1.23"), PluginVersions("1.1.23", "1.1.23", "1.1.23")))
        val tracker = StepTracker { rows -> ui.value = ui.value.copy(pluginSteps = rows) }
        val result = CoroutineScope(Dispatchers.Default).async { runPluginUpdate(fake, "1.1.23", accept = false, steps = tracker, pause = {}) }
        val name = if (dark) "dark" else "light"

        // Hermes is fetching the plugin: the download is running and nothing after it has started.
        waitForRow(UpdateStep.PluginDownload, "Working")
        reveal("update-step:PluginDashboard")
        row(UpdateStep.PluginDownload).shows("Downloading").shows("Working")
        row(UpdateStep.PluginInstall).shows("Waiting")
        row(UpdateStep.PluginGateway).shows("Waiting")
        row(UpdateStep.PluginDashboard).shows("Waiting")
        capture("plugin-download-$name")

        fake.update.complete(Unit)
        waitForRow(UpdateStep.PluginGateway, "Working")
        row(UpdateStep.PluginDownload).shows("Done")
        row(UpdateStep.PluginInstall).shows("Done")
        row(UpdateStep.PluginDashboard).shows("Waiting")
        capture("plugin-gateway-$name")

        fake.gateway.complete(Unit)
        waitForRow(UpdateStep.PluginDashboard, "Reconnecting")
        row(UpdateStep.PluginGateway).shows("Restarting Hermes gateway").shows("Done")
        row(UpdateStep.PluginDashboard).shows("Restarting Hermes dashboard").shows("Working")
        // The closing row waits as "Done, Not yet", not "Done, Waiting".
        row(UpdateStep.PluginDone).shows("Not yet")
        capture("plugin-dashboard-$name")

        fake.reconnecting.complete(Unit)
        fake.dashboard.complete(Unit)
        val finished = result.await()
        compose.waitForIdle()
        assertEquals(PluginStatus.Updated, finished.status)
        ui.value = ui.value.copy(plugin = finished)  // the manager publishes the row beside the steps
        waitForRow(UpdateStep.PluginDone, "1.1.23")
        row(UpdateStep.PluginDashboard).shows("Done")
        capture("plugin-done-$name")
    }

    @Test(timeout = 90_000) fun aPluginUpdateShowsEachStepAsItReallyHappensInDark() = walkThrough(dark = true)
    @Test(timeout = 90_000) fun aPluginUpdateShowsEachStepAsItReallyHappensInLight() = walkThrough(dark = false)

    /** Only the dashboard restart failed: the hero says so and carries the one action, so the stepper has no Try again row of its own. */
    private fun failedStates(dark: Boolean) {
        ui.value = ui.value.copy(latest = release, phase = UpdatePhase.Available, plugin = PluginRow(PluginStatus.DashboardFailed, "1.1.23", "1.1.23"), pluginSteps = listOf(
            StepRow(UpdateStep.PluginDownload, StepState.Done), StepRow(UpdateStep.PluginInstall, StepState.Done), StepRow(UpdateStep.PluginGateway, StepState.Done),
            StepRow(UpdateStep.PluginDashboard, StepState.Failed, StepNote.DashboardFailed), StepRow(UpdateStep.PluginDone)))
        show(dark)
        compose.onNodeWithText("The plugin is updated. The Hermes dashboard didn't restart.").assertIsDisplayed()
        compose.onNodeWithTag("update-primary").assertTextEquals("Restart dashboard")
        compose.onAllNodesWithTag("update-steps-plugin-retry").assertCountEquals(0)
        reveal("update-step:PluginDone")
        row(UpdateStep.PluginDashboard).shows("Restarting Hermes dashboard").shows("Didn't restart").shows("Failed")
        row(UpdateStep.PluginDone).shows("Done").shows("Not yet")
        capture("plugin-failed-${if (dark) "dark" else "light"}")
        compose.onNodeWithTag("update-screen").performScrollToNode(hasTestTag("update-primary"))
        compose.onNodeWithTag("update-primary").performClick()
        assertEquals(1, tries)
    }

    @Test fun aDashboardOnlyFailureHasOneActionInTheHeroInDark() = failedStates(dark = true)
    @Test fun aDashboardOnlyFailureHasOneActionInTheHeroInLight() = failedStates(dark = false)

    @Test fun aGatewayFailureAlsoLeavesTheHeroTheOnlyTryAgain() {
        ui.value = ui.value.copy(latest = release, phase = UpdatePhase.Available, plugin = PluginRow(PluginStatus.RestartFailed, "1.1.23", "1.1.23"), pluginSteps = listOf(
            StepRow(UpdateStep.PluginGateway, StepState.Failed, StepNote.GatewayFailed), StepRow(UpdateStep.PluginDashboard), StepRow(UpdateStep.PluginDone)))
        show(dark = true)
        compose.onNodeWithTag("update-primary").assertTextEquals("Try again")
        compose.onAllNodesWithTag("update-steps-plugin-retry").assertCountEquals(0)
    }

    @Test fun anOlderPluginLeavesTheDashboardToTheOwnerWithAHowAndTryAgainChecksIt() {
        ui.value = ui.value.copy(latest = release, phase = UpdatePhase.Available, plugin = PluginRow(PluginStatus.DashboardRestart, "1.1.23", "1.1.23"), pluginSteps = listOf(
            StepRow(UpdateStep.PluginDashboard, StepState.Failed, StepNote.DashboardManual), StepRow(UpdateStep.PluginDone)))
        show(dark = true)
        // The hero has no primary action here, so the stepper keeps the one Try again that checks again after the owner restarted it.
        compose.onAllNodesWithTag("update-primary").assertCountEquals(0)
        compose.onNodeWithTag("update-restart-how").assertTextEquals("How").assertIsDisplayed()
        compose.onNodeWithTag("update-plugin-line").assertTextEquals("Restart the Hermes dashboard on your computer.")
        reveal("update-steps-plugin-retry")
        row(UpdateStep.PluginDashboard).shows("Restarting Hermes dashboard").shows("Failed")
        // The sentence is said once, by the hero, not again on the step.
        compose.onAllNodesWithText("Restart the Hermes dashboard on your computer.").assertCountEquals(1)
        compose.onNodeWithTag("update-steps-plugin-retry").performScrollTo().performClick()
        assertEquals(1, tries)
    }

    @Test fun thePluginStepHasNoCancelAndNoSecondBar() {
        ui.value = AppUpdateUi(phase = UpdatePhase.Downloading, release = release, latest = release, checkedAt = now,
            plugin = PluginRow(PluginStatus.Updating, "1.1.22", "1.1.23"),
            pluginSteps = listOf(StepRow(UpdateStep.PluginDownload, StepState.Active), StepRow(UpdateStep.PluginInstall), StepRow(UpdateStep.PluginGateway),
                StepRow(UpdateStep.PluginDashboard), StepRow(UpdateStep.PluginDone)),
            appSteps = listOf(StepRow(UpdateStep.AppDownload), StepRow(UpdateStep.AppVerify), StepRow(UpdateStep.AppInstall), StepRow(UpdateStep.AppDone)))
        show(dark = true, installed = "1.1.22")
        compose.onNodeWithText("Updating the Beacon plugin…").assertIsDisplayed()
        compose.onAllNodesWithTag("update-cancel").assertCountEquals(0)
        compose.onAllNodesWithTag("update-progress").assertCountEquals(0)
        reveal("update-step:AppDone")
        compose.onNodeWithText("Beacon plugin").assertExists()
    }

    private fun appStates(dark: Boolean) {
        val name = if (dark) "dark" else "light"
        ui.value = AppUpdateUi(phase = UpdatePhase.Downloading, release = release, latest = release, checkedAt = now, downloaded = 17 * mb, appSteps = listOf(
            StepRow(UpdateStep.AppDownload, StepState.Active), StepRow(UpdateStep.AppVerify), StepRow(UpdateStep.AppInstall), StepRow(UpdateStep.AppDone)))
        show(dark, installed = "1.1.22")
        reveal("update-step:AppDone")
        row(UpdateStep.AppDownload).shows("Downloading").shows("42%")
        row(UpdateStep.AppVerify).shows("Waiting")
        row(UpdateStep.AppDone).shows("Not yet")
        // Progress shows once: the percent is on the step, the hero keeps the size and has no bar.
        compose.onNodeWithTag("update-screen").performScrollToNode(hasTestTag("update-progress-text"))
        compose.onNodeWithTag("update-progress-text").assertTextEquals("17 of 40 MB")
        compose.onAllNodesWithTag("update-progress").assertCountEquals(0)
        compose.onNodeWithTag("update-cancel").assertIsDisplayed()
        capture("app-download-$name")

        ui.value = ui.value.copy(phase = UpdatePhase.Verifying, appSteps = listOf(
            StepRow(UpdateStep.AppDownload, StepState.Done), StepRow(UpdateStep.AppVerify, StepState.Active), StepRow(UpdateStep.AppInstall), StepRow(UpdateStep.AppDone)))
        compose.waitForIdle()
        row(UpdateStep.AppVerify).shows("Verifying").shows("Working")
        row(UpdateStep.AppDownload).shows("Done")
        compose.onNodeWithTag("update-cancel").assertIsDisplayed()
        capture("app-verify-$name")

        ui.value = ui.value.copy(phase = UpdatePhase.Installing, appSteps = listOf(
            StepRow(UpdateStep.AppDownload, StepState.Done), StepRow(UpdateStep.AppVerify, StepState.Done), StepRow(UpdateStep.AppInstall, StepState.Active), StepRow(UpdateStep.AppDone)))
        compose.waitForIdle()
        row(UpdateStep.AppInstall).shows("Installing").shows("Working")
        // Android's installer has the file: nothing left to cancel.
        compose.onAllNodesWithTag("update-cancel").assertCountEquals(0)
        capture("app-install-$name")
        // Nothing of the old component rows is shown twice while a checklist stands in for them.
        compose.onAllNodesWithTag("update-row-app").assertCountEquals(0)
    }

    @Test fun theAppUpdateShowsDownloadPercentVerifyingAndInstallingInDark() = appStates(dark = true)
    @Test fun theAppUpdateShowsDownloadPercentVerifyingAndInstallingInLight() = appStates(dark = false)

    @Test fun withNoChecklistTheComponentRowsAreAsBefore() {
        ui.value = ui.value.copy(release = release, plugin = PluginRow(PluginStatus.Available, "1.1.22", "1.1.23"), phase = UpdatePhase.Available)
        show(dark = true, installed = "1.1.22")
        reveal("update-row-plugin")
        compose.onNodeWithTag("update-row-app").assertIsDisplayed()
        compose.onNodeWithTag("update-row-plugin").assertIsDisplayed()
        compose.onAllNodesWithTag("update-steps-plugin").assertCountEquals(0)
    }
}
