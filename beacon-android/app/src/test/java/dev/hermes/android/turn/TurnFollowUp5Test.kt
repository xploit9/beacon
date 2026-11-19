package dev.hermes.android.turn

import dev.hermes.android.ChatState
import dev.hermes.android.Message
import dev.hermes.android.UNCONFIRMED_REPLIES
import dev.hermes.android.assist.answerFrom
import dev.hermes.android.core.LiveAssistantBoundary
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.sealedFromFrame
import dev.hermes.android.core.sealedPlacesFromFrame
import dev.hermes.android.core.string
import dev.hermes.android.liveAfterEvent
import dev.hermes.android.releaseTerminalChat
import dev.hermes.android.retainUnconfirmedReply
import dev.hermes.android.sealedPlaces
import dev.hermes.android.ui.ToolOutcome
import dev.hermes.android.ui.isInterruptedToolResult
import dev.hermes.android.ui.toolOutcome
import dev.hermes.android.ui.toolOutcomeLabel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TURN-121 follow-up 5. Synthetic text and ids only.
 * Item 29: live and sealed words are retired by their place in the turn, never by their words. Item 28: the side-button overlay reads the same words the chat draws.
 * Item 52: a tool call Hermes saved as interrupted reads Stopped (result shapes from tools/environments/base.py and tools/terminal_tool_result.py).
 */
class TurnFollowUp5Test {
    private val ask = json("row_id" to 31001, "role" to "user", "content" to "Synthetic question")
    private val a = "I will look at the file first."
    private val b = "Now the command."

    /** The chat as the view model drives it: stream events and the history reads that arrive as state_saved. */
    private class Run(var chat: ChatState = ChatState(sessionId = "chat-5", streamId = "runtime-5", status = "Working")) {
        var boundary = LiveAssistantBoundary()
        fun event(type: String, payload: JSONObject = JSONObject()) {
            val next = boundary.after(type, payload)
            chat = liveAfterEvent(chat, type, payload, boundary.closed, next) ?: chat
            boundary = next
        }
        fun saved(vararg rows: JSONObject) = event("state_saved", json("session" to json("session_id" to "chat-5", "messages" to JSONArray(rows.toList()))))
        fun words(text: String) { event("token", json("text" to text.take(12))); event("token", json("text" to text.drop(12))) }
        fun tool(id: String) { event("tool", json("tid" to id, "name" to "terminal")); event("tool_complete", json("tid" to id, "name" to "terminal")) }
    }

    /** What Hermes saves for a model message with words and one tool call, and the tool's result. */
    private fun call(id: Int, words: String, tool: String) = json("row_id" to id, "role" to "assistant", "content" to words, "tool_calls" to JSONArray().put(json("id" to tool)))
    private fun result(id: Int, tool: String) = json("row_id" to id, "role" to "tool", "content" to "ok", "tool_call_id" to tool)
    private fun shown(chat: ChatState) = chat.liveSealed + listOf(chat.liveText).filter { it.isNotBlank() }
    private fun savedWords(chat: ChatState) = chat.messages.filter { it.role == "assistant" && it.text.isNotBlank() }.map { it.text }

    // ---- Item 29: retired by place ----

    @Test fun sealedWordsHermesSavedDifferentlyShowOnceNotTwice() {
        val run = Run()
        run.saved(ask)
        run.words(a); run.tool("c1"); run.words(b)
        assertEquals("the first words are sealed, the second are live", listOf(a, b), shown(run.chat))
        assertEquals("the sealed words keep their place in the turn", listOf(1), run.chat.sealedPlaces())
        // Hermes saved the message with its think tags stripped and the sentence reworded a little: not the words that streamed.
        val saved = "I will look at the file first"
        run.saved(ask, call(31002, saved, "c1"), result(31003, "c1"))
        assertEquals("only Hermes' row shows the first words; the live words stay", listOf(b), shown(run.chat))
        assertEquals(listOf(saved), savedWords(run.chat))
    }

