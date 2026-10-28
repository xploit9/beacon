package dev.hermes.android.tasks
import dev.hermes.android.core.*
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.ui.nativeClockPattern
import dev.hermes.android.ui.nativeDayPattern
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
/** Schedules showed 24-hour time before the phone's setting counted, so that stays the default; callers pass the phone's own. */
internal fun scheduleTime(value: String, timezone: String, empty: String, is24Hour: Boolean = true, locale: java.util.Locale = java.util.Locale.getDefault()): String {
    if (value.isBlank()) return empty
    return runCatching {
        val time = java.time.OffsetDateTime.parse(value)
        // Without the plugin there is no Hermes zone; Hermes wrote the time with its own offset, so that wall clock is right.
        val zone = java.util.TimeZone.getTimeZone(runCatching { java.time.ZoneId.of(timezone) }.getOrElse { time.offset })
        val pattern = nativeDayPattern(locale, withYear = true, withWeekday = false) + ", " + nativeClockPattern(locale, is24Hour)
        java.text.SimpleDateFormat(pattern, locale).also { it.timeZone = zone }.format(java.util.Date.from(time.toInstant()))
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
/** Hermes retired the job: a one-time job that has run, or one that reached its repeat limit. */
internal fun scheduleEnded(job: JSONObject): Boolean = job.string("state") in setOf("completed", "finished")
// Hermes records other terminal statuses too (interrupted, blocked_config); it sets last_error on every failed run and clears it on success.
private fun scheduleRunFailed(job: JSONObject): Boolean = job.string("last_status") in setOf("error", "failed", "delivery_failed") || job.string("last_error").isNotBlank()
// An ended job whose last run failed is Failed, not Completed, so it stays among the schedules that need a look.
internal fun scheduleState(job: JSONObject): String = when {
    schedulePaused(job) -> "Paused"
    scheduleEnded(job) -> if (scheduleRunFailed(job)) "Failed" else "Completed"
    job.string("state") in setOf("running", "claimed") -> "Running"
    scheduleRunFailed(job) || job.string("state") == "error" -> "Failed"
    scheduleGatewayNeedsAttention(job) -> "Waiting"
    job.string("next_run_at").isNotBlank() -> "Waiting"
    else -> "Execution unconfirmed"
}
/** Why the last result could not be delivered (for example a thread that was deleted), as Hermes recorded it; blank otherwise. */
internal fun scheduleDeliveryError(job: JSONObject): String = if (job.string("last_status") == "delivery_failed") job.string("last_delivery_error").take(200) else ""
/** Hermes' own words for the schedule, from the structured schedule or the job: what a card falls back to when it cannot describe it itself. */
internal fun scheduleDisplay(job: JSONObject): String = job.optJSONObject("schedule")?.string("display").orEmpty().ifBlank { job.string("schedule_display") }.ifBlank { if (job.optJSONObject("schedule") == null) job.string("schedule") else "" }
/** A finished one-time job (or one at its repeat limit) whose run went well: it moves out of the active list into "Completed". */
internal fun scheduleCompleted(job: JSONObject): Boolean = scheduleState(job) == "Completed" &&
    (job.optJSONObject("schedule")?.string("kind") == "once" || job.optJSONObject("repeat")?.let { it.optInt("times") > 0 && it.optInt("completed") >= it.optInt("times") } == true)
/** The active schedules first, then the completed ones. */
internal fun splitSchedules(jobs: List<JSONObject>): Pair<List<JSONObject>, List<JSONObject>> = jobs.partition { !scheduleCompleted(it) }
private val callbackScript = Regex("(^|[/\\\\])beacon-callback-[^/\\\\]+\\.py$")
private val callbackName = Regex("^Beacon callback [0-9a-f]{16,}$")
/** A callback the plugin scheduled: a one-time script job named after a long hash, which the owner never sees. */
internal fun scheduleIsCallback(job: JSONObject): Boolean = callbackScript.containsMatchIn(job.string("script")) || callbackName.matches(job.string("name"))
private val proactiveMarker = Regex("^\\s*<!--\\s*BEACON_PROACTIVE_JOB:([A-Za-z0-9_-]+)\\s*-->")
/** The kind of a Beacon-managed background job (`watch_email`, `nightly_memory`), read from the marker its prompt starts with. */
internal fun proactiveKind(job: JSONObject): String? = proactiveMarker.find(job.string("prompt"))?.groupValues?.get(1)
/** Jobs the plugin keeps in shape: only their name is the owner's to change. */
internal fun scheduleNameOnly(job: JSONObject): Boolean = job.string("script").endsWith("beacon-checkups.py") || proactiveKind(job) != null
internal fun scheduleTitle(job: JSONObject, untitled: String, callback: String): String = if (scheduleIsCallback(job)) callback else job.string("name").ifBlank { untitled }
/** Why a callback was scheduled, when the plugin says; blank otherwise. */
internal fun callbackReason(capabilities: JSONObject?, jobId: String): String = capabilities?.optJSONObject("callbacks")?.optJSONObject(jobId)?.string("reason").orEmpty().trim()
/** The plain-words line for each Beacon-managed job kind; scheduleBody's `text` translates it. */
private val proactiveDescriptions = mapOf("watch_email" to "Looks through your connected sources and lets you know when something needs you.", "nightly_memory" to "Tidies up what Beacon remembers about you.")
private val htmlComment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
/** What a card says about the task: a callback's reason, a Beacon-managed job's description, or the owner's own prompt without hidden comments. */
internal fun scheduleBody(job: JSONObject, reason: String, text: (String) -> String = { it }): String = when {
    scheduleIsCallback(job) -> reason
    else -> proactiveKind(job)?.let { text(proactiveDescriptions[it] ?: "Runs in the background for Beacon.") } ?: job.string("prompt").replace(htmlComment, "").trim()
}
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
/** The delivery names the app writes itself; Hermes' own target names and a thread's title are shown as they are. */
internal val DeliveryNameStrings = listOf("Beacon chat", "Save result in Beacon", "A thread", "Untitled thread")
/** [translated] maps [DeliveryNameStrings] to the phone's language, so only the app's own names change. */
internal fun deliveryLabel(deliver: String, names: Map<String, String>, translated: Map<String, String> = emptyMap()): String =
    deliver.split(',').map(String::trim).filter(String::isNotEmpty).joinToString(", ") { (names[it] ?: if (it.startsWith("phone:")) "A thread" else it).let { name -> translated[name] ?: name } }
        .ifEmpty { names.getValue("local").let { translated[it] ?: it } }
internal fun fixedWindowSchedule(body: JSONObject): String {
    val start = body.getInt("_start_hour")
    val end = body.getInt("_end_hour")
    val cadence = body.getInt("_every_hours")
    // The wording is what the owner reads: only messages that start with Enter, Choose or Use reach the form (safeLocalErrorText).
    require(start in 0..23 && end in 1..24 && start < end) { "Choose a start hour before the end hour." }
    require(cadence in 1..12) { "Choose a gap of 1 to 12 hours." }
    val last = if(body.optBoolean("_include_end")) end else end - 1
    require(last <= 23) { "Choose an end hour of 23 or earlier, or turn off Include the end hour." }
    return "0 $start-$last/$cadence * * *"
}
/** The `schedule` a submitted form body saves, built from its schedule fields; Hermes' own string when the owner changed nothing about it. */
internal fun formSchedule(d: JSONObject, now: java.time.LocalDateTime? = null): String {
    val state = scheduleStateFrom { key -> d.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty() }
    return scheduleToSave(state, d.optJSONObject("_original")?.string("schedule").orEmpty(), runCatching { java.time.ZoneId.of(d.string("_timezone")) }.getOrNull(), now)
}
internal class NativeSchedules(private val c: BeaconClient) {
    suspend fun call(path: String, q: Map<String,String>, method: String, body: JSONObject?): JSONObject {
        val d = body ?: JSONObject(q)
        if (path == "/api/crons/create" || path == "/api/crons/update" && (d.has("schedule") || d.has("_schedule_mode"))) {
            val schedule = formSchedule(d)
            // A window's hours are read on the profile's clock, so a changed one is saved only when that clock is still the one the form showed.
            if (ScheduleMode.of(d.string("_schedule_mode")) == ScheduleMode.Fixed && schedule != d.optJSONObject("_original")?.string("schedule").orEmpty()) {
                val expected = d.string("_timezone")
                val actual = c.nativeGet("/api/plugins/beacon/mobile/tasks/capabilities").string("timezone")
                require(expected.isNotBlank() && expected == actual) { "Refresh and reopen this form before saving. The time zone changed or can't be confirmed." }
            }
            d.put("schedule", schedule)
            require(!schedule.contains(Regex("(?i)every.*(?:to|random)"))) { "Choose one interval, such as every 2h." }
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
