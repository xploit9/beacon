package dev.hermes.android

import dev.hermes.android.core.json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R10 Should 2: a saved live frame belongs to the runtime id, which is the same for every turn of that runtime. Its `terminal` flag ends the attach only for the turn this chat
 * follows: the chat has a submission of its own and the frame names it. A frame an earlier errored or interrupted turn left behind must not hide a newer turn.
 */
class TerminalFrameTurnTest {
    private fun frame(terminal: Boolean, submission: String) = json("cursor" to "7", "text" to "", "terminal" to terminal, "submission_id" to submission)

    @Test fun aTerminalFrameEndsTheAttachOnlyForTheTurnItNames() {
        assertTrue(frameEndedTurn(frame(true, "turn-1"), "turn-1"))
        // Another turn's frame, or a chat with no submission of its own (a cold open, or opened from History): never.
        assertFalse(frameEndedTurn(frame(true, "turn-1"), "turn-2"))
        assertFalse(frameEndedTurn(frame(true, "turn-1"), ""))
        assertFalse(frameEndedTurn(frame(true, ""), ""))
    }

    /** R11 Should 1: the chat's own failed turn leaves a frame that names the chat's own id, so the id alone proves nothing; Hermes' answer does, and an unreadable one never ends the turn. */
    @Test fun onlyAnIdleTurnMakesASavedTerminalFrameTheEndOfTheTurnBeingFollowed() {
        assertEquals(SavedTerminalFrame.Ended, savedTerminalFrame("idle"))
        assertEquals(SavedTerminalFrame.NewerTurn, savedTerminalFrame("working"))
        assertEquals(SavedTerminalFrame.NewerTurn, savedTerminalFrame("waiting"))
        assertEquals("Not readable: follow the turn", SavedTerminalFrame.Unknown, savedTerminalFrame(null))
        assertEquals("Anything Hermes does not name as idle", SavedTerminalFrame.Unknown, savedTerminalFrame("unknown"))
        assertEquals(SavedTerminalFrame.Unknown, savedTerminalFrame(""))
    }

    /** R12 Should 1: when Hermes reports a newer turn, nothing of the failed turn's frame is put back on screen but its cursor; any other verdict restores the frame as it is. */
    @Test fun aNewerTurnKeepsOnlyTheCursorOfTheFailedTurnsFrame() {
        val failed = json("cursor" to "7", "text" to "Let me look up the fore", "reasoning" to "Checking the weather", "tools" to JSONArray().put(json("id" to "tool-1")),
            "error" to "Interrupted", "terminal" to true, "submission_id" to "turn-1", "boundary_closed" to true, "boundary_tool_ids" to JSONArray().put("tool-1"))
        val kept = restoredFrame(failed, SavedTerminalFrame.NewerTurn)!!
        assertEquals("7", kept.getString("cursor"))
        assertEquals("Only the cursor is kept", setOf("cursor"), kept.keys().asSequence().toSet())
        for (verdict in listOf(SavedTerminalFrame.Ended, SavedTerminalFrame.Unknown, null)) assertSame("$verdict restores the frame as it is", failed, restoredFrame(failed, verdict))
        assertNull(restoredFrame(null, SavedTerminalFrame.NewerTurn))
        assertNull(restoredFrame(null, null))
    }

    @Test fun aFrameThatIsNotTerminalOrIsMissingEndsNothing() {
        assertFalse(frameEndedTurn(frame(false, "turn-1"), "turn-1"))
        assertFalse(frameEndedTurn(JSONObject(), "turn-1"))
        assertFalse(frameEndedTurn(null, "turn-1"))
    }
}
