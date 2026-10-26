package dev.hermes.android.data

import dev.hermes.android.core.json
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** A chat's project, as Hermes files it by folder. Every response is a SYNTHETIC FIXTURE: HTTP by an interceptor, RPC by a fake socket. */
class NativeProjectsTest {
    private class Fake(val session: Pair<Int, String>, val rpc: (String, JSONObject) -> JSONObject) : WebSocket.Factory {
        val methods = CopyOnWriteArrayList<String>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text)
                    val method = frame.getString("method"); methods += method
                    val result = if (method == "client.capabilities") json() else rpc(method, frame.optJSONObject("params") ?: json())
                    listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "result" to result).toString())
                    return true
                }
            }
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            return socket
        }
    }

    private fun client(fake: Fake) = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "work", backend = "hermes-dashboard"), null, fake) { builder ->
        builder.addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val (code, body) = when {
                path == "/api/auth/ws-ticket" -> 200 to json("ticket" to "fixture-ticket").toString()
                path.startsWith("/api/sessions/") -> fake.session
                path == "/api/files" -> 200 to json("path" to chain.request().url.queryParameter("path")).toString()
                else -> 404 to json("detail" to "Not Found").toString()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun forSession(code: Int, body: String = json("detail" to "x").toString()): Result<JSONObject> {
        val fake = Fake(code to body) { _, _ -> json("project" to JSONObject.NULL) }
        val c = client(fake)
        return try { runCatching { runBlocking { NativeProjects(c).call("/api/projects/for-session", "GET", json("chat" to "chat-1")) } } } finally { c.closeNative() }
    }

    @Test fun aChatHermesHasNotSavedIsInNoProject() {
        assertTrue(forSession(404).getOrThrow().isNull("project"))
        // Only "not saved yet" means no project: a sign-in end or a server fault still fails the read.
        assertEquals(401, (forSession(401).exceptionOrNull() as? ApiFailure)?.status)
        assertEquals(500, (forSession(500).exceptionOrNull() as? ApiFailure)?.status)
    }

    @Test fun aFolderAnotherProjectHasIsRefusedBeforeItIsLinked() {
        val other = json("id" to "p_other", "name" to "Other", "folders" to JSONArray().put(json("path" to "C:/work/shared")))
        val fake = Fake(200 to json("id" to "chat-1", "cwd" to "C:/home").toString()) { method, _ ->
            when (method) {
                "projects.get" -> json("project" to json("id" to "p_new", "name" to "New", "folders" to JSONArray()))
                "projects.for_cwd" -> json("project" to other)
                else -> json()
            }
        }
        val c = client(fake)
        try {
            val error = runCatching { runBlocking {
                NativeProjects(c).call("/api/projects/assign", "POST", json("id" to "p_new", "session_id" to "chat-1", "scope_key" to c.scope.session("chat-1").key, "path" to "C:/work/shared", "link" to true))
            } }.exceptionOrNull()
            assertEquals("That folder already belongs to another project.", error?.message)
            assertFalse("Nothing is linked when the folder is taken", "projects.add_folder" in fake.methods)
        } finally { c.closeNative() }
    }
}
