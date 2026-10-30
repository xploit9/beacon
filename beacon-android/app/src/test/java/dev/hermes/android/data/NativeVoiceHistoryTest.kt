package dev.hermes.android.data
import dev.hermes.android.Message
import dev.hermes.android.core.*
import dev.hermes.android.voice.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeVoiceHistoryTest {
    private val scope = Scope("https://native.example", "owner", "work", "stored-a")
    private fun archive() = json("session_id" to scope.session, "profile" to scope.profile, "events" to JSONArray().put(json("call_id" to "call-a", "event_id" to "call-a:user:1", "session_id" to scope.session, "role" to "user", "text" to "Archived voice", "final" to true)))
    private fun providerEvent(item: String, turn: Int, text: String, callId: String = "call-a"): JSONObject {
        val wire = GrokWire()
        wire.inbound(json("type" to "input_audio_buffer.speech_started", "item_id" to item))
        wire.inbound(json("type" to "input_audio_buffer.committed", "item_id" to item))
        val input = requireNotNull(wire.inbound(json(
            "type" to "conversation.item.input_audio_transcription.completed", "event_id" to java.util.UUID.randomUUID().toString(),
            "item_id" to item, "content_index" to 0, "transcript" to text, "status" to "completed",
        ))).getJSONObject("serverContent").getJSONObject("inputTranscription")
        // DirectVoice forwards the provider item; BeaconCallService supplies its numeric turn.
        return requireNotNull(providerTranscript(json("type" to "transcript", "role" to "user",
            "provider_item" to input.getString("item_id"), "content_index" to input.getInt("content_index"),
            "text" to input.getString("text"), "final" to input.getBoolean("final"), "turn_id" to turn.toString(),
        ), callId, scope.session, 1000))
    }
    private fun savedEvents(vararg events: JSONObject): JSONObject = json(
        "session_id" to scope.session, "profile" to scope.profile, "truncated" to false,
        // Older plugin archives grouped receipts by call/role/turn and reconstructed this ID.
        "events" to JSONArray(events.map { event ->
            val callId = event.getString("event_id").substringBefore(':')
            json("call_id" to callId, "event_id" to "$callId:${event.getString("role")}:${event.getString("turn_id")}",
                "session_id" to scope.session, "turn_id" to event.getString("turn_id"), "role" to event.getString("role"),
                "text" to event.getString("text"), "final" to event.getBoolean("final"), "interrupted" to event.optBoolean("interrupted"),
                "at_ms" to event.getLong("at_ms"), "created_at" to 1_800_000_000.0 + event.getLong("at_ms") / 1000.0)
        }),
    )
    @Test fun completedGrokInputOverlappingItsArchiveAppearsOnceDuringAndAfterTheCall() {
        val event = providerEvent("input-one", 7, "Please say Grok ready")
        val saved = savedEvents(event)
        val archived = archivedVoiceTranscripts(scope, saved)
        val call = CallState(active = true, conversationScope = scope, callId = "call-a")
            .withTranscript(event, scope.session, "call-a")
        assertEquals("call-a:user:7", archived.single().eventId)
        assertNotEquals(archived.single().eventId, call.transcripts.single().eventId)
        for (state in listOf(call, call.copy(active = false, transcriptStatus = "saved"))) {
            val merged = mergeVoiceTranscripts(archived, state.transcriptsFor(scope, emptyList()), emptySet())
            assertEquals(1, merged.size)
            assertEquals("Please say Grok ready", merged.single().text)
            assertEquals(1_800_000_001_000L, merged.single().at)
            assertEquals(1, dev.hermes.android.ui.groupCallTranscriptEvents(merged).single().lines.size)
        }
        val correction = providerEvent("input-one", 7, "Please say Grok ready.")
        val corrected = call.withTranscript(correction, scope.session, "call-a")
        val merged = mergeVoiceTranscripts(archived, corrected.transcriptsFor(scope, emptyList()), emptySet())
        assertEquals(1, merged.size)
        assertEquals("Please say Grok ready.", merged.single().text)
        assertEquals(1_800_000_001_000L, merged.single().at)
        assertTrue(mergeVoiceTranscripts(archived, call.transcripts, setOf("call-a:user:7")).isEmpty())
        assertTrue(mergeVoiceTranscripts(archived, call.transcripts, setOf(call.transcripts.single().eventId)).isEmpty())
        val reopened = archivedVoiceTranscripts(scope, JSONObject(saved.toString()))
        assertEquals(listOf("Please say Grok ready"), mergeVoiceTranscripts(reopened, emptyList(), emptySet()).map { it.text })
    }
    @Test fun archiveOverlapPreservesRepeatedTurnsPendingTailInterruptionAndScope() {
        val first = providerEvent("input-one", 7, "Tell me found or not found")
        val repeated = providerEvent("input-two", 8, "Tell me found or not found")
        val interrupted = mutableListOf<JSONObject>()
        finishDirectTranscriptTurn("call-a", scope.session, 7, mutableMapOf("assistant" to "Checking"),
            1500, interrupted = true) { interrupted += it }
        val pending = directCaption(json("type" to "transcript", "role" to "assistant", "final" to false),
            "call-a", scope.session, 9, "Still checking")
        var call = CallState(active = true, conversationScope = scope, callId = "call-a", transcriptStatus = "partial")
        for (event in listOf(first, interrupted.single(), repeated, pending)) call = call.withTranscript(event, scope.session, "call-a")
        val archived = archivedVoiceTranscripts(scope, savedEvents(first, interrupted.single()))
        val merged = mergeVoiceTranscripts(archived, call.transcriptsFor(scope, emptyList()), emptySet())
        assertEquals(listOf("Tell me found or not found", "Checking", "Tell me found or not found", "Still checking"), merged.map { it.text })
        assertEquals(listOf("7", "7", "8", "9"), merged.map { it.turnId })
        assertEquals(merged, orderCallTranscriptLines(merged))
        val between = Message("text-row", "user", "A typed message", recordedAt = 1_800_000_001_250L)
        val interleaved = interleaveCallMessages(listOf(between), merged.map { Message(it.eventId, it.role, it.text, recordedAt = it.at) })
        assertEquals(listOf("Tell me found or not found", "A typed message", "Checking", "Tell me found or not found", "Still checking"), interleaved.map { it.text })
        assertTrue(merged[1].final && merged[1].interrupted)
        assertFalse(merged.last().final)
        // The service's stop fence finalizes its remaining caption before setting active=false.
        finishDirectTranscriptTurn("call-a", scope.session, 9, mutableMapOf("assistant" to "Still checking"),
            2000, interrupted = true) { call = call.withTranscript(it, scope.session, "call-a") }
        val ended = call.copy(active = false)
        val afterEnd = mergeVoiceTranscripts(archived, ended.transcriptsFor(scope, emptyList()), emptySet())
        assertEquals(merged.map { it.text }, afterEnd.map { it.text })
        assertTrue(afterEnd.last().final && afterEnd.last().interrupted)
        assertEquals("partial", ended.transcriptStatus)
        listOf(scope.copy(owner = "other"), scope.copy(origin = "https://other.example"),
            scope.copy(profile = "other"), scope.session("other")).forEach { foreign ->
            assertTrue(call.transcriptsFor(foreign, emptyList()).isEmpty())
        }
    }
    @Test fun distinctProviderItemsInTheSameNumericTurnSurviveArchiveOverlap() {
        val first = providerEvent("input-one", 7, "Yes")
        val second = providerEvent("input-two", 7, "Yes")
        val live = listOf(first, second).map { requireNotNull(CallTranscript.from(it, scope.session, "call-a")) }
        val archived = archivedVoiceTranscripts(scope, savedEvents(second))
        val merged = mergeVoiceTranscripts(archived, live, emptySet())
        assertEquals(2, merged.size)
        assertEquals(live.map { it.eventId }.toSet(), merged.map { it.eventId }.toSet())
        assertTrue(merged.all { it.text == "Yes" })
    }
    @Test fun matchingWordsAndTurnsInDifferentCallsAreIndependent() {
        val archived = archivedVoiceTranscripts(scope, savedEvents(providerEvent("input-one", 7, "Yes")))
        val live = requireNotNull(CallTranscript.from(providerEvent("input-one", 7, "Yes", "call-b"), scope.session, "call-b"))
        assertEquals(2, mergeVoiceTranscripts(archived, listOf(live), emptySet()).size)
    }
    @Test fun serializedSessionProjectionRestoresVoiceAfterProcessReopen() {
        val snapshot = json("session_id" to scope.session, "profile" to scope.profile, "voice_transcripts" to archive().optJSONArray("events"))
        val reopened = org.json.JSONObject(snapshot.toString())
        val rows = archivedVoiceTranscripts(scope, json("session_id" to reopened.string("session_id"), "profile" to reopened.string("profile"), "events" to reopened.optJSONArray("voice_transcripts")))
        assertEquals("Archived voice", mergeVoiceTranscripts(rows, emptyList(), emptySet()).single().text)
    }
    @Test fun finalDoesNotRegressAndLiveFinalReplacesPartialByStableEventId() {
        val partial = CallTranscript("call-a:user:1", "user", "par", false)
        val final = partial.copy(text = "final", final = true)
        assertEquals(listOf(final), mergeVoiceTranscripts(listOf(partial), listOf(final), emptySet()))
        assertEquals(listOf(final), mergeVoiceTranscripts(listOf(final), listOf(partial), emptySet()))
        assertTrue(mergeVoiceTranscripts(listOf(final), emptyList(), setOf(final.eventId)).isEmpty())
    }
    @Test fun untimedLiveTurnKeepsTheSavedTurnTime() {
        val saved = CallTranscript("call-a:user:1", "user", "saved", at = 1_800_000_000_000)
        val live = saved.copy(text = "updated", at = 0)
        assertEquals(saved.at, mergeVoiceTranscripts(listOf(saved), listOf(live), emptySet()).single().at)
    }
    @Test fun lateProviderCorrectionKeepsOriginalArchiveTurnAndTime() {
        val first = providerEvent("input-one", 3, "First").put("created_at", 1_800_000_001.0)
        val second = providerEvent("input-two", 4, "Second").put("created_at", 1_800_000_003.0)
        val archived = archivedVoiceTranscripts(scope, json("session_id" to scope.session, "profile" to scope.profile,
            "events" to JSONArray(listOf(first, second).map { JSONObject(it.toString()).put("call_id", "call-a") })))
        val corrected = providerEvent("input-one", 9, "First corrected").put("created_at", 1_800_000_009.0)
        val call = CallState(active = true, conversationScope = scope, callId = "call-a")
            .withTranscript(corrected, scope.session, "call-a")
        val merged = mergeVoiceTranscripts(archived, call.transcriptsFor(scope, emptyList()), emptySet())
        assertEquals(listOf("3", "4"), merged.map { it.turnId })
        assertEquals(archived.map { it.at }, merged.map { it.at })
        assertEquals(listOf("First corrected", "Second"), orderCallTranscriptLines(merged).map { it.text })
        val cold = archived.map { if (it.eventId == first.getString("event_id")) it.copy(text = "First corrected") else it }
        assertEquals(orderCallTranscriptLines(cold), orderCallTranscriptLines(merged))
        val typed = Message("typed", "user", "Typed between", recordedAt = 1_800_000_002_000L)
        assertEquals(listOf("First corrected", "Typed between", "Second"),
            interleaveCallMessages(listOf(typed), merged.map { Message(it.eventId, it.role, it.text, recordedAt = it.at) }).map { it.text })
    }
    @Test fun foreignProfileSessionAndRowAreRejected() {
        assertTrue(runCatching { archivedVoiceTranscripts(scope, archive().put("profile", "foreign")) }.isFailure)
        assertTrue(runCatching { archivedVoiceTranscripts(scope, archive().put("session_id", "foreign")) }.isFailure)
        val wrong = archive(); wrong.getJSONArray("events").getJSONObject(0).put("session_id", "foreign")
        assertTrue(runCatching { archivedVoiceTranscripts(scope, wrong) }.isFailure)
    }
}
