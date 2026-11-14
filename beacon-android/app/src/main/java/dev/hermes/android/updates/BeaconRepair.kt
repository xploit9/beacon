package dev.hermes.android.updates

import dev.hermes.android.core.Release
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.BeaconClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

// "Repair Beacon": the owner taps once and the app puts the published Beacon plugin back on their Hermes, using only Hermes' own routes,
// because Beacon's routes may be the broken part. It never runs by itself and it never restarts the Hermes gateway: the demo proof showed
// that the install's own reload puts the gateway on the new Beacon at once, and that Beacon then restarts the old dashboard by itself
// (about a minute later), so the repair only waits for that.

/** What the repair finds before it does anything. */
internal enum class RepairFinding {
    Healthy,
    /** Hermes lists no Beacon. */
    NotInstalled,
    /** Hermes lists Beacon, off. */
    TurnedOff,
    /** Hermes lists Beacon, on, but Beacon's own routes do not answer. */
    NotRunning,
    /** Beacon answers, with a version older than the published release. */
    OutOfDate,
    /** Beacon answers, the right files are on disk, and a process still runs older code. */
    StillOld,
    /** Beacon is past the latest release (a newer build). Installing the release would take it back, so the repair touches nothing. */
    Newer,
}

/** Hermes' own row for Beacon: the version on disk and whether it is on. */
internal data class HubPlugin(val version: String, val enabled: Boolean)

/** The `beacon` row of `GET /api/dashboard/plugins/hub`, or null when Hermes lists none. `runtime_status` is "enabled", "disabled" or "inactive". */
internal fun hubPluginOf(answer: JSONObject): HubPlugin? {
    val rows = answer.optJSONArray("plugins") ?: return null
    val row = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.firstOrNull { it.optString("name") == "beacon" } ?: return null
    return HubPlugin(row.optString("version"), row.optString("runtime_status") == "enabled")
}

/**
 * [hub]: Hermes' row, [versions]: what Beacon says about itself (null when it does not answer), [published]: the latest release's plugin version.
 * A Beacon that is off is only turned on, which cannot take it back; any other fix reinstalls the release, so one that is past the release is [RepairFinding.Newer].
 */
internal fun repairFinding(hub: HubPlugin?, versions: PluginVersions?, published: String): RepairFinding = when {
    hub == null -> RepairFinding.NotInstalled
    !hub.enabled -> RepairFinding.TurnedOff
    olderVersion(published, versions?.installed ?: hub.version) -> RepairFinding.Newer
    versions == null -> RepairFinding.NotRunning
    published.isNotBlank() && olderVersion(versions.installed, published) -> RepairFinding.OutOfDate
    versions.restartKind() != RestartKind.None -> RepairFinding.StillOld
    else -> RepairFinding.Healthy
}

internal enum class RepairStage { Idle, Checking, Repairing, Waiting, Done, Failed }

/** Why a repair stopped, each with the one short line the owner reads. */
internal enum class RepairProblem(val text: String) {
    SignIn("Sign in with Hermes again, then try again."),
    Denied("Hermes only lets its owner add Beacon."),
    Offline("Can't reach Hermes. Check your connection, then try again."),
    Unsupported("This Hermes can't add plugins from the app."),
    NoRelease("Couldn't find the latest Beacon release."),
    GitHubBusy("GitHub is busy. Try again later."),
    Refused("Hermes couldn't do that. Try again."),
    /** Hermes' own security scan said no; forcing the install does not change that, so trying again would not either. */
    Blocked("Hermes' security scan blocked it."),
    Busy("Hermes is busy. Try again later."),
    Call("End your call before repairing Beacon."),
    NotAnswering("Hermes isn't answering yet. Try again in a moment."),
    /** Beacon is installed but the gateway did not take it up: only a Hermes restart finishes it, and the update screen offers that with its own checks. */
    RestartNeeded("Restart Hermes to finish"),
    DashboardManual(StepNote.DashboardManual),
}

internal fun repairProblemOf(failure: PluginInstallFailure): RepairProblem = when (failure) {
    PluginInstallFailure.SignIn -> RepairProblem.SignIn
    PluginInstallFailure.Denied -> RepairProblem.Denied
    PluginInstallFailure.Offline -> RepairProblem.Offline
    PluginInstallFailure.Unsupported -> RepairProblem.Unsupported
    PluginInstallFailure.NoRelease -> RepairProblem.NoRelease
    PluginInstallFailure.GitHubBusy -> RepairProblem.GitHubBusy
    PluginInstallFailure.Blocked -> RepairProblem.Blocked
    PluginInstallFailure.Busy -> RepairProblem.Busy
    PluginInstallFailure.NotYet, PluginInstallFailure.GatewayDown -> RepairProblem.NotAnswering
    PluginInstallFailure.Error -> RepairProblem.Refused
}

internal data class RepairUi(val stage: RepairStage = RepairStage.Idle, val problem: RepairProblem? = null, val finding: RepairFinding? = null) {
    val working: Boolean get() = stage == RepairStage.Checking || stage == RepairStage.Repairing || stage == RepairStage.Waiting
}

/** Every fixed line the repair shows through uiText; each must exist in all languages. */
internal val RepairStrings = listOf(
    "Repair Beacon", "Repair", "Reinstalls the latest Beacon. The Hermes dashboard restarts briefly.",
    "Repairing Beacon…", "Beacon is repaired.", NEWER_THAN_RELEASE, "Beacon is turned off on Hermes.", "Beacon is on Hermes but isn't running.", StepNote.Reconnecting,
) + RepairProblem.entries.map { it.text }

/** The result line of a repair that found Beacon past the latest release and left it alone. */
internal const val NEWER_THAN_RELEASE = "Beacon is newer than the latest release."

