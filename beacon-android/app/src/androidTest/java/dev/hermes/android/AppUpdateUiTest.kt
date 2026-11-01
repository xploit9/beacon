package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hermes.android.core.Release
import dev.hermes.android.updates.AppUpdateContent
import dev.hermes.android.updates.AppUpdateUi
import dev.hermes.android.updates.PluginRow
import dev.hermes.android.updates.PluginStatus
import dev.hermes.android.updates.UpdateFailure
import dev.hermes.android.updates.UpdatePhase
import dev.hermes.android.updates.UpdatedNotice
import dev.hermes.android.updates.UpdatedSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The node's touch target, which can be larger than what it draws, is at least [min] tall. */
private fun SemanticsNodeInteraction.assertTouchHeightIsAtLeast(min: Dp): SemanticsNodeInteraction = apply {
    val node = fetchSemanticsNode()
    val height = with(node.layoutInfo.density) { node.touchBoundsInRoot.height.toDp() }
    assertTrue("touch target $height is under $min", height >= min)
}

/** The update screen in every state, from a fake state alone: no network, no Hermes, no sign-in. */
class AppUpdateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val mb = 1024L * 1024
    private val notes = "## What's new\n- Faster updates\n- Calmer update screen\n- One\n- Two\n- Three\n- Four\n- Five\n- Links open in [your browser](https://github.com/xploit9/beacon/releases)"
    private val release = Release(113, "1.1.3", "dev.hermes.android.stable", "https://github.com/xploit9/beacon/releases/download/v1.1.3/Beacon-1.1.3.apk",
        "b".repeat(64), 31 * mb, "a".repeat(64), "1.1.3", notes, "2026-10-24T09:00:00Z")
    private val now = 1_000_000_000_000L
    private var checks = 0
    private var updates = 0
    private var retries = 0
    private var cancels = 0
    private var allows = 0
    private var reviews = 0
    private var alerts = 0

    private fun show(state: AppUpdateUi, callBusy: Boolean = false, fontScale: Float = 1f, alertsBlocked: Boolean = false, dark: Boolean = true) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            dev.hermes.android.ui.NativeLocalization("en") { BeaconTheme(dark = dark) { Surface { Box(Modifier.widthIn(max = 411.dp)) {
                AppUpdateContent(state, "1.1.2", callBusy, now, onCheck = { checks++ }, onUpdate = { updates++ }, onRetry = { retries++ },
                    onCancel = { cancels++ }, onAllow = { allows++ }, onReview = { reviews++ }, alertsBlocked = alertsBlocked, onAllowAlerts = { alerts++ })
            } } } }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = java.io.File(compose.activity.filesDir, "native-experience/updates").apply { mkdirs() }
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    /** The page is a lazy list: bring a row into the composition before asserting on it. */
    private fun reveal(tag: String) = compose.onNodeWithTag("update-screen").performScrollToNode(hasTestTag(tag))
    private fun available(plugin: PluginRow = PluginRow(PluginStatus.Available, "1.1.1", "1.1.3")) =
        AppUpdateUi(phase = UpdatePhase.Available, release = release, latest = release, checkedAt = now - 5 * 60_000, plugin = plugin)
    private fun pluginOnly(plugin: PluginRow) = AppUpdateUi(phase = UpdatePhase.Available, release = null, latest = release, plugin = plugin)

    @Test fun upToDateShowsTheInstalledVersionAndWhenItWasChecked() {
        show(AppUpdateUi(checkedAt = now - 5 * 60_000))
        compose.onNodeWithTag("update-state:Idle").assertIsDisplayed()
        compose.onNodeWithTag("update-hero-title").assertTextEquals("Beacon 1.1.2")
        compose.onNodeWithText("You're up to date").assertIsDisplayed()
        compose.onNodeWithTag("update-checked").assertTextEquals("Checked 5 min ago")
        compose.onAllNodesWithTag("update-primary").assertCountEquals(0)
        capture("up-to-date")
        compose.onNodeWithTag("update-check").assertIsEnabled().assertTouchHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, checks)
    }

    @Test fun checkingKeepsTheSameHeroSoNothingJumps() {
        show(AppUpdateUi(phase = UpdatePhase.Checking, checkedAt = now - 5 * 60_000))
        compose.onNodeWithTag("update-state:Idle").assertIsDisplayed()
        compose.onNodeWithTag("update-hero-title").assertTextEquals("Beacon 1.1.2")
        compose.onNodeWithTag("update-checking").assertIsDisplayed()
        compose.onNodeWithText("Checking for updates…").assertIsDisplayed()
        compose.onNodeWithTag("update-check").assertIsNotEnabled()
    }

    @Test fun anAvailableUpdateListsTheAppAndThePluginUnderOneUpdateButton() {
        show(available())
        compose.onNodeWithTag("update-state:Available").assertIsDisplayed()
        compose.onNodeWithTag("update-hero-title").assertTextEquals("Beacon 1.1.3")
        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNodeWithTag("update-badge-new").assertIsDisplayed()
        compose.onNodeWithTag("update-meta").assertIsDisplayed()
        reveal("update-row-plugin")
        compose.onNodeWithTag("update-row-app").assertTextContains("1.1.2 → 1.1.3").assertTextContains("Ready to update")
        compose.onNodeWithText("Beacon plugin").assertIsDisplayed()
        compose.onNodeWithText("1.1.1 → 1.1.3").assertIsDisplayed()
        compose.onAllNodesWithTag("update-row-plugin").assertCountEquals(1)
        capture("available")
        compose.onNodeWithTag("update-primary").assertIsEnabled().assertTouchHeightIsAtLeast(48.dp).performScrollTo().performClick()
        assertEquals(1, updates)
    }

    @Test fun whatsNewIsTrimmedWithShowMoreAndKeepsItsLinks() {
        show(available())
        reveal("update-notes")
        compose.onNodeWithTag("update-notes").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("update-notes-more").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("update-notes-more").performScrollTo().assertTextEquals("Show more").performClick()
        compose.onNodeWithTag("update-notes-more").assertTextEquals("Show less")
        compose.onNodeWithText("• Links open in your browser", substring = true).assertExists()
    }

    @Test fun aPluginOnlyActionNeverBadgesTheCurrentApp() {
        show(pluginOnly(PluginRow(PluginStatus.Failed, "1.1.1", "1.1.3")))
        compose.onNodeWithTag("update-state:Available").assertIsDisplayed()
        compose.onNodeWithTag("update-hero-title").assertTextEquals("Beacon 1.1.2")
        compose.onAllNodesWithTag("update-badge-new").assertCountEquals(0)
        reveal("update-row-plugin")
        compose.onNodeWithTag("update-row-plugin").assertIsDisplayed()
    }

    @Test fun anAppOnlyReleaseHidesThePluginRow() {
        show(available(PluginRow()))
        reveal("update-row-app")
        compose.onNodeWithTag("update-row-app").assertExists()
        compose.onAllNodesWithTag("update-row-plugin").assertCountEquals(0)
    }

    @Test fun theUpToDatePageStillListsBothComponentsTogether() {
        show(AppUpdateUi(checkedAt = now - 60_000, latest = release.copy(version = 112, name = "1.1.2", pluginVersion = "1.1.2"), plugin = PluginRow(PluginStatus.UpToDate, "1.1.2", "1.1.2")))
        reveal("update-row-plugin")
        compose.onNodeWithTag("update-row-app").assertTextContains("Up to date")
        compose.onNodeWithTag("update-row-plugin").assertTextContains("Up to date")
    }

    @Test fun aPendingPluginRestartCanBeFinishedFromTheApp() {
        show(pluginOnly(PluginRow(PluginStatus.Restart, "1.1.3", "1.1.3")))
        compose.onNodeWithTag("update-primary").assertTextEquals("Restart Hermes").assertIsEnabled().performClick()
        assertEquals(1, updates)
    }

    @Test fun anActiveCallAlsoDisablesThePluginRestart() {
        show(pluginOnly(PluginRow(PluginStatus.Restart, "1.1.3", "1.1.3")), callBusy = true)
        compose.onNodeWithTag("update-primary").assertTextEquals("Restart Hermes").assertIsNotEnabled()
        compose.onNodeWithTag("update-call-busy").assertIsDisplayed()
    }

    @Test fun aRestartHermesRefusedWhileBusyWaitsWithoutAButton() {
        show(pluginOnly(PluginRow(PluginStatus.Waiting, "1.1.3", "1.1.3")))
        compose.onAllNodesWithText("Waiting").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Hermes is busy — restarts when it's idle").assertIsDisplayed()
        compose.onAllNodesWithTag("update-primary").assertCountEquals(0)
        reveal("update-row-plugin")
        compose.onNodeWithTag("update-row-plugin").assertTextContains("Waiting")
    }

    @Test fun aRestartThatFinishedLeavesTheDashboardRestartToTheOwner() {
        show(pluginOnly(PluginRow(PluginStatus.DashboardRestart, "1.1.3", "1.1.3")))
        compose.onAllNodesWithText("Restart needed").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Restart the Hermes dashboard on your computer.").assertIsDisplayed()
        compose.onNodeWithTag("update-restart-how").assertTextEquals("How")
        compose.onAllNodesWithTag("update-primary").assertCountEquals(0)
    }

    @Test fun aFailedRestartOffersTryAgain() {
        show(pluginOnly(PluginRow(PluginStatus.RestartFailed, "1.1.3", "1.1.3")))
        compose.onNodeWithText("Hermes gateway didn't restart").assertIsDisplayed()
        compose.onNodeWithTag("update-primary").assertTextEquals("Try again").performClick()
        assertEquals(1, updates)
    }

    @Test fun aPluginNewerThanTheAppIsAttentionNotUpToDate() {
        show(AppUpdateUi(checkedAt = now - 60_000, latest = release, plugin = PluginRow(PluginStatus.Newer, "1.2.0", "1.1.3"), phase = UpdatePhase.Available))
        compose.onNodeWithText("The Beacon plugin is newer than this app. Update the app.").assertIsDisplayed()
        compose.onAllNodesWithText("Newer than app").onFirst().assertIsDisplayed()
        compose.onAllNodesWithTag("update-primary").assertCountEquals(0)
        compose.onAllNodesWithText("You're up to date").assertCountEquals(0)
    }

    @Test fun withAnAppUpdateTheRestartSentenceSitsOnThePluginRow() {
        show(available(PluginRow(PluginStatus.Waiting, "1.1.3", "1.1.3")))
        reveal("update-row-plugin")
        compose.onNodeWithTag("update-row-plugin").assertTextContains("Hermes is busy — restarts when it's idle", substring = true).assertTextContains("Waiting")
        compose.onNodeWithTag("update-primary").assertTextEquals("Update").assertIsEnabled()
    }

    @Test fun aPluginThatNeedsReviewNeverBlocksTheAppUpdate() {
        show(available(PluginRow(PluginStatus.NeedsReview, "1.1.1", "1.1.3", listOf("Reads files"))))
        reveal("update-row-plugin")
        compose.onNodeWithTag("update-row-plugin").assertTextContains("Review changes").assertTouchHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, reviews)
        compose.onNodeWithTag("update-primary").assertIsEnabled()
    }

    @Test fun aCallDisablesUpdateAndSaysWhy() {
        show(available(), callBusy = true)
        compose.onNodeWithTag("update-primary").assertIsNotEnabled()
        compose.onNodeWithTag("update-call-busy").assertTextEquals("End your call before updating Beacon.")
    }

    @Test fun downloadingShowsDeterminateProgressAndCancel() {
        show(AppUpdateUi(phase = UpdatePhase.Downloading, release = release, latest = release, downloaded = 13 * mb))
        compose.onNodeWithTag("update-state:Downloading").assertIsDisplayed()
        compose.onNodeWithTag("update-progress").assertIsDisplayed()
        compose.onNodeWithTag("update-progress-text").assertTextEquals("41% · 13 of 31 MB")
        capture("downloading")
        compose.onNodeWithTag("update-cancel").assertIsEnabled().assertTouchHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, cancels)
    }

    @Test fun thePluginStepComesFirstAndSaysSo() {
        show(AppUpdateUi(phase = UpdatePhase.Downloading, release = release, latest = release, plugin = PluginRow(PluginStatus.Updating, "1.1.1", "1.1.3")))
        compose.onNodeWithText("Updating the Beacon plugin…").assertIsDisplayed()
        // Hermes carries on installing whatever the phone does, so the plugin step offers no Cancel.
        compose.onAllNodesWithTag("update-cancel").assertCountEquals(0)
    }

    @Test fun installingShowsProgressWhileAndroidAsks() {
        show(AppUpdateUi(phase = UpdatePhase.Installing, release = release, latest = release))
        compose.onNodeWithTag("update-state:Installing").assertIsDisplayed()
        compose.onNodeWithText("Installing…").assertIsDisplayed()
        compose.onNodeWithTag("update-progress").assertIsDisplayed()
    }

    @Test fun androidPermissionHasOneShortWordAndAnAllowButton() {
        show(AppUpdateUi(phase = UpdatePhase.NeedsPermission, release = release, latest = release))
        compose.onNodeWithText("Allow installs").assertIsDisplayed()
        compose.onNodeWithTag("update-allow").assertTouchHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, allows)
    }

    private fun failed(failure: UpdateFailure, reason: String) {
        show(AppUpdateUi(phase = UpdatePhase.Failed, release = release, latest = release, failure = failure))
        compose.onNodeWithTag("update-state:Failed").assertIsDisplayed()
        compose.onNodeWithTag("update-failure").assertTextEquals(reason)
        capture("failed-" + failure.name.lowercase())
        compose.onNodeWithTag("update-retry").assertTextEquals("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test fun aDownloadFailureHasOneShortReasonAndTryAgain() = failed(UpdateFailure.Download, "Couldn't download the update. Check your connection.")

    @Test fun aFailedVerificationHasOneShortReasonAndTryAgain() = failed(UpdateFailure.Verification, "The update didn't pass verification.")

    @Test fun previewBuildsAreInstalledByHand() {
        show(AppUpdateUi(supported = false))
        compose.onNodeWithText("Preview build").assertIsDisplayed()
        compose.onNodeWithText("Installed manually").assertIsDisplayed()
        compose.onAllNodesWithTag("update-check").assertCountEquals(0)
        compose.onAllNodesWithTag("update-primary").assertCountEquals(0)
        compose.onAllNodesWithTag("update-components").assertCountEquals(0)
    }

    @Test fun blockedUpdateNotificationsOfferTheAndroidPageOnlyWhileBlocked() {
        show(AppUpdateUi(checkedAt = now - 60_000), alertsBlocked = true)
        reveal("update-allow-notifications")
        compose.onNodeWithTag("update-allow-notifications").assertTextContains("Allow update notifications").assertTouchHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, alerts)
    }

    @Test fun allowedUpdateNotificationsShowNoRow() {
        show(AppUpdateUi(checkedAt = now - 60_000), alertsBlocked = false)
        compose.onAllNodesWithTag("update-allow-notifications").assertCountEquals(0)
    }

    @Test fun theHeroSitsFirstWithItsStateTile() {
        show(available())
        compose.onNodeWithTag("update-hero").assertIsDisplayed()
        compose.onNodeWithTag("update-hero-tile").assertIsDisplayed()
    }

    @Test fun lightThemeKeepsTheHeroAndRowsReadable() {
        show(available(), dark = false)
        compose.onNodeWithTag("update-hero").assertIsDisplayed()
        compose.onNodeWithTag("update-primary").assertIsEnabled()
        capture("available-light")
    }

    @Test fun theFirstLaunchAfterAnUpdateShowsOneUpdatedSheet() {
        var done = 0
        compose.setContent { dev.hermes.android.ui.NativeLocalization("en") { BeaconTheme(dark = true) { UpdatedSheet(UpdatedNotice("1.1.3", notes)) { done++ } } } }
        compose.onNodeWithTag("update-updated-sheet").assertIsDisplayed()
        compose.onNodeWithTag("update-updated-title").assertTextEquals("Updated to 1.1.3")
        compose.onNodeWithText("What's new").assertIsDisplayed()
        capture("updated-sheet")
        compose.onNodeWithTag("update-updated-done").performScrollTo().performClick()
        assertEquals(1, done)
    }

    @Test fun largeTextKeepsTheMainActionReachableAndTouchable() {
        show(available(), fontScale = 1.3f)
        compose.onNodeWithTag("update-primary").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("update-hero-title").assertIsDisplayed()
    }
}
