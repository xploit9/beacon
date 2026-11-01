package dev.hermes.android.uiux.updates

import dev.hermes.android.core.Release
import dev.hermes.android.updates.AppUpdateUi
import dev.hermes.android.updates.DashboardRestartResult
import dev.hermes.android.updates.HeroActionStrings
import dev.hermes.android.updates.HeroActions
import dev.hermes.android.updates.HeroPrimary
import dev.hermes.android.updates.HeroSecondary
import dev.hermes.android.updates.PluginEndpoint
import dev.hermes.android.updates.PluginRow
import dev.hermes.android.updates.PluginRowStrings
import dev.hermes.android.updates.PluginSentenceStates
import dev.hermes.android.updates.UpdateFailure
import dev.hermes.android.updates.heroSentence
import dev.hermes.android.updates.progressSizeLine
import dev.hermes.android.updates.visibleStepNote
import dev.hermes.android.updates.PluginStatus
import dev.hermes.android.updates.PluginVersions
import dev.hermes.android.updates.RESTART_DASHBOARD_HELP_URL
import dev.hermes.android.updates.RestartMemo
import dev.hermes.android.updates.RestartResult
import dev.hermes.android.updates.StepNote
import dev.hermes.android.updates.StepRow
import dev.hermes.android.updates.StepState
import dev.hermes.android.updates.StepStateText
import dev.hermes.android.updates.StepTracker
import dev.hermes.android.updates.UpdatePhase
import dev.hermes.android.updates.UpdateStep
import dev.hermes.android.updates.UpdateStepStrings
import dev.hermes.android.updates.afterDashboardFailure
import dev.hermes.android.updates.failed
import dev.hermes.android.updates.heroActions
import dev.hermes.android.updates.heroBarShown
import dev.hermes.android.updates.heroProgressLine
import dev.hermes.android.updates.progressLine
import dev.hermes.android.updates.runPluginUpdate
import dev.hermes.android.updates.stepReading
import dev.hermes.android.updates.stepStateText
import dev.hermes.android.updates.stepperRetryShown
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The update screen's stepper and hero (UX-5, UX-6, UX-10, UX-15, UX-31, UX-32, UX-33): one primary action per screen, no Cancel in the plugin
 * step, the owner's progressive step words, TalkBack text that says what each row is, and failure notes that do not repeat their button.
 */
class UpdateStepperUxTest {
    private val target = "1.1.23"
    private val mb = 1024L * 1024
    private val release = Release(123, "1.1.23", "dev.hermes.android.stable", "https://github.com/xploit9/beacon/releases/download/v1.1.23/Beacon-1.1.23.apk",
        "b".repeat(64), 40 * mb, "a".repeat(64), "1.1.23", "- Updates show every step", "2026-11-01T09:00:00Z")

    private fun versions(running: String, installed: String = running, gateway: String = installed) = PluginVersions(running, installed, gateway)

    /** A Hermes whose version reads and restart answers are scripted. */
    private class Fake(
        private val reads: List<PluginVersions>,
        val gateway: RestartResult = RestartResult.Completed,
        val dashboard: DashboardRestartResult = DashboardRestartResult.Completed,
    ) : PluginEndpoint {
        var read = 0
        val calls = mutableListOf<String>()
        override suspend fun version(): PluginVersions = reads[minOf(read++, reads.lastIndex)].also { calls += "version" }
        override suspend fun update(acceptCapabilities: Boolean): JSONObject { calls += "update"; return JSONObject().put("ok", true) }
        override suspend fun restart(last: RestartMemo, remember: (RestartMemo) -> Unit): RestartResult { calls += "gateway"; return gateway }
        override suspend fun restartDashboard(onReconnecting: (Boolean) -> Unit): DashboardRestartResult { calls += "dashboard"; return dashboard }
    }

    private fun run(endpoint: PluginEndpoint, tracker: StepTracker = StepTracker(), restartOnly: Boolean = false) =
        runBlocking { runPluginUpdate(endpoint, target, accept = false, restartOnly = restartOnly, steps = tracker, pause = {}) }

