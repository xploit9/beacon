@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

/** What a tool group or MCP server shows under its name in place of its description. */
internal enum class ToolPillKind { TestFailed, Tested, FromPlugin, SetupNeeded }
internal data class ToolPill(val kind: ToolPillKind, val status: SettingsStatus, val count: Int = 0)

/**
 * The one state a row calls out: a failed or passed MCP test first, then a group a plugin manages, then a group that still needs setup.
 * [testedTools] is the tool count of a passed test (null before any test), and null is returned when the row has nothing to flag.
 */
internal fun toolRowPill(mcp: Boolean, managedByPlugin: Boolean, configured: Boolean, testedTools: Int?, testFailed: Boolean): ToolPill? = when {
    mcp && testFailed -> ToolPill(ToolPillKind.TestFailed, SettingsStatus.Error)
    mcp && testedTools != null -> ToolPill(ToolPillKind.Tested, SettingsStatus.Ok, testedTools)
    managedByPlugin -> ToolPill(ToolPillKind.FromPlugin, SettingsStatus.Off)
    !mcp && !configured -> ToolPill(ToolPillKind.SetupNeeded, SettingsStatus.Attention)
    else -> null
}

@Composable private fun toolPillText(pill: ToolPill, plugin: String): String = when (pill.kind) {
    ToolPillKind.TestFailed -> uiText("Test failed")
    ToolPillKind.Tested -> if (pill.count == 1) uiText("1 tool") else uiText("{0} tools").replace("{0}", "${pill.count}")
    ToolPillKind.FromPlugin -> uiText("From {0}").replace("{0}", plugin)
    ToolPillKind.SetupNeeded -> uiText("Setup needed")
}

/**
 * Tool groups and MCP servers for the open profile. The same page opens in Settings and in the chat's Tools sheet, so it is a plain
 * [SettingsList] with no header of its own; [containedActions] only marks the sheet, whose rows already clear 48dp.
 */
