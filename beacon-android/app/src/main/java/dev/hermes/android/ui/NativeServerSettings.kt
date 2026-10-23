package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hermes.android.core.string
import dev.hermes.android.data.NativeServerState

@Composable fun NativeServerSettings(page: String, state: NativeServerState,
    onCheckUpdate: () -> Unit, onApplyUpdate: () -> Unit, onLogFile: (String) -> Unit, onLogSearch: (String) -> Unit, onReviewUnknown: () -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.error.isNotBlank()) item { Text(state.error, color = MaterialTheme.colorScheme.error) }
        if (state.notice.isNotBlank()) item { Text(state.notice) }
        when (page) {
            "Health" -> {
                item { Text("Hermes ${state.version.ifBlank { "server" }}", style = MaterialTheme.typography.titleLarge); Text(if (state.overall == "ok") "Server components report healthy" else if (state.overall == "degraded") "Some server components need attention" else "Server health is not available."); Text("Gateway: ${state.gatewayState.ifBlank { "Not reported" }} · ${state.activeAgents} active agents"); Text("Checks Hermes's components.", style = MaterialTheme.typography.bodyMedium) }
                items(state.components, key = { it.name }) { component -> Column { Text(component.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium); Text("${component.status}${component.detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"); HorizontalDivider(Modifier.padding(top = 12.dp)) } }
            }
            "Logs" -> {
                item { Text("Recent entries for this profile."); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("errors", "agent", "gateway", "gui", "desktop", "mcp").forEach { file -> FilterChip(selected = state.logFile == file, onClick = { onLogFile(file) }, enabled = !state.loading, label = { Text(file) }, modifier = Modifier.heightIn(min = 48.dp)) } }; OutlinedTextField(state.logSearch, onLogSearch, label = { Text("Search log entries") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                if (!state.loading && state.logs.isEmpty()) item { Text("No matching log entries. The log may be empty or not created yet.") }
                items(state.logs) { line -> SelectionContainer { Text(line, style = MaterialTheme.typography.bodyMedium) } }
            }
            "Server updates" -> {
                item { Text("Hermes may reconnect while updating."); Button(onClick = onCheckUpdate, enabled = !state.loading && !state.applying) { Text("Check now") } }
                state.update?.let { update ->
                    item { Text("Current version: ${update.string("current_version").ifBlank { "Not reported" }}"); Text(update.string("message").ifBlank { if (update.optBoolean("update_available")) "An update is available." else "No update result was reported." }); if (!update.optBoolean("can_apply")) Text(update.string("update_command").ifBlank { "This installation is updated outside the dashboard." }); if (update.optBoolean("can_apply") && update.optBoolean("update_available")) Button(onClick = onApplyUpdate, enabled = state.canApply) { Text("Apply server update") } }

                }
                if (state.applying || state.requestedAt.isNotBlank()) item {
                    Text(when (state.outcome) { "success" -> "Server update completed"; "partial" -> "Update completed with steps needing attention"; "failed" -> "Server update failed"; "refused" -> "Server update was refused"; "running" -> "Server update is running"; else -> "Update outcome is not yet confirmed. Reconnect and refresh the receipt." }, style = MaterialTheme.typography.titleMedium)
                    if (state.applying || state.outcome == "running") LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.canReviewUnknown) OutlinedButton(onClick = onReviewUnknown) { Text("Review unconfirmed update") }
                    val receipt = state.receipt?.optJSONObject("receipt") ?: state.action?.optJSONObject("receipt")
                    if (state.outcome != "unconfirmed") receipt?.string("finished_at")?.takeIf { it.isNotBlank() }?.let { Text("Finished: $it") }
                }
                if (state.applying || state.requestedAt.isNotBlank()) {
                    val lines = state.action?.optJSONArray("lines")
                    val recent = (0 until (lines?.length() ?: 0)).map { lines!!.optString(it) }.takeLast(40)
                    if ((state.applying || state.outcome == "running") && recent.isNotEmpty()) item { Text(recent.last().take(240), style = MaterialTheme.typography.bodyMedium) }
                    if (recent.isNotEmpty()) item { Disclosure("Update diagnostics", recent.joinToString("\n").takeLast(6000)) }
                }
            }
            "Session checkpoints", "Rollback checkpoints" -> {
                item { Text("File snapshots for /rollback in each conversation."); Text("Stored: ${state.checkpointBytes} bytes") }
                if (!state.loading && state.checkpoints.isEmpty()) item { Text("No session checkpoints are stored for this profile.") }
                items(state.checkpoints, key = { it.session }) { checkpoint -> Column { Text(checkpoint.session, style = MaterialTheme.typography.titleMedium); Text("${checkpoint.files} stored files · ${checkpoint.bytes} bytes"); HorizontalDivider(Modifier.padding(top = 12.dp)) } }
            }
        }
    }
}
