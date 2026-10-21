package dev.hermes.android.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.hermes.android.CypherApplication
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import dev.hermes.android.push.validOpaque
import kotlinx.coroutines.*
import org.json.JSONObject

internal const val INCOMING_RING_SECONDS = 20L
private const val INCOMING_INVITATION_SECONDS = 120L
private const val INCOMING_CLOCK_SKEW_SECONDS = 30L

data class IncomingCall(val source: String, val event: String, val expiresAt: Long, val actionToken: String = java.util.UUID.randomUUID().toString(),
    val arrivedAt: Long = 0, val arrivedElapsed: Long = 0, val boot: Int = -1, val state: String = "ringing", val conversation: String = "") {
    val key get() = "$source\u0000$event".digest()
    val answerAction get() = "hermes.call.ANSWER.$actionToken"
    val declineAction get() = "hermes.call.DECLINE.$actionToken"
    val callbackAction get() = "hermes.call.CALLBACK.$actionToken"
    val deadlineAction get() = "hermes.call.DEADLINE.$actionToken"
    fun valid(now: Long = System.currentTimeMillis() / 1000): Boolean = validIncoming(source, event, expiresAt, now)
    fun ringDurationMillis(nowMillis: Long = System.currentTimeMillis(), elapsed: Long = android.os.SystemClock.elapsedRealtime(), currentBoot: Int = boot): Long {
        if (state != "ringing" || arrivedAt <= 0 || nowMillis < arrivedAt || elapsed < arrivedElapsed || currentBoot != boot) return 0
        val wallRemaining = INCOMING_RING_SECONDS * 1000 - (nowMillis - arrivedAt)
        val monotonicRemaining = INCOMING_RING_SECONDS * 1000 - (elapsed - arrivedElapsed)
        val serverRemaining = if (expiresAt <= nowMillis / 1000) 0 else (expiresAt - nowMillis / 1000).coerceAtMost(INCOMING_INVITATION_SECONDS + 1) * 1000 - nowMillis % 1000
        return minOf(wallRemaining, monotonicRemaining, serverRemaining).coerceAtLeast(0)
    }
}

fun validIncoming(source: String, event: String, expiresAt: Long, now: Long): Boolean =
    validOpaque(source) && validOpaque(event) && now >= 0 && expiresAt > now && expiresAt - now <= INCOMING_INVITATION_SECONDS + INCOMING_CLOCK_SKEW_SECONDS

internal fun incomingRecordMayBePruned(state: String, expiresAtSeconds: Long, nowMillis: Long): Boolean {
    val retentionSeconds = if (state == "missed") 7 * 24 * 60 * 60L else 5 * 60L
    return expiresAtSeconds > 0 && expiresAtSeconds < nowMillis / 1000 - retentionSeconds
}

/** The FCM payload and intent only identify a saved invitation; they cannot authorize a call. */
suspend fun resolveIncoming(app: CypherApplication, source: String, event: String): Pair<CypherClient, JSONObject> {
    val invite = app.push.invitation(source, event) ?: run { app.push.discardInvitation(source, event); error("This ${dev.hermes.android.data.currentAssistantName} invitation has ended. Open Beacon history to view it.") }
    require(invite.valid()) { "This ${dev.hermes.android.data.currentAssistantName} invitation has expired" }
    val binding = app.push.binding(source) ?: error("Sign in to the invitation's owner account")
    val origin = trustedOrigin(binding.getString("origin"))
    val scope = Scope(origin, binding.getString("owner"), binding.getString("profile"))
    fun authorized() = app.push.permitsIncoming(source) && app.auth.read(origin).authorizesPushSource(scope)
    require(authorized()) { "Sign in to the invitation's owner account" }
    val c = app.push.clientFor(binding)
    try {
        c.bootstrap()
        if (c.scope != scope || !authorized()) app.push.discardInvitation(source, event)
        require(c.scope == scope && authorized()) { "Invitation authorization no longer matches this account" }
        val route = c.nativeRequest("/api/plugins/cypher/mobile/notifications/events/$event")
        if (incomingRouteEnded(route, source, c.auth.profile)) app.push.discardInvitation(source, event)
        requireIncomingRoute(route, source, c.auth.profile)
        require(authorized() && app.push.invitation(source, event) == invite && invite.valid()) { "This ${dev.hermes.android.data.currentAssistantName} invitation has ended" }
        return c to route
    } catch (e: Throwable) {
        if (e !is CancellationException) reconcileIncomingFailure(app, c, source, event, e)
        throw e
    }
}

