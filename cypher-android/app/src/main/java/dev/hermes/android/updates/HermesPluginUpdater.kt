package dev.hermes.android.updates

import dev.hermes.android.data.CypherClient
import org.json.JSONObject

internal class HermesPluginUpdater(private val client: CypherClient) {
    suspend fun version(): PluginVersions {
        val response = client.nativeRequest("/api/plugins/cypher/mobile/plugin/version")
        val versions = PluginVersions(response.getString("running"), response.getString("installed"))
        require(versions.running.isNotBlank() && versions.installed.isNotBlank())
        return versions
    }

    suspend fun update(acceptCapabilities: Boolean = false): JSONObject {
        val body = JSONObject()
        if (acceptCapabilities) body.put("accept_capabilities", true)
        return client.nativeRequest("/api/dashboard/agent-plugins/cypher/update", "POST", body)
    }
}
