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

    @Test fun toolCallMetaToolShowsTheToolHermesRan() {
        fun call(name: String, arguments: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("arguments", arguments))
        assertEquals("cypher call user", toolDisplayName(call("tool_call", "{\"name\":\"cypher_call_user\",\"arguments\":{}}")))
        assertEquals("tool describe", toolDisplayName(call("tool_describe", "{\"names\":[\"cypher_call_user\"]}")))
        assertEquals("tool call", toolDisplayName(call("tool_call", "not json")))
        assertEquals("tool call", toolDisplayName(call("tool_call", "{\"name\":\"<script>\"}")))
        assertEquals("web search", toolDisplayName(JSONObject().put("name", "web_search")))
    }

    @Test fun toolResultRowsAreTitledAfterTheStoredToolName() {
        val rows = listOf(
            JSONObject().put("id", 19784).put("role", "tool").put("tool_name", "cypher_call_user"),
            JSONObject().put("id", 19776).put("role", "tool").put("tool_name", "hindsight_recall"),
            JSONObject().put("id", 19777).put("role", "tool").put("tool_name", "<b>x</b>"),
            JSONObject().put("id", 19778).put("role", "assistant").put("tool_name", "ignored"),
        )
        val titles = toolResultTitles(rows)
        assertEquals("Cypher call user result", titles["19784"])
        assertEquals("Hindsight recall result", titles["19776"])
        assertEquals(setOf("19784", "19776"), titles.keys)
    }
}
