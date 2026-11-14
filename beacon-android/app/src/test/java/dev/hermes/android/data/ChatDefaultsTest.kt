package dev.hermes.android.data

import dev.hermes.android.core.json
import dev.hermes.android.core.string
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** The chat's model and reasoning defaults come from two reads on a busy Hermes; one slow answer must not reach the owner. */
class ChatDefaultsTest {
    private fun good(session: String = "chat-a") = json("profile" to "default", "scope_key" to "scope-1", "session_id" to session,
        "model" to "synthetic-model", "reasoning_effort" to "high")

    @Test fun aFirstReadThatFailsIsAskedAgainOnceAndSucceedsSilently() = runBlocking {
        var reads = 0
        val failures = mutableListOf<Pair<Int, Throwable>>()
        val answer = readDefaultsAgain(pauseMs = 1, failed = { attempt, error -> failures += attempt to error }) {
            reads++
            if (reads == 1) throw ApiFailure(502, "Synthetic") else good()
        }
        assertEquals(2, reads)
        assertEquals("synthetic-model", answer.string("model"))
        // The first failure goes to the callback; the owner sees nothing.
        assertEquals(listOf(1), failures.map { it.first })
        assertEquals(502, (failures.single().second as ApiFailure).status)
    }

    @Test fun twoFailedReadsRaiseTheLastErrorAfterOneRetryOnly() = runBlocking {
        var reads = 0
        val attempts = mutableListOf<Int>()
        val error = runCatching { readDefaultsAgain(pauseMs = 1, failed = { attempt, _ -> attempts += attempt }) { reads++; throw IOException("Synthetic $reads") } }.exceptionOrNull()
        assertEquals(2, reads)
        assertEquals("Synthetic 2", error?.message)
        assertEquals(listOf(1, 2), attempts)
    }

    @Test fun anExpiredSignInIsNeverRetriedAsADefaultsFailure() = runBlocking {
        var reads = 0
        val error = runCatching { readDefaultsAgain(pauseMs = 1) { reads++; throw ApiFailure(401, "Synthetic") } }.exceptionOrNull()
        assertEquals(1, reads)
        assertEquals(401, (error as ApiFailure).status)
    }

    @Test fun whenBothReadsFailTheLastGoodDefaultsOfThisAccountAndProfileStayInUse() {
        val shown = profileDefaultsAfterFailure(good("chat-old"), scopeKey = "scope-1", profile = "default", session = "chat-new")
        assertEquals("synthetic-model", shown.string("model"))
        assertEquals("high", shown.string("reasoning_effort"))
        assertEquals("chat-new", shown.string("session_id"))
        assertFalse("no error is shown when something good is cached", shown.has("_error"))
        assertTrue(shown.optBoolean("_stale"))
    }

    @Test fun defaultsOfAnotherAccountProfileOrAHalfFilledEntryAreNeverReused() {
        for (previous in listOf<JSONObject?>(null, good().put("scope_key", "scope-2"), good().put("profile", "work"),
            good().put("model", ""), good().apply { remove("reasoning_effort") }, good().put("_error", "Earlier failure"))) {
            val shown = profileDefaultsAfterFailure(previous, scopeKey = "scope-1", profile = "default", session = "chat-a")
            assertEquals("Couldn't load models", shown.string("_error"))
            assertFalse(shown.has("model"))
        }
    }

    @Test fun aDefaultsFailureNeverRaisesTheAppWideErrorBanner() {
        assertFalse(loadFailureRaisesBanner("/api/profile/defaults", force = true, quiet = false))
        assertTrue(loadFailureRaisesBanner("/api/profiles", force = true, quiet = false))
        assertFalse(loadFailureRaisesBanner("/api/profiles", force = true, quiet = true))
    }
}