    private fun state(plugin: PluginRow, phase: UpdatePhase = UpdatePhase.Available, release: Release? = null, pluginSteps: List<StepRow> = emptyList(), appSteps: List<StepRow> = emptyList()) =
        AppUpdateUi(phase = phase, release = release, latest = this.release, plugin = plugin, pluginSteps = pluginSteps, appSteps = appSteps)

    // ---- UX-5: a dashboard-only failure has its own status and exactly one action

    @Test fun aDashboardOnlyFailureHasItsOwnStatusAndTheHeroOffersRestartDashboard() {
        val tracker = StepTracker()
        val row = run(Fake(listOf(versions("1.1.22", "1.1.23", "1.1.23")), dashboard = DashboardRestartResult.Failed), tracker)
        assertEquals(PluginRow(PluginStatus.DashboardFailed, "1.1.23", target), row)
        // Not the gateway's failure, and not the sentence the reviewer found misleading.
        assertEquals("The plugin is updated. The Hermes dashboard didn't restart.", row.text)
        assertFalse(row.text.contains("Couldn't restart Hermes"))
        assertEquals(StepRow(UpdateStep.PluginDashboard, StepState.Failed, "Didn't restart"), tracker.rows.failed)

        val screen = state(row, pluginSteps = tracker.rows)
        val hero = heroActions(screen, UpdatePhase.Available)
        assertEquals(HeroActions(primary = HeroPrimary.RestartDashboard), hero)
        assertEquals("Restart dashboard", hero.primary?.label)
        assertNull(hero.secondary)
        // The hero carries the action, so the stepper has no Try again row of its own.
        assertFalse(stepperRetryShown(screen, tracker.rows, hero))
    }

    @Test fun aDashboardThatCameBackStillOldIsTheSameDashboardOnlyFailure() {
        val row = run(Fake(listOf(versions("1.1.22", "1.1.23", "1.1.23"))))  // the reads never improve
        assertEquals(PluginStatus.DashboardFailed, row.status)
    }

    @Test fun restartDashboardRunsTheDashboardRestartAgainAndNothingElse() {
        val first = Fake(listOf(versions("1.1.22", "1.1.23", "1.1.23")), dashboard = DashboardRestartResult.Failed)
        assertEquals(PluginStatus.DashboardFailed, run(first).status)
        // The tap: the same Hermes, the dashboard still old, the restart now works.
        val again = Fake(listOf(versions("1.1.22", "1.1.23", "1.1.23"), versions("1.1.23")))
        val tracker = StepTracker()
        val row = run(again, tracker, restartOnly = true)
        assertEquals(PluginRow(PluginStatus.Updated, "1.1.23", target), row)
        assertEquals(listOf("version", "dashboard", "version"), again.calls)
        assertEquals(listOf(UpdateStep.PluginDashboard, UpdateStep.PluginDone), tracker.rows.map { it.step })
        assertTrue(tracker.rows.all { it.state == StepState.Done })
    }

    @Test fun theGatewayFailureKeepsItsOwnStatusAndTryAgain() {
        val tracker = StepTracker()
        val row = run(Fake(listOf(versions("1.1.22", "1.1.23", "1.1.22")), gateway = RestartResult.Failed), tracker, restartOnly = true)
        assertEquals(PluginStatus.RestartFailed, row.status)
        assertEquals("Hermes gateway didn't restart", row.text)
        val hero = heroActions(state(row, pluginSteps = tracker.rows), UpdatePhase.Available)
        assertEquals(HeroActions(primary = HeroPrimary.TryAgain), hero)
        assertFalse(stepperRetryShown(state(row, pluginSteps = tracker.rows), tracker.rows, hero))
    }

    @Test fun aDashboardFailureTheAppRemembersStaysADashboardFailureUntilTheVersionsSayOtherwise() {
        val manual = PluginRow(PluginStatus.DashboardRestart, "1.1.23", target)
        assertEquals(PluginStatus.DashboardFailed, manual.afterDashboardFailure(true).status)
        assertEquals(PluginStatus.DashboardRestart, manual.afterDashboardFailure(false).status)
        // Only a dashboard still old can be one that failed to restart.
        assertEquals(PluginStatus.UpToDate, PluginRow(PluginStatus.UpToDate, "1.1.23", target).afterDashboardFailure(true).status)
        assertEquals(PluginStatus.Restart, PluginRow(PluginStatus.Restart, "1.1.23", target).afterDashboardFailure(true).status)
    }

