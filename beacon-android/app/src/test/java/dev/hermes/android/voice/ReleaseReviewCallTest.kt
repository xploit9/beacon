package dev.hermes.android.voice

import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ReleaseReviewCallTest {
    @Test fun liveCaptionStaysInItsTurnAfterThePreviousAssistantReply() {
        val previous = CallTranscript("call:assistant:0", "assistant", "First reply", turnId = "0")
        val live = directCaption(json("type" to "transcript", "role" to "user", "final" to false), "call", "chat", 1, "Next question")
        val partial = checkNotNull(CallTranscript.from(live, "chat", "call"))
        assertEquals("1", partial.turnId)
        assertEquals(listOf(previous, partial), orderCallTranscriptLines(listOf(previous, partial)))
    }

    @Test fun failureRedirectIsClaimedOncePerCallAcrossChatReentry() {
        val redirects = FailedCallRedirect()
        val failed = CallState(callId = "failed-a", failureReason = "Bad key",
            conversationScope = Scope("https://fixture.invalid", "owner", "default", "chat"))
        assertFalse(redirects.claim(failed.copy(failureReason = "")))
        assertTrue(redirects.claim(failed))
        assertFalse(redirects.claim(failed))
        assertFalse(redirects.claim(failed.copy(failureReason = "Another reason")))
        assertTrue(redirects.claim(failed.copy(callId = "failed-b")))
    }

    @Test fun stopFenceFinalizesTheLastLocalWordsAfterClosing() {
        val closed = AtomicBoolean(false)
        var state = CallState(callId = "call")
        val captions = mutableMapOf("user" to "Last question", "assistant" to "Last answer")
        assertTrue(finishCallOnce(closed, {
            assertTrue(closed.get())
            finishDirectTranscriptTurn("call", "chat", 2, captions, 10, interrupted = true) {
                state = state.withTranscript(it, "chat", "call")
            }
        }) {})
        assertEquals(listOf("Last question", "Last answer"), state.transcripts.map { it.text })
        assertTrue(state.transcripts.all { it.final })
        assertTrue(state.transcripts.last().interrupted)
        assertTrue(captions.isEmpty())
        assertFalse(finishCallOnce(closed) { fail("Teardown must remain once-only") })
        assertSame(state, state.withTranscript(json("type" to "transcript", "session_id" to "foreign", "event_id" to "call:user:3"), "chat", "call"))
    }

    @Test fun navigationLeavesTheMeasuredMinimumContentWidth() {
        assertFalse(persistentCallNavigation(720f, 1f))
        assertTrue(persistentCallNavigation(persistentCallNavigationWidthDp + 420f, 1f))
        assertFalse(persistentCallNavigation(persistentCallNavigationWidthDp + 419f, 1f))
        assertTrue(persistentCallNavigation(persistentCallNavigationWidthDp + 420f * 1.4f, 1.4f))
    }
}
