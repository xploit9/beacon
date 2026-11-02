package dev.hermes.android.voice

import dev.hermes.android.data.AuthContext
import dev.hermes.android.core.json
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.data.defaultVoiceModel
import dev.hermes.android.data.defaultVoiceName
import kotlinx.coroutines.*
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DirectVoiceStartupTest {

    @Test fun onlyUnsentNativeStagingMayBeCancelledAtCallEnd() {
        assertTrue(directToolMayCancel("staging_native"))
        assertFalse(directToolMayCancel("submitting_native"))
        assertFalse(outcomeUnknownAtCallEnd(json("function_name" to "hermes_work", "status" to "staging_native")))
        assertTrue(outcomeUnknownAtCallEnd(json("function_name" to "hermes_work", "status" to "submitting_native")))
    }

    private fun gatewayClient(paths: MutableList<String>, tickets: AtomicInteger, posts: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()): BeaconClient =
        BeaconClient(AuthContext("https://voice-fixture.invalid", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                paths += path
                if (chain.request().method == "POST") posts += path
                if (path.endsWith("/ws-ticket")) tickets.incrementAndGet()
                val body = when {
                    path.endsWith("/context") -> json("profile" to "default", "selected_session_id" to "stored")
                    path.endsWith("/tools") -> json("tools" to JSONArray().put(json("name" to "get_weather", "tool_id" to "a".repeat(64), "description" to "Weather")))
                    else -> json("jobs" to JSONArray())
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }

    @Test(timeout=8000) fun aModelToolCallRunsThroughTheGatewayAndGetsAToolResponse() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val started=CompletableDeferred<Pair<WebSocket,WebSocketListener>>()
        val events=java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val frames=java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val paths=java.util.concurrent.CopyOnWriteArrayList<String>();val tickets=AtomicInteger()
        val client=gatewayClient(paths,tickets)
        val factory=providerFactory(true,onSetup={ socket,listener,_ -> started.complete(socket to listener) },onFrame={ frames+=it })
        val voice=DirectVoice(client,"call","stored",scope,{}, { events+=it }, {},credentials=credentials(),providerSocketFactory=factory)
        try {
            voice.start(); val (socket,listener)=withTimeout(3000) { started.await() }
            withTimeout(3000) { while(events.none { it.optString("state")=="listening" }) delay(5) }
            listener.onMessage(socket,json("toolCall" to json("functionCalls" to JSONArray().put(json("id" to "find-1","name" to "discover_tools","args" to json("query" to "weather"))))).toString())
            withTimeout(3000) { while(frames.none { it.has("toolResponse") }) delay(5) }
            val response=frames.first { it.has("toolResponse") }.getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
            assertEquals("find-1",response.getString("id"));assertEquals("discover_tools",response.getString("name"))
            assertEquals("get_weather",response.getJSONObject("response").getJSONArray("tools").getJSONObject(0).getString("name"))
            assertTrue(paths.contains("/api/plugins/beacon/mobile/calls/call/tools"))
            assertEquals("A lookup opens no Hermes work",0,tickets.get())
            assertTrue(events.any { it.optString("type")=="tool_receipt"&&it.optString("tool_name")=="discover_tools"&&it.optString("status")=="running" })
        } finally { voice.close();scope.cancel();client.closeNative() }
    }

    @Test(timeout=8000) fun aFunctionTheCallDoesNotOfferIsRefusedWithoutRunningAnything() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val started=CompletableDeferred<Pair<WebSocket,WebSocketListener>>()
        val events=java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val frames=java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val paths=java.util.concurrent.CopyOnWriteArrayList<String>();val tickets=AtomicInteger()
        val client=gatewayClient(paths,tickets)
        val factory=providerFactory(true,onSetup={ socket,listener,_ -> started.complete(socket to listener) },onFrame={ frames+=it })
        val voice=DirectVoice(client,"call","stored",scope,{}, { events+=it }, {},credentials=credentials(),providerSocketFactory=factory)
        try {
            voice.start(); val (socket,listener)=withTimeout(3000) { started.await() }
            withTimeout(3000) { while(events.none { it.optString("state")=="listening" }) delay(5) }
            listener.onMessage(socket,json("toolCall" to json("functionCalls" to JSONArray().put(json("id" to "invented","name" to "delete_everything","args" to json())))).toString())
            withTimeout(3000) { while(frames.none { it.has("toolResponse") }) delay(5) }
            val response=frames.first { it.has("toolResponse") }.getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0).getJSONObject("response")
            assertEquals("unsupported",response.getString("status"))
            assertEquals(0,tickets.get());assertTrue(paths.none { it.contains("/tools") })
        } finally { voice.close();scope.cancel();client.closeNative() }
    }

    @Test(timeout=8000) fun aFinalOwnerTranscriptIsNotRelayedToHermesAndTheModelAnswersAloud() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val started=CompletableDeferred<Pair<WebSocket,WebSocketListener>>()
        val events=java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val audio=java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val paths=java.util.concurrent.CopyOnWriteArrayList<String>();val posts=java.util.concurrent.CopyOnWriteArrayList<String>();val tickets=AtomicInteger()
        val client=gatewayClient(paths,tickets,posts)
        val factory=providerFactory(true,onSetup={ socket,listener,_ -> started.complete(socket to listener) })
        val voice=DirectVoice(client,"call","stored",scope,{ audio+=it }, { events+=it }, {},credentials=credentials(),providerSocketFactory=factory)
        try {
            voice.start(); val (socket,listener)=withTimeout(3000) { started.await() }
            withTimeout(3000) { while(events.none { it.optString("state")=="listening" }) delay(5) }
            // The greeting turn ends, then the owner speaks and the live model answers in its own voice.
            listener.onMessage(socket,json("serverContent" to json("turnComplete" to true)).toString())
            listener.onMessage(socket,json("serverContent" to json("inputTranscription" to json("text" to "What is two plus three?","finished" to true))).toString())
            listener.onMessage(socket,json("serverContent" to json("outputTranscription" to json("text" to "Five."),"modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000","data" to "AAE=")))))).toString())
            listener.onMessage(socket,json("serverContent" to json("generationComplete" to true,"turnComplete" to true)).toString())
            withTimeout(3000) { while(audio.isEmpty()||events.none { it.optString("type")=="turn_complete" }) delay(5) }
            delay(150)
            assertEquals("The model's own answer reaches the speaker",1,audio.size)
            assertTrue("The owner's words and the answer reach the call card",events.any { it.optString("role")=="user"&&it.optString("text")=="What is two plus three?" }&&events.any { it.optString("role")=="assistant"&&it.optString("text")=="Five." })
            assertEquals("No Hermes work or native session is opened",0,tickets.get())
            assertTrue("Nothing is posted to the call's jobs",posts.none { it.contains("/jobs") })
            assertTrue("A normal turn shows no verification warning",events.none { it.optString("type")=="warning" })
        } finally { voice.close();scope.cancel();client.closeNative() }
    }

    // The reconnect context read now runs beside its provider socket; a failed read fails that attempt, never admits it.
    @Test(timeout=8000) fun failedReconnectContextReadUsesTheReconnectBudgetWithoutAdmittingItsSocket() = runBlocking {
        val contexts=AtomicInteger();val asrConnections=AtomicInteger();val sockets=AtomicInteger()
        val reconnected=CompletableDeferred<Unit>();val failure=CompletableDeferred<String>()
        val admittedOn=java.util.concurrent.CopyOnWriteArrayList<Int>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val client=client("stored",contextRead={ if(contexts.incrementAndGet()==2) throw java.io.IOException("Network handover") })
        val factory=providerFactory(true,onSetup={ socket,listener,frame ->
            if(asrConnections.incrementAndGet()==1) listener.onFailure(socket,java.io.IOException("Network handover"),null)
        },onRequest={ sockets.incrementAndGet() })
        val voice=DirectVoice(client,"call","stored",scope,{}, {
            if(it.optString("state")=="listening") { admittedOn+=sockets.get();if(sockets.get()==3) reconnected.complete(Unit) }
        }, { failure.complete(it) },credentials=credentials(),providerSocketFactory=factory)
        try {
            voice.start()
            assertTrue("A failed context read must use the reconnect budget",withTimeoutOrNull(5000) { reconnected.await();true }==true)
            assertFalse("The call must stay alive",failure.isCompleted)
            assertEquals(3,contexts.get())
            assertEquals("Three sockets; the one beside the failed read never sends its setup",3,sockets.get())
            assertEquals(2,asrConnections.get())
            assertFalse("The socket beside the failed context read must never be admitted",2 in admittedOn)
        } finally { voice.close();scope.cancel();client.closeNative() }
    }

    @Test(timeout=8000) fun rejectedGrokAndOpenAiHandshakesDoNotRetryAsNetworkFailures() = runBlocking {
        for(provider in listOf("grok","openai")) for(code in listOf(400,401,403)) {
            val connections=AtomicInteger();val failed=CompletableDeferred<String>()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            // The context stays pending, so a Grok/OpenAI greeting prefetch cannot open a second socket through this factory mid-assertion.
            val context=java.util.concurrent.CountDownLatch(1)
            val client=client("stored",contextRead={ context.await(5,java.util.concurrent.TimeUnit.SECONDS) })
            val factory=rejectedProviderFactory(connections) { socket,listener ->
                listener.onFailure(socket,java.io.IOException("Upgrade refused"),Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1).code(code).message("Rejected").build())
            }
            val voice=DirectVoice(client,"call","stored",scope,{}, {}, { failed.complete(it) },credentials=credentials(provider),providerSocketFactory=factory)
            try {
                voice.start()
                assertEquals("$provider HTTP $code",providerRejectionMessage(provider,if(code==400) "model" else "key"),withTimeout(1000) { failed.await() })
                assertEquals(1,connections.get())
            } finally { voice.close();context.countDown();scope.cancel();client.closeNative() }
        }
    }

    @Test(timeout=8000) fun providerRejectionOnClosedStillReportsTheKeyOrModel() = runBlocking {
        for(provider in listOf("grok","openai")) for(code in listOf(1007,1008)) {
            val connections=AtomicInteger();val failed=CompletableDeferred<String>()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val context=java.util.concurrent.CountDownLatch(1)
            val client=client("stored",contextRead={ context.await(5,java.util.concurrent.TimeUnit.SECONDS) })
            val reason=if(code==1008) "Invalid API key" else "Unknown model"
            val factory=rejectedProviderFactory(connections) { socket,listener ->
                listener.onClosed(socket,code,reason)
            }
            val voice=DirectVoice(client,"call","stored",scope,{}, {}, { failed.complete(it) },credentials=credentials(provider),providerSocketFactory=factory)
            try {
                voice.start()
                assertEquals(providerRejectionMessage(provider,reason),withTimeout(1000) { failed.await() })
                assertEquals(1,connections.get())
            } finally { voice.close();context.countDown();scope.cancel();client.closeNative() }
        }
    }

    @Test(timeout=10000) fun aNativeBackendRequestDuringAHermesUpdateTellsTheModelToTryAgainLater() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val events=mutableListOf<JSONObject>()
        val client=BeaconClient(AuthContext("https://voice-fixture.invalid",emptyList(),"owner","default",backend="hermes-dashboard"),null) { builder ->
            builder.addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Fixture")
                    .body(json("detail" to "Hermes is updating").toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val voice=DirectVoice(client,"call","stored",scope,{}, { events+=it }, {},credentials=credentials())
        val method=DirectVoice::class.java.getDeclaredMethod("tool",JSONObject::class.java,java.lang.Long.TYPE,Continuation::class.java).apply { isAccessible=true }
        try {
            runCatching { suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                method.invoke(voice,json("id" to "updating-native","name" to "hermes_work","args" to json("text" to "Owned fixture")),0L,continuation)
            } }
            @Suppress("UNCHECKED_CAST")
            val receipts=DirectVoice::class.java.getDeclaredField("toolReceipts").apply { isAccessible=true }.get(voice) as Map<String,JSONObject>
            val response=receipts.getValue("updating-native")
            assertEquals("not_sent",response.getString("status"));assertEquals("hermes_updating",response.getString("error_code"))
            assertTrue("The model is told why and when to try again",response.getString("message").contains("updating"))
        } finally { voice.close();scope.cancel();client.closeNative() }
    }

    @Test(timeout=5000) fun rejectedSetupReportsTheProviderReasonWithoutRetrying() = runBlocking {
        val connections = AtomicInteger()
        val failed = CompletableDeferred<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = client("stored")
        val factory = WebSocket.Factory { request, listener ->
            connections.incrementAndGet()
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(text: String): Boolean {
                    listener.onClosing(this, 1008, "API key not valid")
                    return true
                }
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
            }
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            socket
        }
        val voice = DirectVoice(client, "call", "stored", scope, {}, {}, { failed.complete(it) },
            credentials = credentials(), providerSocketFactory = factory)
        try {
            voice.start()
            assertEquals(providerRejectionMessage("gemini", "API key not valid"), withTimeout(3000) { failed.await() })
            assertEquals(1, connections.get())
        } finally { voice.close(); scope.cancel(); client.closeNative() }
    }

    @Test fun directVoiceAcceptsOpenAiAsASelectedProvider() {
        val client=BeaconClient(AuthContext("https://voice-fixture.invalid",emptyList(),"owner","default",backend="hermes-dashboard"),null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val voice=DirectVoice(client,"call","stored",scope,{}, {}, {},credentials=credentials("openai"))
        try {
            assertEquals("openai",DirectVoice::class.java.getDeclaredField("provider").apply { isAccessible=true }.get(voice))
        } finally { voice.close();scope.cancel();client.closeNative() }
    }

    @Test(timeout=15000) fun normalVoiceWorkUsesTheSameNativeChatAndCannotReplayAcrossProfiles()=runBlocking {
        for(profile in listOf("default","research")) {
            val prompts=java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
            val paths=java.util.concurrent.CopyOnWriteArrayList<String>()
            val factory=WebSocket.Factory { request,listener ->
                val socket=object:WebSocket {
                    override fun request()=request
                    override fun queueSize()=0L
                    override fun send(bytes:ByteString)=false
                    override fun close(code:Int,reason:String?)=true
                    override fun cancel()=Unit
                    override fun send(text:String):Boolean {
                        val frame=JSONObject(text);val method=frame.optString("method")
                        val result=when(method) {
                            "session.active_list" -> json("sessions" to JSONArray().put(json("id" to "runtime","session_key" to "stored","status" to "idle")))
                            "prompt.submit" -> { prompts.add(frame.getJSONObject("params"));json("status" to "streaming","user_row_id" to 7) }
                            else -> json("session_id" to "runtime","stored_session_id" to "stored","info" to json("lazy" to false),"messages" to JSONArray())
                        }
                        if(frame.has("id")) listener.onMessage(this,json("jsonrpc" to "2.0","id" to frame.get("id"),"result" to result).toString())
                        return true
                    }
                }
                listener.onOpen(socket,Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
                listener.onMessage(socket,json("jsonrpc" to "2.0","method" to "event","params" to json("type" to "gateway.ready","payload" to json())).toString())
                socket
            }
            val client=BeaconClient(AuthContext("https://voice-fixture.invalid",emptyList(),"owner",profile,backend="hermes-dashboard"),null,factory) { builder ->
                builder.addInterceptor { chain ->
                    paths.add(chain.request().url.encodedPath)
                    val path=chain.request().url.encodedPath
                    val value=if(path.endsWith("/jobs")) json("job_id" to "native-job") else if(path.endsWith("/calls/call")) json("jobs" to JSONArray()) else json("ticket" to "fixture-ticket")
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                        .body(value.toString().toResponseBody("application/json".toMediaType())).build()
                }
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val voice=DirectVoice(client,"call","stored",scope,{}, {}, {},credentials=credentials())
            val tool=DirectVoice::class.java.getDeclaredMethod("tool",JSONObject::class.java,java.lang.Long.TYPE,Continuation::class.java).apply { isAccessible=true }
            suspend fun call(id:String,name:String,args:JSONObject) {
                suspendCoroutineUninterceptedOrReturn<Unit> { continuation -> tool.invoke(voice,json("id" to id,"name" to name,"args" to args),0L,continuation) }
            }
            try {
                call("question","hermes_work",json("text" to "Use this profile's configured tools and memory"))
                call("question","hermes_work",json("text" to "Use this profile's configured tools and memory"))
                assertEquals(1,prompts.size)
                assertEquals(profile,prompts.single().getString("profile"))
                assertEquals("runtime",prompts.single().getString("session_id"))
                assertEquals("voice-live",prompts.single().getString("surface"))
                assertEquals("Use this profile's configured tools and memory",prompts.single().getString("text"))
                call("different-chat","hermes_work",json("text" to "Fixture","separate" to true))
                assertEquals("Extra arguments never reach a second chat",1,prompts.size)
                call("lookup","discover_tools",json("query" to "mail"))
                assertEquals("A lookup submits no prompt",1,prompts.size)
                assertTrue("Tools are found through the call's own gateway",paths.contains("/api/plugins/beacon/mobile/calls/call/tools"))
                assertFalse(paths.any { it.endsWith("/memory")||it.endsWith("/search") })
            } finally { voice.close();scope.cancel();client.closeNative() }
        }
    }

    @Test(timeout=25000) fun delegatedReadinessTimeoutReturnsNotSentWithoutCancellingTheCall() = runBlocking {
        val submitted=AtomicInteger()
        val factory=WebSocket.Factory { request,listener ->
            val socket=object:WebSocket {
                override fun request()=request
                override fun queueSize()=0L
                override fun send(bytes:ByteString)=false
                override fun close(code:Int,reason:String?)=true
                override fun cancel()=Unit
                override fun send(text:String):Boolean {
                    val input=JSONObject(text);val method=input.getString("method")
                    if(method=="prompt.submit") submitted.incrementAndGet()
                    val result=if(method=="session.active_list") json("sessions" to JSONArray().put(json("id" to "runtime","session_key" to "stored","status" to "starting")))
                    else json("session_id" to "runtime","stored_session_id" to "stored","info" to json("lazy" to true),"messages" to JSONArray())
                    listener.onMessage(this,json("jsonrpc" to "2.0","id" to input.getString("id"),"result" to result).toString())
                    return true
                }
            }
            listener.onOpen(socket,Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            socket
        }
        val client=BeaconClient(AuthContext("https://voice-fixture.invalid",emptyList(),"owner","default",backend="hermes-dashboard"),null,factory) { builder ->
            builder.addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(json("ticket" to "fixture-ticket").toString().toResponseBody("application/json".toMediaType())).build() }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val voice=DirectVoice(client,"call","stored",scope,{}, {}, {},credentials=credentials())
        try {
            val method=DirectVoice::class.java.getDeclaredMethod("tool",JSONObject::class.java,java.lang.Long.TYPE,Continuation::class.java).apply { isAccessible=true }
            val result=runCatching { suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                method.invoke(voice,json("id" to "work-timeout","name" to "hermes_work","args" to json("text" to "Owned synthetic task")),0L,continuation)
            } }
            assertTrue("The caller must receive a terminal result: ${result.exceptionOrNull()}",result.isSuccess)
            assertTrue(currentCoroutineContext().isActive)
            assertEquals(0,submitted.get())
            @Suppress("UNCHECKED_CAST")
            val receipts=DirectVoice::class.java.getDeclaredField("toolReceipts").apply { isAccessible=true }.get(voice) as Map<String,JSONObject>
            assertEquals("not_sent",receipts.getValue("work-timeout").getString("status"))
        } finally { voice.close();scope.cancel();client.closeNative() }
    }

    @Test(timeout=30000) fun phoneKeyReconnectsShareOneInitialConnectionDeadlineWithoutTokenFetches() = runBlocking {
        val connections=AtomicInteger()
        val failed=CompletableDeferred<String>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val client=client("stored")
        val voice=DirectVoice(client,"call","stored",scope,{}, {}, { failed.complete(it) },credentials=credentials(),providerSocketFactory=providerFactory(false) { connections.incrementAndGet() })
        val start=System.nanoTime()
        try {
            voice.start()
            val message=withTimeout(25000) { failed.await() }
            val elapsed=(System.nanoTime()-start)/1_000_000
            assertEquals("A provider that never answers setup is named as the failed stage",CALL_VOICE_NO_ANSWER,message)
            assertTrue("Elapsed $elapsed ms",elapsed in 19000..24000)
            assertTrue(connections.get() in 1..3)
        } finally { voice.close();scope.cancel();client.closeNative() }
    }

    @Test fun providerSocketOpensWhileTheScopedContextIsPendingAndAdmissionWaitsForIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val contexts=AtomicInteger();val nativeTickets=AtomicInteger();val connections=AtomicInteger()
        val release=java.util.concurrent.CountDownLatch(1)
        val events=java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val client = client("stored",contextRead={ contexts.incrementAndGet();release.await(3,java.util.concurrent.TimeUnit.SECONDS) },nativeAdmission={nativeTickets.incrementAndGet()})
        val voice = DirectVoice(client, "call", "stored", scope, {}, { events+=it }, {},credentials=credentials(),providerSocketFactory=providerFactory(true) { connections.incrementAndGet() })
        try {
            voice.start()
            assertEquals("The provider socket must not wait for the Hermes context", true,withTimeoutOrNull(2000) { while(connections.get()==0) delay(5);true })
            delay(150)
            assertEquals("The context read is still pending",1,contexts.get())
            assertFalse("Owner audio waits for the scoped context",voice.pcm(ByteArray(640)) { true })
            assertTrue("Admission waits for the scoped context",events.none { it.optString("state") in setOf("listening","thinking") })
            release.countDown()
            assertEquals(true,withTimeoutOrNull(3000) { while(events.none { it.optString("state")=="listening" }) delay(5);true })
            assertTrue("Owner audio flows once admitted",voice.pcm(ByteArray(640)) { true })
            assertEquals("One scoped context read",1,contexts.get())
            delay(100);assertEquals("Direct voice must not open a native events socket without delegated jobs",0,nativeTickets.get())
        } finally { release.countDown(); voice.close(); scope.cancel(); client.closeNative() }
    }

    // With the socket in parallel a mismatched scope may open the phone-key socket, but it is never admitted.
    @Test fun mismatchedConversationNeverAdmitsTheProviderOrTheMicrophone() = runBlocking {
        val connections = AtomicInteger()
        val failed = CompletableDeferred<String>()
        val events=java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = client("someone-else")
        val voice = DirectVoice(client, "call", "stored", scope, {}, { events+=it }, { failed.complete(it) },credentials=credentials(),providerSocketFactory=providerFactory(true) { connections.incrementAndGet() })
        try {
            voice.start()
            assertEquals(CALL_HERMES_NO_ANSWER,withTimeout(3000) { failed.await() })
            assertTrue(connections.get() <= 1)
            assertTrue(events.none { it.optString("state") in setOf("listening","thinking") })
            assertTrue(events.none { it.optString("stage")=="provider_ready" })
            assertFalse(voice.pcm(ByteArray(640)) { true })
        } finally { voice.close(); scope.cancel(); client.closeNative() }
    }

    @Test fun reconnectContextRefreshRevalidatesScopeAndNeverAdmitsStaleFallback() = runBlocking {
        val reads=AtomicInteger()
        val client=BeaconClient(AuthContext("https://voice-fixture.invalid",emptyList(),"owner","default",backend="hermes-dashboard"),null) { builder ->
            builder.addInterceptor { chain ->
                val read=reads.incrementAndGet()
                val body=json("profile" to if(read==3) "other" else "default","selected_session_id" to "stored","system_instruction" to "Fresh revision $read")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val voice=DirectVoice(client,"call","stored",scope,{}, {}, {},credentials=credentials())
        val method=DirectVoice::class.java.getDeclaredMethod("refreshContext",java.lang.Long.TYPE,java.lang.Boolean.TYPE,Continuation::class.java).apply { isAccessible=true }
        suspend fun refresh()=suspendCoroutineUninterceptedOrReturn<Unit> { continuation -> method.invoke(voice,3000L,false,continuation) }
        val context=DirectVoice::class.java.getDeclaredField("context").apply { isAccessible=true }
        val ready=DirectVoice::class.java.getDeclaredField("contextReady").apply { isAccessible=true }
        try {
            refresh();assertEquals("Fresh revision 1",(context.get(voice) as JSONObject).getString("system_instruction"))
            refresh();assertEquals("Fresh revision 2",(context.get(voice) as JSONObject).getString("system_instruction"));assertEquals(true,ready.get(voice))
            assertTrue(runCatching { refresh() }.isFailure);assertEquals(false,ready.get(voice))
            assertEquals("Fresh revision 2",(context.get(voice) as JSONObject).getString("system_instruction"));assertEquals(3,reads.get())
        } finally { voice.close();scope.cancel();client.closeNative() }
    }

    @Test fun callbackControlUsesTheBoundCallAndNeverReplaysAPost()=runBlocking {
        val posts=AtomicInteger();var httpStatus=200
        val client=BeaconClient(AuthContext("https://voice-fixture.invalid",emptyList(),"owner","default",backend="hermes-dashboard"),null) { builder ->
            builder.addInterceptor { chain ->
                assertEquals("/api/plugins/beacon/mobile/calls/call/callback",chain.request().url.encodedPath)
                val buffer=okio.Buffer();chain.request().body!!.writeTo(buffer)
                assertEquals("latest",JSONObject(buffer.readUtf8()).getString("scope"));posts.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(httpStatus).message("Fixture")
                    .body((if(httpStatus==200) json("subscribed" to true,"job_ids" to JSONArray().put("owned-job")) else json("detail" to "Synthetic unconfirmed receipt")).toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val voice=DirectVoice(client,"call","stored",scope,{}, {}, {},credentials=credentials())
        val tool=DirectVoice::class.java.getDeclaredMethod("tool",JSONObject::class.java,java.lang.Long.TYPE,Continuation::class.java).apply { isAccessible=true }
        suspend fun invoke(id:String,selection:String="latest")=suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            tool.invoke(voice,json("id" to id,"name" to "hermes_callback","args" to json("scope" to selection)),0L,continuation)
        }
        @Suppress("UNCHECKED_CAST")
        val receipts=DirectVoice::class.java.getDeclaredField("toolReceipts").apply { isAccessible=true }.get(voice) as MutableMap<String,JSONObject>
        receipts["work"]=json("function_name" to "hermes_work","job_id" to "owned-job")
        try {
            invoke("callback");invoke("callback")
            assertEquals(1,posts.get());assertTrue(receipts.getValue("callback").getBoolean("subscribed"))
            invoke("foreign-scope","foreign")
            assertEquals(1,posts.get());assertEquals("not_sent",receipts.getValue("foreign-scope").getString("status"))
            httpStatus=503;invoke("unknown");invoke("unknown")
            assertEquals(2,posts.get());assertEquals("submission_unconfirmed",receipts.getValue("unknown").getString("status"))
        } finally { voice.close();scope.cancel();client.closeNative() }
    }

    private fun credentials(provider:String="gemini")=VoiceSessionCredentials(provider,"fixture-provider-key",defaultVoiceModel(provider),defaultVoiceName(provider),"balanced")

    private fun rejectedProviderFactory(connections:AtomicInteger,reject:(WebSocket,WebSocketListener)->Unit)=WebSocket.Factory { request,listener ->
        connections.incrementAndGet()
        val socket=object:WebSocket {
            override fun request()=request
            override fun queueSize()=0L
            override fun send(text:String)=false
            override fun send(bytes:ByteString)=false
            override fun close(code:Int,reason:String?)=true
            override fun cancel()=Unit
        }
        reject(socket,listener)
        socket
    }

    private fun providerFactory(open:Boolean,onGreeting:(WebSocket,WebSocketListener)->Unit={_,_->},onFrame:(JSONObject)->Unit={},onSetup:(WebSocket,WebSocketListener,JSONObject)->Unit={_,_,_->},onRequest:()->Unit={}):WebSocket.Factory = WebSocket.Factory { request,listener ->
        onRequest()
        val socket=object:WebSocket {
            override fun request()=request
            override fun queueSize()=0L
            override fun send(bytes:ByteString)=false
            override fun close(code:Int,reason:String?)=true
            override fun cancel()=Unit
            override fun send(text:String):Boolean {
                val frame=JSONObject(text)
                onFrame(frame)
                if(frame.has("setup")) { onSetup(this,listener,frame);listener.onMessage(this,JSONObject().put("setupComplete",JSONObject()).toString()) }
                else if(frame.optString("type")=="session.update") listener.onMessage(this,JSONObject().put("type","session.updated").toString())
                else if(frame.has("clientContent")) onGreeting(this,listener)
                return true
            }
        }
        if(open) listener.onOpen(socket,Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
        socket
    }

    private fun client(session: String, contextRead: () -> Unit = {}, nativeAdmission: () -> Unit = {}): BeaconClient =
        BeaconClient(AuthContext("https://voice-fixture.invalid", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                check(request.url.host == "voice-fixture.invalid")
                val context = request.url.encodedPath.endsWith("/context")
                if(context) contextRead()
                if(request.url.encodedPath.endsWith("/ws-ticket")) nativeAdmission()
                check(!request.url.encodedPath.endsWith("/token")) { "Phone-held provider keys must not request a Hermes token" }
                val body = if (context) json("profile" to "default", "selected_session_id" to session) else json("detail" to "Synthetic native runtime unavailable")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (context) 200 else 503)
                    .message("Fixture").body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }

    @Test fun noRelayVerificationOrPerUtteranceHandOffRemainsInTheCall() {
        val root = listOf("src/main/java/dev/hermes/android/voice", "app/src/main/java/dev/hermes/android/voice", "beacon-android/app/src/main/java/dev/hermes/android/voice")
            .map { java.io.File(it) }.first { it.isDirectory }
        val sources = root.walk().filter { it.isFile && it.extension == "kt" }.associate { it.name to it.readText() }
        for (gone in listOf("Speech could not be verified", "Speech could not be confirmed", "VoiceFinalItems", "codecDrain", "lastNativeReply"))
            assertTrue("$gone must not exist", sources.none { (_, text) -> text.contains(gone) })
        // A final owner transcript is a caption, never a Hermes request.
        assertFalse(sources.getValue("DirectVoice.kt").contains("Your request is still pending."))
        assertTrue(callToolHint("hermes_callback", json("subscribed" to true)).contains("call back"))
        assertEquals("", callToolHint("discover_tools", json("tools" to JSONArray())))
    }
}
