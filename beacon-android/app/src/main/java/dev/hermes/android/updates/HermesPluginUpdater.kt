package dev.hermes.android.updates

import dev.hermes.android.core.PLUGIN_VERSION_PATTERN
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.NativeAuthExpiredException
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal class HermesPluginUpdater(private val client: BeaconClient, private val clock: () -> Long = System::currentTimeMillis) : PluginEndpoint {
    /** How the dashboard restart's polling waits between asks; tests shorten it. */
    internal var pause: suspend (Long) -> Unit = { delay(it) }

    override suspend fun version(): PluginVersions {
        val response = client.nativeRequest("/api/plugins/beacon/mobile/plugin/version")
        // "gateway" is the version the Hermes gateway loaded; a plugin that predates it sends nothing, and "" means Hermes does not know.
        val gateway = (response.opt("gateway") as? String).orEmpty().trim().takeIf { PLUGIN_VERSION_PATTERN.matches(it) }.orEmpty()
        val versions = PluginVersions(response.getString("running"), response.getString("installed"), gateway)
        require(versions.running.isNotBlank() && versions.installed.isNotBlank())
        return versions
    }

    override suspend fun update(acceptCapabilities: Boolean): JSONObject {
        val body = JSONObject()
        if (acceptCapabilities) body.put("accept_capabilities", true)
        return client.nativeRequest("/api/dashboard/agent-plugins/beacon/update", "POST", body)
    }

    /** Waits for the restart [pid] to finish and the gateway to serve again (45 s at most). */
    private suspend fun awaitRestart(pid: Int): RestartResult = withTimeoutOrNull(45_000) {
        repeat(30) {
            delay(1000)
            val status = try { client.nativeRequest("/api/plugins/beacon/admin/plugin/restart/status", params = mapOf("pid" to pid.toString())) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { return@repeat }
            if (status.optInt("pid") != pid || status.optBoolean("failed")) return@withTimeoutOrNull RestartResult.Failed
            if (status.opt("completed") == true && HermesPluginInstaller(client).gateway().running) return@withTimeoutOrNull RestartResult.Completed
        }
        RestartResult.Pending
    } ?: RestartResult.Pending

    override suspend fun restart(last: RestartMemo, remember: (RestartMemo) -> Unit): RestartResult = try {
        run {
            // A restart this phone already asked for is polled first: asking again while it runs would start a second one.
            if (last.inFlight(clock())) {
                when (awaitRestart(last.pid)) {
                    RestartResult.Completed -> { remember(last.copy(completed = true)); return@run RestartResult.Completed }
                    RestartResult.Pending -> return@run RestartResult.Pending
                    else -> remember(RestartMemo())
                }
            }
            val response = try { client.nativeRequest("/api/plugins/beacon/admin/plugin/restart", "POST", JSONObject()) }
            // Hermes refuses a restart during an owner call with 409.
            catch (error: ApiFailure) { return@run if (error.status == 409) RestartResult.Call else RestartResult.Failed }
            val pid = response.optInt("restart_pid")
            if (response.opt("restart_started") != true || pid <= 0) {
                return@run when (response.optString("restart_error_code")) {
                    "live_sessions" -> RestartResult.Waiting
                    "owner_call" -> RestartResult.Call
                    // The same pid as the restart before: that one is not new, so wait on it instead of reporting a failure.
                    "stale" -> if (pid > 0 || last.pid > 0) pendingOn(pid.takeIf { it > 0 } ?: last.pid, last, remember) else RestartResult.Pending
                    else -> RestartResult.Failed
                }
            }
            val memo = RestartMemo(pid, clock())
            remember(memo)
            pendingOn(pid, memo, remember, alreadyKnown = true)
        }
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { RestartResult.Failed }

    private suspend fun pendingOn(pid: Int, memo: RestartMemo, remember: (RestartMemo) -> Unit, alreadyKnown: Boolean = false): RestartResult {
        val known = if (alreadyKnown) memo else RestartMemo(pid, if (memo.pid == pid) memo.at else clock()).also(remember)
        return when (val result = awaitRestart(pid)) {
            RestartResult.Completed -> result.also { remember(known.copy(completed = true)) }
            RestartResult.Failed -> result.also { remember(RestartMemo()) }
            else -> result
        }
    }

    /**
     * The plugin's own helper restarts the dashboard and records each step in a file the new dashboard reads, so the phone asks
     * the same route before, during and after. A plugin without the route answers 404 and leaves the restart to the owner.
     */
    override suspend fun restartDashboard(onReconnecting: (Boolean) -> Unit): DashboardRestartResult {
        try {
            val response = try { client.nativeRequest(DASHBOARD_RESTART, "POST", JSONObject()) }
            catch (error: ApiFailure) {
                return when (error.status) { 404, 405 -> DashboardRestartResult.Unsupported; 409 -> DashboardRestartResult.Call; else -> DashboardRestartResult.Failed }
            }
            val code = response.optString("restart_error_code")
            val id = response.optString("restart_id")
            return when {
                code == "live_sessions" -> DashboardRestartResult.Waiting
                code == "unsupported" -> DashboardRestartResult.Unsupported
                // A restart this phone (or another) already started is joined, not started twice.
                id.isNotBlank() && (response.optBoolean("restart_started") || code == "in_progress") -> awaitDashboard(id, onReconnecting)
                else -> DashboardRestartResult.Failed
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { return DashboardRestartResult.Failed }
    }

    /** Asks for the restart's steps once a second (the dashboard is down for part of it, and answers again when it is back) until it ends or two and a half minutes pass. */
    private suspend fun awaitDashboard(id: String, onReconnecting: (Boolean) -> Unit): DashboardRestartResult {
        repeat(DASHBOARD_POLLS) {
            pause(1000)
            val status = try { client.nativeRequest(DASHBOARD_STATUS, params = mapOf("id" to id)).also { onReconnecting(false) } }
            catch (error: CancellationException) { throw error }
            catch (_: NativeAuthExpiredException) { return DashboardRestartResult.Failed }
            catch (_: Exception) { onReconnecting(true); return@repeat }
            when (status.optString("state")) {
                "completed" -> return DashboardRestartResult.Completed
                "failed" -> return if (status.optString("reason") == "live_sessions") DashboardRestartResult.Waiting else DashboardRestartResult.Failed
                "unknown" -> return DashboardRestartResult.Failed
            }
        }
        return DashboardRestartResult.Failed
    }

    private companion object {
        const val DASHBOARD_RESTART = "/api/plugins/beacon/admin/dashboard/restart"
        const val DASHBOARD_STATUS = "/api/plugins/beacon/admin/dashboard/restart/status"
        const val DASHBOARD_POLLS = 150
    }
}
