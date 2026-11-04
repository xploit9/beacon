package dev.hermes.android.push

import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import dev.hermes.android.data.NativeTurnNotSent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/** Synthetic ids only. Reply from the notification (4 Nov): what may be answered, to which chat, and what the notice may claim afterwards. */
class NotificationReplyTest {
    private val scope = Scope("https://hermes.example.com", "owner-1", "work", nativeProvider = "fixture")
    private fun route(kind: String = "completion", session: String = "20261104_090000_abc123", conversation: String = "conv-ref-1", profile: String = "work", owner: String = "owner-1", source: String = "source-ref-1", event: String = "event-1") =
        json("kind" to kind, "session_id" to session, "conversation_ref" to conversation, "profile" to profile, "owner_id" to owner, "source_ref" to source, "event_id" to event)

    @Test fun aChatReplyAndAPostThatLandedInAChatCanBeAnswered() {
        assertTrue(noticeAcceptsReply(route("completion")))
        assertTrue(noticeAcceptsReply(route("task_result")))
    }

    @Test fun nothingElseOffersReply() {
        for (kind in listOf("approval", "clarify", "failure", "working", "test", "incoming_call", "activity_end", "")) assertFalse(kind, noticeAcceptsReply(route(kind)))
        assertFalse("no chat named", noticeAcceptsReply(route(session = "")))
        assertFalse("a chat id that is not an opaque id", noticeAcceptsReply(route(session = "../other/chat")))
        assertFalse("no conversation reference", noticeAcceptsReply(route(conversation = "")))
    }

    @Test fun theTypedTextIsTrimmedAndNeverEmptyOrOverTheAppsLimit() {
        assertEquals("on my way", cleanReply("  on my way \n"))
        assertEquals("two\nlines", cleanReply("two\nlines"))
        assertNull(cleanReply(null))
        assertNull(cleanReply("   \n\t "))
        // Control characters are dropped, line breaks and tabs stay.
        assertEquals("ab", cleanReply("a\u0007b"))
        assertEquals("a\tb", cleanReply("a\tb"))
        assertEquals(REPLY_TEXT_LIMIT, cleanReply("x".repeat(REPLY_TEXT_LIMIT))?.length)
        assertNull(cleanReply("x".repeat(REPLY_TEXT_LIMIT + 1)))
    }

    @Test fun theChatIsTheOneHermesNamesForThisNoticeOfThisOwnerAndProfile() {
        assertEquals("20261104_090000_abc123", replySession(route(), "source-ref-1", "event-1", scope))
        assertEquals("a task post lands in its chat too", "20261104_090000_abc123", replySession(route("task_result"), "source-ref-1", "event-1", scope))
    }

    @Test fun aRouteOfAnotherNoticeOwnerProfileOrKindNamesNoChat() {
        assertNull("another source", replySession(route(source = "source-ref-2"), "source-ref-1", "event-1", scope))
        assertNull("another event", replySession(route(event = "event-2"), "source-ref-1", "event-1", scope))
        assertNull("another owner", replySession(route(owner = "owner-2"), "source-ref-1", "event-1", scope))
        assertNull("another profile", replySession(route(profile = "home"), "source-ref-1", "event-1", scope))
        assertNull("a question is answered in its own card, not by reply", replySession(route("clarify"), "source-ref-1", "event-1", scope))
        assertNull("no chat", replySession(route(session = ""), "source-ref-1", "event-1", scope))
        assertNull("an unsafe chat id", replySession(route(session = "a/b"), "source-ref-1", "event-1", scope))
    }

    @Test fun theMessageIsStartedInTheSameWayTheComposerStartsIt() {
        val body = replyStartBody("chat-1", "on my way", "work", "request-1")
        assertEquals("chat-1", body.getString("session_id"))
        assertEquals("on my way", body.getString("message"))
        assertEquals("work", body.getString("profile"))
        assertEquals("request-1", body.getString("client_request_id"))
        assertEquals(0, body.getJSONArray("attachments").length())
        // No model pick: the chat keeps the model it has.
        assertFalse(body.has("model")); assertFalse(body.has("model_provider"))
    }

    @Test fun onlyAnAcceptedStartIsSent() {
        assertEquals(ReplyState.Sent, replyOutcome(JSONObject().put("accepted", true).put("status", "started")))
        assertEquals(ReplyState.Sent, replyOutcome(JSONObject().put("accepted", true).put("status", "queued")))
        assertEquals(ReplyState.Unsure, replyOutcome(JSONObject().put("accepted", false).put("status", "unknown")))
        assertEquals(ReplyState.Unsure, replyOutcome(JSONObject()))
    }

    @Test fun aFailureThatCannotHaveSentMayBeRetriedAndALateOneMustNotBe() {
        assertEquals("before the hand-over", ReplyState.NotSent, replyOutcomeFor(IOException("boom"), attempted = false))
        assertEquals(ReplyState.NotSent, replyOutcomeFor(NativeTurnNotSent("The conversation could not be prepared."), attempted = true))
        assertEquals("never connected", ReplyState.NotSent, replyOutcomeFor(ConnectException("refused"), attempted = true))
        assertEquals(ReplyState.NotSent, replyOutcomeFor(SocketTimeoutException("connect timed out"), attempted = true))
        assertEquals("a lost answer after the hand-over", ReplyState.Unsure, replyOutcomeFor(IOException("stream closed"), attempted = true))
        assertEquals(ReplyState.Unsure, replyOutcomeFor(SocketTimeoutException("timeout"), attempted = true))
    }

    @Test fun aSecondPreviewRefreshDoesNotAddAnotherReply() {
        assertFalse(carriesReply(emptyList()))
        assertFalse(carriesReply(listOf(null, "answer")))
        assertTrue(carriesReply(listOf("answer", REPLY_KEY)))
    }

    @Test fun theAnswerReplacesTheSentNoticeOfItsOwnChatOnly() {
        val shown = listOf(
            ShownNotice("sent-here", 1, "conv-ref-1", replySent = true),
            ShownNotice("sent-elsewhere", 1, "conv-ref-2", replySent = true),
            ShownNotice("plain-here", 1, "conv-ref-1", replySent = false),
            ShownNotice("sent-here-call", 2, "conv-ref-1", replySent = true),
            ShownNotice("new", 1, "conv-ref-1", replySent = true),
        )
        assertEquals(listOf("sent-here"), supersededByAnswer(shown, "conv-ref-1", "new").map { it.tag })
        assertTrue("an invalid reference retires nothing", supersededByAnswer(shown, "../x", "new").isEmpty())
        assertTrue(supersededByAnswer(shown, "", "new").isEmpty())
    }

    @Test fun everyReplyLineIsAWrittenSentence() {
        assertEquals(ReplyStrings.all.size, ReplyStrings.all.toSet().size)
        assertTrue(ReplyStrings.all.all { it.isNotBlank() && it == it.trim() })
        // The two outcomes the owner reads, word for word.
        assertEquals("Sent", ReplyStrings.SENT)
        assertEquals("Couldn't send. Open Beacon to try again.", ReplyStrings.NOT_SENT)
        assertNotNull(ReplyStrings.SENDING)
    }
}
