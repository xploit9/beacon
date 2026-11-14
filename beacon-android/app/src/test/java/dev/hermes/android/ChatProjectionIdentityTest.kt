package dev.hermes.android

import dev.hermes.android.core.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2B: a stream is released by its turn's display identity (ownsCompletion). A saved-chat snapshot that names no profile (a stream's state_saved)
 * used to make every later merge a replacement, which dropped that identity, so a finished reply stayed on "Finishing" with its Stop button.
 */
class ChatProjectionIdentityTest {
    private val running = json("session_id" to "chat-a", "profile" to "default", "_display_submission_id" to "turn-1", "_display_admission_id" to "admission-1")

    @Test fun aSnapshotThatNamesNoProfileIsStillThatChatAndKeepsItsTurnIdentity() {
        val stream = json("session_id" to "chat-a", "messages" to org.json.JSONArray())
        val merged = mergeChatProjection(running, stream)
        assertEquals("turn-1", merged.getString("_display_submission_id"))
        assertEquals("admission-1", merged.getString("_display_admission_id"))
        assertEquals("The chat's profile is kept when a snapshot omits it", "default", merged.getString("profile"))
    }

    @Test fun aChatWhoseProfileIsNotKnownYetAcceptsTheSnapshotThatNamesIt() {
        val unnamed = json("session_id" to "chat-a", "_display_submission_id" to "turn-1")
        val merged = mergeChatProjection(unnamed, json("session_id" to "chat-a", "profile" to "default", "messages" to org.json.JSONArray()))
        assertEquals("turn-1", merged.getString("_display_submission_id"))
        assertEquals("default", merged.getString("profile"))
    }

    @Test fun theTurnIdentitySurvivesTheSequenceThatLeftTheChatStuck() {
        val afterStream = mergeChatProjection(running, json("session_id" to "chat-a"))
        val afterHistory = mergeChatProjection(afterStream, json("session_id" to "chat-a", "profile" to "default", "messages" to org.json.JSONArray()))
        assertEquals("turn-1", afterHistory.getString("_display_submission_id"))
    }

    @Test fun anotherNamedProfileOrAnotherSessionIsAnotherChat() {
        val other = mergeChatProjection(running, json("session_id" to "chat-a", "profile" to "coder"))
        assertFalse(other.has("_display_submission_id"))
        assertEquals("coder", other.getString("profile"))
        val elsewhere = mergeChatProjection(running, json("session_id" to "chat-b", "profile" to "default"))
        assertFalse(elsewhere.has("_display_submission_id"))
    }

    @Test fun aChatWithoutASessionIdIsNeverTheSameAsAnything() {
        assertFalse(sameChatProjection(json("profile" to "default"), json("profile" to "default")))
        assertFalse(sameChatProjection(json("session_id" to "chat-a"), json("profile" to "default")))
        assertTrue(sameChatProjection(json("session_id" to "chat-a"), json("session_id" to "chat-a")))
    }
}
