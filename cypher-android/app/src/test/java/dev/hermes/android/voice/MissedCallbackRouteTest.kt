package dev.hermes.android.voice

import dev.hermes.android.core.Scope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MissedCallbackRouteTest {
    private val scope=Scope("https://native.example","owner","default")
    private val missed=IncomingCall("source","event",1,state="missed",conversation="conversation-ref")
    private fun route()=JSONObject().put("event_id","event").put("source_ref","source").put("owner_id","owner").put("profile","default").put("kind","incoming_call").put("state","expired").put("conversation_ref","conversation-ref").put("session_id","stored-session").put("conversation_id","stored-session")
    @Test fun expiredOwnedNoticeCanStartNewCallButForeignOrConsumedNoticesCannot() {
        requireMissedCallbackRoute(route(),missed,scope)
        for((field,value) in listOf("event_id" to "other","source_ref" to "other","owner_id" to "other","profile" to "other","conversation_ref" to "other","state" to "answered","state" to "revoked","session_id" to ""))
            assertTrue(field,runCatching { requireMissedCallbackRoute(route().put(field,value),missed,scope) }.isFailure)
        assertTrue(runCatching { requireMissedCallbackRoute(route(),missed.copy(state="ringing"),scope) }.isFailure)
    }
}
