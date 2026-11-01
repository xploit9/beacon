package dev.hermes.android.uxtour

import dev.hermes.android.core.json
import dev.hermes.android.tasks.ScheduleFormat
import dev.hermes.android.ui.ScheduleCardClock
import dev.hermes.android.ui.scheduleCardClock
import dev.hermes.android.ui.scheduleNextIsRelative
import dev.hermes.android.ui.scheduleReference
import dev.hermes.android.ui.scheduleZoneCity
import dev.hermes.android.ui.scheduleZoneNamed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** UX-38: every time on a schedule card is in the phone's zone; Hermes' zone is a second line only when it differs. */
class ScheduleClockTest {
    // The JVM has no best-pattern lookup, so the clock falls back to h:mm a and the day to "d MMM".
    private val twelveHour = ScheduleFormat(false, Locale.US, { it }, dayPattern = "d MMM")
    private val lisbon = "Europe/Lisbon"
    private val winter = Instant.parse("2026-01-15T08:00:00Z")
    private val summer = Instant.parse("2026-07-15T07:00:00Z")

    private fun cron(expr: String, hermes: String, phone: String, at: Instant): ScheduleCardClock =
        scheduleCardClock(json("kind" to "cron", "expr" to expr), "", hermes, ZoneId.of(phone), at, twelveHour)

    @Test fun aDailyTimeReadsInThePhonesZoneAndKeepsHermesTimeOnASecondLine() {
        val card = cron("0 8 * * *", lisbon, "Asia/Dubai", winter)
        assertEquals("Every day at 12:00 PM", card.description)
        assertEquals("Every day at 8:00 AM", card.inHermesClock)
        assertEquals(ZoneId.of("Asia/Dubai"), card.zone)
        assertFalse(card.zoneNamed)
        assertEquals("Lisbon", card.zoneLabel)
    }

    @Test fun daylightSavingMovesTheConvertedTimeWithTheDateOfTheRun() {
        // 8:00 in Lisbon is 08:00 UTC in winter and 07:00 UTC in summer; Dubai stays at UTC+4.
        assertEquals("Every day at 12:00 PM", cron("0 8 * * *", lisbon, "Asia/Dubai", winter).description)
        assertEquals("Every day at 11:00 AM", cron("0 8 * * *", lisbon, "Asia/Dubai", summer).description)
        // Lisbon changes clocks on 29 March 2026, New York on 8 March: the gap is 4 h in mid-March and 5 h in April.
        assertEquals("Every day at 4:00 AM", cron("0 8 * * *", lisbon, "America/New_York", Instant.parse("2026-03-15T08:00:00Z")).description)
        assertEquals("Every day at 3:00 AM", cron("0 8 * * *", lisbon, "America/New_York", Instant.parse("2026-04-15T07:00:00Z")).description)
    }

    @Test fun halfHourZonesMoveTheMinutesToo() {
        assertEquals("Every day at 1:30 PM", cron("0 8 * * *", lisbon, "Asia/Kolkata", winter).description)
        assertEquals("Every day at 1:00 PM, 9:00 PM", cron("0 9,17 * * *", lisbon, "Asia/Dubai", winter).description)
    }

    @Test fun whenTheZonesAgreeThereIsNoSecondLine() {
        // Lisbon and London share an offset all year; Dubai is Dubai.
        assertNull(cron("0 8 * * *", lisbon, "Europe/London", winter).inHermesClock)
        assertNull(cron("0 8 * * *", lisbon, "Europe/London", summer).inHermesClock)
        assertNull(cron("0 8 * * *", "Asia/Dubai", "Asia/Dubai", winter).inHermesClock)
        assertEquals("Every day at 8:00 AM", cron("0 8 * * *", lisbon, "Europe/London", winter).description)
    }

    @Test fun aWindowThatStaysInTheDayMovesAsAWhole() {
        val card = cron("*/15 7-21 * * *", lisbon, "Europe/Madrid", winter)
        assertEquals("Every 15 minutes, 8:00 AM–11:00 PM", card.description)
        assertEquals("Every 15 minutes, 7:00 AM–10:00 PM", card.inHermesClock)
        assertFalse(card.zoneNamed)
    }

    @Test fun aWindowThatWouldWrapPastMidnightStaysInHermesZoneAndNamesIt() {
        val card = cron("*/15 7-21 * * *", lisbon, "Asia/Dubai", winter)
        assertEquals("Every 15 minutes, 7:00 AM–10:00 PM", card.description)
        assertTrue(card.zoneNamed)
        assertEquals(ZoneId.of(lisbon), card.zone)
        assertNull(card.inHermesClock)
        assertEquals("Lisbon", card.zoneLabel)
    }

    @Test fun aDayOfWeekRuleThatCrossesMidnightStaysInHermesZone() {
        // Monday 23:00 in Lisbon is Tuesday 03:00 in Dubai, so "Every Monday" cannot be said in the phone's clock.
        val monday = cron("0 23 * * 1", lisbon, "Asia/Dubai", winter)
        assertTrue(monday.zoneNamed)
        assertEquals("Every Monday at 11:00 PM", monday.description)
        assertEquals(ZoneId.of(lisbon), monday.zone)
        // The same rule is converted when the day does not change.
        val noon = cron("0 9 * * 1-5", lisbon, "Asia/Dubai", winter)
        assertFalse(noon.zoneNamed)
        assertEquals("Weekdays at 1:00 PM", noon.description)
        assertEquals("Weekdays at 9:00 AM", noon.inHermesClock)
        // A day of the month is a day rule too.
        assertTrue(cron("0 23 15 * *", lisbon, "Asia/Dubai", winter).zoneNamed)
        assertEquals("Every month on day 15 at 9:00 AM", cron("0 8 15 * *", lisbon, "Europe/Madrid", winter).description)
    }

