package dev.hermes.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hermes.android.data.NativeMemoryState

/** Body only: the shell owns navigation, profile scope, drafts and safe-area insets. */
@Composable fun NativeMemoryScreen(
    state: NativeMemoryState,
    onEdit: (String) -> Unit,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    renderNotes: @Composable (String) -> Unit = { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyLarge) } },
) {
    var selected by rememberSaveable(state.scope) { mutableStateOf("memory") }
    var explanation by rememberSaveable(state.scope) { mutableStateOf(false) }
    val target = state.editing.ifBlank { selected }
    val current = state.documents.firstOrNull { it.target == target } ?: state.documents.firstOrNull()
    BackHandler(enabled = state.editing.isNotBlank() && !state.saving, onBack = onCancel)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.loading && state.provider.isNotBlank() && !state.providerReady) item { Text("Memory recall is not ready. Your profile notes remain available.", color = MaterialTheme.colorScheme.error) }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.error.isNotBlank()) item { Text(state.error, color = MaterialTheme.colorScheme.error); if (state.editing.isNotBlank()) Text("Your unsaved draft is kept. Review newer notes before saving again.", style = MaterialTheme.typography.bodyMedium) }
        if (state.message.isNotBlank()) item { Text(state.message, color = MaterialTheme.colorScheme.primary) }
        if (state.documents.isNotEmpty()) item {
            TabRow(selectedTabIndex = state.documents.indexOfFirst { it.target == current?.target }.coerceAtLeast(0)) {
                state.documents.forEach { notes -> Tab(selected = notes.target == current?.target, enabled = state.editing.isBlank() && !state.saving, onClick = { selected = notes.target }, text = { Text(notes.title) }) }
            }
        }
        items(state.documents.filter { it.target == current?.target }, key = { it.target }) { document ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (document.target == "memory") "Facts and working notes your agent keeps." else "Preferences and background you want your agent to know.", style = MaterialTheme.typography.bodyMedium)
                if (!document.enabled) Text("Stored, but disabled for new chats in this profile.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.editing == document.target) {
                    OutlinedTextField(state.draft, onDraft, modifier = Modifier.fillMaxWidth(), label = { Text("Edit ${document.title.lowercase()}") }, minLines = 6, maxLines = 12, enabled = !state.saving,
                        supportingText = { Text("${state.draft.length} / ${document.limit} characters") }, isError = state.draft.length > document.limit)
                    Button(onClick = onSave, enabled = !state.saving && state.draft != document.content && state.draft.length <= document.limit) { Text(if (state.saving) "Saving…" else "Save notes") }
                    TextButton(onClick = onCancel, enabled = !state.saving) { Text("Cancel edit") }
                } else {
                    OutlinedButton(onClick = { onEdit(document.target) }, enabled = state.editing.isBlank() && !state.loading && !state.saving) { Text("Edit ${document.title.lowercase()}") }
                    // Reading decoration never changes the source sent to the editor.
                    val entries = remember(document.content) { document.content.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "").split(Regex("(?:\r?\n)§(?:\r?\n)")).map(String::trim).filter(String::isNotBlank) }
                    if (entries.isEmpty()) Text("No notes yet. Add what you want your agent to remember.")
                    entries.forEachIndexed { index, entry -> renderNotes(entry); if (index < entries.lastIndex) HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
                }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Memory service · ${state.activeLabel}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { explanation = !explanation }) { Text(if (explanation) "Hide how memory works" else "How memory works") }
            if (explanation) {
                if (state.provider.isNotBlank()) Text("${state.activeLabel} stores additional recall separately. These profile notes are not its full knowledge, and editing them does not erase its stored recall.", style = MaterialTheme.typography.bodyMedium)
                Text("Used by new chats.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
