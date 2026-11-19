package dev.hermes.android.data

import dev.hermes.android.core.json
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WEDGE-121 follow-ups 3 and 4: the phone's read of Beacon's read-only lost-turn route, and its Discard of a lone lost reply. Synthetic ids only. */
class LostTurnRouteTest {
    private fun unknown(vararg turns: Pair<String, Any?>) = JSONArray().apply { turns.forEach { (id, generation) -> put(json("admission_id" to id, "execution_generation" to generation)) } }

    @Test fun theRouteAnswerCountsOnlyTheAskedChatsWithWhatDiscardTakes() {
        val answer = json("lost" to json(
            "chat-a" to json("waiting" to 2, "unknown" to unknown("lost-a" to 8)),
            "chat-b" to json("waiting" to 0, "unknown" to unknown("lost-b" to 3, "" to 4, "lost-bad" to null, "lost-neg" to -1)),
            "chat-x" to json("waiting" to 5, "unknown" to unknown("lost-x" to 1)),
            "chat-c" to json("waiting" to -3)))
        assertEquals(mapOf(
            "chat-a" to LostTurnChat(2, listOf(LostAdmission("lost-a", 8))),
            "chat-b" to LostTurnChat(0, listOf(LostAdmission("lost-b", 3))),
            "chat-c" to LostTurnChat(0, emptyList())), lostTurnsFrom(answer, listOf("chat-a", "chat-b", "chat-c", "chat-d")))
        assertEquals(emptyMap<String, LostTurnChat>(), lostTurnsFrom(json("detail" to "Not Found"), listOf("chat-a")))
    }

    /** Follow-up 4: a bot row has no chat id on the phone; the route names the bot's Bot Chat with its lost reply. */
    @Test fun theRouteAnswerNamesEachAskedBotsChat() {
        val answer = json("lost" to json(), "bots" to json(
            "bot-a" to json("session_id" to "bot-a-chat", "waiting" to 0, "unknown" to unknown("x1" to 3)),
            "bot-b" to json("waiting" to 0, "unknown" to unknown("y1" to 3)),
            "bot-z" to json("session_id" to "bot-z-chat", "waiting" to 0)))
        assertEquals(mapOf("bot-a" to LostTurnChat(0, listOf(LostAdmission("x1", 3)), "bot-a-chat")), lostBotsFrom(answer, listOf("bot-a", "bot-b", "bot-c")))
    }

    @Test fun theReadAsksBeaconsRouteForThoseChatsAndResumesNothing() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        try {
            assertEquals(emptyMap<String, LostTurnChat>(), f.client.lostTurnChats(emptyList()))
            assertTrue("no chats, no read", f.httpRequests.none { it.url.encodedPath == LOST_TURNS_PATH })
            f.client.lostTurnChats(listOf("chat-a", "chat-b"))
            f.client.lostTurnBots(listOf("bot-a", "bot-b"))
            val asked = f.httpRequests.filter { it.url.encodedPath == LOST_TURNS_PATH }
            assertEquals(listOf("GET", "GET"), asked.map { it.method })
            assertEquals("chat-a,chat-b", asked[0].url.queryParameter("ids"))
            assertEquals("bot-a,bot-b", asked[1].url.queryParameter("bots"))
            assertTrue(asked.all { it.url.queryParameter("beacon_profile") == "default" })
            assertTrue("browsing never resumes an agent", f.methods.none { it in setOf("session.resume", "prompt.resolve_unknown") })
        } finally { f.client.closeNative() }
    }

    /** Follow-up 4: a lone lost reply is let go as Hermes' Discard does: prompt.resolve_unknown with its own admission and generation, nothing resumed. */
    @Test fun aLoneLostReplyIsDiscardedWithoutAResume() = runBlocking {
        val asked = mutableListOf<org.json.JSONObject>()
        val f = NativeOperationsTest.Fixture(canonical = true).apply {
            onRpc = { method, params ->
                if (method == "prompt.resolve_unknown") { asked += params; json("admission_id" to params.getString("admission_id"), "status" to "terminal", "outcome" to "interrupted") } else null
            }
        }
        try {
            assertTrue(f.client.discardLostTurns("stored-a", listOf(LostAdmission("lost-1", 8))))
            val call = asked.single()
            assertEquals("stored-a", call.getString("session_id"))
            assertEquals("lost-1", call.getString("admission_id"))
            assertEquals(8L, call.getLong("execution_generation"))
            assertFalse("closing a dead turn is not a resume", f.methods.any { it in setOf("session.resume", "session.create", "prompt.submit") })
            assertFalse("nothing to let go", f.client.discardLostTurns("stored-a", emptyList()))
        } finally { f.client.closeNative() }
    }

    @Test fun oneAnotherClientLetGoFirstCountsAsGoneAndARefusalDoesNot() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply {
            onRpc = { method, params ->
                if (method != "prompt.resolve_unknown") null
                else if (params.getString("admission_id") == "lost-stale") json("error" to json("code" to 4001, "message" to "stale_generation"))
                else json("error" to json("code" to 4001, "message" to "permission_denied"))
            }
        }
        try {
            assertTrue(f.client.discardLostTurns("stored-a", listOf(LostAdmission("lost-stale", 8))))
            assertTrue("a refusal is not reported as gone", runCatching { f.client.discardLostTurns("stored-a", listOf(LostAdmission("lost-refused", 8))) }.isFailure)
        } finally { f.client.closeNative() }
    }
}
