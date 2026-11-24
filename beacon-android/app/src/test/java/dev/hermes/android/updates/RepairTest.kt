package dev.hermes.android.updates

import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.AuthContext
import dev.hermes.android.core.Release
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.ui.normalized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/** Repair Beacon and the pinned install behind it: what it finds, what it does, and what it will not do. */
class RepairTest {
    private val commit = "0123456789abcdef0123456789abcdef01234567"
    private fun versions(running: String = "1.1.33", installed: String = "1.1.33", gateway: String = "1.1.33") = PluginVersions(running, installed, gateway)
    private val current = versions()
    private val stale = versions(running = "1.1.30")
    private fun failed(problem: RepairProblem, finding: RepairFinding? = null) = RepairUi(RepairStage.Failed, problem, finding)

    // ---- what the repair finds

    @Test fun hermesRowAndBeaconsOwnAnswerDecideWhatIsWrong() {
        val on = HubPlugin("1.1.33", true)
        assertEquals(RepairFinding.NotInstalled, repairFinding(null, null, "1.1.33"))
        assertEquals(RepairFinding.NotInstalled, repairFinding(null, current, "1.1.33"))
        assertEquals(RepairFinding.TurnedOff, repairFinding(HubPlugin("1.1.33", false), null, "1.1.33"))
        assertEquals(RepairFinding.NotRunning, repairFinding(on, null, "1.1.33"))
        assertEquals(RepairFinding.OutOfDate, repairFinding(on, versions("1.1.30", "1.1.30", "1.1.30"), "1.1.33"))
        assertEquals(RepairFinding.StillOld, repairFinding(on, stale, "1.1.33"))
        assertEquals(RepairFinding.StillOld, repairFinding(on, versions(gateway = "1.1.30"), "1.1.33"))
        assertEquals(RepairFinding.Healthy, repairFinding(on, current, "1.1.33"))
        // A plugin past the release is a newer build, not a fault, and installing the release would take it back: it is left alone.
        assertEquals(RepairFinding.Newer, repairFinding(on, versions("1.1.34", "1.1.34", "1.1.34"), "1.1.33"))
        assertEquals(RepairFinding.Newer, repairFinding(HubPlugin("1.2.0", true), null, "1.1.33"))
        assertEquals("Off is only turned on, which cannot take it back", RepairFinding.TurnedOff, repairFinding(HubPlugin("1.2.0", false), null, "1.1.33"))
        assertEquals("The same version is not newer", RepairFinding.Healthy, repairFinding(on, current, "1.1.33"))
        assertEquals("An odd version is never newer", RepairFinding.NotRunning, repairFinding(HubPlugin("dev", true), null, "1.1.33"))
        // An unknown release hides "out of date" and "newer" alike.
        assertEquals(RepairFinding.Healthy, repairFinding(on, versions("1.1.34", "1.1.34", "1.1.34"), ""))
        assertEquals(RepairFinding.Healthy, repairFinding(on, versions("1.1.30", "1.1.30", "1.1.30"), ""))
    }

    @Test fun onlyTheBeaconRowOfTheHubCounts() {
        val hub = JSONObject().put("plugins", JSONArray()
            .put(JSONObject().put("name", "kanban").put("version", "9").put("runtime_status", "enabled"))
            .put(JSONObject().put("name", "beacon").put("version", "1.1.33").put("runtime_status", "enabled")))
        assertEquals(HubPlugin("1.1.33", true), hubPluginOf(hub))
        for (status in listOf("disabled", "inactive", "", "Enabled")) {
            val off = JSONObject().put("plugins", JSONArray().put(JSONObject().put("name", "beacon").put("version", "1.1.30").put("runtime_status", status)))
            assertEquals(status, HubPlugin("1.1.30", false), hubPluginOf(off))
        }
        assertNull(hubPluginOf(JSONObject().put("plugins", JSONArray().put(JSONObject().put("name", "kanban")))))
        assertNull(hubPluginOf(JSONObject()))
        assertNull(hubPluginOf(JSONObject().put("plugins", "none")))
    }

    // ---- the repair itself, against a scripted Hermes

