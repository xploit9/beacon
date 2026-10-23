package dev.hermes.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import org.json.JSONArray
import org.json.JSONObject

@Composable fun NativeCatalog(vm: BeaconViewModel, containedActions: Boolean = false) {
    val kind by vm.catalog.collectAsStateWithLifecycle()
    if (kind.isBlank()) return
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val reasoningSaving by vm.reasoningSaving.collectAsStateWithLifecycle()
    val modelSaving by vm.modelSaving.collectAsStateWithLifecycle()
    val pending by vm.conversationPending.collectAsStateWithLifecycle()
    val scopeKey = vm.client?.scope?.session(chat.sessionId)?.key
    val modelPending = modelSaving.isNotBlank() || "$scopeKey|/api/session/update" in pending
    val reasoningPending = reasoningSaving || "$scopeKey|/api/reasoning" in pending
    var query by rememberSaveable(kind, chat.sessionId) { mutableStateOf("") }
    Dialog(vm::closeCatalog, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 900.dp).fillMaxWidth().fillMaxHeight(.92f).padding(12.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface.copy(alpha = 1f)) {
            Column(Modifier.fillMaxSize()) {
                Header(kind, chat.title, actions = {
                    if (kind == "Reasoning" || kind == "Model & provider") IconButton(
                        onClick = { vm.openCatalog(kind) },
                        enabled = if (kind == "Reasoning") !reasoningPending && "picker:reasoning" !in refreshing else !modelPending && "picker:models" !in refreshing,
                    ) {
                        Icon(Icons.Rounded.Refresh, uiText(if (kind == "Reasoning") "Reload reasoning choices" else "Refresh models"))
                    }
                    IconButton(onClick = vm::closeCatalog) { Icon(Icons.Rounded.Close, uiText("Close picker")) }
                })
                when (kind) {
                    "Model & provider" -> {
                        val catalog = data["picker:models"]
                        val loading = catalog == null || "picker:models" in refreshing
                        // Hermes may omit the session's provider; then the group holding the current model is the current one.
                        val currentProvider = chat.raw.string("model_provider").ifBlank {
                            catalog?.rows("groups").orEmpty().firstOrNull { g -> (g.rows("models") + g.rows("extra_models")).any { it.string("id") == chat.raw.string("model") } }?.string("provider_id").orEmpty()
                        }
                        val groups = remember(catalog, query, currentProvider) { catalog?.rows("groups").orEmpty().map { group ->
                            val provider = group.string("provider", group.string("label", group.string("provider_id")))
                            group to (group.rows("models") + group.rows("extra_models")).distinctBy { it.string("id") }.filter { modelSearchMatches(provider, it.string("id"), it.string("label"), query) }
                        }.filter { it.second.isNotEmpty() }.sortedBy { it.first.string("provider_id") != currentProvider } }
                        OutlinedTextField(query, { query = it }, label = { Text(uiText("Search model or provider")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                            if (loading) item { Column(Modifier.testTag("models-loading")) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Loading models…", Modifier.padding(vertical = 12.dp)) } }
                            if (modelPending && modelSaving.isBlank()) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Applying model…") }
                            groups.forEach { (group, models) ->
                                val provider = group.string("provider_id")
                                val label = group.string("provider", group.string("label", provider))
                                if (models.isNotEmpty()) {
                                    item { Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp)) }
                                    items(models, key = { provider to it.string("id") }) { model ->
                                        val id = model.string("id")
                                        val title = model.string("label", id)
                                        val selected = chat.raw.string("model") == id && currentProvider == provider
                                        ListItem(headlineContent = { Text(title) },
                                            supportingContent = if (modelSubtitle(title, id).isNotBlank()) ({ Text(id, style = MaterialTheme.typography.bodySmall) }) else null,
                                            trailingContent = { when {
                                                modelSaving == "$provider:$id" -> CircularProgressIndicator(Modifier.size(24.dp).testTag("model-applying"), strokeWidth = 2.dp)
                                                selected -> Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                                            } },
                                            modifier = Modifier.testTag("model:$provider:$id").semantics { this.selected = selected }.clickable(enabled = !loading && !modelPending) { vm.pickModel(id, provider) }.heightIn(min = 64.dp))
                                    }
                                }
                            }
                            item {
                                if (catalog != null && !loading && recordError(catalog).isBlank() && groups.isEmpty()) Text("No matching models. Try another search.")
                                if (recordError(catalog).isNotBlank()) Text("Couldn't load models. Tap refresh to try again.", color = MaterialTheme.colorScheme.error)
                                val enterCustomModel = { vm.closeCatalog(); vm.openForm(ActionForm("Custom model", "/api/session/update", listOf(Field("model", required = true), Field("model_provider", "Provider ID")), json("session_id" to chat.sessionId))) }
                                if (!loading) {
                                    if (containedActions) OutlinedButton(onClick = enterCustomModel, enabled = !modelPending, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Enter a configured model ID")) }
                                    else TextButton(onClick = enterCustomModel, enabled = !modelPending) { Text(uiText("Enter a configured model ID")) }
                                }
                            }
                        }
                    }
                    "Toolsets" -> {
                        val record = data["picker:toolsets"]
                        Column(Modifier.fillMaxSize().padding(20.dp).testTag("chat-toolsets")) {
                            Text("Toolsets are shared by all chats in this profile.")
                            TextButton(onClick = { vm.closeCatalog(); vm.more("Tools & commands") }, modifier = Modifier.testTag("toolsets-profile-settings")) { Text("Change for all chats") }
                            if (record == null || "picker:toolsets" in refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (recordError(record).isNotBlank()) Text("Couldn't load toolsets. Reopen to try again.", color = MaterialTheme.colorScheme.error)
                            LazyColumn(Modifier.weight(1f)) {
                                items(record?.rows("items").orEmpty(), key = { it.string("name") }) { row ->
                                    ListItem(headlineContent = { Text(row.string("label", row.string("name"))) }, supportingContent = { Text(if (row.optBoolean("enabled")) "Enabled for this profile" else "Disabled for this profile") })
                                }
                            }
                        }
                    }
                    "Reasoning" -> {
                        val value = data["picker:reasoning"]
                        val options = value?.optJSONArray("options")?.strings().orEmpty()
                        var level by remember(value?.toString()) { mutableStateOf(value?.string("value").orEmpty().takeIf { it in options }.orEmpty()) }
                        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Choose a reasoning level for this conversation.", style = MaterialTheme.typography.bodyLarge)
                            if (value == null || "picker:reasoning" in refreshing) ReasoningSkeleton()
                            else if (recordError(value).isNotBlank()) Text("Reasoning options are unavailable. Your current setting is unchanged.", color = MaterialTheme.colorScheme.error)
                            else if (options.isNotEmpty()) {
                                value.string("value").takeIf(String::isNotBlank)?.let { Text("Current: ${reasoningLevelLabel(it)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                options.forEach { option -> ReasoningLevelOption(option, level == option, enabled = !reasoningPending) { level = option } }
                                Button(onClick = { vm.setReasoning(level) }, enabled = !reasoningPending && level in options, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (reasoningPending) "Applying…" else "Apply to conversation") }
                            } else if (value.optBoolean("known")) Text("This model manages reasoning automatically.")
                            else Text("No reasoning choices are available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

internal fun modelSubtitle(title: String, id: String): String = id.takeUnless { it == title }.orEmpty()
internal fun reasoningLevelLabel(level: String): String = if (level == "xhigh") "Extra high" else level.replaceFirstChar(Char::uppercase)
internal fun reasoningLevelDescription(level: String): String = when (level.lowercase()) {
    "none" -> "Respond without a separate reasoning step"
    "minimal" -> "Use the least reasoning for quick replies"
    "low" -> "Faster, lighter responses"
    "medium" -> "Balanced detail"
    "high" -> "More detailed reasoning"
    "xhigh" -> "Spend more time on difficult problems"
    "max" -> "Use the model's maximum reasoning budget"
    "ultra" -> "Use extended reasoning when the provider supports it"
    else -> "Use the model's default reasoning"
}

@Composable private fun ReasoningLevelOption(level: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Surface(
        onClick = onSelect,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("reasoning-level:$level"),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected, onClick = null, enabled = enabled)
            Column(Modifier.padding(start = 8.dp)) {
                Text(reasoningLevelLabel(level), style = MaterialTheme.typography.titleSmall)
                Text(reasoningLevelDescription(level), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun ReasoningSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().testTag("reasoning-skeleton")) {
        BeaconSignal(BeaconMotionMode.Sweep, size = 28.dp)
        repeat(3) { index ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f), modifier = Modifier.fillMaxWidth(if (index == 1) .48f else .62f).height(12.dp)) {}
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .05f), modifier = Modifier.fillMaxWidth(.82f).height(9.dp)) {}
                }
            }
        }
    }
}

/** Each administration destination has a native operation or a concrete reader. */
@Composable fun NativeAdministration(vm: BeaconViewModel, page: String) {
    val data by vm.data.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var query by rememberSaveable(page, ui.origin, ui.owner, ui.profile) { mutableStateOf("") }
    LaunchedEffect(page, ui.origin, ui.owner, ui.profile, chat.sessionId) {
        if (page == "Commands") vm.loadSlashCatalog()
        if (page == "Session usage") vm.load("native-usage:${ui.profile}:${chat.sessionId}", "/api/session/usage", mapOf("session_id" to chat.sessionId))
    }
    Column(Modifier.fillMaxSize()) {
        Header(page, back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current, actions = {
            if (page == "Commands") IconButton(onClick = { vm.loadSlashCatalog(force = true) }, enabled = "slash:catalog" !in refreshing) { Icon(Icons.Rounded.Refresh, uiText("Refresh commands")) }
            if (page == "Session usage") IconButton(onClick = { vm.load("native-usage:${ui.profile}:${chat.sessionId}", "/api/session/usage", mapOf("session_id" to chat.sessionId)) }, enabled = "native-usage:${ui.profile}:${chat.sessionId}" !in refreshing) { Icon(Icons.Rounded.Refresh, uiText("Refresh usage")) }
        })
        when (page) {
            "Tools & commands" -> NativeToolSettings(vm, containedActions = true)
            "MCP servers" -> NativeToolSettings(vm, mcp = true, containedActions = true)
            "Providers & models" -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Model for this chat.")
                Text(chat.title, style = MaterialTheme.typography.titleMedium)
                Text(chat.raw.string("model").ifBlank { "Uses the profile default" })
                Button(onClick = { vm.openCatalog("Model & provider") }, enabled = chat.sessionId.isNotBlank()) { Text("Change conversation model") }
                OutlinedButton(onClick = { vm.more("Profiles") }) { Text("Manage profile defaults") }
            }
            "Commands" -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Choose a command to prepare it in Chat. Review it before sending."); OutlinedTextField(query, { query = it }, label = { Text("Search commands") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                val catalog = data["slash:catalog"]
                if (catalog == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (recordError(catalog).isNotBlank()) item { Text(recordError(catalog), color = MaterialTheme.colorScheme.error) }
                val commands = mobileSlashCommands(catalog?.rows("commands").orEmpty()).filter { query.isBlank() || (it.string("name") + it.string("description")).contains(query, true) }
                if (catalog != null && commands.isEmpty()) item { Text("No matching commands") }
                items(commands) { row -> ActionRow("/${row.string("name")}", row.string("description")) { vm.tab("Chat"); vm.composer(androidx.compose.ui.text.input.TextFieldValue("/${row.string("name").removePrefix("/")} ")) } }
            }
            "Session usage" -> LazyColumn(contentPadding = PaddingValues(20.dp)) {
                item { val usage = data["native-usage:${ui.profile}:${chat.sessionId}"]; if (usage == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else if (recordError(usage).isNotBlank()) Text(recordError(usage), color = MaterialTheme.colorScheme.error) else StructuredRead(usage) }
            }
            else -> LaunchedEffect(page) { vm.tab("More") }
        }
    }
}
