@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hermes.android.data.NativeSkill
import dev.hermes.android.data.NativeSkillContent

data class NativeSkillsState(
    val profile: String = "", val skills: List<NativeSkill> = emptyList(), val search: String = "",
    val selected: NativeSkill? = null, val content: NativeSkillContent? = null,
    val draft: String = "", val editing: Boolean = false, val loading: Boolean = false,
    val saving: Boolean = false, val error: String? = null, val notice: String? = null,
    /** The skill whose switch is being saved: it changes at once, and only the switches wait for it. */
    val toggling: String? = null,
)

/** The two notices a successful save puts in [NativeSkillsState.notice] (NativePhoneSettings writes them). Any other notice is a warning from the server and never reads as success. */
internal const val SkillsSavedNotice = "Saved. New chats use this change."
internal const val SkillInstructionsSavedNotice = "Instructions saved. New chats use this change."
internal fun skillNoticeIsSuccess(notice: String): Boolean = notice == SkillsSavedNotice || notice == SkillInstructionsSavedNotice

/** The state with one skill switched on or off right away, before Hermes confirms; the open skill follows. Calling it again with the old value is the rollback. */
internal fun NativeSkillsState.withSkillEnabled(name: String, enabled: Boolean): NativeSkillsState =
    copy(skills = skills.map { if (it.name == name) it.copy(enabled = enabled) else it }, selected = selected?.let { if (it.name == name) it.copy(enabled = enabled) else it })

/** "apple-notes" reads "Apple Notes": skills are stored under slugs, people read names. */
/** Words that read as letters keep their capitals ("Autonomous AI Agents", "MCP Servers"). */
private val SkillNameAcronyms = setOf("ai", "api", "mcp", "pdf", "url", "sql", "ui", "ux", "llm", "csv", "json", "html", "css", "ocr", "tts", "seo", "sms", "id")
internal fun skillDisplayName(name: String): String =
    name.split('-', '_').filter(String::isNotBlank).joinToString(" ") { word ->
        if (word.lowercase() in SkillNameAcronyms) word.uppercase() else word.replaceFirstChar(Char::titlecase)
    }.ifBlank { name }

/** The filter ids are "all", "on" (enabled) and "setup" (needs setup); a search matches the name as stored, as shown, the description and the category. */
internal fun skillMatches(skill: NativeSkill, filter: String, search: String): Boolean =
    (filter == "all" || filter == "on" && skill.enabled || filter == "setup" && skill.requirements.isNotEmpty()) &&
        (search.isBlank() || "${skill.name} ${skillDisplayName(skill.name)} ${skill.description} ${skill.category}".contains(search.trim(), ignoreCase = true))

/**
 * The skills as sections: enabled first, then by name. With two or more categories each category is a section (the key is the
 * category, blank for "Other"); with one the list is a single section whose key is null.
 */
internal fun skillSections(skills: List<NativeSkill>): List<Pair<String?, List<NativeSkill>>> {
    val ordered = skills.sortedWith(compareByDescending<NativeSkill> { it.enabled }.thenBy { it.name.lowercase() })
    val byCategory = ordered.groupBy { it.category.trim().lowercase() }
    if (byCategory.size <= 1) return listOf(null to ordered)
    return byCategory.entries.sortedWith(compareBy({ it.key.isBlank() }, { it.key })).map { it.key to it.value }
}

