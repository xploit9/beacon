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
import dev.hermes.android.core.Scope
import dev.hermes.android.core.string
import dev.hermes.android.core.sessionRowId
import dev.hermes.android.core.sessionRowKey
import dev.hermes.android.data.BEACON_THREAD_SOURCE
import dev.hermes.android.data.THREAD_SOURCES
import dev.hermes.android.data.currentAssistantName
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// Threads and drawer filters follow Hermes Relay's SessionDrawer (All/Threads/Pinned/Archive lanes,
// source visibility, ordering) and ThreadGlyph (MIT; see licenses/Hermes-Relay-MIT.txt). Relay marks
// its threads with its own `phone` platform; Beacon threads are native chats created with
// BEACON_THREAD_SOURCE, and the owner's Relay threads show in the same lane.

internal enum class HistoryView { All, Threads, Pinned }

/** Drawer ordering; Hermes orders `recent`/`created` natively, titles are sorted on the phone. */
internal enum class HistoryOrder(val key: String, val label: String) {
    Recent("recent", "Recent activity"), Created("created", "Newest first"), Title("title", "A–Z");
    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: Recent }
}

internal fun isThreadSource(source: String?): Boolean = source?.trim()?.lowercase(Locale.ROOT) in THREAD_SOURCES
internal fun historyRowKey(row: JSONObject, currentProfile: String = ""): String = sessionRowKey(row, currentProfile)

/** Loaded titles answer each keystroke; only results for that exact query may supplement them. */
internal fun historySearchRows(loaded: List<JSONObject>, query: String, serverMatches: List<JSONObject>? = null, currentProfile: String = ""): List<JSONObject> {
    if (query.isBlank()) return loaded
    val local = loaded.filter { it.string("title").contains(query.trim(), ignoreCase = true) }
    return (local + serverMatches.orEmpty()).distinctBy { historyRowKey(it, currentProfile) }
}

/** Readable names for Hermes session sources; an unknown platform keeps its own name. */
internal fun historySourceLabel(source: String): String = when (source.trim().lowercase(Locale.ROOT)) {
    "android" -> "Beacon"
    BEACON_THREAD_SOURCE -> "$currentAssistantName threads"
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

/** A thread's Hermes title: the optional emoji, a space, then the name. Nothing else is stored. */
internal fun threadTitle(emoji: String, name: String): String = listOf(emoji.trim(), name.trim()).filter(String::isNotEmpty).joinToString(" ")

private fun emojiOf(vararg codePoints: Int) = String(codePoints, 0, codePoints.size)

/** The icons New thread offers; the owner can also type any emoji at the start of the name. */
internal val threadEmojis: List<String> = listOf(
    emojiOf(0x1F4F0), emojiOf(0x1F512), emojiOf(0x1F4C8), emojiOf(0x1F4A1), emojiOf(0x1F9EA), emojiOf(0x2708, 0xFE0F),
    emojiOf(0x1F3E0), emojiOf(0x1F4DA), emojiOf(0x1F3A7), emojiOf(0x1F4BC), emojiOf(0x1F6E0, 0xFE0F), emojiOf(0x1F30D),
)

private fun emojiStart(codePoint: Int) = codePoint in 0x1F000..0x1FAFF || codePoint in 0x2600..0x27BF || codePoint in 0x2B00..0x2BFF || codePoint in 0x2300..0x23FF || codePoint == 0x00A9 || codePoint == 0x00AE

/** The emoji a title starts with (a whole cluster such as a flag or a variation form), or null. */
internal fun leadingEmoji(title: String): String? {
    val text = title.trimStart()
    if (text.isEmpty() || !emojiStart(text.codePointAt(0))) return null
    val boundaries = java.text.BreakIterator.getCharacterInstance().also { it.setText(text) }
    return text.substring(0, boundaries.next().coerceAtLeast(Character.charCount(text.codePointAt(0))))
}

/** The title without the emoji its avatar already shows; a title that is only an emoji stays whole. */
internal fun plainTitle(title: String): String {
    val emoji = leadingEmoji(title) ?: return title
    return title.trimStart().removePrefix(emoji).trimStart().ifBlank { title }
}

/** What a chat's avatar shows: its emoji, else the title's initial on a colour that depends only on the chat id. */
internal data class ChatAvatarSpec(val emoji: String?, val initial: String, val color: Long)

// Darker than Telegram's name colours so the white initial reads (at least 4.5:1).
private val avatarPalette = longArrayOf(0xFFB23C45, 0xFFB0611A, 0xFF6A58C2, 0xFF2F7F32, 0xFF1B858C, 0xFF2D6FB5, 0xFFB0407A)

internal fun chatAvatarSpec(title: String, id: String): ChatAvatarSpec {
    var initial = "#"
    var index = 0
    val text = title.trim()
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (Character.isLetterOrDigit(codePoint)) { initial = String(Character.toChars(codePoint)).uppercase(Locale.ROOT); break }
        index += Character.charCount(codePoint)
    }
    val seed = id.ifBlank { title }
    return ChatAvatarSpec(leadingEmoji(title), initial, avatarPalette[Math.floorMod(seed.hashCode(), avatarPalette.size)])
}