    private class Hermes(
        var publishedOf: () -> PublishedCommit = { PublishedCommit("1.1.33", "0123456789abcdef0123456789abcdef01234567") },
        var hubOf: () -> HubPlugin? = { HubPlugin("1.1.33", true) },
        versionScript: List<PluginVersions?> = listOf(PluginVersions("1.1.33", "1.1.33", "1.1.33")),
        var installOf: () -> JSONObject = { JSONObject().put("ok", true) },
        var enableOf: () -> JSONObject = { JSONObject().put("ok", true) },
        var presence: Boolean = true,
        startScript: List<Long?> = emptyList(),
    ) : RepairEndpoint {
        private val starts = startScript.toMutableList()
        override suspend fun dashboardStart(): Long? { calls += "start"; return if (starts.size > 1) starts.removeAt(0) else starts.firstOrNull() }
        val calls = mutableListOf<String>()
        private val script = versionScript.toMutableList()
        override suspend fun published(): PublishedCommit { calls += "published"; return publishedOf() }
        override suspend fun hub(): HubPlugin? { calls += "hub"; return hubOf() }
        override suspend fun versions(): PluginVersions? { calls += "versions"; return if (script.size > 1) script.removeAt(0) else script[0] }
        override suspend fun install(): JSONObject { calls += "install"; return installOf() }
        override suspend fun enable(): JSONObject { calls += "enable"; return enableOf() }
        override suspend fun present(): Boolean { calls += "present"; return presence }
        override suspend fun gateway(): GatewayStatus { calls += "gateway"; return GatewayStatus(running = true, busy = false) }
        override suspend fun restartGateway() { calls += "restart" }
        fun count(name: String) = calls.count { it == name }
    }

    private fun repair(hermes: Hermes, callActive: Boolean = false, stages: MutableList<RepairStage> = mutableListOf()) =
        runBlocking { runRepair(hermes, { callActive }, { stages += it }, pause = {}) }

    /** The demo proof showed no restart is needed, so none is ever asked for: not the gateway, not through the phone. */
    private fun assertNothingRestarted(hermes: Hermes) {
        assertEquals(hermes.calls.toString(), 0, hermes.count("restart") + hermes.count("gateway"))
    }

    @Test fun aMissingBeaconIsInstalledPinnedAndTheRepairThenWaitsForItToRunCurrent() {
        val hermes = Hermes(hubOf = { null }, versionScript = listOf(null, current))
        val stages = mutableListOf<RepairStage>()
        assertEquals(RepairUi(RepairStage.Done, finding = RepairFinding.NotInstalled), repair(hermes, stages = stages))
        assertEquals(listOf(RepairStage.Checking, RepairStage.Repairing, RepairStage.Waiting), stages)
        assertEquals(1, hermes.count("install")); assertEquals(0, hermes.count("enable"))
        assertEquals("The release is read before anything changes", "published", hermes.calls.first())
        assertNothingRestarted(hermes)
    }

    @Test fun repairIsDoneOnlyAfterTheDashboardHasRestarted() {
        // The same version text before and after: only the dashboard's start time says the restart happened.
        val hermes = Hermes(versionScript = listOf(current), startScript = listOf(100L, 100L, 100L, 100L, 250L))
        assertEquals(RepairUi(RepairStage.Done, finding = RepairFinding.Healthy), repair(hermes))
        assertEquals("one read before the install, then one per look until it changed", 5, hermes.count("start"))
        // A dashboard that never restarts (same files, nothing to load) is not held for ever, and says nothing false.
        val still = Hermes(versionScript = listOf(current), startScript = listOf(100L))
        assertEquals(RepairUi(RepairStage.Done, finding = RepairFinding.Healthy), repair(still))
        // Files that did not change need no restart; a start time it cannot read changes nothing.
        val same = Hermes(versionScript = listOf(current), installOf = { JSONObject().put("ok", true).put("unchanged", true) }, startScript = listOf(100L))
        assertEquals(RepairUi(RepairStage.Done, finding = RepairFinding.Healthy), repair(same)); assertEquals(1, same.count("start"))
        val unknown = Hermes(versionScript = listOf(current))
        assertEquals(RepairUi(RepairStage.Done, finding = RepairFinding.Healthy), repair(unknown))
    }

    @Test fun aBeaconThatIsOffIsTurnedOnNotReinstalled() {
        val hermes = Hermes(hubOf = { HubPlugin("1.1.33", false) }, versionScript = listOf(null, current))
        assertEquals(RepairUi(RepairStage.Done, finding = RepairFinding.TurnedOff), repair(hermes))
        assertEquals(1, hermes.count("enable")); assertEquals(0, hermes.count("install"))
        assertNothingRestarted(hermes)
        assertEquals(failed(RepairProblem.Refused, RepairFinding.TurnedOff), repair(Hermes(hubOf = { HubPlugin("1.1.33", false) }, versionScript = listOf(null), enableOf = { JSONObject().put("ok", false) })))
    }

