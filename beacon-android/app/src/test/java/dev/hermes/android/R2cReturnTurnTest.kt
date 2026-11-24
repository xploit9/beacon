package dev.hermes.android

import dev.hermes.android.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * R2C (owner, 22 Nov): coming back to Beacon must show the open chat's true turn state at once. Measured on the 1.2.3 candidate: a screen-off return
 * flipped a working turn to idle 1.9 s in (the passive history read ended it), and a return after a process kill painted the chat idle for more than
 * 6.9 s (the turn was not part of the saved chat). Synthetic chats only.
 */
class R2cReturnTurnTest {
    private val ask = json("role" to "user", "content" to "Write a story about a lighthouse keeper.", "row_id" to 101)
    private val step = json("role" to "assistant", "content" to "", "row_id" to 102,
        "tool_calls" to JSONArray(listOf(json("id" to "call-1", "function" to json("name" to "terminal")))))
    private val stepResult = json("role" to "tool", "content" to "ok", "row_id" to 103, "tool_call_id" to "call-1")
    private val reply = json("role" to "assistant", "content" to "The boat came out of Dingle at eleven.", "row_id" to 104)

    /** The chat as it was when the owner left: a tool step running, Stop on screen. */
    private fun working() = ChatState(sessionId = "s", streamId = "runtime-s", status = "Working",
        liveTools = listOf(json("tid" to "call-1", "name" to "terminal", "status" to "running")),
        raw = json("messages" to JSONArray(listOf(ask)), "_display_submission_id" to "sub-1"),
        messages = listOf(Message.from(ask, 0, "s")))

    /** The read a return makes (Beacon's native history names no running stream), as the stream's state_saved projection carries it. */
    private fun passiveRead(chat: ChatState, rows: List<JSONObject>, boundary: LiveAssistantBoundary = LiveAssistantBoundary(closed = true, toolIds = setOf("call-1")), names: Any? = null): ChatState {
        val session = json("session_id" to "s", "messages" to JSONArray(rows))
        if (names != null) session.put("active_stream_id", names)
        return liveAfterEvent(chat, "state_saved", json("session" to session), boundary.closed, boundary)!!
    }

    @Test fun aReadOnReturnDoesNotEndATurnHermesStillRuns() {
        // Hermes saved the running step (the history stops inside the turn): before R2C this read blanked the stream and the chat drew idle.
        val back = passiveRead(working(), listOf(ask, step))
        assertEquals("The turn is still shown as running", "runtime-s", back.streamId)
        assertEquals("Working", back.status)
        assertTrue("The saved step is not drawn twice", back.liveTools.isEmpty())
    }

    @Test fun aReadThatHoldsTheTurnsEndStillEndsIt() {
        val back = passiveRead(working(), listOf(ask, step, stepResult, reply))
        assertEquals("History holds the reply: nothing is left running", "", back.streamId)
        assertEquals("", back.status)
    }

    @Test fun aReadThatNamesNoStreamOutrightStillEndsIt() {
        // A snapshot that says outright that nothing runs (active_stream_id: null) is Hermes' own word, not a passive read.
        val back = passiveRead(working(), listOf(ask, step), names = JSONObject.NULL)
        assertEquals("", back.streamId)
    }

    @Test fun theRuleOnlyKeepsAShownTurnInsideAnOpenTurn() {
        assertTrue(passiveReadKeepsTurn("runtime-s", namesStream = false, rows = listOf(ask)))
        assertTrue(passiveReadKeepsTurn("runtime-s", namesStream = false, rows = listOf(ask, step)))
        assertTrue(passiveReadKeepsTurn("runtime-s", namesStream = false, rows = listOf(ask, step, stepResult)))
        assertFalse("ended on a reply", passiveReadKeepsTurn("runtime-s", namesStream = false, rows = listOf(ask, reply)))
        assertFalse("Hermes named the stream state", passiveReadKeepsTurn("runtime-s", namesStream = true, rows = listOf(ask, step)))
        assertFalse("nothing shown", passiveReadKeepsTurn("", namesStream = false, rows = listOf(ask, step)))
    }

    // Process kill: the turn record and the live frame are what survive; the cold start paints them back.

    private val frame = json("cursor" to "41", "text" to "The boat came out of Dingle", "reasoning" to "", "tools" to JSONArray(), "sealed" to JSONArray(),
        "sealed_at" to JSONArray(), "ordinal" to 0, "base" to 0, "base_prompt" to "", "error" to "", "terminal" to false, "wait" to "", "submission_id" to "sub-1")

    /** What a cold start draws from the saved chat before R2C: its rows, no stream. */
    private fun coldSaved() = ChatState(sessionId = "s", status = "Saved copy · reconnecting",
        raw = json("messages" to JSONArray(listOf(ask)), "_display_submission_id" to "sub-1"), messages = listOf(Message.from(ask, 0, "s")))

