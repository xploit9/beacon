package dev.hermes.android.tasks
import dev.hermes.android.core.*
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.ApiFailure
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
internal fun scheduleTime(value: String, timezone: String, empty: String): String {
    if (value.isBlank()) return empty
    return runCatching {
        val time = java.time.OffsetDateTime.parse(value)
        val zone = java.time.ZoneId.of(timezone)
        time.atZoneSameInstant(zone).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
    }.getOrDefault("Time unavailable")
}
internal val editableScheduleFields = setOf("name", "prompt", "schedule", "deliver", "skills", "model", "provider")
internal fun schedulePayload(body: JSONObject): JSONObject = JSONObject().apply { editableScheduleFields.forEach { key -> if (body.has(key) && !body.isNull(key)) put(key, body.opt(key)) } }
internal fun scheduleUpdates(body: JSONObject): JSONObject = schedulePayload(body).apply {
    body.optJSONObject("_original")?.let { original -> keys().asSequence().toList().forEach { key -> if (opt(key).toString() == original.opt(key).toString()) remove(key) } }
}
internal fun nativeScheduleInput(job: JSONObject): String {
    val schedule=job.optJSONObject("schedule") ?: return job.string("schedule")
    return when(schedule.string("kind")) {
        "cron" -> schedule.string("expr", schedule.string("expression"))
        "once" -> schedule.string("run_at")
        "interval" -> schedule.optInt("minutes").takeIf { it > 0 }?.let { "every ${it}m" }.orEmpty()
        else -> schedule.string("expr", schedule.string("expression", schedule.string("display")))
    }
}
// Hermes's own rule (hermes_cli/web_routers/cron.py): disabled or state "paused" means paused. The legacy "paused"
// flag can stay true on a job that is enabled and still running, so it is read only when Hermes sends neither field.
internal fun schedulePaused(job: JSONObject): Boolean = when {
    job.string("state") in setOf("completed", "finished") -> false
    job.has("enabled") || job.has("state") -> job.string("state") == "paused" || (job.has("enabled") && !job.optBoolean("enabled"))
    else -> job.optBoolean("paused")
}
internal fun scheduleGatewayNeedsAttention(job: JSONObject): Boolean = job.optDouble("scheduler_heartbeat_age_s", 0.0) > 120
internal fun scheduleState(job: JSONObject): String = when {
    schedulePaused(job) -> "Paused"
    job.string("state") in setOf("completed", "finished") -> "Completed"
    job.string("state") in setOf("running", "claimed") -> "Running"
    job.string("last_status") in setOf("error", "failed", "delivery_failed") || job.string("state") == "error" -> "Failed"
    scheduleGatewayNeedsAttention(job) -> "Waiting"
    job.string("next_run_at").isNotBlank() -> "Waiting"
    else -> "Execution unconfirmed"
}
/** Why the last result could not be delivered (for example a thread that was deleted), as Hermes recorded it; blank otherwise. */
internal fun scheduleDeliveryError(job: JSONObject): String = if (job.string("last_status") == "delivery_failed") job.string("last_delivery_error").take(200) else ""
/** `phone:<id>` sends a job's result into that thread, through the Beacon plugin's phone delivery; `deliver` may list several targets. */
internal fun threadTarget(threadId: String) = "phone:$threadId"
internal fun deliversTo(job: JSONObject, target: String): Boolean = job.string("deliver").split(',').any { it.trim() == target }
private fun threadRow(threadId: String, title: String) = json("id" to threadTarget(threadId), "name" to title.ifBlank { "Untitled thread" }, "untitled" to title.isBlank(), "home_target_set" to true, "home_env_var" to null, "thread" to true)
/** The editor's "Deliver to" list: Hermes' own targets with the phone platform named Beacon chat, and each thread right after it. */
internal fun deliveryTargets(native: JSONObject, threads: List<JSONObject>): JSONObject {
    val own = native.rows("targets").map { if (it.string("id") == "phone") it.copy().put("name", "Beacon chat") else it }
    val at = own.indexOfFirst { it.string("id") == "phone" }.let { if (it < 0) own.size else it + 1 }
    val rows = threads.filter { it.string("id").isNotBlank() }.map { threadRow(it.string("id"), it.string("title")) }
    return native.copy().put("targets", JSONArray(own.take(at) + rows + own.drop(at)))
}
/** The thread's own entry, for a list that has not loaded it yet. */
internal fun withThreadTarget(targets: List<JSONObject>, threadId: String, title: String): List<JSONObject> {
    val id = threadTarget(threadId)
    if (targets.none { it.string("id") == id }) return targets + threadRow(threadId, title)
    // Hermes may not hold a new thread's title yet; the open thread's own name beats "Untitled thread".
    if (title.isBlank() || targets.none { it.string("id") == id && it.optBoolean("untitled") }) return targets
    return targets.map { if (it.string("id") == id && it.optBoolean("untitled")) threadRow(threadId, title) else it }
}
internal fun deliveryNames(targets: List<JSONObject>): Map<String, String> =
    mapOf("local" to "Save result in Beacon") + targets.filter { it.string("id") != "local" }.associate { it.string("id") to it.string("name", it.string("id")) }
