package dev.hermes.android.voice

import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.NativeSessionBinding
import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.data.voiceProviderSupported
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import org.json.JSONObject
import kotlin.coroutines.resume
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import dev.hermes.android.data.NativeToolGateway


internal data class DirectCallEnd(val notSent:Int=0,val submissionUnknown:Int=0)
internal suspend fun <T:Any> callLookup(block:suspend ()->T):T =
    withTimeoutOrNull(15000) { block() } ?: throw java.net.SocketTimeoutException("Lookup timed out. Try again.")

/** UI receipts confirm a bounded read without copying its private contents. */
internal fun directLookupReceipt(requestId:String,name:String,retained:JSONObject):JSONObject {
    val returned=if(name=="discover_tools") retained.optJSONArray("tools")!=null else retained.opt("summary") is String
    val completed=returned&&retained.optString("status") in setOf("","completed","empty")
    return JSONObject().put("type","tool_receipt").put("request_id",requestId).put("tool_name",name)
        .put("status",if(completed) "completed" else "failed").put("result_present",completed)
        .apply { retained.optString("error_code").takeIf { it.isNotBlank() }?.let { put("error_code",it) } }
}

internal suspend fun awaitNativeVoiceAgent(client:BeaconClient,binding:NativeSessionBinding) {
    suspend fun resume():JSONObject {
        require(binding.scope==client.scope) { "Native conversation scope changed" }
        val admitted=client.nativeRpc("session.resume",JSONObject().put("session_id",binding.storedSessionId).put("eager_build",true).put("omit_messages",false).put("close_on_disconnect",false))
        require(dev.hermes.android.data.nativeSessionBinding(client.scope,admitted)==binding) { "Native conversation identity changed" }
        return admitted
    }
    // session.history neither waits for the agent nor bounds inline photo data.
    resume()
    // A live lazy watch ignores eager_build; this scoped read starts and waits for its agent.
    client.nativeRpc("approval.pending",JSONObject().put("session_id",binding.runtimeSessionId))
    while(true) {
        require(binding.scope==client.scope) { "Native conversation scope changed" }
        if(nativeVoiceSessionReady(client.nativeRpc("session.active_list"),binding.runtimeSessionId,binding.storedSessionId)&&nativeVoiceAgentReady(resume())) return
        currentCoroutineContext().ensureActive();delay(250)
    }
}
/** A receipt whose outcome the owner must check after hanging up: a hand-off not yet confirmed, or a tool Hermes itself
 *  marks uncertain. A tool still running when the call ends is only cancelled, never reported as unknown work. */
internal fun outcomeUnknownAtCallEnd(receipt:JSONObject):Boolean {
    val status=receipt.optString("status")
    return receipt.optString("function_name")=="hermes_work"&&status in setOf("creating_native_session","submitting_native","submission_unconfirmed") ||
        status=="uncertain"&&!receipt.optBoolean("read_only",false)
}
/** What the voice agent hears when Hermes accepted a hand-off but returned no row id (it queued behind a running reply). */
internal fun handoffWithoutRowMessage(status:String):String=
    if(status in setOf("queued","steered","redirected")) "${dev.hermes.android.data.currentAssistantName} queued this behind the reply it is working on; the answer will appear in the chat. Do not send it again."
    else "${dev.hermes.android.data.currentAssistantName} accepted this; the answer will appear in the chat. Do not send it again."

internal enum class VoiceProviderTestResult { Success, KeyRejected, Unreachable, ProviderError }

internal fun classifyVoiceProviderTestResponse(code:Int?):VoiceProviderTestResult = when(code) {
    101 -> VoiceProviderTestResult.Success
    400,401,403 -> VoiceProviderTestResult.KeyRejected
    null -> VoiceProviderTestResult.Unreachable
    else -> VoiceProviderTestResult.ProviderError
}

