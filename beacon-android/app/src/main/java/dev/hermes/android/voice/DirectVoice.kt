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
import dev.hermes.android.core.string


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
    // Canonical Hermes admits durably before it builds an agent; its FIFO also owns busy turns.
    if(client.nativeTurnLease(binding.storedSessionId,binding.runtimeSessionId).authority) return
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
    return receipt.optString("function_name") in setOf("hermes_work","hermes_follow")&&status in setOf("creating_native_session","submitting_native","submission_unconfirmed") ||
        status=="uncertain"&&!receipt.optBoolean("read_only",false)
}
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
/** The call's chat stream (Hermes' approval and clarify prompts) was lost; cleared when Hermes is back (device round 2). */
internal const val CALL_LOST_HERMES = "Lost Hermes. Check the chat."
/** Gemini closes a connection after ~120 s with no client message; the client sends a short silence after this long without one. */
internal const val CLIENT_KEEPALIVE_MS = 30_000L
/** 100 ms of silence at 16 kHz, base64: never microphone data. */
internal val SILENCE_FRAME: String = java.util.Base64.getEncoder().encodeToString(ByteArray(3200))
/** How many non-audio frames a debug build keeps for the call's device tests. */
internal const val FRAME_LOG_LIMIT = 1_000
/** A reconnect's read of the call's context; past it the call goes on with the context it verified. */
internal const val RECONNECT_CONTEXT_MS = 4000L
/** How long a call-back request waits for the owner's words to arrive before it is refused. */
internal const val OWNER_WORDS_GRACE_MS = 1500L
/** How long the lost link's probe stays after the link's return: a loss within it goes on with the probe's backoff (REVIEW-E7 P3-1). */
internal const val LINK_FLAP_MS = 3_000L
/** The phone could not reach Hermes for [CALL_HERMES_OUTAGE_MS]: the call ends with the "Can't reach Hermes" line (APP_CONNECTION_ERROR). */
internal class HermesOutage : Exception("Hermes out of reach")
internal const val CALL_VOICE_STOPPED = "Voice stopped answering. Call again."
/** The call-start stages, in order; a failed start reports the first one that did not finish. */
internal val CALL_START_STAGES = listOf("grant_ok", "context_ok", "provider_setup_ok", "mic_streaming")
/** The steps a call takes with Hermes (its grant and the conversation's context), not with the voice service. */
internal fun hermesStage(stage: String) = stage == "grant_ok" || stage == "context_ok"
internal fun callFailureText(stage: String, error: Throwable?): String = when {
    error is ApiFailure && error.status in setOf(401, 403) -> CALL_SIGN_IN_EXPIRED
    // The phone could not connect to Hermes at all (a VPN that dropped): say so, not that Hermes stayed silent (owner, 4 Nov 2026).
    hermesStage(stage) && error != null && dev.hermes.android.data.requestNeverSent(error) -> dev.hermes.android.data.APP_CONNECTION_ERROR
    stage == "grant_ok" || stage == "context_ok" -> CALL_HERMES_NO_ANSWER
    stage == "mic_streaming" -> CALL_MIC_FAILED
    else -> CALL_VOICE_NO_ANSWER
}
/** Hermes refused or never answered the call's admission; the service ends the call with the grant's own reason. */
internal class CallAdmissionFailed(cause: Throwable) : Exception(cause)

private val TOOL_ID = Regex("[a-f0-9]{64}")
/** The plugin's own limits for a tool search and for a search, memory, skills or profile read. */
private const val DISCOVER_WAIT_MS = 10_000L
private const val LOOKUP_WAIT_MS = 12_000L
/** How long one long-poll of the call's updates waits on Hermes for an event (contract section 3). */
private const val CALL_UPDATES_WAIT_MS = 20_000L

