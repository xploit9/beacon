package dev.hermes.android

import dev.hermes.android.core.json
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A render builds its projection from the raw it saw at its start and replaces the chat's raw after it has waited for the cache. A send identity that accepted() wrote in between
 * used to be lost (fl6 trace under load: the stream attached next followed an empty id, so the finished turn could not be matched to its send). The replace point carries it.
 */
class DisplayIdentityCarryTest {
    private fun projection(vararg pairs: Pair<String, Any?>) = json("session_id" to "chat-a", "messages" to JSONArray(), *pairs)

    @Test fun anIdentityTheChatGainedWhileTheRenderWaitedIsCarriedIntoTheReplacement() {
        val builtBeforeTheSend = projection()
        val chatNow = projection("_display_submission_id" to "turn-2", "_display_admission_id" to "admission-2")
        val replacement = carryDisplayIdentity(builtBeforeTheSend, chatNow)
        assertEquals("turn-2", replacement.getString("_display_submission_id"))
        assertEquals("admission-2", replacement.getString("_display_admission_id"))
    }

    @Test fun theNewerIdentityOfTheSameChatWinsOverTheOlderOneTheSnapshotCarried() {
        val builtBeforeTheSend = projection("_display_submission_id" to "turn-1", "_display_admission_id" to "admission-1")
        val chatNow = projection("_display_submission_id" to "turn-2", "_display_admission_id" to "admission-2")
        val replacement = carryDisplayIdentity(builtBeforeTheSend, chatNow)
        assertEquals("turn-2", replacement.getString("_display_submission_id"))
        assertEquals("admission-2", replacement.getString("_display_admission_id"))
    }

    @Test fun aBlankIdentityNeverBlanksWhatTheProjectionCarries() {
        val projectionCarries = projection("_display_submission_id" to "turn-1")
        val chatNow = projection("_display_submission_id" to "", "_display_admission_id" to "")
        val replacement = carryDisplayIdentity(projectionCarries, chatNow)
        assertEquals("turn-1", replacement.getString("_display_submission_id"))
        assertFalse(replacement.has("_display_admission_id"))
    }

    @Test fun aChatWithoutAnIdentityAddsNothingAndReturnsTheProjection() {
        val built = projection("title" to "Travel")
        val replacement = carryDisplayIdentity(built, projection())
        assertSame(built, replacement)
        assertFalse(replacement.has("_display_submission_id"))
        assertFalse(replacement.has("_display_admission_id"))
    }
}
