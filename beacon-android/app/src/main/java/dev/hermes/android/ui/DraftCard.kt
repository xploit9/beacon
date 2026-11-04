package dev.hermes.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What a draft card is doing, which decides what it offers: [Editing] can be revised and confirmed, [Done] is final. */
internal enum class DraftPhase { Editing, Revising, Confirming, Done }

/** One labelled line of a draft (To, Subject, a calendar invite's time); [key] names it for tests and accessibility. */
internal data class DraftCardField(val key: String, val label: String, val value: String)

/** The most body lines a card shows before "Show more": a long draft must not take over the chat. */
internal const val DRAFT_BODY_LINES = 10

/**
 * A draft Hermes wrote for the owner to read, reword and approve: its fields and body as a document, a field to say what to change,
 * and one button to confirm. Email uses it now; a calendar invite or a message draft can use the same card with their own fields.
 *
 * Nothing here knows what the draft is. [onRevise] gets the owner's words, [onConfirm] the tap; [onCopy] replaces the confirm button
 * when the draft cannot be confirmed from here. [problem] is a sentence about the last attempt, shown in the error colour.
 */
@Composable internal fun DraftCard(
    title: String,
    icon: ImageVector,
    fields: List<DraftCardField>,
    body: String,
    phase: DraftPhase,
    ready: Boolean,
    reviseText: String,
    onReviseText: (String) -> Unit,
    reviseHint: String,
    reviseLabel: String,
    onRevise: () -> Unit,
    confirmLabel: String,
    onConfirm: (() -> Unit)?,
    workingText: String,
    doneText: String,
    problem: String,
    versionText: String?,
    previousLabel: String,
    nextLabel: String,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    copyLabel: String,
    onCopy: (() -> Unit)?,
    showMoreLabel: String,
    showLessLabel: String,
    modifier: Modifier = Modifier,
    key: String = "",
) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    var overflowing by remember(key, body) { mutableStateOf(false) }
    val editable = phase == DraftPhase.Editing && ready
    Surface(modifier.fillMaxWidth().testTag("draft-card"), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(title, Modifier.weight(1f).testTag("draft-title"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (versionText != null) {
                    IconButton(onClick = { onPrevious?.invoke() }, enabled = onPrevious != null && editable, modifier = Modifier.size(48.dp).testTag("draft-previous")) {
                        Icon(Icons.Rounded.ChevronLeft, previousLabel, Modifier.size(20.dp))
                    }
                    Text(versionText, Modifier.testTag("draft-version"), style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null && editable, modifier = Modifier.size(48.dp).testTag("draft-next")) {
                        Icon(Icons.Rounded.ChevronRight, nextLabel, Modifier.size(20.dp))
                    }
                }
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    fields.filter { it.value.isNotBlank() }.forEach { field ->
                        Row(Modifier.fillMaxWidth().testTag("draft-field:${field.key}"), verticalAlignment = Alignment.Top) {
                            Text(field.label, Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(field.value, Modifier.weight(1f).testTag("draft-value:${field.key}"), style = MaterialTheme.typography.bodyMedium.contentDirection(),
                                fontWeight = if (field.key == "subject") androidx.compose.ui.text.font.FontWeight.SemiBold else null)
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(body, Modifier.fillMaxWidth().testTag("draft-body"), style = MaterialTheme.typography.bodyMedium.contentDirection(),
                        maxLines = if (expanded) Int.MAX_VALUE else DRAFT_BODY_LINES, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { overflowing = it.hasVisualOverflow })
                }
            }
            if (overflowing || expanded) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).testTag("draft-more")) { Text(if (expanded) showLessLabel else showMoreLabel) }
            when (phase) {
                DraftPhase.Revising, DraftPhase.Confirming -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("draft-progress"))
                    Text(workingText, Modifier.testTag("draft-status"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DraftPhase.Done -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(min = 40.dp).testTag("draft-done")) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(doneText, Modifier.testTag("draft-status"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                DraftPhase.Editing -> {
                    if (problem.isNotBlank()) Text(problem, Modifier.testTag("draft-problem"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (phase == DraftPhase.Editing || phase == DraftPhase.Revising) {
                val submit = { if (editable && reviseText.isNotBlank()) onRevise() }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        reviseText, onReviseText, Modifier.weight(1f).testTag("draft-revise-input"), enabled = editable,
                        placeholder = { Text(reviseHint, maxLines = 1, overflow = TextOverflow.Ellipsis) }, maxLines = 3,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }),
                        trailingIcon = {
                            IconButton(onClick = submit, enabled = editable && reviseText.isNotBlank(), modifier = Modifier.testTag("draft-revise-go")) {
                                Icon(Icons.Rounded.Edit, reviseLabel)
                            }
                        },
                    )
                    if (onConfirm != null) Button(onClick = onConfirm, enabled = editable, modifier = Modifier.heightIn(min = 56.dp).testTag("draft-confirm")) { Text(confirmLabel) }
                    else if (onCopy != null) OutlinedButton(onClick = onCopy, enabled = ready, modifier = Modifier.heightIn(min = 56.dp).testTag("draft-copy")) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(copyLabel)
                    }
                }
            }
        }
    }
}
