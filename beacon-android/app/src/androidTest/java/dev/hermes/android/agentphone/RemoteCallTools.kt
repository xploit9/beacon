package dev.hermes.android.agentphone

import dev.hermes.android.core.json
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.NativeToolGateway
import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.data.defaultVoiceName
import dev.hermes.android.voice.phoneKeyCallBody
import dev.hermes.android.voice.voiceTransportCause
import dev.hermes.android.voice.DirectVoice
import dev.hermes.android.voice.CallToolApproval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import java.util.concurrent.ConcurrentLinkedQueue
import android.os.SystemClock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

private fun peerStatus(run: String, action: String): Int {
    val conn = URL("http://10.0.2.2:18996/$run/$action").openConnection(Proxy.NO_PROXY) as HttpURLConnection
    conn.connectTimeout = 2_000
    conn.readTimeout = 2_000
    return try { conn.responseCode } finally { conn.disconnect() }
}

private fun scopedTestClient(source: BeaconClient, transport: OkHttpClient) =
    BeaconClient(source.auth, null, sharedHttp = transport, detachedView = true, persistContext = false, credentialSource = source, configureHttp = {})

private suspend fun measureMemoryPing(client: BeaconClient, call: String, run: String, record: (String, String) -> Unit) {
    val source = client.forCall()
    val productionPing = source.http.pingIntervalMillis
    for (pingMs in listOf(5000, productionPing)) {
        val wire = AtomicReference<Protocol>()
        val actual = source.http.newBuilder().connectionPool(ConnectionPool())
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("10.0.2.2", 18998)))
            .pingInterval(pingMs.toLong(), TimeUnit.MILLISECONDS)
            .addNetworkInterceptor { chain ->
                wire.set(chain.connection()?.protocol())
                chain.proceed(chain.request())
            }.build()
        val measured = scopedTestClient(source, actual)
        try {
            measured.nativeRequest("/api/plugins/beacon/mobile/calls/$call")
            check(wire.get() == Protocol.HTTP_2) { "The delayed-pong test requires negotiated HTTP/2" }
            delay(3200)
            check(peerStatus(run, "ping-hold") == 200)
            delay(200)
            val t = SystemClock.elapsedRealtime()
            val result = runCatching {
                measured.nativeRequest("/api/plugins/beacon/mobile/calls/$call/memory", "POST", json("query" to "synthetic demo fixture at example.com"))
            }
            val elapsed = SystemClock.elapsedRealtime() - t
            val error = result.exceptionOrNull()
            record("ping:sample", "$pingMs $elapsed ${wire.get()} ${error?.javaClass?.simpleName ?: "success"} ${error?.let(::voiceTransportCause) ?: "none"}")
            if (pingMs == productionPing) check(result.getOrThrow().optString("status") == "completed")
            else check(error is IOException && elapsed < 8500) { "The legacy HTTP/2 ping did not interrupt this hold" }
        } finally {
            peerStatus(run, "ping-clear")
            actual.connectionPool.evictAll()
            measured.closeNative()
        }
    }
    source.closeNative()
}

/** Controls only the synthetic operations. Native research executes once, with a real receipt to read back. */
private class ReceiptFaults(private val call: String, private val run: String, private val record: (String, String) -> Unit) : Interceptor {
    fun requestId(id: String) = UUID.nameUUIDFromBytes("$call:$id".toByteArray(Charsets.UTF_8)).toString()
    private val research = requestId("search-research")
    private val firstMemory = AtomicBoolean(true)
    private val reset = AtomicBoolean()
    val freshChecks = AtomicInteger()
    val researchInvokes = AtomicInteger()

    private fun requestBody(request: Request) = Buffer().use { buffer ->
        request.body?.writeTo(buffer)
        JSONObject(buffer.readUtf8().ifBlank { "{}" })
    }

    private val terminalUnknown = requestId("native-unknown")
    val nativeUnknownReads = AtomicInteger()
    val nativeUnknownInvokes = AtomicInteger()
    val staleProbeConfirmed = AtomicBoolean()

