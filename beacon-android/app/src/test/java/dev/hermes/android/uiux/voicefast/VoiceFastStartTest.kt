package dev.hermes.android.uiux.voicefast

import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.data.defaultVoiceModel
import dev.hermes.android.data.defaultVoiceName
import dev.hermes.android.voice.DirectVoice
import dev.hermes.android.voice.CALL_HERMES_NO_ANSWER
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Call start: the context read runs beside the provider socket, and its failure ends the call without waiting for the provider. */
class VoiceFastStartTest {
    private val credentials = VoiceSessionCredentials("gemini", "fixture-provider-key", defaultVoiceModel("gemini"), defaultVoiceName("gemini"), "balanced")

    private inner class Call(private val holdSetup: Boolean = false, failContext: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = CopyOnWriteArrayList<JSONObject>()
        val audio = CopyOnWriteArrayList<ByteArray>()
        val failure = CompletableDeferred<String>()
        val contexts = AtomicInteger()
        val inputs = CopyOnWriteArrayList<Pair<WebSocket, WebSocketListener>>()
        val client = BeaconClient(AuthContext("https://voice-fixture.invalid", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val context = request.url.encodedPath.endsWith("/context")
                if (context) { contexts.incrementAndGet(); if (failContext) throw java.io.IOException("Synthetic context outage") }
                val body = if (context) json("profile" to "default", "selected_session_id" to "stored")
                    else json("jobs" to JSONArray())
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        private val provider = WebSocket.Factory { request, listener ->
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
                override fun send(text: String): Boolean {
                    if (JSONObject(text).has("setup") && !holdSetup) listener.onMessage(this, json("setupComplete" to json()).toString())
                    return true
                }
            }
            inputs += socket to listener
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            socket
        }
        lateinit var voice: DirectVoice
        init {
            // The clock stands still one second after the tap, so every diagnostic must read 1000 ms.
            voice = DirectVoice(client, "call", "stored", scope, { bytes -> audio += bytes; voice.played(bytes.size) }, { events += it }, { failure.complete(it) },
                credentials = credentials, providerSocketFactory = provider, clock = { 51_000L }, tappedAt = 50_000L)
        }
        fun receive(frame: JSONObject) { val (socket, listener) = inputs.last(); listener.onMessage(socket, frame.toString()) }
        fun states() = events.filter { it.optString("type") == "state" }.map { it.getString("state") }
        fun stage(name: String) = events.filter { it.optString("type") == "media_diagnostic" && it.optString("stage") == name }
        fun close() { voice.close(); scope.cancel(); client.closeNative() }
    }

    private suspend fun waitFor(predicate: () -> Boolean): Boolean = withTimeoutOrNull(3000) {
        while (!predicate()) delay(10)
        true
    } == true

    @Test(timeout = 10000) fun contextFailureEndsTheCallWhileTheProviderHandshakeIsStillPending() = runBlocking {
        val c = Call(holdSetup = true, failContext = true)
        try {
            val started = System.nanoTime()
            c.voice.start()
            assertEquals(CALL_HERMES_NO_ANSWER, withTimeout(3000) { c.failure.await() })
            assertTrue("The failure does not wait for the provider", (System.nanoTime() - started) / 1_000_000 < 3000)
            assertEquals("The socket opened beside the context read", 1, c.inputs.size)
            assertTrue(c.states().isEmpty())
            assertFalse(c.voice.pcm(ByteArray(640)) { true })
        } finally { c.close() }
    }
}