// The unread mark and its "Unread reply" wording follow Hermes Relay's SessionDrawer and ChatUnreadStore (MIT; see licenses/Hermes-Relay-MIT.txt):
// local read receipts only, nothing sent to Hermes. Relay records a receipt per finished turn; Beacon keeps a seen watermark per chat, so a
// scheduled post or a reply that finished while the owner was elsewhere counts as well.

/** What the chat list keeps about a chat on this phone: its latest line, who wrote it, and when the owner last looked (epoch ms). */
internal data class ChatGlance(val seen: Long = 0L, val preview: String = "", val role: String = "", val at: Long = 0L)

/** Glances of the signed-in [account] (its scope key without profile or chat), keyed like History rows ("profile:id"). [base] is, per profile, the newest activity its first full list already showed. */
internal data class ChatGlances(val account: String = "", val base: Map<String, Long> = emptyMap(), val rows: Map<String, ChatGlance> = emptyMap()) {
    /** The chat was looked at up to [seen]; a blank [preview] keeps the line that was known. Returns this when nothing changed. */
    fun viewed(profile: String, id: String, preview: String, role: String, at: Long, seen: Long): ChatGlances {
        val key = "$profile:$id"
        val old = rows[key]
        val line = preview.takeIf { it.isNotBlank() }
        val next = ChatGlance(maxOf(old?.seen ?: 0L, seen), line ?: old?.preview.orEmpty(), if (line != null) role else old?.role.orEmpty(), if (line != null) at else old?.at ?: 0L)
        return if (next == old) this else copy(rows = rows + (key to next))
    }
    fun withBase(profile: String, millis: Long): ChatGlances = if (profile in base) this else copy(base = base + (profile to millis))
    /** Stored values fill in what this run has not set. */
    fun merged(stored: ChatGlances) = copy(base = stored.base + base, rows = stored.rows + rows)
}

internal const val GLANCE_ROWS_MAX = 300
internal const val GLANCE_PREVIEW_MAX = 140

/** One profile's part of the glances, as its encrypted row stores it. */
internal fun ChatGlances.encodeFor(profile: String): String {
    val prefix = "$profile:"
    val stored = JSONObject()
    rows.entries.filter { it.key.startsWith(prefix) }.sortedByDescending { maxOf(it.value.seen, it.value.at) }.take(GLANCE_ROWS_MAX).forEach { (key, glance) ->
        stored.put(key.removePrefix(prefix), JSONObject().put("s", glance.seen).put("p", glance.preview).put("r", glance.role).put("a", glance.at))
    }
    return JSONObject().put("has_base", profile in base).put("base", base[profile] ?: 0L).put("rows", stored).toString()
}