internal suspend fun testVoiceProvider(provider:String,apiKey:String,model:String):VoiceProviderTestResult {
    val request=try { when(provider) {
        "gemini" -> GeminiWire.request(apiKey)
        "grok" -> GrokWire.request(apiKey,model)
        "openai" -> OpenAiWire.request(apiKey,model)
        else -> error("Unsupported voice provider")
    } } catch (error: Throwable) {
        return if (error.message == "Invalid provider key") VoiceProviderTestResult.KeyRejected else VoiceProviderTestResult.ProviderError
    }
    val http=OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).build()
    return try {
        withTimeoutOrNull(10000) {
            suspendCancellableCoroutine { continuation ->
                val socket=http.newWebSocket(request.rawRequest(),object:WebSocketListener() {
                    override fun onOpen(webSocket:WebSocket,response:Response) {
                        // Gemini upgrades any key and rejects a bad one only after setup, so its test waits for setupComplete.
                        if(provider=="gemini") { webSocket.send(JSONObject().put("setup",JSONObject().put("model",if(model.startsWith("models/")) model else "models/$model")).toString());return }
                        webSocket.close(1000,"Credential test")
                        if(continuation.isActive) continuation.resume(classifyVoiceProviderTestResponse(response.code))
                    }
                    override fun onMessage(webSocket:WebSocket,text:String) {
                        if(!text.contains("setupComplete")) return
                        webSocket.close(1000,"Credential test")
                        if(continuation.isActive) continuation.resume(VoiceProviderTestResult.Success)
                    }
                    override fun onMessage(webSocket:WebSocket,bytes:okio.ByteString) = onMessage(webSocket,bytes.utf8())
                    override fun onClosing(webSocket:WebSocket,code:Int,reason:String) {
                        webSocket.close(1000,null)
                        if(continuation.isActive) continuation.resume(classifyVoiceProviderClose(code,reason))
                    }
                    override fun onFailure(webSocket:WebSocket,error:Throwable,response:Response?) {
                        if(continuation.isActive) continuation.resume(classifyVoiceProviderTestResponse(response?.code))
                    }
                })
                continuation.invokeOnCancellation { socket.cancel() }
            }
    } ?: VoiceProviderTestResult.Unreachable
    } catch(error:CancellationException) { throw error }
    catch(_:Exception) { VoiceProviderTestResult.Unreachable
    } finally {
        // Closing a pooled TLS socket writes to the network, which Android forbids on the main thread.
        withContext(NonCancellable + Dispatchers.IO) {
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}

/** What the owner reads when a call cannot connect: the stage that did not finish says whose side failed. */
internal const val CALL_HERMES_NO_ANSWER = "Hermes didn't answer. Try again."
internal const val CALL_VOICE_NO_ANSWER = "The voice service didn't answer. Try again."
internal const val CALL_SIGN_IN_EXPIRED = "Your sign-in expired. Sign in again to call."
internal const val CALL_MIC_FAILED = "The microphone didn't start. Try again."
internal const val CALL_DROPPED = "The call dropped. Call again."
internal const val CALL_VOICE_STOPPED = "Voice stopped answering. Call again."
/** The call-start stages, in order; a failed start reports the first one that did not finish. */
internal val CALL_START_STAGES = listOf("grant_ok", "context_ok", "provider_setup_ok", "mic_streaming")
internal fun callFailureText(stage: String, error: Throwable?): String = when {
    error is ApiFailure && error.status in setOf(401, 403) -> CALL_SIGN_IN_EXPIRED
    stage == "grant_ok" || stage == "context_ok" -> CALL_HERMES_NO_ANSWER
    stage == "mic_streaming" -> CALL_MIC_FAILED
    else -> CALL_VOICE_NO_ANSWER
}
/** Hermes refused or never answered the call's admission; the service ends the call with the grant's own reason. */
internal class CallAdmissionFailed(cause: Throwable) : Exception(cause)

/** The functions the live model may call in a direct call; anything else is refused without running. */
internal val DIRECT_CALL_TOOLS = setOf("hermes_work", "hermes_callback", "hermes_cancel", "get_visual_state", "discover_tools", "invoke_tool", "tool_status",
    "hermes_search", "hermes_memory", "hermes_skills", "hermes_context")

/** What the live model is told beside a result that carries no message of its own, so it can say the outcome plainly. */
internal fun callToolHint(name:String,result:JSONObject):String {
    val status=result.optString("status")
    return when {
        name=="hermes_callback" && result.optBoolean("subscribed") -> "Tell the owner you will call back when the selected work is ready."
        name=="hermes_callback" && status=="not_sent" -> "A callback is unavailable for this work. Tell the owner to check the attached conversation."
        status=="not_sent" && result.optString("reason")=="gateway_unconfirmed" -> "The request was not sent because Hermes did not confirm the connection. Tell the owner to reconnect and try again."
        status=="not_sent" && result.optString("reason")=="job_capacity" -> "This call reached its work limit. Tell the owner to start a new call to continue."
        status=="not_sent" && result.optString("error_code")=="hermes_updating" -> "Hermes is updating. Nothing was sent; tell the owner to try again later."
        status=="not_sent" -> "That request was not sent. Tell the owner to try again."
        status=="submission_unconfirmed" -> "It could not be confirmed whether that request was sent. Tell the owner to check the attached conversation before retrying."
        name=="hermes_work" && result.has("runtime_session_id") && !result.has("job_id") && !result.has("admission_id") -> handoffWithoutRowMessage(status)
        name=="hermes_callback" -> "The callback could not be confirmed. Tell the owner to check the attached conversation."
        else -> ""
    }
}

internal class DirectVoice(private val client:BeaconClient,callId:String,private val selectedSession:String,private val scope:CoroutineScope,
    private val audio:(ByteArray)->Unit,private val event:(JSONObject)->Unit,private val failure:(String)->Unit,
    private val credentials:VoiceSessionCredentials,private val providerSocketFactory:WebSocket.Factory?=null,private val ownerName:String="",stallAfterMs:Long=20_000,
    private val ownerFence:Any=Any(),private val ownerActive:()->Boolean={true},
    private val clock:()->Long={ System.nanoTime()/1_000_000 },tappedAt:Long=0,
    /** A live session opened before the tap with this conversation's context; the call takes it over. */
    private val warm:LiveSession?=null,
    /** This conversation's context read before the tap; without it the call reads it now. */
    private val prefetched:JSONObject?=null,
    /** Hermes' admission (the grant), running alongside the voice connection; tools and call reads wait for it. */
    admission:Deferred<JSONObject>?=null,
    /** A callback the owner answered: its context carries the call-bound reason and result. */
    private val callback:Boolean=false,
    /** The call streams the phone's microphone: it is connected only once that stream reaches the session. */
    private val streamsMicrophone:Boolean=false,
    /** What was said so far in this call; a replacement session without a resumption handle starts from it. */
    private val recentSpeech:()->String={ "" },
    /** Runs the phone tools (calendar, alarms, contacts, calls, messages) on this phone; null in tests and sessions without them. */
    private val phone:PhoneVoiceBridge?=null,
    /** Whether this conversation may read or do anything private: not until the phone has been unlocked since it started ([CallTrust]). */
    private val trust:CallTrust=CallTrust.open()) {
    // One call clock: every diagnostic's elapsed_ms counts from the owner's tap when the service supplies it.
    private val origin=tappedAt.takeIf { it>0 }?:clock()
    private fun elapsedMs()=clock()-origin
    @Volatile private var callId=callId
    private val admission:Deferred<JSONObject> = admission ?: CompletableDeferred(JSONObject().put("call_id",callId))
    // Deliberately separate from authenticated Hermes HTTP: no cookie jar, interceptors, or copied headers.
    private val provider=credentials.provider
    private val providerHttp=OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).pingInterval(20,TimeUnit.SECONDS).build()
    private val epoch=AtomicLong();@Volatile private var socket:WebSocket?=null;@Volatile private var resume:String?=null
    @Volatile private var typedTurn:((String)->Boolean)?=null
    /** The owner's typed words as a user turn of the admitted live session; false when no session took them. */
    fun sendText(text:String):Boolean {
        val words=text.trim().take(4000)
        if(words.isEmpty()||stopped||!ready) return false
        val sent=typedTurn?.invoke(words)==true
        if(sent) event(JSONObject().put("type","transcript").put("role","user").put("text",words).put("final",true))
        return sent
    }
    private data class Outbound(val json:String,val permitted:()->Boolean,val visualBytes:Int=0,val control:Boolean=false,val mic:Boolean=false,val onSent:()->Unit={},val onDropped:()->Unit={},
        val providerSession:Long?=null,val newSessionJson:String?=null,val at:Long=0)
    private val providerSession=AtomicLong()
    // A session that took the owner's speech or a tool result and never answered is replaced; what it was owed is said again.
    private val stall=VoiceStallWatch(limit=stallAfterMs);private val stallPoll=(stallAfterMs/4).coerceIn(50,1000)
    private val unanswered=ArrayDeque<String>()
    private val visualPacing=VisualPacing()
    private var run:Job?=null;private val microphone=Channel<Outbound>(20)
    private val controls=Channel<Outbound>(64)
    private var retryControl:Outbound?=null
    private val visualFrames=Channel<Outbound>(Channel.CONFLATED,onUndeliveredElement={it.onDropped()})
    private val visualPending=java.util.concurrent.atomic.AtomicBoolean()
    private val visual=NativeVoiceVisual()
    private val gatewayFor=java.util.concurrent.ConcurrentHashMap<String,NativeToolGateway>()
    private val gatewayReceipts=DirectGatewayReceipts()
    private val gatewayDeliveryLock=Mutex()
    private val gatewayRequests=Semaphore(4)
    private var gatewayPolling:Job?=null
    // When the audio queued for the speaker ends (this clock): the owner talking before it cuts the reply short.
    @Volatile private var playUntil=0L
    private val toolReceipts=mutableMapOf<String,JSONObject>()
    private val functionSessions=mutableMapOf<String,Long>()
    private val cancelledFunctions=mutableSetOf<String>()
    private val submissionLock=Mutex()
    private val reported=mutableSetOf<String>()
    private var polling:Job?=null
    private var nativeEvents:Job?=null
    private val reconnects=VoiceReconnects()
    @Volatile private var ready=false;@Volatile private var stopped=false
    @Volatile private var greeted=false
    @Volatile private var context=JSONObject()
    private val callScope=client.scope
    private val toolJobs=mutableMapOf<Job,String>()
    @Volatile private var contextReady=false
    // An older plugin has no conversation context route: the call reads its own context once Hermes admitted it.
    @Volatile private var callContextOnly=callback
    // A setup the provider refused (bad key, unknown model): retrying cannot help, so the call ends with the reason.
    @Volatile private var providerRejection:String?=null
    private val connectingSince=System.nanoTime()
    // Connected = the provider answered setup, the microphone streams into it and Hermes admitted the call. Until then the call shows connecting.
    @Volatile private var admitted=false;@Volatile private var micStreaming=false;@Volatile private var connected=false
    private val stages=java.util.concurrent.ConcurrentHashMap<String,Long>()
    // The live model's greeting: its turn is open from the start note until the model finishes it or the owner talks over it.
    private val greetingLock=Any()
    @Volatile private var greetingOpen=false
    private var greetingHeard=false // guarded by greetingLock: the model's greeting audio has arrived
    private var greetingCut=false // guarded by greetingLock
    private val greetingText=StringBuilder() // guarded by greetingLock
    private var streamEndHeld=false // guarded by greetingLock: a mute during the greeting, sent once it ends
    private val heldGreeting=ArrayList<ByteArray>() // guarded by greetingLock: audio that arrived before the call connected
    private val greetingOutstanding=java.util.concurrent.atomic.AtomicLong()
    private val greetingPresented=java.util.concurrent.atomic.AtomicBoolean()
    /** When the live model's first greeting audio reached the phone, on the tap clock (-1 before); live timing reads it. */
    @Volatile internal var greetingArrivedMs=-1L; private set
    @Volatile private var warmUsed=false
    @Volatile private var running=false
    private fun admissionBudget(maximum:Long):Long {
        if(greeted) return maximum
        val left=20000-(System.nanoTime()-connectingSince)/1000000
        if(left<=0) throw java.net.SocketTimeoutException("Voice admission deadline")
        return minOf(maximum,left)
    }
    private fun errorCode(error:Throwable):String = when(error) {
        is ApiFailure -> if(error.status in 400..599) "http_${error.status}" else "invalid_response"
        is java.net.SocketTimeoutException, is TimeoutCancellationException -> "timeout"
        is java.net.UnknownHostException -> "dns"
        is javax.net.ssl.SSLException -> "tls"
        is java.io.IOException -> "network"
        else -> "invalid_response"
    }
    /** [at] is a phase that happened earlier on the tap clock; [warm] marks work done before the tap; [failed] names the stage a failed call did not finish. */
    private fun diagnostic(stage:String,error:Throwable?=null,closeCode:Int?=null,at:Long?=null,warm:Boolean?=null,failed:String?=null) {
        if(stopped) return
        event(JSONObject().put("type","media_diagnostic").put("stage",stage)
            .put("elapsed_ms",at?:elapsedMs()).put("provider",provider).put("attempt",reconnects.failures).apply {
                warm?.let { put("warm",it) }
                failed?.let { put("failed_stage",it) }
                error?.let { put("error_code",errorCode(it));put("cause_class",voiceTransportCause(it)) }
                closeCode?.takeIf { it in 1000..4999 }?.let { put("close_code",it) }
            })
    }
    /** Reports a call-start stage once, at its first occurrence. */
    private fun stage(name:String,at:Long=elapsedMs(),warm:Boolean?=null) {
        if(stages.putIfAbsent(name,at)==null) diagnostic(name,at=at,warm=warm)
    }
    private fun failedStage():String = CALL_START_STAGES.firstOrNull { !stages.containsKey(it) } ?: "first_model_audio"
    init {
        require(voiceProviderSupported(provider)&&credentials.apiKey.isNotBlank())
        this.admission.invokeOnCompletion { error ->
            if(error!=null) return@invokeOnCompletion
            this.callId=this.admission.getCompleted().optString("call_id",this.callId)
            stages.putIfAbsent("grant_ok",elapsedMs())
            admitted=true
            if(!stopped&&running) { startPolling();checkConnected() }
        }
    }
    private fun providerRequest():VoiceProviderRequest = liveProviderRequest(credentials)
    private fun newProviderSocket(request:VoiceProviderRequest,listener:WebSocketListener):WebSocket =
        providerSocketFactory?.newWebSocket(request.rawRequest(),listener)?:providerHttp.newWebSocket(request.rawRequest(),listener)
    /** Hermes' admission of this call; a refused grant ends the attempt quietly, as the service ends the call with its reason. */
    private suspend fun admittedCall():String {
        try { admission.await() } catch(error:CancellationException) { currentCoroutineContext().ensureActive();throw CallAdmissionFailed(error) }
        catch(error:Exception) { throw CallAdmissionFailed(error) }
        return callId
    }
    private fun checkedContext(value:JSONObject):JSONObject {
        require(client.scope==callScope&&value.optString("profile")==callScope.profile&&value.optString("selected_session_id")==selectedSession&&selectedSession.isNotBlank()) { "Native call context scope mismatch" }
        return value
    }
    /** The conversation's context from the plugin's conversation route; an older plugin's call route once Hermes admitted the call. */
    private suspend fun readContext():JSONObject {
        if(!callContextOnly) try { return client.nativeVoiceContext(selectedSession,ownerName) }
        // An older plugin has no such route ("Not Found"); a chat the grant is still saving is not readable yet. Both read the call's own context once admitted.
        catch(error:ApiFailure) { if(error.status!=404) throw error;callContextOnly=true }
        return client.nativeCallContext(admittedCall(),ownerName)
    }
    private suspend fun refreshContext(timeout:Long,first:Boolean) {
        // A read in flight keeps the last verdict; admission waits for this read's own result.
        try {
            val known=if(first) prefetched?.takeIf { contextMatches(it,callScope.session(selectedSession)) } else null
            if(first&&known!=null) { context=checkedContext(known);contextReady=true;stage("context_ok",warm=true);return }
            if(first) stage("context_sent")
            val fresh=checkedContext(withTimeout(timeout) { readContext() })
            context=fresh;contextReady=true
            if(first) stage("context_ok")
        } catch(error:Throwable) { contextReady=false;throw error }
    }
    private class ContextLoad(val result:Deferred<Throwable?>,val reconnect:Boolean)
    private class ContextFailed(val error:Throwable):Exception()
    /** Reads the scoped context alongside the provider socket; resolves to null when verified, else to the error. */
    private fun CoroutineScope.loadContext(timeout:Long,reconnect:Boolean)=ContextLoad(async {
        try { refreshContext(timeout,first=!reconnect);null }
        catch(error:CancellationException) { currentCoroutineContext().ensureActive();error }
        catch(error:Exception) { error }
    },reconnect)
    /** True once verified; false lets a reconnect retry; otherwise the call ends with the context's reason. */
    private fun contextAdmits(load:ContextLoad,error:Throwable?):Boolean = when {
        error==null -> true
        error is CallAdmissionFailed -> throw error
        load.reconnect&&(error is TimeoutCancellationException||error is java.io.IOException) -> false
        else -> throw ContextFailed(error)
    }
    /** The call failed before or after connecting: the diagnostic names the unfinished stage and is uploaded before the call is released. */
    private fun fail(error:Throwable,stage:String=failedStage()) {
        diagnostic("voice_error",error,failed=stage)
        if(stopped) return
        failure(when {
            error is VoiceProviderRejected || error is VoiceStalled -> error.message.orEmpty()
            connected -> CALL_DROPPED
            else -> callFailureText(stage,error)
        })
    }
    /** Every condition of a connected call holds: the call leaves connecting, and the greeting heard so far plays. */
    private fun checkConnected() {
        val flush=synchronized(greetingLock) {
            if(connected||!ready||streamsMicrophone&&!micStreaming||!admitted||stopped) return
            connected=true
            heldGreeting.toList().also { heldGreeting.clear() }
        }
        // The greeting the live model already sent plays at once, so the first state after connecting is its speech.
        event(JSONObject().put("type","state").put("state",if(flush.isEmpty()) "listening" else "speaking"))
        flush.forEach(::presentGreeting)
    }
    private fun presentGreeting(pcm:ByteArray) {
        greetingOutstanding.addAndGet(pcm.size.toLong())
        notePlaying(pcm.size/2*1000L/GeminiWire.OUTPUT_RATE)
        audio(pcm)
    }
    private fun notePlaying(ms:Long) {
        playUntil=maxOf(playUntil,clock())+ms
        stall.playing(ms)
    }
    /** The live model's own reply: its audio to the speaker, both sides' words to the call card, and the end of its turn. */
    private fun modelContent(content:JSONObject,greetingTurn:Boolean) {
        if(!greetingTurn) content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for(i in 0 until parts.length()) parts.optJSONObject(i)?.optJSONObject("inlineData")?.let { data ->
                val pcm=try { java.util.Base64.getDecoder().decode(data.optString("data")) } catch(_:Exception) { null }
                if(pcm!=null&&GeminiWire.validAudio(data.optString("mimeType"),pcm.size)) {
                    notePlaying(pcm.size/2*1000L/GeminiWire.OUTPUT_RATE)
                    audio(pcm)
                }
            }
        }
        for((key,role) in listOf("inputTranscription" to "user","outputTranscription" to "assistant")) {
            // The greeting's words are shown by the greeting turn, and only once the owner heard them.
            if(greetingTurn&&role=="assistant") continue
            content.optJSONObject(key)?.let {
                val transcript=JSONObject().put("type","transcript").put("role",role).put("text",it.optString("text")).put("final",it.optBoolean("final",false))
                if(it.has("item_id")) transcript.put("provider_item",it.getString("item_id")).put("content_index",it.optInt("content_index",0))
                event(transcript)
            }
        }
        if(!greetingTurn&&content.optBoolean("turnComplete")) event(JSONObject().put("type","turn_complete"))
    }
    /** One frame of the live model's greeting turn: its audio, its words, and its end. */
    private fun greetingFrame(content:JSONObject) {
        content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for(i in 0 until parts.length()) parts.optJSONObject(i)?.optJSONObject("inlineData")?.let { data ->
                val pcm=try { java.util.Base64.getDecoder().decode(data.optString("data")) } catch(_:Exception) { null }
                if(pcm!=null&&GeminiWire.validAudio(data.optString("mimeType"),pcm.size)) greetingAudio(pcm)
            }
        }
        content.optJSONObject("outputTranscription")?.let { words -> synchronized(greetingLock) {
            if(greetingCut) return@synchronized
            val text=words.optString("text")
            if(words.optBoolean("final")&&provider!="gemini") greetingText.setLength(0)
            if(greetingText.length+text.length<=1000) greetingText.append(text)
        } }
        if(content.optBoolean("interrupted")) endGreeting(cut=true)
        else if(content.optBoolean("turnComplete")) endGreeting(cut=false)
    }
    private fun greetingAudio(pcm:ByteArray) {
        val present=synchronized(greetingLock) {
            if(greetingCut||stopped||!greetingOpen) return
            if(!greetingHeard) greetingArrivedMs=elapsedMs()
            greetingHeard=true
            if(connected) true else { if(heldGreeting.sumOf { it.size }+pcm.size<=60*48000) heldGreeting+=pcm;false }
        }
        if(present) presentGreeting(pcm)
    }
    private fun endGreeting(cut:Boolean) {
        val words=synchronized(greetingLock) {
            if(!greetingOpen) return
            greetingOpen=false
            // Words are shown only for a greeting the owner heard.
            val said=if(cut||greetingCut||!greetingHeard) "" else greetingText.toString().trim()
            greetingText.setLength(0);heldGreeting.clear()
            said
        }
        if(stopped) return
        if(synchronized(greetingLock) { streamEndHeld.also { streamEndHeld=false } }) controls.trySend(Outbound(GeminiWire.endAudio().toString(),{ true },control=true))
        if(words.isNotBlank()) {
            event(JSONObject().put("type","transcript").put("role","assistant").put("text",words).put("final",false))
            event(JSONObject().put("type","turn_complete"))
        }
        if(greetingPresented.get()) diagnostic("playout_stopped")
    }
    /** The greeting turn opens when the start note is sent: at the tap for a warm session, at admission otherwise. */
    private fun beginGreeting() = synchronized(greetingLock) {
        if(!greetingOpen) { greetingOpen=true;greetingHeard=false;greetingCut=false;greetingText.setLength(0) }
    }
    /** Opens the greeting turn and sends the start note unless the warm session already sent it at the tap. */
    private fun openGreeting(ws:WebSocket,adopted:LiveSession?,send:(WebSocket,String)->Boolean) {
        beginGreeting()
        if(adopted==null||!adopted.cueRequested) {
            val cue=JSONObject().put("clientContent",JSONObject().put("turns",org.json.JSONArray().put(JSONObject().put("role","user")
                .put("parts",org.json.JSONArray().put(JSONObject().put("text",liveStartCue(context)))))).put("turnComplete",true))
            if(!send(ws,cue.toString())) { endGreeting(cut=true);return }
        }
        // A model that never answers the note leaves the turn closed, so the owner's speech is never mistaken for the greeting.
        scope.launch { delay(10_000);if(synchronized(greetingLock) { greetingOpen&&!greetingHeard }) { diagnostic("media_timeout");endGreeting(cut=true) } }
    }
    // The owner talking stops whatever is speaking, the live greeting included.
    private fun bargeIn(force:Boolean=false) {
        val close=synchronized(greetingLock) {
            heldGreeting.clear();greetingOutstanding.set(0)
            if(!greetingOpen) false else { greetingCut=true;!greetingHeard }
        }
        if(close) endGreeting(cut=true)
        else if(greetingOpen) scope.launch { delay(1500);if(greetingOpen) endGreeting(cut=true) }
        // The speaker stops at once; a reply that already ended is not "interrupted" by the owner speaking.
        if(force||clock()<playUntil) { playUntil=0;event(JSONObject().put("type","interrupted")) }
    }
    private fun ensureNativeEvents() = synchronized(this) {
        if(stopped || nativeEvents?.isActive==true) return@synchronized
        nativeEvents=scope.launch {
            try { client.nativeEvents { frame ->
                if(stopped) return@nativeEvents
                if(frame.has("id")&&frame.optString("method") in setOf("approval","clarify"))
                    event(JSONObject().put("type","warning").put("message","${dev.hermes.android.data.currentAssistantName} needs your approval or answer. Open the attached conversation."))
            } } catch(e:CancellationException) { throw e } catch(e:Exception) {
                if(!stopped) event(JSONObject().put("type","warning").put("message","Native live events disconnected. Job status continues through verified polling."))
            }
        }
    }
    private suspend fun pollNativeAdmissions() {
        val pending=synchronized(toolReceipts) { toolReceipts.entries.mapNotNull { (id,value) ->
            val admission=value.optString("admission_id")
            if(admission.isBlank() || value.optString("receipt_reported").isNotBlank()) null
            else Triple(id,admission,value.optString("runtime_session_id"))
        } }
        if(pending.isNotEmpty()) ensureNativeEvents()
        for((id,admission,runtime) in pending) {
            if(!callOwned()) return
            val receipt=withTimeoutOrNull(8000) {
                try {
                    val binding=client.nativeSession(selectedSession)
                    if(binding.runtimeSessionId!=runtime || binding.storedSessionId!=selectedSession) return@withTimeoutOrNull null
                    client.nativeRpc("prompt.receipt",JSONObject().put("session_id",runtime).put("admission_id",admission).put("include_result",true))
                } catch(e:CancellationException) { throw e } catch(_:Exception) { null }
            } ?: continue
            if(receipt.optString("admission_id")!=admission || receipt.optJSONObject("ref")?.optString("session_id")!=runtime) continue
            val state=receipt.optString("status")
            if(state!="terminal" && state!="unknown") continue
            val outcome=receipt.optString("outcome")
            val result=receipt.optJSONObject("result")
            val text=when {
                state=="unknown" -> "The outcome could not be confirmed. Check the attached conversation before retrying."
                outcome=="completed" && result?.optString("final_response")?.isNotBlank()==true -> result?.optString("final_response").orEmpty().take(65536)
                outcome=="cancelled" || outcome=="interrupted" -> "The Hermes request was interrupted."
                outcome=="failed" -> "Hermes could not complete that request. Open the attached conversation for details."
                else -> "The result could not be confirmed. Open the attached conversation."
            }
            if(tellModel(text)) synchronized(toolReceipts) { toolReceipts[id]?.put("receipt_reported",state+":"+outcome) }
        }
    }
    /** Tool requests the call submitted are read back until they settle, so an approval or a late result reaches the owner and the model. */
    private fun startGatewayPolling() {
        if(gatewayPolling!=null) return
        gatewayPolling=scope.launch {
            var cursor=0
            while(isActive&&!stopped) {
                delay(2000)
                val requests=gatewayReceipts.pending()
                if(requests.isEmpty()) continue
                val selected=(requests.drop(cursor%requests.size)+requests.take(cursor%requests.size)).take(4)
                cursor+=selected.size
                for(requestId in selected) {
                    val receipt=withTimeoutOrNull(3000) {
                        try { gatewayRequests.withPermit { gateway().status(requestId) } }
                        catch(error:CancellationException) { throw error }
                        catch(error:Exception) { null }
                    } ?: continue
                    try { publishGatewayReceipt(requestId,receipt) }
                    catch(error:CancellationException) { throw error }
                    catch(error:Exception) { event(JSONObject().put("type","warning").put("message","Tool status could not be verified; no action was replayed.")) }
                }
            }
        }
    }
    private fun startPolling() = synchronized(toolJobs) {
            if(polling!=null||stopped) return@synchronized
            startGatewayPolling()
            polling=scope.launch { while(isActive&&!stopped) {
                delay(3000)
                pollNativeAdmissions()
                val status=withTimeoutOrNull(8000) {
                    try { client.nativeRequest("/api/plugins/beacon/mobile/calls/$callId") }
                    catch(error:CancellationException) { throw error }
                    catch(error:Exception) { null }
                } ?: continue
                val jobs=status.optJSONArray("jobs") ?: continue
                if((0 until jobs.length()).any { i -> jobs.optJSONObject(i)?.let { job ->
                        voiceNeedsNativeEvents(job.optString("status")) || job.optString("status")=="uncertain" && job.optBoolean("will_continue")
                    } == true }) ensureNativeEvents()
                for(i in 0 until jobs.length()) {
                    val job=jobs.optJSONObject(i)?:continue
                    if(job.optString("status") !in setOf("done","completed","failed","needs_input","cancelled","uncertain")) continue
                    val own=synchronized(toolReceipts) { toolReceipts.values.any {
                        it.optString("job_id")==job.optString("job_id") && it.optString("request_id")==job.optString("request_id") &&
                            it.optString("stored_session_id")==selectedSession && it.optString("runtime_session_id")==job.optString("runtime_session_id")
                    } }
                    if(!own) continue
                    val identity=job.optString("job_id")+":"+job.optString("status")+":"+job.optString("error_code")+":"+job.optBoolean("will_continue")
                    if(identity in reported) continue
                    val text=when(job.optString("status")) {
                        "done","completed" -> job.optString("result").take(65536).ifBlank { "The reply is available in the attached conversation." }
                        "needs_input" -> "Hermes needs your approval or answer. Open the attached conversation."
                        "failed" -> "Hermes could not complete that request. Open the attached conversation for details."
                        "cancelled" -> "The Hermes request was cancelled."
                        else -> if(job.optBoolean("will_continue")) "Your request is still pending; its outcome is not yet confirmed." else "The outcome could not be confirmed. Open the attached conversation before retrying."
                    }
                    if(tellModel(text)) {
                        reported.add(identity)
                        if(job.optString("status")!="needs_input" && !job.optBoolean("will_continue")) synchronized(toolReceipts) {
                            toolReceipts.values.firstOrNull { it.optString("job_id")==job.optString("job_id") }?.put("receipt_reported",job.optString("status"))
                        }
                    }
                }
            } }
    }
    fun start() {
        if(!callOwned()) return
        // A call that is not connected within the admission budget ends, naming the stage that did not finish.
        scope.launch {
            delay((20_000-elapsedMs()).coerceAtLeast(0))
            if(!connected&&!stopped&&callOwned()) { run?.cancel();fail(java.net.SocketTimeoutException("Call did not connect")) }
        }
        running=true
        if(admitted) startPolling()
        run=scope.launch {
        try {
            if(!callOwned()) return@launch
            diagnostic("voice_connecting")
            // The provider socket never waits for Hermes; admission (owner audio, greeting, tools) waits for the scope check.
            var load=loadContext(admissionBudget(20000),reconnect=false)
            while(isActive&&!stopped) {
                providerRejection?.let { throw VoiceProviderRejected(providerRejectionMessage(provider,it)) }
                if(stall.stallsInARow()>=3) throw VoiceStalled()
                if(reconnects.exhausted) error("Voice reconnect limit reached")
                if(reconnects.failures>0) {
                    event(JSONObject().put("type","state").put("state","reconnecting"))
                    delay(minOf(GeminiWire.delay(reconnects.failures-1),admissionBudget(20000)))
                    // A read still in flight serves this attempt too; otherwise the scope is checked again.
                    if(load.result.isCompleted) load=loadContext(admissionBudget(12000),reconnect=true)
                }
                val generation=epoch.incrementAndGet();ready=false
                while(microphone.tryReceive().isSuccess) {}
                while(true) { val dropped=visualFrames.tryReceive().getOrNull()?:break;dropped.onDropped() };visualPending.set(false)
                stall.reset();visualPacing.reset()
                diagnostic("provider_key_ready")
                val grok=if(provider=="grok") GrokWire() else null
                val openAi=if(provider=="openai") OpenAiWire() else null
                // The first attempt takes the session opened before the tap; a session that died meanwhile is never reused.
                val adopted=warm?.takeIf { !warmUsed&&!greeted&&!it.dead&&trust.trusted() }
                if(!warmUsed) { warmUsed=true;if(adopted==null) warm?.close() }
                // A Gemini session continues from its resumption handle; any other replacement starts from what was said so far.
                val resumption=resume.takeIf { provider=="gemini" }
                if(resumption==null) providerSession.incrementAndGet()
                fun sendFrames(ws:WebSocket,json:String):Boolean {
                    val frame=JSONObject(json)
                    val frames=when {
                        grok!=null -> grok.outbound(frame)
                        openAi!=null -> openAi.outbound(frame)
                        else -> listOf(frame)
                    }
                    return frames.all { ws.send(it.toString()) }
                }
                val setupSeen=CompletableDeferred<Unit>();val ended=CompletableDeferred<Unit>();val opened=CompletableDeferred<Unit>()
                var ownerSpeaking=false
                val socketFence=Any()
                val listener=object:WebSocketListener() {
                    fun current()=voiceProviderCurrent(generation,epoch.get(),stopped,ended.isCompleted)
                    override fun onOpen(ws:WebSocket,response:Response) {
                        if(!current()) { ws.cancel();return }
                        stage("provider_open",at=adopted?.let { (it.socketOpenAt-origin).coerceAtLeast(0) }?:elapsedMs(),warm=adopted?.let { it.socketOpenAt<origin })
                        // A session opened before the tap already sent its setup; this socket sends it once the context is verified.
                        if(adopted==null) opened.complete(Unit)
                    }
                    override fun onMessage(ws:WebSocket,text:String) {
                        synchronized(socketFence) {
                        if(!current()) return
                        if(text.length>1_000_000) { ended.complete(Unit);ws.cancel();return }
                        try {
                            val raw=JSONObject(text);stall.heard()
                            val frame=when {
                                grok!=null -> grok.inbound(raw)?:return
                                openAi!=null -> openAi.inbound(raw)?:return
                                else -> raw
                            }
                            if(!current()) return
                            if(voiceReplyFrame(frame)) { ownerSpeaking=false;stall.replied();synchronized(unanswered) { unanswered.clear() } }
                            (grok?.continueResponse()?:openAi?.continueResponse())?.let { if(!ws.send(it.toString())) { ended.complete(Unit);ws.cancel();return } }
                            // Readiness never precedes setupComplete; admission below also waits for the verified context.
                            if(frame.has("setupComplete")) {
                                stage("provider_setup_ok",at=adopted?.setupAt?.takeIf { it>=0 }?.let { (it-origin).coerceAtLeast(0) }?:elapsedMs(),warm=adopted?.let { it.setupAt in 0 until origin })
                                setupSeen.complete(Unit)
                            }
                            if(frame.has("goAway")) { ended.complete(Unit);ws.close(1000,"Reconnect");return }
                            frame.optJSONObject("sessionResumptionUpdate")?.let { resume=it.optString("newHandle").takeIf { _ -> it.optBoolean("resumable") }?.takeIf(String::isNotBlank) }
                            when(frame.optJSONObject("voiceActivity")?.optString("type")) {
                                "ACTIVITY_START" -> { ownerSpeaking=true;bargeIn() }
                                "ACTIVITY_END" -> ownerSpeaking=false
                            }
                            frame.optJSONObject("serverContent")?.let { content ->
                                if(content.optJSONObject("interimInputTranscription")?.optString("text")?.isNotBlank()==true && !ownerSpeaking) {
                                    ownerSpeaking=true;bargeIn()
                                }
                                // The live model's greeting turn is its own speech, shown only once the owner heard it.
                                val greetingTurn=greetingOpen
                                if(greetingTurn) greetingFrame(content)
                                if(content.optBoolean("interrupted")) { stall.interrupted();bargeIn(force=true) }
                                modelContent(content,greetingTurn)
                            }
                            frame.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls -> for(i in 0 until calls.length()) {
                                val call=calls.getJSONObject(i);if(current()) launchTool(call,generation)
                            } }
                            frame.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { ids -> synchronized(toolReceipts) {
                                for(i in 0 until minOf(ids.length(),64)) { val id=ids.optString(i);if(toolReceipts.containsKey(id)) cancelledFunctions.add(id) }
                            } }
                        } catch(e:Exception) {
                            ended.complete(Unit);ws.cancel()
                        }
                        }
                    }
                    override fun onMessage(ws:WebSocket,bytes:okio.ByteString) {
                        if(bytes.size>1_000_000) { if(current()) { ended.complete(Unit);ws.cancel() };return }
                        onMessage(ws,bytes.utf8())
                    }
                    // A provider close frame (rejected setup, quota) ends this attempt now; without closing back, OkHttp waits and the call sat in "connecting".
                    override fun onClosing(ws:WebSocket,code:Int,reason:String) { ws.close(1000,null);if(current()) { if(!greeted&&code in setOf(1007,1008)) providerRejection=reason;diagnostic("provider_error",closeCode=code);ended.complete(Unit) } }
                    override fun onFailure(ws:WebSocket,t:Throwable,response:Response?) { if(current()) { if(!greeted) providerRejection=when(response?.code) { 401,403 -> "key";400 -> "model";else -> null };diagnostic("provider_error",response?.code?.takeIf { it in 400..599 }?.let { ApiFailure(it,"") } ?: t);ended.complete(Unit) } }
                    override fun onClosed(ws:WebSocket,code:Int,reason:String) { if(current()) { if(!greeted&&code in setOf(1007,1008)) providerRejection=reason;diagnostic("provider_error",closeCode=code);ended.complete(Unit) } }
                }
                // A warm session's greeting may already be arriving: its turn is open before the held frames are read.
                if(adopted?.cueRequested==true) beginGreeting()
                val ws=adopted?.attach(listener)?:newProviderSocket(providerRequest(),listener)
                socket=ws
                // The setup carries this conversation's verified context, so it waits for that read; the socket's handshake does not.
                val setupSender=if(adopted!=null) null else launch {
                    opened.await()
                    if(load.result.await()!=null) return@launch
                    if(voiceProviderCurrent(generation,epoch.get(),stopped,ended.isCompleted)&&!sendFrames(ws,liveSetup(credentials,callBrief(context,trust.trusted()),resumption,recentSpeech()).toString())) { ended.complete(Unit);ws.cancel() }
                }
                var fatal=false
                fun admit():Boolean = synchronized(socketFence) {
                    if(!voiceProviderCurrent(generation,epoch.get(),stopped,ended.isCompleted)) return@synchronized false
                    ready=true
                    reconnects.admitted()
                    // A typed turn rides this session's own wire, like the start note; a later socket replaces it.
                    typedTurn={ words -> socket===ws&&sendFrames(ws,JSONObject().put("clientContent",JSONObject().put("turns",org.json.JSONArray().put(JSONObject().put("role","user")
                        .put("parts",org.json.JSONArray().put(JSONObject().put("text",words))))).put("turnComplete",true)).toString()) }
                    diagnostic("provider_ready")
                    // The microphone is live from here on; the call reads as connected once it streams and Hermes admitted it.
                    if(!greeted) { greeted=true;openGreeting(ws,adopted) { socket,json -> sendFrames(socket,json) } }
                    else if(connected) event(JSONObject().put("type","state").put("state","listening"))
                    true
                }
                try {
                    if(directAdmissionAttempt(admissionBudget(12000),{ it is ContextFailed||it is CallAdmissionFailed }) { awaitSetup(setupSeen,ended,load);true }!=true) { reconnects.failed();continue }
                    if(!contextAdmits(load,load.result.await())||!admit()) { reconnects.failed();continue }
                    checkConnected()
                    val writer=launch { while(isActive) {
                        // Control receipts get the first bounded queue; PCM producers never wait.
                        val next=retryControl?.also { retryControl=null } ?: nextDirectFrame(controls,microphone,visualFrames)
                        if(generation!=epoch.get()||!ready||ended.isCompleted) { if(next.control) retryControl=next else next.onDropped();break }
                        visualPacing.drained(ws.queueSize())
                        if(next.visualBytes>0) {
                            visualPending.set(false)
                            if(!GeminiWire.visualMayQueue(next.visualBytes,ws.queueSize(),false,System.nanoTime()/1_000_000-next.at)) { next.onDropped();continue }
                        }
                        if(!next.permitted()) { next.onDropped();continue }
                        val payload=if(next.providerSession!=null&&next.providerSession!=providerSession.get()) next.newSessionJson?:next.json else next.json
                        if(ws.queueSize()>256000||!sendFrames(ws,payload)) {
                            if(next.control) retryControl=next else next.onDropped()
                            ended.complete(Unit);break
                        }
                        if(next.visualBytes>0) visualPacing.sent()
                        if(next.control) when(voiceOwedReply(payload)) {
                            OwedReply.Tool -> owe(next.newSessionJson)
                            OwedReply.Turn -> owe(payload)
                            OwedReply.None -> Unit
                        }
                        next.onSent()
                        if(next.mic&&!micStreaming) { micStreaming=true;stage("mic_streaming");checkConnected() }
                    } }
                    val watchdog=launch { while(isActive) {
                        delay(stallPoll)
                        // End this session before re-queuing what it was owed, so its writer cannot send that into the dead socket.
                        if(ready&&stall.stalled()) { ended.complete(Unit);stalledSession();break }
                    } }
                    ended.await();watchdog.cancel();writer.cancelAndJoin()
                } catch(e:CancellationException) { throw e }
                catch(e:ContextFailed) { fatal=true;throw e }
                catch(e:CallAdmissionFailed) { fatal=true;throw e }
                catch(e:Exception) { /* Reconnect with the same phone-held key and refreshed Hermes context. */ }
                // A fatal context failure ends the call next (the call ends next), so it shows no interim state.
                finally { setupSender?.cancel();epoch.compareAndSet(generation,generation+1);ready=false;if(!fatal) { playUntil=0;event(JSONObject().put("type","interrupted")) };endGreeting(cut=true);ws.cancel();if(!stopped&&!fatal) event(JSONObject().put("type","state").put("state","reconnecting")) }
                reconnects.failed()
            }
        } catch(e:CallAdmissionFailed) { /* The service ends the call with Hermes' own reason for refusing it. */ }
        catch(e:ContextFailed) { if(e.error !is CallAdmissionFailed) fail(e.error,"context_ok") }
        catch(e:TimeoutCancellationException) { currentCoroutineContext().ensureActive();fail(e) }
        catch(e:CancellationException) { throw e } catch(e:Exception) { fail(e) }
    } }
    private fun launchTool(call:JSONObject,generation:Long) {
        val job=scope.launch(start=CoroutineStart.LAZY) { tool(call,generation) }
        synchronized(toolJobs) { if(stopped) { job.cancel();return };toolJobs[job]=call.optString("id") }
        job.invokeOnCompletion { synchronized(toolJobs) { toolJobs.remove(job) } }
        job.start()
    }
    private fun callOwned() = !stopped&&ownerActive()&&client.scope==callScope
    /** Replace the session at once: resume it once, then start fresh from the call transcript; tool results are re-said as text, never re-run. */
    private fun stalledSession() {
        val stalls=stall.stalledAgain()
        diagnostic("provider_error",java.net.SocketTimeoutException("Voice stopped answering"))
        if(stalls>1) resume=null
        val again=synchronized(unanswered) { unanswered.toList().also { unanswered.clear() } }
        for(text in again) controls.trySend(Outbound(text,{ callOwned() },control=true))
    }
    /** Waits for the provider's setup; a failed context read ends the wait at once instead of after the handshake. */
    private suspend fun awaitSetup(setup:Deferred<Unit>,ended:Deferred<Unit>,load:ContextLoad) {
        var verified=false
        while(!setup.isCompleted) kotlinx.coroutines.selects.select<Unit> {
            setup.onAwait { };ended.onAwait { error("$provider ended before setup") }
            if(!verified) load.result.onAwait { if(!contextAdmits(load,it)) error("Call context retry");verified=true }
        }
    }
    // NativePcm reports presented bytes under its own lock and in queue order: the greeting's come first, then the lines being read.
    fun played(bytes:Int) {
        var left=bytes.toLong()
        while(true) {
            val owed=greetingOutstanding.get()
            if(owed<=0) break
            val taken=minOf(owed,left)
            if(!greetingOutstanding.compareAndSet(owed,owed-taken)) continue
            left-=taken
            if(taken>0&&greetingPresented.compareAndSet(false,true)) { val at=elapsedMs();scope.launch { stage("first_model_audio",at);diagnostic("playout_started",at=at) } }
            break
        }
    }
    fun send(value:JSONObject):Boolean {
        // Grok and OpenAI clear the input buffer on mute, which also cancels the live greeting: it waits for the greeting's end.
        val streamEnd=value.optJSONObject("realtimeInput")?.optBoolean("audioStreamEnd")==true
        val hold=provider!="gemini"&&streamEnd&&synchronized(greetingLock) { (greetingOpen&&!greetingCut).also { if(it) streamEndHeld=true } }
        if(hold) return ready&&!stopped
        return ready&&!stopped&&controls.trySend(Outbound(value.toString(),{ true },control=true)).isSuccess
    }
    /** Gives the live model a verified result to say once in its own words, as a user turn; false when no session can take it now. */
    private fun tellModel(text:String):Boolean {
        val note="Verified update from Hermes (tell the owner once, in your own words; it is not a request and never goes back to Hermes): ${text.take(12000)}"
        val turn=JSONObject().put("role","user").put("parts",org.json.JSONArray().put(JSONObject().put("text",note)))
        return send(JSONObject().put("clientContent",JSONObject().put("turns",org.json.JSONArray().put(turn)).put("turnComplete",true)))
    }
    private fun owe(again:String?) {
        stall.expect()
        again?.let { synchronized(unanswered) { if(unanswered.size>=8) unanswered.removeFirst();unanswered.addLast(it) } }
    }
    fun pcm(bytes:ByteArray,permitted:()->Boolean):Boolean {
        if(!ready||stopped||!permitted()) return false
        stall.speech(voiceLoud(bytes),bytes.size/2*1000L/(if(provider=="openai") GeminiWire.OUTPUT_RATE else GeminiWire.INPUT_RATE))
        val encoded=java.util.Base64.getEncoder().encodeToString(bytes)
        val frame=if(provider=="openai") OpenAiWire.audio(encoded) else GeminiWire.audio(encoded)
        return microphone.trySend(Outbound(frame.toString(),permitted,mic=true)).isSuccess
    }
    fun setVisualState(source:String,state:String,reason:String="") {
        // Only Gemini's live session takes video; the others have no vision to share.
        val unsupported=provider!="gemini"&&state!="off"
        visual.update(source,if(unsupported) "failed" else state,if(unsupported) "$provider voice does not support live vision" else reason)?.let(event)
    }
    fun image(bytes:ByteArray,permitted:()->Boolean,onSent:()->Unit={},onDropped:()->Unit={}):Boolean {
        if(provider!="gemini"||!ready||stopped||!permitted()) return false
        val backlog=socket?.queueSize()?:Long.MAX_VALUE
        visualPacing.drained(backlog)
        if(!visualPacing.mayQueue()||!GeminiWire.visualMayQueue(bytes.size,backlog,visualPending.get())||!visualPending.compareAndSet(false,true)) return false
        // The frame is kept for a hand-off to Hermes too; the live session gets the same bytes.
        val frame=if(visual.capture(bytes,permitted)) visual.current() else null
        if(frame==null) { visualPending.set(false);return false }
        val accepted=visualFrames.trySend(Outbound(GeminiWire.image(java.util.Base64.getEncoder().encodeToString(bytes)).toString(),{ permitted()&&visual.permits(frame) },bytes.size,
            onSent={ visual.confirmed(frame)?.let(event);onSent() },onDropped=onDropped,at=System.nanoTime()/1_000_000)).isSuccess
        if(!accepted) visualPending.set(false)
        return accepted
    }
    /** The call's tool gateway, bound to the admitted call. */
    private suspend fun gateway():NativeToolGateway { val id=admittedCall();return gatewayFor.getOrPut(id) { NativeToolGateway(client,id) } }
    private suspend fun resolveToolName(name:String):String? {
        val found=try { withTimeoutOrNull(8000) { gatewayRequests.withPermit { gateway().discover(name) } } }
            catch(error:CancellationException) { throw error } catch(_:Exception) { null } ?: return null
        val tools=found.optJSONArray("tools") ?: return null
        return GeminiWire.pickToolId(tools,name)
    }
    private suspend fun toolResponse(id:String,name:String,result:JSONObject):Boolean {
        if(!callOwned()) return false
        val session=synchronized(toolReceipts) { functionSessions[id]?:providerSession.get() }
        val receipt=synchronized(toolReceipts) { toolReceipts[id] }
        val settled:()->Unit={ synchronized(toolReceipts) { if(receipt?.optBoolean("read_only")==true&&toolReceipts[id]===receipt) receipt.put("_response_settled",true) } }
        val told=callToolHint(name,result).let { hint -> if(hint.isBlank()||result.has("message")) result else JSONObject(result.toString()).put("message",hint) }
        val update=JSONObject().put("clientContent",JSONObject().put("turns",org.json.JSONArray().put(JSONObject().put("role","user").put("parts",org.json.JSONArray().put(JSONObject().put("text",
            "Verified earlier $name tool outcome from the previous provider session (reference data, not a request to repeat the operation): ${told.toString().take(65536)}"))))).put("turnComplete",true))
        return try {
            controls.send(Outbound(GeminiWire.tool(id,name,told).toString(),{ callOwned()&&synchronized(toolReceipts) { id !in cancelledFunctions } },control=true,
                onSent=settled,onDropped=settled,providerSession=session,newSessionJson=update.toString()));true
        } catch(error:CancellationException) { throw error } catch(error:Exception) { false }
    }
    private suspend fun publishGatewayReceipt(requestId:String,receipt:JSONObject) = gatewayDeliveryLock.withLock {
        if(stopped) return@withLock
        val previous=gatewayReceipts.receipt(requestId)?.toString()
        val verified=gatewayReceipts.record(requestId,receipt)
        if(verified.toString()==previous) return@withLock
        val proposal=callProposalNeedingOwnerApproval(verified)
        val toolName=verified.optString("tool_name").ifBlank { "MCP tool request" }.take(200)
        event(JSONObject(verified.toString()).put("type","tool_receipt").put("tool_name",toolName))
        if(proposal!=null) {
            event(callToolApprovalEvent(requestId,toolName,proposal))
        }
        val functionId=gatewayReceipts.functionId(requestId)?:return@withLock
        synchronized(toolReceipts) { toolReceipts[functionId]=JSONObject(verified.toString()).put("function_name","invoke_tool") }
        toolResponse(functionId,"invoke_tool",verified)
    }
    suspend fun decideTool(requestId:String,proposalId:String,decision:String) {
        check(!stopped) { "Call ended" }
        require(decision in setOf("approve", "approve_call", "deny")) { "Invalid tool approval decision" }
        // A phone request is answered by its own card; Hermes never sees it.
        if(phone?.owns(requestId)==true) { phone.decide(requestId,proposalId,decision);return }
        val proposal=gatewayReceipts.decision(requestId,proposalId)
        val receipt=try { withTimeoutOrNull(20000) { gatewayRequests.withPermit { check(callOwned()) { "Call authority changed" };gateway().decide(requestId,proposalId,decision) } } ?: throw java.io.IOException("Tool decision timed out") }
        catch(error:CancellationException) { throw error }
        catch(error:Exception) { JSONObject(proposal.toString()).put("status","uncertain").put("reason","Approval outcome unconfirmed; reading status, never replaying the action.").apply { remove("proposal") } }
        if(!stopped) publishGatewayReceipt(requestId,receipt)
    }
    private suspend fun tool(call:JSONObject,generation:Long) {
        if(stopped) return
        val id=call.optString("id");val name=call.optString("name")
        if(id.isBlank()||id.length>256) {
            event(JSONObject().put("type","warning").put("message","Provider function identifier was invalid; no work was submitted."))
            socket?.close(1002,"Invalid function identifier")
            return
        }
        val phoneTool=phone!=null&&PhoneVoiceTools.handles(name)
        if(name !in DIRECT_CALL_TOOLS&&!phoneTool) {
            toolResponse(id,name,JSONObject().put("status","unsupported").put("message","Unknown function; no work was submitted."))
            return
        }
        // A conversation that started on a locked phone and has not been unlocked since may talk, but nothing private is read or done:
        // every Hermes tool answers at once, before admission or any request (the phone's own tools are held back in PhoneVoiceBridge).
        if(!trust.trusted()&&name in DIRECT_CALL_TOOLS) { toolResponse(id,name,lockedToolResult());return }
        // Every Hermes action belongs to the admitted call; none runs before Hermes admitted it.
        // Phone tools run on this phone and never reach Hermes, so they do not wait for its admission.
        if(name!="get_visual_state"&&!phoneTool) try { admittedCall() } catch(_:CallAdmissionFailed) {
            toolResponse(id,name,JSONObject().put("status","not_sent").put("message","That request was not sent: Hermes did not admit this call. Tell the owner to try again."));return
        }
        val requestId=java.util.UUID.nameUUIDFromBytes((callId+":"+id).toByteArray(Charsets.UTF_8)).toString()
        val readOnly=directToolIsReadOnly(name)
        val lookup=name in setOf("discover_tools","hermes_search","hermes_memory","hermes_skills","hermes_context")
        val previous=synchronized(toolReceipts) { toolReceipts[id] }
        if(previous!=null) {
            if(previous.optString("function_name")!=name) { event(JSONObject().put("type","warning").put("message","Provider reused a function identity with a different name; no new action was submitted."));return }
            toolResponse(id,name,previous);return
        }
        // Reserve before suspension: duplicate tool frames must never repeat an ambiguous mutation.
        val reserved=JSONObject().put("status","submitting").put("request_id",requestId).put("function_name",name)
        val full=synchronized(toolReceipts) {
            if(toolReceipts.containsKey(id)) return
            if(toolReceipts.size>=64) toolReceipts.entries.firstOrNull {
                it.value.optString("receipt_reported").isNotBlank() || it.value.optString("status") == "not_sent" ||
                    it.value.optBoolean("read_only")&&it.value.optBoolean("_response_settled")
            }?.key?.let { toolReceipts.remove(it);functionSessions.remove(it);cancelledFunctions.remove(it) }
            if(toolReceipts.size>=64) true else { toolReceipts[id]=reserved;functionSessions[id]=providerSession.get();false }
        }
        if(full) { toolResponse(id,name,JSONObject().put("status","rejected").put("message","Call work limit reached. Tell the owner to start a new call to continue."));return }
        if(lookup) event(JSONObject().put("type","tool_receipt").put("request_id",requestId).put("tool_name",name).put("status","running"))
        val result=try {
            if(name=="discover_tools") {
                withTimeoutOrNull(15000) { gatewayRequests.withPermit { gateway().discover(call.optJSONObject("args")?.optString("query").orEmpty()) } } ?: throw java.net.SocketTimeoutException("Tool discovery timed out")
            } else if(name=="get_visual_state") {
                visual.snapshot()
            } else if(name=="tool_status") {
                val existing=call.getJSONObject("args").getString("request_id")
                require(gatewayReceipts.receipt(existing)!=null) { "Unknown call tool request" }
                val receipt=withTimeoutOrNull(8000) { gatewayRequests.withPermit { gateway().status(existing) } } ?: throw java.io.IOException("Tool status timed out")
                publishGatewayReceipt(existing,receipt)
                gatewayReceipts.receipt(existing)!!
            } else if(name=="invoke_tool") {
                val args=call.getJSONObject("args")
                require(args.keys().asSequence().toSet()==setOf("tool_id","arguments")) { "Only tool identity and arguments are accepted" }
                val requested=args.getString("tool_id")
                require(requested.matches(Regex("[a-f0-9]{64}|[A-Za-z0-9_.:/-]{1,128}")))
                val rawArguments=args.getString("arguments");require(rawArguments.toByteArray(Charsets.UTF_8).size<=16384)
                val arguments=JSONObject(rawArguments)
                // The call's catalogue lists names: resolve one to its current tool_id with a quick lookup, not a model turn.
                val toolId=if(requested.matches(Regex("[a-f0-9]{64}"))) requested else resolveToolName(requested)
                if(toolId==null) JSONObject().put("status","failed").put("request_id",requestId).put("tool_name",requested)
                    .put("reason","Not run: no tool named $requested; use discover_tools.") else {
                val duplicate=gatewayReceipts.reserve(requestId,id,toolId,arguments)
                if(duplicate!=null) duplicate else {
                if(!claimMutation(id)) return
                var receipt=try { withTimeoutOrNull(20000) { gatewayRequests.withPermit { check(callOwned()) { "Call authority changed" };gateway().invoke(requestId,toolId,arguments) } } ?: throw java.io.IOException("Tool submission timed out") }
                catch(error:CancellationException) { throw error }
                catch(error:Exception) { directInvokeFailure(requestId,toolId,error) }
                try { publishGatewayReceipt(requestId,receipt) }
                catch(error:CancellationException) { throw error }
                catch(error:Exception) {
                    receipt=JSONObject().put("request_id",requestId).put("tool_id",toolId).put("tool_name","MCP tool request").put("status","uncertain").put("reason","Tool receipt identity could not be verified; read status and never replay the action.")
                    publishGatewayReceipt(requestId,receipt)
                }
                gatewayReceipts.receipt(requestId)!!
                }
                }
            } else if(phoneTool) {
                require(!call.has("args")||call.opt("args") is JSONObject)
                checkNotNull(phone).call(requestId,name,call.optJSONObject("args")?:JSONObject(),event)
            } else if(name in setOf("hermes_search","hermes_memory","hermes_skills","hermes_context")) {
                require(!call.has("args")||call.opt("args") is JSONObject)
                val read=GeminiWire.nativeRead(name,call.optJSONObject("args")?:JSONObject())
                require(GeminiWire.capable(context,read.capability))
                val evidence=callLookup {
                    check(callOwned()) { "Call authority changed" }
                    client.nativeRequest("/api/plugins/beacon/mobile/calls/$callId/${read.route}","POST",read.body)
                }
                check(callOwned()) { "Call authority changed" }
                GeminiWire.evidence(evidence,name=="hermes_search")
            } else if(name=="hermes_callback") {
                val selection=call.optJSONObject("args")?.optString("scope","latest")?:"latest";require(selection in setOf("latest","all"))
                val hasJob=synchronized(toolReceipts) { toolReceipts.values.lastOrNull { it.optString("function_name")=="hermes_work" }?.optString("job_id")?.isNotBlank()==true }
                if(!hasJob || !claimMutation(id)) JSONObject().put("status","not_sent") else client.nativeRequest("/api/plugins/beacon/mobile/calls/$callId/callback","POST",JSONObject().put("scope",selection))
            } else if(name=="hermes_cancel") {
                val jobId=call.getJSONObject("args").getString("job_id")
                check(callOwned()) { "Call ended before job lookup" }
                val owned=client.nativeRequest("/api/plugins/beacon/mobile/calls/$callId").getJSONArray("jobs")
                val job=(0 until owned.length()).map { owned.getJSONObject(it) }.firstOrNull { it.optString("job_id")==jobId }?:error("Unknown call job")
                // A finished job has nothing left to stop; its runtime may already be working on something newer.
                val running=job.optString("status") in setOf("working","watching","needs_input") || job.optString("status")=="uncertain"&&job.optBoolean("will_continue")
                if(!running) JSONObject().put("status","not_sent").put("reason","job_finished").put("message","That job already finished, so nothing was cancelled.")
                else if(!claimMutation(id)) JSONObject().put("status","not_sent") else client.nativeRpc("session.interrupt",JSONObject().put("session_id",job.getString("runtime_session_id")))
            } else {
            require(name=="hermes_work")
            val args=call.getJSONObject("args");val text=args.getString("text");require(text.isNotBlank()&&text.length<=16000)
            require(args.keys().asSequence().toSet()==setOf("text")) { "Voice requests stay in their attached conversation" }
            check(callOwned()) { "Call ended before native admission" }
            ensureNativeEvents()
            require(selectedSession.isNotBlank()) { "The attached conversation is unavailable" }
            val binding=client.nativeSession(selectedSession)
            // The same agent as text chat owns reasoning, memory, tools and approvals.
            // Hermes' approval read itself may wait up to 30 seconds for a cold agent build.
            if(directAdmissionAttempt(45000,{true}) {
                awaitNativeVoiceAgent(client,binding)
                true
            }!=true) throw java.net.SocketTimeoutException("Native agent admission timed out")
            val waiting=JSONObject().put("status","waiting_for_idle").put("request_id",requestId).put("stored_session_id",binding.storedSessionId).put("function_name",name).put("message","This request is waiting locally for Hermes to become idle. It has not been submitted and will be cancelled if this call ends.").put("willContinue",true)
            synchronized(toolReceipts) { toolReceipts[id]=waiting }
            toolResponse(id,name,waiting)
            submissionLock.withLock {
                var legacyJobCapacity=false
                val receipt=submitNativeWhenIdle({callOwned()},{
                    // This read settles old watches; a still-live watch cannot own a second prompt.
                    val status=client.nativeRequest("/api/plugins/beacon/mobile/calls/$callId")
                    val jobs=status.optJSONArray("jobs") ?: throw java.io.IOException("Call job capacity is unavailable")
                    legacyJobCapacity=jobs.length()<64
                    val watching=status.optString("job_linkage")!="row_bound" && (0 until jobs.length()).any { i -> jobs.optJSONObject(i)?.let { job ->
                        job.optString("runtime_session_id")==binding.runtimeSessionId &&
                            (job.optString("status") in setOf("working","watching","needs_input") || job.optString("status")=="uncertain" && job.optBoolean("will_continue"))
                    } == true }
                    !watching && client.nativeSessionStatus(binding.storedSessionId,binding.runtimeSessionId)=="idle"
                },{
                    val claimed=synchronized(toolReceipts) { if(!callOwned()) false else { toolReceipts[id]=JSONObject(waiting.toString()).put("status","staging_native");true } }
                    if(!claimed) JSONObject().put("status","not_sent") else {
                        val frame=visual.current()
                        val missingFrame=frame==null && visual.active()
                        if(missingFrame) event(JSONObject().put("type","warning").put("message","A fresh shared frame was not available for this request."))
                        val jpeg=frame?.let { java.util.Base64.getEncoder().encodeToString(it.bytes) }
                        val prompt=if(missingFrame) "$text\n[The current shared image could not be attached.]" else text
                        val result=client.nativeVoiceSubmit(binding,JSONObject().put("session_id",binding.runtimeSessionId).put("text",prompt)
                            .put("submission_id",requestId).put("surface","voice-live").put("queued",true),jpeg,legacyJobCapacity,
                            ownerFence,{ callOwned() },{
                                synchronized(toolReceipts) { toolReceipts[id]?.takeIf { it.optString("status")=="staging_native" }?.let { it.put("status","submitting_native");true } == true }
                            },{ callOwned() && frame != null && visual.permits(frame) })
                        if(frame!=null && result.optString("voice_visual") in setOf("image","file_reference")) visual.confirmed(frame)?.let(event)
                        if(frame!=null && result.optString("voice_visual")=="unavailable") event(JSONObject().put("type","warning").put("message","The current shared frame could not be attached to this request."))
                        result
                    }
                },{delay(1000)})
                if(receipt.optString("status")=="not_sent") {
                    JSONObject().put("status","not_sent").put("reason",receipt.optString("reason")).put("request_id",requestId).put("stored_session_id",binding.storedSessionId)
                } else if(receipt.optString("status")=="unknown" || receipt.optBoolean("voice_authority") && receipt.optString("admission_id").isBlank()) {
                    JSONObject().put("status","submission_unconfirmed").put("request_id",requestId)
                } else if(receipt.optBoolean("voice_authority")) {
                    JSONObject().put("status",receipt.optString("status")).put("request_id",requestId).put("admission_id",receipt.getString("admission_id"))
                        .put("stored_session_id",binding.storedSessionId).put("runtime_session_id",binding.runtimeSessionId)
                } else if(!receipt.has("user_row_id")||receipt.isNull("user_row_id")) {
                    JSONObject().put("status",receipt.optString("status","accepted_unlinked")).put("request_id",requestId)
                        .put("stored_session_id",binding.storedSessionId).put("runtime_session_id",binding.runtimeSessionId)
                } else {
                    val linked=client.nativeRequest("/api/plugins/beacon/mobile/calls/$callId/jobs","POST",JSONObject().put("stored_session_id",binding.storedSessionId)
                        .put("runtime_session_id",binding.runtimeSessionId).put("request_id",requestId).put("user_row_id",receipt.opt("user_row_id")).put("title",text.take(100)).put("callback",false))
                    require(linked.optString("job_id").isNotBlank()) { "Native job linkage was not confirmed" }
                    JSONObject().put("status",receipt.optString("status","submitted")).put("job_id",linked.getString("job_id")).put("request_id",requestId)
                        .put("stored_session_id",binding.storedSessionId).put("runtime_session_id",binding.runtimeSessionId)
                }
            }
            }
        } catch(error:CancellationException) { throw error }
        catch(e:Exception) {
            val stage=synchronized(toolReceipts) { toolReceipts[id]?.optString("status") }
            when {
                // The plugin answers this at once while Hermes updates (admin_api.UPDATING), before anything runs.
                e is ApiFailure&&e.status==503&&e.message=="Hermes is updating" -> JSONObject().put("status","not_sent").put("error_code","hermes_updating").put("message","Hermes is updating, so nothing ran. Tell the user to try again in a few minutes.")
                name in setOf("hermes_search","hermes_memory","hermes_skills","hermes_context","discover_tools","tool_status","get_visual_state") -> JSONObject().put("status","unavailable").put("error_code",errorCode(e)).put("message",(if(errorCode(e)=="timeout") "This lookup timed out." else "This lookup failed.")+" No current result was verified; report the failure without inventing facts or claiming the service is disconnected.")
                name=="hermes_work"&&stage in setOf("creating_native_session","waiting_for_idle","submitting","staging_native") -> JSONObject().put("status","not_sent").put("request_id",requestId).put("message","The request was not submitted because native admission failed or the call ended.")
                stage=="submitting" -> JSONObject().put("status","not_sent").put("request_id",requestId).put("message","Native request validation failed; no mutation was attempted.")
                else -> JSONObject().put("status","submission_unconfirmed").put("request_id",requestId).put("message","Do not retry automatically; submission or linkage may have succeeded.")
            }
        }
        if(!callOwned()) return
        val retained=if(readOnly&&result.toString().toByteArray(Charsets.UTF_8).size>65536) JSONObject().put("status","unavailable").put("message","This lookup exceeded the call result limit. No bounded result was verified.") else result
        if(lookup) event(directLookupReceipt(requestId,name,retained))
        synchronized(toolReceipts) { toolReceipts[id]=JSONObject(retained.toString()).put("function_name",name).put("read_only",readOnly) }
        if(name!="invoke_tool"||gatewayReceipts.receipt(requestId)==null) toolResponse(id,name,retained)
    }
    private fun claimMutation(id:String):Boolean=synchronized(toolReceipts) {
        if(!callOwned()) false else { toolReceipts[id]?.put("status","submitting_native");true }
    }
    fun close():DirectCallEnd {
        val disposition=synchronized(ownerFence) {
            synchronized(toolReceipts) {
                stopped=true
                DirectCallEnd(toolReceipts.values.count { (it.optString("function_name")=="hermes_work"&&it.optString("status") in setOf("waiting_for_idle","submitting","staging_native")) || it.optString("status")=="needs_approval" },
                    toolReceipts.values.count(::outcomeUnknownAtCallEnd))
            }
        }
        epoch.incrementAndGet();ready=false;visual.clear();phone?.close()
        synchronized(greetingLock) { greetingOpen=false;heldGreeting.clear() }
        if(!warmUsed) { warmUsed=true;warm?.close() }
        // Stop local reads/idle waits; accepted native writes still settle their receipt once.
        val localJobs=synchronized(toolJobs) { toolJobs.filterValues { id -> synchronized(toolReceipts) { directToolMayCancel(toolReceipts[id]?.optString("status")) } }.keys.toList() }
        localJobs.forEach(Job::cancel)
        // Drain native events until the service closes this client after accepted writes settle.
        socket?.cancel();run?.cancel();polling?.cancel();gatewayPolling?.cancel();microphone.close();controls.close();visualFrames.cancel();providerHttp.dispatcher.executorService.shutdown()
        // close() can run on the main thread; evicting pooled TLS sockets writes to the network.
        kotlin.concurrent.thread(name = "beacon-voice-close") { providerHttp.connectionPool.evictAll() }
        return disposition
    }
}
