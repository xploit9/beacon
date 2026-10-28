package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URLEncoder

@Composable internal fun NativeToolSettings(vm: BeaconViewModel, mcp: Boolean = false, containedActions: Boolean = false) {
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
    var result by remember(client, ui.origin, ui.owner, ui.profile, mcp) { mutableStateOf("") }
    var opened by remember(client, ui.origin, ui.owner, ui.profile, mcp) { mutableStateOf("") }
    val saved = uiText("Saved. New chats use this change.")
    val connectedTo = uiText("Connected to")
    val toolsAvailable = uiText("tools available")
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
        busy = name; error = ""; result = ""
        vm.viewModelScope.launch {
            try {
                val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                val operation = if (mcp) "$path/$encoded/${if (enabled == null) "test" else "enabled"}" else "$path/$encoded"
                val response = c.nativeGet(operation, mapOf("profile" to c.auth.profile), method = if (enabled == null) "POST" else "PUT", data = if (enabled == null) json() else json("enabled" to enabled, "profile" to c.auth.profile))
                require(response.optBoolean("ok")) { notConfirmed }
                if (vm.client === active) {
                    result = if (enabled == null) "$connectedTo $name · ${response.rows("tools").size} $toolsAvailable" else saved
                    if (enabled == null) vm.storeData("mcp-test:$name", response) else { if (mcp) vm.storeData("mcp-test:$name", json()); refresh() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (vm.client === active) { error = couldNotFinish; if (!shown.get()) vm.notice(couldNotSave) } }
            finally { c.closeNative(); if (vm.client === active) busy = "" }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(SettingsPagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(uiText(if (mcp) "MCP servers let Hermes use your services. Test one to see its tools." else "Choose the tool groups available to this profile. Changes apply to new chats."), Modifier.weight(1f))
                IconButton(onClick = { refresh() }, enabled = busy.isBlank() && !loading) {
                    Icon(Icons.Rounded.Refresh, uiText(if (mcp) "Refresh MCP servers" else "Refresh tools"))
                }
            }
            OutlinedTextField(search, { search = it }, label = { Text(uiText(if (mcp) "Search MCP servers" else "Search tools")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        val record = data[key]
        if (record == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        val problem = error.ifBlank { recordError(record) }
        if (problem.isNotBlank()) item { Text(problem, color = MaterialTheme.colorScheme.error) }
        if (result.isNotBlank()) item { Text(result, color = MaterialTheme.colorScheme.primary) }
        val rows = record?.rows(if (mcp) "servers" else "items").orEmpty().filter { search.isBlank() || (it.string("name") + it.string("label") + it.string("description")).contains(search, true) }
        if (record != null && !loading && problem.isBlank() && rows.isEmpty()) item {
            if (search.isBlank()) EmptyState(uiText(if (mcp) "No MCP servers yet" else "No tools yet"), uiText(if (mcp) "Add one in your Hermes settings on your computer, then refresh." else "Tool groups for this profile show up here."), action = uiText("Refresh"), onAction = { refresh() })
            else EmptyState(uiText("No matches"), uiText("Try another search."), Icons.Rounded.SearchOff, action = uiText("Clear search"), onAction = { search = "" })
        }
        items(rows, key = { it.string("name") }) { row ->
            val name = row.string("name")
            val managedByPlugin = row.string("source") == "plugin"
            val status = if (mcp || row.optBoolean("configured")) "" else uiText("Setup needed")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledToggle(row.string("label", name), row.optBoolean("enabled"), { operate(name, it) },
                    description = listOf(row.string("description"), status).filter(String::isNotBlank).joinToString("\n"),
                    enabled = busy.isBlank() && !loading && !managedByPlugin)
                if (managedByPlugin) Text("${uiText("Provided by")} ${row.string("plugin")}. ${uiText("Managed with that plugin.")}", style = MaterialTheme.typography.bodySmall)
                if (mcp) OutlinedButton(onClick = { operate(name) }, enabled = busy.isBlank() && !loading) { Text(uiText("Test server")) }
                val tools = if (mcp) data["mcp-test:$name"]?.takeIf { row.optBoolean("enabled") }?.rows("tools")?.map { it.string("name") }.orEmpty() else row.optJSONArray("tools")?.strings().orEmpty()
                if (tools.isNotEmpty()) {
                    TextButton(onClick = { opened = if (opened == name) "" else name }, modifier = if (containedActions) Modifier.heightIn(min = 48.dp) else Modifier) { Text(if (opened == name) uiText("Hide tools") else "${uiText("View tools")} · ${tools.size}") }
                    if (opened == name) Text(tools.joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }
        }
        if (!mcp) item {
            val browseCommands = { vm.closeCatalog(); vm.loadSlashCatalog(); vm.more("Commands") }
            if (containedActions) OutlinedButton(onClick = browseCommands, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Browse chat commands")) }
            else TextButton(onClick = browseCommands) { Text(uiText("Browse chat commands")) }
        }
    }
}
