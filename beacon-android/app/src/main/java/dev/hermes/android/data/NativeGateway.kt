package dev.hermes.android.data

import dev.hermes.android.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Hermes `source` for a named Beacon thread (Relay tags its threads `phone`); every other phone chat is `android`. */
internal const val BEACON_THREAD_SOURCE = "android_thread"
internal val THREAD_SOURCES = listOf(BEACON_THREAD_SOURCE, "phone")
private val WINDOW_SERVER_REQUESTS = setOf("preview.act", "preview.read", "terminal.read", "window.read", "tour")
private val SECURE_SERVER_REQUESTS = setOf("vault.save_login", "vault.code", "vault.unlock_prompt", "secret")

private data class ParsedNativeRequestId(val key: String, val text: String)
private data class NativeRequestIdentity(val idKey: String, val method: String, val sessionId: String)
private data class NativeServerRequest(
    val id: Any,
    val idText: String,
    val idKey: String,
    val method: String,
    val sessionId: String,
    var params: JSONObject,
    val socket: WebSocket,
    val expiresAt: Long = Long.MAX_VALUE,
    var delivered: Boolean = false,
) {
    val identity get() = NativeRequestIdentity(idKey, method, sessionId)
}

data class NativeSessionBinding(val scope: Scope, val storedSessionId: String, val runtimeSessionId: String)

internal open class NativeTurnNotSent(message: String, cause: Throwable? = null) : IOException(message, cause)
/** CHATQA-121 follow-up 5: a prompt whose answer was lost; [staged] is its payload's staged attachments, so asking again re-sends them unchanged. */
internal class NativeSubmitUnknown(val staged: org.json.JSONArray, cause: Throwable) : IOException(NATIVE_OUTCOME_UNKNOWN, cause)
/** Follow-up 5: a refusal that names the field it is about (Hermes' error `data.field`, gateway/session_controls.py `_refusal_data`). */
internal class NativeRefusal(status: Int, message: String, val field: String) : ApiFailure(status, message)
internal class NativeTurnLease internal constructor(
    val binding: NativeSessionBinding, val authority: Boolean,
    internal val gateway: NativeGateway, internal val connectionToken: Any, internal val cursorToken: Any,
    internal val attachLimits: AttachmentLimits = AttachmentLimits(),
)

/**
 * What one file frame of this connection may carry (CHAT-121 follow-up 4), from its gateway ticket: the plugin's frame limit ([frameBytes], sent in
 * pieces when over CANONICAL_RELAY_FRAME_BYTES) and Hermes' own caps on one file and one image ([fileBytes], [imageBytes], null when not given). A 1.2.0
 * plugin, or a socket without the plugin, gives none: one text frame of 8 MiB, about 6 MB of file.
 */
internal data class AttachmentLimits(val frameBytes: Long = CANONICAL_RELAY_FRAME_BYTES.toLong(), val fileBytes: Long? = null, val imageBytes: Long? = null) {
    val pieces get() = frameBytes > CANONICAL_RELAY_FRAME_BYTES
}

/**
 * The limits a gateway ticket's answer gives ([AttachmentLimits]); a value of the wrong kind or out of range counts as not given. Pieces are used only in the form
 * this phone sends ([ATTACHMENT_PIECES_FORM], `attach_pieces`): a plugin that names another form, or none (follow-up 4's), keeps one text frame.
 */
internal fun advertisedAttachmentLimits(admission: JSONObject): AttachmentLimits {
    fun bytes(key: String, range: LongRange): Long? = when (val value = admission.opt(key)) { is Int -> value.toLong(); is Long -> value; else -> null }?.takeIf { it in range }
    val gib = 1L shl 30
    val pieces = admission.opt("attach_pieces").let { it is Int && it == ATTACHMENT_PIECES_FORM }
    return AttachmentLimits((if (pieces) bytes("attach_frame_limit", CANONICAL_RELAY_FRAME_BYTES.toLong()..gib) else null) ?: CANONICAL_RELAY_FRAME_BYTES.toLong(),
        bytes("attach_file_limit", 1L..gib), bytes("attach_image_limit", 1L..gib))
}

internal fun nativeSessionBinding(scope: Scope, result: JSONObject): NativeSessionBinding {
    // Create and unpersisted resume use stored_session_id; persisted resume uses session_key.
    val identities = listOf("stored_session_id", "session_key").map { result.string(it) }.filter { it.isNotBlank() }.distinct()
    require(identities.size == 1 && identities.single().matches(Regex("[A-Za-z0-9_.:-]{1,256}"))) { "Invalid native stored session identity" }
    val runtime = result.string("session_id")
    require(runtime.matches(Regex("[A-Za-z0-9_.:-]{1,256}"))) { "Invalid native runtime session identity" }
    return NativeSessionBinding(scope, identities.single(), runtime)
}

/** The ticket rides in a Sec-WebSocket-Protocol header value, so refuse anything but a bounded single token. */
internal fun nativeConnectionTicket(value: String): String {
    require(value.length in 1..1024 && value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "!#\$%&'*+-.^_`|~" }) { "Native server did not return a usable connection ticket" }
    return value
}

/**
 * One file on the way per phone (JOINBFIX-121, REVIEW-JOINB-121 P2-1). The plugin keeps one file socket and one share of its memory budget per phone, and a
 * newer file socket ends the older one, so two files sent at once ended each other's socket and both ended "Not sent". Every send runs on its own detached
 * client with its own gateway, so the lock is the process's: a second file waits for the first, and the chat's own frames are not held.
 */
private val fileUploads = Mutex()

/** A request whose frame may have reached Hermes; [requestNeverSent] reads this to keep such sends uncertain. */
internal const val NATIVE_OUTCOME_UNKNOWN = "Native request outcome may be unknown; reconcile history before resending"

/** The params field a file frame carries its file in, when the whole file goes in one text frame ([NativeGateway.turnRpc]). */
private val TEXT_FILE_FIELDS = mapOf("file.attach" to "data_url", "image.attach_bytes" to "content_base64")

/** The pauses before a busy admission is asked again ([admissionBusy]): about a second in all, then the refusal stands. */
internal val NATIVE_ADMISSION_BUSY_PAUSES_MS = longArrayOf(150, 300, 600)
/**
 * Only a refusal this quick is asked again ([admissionBusy]). A 1.2.0 plugin's busy refusal comes at once (125-164 ms measured, CHAT-121)
 * and so does an at-once socket refusal; an "unavailable" after a slow step (the plugin's 6 s bound, a slow exchange) is a gateway that is
 * slow or down, and asking it four times only stacked about 25 s of waiting (REVIEW-121B P3-3).
 */
internal const val NATIVE_ADMISSION_QUICK_MS = 1_000L
/** The plugin refused a canonical socket before it opened: its upgrade answered [status], never 101, [tookMs] after the upgrade was asked. */
internal class NativeAdmissionRefused(val status: Int, cause: Throwable, val tookMs: Long = 0) : IOException("Native connection refused before it opened ($status)", cause)
/**
 * A 1.2.0 plugin's 503 for a ticket it turned away at once with all its admission workers taken; Hermes' gateway being down answers the
 * same. A 1.2.1 plugin waits for a worker first and then answers "Canonical gateway busy", which is not asked again: it already waited.
 */
private const val NATIVE_ADMISSION_REFUSED_AT_ONCE = "Canonical gateway unavailable"
/**
 * The plugin turned a native connection away before anything reached Hermes, quickly, and asking again shortly may get in: its gateway ticket
 * answered a 1.2.0 plugin's busy-or-down 503 within [NATIVE_ADMISSION_QUICK_MS] ([ticketMs], the ticket call's own time), or it refused the
 * canonical socket's upgrade at once (403 or 503, within the same time from the upgrade's own start, [NativeAdmissionRefused.tookMs]). Only the
 * step that failed is timed, so a slow link or a proxy's extra round trips before it do not count (REVIEW-121B FU-P3-1). Not a 1.2.1 plugin's
 * 429, a socket that already waited for a worker, and not any other refusal.
 */
internal fun admissionBusy(error: Throwable, ticketMs: Long): Boolean = generateSequence(error) { it.cause }.take(8).any {
    it is ApiFailure && it.plugin && it.status == 503 && it.detail == NATIVE_ADMISSION_REFUSED_AT_ONCE && ticketMs < NATIVE_ADMISSION_QUICK_MS ||
        it is NativeAdmissionRefused && it.status in setOf(403, 503) && it.tookMs < NATIVE_ADMISSION_QUICK_MS
}

internal class NativeGateway(private val client: BeaconClient, private val socketFactory: WebSocket.Factory = client.http, private val rpcTimeoutMs: Long = 60000) {
    private class Connection(val identity: BeaconClient.GatewayIdentity, val canonical: Boolean, val device: String?) {
        val authorityVersion = identity.authority
        val secureAdmission = identity.admission
        val frames = Channel<JSONObject>(256)
        val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
        val requestLock = Any()
        val serverRequests = linkedMapOf<String, NativeServerRequest>()
        val retiredServerRequests = linkedSetOf<NativeRequestIdentity>()
        val secureIds = mutableSetOf<String>()
        var secureExhausted = false
        val secureConnection = UUID.randomUUID().toString()
        val ready = CompletableDeferred<Unit>()
        val sessionAuthority = CompletableDeferred<Boolean?>()
        @Volatile var capabilitiesSent = false
        @Volatile var socket: WebSocket? = null
        @Volatile var failure: IOException? = null
        /** What a file frame may carry (its ticket's answer); in pieces, it goes on a socket of its own ([attachFile]). */
        @Volatile var attachLimits = AttachmentLimits()
    }
    /** A binding names a runtime that lives on the socket that made it: once that socket is gone Hermes may reap the runtime. */
    private class Cursor(val binding: NativeSessionBinding, val connection: Connection?, var sequence: Long = 0, var epoch: String = "")
    companion object {
        // Weak, so a gateway leaves with its client; answers are only routed between gateways of one account and profile.
        private val gateways: MutableSet<NativeGateway> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
        /** When a secure question was first seen, so a replay after the app returns keeps Hermes' original deadline. */
        private val secureFirstSeen = ConcurrentHashMap<String, Long>()
        /** Told when a secret question arrives and no screen is collecting events (the app is away); the app posts a notice. */
        @Volatile internal var secureAwaiting: ((Scope, String) -> Unit)? = null

        /** Live, already-entitled prompts for the open conversation; values are never retained here. */
        internal fun pendingSecureRequests(scope: Scope, authorityVersion: Long? = null): List<JSONObject> {
            if (scope.session.isBlank() || authorityVersion == null) return emptyList()
            // Idle weak-registry entries must not hide a live entitled socket; output stays bounded below.
            val peers = synchronized(gateways) { gateways.filter { it.client.scope == scope.copy(session = "") && it.client.nativeAuthorityVersion() == authorityVersion } }
            return peers.asSequence().flatMap { it.secureRequests(scope).asSequence() }.take(8).toList()
        }

        /** Whether a live, entitled connection is attached to this conversation: an empty list from it means Hermes has no question open, with none it means nothing is known. */
        internal fun secureLinkLive(scope: Scope, authorityVersion: Long? = null): Boolean {
            if (scope.session.isBlank() || authorityVersion == null) return false
            val peers = synchronized(gateways) { gateways.filter { it.client.scope == scope.copy(session = "") && it.client.nativeAuthorityVersion() == authorityVersion } }
            return peers.any { it.secureLinkLive(scope) }
        }
    }
    private val connectLock = Mutex()
    private val sessionLock = Mutex()
    private val lifecycle = Any()
    private val cursorLock = Any()
    private val consuming = AtomicBoolean()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val canonicalScopes = mutableSetOf<Scope>()
    @Volatile private var connection: Connection? = null
    private var cursor: Cursor? = null
    init { synchronized(gateways) { gateways += this } }
    private fun connectionIdentityCurrent(mine: Connection): Boolean = client.gatewayIdentityCurrent(mine.identity) &&
        NativePairing.deviceHeader(CANONICAL_TICKET_PATH, mine.identity.scope) == mine.device
    private fun secureCurrent(mine: Connection): Boolean = connectionIdentityCurrent(mine) && mine.authorityVersion != null &&
        mine.authorityVersion == client.nativeAuthorityVersion() && mine.secureAdmission != null && mine.secureAdmission == client.nativeSecureAdmission()

