package dev.hermes.android.data

import dev.hermes.android.core.string
import org.json.JSONObject

data class NativeMemoryDocument(val target: String, val content: String, val revision: String, val enabled: Boolean, val limit: Int) {
    val title get() = if (target == "memory") "Agent notes" else "About you"
    fun saveBody(draft: String) = JSONObject().put("target", target).put("content", draft).put("revision", revision)
}

data class NativeMemoryState(
    val scope: String,
    val provider: String = "",
    val providerReady: Boolean = true,
    val documents: List<NativeMemoryDocument> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String = "",
    val message: String = "",
    val editing: String = "",
    val draft: String = "",
) {
    val activeLabel get() = provider.ifBlank { "Built-in profile memory" }
    companion object {
        fun parse(scope: String, status: JSONObject, notes: JSONObject): NativeMemoryState {
            val active = status.string("active").takeUnless { it in listOf("", "builtin", "none", "disabled", "off") }.orEmpty()
            val providers = status.optJSONArray("providers")
            val selected = (0 until (providers?.length() ?: 0)).mapNotNull { providers?.optJSONObject(it) }.firstOrNull { it.string("name") == active }
            val rows = notes.optJSONArray("documents")
            val documents = (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it) }.filter { it.string("target") in listOf("memory", "user") }.map {
                NativeMemoryDocument(it.getString("target"), it.getString("content"), it.getString("revision"), it.getBoolean("enabled"), it.getInt("limit"))
            }
            return NativeMemoryState(scope, active, active.isBlank() || (selected?.optBoolean("available") == true && selected.optBoolean("configured")), documents)
        }
    }
}
