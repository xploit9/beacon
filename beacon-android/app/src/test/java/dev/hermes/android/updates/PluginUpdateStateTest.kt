package dev.hermes.android.updates

import dev.hermes.android.data.ApiFailure
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class PluginUpdateStateTest {
    private class Fake(private val reads: List<PluginVersions>, private val answer: () -> JSONObject = { JSONObject().put("ok", true) }, private val failure: Exception? = null, val restartResult: RestartResult = RestartResult.Pending) : PluginEndpoint {
        var read = 0
        var restarts = 0
        var memos = emptyList<RestartMemo>()
        override suspend fun restart(last: RestartMemo, remember: (RestartMemo) -> Unit): RestartResult { restarts++; memos += last; return restartResult }
        var updates = 0
        override suspend fun version(): PluginVersions = reads[minOf(read++, reads.lastIndex)]
        override suspend fun update(): JSONObject {
            updates++
            failure?.let { throw it }
            return answer()
        }
    }
    private fun versions(running: String, installed: String = running) = PluginVersions(running, installed)

    @Test(timeout = 8000) fun theSafeRestartWaitsForItsActionAndAReadyGateway() = runBlocking {
        val paths = mutableListOf<String>()
        var gatewayReads = 0
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request(); paths += request.url.encodedPath
                val value = when (request.url.encodedPath) {
                    "/api/plugins/beacon/admin/plugin/restart" -> { assertEquals("POST", request.method); "{\"restart_started\":true,\"restart_pid\":123}" }
                    "/api/plugins/beacon/admin/plugin/restart/status" -> { assertEquals("123", request.url.queryParameter("pid")); "{\"pid\":123,\"completed\":true}" }
                    "/api/status" -> if (++gatewayReads == 1) "{\"gateway_running\":false}" else "{\"gateway_running\":true,\"gateway_state\":\"running\"}"
                    else -> error("Unexpected request")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(value.toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            assertEquals(RestartResult.Completed, HermesPluginUpdater(client).restart())
            assertEquals(2, gatewayReads)
            assertEquals(1, paths.count { it == "/api/plugins/beacon/admin/plugin/restart" })
            assertFalse(paths.contains("/api/gateway/restart"))
        } finally { client.closeNative() }
    }

    @Test(timeout = 5000) fun aRefusedOrUnrelatedRestartNeverUsesTheForceEndpoint() = runBlocking {
        for (accepted in listOf(false, true)) {
            var posts = 0
            val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
                builder.addInterceptor { chain ->
                    val request = chain.request()
                    val value = when (request.url.encodedPath) {
                        "/api/plugins/beacon/admin/plugin/restart" -> { posts++; "{\"restart_started\":$accepted,\"restart_pid\":123}" }
                        "/api/plugins/beacon/admin/plugin/restart/status" -> "{\"pid\":124,\"completed\":true}"
                        else -> error("No force restart or unrelated success is allowed")
                    }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(value.toResponseBody("application/json".toMediaType())).build()
                }
            }
            try { assertEquals(RestartResult.Failed, HermesPluginUpdater(client).restart()); assertEquals(1, posts) }
            finally { client.closeNative() }
        }
    }

    @Test fun aSavedRestartCanRetryAfterTheDashboardAlreadyReloaded() = runBlocking {
        val endpoint = Fake(listOf(versions("1.1.3")), restartResult = RestartResult.Completed)
        assertEquals(PluginStatus.Updated, runPluginUpdate(endpoint, "1.1.3", restartOnly = true) {}.status)
        assertEquals(0, endpoint.updates)
        assertEquals(1, endpoint.restarts)
    }

    @Test fun anInterruptedUpdateStillInstallsTheTargetBeforeRestarting() = runBlocking {
        val endpoint = Fake(listOf(versions("1.1.1"), versions("1.1.3")), restartResult = RestartResult.Completed)
        assertEquals(PluginStatus.Updated, runPluginUpdate(endpoint, "1.1.3", restartOnly = true) {}.status)
        assertEquals(1, endpoint.updates)
        assertEquals(0, endpoint.restarts)
    }

    @Test fun finishingAnOlderPendingRestartLeavesTheNewReleaseAvailable() {
        val endpoint = Fake(listOf(versions("1.1.0", "1.1.1"), versions("1.1.1")), restartResult = RestartResult.Completed)
        assertEquals(PluginStatus.Available, updateWith(endpoint).status)
        assertEquals(0, endpoint.updates)
        assertEquals(1, endpoint.restarts)
    }

    @Test fun aSuccessfulUpdateRestartsTheGatewayBeforeReportingUpdated() {
        val endpoint = Fake(listOf(versions("1.1.1"), versions("1.1.1", "1.1.3"), versions("1.1.3")),
            { JSONObject().put("ok", true).put("restart_required", true) }, restartResult = RestartResult.Completed)
        assertEquals(PluginStatus.Updated, updateWith(endpoint).status)
        assertEquals(1, endpoint.restarts)
        assertEquals(1, endpoint.updates)
    }

    @Test fun pendingRestartRetriesDoNotInstallThePluginAgain() {
        val endpoint = Fake(listOf(versions("1.1.1", "1.1.3"), versions("1.1.3")), restartResult = RestartResult.Completed)
        assertEquals(PluginStatus.Updated, updateWith(endpoint).status)
        assertEquals(0, endpoint.updates)
        assertEquals(1, endpoint.restarts)
    }

    @Test fun aRestartThatIsNotConfirmedStaysPendingInsteadOfClaimingUpdated() {
        val endpoint = Fake(listOf(versions("1.1.1", "1.1.3")), restartResult = RestartResult.Pending)
        assertEquals(PluginStatus.Restart, updateWith(endpoint).status)
        assertEquals(1, endpoint.restarts)
        assertEquals(0, endpoint.updates)
    }

    @Test fun eachRefusalLandsOnItsOwnRowAndNeverClaimsUpdated() {
        val expected = mapOf(RestartResult.Waiting to PluginStatus.Waiting, RestartResult.Call to PluginStatus.Busy,
            RestartResult.Failed to PluginStatus.RestartFailed, RestartResult.Pending to PluginStatus.Restart)
        expected.forEach { (result, status) -> assertEquals(result.name, status, updateWith(Fake(listOf(versions("1.1.1", "1.1.3")), restartResult = result)).status) }
        // All of them stay actionable, so a later tap or an idle Hermes finishes the job.
        expected.values.forEach { assertTrue(it.name, PluginRow(it).actionable) }
    }

    @Test fun aRestartThatFinishedButLeftTheDashboardOldSaysSoInsteadOfOfferingTheSameRestart() {
        // The plugin cannot tell the gateway's version: after a completed restart a still-old dashboard is a dashboard restart.
        val legacy = Fake(listOf(versions("1.1.1", "1.1.3"), versions("1.1.1", "1.1.3")), restartResult = RestartResult.Completed)
        assertEquals(PluginRow(PluginStatus.DashboardRestart, "1.1.3", "1.1.3"), updateWith(legacy))
        // A gateway that reports its version settles it: it loaded the new plugin, the dashboard did not.
        val known = Fake(listOf(versions("1.1.1", "1.1.3").copy(gateway = "1.1.1"), versions("1.1.1", "1.1.3").copy(gateway = "1.1.3")), restartResult = RestartResult.Completed)
        assertEquals(PluginStatus.DashboardRestart, updateWith(known).status)
        assertEquals(1, known.restarts)
        // The gateway still on the old plugin after its restart is still a gateway restart.
        val stuck = Fake(listOf(versions("1.1.3", "1.1.3").copy(gateway = "1.1.1")), restartResult = RestartResult.Completed)
        assertEquals(PluginStatus.Restart, updateWith(stuck).status)
    }

    @Test fun aDashboardTooOldToRestartItselfIsWaitedForWhileTheGatewayRestartsIt() {
        // The old dashboard has no restart route; the gateway, started with the new plugin, restarts it and the phone waits for that.
        val old = versions("1.1.1", "1.1.3")
        val endpoint = Fake(listOf(old, old, old, old, versions("1.1.3", "1.1.3")), restartResult = RestartResult.Completed)
        val pauses = mutableListOf<Long>()
        assertEquals(PluginRow(PluginStatus.Updated, "1.1.3", "1.1.3"), updateWith(endpoint, pauses = pauses))
        assertEquals("polled every two seconds until the new dashboard answered", listOf(2000L, 2000L), pauses.filter { it == 2000L })
        // A dashboard that never comes back new still ends with the honest manual line, after the wait.
        val never = mutableListOf<Long>()
        assertEquals(PluginStatus.DashboardRestart, updateWith(Fake(listOf(old), restartResult = RestartResult.Completed), pauses = never).status)
        assertEquals(GATEWAY_LED_RESTART_MS, never.filter { it == 2000L }.sum())
    }

    @Test fun onlyTheDashboardBeingOldNeverAsksForAGatewayRestart() {
        val dashboardOnly = Fake(listOf(versions("1.1.1", "1.1.3").copy(gateway = "1.1.3")))
        assertEquals(PluginRow(PluginStatus.DashboardRestart, "1.1.3", "1.1.3"), updateWith(dashboardOnly))
        assertEquals(0, dashboardOnly.restarts)
        assertEquals(0, dashboardOnly.updates)
        // The phone's memory of a finished restart does the same when the gateway cannot be read.
        val remembered = Fake(listOf(versions("1.1.1", "1.1.3")))
        assertEquals(PluginStatus.DashboardRestart, runBlocking { runPluginUpdate(remembered, "1.1.3", memo = RestartMemo(7, 1, completed = true)) {} }.status)
        assertEquals(0, remembered.restarts)
    }

    @Test fun aGatewayThatReportsTheNewPluginNeedsNoRestartEvenWithOneLeftPending() {
        val endpoint = Fake(listOf(versions("1.1.3").copy(gateway = "1.1.3")))
        assertEquals(PluginRow(PluginStatus.Updated, "1.1.3", "1.1.3"), runBlocking { runPluginUpdate(endpoint, "1.1.3", restartOnly = true) {} })
        assertEquals(0, endpoint.restarts)
    }

    @Test fun theRestartAlreadyAskedForTravelsWithTheNextTapAndIsClearedByANewInstall() {
        val memo = RestartMemo(123, 5_000)
        val endpoint = Fake(listOf(versions("1.1.1", "1.1.3")), restartResult = RestartResult.Completed)
        runBlocking { runPluginUpdate(endpoint, "1.1.3", memo = memo) {} }
        assertEquals(listOf(memo), endpoint.memos)
        // New files on disk make the earlier restart history irrelevant.
        val remembered = mutableListOf<RestartMemo>()
        val install = Fake(listOf(versions("1.1.1"), versions("1.1.3")))
        runBlocking { runPluginUpdate(install, "1.1.3", memo = memo, remember = { remembered += it }) {} }
        assertEquals(listOf(RestartMemo()), remembered)
    }

    @Test fun theGatewayVersionDecidesWhichProcessIsOldAndAPluginPastTheReleaseIsNewer() {
        // Gateway still old although the dashboard reloaded: a gateway restart.
        assertEquals(PluginStatus.Restart, pluginStatusFor(versions("1.1.3").copy(gateway = "1.1.1"), "1.1.3"))
        assertEquals(PluginStatus.DashboardRestart, pluginStatusFor(versions("1.1.1", "1.1.3").copy(gateway = "1.1.3"), "1.1.3"))
        assertEquals(PluginStatus.UpToDate, pluginStatusFor(versions("1.1.3").copy(gateway = "1.1.3"), "1.1.3"))
        // The phone's own memory of a finished restart stands in when the gateway is unknown.
        assertEquals(PluginStatus.Restart, pluginStatusFor(versions("1.1.1", "1.1.3"), "1.1.3"))
        assertEquals(PluginStatus.DashboardRestart, pluginStatusFor(versions("1.1.1", "1.1.3"), "1.1.3", restartDone = true))
        // One release is one version: a plugin past the release is never "Up to date".
        assertEquals(PluginStatus.Newer, pluginStatusFor(versions("1.1.4"), "1.1.3"))
        assertEquals(PluginRow(PluginStatus.Newer, "1.2.0", "1.1.3"), pluginRowFor(PluginProbe.Read(versions("1.2.0")), "1.1.3"))
        assertTrue(PluginRow(PluginStatus.Newer).attention)
        assertFalse(PluginRow(PluginStatus.Newer).actionable)
        assertTrue(PluginRow(PluginStatus.DashboardRestart).attention)
        assertFalse(PluginRow(PluginStatus.DashboardRestart).actionable)
    }

    @Test fun theRestartMemoSurvivesStorageAndExpires() {
        val memo = RestartMemo(123, 1_000, completed = true)
        assertEquals(memo, RestartMemo.decode(memo.encode()))
        assertEquals("", RestartMemo().encode())
        assertEquals(RestartMemo(), RestartMemo.decode(""))
        assertEquals(RestartMemo(), RestartMemo.decode("not json"))
        assertTrue(RestartMemo(123, 1_000).inFlight(1_000 + RestartMemo.RESTART_MEMORY_MS))
        assertFalse(RestartMemo(123, 1_000).inFlight(1_001 + RestartMemo.RESTART_MEMORY_MS))
        assertFalse(RestartMemo(123, 1_000, completed = true).inFlight(2_000))
        assertFalse(RestartMemo(123, 5_000).inFlight(1_000))
        assertFalse(RestartMemo(0, 1_000).inFlight(2_000))
    }

    @Test fun hermesCountsAsIdleOnlyWhenServingWithNothingRunning() {
        assertTrue(hermesIdle(GatewayStatus(running = true, busy = false)))
        assertFalse(hermesIdle(GatewayStatus(running = true, busy = true)))
        assertFalse(hermesIdle(GatewayStatus(running = false, busy = false)))
    }

    private fun updateWith(endpoint: Fake, target: String = "1.1.3", pauses: MutableList<Long> = mutableListOf()) =
        runBlocking { runPluginUpdate(endpoint, target) { pauses += it } }

    @Test fun theRowComparesWhatHermesRunsWithWhatTheReleaseShips() {
        assertEquals(PluginStatus.Available, pluginStatusFor(versions("1.1.1"), "1.1.3"))
        assertEquals(PluginStatus.UpToDate, pluginStatusFor(versions("1.1.3"), "1.1.3"))
        assertEquals(PluginStatus.Newer, pluginStatusFor(versions("1.2.0"), "1.1.3"))
        // The files are new but Hermes still runs the old code.
        assertEquals(PluginStatus.Restart, pluginStatusFor(versions("1.1.1", "1.1.3"), "1.1.3"))
        // An odd version is never offered.
        assertEquals(PluginStatus.UpToDate, pluginStatusFor(versions("dev"), "1.1.3"))
    }

    @Test fun noPluginTargetOrNoReadingMeansNoRow() {
        assertEquals(PluginRow(), pluginRowFor(PluginProbe.Read(versions("1.1.1")), ""))
        assertEquals(PluginRow(), pluginRowFor(PluginProbe.None, "1.1.3"))
        assertEquals(PluginRow(), pluginRowFor(PluginProbe.Failed, "1.1.3"))
        assertEquals(PluginRow(PluginStatus.Checking, "", "1.1.3"), pluginRowFor(PluginProbe.Checking, "1.1.3"))
        // An older plugin without the version route is installed, just old.
        assertEquals(PluginRow(PluginStatus.Available, "", "1.1.3"), pluginRowFor(PluginProbe.Unreadable, "1.1.3"))
        assertEquals(PluginRow(PluginStatus.Available, "1.1.1", "1.1.3"), pluginRowFor(PluginProbe.Read(versions("1.1.1")), "1.1.3"))
    }

    @Test fun probingTellsAnOldPluginFromAnotherOwnersHermes() = runBlocking {
        assertEquals(PluginProbe.Read(versions("1.1.1")), probePlugin(Fake(listOf(versions("1.1.1")))))
        val failing = { status: Int -> object : PluginEndpoint {
            override suspend fun version(): PluginVersions = throw ApiFailure(status, "no", plugin = true)
            override suspend fun update(): JSONObject = JSONObject()
        } }
        assertEquals(PluginProbe.None, probePlugin(failing(403)))
        assertEquals(PluginProbe.Unreadable, probePlugin(failing(404)))
        assertEquals(PluginProbe.Failed, probePlugin(failing(500)))
        assertEquals(PluginProbe.Failed, probePlugin(object : PluginEndpoint {
            override suspend fun version(): PluginVersions = throw IOException("offline")
            override suspend fun update(): JSONObject = JSONObject()
        }))
    }

    @Test fun anUpdateWaitsForHermesToReportTheNewVersion() {
        val pauses = mutableListOf<Long>()
        val endpoint = Fake(listOf(versions("1.1.1"), versions("1.1.1"), versions("1.1.3")))
        assertEquals(PluginRow(PluginStatus.Updated, "1.1.3", "1.1.3"), updateWith(endpoint, pauses = pauses))
        assertEquals(listOf(1000L), pauses)
        assertEquals(1, endpoint.updates)
    }

    @Test fun anUnchangedPluginDoesNotWait() {
        val pauses = mutableListOf<Long>()
        val endpoint = Fake(listOf(versions("1.1.3")), { JSONObject().put("ok", true).put("unchanged", true) })
        assertEquals(PluginStatus.Updated, updateWith(endpoint, pauses = pauses).status)
        assertTrue(pauses.isEmpty())
    }

    @Test fun aScanBlockIsAFailedUpdateWithItsOwnLineAndNothingToReview() {
        // Hermes answers a dangerous scan verdict with HTTP 400 whose text starts "Security scan blocked"; --force never overrides it.
        val blocked = ApiFailure(400, "Security scan blocked plugin install: dangerous verdict (2 findings)")
        for (failure in listOf(blocked, ApiFailure(400, "Install failed."))) {
            val steps = StepTracker()
            val row = runBlocking { runPluginUpdate(Fake(listOf(versions("1.1.1")), failure = failure), "1.1.3", steps = steps) {} }
            assertEquals(PluginRow(PluginStatus.Failed, "1.1.1", "1.1.3"), row)
            assertEquals(if (failure === blocked) StepNote.PluginBlocked else StepNote.PluginFailed, steps.rows.failed?.note)
        }
        assertTrue(scanBlocked(blocked)); assertFalse(scanBlocked(ApiFailure(400, "Install failed."))); assertFalse(scanBlocked(ApiFailure(500, "Security scan blocked")))
        assertFalse(scanBlocked(IOException("Security scan blocked")))
    }

    @Test fun anAnswerThatAsksForConsentOrFlagsAScanBlockIsJustNotOk() {
        // Hermes' install route never sends these (only its update route does); if it ever did, the update fails and nothing is offered for review.
        for (answer in listOf(JSONObject().put("ok", false).put("consent_required", true), JSONObject().put("ok", false).put("scan_blocked", true), JSONObject().put("consent_required", true))) {
            assertEquals(PluginRow(PluginStatus.Failed, "1.1.1", "1.1.3"), updateWith(Fake(listOf(versions("1.1.1")), { answer })))
        }
    }

    @Test fun aRestartPendingPluginIsNotUpdatedAgain() {
        val endpoint = Fake(listOf(versions("1.1.1", "1.1.3")))
        assertEquals(PluginRow(PluginStatus.Restart, "1.1.3", "1.1.3"), updateWith(endpoint))
        assertEquals(0, endpoint.updates)
        assertEquals(PluginStatus.Restart, updateWith(Fake(listOf(versions("1.1.1"), versions("1.1.1", "1.1.3")), { JSONObject().put("ok", true).put("restart_required", true) })).status)
    }

    @Test fun everyFailureLandsOnTheRowInsteadOfBlockingTheApp() {
        assertEquals(PluginStatus.Failed, updateWith(Fake(listOf(versions("1.1.1")), { JSONObject().put("ok", false) })).status)
        // Hermes took the update but the files are still old.
        assertEquals(PluginStatus.Failed, updateWith(Fake(listOf(versions("1.1.1")), { JSONObject().put("ok", true).put("unchanged", true) })).status)
        assertEquals(PluginStatus.Failed, updateWith(Fake(listOf(versions("1.1.1")), failure = IOException("offline"))).status)
        listOf(409, 423, 429).forEach { assertEquals(PluginStatus.Busy, updateWith(Fake(listOf(versions("1.1.1")), failure = ApiFailure(it, "busy"))).status) }
        // Not the owner, or no plugin: nothing to show.
        listOf(403, 404).forEach { assertEquals(PluginRow(), updateWith(Fake(listOf(versions("1.1.1")), failure = ApiFailure(it, "no")))) }
    }

    @Test fun aReplyLostAfterTheRequestLeftGoesOnToReadingTheVersionInsteadOfFailing() {
        // A slow install: the phone's read timed out while Hermes carried on, so the new files arrive a little later.
        for (lost in listOf(java.net.SocketTimeoutException("timeout"), java.net.SocketException("Connection reset"), IOException("unexpected end of stream"))) {
            val pauses = mutableListOf<Long>()
            val endpoint = Fake(listOf(versions("1.1.1"), versions("1.1.1"), versions("1.1.3")), failure = lost)
            assertEquals(lost.toString(), PluginRow(PluginStatus.Updated, "1.1.3", "1.1.3"), updateWith(endpoint, pauses = pauses))
            assertEquals("It asked once and then only looked", 1, endpoint.updates)
            assertEquals(listOf(1000L), pauses)
        }
    }

    @Test fun aSlowInstallThatNeverShowsUpStillEndsOnTheRowNotOnAWrongError() {
        val old = Fake(listOf(versions("1.1.1")), failure = java.net.SocketTimeoutException("timeout"))
        val pauses = mutableListOf<Long>()
        assertEquals(PluginRow(PluginStatus.Failed, "1.1.1", "1.1.3"), updateWith(old, pauses = pauses))
        assertTrue("it waited longer than an install that answered", pauses.size > 10)
        // The files arrive but the gateway still runs the old plugin: that is the restart, found from the versions alone.
        val restart = Fake(listOf(versions("1.1.1"), versions("1.1.1", "1.1.3"), versions("1.1.3")), failure = IOException("Connection reset"), restartResult = RestartResult.Completed)
        assertEquals(PluginStatus.Updated, updateWith(restart).status)
        assertEquals(1, restart.restarts)
    }

    @Test fun aRequestThatNeverLeftTheMachineFailsAtOnceWithoutWaiting() {
        for (never in listOf(java.net.UnknownHostException("no host"), java.net.ConnectException("refused"), java.net.SocketTimeoutException("connect timed out"))) {
            val pauses = mutableListOf<Long>()
            assertEquals(never.toString(), PluginStatus.Failed, updateWith(Fake(listOf(versions("1.1.1")), failure = never), pauses = pauses).status)
            assertTrue(pauses.isEmpty())
        }
    }

    @Test fun anOlderPluginWithoutAVersionRouteCanStillBeUpdated() {
        val endpoint = object : PluginEndpoint {
            var reads = 0
            override suspend fun version(): PluginVersions = if (reads++ < 2) throw ApiFailure(404, "unknown route", plugin = true) else versions("1.1.3")
            override suspend fun update(): JSONObject = JSONObject().put("ok", true)
        }
        assertEquals(PluginRow(PluginStatus.Updated, "1.1.3", "1.1.3"), runBlocking { runPluginUpdate(endpoint, "1.1.3") {} })
    }

    @Test fun rowWordsAreShortAndActionsAreOnlyOfferedWhereTheyHelp() {
        PluginStatus.entries.forEach { assertTrue(PluginRow(it).text.length <= 60) }
        assertEquals("Hermes is busy. It restarts when idle.", PluginRow(PluginStatus.Waiting).text)
        assertEquals("Restart the Hermes dashboard on your computer.", PluginRow(PluginStatus.DashboardRestart).text)
        assertEquals("Beacon on Hermes is newer than this app. Update the app.", PluginRow(PluginStatus.Newer).text)
        assertEquals("Beacon is updated. The Hermes dashboard didn't restart.", PluginRow(PluginStatus.DashboardFailed).text)
        assertEquals("Hermes didn't restart", PluginRow(PluginStatus.RestartFailed).text)
        // The button beside a failure says Try again, so the sentence does not.
        PluginStatus.entries.forEach { assertFalse(it.name, PluginRow(it).text.contains("Try again") && it != PluginStatus.Busy) }
        assertTrue(PluginRow(PluginStatus.Waiting).actionable)
        assertTrue(PluginRow(PluginStatus.RestartFailed).actionable)
        assertTrue(PluginRow(PluginStatus.DashboardFailed).actionable)
        assertTrue(PluginRow(PluginStatus.DashboardFailed).restartPending)
        assertTrue(PluginRow(PluginStatus.Waiting).restartPending)
        assertFalse(PluginRow(PluginStatus.Available).restartPending)
        assertTrue(PluginRow(PluginStatus.Available).actionable)
        assertFalse(PluginRow(PluginStatus.UpToDate).actionable)
        assertFalse(PluginRow(PluginStatus.Updated).actionable)
        assertFalse(PluginRow(PluginStatus.Hidden).actionable)
        assertEquals("", PluginRow().text)
    }
}
