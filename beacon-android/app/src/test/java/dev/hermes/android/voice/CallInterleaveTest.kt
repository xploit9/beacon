package dev.hermes.android.voice

import dev.hermes.android.Message
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallInterleaveTest {
    private fun text(id: String, at: Long) = Message(id, "user", id, recordedAt = at)

    @Test fun savedCallTurnsSitBetweenTheMessagesAroundThem() {
        val messages = listOf(text("before", 1_000), text("after", 5_000))
        val calls = listOf(text("call:1", 2_000), text("call:2", 3_000), text("call:live", 0))
        assertEquals(listOf("before", "call:1", "call:2", "after", "call:live"), interleaveCallMessages(messages, calls).map { it.id })
    }

    @Test fun untimedRowsKeepTheOldOrder() {
        val messages = listOf(text("a", 0), text("b", 0))
        val calls = listOf(text("call:1", 2_000))
        assertEquals(listOf("a", "b", "call:1"), interleaveCallMessages(messages, calls).map { it.id })
    }

    @Test fun archivedTurnCarriesItsSavedTime() {
        val row = JSONObject().put("role", "user").put("event_id", "c1:user:t1").put("session_id", "s1").put("text", "Hi").put("created_at", 1_792_530_000.5)
        assertEquals(1_792_530_000_500L, CallTranscript.from(row, "s1", "c1")?.at)
    }

    @Test fun localCaptionFinalizationAndCorrectionKeepTheFirstCapturedWallTime() {
        val first = CallTranscript("c1:user:1", "user", "First words", final = false, at = 1_800_000_001_000L)
        val final = first.copy(text = "Final words", final = true, at = 1_800_000_004_000L)
        val corrected = final.copy(text = "Final corrected words", at = 1_800_000_009_000L)
        val call = emptyList<CallTranscript>().acceptTranscript(first).acceptTranscript(final).acceptTranscript(corrected).single()
        assertEquals(first.at, call.at)
        assertTrue(call.final)
        val later = text("image-after-call", first.at + 10_000)
        assertEquals(listOf("c1:user:1", later.id), interleaveCallMessages(listOf(later), listOf(text(call.eventId, call.at))).map { it.id })
    }
}
