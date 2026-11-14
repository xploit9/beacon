package dev.hermes.android.updates

import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.NativeAuthExpiredException
import dev.hermes.android.data.NativeOperationPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class PluginInstallTest {
    private class Fake(
        private val answer: () -> JSONObject = { JSONObject().put("ok", true).put("restart_required", false) },
        private val presence: List<Boolean> = listOf(true),
        private val gateways: List<GatewayStatus?> = listOf(GatewayStatus(running = true, busy = false)),
        private val failure: Exception? = null,
        private val restartFailure: Exception? = null,
        private val enableFailure: Exception? = null,
        private val hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    ) : PluginInstallEndpoint {
        var installs = 0
        var enables = 0
        var restarts = 0
        var asked = 0
        private var polled = 0
        override suspend fun install(): JSONObject {
            installs++
            hold?.await()
            failure?.let { throw it }
            return answer()
        }
        override suspend fun enable(): JSONObject { enables++; enableFailure?.let { throw it }; return JSONObject().put("ok", true) }
        override suspend fun present(): Boolean = presence[minOf(asked++, presence.lastIndex)]
        override suspend fun gateway(): GatewayStatus = gateways[minOf(polled++, gateways.lastIndex)] ?: throw IOException("status unreadable")
        override suspend fun restartGateway() { restarts++; restartFailure?.let { throw it } }
    }
    private fun needsRestart() = JSONObject().put("ok", true).put("restart_required", true)
    private fun install(endpoint: Fake, pauses: MutableList<Long> = mutableListOf()) = runBlocking { runPluginInstall(endpoint) { pauses += it } }
    private fun failed(failure: PluginInstallFailure) = PluginInstallUi(PluginInstallStage.Failed, failure)
    private val done = PluginInstallUi(PluginInstallStage.Done)

    private val commit = "0123456789abcdef0123456789abcdef01234567"

    @Test fun theRequestIsTheFixedSourceReplacedEnabledAndPinnedToTheReleaseCommit() {
        val body = pluginInstallBody(commit)
        assertEquals("xploit9/beacon/hermes-plugin/beacon", body.getString("identifier"))
        assertTrue(body.getBoolean("enable"))
        assertTrue(body.getBoolean("force"))
        assertEquals(commit, body.getString("ref"))
        // Exactly these four keys: never a catalog name, never a branch.
        assertEquals(setOf("identifier", "enable", "force", "ref"), body.keys().asSequence().toSet())
    }

    @Test fun onlyAFullLowercaseCommitCanBePinned() {
        for (bad in listOf("", "main", "v1.1.33", commit.uppercase(), commit.take(39), commit + "0", " $commit", commit + "\u000a"))
            try { pluginInstallBody(bad); fail("Accepted '$bad'") } catch (_: IllegalArgumentException) { }
    }

    @Test fun theInstallRouteTakesThatOneBodyAndNothingBroader() {
        val install = "/api/dashboard/agent-plugins/install"
        assertTrue(NativeOperationPolicy.installBodyAllowed(install, pluginInstallBody(commit)))
        val exact = { pluginInstallBody(commit) }
        val refused = listOf<JSONObject?>(
            null, JSONObject(), exact().put("accept_capabilities", true), exact().put("force", false), exact().put("enable", false), exact().put("ref", commit.uppercase()),
            exact().put("ref", "main"), exact().put("ref", 1), exact().put("identifier", "someone-else/beacon/hermes-plugin/beacon"), exact().put("identifier", "xploit9/beacon"),
            exact().apply { remove("ref") }, exact().apply { remove("force") }, exact().put("catalog_name", "beacon"),
        )
        refused.forEachIndexed { index, body -> assertFalse("body $index: $body", NativeOperationPolicy.installBodyAllowed(install, body)) }
        // Every other route has no body rule of its own.
        assertTrue(NativeOperationPolicy.installBodyAllowed("/api/gateway/restart", JSONObject()))
        assertTrue(NativeOperationPolicy.installBodyAllowed("/api/dashboard/agent-plugins/beacon/enable", null))
    }

    @Test fun theHubIsReadOnlyAndNothingAroundItIsOpen() {
        assertTrue(NativeOperationPolicy.direct("/api/dashboard/plugins/hub", "GET"))
        for (method in listOf("POST", "PUT", "PATCH", "DELETE", "get")) assertFalse(method, NativeOperationPolicy.direct("/api/dashboard/plugins/hub", method))
        for (other in listOf("/api/dashboard/plugins", "/api/dashboard/plugins/rescan", "/api/dashboard/plugins/catalog", "/api/dashboard/plugins/hub/", "/api/dashboard/plugins/hub/x",
                "/api/dashboard/plugins/auto-update", "/api/dashboard/agent-plugins/update-all", "/api/dashboard/agent-plugins/activate"))
            assertFalse("GET $other", NativeOperationPolicy.direct(other, "GET"))
    }

    @Test fun aReleaseThatCannotBeFoundInstallsNothingAndIsNotMistakenForALostReply() {
        for (kind in UpdateException.Kind.entries) {
            val endpoint = Fake(failure = UpdateException(kind, "GitHub"))
            // GitHub's rate limit has its own words; every other way the release cannot be found is "no release".
            assertEquals(failed(if (kind == UpdateException.Kind.Busy) PluginInstallFailure.GitHubBusy else PluginInstallFailure.NoRelease), install(endpoint))
            assertEquals(1, endpoint.installs)
            assertEquals("Beacon is never looked for when no request left", 0, endpoint.asked)
            assertEquals(0, endpoint.enables)
        }
    }

    @Test fun theSourceIsTheOneTheReadmeInstallerUses() {
        val script = listOf("../../hermes-plugin", "../hermes-plugin", "hermes-plugin").map { File(it, "beacon/install/beacon_install.py") }.firstOrNull { it.isFile }
        assumeNotNull(script)
        assertTrue(script!!.readText().contains("INSTALL_SOURCE = \"$BEACON_PLUGIN_SOURCE\""))
    }

    @Test fun onlyAPostReachesTheInstallAndRestartRoutes() {
        for (path in listOf("/api/dashboard/agent-plugins/install", "/api/dashboard/agent-plugins/beacon/enable", "/api/gateway/restart")) {
            assertTrue("POST $path", NativeOperationPolicy.direct(path, "POST"))
            for (method in listOf("GET", "PUT", "PATCH", "DELETE", "post")) assertFalse("$method $path", NativeOperationPolicy.direct(path, method))
            assertFalse("POST $path/", NativeOperationPolicy.direct("$path/", "POST"))
        }
        for (other in listOf("/api/gateway/drain", "/api/gateway/migrate", "/api/dashboard/agent-plugins/beacon", "/api/dashboard/agent-plugins/other/enable",
                "/api/dashboard/agent-plugins/beacon/disable"))
            assertFalse("POST $other", NativeOperationPolicy.direct(other, "POST"))
    }

    @Test fun anInstallWaitsForBeaconToAnswerThenIsDone() {
        val pauses = mutableListOf<Long>()
        val endpoint = Fake(presence = listOf(false, false, true))
        assertEquals(done, install(endpoint, pauses = pauses))
        assertEquals(listOf(1500L, 1500L), pauses)
        assertEquals(1, endpoint.installs)
        assertEquals(0, endpoint.restarts)
    }

    @Test fun beaconThatNeverAnswersEndsNotYetAndTheRetryOnlyAsksAgain() {
        val pauses = mutableListOf<Long>()
        val endpoint = Fake(presence = listOf(false))
        assertEquals(failed(PluginInstallFailure.NotYet), install(endpoint, pauses = pauses))
        assertEquals(8, endpoint.asked)
        assertEquals(List(7) { 1500L }, pauses)
        assertEquals(PluginInstallStep.Check, retryStep(failed(PluginInstallFailure.NotYet)))
        // Installing twice would be refused as already there, so the retry never installs.
        assertEquals(done, runBlocking { runPluginCheck(Fake(presence = listOf(false, true))) { } })
        assertEquals(1, endpoint.installs)
    }

    @Test fun aRestartIsOfferedOnlyWhenHermesFlagsItAndItsGatewayRuns() {
        assertEquals(PluginInstallUi(PluginInstallStage.NeedsRestart, busy = true), install(Fake(answer = ::needsRestart, gateways = listOf(GatewayStatus(running = true, busy = true)))))
        assertEquals(PluginInstallUi(PluginInstallStage.NeedsRestart), install(Fake(answer = ::needsRestart)))
        // A status Hermes will not give is no reason to hide the offer; the owner decides.
        assertEquals(PluginInstallUi(PluginInstallStage.NeedsRestart), install(Fake(answer = ::needsRestart, gateways = listOf(null))))
        // A gateway that is not running loads Beacon when it starts.
        assertEquals(done, install(Fake(answer = ::needsRestart, gateways = listOf(GatewayStatus(running = false, busy = false)))))
        // Hermes' reload reached the gateway: nothing to restart, so nothing is offered.
        assertEquals(done, install(Fake(answer = { JSONObject().put("ok", true).put("gateway_reloaded", true).put("restart_required", false) })))
        // A dependency installed after the gateway booted needs one too.
        val missing = JSONObject().put("ok", true).put("activation", JSONObject().put("missing_dependencies", JSONArray(listOf("httpx"))))
        assertEquals(PluginInstallUi(PluginInstallStage.NeedsRestart), install(Fake(answer = { missing })))
    }

    @Test fun aBeaconAlreadyOnHermesIsReplacedByTheInstallItselfAndARefusalIsHermesAnswer() {
        // The install replaces and turns on a Beacon that is already there, so a 400 is a refusal, never "turn it on instead".
        val refused = Fake(failure = ApiFailure(400, "Plugin was not published"), gateways = listOf(GatewayStatus(running = false, busy = false)))
        assertEquals(failed(PluginInstallFailure.Error), install(refused))
        assertEquals(1, refused.installs)
        assertEquals(0, refused.enables)
        assertEquals(0, refused.asked)
        // An answer that is not ok is an install failure.
        assertEquals(failed(PluginInstallFailure.Error), install(Fake(answer = { JSONObject().put("ok", false) })))
    }

    @Test fun everyFailureIsNamedForTheOwner() {
        val table = mapOf(
            ApiFailure(401, "no") to PluginInstallFailure.SignIn,
            ApiFailure(403, "no") to PluginInstallFailure.Denied,
            ApiFailure(404, "no") to PluginInstallFailure.Unsupported,
            ApiFailure(405, "no") to PluginInstallFailure.Unsupported,
            ApiFailure(409, "no") to PluginInstallFailure.Busy,
            ApiFailure(429, "no") to PluginInstallFailure.Busy,
            ApiFailure(500, "no") to PluginInstallFailure.Error,
        )
        table.forEach { (error, failure) ->
            val endpoint = Fake(failure = error)
            assertEquals("${error.status}", failed(failure), install(endpoint))
            assertEquals("Beacon is never looked for after a refusal", 0, endpoint.asked)
        }
        assertEquals(failed(PluginInstallFailure.SignIn), install(Fake(failure = NativeAuthExpiredException())))
        assertEquals(failed(PluginInstallFailure.Offline), install(Fake(failure = UnknownHostException("hermes"))))
        assertEquals(failed(PluginInstallFailure.Offline), install(Fake(failure = ConnectException("refused"))))
        assertEquals(failed(PluginInstallFailure.Error), install(Fake(failure = IllegalStateException("odd"))))
    }

    @Test fun aLostReplyIsCheckedBeforeItIsCalledAFailure() {
        // The request reached Hermes and the answer never came back: Beacon may be installed already, and whether its gateway
        // reloaded is unknown, so a running gateway gets the offer and a stopped one loads Beacon when it starts.
        val installed = Fake(failure = SocketTimeoutException("Read timed out"), presence = listOf(true))
        assertEquals(PluginInstallUi(PluginInstallStage.NeedsRestart), install(installed))
        assertEquals(1, installed.installs)
        assertEquals(done, install(Fake(failure = SocketTimeoutException("Read timed out"), gateways = listOf(GatewayStatus(running = false, busy = false)))))
        val missing = Fake(failure = SocketTimeoutException("Read timed out"), presence = listOf(false))
        assertEquals(failed(PluginInstallFailure.NotYet).copy(restartPending = true), install(missing))
    }

    @Test fun installThenRestartThenAvailable() {
        val pauses = mutableListOf<Long>()
        val endpoint = Fake(answer = ::needsRestart, gateways = listOf(GatewayStatus(running = true, busy = true), GatewayStatus(running = false, busy = false), GatewayStatus(running = true, busy = false)))
        val offer = install(endpoint)
        assertEquals(PluginInstallUi(PluginInstallStage.NeedsRestart, busy = true), offer)
        assertEquals("Nothing restarts until the owner agrees", 0, endpoint.restarts)
        assertEquals(PluginInstallStep.Restart, retryStep(offer))
        val ready = runBlocking { runPluginRestart(endpoint, offer) { pauses += it } }
        assertEquals(done, ready)
        assertEquals(1, endpoint.restarts)
        // Settle first, so the old gateway's last answer is not read as the new one, then wait for it to serve again.
        assertEquals(listOf(3000L, 2000L), pauses)
    }

    @Test fun aRefusedRestartKeepsTheOfferAndWhatItWarnedAbout() {
        val pauses = mutableListOf<Long>()
        val offer = PluginInstallUi(PluginInstallStage.NeedsRestart, busy = true)
        val endpoint = Fake(restartFailure = ApiFailure(403, "no"))
        assertEquals(offer.copy(failure = PluginInstallFailure.Denied), runBlocking { runPluginRestart(endpoint, offer) { pauses += it } })
        assertEquals(emptyList<Long>(), pauses)
        assertEquals(PluginInstallStep.Restart, retryStep(offer.copy(failure = PluginInstallFailure.Denied)))
    }

    @Test fun aGatewayThatDoesNotComeBackEndsGatewayDownAndTheRetryOnlyWaits() {
        val pauses = mutableListOf<Long>()
        val endpoint = Fake(gateways = listOf(GatewayStatus(running = false, busy = false)))
        val offer = PluginInstallUi(PluginInstallStage.NeedsRestart)
        assertEquals(failed(PluginInstallFailure.GatewayDown), runBlocking { runPluginRestart(endpoint, offer) { pauses += it } })
        assertEquals(listOf(3000L) + List(14) { 2000L }, pauses)
        assertEquals(1, endpoint.restarts)
        assertEquals(PluginInstallStep.Gateway, retryStep(failed(PluginInstallFailure.GatewayDown)))
        val later = Fake(gateways = listOf(GatewayStatus(running = false, busy = false), GatewayStatus(running = true, busy = false)))
        val waits = mutableListOf<Long>()
        assertEquals(done, runBlocking { runGatewayCheck(later) { waits += it } })
        assertEquals("The restart was already started: no settle pause, no second restart", listOf(2000L), waits)
        assertEquals(0, later.restarts)
    }

    @Test fun retryRunsTheStepThatStopped() {
        assertEquals(PluginInstallStep.Install, retryStep(failed(PluginInstallFailure.Offline)))
        assertEquals(PluginInstallStep.Install, retryStep(failed(PluginInstallFailure.Busy)))
        assertEquals(PluginInstallStep.Install, retryStep(failed(PluginInstallFailure.Error)))
        assertEquals(PluginInstallStep.Restart, retryStep(PluginInstallUi(PluginInstallStage.NeedsRestart, PluginInstallFailure.Offline)))
    }

    @Test fun theWorkingStagesAreTheOnesWithASpinner() {
        val working = PluginInstallStage.entries.filter { PluginInstallUi(it).working }
        assertEquals(listOf(PluginInstallStage.Installing, PluginInstallStage.Checking, PluginInstallStage.Restarting), working)
    }

    @Test fun theGatewayIsReadFromHermesPublicStatus() {
        fun status(vararg pairs: Pair<String, Any>) = gatewayStatusOf(JSONObject().apply { pairs.forEach { (key, value) -> put(key, value) } })
        assertEquals(GatewayStatus(running = true, busy = true), status("gateway_running" to true, "gateway_state" to "running", "gateway_busy" to true))
        assertEquals(GatewayStatus(running = true, busy = false), status("gateway_running" to true, "gateway_state" to "degraded", "gateway_busy" to false))
        // Draining, starting, stopped and a dead process all mean it is not serving.
        for (state in listOf("draining", "starting", "stopped", "startup_failed", ""))
            assertFalse(state, status("gateway_running" to true, "gateway_state" to state).running)
        assertFalse(status("gateway_running" to false, "gateway_state" to "running").running)
        // An older Hermes has no gateway_busy: active_agents says it.
        assertTrue(status("gateway_state" to "running", "active_agents" to 2).busy)
        assertFalse(status("gateway_state" to "running", "active_agents" to 0).busy)
        assertTrue(status("gateway_state" to "running").running)
    }

    @Test fun anAccountThatIsNotSignedInStartsNothing() {
        var finished = 0
        var opened = 0
        val controller = PluginInstallController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), { null }, { finished++ }, { opened++ })
        controller.install(); controller.restart(); controller.retry()
        assertEquals(PluginInstallUi(), controller.state.value)
        controller.dismiss()
        assertEquals("Nothing was offered, so there is nothing to read again", 0, finished)
        controller.openUpdates()
        assertEquals(1, opened)
    }

    private fun signedIn() = dev.hermes.android.data.BeaconClient(dev.hermes.android.data.AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, persistContext = false)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun slowPresenceRetryRetainsTheRestartOfferWithoutReinstalling() = runTest {
        val endpoint = Fake(answer = ::needsRestart, presence = List(8) { false } + true,
            gateways = listOf(GatewayStatus(running = true, busy = true)))
        var finished = 0
        val controller = PluginInstallController(this, ::signedIn, { finished++ }, {}, { endpoint })
        controller.install(); advanceUntilIdle()
        assertEquals(PluginInstallFailure.NotYet, controller.state.value.failure)
        assertEquals(0, finished)
        controller.retry(); advanceUntilIdle()
        assertEquals(PluginInstallUi(PluginInstallStage.NeedsRestart, busy = true), controller.state.value)
        assertEquals(0, finished)
        assertEquals(1, endpoint.installs)
        assertEquals(0, endpoint.restarts)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun slowPresenceRetryWithoutRestartRequirementFinishes() = runTest {
        val endpoint = Fake(presence = List(8) { false } + true)
        var finished = 0
        val controller = PluginInstallController(this, ::signedIn, { finished++ }, {}, { endpoint })
        controller.install(); advanceUntilIdle()
        assertEquals(PluginInstallFailure.NotYet, controller.state.value.failure)
        assertEquals(0, finished)
        controller.retry(); advanceUntilIdle()
        assertEquals(done, controller.state.value)
        assertEquals(1, finished)
        assertEquals(1, endpoint.installs)
        assertEquals(0, endpoint.restarts)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun unknownRestartDecisionSurvivesRepeatedRetriesAndResetClearsIt() = runTest {
        val endpoint = Fake(answer = { JSONObject().put("ok", true) }, presence = List(16) { false } + true,
            gateways = listOf(null))
        var finished = 0
        val controller = PluginInstallController(this, ::signedIn, { finished++ }, {}, { endpoint })
        controller.install(); advanceUntilIdle()
        assertEquals(failed(PluginInstallFailure.NotYet).copy(restartPending = true), controller.state.value)
        controller.retry(); advanceUntilIdle()
        assertEquals(failed(PluginInstallFailure.NotYet).copy(restartPending = true), controller.state.value)
        controller.retry(); advanceUntilIdle()
        assertEquals(PluginInstallUi(PluginInstallStage.NeedsRestart), controller.state.value)
        assertEquals(0, finished)
        assertEquals(1, endpoint.installs)
        assertEquals(0, endpoint.restarts)
        controller.reset()
        assertEquals(PluginInstallUi(), controller.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun slowPresenceWithGatewayStoppedCompletesWithoutRestart() = runTest {
        val endpoint = Fake(answer = ::needsRestart, presence = List(8) { false } + true,
            gateways = listOf(GatewayStatus(running = false, busy = false)))
        var finished = 0
        val controller = PluginInstallController(this, ::signedIn, { finished++ }, {}, { endpoint })
        controller.install(); advanceUntilIdle()
        controller.retry(); advanceUntilIdle()
        assertEquals(done, controller.state.value)
        assertEquals(1, finished)
        assertEquals(1, endpoint.installs)
        assertEquals(0, endpoint.restarts)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun slowPresenceAfterALostReplyStillOffersRestart() = runTest {
        for (failure in listOf(SocketTimeoutException("Read timed out"))) {
            val endpoint = Fake(failure = failure, presence = List(8) { false } + true)
            var finished = 0
            val controller = PluginInstallController(this, ::signedIn, { finished++ }, {}, { endpoint })
            controller.install(); advanceUntilIdle()
            assertEquals(failed(PluginInstallFailure.NotYet).copy(restartPending = true), controller.state.value)
            controller.retry(); advanceUntilIdle()
            assertEquals(PluginInstallUi(PluginInstallStage.NeedsRestart), controller.state.value)
            assertEquals(0, finished)
            assertEquals(1, endpoint.installs)
            assertEquals(0, endpoint.enables)
            assertEquals(0, endpoint.restarts)
        }
    }

    @Test fun aSecondTapWhileAnInstallRunsStartsNothing() {
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        val endpoint = Fake(hold = hold)
        var finished = 0
        val controller = PluginInstallController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), ::signedIn, { finished++ }, {}, { endpoint })
        controller.install(); controller.install(); controller.retry()
        assertEquals(PluginInstallStage.Installing, controller.state.value.stage)
        hold.complete(Unit)
        assertEquals(1, endpoint.installs)
        assertEquals(done, controller.state.value)
        assertEquals("Done reads the plugin again exactly once", 1, finished)
    }

    @Test fun anAccountChangeMidInstallDropsItsResult() {
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        var finished = 0
        val controller = PluginInstallController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), ::signedIn, { finished++ }, {}, { Fake(hold = hold) })
        controller.install()
        controller.reset()
        hold.complete(Unit)
        assertEquals(PluginInstallUi(), controller.state.value)
        assertEquals(0, finished)
    }

    @Test fun anAccountChangeMidInstallGivesTheSharedSlotBackForTheNextChange() {
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        val controller = PluginInstallController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), ::signedIn, {}, {}, { Fake(hold = hold) })
        controller.install()
        assertTrue("an install holds the one plugin change", PluginChange.active)
        controller.reset()
        assertFalse("walking away does not lock the plugin for the repair and the update", PluginChange.active)
        hold.complete(Unit)
        assertFalse(PluginChange.active)
    }

    @Test fun anInstallThatCannotStartBecauseAnotherChangeRunsSaysBusyAndTouchesNothing() {
        val endpoint = Fake()
        val controller = PluginInstallController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), ::signedIn, {}, {}, { endpoint })
        assertTrue(PluginChange.begin())
        try {
            controller.install()
            assertEquals(failed(PluginInstallFailure.Busy), controller.state.value)
            assertEquals(0, endpoint.installs + endpoint.enables + endpoint.restarts + endpoint.asked)
        } finally { PluginChange.finish() }
        // Once the other change is over, "Try again" goes through.
        controller.retry()
        assertEquals(done, controller.state.value)
        assertFalse(PluginChange.active)
    }

    @Test fun aLostReplyIsOneRuleForTheInstallTheRepairAndTheUpdate() {
        for (lost in listOf(SocketTimeoutException("Read timed out"), java.net.SocketException("Connection reset"), IOException("unexpected end of stream"), java.io.EOFException()))
            assertTrue(lost.toString(), lostReply(lost))
        // The request never left, the phone said no, or Hermes answered: nothing may have been installed, so none of these is "lost".
        for (settled in listOf<Exception>(ConnectException("refused"), UnknownHostException("no host"), SocketTimeoutException("connect timed out"), ApiFailure(500, "boom"),
                NativeAuthExpiredException(), UpdateException(UpdateException.Kind.Network, "offline"), IllegalStateException("x")))
            assertFalse(settled.toString(), lostReply(settled))
    }

    @Test fun aScanBlockAndGitHubBeingBusyEachHaveTheirOwnFailure() {
        val blocked = ApiFailure(400, "Security scan blocked plugin install: dangerous verdict (2 findings).")
        assertTrue(scanBlocked(blocked)); assertTrue(scanBlocked(ApiFailure(400, "  security scan blocked plugin install")))
        assertFalse(scanBlocked(ApiFailure(400, "Install failed."))); assertFalse(scanBlocked(ApiFailure(403, "Security scan blocked"))); assertFalse(scanBlocked(IOException("Security scan blocked")))
        assertEquals(failed(PluginInstallFailure.Blocked), install(Fake(failure = blocked)))
        assertEquals(failed(PluginInstallFailure.Error), install(Fake(failure = ApiFailure(400, "Install failed."))))
        assertEquals(failed(PluginInstallFailure.GitHubBusy), install(Fake(failure = UpdateException(UpdateException.Kind.Busy, "GitHub answered 429"))))
        assertEquals(failed(PluginInstallFailure.NoRelease), install(Fake(failure = UpdateException(UpdateException.Kind.Network, "offline"))))
    }

    @Test fun notNowAtTheOfferOrAfterALateGatewayReadsBeaconAgainOnce() {
        for (endpoint in listOf(Fake(answer = ::needsRestart), Fake(answer = ::needsRestart, gateways = listOf(GatewayStatus(running = true, busy = false), GatewayStatus(running = false, busy = false))))) {
            var finished = 0
            val controller = PluginInstallController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), ::signedIn, { finished++ }, {}, { endpoint })
            controller.install()
            assertEquals(PluginInstallStage.NeedsRestart, controller.state.value.stage)
            controller.dismiss()
            assertEquals(1, finished)
            assertEquals(PluginInstallUi(), controller.state.value)
        }
        // A plain failure has nothing to read again.
        var finished = 0
        val controller = PluginInstallController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), ::signedIn, { finished++ }, {}, { Fake(failure = ApiFailure(403, "no")) })
        controller.install(); controller.dismiss()
        assertEquals(0, finished)
    }
}
