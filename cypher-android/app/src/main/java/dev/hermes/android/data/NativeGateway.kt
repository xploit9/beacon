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

internal class NativeGateway(private val client: CypherClient, private val socketFactory: WebSocket.Factory = client.http, private val rpcTimeoutMs: Long = 60000) {
    private class Connection {
        val frames = Channel<JSONObject>(256)
        val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
        val ready = CompletableDeferred<Unit>()
        @Volatile var socket: WebSocket? = null
        @Volatile var failure: IOException? = null
    }
    private class Cursor(val binding: NativeSessionBinding, var sequence: Long = 0, var epoch: String = "")
    private val connectLock = Mutex()
    private val sessionLock = Mutex()
    private val lifecycle = Any()
    private val cursorLock = Any()
    private val consuming = AtomicBoolean()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var connection: Connection? = null
    private var cursor: Cursor? = null
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
                    mine.ready.complete(Unit)
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    if (connection !== mine || mine.failure != null) return
                    if (text.length > 8 * 1024 * 1024) { terminate(mine, IOException("Native frame exceeds size limit")); ws.cancel(); return }
                    val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                    val id = frame.optString("id")
                    if (!frame.has("method") && id.isNotBlank()) mine.pending.remove(id)?.complete(frame)
                    else if (!mine.frames.trySend(frame).isSuccess) { terminate(mine, IOException("Native event queue overflow; reconcile history")); ws.cancel() }
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
    private fun unknown(cause: Throwable) = IOException("Native request outcome may be unknown; reconcile history before resending", cause)
    suspend fun rpc(method: String, params: JSONObject): JSONObject {
        val mine = connect()
        val id = UUID.randomUUID().toString(); val result = CompletableDeferred<JSONObject>()
        mine.pending[id] = result
        try {
            mine.failure?.let { throw unknown(it) }
            val wireParams = params.copy().put("profile", client.auth.profile)
            if (method == "session.resume") wireParams.put("source", "android")
            if (mine.socket?.send(json("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to wireParams).toString()) != true) throw unknown(IOException("Native connection unavailable"))
            val response = try { withTimeout(rpcTimeoutMs) { result.await() } } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); throw unknown(e) }
            response.optJSONObject("error")?.let { throw ApiFailure(it.optInt("code", 500), it.optString("message", "Native operation failed")) }
            return response.optJSONObject("result") ?: JSONObject().put("value", response.opt("result"))
        } finally { mine.pending.remove(id) }
    }
    private var sessionState = JSONObject()
    internal fun sessionSnapshot(): JSONObject = synchronized(cursorLock) { sessionState.copy() }
    private fun bind(result: JSONObject): NativeSessionBinding = nativeSessionBinding(client.scope, result).also { synchronized(cursorLock) { sessionState = result.copy(); cursor = Cursor(it) } }
    suspend fun create(params: JSONObject): NativeSessionBinding = sessionLock.withLock { bind(rpc("session.create", params.copy().put("source", "android").put("close_on_disconnect", false))) }
    /** [lazy]: reattach a live unsent shell or answer 4007, never let Hermes materialize an ownerless row for a runtime that is gone. */
    suspend fun session(stored: String?, lazy: Boolean = false): NativeSessionBinding = sessionLock.withLock {
        synchronized(cursorLock) { cursor?.binding }?.takeIf { connection?.failure == null && connection?.ready?.isCompleted == true && it.scope == client.scope && (stored == it.storedSessionId || stored == it.runtimeSessionId) }?.let { return@withLock it }
        bind(rpc(if (stored.isNullOrBlank()) "session.create" else "session.resume", if (stored.isNullOrBlank()) json("source" to "android", "close_on_disconnect" to false) else json("session_id" to stored, "close_on_disconnect" to false, "omit_messages" to false).apply { if (lazy && !stored.isNullOrBlank()) put("lazy", true) }))
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
                    val history = rpc("session.history", json("session_id" to b.runtimeSessionId))
                    if (synchronized(cursorLock) { cursor === snapshot }) onEvent(json("method" to "reconciliation.required", "params" to json("session_id" to b.runtimeSessionId, "history" to history, "epoch" to nextEpoch)))
                }
                synchronized(cursorLock) { if (cursor === snapshot) { if (reconcile) snapshot.sequence = replay.optLong("latest_seq", 0); snapshot.epoch = nextEpoch } }
                if (synchronized(cursorLock) { cursor === snapshot }) {
                    replay.rows("open_requests").forEach { deliver(it, onEvent) }
                    replay.rows("events").forEach { deliver(json("method" to "event", "params" to it), onEvent) }
                }
            }
            for (frame in mine.frames) { mine.failure?.let { throw it }; deliver(frame, onEvent) }
        } finally { consuming.set(false); if (mine?.failure != null) mine.frames.cancel() }
    }
    private suspend fun deliver(frame: JSONObject, callback: suspend (JSONObject) -> Unit) {
        val p = frame.optJSONObject("params") ?: frame
        val snapshot = synchronized(cursorLock) { cursor }
        val b = snapshot?.binding
        if (b != null && p.string("session_id").isNotBlank() && p.string("session_id") != b.runtimeSessionId) return
        val seq = p.optLong("seq", frame.optLong("seq", 0))
        val seen = synchronized(cursorLock) { snapshot?.sequence ?: 0 }
        if (b != null && seq > 0 && seq <= seen) return
        if (b != null && seq > 0 && seen > 0 && seq != seen + 1) {
            val history = rpc("session.history", json("session_id" to b.runtimeSessionId))
            if (synchronized(cursorLock) { cursor !== snapshot }) return
            callback(json("method" to "reconciliation.required", "params" to json("session_id" to b.runtimeSessionId, "gap" to true, "history" to history)))
        }
        if (synchronized(cursorLock) { cursor !== snapshot }) return
        callback(frame)
        synchronized(cursorLock) { if (snapshot != null && cursor === snapshot && seq > 0) snapshot.sequence = seq }
    }
    fun close() { generation.incrementAndGet(); val mine = synchronized(lifecycle) { connection.also { connection = null } } ?: return; terminate(mine, IOException("Native client connection closed")); mine.socket?.cancel() }
}
