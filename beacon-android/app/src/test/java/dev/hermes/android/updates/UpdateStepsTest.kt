package dev.hermes.android.updates

import dev.hermes.android.data.ApiFailure
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checklist the update screen shows: every state is a fact the update observed, and a step it does not need is removed, not faked. */
class UpdateStepsTest {
    private val target = "1.1.23"
    private fun versions(running: String, installed: String = running, gateway: String = installed) = PluginVersions(running, installed, gateway)

    /** A Hermes whose answers are scripted; [reads] are the successive version reads (the last one repeats). */
    private class Fake(
        private val reads: List<PluginVersions>,
        private val update: () -> JSONObject = { JSONObject().put("ok", true) },
        val gateway: RestartResult = RestartResult.Completed,
        val dashboard: DashboardRestartResult = DashboardRestartResult.Completed,
        val reconnecting: List<Boolean> = emptyList(),
    ) : PluginEndpoint {
        var read = 0
        val calls = mutableListOf<String>()
        override suspend fun version(): PluginVersions = reads[minOf(read++, reads.lastIndex)].also { calls += "version" }
        override suspend fun update(acceptCapabilities: Boolean): JSONObject { calls += "update"; return update() }
        override suspend fun restart(last: RestartMemo, remember: (RestartMemo) -> Unit): RestartResult { calls += "gateway"; return gateway }
        override suspend fun restartDashboard(onReconnecting: (Boolean) -> Unit): DashboardRestartResult {
            calls += "dashboard"
            reconnecting.forEach(onReconnecting)
            return dashboard
        }
    }

    private class Run(val row: PluginRow, val history: List<List<StepRow>>, val steps: List<StepRow>)

    private fun run(endpoint: PluginEndpoint, restartOnly: Boolean = false, memo: RestartMemo = RestartMemo()): Run {
        val history = mutableListOf<List<StepRow>>()
        val tracker = StepTracker { history += it }
        val row = runBlocking { runPluginUpdate(endpoint, target, accept = false, restartOnly = restartOnly, memo = memo, steps = tracker, pause = {}) }
        return Run(row, history, tracker.rows)
    }

    private fun List<StepRow>.state(step: UpdateStep) = firstOrNull { it.step == step }?.state
    private fun List<StepRow>.note(step: UpdateStep) = firstOrNull { it.step == step }?.note

    // ---- the tracker itself

    @Test fun aPlanListsTheStepsAsWaitingAndClosesWithDone() {
        val tracker = StepTracker()
        tracker.plan(UpdateStep.PluginDownload, UpdateStep.PluginInstall)
        assertEquals(listOf(UpdateStep.PluginDownload, UpdateStep.PluginInstall, UpdateStep.PluginDone), tracker.rows.map { it.step })
        assertTrue(tracker.rows.all { it.state == StepState.Waiting })
        tracker.plan(UpdateStep.AppDownload, UpdateStep.AppVerify)
        assertEquals(UpdateStep.AppDone, tracker.rows.last().step)
    }

    @Test fun aStepMovesOnlyWhenTheCallerSaysSoAndOnlyItsOwnRow() {
        val tracker = StepTracker()
        tracker.plan(UpdateStep.PluginDownload, UpdateStep.PluginInstall)
        tracker.active(UpdateStep.PluginInstall)
        assertEquals(listOf(StepState.Waiting, StepState.Active, StepState.Waiting), tracker.rows.map { it.state })
        tracker.fail(UpdateStep.PluginInstall, "No")
        assertEquals(StepRow(UpdateStep.PluginInstall, StepState.Failed, "No"), tracker.rows.failed)
        // A step that is not in the plan cannot be marked.
        tracker.done(UpdateStep.PluginGateway)
        assertEquals(3, tracker.rows.size)
    }

    @Test fun theClosingDoneTurnsDoneOnlyWhenEveryStepBeforeItIs() {
        val tracker = StepTracker()
        tracker.plan(UpdateStep.PluginDownload, UpdateStep.PluginInstall)
        tracker.done(UpdateStep.PluginDownload)
        tracker.complete()
        assertEquals(StepState.Waiting, tracker.rows.state(UpdateStep.PluginDone))
        tracker.done(UpdateStep.PluginInstall)
        tracker.complete()
        assertEquals(StepState.Done, tracker.rows.state(UpdateStep.PluginDone))
    }

