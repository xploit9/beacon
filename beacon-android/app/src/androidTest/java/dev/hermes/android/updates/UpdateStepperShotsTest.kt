package dev.hermes.android.updates

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.core.Release
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Every state of the update stepper (plugin and app) in the four looks the pass checks: dark, light, Arabic dark with a real right-to-left
 * layout, and light at 1.3x text. Each state is drawn from facts alone (no Hermes, no network) and saved twice, at the top of the page and
 * scrolled to the end. The fixture is one release: the app and the plugin are both 1.1.23.
 *
 * Notifications are silenced while the screen is captured, so no banner covers the hero; the do-not-disturb setting is put back afterwards.
 * The pictures land in files/update-stepper/ and are pulled by the runner of the pass.
 */
class UpdateStepperShotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val mb = 1024L * 1024
    private val now = 1_000_000_000_000L
    private val release = Release(123, "1.1.23", "dev.hermes.android.stable", "https://github.com/xploit9/beacon/releases/download/v1.1.23/Beacon-1.1.23.apk",
        "b".repeat(64), 40 * mb, "a".repeat(64), "1.1.23", "- Updates show every step", "2026-11-01T09:00:00Z")

    private class Look(val tag: String, val dark: Boolean, val language: String, val rtl: Boolean, val fontScale: Float)
    private class Shot(val name: String, val installed: String, val state: AppUpdateUi)

    private val ui = mutableStateOf(AppUpdateUi())
    private val installed = mutableStateOf("1.1.23")
    private var zenBefore = ""

    private fun shell(command: String): String = runCatching {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString().trim() }
    }.getOrDefault("")

    @Before fun silenceNotifications() {
        zenBefore = shell("settings get global zen_mode")
        shell("cmd notification set_dnd on")
        shell("cmd statusbar collapse")
        compose.activity.getSystemService(android.app.NotificationManager::class.java).cancelAll()
    }

    @After fun restoreNotifications() {
        // 0 = off; anything else is put back as it was.
        shell(if (zenBefore == "0" || zenBefore.isBlank() || zenBefore == "null") "cmd notification set_dnd off" else "settings put global zen_mode $zenBefore")
    }

    // ---- the states

    private fun row(step: UpdateStep, state: StepState = StepState.Waiting, note: String = "") = StepRow(step, state, note)
    private val appWaiting = listOf(row(UpdateStep.AppDownload), row(UpdateStep.AppVerify), row(UpdateStep.AppInstall), row(UpdateStep.AppDone))

    /** The whole update from an app that is behind: the plugin goes first, the app's steps wait their turn. */
    private fun plugin(name: String, vararg steps: StepRow) = Shot(name, "1.1.22", AppUpdateUi(phase = UpdatePhase.Downloading, release = release, latest = release, checkedAt = now,
        plugin = PluginRow(PluginStatus.Updating, "1.1.22", "1.1.23"), pluginSteps = steps.toList(), appSteps = appWaiting))

    /** The app is current at 1.1.23 and only the plugin had something to do. */
    private fun pluginOnly(name: String, plugin: PluginRow, vararg steps: StepRow, phase: UpdatePhase = UpdatePhase.Available) =
        Shot(name, "1.1.23", AppUpdateUi(phase = phase, release = null, latest = release, checkedAt = now, plugin = plugin, pluginSteps = steps.toList()))

    private fun app(name: String, phase: UpdatePhase, vararg steps: StepRow, failure: UpdateFailure? = null) =
        Shot(name, "1.1.22", AppUpdateUi(phase = phase, release = release, latest = release, checkedAt = now, downloaded = 17 * mb, failure = failure,
            plugin = PluginRow(PluginStatus.UpToDate, "1.1.23", "1.1.23"), appSteps = steps.toList()))

    private val shots = listOf(
        plugin("plugin-1-download", row(UpdateStep.PluginDownload, StepState.Active), row(UpdateStep.PluginInstall), row(UpdateStep.PluginGateway), row(UpdateStep.PluginDashboard), row(UpdateStep.PluginDone)),
        plugin("plugin-2-install", row(UpdateStep.PluginDownload, StepState.Done), row(UpdateStep.PluginInstall, StepState.Active), row(UpdateStep.PluginGateway), row(UpdateStep.PluginDashboard), row(UpdateStep.PluginDone)),
        plugin("plugin-3-gateway", row(UpdateStep.PluginDownload, StepState.Done), row(UpdateStep.PluginInstall, StepState.Done), row(UpdateStep.PluginGateway, StepState.Active), row(UpdateStep.PluginDashboard), row(UpdateStep.PluginDone)),
        plugin("plugin-4-dashboard", row(UpdateStep.PluginDownload, StepState.Done), row(UpdateStep.PluginInstall, StepState.Done), row(UpdateStep.PluginGateway, StepState.Done),
            row(UpdateStep.PluginDashboard, StepState.Active, StepNote.Reconnecting), row(UpdateStep.PluginDone)),
        pluginOnly("plugin-5-done", PluginRow(PluginStatus.Updated, "1.1.23", "1.1.23"), row(UpdateStep.PluginDownload, StepState.Done), row(UpdateStep.PluginInstall, StepState.Done),
            row(UpdateStep.PluginGateway, StepState.Done), row(UpdateStep.PluginDashboard, StepState.Done), row(UpdateStep.PluginDone, StepState.Done), phase = UpdatePhase.Idle),
        pluginOnly("plugin-6-dashboard-failed", PluginRow(PluginStatus.DashboardFailed, "1.1.23", "1.1.23"), row(UpdateStep.PluginDownload, StepState.Done), row(UpdateStep.PluginInstall, StepState.Done),
            row(UpdateStep.PluginGateway, StepState.Done), row(UpdateStep.PluginDashboard, StepState.Failed, StepNote.DashboardFailed), row(UpdateStep.PluginDone)),
        pluginOnly("plugin-7-dashboard-manual", PluginRow(PluginStatus.DashboardRestart, "1.1.23", "1.1.23"), row(UpdateStep.PluginDashboard, StepState.Failed, StepNote.DashboardManual), row(UpdateStep.PluginDone)),
        pluginOnly("plugin-8-gateway-failed", PluginRow(PluginStatus.RestartFailed, "1.1.23", "1.1.23"), row(UpdateStep.PluginGateway, StepState.Failed, StepNote.GatewayFailed),
            row(UpdateStep.PluginDashboard), row(UpdateStep.PluginDone)),
        pluginOnly("plugin-9-waiting", PluginRow(PluginStatus.Waiting, "1.1.23", "1.1.23"), row(UpdateStep.PluginGateway, StepState.Waiting, StepNote.Busy), row(UpdateStep.PluginDashboard), row(UpdateStep.PluginDone)),
        app("app-1-download", UpdatePhase.Downloading, row(UpdateStep.AppDownload, StepState.Active), row(UpdateStep.AppVerify), row(UpdateStep.AppInstall), row(UpdateStep.AppDone)),
        app("app-2-verify", UpdatePhase.Verifying, row(UpdateStep.AppDownload, StepState.Done), row(UpdateStep.AppVerify, StepState.Active), row(UpdateStep.AppInstall), row(UpdateStep.AppDone)),
        app("app-3-install", UpdatePhase.Installing, row(UpdateStep.AppDownload, StepState.Done), row(UpdateStep.AppVerify, StepState.Done), row(UpdateStep.AppInstall, StepState.Active), row(UpdateStep.AppDone)),
        app("app-4-failed", UpdatePhase.Failed, row(UpdateStep.AppDownload, StepState.Failed, UpdateFailure.Download.text), row(UpdateStep.AppVerify), row(UpdateStep.AppInstall), row(UpdateStep.AppDone),
            failure = UpdateFailure.Download),
    )

    // ---- the looks

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = java.io.File(compose.activity.filesDir, "update-stepper").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun stateDescription(tag: String): String? =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription)

    private fun shoot(look: Look) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, look.fontScale),
                LocalLayoutDirection provides if (look.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                dev.hermes.android.ui.NativeLocalization(look.language) { BeaconTheme(dark = look.dark) { Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.systemBarsPadding().widthIn(max = 411.dp)) {
                        AppUpdateContent(ui.value, installed.value, false, now, onCheck = {}, onUpdate = {}, onRetry = {}, onCancel = {}, onAllow = {}, onReview = {})
                    }
                } } }
            }
        }
        if (look.language != "en") compose.waitUntil(10_000) { (compose.activity.application as BeaconApplication).cachedTranslations != null }
        shots.forEach { shot ->
            installed.value = shot.installed
            ui.value = shot.state
            compose.waitForIdle()
            compose.onNodeWithTag("update-screen").performScrollToIndex(0)
            compose.waitForIdle()
            checkFacts(shot, look)
            capture("${shot.name}-${look.tag}")
            // Where the page does not fit the screen (large text), the end of it too: the last step and what is under it.
            val range = compose.onNodeWithTag("update-screen").fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            val lastRow = listOf("update-step:AppDone", "update-step:PluginDone").lastOrNull { compose.onAllNodes(hasTestTag(it)).fetchSemanticsNodes().isNotEmpty() }
            if (range != null && range.value() < range.maxValue() && lastRow != null) {
                compose.onNodeWithTag("update-screen").performScrollToNode(hasTestTag(lastRow))
                capture("${shot.name}-${look.tag}-end")
            }
        }
    }

    /** What every look must hold for a state; language-free, except the spoken state of the closing row, which is checked in English. */
    private fun checkFacts(shot: Shot, look: Look) {
        val plugin = shot.state.plugin.status
        compose.onNodeWithTag("update-hero").assertIsDisplayed()
        // No Cancel while Hermes carries out the plugin step; Cancel only in the app's download and verify.
        val cancelWanted = if (plugin != PluginStatus.Updating && shot.state.phase in setOf(UpdatePhase.Downloading, UpdatePhase.Verifying)) 1 else 0
        compose.onAllNodesWithTag("update-cancel").assertCountEquals(cancelWanted)
        // One primary action per screen: a hero that has one leaves no Try again row in the stepper.
        val heroPrimary = listOf("update-primary", "update-retry", "update-allow").sumOf { compose.onAllNodesWithTag(it).fetchSemanticsNodes().size }
        val stepperRetry = listOf("update-steps-plugin-retry", "update-steps-app-retry").sumOf { compose.onAllNodesWithTag(it).fetchSemanticsNodes().size }
        check(heroPrimary + stepperRetry <= 1) { "${shot.name} ${look.tag}: $heroPrimary hero and $stepperRetry stepper primary actions" }
        // Progress is shown once: with a checklist on screen the hero has no bar.
        compose.onAllNodesWithTag("update-progress").assertCountEquals(0)
        // A plugin's sentence is shown in full: no ellipsis, at any text size.
        compose.onAllNodes(hasTestTag("update-plugin-line")).fetchSemanticsNodes().forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            check(layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow }) { "${shot.name} ${look.tag}: the plugin sentence is cut" }
        }
        if (look.language == "en") {
            // What the hero says is not said again by a step.
            val said = heroSentence(shot.state, shot.state.phase)
            if (said.isNotBlank()) check(compose.onAllNodes(hasText(said)).fetchSemanticsNodes().size <= 1) { "${shot.name}: '$said' is shown twice" }
            val closing = if (shot.state.pluginSteps.isNotEmpty()) "update-step:PluginDone" else "update-step:AppDone"
            val done = (if (shot.state.pluginSteps.isNotEmpty()) shot.state.pluginSteps else shot.state.appSteps).last().state == StepState.Done
            compose.onNodeWithTag("update-screen").performScrollToNode(hasTestTag(closing))
            val spoken = stateDescription(closing)
            check(if (done) spoken == null else spoken == "Not yet") { "${shot.name}: the closing row says '$spoken'" }
            compose.onNodeWithTag("update-screen").performScrollToIndex(0)
            compose.waitForIdle()
        }
    }

    @Test(timeout = 300_000) fun everyStepperStateInDark() = shoot(Look("dark", true, "en", false, 1f))
    @Test(timeout = 300_000) fun everyStepperStateInLight() = shoot(Look("light", false, "en", false, 1f))
    @Test(timeout = 300_000) fun everyStepperStateInArabicDarkRightToLeft() = shoot(Look("ar-dark", true, "ar", true, 1f))
    @Test(timeout = 300_000) fun everyStepperStateAtLargeTextInLight() = shoot(Look("large-light", false, "en", false, 1.3f))
}
