package dev.hermes.android.ui

// Tool-state and activity rules adapted from Hermes Relay (MIT; see licenses/Hermes-Relay-MIT.txt):
// ToolProgressCard.isDetachedDelegationDispatch and compactToolDetail.
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
enum class ToolOutcome { Preparing, Running, Completed, Dispatched, Failed, NoResult }

private val failedStates = setOf("error", "failed", "failure", "denied", "cancelled", "canceled")
private val preparingStates = setOf("generating", "preparing")
private val doneStates = setOf("done", "complete", "completed", "success", "succeeded", "ok")
/** A finished dispatch call is not evidence that its detached child finished. Parsed as JSON like Relay's check. */
internal fun isDetachedDispatch(name: String, result: String?): Boolean {
    if (name != "delegate_task" || result.isNullOrBlank()) return false
    val payload = runCatching { org.json.JSONObject(result) }.getOrNull() ?: return false
    return payload.optString("status").trim().lowercase() == "dispatched" || payload.optString("mode").trim().lowercase() == "background"
}

/**
 * [result] is the terminal `result`/`output` only, never a partial preview. [explicitCompletion] is true when the
 * native payload carried a terminal marker (a `result` or `is_error` field, even if the result is empty).
 *
 * Terminal evidence and errors win over a stale `running`/`generating` status, because the same-id completion is
 * merged into the start event without clearing it. Without terminal evidence, `running`/`generating` only shows
 * while the turn is [streaming]; a stored call is NoResult, never running and never success.
 */
internal fun toolOutcome(name: String, status: String, isError: Boolean, result: String?, explicitCompletion: Boolean, streaming: Boolean): ToolOutcome {
    val state = status.trim().lowercase()
    return when {
        isError || state in failedStates -> ToolOutcome.Failed
        state in doneStates || explicitCompletion || !result.isNullOrBlank() -> if (isDetachedDispatch(name, result)) ToolOutcome.Dispatched else ToolOutcome.Completed
        !streaming -> ToolOutcome.NoResult
        state in preparingStates -> ToolOutcome.Preparing
        else -> ToolOutcome.Running
    }
}

internal fun toolOutcomeLabel(outcome: ToolOutcome): String = when (outcome) {
    ToolOutcome.Preparing -> "Preparing"
    ToolOutcome.Running -> "Running"
    ToolOutcome.Completed -> "Completed"
    ToolOutcome.Dispatched -> "Dispatched"
    ToolOutcome.Failed -> "Failed"
    ToolOutcome.NoResult -> "No result recorded"
}

/** Recorded epoch (seconds or milliseconds) as milliseconds; 0 when absent or not a plausible time. */
internal fun epochMillis(value: Long): Long = when {
    value <= 0 -> 0
    value < 100_000_000_000L -> value * 1000
    else -> value
}

/** "h:mm a" like Relay's message and tool timestamps; null when no time was recorded. */
internal fun messageTimeLabel(epoch: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault(), locale: java.util.Locale = java.util.Locale.getDefault()): String? {
    val millis = epochMillis(epoch).takeIf { it > 0 } ?: return null
    return java.text.SimpleDateFormat("h:mm a", locale).also { it.timeZone = zone }.format(java.util.Date(millis))
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

/** One-line summary for collapsed use; the expanded card shows [boundedDetail] instead. Honors [maxChars]. */
internal fun compactToolDetail(value: String, maxChars: Int = 700): String {
    val compact = value.replace(whitespaceRun, " ").trim()
    return if (compact.length <= maxChars) compact else compact.takeWholeChars(maxChars).trimEnd() + "..."
}

internal const val ToolDetailLimit = 200_000

/** Full recorded text with newlines intact; only a pathological size is cut, and the omitted count is stated. */
internal fun boundedDetail(value: String, limit: Int = ToolDetailLimit): String {
    if (value.length <= limit) return value
    val shown = value.takeWholeChars(limit)
    return shown + "\n… ${value.length - shown.length} more characters not shown"
}

internal data class ToolSnapshot(val key: String, val name: String, val outcome: ToolOutcome, val durationSeconds: Double? = null)

/** Real activity of the current chat only: its tools, queue and pending prompts. */
internal data class ChatActivity(val running: List<ToolSnapshot>, val recent: List<ToolSnapshot>, val queued: Int, val waiting: List<String>) {
    /** Finished work stays in its transcript card; only active work owns composer space. */
    val active: Boolean get() = running.isNotEmpty() || queued > 0 || waiting.isNotEmpty()
}

internal fun chatActivity(tools: List<ToolSnapshot>, queued: Int, approval: Boolean, question: Boolean): ChatActivity {
    val (running, recent) = tools.partition { it.outcome == ToolOutcome.Running || it.outcome == ToolOutcome.Preparing }
    return ChatActivity(running, recent, queued.coerceAtLeast(0), listOfNotNull("Approval needed".takeIf { approval }, "Question waiting".takeIf { question }))
}

internal fun chatActivityStatus(activity: ChatActivity): String = listOfNotNull(
    "${activity.running.size} running".takeIf { activity.running.isNotEmpty() },
    "${activity.queued} queued".takeIf { activity.queued > 0 },
    "Waiting for you".takeIf { activity.waiting.isNotEmpty() },
).joinToString(" · ")

/** The native connection exposes no process registry yet; say so instead of showing an empty list as truth. */
internal const val ProcessControlUnavailable = "Background process list and stop controls aren’t available from this connection yet. Only this chat’s own tools, queue and prompts are shown."
