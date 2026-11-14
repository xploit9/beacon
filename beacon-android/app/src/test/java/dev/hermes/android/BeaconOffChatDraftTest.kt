package dev.hermes.android

import dev.hermes.android.core.Draft
import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.Scope
import dev.hermes.android.data.PROFILE_PLUGIN_OFF_ERROR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R10F-A round 2: a chat the owner opened where Hermes does not serve Beacon stays that chat, and what is typed while the page says so belongs to that chat. The composer is not
 * cleared on that page, what is typed there is saved with the chat, and the chat's queued messages stay with its draft, so what is in the composer when Turn on reopens the
 * chat is the chat's own draft, nothing lost.
 */
class BeaconOffChatDraftTest {
    private val owner = Scope("https://native.example", "owner", "coder")
    private val off = ChatState(sessionId = "chat-x", status = PROFILE_PLUGIN_OFF_ERROR)

    @Test fun whatIsTypedOnTheOffChatPageIsSavedWithThatChat() {
        assertEquals(owner.session("chat-x"), retainedDraftScope(off, null, owner))
    }

    @Test fun noOtherPageOwnsADraftThisWay() {
        // The first-chat card has no conversation id (it keeps the owner's initial draft); an open chat has its own source scope; no other status is this page.
        assertNull(retainedDraftScope(ChatState(sessionId = "", status = PROFILE_PLUGIN_OFF_ERROR), null, owner))
        assertNull(retainedDraftScope(off, owner.session("chat-x"), owner))
        assertNull(retainedDraftScope(off.copy(status = ""), null, owner))
        assertNull(retainedDraftScope(off, null, null))
    }

    @Test fun theOffPageKeepsTheQueuedMessagesAndPlaceOfTheDraftItRetains() {
        val queued = QueuedMessage("later")
        val draft = Draft("words", 2, 5, emptyList(), listOf(queued), anchor = 3, anchorOffset = 4, anchorId = "row-9")
        val state = retainedChatState("chat-x", PROFILE_PLUGIN_OFF_ERROR, draft)
        assertEquals("chat-x", state.sessionId)
        assertEquals(PROFILE_PLUGIN_OFF_ERROR, state.status)
        assertEquals(listOf(queued), state.queued)
        assertEquals(Triple(3, 4, "row-9"), Triple(state.anchor, state.anchorOffset, state.anchorId))
    }
}
