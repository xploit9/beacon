package dev.hermes.android

import dev.hermes.android.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * A turn that speaks, is saved, and goes on with more tool steps. Hermes saves a message that has no tool call
 * (a stall-guard or stop-gate interim) with a hidden "[System: ...]" steering row after it, so nothing but its words names it.
 */
class LiveSegmentOwnershipTest {
    private val ask = row("user", "can you set an alarm for me on my phone for 10 am tomorrow?")
    private val prelude = "I'll set the alarm for 10 am tomorrow now."

    private fun row(role: String, content: String, vararg extra: Pair<String, Any?>) =
        json("role" to role, "content" to content, "row_id" to Math.floorMod("$role|$content|${extra.joinToString { it.second.toString() }}".hashCode(), 900_000) + 1000, *extra)
    private fun steering() = row("user", "[System: Continue now. Execute the required tool calls and only send your final answer after completing the task.]")

    /** The chat as the view model drives it: stream events through [liveAfterEvent], saved history through [settleLive]. */
    private class Run(var chat: ChatState = ChatState(sessionId = "s", streamId = "runtime-s", status = "Working")) {
        var boundary = LiveAssistantBoundary()
        fun event(type: String, payload: JSONObject = JSONObject()) {
            val next = boundary.after(type, payload)
            chat = liveAfterEvent(chat, type, payload, boundary.closed, next) ?: chat
            boundary = next
        }
        /** What renderSession does with the passive history read: it names no active stream. */
        fun saved(rows: List<JSONObject>) {
            val live = settleLive(chat, rows, null, boundary, "")
            chat = chat.copy(messages = rows.mapIndexed { i, m -> Message.from(m, i, "s") }, raw = JSONObject().put("messages", JSONArray(rows)),
                streamId = if (live.streamEnded) "" else chat.streamId, liveText = live.text, liveReasoning = live.reasoning, liveTools = live.tools)
        }
        fun shownTwice(): Boolean {
            val live = chat.liveText.trim()
            return live.isNotEmpty() && chat.messages.any { it.role == "assistant" && it.text.trim() == live }
        }
    }

    @Test fun aSavedTextOnlyMessageIsNotAlsoShownLiveWhileTheTurnGoesOn() {
        val run = Run()
        run.saved(listOf(ask))
        run.event("reasoning", json("text" to "Considering the alarm."))
        run.event("token", json("text" to "I'll set the alarm for 10 am "))
        run.event("token", json("text" to "tomorrow now."))
        run.event("interim_assistant", json("text" to prelude, "already_streamed" to true))
        assertEquals(prelude, run.chat.liveText)
        assertFalse("Nothing is saved yet", run.shownTwice())

        run.saved(listOf(ask, row("assistant", prelude, "reasoning" to "Considering the alarm."), steering()))
        assertFalse("The saved copy replaces the live bubble", run.shownTwice())
        assertEquals("", run.chat.liveText)
        assertEquals("The reasoning is in the saved message too", "", run.chat.liveReasoning)
        assertEquals("The turn goes on", "runtime-s", run.chat.streamId)

        repeat(6) { run.event("tool", json("tid" to "describe-$it", "name" to "describe")) }
        assertFalse(run.shownTwice())
        assertEquals("", run.chat.liveText)
        assertEquals(6, run.chat.liveTools.size)
    }

    @Test fun aMessageSavedBeforeItsInterimArrivesIsNeverShownLive() {
        val run = Run()
        run.saved(listOf(ask))
        run.event("token", json("text" to prelude))
        run.saved(listOf(ask, row("assistant", prelude), steering()))
        // Still arriving as far as the stream knows: the saved copy and the words in flight are one message until the interim closes it.
        run.event("interim_assistant", json("text" to prelude, "already_streamed" to false))
        assertFalse(run.shownTwice())
        assertEquals("", run.chat.liveText)
        run.event("tool", json("tid" to "describe-0", "name" to "describe"))
        assertFalse(run.shownTwice())
    }

    @Test fun aHistoryReadBetweenTheWordsAndTheFirstToolStepStillSettlesWhenTheToolStepArrives() {
        val run = Run()
        run.saved(listOf(ask))
        run.event("token", json("text" to prelude))
        run.event("interim_assistant", json("text" to prelude, "already_streamed" to true))
        run.event("tool", json("tid" to "describe-0", "name" to "describe"))
        run.saved(listOf(ask, row("assistant", prelude), steering()))
        assertFalse(run.shownTwice())
        run.event("tool", json("tid" to "describe-1", "name" to "describe"))
        run.event("tool_complete", json("tid" to "describe-0", "name" to "describe"))
        assertFalse(run.shownTwice())
    }

    @Test fun theSameWordsInAnotherTurnStayTheirOwn() {
        val run = Run()
        val first = listOf(row("user", "first question"), row("assistant", prelude))
        run.saved(first + row("user", "second question"))
        run.event("token", json("text" to prelude))
        run.event("interim_assistant", json("text" to prelude, "already_streamed" to true))
        run.saved(first + row("user", "second question"))
        assertEquals(prelude, run.chat.liveText)
    }

