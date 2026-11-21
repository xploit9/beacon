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
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    /**
     * A Gemini Live double: records every frame, answers setup unless held, or ends the session at its setup ([refuse]: the provider closes it;
     * [dropAtSetup]: the connection is lost), or never opens ([failOpen]). With [echoClose] a socket closed or cancelled here reports its own end, as OkHttp's does.
     */
    private class Provider(val holdSetup: Boolean = false, val refuse: Boolean = false, val echoClose: Boolean = false,
        val dropAtSetup: Boolean = false, val failOpen: Boolean = false) : WebSocket.Factory {
        val frames = CopyOnWriteArrayList<JSONObject>()
        val sockets = CopyOnWriteArrayList<Pair<WebSocket, WebSocketListener>>()
        override fun newWebSocket(request: okhttp3.Request, listener: WebSocketListener): WebSocket {
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?): Boolean { if (echoClose) listener.onClosed(this, code, reason.orEmpty()); return true }
                override fun cancel() { if (echoClose) listener.onFailure(this, java.io.IOException("Canceled"), null) }
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text); frames += frame
                    if (frame.has("setup") && refuse) { listener.onClosing(this, 1008, "Synthetic refusal"); return true }
                    if (frame.has("setup") && dropAtSetup) { listener.onFailure(this, java.io.IOException("Synthetic: connection lost"), null); return true }
                    if (frame.has("setup") && !holdSetup) listener.onMessage(this, json("setupComplete" to json()).toString())
                    return true
                }
            }
            sockets += socket to listener
            if (failOpen) { listener.onFailure(socket, java.io.IOException("Synthetic: no network"), null); return socket }
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            return socket
        }
        fun receive(frame: JSONObject, index: Int = sockets.lastIndex) { val (socket, listener) = sockets[index]; listener.onMessage(socket, frame.toString()) }
        fun cues() = frames.filter { it.has("clientContent") }
        fun setups() = frames.filter { it.has("setup") }
    }

    private fun client(contextStatus: Int = 200, callContextReads: AtomicInteger = AtomicInteger(), sessionReads: AtomicInteger = AtomicInteger(),
        requests: AtomicInteger = AtomicInteger()) =
        BeaconClient(AuthContext("https://voice-fixture.invalid", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                requests.incrementAndGet()
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
        warm: LiveSession? = null, val client: BeaconClient = client(), streamsMicrophone: Boolean = true, now: () -> Long = { 50_400L },
        microphoneMuted: () -> Boolean = { false }, dispatcher: CoroutineDispatcher = Dispatchers.Default,
        onEvent: (JSONObject) -> Unit = {}) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val events = CopyOnWriteArrayList<JSONObject>()
        val audio = CopyOnWriteArrayList<ByteArray>()
        val failure = CompletableDeferred<String>()
        lateinit var voice: DirectVoice
        init {
            voice = DirectVoice(client, if (admission == null) "call" else "", "stored", scope, { bytes -> audio += bytes; voice.played(bytes.size) },
                { events += it; onEvent(it) }, { failure.complete(it) }, gemini, providerSocketFactory = provider, clock = now, tappedAt = 50_000L,
                warm = warm, prefetched = prefetched, admission = admission, streamsMicrophone = streamsMicrophone,
                microphoneMuted = microphoneMuted)
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

    @Test fun aCallMutedBeforeSetupConnectsWithoutPretendingItsMicrophoneStreamed() = runBlocking {
        val c = Call(microphoneMuted = { true })
        try {
            c.voice.start()
            assertTrue(waitFor { "listening" in c.states() })
            assertTrue(c.stage("mic_streaming").isEmpty())
            assertTrue(c.provider.frames.none { it.optJSONObject("realtimeInput")?.has("audio") == true })
            c.provider.receive(greetingAudio())
            assertTrue(waitFor { c.audio.size == 1 })
            assertFalse(c.failure.isCompleted)
        } finally { c.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun mutingAnAdmittedCallReleasesItsHeldGreetingAndUnmuteStillSendsRealFrames() = runBlocking {
        val muted = java.util.concurrent.atomic.AtomicBoolean(false)
        val dispatcher = StandardTestDispatcher()
        val scheduler = dispatcher.scheduler
        val requests = AtomicInteger()
        val c = Call(microphoneMuted = muted::get, dispatcher = dispatcher, client = client(requests = requests))
        try {
            c.voice.start()
            assertTrue(waitFor { scheduler.runCurrent(); c.stage("provider_ready").isNotEmpty() })
            assertFalse("Unmuted startup still waits for the microphone", "listening" in c.states())
            c.provider.receive(greetingAudio())
            assertTrue(c.audio.isEmpty())
            muted.set(true); c.voice.microphoneModeChanged()
            assertEquals(1, c.audio.size)
            assertTrue(c.stage("mic_streaming").isEmpty())
            muted.set(false); c.voice.microphoneModeChanged(); c.microphone()
            scheduler.runCurrent()
            assertEquals(1, c.stage("mic_streaming").size)
            assertEquals(1, c.stage("first_model_audio").size)
            assertEquals(1, c.stage("playout_started").size)
            assertEquals(3, c.provider.frames.count { it.optJSONObject("realtimeInput")?.has("audio") == true })
            val count = c.events.size
            val frames = c.provider.frames.size
            val reads = requests.get()
            assertTrue(reads > 0)
            c.voice.close()
            muted.set(true); c.voice.microphoneModeChanged()
            c.provider.receive(greetingAudio())
            c.provider.receive(json("goAway" to json()))
            assertFalse(c.voice.pcm(ByteArray(640)) { true })
            scheduler.advanceTimeBy(60_000)
            scheduler.runCurrent()
            val late = c.events.drop(count).map { it.getString("type") }
            // Cancelling the provider may report one final playback cut to the closing service.
            assertTrue("Unexpected callbacks after close: $late", late.isEmpty() || late == listOf("interrupted"))
            assertEquals(1, c.provider.sockets.size)
            assertEquals(frames, c.provider.frames.size)
            assertEquals(reads, requests.get())
            assertTrue(c.scope.coroutineContext.job.children.none())
            assertEquals(1, c.audio.size)
            assertFalse(c.failure.isCompleted)
        } finally { c.close() }
    }

    @Test fun mutingDoesNotBypassTheHermesAdmission() = runBlocking {
        val admission = CompletableDeferred<JSONObject>()
        val c = Call(admission = admission, prefetched = context, microphoneMuted = { true })
        try {
            c.voice.start()
            assertTrue(waitFor { c.provider.sockets.isNotEmpty() })
            assertTrue(c.states().none { it in setOf("listening", "speaking") })
            assertTrue(c.audio.isEmpty())
            admission.complete(json("call_id" to "call", "conversation_id" to "stored", "profile" to "default"))
            assertTrue(waitFor { "listening" in c.states() })
            assertTrue(c.stage("mic_streaming").isEmpty())
        } finally { c.close() }
    }

    private fun lookupClient(discover: () -> Pair<Int, JSONObject>, invocations: MutableList<JSONObject>) =
        BeaconClient(AuthContext("https://voice-fixture.invalid", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val (code, body) = when {
                    request.url.encodedPath.endsWith("/context") -> 200 to context
                    request.url.encodedPath.endsWith("/tools") -> discover()
                    request.url.encodedPath.endsWith("/tools/invoke") -> {
                        val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                        val invocation = JSONObject(buffer.readUtf8()); invocations += invocation
                        200 to json("request_id" to invocation.getString("request_id"), "tool_id" to invocation.getString("tool_id"),
                            "tool_name" to "get_weather", "status" to "completed")
                    }
                    else -> 200 to json("jobs" to JSONArray())
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }

    private fun invokeWeather(c: Call) = c.provider.receive(json("toolCall" to json("functionCalls" to JSONArray().put(
        json("id" to "invoke-weather", "name" to "invoke_tool", "args" to json("tool_id" to "get_weather", "arguments" to "{}"))))))

    private class ContinuationNative : WebSocket.Factory {
        val methods=CopyOnWriteArrayList<String>()
        val prompts=CopyOnWriteArrayList<JSONObject>()
        val controls=CopyOnWriteArrayList<Pair<String,JSONObject>>()
        var cancelState="queued"
        var inlineStatus:String?=null
        var callbackConsent="unset"
        /** A 1.2.0 plugin whose /work keeps the consent it had (so the app would turn the call back on with its own POST). */
        var workIgnoresCallback=false
        var completedReply=""
        val inlineInvocations=CopyOnWriteArrayList<JSONObject>()
        var accepted:()->Unit={}
        override fun newWebSocket(request:okhttp3.Request,listener:WebSocketListener):WebSocket {
            val socket=object:WebSocket {
                override fun request()=request
                override fun queueSize()=0L
                override fun cancel()=Unit
                override fun close(code:Int,reason:String?)=true
                override fun send(bytes:ByteString)=false
                override fun send(text:String):Boolean {
                    val frame=JSONObject(text);val method=frame.getString("method");methods+=method
                    val params=frame.getJSONObject("params")
                    val result=when(method) {
                        "session.resume" -> json("session_id" to "stored","stored_session_id" to "stored","messages" to JSONArray(),
                            "running" to true,"info" to json("desktop_protocol" to "hermes-gateway-v1","lazy" to true))
                        "session.events.since" -> json("epoch" to "fixture-epoch","latest_seq" to 0,"events" to JSONArray())
                        "prompt.submit" -> {
                            prompts+=params;accepted()
                            json("status" to "queued","admission_id" to "native-admission","ref" to json("session_id" to "stored"))
                        }
                        "prompt.receipt" -> json("status" to cancelState,"admission_id" to "native-admission",
                            "ref" to json("session_id" to "stored"),"execution_generation" to 17)
                        "prompt.cancel","session.interrupt" -> {
                            controls+=method to params
                            json("status" to "terminal","outcome" to "cancelled")
                        }
                        else -> JSONObject()
                    }
                    listener.onMessage(this,json("jsonrpc" to "2.0","id" to frame.getString("id"),"result" to result).toString())
                    return true
                }
            }
            listener.onOpen(socket,Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            listener.onMessage(socket,json("jsonrpc" to "2.0","method" to "event","params" to json("type" to "gateway.ready","payload" to json("session_authority" to true))).toString())
            return socket
        }
    }

    private fun continuationClient(native:ContinuationNative,preparations:MutableList<JSONObject>,posts:MutableList<String>)=
        BeaconClient(AuthContext("https://voice-fixture.invalid",emptyList(),"owner","default",backend="hermes-dashboard"),null,native) { builder ->
            builder.addInterceptor { chain ->
                val request=chain.request();val path=request.url.encodedPath
                val result=when {
                    path=="/api/plugins/beacon/mobile/gateway-ticket" -> json("detail" to "Not Found")
                    path=="/api/auth/ws-ticket" -> json("ticket" to "synthetic-ticket")
                    path.endsWith("/context") -> context
                    path.endsWith("/tools/invoke") || path.contains("/tools/requests/") -> {
                        if(path.endsWith("/tools/invoke")) {
                            val buffer=okio.Buffer();request.body!!.writeTo(buffer);native.inlineInvocations+=JSONObject(buffer.readUtf8())
                        }
                        val admitted=native.inlineInvocations.single()
                        json("request_id" to admitted.getString("request_id"),"tool_id" to admitted.getString("tool_id"),
                            "tool_name" to "synthetic_action","status" to native.inlineStatus).apply {
                            if(native.inlineStatus=="needs_approval") put("proposal",json("id" to "p".repeat(40),"tool_name" to "synthetic_action","arguments" to admitted.getJSONObject("arguments")))
                            if(native.inlineStatus=="completed") put("result",json("text" to "Synthetic original result"))
                        }
                    }
                    path.endsWith("/work") -> {
                        val buffer=okio.Buffer();request.body!!.writeTo(buffer);preparations+=JSONObject(buffer.readUtf8());posts+=path
                        val body=preparations.last()
                        if(!native.workIgnoresCallback) native.callbackConsent=if(body.optBoolean("callback")) "requested" else if(body.has("callback")) "declined" else native.callbackConsent
                        if(body.has("tool_request_id")) {
                            val transfer=native.inlineStatus=="needs_approval"
                            if(transfer) native.inlineStatus="transferred"
                            json("status" to if(transfer) "prepared" else "following","job_id" to "durable-job","continuation" to true,
                                "submit" to transfer,"native_transfer" to transfer,"request_id" to body.getString("request_id"),
                                "text" to "Continue the exact original receipt","callback_consent" to native.callbackConsent)
                        } else json("status" to "prepared","job_id" to "durable-job","continuation" to true,"submit" to true,"callback_consent" to native.callbackConsent)
                    }
                    path.endsWith("/callback") -> {
                        val buffer=okio.Buffer();request.body!!.writeTo(buffer);val body=JSONObject(buffer.readUtf8());posts+=path
                        native.callbackConsent=if(body.optBoolean("enabled",true)) "requested" else "declined"
                        json("subscribed" to body.optBoolean("enabled",true))
                    }
                    path.endsWith("/calls/call")&&request.method=="GET" -> json("jobs" to JSONArray().apply {
                        if(preparations.isNotEmpty()) put(json("job_id" to "durable-job","request_id" to preparations.first().getString("request_id"),
                            "stored_session_id" to "stored","runtime_session_id" to "stored","status" to if(native.completedReply.isNotBlank()) "done" else "working",
                            "result" to native.completedReply,"callback_consent" to native.callbackConsent).apply {
                            if(native.prompts.isNotEmpty()) put("admission_id","native-admission")
                            preparations.first().optString("tool_request_id").takeIf(String::isNotBlank)?.let { put("tool_request_id",it) }
                        })
                    })
                    else -> { if(request.method=="POST") posts+=path;json("jobs" to JSONArray()) }
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(path=="/api/plugins/beacon/mobile/gateway-ticket") 404 else 200).message("Fixture")
                    .body(result.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }

    private fun handOffDraft(c:Call)=c.provider.receive(json("toolCall" to json("functionCalls" to JSONArray().put(
        json("id" to "draft-once","name" to "hermes_work","args" to json("text" to "Prepare a synthetic draft only",dev.hermes.android.voice.GeminiWire.OWNER_ASKED_FOR_CALL_BACK to true))))))

    private fun adaptiveTool(c:Call,id:String,name:String,args:JSONObject)=c.provider.receive(json("toolCall" to json("functionCalls" to JSONArray().put(
        json("id" to id,"name" to name,"args" to args)))))

    private suspend fun adaptiveResult(c:Call,name:String):JSONObject {
        fun responses()=c.provider.frames.flatMap { f -> f.optJSONObject("toolResponse")?.optJSONArray("functionResponses")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }
        // An approval's state waits up to 4 s before the model hears it (device round 2): the first answer can take that long.
        assertTrue(withTimeoutOrNull(6500) { while (responses().none { it.optString("name")==name }) delay(10); true } == true)
        return responses().last { it.optString("name")==name }.getJSONObject("response")
    }

    @Test(timeout=10000) fun adaptiveQuickProtectedWorkFinishesInTheCallWithoutCallbackOffer()=runBlocking {
        val native=ContinuationNative();val prepared=CopyOnWriteArrayList<JSONObject>();val posts=CopyOnWriteArrayList<String>()
        val c=Call(client=continuationClient(native,prepared,posts))
        try {
            c.voice.start();c.microphone()
            adaptiveTool(c,"quick-native","hermes_work",json("text" to "Synthetic protected quick read","mode" to "inline"))
            assertTrue(waitFor { native.prompts.size==1 })
            native.completedReply="Synthetic protected result"
            // Calls contract v3, section 4: inline work is answered by its result, as hermes_work's own final response.
            fun completed()=c.provider.frames.flatMap { f -> f.optJSONObject("toolResponse")?.optJSONArray("functionResponses")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }
                .lastOrNull { it.optString("name")=="hermes_work" }?.getJSONObject("response")?.takeIf { it.optString("status")=="completed" }
            assertTrue(withTimeoutOrNull(7000) { while(completed()==null) delay(10);true }==true)
            val receipt=completed()!!
            assertEquals(native.completedReply,receipt.getString("result"));assertFalse(receipt.getBoolean("will_update"))
            assertTrue("No scripted turn", c.provider.frames.none { it.toString().contains("Verified update from Hermes") })
            assertTrue("No offer in any answer", c.provider.frames.filter { it.has("toolResponse") }.none { it.toString().contains("offer") })
            assertEquals(1,native.prompts.size);assertEquals("unset",native.callbackConsent)
            assertTrue(posts.none { it.endsWith("/callback") })
        } finally { c.close() }
    }

    // REVIEW-E3 finding 3: a follow's call back goes on with its own request or not at all; no POST afterwards turns it on without the
    // owner's answer to a question (the same guard as hermes_callback). A decline is still sent.
    @Test(timeout=10000) fun aFollowNeverTurnsTheCallBackOnAfterItsRequest()=runBlocking {
        val native=ContinuationNative().apply { inlineStatus="running";workIgnoresCallback=true }
        val prepared=CopyOnWriteArrayList<JSONObject>();val posts=CopyOnWriteArrayList<String>()
        val c=Call(client=continuationClient(native,prepared,posts))
        try {
            c.voice.start();c.microphone()
            adaptiveTool(c,"quick-action","invoke_tool",json("tool_id" to "a".repeat(64),"arguments" to "{\"query\":\"synthetic\"}"))
            val original=adaptiveResult(c,"invoke_tool")
            adaptiveTool(c,"follow-existing","hermes_follow",json("request_id" to original.getString("request_id"),"callback" to true))
            adaptiveResult(c,"hermes_follow")
            delay(1_700)
            assertTrue(posts.toString(),posts.none { it.endsWith("/callback") });assertEquals("unset",native.callbackConsent)
        } finally { c.close() }
    }

    @Test(timeout=10000) fun adaptiveOverrunFollowNeverDispatchesTheOriginalActionAgain()=runBlocking {
        val native=ContinuationNative().apply { inlineStatus="running" }
        val prepared=CopyOnWriteArrayList<JSONObject>();val posts=CopyOnWriteArrayList<String>()
        val c=Call(client=continuationClient(native,prepared,posts))
        try {
            c.voice.start();c.microphone()
            adaptiveTool(c,"quick-action","invoke_tool",json("tool_id" to "a".repeat(64),"arguments" to "{\"query\":\"synthetic\"}"))
            val original=adaptiveResult(c,"invoke_tool")
            assertTrue(prepared.isEmpty());assertTrue(native.prompts.isEmpty())
            adaptiveTool(c,"follow-existing","hermes_follow",json("request_id" to original.getString("request_id"),"callback" to true))
            val followed=adaptiveResult(c,"hermes_follow")
            assertEquals("requested",followed.getString("callback_consent"));assertEquals(1,native.inlineInvocations.size)
            assertTrue(native.prompts.isEmpty());assertEquals(original.getString("request_id"),prepared.single().getString("tool_request_id"))
            adaptiveTool(c,"decline-callback","hermes_callback",json("enabled" to false))
            assertFalse(adaptiveResult(c,"hermes_callback").getBoolean("subscribed"));assertEquals("declined",native.callbackConsent)
            assertEquals(1,native.inlineInvocations.size)
        } finally { c.close() }
    }

    @Test(timeout=10000) fun adaptivePendingProposalTransfersOnlyItsReceiptIntoNativeConversation()=runBlocking {
        val native=ContinuationNative().apply { inlineStatus="needs_approval" }
        val prepared=CopyOnWriteArrayList<JSONObject>();val posts=CopyOnWriteArrayList<String>()
        val c=Call(client=continuationClient(native,prepared,posts))
        try {
            c.voice.start();c.microphone()
            adaptiveTool(c,"pending-action","invoke_tool",json("tool_id" to "a".repeat(64),"arguments" to "{\"query\":\"synthetic\"}"))
            val original=adaptiveResult(c,"invoke_tool")
            adaptiveTool(c,"transfer-pending","hermes_follow",json("request_id" to original.getString("request_id")))
            val followed=adaptiveResult(c,"hermes_follow")
            // Device round 1: an admitted hand-over is said at once as handed_over.
            assertEquals("handed_over",followed.getString("status"));assertEquals("durable-job",followed.getString("job_id"))
            assertEquals(1,native.inlineInvocations.size);assertEquals(1,native.prompts.size)
            assertEquals("Continue the exact original receipt",native.prompts.single().getString("text"))
            assertEquals("stored",native.prompts.single().getString("session_id"))
            assertEquals("transferred",native.inlineStatus)
            assertEquals(0,c.voice.close().notSent)
        } finally { c.close() }
    }

    @Test(timeout=12000) fun nativeQueuedWorkKeepsItsDurableCallbackWhenHangupRacesTheAdmissionReceipt()=runBlocking {
        val native=ContinuationNative();val preparations=CopyOnWriteArrayList<JSONObject>();val posts=CopyOnWriteArrayList<String>()
        val c=Call(client=continuationClient(native,preparations,posts))
        var unsent=-1
        native.accepted={unsent=c.voice.close().notSent}
        try {
            c.voice.start();c.microphone();handOffDraft(c)
            assertTrue(waitFor { native.prompts.size==1 })
            assertTrue(c.voice.settleAcceptedWork())
            assertEquals(0,unsent);assertEquals(1,preparations.size)
            assertEquals(preparations.single().getString("request_id"),native.prompts.single().getString("submission_id"))
            assertEquals("stored",native.prompts.single().getString("session_id"))
            assertTrue(preparations.single().getBoolean("callback"))
            assertTrue(posts.none { it.endsWith("/jobs") })
            assertTrue(native.methods.none { it in setOf("session.interrupt","prompt.cancel","approval.respond","approval.pending","session.active_list") })
        } finally { c.close() }
    }

    @Test(timeout=12000) fun duplicateQueuedNativeFunctionDoesNotResubmitOrLoseItsCallbackPromise()=runBlocking {
        val native=ContinuationNative();val preparations=CopyOnWriteArrayList<JSONObject>();val posts=CopyOnWriteArrayList<String>()
        val c=Call(client=continuationClient(native,preparations,posts))
        try {
            c.voice.start();c.microphone();handOffDraft(c)
            val result=invocationResult(c)
            assertEquals("handed_over",result.getString("status"));assertEquals("requested",result.getString("callback_consent"))
            assertEquals("durable-job",result.getString("job_id"));assertTrue(result.getBoolean("will_update"))
            handOffDraft(c)
            delay(500)
            // Calls contract v3, section 6: the same open call is not told its state again; it keeps the job and the callback it had.
            assertEquals(1,c.provider.frames.count { it.has("toolResponse") })
            assertEquals(1,native.prompts.size);assertEquals(1,preparations.size)
        } finally { c.close() }
    }

    @Test(timeout=18000) fun explicitCancelUsesExactAdmissionOrGenerationAndNeverStopsNewerWork()=runBlocking {
        for(state in listOf("queued","started","terminal")) {
            val native=ContinuationNative().apply { cancelState=state }
            val preparations=CopyOnWriteArrayList<JSONObject>();val posts=CopyOnWriteArrayList<String>()
            val c=Call(client=continuationClient(native,preparations,posts))
            try {
                c.voice.start();c.microphone();handOffDraft(c);invocationResult(c)
                c.provider.receive(json("toolCall" to json("functionCalls" to JSONArray().put(
                    json("id" to "cancel-exact","name" to "hermes_cancel","args" to json("job_id" to "durable-job"))))))
                assertTrue(waitFor { c.provider.frames.count { it.has("toolResponse") }>=2 })
                assertEquals(1,native.methods.count { it=="prompt.receipt" })
                when(state) {
                    "queued" -> {
                        assertEquals("prompt.cancel",native.controls.single().first)
                        assertEquals("native-admission",native.controls.single().second.getString("admission_id"))
                    }
                    "started" -> {
                        assertEquals("session.interrupt",native.controls.single().first)
                        assertEquals(17,native.controls.single().second.getInt("execution_generation"))
                    }
                    else -> assertTrue(native.controls.isEmpty())
                }
                assertEquals(1,native.prompts.size)
            } finally { c.close() }
        }
    }

    /** The first result as Hermes gave it (a request still running past the notice is told "still_running" first: CallLinkState). */
    private suspend fun invocationResult(c: Call): JSONObject {
        fun results() = c.provider.frames.flatMap { f -> f.optJSONObject("toolResponse")?.optJSONArray("functionResponses")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }
            .map { it.getJSONObject("response") }.filter { it.optString("status") != "still_running" }
        assertTrue("The model receives the tool result", waitFor { results().isNotEmpty() })
        return results().first()
    }

    @Test(timeout = 18000) fun nameLookupWaitsForAValidCatalogueBeyondTheOldEightSecondDeadline() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val invocations = CopyOnWriteArrayList<JSONObject>()
        val client = lookupClient({
            entered.countDown(); check(release.await(12, TimeUnit.SECONDS))
            200 to json("tools" to JSONArray().put(json("name" to "get_weather", "tool_id" to "a".repeat(64))))
        }, invocations)
        val c = Call(client = client, streamsMicrophone = false)
        try {
            c.voice.start(); assertTrue(waitFor { "listening" in c.states() }); invokeWeather(c)
            assertTrue("The catalogue request started", withTimeoutOrNull(3000) { while (entered.count != 0L) delay(10); true } == true)
            delay(8300); release.countDown()
            val result = invocationResult(c)
            assertEquals("completed", result.getString("status"))
            assertEquals("The resolved identity, not a false no-match, is submitted", "a".repeat(64), invocations.single().getString("tool_id"))
        } finally { release.countDown(); c.close() }
    }

    @Test(timeout = 10000) fun failedNameLookupIsNotReportedAsAConfirmedMissingTool() = runBlocking {
        for ((code, body) in listOf(503 to json("detail" to "Temporarily unavailable"), 200 to json("status" to "unavailable"))) {
            val invocations = CopyOnWriteArrayList<JSONObject>()
            val c = Call(client = lookupClient({ code to body }, invocations), streamsMicrophone = false)
            try {
                c.voice.start(); assertTrue(waitFor { "listening" in c.states() }); invokeWeather(c)
                val result = invocationResult(c)
                assertEquals("failed", result.optString("status"))
                assertNotEquals("An unread tool list is never a confirmed missing tool: $result", "tool_unknown", result.optString("detail"))
                assertEquals(false, result.get("ran_on_hermes"))
                assertTrue("Lookup failure never invokes a tool", invocations.isEmpty())
            } finally { c.close() }
        }
    }

    private fun discoverCall(c: Call, id: String = "discover-1") = c.provider.receive(json("toolCall" to json("functionCalls" to JSONArray().put(
        json("id" to id, "name" to "discover_tools", "args" to json("query" to "check my email"))))))

    private suspend fun lastToolResponse(c: Call, count: Int = 1): JSONObject {
        assertTrue("The model receives the tool result", waitFor { c.provider.frames.count { it.has("toolResponse") } >= count })
        return c.provider.frames.last { it.has("toolResponse") }.getJSONObject("toolResponse")
            .getJSONArray("functionResponses").getJSONObject(0).getJSONObject("response")
    }

    private fun declaredTools(c: Call): Set<String> {
        val declarations = c.provider.setups().first().getJSONObject("setup").getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        return (0 until declarations.length()).map { declarations.getJSONObject(it).getString("name") }.toSet()
    }

    // Calls contract v3: no retry ladder and no "still starting" script. A tool search is one request; the model gets its real
    // answer, or a failure with its reason, and decides itself what to do next (CALLS-A Part 2).
    @Test(timeout = 15000) fun aToolSearchThatFailsIsAnsweredOnceWithItsReasonAndNoScript() = runBlocking {
        val searches = AtomicInteger()
        val c = Call(client = lookupClient({ searches.incrementAndGet(); 504 to json("detail" to "Tool lookup still settling") }, CopyOnWriteArrayList()), streamsMicrophone = false)
        try {
            c.voice.start(); assertTrue(waitFor { "listening" in c.states() }); discoverCall(c)
            val result = lastToolResponse(c)
            assertEquals("One search, not a ladder", 1, searches.get())
            assertEquals("failed", result.getString("status")); assertEquals("http_504", result.getString("detail"))
            assertFalse(result.getBoolean("will_update")); assertFalse(result.has("message")); assertFalse(result.has("fallback_tool"))
        } finally { c.close() }
    }

    @Test(timeout = 15000) fun aCatalogueStillStartingIsSaidAsHermesSaidItEveryTime() = runBlocking {
        val searches = AtomicInteger()
        val c = Call(client = lookupClient({
            if (searches.incrementAndGet() == 2) 200 to json("tools" to JSONArray().put(json("name" to "search_mail", "tool_id" to "a".repeat(64))))
            else 200 to json("status" to "starting", "tools" to JSONArray(), "total_matches" to 0)
        }, CopyOnWriteArrayList()), streamsMicrophone = false)
        try {
            c.voice.start(); assertTrue(waitFor { "listening" in c.states() })
            discoverCall(c, "discover-1")
            val first = lastToolResponse(c, 1)
            assertEquals("starting", first.getString("status")); assertFalse(first.has("message"))
            discoverCall(c, "discover-2")
            assertEquals("search_mail", lastToolResponse(c, 2).getJSONArray("tools").getJSONObject(0).getString("name"))
            discoverCalls(c, "mail-3", "calendar-3")
            val together = toolResponses(c, 4)
            assertEquals("starting", together.getValue("mail-3").getString("status")); assertEquals("starting", together.getValue("calendar-3").getString("status"))
            assertEquals("Each search is one request", 4, searches.get())
        } finally { c.close() }
    }

    private fun discoverCalls(c: Call, vararg ids: String) = c.provider.receive(json("toolCall" to json("functionCalls" to JSONArray().also { calls ->
        ids.forEach { calls.put(json("id" to it, "name" to "discover_tools", "args" to json("query" to "check $it"))) } })))

    /** Every tool result the model has received so far, by function call id, once at least [count] responses are in. */
    private suspend fun toolResponses(c: Call, count: Int): Map<String, JSONObject> {
        fun all() = c.provider.frames.filter { it.has("toolResponse") }.flatMap { frame ->
            val responses = frame.getJSONObject("toolResponse").getJSONArray("functionResponses")
            (0 until responses.length()).map { responses.getJSONObject(it) }
        }
        // One release can carry several results in one tool response (CallLinkState): the results are counted, not the frames.
        assertTrue("The model receives $count tool results", waitFor { all().size >= count })
        return all().associate { it.getString("id") to it.getJSONObject("response") }
    }

    @Test(timeout = 15000) fun theCallInstructionSaysWhereToFindMoreToolsAndWhenToHandOver() = runBlocking {
        val c = Call(client = lookupClient({ 200 to json("tools" to JSONArray()) }, CopyOnWriteArrayList()), streamsMicrophone = false)
        try {
            c.voice.start(); assertTrue(waitFor { "listening" in c.states() })
            // Calls contract v3, section 7: no scripted hand-off after failed lookups; the model is told where tools are and when to hand over.
            val setup = c.provider.setups().first().toString()
            assertTrue(setup.contains(dev.hermes.android.voice.CallInstruction.DISCOVER.trim()))
            assertTrue(setup.contains("hand it to Hermes with hermes_work right away"))
            assertFalse(setup.contains("do not ask again: call hermes_work"))
        } finally { c.close() }
    }

    @Test(timeout = 15000) fun theHandOffToolIsDeclaredFromTheFirstSecondAndNamedWhenToolSearchKeepsFailing() = runBlocking {
        val searches = AtomicInteger()
        val c = Call(client = lookupClient({ searches.incrementAndGet(); 504 to json("detail" to "Tool lookup still settling") }, CopyOnWriteArrayList()),
            streamsMicrophone = false)
        try {
            c.voice.start(); assertTrue(waitFor { "listening" in c.states() })
            // Declared in the session setup itself, before any search: it does not depend on the tool list.
            assertTrue("hermes_work", "hermes_work" in declaredTools(c))
            assertTrue("hermes_follow", "hermes_follow" in declaredTools(c))
            val instruction = c.provider.setups().first().toString()
            assertTrue(instruction.contains("hand it to Hermes with hermes_work right away"))
            assertTrue(instruction.contains("never say it without making the call"))
            discoverCall(c)
            val result = lastToolResponse(c)
            assertEquals(1, searches.get())
            assertEquals("failed", result.getString("status")); assertEquals("http_504", result.getString("detail"))
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun aSuccessfulEmptyCatalogueReallyIsANoMatch() = runBlocking {
        val invocations = CopyOnWriteArrayList<JSONObject>()
        val c = Call(client = lookupClient({ 200 to json("tools" to JSONArray()) }, invocations), streamsMicrophone = false)
        try {
            c.voice.start(); assertTrue(waitFor { "listening" in c.states() }); invokeWeather(c)
            val result = invocationResult(c)
            assertEquals("failed", result.getString("status"))
            assertEquals("tool_unknown", result.getString("detail"))
            assertTrue(invocations.isEmpty())
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun endingTheCallCancelsAnUnfinishedNameLookupWithoutInvoking() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val invocations = CopyOnWriteArrayList<JSONObject>()
        val c = Call(client = lookupClient({
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            200 to json("tools" to JSONArray().put(json("name" to "get_weather", "tool_id" to "a".repeat(64))))
        }, invocations), streamsMicrophone = false)
        try {
            c.voice.start(); assertTrue(waitFor { "listening" in c.states() }); invokeWeather(c)
            assertTrue(withTimeoutOrNull(3000) { while (entered.count != 0L) delay(10); true } == true)
            c.voice.close(); release.countDown(); delay(200)
            assertTrue(invocations.isEmpty())
            assertTrue(c.provider.frames.none { it.has("toolResponse") })
        } finally { release.countDown(); c.scope.cancel(); c.client.closeNative() }
    }

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

    @Test(timeout = 10000) fun aFinishedGreetingPlaysOnceAfterDelayedAdmission() = runBlocking {
        val admission = CompletableDeferred<JSONObject>()
        val c = Call(admission = admission, prefetched = context)
        try {
            c.voice.start(); assertTrue(waitFor { c.provider.cues().size == 1 }); c.microphone()
            c.provider.receive(greetingAudio()); c.provider.receive(greetingWords("Hello, Maya.")); c.provider.receive(greetingDone())
            assertTrue(c.audio.isEmpty())
            admission.complete(json("call_id" to "admitted-call", "conversation_id" to "stored"))
            assertTrue("A finished greeting is retained until the call connects", waitFor { c.audio.size == 1 })
            assertArrayEquals(greetingPcm, c.audio.single())
            assertTrue(waitFor { c.events.count { it.optString("type") == "transcript" && it.optString("text") == "Hello, Maya." } == 1 })
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun aFinishedGreetingIsNotCaptionedBeforeItsAudioCanPlay() = runBlocking {
        val admission = CompletableDeferred<JSONObject>()
        val c = Call(admission = admission, prefetched = context)
        try {
            c.voice.start(); assertTrue(waitFor { c.provider.cues().size == 1 }); c.microphone()
            c.provider.receive(greetingAudio()); c.provider.receive(greetingWords("Hello, Maya.")); c.provider.receive(greetingDone())
            assertTrue("A greeting cannot be shown before it is heard", c.events.none { it.optString("type") == "transcript" && it.optString("role") == "assistant" })
            admission.complete(json("call_id" to "admitted-call", "conversation_id" to "stored"))
            assertTrue(waitFor { c.audio.size == 1 && c.events.any { it.optString("type") == "transcript" && it.optString("text") == "Hello, Maya." } })
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun aWarmGreetingFinishedBeforeHandoffStillPlaysAfterAdmission() = runBlocking {
        val provider = Provider()
        val warm = LiveSession(Scope("https://voice-fixture.invalid", "owner", "default", session = "stored"), gemini, context, provider) { 49_000L }
        assertTrue(waitFor { warm.setupAt >= 0 })
        warm.startTurn(liveStartCue(context))
        provider.receive(greetingAudio()); provider.receive(greetingWords("Hello, Maya.")); provider.receive(greetingDone())
        val admission = CompletableDeferred<JSONObject>()
        val c = Call(provider = provider, admission = admission, prefetched = context, warm = warm)
        try {
            c.voice.start(); c.microphone()
            admission.complete(json("call_id" to "admitted-call", "conversation_id" to "stored"))
            assertTrue("The greeting completed on the warm socket, not before the call", waitFor { c.audio.size == 1 })
            assertArrayEquals(greetingPcm, c.audio.single())
            assertEquals("No second greeting is requested", 1, provider.cues().size)
            assertTrue(waitFor { c.events.count { it.optString("type") == "transcript" && it.optString("text") == "Hello, Maya." } == 1 })
        } finally { c.close() }
    }

    @Test(timeout = 10000) fun interruptionDuringConnectionCannotPlayTheHeldGreetingAfterward() = runBlocking {
        val admission = CompletableDeferred<JSONObject>()
        lateinit var c: Call
        c = Call(admission = admission, prefetched = context, onEvent = { event ->
            if (event.optString("type") == "state" && event.optString("state") == "speaking")
                c.provider.receive(json("serverContent" to json("interrupted" to true)))
        })
        try {
            c.voice.start(); assertTrue(waitFor { c.provider.cues().size == 1 }); c.microphone()
            c.provider.receive(greetingAudio())
            admission.complete(json("call_id" to "admitted-call", "conversation_id" to "stored"))
            assertTrue(waitFor { c.events.any { it.optString("type") == "interrupted" } })
            assertTrue("Interrupted held audio must not be queued after the interruption", c.audio.isEmpty())
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

    /**
     * A tap right after the owner opens a chat meets its warm session still connecting (about 1.2-1.4 s after the chat opens on the
     * emulator, 14 Nov 2026). The call takes it as it is: the same socket and setup carry on, and the greeting is asked for the
     * moment setup is answered, so a call never waits longer than a fresh connection would.
     */
    @Test fun aSessionTakenBeforeItsSetupAnsweredCarriesOnAndGreetsAtOnce() = runBlocking {
        val provider = Provider(holdSetup = true)
        var clock = 1_000L
        val warm = LiveSession(Scope("https://voice-fixture.invalid", "owner", "default", session = "stored"), gemini, context, provider) { clock }
        assertEquals("Setup goes out when the socket opens, before any tap", 1, provider.setups().size)
        clock = 1_300L
        warm.startTurn(liveStartCue(context))
        assertTrue("No greeting is asked for before setup is answered", provider.cues().isEmpty())
        val heard = CopyOnWriteArrayList<String>()
        warm.attach(object : WebSocketListener() { override fun onMessage(webSocket: WebSocket, text: String) { heard += text } })
        clock = 1_900L
        provider.receive(json("setupComplete" to json()))
        assertEquals("One socket: the call carried on the session it took", 1, provider.sockets.size)
        assertEquals("One setup: nothing was sent again", 1, provider.setups().size)
        assertEquals("The greeting is asked for as soon as setup is answered", 1, provider.cues().size)
        assertEquals(1_900L, warm.setupAt); assertEquals(1_900L, warm.cueSentAt)
        assertTrue("The call hears the setup answer itself", heard.any { JSONObject(it).has("setupComplete") })
        warm.close()
    }

    /**
     * Right after the owner switches chats, a call finds the new chat's context only together with its session (fault suite,
     * 14 Nov 2026: the context was published first, a tap 0.36 s after the switch found it alone and opened a second connection).
     */
    @Test fun aChatsContextIsNeverReadyWithoutItsSession() = runBlocking {
        val provider = Provider(holdSetup = true)
        val prewarm = dev.hermes.android.voice.CallPrewarm(dev.hermes.android.BeaconApplication(), { null }, { false }, clock = { 0L })
        val chatA = Scope("https://voice-fixture.invalid", "owner", "default", session = "chat-a")
        val chatB = chatA.session("chat-b")
        fun prefetched(chat: Scope) = dev.hermes.android.voice.PrefetchedContext(chat, "", JSONObject(context.toString()).put("selected_session_id", chat.session), 0L)
        prewarm.prepare(chatA, gemini, prefetched(chatA), provider)
        assertNotNull(prewarm.context(chatA, ""))
        assertNull("Before chat B is prepared a call finds nothing of it, and connects on its own", prewarm.context(chatB, ""))
        prewarm.prepare(chatB, gemini, prefetched(chatB), provider)
        assertNotNull(prewarm.context(chatB, ""))
        val taken = prewarm.claim(chatB, gemini)
        assertNotNull("The context comes with its session, still connecting", taken)
        assertEquals(chatB, taken!!.target); assertTrue(taken.setupAt < 0)
        assertEquals("Chat A's session made way for B's; no other connection", 2, provider.sockets.size)
        assertTrue(provider.sockets.size == provider.setups().size)
        taken.close()
    }

    private fun prefetchedFor(chat: Scope) = dev.hermes.android.voice.PrefetchedContext(chat, "", JSONObject(context.toString()).put("selected_session_id", chat.session), 0L)

    /** A call between BeaconCallService's `starting` and its claim: busy (active || starting) and starting, as the service reports it. */
    private class StartingCall { @Volatile var starting = false; @Volatile var active = false; val busy get() = active || starting }

    /**
     * Device round 1 (14 Nov 2026), leg C and the fault-suite baseline: the session was ready (or connecting) at the tap, but the prewarm
     * loop looked between the call's `starting` and its claim, saw a busy call and closed it, so the call opened a second session
     * (2,368 / 2,727 ms to the first audio instead of 0.8-1.1 s). A starting call always takes the session it finds.
     */
    @Test fun aStartedCallTakesItsReadySessionWhenThePrewarmLooksBeforeTheClaim() = runBlocking {
        val provider = Provider()
        val call = StartingCall()
        val prewarm = dev.hermes.android.voice.CallPrewarm(dev.hermes.android.BeaconApplication(), { null }, { call.busy }, clock = { 0L }, callStarting = { call.starting })
        val chat = Scope("https://voice-fixture.invalid", "owner", "default", session = "chat-c")
        prewarm.prepare(chat, gemini, prefetchedFor(chat), provider)
        assertTrue("Ready before the tap", prewarm.ready(chat))
        call.starting = true
        // The loop wakes on the touch-down poke while the start reads the owner's name.
        prewarm.prepare(chat, gemini, prefetchedFor(chat), provider)
        call.active = true
        prewarm.prepare(chat, gemini, prefetchedFor(chat), provider)
        val taken = prewarm.claim(chat, gemini)
        assertNotNull("The started call takes the ready session", taken)
        assertFalse(taken!!.dead)
        assertTrue("Still ready: nothing was set up again", taken.setupAt >= 0)
        assertEquals("One provider session for the call", 1, provider.sockets.size)
        taken.close()
    }

    /** The same window when the chat's call entry leaves as the call screen opens (the loop's no-entry path), with the session still connecting. */
    @Test fun aStartedCallTakesItsConnectingSessionWhenTheEntryLeavesBeforeTheClaim() = runBlocking {
        val provider = Provider(holdSetup = true)
        val call = StartingCall()
        val prewarm = dev.hermes.android.voice.CallPrewarm(dev.hermes.android.BeaconApplication(), { null }, { call.busy }, clock = { 0L }, callStarting = { call.starting })
        val chat = Scope("https://voice-fixture.invalid", "owner", "default", session = "chat-b")
        prewarm.prepare(chat, gemini, prefetchedFor(chat), provider)
        call.starting = true; call.active = true
        prewarm.drop()
        val taken = prewarm.claim(chat, gemini)
        assertNotNull("The started call takes the session still connecting", taken)
        assertFalse(taken!!.dead); assertTrue(taken.setupAt < 0)
        assertEquals(1, provider.sockets.size)
        taken.close()
    }

    /** A live call that did not take the session (a locked start, another chat) never has a second provider session beside its own. */
    @Test fun aLiveCallLeavesNoWarmSessionBesideItsOwn() = runBlocking {
        val provider = Provider()
        val call = StartingCall()
        val prewarm = dev.hermes.android.voice.CallPrewarm(dev.hermes.android.BeaconApplication(), { null }, { call.busy }, clock = { 0L }, callStarting = { call.starting })
        val chat = Scope("https://voice-fixture.invalid", "owner", "default", session = "chat-a")
        prewarm.prepare(chat, gemini, prefetchedFor(chat), provider)
        call.active = true
        prewarm.prepare(chat, gemini, prefetchedFor(chat), provider)
        assertFalse("Closed once the call is live", prewarm.ready(chat))
        assertEquals("Nothing new opens beside the call", 1, provider.sockets.size)
    }

    /** A call that starts without taking the warm session (an incoming call, a locked phone) closes it at once: one provider session per call. */
    @Test fun aCallThatDoesNotTakeTheWarmSessionClosesItAtTheClaim() = runBlocking {
        val provider = Provider()
        val prewarm = dev.hermes.android.voice.CallPrewarm(dev.hermes.android.BeaconApplication(), { null }, { false }, clock = { 0L })
        val chat = Scope("https://voice-fixture.invalid", "owner", "default", session = "chat-d")
        prewarm.prepare(chat, gemini, prefetchedFor(chat), provider)
        assertNull(prewarm.claim(null, gemini))
        assertFalse("The session the call did not take is closed at its claim", prewarm.ready(chat))
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
        assertTrue("The session answers the owner itself", instruction.contains("Answer yourself as soon as the owner finishes"))
        assertTrue(liveInstruction(json("profile" to "default")).contains("without using any name"))
        val callback = liveInstruction(json("callback" to json("reason" to "the flight check")))
        assertTrue(callback.contains("callback the owner asked for") && callback.contains("the flight check"))
        assertFalse("The callback reason is data, not the trusted opening", callback.substringAfter("Call opening:\n").substringBefore('\n').contains("the flight check"))
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

    /** [chat]'s context as the plugin returned it, with [summary] as its conversation so far (a newer message changes it). */
    private fun contextFor(chat: Scope, summary: String = "") = dev.hermes.android.voice.PrefetchedContext(chat, "",
        JSONObject(context.toString()).put("selected_session_id", chat.session).apply { if (summary.isNotBlank()) put("context_summary", summary) }, 0L)

    /**
     * CHAT-121 follow-up 10: a warm-up is a whole call setup (about 43 KB) in the phone's one upload queue. While the provider refused it, chat-121-10 sent
     * another at every look, 25 s apart, on any link (the demo's Gemini on 17 Nov: a 43 KB upload every 25 s while a chat was on screen). A refusal now rests
     * the warm-up for 2 minutes, then 4, then 8; the Call button showing again does not end the rest, another key does.
     */
    @Test fun aWarmUpTheProviderRefusesRestsInsteadOfSendingItsSetupAtEveryLook() = runBlocking {
        val provider = Provider(refuse = true, echoClose = true)
        var now = 0L
        val prewarm = dev.hermes.android.voice.CallPrewarm(dev.hermes.android.BeaconApplication(), { null }, { false }, clock = { now })
        val chat = Scope("https://voice-fixture.invalid", "owner", "default", session = "chat-r")
        prewarm.prepare(chat, gemini, contextFor(chat), provider)
        while (now < 500_000) { now += 25_000; prewarm.prepare(chat, gemini, contextFor(chat), provider) }
        assertEquals("a setup at 0 s, one after the 2-minute rest, one after the 4-minute rest", 3, provider.setups().size)
        assertEquals("resting: setup refused", prewarm.status)
        assertFalse(prewarm.ready(chat))
        // The Call button showing again (a send emptied the composer) is no new try.
        prewarm.pressed(arm = false)
        now += 25_000; prewarm.prepare(chat, gemini, contextFor(chat), provider)
        assertEquals(3, provider.setups().size)
        // A new key is tried at once.
        val rekeyed = VoiceSessionCredentials("gemini", "fixture-provider-key-2", defaultVoiceModel("gemini"), defaultVoiceName("gemini"), "balanced", 1)
        prewarm.prepare(chat, rekeyed, contextFor(chat), provider)
        assertEquals(4, provider.setups().size)
    }

    /**
     * CHAT-121 follow-up 10: on a link too slow for a call (the setup not answered 6 s after its socket opened; a working link answers in 0.7-1.3 s) the
     * warm-up rests. The chat's newer context sends no second setup while the first is on its way, and none during the rest; the context it was set up with
     * stays published with it until then. Another network ends the rest at once.
     */
    @Test fun aSetupTheLinkCarriesSlowlyRestsTheWarmUpUntilAnotherNetwork() = runBlocking {
        val provider = Provider(holdSetup = true, echoClose = true)
        var now = 0L
        var network = "cellular"
        val prewarm = dev.hermes.android.voice.CallPrewarm(dev.hermes.android.BeaconApplication(), { null }, { false }, clock = { now }, network = { network })
        val chat = Scope("https://voice-fixture.invalid", "owner", "default", session = "chat-s")
        prewarm.prepare(chat, gemini, contextFor(chat), provider)
        now += 1_000
        prewarm.prepare(chat, gemini, contextFor(chat, "A newer message"), provider)
        assertEquals("a setup on its way is not replaced", 1, provider.setups().size)
        assertEquals("the context it was set up with stays published with it", "", prewarm.context(chat, "")?.optString("context_summary"))
        now += 6_000
        prewarm.prepare(chat, gemini, contextFor(chat, "A newer message"), provider)
        assertEquals("resting: slow link", prewarm.status)
        assertFalse(prewarm.ready(chat))
        for (look in 1..4) { now += 25_000; prewarm.prepare(chat, gemini, contextFor(chat, "Message $look"), provider) }
        assertEquals("no other setup while it rests", 1, provider.setups().size)
        network = "wifi"; now += 1_000
        prewarm.prepare(chat, gemini, contextFor(chat, "Message 5"), provider)
        assertEquals("another network: a new warm-up", 2, provider.setups().size)
    }

    /**
     * CHAT-121 follow-up 10, calls stay instant on a working link: the setup answered at once, then the chat's context changes three times and each change
     * replaces the warm session at once with one set up with it, ready for a call, and nothing rests. The replaced sessions report their own end (as OkHttp's
     * do after a cancel), and that is not taken for a refusal.
     */
    @Test fun onAWorkingLinkEachContextChangeStillReplacesTheWarmSessionAtOnce() = runBlocking {
        val provider = Provider(echoClose = true)
        var now = 0L
        val prewarm = dev.hermes.android.voice.CallPrewarm(dev.hermes.android.BeaconApplication(), { null }, { false }, clock = { now })
        val chat = Scope("https://voice-fixture.invalid", "owner", "default", session = "chat-w")
        prewarm.prepare(chat, gemini, contextFor(chat), provider)
        assertTrue(prewarm.ready(chat))
        for (change in 1..3) {
            now += 25_000
            prewarm.prepare(chat, gemini, contextFor(chat, "Message $change"), provider)
            assertTrue("change $change: ready at once", prewarm.ready(chat))
            assertEquals("Message $change", prewarm.context(chat, "")?.optString("context_summary"))
        }
        assertEquals(4, provider.setups().size)
        assertEquals("ready", prewarm.status)
        val taken = prewarm.claim(chat, gemini)
        assertNotNull(taken)
        assertTrue("the call takes a session already set up", taken!!.setupAt >= 0)
        taken.close()
    }

    /**
     * CHAT-121 follow-up 10: only a setup that went can rest the warm-up. A socket that never opened (no network, a handover) sent no setup, and a connection
     * lost at its setup, not closed by the provider, says nothing of the provider or the link: the next look opens another, as chat-121-10 did.
     */
    @Test fun aSocketThatNeverOpenedOrAConnectionLostAtItsSetupRestsNothing() = runBlocking {
        for ((name, provider) in listOf("never opened" to Provider(failOpen = true, echoClose = true), "lost at its setup" to Provider(dropAtSetup = true, echoClose = true))) {
            var now = 0L
            val prewarm = dev.hermes.android.voice.CallPrewarm(dev.hermes.android.BeaconApplication(), { null }, { false }, clock = { now })
            val chat = Scope("https://voice-fixture.invalid", "owner", "default", session = "chat-l")
            prewarm.prepare(chat, gemini, contextFor(chat), provider)
            now += 25_000
            prewarm.prepare(chat, gemini, contextFor(chat), provider)
            assertEquals(name, 2, provider.sockets.size)
            assertFalse("$name: ${prewarm.status}", prewarm.status.startsWith("resting"))
        }
    }

    /**
     * CHAT-121 follow-up 10, window 3: the chat's Call button leaves when a message is typed, and the prewarm then drops its session. A setup the link
     * carried slowly (answered 22 s after its socket opened) still rests the warm-up: on chat-121-11's first build it was dropped unlearned, and the
     * button's return sent another whole setup into the 2 KB/s queue, in front of the message's own submit.
     */
    @Test fun aSlowSetupStillRestsTheWarmUpWhenTheCallButtonLeavesFirst() = runBlocking {
        val provider = Provider(holdSetup = true, echoClose = true)
        var now = 0L
        val prewarm = dev.hermes.android.voice.CallPrewarm(dev.hermes.android.BeaconApplication(), { null }, { false }, clock = { now }, network = { "cellular" })
        val chat = Scope("https://voice-fixture.invalid", "owner", "default", session = "chat-d")
        prewarm.prepare(chat, gemini, contextFor(chat), provider)
        now += 22_000
        provider.receive(json("setupComplete" to json()))
        prewarm.drop()
        now += 80_000
        prewarm.prepare(chat, gemini, contextFor(chat, "A newer message"), provider)
        assertEquals("no second setup after the slow one", 1, provider.setups().size)
        assertEquals("resting: slow link", prewarm.status)
    }

    /**
     * CHAT-121 follow-up 10, window 3: the Call button left 2 s after a warm-up went (a message typed), before its setup was answered. The session is kept,
     * never presented, until it shows what the link is: answered 22 s after its socket opened, it rests the warm-up, and the button's return sends no
     * second setup into the slow link.
     */
    @Test fun aSetupStillOnItsWayWhenTheCallButtonLeavesStillRestsTheWarmUpOnceAnswered() = runBlocking {
        val provider = Provider(holdSetup = true, echoClose = true)
        var now = 0L
        val prewarm = dev.hermes.android.voice.CallPrewarm(dev.hermes.android.BeaconApplication(), { null }, { false }, clock = { now }, network = { "cellular" })
        val chat = Scope("https://voice-fixture.invalid", "owner", "default", session = "chat-e")
        prewarm.prepare(chat, gemini, contextFor(chat), provider)
        now += 2_000
        prewarm.drop()
        now += 20_000
        provider.receive(json("setupComplete" to json()), 0)
        now += 60_000
        prewarm.prepare(chat, gemini, contextFor(chat, "A newer message"), provider)
        assertEquals("no second setup after the slow one", 1, provider.setups().size)
        assertEquals("resting: slow link", prewarm.status)
    }
}
