package dev.hermes.android.data

import dev.hermes.android.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeGatewayTest {
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
                    assertEquals("default", chain.request().url.queryParameter("profile"))
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
                } else if (path == "/api/plugins/cypher/mobile/sessions/$stored") json("session_id" to stored, "profile" to "default", "runtime_session_id" to "android-runtime", "active_stream_id" to if(resumeStatus in setOf("working", "waiting")) "android-runtime" else "", "messages" to JSONArray())
                else json("ticket" to "test-ticket")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Native").body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val fresh = client.post("/api/session/new")
        assertEquals(stored, fresh.getString("session_id"))
        repeat(2) {
            val resumed = client.get("/api/session", mapOf("session_id" to stored))
            assertEquals(stored, resumed.getString("session_id"))
            assertEquals("android-runtime", resumed.getString("runtime_session_id"))
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
        for (status in listOf("starting", "resuming", "idle", "working", "waiting")) {
            resumeStatus = status
            val snapshot = client.get("/api/session", mapOf("session_id" to stored))
            assertEquals(status, status in setOf("working", "waiting"), snapshot.optString("active_stream_id").isNotBlank())
        }
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