    @Test fun onePrimaryActionPerHeroForEveryPluginStateAndEveryPhase() {
        val expected = mapOf(
            PluginStatus.Restart to HeroActions(primary = HeroPrimary.RestartHermes),
            PluginStatus.RestartFailed to HeroActions(primary = HeroPrimary.TryAgain),
            PluginStatus.Failed to HeroActions(primary = HeroPrimary.TryAgain),
            PluginStatus.DashboardFailed to HeroActions(primary = HeroPrimary.RestartDashboard),
            PluginStatus.Busy to HeroActions(primary = HeroPrimary.Update),
            PluginStatus.Available to HeroActions(primary = HeroPrimary.Update),
            PluginStatus.Waiting to HeroActions(),
            PluginStatus.Newer to HeroActions(),
            PluginStatus.NeedsReview to HeroActions(),
            PluginStatus.DashboardRestart to HeroActions(secondary = HeroSecondary.How),
        )
        expected.forEach { (status, actions) -> assertEquals(status.name, actions, heroActions(state(PluginRow(status, "1.1.23", target)), UpdatePhase.Available)) }
        // An app release waiting makes the app's Update the screen's one primary action whatever the plugin says.
        PluginStatus.entries.forEach { status ->
            if (status != PluginStatus.Updating) assertEquals(status.name, HeroActions(primary = HeroPrimary.Update), heroActions(state(PluginRow(status, "1.1.22", target), release = release), UpdatePhase.Available))
        }
        assertEquals(HeroActions(secondary = HeroSecondary.Check), heroActions(state(PluginRow(), phase = UpdatePhase.Idle), UpdatePhase.Idle))
        assertEquals(HeroActions(primary = HeroPrimary.Allow), heroActions(state(PluginRow(), release = release), UpdatePhase.NeedsPermission))
        assertEquals(HeroActions(primary = HeroPrimary.RetryFailed), heroActions(state(PluginRow(), release = release), UpdatePhase.Failed))
        assertEquals(HeroActions(), heroActions(AppUpdateUi(supported = false), UpdatePhase.Idle))
    }

    @Test fun theManualFallbackHasAHowActionThatOpensTheReadmeSection() {
        assertEquals("Restart the Hermes dashboard on your computer.", StepNote.DashboardManual)
        assertEquals(StepNote.DashboardManual, PluginRow(PluginStatus.DashboardRestart).text)
        assertEquals("How", HeroSecondary.How.label)
        assertTrue(RESTART_DASHBOARD_HELP_URL.startsWith("https://github.com/xploit9/beacon#"))
        // No hero primary, so the stepper keeps the one Try again that re-checks after the owner restarted it by hand.
        val rows = listOf(StepRow(UpdateStep.PluginDashboard, StepState.Failed, StepNote.DashboardManual), StepRow(UpdateStep.PluginDone))
        val screen = state(PluginRow(PluginStatus.DashboardRestart, "1.1.23", target), pluginSteps = rows)
        assertTrue(stepperRetryShown(screen, rows, heroActions(screen, UpdatePhase.Available)))
    }

    @Test fun theStepperTryAgainShowsOnlyWhenItIsTheOnlyActionAndNothingIsRunning() {
        val failed = listOf(StepRow(UpdateStep.AppDownload, StepState.Failed, "x"), StepRow(UpdateStep.AppDone))
        val fine = listOf(StepRow(UpdateStep.AppDownload, StepState.Active), StepRow(UpdateStep.AppDone))
        val none = HeroActions()
        val screen = state(PluginRow(), phase = UpdatePhase.Idle)
        assertTrue(stepperRetryShown(screen, failed, none))
        assertFalse("no step failed", stepperRetryShown(screen, fine, none))
        assertFalse("the hero has the primary action", stepperRetryShown(screen, failed, HeroActions(primary = HeroPrimary.RetryFailed)))
        // The app update failed: the hero's Try again is the one.
        val appFailed = state(PluginRow(), phase = UpdatePhase.Failed, release = release, appSteps = failed)
        assertFalse(stepperRetryShown(appFailed, failed, heroActions(appFailed, UpdatePhase.Failed)))
        // A plugin step failed while the app downloads: Try again would do nothing until the update ends.
        listOf(UpdatePhase.Downloading, UpdatePhase.Verifying, UpdatePhase.Installing).forEach { phase ->
            val running = state(PluginRow(PluginStatus.Failed), phase = phase, release = release)
            assertFalse(phase.name, stepperRetryShown(running, failed, heroActions(running, phase)))
        }
    }

