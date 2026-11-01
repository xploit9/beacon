package dev.hermes.android.uiux.gaps

import dev.hermes.android.core.json
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.ui.nativeClockPattern
import dev.hermes.android.ui.proactiveCallsUnavailable
import dev.hermes.android.ui.proactiveClockLabel
import dev.hermes.android.ui.proactiveSettingsBody
import dev.hermes.android.ui.proactiveSettingsValid
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** Assistant quiet hours and the call rules: what the clock dialog writes, and how a stored time reads. */
class ProactiveGapsTest {
    private fun body(start: String, end: String) = proactiveSettingsBody(true, "notify", 15, start, end, AppPreferences())

    @Test fun everyTimeTheClockDialogCanWriteIsAValidQuietTime() {
        // ProactiveTimePicker confirms with "%02d:%02d".format(hour, minute); the plugin would refuse anything the form rejects.
        for (hour in 0..23) for (minute in 0..59) {
            val time = "%02d:%02d".format(hour, minute)
            assertTrue("$time must be accepted", proactiveSettingsValid(json("delivery" to "notify", "check_minutes" to 15, "quiet_start" to time, "quiet_end" to time)))
        }
    }

    @Test fun aNewQuietStartAndEndGoIntoTheSavedBodyAndTheOtherSettingsStay() {
        val saved = body("23:30", "06:15")
        assertEquals("23:30", saved.getString("quiet_start"))
        assertEquals("06:15", saved.getString("quiet_end"))
        assertEquals("notify", saved.getString("delivery"))
        assertEquals(15, saved.getInt("check_minutes"))
        assertTrue(saved.getBoolean("enabled"))
    }

    @Test fun aTimeTheClockCannotWriteIsRefusedBeforeAnythingIsSent() {
        for (bad in listOf("24:00", "7:05", "07:5", "07:60", "", "0705", "07-05", "12:00 PM")) {
            val failure = runCatching { body(bad, "07:00") }.exceptionOrNull()
            assertTrue("\"$bad\" as a start must be refused", failure is IllegalArgumentException)
            assertEquals("Choose a valid delivery, check interval, and quiet-hours time.", failure?.message)
            assertTrue("\"$bad\" as an end must be refused", runCatching { body("22:00", bad) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun aStoredTimeReadsTheWayThePhoneWritesTheClock() {
        val h24 = nativeClockPattern(Locale.US, true)
        val h12 = nativeClockPattern(Locale.US, false)
        assertEquals("22:00", proactiveClockLabel("22:00", h24, Locale.US))
        val evening = proactiveClockLabel("22:00", h12, Locale.US)
        assertTrue("12-hour clock reads 10:00 PM, not \"$evening\"", evening.contains("10:00") && evening.contains("PM"))
        val morning = proactiveClockLabel("07:05", h12, Locale.US)
        assertTrue("\"$morning\"", morning.contains("7:05") && morning.contains("AM"))
    }

    @Test fun aTimeThatDoesNotParseStaysAsItIs() {
        assertEquals("not a time", proactiveClockLabel("not a time", "HH:mm", Locale.US))
        assertEquals("", proactiveClockLabel("", "HH:mm", Locale.US))
        assertEquals("25:00", proactiveClockLabel("25:00", "HH:mm", Locale.US))
    }

    @Test fun callsAreUnavailableWhenAnyRuleOrTheDefaultNeedsOneAndCallbacksAreOff() {
        val callRule = json("rules" to JSONArray().put(json("action" to "notify")).put(json("action" to "call")))
        val notifyRules = json("rules" to JSONArray().put(json("action" to "notify")))
        assertTrue(proactiveCallsUnavailable(callRule, "notify", callbacksAvailable = false))
        assertFalse("Only notify rules need no calls", proactiveCallsUnavailable(notifyRules, "notify", callbacksAvailable = false))
        assertTrue("A call delivery needs them even without rules", proactiveCallsUnavailable(null, "call", callbacksAvailable = false))
        assertFalse("Available callbacks always allow calls", proactiveCallsUnavailable(callRule, "call", callbacksAvailable = true))
        assertFalse(proactiveCallsUnavailable(null, "notify", callbacksAvailable = false))
    }
}
