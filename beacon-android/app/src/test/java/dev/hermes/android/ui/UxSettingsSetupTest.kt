package dev.hermes.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class UxSettingsSetupTest {
    @Test fun quietHoursAreShownTheWayThePhoneWritesATime() {
        assertEquals("22:00", proactiveClockLabel("22:00", "HH:mm", Locale.US))
        assertEquals("10:00 PM", proactiveClockLabel("22:00", "h:mm a", Locale.US))
        assertEquals("7:05 AM", proactiveClockLabel("07:05", "h:mm a", Locale.US))
    }

    @Test fun aValueThatIsNotATimeStaysAsItIs() {
        assertEquals("soon", proactiveClockLabel("soon", "HH:mm", Locale.US))
        assertEquals("25:00", proactiveClockLabel("25:00", "h:mm a", Locale.US))
    }

}
