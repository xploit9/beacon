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

class NativeOperationsTest {
    /** A Hermes whose chat has no agent until `approval.pending` starts it, like a lazily resumed runtime. */
    private class Fixture(val refuseBuild: Boolean = false) : WebSocket.Factory {
        val methods = mutableListOf<String>()
        var agentBuilt = false
        var builtAtWrite: Boolean? = null
        var model = "old-model"
        var yolo = true
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
                    val method = frame.getString("method")
                    methods += method
                    val params = frame.getJSONObject("params")
                    val result = when (method) {
                        "session.resume" -> json("session_id" to "runtime-a", "session_key" to "stored-a", "message_count" to 0, "messages" to JSONArray(),
                            // Hermes reports the chat's YOLO state only from a built agent.
                            "info" to json("model" to model, "provider" to "fixture", "lazy" to !agentBuilt).apply { if (agentBuilt) put("yolo", yolo) })
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
                        else -> JSONObject()
                    }
                    listener.onMessage(this, reply(frame.getString("id"), result))
                    return true
                }
            }
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            return socket
        }
    }

    private suspend fun pick(fixture: Fixture) =
        fixture.client.post("/api/session/update", json("session_id" to "stored-a", "model" to "picked-model", "model_provider" to "fixture")).getJSONObject("session")

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
