package dev.hermes.android

import dev.hermes.android.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * The words a model says before or between tool steps, in the order Hermes itself emits them (agent/turn_tool_round.py: the tool-call row is
 * saved, then message.interim, then message.delta with text null, then the tools run). Lane G3M, 12 Nov 2026: on a 68 s turn the owner saw
 * the model's progress sentence for 0.3 s and then an empty Working state for 50 s, because the empty chunk (the segment boundary every other
 * Hermes viewer ignores) started a new segment, and the next model step dropped the closed words for good.
 */
class LiveSealedSegmentsTest {
    private val ask = row("user", "Inspect this synthetic image, then run one harmless command.")
    private val first = "I'll look at the image first, then run the check."
    private val second = "The picture is clear; now the command."
    private val last = "Done. The picture shows a few shapes."

    private fun row(role: String, content: String, vararg extra: Pair<String, Any?>) =
        json("role" to role, "content" to content, "row_id" to "$role-${content.hashCode()}", *extra)
    /** The assistant row Hermes saves for a message with words and one tool call. */
    private fun call(words: String, id: String) = row("assistant", words, "tool_calls" to JSONArray().put(json("id" to id)))
    private fun result(id: String) = row("tool", "ok", "tool_call_id" to id)

    /** The chat as the view model drives it: stream events through [liveAfterEvent], saved history through [settleLive]. */
    private class Run(var chat: ChatState = ChatState(sessionId = "s", streamId = "runtime-s", status = "Working")) {
        var boundary = LiveAssistantBoundary()
        fun event(type: String, payload: JSONObject = JSONObject()) {
            val next = boundary.after(type, payload)
            chat = liveAfterEvent(chat, type, payload, boundary.closed, next) ?: chat
            boundary = next
        }
        /** What renderSession does with a history read: it names no active stream. */
        fun saved(rows: List<JSONObject>) {
            val live = settleLive(chat, rows, null, boundary, "")
            chat = chat.copy(messages = rows.mapIndexed { i, m -> Message.from(m, i, "s") }, raw = JSONObject().put("messages", JSONArray(rows)),
                streamId = if (live.streamEnded) "" else chat.streamId, liveText = live.text, liveReasoning = live.reasoning, liveTools = live.tools, liveSealed = live.sealed)
        }
        /** The same history read arriving as the stream's own state_saved event. */
        fun stateSaved(rows: List<JSONObject>) = event("state_saved", json("session" to json("messages" to JSONArray(rows))))
    }

    /** What the owner reads from the model in the live area of the chat, in order. */
    private fun shown(chat: ChatState): List<String> = chat.liveSealed + listOf(chat.liveText).filter { it.isNotBlank() }
    /** How many times [words] is on screen: in the live area and in the saved assistant rows. */
    private fun timesShown(chat: ChatState, words: String) = shown(chat).count { it == words } + chat.messages.count { it.role == "assistant" && it.text == words }

    /** Hermes' order for a model message with words and a tool call; message.delta text:null is the segment boundary every other viewer ignores. */
    private fun wordsThenTool(run: Run, words: String, tool: String) {
        run.event("token", json("text" to words.take(20)))
        run.event("token", json("text" to words.drop(20)))
        run.event("interim_assistant", json("text" to words, "already_streamed" to true))
        run.event("token", json("text" to null))
        run.event("tool", json("tid" to tool, "name" to "terminal"))
    }

    @Test fun theWordsBeforeAToolStayOnScreenWhileTheToolRuns() {
        val run = Run()
        wordsThenTool(run, first, "vision-1")
        assertEquals("The words stay while the tool runs", listOf(first), shown(run.chat))
        assertEquals("The tool step is shown beside them", listOf("vision-1"), run.chat.liveTools.map { it.optString("tid") })
        assertEquals("The turn goes on", "runtime-s", run.chat.streamId)
    }

