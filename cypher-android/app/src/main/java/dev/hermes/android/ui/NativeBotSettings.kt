package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hermes.android.data.NativeBotsState

@Composable fun NativeBotSettings(state: NativeBotsState, onOpen: (String) -> Unit, onBack: () -> Unit,
    onEditModel: () -> Unit, onModelDraft: (String, String) -> Unit, onSaveModel: () -> Unit, onCancel: () -> Unit,
    onSettings: (String, String) -> Unit, onRefresh: () -> Unit, models: List<Pair<String, String>> = emptyList(), renderBehavior: @Composable (String) -> Unit = { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyLarge) } }) {
    var showBehavior by rememberSaveable(state.selected?.profile) { mutableStateOf(false) }
    var modelSearch by rememberSaveable(state.selected?.profile, state.editing) { mutableStateOf("") }
    var customModel by rememberSaveable(state.selected?.profile, state.editing) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Profile defaults & behavior", style = MaterialTheme.typography.titleLarge); Text("Review each assistant’s purpose, behavior and default model.") }
        if (state.loading || state.saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.error.isNotBlank()) item { Text(state.error, color = MaterialTheme.colorScheme.error) }
        if (state.notice.isNotBlank()) item { Text(state.notice) }
        if (state.selected == null) {
            if (!state.loading && state.profiles.isEmpty()) item { Text("No authorized profiles were returned. Refresh to try again.") }
            items(state.profiles, key = { it.profile }) { bot ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(bot.title, style = MaterialTheme.typography.titleMedium); Text(bot.purpose.ifBlank { "No purpose description has been written." }); OutlinedButton(onClick = { onOpen(bot.profile) }, enabled = !state.loading) { Text("View profile settings") }; HorizontalDivider() }
            }
        } else {
            val bot = state.selected
            item { Text(bot.title, style = MaterialTheme.typography.titleLarge); TextButton(onClick = onBack, enabled = !state.saving && !state.editing) { Text("All profiles") } }
            item { Text("Purpose", style = MaterialTheme.typography.titleMedium); SelectionContainer { Text(bot.description.ifBlank { "No purpose description has been written. Native Hermes uses this description to help route Kanban work." }) } }
            item { Text("Default model", style = MaterialTheme.typography.titleMedium); Text("${bot.provider.ifBlank { "Provider not set" }} · ${bot.model.ifBlank { "Model not set" }}"); Text("Default for new chats in this profile.", style = MaterialTheme.typography.bodyMedium) }
            if (state.editing) {
                item {
                    Text("Choose a configured model", style = MaterialTheme.typography.titleMedium)
                    Text("Selected: ${state.draftProvider} · ${state.draftModel}")
                    OutlinedTextField(modelSearch, { modelSearch = it }, label = { Text("Search models or providers") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.saving)
                }
                val visible = models.distinct().filter { modelSearch.isBlank() || "${it.first} ${it.second}".contains(modelSearch, ignoreCase = true) }
                if (visible.isEmpty()) item { Text(if (models.isEmpty()) "No configured model choices were returned. Refresh this profile or enter an existing configured model ID." else "No models match this search.") }
                items(visible, key = { "${it.first}:${it.second}" }) { (provider, model) ->
                    TextButton(onClick = { onModelDraft(provider, model) }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Column(Modifier.fillMaxWidth()) { Text(model); Text(provider + if (provider == state.draftProvider && model == state.draftModel) " · selected" else "", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                item {
                    TextButton(onClick = { customModel = !customModel }, enabled = !state.saving) { Text(if (customModel) "Hide custom model entry" else "Enter a configured model ID") }
                    if (customModel) {
                        OutlinedTextField(state.draftProvider, { onModelDraft(it, state.draftModel) }, label = { Text("Configured provider ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.saving)
                        OutlinedTextField(state.draftModel, { onModelDraft(state.draftProvider, it) }, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.saving)
                    }
                    Text("Hermes validates the selection against this profile’s configured providers.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onSaveModel, enabled = !state.saving && state.draftModel.isNotBlank() && state.draftProvider.isNotBlank() && (state.draftModel != bot.model || state.draftProvider != bot.provider)) { Text("Save default model") }
                    TextButton(onClick = onCancel, enabled = !state.saving) { Text("Cancel edit") }
                }
            } else item { OutlinedButton(onClick = onEditModel, enabled = !state.loading) { Text("Change default model") } }
            item { Text("Behavior instructions", style = MaterialTheme.typography.titleMedium); TextButton(onClick = { showBehavior = !showBehavior }) { Text(if (showBehavior) "Hide behavior instructions" else "Read behavior instructions") }; if (showBehavior) { renderBehavior(bot.soul.ifBlank { "No SOUL.md instructions are stored for this profile." }); Text("Read-only here. These are this profile’s authored SOUL.md instructions.", style = MaterialTheme.typography.bodyMedium) } }
            item { TextButton(onClick = { onSettings(bot.profile, "Skills") }, enabled = !state.editing) { Text("Skills for this profile") }; TextButton(onClick = { onSettings(bot.profile, "Memory") }, enabled = !state.editing) { Text("Memory for this profile") }; TextButton(onClick = { onSettings(bot.profile, "Profiles & model") }, enabled = !state.editing) { Text("Profiles & model for this profile") } }
        }
        item { TextButton(onClick = onRefresh, enabled = !state.loading && !state.saving && !state.editing) { Text("Refresh profiles") } }
    }
}
