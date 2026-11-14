package dev.hermes.android

import dev.hermes.android.core.Scope
import dev.hermes.android.core.ScopeSettlement
import dev.hermes.android.ui.ToolOutcome
import dev.hermes.android.ui.toolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G5 pass 2: a chat left while a long tool ran, then opened again from the drawer, showed no Stop and a "No result recorded" tool row for 31 s.
 * Opening is passive (history has no running flag), so the only way the open learns of the turn is the cold-open question to Hermes (session.resume `running`),
 * and that question was skipped because the stream job the chat was left with still counted as following it. Selecting a chat moves the session generation on,
 * which silences every job started before; such a job wakes only after its quiet window (20 s, doubled on each resubscribe).
 */
class ReopenedRunningChatTest {
    private val owner = Scope("https://hermes.example", "owner", "default")
    private val chat = owner.session("session-a")
    private val client = Any()

    /** What selectSession does to the generation: every open (or re-open) moves it on. */
    private var generation = 1

    @Test fun aChatReopenedAfterBeingLeftMidTurnAsksHermesAndShowsStopAtOnce() {
        // Sent here at generation 1: the run marker is written, the stream job follows, Stop shows and the tool row runs.
        val started = ScopeSettlement(chat, client, 0, generation)
        val sending = ChatState(sessionId = "session-a", streamId = "runtime-1", status = "Working")
        assertTrue("the job follows the chat it was started for", streamFollows(true, started, chat, client, 0, generation))
        assertEquals(ToolOutcome.Running, toolOutcome("terminal", "running", false, null, false, streaming = sending.streamId.isNotBlank()))

        // The person opens another chat, then this one again from the drawer.
        generation++
        val other = chatOnSelection(sending, "session-b", "", "")
        generation++
        val reopened = chatOnSelection(other, "session-a", "", "")
        // History is passive: it names no running stream, so the reopened chat shows none, and the saved tool call has no result yet.
        assertTrue(reopened.streamId.isBlank())
        assertEquals(ToolOutcome.NoResult, toolOutcome("terminal", "", false, null, false, streaming = reopened.streamId.isNotBlank()))

        // The job from generation 1 still exists but drops its events: it follows nothing now.
        val follows = streamFollows(true, started, chat, client, 0, generation)
        assertFalse("a job started before this open is muted, so it does not follow the reopened chat", follows)

        // So the cold open asks Hermes (marker written, nothing shown, nothing following), and Hermes' `running` answer brings the turn back at once.
        assertTrue(shouldAskRunning(marked = true, shownStream = reopened.streamId) && !follows)
        assertEquals(ColdRun.Follow, coldRunAction("working"))
        val followed = reopened.copy(streamId = "runtime-1", status = "Working")
        assertTrue("Stop shows", followed.streamId.isNotBlank())
        assertEquals("the tool row is running, not 'No result recorded'", ToolOutcome.Running,
            toolOutcome("terminal", "running", false, null, false, streaming = followed.streamId.isNotBlank()))
    }

    @Test fun aJobOfTheCurrentOpenStillFollowsSoASettleInProgressIsNotAskedTwice() {
        // b02e4681: a reply settled on a cold open is not asked about twice. The job that open started belongs to the same generation.
        generation++
        val settling = ScopeSettlement(chat, client, 0, generation)
        assertTrue(streamFollows(true, settling, chat, client, 0, generation))
    }

    @Test fun aFinishedJobFollowsNothingWhateverItsGeneration() {
        val started = ScopeSettlement(chat, client, 0, generation)
        assertFalse(streamFollows(false, started, chat, client, 0, generation))
        assertFalse("no job registered", streamFollows(false, null, chat, client, 0, generation))
    }

    @Test fun aJobOfAnotherChatClientOrSignInFollowsNothing() {
        val started = ScopeSettlement(chat, client, 0, generation)
        assertFalse(streamFollows(true, started, owner.session("session-b"), client, 0, generation))
        assertFalse(streamFollows(true, started, chat, Any(), 0, generation))
        assertFalse(streamFollows(true, started, chat, client, 1, generation))
        assertFalse("a job with no recorded start cannot be shown to follow", streamFollows(true, null, chat, client, 0, generation))
    }

    @Test fun theSameChatSelectedAgainMidTurnIsPickedUpByTheWatchNotLeftMute() {
        // Re-selecting the open chat keeps its shown stream (chatOnSelection), but the job is muted by the new generation: the watch must see it as unfollowed and ask Hermes.
        val started = ScopeSettlement(chat, client, 0, generation)
        val shown = ChatState(sessionId = "session-a", streamId = "runtime-1", status = "Working")
        generation++
        assertEquals("runtime-1", chatOnSelection(shown, "session-a", "", "").streamId)
        assertFalse(streamFollows(true, started, chat, client, 0, generation))
    }
}