fun incomingRouteEnded(route: JSONObject, source: String, profile: String): Boolean =
    route.string("source_ref") == source && route.string("profile") == profile && route.string("kind") == "incoming_call" && route.string("state") in setOf("answered", "declined", "expired")

fun incomingFailureTerminal(error: Throwable): Boolean = error is ApiFailure && error.status in setOf(401, 403, 404, 410)

suspend fun reconcileIncomingFailure(app: CypherApplication, client: CypherClient, source: String, event: String, error: Throwable) {
    if (!app.push.permitsIncoming(source) || app.push.invitation(source, event) == null || incomingFailureTerminal(error)) {
        app.push.discardInvitation(source, event)
    } else if (error is ApiFailure && error.status in setOf(400, 409)) {
        // A lease conflict can be retryable; only the authenticated event state confirms replay/settlement.
        withTimeoutOrNull(3000) {
            try { if(incomingRouteEnded(client.nativeRequest("/api/plugins/cypher/mobile/notifications/events/$event"),source,client.auth.profile)) app.push.discardInvitation(source,event) }
            catch(error:CancellationException) { throw error }
            catch(error:Exception) { /* No authenticated terminal receipt; keep the invitation. */ }
        }
    }
}

fun requireIncomingRoute(route: JSONObject, source: String, profile: String, now: Long = System.currentTimeMillis() / 1000) {
    require(route.string("source_ref") == source && route.string("profile") == profile && route.string("kind") == "incoming_call" && route.string("state") == "pending" && validOpaque(route.string("callback_id")) && validIncoming(source, route.string("callback_id"), route.optLong("expires_at"), now)) { "This ${dev.hermes.android.data.currentAssistantName} invitation is no longer available" }
}

internal suspend fun performIncomingDeclineRequest(
    pending: () -> Boolean,
    authorize: () -> Boolean,
    request: suspend () -> Unit,
) {
    require(pending()) { "This ${dev.hermes.android.data.currentAssistantName} invitation has already been handled" }
    require(authorize()) { "This ${dev.hermes.android.data.currentAssistantName} invitation has ended" }
    request()
}

suspend fun declineIncoming(app: CypherApplication, source: String, event: String) = withTimeout(8000) {
    require(app.push.claimInvitation(source, event)) { "This ${dev.hermes.android.data.currentAssistantName} invitation has ended" }
    val (client, _) = resolveIncoming(app, source, event)
    try {
        performIncomingDeclineRequest(
            pending = { app.push.invitation(source, event) != null },
            authorize = { app.push.authorizeKeepConnectedAction(source, event, "decline") },
            request = { client.nativeRequest("/api/plugins/cypher/mobile/callbacks/$event/decline", "POST", org.json.JSONObject()) },
        )
        app.push.discardInvitation(source, event)
    } catch (e: Throwable) {
        if (e !is CancellationException) reconcileIncomingFailure(app, client, source, event, e)
        throw e
    }
}

class IncomingCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val source = intent.getStringExtra("push_source").orEmpty()
        val event = intent.getStringExtra("push_event").orEmpty()
        if (!validOpaque(source) || !validOpaque(event)) return
        val app=context.applicationContext as? CypherApplication?:return
        val invite=app.push.deadlineInvitation(source,event)
        if (invite == null) return
        if (intent.action == invite.deadlineAction) { app.push.expireInvitation(source, event); return }
        if (intent.action != invite.declineAction) return
        val pending=goAsync()
        val workScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        workScope.launch {
            try { declineIncoming(app, source, event) }
            catch(error:CancellationException) { throw error }
            catch (_: Exception) { /* An unaccepted decline stays actionable until expiry; the server settles replay. */ }
            finally { pending.finish();workScope.cancel() }
        }
    }
}
