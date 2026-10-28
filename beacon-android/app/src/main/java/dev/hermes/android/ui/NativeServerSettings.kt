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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.text.format.Formatter
import dev.hermes.android.core.string
import dev.hermes.android.data.NativeServerState

@Composable fun NativeServerSettings(page: String, state: NativeServerState,
    onCheckUpdate: () -> Unit, onApplyUpdate: () -> Unit, onLogFile: (String) -> Unit, onLogSearch: (String) -> Unit, onReviewUnknown: () -> Unit = {}) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.error.isNotBlank()) item { Text(uiText(state.error), color = MaterialTheme.colorScheme.error) }
        if (state.notice.isNotBlank()) item { Text(uiText(state.notice)) }
        when (page) {
            "Health" -> {
                item { Text(if (state.version.isBlank()) uiText("Hermes server") else uiText("Hermes {0}").replace("{0}", state.version), style = MaterialTheme.typography.titleLarge); Text(uiText(if (state.overall == "ok") "Hermes is healthy" else if (state.overall == "degraded") "Some parts of Hermes need attention" else "Health isn't available right now.")); Text(uiText("Gateway: {0} · Active agents: {1}").replace("{1}", "${state.activeAgents}").replace("{0}", state.gatewayState.ifBlank { uiText("Not reported") })) }
                items(state.components, key = { it.name }) { component -> Column { Text(component.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium); Text("${component.status}${component.detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"); HorizontalDivider(Modifier.padding(top = 12.dp)) } }
            }
            "Logs" -> {
                item { Text(uiText("Recent entries for this profile.")); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("errors", "agent", "gateway", "gui", "desktop", "mcp").forEach { file -> FilterChip(selected = state.logFile == file, onClick = { onLogFile(file) }, enabled = !state.loading, label = { Text(file) }, modifier = Modifier.heightIn(min = 48.dp)) } }; OutlinedTextField(state.logSearch, onLogSearch, label = { Text(uiText("Search log entries")) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                if (!state.loading && state.logs.isEmpty()) item { Text(uiText("No matching log entries.")) }
                items(state.logs) { line -> SelectionContainer { Text(line, style = MaterialTheme.typography.bodyMedium) } }
            }
            "Server updates" -> {
                item { Text(uiText("Hermes may reconnect while updating.")); Button(onClick = onCheckUpdate, enabled = !state.loading && !state.applying) { Text(uiText("Check now")) } }
                state.update?.let { update ->
                    item { Text(uiText("Current version: {0}").replace("{0}", update.string("current_version").ifBlank { uiText("Not reported") })); Text(update.string("message").ifBlank { uiText(if (update.optBoolean("update_available")) "An update is available." else "No update result was reported.") }); if (!update.optBoolean("can_apply")) Text(update.string("update_command").ifBlank { uiText("Hermes is updated on your computer, not from here.") }); if (update.optBoolean("can_apply") && update.optBoolean("update_available")) Button(onClick = onApplyUpdate, enabled = state.canApply) { Text(uiText("Apply server update")) } }

                }
                if (state.applying || state.requestedAt.isNotBlank()) item {
                    Text(uiText(when (state.outcome) { "success" -> "Server update completed"; "partial" -> "Updated, but some steps need attention"; "failed" -> "Server update failed"; "refused" -> "Server update was refused"; "running" -> "Server update is running"; else -> "The update isn't confirmed yet. Reconnect and refresh." }), style = MaterialTheme.typography.titleMedium)
                    if (state.applying || state.outcome == "running") LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.canReviewUnknown) OutlinedButton(onClick = onReviewUnknown) { Text(uiText("Review unconfirmed update")) }
                    val receipt = state.receipt?.optJSONObject("receipt") ?: state.action?.optJSONObject("receipt")
                    if (state.outcome != "unconfirmed") receipt?.string("finished_at")?.takeIf { it.isNotBlank() }?.let { Text(uiText("Finished: {0}").replace("{0}", it)) }
                }
                if (state.applying || state.requestedAt.isNotBlank()) {
                    val lines = state.action?.optJSONArray("lines")
                    val recent = (0 until (lines?.length() ?: 0)).map { lines!!.optString(it) }.takeLast(40)
                    if ((state.applying || state.outcome == "running") && recent.isNotEmpty()) item { Text(recent.last().take(240), style = MaterialTheme.typography.bodyMedium) }
                    if (recent.isNotEmpty()) item { Disclosure("Update diagnostics", recent.joinToString("\n").takeLast(6000)) }
                }
            }
            "Session checkpoints", "Rollback checkpoints" -> {
                item { Text(uiText("File snapshots that let you roll back changes in each conversation.")); Text(uiText("Stored: {0}").replace("{0}", Formatter.formatShortFileSize(context, state.checkpointBytes))) }
                if (!state.loading && state.checkpoints.isEmpty()) item { Text(uiText("No session checkpoints are stored for this profile.")) }
                items(state.checkpoints, key = { it.session }) { checkpoint -> Column { Text(checkpoint.session, style = MaterialTheme.typography.titleMedium); Text(uiText("Files: {0} · {1}").replace("{1}", Formatter.formatShortFileSize(context, checkpoint.bytes)).replace("{0}", "${checkpoint.files}")); HorizontalDivider(Modifier.padding(top = 12.dp)) } }
            }
        }
    }
}
