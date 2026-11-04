package dev.hermes.android.voiceconnect

import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.data.defaultVoiceModel
import dev.hermes.android.data.defaultVoiceName
import dev.hermes.android.voice.CALL_HERMES_NO_ANSWER
import dev.hermes.android.voice.CALL_SIGN_IN_EXPIRED
import dev.hermes.android.voice.DirectVoice
import dev.hermes.android.voice.LiveSession
import dev.hermes.android.voice.NativeCallTranscriptSync
import dev.hermes.android.voice.callFailureText
import dev.hermes.android.voice.contextMatches
import dev.hermes.android.voice.liveInstruction
import dev.hermes.android.voice.liveSetup
import dev.hermes.android.voice.liveStartCue
import dev.hermes.android.voice.warmIdleLimitMs
import dev.hermes.android.voice.warmRenewsWhenIdle
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The call connects for real: the first audio the owner hears is the live session's own greeting, produced only after that
 * session answered setup with the conversation's context, and played only once the microphone streams and Hermes admitted
 * the call. Nothing on the phone renders, caches or plays a greeting of its own.
 */
class VoiceConnectTest {
    private val gemini = VoiceSessionCredentials("gemini", "fixture-provider-key", defaultVoiceModel("gemini"), defaultVoiceName("gemini"), "balanced")
    private val context = json("profile" to "default", "selected_session_id" to "stored", "owner_first_name" to "Maya", "zone" to "Asia/Dubai",
        "context" to json("language" to "Arabic"), "callback" to JSONObject())
    private val greetingPcm = ByteArray(9600) { (it % 7).toByte() }
    private fun greetingAudio() = json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to
        json("mimeType" to "audio/pcm;rate=24000", "data" to java.util.Base64.getEncoder().encodeToString(greetingPcm)))))))
    private fun greetingWords(text: String) = json("serverContent" to json("outputTranscription" to json("text" to text)))
    private fun greetingDone() = json("serverContent" to json("generationComplete" to true, "turnComplete" to true))

    /** A Gemini Live double: records every frame, answers setup unless held. */
    private class Provider(val holdSetup: Boolean = false) : WebSocket.Factory {
        val frames = CopyOnWriteArrayList<JSONObject>()
        val sockets = CopyOnWriteArrayList<Pair<WebSocket, WebSocketListener>>()
        override fun newWebSocket(request: okhttp3.Request, listener: WebSocketListener): WebSocket {
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text); frames += frame
                    if (frame.has("setup") && !holdSetup) listener.onMessage(this, json("setupComplete" to json()).toString())
                    return true
                }
            }
            sockets += socket to listener
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            return socket
        }
        fun receive(frame: JSONObject, index: Int = sockets.lastIndex) { val (socket, listener) = sockets[index]; listener.onMessage(socket, frame.toString()) }
        fun cues() = frames.filter { it.has("clientContent") }
        fun setups() = frames.filter { it.has("setup") }
    }

    private fun client(contextStatus: Int = 200, callContextReads: AtomicInteger = AtomicInteger(), sessionReads: AtomicInteger = AtomicInteger()) =
        BeaconClient(AuthContext("https://voice-fixture.invalid", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                val (code, body) = when {
                    path == "/api/plugins/beacon/mobile/voice/context" -> { sessionReads.incrementAndGet()
                        if (contextStatus == 200) 200 to context else contextStatus to json("detail" to if (contextStatus == 404) "Not Found" else "Synthetic failure") }
                    path.endsWith("/context") -> { callContextReads.incrementAndGet(); 200 to context }
                    else -> 200 to json("jobs" to JSONArray())
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }

    private inner class Call(val provider: Provider = Provider(), admission: Deferred<JSONObject>? = null, prefetched: JSONObject? = null,
        warm: LiveSession? = null, val client: BeaconClient = client(), streamsMicrophone: Boolean = true, now: () -> Long = { 50_400L }) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = CopyOnWriteArrayList<JSONObject>()
        val audio = CopyOnWriteArrayList<ByteArray>()
        val failure = CompletableDeferred<String>()
        lateinit var voice: DirectVoice
        init {
            voice = DirectVoice(client, if (admission == null) "call" else "", "stored", scope, { bytes -> audio += bytes; voice.played(bytes.size) },
                { events += it }, { failure.complete(it) }, gemini, providerSocketFactory = provider, clock = now, tappedAt = 50_000L,
                warm = warm, prefetched = prefetched, admission = admission, streamsMicrophone = streamsMicrophone)
        }
        fun states() = events.filter { it.optString("type") == "state" }.map { it.getString("state") }
        fun stage(name: String) = events.filter { it.optString("type") == "media_diagnostic" && it.optString("stage") == name }
        /** The phone's microphone frames, once the provider is ready to take them. */
        suspend fun microphone() {
            assertTrue("The provider must answer setup first", withTimeoutOrNull(3000) { while (stage("provider_ready").isEmpty()) delay(10); true } == true)
            repeat(3) { assertTrue(voice.pcm(ByteArray(640)) { true }) }
        }
        fun at(name: String): Long = stage(name).singleOrNull()?.getLong("elapsed_ms")
            ?: throw AssertionError("$name missing; stages: " + events.filter { it.optString("type") == "media_diagnostic" }.map { it.optString("stage") })
        fun close() { voice.close(); scope.cancel(); client.closeNative() }
    }

    private suspend fun waitFor(predicate: () -> Boolean): Boolean = withTimeoutOrNull(3000) { while (!predicate()) delay(10); true } == true

    @Test fun noPhoneGreetingPathRemainsInTheApp() {
        val root = listOf("src/main/java/dev/hermes/android", "app/src/main/java/dev/hermes/android", "beacon-android/app/src/main/java/dev/hermes/android")
            .map(::File).first { it.isDirectory }
        val sources = root.walk().filter { it.isFile && it.extension == "kt" }.associate { it.relativeTo(root).path to it.readText() }
        for (gone in listOf("GreetingCache", "EarlyGreeting", "OpeningPrefetch", "TrackGreetingOutput", "callGrantGreeting", "openingRenderMs", "MODE_STATIC"))
            assertTrue("$gone must not exist", sources.none { (_, text) -> text.contains(gone) })
        assertFalse("No greeting audio is stored on the phone", sources.values.any { it.contains("\"voice-greetings\"") && !it.contains("deleteRecursively") })
    }

    @Test(timeout = 10000) fun theFirstAudioIsTheLiveModelsGreetingAfterSetup() = runBlocking {
        val c = Call()
        try {
            c.voice.start()
            assertTrue("The start note goes to the live session once it answered setup", waitFor { c.provider.cues().size == 1 })
            assertTrue("The live setup carries the conversation's context", c.provider.setups().single().toString().contains("Maya"))
            assertEquals(listOf<String>(), c.states().filter { it != "reconnecting" })
            c.provider.receive(greetingAudio())
            delay(150)
            assertTrue("Nothing plays before the call is connected", c.audio.isEmpty())
            c.microphone()
            // Connected on a greeting the live model already sent: the first state is its speech.
            assertTrue(waitFor { c.states().firstOrNull { it != "reconnecting" } == "speaking" })
            assertTrue("The model's own audio plays once connected", waitFor { c.audio.size == 1 })
            assertArrayEquals(greetingPcm, c.audio.single())
            c.provider.receive(greetingWords("مساء الخير يا مايا"))
            c.provider.receive(greetingDone())
            assertTrue(waitFor { c.events.any { it.optString("type") == "transcript" && it.optString("text") == "مساء الخير يا مايا" } })
            assertTrue(waitFor { c.stage("first_model_audio").isNotEmpty() })
            val setup = c.at("provider_setup_ok")
            val heard = c.at("first_model_audio")
            assertTrue("The greeting is heard after the provider answered setup", heard >= setup)
            for (stage in listOf("context_sent", "context_ok", "provider_open", "provider_setup_ok", "mic_streaming", "first_model_audio"))
                assertEquals(stage, 1, c.stage(stage).size)
            assertTrue("Every stage is on the tap clock", c.events.filter { it.optString("type") == "media_diagnostic" }.all { it.getLong("elapsed_ms") == 400L })
            assertFalse("Diagnostics stay content-free", c.events.filter { it.optString("type") == "media_diagnostic" }.toString().contains("Maya"))
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun typedWordsBecomeOneUserTurnOfTheLiveSessionOnlyOnceItIsReady() = runBlocking {
        val c = Call(Provider(holdSetup = true))
        try {
            c.voice.start()
            assertFalse("Nothing is sent before the live session is ready", c.voice.sendText("hello"))
            assertTrue("The live socket opens", waitFor { c.provider.sockets.isNotEmpty() && c.provider.setups().isNotEmpty() })
            assertFalse("Nothing is sent while setup is unanswered", c.voice.sendText("hello"))
            val (socket, listener) = c.provider.sockets.single()
            listener.onMessage(socket, json("setupComplete" to json()).toString())
            c.microphone()
            val cues = c.provider.cues().size
            assertTrue(c.voice.sendText("  What's on my calendar?  "))
            // Only the typed words count: the call's own opening cue may land in the same moment on a slow run (4 Nov 2026 build).
            val turn = c.provider.cues().drop(cues).single { it.optJSONObject("clientContent")?.toString()?.contains("What's on my calendar?") == true }
                .getJSONObject("clientContent")
            assertTrue(turn.getBoolean("turnComplete"))
            assertEquals("What's on my calendar?", turn.getJSONArray("turns").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
            assertTrue("The owner's typed words show in the captions", c.events.any { it.optString("type") == "transcript" && it.optString("role") == "user" && it.optString("text") == "What's on my calendar?" })
            assertFalse("Blank text is not a turn", c.voice.sendText("   "))
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun theCallStaysConnectingUntilHermesAdmitsItAndToolsWaitForIt() = runBlocking {
        val admission = CompletableDeferred<JSONObject>()
        val c = Call(admission = admission, prefetched = context)
        try {
            c.voice.start()
            assertTrue(waitFor { c.provider.cues().size == 1 })
            c.microphone(); c.provider.receive(greetingAudio())
            delay(200)
            assertTrue("Connecting until Hermes admitted the call", c.states().none { it == "listening" || it == "speaking" })
            assertTrue("The greeting waits with it", c.audio.isEmpty())
            assertEquals(1, c.stage("context_ok").size); assertEquals(true, c.stage("context_ok").single().getBoolean("warm"))
            admission.complete(json("call_id" to "admitted-call", "conversation_id" to "stored"))
            assertTrue(waitFor { "speaking" in c.states() && c.audio.size == 1 })
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun ownerSpeechCutsTheGreetingAndIsStillCheckedNormally() = runBlocking {
        val c = Call()
        try {
            c.voice.start(); assertTrue(waitFor { c.provider.cues().size == 1 }); c.microphone()
            assertTrue(waitFor { "listening" in c.states() })
            c.provider.receive(greetingAudio())
            assertTrue(waitFor { c.audio.size == 1 })
            c.provider.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            c.provider.receive(greetingAudio())
            c.provider.receive(json("serverContent" to json("interrupted" to true)))
            delay(150)
            assertEquals("Greeting audio after the owner spoke is dropped", 1, c.audio.size)
            assertTrue(c.events.any { it.optString("type") == "interrupted" })
            assertTrue("A cut greeting leaves no caption", c.events.none { it.optString("type") == "transcript" && it.optString("role") == "assistant" })
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun aWarmSessionIsTakenOverWithoutASecondSocketOrSetup() = runBlocking {
        val provider = Provider()
        val clock = AtomicInteger(49_000)
        val warm = LiveSession(Scope("https://voice-fixture.invalid", "owner", "default", session = "stored"), gemini, context, provider) { clock.get().toLong() }
        assertTrue(waitFor { warm.setupAt >= 0 })
        warm.startTurn(liveStartCue(context))
        assertEquals("The start note is sent at the tap", 1, provider.cues().size)
        provider.receive(greetingAudio())
        clock.set(50_200)
        val c = Call(provider = provider, warm = warm, prefetched = context, now = { clock.get().toLong() })
        try {
            c.voice.start(); c.microphone()
            assertTrue(waitFor { c.audio.size == 1 })
            assertEquals("One socket for the whole call", 1, provider.sockets.size)
            assertEquals("Setup and start note are not sent again", 1, provider.setups().size); assertEquals(1, provider.cues().size)
            val setup = c.stage("provider_setup_ok").single()
            assertEquals("Set up before the tap", 0L, setup.getLong("elapsed_ms")); assertTrue(setup.getBoolean("warm"))
            assertTrue(waitFor { c.stage("first_model_audio").isNotEmpty() })
            assertTrue(c.at("first_model_audio") >= 0)
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun muteDuringAGrokGreetingClearsTheInputOnlyAfterTheGreeting() = runBlocking {
        val grok = VoiceSessionCredentials("grok", "fixture-provider-key", defaultVoiceModel("grok"), defaultVoiceName("grok"), "balanced")
        val frames = CopyOnWriteArrayList<JSONObject>()
        val sockets = CopyOnWriteArrayList<Pair<WebSocket, WebSocketListener>>()
        val provider = WebSocket.Factory { request, listener ->
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text); frames += frame
                    if (frame.optString("type") == "session.update") listener.onMessage(this, json("type" to "session.updated").toString())
                    return true
                }
            }
            sockets += socket to listener
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            socket
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = CopyOnWriteArrayList<JSONObject>(); val client = client()
        lateinit var voice: DirectVoice
        voice = DirectVoice(client, "call", "stored", scope, { voice.played(it.size) }, { events += it }, {}, grok, providerSocketFactory = provider)
        fun receive(frame: JSONObject) { val (socket, listener) = sockets.last(); listener.onMessage(socket, frame.toString()) }
        fun clears() = frames.count { it.optString("type") == "input_audio_buffer.clear" }
        try {
            voice.start()
            assertTrue("The start note asks Grok for a spoken answer", waitFor { frames.any { it.optString("type") == "response.create" } })
            receive(json("type" to "response.created"))
            receive(json("type" to "response.output_audio.delta", "delta" to java.util.Base64.getEncoder().encodeToString(greetingPcm)))
            assertTrue(voice.send(dev.hermes.android.voice.GeminiWire.endAudio()))
            delay(150)
            assertEquals("A mute during the greeting does not clear Grok's input (that cancels the greeting)", 0, clears())
            receive(json("type" to "response.output_audio_transcript.done", "item_id" to "greeting", "transcript" to "Good evening, Maya."))
            receive(json("type" to "response.done", "response" to json("status" to "completed")))
            assertTrue("The mute's clear follows the greeting", waitFor { clears() == 1 })
            assertTrue(events.any { it.optString("type") == "transcript" && it.optString("text") == "Good evening, Maya." })
            assertTrue(voice.send(dev.hermes.android.voice.GeminiWire.endAudio()))
            assertTrue("Later mutes clear at once", waitFor { clears() == 2 })
        } finally { voice.close(); scope.cancel(); client.closeNative() }
    }

    @Test fun heldFramesAreNeverPresentedByAWarmSessionNobodyTook() = runBlocking {
        val provider = Provider()
        val warm = LiveSession(Scope("https://voice-fixture.invalid", "owner", "default", session = "stored"), gemini, context, provider) { 0L }
        provider.receive(greetingAudio())
        assertFalse(warm.dead)
        warm.close()
        assertTrue(warm.dead)
        assertTrue("No start note without a tap", provider.cues().isEmpty())
    }

    @Test(timeout = 10000) fun anOlderPluginsContextIsReadOnceHermesAdmittedTheCall() = runBlocking {
        val callReads = AtomicInteger(); val sessionReads = AtomicInteger()
        val c = Call(client = client(404, callReads, sessionReads))
        try {
            c.voice.start(); c.microphone()
            assertTrue(waitFor { "listening" in c.states() })
            assertEquals(1, sessionReads.get()); assertEquals(1, callReads.get())
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun aFailedStartNamesTheStageAndSaysWhoseSideFailed() = runBlocking {
        val c = Call(client = client(503))
        try {
            c.voice.start()
            assertEquals(CALL_HERMES_NO_ANSWER, withTimeout(5000) { c.failure.await() })
            val error = c.stage("voice_error").single()
            assertEquals("context_ok", error.getString("failed_stage")); assertEquals("http_503", error.getString("error_code"))
            assertTrue(c.audio.isEmpty())
        } finally { c.close() }
        val expired = Call(client = client(401))
        try {
            expired.voice.start()
            assertEquals(CALL_SIGN_IN_EXPIRED, withTimeout(5000) { expired.failure.await() })
        } finally { expired.close() }
        assertEquals("The voice service didn't answer. Try again.", callFailureText("provider_setup_ok", java.net.SocketTimeoutException()))
        assertEquals("The microphone didn't start. Try again.", callFailureText("mic_streaming", null))
        // Owner, 4 Nov 2026: a callback sat on Connecting when the VPN dropped; a call that cannot reach Hermes says so.
        assertEquals("Can't reach Hermes. Check your connection, then try again.",
            callFailureText("context_ok", java.io.IOException("Native connection interrupted", java.net.ConnectException("Failed to connect"))))
        assertEquals("Hermes didn't answer. Try again.", callFailureText("context_ok", java.net.SocketTimeoutException("timeout")))
        // The voice service out of reach is the voice service, not Hermes.
        assertEquals("The voice service didn't answer. Try again.", callFailureText("provider_setup_ok", java.net.ConnectException("Failed to connect")))
    }

    @Test(timeout = 10000) fun aFailedCallUploadsItsStageDiagnosticsBeforeItIsReleased() = runBlocking {
        val posted = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = NativeCallTranscriptSync({ path, _, body -> delay(30); posted += path.substringAfterLast('/') + ":" + (body?.optJSONArray("events")?.optJSONObject(0)?.optString("stage") ?: "")
            if (path.endsWith("finalize")) json("closed" to true, "transcript_status" to "saved") else json("stored" to true) }, "call", scope) { }
        try {
            for (stage in listOf("grant_sent", "grant_ok", "context_sent", "provider_open")) sync.event(json("type" to "media_diagnostic", "stage" to stage, "event_id" to stage, "turn_id" to "connection"))
            sync.event(json("type" to "media_diagnostic", "stage" to "voice_error", "failed_stage" to "context_ok", "event_id" to "error", "turn_id" to "connection"))
            assertTrue(sync.finish(failed = true))
            val finalized = posted.indexOf("finalize:")
            assertTrue(posted.toString(), finalized == posted.lastIndex)
            assertTrue("Every stage reached Hermes before the release", posted.subList(0, finalized).map { it.substringAfter(':') }.containsAll(listOf("grant_sent", "grant_ok", "context_sent", "provider_open", "voice_error")))
        } finally { scope.cancel() }
    }

    @Test fun theLiveSetupGreetsOnceInTheOwnersLanguageAndOtherwiseAnswersWithHermesTools() {
        val instruction = liveInstruction(context, gemini.personalityInstruction, gemini.model)
        assertTrue(instruction.contains("\"Maya\"") && instruction.contains("otherwise Arabic") && instruction.contains("one short, warm greeting"))
        assertTrue("The session answers the owner itself", instruction.contains("You answer the owner yourself, in your own voice"))
        assertTrue(liveInstruction(json("profile" to "default")).contains("without using any name"))
        val callback = liveInstruction(json("callback" to json("reason" to "the flight check")))
        assertTrue(callback.contains("callback the owner asked for") && callback.contains("the flight check"))
        assertFalse("The callback reason is data, not the trusted opening", callback.substringAfter("Trusted natural opening guidance:\n").substringBefore('\n').contains("the flight check"))
        val setup = liveSetup(gemini, context).getJSONObject("setup")
        val tools = setup.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertTrue("The warm session already carries Hermes' tools", names.containsAll(listOf("discover_tools", "invoke_tool", "tool_status", "hermes_work", "get_visual_state")))
        assertEquals(instruction, setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        // 1 November 2026 14:05 UTC is 18:05 in Dubai.
        assertTrue(liveStartCue(context, 1793541900000L).contains("Sunday 18:05"))
        assertTrue(contextMatches(context, Scope("o", "owner", "default", session = "stored")))
        assertFalse(contextMatches(context, Scope("o", "owner", "default", session = "other")))
        assertFalse(contextMatches(context, Scope("o", "owner", "work", session = "stored")))
        assertEquals("Grok bills each open minute", 60_000L, warmIdleLimitMs("grok"))
        assertTrue(warmIdleLimitMs("gemini") > warmIdleLimitMs("grok"))
        assertTrue("Gemini and OpenAI stay warm while the entry shows", warmRenewsWhenIdle("gemini") && warmRenewsWhenIdle("openai"))
        assertFalse("Grok rests after its idle minute", warmRenewsWhenIdle("grok"))
    }
}
