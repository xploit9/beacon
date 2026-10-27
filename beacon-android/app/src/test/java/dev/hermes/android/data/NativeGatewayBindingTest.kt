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

/** A conversation binding names a runtime that lives on the socket that made it; Hermes reaps the runtime once that socket is gone. */
class NativeGatewayBindingTest {
    private class Wire : WebSocket.Factory {
        val methods = mutableListOf<String>()
        @Volatile var runtime = "runtime-1"
        /** A runtime Hermes dropped: a call naming it answers 4001, as Hermes does for one that is not in memory. */
        @Volatile var reaped: String? = null
        /** Hermes' text for the 4001: "session not found" when the runtime is gone; other 4001s (a session not owned by this transport) keep the binding. */
        @Volatile var refusal = "session not found"
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
                    methods += method
                    val gone = method != "session.resume" && frame.getJSONObject("params").optString("session_id") == reaped
                    val reply = json("jsonrpc" to "2.0", "id" to frame.getString("id"))
                    if (gone) reply.put("error", json("code" to 4001, "message" to refusal))
                    else reply.put("result", when (method) {
                        "session.create" -> json("session_id" to runtime, "stored_session_id" to "stored-a", "messages" to JSONArray(), "info" to JSONObject())
                        "session.resume" -> json("session_id" to runtime, "session_key" to "stored-a", "messages" to JSONArray(), "info" to JSONObject())
                        else -> JSONObject()
                    })
                    listener.onMessage(this, reply.toString())
                    return true
                }
            }
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            return socket
        }
    }

    // Live 2026-10-27: the socket that created a chat closes once its first prompt is accepted, yet a background read reopened the gateway
    // and the old binding looked valid again, so Beacon kept asking a runtime Hermes had dropped.
    @Test(timeout = 15_000) fun aBindingMadeOnAClosedSocketIsNotRevivedWhenAnotherCallReopensTheGateway() = runBlocking {
        val wire = Wire()
        try {
            assertEquals("runtime-1", wire.client.nativeCreate(json("source" to "android")).runtimeSessionId)
            wire.client.closeNative()
            wire.runtime = "runtime-2"
            wire.client.nativeRpc("subagent.list")
            assertEquals("A reopened socket holds no runtime of the closed one", "", wire.client.nativeSessionSnapshot().string("session_id"))
            assertEquals("The stored conversation is resumed instead of the old runtime reused", "runtime-2", wire.client.nativeSession("stored-a").runtimeSessionId)
            assertEquals(1, wire.methods.count { it == "session.resume" })
            assertEquals("runtime-2", wire.client.nativeSessionSnapshot().string("session_id"))
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun aBindingStaysWhileItsSocketIsOpen() = runBlocking {
        val wire = Wire()
        try {
            val created = wire.client.nativeCreate(json("source" to "android"))
            assertEquals(created, wire.client.nativeSession("stored-a"))
            assertEquals("runtime-1", wire.client.nativeSessionSnapshot().string("session_id"))
            assertEquals(0, wire.methods.count { it == "session.resume" })
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun aRuntimeHermesNoLongerHoldsIsForgottenSoNoCallerAddressesItAgain() = runBlocking {
        val wire = Wire()
        try {
            assertEquals("runtime-1", wire.client.nativeSession("stored-a").runtimeSessionId)
            wire.reaped = "runtime-1"
            val failure = runCatching { wire.client.nativeRpc("process.list", json("session_id" to "runtime-1")) }.exceptionOrNull()
            assertEquals(4001, (failure as ApiFailure).status)
            assertEquals("", wire.client.nativeSessionSnapshot().string("session_id"))
            wire.reaped = null
            wire.runtime = "runtime-2"
            assertEquals("The next use resumes the stored conversation", "runtime-2", wire.client.nativeSession("stored-a").runtimeSessionId)
            assertEquals(2, wire.methods.count { it == "session.resume" })
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun aNotOwnedRefusalKeepsTheBinding() = runBlocking {
        val wire = Wire()
        try {
            assertEquals("runtime-1", wire.client.nativeSession("stored-a").runtimeSessionId)
            wire.reaped = "runtime-1"; wire.refusal = "session not found or not owned by this transport"
            val failure = runCatching { wire.client.nativeRpc("subagent.list", json("session_id" to "runtime-1")) }.exceptionOrNull()
            assertEquals(4001, (failure as ApiFailure).status)
            assertEquals("Only Hermes' runtime-gone answer drops the binding", "runtime-1", wire.client.nativeSessionSnapshot().string("session_id"))
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun aRefusalNamingAnotherRuntimeKeepsTheBinding() = runBlocking {
        val wire = Wire()
        try {
            wire.client.nativeSession("stored-a")
            wire.reaped = "runtime-elsewhere"
            assertTrue(runCatching { wire.client.nativeRpc("process.list", json("session_id" to "runtime-elsewhere")) }.isFailure)
            assertEquals("runtime-1", wire.client.nativeSessionSnapshot().string("session_id"))
        } finally { wire.client.closeNative() }
    }
}
