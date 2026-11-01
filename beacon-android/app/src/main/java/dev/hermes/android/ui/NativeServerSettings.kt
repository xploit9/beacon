package dev.hermes.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hermes.android.core.string
import dev.hermes.android.data.NativeServerState
import kotlinx.coroutines.delay

/** Log files the Logs page can show. The chip labels are translated; these ids are what the server is asked for. */
internal val serverLogFiles = listOf("errors", "agent", "gateway", "gui", "desktop", "mcp")

/** Overall health: ok is healthy, degraded needs attention, anything else (including a failed load) is an error. */
internal fun serverHealthStatus(overall: String, loading: Boolean): SettingsStatus = when {
    overall == "ok" -> SettingsStatus.Ok
    overall == "degraded" -> SettingsStatus.Attention
    overall.isBlank() && loading -> SettingsStatus.Working
    else -> SettingsStatus.Error
}

/** The short pill word of an overall health state, used by the Settings hub row; the Health page's hero says the longer sentence for the same state. */
internal fun serverHealthWord(status: SettingsStatus): String = when (status) {
    SettingsStatus.Ok -> "Healthy"
    SettingsStatus.Attention -> "Needs attention"
    else -> "Failed"
}

/** A component's raw status word as a pill state. Unknown words are Off and keep their raw word as the label. */
internal fun serverComponentStatus(raw: String): SettingsStatus = when (raw.trim().lowercase()) {
    "ok", "healthy" -> SettingsStatus.Ok
    "degraded", "warn" -> SettingsStatus.Attention
    "error", "down", "failed" -> SettingsStatus.Error
    else -> SettingsStatus.Off
}

/** The hero state of a confirmed server update outcome; anything not confirmed needs the user's review. */
internal fun serverOutcomeStatus(outcome: String): SettingsStatus = when (outcome) {
    "success" -> SettingsStatus.Ok
    "failed", "refused" -> SettingsStatus.Error
    "running" -> SettingsStatus.Working
    else -> SettingsStatus.Attention
}

/** Whether [page] already holds data, so a reload keeps it on screen with a thin bar instead of a blank page. */
internal fun serverHasCache(page: String, state: NativeServerState): Boolean = when (page) {
    "Health" -> state.overall.isNotBlank() || state.version.isNotBlank() || state.components.isNotEmpty()
    "Logs" -> state.logs.isNotEmpty()
    "Server updates" -> state.update != null || state.applying || state.requestedAt.isNotBlank()
    "Session checkpoints", "Rollback checkpoints" -> state.checkpoints.isNotEmpty() || state.checkpointBytes > 0
    else -> false
}

/** The icon of a Hermes component row; unknown components use the memory glyph. */
internal fun serverComponentIcon(name: String): ImageVector = when (name.lowercase()) {
    "gateway" -> Icons.Rounded.Router
    "agent" -> Icons.Rounded.SmartToy
    "dashboard" -> Icons.Rounded.Dashboard
    else -> Icons.Rounded.Memory
}

private fun serverLogFileIcon(file: String): ImageVector = when (file) {
    "errors" -> Icons.Rounded.ErrorOutline
    "agent" -> Icons.Rounded.SmartToy
    "gateway" -> Icons.Rounded.Router
    "gui" -> Icons.Rounded.PhoneAndroid
    "desktop" -> Icons.Rounded.DesktopWindows
    else -> Icons.Rounded.Hub
}

@Composable private fun serverLogFileLabel(file: String): String = when (file) {
    "errors" -> uiText("Errors")
    "agent" -> uiText("Agent")
    "gateway" -> uiText("Gateway")
    "gui" -> uiText("App")
    "desktop" -> uiText("Desktop")
    else -> uiText("MCP")
}

@Composable private fun serverComponentLabel(status: SettingsStatus, raw: String): String = when (status) {
    SettingsStatus.Ok -> uiText("Healthy")
    SettingsStatus.Attention -> uiText("Needs attention")
    SettingsStatus.Error -> uiText("Failed")
    else -> if (raw.isBlank()) uiText("Not reported") else raw.trim().replaceFirstChar { it.uppercase() }
}

