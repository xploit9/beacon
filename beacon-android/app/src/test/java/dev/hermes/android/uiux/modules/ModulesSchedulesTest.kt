package dev.hermes.android.uiux.modules

import dev.hermes.android.tasks.ScheduleChip
import dev.hermes.android.tasks.ScheduleWhenWords
import dev.hermes.android.tasks.scheduleNextRun
import dev.hermes.android.tasks.scheduleZoneDiffers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The Schedules card words: the status chip and the next run in the phone's own zone. Synthetic times only. */
class ModulesSchedulesTest {
    private val now = Instant.parse("2026-10-31T19:44:00Z")
    private val utc = ZoneId.of("UTC")
    private val dubai = ZoneId.of("Asia/Dubai")
    private fun next(value: String, zone: ZoneId = utc, words: ScheduleWhenWords = ScheduleWhenWords()) =
        scheduleNextRun(value, "Not scheduled", words, now, zone, true, Locale.US)

    @Test fun theNextRunWithinTheHourIsInMinutesRoundedUp() {
        assertEquals("in 1 min", next("2026-10-31T19:44:30Z"))
        assertEquals("in 2 min", next("2026-10-31T19:45:30Z"))
        assertEquals("in 46 min", next("2026-10-31T20:30:00Z"))
        // An hour or more away is a clock time, not minutes.
        assertEquals("Today 20:45", next("2026-10-31T20:45:00Z"))
    }

    @Test fun aRunThatIsDueOrOverdueSaysSo() {
        assertEquals("Due now", next("2026-10-31T19:44:00Z"))
        assertEquals("Due now", next("2026-10-31T15:45:00Z"))
    }

    @Test fun laterTodayAndTomorrowCarryTheClockInThePhonesZone() {
        assertEquals("Today 22:15", next("2026-10-31T22:15:00Z"))
        // 08:00 in Dubai is 04:00 UTC: tomorrow for a phone in UTC and for one in Dubai (23:44 there tonight).
        assertEquals("Tomorrow 04:00", next("2026-11-01T08:00:00+04:00"))
        assertEquals("Tomorrow 08:00", next("2026-11-01T08:00:00+04:00", dubai))
    }

    @Test fun thePhonesDayDecidesTodayNotTheServers() {
        // 02:00 on 1 Nov in Dubai is 22:00 UTC on 31 Oct: still today for a UTC phone, tomorrow for the Dubai one.
        assertEquals("Today 22:00", next("2026-11-01T02:00:00+04:00"))
        assertEquals("Tomorrow 02:00", next("2026-11-01T02:00:00+04:00", dubai))
    }

    @Test fun insideAWeekItIsTheWeekdayThenTheDate() {
        assertEquals("Wed 09:00", next("2026-11-04T09:00:00Z"))
        assertEquals("Dec 25, 09:00", next("2026-12-25T09:00:00Z"))
        assertEquals("Jan 5, 2027, 09:00", next("2027-01-05T09:00:00Z"))
    }

    @Test fun theWordsComeFromTheTemplatesSoAnotherLanguageCanFillThem() {
        val words = ScheduleWhenWords("Maintenant", "dans {0} min", "Aujourd’hui {0}", "Demain {0}")
        assertEquals("dans 2 min", next("2026-10-31T19:45:30Z", words = words))
        assertEquals("Aujourd’hui 22:15", next("2026-10-31T22:15:00Z", words = words))
        assertEquals("Demain 04:00", next("2026-11-01T08:00:00+04:00", words = words))
        assertEquals("Maintenant", next("2026-10-30T00:00:00Z", words = words))
    }

    @Test fun noNextRunShowsTheEmptyWord() {
        assertEquals("Not scheduled", next(""))
        assertEquals("Time unavailable", next("tomorrow"))
    }

    @Test fun theServersZoneIsNamedOnlyWhenItsClockDiffersFromThePhones() {
        assertTrue(scheduleZoneDiffers("Asia/Dubai", utc, now))
        assertFalse(scheduleZoneDiffers("UTC", utc, now))
        assertFalse(scheduleZoneDiffers("", utc, now))
        // Lisbon is on UTC in late October, so a UTC phone needs no note, but one in summer time would.
        assertFalse(scheduleZoneDiffers("Europe/Lisbon", utc, now))
        assertTrue(scheduleZoneDiffers("Europe/Lisbon", utc, Instant.parse("2026-07-15T12:00:00Z")))
        // A zone Beacon cannot read is better named than hidden.
        assertTrue(scheduleZoneDiffers("Not/AZone", utc, now))
    }

    private fun chip(vararg fields: Pair<String, Any>) = ScheduleChip.of(JSONObject().apply { fields.forEach { (key, value) -> put(key, value) } })

    @Test fun theChipSaysActivePausedFailedOrRunning() {
        assertEquals(ScheduleChip.Active, chip("next_run_at" to "2026-11-01T08:00:00Z"))
        assertEquals(ScheduleChip.Paused, chip("state" to "paused"))
        assertEquals(ScheduleChip.Paused, chip("enabled" to false, "next_run_at" to "2026-11-01T08:00:00Z"))
        assertEquals(ScheduleChip.Failed, chip("last_status" to "error", "next_run_at" to "2026-11-01T08:00:00Z"))
        assertEquals(ScheduleChip.Running, chip("state" to "running"))
        assertEquals(ScheduleChip.Completed, chip("state" to "completed"))
        assertEquals(ScheduleChip.Unconfirmed, chip())
    }
}
