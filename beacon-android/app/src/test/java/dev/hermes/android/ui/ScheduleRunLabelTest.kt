package dev.hermes.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Run now button says which action is running on its card, not always "Running...". */
class ScheduleRunLabelTest {
    @Test fun runNowShowsTheActionThatIsInFlight() {
        assertEquals("Run now", scheduleRunLabel(ScheduleBusy.None))
        assertEquals("Running…", scheduleRunLabel(ScheduleBusy.Run))
        assertEquals("a pause in flight is not a run", "Pausing…", scheduleRunLabel(ScheduleBusy.Pause))
        assertEquals("a resume in flight is not a run", "Resuming…", scheduleRunLabel(ScheduleBusy.Resume))
    }
}