    @Test fun aBeaconThatIsNotRunningOutOfDateOrStillOldIsReinstalled() {
        val outOfDate = versions("1.1.30", "1.1.30", "1.1.30")
        for ((finding, script) in listOf(RepairFinding.NotRunning to listOf(null, current), RepairFinding.OutOfDate to listOf(outOfDate, current),
                RepairFinding.StillOld to listOf(stale, current), RepairFinding.Healthy to listOf(current, current))) {
            val hermes = Hermes(hubOf = { HubPlugin("1.1.30", true) }, versionScript = script)
            assertEquals(finding.name, RepairUi(RepairStage.Done, finding = finding), repair(hermes))
            assertEquals(finding.name, 1, hermes.count("install"))
            assertNothingRestarted(hermes)
        }
    }

    @Test fun theDashboardRestartsItselfAboutAMinuteLaterAndTheRepairJustKeepsAsking() {
        // As measured on the demo: files and gateway are the release at once, the dashboard answers old code for a while, is down, then current.
        val oldDashboard = versions(running = "1.1.30")
        val hermes = Hermes(hubOf = { null }, versionScript = listOf(null, oldDashboard, oldDashboard, null, null, current))
        val stages = mutableListOf<RepairStage>()
        assertEquals(RepairUi(RepairStage.Done, finding = RepairFinding.NotInstalled), repair(hermes, stages = stages))
        assertEquals(1, hermes.count("install"))
        assertTrue("it asked again while the dashboard was down", hermes.count("versions") >= 6)
        assertNothingRestarted(hermes)
    }

    @Test fun aCallStopsItBeforeAnythingIsReadOrChanged() {
        val hermes = Hermes(hubOf = { null }, versionScript = listOf(null, current))
        assertEquals(failed(RepairProblem.Call), repair(hermes, callActive = true))
        assertEquals("Nothing was asked of Hermes", emptyList<String>(), hermes.calls)
    }

    @Test fun aDashboardThatStaysOldEndsInTheOnlyThingThePhoneCannotDo() {
        val hermes = Hermes(versionScript = listOf(stale))
        assertEquals(failed(RepairProblem.DashboardManual, RepairFinding.StillOld), repair(hermes))
        assertEquals(1, hermes.count("install"))
        assertTrue("it keeps asking until the wait is over", hermes.count("versions") > 30)
        assertNothingRestarted(hermes)
        assertEquals("Restart the Hermes dashboard on your computer.", RepairProblem.DashboardManual.text)
    }

    @Test fun aGatewayThatDidNotTakeBeaconUpIsLeftToTheUpdateScreenWhichRestartsWithItsOwnChecks() {
        val gatewayOld = versions(gateway = "1.1.30")
        val hermes = Hermes(hubOf = { null }, versionScript = listOf(null, gatewayOld), installOf = { JSONObject().put("ok", true).put("restart_required", true) })
        assertEquals(failed(RepairProblem.RestartNeeded, RepairFinding.NotInstalled), repair(hermes))
        assertNothingRestarted(hermes)
        assertEquals("Restart Hermes to finish", RepairProblem.RestartNeeded.text)
        // The same answer from a Beacon that stopped answering: still a restart, still not the phone's to do.
        val silent = Hermes(hubOf = { null }, versionScript = listOf(null), installOf = { JSONObject().put("ok", true).put("restart_required", true) })
        assertEquals(failed(RepairProblem.RestartNeeded, RepairFinding.NotInstalled), repair(silent))
        assertNothingRestarted(silent)
    }

    @Test fun anInstallThatLeavesTheOlderFilesOnDiskIsRefusedNotWaitedOnForever() {
        val old = versions("1.1.30", "1.1.30", "1.1.30")
        val hermes = Hermes(hubOf = { HubPlugin("1.1.30", true) }, versionScript = listOf(old))
        assertEquals(failed(RepairProblem.Refused, RepairFinding.OutOfDate), repair(hermes))
        assertNothingRestarted(hermes)
    }

