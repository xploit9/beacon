package dev.hermes.android.ui

import dev.hermes.android.data.VoiceKeyStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ChatScreenVoicePreparationTest {
    @Test fun voiceKeyDecryptionRunsAwayFromTheCallingThread() = runBlocking {
        val decryptThread = AtomicReference<Thread?>()
        val store = VoiceKeyStore(
            mutableMapOf(),
            encrypt = { bytes, _ -> bytes },
            decrypt = { bytes, _ -> decryptThread.set(Thread.currentThread()); bytes },
        )
        store.saveKey("gemini", "test-key")
        val caller = Thread.currentThread()

        assertTrue(voiceKeyConfiguredOnIo { store })
        assertNotSame(caller, decryptThread.get())
    }

    @Test fun storedToolResultsMatchTheirCallIdRowsOnly() {
        val messages = listOf(
            JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "call-1"))),
            JSONObject().put("role", "tool").put("tool_call_id", "call-1").put("content", "saved result"),
            JSONObject().put("role", "function").put("tool_call_id", "call-2").put("content", "legacy result"),
            JSONObject().put("role", "assistant").put("tool_call_id", "call-3"),
        )

        assertEquals(setOf("call-1", "call-2"), separateToolResultIds(messages))
    }

    @Test fun explicitToolCallIdTakesPrecedenceWhenLinkingAStoredResult() {
        val tool = JSONObject().put("id", "other-id").put("tool_call_id", "call-1")
        assertEquals("call-1", toolCallResultId(tool))
    }
}
