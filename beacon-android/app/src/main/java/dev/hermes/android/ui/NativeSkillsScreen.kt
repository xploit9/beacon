@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SearchOff
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
    val enableLabel = uiText("Enable")
    var filter by rememberSaveable(state.profile) { mutableStateOf("All") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(SettingsPagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (state.selected == null) {
                Text(uiText("{0} installed · {1} enabled · {2} need setup").replace("{0}", "${state.skills.size}").replace("{1}", "${state.skills.count { it.enabled }}").replace("{2}", "${state.skills.count { it.requirements.isNotEmpty() }}"), style = MaterialTheme.typography.titleMedium)
                Text(uiText("Changes apply to new sessions."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); if (state.saving) Text(uiText("Saving skill…")) }
        state.notice?.let { notice -> item { Text(uiText(notice)) } }
        state.error?.let { error -> item { Text(uiText(error), color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = onRetry, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Retry")) } } }
        if (state.selected == null) {
            item { OutlinedTextField(value = state.search, onValueChange = onSearch, label = { Text(uiText("Search skills")) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("All", "Enabled", "Needs setup").forEach { choice -> FilterChip(selected = filter == choice, onClick = { filter = choice }, label = { Text(uiText(choice)) }) } } }
            val visible = state.skills.filter {
                (filter == "All" || filter == "Enabled" && it.enabled || filter == "Needs setup" && it.requirements.isNotEmpty()) &&
                    (state.search.isBlank() || "${it.name} ${it.description} ${it.category}".contains(state.search, ignoreCase = true))
            }.sortedWith(compareByDescending<NativeSkill> { it.enabled }.thenBy { it.name.lowercase() })
            if (visible.isEmpty() && !state.loading) item {
                if (state.search.isNotBlank() || filter != "All") EmptyState(uiText("No matching skills"), uiText("Try another search or filter."), Icons.Rounded.SearchOff, action = uiText("Clear search"), onAction = { onSearch(""); filter = "All" })
                else EmptyState(uiText("No skills yet"), uiText("Skills installed for this profile show up here."), SkillsIcon)
            }
            items(visible, key = { it.name }) { skill ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Button) { onOpen(skill) }.padding(vertical = 10.dp).testTag("skill-row-${skill.name}"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, style = MaterialTheme.typography.titleMedium)
                            Text(skill.description.ifBlank { uiText("Read this skill for setup instructions.") }, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            val status = listOfNotNull(uiText("Essential").takeIf { skill.essential }, uiText("Needs setup").takeIf { skill.requirements.isNotEmpty() }).joinToString(" · ")
                            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = skill.enabled, onCheckedChange = { onToggle(skill, it) }, enabled = !busy && !skill.essential, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "${enableLabel} ${skill.name}" })
                    }
                    HorizontalDivider()
                }
            }
        } else {
            val skill = state.selected
            item { Text(skill.name, style = MaterialTheme.typography.headlineSmall) }
            item { Text(skill.description); Text(uiText(if (skill.requirements.isEmpty()) "Setup ready" else "Setup needed"), style = MaterialTheme.typography.titleMedium); if (skill.requirements.isNotEmpty()) Text(skill.requirements.joinToString("\n")); Text(uiText("Based on what the skill says it needs."), color = MaterialTheme.colorScheme.onSurfaceVariant); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(uiText(if (skill.essential) "Essential · always enabled" else if (skill.enabled) "Enabled" else "Disabled"), modifier = Modifier.weight(1f)); Switch(checked = skill.enabled, onCheckedChange = { onToggle(skill, it) }, enabled = !busy && !skill.essential, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "${enableLabel} ${skill.name}" }) } }
            state.content?.let { content ->
                if (state.editing) {
                    item { Text(uiText("Adjust instructions"), style = MaterialTheme.typography.titleMedium); Text(uiText("Replaces this skill's instructions for new chats.")) }
                    item { OutlinedTextField(value = state.draft, onValueChange = onDraft, enabled = !state.saving, label = { Text(uiText("Skill instructions")) }, minLines = 8, maxLines = 16, modifier = Modifier.fillMaxWidth()) }
                    item { Column { Button(onClick = onSave, enabled = !busy && state.draft.isNotBlank() && state.draft != content.content, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(uiText("Save instructions")) }; TextButton(onClick = { onEdit(false) }, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Cancel")) } } }
                } else {
                    item { OutlinedButton(onClick = { onEdit(true) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Adjust instructions")) } }
                    item { renderInstructions(content.content) }
                }
            }
        }
    }
}
