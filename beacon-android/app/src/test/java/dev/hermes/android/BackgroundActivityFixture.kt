package dev.hermes.android

import dev.hermes.android.core.copy
import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import kotlinx.coroutines.CompletableDeferred
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Only synthetic transport responses; unexpected reads fail instead of reaching a real service. */
internal class BackgroundActivityFixture : WebSocket.Factory, AutoCloseable {
    val requests = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    val httpPaths = CopyOnWriteArrayList<String>()
    var inventory = json("sessions" to JSONArray()
        .put(json("id" to "runtime-a", "session_key" to "stored-a"))
        .put(json("id" to "runtime-b", "session_key" to "stored-b")))
    val rosters = mutableMapOf(
        "runtime-a" to json("subagents" to JSONArray().put(json("subagent_id" to "worker-a", "goal" to "Check synthetic notes", "status" to "running", "started_at" to 100))),
        "runtime-b" to json("subagents" to JSONArray()),
    )
    val processes = mutableMapOf(
        "runtime-a" to json("processes" to JSONArray()),
        "runtime-b" to json("processes" to JSONArray().put(json("session_id" to "process-b", "command" to "synthetic build", "status" to "running", "started_epoch" to 100))),
    )
    val profileWatches = JSONArray().put(json("job_id" to "profile-watch", "cadence" to "watch", "active" to true))
    val profileSchedules = JSONArray().put(json("id" to "profile-cron", "status" to "running"))
    var failMethod = ""
    var holdMethod = ""
    var beforeReply: ((String) -> Unit)? = null
    val held = CompletableDeferred<Unit>()
    val client = BeaconClient(AuthContext("https://activity-fixture.invalid", emptyList(), "synthetic-owner", "default", backend = "hermes-dashboard"), null, this) { builder ->
        builder.addInterceptor { chain ->
            val request = chain.request()
            httpPaths += request.url.encodedPath
            val path = request.url.encodedPath
            val missing = path == "/api/plugins/beacon/mobile/gateway-ticket"
            check(missing || path == "/api/auth/ws-ticket") { "Unexpected HTTP activity read" }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (missing) 404 else 200).message("Synthetic")
                .body((if (missing) json("detail" to "Not Found") else json("ticket" to "synthetic-ticket")).toString().toResponseBody("application/json".toMediaType())).build()
        }
    }

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        val socket = object : WebSocket {
            override fun request() = request
            override fun queueSize() = 0L
            override fun cancel() = Unit
            override fun close(code: Int, reason: String?) = true
            override fun send(bytes: ByteString) = false
            override fun send(text: String): Boolean {
                val frame = JSONObject(text)
                val method = frame.getString("method")
                val params = frame.getJSONObject("params")
                requests += method to params.copy()
                if (method == holdMethod) { held.complete(Unit); return true }
                val response = json("jsonrpc" to "2.0", "id" to frame.getString("id"))
                if (method == failMethod) response.put("error", json("code" to 5030, "message" to "unsupported_projection"))
                else response.put("result", when (method) {
                    "client.capabilities" -> JSONObject()
                    "session.active_list" -> inventory
                    "subagent.list" -> checkNotNull(rosters[params.getString("session_id")])
                    "process.list" -> checkNotNull(processes[params.getString("session_id")])
                    else -> error("Unexpected RPC activity read: $method")
                })
                beforeReply?.invoke(method)
                listener.onMessage(this, response.toString())
                return true
            }
        }
        listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Synthetic").build())
        listener.onMessage(socket, json("jsonrpc" to "2.0", "method" to "event", "params" to json("type" to "gateway.ready", "payload" to json("session_authority" to true))).toString())
        return socket
    }

    override fun close() { client.closeNative() }
}
