package dev.hermes.android.voice

import dev.hermes.android.Message
import dev.hermes.android.core.json
import dev.hermes.android.core.Scope
import org.junit.Assert.*
import org.junit.Test

class CallTranscriptTest {
    @Test fun interruptedDirectCaptionStaysVisibleAsFinalAndCannotCompleteOrReplayTwice() {
        val captions=mutableMapOf("user" to "Check this", "assistant" to "I can see the")
        var live=listOf(CallTranscript("call:user:7","user","Check this",false),
            CallTranscript("call:assistant:7","assistant","I can see the",false,interrupted=true))
        assertEquals(1,live.pendingTranscripts(emptyList(),true).size)
        val archived=mutableListOf<org.json.JSONObject>()
        val next=finishDirectTranscriptTurn("call","session",7,captions,1200,interrupted=true) {
            archived+=it
            live=live.acceptTranscript(requireNotNull(CallTranscript.from(it,"session","call")))
        }
        assertEquals(8L,next);assertTrue(captions.isEmpty())
        val visible=live.pendingTranscripts(emptyList(),true)
        assertEquals(2,visible.size)
        val assistant=visible.single { it.role=="assistant" }
        assertEquals("I can see the",assistant.text);assertTrue(assistant.final);assertTrue(assistant.interrupted)
        assertEquals("Interrupted call transcript",dev.hermes.android.ui.archivedCallLabel(assistant.final,assistant.interrupted,true))
        assertFalse(visible.single { it.role=="user" }.interrupted)
        assertTrue(archived.all { it.getString("turn_id")=="7" })
        assertEquals(setOf("call:user:7","call:assistant:7"),archived.map { it.getString("event_id") }.toSet())
        assertEquals(next,finishDirectTranscriptTurn("call","session",next,captions,1300) { error("No replay after consumed interruption") })
        assertEquals(2,archived.size)
        assertEquals(live,live.acceptTranscript(assistant.copy(text="Late tokens",final=false,interrupted=false)))
    }
    @Test fun ordinaryDirectTurnUsesSameFinalPayloadWithoutInterruptedLabel() {
        val captions=mutableMapOf("assistant" to "Complete answer")
        var event:CallTranscript?=null
        assertEquals(4L,finishDirectTranscriptTurn("call","session",3,captions,900) { event=CallTranscript.from(it,"session","call") })
        assertTrue(event!!.final);assertFalse(event!!.interrupted)
        assertEquals("Call transcript",dev.hermes.android.ui.archivedCallLabel(event!!.final,event!!.interrupted,false))
    }

    @org.junit.Test fun canonicalFinalReplacesDifferentSpeechProgressIdWithoutDiscardingOtherRole() {
        val assistant = CallTranscript("call:assistant:speech", "assistant", "Working", final = false)
        val user = CallTranscript("call:user:progress", "user", "Question", final = false)
        val final = CallTranscript("call:assistant:item", "assistant", "Complete answer", messageId = "canonical")
        val result = listOf(assistant, user).acceptTranscript(final)
        org.junit.Assert.assertEquals(listOf(user, final), result)
        org.junit.Assert.assertTrue(result.acceptTranscript(final).count { it.eventId == final.eventId } == 1)
    }

    @Test fun savedVoiceTranscriptKeepsCypherAttributionInOrdinaryChats() {
        val voice = Message.from(json("role" to "assistant", "content" to "Hello",
            "display_metadata" to json("channel" to "voice")), 0, "ordinary")
        assertTrue(voice.voice)
        assertFalse(Message.from(json("role" to "assistant", "content" to "Hello"), 0).voice)
    }
    @Test fun fullTextProgressReplacesOneEventAndLatePartialCannotReplaceFinal() {
        val first = CallTranscript("call:assistant:1", "assistant", "Hel", false)
        val partials = emptyList<CallTranscript>().acceptTranscript(first).acceptTranscript(first.copy(text = "Hello"))
        assertEquals(listOf(first.copy(text = "Hello")), partials)
        val committed = partials.acceptTranscript(first.copy(text = "Hello there", final = true, messageId = "42"))
        assertEquals(committed, committed.acceptTranscript(first.copy(text = "stale")))
        assertEquals(1, committed.size)
    }

