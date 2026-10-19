package dev.hermes.android.voice

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.IBinder
import android.os.Handler
import android.os.HandlerThread
import android.telecom.DisconnectCause
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.telecom.*
import dev.hermes.android.*
import dev.hermes.android.R
import dev.hermes.android.core.*
import dev.hermes.android.data.CypherClient
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.MissingVoiceKeyException
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.data.voiceProviderSupported
import dev.hermes.android.data.authorizesPushSource
import dev.hermes.android.data.cachedScopeFor
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import java.util.concurrent.atomic.AtomicBoolean

data class AudioRoute(val id: String, val name: String)
data class CallState(val active: Boolean = false, val state: String = "idle", val muted: Boolean = false, val transcript: String = "", val error: String = "", val routes: List<AudioRoute> = emptyList(), val route: String = "", val startedAt: Long = 0, val conversationScope: Scope? = null, val callId: String = "", val transcripts: List<CallTranscript> = emptyList(), val transcriptStatus: String = "", val cameraSharing: Boolean = false, val cameraRear: Boolean = false, val cameraBusy: Boolean = false, val cameraCanFlip: Boolean = false, val cameraStopPending: Boolean = false, val held: Boolean = false, val userSpeaking: Boolean = false, val remoteSpeaking: Boolean = false, val localAudioLevel: Float? = null, val remoteAudioLevel: Float? = null, val minimized: Boolean = false, val screenSharing: Boolean = false, val screenBusy: Boolean = false, val screenStopPending: Boolean = false, val overlayPermissionMissing: Boolean = false, val contextNotice: String = "", val audioStopPending: Boolean = false, val hostCleanupPending: Boolean = false, val toolApprovals: List<CallToolApproval> = emptyList(), val voiceProvider: String = "gemini", val visionAvailable: Boolean = true)
internal fun CallState.withServerWarning(message: String, eventCallId: String, authorizedCallId: String): CallState {
    if (!active || callId.isBlank() || callId != authorizedCallId || eventCallId.isNotBlank() && eventCallId != callId || message.isBlank() || message.length > 240) return this
    return copy(error = message)
}
internal fun CallState.transcriptLabel(): String = when (transcriptStatus) {
    "partial", "unavailable", "failed" -> "Text synchronization incomplete"
    "syncing", "finalizing" -> "Synchronizing text"
    "saved" -> "Text conversation synchronized"
    else -> ""
}
internal fun CallState.afterCleanupFailure(error: Throwable): CallState =
    if (error is ApiFailure && error.status == 404) this else copy(hostCleanupPending=true,error=listOf(this.error, "Call ended locally; PC call cleanup is unconfirmed").filter(String::isNotBlank).distinct().joinToString(" "))
internal fun finishCallOnce(closed: AtomicBoolean, fence: () -> Unit = {}, finish: (Throwable?) -> Unit): Boolean {
    if (!closed.compareAndSet(false, true)) return false
    val fenceFailure = runCatching(fence).exceptionOrNull()
    finish(fenceFailure)
    return true
}
internal suspend fun awaitCallTeardown(destroyed: Deferred<Unit>, timeoutMillis: Long = 45_000, current: () -> Boolean) {
    check(withTimeoutOrNull(timeoutMillis) { destroyed.await();true } == true) { "The previous call is still closing. Wait a moment and try again." }
    check(current()) { "Call start was cancelled" }
}
internal suspend fun finishTranscriptSyncForCleanup(finish: suspend () -> Boolean): Boolean = runCatching { finish() }.getOrDefault(false)
internal fun CallState.afterOwnedCleanupFailure(error: Throwable, ownsCall: Boolean): CallState =
    if (ownsCall) afterCleanupFailure(error) else this
internal fun CallState.afterTerminalControls(fenceFailure: Throwable?): CallState = copy(
    held=false,muted=false,userSpeaking=false,remoteSpeaking=false,localAudioLevel=null,remoteAudioLevel=null,
    routes=emptyList(),route="",cameraRear=false,cameraCanFlip=false,overlayPermissionMissing=false,contextNotice="",toolApprovals=emptyList(),
    error=listOf(error,if(fenceFailure!=null) "Call stop fence failed; capture or transcript finalization was unconfirmed" else "")
        .filter(String::isNotBlank).distinct().joinToString(" "))
internal fun cameraMayShare(requested: Boolean, visible: Boolean, held: Boolean, closed: Boolean): Boolean =
    requested && visible && !held && !closed

internal fun cameraStopRequested(event: JSONObject): Boolean = event.string("type") == "camera_state" && event.opt("enabled") == false

internal data class CallSpec(val client: CypherClient, val grant: JSONObject, val incoming: Boolean = false, val credentials: VoiceSessionCredentials? = null)

internal fun phoneKeyCallBody(provider:String,profile:String,conversationId:String?):JSONObject {
    require(voiceProviderSupported(provider))
    return JSONObject().put("profile",profile).put("voice_provider",provider).put("client_key",true).apply {
        conversationId?.let { put("conversation_id",it) }
    }
}
internal fun phoneKeyAnswerBody(provider:String):JSONObject {
    require(voiceProviderSupported(provider))
    return JSONObject().put("voice_provider",provider).put("client_key",true)
}