    @Test fun aSkippedStepLeavesTheListInsteadOfBeingShownAsDone() {
        val tracker = StepTracker()
        tracker.plan(UpdateStep.PluginGateway, UpdateStep.PluginDashboard)
        tracker.skip(UpdateStep.PluginGateway)
        assertEquals(listOf(UpdateStep.PluginDashboard, UpdateStep.PluginDone), tracker.rows.map { it.step })
    }

    @Test fun aNoteOnlyAttachesToTheStepThatIsRunning() {
        val tracker = StepTracker()
        tracker.plan(UpdateStep.PluginDashboard)
        tracker.note(UpdateStep.PluginDashboard, "x")
        assertEquals("", tracker.rows.note(UpdateStep.PluginDashboard))
        tracker.active(UpdateStep.PluginDashboard)
        tracker.note(UpdateStep.PluginDashboard, StepNote.Reconnecting)
        assertEquals(StepNote.Reconnecting, tracker.rows.note(UpdateStep.PluginDashboard))
        tracker.hold(UpdateStep.PluginDashboard, StepNote.Busy)
        assertEquals(StepRow(UpdateStep.PluginDashboard, StepState.Waiting, StepNote.Busy), tracker.rows.first())
    }

    @Test fun everyStepAndNoteTheScreenShowsIsListedForTranslation() {
        assertTrue(PluginRowStrings.containsAll(UpdateStepStrings))
        assertTrue(PluginRowStrings.containsAll(StepNote.all))
        assertTrue(PluginRowStrings.containsAll(StepStateText.all))
        assertTrue(PluginRowStrings.containsAll(HeroActionStrings))
        assertEquals(setOf("Downloading", "Installing", "Verifying", "Restarting Hermes gateway", "Restarting Hermes dashboard", "Done"), UpdateStepStrings.toSet())
    }

    // ---- a plugin update, step by step

    @Test fun aFullUpdateWalksDownloadInstallGatewayDashboardAndDone() {
        val endpoint = Fake(listOf(
            versions("1.1.22", "1.1.22"),            // before
            versions("1.1.22", "1.1.23", "1.1.22"),  // new files on disk, both processes old
            versions("1.1.22", "1.1.23", "1.1.23"),  // after the gateway restart: only the dashboard is old
            versions("1.1.23", "1.1.23", "1.1.23"),  // after the dashboard restart
        ))
        val result = run(endpoint)
        assertEquals(PluginRow(PluginStatus.Updated, "1.1.23", target), result.row)
        assertEquals(listOf("version", "update", "version", "gateway", "version", "dashboard", "version"), endpoint.calls)
        assertTrue(result.steps.all { it.state == StepState.Done })
        assertEquals(listOf(UpdateStep.PluginDownload, UpdateStep.PluginInstall, UpdateStep.PluginGateway, UpdateStep.PluginDashboard, UpdateStep.PluginDone), result.steps.map { it.step })
        // The order in which things really happened: download first, the gateway only once installed, the dashboard only once the gateway is back.
        fun at(predicate: (List<StepRow>) -> Boolean) = result.history.indexOfFirst(predicate)
        val downloading = at { it.state(UpdateStep.PluginDownload) == StepState.Active }
        val installing = at { it.state(UpdateStep.PluginInstall) == StepState.Active && it.state(UpdateStep.PluginDownload) == StepState.Done }
        val gateway = at { it.state(UpdateStep.PluginGateway) == StepState.Active && it.state(UpdateStep.PluginInstall) == StepState.Done }
        val dashboard = at { it.state(UpdateStep.PluginDashboard) == StepState.Active && it.state(UpdateStep.PluginGateway) == StepState.Done }
        assertTrue("$downloading $installing $gateway $dashboard", downloading in 0 until installing && installing < gateway && gateway < dashboard)
    }

    @Test fun anUpdateThatOnlyNeedsTheDashboardSkipsTheGatewayStep() {
        val endpoint = Fake(listOf(versions("1.1.22", "1.1.22", "1.1.22"), versions("1.1.22", "1.1.23", "1.1.23"), versions("1.1.23")))
        val result = run(endpoint)
        assertEquals(PluginStatus.Updated, result.row.status)
        assertEquals(listOf(UpdateStep.PluginDownload, UpdateStep.PluginInstall, UpdateStep.PluginDashboard, UpdateStep.PluginDone), result.steps.map { it.step })
        assertTrue(endpoint.calls.none { it == "gateway" })
    }

