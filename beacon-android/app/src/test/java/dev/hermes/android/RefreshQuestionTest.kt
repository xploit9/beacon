package dev.hermes.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Refresh question" must not drop the question before the reload has brought a snapshot, and must ask Hermes again afterwards. Synthetic text only. */
class RefreshQuestionTest {
    private val asked = ChatState(sessionId = "chat-a", clarification = JSONObject().put("id", "q1").put("question", "Which city?"))

    @Test fun aFailedReloadKeepsTheQuestion() {
        // selectSession sets the chat from this state before any read; if the read then fails, this state is what stays on screen.
        val whileReloading = chatOnSelection(asked, "chat-a", "", "")
        assertNotNull("the question is still there while the reload runs or after it fails", whileReloading.clarification)
        assertEquals("q1", whileReloading.clarification?.optString("id"))
    }

    @Test fun refreshingAnotherChatStillStartsWithoutTheOldChatsQuestion() {
        assertNull(chatOnSelection(asked, "chat-b", "", "").clarification)
    }

    @Test fun anOrdinaryReselectionKeepsTheQuestionToo() {
        assertNotNull(chatOnSelection(asked, "chat-a", "", "").clarification)
    }

    @Test fun afterASuccessfulReadHermesIsAskedAgainWhetherOrNotAStreamIsAttached() {
        assertTrue("the refresh button asks even with no stream", asksPromptsAfterRead(dropQuestion = true, streamId = ""))
        assertTrue(asksPromptsAfterRead(dropQuestion = true, streamId = "runtime-chat-a"))
        assertTrue("an attached stream asks as before", asksPromptsAfterRead(dropQuestion = false, streamId = "runtime-chat-a"))
        assertFalse("an ordinary reload with no stream does not", asksPromptsAfterRead(dropQuestion = false, streamId = ""))
    }
}
