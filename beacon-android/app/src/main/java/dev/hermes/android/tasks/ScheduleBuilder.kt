package dev.hermes.android.tasks
// Ported from Hermes' dashboard schedule helpers (hermes-agent web/src/lib/schedule.ts: ScheduleBuilderState, buildScheduleString,
// parseScheduleString, describeSchedule), MIT, Nous Research; the licence is assets/licenses/hermes-LICENSE.txt. Every string built
// here is one of the shapes cron/jobs.py::parse_schedule accepts (interval, 5-field cron, ISO timestamp).
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.ui.nativeClockPattern
import dev.hermes.android.ui.nativeDayPattern
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** [value] is what the form stores in `_schedule_mode`; "Fixed window" and "Custom schedule" keep the names drafts saved before the builder carry. */
internal enum class ScheduleMode(val value: String, val label: String) {
    Once("Once", "Once"), Every("Every", "Every few minutes or hours"), Daily("Daily", "Every day"), Weekly("Weekly", "On certain days"),
    Monthly("Monthly", "Once a month"), Fixed("Fixed window", "Within set hours"), Custom("Custom schedule", "Custom timing");
    companion object { fun of(value: String) = entries.firstOrNull { it.value == value } ?: Custom }
}

/** The picker's whole state, as Hermes' `ScheduleBuilderState`; weekdays are cron numbers (0 = Sunday). */
internal data class ScheduleBuilderState(
    val mode: ScheduleMode = ScheduleMode.Daily,
    val everyN: Int = 30, val everyUnit: String = "minutes",
    val hour: Int = 9, val minute: Int = 0,
    val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val dayOfMonth: Int = 1,
    val onceDate: String = "",
    val windowStart: Int = 10, val windowEnd: Int = 22, val windowEvery: Int = 2, val windowIncludeEnd: Boolean = false,
    val custom: String = "",
)

private val unitSuffix = mapOf("minutes" to "m", "hours" to "h", "days" to "d")
private val windowCadences = setOf(1, 2, 3, 4, 6, 12)

internal fun buildScheduleString(s: ScheduleBuilderState): String {
    val time = s.hour in 0..23 && s.minute in 0..59
    return when (s.mode) {
        ScheduleMode.Every -> unitSuffix[s.everyUnit]?.takeIf { s.everyN >= 1 }?.let { "every ${s.everyN}$it" }.orEmpty()
        ScheduleMode.Daily -> if (time) "${s.minute} ${s.hour} * * *" else ""
        // Hermes sends "*" for no chosen day; the form asks for a day first (see scheduleToSave).
        ScheduleMode.Weekly -> if (time) "${s.minute} ${s.hour} * * ${s.weekdays.sorted().joinToString(",").ifEmpty { "*" }}" else ""
        ScheduleMode.Monthly -> if (time && s.dayOfMonth in 1..31) "${s.minute} ${s.hour} ${s.dayOfMonth} * *" else ""
        // Padded by hand: String.format would write Arabic-Indic digits in some locales, and Hermes reads ASCII.
        ScheduleMode.Once -> if (time && Regex("\\d{4}-\\d{2}-\\d{2}").matches(s.onceDate)) "${s.onceDate}T${s.hour.toString().padStart(2, '0')}:${s.minute.toString().padStart(2, '0')}:00" else ""
        ScheduleMode.Fixed -> runCatching { fixedWindowSchedule(s.windowJson()) }.getOrDefault("")
        ScheduleMode.Custom -> s.custom.trim()
    }
}

private fun ScheduleBuilderState.windowJson() = json("_start_hour" to windowStart, "_end_hour" to windowEnd, "_every_hours" to windowEvery, "_include_end" to windowIncludeEnd)

private val isoOnce = Regex("^(\\d{4}-\\d{2}-\\d{2})T(\\d{2}):(\\d{2})(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?$")
private val intervalText = Regex("^every\\s+(\\d+)\\s*([mhd])$", RegexOption.IGNORE_CASE)
private val windowCron = Regex("^0 (\\d{1,2})-(\\d{1,2})/(\\d{1,2}) \\* \\* \\*$")

