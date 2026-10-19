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
        val client = CypherClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, this) { builder ->
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
            return socket
        }
    }

    private class GatewayFixture {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        var socketCount = 0
        val client = CypherClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null,
            WebSocket.Factory { request, listener ->
                socketCount++
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
                            "session.create" -> json("session_id" to "phone-runtime", "stored_session_id" to "20261019_001507_a4cbef", "messages" to JSONArray(), "info" to json("lazy" to true))
                            "session.resume" -> json("session_id" to "phone-runtime", "session_key" to "20261019_001507_a4cbef", "messages" to JSONArray(), "info" to json("lazy" to true))
                            "session.events.since" -> json("events" to JSONArray(), "latest_seq" to 0, "epoch" to "fixture-epoch")
                            "prompt.submit" -> json("status" to "streaming", "user_row_id" to 1)
                            else -> JSONObject()
                        }
                        listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "result" to result).toString())
                        return true
                    }
                }
                listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
                socket
            }) { builder ->
                builder.addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(json("ticket" to "fixture-ticket").toString().toResponseBody("application/json".toMediaType())).build() }
            }
    }

    @Test fun newChatFirstPromptUsesTheLiveRuntimeCreatedForThatChat() = runBlocking<Unit> {
        val fixture = GatewayFixture()
        val stored = "20261019_001507_a4cbef"

        val created = fixture.client.post("/api/session/new")
        val accepted = fixture.client.post("/api/chat/start", json("session_id" to stored, "message" to "Hello", "attachments" to JSONArray()))

        assertEquals(stored, created.getString("session_id"))
        assertTrue(accepted.getBoolean("accepted"))
        assertEquals("The creating client keeps one live socket through the first prompt", 1, fixture.socketCount)
        assertEquals(listOf("session.create", "session.events.since", "prompt.submit"), fixture.calls.map { it.first })
        assertEquals("phone-runtime", fixture.calls.last().second.getString("session_id"))
        fixture.client.closeNative()
    }

    @Test fun everyStoredSessionResumeCarriesAndroidSource() = runBlocking<Unit> {
        val fixture = GatewayFixture()
        fixture.client.nativeSession("20261019_001507_a4cbef")

        assertEquals("android", fixture.calls.single { it.first == "session.resume" }.second.getString("source"))
        fixture.client.closeNative()
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
        val client = CypherClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
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
            val client = CypherClient(AuthContext("https://native.example", emptyList(), "owner", "default"), null) { builder ->
                builder.addInterceptor { chain ->
                    assertEquals("GET", chain.request().method)
                    assertEquals("/api/plugins/cypher/mobile/calls/owned-call/context", chain.request().url.encodedPath)
                    assertEquals("default", chain.request().url.queryParameter("cypher_profile"))
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
            return socket
          }
        }
        val client = CypherClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, wire) { builder ->
            builder.addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                httpPaths += path
                if(path in setOf("/api/mcp/servers","/api/tools/toolsets")) assertEquals("default",chain.request().url.queryParameter("profile"))
                val status=if(path.endsWith("/files/create")) 409 else 200
                val body = if(path=="/api/fs/read-text") json("text" to fileText,"path" to "C:/test/file.txt","byteSize" to fileText.toByteArray().size,"truncated" to false,"binary" to false)
                else if(path=="/api/fs/write-text") {
                    nativeWrites++
                    val sink=okio.Buffer();chain.request().body!!.writeTo(sink)
                    savedText=JSONObject(sink.readUtf8()).getString("content")
                    fileText=savedText.replace("\n","\r\n")
                    json("ok" to true)
                } else if (path.endsWith("/voice-history")) {
                    archivePaths += path
                    json("session_id" to stored, "profile" to "default", "events" to JSONArray())
                } else if (path == "/api/sessions/$stored") {
                    assertEquals("default", chain.request().url.queryParameter("profile"))
                    json("id" to stored, "profile" to "default", "title" to "Synthetic", "message_count" to 0)
                } else if (path == "/api/sessions/$stored/messages") {
                    assertEquals("default", chain.request().url.queryParameter("profile"))
                    json("session_id" to stored, "profile" to "default", "messages" to JSONArray(), "pagination" to json("limit" to 500, "offset" to 0, "order" to "latest", "returned" to 0))
                } else if (path.startsWith("/api/plugins/cypher/mobile/sessions/$stored") && !path.endsWith("/voice-history")) error("Passive history must not use the optional plugin")
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
        val saved=client.post("/api/file/save",json("session_id" to stored,"path" to "C:/test/file.txt","content" to "after\r\n","expected_content" to "before\r\n"))
        assertEquals("after\n",savedText)
        assertEquals("after\r\n",saved.getString("content"))
        assertEquals(1,nativeWrites)
        assertTrue(runCatching { client.post("/api/file/save",json("session_id" to stored,"path" to "C:/test/file.txt","content" to "lost","expected_content" to "before\r\n")) }.isFailure)
        assertTrue(runCatching { client.post("/api/file/create",json("session_id" to stored,"path" to "C:/test/file.txt","content" to "overwrite")) }.isFailure)
        assertEquals(1,nativeWrites)
        assertEquals("/api/plugins/cypher/mobile/files/create",httpPaths.last())
        assertEquals(1, methods.count { it == "session.create" })
    }
}