    @Test fun toolCardsOfAnEarlierMessageOfTheTurnAreDroppedOnceSavedEvenAfterASteeringNote() {
        val run = Run()
        run.saved(listOf(ask))
        repeat(3) { run.event("tool", json("tid" to "terminal-$it", "name" to "terminal")) }
        run.event("token", json("text" to prelude))
        run.event("interim_assistant", json("text" to prelude, "already_streamed" to true))
        repeat(3) { run.event("tool", json("tid" to "describe-$it", "name" to "describe")) }
        val calls = JSONArray().apply { repeat(3) { put(json("id" to "terminal-$it")) } }
        run.saved(listOf(ask, row("assistant", "", "tool_calls" to calls), row("assistant", prelude), steering()))
        assertEquals("Only the steps the history does not hold are still live", listOf("describe-0", "describe-1", "describe-2"), run.chat.liveTools.map { it.optString("tid") })
        assertFalse(run.shownTwice())
    }

    @Test fun aSteeringNoteContinuesTheOwnersTurnButAnOwnersMessageStartsAnother() {
        val first = listOf(row("user", "first question"), row("assistant", prelude), steering(), row("assistant", "second"))
        assertEquals("The steering note does not cut the turn", first, runningTurnMessages(first))
        val next = first + row("user", "second question")
        assertEquals(listOf("second question"), runningTurnMessages(next).map { messageContentText("user", historyRowContent(it)) })
        assertTrue(isSteeringText("  [System: The active model changed]"))
        assertFalse(isSteeringText("[system note] hello"))
    }

    @Test fun onlyAClosedSegmentOfAHeldPlaceIsHeld() {
        val closed = LiveAssistantBoundary(closed = true)
        val turn = listOf(row("assistant", "I'll  set the alarm\nfor 10 am tomorrow, now."))
        assertTrue("Held by its place, whatever words Hermes saved", liveWordsSaved(prelude, 1, closed, turn))
        assertFalse("Words still arriving are not held yet", liveWordsSaved(prelude, 1, LiveAssistantBoundary(), turn))
        assertFalse("The next segment is not held by the first one's row", liveWordsSaved(prelude, 2, closed, turn))
        val withCall = listOf(row("assistant", prelude, "tool_calls" to JSONArray().put(json("id" to "call-1"))))
        assertTrue("A message with calls is told by them: its own call", liveWordsSaved(prelude, 1, LiveAssistantBoundary(closed = true, toolIds = setOf("call-1")), withCall))
        assertFalse("...and another call is another message", liveWordsSaved(prelude, 1, LiveAssistantBoundary(closed = true, toolIds = setOf("call-2")), withCall))
        assertFalse("Nothing to hold", liveWordsSaved("  ", 1, closed, turn))
        assertFalse("A user's own words are not a model message", liveWordsSaved(prelude, 1, closed, listOf(row("user", prelude))))
        assertFalse("With no turn known nothing is judged saved", liveWordsSaved(prelude, 1, closed, null))
        assertFalse("A place not known is not held", liveWordsSaved(prelude, 0, closed, turn))
    }

    @Test fun aCheckpointDoesNotBringBackWhatTheSavedRowsHold() {
        val boundary = LiveAssistantBoundary().after("interim_assistant").after("tool", json("tid" to "describe-0"))
        val checkpoint = ChatState(sessionId = "s", streamId = "runtime-s", messages = listOf(Message.from(ask, 0, "s")), liveText = prelude, liveReasoning = "Considering.", liveTools = listOf(json("tid" to "describe-0")), liveOrdinal = 1)
        val rows = listOf(ask, row("assistant", prelude), steering())
        val restored = withoutSavedLive(checkpoint, rows, boundary)
        assertEquals("", restored.liveText)
        assertEquals("", restored.liveReasoning)
        assertEquals("The turn and the step it has not saved go on", "runtime-s", restored.streamId)
        assertEquals(1, restored.liveTools.size)
        assertEquals(prelude, withoutSavedLive(checkpoint, listOf(ask), boundary).liveText)
    }

    @Test fun aMessageWithToolCallsStillGoesByItsToolCalls() {
        val run = Run()
        run.saved(listOf(ask))
        run.event("token", json("text" to prelude))
        run.event("interim_assistant", json("text" to prelude, "already_streamed" to true))
        run.event("tool", json("tid" to "call-1", "name" to "describe"))
        // The same words saved with a different call: the call, not the prose, is what names the message.
        run.saved(listOf(ask, row("assistant", prelude, "tool_calls" to JSONArray().put(json("id" to "other-call")))))
        assertEquals(prelude, run.chat.liveText)
        run.saved(listOf(ask, row("assistant", prelude, "tool_calls" to JSONArray().put(json("id" to "call-1")))))
        assertEquals("", run.chat.liveText)
    }
}
