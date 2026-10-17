package dev.hermes.android.ui

import org.junit.Assert.*
import org.junit.Test

class ConversationPositionTest {
    @Test fun newHistoryMeasuresAtTailInsteadOfFlashingTheFirstMessage() {
        assertEquals(Int.MAX_VALUE to 0, initialConversationPosition(listOf("a", "b"), "", 0, 0))
    }
    @Test fun savedDurableAnchorSurvivesInsertedMessages() {
        assertEquals(2 to 42, initialConversationPosition(listOf("new", "a", "b"), "b", 1, 42))
    }
    @Test fun removedAnchorFallsBackWithinTheCurrentHistory() {
        assertEquals(1 to 0, initialConversationPosition(listOf("a", "b"), "removed", 99, -10))
        assertEquals(0 to 0, initialConversationPosition(emptyList(), "a", 2, 40))
    }
}
