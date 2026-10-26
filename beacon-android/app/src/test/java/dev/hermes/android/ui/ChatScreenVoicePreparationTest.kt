package dev.hermes.android.ui

import dev.hermes.android.Message
import dev.hermes.android.data.VoiceKeyStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun matchedToolResultsFoldIntoCallsAndUnmatchedStayVisible() {
        val messages = listOf(
            JSONObject().put("id", "assistant-1").put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "call-1"))),
            JSONObject().put("id", "result-1").put("role", "tool").put("tool_call_id", "call-1").put("content", "saved result"),
            JSONObject().put("id", "orphan-1").put("role", "function").put("tool_call_id", "missing-call").put("content", "legacy result"),
        )
        val cards = listOf(
            Message("assistant-1", "assistant", "", tools = listOf(JSONObject().put("id", "call-1"))),
            Message("result-1", "tool", "saved result"),
            Message("orphan-1", "function", "legacy result"),
        )
        val folded = foldToolResults(messages, cards)

        assertEquals(setOf("result-1"), folded.foldedMessageIds)
        assertEquals("saved result", folded.byCallId["call-1"]?.content)
        assertEquals(null, folded.byCallId["missing-call"])
        assertEquals(false, "orphan-1" in folded.foldedMessageIds)
    }

    @Test fun ambiguousOrImageToolResultsStayVisible() {
        val messages = listOf(
            JSONObject().put("id", "assistant-1").put("role", "assistant"),
            JSONObject().put("id", "twin-a").put("role", "tool").put("tool_call_id", "call-twin").put("content", "first"),
            JSONObject().put("id", "twin-b").put("role", "tool").put("tool_call_id", "call-twin").put("content", "second"),
            JSONObject().put("id", "image-result").put("role", "tool").put("tool_call_id", "call-image").put("content", "screenshot"),
        )
        val cards = listOf(
            Message("assistant-1", "assistant", "", tools = listOf(JSONObject().put("id", "call-twin"), JSONObject().put("id", "call-image"))),
            Message("twin-a", "tool", "first"),
            Message("twin-b", "tool", "second"),
            Message("image-result", "tool", "screenshot", attachments = listOf(JSONObject().put("kind", "image").put("url", "/api/media/shot.png"))),
        )
        val folded = foldToolResults(messages, cards)

        assertEquals(emptySet<String>(), folded.foldedMessageIds)
        assertEquals(emptyMap<String, FoldedToolResult>(), folded.byCallId)
    }

    @Test fun explicitToolCallIdTakesPrecedenceWhenLinkingAStoredResult() {
        val tool = JSONObject().put("id", "other-id").put("tool_call_id", "call-1")
        assertEquals("call-1", toolCallResultId(tool))
    }

    @Test fun toolCallMetaToolShowsTheToolHermesRan() {
        fun call(name: String, arguments: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("arguments", arguments))
        assertEquals("Beacon call user", toolDisplayName(call("tool_call", "{\"name\":\"beacon_call_user\",\"arguments\":{}}")))
        assertEquals("List calendar events · Personal", humanToolName("mcp__personal__list_calendar_events"))
        assertEquals("Search emails result", humanToolName("mcp__tool_gateway_meeting__search_emails", " result"))
        assertEquals("Terminal", humanToolName("terminal"))
        // Hermes reports only its own server-held keys; a phone-held key still allows the call.
        assertTrue(voiceCallsOffered(JSONObject().put("optional_features", JSONObject().put("plugin", "available")).put("voice", JSONObject().put("available", false))))
        assertFalse(voiceCallsOffered(JSONObject().put("optional_features", JSONObject().put("plugin", "absent"))))
        assertEquals("Tool describe", toolDisplayName(call("tool_describe", "{\"names\":[\"beacon_call_user\"]}")))
        assertEquals("Tool call", toolDisplayName(call("tool_call", "not json")))
        assertEquals("Tool call", toolDisplayName(call("tool_call", "{\"name\":\"<script>\"}")))
        assertEquals("Web search", toolDisplayName(JSONObject().put("name", "web_search")))
    }

    @Test fun toolResultRowsAreTitledAfterTheStoredToolName() {
        val rows = listOf(
            JSONObject().put("id", 19784).put("role", "tool").put("tool_name", "beacon_call_user"),
            JSONObject().put("id", 19776).put("role", "tool").put("tool_name", "hindsight_recall"),
            JSONObject().put("id", 19777).put("role", "tool").put("tool_name", "<b>x</b>"),
            JSONObject().put("id", 19778).put("role", "assistant").put("tool_name", "ignored"),
        )
        val titles = toolResultTitles(rows)
        assertEquals("Beacon call user result", titles["19784"])
        assertEquals("Hindsight recall result", titles["19776"])
        assertEquals(setOf("19784", "19776"), titles.keys)
    }
}