/** Null for anything unreadable: a bad row is the same as no row. */
internal fun decodeGlances(profile: String, text: String): ChatGlances? = try {
    val root = JSONObject(text)
    val stored = root.optJSONObject("rows") ?: JSONObject()
    val parsed = LinkedHashMap<String, ChatGlance>()
    stored.keys().forEach { id ->
        val row = stored.optJSONObject(id)
        if (row != null && id.isNotBlank() && parsed.size < GLANCE_ROWS_MAX)
            parsed["$profile:$id"] = ChatGlance(row.optLong("s").coerceAtLeast(0L), row.optString("p").take(GLANCE_PREVIEW_MAX), row.optString("r").takeIf { it == "user" || it == "assistant" }.orEmpty(), row.optLong("a").coerceAtLeast(0L))
    }
    ChatGlances(base = if (root.optBoolean("has_base")) mapOf(profile to root.optLong("base").coerceAtLeast(0L)) else emptyMap(), rows = parsed)
} catch (_: Exception) { null }

/** The account's own row names every profile whose previews are on this phone, so a sign-out never depends on what is in memory. */
internal const val GLANCE_PROFILES_KIND = "chat-glance-profiles"

internal fun glanceProfilesJson(profiles: Collection<String>): String = JSONArray(profiles.filter(String::isNotBlank).distinct()).toString()

/** No row or an unreadable one names no profile. */
internal fun decodeGlanceProfiles(text: String?): List<String> = try {
    JSONArray(text.orEmpty()).let { names -> (0 until names.length()).map { names.optString(it) }.filter(String::isNotBlank) }
} catch (_: Exception) { emptyList() }

/** Every profile of [scope]'s account whose previews a sign-out deletes: those opened in this run, those the profile list or the account row names, and the active one. */
internal fun glanceProfilesToForget(scope: Scope, opened: Collection<Scope>, listed: Collection<String>, recorded: Collection<String>): List<Scope> {
    val account = scope.copy(profile = "", session = "").key
    return (opened.filter { it.copy(profile = "", session = "").key == account } + (listed + recorded).filter(String::isNotBlank).map { scope.copy(profile = it, session = "") } + scope.copy(session = ""))
        .distinctBy { it.key }
}

private const val PLAUSIBLE_AFTER_MS = 946_684_800_000L // 2000-01-01; earlier values are not real times
private const val GLANCE_SKEW_MS = 2_000L

/** A History row's latest activity in epoch ms (Hermes `last_active`, else `started_at`); 0 when it has none. */
internal fun rowActivityMillis(row: JSONObject): Long {
    val seconds = maxOf(row.optDouble("last_active", 0.0), row.optDouble("started_at", 0.0))
    if (!seconds.isFinite()) return 0L
    return epochMillis(seconds.toLong()).takeIf { it >= PLAUSIBLE_AFTER_MS } ?: 0L
}

/**
 * New since the owner last looked: activity after what this phone saw (a chat it never opened counts against [baselineMs], so a
 * first install is not all unread), unless the newest message it knows of is the owner's own. The open chat is never unread.
 */
internal fun chatUnread(activityMs: Long, glance: ChatGlance?, baselineMs: Long, open: Boolean): Boolean {
    if (open || activityMs <= 0L) return false
    val seen = glance?.seen?.takeIf { it > 0L } ?: baselineMs
    if (activityMs <= seen) return false
    return glance == null || glance.role != "user" || activityMs > glance.at + GLANCE_SKEW_MS
}

private val markdownLink = Regex("\\[([^\\]]*)]\\([^)]*\\)")
private val markdownBlockMarker = Regex("(?m)^\\s{0,3}(?:#{1,6}|>|[-*+])\\s+")
private val markdownInlineMarker = Regex("[*`~]")
private val whitespaceRuns = Regex("\\s+")