    @Test fun finalReconcilesByDurableIdentityWithoutCollapsingRepeatedWords() {
        val live = listOf(CallTranscript("call:user:1", "user", "Yes", messageId = "42"))
        assertEquals(live, live.pendingTranscripts(listOf(Message("41", "user", "Yes")), true))
        assertTrue(live.pendingTranscripts(listOf(Message("42", "user", "Yes")), true).isEmpty())
        assertTrue(live.pendingTranscripts(listOf(Message("call:user:1", "user", "Yes")), true).isEmpty())
    }

    @Test fun canceledAndEndedProjectionsNeverSurviveAsCompletedAnswers() {
        val progress = CallTranscript("call:assistant:1", "assistant", "Unfinished", false)
        assertTrue(listOf(progress).pendingTranscripts(emptyList(), false).isEmpty())
        val canceled = listOf(progress).acceptTranscript(progress.copy(text = "", final = true, interrupted = true))
        assertTrue(canceled.pendingTranscripts(emptyList(), true).isEmpty())
        assertEquals(canceled, canceled.acceptTranscript(progress.copy(text = "Late canceled tokens")))
        val interrupted = progress.copy(final = true, messageId = "42", interrupted = true)
        assertEquals(listOf(interrupted), listOf(interrupted).pendingTranscripts(emptyList(), false))
    }

    @Test fun wireRequiresCanonicalSessionAndKnownRoleAndDefaultsToCommitted() {
        val wire = json("event_id" to "call:user:1", "role" to "user", "text" to "Hello", "session_id" to "canonical", "message_id" to 42)
        val event = requireNotNull(CallTranscript.from(wire, "canonical", "call"))
        assertTrue(event.final); assertEquals("42", event.messageId)
        assertNull(CallTranscript.from(wire, "other", "call"))
        assertNull(CallTranscript.from(wire, "canonical", "new-call"))
        assertNull(CallTranscript.from(wire.put("event_id", "call:assistant:1"), "canonical", "call"))
        assertNull(CallTranscript.from(wire.put("role", "tool"), "canonical", "call"))
    }

    @Test fun projectionCannotCrossOwnerOriginProfileOrCanonicalSession() {
        val scope = Scope("https://fixture.example", "owner", "default", "canonical")
        val event = CallTranscript("call:user:1", "user", "Private progress", false)
        val call = CallState(active = true, conversationScope = scope, transcripts = listOf(event))
        assertEquals(listOf(event), call.transcriptsFor(scope, emptyList()))
        listOf(scope.copy(owner = "other"), scope.copy(profile = "other"), scope.copy(origin = "https://other.example"), scope.session("other")).forEach { wrong -> assertTrue(call.transcriptsFor(wrong, emptyList()).isEmpty()) }
        assertTrue(call.transcriptsFor(null, emptyList()).isEmpty())
    }

    @Test fun durableInterruptedMetadataRemainsVisibleAfterReopen() {
        val row = json("id" to 42, "role" to "assistant", "content" to "Partial answer", "display_metadata" to json("interrupted" to true))
        assertTrue(Message.from(row, 0, "canonical").interrupted)
    }

    @Test fun canceledProgressBeforeFirstTextCannotReappearUntilDurableFinal() {
        val canceled = CallTranscript("call:assistant:1", "assistant", "Canceled before display", false, interrupted = true)
        val tombstone = emptyList<CallTranscript>().acceptTranscript(canceled)
        assertTrue(tombstone.pendingTranscripts(emptyList(), true).isEmpty())
        assertEquals(tombstone, tombstone.acceptTranscript(canceled.copy(text = "Late tokens", interrupted = false)))
        val committed = canceled.copy(final = true, messageId = "42")
        assertEquals(listOf(committed), tombstone.acceptTranscript(committed).pendingTranscripts(emptyList(), true))
    }
}
