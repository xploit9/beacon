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
}
