package dev.hermes.android

import dev.hermes.android.ui.placeKeptReplies
import org.junit.Assert.*
import org.junit.Test

/** A streamed reply Beacon keeps because it could not prove its turn belongs to that turn alone (review N1). Synthetic ids only. */
class KeptReplyTurnTest {
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
}
