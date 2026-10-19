package dev.hermes.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.CypherViewModel
import dev.hermes.android.core.*
import org.json.JSONArray
import org.json.JSONObject

@Composable fun NativeCatalog(vm: CypherViewModel) {
    val kind by vm.catalog.collectAsStateWithLifecycle()
    if (kind.isBlank()) return
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val reasoningSaving by vm.reasoningSaving.collectAsStateWithLifecycle()
    var query by rememberSaveable(kind, chat.sessionId) { mutableStateOf("") }
    Dialog(vm::closeCatalog, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 900.dp).fillMaxWidth().fillMaxHeight(.92f).padding(12.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface.copy(alpha = 1f)) {
            Column(Modifier.fillMaxSize()) {
                Header(kind, "${chat.title}") { IconButton(onClick = vm::closeCatalog) { Icon(Icons.Rounded.Close, uiText("Close picker")) } }
                when (kind) {
                    "Model & provider" -> {
                        val catalog = data["picker:models"]
                        val loading = "picker:models" in refreshing
                        val groups = remember(catalog, query) { catalog?.rows("groups").orEmpty().map { group ->
                            val provider = group.string("provider", group.string("label", group.string("provider_id")))
                            group to (group.rows("models") + group.rows("extra_models")).distinctBy { it.string("id") }.filter { modelSearchMatches(provider, it.string("id"), it.string("label"), query) }
                        }.filter { it.second.isNotEmpty() } }
                        OutlinedTextField(query, { query = it }, label = { Text(uiText("Search model or provider")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                            groups.forEach { (group, models) ->
                                val provider = group.string("provider_id")
                                val label = group.string("provider", group.string("label", provider))
                                if (models.isNotEmpty()) {
                                    item { Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp)) }
                                    items(models, key = { provider to it.string("id") }) { model ->
                                        val id = model.string("id")
                                        ListItem(headlineContent = { Text(model.string("label", id)) }, supportingContent = { Text(id, style = MaterialTheme.typography.bodySmall) }, trailingContent = { if (chat.raw.string("model") == id && chat.raw.string("model_provider") == provider) Text(uiText("Selected"), color = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { vm.pickModel(id, provider) }.heightIn(min = 64.dp))
                                    }
                                }
                            }
                            item { if (catalog == null || loading) LinearProgressIndicator(Modifier.fillMaxWidth()); if (catalog != null && !loading && recordError(catalog).isBlank() && groups.isEmpty()) Text("No matching models. Try another search."); TextButton(onClick = { vm.openCatalog(kind) }, enabled = !loading) { Text(if (loading) "Refreshing models…" else "Refresh models") }; recordError(catalog).takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }; TextButton(onClick = { vm.closeCatalog(); vm.openForm(ActionForm("Custom model", "/api/session/update", listOf(Field("model", required = true), Field("model_provider", "Provider ID")), json("session_id" to chat.sessionId))) }) { Text(uiText("Enter a configured model ID")) } }
                        }
                    }
                    "Toolsets" -> NativeToolSettings(vm)
                    "Reasoning" -> {
                        val value = data["picker:reasoning"]
                        val options = value?.optJSONArray("options")?.strings().orEmpty()
                        var level by remember(value?.toString()) { mutableStateOf(value?.string("value").orEmpty().takeIf { it in options }.orEmpty()) }
                        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Choose how much reasoning this model uses for this conversation.")
                            value?.string("value")?.takeIf(String::isNotBlank)?.let { Text("Current effort: $it") }
                            if (options.isNotEmpty()) Choice("Reasoning effort", level, options, { level = it }, enabled = !reasoningSaving && "picker:reasoning" !in refreshing && recordError(value).isBlank())
                            else if (value != null && "picker:reasoning" !in refreshing && recordError(value).isBlank()) Text(if (value.optBoolean("known")) "This model manages reasoning automatically." else "Reasoning choices could not be confirmed. Refresh to try again; your current setting is retained.")
                            if (options.isNotEmpty()) Button(onClick = { vm.setReasoning(level) }, enabled = !reasoningSaving && "picker:reasoning" !in refreshing && recordError(value).isBlank() && level in options) { Text(if (reasoningSaving) "Applying..." else "Apply to conversation") }
                            if (reasoningSaving || "picker:reasoning" in refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick = { vm.openCatalog(kind) }, enabled = !reasoningSaving && "picker:reasoning" !in refreshing) { Text("Refresh reasoning") }
                            recordError(value).takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}

/** Each administration destination has a native operation or a concrete reader. */
@Composable fun NativeAdministration(vm: CypherViewModel, page: String) {
    val data by vm.data.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    var query by rememberSaveable(page, ui.origin, ui.owner, ui.profile) { mutableStateOf("") }
    LaunchedEffect(page, ui.origin, ui.owner, ui.profile, chat.sessionId) {
        if (page == "Commands") vm.loadSlashCatalog()
        if (page == "Session usage") vm.load("native-usage:${ui.profile}:${chat.sessionId}", "/api/session/usage", mapOf("session_id" to chat.sessionId))
    }
    Column(Modifier.fillMaxSize()) {
        Header(page, back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current)
        when (page) {
            "Tools & commands" -> NativeToolSettings(vm)
            "MCP servers" -> NativeToolSettings(vm, mcp = true)
            "Providers & models" -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Model for this chat.")
                Text(chat.title, style = MaterialTheme.typography.titleMedium)
                Text(chat.raw.string("model").ifBlank { "Uses the profile default" })
                Button(onClick = { vm.openCatalog("Model & provider") }, enabled = chat.sessionId.isNotBlank()) { Text("Change conversation model") }
                OutlinedButton(onClick = { vm.more("Profiles") }) { Text("Manage profile defaults") }
            }
            "Commands" -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Choose a command to prepare it in Chat. Review it before sending."); OutlinedTextField(query, { query = it }, label = { Text("Search commands") }, modifier = Modifier.fillMaxWidth(), singleLine = true); TextButton(onClick = { vm.loadSlashCatalog(force = true) }) { Text(uiText("Refresh commands")) } }
                val catalog = data["slash:catalog"]
                if (catalog == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (recordError(catalog).isNotBlank()) item { Text(recordError(catalog), color = MaterialTheme.colorScheme.error) }
                val commands = catalog?.rows("commands").orEmpty().filter { query.isBlank() || (it.string("name") + it.string("description")).contains(query, true) }
                if (catalog != null && commands.isEmpty()) item { Text("No matching commands") }
                items(commands) { row -> ActionRow("/${row.string("name")}", row.string("description")) { vm.tab("Chat"); vm.composer(androidx.compose.ui.text.input.TextFieldValue("/${row.string("name").removePrefix("/")} ")) } }
            }
            "Session usage" -> LazyColumn(contentPadding = PaddingValues(20.dp)) {
                item { val usage = data["native-usage:${ui.profile}:${chat.sessionId}"]; if (usage == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else if (recordError(usage).isNotBlank()) Text(recordError(usage), color = MaterialTheme.colorScheme.error) else StructuredRead(usage); TextButton(onClick = { vm.load("native-usage:${ui.profile}:${chat.sessionId}", "/api/session/usage", mapOf("session_id" to chat.sessionId)) }) { Text("Refresh usage") } }
            }
            else -> LaunchedEffect(page) { vm.tab("More") }
        }
    }
}
