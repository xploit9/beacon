package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hermes.android.voice.CallTranscript
import dev.hermes.android.voice.orderCallTranscriptLines
import dev.hermes.android.voice.visibleCallTranscriptLines

/** The native adapter is closed: identity alone never enables a retired WebUI action. */
internal fun nativeConversationAction(action: String): Boolean = action in setOf(
    "New conversation", "Rename", "Pin", "Archive", "Branch", "Model & provider", "Reasoning", "Toolsets", "Compress context", "Undo last turn", "Usage", "Delete conversation"
)

/** Column widths in dp: the widest line ([measure] gives its width in dp) plus padding, kept within 100-280. */
internal fun tableColumnWidths(rows: List<List<String>>, measure: (String) -> Int = { it.length * 7 }): List<Int> =
    (0 until (rows.maxOfOrNull { it.size } ?: 0)).map { column ->
        (rows.take(200).maxOfOrNull { row -> row.getOrNull(column).orEmpty().lineSequence().maxOfOrNull(measure) ?: 0 }?.plus(24) ?: 100).coerceIn(100, 280)
    }

internal fun slashMatches(name: String, description: String, draft: String): Boolean =
    draft.startsWith('/') && !draft.substringAfter('/').contains(' ') &&
        (name.removePrefix("/").startsWith(draft.removePrefix("/"), true) || draft.removePrefix("/").length >= 3 && description.contains(draft.removePrefix("/"), true))

internal fun nativeSlashName(draft: String, names: List<String>): String? {
    if (!draft.startsWith('/')) return null
    val name = draft.substringBefore(' ').removePrefix("/")
    return names.firstOrNull { it.removePrefix("/").equals(name, true) }?.removePrefix("/")
}

internal data class CallTranscriptGroup(val callId: String, val lines: List<CallTranscript>)

internal fun callTranscriptCallId(eventId: String): String? = eventId.substringBefore(':').takeIf { it.isNotBlank() && it != eventId }

internal fun groupCallTranscriptEvents(lines: List<CallTranscript>): List<CallTranscriptGroup> = lines.mapNotNull { line ->
    callTranscriptCallId(line.eventId)?.let { it to line }
}.groupBy({ it.first }, { it.second }).map { (callId, events) ->
    CallTranscriptGroup(callId, orderCallTranscriptLines(events))
}

internal fun callTranscriptCardTitle(durationSeconds: Long?, provider: String?): String = buildList {
    add("Call")
    durationSeconds?.takeIf { it > 0 }?.let { add("${((it + 30) / 60).coerceAtLeast(1)} min") }
    provider?.takeIf(String::isNotBlank)?.let {
        add(when (it.lowercase()) { "openai" -> "OpenAI"; "grok" -> "Grok"; "gemini" -> "Gemini"; else -> it.replaceFirstChar { char -> char.titlecase() } })
    }
}.joinToString(" · ")

@Composable
internal fun CallTranscriptCard(
    callId: String,
    lines: List<CallTranscript>,
    durationSeconds: Long? = null,
    provider: String? = null,
    assistantName: String = dev.hermes.android.data.currentAssistantName,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
) {
    var expanded by rememberSaveable(callId) { mutableStateOf(false) }
    val ordered = remember(callId, lines) { orderCallTranscriptLines(lines) }
    if (ordered.isEmpty()) return
    val searchExpanded = searchQuery.isNotBlank() && ordered.dropLast(4).any { it.text.contains(searchQuery, ignoreCase = true) }
    val visible = visibleCallTranscriptLines(ordered, expanded || searchExpanded)
    val transcriptDescription = uiText("Call transcript")
    Surface(
        modifier.semantics { contentDescription = transcriptDescription }
            .testTag("call-transcript-card:$callId"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(callTranscriptCardTitle(durationSeconds, provider), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                if (ordered.size > 4 && !searchExpanded) TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("call-transcript-toggle:$callId"),
                ) { Text(uiText(if (expanded) "Show less" else "Show all")) }
            }
            visible.forEach { line ->
                val owner = line.role == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (owner) Arrangement.End else Arrangement.Start) {
                    Surface(
                        Modifier.widthIn(max = 560.dp).fillMaxWidth(.92f).testTag("call-transcript-bubble:${line.eventId}"),
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (owner) 20.dp else 4.dp, bottomEnd = if (owner) 4.dp else 20.dp),
                        color = if (owner) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (owner) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(uiText(if (owner) "You" else assistantName), style = MaterialTheme.typography.labelSmall)
                            Text(line.text, style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Start,
                                overflow = TextOverflow.Clip)
                        }
                    }
                }
            }
        }
    }
}
