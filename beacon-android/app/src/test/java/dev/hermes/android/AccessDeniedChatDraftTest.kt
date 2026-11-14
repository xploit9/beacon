package dev.hermes.android

import dev.hermes.android.core.Draft
import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.Scope
import dev.hermes.android.data.PROFILE_PLUGIN_OFF_ERROR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R10F-A round 3: a chat whose open was refused (401/403) stays that chat, like the Beacon-off page: what is typed on its page is saved with that chat, the composer is not
 * cleared, and the chat's queued messages stay with its draft, so what the composer holds when the chat opens again is the chat's latest draft, nothing lost.
 */
class AccessDeniedChatDraftTest {
    private val owner = Scope("https://native.example", "owner", "default")
    private val denied = ChatState(sessionId = "chat-x", status = ACCESS_DENIED_RETAINED)

    @Test fun whatIsTypedOnTheAccessDeniedPageIsSavedWithThatChat() {
        assertEquals("Not allowed \u00b7 draft kept", ACCESS_DENIED_RETAINED)
        assertEquals(owner.session("chat-x"), retainedDraftScope(denied, null, owner))
    }

    @Test fun theAccessDeniedPageKeepsTheQueuedMessagesAndPlaceOfTheDraftItRetains() {
        val queued = QueuedMessage("later")
        val draft = Draft("words", 2, 5, emptyList(), listOf(queued), anchor = 3, anchorOffset = 4, anchorId = "row-9")
        val state = retainedChatState("chat-x", ACCESS_DENIED_RETAINED, draft)
        assertEquals("chat-x", state.sessionId)
        assertEquals(ACCESS_DENIED_RETAINED, state.status)
        assertEquals(listOf(queued), state.queued)
        assertEquals(Triple(3, 4, "row-9"), Triple(state.anchor, state.anchorOffset, state.anchorId))
    }

    @Test fun onlyTheBeaconOffAndAccessDeniedPagesOwnADraftThisWay() {
        assertTrue(isRetainedPage(PROFILE_PLUGIN_OFF_ERROR))
        assertTrue(isRetainedPage(ACCESS_DENIED_RETAINED))
        // Every other status belongs to a chat that is still open (it has its own source scope) or to no chat at all.
        for (status in listOf("", "Sending", "Working", "Saved copy \u00b7 couldn't refresh", "Chat unavailable \u00b7 draft kept", "Couldn't load this chat \u00b7 retry")) {
            assertFalse(status, isRetainedPage(status))
            assertNull(status, retainedDraftScope(denied.copy(status = status), null, owner))
        }
        // An open chat has its own source scope, no id is no chat, and without an owner there is nothing to save under.
        assertNull(retainedDraftScope(denied, owner.session("chat-x"), owner))
        assertNull(retainedDraftScope(denied.copy(sessionId = ""), null, owner))
        assertNull(retainedDraftScope(denied, null, null))
    }
}