    @Test fun aDailyTimeMayCrossMidnightBecauseNoDayDependsOnIt() {
        val card = cron("0 22 * * *", lisbon, "Asia/Dubai", winter)
        assertEquals("Every day at 2:00 AM", card.description)
        assertEquals("Every day at 10:00 PM", card.inHermesClock)
        assertFalse(card.zoneNamed)
    }

    @Test fun anIntervalAndAnAllDayStepNameNoClockTimeSoNothingMoves() {
        val interval = scheduleCardClock(json("kind" to "interval", "minutes" to 120), "", lisbon, ZoneId.of("Asia/Dubai"), winter, twelveHour)
        assertEquals("Every 2 hours", interval.description)
        assertNull(interval.inHermesClock)
        assertFalse(interval.zoneNamed)
        val step = cron("*/20 * * * *", lisbon, "Asia/Kolkata", winter)
        assertEquals("Every 20 minutes", step.description)
        assertNull(step.inHermesClock)
        assertFalse(step.zoneNamed)
        // Hourly at a minute past is a clock time: 12:30 in Lisbon is 18:00 in Kolkata.
        assertEquals("Every hour", cron("30 * * * *", lisbon, "Asia/Kolkata", winter).description)
    }

    @Test fun aOneTimeScheduleMovesWhetherOrNotItsStampCarriesAnOffset() {
        val withOffset = scheduleCardClock(json("kind" to "once", "run_at" to "2026-10-28T19:01:00+04:00"), "", "Asia/Dubai", ZoneId.of(lisbon), Instant.parse("2026-10-28T15:01:00Z"), twelveHour)
        assertEquals("Once · 28 Oct, 3:01 PM", withOffset.description)
        assertEquals("Once · 28 Oct, 7:01 PM", withOffset.inHermesClock)
        // No offset: the stamp is Hermes' own wall clock.
        val bare = scheduleCardClock(json("kind" to "once", "run_at" to "2026-10-28T19:01:00"), "", "Asia/Dubai", ZoneId.of(lisbon), Instant.parse("2026-10-28T15:01:00Z"), twelveHour)
        assertEquals("Once · 28 Oct, 3:01 PM", bare.description)
    }

    @Test fun aScheduleHermesOnlyDescribedInWordsStillMoves() {
        // No structured schedule: the display text is a shape the builder knows.
        val card = scheduleCardClock(null, "0 8 * * *", lisbon, ZoneId.of("Asia/Dubai"), winter, twelveHour)
        assertEquals("Every day at 12:00 PM", card.description)
        assertEquals("Every day at 8:00 AM", card.inHermesClock)
        // Words the builder cannot read have no clock to move.
        val custom = scheduleCardClock(null, "twice a week", lisbon, ZoneId.of("Asia/Dubai"), winter, twelveHour)
        assertEquals("twice a week", custom.description)
        assertFalse(custom.zoneNamed)
        assertEquals("", scheduleCardClock(null, "", lisbon, ZoneId.of("Asia/Dubai"), winter, twelveHour).description)
    }

    @Test fun withoutAUsableHermesZoneNothingChanges() {
        listOf("", "Not/AZone").forEach { zone ->
            val card = cron("0 8 * * *", zone, "Asia/Dubai", winter)
            assertEquals("Every day at 8:00 AM", card.description)
            assertNull(card.inHermesClock)
            assertFalse(card.zoneNamed)
            assertEquals(ZoneId.of("Asia/Dubai"), card.zone)
        }
    }

    @Test fun theZoneIsNamedByItsCityAndTemplatesFillBothPlaces() {
        assertEquals("Lisbon", scheduleZoneCity("Europe/Lisbon"))
        assertEquals("Buenos Aires", scheduleZoneCity("America/Argentina/Buenos_Aires"))
        assertEquals("UTC", scheduleZoneCity("UTC"))
        assertEquals("Next: Today 8:00 AM (Lisbon)", scheduleZoneNamed("Next: Today 8:00 AM", "Lisbon", "{0} ({1})"))
        assertEquals("8:00 AM in Lisbon", scheduleZoneNamed("8:00 AM", "Lisbon", "{0} in {1}"))
    }

    @Test fun theRuleIsReadAtTheNextRunAndAnUnnamedRelativeNextRunNeedsNoZone() {
        val now = Instant.parse("2026-01-15T10:00:00Z")
        assertEquals(Instant.parse("2026-01-16T08:00:00Z"), scheduleReference("2026-01-16T08:00:00+00:00", now))
        assertEquals(now, scheduleReference("", now))
        assertEquals(now, scheduleReference("soon", now))
        assertTrue(scheduleNextIsRelative("2026-01-15T10:30:00+00:00", now))
        assertTrue(scheduleNextIsRelative("2026-01-15T09:00:00+00:00", now))
        assertTrue(scheduleNextIsRelative("", now))
        assertFalse(scheduleNextIsRelative("2026-01-15T12:00:00+00:00", now))
    }
}