class CallController(private val app: CypherApplication) {
    private val _state = MutableStateFlow(CallState())
    val state = _state.asStateFlow()
    @Volatile internal var spec: CallSpec? = null
    @Volatile internal var service: CypherCallService? = null
    @Volatile var ownerScope: Scope? = null
        set(value) { field=value; service?.refreshOverlay() }
    fun minimize(expectedId: String) { if(state.value.callId==expectedId) service?.minimize(expectedId) }
    fun resumeWindow() { service?.restoreWindow() }
    fun refreshOverlayPermission() { _state.update { it.withOverlayPermission(android.provider.Settings.canDrawOverlays(app)) } }
    @Volatile var uiVisible = false
        set(value) {
            field = value
            // Invalidate before minimize starts its first asynchronous authorization read.
            service?.refreshOverlay()
            if (!value) { if (backgroundNeedsBubble(state.value,value)) service?.minimize(state.value.callId) else service?.camera(false) }
        }
    private val _cameraTrack = MutableStateFlow<android.graphics.Bitmap?>(null)
    val cameraTrack = _cameraTrack.asStateFlow()
    fun cameraPreview(callId:String,view:android.view.TextureView,attached:Boolean) {
        val current=state.value
        if(callId.isNotBlank()&&current.active&&current.callId==callId) service?.cameraPreview(callId,view,attached)
    }
    internal fun cameraTrack(owner: CypherCallService, track: android.graphics.Bitmap?) { if (service === owner) _cameraTrack.value = track }
    fun camera(value: Boolean) { service?.camera(value) }
    fun decideTool(callId: String, requestId: String, proposalId: String, approve: Boolean) { if (state.value.callId == callId) service?.decideTool(requestId, proposalId, approve) }
    fun flipCamera() { service?.flipCamera() }
    fun beginScreenConsent(expectedId: String): Long = service?.beginScreenConsent(expectedId) ?: 0L
    fun screenConsent(expectedId: String, token: Long, consent: Intent?) { service?.screenConsent(expectedId,token,consent) }
    fun stopScreen() { service?.stopScreen() }
    fun previewFailed(expectedCall: String) { service?.previewFailed(expectedCall) }
    private val starting = AtomicBoolean(false)
    private val switching = AtomicBoolean(false)
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    suspend fun start(client: CypherClient, conversationId: String? = null) {
        check(!switching.get()) { "Wait for the voice model switch to finish" }
        val keyStore=VoiceKeyStore(app)
        val credentials=withContext(Dispatchers.IO) { keyStore.session() }?:throw MissingVoiceKeyException(keyStore.settings().provider)
        start(client, null, conversationId,credentials)
    }
    suspend fun switchToGemini(expectedId:String):String {
        val before=state.value
        require(before.active&&before.callId==expectedId&&before.voiceProvider=="grok"&&before.conversationScope!=null) { "This Grok call has ended" }
        val credentials=withContext(Dispatchers.IO) { VoiceKeyStore(app).session("gemini") }?:throw MissingVoiceKeyException("gemini")
        check(switching.compareAndSet(false,true)) { "A voice switch is already in progress" }
        var replacement=""
        try {
            val previous=checkNotNull(service) { "Wait until the call has connected" }
            val client=previous.clientForHandoff(expectedId)
            check(uiVisible&&ownerScope==client.scope) { "Open this call before switching" }
            val generation=epoch.incrementAndGet()
            previous.hangup()
            awaitCallTeardown(previous.destroyed) { epoch.get()==generation&&uiVisible&&ownerScope==client.scope }
            start(client,null,before.conversationScope.session,credentials)
            replacement=state.value.callId
            withTimeout(45000) {
                state.first {
                    check(epoch.get()==generation&&uiVisible&&ownerScope==client.scope&&it.callId==replacement&&it.active) { "Voice switch cancelled" }
                    check(it.error.isBlank()) { it.error }
                    it.visionAvailable&&it.state in setOf("listening","speaking")
                }
            }
            return replacement
        } catch(error:Throwable) {
            if(replacement.isNotBlank()&&state.value.callId==replacement) hangup()
            throw error
        } finally { switching.set(false) }
    }
    suspend fun answer(source: String, event: String) {
        check(!switching.get()) { "Wait for the voice model switch to finish" }
        val keyStore=VoiceKeyStore(app)
        val credentials=withContext(Dispatchers.IO) { keyStore.session() }?:throw MissingVoiceKeyException(keyStore.settings().provider)
        check(ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Allow microphone access to answer Cypher" }
        require(app.push.claimInvitation(source, event)) { "This Cypher invitation has ended" }
        val (client, route) = withTimeout(15000) { resolveIncoming(app, source, event) }
        start(client, IncomingCall(source, event, route.getLong("expires_at")),voiceSettings=credentials)
    }
    private suspend fun start(client: CypherClient, incoming: IncomingCall?, conversationId: String? = null, voiceSettings:VoiceSessionCredentials) {
        requireCameraReleasedForNewCall(_state.value)
        check(!_state.value.audioStopPending) { "Microphone release is unconfirmed. Use Android’s Stop app control before another call." }
        check(!_state.value.active) { "Cypher already has a call" }
        check(ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Allow microphone access to call Cypher" }
        check(starting.compareAndSet(false, true)) { "Cypher is already connecting" }
        val generation = epoch.get()
        val frozen = client.detached()
        var grant: JSONObject? = null
        try {
            service?.let { awaitCallTeardown(it.destroyed) { generation == epoch.get() } }
            check(generation == epoch.get()) { "Call start was cancelled" }
            // Saved-auth restoration must recognize an already admitted local owner during reservation.
            _state.value = CallState(true, "connecting", startedAt = System.currentTimeMillis(), conversationScope = conversationId?.let { frozen.scope.session(it) })
            if (incoming != null) require(app.push.invitation(incoming.source, incoming.event) != null) { "This Cypher invitation has already ended" }
            grant = if (incoming == null) frozen.nativeRequest("/api/plugins/cypher/mobile/calls", "POST",
                phoneKeyCallBody(voiceSettings.provider,frozen.auth.profile,conversationId))
                else frozen.nativeRequest("/api/plugins/cypher/mobile/callbacks/" + incoming.event + "/answer", "POST", phoneKeyAnswerBody(voiceSettings.provider))
            require(grant.string("provider")==voiceSettings.provider&&voiceProviderSupported(voiceSettings.provider)&&
                grant.string("transport")==voiceSettings.provider+"_live_direct") { "Unsupported voice transport" }
            require(grant.string("profile") == frozen.auth.profile && grant.string("call_id").isNotBlank() && grant.string("conversation_id").isNotBlank()) { "Invalid call authorization" }
            if (incoming != null) app.push.discardInvitation(incoming.source, incoming.event)
            if (generation != epoch.get()) { release(CallSpec(frozen, grant)); return }
            if (incoming != null) {
                require(app.push.permitsIncoming(incoming.source)) { "The invitation's owner signed out" }
                require(uiVisible) { "Open Cypher to finish answering Cypher" }
            }
            spec = CallSpec(frozen, grant, incoming != null,voiceSettings)
            _state.update { it.copy(conversationScope = frozen.scope.session(grant.getString("conversation_id")), callId = grant.getString("call_id"), voiceProvider = voiceSettings.provider, visionAvailable = voiceSettings.provider == "gemini").withOverlayPermission(android.provider.Settings.canDrawOverlays(app)) }
            ContextCompat.startForegroundService(app, Intent(app, CypherCallService::class.java).setAction(CypherCallService.START))
        } catch (e: Throwable) {
            if (generation == epoch.get()) { spec = null; _state.value = CallState(error = e.message.orEmpty()) }
            grant?.takeIf { it.string("call_id").isNotBlank() }?.let { release(CallSpec(frozen, it)) }
            if (incoming != null && e !is CancellationException) reconcileIncomingFailure(app, frozen, incoming.source, incoming.event, e)
            throw e
        } finally { starting.set(false) }
    }
    private suspend fun release(pending: CallSpec) { withContext(NonCancellable) { withTimeoutOrNull(5000) { runCatching { pending.client.nativeRequest("/api/plugins/cypher/mobile/calls/${pending.grant.getString("call_id")}", "DELETE") } } } }
    internal fun rejectPendingStart(message: String = "The previous call is still ending. Try again.") {
        val pending = spec ?: return
        spec = null
        _state.update { it.copy(active = false, state = "idle", error = message) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { release(pending) }
    }
    fun hangup() { epoch.incrementAndGet(); service?.hangup() ?: run { val pending = spec; spec = null; _state.update { it.copy(active = false, state = "idle") }; if (pending != null) CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { release(pending) } } }
    fun mute() { service?.mute(!_state.value.muted) }
    fun route(id: String) { service?.route(id) }
    internal fun update(change: (CallState) -> CallState) { _state.update(change) }
    internal fun updateFrom(owner: CypherCallService, change: (CallState) -> CallState) {
        update { if (service === owner) change(it) else it }
    }
}

class CypherCallService : Service() {
    internal fun clientForHandoff(expectedId:String):CypherClient {
        check(!closed.get()&&spec?.grant?.optString("call_id")==expectedId) { "The call changed before switching" }
        return checkNotNull(spec).client.detached()
    }
    internal val destroyed = CompletableDeferred<Unit>()
    private val platformDestroyed = CompletableDeferred<Unit>()
    private val app get() = application as CypherApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private var spec: CallSpec? = null
    private var directVoice: DirectVoice? = null
    private var pcm: NativePcm? = null
    private var transcriptSync: NativeCallTranscriptSync? = null
    private var visual: NativeVisual? = null
    private var previousAudioMode:Int?=null
    private var transcriptTurn = 0L
    private val transcriptText = mutableMapOf<String,String>()
    private var retainedCameraWarning = false
    // Native camera/projection operations serialize off the UI thread.
    private val mediaThread = HandlerThread("CypherMedia").apply { start() }
    private val mediaDispatcher = Handler(mediaThread.looper).asCoroutineDispatcher()
    private val mediaLock = Mutex()
    private val microphoneGate = MicrophoneIntentGate()
    private val cameraGate = CameraOperationGate()
    private val screenGate = ScreenConsentGate()
    @Volatile private var screenRequested = false
    @Volatile private var screenCaptureAttempted = false
    @Volatile private var cameraCaptureAttempted = false
    private var control: CallControlScope? = null
    private var endpoints = emptyList<CallEndpointCompat>()
    @Volatile private var muted = false
    @Volatile private var held = false
    @Volatile private var cameraRequested = false
    private var overlay: CallOverlayWindow? = null
    private var overlayCallId=""
    private var overlayAuthorityJob: Job? = null
    private val overlayAuthority by lazy { OverlayAuthority(app.auth.authorityEpoch, read@ {
        val frozen=spec?.client?.scope ?: return@read null
        val auth=app.auth.read(frozen.origin)
        val now=System.currentTimeMillis()
        // Require the cookie to remain valid throughout this bounded snapshot; parse/decrypt only on IO.
        auth.cachedScopeFor(frozen.origin,false,now+OverlayAuthority.LIFETIME_MS)
            ?.let { OverlayAuthorization(it,android.os.SystemClock.elapsedRealtime()+OverlayAuthority.LIFETIME_MS) }
    }) }
    private var connectionJob: Job? = null
    private var telecomJob: Job? = null
    private val ended = CompletableDeferred<Unit>()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == START) {
            // Even a cancelled pending start must satisfy Android's foreground-service handshake.
            try {
                if (spec != null && !closed.get()) foreground(cameraCaptureAttempted, screenCaptureAttempted)
                else ServiceCompat.startForeground(this, NOTIFICATION, notification(starting = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            } catch (_: RuntimeException) {
                app.calls.rejectPendingStart("Android could not start the call. Open Cypher and try again.")
                stopSelf();return START_NOT_STICKY
            }
        }
        if (intent?.action in setOf(HANGUP, MUTE, STOP_SCREEN, TOOL_ALLOW, TOOL_DENY) && (spec == null || intent?.getStringExtra(EXTRA_CALL_ID) != spec?.grant?.optString("call_id"))) {
            if (spec == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == HANGUP) { hangup(); return START_NOT_STICKY }
        if (intent?.action == STOP_SCREEN) { stopScreen(); return START_NOT_STICKY }
        if (intent?.action == MUTE) { mute(!muted); return START_NOT_STICKY }
        if (intent?.action == TOOL_ALLOW || intent?.action == TOOL_DENY) {
            decideTool(intent.getStringExtra("request").orEmpty(),intent.getStringExtra("proposal").orEmpty(),intent.action==TOOL_ALLOW,fromNotification=true)
            return START_NOT_STICKY
        }
        if (spec != null || closed.get()) {
            app.calls.rejectPendingStart()
            if (closed.get()) stopSelf()
            return START_NOT_STICKY
        }
        val pending = app.calls.spec ?: run { stopSelf(); return START_NOT_STICKY }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || !app.auth.read(pending.client.auth.origin).authorizesPushSource(pending.client.scope)) {
            app.calls.rejectPendingStart("Sign in and allow microphone access before calling Cypher"); stopSelf(); return START_NOT_STICKY
        }
        spec = pending; app.calls.spec = null; app.calls.service = this
        scope.launch {
            var last = ""
            while(isActive&&!closed.get()) {
                delay(1000)
                val state=app.calls.state.value
                val label=state.chipLabel()
                if(!closed.get()&&app.calls.service===this@CypherCallService&&state.active&&label!=last) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification())
                    last=label
                }
            }
        }
        try { foreground(camera = false) }
        catch (e: RuntimeException) { app.calls.updateFrom(this@CypherCallService) { it.copy(error = "Open Cypher and allow microphone access to continue the call") }; hangup(); return START_NOT_STICKY }
        // Home may precede service binding; reconcile once foreground admission succeeds.
        if (backgroundNeedsBubble(app.calls.state.value,app.calls.uiVisible)) { refreshOverlay(); minimize(app.calls.state.value.callId) }
        telecomJob = scope.launch {
            try {
                admitCallTransport(mediaLock,closed,{app.calls.service===this@CypherCallService}) {
                    val audioManager=getSystemService(AudioManager::class.java)
                    previousAudioMode=audioManager.mode;audioManager.mode=AudioManager.MODE_IN_COMMUNICATION
                }
                if(closed.get()) return@launch
                check(ContextCompat.checkSelfPermission(this@CypherCallService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission was revoked" }
                val manager = CallsManager(this@CypherCallService)
                manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
                currentCoroutineContext().ensureActive()
                if(closed.get()||app.calls.service!==this@CypherCallService) return@launch
                val attributes = CallAttributesCompat(displayName = "Cypher", address = Uri.parse("hermes:xara"), direction = if (pending.incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING, callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
                manager.addCall(attributes, onAnswer = { _ -> hold(false) }, onDisconnect = { _ -> hangup() }, onSetActive = { hold(false) }, onSetInactive = { hold(true) }) {
                    control = this
                    launch { availableEndpoints.collect { values -> endpoints = values; app.calls.updateFrom(this@CypherCallService) { it.copy(routes = values.map { e -> AudioRoute(e.identifier.toString(), e.name.toString()) }) } } }
                    launch { currentCallEndpoint.collect { endpoint -> app.calls.updateFrom(this@CypherCallService) { it.copy(route = endpoint.identifier.toString()) } } }
                    launch { isMuted.collect { value -> mute(value) } }
                    launch {
                        try {
                            if (!closed.get()) {
                                val result = if (pending.incoming) answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) else setActive()
                                if (result is CallControlResult.Error) failure("Android could not activate the call: $result")
                                else if (!closed.get() && app.calls.service === this@CypherCallService) openTransport(pending)
                            }
                            ended.await()
                        } finally {
                            // Cancelling our service scope must not cancel Android's disconnect request.
                            withContext(NonCancellable) { withTimeoutOrNull(5_000) { runCatching { disconnect(DisconnectCause(DisconnectCause.LOCAL)) } } }
                        }
                    }
                }
            } catch (e: Throwable) { if (e !is CancellationException) failure(e.message ?: "Call ended unexpectedly") else hangup() }
            finally { control = null }
        }
        return START_NOT_STICKY
    }
    internal fun cameraPreview(callId:String,view:android.view.TextureView,attached:Boolean) {
        scope.launch(mediaDispatcher) {
            val current=app.calls.state.value
            if(!closed.get()&&app.calls.service===this@CypherCallService&&current.active&&current.callId==callId&&spec?.grant?.optString("call_id")==callId) visual?.preview(view,attached)
        }
    }
    private fun openTransport(spec: CallSpec) {
        connectionJob = scope.launch(mediaDispatcher, start=CoroutineStart.LAZY) {
            admitCallTransport(mediaLock,closed,{app.calls.service===this@CypherCallService}) {
            try {
                transcriptSync = NativeCallTranscriptSync(spec.client::nativeRequest,spec.grant.getString("call_id"),scope) { status -> app.calls.updateFrom(this@CypherCallService) { it.copy(transcriptStatus=status) } }
                val voiceSettings=checkNotNull(spec.credentials)
                val audio = NativePcm(scope,{ bytes,at ->
                    val intent=microphoneGate.snapshot()
                    if(directVoice?.pcm(bytes) { microphoneGate.current(intent)&&intent.enabled&&!closed.get() }!=true) app.calls.updateFrom(this@CypherCallService) { it.copy(state="connecting") }
                },{_,_ -> },::failure,::audioLevel,PcmQueue(),captureRate=if(voiceSettings.provider=="openai")48000 else 16000)
                pcm=audio;audio.start(this@CypherCallService);audio.mute(muted||held||closed.get())
                if(closed.get()) return@admitCallTransport
                visual=NativeVisual(this@CypherCallService,Handler(mediaThread.looper),{
                    !closed.get()&&!held&&(screenRequested||cameraMayShare(cameraRequested,app.calls.uiVisible,held,closed.get()))
                },{bytes,bitmap,permitted ->
                    if(directVoice?.image(bytes,permitted,onSent={
                        if(!permitted()||closed.get()) bitmap?.recycle()
                        else if(bitmap!=null) { app.calls.cameraTrack(this@CypherCallService,bitmap);app.calls.updateFrom(this@CypherCallService) { if(permitted()&&!closed.get()) it.copy(cameraSharing=true,cameraBusy=false) else it } }
                        else if(screenRequested) app.calls.updateFrom(this@CypherCallService) { it.copy(screenSharing=true,screenBusy=false) }
                    },onDropped={ bitmap?.recycle() })!=true) bitmap?.recycle()
                },{ reason ->
                    val source=if(screenRequested) "screen" else "camera"
                    app.calls.updateFrom(this@CypherCallService) { it.copy(error=reason) };camera(false);stopScreen()
                    directVoice?.setVisualState(source,"failed",reason)
                },{ stopScreen() })
                directVoice=DirectVoice(spec.client,spec.grant.getString("call_id"),spec.grant.getString("conversation_id"),scope,{bytes ->
                    if(!held&&!closed.get()) { if(!audio.enqueue(bytes)&&!closed.get()) { audio.interrupt();app.calls.updateFrom(this@CypherCallService) { it.copy(error="Playback queue overflow; current speech was interrupted") } } }
                },::directEvent,::failure,{ app.calls.state.value.transcripts.takeLast(10).joinToString("\n") { "${it.role}: ${it.text}" }.takeLast(6000) },voiceSettings).also { it.start() }
            } catch(e:Exception) { if(!closed.get()) failure("Native audio initialization failed") }
            }
        }
        connectionJob?.start()
    }
    @Synchronized private fun directEvent(value:JSONObject) {
        if(closed.get()) return
        when(value.string("type")) {
            "interrupted" -> {
                pcm?.interrupt();handleControl(value)
                finishDirectTurn(interrupted=true)
            }
            "transcript" -> {
                val pendingItem=spec?:return
                providerTranscript(value,pendingItem.grant.string("call_id"),pendingItem.grant.string("conversation_id"),pcm?.clock()?:0)?.let { item ->
                    transcriptSync?.event(item);handleControl(item);return
                }
                val role=value.string("role");val text=accumulatedCaption(transcriptText[role].orEmpty(),value.string("text"));transcriptText[role]=text
                val pending=spec?:return
                val id="${pending.grant.string("call_id")}:$role:$transcriptTurn"
                val transcript=value.put("event_id",id).put("session_id",pending.grant.string("conversation_id")).put("text",text)
                transcriptSync?.event(JSONObject(transcript.toString()).put("turn_id",transcriptTurn.toString()).put("at_ms",pcm?.clock()?:0))
                handleControl(transcript)
            }
            "turn_complete" -> {
                finishDirectTurn();app.calls.updateFrom(this) { it.copy(state="listening",remoteSpeaking=false) }
            }
            "state" -> app.calls.updateFrom(this) { it.copy(state=value.string("state")) }
            "warning" -> handleControl(value)
            "tool_approval" -> {
                val approval=runCatching { CallToolApproval.from(value) }.getOrNull() ?: return
                app.calls.updateFrom(this) { it.copy(toolApprovals=(it.toolApprovals.filterNot { p -> p.requestId==approval.requestId }+approval).take(8)) }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification())
                updateToolNotification()
            }
            "media_diagnostic" -> {
                val receipt=JSONObject(value.toString()).put("event_id",java.util.UUID.randomUUID().toString())
                    .put("turn_id","connection").put("at_ms",pcm?.clock()?:0)
                transcriptSync?.event(receipt)
            }
            "tool_receipt" -> {
                if(value.string("status")!="needs_approval") app.calls.updateFrom(this) { it.copy(toolApprovals=it.toolApprovals.filterNot { p -> p.requestId==value.string("request_id") }) }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification())
                updateToolNotification()
                val receipt=JSONObject().put("type","tool_receipt").put("event_id",java.util.UUID.randomUUID().toString()).put("turn_id","tools").put("at_ms",pcm?.clock()?:0)
                for(key in listOf("request_id","tool_name","status","error_code")) if(value.has(key)) receipt.put(key,value.get(key))
                transcriptSync?.event(receipt)
            }
            "visual_state" -> {
                val receipt=JSONObject().put("type","visual_state").put("event_id",java.util.UUID.randomUUID().toString()).put("turn_id","visual").put("at_ms",pcm?.clock()?:0)
                for(key in listOf("source","state","frames_sent")) if(value.has(key)) receipt.put(key,value.get(key))
                transcriptSync?.event(receipt)
            }
        }
    }
    internal fun decideTool(requestId:String,proposalId:String,approve:Boolean,fromNotification:Boolean=false) {
        val pending=spec ?: return
        val state=app.calls.state.value
        fun visibleOrUnlockedNotification()=if(fromNotification) !getSystemService(KeyguardManager::class.java).isDeviceLocked else app.calls.uiVisible
        if(closed.get()||!visibleOrUnlockedNotification()||app.calls.ownerScope?.let { it.origin==pending.client.scope.origin&&it.owner==pending.client.scope.owner&&it.profile==pending.client.scope.profile }!=true) return
        if(!app.auth.read(pending.client.scope.origin).authorizesPushSource(pending.client.scope)) return
        if(state.toolApprovals.none { it.requestId==requestId&&it.proposalId==proposalId&&!it.busy }) return
        app.calls.updateFrom(this) { it.copy(toolApprovals=it.toolApprovals.map { p -> if(p.requestId==requestId) p.copy(busy=true,error="") else p }) }
        updateToolNotification()
        scope.launch {
            try {
                check(!closed.get()&&spec===pending&&app.calls.service===this@CypherCallService&&visibleOrUnlockedNotification())
                check(app.calls.ownerScope?.let { it.origin==pending.client.scope.origin&&it.owner==pending.client.scope.owner&&it.profile==pending.client.scope.profile }==true)
                check(app.auth.read(pending.client.scope.origin).authorizesPushSource(pending.client.scope))
                checkNotNull(directVoice).decideTool(requestId,proposalId,approve)
            }
            catch(error:CancellationException) { throw error }
            catch(error:Exception) { app.calls.updateFrom(this@CypherCallService) { it.copy(toolApprovals=it.toolApprovals.map { p -> if(p.requestId==requestId) p.copy(busy=false,error="Could not confirm the response. Retry checks the same request.") else p }) };updateToolNotification() }
        }
    }
    private fun finishDirectTurn(interrupted:Boolean=false) {
        val pending=spec?:return
        val turn=transcriptTurn
        transcriptTurn=finishDirectTranscriptTurn(pending.grant.string("call_id"),pending.grant.string("conversation_id"),turn,transcriptText,pcm?.clock()?:0,interrupted) {
            handleControl(it);transcriptSync?.event(it)
        }
        if(interrupted) transcriptSync?.event(json("event_id" to "${pending.grant.string("call_id")}:assistant:interrupt:$turn","turn_id" to turn.toString(),"type" to "interrupted","interrupted" to true,"at_ms" to (pcm?.clock()?:0)))
    }
    fun camera(value:Boolean) { if(value&&!app.calls.state.value.visionAvailable) return;updateCamera(value,app.calls.state.value.cameraRear) }
    internal fun previewFailed(expectedCall:String) { if(app.calls.state.value.callId==expectedCall) camera(false) }
    fun flipCamera() { if(app.calls.state.value.cameraSharing) updateCamera(true,!app.calls.state.value.cameraRear) }
    private fun updateCamera(value:Boolean,rear:Boolean) {
        if(closed.get()||app.calls.service!==this@CypherCallService) return
        if(!value&&cameraStopIsNoop(cameraRequested,cameraCaptureAttempted,app.calls.state.value)) return
        if(value&&(screenRequested||app.calls.state.value.screenStopPending)) return
        cameraRequested=cameraMayShare(value,app.calls.uiVisible,held,closed.get())
        directVoice?.setVisualState("camera",if(cameraRequested) "starting" else "off")
        val operation=cameraGate.request(cameraRequested,rear)
        if(!cameraRequested) app.calls.cameraTrack(this,null)
        app.calls.updateFrom(this) { it.copy(cameraSharing=false,cameraBusy=true,cameraStopPending=cameraCaptureAttempted&&!cameraRequested) }
        scope.launch(mediaDispatcher) { mediaLock.withLock {
            if(!cameraGate.current(operation)) return@withLock
            try {
                visual?.stop();cameraCaptureAttempted=false
                if(operation.enabled) { val capture=checkNotNull(visual);foreground(true);cameraCaptureAttempted=true;capture.camera(rear) }
                app.calls.updateFrom(this@CypherCallService) { it.copy(cameraRear=rear,cameraCanFlip=operation.enabled,cameraStopPending=false,cameraBusy=operation.enabled) }
                if(operation.enabled) scope.launch { delay(10000);if(cameraGate.current(operation)&&app.calls.state.value.cameraBusy&&!closed.get()) { app.calls.updateFrom(this@CypherCallService) { it.copy(error="Camera frame delivery did not start") };camera(false) } }
                if(!operation.enabled) foreground(false)
            } catch(e:Exception) { cameraRequested=false;val stopped=runCatching { visual?.stop() }.isSuccess;cameraCaptureAttempted=!stopped;app.calls.updateFrom(this@CypherCallService) { it.afterCameraFailure(stopped) };directVoice?.setVisualState("camera","failed","Camera capture unavailable");foreground(!stopped) }
        } }
    }
    internal fun beginScreenConsent(expectedId:String):Long {
        if(!app.calls.state.value.visionAvailable) return 0L
        val call=app.calls.state.value
        if(closed.get()||app.calls.service!==this@CypherCallService||call.callId!=expectedId||held||call.state=="connecting"||call.screenBusy||call.screenSharing||call.cameraStopPending) return 0
        camera(false);val token=screenGate.request();app.calls.updateFrom(this) { it.copy(screenBusy=true) };return token
    }
    internal fun screenConsent(expectedId:String,token:Long,consent:Intent?) {
        if(!screenGate.permits(token,expectedId,app.calls.state.value,closed.get(),app.calls.service===this@CypherCallService)) return
        if(consent==null) { screenGate.invalidate();app.calls.updateFrom(this) { it.copy(screenBusy=false) };return }
        screenRequested=true
        directVoice?.setVisualState("screen","starting")
        scope.launch(mediaDispatcher) { mediaLock.withLock {
            if(!screenGate.permits(token,expectedId,app.calls.state.value,closed.get(),app.calls.service===this@CypherCallService)||!screenRequested) return@withLock
            try { check(!cameraCaptureAttempted);foreground(false,true);screenCaptureAttempted=true;checkNotNull(visual).screen(consent)
                scope.launch { delay(10000);if(screenGate.current(token)&&screenRequested&&app.calls.state.value.screenBusy&&!closed.get()) { app.calls.updateFrom(this@CypherCallService) { it.copy(error="Screen frame delivery did not start") };stopScreen() } }
            }
            catch(e:Exception) { stopScreen();app.calls.updateFrom(this@CypherCallService) { it.copy(error="Screen capture unavailable") } }
        } }
    }
    internal fun stopScreen() {
        if(screenRequested||screenCaptureAttempted) directVoice?.setVisualState("screen","off")
        screenRequested=false;screenGate.invalidate()
        app.calls.updateFrom(this) { it.copy(screenSharing=false,screenBusy=false,screenStopPending=screenCaptureAttempted) }
        scope.launch(mediaDispatcher) { mediaLock.withLock {
            if(screenRequested) return@withLock
            val stopped=runCatching { if(screenCaptureAttempted) visual?.stop() }.isSuccess
            screenCaptureAttempted=!stopped
            app.calls.updateFrom(this@CypherCallService) { it.copy(screenStopPending=!stopped) };if(!closed.get()) foreground(cameraCaptureAttempted,!stopped)
        } }
    }
    internal fun refreshOverlay() {
        overlayAuthority.invalidate()
        scope.launch(Dispatchers.Main.immediate) { overlay?.refresh() }
    }
    internal fun minimize(expectedId: String) {
        if(closed.get()||app.calls.service!==this@CypherCallService||app.calls.state.value.callId!=expectedId) return
        camera(false)
        overlayCallId=expectedId
        app.calls.updateFrom(this) { it.copy(minimized=true,overlayPermissionMissing=!android.provider.Settings.canDrawOverlays(this)) }
        scope.launch(Dispatchers.Main.immediate) {
            if(closed.get()||app.calls.state.value.callId!=expectedId||!android.provider.Settings.canDrawOverlays(this@CypherCallService)) return@launch
            if(overlay==null) overlay=CallOverlayWindow(this@CypherCallService,{app.calls.state.value},{
                val state=app.calls.state.value
                !closed.get()&&!app.calls.uiVisible&&state.minimized&&overlayAuthority.permits(state,overlayCallId,app.calls.ownerScope,
                    !getSystemService(KeyguardManager::class.java).isDeviceLocked&&getSystemService(android.os.PowerManager::class.java).isInteractive,android.os.SystemClock.elapsedRealtime())
            },{if(app.calls.state.value.callId==expectedId) mute(!muted)},{if(app.calls.state.value.callId==expectedId) hangup()},{
                if(app.calls.state.value.callId==expectedId) startActivity(Intent(this@CypherCallService,CypherActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_CALL_ID,expectedId))
            },{android.provider.Settings.Global.getFloat(contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f})
            if(overlayAuthorityJob?.isActive!=true) overlayAuthorityJob=scope.launch {
                while(isActive&&!closed.get()) {
                    overlayAuthority.refresh()
                    withContext(Dispatchers.Main.immediate) { overlay?.refresh() }
                    delay(1_000)
                }
            }
        }
    }
    internal fun restoreWindow() {
        overlayAuthorityJob?.cancel();overlayAuthorityJob=null;overlayAuthority.invalidate()
        app.calls.updateFrom(this) { it.copy(minimized=false).withOverlayPermission(android.provider.Settings.canDrawOverlays(this)) }
        scope.launch(Dispatchers.Main.immediate) { overlay?.close();overlay=null }
    }
    private fun foreground(camera: Boolean, screen: Boolean = screenRequested) {
        val captureTypes=if(android.os.Build.VERSION.SDK_INT>=30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or (if(camera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) else 0
        val types = captureTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
            (if (screen) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        ServiceCompat.startForeground(this, NOTIFICATION, notification(), types)
    }
    private fun activity(user: Boolean? = null, remote: Boolean? = null) {
        if (!closed.get()) app.calls.updateFrom(this) { if (closed.get()) it else it.copy(userSpeaking = user ?: it.userSpeaking, remoteSpeaking = remote ?: it.remoteSpeaking,
            localAudioLevel = if (user == false) null else it.localAudioLevel, remoteAudioLevel = if (remote == false) null else it.remoteAudioLevel) }
    }
    private val levelCadence = VoiceLevelCadence()
    private fun audioLevel(level: Float, local: Boolean) {
        val normalized = normalizedVoiceLevel(level) ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (closed.get() || !levelCadence.accept(local,now)) return
        app.calls.updateFrom(this) { if (closed.get()) it else if (local) it.copy(localAudioLevel=normalized,userSpeaking=normalized>.03f&&!muted&&!held) else it.copy(remoteAudioLevel=normalized,remoteSpeaking=normalized>.01f,state=if(normalized>.01f) "speaking" else if(it.state=="speaking") "listening" else it.state) }
    }
    private fun handleControl(value: JSONObject) {
        if (closed.get()) return
        when (value.string("type", value.string("event"))) {
            "user_state" -> when(value.string("state")) { "speaking" -> activity(user=true); "listening" -> activity(user=false) }
            "camera_state" -> if (cameraStopRequested(value)) { if(value.string("source","camera")=="screen") stopScreen() else camera(false) }
            "warning", "context_notice" -> {
                val pending = spec ?: return
                if (!app.auth.read(pending.client.scope.origin).authorizesPushSource(pending.client.scope)) return
                app.calls.updateFrom(this@CypherCallService) {
                    if (closed.get()) it else if (value.string("type", value.string("event")) == "context_notice")
                        it.withServerContextNotice(value,pending.grant.string("call_id"))
                    else it.withServerWarning(value.string("message"), value.string("call_id"), pending.grant.string("call_id"))
                }
            }
            "state" -> value.string("state").takeIf { it in setOf("connecting", "reconnecting", "listening", "thinking", "speaking") }?.let { received ->
                app.calls.updateFrom(this@CypherCallService) { call ->
                    val state = received
                    if (closed.get()) call else call.copy(state = state,contextNotice=if(state=="connecting") "" else call.contextNotice)
                }
            }
            "transcript" -> {
                val event = CallTranscript.from(value, spec?.grant?.string("conversation_id").orEmpty(), spec?.grant?.string("call_id").orEmpty())
                // Older final events lacked session/final fields; only their scalar call-stage text is compatible.
                if (event == null && (value.has("session_id") || !value.optBoolean("final", true))) return
                app.calls.updateFrom(this@CypherCallService) {
                    val next = if (event == null) it.transcripts else it.transcripts.acceptTranscript(event)
                    if (closed.get() || event != null && next === it.transcripts) it else it.copy(transcript = if (event != null && event.interrupted && !event.final) "" else value.string("text", value.string("transcript")), transcripts = next)
                }
            }
            "interrupted" -> {
                app.calls.updateFrom(this@CypherCallService) { call ->
                    val state = "listening"
                    if (closed.get()) call else call.copy(state = state, transcript = "", transcripts = call.transcripts.map { event -> if (!event.final && event.role == "assistant") event.copy(interrupted = true) else event })
                }
            }
            "error" -> failure(value.string("message", value.string("error", "Cypher call failed")))
            "ended" -> hangup()
        }
    }
    fun mute(value:Boolean) { changeMute(muted=value);if(!closed.get()) getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification()) }
    private fun hold(value:Boolean) { changeMute(held=value);if(value) { pcm?.interrupt();camera(false);stopScreen() } }
    private fun changeMute(muted:Boolean?=null,held:Boolean?=null) {
        if(closed.get()||app.calls.service!==this@CypherCallService) return
        val intent=microphoneGate.request(muted,held)
        this.muted=intent.muted;this.held=intent.held
        pcm?.mute(!intent.enabled)
        if(!intent.enabled) directVoice?.send(GeminiWire.endAudio())
        app.calls.updateFrom(this) { it.copy(muted=intent.muted,held=intent.held,userSpeaking=false) }
    }
    fun route(id: String) { val endpoint = endpoints.find { it.identifier.toString() == id } ?: return; scope.launch { val result = control?.requestEndpointChange(endpoint); if (result is CallControlResult.Error) app.calls.updateFrom(this@CypherCallService) { it.copy(error = "Audio route could not change: $result") } } }
    private fun failure(message: String) { endCall(message) }
    fun hangup() { endCall(null) }
    @Synchronized private fun syncRemainingTranscript() {
        val pending=spec?:return
        transcriptText.forEach { (role,text) -> transcriptSync?.event(json("event_id" to "${pending.grant.string("call_id")}:$role:$transcriptTurn","session_id" to pending.grant.string("conversation_id"),"turn_id" to transcriptTurn.toString(),"type" to "transcript","role" to role,"text" to text,"final" to false,"interrupted" to true,"at_ms" to (pcm?.clock()?:0))) }
        transcriptText.clear()
    }
    private fun endCall(error:String?) { finishCallOnce(closed,{ pcm?.mute(true);syncRemainingTranscript() }) { fenceFailure ->
        getSystemService(NotificationManager::class.java).cancel(TOOL_NOTIFICATION)
        overlayAuthority.close();overlayAuthorityJob?.cancel();connectionJob?.cancel();cameraRequested=false;screenRequested=false;cameraGate.invalidate();screenGate.invalidate()
        val endingGemini=directVoice
        var endDisposition=endingGemini?.close()?:DirectCallEnd()
        scope.launch(Dispatchers.Main.immediate) { overlay?.close();overlay=null }
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            var captureReleased=false
            var audioReleased=false
            try {
                withContext(mediaDispatcher) { mediaLock.withLock {
                    // An initializer already holding the lock may have assigned transport after the first fence.
                    if(directVoice!==endingGemini) endDisposition=directVoice?.close()?:endDisposition
                    audioReleased=runCatching { pcm?.close() }.isSuccess
                    captureReleased=runCatching { visual?.stop() }.isSuccess
                } }
                ended.complete(Unit)
                val stopped=captureReleased
                app.calls.cameraTrack(this@CypherCallService,null)
                app.calls.updateFrom(this@CypherCallService) { it.copy(active=false,state="ending",cameraSharing=false,cameraBusy=false,screenSharing=false,screenBusy=false,cameraStopPending=!stopped,screenStopPending=!stopped,audioStopPending=!audioReleased,minimized=false,error=listOfNotNull(error?:it.error,if(!audioReleased) "Native microphone release unconfirmed" else null,if(endDisposition.notSent>0) "${endDisposition.notSent} waiting work request(s) were not submitted when the call ended." else null,if(endDisposition.submissionUnknown>0) "${endDisposition.submissionUnknown} native work acceptance/linkage receipt(s) are unconfirmed. Inspect the attached conversation; do not resubmit automatically." else null).filter(String::isNotBlank).joinToString(" ")).afterTerminalControls(fenceFailure) }
                val transcriptClosed=finishTranscriptSyncForCleanup { transcriptSync?.finish()==true }
                if(!transcriptClosed) app.calls.updateFrom(this@CypherCallService) { it.copy(transcriptStatus="partial") }
                spec?.let { pending ->
                    val cleanup=withTimeoutOrNull(5000) { runCatching { pending.client.nativeRequest("/api/plugins/cypher/mobile/calls/${pending.grant.getString("call_id")}","DELETE") } }
                    val receipt=cleanup?.getOrNull()
                    val deleted=receipt?.optBoolean("closed")==true||receipt?.optString("status")=="closed"
                    if(!transcriptClosed&&!deleted) {
                        val failure=java.io.IOException("Host cleanup receipt unconfirmed",cleanup?.exceptionOrNull())
                        app.calls.updateFrom(this@CypherCallService) { it.afterOwnedCleanupFailure(failure,app.calls.service===this@CypherCallService) }
                    }
                    pending.client.closeNative()
                }
            } finally {
                ended.complete(Unit);scope.cancel()
                val telecomReleased=withTimeoutOrNull(6_000) { telecomJob?.join();true }==true
                if(!telecomReleased) app.calls.updateFrom(this@CypherCallService) { it.copy(error="Android call shutdown is unconfirmed. Use Android’s Stop app control before another call.") }
                if(captureReleased&&audioReleased&&telecomReleased) {
                    runCatching { if(app.calls.service===this@CypherCallService) {
                        val audioManager=getSystemService(AudioManager::class.java)
                        if(audioManager.mode==AudioManager.MODE_IN_COMMUNICATION) previousAudioMode?.let { audioManager.mode=it }
                    } }
                    stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();platformDestroyed.await();if(app.calls.service===this@CypherCallService) { app.calls.updateFrom(this@CypherCallService) { it.copy(state="idle") };app.calls.service=null };mediaThread.quitSafely();destroyed.complete(Unit)
                } else {
                    retainedCameraWarning=true
                    ServiceCompat.startForeground(this@CypherCallService,NOTIFICATION,notification(),
                        (if(android.os.Build.VERSION.SDK_INT>=30&&!captureReleased&&cameraCaptureAttempted) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) or (if(!captureReleased&&screenCaptureAttempted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0) or (if(android.os.Build.VERSION.SDK_INT>=30&&!audioReleased) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL))
                }
            }
        }
    } }
    private fun notification(starting: Boolean = false): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cypher calls", NotificationManager.IMPORTANCE_LOW))
        val state=app.calls.state.value
        val open = openCallIntent(state.callId)
        fun action(name: String, code: Int): PendingIntent {
            val id = spec?.grant?.optString("call_id").orEmpty()
            val intent = Intent(this, CypherCallService::class.java).setAction(name).putExtra(EXTRA_CALL_ID, id)
                .setData(Uri.Builder().scheme("cypher-call").authority(packageName).appendPath(id).appendPath(name).build())
            return PendingIntent.getService(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_cypher_voice)
            .setContentTitle(if (retainedCameraWarning) "Cypher call cleanup warning" else "Cypher call")
            .setContentText(if (starting) { if(app.calls.state.value.active) "Connecting to Cypher" else "Call cancelled" } else if (retainedCameraWarning) "Call or capture shutdown unconfirmed; media delivery blocked. Use Android’s Stop app control."
                else if (app.calls.state.value.toolApprovals.isNotEmpty()) "Action needs approval. Open Cypher to review."
                else if (app.calls.state.value.screenStopPending) "Screen stop unconfirmed; visual delivery blocked. Tap to retry."
                else if (app.calls.state.value.screenSharing) "Screen sharing with Cypher"
                else if (app.calls.state.value.cameraStopPending) "Camera stop unconfirmed; video blocked. Tap to retry."
                else if (muted) "Microphone muted" else if (state.remoteSpeaking || state.state == "speaking") "Cypher is speaking" else "Listening to you")
            .setContentIntent(open).setOngoing(true).setCategory(if (retainedCameraWarning) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_CALL)
            .setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_cypher_notification).setContentTitle("Cypher call").setContentText("Tap to return to your call").build())
        if (!retainedCameraWarning && !starting) {
            builder.setStyle(NotificationCompat.CallStyle.forOngoingCall(androidx.core.app.Person.Builder().setName("Cypher").setImportant(true).build(),action(HANGUP,23)))
                .addAction(0, if (muted) "Unmute" else "Mute", action(MUTE, 22))
            builder.setShowWhen(false)
            if(android.os.Build.VERSION.SDK_INT>=36) builder.setRequestPromotedOngoing(true).setShortCriticalText(state.chipLabel())
        }
        if (!retainedCameraWarning && !starting && (app.calls.state.value.screenSharing || app.calls.state.value.screenStopPending)) builder.addAction(0,"Stop sharing screen",action(STOP_SCREEN,24))
        return builder.build()
    }
    private fun openCallIntent(id:String):PendingIntent = PendingIntent.getActivity(this,21,
        Intent(this,CypherActivity::class.java).putExtra(EXTRA_CALL_ID,id).setData(Uri.Builder().scheme("cypher-call").authority(packageName).appendPath(id).appendPath("open").build())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun updateToolNotification() {
        val state=app.calls.state.value
        val manager=getSystemService(NotificationManager::class.java)
        val request=state.toolApprovals.firstOrNull { !it.busy }
        if(closed.get()||!state.active||request==null) { manager.cancel(TOOL_NOTIFICATION);return }
        dev.hermes.android.push.ensureActivityChannels(manager)
        val channel=dev.hermes.android.push.promptNotificationChannel(manager)
        val details="${request.toolName}\n${request.arguments}"
        val open=openCallIntent(state.callId)
        val builder=NotificationCompat.Builder(this,channel).setSmallIcon(R.drawable.ic_cypher_notification)
            .setContentTitle("Cypher needs permission").setContentText(dev.hermes.android.push.boundedNotificationText(details,640,preserveWhitespace=true))
            .setStyle(NotificationCompat.BigTextStyle().bigText(dev.hermes.android.push.boundedNotificationText(details,640,preserveWhitespace=true)))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_STATUS).setTimeoutAfter(300_000)
            .setPublicVersion(NotificationCompat.Builder(this,channel).setSmallIcon(R.drawable.ic_cypher_notification).setContentTitle("Cypher needs permission").setContentText("Unlock to review the request").build())
        if(android.os.Build.VERSION.SDK_INT>=36) builder.setRequestPromotedOngoing(true).setShortCriticalText("Review")
        fun answer(approved:Boolean) {
            val action=if(approved) TOOL_ALLOW else TOOL_DENY
            val intent=Intent(this,CypherCallService::class.java).setAction(action).putExtra(EXTRA_CALL_ID,state.callId)
                .putExtra("request",request.requestId).putExtra("proposal",request.proposalId)
                .setData(Uri.Builder().scheme("cypher-call").authority(packageName).appendPath(state.callId).appendPath(request.requestId).appendPath(request.proposalId).appendPath(action).build())
            builder.addAction(NotificationCompat.Action.Builder(0,if(approved) "Allow once" else "Deny",PendingIntent.getService(this,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).setAuthenticationRequired(true).build())
        }
        // A shortened command must be reviewed in full before it can be approved.
        if(details.length<=640) answer(true) else builder.addAction(0,"Review request",open)
        answer(false)
        try { manager.notify(TOOL_NOTIFICATION,builder.build()) } catch (_: SecurityException) { }
    }
    override fun onDestroy() {
        hangup()
        getSystemService(NotificationManager::class.java).cancel(TOOL_NOTIFICATION)
        super.onDestroy()
        platformDestroyed.complete(Unit)
    }
    companion object { const val EXTRA_CALL_ID = "hermes.call.ID"; const val START = "hermes.call.START"; const val HANGUP = "hermes.call.HANGUP"; const val MUTE = "hermes.call.MUTE"; const val STOP_SCREEN = "hermes.call.STOP_SCREEN"; const val TOOL_ALLOW="hermes.call.TOOL_ALLOW"; const val TOOL_DENY="hermes.call.TOOL_DENY"; const val TOOL_NOTIFICATION=43; const val CHANNEL = "xara-calls"; const val NOTIFICATION = 41 }
}


