package dev.hermes.android.data

import dev.hermes.android.core.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeOperationsTest {
    /** A Hermes whose chat has no agent until `approval.pending` starts it, like a lazily resumed runtime. */
    private class Fixture(val refuseBuild: Boolean = false, val canonical: Boolean = false) : WebSocket.Factory {
        val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
        val calls = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        private lateinit var listener: WebSocketListener
        private lateinit var socket: WebSocket
        var agentBuilt = false
        var builtAtWrite: Boolean? = null
        var model = "old-model"
        var yolo = true
        var steerResult = json("status" to "queued")
        var steerErrorCode: Int? = null
        private fun reply(id: String, result: JSONObject) = json("jsonrpc" to "2.0", "id" to id, "result" to result).toString()
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, this) { builder ->
            builder.addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                val found = !path.endsWith("/voice-history")
                val body = when {
                    path == "/api/auth/ws-ticket" -> json("ticket" to "fixture-ticket")
                    path == "/api/sessions/stored-a" -> json("id" to "stored-a", "profile" to "default", "title" to "Fixture", "message_count" to 0)
                    path == "/api/sessions/stored-a/messages" -> json("session_id" to "stored-a", "profile" to "default", "messages" to JSONArray(), "pagination" to json("returned" to 0))
                    else -> json("detail" to "Not Found")
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (found) 200 else 404).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
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
                    calls += frame.copy()
                    val method = frame.getString("method")
                    methods += method
                    val params = frame.getJSONObject("params")
                    if (method == "session.steer" && steerErrorCode != null) {
                        listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to json("code" to steerErrorCode, "message" to "Synthetic steer refusal")).toString())
                        return true
                    }
                    val result = when (method) {
                        "session.resume" -> json("session_id" to if (canonical) "stored-a" else "runtime-a", "session_key" to "stored-a", "stored_session_id" to "stored-a", "message_count" to 0, "messages" to JSONArray(),
                            // Hermes reports the chat's YOLO state only from a built agent.
                            "info" to json("model" to model, "provider" to "fixture", "lazy" to !agentBuilt).apply { if (agentBuilt) put("yolo", yolo) }).apply {
                                if (canonical) put("execution_generation", 7).put("running", true)
                            }
                        "approval.pending" -> {
                            if (refuseBuild) {
                                listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to json("code" to 5032, "message" to "Fixture builder refused")).toString())
                                return true
                            }
                            agentBuilt = true
                            json("approvals" to JSONArray())
                        }
                        "config.set" -> {
                            builtAtWrite = agentBuilt
                            if (params.getString("key") == "yolo") {
                                yolo = params.getString("value") == "1"
                                json("key" to "yolo", "value" to params.getString("value"))
                            } else {
                                model = params.getString("value").substringBefore(" --provider ")
                                json("key" to "model", "value" to model, "confirm_required" to false)
                            }
                        }
                        "config.get" -> json("key" to params.getString("key"), "value" to "smart")
                        "session.events.since" -> json("epoch" to "fixture", "latest_seq" to 0, "events" to JSONArray())
                        "session.steer" -> steerResult.copy()
                        "image.attach_bytes" -> json("attached" to true, "path" to "/synthetic/image.png", "mime" to "image/png")
                        "prompt.submit" -> if (canonical) json("status" to "started", "admission_id" to "admission-a", "ref" to json("session_id" to "stored-a"))
                            else json("status" to "streaming", "user_row_id" to 42)
                        else -> JSONObject()
                    }
                    listener.onMessage(this, reply(frame.getString("id"), result))
                    return true
                }
            }
            this.listener = listener; this.socket = socket
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            listener.onMessage(socket, json("jsonrpc" to "2.0", "method" to "event", "params" to json("type" to "gateway.ready", "payload" to json("session_authority" to canonical))).toString())
            return socket
        }
        fun emitToken(text: String, type: String = "token") = listener.onMessage(socket, json("method" to "event", "params" to json("session_id" to "runtime-a", "seq" to 1, "type" to type, "payload" to json("text" to text))).toString())
    }

    private suspend fun pick(fixture: Fixture) =
        fixture.client.post("/api/session/update", json("session_id" to "stored-a", "model" to "picked-model", "model_provider" to "fixture")).getJSONObject("session")

    @Test fun gatewayReadinessDoesNotCountAsTurnProgress() = runBlocking {
        val fixture = Fixture()
        val events = java.util.concurrent.CopyOnWriteArrayList<SseEvent>()
        val token = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.IO) {
            fixture.client.nativeStream("/api/chat/stream", mapOf("session_id" to "stored-a")) { event ->
                events += event
                if (event.payload().string("text") == "Real turn progress") token.complete(Unit)
            }
        }
        try {
            withTimeout(5_000) { while (fixture.calls.count { it.string("method") == "session.events.since" } < 2) delay(10) }
            fixture.emitToken("Real turn progress", "message.delta")
            withTimeout(5_000) { token.await() }
            assertEquals("Connection admission must not reset a quiet turn's backoff", 1, events.size)
            assertEquals("token", events.single().type)
            assertEquals("Real turn progress", events.single().payload().string("text"))
            assertTrue(collector.isActive)
        } finally { collector.cancelAndJoin(); fixture.client.closeNative() }
    }

    // A pick that names a provider, on a chat whose agent was still starting, showed "updated" and was then lost.
    @Test fun modelPickWaitsForTheChatsAgentBeforeItIsWritten() = runBlocking {
        val fixture = Fixture()
        try {
            val session = pick(fixture)
            assertEquals(listOf("session.resume", "approval.pending", "config.set", "session.resume"), fixture.methods.filter { it != "client.capabilities" })
            assertEquals(true, fixture.builtAtWrite)
            assertEquals("picked-model", session.getString("model"))
        } finally { fixture.client.closeNative() }
    }

    @Test fun steerUsesTheBoundRuntimeWithoutReplacingTheLiveCollector() = runBlocking {
        val fixture = Fixture()
        try {
            val binding = fixture.client.nativeSession("stored-a")
            fixture.client.nativeReplay(beforePrompt = true)
            val token = CompletableDeferred<String>()
            val collector = launch(Dispatchers.IO) { fixture.client.nativeEvents { frame ->
                val params = frame.optJSONObject("params") ?: return@nativeEvents
                if (params.string("type") == "token") token.complete(params.getJSONObject("payload").string("text"))
            } }
            withTimeout(5_000) { while (fixture.calls.count { it.string("method") == "session.events.since" } < 2) delay(10) }
            val response = fixture.client.post("/api/chat/steer", json("session_id" to "stored-a", "runtime_session_id" to binding.runtimeSessionId, "text" to "Make it concise"))
            assertEquals("queued", response.string("status"))
            val sent = fixture.calls.single { it.string("method") == "session.steer" }.getJSONObject("params")
            assertEquals("runtime-a", sent.string("session_id"))
            assertEquals("default", sent.string("profile"))
            assertEquals("Make it concise", sent.string("text"))
            assertFalse(sent.has("execution_generation"))
            fixture.emitToken("still streaming")
            assertEquals("still streaming", withTimeout(5_000) { token.await() })
            assertTrue(collector.isActive)
            collector.cancelAndJoin()
        } finally { fixture.client.closeNative() }
    }

    @Test fun aRefusedSteerDoesNotCloseTheLiveCollector() = runBlocking {
        val fixture = Fixture()
        try {
            val binding = fixture.client.nativeSession("stored-a")
            fixture.client.nativeReplay(beforePrompt = true)
            val token = CompletableDeferred<String>()
            val collector = launch(Dispatchers.IO) { fixture.client.nativeEvents { frame ->
                val params = frame.optJSONObject("params") ?: return@nativeEvents
                if (params.string("type") == "token") token.complete(params.getJSONObject("payload").string("text"))
            } }
            withTimeout(5_000) { while (fixture.calls.count { it.string("method") == "session.events.since" } < 2) delay(10) }
            fixture.steerErrorCode = 4010
            val failure = runCatching { fixture.client.post("/api/chat/steer", json("session_id" to "stored-a", "runtime_session_id" to binding.runtimeSessionId, "text" to "Correction")) }.exceptionOrNull()
            assertTrue(failure is NativeTurnNotSent)
            assertEquals(1, fixture.calls.count { it.string("method") == "session.steer" })
            fixture.emitToken("still streaming after refusal")
            assertEquals("still streaming after refusal", withTimeout(5_000) { token.await() })
            assertTrue(collector.isActive)
            collector.cancelAndJoin()
        } finally { fixture.client.closeNative() }
    }

    @Test fun canonicalSubmitCarriesTheSavedIdentityModelAndImageOnTheAdvertisedAuthority() = runBlocking {
        val fixture = Fixture(canonical = true)
        try {
            val attachment = json("id" to "synthetic", "name" to "sample.png", "mime" to "image/png", "staged_base64" to "c2FtcGxl",
                "scope_key" to fixture.client.scope.session("stored-a").key)
            val result = fixture.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Describe this", "client_request_id" to "local-123",
                "attachments" to JSONArray().put(attachment), "queued_model_snapshot" to true, "model" to "snapshot-model", "model_provider" to "fixture"))
            assertEquals("admission-a", result.string("admission_id"))
            val sent = fixture.calls.single { it.string("method") == "prompt.submit" }.getJSONObject("params")
            assertEquals("local-123", sent.string("submission_id"))
            assertTrue(sent.optBoolean("queued"))
            assertEquals("stored-a", sent.string("session_id"))
            assertEquals("snapshot-model", sent.getJSONObject("model_override").string("model"))
            assertEquals("fixture", sent.getJSONObject("model_override").string("provider"))
            assertEquals("/synthetic/image.png", sent.getJSONArray("attachments").getJSONObject(0).string("path"))
            assertEquals("image/png", sent.getJSONArray("attachments").getJSONObject(0).string("mime"))
        } finally { fixture.client.closeNative() }
    }

    @Test fun canonicalRejectionWithoutDeliveredFalseIsNotSafeToQueue() = runBlocking {
        val fixture = Fixture(canonical = true)
        try {
            val binding = fixture.client.nativeSession("stored-a")
            fixture.steerResult = json("status" to "rejected", "execution_generation" to 7, "authority_epoch" to 1)
            val body = json("session_id" to "stored-a", "runtime_session_id" to binding.runtimeSessionId, "text" to "Correction")
            assertFalse(fixture.client.post("/api/chat/steer", body).optBoolean("safe_to_queue"))
            fixture.steerResult = fixture.steerResult.copy().put("delivered", false)
            assertTrue(fixture.client.post("/api/chat/steer", body).optBoolean("safe_to_queue"))
            assertTrue(fixture.calls.filter { it.string("method") == "session.steer" }.all { it.getJSONObject("params").optInt("execution_generation") == 7 })
        } finally { fixture.client.closeNative() }
    }

    @Test fun legacyModelMismatchAndUnsupportedCanonicalFileAreRefusedBeforePrompt() = runBlocking {
        val legacy = Fixture()
        try {
            val failure = runCatching { legacy.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Queued text",
                "client_request_id" to "local-a", "queued_model_snapshot" to true, "model" to "earlier-model", "model_provider" to "fixture")) }.exceptionOrNull()
            assertTrue(failure is NativeTurnNotSent)
            assertTrue(legacy.calls.none { it.string("method") in setOf("prompt.submit", "config.set") })
        } finally { legacy.client.closeNative() }
        val canonical = Fixture(canonical = true)
        try {
            val file = json("id" to "file-a", "name" to "notes.txt", "mime" to "text/plain", "staged_base64" to "c2FtcGxl",
                "scope_key" to canonical.client.scope.session("stored-a").key)
            val failure = runCatching { canonical.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Read this",
                "client_request_id" to "local-b", "attachments" to JSONArray().put(file))) }.exceptionOrNull()
            assertTrue(failure is NativeTurnNotSent)
            assertTrue(canonical.calls.none { it.string("method") == "prompt.submit" })
        } finally { canonical.client.closeNative() }
    }

    @Test fun aLegacyPhoneQueueRemainsQueuedIfTheIdleCheckRacesAnotherTurn() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Next turn", "client_request_id" to "local-a",
                "queued_model_snapshot" to true, "model" to "old-model", "model_provider" to "fixture"))
            val sent = fixture.calls.single { it.string("method") == "prompt.submit" }.getJSONObject("params")
            assertTrue(sent.optBoolean("queued"))
            assertFalse(sent.has("submission_id"))
        } finally { fixture.client.closeNative() }
    }

    @Test fun modelPickStillReachesHermesWhenTheAgentBuildFailed() = runBlocking {
        val fixture = Fixture(refuseBuild = true)
        try {
            val session = pick(fixture)
            assertEquals(false, fixture.builtAtWrite)
            assertEquals("picked-model", session.getString("model"))
        } finally { fixture.client.closeNative() }
    }

    private suspend fun yoloOff(fixture: Fixture) =
        fixture.client.post("/api/approval/mode", json("session_id" to "stored-a", "yolo" to false, "scope_key" to fixture.client.scope.session("stored-a").key))

    // YOLO off right after opening a chat: Hermes saved it, but the check ran before the agent could report it and the change showed as failed.
    @Test fun yoloOffWaitsForTheChatsAgentSoTheChangeCanBeVerified() = runBlocking {
        val fixture = Fixture()
        try {
            val result = yoloOff(fixture)
            assertEquals(listOf("session.resume", "approval.pending", "config.set", "config.get", "session.resume"), fixture.methods.filter { it != "client.capabilities" })
            assertEquals(true, fixture.builtAtWrite)
            assertEquals(false, fixture.yolo)
            assertTrue(result.getBoolean("session_status_known"))
            assertEquals(false, result.getBoolean("session_bypass"))
        } finally { fixture.client.closeNative() }
    }

    @Test fun yoloOffIsNotReportedAsVerifiedWhenTheAgentBuildFailed() = runBlocking {
        val fixture = Fixture(refuseBuild = true)
        try {
            assertNotNull(runCatching { yoloOff(fixture) }.exceptionOrNull())
        } finally { fixture.client.closeNative() }
    }
}
