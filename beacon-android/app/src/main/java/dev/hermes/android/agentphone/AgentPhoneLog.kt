package dev.hermes.android.agentphone

import dev.hermes.android.phone.PhoneDayName
import dev.hermes.android.phone.PhoneLine
import dev.hermes.android.phone.phoneDayName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * "What {name} did on its phone" (EMU-DESIGN rev 4 §5.5; approvals-v1 §3, §6, §6b): the agent phone's variant of Phone access's
 * activity list (phone/PhoneActivityList.kt). Each row is one line the plugin wrote: when, which app, what the agent did, the
 * control's label, and whether the owner approved it. Labels only, text only; nothing here is kept on this phone.
 */

/**
 * One row of `GET /mobile/agent-phone/activity` (approvals-v1 §6, rev 3 with `seq`): L4's AgentPhoneActivity, by its own name in this
 * package, where L5's setup view already takes "AgentPhoneActivity" for its Activity.
 */
internal typealias AgentPhoneLogRow = dev.hermes.android.data.AgentPhoneActivity

/** The number in a done notice's `activity_ref` / `activity_key` ("agent_phone_activity:<seq>"), or null. */
internal fun activitySeq(ref: String?): Long? =
    ref?.takeIf { it.startsWith(ACTIVITY_REF_PREFIX) }?.removePrefix(ACTIVITY_REF_PREFIX)?.toLongOrNull()?.takeIf { it > 0 }

internal const val ACTIVITY_REF_PREFIX = "agent_phone_activity:"

/** What the agent did, in a word, by the plugin's action names (L3's tool; `answer` is Android's own question answered No). */
internal val AgentPhoneLogVerbs: Map<String, String> = linkedMapOf(
    "tap" to "Tapped", "long_press" to "Pressed and held", "swipe" to "Swiped", "type" to "Typed", "key" to "Pressed a key",
    "fill" to "Filled", "save" to "Saved a login", "answer" to "Answered",
    // D2b: a link's row never names its address; the navigation keys use the words of the phone view's own bar.
    "open_link" to "Opened a link", "back" to "Back", "home" to "Home screen", "recents" to "Recent apps",
)

/** The plugin's name for the phone's home screen (its launcher, R3-F3): the row word "Home screen", shown in the owner's language. */
internal const val HOME_SCREEN = "Home screen"

/** An app as a row or a card names it: the home screen in the owner's words, any other app by the name its phone shows. */
internal fun appWords(app: String, translate: (String) -> String): String = if (app == HOME_SCREEN) translate(HOME_SCREEN) else app

/** How the row ended, by the plugin's outcomes (approvals-v1 §6b and L3's tool); "" for an outcome this app does not know. */
internal fun logOutcomeWords(outcome: String): String = when (outcome) {
    "done" -> "Done"
    "declined" -> "Not allowed"
    "blocked" -> "Blocked"
    "phone_in_use" -> "You're using the phone"
    "said_no" -> "{name} said no"
    "asked_owner" -> "Asked you"
    "not_saved" -> "Not saved"
    "saved" -> "Saved"
    "filled" -> "Filled"
    else -> ""
}

/** The Result filter's three groups. */
internal enum class LogResult(val words: String) { Approved("Approved by you"), Done("Done"), NotDone("Didn't run") }

internal fun logResultOf(row: AgentPhoneLogRow): Set<LogResult> = buildSet {
    if (row.approved) add(LogResult.Approved)
    if (row.outcome in setOf("done", "saved", "filled")) add(LogResult.Done) else add(LogResult.NotDone)
}

/**
 * What a row reads. [title] is a plain sentence of what was done ("Tapped Place order at Kanto Foods"), the plugin's own sentence for a
 * save, or the verb when the label is empty. [verb] is shown beside the app unless the title already says it; [appInTitle] when the
 * sentence names the app itself.
 */
internal data class LogLine(val title: PhoneLine, val verb: String, val outcome: String, val appInTitle: Boolean = false)

/**
 * A done row in plain words, claiming only what the record proves (AGENTUX-121 idea 6): the input and where, never what it led to
 * ("Tapped Place order at Kanto Foods", not "Ordered"). {0} is the control's label, {1} the app.
 */
private val DoneSentences = mapOf("tap" to "Tapped {0} at {1}", "long_press" to "Pressed and held {0} at {1}", "type" to "Typed in {0} at {1}")

/** A row that didn't run names only the control and where; its outcome says what happened. */
private const val NOT_RUN = "{0} at {1}"

/** An app the agent opened from its list ("Opened Clock"): the plugin's row carries the app's name, never its package when it knows it. */
private const val OPENED_APP = "Opened {0}"

