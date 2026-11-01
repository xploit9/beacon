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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
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

/** The Server updates hero says "You're up to date": a result was read, no update waits and none is running or being confirmed. */
internal fun serverUpdateIsCurrent(state: NativeServerState): Boolean =
    state.update != null && state.update.optBoolean("update_available") != true && !state.applying && state.requestedAt.isBlank() && state.outcome != "running"

private val upToDateWords = Regex("""\b(latest version|up[- ]to[- ]date)\b""", RegexOption.IGNORE_CASE)

/** Whether a Hermes sentence only says again what the current-state hero already says. */
internal fun serverMessageRestatesHero(message: String, current: Boolean): Boolean = current && upToDateWords.containsMatchIn(message)

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

/** The icon of a Hermes component row; every known component has its own glyph, an unknown one uses the memory glyph. */
internal fun serverComponentIcon(name: String): ImageVector = when (name.lowercase()) {
    "gateway" -> Icons.Rounded.Router
    "agent" -> Icons.Rounded.SmartToy
    "dashboard" -> Icons.Rounded.Dashboard
    "storage" -> Icons.Rounded.Storage
    "platforms" -> Icons.Rounded.Hub
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

/** A Hermes state word ("running") as the phone says it: a known word is translated, any other is capitalised as it arrived. */
@Composable internal fun serverStateText(raw: String): String = when (raw.trim().lowercase()) {
    "running" -> uiText("Running")
    "stopped" -> uiText("Stopped")
    "starting" -> uiText("Starting")
    else -> raw.trim().replaceFirstChar { it.uppercase() }
}

/** A component's name: the four Hermes names are translated, an unknown one shows capitalised. */
@Composable internal fun serverComponentName(raw: String): String = when (raw.trim().lowercase()) {
    "gateway" -> uiText("Gateway")
    "agent" -> uiText("Agent")
    "dashboard" -> uiText("Dashboard")
    "storage" -> uiText("Storage")
    "platforms" -> uiText("Platforms")
    else -> raw.trim().replaceFirstChar { it.uppercase() }
}

private val connectedCounts = Regex("""^(\d+)\s*/\s*(\d+)\s+connected$""")

/** "1 / 1 connected" as its two numbers, or null for any other detail. */
internal fun serverConnectedCounts(detail: String): Pair<Int, Int>? =
    connectedCounts.matchEntire(detail.trim())?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

/** What a component row says under its name: the platform count and the gateway state in the phone's words; any other reason as Hermes sent it. */
@Composable private fun serverComponentDetail(detail: String): String {
    val counts = serverConnectedCounts(detail)
    return when {
        counts != null -> uiText("{0} of {1} connected").replace("{0}", "${counts.first}").replace("{1}", "${counts.second}")
        detail.trim().lowercase() in setOf("running", "stopped", "starting") -> serverStateText(detail)
        else -> detail
    }
}

@Composable private fun serverComponentLabel(status: SettingsStatus, raw: String): String = when (status) {
    SettingsStatus.Ok -> uiText("Healthy")
    SettingsStatus.Attention -> uiText("Needs attention")
    SettingsStatus.Error -> uiText("Failed")
    else -> if (raw.isBlank()) uiText("Not reported") else serverStateText(raw)
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
        if (state.notice.isNotBlank() && !(page == "Server updates" && serverMessageRestatesHero(state.notice, serverUpdateIsCurrent(state)))) item { SettingsSection(testTag = "server-notice") { SettingsValueRow(uiText(state.notice), icon = Icons.Rounded.Info) } }
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
                uiText("Gateway") to if (state.gatewayState.isBlank()) uiText("Not reported") else serverStateText(state.gatewayState),
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
                    label = serverComponentName(component.name),
                    icon = serverComponentIcon(component.name),
                    value = serverComponentDetail(component.detail),
                    status = status,
                    statusLabel = serverComponentLabel(status, component.status),
                    testTag = "server-component-${component.name}",
                )
            }
        }
    }
}

/** Code and log text stays left to right whatever the layout direction: a command or timestamp must not reorder. */
internal fun TextStyle.codeDirection(): TextStyle = copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Left)