private fun copyServerText(context: Context, text: String) {
    runCatching { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Hermes update command", text)) }
}

/** The four Server pages. The Header is drawn by NativeServerPage; this is the body, loaded from cache first. */
@Composable fun NativeServerSettings(page: String, state: NativeServerState,
    onCheckUpdate: () -> Unit, onApplyUpdate: () -> Unit, onLogFile: (String) -> Unit, onLogSearch: (String) -> Unit, onReviewUnknown: () -> Unit = {}) {
    val context = LocalContext.current
    SettingsList(refreshing = state.loading && serverHasCache(page, state)) {
        if (state.error.isNotBlank()) item { SettingsSection(testTag = "server-error") { SettingsErrorRow(uiText(state.error), onCheckUpdate, uiText("Retry"), retryTestTag = "server-retry") } }
        if (state.notice.isNotBlank()) item { SettingsSection(testTag = "server-notice") { SettingsValueRow(uiText(state.notice), icon = Icons.Rounded.Info) } }
        when (page) {
            "Health" -> serverHealth(context, state)
            "Logs" -> serverLogs(state, onLogFile, onLogSearch, onCheckUpdate)
            "Server updates" -> serverUpdates(context, state, onCheckUpdate, onApplyUpdate, onReviewUnknown)
            "Session checkpoints", "Rollback checkpoints" -> serverCheckpoints(context, state)
        }
    }
}

private fun LazyListScope.serverHealth(context: Context, state: NativeServerState) {
    item {
        val status = serverHealthStatus(state.overall, state.loading)
        val title = when (status) {
            SettingsStatus.Ok -> uiText("Hermes is healthy")
            SettingsStatus.Attention -> uiText("Some parts of Hermes need attention")
            SettingsStatus.Working -> uiText("Checking...")
            else -> uiText("Health isn't available right now.")
        }
        val line = if (state.version.isBlank()) uiText("Hermes server") else uiText("Hermes {0}").replace("{0}", state.version)
        SettingsStatusHero(status, title, line = line, testTag = "server-health-hero")
    }
    if (serverHasCache("Health", state)) item {
        SettingsSection(testTag = "server-health-metrics") {
            SettingsMetricRow(listOf(
                uiText("Gateway") to state.gatewayState.ifBlank { uiText("Not reported") },
                uiText("Active agents") to "${state.activeAgents}",
            ))
        }
    }
    if (state.components.isNotEmpty()) item {
        SettingsSection(title = uiText("Components"), testTag = "server-components") {
            state.components.forEachIndexed { index, component ->
                if (index > 0) SettingsDivider()
                val status = serverComponentStatus(component.status)
                SettingsValueRow(
                    label = component.name.replaceFirstChar { it.uppercase() },
                    icon = serverComponentIcon(component.name),
                    value = component.detail,
                    status = status,
                    statusLabel = serverComponentLabel(status, component.status),
                    testTag = "server-component-${component.name}",
                )
            }
        }
    }
}

/** How long typing in the log search pauses before the lines are read again. */
internal const val LogSearchPauseMillis = 400L

private fun LazyListScope.serverLogs(state: NativeServerState, onLogFile: (String) -> Unit, onLogSearch: (String) -> Unit, onReload: () -> Unit) {
    item {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            serverLogFiles.forEach { file ->
                FilterChip(selected = state.logFile == file, onClick = { onLogFile(file) }, enabled = !state.loading,
                    label = { Text(serverLogFileLabel(file)) }, leadingIcon = { Icon(serverLogFileIcon(file), null, Modifier.size(18.dp)) },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
    item {
        // Typing only changes the term; the lines narrow when it pauses. Opening the page already read them, so the first value is not a change.
        var opened by remember { mutableStateOf(false) }
        LaunchedEffect(state.logSearch) {
            if (!opened) { opened = true; return@LaunchedEffect }
            delay(LogSearchPauseMillis)
            onReload()
        }
        SettingsSection(testTag = "server-log-search") {
            SettingsTextFieldRow(uiText("Search log entries"), state.logSearch, onLogSearch, icon = Icons.Rounded.Search)
        }
    }
    item {
        SettingsSection(testTag = "server-log-lines") {
            when {
                state.logs.isNotEmpty() -> SelectionContainer {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.logs.forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
                    }
                }
                state.loading -> SettingsSkeletonRows(3)
                else -> SettingsEmpty(uiText("No matching log entries."), Icons.Rounded.SearchOff)
            }
        }
    }
}

private fun LazyListScope.serverUpdates(context: Context, state: NativeServerState,
    onCheckUpdate: () -> Unit, onApplyUpdate: () -> Unit, onReviewUnknown: () -> Unit) {
    val update = state.update
    val requested = state.applying || state.requestedAt.isNotBlank()
    val running = state.applying || state.outcome == "running"
    val lines = state.action?.optJSONArray("lines")
    val recent = lines?.let { array -> (0 until array.length()).map { array.optString(it) }.takeLast(40) }.orEmpty()
    val available = update?.optBoolean("update_available") == true
    val canApplyOnServer = update?.optBoolean("can_apply") == true
    val command = update?.string("update_command").orEmpty()
    val message = update?.string("message").orEmpty()
    item {
        val receipt = state.receipt?.optJSONObject("receipt") ?: state.action?.optJSONObject("receipt")
        val finished = receipt?.string("finished_at").orEmpty()
        val currentVersion = update?.string("current_version").orEmpty().ifBlank { uiText("Not reported") }
        val status: SettingsStatus
        val title: String
        val line: String
        when {
            running -> {
                status = SettingsStatus.Working
                title = uiText("Server update is running")
                line = recent.lastOrNull()?.take(240).orEmpty()
            }
            requested -> {
                status = serverOutcomeStatus(state.outcome)
                title = when (state.outcome) {
                    "success" -> uiText("Server update completed")
                    "partial" -> uiText("Updated, but some steps need attention")
                    "failed" -> uiText("Server update failed")
                    "refused" -> uiText("Server update was refused")
                    else -> uiText("Not confirmed yet")
                }
                line = if (state.outcome == "unconfirmed") uiText("The update isn't confirmed yet. Reconnect and refresh.")
                    else if (finished.isNotBlank()) uiText("Finished: {0}").replace("{0}", finished) else ""
            }
            update == null -> {
                status = if (state.loading) SettingsStatus.Working else SettingsStatus.Off
                title = if (state.loading) uiText("Checking...") else uiText("No update result was reported.")
                line = ""
            }
            available -> {
                status = SettingsStatus.Attention
                title = uiText("Update available")
                line = if (canApplyOnServer) uiText("Current version: {0}").replace("{0}", currentVersion) else uiText("Update on your computer")
            }
            else -> {
                status = SettingsStatus.Ok
                title = uiText("You're up to date")
                line = uiText("Current version: {0}").replace("{0}", currentVersion)
            }
        }
        val reviewable = requested && state.outcome == "unconfirmed" && state.canReviewUnknown
        val primary = when {
            running -> null
            reviewable -> SettingsAction(uiText("Review unconfirmed update"), onReviewUnknown, testTag = "server-review")
            available && canApplyOnServer -> SettingsAction(uiText("Update Hermes"), onApplyUpdate, enabled = state.canApply, testTag = "server-update-apply")
            else -> null
        }
        val secondary = if (running) null else SettingsAction(uiText("Check now"), onCheckUpdate, enabled = !state.loading && !state.applying, testTag = "server-check")
        SettingsStatusHero(status, title, line = line, primary = primary, secondary = secondary, lineTestTag = "server-update-line", testTag = "server-update-hero")
    }
    if (running) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    if (update != null && !canApplyOnServer && command.isNotBlank()) item {
        SettingsSection(testTag = "server-update-command") {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(command, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), maxLines = 2, overflow = TextOverflow.Ellipsis)
                SettingsIconButton(Icons.Rounded.ContentCopy, uiText("Copy"), { copyServerText(context, command) })
            }
        }
    }
    if (message.isNotBlank()) item {
        SettingsSection(testTag = "server-update-message") { SettingsValueRow(message, icon = Icons.Rounded.Info) }
    }
    if (requested && recent.isNotEmpty()) item {
        SettingsSection(testTag = "server-update-diagnostics") { Disclosure("Update diagnostics", recent.joinToString("\n").takeLast(6000)) }
    }
}

private fun LazyListScope.serverCheckpoints(context: Context, state: NativeServerState) {
    if (!state.loading || serverHasCache("Session checkpoints", state)) item {
        SettingsSection(testTag = "server-checkpoint-metrics") {
            SettingsMetricRow(listOf(
                uiText("Stored") to Formatter.formatShortFileSize(context, state.checkpointBytes),
                uiText("Sessions") to "${state.checkpoints.size}",
            ))
        }
    }
    item {
        SettingsSection(testTag = "server-checkpoint-list") {
            when {
                state.checkpoints.isNotEmpty() -> state.checkpoints.forEachIndexed { index, checkpoint ->
                    if (index > 0) SettingsDivider()
                    SettingsValueRow(checkpoint.session, icon = Icons.Rounded.Restore,
                        value = uiText("Files: {0} · {1}").replace("{1}", Formatter.formatShortFileSize(context, checkpoint.bytes)).replace("{0}", "${checkpoint.files}"),
                        testTag = "server-checkpoint-${checkpoint.session}")
                }
                state.loading -> SettingsSkeletonRows(3)
                else -> SettingsEmpty(uiText("No session checkpoints are stored for this profile."), Icons.Rounded.Restore)
            }
        }
    }
}
