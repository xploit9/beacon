package dev.hermes.android.data

import org.json.JSONObject

data class NativeSkill(val name: String, val description: String, val category: String, val enabled: Boolean, val provenance: String, val requirements: List<String> = emptyList(), val essential: Boolean = false)
data class NativeSkillContent(val name: String, val content: String, val revision: String = "")

/** Uses the dashboard's profile-scoped skill manager, including its write validation. */
class NativeSkills(private val client: CypherClient) {
    private val profile = client.auth.profile
    var warnings: List<String> = emptyList(); private set
    var snapshot: JSONObject? = null; private set
    private fun scope() = mapOf("profile" to profile).also { require(client.auth.profile == profile) { "Profile changed; reopen Skills" } }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject {
        // A dashboard restart may briefly return a proxy error. Never replay writes.
        repeat(3) { attempt ->
            try { return client.nativeGet(path, scope() + query) }
            catch (error: ApiFailure) {
                if (error.status !in setOf(502, 503, 504) || attempt == 2) throw error
                kotlinx.coroutines.delay(500L * (attempt + 1))
            }
        }
        error("Skills refresh did not finish")
    }

    suspend fun list(): List<NativeSkill> {
        val response = get("/api/plugins/cypher/mobile/skills")
        val rows = parseNativeSkills(response)
        snapshot = response
        val notes = response.optJSONArray("warnings")
        warnings = (0 until (notes?.length() ?: 0)).map { notes!!.getString(it) }
        return rows
    }
    suspend fun read(name: String): NativeSkillContent {
        val response = get("/api/plugins/cypher/mobile/skills/content", mapOf("name" to name))
        require(response.getString("name") == name) { "Skill response does not match selection" }
        return NativeSkillContent(name, response.getString("content"), response.getString("revision"))
    }
    suspend fun toggle(name: String, enabled: Boolean) {
        scope()
        val result = client.nativeGet("/api/skills/toggle", method = "PUT", data = JSONObject().put("name", name).put("enabled", enabled).put("profile", profile))
        require(result.optBoolean("ok") && result.getString("name") == name && result.getBoolean("enabled") == enabled) { "Skill change was not confirmed; refresh Skills before retrying" }
        // Native toggles echo the request even when an essential skill cannot be disabled.
        require(list().singleOrNull { it.name == name }?.enabled == enabled) { "Hermes did not keep this change. Essential skills cannot be disabled; refresh Skills to see the saved state." }
    }
    suspend fun save(name: String, content: String, revision: String) {
        scope()
        require(content.isNotBlank()) { "Skill instructions cannot be empty" }
        require(revision.matches(Regex("[a-f0-9]{64}"))) { "Read the current skill before saving" }
        val result = client.nativeGet("/api/plugins/cypher/mobile/skills/content", method = "PUT", data = JSONObject().put("name", name).put("content", content).put("revision", revision).put("profile", profile))
        require(result.optBoolean("success")) { "Skill save was not confirmed; read the skill before retrying" }
    }
}

internal fun parseNativeSkills(response: JSONObject): List<NativeSkill> {
    val rows = response.getJSONArray("items")
    return (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        val name = row.getString("name")
        require(name.isNotBlank() && row.get("enabled") is Boolean) { "Missing skill name or enabled state" }
        val requirements = row.getJSONArray("requirements")
        require(row.get("essential") is Boolean) { "Missing essential skill state" }
        NativeSkill(name, row.optString("description").takeUnless { it == "null" }.orEmpty(), row.optString("category").takeUnless { it == "null" }.orEmpty(), row.getBoolean("enabled"), row.optString("provenance"), (0 until requirements.length()).map { requirements.getString(it) }, row.getBoolean("essential"))
    }.sortedWith(compareBy({ it.category }, { it.name }))
}