    /**
     * The order the phone's own route really sends (Hermes gateway/run_turn_runner.py:303-345, local viewer): streamed words arrive as chunks, then the
     * empty chunk (message.delta text null); message.interim is published only for words that were not streamed, so none comes here. R20 Nice 1.
     */
    @Test fun streamedWordsWithoutAnInterimStayOnScreenThroughTheToolAndAreSealedByTheNextChunk() {
        val run = Run()
        run.saved(listOf(ask))
        run.event("token", json("text" to first.take(20)))
        run.event("token", json("text" to first.drop(20)))
        assertEquals("While they stream", listOf(first), shown(run.chat))
        val beforeBoundary = run.chat
        run.event("token", json("text" to null))
        assertEquals("The empty chunk after the words changes nothing", beforeBoundary, run.chat)
        assertFalse("No interim came, so the segment is still open", run.boundary.closed)
        run.event("tool", json("tid" to "vision-1", "name" to "terminal"))
        assertEquals("During the tool", listOf(first), shown(run.chat))
        assertTrue("The tool closes the segment", run.boundary.closed)
        assertEquals("Closed, not sealed yet: the words are still the live words", emptyList<String>(), run.chat.liveSealed)
        run.event("tool_complete", json("tid" to "vision-1", "name" to "terminal"))
        assertEquals("After the tool, before the next step speaks", listOf(first), shown(run.chat))
        // A next step with no words of its own, only a tool call: Hermes sends the empty chunk again, now on a closed segment.
        val beforeNextBoundary = run.chat
        run.event("token", json("text" to null))
        assertEquals("The empty chunk of a tool-only step changes nothing either", beforeNextBoundary, run.chat)
        run.event("tool", json("tid" to "terminal-1", "name" to "terminal"))
        run.event("tool_complete", json("tid" to "terminal-1", "name" to "terminal"))
        assertEquals("Still shown through the second tool", listOf(first), shown(run.chat))
        run.event("token", json("text" to last))
        assertEquals("The next real chunk seals the closed words", listOf(first), run.chat.liveSealed)
        assertEquals("and starts the new words", last, run.chat.liveText)
        assertEquals(listOf(first, last), shown(run.chat))
        // Hermes saved the first message before its tool ran: its row takes the place of the sealed words, once.
        run.stateSaved(listOf(ask, call(first, "vision-1"), result("vision-1")))
        assertEquals(listOf(last), shown(run.chat))
        assertEquals(1, timesShown(run.chat, first))
    }

    @Test fun anEmptyChunkIsOnlyASegmentBoundaryAndChangesNothing() {
        val run = Run()
        run.event("token", json("text" to first))
        run.event("interim_assistant", json("text" to first, "already_streamed" to true))
        val before = run.chat
        run.event("token", json("text" to null))
        run.event("token", json("text" to ""))
        assertEquals(before, run.chat)
        assertTrue("The segment stays closed", run.boundary.closed)
        assertEquals("Nothing is sealed by a boundary alone", emptyList<String>(), run.chat.liveSealed)
    }

    @Test fun theWordsStayWhenTheNextModelStepBegins() {
        val run = Run()
        wordsThenTool(run, first, "vision-1")
        run.event("tool_complete", json("tid" to "vision-1", "name" to "terminal"))
        run.event("reasoning", json("text" to "Considering the result."))
        assertEquals(listOf(first), shown(run.chat))
        assertEquals("Considering the result.", run.chat.liveReasoning)
        run.event("token", json("text" to last))
        assertEquals("The closed words stay above the new ones, in order", listOf(first, last), shown(run.chat))
    }

    @Test fun twoToolRoundsKeepBothSentencesInOrder() {
        val run = Run()
        wordsThenTool(run, first, "vision-1")
        run.event("tool_complete", json("tid" to "vision-1", "name" to "terminal"))
        wordsThenTool(run, second, "terminal-1")
        assertEquals("During the second tool", listOf(first, second), shown(run.chat))
        assertEquals(listOf("vision-1", "terminal-1"), run.chat.liveTools.map { it.optString("tid") })
        run.event("tool_complete", json("tid" to "terminal-1", "name" to "terminal"))
        run.event("token", json("text" to last))
        assertEquals("With the final words", listOf(first, second, last), shown(run.chat))
    }

