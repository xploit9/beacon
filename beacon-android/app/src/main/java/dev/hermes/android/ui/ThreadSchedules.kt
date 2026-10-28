package dev.hermes.android.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.tasks.*
import kotlinx.coroutines.launch
import org.json.JSONObject
/** The jobs that post into this thread, read from the Cron list the app already holds so the sheet opens filled. */
internal fun threadJobs(jobs: List<JSONObject>, threadId: String): List<JSONObject> = jobs.filter { deliversTo(it, threadTarget(threadId)) }
/** A thread's "Scheduled posts": the tasks whose results land in it, with the Schedules screen's pause, run, edit and delete, and a new task that already posts here. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun ThreadSchedulesSheet(vm: BeaconViewModel, threadId: String, title: String, onDismiss: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val crons = data["crons"]
    val delivery = data["cron-delivery"]
    val timezone = data["task-capabilities"]?.string("timezone").orEmpty()
    val targets = withThreadTarget(delivery?.rows("targets").orEmpty(), threadId, title)
    val needsPlugin = delivery != null && delivery.rows("targets").none { it.string("id") == "phone" }
    val jobs = threadJobs(crons?.rows("jobs").orEmpty(), threadId)
    val (active, completed) = splitSchedules(jobs)
    val format = rememberScheduleFormat()
    var showCompleted by rememberSaveable(ui.profile, threadId) { mutableStateOf(false) }
    val refresh: () -> Unit = { refreshSchedules(vm) }
    var pending by remember(threadId) { mutableStateOf("") }
    val actionScope = rememberCoroutineScope()
    val delete: (String) -> Unit = { id -> vm.openForm(ActionForm("Delete scheduled post?", "/api/crons/delete", emptyList(), json("job_id" to id), explanation = "It stops posting here. This can't be undone.", actionLabel = "Delete", onResult = { refresh() }, destructive = true)) }
    // A thread made before it reserved itself is saved now, so the posts set up here can land before its first message.
    LaunchedEffect(ui.origin, ui.owner, ui.profile, threadId) { vm.reserveThread(threadId); refresh() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // As tall as its content, up to this cap, so one post does not leave an empty sheet.
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(uiText("Scheduled posts"), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    Text(plainTitle(title).ifBlank { uiText("This thread") }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, uiText("Close")) }
            }
            if (needsPlugin) MissingBeaconPluginState(compact = true)
            else Button(onClick = { cronForm(vm, null, targets, timezone, refresh, deliver = threadTarget(threadId), format = format) }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Rounded.Add, null); Text(uiText("Add scheduled post"), Modifier.padding(start = 8.dp)) }
            when {
                crons == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                crons.has("_error") -> Column { Text(uiText("Couldn't load scheduled posts. Check your connection and try again."), color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = refresh, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Retry")) } }
                jobs.isEmpty() -> Text(uiText("Nothing posts here yet. Add a task and its results show up in this thread."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(active, key = { it.string("id") }) { job ->
                        val id = job.string("id")
                        val paused = schedulePaused(job)
                        Glass { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(scheduleTitle(job, uiText("Scheduled task"), format.text("Callback")), style = MaterialTheme.typography.titleMedium)
                            describeSchedule(job.optJSONObject("schedule"), scheduleDisplay(job), timezone, format).takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                            Text("${uiText(scheduleState(job))} · ${uiText("Next")}: ${scheduleTime(job.string("next_run_at"), timezone, uiText("Not scheduled"), format.is24Hour)}", style = MaterialTheme.typography.bodySmall)
                            scheduleDeliveryError(job).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            if (pending == id) LinearProgressIndicator(Modifier.fillMaxWidth())
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IconButton(enabled = pending.isBlank() && !scheduleEnded(job), onClick = { pending = id; actionScope.launch { pauseSchedule(vm, job, refresh).join(); pending = "" } }) {
                                    Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, uiText(if (paused) "Resume" else "Pause"))
                                }
                                TextButton(enabled = pending.isBlank(), onClick = { runSchedule(vm, job, refresh) }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp)); Text(uiText("Run now"), Modifier.padding(start = 6.dp)) }
                                IconButton(enabled = pending.isBlank(), onClick = { cronForm(vm, job, targets, timezone, refresh, format = format) }) { Icon(Icons.Rounded.Edit, uiText("Edit")) }
                                IconButton(enabled = pending.isBlank(), onClick = { delete(id) }) {
                                    Icon(Icons.Rounded.Delete, uiText("Delete"), tint = if (pending.isBlank()) MaterialTheme.colorScheme.error else LocalContentColor.current)
                                }
                            }
                        } }
                    }
                    if (completed.isNotEmpty()) {
                        item(key = "completed-schedules") { CompletedHeader(completed.size, showCompleted, format.text("Completed schedules")) { showCompleted = !showCompleted } }
                        if (showCompleted) items(completed, key = { it.string("id") }) { job ->
                            CompletedScheduleRow(job.string("id"), scheduleTitle(job, uiText("Scheduled task"), format.text("Callback")), "",
                                job.string("last_run_at").takeIf(String::isNotBlank)?.let { "${format.text("Ran")} ${scheduleTime(it, timezone, "", format.is24Hour)}" } ?: uiText("No recorded run"),
                                onHistory = null, historyOpen = false, onDelete = { delete(job.string("id")) })
                        }
                    }
                }
            }
        }
    }
}
