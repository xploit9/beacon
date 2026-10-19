package dev.hermes.android

import org.junit.Assert.assertEquals
import org.junit.Test

class LaneR1RegressionTest {
    @Test fun transcriptHidesOnlySystemSteeringMarkers() {
        val messages = listOf(
            Message("steering", "system", "  [System: The active model changed]"),
            Message("system-note", "system", "A normal system note"),
            Message("steering-user-row", "user", "[System: The active model changed to model-x]"),
            Message("normal-user", "user", "A normal user message"),
            Message("assistant", "assistant", "A reply"),
        )

        assertEquals(listOf("system-note", "normal-user", "assistant"), visibleTranscriptMessages(messages).map { it.id })
    }
}