/** How long the dashboard gets to come back on the new Beacon: measured on the demo at 66 s after the install answered (20 s hold, then stop, start, verify). */
internal const val CURRENT_WAIT_MS = 150_000L
private const val CURRENT_POLL_MS = 2_000L

/**
 * One repair: find what is wrong from Hermes' own data, put the published Beacon back (turn it on, or install it pinned to the release's commit),
 * then wait for the dashboard and the gateway to run it. Ends Done only when Beacon answers and the files, the dashboard process and the gateway
 * all run the release. It restarts nothing itself. A call stops it before it starts: the plugin's own dashboard restart never runs during one.
 */
internal suspend fun runRepair(
    endpoint: RepairEndpoint,
    callActive: () -> Boolean,
    report: (RepairStage) -> Unit = {},
    pause: suspend (Long) -> Unit = { delay(it) },
): RepairUi {
    fun failed(problem: RepairProblem, finding: RepairFinding? = null) = RepairUi(RepairStage.Failed, problem, finding)
    fun mapped(error: Exception, finding: RepairFinding? = null) = failed(repairProblemOf(installFailure(error)), finding)
    if (callActive()) return failed(RepairProblem.Call)
    try {
        report(RepairStage.Checking)
        val published = endpoint.published()
        val finding = repairFinding(endpoint.hub(), endpoint.versions(), published.version)
        // Past the release: installing it would take Beacon back to an older version, so nothing is installed, turned on or restarted.
        if (finding == RepairFinding.Newer) return RepairUi(RepairStage.Done, finding = finding)
        report(RepairStage.Repairing)
        var gatewayNeedsRestart = false
        when (finding) {
            RepairFinding.TurnedOff -> if (!endpoint.enable().optBoolean("ok", true)) return failed(RepairProblem.Refused, finding)
            else -> {
                // A reply lost after the request left (a slow install outlasting the phone's read time) may still have installed Beacon:
                // look at the versions, as the first-time install does, instead of calling it "Can't reach Hermes" and asking for a second install.
                val answer = try { endpoint.install() } catch (error: CancellationException) { throw error } catch (error: Exception) { if (lostReply(error)) null else throw error }
                if (answer != null) {
                    if (!answer.optBoolean("ok")) return failed(RepairProblem.Refused, finding)
                    gatewayNeedsRestart = answer.opt("restart_required") == true
                }
            }
        }
        if (!beaconAnswers(endpoint, pause)) return failed(RepairProblem.NotAnswering, finding)
        report(RepairStage.Waiting)
        var waited = 0L
        var now: PluginVersions?
        while (true) {
            now = attempt { endpoint.versions() }
            if (now != null && now.restartKind() == RestartKind.None && !olderVersion(now.installed, published.version)) return RepairUi(RepairStage.Done, finding = finding)
            if (waited >= CURRENT_WAIT_MS) break
            pause(CURRENT_POLL_MS); waited += CURRENT_POLL_MS
        }
        // What is left is not the phone's to do: a gateway that did not take Beacon up needs a Hermes restart (the update screen offers it with its
        // own checks); a dashboard that stayed old needs its owner.
        val gatewayOld = now?.let { it.gateway.isNotBlank() && it.gateway != it.installed } == true
        val filesOld = now?.let { olderVersion(it.installed, published.version) } == true
        return failed(when {
            filesOld -> RepairProblem.Refused
            gatewayOld || gatewayNeedsRestart && now == null -> RepairProblem.RestartNeeded
            else -> RepairProblem.DashboardManual
        }, finding)
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) { return mapped(error) }
}

/** The one repair in progress for the signed-in Hermes. [finished] runs when it ends well, so the app reads the plugin again. */
internal class PluginRepairController(
    private val scope: CoroutineScope,
    private val client: () -> BeaconClient?,
    private val callActive: () -> Boolean,
    private val finished: () -> Unit,
    /** Tests give a scripted Hermes; the app uses the real one. */
    private val endpointFor: ((BeaconClient) -> RepairEndpoint)? = null,
    /** The release the last update check found, for the commit to pin: the tap then asks GitHub for nothing. */
    private val known: () -> Release? = { null },
) {
    private val mutable = MutableStateFlow(RepairUi())
    val state: StateFlow<RepairUi> = mutable.asStateFlow()
    private var job: Job? = null

    /** Never automatic, and never two at once. */
    fun repair() {
        val active = client() ?: return
        if (job?.isActive == true) return
        val detached = active.detached()
        mutable.value = RepairUi(RepairStage.Checking)
        job = scope.launch {
            // The install and the app update post the same forced install: one plugin change at a time, and the one that waits says so.
            if (!PluginChange.begin()) { detached.closeNative(); mutable.value = RepairUi(RepairStage.Failed, RepairProblem.Busy); return@launch }
            val result = try {
                try {
                    runRepair(endpointFor?.invoke(detached) ?: HermesPluginInstaller(detached, known = known), callActive, report = { stage -> mutable.value = RepairUi(stage) })
                } finally { detached.closeNative() }
            } finally { PluginChange.finish() }
            mutable.value = result
            if (result.stage == RepairStage.Done) finished()
        }
    }

    /** A different account or Hermes is signed in, or the owner closed the result. */
    fun reset() { job?.cancel(); job = null; mutable.value = RepairUi() }
}

/**
 * What Hermes and Beacon say right now, for the line beside the Repair button; null when Hermes cannot be read. It does not ask GitHub, so
 * "out of date" is only found when the owner taps Repair.
 */
internal suspend fun readRepairFinding(endpoint: RepairEndpoint): RepairFinding? = try {
    repairFinding(endpoint.hub(), endpoint.versions(), published = "")
} catch (error: CancellationException) { throw error }
catch (_: Exception) { null }
