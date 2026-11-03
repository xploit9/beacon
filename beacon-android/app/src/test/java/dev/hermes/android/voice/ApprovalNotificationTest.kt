package dev.hermes.android.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An approval is answered on its card or from its notification, never from neither: the notification stands aside only for a card
 * that is really on screen (the app's dialog or the overlay's card), whatever else is in front.
 */
class ApprovalNotificationTest {
    private fun approval(id: String, busy: Boolean = false) = CallToolApproval(id, "p".repeat(32), "phone_timer_set", "{}", busy = busy)

    @Test fun anApprovalWithNoCardOnScreenGetsTheNotification() {
        val cards = ApprovalCardsOnScreen()
        // The call screen may be started and the overlay shown, but when neither draws this approval's card it still needs the notification.
        assertEquals("a", approvalNeedingNotification(listOf(approval("a")), cards::shown)?.requestId)
    }

    @Test fun theNotificationStepsAsideOnlyForACardReallyOnScreen() {
        val cards = ApprovalCardsOnScreen()
        assertTrue(cards.set("a", true))
        assertNull(approvalNeedingNotification(listOf(approval("a")), cards::shown))
        assertTrue(cards.set("a", false))
        assertEquals("a", approvalNeedingNotification(listOf(approval("a")), cards::shown)?.requestId)
    }

    @Test fun twoCardsOfTheSameApprovalHoldItUntilBothHaveLeft() {
        val cards = ApprovalCardsOnScreen()
        assertTrue("the first card puts it on screen", cards.set("a", true))
        assertFalse("a second card changes nothing", cards.set("a", true))
        assertFalse("one card leaving leaves the other", cards.set("a", false))
        assertTrue(cards.shown("a"))
        assertTrue("the last one leaving takes it off", cards.set("a", false))
        assertFalse(cards.shown("a"))
        // Leaving more often than arriving never goes below nothing.
        assertFalse(cards.set("a", false))
        assertTrue(cards.set("a", true))
        assertTrue(cards.shown("a"))
    }

    @Test fun theCardOnScreenIsTheFirstApprovalSoNothingElseStandsBesideIt() {
        val cards = ApprovalCardsOnScreen()
        val waiting = listOf(approval("a"), approval("b"))
        cards.set("a", true)
        assertNull("a is on screen and b waits behind it", approvalNeedingNotification(waiting, cards::shown))
        // The card of an approval that is not the first one is not what the app shows, so it does not hide the first one's notification.
        cards.set("a", false)
        cards.set("b", true)
        assertEquals("a", approvalNeedingNotification(waiting, cards::shown)?.requestId)
    }

    @Test fun anApprovalBeingAnsweredIsNotAskedAgainButTheNextOneWithoutACardIs() {
        val cards = ApprovalCardsOnScreen()
        val waiting = listOf(approval("a", busy = true), approval("b"))
        assertEquals("b", approvalNeedingNotification(waiting, cards::shown)?.requestId)
        cards.set("a", true)
        assertNull("its card is still up while it is answered", approvalNeedingNotification(waiting, cards::shown))
        assertNull(approvalNeedingNotification(emptyList(), cards::shown))
        assertNull(approvalNeedingNotification(listOf(approval("a", busy = true)), ApprovalCardsOnScreen()::shown))
    }
}
