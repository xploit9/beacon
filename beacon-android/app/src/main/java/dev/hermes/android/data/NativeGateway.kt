package dev.hermes.android.data

import dev.hermes.android.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Hermes `source` for a named Beacon thread (Relay tags its threads `phone`); every other phone chat is `android`. */
internal const val BEACON_THREAD_SOURCE = "android_thread"
internal val THREAD_SOURCES = listOf(BEACON_THREAD_SOURCE, "phone")
private val WINDOW_SERVER_REQUESTS = setOf("preview.act", "preview.read", "terminal.read", "window.read", "tour")

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
    var delivered: Boolean = false,
) {
    val identity get() = NativeRequestIdentity(idKey, method, sessionId)
}

data class NativeSessionBinding(val scope: Scope, val storedSessionId: String, val runtimeSessionId: String)

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
    require(value.length in 1..1024 && value.none { it <= ' ' || it == '\u007f' || it == ',' }) { "Native server did not return a usable connection ticket" }
    return value
}

/** A request whose frame may have reached Hermes; [requestNeverSent] reads this to keep such sends uncertain. */
internal const val NATIVE_OUTCOME_UNKNOWN = "Native request outcome may be unknown; reconcile history before resending"

internal class NativeGateway(private val client: BeaconClient, private val socketFactory: WebSocket.Factory = client.http, private val rpcTimeoutMs: Long = 60000) {
    private class Connection {
        val frames = Channel<JSONObject>(256)
        val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
        val requestLock = Any()
        val serverRequests = linkedMapOf<String, NativeServerRequest>()
        val retiredServerRequests = linkedSetOf<NativeRequestIdentity>()
        val ready = CompletableDeferred<Unit>()
        @Volatile var socket: WebSocket? = null
        @Volatile var failure: IOException? = null
    }
    /** A binding names a runtime that lives on the socket that made it: once that socket is gone Hermes may reap the runtime. */
    private class Cursor(val binding: NativeSessionBinding, val connection: Connection?, var sequence: Long = 0, var epoch: String = "")
    private companion object {
        // Weak, so a gateway leaves with its client; answers are only routed between gateways of one account and profile.
        val gateways: MutableSet<NativeGateway> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    }
    private val connectLock = Mutex()
    private val sessionLock = Mutex()
    private val lifecycle = Any()
    private val cursorLock = Any()
    private val consuming = AtomicBoolean()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var connection: Connection? = null
    private var cursor: Cursor? = null
    init { synchronized(gateways) { gateways += this } }
    private suspend fun connect(): Connection = connectLock.withLock {
        connection?.takeIf { it.failure == null && it.ready.isCompleted }?.let { return@withLock it }
        require(client.auth.backend == "hermes-dashboard" && client.auth.owner.isNotBlank() && client.auth.profile.isNotBlank()) { "Verify native dashboard identity before connecting" }
        val attempt = generation.get()
        val ticket = nativeConnectionTicket(client.nativeRequest("/api/auth/ws-ticket", "POST", JSONObject()).getString("ticket"))
        val mine = Connection()
        synchronized(lifecycle) { check(attempt == generation.get()) { "Native client closed during ticket admission" }; connection = mine }
        try {
            val request = client.request("/api/ws").header("Sec-WebSocket-Protocol", "hermes-gateway-v1, hermes-gateway-ticket.$ticket").build()
            val socket = socketFactory.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    mine.socket = ws
                    if (connection !== mine || mine.failure != null) { ws.cancel(); return }
                    // No acknowledgement is required; a legacy gateway's method-not-found response is dropped below.
                    if (ws.send(json("jsonrpc" to "2.0", "id" to "capabilities-${UUID.randomUUID()}", "method" to "client.capabilities",
                            "params" to json("server_requests" to true)).toString()) != true) {
                        terminate(mine, IOException("Native connection could not advertise server-request support"))
                        ws.cancel()
                        return
                    }
                    mine.ready.complete(Unit)
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    if (connection !== mine || mine.failure != null) return
                    if (text.length > 8 * 1024 * 1024) { terminate(mine, IOException("Native frame exceeds size limit")); ws.cancel(); return }
                    val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                    val id = frame.optString("id")
                    val method = frame.optString("method")
                    if (!frame.has("method") && id.isNotBlank()) mine.pending.remove(id)?.complete(frame)
                    else if (method == "approval" || method == "clarify") {
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
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { terminate(mine, IOException("Native connection interrupted", t)) }
                override fun onClosing(ws: WebSocket, code: Int, reason: String) { terminate(mine, IOException("Native connection closed ($code)")); ws.close(code, reason) }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) { terminate(mine, IOException("Native connection closed ($code)")) }
            })
            mine.socket = socket
            if (connection !== mine) socket.cancel()
            try { withTimeout(15000) { mine.ready.await() } } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); throw IOException("Native connection admission timed out", e) }
            mine.failure?.let { throw it }
            mine
        } catch (e: Throwable) { terminate(mine, IOException("Native connection admission ended", e)); mine.socket?.cancel(); throw e }
    }
    private fun terminate(mine: Connection, error: IOException) {
        synchronized(lifecycle) { if (connection === mine) connection = null; if (mine.failure == null) mine.failure = error }
        mine.ready.completeExceptionally(error)
        mine.pending.values.forEach { it.completeExceptionally(unknown(error)) }; mine.pending.clear()
        // Channel closure cannot be dropped when the queue is full and wakes an idle collector.
        mine.frames.close(error)
    }
    private fun unknown(cause: Throwable) = IOException(NATIVE_OUTCOME_UNKNOWN, cause)
    suspend fun rpc(method: String, params: JSONObject): JSONObject {
        val mine = connect()
        val id = UUID.randomUUID().toString(); val result = CompletableDeferred<JSONObject>()
        mine.pending[id] = result
        try {
            mine.failure?.let { throw unknown(it) }
            val wireParams = params.copy().put("profile", client.auth.profile)
            // Keep required history, without retransmitting legacy inline photos beyond the frame cap.
            if (method == "session.resume") wireParams.put("source", "android").put("inline_images", false)
            if (mine.socket?.send(json("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to wireParams).toString()) != true) throw unknown(IOException("Native connection unavailable"))
            val response = try { withTimeout(rpcTimeoutMs) { result.await() } } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); throw unknown(e) }
            response.optJSONObject("error")?.let {
                // 4001: Hermes no longer holds this runtime (reaped or evicted). Nothing may keep addressing it; the next use resumes the stored conversation.
                if (it.optInt("code", 500) == 4001 && it.optString("message") == "session not found") forget(params.string("session_id"))
                throw ApiFailure(it.optInt("code", 500), it.optString("message", "Native operation failed"))
            }
            return response.optJSONObject("result") ?: JSONObject().put("value", response.opt("result"))
        } finally { mine.pending.remove(id) }
    }

    /** The live connection of this gateway that received [requestId] for its bound conversation, if any. */
    private fun openServerRequest(method: String, requestId: String, sessionId: String): Triple<NativeGateway, Connection, NativeServerRequest>? {
        val mine = connection?.takeIf { it.failure == null && it.ready.isCompleted } ?: return null
        val binding = synchronized(cursorLock) { cursor?.binding }
        if (binding == null || binding.scope != client.scope || binding.runtimeSessionId != sessionId) return null
        val open = synchronized(mine.requestLock) {
            mine.serverRequests.values.filter { request ->
                request.method == method && request.sessionId == sessionId &&
                    (if (method == "approval") request.params.string("request_id") == requestId else request.idText == requestId)
            }.singleOrNull()
        } ?: return null
        return Triple(this, mine, open)
    }

    // Adapted from Hermes Relay's GatewayChatClient request lifecycle; Beacon keeps its existing native session cursor.
    internal fun answerServerRequest(method: String, requestId: String, sessionId: String, payload: JSONObject): JSONObject {
        // A chat action answers from its own detached client, but the request stays owned by the stream connection that received it.
        val peers = synchronized(gateways) { gateways.filter { it !== this && it.client.scope == client.scope } }
        val (owner, mine, open) = (listOf(this) + peers).firstNotNullOfOrNull { it.openServerRequest(method, requestId, sessionId) }
            ?: throw IOException("Native server request is no longer active")
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
            else -> throw IOException("Not available on the phone")
        }
        synchronized(mine.requestLock) {
            if (mine.serverRequests[open.idKey] !== open || owner.connection !== mine || mine.failure != null || open.socket !== mine.socket) {
                throw IOException("Native server request is no longer active")
            }
            val response = JSONObject().put("jsonrpc", "2.0").put("id", open.id).put("result", result)
            if (open.socket.send(response.toString()) != true) throw IOException("Native server request response could not be sent")
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
        if (method != "approval" && method != "clarify") {
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
        var failure: Pair<Int, String>? = null
        val open = synchronized(mine.requestLock) {
            if (identity in mine.retiredServerRequests) return null
            val previous = mine.serverRequests[idParts.key]
            when {
                previous != null && (previous.method != method || previous.sessionId != sessionId) -> {
                    failure = -32602 to "Request identity changed"
                    null
                }
                // A replayed request keeps its identity and takes the latest parameters.
                previous != null -> previous.also { it.params = params.copy() }
                mine.serverRequests.size >= 64 -> {
                    failure = -32000 to "Too many open requests"
                    null
                }
                else -> NativeServerRequest(id, idParts.text, idParts.key, method, sessionId, params.copy(), mine.socket!!)
                    .also { mine.serverRequests[idParts.key] = it }
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
    // shortcut: the binding takes the connection current at bind time, so a socket that died and was replaced between the reply and here looks live; pass the reply's own connection from rpc if that window ever shows up.
    private fun bind(result: JSONObject): NativeSessionBinding = nativeSessionBinding(client.scope, result).also { synchronized(cursorLock) { sessionState = result.copy(); cursor = Cursor(it, connection) } }
    suspend fun create(params: JSONObject): NativeSessionBinding = sessionLock.withLock { bind(rpc("session.create", params.copy().put("source", if (params.string("source") == BEACON_THREAD_SOURCE) BEACON_THREAD_SOURCE else "android").put("close_on_disconnect", false))) }
    /** [lazy]: reattach a live unsent shell or answer 4007, never let Hermes materialize an ownerless row for a runtime that is gone. */
    suspend fun session(stored: String?, lazy: Boolean = false): NativeSessionBinding = sessionLock.withLock {
        synchronized(cursorLock) { cursor }?.takeIf { it.live() && it.binding.scope == client.scope && (stored == it.binding.storedSessionId || stored == it.binding.runtimeSessionId) }?.let { return@withLock it.binding }
        bind(rpc(if (stored.isNullOrBlank()) "session.create" else "session.resume", if (stored.isNullOrBlank()) json("source" to "android", "close_on_disconnect" to false) else json("session_id" to stored, "close_on_disconnect" to false, "omit_messages" to false).apply { if (lazy && !stored.isNullOrBlank()) put("lazy", true) }))
    }
    private suspend fun reconciliationHistory(binding: NativeSessionBinding): JSONObject {
        require(binding.scope == client.scope) { "Conversation scope changed" }
        // session.history has no compact-image option; reuse the scoped, bounded REST projection.
        return NativeOperations(client).passiveHistory(binding.storedSessionId).also {
            require(binding.scope == client.scope) { "Conversation scope changed" }
        }
    }
    suspend fun prepareReplay(lastId: String = "", beforePrompt: Boolean = false) = sessionLock.withLock {
        val snapshot = synchronized(cursorLock) { cursor } ?: return@withLock
        check(!consuming.get()) { "Prepare replay before starting the native event collector" }
        val b = snapshot.binding
        val replay = rpc("session.events.since", json("session_id" to b.runtimeSessionId, "last_seen" to 0))
        synchronized(cursorLock) {
            if (cursor === snapshot) {
                snapshot.epoch = replay.string("epoch")
                snapshot.sequence = when { beforePrompt -> replay.optLong("latest_seq", 0); lastId.substringBeforeLast(':', "") == b.runtimeSessionId -> lastId.substringAfterLast(':').toLongOrNull() ?: 0; else -> replay.rows("events").lastOrNull { it.string("type") == "message.start" }?.optLong("seq")?.minus(1) ?: replay.optLong("latest_seq", 0) }
            }
        }
    }
    suspend fun events(onEvent: suspend (JSONObject) -> Unit) {
        check(consuming.compareAndSet(false, true)) { "Only one native event collector is allowed per client" }
        var mine: Connection? = null
        try {
            mine = connect()
            val snapshot = synchronized(cursorLock) { cursor }
            if (snapshot != null) {
                val b = snapshot.binding
                val seen = synchronized(cursorLock) { snapshot.sequence }
                val replay = rpc("session.events.since", json("session_id" to b.runtimeSessionId, "last_seen" to seen))
                mine.failure?.let { throw it }
                val nextEpoch = replay.string("epoch")
                val reconcile = synchronized(cursorLock) { cursor === snapshot && (replay.optBoolean("truncated") || snapshot.epoch.isNotBlank() && nextEpoch != snapshot.epoch) }
                if (reconcile) {
                    val history = reconciliationHistory(b)
                    if (synchronized(cursorLock) { cursor === snapshot }) onEvent(json("method" to "reconciliation.required", "params" to json("session_id" to b.runtimeSessionId, "history" to history, "epoch" to nextEpoch)))
                }
                synchronized(cursorLock) { if (cursor === snapshot) { if (reconcile) snapshot.sequence = replay.optLong("latest_seq", 0); snapshot.epoch = nextEpoch } }
                if (synchronized(cursorLock) { cursor === snapshot }) {
                    val events = replay.rows("events")
                    events.forEach { retireCancelledServerRequest(mine, it) }
                    // Hermes replays open requests as {id, method, params}, without the JSON-RPC envelope field.
                    replay.rows("open_requests").forEach { deliver(mine, it.put("jsonrpc", "2.0"), onEvent) }
                    events.forEach { deliver(mine, json("method" to "event", "params" to it), onEvent) }
                }
            }
            for (frame in mine.frames) { mine.failure?.let { throw it }; deliver(mine, frame, onEvent) }
        } finally { consuming.set(false); if (mine?.failure != null) mine.frames.cancel() }
    }
    private suspend fun deliver(mine: Connection, frame: JSONObject, callback: suspend (JSONObject) -> Unit) {
        if (isServerRequest(frame)) {
            val request = sessionLock.withLock { prepareServerRequest(mine, frame) } ?: return
            val shouldDeliver = synchronized(mine.requestLock) {
                if (mine.serverRequests[request.idKey] !== request || request.delivered) false
                else { request.delivered = true; true }
            }
            if (shouldDeliver) callback(frame)
            return
        }
        retireCancelledServerRequest(mine, frame)
        val p = frame.optJSONObject("params") ?: frame
        val snapshot = synchronized(cursorLock) { cursor }
        val b = snapshot?.binding
        if (b != null && p.string("session_id").isNotBlank() && p.string("session_id") != b.runtimeSessionId) return
        val seq = p.optLong("seq", frame.optLong("seq", 0))
        val seen = synchronized(cursorLock) { snapshot?.sequence ?: 0 }
        if (b != null && seq > 0 && seq <= seen) return
        if (b != null && seq > 0 && seen > 0 && seq != seen + 1) {
            val history = reconciliationHistory(b)
            if (synchronized(cursorLock) { cursor !== snapshot }) return
            callback(json("method" to "reconciliation.required", "params" to json("session_id" to b.runtimeSessionId, "gap" to true, "history" to history)))
        }
        if (synchronized(cursorLock) { cursor !== snapshot }) return
        callback(frame)
        synchronized(cursorLock) { if (snapshot != null && cursor === snapshot && seq > 0) snapshot.sequence = seq }
    }
    fun close() { generation.incrementAndGet(); val mine = synchronized(lifecycle) { connection.also { connection = null } } ?: return; terminate(mine, IOException("Native client connection closed")); mine.socket?.cancel() }
}