    @Test fun aDashboardThatIsTheOnlyOldPartIsRestartedWithoutAnyDownloadOrGatewayStep() {
        val endpoint = Fake(listOf(versions("1.1.22", "1.1.23", "1.1.23"), versions("1.1.23")))
        val result = run(endpoint)
        assertEquals(PluginRow(PluginStatus.Updated, "1.1.23", target), result.row)
        assertEquals(listOf("version", "dashboard", "version"), endpoint.calls)
        assertEquals(listOf(UpdateStep.PluginDashboard, UpdateStep.PluginDone), result.steps.map { it.step })
        assertTrue(result.steps.all { it.state == StepState.Done })
    }

    @Test fun aRestartOnlyRetryRestartsTheGatewayThenTheDashboard() {
        val endpoint = Fake(listOf(versions("1.1.22", "1.1.23", "1.1.22"), versions("1.1.22", "1.1.23", "1.1.23"), versions("1.1.23")))
        val result = run(endpoint, restartOnly = true)
        assertEquals(PluginStatus.Updated, result.row.status)
        assertEquals(listOf(UpdateStep.PluginGateway, UpdateStep.PluginDashboard, UpdateStep.PluginDone), result.steps.map { it.step })
        assertTrue(result.steps.all { it.state == StepState.Done })
    }

    @Test fun anOlderPluginWithoutTheRouteLeavesTheDashboardToTheOwnerAsBefore() {
        val endpoint = Fake(listOf(versions("1.1.22", "1.1.23", "1.1.23")), dashboard = DashboardRestartResult.Unsupported)
        val result = run(endpoint)
        assertEquals(PluginRow(PluginStatus.DashboardRestart, "1.1.23", target), result.row)
        assertEquals(StepRow(UpdateStep.PluginDashboard, StepState.Failed, StepNote.DashboardManual), result.steps.failed)
        assertEquals("Restart the Hermes dashboard on your computer.", PluginRow(PluginStatus.DashboardRestart).text)
        assertEquals(StepNote.DashboardManual, PluginRow(PluginStatus.DashboardRestart).text)
    }

    // Owner, 3 Nov: a dashboard on an older plugin may still refuse while Hermes is busy; the gateway, restarted with the new plugin,
    // restarts it anyway, so the phone waits for that and the update ends as one version instead of "Hermes is busy".
    @Test fun aBusyRefusalFromAnOlderDashboardEndsUpdatedOnceTheGatewayRestartsIt() {
        val endpoint = Fake(listOf(versions("1.1.22", "1.1.23", "1.1.23"), versions("1.1.23")), dashboard = DashboardRestartResult.Waiting)
        val result = run(endpoint)
        assertEquals(PluginStatus.Updated, result.row.status)
        assertTrue(result.steps.all { it.state == StepState.Done })
    }

    @Test fun theDashboardStepShowsWhileItReconnects() {
        val endpoint = Fake(listOf(versions("1.1.22", "1.1.23", "1.1.23"), versions("1.1.23")), reconnecting = listOf(true, true, false))
        val result = run(endpoint)
        assertTrue(result.history.any { it.note(UpdateStep.PluginDashboard) == StepNote.Reconnecting && it.state(UpdateStep.PluginDashboard) == StepState.Active })
        assertEquals("", result.steps.note(UpdateStep.PluginDashboard))
    }

    @Test fun aDashboardThatCameBackStillOldIsNotCalledUpdated() {
        val endpoint = Fake(listOf(versions("1.1.22", "1.1.23", "1.1.23")))  // the version reads never improve
        val result = run(endpoint)
        assertEquals(PluginStatus.DashboardFailed, result.row.status)
        assertEquals(StepNote.DashboardFailed, result.steps.failed?.note)
        assertNull(result.steps.firstOrNull { it.step == UpdateStep.PluginDone && it.state == StepState.Done })
    }

