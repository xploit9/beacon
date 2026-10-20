package dev.hermes.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.hermes.android.data.CYPHER_THREAD_SOURCE
import dev.hermes.android.core.string
import dev.hermes.android.core.sessionRowId
import dev.hermes.android.core.sessionRowKey
import org.json.JSONObject
import java.util.Locale

// Threads and drawer filters follow Hermes Relay's SessionDrawer (All/Threads/Pinned/Archive lanes,
// source visibility, ordering) and ThreadGlyph (MIT; see licenses/Hermes-Relay-MIT.txt). Relay marks
// its threads with its own `phone` platform; Cypher threads are native chats created with
// CYPHER_THREAD_SOURCE, and the owner's Relay threads show in the same lane.

internal enum class HistoryView { All, Threads, Pinned }

/** Drawer ordering; Hermes orders `recent`/`created` natively, titles are sorted on the phone. */
internal enum class HistoryOrder(val key: String, val label: String) {
    Recent("recent", "Recent activity"), Created("created", "Newest first"), Title("title", "A–Z");
    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: Recent }
}

internal fun isThreadSource(source: String?): Boolean = source?.trim()?.lowercase(Locale.ROOT) in setOf(CYPHER_THREAD_SOURCE, "phone")
internal fun historyRowKey(row: JSONObject): String = sessionRowKey(row)

/** Readable names for Hermes session sources; an unknown platform keeps its own name. */
internal fun historySourceLabel(source: String): String = when (source.trim().lowercase(Locale.ROOT)) {
    "android" -> "Cypher"
    CYPHER_THREAD_SOURCE -> "Cypher threads"
    "phone" -> "Relay threads"
    "webui" -> "Web"
    "desktop" -> "Desktop"
    "tui" -> "Terminal"
    "cli" -> "Command line"
    "api_server" -> "API"
    "bot_room" -> "Bot rooms"
    "subagent" -> "Subagents"
    "oneshot" -> "One-off runs"
    else -> source.trim().replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/** Sources offered in the filter: those listed now plus any the user has hidden (they are no longer listed). */
internal fun historySources(rows: List<JSONObject>, hidden: Set<String>): List<String> =
    (rows.map { it.string("source").trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank) + hidden)
        .filterNot { it == "cron" || isThreadSource(it) }.distinct().sortedBy { historySourceLabel(it).lowercase(Locale.ROOT) }

/** The rows a view shows, in the chosen order (pinned first, as Relay does). */
internal fun historyRows(rows: List<JSONObject>, view: HistoryView, order: HistoryOrder, currentProfile: String = "", assistantProfile: String = "", assistantSessionId: String = ""): List<JSONObject> {
    val visible = rows.filterNot { row ->
        assistantSessionId.isNotBlank() && row.string("profile", currentProfile) == assistantProfile && sessionRowId(row) == assistantSessionId
    }
    val shown = when (view) {
        HistoryView.All -> visible
        HistoryView.Threads -> visible.filter { isThreadSource(it.string("source")) }
        HistoryView.Pinned -> visible.filter { it.optBoolean("pinned") }
    }
    return if (order == HistoryOrder.Title) shown.sortedWith(compareByDescending<JSONObject> { it.optBoolean("pinned") }.thenBy { it.string("title").lowercase(Locale.ROOT) }) else shown
}

/** Hermes rejects a duplicate title (the first-turn title is then dropped), so a repeated thread name gets a number. */
internal fun uniqueThreadTitle(name: String, existing: Collection<String>): String {
    val base = name.trim().replace(Regex("\\s+"), " ").take(100)
    val taken = existing.map { it.trim().lowercase(Locale.ROOT) }.toSet()
    if (base.lowercase(Locale.ROOT) !in taken) return base
    return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it.lowercase(Locale.ROOT) !in taken }
}

/** Relay's thread-spool glyph: a message trunk that curls into a second message. */
@Composable internal fun ThreadSpoolGlyph(modifier: Modifier = Modifier, tint: Color = Color.Black) {
    Canvas(modifier = modifier) {
        val s = size.minDimension
        val strokeW = s * 0.10f
        val trunkX = s * 0.30f
        drawLine(tint, Offset(trunkX, s * 0.33f), Offset(s * 0.74f, s * 0.33f), strokeWidth = strokeW, cap = StrokeCap.Round)
        val spool = Path().apply {
            moveTo(trunkX, s * 0.18f)
            lineTo(trunkX, s * 0.66f)
            cubicTo(trunkX, s * 0.77f, trunkX + s * 0.06f, s * 0.83f, trunkX + s * 0.17f, s * 0.83f)
        }
        drawPath(spool, tint, style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(tint, Offset(trunkX + s * 0.17f, s * 0.83f), Offset(s * 0.74f, s * 0.83f), strokeWidth = strokeW, cap = StrokeCap.Round)
    }
}