/** The state a saved schedule string opens with; anything the picker cannot express stays Custom, verbatim. */
internal fun parseScheduleString(schedule: String): ScheduleBuilderState {
    val text = schedule.trim()
    if (text.isEmpty()) return ScheduleBuilderState()
    isoOnce.matchEntire(text)?.let { return ScheduleBuilderState(ScheduleMode.Once, onceDate = it.groupValues[1], hour = it.groupValues[2].toInt(), minute = it.groupValues[3].toInt()) }
    intervalText.matchEntire(text)?.let { match ->
        val n = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..100_000 } ?: return@let
        val (count, unit) = intervalParts(n * mapOf("m" to 1, "h" to 60, "d" to 1440).getValue(match.groupValues[2].lowercase()))
        return ScheduleBuilderState(ScheduleMode.Every, everyN = count, everyUnit = unit)
    }
    windowCron.matchEntire(text)?.let { match ->
        val (from, to, every) = match.groupValues.drop(1).map(String::toInt)
        if (from < to && to <= 23 && every in windowCadences) return ScheduleBuilderState(ScheduleMode.Fixed, windowStart = from, windowEnd = to + 1, windowEvery = every)
    }
    cronParts(text)?.let { c ->
        val minute = c.minutes.singleOrNull() ?: return@let
        val hour = c.hours.singleOrNull() ?: return@let
        if (c.months != "*" || !c.minutePlain || !c.hourPlain) return@let
        val time = ScheduleBuilderState(hour = hour, minute = minute)
        c.dayOfMonth?.let { return time.copy(mode = ScheduleMode.Monthly, dayOfMonth = it) }
        val days = c.weekdays
        return if (days == null || days.size == 7) time.copy(mode = ScheduleMode.Daily) else time.copy(mode = ScheduleMode.Weekly, weekdays = days.toSet())
    }
    return ScheduleBuilderState(ScheduleMode.Custom, custom = text)
}

/** `120` minutes is "2 hours": the largest unit that divides it. */
private fun intervalParts(minutes: Int): Pair<Int, String> = when {
    minutes % 1440 == 0 -> minutes / 1440 to "days"
    minutes % 60 == 0 -> minutes / 60 to "hours"
    else -> minutes to "minutes"
}

// --- Reading the form ---

/** The state held by the form's values, as strings from a map or a submitted body. A mode the form does not know is Custom. */
internal fun scheduleStateFrom(get: (String) -> String): ScheduleBuilderState {
    val d = ScheduleBuilderState()
    fun int(key: String, default: Int) = get(key).toIntOrNull() ?: default
    return ScheduleBuilderState(
        mode = ScheduleMode.of(get("_schedule_mode")), everyN = int("_every_n", 0), everyUnit = get("_every_unit").ifBlank { d.everyUnit },
        hour = int("_hour", d.hour), minute = int("_minute", d.minute), weekdays = (0..6).filter { get("_wd_$it") == "true" }.toSet(),
        dayOfMonth = int("_dom", d.dayOfMonth), onceDate = get("_once_date"),
        windowStart = int("_start_hour", d.windowStart), windowEnd = int("_end_hour", d.windowEnd), windowEvery = int("_every_hours", d.windowEvery),
        windowIncludeEnd = get("_include_end") == "true", custom = get("schedule"),
    )
}

/** The form values for the state; the seed of a new or opened schedule. Ints stay Ints so a choice is read back as a number. A one-time date left blank starts tomorrow, so the default 09:00 is never already past. */
internal fun ScheduleBuilderState.formSeed(today: LocalDate): Map<String, Any> = buildMap {
    put("_schedule_mode", mode.value); put("_every_n", everyN); put("_every_unit", everyUnit); put("_hour", hour); put("_minute", minute)
    (0..6).forEach { put("_wd_$it", it in weekdays) }
    put("_dom", dayOfMonth); put("_once_date", onceDate.ifBlank { today.plusDays(1).toString() })
    put("_start_hour", windowStart); put("_end_hour", windowEnd); put("_every_hours", windowEvery); put("_include_end", windowIncludeEnd)
}

/** The string to save: what the picker builds, or the stored one when the owner changed nothing, so an unrelated edit never rewrites it. */
internal fun scheduleToSave(s: ScheduleBuilderState, stored: String, zone: ZoneId? = null, now: LocalDateTime? = null): String {
    // A window states its own reason when it is wrong; the other modes only know that something is missing.
    val built = if (s.mode == ScheduleMode.Fixed) fixedWindowSchedule(s.windowJson()) else buildScheduleString(s)
    require(s.mode != ScheduleMode.Weekly || s.weekdays.isNotEmpty()) { "Choose at least one day." }
    require(built.isNotBlank()) { if (s.mode == ScheduleMode.Custom) "Enter when this should run." else "Enter how often this should run." }
    if (stored.isNotBlank() && buildScheduleString(parseScheduleString(stored)) == built) return stored
    // Without the Hermes zone the phone's clock could refuse a valid time; Hermes itself rejects a one-time job already past.
    val clock = now ?: zone?.let { LocalDateTime.now(it) }
    require(s.mode != ScheduleMode.Once || clock == null || LocalDateTime.parse(built).isAfter(clock)) { "Choose a time in the future." }
    return built
}