/** Body only: the shared shell owns the toolbar, Back handling, and system insets. */
@Composable fun NativeSkillsScreen(
    state: NativeSkillsState, onSearch: (String) -> Unit, onOpen: (NativeSkill) -> Unit,
    onBack: () -> Unit, onToggle: (NativeSkill, Boolean) -> Unit,
    onEdit: (Boolean) -> Unit, onDraft: (String) -> Unit, onSave: () -> Unit,
    onRetry: () -> Unit, renderInstructions: @Composable (String) -> Unit = { SelectionContainer { Text(it) } },
) {
    val busy = state.loading || state.saving
    val enableLabel = uiText("Enable")
    var filter by rememberSaveable(state.profile) { mutableStateOf("all") }
    val selected = state.selected
    // Cached skills stay on screen while a refresh or save runs; the thin bar is the only sign of it.
    SettingsList(refreshing = busy && (state.skills.isNotEmpty() || selected != null), testTag = "skills-page") {
        state.notice?.takeIf { it.isNotBlank() }?.let { notice -> item(key = "notice") {
            if (skillNoticeIsSuccess(notice)) SettingsStatusPill(SettingsStatus.Ok, uiText(notice))
            else SettingsSection { SettingsValueRow(uiText(notice), icon = Icons.Rounded.ErrorOutline) }
        } }
        if (state.saving) item(key = "saving") { SettingsStatusPill(SettingsStatus.Working, uiText("Saving skill…")) }
        // While instructions are being edited, a failed save shows beside the Save button, where the owner is looking.
        val editingInstructions = selected != null && state.editing && state.content != null
        state.error?.takeUnless { editingInstructions }?.let { error -> item(key = "error") { SettingsSection { SettingsErrorRow(uiText(error), if (busy) null else onRetry, uiText("Retry")) } } }
        if (selected == null) skillList(state, filter, { filter = it }, busy, enableLabel, onSearch, onOpen, onToggle)
        else skillDetail(selected, state, busy, enableLabel, onToggle, onEdit, onDraft, onSave, renderInstructions)
    }
}

