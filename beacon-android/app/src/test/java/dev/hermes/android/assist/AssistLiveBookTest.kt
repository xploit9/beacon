package dev.hermes.android.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strix vuln-0010 (low): the overlay's cancel of a conversation that was still starting outlived that start. The Android assistant
 * session survives every hide, so a later call that turned active in it (an answered one, the chat's own) was hung up by the stale
 * flag before the overlay ever saw it. A cancel now belongs to the start it was armed for: the start that is in flight, and the call
 * that start produces, nothing else.
 */
class AssistLiveBookTest {
    @Test fun aCloseWithNoCallStartingNeverHangsUpAnUnrelatedCallLater() {
        val book = AssistLiveBook()
        book.shown(); book.requested()
        // Back, the orb or a swipe down while the ask still waited for the chat: no call exists, so nothing is left to cancel.
        assertTrue(book.end())
        assertEquals(LiveActive.Other, book.callActive(startedAt = 9_000))
        assertEquals(LiveActive.Other, book.callActive(startedAt = 9_500))
    }

    @Test fun aCloseWhileTheCallIsStartingEndsThatCallAndOnlyThat() {
        val book = AssistLiveBook()
        book.shown(); book.requested(); book.launched(at = 1_000)
        assertTrue(book.end())
        assertEquals("the call this start produced", LiveActive.HangUp, book.callActive(startedAt = 1_200))
        assertEquals("a later, unrelated call", LiveActive.Other, book.callActive(startedAt = 9_000))
    }

    @Test fun aCallThatWasAlreadyUpBeforeTheStartBeganIsNotTheOneCancelled() {
        val book = AssistLiveBook()
        book.shown(); book.requested(); book.launched(at = 1_000)
        book.end()
        assertEquals(LiveActive.Other, book.callActive(startedAt = 400))
        assertEquals("the cancel still waits for its own call", LiveActive.HangUp, book.callActive(startedAt = 1_100))
    }

    @Test fun aStartThatReturnedAfterTheCloseEndsItsCallOnceEvenWhenNoStateWasSeen() {
        val book = AssistLiveBook()
        book.shown(); book.requested(); book.launched(at = 1_000)
        book.end()
        assertTrue(book.settled(returned = true, active = true, startedAt = 1_200))
        assertFalse("only once", book.settled(returned = true, active = true, startedAt = 1_200))
        assertEquals(LiveActive.Other, book.callActive(startedAt = 9_000))
    }

    @Test fun aStartThatFailedLeavesNoCancelAndNeverEndsAnUnrelatedCall() {
        val book = AssistLiveBook()
        book.shown(); book.requested(); book.launched(at = 1_000)
        book.end()
        // The start threw (or was refused because another call was on): the call that is up is not this start's.
        assertFalse(book.settled(returned = false, active = true, startedAt = 5_000))
        assertEquals(LiveActive.Other, book.callActive(startedAt = 9_000))
    }

    @Test fun aStartThatReturnedNoCallOfItsOwnEndsNothing() {
        val book = AssistLiveBook()
        book.shown(); book.requested(); book.launched(at = 1_000)
        book.end()
        // It returned early (cancelled before it began) while an older, unrelated call is up.
        assertFalse(book.settled(returned = true, active = true, startedAt = 400))
        assertFalse(book.settled(returned = true, active = false, startedAt = 0))
    }

    @Test fun aCallAskedForHereIsAdoptedAndClosingTheOverlayEndsIt() {
        val book = AssistLiveBook()
        book.shown(); book.requested(); book.launched(at = 1_000)
        assertEquals(LiveActive.Adopted, book.callActive(startedAt = 1_100))
        assertTrue(book.here); assertFalse(book.wanted)
        assertTrue(book.closing())
        assertFalse("nothing was still starting", book.end())
        assertFalse(book.closing())
        assertEquals(LiveActive.Other, book.callActive(startedAt = 9_000))
    }

    @Test fun aCallTheAppIsOnCarriesOnWhenTheOverlayCloses() {
        val book = AssistLiveBook()
        book.shown()
        assertEquals(LiveActive.Other, book.callActive(startedAt = 100))
        assertFalse(book.closing())
    }
}
