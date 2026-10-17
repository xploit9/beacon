package dev.hermes.android.data

import dev.hermes.android.core.json
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NativeGatewayProtocolTest {
    private class Wire : WebSocket.Factory {
        val requests = mutableListOf<Request>()
        val sent = mutableListOf<JSONObject>()
        var approvalMode = "manual"
        var ignoreApprovalWrite = false
        var yoloAcknowledgement = "0"
        var sessionBypass: Boolean? = false
        var failPrompt = false
        var silentPrompt = false
        var replayEvents = JSONArray().put(json("session_id" to "runtime-a", "seq" to 1, "type" to "message.delta", "payload" to json("text" to "hello")))
        lateinit var listener: WebSocketListener
        lateinit var ws: WebSocket
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            requests.add(request); this.listener = listener
            ws = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
                override fun send(text: String): Boolean {
                    val call = JSONObject(text); sent.add(call)
                    if (failPrompt && call.getString("method") == "prompt.submit") {
                        listener.onFailure(this, IOException("Lost response"), null); return true
                    }
                    if (silentPrompt && call.getString("method") == "prompt.submit") return true
                    val result = when(call.getString("method")) {
                        "session.create", "session.resume" -> json("session_id" to "runtime-a", "stored_session_id" to "stored-a", "info" to json("yolo" to sessionBypass))
                        "session.events.since" -> json("epoch" to "epoch-a", "latest_seq" to 2, "events" to replayEvents)
                        "prompt.submit" -> json("status" to "streaming", "user_row_id" to 42)
                        "session.steer" -> json("status" to "queued")
                        "session.list" -> json("sessions" to JSONArray().put(json("id" to "visible", "title" to "Visible title", "secret_metadata" to "not-searchable")).apply { if (call.getJSONObject("params").optBoolean("include_hidden")) { put(json("id" to "hidden", "title" to "Archived title")); put(json("id" to "hidden", "title" to "Archived title")) } })
                        "config.get" -> json("value" to approvalMode)
                        "config.set" -> { val params = call.getJSONObject("params"); if (params.getString("key") == "approvals.mode" && !ignoreApprovalWrite) approvalMode = params.getString("value"); json("key" to params.getString("key"), "value" to if (params.getString("key") == "yolo") yoloAcknowledgement else params.getString("value")) }
                        "commands.catalog" -> json("pairs" to JSONArray().put(JSONArray().put("/help").put("Help")))
                        else -> JSONObject()
                    }
                    listener.onMessage(this, json("jsonrpc" to "2.0", "id" to call.getString("id"), "result" to result).toString())
                    return true
                }
            }
            listener.onOpen(ws, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
            return ws
        }
        fun event(session: String, seq: Int) = listener.onMessage(ws, json("method" to "event", "params" to json("session_id" to session, "seq" to seq, "type" to "message.delta", "payload" to json("text" to "x"))).toString())
    }
    private fun client(wire: Wire? = null, voiceStatus: Int = 404): CypherClient = CypherClient(AuthContext("https://native.example", emptyList(), "owner", "work", backend = "hermes-dashboard"), null, wire) { builder ->
        builder.addInterceptor { chain ->
            val voice = chain.request().url.encodedPath.endsWith("/voice-history")
            val path = chain.request().url.encodedPath
            val payload = when {
                voice -> json("session_id" to "stored-a", "profile" to "work", "events" to JSONArray())
                path == "/api/plugins/cypher/mobile/sessions/stored-a" -> json("session_id" to "stored-a", "profile" to "work", "messages" to JSONArray(), "runtime_session_id" to "runtime-a")
                path == "/api/plugins/cypher/mobile/sessions" -> json("sessions" to JSONArray().put(if (chain.request().url.queryParameter("include_archived") == "1") json("id" to "hidden", "title" to "Archived title") else json("id" to "visible", "title" to "Visible title", "secret_metadata" to "not-searchable")))
                else -> json("ticket" to "fixture-single-use-ticket")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (voice) voiceStatus else 200).message("Fixture").body(payload.toString().toResponseBody("application/json".toMediaType())).build()
        }
    }
    @Test fun approvalsUseNativeProfileModesAndRemoveOnlyTheSelectedSessionBypass() = runBlocking {
        val wire = Wire(); val c = client(wire)
        for (mode in nativeApprovalModes) {
            val result = c.call("/api/approval/mode", method = "POST", data = json("session_id" to "stored-a", "mode" to mode, "scope_key" to c.scope.session("stored-a").key))
            assertEquals(mode, result.getString("value"))
            assertEquals(c.scope.key, result.getString("scope_key"))
        }
        val writes = wire.sent.filter { it.getString("method") == "config.set" }.map { it.getJSONObject("params") }
        assertEquals(6, writes.size)
        for (pair in writes.chunked(2)) {
            assertEquals("yolo", pair[0].getString("key"));assertEquals("off", pair[0].getString("value"))
            assertEquals("session", pair[0].getString("scope"));assertEquals("runtime-a", pair[0].getString("session_id"))
            assertEquals("approvals.mode", pair[1].getString("key"))
            assertTrue(pair.all { it.getString("profile") == "work" })
        }
        c.closeNative()
    }
    @Test fun aLazyChatCanChooseItsProfilePolicyBeforeAgentInfoArrives() = runBlocking {
        val wire = Wire().apply { sessionBypass = null }; val c = client(wire)
        val result = c.get("/api/approval/mode", mapOf("session_id" to "stored-a"))
        assertEquals("manual", result.getString("value")); assertFalse(result.getBoolean("session_status_known"))
        val applied = c.call("/api/approval/mode", method="POST", data=json("session_id" to "stored-a", "mode" to "smart", "scope_key" to c.scope.session("stored-a").key))
        assertEquals("smart", applied.getString("value")); assertFalse(applied.getBoolean("session_status_known"))
        c.closeNative()
    }
    @Test fun profileManualModeDoesNotHideAConversationBypass() = runBlocking {
        val wire = Wire().apply { sessionBypass = true }; val c = client(wire)
        val result = c.get("/api/approval/mode", mapOf("session_id" to "stored-a"))
        assertEquals("off", result.getString("value")); assertEquals("manual", result.getString("profile_mode"))
        assertTrue(result.getBoolean("session_bypass")); assertEquals("stored-a", result.getString("session_id"))
        assertTrue(runCatching { c.call("/api/approval/mode", method="POST", data=json("session_id" to "stored-a", "mode" to "manual", "scope_key" to c.scope.session("stored-a").key)) }.isFailure)
        c.closeNative()
    }
    @Test fun approvalModeNeverChangesIfNativeBypassWasNotDisabled() = runBlocking {
        val wire = Wire().apply { yoloAcknowledgement = "1" }; val c = client(wire)
        assertTrue(runCatching { c.call("/api/approval/mode", method="POST", data=json("session_id" to "stored-a", "mode" to "smart", "scope_key" to c.scope.session("stored-a").key)) }.isFailure)
        assertFalse(wire.sent.any { it.getString("method") == "config.set" && it.getJSONObject("params").getString("key") == "approvals.mode" })
        c.closeNative()
    }
    @Test fun approvalsDoNotReportSuccessWhenEffectiveReadbackDiffers() = runBlocking {
        val wire = Wire().apply { ignoreApprovalWrite = true }; val c = client(wire)
        assertTrue(runCatching { c.call("/api/approval/mode", method="POST", data=json("session_id" to "stored-a", "mode" to "smart", "scope_key" to c.scope.session("stored-a").key)) }.isFailure)
        assertEquals("manual", wire.approvalMode)
        c.closeNative()
    }
    @Test fun approvalsRejectUnknownModeExtraFieldsAndForeignProfileBeforeAnyNativeWrite() = runBlocking {
        val wire = Wire(); val c = client(wire)
        for (body in listOf(json("session_id" to "stored-a", "mode" to "always", "scope_key" to c.scope.session("stored-a").key),json("session_id" to "stored-a", "mode" to "off", "scope_key" to c.scope.session("stored-a").key, "scope" to "global"),json("session_id" to "stored-a", "mode" to "off", "scope_key" to "other-account"),json("session_id" to "other-session", "mode" to "off", "scope_key" to c.scope.session("stored-a").key))) {
            assertTrue(runCatching { c.call("/api/approval/mode", method="POST", data=body) }.isFailure)
        }
        assertTrue(runCatching { c.get("/api/approval/mode",mapOf("profile" to "other")) }.isFailure)
        assertTrue(wire.sent.isEmpty())
        wire.approvalMode="unknown-native-mode"
        assertTrue(runCatching { c.get("/api/approval/mode") }.isFailure)
        assertFalse(wire.sent.any { it.getString("method") == "config.set" })
        c.closeNative()
    }

    @Test fun createPublishesAckAndPassiveHistoryNeverResumesAnAgent() = runBlocking<Unit> {
        val wire = Wire(); val c = client(wire, 500)
        val created = c.post("/api/session/new")
        assertEquals("stored-a", created.getString("session_id"))
        assertTrue(created.getBoolean("acknowledged_shell"))
        assertEquals(listOf("session.create"), wire.sent.map { it.getString("method") })
        wire.sent.clear()
        val fetched = c.get("/api/session", mapOf("session_id" to "stored-a"))
        assertEquals("stored-a", fetched.getString("session_id"))
        assertEquals(0, wire.sent.count { it.getString("method") == "session.resume" })
        assertEquals(0, wire.sent.count { it.getString("method") == "session.history" })
        assertFalse(fetched.getBoolean("voice_transcripts_available"))
    }
    @Test fun guideUsesNativeCorrectionAndNeverPromptSubmit() = runBlocking<Unit> {
        val wire = Wire(); val c = client(wire)
        assertTrue(c.post("/api/chat/steer", json("session_id" to "stored-a", "text" to "correction")).getBoolean("accepted"))
        val before = wire.sent.size
        assertTrue(runCatching { c.post("/api/chat/steer", json("session_id" to "stored-a", "text" to "correction", "attachments" to JSONArray().put(json("id" to "media")))) }.isFailure)
        assertEquals(before, wire.sent.size)
        assertTrue(wire.sent.any { it.getString("method") == "session.steer" })
        assertTrue(wire.sent.none { it.getString("method") == "prompt.submit" })
    }
    @Test fun displayPreferenceCannotBecomeReasoningMutation() = runBlocking<Unit> {
        val wire = Wire(); val c = client(wire)
        assertTrue(runCatching { c.post("/api/reasoning", json("session_id" to "stored-a", "display" to "hide")) }.isFailure)
        assertTrue(wire.sent.none { it.getString("method") == "config.set" })
        assertTrue(runCatching { c.post("/api/chat/start", json("message" to "missing selection")) }.isFailure)
        assertTrue(wire.sent.none { it.getString("method") == "session.create" })
    }
    @Test fun catalogBrowsingDoesNotCreateAndArchivedSearchUsesBoundedIdentities() = runBlocking<Unit> {
        val wire = Wire(); val c = client(wire)
        val catalog = c.get("/api/commands/catalog")
        assertEquals("help", catalog.getJSONArray("commands").getJSONObject(0).getString("name"))
        assertEquals(listOf("commands.catalog"), wire.sent.map { it.getString("method") })
        val archive = c.get("/api/sessions", mapOf("include_archived" to "1"))
        assertEquals(1, archive.getJSONArray("sessions").length())
        assertEquals("hidden", archive.getJSONArray("sessions").getJSONObject(0).getString("session_id"))
        assertTrue(archive.getJSONArray("sessions").getJSONObject(0).getBoolean("archived"))
        assertEquals(200, archive.getInt("limit"))
        assertEquals(0, c.get("/api/sessions/search", mapOf("q" to "not-searchable")).getJSONArray("sessions").length())
        assertEquals(1, c.get("/api/sessions/search", mapOf("q" to "Visible title")).getJSONArray("sessions").length())
        assertTrue(wire.sent.none { it.getString("method") == "session.create" })
    }
    @Test fun ticketAdmissionAndSessionIdentitiesAreSeparate() = runBlocking<Unit> {
        val wire = Wire(); val gateway = NativeGateway(client(), wire)
        val binding = gateway.session(null)
        assertEquals("stored-a", binding.storedSessionId)
        assertEquals("runtime-a", binding.runtimeSessionId)
        assertNull(wire.requests.single().url.queryParameter("ticket"))
        assertEquals("hermes-gateway-v1, hermes-gateway-ticket.fixture-single-use-ticket", wire.requests.single().header("Sec-WebSocket-Protocol"))
        assertEquals("work", wire.sent.single().getJSONObject("params").getString("profile"))
        assertFalse(wire.sent.single().getJSONObject("params").getBoolean("close_on_disconnect"))
        gateway.close()
        gateway.rpc("session.history", json("session_id" to "runtime-a"))
        assertEquals(2, wire.requests.size)
        assertTrue(wire.sent.none { it.getString("method") == "session.interrupt" })
        gateway.close()
    }
    @Test fun acceptedPromptMetadataIsPreservedAndLostAckIsNeverRetried() = runBlocking<Unit> {
        val wire = Wire(); val gateway = NativeGateway(client(), wire)
        assertEquals(42, gateway.rpc("prompt.submit", json("session_id" to "runtime-a", "text" to "fixture")).getInt("user_row_id"))
        wire.failPrompt = true
        assertTrue(runCatching { gateway.rpc("prompt.submit", json("session_id" to "runtime-a", "text" to "fixture")) }.isFailure)
        assertEquals(2, wire.sent.count { it.getString("method") == "prompt.submit" })
        gateway.close()
    }
    @Test fun replayAndLiveFramesStayScopedAndDeduplicated() = runBlocking<Unit> {
        val wire = Wire(); val gateway = NativeGateway(client(), wire)
        gateway.session("stored-a")
        wire.event("foreign-runtime", 1); wire.event("runtime-a", 1); wire.event("runtime-a", 2)
        val received = mutableListOf<Long>()
        val stop = IOException("fixture-stop")
        try { gateway.events { frame -> val seq = frame.getJSONObject("params").getLong("seq"); received.add(seq); if (seq == 2L) throw stop } } catch (e: IOException) { assertSame(stop, e) }
        assertEquals(listOf(1L, 2L), received)
        assertFalse(wire.sent.first().getJSONObject("params").has("source"))
        gateway.close()
    }
    @Test fun currentTurnReplayDoesNotTerminateOnAnOlderCompletion() = runBlocking<Unit> {
        val wire = Wire(); val gateway = NativeGateway(client(), wire)
        gateway.session("stored-a")
        wire.replayEvents = JSONArray()
            .put(json("session_id" to "runtime-a", "seq" to 1, "type" to "message.complete"))
            .put(json("session_id" to "runtime-a", "seq" to 2, "type" to "message.start"))
            .put(json("session_id" to "runtime-a", "seq" to 3, "type" to "message.delta", "payload" to json("text" to "current")))
        gateway.prepareReplay()
        val types = mutableListOf<String>(); val stop = IOException("fixture-stop")
        try { gateway.events { frame -> val type = frame.getJSONObject("params").getString("type"); types.add(type); if (type == "message.delta") throw stop } } catch (e: IOException) { assertSame(stop, e) }
        assertEquals(listOf("message.start", "message.delta"), types)
        gateway.close()
    }

    @Test fun unverifiedLegacyIdentityCannotMintNativeTicket() = runBlocking<Unit> {
        val wire = Wire(); val client = CypherClient(AuthContext("https://native.example", emptyList(), "legacy-owner", csrf = "legacy-csrf"))
        val gateway = NativeGateway(client, wire)
        assertTrue(runCatching { gateway.rpc("session.create", JSONObject()) }.isFailure)
        assertTrue(wire.requests.isEmpty())
    }

    @Test fun overflowCannotLoseConnectionTermination() = runBlocking<Unit> {
        val wire = Wire(); val gateway = NativeGateway(client(), wire)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val collector = async { runCatching { gateway.events { entered.complete(Unit); release.await() } }.exceptionOrNull() }
        while (wire.requests.isEmpty()) yield()
        wire.event("unbound", 1); entered.await()
        repeat(257) { wire.event("unbound", it + 2) }
        release.complete(Unit)
        val failure = withTimeout(2000) { collector.await() }
        assertTrue(failure is IOException && failure.message.orEmpty().contains("overflow"))
        gateway.close()
    }
    @Test fun explicitCloseWakesIdleCollectorAndNewConnectionIsIndependent() = runBlocking<Unit> {
        val wire = Wire(); val gateway = NativeGateway(client(), wire)
        val collector = async { runCatching { gateway.events { } }.exceptionOrNull() }
        while (wire.requests.isEmpty()) yield()
        gateway.close()
        assertTrue(withTimeout(2000) { collector.await() } is IOException)
        gateway.rpc("session.history", json("session_id" to "runtime-a"))
        assertEquals(2, wire.requests.size)
        gateway.close()
    }
    @Test fun rpcTimeoutIsUnknownOutcomeButCallerCancellationRemainsCancellation() = runBlocking<Unit> {
        val wire = Wire().apply { silentPrompt = true }; val gateway = NativeGateway(client(), wire, 30)
        val error = runCatching { gateway.rpc("prompt.submit", JSONObject()) }.exceptionOrNull()
        assertTrue(error is IOException && error.message.orEmpty().contains("outcome may be unknown"))
        assertEquals(1, wire.sent.count { it.getString("method") == "prompt.submit" })
        val longGateway = NativeGateway(client(), wire, 60000)
        val cancelled = async { runCatching { longGateway.rpc("prompt.submit", JSONObject()) }.exceptionOrNull() }
        while (wire.sent.count { it.getString("method") == "prompt.submit" } < 2) yield()
        cancelled.cancel()
        assertTrue(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
        gateway.close(); longGateway.close()
    }
    @Test fun onlyOneCollectorOwnsEachConnectionAndUnboundSequencesNeverFetchNullHistory() = runBlocking<Unit> {
        val wire = Wire(); val gateway = NativeGateway(client(), wire)
        val received = CompletableDeferred<Unit>()
        val collector = async { runCatching { gateway.events { if (it.getJSONObject("params").getInt("seq") == 4) received.complete(Unit) } }.exceptionOrNull() }
        while (wire.requests.isEmpty()) yield()
        assertTrue(runCatching { gateway.events { } }.exceptionOrNull() is IllegalStateException)
        wire.event("unbound", 1); wire.event("unbound", 4)
        withTimeout(2000) { received.await() }
        assertTrue(wire.sent.none { it.getString("method") == "session.history" })
        gateway.close(); collector.await()
    }
    @Test fun stopWithRuntimeIdNeverCreatesOrResumesASession() = runBlocking<Unit> {
        val wire = Wire(); val c = client(wire)
        c.get("/api/chat/cancel", mapOf("stream_id" to "runtime-a"))
        assertEquals(listOf("session.interrupt"), wire.sent.map { it.getString("method") })
        assertEquals("runtime-a", wire.sent.single().getJSONObject("params").getString("session_id"))
        assertTrue(runCatching { c.get("/api/chat/cancel") }.isFailure)
        assertEquals(1, wire.sent.size)
    }

    @Test fun missingVoiceArchiveDoesNotBlockNativeHistoryButAuthFailurePropagates() = runBlocking<Unit> {
        for (status in listOf(403, 404)) {
            val c = client(Wire(), status)
            val snapshot = c.get("/api/session", mapOf("session_id" to "stored-a"))
            assertEquals("stored-a", snapshot.getString("session_id"))
            assertFalse(snapshot.getBoolean("voice_transcripts_available"))
        }
        val error = runCatching { client(Wire(), 401).get("/api/session", mapOf("session_id" to "stored-a")) }.exceptionOrNull()
        assertTrue(error is ApiFailure && error.status == 401)
    }
    @Test fun methodOnlyCompletionClosesLogicalChatStream() = runBlocking<Unit> {
        val wire = Wire(); val c = client(wire)
        val types = mutableListOf<String>()
        val collector = async { c.stream("/api/chat/stream", mapOf("session_id" to "stored-a")) { types.add(it.type) } }
        while (wire.sent.count { it.getString("method") == "session.events.since" } < 2) yield()
        wire.listener.onMessage(wire.ws, json("method" to "message.complete", "params" to json("text" to "complete")).toString())
        withTimeout(2000) { collector.await() }
        assertEquals("done", types.last())
    }

}
