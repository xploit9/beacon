package dev.hermes.android.uiux.voiceinstant

import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.data.defaultVoiceModel
import dev.hermes.android.data.defaultVoiceName
import dev.hermes.android.voice.CallGreeting
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.DirectVoice
import dev.hermes.android.voice.EarlyGreeting
import dev.hermes.android.voice.GreetingOutput
import dev.hermes.android.voice.SpeechHttpFixture
import dev.hermes.android.voice.callStatusLabel
import dev.hermes.android.voice.chipLabel
import dev.hermes.android.voice.withGreetingLevel
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
import java.util.concurrent.atomic.AtomicLong

/** The cached greeting heard from the tap: the call's only greeting, honest states around it, and every way it can end. */
class VoiceInstantStartTest {
    private val credentials = VoiceSessionCredentials("gemini", "fixture-provider-key", defaultVoiceModel("gemini"), defaultVoiceName("gemini"), "balanced")
    private val line = "Good evening, Maya. What can I do for you?"
    // One second of speech at 24 kHz.
    private val speech = ByteArray(24000 * 2).also { for (i in it.indices step 2) { it[i] = 0x10; it[i + 1] = 0x20 } }

    /** A speaker whose presented frames the test moves. */
    private class Speaker(private val refuse: Boolean = false) : GreetingOutput {
        val started = CopyOnWriteArrayList<ByteArray>()
        val frames = AtomicLong(0)
        @Volatile var released = false
        override fun start(pcm: ByteArray): Boolean { if (refuse) return false; started += pcm; return true }
        override fun presented() = frames.get()
        override fun release() { released = true }
    }

