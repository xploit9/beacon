package dev.hermes.android

import dev.hermes.android.ui.placeKeptReplies
import dev.hermes.android.core.*
import dev.hermes.android.ui.messageTimeLabel
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray

/** A streamed reply Beacon keeps because it could not prove its turn belongs to that turn alone (review N1). Synthetic ids only. */
class KeptReplyTurnTest {
    @Test fun noKeptRepliesLeavesTheTranscriptUntouched() {
        val shown = listOf(user(10, "First"), assistant(11, "Reply"))
        assertSame(shown, placeKeptReplies(shown, emptyList()))
    }

    @Test fun aKeptReplyRemembersThePromptRowItAnswers() {
        val chat = ChatState(sessionId = "chat", streamId = "runtime-1", liveText = "Streamed answer",
            messages = listOf(Message("10", "user", "First"), Message("11", "assistant", "Old"), Message("20", "user", "Second"), Message("21", "user", "Change course", displayKind = "steer")))
        val kept = retainUnconfirmedReply(chat).raw.getJSONArray("_unconfirmed_replies").getJSONObject(0)
        assertEquals("a steer is not the prompt", "20", kept.optString("_history_user_row_id"))
    }

    @Test fun aKeptReplyWithNoStableRowIdIsLeftUnbound() {
        val chat = ChatState(sessionId = "chat", streamId = "runtime-1", liveText = "Streamed answer", messages = listOf(Message("user:0", "user", "First")))
        assertEquals("", retainUnconfirmedReply(chat).raw.getJSONArray("_unconfirmed_replies").getJSONObject(0).optString("_history_user_row_id"))
    }

    private fun user(id: Long, text: String, kind: String = "") = Message("$id", "user", text, displayKind = kind)
    private fun assistant(id: Long, text: String) = Message("$id", "assistant", text)
    private fun kept(text: String) = Message("unconfirmed:$text", "assistant", text, displayKind = "unconfirmed_reply")
    private fun ids(list: List<Message>) = list.map { it.id }

    @Test fun anOlderKeptReplyStaysHiddenOnceItsTurnsSavedReplyIsInHistoryEvenAfterTheNextQuestion() {
        val shown = listOf(user(10, "First"), assistant(11, "Done."), user(30, "Second"), assistant(31, "Another answer"))
        assertEquals(ids(shown), ids(placeKeptReplies(shown, listOf(kept("Done.") to "10"))))
    }

    @Test fun anUnsavedKeptReplySitsAtTheEndOfItsOwnTurnAboveALaterQuestion() {
        val shown = listOf(user(10, "First"), assistant(11, "Part"), user(30, "Second"), assistant(31, "Answer"))
        val placed = placeKeptReplies(shown, listOf(kept("Lost reply") to "10"))
        assertEquals(listOf("10", "11", "unconfirmed:Lost reply", "30", "31"), ids(placed))
    }

    @Test fun theKeptReplyOfTheNewestTurnStaysAtTheEndAndAbovePendingQuestions() {
        val shown = listOf(user(10, "First"), assistant(11, "Part"))
        assertEquals(listOf("10", "11", "unconfirmed:Streamed"), ids(placeKeptReplies(shown, listOf(kept("Streamed") to "10"))))
        val withPending = shown + Message("queued:local", "user", "Next", displayKind = "local_input")
        assertEquals(listOf("10", "11", "unconfirmed:Streamed", "queued:local"), ids(placeKeptReplies(withPending, listOf(kept("Streamed") to "10"))))
    }

    @Test fun anOlderIdenticalReplyStillDoesNotHideANewReplyThatWasNotSaved() {
        val shown = listOf(user(10, "First"), assistant(11, "Done."), user(30, "Second"))
        assertEquals(listOf("10", "11", "30", "unconfirmed:Done."), ids(placeKeptReplies(shown, listOf(kept("Done.") to "30"))))
        val saved = shown + assistant(31, "Done.")
        assertEquals("its own turn's saved reply does hide it", ids(saved), ids(placeKeptReplies(saved, listOf(kept("Done.") to "30"))))
    }

    @Test fun aSteerDoesNotEndTheTurnItIsPartOf() {
        val shown = listOf(user(10, "First"), assistant(11, "Looking"), user(12, "Change course", "steer"), assistant(13, "The answer"))
        assertEquals(ids(shown), ids(placeKeptReplies(shown, listOf(kept("The answer") to "10"))))
    }

