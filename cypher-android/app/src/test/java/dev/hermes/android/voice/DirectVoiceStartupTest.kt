package dev.hermes.android.voice

import dev.hermes.android.data.AuthContext
import dev.hermes.android.core.json
import dev.hermes.android.data.CypherClient
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
        val client=CypherClient(AuthContext("https://voice-fixture.invalid",emptyList(),"owner","default",backend="hermes-dashboard"),null,factory) { builder ->
            builder.addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(json("ticket" to "fixture-ticket").toString().toResponseBody("application/json".toMediaType())).build() }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val voice=DirectVoice(client,"call","stored",scope,{}, {}, {})
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

    @Test(timeout=30000) fun retriesShareOneInitialConnectionDeadline() = runBlocking {
        val server=MockWebServer()
        repeat(4) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        server.start()
        val failed=CompletableDeferred<String>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val client=client("stored",server.url("/token")) {}
        val voice=DirectVoice(client,"call","stored",scope,{}, {}, { failed.complete(it) })
        val start=System.nanoTime()
        try {
            voice.start()
            val message=withTimeout(25000) { failed.await() }
            val elapsed=(System.nanoTime()-start)/1_000_000
            assertTrue(message,message.contains("20 seconds"))
            assertTrue("Elapsed $elapsed ms",elapsed in 19000..24000)
            assertTrue(server.requestCount in 1..3)
        } finally { voice.close();scope.cancel();client.closeNative();server.shutdown() }
    }

    @Test fun providerAdmissionDoesNotWaitForUnrelatedNativeModel() = runBlocking {
        val token = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val contexts=AtomicInteger();val nativeTickets=AtomicInteger();val firstTokenContextCount=CompletableDeferred<Int>()
        val client = client("stored",contextRead={contexts.incrementAndGet()},nativeAdmission={nativeTickets.incrementAndGet()}) { firstTokenContextCount.complete(contexts.get());token.complete(Unit) }
        val voice = DirectVoice(client, "call", "stored", scope, {}, {}, {})
        try {
            voice.start()
            assertEquals("An unavailable Hermes agent must not block direct voice admission", true,
                withTimeoutOrNull(3000) { token.await(); true })
            assertEquals("First admission must not repeat the context roundtrip",1,firstTokenContextCount.await())
            delay(100);assertEquals("Direct voice must not open a native events socket without delegated jobs",0,nativeTickets.get())
        } finally { voice.close(); scope.cancel(); client.closeNative() }
    }

    @Test fun mismatchedConversationCannotRequestProviderCredentials() = runBlocking {
        val requests = AtomicInteger()
        val failed = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = client("someone-else") { requests.incrementAndGet() }
        val voice = DirectVoice(client, "call", "stored", scope, {}, {}, { failed.complete(Unit) })
        try {
            voice.start()
            withTimeout(3000) { failed.await() }
            assertEquals(0, requests.get())
        } finally { voice.close(); scope.cancel(); client.closeNative() }
    }

    @Test fun reconnectContextRefreshRevalidatesScopeAndNeverAdmitsStaleFallback() = runBlocking {
        val reads=AtomicInteger()
        val client=CypherClient(AuthContext("https://voice-fixture.invalid",emptyList(),"owner","default",backend="hermes-dashboard"),null) { builder ->
            builder.addInterceptor { chain ->
                val read=reads.incrementAndGet()
                val body=json("profile" to if(read==3) "other" else "default","selected_session_id" to "stored","system_instruction" to "Fresh revision $read")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val voice=DirectVoice(client,"call","stored",scope,{}, {}, {})
        val method=DirectVoice::class.java.getDeclaredMethod("refreshContext",java.lang.Long.TYPE,Continuation::class.java).apply { isAccessible=true }
        suspend fun refresh()=suspendCoroutineUninterceptedOrReturn<Unit> { continuation -> method.invoke(voice,3000L,continuation) }
        val context=DirectVoice::class.java.getDeclaredField("context").apply { isAccessible=true }
        val ready=DirectVoice::class.java.getDeclaredField("contextReady").apply { isAccessible=true }
        try {
            refresh();assertEquals("Fresh revision 1",(context.get(voice) as JSONObject).getString("system_instruction"))
            refresh();assertEquals("Fresh revision 2",(context.get(voice) as JSONObject).getString("system_instruction"));assertEquals(true,ready.get(voice))
            assertTrue(runCatching { refresh() }.isFailure);assertEquals(false,ready.get(voice))
            assertEquals("Fresh revision 2",(context.get(voice) as JSONObject).getString("system_instruction"));assertEquals(3,reads.get())
        } finally { voice.close();scope.cancel();client.closeNative() }
    }

    @Test fun callbackReceiptIsBoundAndDuplicateFramesNeverReplayMutation() = runBlocking {
        val posts=AtomicInteger()
        var mismatch=false
        var httpStatus=200
        val client=CypherClient(AuthContext("https://voice-fixture.invalid",emptyList(),"owner","default",backend="hermes-dashboard"),null) { builder ->
            builder.addInterceptor { chain ->
                val request=chain.request()
                check(request.url.encodedPath.endsWith("/schedule-callback"))
                val buffer=okio.Buffer();request.body!!.writeTo(buffer)
                val body=JSONObject(buffer.readUtf8())
                assertEquals(300,body.getInt("delay_seconds"))
                posts.incrementAndGet()
                val receipt=json("status" to "scheduled","request_id" to body.getString("request_id"),"job_id" to "job", "scheduled_at" to "2026-10-16T20:00:00+04:00","timezone" to "Asia/Dubai","conversation_id" to if(mismatch) "foreign" else "stored")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(httpStatus).message("Fixture")
                    .body((if(httpStatus==200) receipt else json("detail" to if(httpStatus==409) "Choose delay_seconds or at" else "Unconfirmed cron provider failure")).toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val voice=DirectVoice(client,"call","stored",scope,{}, {}, {})
        val method=DirectVoice::class.java.getDeclaredMethod("tool",JSONObject::class.java,java.lang.Long.TYPE,Continuation::class.java).apply { isAccessible=true }
        suspend fun invoke(id:String)=suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            method.invoke(voice,json("id" to id,"name" to "schedule_callback","args" to json("reason" to "Check back","delay_seconds" to 300)),0L,continuation)
        }
        @Suppress("UNCHECKED_CAST")
        val receipts=DirectVoice::class.java.getDeclaredField("toolReceipts").apply { isAccessible=true }.get(voice) as Map<String,JSONObject>
        try {
            invoke("scheduled");invoke("scheduled")
            assertEquals(1,posts.get());assertEquals("scheduled",receipts.getValue("scheduled").getString("status"))
            mismatch=true;invoke("foreign")
            assertEquals(2,posts.get());assertEquals("submission_unconfirmed",receipts.getValue("foreign").getString("status"))
            mismatch=false;httpStatus=409;invoke("invalid-time")
            assertEquals("not_sent",receipts.getValue("invalid-time").getString("status"))
            httpStatus=400;invoke("ambiguous-create")
            assertEquals("submission_unconfirmed",receipts.getValue("ambiguous-create").getString("status"))
            httpStatus=503;invoke("unknown-save")
            assertEquals("submission_unconfirmed",receipts.getValue("unknown-save").getString("status"))
        } finally { voice.close();scope.cancel();client.closeNative() }
    }

    private fun client(session: String, stalledToken: HttpUrl? = null, contextRead: () -> Unit = {}, nativeAdmission: () -> Unit = {}, token: () -> Unit): CypherClient =
        CypherClient(AuthContext("https://voice-fixture.invalid", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                check(request.url.host == "voice-fixture.invalid")
                val context = request.url.encodedPath.endsWith("/context")
                if(context) contextRead()
                if(request.url.encodedPath.endsWith("/ws-ticket")) nativeAdmission()
                if (request.url.encodedPath.endsWith("/token")) {
                    token()
                    if(stalledToken!=null) return@addInterceptor chain.proceed(request.newBuilder().url(stalledToken).build())
                }
                val body = if (context) json("profile" to "default", "selected_session_id" to session) else json("detail" to "Synthetic native runtime unavailable")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (context) 200 else 503)
                    .message("Fixture").body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
}
