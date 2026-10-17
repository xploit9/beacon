package dev.hermes.android.voice

import dev.hermes.android.core.Scope
import org.json.JSONObject

/** A missed notification can start a new call, never answer its expired server invitation. */
internal fun requireMissedCallbackRoute(route: JSONObject, missed: IncomingCall, scope: Scope) {
    require(missed.state=="missed" && missed.conversation.isNotBlank()) { "This missed call is no longer available" }
    require(route.optString("event_id")==missed.event && route.optString("source_ref")==missed.source &&
        route.optString("owner_id")==scope.owner && route.optString("profile")==scope.profile &&
        route.optString("kind")=="incoming_call" && route.optString("state") in setOf("pending","expired") &&
        route.optString("conversation_ref")==missed.conversation && route.optString("session_id").isNotBlank() &&
        route.optString("conversation_id")==route.optString("session_id")) { "The missed call no longer belongs to this conversation" }
}