/** Which form fields belong to [mode]; every other schedule field stays hidden. */
private val modeFields = mapOf(
    ScheduleMode.Once to setOf("_once_date", "_hour", "_minute"),
    ScheduleMode.Every to setOf("_every_n", "_every_unit"),
    ScheduleMode.Daily to setOf("_hour", "_minute"),
    ScheduleMode.Weekly to (0..6).map { "_wd_$it" }.toSet() + setOf("_hour", "_minute"),
    ScheduleMode.Monthly to setOf("_dom", "_hour", "_minute"),
    ScheduleMode.Fixed to setOf("_start_hour", "_end_hour", "_every_hours", "_include_end"),
    ScheduleMode.Custom to setOf("schedule"),
)
internal fun scheduleFieldVisible(key: String, mode: String): Boolean = modeFields.values.none { key in it } || key in modeFields.getValue(ScheduleMode.of(mode))

// --- Describing ---

/** How a description is written: the phone's clock and language, and the translation of the app's own words (identity in tests). */
internal class ScheduleFormat(
    val is24Hour: Boolean = true, val locale: Locale = Locale.getDefault(), val text: (String) -> String = { it },
    val dayPattern: String = nativeDayPattern(locale, withYear = false, withWeekday = false),
) {
    private val clockPattern = nativeClockPattern(locale, is24Hour)
    private fun format(pattern: String, hour: Int, minute: Int, date: LocalDate = LocalDate.of(2000, 1, 1)) =
        java.text.SimpleDateFormat(pattern, locale).also { it.timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date.from(date.atTime(hour % 24, minute).toInstant(java.time.ZoneOffset.UTC)))
    fun clock(hour: Int, minute: Int) = format(clockPattern, hour, minute)
    /** An hour on its own, for the hour choice: "08" on a 24-hour phone, "8 AM" on a 12-hour one. */
    fun hour(hour: Int) = format(if (is24Hour) "HH" else "h a", hour, 0)
    /** A day with its weekday and year, for the date choice. */
    fun fullDay(date: LocalDate) = format(nativeDayPattern(locale, withYear = true, withWeekday = true), 0, 0, date)
    fun dayAndClock(at: LocalDateTime) = format("$dayPattern, $clockPattern", at.hour, at.minute, at.toLocalDate())
    fun fill(template: String, vararg values: Pair<String, Any>) = values.fold(text(template)) { acc, (key, value) -> acc.replace("{$key}", value.toString()) }
    /** [day] in cron numbering (0 = Sunday), as the locale writes it. */
    fun weekday(day: Int, style: TextStyle) = DayOfWeek.of(if (day == 0) 7 else day).getDisplayName(style, locale)
}

/** Every fixed phrase [describeSchedule] and the builder form write; `{n}`, `{time}`, `{days}` and `{day}` are filled in. */
internal val ScheduleBuilderStrings = listOf(
    "Once", "Every few minutes or hours", "Every day", "On certain days", "Once a month", "Within set hours", "Custom timing", "Custom schedule",
    "Every minute", "Every {n} minutes", "Every hour", "Every {n} hours", "Every {n} days",
    "Every day at {time}", "Weekdays at {time}", "Weekends at {time}", "Every {days} at {time}", "Every month on day {day} at {time}",
    "Weekdays", "Weekends", "minutes", "hours", "days",
    "Enter when this should run.", "Enter how often this should run.", "Choose at least one day.", "Choose a time in the future.",
    "Choose a start hour before the end hour.", "Choose a gap of 1 to 12 hours.", "Choose an end hour of 23 or earlier, or turn off Include the end hour.",
    "Refresh and reopen this form before saving. The time zone changed or can't be confirmed.", "Choose one interval, such as every 2h.",
    "Ran", "Callback", "Completed schedules",
    "Looks through your connected sources and lets you know when something needs you.", "Tidies up what Beacon remembers about you.", "Runs in the background for Beacon.",
)

