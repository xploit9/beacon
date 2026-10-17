package dev.hermes.android.data
import dev.hermes.android.core.*
import dev.hermes.android.voice.CallTranscript
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativeVoiceHistoryTest {
    private val scope = Scope("https://native.example", "owner", "work", "stored-a")
    private fun archive() = json("session_id" to scope.session, "profile" to scope.profile, "events" to JSONArray().put(json("call_id" to "call-a", "event_id" to "call-a:user:1", "session_id" to scope.session, "role" to "user", "text" to "Archived voice", "final" to true)))
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
    @Test fun foreignProfileSessionAndRowAreRejected() {
        assertTrue(runCatching { archivedVoiceTranscripts(scope, archive().put("profile", "foreign")) }.isFailure)
        assertTrue(runCatching { archivedVoiceTranscripts(scope, archive().put("session_id", "foreign")) }.isFailure)
        val wrong = archive(); wrong.getJSONArray("events").getJSONObject(0).put("session_id", "foreign")
        assertTrue(runCatching { archivedVoiceTranscripts(scope, wrong) }.isFailure)
    }
}