// Hermes' MEDIA tag in any case, quoted or not; an unquoted one must look like a path, so "social media: news" stays words.
private val mediaReference = Regex("MEDIA:\\s*(?:`([^`\\n]+)`|\"([^\"\\n]+)\"|'([^'\\n]+)'|(\\S*[/\\\\]\\S*))", RegexOption.IGNORE_CASE)

/** What the chat list keeps for a chat's newest message: the line its bubble shows and who it is from. A scheduled post is
 *  the thread's message (not "You"), and an interrupted turn reads "Stopped" rather than Hermes' error text. */
internal fun chatListLine(latest: dev.hermes.android.Message?): Pair<String, String> {
    if (latest == null || latest.role !in setOf("user", "assistant")) return "" to latest?.role.orEmpty()
    val post = channelPost(latest.displayKind, latest.role, latest.text)
    val line = stoppedMessageMarker(latest.role, latest.text, latest.interrupted) ?: chatPreviewText(post?.body ?: latest.text)
    return line to if (post != null) "assistant" else latest.role
}

/** One line of a message for a list: Markdown marks and line breaks gone, bounded. */
internal fun chatPreviewText(raw: String): String {
    // Hermes labels a scheduled post "[Cron delivery: <job>]"; the list shows the post itself.
    val unlabelled = raw.trimStart().let { trimmed -> cronDeliveryLabel.find(trimmed)?.let { trimmed.substring(it.range.last + 1) } ?: raw }
    // Like the bubble: an attached or generated file shows as its safe name, never its path on the computer.
    val named = mediaReference.replace(unlabelled) { match -> match.groupValues.drop(1).first { it.isNotEmpty() }.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\') }
    val text = historyAttachmentPresentation(named).let { shown -> shown.text.ifBlank { shown.attachments.firstOrNull()?.name.orEmpty() } }
    val plain = whitespaceRuns.replace(markdownInlineMarker.replace(markdownBlockMarker.replace(markdownLink.replace(text) { it.groupValues[1] }, ""), ""), " ").trim()
    val cut = plain.take(GLANCE_PREVIEW_MAX)
    return if (cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut
}

/**
 * The line under a chat's title: the newest message this phone has seen, else Hermes' own preview (the chat's first message).
 * A preview that only repeats the title gives way to the message count.
 */
internal fun chatPreviewLine(row: JSONObject, glance: ChatGlance?, you: String = "You"): String {
    val remembered = glance?.preview?.takeIf { it.isNotBlank() }
    if (glance != null && remembered != null) return if (glance.role == "user") "$you: $remembered" else remembered
    val title = row.string("title").trim().trimEnd('.', '…')
    val first = chatPreviewText(row.string("preview"))
    val cut = first.trimEnd('.', '…')
    if (cut.isNotBlank() && !title.equals(cut, ignoreCase = true) && !title.startsWith(cut, ignoreCase = true)) return first
    val count = row.optInt("message_count")
    return if (count > 0) "$count ${if (count == 1) "message" else "messages"}" else ""
}

/** Time of the newest activity for a list row: the clock today, the weekday this week, else the date. */
internal fun chatListTime(epochMs: Long, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String {
    if (epochMs <= 0L) return ""
    fun midnight(daysBack: Int) = Calendar.getInstance(zone).apply {
        timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_YEAR, -daysBack)
    }.timeInMillis
    fun year(millis: Long) = Calendar.getInstance(zone).apply { timeInMillis = millis }.get(Calendar.YEAR)
    val pattern = when {
        epochMs >= midnight(0) -> "h:mm a"
        epochMs >= midnight(6) -> "EEE"
        year(epochMs) == year(now) -> "MMM d"
        else -> "MMM d, yyyy"
    }
    return java.text.SimpleDateFormat(pattern, locale).also { it.timeZone = zone }.format(java.util.Date(epochMs))
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
