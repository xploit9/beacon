package dev.hermes.android.push

import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.missedCallGone
import dev.hermes.android.missedCallRouteFromHistory
import dev.hermes.android.voice.IncomingCall
import dev.hermes.android.voice.requireMissedCallbackRoute
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissedCallGoneTest {
    private val scope = Scope("https://native.example", "owner", "default")
    private val missed = IncomingCall("source", "event", 1, state = "missed", conversation = "conversation-ref")
    private fun row(id: String = "event", state: String = "missed", direction: String = "incoming", chat: String = "stored-session") =
        json("id" to id, "state" to state, "direction" to direction, "conversation_id" to chat)
    private fun history(vararg rows: JSONObject) = json("calls" to rows.fold(JSONArray()) { all, next -> all.put(next) })

    // Hermes prunes the ringing invitation's notice about two minutes after it was created; Call back is tapped after that.
    @Test fun aMissedCallWhoseNoticeIsGoneIsFoundInTheOwnersHistoryWithItsChat() {
        val route = checkNotNull(missedCallRouteFromHistory(history(row("other", chat = "elsewhere"), row()), missed, scope))
        assertEquals("stored-session", route.getString("session_id"))
        requireMissedCallbackRoute(route, missed, scope)
    }

    @Test fun onlyTheSameStillMissedIncomingCallWithAUsableChatIsOffered() {
        assertNull(missedCallRouteFromHistory(history(), missed, scope))
        assertNull(missedCallRouteFromHistory(json(), missed, scope))
        for (other in listOf(row(id = "other"), row(state = "completed"), row(state = "declined"), row(state = "active"), row(direction = "outgoing"), row(chat = ""), row(chat = "../other chat")))
            assertNull(other.toString(), missedCallRouteFromHistory(history(other), missed, scope))
    }

    @Test fun onlyAGoneNoticeOnTheCallBackPathRetiresTheMissedCard() {
        assertTrue(missedCallGone("callback", ApiFailure(404, "Owned notification unavailable")))
        assertTrue(missedCallGone("callback", ApiFailure(410, "Gone")))
        assertFalse(missedCallGone("callback", ApiFailure(500, "Busy")))
        assertFalse(missedCallGone("callback", java.io.IOException("offline")))
        assertFalse(missedCallGone("answer", ApiFailure(404, "Owned notification unavailable")))
    }
}
