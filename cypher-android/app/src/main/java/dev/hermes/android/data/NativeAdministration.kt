package dev.hermes.android.data

import dev.hermes.android.core.string
import org.json.JSONObject
import java.time.Instant

data class NativeBot(val profile: String, val title: String, val description: String, val soul: String, val provider: String, val model: String, val revision: String) {
    fun saveModel(provider: String, model: String) = JSONObject().put("provider", provider).put("model", model).put("revision", revision)
    companion object { fun parse(row: JSONObject) = NativeBot(row.getString("profile"), row.string("title"), row.string("description"), row.string("soul"), row.string("provider"), row.string("model"), row.getString("revision")) }
}
data class NativeBotSummary(val profile: String, val title: String, val purpose: String)
data class NativeBotsState(val profiles: List<NativeBotSummary> = emptyList(), val selected: NativeBot? = null, val loading: Boolean = false, val saving: Boolean = false, val error: String = "", val notice: String = "", val editing: Boolean = false, val draftProvider: String = "", val draftModel: String = "")
data class NativeComponent(val name: String, val status: String, val detail: String)
data class NativeCheckpoint(val session: String, val files: Int, val bytes: Long)
data class NativeServerState(
    val loading: Boolean = false, val error: String = "", val notice: String = "",
    val version: String = "", val overall: String = "", val gatewayState: String = "", val activeAgents: Int = 0,
    val components: List<NativeComponent> = emptyList(), val logFile: String = "errors", val logSearch: String = "", val logs: List<String> = emptyList(),
    val update: JSONObject? = null, val action: JSONObject? = null, val receipt: JSONObject? = null,
    val applying: Boolean = false, val requestedActionId: String = "", val requestedAt: String = "",
    val checkpoints: List<NativeCheckpoint> = emptyList(), val checkpointBytes: Long = 0,
) {
    val canApply get() = update?.optBoolean("can_apply") == true && update.optBoolean("update_available") && !loading && !applying && (requestedAt.isBlank() || outcome in setOf("success", "partial", "failed", "refused"))
    val outcome get() = confirmedUpdateOutcome(action, receipt, requestedActionId, requestedAt)
    val canReviewUnknown get() = requestedAt.isNotBlank() && outcome == "unconfirmed" && action?.has("running") == true && !action.optBoolean("running")
}

/** Old receipts and server reconnection alone cannot prove this update completed. */
fun confirmedUpdateOutcome(action: JSONObject?, response: JSONObject?, actionId: String, requestedAt: String): String {
    if (action?.optBoolean("running") == true) return "running"
    val receipt = response?.optJSONObject("receipt") ?: action?.optJSONObject("receipt") ?: return "unconfirmed"
    if (requestedAt.isBlank()) return "unconfirmed"
    val newer = runCatching { Instant.parse(receipt.string("started_at")) >= Instant.parse(requestedAt) }.getOrDefault(false)
    val reportedId = action?.string("action_id").orEmpty()
    if (!newer || (actionId.isNotBlank() && reportedId.isNotBlank() && reportedId != actionId)) return "unconfirmed"
    if (receipt.string("finished_at").isBlank()) return "running"
    return receipt.string("outcome").takeIf { it in setOf("success", "partial", "failed", "refused") } ?: "unconfirmed"
}

fun parseServerStatus(status: JSONObject, previous: NativeServerState = NativeServerState()): NativeServerState {
    val components = status.optJSONObject("components")
    val rows = components?.keys()?.asSequence()?.map { key ->
        val row = components?.optJSONObject(key) ?: JSONObject()
        val detail = when (key) { "platforms" -> "${row.optInt("connected")} / ${row.optInt("configured")} connected"; "gateway" -> row.string("state"); else -> row.string("reason") }
        NativeComponent(key, row.string("status", "unknown"), detail)
    }?.toList().orEmpty()
    return previous.copy(version = status.string("version"), overall = status.string("overall"), gatewayState = status.string("gateway_state"), activeAgents = status.optInt("active_agents"), components = rows)
}
fun parseCheckpoints(data: JSONObject, previous: NativeServerState = NativeServerState()): NativeServerState {
    val rows = data.optJSONArray("sessions")
    return previous.copy(checkpointBytes = data.optLong("total_bytes"), checkpoints = (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it) }.map { NativeCheckpoint(it.getString("session"), it.optInt("files"), it.optLong("bytes")) })
}
