package dev.hermes.android.tasks
import dev.hermes.android.core.json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class ScheduleBuilderTest {
    // The JVM has no best-pattern lookup, so the clock falls back to HH:mm / h:mm a and the day to the given "d MMM".
    private fun format(is24Hour: Boolean = true, text: (String) -> String = { it }) = ScheduleFormat(is24Hour, Locale.US, text, dayPattern = "d MMM")
    private fun cron(expr: String, is24Hour: Boolean = true, display: String = "") = describeSchedule(json("kind" to "cron", "expr" to expr, "display" to display), display, "Asia/Dubai", format(is24Hour))

    @Test fun theOwnersExamplesReadInPlainWords() {
        assertEquals("Every day at 08:00", cron("0 8 * * *"))
        assertEquals("Every 15 minutes, 10:00–22:00", cron("*/15 10-21 * * *"))
        assertEquals("Weekdays at 09:00", cron("0 9 * * 1-5"))
        assertEquals("Every Monday at 09:00", cron("0 9 * * 1"))
        assertEquals("Every 2 hours", describeSchedule(json("kind" to "interval", "minutes" to 120, "display" to "every 120m"), "every 120m", "Asia/Dubai", format()))
        val once = json("kind" to "once", "run_at" to "2026-10-28T19:01:00+04:00", "display" to "once at 2026-10-28 19:01")
        assertEquals("Once · 28 Oct, 19:01", describeSchedule(once, "once at 2026-10-28 19:01", "Asia/Dubai", format()))
    }

    @Test fun theSameExamplesFollowThePhonesTwelveHourClock() {
        assertEquals("Every day at 8:00 AM", cron("0 8 * * *", is24Hour = false))
        assertEquals("Every 15 minutes, 10:00 AM–10:00 PM", cron("*/15 10-21 * * *", is24Hour = false))
        assertEquals("Weekdays at 9:00 AM", cron("0 9 * * 1-5", is24Hour = false))
        assertEquals("Every Monday at 5:30 PM", cron("30 17 * * 1", is24Hour = false))
        assertEquals("Once · 28 Oct, 7:01 PM", describeSchedule(json("kind" to "once", "run_at" to "2026-10-28T19:01:00+04:00"), "", "Asia/Dubai", format(is24Hour = false)))
    }

    @Test fun aOneTimeScheduleReadsOnTheHermesClockWhateverOffsetItWasStoredWith() {
        val utc = json("kind" to "once", "run_at" to "2026-10-28T15:01:00Z")
        assertEquals("Once · 28 Oct, 19:01", describeSchedule(utc, "", "Asia/Dubai", format()))
        // No offset: the stamp is already the Hermes clock. No usable timezone: the stored offset's own clock.
        assertEquals("Once · 28 Oct, 19:01", describeSchedule(json("kind" to "once", "run_at" to "2026-10-28T19:01:00"), "", "Asia/Dubai", format()))
        assertEquals("Once · 28 Oct, 15:01", describeSchedule(utc, "", "", format()))
    }

    @Test fun commonCronShapesAreDescribed() {
        assertEquals("Every 2 hours", cron("0 */2 * * *"))
        assertEquals("Every 2 hours, 10:00–20:00", cron("0 10-21/2 * * *"))
        assertEquals("Every hour", cron("0 * * * *"))
        assertEquals("Every minute", cron("* * * * *"))
        assertEquals("Every 30 minutes", cron("*/30 * * * *"))
        assertEquals("Every 15 minutes, 00:00–02:00, 10:00–00:00", cron("*/15 0-1,10-23 * * *"))
        assertEquals("Weekdays at 09:00, 17:00", cron("0 9,17 * * 1-5"))
        assertEquals("Weekends at 09:00", cron("0 9 * * 6,0"))
        assertEquals("Every Mon, Wed, Fri at 14:30", cron("30 14 * * 1,3,5"))
        assertEquals("Every Sunday at 09:00", cron("0 9 * * 7"))
        assertEquals("Every Monday at 09:00", cron("0 9 * * mon"))
        assertEquals("Weekdays at 09:00", cron("0 9 * * mon-fri"))
        assertEquals("Every month on day 15 at 00:00", cron("0 0 15 * *"))
        assertEquals("Every day at 09:00, 09:30", cron("0,30 9 * * *"))
        assertEquals("Every 15 minutes, 10:00–22:00 · Weekdays", cron("*/15 10-21 * * 1-5"))
        assertEquals("Every hour, 00:30–23:30", cron("30 * * * *"))
    }

    @Test fun whatCannotBeDescribedNeverShowsACronCode() {
        // Steps that do not divide the hour, a year, a month, both day fields: all left alone rather than misstated.
        listOf("*/7 * * * *", "0 9 * * * 2099", "0 9 15 6 *", "0 9 1 * 1", "0,15 * * * *", "*/90 * * * *", "0 25 * * *", "0 9 * * 8", "a b c d e").forEach { expr ->
            assertNull(expr, describeCron(expr, format()))
            assertEquals(expr, "Custom schedule", cron(expr, display = expr))
        }
        // Hermes' own display is used only when it is words; text that is itself a cron code becomes "Custom schedule".
        assertEquals("yearly on June 15", cron("0 9 15 6 *", display = "yearly on June 15"))
        assertEquals("Custom schedule", describeSchedule(null, "0 9 15 6 *", "Asia/Dubai", format()))
        assertEquals("", describeSchedule(null, "", "Asia/Dubai", format()))
        assertEquals("Custom schedule", describeSchedule(JSONObject(), "", "Asia/Dubai", format()))
    }

    @Test fun aJobWithoutAStructuredScheduleIsReadFromItsDisplayText() {
        assertEquals("Every 2 hours", describeSchedule(null, "every 2h", "Asia/Dubai", format()))
        assertEquals("Every day at 08:00", describeSchedule(null, "0 8 * * *", "Asia/Dubai", format()))
        assertEquals("Every day at 10:00", describeSchedule(json("kind" to "cron", "expression" to "0 10 * * *", "display" to "0 10 * * *"), "0 10 * * *", "Asia/Dubai", format()))
        assertEquals("weekdays at 9am", describeSchedule(JSONObject(), "weekdays at 9am", "Asia/Dubai", format()))
    }

    @Test fun descriptionsUseOnlyTheWordsTheAppTranslates() {
        val seen = mutableSetOf<String>()
        val f = format(text = { seen.add(it); it })
        val expressions = listOf("0 8 * * *", "*/15 10-21 * * *", "0 9 * * 1-5", "0 9 * * 0,6", "0 9 * * 1", "30 14 * * 1,3,5", "0 0 15 * *", "0 */2 * * *", "* * * * *", "*/15 10-21 * * 1-5", "0 9 15 6 *")
        expressions.forEach { assertFalse(it, describeSchedule(json("kind" to "cron", "expr" to it), "", "Asia/Dubai", f).contains('*')) }
        listOf(1, 60, 120, 1440, 2880, 90).forEach { describeSchedule(json("kind" to "interval", "minutes" to it), "", "Asia/Dubai", f) }
        describeSchedule(json("kind" to "once", "run_at" to "2026-10-28T19:01:00+04:00"), "", "Asia/Dubai", f)
        assertTrue("These are not in ScheduleBuilderStrings: ${seen - ScheduleBuilderStrings.toSet()}", ScheduleBuilderStrings.containsAll(seen))
        // The weekday and month names come from the phone's language, not the catalog.
        assertEquals("Jeden Montag um 09:00", describeSchedule(json("kind" to "cron", "expr" to "0 9 * * 1"), "", "Europe/Berlin",
            ScheduleFormat(true, Locale.GERMAN, { if (it == "Every {days} at {time}") "Jeden {days} um {time}" else it }, "d MMM")))
    }

    @Test fun intervalsPickTheirLargestUnit() {
        fun every(minutes: Int) = describeSchedule(json("kind" to "interval", "minutes" to minutes), "", "", format())
        assertEquals("Every minute", every(1)); assertEquals("Every 45 minutes", every(45)); assertEquals("Every hour", every(60)); assertEquals("Every 90 minutes", every(90))
        assertEquals("Every 3 hours", every(180)); assertEquals("Every day", every(1440)); assertEquals("Every 2 days", every(2880))
        assertEquals("Custom schedule", every(0))
    }

    // --- Building, parsing and round trips ---

    private fun roundTrip(state: ScheduleBuilderState): ScheduleBuilderState {
        val text = buildScheduleString(state)
        assertTrue("no string for $state", text.isNotBlank())
        return parseScheduleString(text)
    }

    @Test fun everyModeBuildsAStringItsParserReadsBack() {
        val every = ScheduleBuilderState(ScheduleMode.Every, everyN = 15, everyUnit = "minutes")
        assertEquals("every 15m", buildScheduleString(every)); assertEquals(every, roundTrip(every))
        val hours = every.copy(everyN = 2, everyUnit = "hours")
        assertEquals("every 2h", buildScheduleString(hours)); assertEquals(hours, roundTrip(hours))
        val days = every.copy(everyN = 3, everyUnit = "days")
        assertEquals("every 3d", buildScheduleString(days)); assertEquals(days, roundTrip(days))
        // A stored "every 120m" opens as 2 hours.
        assertEquals(hours, parseScheduleString("every 120m"))
        assertEquals("", buildScheduleString(every.copy(everyN = 0))); assertEquals("", buildScheduleString(every.copy(everyUnit = "weeks")))
    }

    @Test fun dailyWeeklyAndMonthlyBuildCronAndReadBack() {
        val daily = ScheduleBuilderState(ScheduleMode.Daily, hour = 8, minute = 5)
        assertEquals("5 8 * * *", buildScheduleString(daily)); assertEquals(daily, roundTrip(daily))
        val weekly = ScheduleBuilderState(ScheduleMode.Weekly, hour = 14, minute = 30, weekdays = setOf(5, 1, 3))
        assertEquals("30 14 * * 1,3,5", buildScheduleString(weekly)); assertEquals(weekly, roundTrip(weekly))
        val monthly = ScheduleBuilderState(ScheduleMode.Monthly, hour = 9, minute = 0, dayOfMonth = 15)
        assertEquals("0 9 15 * *", buildScheduleString(monthly)); assertEquals(monthly, roundTrip(monthly))
        // Hermes sends "*" for no chosen day; every day is also how an all-days list reads back.
        assertEquals("0 9 * * *", buildScheduleString(weekly.copy(hour = 9, minute = 0, weekdays = emptySet())))
        assertEquals(ScheduleMode.Daily, parseScheduleString("0 9 * * 0-6").mode)
        assertEquals(setOf(1, 2, 3, 4, 5), parseScheduleString("0 9 * * mon-fri").weekdays)
        assertEquals(setOf(0), parseScheduleString("0 9 * * 7").weekdays)
        assertEquals("", buildScheduleString(daily.copy(hour = 24))); assertEquals("", buildScheduleString(monthly.copy(dayOfMonth = 32)))
    }

    @Test fun onceBuildsATimestampAndReadsBackWhateverOffsetItCarries() {
        val once = ScheduleBuilderState(ScheduleMode.Once, hour = 19, minute = 1, onceDate = "2026-10-28")
        assertEquals("2026-10-28T19:01:00", buildScheduleString(once)); assertEquals(once, roundTrip(once))
        assertEquals(once, parseScheduleString("2026-10-28T19:01:00+04:00"))
        assertEquals(once, parseScheduleString("2026-10-28T19:01:00.123456+04:00"))
        assertEquals(once, parseScheduleString("2026-10-28T19:01"))
        assertEquals("", buildScheduleString(once.copy(onceDate = "")))
    }

    @Test fun aFixedWindowKeepsItsBuildAndOpensAsAWindow() {
        val window = ScheduleBuilderState(ScheduleMode.Fixed, windowStart = 10, windowEnd = 22, windowEvery = 2)
        assertEquals("0 10-21/2 * * *", buildScheduleString(window)); assertEquals(window, roundTrip(window))
        // An inclusive end is the same hours with one more; it opens as the exclusive window and builds the same string.
        val inclusive = window.copy(windowIncludeEnd = true)
        assertEquals("0 10-22/2 * * *", buildScheduleString(inclusive))
        assertEquals("0 10-22/2 * * *", buildScheduleString(parseScheduleString("0 10-22/2 * * *")))
        assertEquals("", buildScheduleString(window.copy(windowStart = 23)))
        assertEquals(ScheduleMode.Custom, parseScheduleString("0 10-21/5 * * *").mode)
    }

    @Test fun whatThePickerCannotExpressStaysCustomAndVerbatim() {
        listOf("*/15 10-21 * * *", "0 10,14,18 * * *", "in 30m", "30m", "every monday 9am", "weekdays at 9am", "0 9 * * * 2099", "every 2 hours").forEach { text ->
            val state = parseScheduleString(text)
            assertEquals(text, ScheduleMode.Custom, state.mode)
            assertEquals(text, buildScheduleString(state))
        }
        assertEquals(ScheduleBuilderState(), parseScheduleString("  "))
    }

    /** Hermes' cron/jobs.py::parse_schedule: "every <duration>" (line 802-803, parse_duration 673-682), five cron fields of [A-Za-z\d*-,/] (807-810), an ISO timestamp (812-831). */
    private fun parseScheduleAccepts(text: String): Boolean {
        if (Regex("every \\d+[mhd]").matches(text)) return true
        val parts = text.split(Regex("\\s+"))
        if (parts.size >= 5 && parts.take(5).all { Regex("[A-Za-z\\d*\\-,/]+").matches(it) }) return true
        return Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}").matches(text) && runCatching { LocalDateTime.parse(text) }.isSuccess
    }

    @Test fun everyStringTheAppSendsIsOneParseScheduleAccepts() {
        val states = listOf(
            ScheduleBuilderState(ScheduleMode.Every, everyN = 30), ScheduleBuilderState(ScheduleMode.Every, everyN = 2, everyUnit = "hours"), ScheduleBuilderState(ScheduleMode.Every, everyN = 1, everyUnit = "days"),
            ScheduleBuilderState(ScheduleMode.Daily, hour = 8), ScheduleBuilderState(ScheduleMode.Daily, hour = 0, minute = 59), ScheduleBuilderState(ScheduleMode.Weekly, weekdays = setOf(0, 6)), ScheduleBuilderState(ScheduleMode.Weekly, weekdays = setOf(1, 2, 3, 4, 5)),
            ScheduleBuilderState(ScheduleMode.Monthly, dayOfMonth = 31), ScheduleBuilderState(ScheduleMode.Once, onceDate = "2026-02-03", hour = 14), ScheduleBuilderState(ScheduleMode.Fixed), ScheduleBuilderState(ScheduleMode.Fixed, windowIncludeEnd = true),
        )
        val strings = states.map(::buildScheduleString)
        assertEquals(listOf("every 30m", "every 2h", "every 1d", "0 8 * * *", "59 0 * * *", "0 9 * * 0,6", "0 9 * * 1,2,3,4,5", "0 9 31 * *", "2026-02-03T14:00:00", "0 10-21/2 * * *", "0 10-22/2 * * *"), strings)
        strings.forEach { assertTrue(it, parseScheduleAccepts(it)) }
        // Not the natural-language branch (730-763): its day words and clock times are never in these strings.
        strings.forEach { assertFalse(it, Regex("(?i)^(every )?(day|daily|weekday|weekend|mon|tue|wed|thu|fri|sat|sun)").containsMatchIn(it)) }
    }

    // --- Saving ---

    @Test fun anUntouchedScheduleIsSavedAsHermesHadIt() {
        listOf("every 120m", "2026-10-28T19:01:00+04:00", "0 9 * * mon-fri", "*/15 10-21 * * *", "0 10-21/2 * * *", "in 30m").forEach { stored ->
            assertEquals(stored, scheduleToSave(parseScheduleString(stored), stored))
        }
    }

    @Test fun aChangedScheduleIsSavedAsBuilt() {
        assertEquals("0 9 * * *", scheduleToSave(ScheduleBuilderState(ScheduleMode.Daily, hour = 9), "0 8 * * *"))
        assertEquals("every 3h", scheduleToSave(ScheduleBuilderState(ScheduleMode.Every, everyN = 3, everyUnit = "hours"), "every 120m"))
        assertEquals("every 2h", scheduleToSave(ScheduleBuilderState(ScheduleMode.Custom, custom = " every 2h "), ""))
    }

    @Test fun anIncompleteScheduleSaysWhatIsMissing() {
        fun message(state: ScheduleBuilderState, stored: String = "") = runCatching { scheduleToSave(state, stored) }.exceptionOrNull()?.message
        assertEquals("Enter how often this should run.", message(ScheduleBuilderState(ScheduleMode.Every, everyN = 0)))
        assertEquals("Enter when this should run.", message(ScheduleBuilderState(ScheduleMode.Custom, custom = "  ")))
        assertEquals("Choose at least one day.", message(ScheduleBuilderState(ScheduleMode.Weekly, weekdays = emptySet())))
        assertEquals("Choose a start hour before the end hour.", message(ScheduleBuilderState(ScheduleMode.Fixed, windowStart = 23)))
        assertEquals("Choose a gap of 1 to 12 hours.", message(ScheduleBuilderState(ScheduleMode.Fixed, windowEvery = 0)))
        assertEquals("Choose an end hour of 23 or earlier, or turn off Include the end hour.", message(ScheduleBuilderState(ScheduleMode.Fixed, windowEnd = 24, windowIncludeEnd = true)))
        // Every message the owner can get starts with a word that reaches the form (safeLocalErrorText) and has a translation.
        listOf("Enter how often this should run.", "Enter when this should run.", "Choose at least one day.", "Choose a time in the future.").forEach { assertTrue(it in ScheduleBuilderStrings) }
    }

    @Test fun aOneTimeScheduleInThePastIsRefusedButAnUnchangedOneIsKept() {
        val zone = ZoneId.of("Asia/Dubai")
        val now = LocalDateTime.of(2026, 10, 28, 12, 0)
        val past = ScheduleBuilderState(ScheduleMode.Once, onceDate = "2026-10-28", hour = 9)
        assertEquals("Choose a time in the future.", runCatching { scheduleToSave(past, "", zone, now) }.exceptionOrNull()?.message)
        assertEquals("2026-10-28T13:00:00", scheduleToSave(past.copy(hour = 13), "", zone, now))
        // A job that already ran is not refused for being old: nothing about its time changed.
        assertEquals("2026-10-28T09:00:00+04:00", scheduleToSave(past, "2026-10-28T09:00:00+04:00", zone, now))
        // Without the Hermes zone the check is skipped: the phone's clock may be in another zone, and Hermes refuses a past time itself.
        assertEquals("2026-10-28T09:00:00", scheduleToSave(past, ""))
    }

    @Test fun theFormBodyFollowsItsModeAndReadsValuesTheWayTheFormStoresThem() {
        fun body(mode: String, vararg more: Pair<String, Any?>) = json("_schedule_mode" to mode, "_hour" to 8L, "_minute" to 0L, "_every_n" to JSONObject.NULL, "_timezone" to "Asia/Dubai", "schedule" to "stale text", *more)
        assertEquals("0 8 * * *", formSchedule(body("Daily")))
        assertEquals("stale text", formSchedule(body("Custom schedule")))
        assertEquals("every 15m", formSchedule(body("Every", "_every_n" to 15L, "_every_unit" to "minutes")))
        assertEquals("0 8 * * 1,5", formSchedule(body("Weekly", "_wd_1" to true, "_wd_5" to true, "_wd_2" to false)))
        assertEquals("0 8 * * *", formSchedule(body("Daily", "_original" to json("schedule" to "0 8 * * *"))))
        // A mode the form does not know (an old draft) is read as typed text.
        assertEquals("stale text", formSchedule(body("")))
    }

    @Test fun theFormShowsOnlyTheFieldsOfItsMode() {
        fun visible(mode: String) = listOf("_once_date", "_every_n", "_every_unit", "_wd_1", "_dom", "_hour", "_minute", "_start_hour", "_include_end", "schedule").filter { scheduleFieldVisible(it, mode) }
        assertEquals(listOf("_once_date", "_hour", "_minute"), visible("Once"))
        assertEquals(listOf("_every_n", "_every_unit"), visible("Every"))
        assertEquals(listOf("_hour", "_minute"), visible("Daily"))
        assertEquals(listOf("_wd_1", "_hour", "_minute"), visible("Weekly"))
        assertEquals(listOf("_dom", "_hour", "_minute"), visible("Monthly"))
        assertEquals(listOf("_start_hour", "_include_end"), visible("Fixed window"))
        assertEquals(listOf("schedule"), visible("Custom schedule"))
        assertEquals(listOf("schedule"), visible(""))
        listOf("name", "prompt", "deliver", "_schedule_mode", "skills").forEach { assertTrue(it, scheduleFieldVisible(it, "Daily")) }
    }

    @Test fun theFormSeedCarriesEveryFieldWithNumbersAsNumbers() {
        val seed = parseScheduleString("30 14 * * 1,3,5").formSeed(java.time.LocalDate.of(2026, 10, 28))
        assertEquals("Weekly", seed["_schedule_mode"]); assertEquals(14, seed["_hour"]); assertEquals(30, seed["_minute"])
        assertEquals(listOf(false, true, false, true, false, true, false), (0..6).map { seed["_wd_$it"] })
        assertEquals("2026-10-29", seed["_once_date"])
        assertEquals(scheduleStateFrom { key -> seed[key]?.toString().orEmpty() }.copy(onceDate = "", custom = ""), parseScheduleString("30 14 * * 1,3,5"))
    }
}
