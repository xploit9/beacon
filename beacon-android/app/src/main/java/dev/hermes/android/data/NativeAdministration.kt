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
    val updateStatusFresh: Boolean = false, val hostBusy: Boolean = false,
    val checkpoints: List<NativeCheckpoint> = emptyList(), val checkpointBytes: Long = 0,
) {
    val canApply get() = updateStatusFresh && !hostBusy && error.isBlank() && update?.optBoolean("can_apply") == true && update.optBoolean("update_available") && !loading && !applying && (requestedAt.isBlank() || outcome in terminalUpdateOutcomes)
    val outcome: String get() {
        val resolved = confirmedUpdateOutcome(action, receipt, requestedActionId, requestedAt)
        return if (resolved in terminalUpdateOutcomes) resolved else if (!updateStatusFresh) "unconfirmed"
            else if (requestedAt.isBlank() && hostBusy) "running" else resolved
    }
    val canReviewUnknown get() = updateStatusFresh && !hostBusy && requestedAt.isNotBlank() && outcome == "unconfirmed" && !applying
    val updateWarnings get() = updateReceiptWarnings(correlatedUpdateReceipt(action, receipt, requestedActionId, requestedAt))
}

private val terminalUpdateOutcomes = setOf("success", "partial", "failed", "refused", "interrupted")

private fun terminalUpdateOutcome(receipt: JSONObject?): String? {
    val outcome = receipt?.string("outcome").orEmpty()
    if (outcome == "interrupted") return outcome // Native reconciliation may omit finished_at.
    return outcome.takeIf { receipt != null && it in terminalUpdateOutcomes && runCatching {
        Instant.parse(receipt.string("finished_at")) >= Instant.parse(receipt.string("started_at"))
    }.getOrDefault(false) }
}

fun NativeServerState.withUpdateStatus(response: JSONObject): NativeServerState {
    val next = copy(action = response.optJSONObject("action"), receipt = response.optJSONObject("receipt"),
        hostBusy = response.optBoolean("host_busy") || response.optJSONObject("action")?.optBoolean("running") == true,
        updateStatusFresh = true, applying = false, notice = "")
    return next.copy(applying = next.outcome == "running")
}

/** A root-wide latest receipt never substitutes for the action this phone requested. */
fun correlatedUpdateReceipt(action: JSONObject?, response: JSONObject?, actionId: String, requestedAt: String): JSONObject? {
    if (actionId.isBlank() || requestedAt.isBlank()) return null
    val candidates = listOfNotNull(response?.optJSONObject("receipt"), action?.optJSONObject("receipt")).filter { receipt ->
        receipt.string("action_id") == actionId && runCatching {
            Instant.parse(receipt.string("started_at")) >= Instant.parse(requestedAt)
        }.getOrDefault(false)
    }
    return candidates.firstOrNull { terminalUpdateOutcome(it) != null } ?: candidates.firstOrNull()
}

/** Old receipts and server reconnection alone cannot prove this update completed. */
fun confirmedUpdateOutcome(action: JSONObject?, response: JSONObject?, actionId: String, requestedAt: String): String {
    val receipt = correlatedUpdateReceipt(action, response, actionId, requestedAt)
    terminalUpdateOutcome(receipt)?.let { return it }
    val reportedId = action?.string("action_id").orEmpty().ifBlank { action?.optJSONObject("receipt")?.string("action_id").orEmpty() }
    return if (action?.optBoolean("running") == true && actionId.isNotBlank() && reportedId == actionId) "running" else "unconfirmed"
}

private fun updateReceiptWarnings(receipt: JSONObject?): List<String> = buildList {
    if (receipt == null) return@buildList
    fun text(label: String, value: String) { if (value.isNotBlank()) add("$label: $value") }
    fun rows(parent: JSONObject?, key: String, label: String) {
        val entries = parent?.optJSONArray(key) ?: return
        for (index in 0 until entries.length()) {
            val row = entries.optJSONObject(index)
            val detail = row?.let { listOf("step", "kind", "profile", "unit", "reason", "message", "command", "restart").map { key -> it.string(key) }.filter(String::isNotBlank).joinToString(" · ") }
                ?.ifBlank { row.toString() } ?: entries.optString(index)
            text(label, detail)
        }
    }
    rows(receipt, "followups", "Follow-up")
    receipt.optJSONObject("user_action")?.let { text("Action needed", "${it.string("step")}: ${it.string("reason")}") }
    text("Update", receipt.string("stop_reason"))
    rows(receipt, "pending_manual_serves", "Manual restart")
    receipt.optJSONObject("gateway_restart")?.let { restart ->
        if (restart.optBoolean("incomplete")) add("Gateway restart needs attention")
        text("Gateway restart", restart.string("phase_error"))
        rows(restart, "failed_units", "Gateway restart failed")
        restart.optJSONObject("fresh_recovery")?.let { recovery ->
            rows(recovery, "failed", "Restart failed")
            rows(recovery, "skipped", "Restart skipped")
            rows(recovery, "stale_runtimes", "Runtime needs restart")
            rows(recovery.optJSONObject("serve_units"), "failed", "Serve restart failed")
        }
    }
}.distinct()

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
