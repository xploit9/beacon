package dev.hermes.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep connected (a foreground service, a quiet notification, battery) is what the owner sees on the last setup step. On a first setup the switch starts on only when
 * notifications are allowed (a service that cannot alert anyone helps nobody), off otherwise; the owner's own move wins; finishing stores exactly what it shows; and
 * nothing else turns it on.
 */
class SetupKeepConnectedRuleTest {
    private fun shown(allowed: Boolean, moved: Boolean? = null, stored: Boolean = false, firstSetup: Boolean = true) =
        keepConnectedSwitch(moved = moved, stored = stored, firstSetup = firstSetup, notificationsAllowed = allowed)

    @Test fun aFirstSetupWithNotificationsAllowedShowsItOnAndStoresWhatIsShown() {
        assertTrue(shown(allowed = true))
        assertEquals("Open chat stores exactly what the switch shows", true, keepConnectedChangeOnFinish(choice = shown(allowed = true), stored = false))
    }

    @Test fun aFirstSetupWithNotificationsDeniedOrSkippedShowsItOffAndStoresNothing() {
        // Round 2 finding F9: it used to read on here, running a service that cannot alert anyone.
        assertFalse(shown(allowed = false))
        assertNull(keepConnectedChangeOnFinish(choice = shown(allowed = false), stored = false))
    }

    @Test fun aMovedSwitchWinsOverTheStartingValue() {
        assertFalse("Allowed, but the owner turned it off", shown(allowed = true, moved = false))
        assertNull("...so nothing is stored", keepConnectedChangeOnFinish(choice = shown(allowed = true, moved = false), stored = false))
        assertTrue("Denied, but the owner turned it on (the step then says what is missing)", shown(allowed = false, moved = true))
        assertEquals(true, keepConnectedChangeOnFinish(choice = shown(allowed = false, moved = true), stored = false))
    }

    @Test fun aSetupThatIsReopenedOrAlreadyOnShowsTheOwnersEarlierChoice() {
        assertFalse("The owner's later change in Settings stays theirs", shown(allowed = true, firstSetup = false, stored = false))
        assertTrue(shown(allowed = false, firstSetup = false, stored = true))
        assertTrue("An unfinished setup keeps an earlier explicit choice", shown(allowed = false, firstSetup = true, stored = true))
    }

    @Test fun finishingWritesTheStoredStateOnlyWhenTheSwitchDiffersFromIt() {
        assertNull("A reopened setup does not restart a service that is already on", keepConnectedChangeOnFinish(choice = true, stored = true))
        assertNull(keepConnectedChangeOnFinish(choice = false, stored = false))
        assertEquals(true, keepConnectedChangeOnFinish(choice = true, stored = false))
        assertEquals(false, keepConnectedChangeOnFinish(choice = false, stored = true))
    }

    @Test fun finishingWithoutAChoiceLeavesTheStoredStateAlone() {
        assertNull(keepConnectedChangeOnFinish(choice = null, stored = false))
        assertNull(keepConnectedChangeOnFinish(choice = null, stored = true))
    }
}
