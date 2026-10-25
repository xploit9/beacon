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
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
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
import org.junit.Rule
import org.junit.Test

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

    private fun show(state: AppUpdateUi, callBusy: Boolean = false, fontScale: Float = 1f) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            dev.hermes.android.ui.NativeLocalization("en") { BeaconTheme(dark = true) { Surface { Box(Modifier.widthIn(max = 411.dp)) {
                AppUpdateContent(state, "1.1.2", callBusy, now, onCheck = { checks++ }, onUpdate = { updates++ }, onRetry = { retries++ },
                    onCancel = { cancels++ }, onAllow = { allows++ }, onReview = { reviews++ })
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
    private fun available(plugin: PluginRow = PluginRow(PluginStatus.Available, "1.1.1", "1.1.3")) =
        AppUpdateUi(phase = UpdatePhase.Available, release = release, latest = release, checkedAt = now - 5 * 60_000, plugin = plugin)

    @Test fun upToDateShowsTheInstalledVersionAndWhenItWasChecked() {
        show(AppUpdateUi(checkedAt = now - 5 * 60_000))
        compose.onNodeWithTag("update-state:Idle").assertIsDisplayed()
        compose.onNodeWithTag("update-version").assertTextEquals("Beacon 1.1.2")
        compose.onNodeWithText("You're up to date").assertIsDisplayed()
        compose.onNodeWithTag("update-checked").assertTextEquals("Checked 5 min ago")
        compose.onAllNodesWithTag("update-primary").assertCountEquals(0)
        capture("up-to-date")
        compose.onNodeWithTag("update-check").assertIsEnabled().performClick()
        assertEquals(1, checks)
    }

    @Test fun checkingKeepsTheSameCardSoNothingJumps() {
        show(AppUpdateUi(phase = UpdatePhase.Checking, checkedAt = now - 5 * 60_000))
        compose.onNodeWithTag("update-state:Idle").assertIsDisplayed()
        compose.onNodeWithTag("update-version").assertTextEquals("Beacon 1.1.2")
        compose.onNodeWithTag("update-checking").assertIsDisplayed()
        compose.onNodeWithText("Checking for updates…").assertIsDisplayed()
        compose.onNodeWithTag("update-check").assertIsNotEnabled()
    }

    @Test fun anAvailableUpdateListsTheAppAndThePluginUnderOneUpdateButton() {
        show(available())
        compose.onNodeWithTag("update-state:Available").assertIsDisplayed()
        compose.onNodeWithTag("update-version").assertTextEquals("Beacon 1.1.3")
        compose.onNodeWithTag("update-badge-new").assertIsDisplayed()
        compose.onNodeWithTag("update-meta").assertIsDisplayed()
        compose.onNodeWithText("1.1.2 → 1.1.3").assertIsDisplayed()
        compose.onNodeWithText("Hermes plugin").assertIsDisplayed()
        compose.onNodeWithText("1.1.1 → 1.1.3").assertIsDisplayed()
        compose.onAllNodesWithTag("update-row-plugin").assertCountEquals(1)
        capture("available")
        compose.onNodeWithTag("update-primary").assertIsEnabled().performScrollTo().performClick()
        assertEquals(1, updates)
    }

    @Test fun whatsNewIsTrimmedWithShowMoreAndKeepsItsLinks() {
        show(available())
        compose.onNodeWithTag("update-notes").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("update-notes-more").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("update-notes-more").performScrollTo().assertTextEquals("Show more").performClick()
        compose.onNodeWithTag("update-notes-more").assertTextEquals("Show less")
        compose.onNodeWithText("• Links open in your browser", substring = true).assertExists()
    }

    @Test fun aPluginOnlyActionNeverBadgesTheCurrentApp() {
        show(AppUpdateUi(phase = UpdatePhase.Available, release = null, latest = release, plugin = PluginRow(PluginStatus.Failed, "1.1.1", "1.1.3")))
        compose.onNodeWithTag("update-state:Available").assertIsDisplayed()
        compose.onNodeWithTag("update-version").assertTextEquals("Beacon 1.1.2")
        compose.onAllNodesWithTag("update-badge-new").assertCountEquals(0)
        compose.onNodeWithTag("update-row-plugin").assertIsDisplayed()
    }

    @Test fun anAppOnlyReleaseHidesThePluginRow() {
        show(available(PluginRow()))
        compose.onAllNodesWithTag("update-row-plugin").assertCountEquals(0)
    }

    @Test fun aPluginThatNeedsReviewNeverBlocksTheAppUpdate() {
        show(available(PluginRow(PluginStatus.NeedsReview, "1.1.1", "1.1.3", listOf("Reads files"))))
        compose.onNodeWithTag("update-row-plugin-action").performScrollTo().assertTextEquals("Review changes").performClick()
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
        compose.onNodeWithTag("update-cancel").assertIsEnabled().performClick()
        assertEquals(1, cancels)
    }

    @Test fun thePluginStepComesFirstAndSaysSo() {
        show(AppUpdateUi(phase = UpdatePhase.Downloading, release = release, latest = release, plugin = PluginRow(PluginStatus.Updating, "1.1.1", "1.1.3")))
        compose.onNodeWithText("Updating the Hermes plugin…").assertIsDisplayed()
        compose.onNodeWithTag("update-cancel").assertIsEnabled()
    }

    @Test fun installingShowsProgressWhileAndroidAsks() {
        show(AppUpdateUi(phase = UpdatePhase.Installing, release = release, latest = release))
        compose.onNodeWithTag("update-state:Installing").assertIsDisplayed()
        compose.onNodeWithText("Installing…").assertIsDisplayed()
        compose.onNodeWithTag("update-progress").assertIsDisplayed()
    }

    @Test fun androidPermissionHasOneShortLineAndAnAllowButton() {
        show(AppUpdateUi(phase = UpdatePhase.NeedsPermission, release = release, latest = release))
        compose.onNodeWithText("Allow Beacon to install updates.").assertIsDisplayed()
        compose.onNodeWithTag("update-allow").performClick()
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
        compose.onNodeWithText("Preview builds are installed manually.").assertIsDisplayed()
        compose.onAllNodesWithTag("update-check").assertCountEquals(0)
        compose.onAllNodesWithTag("update-primary").assertCountEquals(0)
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
        compose.onNodeWithTag("update-version").assertIsDisplayed()
    }
}
