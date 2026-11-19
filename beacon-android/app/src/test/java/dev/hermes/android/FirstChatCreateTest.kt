package dev.hermes.android

import dev.hermes.android.core.json
import dev.hermes.android.data.ApiFailure
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * CHAT-121 follow-up 4: the first chat's create keeps its retry id (new-chat-attempt) until a create succeeds, so a Retry after a lost answer gets
 * the chat Hermes made. When Hermes refuses that id itself (its chat deleted after the lost answer: not_found; a changed request: invalid_params),
 * every Retry sent the same id again and the first chat never opened (the shape lane D found blocking Beacon's chat, F6). Now a refused kept id is
 * spent, and one fresh create starts.
 */
class FirstChatCreateTest {
    private class Hermes(vararg answers: Any) {
        val ids = mutableListOf<String>()
        private val queue = answers.toMutableList()
        var id = "kept-id"
        suspend fun create(): JSONObject {
            ids += id
            val next = queue.removeAt(0)
            if (next is Throwable) throw next
            return json("session_id" to "local-$id")
        }
    }

    private fun open(hermes: Hermes, kept: Boolean): Result<JSONObject> = runBlocking {
        runCatching { dev.hermes.android.createWithRetryIdentity(kept, renew = { hermes.id = "fresh-id" }) { hermes.create() } }
    }

    @Test fun aKeptIdWhoseChatWasDeletedOnHermesStartsOneFreshCreate() {
        for (refusal in listOf("not_found", "invalid_params")) {
            val hermes = Hermes(ApiFailure(4001, refusal), Unit)
            val opened = open(hermes, kept = true)
            assertEquals(refusal, "local-fresh-id", opened.getOrNull()?.getString("session_id"))
            assertEquals(refusal, listOf("kept-id", "fresh-id"), hermes.ids)
        }
    }

    /**
     * REVIEW-JOINB-121 P3-2: Hermes refuses the connection itself before it reads the id (permission_denied, profile_mismatch, as for Beacon's chat). The kept id
     * stays: a fresh one would be refused the same way, and once access is back it would make a second chat beside the one the kept id may have made.
     */
    @Test fun aKeptIdRefusedForTheConnectionIsKept() {
        for (refusal in listOf("permission_denied", "profile_mismatch", "runtime_draining")) {
            val hermes = Hermes(ApiFailure(4003, refusal))
            val opened = open(hermes, kept = true)
            assertTrue(refusal, opened.isFailure)
            assertEquals(refusal, listOf("kept-id"), hermes.ids)
        }
    }

    @Test fun aKeptIdIsSentAgainAndHermesReturnsTheChatItMade() {
        val hermes = Hermes(Unit)
        assertEquals("local-kept-id", open(hermes, kept = true).getOrNull()?.getString("session_id"))
        assertEquals(listOf("kept-id"), hermes.ids)
    }

    @Test fun aRestartALostAnswerOrANewIdKeepsTheIdForTheNextTry() {
        // runtime_draining: Hermes restarting, after which the same id works; a transport failure or the plugin's own answer says nothing about the
        // id; a new id (not kept) that fails is kept for the next try; and a fresh id that fails too is not replaced again (two creates at most).
        for ((failure, kept) in listOf(ApiFailure(4001, "runtime_draining") to true, IOException("lost") to true,
                ApiFailure(503, "Canonical gateway busy", plugin = true) to true, ApiFailure(4001, "not_found") to false)) {
            val hermes = Hermes(failure)
            assertTrue("$failure", open(hermes, kept).exceptionOrNull() === failure)
            assertEquals("$failure", listOf("kept-id"), hermes.ids)
        }
        val twice = Hermes(ApiFailure(4001, "not_found"), ApiFailure(4001, "not_found"))
        assertTrue(open(twice, kept = true).isFailure)
        assertEquals(listOf("kept-id", "fresh-id"), twice.ids)
    }
}
