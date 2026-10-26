package dev.hermes.android.voice

import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.data.defaultVoiceModel
import dev.hermes.android.data.defaultVoiceName
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

class VoiceStallRecoveryTest {
    /** A provider socket that accepts everything and answers only setup, like a link that stopped delivering replies. */
    private class Provider {
        val sockets = CopyOnWriteArrayList<Pair<WebSocket, WebSocketListener>>()
        val frames = CopyOnWriteArrayList<Pair<Int, JSONObject>>()
        @Volatile var queued = 0L
        fun sent(index: Int) = frames.filter { it.first == index }.map { it.second }
        val factory = WebSocket.Factory { request, listener ->
            val index = sockets.size + 1
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = queued
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text); frames += index to frame
                    if (frame.has("setup")) listener.onMessage(this, json("setupComplete" to json()).toString())
                    else if (frame.optString("type") == "session.update") listener.onMessage(this, json("type" to "session.updated").toString())
                    return true
                }
            }
            sockets += socket to listener
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            socket
        }
    }

    private fun credentials(provider: String) = VoiceSessionCredentials(provider, "fixture-provider-key", defaultVoiceModel(provider), defaultVoiceName(provider), "balanced")

    private fun client(reads: AtomicInteger = AtomicInteger()) =
        BeaconClient(AuthContext("https://voice-fixture.invalid", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                val body = when {
                    path.endsWith("/context") -> json("profile" to "default", "selected_session_id" to "stored", "capabilities" to json("memory_recall" to true))
                    path.endsWith("/memory") -> { reads.incrementAndGet(); json("summary" to "Verified owned memory", "observed_at" to "2026-10-23", "sources" to JSONArray()) }
                    else -> json("jobs" to JSONArray())
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }

    private val speech = ByteArray(640).also { for (i in it.indices step 2) { it[i] = (8000 and 255).toByte(); it[i + 1] = (8000 shr 8).toByte() } }
    // Real-time waits: generous bounds so a loaded test machine does not fail them; a real hang still does.
    private suspend fun await(predicate: () -> Boolean) = withTimeout(30_000) { while (!predicate()) delay(10) }
    private suspend fun DirectVoice.talk() = repeat(80) { pcm(speech) { true }; delay(20) }
    private fun reply(wire: String) =
        if (wire == "gemini") json("serverContent" to json("turnComplete" to true))
        else json("type" to "response.done", "response" to json("status" to "completed", "output" to JSONArray()))

    @Test(timeout = 120000) fun anUnansweredToolResultResumesOnceThenStartsFreshThenSaysSoPlainly() = runBlocking<Unit> {
        val provider = Provider(); val reads = AtomicInteger(); val events = CopyOnWriteArrayList<JSONObject>(); val failed = CompletableDeferred<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = client(reads)
        val voice = DirectVoice(client, "call", "stored", scope, {}, { events += it }, { failed.complete(it) }, credentials = credentials("gemini"), providerSocketFactory = provider.factory, stallAfterMs = 500)
        try {
            voice.start()
            await { events.any { it.optString("state") == "listening" } }
            val (first, listener) = provider.sockets.first()
            listener.onMessage(first, json("sessionResumptionUpdate" to json("resumable" to true, "newHandle" to "owned-resume")).toString())
            listener.onMessage(first, json("toolCall" to json("functionCalls" to JSONArray().put(json("id" to "recall-1", "name" to "hermes_memory", "args" to json("query" to "owned memory"))))).toString())
            await { provider.sent(1).any { it.has("toolResponse") } }
            // The result went into a socket that never answers: the session is replaced and resumed once.
            await { provider.sent(2).any { it.has("setup") } }
            assertEquals("owned-resume", provider.sent(2).first { it.has("setup") }.getJSONObject("setup").getJSONObject("sessionResumption").optString("handle"))
            // What the model was owed is said again as reference text, never as a second tool run.
            await { provider.sent(2).any { it.toString().contains("Verified earlier hermes_memory tool outcome") } }
            assertEquals(1, provider.sent(2).count { it.toString().contains("Verified earlier hermes_memory tool outcome") })
            assertFalse(provider.sent(2).any { it.has("toolResponse") })
            // Still unanswered: the next session starts fresh from the call transcript instead of resuming the same handle.
            await { provider.sent(3).any { it.has("setup") } }
            assertFalse(provider.sent(3).first { it.has("setup") }.getJSONObject("setup").getJSONObject("sessionResumption").has("handle"))
            assertEquals("Voice stopped answering and couldn't recover. Try calling again.", withTimeout(30_000) { failed.await() })
            assertEquals("Recovery never repeats the lookup", 1, reads.get())
            // The plugin accepts only known diagnostic stages and codes.
            assertTrue(events.any { it.optString("type") == "media_diagnostic" && it.optString("stage") == "provider_error" && it.optString("error_code") == "timeout" && it.optString("cause_class") == "socket_timeout" })
        } finally { voice.close(); scope.cancel(); client.closeNative() }
    }

    @Test(timeout = 120000) fun speechTheProviderNeverAnswersReplacesTheSessionOnEveryWire() = runBlocking<Unit> {
        for (wire in listOf("gemini", "grok", "openai")) {
            val provider = Provider(); val events = CopyOnWriteArrayList<JSONObject>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client = client()
            val voice = DirectVoice(client, "call", "stored", scope, {}, { events += it }, {}, credentials = credentials(wire), providerSocketFactory = provider.factory, stallAfterMs = 400)
            try {
                voice.start()
                await { events.any { it.optString("state") == "listening" } }
                voice.talk()
                await { provider.sockets.size >= 2 && provider.sent(2).any { it.has("setup") || it.optString("type") == "session.update" } }
                assertFalse("$wire: the greeting is not repeated", provider.sent(2).any { it.toString().contains("Open naturally") })
            } finally { voice.close(); scope.cancel(); client.closeNative() }
        }
    }

    @Test(timeout = 120000) fun plainSilenceOrAnyModelReplyNeverReplacesTheSession() = runBlocking<Unit> {
        for (wire in listOf("gemini", "grok", "openai")) {
            val provider = Provider(); val events = CopyOnWriteArrayList<JSONObject>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client = client()
            val voice = DirectVoice(client, "call", "stored", scope, {}, { events += it }, {}, credentials = credentials(wire), providerSocketFactory = provider.factory, stallAfterMs = 300)
            try {
                voice.start()
                await { events.any { it.optString("state") == "listening" } }
                delay(1200)
                assertEquals("$wire: a quiet call is healthy", 1, provider.sockets.size)
                val (first, listener) = provider.sockets.first()
                voice.talk()
                listener.onMessage(first, reply(wire).toString())
                delay(1200)
                assertEquals("$wire: an answered turn is healthy", 1, provider.sockets.size)
            } finally { voice.close(); scope.cancel(); client.closeNative() }
        }
    }

    @Test(timeout = 120000) fun videoOnASlowLinkIsDroppedAndPacedWhileAudioAndControlsStillFlow() = runBlocking<Unit> {
        val provider = Provider(); val events = CopyOnWriteArrayList<JSONObject>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = client()
        val voice = DirectVoice(client, "call", "stored", scope, {}, { events += it }, {}, credentials = credentials("gemini"), providerSocketFactory = provider.factory)
        fun realtime(key: String) = provider.sent(1).any { it.optJSONObject("realtimeInput")?.has(key) == true }
        try {
            voice.start()
            await { events.any { it.optString("state") == "listening" } }
            voice.setVisualState("camera", "starting")
            val frame = ByteArray(30_000)
            provider.queued = 40_000
            assertFalse("A socket that is behind takes no video", voice.image(frame, { true }))
            voice.pcm(speech) { true }
            assertTrue(voice.send(GeminiWire.endAudio()))
            await { realtime("audio") && realtime("audioStreamEnd") }
            assertFalse(realtime("video"))
            provider.queued = 0
            assertTrue(voice.image(frame, { true }))
            await { realtime("video") }
            delay(100)
            assertFalse("The next frame waits for the last one to drain", voice.image(frame, { true }))
            delay(900)
            assertTrue(voice.image(frame, { true }))
        } finally { voice.close(); scope.cancel(); client.closeNative() }
    }
}
