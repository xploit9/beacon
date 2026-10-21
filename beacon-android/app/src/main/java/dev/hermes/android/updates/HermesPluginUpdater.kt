package dev.hermes.android.updates

import dev.hermes.android.data.BeaconClient
import org.json.JSONObject

internal class HermesPluginUpdater(private val client: BeaconClient) {
    suspend fun version(): PluginVersions {
        val response = client.nativeRequest("/api/plugins/beacon/mobile/plugin/version")
        val versions = PluginVersions(response.getString("running"), response.getString("installed"))
        require(versions.running.isNotBlank() && versions.installed.isNotBlank())
        return versions
    }

    suspend fun update(acceptCapabilities: Boolean = false): JSONObject {
        val body = JSONObject()
        if (acceptCapabilities) body.put("accept_capabilities", true)
        return client.nativeRequest("/api/dashboard/agent-plugins/beacon/update", "POST", body)
    }
}