    // ---- UX-6: no Cancel in the plugin step

    @Test fun thereIsNoCancelWhileThePluginStepRunsAndOnlyTheAppDownloadAndVerifyCanBeCancelled() {
        val updating = PluginRow(PluginStatus.Updating, "1.1.22", target)
        // The plugin step runs (the app's download has not begun, or the phone only watches): nothing to cancel, in any phase.
        UpdatePhase.entries.forEach { phase -> assertEquals(phase.name, HeroActions(), heroActions(state(updating, phase = phase, release = release), phase)) }
        // The app downloads and verifies: Cancel, and only that.
        val plugin = PluginRow(PluginStatus.Updated, "1.1.23", target)
        assertEquals(HeroActions(secondary = HeroSecondary.Cancel), heroActions(state(plugin, UpdatePhase.Downloading, release), UpdatePhase.Downloading))
        assertEquals(HeroActions(secondary = HeroSecondary.Cancel), heroActions(state(plugin, UpdatePhase.Verifying, release), UpdatePhase.Verifying))
        // Android's installer has the file: no Cancel.
        assertEquals(HeroActions(), heroActions(state(plugin, UpdatePhase.Installing, release), UpdatePhase.Installing))
    }

    @Test fun cancellingRefreshesThePluginRowSoItShowsWhatHermesReallyHas() {
        // UpdateManager needs the Android application, so the cancel path is read from its source: the stale "Updating…" row is dropped
        // and the row is read from Hermes again, in the branch that handles a cancelled update and in the plugin step itself.
        val manager = source("UpdateManager.kt")
        val cancelled = manager.substringAfter("catch (error: CancellationException) {\n            pluginSteps.clear()").substringBefore("throw error")
        assertTrue(cancelled, cancelled.contains("pluginResult = null") && cancelled.contains("refreshPlugin(client, pluginAbsent)"))
        val step = manager.substringAfter("private suspend fun pluginStep").substringBefore("restartMemoSeen = restartMemo(client)")
        assertTrue(step.contains("catch (error: CancellationException) { pluginResult = null; throw error }"))
    }

    // ---- UX-15: the owner's progressive labels, and what TalkBack says

    @Test fun theStepLabelsAreTheOwnersProgressiveWords() {
        assertEquals(listOf("Downloading", "Installing", "Restarting Hermes gateway", "Restarting Hermes dashboard", "Done"),
            listOf(UpdateStep.PluginDownload, UpdateStep.PluginInstall, UpdateStep.PluginGateway, UpdateStep.PluginDashboard, UpdateStep.PluginDone).map { it.label })
        assertEquals(listOf("Downloading", "Verifying", "Installing", "Done"),
            listOf(UpdateStep.AppDownload, UpdateStep.AppVerify, UpdateStep.AppInstall, UpdateStep.AppDone).map { it.label })
        assertTrue(UpdateStepStrings.containsAll(listOf("Restarting Hermes gateway", "Restarting Hermes dashboard")))
    }

    @Test fun talkBackReadsAFinishedStepAsItsLabelAndDone() {
        assertEquals("Restarting Hermes gateway, Done", stepReading(StepRow(UpdateStep.PluginGateway, StepState.Done)))
        assertEquals("Restarting Hermes dashboard, Working", stepReading(StepRow(UpdateStep.PluginDashboard, StepState.Active)))
        assertEquals("Installing, Waiting", stepReading(StepRow(UpdateStep.PluginInstall)))
        assertEquals("Restarting Hermes dashboard, Didn't restart, Failed", stepReading(StepRow(UpdateStep.PluginDashboard, StepState.Failed, StepNote.DashboardFailed)))
        assertEquals("Verifying, Done", stepReading(StepRow(UpdateStep.AppVerify, StepState.Done)))
    }