    @Test fun everyWayItCanStopIsNamedAndNothingElseRuns() {
        val noRelease = Hermes(publishedOf = { throw UpdateException(UpdateException.Kind.Verification, "no tag") })
        assertEquals(failed(RepairProblem.NoRelease), repair(noRelease))
        assertEquals("Nothing is read or changed without a release", listOf("published"), noRelease.calls)
        assertEquals(failed(RepairProblem.NoRelease), repair(Hermes(publishedOf = { throw UpdateException(UpdateException.Kind.Network, "offline") })))
        assertEquals(failed(RepairProblem.Offline), repair(Hermes(hubOf = { throw IOException("down") })))
        assertEquals(failed(RepairProblem.SignIn), repair(Hermes(hubOf = { throw ApiFailure(401, "no") })))
        assertEquals(failed(RepairProblem.Denied), repair(Hermes(hubOf = { throw ApiFailure(403, "no") })))
        assertEquals(failed(RepairProblem.Unsupported), repair(Hermes(hubOf = { throw ApiFailure(404, "no") })))
        val refused = Hermes(hubOf = { null }, versionScript = listOf(null), installOf = { JSONObject().put("ok", false) })
        assertEquals(failed(RepairProblem.Refused, RepairFinding.NotInstalled), repair(refused))
        assertEquals(failed(RepairProblem.Refused), repair(Hermes(hubOf = { null }, versionScript = listOf(null), installOf = { throw ApiFailure(400, "scan") })))
        assertEquals(failed(RepairProblem.Busy), repair(Hermes(hubOf = { null }, versionScript = listOf(null), installOf = { throw ApiFailure(409, "busy") })))
        val silent = Hermes(hubOf = { null }, versionScript = listOf(null), presence = false)
        assertEquals(failed(RepairProblem.NotAnswering, RepairFinding.NotInstalled), repair(silent))
        assertEquals(8, silent.count("present"))
        assertNothingRestarted(silent)
    }

    // ---- a slow install is not a lost one

    @Test fun aReplyLostAfterTheRequestLeftIsLookedAtNotCalledOffline() {
        // The phone's read timed out while Hermes kept installing: the versions say how it ended.
        val old = versions("1.1.30", "1.1.30", "1.1.30")
        for (lost in listOf(java.net.SocketTimeoutException("timeout"), java.net.SocketException("Connection reset"), IOException("unexpected end of stream"))) {
            val hermes = Hermes(hubOf = { HubPlugin("1.1.30", true) }, versionScript = listOf(old, old, current), installOf = { throw lost })
            assertEquals(lost.toString(), RepairUi(RepairStage.Done, finding = RepairFinding.OutOfDate), repair(hermes))
            assertEquals("One install, not a second one on top", 1, hermes.count("install"))
            assertNothingRestarted(hermes)
        }
    }

    @Test fun aSlowInstallThatNeverShowsUpEndsOnWhatHermesReportsNotOnOffline() {
        val old = versions("1.1.30", "1.1.30", "1.1.30")
        val hermes = Hermes(hubOf = { HubPlugin("1.1.30", true) }, versionScript = listOf(old), installOf = { throw java.net.SocketTimeoutException("timeout") })
        assertEquals(failed(RepairProblem.Refused, RepairFinding.OutOfDate), repair(hermes))
        assertTrue("it kept looking for the new files", hermes.count("versions") > 30)
    }

    @Test fun aRequestThatNeverLeftTheMachineIsStillCantReachHermes() {
        val never = listOf(java.net.UnknownHostException("no host"), java.net.ConnectException("refused"), java.net.SocketTimeoutException("connect timed out"))
        for (error in never) {
            val hermes = Hermes(hubOf = { null }, versionScript = listOf(null), installOf = { throw error })
            assertEquals(error.toString(), failed(RepairProblem.Offline), repair(hermes))
            assertEquals(0, hermes.count("present"))
        }
    }

    // ---- a plugin newer than the release is never taken back to the release

    @Test fun aBeaconNewerThanTheLatestReleaseIsLeftAloneNotDowngraded() {
        val newer = versions("1.2.0", "1.2.0", "1.2.0")
        val hermes = Hermes(hubOf = { HubPlugin("1.2.0", true) }, versionScript = listOf(newer))
        val result = repair(hermes)
        assertEquals(RepairStage.Done, result.stage)
        assertEquals("Nothing is installed over it", 0, hermes.count("install"))
        assertEquals(0, hermes.count("enable"))
        assertNothingRestarted(hermes)
        assertEquals(0, hermes.count("present"))
    }

