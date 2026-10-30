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
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Network doubles preserve the real DirectVoice, native gateway and provider event paths. */
class NativeSpeechBackendTest {
    @Test(timeout = 12000) fun proseEmphasisIsNotSpokenButLiteralStarsRemainRequired() = runBlocking {
        for ((native,spoken,allowed) in listOf(
            Triple("One available skill is **hermes-agent**.","One available skill is hermes-agent.",true),
            Triple("One available skill is _hermes-agent_.","One available skill is hermes-agent.",true),
            Triple("Keep `**literal**` unchanged.","Keep `literal` unchanged.",false),
            Triple("Calculate 2**3.","Calculate 2*3.",false),
        )) {
            val f=RenderFixture("grok")
            try {
                f.start(native);f.receive(renderPcm("grok"));f.receive(renderTranscript("grok",text=spoken));f.receive(renderDone("grok","completed"))
                if(allowed) {
                    assertTrue(native,waitFor { f.events.any { it.optString("text")==native } })
                    assertEquals(1,f.audio.size)
                } else { delay(100);assertTrue(native,f.audio.isEmpty()) }
            } finally { f.close() }
        }
    }
    @Test(timeout = 8000) fun apostropheTypographyKeepsTheNativeReplyAndMeasurementMark() = runBlocking {
        val f=RenderFixture("grok")
        try {
            val native="Your preferred name is Maya. I didn\u2019t change anything."
            f.start(native);f.receive(renderPcm("grok"))
            f.receive(renderTranscript("grok",text="Your preferred name is Maya. I didn't change anything."))
            f.receive(renderDone("grok","completed"))
            assertTrue(waitFor { f.events.any { it.optString("text")==native } })
            assertEquals(1,f.audio.size)
            assertNotEquals(normalizeVoiceText("Height 6\u2019"),normalizeVoiceText("Height 6"))
        } finally { f.close() }
    }
    @Test(timeout = 8000) fun completedGeminiSilenceRetriesOnlyTheReadOnlySpeechRender() = runBlocking {
        val f=RenderFixture()
        try {
            f.start();f.receive(renderTranscript(text="<silence></silence>"));f.receive(renderDone())
            assertTrue("A silent completed generation gets one new isolated render",waitFor { f.requestCount("Verified reply.")==2 })
            assertTrue(f.audio.isEmpty());assertFalse(f.warned())
            f.receive(renderPcm());f.receive(renderTranscript());f.receive(renderDone())
            assertTrue(waitFor { f.events.any { it.optString("text")=="Verified reply." } })
            assertEquals(1,f.audio.size);assertEquals(2,f.requestCount("Verified reply."));assertFalse(f.warned())
        } finally { f.close() }
    }
    @Test(timeout = 8000) fun straightAndCurlyProseQuotesRenderTheSameNativeWords() = runBlocking {
        val f=RenderFixture()
        try {
            val native="One available skill is \u201chermes-agent.\u201d"
            f.start(native);f.receive(renderPcm())
            f.receive(renderTranscript(text=" One available skill is \"hermes-agent.\""));f.receive(renderDone())
            assertTrue("Quote typography must not reject the same native words",waitFor { f.audio.size==1 })
            assertTrue(waitFor { f.events.any { it.optString("text")==native } })
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun doubleMeasurementMarksAreNotProseQuotes() = runBlocking {
        val f=RenderFixture()
        try {
            f.start("Width 6\" and height 8\".");f.receive(renderPcm())
            f.receive(renderTranscript(text="Width 6 and height 8."));f.receive(renderDone())
            assertTrue(waitFor { f.warned() });assertTrue(f.audio.isEmpty())
        } finally { f.close() }
    }
    @Test fun geminiNumericAdditionSpellingKeepsTheRecognizedInput() {
        for((input,echo) in listOf("What is 2 + 3?" to "What is 2 plus 3?", "WHAT is 12.5 plus 3?" to "What is 12.5+3?", "Add 1+2+3" to "Add 1 plus 2 plus 3")) {
            val ledger=VoiceFinalItems()
            ledger.activityStart()
            assertNull(ledger.accept(JSONObject().put("text",input),true))
            assertNull(ledger.echo(JSONObject().put("text",echo)))
            assertNull(ledger.activityEnd())
            assertEquals(input,ledger.generationComplete()?.second)
            assertNull("A spelling equivalence must not submit twice",ledger.generationComplete())
        }
    }

    @Test fun geminiAdditionSpellingNeverChangesOperandsOperatorsOrIdentifiers() {
        for((input,echo) in listOf(
            "What is 2 + 3?" to "What is 2 plus 4?",
            "What is 2 + 3?" to "What is 2 minus 3?",
            "Read file2+3" to "Read file2 plus 3",
            "Read 2+3.txt" to "Read 2 plus 3.txt",
            "Read /2+3" to "Read /2 plus 3",
            "Read file2+3+4" to "Read file2+3 plus 4",
            "Read 2+3+4.txt" to "Read 2 plus 3+4.txt",
            "Read '2+3'" to "Read '2 plus 3'",
            "Do not send 2 + 3" to "Do send 2 plus 3",
        )) {
            val ledger=VoiceFinalItems()
            ledger.activityStart();ledger.accept(JSONObject().put("text",input),true)
            ledger.echo(JSONObject().put("text",echo));ledger.activityEnd()
            assertNull(input,ledger.generationComplete())
        }
    }
    // The isolated renderer is exercised through its actual provider socket callbacks.
    private class RenderFixture(private val provider: String = "gemini") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sockets = CopyOnWriteArrayList<Pair<WebSocket, WebSocketListener>>()
        val frames = CopyOnWriteArrayList<JSONObject>()
        val audio = CopyOnWriteArrayList<ByteArray>()
        val events = CopyOnWriteArrayList<JSONObject>()
        val owner = java.util.concurrent.atomic.AtomicBoolean(true)
        @Volatile var autoPlay = true
        lateinit var renderer: NativeSpeechRenderer

        private val factory = WebSocket.Factory { request, listener ->
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text)
                    frames += frame
                    if (frame.has("setup")) listener.onMessage(this, json("setupComplete" to json()).toString())
                    else if (frame.optString("type") == "session.update") listener.onMessage(this, json("type" to "session.updated").toString())
                    return true
                }
            }
            sockets += socket to listener
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            socket
        }

        init {
            renderer = NativeSpeechRenderer(
                VoiceSessionCredentials(provider, "fixture-key", defaultVoiceModel(provider), defaultVoiceName(provider), "balanced"),
                factory, scope, { owner.get() },
                { bytes -> audio += bytes; if (autoPlay) renderer.played(bytes.size) },
                { events += it }, {},
            )
        }

        suspend fun start(text: String = "Verified reply.") {
            assertTrue(renderer.say(text))
            assertTrue("The isolated socket must receive the supplied text", waitFor { requestCount(text) > 0 })
        }
        fun requestCount(text: String) = frames.count {
            it.optJSONObject("realtimeInput")?.optString("text") == text ||
                it.optJSONObject("item")?.optJSONArray("content")?.optJSONObject(0)?.optString("text") == text
        }
        fun receive(frame: JSONObject, index: Int = sockets.lastIndex) {
            val (socket, listener) = sockets[index]
            listener.onMessage(socket, frame.toString())
        }
        fun closeSocket() { val (socket, listener) = sockets.last(); listener.onClosed(socket, 1000, "Fixture") }
        fun warned() = events.any { it.optString("message").contains("could not safely read") }
        fun close() { renderer.close(); scope.cancel() }
    }
    private class Fixture(provider: String = "gemini", private val authority: Boolean = false, stallAfterMs: Long = 20000, sendGatewayReady: Boolean = true,
        private val ownerFence: Any = Any(), private val ownerActive: () -> Boolean = { true }) {
        val runtime = if (authority) "stored" else "runtime"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = CopyOnWriteArrayList<JSONObject>()
        val audio = CopyOnWriteArrayList<ByteArray>()
        val providerSockets = CopyOnWriteArrayList<Pair<WebSocket, WebSocketListener>>()
        val inputSockets = CopyOnWriteArrayList<Pair<WebSocket, WebSocketListener>>()
        val providerFrames = CopyOnWriteArrayList<JSONObject>()
        val nativeFrames = CopyOnWriteArrayList<JSONObject>()
        val nativeSockets = CopyOnWriteArrayList<Pair<WebSocket, WebSocketListener>>()
        @Volatile var autoGatewayReady = sendGatewayReady
        val contextRequests = java.util.concurrent.atomic.AtomicInteger()
        val uploads = CopyOnWriteArrayList<JSONObject>()
        val jobPosts = CopyOnWriteArrayList<JSONObject>()
        val executedPrompts = CopyOnWriteArrayList<JSONObject>()
        val rejectedPrompts = CopyOnWriteArrayList<JSONObject>()
        @Volatile var strictPromptContract = false
        @Volatile var strictCanonicalResume = false
        @Volatile var promptError = 0
        @Volatile var promptErrorMessage = "invalid params for prompt.submit: submission_id: Extra inputs are not permitted"
        @Volatile var dropPromptResponse = false
        @Volatile var approvalError = 0
        @Volatile var approvalDelayMs = 0L
        @Volatile var jobs = JSONArray()
        @Volatile var rowBound = false
        @Volatile var canonicalAlsoRow = false
        @Volatile var autoPlay = true
        @Volatile var fileUnavailable = false
        @Volatile var onStage: () -> Unit = {}
        @Volatile var holdStage = false
        @Volatile var dropStageResponse = false
        @Volatile var holdPrompt = false
        val stageHeld = CompletableDeferred<Unit>()
        val releaseStage = CompletableDeferred<Unit>()
        val stageReleased = CompletableDeferred<Unit>()
        val promptHeld = CompletableDeferred<Unit>()
        val releasePrompt = CompletableDeferred<Unit>()
        @Volatile var admission = json("status" to "started", "admission_id" to "admission-1", "ref" to json("session_id" to runtime))

        val native = WebSocket.Factory { request, listener ->
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text)
                    if (!frame.has("method")) return true
                    nativeFrames += frame
                    val method = frame.getString("method")
                    val params = frame.getJSONObject("params")
                    // These closed sets match the installed legacy prompt and canonical resume contracts.
                    if (authority && strictCanonicalResume && method == "session.resume" &&
                        params.keys().asSequence().any { it !in setOf("session_id","profile","source","omit_messages") }) {
                        listener.onMessage(this,json("jsonrpc" to "2.0","id" to frame.get("id"),"error" to json("code" to 4001,"message" to "invalid_params")).toString())
                        return true
                    }
                    if (method == "prompt.submit") {
                        val unknownLegacy = !authority && strictPromptContract &&
                            params.keys().asSequence().any { it !in setOf("session_id","profile","text","surface","queued") }
                        if (unknownLegacy || promptError != 0) {
                            rejectedPrompts += frame
                            listener.onMessage(this,json("jsonrpc" to "2.0","id" to frame.get("id"),"error" to json("code" to (if(unknownLegacy) 4000 else promptError),"message" to promptErrorMessage)).toString())
                            return true
                        }
                        executedPrompts += frame
                        if (dropPromptResponse) {
                            listener.onFailure(this,java.io.IOException("Fixture lost the prompt response"),null)
                            return true
                        }
                    }
                    if (method == "approval.pending") {
                        if (approvalError != 0) {
                            listener.onMessage(this,json("jsonrpc" to "2.0","id" to frame.get("id"),"error" to json("code" to approvalError,"message" to "fixture permanent failure")).toString())
                            return true
                        }
                        if (approvalDelayMs > 0) {
                            val socket = this
                            scope.launch {
                                delay(approvalDelayMs)
                                listener.onMessage(socket,json("jsonrpc" to "2.0","id" to frame.get("id"),"result" to json("approvals" to JSONArray())).toString())
                            }
                            return true
                        }
                    }
                    if(frame.getString("method") in setOf("file.attach","image.attach_bytes")) {
                        onStage()
                        if (dropStageResponse) { listener.onFailure(this, java.io.IOException("Fixture lost stage response"), null); return true }
                    }
                    if(frame.getString("method")=="file.attach" && fileUnavailable) {
                        listener.onMessage(this,json("jsonrpc" to "2.0","id" to frame.get("id"),"error" to json("code" to 5028,"message" to "Fixture attachment unavailable")).toString())
                        return true
                    }
                    val result = when (frame.getString("method")) {
                        "session.active_list" -> json("sessions" to JSONArray().put(json("id" to runtime, "session_key" to "stored", "status" to "idle")))
                        "session.resume" -> if (authority) json("session_id" to runtime, "stored_session_id" to runtime, "info" to json("desktop_protocol" to "hermes-gateway-v1"), "messages" to JSONArray())
                            else json("session_id" to runtime, "stored_session_id" to "stored", "info" to json("lazy" to false), "messages" to JSONArray())
                        "prompt.submit" -> if (authority) json("status" to "started", "admission_id" to "admission-1").apply { if(canonicalAlsoRow) put("user_row_id",101) } else json("status" to "streaming", "user_row_id" to (100 + nativeFrames.count { it.optString("method") == "prompt.submit" }))
                        "prompt.receipt" -> admission
                        "file.attach" -> json("attached" to true, "path" to "/profile/attachments/voice-frame.jpg", "ref_text" to "@file:/profile/attachments/voice-frame.jpg")
                        "image.attach", "image.attach_bytes" -> json("attached" to true, "path" to "/profile/cache/images/voice-frame.jpg", "mime" to "image/jpeg")
                        "session.events.since" -> json("epoch" to "fixture", "latest_seq" to 0, "events" to JSONArray(), "open_requests" to JSONArray())
                        else -> json("session_id" to runtime, "stored_session_id" to "stored", "info" to json("lazy" to false), "messages" to JSONArray())
                    }
                    val reply = json("jsonrpc" to "2.0", "id" to frame.get("id"), "result" to result).toString()
                    if (holdStage && method in setOf("file.attach", "image.attach_bytes")) {
                        val target = this
                        stageHeld.complete(Unit)
                        scope.launch { releaseStage.await(); listener.onMessage(target, reply); stageReleased.complete(Unit) }
                    } else if (holdPrompt && method == "prompt.submit") {
                        val target = this
                        promptHeld.complete(Unit)
                        scope.launch { releasePrompt.await(); listener.onMessage(target, reply) }
                    } else listener.onMessage(this, reply)
                    return true
                }
            }
            nativeSockets += socket to listener
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            if(autoGatewayReady) gatewayReady(nativeSockets.lastIndex,authority)
            socket
        }
        val client = BeaconClient(AuthContext("https://voice-fixture.invalid", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, native) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                if (path.endsWith("/context")) contextRequests.incrementAndGet()
                if (path == "/api/files/upload" || path.endsWith("/jobs")) {
                    val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                    val body = JSONObject(buffer.readUtf8())
                    if(path.endsWith("/jobs")) jobPosts += body else uploads += body
                }
                val body = when {
                    path.endsWith("/context") -> json("profile" to "default", "selected_session_id" to "stored")
                    path.endsWith("/ws-ticket") -> json("ticket" to "fixture-ticket")
                    path.endsWith("/jobs") -> json("job_id" to "job-${nativeFrames.count { it.optString("method") == "prompt.submit" }}")
                    path.endsWith("/calls/call") -> json("jobs" to jobs).apply { if(rowBound) put("job_linkage","row_bound") }
                    else -> json("ok" to true)
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val speech = WebSocket.Factory { request, listener ->
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text); providerFrames += frame
                    val instructions = frame.optJSONObject("setup")?.optJSONObject("systemInstruction")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                        ?: frame.optJSONObject("session")?.optString("instructions").orEmpty()
                    val input = instructions.startsWith("Transcribe the owner's speech.")
                    if (input && inputSockets.none { it.first === this }) inputSockets += this to listener
                    if (frame.has("setup")) listener.onMessage(this, json("setupComplete" to json()).toString())
                    else if (frame.optString("type") == "session.update") listener.onMessage(this, json("type" to "session.updated").toString())
                    return true
                }
            }
            providerSockets += socket to listener
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            socket
        }
        lateinit var voice: DirectVoice
        init {
            voice = DirectVoice(client, "call", "stored", scope, { bytes -> audio += bytes; if(autoPlay) voice.played(bytes.size) }, { events += it }, { events += json("type" to "failure", "message" to it) },
                credentials = VoiceSessionCredentials(provider, "fixture-key", defaultVoiceModel(provider), defaultVoiceName(provider), "balanced"), providerSocketFactory = speech, stallAfterMs = stallAfterMs, ownerFence = ownerFence, ownerActive = ownerActive)
        }

        suspend fun start() {
            voice.start()
            assertTrue("Provider was not admitted", waitFor { events.any { it.optString("state") == "listening" } })
            assertTrue("Greeting render was not started", waitFor { renderSockets().isNotEmpty() })
        }
        fun gatewayReady(index: Int, canonical: Boolean) {
            val (socket,listener)=nativeSockets[index]
            val payload=JSONObject().apply { if(canonical) put("session_authority",true) }
            listener.onMessage(socket,json("jsonrpc" to "2.0","method" to "event","params" to json("type" to "gateway.ready","payload" to payload)).toString())
        }
        fun receive(frame: JSONObject, index: Int = inputSockets.lastIndex) {
            val (socket, listener) = inputSockets[index]
            listener.onMessage(socket, frame.toString())
        }
        fun renderSockets() = providerSockets.filter { pair -> inputSockets.none { it.first === pair.first } }
        fun renderRequestCount(text:String) = providerFrames.count {
            it.optJSONObject("realtimeInput")?.optString("text")==text || it.optJSONObject("clientContent")?.toString()?.contains(text)==true
        }
        fun receiveRender(frame: JSONObject) {
            val (socket, listener) = renderSockets().last()
            listener.onMessage(socket, frame.toString())
        }
        fun prompts() = nativeFrames.filter { it.optString("method") == "prompt.submit" }
        @Suppress("UNCHECKED_CAST")
        fun pendingVoiceJob(): Job {
            val field = DirectVoice::class.java.getDeclaredField("toolJobs").apply { isAccessible = true }
            val jobs = field.get(voice) as MutableMap<Job, String>
            return synchronized(jobs) { jobs.keys.single() }
        }
        fun close() { voice.close(); scope.cancel(); client.closeNative() }
    }

    companion object {
        private suspend fun waitFor(predicate: () -> Boolean): Boolean = withTimeoutOrNull(2500) {
            while (!predicate()) delay(10)
            true
        } == true
        private fun geminiFinal(text: String) = json("serverContent" to json("inputTranscription" to json("text" to text, "finished" to true)))
        private fun openAiFinal(id: String, text: String) = json("type" to "conversation.item.input_audio_transcription.completed", "item_id" to id, "content_index" to 0, "transcript" to text)
    }

    private fun renderPcm(provider: String = "gemini", data: String = "AAE=") = if (provider == "gemini")
        json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000", "data" to data))))))
    else json("type" to "response.output_audio.delta", "delta" to data)

    private fun renderTranscript(provider: String = "gemini", text: String = "Verified reply.") = if (provider == "gemini")
        json("serverContent" to json("outputTranscription" to json("text" to text)))
    else json("type" to "response.output_audio_transcript.done", "item_id" to "output-one", "transcript" to text)

    private fun renderDone(provider: String = "gemini", status: String? = "IDLE") = if (provider == "gemini")
        json("serverContent" to json("turnComplete" to true).apply { if (status != null) put("interactionStatus", status) })
    else json("type" to "response.done", "response" to JSONObject().apply { if (status != null) put("status", status) })

    @Test(timeout = 8000) fun geminiRenderSetupIsAudioOnlyWithoutToolsOrResumption() = runBlocking {
        val f = RenderFixture()
        try {
            f.start()
            val setup = f.frames.first { it.has("setup") }.getJSONObject("setup")
            val modalities = setup.getJSONObject("generationConfig").getJSONArray("responseModalities")
            assertEquals(1, modalities.length()); assertEquals("AUDIO", modalities.getString(0))
            assertTrue(setup.has("outputAudioTranscription"))
            assertFalse(setup.has("inputAudioTranscription")); assertFalse(setup.has("tools"))
            assertFalse(setup.has("sessionResumption")); assertFalse(setup.has("contextWindowCompression"))
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun geminiCompletedAudioWithoutTranscriptPlaysOnceAndCaptionsAfterPresentation() = runBlocking {
        val f = RenderFixture()
        try {
            f.autoPlay = false; f.start("5")
            f.receive(renderPcm())
            assertTrue("PCM is held until the owned render completes", f.audio.isEmpty())
            f.receive(renderDone())
            assertTrue("A completed Gemini render need not contain an output transcript", waitFor { f.audio.size == 1 })
            assertArrayEquals(byteArrayOf(0, 1), f.audio.single().copyOfRange(0, 2))
            assertTrue("The native text is not captioned before presentation", f.events.none { it.optString("role") == "assistant" })
            f.renderer.played(f.audio.single().size)
            assertTrue(waitFor { f.events.any { it.optString("role") == "assistant" && it.optString("text") == "5" } })
            assertEquals("A missing optional transcript must not retry", 1, f.requestCount("5"))
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun geminiLegacyTurnCompleteWithoutStatusStillAllowsOptionalTranscript() = runBlocking {
        val f = RenderFixture()
        try {
            f.start(); f.receive(renderPcm()); f.receive(renderDone(status = null))
            assertTrue("An absent legacy status is distinct from an explicit nonterminal status", waitFor { f.audio.size == 1 })
            assertEquals(1, f.requestCount("Verified reply."))
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun geminiTranscriptionObjectWithoutTextRemainsOptional() = runBlocking {
        val f = RenderFixture()
        try {
            f.start(); f.receive(renderPcm()); f.receive(json("serverContent" to json("outputTranscription" to json())))
            f.receive(renderDone()); assertTrue(waitFor { f.audio.size == 1 })
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun geminiBlankTranscriptRemainsOptionalAndDoesNotPoisonLaterText() = runBlocking {
        for (blank in listOf("", "   ", "\u00a0")) {
            val f = RenderFixture()
            try {
                f.start(); f.receive(renderPcm()); f.receive(renderTranscript(text = blank)); f.receive(renderDone())
                assertTrue("Blank text is an absent transcript", waitFor { f.audio.size == 1 })
            } finally { f.close() }
        }
        val followed = RenderFixture()
        try {
            followed.start(); followed.receive(renderPcm()); followed.receive(renderTranscript(text = ""))
            followed.receive(renderTranscript()); followed.receive(renderDone())
            assertTrue("An empty fragment must not poison later matching text", waitFor { followed.audio.size == 1 })
        } finally { followed.close() }
    }

    @Test(timeout = 10000) fun geminiMalformedTranscriptCannotBypassTheMismatchGuard() = runBlocking {
        val supplied = listOf(
            json("outputTranscription" to json("text" to 42)),
            json("outputTranscription" to json("text" to JSONObject.NULL)),
            json("outputTranscription" to JSONObject.NULL),
        )
        for (content in supplied) {
            val f = RenderFixture()
            try {
                f.start(); f.receive(renderPcm()); f.receive(json("serverContent" to content)); f.receive(renderDone()); f.closeSocket()
                assertTrue(waitFor { f.audio.isNotEmpty() || f.warned() })
                assertTrue("A supplied invalid transcript is not an absent transcript", f.audio.isEmpty())
            } finally { f.close() }
        }
    }

    @Test(timeout = 12000) fun geminiMissingOutputTranscriptStillRendersTheVerifiedNativeReply() = runBlocking {
        val f = Fixture(authority = true)
        try {
            f.start(); f.receive(geminiFinal("What is two plus three?"))
            assertTrue(waitFor { f.prompts().size == 1 })
            f.admission = json("status" to "terminal", "outcome" to "completed", "admission_id" to "admission-1", "ref" to json("session_id" to "stored"), "result" to json("final_response" to "5"))
            assertTrue(withTimeoutOrNull(6000) { while (f.renderRequestCount("5") == 0) delay(10); true } == true)
            f.receiveRender(renderPcm()); f.receiveRender(renderDone())
            assertTrue("The native result must reach the speaker without an optional transcript", waitFor { f.audio.size == 1 })
            assertTrue(waitFor { f.events.any { it.optString("role") == "assistant" && it.optString("text") == "5" } })
            assertEquals(1, f.renderRequestCount("5")); assertEquals(1, f.prompts().size)
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun geminiExplicitNonterminalOrUnknownInteractionNeverReleasesAudio() = runBlocking {
        for (status in listOf("IN_PROGRESS", "INTERACTION_STATUS_UNSPECIFIED", "FUTURE_VALUE", 42, JSONObject.NULL)) {
            val f = RenderFixture()
            try {
                f.start(); f.receive(renderPcm()); f.receive(renderTranscript())
                f.receive(json("serverContent" to json("turnComplete" to true, "interactionStatus" to status)))
                withTimeoutOrNull(1000) { while (f.audio.isEmpty() && !f.warned()) delay(10) }
                assertTrue("$status is not a successful render completion", f.audio.isEmpty())
            } finally { f.close() }
        }
    }

    @Test(timeout = 8000) fun geminiInProgressWaitsForIdleBeforePlayingAndCaptioning() = runBlocking {
        val f = RenderFixture()
        try {
            f.autoPlay = false; f.start()
            f.receive(renderPcm()); f.receive(renderTranscript()); f.receive(renderDone(status = "IN_PROGRESS"))
            delay(150)
            assertTrue("IN_PROGRESS holds audio until a terminal status", f.audio.isEmpty())
            assertTrue(f.events.none { it.optString("role") == "assistant" })
            assertFalse("IN_PROGRESS is not a render failure", f.warned())
            f.receive(renderDone(status = null)); delay(150)
            assertTrue("An absent status cannot resolve an explicit IN_PROGRESS", f.audio.isEmpty())
            assertFalse(f.warned())
            f.receive(renderDone(status = "IDLE"))
            assertTrue("A later IDLE completes the same owned render", waitFor { f.audio.size == 1 })
            assertTrue(f.events.none { it.optString("role") == "assistant" })
            f.renderer.played(f.audio.single().size)
            assertTrue(waitFor { f.events.any { it.optString("role") == "assistant" && it.optString("text") == "Verified reply." } })
            assertEquals(1, f.requestCount("Verified reply."))
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun geminiErrorFrameInvalidatesTheWholeRender() = runBlocking {
        val f = RenderFixture()
        try {
            f.start(); f.receive(json("error" to json("code" to 500, "message" to "fixture")))
            f.receive(renderPcm()); f.receive(renderTranscript()); f.receive(renderDone())
            assertTrue(waitFor { f.audio.isNotEmpty() || f.warned() })
            assertTrue("An error cannot authorize later rendered PCM", f.audio.isEmpty())
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun geminiToolAttemptsInvalidateTheWholeRender() = runBlocking {
        val bad = listOf(
            json("toolCall" to json("functionCalls" to JSONArray().put(json("id" to "call", "name" to "do_work", "args" to json())))),
            json("toolCallCancellation" to json("ids" to JSONArray().put("call"))),
            json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("functionCall" to json("name" to "do_work", "args" to json())))))),
            json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("functionResponse" to json("name" to "do_work", "response" to json())))))),
            json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("executableCode" to json("language" to "PYTHON", "code" to "pass")))))),
            json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("codeExecutionResult" to json("outcome" to "OK")))))),
        )
        for (frame in bad) {
            val f = RenderFixture()
            try {
                f.start(); f.receive(frame); f.receive(renderPcm()); f.receive(renderTranscript()); f.receive(renderDone())
                assertTrue(waitFor { f.audio.isNotEmpty() || f.warned() })
                assertTrue("A tool attempt cannot authorize rendered PCM", f.audio.isEmpty())
            } finally { f.close() }
        }
    }

    @Test(timeout = 8000) fun geminiSuppliedConflictingTranscriptStillSilencesTheRender() = runBlocking {
        val f = RenderFixture()
        try {
            f.start(); f.receive(renderPcm()); f.receive(renderTranscript(text = "Different reply.")); f.receive(renderDone())
            f.closeSocket()
            assertTrue(waitFor { f.warned() })
            assertTrue(f.audio.isEmpty()); assertEquals(1, f.requestCount("Verified reply."))
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun geminiInterruptedOrForeignRenderNeverReleasesAudio() = runBlocking {
        for (interrupt in listOf(true, false)) {
            val f = RenderFixture()
            try {
                f.start(); f.receive(renderPcm())
                if (interrupt) f.receive(json("serverContent" to json("interrupted" to true))) else f.owner.set(false)
                f.receive(renderTranscript()); f.receive(renderDone())
                delay(150)
                assertTrue("Interrupted or foreign generations must remain silent", f.audio.isEmpty())
                assertTrue(f.events.none { it.optString("role") == "assistant" })
            } finally { f.close() }
        }
    }

    @Test(timeout = 12000) fun geminiEmptyMalformedAndOversizedPcmCannotPlay() = runBlocking {
        val badData = listOf("", "AA==", "not base64!", java.util.Base64.getEncoder().encodeToString(ByteArray(192002)))
        for (data in badData) {
            val f = RenderFixture()
            try {
                f.start(); f.receive(renderPcm(data = data)); f.receive(renderTranscript()); f.receive(renderDone()); f.closeSocket()
                assertTrue(waitFor { f.warned() }); assertTrue("Invalid PCM must stay silent", f.audio.isEmpty())
            } finally { f.close() }
        }
        val empty = RenderFixture()
        try {
            empty.start(); empty.receive(renderTranscript()); empty.receive(renderDone())
            assertTrue(waitFor { empty.requestCount("Verified reply.")==2 })
            empty.receive(renderTranscript()); empty.receive(renderDone())
            assertTrue(waitFor { empty.warned() }); assertTrue(empty.audio.isEmpty())
            assertEquals("Empty speech retries are bounded",2,empty.requestCount("Verified reply."))
        } finally { empty.close() }
    }

    @Test(timeout = 10000) fun geminiAggregatePcmLimitRejectsAnOversizedRender() = runBlocking {
        val f = RenderFixture()
        try {
            f.start()
            val chunk = java.util.Base64.getEncoder().encodeToString(ByteArray(192000))
            repeat(16) { f.receive(renderPcm(data = chunk)) }
            f.receive(renderDone())
            assertTrue(waitFor { f.warned() }); assertTrue(f.audio.isEmpty())
        } finally { f.close() }
    }

    @Test(timeout = 15000) fun otherProviderUnsuccessfulResponseDoneCannotReleaseMatchingAudio() = runBlocking {
        for (provider in listOf("grok", "openai")) for (status in listOf(null, "incomplete", "in_progress", "cancelled", "failed")) {
            val f = RenderFixture(provider)
            try {
                f.start(); f.receive(renderPcm(provider)); f.receive(renderTranscript(provider)); f.receive(renderDone(provider, status))
                withTimeoutOrNull(700) { while (f.audio.isEmpty() && !f.warned()) delay(10) }
                assertTrue("$provider status $status cannot complete a render", f.audio.isEmpty())
            } finally { f.close() }
        }
    }

    @Test(timeout = 15000) fun otherProviderToolItemsCannotAuthorizeACompletedRender() = runBlocking {
        val attempts = listOf(
            json("type" to "response.output_item.added", "item" to json("type" to "function_call", "call_id" to "call", "name" to "do_work")),
            json("type" to "response.function_call_arguments.delta", "item_id" to "item", "delta" to "{"),
            json("type" to "response.web_search_call.in_progress", "item_id" to "item"),
            json("type" to "response.done", "response" to json("status" to "completed", "output" to JSONArray().put(json("type" to "function_call", "call_id" to "call", "name" to "do_work", "arguments" to "{}")))),
            json("type" to "response.done", "response" to json("status" to "completed", "output" to JSONArray().put(json("type" to "web_search_call")))),
        )
        for (provider in listOf("grok", "openai")) for (attempt in attempts) {
            val f = RenderFixture(provider)
            try {
                f.start()
                if (attempt.optString("type") != "response.done") f.receive(attempt)
                f.receive(renderPcm(provider)); f.receive(renderTranscript(provider))
                f.receive(if (attempt.optString("type") == "response.done") attempt else renderDone(provider, "completed"))
                assertTrue(waitFor { f.audio.isNotEmpty() || f.warned() })
                assertTrue("A raw $provider function item must invalidate the render", f.audio.isEmpty())
            } finally { f.close() }
        }
    }

    @Test(timeout = 10000) fun otherProviderCompletedStatusAndMatchingTranscriptStillPlay() = runBlocking {
        for (provider in listOf("grok", "openai")) {
            val f = RenderFixture(provider)
            try {
                f.start(); f.receive(renderPcm(provider)); f.receive(renderTranscript(provider)); f.receive(renderDone(provider, "completed"))
                assertTrue("A completed $provider render with matching text remains supported", waitFor { f.audio.size == 1 })
            } finally { f.close() }
        }
    }

    @Test(timeout = 18000) fun otherProviderMissingTranscriptStillRetriesOnce() = runBlocking {
        for (provider in listOf("grok", "openai")) {
            val f = RenderFixture(provider)
            try {
                f.start(); f.receive(renderPcm(provider)); f.receive(renderDone(provider, "completed"))
                assertTrue("$provider retains its bounded missing-transcript retry", withTimeoutOrNull(6000) {
                    while (f.requestCount("Verified reply.") < 2) delay(10); true
                } == true)
                f.receive(renderPcm(provider)); f.receive(renderTranscript(provider)); f.receive(renderDone(provider, "completed"))
                assertTrue(waitFor { f.audio.size == 1 }); assertEquals(2, f.requestCount("Verified reply."))
            } finally { f.close() }
        }
    }

    @Test(timeout = 12000) fun grokPrematureCompletionsNeverSubmitUntilTheFullCommittedItem() = runBlocking {
        val f = Fixture("grok")
        fun event(type:String) = json("type" to type,"item_id" to "owner-1")
        fun transcript(text:String,status:String) = event("conversation.item.input_audio_transcription.completed")
            .put("content_index",0).put("transcript",text).put("status",status)
        try {
            f.start()
            f.receive(event("input_audio_buffer.speech_started"))
            for(text in listOf("Without","Without using any tools","Without using any tools what is two plus three?")) {
                f.receive(event("conversation.item.input_audio_transcription.updated").put("content_index",0).put("transcript",text))
                f.receive(transcript(text,"in_progress"))
            }
            f.receive(event("input_audio_buffer.speech_stopped"))
            val full="Without using any tools what is two plus three?"
            f.receive(transcript(full,"in_progress"))
            assertFalse("No precommit provider snapshot may reach Hermes",
                withTimeoutOrNull(1200) { while(f.prompts().isEmpty()) delay(10);true } == true)
            f.receive(event("input_audio_buffer.committed"))
            assertTrue("Commit alone does not invent a final transcript",f.prompts().isEmpty())
            f.receive(transcript(full,"completed"))
            assertTrue("The whole committed utterance reaches Hermes",waitFor { f.jobPosts.size==1 })
            assertEquals(1,f.prompts().size)
            assertEquals(full,f.prompts().single().getJSONObject("params").getString("text"))
            f.receive(transcript(full,"completed"))
            delay(150)
            assertEquals("A duplicate final must not replay the native mutation",1,f.prompts().size)
            assertEquals(1,f.jobPosts.size)
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun strictLegacyPromptOmitsCanonicalIdsAndLinksActualUserRow() = runBlocking {
        val f = Fixture()
        try {
            f.strictPromptContract = true
            f.start(); f.receive(geminiFinal("What is two plus three?"))
            assertTrue("The strict legacy handler must execute one request and link its row",
                waitFor { f.jobPosts.isNotEmpty() || f.rejectedPrompts.isNotEmpty() })
            assertTrue("A strict 4000 rejection means no legacy handler ran", f.rejectedPrompts.isEmpty())
            assertEquals(1, f.executedPrompts.size)
            val params = f.executedPrompts.single().getJSONObject("params")
            assertFalse(params.has("submission_id")); assertFalse(params.has("input_id"))
            assertEquals("runtime", params.getString("session_id"))
            assertEquals("What is two plus three?", params.getString("text"))
            assertEquals(1, f.jobPosts.size)
            assertEquals(101L, f.jobPosts.single().getLong("user_row_id"))
            assertEquals("runtime", f.jobPosts.single().getString("runtime_session_id"))
            assertEquals("stored", f.jobPosts.single().getString("stored_session_id"))
            val resumes = f.nativeFrames.filter { it.optString("method") == "session.resume" }
            assertTrue(resumes.isNotEmpty())
            assertTrue(resumes.all { !it.getJSONObject("params").getBoolean("close_on_disconnect") &&
                !it.getJSONObject("params").getBoolean("inline_images") })
            assertTrue(resumes.any { it.getJSONObject("params").optBoolean("eager_build") })
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun strictCanonicalResumeKeepsCanonicalSubmissionIdentity() = runBlocking {
        val f = Fixture(authority = true)
        try {
            f.strictCanonicalResume = true
            f.start(); f.receive(geminiFinal("Check this"))
            assertTrue("Canonical resume must accept only its actual fields before submitting",
                waitFor { f.prompts().isNotEmpty() || f.events.any { it.optString("message").contains("not sent") } })
            assertEquals(1, f.executedPrompts.size)
            val params = f.executedPrompts.single().getJSONObject("params")
            assertTrue(params.getString("submission_id").isNotBlank())
            assertEquals("stored", params.getString("session_id"))
            assertTrue(f.jobPosts.isEmpty())
            val resumes = f.nativeFrames.filter { it.optString("method") == "session.resume" }
            assertTrue(resumes.isNotEmpty())
            assertTrue(resumes.all { it.getJSONObject("params").keys().asSequence().all { key ->
                key in setOf("session_id","profile","source","omit_messages")
            } })
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun canonicalLazyRecoveryRefusesBeforeResume() = runBlocking {
        val f = Fixture(authority = true)
        try {
            assertTrue("The authority has no equivalent lazy recovery parameter",
                runCatching { f.client.nativeSession("stored", lazy = true) }.isFailure)
            assertTrue(f.nativeFrames.none { it.optString("method") == "session.resume" })
        } finally { f.close() }
    }

    @Test(timeout = 28000) fun coldNativeApprovalReadCanFinishAfterSeventeenSeconds() = runBlocking {
        val f = Fixture(stallAfterMs = 30000)
        try {
            f.strictPromptContract = true
            f.approvalDelayMs = 17000
            f.start(); f.receive(geminiFinal("Cold native request"))
            val outcome = withTimeoutOrNull(22000) {
                while (f.jobPosts.isEmpty() && f.rejectedPrompts.isEmpty() &&
                    f.events.none { it.optString("message").contains("not sent") }) delay(20)
                true
            }
            assertTrue("The native cold read must finish within the enclosing budget", outcome == true)
            assertEquals("A slow approval read must not become a failed preflight", 1, f.jobPosts.size)
            assertEquals(1, f.executedPrompts.size)
            assertEquals(1, f.nativeFrames.count { it.optString("method") == "approval.pending" })
            assertEquals(101L, f.jobPosts.single().getLong("user_row_id"))
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun permanentNative5032IsNotSentAndIsNotRetried() = runBlocking {
        val f = Fixture()
        try {
            f.approvalError = 5032
            f.start(); f.receive(geminiFinal("Do work"))
            assertTrue(waitFor { f.events.any { it.optString("message").contains("not sent") } })
            assertEquals(1, f.nativeFrames.count { it.optString("method") == "approval.pending" })
            assertTrue(f.prompts().isEmpty()); assertTrue(f.jobPosts.isEmpty())
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun legacyInvalidParamsIsDefinitelyNotSent() = runBlocking {
        val f = Fixture()
        try {
            f.promptError = 4000
            f.start(); f.receive(geminiFinal("Do work"))
            assertTrue(waitFor { f.events.any { it.optString("message").contains("not sent") ||
                it.optString("message").contains("could not confirm") } })
            assertEquals(1, f.rejectedPrompts.size)
            assertTrue("The rejected request cannot run a handler", f.executedPrompts.isEmpty())
            assertTrue(f.jobPosts.isEmpty())
            assertTrue("A legacy pre-handler validation refusal is definite", f.events.any { it.optString("message").contains("not sent") })
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun unclassifiedLegacy4000StaysUnknownAndNeverReplays() = runBlocking {
        val f = Fixture()
        try {
            f.promptError = 4000
            f.promptErrorMessage = "Some other failure"
            f.start(); f.receive(geminiFinal("Do work"))
            assertTrue(waitFor { f.events.any { it.optString("message").contains("could not confirm") } })
            assertEquals(1, f.prompts().size)
            assertTrue(f.jobPosts.isEmpty())
            assertTrue(f.events.none { it.optString("message").contains("not sent") })
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun canonical4000LookalikeStaysUnknown() = runBlocking {
        val f = Fixture(authority = true)
        try {
            f.promptError = 4000
            f.start(); f.receive(geminiFinal("Do work"))
            assertTrue(waitFor { f.events.any { it.optString("message").contains("could not confirm") } })
            assertEquals(1, f.prompts().size)
            assertTrue(f.jobPosts.isEmpty())
            assertTrue(f.events.none { it.optString("message").contains("not sent") })
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun lostPromptResponseRemainsUnknownAndNeverReplays() = runBlocking {
        val f = Fixture()
        try {
            f.dropPromptResponse = true
            f.start(); f.receive(geminiFinal("Do work"))
            assertTrue(waitFor { f.events.any { it.optString("message").contains("could not confirm") } })
            assertEquals(1, f.executedPrompts.size)
            assertEquals(1, f.prompts().size)
            assertTrue(f.jobPosts.isEmpty())
            assertTrue(f.events.none { it.optString("message").contains("not sent") })
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun finalOwnerSpeechIsSubmittedEvenWhenProviderNeverCallsAFunction() = runBlocking {
        val f = Fixture()
        try {
            f.start()
            f.receive(geminiFinal("What is in my calendar?"))
            assertTrue("A final utterance must be submitted without relying on a provider tool call", waitFor { f.prompts().size == 1 })
            val params = f.prompts().single().getJSONObject("params")
            assertEquals("What is in my calendar?", params.getString("text"))
            assertEquals("runtime", params.getString("session_id"))
            assertEquals("default", params.getString("profile"))
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun providerAnswerBeforeNativeResultCannotBecomeTheAssistantTranscriptOrAudio() = runBlocking {
        val f = Fixture()
        try {
            f.start()
            f.receive(geminiFinal("What is in my calendar?"))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Your calendar is empty.", "finished" to true))))
            f.receive(json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000", "data" to "AAE=")))))))
            delay(150)
            assertTrue("An unverified provider answer must not become an assistant caption", f.events.none { it.optString("role") == "assistant" && it.optString("text").contains("calendar is empty") })
            assertTrue("An unverified provider answer must not be played", f.audio.isEmpty())
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun repeatedProviderItemAfterReconnectIsNotResubmittedButANewIdenticalUtteranceIs() = runBlocking {
        val f = Fixture("openai")
        try {
            f.start()
            f.receive(openAiFinal("item-one", "Check that"))
            assertTrue("First final item must reach Hermes", waitFor { f.prompts().size == 1 })
            val (socket, listener) = f.inputSockets.first()
            listener.onFailure(socket, java.io.IOException("fixture disconnect"), null)
            assertTrue("Provider must reconnect", waitFor { f.inputSockets.size >= 2 })
            assertTrue("Provider must be admitted after reconnect", waitFor { f.events.count { it.optString("state") == "listening" } >= 2 })
            f.receive(openAiFinal("item-one", "Check that"))
            f.receive(openAiFinal("item-two", "Check that"))
            assertTrue("A different final item must be submitted", waitFor { f.prompts().size >= 2 })
            delay(200)
            assertEquals("Replayed item must not run twice", 2, f.prompts().size)
        } finally { f.close() }
    }


    private suspend fun hangupDuringHeldStageDoesNotSubmit(authority: Boolean) {
        val f = Fixture(authority = authority)
        try {
            f.holdStage = true; f.start(); f.voice.setVisualState("camera", "starting")
            val sent = CompletableDeferred<Unit>()
            assertTrue(f.voice.image(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()), { true }, onSent = { sent.complete(Unit) }))
            withTimeout(2000) { sent.await() }
            f.receive(geminiFinal("What is visible?"))
            withTimeout(3000) { f.stageHeld.await() }
            val job = f.pendingVoiceJob()
            val end = f.voice.close()
            f.releaseStage.complete(Unit)
            withTimeout(3000) { f.stageReleased.await(); job.join() }
            assertEquals("No new native prompt may cross hang-up", 0, f.prompts().size)
            assertTrue("An unsent staged request is reported as not sent", end.notSent == 1 && end.submissionUnknown == 0)
            assertTrue("Hang-up must not interrupt unrelated Hermes work", f.nativeFrames.none { it.optString("method") == "session.interrupt" })
            assertTrue(f.jobPosts.isEmpty())
        } finally { f.close() }
    }

    @Test(timeout = 15000) fun hangupDuringHeldLegacyFileStageNeverSubmits() = runBlocking {
        hangupDuringHeldStageDoesNotSubmit(false)
    }

    @Test(timeout = 15000) fun hangupDuringHeldCanonicalImageStageNeverSubmits() = runBlocking {
        hangupDuringHeldStageDoesNotSubmit(true)
    }

    @Test(timeout = 20000) fun heldStageStillSubmitsWhileCallLivesEvenIfOnlyFrameIsRevoked() = runBlocking {
        for (authority in listOf(false, true)) for (revoke in listOf(false, true)) {
            val f = Fixture(authority = authority); val allowed = java.util.concurrent.atomic.AtomicBoolean(true)
            try {
                f.holdStage = true; f.start(); f.voice.setVisualState("camera", "starting")
                val sent = CompletableDeferred<Unit>()
                assertTrue(f.voice.image(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()), { allowed.get() }, onSent = { sent.complete(Unit) }))
                withTimeout(2000) { sent.await() }; f.receive(geminiFinal("What is visible?"))
                withTimeout(3000) { f.stageHeld.await() }; val job = f.pendingVoiceJob()
                if (revoke) allowed.set(false)
                f.releaseStage.complete(Unit)
                withTimeout(3000) { f.stageReleased.await(); job.join() }
                assertEquals("An owned call submits exactly once", 1, f.prompts().size)
                val prompt = f.prompts().single().getJSONObject("params")
                if (revoke) {
                    assertFalse(prompt.has("attachments")); assertFalse(prompt.getString("text").contains("@file:"))
                    assertTrue(prompt.getString("text").contains("image could not be attached"))
                } else if (authority) assertTrue(prompt.has("attachments"))
                else assertTrue(prompt.getString("text").contains("@file:"))
            } finally { f.close() }
        }
    }

    @Test(timeout = 15000) fun hangupAfterPromptSendSettlesOneAcceptedAttempt() = runBlocking {
        for (authority in listOf(false, true)) {
            val f = Fixture(authority = authority)
            try {
                f.holdPrompt = true; f.start(); f.receive(geminiFinal("Do the task"))
                withTimeout(3000) { f.promptHeld.await() }; val job = f.pendingVoiceJob()
                val end = f.voice.close()
                f.releasePrompt.complete(Unit); withTimeout(3000) { job.join() }
                assertEquals(1, f.prompts().size)
                assertEquals(0, end.notSent); assertEquals(1, end.submissionUnknown)
                assertTrue(f.nativeFrames.none { it.optString("method") == "session.interrupt" })
                if (!authority) assertEquals("The already-sent legacy job is linked once", 1, f.jobPosts.size)
            } finally { f.close() }
        }
    }


    @Test(timeout = 15000) fun lostStageResponseNeverClaimsTheNativePromptWasSent() = runBlocking {
        for (authority in listOf(false, true)) {
            val f = Fixture(authority = authority)
            try {
                f.dropStageResponse = true; f.start(); f.voice.setVisualState("camera", "starting")
                assertTrue(f.voice.image(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), { true }))
                f.receive(geminiFinal("What is visible?"))
                assertTrue(waitFor { f.events.any { it.optString("message").contains("not sent") } })
                assertTrue(f.prompts().isEmpty()); assertTrue(f.jobPosts.isEmpty())
                assertTrue(f.events.none { it.optString("message").contains("could not confirm whether") })
            } finally { f.close() }
        }
    }


    @Test(timeout = 12000) fun serviceHangupClaimPreventsPromptWhileTranscriptFenceIsStillRunning() = runBlocking {
        for (authority in listOf(false, true)) {
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            val f = Fixture(authority = authority, ownerFence = closed, ownerActive = { !closed.get() })
            val fenceEntered = CompletableDeferred<Unit>(); val releaseFence = java.util.concurrent.CountDownLatch(1)
            try {
                f.holdStage = true; f.start(); f.voice.setVisualState("camera", "starting")
                assertTrue(f.voice.image(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), { true }))
                f.receive(geminiFinal("What is visible?")); withTimeout(3000) { f.stageHeld.await() }
                val job = f.pendingVoiceJob()
                val hangup = async(Dispatchers.Default) {
                    finishCallOnce(closed, { fenceEntered.complete(Unit); releaseFence.await(3, java.util.concurrent.TimeUnit.SECONDS) }) { f.voice.close() }
                }
                withTimeout(2000) { fenceEntered.await() }
                assertTrue(closed.get())
                f.releaseStage.complete(Unit); withTimeout(2000) { f.stageReleased.await(); job.join() }
                assertTrue("Service claim must fence prompts before transcript cleanup completes", f.prompts().isEmpty())
                releaseFence.countDown(); assertTrue(withTimeout(2000) { hangup.await() })
            } finally { releaseFence.countDown(); f.close() }
        }
    }

    @Test(timeout = 5000) fun lateVoiceInitializerDoesNotStartAfterServiceHangupClaim() = runBlocking {
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        assertTrue(finishCallOnce(closed) { })
        val f = Fixture(ownerFence = closed, ownerActive = { !closed.get() })
        try {
            f.voice.start(); delay(200)
            assertEquals("A late initializer cannot request native context", 0, f.contextRequests.get())
            assertTrue(f.providerSockets.isEmpty()); assertTrue(f.nativeFrames.isEmpty())
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun freshOptInFrameReachesTheNativeTurnAsRealImageBytes() = runBlocking {
        val f = Fixture()
        try {
            f.start(); f.voice.setVisualState("camera", "starting")
            val sent = CompletableDeferred<Unit>()
            val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
            assertTrue(f.voice.image(jpeg, { true }, onSent = { sent.complete(Unit) }))
            assertTrue("Captured frame was accepted", withTimeoutOrNull(2000) { sent.await(); true } == true)
            f.receive(geminiFinal("What is on my camera?"))
            assertTrue("Visual question must reach native Hermes", waitFor { f.prompts().size == 1 })
            val methods = f.nativeFrames.map { it.optString("method") }
            val attach = methods.indexOf("file.attach")
            assertTrue("Legacy Hermes must stage a file before prompt.submit", attach >= 0 && attach < methods.indexOf("prompt.submit"))
            val frame = f.nativeFrames[attach].getJSONObject("params")
            assertEquals("runtime", frame.getString("session_id"))
            val expected = java.util.Base64.getEncoder().encodeToString(jpeg)
            assertTrue(frame.getString("data_url").endsWith(expected))
            assertTrue(f.prompts().single().getJSONObject("params").getString("text").contains("@file:/profile/attachments/voice-frame.jpg"))
            assertFalse("Legacy staging must not enqueue an image for a different prompt", methods.contains("image.attach_bytes"))
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun authorityImageIsBoundAtomicallyToTheSameNativeAdmission() = runBlocking {
        val f = Fixture(authority = true)
        try {
            f.start(); f.voice.setVisualState("camera", "starting")
            val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
            assertTrue(f.voice.image(jpeg, { true }))
            f.receive(geminiFinal("What is on my camera?"))
            assertTrue(waitFor { f.prompts().size == 1 })
            val attach = f.nativeFrames.single { it.optString("method") == "image.attach_bytes" }.getJSONObject("params")
            assertEquals(java.util.Base64.getEncoder().encodeToString(jpeg), attach.getString("content_base64"))
            val prompt = f.prompts().single().getJSONObject("params")
            val image = prompt.getJSONArray("attachments").getJSONObject(0)
            assertEquals("stored", prompt.getString("session_id"))
            assertEquals("/profile/cache/images/voice-frame.jpg", image.getString("path"))
            assertEquals("image/jpeg", image.getString("mime"))
            assertTrue(prompt.getString("submission_id").isNotBlank())
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun revokedFrameIsNeverUploadedToHermes() = runBlocking {
        val f = Fixture(authority = true)
        try {
            f.start(); f.voice.setVisualState("camera", "starting")
            assertTrue(f.voice.image(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), { true }))
            f.voice.setVisualState("camera", "off")
            f.receive(geminiFinal("What is visible?"))
            assertTrue(waitFor { f.prompts().size == 1 })
            assertTrue(f.nativeFrames.none { it.optString("method") in setOf("image.attach_bytes", "file.attach") })
            assertFalse(f.prompts().single().getJSONObject("params").has("attachments"))
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun onlyMatchingAudioForAnOwnedCanonicalReplyIsReleased() = runBlocking {
        val f = Fixture(authority = true)
        try {
            f.start(); f.receive(geminiFinal("Check this"))
            assertTrue(waitFor { f.prompts().size == 1 })
            f.admission = json("status" to "terminal", "outcome" to "completed", "admission_id" to "admission-1", "ref" to json("session_id" to "stored"), "result" to json("final_response" to "Hermes verified it."))
            assertTrue("Verified native result should create a separate rendering session", withTimeoutOrNull(6000) { while(f.renderRequestCount("Hermes verified it.")==0) delay(10); true } == true)
            f.receiveRender(json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000", "data" to "AAE=")))))))
            f.receiveRender(json("serverContent" to json("outputTranscription" to json("text" to "Hermes verified it.", "finished" to true))))
            f.receiveRender(json("serverContent" to json("turnComplete" to true)))
            assertTrue("Only the checked render may play", waitFor { f.audio.size == 1 })
            assertArrayEquals(byteArrayOf(0, 1), f.audio.single().copyOfRange(0,2))
            assertTrue(f.audio.single().drop(2).all { it == 0.toByte() })
        } finally { f.close() }
    }

    private suspend fun checkNativeSpeech(expected: String, spoken: String, playable: Boolean) {
        val f = Fixture(authority = true)
        try {
            f.start(); f.receive(geminiFinal("Check this"))
            assertTrue(waitFor { f.prompts().size == 1 })
            f.admission = json("status" to "terminal", "outcome" to "completed", "admission_id" to "admission-1", "ref" to json("session_id" to "stored"), "result" to json("final_response" to expected))
            assertTrue(withTimeoutOrNull(6000) { while(f.renderRequestCount(expected)==0) delay(10); true } == true)
            f.receiveRender(json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000", "data" to "AAE=")))))))
            f.receiveRender(json("serverContent" to json("outputTranscription" to json("text" to spoken))))
            f.receiveRender(json("serverContent" to json("turnComplete" to true)))
            if (playable) {
                assertTrue("Unspoken quotation marks must not silence the native reply", waitFor { f.audio.isNotEmpty() })
                assertTrue(waitFor { f.events.any { it.optString("role")=="assistant" && it.optString("text")==expected } })
            } else {
                val (socket,listener)=f.renderSockets().last();listener.onClosed(socket,1000,"Fixture")
                assertTrue(waitFor { f.events.any { it.optString("message").contains("could not safely read") } })
                assertTrue(f.audio.isEmpty())
            }
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun balancedTypographicQuotesAreUnspokenWithoutChangingTheNativeCaption() = runBlocking {
        checkNativeSpeech("One skill is \u201cweather\u201d.", "One skill is weather.", true)
    }

    @Test(timeout = 50000) fun quotationMatchingStillRejectsChangedWordsNumbersSignsAndUnits() = runBlocking {
        listOf(
            "Use \u201cweather\u201d." to "Use calendar.",
            "Use \u201cminus -1.5\u201d." to "Use minus +1.5.",
            "Use \u201c1.5\u201d." to "Use 15.",
            "O\u2019Connor." to "OConnor.",
            "Use 5\u201d." to "Use 5.",
            "Use \u201cweather." to "Use weather.",
        ).forEach { (expected,spoken) -> checkNativeSpeech(expected,spoken,false) }
    }

    @Test(timeout = 12000) fun rendererThatAddsWordsIsSilenced() = runBlocking {
        val f = Fixture(authority = true)
        try {
            f.start(); f.receive(geminiFinal("Check this"))
            assertTrue(waitFor { f.prompts().size == 1 })
            f.admission = json("status" to "terminal", "outcome" to "completed", "admission_id" to "admission-1", "ref" to json("session_id" to "stored"), "result" to json("final_response" to "Hermes verified it."))
            assertTrue(withTimeoutOrNull(6000) { while(f.renderRequestCount("Hermes verified it.")==0) delay(10); true } == true)
            f.receiveRender(json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000", "data" to "AAE=")))))))
            f.receiveRender(json("serverContent" to json("outputTranscription" to json("text" to "Hermes verified it and sent the money."))))
            f.receiveRender(json("serverContent" to json("turnComplete" to true)))
            val (socket,listener)=f.renderSockets().last();listener.onClosed(socket,1000,"Fixture")
            assertTrue(waitFor { f.events.any { it.optString("message").contains("could not safely read") } })
            assertTrue(f.audio.isEmpty())
        } finally { f.close() }
    }

    @Test fun geminiExplicitFinalityNeverDependsOnTheModelName() {
        val turns=VoiceFinalItems()
        assertNull(turns.accept(json("text" to "hel"),true))
        assertEquals("hello",turns.accept(json("text" to "lo", "finished" to true),true)?.second)
        val partial=VoiceFinalItems()
        assertNull(partial.accept(json("text" to "hel", "finished" to false),true))
        assertEquals("hello",partial.accept(json("text" to "lo", "finished" to true),true)?.second)
    }

    @Test(timeout = 8000) fun nativeWorkDoesNotTriggerTheSpeechProviderStallWatch() = runBlocking {
        val f=Fixture(authority=true,stallAfterMs=300)
        try {
            f.start()
            val speech=ByteArray(640).also { for(i in it.indices step 2) { it[i]=(8000 and 255).toByte();it[i+1]=(8000 shr 8).toByte() } }
            repeat(40) { f.voice.pcm(speech) { true };delay(20) }
            f.receive(geminiFinal("Long native task"))
            assertTrue(waitFor { f.prompts().size==1 })
            delay(1200)
            assertEquals("A pending native admission is not a stalled speech provider",1,f.inputSockets.size)
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun interruptionDiscardsAFormerRenderGeneration() = runBlocking {
        val f=Fixture(authority=true)
        try {
            f.start();f.receive(geminiFinal("Check this"));assertTrue(waitFor { f.prompts().size==1 })
            f.admission=json("status" to "terminal","outcome" to "completed","admission_id" to "admission-1","ref" to json("session_id" to "stored"),"result" to json("final_response" to "Hermes verified it."))
            assertTrue(withTimeoutOrNull(6000) { while(f.renderRequestCount("Hermes verified it.")==0) delay(10);true }==true)
            f.receiveRender(json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000","data" to "AAE=")))))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receiveRender(json("serverContent" to json("outputTranscription" to json("text" to "Hermes verified it."),"turnComplete" to true)))
            delay(150);assertTrue(f.audio.isEmpty())
        } finally { f.close() }
    }

    @Test(timeout = 18000) fun pendingUncertaintyCanRecoverToAVerifiedNativeReply() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(geminiFinal("Check the long task"))
            assertTrue(waitFor { f.jobPosts.size==1 })
            val requestId=f.jobPosts.single().getString("request_id")
            f.jobs=JSONArray().put(json("job_id" to "job-1","request_id" to requestId,"stored_session_id" to "stored","runtime_session_id" to "runtime",
                "status" to "uncertain","error_code" to "native_turn_pending_confirmation","will_continue" to true))
            val pendingText="Your request is still pending; its outcome is not yet confirmed."
            assertTrue(withTimeoutOrNull(6000) { while(f.renderRequestCount(pendingText)==0) delay(10);true }==true)
            f.receiveRender(json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000","data" to "AAE=")))))))
            f.receiveRender(json("serverContent" to json("outputTranscription" to json("text" to pendingText),"turnComplete" to true)))
            assertTrue(waitFor { f.audio.size==1 })
            f.jobs=JSONArray().put(json("job_id" to "job-1","request_id" to requestId,"stored_session_id" to "stored","runtime_session_id" to "runtime",
                "status" to "done","result" to "A verified final reply."))
            assertTrue("A later verified result must still be rendered",withTimeoutOrNull(6000) { while(f.renderRequestCount("A verified final reply.")==0) delay(10);true }==true)
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun bargeInDoesNotCaptionUnplayedAudioOrBlockTheNextRender() = runBlocking {
        val f=Fixture(authority=true)
        try {
            f.autoPlay=false;f.start();f.receive(geminiFinal("Check this"));assertTrue(waitFor { f.prompts().size==1 })
            f.admission=json("status" to "terminal","outcome" to "completed","admission_id" to "admission-1","ref" to json("session_id" to "stored"),"result" to json("final_response" to "Hermes verified it."))
            assertTrue(withTimeoutOrNull(6000) { while(f.renderRequestCount("Hermes verified it.")==0) delay(10);true }==true)
            f.receiveRender(json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000","data" to "AAE=")))))))
            f.receiveRender(json("serverContent" to json("outputTranscription" to json("text" to "Hermes verified it."),"turnComplete" to true)))
            assertTrue(waitFor { f.audio.size==1 })
            assertTrue(f.events.none { it.optString("role")=="assistant" && it.optString("text")=="Hermes verified it." })
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(geminiFinal("repeat that"))
            assertTrue("Interrupted playback must not hold the next render",waitFor { f.renderRequestCount("Hermes verified it.")>=2 })
            assertTrue(f.events.none { it.optString("role")=="assistant" && it.optString("text")=="Hermes verified it." })
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun playbackIsBoundedAndCaptionWaitsForPresentedPcm() = runBlocking {
        val f=Fixture(authority=true)
        try {
            f.autoPlay=false;f.start();f.receive(geminiFinal("Check this"));assertTrue(waitFor { f.prompts().size==1 })
            f.admission=json("status" to "terminal","outcome" to "completed","admission_id" to "admission-1","ref" to json("session_id" to "stored"),"result" to json("final_response" to "Hermes verified it."))
            assertTrue(withTimeoutOrNull(6000) { while(f.renderRequestCount("Hermes verified it.")==0) delay(10);true }==true)
            val pcm=java.util.Base64.getEncoder().encodeToString(ByteArray(48000))
            repeat(3) { f.receiveRender(json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000","data" to pcm))))))) }
            f.receiveRender(json("serverContent" to json("outputTranscription" to json("text" to "Hermes verified it."),"turnComplete" to true)))
            assertTrue(waitFor { f.audio.size==2 });delay(100)
            assertEquals("At most two seconds can be outstanding",2,f.audio.size)
            assertTrue(f.events.none { it.optString("role")=="assistant" && it.optString("text")=="Hermes verified it." })
            f.voice.played(48000);assertTrue(waitFor { f.audio.size==3 })
            assertTrue(f.audio.all { it.size<=48000 })
            f.voice.played(96000)
            assertTrue(waitFor { f.events.any { it.optString("role")=="assistant" && it.optString("text")=="Hermes verified it." } })
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun unsupportedLegacyFileAttachmentSubmitsWithAnHonestVisualNotice() = runBlocking {
        val f=Fixture()
        try {
            f.fileUnavailable=true;f.start();f.voice.setVisualState("screen","starting")
            assertTrue(f.voice.image(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte()),{ true }))
            f.receive(geminiFinal("What is on my screen?"));assertTrue(waitFor { f.prompts().size==1 })
            val params=f.prompts().single().getJSONObject("params")
            assertTrue(params.getString("text").contains("image could not be attached"))
            assertFalse(params.has("attachments"))
            assertTrue(waitFor { f.events.any { it.optString("message").contains("could not be attached") } })
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun revocationDuringStagingDoesNotBindTheOldImageToTheTurn() = runBlocking {
        val f=Fixture(authority=true);val allowed=java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            f.start();f.voice.setVisualState("camera","starting")
            assertTrue(f.voice.image(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte()),{ allowed.get() }))
            f.onStage={ allowed.set(false) }
            f.receive(geminiFinal("What is visible?"));assertTrue(waitFor { f.prompts().size==1 })
            val params=f.prompts().single().getJSONObject("params")
            assertFalse(params.has("attachments"));assertTrue(params.getString("text").contains("image could not be attached"))
        } finally { f.close() }
    }


    @Test(timeout = 15000) fun uncertainContinuingNativeJobRestartsTheLostCollectorWithoutAnotherPrompt() = runBlocking {
        val f = Fixture()
        try {
            f.start(); f.receive(geminiFinal("First request")); assertTrue(waitFor { f.jobPosts.size == 1 })
            val requestId = f.jobPosts.single().getString("request_id")
            f.jobs = JSONArray().put(json("job_id" to "job-1", "request_id" to requestId, "stored_session_id" to "stored",
                "runtime_session_id" to "runtime", "status" to "uncertain", "will_continue" to true))
            val (socket, listener) = f.nativeSockets.last()
            listener.onFailure(socket, java.io.IOException("fixture loss"), null)
            assertTrue("A continuing uncertain job must recover its collector", withTimeoutOrNull(6500) {
                while (f.nativeSockets.size < 2) delay(20)
                true
            } == true)
            assertEquals("Rejoining must not resubmit the accepted prompt", 1, f.prompts().size)
        } finally { f.close() }
    }


    @Test(timeout = 12000) fun terminalUncertainNativeJobDoesNotRestartTheLostCollector() = runBlocking {
        val f = Fixture()
        try {
            f.start(); f.receive(geminiFinal("First request")); assertTrue(waitFor { f.jobPosts.size == 1 })
            val requestId = f.jobPosts.single().getString("request_id")
            f.jobs = JSONArray().put(json("job_id" to "job-1", "request_id" to requestId, "stored_session_id" to "stored",
                "runtime_session_id" to "runtime", "status" to "uncertain", "will_continue" to false))
            val (socket, listener) = f.nativeSockets.last()
            listener.onFailure(socket, java.io.IOException("fixture loss"), null)
            delay(3800)
            assertEquals("Terminal uncertainty does not restart a collector", 1, f.nativeSockets.size)
            assertEquals(1, f.prompts().size)
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun aFastFollowupWaitsUntilThePreviousWatchSettles() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(geminiFinal("First request"));assertTrue(waitFor { f.jobPosts.size==1 })
            val requestId=f.jobPosts.single().getString("request_id")
            f.jobs=JSONArray().put(json("job_id" to "job-1","request_id" to requestId,"stored_session_id" to "stored","runtime_session_id" to "runtime",
                "status" to "uncertain","error_code" to "native_turn_pending_confirmation","will_continue" to true))
            f.receive(geminiFinal("Next request"));delay(1400)
            assertEquals("A still-live watch must not receive an unlinked second prompt",1,f.prompts().size)
            f.jobs=JSONArray().put(json("job_id" to "job-1","request_id" to requestId,"stored_session_id" to "stored","runtime_session_id" to "runtime","status" to "done","result" to "First result."))
            assertTrue("The follow-up may start after the watch settles",waitFor { f.prompts().size==2 })
            assertEquals("Next request",f.prompts().last().getJSONObject("params").getString("text"))
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun rowBoundLinkageAllowsAnIndependentFollowupDuringAnOlderUnconfirmedWatch() = runBlocking {
        val f=Fixture()
        try {
            f.rowBound=true;f.start();f.receive(geminiFinal("First request"));assertTrue(waitFor { f.jobPosts.size==1 })
            val requestId=f.jobPosts.single().getString("request_id")
            f.jobs=JSONArray().put(json("job_id" to "job-1","request_id" to requestId,"stored_session_id" to "stored","runtime_session_id" to "runtime",
                "status" to "uncertain","error_code" to "native_turn_pending_confirmation","will_continue" to true))
            f.receive(geminiFinal("Next request"))
            assertTrue("Row-bound linkage must not strand a distinct prompt",waitFor { f.jobPosts.size==2 })
            assertEquals(2,f.prompts().size)
            assertNotEquals(f.jobPosts.first().getLong("user_row_id"),f.jobPosts.last().getLong("user_row_id"))
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun microphoneProviderHasNoToolRouteOrAutomaticOpenAiReply() = runBlocking {
        for(provider in listOf("gemini","openai")) {
            val f=Fixture(provider)
            try {
                f.start()
                val input=f.providerFrames.first { frame ->
                    val instruction=frame.optJSONObject("setup")?.optJSONObject("systemInstruction")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                        ?: frame.optJSONObject("session")?.optString("instructions").orEmpty()
                    instruction.startsWith("Transcribe")
                }
                if(provider=="gemini") assertFalse(input.getJSONObject("setup").has("tools"))
                else {
                    val session=input.getJSONObject("session")
                    assertEquals(0,session.getJSONArray("tools").length());assertEquals("none",session.getString("tool_choice"))
                    assertFalse(session.getJSONObject("audio").getJSONObject("input").getJSONObject("turn_detection").getBoolean("create_response"))
                }
            } finally { f.close() }
        }
    }

    @Test fun finalItemLedgerRejectsOverflowInsteadOfForgettingReplayIdentity() {
        val ledger=VoiceFinalItems()
        repeat(512) { i -> assertNotNull(ledger.accept(json("text" to "request", "final" to true, "item_id" to "item-$i"),false)) }
        assertTrue(runCatching { ledger.accept(json("text" to "request", "final" to true, "item_id" to "overflow"),false) }.exceptionOrNull() is VoiceInputLimit)
        assertNull(ledger.accept(json("text" to "request", "final" to true, "item_id" to "item-0"),false))
    }

    @Test(timeout = 8000) fun fullLegacyJobTableRefusesBeforeImageStagingOrNativeMutation() = runBlocking {
        val f=Fixture()
        try {
            f.rowBound=true
            f.jobs=JSONArray().apply { repeat(64) { put(json("job_id" to "old-$it","status" to "done","runtime_session_id" to "runtime")) } }
            f.start();f.voice.setVisualState("camera","starting")
            assertTrue(f.voice.image(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte()),{ true }))
            f.receive(geminiFinal("Check this"))
            assertTrue(waitFor { f.prompts().isNotEmpty() || f.events.any { it.optString("message").contains("work limit") } })
            assertEquals("A full legacy call must refuse before prompt.submit",0,f.prompts().size)
            assertTrue("A refused turn must not stage image bytes",f.nativeFrames.none { it.optString("method") in setOf("file.attach","image.attach_bytes") })
            assertTrue(f.jobPosts.isEmpty())
            assertTrue(f.events.any { it.optString("message").contains("new call") })
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun canonicalAdmissionDoesNotConsumeLegacyJobTableCapacity() = runBlocking {
        val f=Fixture(authority=true)
        try {
            f.rowBound=true;f.canonicalAlsoRow=true
            f.jobs=JSONArray().apply { repeat(64) { put(json("job_id" to "old-$it","status" to "done","runtime_session_id" to "stored")) } }
            f.start();f.receive(geminiFinal("Check this"))
            assertTrue("Canonical admission uses its actual admission receipt",waitFor { f.prompts().size==1 })
            @Suppress("UNCHECKED_CAST")
            val receipts=DirectVoice::class.java.getDeclaredField("toolReceipts").apply { isAccessible=true }.get(f.voice) as Map<String,JSONObject>
            assertTrue("The exact admission must be retained before checking for a job POST",waitFor {
                synchronized(receipts) { receipts.values.any { it.optString("admission_id")=="admission-1" && it.optString("runtime_session_id")=="stored" } }
            })
            assertTrue(f.jobPosts.isEmpty())
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun delayedGatewayReadyNeverFallsBackToLegacyStaging() = runBlocking {
        val f=Fixture(authority=true,sendGatewayReady=false)
        try {
            f.start();f.voice.setVisualState("camera","starting")
            assertTrue(f.voice.image(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte()),{ true }))
            f.receive(geminiFinal("What is visible?"))
            assertTrue(waitFor { f.nativeSockets.size==1 })
            delay(1800)
            assertTrue("Before gateway.ready neither resume, image nor prompt may be sent",f.nativeFrames.none { it.optString("method") in setOf("session.resume","file.attach","image.attach_bytes","prompt.submit") })
            f.gatewayReady(0,true)
            assertTrue("The same socket's canonical ready permits an atomic image turn",waitFor { f.prompts().size==1 })
            assertTrue(f.nativeFrames.any { it.optString("method")=="image.attach_bytes" })
            assertTrue(f.nativeFrames.none { it.optString("method")=="file.attach" })
            assertTrue(f.prompts().single().getJSONObject("params").has("attachments"))
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun missingGatewayReadyRefusesBeforeAnyNativeMutation() = runBlocking {
        val f=Fixture(authority=true,sendGatewayReady=false)
        try {
            f.start();f.voice.setVisualState("camera","starting")
            assertTrue(f.voice.image(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte()),{ true }))
            f.receive(geminiFinal("What is visible?"))
            val settled=withTimeoutOrNull(6500) { while(f.prompts().isEmpty() && f.events.none { it.optString("message").contains("not sent") }) delay(10);true }==true
            assertTrue("An unknown ready state must end with an honest refusal",settled)
            assertTrue("No resume, image staging or prompt.submit is safe without gateway.ready",f.nativeFrames.none { it.optString("method") in setOf("session.resume","file.attach","image.attach_bytes","prompt.submit") })
        } finally { f.close() }
    }

    @Test(timeout = 14000) fun staleGatewayReadyCannotAuthorizeAReplacementSocket() = runBlocking {
        val f=Fixture(authority=true,sendGatewayReady=false)
        try {
            f.start();f.voice.setVisualState("camera","starting")
            assertTrue(f.voice.image(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte()),{ true }))
            f.receive(geminiFinal("First request"))
            assertTrue(waitFor { f.nativeSockets.size==1 })
            delay(100)
            val (oldSocket,oldListener)=f.nativeSockets[0]
            oldListener.onFailure(oldSocket,java.io.IOException("Fixture disconnect"),null)
            f.gatewayReady(0,true)
            f.receive(geminiFinal("Second request"))
            assertTrue(waitFor { f.nativeSockets.size>=2 })
            delay(1800)
            assertTrue("An old socket's ready event must not authorize a replacement",f.nativeFrames.none { it.optString("method") in setOf("session.resume","file.attach","image.attach_bytes","prompt.submit") })
            f.gatewayReady(1,true)
            assertTrue(waitFor { f.prompts().size==1 })
            assertEquals("Second request",f.prompts().single().getJSONObject("params").getString("text"))
            assertTrue(f.prompts().single().getJSONObject("params").has("attachments"))
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun unflaggedGeminiFragmentsWaitForAnExplicitFinalTranscription() = runBlocking {
        val f=Fixture()
        try {
            f.start()
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Delete"))))
            assertFalse("The first unflagged fragment must not submit",waitFor { f.prompts().isNotEmpty() })
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to " all drafts"))))
            assertFalse("The second unflagged fragment is still not a final turn",waitFor { f.prompts().isNotEmpty() })
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "", "finished" to true))))
            assertTrue("A transcription-final boundary must submit the full turn",waitFor { f.prompts().size==1 })
            assertEquals("Delete all drafts",f.prompts().single().getJSONObject("params").getString("text"))
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun modelTurnCompleteQuarantinesUnsegmentedLateInput() = runBlocking {
        val f=Fixture()
        try {
            f.start()
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Delete"))))
            f.receive(json("serverContent" to json("turnComplete" to true)))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to " all drafts"))))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "", "finished" to true))))
            assertFalse("An unowned late fragment must never be combined into a guessed request",waitFor { f.prompts().isNotEmpty() })
            assertTrue(f.events.any { it.optString("message").contains("not sent") })
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun closedGeminiSegmentWithMatchingEchoSubmitsOnlyTheFullInputOnce() = runBlocking {
        val f=Fixture()
        try {
            f.start()
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Review "))))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "my notes"))))
            assertFalse("Unflagged fragments are not committed",waitFor { f.prompts().isNotEmpty() })
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Review my notes"))))
            f.receive(json("serverContent" to json("generationComplete" to true)))
            assertTrue(waitFor { f.prompts().size==1 })
            assertEquals("Review my notes",f.prompts().single().getJSONObject("params").getString("text"))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Review my notes"),"turnComplete" to true)))
            delay(200);assertEquals(1,f.prompts().size)
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun geminiEchoCanArriveBeforeInputButNeedsClosedActivityAndCompletedOutput() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Check the calendar"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Check the calendar"))))
            assertFalse(waitFor { f.prompts().isNotEmpty() })
            f.receive(json("serverContent" to json("generationComplete" to true)))
            assertTrue(waitFor { f.prompts().size==1 })
            assertEquals("Check the calendar",f.prompts().single().getJSONObject("params").getString("text"))
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun mismatchedGeminiEchoNeverSubmitsPartialOrAlteredNumbers() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Pay 1.5"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Pay 15"),"generationComplete" to true)))
            assertFalse("A mismatch cannot become a native request",waitFor { f.prompts().isNotEmpty() })
            assertTrue(waitFor { f.events.any { it.optString("message").contains("not sent") } })
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun newGeminiActivityBeforeCompletionNeverCombinesTwoUtterances() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Delete"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to " all drafts"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Delete all drafts"),"turnComplete" to true)))
            assertFalse("Ambiguous overlapped utterances must not mutate Hermes",waitFor { f.prompts().isNotEmpty() })
            assertTrue(f.events.any { it.optString("message").contains("not sent") })
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun lateGeminiInputAfterSubmissionCannotCreateAnotherTurn() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Open calendar"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Open calendar"),"generationComplete" to true)))
            assertTrue(waitFor { f.prompts().size==1 })
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to " and send an invite"))))
            delay(500);assertEquals("Late unscoped data is not a second user turn",1,f.prompts().size)
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun explicitFinalAndLaterMatchingEchoCannotSubmitTheSameGeminiSegmentTwice() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(geminiFinal("Review notes"));assertTrue(waitFor { f.prompts().size==1 })
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Review notes"),"generationComplete" to true)))
            delay(500);assertEquals(1,f.prompts().size)
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun unsegmentedGeminiOutputNeverAuthorizesARequest() = runBlocking {
        val f=Fixture()
        try {
            f.start()
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Delete all drafts"))))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Delete all drafts"),"turnComplete" to true)))
            assertFalse("A model turn boundary without an owned closed segment is insufficient",waitFor { f.prompts().isNotEmpty() })
            assertTrue(f.events.any { it.optString("message").contains("not sent") })
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun spokenNativeReplyAcceptsOnlyCanonicalUnicodeAndWhitespaceEquivalence() = runBlocking {
        val f=Fixture(authority=true)
        try {
            f.start();f.receive(geminiFinal("Check this"));assertTrue(waitFor { f.prompts().size==1 })
            f.admission=json("status" to "terminal","outcome" to "completed","admission_id" to "admission-1","ref" to json("session_id" to "stored"),"result" to json("final_response" to "Café balance"))
            assertTrue(withTimeoutOrNull(6000) { while(f.renderRequestCount("Caf\u00e9 balance")==0) delay(10);true }==true)
            f.receiveRender(json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000","data" to "AAE=")))))))
            f.receiveRender(json("serverContent" to json("outputTranscription" to json("text" to "Café balance"),"turnComplete" to true)))
            assertTrue("Canonical Unicode and spacing describe the same text",waitFor { f.audio.size==1 })
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun matchingRenderTranscriptBeforeTurnCompleteStillReleasesAudio() = runBlocking {
        val f=Fixture(authority=true)
        try {
            f.start();f.receive(geminiFinal("Check this"));assertTrue(waitFor { f.prompts().size==1 })
            f.admission=json("status" to "terminal","outcome" to "completed","admission_id" to "admission-1","ref" to json("session_id" to "stored"),"result" to json("final_response" to "Verified reply."))
            assertTrue(withTimeoutOrNull(6000) { while(f.renderRequestCount("Verified reply.")==0) delay(10);true }==true)
            f.receiveRender(json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000","data" to "AAE=")))))))
            f.receiveRender(json("serverContent" to json("outputTranscription" to json("text" to "Verified reply."))))
            f.receiveRender(json("serverContent" to json("generationComplete" to true)))
            delay(150);assertTrue("PCM must wait for the turn to complete",f.audio.isEmpty())
            f.receiveRender(json("serverContent" to json("turnComplete" to true)))
            assertTrue("A matching supplied transcription permits the completed render",waitFor { f.audio.size==1 })
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun mismatchedRenderTranscriptBeforeTurnCompleteNeverPlaysPcm() = runBlocking {
        val f=Fixture(authority=true)
        try {
            f.start();f.receive(geminiFinal("Check this"));assertTrue(waitFor { f.prompts().size==1 })
            f.admission=json("status" to "terminal","outcome" to "completed","admission_id" to "admission-1","ref" to json("session_id" to "stored"),"result" to json("final_response" to "Verified reply."))
            assertTrue(withTimeoutOrNull(6000) { while(f.renderRequestCount("Verified reply.")==0) delay(10);true }==true)
            f.receiveRender(json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000","data" to "AAE=")))))))
            f.receiveRender(json("serverContent" to json("generationComplete" to true)))
            f.receiveRender(json("serverContent" to json("outputTranscription" to json("text" to "Verified reply and extra words."))))
            f.receiveRender(json("serverContent" to json("turnComplete" to true)))
            assertTrue(waitFor { f.events.any { it.optString("message").contains("could not safely read") } })
            assertTrue("A mismatched transcript never releases PCM",f.audio.isEmpty())
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun geminiInputCanArriveAfterTheClosedSegmentGenerationCompletes() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Check notes"))))
            f.receive(json("serverContent" to json("generationComplete" to true)))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Check notes"))))
            assertTrue("The independently delivered input must reach Hermes once",waitFor { f.prompts().size==1 })
            assertEquals("Check notes",f.prompts().single().getJSONObject("params").getString("text"))
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun geminiRendererUsesRealtimeTextForItsIsolatedSpeechRequest() = runBlocking {
        val f=Fixture()
        try {
            f.start()
            assertTrue("The selected Gemini render session must receive realtime text",waitFor {
                f.providerFrames.any { it.optJSONObject("realtimeInput")?.optString("text")=="I'm here." }
            })
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun completedGeminiRenderWithoutTranscriptDoesNotRetry() = runBlocking {
        val f=Fixture(authority=true)
        try {
            f.start();f.receive(geminiFinal("Check this"));assertTrue(waitFor { f.prompts().size==1 })
            f.admission=json("status" to "terminal","outcome" to "completed","admission_id" to "admission-1","ref" to json("session_id" to "stored"),"result" to json("final_response" to "Verified reply."))
            assertTrue(withTimeoutOrNull(6000) { while(f.renderRequestCount("Verified reply.")==0) delay(10);true }==true)
            f.receiveRender(json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000","data" to "AAE=")))))))
            f.receiveRender(json("serverContent" to json("turnComplete" to true)))
            assertTrue(waitFor { f.audio.size==1 });delay(100)
            assertEquals(1,f.renderRequestCount("Verified reply."))
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun completedGeminiRenderWithoutPcmRetriesSpeechOnceWithoutRepeatingNativeWork() = runBlocking {
        val f=Fixture(authority=true)
        try {
            f.start();f.receive(geminiFinal("Check this"));assertTrue(waitFor { f.prompts().size==1 })
            f.admission=json("status" to "terminal","outcome" to "completed","admission_id" to "admission-1","ref" to json("session_id" to "stored"),"result" to json("final_response" to "Verified reply."))
            assertTrue(withTimeoutOrNull(6000) { while(f.renderRequestCount("Verified reply.")==0) delay(10);true }==true)
            f.receiveRender(json("serverContent" to json("turnComplete" to true)))
            assertTrue(waitFor { f.renderRequestCount("Verified reply.")==2 })
            assertTrue(f.audio.isEmpty());assertEquals(1,f.prompts().size)
            f.receiveRender(json("serverContent" to json("turnComplete" to true)))
            assertTrue(withTimeoutOrNull(6000) { while(f.events.none { it.optString("message").contains("could not safely read") }) delay(10);true }==true)
            assertEquals(2,f.renderRequestCount("Verified reply."));assertTrue(f.audio.isEmpty())
            assertEquals("A speech retry never repeats the Hermes request",1,f.prompts().size)
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun aNonemptyMismatchedRenderDoesNotRetryOrPlay() = runBlocking {
        val f=Fixture(authority=true)
        try {
            f.start();f.receive(geminiFinal("Check this"));assertTrue(waitFor { f.prompts().size==1 })
            f.admission=json("status" to "terminal","outcome" to "completed","admission_id" to "admission-1","ref" to json("session_id" to "stored"),"result" to json("final_response" to "Verified reply."))
            assertTrue(withTimeoutOrNull(6000) { while(f.renderRequestCount("Verified reply.")==0) delay(10);true }==true)
            f.receiveRender(json("serverContent" to json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000","data" to "AAE=")))))))
            f.receiveRender(json("serverContent" to json("outputTranscription" to json("text" to "A different answer."),"turnComplete" to true)))
            val (socket,listener)=f.renderSockets().last();listener.onClosed(socket,1000,"Fixture")
            assertTrue(waitFor { f.events.any { it.optString("message").contains("could not safely read") } })
            assertEquals(1,f.renderRequestCount("Verified reply."));assertTrue(f.audio.isEmpty())
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun unflaggedFragmentAfterStandaloneFinalCannotBecomeAnotherNativeTurn() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(geminiFinal("Review notes"));assertTrue(waitFor { f.prompts().size==1 })
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to " and delete them"))))
            f.receive(geminiFinal(""))
            assertFalse("Late unsegmented text is not a second request",waitFor { f.prompts().size>1 })
            assertTrue(f.events.any { it.optString("message").contains("not sent") })
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun freshGeminiActivityAfterStandaloneFinalCanStartANewSegment() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(geminiFinal("Review notes"));assertTrue(waitFor { f.prompts().size==1 })
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("interrupted" to true,"turnComplete" to true)))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Open calendar"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Open calendar"),"generationComplete" to true)))
            assertTrue(waitFor { f.prompts().size==2 })
            assertEquals("Open calendar",f.prompts().last().getJSONObject("params").getString("text"))
        } finally { f.close() }
    }

    @Test(timeout = 10000) fun matchingGeminiGenerationCompletesBeforePlaybackTurnEnds() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Check calendar"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Check calendar"))))
            f.receive(json("serverContent" to json("generationComplete" to true)))
            assertTrue("A matching completed generation must not wait for provider playback",waitFor { f.prompts().size==1 })
            assertEquals("Check calendar",f.prompts().single().getJSONObject("params").getString("text"))
        } finally { f.close() }
    }

    @Test(timeout = 15000) fun oldInterruptedTurnCompleteCannotFinalizeTheNextGeminiSegment() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "First request"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "First request"),"generationComplete" to true)))
            assertTrue(waitFor { f.prompts().size==1 })
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("interrupted" to true)))
            f.receive(json("serverContent" to json("turnComplete" to true)))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Second request"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Second request"))))
            assertFalse("An older interrupted turnComplete is not this segment's completion",waitFor { f.prompts().size>1 })
            f.receive(json("serverContent" to json("generationComplete" to true)))
            assertTrue(waitFor { f.prompts().size==2 })
            assertEquals("Second request",f.prompts().last().getJSONObject("params").getString("text"))
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun interruptedGeminiTurnCompleteAloneNeverAdmitsMatchingSpeech() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Delete drafts"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Delete drafts"))))
            f.receive(json("serverContent" to json("interrupted" to true,"turnComplete" to true)))
            assertFalse("Interrupted playback completion cannot finalize a speech request",waitFor { f.prompts().isNotEmpty() })
        } finally { f.close() }
    }

    @Test(timeout = 15000) fun explicitGeminiFinalDoesNotBlockTheNextActivityOnOldPlayback() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(geminiFinal("First request"));assertTrue(waitFor { f.prompts().size==1 })
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("interrupted" to true,"turnComplete" to true)))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Second request"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Second request"))))
            assertFalse("The old turnComplete does not complete the new segment",waitFor { f.prompts().size>1 })
            f.receive(json("serverContent" to json("generationComplete" to true)))
            assertTrue(waitFor { f.prompts().size==2 })
            assertEquals("Second request",f.prompts().last().getJSONObject("params").getString("text"))
        } finally { f.close() }
    }

    @Test(timeout = 15000) fun standaloneFinalDoesNotLetOldTurnCompleteAuthorizeNextSpeech() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(geminiFinal("First request"));assertTrue(waitFor { f.prompts().size==1 })
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("interrupted" to true,"turnComplete" to true)))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Second request"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Second request"))))
            assertFalse("Playback completion cannot authorize the second turn",waitFor { f.prompts().size>1 })
            f.receive(json("serverContent" to json("generationComplete" to true)))
            assertTrue(waitFor { f.prompts().size==2 })
            assertEquals("Second request",f.prompts().last().getJSONObject("params").getString("text"))
        } finally { f.close() }
    }

    @Test(timeout = 15000) fun oldGenerationCompleteAfterExplicitFinalCannotCompleteNewSegment() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(geminiFinal("First request"));assertTrue(waitFor { f.prompts().size==1 })
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("generationComplete" to true,"turnComplete" to true)))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Second request"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Second request"))))
            assertFalse("The prior generation cannot authorize the new segment",waitFor { f.prompts().size>1 })
            f.receive(json("serverContent" to json("generationComplete" to true)))
            assertTrue(waitFor { f.prompts().size==2 })
        } finally { f.close() }
    }

    @Test(timeout = 18000) fun playbackBeforeLateInputDoesNotStrandTwoFollowingGeminiSegments() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "First request"))))
            f.receive(json("serverContent" to json("generationComplete" to true)))
            f.receive(json("serverContent" to json("turnComplete" to true)))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "First request"))))
            assertTrue("The late first input must commit once",waitFor { f.prompts().size==1 })
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Second request"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Second request"),"generationComplete" to true)))
            assertTrue("The second segment must commit once",waitFor { f.prompts().size==2 })
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("interrupted" to true,"turnComplete" to true)))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Third request"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Third request"))))
            assertFalse("The prior playback tail cannot finish the third segment",waitFor { f.prompts().size>2 })
            f.receive(json("serverContent" to json("generationComplete" to true)))
            assertTrue("The third segment must commit after its own generation",waitFor { f.prompts().size==3 })
            assertEquals("Third request",f.prompts().last().getJSONObject("params").getString("text"))
        } finally { f.close() }
    }

    @Test(timeout = 12000) fun generationCompleteDuringAnActiveGeminiSegmentCannotAuthorizeItsLaterEcho() = runBlocking {
        val f=Fixture()
        try {
            f.start();f.receive(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            f.receive(json("serverContent" to json("generationComplete" to true)))
            f.receive(json("serverContent" to json("inputTranscription" to json("text" to "Delete drafts"))))
            f.receive(json("voiceActivity" to json("type" to "ACTIVITY_END")))
            f.receive(json("serverContent" to json("outputTranscription" to json("text" to "Delete drafts"))))
            assertFalse("A completion during active speech cannot authorize its later transcript",waitFor { f.prompts().isNotEmpty() })
            f.receive(json("serverContent" to json("generationComplete" to true)))
            assertTrue("The closed segment's own generation may be admitted",waitFor { f.prompts().size==1 })
            assertEquals("Delete drafts",f.prompts().single().getJSONObject("params").getString("text"))
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun cancellingACompletedJobNeverInterruptsItsRuntime() = runBlocking {
        val f = Fixture()
        try {
            f.jobs = JSONArray().put(json("job_id" to "old", "status" to "done", "runtime_session_id" to "runtime"))
                .put(json("job_id" to "new", "status" to "watching", "runtime_session_id" to "runtime"))
            val method = DirectVoice::class.java.getDeclaredMethod("tool", JSONObject::class.java, java.lang.Long.TYPE, Continuation::class.java).apply { isAccessible = true }
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                method.invoke(f.voice, json("id" to "cancel-old", "name" to "hermes_cancel", "args" to json("job_id" to "old")), 0L, continuation)
            }
            assertTrue("An old job must never interrupt a newer voice or chat turn", f.nativeFrames.none { it.optString("method") == "session.interrupt" })
        } finally { f.close() }
    }
}
