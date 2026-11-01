package dev.hermes.android.updates

import dev.hermes.android.data.ApiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Which process still runs old plugin code: the Hermes gateway (a gateway restart fixes it) or only the dashboard (it does not). */
internal enum class RestartKind { None, Gateway, Dashboard }

/**
 * What Hermes reports: [running] is the dashboard's import-time version, [installed] the files on disk, and [gateway] the version the
 * gateway process loaded ("" when Hermes or the plugin is too old to say).
 */
internal data class PluginVersions(val running: String, val installed: String, val gateway: String = "") {
    /** [restartDone]: a gateway restart already finished, so a gateway that cannot be read is not what is still old. */
    fun restartKind(restartDone: Boolean = false): RestartKind = when {
        running.isBlank() || installed.isBlank() -> RestartKind.None
        gateway.isNotBlank() -> when {
            gateway != installed -> RestartKind.Gateway
            running != installed -> RestartKind.Dashboard
            else -> RestartKind.None
        }
        running == installed -> RestartKind.None
        restartDone -> RestartKind.Dashboard
        else -> RestartKind.Gateway
    }
    val restartRequired: Boolean get() = restartKind() != RestartKind.None
}

internal enum class PluginStatus {
    Hidden, Checking, UpToDate, Available, Updating, Updated, NeedsReview, Restart, Busy, Failed,
    /** Hermes refused the restart because it is working; the phone retries when it is idle. */
    Waiting,
    /** The gateway restarted but the dashboard still runs the old plugin: only the owner can restart that. */
    DashboardRestart,
    /** The Hermes gateway restart did not start or did not finish; the next try asks again. */
    RestartFailed,
    /** The plugin is updated and the gateway restarted, but the dashboard restart did not finish: the app can ask for that one restart again. */
    DashboardFailed,
    /** The plugin on Hermes is newer than anything this app can match. */
    Newer,
}

/** The "Beacon plugin" row of the update screen. [target] is the plugin version the latest release ships with. */
internal data class PluginRow(val status: PluginStatus = PluginStatus.Hidden, val installed: String = "", val target: String = "", val review: List<String> = emptyList()) {
    /** The sentence behind the row's state: shown beside the version, or as the row's only action when it needs the owner's review. */
    val text: String get() = when (status) {
        PluginStatus.Checking -> "Checking…"
        PluginStatus.UpToDate -> "Up to date"
        PluginStatus.Available -> "Ready to update"
        PluginStatus.Updating -> "Updating…"
        PluginStatus.Updated -> "Updated"
        PluginStatus.NeedsReview -> "Review changes"
        PluginStatus.Restart -> "Restart Hermes"
        PluginStatus.Busy -> "Hermes is busy. Try again later."
        PluginStatus.Failed -> "The Beacon plugin didn't update"
        PluginStatus.Waiting -> "Hermes is busy — restarts when it's idle"
        PluginStatus.DashboardRestart -> StepNote.DashboardManual
        PluginStatus.RestartFailed -> StepNote.GatewayFailed
        PluginStatus.DashboardFailed -> "The plugin is updated. The Hermes dashboard didn't restart."
        PluginStatus.Newer -> "The Beacon plugin is newer than this app. Update the app."
        PluginStatus.Hidden -> ""
    }
    /** A tap on Update (or Restart Hermes, or Restart dashboard) can move this forward. */
    val actionable: Boolean get() = status in setOf(PluginStatus.Available, PluginStatus.NeedsReview, PluginStatus.Busy, PluginStatus.Failed,
        PluginStatus.Restart, PluginStatus.Waiting, PluginStatus.RestartFailed, PluginStatus.DashboardFailed)
    /** The row needs the owner's attention, even when nothing on the phone can fix it (the dashboard restart, a plugin newer than the app). */
    val attention: Boolean get() = actionable || status == PluginStatus.DashboardRestart || status == PluginStatus.Newer
    /** A Hermes restart is what is pending (the hub says "Restart Hermes" for this, and "Update Hermes plugin" for the rest of [actionable]). */
    val restartPending: Boolean get() = status in setOf(PluginStatus.Restart, PluginStatus.Waiting, PluginStatus.RestartFailed, PluginStatus.DashboardRestart, PluginStatus.DashboardFailed)
}