/** Android's own debugging question, answered No for the owner (P3-4: said without "debugging"). */
private const val BLOCKED_TAKEOVER = "Blocked an app that tried to take control"

private fun fillTitle(kind: String): String = when (kind) {
    "payment" -> "Filled a saved card"
    "address" -> "Filled a saved address"
    else -> "Filled a saved login"
}

internal fun logLine(row: AgentPhoneLogRow): LogLine {
    val verb = AgentPhoneLogVerbs[row.action].orEmpty()
    val outcome = logOutcomeWords(row.outcome)
    return when {
        // Revision 5 (17 Nov): a save row's label is the saved login's identifier and its app the site, so the verb says what was
        // done. An older plugin's label is the whole sentence ("Saved: Kanto Foods · k@example.com"), shown as it says.
        row.action == "save" -> {
            val said = if (row.label.isBlank()) PhoneLine("Saved a login") else serverLine(row.label)
            if (said.template == "{0}") LogLine(PhoneLine("{0}", listOf(plainLabel(row.label))), verb, if (row.outcome == "saved") "" else outcome)
            else LogLine(said, "", if (row.outcome == "saved") "" else outcome)
        }
        row.action == "answer" -> LogLine(PhoneLine(BLOCKED_TAKEOVER), "", if (row.outcome == "blocked" || row.outcome == "said_no") "" else outcome)
        row.action == "fill" -> LogLine(PhoneLine(fillTitle(row.label)), "", if (row.outcome == "filled" || row.outcome == "done") "" else outcome)
        row.action == "open_app" && row.app.isNotBlank() ->
            LogLine(PhoneLine(OPENED_APP, listOf(plainLabel(row.app))), "", if (row.outcome == "done") "" else outcome, appInTitle = true)
        row.label.isNotBlank() && row.app.isNotBlank() && (row.outcome != "done" || row.action in DoneSentences) -> {
            val names = listOf(plainLabel(row.label), plainLabel(row.app))
            if (row.outcome == "done") LogLine(PhoneLine(DoneSentences.getValue(row.action), names), "", "", appInTitle = true)
            else LogLine(PhoneLine(NOT_RUN, names), "", outcome, appInTitle = true)
        }
        // The control's own words as one plain line (the screen's text is never trusted to be tidy).
        row.label.isNotBlank() -> LogLine(PhoneLine("{0}", listOf(plainLabel(row.label))), verb, if (row.outcome == "done") "" else outcome)
        else -> LogLine(PhoneLine(verb.ifBlank { "Used the phone" }), "", if (row.outcome == "done") "" else outcome)
    }
}

/** The search text and the result choice; null is all. */
internal data class LogFilter(val query: String = "", val result: LogResult? = null) {
    val active: Boolean get() = query.isNotBlank() || result != null
}

/** The rows that pass [filter], newest first; [text] is a row as the owner reads it, in the shown language. */
internal fun filterLog(rows: List<AgentPhoneLogRow>, filter: LogFilter, text: (AgentPhoneLogRow) -> String): List<AgentPhoneLogRow> {
    val words = filter.query.lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
    return rows.sortedByDescending { it.seq }.filter { row ->
        (filter.result == null || filter.result in logResultOf(row)) &&
            (words.isEmpty() || text(row).lowercase().let { line -> words.all { it in line } })
    }
}

/** One day of the list, its rows newest first. */
internal data class LogDay(val day: LocalDate, val rows: List<AgentPhoneLogRow>)

internal fun groupLogByDay(rows: List<AgentPhoneLogRow>, zone: ZoneId): List<LogDay> {
    val days = ArrayList<LogDay>()
    for (row in rows) {
        val day = Instant.ofEpochMilli(row.atMillis).atZone(zone).toLocalDate()
        val last = days.lastOrNull()
        if (last != null && last.day == day) days[days.lastIndex] = last.copy(rows = last.rows + row) else days += LogDay(day, listOf(row))
    }
    return days
}

/** Today, Yesterday or a date, as Phone access's list names its days. */
internal fun logDayName(day: LocalDate, today: LocalDate): PhoneDayName = phoneDayName(day, today)

/** The list's fixed words that reach uiText through a variable. */
internal val AgentPhoneLogWords: List<String> by lazy {
    AgentPhoneLogVerbs.values.toList() + listOf("done", "declined", "blocked", "said_no", "asked_owner", "not_saved", "saved", "filled").map(::logOutcomeWords) +
        LogResult.entries.map { it.words } + listOf("Filled a saved card", "Filled a saved address", "Filled a saved login", "Used the phone") +
        DoneSentences.values + listOf(NOT_RUN, BLOCKED_TAKEOVER, OPENED_APP)
}
