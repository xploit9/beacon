package dev.hermes.android.updates

import dev.hermes.android.core.PLUGIN_VERSION_PATTERN
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.BeaconClient
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal class HermesPluginUpdater(private val client: BeaconClient, private val clock: () -> Long = System::currentTimeMillis) : PluginEndpoint {
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
}
