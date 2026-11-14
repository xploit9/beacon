package dev.hermes.android.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Lane S3 round 2: the first chat a phone remembers (its "shell") can be deleted on Hermes. Resuming it then gets the canonical authority's `4001 not_found`
 * (gateway/session_controls.py:372-374), not the legacy `4007 session not found` the app knew, so the app never recovered and never made another chat
 * (captured on the demo: `session.resume` answered not_found in 21 ms, no `session.create` was ever sent, Retry resumed the same dead id).
 * And a first chat's create now carries its attempt as Hermes' retry identity (gateway/session_local.py:333-340). Synthetic values only.
 */
class FirstChatContractTest {
    /** The red test on the base: Hermes' canonical not_found was not read as a missing conversation. */
    @Test fun theCanonicalAuthoritysNotFoundIsAMissingConversation() {
        assertTrue(missingNativeConversation(ApiFailure(4001, "not_found")))
    }

    @Test fun bothHermesAnswersForAMissingSessionAreRecognisedAndNothingElseIs() {
        assertTrue(nativeSessionNotFound(ApiFailure(4007, "session not found")))
        assertTrue(nativeSessionNotFound(ApiFailure(4001, "not_found")))
        assertFalse("another 4001 refusal", nativeSessionNotFound(ApiFailure(4001, "invalid_params")))
        assertFalse("another 4001 refusal", nativeSessionNotFound(ApiFailure(4001, "runtime_draining")))
        assertFalse("a 4007 about something else", nativeSessionNotFound(ApiFailure(4007, "session_key required")))
        assertFalse("a REST 404 is not Hermes' own answer", nativeSessionNotFound(ApiFailure(404, "Not found")))
        assertFalse(nativeSessionNotFound(ApiFailure(401, "Sign in again")))
        assertFalse(nativeSessionNotFound(ApiFailure(503, "Busy")))
        assertFalse(nativeSessionNotFound(java.io.IOException("Timed out")))
    }

    @Test fun aRestNotFoundStillCountsAsAMissingConversation() {
        assertTrue(missingNativeConversation(ApiFailure(404, "Not found")))
        assertFalse(missingNativeConversation(ApiFailure(4001, "invalid_params")))
    }

    private fun create(body: JSONObject) = nativeCreateParams(body)

    @Test fun aFirstChatsCreateSendsOnlyWhatHermesAnswersAndItsAttemptAsTheRetryIdentity() {
        val params = create(JSONObject().put("worktree", false).put("profile", "default").put("prev_session_id", "chat-a").put("request_id", "attempt-1"))
        assertEquals(setOf("request_id"), params.keys().asSequence().toSet())
        assertEquals("attempt-1", params.getString("request_id"))
    }

    @Test fun aPlainCreateSendsNothingButTheEnvelope() {
        assertEquals(0, create(JSONObject().put("worktree", false).put("profile", "default")).length())
    }

    @Test fun titleModelProviderFolderAndThreadStillMapToHermesFields() {
        val params = create(JSONObject().put("title", "Trip").put("model", "m1").put("model_provider", "p1").put("workspace", "C:/synthetic-workspace").put("thread", true).put("request_id", "r1"))
        assertEquals("Trip", params.getString("title"))
        assertEquals("m1", params.getString("model"))
        assertEquals("p1", params.getString("provider"))
        assertEquals("C:/synthetic-workspace", params.getString("cwd"))
        assertEquals("r1", params.getString("request_id"))
        assertTrue(params.has("source"))
    }

    @Test fun aBlankOrOversizedRetryIdentityIsNotSent() {
        assertFalse(create(JSONObject().put("request_id", "")).has("request_id"))
        assertFalse(create(JSONObject().put("request_id", "x".repeat(257))).has("request_id"))
        assertTrue(create(JSONObject().put("request_id", "x".repeat(256))).has("request_id"))
    }
}
