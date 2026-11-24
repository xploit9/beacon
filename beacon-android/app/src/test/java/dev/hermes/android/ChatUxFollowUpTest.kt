package dev.hermes.android

import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.copy
import dev.hermes.android.core.json
import dev.hermes.android.core.LiveAssistantBoundary
import dev.hermes.android.core.string
import dev.hermes.android.ui.liveStatusLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** H1b, from the 1.2.3 UX pass: chat notices, the steer shown twice on a cold return, and the line while Hermes waits for the owner. Synthetic text only. */
class ChatUxFollowUpTest {
    // P1-2: a chat's notice is the chat's; it goes when the owner leaves for another page, and stays while they stay.
    @Test fun aChatNoticeEndsWhenTheOwnerLeavesTheChat() {
        assertTrue(leavesChat("Chat", "More"))
        assertTrue(leavesChat("Beacon", "Tasks"))
        assertTrue(leavesChat("Chat", "Beacon"))
        assertFalse(leavesChat("Chat", "Chat"))
        assertFalse("not a chat's notice", leavesChat("More", "Tasks"))
        assertTrue("Open a chat first." in SHORT_NOTICES)
    }

    // P1-3: the history that brings a steer's saved row pairs the steer in the same update, so it is never drawn twice.
    @Test fun aSteerTakesItsSavedRowInTheSameRender() {
        val steer = QueuedMessage("Make it rhyme", id = "steer-1", createdAt = 1, state = "accepted", kind = "steer")
        val rows = listOf(
            json("row_id" to 40L, "role" to "user", "content" to "Write a poem"),
            json("row_id" to 41L, "role" to "assistant", "content" to "", "tool_calls" to org.json.JSONArray().put(json("id" to "call-1"))),
            json("row_id" to 42L, "role" to "tool", "content" to "ok", "tool_call_id" to "call-1"),
            json("row_id" to 43L, "role" to "user", "display_kind" to "steer", "content" to "[OUT-OF-BAND USER MESSAGE note]\nMake it rhyme\n[/OUT-OF-BAND USER MESSAGE]"))
        val paired = steersPairedOnRender(listOf(steer), rows).single()
        assertEquals("sent" to "43", paired.state to paired.rowId)
        val shown = chatTimelineMessages(rows.mapIndexed { i, row -> Message.from(row, i, "chat") }, listOf(paired)).map { it.id }
        assertFalse("the copy is not drawn beside its row", "queued:steer-1" in shown)
        val none = listOf(steer)
        assertSame("nothing to pair leaves the list as it is", none, steersPairedOnRender(none, rows.take(3)))
    }

    // P2-9: while Hermes waits for the owner the line says so, not Working.
    @Test fun theLiveLineSaysWhenHermesWaitsForTheOwner() {
        val asking = json("tool_call_id" to "q-1", "name" to "clarify", "status" to "running")
        assertEquals("Waiting for your answer", liveStatusLine(ChatState(streamId = "r", status = "Working", liveTools = listOf(asking))))
        assertEquals("Waiting for your answer", liveStatusLine(ChatState(streamId = "r", status = "Using Ask you", liveTools = listOf(asking))))
        assertEquals("Waiting for your answer", liveStatusLine(ChatState(streamId = "r", status = "Working", clarification = json("question" to "Red or blue?"))))
        assertEquals("not the card's own title again", "Waiting for your answer", liveStatusLine(ChatState(streamId = "r", status = "Working", approval = json("approval_id" to "a-1"))))
        val answered = asking.copy().put("result", "red")
        assertEquals("Working", liveStatusLine(ChatState(streamId = "r", status = "Working", liveTools = listOf(answered))))
        assertEquals("Working", liveStatusLine(ChatState(streamId = "r")))
        assertEquals("Hermes' own lines stay", TURN_STOPPED, liveStatusLine(ChatState(streamId = "r", status = TURN_STOPPED, liveTools = listOf(asking))))
    }

    // P1-3: the steps Hermes saved before the owner's steer are the running turn's: a replay on a cold return never draws them a second time.
    @Test fun aStepSavedBeforeTheSteerIsNotDrawnAgainInTheLiveReply() {
        val rows = listOf(
            json("row_id" to 40L, "role" to "user", "content" to "Write a poem"),
            json("row_id" to 41L, "role" to "assistant", "content" to "", "tool_calls" to org.json.JSONArray().put(json("id" to "call-1"))),
            json("row_id" to 42L, "role" to "tool", "content" to "ok", "tool_call_id" to "call-1"),
            json("row_id" to 43L, "role" to "user", "display_kind" to "steer", "content" to "Make it rhyme"))
        assertEquals("the steer continues the turn", listOf("40", "41", "42", "43"), dev.hermes.android.core.runningTurnMessages(rows).map { it.string("row_id") })
        val step = json("tool_call_id" to "call-1", "name" to "execute_code", "status" to "completed")
        val live = ChatState(streamId = "r", liveTools = listOf(step), raw = json("messages" to org.json.JSONArray(rows)))
        assertTrue("a saved step leaves the live reply", withoutSavedLive(live, rows, LiveAssistantBoundary()).liveTools.isEmpty())
        val replayed = liveAfterEvent(live.copy(liveTools = emptyList()), "tool", step, false, LiveAssistantBoundary())
        assertTrue("its replayed event adds nothing", replayed!!.liveTools.isEmpty())
    }

    // P0-1: when Beacon's chat is gone, the owner's unsent words, files and box messages go to the chat that replaces it; what Hermes had does not.
    @Test fun aGoneChatHandsOnOnlyWhatIsStillTheOwnersToSend() {
        val box = QueuedMessage("Box words", state = "settled", outcome = "cancelled", id = "box", createdAt = 1)
        val waiting = QueuedMessage("Waiting words", state = "queued", id = "wait", createdAt = 2)
        val hermesHad = QueuedMessage("Sent words", state = "accepted", id = "had", createdAt = 3)
        val file = json("name" to "Synthetic.pdf")
        val part = dev.hermes.android.core.Draft("Unsent", 6, 6, listOf(file), listOf(box, waiting, hermesHad), anchor = 4).unsentPart()!!
        assertEquals(listOf("box", "wait"), part.queued.map { it.id })
        assertTrue("both wait for the owner's Retry, as Not sent because their chat was deleted", part.queued.all { it.notSent() && it.outcome == CHAT_GONE_OUTCOME })
        assertEquals("Its chat was deleted", notSentReason(part.queued.last()))
        assertEquals("Unsent" to 1, part.text to part.attachments.size)
        assertEquals("the old chat's place is not carried", 0, part.anchor)
        assertEquals(null, dev.hermes.android.core.Draft(queued = listOf(hermesHad)).unsentPart())
    }

    // P0-1 related: the Chat tab never reopens Beacon's own chat.
    @Test fun theChatTabNeverReopensBeaconsChat() {
        assertEquals(null, chatTabSelection("beacon-1", "beacon-1"))
        assertEquals("chat-1", chatTabSelection("chat-1", "beacon-1"))
        assertEquals("chat-1", chatTabSelection("chat-1", null))
        assertEquals(null, chatTabSelection("", null))
    }
}