    @Test fun theClosingRowReadsDoneNotYetUntilEverythingFinishedAndThenJustDone() {
        assertEquals("Done, Not yet", stepReading(StepRow(UpdateStep.PluginDone)))
        assertEquals("Done, Not yet", stepReading(StepRow(UpdateStep.AppDone)))
        assertEquals("Done", stepReading(StepRow(UpdateStep.PluginDone, StepState.Done)))
        assertEquals("Not yet", stepStateText(StepRow(UpdateStep.AppDone)))
        assertEquals("", stepStateText(StepRow(UpdateStep.AppDone, StepState.Done)))
        // Every spoken state is listed for translation.
        StepState.entries.forEach { state ->
            listOf(StepRow(UpdateStep.PluginGateway, state), StepRow(UpdateStep.PluginDone, state)).forEach { row ->
                stepStateText(row).takeIf(String::isNotBlank)?.let { assertTrue(it, it in StepStateText.all && it in PluginRowStrings) }
            }
        }
    }

    // ---- UX-31: the note beside a Try again button does not say Try again

    @Test fun failureNotesDoNotRepeatTheirButton() {
        assertEquals("Didn't restart", StepNote.DashboardFailed)
        assertEquals("Hermes gateway didn't restart", StepNote.GatewayFailed)
        assertEquals("Didn't update", StepNote.PluginFailed)
        assertEquals("Hermes is busy", StepNote.HermesBusy)
        StepNote.all.forEach { assertFalse(it, it.contains("Try again", ignoreCase = true)) }
    }

    @Test fun aStepNoteThatRepeatsTheHeroSentenceIsLeftOut() {
        val manual = StepRow(UpdateStep.PluginDashboard, StepState.Failed, StepNote.DashboardManual)
        val screen = state(PluginRow(PluginStatus.DashboardRestart, "1.1.23", target), pluginSteps = listOf(manual))
        // Only the plugin has something to do: the hero says the sentence, the step does not.
        assertEquals("Restart the Hermes dashboard on your computer.", heroSentence(screen, UpdatePhase.Available))
        assertEquals("", visibleStepNote(manual, heroSentence(screen, UpdatePhase.Available)))
        // With an app update waiting, the hero is the app's, so the step keeps its note.
        val withApp = state(PluginRow(PluginStatus.DashboardRestart, "1.1.23", target), release = release, pluginSteps = listOf(manual))
        assertEquals("", heroSentence(withApp, UpdatePhase.Available))
        assertEquals(StepNote.DashboardManual, visibleStepNote(manual, heroSentence(withApp, UpdatePhase.Available)))
        // Every plugin sentence the hero says is the row's own text; the gateway failure shows its note once.
        PluginSentenceStates.forEach { status ->
            assertEquals(status.name, PluginRow(status).text, heroSentence(state(PluginRow(status, "1.1.23", target)), UpdatePhase.Available))
        }
        val gateway = StepRow(UpdateStep.PluginGateway, StepState.Failed, StepNote.GatewayFailed)
        assertEquals("", visibleStepNote(gateway, heroSentence(state(PluginRow(PluginStatus.RestartFailed)), UpdatePhase.Available)))
        // A different note stays: "Didn't restart" says what the hero's longer sentence does not.
        val dashboard = StepRow(UpdateStep.PluginDashboard, StepState.Failed, StepNote.DashboardFailed)
        assertEquals("Didn't restart", visibleStepNote(dashboard, heroSentence(state(PluginRow(PluginStatus.DashboardFailed)), UpdatePhase.Available)))
        // The app's failure reads once too: in the hero, not again on the failed step.
        val download = StepRow(UpdateStep.AppDownload, StepState.Failed, UpdateFailure.Download.text)
        val failed = AppUpdateUi(phase = UpdatePhase.Failed, release = release, latest = release, failure = UpdateFailure.Download, appSteps = listOf(download))
        assertEquals(UpdateFailure.Download.text, heroSentence(failed, UpdatePhase.Failed))
        assertEquals("", visibleStepNote(download, heroSentence(failed, UpdatePhase.Failed)))
        // Nothing is repeated while the plugin runs or when nothing was said.
        assertEquals("", heroSentence(state(PluginRow(PluginStatus.Updating)), UpdatePhase.Downloading))
        assertEquals("", visibleStepNote(StepRow(UpdateStep.PluginGateway), "anything"))
    }