    private inner class Call(val speaker: Speaker = Speaker(), cached: Boolean = true, private val holdSetup: Boolean = false, failContext: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = CopyOnWriteArrayList<JSONObject>()
        val audio = CopyOnWriteArrayList<ByteArray>()
        val levels = CopyOnWriteArrayList<Float>()
        val observed = CopyOnWriteArrayList<String>()
        val stored = CopyOnWriteArrayList<Pair<String, Int>>()
        val failure = CompletableDeferred<String>()
        val contexts = AtomicInteger()
        val tts = SpeechHttpFixture()
        val inputs = CopyOnWriteArrayList<Pair<WebSocket, WebSocketListener>>()
        // The tap clock reads 120 ms when the cached line's first frame is heard.
        val early = EarlyGreeting({ 120L }, { _, level -> levels += level }).also { it.begin(scope, { speaker }) { if (cached) line to speech else null } }
        val client = BeaconClient(AuthContext("https://voice-fixture.invalid", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val context = request.url.encodedPath.endsWith("/context")
                if (context) { contexts.incrementAndGet(); if (failContext) throw java.io.IOException("Synthetic context outage") }
                val body = if (context) json("profile" to "default", "selected_session_id" to "stored", "greeting" to line) else json("jobs" to JSONArray())
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
            val greeting = CallGreeting(early, { _, spoken -> observed += spoken }, { text, pcm -> stored += text to pcm.size })
            voice = DirectVoice(client, "call", "stored", scope, { bytes -> audio += bytes; voice.played(bytes.size) }, { events += it }, { failure.complete(it) },
                credentials = credentials, providerSocketFactory = provider, providerCallFactory = tts, clock = { 51_000L }, tappedAt = 50_000L, greeting = greeting)
        }
        fun completeSetup() { val (socket, listener) = inputs.last(); listener.onMessage(socket, json("setupComplete" to json()).toString()) }
        fun receive(frame: JSONObject) { val (socket, listener) = inputs.last(); listener.onMessage(socket, frame.toString()) }
        fun heard(frames: Long) = speaker.frames.set(frames)
        fun states() = events.filter { it.optString("type") == "state" }.map { it.getString("state") }
        fun stage(name: String) = events.filter { it.optString("type") == "media_diagnostic" && it.optString("stage") == name }
        fun captions(text: String) = events.count { it.optString("type") == "transcript" && it.optString("text") == text }
        fun close() { voice.close(); scope.cancel(); client.closeNative() }
    }

    private suspend fun waitFor(predicate: () -> Boolean): Boolean = withTimeoutOrNull(3000) {
        while (!predicate()) delay(10)
        true
    } == true

    @Test(timeout = 10000) fun theCachedGreetingIsTheCallsOnlyGreeting() = runBlocking {
        val c = Call(holdSetup = true)
        try {
            assertTrue(waitFor { c.speaker.started.size == 1 })
            c.heard(2400)
            assertTrue("Heard from the tap, before any grant, socket or context", waitFor { c.early.firstAudioMs == 120L })
            c.voice.start()
            assertTrue(waitFor { c.contexts.get() == 1 && c.observed.size == 1 })
            delay(150)
            assertTrue("No second greeting is rendered", c.tts.requests.isEmpty())
            assertTrue("Nothing says listening before admission", c.states().isEmpty())
            c.completeSetup()
            assertTrue(waitFor { c.stage("provider_ready").size == 1 })
            assertEquals("Already heard: the microphone opens, no thinking", listOf("listening"), c.states())
            val started = c.stage("playout_started").single()
            assertEquals("The first frame keeps its time on the tap clock", 120L, started.getLong("elapsed_ms"))
            assertEquals("cached", started.getString("greeting"))
            c.heard(24000)
            assertTrue(waitFor { c.captions(line) == 1 && c.stage("playout_stopped").size == 1 })
            assertEquals("The cached line is the transcript, once", 1, c.captions(line))
            assertTrue(c.events.any { it.optString("type") == "turn_complete" })
            delay(150)
            assertTrue("Still no render and nothing on the call's own track", c.tts.requests.isEmpty() && c.audio.isEmpty())
            assertEquals(listOf("listening"), c.states())
            assertEquals("The verified context's line is recorded for the next tap", listOf(line), c.observed)
            assertTrue(c.speaker.released)
            assertEquals("The presence follows the cached line, then rests", 0f, c.levels.last())
            assertTrue(c.levels.any { it > .1f })
            assertFalse(c.failure.isCompleted)
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun aCachedGreetingStillStartingShowsThinkingUntilItIsHeard() = runBlocking {
        val c = Call()
        try {
            assertTrue(waitFor { c.speaker.started.size == 1 })
            c.voice.start()
            assertTrue(waitFor { "thinking" in c.states() })
            assertEquals(listOf("listening", "thinking"), c.states())
            c.heard(480)
            assertTrue(waitFor { c.states().size == 3 })
            assertEquals("First heard frame returns the call to listening", listOf("listening", "thinking", "listening"), c.states())
            assertEquals(120L, c.stage("playout_started").single().getLong("elapsed_ms"))
            assertTrue(c.tts.requests.isEmpty())
        } finally { c.close() }
    }

    @Test fun whileTheCachedGreetingPlaysTheCallStillSaysConnectingOrThinking() {
        val connecting = CallState(active = true, state = "connecting").withGreetingLevel(.4f)
        assertTrue(connecting.remoteSpeaking)
        assertEquals("connecting", connecting.state)
        assertEquals("Connecting", callStatusLabel(connecting))
        assertEquals("Start", connecting.chipLabel())
        val thinking = CallState(active = true, state = "thinking").withGreetingLevel(.4f)
        assertEquals("Thinking", callStatusLabel(thinking))
        val reconnecting = CallState(active = true, state = "reconnecting").withGreetingLevel(.4f)
        assertEquals("Connecting", callStatusLabel(reconnecting))
        // Admitted: the line reads as speaking, like any reply, and rests as listening.
        val admitted = CallState(active = true, state = "listening").withGreetingLevel(.4f)
        assertEquals("speaking", admitted.state)
        assertEquals("Speaking", callStatusLabel(admitted))
        val rested = admitted.withGreetingLevel(0f)
        assertEquals("listening", rested.state)
        assertFalse(rested.remoteSpeaking)
        assertNull(rested.remoteAudioLevel)
        assertSame("A bad level changes nothing", connecting, connecting.withGreetingLevel(Float.NaN))
    }

    @Test(timeout = 10000) fun aFailureWhileTheCachedGreetingPlaysShowsAsTodayAndSilencesIt() = runBlocking {
        val c = Call(holdSetup = true, failContext = true)
        try {
            assertTrue(waitFor { c.speaker.started.size == 1 })
            c.heard(4800)
            assertTrue(waitFor { c.early.firstAudioMs >= 0 })
            c.voice.start()
            assertEquals("Call context unavailable; reconnect to Hermes and retry", withTimeout(3000) { c.failure.await() })
            // The service closes the transport on failure; the cached line falls silent with it.
            c.voice.close()
            assertTrue(c.speaker.released)
            assertFalse(c.early.speaking)
            c.heard(24000); delay(150)
            assertEquals("A cut line is not the call's transcript", 0, c.captions(line))
            assertTrue("Never listening", c.states().isEmpty())
            assertFalse(c.voice.pcm(ByteArray(640)) { true })
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun theOwnerTalkingOverTheCachedGreetingStopsIt() = runBlocking {
        val c = Call()
        try {
            assertTrue(waitFor { c.speaker.started.size == 1 })
            c.heard(2400)
            c.voice.start()
            assertTrue(waitFor { c.stage("provider_ready").size == 1 })
            c.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            assertTrue(waitFor { c.speaker.released })
            assertTrue(c.events.any { it.optString("type") == "interrupted" })
            c.heard(24000); delay(150)
            assertEquals(0, c.captions(line))
            assertTrue("Nothing else is said in its place", c.tts.requests.isEmpty())
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun withoutACachedLineTheCallRendersItsGreetingAndKeepsItForTheNextTap() = runBlocking {
        val c = Call(cached = false)
        try {
            assertTrue(waitFor { !c.early.available })
            c.voice.start()
            assertTrue("Today's path: the context greeting renders", waitFor { c.tts.requests.size == 1 })
            assertEquals(line, c.tts.text(0))
            assertTrue(waitFor { "thinking" in c.states() })
            c.tts.respond(SpeechHttpFixture.pcm(java.util.Base64.getEncoder().encodeToString(speech)))
            assertTrue(waitFor { c.captions(line) == 1 && c.stored.size == 1 })
            assertEquals(listOf("listening", "thinking", "listening"), c.states())
            assertEquals("The rendered audio fills the cache for the next call", line to speech.size, c.stored.single())
            assertTrue(c.speaker.started.isEmpty())
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun aSpeakerThatRefusesOrNeverStartsGivesTheOpeningBack() = runBlocking {
        val refused = Call(speaker = Speaker(refuse = true))
        try {
            assertTrue(waitFor { !refused.early.available })
            refused.voice.start()
            assertTrue(waitFor { refused.tts.requests.size == 1 })
        } finally { refused.close() }
        // A track that never presents a frame hands the opening back after its start limit.
        val silent = Call()
        try {
            assertTrue(waitFor { silent.speaker.started.size == 1 })
            silent.voice.start()
            assertTrue(waitFor { "thinking" in silent.states() })
            assertTrue(withTimeoutOrNull(EarlyGreeting.START_LIMIT_MS + 2000) { while (silent.tts.requests.isEmpty()) delay(20); true } == true)
            assertEquals(line, silent.tts.text(0))
            assertTrue(silent.speaker.released)
            assertEquals("One thinking, no repeat", 1, silent.states().count { it == "thinking" })
        } finally { silent.close() }
    }

    @Test(timeout = 10000) fun aGreetingStoppedBeforeItsFirstFrameCannotBeJoined() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val loading = CompletableDeferred<Unit>()
            val early = EarlyGreeting({ 0L }).also { it.begin(scope, { Speaker() }) { loading.await(); line to speech } }
            early.stop()
            loading.complete(Unit)
            assertFalse(early.available)
            assertFalse(early.join({ fail("never heard") }, { fail("never ends for the call") }))
            // A heard and finished line still reports both to a late join, once.
            val speaker = Speaker()
            val done = EarlyGreeting({ 7L }).also { it.begin(scope, { speaker }) { line to speech } }
            assertTrue(waitFor { speaker.started.size == 1 }); speaker.frames.set(24000)
            assertTrue(waitFor { !done.speaking && done.firstAudioMs == 7L && speaker.released })
            val heard = CopyOnWriteArrayList<Long>(); val ends = CopyOnWriteArrayList<Int>()
            assertTrue(done.join({ heard += it }, { ends += it }))
            assertEquals(listOf(7L), heard)
            assertEquals(listOf(EarlyGreeting.DONE), ends)
        } finally { scope.cancel() }
    }
}
