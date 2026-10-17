package dev.hermes.android.tasks

import org.junit.Assert.*
import org.junit.Test

class ScheduleTimeTest {
    @Test fun usesTheProfileClockAndDoesNotInventMissingTimes() {
        assertTrue(scheduleTime("2026-10-14T06:00:00Z", "Asia/Dubai", "No recorded run").endsWith("10:00"))
        assertEquals("No recorded run", scheduleTime("", "Asia/Dubai", "No recorded run"))
        assertEquals("Time unavailable", scheduleTime("invalid", "Asia/Dubai", "No recorded run"))
        assertEquals("Time unavailable", scheduleTime("2026-10-14T06:00:00Z", "", "No recorded run"))
    }
}