    @Test fun commentaryThatComesWholeAfterAClosedSegmentKeepsTheEarlierWords() {
        // Codex-style commentary: no message.delta chunks, only message.interim with already_streamed false.
        val run = Run()
        run.event("interim_assistant", json("text" to first, "already_streamed" to false))
        run.event("tool", json("tid" to "t-1", "name" to "terminal"))
        run.event("tool_complete", json("tid" to "t-1", "name" to "terminal"))
        run.event("interim_assistant", json("text" to second, "already_streamed" to false))
        assertEquals(listOf(first, second), shown(run.chat))
        run.event("interim_assistant", json("text" to second, "already_streamed" to false))
        assertEquals("A re-sent seal of the same words is no new bubble", listOf(first, second), shown(run.chat))
    }

    @Test fun aFinalReplyThatComesWholeKeepsTheClosedWordsAbove() {
        val run = Run()
        wordsThenTool(run, first, "t-1")
        run.event("tool_complete", json("tid" to "t-1", "name" to "terminal"))
        run.event("done", json("text" to last))
        assertEquals(listOf(first, last), shown(run.chat))
    }

    @Test fun theSavedRowsReplaceTheSealedWordsOnceAndNothingIsShownTwice() {
        val run = Run()
        run.saved(listOf(ask))
        wordsThenTool(run, first, "vision-1")
        run.event("tool_complete", json("tid" to "vision-1", "name" to "terminal"))
        wordsThenTool(run, second, "terminal-1")
        // Hermes saved both messages before their tools ran; the phone reads that history while the second tool runs.
        run.saved(listOf(ask, call(first, "vision-1"), result("vision-1"), call(second, "terminal-1")))
        assertEquals(emptyList<String>(), shown(run.chat))
        listOf(first, second).forEach { assertEquals("$it is shown once, as its row", 1, timesShown(run.chat, it)) }
        run.event("tool_complete", json("tid" to "terminal-1", "name" to "terminal"))
        run.event("token", json("text" to last))
        assertEquals(listOf(last), shown(run.chat))
    }

    @Test fun aHistoryReadBetweenTheTwoRoundsDropsOnlyTheSavedSentence() {
        val run = Run()
        run.saved(listOf(ask))
        wordsThenTool(run, first, "vision-1")
        run.event("tool_complete", json("tid" to "vision-1", "name" to "terminal"))
        run.event("reasoning", json("text" to "Thinking."))
        run.event("token", json("text" to second))
        assertEquals(listOf(first, second), shown(run.chat))
        // Only the first message is saved so far.
        run.stateSaved(listOf(ask, call(first, "vision-1"), result("vision-1")))
        assertEquals(listOf(second), shown(run.chat))
        assertEquals(1, timesShown(run.chat, first))
        assertEquals(1, timesShown(run.chat, second))
    }

    @Test fun aSentenceTheModelRepeatsIsNotDroppedBecauseOnlyTheFirstCopyIsSaved() {
        val checking = "Checking."
        val run = Run()
        run.saved(listOf(ask))
        wordsThenTool(run, checking, "t-1")
        run.event("tool_complete", json("tid" to "t-1", "name" to "terminal"))
        wordsThenTool(run, checking, "t-2")
        run.event("tool_complete", json("tid" to "t-2", "name" to "terminal"))
        run.event("token", json("text" to last))
        assertEquals(listOf(checking, checking, last), shown(run.chat))
        run.stateSaved(listOf(ask, call(checking, "t-1"), result("t-1")))
        assertEquals("One saved row holds one of the two", listOf(checking, last), shown(run.chat))
        run.stateSaved(listOf(ask, call(checking, "t-1"), result("t-1"), call(checking, "t-2"), result("t-2")))
        assertEquals(listOf(last), shown(run.chat))
    }