/** Cron fields of a 5-field expression, expanded into numbers; null for anything the describer or parser would misstate. */
private class CronParts(val minutes: List<Int>, val hours: List<Int>, val dayOfMonth: Int?, val months: String, val weekdays: List<Int>?, val minutePlain: Boolean, val hourPlain: Boolean)

private val weekdayNames = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
private val plainList = Regex("\\d+(,\\d+)*")

// A star, a star with a step, a, a-b, a-b with a step, and comma lists of those; names stand for weekday numbers.
private fun cronValues(field: String, low: Int, high: Int, names: List<String> = emptyList()): List<Int>? {
    fun number(text: String) = text.toIntOrNull() ?: names.indexOf(text.lowercase()).takeIf { it >= 0 }
    val values = field.split(',').flatMap { item ->
        val (range, gap) = item.substringBefore('/') to (item.substringAfter('/', "1").toIntOrNull() ?: return null)
        if (gap < 1) return null
        val (from, to) = when {
            range == "*" -> low to high
            '-' in range -> (number(range.substringBefore('-')) ?: return null) to (number(range.substringAfter('-')) ?: return null)
            else -> (number(range) ?: return null).let { if ('/' in item) return null else it to it }
        }
        // A step wider than the field (*/90 minutes) is no schedule croniter keeps.
        if (from < low || to > high || from > to || range == "*" && gap > high - low) return null
        (from..to step gap).toList()
    }
    return values.distinct().sorted().takeIf { it.isNotEmpty() }
}

