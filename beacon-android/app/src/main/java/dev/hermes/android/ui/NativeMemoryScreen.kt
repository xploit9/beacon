@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hermes.android.data.NativeMemoryState

/** Lines a note shows before it folds; a longer one opens on tap. */
internal const val MemoryNoteCollapsedLines = 3

/** Notes in a document are separated by a line holding only §; reading decoration never changes the source sent to the editor. */
internal fun memoryEntries(content: String): List<String> =
    content.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "").split(Regex("(?:\r?\n)§(?:\r?\n)")).map(String::trim).filter(String::isNotBlank)

/** The length counter is only worth showing when the limit is close: from 80% of it on. */
internal fun memoryCounterVisible(length: Int, limit: Int): Boolean = limit > 0 && length * 5 >= limit * 4

/** Body only: the shell owns navigation, profile scope, drafts and safe-area insets. */
@Composable fun NativeMemoryScreen(
    state: NativeMemoryState,
    onEdit: (String) -> Unit,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    renderNotes: @Composable (String) -> Unit = {
        val maxLines = LocalMarkdownMaxLines.current
        val cut = LocalMarkdownCut.current
        SelectionContainer { Text(it, style = MaterialTheme.typography.bodyLarge.contentDirection(), maxLines = maxLines, overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis, onTextLayout = { layout -> if (layout.hasVisualOverflow) cut() }) }
    },
) {
    var selected by rememberSaveable(state.scope) { mutableStateOf("memory") }
    var explanation by rememberSaveable(state.scope) { mutableStateOf(false) }
    val target = state.editing.ifBlank { selected }
    val current = state.documents.firstOrNull { it.target == target } ?: state.documents.firstOrNull()
    val canEdit = state.editing.isBlank() && !state.loading && !state.saving
    BackHandler(enabled = state.editing.isNotBlank() && !state.saving, onBack = onCancel)
    // Notes already loaded stay on screen while a refresh or save runs; the thin bar is the only sign of it.
    SettingsList(refreshing = (state.loading && state.documents.isNotEmpty()) || state.saving, testTag = "memory-page") {
        if (state.documents.isNotEmpty()) item(key = "tabs") {
            SegmentedChoice("memory", current?.target.orEmpty(), state.documents.map { it.target }, state.documents.map { uiText(it.title) }, { selected = it },
                enabled = state.editing.isBlank() && !state.saving, testTags = state.documents.associate { it.target to "memory-tab-${it.target}" })
        }
        val recallNotReady = !state.loading && state.provider.isNotBlank() && !state.providerReady
        val off = current != null && !current.enabled
        if (recallNotReady || off || state.message.isNotBlank()) item(key = "status") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recallNotReady) SettingsStatusPill(SettingsStatus.Attention, uiText("Recall not ready"), testTag = "memory-recall-pill")
                if (off) SettingsStatusPill(SettingsStatus.Off, uiText("Off for new chats"), testTag = "memory-off-pill")
                if (state.message.isNotBlank()) SettingsStatusPill(SettingsStatus.Ok, uiText(state.message))
            }
        }
        if (state.error.isNotBlank()) item(key = "error") { SettingsSection {
            SettingsErrorRow(uiText(state.error), null, "")
            if (state.editing.isNotBlank()) Text(uiText("Your unsaved draft is kept. Review newer notes before saving again."), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (state.documents.isEmpty() && state.loading) item(key = "skeleton") { SettingsSection { SettingsSkeletonRows(3) } }
        if (current != null) item(key = "document:${current.target}") {
            val editLabel = uiText(if (current.target == "memory") "Edit agent notes" else "Edit about you")
            SettingsSection(testTag = "memory-document") {
                if (state.editing == current.target) Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val over = state.draft.length > current.limit
                    OutlinedTextField(state.draft, onDraft, modifier = Modifier.fillMaxWidth(), label = { Text(editLabel) }, minLines = 6, maxLines = 12, enabled = !state.saving,
                        supportingText = if (memoryCounterVisible(state.draft.length, current.limit) || over) ({ Text("${state.draft.length} / ${current.limit}") }) else null, isError = over)
                    Button(onClick = onSave, enabled = !state.saving && state.draft != current.content && !over, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(uiText(if (state.saving) "Saving…" else "Save notes")) }
                    TextButton(onClick = onCancel, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Cancel edit")) }
                } else {
                    val entries = remember(current.content) { memoryEntries(current.content) }
                    if (entries.isEmpty()) SettingsEmpty(uiText("No notes yet"), MemoryIcon, action = if (canEdit) SettingsAction(uiText("Add notes"), { onEdit(current.target) }, testTag = "memory-add") else null)
                    else {
                        val expanded = remember(current.target, current.content) { mutableStateListOf<Int>() }
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            entries.forEachIndexed { index, entry ->
                                NoteCard(entry, index in expanded, { if (index in expanded) expanded.remove(index) else expanded.add(index) }, Modifier.testTag("memory-note-$index")) { renderNotes(entry) }
                            }
                        }
                        SettingsDivider()
                        SettingsActionRow(editLabel, { onEdit(current.target) }, icon = Icons.Rounded.Edit, enabled = canEdit, testTag = "memory-edit")
                    }
                }
            }
        }
        item(key = "service") { SettingsSection {
            SettingsActionRow(uiText(if (explanation) "Hide how memory works" else "How memory works"), { explanation = !explanation }, icon = Icons.Rounded.Info, testTag = "memory-how")
            if (explanation) {
                SettingsDivider()
                SettingsValueRow(uiText("Memory service"), icon = MemoryIcon, value = uiText(state.activeLabel), testTag = "memory-service")
                if (state.provider.isNotBlank()) Text(uiText("{0} keeps more than these notes").replace("{0}", state.activeLabel), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
    }
}

/**
 * One note as a compact card: a long one folds to [MemoryNoteCollapsedLines] lines ending in an ellipsis and opens on tap. The text is limited by lines,
 * never by a fixed height, so a line is never cut in half at any text size. The card learns that the note is long when its text reports being cut;
 * it keeps offering to fold once opened.
 */
@Composable private fun NoteCard(entry: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var folds by remember(entry) { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val scheme = MaterialTheme.colorScheme
    val moreLabel = uiText("Show more"); val lessLabel = uiText("Show less")
    val collapsedWord = uiText("Collapsed"); val expandedWord = uiText("Expanded")
    Surface(
        modifier.fillMaxWidth().clip(shape).then(if (folds) Modifier.clickable(onClickLabel = if (expanded) lessLabel else moreLabel, role = Role.Button, onClick = onToggle).semantics { stateDescription = if (expanded) expandedWord else collapsedWord } else Modifier),
        shape = shape, color = scheme.surfaceContainerHigh.copy(alpha = .6f), border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .6f)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            CompositionLocalProvider(LocalMarkdownMaxLines provides (if (expanded) Int.MAX_VALUE else MemoryNoteCollapsedLines), LocalMarkdownCut provides { folds = true }) {
                Box(Modifier.fillMaxWidth().animateContentSize(tween(if (rememberNativeMotionEnabled()) 200 else 0))) { content() }
            }
            if (folds) Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.align(Alignment.End).size(20.dp), tint = scheme.onSurfaceVariant)
        }
    }
}
