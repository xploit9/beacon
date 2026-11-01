package dev.hermes.android.uiux.voicefast

import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.data.defaultVoiceModel
import dev.hermes.android.data.defaultVoiceName
import dev.hermes.android.voice.DirectVoice
import dev.hermes.android.voice.OpeningPrefetch
import dev.hermes.android.voice.SpeechHttpFixture
import dev.hermes.android.voice.callGrantGreeting
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

/** Call start: context beside the provider socket, honest thinking state, one tap clock and the grant greeting prefetch. */
class VoiceFastStartTest {
    private val credentials = VoiceSessionCredentials("gemini", "fixture-provider-key", defaultVoiceModel("gemini"), defaultVoiceName("gemini"), "balanced")

    private inner class Call(greeting: String = "", private val holdSetup: Boolean = false, failContext: Boolean = false,
        val tts: SpeechHttpFixture = SpeechHttpFixture(), opening: OpeningPrefetch? = null) {
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
                val body = if (context) json("profile" to "default", "selected_session_id" to "stored").apply { if (greeting.isNotEmpty()) put("greeting", greeting) }
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
                credentials = credentials, providerSocketFactory = provider, providerCallFactory = tts, opening = opening, clock = { 51_000L }, tappedAt = 50_000L)
        }
        fun completeSetup() { val (socket, listener) = inputs.last(); listener.onMessage(socket, json("setupComplete" to json()).toString()) }
        fun receive(frame: JSONObject) { val (socket, listener) = inputs.last(); listener.onMessage(socket, frame.toString()) }
        fun states() = events.filter { it.optString("type") == "state" }.map { it.getString("state") }
        fun stage(name: String) = events.filter { it.optString("type") == "media_diagnostic" && it.optString("stage") == name }
        fun captions(text: String) = events.count { it.optString("type") == "transcript" && it.optString("text") == text }
        fun close() { voice.close(); scope.cancel(); client.closeNative() }
    }

    private suspend fun waitFor(predicate: () -> Boolean): Boolean = withTimeoutOrNull(3000) {
        while (!predicate()) delay(10)
        true
    } == true

    @Test(timeout = 10000) fun greetingShowsThinkingUntilItsFirstAudioAndReportsPlayoutOnceOnTheTapClock() = runBlocking {
        val c = Call(greeting = "Good evening, Maya.")
        try {
            c.voice.start()
            assertTrue(waitFor { "thinking" in c.states() })
            assertEquals("Admission opens the microphone, then the pending greeting shows as thinking", listOf("listening", "thinking"), c.states())
            assertEquals(1, c.stage("provider_ready").size)
            assertTrue("The greeting render starts from the context, before admission", waitFor { c.tts.requests.size == 1 })
            assertEquals("Good evening, Maya.", c.tts.text(0))
            assertTrue(c.stage("playout_started").isEmpty())
            c.tts.respond()
            assertTrue(waitFor { c.stage("playout_stopped").size == 1 })
            assertEquals("First presented audio returns the call to listening", listOf("listening", "thinking", "listening"), c.states())
            assertEquals(1, c.stage("playout_started").size)
            assertTrue(c.events.indexOf(c.stage("playout_started").single()) < c.events.indexOf(c.stage("playout_stopped").single()))
            assertEquals(1, c.captions("Good evening, Maya."))
            assertEquals("One render for the greeting", 1, c.tts.requests.size)
            val timed = c.events.filter { it.optString("type") == "media_diagnostic" }
            assertTrue(timed.map { it.optString("stage") }.containsAll(listOf("voice_connecting", "context_ready", "provider_ready", "playout_started", "playout_stopped")))
            assertTrue("Every phase, speech included, shares the tap clock", timed.all { it.getLong("elapsed_ms") == 1000L })
            assertTrue(timed.any { it.optString("turn_id") == "speech-ready" })
            assertFalse("Diagnostics stay content-free", timed.toString().contains("Maya"))
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun aFailedGreetingReturnsToListeningWithoutPlayoutEvents() = runBlocking {
        val c = Call(greeting = "Good evening, Maya.")
        try {
            c.voice.start()
            assertTrue(waitFor { "thinking" in c.states() && c.tts.requests.size == 1 })
            c.tts.respond(json("error" to json("code" to 400)), 400)
            assertTrue(waitFor { c.states().last() == "listening" && c.states().size == 3 })
            assertTrue(c.events.any { it.optString("message").contains("speech session could not start") })
            assertTrue(c.stage("playout_started").isEmpty() && c.stage("playout_stopped").isEmpty())
            assertTrue(c.audio.isEmpty())
            assertFalse(c.failure.isCompleted)
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun ownerSpeechDuringThinkingInterruptsTheGreeting() = runBlocking {
        val c = Call(greeting = "Good evening, Maya.")
        try {
            c.voice.start()
            assertTrue(waitFor { "thinking" in c.states() && c.tts.requests.size == 1 })
            c.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            assertTrue("The service maps this to listening", waitFor { c.events.any { it.optString("type") == "interrupted" } })
            assertTrue(waitFor { c.tts.requests[0].canceled })
            c.tts.respond(index = 0); delay(150)
            assertTrue(c.audio.isEmpty())
            assertTrue(c.stage("playout_started").isEmpty())
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun contextFailureEndsTheCallWhileTheProviderHandshakeIsStillPending() = runBlocking {
        val c = Call(holdSetup = true, failContext = true)
        try {
            val started = System.nanoTime()
            c.voice.start()
            assertEquals("Call context unavailable; reconnect to Hermes and retry", withTimeout(3000) { c.failure.await() })
            assertTrue("The failure does not wait for the provider", (System.nanoTime() - started) / 1_000_000 < 3000)
            assertEquals("The socket opened beside the context read", 1, c.inputs.size)
            assertTrue(c.states().isEmpty())
            assertFalse(c.voice.pcm(ByteArray(640)) { true })
            assertTrue(c.tts.requests.isEmpty())
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun grantGreetingRendersBeforeTheCallAndIsPresentedOnlyOnceReady() = runBlocking {
        val tts = SpeechHttpFixture()
        val early = OpeningPrefetch(credentials, "Good evening, Maya.", { 300L }, http = tts)
        assertTrue(waitFor { tts.requests.size == 1 })
        tts.respond()
        val c = Call(greeting = "Good evening, Maya.", holdSetup = true, tts = tts, opening = early)
        try {
            c.voice.start()
            assertTrue(waitFor { c.contexts.get() == 1 })
            delay(200)
            assertTrue("A finished render waits for the provider's setup", c.audio.isEmpty())
            assertTrue(c.states().isEmpty())
            c.completeSetup()
            assertTrue(waitFor { c.captions("Good evening, Maya.") == 1 })
            assertEquals("The grant render is reused for the same text", 1, tts.requests.size)
            assertTrue(waitFor { c.stage("playout_stopped").size == 1 })
            assertTrue("Its diagnostics join the call timeline", c.events.any { it.optString("turn_id") == "speech-ready" && it.getLong("elapsed_ms") == 300L })
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun aDifferentContextGreetingReplacesTheGrantRender() = runBlocking {
        val tts = SpeechHttpFixture()
        val early = OpeningPrefetch(credentials, "Good evening, Maya.", { 0L }, http = tts)
        assertTrue(waitFor { tts.requests.size == 1 })
        val c = Call(greeting = "Good morning, Maya.", tts = tts, opening = early)
        try {
            c.voice.start()
            assertTrue("The authoritative context text renders again", waitFor { tts.requests.size == 2 })
            assertEquals("Good morning, Maya.", tts.text(1))
            assertTrue("The grant render is discarded", waitFor { tts.requests[0].canceled })
            tts.respond(index = 0); delay(150)
            assertTrue(c.audio.isEmpty())
            tts.respond(index = 1)
            assertTrue(waitFor { c.captions("Good morning, Maya.") == 1 })
            assertEquals(0, c.captions("Good evening, Maya."))
            assertEquals(1, c.audio.size)
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun aDroppedGrantRenderIsCancelledAndNeverAdopted() = runBlocking {
        val tts = SpeechHttpFixture()
        val early = OpeningPrefetch(credentials, "Good evening, Maya.", { 0L }, http = tts)
        assertTrue(waitFor { tts.requests.size == 1 })
        early.drop()
        assertTrue(waitFor { tts.requests[0].canceled })
        val c = Call(greeting = "Good evening, Maya.", tts = tts, opening = early)
        try {
            c.voice.start()
            assertTrue("The call renders its own greeting", waitFor { tts.requests.size == 2 })
            tts.respond(index = 1)
            assertTrue(waitFor { c.captions("Good evening, Maya.") == 1 })
        } finally { c.close() }
    }

    @Test fun grantGreetingIsOptionalBoundedText() {
        assertEquals("Good evening, Maya.", callGrantGreeting(json("greeting" to "  Good evening, Maya. ")))
        assertEquals("", callGrantGreeting(json("call_id" to "call")))
        assertEquals("", callGrantGreeting(json("greeting" to " ")))
        assertEquals("", callGrantGreeting(json("greeting" to 42)))
        assertEquals("", callGrantGreeting(json("greeting" to "x".repeat(161))))
        assertEquals(160, callGrantGreeting(json("greeting" to "x".repeat(160))).length)
    }
}