    @Test fun aClosedSegmentWithNoToolCallIsHeldByItsPlaceNotItsWords() {
        // Hermes saves the words of a continuation message with no tool call (turn_truncation.py); the saved copy reads a little differently.
        val run = Run()
        run.saved(ask)
        run.words(a)
        run.event("interim_assistant", json("text" to a, "already_streamed" to true))
        assertEquals(listOf(a), shown(run.chat))
        run.saved(ask, json("row_id" to 31002, "role" to "assistant", "content" to "I will look at the file first"))
        assertEquals("Hermes holds the segment: its row alone shows the words", emptyList<String>(), shown(run.chat))
    }

    @Test fun anOlderTurnsRepliesNeverHideTheNewTurnsWords() {
        // The new turn's prompt is not a held row yet; the last held question is an older turn's, and its reply says "Done." too.
        val old = json("row_id" to 30001, "role" to "user", "content" to "Earlier question")
        val oldReply = json("row_id" to 30002, "role" to "assistant", "content" to "Done.")
        val run = Run()
        run.saved(old, oldReply)
        run.words("Done."); run.tool("c1"); run.words(b)
        assertEquals("sealed words of the new turn", listOf("Done."), run.chat.liveSealed)
        run.saved(old, oldReply)
        assertEquals("the older reply does not retire the new turn's words", listOf("Done.", b), shown(run.chat))
        assertEquals("the words were counted behind the older turn's saved message", listOf(2), run.chat.sealedPlaces())
        // The new turn's own question arrives with Hermes' saved row for its first message: the older turn's message comes off the places, and that row holds the first words.
        val newAsk = json("row_id" to 30003, "role" to "user", "content" to "New question")
        run.saved(old, oldReply, newAsk, call(30004, "Done.", "c1"), result(30005, "c1"))
        assertEquals("only the words Hermes has not saved are live", listOf(b), shown(run.chat))
        assertEquals("the places are the turn's own again", 2, run.chat.liveOrdinal)
        assertEquals(0, run.chat.liveBase)
    }

    @Test fun placesCountedBehindAnOlderTurnAreKeptWhileItsOwnQuestionIsStillUnsaved() {
        // Same turn, same question (this phone joined it mid-way): nothing is re-based.
        val run = Run()
        run.saved(ask, call(31002, a, "c1"), result(31003, "c1"))
        run.words(b); run.tool("c2"); run.words("The end.")
        assertEquals("behind Hermes' saved message of this turn", listOf(2), run.chat.sealedPlaces())
        run.saved(ask, call(31002, a, "c1"), result(31003, "c1"))
        assertEquals(listOf(2), run.chat.sealedPlaces())
        assertEquals(1, run.chat.liveBase)
        run.saved(ask, call(31002, a, "c1"), result(31003, "c1"), call(31004, "Now the command!", "c2"), result(31005, "c2"))
        assertEquals("Hermes' second message holds the sealed words", listOf("The end."), shown(run.chat))
    }

    @Test fun aSegmentNotSavedYetIsNotRetiredByAnEarlierOne() {
        val run = Run()
        run.saved(ask)
        run.words(a); run.tool("c1"); run.words(b); run.tool("c2"); run.words("The end.")
        assertEquals(listOf(1, 2), run.chat.sealedPlaces())
        // Hermes saved only the first message so far.
        run.saved(ask, call(31002, a, "c1"), result(31003, "c1"))
        assertEquals("the second is still only on screen, once", listOf(b, "The end."), shown(run.chat))
        run.saved(ask, call(31002, a, "c1"), result(31003, "c1"), call(31004, "Now the command!", "c2"), result(31005, "c2"))
        assertEquals(listOf("The end."), shown(run.chat))
    }