    @Test fun theTurnSurvivesAProcessKill() {
        val shown = ChatState(sessionId = "s", streamId = "runtime-s", status = "Responding", liveText = "The boat came out of Dingle",
            raw = json("messages" to JSONArray(listOf(ask)), "_display_submission_id" to "sub-1"))
        // The record the view model keeps while the turn is shown.
        val record = JSONObject(requireNotNull(liveTurnRecordChange("s" to "", shown, connected = true)))
        assertEquals("runtime-s", record.string("stream_id"))
        assertEquals("sub-1", record.string("submission_id"))
        // The process dies; the cold start paints the saved chat with the record and the stream's frame.
        val painted = requireNotNull(paintedTurn(coldSaved(), record, frame))
        assertEquals("Running on the first frame", "runtime-s", painted.streamId)
        assertEquals("Working", painted.status)
        assertEquals("With the words so far", "The boat came out of Dingle", painted.liveText)
        assertEquals("The saved rows stay", 1, painted.messages.size)
    }

    @Test fun aTurnWithoutAFrameStillPaintsRunning() {
        val painted = requireNotNull(paintedTurn(coldSaved(), json("stream_id" to "runtime-s", "submission_id" to "sub-1"), null))
        assertEquals("runtime-s", painted.streamId)
        assertEquals("Working", painted.status)
        assertEquals("", painted.liveText)
    }

    @Test fun nothingIsPaintedForAnEndedOrForeignTurn() {
        val record = json("stream_id" to "runtime-s", "submission_id" to "sub-1")
        assertNull("no record", paintedTurn(coldSaved(), null, frame))
        assertNull("the frame says this turn ended", paintedTurn(coldSaved(), record, frame.copy().put("terminal", true)))
        assertNull("another send owns the chat now", paintedTurn(coldSaved(), json("stream_id" to "runtime-s", "submission_id" to "sub-0"), frame))
        assertNull("not a stream id", paintedTurn(coldSaved(), json("stream_id" to "../x y", "submission_id" to "sub-1"), frame))
        // A frame an earlier turn of the same runtime left is not this turn's words.
        val painted = requireNotNull(paintedTurn(coldSaved(), record, frame.copy().put("submission_id", "sub-0").put("terminal", true)))
        assertEquals("", painted.liveText)
        assertEquals("runtime-s", painted.streamId)
    }

    @Test fun theRecordFollowsTheStreamNotTheChatSwitch() {
        val a = ChatState(sessionId = "a", streamId = "runtime-a", raw = json("_display_submission_id" to "sub-a"))
        assertNotNull("bound: written", liveTurnRecordChange("a" to "", a, connected = true)?.takeIf { it.isNotBlank() })
        assertEquals("released: deleted", "", liveTurnRecordChange("a" to "runtime-a", a.copy(streamId = ""), connected = true))
        assertNull("a cold restore opens its chat before it is connected: the record it paints stays", liveTurnRecordChange("" to "", ChatState(sessionId = "a"), connected = false))
        assertEquals("a chat opened idle while connected drops a turn that ended unseen", "", liveTurnRecordChange("a" to "runtime-a", ChatState(sessionId = "b"), connected = true))
        assertNull("nothing changes for a chat that stays idle", liveTurnRecordChange("b" to "", ChatState(sessionId = "b", status = "x"), connected = true))
        assertNull("no chat", liveTurnRecordChange("a" to "runtime-a", ChatState(), connected = true))
    }

    @Test fun aStalePaintedTurnSettlesFromTheSavedChatWithoutADuplicate() {
        // Killed mid-turn; the turn ended while Beacon was gone. The cold start paints the words so far, Hermes then answers idle and the settle reads the
        // saved chat, which holds the whole reply (proved by the turn's final row).
        val painted = requireNotNull(paintedTurn(coldSaved(), json("stream_id" to "runtime-s", "submission_id" to "sub-1"), frame))
        val saved = listOf(ask, reply)
        assertFalse("The saved chat ends on the reply: the read may end the turn", passiveReadKeepsTurn(painted.streamId, false, saved))
        val live = settleLive(painted, saved, null, LiveAssistantBoundary.from(frame), finalRow = "104")
        assertTrue(live.streamEnded)
        assertEquals("The painted words go: the saved reply shows them", "", live.text)
        val settled = releaseTerminalChat(painted.copy(liveText = live.text, messages = saved.mapIndexed { i, m -> Message.from(m, i, "s") },
            raw = painted.raw.copy().put("messages", JSONArray(saved))), "runtime-s", replyConfirmed = true, terminalStatus = "", submission = "sub-1")
        assertEquals("", settled.streamId)
        assertEquals("", settled.liveText)
        assertEquals("The reply shows once", 1, settled.messages.count { it.role == "assistant" && it.text.contains("Dingle") })
    }

    /** A/B on 22 Nov: the reopen asked Hermes for cards (two slow reads) before following a painted turn, so its words came back seconds later than the candidate's. */
    @Test fun aPaintedTurnIsFollowedBeforeAnyPromptRead() {
        assertFalse("a painted turn nothing follows yet waits for its own look", asksPromptsAfterRead(dropQuestion = false, streamId = "runtime-s", followed = false))
        assertTrue("a followed stream asks as before", asksPromptsAfterRead(dropQuestion = false, streamId = "runtime-s", followed = true))
        assertTrue("Refresh question still asks", asksPromptsAfterRead(dropQuestion = true, streamId = "runtime-s", followed = false))
    }
}