/** Every status text the plugin row shows through uiText, the labels, notes and spoken states of the update steps, and the hero's action labels. */
internal val PluginRowStrings = (PluginStatus.entries.map { PluginRow(it).text } + UpdateStepStrings + StepNote.all + StepStateText.all + HeroActionStrings)
    .filter(String::isNotBlank).distinct()

/** What the phone learned about the plugin on Hermes. */
internal sealed interface PluginProbe {
    /** No Hermes session, no plugin, or not the owner: nothing to update. */
    data object None : PluginProbe
    data object Checking : PluginProbe
    /** An older plugin without the version route: installed, but old. */
    data object Unreadable : PluginProbe
    data object Failed : PluginProbe
    data class Read(val versions: PluginVersions) : PluginProbe
}

internal fun pluginStatusFor(versions: PluginVersions, target: String, restartDone: Boolean = false): PluginStatus {
    // One release is one version of the app and the plugin: a plugin past the release has no app that matches it.
    if (olderVersion(target, versions.installed)) return PluginStatus.Newer
    if (olderVersion(versions.installed, target)) return PluginStatus.Available
    val behind = olderVersion(versions.running, target) || versions.gateway.isNotBlank() && olderVersion(versions.gateway, target)
    if (!behind) return PluginStatus.UpToDate
    return when (versions.restartKind(restartDone)) {
        RestartKind.Dashboard -> PluginStatus.DashboardRestart
        RestartKind.Gateway -> PluginStatus.Restart
        RestartKind.None -> PluginStatus.UpToDate
    }
}

/**
 * A dashboard that is still old after a restart of its own failed is not one that cannot restart itself: the phone offers that restart again
 * ([PluginStatus.DashboardFailed]) instead of sending the owner to the computer. [failed] is the phone's memory of that failure.
 */
internal fun PluginRow.afterDashboardFailure(failed: Boolean): PluginRow =
    if (failed && status == PluginStatus.DashboardRestart) copy(status = PluginStatus.DashboardFailed) else this

/** A failed check hides the row: there is nothing to say about a plugin the phone could not reach. */
internal fun pluginRowFor(probe: PluginProbe, target: String, memo: RestartMemo = RestartMemo()): PluginRow = when {
    target.isBlank() -> PluginRow()
    probe is PluginProbe.Checking -> PluginRow(PluginStatus.Checking, "", target)
    probe is PluginProbe.Unreadable -> PluginRow(PluginStatus.Available, "", target)
    probe is PluginProbe.Read -> PluginRow(pluginStatusFor(probe.versions, target, memo.completed), probe.versions.installed, target)
    else -> PluginRow()
}

/**
 * The gateway restart this phone asked Hermes for: its [pid] and when ([at], phone clock), kept beside the pending flag so a tap polls
 * a restart still in flight instead of starting a second one. [completed] says that restart finished.
 */
internal data class RestartMemo(val pid: Int = 0, val at: Long = 0, val completed: Boolean = false) {
    fun encode(): String = if (this == RestartMemo()) "" else JSONObject().put("pid", pid).put("at", at).put("done", completed).toString()
    /** A restart this recent may still be running; an older pid is only history. */
    fun inFlight(now: Long): Boolean = pid > 0 && !completed && now >= at && now - at <= RESTART_MEMORY_MS
    companion object {
        const val RESTART_MEMORY_MS = 10 * 60_000L
        fun decode(value: String?): RestartMemo = runCatching {
            val json = JSONObject(value.orEmpty())
            RestartMemo(json.optInt("pid"), json.optLong("at"), json.optBoolean("done"))
        }.getOrDefault(RestartMemo())
    }
}

/** How a restart request ended. [Pending]: not confirmed yet, the next try polls it. [Waiting]: Hermes is working. [Call]: a call is running. */
internal enum class RestartResult { Completed, Pending, Waiting, Call, Failed }

/**
 * How a dashboard restart ended. [Unsupported]: this plugin has no such route (it predates it, or runs on a machine that cannot do it),
 * so only the owner can restart the dashboard.
 */