internal class DirectVoice(private val client:BeaconClient,callId:String,private val selectedSession:String,private val scope:CoroutineScope,
    private val audio:(ByteArray)->Unit,private val event:(JSONObject)->Unit,private val failure:(String)->Unit,
    private val credentials:VoiceSessionCredentials,private val providerSocketFactory:WebSocket.Factory?=null,private val ownerName:String="",stallAfterMs:Long=20_000,
    private val ownerFence:Any=Any(),private val ownerActive:()->Boolean={true},
    private val clock:()->Long={ System.nanoTime()/1_000_000 },tappedAt:Long=0,
    /** A live session opened before the tap with this conversation's context; the call takes it over. */
    private val warm:LiveSession?=null,
    /** This conversation's context read before the tap; without it the call reads it now. */
    private val prefetched:JSONObject?=null,
    /** An authenticated, scoped context read started before the service/Telecom pipeline; used only after the call re-validates it. */
    private val earlyContext:Deferred<JSONObject?>?=null,
    /** Tap-clock time the early read started, for the stage log. */
    private val earlyContextAt:Long=0,
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
    private val trust:CallTrust=CallTrust.open(),
    private val microphoneMuted:()->Boolean={false}) {
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
        if(sent) {
            ownerSpoke()
            // A reply whose end waits for its audio ends first: its caption turn closes whole, then the typed line starts the next one (REVIEW-E7
            // P2-1, Root's option (a); before, the line joined that turn and was stored ahead of the answer it followed). Typing never silences
            // the model, so the reply's tail plays on; a spoken cut of that tail no longer trims its line.
            synchronized(repeatLock) {
                assistantCaption(repeats.ownerSpoke())
                if(replyEnding!=0) finishReply()
                event(JSONObject().put("type","transcript").put("role","user").put("text",words).put("final",true))
            }
        }
        return sent
    }
    // [build]: the frame as it is sent, or null to drop it (a release leaves out a function the provider cancelled after it was queued).
    private data class Outbound(val json:String,val permitted:()->Boolean,val visualBytes:Int=0,val control:Boolean=false,val mic:Boolean=false,val onSent:()->Unit={},val onDropped:()->Unit={},
        val providerSession:Long?=null,val newSessionJson:String?=null,val at:Long=0,val build:(()->String?)?=null)
    private val providerSession=AtomicLong()
    // A session that took the owner's speech or a tool result and never answered is replaced; what it was owed is said again.
    private val stall=VoiceStallWatch(limit=stallAfterMs);private val stallPoll=(stallAfterMs/4).coerceIn(50,1000)
    private val unanswered=ArrayDeque<String>()
    private val visualPacing=VisualPacing()
    private var run:Job?=null;private val microphone=Channel<Outbound>(20)
    private val controls=Channel<Outbound>(Channel.UNLIMITED)
    private var retryControl:Outbound?=null
    private val visualFrames=Channel<Outbound>(Channel.CONFLATED,onUndeliveredElement={it.onDropped()})
    private val visualPending=java.util.concurrent.atomic.AtomicBoolean()
    private val visual=NativeVoiceVisual()
    private val gatewayFor=java.util.concurrent.ConcurrentHashMap<String,NativeToolGateway>()
    private val gatewayReceipts=DirectGatewayReceipts()
    // The call's own client for Hermes: pinged connections, so a stalled link is noticed in seconds (calls contract v3, section 6).
    private val calls=client.forCall()
    // What each function call was told, and the link state that tells it when Hermes cannot be reached.
    private val link=CallLinkState(clock,::sendToolResponse,::toolWindow,::recovered,::linkLost,later={ ms,block -> scope.launch { delay(ms);block() } },
        settled=::changeSettled,probed=true,verifyOverlap=::verifyCallReach)
    /** Ends the call when Hermes stays out of reach this long (device round 1); a test may shorten it. */
    @Volatile internal var outageLimitMs=CALL_HERMES_OUTAGE_MS
    @Volatile private var outageWatch:Job?=null
    // The lost link's probe ([probeLink]), and the lock its start and end share with the outage timer's.
    private var probe:Job?=null
    private val outageLock=Any()
    // The owner's speech (activity, transcription or typed words), counted: the call back needs words after the model asked (device round 1).
    private val ownerWords=AtomicLong()
    /** How long the client may send nothing before it sends a short silence (Gemini's inactivity limit is ~120 s); a test may shorten it. */
    @Volatile internal var keepAliveMs=CLIENT_KEEPALIVE_MS
    @Volatile private var lastClientFrameAt=0L
    // A resumption handle the provider refused once with a transient close: refused again, it is dropped.
    @Volatile private var refusedHandle:String?=null
    // The current provider connection's session resumption updates (for the diagnostic of its end).
    @Volatile private var resumeUpdates=0
    @Volatile private var lastResumable:Boolean?=null
    // Debug builds only: the call's newest non-audio frames to and from the live model (CallsFaultDeviceTest writes them into its result).
    private val frames=ArrayDeque<JSONObject>()
    private fun logFrame(direction:String,frame:JSONObject) {
        if(!dev.hermes.android.BuildConfig.DEBUG||direction=="in"&&voiceAudioOnly(frame)) return
        val row=voiceFrameSummary(direction,frame,elapsedMs())
        synchronized(frames) { if(frames.size>=FRAME_LOG_LIMIT) frames.removeFirst();frames.addLast(row) }
    }
    /** Debug builds: the call's newest non-audio frames, oldest first. */
    internal fun frameLog():List<JSONObject> = synchronized(frames) { frames.toList() }
    // A hand-over was said to the model and its next turn (where it asks about a call back) has not ended yet.
    @Volatile private var callBackTurnOpen=false
    // ownerWords when that turn ended; -1 until a hand-over's turn ended, again when a new hand-over arrives and once an answer turned it on.
    private val callBackAskedAt=AtomicLong(-1)
    // The calls of a frame that change something, by id, until each has its final result: a later call in the same frame waits for them,
    // so the calls run in the order the model gave them where it matters (device round 3: a read beside a write answered first, with the
    // old value, and the model then said the tool "confirms" the new one).
    private val changesDone=java.util.concurrent.ConcurrentHashMap<String,CompletableDeferred<Unit>>()
    // For a call after such changes in its frame, their ids.
    private val waitsFor=java.util.concurrent.ConcurrentHashMap<String,List<String>>()
    // For each hermes_callback, callBackAskedAt as it stood when its frame arrived (REVIEW-E3 finding 1).
    private val callBackAsked=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private fun ownerSpoke() { ownerWords.incrementAndGet() }
    private val gatewayDeliveryLock=Mutex()
    private val gatewayRequests=Semaphore(4)
    // When the audio queued for the speaker ends (this clock): the owner talking before it cuts the reply short.
    @Volatile private var playUntil=0L
    private val toolReceipts=mutableMapOf<String,JSONObject>()
    private val functionSessions=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private val cancelledFunctions=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var polling:Job?=null
    private var nativeEvents:Job?=null
    // The chat stream ended with a failure: started again once the link to Hermes is back.
    @Volatile private var nativeEventsLost=false
    private val reconnects=VoiceReconnects()
    @Volatile private var ready=false;@Volatile private var stopped=false
    @Volatile private var greeted=false
    @Volatile private var context=JSONObject()
    private val callScope=client.scope
    private val toolJobs=mutableMapOf<Job,String>()
    private val submissionLock=Mutex()
    @Volatile private var contextReady=false
    private val contextLoaded=CompletableDeferred<Unit>()
    // An older plugin has no conversation context route: the call reads its own context once Hermes admitted it.
    @Volatile private var callContextOnly=callback
    // A setup the provider refused (bad key, unknown model): retrying cannot help, so the call ends with the reason.
    @Volatile private var providerRejection:String?=null
    // The provider refused a setup that declared the profile's own tools: how many the context had. Later setups declare none
    // (once per call; discover_tools still finds them), and their stage log says so.
    @Volatile private var refusedDeclarations=0
    private val connectingSince=System.nanoTime()
    // Connected = the provider answered setup, the microphone streams into it and Hermes admitted the call. Until then the call shows connecting.
    @Volatile private var admitted=false;@Volatile private var micStreaming=false;@Volatile private var connected=false
    private val stages=java.util.concurrent.ConcurrentHashMap<String,Long>()
    // The live model's greeting: its turn is open from the start note until the model finishes it or the owner talks over it.
    private val greetingLock=Any()
    @Volatile private var greetingOpen=false
    private var greetingHeard=false // guarded by greetingLock: the model's greeting audio has arrived
    private var greetingCut=false // guarded by greetingLock
    private var greetingComplete=false // guarded by greetingLock
    private var greetingReported=false // guarded by greetingLock
    private var greetingFlushing=false // guarded by greetingLock
    private val greetingText=StringBuilder() // guarded by greetingLock
    private var streamEndHeld=false // guarded by greetingLock: a mute during the greeting, sent once it ends
    private val heldGreeting=ArrayList<ByteArray>() // guarded by greetingLock: audio that arrived before the call connected
    private val greetingOutstanding=java.util.concurrent.atomic.AtomicLong()
    private val greetingPresented=java.util.concurrent.atomic.AtomicBoolean()
    /** When the live model's first greeting audio reached the phone, on the tap clock (-1 before); live timing reads it. */
    @Volatile internal var greetingArrivedMs=-1L; private set
    @Volatile private var warmUsed=false
    @Volatile private var running=false
    // Whether the brief the current session was set up with held the owner's memory. It starts true: nothing is withheld until a setup is sent locked.
    @Volatile private var briefedTrusted=true
    // The unlock replaced a session set up without the owner's memory: the new one is told a turned-away request is owed (see TRUSTED_CUE).
    @Volatile private var rebrief=false
    // Ends the session this loop turn runs, so the next turn sets up a new one; null between sessions.
    @Volatile private var replaceSession:(()->Unit)?=null
    private fun briefTrust():Boolean=trust.trusted().also { briefedTrusted=it }
    private fun admissionBudget(maximum:Long):Long {
        if(greeted) return maximum
        val left=20000-(System.nanoTime()-connectingSince)/1000000
        if(left<=0) throw java.net.SocketTimeoutException("Voice admission deadline")
        return minOf(maximum,left)
    }
    /** [at] is a phase that happened earlier on the tap clock; [warm] marks work done before the tap; [failed] names the stage a failed call did not finish. */
    private fun diagnostic(stage:String,error:Throwable?=null,closeCode:Int?=null,at:Long?=null,warm:Boolean?=null,failed:String?=null,
                           closeReason:String?=null,extra:JSONObject?=null) {
        if(stopped) return
        event(JSONObject().put("type","media_diagnostic").put("stage",stage)
            .put("elapsed_ms",at?:elapsedMs()).put("provider",provider).put("attempt",reconnects.failures).apply {
                warm?.let { put("warm",it) }
                failed?.let { put("failed_stage",it) }
                error?.let { put("error_code",diagnosticErrorCode(it));put("cause_class",voiceTransportCause(it)) }
                closeCode?.takeIf { it in 1000..4999 }?.let { put("close_code",it) }
                closeReason?.let(::providerCloseReason)?.let { put("close_reason",it) }
                extra?.let { for(key in it.keys()) put(key,it.get(key)) }
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
        if(!callContextOnly) try { return calls.nativeVoiceContext(selectedSession,ownerName) }
        // An older plugin has no such route ("Not Found"); a chat the grant is still saving is not readable yet. Both read the call's own context once admitted.
        catch(error:ApiFailure) { if(error.status!=404) throw error;callContextOnly=true }
        return calls.nativeCallContext(admittedCall(),ownerName)
    }
    private suspend fun refreshContext(timeout:Long,first:Boolean) {
        // A read in flight keeps the last verdict; admission waits for this read's own result.
        try {
            val known=if(first) prefetched?.takeIf { contextMatches(it,callScope.session(selectedSession)) } else null
            if(first&&known!=null) { context=checkedContext(known);contextReady=true;contextLoaded.complete(Unit);stage("context_ok",warm=true);return }
            if(first&&earlyContext!=null) {
                stage("context_sent",at=earlyContextAt.takeIf { it>0 }?.let { (it-origin).coerceAtLeast(0) }?:elapsedMs())
                // The early read is authenticated and scoped, but this call still re-validates its identity before using it.
                val value=try { withTimeoutOrNull(timeout) { earlyContext.await() } }
                    catch(error:CancellationException) { currentCoroutineContext().ensureActive();null }
                if(value!=null&&contextMatches(value,callScope.session(selectedSession))) {
                    context=checkedContext(value);contextReady=true;contextLoaded.complete(Unit);stage("context_ok");return
                }
            }
            if(first) stage("context_sent")
            val fresh=checkedContext(withTimeout(timeout) { readContext() })
            context=fresh;contextReady=true;contextLoaded.complete(Unit)
            if(first) stage("context_ok")
        } catch(error:Throwable) { contextReady=false;throw error }
    }
    private class ContextLoad(val result:Deferred<Throwable?>,val reconnect:Boolean)
    private class ContextFailed(val error:Throwable):Exception()
    /**
     * Reads the scoped context alongside the provider socket; resolves to null when verified, else to the error. A reconnect of a
     * call that is already on (greeted) keeps the context it verified while Hermes cannot be reached: the provider's own reconnect
     * (every 2 to 3 minutes) ended the call during a longer outage (device round 1). Hermes' own refusal still ends it.
     */
    private fun CoroutineScope.loadContext(timeout:Long,reconnect:Boolean)=ContextLoad(async {
        val keep=reconnect&&greeted
        if(keep&&link.link==Link.LOST) { contextReady=true;return@async null }
        try { refreshContext(if(keep) minOf(timeout,RECONNECT_CONTEXT_MS) else timeout,first=!reconnect);null }
        catch(error:CancellationException) { currentCoroutineContext().ensureActive();if(keep&&error is TimeoutCancellationException) { contextReady=true;null } else error }
        catch(error:Exception) { if(keep&&error is java.io.IOException&&error !is ApiFailure) { contextReady=true;null } else error }
    },reconnect)
    /** One short, authenticated read to distinguish a broken stream from a broken call connection. */
    private fun verifyCallReach(done: (Throwable?) -> Unit) {
        scope.launch {
            if (!callOwned()) return@launch
            val error = try {
                withTimeoutOrNull(CALL_REQUEST_MARGIN_MS) { calls.nativeRequest("/api/plugins/beacon/mobile/calls/$callId") }
                    ?: throw CallRequestTimeout()
                null
            } catch (e: CancellationException) { throw e }
            catch (_: ApiFailure) { null } // an HTTP refusal is also a fresh Hermes answer
            catch (e: Exception) { e }
            if (callOwned()) done(error)
        }
    }

    /** Hermes has been out of reach since the link was lost: the call goes on, and ends only after [outageLimitMs]. */
    private fun linkLost() {
        if(stopped) return
        synchronized(outageLock) {
            outageWatch?.cancel()
            outageWatch=scope.launch {
                delay(outageLimitMs)
                if(link.link==Link.LOST&&!stopped&&callOwned()) { run?.cancel();fail(HermesOutage()) }
            }
            // A probe still on from a return moments ago goes on with its backoff.
            if(probe==null) probe=scope.launch { probeLink() }
        }
    }
    /**
     * While the link is lost, its return is found by a short read Hermes answers at once (the call's job list), on the retry backoff: "Lost
     * Hermes" clears within about 2-3 s of Hermes answering, whatever long server wait is in flight (device round 4, F1: the first request
     * after a 3-minute outage was an updates poll Hermes held its full 20 s). A ping's answer proves only the transport.
     *
     * Only this probe tries while the link is lost; other retries wait for its return (CallLinkState.retryDelay). After a return it stays
     * [LINK_FLAP_MS], and a loss in that time goes on with its backoff instead of at 0.5 s: when one request keeps failing while Hermes
     * answers the rest, each return wakes it once and it loses the link again at once, so the link comes and goes at the backoff's pace
     * (0.5, 1, 2, 2 s), with one probe and one try of that request each time (REVIEW-E7 P3-1: every ~0.5 s before).
     */
    private suspend fun probeLink() {
        var attempt=0
        val self=currentCoroutineContext()[Job]
        try {
            while(!stopped) {
                if(link.link==Link.LOST) {
                    link.retryDelay(attempt++,probe=true)
                    if(link.link!=Link.LOST||stopped) continue
                    try { link.track(null) { calls.nativeRequest("/api/plugins/beacon/mobile/calls/$callId") } }
                    catch(error:CancellationException) { throw error } catch(_:Exception) {}
                    continue
                }
                var idle=0L
                while(link.link!=Link.LOST&&idle<LINK_FLAP_MS&&!stopped) { delay(100);idle+=100 }
                synchronized(outageLock) { if(link.link!=Link.LOST) { probe=null;return } }
            }
        } finally { synchronized(outageLock) { if(probe===self) probe=null } }
    }
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
        // Out of reach of Hermes: the app's banner says so as well, and clears once Hermes answers.
        if(hermesStage(stage)) dev.hermes.android.data.HermesReach.failed(client.auth.origin,error)
        if(stopped) return
        failure(when {
            error is VoiceProviderRejected || error is VoiceStalled -> error.message.orEmpty()
            error is HermesOutage -> dev.hermes.android.data.APP_CONNECTION_ERROR
            connected -> CALL_DROPPED
            else -> callFailureText(stage,error)
        })
    }
    fun microphoneModeChanged() { if(callOwned()) checkConnected() }
    /** Muting deliberately stops microphone frames; it must not strand an otherwise admitted call. */
    private fun checkConnected() {
        val speaking=synchronized(greetingLock) {
            if(connected||!ready||streamsMicrophone&&!micStreaming&&!microphoneMuted()||!admitted||stopped) return
            connected=true
            greetingFlushing=true
            heldGreeting.isNotEmpty()
        }
        // The greeting the live model already sent plays at once, so the first state after connecting is its speech.
        event(JSONObject().put("type","state").put("state",if(speaking) "speaking" else "listening"))
        synchronized(greetingLock) {
            if(!stopped&&!greetingCut) heldGreeting.forEach(::presentGreeting)
            heldGreeting.clear();greetingFlushing=false
        }
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
    // The audio received for the reply now playing (its own, not the greeting's), since it began or was last cut: a cut's estimate of what
    // played (device round 4, D2).
    @Volatile private var replyAudioMs=0L
    /**
     * A cut of the reply now playing: the audio received for it and how much of that the speaker has played, from the call's playback clock
     * ([playUntil]). The words came ahead of the audio (Gemini Live gives transcription no ordering against audio; Grok streams transcript
     * deltas with it), so the caption is cut to this share ([cutCaptions]). The count starts again for what comes next.
     */
    private fun cutAudio(event:JSONObject):JSONObject {
        val received=replyAudioMs;replyAudioMs=0
        return event.put("played_ms",(received-maxOf(0L,playUntil-clock())).coerceIn(0L,received)).put("received_ms",received)
    }
    // A reply whose words all came while its audio still plays ends once that audio has played, or when the next reply begins; a cut before
    // then still knows what played (device round 4, D2: Grok ends its response when generation ends, before its audio has played).
    private var replyEnds=0;private var replyEnding=0
    private fun endReply() {
        val wait=playUntil-clock()
        if(wait<=0) { finishReply();return }
        val token=++replyEnds;replyEnding=token
        scope.launch { delay(wait);synchronized(repeatLock) { if(replyEnding==token) finishReply() } }
    }
    private fun finishReply() { replyEnding=0;replyAudioMs=0;event(JSONObject().put("type","turn_complete")) }
    /** The live model's own reply: its audio to the speaker, both sides' words to the call card, and the end of its turn. */
    private val repeats=RepeatGuard()
    private val notes=NotesFilter()
    // The guard's captions: the live model's frames, and the owner's typed words, both reach it.
    private val repeatLock=Any()
    private fun play(pcm:ByteArray) { val ms=pcm.size/2*1000L/GeminiWire.OUTPUT_RATE;replyAudioMs+=ms;notePlaying(ms);audio(pcm) }
    private fun assistantCaption(text:String) { if(text.isNotEmpty()) event(JSONObject().put("type","transcript").put("role","assistant").put("text",text).put("final",false)) }
    /**
     * The owner cut the model off, or the session ended: the sentence may have been heard (its audio always plays), so what the guard held of
     * it is shown unless it repeats (REVIEW-E5-2 P3-2); before the "interrupted" event, so it stays in the turn that was cut.
     */
    private fun cutCaption(cut:JSONObject?=null) = synchronized(repeatLock) {
        replyEnding=0
        // Its sentences count as said only as far as its audio played (RepeatGuard).
        val share=cut?.let { val received=it.optLong("received_ms");if(received<=0) 0.0 else it.optLong("played_ms").toDouble()/received } ?: 1.0
        assistantCaption(repeats.turnEnded(share))
    }
    private fun modelContent(content:JSONObject,greetingTurn:Boolean) = synchronized(repeatLock) {
        // The next reply's words or audio end the one before, if its end waited for its audio.
        if(!greetingTurn&&replyEnding!=0&&(content.has("modelTurn")||content.has("outputTranscription"))) finishReply()
        // Gemini can say a sentence again in the turn of a following answer; the guard keeps a word-for-word repeat out of the captions and the
        // chat (RepeatGuard). The audio always plays as it comes.
        val guarded=!greetingTurn&&provider=="gemini"
        if(guarded&&content.optJSONObject("inputTranscription")?.optString("text").orEmpty().isNotBlank()) {
            assistantCaption(repeats.ownerSpoke())
        }
        // Gemini 3.8 Live reasons interleaved; device round 2 got "*   **Analysis:** ..." in the model's transcript. Speech never carries
        // markdown, so the marked part is the model's notes, not its words: it is left out of the captions and the chat (NotesFilter).
        val raw=content.optJSONObject("outputTranscription")?.optString("text")
        val spoken=if(raw!=null&&!greetingTurn) notes.text(raw) else raw
        val shown=if(guarded&&spoken!=null) repeats.words(spoken) else spoken
        if(!greetingTurn) content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for(i in 0 until parts.length()) parts.optJSONObject(i)?.optJSONObject("inlineData")?.let { data ->
                val pcm=try { java.util.Base64.getDecoder().decode(data.optString("data")) } catch(_:Exception) { null }
                if(pcm!=null&&GeminiWire.validAudio(data.optString("mimeType"),pcm.size)) play(pcm)
            }
        }
        for((key,role) in listOf("inputTranscription" to "user","outputTranscription" to "assistant")) {
            // The greeting's words are shown by the greeting turn, and only once the owner heard them.
            if(greetingTurn&&role=="assistant") continue
            content.optJSONObject(key)?.let {
                if(role=="user"&&it.optString("text").isNotBlank()) ownerSpoke()
                val text=if(role=="assistant") shown.orEmpty() else it.optString("text")
                if(role=="assistant"&&guarded&&text.isEmpty()&&!it.optBoolean("final",false)) return@let
                val transcript=JSONObject().put("type","transcript").put("role",role).put("text",text).put("final",it.optBoolean("final",false))
                if(it.has("item_id")) transcript.put("provider_item",it.getString("item_id")).put("content_index",it.optInt("content_index",0))
                event(transcript)
            }
        }
        if(guarded&&content.optBoolean("interrupted")) cutCaption()
        else if(guarded&&content.optBoolean("turnComplete")) assistantCaption(repeats.turnEnded())
        if(!greetingTurn&&(content.optBoolean("turnComplete")||content.optBoolean("interrupted"))) { notes.turnEnded();callBackTurnEnded() }
        if(!greetingTurn&&content.optBoolean("turnComplete")) endReply()
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
        synchronized(greetingLock) {
            if(greetingCut||stopped||!greetingOpen) return
            if(!greetingHeard) greetingArrivedMs=elapsedMs()
            greetingHeard=true
            if(connected&&!greetingFlushing) presentGreeting(pcm)
            else if(heldGreeting.sumOf { it.size }+pcm.size<=60*48000) heldGreeting+=pcm
        }
    }
    private fun endGreeting(cut:Boolean) {
        val releaseMute=synchronized(greetingLock) {
            if(!greetingOpen&&!(cut&&greetingComplete)) return
            greetingOpen=false
            if(cut||greetingCut) {
                greetingCut=true;greetingComplete=false;greetingText.setLength(0);heldGreeting.clear();greetingOutstanding.set(0)
            } else greetingComplete=true
            streamEndHeld.also { streamEndHeld=false }
        }
        if(stopped) return
        if(releaseMute) controls.trySend(Outbound(GeminiWire.endAudio().toString(),{ true },control=true))
        reportGreeting()
    }
    /** A completed greeting is captioned only after its audio reached the speaker. */
    private fun reportGreeting() = synchronized(greetingLock) {
        if(stopped||greetingCut||!greetingComplete||!greetingPresented.get()||greetingReported) return@synchronized
        greetingReported=true
        val words=greetingText.toString().trim();greetingText.setLength(0)
        if(words.isNotBlank()) {
            event(JSONObject().put("type","transcript").put("role","assistant").put("text",words).put("final",false))
            event(JSONObject().put("type","turn_complete"))
        }
        diagnostic("playout_stopped")
    }
    /** The greeting turn opens when the start note is sent: at the tap for a warm session, at admission otherwise. */
    private fun beginGreeting() = synchronized(greetingLock) {
        if(greetingComplete) return@synchronized false
        if(!greetingOpen) { greetingOpen=true;greetingHeard=false;greetingCut=false;greetingReported=false;greetingText.setLength(0) }
        true
    }
    /** Opens the greeting turn and sends the start note unless the warm session already sent it at the tap. */
    private fun openGreeting(ws:WebSocket,adopted:LiveSession?,send:(WebSocket,String)->Boolean) {
        if(!beginGreeting()) return
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
            if(!greetingOpen&&!greetingComplete) false else { greetingCut=true;!greetingOpen||!greetingHeard }
        }
        if(close) endGreeting(cut=true)
        else if(greetingOpen) scope.launch { delay(1500);if(greetingOpen) endGreeting(cut=true) }
        // The speaker stops at once; a reply that already ended is not "interrupted" by the owner speaking.
        if(force||clock()<playUntil) { val cut=cutAudio(JSONObject().put("type","interrupted"));playUntil=0;cutCaption(cut);event(cut) }
    }
    private fun ensureNativeEvents() = synchronized(this) {
        if(stopped || nativeEvents?.isActive==true) return@synchronized
        nativeEvents=scope.launch {
            try { client.nativeEvents { frame ->
                if(stopped) return@nativeEvents
                if(frame.has("id")&&frame.optString("method") in setOf("approval","clarify"))
                    event(JSONObject().put("type","warning").put("message","${dev.hermes.android.data.currentAssistantName} needs your answer. Open the chat."))
                if(frame.has("id")&&frame.optString("method") in setOf("vault.save_login","vault.code","vault.unlock_prompt","secret"))
                    event(JSONObject().put("type","warning").put("message","Open the chat to answer."))
            } } catch(e:CancellationException) { throw e } catch(e:Exception) {
                if(!stopped) { nativeEventsLost=true;event(JSONObject().put("type","warning").put("message",CALL_LOST_HERMES)) }
            }
        }
    }
    /**
     * What comes after a function's first answer: on a v3 plugin one long-poll of the call's updates (receipts, approvals, jobs);
     * on an older plugin the 1.2.0 reads (pending tool requests every 2 s, the call's jobs every 3 s). Every read feeds the link.
     */
    private fun startPolling() = synchronized(toolJobs) {
        if(polling!=null||stopped) return@synchronized
        polling=scope.launch {
            contextLoaded.await()
            if(v3("call_updates")) followUpdates() else coroutineScope {
                launch {
                    var cursor=0
                    while(isActive&&!stopped) {
                        delay(2000)
                        val requests=gatewayReceipts.pending()
                        if(requests.isEmpty()) continue
                        val selected=(requests.drop(cursor%requests.size)+requests.take(cursor%requests.size)).take(4)
                        cursor+=selected.size
                        for(requestId in selected) readReceipt(requestId)
                    }
                }
                while(isActive&&!stopped) { delay(3000);readJobs() }
            }
        }
    }
    /** Contract v3 section 3: events after the last one seen, applied as idempotent updates; an empty answer just asks again. */
    private suspend fun followUpdates() {
        var after=0L;var attempt=0
        while(currentCoroutineContext().isActive&&!stopped) {
            val before=link.losses
            val page=try { link.track(null,CALL_UPDATES_WAIT_MS) { gateway().updates(after,CALL_UPDATES_WAIT_MS) } }
            catch(error:CancellationException) { throw error }
            catch(error:ApiFailure) {
                // Hermes no longer knows this call, or closed it: nothing more will come.
                if(error.status==404||error.status==409) return
                if(error.status==503) delay(5000) else link.retryDelay(attempt++);continue
            }
            catch(error:Exception) { link.retryDelay(attempt++,lostIt=link.losses!=before);continue }
            attempt=0
            var closed=page.optBoolean("closed")
            val events=page.optJSONArray("events")?:org.json.JSONArray()
            for(i in 0 until events.length()) {
                val update=events.optJSONObject(i)?:continue
                val payload=JSONObject(update.toString()).apply { remove("seq");remove("type");remove("at") }
                when(update.optString("type")) {
                    "receipt" -> receiptUpdate(payload)
                    "approval" -> receiptUpdate(payload.put("status","needs_approval"))
                    "job" -> deliverJob(payload)
                    "call" -> if(payload.optString("status")=="closed") closed=true
                }
            }
            after=page.optLong("next_after",after)
            if(closed) return
        }
    }
    /** A receipt from the updates channel; only this call's own requests are taken. */
    private suspend fun receiptUpdate(receipt:JSONObject) {
        val requestId=receipt.string("request_id")
        if(gatewayReceipts.functionId(requestId)==null) return
        try { publishGatewayReceipt(requestId,receipt) }
        catch(error:CancellationException) { throw error }
        catch(_:Exception) { event(JSONObject().put("type","warning").put("message","Tool status could not be verified; no action was replayed.")) }
    }
    /** Reads one tool request's receipt again; a failed read only feeds the link. */
    private suspend fun readReceipt(requestId:String) {
        val receipt=try { gatewayRequests.withPermit { link.track(null) { gateway().status(requestId) } } }
            catch(error:CancellationException) { throw error } catch(_:Exception) { return }
        receiptUpdate(receipt)
    }
    /** The call's jobs, read at once: a hand-over's result goes to the model as that hermes_work call's own response. */
    private suspend fun readJobs() {
        val jobs=try { link.track(null) { calls.nativeRequest("/api/plugins/beacon/mobile/calls/$callId") }.optJSONArray("jobs") }
            catch(error:CancellationException) { throw error } catch(_:Exception) { null } ?: return
        for(i in 0 until jobs.length()) {
            val job=jobs.optJSONObject(i)?:continue
            // A follow-up of a tool request: when its job is done, the request's own receipt carries the result.
            val source=job.optString("tool_request_id")
            if(source.isNotBlank()&&gatewayReceipts.receipt(source)!=null&&job.optString("status") in setOf("done","completed")) readReceipt(source)
            else deliverJob(job)
        }
    }
    /** A job of this call: done, failed or cancelled is its hand-over's final result; Hermes waiting for the owner is a state. */
    private fun deliverJob(job:JSONObject) {
        val jobId=job.string("job_id");val requestId=job.string("request_id")
        val (id,name)=synchronized(toolReceipts) { toolReceipts.entries.firstOrNull { (_,value) ->
            value.optString("function_name") in setOf("hermes_work","hermes_follow")&&
                (jobId.isNotBlank()&&value.string("job_id")==jobId||requestId.isNotBlank()&&value.string("request_id")==requestId)
        }?.let { it.key to it.value.optString("function_name") } } ?: return
        val status=job.optString("status")
        if(voiceNeedsNativeEvents(status)||status=="uncertain"&&job.optBoolean("will_continue")) ensureNativeEvents()
        when {
            status=="done"||status=="completed" -> finish(id,name,JSONObject(job.toString()).put("status","completed"))
            status=="failed"||status=="cancelled"||status=="uncertain"&&!job.optBoolean("will_continue") -> finish(id,name,job)
            status=="needs_input" -> link.state(id,"needs_approval","needs_input",job)
        }
    }
    fun start() {
        if(!callOwned()) return
        // A call that is not connected within the admission budget ends, naming the stage that did not finish.
        scope.launch {
            delay((20_000-elapsedMs()).coerceAtLeast(0))
            if(!connected&&!stopped&&callOwned()) { run?.cancel();fail(java.net.SocketTimeoutException("Call did not connect")) }
        }
        running=true
        // A conversation set up locked carries on trusted once the phone is unlocked: its session is replaced by one with the whole brief.
        trust.whenTrusted(::upgrade)
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
                fun sendFrames(ws:WebSocket,json:String,requireFrame:Boolean=false,typed:Boolean=false):Boolean {
                    val frame=JSONObject(json)
                    lastClientFrameAt=clock()
                    if(!frame.has("realtimeInput")) logFrame("out",frame)
                    val frames=when {
                        grok!=null -> if(typed) grok.outboundTyped(frame)?:return false else grok.outbound(frame)
                        openAi!=null -> openAi.outbound(frame)
                        else -> listOf(frame)
                    }
                    return (frames.isNotEmpty()||!requireFrame)&&frames.all { ws.send(it.toString()) }
                }
                val setupSeen=CompletableDeferred<Unit>();val ended=CompletableDeferred<Unit>();val opened=CompletableDeferred<Unit>()
                // What this attempt's setup declared of the profile's own tools (a warm session's setup was built from the same context).
                var sentRows=if(adopted!=null&&refusedDeclarations==0) context.optJSONArray("tool_declarations")?.length()?:0 else 0
                var sentDeclarations=if(sentRows>0) sentRows-GeminiWire.declarationsDropped(context) else 0
                // This attempt resumed a session the provider no longer has (for the diagnostic of its end).
                var resumeRefused=false
                fun refused(code:Int,reason:String) {
                    // Backlog 81: a refusal for money never heals by retrying. The call ends at once with its own line, whether or not this
                    // attempt resumed a session or the greeting was heard (the demo's Gemini re-sent its setup about six times in 12 s).
                    if(providerMoneyRefusal(reason)) { providerRejection=PROVIDER_MONEY_REFUSAL;return }
                    // A resumed session refused before its setup completed. When the provider says it no longer has it (1007/1008: device
                    // round 2's "BidiGenerateContent session not found or expired" on every retry of the same handle, until the call dropped),
                    // or refuses the same handle a second time, the next attempt starts a new session from the conversation so far; a
                    // transient close (1011, 1013, 1001) tries the handle once more, so the model keeps its own context (REVIEW-E4 P3-2).
                    if(resumption!=null&&!setupSeen.isCompleted) {
                        if(code in setOf(1007,1008)||refusedHandle==resumption) { resume=null;resumeRefused=true } else refusedHandle=resumption
                        return
                    }
                    if(greeted||code !in setOf(1007,1008)) return
                    if(sentDeclarations>0&&!setupSeen.isCompleted&&refusedDeclarations==0) refusedDeclarations=sentRows else providerRejection=reason
                }
                var ownerSpeaking=false
                val socketFence=Any()
                val connectedAt=elapsedMs();resumeUpdates=0;lastResumable=null
                // What this connection lived through, for the diagnostic of its end.
                fun connectionFacts()=JSONObject().put("connection_ms",elapsedMs()-connectedAt).put("resume_updates",resumeUpdates)
                    .put("resumable",lastResumable?:JSONObject.NULL).put("resumed",resumption!=null).apply { if(resumeRefused) put("resume_refused",true) }
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
                            val raw=JSONObject(text);stall.heard();logFrame("in",raw)
                            val frame=when {
                                grok!=null -> grok.inbound(raw)?:return
                                openAi!=null -> openAi.inbound(raw)?:return
                                else -> raw
                            }
                            if(!current()) return
                            if(voiceReplyFrame(frame)) { ownerSpeaking=false;stall.replied();synchronized(unanswered) { unanswered.clear() } }
                            (grok?.continueResponse()?:openAi?.continueResponse())?.let { if(!ws.send(it.toString())) { ended.complete(Unit);ws.cancel();return } }
                            // Readiness never precedes setupComplete; admission below also waits for the verified context.
                            // A frame that carries a call and the turn's end (Grok's response.done) records the call back's marker before the
                            // turn end moves it (REVIEW-E4 P3-1).
                            frame.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { if(current()) callBackOrder(it) }
                            if(frame.has("setupComplete")) {
                                stage("provider_setup_ok",at=adopted?.setupAt?.takeIf { it>=0 }?.let { (it-origin).coerceAtLeast(0) }?:elapsedMs(),warm=adopted?.let { it.setupAt in 0 until origin })
                                setupSeen.complete(Unit)
                            }
                            frame.optJSONObject("sessionResumptionUpdate")?.let {
                                resumeUpdates++;lastResumable=it.optBoolean("resumable")
                                resume=it.optString("newHandle").takeIf { _ -> it.optBoolean("resumable") }?.takeIf(String::isNotBlank)
                            }
                            if(frame.has("goAway")) {
                                // Reported as provider_error (the plugin takes only its known stages): the connection ends here.
                                diagnostic("provider_error",extra=connectionFacts().put("go_away",true).put("time_left",frame.optJSONObject("goAway")?.optString("timeLeft").orEmpty().take(32)))
                                ended.complete(Unit);ws.close(1000,"Reconnect");return
                            }
                            when(frame.optJSONObject("voiceActivity")?.optString("type")) {
                                "ACTIVITY_START" -> { ownerSpeaking=true;ownerSpoke();bargeIn() }
                                "ACTIVITY_END" -> ownerSpeaking=false
                            }
                            frame.optJSONObject("serverContent")?.let { content ->
                                if(content.optJSONObject("interimInputTranscription")?.optString("text")?.isNotBlank()==true && !ownerSpeaking) {
                                    ownerSpeaking=true;ownerSpoke();bargeIn()
                                }
                                // The live model's greeting turn is its own speech, shown only once the owner heard it.
                                val greetingTurn=greetingOpen
                                if(greetingTurn) greetingFrame(content)
                                if(content.optBoolean("interrupted")) { stall.interrupted();bargeIn(force=true) }
                                modelContent(content,greetingTurn)
                            }
                            frame.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
                                // The frame's calls answer together after a shared notice (CallLinkState.frame).
                                if(current()) link.frame((0 until calls.length()).mapNotNull { calls.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) })
                                val changes=ArrayList<String>()
                                for(i in 0 until calls.length()) {
                                    val call=calls.getJSONObject(i);val id=call.optString("id")
                                    if(!current()) continue
                                    val after=changes.toList()
                                    if(after.isNotEmpty()&&id.isNotBlank()) { if(waitsFor.size>=256) waitsFor.clear();waitsFor[id]=after }
                                    if(id.isNotBlank()&&changesSomething(call.optString("name"))) { changesDone.getOrPut(id) { CompletableDeferred() };changes+=id }
                                    launchTool(call,generation)
                                }
                            }
                            frame.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { ids ->
                                val cancelled=synchronized(toolReceipts) {
                                    (0 until minOf(ids.length(),64)).map { ids.optString(it) }.filter { toolReceipts.containsKey(it) }.onEach { cancelledFunctions.add(it) }
                                }
                                // A cancelled call is answered no more and holds no release back (CallLinkState).
                                for(id in cancelled) link.cancelled(id)
                            }
                        } catch(e:Exception) {
                            diagnostic("provider_error",e)
                            ended.complete(Unit);ws.cancel()
                        }
                        }
                    }
                    override fun onMessage(ws:WebSocket,bytes:okio.ByteString) {
                        if(bytes.size>1_000_000) { if(current()) { ended.complete(Unit);ws.cancel() };return }
                        onMessage(ws,bytes.utf8())
                    }
                    // A provider close frame (rejected setup, quota) ends this attempt now; without closing back, OkHttp waits and the call sat in "connecting".
                    override fun onClosing(ws:WebSocket,code:Int,reason:String) { ws.close(1000,null);if(current()) { refused(code,reason);diagnostic("provider_error",closeCode=code,closeReason=reason,extra=connectionFacts());ended.complete(Unit) } }
                    override fun onFailure(ws:WebSocket,t:Throwable,response:Response?) { if(current()) { if(response?.code==402) providerRejection=PROVIDER_MONEY_REFUSAL else if(!greeted) providerRejection=when(response?.code) { 401,403 -> "key";400 -> "model";else -> null };diagnostic("provider_error",response?.code?.takeIf { it in 400..599 }?.let { ApiFailure(it,"") } ?: t);ended.complete(Unit) } }
                    override fun onClosed(ws:WebSocket,code:Int,reason:String) { if(current()) { refused(code,reason);diagnostic("provider_error",closeCode=code,closeReason=reason,extra=connectionFacts());ended.complete(Unit) } }
                }
                // A warm session's greeting may already be arriving: its turn is open before the held frames are read.
                if(adopted?.cueRequested==true) beginGreeting()
                val ws=adopted?.attach(listener)?:newProviderSocket(providerRequest(),listener)
                socket=ws
                replaceSession={ if(!ended.isCompleted&&voiceProviderCurrent(generation,epoch.get(),stopped,false)) ended.complete(Unit) }
                // The setup carries this conversation's verified context, so it waits for that read; the socket's handshake does not.
                val setupSender=if(adopted!=null) null else launch {
                    fun setupStage(name:String,dropped:Int=0) { event(JSONObject().put("type","media_diagnostic").put("stage",name).put("resumed",resumption!=null)
                        .put("elapsed_ms",elapsedMs()).put("attempt",reconnects.failures).apply { if(dropped>0) put("declarations_dropped",dropped) }) }
                    try {
                        opened.await()
                        if(load.result.await()!=null) return@launch
                        if(voiceProviderCurrent(generation,epoch.get(),stopped,ended.isCompleted)) {
                            val brief=setupBrief()
                            val dropped=GeminiWire.declarationsDropped(brief)
                            sentRows=brief.optJSONArray("tool_declarations")?.length()?:0;sentDeclarations=sentRows-dropped
                            check(sendFrames(ws,liveSetup(credentials,brief,resumption,recentSpeech()).toString(),true))
                            setupStage("provider_setup_sent",if(refusedDeclarations>0) refusedDeclarations else dropped)
                        }
                    } catch(error:CancellationException) { throw error }
                    catch(_:Exception) {
                        setupStage("provider_setup_failed")
                        setupSeen.completeExceptionally(java.io.IOException("Voice setup submission failed"))
                        ended.complete(Unit);ws.cancel()
                    }
                }
                var fatal=false
                fun admit():Boolean = synchronized(socketFence) {
                    if(!voiceProviderCurrent(generation,epoch.get(),stopped,ended.isCompleted)) return@synchronized false
                    ready=true
                    reconnects.admitted()
                    // A typed turn rides this session's own wire, like the start note; a later socket replaces it.
                    typedTurn={ words -> socket===ws&&sendFrames(ws,JSONObject().put("clientContent",JSONObject().put("turns",org.json.JSONArray().put(JSONObject().put("role","user")
                        .put("parts",org.json.JSONArray().put(JSONObject().put("text",words))))).put("turnComplete",true)).toString(),typed=true) }
                    diagnostic("provider_ready")
                    // The microphone is live from here on; the call reads as connected once it streams and Hermes admitted it.
                    if(!greeted) { greeted=true;openGreeting(ws,adopted) { socket,json -> sendFrames(socket,json) } }
                    else if(connected) event(JSONObject().put("type","state").put("state","listening"))
                    // A session replaced after the unlock goes on where the conversation was; a turned-away request is carried out or asked for again.
                    if(rebrief&&briefedTrusted) { rebrief=false;if(trust.wasRefused()) controls.trySend(Outbound(cueTurn(TRUSTED_CUE),{ callOwned() },control=true)) }
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
                        val replaced=next.providerSession!=null&&next.providerSession!=providerSession.get()
                        // The new session never saw that function call: it gets the final result as data, and no state at all.
                        if(replaced&&next.newSessionJson==null) { next.onDropped();continue }
                        val built=if(replaced) null else next.build?.invoke()
                        if(!replaced&&next.build!=null&&built==null) { next.onDropped();continue }
                        val payload=if(replaced) next.newSessionJson!! else built?:next.json
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
                    var stalledHere=false
                    // Gemini ends a connection after ~2 minutes without a client message (device round 2: 1008 "Session closed due to client
                    // inactivity" every 121 s while the microphone was muted or held). A short silence goes out when nothing else has.
                    val keepAlive=if(provider!="gemini") null else launch { while(isActive) {
                        delay(minOf(keepAliveMs,5_000L))
                        if(ready&&clock()-lastClientFrameAt>=keepAliveMs) controls.trySend(Outbound(GeminiWire.audio(SILENCE_FRAME).toString(),{ callOwned() },control=true))
                    } }
                    val watchdog=launch { while(isActive) {
                        delay(stallPoll)
                        // A conversation set up locked looks for the unlock itself, so its upgrade never depends on a broadcast reaching it.
                        if(!briefedTrusted) trust.trusted()
                        // End this session before re-queuing what it was owed, so its writer cannot send that into the dead socket.
                        if(ready&&stall.stalled()) { stalledHere=ended.complete(Unit);break }
                    } }
                    ended.await();keepAlive?.cancelAndJoin();watchdog.cancelAndJoin();writer.cancelAndJoin()
                    // Replay only after the old writer is gone, before the next session resets its obligation.
                    if(stalledHere) stalledSession()
                } catch(e:CancellationException) { throw e }
                catch(e:ContextFailed) { fatal=true;throw e }
                catch(e:CallAdmissionFailed) { fatal=true;throw e }
                catch(e:Exception) { /* Reconnect with the same phone-held key and refreshed Hermes context. */ }
                // A fatal context failure ends the call next (the call ends next), so it shows no interim state.
                finally { replaceSession=null;setupSender?.cancel();val cut=cutAudio(JSONObject().put("type","interrupted"));synchronized(repeatLock) { cutCaption(cut);notes.turnEnded() };epoch.compareAndSet(generation,generation+1);ready=false;endGreeting(cut=true);if(!fatal) { playUntil=0;event(cut) };ws.cancel();if(!stopped&&!fatal) event(JSONObject().put("type","state").put("state","reconnecting")) }
                reconnects.failed()
            }
        } catch(e:CallAdmissionFailed) { /* The service ends the call with Hermes' own reason for refusing it. */ }
        catch(e:ContextFailed) { if(e.error !is CallAdmissionFailed) fail(e.error,"context_ok") }
        catch(e:TimeoutCancellationException) { currentCoroutineContext().ensureActive();fail(e) }
        catch(e:CancellationException) { throw e } catch(e:Exception) { fail(e) }
    } }
    private fun launchTool(call:JSONObject,generation:Long) {
        val id=call.optString("id")
        val job=scope.launch(start=CoroutineStart.LAZY) { tool(call,generation) }
        synchronized(toolJobs) { if(stopped) { job.cancel();return };toolJobs[job]=id }
        job.invokeOnCompletion {
            synchronized(toolJobs) { toolJobs.remove(job) }
            // A call that never became a function of this call (refused, or sent before) changes nothing anyone waits for.
            if(!link.known(id)||link.isFinal(id)) changeSettled(id)
        }
        job.start()
    }
    /** Whether a call may change what a later read of the same frame returns: a write, a phone change, an unknown tool. */
    private fun changesSomething(name:String)=!directToolIsReadOnly(name)&&!declaredRead(name)&&name !in setOf("hermes_work","hermes_follow","hermes_callback","schedule_callback")
    private fun changeSettled(id:String) { changesDone.remove(id)?.complete(Unit) }
    private fun callOwned() = !stopped&&ownerActive()&&client.scope==callScope
    /** What the live session is set up with: the call's brief, without the profile's own tools once a setup with them was refused. */
    private fun setupBrief():JSONObject = callBrief(context,briefTrust()).let { if(refusedDeclarations>0) JSONObject(it.toString()).apply { remove("tool_declarations") } else it }
    /** One user turn in the live session's own wire shape, for the notes the app gives the model. */
    private fun cueTurn(text:String):String=JSONObject().put("clientContent",JSONObject().put("turns",org.json.JSONArray().put(JSONObject().put("role","user")
        .put("parts",org.json.JSONArray().put(JSONObject().put("text",text))))).put("turnComplete",true)).toString()
    /**
     * The phone was unlocked in a conversation whose session was set up without the owner's memory, history, persona or tools: that session
     * is replaced by one set up with all of it, from what was said so far (the same replacement a stalled session gets), and the
     * conversation goes on. A reply that is being said is finished first, so the swap comes between two turns.
     */
    private fun upgrade() {
        if(stopped) return
        scope.launch {
            val limit=clock()+8_000
            while(!stopped&&clock()<playUntil&&clock()<limit) delay(100)
            if(stopped||briefedTrusted) return@launch
            rebrief=true;resume=null
            replaceSession?.invoke()
        }
    }
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
            if(taken>0&&greetingPresented.compareAndSet(false,true)) { val at=elapsedMs();scope.launch { stage("first_model_audio",at);diagnostic("playout_started",at=at);reportGreeting() } }
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
    /** The owner's Approvals choice, read at each request; only a plugin that says it takes one is sent it. */
    private fun approvalChoice():String?=if(context.optBoolean("tool_approval_choice")) PhoneVoiceTools.approvalMode() else null
    /** Calls contract v3, section 0: decided per call from the context the call was built with; without call_contract 3 it is v2. */
    private fun v3(capability:String):Boolean = context.optInt("call_contract")>=3&&context.optJSONObject("call_capabilities")?.optBoolean(capability)==true
    /** This call's plugin keeps a tool receipt's cause (LINK-121-CAUSE): the admitted call's own context says so. */
    internal val takesReceiptCause:Boolean get() = takesToolReceiptCause(context)
    /** One of the owner's own Hermes tools that this call's context declared to the live model (contract section 1). */
    /** A declared Hermes tool whose declaration says it only reads. */
    private fun declaredRead(name:String):Boolean = declared(name)&&context.optJSONArray("tool_declarations")!!.let { rows ->
        (0 until rows.length()).any { rows.optJSONObject(it)?.let { row -> row.optString("name")==name&&row.optBoolean("read_only") }==true }
    }
    private fun declared(name:String):Boolean {
        if(name in GeminiWire.CALL_FUNCTIONS||!name.matches(ToolDeclarations.NAME)) return false
        val rows=context.optJSONArray("tool_declarations")?:return false
        return (0 until rows.length()).any { rows.optJSONObject(it)?.optString("name")==name }
    }
    /** The call's tool gateway, bound to the admitted call. */
    private suspend fun gateway():NativeToolGateway { val id=admittedCall();return gatewayFor.getOrPut(id) { NativeToolGateway(calls,id) } }
    /** One Hermes request of function call [id]: a lost link tells the model, and a retry-safe request is sent again. */
    private suspend fun <T> hermes(id:String,serverWaitMs:Long,retrySafe:Boolean,block:suspend ()->T):T =
        link.request(id,serverWaitMs,retrySafe,::callOwned,block)
    /** Starts function call [id] in the link ledger; the same call sent again never runs again and is told nothing new. */
    private fun opened(id:String,name:String,requestId:String,hermes:Boolean,retrySafe:Boolean):Boolean {
        if(!link.open(id,name,requestId,hermes,retrySafe)) return false
        functionSessions.putIfAbsent(id,providerSession.get())
        return true
    }
    /** A function call's final answer to the model, kept for the call-end report. */
    private fun finish(id:String,name:String,result:JSONObject) {
        synchronized(toolReceipts) { toolReceipts[id]=JSONObject(result.toString()).put("function_name",name).put("read_only",directToolIsReadOnly(name)) }
        link.final(id,result)
    }
    /**
     * One release of function results from the link ledger (CallLinkState): one tool response, every result SILENT but the last, which asks
     * the model to answer when [prompt]. A function the provider cancelled is left out (its answer would be refused), when the release is
     * queued and again, per function, when it is sent (REVIEW-E5 P3-1), so the release's last answer is the last one left (REVIEW-E4-2
     * P3-A). Called under the ledger's lock, so releases are queued in order. A function of a replaced session gets only its final result, as
     * data in a user turn (the one tool response a stalled session is told again); its states are not said there.
     */
    private fun sendToolResponse(replies:List<CallReply>,prompt:Boolean) {
        val live=replies.filter { it.id !in cancelledFunctions }
        val groups=live.groupBy { functionSessions[it.id]?:providerSession.get() }.entries.toList()
        groups.forEachIndexed { i,(session,group) ->
            val finals=group.filter { resultTurn(it.result)==ResultTurn.Final }
            val outcome=if(finals.isEmpty()) null else cueTurn(finals.joinToString("\n") {
                "Verified earlier ${it.name} tool outcome from the previous provider session (reference data, not a request to repeat the operation): ${it.result.toString().take(65536)}"
            })
            val asks=prompt&&i==groups.lastIndex
            fun frame(rows:List<CallReply>)=GeminiWire.tools(rows,asks).toString()
            controls.trySend(Outbound(frame(group),{ callOwned() },control=true,providerSession=session,newSessionJson=outcome,
                build={ group.filter { it.id !in cancelledFunctions }.takeIf { it.isNotEmpty() }?.let(::frame) }))
        }
    }
    private val reconcilingReceipts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Until Hermes has a terminal receipt, read the original request ID. Only the invoke path submits work. */
    private fun reconcileReceipt(requestId: String) {
        if (gatewayReceipts.functionId(requestId) == null || !reconcilingReceipts.add(requestId)) return
        scope.launch {
            try {
                var attempt = 0
                while (callOwned() && !gatewayReceipts.settled(requestId)) {
                    link.retryDelay(attempt++)
                    if (callOwned() && !gatewayReceipts.settled(requestId)) readReceipt(requestId)
                }
            } finally { reconcilingReceipts.remove(requestId) }
        }
    }

    /** The tool window and the call's timeline hear the phone's own states; an approval card stays until Hermes decides it. */
    private fun toolWindow(id:String,name:String,result:JSONObject) {
        val status=result.optString("status");val requestId=result.optString("request_id")
        if (status == "lost_result") reconcileReceipt(requestId)
        // The card and the tool chip say "Can't reach Hermes" until Hermes answers again (recovered).
        if(result.optString("state_source")=="phone"&&status in setOf("hermes_unreachable","lost_result")) event(JSONObject().put("type","hermes_link").put("reachable",link.link!=Link.LOST))
        if(result.optString("state_source")!="phone"||!requestId.matches(Regex("[A-Za-z0-9_-]{1,160}"))) return
        if(name=="get_visual_state"||phone!=null&&PhoneVoiceTools.handles(name)) return
        val receipt=gatewayReceipts.receipt(requestId)
        // While Hermes' approval is pending, the card and its chip stay as they are; the call's tool log still records what the model
        // was told (device round 1: 47.6 s with no state in the log, though the model had been told).
        val logOnly=status=="needs_approval"||receipt?.optString("status")=="needs_approval"
        event(JSONObject().put("type","tool_receipt").put("request_id",requestId).put("status",status)
            .put("tool_name",(receipt?.string("tool_name")?.takeIf(String::isNotBlank)?:name).take(200))
            .apply {
                if(logOnly) put("log_only",true);result.optString("detail").takeIf(String::isNotBlank)?.let { put("detail",it.take(80)) }
                // What lost the link (LINK-121): the call's tool log keeps it; the synced timeline leaves it out.
                result.optString("cause_class").takeIf(String::isNotBlank)?.let { put("cause_class",it.take(80)) }
            })
    }
    /** The link is back: every request still waiting is read again once, so the model hears where it is now. */
    private fun recovered() {
        // A new loss may already have its own outage timer (the request this return woke failed at once): that one stays.
        synchronized(outageLock) { if(link.link!=Link.LOST) outageWatch?.cancel() }
        if(stopped) return
        event(JSONObject().put("type","hermes_link").put("reachable",true))
        if(nativeEventsLost) { nativeEventsLost=false;ensureNativeEvents() }
        val confirmed=link.confirmed()
        scope.launch {
            for(requestId in gatewayReceipts.pending()) if(gatewayReceipts.functionId(requestId)?.let(link::isFinal)==false) readReceipt(requestId)
            // Admitted hand-overs wait on their job, which only the call's job list shows.
            if(confirmed.any { (id,requestId) -> gatewayReceipts.functionId(requestId)!=id }) readJobs()
        }
    }
    /** A receipt Hermes gave for one of this call's tool requests: the tool window, an approval card, and the model. */
    private suspend fun publishGatewayReceipt(requestId:String,receipt:JSONObject) = gatewayDeliveryLock.withLock {
        if(stopped) return@withLock
        val previous=gatewayReceipts.receipt(requestId)?.toString()
        val verified=gatewayReceipts.record(requestId,receipt)
        if(verified.toString()!=previous) {
            val toolName=verified.string("tool_name").ifBlank { "MCP tool request" }.take(200)
            event(JSONObject(verified.toString()).put("type","tool_receipt").put("tool_name",toolName))
            callProposalNeedingOwnerApproval(verified)?.let { event(callToolApprovalEvent(requestId,toolName,it)) }
        }
        val functionId=gatewayReceipts.functionId(requestId)?:return@withLock
        val name=synchronized(toolReceipts) { toolReceipts[functionId]?.optString("function_name") }?.takeIf(String::isNotBlank)?:"invoke_tool"
        deliverReceipt(functionId,name,verified)
    }
    /** What a receipt means for the function waiting on it: a final result, an approval to wait for, or still running. */
    private fun deliverReceipt(id:String,name:String,receipt:JSONObject) {
        val status=receipt.optString("status")
        if (!receipt.isNull("ran_on_hermes")) link.ran(id, receipt.getBoolean("ran_on_hermes"))
        else if (status != "uncertain") link.ran(id, status != "needs_approval")
        when(status) {
            "needs_approval" -> link.state(id,"needs_approval",null,receipt)
            // Past the notice it is the same "still running" the owner heard (CallLinkState), not news again.
            "ready","running" -> link.state(id,if(link.elapsed(id)>=CALL_NOTICE_MS) "still_running" else "running",null,receipt)
            else -> finish(id,name,receipt)
        }
    }
    suspend fun decideTool(requestId:String,proposalId:String,decision:String) {
        check(!stopped) { "Call ended" }
        require(decision in setOf("approve", "approve_call", "deny")) { "Invalid tool approval decision" }
        // A phone request is answered by its own card; Hermes never sees it.
        if(phone?.owns(requestId)==true) { phone.decide(requestId,proposalId,decision);return }
        gatewayReceipts.decision(requestId,proposalId)
        gatewayReceipts.forgetReads()
        val functionId=gatewayReceipts.functionId(requestId)
        val wait=if(v3("invoke_wait")) CALL_INVOKE_WAIT_MS else null
        // A tap while Hermes is out of reach is kept and sent again until the link is back, while the card waits (device round 3: it was
        // lost, and the model was told lost_result for a decision Hermes never got). Hermes knows a decision by its proposal, so sending it
        // again is safe. Only Hermes' own receipt says the request ran: a decision without an answer leaves the call waiting, and the
        // receipt read when the link is back says where it is.
        val receipt=link.request(functionId,wait?:0,retrySafe=true,owned=::callOwned) { gatewayRequests.withPermit { gateway().decide(requestId,proposalId,decision,wait) } }
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
        if(name !in GeminiWire.CALL_FUNCTIONS&&!phoneTool&&!declared(name)) { if(opened(id,name,"",false,true)) link.failed(id,"tool_unknown");return }
        // A conversation that started on a locked phone and has not been unlocked since may talk, but nothing private is read or done:
        // every Hermes tool answers at once, before admission or any request (the phone's own tools are held back in PhoneVoiceBridge).
        if(!trust.trusted()&&!phoneTool) { if(name!="get_visual_state") trust.refused();if(opened(id,name,"",false,true)) link.final(id,lockedToolResult());return }
        // Anything that is not a read (a hand-over, a call back, a phone write) may change what a read returns; a Hermes tool's own receipt says.
        if(!directToolIsReadOnly(name)&&name!="invoke_tool"&&!declared(name)) gatewayReceipts.forgetReads()
        // Every Hermes action belongs to the admitted call; none runs before Hermes admitted it.
        // Phone tools run on this phone and never reach Hermes, so they do not wait for its admission.
        if(name!="get_visual_state"&&!phoneTool) try { admittedCall() } catch(_:CallAdmissionFailed) { if(opened(id,name,"",true,false)) link.failed(id,"admission_rejected");return }
        val requestId=java.util.UUID.nameUUIDFromBytes((callId+":"+id).toByteArray(Charsets.UTF_8)).toString()
        val previous=synchronized(toolReceipts) { toolReceipts[id] }
        if(previous!=null&&previous.optString("function_name")!=name) { event(JSONObject().put("type","warning").put("message","Provider reused a function identity with a different name; no new action was submitted."));return }
        // A read, or a request Hermes knows by its request_id, may be sent again after a lost answer; a 1.2.0 hand-over or a cancel may not.
        val retrySafe=directToolIsReadOnly(name)||name !in setOf("hermes_work","hermes_follow","hermes_cancel")||name=="hermes_work"&&v3("handover")
        if(!opened(id,name,requestId,name!="get_visual_state"&&!phoneTool,retrySafe)) return
        val full=synchronized(toolReceipts) {
            if(toolReceipts.size>=64) toolReceipts.keys.firstOrNull(link::isFinal)?.let { toolReceipts.remove(it);functionSessions.remove(it);cancelledFunctions.remove(it) }
            if(toolReceipts.size>=64) true else { toolReceipts[id]=JSONObject().put("status","submitting").put("request_id",requestId).put("function_name",name);false }
        }
        if(full) { link.failed(id,"capacity");return }
        try {
            // After a change earlier in its own frame, it runs once that change has its result (device round 3: the read beside a write
            // answered first, with the old value).
            // While it waits it has no "still running" of its own: the change it waits for has its own states (REVIEW-E5 P3-2).
            val waits=waitsFor.remove(id).orEmpty()
            if(waits.isNotEmpty()) { link.queued(id,true);try { for(earlier in waits) changesDone[earlier]?.await() } finally { link.queued(id,false) } }
            val args=call.optJSONObject("args")?:JSONObject()
            require(!call.has("args")||call.opt("args") is JSONObject) { "Function arguments must be an object" }
            when {
                name=="get_visual_state" -> finish(id,name,visual.snapshot())
                phoneTool -> finish(id,name,checkNotNull(phone).call(requestId,name,args,event))
                name=="invoke_tool"||declared(name) -> invoke(id,name,requestId,args)
                name=="discover_tools" -> lookup(id,name,requestId,DISCOVER_WAIT_MS) { gatewayRequests.withPermit { gateway().discover(args.optString("query")) } }
                name=="tool_status" -> {
                    val existing=args.getString("request_id")
                    require(gatewayReceipts.receipt(existing)!=null) { "Unknown call tool request" }
                    publishGatewayReceipt(existing,hermes(id,0,true) { gatewayRequests.withPermit { gateway().status(existing) } })
                    finish(id,name,gatewayReceipts.receipt(existing)!!)
                }
                name in setOf("hermes_search","hermes_memory","hermes_skills","hermes_context") -> {
                    val read=GeminiWire.nativeRead(name,args)
                    require(GeminiWire.capable(context,read.capability))
                    lookup(id,name,requestId,LOOKUP_WAIT_MS) { GeminiWire.evidence(calls.nativeRequest("/api/plugins/beacon/mobile/calls/$callId/${read.route}","POST",read.body),name=="hermes_search") }
                }
                name=="schedule_callback" -> {
                    val body=ScheduledCallback.body(args,callId,id,java.time.ZonedDateTime.now())
                    if(claimMutation(id)) finish(id,name,hermes(id,0,true) { calls.nativeRequest("/api/plugins/beacon/mobile/calls/$callId/schedule-callback","POST",body) })
                }
                name=="hermes_callback" -> {
                    require((args.keys().asSequence().toSet()-setOf("scope","enabled")).isEmpty())
                    require(!args.has("enabled")||args.opt("enabled") is Boolean)
                    val selection=args.optString("scope","latest");require(selection in setOf("latest","all"))
                    val body=JSONObject().put("scope",selection).apply { if(args.has("enabled")) put("enabled",args.getBoolean("enabled")) }
                    if(args.opt("enabled")!=false&&!ownerAnsweredCallBack(id)) { link.failed(id,"owner_has_not_answered",JSONObject().put("call_back","off"));return }
                    if(claimMutation(id)) finish(id,name,hermes(id,0,true) { calls.nativeRequest("/api/plugins/beacon/mobile/calls/$callId/callback","POST",body) })
                }
                name=="hermes_cancel" -> cancel(id,args.getString("job_id"))
                name=="hermes_follow" -> follow(id,requestId,args)
                else -> work(id,requestId,args)
            }
        } catch(error:CancellationException) { throw error }
        catch(error:Exception) { if(callOwned()) failed(id,error) }
    }
    /** The function's final failure, in words of state: what happened, never a script for the model. */
    private fun failed(id:String,error:Exception) {
        // Nothing ran before the call claimed the change (claimMutation, just before the request that changes something).
        val claimed=synchronized(toolReceipts) { toolReceipts[id]?.optString("status") }.let { it!=null&&it!="submitting" }
        if(error is java.io.IOException&&error !is ApiFailure) {
            // Only a request that may not be sent again gets here: after the claim, unless it never left the phone, its outcome is unknown.
            if(!claimed||dev.hermes.android.data.requestNeverSent(error)) { link.ran(id,false);link.failed(id,if(error is java.net.SocketTimeoutException) "no_response" else "connection_lost") }
            else {
                val requestId = java.util.UUID.nameUUIDFromBytes((callId+":"+id).toByteArray(Charsets.UTF_8)).toString()
                if (gatewayReceipts.functionId(requestId) == id) {
                    link.state(id, "uncertain", "outcome_unknown")
                    reconcileReceipt(requestId)
                } else link.failed(id,"outcome_unknown",status="uncertain")
            }
            return
        }
        // So did nothing when Hermes refused it (4xx) or was updating; a server error after the claim leaves it as known.
        if(!claimed||error is ApiFailure&&(error.status<500||error.message=="Hermes is updating")) link.ran(id,false)
        val problem=(error as? IllegalArgumentException)?.message?.takeIf { it.isNotBlank()&&it!="Failed requirement." }?.let { JSONObject().put("problem",it.take(200)) }
        link.failed(id,fields=problem,detail=when {
            error is ToolListUnread -> "tool_list_${error.status}"
            error is ApiFailure&&error.status==503&&error.message=="Hermes is updating" -> "hermes_updating"
            error is ApiFailure -> "http_${error.status}"
            error is IllegalStateException -> "not_started"
            else -> "invalid_arguments"
        })
    }
    /** A read of Hermes for the model; the tool window confirms it without its contents. */
    private suspend fun lookup(id:String,name:String,requestId:String,serverWaitMs:Long,read:suspend ()->JSONObject) {
        event(JSONObject().put("type","tool_receipt").put("request_id",requestId).put("tool_name",name).put("status","running"))
        val result=hermes(id,serverWaitMs,true) { read() }
        link.ran(id,true)
        if(result.toString().toByteArray(Charsets.UTF_8).size>65536) { link.failed(id,"result_too_large");return }
        event(directLookupReceipt(requestId,name,result))
        finish(id,name,result)
    }
    /** One tool of the owner's Hermes, in one request: Hermes answers with its result, or says it is still running or waits for approval. */
    private suspend fun invoke(id:String,name:String,requestId:String,args:JSONObject) {
        val (tool,arguments)=if(name=="invoke_tool") {
            require(args.keys().asSequence().toSet()==setOf("tool_id","arguments")) { "Only tool identity and arguments are accepted" }
            val raw=args.get("arguments")
            args.getString("tool_id") to (raw as? JSONObject ?: JSONObject(raw as String))
        } else name to args
        require(tool.matches(Regex("[a-f0-9]{64}|[A-Za-z0-9_.:/-]{1,128}")))
        require(arguments.toString().toByteArray(Charsets.UTF_8).size<=16384) { "Tool arguments are too large" }
        val waits=v3("invoke_wait")
        // A 1.2.0 plugin runs a tool only by its tool_id, so a name is looked up first.
        val handle=if(waits||tool.matches(TOOL_ID)) tool else toolId(id,tool) ?: run { link.failed(id,"tool_unknown");return }
        gatewayReceipts.reserve(requestId,id,handle,arguments)?.let { finish(id,name,it);return }
        if(!claimMutation(id)) return
        val receipt=try {
            hermes(id,if(waits) CALL_INVOKE_WAIT_MS else 0,true) { gatewayRequests.withPermit {
                if(waits) gateway().invokeAndWait(requestId,handle,arguments,approvalChoice(),CALL_INVOKE_WAIT_MS) else gateway().invoke(requestId,handle,arguments,approvalChoice())
            } }
        } catch(error:ApiFailure) {
            // Hermes refused it before running it: the request is over, and nothing reads it again.
            if(error.status in 400..499) gatewayReceipts.record(requestId,JSONObject().put("request_id",requestId).put("status","failed"))
            throw error
        }
        publishGatewayReceipt(requestId,receipt)
    }
    /** The tool_id a 1.2.0 plugin lists for [name], from one search; null only when a real tool list has no tool of that name. */
    private suspend fun toolId(id:String,name:String):String? {
        val found=hermes(id,DISCOVER_WAIT_MS,true) { gatewayRequests.withPermit { gateway().discover(name) } }
        val tools=found.optJSONArray("tools")
        // A list still being built, or one Hermes could not read, says nothing about whether the tool exists.
        if(tools==null||found.string("status") in setOf("starting","unavailable","failed","error")) throw ToolListUnread(found.string("status").ifBlank { "unavailable" })
        return GeminiWire.pickToolId(tools,name)
    }
    private class ToolListUnread(val status:String):Exception()
    /** Stops one of this call's jobs when the owner asks: a queued admission is retired, a started one interrupted. */
    private suspend fun cancel(id:String,jobId:String) {
        val owned=link.track(id) { calls.nativeRequest("/api/plugins/beacon/mobile/calls/$callId") }.getJSONArray("jobs")
        val job=(0 until owned.length()).map { owned.getJSONObject(it) }.firstOrNull { it.optString("job_id")==jobId }?:error("Unknown call job")
        // A finished job has nothing left to stop; its runtime may already be working on something newer.
        val running=job.optString("status") in setOf("working","watching","needs_input","queued") || job.optString("status")=="uncertain"&&job.optBoolean("will_continue")
        if(!running) { link.failed(id,"job_finished");return }
        if(!claimMutation(id)) return
        val source=job.optString("tool_request_id")
        val cancelled=if(source.isNotBlank()) link.track(id) { calls.nativeRequest("/api/plugins/beacon/mobile/calls/$callId/tools/requests/$source/cancel","POST",JSONObject()) } else null
        if(job.optString("admission_id").isBlank()) { cancelled?.let { finish(id,"hermes_cancel",it) } ?: link.failed(id,"exact_cancel_unavailable");return }
        val runtime=job.getString("runtime_session_id");val admission=job.getString("admission_id")
        val exact=client.nativeRpc("prompt.receipt",JSONObject().put("session_id",runtime).put("admission_id",admission))
        require(exact.optString("admission_id")==admission&&exact.optJSONObject("ref")?.optString("session_id")==runtime) { "Native cancellation identity changed" }
        val outcome=when(exact.optString("status")) {
            "queued" -> client.nativeRpc("prompt.cancel",JSONObject().put("session_id",runtime).put("admission_id",admission))
            "started" -> {
                require(exact.opt("execution_generation") is Number) { "Native turn generation unavailable" }
                client.nativeRpc("session.interrupt",JSONObject().put("session_id",runtime).put("execution_generation",exact.get("execution_generation")))
            }
            else -> { link.failed(id,"job_finished");return }
        }
        finish(id,"hermes_cancel",cancelled?.takeIf { it.optString("status")=="uncertain" }?:outcome)
    }
    /** A 1.2.0 plugin's follow-up of a running tool request by its request_id, into the same chat. */
    private suspend fun follow(id:String,requestId:String,args:JSONObject) {
        require((args.keys().asSequence().toSet()-setOf("request_id","callback")).isEmpty())
        require(!args.has("callback")||args.opt("callback") is Boolean)
        val source=args.getString("request_id");require(source.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        val binding=client.nativeSession(selectedSession)
        val lease=client.nativeTurnLease(binding.storedSessionId,binding.runtimeSessionId)
        val original=link.track(id) { gatewayRequests.withPermit { gateway().status(source) } }
        if(original.optString("status")=="needs_approval"&&!lease.authority) { link.failed(id,"transfer_unavailable");return }
        if(!claimMutation(id)) return
        val body=JSONObject().put("stored_session_id",binding.storedSessionId).put("runtime_session_id",binding.runtimeSessionId)
            .put("request_id",requestId).put("tool_request_id",source).apply { if(args.has("callback")) put("callback",args.getBoolean("callback")) }
        val prepared=link.track(id) { calls.nativeRequest("/api/plugins/beacon/mobile/calls/$callId/work","POST",body) }
        if(prepared.optBoolean("native_transfer")) {
            require(original.optString("status")=="needs_approval"&&lease.authority)
            if(gatewayReceipts.receipt(source)!=null) publishGatewayReceipt(source,JSONObject(original.toString()).put("status","transferred"))
        }
        // The owner's own ask goes with the request (/work); a turn-on afterwards passes the same guard as hermes_callback (REVIEW-E3 finding 3),
        // and this follow is itself a hand-over that needs its own question, so it never turns the call back on here.
        if(args.has("callback")&&(!args.getBoolean("callback")||ownerAnsweredCallBack(id))) {
            val desired=if(args.getBoolean("callback")) "requested" else "declined"
            if(prepared.optString("callback_consent")!=desired) {
                val consent=link.track(id) { calls.nativeRequest("/api/plugins/beacon/mobile/calls/$callId/callback","POST",JSONObject().put("job_id",prepared.getString("job_id")).put("enabled",args.getBoolean("callback"))) }
                require(consent.opt("subscribed")==args.getBoolean("callback"))
                prepared.put("callback_consent",desired)
            }
        }
        val receipt=if(prepared.optBoolean("native_transfer")&&prepared.optBoolean("submit")) {
            val admitted=submitVoiceContinuation({callOwned()},{prepared},{
                client.nativeVoiceSubmit(binding,JSONObject().put("session_id",binding.runtimeSessionId).put("text",prepared.getString("text"))
                    .put("submission_id",prepared.getString("request_id")).put("surface","voice-live").put("queued",true),null,true,
                    ownerFence,{callOwned()},{true},{false})
            })
            JSONObject(prepared.toString()).put("status",if(admitted.optString("admission_id").isBlank()) "submission_unconfirmed" else admitted.optString("status"))
                .apply { admitted.optString("admission_id").takeIf(String::isNotBlank)?.let { put("admission_id",it) } }
        } else prepared
        admitted(id,"hermes_follow",receipt.put("stored_session_id",binding.storedSessionId).put("runtime_session_id",binding.runtimeSessionId).put("work_mode","background"),"background")
    }
    /** Work for Hermes' own agent in this call's chat: one hand-over on a v3 plugin, the 1.2.0 path otherwise. */
    private suspend fun work(id:String,requestId:String,args:JSONObject) {
        val text=args.getString("text");require(text.isNotBlank()&&text.length<=16000)
        val asked=GeminiWire.OWNER_ASKED_FOR_CALL_BACK
        require(args.keys().asSequence().toSet().let { it.contains("text")&&(it-setOf("text",asked,"mode")).isEmpty() }) { "hermes_work takes text, $asked and mode" }
        require(!args.has(asked)||args.opt(asked) is Boolean) { "$asked is true or false" }
        val mode=args.optString("mode","background");require(mode in setOf("inline","background"))
        // Only the owner's own request sets it here; a yes to the model's question goes through hermes_callback.
        val callback=if(args.optBoolean(asked)) true else null
        check(callOwned()) { "Call ended before native admission" }
        val frame=visual.current()
        val missingFrame=frame==null&&visual.active()
        if(missingFrame) event(JSONObject().put("type","warning").put("message","A fresh shared frame was not available for this request."))
        val prompt=if(missingFrame) "$text\n[The current shared image could not be attached.]" else text
        val receipt=if(v3("handover")) {
            if(!claimMutation(id)) return
            val jpeg=frame?.takeIf { visual.permits(it) }?.let { java.util.Base64.getEncoder().encodeToString(it.bytes) }?.takeIf { it.length<=65536 }
            hermes(id,CALL_HANDOVER_WAIT_MS,true) { gateway().handover(requestId,prompt,mode,callback,jpeg) }
                .also { if(jpeg!=null&&it.string("job_id").isNotBlank()) frame?.let { shared -> visual.confirmed(shared)?.let(event) } }
        } else legacyWork(id,requestId,text,prompt,callback,mode,frame)
        admitted(id,"hermes_work",receipt.put("request_id",requestId).put("work_mode",mode)
            .apply { if(callback==true&&!has("callback_consent")) put("callback_consent","requested") },mode)
    }
    /**
     * A hand-over's answer for the model. Admitted work is still_running (inline work only once it has run 8 s without its result)
     * until its job's result comes as this call's final response; refused work is failed, and work whose admission is unknown is uncertain.
     */
    private fun admitted(id:String,name:String,receipt:JSONObject,mode:String) {
        // The job's result may have come over the updates channel before this answer: it stays the function's final result.
        if(link.isFinal(id)) return
        synchronized(toolReceipts) { toolReceipts[id]=JSONObject(receipt.toString()).put("function_name",name) }
        val status=receipt.optString("status")
        val code=receipt.string("error_code").ifBlank { receipt.string("reason") }
        when {
            status=="not_sent" -> { link.ran(id,false);link.failed(id,code.ifBlank { "not_sent" },receipt) }
            status in setOf("uncertain","submission_unconfirmed")||receipt.string("job_id").isBlank()&&status=="unknown" -> link.failed(id,code.ifBlank { "outcome_unknown" },receipt,status="uncertain")
            receipt.string("job_id").isBlank() -> finish(id,name,receipt)
            else -> {
                link.ran(id,true)
                // Said at once: Gemini Live says nothing until the function answers, so a quiet admission left the owner 8.8 to 9.3 s
                // without a word (device round 1). Its later states stay quiet; its result is the final answer.
                val on=receipt.string("callback_consent")=="requested"
                callBackTurnOpen=true;callBackAskedAt.set(-1)
                link.state(id,"handed_over",null,JSONObject(receipt.toString()).put("call_back",if(on) "on" else "off").apply {
                    if(on) put("call_back_note","The owner asked for this call back in their request; it is already on and rings them when this is done.")
                    else put("call_back_question","Ask the owner once whether they want a call back when it is done.")
                })
            }
        }
    }
    /** The model's turn after a hand-over ended: it asked there, so only the owner's words from now on can be a yes. */
    private fun callBackTurnEnded() {
        if(callBackTurnOpen) { callBackTurnOpen=false;callBackAskedAt.set(ownerWords.get());return }
        // The model's reply to the owner's answer ended: a call back it turned on there was kept as its frame came; later ones need a new
        // question (REVIEW-E3-2 finding 1).
        val asked=callBackAskedAt.get()
        if(asked>=0&&ownerWords.get()>asked) callBackAskedAt.compareAndSet(asked,-1)
    }
    /**
     * A frame of function calls, in the order the provider sent it (REVIEW-E3 finding 1). A hand-over in it needs its own question, so the
     * marker of an earlier one is cleared first; then each hermes_callback in it keeps the marker as it stands now, before any later turn end.
     */
    private fun callBackOrder(calls:org.json.JSONArray) {
        val names=(0 until calls.length()).map { calls.optJSONObject(it)?.optString("name").orEmpty() }
        if(names.any { !directToolIsReadOnly(it)&&!declaredRead(it) }) gatewayReceipts.forgetReads()
        if(names.any { it=="hermes_work"||it=="hermes_follow" }) { callBackTurnOpen=false;callBackAskedAt.set(-1) }
        for(i in 0 until calls.length()) if(names[i]=="hermes_callback") calls.optJSONObject(i)?.optString("id")?.let { callBackAsked[it]=callBackAskedAt.get() }
    }
    /**
     * Whether the owner answered the model's question: the turn after the hand-over had ended when function call [id] arrived, and the owner
     * spoke after it. Structural, not a reading of words: a call back turned on in the turn that asks, or before the owner spoke, or with
     * the answer to an earlier hand-over's question, is refused. A transcript may trail the model's call, so it waits a moment for the
     * owner's words, never for the question's turn to end. One answer turns it on once.
     */
    private suspend fun ownerAnsweredCallBack(id:String):Boolean {
        val asked=callBackAsked.remove(id)?:callBackAskedAt.get()
        if(asked<0) return false
        val answered=withTimeoutOrNull(OWNER_WORDS_GRACE_MS) { while(ownerWords.get()<=asked) delay(100);true }==true
        if(answered) callBackAskedAt.compareAndSet(asked,-1)
        return answered
    }
    /** A 1.2.0 plugin: the hand-over goes through this chat's own socket after the plugin records it, as in 1.2.0. */
    private suspend fun legacyWork(id:String,requestId:String,text:String,prompt:String,callBack:Boolean?,mode:String,frame:NativeVoiceVisual.Frame?):JSONObject {
        val consent=when(callBack) { true -> "requested"; false -> "declined"; null -> "unset" }
        ensureNativeEvents()
        val jpeg=frame?.let { java.util.Base64.getEncoder().encodeToString(it.bytes) }
        lateinit var binding:dev.hermes.android.data.NativeSessionBinding
        suspend fun submitOnce():JSONObject {
            synchronized(toolReceipts) { toolReceipts[id]?.put("status","staging_native") }
            val receipt=submitVoiceContinuation({callOwned()},{
                calls.nativeRequest("/api/plugins/beacon/mobile/calls/$callId/work","POST",JSONObject()
                    .put("stored_session_id",binding.storedSessionId).put("runtime_session_id",binding.runtimeSessionId)
                    .put("request_id",requestId).put("text",text).apply { callBack?.let { put("callback",it) } })
            },{
                client.nativeVoiceSubmit(binding,JSONObject().put("session_id",binding.runtimeSessionId).put("text",prompt)
                    .put("submission_id",requestId).put("surface","voice-live").put("queued",true),jpeg,true,
                    ownerFence,{ callOwned() },{
                        synchronized(toolReceipts) { toolReceipts[id]?.takeIf { it.optString("status")=="staging_native" }?.let { it.put("status","submitting_native");true } == true }
                    },{ callOwned() && frame != null && visual.permits(frame) })
            })
            if(frame!=null && receipt.optString("voice_visual") in setOf("image","file_reference")) visual.confirmed(frame)?.let(event)
            if(frame!=null && receipt.optString("voice_visual")=="unavailable") event(JSONObject().put("type","warning").put("message","The current shared frame could not be attached to this request."))
            return receipt
        }
        val receipt=try {
            require(selectedSession.isNotBlank()) { "The attached conversation is unavailable" }
            binding=client.nativeSession(selectedSession)
            val canonical=client.nativeTurnLease(binding.storedSessionId,binding.runtimeSessionId).authority
            // The same agent as text chat owns reasoning, memory, tools and approvals; Hermes' approval read may wait 30 s for a cold agent build.
            if(directAdmissionAttempt(45000,{true}) { awaitNativeVoiceAgent(client,binding);true }!=true) return JSONObject().put("status","not_sent").put("reason","agent_not_ready")
            if(canonical) submitOnce() else submissionLock.withLock {
                var capacity=true
                submitNativeWhenIdle({callOwned()},{
                    val status=calls.nativeRequest("/api/plugins/beacon/mobile/calls/$callId")
                    val jobs=status.optJSONArray("jobs") ?: throw java.io.IOException("Call job capacity is unavailable")
                    capacity=jobs.length()<64
                    if(!capacity) true else {
                        val watching=status.optString("job_linkage")!="row_bound" && (0 until jobs.length()).any { i -> jobs.optJSONObject(i)?.let { job ->
                            job.optString("runtime_session_id")==binding.runtimeSessionId &&
                                (job.optString("status") in setOf("working","watching","needs_input") || job.optString("status")=="uncertain" && job.optBoolean("will_continue"))
                        }==true }
                        !watching && client.nativeSessionStatus(binding.storedSessionId,binding.runtimeSessionId)=="idle"
                    }
                },{ if(!capacity) JSONObject().put("status","not_sent").put("reason","job_capacity") else submitOnce() },{delay(1000)})
            }
        } catch(error:CancellationException) { throw error }
        catch(error:Exception) {
            if(error is ApiFailure&&error.status==503&&error.message=="Hermes is updating") return JSONObject().put("status","not_sent").put("reason","hermes_updating")
            val stage=synchronized(toolReceipts) { toolReceipts[id]?.optString("status") }
            return JSONObject().put("status",if(stage in setOf("creating_native_session","waiting_for_idle","submitting","staging_native")) "not_sent" else "submission_unconfirmed")
        }
        val shared=JSONObject().put("stored_session_id",binding.storedSessionId).put("runtime_session_id",binding.runtimeSessionId)
        return when {
            receipt.optString("status")=="not_sent" -> shared.put("status","not_sent").put("reason",receipt.optString("reason"))
            receipt.optBoolean("continuation_replay") -> receipt
            receipt.optString("status")=="unknown" || receipt.optBoolean("voice_authority") && receipt.optString("admission_id").isBlank() -> JSONObject().put("status","submission_unconfirmed")
            receipt.optBoolean("voice_authority") -> shared.put("status",receipt.optString("status")).put("admission_id",receipt.getString("admission_id"))
                .put("title",text.take(100)).put("job_id",receipt.getString("job_id")).put("callback_consent",receipt.optString("callback_consent",consent))
                .apply { receipt.opt("user_row_id")?.takeIf { it is Number }?.let { put("user_row_id",it) };if(receipt.has("outcome")) put("outcome",receipt.get("outcome")) }
            !receipt.has("user_row_id")||receipt.isNull("user_row_id") -> shared.put("status",receipt.optString("status","accepted_unlinked"))
            else -> {
                val linked=link.track(id) { calls.nativeRequest("/api/plugins/beacon/mobile/calls/$callId/jobs","POST",JSONObject().put("stored_session_id",binding.storedSessionId)
                    .put("runtime_session_id",binding.runtimeSessionId).put("request_id",requestId).put("user_row_id",receipt.opt("user_row_id")).put("title",text.take(100)).put("callback",callBack==true)) }
                require(linked.optString("job_id").isNotBlank()) { "Native job linkage was not confirmed" }
                shared.put("status",receipt.optString("status","submitted")).put("job_id",linked.getString("job_id")).put("callback_consent",consent)
            }
        }
    }
    private fun claimMutation(id:String):Boolean=synchronized(toolReceipts) {
        if(!callOwned()) false else { toolReceipts[id]?.put("status","submitting_native");true }
    }
    /** Audio closes immediately; an already sent admission/link may still return its single receipt. */
    internal suspend fun settleAcceptedWork():Boolean=withTimeoutOrNull(25000) {
        val accepted=synchronized(toolJobs) { toolJobs.filterValues { id -> synchronized(toolReceipts) { !directToolMayCancel(toolReceipts[id]?.optString("status")) } }.keys.toList() }
        accepted.joinAll();true
    }==true
    fun close():DirectCallEnd {
        val disposition=synchronized(ownerFence) {
            synchronized(toolReceipts) {
                stopped=true
                DirectCallEnd(toolReceipts.values.count { (it.optString("function_name") in setOf("hermes_work","hermes_follow")&&it.optString("status") in setOf("waiting_for_idle","submitting","staging_native")) || it.optString("status")=="needs_approval" },
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
        socket?.cancel();run?.cancel();polling?.cancel();microphone.close();controls.close();visualFrames.cancel();providerHttp.dispatcher.executorService.shutdown()
        // close() can run on the main thread; evicting pooled TLS sockets writes to the network.
        kotlin.concurrent.thread(name = "beacon-voice-close") { providerHttp.connectionPool.evictAll();calls.http.connectionPool.evictAll() }
        return disposition
    }
}