    @Test fun theSealedPlacesSurviveAReconnect() {
        val frame = json("sealed" to JSONArray(listOf(a, b)), "sealed_at" to JSONArray(listOf(1, 3)))
        assertEquals(listOf(a, b), sealedFromFrame(frame))
        assertEquals(listOf(1, 3), sealedPlacesFromFrame(frame))
        assertEquals("a frame of an older build has no places", listOf(0, 0), sealedPlacesFromFrame(json("sealed" to JSONArray(listOf(a, b)))))
        assertEquals(emptyList<Int>(), sealedPlacesFromFrame(json("text" to "x")))
    }

    @Test fun aKeptSegmentKeepsItsOwnPlaceWhenThePlacesHaveAGap() {
        // Hermes saved a message that streamed no words between the two segments, so their places are 1 and 3, not 1 and 2.
        val chat = ChatState(sessionId = "chat-5", streamId = "runtime-5", messages = listOf(Message.from(ask, 0, "chat-5")), raw = json("messages" to JSONArray().put(ask)),
            liveSealed = listOf(a, b), liveSealedAt = listOf(1, 3), liveText = "The end.", liveOrdinal = 4)
        val kept = retainUnconfirmedReply(chat).raw.rows(UNCONFIRMED_REPLIES)
        assertEquals(listOf(1, 3, 4), kept.map { it.optInt("_segment") })
        assertEquals(listOf(a, b, "The end."), kept.map { it.string("text") })
    }

    @Test fun savedRowsThatRepeatASentenceAreDrawnAsHermesSavedThem() {
        // G5 W1: the model's final message began with the sentence it had already said beside its tool call, so Hermes' saved rows hold it twice.
        // Hermes' own chat view does the same on a reload (apps/desktop/src/lib/chat-messages/parts.ts dedupeRepeatedTextInParts folds only byte-identical text).
        val run = Run()
        run.saved(ask)
        run.words(a); run.tool("c1"); run.words("$a\n\nThe answer.")
        run.saved(ask, call(31002, a, "c1"), result(31003, "c1"), json("row_id" to 31004, "role" to "assistant", "content" to "$a\n\nThe answer."))
        run.event("done", json("status" to "complete", "text" to "$a\n\nThe answer."))
        val ended = releaseTerminalChat(run.chat, "runtime-5", replyConfirmed = true, terminalStatus = "")
        assertEquals("nothing is kept or live beside Hermes' two rows", emptyList<String>(), shown(ended))
        assertEquals(listOf(a, "$a\n\nThe answer."), savedWords(ended))
    }

    // ---- Item 28: the overlay ----

    @Test fun theOverlayShowsTheSealedAndLiveWordsInOrder() {
        val chat = ChatState(sessionId = "s1", messages = listOf(Message("m1", "user", "Hi")), streamId = "st", liveSealed = listOf(a), liveSealedAt = listOf(1), liveText = b, liveOrdinal = 2)
        assertEquals("$a\n\n$b", answerFrom(chat, emptySet(), "s1")!!.answer)
    }

    @Test fun theOverlayShowsWhatHermesHoldsOnceAndTheRestAfterIt() {
        val run = Run()
        run.saved(ask)
        run.words(a); run.tool("c1"); run.words(b); run.tool("c2"); run.words("The end.")
        run.saved(ask, call(31002, a, "c1"), result(31003, "c1"))
        assertEquals("Hermes' row, then the closed segment, then the live words", "$a\n\n$b\n\nThe end.", answerFrom(run.chat, emptySet(), "chat-5")!!.answer)
        // Hermes saves the second message with different words: its row shows them, the sealed copy goes (by place).
        run.saved(ask, call(31002, a, "c1"), result(31003, "c1"), call(31004, "Now the command!", "c2"), result(31005, "c2"))
        assertEquals("$a\n\nNow the command!\n\nThe end.", answerFrom(run.chat, emptySet(), "chat-5")!!.answer)
    }