    private fun terminal(value: JSONObject) = json("request_id" to terminalUnknown, "tool_name" to value.getString("tool_name"),
        "tool_id" to value.getString("tool_id"), "status" to "uncertain", "read_only" to false,
        "ran_on_hermes" to true, "error_code" to "outcome_unknown", "reason" to "Tool outcome unavailable", "server_ms" to JSONObject())

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val base = "/api/plugins/beacon/mobile/calls/$call"
        val jsonType = "application/json".toMediaType()
        if (path == "$base/memory") {
            if (requestBody(request).getString("query") == "synthetic unavailable provider at example.com") {
                val refused = json("status" to "unavailable", "error_code" to "memory_lookup_failed",
                    "summary" to "Memory lookup failed.", "sources" to JSONArray(), "observed_at" to 1)
                return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(refused.toString().toResponseBody(jsonType)).build()
            }
            if (firstMemory.compareAndSet(true, false)) {
                val deadline = SystemClock.elapsedRealtime() + 14_000
                while (peerStatus(run, "discovered") != 200 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(80)
                check(peerStatus(run, "discovered") == 200)
                reset.set(true)
                record("voice:overlap", "isolated stream reset after discovery answered")
                throw IOException("Synthetic older memory stream reset")
            }
        }
        if (path == base && request.method == "GET" && reset.get()) {
            val attempt = freshChecks.incrementAndGet()
            val proof = chain.proceed(request)
            if (attempt == 1) {
                proof.close()
                val until = SystemClock.elapsedRealtime() + 2800
                while (peerStatus(run, "new-answer") != 200 && SystemClock.elapsedRealtime() < until) Thread.sleep(40)
                check(peerStatus(run, "new-answer") == 200) { "Missing newer call answer" }
                staleProbeConfirmed.set(true)
                record("voice:stale_verifier", "fresh memory completed before verification failed")
                throw java.net.SocketTimeoutException("Synthetic stalled verification response")
            }
            return proof
        }
        val invoked = if (path == "$base/tools/invoke") requestBody(request).optString("request_id") else ""
        if (invoked == research) researchInvokes.incrementAndGet()
        if (invoked == terminalUnknown) nativeUnknownInvokes.incrementAndGet()
        val readsUnknown = path == "$base/tools/requests/$terminalUnknown" && request.method == "GET"
        if (readsUnknown) nativeUnknownReads.incrementAndGet()
        val response = chain.proceed(request)
        val pertains = invoked == terminalUnknown || readsUnknown || path.endsWith("/$terminalUnknown/decision")
        if (!response.isSuccessful || !pertains && path != "$base/updates") return response
        val value = response.use { JSONObject(checkNotNull(it.body).string()) }
        val result = if (path == "$base/updates") {
            val rows = value.getJSONArray("events")
            for (index in rows.length() - 1 downTo 0) {
                val row = rows.getJSONObject(index)
                if (row.optString("request_id") == terminalUnknown && row.optString("status") in setOf("running", "completed", "failed"))
                    rows.put(index, terminal(row).put("type", "receipt").put("seq", row.optLong("seq")))
            }
            value
        } else if (value.optString("status") in setOf("running", "completed", "failed")) terminal(value) else value
        return response.newBuilder().body(result.toString().toResponseBody(jsonType)).build()
    }
}

private fun hasResearchData(receipt: JSONObject): Boolean {
    val outer = receipt.optJSONObject("result") ?: return false
    val result = when (val inner = outer.opt("result")) {
        is JSONObject -> inner
        is String -> runCatching { JSONObject(inner) }.getOrNull() ?: return false
        else -> outer
    }
    val web = result.optJSONObject("data")?.optJSONArray("web") ?: return false
    return result.optBoolean("success", false) && result.isNull("error") && (0 until web.length()).any { index ->
        val item = web.optJSONObject(index)
        item != null && listOf("title", "url").any { (item.opt(it) as? String)?.isNotBlank() == true }
    }
}

