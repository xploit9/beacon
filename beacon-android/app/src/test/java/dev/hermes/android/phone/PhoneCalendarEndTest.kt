package dev.hermes.android.phone

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant

/**
 * Strix vuln-0013 (the brief's 0013): the approval card for a calendar write shows the span the phone will write, start AND end, as it
 * is written. Before the fix a timed event's card named only its start, so a 300-day event, or an update that silently moved an end,
 * looked like a one-hour tap.
 */
class PhoneCalendarEndTest {
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }
    private fun ready() = Rig().also { it.settings.everything() }
    private fun prepare(rig: Rig, name: String, args: JSONObject) = runBlocking { rig.engine.prepare(name, args, PhoneOrigin.LiveCall) }
    private val start = "2025-11-04T15:00:00Z"
    private fun millis(text: String) = Instant.parse(text).toEpochMilli()

    @Test fun aCreateThatEndsThreeHundredDaysLaterSaysSo() {
        val rig = ready()
        val args = obj("title" to "Standup", "start" to start, "end" to "2026-08-31T15:00:00Z")
        val card = prepare(rig, "phone_calendar_create", args).card
        assertEquals("Add 'Standup' Tue 4 Nov 3:00 PM – Mon 31 Aug 3:00 PM to Personal", card.headline.text())
        assertEquals("Add 'Standup' Tue 4 Nov 3:00 PM – Mon 31 Aug 3:00 PM to Personal", card.describe())
        // The card is the write: what the phone saves is exactly the span the card names.
        runBlocking { rig.engine.run("phone_calendar_create", args, PhoneOrigin.LiveCall, Approved) }
        val saved = rig.calendar.inserted.single()
        assertEquals(300L * 86_400_000L, saved.endMillis - saved.startMillis)
        assertEquals(millis("2026-08-31T15:00:00Z"), saved.endMillis)
    }

    @Test fun anEventWithNoEndShowsItsOneHourEnd() {
        val rig = ready()
        assertEquals("Add 'Dentist' Tue 4 Nov 3:00 PM – 4:00 PM to Personal", prepare(rig, "phone_calendar_create", obj("title" to "Dentist", "start" to start)).card.describe())
        // Without the calendar lookup (a notification) the span is still whole.
        assertEquals("Add 'Dentist' Tue 4 Nov 3:00 PM – 4:00 PM", rig.engine.describeQuick("phone_calendar_create", obj("title" to "Dentist", "start" to start)))
    }

    @Test fun anEndOnAnotherDayNamesTheDay() {
        val rig = ready()
        val card = prepare(rig, "phone_calendar_create", obj("title" to "Night shift", "start" to start, "end" to "2025-11-05T09:00:00Z")).card
        assertEquals("Add 'Night shift' Tue 4 Nov 3:00 PM – Wed 5 Nov 9:00 AM to Personal", card.headline.text())
    }

    @Test fun anUpdateThatOnlyMovesTheEndShowsTheNewEndAndDiffersFromOneThatDoesNotTouchTheTime() {
        val rig = ready()
        rig.calendar.events[5] = EventInfo(5, 1, "Dentist", millis(start), millis("2025-11-04T16:00:00Z"), false, "", "", emptyList())
        val moved = prepare(rig, "phone_calendar_update", obj("id" to "5", "end" to "2025-11-06T16:00:00Z")).card
        assertEquals("Change 'Dentist'; When: Tue 4 Nov 3:00 PM – Thu 6 Nov 4:00 PM", moved.describe())
        val untouched = prepare(rig, "phone_calendar_update", obj("id" to "5", "title" to "Doctor")).card
        assertEquals("Change 'Dentist'; Title: Doctor", untouched.describe())
        assertNotEquals(moved.describe(), prepare(rig, "phone_calendar_update", obj("id" to "5", "location" to "Clinic")).card.describe())
        // A new start keeps the stored length, and the card says where that ends.
        assertEquals("Change 'Dentist'; When: Wed 5 Nov 10:00 AM – 11:00 AM", prepare(rig, "phone_calendar_update", obj("id" to "5", "start" to "2025-11-05T10:00:00Z")).card.describe())
    }

    @Test fun aDeleteShowsTheWholeSpanOfTheEventItRemoves() {
        val rig = ready()
        rig.calendar.events[5] = EventInfo(5, 1, "Offsite", millis(start), millis("2025-11-07T12:00:00Z"), false, "", "", emptyList())
        assertEquals("Delete 'Offsite' Tue 4 Nov 3:00 PM – Fri 7 Nov 12:00 PM", prepare(rig, "phone_calendar_delete", obj("id" to "5")).card.describe())
        rig.calendar.events[6] = EventInfo(6, 1, "Dentist", millis(start), millis("2025-11-04T16:00:00Z"), false, "", "", emptyList())
        assertEquals("Delete 'Dentist' Tue 4 Nov 3:00 PM – 4:00 PM", prepare(rig, "phone_calendar_delete", obj("id" to "6")).card.describe())
    }

    @Test fun anAllDayEventAndAReminderKeepTheirCards() {
        val rig = ready()
        assertEquals("Add 'Trip' Mon 10 Nov – Tue 11 Nov to Personal",
            prepare(rig, "phone_calendar_create", obj("title" to "Trip", "start" to "2025-11-10", "end" to "2025-11-11", "all_day" to true)).card.headline.text())
        assertEquals("Add 'Holiday' Mon 10 Nov to Personal",
            prepare(rig, "phone_calendar_create", obj("title" to "Holiday", "start" to "2025-11-10", "all_day" to true)).card.headline.text())
        // A reminder's own length is the phone's, not something Hermes sets, so its card names the time only.
        assertEquals("Remind you: 'Pills' Tue 4 Nov 3:00 PM; In calendar: Personal; Alert at the start", prepare(rig, "phone_reminder_create", obj("title" to "Pills", "at" to start)).card.describe())
    }

    @Test fun changingATimedEventToAllDayShowsDatesNotTimes() {
        val rig = ready()
        rig.calendar.events[5] = EventInfo(5, 1, "Dentist", millis(start), millis("2025-11-04T16:00:00Z"), false, "", "", emptyList())
        val card = prepare(rig, "phone_calendar_update", obj("id" to "5", "all_day" to true, "start" to "2025-11-04", "end" to "2025-11-06")).card
        assertEquals("Change 'Dentist'; When: Tue 4 Nov – Thu 6 Nov", card.describe())
    }

    @Test fun anOddStoredEventWithNoLengthStillShowsItsStart() {
        val rig = ready()
        rig.calendar.events[7] = EventInfo(7, 1, "Odd", millis(start), millis(start), false, "", "", emptyList())
        assertEquals("Delete 'Odd' Tue 4 Nov 3:00 PM", prepare(rig, "phone_calendar_delete", obj("id" to "7")).card.describe())
    }
}