    @Test fun aNewerBeaconThatDoesNotAnswerIsStillNotReinstalledAsTheOlderRelease() {
        val hermes = Hermes(hubOf = { HubPlugin("1.2.0", true) }, versionScript = listOf(null))
        assertEquals(RepairStage.Done, repair(hermes).stage)
        assertEquals(0, hermes.count("install"))
    }

    @Test fun aNewerBeaconThatIsTurnedOffMayStillBeTurnedOn() {
        val hermes = Hermes(hubOf = { HubPlugin("1.2.0", false) }, versionScript = listOf(null, versions("1.2.0", "1.2.0", "1.2.0")))
        assertEquals(RepairUi(RepairStage.Done, finding = RepairFinding.TurnedOff), repair(hermes))
        assertEquals(1, hermes.count("enable")); assertEquals(0, hermes.count("install"))
    }

    @Test fun aCancelledRepairIsNotReportedAsAFailure() {
        val hermes = Hermes(hubOf = { throw kotlinx.coroutines.CancellationException("closed") })
        try { repair(hermes); fail("swallowed the cancellation") } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    // ---- the controller: never automatic, one at a time

    private fun signedIn() = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, persistContext = false)

    @Test fun oneRepairAtATimeAndTheAccountChangeDropsIt() {
        val hold = CompletableDeferred<Unit>()
        val hermes = Hermes(hubOf = { null }, versionScript = listOf(null, current), installOf = { runBlocking { hold.await() }; JSONObject().put("ok", true) })
        val finished = java.util.concurrent.atomic.AtomicInteger()
        val scope = CoroutineScope(Dispatchers.Default)
        val controller = PluginRepairController(scope, ::signedIn, { false }, { finished.incrementAndGet() }, { hermes })
        controller.repair(); controller.repair(); controller.repair()
        assertTrue(controller.state.value.working)
        hold.complete(Unit)
        val deadline = System.currentTimeMillis() + 5000
        while ((controller.state.value.stage != RepairStage.Done || finished.get() == 0) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(RepairStage.Done, controller.state.value.stage)
        assertEquals(1, hermes.count("install")); assertEquals(1, finished.get())
        controller.reset()
        assertEquals(RepairUi(), controller.state.value)
    }

    @Test fun oneChangeToThePluginAtATimeAcrossRepairAndTheMissingPluginInstall() {
        val hold = CompletableDeferred<Unit>()
        val first = Hermes(hubOf = { null }, versionScript = listOf(null, current), installOf = { runBlocking { hold.await() }; JSONObject().put("ok", true) })
        val second = Hermes(hubOf = { null }, versionScript = listOf(null, current))
        val scope = CoroutineScope(Dispatchers.Default)
        fun until(what: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 5000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue(what, condition())
        }
        try {
            val repair = PluginRepairController(scope, ::signedIn, { false }, {}, { first })
            val install = PluginInstallController(scope, ::signedIn, {}, {}, { second })
            repair.repair()
            until("the repair holds the change") { first.count("install") == 1 }
            assertTrue(PluginChange.active)
            // The missing-plugin install asked meanwhile waits, says so, and never reaches Hermes.
            install.install()
            until("the install is told to wait") { install.state.value.stage == PluginInstallStage.Failed }
            assertEquals(PluginInstallFailure.Busy, install.state.value.failure)
            assertEquals(0, second.calls.size)
            // The update's own step asks the same way.
            assertFalse(PluginChange.begin())
            hold.complete(Unit)
            until("the repair ends") { repair.state.value.stage == RepairStage.Done }
            until("the change is free again") { !PluginChange.active }
            // And the other way round: a repair asked while the install runs waits.
            val later = CompletableDeferred<Unit>()
            val running = Hermes(hubOf = { null }, versionScript = listOf(null, current), installOf = { runBlocking { later.await() }; JSONObject().put("ok", true).put("restart_required", false) })
            val busyRepair = PluginRepairController(scope, ::signedIn, { false }, {}, { Hermes() })
            val busyInstall = PluginInstallController(scope, ::signedIn, {}, {}, { running })
            busyInstall.install()
            until("the install holds the change") { running.count("install") == 1 }
            busyRepair.repair()
            until("the repair is told to wait") { busyRepair.state.value.stage == RepairStage.Failed }
            assertEquals(RepairUi(RepairStage.Failed, RepairProblem.Busy), busyRepair.state.value)
            later.complete(Unit)
            until("the install ends") { busyInstall.state.value.stage == PluginInstallStage.Done }
            until("free again") { !PluginChange.active }
        } finally { hold.complete(Unit); PluginChange.finish() }
    }

    @Test fun theReleaseTheLastCheckFoundIsPinnedWithoutAskingGitHub() {
        val kept = Release(133, "1.1.33", "dev.hermes.android", "https://example.com/Beacon-1.1.33.apk", "b".repeat(64), 1024, "a".repeat(64), "1.1.33", commit = commit)
        // GitHub is unreachable here (nothing listens on port 9): any call to it would fail the test.
        val offline = GitHubReleases(okhttp3.OkHttpClient(), "http://127.0.0.1:9", "xploit9/beacon", "Beacon-Test", setOf("127.0.0.1"), secureOnly = false)
        val installer = HermesPluginInstaller(signedIn(), offline, known = { kept })
        assertEquals(PublishedCommit("1.1.33", commit), runBlocking { installer.published() })
        // A release kept by an earlier build has no commit, and a mangled one is no commit: GitHub is asked, which fails here.
        for (bad in listOf(kept.copy(commit = ""), kept.copy(commit = "main"), kept.copy(commit = commit.uppercase()))) {
            try { runBlocking { HermesPluginInstaller(signedIn(), offline, known = { bad }).published() }; fail("Pinned '${bad.commit}'") } catch (_: UpdateException) { }
        }
        try { runBlocking { HermesPluginInstaller(signedIn(), offline).published() }; fail("Pinned with no release") } catch (_: UpdateException) { }
    }

    @Test fun gitHubBeingBusyHasItsOwnWordsEverywhereTheRepairAndTheInstallCanStop() {
        val busy = UpdateException(UpdateException.Kind.Busy, "GitHub answered 403")
        assertEquals(PluginInstallFailure.GitHubBusy, installFailure(busy))
        assertEquals(PluginInstallFailure.NoRelease, installFailure(UpdateException(UpdateException.Kind.Network, "offline")))
        assertEquals(PluginInstallFailure.NoRelease, installFailure(UpdateException(UpdateException.Kind.Verification, "no tag")))
        assertEquals(failed(RepairProblem.GitHubBusy), repair(Hermes(publishedOf = { throw busy })))
        assertEquals("GitHub is busy. Try again later.", RepairProblem.GitHubBusy.text)
        assertEquals("Nothing is read or changed without a release", listOf("published"), Hermes(publishedOf = { throw busy }).also { repair(it) }.calls)
    }

    @Test fun aScanBlockHasItsOwnLineInTheRepairAndTheInstall() {
        val blocked = ApiFailure(400, "Security scan blocked plugin install: dangerous verdict (2 findings). --force does not override a dangerous verdict.")
        assertEquals(PluginInstallFailure.Blocked, installFailure(blocked))
        assertEquals(PluginInstallFailure.Error, installFailure(ApiFailure(400, "Install failed.")))
        assertEquals(PluginInstallFailure.Error, installFailure(ApiFailure(500, "Security scan blocked plugin install")))
        val hermes = Hermes(hubOf = { null }, versionScript = listOf(null), installOf = { throw blocked })
        assertEquals(failed(RepairProblem.Blocked), repair(hermes))
        assertEquals("Hermes' security scan blocked it.", RepairProblem.Blocked.text)
        assertEquals("It does not look for the plugin as if it had been installed", 0, hermes.count("present"))
        assertEquals("The same words on the update's failed step", RepairProblem.Blocked.text, StepNote.PluginBlocked)
    }

    @Test fun aSignedOutPhoneStartsNothing() {
        var finished = 0
        val controller = PluginRepairController(CoroutineScope(Dispatchers.Unconfined), { null }, { false }, { finished++ })
        controller.repair()
        assertEquals(RepairUi(), controller.state.value); assertEquals(0, finished)
    }

    // ---- the real endpoint, against a scripted HTTP layer

    private class Wire(private val answer: (method: String, path: String, body: String) -> Pair<Int, String>) {
        val requests = mutableListOf<String>()
        val bodies = mutableMapOf<String, String>()
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, persistContext = false) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer(); request.body?.writeTo(buffer)
                val body = buffer.readUtf8()
                val path = request.url.encodedPath
                requests += request.method + " " + path
                bodies[request.method + " " + path] = body
                val (code, text) = answer(request.method, path, body)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(text.toResponseBody("application/json".toMediaType())).build()
            }
        }
    }

    @Test fun anUpdateIsHermesInstallerPinnedToTheReleaseCommitAndNeverAGitPull() {
        val wire = Wire { _, _, _ -> 200 to """{"ok":true,"plugin_name":"beacon","restart_required":false}""" }
        val answer = runBlocking { HermesPluginUpdater(wire.client, commitFor = { commit }).update() }
        assertTrue(answer.optBoolean("ok"))
        assertEquals(listOf("POST /api/dashboard/agent-plugins/install"), wire.requests)
        val body = JSONObject(wire.bodies.getValue("POST /api/dashboard/agent-plugins/install"))
        assertEquals(pluginInstallBody(commit).toString(), body.toString())
        wire.client.closeNative()
    }

    @Test fun aTreeHermesWillNotTakeComesBackAsA400WithItsScanTextAndIsNeverRetried() {
        // Hermes' install route answers a dangerous verdict with HTTP 400 (dashboard_ui._plugin_action); --force does not override it.
        val wire = Wire { _, _, _ -> 400 to """{"detail":"Security scan blocked plugin install: dangerous verdict (1 findings). --force does not override a dangerous verdict."}""" }
        val error = try { runBlocking { HermesPluginUpdater(wire.client, commitFor = { commit }).update() }; null } catch (error: ApiFailure) { error }
        assertEquals(400, error?.status)
        assertTrue(scanBlocked(error!!))
        assertEquals(listOf("POST /api/dashboard/agent-plugins/install"), wire.requests)
        assertEquals(PluginInstallFailure.Blocked, installFailure(error))
        assertEquals(RepairProblem.Blocked, repairProblemOf(installFailure(error)))
        wire.client.closeNative()
    }

    @Test fun anUpdateWithoutAReleaseCommitSendsNothing() {
        val wire = Wire { _, _, _ -> 200 to "{}" }
        try { runBlocking { HermesPluginUpdater(wire.client, commitFor = { throw UpdateException(UpdateException.Kind.Verification, "no tag") }).update() }; fail("went on") }
        catch (_: UpdateException) { }
        assertTrue(wire.requests.isEmpty())
        wire.client.closeNative()
    }

    @Test fun theClientRefusesAnyOtherInstallBodyBeforeARequestExists() {
        val wire = Wire { _, _, _ -> 200 to """{"ok":true}""" }
        val bad = listOf(
            JSONObject().put("identifier", BEACON_PLUGIN_SOURCE).put("enable", true).put("force", false),
            pluginInstallBody(commit).put("catalog_name", "beacon"),
            pluginInstallBody(commit).put("identifier", "someone-else/beacon/hermes-plugin/beacon"),
        )
        for (body in bad) try { runBlocking { wire.client.nativeRequest("/api/dashboard/agent-plugins/install", "POST", body) }; fail("sent $body") } catch (_: IllegalArgumentException) { }
        assertTrue(wire.requests.isEmpty())
        runBlocking { wire.client.nativeRequest("/api/dashboard/agent-plugins/install", "POST", pluginInstallBody(commit)) }
        assertEquals(listOf("POST /api/dashboard/agent-plugins/install"), wire.requests)
        wire.client.closeNative()
    }

    // ---- every line is translated

    @Test fun everyLineTheRepairCanShowIsTranslatedInEveryLanguage() {
        val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText())
        assertTrue(RepairStrings.size >= 15)
        // The new lines are in the list the check walks.
        listOf("GitHub is busy. Try again later.", "Hermes' security scan blocked it.", "Beacon is newer than the latest release.").forEach { assertTrue(it, it in RepairStrings) }
        val missing = catalog.keys().asSequence().filter { it != "en" }.flatMap { language ->
            RepairStrings.filter { catalog.getJSONObject(language).optString(normalized(it)).isBlank() }.map { "$language \"$it\"" }
        }.toList()
        assertTrue("These repair lines are missing in a language:\n" + missing.joinToString("\n"), missing.isEmpty())
        // Short, plain lines: none is a paragraph.
        RepairStrings.forEach { assertTrue(it, it.length <= 70) }
    }
}
