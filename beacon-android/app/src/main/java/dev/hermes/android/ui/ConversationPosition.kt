package dev.hermes.android.ui

// Tool-state rules adapted from Hermes Relay (MIT; see licenses/Hermes-Relay-MIT.txt):
// ToolProgressCard.isDetachedDelegationDispatch.
// Pure functions only, so they run as plain JVM unit tests.

/** Seed the first measurement, so history never renders at the top before restoration. */
internal fun initialConversationPosition(ids: List<String>, anchorId: String, index: Int, offset: Int): Pair<Int, Int> {
    if (ids.isEmpty()) return 0 to 0
    if (anchorId.isBlank() && index == 0 && offset == 0) return Int.MAX_VALUE to 0
    return (anchoredIndex(ids, anchorId, index) ?: 0) to offset.coerceAtLeast(0)
}

/** Durable id wins over the saved index, which only covers a removed anchor. Null when there is nothing to anchor to. */
internal fun anchoredIndex(ids: List<String>, anchorId: String, index: Int): Int? =
    if (ids.isEmpty()) null else ids.indexOf(anchorId).takeIf { it >= 0 } ?: index.coerceIn(0, ids.lastIndex)

/** A reader of older messages is never pulled to the tail; only a follower is. */
internal fun followsTail(userAtTail: Boolean, autoFollowSetting: Boolean, hasSavedAnchor: Boolean): Boolean =
    userAtTail && autoFollowSetting && !hasSavedAnchor

/** Expanding a tool to inspect it yields live-tail follow; collapsing never re-arms it. */
internal fun followAfterToolToggle(following: Boolean, expanded: Boolean): Boolean = following && !expanded

/** Execution state as recorded. Success is never inferred from the absence of an error. */
enum class ToolOutcome { Preparing, Running, Completed, Dispatched, Failed, NoResult, Stopped }

private val failedStates = setOf("error", "failed", "failure", "denied", "cancelled", "canceled")
private val preparingStates = setOf("generating", "preparing")
private val doneStates = setOf("done", "complete", "completed", "success", "succeeded", "ok")
/** A finished dispatch call is not evidence that its detached child finished. Parsed as JSON like Relay's check. */
internal fun isDetachedDispatch(name: String, result: String?): Boolean {
    if (name != "delegate_task" || result.isNullOrBlank()) return false
    val payload = runCatching { org.json.JSONObject(result) }.getOrNull() ?: return false
    return payload.optString("status").trim().lowercase() == "dispatched" || payload.optString("mode").trim().lowercase() == "background"
}

private val interruptMarkerLine = Regex("""^\[(?:command|execution) interrupted\b[^\n]*\]\s*$""", RegexOption.IGNORE_CASE)

/**
 * A tool result an interrupt cut, by Hermes' own test (agent/replay_cleanup.py `is_interrupted_tool_result`): the executor's bracketed marker ("[Command interrupted]") is the last line of the
 * output, and a JSON result also carries a non-zero `exit_code` (130 for a terminal, -1 for code execution). The saved result has no flag of its own: Hermes does not pass its `hermes_interrupted`
 * through (tools/terminal_tool_result.py), so this shape, which Hermes itself trusts, is the evidence. A marker quoted inside successful output is data: the exit code is then 0.
 */
internal fun isInterruptedToolResult(result: String?): Boolean {
    if (result == null) return false
    var output = result
    if (result.trimStart().startsWith("{")) {
        val envelope = runCatching { org.json.JSONObject(result) }.getOrNull() ?: return false
        val exit = envelope.opt("exit_code")
        if (exit == null || exit == org.json.JSONObject.NULL || exit is Number && exit.toDouble() == 0.0) return false
        output = envelope.opt("output") as? String ?: return false
    }
    return interruptMarkerLine.containsMatchIn(output!!.trimEnd().substringAfterLast('\n'))
}

/**
 * [result] is the terminal `result`/`output` only, never a partial preview. [explicitCompletion] is true when the
 * native payload carried a terminal marker (a `result` or `is_error` field, even if the result is empty).
 *
 * Terminal evidence and errors win over a stale `running`/`generating` status, because the same-id completion is
 * merged into the start event without clearing it. Without terminal evidence, `running`/`generating` only shows
 * while the turn is [streaming] (a live call, or a stored call of the turn running now: [runningTurnMessageIds]); a call of a turn that is over is NoResult, never running and never success.
 */
