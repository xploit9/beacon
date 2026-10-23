package dev.hermes.android

import org.junit.Assert.assertEquals
import org.junit.Test

class BeaconViewModelNoticeTest {
    @Test fun stoppingNoticeClearsWhenTheTurnEndsAndLeavesOtherNoticesAlone() {
        assertEquals("", stoppingNoticeAfterEvent("Stopping the current turn", "done"))
        assertEquals("", stoppingNoticeAfterEvent("Stopping the current turn", "state_saved"))
        assertEquals("", stoppingNoticeAfterEvent("Stopping the current turn", "cancel"))
        assertEquals("Stopping the current turn", stoppingNoticeAfterEvent("Stopping the current turn", "token"))
        assertEquals("File uploaded", stoppingNoticeAfterEvent("File uploaded", "done"))
    }
}
