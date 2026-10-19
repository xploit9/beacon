package dev.hermes.android

import dev.hermes.android.core.Draft
import dev.hermes.android.core.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun attachmentBudgetIncludesBase64ExpansionAndCurrentDraft() {
        val draft = Draft(text = "A short message")
        val attachment = json("id" to "id", "name" to "photo.jpg", "mime" to "image/jpeg", "staged_base64" to "")

        assertTrue(attachmentFitsLocalCheckpoint(draft, attachment, 11L * 1024 * 1024))
        assertFalse(attachmentFitsLocalCheckpoint(draft, attachment, 12L * 1024 * 1024))
        assertFalse(attachmentFitsLocalCheckpoint(draft.copy(attachments = listOf(attachment.put("staged_base64", "A".repeat(2_000_000)))), attachment, 11L * 1024 * 1024))
    }
}
