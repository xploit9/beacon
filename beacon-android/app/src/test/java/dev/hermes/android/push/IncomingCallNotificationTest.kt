package dev.hermes.android.push

import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallNotificationTest {
    @Test fun normalRingUsesAnAlertingCallStyleCardUntilItsDeadline() {
        val card = incomingCallCardOptions(45_000, silent = false)

        assertEquals(NotificationCompat.CATEGORY_CALL, card.category)
        assertTrue(card.callStyle)
        assertTrue(card.fullScreenIntent)
        assertFalse("setSilent(true) groups the card and suppresses interruption", card.silent)
        assertTrue(card.insistent)
        assertTrue(card.onlyAlertOnce)
        assertEquals(45_000L, card.timeoutAfterMillis)
    }

    @Test fun suppressedRingCardHasNoPopupOrInsistentAlert() {
        val card = incomingCallCardOptions(45_000, silent = true)

        assertEquals(NotificationCompat.CATEGORY_CALL, card.category)
        assertTrue(card.callStyle)
        assertFalse(card.fullScreenIntent)
        assertTrue(card.silent)
        assertFalse(card.insistent)
        assertTrue(card.onlyAlertOnce)
        assertEquals(45_000L, card.timeoutAfterMillis)
    }
}
