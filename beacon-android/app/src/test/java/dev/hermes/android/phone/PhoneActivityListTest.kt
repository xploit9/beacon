package dev.hermes.android.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** What the Activity list shows: search, the three filters, day groups, newest first and the page. */
class PhoneActivityListTest {
    private val utc = ZoneId.of("UTC")
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2025, 11, day, hour, minute, 0, 0, utc).toInstant().toEpochMilli()
    private fun entry(at: Long, tool: String, line: PhoneLine, outcome: String = "done", origin: PhoneOrigin = PhoneOrigin.Hermes) =
        PhoneActivityEntry(at, origin, tool, line, outcome)

    private val dentist = entry(at(4, 9), "phone_calendar_create", PhoneLine("Add '{0}' {1}", listOf("Dentist", "Tue 4 Nov 3:00 PM")))
    private val pills = entry(at(4, 10), "phone_reminder_create", PhoneLine("Remind you: '{0}' {1}", listOf("Pills", "Tue 4 Nov 8:00 PM")), origin = PhoneOrigin.LiveCall)
    private val alarm = entry(at(3, 22), "phone_alarm_set", PhoneLine("Set an alarm for {0}", listOf("7:30 AM")), outcome = "denied")
    private val call = entry(at(3, 8), "phone_call", PhoneLine("Call {0}", listOf("Mona Test")), outcome = "expired", origin = PhoneOrigin.Overlay)
    private val find = entry(at(2, 12), "phone_contacts_search", PhoneLine("Search contacts for '{0}'", listOf("Sam")), outcome = "off")
    private val draft = entry(at(1, 18), "phone_message_draft", PhoneLine("Write to {0}", listOf("+15550100")), outcome = "permission")
    private val all = listOf(dentist, pills, alarm, call, find, draft)
    private val text: (PhoneLine) -> String = PhoneLine::text

    private fun shown(filter: PhoneActivityFilter, from: List<PhoneActivityEntry> = all) = filterActivity(from, filter, text)

    @Test fun noFilterListsEverythingNewestFirstWhateverTheStoredOrder() {
        assertEquals(listOf(pills, dentist, alarm, call, find, draft), shown(PhoneActivityFilter(), all.shuffled(java.util.Random(7))))
        assertFalse(PhoneActivityFilter().active)
        assertFalse(PhoneActivityFilter(query = "  ").active)
    }

    @Test fun searchMatchesTheActionTextWhateverTheCaseAndEveryWordTyped() {
        assertEquals(listOf(dentist), shown(PhoneActivityFilter(query = "dentist")))
        assertEquals(listOf(dentist), shown(PhoneActivityFilter(query = "  DENT  ")))
        assertEquals(listOf(pills, dentist), shown(PhoneActivityFilter(query = "tue 4 nov")))
        assertEquals(listOf(dentist), shown(PhoneActivityFilter(query = "add dentist")))
        assertEquals(listOf(call), shown(PhoneActivityFilter(query = "mona call")))
        assertTrue(shown(PhoneActivityFilter(query = "dentist pills")).isEmpty())
        assertTrue(shown(PhoneActivityFilter(query = "zzz")).isEmpty())
        assertTrue(PhoneActivityFilter(query = "x").active)
    }

    @Test fun searchReadsTheLineTheOwnerSeesNotTheEnglishTemplate() {
        val german: (PhoneLine) -> String = { PhoneLine(it.template.replace("Call", "Anruf: "), it.args).text() }
        assertEquals(listOf(call), filterActivity(all, PhoneActivityFilter(query = "anruf"), german))
        assertTrue(filterActivity(all, PhoneActivityFilter(query = "call"), german).isEmpty())
    }

    @Test fun typeFilterFollowsTheToolsCapability() {
        assertEquals(listOf(pills, dentist), shown(PhoneActivityFilter(capability = PhoneCapability.Calendar)))
        assertEquals(listOf(alarm), shown(PhoneActivityFilter(capability = PhoneCapability.Alarms)))
        assertEquals(listOf(find), shown(PhoneActivityFilter(capability = PhoneCapability.Contacts)))
        assertEquals(listOf(call), shown(PhoneActivityFilter(capability = PhoneCapability.Calls)))
        assertEquals(listOf(draft), shown(PhoneActivityFilter(capability = PhoneCapability.Messages)))
        val unknown = entry(at(4, 11), "phone_future_tool", PhoneLine("Something new"))
        assertEquals("a tool this version does not know is listed but under no type", emptyList<PhoneActivityEntry>(), shown(PhoneActivityFilter(capability = PhoneCapability.Calendar), listOf(unknown)))
        assertEquals(listOf(unknown), shown(PhoneActivityFilter(), listOf(unknown)))
    }

    @Test fun everyLoggedOutcomeLandsInOneOfThreeResults() {
        assertEquals(PhoneResult.Done, phoneResultOf("done"))
        assertEquals(PhoneResult.SaidNo, phoneResultOf("denied"))
        for (other in listOf("expired", "cancelled", "off", "permission", "busy", "not_found", "unavailable", "invalid", "locked", "something-new"))
            assertEquals(other, PhoneResult.DidntRun, phoneResultOf(other))
        assertEquals(listOf(pills, dentist), shown(PhoneActivityFilter(result = PhoneResult.Done)))
        assertEquals(listOf(alarm), shown(PhoneActivityFilter(result = PhoneResult.SaidNo)))
        assertEquals(listOf(call, find, draft), shown(PhoneActivityFilter(result = PhoneResult.DidntRun)))
    }

    @Test fun whoAskedFilterUsesTheLoggedOrigin() {
        assertEquals(listOf(dentist, alarm, find, draft), shown(PhoneActivityFilter(origin = PhoneOrigin.Hermes)))
        assertEquals(listOf(pills), shown(PhoneActivityFilter(origin = PhoneOrigin.LiveCall)))
        assertEquals(listOf(call), shown(PhoneActivityFilter(origin = PhoneOrigin.Overlay)))
    }

    @Test fun filtersCombineAndNarrowTogether() {
        assertEquals(listOf(dentist), shown(PhoneActivityFilter(query = "tue", capability = PhoneCapability.Calendar, origin = PhoneOrigin.Hermes)))
        assertTrue(shown(PhoneActivityFilter(query = "tue", capability = PhoneCapability.Calendar, result = PhoneResult.SaidNo)).isEmpty())
        assertTrue(PhoneActivityFilter(capability = PhoneCapability.Calls, result = PhoneResult.Done).active)
    }

    @Test fun daysAreGroupedNewestFirstAndSplitAtLocalMidnight() {
        val days = groupActivityByDay(shown(PhoneActivityFilter()), utc)
        assertEquals(listOf(LocalDate.of(2025, 11, 4), LocalDate.of(2025, 11, 3), LocalDate.of(2025, 11, 2), LocalDate.of(2025, 11, 1)), days.map { it.day })
        assertEquals(listOf(listOf(pills, dentist), listOf(alarm, call), listOf(find), listOf(draft)), days.map { it.entries })
        assertTrue(groupActivityByDay(emptyList(), utc).isEmpty())
        // 23:30 UTC is already the next day in Riyadh (UTC+3).
        val late = entry(at(4, 23, 30), "phone_timer_set", PhoneLine("Start a timer for {0}", listOf("5 min")))
        val early = entry(at(4, 1, 0), "phone_timer_set", PhoneLine("Start a timer for {0}", listOf("1 min")))
        val riyadh = groupActivityByDay(listOf(late, early), ZoneId.of("Asia/Riyadh"))
        assertEquals(listOf(LocalDate.of(2025, 11, 5), LocalDate.of(2025, 11, 4)), riyadh.map { it.day })
        assertEquals(1, groupActivityByDay(listOf(late, early), utc).size)
    }

    @Test fun aDayIsNamedTodayYesterdayOrByItsDate() {
        val today = LocalDate.of(2025, 11, 4)
        assertEquals(PhoneDayName.Today, phoneDayName(today, today))
        assertEquals(PhoneDayName.Yesterday, phoneDayName(today.minusDays(1), today))
        assertEquals(PhoneDayName.Date, phoneDayName(today.minusDays(2), today))
        assertEquals("a day in the future is a date, not today", PhoneDayName.Date, phoneDayName(today.plusDays(1), today))
    }

    @Test fun theListShowsTwentyRowsAtATimeAndNeverMoreThanItHolds() {
        val many = (1..65).map { entry(1_000L * it, "phone_timer_set", PhoneLine("Start a timer for {0}", listOf("$it min"))) }
        val newest = shown(PhoneActivityFilter(), many)
        assertEquals(20, ACTIVITY_PAGE)
        assertEquals(20, activityPage(newest, 1).size)
        assertEquals(40, activityPage(newest, 2).size)
        assertEquals(60, activityPage(newest, 3).size)
        assertEquals(65, activityPage(newest, 4).size)
        assertEquals("a page count that is not positive still shows the first page", 20, activityPage(newest, 0).size)
        assertEquals(newest.take(20), activityPage(newest, 1))
        assertEquals(PhoneActivityLog.LIMIT, 200)
        assertEquals(emptyList<PhoneActivityEntry>(), activityPage(emptyList(), 1))
    }
}