internal fun toolOutcome(name: String, status: String, isError: Boolean, result: String?, explicitCompletion: Boolean, streaming: Boolean): ToolOutcome {
    val state = status.trim().lowercase()
    return when {
        isInterruptedToolResult(result) -> ToolOutcome.Stopped
        isError || state in failedStates -> ToolOutcome.Failed
        state in doneStates || explicitCompletion || !result.isNullOrBlank() -> if (isDetachedDispatch(name, result)) ToolOutcome.Dispatched else ToolOutcome.Completed
        !streaming -> ToolOutcome.NoResult
        state in preparingStates -> ToolOutcome.Preparing
        else -> ToolOutcome.Running
    }
}

/**
 * The saved messages that belong to the turn running now: from the last message that begins a turn to the end (a "[System: ...]" note and a steer the person typed continue a turn, they do not begin
 * another), and only while [turnRunning], the signal Stop shows. A stored tool call of such a message that has no result reads Running, as its live twin does. An earlier turn's call, and every call
 * once the turn has ended, keeps "No result recorded". Like core.runningTurnMessages, with no message that begins a turn the whole chat is the turn.
 */
internal fun runningTurnMessageIds(messages: List<dev.hermes.android.Message>, turnRunning: Boolean): Set<String> =
    if (!turnRunning) emptySet()
    else messages.drop(messages.indexOfLast { it.role == "user" && it.displayKind != "steer" && !dev.hermes.android.core.isSteeringText(it.text) }.coerceAtLeast(0)).map { it.id }.toSet()

internal fun toolOutcomeLabel(outcome: ToolOutcome): String = when (outcome) {
    ToolOutcome.Preparing -> "Preparing"
    ToolOutcome.Running -> "Running"
    ToolOutcome.Completed -> "Completed"
    ToolOutcome.Dispatched -> "Handed off"
    ToolOutcome.Failed -> "Failed"
    ToolOutcome.NoResult -> "No result recorded"
    ToolOutcome.Stopped -> "Stopped"
}

/** Recorded epoch (seconds or milliseconds) as milliseconds; 0 when absent or not a plausible time. */
internal fun epochMillis(value: Long): Long = when {
    value <= 0 -> 0
    value < 100_000_000_000L -> value * 1000
    else -> value
}

/** The phone's clock pattern for its 12/24-hour setting and locale; today's English one where the platform has none (JVM tests). */
internal fun nativeClockPattern(locale: java.util.Locale, is24Hour: Boolean): String =
    android.text.format.DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hm")?.takeIf { it.isNotBlank() } ?: if (is24Hour) "HH:mm" else "h:mm a"

/** The phone's day pattern in its locale's order ("Tue, Oct 7", "Sep 4, 2024"); today's English one where the platform has none. */
internal fun nativeDayPattern(locale: java.util.Locale, withYear: Boolean, withWeekday: Boolean): String =
    android.text.format.DateFormat.getBestDateTimePattern(locale, (if (withWeekday) "EEE" else "") + (if (withYear) "y" else "") + "MMMd")?.takeIf { it.isNotBlank() }
        ?: (if (withWeekday) "EEE, " else "") + "MMM d" + (if (withYear) ", yyyy" else "")

/** The platform's own best pattern for [skeleton] in [locale]; null where it has none (JVM tests). */
internal fun platformBestPattern(locale: java.util.Locale, skeleton: String): String? = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)

/**
 * A date and its time as one pattern in the locale's own order and joiner. The day and the clock are asked for as one skeleton, so the platform
 * picks the separator ("12 نوفمبر 2026، 12:01 م", "Nov 12, 2026, 12:01 PM"); joining the two patterns with a fixed ", " left an English comma in Arabic.
 * [best] is the platform lookup; today's English pattern stands where it has none (JVM tests).
 */
internal fun nativeDateTimePattern(
    locale: java.util.Locale, is24Hour: Boolean, withYear: Boolean, withWeekday: Boolean,
    best: (java.util.Locale, String) -> String? = ::platformBestPattern,
): String =
    best(locale, (if (withWeekday) "EEE" else "") + (if (withYear) "y" else "") + "MMMd" + (if (is24Hour) "Hm" else "hm"))?.takeIf { it.isNotBlank() }
        ?: ((if (withWeekday) "EEE, " else "") + "MMM d" + (if (withYear) ", yyyy" else "") + ", " + (if (is24Hour) "HH:mm" else "h:mm a"))

