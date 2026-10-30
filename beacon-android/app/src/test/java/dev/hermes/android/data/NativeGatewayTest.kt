package dev.hermes.android.data

import dev.hermes.android.ChatState
import dev.hermes.android.Message
import dev.hermes.android.releaseTerminalChat
import dev.hermes.android.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeGatewayTest {
    private class RecoveryFixture : WebSocket.Factory {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val streamSubscribed = CompletableDeferred<Unit>()
        val storedSessionId = "chat-background"
        @Volatile var currentRuntimeId = "runtime-created"
        @Volatile var turnStatus = "working"
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, this) { builder ->
            builder.addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(json("ticket" to "fixture-ticket").toString().toResponseBody("application/json".toMediaType())).build() }
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
                    val params = frame.getJSONObject("params").copy()
                    calls += method to params
                    val result = when (method) {
                        "session.create" -> json("session_id" to currentRuntimeId, "stored_session_id" to storedSessionId, "messages" to JSONArray(), "info" to json("lazy" to true))
                        "session.resume" -> json("session_id" to currentRuntimeId, "session_key" to storedSessionId, "messages" to JSONArray(), "info" to json("lazy" to true))
                        "session.events.since" -> {
                            if (calls.count { it.first == method } >= 3) streamSubscribed.complete(Unit)
                            json("events" to JSONArray(), "latest_seq" to 0, "epoch" to "fixture-epoch")
                        }
                        "prompt.submit" -> json("status" to "streaming", "user_row_id" to 1)
                        "session.active_list" -> json("sessions" to JSONArray().apply {
                            val runtime = params.string("current_session_id")
                            if (runtime == currentRuntimeId) put(json("id" to runtime, "session_key" to storedSessionId, "status" to turnStatus))
                        })
                        else -> JSONObject()
                    }
                    listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "result" to result).toString())
                    return true
                }
            }
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            listener.onMessage(socket, json("jsonrpc" to "2.0", "method" to "event", "params" to json("type" to "gateway.ready", "payload" to json())).toString())
            return socket
        }
    }

    private class GatewayFixture {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val connectionCalls = mutableListOf<MutableList<String>>()
        val serverResponses = mutableListOf<JSONObject>()
        val eventsReady = CompletableDeferred<Unit>()
        var socketCount = 0
        var rejectCapabilities = false
        var requestDuringResume: JSONObject? = null
        var currentSocket: WebSocket? = null
        var currentListener: WebSocketListener? = null
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null,
            WebSocket.Factory { request, listener ->
                socketCount++
                val methods = mutableListOf<String>()
                connectionCalls += methods
                val socket = object : WebSocket {
                    override fun request() = request
                    override fun queueSize() = 0L
                    override fun cancel() = Unit
                    override fun close(code: Int, reason: String?) = true
                    override fun send(bytes: ByteString) = false
                    override fun send(text: String): Boolean {
                        val frame = JSONObject(text)
                        if (!frame.has("method")) {
                            serverResponses += frame.copy()
                            return true
                        }
                        val method = frame.getString("method")
                        val params = frame.getJSONObject("params").copy()
                        calls += method to params
                        methods += method
                        if (method == "client.capabilities" && rejectCapabilities) {
                            listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to json("code" to -32601, "message" to "method not found")).toString())
                            return true
                        }
                        val result = when (method) {
                            "session.create" -> json("session_id" to "phone-runtime", "stored_session_id" to "20261019_001507_a4cbef", "messages" to JSONArray(), "info" to json("lazy" to true))
                            "session.resume" -> json("session_id" to "phone-runtime", "session_key" to "20261019_001507_a4cbef", "messages" to JSONArray(), "info" to json("lazy" to true))
                            "session.events.since" -> json("events" to JSONArray(), "latest_seq" to 0, "epoch" to "fixture-epoch").also { eventsReady.complete(Unit) }
                            "prompt.submit" -> json("status" to "streaming", "user_row_id" to 1)
                            else -> JSONObject()
                        }
                        if (method == "session.resume") requestDuringResume?.also {
                            requestDuringResume = null
                            listener.onMessage(this, it.toString())
                        }
                        listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "result" to result).toString())
                        return true
                    }
                }
                // Like Hermes, prompts keep going to the subscribed stream socket, not to a later per-action socket.
                if (!eventsReady.isCompleted) { currentSocket = socket; currentListener = listener }
                listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
                listener.onMessage(socket, json("jsonrpc" to "2.0", "method" to "event", "params" to json("type" to "gateway.ready", "payload" to json())).toString())
                socket
            }) { builder ->
                builder.addInterceptor { chain ->
                    val request=chain.request()
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                        .body(json("ticket" to "fixture-ticket").toString().toResponseBody("application/json".toMediaType())).build()
                }
            }

        fun emitServerRequest(method: String, id: Any, params: JSONObject) {
            requireNotNull(currentListener).onMessage(requireNotNull(currentSocket),
                json("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params.copy()).toString())
        }

        fun cancelServerRequest(id: Any, method: String, sessionId: String) {
            val event = json("method" to "event", "params" to json("session_id" to sessionId, "type" to "request.cancel",
                "payload" to json("id" to id, "method" to method, "reason" to "timeout")))
            requireNotNull(currentListener).onMessage(requireNotNull(currentSocket), event.toString())
        }

        fun disconnect() = requireNotNull(currentListener).onClosed(requireNotNull(currentSocket), 1001, "fixture reconnect")
    }

    @Test fun newChatFirstPromptUsesTheLiveRuntimeCreatedForThatChat() = runBlocking<Unit> {
        val fixture = GatewayFixture()
        val stored = "20261019_001507_a4cbef"

        val created = fixture.client.post("/api/session/new")
        val accepted = fixture.client.post("/api/chat/start", json("session_id" to stored, "message" to "Hello", "attachments" to JSONArray()))

        assertEquals(stored, created.getString("session_id"))
        assertTrue(accepted.getBoolean("accepted"))
        assertEquals("The creating client keeps one live socket through the first prompt", 1, fixture.socketCount)
        assertEquals(listOf("client.capabilities", "session.create", "session.events.since", "prompt.submit"), fixture.calls.map { it.first })
        assertEquals(true, fixture.calls.first().second.optBoolean("server_requests"))
        assertTrue(fixture.connectionCalls.single().indexOf("client.capabilities") < fixture.connectionCalls.single().indexOf("session.create"))
        assertEquals("phone-runtime", fixture.calls.last().second.getString("session_id"))
        fixture.client.closeNative()
    }

    @Test fun capabilitiesPrecedeEveryConnectionAndMethodNotFoundDoesNotBlockResume() = runBlocking<Unit> {
        val fixture = GatewayFixture().apply { rejectCapabilities = true }
        fixture.client.nativeSession("20261019_001507_a4cbef")
        fixture.disconnect()
        fixture.client.nativeSession("20261019_001507_a4cbef")

        assertEquals(2, fixture.connectionCalls.size)
        fixture.connectionCalls.forEach { methods ->
            assertEquals("client.capabilities", methods.first())
            assertTrue(methods.indexOf("client.capabilities") < methods.indexOf("session.resume"))
        }
        assertEquals(2, fixture.calls.count { it.first == "client.capabilities" })
        assertEquals(2, fixture.calls.count { it.first == "session.resume" })
        fixture.client.closeNative()
    }

    @Test fun requestArrivingBeforeResumeAcknowledgementWaitsForSessionBinding() = runBlocking<Unit> {
        val fixture = GatewayFixture()
        val stored = "20261019_001507_a4cbef"
        fixture.requestDuringResume = json("jsonrpc" to "2.0", "id" to "approval-during-resume", "method" to "approval",
            "params" to json("session_id" to "phone-runtime", "request_id" to "approval-during-resume-queue", "choices" to JSONArray().put("once")))
        val binding = fixture.client.nativeSession(stored)
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        val collector = launch { fixture.client.nativeEvents { if (it.optJSONObject("params")?.optString("type") != "gateway.ready") events.send(it) } }
        try {
            withTimeout(5_000) { fixture.eventsReady.await() }
            assertEquals("approval", withTimeout(5_000) { events.receive() }.string("method"))
            // Like BeaconViewModel.action, the answer comes from a detached client, not the stream's.
            fixture.client.detached().post("/api/approval/respond", json("session_id" to stored, "scope_key" to fixture.client.scope.session(stored).key,
                "approval_id" to "approval-during-resume-queue", "choice" to "once"))
            assertEquals("approval-during-resume", fixture.serverResponses.single().getString("id"))
            assertEquals("phone-runtime", binding.runtimeSessionId)
        } finally {
            collector.cancelAndJoin()
            fixture.client.closeNative()
        }
    }

    @Test fun approvalAndClarifyUseTheOriginalRequestIdsAndHermesResultShapes() = runBlocking<Unit> {
        val fixture = GatewayFixture()
        val stored = "20261019_001507_a4cbef"
        val binding = fixture.client.nativeSession(stored)
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        val collector = launch { fixture.client.nativeEvents { if (it.optJSONObject("params")?.optString("type") != "gateway.ready") events.send(it) } }
        try {
            withTimeout(5_000) { fixture.eventsReady.await() }
            fixture.emitServerRequest("approval", 41, json("session_id" to binding.runtimeSessionId, "request_id" to "approval-queue-41", "choices" to JSONArray().put("once").put("deny")))
            assertEquals("approval", withTimeout(5_000) { events.receive() }.string("method"))
            val otherProfile = fixture.client.forAuthorizedProfile("other")
            assertTrue("Another profile never answers this conversation's prompt", runCatching {
                otherProfile.nativeAnswerServerRequest("approval", "approval-queue-41", binding.runtimeSessionId, json("choice" to "once"))
            }.isFailure)
            assertTrue(fixture.serverResponses.isEmpty())
            // Like BeaconViewModel.action, each answer comes from a detached client, not the stream's.
            val answers = fixture.client.detached()
            answers.post("/api/approval/respond", json("session_id" to stored, "scope_key" to fixture.client.scope.session(stored).key,
                "approval_id" to "approval-queue-41", "choice" to "once"))
            val approval = fixture.serverResponses.single()
            assertEquals(41, approval.optInt("id"))
            assertEquals(setOf("choice"), approval.getJSONObject("result").keys().asSequence().toSet())
            assertEquals("once", approval.getJSONObject("result").getString("choice"))

            fixture.emitServerRequest("approval", "approval-wire-all", json("session_id" to binding.runtimeSessionId, "request_id" to "approval-queue-all", "choices" to JSONArray().put("deny")))
            assertEquals("approval", withTimeout(5_000) { events.receive() }.string("method"))
            answers.post("/api/approval/respond", json("session_id" to stored, "scope_key" to fixture.client.scope.session(stored).key,
                "approval_id" to "approval-queue-all", "choice" to "deny", "all" to true))
            val approvalAll = fixture.serverResponses.last()
            assertEquals("approval-wire-all", approvalAll.getString("id"))
            assertEquals(setOf("choice", "all"), approvalAll.getJSONObject("result").keys().asSequence().toSet())
            assertTrue(approvalAll.getJSONObject("result").getBoolean("all"))

            val questions = JSONArray().put(json("qid" to "route/a", "question" to "Which route?"))
                .put(json("qid" to "notes:b", "question" to "Anything else?"))
            fixture.emitServerRequest("clarify", "clarify-wire-id", json("session_id" to binding.runtimeSessionId, "questions" to questions))
            assertEquals("clarify", withTimeout(5_000) { events.receive() }.string("method"))
            answers.post("/api/clarify/respond", json("session_id" to stored, "scope_key" to fixture.client.scope.session(stored).key,
                "clarify_id" to "clarify-wire-id", "answers" to json("route/a" to "Canary", "notes:b" to "Keep rollback ready")))
            val clarify = fixture.serverResponses.last()
            assertEquals("clarify-wire-id", clarify.getString("id"))
            assertEquals(setOf("answers", "outcome"), clarify.getJSONObject("result").keys().asSequence().toSet())
            assertEquals("submitted", clarify.getJSONObject("result").getString("outcome"))
            assertEquals("Canary", clarify.getJSONObject("result").getJSONObject("answers").getString("route/a"))
            assertEquals("Keep rollback ready", clarify.getJSONObject("result").getJSONObject("answers").getString("notes:b"))
            assertTrue(fixture.calls.none { it.first in setOf("approval.respond", "request.answer", "clarify.lock") })
        } finally {
            collector.cancelAndJoin()
            fixture.client.closeNative()
        }
    }

    @Test fun unsupportedForeignAndCancelledRequestsNeverLeaveAnAnswerablePrompt() = runBlocking<Unit> {
        val fixture = GatewayFixture()
        val stored = "20261019_001507_a4cbef"
        val binding = fixture.client.nativeSession(stored)
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        val collector = launch { fixture.client.nativeEvents { if (it.optJSONObject("params")?.optString("type") != "gateway.ready") events.send(it) } }
        try {
            withTimeout(5_000) { fixture.eventsReady.await() }
            val unsupported = listOf(
                "secret" to -32601, "sudo" to -32601, "display.install.sudo" to -32601,
                "vault.code" to -32601, "vault.save_login" to -32601, "vault.unlock_prompt" to -32601,
                "theme.apply" to -32601, "theme.pick" to -32601, "setup_beat" to -32601,
                "preview.act" to 4404, "preview.read" to 4404, "terminal.read" to 4404,
                "window.read" to 4404, "tour" to 4404, "future.method" to -32601,
            )
            unsupported.forEachIndexed { index, (method, code) ->
                fixture.emitServerRequest(method, "unsupported-$index", json("session_id" to binding.runtimeSessionId))
                assertEquals(code, fixture.serverResponses.last().getJSONObject("error").getInt("code"))
                assertEquals("unsupported-$index", fixture.serverResponses.last().getString("id"))
            }
            fixture.emitServerRequest("clarify", "foreign-clarify", json("session_id" to "another-runtime", "questions" to JSONArray().put(json("qid" to "q", "question" to "No?"))))
            // A prompt waits in the event queue for its session binding, so the collector rejects it after this suspension.
            assertNull(withTimeoutOrNull(50) { events.receive() })
            assertEquals("foreign-clarify", fixture.serverResponses.last().getString("id"))
            assertEquals(-32602, fixture.serverResponses.last().getJSONObject("error").getInt("code"))

            fixture.emitServerRequest("clarify", "cancel-me", json("session_id" to binding.runtimeSessionId, "questions" to JSONArray().put(json("qid" to "q", "question" to "Continue?"))))
            assertEquals("clarify", withTimeout(5_000) { events.receive() }.string("method"))
            fixture.cancelServerRequest("cancel-me", "clarify", binding.runtimeSessionId)
            val before = fixture.serverResponses.size
            assertTrue(runCatching {
                fixture.client.detached().post("/api/clarify/respond", json("session_id" to stored, "scope_key" to fixture.client.scope.session(stored).key,
                    "clarify_id" to "cancel-me", "answers" to json("q" to "late")))
            }.isFailure)
            assertEquals(before, fixture.serverResponses.size)
        } finally {
            collector.cancelAndJoin()
            fixture.client.closeNative()
        }
    }

    @Test fun everyStoredSessionResumeCarriesAndroidSource() = runBlocking<Unit> {
        val fixture = GatewayFixture()
        fixture.client.nativeSession("20261019_001507_a4cbef")

        assertEquals("android", fixture.calls.single { it.first == "session.resume" }.second.getString("source"))
        fixture.client.closeNative()
    }

    @Test fun aThreadIsCreatedWithItsOwnSourceAndOnlyThatSourceIsAccepted() = runBlocking<Unit> {
        val fixture = GatewayFixture()
        assertEquals("android_thread", BEACON_THREAD_SOURCE)
        fixture.client.nativeCreate(json("title" to "Trip planning", "source" to BEACON_THREAD_SOURCE))
        val thread = fixture.calls.single { it.first == "session.create" }.second
        assertEquals(BEACON_THREAD_SOURCE, thread.getString("source"))
        assertEquals("Trip planning", thread.getString("title"))
        fixture.client.closeNative()
        val other = GatewayFixture()
        other.client.nativeCreate(json("source" to "telegram"))
        assertEquals("A phone chat never claims another platform", "android", other.calls.single { it.first == "session.create" }.second.getString("source"))
        other.client.closeNative()
    }

    @Test fun statusProbeResumesLazilySoAGoneConversationIsNeverRecreated() = runBlocking<Unit> {
        val fixture = GatewayFixture()
        runCatching { withTimeout(3_000) { fixture.client.recoveredNativeSessionStatus("20261019_001507_a4cbef") } }
        assertTrue("A probe of a gone conversation must get 4007, not a new ownerless row", fixture.calls.first { it.first == "session.resume" }.second.optBoolean("lazy"))
        fixture.client.closeNative()
    }

    @Test fun unsentShellRecoveryResumesLazilySoHermesNeverMaterializesAnOwnerlessRow() = runBlocking<Unit> {
        val fixture = GatewayFixture()
        fixture.client.nativeSession("20261019_001507_a4cbef", lazy = true)
        assertTrue(fixture.calls.single { it.first == "session.resume" }.second.optBoolean("lazy"))
        fixture.client.closeNative()
        val plain = GatewayFixture()
        plain.client.nativeSession("20261019_001507_a4cbef")
        assertFalse(plain.calls.single { it.first == "session.resume" }.second.has("lazy"))
        plain.client.closeNative()
    }

    @Test fun foregroundStatusUsesResumedRuntimeAfterBackgroundStreamWasCancelled() = runBlocking<Unit> {
        val fixture = RecoveryFixture()
        val created = fixture.client.post("/api/session/new")
        val stored = created.getString("session_id")
        val accepted = fixture.client.post("/api/chat/start", json("session_id" to stored, "message" to "Hello", "attachments" to JSONArray()))
        val originalRuntime = accepted.getString("stream_id")
        val stream = launch { fixture.client.stream("/api/chat/stream", mapOf("session_id" to stored, "stream_id" to originalRuntime), "") {} }
        withTimeout(5_000) { fixture.streamSubscribed.await() }
        stream.cancelAndJoin()

        fixture.currentRuntimeId = "runtime-resumed"
        fixture.turnStatus = "idle"
        val recovery = fixture.client.detached()

        assertNull("A status read against the old socket runtime cannot prove the turn is idle", recovery.nativeSessionStatus(stored, originalRuntime))
        assertEquals("idle", recovery.recoveredNativeSessionStatus(stored))
        assertEquals("The recovered status must be scoped to the runtime bound by session.resume", "runtime-resumed",
            fixture.calls.last { it.first == "session.active_list" }.second.getString("current_session_id"))
        assertEquals("default", fixture.calls.last { it.first == "session.active_list" }.second.getString("profile"))
        val finalReply = Message.from(json("role" to "assistant", "text" to "Background completion", "row_id" to 42), 0, stored)
        val settled = releaseTerminalChat(ChatState(sessionId = stored, streamId = originalRuntime, status = "Working", messages = listOf(finalReply)), originalRuntime, replyConfirmed = true, terminalStatus = "")
        assertEquals("The final projection remains visible", "Background completion", settled.messages.single().text)
        assertEquals("The accepted turn no longer holds Send", "", settled.streamId)
        assertEquals("The working status is cleared", "", settled.status)
    }

    @Test fun synchronousSocketFailureDoesNotRetainAConnection() = runBlocking<Unit> {
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(json("ticket" to "fixture-ticket").toString().toResponseBody("application/json".toMediaType())).build() }
        }
        val gateway = NativeGateway(client, WebSocket.Factory { _, _ -> throw java.io.IOException("Synthetic socket construction failed") })
        repeat(2) {
            assertTrue(runCatching { gateway.rpc("session.list", JSONObject()) }.isFailure)
            val retained = NativeGateway::class.java.getDeclaredField("connection").apply { isAccessible = true }.get(gateway)
            assertNull(retained)
        }
    }

    @Test fun coldCallContextRetriesOnlyTransientReadsOnceAndNeverAuthorizationFailures() = runBlocking<Unit> {
        for ((first, second) in listOf(504 to 200, 503 to 503, 429 to 200, 401 to 200, 403 to 200, 404 to 200)) {
            var requests = 0
            val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default"), null) { builder ->
                builder.addInterceptor { chain ->
                    assertEquals("GET", chain.request().method)
                    assertEquals("/api/plugins/beacon/mobile/calls/owned-call/context", chain.request().url.encodedPath)
                    assertEquals("default", chain.request().url.queryParameter("beacon_profile"))
                    val status = if (requests++ == 0) first else second
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Native")
                        .body(json((if (status == 200) "profile" else "detail") to (if (status == 200) "default" else "Native lookup failed")).toString().toResponseBody("application/json".toMediaType())).build()
                }
            }
            val result = runCatching { client.nativeCallContext("owned-call") }
            val retry = first in setOf(429, 502, 503, 504)
            assertEquals(if (retry) 2 else 1, requests)
            if (retry && second == 200) assertEquals("default", result.getOrThrow().getString("profile"))
            else assertEquals(if (retry) second else first, (result.exceptionOrNull() as ApiFailure).status)
        }
    }

    @Test fun passiveHistoryPreservesIdentityWithoutResumingAndWritesStillUseNativeRpc() = runBlocking<Unit> {
        val stored = "20261014_125947_0d8269"
        val archivePaths = mutableListOf<String>()
        val methods = mutableListOf<String>()
        val httpPaths = mutableListOf<String>()
        var resumes = 0
        var resumeStatus = "idle"
        var fileText="before\r\n"
        var savedText=""
        var nativeWrites=0
        val wire = object : WebSocket.Factory {
          override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun cancel() = Unit
                override fun close(code: Int, reason: String?) = true
                override fun send(bytes: ByteString) = false
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text)
                    val method = frame.getString("method"); methods += method
                    val result = when (method) {
                        "session.create" -> json("session_id" to "android-runtime", "stored_session_id" to stored, "messages" to JSONArray(), "info" to json("lazy" to true))
                        "session.resume" -> { resumes++; json("session_id" to "android-runtime", "session_key" to stored, "resumed" to stored, "messages" to JSONArray(), "status" to resumeStatus, "running" to false, "info" to json("lazy" to false)) }
                        "session.history" -> json("messages" to JSONArray(), "count" to 0)
                        "session.compress" -> { assertEquals("android-runtime",frame.getJSONObject("params").getString("session_id"));json("status" to "aborted", "removed" to 0, "summary" to json("aborted" to true)) }
                        else -> JSONObject()
                    }
                    listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "result" to result).toString())
                    return true
                }
            }
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Native").build())
            listener.onMessage(socket, json("jsonrpc" to "2.0", "method" to "event", "params" to json("type" to "gateway.ready", "payload" to json())).toString())
            return socket
          }
        }
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, wire) { builder ->
            builder.addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                httpPaths += path
                if(path in setOf("/api/mcp/servers","/api/tools/toolsets")) assertEquals("default",chain.request().url.queryParameter("profile"))
                var status=if(path.endsWith("/files/create")) 409 else 200
                fun preview() = json("content" to fileText.replace("\r\n", "\n"), "text" to fileText, "path" to "C:/test/file.txt", "byteSize" to fileText.toByteArray().size, "truncated" to false, "binary" to false, "ok" to true)
                val body = if(path=="/api/plugins/beacon/mobile/files/text") {
                    if (chain.request().method == "POST") {
                        val sink=okio.Buffer();chain.request().body!!.writeTo(sink)
                        val fields = JSONObject(sink.readUtf8())
                        if (fields.getString("expected_content") != fileText.replace("\r\n", "\n")) {
                            status = 409; json("detail" to "File changed. Reopen and review your draft.")
                        } else {
                            nativeWrites++
                            savedText=fields.getString("content")
                            fileText=savedText.replace("\r\n", "\n").replace("\n","\r\n")
                            preview()
                        }
                    } else preview()
                } else if (path.endsWith("/voice-history")) {
                    archivePaths += path
                    json("session_id" to stored, "profile" to "default", "events" to JSONArray())
                } else if (path == "/api/sessions/$stored") {
                    assertEquals("default", chain.request().url.queryParameter("profile"))
                    json("id" to stored, "profile" to "default", "title" to "Synthetic", "message_count" to 0)
                } else if (path == "/api/sessions/$stored/messages") {
                    assertEquals("default", chain.request().url.queryParameter("profile"))
                    json("session_id" to stored, "profile" to "default", "messages" to JSONArray(), "pagination" to json("limit" to 500, "offset" to 0, "order" to "latest", "returned" to 0))
                } else if (path.startsWith("/api/plugins/beacon/mobile/sessions/$stored") && !path.endsWith("/voice-history")) error("Passive history must not use the optional plugin")
                else json("ticket" to "test-ticket")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Native").body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val fresh = client.post("/api/session/new")
        assertEquals(stored, fresh.getString("session_id"))
        repeat(2) {
            val resumed = client.get("/api/session", mapOf("session_id" to stored))
            assertEquals(stored, resumed.getString("session_id"))
            assertEquals(stored, resumed.getString("stored_session_id"))
            // Passive stock history has no live agent, so it never reports a runtime identity it did not open.
            assertTrue(resumed.optString("runtime_session_id").isBlank())
            assertTrue(resumed.getBoolean("voice_transcripts_available"))
        }
        assertEquals(0, resumes)
        val compressed=client.post("/api/session/compress",json("session_id" to stored))
        assertEquals("aborted",compressed.getString("status"))
        client.get("/api/mcp/servers")
        assertEquals("/api/mcp/servers",httpPaths.last())
        client.get("/api/toolsets")
        assertEquals("/api/tools/toolsets",httpPaths.last())

        assertEquals(2, archivePaths.size)
        assertTrue(archivePaths.all { it.endsWith("/$stored/voice-history") })
        val resumesBeforePassiveStatusReads = resumes
        for (status in listOf("starting", "resuming", "idle", "working", "waiting")) {
            resumeStatus = status
            val snapshot = client.get("/api/session", mapOf("session_id" to stored))
            // Stock history cannot observe a running turn without resuming; it must not claim one.
            assertTrue(status, snapshot.optString("active_stream_id").isBlank())
        }
        assertEquals("Reading history in any agent state never adds a resume", resumesBeforePassiveStatusReads, resumes)
        resumeStatus = "idle"
        val preview = client.get("/api/file", mapOf("session_id" to stored, "path" to "C:/test/file.txt"))
        val saved=client.post("/api/file/save",json("session_id" to stored,"path" to "C:/test/file.txt","content" to "after\r\n","expected_content" to preview.getString("content")))
        assertEquals("after\r\n",savedText)
        assertEquals("after\n",saved.getString("content"))
        assertEquals(1,nativeWrites)
        assertTrue(runCatching { client.post("/api/file/save",json("session_id" to stored,"path" to "C:/test/file.txt","content" to "lost","expected_content" to "before\r\n")) }.isFailure)
        assertTrue(runCatching { client.post("/api/file/create",json("session_id" to stored,"path" to "C:/test/file.txt","content" to "overwrite")) }.isFailure)
        assertEquals(1,nativeWrites)
        assertEquals("/api/plugins/beacon/mobile/files/create",httpPaths.last())
        assertEquals(1, methods.count { it == "session.create" })
    }
}