internal enum class DashboardRestartResult { Completed, Waiting, Call, Failed, Unsupported }

/** Hermes is serving and not working on a reply or task, so a restart will not be refused for live sessions. */
internal fun hermesIdle(status: GatewayStatus): Boolean = status.running && !status.busy

internal interface PluginEndpoint {
    suspend fun version(): PluginVersions
    suspend fun update(acceptCapabilities: Boolean): JSONObject
    /**
     * Restarts the Hermes gateway the safe way (never an interrupt). [last] is the restart already asked for; [remember] is told each pid
     * before it is waited on, and when it finishes. No forced restart fallback.
     */
    suspend fun restart(last: RestartMemo = RestartMemo(), remember: (RestartMemo) -> Unit = {}): RestartResult = RestartResult.Failed
    /**
     * Restarts the Hermes dashboard through the plugin's own helper and waits until the new dashboard answers. [onReconnecting] is told
     * true while the dashboard is down and false when it answers again.
     */
    suspend fun restartDashboard(onReconnecting: (Boolean) -> Unit = {}): DashboardRestartResult = DashboardRestartResult.Unsupported
}

private suspend fun restartUpdatedPlugin(endpoint: PluginEndpoint, installed: String, target: String, memo: RestartMemo, remember: (RestartMemo) -> Unit, steps: StepTracker, pause: suspend (Long) -> Unit): PluginRow {
    fun row(status: PluginStatus, from: String = installed) = PluginRow(status, from, target)
    steps.active(UpdateStep.PluginGateway)
    return try {
        when (endpoint.restart(memo, remember)) {
            RestartResult.Waiting -> { steps.hold(UpdateStep.PluginGateway, StepNote.Busy); row(PluginStatus.Waiting) }
            RestartResult.Call -> { steps.fail(UpdateStep.PluginGateway, StepNote.Call); row(PluginStatus.Busy) }
            RestartResult.Pending -> { steps.fail(UpdateStep.PluginGateway, StepNote.GatewayFailed); row(PluginStatus.Restart) }
            RestartResult.Failed -> { steps.fail(UpdateStep.PluginGateway, StepNote.GatewayFailed); row(PluginStatus.RestartFailed) }
            RestartResult.Completed -> {
                steps.done(UpdateStep.PluginGateway)
                val current = endpoint.version()
                when {
                    olderVersion(current.installed, target) -> { steps.fail(UpdateStep.PluginInstall, StepNote.PluginFailed); row(PluginStatus.Available, current.installed) }
                    // A restart that finished leaves a stale dashboard (or gateway) behind: the dashboard is restarted next, a stale gateway is said so.
                    current.restartKind(restartDone = true) == RestartKind.Dashboard -> restartDashboardStep(endpoint, current.installed, target, steps, pause)
                    current.restartKind(restartDone = true) == RestartKind.Gateway -> { steps.fail(UpdateStep.PluginGateway, StepNote.GatewayFailed); row(PluginStatus.Restart, current.installed) }
                    else -> { steps.skip(UpdateStep.PluginDashboard); steps.complete(); row(PluginStatus.Updated, current.installed) }
                }
            }
        }
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { steps.fail(steps.rows.active?.step ?: UpdateStep.PluginGateway, StepNote.GatewayFailed); row(PluginStatus.Restart) }
}

/**
 * Restarts the dashboard so it loads the new plugin, then reads what the new dashboard imported: that, not the helper's word, is what
 * says it worked. A Hermes plugin without the route leaves the restart to the owner, as it always did.
 */
private suspend fun restartDashboardStep(endpoint: PluginEndpoint, installed: String, target: String, steps: StepTracker, pause: suspend (Long) -> Unit): PluginRow {
    fun row(status: PluginStatus, from: String = installed) = PluginRow(status, from, target)
    steps.active(UpdateStep.PluginDashboard)
    val result = try { endpoint.restartDashboard { reconnecting -> steps.note(UpdateStep.PluginDashboard, if (reconnecting) StepNote.Reconnecting else "") } }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { DashboardRestartResult.Failed }
    return when (result) {
        // A dashboard too old for the route is restarted by the gateway, which started with the new plugin: wait for that restart.
        DashboardRestartResult.Unsupported -> awaitGatewayLedRestart(endpoint, target, steps, pause)?.let { now ->
            steps.done(UpdateStep.PluginDashboard); steps.complete(); row(PluginStatus.Updated, now.installed)
        } ?: run { steps.fail(UpdateStep.PluginDashboard, StepNote.DashboardManual); row(PluginStatus.DashboardRestart) }
        DashboardRestartResult.Waiting -> { steps.hold(UpdateStep.PluginDashboard, StepNote.Busy); row(PluginStatus.Waiting) }
        DashboardRestartResult.Call -> { steps.fail(UpdateStep.PluginDashboard, StepNote.Call); row(PluginStatus.Busy) }
        DashboardRestartResult.Failed -> { steps.fail(UpdateStep.PluginDashboard, StepNote.DashboardFailed); row(PluginStatus.DashboardFailed) }
        DashboardRestartResult.Completed -> {
            var now: PluginVersions? = null
            for (attempt in 0 until 5) {
                if (attempt > 0) pause(1000)
                now = try { endpoint.version() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
                if (now != null) break
            }
            if (now != null && now.restartKind(restartDone = true) == RestartKind.None && !olderVersion(now.installed, target)) {
                steps.done(UpdateStep.PluginDashboard); steps.complete(); row(PluginStatus.Updated, now.installed)
            } else { steps.fail(UpdateStep.PluginDashboard, StepNote.DashboardFailed); row(PluginStatus.DashboardFailed) }
        }
    }
}

/** How long the phone waits for the gateway to restart a dashboard that is too old to restart itself. */
internal const val GATEWAY_LED_RESTART_MS = 150_000L

/** The versions once the dashboard runs the new plugin, or null when it did not within [GATEWAY_LED_RESTART_MS]. */
private suspend fun awaitGatewayLedRestart(endpoint: PluginEndpoint, target: String, steps: StepTracker, pause: suspend (Long) -> Unit): PluginVersions? {
    var waited = 0L
    while (true) {
        val now = try { endpoint.version() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        steps.note(UpdateStep.PluginDashboard, if (now == null) StepNote.Reconnecting else "")
        if (now != null && now.restartKind(restartDone = true) == RestartKind.None && !olderVersion(now.installed, target)) return now
        if (waited >= GATEWAY_LED_RESTART_MS) return null
        pause(2000); waited += 2000
    }
}

/** Reads the plugin version; a 404 on the version route is an older plugin, a 403 means this account is not the owner. */
internal suspend fun probePlugin(endpoint: PluginEndpoint): PluginProbe = try {
    PluginProbe.Read(endpoint.version())
} catch (error: CancellationException) { throw error }
catch (error: Exception) {
    when {
        error is ApiFailure && error.status == 403 -> PluginProbe.None
        error is ApiFailure && error.status == 404 -> PluginProbe.Unreadable
        else -> PluginProbe.Failed
    }
}

/**
 * Runs Hermes' own updater once and says how the plugin row ends. It never throws (so the app update that follows
 * is never blocked) and never installs a plugin Hermes asked the owner to review. [memo] and [remember] carry the restart already asked for.
 * [steps] is told each step as it really starts and ends; a step this update does not need is skipped, never shown as done.
 */
internal suspend fun runPluginUpdate(
    endpoint: PluginEndpoint, target: String, accept: Boolean, restartOnly: Boolean = false,
    memo: RestartMemo = RestartMemo(), remember: (RestartMemo) -> Unit = {}, steps: StepTracker = StepTracker(), pause: suspend (Long) -> Unit = { delay(it) },
): PluginRow {
    var before: PluginVersions? = null
    try {
        before = runCatching { endpoint.version() }.getOrNull()
        val current = before
        val kind = current?.restartKind(memo.completed) ?: RestartKind.None
        // Only the dashboard is old: a gateway restart changes nothing, so none is asked for.
        if (kind == RestartKind.Dashboard) {
            steps.plan(UpdateStep.PluginDashboard)
            return restartDashboardStep(endpoint, current?.installed.orEmpty(), target, steps, pause)
        }
        if (kind == RestartKind.Gateway || restartOnly && current != null && !olderVersion(current.installed, target)) {
            // A gateway that says it already runs the installed version needs no restart even when an earlier one was left pending.
            if (kind == RestartKind.None && current?.gateway?.isNotBlank() == true) { steps.clear(); return PluginRow(PluginStatus.Updated, current.installed, target) }
            steps.plan(UpdateStep.PluginGateway, UpdateStep.PluginDashboard)
            return restartUpdatedPlugin(endpoint, current?.installed.orEmpty(), target, memo, remember, steps, pause)
        }
        steps.plan(UpdateStep.PluginDownload, UpdateStep.PluginInstall, UpdateStep.PluginGateway, UpdateStep.PluginDashboard)
        // Hermes downloads the new plugin and puts it in place in this one request: it is the download until it answers.
        steps.active(UpdateStep.PluginDownload)
        val response = endpoint.update(accept)
        if (response.optBoolean("scan_blocked")) { steps.clear(); return PluginRow(PluginStatus.NeedsReview, before?.installed.orEmpty(), target) }
        if (response.optBoolean("consent_required")) {
            steps.clear()
            val lines = response.optJSONArray("delta_lines")?.let { rows -> (0 until rows.length()).mapNotNull { rows.optString(it).takeIf { line -> line.isNotBlank() && line != "null" } } }.orEmpty()
            return PluginRow(PluginStatus.NeedsReview, before?.installed.orEmpty(), target, lines)
        }
        if (!response.optBoolean("ok")) { steps.fail(UpdateStep.PluginDownload, StepNote.PluginFailed); return PluginRow(PluginStatus.Failed, before?.installed.orEmpty(), target) }
        // New files are on disk: whatever restart was remembered belonged to the version before.
        remember(RestartMemo())
        if (response.optBoolean("unchanged")) steps.skipAll(UpdateStep.PluginDownload, UpdateStep.PluginInstall)
        else { steps.done(UpdateStep.PluginDownload); steps.active(UpdateStep.PluginInstall) }
        var latest = runCatching { endpoint.version() }.getOrNull()
        if (!response.optBoolean("unchanged")) {
            // Hermes reports the new version a moment after the update call returns.
            for (attempt in 0 until 10) {
                if (latest != null && latest != before) break
                pause(1000)
                latest = runCatching { endpoint.version() }.getOrNull() ?: latest
            }
        }
        val done = latest ?: run { steps.fail(UpdateStep.PluginInstall, StepNote.PluginFailed); return PluginRow(PluginStatus.Failed, before?.installed.orEmpty(), target) }
        if (!olderVersion(done.installed, target)) steps.done(UpdateStep.PluginInstall)
        if (response.optBoolean("restart_required") || done.restartKind() == RestartKind.Gateway)
            return restartUpdatedPlugin(endpoint, done.installed, target, RestartMemo(), remember, steps, pause)
        steps.skip(UpdateStep.PluginGateway)
        if (olderVersion(done.installed, target)) { steps.fail(UpdateStep.PluginInstall, StepNote.PluginFailed); return PluginRow(PluginStatus.Failed, done.installed, target) }
        if (done.restartKind() == RestartKind.Dashboard) return restartDashboardStep(endpoint, done.installed, target, steps, pause)
        steps.skip(UpdateStep.PluginDashboard); steps.complete()
        return PluginRow(PluginStatus.Updated, done.installed, target)
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) {
        val installed = before?.installed.orEmpty()
        return when {
            error is ApiFailure && (error.status == 403 || error.status == 404) -> { steps.clear(); PluginRow() }
            error is ApiFailure && error.status in setOf(409, 423, 429) -> { steps.rows.active?.let { steps.fail(it.step, StepNote.HermesBusy) }; PluginRow(PluginStatus.Busy, installed, target) }
            else -> { steps.rows.active?.let { steps.fail(it.step, StepNote.PluginFailed) }; PluginRow(PluginStatus.Failed, installed, target) }
        }
    }
}
