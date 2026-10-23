@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import dev.hermes.android.data.NativeSkill
import dev.hermes.android.data.NativeSkillContent

data class NativeSkillsState(
    val profile: String = "", val skills: List<NativeSkill> = emptyList(), val search: String = "",
    val selected: NativeSkill? = null, val content: NativeSkillContent? = null,
    val draft: String = "", val editing: Boolean = false, val loading: Boolean = false,
    val saving: Boolean = false, val error: String? = null, val notice: String? = null,
)

/** Body only: the shared shell owns the toolbar, Back handling, and system insets. */
@Composable fun NativeSkillsScreen(
    state: NativeSkillsState, onSearch: (String) -> Unit, onOpen: (NativeSkill) -> Unit,
    onBack: () -> Unit, onToggle: (NativeSkill, Boolean) -> Unit,
    onEdit: (Boolean) -> Unit, onDraft: (String) -> Unit, onSave: () -> Unit,
    onRetry: () -> Unit, renderInstructions: @Composable (String) -> Unit = { SelectionContainer { Text(it) } },
) {
    val busy = state.loading || state.saving
    var filter by rememberSaveable(state.profile) { mutableStateOf("All") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (state.selected == null) {
                Text("${state.skills.size} installed · ${state.skills.count { it.enabled }} enabled · ${state.skills.count { it.requirements.isNotEmpty() }} need setup", style = MaterialTheme.typography.titleMedium)
                Text("Changes apply to new sessions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (state.saving) "Saving skill…" else "Loading skills…") }
        state.notice?.let { notice -> item { Text(notice) } }
        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = onRetry, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") } } }
        if (state.selected == null) {
            item { OutlinedTextField(value = state.search, onValueChange = onSearch, label = { Text("Search skills") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("All", "Enabled", "Needs setup").forEach { choice -> FilterChip(selected = filter == choice, onClick = { filter = choice }, label = { Text(choice) }, modifier = Modifier.heightIn(min = 48.dp)) } } }
            val visible = state.skills.filter {
                (filter == "All" || filter == "Enabled" && it.enabled || filter == "Needs setup" && it.requirements.isNotEmpty()) &&
                    (state.search.isBlank() || "${it.name} ${it.description} ${it.category}".contains(state.search, ignoreCase = true))
            }.sortedWith(compareByDescending<NativeSkill> { it.enabled }.thenBy { it.name.lowercase() })
            if (visible.isEmpty() && !state.loading) item { Text(if (state.search.isNotBlank() || filter != "All") "No skills match this filter and search." else "No installed skills in this profile.") }
            items(visible, key = { it.name }) { skill ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Button) { onOpen(skill) }.padding(vertical = 10.dp).testTag("skill-row-${skill.name}"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, style = MaterialTheme.typography.titleMedium)
                            Text(skill.description.ifBlank { "Read this skill for setup instructions." }, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            val status = listOfNotNull("Essential".takeIf { skill.essential }, "Needs setup".takeIf { skill.requirements.isNotEmpty() }).joinToString(" · ")
                            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = skill.enabled, onCheckedChange = { onToggle(skill, it) }, enabled = !busy && !skill.essential, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Enable ${skill.name}" })
                    }
                    HorizontalDivider()
                }
            }
        } else {
            val skill = state.selected
            item { Text(skill.name, style = MaterialTheme.typography.headlineSmall); TextButton(onClick = onBack, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("All skills") } }
            item { Text(skill.description); Text(if (skill.requirements.isEmpty()) "Setup ready" else "Setup needed", style = MaterialTheme.typography.titleMedium); if (skill.requirements.isNotEmpty()) Text(skill.requirements.joinToString("\n")); Text("Checks declared requirements only.", color = MaterialTheme.colorScheme.onSurfaceVariant); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(if (skill.essential) "Essential · always enabled" else if (skill.enabled) "Enabled" else "Disabled", modifier = Modifier.weight(1f)); Switch(checked = skill.enabled, onCheckedChange = { onToggle(skill, it) }, enabled = !busy && !skill.essential, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Enable ${skill.name}" }) } }
            state.content?.let { content ->
                if (state.editing) {
                    item { Text("Adjust instructions", style = MaterialTheme.typography.titleMedium); Text("Replaces this skill's instructions for new chats.") }
                    item { OutlinedTextField(value = state.draft, onValueChange = onDraft, enabled = !state.saving, label = { Text("Skill instructions") }, minLines = 8, maxLines = 16, modifier = Modifier.fillMaxWidth()) }
                    item { Column { Button(onClick = onSave, enabled = !busy && state.draft.isNotBlank() && state.draft != content.content, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save instructions") }; TextButton(onClick = { onEdit(false) }, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } } }
                } else {
                    item { OutlinedButton(onClick = { onEdit(true) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Adjust instructions") } }
                    item { renderInstructions(content.content) }
                }
            }
        }
    }
}
