package dev.hermes.android.data

import dev.hermes.android.core.string
import org.json.JSONObject
import java.time.Instant

data class NativeBot(val profile: String, val title: String, val description: String, val soul: String, val provider: String, val model: String) {
    /** The body of Hermes' PUT /api/profiles/{name}/model: the last writer wins, as in Hermes' own dashboard. */
    fun saveModel(provider: String, model: String) = JSONObject().put("provider", provider).put("model", model)
    companion object {
        /** [row] is the profile's row in GET /api/profiles (the title rule of the profile list), [soul] the content of GET /api/profiles/{name}/soul. */
        fun fromNative(row: JSONObject, soul: String) = NativeBot(row.getString("name"), row.string("bot_title").ifBlank { row.string("display_name") }.ifBlank { row.getString("name") },
            row.string("description"), soul, row.string("provider"), row.string("model"))
    }
}
data class NativeBotSummary(val profile: String, val title: String, val purpose: String)
data class NativeBotsState(val profiles: List<NativeBotSummary> = emptyList(), val selected: NativeBot? = null, val loading: Boolean = false, val saving: Boolean = false, val error: String = "", val notice: String = "", val editing: Boolean = false, val draftProvider: String = "", val draftModel: String = "",
    /** The (profile, provider, model) a save wrote but could not read back: shown as saved, and the next Save of that same bot trusts the stored pair that comes back instead of calling it a change made elsewhere. Gone when a bot is opened or closed. */
    val unconfirmedModel: Triple<String, String, String>? = null)
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
    /** The first sentence of an ERROR or FATAL line Hermes logged as recently as its latest log activity; blank when there is none (see [recentServerError]). */
    val recentError: String = "",
    /** True once this page has asked Hermes (or been given a saved answer): before that the empty state is not an answer, and the page says it is checking. */
    val asked: Boolean = false,
    /** The pages Hermes has answered at least once, even with nothing in the answer (no log lines, no backups): an empty answer is still the answer, so it never draws a skeleton again. */
    val answered: Set<String> = emptySet(),
    val savedLogCount: Int? = null,
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

/**
 * Hermes' own update status, in the shape the page reads: the update's action (GET /api/actions/hermes-update/status, with its receipt summary) and the
 * latest full receipt (GET /api/hermes/update/receipt; 404 until an update has run). The host is busy while that action runs.
 */
internal suspend fun BeaconClient.nativeUpdateStatus(): JSONObject {
    val action = nativeGet("/api/actions/hermes-update/status", mapOf("lines" to "100"))
    val receipt = try { nativeGet("/api/hermes/update/receipt") } catch (e: ApiFailure) { if (e.status == 404) null else throw e }
    return JSONObject().put("action", action).put("receipt", receipt ?: JSONObject.NULL).put("host_busy", action.optBoolean("running"))
}

fun NativeServerState.withUpdateStatus(response: JSONObject): NativeServerState {
    val next = copy(action = response.optJSONObject("action"), receipt = response.optJSONObject("receipt"),
        hostBusy = response.optBoolean("host_busy") || response.optJSONObject("action")?.optBoolean("running") == true,
        updateStatusFresh = true, applying = false, notice = "")
    return next.copy(applying = next.outcome == "running")
}

/**
 * A root-wide latest receipt never substitutes for the action this phone requested. Hermes mints a fresh action id for every update it starts, so the id
 * alone says which run a receipt belongs to; [requestedAt] only says that this phone asked for one.
 */
fun correlatedUpdateReceipt(action: JSONObject?, response: JSONObject?, actionId: String, requestedAt: String): JSONObject? {
    if (actionId.isBlank() || requestedAt.isBlank()) return null
    val candidates = listOfNotNull(response?.optJSONObject("receipt"), action?.optJSONObject("receipt")).filter { it.string("action_id") == actionId }
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
    rows(receipt, "pending_manual_serves", "Restart by hand")
    receipt.optJSONObject("gateway_restart")?.let { restart ->
        if (restart.optBoolean("incomplete")) add("Hermes restart needs attention")
        text("Hermes restart", restart.string("phase_error"))
        rows(restart, "failed_units", "Hermes didn't restart")
        restart.optJSONObject("fresh_recovery")?.let { recovery ->
            rows(recovery, "failed", "Restart failed")
            rows(recovery, "skipped", "Restart skipped")
            rows(recovery, "stale_runtimes", "Needs a restart")
            rows(recovery.optJSONObject("serve_units"), "failed", "A Hermes service didn't restart")
        }
    }
}.distinct()

/**
 * Hermes' own verdict, plus what its log says. Hermes' /api/status builds `overall` from a read-only probe that only reports a corrupt state.db
 * (gateway/readiness.py `_probe_state_db`), so a gateway that refuses writes after state.db was replaced under it (hermes_state_errors.py
 * `_STATE_DB_REPLACED_MSG`) still reads "ok". A [recentError] turns an "ok" into "degraded"; a verdict that failed to load stays as it is.
 */
