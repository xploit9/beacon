package dev.hermes.android.ui
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import org.json.JSONObject
import dev.hermes.android.tasks.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
@Composable fun TasksScreen(vm: BeaconViewModel) {
    Column(Modifier.fillMaxSize()) {
        Header("Cron", menu = LocalOpenNavigationMenu.current) { IconButton(onClick = { refreshSchedules(vm) }) { Icon(Icons.Rounded.Refresh, uiText("Refresh tasks")) } }
        Schedules(vm, Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth()
            .align(androidx.compose.ui.Alignment.CenterHorizontally))
    }
}
@Composable private fun Schedules(vm: BeaconViewModel, modifier: Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val profileLoading = ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading"))
    var selected by rememberSaveable(ui.profile) { mutableStateOf("") }
    var showPresets by rememberSaveable(ui.profile) { mutableStateOf(false) }
    val actionScope = rememberCoroutineScope()
    var pending by remember(ui.profile) { mutableStateOf("") }
    val crons = data["crons"]
    val gatewayNeedsAttention = crons?.rows("jobs").orEmpty().any { !schedulePaused(it) && scheduleGatewayNeedsAttention(it) }
    val refresh: () -> Unit = { refreshSchedules(vm) }
    LaunchedEffect(ui.origin, ui.owner, ui.profile) { refresh(); if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, selected, crons) { if (selected.isNotBlank()) vm.load("cron-history:$selected", "/api/crons/history", mapOf("job_id" to selected)) }
    val deliveries = data["cron-delivery"]?.rows("targets").orEmpty()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (gatewayNeedsAttention) item { Text("Scheduled jobs aren't running. Start Hermes on your computer.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        item { Button(onClick = { cronForm(vm, null, deliveries, data["task-capabilities"]?.string("timezone").orEmpty(), refresh) }) { Icon(Icons.Rounded.Add, null); Text(uiText("Create schedule"), Modifier.padding(start = 8.dp)) } }
        item {
            val capability = data["task-capabilities"]
            capability?.string("timezone")?.takeIf(String::isNotBlank)?.let { Text("Times in $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = { showPresets = !showPresets }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Call tasks"); Icon(if (showPresets) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) }
            if (showPresets) {
            if (capability == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (recordError(capability).isNotBlank()) { Text("Scheduled task setup isn't available. Check your connection and try again.", color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = refresh, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry task setup status") } }

            OutlinedButton(enabled = capability?.optBoolean("checkups_ready") == true, onClick = { vm.openForm(ActionForm("Create check-up task", "/api/plugins/beacon/mobile/tasks/checkups", emptyList(), explanation = "Create an ordinary native schedule. 3–4 random opportunities daily from 10:00 to before 22:00 in your local time zone; no missed-slot catch-up. Android call permission, quiet and DND policy still apply.", actionLabel = "Create task", onResult = { refresh() })) }) { Text("Create check-up task") }
            if (capability != null && recordError(capability).isBlank() && !capability.optBoolean("checkups_ready")) Text("Set up scheduled callbacks on your computer to enable check-up calls.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (crons?.optBoolean("cron_unavailable") == true) item { EmptyState("Scheduling is unavailable", "Scheduling is not enabled for your Beacon. Check the connection in Settings & more.", Icons.Rounded.EventBusy) }
        if (crons?.has("_error") == true) item { Column { Text("Couldn't load scheduled jobs. Check your connection and try again.", color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = refresh, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") } } }
        if (crons == null) item { Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Loading scheduled jobs…", style = MaterialTheme.typography.bodySmall) } }
        items(crons?.rows("jobs").orEmpty(), key = { it.string("id") }) { job ->
            val id = job.string("id")
            Glass { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(job.string("name", "Scheduled task"), style = MaterialTheme.typography.titleMedium)
                Text(job.string("prompt"), style = MaterialTheme.typography.bodyMedium, maxLines = if (selected == id) Int.MAX_VALUE else 3)
                Text(job.optJSONObject("schedule")?.string("display").orEmpty().ifBlank { job.string("schedule_display") }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("${scheduleState(job)} · ${profileDisplayLabel(job.string("profile", ui.profile), profileRows, profileLoading)} · ${deliveryLabel(job.string("deliver", "local"), deliveryNames(deliveries))}", style = MaterialTheme.typography.bodySmall)
                val timezone = data["task-capabilities"]?.string("timezone").orEmpty()
                Text("Next: ${scheduleTime(job.string("next_run_at"), timezone, "Not scheduled")}", style = MaterialTheme.typography.bodySmall)
                Text("Last: ${scheduleTime(job.string("last_run_at"), timezone, "No recorded run")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                scheduleDeliveryError(job).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (pending == id) LinearProgressIndicator(Modifier.fillMaxWidth())
                // Same actions, order and icons as the Hermes dashboard's cron page (web/src/pages/CronPage.tsx), plus run history.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val paused = schedulePaused(job)
                    IconButton(enabled = pending.isBlank() && scheduleState(job) != "Completed", onClick = { pending = id; actionScope.launch { pauseSchedule(vm, job, refresh).join(); pending = "" } }) {
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, uiText(if (paused) "Resume" else "Pause"))
                    }
                    IconButton(enabled = pending.isBlank(), onClick = { runSchedule(vm, job, refresh) }) { Icon(Icons.Rounded.Bolt, uiText("Run")) }
                    IconButton(enabled = pending.isBlank(), onClick = { cronForm(vm, job, deliveries, data["task-capabilities"]?.string("timezone").orEmpty(), refresh) }) { Icon(Icons.Rounded.Edit, uiText("Edit")) }
                    IconButton(onClick = { selected = if (selected == id) "" else id }) { Icon(Icons.Rounded.History, uiText("Run history"), tint = if (selected == id) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                    IconButton(enabled = pending.isBlank(), onClick = { vm.openForm(ActionForm("Delete schedule", "/api/crons/delete", emptyList(), json("job_id" to id), explanation = "Remove this scheduled task from Beacon.", actionLabel = "Delete", onResult = { refresh() })) }) { Icon(Icons.Rounded.Delete, uiText("Delete"), tint = if (pending.isBlank()) MaterialTheme.colorScheme.error else LocalContentColor.current) }
                }
                if (selected == id) {
                    val history = data["cron-history:$id"]
                    if (history == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else if (history.has("_error")) Text("Run history isn't available. Check your connection and try again.", color = MaterialTheme.colorScheme.error)
                    else if (history.rows("runs").isEmpty()) Text("No recorded runs yet.")
                    else history.rows("runs").forEach { run ->
                        val session = run.string("session_id", run.string("id"))
                        TextButton(enabled = session.isNotBlank(), onClick = { vm.tab("Chat"); vm.selectSession(session) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(run.string("title", "Open result conversation") + if (run.optBoolean("is_active")) " · Running" else "") }
                    }
                    if (job.optBoolean("no_agent")) Text("Script tasks don't create a chat per run.", style = MaterialTheme.typography.bodySmall)
                }
            } }
        }
        if (crons != null && !crons.has("_error") && crons.rows("jobs").isEmpty() && !crons.optBoolean("cron_unavailable")) item { EmptyState("Nothing scheduled yet", assistantText("Choose what Beacon should do and when."), Icons.Rounded.EventNote) }
    }
}
internal fun pauseSchedule(vm: BeaconViewModel, job: JSONObject, refresh: () -> Unit): Job =
    vm.action(if (schedulePaused(job)) "/api/crons/resume" else "/api/crons/pause", json("job_id" to job.string("id")), refresh = false) { _, _ -> refresh() }
internal fun runSchedule(vm: BeaconViewModel, job: JSONObject, refresh: () -> Unit) = vm.openForm(ActionForm("Run this task", "/api/crons/run", emptyList(), json("job_id" to job.string("id")), explanation = if(job.optBoolean("no_agent")) "Run the task script once. Check-up tasks consider only an opportunity due this minute; missed slots are skipped." else "Run this task now with its saved tools and delivery.", actionLabel = "Run", onResult = { refresh() }))
/** `deliver` is where a new task's result goes; the thread sheet starts it on its own thread. */
internal fun cronForm(vm: BeaconViewModel, job: JSONObject?, targets: List<JSONObject>, timezone: String, refresh: () -> Unit, deliver: String = "local") {
    val seed = job?.let(::schedulePayload) ?: json("deliver" to deliver)
    listOf("name", "prompt", "provider", "model").forEach { if (!seed.has(it)) seed.put(it, "") }
    if (!seed.has("skills")) seed.put("skills", org.json.JSONArray())
    seed.put("_schedule_mode", "Custom schedule").put("_start_hour", 10).put("_end_hour", 22).put("_every_hours", 2).put("_include_end", false).put("_timezone", timezone)
    if (job != null) {
        seed.put("job_id", job.string("id"))
        seed.put("schedule", nativeScheduleInput(job))
        seed.put("_original", seed.copy())
    }
    val names = deliveryNames(targets)
    val choices = targets.filter { it.string("id") == "local" || it.optBoolean("home_target_set") }.map { it.string("id") }.ifEmpty { listOf("local") }.toMutableList()
    seed.string("deliver").takeIf { it.isNotBlank() && it !in choices }?.let { choices.add(it) }
    // A target saved on the job but no longer listed (a deleted thread, an unset platform) keeps its place under a plain name.
    val labels = names + targets.filter { it.string("id") != "local" && !it.optBoolean("home_target_set") }.associate { it.string("id") to names.getValue(it.string("id")) + " (not configured)" } +
        choices.filter { it !in names }.associateWith { deliveryLabel(it, names) }
    val missing = targets.filter { it.string("id") != "local" && !it.optBoolean("home_target_set") }.joinToString { it.string("name") + ": configure " + it.string("home_env_var") }
    val fields = listOf(Field("name", "Task name"), Field("prompt", "What should ${dev.hermes.android.data.currentAssistantName} do?", FieldType.MULTILINE, true), Field("_schedule_mode", "Schedule input", FieldType.CHOICE, options = listOf("Custom schedule", "Fixed window")), Field("schedule", "When (custom schedule)", help = "Examples: every 2h, every day at 8am, in 30m. Timezone: $timezone. For an exact daytime cadence choose Fixed window. Cron expressions are also supported."), Field("_start_hour", "Start hour", FieldType.CHOICE, options = (0..23).map(Int::toString), section = "Fixed window"), Field("_end_hour", "End hour", FieldType.CHOICE, options = (1..24).map(Int::toString), section = "Fixed window"), Field("_every_hours", "Every (hours)", FieldType.CHOICE, options = listOf("1", "2", "3", "4", "6", "12"), section = "Fixed window"), Field("_include_end", "Include the end hour", FieldType.BOOLEAN, help = "Off stops before the end hour. Timezone: $timezone", section = "Fixed window"), Field("deliver", "Where should the result appear?", FieldType.CHOICE, options = choices, optionLabels = labels, help = missing), Field("provider", "Provider", section = "Advanced"), Field("model", "Model", section = "Advanced"), Field("skills", "Skills", FieldType.LIST, help = "One skill name per line", section = "Advanced"))
    val editableFields = if (job?.string("script")?.endsWith("beacon-checkups.py") == true) fields.filter { it.key == "name" } else fields
    vm.openForm(ActionForm(if (job == null) "Create schedule" else "Edit schedule", if (job == null) "/api/crons/create" else "/api/crons/update", editableFields, seed, onResult = { refresh() }))
}

internal fun refreshSchedules(vm: BeaconViewModel) {
    vm.load("crons", "/api/crons")
    vm.load("cron-delivery", "/api/crons/delivery-options")
    vm.load("task-capabilities", "/api/plugins/beacon/mobile/tasks/capabilities")
}
