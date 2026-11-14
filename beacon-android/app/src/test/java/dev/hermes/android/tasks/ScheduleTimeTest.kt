package dev.hermes.android.tasks

import org.junit.Assert.*
import org.junit.Test

class ScheduleTimeTest {
    @Test fun usesTheProfileClockAndDoesNotInventMissingTimes() {
        assertTrue(scheduleTime("2026-10-14T06:00:00Z", "Asia/Dubai", "No recorded run").endsWith("10:00"))
        assertEquals("No recorded run", scheduleTime("", "Asia/Dubai", "No recorded run"))
        assertEquals("Time unavailable", scheduleTime("invalid", "Asia/Dubai", "No recorded run"))
        // Without the plugin's zone, Hermes' own offset on the time gives the same wall clock.
        assertTrue(scheduleTime("2026-10-14T10:00:00+04:00", "", "No recorded run").endsWith("10:00"))
    }

    // The platform's own pattern for one day-and-clock skeleton, as a device in Arabic answers it: the locale's comma, not an English ", ".
    private val arabic = java.util.Locale.forLanguageTag("ar")
    private val platform = { _: java.util.Locale, skeleton: String ->
        mapOf("yMMMdhm" to "d MMM y، h:mm a", "MMMdhm" to "d MMM، h:mm a", "hm" to "h:mm a")[skeleton]
    }

    @Test fun aLastRunReadsWithTheLocalesOwnJoinerNotAnEnglishComma() {
        val text = scheduleTime("2026-11-12T12:01:00Z", "UTC", "No recorded run", is24Hour = false, locale = arabic, best = platform)
        assertTrue(text, text.contains("،"))
        assertFalse("no fixed English \", \" between the date and the time: $text", text.contains(", "))
    }

    @Test fun aFarNextRunReadsWithTheLocalesOwnJoinerToo() {
        val now = java.time.Instant.parse("2026-11-01T00:00:00Z")
        val text = scheduleNextRun("2026-11-20T09:00:00Z", "", now = now, zone = java.time.ZoneOffset.UTC, is24Hour = false, locale = arabic, best = platform)
        assertTrue(text, text.contains("،"))
        assertFalse(text, text.contains(", "))
    }
}