    @Test fun eachDashboardRefusalLandsOnItsOwnRowAndStepNeverUpdated() {
        val expected = mapOf(
            DashboardRestartResult.Waiting to (PluginStatus.Waiting to StepState.Waiting),
            DashboardRestartResult.Call to (PluginStatus.Busy to StepState.Failed),
            DashboardRestartResult.Failed to (PluginStatus.DashboardFailed to StepState.Failed),
        )
        expected.forEach { (result, outcome) ->
            val run = run(Fake(listOf(versions("1.1.22", "1.1.23", "1.1.23")), dashboard = result))
            assertEquals(result.name, outcome.first, run.row.status)
            assertEquals(result.name, outcome.second, run.steps.state(UpdateStep.PluginDashboard))
            assertEquals(result.name, StepState.Waiting, run.steps.state(UpdateStep.PluginDone))
        }
    }

    @Test fun aHermesThatIsBusyHoldsTheGatewayStepWithoutFailingIt() {
        val endpoint = Fake(listOf(versions("1.1.22", "1.1.23", "1.1.22")), gateway = RestartResult.Waiting)
        val result = run(endpoint, restartOnly = true)
        assertEquals(PluginStatus.Waiting, result.row.status)
        assertEquals(StepRow(UpdateStep.PluginGateway, StepState.Waiting, StepNote.Busy), result.steps.first())
        assertNull(result.steps.failed)
        assertTrue(endpoint.calls.none { it == "dashboard" })
    }

    @Test fun aGatewayRestartThatFailsStopsThereAndSaysSo() {
        val endpoint = Fake(listOf(versions("1.1.22", "1.1.23", "1.1.22")), gateway = RestartResult.Failed)
        val result = run(endpoint, restartOnly = true)
        assertEquals(PluginStatus.RestartFailed, result.row.status)
        assertEquals(StepRow(UpdateStep.PluginGateway, StepState.Failed, StepNote.GatewayFailed), result.steps.failed)
        assertEquals(StepState.Waiting, result.steps.state(UpdateStep.PluginDashboard))
        assertTrue(endpoint.calls.none { it == "dashboard" })
    }

    @Test fun aRefusedDownloadFailsTheDownloadStep() {
        val endpoint = Fake(listOf(versions("1.1.22", "1.1.22")), update = { JSONObject().put("ok", false) })
        val result = run(endpoint)
        assertEquals(PluginStatus.Failed, result.row.status)
        assertEquals(StepRow(UpdateStep.PluginDownload, StepState.Failed, StepNote.PluginFailed), result.steps.failed)
    }

    @Test fun aRequestThatFailsFailsTheStepThatWasRunning() {
        val busy = run(Fake(listOf(versions("1.1.22", "1.1.22")), update = { throw ApiFailure(409, "busy") }))
        assertEquals(PluginStatus.Busy, busy.row.status)
        assertEquals(StepRow(UpdateStep.PluginDownload, StepState.Failed, StepNote.HermesBusy), busy.steps.failed)
        val broken = run(Fake(listOf(versions("1.1.22", "1.1.22")), update = { throw ApiFailure(500, "no") }))
        assertEquals(StepRow(UpdateStep.PluginDownload, StepState.Failed, StepNote.PluginFailed), broken.steps.failed)
        val notOwner = run(Fake(listOf(versions("1.1.22", "1.1.22")), update = { throw ApiFailure(403, "no") }))
        assertEquals(PluginRow(), notOwner.row)
        assertTrue(notOwner.steps.isEmpty())
    }

    @Test fun filesThatNeverArriveFailTheInstallStep() {
        val endpoint = Fake(listOf(versions("1.1.22", "1.1.22")))  // Hermes keeps reporting the old version
        val result = run(endpoint)
        assertEquals(PluginStatus.Failed, result.row.status)
        assertEquals(UpdateStep.PluginInstall, result.steps.failed?.step)
        assertEquals(StepState.Done, result.steps.state(UpdateStep.PluginDownload))
    }

    @Test fun anUpdateThatNeedsReviewShowsNoStepsAtAll() {
        val endpoint = Fake(listOf(versions("1.1.22", "1.1.22")), update = { JSONObject().put("consent_required", true) })
        val result = run(endpoint)
        assertEquals(PluginStatus.NeedsReview, result.row.status)
        assertTrue(result.steps.isEmpty())
    }

    @Test fun anUpToDatePluginHasNothingToWalkThrough() {
        val endpoint = Fake(listOf(versions("1.1.23", "1.1.23")))
        val result = run(endpoint, restartOnly = true)
        assertEquals(PluginStatus.Updated, result.row.status)
        assertTrue(result.steps.isEmpty())
    }
}
