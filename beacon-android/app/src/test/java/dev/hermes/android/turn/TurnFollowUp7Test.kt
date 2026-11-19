package dev.hermes.android.turn

import dev.hermes.android.ChatState
import dev.hermes.android.KEPT_IN_TURN
import dev.hermes.android.Message
import dev.hermes.android.UNCONFIRMED_REPLIES
import dev.hermes.android.assist.answerFrom
import dev.hermes.android.core.LiveAssistantBoundary
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.foldRepeatedFinal
import dev.hermes.android.keptRowsNotSaved
import dev.hermes.android.keptTurnPrompt
import dev.hermes.android.liveAfterEvent
import dev.hermes.android.retainUnconfirmedReply
import dev.hermes.android.ui.placeKeptReplies
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TURN-121 follow-up 6 (turn-121-7). Synthetic text and ids only.
 * (2) The byte-identical final row Hermes' housekeeping-reuse path saves is folded as Hermes' own view folds it (apps/desktop/src/lib/chat-messages/parts.ts:438 dedupeRepeatedTextInParts).
 * (3) A kept copy this build makes always has a place and finds its turn by ids, so the words fallback can only meet a copy from an older frame.
 */
class TurnFollowUp7Test {
    private val x = "I'll run the check and report back."
    private val call = JSONObject().put("tid", "c1").put("name", "terminal")
    private fun user(id: String, text: String = "Question") = Message(id, "user", text)
    private fun said(id: String, text: String, tools: Boolean = false, kind: String = "") =
        Message(id, "assistant", text, tools = if (tools) listOf(call) else emptyList(), displayKind = kind)
    private fun texts(messages: List<Message>) = messages.map { it.text }

    // ---- (2) the fold ----

    @Test fun anIdenticalFinalAfterAToolStepShowsTheSentenceOnceAndKeepsTheToolStep() {
        val folded = foldRepeatedFinal(listOf(user("1"), said("2", x, tools = true), said("3", x)))
        assertEquals(listOf("Question", "", x), texts(folded))
        assertEquals("the earlier row keeps its tool step", 1, folded[1].tools.size)
        assertEquals(listOf("2", "3"), folded.drop(1).map { it.id })
        assertEquals("spacing is not part of the words (normalizeWs, parts.ts:403)", "",
            foldRepeatedFinal(listOf(said("2", x, tools = true), said("3", x.replace(" ", "  ") + "\n")))[0].text)
    }

    @Test fun theRowsHermesSavesAreFoldedThroughTheRealRowReader() {
        val rows = listOf(
            json("row_id" to 41001, "role" to "user", "content" to "Question"),
            json("row_id" to 41002, "role" to "assistant", "content" to x, "tool_calls" to JSONArray().put(json("id" to "c1"))),
            json("row_id" to 41003, "role" to "tool", "content" to "ok", "tool_call_id" to "c1"),
            json("row_id" to 41004, "role" to "assistant", "content" to x))
        val shown = foldRepeatedFinal(rows.mapIndexed { i, row -> Message.from(row, i, "chat-7") }).filter { it.role == "assistant" }
        assertEquals(listOf("", x), texts(shown))
        assertEquals(1, shown[0].tools.size)
    }

    @Test fun onlyHermesExactShapeIsFolded() {
        // The G5 shape: the final only starts with the earlier sentence. Hermes does not fold it, so neither does Beacon.
        assertEquals(listOf(x, "$x\n\nThe answer."), texts(foldRepeatedFinal(listOf(said("2", x, tools = true), said("3", "$x\n\nThe answer.")))))
        // The same words with no tool call between them are two messages the model wrote.
        assertEquals(listOf(x, x), texts(foldRepeatedFinal(listOf(said("2", x), said("3", x)))))
        // A tool call after the last text: the last text is not the final.
        assertEquals(listOf(x, x, ""), texts(foldRepeatedFinal(listOf(said("2", x, tools = true), said("3", x), said("4", "", tools = true)))))
        // An authored repeat in an earlier round is not the last two texts.
        assertEquals(listOf(x, x, "Then this."), texts(foldRepeatedFinal(listOf(said("2", x, tools = true), said("3", x, tools = true), said("4", "Then this.")))))
        // Hermes' own notes are not model text.
        assertEquals(listOf(x, x), texts(foldRepeatedFinal(listOf(said("2", x, tools = true), said("3", x, kind = "failed_turn")))))
        // A question starts another run: the earlier turn's words are its own.
        assertEquals(listOf(x, "Q", x), texts(foldRepeatedFinal(listOf(said("2", x, tools = true), user("3", "Q"), said("4", x)))))
    }

    @Test fun eachTurnIsFoldedByItself() {
        val folded = foldRepeatedFinal(listOf(user("1"), said("2", x, tools = true), said("3", x), user("4", "Again"), said("5", "Done.", tools = true), said("6", "Done.")))
        assertEquals(listOf("Question", "", x, "Again", "", "Done."), texts(folded))
    }

    @Test fun theOverlayShowsTheFoldedSentenceOnce() {
        val chat = ChatState(sessionId = "s1", messages = listOf(user("m1"), said("m2", x, tools = true), said("m3", x)))
        assertEquals(x, answerFrom(chat, emptySet(), "s1")!!.answer)
    }