/** The emulator's host-loopback peer speaks Gemini Live. The grant, context, tools, approvals and receipts use the real demo. */
internal suspend fun checkRemoteVoiceTools(client: BeaconClient, runId: String, arguments: android.os.Bundle, record: (String, String) -> Unit) {
    require(runId.matches(Regex("[a-f0-9]{32}")))
    fun setting(name: String): String = String(java.util.Base64.getUrlDecoder().decode(
        requireNotNull(arguments.getString(name)) { "Missing instrumentation argument $name" }), Charsets.UTF_8).also { require(it.isNotBlank()) }
    val provider = setting("voiceProviderB64")
    val voiceModel = setting("voiceModelB64")
    val session = client.post("/api/session/new", json("title" to "Beacon remote tools $runId",
        "model" to setting("sessionModelB64"), "model_provider" to setting("sessionProviderB64"))).getString("session_id")
    record("voice:run", runId)
    record("voice:created_session", session)
    val peer = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    val factory = WebSocket.Factory { _, listener ->
        peer.newWebSocket(Request.Builder().url("ws://10.0.2.2:18996/$runId").build(), listener)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val events = ConcurrentLinkedQueue<JSONObject>()
    var callId = ""
    var voice: DirectVoice? = null
    try {
        callId = client.nativeRequest("/api/plugins/beacon/mobile/calls", "POST",
            phoneKeyCallBody(provider, client.auth.profile, session)).getString("call_id")
        val credentials = VoiceSessionCredentials(provider, "loopback-test", voiceModel, defaultVoiceName(provider), "balanced")
        measureMemoryPing(client, callId, runId, record)
        val faults = ReceiptFaults(callId, runId, record)
        val source = client.forCall()
        val faulted = scopedTestClient(source, source.http.newBuilder().addInterceptor(faults).build())
        val direct = DirectVoice(faulted, callId, session, scope, {}, { events.add(it) }, { record("voice:failed", "transport") },
            credentials, providerSocketFactory = factory, prefetched = client.nativeCallContext(callId))
        voice = direct
        direct.start()
        val approved = mutableSetOf<String>()
        val finals = mutableMapOf<String, JSONObject>()
        val expected = listOf("read-memory", "refused-memory", "search-research", "native-unknown").map(faults::requestId).toSet()
        withTimeout(120_000) {
            while (!finals.keys.containsAll(expected)) {
                val event = events.poll()
                if (event == null) { delay(50); continue }
                check(event.optString("type") != "hermes_link" || event.optBoolean("reachable", true)) { "Call-wide outage despite a working call" }
                val name = event.optString("tool_name")
                if (event.optString("type") == "tool_approval") {
                    val approval = CallToolApproval.from(event)
                    if (approval.requestId in setOf(faults.requestId("search-research"), faults.requestId("native-unknown")) && approved.add(approval.proposalId))
                        direct.decideTool(approval.requestId, approval.proposalId, "approve")
                }
                if (event.optString("type") == "tool_receipt" && event.optString("request_id") in expected) {
                    val status = event.optString("status")
                    record("voice:receipt", "$name $status")
                    if (status in setOf("completed", "failed", "denied", "expired", "cancelled") || status == "uncertain" && event.optString("error_code") == "outcome_unknown") finals[event.getString("request_id")] = event
                }
            }
        }
        val receipt = finals.getValue(faults.requestId("search-research"))
        check(receipt.getString("status") == "completed")
        val verified = NativeToolGateway(client, callId).status(receipt.getString("request_id"))
        check(verified.getString("status") == "completed" && hasResearchData(verified))
        check(hasResearchData(receipt))
        val unknown = finals.getValue(faults.requestId("native-unknown"))
        check(unknown.getString("status") == "uncertain" && unknown.getString("error_code") == "outcome_unknown")
        record("voice:server_receipt", verified.getString("status"))
        val memory = finals.getValue(faults.requestId("read-memory"))
        check(memory.getString("status") == "completed" && memory.optBoolean("result_present"))
        val refused = finals.getValue(faults.requestId("refused-memory"))
        check(refused.getString("status") == "failed" && refused.getString("error_code") == "memory_lookup_failed")
        record("voice:refused_memory", refused.getString("error_code"))
        check(faults.staleProbeConfirmed.get() && faults.freshChecks.get() == 1 && faults.researchInvokes.get() == 1)
        record("voice:memory", memory.optString("error_code", memory.getString("status")))
        // Let the writer deliver both terminal functionResponses to the peer before closing the call.
        withTimeout(10_000) {
            while (true) {
                val result = (URL("http://10.0.2.2:18996/$runId/received").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection)
                val received = try { result.responseCode == 200 } finally { result.disconnect() }
                if (received) break
                delay(100)
            }
        }
        delay(2200)
        check(faults.nativeUnknownInvokes.get() == 1 && faults.nativeUnknownReads.get() == 0)
        check(peerStatus(runId, "received") == 200)
        record("voice:terminal_unknown", "invokes=${faults.nativeUnknownInvokes.get()} reads=${faults.nativeUnknownReads.get()}")
    } finally {
        voice?.close()
        scope.cancel()
        peer.dispatcher.cancelAll()
        peer.connectionPool.evictAll()
        peer.dispatcher.executorService.shutdown()
        if (callId.isNotBlank()) client.nativeRequest("/api/plugins/beacon/mobile/calls/$callId", "DELETE")
    }
}