fun parseServerStatus(status: JSONObject, previous: NativeServerState = NativeServerState(), recentError: String = ""): NativeServerState {
    val components = status.optJSONObject("components")
    val rows = components?.keys()?.asSequence()?.map { key ->
        val row = components?.optJSONObject(key) ?: JSONObject()
        val detail = when (key) { "platforms" -> "${row.optInt("connected")} / ${row.optInt("configured")} connected"; "gateway" -> row.string("state"); else -> row.string("reason") }
        NativeComponent(key, row.string("status", "unknown"), detail)
    }?.toList().orEmpty()
    val overall = status.string("overall").let { if (it == "ok" && recentError.isNotBlank()) "degraded" else it }
    return previous.copy(version = status.string("version"), overall = overall, gatewayState = status.string("gateway_state"), activeAgents = status.optInt("active_agents"), components = rows, recentError = recentError)
}

private val serverLogLine = Regex("""^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})(?:[,.]\d+)? (DEBUG|INFO|WARNING|ERROR|CRITICAL|FATAL)\s+(.*)$""")
/** What Hermes writes before the message (hermes_logging.py `_LOG_FORMAT`): an optional ` [session]` tag, then `logger: `. */
private val serverLogger = Regex("""^(?:\[[^\]]*]\s+)?[\w.\-]+: """)
private val serverAlarmLevels = setOf("CRITICAL", "FATAL")
private const val RecentErrorSeconds = 10 * 60L
/** Hermes writes this once each time a gateway process starts (gateway/run.py:4542, from gateway/run_bootstrap.py:388-466), in its gateway log. */
internal const val GATEWAY_START_LINE = "Gateway housekeeping started"
private const val ErrorLineMax = 160

private class ServerLogLine(val at: java.time.LocalDateTime, val level: String, val message: String) {
    val body = message.replaceFirst(serverLogger, "")
    /** CRITICAL or FATAL, or a refused write: Hermes logs that at ERROR with its own "FATAL:" head (hermes_state.py:1288, hermes_state_errors.py:218-262). */
    val alarming = level in serverAlarmLevels || body.startsWith("FATAL:")
}

private fun serverLogLine(line: String): ServerLogLine? = serverLogLine.matchEntire(line.trim())?.let { m ->
    runCatching { ServerLogLine(java.time.LocalDateTime.parse("${m.groupValues[1]}T${m.groupValues[2]}"), m.groupValues[3], m.groupValues[4]) }.getOrNull()
}

/**
 * The newest CRITICAL or FATAL line of Hermes' error log, or a refused write, that is still current, as one readable sentence: a refused write until a
 * gateway start follows it, any other within ten minutes of the log's own latest activity ([activity], the newest lines of the agent log). Both clocks are Hermes' own, so no time zone is guessed. Routine
 * ERROR lines (a dropped poll, one failed tool) stay in Logs. A traceback under the line is skipped; with no activity to compare against, or nothing
 * readable, the answer is blank rather than a guess.
 */
internal fun recentServerError(errors: List<String>, activity: List<String>, gatewayStarts: List<String> = emptyList()): String {
    val latest = activity.asReversed().firstNotNullOfOrNull { serverLogLine(it) } ?: return ""
    // A refused write is logged once and every later write is refused silently (hermes_state.py:1278-1289, :1321-1327), so it stays current until the
    // newest gateway start ([gatewayStarts], [GATEWAY_START_LINE]) comes after it. With no start line in view, the ten-minute window decides, as for the rest.
    val started = gatewayStarts.asReversed().firstNotNullOfOrNull { serverLogLine(it) }?.at
    fun ServerLogLine.current() = if (started != null && body.startsWith("FATAL:")) !started.isAfter(at)
        else java.time.Duration.between(at, latest.at).seconds <= RecentErrorSeconds
    // The newest alarm that is still current wins: a later CRITICAL line that aged out cannot hide a refused write behind it.
    val error = errors.asReversed().mapNotNull { serverLogLine(it)?.takeIf(ServerLogLine::alarming) }.sortedByDescending { it.at }.firstOrNull { it.current() } ?: return ""
    val body = error.body
    val sentence = body.indexOf(". ").let { if (it >= 0) body.substring(0, it + 1) else body }.trim()
    return if (sentence.length > ErrorLineMax) sentence.take(ErrorLineMax - 1).trimEnd() + "…" else sentence
}
/**
 * What [recentServerError] reads from Hermes' `/api/logs` ([read] takes the query and returns its lines): the last 100 ERROR lines, the newest agent-log
 * line, and two of Hermes' searches over a file's last 2,000 matching lines (hermes_cli/web_routers/status.py get_logs): the newest gateway start, and
 * the refused writes, logged once and pushed out of the 100-line tail by routine tracebacks within hours. A failed search leaves the tail and the
 * ten-minute window to decide.
 */
internal suspend fun readRecentServerError(read: suspend (Map<String, String>) -> List<String>): String {
    suspend fun search(query: Map<String, String>) = try { read(query) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
    val errors = read(mapOf("file" to "errors", "level" to "ERROR", "lines" to "100"))
    val activity = read(mapOf("file" to "agent", "lines" to "1"))
    val starts = search(mapOf("file" to "gateway", "search" to GATEWAY_START_LINE, "lines" to "1"))
    val refused = search(mapOf("file" to "errors", "level" to "ERROR", "search" to "FATAL:", "lines" to "20"))
    return recentServerError(errors + refused, activity, starts)
}
fun parseCheckpoints(data: JSONObject, previous: NativeServerState = NativeServerState()): NativeServerState {
    val rows = data.optJSONArray("sessions")
    return previous.copy(checkpointBytes = data.optLong("total_bytes"), checkpoints = (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it) }.map { NativeCheckpoint(it.getString("session"), it.optInt("files"), it.optLong("bytes")) })
}