    @Test fun aReplyKeptByAnOlderBuildIsJudgedAgainstTheNewestTurnOnly() {
        val shown = listOf(user(10, "First"), assistant(11, "Done."), user(30, "Second"), assistant(31, "Done."))
        assertEquals(ids(shown), ids(placeKeptReplies(shown, listOf(kept("Done.") to ""))))
        val unsaved = listOf(user(10, "First"), assistant(11, "Done."), user(30, "Second"))
        assertEquals(listOf("10", "11", "30", "unconfirmed:Done."), ids(placeKeptReplies(unsaved, listOf(kept("Done.") to ""))))
    }

    @Test fun aReplyWhoseTurnIsGoneFromTheLoadedHistoryIsNotShownButAnEmptyHistoryKeepsIt() {
        val shown = listOf(user(50, "Later"), assistant(51, "Answer"))
        assertEquals(ids(shown), ids(placeKeptReplies(shown, listOf(kept("Old") to "10"))))
        assertEquals(listOf("unconfirmed:Old"), ids(placeKeptReplies(emptyList(), listOf(kept("Old") to "10"))))
    }

    @Test fun severalKeptRepliesKeepTheirOrderWithinOneTurn() {
        val shown = listOf(user(10, "First"), user(30, "Second"))
        assertEquals(listOf("10", "unconfirmed:a", "unconfirmed:b", "30"), ids(placeKeptReplies(shown, listOf(kept("a") to "10", kept("b") to "10"))))
    }

    // Owner, 25 Nov: a reply kept in the running turn came back above a newer question with no time, so it drew below every newer message and hid the
    // new message and its answer. A copy the older build kept with no time must keep its own turn, and it must show a time.
    @Test fun aKeptReplyWithNoTimeStaysAboveANewerMessageAndShowsThatTurnsTime() {
        val shown = listOf(user(10, "First").at(1_700_000_000_000L), assistant(11, "Part").at(1_700_000_020_000L),
            user(30, "Second").at(1_700_000_600_000L), assistant(31, "Answer").at(1_700_000_620_000L))
        // The row Hermes saved was dropped from a partial read, so Beacon kept it as unconfirmed-history:21 with no time (1.2.4 and earlier).
        val lost = Message("unconfirmed-history:21", "assistant", "Part", displayKind = "unconfirmed_reply")
        val placed = placeKeptReplies(shown, listOf(lost to ""))
        assertEquals("it stays with its own turn, never under the newer answer",
            listOf("10", "11", "unconfirmed-history:21", "30", "31"), ids(placed))
        val reply = placed.single { it.id == "unconfirmed-history:21" }
        assertEquals("its bubble shows the time of the message it sits after", 1_700_000_020_000L, reply.recordedAt)
        assertTrue("never a blank time", messageTimeLabel(reply.recordedAt, java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.US)!!.isNotBlank())
    }

    @Test fun aBoundKeptReplyWithNoTimeAlsoGetsTheTimeOfItsTurn() {
        val shown = listOf(user(10, "First").at(1_700_000_000_000L), assistant(11, "Part").at(1_700_000_020_000L),
            user(30, "Second").at(1_700_000_600_000L), assistant(31, "Answer").at(1_700_000_620_000L))
        val placed = placeKeptReplies(shown, listOf(kept("Lost reply") to "10"))
        assertEquals(listOf("10", "11", "unconfirmed:Lost reply", "30", "31"), ids(placed))
        assertEquals(1_700_000_020_000L, placed.single { it.id == "unconfirmed:Lost reply" }.recordedAt)
    }

    @Test fun aKeptReplyRebuiltFromASavedRowWithNoTimeStaysWithThatRowNotAtTheBottom() {
        val shown = listOf(user(10, "First").at(1_700_000_000_000L), assistant(11, "Part").at(1_700_000_020_000L),
            user(30, "Second").at(1_700_000_600_000L), assistant(31, "Answer").at(1_700_000_620_000L))
        val history = Message("unconfirmed-history:21", "assistant", "Part", displayKind = "unconfirmed_reply")
        val placed = placeKeptReplies(shown, listOf(history to ""))
        assertEquals("20 is gone from this window, so its saved row sits after 11, never under 31", listOf("10", "11", "unconfirmed-history:21", "30", "31"), ids(placed))
        assertEquals(1_700_000_020_000L, placed.single { it.id == "unconfirmed-history:21" }.recordedAt)
    }

