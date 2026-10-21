package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.CypherViewModel
import dev.hermes.android.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URLEncoder

@Composable internal fun NativeToolSettings(vm: CypherViewModel, mcp: Boolean = false) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val key = if (mcp) "native-mcp" else "native-tools"
    val path = if (mcp) "/api/mcp/servers" else "/api/tools/toolsets"
    val loading = key in refreshing
    val scope = rememberCoroutineScope()
    val client = vm.client
    var search by rememberSaveable(ui.origin, ui.owner, ui.profile, mcp) { mutableStateOf("") }
    var busy by remember(client, ui.origin, ui.owner, ui.profile, mcp) { mutableStateOf("") }
    var error by remember(client, ui.origin, ui.owner, ui.profile, mcp) { mutableStateOf("") }
    var result by remember(client, ui.origin, ui.owner, ui.profile, mcp) { mutableStateOf("") }
    var opened by remember(client, ui.origin, ui.owner, ui.profile, mcp) { mutableStateOf("") }
    fun refresh(force: Boolean = true) { error = ""; vm.load(key, path, mapOf("profile" to ui.profile), force = force) }
    LaunchedEffect(path, ui.origin, ui.owner, ui.profile, ui.connected, client) { refresh(force = false) }
    fun operate(name: String, enabled: Boolean? = null) {
        val active = vm.client ?: return
        val c = active.detached()
        busy = name; error = ""; result = ""
        scope.launch {
            try {
                val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                val operation = if (mcp) "$path/$encoded/${if (enabled == null) "test" else "enabled"}" else "$path/$encoded"
                val response = c.nativeGet(operation, mapOf("profile" to c.auth.profile), method = if (enabled == null) "POST" else "PUT", data = if (enabled == null) json() else json("enabled" to enabled, "profile" to c.auth.profile))
                require(response.optBoolean("ok")) { response.string("error", "Hermes did not confirm this change. Refresh to see the saved state.") }
                if (vm.client === active) {
                    result = if (enabled == null) "Connected to $name · ${response.rows("tools").size} tools available" else "Saved. New chats use this change."
                    if (enabled == null) vm.storeData("mcp-test:$name", response) else { if (mcp) vm.storeData("mcp-test:$name", json()); refresh() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (vm.client === active) error = e.message ?: "Couldn't finish. Try again." }
            finally { c.closeNative(); if (vm.client === active) busy = "" }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(if (mcp) "Connections let Hermes use your services. Test a connection to check its available tools." else "Choose the tool groups available to this profile. Changes apply to new chats.", Modifier.weight(1f))
                IconButton(onClick = { refresh() }, enabled = busy.isBlank() && !loading, modifier = Modifier.semantics { contentDescription = uiText(if (mcp) "Refresh connections" else "Refresh tools") }) {
                    Icon(Icons.Rounded.Refresh, null)
                }
            }
            OutlinedTextField(search, { search = it }, label = { Text(if (mcp) "Search connections" else "Search tools") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        val record = data[key]
        if (record == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        val problem = error.ifBlank { recordError(record) }
        if (problem.isNotBlank()) item { Text(problem, color = MaterialTheme.colorScheme.error) }
        if (result.isNotBlank()) item { Text(result, color = MaterialTheme.colorScheme.primary) }
        val rows = record?.rows(if (mcp) "servers" else "items").orEmpty().filter { search.isBlank() || (it.string("name") + it.string("label") + it.string("description")).contains(search, true) }
        if (record != null && !loading && problem.isBlank() && rows.isEmpty()) item { Text(if (search.isBlank()) "No ${if (mcp) "connections" else "tools"} configured in this profile." else "No matches. Try another search.") }
        items(rows, key = { it.string("name") }) { row ->
            val name = row.string("name")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(row.string("label", name), style = MaterialTheme.typography.titleMedium)
                        if (row.string("description").isNotBlank()) Text(row.string("description"), style = MaterialTheme.typography.bodyMedium)
                        Text(if (mcp) "${if (row.optBoolean("enabled")) "Enabled" else "Disabled"} · ${row.string("transport")}" else "${if (row.optBoolean("enabled")) "Enabled" else "Disabled"} · ${if (row.optBoolean("configured")) "Setup ready" else "Setup needed"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(row.optBoolean("enabled"), { operate(name, it) }, enabled = busy.isBlank() && !loading && row.string("source") != "plugin", modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = row.string("label", name) })
                }
                if (row.string("source") == "plugin") Text("Provided by ${row.string("plugin")}. Managed with that plugin.", style = MaterialTheme.typography.bodySmall)
                if (mcp) OutlinedButton(onClick = { operate(name) }, enabled = busy.isBlank() && !loading) { Text("Test connection") }
                val tools = if (mcp) data["mcp-test:$name"]?.takeIf { row.optBoolean("enabled") }?.rows("tools")?.map { it.string("name") }.orEmpty() else row.optJSONArray("tools")?.strings().orEmpty()
                if (tools.isNotEmpty()) {
                    TextButton(onClick = { opened = if (opened == name) "" else name }) { Text(if (opened == name) "Hide tools" else "View ${tools.size} tools") }
                    if (opened == name) Text(tools.joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }
        }
        if (!mcp) item { TextButton(onClick = { vm.closeCatalog(); vm.loadSlashCatalog(); vm.more("Commands") }) { Text("Browse chat commands") } }
    }
}