/** How far a scrolling chip row fades out at an edge that has more beyond it. */
internal val ChipRowFade = 24.dp

/** A 24 dp fade to fully transparent at each edge of a horizontally scrolling chip row that has more beyond it, so a half-shown chip reads as "scroll" and not as cut off. */
internal fun Modifier.chipRowEdgeFade(scroll: ScrollState): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
    drawContent()
    val fade = ChipRowFade.toPx().coerceAtMost(size.width / 2)
    // The scroll starts at the layout's start edge: the left in a left-to-right layout, the right in Arabic.
    val moreAtStart = scroll.value > 0
    val moreAtEnd = scroll.value < scroll.maxValue
    val moreLeft = if (layoutDirection == LayoutDirection.Rtl) moreAtEnd else moreAtStart
    val moreRight = if (layoutDirection == LayoutDirection.Rtl) moreAtStart else moreAtEnd
    if (moreLeft) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade), Offset.Zero, Size(fade, size.height), blendMode = BlendMode.DstIn)
    if (moreRight) drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width), Offset(size.width - fade, 0f), Size(fade, size.height), blendMode = BlendMode.DstIn)
}

/** Runs a row out across the [padding] its page keeps at each side, to the screen edges; the row's own content padding then sits inside its scroll. */
internal fun Modifier.bleedHorizontally(padding: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val extra = padding.roundToPx()
    val full = constraints.maxWidth + 2 * extra
    val placeable = measurable.measure(constraints.copy(minWidth = full, maxWidth = full))
    // The page still sees its own column width; the row is drawn and touched across the padding on both sides.
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

/** From this text scale the four log filters wrap onto lines instead of scrolling: a chip half out of view at large text hides which logs exist. */
internal fun logFiltersWrap(fontScale: Float): Boolean = isLargeText(fontScale)

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun LogFilterChips(state: NativeServerState, onLogFile: (String) -> Unit) {
    val chips: @Composable () -> Unit = {
        serverLogFiles.forEach { file ->
            FilterChip(selected = state.logFile == file, onClick = { onLogFile(file) }, enabled = !state.loading,
                label = { Text(serverLogFileLabel(file)) }, leadingIcon = { Icon(serverLogFileIcon(file), null, Modifier.size(18.dp)) },
                modifier = Modifier.heightIn(min = 48.dp))
        }
    }
    if (logFiltersWrap(LocalDensity.current.fontScale)) {
        FlowRow(Modifier.testTag("server-log-filters-wrap"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    } else {
        val scroll = rememberScrollState()
        val pageGap = SettingsKit.PagePadding
        // The row reaches both screen edges, its first chip stays one page gap in, and the edge that has more beyond it fades out.
        Box(Modifier.testTag("server-log-filters-scroll").bleedHorizontally(pageGap).fillMaxWidth().chipRowEdgeFade(scroll).horizontalScroll(scroll)) {
            Row(Modifier.padding(horizontal = pageGap), horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
        }
    }
}

/** How long typing in the log search pauses before the lines are read again. */
internal const val LogSearchPauseMillis = 400L

private fun LazyListScope.serverLogs(state: NativeServerState, onLogFile: (String) -> Unit, onLogSearch: (String) -> Unit, onReload: () -> Unit) {
    item { LogFilterChips(state, onLogFile) }
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
                // Log lines are English text with timestamps: in an Arabic layout they keep their left-to-right order, or the punctuation jumps to the front.
                state.logs.isNotEmpty() -> SelectionContainer {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.logs.forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace).codeDirection()) }
                        }
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
                Text(command, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace).codeDirection(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                SettingsIconButton(Icons.Rounded.ContentCopy, uiText("Copy"), { copyServerText(context, command) })
            }
        }
    }
    // "You're on the latest version." under a hero that already says "You're up to date" is the same news twice.
    if (message.isNotBlank() && !serverMessageRestatesHero(message, serverUpdateIsCurrent(state))) item {
        SettingsSection(testTag = "server-update-message") { SettingsValueRow(uiText(message), icon = Icons.Rounded.Info) }
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