private fun LazyListScope.skillList(
    state: NativeSkillsState, filter: String, onFilter: (String) -> Unit, busy: Boolean, enableLabel: String,
    onSearch: (String) -> Unit, onOpen: (NativeSkill) -> Unit, onToggle: (NativeSkill, Boolean) -> Unit,
) {
    val all = state.skills
    if (all.isEmpty() && state.loading) { item(key = "skeleton") { SettingsSection { SettingsSkeletonRows(4) } }; return }
    item(key = "counts") { SettingsSection(testTag = "skills-counts") {
        SettingsMetricRow(listOf(uiText("Installed") to "${all.size}", uiText("Enabled") to "${all.count { it.enabled }}", uiText("Need setup") to "${all.count { it.requirements.isNotEmpty() }}"))
    } }
    item(key = "search") { OutlinedTextField(value = state.search, onValueChange = onSearch, label = { Text(uiText("Search skills")) }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
    item(key = "filter") {
        val ids = listOf("all", "on", "setup")
        val labels = listOf(uiText("All"), uiText("Enabled"), uiText("Needs setup"))
        val tags = mapOf("all" to "skills-filter-all", "on" to "skills-filter-on", "setup" to "skills-filter-needs-setup")
        // Three segments cut their words at large text; wrapping chips keep every word whole (the pattern ReasoningChoice uses).
        if (isLargeText(LocalDensity.current.fontScale)) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ids.forEachIndexed { index, id ->
                FilterChip(selected = filter == id, onClick = { if (filter != id) onFilter(id) }, label = { Text(labels[index]) },
                    leadingIcon = if (filter == id) ({ Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(tags.getValue(id)))
            }
        } else SegmentedChoice("skills", filter, ids, labels, onFilter, testTags = tags)
    }
    val visible = all.filter { skillMatches(it, filter, state.search) }
    if (visible.isEmpty() && !state.loading) item(key = "empty") { SettingsSection {
        if (state.search.isNotBlank() || filter != "all") SettingsEmpty(uiText("No matching skills"), Icons.Rounded.SearchOff, action = SettingsAction(uiText("Clear search"), { onSearch(""); onFilter("all") }))
        else SettingsEmpty(uiText("No skills yet"), SkillsIcon)
    } }
    skillSections(visible).filter { it.second.isNotEmpty() }.forEach { (category, skills) ->
        item(key = "section:${category.orEmpty()}:${category == null}") {
            SettingsSection(title = when { category == null -> ""; category.isBlank() -> uiText("Other"); else -> skillDisplayName(category) }) {
                skills.forEachIndexed { index, skill ->
                    if (index > 0) SettingsDivider()
                    SkillRow(skill, busy, state.toggling != null, enableLabel, { onOpen(skill) }) { onToggle(skill, it) }
                }
            }
        }
    }
}

/** A skill: its name, one line of description and, when it can't work yet, a "Needs setup" pill. The row opens it; the switch is its own target. */
@Composable private fun SkillRow(skill: NativeSkill, busy: Boolean, switchSaving: Boolean, enableLabel: String, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(enabled = !busy, role = Role.Button, onClick = onOpen).padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp).testTag("skill-row-${skill.name}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsIconTile(SkillsIcon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(skillDisplayName(skill.name), style = MaterialTheme.typography.bodyLarge.contentDirection(), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(skill.description.ifBlank { uiText("No description") }, style = MaterialTheme.typography.bodyMedium.contentDirection(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (skill.requirements.isNotEmpty()) SettingsStatusPill(SettingsStatus.Attention, uiText("Needs setup"))
            else if (skill.essential) SettingsStatusPill(SettingsStatus.Off, uiText("Always on"))
        }
        Switch(checked = skill.enabled, onCheckedChange = onToggle, enabled = !busy && !switchSaving && !skill.essential, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$enableLabel ${skill.name}" })
    }
}

private fun LazyListScope.skillDetail(
    skill: NativeSkill, state: NativeSkillsState, busy: Boolean, enableLabel: String, onToggle: (NativeSkill, Boolean) -> Unit,
    onEdit: (Boolean) -> Unit, onDraft: (String) -> Unit, onSave: () -> Unit, renderInstructions: @Composable (String) -> Unit,
) {
    val ready = skill.requirements.isEmpty()
    val status = if (ready) SettingsStatus.Ok else SettingsStatus.Attention
    item(key = "hero") {
        SettingsStatusHero(status, skillDisplayName(skill.name), line = skill.description.ifBlank { uiText("No description") },
            meta = { SettingsStatusPill(status, uiText(if (ready) "Ready" else "Needs setup")) }, testTag = "skill-hero")
    }
    item(key = "enabled") { SettingsSection {
        SettingsSwitchRow(uiText("Enabled"), skill.enabled, { onToggle(skill, it) }, Modifier.semantics { contentDescription = "$enableLabel ${skill.name}" }, icon = Icons.Rounded.PowerSettingsNew,
            summary = if (skill.essential) uiText("Always on") else "", enabled = !busy && state.toggling == null && !skill.essential, testTag = "skill-enabled")
    } }
    if (!ready) item(key = "needs") { SettingsSection(title = uiText("Needs"), testTag = "skill-needs") {
        skill.requirements.forEachIndexed { index, requirement ->
            if (index > 0) SettingsDivider()
            SettingsValueRow(requirement, icon = Icons.Rounded.Key)
        }
    } }
    val content = state.content
    if (content != null) item(key = "instructions") { SettingsSection(title = uiText("Instructions")) {
        if (state.editing) Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = state.draft, onValueChange = onDraft, enabled = !state.saving, label = { Text(uiText("Skill instructions")) }, minLines = 8, maxLines = 16, modifier = Modifier.fillMaxWidth())
            state.error?.let { error -> SettingsErrorRow(uiText(error), null, uiText("Retry"), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Button(onClick = onSave, enabled = !busy && state.draft.isNotBlank() && state.draft != content.content, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(uiText("Save instructions")) }
            TextButton(onClick = { onEdit(false) }, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Cancel")) }
        } else {
            Box(Modifier.fillMaxWidth().padding(16.dp)) { renderInstructions(content.content) }
            SettingsDivider()
            SettingsActionRow(uiText("Edit instructions"), { onEdit(true) }, icon = Icons.Rounded.Edit, enabled = !busy, testTag = "skill-edit")
        }
    } }
    else if (state.loading) item(key = "instructions-loading") { SettingsSection(title = uiText("Instructions")) { SettingsSkeletonRows(2) } }
}
