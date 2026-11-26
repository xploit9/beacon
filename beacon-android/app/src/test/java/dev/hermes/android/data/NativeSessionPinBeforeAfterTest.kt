package dev.hermes.android.data

import dev.hermes.android.ChatState
import dev.hermes.android.core.json
import dev.hermes.android.ui.openChatPinned
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner-visible half of the 1.2.4 pin fix: a real reason, and no "pin" while unpinning. */
class NativeSessionPinBeforeAfterTest {
    @Test fun aPinRefusalKeepsHermesOwnReason() {
        assertEquals("Hermes didn't allow that.",
            conversationActionFailure("/api/session/pin", ApiFailure(4001, "permission_denied")).message)
    }

    @Test fun thePendingLineDoesNotSayPinWhileTheActionMayBeUnpin() {
        assertEquals("Updating…", conversationPendingLabel("/api/session/pin"))
    }

    @Test fun aLegacyNumericPinnedRowReadsAsPinned() {
        val row = json("session_id" to "chat-1", "profile" to "default", "pinned" to 1)
        assertTrue(openChatPinned(listOf(row), ChatState(sessionId = "chat-1"), "default"))
    }
}