@Composable internal fun NativeToolSettings(vm: BeaconViewModel, mcp: Boolean = false, @Suppress("UNUSED_PARAMETER") containedActions: Boolean = false) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val key = if (mcp) "native-mcp" else "native-tools"
    val path = if (mcp) "/api/mcp/servers" else "/api/tools/toolsets"
    val loading = key in refreshing
    val client = vm.client
    var search by rememberSaveable(ui.origin, ui.owner, ui.profile, mcp) { mutableStateOf("") }
    var busy by remember(client, ui.origin, ui.owner, ui.profile, mcp) { mutableStateOf("") }
    var error by remember(client, ui.origin, ui.owner, ui.profile, mcp) { mutableStateOf("") }
    var failedTests by remember(client, ui.origin, ui.owner, ui.profile, mcp) { mutableStateOf(emptySet<String>()) }
    var opened by remember(client, ui.origin, ui.owner, ui.profile, mcp) { mutableStateOf("") }
    val saved = uiText("Saved for new chats")
    val couldNotFinish = uiText("Couldn't finish. Try again.")
    val notConfirmed = uiText("Hermes did not confirm this change. Refresh to see the saved state.")
    val couldNotSave = uiText(if (mcp) "Couldn't save the MCP server. Try again." else "Couldn't save the tool change. Try again.")
    fun refresh(force: Boolean = true) { error = ""; vm.load(key, path, mapOf("profile" to ui.profile), force = force) }
    LaunchedEffect(path, ui.origin, ui.owner, ui.profile, ui.connected, client) { refresh(force = false) }
    // A save runs on after the window closes; this says whether its own error line is still on screen.
    val shown = remember(mcp) { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(mcp) { onDispose { shown.set(false) } }
    fun operate(name: String, enabled: Boolean? = null) {
        val active = vm.client ?: return
        val c = active.detached()
        busy = name; error = ""
        vm.viewModelScope.launch {
            try {
                val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                val operation = if (mcp) "$path/$encoded/${if (enabled == null) "test" else "enabled"}" else "$path/$encoded"
                val response = c.nativeGet(operation, mapOf("profile" to c.auth.profile), method = if (enabled == null) "POST" else "PUT", data = if (enabled == null) json() else json("enabled" to enabled, "profile" to c.auth.profile))
                require(response.optBoolean("ok")) { notConfirmed }
                if (vm.client === active) {
                    if (enabled == null) { failedTests = failedTests - name; vm.storeData("mcp-test:$name", response) }
                    else { vm.notice(saved); if (mcp) vm.storeData("mcp-test:$name", json()); refresh() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (vm.client === active) { error = couldNotFinish; if (enabled == null) failedTests = failedTests + name; if (!shown.get()) vm.notice(couldNotSave) } }
            finally { c.closeNative(); if (vm.client === active) busy = "" }
        }
    }
    val record = data[key]
    val problem = error.ifBlank { recordError(record) }
    val rows = record?.rows(if (mcp) "servers" else "items").orEmpty().filter { search.isBlank() || (it.string("name") + it.string("label") + it.string("description")).contains(search, true) }
    val retry = uiText("Retry")
    val idle = busy.isBlank() && !loading
    SettingsList(refreshing = loading && record != null, testTag = if (mcp) "mcp-settings" else "tool-settings") {
        item(key = "search") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(search, { search = it }, Modifier.weight(1f), label = { Text(uiText(if (mcp) "Search MCP servers" else "Search tools")) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true)
                SettingsIconButton(Icons.Rounded.Refresh, uiText(if (mcp) "Refresh MCP servers" else "Refresh tools"), { refresh() }, Modifier.testTag("tools-refresh"), enabled = idle)
            }
        }
        item(key = "list") {
            val empty = record != null && rows.isEmpty() && problem.isBlank()
            SettingsSection(footer = if (mcp && empty && search.isBlank()) uiText("Add servers on your computer") else "", testTag = if (mcp) "mcp-list" else "tools-list") {
                if (record == null && problem.isBlank()) SettingsSkeletonRows(3)
                if (problem.isNotBlank()) {
                    // With a saved copy the rows stay and the line says the refresh failed; with none, the line is the page.
                    SettingsErrorRow(if (error.isNotBlank() || rows.isEmpty()) problem else uiText("Couldn't refresh"), { refresh() }, retry, retryTestTag = "tools-retry")
                    if (rows.isNotEmpty()) SettingsDivider()
                }
                if (empty) when {
                    search.isNotBlank() -> SettingsEmpty(uiText("No matches"), Icons.Rounded.SearchOff, action = SettingsAction(uiText("Clear search"), { search = "" }))
                    mcp -> SettingsEmpty(uiText("No MCP servers"), Icons.Rounded.Hub, action = SettingsAction(uiText("Refresh"), { refresh() }))
                    else -> SettingsEmpty(uiText("No tools yet"), Icons.Rounded.Build, action = SettingsAction(uiText("Refresh"), { refresh() }))
                }
                rows.forEachIndexed { index, row ->
                    val name = row.string("name")
                    if (index > 0) SettingsDivider()
                    val test = if (mcp) data["mcp-test:$name"]?.takeIf { row.optBoolean("enabled") } else null
                    val tools = if (mcp) test?.rows("tools")?.map { it.string("name") }.orEmpty() else row.optJSONArray("tools")?.strings().orEmpty()
                    ToolRow(row, mcp, saving = busy == name, enabled = (busy.isBlank() || busy == name) && !loading, idle = idle,
                        testedTools = test?.takeIf { it.optBoolean("ok") }?.rows("tools")?.size, testFailed = name in failedTests,
                        tools = tools, expanded = opened == name, onExpand = { opened = if (opened == name) "" else name },
                        onToggle = { operate(name, it) }, onTest = { operate(name) })
                }
            }
        }
        if (!mcp) item(key = "more") {
            val browseCommands = { vm.closeCatalog(); vm.loadSlashCatalog(); vm.more("Commands") }
            SettingsSection(title = uiText("More"), testTag = "tools-more-section") {
                SettingsNavRow(uiText("Chat commands"), browseCommands, icon = Icons.Rounded.Terminal, minHeight = SettingsKit.RowMinHeight, testTag = "tools-commands")
            }
        }
    }
}

/** One tool group or server: its switch with a state pill, then Test and View tools under the name. */
@Composable private fun ToolRow(
    row: JSONObject, mcp: Boolean, saving: Boolean, enabled: Boolean, idle: Boolean, testedTools: Int?, testFailed: Boolean,
    tools: List<String>, expanded: Boolean, onExpand: () -> Unit, onToggle: (Boolean) -> Unit, onTest: () -> Unit,
) {
    val name = row.string("name")
    val managedByPlugin = row.string("source") == "plugin"
    val pill = toolRowPill(mcp, managedByPlugin, row.optBoolean("configured"), testedTools, testFailed)
    val motion = rememberNativeMotionEnabled()
    Column(Modifier.fillMaxWidth().animateContentSize(tween(if (motion) 200 else 0))) {
        SettingsSwitchRow(row.string("label", name), row.optBoolean("enabled"), onToggle, icon = if (mcp) Icons.Rounded.Hub else Icons.Rounded.Build,
            summary = row.string("description"), status = pill?.status, statusLabel = pill?.let { toolPillText(it, row.string("plugin")) }.orEmpty(),
            enabled = enabled && !managedByPlugin, busy = saving, testTag = "tool-row:$name")
        if (mcp || tools.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = SettingsKit.DividerStart - 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mcp) {
                val testLabel = uiText("Test")
                TextButton(onClick = onTest, enabled = idle, modifier = Modifier.heightIn(min = SettingsKit.TouchMin).testTag("tool-test:$name").semantics { contentDescription = "$testLabel $name" }) { Text(testLabel) }
            }
            if (tools.isNotEmpty()) {
                val stateText = uiText(if (expanded) "Expanded" else "Collapsed")
                TextButton(onClick = onExpand, modifier = Modifier.heightIn(min = SettingsKit.TouchMin).testTag("tool-expand:$name").semantics { stateDescription = stateText }) {
                    Text(if (expanded) uiText("Hide tools") else "${uiText("View tools")} · ${tools.size}")
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.padding(start = 4.dp).size(18.dp))
                }
            }
        }
        if (expanded && tools.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(start = SettingsKit.DividerStart, end = 16.dp, bottom = 12.dp).testTag("tool-names:$name"),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tools.forEach { SettingsBadge(it) }
        }
    }
}