    // ---- (3) a new kept copy always has a place and finds its turn by ids ----

    private val ask = json("row_id" to 42001, "role" to "user", "content" to "New question")
    private fun saved(id: Int, words: String, tool: String) = json("row_id" to id, "role" to "assistant", "content" to words, "tool_calls" to JSONArray().put(json("id" to tool)))

    /** A reply cut while its own question is not a held row yet: the phone holds no rows of this turn. */
    private fun keptWhileTheQuestionIsNotHeld(): List<JSONObject> {
        val chat = ChatState(sessionId = "chat-7", streamId = "runtime-7", raw = json("session_id" to "chat-7"),
            liveSealed = listOf("I will look at the file first."), liveSealedAt = listOf(1), liveText = "Now the command.", liveOrdinal = 2)
        return retainUnconfirmedReply(chat).raw.rows(UNCONFIRMED_REPLIES)
    }

    @Test fun aNewCopyKeptBeforeItsQuestionIsHeldStillHasAPlaceAndFindsItsTurnByIds() {
        val kept = keptWhileTheQuestionIsNotHeld()
        assertEquals(listOf(1, 2), kept.map { it.optInt("_segment") })
        assertTrue("never the words-only kind: ${kept.map { it.string("id") }}", kept.all { it.string("id").startsWith(KEPT_IN_TURN) })
        assertEquals("not bound to a question yet", "", kept[0].string("_history_user_row_id"))
        // Its question arrives, and Hermes' saved first message says the first sentence differently: the first copy goes by its place, the second stays.
        val rows = listOf(ask, saved(42002, "I will look at the file first", "c1"))
        assertEquals("42001", keptTurnPrompt(kept[0], rows))
        assertEquals(listOf("Now the command."), keptRowsNotSaved(rows, kept).map { it.string("text") })
        // Before the question is saved nothing is judged saved.
        assertEquals(2, keptRowsNotSaved(emptyList(), kept).size)
    }

    @Test fun aCopyKeptAfterAnOlderQuestionFindsTheNextQuestionNotTheOlderOne() {
        val older = json("row_id" to 41001, "role" to "user", "content" to "Earlier question")
        val olderReply = json("row_id" to 41002, "role" to "assistant", "content" to "Done.")
        val chat = ChatState(sessionId = "chat-7", streamId = "runtime-7", raw = json("session_id" to "chat-7", "messages" to JSONArray().put(older).put(olderReply)),
            liveText = "Done.", liveOrdinal = 2)
        // Non-own turn: the newest question the rows hold is the older one, so the copy is bound to it and its place counts behind the older reply (the live re-base rule).
        val kept = retainUnconfirmedReply(chat).raw.rows(UNCONFIRMED_REPLIES)
        assertTrue(kept.single().string("id").startsWith(KEPT_IN_TURN))
        assertEquals("41001", keptTurnPrompt(kept.single(), listOf(older, olderReply)))
        assertEquals("the older reply does not hold the new copy", 1, keptRowsNotSaved(listOf(older, olderReply), kept).size)
    }

    @Test fun theWordsFallbackMeetsOnlyACopyWithNoPlace() {
        fun kept(id: String) = Message(id, "assistant", "Done.", displayKind = "unconfirmed_reply", recordedAt = 5)
        val shown = listOf(user("40001", "Earlier"), said("40002", "Done."))
        // A copy this build made (place, question not found yet) is shown, even though an earlier reply says the same words.
        val withPlace = placeKeptReplies(shown, listOf(kept("${KEPT_IN_TURN}abc:0") to ""))
        assertEquals(setOf("40001", "40002", "${KEPT_IN_TURN}abc:0"), withPlace.map { it.id }.toSet())
        // A copy from an older frame (no place) is still told by its words.
        val noPlace = placeKeptReplies(shown, listOf(kept("unconfirmed:abc:0") to ""))
        assertEquals(setOf("40001", "40002"), noPlace.map { it.id }.toSet())
    }

    @Test fun aFinalThatComesWholeAfterAClosedSegmentTakesTheNextPlaceSoItsCopyHasOne() {
        var s = ChatState(sessionId = "chat-7", streamId = "runtime-7", raw = json("session_id" to "chat-7"))
        var boundary = LiveAssistantBoundary()
        fun event(type: String, payload: JSONObject) {
            val next = boundary.after(type, payload)
            s = liveAfterEvent(s, type, payload, boundary.closed, next) ?: s
            boundary = next
        }
        event("token", json("text" to "I will look at the file first."))
        event("tool", json("tid" to "c1", "name" to "terminal"))
        event("tool_complete", json("tid" to "c1", "name" to "terminal"))
        event("done", json("status" to "complete", "text" to "All done."))
        assertEquals(listOf("I will look at the file first."), s.liveSealed)
        assertEquals("the closed words and the final each have a place", 2, s.liveOrdinal)
        val kept = retainUnconfirmedReply(s).raw.rows(UNCONFIRMED_REPLIES)
        assertTrue("${kept.map { it.string("id") }}", kept.all { it.string("id").startsWith(KEPT_IN_TURN) })
        assertEquals(listOf(1, 2), kept.map { it.optInt("_segment") })
    }
}
