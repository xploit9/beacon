package dev.hermes.android

import dev.hermes.android.core.LiveAssistantBoundary
import dev.hermes.android.core.json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * WEDGE-121: after Hermes restarted mid-reply the chat said "Reply status unknown" and stayed there. The saved projection Hermes sends
 * on reconnect names the lost turn (a pending admission `unknown`, nothing running); the chat says what happened while Beacon closes it.
 */
class LostReplyStatusTest {
    private fun projection(pending: JSONArray, running: Boolean, active: String? = null) = json("session" to json("messages" to JSONArray(),
        "pending" to pending, "running" to running, "active_stream_id" to (active ?: JSONObject.NULL)))
    private fun lost() = JSONArray().put(json("admission_id" to "lost-1", "status" to "unknown", "execution_generation" to 8))
    private fun saved(chat: ChatState, event: JSONObject) = liveAfterEvent(chat, "state_saved", event, false, LiveAssistantBoundary())!!

    @Test fun aReplyHermesLostInARestartSaysSo() {
        val followed = ChatState(streamId = "stored-a", status = "Working")
        assertEquals("Hermes restarted during this reply.", saved(followed, projection(lost(), running = false)).status)
        assertEquals("Hermes restarted during this reply.", saved(followed, projection(lost(), running = false).put("replay_reset", true)).status)
        assertEquals("Hermes restarted during this reply.", saved(ChatState(), projection(lost(), running = false)).status)
    }

    /** Follow-up 2 (Root, TF-P3-2/R1): with nothing waiting, one plain line stays under the cut reply until the next turn; no question. */
    @Test fun thePlainRestartLineStaysUntilTheNextTurn() {
        val cut = ChatState(status = "Hermes restarted during this reply.")
        assertEquals("Hermes restarted during this reply.", renderedChatStatus(cut, active = null, streamEnded = false))
        assertEquals("A new turn replaces it", "Working", renderedChatStatus(cut, active = "stored-a", streamEnded = false))
        assertEquals("An ordinary idle chat shows nothing", "", renderedChatStatus(ChatState(), active = null, streamEnded = false))
    }

    @Test fun aRunningReplyIsNotCalledLost() {
        val queuedAfter = lost().put(json("admission_id" to "next-1", "status" to "started", "execution_generation" to 9))
        val running = saved(ChatState(streamId = "stored-a", status = "Working"), projection(JSONArray().put(queuedAfter.getJSONObject(1)), running = true, active = "stored-a"))
        assertNotEquals("Hermes restarted during this reply.", running.status)
        assertEquals("stored-a", running.streamId)
    }
}