    @Test fun anEarlierTurnsSentenceDoesNotHoldTheRunningTurnsWords() {
        val earlier = listOf(row("user", "first question"), call(first, "old-1"), row("assistant", "All done."))
        assertEquals(listOf(first), sealedWordsNotSaved(earlier + ask, listOf(first)))
        assertEquals("Its own turn's saved row holds it", emptyList<String>(), sealedWordsNotSaved(earlier + ask + call(first, "new-1"), listOf(first)))
        assertEquals("Spacing is not part of the words", emptyList<String>(), sealedWordsNotSaved(listOf(ask, row("assistant", "I'll  look at the image first,\nthen run the check.")), listOf(first)))
    }

    @Test fun nothingIsLeftWhenTheTurnEndsFailsOrIsCancelled() {
        val run = Run()
        run.saved(listOf(ask))
        wordsThenTool(run, first, "vision-1")
        run.event("tool_complete", json("tid" to "vision-1", "name" to "terminal"))
        run.event("token", json("text" to last))
        assertEquals(listOf(first, last), shown(run.chat))
        listOf(true, false).forEach { confirmed ->
            val ended = releaseTerminalChat(run.chat, "runtime-s", replyConfirmed = confirmed, terminalStatus = "Stopped")
            assertEquals("A turn that ended (confirmed $confirmed) keeps no sealed words", emptyList<String>(), ended.liveSealed)
            assertEquals("", ended.streamId)
        }
        // The saved final reply ends the live view and takes the sealed words with it.
        val settled = settleLive(run.chat, listOf(ask, call(first, "vision-1"), result("vision-1"), row("assistant", last)), null, run.boundary, row("assistant", last).string("row_id"))
        assertTrue(settled.streamEnded)
        assertEquals(emptyList<String>(), settled.sealed)
    }

    @Test fun aReselectOrReconnectMidTurnKeepsTheSealedWordsFromTheLiveFrame() {
        val run = Run()
        run.saved(listOf(ask))
        wordsThenTool(run, first, "vision-1")
        run.event("tool_complete", json("tid" to "vision-1", "name" to "terminal"))
        wordsThenTool(run, second, "terminal-1")
        val frame = run.boundary.saveInto(json("cursor" to "c1", "text" to run.chat.liveText, "reasoning" to run.chat.liveReasoning,
            "tools" to JSONArray(run.chat.liveTools), "sealed" to JSONArray(run.chat.liveSealed), "terminal" to false))
        // The frame as stored and read back.
        val stored = JSONObject(frame.toString())
        val restored = ChatState(sessionId = "s", streamId = "runtime-s", messages = emptyList(), raw = JSONObject().put("messages", JSONArray(listOf(ask)))).copy(
            liveText = stored.string("text"), liveTools = stored.rows("tools"), liveSealed = sealedFromFrame(stored))
        assertEquals(listOf(first, second), shown(restored))
        // History that already holds the first message drops it from the restored words.
        val afterRead = withoutSavedLive(restored, listOf(ask, call(first, "vision-1"), result("vision-1")), LiveAssistantBoundary.from(stored))
        assertEquals(listOf(second), shown(afterRead))
        // A newer turn keeps only the cursor of the frame: none of the failed turn's words come back.
        assertEquals(emptyList<String>(), sealedFromFrame(restoredFrame(stored, SavedTerminalFrame.NewerTurn)!!))
        assertEquals("A frame of an older build has none", emptyList<String>(), sealedFromFrame(json("text" to "x")))
        // Selecting the open chat again keeps its live words; selecting another chat starts with none.
        assertSame(run.chat, chatOnSelection(run.chat, "s", "", ""))
        assertEquals(emptyList<String>(), chatOnSelection(run.chat, "other", "", "").liveSealed)
    }

    @Test fun aTurnKeepsAtMostTheNewestSealedSegments() {
        val many = (1..40).map { "Step $it" }
        val kept = sealedWordsNotSaved(listOf(ask), many)
        assertEquals(24, kept.size)
        assertEquals("Step 40", kept.last())
        assertEquals(24, sealedFromFrame(json("sealed" to JSONArray(many))).size)
    }
}