/** Like Relay's message and tool timestamps, in the phone's clock; null when no time was recorded. */
internal fun messageTimeLabel(epoch: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault(), locale: java.util.Locale = java.util.Locale.getDefault(), is24Hour: Boolean = false): String? {
    val millis = epochMillis(epoch).takeIf { it > 0 } ?: return null
    return java.text.SimpleDateFormat(nativeClockPattern(locale, is24Hour), locale).also { it.timeZone = zone }.format(java.util.Date(millis))
}

/** The native `duration_s` as recorded; null when absent, negative or not finite. */
internal fun toolDurationLabel(durationSeconds: Double?): String? =
    durationSeconds?.takeIf { it.isFinite() && it >= 0 }?.let { String.format(java.util.Locale.ROOT, "%.1fs", it) }

private val whitespaceRun = Regex("\\s+")

/** First [count] chars, backing off one so a surrogate pair (emoji) is never split. */
private fun String.takeWholeChars(count: Int): String {
    var end = count.coerceIn(0, length)
    if (end in 1 until length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) end--
    return substring(0, end)
}

internal const val ToolDetailLimit = 200_000
internal const val ToolDetailPageSize = 4_000

/** Reveal large details in bounded Compose layouts instead of measuring the full output on the first tap. */
internal fun nextToolDetailLimit(currentLimit: Int): Int =
    (currentLimit + ToolDetailPageSize).coerceAtMost(ToolDetailLimit)

/** Full recorded text with newlines intact; only a pathological size is cut, and the omitted count is stated. */
internal fun boundedDetail(value: String, limit: Int = ToolDetailLimit): String {
    if (value.length <= limit) return value
    val shown = value.takeWholeChars(limit)
    return shown + "\n… ${value.length - shown.length} more characters not shown"
}

/** One-line excerpt for "Quote in reply" (Relay's bounded excerpt): whitespace collapsed, at most [max] characters. */
internal fun quoteExcerpt(text: String, max: Int = 240): String {
    val line = text.replace(whitespaceRun, " ").trim()
    return if (line.length <= max) line else line.take(max - 1).trimEnd() + "…"
}
/** The composer after quoting: a plain Markdown quote above the draft, readable by every Hermes client. */
internal fun quotedDraft(excerpt: String, draft: String): String = "> $excerpt\n\n$draft"

private fun dayNumber(millis: Long, zone: java.util.TimeZone): Int =
    java.util.Calendar.getInstance(zone).apply { timeInMillis = millis }.let { it.get(java.util.Calendar.YEAR) * 1000 + it.get(java.util.Calendar.DAY_OF_YEAR) }

/** Divider labels for the first message of each day (Today, Yesterday or the date); a message without a time starts no day. */
internal fun messageDayStarts(times: List<Pair<String, Long>>, now: Long = System.currentTimeMillis(), zone: java.util.TimeZone = java.util.TimeZone.getDefault(), locale: java.util.Locale = java.util.Locale.getDefault()): Map<String, String> {
    val today = dayNumber(now, zone)
    val yesterday = dayNumber(java.util.Calendar.getInstance(zone).apply { timeInMillis = now; add(java.util.Calendar.DAY_OF_YEAR, -1) }.timeInMillis, zone)
    val year = today / 1000
    var last = -1
    val starts = linkedMapOf<String, String>()
    for ((id, epoch) in times) {
        val millis = epochMillis(epoch).takeIf { it > 0 } ?: continue
        val day = dayNumber(millis, zone)
        if (day == last) continue
        last = day
        starts[id] = when (day) {
            today -> "Today"
            yesterday -> "Yesterday"
            else -> {
                val sameYear = day / 1000 == year
                java.text.SimpleDateFormat(nativeDayPattern(locale, withYear = !sameYear, withWeekday = sameYear), locale).also { it.timeZone = zone }.format(java.util.Date(millis))
            }
        }
    }
    return starts
}

/** The whole conversation as plain text for sharing: who said what, tool and system rows left out. */
internal fun conversationShareText(messages: List<Pair<String, String>>, assistant: String): String =
    messages.filter { (role, text) -> role in setOf("user", "assistant") && text.isNotBlank() }
        .joinToString("\n\n") { (role, text) -> (if (role == "user") "You" else assistant) + ": " + text.trim() }