private fun cronParts(expr: String): CronParts? {
    val p = expr.trim().split(Regex("\\s+"))
    if (p.size != 5) return null
    val dom = if (p[2] == "*") null else p[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    val dow = if (p[4] == "*") null else cronValues(p[4], 0, 7, weekdayNames)?.map { it % 7 }?.distinct()?.sorted() ?: return null
    // Cron runs a day matching either field; a schedule restricted by both is not one this describes.
    if (dom != null && dow != null) return null
    return CronParts(cronValues(p[0], 0, 59) ?: return null, cronValues(p[1], 0, 23) ?: return null, dom, p[3], dow, plainList.matches(p[0]), plainList.matches(p[1]))
}

private fun describeMinutes(minutes: Int, f: ScheduleFormat): String? = when {
    minutes < 1 -> null
    minutes % 1440 == 0 -> (minutes / 1440).let { if (it == 1) f.text("Every day") else f.fill("Every {n} days", "n" to it) }
    minutes % 60 == 0 -> (minutes / 60).let { if (it == 1) f.text("Every hour") else f.fill("Every {n} hours", "n" to it) }
    else -> if (minutes == 1) f.text("Every minute") else f.fill("Every {n} minutes", "n" to minutes)
}

private fun uniformStep(values: List<Int>): Int? = values.zipWithNext { a, b -> b - a }.distinct().singleOrNull()

/** A day restriction as words: "Weekdays", "Weekends", or the days by name; null for every day. */
private fun daysText(days: List<Int>?, f: ScheduleFormat): String? = when {
    days == null || days.size == 7 -> null
    days == listOf(1, 2, 3, 4, 5) -> f.text("Weekdays")
    days == listOf(0, 6) -> f.text("Weekends")
    days.size == 1 -> f.weekday(days[0], TextStyle.FULL_STANDALONE)
    else -> days.sortedBy { (it + 6) % 7 }.joinToString(", ") { f.weekday(it, TextStyle.SHORT_STANDALONE) }
}

/** Human words for a 5-field cron expression, or null when they would misstate it (steps that do not divide the hour or day, odd lists, years). */
internal fun describeCron(expr: String, f: ScheduleFormat): String? {
    val c = cronParts(expr) ?: return null
    if (c.months != "*") return null
    val days = daysText(c.weekdays, f)
    // A few fixed times a day: "9:00", "9:00, 17:00".
    if (c.minutePlain && c.hourPlain && (c.minutes.size == 1 || c.hours.size == 1) && c.minutes.size * c.hours.size <= 6) {
        val time = c.hours.flatMap { h -> c.minutes.map { m -> f.clock(h, m) } }.joinToString(", ")
        val on = c.weekdays
        return when {
            c.dayOfMonth != null -> f.fill("Every month on day {day} at {time}", "day" to c.dayOfMonth, "time" to time)
            on == null || on.size == 7 -> f.fill("Every day at {time}", "time" to time)
            on == listOf(1, 2, 3, 4, 5) -> f.fill("Weekdays at {time}", "time" to time)
            on == listOf(0, 6) -> f.fill("Weekends at {time}", "time" to time)
            else -> f.fill("Every {days} at {time}", "days" to days.orEmpty(), "time" to time)
        }
    }
    if (c.dayOfMonth != null) return null
    val every: String
    val window: String?
    if (c.minutes.size > 1) {
        val step = uniformStep(c.minutes) ?: return null
        if (c.minutes.first() != 0 || 60 % step != 0 || c.minutes.last() != 60 - step) return null
        every = describeMinutes(step, f) ?: return null
        // The window ends when its last hour does, so 10-21 reads 10:00-22:00.
        window = if (c.hours.size == 24) null else c.hours.fold(mutableListOf<IntRange>()) { runs, h ->
            if (runs.isNotEmpty() && runs.last().last + 1 == h) runs[runs.lastIndex] = runs.last().first..h else runs.add(h..h)
            runs
        }.joinToString(", ") { "${f.clock(it.first, 0)}–${f.clock(it.last + 1, 0)}" }
    } else {
        if (c.hours.size < 2) return null
        val step = uniformStep(c.hours) ?: return null
        every = describeMinutes(step * 60, f) ?: return null
        // On the hour all day it needs no window; at another minute the window says which minute.
        val whole = c.minutes[0] == 0 && c.hours.first() == 0 && 24 % step == 0 && c.hours.last() == 24 - step
        window = if (whole) null else "${f.clock(c.hours.first(), c.minutes[0])}–${f.clock(c.hours.last(), c.minutes[0])}"
    }
    return listOfNotNull(every, window).joinToString(", ") + (days?.let { " · $it" } ?: "")
}

private fun describeOnce(runAt: String, timezone: String, f: ScheduleFormat): String? {
    // The instant in the Hermes timezone; a timestamp without an offset is already that clock.
    val local = runCatching { OffsetDateTime.parse(runAt).atZoneSameInstant(ZoneId.of(timezone)).toLocalDateTime() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(runAt.take(19)) }.getOrNull() ?: return null
    return "${f.text("Once")} · ${f.dayAndClock(local)}"
}

/** True for what Hermes stores as `display` when the owner typed a cron code: five fields of cron characters. */
internal fun looksLikeCron(text: String): Boolean = text.trim().split(Regex("\\s+")).let { p -> p.size >= 5 && p.take(5).all(cronToken::matches) }
private val cronToken = Regex("[A-Za-z\\d*\\-,/]+")

/** The schedule object a schedule string stands for, in the shape Hermes stores (`kind` and `minutes`, `expr` or `run_at`). */
internal fun scheduleObject(text: String): JSONObject? = text.takeIf(String::isNotBlank)?.let(::parseScheduleString)?.takeIf { it.mode != ScheduleMode.Custom }?.let { state ->
    val built = buildScheduleString(state)
    when (state.mode) {
        ScheduleMode.Every -> json("kind" to "interval", "minutes" to state.everyN * mapOf("minutes" to 1, "hours" to 60, "days" to 1440).getValue(state.everyUnit))
        ScheduleMode.Once -> json("kind" to "once", "run_at" to built)
        else -> json("kind" to "cron", "expr" to built)
    }
}

/**
 * A schedule in words ("Every day at 08:00", "Every 15 minutes, 10:00–22:00", "Once · 28 Oct, 19:01"), never a cron code.
 * [schedule] is the job's schedule object (or null) and [display] Hermes' own text for it; what cannot be described falls back to
 * [display] unless that is itself a cron code, then to "Custom schedule". Blank when there is nothing to describe.
 */
internal fun describeSchedule(schedule: JSONObject?, display: String, timezone: String, f: ScheduleFormat = ScheduleFormat()): String {
    val own = schedule?.let {
        when (it.string("kind")) {
            "interval" -> describeMinutes(it.optInt("minutes"), f)
            "once" -> describeOnce(it.string("run_at"), timezone, f)
            "cron" -> describeCron(it.string("expr", it.string("expression")), f)
            else -> null
        }
    }
    if (own != null) return own
    // A job stored without a structured schedule: its display text may still be a shape the builder knows.
    scheduleObject(display)?.let { return describeSchedule(it, "", timezone, f) }
    return display.trim().takeIf { it.isNotEmpty() && !looksLikeCron(it) } ?: if (schedule == null && display.isBlank()) "" else f.text("Custom schedule")
}