internal fun deliveryLabel(deliver: String, names: Map<String, String>): String =
    deliver.split(',').map(String::trim).filter(String::isNotEmpty).joinToString(", ") { names[it] ?: if (it.startsWith("phone:")) "A thread" else it }.ifEmpty { names.getValue("local") }
internal fun fixedWindowSchedule(body: JSONObject): String {
    val start = body.getInt("_start_hour")
    val end = body.getInt("_end_hour")
    val cadence = body.getInt("_every_hours")
    require(start in 0..23 && end in 1..24 && start < end) { "Window must start before it ends on the same day (hours 0-24)" }
    require(cadence in 1..12) { "Cadence must be a fixed interval of 1-12 hours" }
    val last = if(body.optBoolean("_include_end")) end else end - 1
    require(last <= 23) { "24:00 cannot be an inclusive end; use an exclusive end" }
    return "0 $start-$last/$cadence * * *"
}
fun fixedWindowPreview(values: Map<String,String>): String? = runCatching {
    val start=values["_start_hour"]?.toIntOrNull() ?: return@runCatching null
    val end=values["_end_hour"]?.toIntOrNull() ?: return@runCatching null
    val every=values["_every_hours"]?.toIntOrNull() ?: return@runCatching null
    val inclusive=values["_include_end"].toBoolean()
    fixedWindowSchedule(json("_start_hour" to start,"_end_hour" to end,"_every_hours" to every,"_include_end" to inclusive))
    ((start..(if(inclusive) end else end-1)) step every).joinToString(", ") { "%02d:00".format(it) }
}.getOrNull()
internal class NativeSchedules(private val c: BeaconClient) {
    suspend fun call(path: String, q: Map<String,String>, method: String, body: JSONObject?): JSONObject {
        val d = body ?: JSONObject(q)
        if (path == "/api/crons/create" || path == "/api/crons/update" && (d.has("schedule") || d.string("_schedule_mode") == "Fixed window")) {
            if(d.string("_schedule_mode") == "Fixed window") {
                val expected=d.string("_timezone")
                val actual=c.nativeGet("/api/plugins/beacon/mobile/tasks/capabilities").string("timezone")
                require(expected.isNotBlank() && expected == actual) { "Profile timezone changed or is unconfirmed. Reopen the task form before saving." }
                d.put("schedule", fixedWindowSchedule(d))
            } else require(d.string("schedule").isNotBlank()) { "When is required. Use every 2h, in 30m, a daily time, or choose Fixed window." }
            require(!d.string("schedule").contains(Regex("(?i)every.*(?:to|random)"))) { "Choose one interval, such as every 2h, or use Fixed window." }
        }
        val profile = mapOf("profile" to c.auth.profile)
        if (path == "/api/crons") { val result = c.nativeGet("/api/cron/jobs", profile); return result.put("jobs", result.optJSONArray("items") ?: result.optJSONArray("jobs") ?: JSONArray()) }
        if (path == "/api/crons/delivery-options") {
            val native = c.nativeGet("/api/cron/delivery-targets", profile)
            // Threads come from the optional plugin; without it the editor offers Hermes' own targets.
            val threads = try { c.nativeGet("/api/plugins/beacon/mobile/threads").rows("threads") } catch (e: Exception) { if (e is CancellationException) throw e; emptyList<JSONObject>() }
            return deliveryTargets(native, threads)
        }
        if (path == "/api/crons/create") return c.nativeGet("/api/cron/jobs", profile, "POST", schedulePayload(d).apply { listOf("provider", "model", "name").forEach { if(string(it).isBlank()) remove(it) }; if(rows("skills").isEmpty() && optJSONArray("skills")?.length() == 0) remove("skills") })
        val id = d.string("job_id")
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid schedule identity" }
        val action = path.substringAfterLast('/')
        val suffix = when(action) { "run" -> "/trigger"; "pause", "resume" -> "/$action"; "history" -> "/runs"; "status", "update", "delete" -> ""; else -> throw ApiFailure(501,"Open a native result conversation from task history") }
        val verb = when(action) { "delete" -> "DELETE"; "update" -> "PUT"; "status", "history" -> "GET"; else -> "POST" }
        val updates = scheduleUpdates(d)
        return c.nativeGet("/api/cron/jobs/$id$suffix", profile, verb, if(action == "update") json("updates" to updates) else if(verb == "POST") JSONObject() else null)
    }
}
