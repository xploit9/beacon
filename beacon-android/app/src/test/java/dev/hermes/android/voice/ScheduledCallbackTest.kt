package dev.hermes.android.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Owner, 4 Nov 2026: "I can request a call back anytime I want" — a call schedules one from minutes or a time, with or without work. */
class ScheduledCallbackTest {
    private val now = ZonedDateTime.of(2026, 11, 4, 12, 7, 53, 0, ZoneId.of("Asia/Dubai"))
    private fun args(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    @Test fun minutesFromNowBecomeTheDelayThePluginTakes() {
        val body = ScheduledCallback.body(args("minutes" to 1, "reason" to "Check in"), "call-1", "tool-1", now)
        assertEquals(60, body.getInt("delay_seconds"))
        assertEquals("Check in", body.getString("reason"))
        assertFalse(body.has("at"))
        assertTrue(body.getString("request_id").matches(Regex("[a-f0-9]{32}")))
    }

    @Test fun aTimeIsTheNextOneLaterTodayOrTomorrow() {
        assertEquals("2026-11-04T17:30:00+04:00", ScheduledCallback.nextAt("17:30", now))
        assertEquals("2026-11-05T09:00:00+04:00", ScheduledCallback.nextAt("9:00", now))
        assertEquals("2026-11-05T12:07:00+04:00", ScheduledCallback.nextAt("12:07", now))
        assertEquals("2026-11-04T17:00:00+04:00", ScheduledCallback.body(args("at" to "17:00", "reason" to "x"), "c", "t", now).getString("at"))
    }

    @Test fun theSameToolCallIsTheSameRequestAndAnotherIsNot() {
        val first = ScheduledCallback.body(args("minutes" to 5, "reason" to "x"), "call-1", "tool-1", now).getString("request_id")
        assertEquals(first, ScheduledCallback.body(args("minutes" to 5, "reason" to "x"), "call-1", "tool-1", now).getString("request_id"))
        assertFalse(first == ScheduledCallback.body(args("minutes" to 5, "reason" to "x"), "call-1", "tool-2", now).getString("request_id"))
    }

    @Test fun aMissingTimeOrReasonIsHandled() {
        assertThrows(IllegalArgumentException::class.java) { ScheduledCallback.body(args("reason" to "x"), "c", "t", now) }
        assertThrows(IllegalArgumentException::class.java) { ScheduledCallback.nextAt("soon", now) }
        assertEquals("The callback you asked for", ScheduledCallback.body(args("minutes" to 2), "c", "t", now).getString("reason"))
        // The plugin takes up to a year; a delay is never zero.
        assertEquals(60, ScheduledCallback.body(args("minutes" to 0, "reason" to "x"), "c", "t", now).getInt("delay_seconds"))
    }

    @Test fun theCallOffersTheToolAndTheJobCallbackPointsToIt() {
        assertTrue("schedule_callback" in DIRECT_CALL_TOOLS)
        assertTrue(callToolHint("hermes_callback", JSONObject().put("status", "not_sent")).contains("schedule_callback"))
        assertTrue(callToolHint("schedule_callback", JSONObject().put("status", "scheduled")).contains("scheduled_at"))
    }
}