    @Test fun theHeroDownloadLineSaysOfInThePhonesLanguage() {
        assertEquals("13 of 40 MB", progressSizeLine(13 * mb, 40 * mb))
        assertEquals("13 von 40 MB", progressSizeLine(13 * mb, 40 * mb, "{0} von {1}"))
        assertEquals("13 من 40 MB", progressSizeLine(13 * mb, 40 * mb, "{0} من {1}"))
        val withSteps = AppUpdateUi(phase = UpdatePhase.Downloading, release = release, latest = release, downloaded = 13 * mb,
            appSteps = listOf(StepRow(UpdateStep.AppDownload, StepState.Active), StepRow(UpdateStep.AppDone)))
        assertEquals("13 von 40 MB", heroProgressLine(withSteps, release.size, "{0} von {1}"))
        assertEquals("32% · 13 von 40 MB", progressLine(13 * mb, 40 * mb, "{0} von {1}"))
    }

    // ---- UX-33: progress is shown once

    @Test fun withAChecklistTheHeroHasNoBarAndOnlyTheSizeAndTheStepHasThePercent() {
        val rows = listOf(StepRow(UpdateStep.AppDownload, StepState.Active), StepRow(UpdateStep.AppVerify), StepRow(UpdateStep.AppInstall), StepRow(UpdateStep.AppDone))
        val withSteps = AppUpdateUi(phase = UpdatePhase.Downloading, release = release, latest = release, downloaded = 13 * mb, appSteps = rows)
        assertFalse(heroBarShown(withSteps))
        assertEquals("13 of 40 MB", heroProgressLine(withSteps, release.size))
        assertFalse(heroProgressLine(withSteps, release.size).contains("%"))
        // The plugin's checklist carries its own progress too.
        assertFalse(heroBarShown(AppUpdateUi(phase = UpdatePhase.Downloading, pluginSteps = listOf(StepRow(UpdateStep.PluginDownload, StepState.Active)))))
        // With no checklist (a state shown from facts alone) the hero is the only progress, bar and percent included.
        val bare = AppUpdateUi(phase = UpdatePhase.Downloading, release = release, latest = release, downloaded = 13 * mb)
        assertTrue(heroBarShown(bare))
        assertEquals(progressLine(13 * mb, release.size), heroProgressLine(bare, release.size))
        assertEquals("32% · 13 of 40 MB", heroProgressLine(bare, release.size))
    }

    // ---- UX-10: one name for the thing on Hermes

    @Test fun theUpdateScreenCallsThePluginTheBeaconPluginAndNeverTheHermesPlugin() {
        val screen = source("UpdateScreen.kt")
        val state = source("PluginUpdateState.kt")
        val steps = source("UpdateSteps.kt")
        listOf(screen, state, steps).forEach { text -> assertFalse(text.contains("\"Hermes plugin")) }
        assertEquals(3, Regex("""uiText\("Beacon plugin"\)""").findAll(screen).count())
        assertTrue(screen.contains("uiText(\"Updating the Beacon plugin…\")"))
        assertTrue(screen.contains("uiText(\"Review what's changing\")"))
        assertTrue(screen.contains("uiText(\"This update changes what the Beacon plugin can do on Hermes.\")"))
        assertEquals("The Beacon plugin is newer than this app. Update the app.", PluginRow(PluginStatus.Newer).text)
        assertNotNull(PluginRowStrings.firstOrNull { it == "The Beacon plugin is newer than this app. Update the app." })
        assertTrue(HeroActionStrings.containsAll(listOf("Restart dashboard", "How", "Cancel", "Try again")))
    }

    private fun source(name: String): String {
        val root = requireNotNull(listOf(".", "app", "beacon-android/app").map(::File).firstOrNull { File(it, "src/main/java/dev/hermes/android/updates/$name").isFile }) {
            "Run this check from the Beacon app, Android project or repository root."
        }
        return File(root, "src/main/java/dev/hermes/android/updates/$name").readText().replace("\r\n", "\n")
    }
}
