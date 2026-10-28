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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import org.json.JSONObject
import dev.hermes.android.tasks.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
/** Every state [scheduleState] names; the card shows it through uiText. */
internal val ScheduleStateStrings = listOf("Paused", "Completed", "Running", "Failed", "Waiting", "Execution unconfirmed")

/** The phone's clock and language, with the app's own schedule words translated and carrying the assistant's name. */
@Composable internal fun rememberScheduleFormat(): ScheduleFormat {
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val words = ScheduleBuilderStrings.associateWith { assistantText(it) }
    return remember(is24Hour, words) { ScheduleFormat(is24Hour, text = { words[it] ?: it }) }
}

/** The schedule the form would save, in words, under the "When" fields; nothing while it is incomplete. */
@Composable fun ScheduleSummary(values: Map<String, String>, timezone: String) {
    val format = rememberScheduleFormat()
    val state = scheduleStateFrom { values[it].orEmpty() }
    val built = buildScheduleString(state).takeIf { it.isNotBlank() && (state.mode != ScheduleMode.Weekly || state.weekdays.isNotEmpty()) } ?: return
    // Typed text that is no schedule shape comes back as typed; that tells the owner nothing.
    // A typed cron code the pickers cannot hold is still described, so the owner sees what it means before saving.
    val text = (if (looksLikeCron(built)) describeCron(built, format) else null)
        ?: describeSchedule(scheduleObject(built), built, timezone, format).takeIf { it != built } ?: return
    Text(if (timezone.isNotBlank() && state.mode != ScheduleMode.Every) "$text · $timezone" else text, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable fun TasksScreen(vm: BeaconViewModel) {
    Column(Modifier.fillMaxSize()) {
        Header(uiText("Schedules"), menu = LocalOpenNavigationMenu.current) { IconButton(onClick = { refreshSchedules(vm) }) { Icon(Icons.Rounded.Refresh, uiText("Refresh schedules")) } }
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
    var showCompleted by rememberSaveable(ui.profile) { mutableStateOf(false) }
    val actionScope = rememberCoroutineScope()
    var pending by remember(ui.profile) { mutableStateOf("") }
    val crons = data["crons"]
    val gatewayNeedsAttention = crons?.rows("jobs").orEmpty().any { !schedulePaused(it) && scheduleGatewayNeedsAttention(it) }
    val (active, completed) = splitSchedules(crons?.rows("jobs").orEmpty())
    val refresh: () -> Unit = { refreshSchedules(vm) }
    LaunchedEffect(ui.origin, ui.owner, ui.profile) { refresh(); if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, selected, crons) { if (selected.isNotBlank()) vm.load("cron-history:$selected", "/api/crons/history", mapOf("job_id" to selected)) }
    val capabilities = data["task-capabilities"]
    val timezone = capabilities?.string("timezone").orEmpty()
    val format = rememberScheduleFormat()
    val deliveries = data["cron-delivery"]?.rows("targets").orEmpty()
    val deliveryWords = DeliveryNameStrings.associateWith { uiText(it) }
    val checkupTitle = assistantText("Check-up calls")
    val checkupExplanation = assistantText("Beacon calls you 3-4 times a day between 10:00 and 22:00. Your phone's Do Not Disturb still applies.")
    val checkupAction = uiText("Turn on")
    val deleteTitle = uiText("Delete schedule")
    val deleteExplanation = uiText("This schedule will be removed.")
    val deleteAction = uiText("Delete")
    val delete: (String) -> Unit = { id -> vm.openForm(ActionForm(deleteTitle, "/api/crons/delete", emptyList(), json("job_id" to id), explanation = deleteExplanation, actionLabel = deleteAction, onResult = { refresh() }, destructive = true)) }
    LazyColumn(modifier.fillMaxSize().testTag("schedules-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (gatewayNeedsAttention) item { Text(uiText("Schedules aren't running. Start Hermes on your computer."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        item { Button(onClick = { cronForm(vm, null, deliveries, timezone, refresh, format = format) }) { Icon(Icons.Rounded.Add, null); Text(uiText("Create schedule"), Modifier.padding(start = 8.dp)) } }
        item {
            timezone.takeIf(String::isNotBlank)?.let { Text("${uiText("Times in")} $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = { showPresets = !showPresets }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Check-up calls")); Icon(if (showPresets) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) }
            if (showPresets) {
            if (capabilities == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (recordError(capabilities).isNotBlank()) { Text(uiText("Check-up setup isn't available. Check your network, then retry."), color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = refresh, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Retry")) } }

            OutlinedButton(enabled = capabilities?.optBoolean("checkups_ready") == true, onClick = { vm.openForm(ActionForm(checkupTitle, "/api/plugins/beacon/mobile/tasks/checkups", emptyList(), explanation = checkupExplanation, actionLabel = checkupAction, onResult = { refresh() })) }) { Text(checkupAction) }
            if (capabilities != null && recordError(capabilities).isBlank() && !capabilities.optBoolean("checkups_ready")) Text(uiText("Set up scheduled callbacks on your computer to enable check-up calls."), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (crons?.optBoolean("cron_unavailable") == true) item { EmptyState(uiText("Schedules are unavailable"), assistantText("Schedules aren't enabled for your Beacon. Check the connection in Settings."), Icons.Rounded.EventBusy) }
        if (crons?.has("_error") == true) item { Column { Text(uiText("Couldn't load schedules. Check your network, then retry."), color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = refresh, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Retry")) } } }
        if (crons == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        items(active, key = { it.string("id") }) { job ->
            val id = job.string("id")
            val callback = scheduleIsCallback(job)
            Glass(Modifier.testTag("schedule-card:$id")) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(scheduleTitle(job, uiText("Untitled schedule"), format.text("Callback")), style = MaterialTheme.typography.titleMedium)
                scheduleBody(job, callbackReason(capabilities, id), format.text).takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = if (selected == id) Int.MAX_VALUE else 3) }
                describeSchedule(job.optJSONObject("schedule"), scheduleDisplay(job), timezone, format).takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                // A callback saves its result nowhere the owner picks, so its target is not worth a word.
                Text(listOfNotNull(uiText(scheduleState(job)), profileDisplayLabel(job.string("profile", ui.profile), profileRows, profileLoading), deliveryLabel(job.string("deliver", "local"), deliveryNames(deliveries), deliveryWords).takeUnless { callback }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                Text("${uiText("Next")}: ${scheduleTime(job.string("next_run_at"), timezone, uiText("Not scheduled"), format.is24Hour)}", style = MaterialTheme.typography.bodySmall)
                Text("${uiText("Last")}: ${scheduleTime(job.string("last_run_at"), timezone, uiText("No recorded run"), format.is24Hour)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                scheduleDeliveryError(job).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (pending == id) LinearProgressIndicator(Modifier.fillMaxWidth())
                // Same actions, order and icons as the Hermes dashboard's cron page (web/src/pages/CronPage.tsx), plus run history. A callback is the plugin's, so it has no Edit.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val paused = schedulePaused(job)
                    IconButton(enabled = pending.isBlank() && !scheduleEnded(job), onClick = { pending = id; actionScope.launch { pauseSchedule(vm, job, refresh).join(); pending = "" } }) {
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, uiText(if (paused) "Resume" else "Pause"))
                    }
                    IconButton(enabled = pending.isBlank(), onClick = { runSchedule(vm, job, refresh) }) { Icon(Icons.Rounded.Bolt, uiText("Run")) }
                    if (!callback) IconButton(enabled = pending.isBlank(), onClick = { cronForm(vm, job, deliveries, timezone, refresh, format = format) }) { Icon(Icons.Rounded.Edit, uiText("Edit")) }
                    IconButton(onClick = { selected = if (selected == id) "" else id }) { Icon(Icons.Rounded.History, uiText("Run history"), tint = if (selected == id) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                    IconButton(enabled = pending.isBlank(), onClick = { delete(id) }) { Icon(Icons.Rounded.Delete, uiText("Delete"), tint = if (pending.isBlank()) MaterialTheme.colorScheme.error else LocalContentColor.current) }
                }
                if (selected == id) RunHistory(vm, job, data["cron-history:$id"])
            } }
        }
        if (completed.isNotEmpty()) {
            item(key = "completed-schedules") { CompletedHeader(completed.size, showCompleted, format.text("Completed schedules")) { showCompleted = !showCompleted } }
            if (showCompleted) items(completed, key = { it.string("id") }) { job ->
                val id = job.string("id")
                CompletedScheduleRow(id, scheduleTitle(job, uiText("Untitled schedule"), format.text("Callback")), if (scheduleIsCallback(job)) callbackReason(capabilities, id) else "",
                    job.string("last_run_at").takeIf(String::isNotBlank)?.let { "${format.text("Ran")} ${scheduleTime(it, timezone, "", format.is24Hour)}" } ?: uiText("No recorded run"),
                    onHistory = { selected = if (selected == id) "" else id }, historyOpen = selected == id, onDelete = { delete(id) }) { RunHistory(vm, job, data["cron-history:$id"]) }
            }
        }
        if (crons != null && !crons.has("_error") && crons.rows("jobs").isEmpty() && !crons.optBoolean("cron_unavailable")) item { EmptyState(uiText("Nothing scheduled yet"), assistantText("Choose what Beacon should do and when."), Icons.Rounded.EventNote) }
    }
}
/** One run per line, each opening the conversation it made. */
@Composable private fun RunHistory(vm: BeaconViewModel, job: JSONObject, history: JSONObject?) {
    if (history == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else if (history.has("_error")) Text(uiText("Run history isn't available. Check your network, then retry."), color = MaterialTheme.colorScheme.error)
    else if (history.rows("runs").isEmpty()) Text(uiText("No recorded runs yet."))
    else history.rows("runs").forEach { run ->
        val session = run.string("session_id", run.string("id"))
        TextButton(enabled = session.isNotBlank(), onClick = { vm.tab("Chat"); vm.selectSession(session) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(run.string("title", uiText("Open result conversation")) + if (run.optBoolean("is_active")) " · ${uiText("Running")}" else "") }
    }
    if (job.optBoolean("no_agent")) Text(uiText("Script schedules don't create a chat per run."), style = MaterialTheme.typography.bodySmall)
}
/** "Completed schedules (3)": closed until opened, like Check-up calls. */
@Composable internal fun CompletedHeader(count: Int, expanded: Boolean, title: String, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp).testTag("completed-schedules")) { Text("$title ($count)"); Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) }
}
/** A finished one-time schedule in one compact row: what it was, when it ran, its results, and delete. [onHistory] is null where a place has no run history. */
@Composable internal fun CompletedScheduleRow(id: String, title: String, reason: String, ran: String, onHistory: (() -> Unit)?, historyOpen: Boolean, onDelete: () -> Unit, history: @Composable () -> Unit = {}) {
    Glass(Modifier.testTag("completed-schedule:$id")) { Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (reason.isNotBlank()) Text(reason, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ran, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onHistory != null) IconButton(onClick = onHistory) { Icon(Icons.Rounded.History, uiText("Run history"), tint = if (historyOpen) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, uiText("Delete"), tint = MaterialTheme.colorScheme.error) }
        }
        if (historyOpen) Column(Modifier.padding(end = 12.dp)) { history() }
    } }
}
internal fun pauseSchedule(vm: BeaconViewModel, job: JSONObject, refresh: () -> Unit): Job =
    vm.action(if (schedulePaused(job)) "/api/crons/resume" else "/api/crons/pause", json("job_id" to job.string("id")), refresh = false) { _, _ -> refresh() }
internal fun runSchedule(vm: BeaconViewModel, job: JSONObject, refresh: () -> Unit) = vm.openForm(ActionForm("Run this schedule", "/api/crons/run", emptyList(), json("job_id" to job.string("id")), explanation = if (job.optBoolean("no_agent")) "Run this script once now." else "Run this schedule now with its saved tools and delivery.", actionLabel = "Run", onResult = { refresh() }))
/** `deliver` is where a new task's result goes; the thread sheet starts it on its own thread. [format] is the phone's clock and language, for the hour and date choices. */
internal fun cronForm(vm: BeaconViewModel, job: JSONObject?, targets: List<JSONObject>, timezone: String, refresh: () -> Unit, deliver: String = "local", format: ScheduleFormat = ScheduleFormat()) {
    val seed = job?.let(::schedulePayload) ?: json("deliver" to deliver)
    listOf("name", "prompt", "provider", "model").forEach { if (!seed.has(it)) seed.put(it, "") }
    if (!seed.has("skills")) seed.put("skills", org.json.JSONArray())
    val stored = job?.let(::nativeScheduleInput).orEmpty()
    // A job saved with no schedule opens as Custom and empty, so saving it never invents one.
    val state = when { job == null -> ScheduleBuilderState(); stored.isBlank() -> ScheduleBuilderState(ScheduleMode.Custom); else -> parseScheduleString(stored) }
    val today = runCatching { java.time.LocalDate.now(java.time.ZoneId.of(timezone)) }.getOrElse { java.time.LocalDate.now() }
    state.formSeed(today).forEach { (key, value) -> seed.put(key, value) }
    seed.put("_timezone", timezone)
    if (job != null) {
        seed.put("job_id", job.string("id"))
        seed.put("schedule", stored)
        seed.put("_original", seed.copy())
    }
    val names = deliveryNames(targets)
    val choices = targets.filter { it.string("id") == "local" || it.optBoolean("home_target_set") }.map { it.string("id") }.ifEmpty { listOf("local") }.toMutableList()
    seed.string("deliver").takeIf { it.isNotBlank() && it !in choices }?.let { choices.add(it) }
    // A target saved on the job but no longer listed (a deleted thread, an unset platform) keeps its place under a plain name.
    val labels = names + targets.filter { it.string("id") != "local" && !it.optBoolean("home_target_set") }.associate { it.string("id") to names.getValue(it.string("id")) + " (not configured)" } +
        choices.filter { it !in names }.associateWith { deliveryLabel(it, names) }
    val missing = targets.filter { it.string("id") != "local" && !it.optBoolean("home_target_set") }.joinToString { it.string("name") + ": configure " + it.string("home_env_var") }
    // The next 90 days, and the saved day when it falls outside them.
    val days = ((0L..90L).map { today.plusDays(it).toString() } + state.onceDate).filter { runCatching { java.time.LocalDate.parse(it) }.isSuccess }.distinct().sorted()
    val dayLabels = days.associateWith { format.fullDay(java.time.LocalDate.parse(it)) }
    val minutes = ((0..59 step 5) + state.minute).distinct().sorted().map(Int::toString)
    val weekdays = (1..7).map { it % 7 }.map { day -> Field("_wd_$day", format.weekday(day, java.time.format.TextStyle.FULL_STANDALONE).replaceFirstChar { it.titlecase(format.locale) }, FieldType.BOOLEAN, section = "When") }
    val fields = listOf(Field("name", "Schedule name"), Field("prompt", "What should ${dev.hermes.android.data.currentAssistantName} do?", FieldType.MULTILINE, true),
        Field("_schedule_mode", "How often", FieldType.CHOICE, options = ScheduleMode.entries.map { it.value }, optionLabels = ScheduleMode.entries.associate { it.value to it.label }, section = "When"),
        Field("_once_date", "Date", FieldType.CHOICE, options = days, optionLabels = dayLabels, section = "When"),
        Field("_every_n", "Every", FieldType.INTEGER, section = "When"), Field("_every_unit", "Unit", FieldType.CHOICE, options = listOf("minutes", "hours", "days"), optionLabels = mapOf("minutes" to "minutes", "hours" to "hours", "days" to "days"), section = "When")) +
        weekdays + listOf(Field("_dom", "Day of the month", FieldType.CHOICE, options = ((1..28) + state.dayOfMonth).distinct().sorted().map(Int::toString), section = "When"),
        Field("_hour", "Hour", FieldType.CHOICE, options = (0..23).map(Int::toString), optionLabels = (0..23).associate { it.toString() to format.hour(it) }, section = "When"),
        Field("_minute", "Minute", FieldType.CHOICE, options = minutes, optionLabels = minutes.associateWith { it.padStart(2, '0') }, section = "When"),
        Field("_start_hour", "Start hour", FieldType.CHOICE, options = (0..23).map(Int::toString), section = "When"), Field("_end_hour", "End hour", FieldType.CHOICE, options = (1..24).map(Int::toString), section = "When"),
        Field("_every_hours", "Every (hours)", FieldType.CHOICE, options = listOf("1", "2", "3", "4", "6", "12"), section = "When"), Field("_include_end", "Include the end hour", FieldType.BOOLEAN, help = "Off stops before the end hour.", section = "When"),
        Field("schedule", "When it runs", help = "For example: every 2h or weekdays at 9am", section = "When"),
        Field("deliver", "Deliver to", FieldType.CHOICE, options = choices, optionLabels = labels, help = missing), Field("provider", "Provider", section = "Advanced"), Field("model", "Model", section = "Advanced"), Field("skills", "Skills", FieldType.LIST, help = "One skill name per line", section = "Advanced"))
    val editableFields = if (job != null && scheduleNameOnly(job)) fields.filter { it.key == "name" } else fields
    vm.openForm(ActionForm(if (job == null) "Create schedule" else "Edit schedule", if (job == null) "/api/crons/create" else "/api/crons/update", editableFields, seed, onResult = { refresh() }))
}

internal fun refreshSchedules(vm: BeaconViewModel) {
    vm.load("crons", "/api/crons")
    vm.load("cron-delivery", "/api/crons/delivery-options")
    vm.load("task-capabilities", "/api/plugins/beacon/mobile/tasks/capabilities")
}
