package dev.hermes.android.updates

import dev.hermes.android.data.BeaconClient
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal class HermesPluginUpdater(private val client: BeaconClient) : PluginEndpoint {
    override suspend fun version(): PluginVersions {
        val response = client.nativeRequest("/api/plugins/beacon/mobile/plugin/version")
        val versions = PluginVersions(response.getString("running"), response.getString("installed"))
        require(versions.running.isNotBlank() && versions.installed.isNotBlank())
        return versions
    }

    override suspend fun update(acceptCapabilities: Boolean): JSONObject {
        val body = JSONObject()
        if (acceptCapabilities) body.put("accept_capabilities", true)
        return client.nativeRequest("/api/dashboard/agent-plugins/beacon/update", "POST", body)
    }

    override suspend fun restart(): Boolean = try {
        val response = client.nativeRequest("/api/plugins/beacon/admin/plugin/restart", "POST", JSONObject())
        val pid = response.optInt("restart_pid")
        if (response.opt("restart_started") != true || pid <= 0) false
        else withTimeoutOrNull(45_000) {
            repeat(30) {
                delay(1000)
                val status = try { client.nativeRequest("/api/plugins/beacon/admin/plugin/restart/status", params = mapOf("pid" to pid.toString())) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { return@repeat }
                if (status.optInt("pid") != pid || status.optBoolean("failed")) return@withTimeoutOrNull false
                if (status.opt("completed") == true && HermesPluginInstaller(client).gateway().running) return@withTimeoutOrNull true
            }
            false
        } ?: false
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { false }
}
