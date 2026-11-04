package dev.hermes.android.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner, 4 Nov 2026: "when I hold click, sometimes it doesnt connect which leaves me to hold click again". A side-button conversation
 * that fails before it gets going is tried once more by itself; a call that already listened or spoke is a dropped call and is not.
 */
class AssistRetryOnceTest {
    @Test fun aStartThatFailsBeforeACallExistsGetsOneMoreTry() {
        val book = AssistLiveBook()
        book.shown(); book.requested()
        assertTrue(book.retryOnce(running = false))
        book.requested(retry = true)
        assertFalse("only one automatic try per ask", book.retryOnce(running = false))
    }

    @Test fun aCallStartedHereThatFailsBeforeItListenedGetsOneMoreTry() {
        val book = AssistLiveBook()
        book.shown(); book.requested()
        assertEquals(LiveActive.Adopted, book.callActive(startedAt = 1_000))
        assertTrue(book.retryOnce(running = true))
        book.requested(retry = true)
        assertEquals(LiveActive.Adopted, book.callActive(startedAt = 2_000))
        assertFalse("the second failure is shown", book.retryOnce(running = true))
    }

    @Test fun aCallThatAlreadyListenedOrSpokeIsNotRetried() {
        val book = AssistLiveBook()
        book.shown(); book.requested()
        book.callActive(startedAt = 1_000)
        book.heard()
        assertFalse(book.retryOnce(running = true))
    }

    @Test fun aNewHoldGetsItsOwnTry() {
        val book = AssistLiveBook()
        book.shown(); book.requested()
        assertTrue(book.retryOnce(running = false))
        book.end()
        book.shown(); book.requested()
        assertTrue(book.retryOnce(running = false))
    }

    @Test fun aCallTheOverlayDidNotStartIsNeverRetried() {
        val book = AssistLiveBook()
        book.shown()
        assertEquals(LiveActive.Other, book.callActive(startedAt = 1_000))
        assertFalse(book.retryOnce(running = true))
        assertFalse("nothing was asked for", book.retryOnce(running = false))
    }
}
