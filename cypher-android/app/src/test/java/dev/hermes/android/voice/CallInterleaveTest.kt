package dev.hermes.android.voice

import dev.hermes.android.Message
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
