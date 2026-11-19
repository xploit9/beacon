package dev.hermes.android.chatqa

import dev.hermes.android.core.json
import dev.hermes.android.historyStopsInsideATurn
import dev.hermes.android.observedRunningTurn
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CHATQA-121 follow-up 3: when a chat may still have a reply running (its history), and which reply Hermes' live inventory says runs there. */
class ObservedTurnTest {
    private fun row(role: String, vararg extra: Pair<String, Any?>) = json("role" to role, *extra)

    @Test fun aHistoryStopsInsideATurnByHermesOwnRule() {
        assertTrue("the person's message is the newest row", historyStopsInsideATurn(listOf(row("assistant"), row("user"))))
        assertTrue("a tool's result is the newest row", historyStopsInsideATurn(listOf(row("user"), row("assistant", "tool_calls" to JSONArray().put(json("id" to "call-1"))), row("tool"))))
        assertTrue("an assistant row whose tool call is not answered yet", historyStopsInsideATurn(listOf(row("user"), row("assistant", "tool_calls" to JSONArray().put(json("id" to "call-1"))))))
        assertFalse("a reply ends it", historyStopsInsideATurn(listOf(row("user"), row("assistant"))))
        assertFalse("a failed turn's notice ends it", historyStopsInsideATurn(listOf(row("user"), row("assistant", "display_kind" to "failed_turn"))))
        assertFalse("an empty chat", historyStopsInsideATurn(emptyList()))
        assertFalse("rows that are not the conversation do not count", historyStopsInsideATurn(listOf(row("user"), row("assistant"), row("system"), row("session_meta"))))
    }

    private fun inventory(vararg rows: JSONObject) = json("sessions" to JSONArray(rows.toList()))
    private fun live(id: String, key: String, status: String) = json("id" to id, "session_key" to key, "status" to status)

    @Test fun onlyAStartedTurnInTheChatIsFollowed() {
        // Hermes with session authority: id and session_key are both the chat (gateway/session_active_list.py).
        assertEquals("chat-1", observedRunningTurn(inventory(live("chat-1", "chat-1", "working"), live("chat-2", "chat-2", "idle")), "chat-1"))
        assertEquals("waiting for the person still runs", "chat-1", observedRunningTurn(inventory(live("chat-1", "chat-1", "waiting")), "chat-1"))
        // An older gateway lists the runtime as id and the stored chat as session_key (tui_gateway session_registry).
        assertEquals("runtime-7", observedRunningTurn(inventory(live("runtime-7", "chat-1", "working")), "chat-1"))
        for (status in listOf("idle", "starting", "unknown", "")) assertNull(status, observedRunningTurn(inventory(live("chat-1", "chat-1", status)), "chat-1"))
        assertNull("another chat's turn", observedRunningTurn(inventory(live("chat-2", "chat-2", "working")), "chat-1"))
        assertNull("two rows name the chat", observedRunningTurn(inventory(live("runtime-7", "chat-1", "working"), live("runtime-8", "chat-1", "idle")), "chat-1"))
        assertNull("an id that is no stream id", observedRunningTurn(inventory(live("runtime 7", "chat-1", "working")), "chat-1"))
        assertNull("no inventory", observedRunningTurn(null, "chat-1"))
        assertNull("an inventory without rows", observedRunningTurn(JSONObject(), "chat-1"))
    }
}
