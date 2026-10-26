package dev.hermes.android.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.tasks.*
import kotlinx.coroutines.launch
import org.json.JSONObject
/** The jobs that post into this thread, read from the Cron list the app already holds so the sheet opens filled. */
internal fun threadJobs(jobs: List<JSONObject>, threadId: String): List<JSONObject> = jobs.filter { deliversTo(it, threadTarget(threadId)) }
/** A thread's "Scheduled posts": the tasks whose results land in it, with the Cron screen's pause, run and edit, and a new task that already posts here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ThreadSchedulesSheet(vm: BeaconViewModel, threadId: String, title: String, onDismiss: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val crons = data["crons"]
    val delivery = data["cron-delivery"]
    val timezone = data["task-capabilities"]?.string("timezone").orEmpty()
    val targets = withThreadTarget(delivery?.rows("targets").orEmpty(), threadId, title)
    val needsPlugin = delivery != null && delivery.rows("targets").none { it.string("id") == "phone" }
    val jobs = threadJobs(crons?.rows("jobs").orEmpty(), threadId)
    val refresh: () -> Unit = { refreshSchedules(vm) }
    var pending by remember(threadId) { mutableStateOf("") }
    val actionScope = rememberCoroutineScope()
    // A thread made before it reserved itself is saved now, so the posts set up here can land before its first message.
    LaunchedEffect(ui.origin, ui.owner, ui.profile, threadId) { vm.reserveThread(threadId); refresh() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Scheduled posts", style = MaterialTheme.typography.titleLarge)
                    Text(plainTitle(title).ifBlank { "This thread" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, uiText("Close")) }
            }
            if (needsPlugin) Text("Scheduled posts need the Beacon plugin on your Hermes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Button(onClick = { cronForm(vm, null, targets, timezone, refresh, deliver = threadTarget(threadId)) }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Rounded.Add, null); Text(uiText("Add"), Modifier.padding(start = 8.dp)) }
            when {
                crons == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                crons.has("_error") -> Column { Text("Couldn't load scheduled posts. Check your connection and try again.", color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = refresh, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") } }
                jobs.isEmpty() -> Text("Nothing posts here yet. Add a task and its results show up in this thread.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(jobs, key = { it.string("id") }) { job ->
                        val id = job.string("id")
                        val paused = schedulePaused(job)
                        Glass { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(job.string("name", "Scheduled task"), style = MaterialTheme.typography.titleMedium)
                            Text(job.optJSONObject("schedule")?.string("display").orEmpty().ifBlank { job.string("schedule_display") }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text("${scheduleState(job)} · Next: ${scheduleTime(job.string("next_run_at"), timezone, "Not scheduled")}", style = MaterialTheme.typography.bodySmall)
                            scheduleDeliveryError(job).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            if (pending == id) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IconButton(enabled = pending.isBlank() && scheduleState(job) != "Completed", onClick = { pending = id; actionScope.launch { pauseSchedule(vm, job, refresh).join(); pending = "" } }) {
                                    Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, uiText(if (paused) "Resume" else "Pause"))
                                }
                                IconButton(enabled = pending.isBlank(), onClick = { runSchedule(vm, job, refresh) }) { Icon(Icons.Rounded.Bolt, uiText("Run")) }
                                IconButton(enabled = pending.isBlank(), onClick = { cronForm(vm, job, targets, timezone, refresh) }) { Icon(Icons.Rounded.Edit, uiText("Edit")) }
                            }
                        } }
                    }
                }
            }
        }
    }
}