    @Test fun timedKeptRepliesKeepTodayOrder() {
        val shown = listOf(user(10, "First").at(1_700_000_000_000L), user(30, "Second").at(1_700_000_600_000L))
        val early = kept("early").at(1_700_000_100_000L)
        val late = kept("late").at(1_700_000_700_000L)
        // A copy with a time is placed by that time, exactly as before: it interleaves, it is not collected at the end.
        assertEquals(listOf("10", "unconfirmed:early", "30", "unconfirmed:late"), ids(placeKeptReplies(shown, listOf(late to "", early to ""))))
    }

    private fun Message.at(millis: Long) = copy(recordedAt = millis)

    // The retained reply is a message of its turn: it carries that turn's clock, so Message.from reads a real recordedAt and BubbleMeta draws the time.
    @Test fun aRetainedReplyIsWrittenWithItsTurnsTime() {
        val asked = json("row_id" to 10, "role" to "user", "content" to "First", "timestamp" to 1_700_000_000.0)
        val chat = ChatState(sessionId = "chat", streamId = "runtime-1", liveText = "Streamed answer",
            raw = json("messages" to JSONArray().put(asked)))
        val kept = retainUnconfirmedReply(chat).raw.getJSONArray("_unconfirmed_replies").getJSONObject(0)
        assertEquals("the turn's own start", 1_700_000_000_000L, kept.optLong("timestamp"))
        assertEquals(1_700_000_000_000L, Message.from(kept, 0, "chat").recordedAt)
    }

    @Test fun aRetainedReplyKeepsItsOwnTimeWhenKeptAgain() {
        val asked = json("row_id" to 10, "role" to "user", "content" to "First", "timestamp" to 1_700_000_000.0)
        val first = retainUnconfirmedReply(ChatState(sessionId = "chat", streamId = "runtime-1", liveText = "Streamed answer",
            raw = json("messages" to JSONArray().put(asked))))
        val keptAt = first.raw.rows("_unconfirmed_replies").single().optLong("timestamp")
        // A second keep of the same words, with the clock far ahead, leaves that copy's time alone.
        val again = retainUnconfirmedReply(first.copy(liveText = "Streamed answer"))
        val kept = again.raw.rows("_unconfirmed_replies").single()
        assertEquals(1, again.raw.rows("_unconfirmed_replies").size)
        assertEquals(keptAt, kept.optLong("timestamp"))
    }

    @Test fun aRetainedReplyKeptBeforeItsQuestionIsARowStillNamesItsTurnForItsTime() {
        // The phone's own send is already accepted but its question is not a held row yet: the send's own boundary keeps the time.
        val chat = ChatState(sessionId = "chat", streamId = "runtime-1", liveText = "Streamed answer",
            queued = listOf(QueuedMessage("First", id = "local", submissionId = "sub-1", createdAt = 1_700_000_500_000L)),
            raw = json("_display_submission_id" to "sub-1"))
        val kept = retainUnconfirmedReply(chat).raw.rows("_unconfirmed_replies").single()
        assertEquals(1_700_000_500_000L, kept.optLong("timestamp"))
    }

    @Test fun aSavedReplyRowWithNoTimeKeepsTheTurnBoundaryInsteadOfNone() {
        val asked = json("row_id" to 10, "role" to "user", "content" to "First", "timestamp" to 1_700_000_000.0)
        val reply = json("row_id" to 11, "role" to "assistant", "content" to "Answer")
        val before = json("session_id" to "chat", "profile" to "default", "messages" to JSONArray().put(asked).put(reply))
        val after = mergeChatProjection(before, json("session_id" to "chat", "profile" to "default",
            "messages" to JSONArray().put(asked)))
        val kept = after.rows("_unconfirmed_replies").single()
        assertTrue("a retained reply always has a clock", kept.optLong("timestamp") > 0L)
        assertEquals(Message.from(kept, 0, "chat").recordedAt, kept.optLong("timestamp"))
    }
}