    @Test fun theOverlayShowsTheWordsAReplyCutKeptInOrderAndNotAnEarlierTurnsCopies() {
        val older = json("id" to "unconfirmed:turn:old0", "text" to "An older kept reply.", "_segment" to 1, "_history_user_row_id" to "30001")
        val first = json("id" to "unconfirmed:turn:new0", "text" to a, "_segment" to 1, "_history_user_row_id" to "31001")
        val second = json("id" to "unconfirmed:turn:new1", "text" to b, "_segment" to 2, "_history_user_row_id" to "31001")
        val chat = ChatState(sessionId = "s1", messages = listOf(Message("31001", "user", "Hi")),
            raw = json(UNCONFIRMED_REPLIES to JSONArray(listOf(second, older, first))))
        assertEquals("both kept segments, in the order said, without the older turn's copy", "$a\n\n$b",
            answerFrom(chat, setOf("unconfirmed:turn:old0"), "s1")!!.answer)
    }

    // ---- Item 52: a stopped tool row ----

    private val stoppedTerminal = """{"output": "partial output\n[Command interrupted]", "exit_code": 130, "error": null}"""

    @Test fun aToolCallHermesSavedAsInterruptedReadsStopped() {
        assertEquals(ToolOutcome.Stopped, toolOutcome("terminal", "", false, stoppedTerminal, explicitCompletion = true, streaming = false))
        assertEquals("Stopped", toolOutcomeLabel(ToolOutcome.Stopped))
        // Hermes' other executors end the same way: Modal (exit 130) and code execution (exit -1).
        assertTrue(isInterruptedToolResult("""{"output": "x\n[Command interrupted - Modal sandbox exec cancelled]", "exit_code": 130}"""))
        assertTrue(isInterruptedToolResult("""{"output": "x\n[execution interrupted after 5s]", "exit_code": -1}"""))
        // A bare text result (a file search the interrupt cut) has no envelope.
        assertTrue(isInterruptedToolResult("found 2 files\n[Command interrupted]"))
    }

    @Test fun onlyTheInterruptShapeReadsStopped() {
        // A marker quoted inside output that succeeded (exit 0) is data; so is a command that chose exit 130 itself.
        assertFalse(isInterruptedToolResult("""{"output": "grep hit: [Command interrupted]\nmore", "exit_code": 0}"""))
        assertFalse(isInterruptedToolResult("""{"output": "[Command interrupted] was quoted in the doc", "exit_code": 0}"""))
        assertFalse(isInterruptedToolResult("""{"output": "bye", "exit_code": 130}"""))
        assertFalse(isInterruptedToolResult("""{"output": "[Command interrupted]"}"""))
        assertFalse(isInterruptedToolResult("""{"output": "x\n[Command interrupted]", "exit_code": null}"""))
        assertFalse(isInterruptedToolResult("not json {\n[Command interrupted]x"))
        assertFalse(isInterruptedToolResult(null))
        assertEquals(ToolOutcome.Completed, toolOutcome("terminal", "", false, """{"output": "total 0", "exit_code": 0}""", explicitCompletion = true, streaming = false))
        assertEquals(ToolOutcome.Failed, toolOutcome("terminal", "", true, """{"output": "boom", "exit_code": 1}""", explicitCompletion = true, streaming = false))
    }

    @Test fun stoppedIsInEveryLanguage() {
        val catalog = JSONObject(File(appRoot, "src/main/assets/native-locales.json").readText(Charsets.UTF_8).removePrefix("﻿"))
        val missing = catalog.keys().asSequence().filter { it != "en" }.filter { catalog.getJSONObject(it).optString("stopped").isBlank() }.toList()
        assertEquals("languages with no word for Stopped: $missing", emptyList<String>(), missing)
        assertEquals(15, catalog.length() - 1)
    }

    private val appRoot = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it).absoluteFile.normalize() }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "the app folder was not found from ${File(".").absolutePath}"
    }
}
