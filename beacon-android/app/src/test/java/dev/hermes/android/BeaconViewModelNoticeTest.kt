package dev.hermes.android

import org.junit.Assert.assertEquals
import org.junit.Test

class BeaconViewModelNoticeTest {
    @Test fun stoppingNoticeClearsWhenTheTurnEndsAndLeavesOtherNoticesAlone() {
        assertEquals("", stoppingNoticeAfterEvent("Stopping…", "done"))
        assertEquals("", stoppingNoticeAfterEvent("Stopping…", "state_saved"))
        assertEquals("", stoppingNoticeAfterEvent("Stopping…", "cancel"))
        assertEquals("Stopping…", stoppingNoticeAfterEvent("Stopping…", "token"))
        assertEquals("File uploaded", stoppingNoticeAfterEvent("File uploaded", "done"))
    }
}