    private fun secureLinkLive(scope: Scope): Boolean {
        val mine = connection?.takeIf { it.failure == null && it.ready.isCompleted && secureCurrent(it) } ?: return false
        val active = synchronized(cursorLock) { cursor } ?: return false
        return active.connection === mine && active.binding.scope == scope.copy(session = "") && active.binding.storedSessionId == scope.session
    }

    private fun secureRequests(scope: Scope): List<JSONObject> {
        val mine = connection?.takeIf { it.failure == null && it.ready.isCompleted && secureCurrent(it) } ?: return emptyList()
        val active = synchronized(cursorLock) { cursor } ?: return emptyList()
        if (active.connection !== mine || active.binding.scope != scope.copy(session = "") || active.binding.storedSessionId != scope.session) return emptyList()
        return synchronized(mine.requestLock) {
            if (connection !== mine || mine.failure != null) return@synchronized emptyList()
            val now = System.nanoTime() / 1_000_000
            mine.serverRequests.values.filter { it.method in SECURE_SERVER_REQUESTS && it.delivered && it.socket === mine.socket && now < it.expiresAt }
                .map { request -> request.params.copy().put("id", request.id).put("stream_id", request.sessionId)
                    .put("secure_method", request.method).put("secure_connection", mine.secureConnection).put("secure_expires_ms", request.expiresAt) }
        }
    }
    private suspend fun connect(): Connection = connectLock.withLock {
        connection?.let { previous ->
            if (previous.failure == null && previous.ready.isCompleted && connectionIdentityCurrent(previous) &&
                (previous.canonical || !client.canonicalRequired(previous.identity))) return@withLock previous
            terminate(previous, IOException("Native connection authority changed")); previous.socket?.cancel()
        }
        require(client.auth.backend == "hermes-dashboard" && client.auth.owner.isNotBlank() && client.auth.profile.isNotBlank()) { "Verify native dashboard identity before connecting" }
        val attempt = generation.get()
        // A 1.2.0 plugin turns an admission away at once while its few admission workers are taken (canonical_transport.BoundedWorkers):
        // its ticket answers 503, or the socket is refused before it opens. The app's own connections at startup met that (CHAT-121: a new
        // chat failed with "Couldn't open the chat" before Hermes saw it). Nothing reaches Hermes before the socket is ready, so the
        // admission is asked again after a short pause ([admissionBusy]: never a newer plugin's "busy", which has already waited); any other
        // failure, and a refused one after the last pause, stays as it was.
        for (pause in NATIVE_ADMISSION_BUSY_PAUSES_MS) {
            try { return@withLock admit(attempt) }
            catch (e: Exception) { if (e is CancellationException || !admissionBusy(e, failedTicketMs)) throw e }
            delay(pause)
        }
        admit(attempt)
    }
    /** How long the last admission's ticket call took when it failed (read by [connect] under connectLock, which [admit] runs under). */
    private var failedTicketMs = Long.MAX_VALUE
    private suspend fun admit(attempt: Long): Connection {
        failedTicketMs = Long.MAX_VALUE
        val identity = client.gatewayIdentity()
        val device = NativePairing.deviceHeader(CANONICAL_TICKET_PATH, identity.scope)
        fun current(checkAdmission: Boolean = false) = attempt == generation.get() && client.gatewayIdentityCurrent(identity, checkAdmission) &&
            NativePairing.deviceHeader(CANONICAL_TICKET_PATH, identity.scope) == device
        check(current(true)) { "Native identity changed during ticket admission" }
        val started = System.nanoTime()
        val admission = try {
            client.nativeRequest(CANONICAL_TICKET_PATH, "POST", JSONObject())
        } catch (e: CanonicalRouteMissing) {
            check(current(true)) { "Native identity changed during ticket admission" }
            if (synchronized(lifecycle) { identity.scope in canonicalScopes } || client.canonicalRequired(identity)) throw e
            null
        } catch (e: Exception) {
            failedTicketMs = (System.nanoTime() - started) / 1_000_000
            throw e
        }
        currentCoroutineContext().ensureActive()
        check(current(true)) { "Native identity changed during ticket admission" }
        val canonical = admission != null
        val attachLimits = admission?.let(::advertisedAttachmentLimits) ?: AttachmentLimits()
        val ticket: String
        val deadline: Long
        if (admission != null) {
            require(admission.opt("path") == CANONICAL_SOCKET_PATH && admission.opt("profile") == identity.scope.profile && admission.opt("canonical") == true) { "Canonical gateway admission did not match this profile" }
            val seconds = when (val value = admission.opt("expires_in")) { is Int -> value.toLong(); is Long -> value; else -> 0L }
            require(seconds in 1..25 && admission.opt("ticket") is String) { "Invalid canonical gateway admission" }
            ticket = nativeConnectionTicket(admission.opt("ticket") as String)
            deadline = started + seconds * 1_000_000_000L
            check(System.nanoTime() < deadline) { "Canonical gateway ticket expired" }
            synchronized(lifecycle) {
                check(current(true)) { "Native identity changed during ticket admission" }
                canonicalScopes += identity.scope
            }
        } else {
            val stock = client.nativeRequest("/api/auth/ws-ticket", "POST", JSONObject())
            require(stock.opt("ticket") is String) { "Invalid native gateway ticket" }
            ticket = nativeConnectionTicket(stock.opt("ticket") as String)
            deadline = Long.MAX_VALUE
        }
        currentCoroutineContext().ensureActive()
        val mine = Connection(identity, canonical, device).also { it.attachLimits = attachLimits }
        synchronized(lifecycle) { check(current(true)) { "Native identity changed during ticket admission" }; connection = mine }
        return try {
            val path = if (canonical) CANONICAL_SOCKET_PATH else "/api/ws"
            val protocols = if (canonical) "beacon-canonical-v1, beacon-ticket.$ticket" else "hermes-gateway-v1, hermes-gateway-ticket.$ticket"
            check(!canonical || System.nanoTime() < deadline) { "Canonical gateway ticket expired" }
            val request = client.request(path).removeHeader("Authorization").removeHeader(HERMES_LOOPBACK_SESSION_HEADER)
                .header("Sec-WebSocket-Protocol", protocols).build()
            check(!canonical || request.header("X-Beacon-Device-ID") == device) { "Native device changed during ticket admission" }
            var upgradeAsked = System.nanoTime()
            val listener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    mine.socket = ws
                    val advertised = runCatching { synchronized(lifecycle) {
                        if (connection !== mine || mine.failure != null || !current(true) || canonical && System.nanoTime() >= deadline) false
                        else {
                            val send = { ws.send(json("jsonrpc" to "2.0", "id" to "capabilities-${UUID.randomUUID()}", "method" to "client.capabilities",
                                "params" to json("server_requests" to true)).toString()) }
                            if (canonical) send() else client.withLegacyCanonicalPermit(identity, send)
                        }
                    } }.getOrDefault(false)
                    if (!advertised) {
                        terminate(mine, IOException("Native connection could not advertise server-request support"))
                        ws.cancel()
                        return
                    }
                    mine.capabilitiesSent = true
                    if (!canonical) mine.ready.complete(Unit)
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    if (connection !== mine || mine.failure != null) return
                    if (!runCatching { current() && (canonical || !client.canonicalRequired(identity)) }.getOrDefault(false)) { terminate(mine, IOException("Native connection identity changed")); ws.cancel(); return }
                    if (text.length > 8 * 1024 * 1024) { terminate(mine, IOException("Native frame exceeds size limit")); ws.cancel(); return }
                    val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                    val id = frame.optString("id")
                    val method = frame.optString("method")
                    val event = frame.optJSONObject("params")
                    if (method == "event" && event?.optString("type") == "gateway.ready") {
                        val payload = event.optJSONObject("payload")
                        val capability = payload?.opt("session_authority")
                        if (canonical && (!mine.capabilitiesSent || capability != true)) { terminate(mine, IOException("Canonical gateway did not become ready")); ws.cancel(); return }
                        if (canonical) {
                            val retained = runCatching { synchronized(lifecycle) {
                                connection === mine && mine.failure == null && current(true) && client.rememberCanonical(identity)
                            } }.getOrDefault(false)
                            if (!retained) { terminate(mine, IOException("Canonical gateway authority could not be retained")); ws.cancel(); return }
                        }
                        mine.sessionAuthority.complete(when {
                            payload == null -> null
                            !payload.has("session_authority") -> false
                            capability is Boolean -> capability
                            else -> null
                        })
                        if (canonical) mine.ready.complete(Unit)
                    }
                    if (!frame.has("method") && id.isNotBlank()) {
                        val pending = mine.pending.remove(id)
                        if (pending != null) {
                            val error = frame.optJSONObject("error")
                            val success = frame.has("result") && !frame.has("error")
                            val refusal = !frame.has("result") && error?.opt("code") is Number && error.opt("message") is String
                            if (pending.complete(frame) && frame.opt("jsonrpc") == "2.0" && (success || refusal))
                                gatewayAnswered(mine)
                        }
                    }
                    else if (method == "approval" || method == "clarify" || method in SECURE_SERVER_REQUESTS) {
                        if (method in SECURE_SERVER_REQUESTS) {
                            frame.put("_secure_received_ms", System.nanoTime() / 1_000_000)
                            // Nothing is collecting events (the app is away): the question waits in the queue, and the person is told.
                            if (frame.has("id") && !consuming.get()) runCatching { secureAwaiting?.invoke(client.scope, method) }
                        }
                        // A prompt can precede the session.resume acknowledgement that installs its owner binding.
                        // Without an id it is a notification: JSON-RPC never answers one, and it cannot be answered.
                        if (frame.has("id") && !mine.frames.trySend(frame).isSuccess) {
                            terminate(mine, IOException("Native event queue overflow; reconcile history")); ws.cancel()
                        }
                    } else if (isServerRequest(frame)) {
                        prepareServerRequest(mine, frame)
                    } else {
                        retireCancelledServerRequest(mine, frame)
                        if (!mine.frames.trySend(frame).isSuccess) { terminate(mine, IOException("Native event queue overflow; reconcile history")); ws.cancel() }
                        else if (method == "event" && !frame.has("id") && frame.opt("jsonrpc") == "2.0" && event?.opt("type") is String && event.getString("type").isNotBlank())
                            gatewayAnswered(mine)
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    // A connection that could not open (not one that dropped later) says Hermes is out of reach right now.
                    if (response == null) HermesReach.failed(client.auth.origin, t)
                    // A socket the plugin refused before it opened (its upgrade answered, never 101): no frame of this connection reached Hermes.
                    terminate(mine, IOException("Native connection interrupted", if (canonical && response != null && !mine.capabilitiesSent)
                        NativeAdmissionRefused(response.code, t, (System.nanoTime() - upgradeAsked) / 1_000_000) else t))
                }
                override fun onClosing(ws: WebSocket, code: Int, reason: String) { terminate(mine, IOException("Native connection closed ($code)")); ws.close(code, reason) }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) { terminate(mine, IOException("Native connection closed ($code)")) }
            }
            val socket = synchronized(lifecycle) {
                check(connection === mine && current(true) && (!canonical || System.nanoTime() < deadline)) { "Native identity changed during connection admission" }
                upgradeAsked = System.nanoTime()
                if (canonical) socketFactory.newWebSocket(request, listener) else {
                    var opened: WebSocket? = null
                    check(client.withLegacyCanonicalPermit(identity) { opened = socketFactory.newWebSocket(request, listener); true }) { "Canonical gateway authority is required" }
                    requireNotNull(opened)
                }
            }
            mine.socket = socket
            if (connection !== mine) socket.cancel()
            try { withTimeout(15000) { mine.ready.await() } } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); throw IOException("Native connection admission timed out", e) }
            mine.failure?.let { throw it }
            check(current(true)) { "Native identity changed during connection admission" }
            mine
        } catch (e: Throwable) { terminate(mine, IOException("Native connection admission ended", e)); mine.socket?.cancel(); throw e }
    }
    private fun terminate(mine: Connection, error: IOException) {
        synchronized(lifecycle) { if (connection === mine) connection = null; if (mine.failure == null) mine.failure = error }
        mine.ready.completeExceptionally(error)
        mine.sessionAuthority.complete(null)
        mine.pending.values.forEach { it.completeExceptionally(unknown(error)) }; mine.pending.clear()
        // Channel closure cannot be dropped when the queue is full and wakes an idle collector.
        mine.frames.close(error)
    }
    private fun unknown(cause: Throwable) = IOException(NATIVE_OUTCOME_UNKNOWN, cause)
    suspend fun rpc(method: String, params: JSONObject): JSONObject = rpcOn(connect(), method, params)

    /** Read this connection's protocol without attaching a conversation. */
    internal suspend fun sessionAuthority(): Boolean {
        val mine = connect()
        val authority = withTimeoutOrNull(4000) { mine.sessionAuthority.await() }
            ?: throw IOException("Native gateway readiness is unavailable")
        if (connection !== mine || mine.failure != null || !connectionIdentityCurrent(mine))
            throw IOException("Native connection authority changed")
        return authority
    }

    private suspend fun rpcOn(mine: Connection, method: String, params: JSONObject, normalizeLegacyCreateSource: Boolean = false,
        sendGuard: (() -> Unit)? = null, sendLock: Any? = null): JSONObject {
        if (connection !== mine || !connectionIdentityCurrent(mine)) {
            if (sendGuard != null) throw NativeTurnNotSent("Native connection changed before the request")
            throw IOException("Native connection changed before request")
        }
        val id = UUID.randomUUID().toString(); val result = CompletableDeferred<JSONObject>()
        mine.pending[id] = result
        try {
            mine.failure?.let { if (sendGuard != null) throw NativeTurnNotSent("Native connection changed before the request", it) else throw unknown(it) }
            val requestScope = client.scope
            val wireParams = params.copy().put("profile", requestScope.profile)
            var canonicalCreate = false
            if (method == "session.resume" || method == "session.create") {
                // Create and resume fields differ between Hermes authorities; wait for this socket's ready event.
                val authority = withTimeoutOrNull(4000) { mine.sessionAuthority.await() }
                    ?: throw IOException("Native gateway readiness is unavailable")
                if (connection !== mine || mine.failure != null || client.scope != requestScope) throw IOException("Native connection or conversation scope changed before request")
                if (method == "session.resume") {
                    wireParams.put("source", "android")
                    if (authority) {
                        if (wireParams.has("lazy") && wireParams.opt("lazy") != false)
                            throw IOException("Native authority does not offer lazy session recovery")
                        wireParams.remove("lazy")
                        wireParams.remove("eager_build")
                        wireParams.remove("close_on_disconnect")
                        wireParams.remove("inline_images")
                    } else {
                        // Keep required history, without retransmitting legacy inline photos beyond the frame cap.
                        wireParams.put("inline_images", false)
                    }
                } else if (authority) {
                    canonicalCreate = true
                    val source = wireParams.string("source")
                    if (source in THREAD_SOURCES) throw NativeThreadUnsupported()
                    if (source == "android") wireParams.remove("source")
                    if (wireParams.has("close_on_disconnect")) {
                        if (wireParams.opt("close_on_disconnect") != false) throw IOException("Native authority cannot apply this disconnect policy")
                        wireParams.remove("close_on_disconnect")
                    }
                } else if (normalizeLegacyCreateSource) {
                    wireParams.put("source", if (wireParams.string("source") == BEACON_THREAD_SOURCE) BEACON_THREAD_SOURCE else "android")
                    // The legacy contract refuses an unknown key as version skew; its create has no retry identity (apps/desktop/src/api/client.ts, LEGACY_STRIP).
                    wireParams.remove("request_id")
                }
            }
            currentCoroutineContext().ensureActive()
            sendFrame(mine, json("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to wireParams).toString(), sendGuard, sendLock)
            val response = try { withTimeout(rpcTimeoutMs) { result.await() } } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); throw unknown(e) }
            return finishRpc(method, params, response, canonicalCreate, requestScope)
        } finally { mine.pending.remove(id) }
    }

    /** Sends one frame as text, after the same checks as always; anything that fails after it may have left the phone is an unknown outcome. */
    private fun sendFrame(mine: Connection, frame: String, sendGuard: (() -> Unit)?, sendLock: Any?) {
            val sent = synchronized(lifecycle) {
                val guardedSend = {
                    if (connection !== mine || mine.failure != null || !connectionIdentityCurrent(mine)) throw NativeTurnNotSent("Native connection changed before the request")
                    sendGuard?.invoke()
                    try {
                        if (mine.canonical) mine.socket?.send(frame)
                        else {
                            var attempted = false
                            val sent = client.withLegacyCanonicalPermit(mine.identity) { attempted = true; mine.socket?.send(frame) == true }
                            if (!attempted) throw NativeTurnNotSent("Canonical gateway authority is required")
                            sent
                        }
                    } catch (e: NativeTurnNotSent) { throw e } catch (e: Exception) { throw unknown(e) }
                }
                if (sendLock == null) guardedSend() else synchronized(sendLock) { guardedSend() }
            }
            if (sent != true) throw unknown(IOException("Native connection unavailable"))
    }

    /** A socket of one file (form 4, CHAT-121 follow-up 9): what its listener has seen. */
    private class FileLink {
        val ready = CompletableDeferred<Unit>()
        val answers = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
        @Volatile var socket: WebSocket? = null
        @Volatile var failure: IOException? = null
        /** The plugin's latest acknowledgment of the file's pieces: the frame's request id, the base64 it holds (PIECE_ACK). */
        @Volatile var ack: Pair<String, Long>? = null
        fun fail(error: IOException) {
            if (failure == null) failure = error
            ready.completeExceptionally(error)
            answers.values.forEach { it.completeExceptionally(error) }
        }
    }
    /** One try of a file failed on the file's own socket (it could not open or join the chat, it closed, or the plugin gave up on its pieces). */
    private class FileLinkLost(cause: Throwable) : IOException("The file's connection failed", cause)

    /**
     * Sends one frame that carries a whole file ([inline]) on a socket of its own (CHAT-121 follow-up 9, the plugin's form 4), never on the chat's [mine]: the
     * chat's pongs never wait behind the file's data, and a file that cannot get through ends only its own socket. Each try opens that socket with its own
     * ticket, attaches it to the chat's session (Hermes stages a file only for a session the connection is attached to), sends the frame in pieces sized
     * and paced from the plugin's acknowledgments ([PieceSizer]), and closes it once Hermes has answered. A try whose socket fails, or that the plugin gave
     * up on ("stalled"), goes again on a new socket in smaller pieces; after [ATTACHMENT_STALL_TRIES] the file is not sent ([AttachmentStalled]), and a
     * queued message with it waits for the person. [bound] fails when the chat this file is for is no longer the one held: before any piece went, that ends
     * the send as the chat's own change ([NativeTurnNotSent]); once pieces were on the way, the file may have caused it (one upload queue), so it is not
     * sent and waits for the person ([AttachmentStalled]): a file takes the chat's socket down at most once in a row.
     */
    private suspend fun attachFile(mine: Connection, binding: NativeSessionBinding, method: String, params: JSONObject, inline: InlineFile, bound: () -> Unit): JSONObject {
        val limits = mine.attachLimits
        if (!mine.canonical || !limits.pieces) throw NativeTurnNotSent("This connection takes no file frames in pieces")
        val requestScope = client.scope
        val wire = params.copy().put("profile", requestScope.profile)
        if (inlineFrameBytes(json("jsonrpc" to "2.0", "id" to UUID.randomUUID().toString(), "method" to method, "params" to wire), inline) > limits.frameBytes)
            throw NativeTurnNotSent("The file is larger than this connection takes")
        var sizer = PieceSizer()
        var tries = 0
        while (true) {
            synchronized(lifecycle) { bound() }
            // One try at a time holds the lock (a second file waits here, and goes between a stuck file's tries); every wait inside a try has a bound
            // ([attachFileOnce]), so the lock is never held for ever. A cancelled wait takes nothing.
            try { return fileUploads.withLock { finishRpc(method, params, attachFileOnce(mine, binding, method, wire, inline, sizer, bound), false, requestScope) } }
            catch (e: FileLinkLost) {
                if (++tries >= ATTACHMENT_STALL_TRIES) throw AttachmentStalled(e)
                // The next try starts again from a small piece and grows only on its own acknowledgments (follow-up 10).
                sizer = sizer.nextTry()
            } catch (e: NativeTurnNotSent) {
                // The chat's own socket was lost while this file's pieces were on the way. A phone's upload is one queue, so a sudden deep drop can
                // close it with them: the file is not sent and waits for the person, as a lost one-frame file does, so it cannot happen again by itself.
                if (sizer.largest > 0 && runCatching { synchronized(lifecycle) { bound() } }.isFailure) throw AttachmentStalled(e)
                throw e
            }
        }
    }

    /** One try of [attachFile]: the file's socket opened, attached to the chat's session, the frame sent; Hermes' answer, or [FileLinkLost]. */
    private suspend fun attachFileOnce(mine: Connection, binding: NativeSessionBinding, method: String, wire: JSONObject, inline: InlineFile,
        sizer: PieceSizer, bound: () -> Unit): JSONObject {
        val link = openFileLink(mine)
        var answered = false
        try {
            fun live() { synchronized(lifecycle) { bound() }; link.failure?.let { throw FileLinkLost(it) } }
            suspend fun answer(pending: CompletableDeferred<JSONObject>, timeoutMs: Long): JSONObject = try { withTimeout(timeoutMs) { pending.await() } }
                catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); throw FileLinkLost(e) }
                catch (e: IOException) { throw if (e is FileLinkLost) e else FileLinkLost(e) }
            fun ask(id: String) = CompletableDeferred<JSONObject>().also { link.answers[id] = it }
            // Attach this socket to the chat's session, as the chat's own resume does (omit_messages: no history).
            val resume = UUID.randomUUID().toString()
            val resumeAnswer = ask(resume)
            live()
            if (link.socket?.send(json("jsonrpc" to "2.0", "id" to resume, "method" to "session.resume", "params" to json("session_id" to binding.storedSessionId,
                    "omit_messages" to true, "source" to "android", "profile" to client.scope.profile)).toString()) != true)
                throw FileLinkLost(IOException("The file's connection is unavailable"))
            val resumed = answer(resumeAnswer, 15_000).optJSONObject("result")
            if (resumed == null || runCatching { nativeSessionBinding(binding.scope, resumed) }.getOrNull() != binding)
                throw FileLinkLost(IOException("The file's connection could not join the chat"))
            val id = UUID.randomUUID().toString()
            val pending = ask(id)
            val envelope = json("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to wire)
            // The upload's own bound: a socket that stays open (it answers pings) but shows no new acknowledgment for [attachmentSilenceMs] while the file is
            // on the way loses this try. Without it a stuck plugin would hold the one upload lock, and so every later file on this phone, for ever.
            var acknowledged = 0L
            var progressAt = System.nanoTime()
            fun acks(): Long = (link.ack?.takeIf { it.first == id }?.second ?: 0L).also { if (it > acknowledged) { acknowledged = it; progressAt = System.nanoTime() } }
            fun silent() { if ((System.nanoTime() - progressAt) / 1_000_000 > attachmentSilenceMs) throw FileLinkLost(IOException("The file's connection went silent")) }
            val queued = try {
                streamInlineFrame(envelope, inline, send = { piece -> live(); link.socket?.send(piece) == true },
                    queued = { link.socket?.queueSize() ?: 0L }, pause = { live(); silent(); delay(10) },
                    acked = ::acks, answered = { pending.isCompleted }, sizer = sizer)
            } catch (e: CancellationException) { throw e } catch (e: NativeTurnNotSent) { throw e } catch (e: FileLinkLost) { throw e }
            catch (e: IllegalArgumentException) { throw NativeTurnNotSent("The file could not be sent", e) }  // it changed while it was read
            catch (e: Exception) { throw FileLinkLost(e) }
            if (!queued && !pending.isCompleted) throw FileLinkLost(link.failure ?: IOException("The file's connection refused a piece"))
            // The answer's wait starts once the plugin holds the whole file (or it answered first: "busy" at the header, "stalled").
            val response = answer(pending, rpcTimeoutMs)
            if (attachmentStalled(response)) throw FileLinkLost(ApiFailure(408, ATTACHMENT_STALLED, plugin = true))
            answered = true
            return response
        } finally {
            if (answered) link.socket?.close(1000, null) else link.socket?.cancel()
        }
    }

    /** Opens a socket of one file ([attachFile]): its own ticket ({"purpose": "file"}), the chat's device and profile; a failure is [FileLinkLost]. */
    private suspend fun openFileLink(mine: Connection): FileLink {
        if (!connectionIdentityCurrent(mine)) throw NativeTurnNotSent("Native connection changed before the request")
        val started = System.nanoTime()
        val admission = try { client.nativeRequest(CANONICAL_TICKET_PATH, "POST", json("purpose" to "file")) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { throw FileLinkLost(e) }
        currentCoroutineContext().ensureActive()
        if (!connectionIdentityCurrent(mine)) throw NativeTurnNotSent("Native connection changed before the request")
        val seconds = when (val value = admission.opt("expires_in")) { is Int -> value.toLong(); is Long -> value; else -> 0L }
        val ticket = (admission.opt("ticket") as? String)?.let { runCatching { nativeConnectionTicket(it) }.getOrNull() }
        if (ticket == null || seconds !in 1..25 || admission.opt("path") != CANONICAL_SOCKET_PATH || admission.opt("profile") != mine.identity.scope.profile ||
            admission.opt("canonical") != true || admission.opt("purpose") != "file") throw FileLinkLost(IOException("Invalid file connection admission"))
        val request = client.request(CANONICAL_SOCKET_PATH).removeHeader("Authorization").removeHeader(HERMES_LOOPBACK_SESSION_HEADER)
            .header("Sec-WebSocket-Protocol", "beacon-canonical-v1, beacon-ticket.$ticket").build()
        if (request.header("X-Beacon-Device-ID") != mine.device) throw NativeTurnNotSent("Native device changed before the request")
        if (System.nanoTime() >= started + seconds * 1_000_000_000L) throw FileLinkLost(IOException("File connection ticket expired"))
        val link = FileLink()
        link.socket = socketFactory.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                link.socket = ws
                // It answers none of Hermes' questions (the chat's socket does), and the plugin sends it none.
                ws.send(json("jsonrpc" to "2.0", "id" to "capabilities-${UUID.randomUUID()}", "method" to "client.capabilities",
                    "params" to json("server_requests" to false)).toString())
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) { pieceAcknowledgment(bytes)?.let { link.ack = it } }
            override fun onMessage(ws: WebSocket, text: String) {
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                val event = frame.optJSONObject("params")
                if (frame.optString("method") == "event" && event?.optString("type") == "gateway.ready") {
                    if (event.optJSONObject("payload")?.opt("session_authority") == true) link.ready.complete(Unit)
                    else { link.fail(IOException("Canonical gateway did not become ready")); ws.cancel() }
                } else if (!frame.has("method")) frame.optString("id").takeIf(String::isNotBlank)?.let { link.answers.remove(it)?.complete(frame) }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { link.fail(IOException("The file's connection failed", t)) }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { link.fail(IOException("The file's connection closed ($code)")); ws.close(code, reason) }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { link.fail(IOException("The file's connection closed ($code)")) }
        })
        try { withTimeout(15_000) { link.ready.await() } }
        catch (e: TimeoutCancellationException) { link.socket?.cancel(); currentCoroutineContext().ensureActive(); throw FileLinkLost(e) }
        catch (e: CancellationException) { link.socket?.cancel(); throw e }  // the send was cancelled while the socket opened (a timeout is a CancellationException: its catch is first)
        catch (e: IOException) { link.socket?.cancel(); throw FileLinkLost(e) }
        return link
    }

    private fun finishRpc(method: String, params: JSONObject, response: JSONObject, canonicalCreate: Boolean, requestScope: Scope): JSONObject {
            response.optJSONObject("error")?.let {
                // 4001: Hermes no longer holds this runtime (reaped or evicted). Nothing may keep addressing it; the next use resumes the stored conversation.
                if (method !in setOf("session.activate", "session.resume") && it.optInt("code", 500) == 4001 && it.optString("message") == "session not found") forget(params.string("session_id"))
                // The plugin's own refusal of a frame it relays (a file frame it cannot take now: "busy") says so in its data. CHATQA-121 follow-up 5:
                // Hermes' refusal that names a field (admission_conflict on request_id) keeps it; such a refusal is Hermes', never the plugin's.
                val data = it.optJSONObject("data")
                val field = data?.optString("field").orEmpty()
                throw if (field.isBlank()) ApiFailure(it.optInt("code", 500), it.optString("message", "Native operation failed"), plugin = data?.optString("source") == "beacon")
                    else NativeRefusal(it.optInt("code", 500), it.optString("message", "Native operation failed"), field)
            }
            val output = response.optJSONObject("result") ?: JSONObject().put("value", response.opt("result"))
            if (canonicalCreate) {
                try {
                    require(output.opt("session_id") is String && output.opt("stored_session_id") is String) { "Canonical create returned invalid identities" }
                    val binding = nativeSessionBinding(requestScope, output)
                    require(binding.runtimeSessionId == binding.storedSessionId) { "Canonical create returned conflicting identities" }
                    output.optJSONObject("info")?.let { info ->
                        if (info.has("profile_name") && !info.isNull("profile_name"))
                            require(info.opt("profile_name") is String && info.opt("profile_name") == requestScope.profile) { "Canonical create returned a different profile" }
                    }
                } catch (e: IllegalArgumentException) {
                    throw unknown(e)
                }
            }
            return output
    }

    /** Stage visual evidence and submit on one socket; a legacy file reference never uses the shared image queue. */
    suspend fun voiceSubmit(binding: NativeSessionBinding, params: JSONObject, jpeg: String?, legacyJobCapacity: Boolean,
        sendLock: Any, callOwned: () -> Boolean, markAttempt: () -> Boolean, permitted: () -> Boolean): JSONObject {
        val mine = connect()
        fun bound(): Boolean = binding.scope == client.scope && synchronized(cursorLock) {
            cursor?.let { it.binding == binding && it.connection === mine && it.live() } == true
        }
        if (!bound()) return JSONObject().put("status", "not_sent").put("reason", "gateway_unconfirmed")
        val authority = withTimeoutOrNull(4000) { mine.sessionAuthority.await() }
            ?: return JSONObject().put("status", "not_sent").put("reason", "gateway_unconfirmed")
        if (!bound()) return JSONObject().put("status", "not_sent").put("reason", "gateway_unconfirmed")
        if (!authority && !legacyJobCapacity) return JSONObject().put("status", "not_sent").put("reason", "job_capacity")
        val request = params.copy()
        if (!authority) {
            // The legacy prompt contract forbids canonical admission identities.
            request.remove("submission_id")
            request.remove("input_id")
        }
        var visual = "none"
        var stageRefusal = "gateway_unconfirmed"
        fun guardStage() {
            if (!callOwned()) { stageRefusal = "call_ended"; throw NativeTurnNotSent("Call ended before native staging") }
            if (!bound()) throw NativeTurnNotSent("Native conversation changed before staging")
            if (!permitted()) { stageRefusal = "visual_unavailable"; throw NativeTurnNotSent("Visual permission changed before staging") }
            if (!callOwned()) { stageRefusal = "call_ended"; throw NativeTurnNotSent("Call ended before native staging") }
            if (!bound()) throw NativeTurnNotSent("Native conversation changed before staging")
        }
        if (jpeg != null && permitted()) {
            try {
                if (authority) {
                    val staged = rpcOn(mine, "image.attach_bytes", json("session_id" to binding.runtimeSessionId,
                        "content_base64" to jpeg, "filename" to "voice-frame.jpg"), sendGuard = ::guardStage, sendLock = sendLock)
                    require(staged.optBoolean("attached") && staged.string("path").isNotBlank() && staged.string("mime") == "image/jpeg") { "Native image staging was not confirmed" }
                    if (permitted() && bound()) {
                        request.put("attachments", JSONArray().put(json("path" to staged.string("path"), "mime" to "image/jpeg")))
                        visual = "image"
                    } else visual = "unavailable"
                } else {
                    val staged = rpcOn(mine, "file.attach", json("session_id" to binding.runtimeSessionId,
                        "data_url" to "data:image/jpeg;base64,$jpeg", "name" to "voice-frame.jpg"), sendGuard = ::guardStage, sendLock = sendLock)
                    val ref = staged.string("ref_text")
                    require(staged.optBoolean("attached") && ref.startsWith("@file:") && ref.length <= 2048 && ref.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Native file staging was not confirmed" }
                    if (permitted() && bound()) {
                        request.put("text", request.getString("text") + "\n" + ref)
                        visual = "file_reference"
                    } else visual = "unavailable"
                }
            } catch (e: NativeTurnNotSent) {
                if (stageRefusal != "visual_unavailable") return JSONObject().put("status", "not_sent").put("reason", stageRefusal)
                visual = "unavailable"
            } catch (e: ApiFailure) {
                visual = "unavailable"
            }
        } else if (jpeg != null) visual = "unavailable"
        if (!bound()) return JSONObject().put("status", "not_sent").put("reason", "gateway_unconfirmed")
        if (visual == "unavailable") request.put("text", request.getString("text") + "\n[The current shared image could not be attached.]")
        var notSentReason = "gateway_unconfirmed"
        val result = try {
            rpcOn(mine, "prompt.submit", request, sendGuard = {
                if (!callOwned()) { notSentReason = "call_ended"; throw NativeTurnNotSent("Call ended before native submission") }
                if (!bound() || !markAttempt()) throw NativeTurnNotSent("Native conversation changed before submission")
            }, sendLock = sendLock)
        } catch (e: NativeTurnNotSent) {
            return JSONObject().put("status", "not_sent").put("reason", notSentReason)
        } catch (e: ApiFailure) {
            // The legacy dispatcher emits this specific extra-field refusal before invoking the handler.
            if (!authority && e.status == 4000 && e.message.orEmpty().startsWith("invalid params for prompt.submit: "))
                return JSONObject().put("status", "not_sent").put("reason", "native_invalid_params")
            throw e
        }
        if (result.optString("status") !in setOf("streaming", "queued", "steered", "redirected", "started", "terminal", "unknown"))
            throw IOException("Native prompt admission was not confirmed")
        return result.put("voice_visual", visual).put("voice_authority", authority)
    }

    /** The live connection of this gateway that received [requestId] for its bound conversation, if any. */
    private fun openServerRequest(method: String, requestId: String, sessionId: String, secureConnection: String = ""): Triple<NativeGateway, Connection, NativeServerRequest>? {
        val mine = connection?.takeIf { it.failure == null && it.ready.isCompleted } ?: return null
        if (method in SECURE_SERVER_REQUESTS && (secureConnection.isBlank() || secureConnection != mine.secureConnection || !secureCurrent(mine))) return null
        val binding = synchronized(cursorLock) { cursor?.binding }
        if (binding == null || binding.scope != client.scope || binding.runtimeSessionId != sessionId) return null
        val open = synchronized(mine.requestLock) {
            mine.serverRequests.values.filter { request ->
                request.method == method && request.sessionId == sessionId &&
                    (method !in SECURE_SERVER_REQUESTS || System.nanoTime() / 1_000_000 < request.expiresAt) &&
                    (if (method == "approval") request.params.string("request_id") == requestId else request.idText == requestId)
            }.singleOrNull()
        } ?: return null
        return Triple(this, mine, open)
    }

    // Adapted from Hermes Relay's GatewayChatClient request lifecycle; Beacon keeps its existing native session cursor.
    internal fun answerServerRequest(method: String, requestId: String, sessionId: String, payload: JSONObject): JSONObject {
        // A chat action answers from its own detached client, but the request stays owned by the stream connection that received it.
        val peers = synchronized(gateways) { gateways.filter { it !== this && it.client.scope == client.scope } }
        val (owner, mine, open) = (listOf(this) + peers).firstNotNullOfOrNull { it.openServerRequest(method, requestId, sessionId, payload.optString("secure_connection")) }
            ?: throw NativeRequestEnded()
        val result = when (method) {
            "approval" -> {
                val choice = payload.string("choice")
                val offered = open.params.optJSONArray("choices")?.let { choices -> (0 until choices.length()).map { choices.optString(it) } }.orEmpty()
                if (choice !in offered.ifEmpty { listOf("deny") }) throw IOException("Approval choice was not offered by Hermes")
                JSONObject().put("choice", choice).apply {
                    if (payload.has("all")) {
                        if (payload.opt("all") !is Boolean) throw IOException("Invalid approval response")
                        put("all", payload.getBoolean("all"))
                    }
                }
            }
            "clarify" -> {
                val questions = open.params.optJSONArray("questions") ?: throw IOException("Invalid clarify request")
                val qids = (0 until questions.length()).map { questions.getJSONObject(it).getString("qid") }
                val answers = payload.optJSONObject("answers")?.copy() ?: run {
                    if (qids.size != 1 || !payload.has("response") || payload.opt("response") !is String) throw IOException("Answer every clarification question before continuing")
                    JSONObject().put(qids.single(), payload.getString("response"))
                }
                val answerIds = answers.keys().asSequence().toSet()
                if (answerIds != qids.toSet() || answerIds.size != qids.size || qids.any { answers.opt(it) !is String && answers.opt(it) != JSONObject.NULL }) {
                    throw IOException("Answer every clarification question before continuing")
                }
                JSONObject().put("answers", answers).put("outcome", "submitted")
            }
            in SECURE_SERVER_REQUESTS -> {
                if (payload.keys().asSequence().toSet() != setOf("value", "secure_connection")) throw IOException("Invalid secure response")
                val value = payload.opt("value") as? String ?: throw IOException("Invalid secure response")
                if (value.length > 8192) throw IOException("Invalid secure response")
                if (method == "vault.save_login" && value.isNotEmpty()) {
                    val login = try { JSONObject(value) } catch (_: Exception) { throw IOException("Invalid secure response") }
                    if (login.keys().asSequence().toSet() != setOf("identifier", "password") ||
                        login.opt("identifier") !is String || login.opt("password") !is String ||
                        login.optString("identifier").isBlank() || login.optString("password").isBlank()) throw IOException("Invalid secure response")
                }
                JSONObject().put("value", value)
            }
            else -> throw IOException("Not available on the phone")
        }
        synchronized(mine.requestLock) {
            if (mine.serverRequests[open.idKey] !== open || owner.connection !== mine || mine.failure != null || open.socket !== mine.socket ||
                method in SECURE_SERVER_REQUESTS && (System.nanoTime() / 1_000_000 >= open.expiresAt || !owner.secureCurrent(mine))) {
                throw IOException("Native server request is no longer active")
            }
            val response = JSONObject().put("jsonrpc", "2.0").put("id", open.id).put("result", result)
            val sent = if (method in SECURE_SERVER_REQUESTS) runCatching { owner.client.enqueueSecureResponse(mine.secureAdmission) { open.socket.send(response.toString()) } }.getOrDefault(false)
                else open.socket.send(response.toString())
            if (!sent) {
                if (method in SECURE_SERVER_REQUESTS) retireServerRequestLocked(mine, open)
                throw IOException("Native server request response could not be sent")
            }
            retireServerRequestLocked(mine, open)
        }
        return JSONObject().put("ok", true)
    }

    /** A JSON-RPC request needs an id; a method-only frame (for example `message.complete`) is an event notification. */
    private fun isServerRequest(frame: JSONObject) = frame.has("id") && frame.has("method") && frame.optString("method") != "event"

    private fun requestId(frame: JSONObject): ParsedNativeRequestId? {
        if (!frame.has("id")) return null
        return when (val id = frame.opt("id")) {
            is String -> id.takeIf(String::isNotBlank)?.let { ParsedNativeRequestId("s:$it", it) }
            is Number -> id.toString().takeIf(String::isNotBlank)?.let { ParsedNativeRequestId("n:$it", it) }
            else -> null
        }
    }

    /**
     * Hermes sends these questions only to a viewer that said it can answer them (client.capabilities server_requests), so no plugin has to
     * vouch for them. The phone still refuses to take a secret over plain http, except to this device.
     */
    private fun securePromptsAvailable(mine: Connection): Boolean {
        val origin = runCatching { java.net.URI(client.scope.origin) }.getOrNull() ?: return false
        val host = origin.host ?: return false
        return origin.userInfo == null && (origin.scheme == "https" || origin.scheme == "http" &&
            (host.equals("localhost", true) || host in setOf("127.0.0.1", "::1", "[::1]"))) && connection === mine && mine.failure == null && secureCurrent(mine)
    }

    /** The first time this question was seen, so a replay keeps Hermes' deadline instead of starting a new window. */
    private fun secureReceivedMs(sessionId: String, method: String, idKey: String, received: Long): Long {
        if (secureFirstSeen.size > 256) secureFirstSeen.entries.removeIf { received - it.value > 600_000 }
        return secureFirstSeen.getOrPut("${client.scope.key}\u0000$sessionId\u0000$method\u0000$idKey") { received }
    }

    /** Only the fields needed for the protected form leave the socket reader. */
    private fun secureRequestParams(method: String, params: JSONObject): JSONObject? {
        fun text(key: String, limit: Int, required: Boolean = true): String? {
            val value = params.opt(key)
            if (value == null || value == JSONObject.NULL) return if (required) null else ""
            return (value as? String)?.takeIf { it.length <= limit && (!required || it.isNotBlank()) && it.none(Char::isISOControl) }
        }
        val session = text("session_id", 256) ?: return null
        val clean = JSONObject().put("session_id", session)
        when (method) {
            "vault.save_login" -> {
                val origin = text("origin", 2048) ?: return null
                val uri = runCatching { java.net.URI(origin) }.getOrNull() ?: return null
                uri.host ?: return null
                if (uri.scheme != "https" || uri.userInfo != null ||
                    uri.rawQuery != null || uri.rawFragment != null || uri.rawPath !in setOf("", "/")) return null
                text("site", 255) ?: return null
                clean.put("origin", origin)
            }
            "vault.code" -> {
                val site = text("site", 255) ?: return null
                val uri = runCatching { java.net.URI("https://$site") }.getOrNull() ?: return null
                if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.rawPath != "" || uri.rawQuery != null || uri.rawFragment != null) return null
                text("hint", 240, false) ?: return null
                clean.put("site", site)
            }
            "vault.unlock_prompt" -> {
                val backend = text("backend", 64)?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) } ?: return null
                clean.put("backend", backend).put("display_name", text("display_name", 120) ?: return null)
            }
            "secret" -> {
                val variable = text("env_var", 128)?.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) } ?: return null
                text("prompt", 240) ?: return null
                clean.put("env_var", variable)
            }
            else -> return null
        }
        return clean
    }

    private fun prepareServerRequest(mine: Connection, frame: JSONObject): NativeServerRequest? {
        val method = frame.optString("method")
        val id = frame.opt("id") ?: JSONObject.NULL
        val idParts = requestId(frame)
        if (idParts == null) {
            val responseId = when (id) { is String, is Number -> id; else -> JSONObject.NULL }
            serverRequestError(mine, responseId, -32600, "Invalid request id")
            return null
        }
        if (frame.optString("jsonrpc") != "2.0") {
            serverRequestError(mine, id, -32600, "Invalid JSON-RPC request")
            return null
        }
        if (method in WINDOW_SERVER_REQUESTS) {
            serverRequestError(mine, id, 4404, "This request is not shown on the phone")
            return null
        }
        if (method != "approval" && method != "clarify" && method !in SECURE_SERVER_REQUESTS) {
            serverRequestError(mine, id, -32601, "Not available on the phone")
            return null
        }
        val params = frame.optJSONObject("params")
        if (params == null) {
            serverRequestError(mine, id, -32602, "Invalid request parameters")
            return null
        }
        val sessionId = params.string("session_id")
        val binding = synchronized(cursorLock) { cursor?.binding }
        if (sessionId.isBlank() || binding == null || binding.scope != client.scope || binding.runtimeSessionId != sessionId) {
            serverRequestError(mine, id, -32602, "Request is not owned by this conversation")
            return null
        }
        val secure = method in SECURE_SERVER_REQUESTS
        if (secure && (idParts.text.length > 160 || !idParts.text.matches(Regex("[A-Za-z0-9_.:-]+")))) {
            serverRequestError(mine, id, -32600, "Invalid request id")
            return null
        }
        val safeParams = if (secure) secureRequestParams(method, params) else params
        if (safeParams == null) {
            if (secure) synchronized(mine.requestLock) { rememberRetiredRequestLocked(mine, NativeRequestIdentity(idParts.key, method, sessionId)) }
            serverRequestError(mine, id, -32602, "Invalid secure request")
            return null
        }
        if (method == "approval" && params.string("request_id").isBlank()) {
            serverRequestError(mine, id, -32602, "Invalid approval request")
            return null
        }
        if (method == "clarify") {
            val questions = params.optJSONArray("questions")
            val qids = questions?.let { rows -> (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.string("qid")?.takeIf(String::isNotBlank) } }.orEmpty()
            val valid = questions != null && questions.length() in 1..5 && qids.size == questions.length() && qids.distinct().size == qids.size &&
                (0 until questions.length()).all { questions.optJSONObject(it)?.string("question")?.isNotBlank() == true }
            if (!valid) {
                serverRequestError(mine, id, -32602, "Invalid clarify request")
                return null
            }
        }
        val identity = NativeRequestIdentity(idParts.key, method, sessionId)
        val now = System.nanoTime() / 1_000_000
        val window = securePromptWindowMs(method)
        var expiresAt = if (secure) secureReceivedMs(sessionId, method, idParts.key, frame.optLong("_secure_received_ms", now)) + window else Long.MAX_VALUE
        if (secure && params.has("expires_at_ms")) {
            val raw = params.opt("expires_at_ms")
            if (raw !is Number || !raw.toDouble().isFinite() || raw.toDouble() != raw.toLong().toDouble() || raw.toLong() <= 0) {
                synchronized(mine.requestLock) { rememberRetiredRequestLocked(mine, identity) }
                serverRequestError(mine, id, -32602, "Invalid secure request")
                return null
            }
            val remaining = raw.toLong() - System.currentTimeMillis()
            expiresAt = minOf(expiresAt, now + remaining.coerceIn(0, window))
        }
        if (secure && expiresAt <= now) {
            synchronized(mine.requestLock) { rememberRetiredRequestLocked(mine, identity) }
            serverRequestError(mine, id, -32000, "Secure request expired")
            return null
        }
        var failure: Pair<Int, String>? = null
        val open = synchronized(mine.requestLock) {
            mine.serverRequests.values.filter { it.method in SECURE_SERVER_REQUESTS && now >= it.expiresAt }.toList().forEach { retireServerRequestLocked(mine, it) }
            if (identity in mine.retiredServerRequests) return null
            val previous = mine.serverRequests[idParts.key]
            when {
                secure && previous == null && idParts.key in mine.secureIds -> return null
                secure && previous == null && (mine.secureExhausted || mine.secureIds.size >= 256) -> {
                    mine.secureExhausted = true
                    failure = -32000 to "Secure request capacity reached"
                    null
                }
                previous != null && (previous.method != method || previous.sessionId != sessionId) -> {
                    failure = -32602 to "Request identity changed"
                    null
                }
                // A replayed request keeps its identity and takes the latest parameters.
                previous != null && secure && previous.params.toString() != safeParams.toString() -> {
                    failure = -32602 to "Request identity changed"
                    null
                }
                previous != null -> previous.also { if (!secure) it.params = safeParams.copy() }
                mine.serverRequests.size >= 64 -> {
                    failure = -32000 to "Too many open requests"
                    null
                }
                else -> NativeServerRequest(id, idParts.text, idParts.key, method, sessionId, safeParams.copy(), mine.socket!!, expiresAt)
                    .also { if (secure) mine.secureIds.add(idParts.key); mine.serverRequests[idParts.key] = it }
            }
        }
        failure?.let { serverRequestError(mine, id, it.first, it.second) }
        return open
    }

    private fun retireCancelledServerRequest(mine: Connection, frame: JSONObject) {
        val params = frame.optJSONObject("params") ?: return
        if (params.string("type", frame.optString("method")) != "request.cancel") return
        val payload = params.optJSONObject("payload") ?: params
        val idParts = requestId(json("id" to payload.opt("id"))) ?: return
        val method = payload.string("method")
        val sessionId = params.string("session_id").ifBlank { payload.string("session_id") }
        if (method.isBlank() || sessionId.isBlank()) return
        synchronized(mine.requestLock) {
            val identity = NativeRequestIdentity(idParts.key, method, sessionId)
            mine.serverRequests[idParts.key]?.takeIf { it.method == method && it.sessionId == sessionId }?.let { mine.serverRequests.remove(idParts.key) }
            rememberRetiredRequestLocked(mine, identity)
        }
    }

    private fun retireServerRequestLocked(mine: Connection, request: NativeServerRequest) {
        if (mine.serverRequests[request.idKey] === request) mine.serverRequests.remove(request.idKey)
        rememberRetiredRequestLocked(mine, request.identity)
    }

    private fun rememberRetiredRequestLocked(mine: Connection, identity: NativeRequestIdentity) {
        if (identity.method in SECURE_SERVER_REQUESTS && identity.idKey !in mine.secureIds) {
            if (mine.secureIds.size >= 256) mine.secureExhausted = true else mine.secureIds.add(identity.idKey)
        }
        mine.retiredServerRequests += identity
        if (mine.retiredServerRequests.size > 256) mine.retiredServerRequests.remove(mine.retiredServerRequests.first())
    }

    private fun serverRequestError(mine: Connection, id: Any, code: Int, message: String) {
        if (connection !== mine || mine.failure != null) return
        val response = JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
        if (mine.socket?.send(response.toString()) != true) terminate(mine, IOException("Native server request error could not be sent"))
    }

    private var sessionState = JSONObject()
    private fun Cursor.live() = connection != null && connection === this@NativeGateway.connection && connection.failure == null && connection.ready.isCompleted
    /** The runtime this gateway holds right now: empty once its socket has closed, so a poller never names a runtime Hermes may have reaped. */
    internal fun sessionSnapshot(): JSONObject = synchronized(cursorLock) { if (cursor?.live() == true) sessionState.copy() else JSONObject() }
    private fun forget(runtime: String) { if (runtime.isNotBlank()) synchronized(cursorLock) { if (cursor?.binding?.runtimeSessionId == runtime) { cursor = null; sessionState = JSONObject() } } }
    private fun bind(result: JSONObject, mine: Connection, scope: Scope): NativeSessionBinding {
        val binding = nativeSessionBinding(scope, result)
        synchronized(lifecycle) {
            check(connection === mine && mine.failure == null && client.scope == scope) { "Native connection or conversation scope changed" }
            synchronized(cursorLock) { sessionState = result.copy(); cursor = Cursor(binding, mine, epoch = result.string("replay_epoch")) }
        }
        return binding
    }
    suspend fun create(params: JSONObject): NativeSessionBinding = sessionLock.withLock {
        val scope = client.scope
        val mine = connect()
        val requested = params.copy().apply { if (!has("source")) put("source", "android") }.put("close_on_disconnect", false)
        bind(rpcOn(mine, "session.create", requested, normalizeLegacyCreateSource = true), mine, scope)
    }
    /** [lazy]: reattach a live unsent shell or answer 4007, never let Hermes materialize an ownerless row for a runtime that is gone. */
    suspend fun session(stored: String?, lazy: Boolean = false): NativeSessionBinding = sessionLock.withLock {
        synchronized(cursorLock) { cursor }?.takeIf { it.live() && it.binding.scope == client.scope && (stored == it.binding.storedSessionId || stored == it.binding.runtimeSessionId) }?.let { return@withLock it.binding }
        val scope = client.scope
        val mine = connect()
        val authority = withTimeoutOrNull(4000) { mine.sessionAuthority.await() }
            ?: throw IOException("Native gateway readiness is unavailable")
        val legacy = !authority
        val result = rpcOn(mine, if (stored.isNullOrBlank()) "session.create" else "session.resume", if (stored.isNullOrBlank()) json("source" to "android", "close_on_disconnect" to false) else json("session_id" to stored, "close_on_disconnect" to false, "omit_messages" to false).apply { if (lazy && legacy && !stored.isNullOrBlank()) put("lazy", true) })
        if (authority) require(result.opt("session_id") is String && result.opt("stored_session_id") == result.opt("session_id")) {
            "Canonical resume returned conflicting conversation identities"
        }
        bind(result, mine, scope)
    }
    /** Reattach the original live runtime before replay; replay alone never creates a viewer. */
    private suspend fun recoverCursorLocked(mine: Connection): Cursor? {
        val previous = synchronized(cursorLock) { cursor } ?: return null
        if (previous.connection === mine && previous.live()) return previous
        val binding = previous.binding
        require(binding.scope == client.scope) { "Native conversation scope changed" }
        val originalAuthority = previous.connection?.let { withTimeoutOrNull(4000) { it.sessionAuthority.await() } }
            ?: throw IOException("Original native gateway readiness is unavailable")
        val authority = withTimeoutOrNull(4000) { mine.sessionAuthority.await() }
            ?: throw IOException("Native gateway readiness is unavailable")
        if (authority != originalAuthority) throw IOException("Native gateway authority changed")
        val result = if (authority) {
            rpcOn(mine, "session.resume", json("session_id" to binding.storedSessionId, "omit_messages" to true))
        } else {
            rpcOn(mine, "session.activate", json("session_id" to binding.runtimeSessionId, "omit_messages" to true))
        }
        if (nativeSessionBinding(binding.scope, result) != binding) throw IOException("Native recovery identity changed")
        currentCoroutineContext().ensureActive()
        return synchronized(lifecycle) {
            check(connection === mine && mine.failure == null && client.scope == binding.scope) { "Native connection or conversation scope changed" }
            synchronized(cursorLock) {
                check(cursor === previous) { "Native conversation changed during recovery" }
                Cursor(binding, mine, previous.sequence, previous.epoch).also { cursor = it; sessionState = result.copy() }
            }
        }
    }
    /** Capture only a currently bound stream; never resume or move it while preparing a turn. */
    suspend fun turnLease(storedSessionId: String, expectedRuntimeSessionId: String? = null): NativeTurnLease {
        val snapshot = synchronized(cursorLock) { cursor } ?: throw NativeTurnNotSent("Native conversation is not attached")
        val mine = snapshot.connection ?: throw NativeTurnNotSent("Native conversation is not attached")
        fun bound() = snapshot.live() && snapshot.binding.scope == client.scope &&
            snapshot.binding.storedSessionId == storedSessionId &&
            (expectedRuntimeSessionId == null || snapshot.binding.runtimeSessionId == expectedRuntimeSessionId) &&
            synchronized(cursorLock) { cursor === snapshot }
        if (!bound()) throw NativeTurnNotSent("Native conversation changed before the request")
        val authority = withTimeoutOrNull(4000) { mine.sessionAuthority.await() }
            ?: throw NativeTurnNotSent("Native gateway readiness is unavailable")
        return synchronized(lifecycle) {
            if (!bound()) throw NativeTurnNotSent("Native conversation changed before the request")
            NativeTurnLease(snapshot.binding, authority, this, mine, snapshot, if (mine.canonical) mine.attachLimits else AttachmentLimits())
        }
    }

    /** Pin every operation to the connection and cursor captured before attachment or mutation. */
    suspend fun turnRpc(lease: NativeTurnLease, method: String, params: JSONObject, inline: InlineFile? = null): JSONObject {
        val mine = lease.connectionToken as? Connection ?: throw NativeTurnNotSent("Native operation has no connection")
        val snapshot = lease.cursorToken as? Cursor ?: throw NativeTurnNotSent("Native operation has no conversation")
        fun bound() {
            if (lease.gateway !== this || snapshot.connection !== mine || snapshot.binding != lease.binding ||
                !snapshot.live() || lease.binding.scope != client.scope || synchronized(cursorLock) { cursor !== snapshot })
                throw NativeTurnNotSent("Native conversation changed before the request")
        }
        if (params.opt("session_id") !is String || params.opt("session_id") != lease.binding.runtimeSessionId)
            throw NativeTurnNotSent("Native request belongs to another conversation")
        if (method == "session.steer" && params.has("execution_generation"))
            throw NativeTurnNotSent("Native turn generation must be verified")
        synchronized(lifecycle) { bound() }
        val wire = params.copy()
        if (method == "session.steer" && lease.authority) {
            val result = try {
                rpcOn(mine, "session.resume", json("session_id" to lease.binding.storedSessionId, "omit_messages" to true), sendGuard = ::bound)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw NativeTurnNotSent("Native turn generation is unavailable", e) }
            try {
                require(result.opt("session_id") is String && result.opt("stored_session_id") is String)
                require(nativeSessionBinding(lease.binding.scope, result) == lease.binding)
                result.optJSONObject("info")?.let { info ->
                    if (info.has("profile_name") && !info.isNull("profile_name"))
                        require(info.opt("profile_name") is String && info.opt("profile_name") == lease.binding.scope.profile)
                }
                require(result.opt("running") == true)
                val generation = when (val value = result.opt("execution_generation")) {
                    is Int -> value.toLong()
                    is Long -> value
                    else -> throw IllegalArgumentException("Invalid native turn generation")
                }
                require(generation >= 0)
                wire.put("execution_generation", generation)
            } catch (e: IllegalArgumentException) { throw NativeTurnNotSent("Native turn generation is unavailable", e) }
        }
        if (inline != null) return attachFile(mine, lease.binding, method, wire, inline, ::bound)
        if (TEXT_FILE_FIELDS[method]?.let(wire::has) != true) return rpcOn(mine, method, wire, sendGuard = ::bound)
        // A file in one text frame on the chat's own socket (a plugin without file sockets, or a gateway without the plugin): when its answer is lost
        // (the socket closed under it, as Hermes' dashboard closes one whose pong waits too long behind the file), the file is not sent and a queued
        // message with it waits for the person, so a link that cannot carry it never takes the chat's socket down again and again (CHAT-121 follow-up 9).
        return try { rpcOn(mine, method, wire, sendGuard = ::bound) }
        catch (e: IOException) { if (e !is ApiFailure && e !is NativeTurnNotSent && e.message == NATIVE_OUTCOME_UNKNOWN) throw AttachmentStalled(e) else throw e }
    }

    /** Whether this connection's chats have session authority, and what one file frame may carry on it (the picker's limit). */
    suspend fun attachmentLimits(): Pair<Boolean, AttachmentLimits> {
        val mine = connect()
        val authority = withTimeoutOrNull(4000) { mine.sessionAuthority.await() } ?: throw IOException("Native gateway readiness is unavailable")
        return authority to if (mine.canonical) mine.attachLimits else AttachmentLimits()
    }

    private suspend fun reconciliationHistory(mine: Connection, snapshot: Cursor): JSONObject {
        val binding = snapshot.binding
        require(binding.scope == client.scope) { "Conversation scope changed" }
        // session.history has no compact-image option; reuse the scoped, bounded REST projection.
        val history = NativeOperations(client).passiveHistory(binding.storedSessionId)
        if (mine.sessionAuthority.await() == true) {
            val fresh = rpcOn(mine, "session.resume", json("session_id" to binding.storedSessionId, "omit_messages" to false), sendGuard = {
                if (binding.scope != client.scope || synchronized(cursorLock) { cursor !== snapshot }) throw NativeTurnNotSent("Conversation scope changed")
            })
            require(nativeSessionBinding(binding.scope, fresh) == binding) { "Conversation identity changed during reconciliation" }
            val profile = fresh.optJSONObject("info")?.opt("profile_name")
            require(profile == null || profile == JSONObject.NULL || profile == binding.scope.profile) { "Conversation scope changed" }
            val rows = fresh.optJSONArray("messages") ?: throw IOException("Native reconciliation history is unavailable")
            require(!fresh.optBoolean("messages_omitted") && fresh.opt("message_count") is Number &&
                fresh.optLong("message_count") == rows.length().toLong() && rows.objects().size == rows.length()) { "Native reconciliation history is incomplete" }
            fun rowId(row: JSONObject) = row.string("row_id", row.string("_row_id", row.string("id")))
            val saved = history.rows("messages").filter { rowId(it).isNotBlank() }.associateBy(::rowId)
            val limit = history.optInt("history_limit", 500)
            // The sequence belongs to this native snapshot; older REST rows must not hide a final committed during the read.
            history.put("messages", JSONArray(rows.objects().takeLast(limit).map { row ->
                (saved[rowId(row)]?.copy() ?: JSONObject()).apply { row.keys().forEach { put(it, row.opt(it)) } }
            })).put("message_count", rows.length()).put("history_truncated", rows.length() > limit)
            history.remove("_history_metadata_stamp")
            for (key in listOf("running", "pending", "prompts", "execution_generation", "replay_epoch", "last_sequence")) if (fresh.has(key)) history.put(key, fresh.opt(key))
            history.put("active_stream_id", if (fresh.opt("running") == true) binding.runtimeSessionId else JSONObject.NULL)
        }
        require(binding.scope == client.scope) { "Conversation scope changed" }
        return history
    }
    private suspend fun replayParams(mine: Connection, snapshot: Cursor, seen: Long): JSONObject =
        json("session_id" to snapshot.binding.runtimeSessionId, "last_seen" to seen).apply {
            if (mine.sessionAuthority.await() == true && snapshot.epoch.isNotBlank()) put("replay_epoch", snapshot.epoch)
        }
    suspend fun prepareReplay(lastId: String = "", beforePrompt: Boolean = false) = sessionLock.withLock {
        if (synchronized(cursorLock) { cursor } == null) return@withLock
        check(!consuming.get()) { "Prepare replay before starting the native event collector" }
        val mine = connect()
        val snapshot = recoverCursorLocked(mine) ?: return@withLock
        val b = snapshot.binding
        val saved = lastId.takeIf { it.startsWith("${b.runtimeSessionId}:") }?.removePrefix("${b.runtimeSessionId}:")
        val savedEpoch = saved?.substringBeforeLast(':', "").orEmpty()
        if (!beforePrompt && savedEpoch.isNotBlank()) synchronized(cursorLock) { if (cursor === snapshot) snapshot.epoch = savedEpoch }
        val replay = rpcOn(mine, "session.events.since", replayParams(mine, snapshot, 0))
        synchronized(cursorLock) {
            if (cursor === snapshot) {
                if (beforePrompt || savedEpoch.isBlank()) snapshot.epoch = replay.string("epoch")
                snapshot.sequence = when { beforePrompt -> replay.optLong("latest_seq", 0); saved != null -> saved.substringAfterLast(':').toLongOrNull() ?: 0; replay.optBoolean("truncated") -> 0; else -> replay.rows("events").lastOrNull { it.string("type") == "message.start" }?.optLong("seq")?.minus(1) ?: replay.optLong("latest_seq", 0) }
            }
        }
    }
    suspend fun events(onEvent: suspend (JSONObject) -> Unit) {
        check(consuming.compareAndSet(false, true)) { "Only one native event collector is allowed per client" }
        var mine: Connection? = null
        try {
            mine = connect()
            val snapshot = sessionLock.withLock { recoverCursorLocked(mine) }
            if (snapshot != null) {
                val b = snapshot.binding
                val seen = synchronized(cursorLock) { snapshot.sequence }
                val replay = rpcOn(mine, "session.events.since", replayParams(mine, snapshot, seen))
                mine.failure?.let { throw it }
                var nextEpoch = replay.string("epoch")
                var latestSequence = replay.optLong("latest_seq", 0)
                val reconcile = synchronized(cursorLock) { cursor === snapshot && (replay.optBoolean("truncated") || snapshot.epoch.isNotBlank() && nextEpoch != snapshot.epoch) }
                if (reconcile) {
                    val history = reconciliationHistory(mine, snapshot)
                    nextEpoch = history.string("replay_epoch", nextEpoch)
                    latestSequence = history.optLong("last_sequence", latestSequence)
                    if (synchronized(cursorLock) { cursor === snapshot }) onEvent(json("method" to "reconciliation.required", "params" to json("session_id" to b.runtimeSessionId, "history" to history, "epoch" to nextEpoch,
                        "replay_reset" to (snapshot.epoch.isNotBlank() && snapshot.epoch != nextEpoch))))
                }
                synchronized(cursorLock) { if (cursor === snapshot) { if (reconcile) snapshot.sequence = latestSequence; snapshot.epoch = nextEpoch } }
                if (synchronized(cursorLock) { cursor === snapshot } && replay.string("epoch") == nextEpoch) {
                    val events = replay.rows("events")
                    events.forEach { retireCancelledServerRequest(mine, it) }
                    // Hermes replays open requests as {id, method, params}, without the JSON-RPC envelope field.
                    replay.rows("open_requests").forEach {
                        it.put("jsonrpc", "2.0")
                        if (it.optString("method") in SECURE_SERVER_REQUESTS) it.put("_secure_received_ms", System.nanoTime() / 1_000_000)
                        deliver(mine, it, onEvent)
                    }
                    events.forEach { deliver(mine, json("method" to "event", "params" to it), onEvent) }
                }
            }
            for (frame in mine.frames) { mine.failure?.let { throw it }; deliver(mine, frame, onEvent) }
        } finally {
            consuming.set(false)
            mine?.let { current -> synchronized(current.requestLock) {
                // The screen stopped listening (app left, chat closed). Hermes still waits for its own timeout, so the question is released,
                // not retired: a replay when the app returns brings it back, and until then it cannot be answered.
                current.serverRequests.values.filter { it.method in SECURE_SERVER_REQUESTS }.toList().forEach { request ->
                    if (current.serverRequests[request.idKey] === request) current.serverRequests.remove(request.idKey)
                    current.secureIds.remove(request.idKey)
                }
            } }
            if (mine?.failure != null) mine.frames.cancel()
        }
    }
    private fun gatewayAnswered(mine: Connection) {
        if (mine.ready.isCompleted && connection === mine && mine.failure == null && connectionIdentityCurrent(mine))
            HermesReach.answered(mine.identity.scope.origin)
    }

    private suspend fun deliver(mine: Connection, frame: JSONObject, callback: suspend (JSONObject) -> Unit) {
        if (isServerRequest(frame)) {
            val secure = frame.optString("method") in SECURE_SERVER_REQUESTS
            if (secure && !securePromptsAvailable(mine)) {
                val parts = requestId(frame)
                val session = frame.optJSONObject("params")?.string("session_id").orEmpty()
                if (parts != null && session.isNotBlank()) synchronized(mine.requestLock) {
                    rememberRetiredRequestLocked(mine, NativeRequestIdentity(parts.key, frame.optString("method"), session))
                }
                serverRequestError(mine, frame.opt("id") ?: JSONObject.NULL, -32601, "Secure input unavailable")
                return
            }
            val request = sessionLock.withLock { prepareServerRequest(mine, frame) } ?: return
            val shouldDeliver = synchronized(mine.requestLock) {
                if (mine.serverRequests[request.idKey] !== request || request.delivered || connection !== mine || mine.failure != null ||
                    secure && System.nanoTime() / 1_000_000 >= request.expiresAt) false
                else { request.delivered = true; true }
            }
            if (shouldDeliver) gatewayAnswered(mine)
            if (shouldDeliver) callback(if (secure) json("jsonrpc" to "2.0", "id" to request.id, "method" to request.method,
                "params" to request.params.copy().put("secure_method", request.method).put("secure_connection", mine.secureConnection).put("secure_expires_ms", request.expiresAt)) else frame)
            return
        }
        retireCancelledServerRequest(mine, frame)
        val p = frame.optJSONObject("params") ?: frame
        val snapshot = synchronized(cursorLock) { cursor }
        val b = snapshot?.binding
        if (b != null && p.string("session_id").isNotBlank() && p.string("session_id") != b.runtimeSessionId) return
        val epoch = p.string("replay_epoch")
        if (snapshot != null && epoch.isNotBlank() && snapshot.epoch.isNotBlank() && epoch != snapshot.epoch) {
            val history = reconciliationHistory(mine, snapshot)
            if (synchronized(cursorLock) { cursor !== snapshot }) return
            val currentEpoch = history.string("replay_epoch", epoch)
            callback(json("method" to "reconciliation.required", "params" to json("session_id" to b?.runtimeSessionId,
                "history" to history, "epoch" to currentEpoch, "replay_reset" to true)))
            synchronized(cursorLock) { if (cursor === snapshot) { snapshot.epoch = currentEpoch; snapshot.sequence = history.optLong("last_sequence") } }
            if (epoch != currentEpoch) return
        }
        val seq = p.optLong("seq", frame.optLong("seq", 0))
        val seen = synchronized(cursorLock) { snapshot?.sequence ?: 0 }
        if (b != null && seq > 0 && seq <= seen) return
        if (b != null && seq > 0 && seen > 0 && seq != seen + 1) {
            val history = reconciliationHistory(mine, snapshot)
            if (synchronized(cursorLock) { cursor !== snapshot }) return
            val currentEpoch = history.string("replay_epoch", snapshot.epoch)
            val reset = currentEpoch != snapshot.epoch
            callback(json("method" to "reconciliation.required", "params" to json("session_id" to b.runtimeSessionId, "gap" to true,
                "history" to history, "epoch" to currentEpoch, "replay_reset" to reset)))
            synchronized(cursorLock) {
                if (cursor === snapshot) {
                    snapshot.sequence = if (reset) history.optLong("last_sequence") else maxOf(snapshot.sequence, history.optLong("last_sequence", snapshot.sequence))
                    snapshot.epoch = currentEpoch
                }
            }
            if (reset || seq <= synchronized(cursorLock) { snapshot.sequence }) return
        }
        if (synchronized(cursorLock) { cursor !== snapshot }) return
        callback(frame)
        synchronized(cursorLock) { if (snapshot != null && cursor === snapshot && seq > 0) snapshot.sequence = seq }
    }
    fun close() { val mine = synchronized(lifecycle) { generation.incrementAndGet(); connection.also { connection = null } } ?: return; terminate(mine, IOException("Native client connection closed")); mine.socket?.cancel() }
}
