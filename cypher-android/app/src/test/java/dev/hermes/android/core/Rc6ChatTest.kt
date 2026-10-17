package dev.hermes.android.core

import org.junit.Assert.*
import org.junit.Test

class Rc6ChatTest {
    @Test fun providerFailureEndsStreamButDoneRemainsNonterminal() {
        assertTrue(SseEvent("apperror", "{}").terminal)
        assertFalse(SseEvent("done", "{}").terminal)
    }
    @Test fun retainedCursorRejectsEveryOlderReplayAndAcceptsNextToken() {
        val cursor = ReplayCursor("side", "side:3")
        for (id in listOf("side:1", "side:2", "side:3")) assertFalse(cursor.accept(SseEvent("token", "{}", id)))
        assertTrue(cursor.accept(SseEvent("token", "{}", "side:4")))
        assertEquals("side:4", cursor.lastId)
    }
}
