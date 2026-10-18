package dev.hermes.android

import android.app.Application
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallTranscript
import dev.hermes.android.voice.transcriptsFor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Message(val id: String, val role: String, val text: String, val reasoning: String = "", val tools: List<JSONObject> = emptyList(), val attachments: List<JSONObject> = emptyList(), val mediaSnapshots: Map<String, String> = emptyMap(), val sessionId: String = "", val interrupted: Boolean = false, val voice: Boolean = false, val recordedAt: Long = 0L) {
    companion object {
        fun from(o: JSONObject, index: Int, sessionId: String = ""): Message {
            val content = when { o.has("display_content") && !o.isNull("display_content") -> o.opt("display_content"); o.has("display_text") && !o.isNull("display_text") -> o.opt("display_text"); o.has("text") && !o.isNull("text") -> o.opt("text"); else -> o.opt("content") }
            val parts = (content as? JSONArray)?.objects().orEmpty()
            val text = messageContentText(o.string("role", "assistant"), content)
            val snapshots = o.optJSONObject("_media_snapshots")?.let { values -> values.keys().asSequence().take(256).mapNotNull { path -> values.optString(path).takeIf(::validMediaSnapshot)?.takeIf { path.length in 1..8192 && path.none { it.code < 32 || it.code == 127 } }?.let { path to it.lowercase() } }.toMap() }.orEmpty()
            return Message(o.string("id", o.string("message_id", o.string("row_id", o.string("_row_id", o.string("tool_call_id", "${o.string("role")}:$index"))))), o.string("role", "assistant"), text, o.string("reasoning", o.string("reasoning_content")), o.rows("tool_calls"), o.rows("attachments") + parts.filter { it.string("type").contains("image") }, snapshots, sessionId, o.optBoolean("interrupted") || o.optJSONObject("display_metadata")?.optBoolean("interrupted") == true || o.string("finish_reason") == "cancelled", o.optJSONObject("display_metadata")?.optString("channel") == "voice", recordedAt = recordedMessageTime(o.opt("timestamp")))
        }
    }
}
data class ChatState(val sessionId: String = "", val title: String = "New conversation", val messages: List<Message> = emptyList(), val raw: JSONObject = JSONObject(), val streamId: String = "", val liveText: String = "", val liveReasoning: String = "", val liveTools: List<JSONObject> = emptyList(), val status: String = "", val approval: JSONObject? = null, val clarification: JSONObject? = null, val uncertain: String = "", val queued: List<QueuedMessage> = emptyList(), val anchor: Int = 0, val anchorOffset: Int = 0, val anchorId: String = "", val callMessages: List<CallTranscript> = emptyList(), val projectionReady: Boolean = true)
internal fun sessionCanStillBeUnpersisted(clientCreated: Boolean, chat: ChatState): Boolean =
    clientCreated && chat.messages.isEmpty() && chat.callMessages.isEmpty() && chat.liveText.isBlank() && chat.streamId.isBlank() && chat.raw.optInt("message_count") == 0
internal fun releaseTerminalChat(chat: ChatState, stream: String, replyConfirmed: Boolean, terminalStatus: String): ChatState {
    if (chat.streamId != stream) return chat
    return if (replyConfirmed) chat.copy(streamId = "", liveText = "", liveReasoning = "", liveTools = emptyList(), status = terminalStatus)
    else chat.copy(streamId = "", liveTools = emptyList())
}
data class EditorState(val scope: Scope? = null, val path: String = "", val text: TextFieldValue = TextFieldValue(), val serverText: String = "", val metadata: JSONObject = JSONObject(), val loading: Boolean = false, val saving: Boolean = false, val error: String = "", val reviewRequired: Boolean = false) { val dirty get() = text.text != serverText }
data class SideQuestion(val scope: Scope, val question: String, val sessionId: String, val streamId: String, val text: String = "", val status: String = "Responding", val cursor: String = "", val error: String = "")
data class AppUpdateState(val release: Release? = null, val busy: Boolean = false, val ready: Boolean = false, val status: String = "Cypher checks for updates automatically while connected.")
data class AppState(val origin: String = BuildConfig.DEFAULT_ORIGIN, val authenticated: Boolean = false, val profile: String = "default", val owner: String = "", val tab: String = "Chat", val connected: Boolean = false, val loading: Boolean = false, val error: String = "", val notice: String = "", val bootstrap: JSONObject = JSONObject(), val sessions: List<JSONObject> = emptyList(), val query: String = "", val showSessions: Boolean = false, val morePage: String = "", val xaraOpening: Boolean = false, val xaraError: String = "", val newChatOpening: Boolean = false, val newChatError: String = "", val initialChatReady: Boolean = false, val restoringSession: Boolean = true, val botModeEnabled: Boolean = false, val botModeSwitching: Boolean = false, val setupWizard: Boolean = false, val setupComplete: Boolean = false, val setupStep: String = "connect", val setupPluginMessage: String = "", val setupOrigin: String = "")
internal fun voiceKeySetupPageIfMissing(hasKey:Boolean):String? = if(hasKey) null else "Cypher"
internal data class VoiceCallRequest(val scope:Scope,val conversationId:String)

internal enum class NativeSetupAuthMode { Loopback, NativePkce, Unsupported }
internal fun nativeSetupAuthMode(status: JSONObject): NativeSetupAuthMode = when (status.opt("auth_required")) {
    false -> NativeSetupAuthMode.Loopback
    true -> if ((status.optJSONArray("auth_flows") ?: JSONArray()).let { flows -> (0 until flows.length()).any { flows.optString(it) == "native_pkce" } }) NativeSetupAuthMode.NativePkce else NativeSetupAuthMode.Unsupported
    else -> NativeSetupAuthMode.Unsupported
}

data class VoiceHandoff(val scope:Scope,val media:String,val targetCall:String="")
private data class PendingVisionSwitch(val scope: Scope, val callId: String, val media: String)

class CypherViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val _voiceHandoff=MutableStateFlow<VoiceHandoff?>(null)
    val voiceHandoff=_voiceHandoff.asStateFlow()
    private var voiceHandoffJob:Job?=null
    fun switchCallForVision(callId:String,media:String) {
        require(media in setOf("camera","screen"))
        if(voiceHandoffJob?.isActive==true) return
        val current=app.calls.state.value
        val owner=current.conversationScope ?: return
        if(owner!=authenticatedScope()||current.callId!=callId) return
        _voiceHandoff.value=VoiceHandoff(owner,media)
        voiceHandoffJob=viewModelScope.launch {
            try {
                val target=app.calls.switchToGemini(callId)
                if(authenticatedScope()==owner) _voiceHandoff.value=VoiceHandoff(owner,media,target)
                else _voiceHandoff.value=null
            } catch(error:Throwable) {
                _voiceHandoff.value=null
                if(error is CancellationException) throw error
                if (error is MissingVoiceKeyException) {
                    pendingVisionSwitchAfterKey = PendingVisionSwitch(owner, callId, media)
                    more(voiceKeySetupPageIfMissing(false) ?: "Cypher")
                    notice("Add a Gemini key in Voice & calls to continue this call's screen sharing.")
                } else notice(error.message ?: "Could not switch to Gemini. Start a new call to try again.")
            }
        }
    }
    fun consumeVisionHandoff(callId:String):String? {
        val pending=_voiceHandoff.value ?: return null
        if(pending.targetCall!=callId||pending.scope!=authenticatedScope()) return null
        _voiceHandoff.value=null
        return pending.media
    }
    fun cancelVoiceHandoff() { voiceHandoffJob?.cancel();_voiceHandoff.value=null;app.calls.hangup() }
    private val _incomingBusy = MutableStateFlow(false)
    val incomingBusy = _incomingBusy.asStateFlow()
    val app = application as CypherApplication
    val phoneSettings = NativePhoneSettings(viewModelScope, saved, draftChanged = ::checkpointPhoneSettings, cache = app.cache) { client }
    val botMode = NativeBotModeController(viewModelScope, saved, openChat = ::openBotChat) { client }
    val preferences = app.preferences.data.map(AppPreferences::read).stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())
    private val _ui = MutableStateFlow(AppState(tab = saved["tab"] ?: "Chat", morePage = saved["more"] ?: ""))
    private var normalModeReturn: JSONObject? = null
    private var modeTransition: Job? = null
    val ui = _ui.asStateFlow()
    private val _voiceCallRequest = MutableStateFlow<VoiceCallRequest?>(null)
    internal val voiceCallRequest = _voiceCallRequest.asStateFlow()
    private var pendingVoiceCallAfterKey:VoiceCallRequest?=null
    private var pendingIncomingAfterKey:Triple<String,String,String>?=null
    private var pendingVisionSwitchAfterKey: PendingVisionSwitch? = null
    private val _appUpdate = MutableStateFlow(AppUpdateState())
    val appUpdate = _appUpdate.asStateFlow()
    private var updateJob: Job? = null
    private var installJob: Job? = null
    private var waitingInstallPermission = false
    private val _chat = MutableStateFlow(ChatState())
    val chat = _chat.asStateFlow()
    private val _composer = MutableStateFlow(TextFieldValue())
    val composer = _composer.asStateFlow()
    private val _attachments = MutableStateFlow<List<JSONObject>>(emptyList())
    val attachments = _attachments.asStateFlow()
    private val _attachmentPreparation = MutableStateFlow<List<JSONObject>>(emptyList())
    val attachmentPreparation = _attachmentPreparation.asStateFlow()
    private val uploadSlots = kotlinx.coroutines.sync.Semaphore(2)
    private val uploadJobs = mutableMapOf<String, Job>()
    private val _data = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
    val data = _data.asStateFlow()
    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshing = _refreshing.asStateFlow()
    private val _reasoningSaving = MutableStateFlow(false)
    val reasoningSaving = _reasoningSaving.asStateFlow()
    private var reasoningChange: Job? = null
    private var phoneCheckpoint: Job? = null
    private val phoneCacheLock = Mutex()
    private val editorCacheLock = Mutex()
    private val _form = MutableStateFlow<dev.hermes.android.ui.ActionForm?>(null)
    val form = _form.asStateFlow()
    private val _submittingForm = MutableStateFlow("")
    val submittingForm = _submittingForm.asStateFlow()
    private var editorReadGeneration = 0L
    private val _editor = MutableStateFlow(EditorState())
    val editor = _editor.asStateFlow()
    private val _side = MutableStateFlow<SideQuestion?>(null)
    val side = _side.asStateFlow()
    private val _catalog = MutableStateFlow("")
    val catalog = _catalog.asStateFlow()
    private var sideJob: Job? = null
    private var sideCheckpointJob: Job? = null
    private val liveFrames = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private var editorCheckpoint: Job? = null
    @Volatile var client: CypherClient? = null
        private set(value) {
            if (value == null || field?.auth?.let { it.origin != value.auth.origin || it.owner != value.auth.owner } == true) botMode.clear()
            if (value != null && field?.auth?.let { it.origin != value.auth.origin || it.owner != value.auth.owner } == true) {
                normalModeReturn = null
                _ui.update { it.copy(botModeEnabled = false, botModeSwitching = false) }
            }
            field=value; app.calls.ownerScope=value?.scope
        }
    @Volatile private var sourceScope: Scope? = null
    private var cachedOwnerScope: Scope? = null
    private var checkpointJob: Job? = null
    private var selectionJob: Job? = null
    private var startupJob: Job? = null
    private var startupIndex: Job? = null
    private var startupSettings: Job? = null
    private var optionalProbe: Job? = null
    private var preparedXara: Pair<Scope, String>? = null
    private val xaraResolveLock = Mutex()
    private var recoveryJob: Job? = null
    private var sessionWatch: Job? = null
    private var connectionProbe: Job? = null
    private var renderedServerProjection: Pair<Scope, String>? = null
    private var callReconcile: Job? = null
    private val streams = mutableMapOf<String, Job>()
    private val drafts = mutableMapOf<String, Draft>()
    private val persistedDrafts = mutableMapOf<String, String>()
    private val initialShells = mutableMapOf<String, String>()
    private val clientCreatedSessions = mutableSetOf<String>()
    private val initialCreateAttempts = mutableMapOf<String, String>()
    private val newChatJobs = mutableMapOf<String, Job>()
    private val initialCreateError = "Conversation opening failed or was not confirmed. Your draft is safe. Retry to open an empty conversation."
    private val pending = mutableMapOf<String, PendingSend>()
    private val loads = mutableMapOf<String, Job>()
    private val loadRequests = mutableMapOf<String, String>()
    private val loadedAt = mutableMapOf<String, Long>()
    private val readInvalidations = mutableMapOf<String, Long>()
    private val preloadJobs = mutableSetOf<Job>()
    private val confirmedSessions = mutableMapOf<String, JSONObject>()
    private var sessionIndexRequest = 0L
    private var sessionIndexRevision = 0L
    private var sessionIndexArchived = false
    private val liveBoundaries = java.util.concurrent.ConcurrentHashMap<String, LiveAssistantBoundary>()
    private var chatHistoryRevision = 0L
    @Volatile private var originGeneration = 0
    @Volatile private var sessionGeneration = 0
    private var navigationGeneration = 0
    private var xaraGeneration = 0
    private var speech: android.speech.tts.TextToSpeech? = null
    private var speechReady = false
    private var speechPending: (() -> Unit)? = null
    private var readSequence = 0L
    private val _readingAloud = MutableStateFlow(false)
    val readingAloud = _readingAloud.asStateFlow()
    private var pasteJob: Job? = null
    @Volatile private var foreground = true
    private var setupProbeJob: Job? = null
    private var setupProbeGeneration = 0L
    private var pendingSetupLink: dev.hermes.android.ui.CypherConnectInput? = null

    init {
        viewModelScope.launch {
            var previousFinals = emptyList<CallTranscript>()
            var wasActive = false
            app.calls.state.collect { call ->
                projectCallTranscripts(call)
                val finals = call.transcripts.filter { it.final && it.text.isNotBlank() }
                if (finals != previousFinals && finals.isNotEmpty() || wasActive && !call.active) reconcileCallConversation(call)
                previousFinals = finals; wasActive = call.active
            }
        }
        viewModelScope.launch {
            val generation = originGeneration
            try {
                val prefs = app.preferences.data.first()
                val origin = prefs[stringPreferencesKey("origin")] ?: BuildConfig.DEFAULT_ORIGIN
                if (generation != originGeneration) return@launch
                val auth = if (origin.isBlank()) AuthContext(origin, emptyList()) else withContext(Dispatchers.IO) { app.auth.read(origin) }
                if (generation != originGeneration) return@launch
                val cached = origin.isNotBlank() && auth.cachedScopeFor(origin, replacingAccount = false) != null
                val complete = prefs[booleanPreferencesKey("setup_complete")] == true
                val activeSetup = prefs[booleanPreferencesKey("setup_active")] == true
                val setupOriginKey = stringPreferencesKey("setup_origin")
                val storedSetupOrigin = prefs[setupOriginKey].orEmpty()
                val setupOrigin = runCatching { dev.hermes.android.ui.parseCypherConnectInput(storedSetupOrigin) }.getOrDefault("")
                if (setupOrigin != storedSetupOrigin) app.preferences.edit { values ->
                    if (values[setupOriginKey] == storedSetupOrigin) {
                        if (setupOrigin.isBlank()) values.remove(setupOriginKey) else values[setupOriginKey] = setupOrigin
                    }
                }
                if (generation != originGeneration) return@launch
                val knownSteps = setOf("connect", "probe", "signin", "unsupported", "voice-provider", "permission-microphone", "permission-notifications", "permission-full-screen", "done")
                val savedStep = prefs[stringPreferencesKey("setup_step")].orEmpty().takeIf { it in knownSteps } ?: if (complete) "done" else "connect"
                val step = when {
                    origin.isBlank() -> "connect"
                    !cached && (complete || savedStep.startsWith("permission-") || savedStep == "done") -> "signin"
                    cached && !complete && !activeSetup && savedStep == "connect" -> firstIncompleteSetupPermission()
                    savedStep == "done" && !complete && !activeSetup -> "connect"
                    else -> savedStep
                }
                _ui.update { it.copy(origin = origin, setupOrigin = setupOrigin, setupWizard = activeSetup || !complete || !cached, setupComplete = complete, setupStep = step) }
                if (cached) connect(origin) else _ui.update { it.copy(restoringSession = false, setupWizard = true) }
            } catch (e: Throwable) {
                if (generation == originGeneration) { _ui.update { it.copy(restoringSession = false) }; fail(e) }
            }
        }
    }
    fun clearMessage() { _ui.update { it.copy(error = "", notice = "") } }
    private suspend fun optionalLocalSave(block: suspend () -> Unit) {
        try { block() } catch (e: Exception) { if (e is CancellationException) throw e; notice("Local recovery checkpoint failed. The displayed result remains current; refresh before leaving this conversation.") }
    }
    private val scheduleDraftLock = Mutex()
    private fun scheduleDraftKey(form: dev.hermes.android.ui.ActionForm) = "schedule-form:${form.path}:${form.seed.string("job_id", "new")}"
    fun openForm(form: dev.hermes.android.ui.ActionForm) {
        _form.value = form.copy(values = dev.hermes.android.ui.formValues(form))
        if (form.path !in setOf("/api/crons/create", "/api/crons/update")) return
        val owner = client?.scope ?: return
        viewModelScope.launch { try { scheduleDraftLock.withLock {
            val draft = app.cache.read(owner, scheduleDraftKey(form))?.let(::JSONObject) ?: return@withLock
            if (client?.scope == owner && _form.value?.id == form.id && _form.value?.values == dev.hermes.android.ui.formValues(form)) _form.update { it?.copy(values = it.values + draft.keys().asSequence().filter { key -> form.fields.any { field -> field.key == key } }.associateWith { key -> draft.optString(key) }) }
        } } catch (e: Exception) { if (e is CancellationException) throw e; if (client?.scope == owner) fail(e) } }
    }
    private fun saveScheduleDraft(form: dev.hermes.android.ui.ActionForm) {
        if (form.path !in setOf("/api/crons/create", "/api/crons/update")) return
        val owner = client?.scope ?: return
        val values = JSONObject(form.values)
        viewModelScope.launch { try { scheduleDraftLock.withLock { app.cache.write(owner, scheduleDraftKey(form), values.toString(), true) } } catch (e: Exception) { if (e is CancellationException) throw e; if (client?.scope == owner) fail(e) } }
    }
    fun dismissForm() { if (_submittingForm.value != _form.value?.id) _form.value = null }
    fun discardScheduleDraft() = viewModelScope.launch {
        val form = _form.value ?: return@launch
        val owner = client?.scope ?: return@launch
        if (_submittingForm.value.isNotBlank() || form.path !in setOf("/api/crons/create", "/api/crons/update")) return@launch
        _submittingForm.value = form.id
        try {
            scheduleDraftLock.withLock { app.cache.delete(owner, scheduleDraftKey(form)) }
            if (client?.scope == owner && _form.value?.id == form.id) _form.value = null
        } catch (e: Exception) { if (e is CancellationException) throw e; if (client?.scope == owner) fail(e) }
        finally { if (_submittingForm.value == form.id) _submittingForm.value = "" }
    }
    fun formValue(key: String, value: String) { _form.update { if (it?.id == _submittingForm.value) it else it?.copy(values = it.values + (key to value), error = "") }; _form.value?.let(::saveScheduleDraft) }
    fun submitForm() {
        val form = _form.value ?: return
        if (_submittingForm.value.isNotBlank()) return
        try {
            require(form.path.isEmpty() || client != null) { "Reconnect before saving changes." }
            val body = dev.hermes.android.ui.formBody(form)
            val modelUpdate = form.path == "/api/session/update" && body.has("model")
            val selected = sourceScope
            val revision = chatHistoryRevision
            if (form.path == "/api/projects/assign") require(selected != null && body.string("session_id") == selected.session && body.string("scope_key") == selected.key) { "Reopen Projects in this conversation." }
            if (modelUpdate) require(selected != null && selected.copy(session = "") == client?.scope && body.string("session_id") == selected.session && body.string("scope_key", selected.key) == selected.key) { "Reopen the model picker in this conversation." }
            _submittingForm.value = form.id
            val job = if (form.path.isEmpty()) viewModelScope.launch { try { form.onResult?.invoke(body); if (_form.value?.id == form.id) _form.value = null } catch (e: Throwable) { _form.update { if (it?.id == form.id) it.copy(error = e.message ?: "Could not save. Try again.") else it }; fail(e) } }
            else action(form.path, body, form.method, refresh = form.path == "/api/projects/assign", successNotice = when (form.path) {
                "/api/projects/create" -> "Project created"; "/api/projects/update" -> "Project updated"; "/api/projects/delete" -> "Project removed"
                "/api/projects/assign" -> "Chat moved to project"; "/api/projects/folders/remove" -> "Folder unlinked"; "/api/pc/file/create" -> "File created"; else -> "Saved"
            }) { c, result ->
                if (modelUpdate && selected != null) {
                    val committed = result.optJSONObject("session")
                    require(committed?.string("session_id") == selected.session) { "The saved model did not resolve to this conversation. Refresh before sending." }
                    if (revision == chatHistoryRevision) renderSession(result, selected, revision)
                    else _chat.update { state -> state.copy(raw = state.raw.copy().apply { listOf("model", "model_provider").forEach { key -> if (committed.has(key)) put(key, committed.opt(key)) } }) }
                    saved["model_pick:${selected.key}"] = true
                }
                form.onResult?.invoke(result)
                if (form.path in setOf("/api/crons/create", "/api/crons/update")) optionalLocalSave { scheduleDraftLock.withLock { app.cache.delete(c.scope, scheduleDraftKey(form)) } }
                if (_form.value?.id == form.id) _form.value = null
            }
            job.invokeOnCompletion { if (_submittingForm.value == form.id) _submittingForm.value = "" }
        } catch (e: Throwable) { _submittingForm.value = ""; _form.update { if (it?.id == form.id) it.copy(error = e.message ?: "Check the fields and try again.") else it }; fail(e) }
    }
    fun notice(value: String) { _ui.update { it.copy(error = if (value.isNotBlank()) "" else it.error, notice = value) } }
    fun prepareVoiceCall(active:CypherClient,conversationId:String):Boolean {
        val keyStore=VoiceKeyStore(app)
        if(keyStore.hasKey(keyStore.settings().provider)) return true
        openVoiceKeySettingsForCall(active,conversationId)
        return false
    }
    fun openVoiceKeySettingsForCall(active:CypherClient,conversationId:String) {
        val page=voiceKeySetupPageIfMissing(false)?:return
        pendingVoiceCallAfterKey=VoiceCallRequest(active.scope,conversationId)
        more(page)
    }
    internal fun consumeVoiceCallRequest(request:VoiceCallRequest) {
        if(_voiceCallRequest.value==request) _voiceCallRequest.value=null
    }
    fun voiceKeyConfigured() {
        viewModelScope.launch {
            val vision = pendingVisionSwitchAfterKey
            if (vision != null) {
                if (withContext(Dispatchers.IO) { VoiceKeyStore(app).session("gemini") == null }) {
                    notice("Save a Gemini key in Voice & calls to continue this call's screen sharing.")
                    return@launch
                }
                pendingVisionSwitchAfterKey = null
                val call = app.calls.state.value
                if (!call.active || call.callId != vision.callId || call.voiceProvider != "grok" || call.conversationScope != vision.scope || authenticatedScope() != vision.scope) {
                    backFromSettings()
                    notice("The call changed before Gemini was ready. Start screen sharing again.")
                    return@launch
                }
                backFromSettings()
                switchCallForVision(vision.callId, vision.media)
                return@launch
            }
            if(withContext(Dispatchers.IO) { VoiceKeyStore(app).session()==null }) return@launch
            pendingIncomingAfterKey?.let { (source,event,action) ->
                pendingIncomingAfterKey=null
                backFromSettings()
                handlePush(source,event,action)
                return@launch
            }
            val pending=pendingVoiceCallAfterKey?:return@launch
            pendingVoiceCallAfterKey=null
            if(client?.scope!=pending.scope) { notice("The conversation changed. Start the call again.");return@launch }
            backFromSettings()
            _voiceCallRequest.value=pending
        }
    }
    private val checklistWrites = Mutex()
    fun saveChecklistReading(scope: Scope, documentKey: String, values: Map<String, Boolean>) = viewModelScope.launch {
        try { checklistWrites.withLock { app.cache.write(scope, "reading-checklist:$documentKey", JSONObject(values).toString()) } }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            if (client?.scope == scope.copy(session = "")) notice("Checklist selection could not be saved on this phone.")
        }
    }
    private val nativePreferencesLock = Mutex()
    fun nativePreferences(values: JSONObject) = viewModelScope.launch {
        val owner = client?.scope ?: cachedOwnerScope ?: return@launch
        try { nativePreferencesLock.withLock {
        val chat = app.cache.read(owner, "chat-preferences")?.let(::JSONObject) ?: json("render_user_markdown" to true, "send_key" to "enter")
        for (key in setOf("render_user_markdown", "show_thinking", "send_key", "auto_scroll_follow", "animate_background")) if (values.has(key)) {
            if (key != "send_key") chat.put(key, values.getBoolean(key))
            else { require(values.getString(key) in setOf("enter", "ctrl_enter")) { "Choose Enter or Ctrl+Enter" }; chat.put(key, values.getString(key)) }
        }
        app.cache.write(owner, "chat-preferences", chat.toString(), true)
        if ((client?.scope ?: cachedOwnerScope) == owner) storeData("profile-settings", chat)
        app.preferences.edit { prefs -> values.keys().forEach { key -> if (key in setOf("theme", "skin", "font_size", "language", "rtl", "reduced_motion")) prefs[stringPreferencesKey(key)] = values.opt(key)?.toString().orEmpty() } }
        } } catch (e: Exception) { if (e is CancellationException) throw e; if ((client?.scope ?: cachedOwnerScope) == owner) fail(e) }
    }
    fun loadSlashCatalog(force: Boolean = false) = load("slash:catalog", "/api/commands/catalog", force = force)
    fun runSlashCommand(name: String, argument: String = "") {
        if (!admitConversation()) return
        if (_chat.value.raw.length() == 0 || _chat.value.streamId.isNotBlank() || _chat.value.uncertain.isNotBlank() || _chat.value.approval != null || _chat.value.clarification != null || app.calls.state.value.active || _attachments.value.isNotEmpty() || _chat.value.raw.optBoolean("read_only") || _chat.value.raw.optBoolean("is_read_only")) { notice("Finish the current turn and attachments before running a command"); return }
        action("/api/commands/exec", json("session_id" to _chat.value.sessionId, "name" to name.removePrefix("/"), "argument" to argument), refresh = false) { _, result ->
            val type = result.string("type")
            if (type in setOf("send", "skill", "prefill", "alias")) notice(result.string("display", result.string("notice", "This command requires the native desktop composer")))
            else { notice(result.string("output", result.string("notice", "Command completed"))); val command = "/${name.removePrefix("/")}" + argument.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty(); if (_composer.value.text == command) composer(TextFieldValue()) }
        }
    }
    fun storeData(key: String, value: JSONObject) { _data.update { it + (key to value) } }
    fun setKeepConnectedEnabled(enabled: Boolean) {
        runCatching { dev.hermes.android.push.KeepConnectedService.setEnabled(app, client?.auth, enabled) }
            .onFailure { notice(it.message ?: "Could not change Keep connected") }
    }
    private fun savedNativeSessionValid(): Boolean = client?.auth?.let { auth ->
        if (auth.bearer == null) true else app.auth.read(auth.origin).let { it.owner == auth.owner && it.bearer != null }
    } ?: true
    fun foreground(active: Boolean) {
        val changed = foreground != active
        foreground = active
        if (active && _ui.value.authenticated && !savedNativeSessionValid()) {
            fail(NativeAuthExpiredException())
            return
        }
        if (!changed) return
        if (active) resumeAppUpdate()
        if (!active) { cancelStartup(); recoveryJob?.cancel(); sessionWatch?.cancel(); sideJob?.cancel(); streams.values.toList().forEach(Job::cancel); viewModelScope.launch { runCatching { checkpointVisible() }.onFailure(::fail) } }
        else if (_ui.value.authenticated && client == null && !_ui.value.loading) connect(_ui.value.origin)
        else if (_ui.value.authenticated) { prepareStartup(); recoverForeground(); checkForAppUpdate(quiet = true) }
    }
    fun checkForAppUpdate(quiet: Boolean = false) {
        val captured = client?.detached() ?: run { if (!quiet) _appUpdate.update { it.copy(status = "Reconnect to Tailscale to check for updates.") }; return }
        val generation = originGeneration
        if (updateJob?.isActive == true || installJob?.isActive == true) return
        val installation = installJob
        updateJob = viewModelScope.launch {
            if (!quiet) _appUpdate.update { it.copy(busy = true, status = "Checking for an update…") }
            try {
                val release = app.updates.check(captured, force = !quiet, notify = true)
                if (generation != originGeneration || client?.scope != captured.scope) return@launch
                if (quiet && installJob !== installation) return@launch
                if (!quiet || release != null) _appUpdate.value = AppUpdateState(release = release, ready = app.updates.hasPendingInstall(), status = release?.let { "Cypher ${it.name} is ready for you." } ?: "You're up to date.")
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (!quiet && generation == originGeneration && client?.scope == captured.scope) _appUpdate.update { it.copy(status = "Couldn’t check for updates. ${e.message.orEmpty()}") }
            } finally { if (generation == originGeneration && client?.scope == captured.scope) _appUpdate.update { it.copy(busy = installJob?.isActive == true) } }
        }
    }
    private fun updateCallBlock(): String? = when {
        _incomingBusy.value || app.calls.state.value.active -> "End your call before updating Cypher."
        app.calls.state.value.state == "ending" || app.calls.service != null || app.calls.spec != null -> "Call cleanup is still finishing. Try the update again in a moment."
        else -> null
    }
    fun downloadAppUpdate(notificationToken: String? = null) {
        updateCallBlock()?.let { message -> _appUpdate.update { it.copy(status = message) }; return }
        val captured = client?.detached() ?: run { _appUpdate.update { it.copy(status = "Reconnect to Cypher through Tailscale, then tap Update.") }; return }
        val generation = originGeneration
        val previous = updateJob
        if (notificationToken == null && (_appUpdate.value.busy || installJob?.isActive == true)) return
        if (notificationToken != null) previous?.cancel()
        if (notificationToken == null && app.updates.hasPendingInstall()) { installAppUpdate(); return }
        if (notificationToken != null) { tab("More"); more("App updates") }
        updateJob = viewModelScope.launch {
            previous?.join()
            if (generation != originGeneration || client?.scope != captured.scope) return@launch
            _appUpdate.update { it.copy(busy = true, ready = false, status = "Downloading and checking your update…") }
            try {
                val file = if (notificationToken != null) app.updates.downloadNotification(notificationToken) else {
                    val available = _appUpdate.value.release ?: app.updates.check(captured, force = true, notify = false)
                    if (generation != originGeneration || client?.scope != captured.scope) return@launch
                    _appUpdate.update { it.copy(release = available) }
                    available?.let { app.updates.download(captured, it) }
                }
                if (generation != originGeneration || client?.scope != captured.scope) return@launch
                _appUpdate.update { it.copy(ready = file != null, status = if (file != null) "Ready to install. Your conversations and settings stay with you." else "You're up to date.") }
                if (file != null && foreground) installAppUpdate()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (generation == originGeneration && client?.scope == captured.scope) _appUpdate.update { it.copy(release = null, ready = false, status = "Update paused. ${e.message.orEmpty()} Tap Update to try again.") }
            } finally { if (generation == originGeneration && client?.scope == captured.scope) _appUpdate.update { it.copy(busy = installJob?.isActive == true) } }
        }
    }
    fun installAppUpdate() {
        updateCallBlock()?.let { message -> _appUpdate.update { it.copy(status = message) }; return }
        if (installJob?.isActive == true) return
        installJob = viewModelScope.launch {
        val captured = client?.scope ?: cachedOwnerScope ?: return@launch
        val generation = originGeneration
        _appUpdate.update { it.copy(busy = true) }
        runCatching { app.updates.installPending() }
            .onSuccess { launched -> if (generation == originGeneration && (client?.scope ?: cachedOwnerScope) == captured) { waitingInstallPermission = !launched; _appUpdate.update { it.copy(ready = true, status = if (launched) "Confirm Update in Android to finish." else "Allow updates from Cypher, then return here to finish.") } } }
            .onFailure { if (it is CancellationException) throw it; if (generation == originGeneration && (client?.scope ?: cachedOwnerScope) == captured) _appUpdate.update { state -> state.copy(ready = false, status = it.message ?: "Check for the update again.") } }
        if (generation == originGeneration && (client?.scope ?: cachedOwnerScope) == captured) _appUpdate.update { it.copy(busy = false) }
        }
    }
    fun resumeAppUpdate() {
        if (_ui.value.authenticated && !savedNativeSessionValid()) {
            fail(NativeAuthExpiredException())
            return
        }
        if (app.updates.shouldContinueInstall() || waitingInstallPermission || app.updates.awaitingInstallPermission()) {
            val awaitingPermission = waitingInstallPermission || app.updates.awaitingInstallPermission()
            waitingInstallPermission = false
            if ((!awaitingPermission || app.packageManager.canRequestPackageInstalls()) && app.updates.hasPendingInstall()) installAppUpdate()
        }
    }
    private fun resetAppUpdate() { updateJob?.cancel(); installJob?.cancel(); updateJob = null; installJob = null; waitingInstallPermission = false; _appUpdate.value = AppUpdateState() }
    private fun restoreAppUpdate() { if (app.updates.hasPendingInstall()) { _appUpdate.update { it.copy(ready = true, status = "Your verified update is ready to install.") }; if (foreground) resumeAppUpdate() } }
    private fun ScopeSettlement.current() = current(sourceScope, client, originGeneration, sessionGeneration)
    private fun recoverForeground() {
        recoveryJob?.cancel()
        recoveryJob = viewModelScope.launch {
            selectionJob?.join()
            if (!foreground) return@launch
            val active = client ?: return@launch
            val scope = sourceScope ?: return@launch
            val request = ScopeSettlement(scope, active, originGeneration, sessionGeneration)
            val c = active.detached()
            try {
                val response = c.get("/api/session", mapOf("session_id" to scope.session))
                if (!request.current() || !foreground) return@launch
                renderSession(response, scope)
                app.cache.write(scope, "session", response.toString())
                if (!request.current() || !foreground) return@launch
                reconcile(scope, c)
                if (!request.current() || !foreground) return@launch
                val stream = _chat.value.streamId
                if (stream.isNotBlank() && streams[scope.key]?.isActive != true) attachStream(c, scope, stream)
                watchSession(c, scope)
                _side.value?.takeIf { it.scope == scope && it.status !in setOf("Complete", "Stopped") }?.let { resumeSideQuestion() }
            } catch (e: Throwable) { if (request.current() && foreground) fail(e) }
        }
    }
    private fun checkpointPhoneSettings() {
        val owner = client?.scope ?: return
        val snapshot = runCatching { phoneSettings.snapshotDraft() }.getOrElse { notice("This settings draft is too large to keep on the device. Shorten it before leaving."); return }
        if (snapshot.string("scope") != owner.key) return
        phoneCheckpoint?.cancel()
        phoneCheckpoint = viewModelScope.launch {
            try { delay(200); retainPhoneSettings(owner, snapshot) }
            catch (e: Throwable) { if (client?.scope == owner) fail(e) }
        }
    }
    private suspend fun retainPhoneSettings(owner: Scope, snapshot: JSONObject) = phoneCacheLock.withLock {
        if (snapshot.string("scope") != owner.key) return@withLock
        val hasDraft = listOf("skill", "memory", "bot").any(snapshot::has)
        if (hasDraft) app.cache.write(owner, "phone-editors", snapshot.toString(), protected = true)
        else app.cache.delete(owner, "phone-editors")
    }
    private suspend fun checkpointPhoneSettingsNow() {
        val owner = client?.scope ?: return
        phoneCheckpoint?.cancelAndJoin()
        retainPhoneSettings(owner, phoneSettings.snapshotDraft())
    }
    internal suspend fun restorePhoneSettings(active: CypherClient) {
        phoneSettings.bind()
        try {
            val snapshot = app.cache.read(active.scope, "phone-editors")
            if (client === active && snapshot != null) phoneSettings.restoreDraft(JSONObject(snapshot))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (client === active) notice("A saved settings draft could not be restored. The stored copy is retained.")
        }
    }
    private suspend fun checkpointVisible() { checkpointNow(); checkpointEditorNow(); checkpointPhoneSettingsNow(); retainSideQuestion() }
    private fun retireSavedDrafts(active: Scope? = draftScope()) = pruneSavedDrafts(drafts, persistedDrafts, active?.key)
    private fun savedDraft(scope: Scope, encoded: String) { persistedDrafts[scope.key] = encoded; retireSavedDrafts() }
    private fun cancelNewChatWork() { newChatJobs.values.toList().forEach(Job::cancel); _ui.update { it.copy(newChatOpening = false, newChatError = if (sourceScope == null && initialCreateAttempts.containsKey((client?.scope ?: cachedOwnerScope)?.key)) initialCreateError else it.newChatError) } }
    private fun clearDetails() { loadedAt.clear(); recoveryJob?.cancel(); stopReadAloud(); pasteJob?.cancel(); uploadJobs.values.toList().forEach(Job::cancel); uploadJobs.clear(); _attachmentPreparation.value = emptyList(); sideCheckpointJob?.cancel(); _editor.value = EditorState(); _form.value = null; _side.value = null; _catalog.value = ""; sideJob?.cancel() }
    fun openCatalog(kind: String) { if (!admitConversation()) return; _catalog.value = kind; when (kind) { "Model & provider" -> load("picker:models", "/api/models"); "Toolsets" -> load("picker:toolsets", "/api/tools/toolsets"); "Reasoning" -> { _data.update { it - "picker:reasoning" }; load("picker:reasoning", if (isCypherConversation()) "/api/mobile/cypher/reasoning" else "/api/reasoning", mapOf("session_id" to _chat.value.sessionId, "model" to _chat.value.raw.string("model"), "provider" to _chat.value.raw.string("model_provider"))) } } }
    fun setReasoning(effort: String) {
        if (!admitConversation() || _reasoningSaving.value) return
        _reasoningSaving.value = true
        val job = action("/api/reasoning", json("session_id" to _chat.value.sessionId, "effort" to effort), refresh = false) { _, result ->
            val effective = result.getString("value")
            _chat.update { it.copy(raw = it.raw.copy().put("reasoning_effort", effective)) }
            notice("Reasoning effort: $effective")
            if (_catalog.value == "Reasoning") closeCatalog()
        }
        reasoningChange = job
        job.invokeOnCompletion { if (reasoningChange === job) _reasoningSaving.value = false }
    }
    fun closeCatalog() { _catalog.value = "" }
    fun pickModel(model: String, provider: String) { if (!admitConversation()) return; val scope = sourceScope ?: return; sessionAction("/api/session/update", json("model" to model, "model_provider" to provider.ifBlank { null })) { saved["model_pick:${scope.key}"] = true; closeCatalog() } }
    private suspend fun exportDownload(c: CypherClient, uri: Uri, path: String, query: Map<String, String>, limit: Long) = withContext(Dispatchers.IO) {
        val temporary = java.io.File.createTempFile("hermes-export-", ".part", app.cacheDir)
        try {
            temporary.outputStream().use { c.copyTo(path, it, query, limit = limit) }
            currentCoroutineContext().ensureActive()
            app.contentResolver.openOutputStream(uri)?.use { output -> temporary.inputStream().use { it.copyTo(output) } } ?: error("Cannot write the selected document")
        } finally { temporary.delete() }
    }
    private fun fail(error: Throwable) {
        if (error is CancellationException) return
        if (error is NativeAuthExpiredException) {
            val owner = client?.scope ?: cachedOwnerScope
            dev.hermes.android.push.KeepConnectedService.onUserSignedOut(app, owner?.origin ?: _ui.value.origin)
            val draftScope = draftScope()
            val retainedDraft = draft()
            val retainedEditor = _editor.value
            checkpointJob?.cancel(); editorCheckpoint?.cancel()
            val invalidation = ++originGeneration
            cancelStartup(); resetAppUpdate(); app.updates.clearAuthorization(); ++sessionGeneration
            if (draftScope != null) {
                drafts[draftScope.key] = retainedDraft
                viewModelScope.launch {
                    runCatching {
                        app.cache.write(draftScope, draftKind(draftScope), retainedDraft.encode(), retainedDraft.text.isNotBlank() || retainedDraft.attachments.isNotEmpty() || retainedDraft.queued.isNotEmpty())
                        if (retainedEditor.scope != null && retainedEditor.path.isNotBlank()) app.cache.write(retainedEditor.scope, "editor:${retainedEditor.path}", json("text" to retainedEditor.text.text, "start" to retainedEditor.text.selection.start, "end" to retainedEditor.text.selection.end).toString(), retainedEditor.dirty || retainedEditor.saving)
                    }.onFailure { if (originGeneration == invalidation) notice("The latest local checkpoint could not be saved: ${it.message.orEmpty()}") }
                }
            }
            cancelNewChatWork(); app.calls.hangup(); sessionWatch?.cancel(); selectionJob?.cancel()
            streams.values.toList().forEach(Job::cancel); loads.values.toList().forEach(Job::cancel)
            client = null; cachedOwnerScope = owner
            _ui.update { it.copy(origin = owner?.origin ?: it.origin, owner = owner?.owner ?: it.owner, profile = owner?.profile ?: it.profile, authenticated = false, connected = false, loading = false, restoringSession = false, setupWizard = true, setupComplete = false, setupStep = "signin", error = error.message ?: "Sign-in expired. Sign in again.") }
            saveSetupProgress("signin", complete = false)
            return
        }
        if (error is LoopbackSessionRefreshFailed || error is ApiFailure && error.status == 401 || error is CachedIdentityMismatch) {
            val loopbackRestart = error is LoopbackSessionRefreshFailed
            val origin = client?.auth?.origin ?: cachedOwnerScope?.origin ?: runCatching { trustedOrigin(_ui.value.origin) }.getOrNull()
            val draftScope = draftScope()
            val retainedDraft = draft()
            val retainedEditor = _editor.value
            checkpointJob?.cancel(); editorCheckpoint?.cancel()
            val invalidation = ++originGeneration
            cancelStartup()
            resetAppUpdate(); app.updates.clearAuthorization()
            ++sessionGeneration
            if (draftScope != null) {
                drafts[draftScope.key] = retainedDraft
                viewModelScope.launch {
                    runCatching {
                        app.cache.write(draftScope, draftKind(draftScope), retainedDraft.encode(), retainedDraft.text.isNotBlank() || retainedDraft.attachments.isNotEmpty() || retainedDraft.queued.isNotEmpty())
                        if (retainedEditor.scope != null && retainedEditor.path.isNotBlank()) app.cache.write(retainedEditor.scope, "editor:${retainedEditor.path}", json("text" to retainedEditor.text.text, "start" to retainedEditor.text.selection.start, "end" to retainedEditor.text.selection.end).toString(), retainedEditor.dirty || retainedEditor.saving)
                    }.onFailure { if (originGeneration == invalidation) notice("The latest local checkpoint could not be saved: ${it.message.orEmpty()}") }
                }
            }
            cancelNewChatWork(); app.calls.hangup(); sessionWatch?.cancel(); selectionJob?.cancel()
            streams.values.toList().forEach(Job::cancel); loads.values.toList().forEach(Job::cancel)
            client = null; cachedOwnerScope = null; sourceScope = null
            val message = if (loopbackRestart) error.message.orEmpty() else "Sign in again. Your local draft is retained."
            origin?.let { revoked -> val version = app.auth.authorityEpoch.readVersion(); viewModelScope.launch { runCatching { app.auth.removeIfUnchanged(revoked, version) }.onFailure { if (originGeneration == invalidation) notice("Local credential removal failed: ${it.message.orEmpty()}") } } }
            _ui.update { it.copy(authenticated = false, connected = false, loading = false, restoringSession = false, setupWizard = true, setupComplete = false, setupStep = if (loopbackRestart) "connect" else "signin", setupOrigin = if (loopbackRestart) origin ?: it.setupOrigin else it.setupOrigin, xaraOpening = false, xaraError = "", newChatOpening = false, newChatError = "", initialChatReady = false, owner = "", query = "", sessions = emptyList(), bootstrap = JSONObject(), error = message) }
            saveSetupProgress(if (loopbackRestart) "connect" else "signin", complete = false, setupOrigin = if (loopbackRestart) origin else null)
            _chat.value = ChatState(); _composer.value = TextFieldValue(); _attachments.value = emptyList(); _data.value = emptyMap(); clearDetails()
        } else {
            _ui.update { it.copy(error = error.message ?: "The operation failed. Try again.") }
            if (error is java.io.IOException && error !is ApiFailure && connectionProbe?.isActive != true) {
                val active = client ?: return
                connectionProbe = viewModelScope.launch {
                    val c = active.detached()
                    val reachable = try { withTimeout(4000) { c.nativeRequest("/api/auth/me").string("user_id") == active.auth.owner } }
                        catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e; false }
                        catch (_: Exception) { false }
                    if (client === active) _ui.update { it.copy(connected = reachable) }
                }
            }
        }
    }
    fun connect(input: String) = connect(input, null)
    private fun connect(input: String, initialAuth: AuthContext?) = viewModelScope.launch {
        val setupFlow = _ui.value.setupWizard
        modeTransition?.cancelAndJoin()
        val generation = ++originGeneration
        cancelStartup()
        resetAppUpdate()
        cancelNewChatWork()
        try { checkpointVisible() } catch (e: Throwable) { _ui.update { it.copy(restoringSession = false) }; fail(e); return@launch }
        if (generation != originGeneration) return@launch
        sessionWatch?.cancel(); selectionJob?.cancel(); ++sessionGeneration
        streams.values.forEach(Job::cancel); streams.clear()
        _ui.update { it.copy(loading = true, restoringSession = true, error = "", xaraOpening = false, xaraError = "", newChatOpening = false, newChatError = "", initialChatReady = false, authenticated = false, connected = false, owner = "", query = "", sessions = emptyList(), bootstrap = JSONObject()) }
        _chat.value = ChatState(); _composer.value = TextFieldValue(); _attachments.value = emptyList(); _data.value = emptyMap(); clearDetails(); sourceScope = null; cachedOwnerScope = null; client = null
        var restoringClient: CypherClient? = null
        var restoringScope: Scope? = null
        val credentialVersion = app.auth.authorityEpoch.readVersion()
        try {
            val origin = trustedOrigin(input)
            if (origin != (app.preferences.data.first()[stringPreferencesKey("origin")] ?: BuildConfig.DEFAULT_ORIGIN)) app.updates.clearAuthorization()
            _ui.update { it.copy(origin = origin) }
            val auth = initialAuth?.also { require(it.origin == origin) } ?: withContext(Dispatchers.IO) { app.auth.read(origin) }
            if (generation != originGeneration) return@launch
            val next = CypherClient(auth, app.auth, persistContext = false)
            val trustedCache = auth.cachedScopeFor(origin, replacingAccount = false)
            if (!keepsRestoredCall(app.calls.state.value.conversationScope, trustedCache, false)) app.calls.hangup()
            restoringClient = next.takeIf { trustedCache != null }
            restoringScope = trustedCache
            if (trustedCache != null) restoreCachedOwner(trustedCache, generation)
            if (generation != originGeneration) return@launch
            // Local encrypted recovery settles the splash; the network may still be offline.
            _ui.update { it.copy(restoringSession = false) }
            val bootstrap = next.bootstrap()
            if (generation != originGeneration) return@launch
            trustedCache?.let(next.auth::requireCachedIdentity)
            val xara = app.cache.read(next.scope, "xara-session")
            if (generation != originGeneration) return@launch
            xara?.let { saved["xara:${next.scope.key}"] = it }
            if (!persistAuthIfCurrent(next.auth, credentialVersion)) {
                if (generation == originGeneration) {
                    app.calls.hangup(); cachedOwnerScope = null; sourceScope = null
                    _ui.update { it.copy(authenticated = false, loading = false, owner = "", error = "Sign-in changed while reconnecting. Reconnect to use the current account.") }
                }
                return@launch
            }
            next.markLoopbackSessionPersisted()
            if (generation != originGeneration) return@launch
            app.preferences.edit { it[stringPreferencesKey("origin")] = origin }
            if (generation != originGeneration) return@launch
            client = next; cachedOwnerScope = null; retireSavedDrafts(null)
            restoreBotMode(next.scope)
            restorePhoneSettings(next)
            if (generation != originGeneration || client !== next) return@launch
            if (app.firebaseConfigured && app.push.registeredTarget(next.scope) != null) {
                viewModelScope.launch { runCatching { app.push.register(next.detached()) } }
            }
            app.updates.authorize(next)
            restoreAppUpdate()
            val previousSetupStep = _ui.value.setupStep
            val wizardStep = when {
                !setupFlow -> previousSetupStep
                previousSetupStep in setOf("connect", "probe") && initialAuth == null -> previousSetupStep
                previousSetupStep in setOf("signin", "voice-provider") -> "voice-provider"
                else -> firstIncompleteSetupPermission()
            }
            if (setupFlow) saveSetupProgress(wizardStep, setupOrigin = origin)
            _ui.update { it.copy(origin = origin, owner = next.auth.owner, profile = next.auth.profile, authenticated = true, connected = true, bootstrap = bootstrap, loading = false, setupWizard = setupFlow || it.setupWizard, setupStep = if (setupFlow) wizardStep else it.setupStep) }
            dev.hermes.android.push.KeepConnectedService.startIfEnabled(app)
            checkForAppUpdate(quiet = true)
            prepareStartup()
            if (_ui.value.tab == "Xara") openXara() else {
                val selected = saved.get<String>("session:${next.scope.key}") ?: sourceScope?.takeIf { it.copy(session = "") == next.scope }?.session
                val shell = app.cache.read(next.scope, "new-chat-shell")
                if (generation != originGeneration || client !== next) return@launch
                shell?.let { initialShells[next.scope.key] = it }
                if (!selected.isNullOrBlank()) selectSession(selected) else restoreInitialDraft(next.scope, generation)
            }
        } catch (e: Throwable) {
            if (generation == originGeneration) {
                // /auth/me may rotate cookies before plugin bootstrap fails. Keep that rotation,
                // never rejected authority, so the next retry does not reuse a spent refresh token.
                if (e is java.io.IOException && !(e is ApiFailure && e.status == 401)) {
                    val retained = restoringClient
                    val owner = restoringScope
                    if (retained != null && owner != null) {
                        try {
                            retained.auth.requireCachedIdentity(owner)
                            persistAuthIfCurrent(retained.auth.copy(cookies = retained.jar.snapshot()), credentialVersion)
                        } catch (saveError: Throwable) {
                            if (saveError is CancellationException) throw saveError
                            if (generation == originGeneration) notice("The refreshed sign-in could not be retained. Reconnect before leaving Cypher.")
                        }
                    }
                }
                if (generation != originGeneration) return@launch
                _ui.update { it.copy(loading = false, restoringSession = false) }
                if (cachedOwnerScope == null) client = null
                fail(e)
            }
        } finally {
            if (generation == originGeneration) _ui.update { it.copy(restoringSession = false) }
        }
    }
    fun reopenSetupWizard(address: String = _ui.value.origin) {
        ++originGeneration
        setupProbeJob?.cancel(); ++setupProbeGeneration
        pendingSetupLink = null
        val safeAddress = runCatching { dev.hermes.android.ui.parseCypherConnectInput(address) }.getOrDefault("")
        _ui.update { it.copy(setupOrigin = safeAddress, setupWizard = true, setupStep = "connect", loading = false, restoringSession = false, setupPluginMessage = "", error = "", notice = "") }
        saveSetupProgress("connect", active = true, setupOrigin = safeAddress)
    }
    fun openSetupLink(value: String) {
        ++originGeneration
        setupProbeJob?.cancel(); ++setupProbeGeneration
        pendingSetupLink = null
        val parsed = try { dev.hermes.android.ui.parseCypherConnectLink(value) }
        catch (error: Exception) {
            _ui.update { it.copy(setupOrigin = "", setupWizard = true, setupStep = "connect", loading = false, restoringSession = false, error = error.message ?: "Enter an HTTPS Hermes address or scan its QR code.", notice = "") }
            saveSetupProgress("connect", active = true, setupOrigin = "")
            return
        }
        pendingSetupLink = parsed.takeIf { it.sessionToken != null }
        _ui.update { it.copy(setupOrigin = parsed.origin, setupWizard = true, setupStep = "connect", loading = false, restoringSession = false, setupPluginMessage = "", error = "", notice = "") }
        saveSetupProgress("connect", active = true, setupOrigin = parsed.origin)
    }
    fun cancelSetupWizard() {
        pendingSetupLink = null
        if (_ui.value.authenticated) {
            setupProbeJob?.cancel(); ++setupProbeGeneration
            val complete = _ui.value.setupComplete
            val connectedClient = client
            val previousOrigin = connectedClient?.auth?.origin ?: _ui.value.origin
            _ui.update { it.copy(origin = previousOrigin, setupOrigin = "", connected = connectedClient != null, setupWizard = false, setupStep = if (complete) "done" else it.setupStep, error = "") }
            if (connectedClient != null) viewModelScope.launch { app.preferences.edit { it[stringPreferencesKey("origin")] = previousOrigin } }
            saveSetupProgress(if (complete) "done" else _ui.value.setupStep, complete = complete, active = false)
            client?.let { prepareStartup(); recoverForeground() }
        }
    }
    fun finishSetupWizard(keepConnected: Boolean = true) {
        if (!_ui.value.authenticated) return
        pendingSetupLink = null
        setKeepConnectedEnabled(keepConnected)
        _ui.update { it.copy(setupWizard = false, setupComplete = true, setupStep = "done", setupOrigin = "", error = "") }
        saveSetupProgress("done", complete = true, active = false)
    }
    private suspend fun persistAuthIfCurrent(auth: AuthContext, version: Long?): Boolean {
        if (app.auth.persistIfUnchanged(auth, version)) return true
        val session = auth.loopbackSession?.takeIf { it.verified } ?: return false
        return withContext(Dispatchers.IO) {
            app.auth.read(auth.origin).let { current ->
                current.origin == auth.origin && current.owner == auth.owner && current.profile == auth.profile &&
                    current.backend == auth.backend && current.loopbackSession?.copy(persisted = false) == session.copy(persisted = false)
            }
        }
    }
    private fun saveSetupProgress(step: String, complete: Boolean? = null, active: Boolean? = true, setupOrigin: String? = null) {
        viewModelScope.launch {
            app.preferences.edit { values ->
                values[stringPreferencesKey("setup_step")] = step
                complete?.let { values[booleanPreferencesKey("setup_complete")] = it }
                active?.let { values[booleanPreferencesKey("setup_active")] = it }
                setupOrigin?.let { address ->
                    val safeAddress = runCatching { dev.hermes.android.ui.parseCypherConnectInput(address) }.getOrDefault("")
                    if (safeAddress.isBlank()) values.remove(stringPreferencesKey("setup_origin"))
                    else values[stringPreferencesKey("setup_origin")] = safeAddress
                }
                if (active == false) values.remove(stringPreferencesKey("setup_origin"))
            }
        }
    }
    private fun firstIncompleteSetupPermission(): String = when {
        androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> "permission-microphone"
        Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> "permission-notifications"
        Build.VERSION.SDK_INT >= 34 && app.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != true -> "permission-full-screen"
        else -> "done"
    }
    fun advanceSetupPermission() {
        val next = when (_ui.value.setupStep) {
            "voice-provider" -> firstIncompleteSetupPermission()
            "permission-microphone" -> when {
                Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> "permission-notifications"
                Build.VERSION.SDK_INT >= 34 && app.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != true -> "permission-full-screen"
                else -> "done"
            }
            "permission-notifications" -> if (Build.VERSION.SDK_INT >= 34 && app.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != true) "permission-full-screen" else "done"
            "permission-full-screen" -> "done"
            else -> return
        }
        _ui.update { it.copy(setupStep = next, error = "") }
        saveSetupProgress(next)
    }
    fun probeSetupAddress(value: String) {
        setupProbeJob?.cancel()
        val parsed = try { dev.hermes.android.ui.parseCypherConnectLink(value) }
        catch (error: Exception) {
            pendingSetupLink = null
            val safeAddress = runCatching { dev.hermes.android.ui.parseCypherConnectInput(value) }.getOrDefault("")
            _ui.update { it.copy(setupOrigin = safeAddress, setupWizard = true, setupStep = "connect", loading = false, error = error.message ?: "Enter an HTTPS Hermes address or scan its QR code.") }
            saveSetupProgress("connect", active = true, setupOrigin = safeAddress)
            return
        }
        val origin = parsed.origin
        val linkedToken = parsed.sessionToken ?: pendingSetupLink?.takeIf { it.origin == origin }?.sessionToken
        pendingSetupLink = null
        val generation = ++setupProbeGeneration
        _ui.update { it.copy(setupOrigin = origin, setupWizard = true, setupStep = "probe", loading = true, error = "") }
        saveSetupProgress("probe", active = true, setupOrigin = origin)
        setupProbeJob = viewModelScope.launch {
            var probe: CypherClient? = null
            try {
                val statusClient = CypherClient(AuthContext(origin, emptyList()))
                probe = statusClient
                val status = withTimeoutOrNull(45_000) { statusClient.get("/api/status") }
                    ?: throw java.io.IOException("Hermes server probe timed out")
                require(status.string("version").isNotBlank() && status.has("gateway_state") && status.optJSONObject("components") != null) {
                    "This address doesn't look like a Hermes server. Check the address, then Retry."
                }
                if (generation != setupProbeGeneration) return@launch
                when (nativeSetupAuthMode(status)) {
                    NativeSetupAuthMode.Loopback -> {
                        val session = linkedToken?.let { LoopbackSessionRecord(it, origin, "local") }
                            ?: withTimeoutOrNull(30_000) { statusClient.readLoopbackSessionToken() }
                            ?: throw LoopbackSessionRefreshFailed()
                        if (session.origin != origin || session.owner != "local") throw LoopbackSessionRefreshFailed()
                        val profile = _ui.value.profile.takeIf(::validNativeProfile) ?: "default"
                        val signedIn = AuthContext(origin, emptyList(), "local", profile, backend = "hermes-dashboard", loopbackSession = session)
                        connect(origin, signedIn).join()
                    }
                    NativeSetupAuthMode.NativePkce -> {
                        saveSetupProgress("signin", active = true, setupOrigin = origin)
                        _ui.update { it.copy(setupOrigin = origin, loading = false, setupStep = "signin", error = "") }
                    }
                    NativeSetupAuthMode.Unsupported -> {
                        saveSetupProgress("unsupported", active = true, setupOrigin = origin)
                        _ui.update { it.copy(setupOrigin = origin, loading = false, setupStep = "unsupported", error = "") }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                if (generation != setupProbeGeneration) return@launch
                val loopbackRestart = error is LoopbackSessionRefreshFailed
                val message = when {
                    loopbackRestart -> error.message.orEmpty()
                    generateSequence(error as Throwable?) { it.cause }.any { it is javax.net.ssl.SSLException } -> "Can't make a secure HTTPS connection. Check the address and TLS certificate, then Retry."
                    error.message?.startsWith("This address doesn't look like a Hermes") == true -> "This address doesn't look like a Hermes server. Check the address, then Retry."
                    error is ApiFailure -> "This address doesn't look like a Hermes server. Check the address, then Retry."
                    else -> "Can't reach Hermes. Check Tailscale/VPN is on, then Retry."
                }
                val step = if (loopbackRestart) "connect" else "probe"
                _ui.update { it.copy(loading = false, setupOrigin = origin, setupStep = step, error = message) }
                saveSetupProgress(step, active = true, setupOrigin = origin)
            } finally {
                probe?.closeNative()
                if (generation == setupProbeGeneration) setupProbeJob = null
            }
        }
    }
    fun signIn(context: Context) = viewModelScope.launch {
        if (_ui.value.loading) return@launch
        val origin = try { trustedOrigin(_ui.value.setupOrigin.ifBlank { _ui.value.origin }) }
        catch (error: Exception) { _ui.update { it.copy(setupStep = "connect", loading = false, error = error.message.orEmpty()) }; return@launch }
        _ui.update { it.copy(setupWizard = true, setupStep = "signin", loading = true, error = "") }
        try {
            val bearer = NativeAuth(origin).authorize(context)
            val profile = _ui.value.profile.takeIf(::validNativeProfile) ?: "default"
            val signedIn = AuthContext(origin, emptyList(), bearer.owner, profile, bearer = bearer.copy(profile = profile))
            app.preferences.edit { it[stringPreferencesKey("setup_origin")] = dev.hermes.android.ui.parseCypherConnectInput(origin); it[booleanPreferencesKey("setup_active")] = true; it[stringPreferencesKey("setup_step")] = "signin" }
            connect(origin, signedIn).join()
        } catch (error: CancellationException) { throw error }
        catch (error: Throwable) { _ui.update { it.copy(loading = false, error = error.message ?: "Hermes sign-in didn't finish. Retry.") } }
    }
    private suspend fun restoreCachedOwner(scope: Scope, generation: Int) {
        val index = app.cache.read(scope, "conversation-index")?.let(::JSONObject)
        val settings = app.cache.read(scope, "profile-settings")?.let(::JSONObject)
        val xara = app.cache.read(scope, "xara-session")
        val selected = saved.get<String>("session:${scope.key}") ?: app.cache.read(scope, "selected-session")
        val shell = app.cache.read(scope, "new-chat-shell")
        if (generation != originGeneration) return
        cachedOwnerScope = scope
        restoreBotMode(scope)
        app.calls.ownerScope = scope
        restoreAppUpdate()
        if (xara != null) saved["xara:${scope.key}"] = xara
        shell?.let { initialShells[scope.key] = it }
        settings?.let { _data.update { data -> data + ("profile-settings" to it) } }
        _ui.update { it.copy(origin = scope.origin, owner = scope.owner, profile = scope.profile, authenticated = true, connected = false, sessions = index?.rows("sessions").orEmpty()) }
        val id = if (_ui.value.tab == "Xara") xara else selected
        if (!id.isNullOrBlank()) {
            sourceScope = scope.session(id)
            val selection = ++sessionGeneration
            _chat.value = ChatState(sessionId = id, status = "Cached · reconnecting")
            if (restoreCachedConversation(scope.session(id), selection) && generation == originGeneration && sourceScope == scope.session(id)) _chat.update { it.copy(status = "Cached · reconnect to synchronize") }
        } else restoreInitialDraft(scope, generation)
    }
    private suspend fun restoreInitialDraft(owner: Scope, generation: Int = originGeneration) {
        val activeValue = _composer.value
        val attemptBefore = initialCreateAttempts[owner.key]
        val shellBefore = initialShells[owner.key]
        val jobBefore = newChatJobs[owner.key]
        val local = drafts[owner.key] ?: app.cache.read(owner, "initial-draft")?.let(Draft::decode) ?: Draft()
        val attempt = app.cache.read(owner, "new-chat-attempt")
        fun current() = generation == originGeneration && (client?.scope ?: cachedOwnerScope) == owner && sourceScope == null && _chat.value.sessionId.isBlank()
        if (!current()) return
        if (initialCreateAttempts[owner.key] == attemptBefore && initialShells[owner.key] == shellBefore && newChatJobs[owner.key] === jobBefore) {
            if (attempt != null) initialCreateAttempts[owner.key] = attempt else initialCreateAttempts.remove(owner.key)
        }
        val latest = drafts[owner.key] ?: local
        if (_composer.value == activeValue) { drafts[owner.key] = latest; restoreDraft(latest) } else checkpointNow()
        if (current()) _ui.update { it.copy(newChatError = if (initialCreateAttempts.containsKey(owner.key)) initialCreateError else "", initialChatReady = true) }
    }
    fun logout() = viewModelScope.launch {
        pendingSetupLink = null
        val generation = ++originGeneration
        cancelStartup()
        resetAppUpdate()
        app.updates.clearAuthorization()
        cancelNewChatWork()
        val cached = cachedOwnerScope
        val current = client?.detached() ?: cached?.let { owner ->
            withContext(Dispatchers.IO) { runCatching { CypherClient(app.auth.read(owner.origin).also { it.requireCachedIdentity(owner) }, app.auth, persistContext = false) }.getOrNull() }
        }
        val loopback = current?.auth?.loopbackSession != null
        try { checkpointVisible() } catch (e: Throwable) { fail(e); return@launch }
        if (generation != originGeneration) return@launch
        val origin = current?.auth?.origin ?: cached?.origin
        origin?.let { dev.hermes.android.push.KeepConnectedService.onUserSignedOut(app, it) }
        try { origin?.let { app.auth.remove(it) } } catch (e: Throwable) { fail(e); return@launch }
        modeTransition?.cancelAndJoin()
        (current?.scope ?: cached)?.let { optionalLocalSave { app.cache.delete(modeCacheScope(it), "bot-mode-return") } }
        normalModeReturn = null
        _ui.update { it.copy(botModeEnabled = false, botModeSwitching = false) }
        app.calls.hangup(); streams.values.toList().forEach(Job::cancel); streams.clear(); sessionWatch?.cancel(); selectionJob?.cancel(); ++sessionGeneration
        loads.values.toList().forEach(Job::cancel)
        current?.let { frozen ->
            // Runs local push revocation before its first network suspension.
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching { try { app.push.revokeOwner(frozen) } finally { frozen.logout() } }
                    .onFailure { if (generation == originGeneration) notice("Signed out on this phone. Push notification cleanup could not be confirmed: ${it.message.orEmpty()}") }
            }
        }
        client = null; cachedOwnerScope = null; sourceScope = null; retireSavedDrafts(null); _chat.value = ChatState(); _composer.value = TextFieldValue(); _data.value = emptyMap(); _attachments.value = emptyList(); clearDetails()
        val setupStep = if (loopback) "connect" else "signin"
        _ui.update { it.copy(authenticated = false, connected = false, loading = false, restoringSession = false, setupWizard = true, setupComplete = false, setupStep = setupStep, setupOrigin = if (loopback) origin.orEmpty() else it.setupOrigin, xaraOpening = false, xaraError = "", newChatOpening = false, newChatError = "", initialChatReady = false, sessions = emptyList(), query = "", owner = "", bootstrap = JSONObject()) }
        saveSetupProgress(setupStep, complete = false, setupOrigin = if (loopback) origin else null)
    }
    fun tab(value: String) { if (_ui.value.botModeSwitching) return; botMode.back(); tab(value, resumeDraft = true) }
    private fun tab(value: String, resumeDraft: Boolean) {
        saved.remove<String>("settings-navigation")
        ++navigationGeneration
        _ui.update { it.copy(tab = value, morePage = "", showSessions = false) }; saved["tab"] = value; saved["more"] = ""
        if (value == "Xara" && _ui.value.authenticated) openXara()
        else if (value != "Xara") {
            ++xaraGeneration; _ui.update { it.copy(xaraOpening = false, xaraError = "") }
            if (value == "Chat" && resumeDraft && canComposeInitialChat()) {
                val active = client
                val owner = active?.scope ?: cachedOwnerScope
                val navigation = navigationGeneration
                _ui.update { it.copy(newChatError = if (initialCreateAttempts.containsKey(owner?.key) && !it.newChatOpening) initialCreateError else it.newChatError) }
                // Returning to Chat is a new user intent. Wait for its owned attempt,
                // then admit only this still-current return, never the earlier request.
                viewModelScope.launch {
                    newChatJobs[owner?.key]?.join()
                    if (client === active && (client?.scope ?: cachedOwnerScope) == owner && navigationGeneration == navigation && canComposeInitialChat() && initialShells.containsKey(owner?.key)) resumeInitialChat()
                }
            }
        }
    }
    fun loadNativeProjects(projectId: String = "", force: Boolean = false): Job {
        if (projectId.isBlank()) return load("native-projects", "/api/projects", force = force)
        val key = "native-project:$projectId"
        val active = client ?: return viewModelScope.launch { storeData(key, (_data.value[key]?.copy() ?: JSONObject()).put("_error", "Reconnect to manage projects.")) }
        val c = active.detached(); val owner = c.scope; val generation = originGeneration
        loads[key]?.cancel()
        val request = "$generation:${owner.key}:${System.nanoTime()}"
        loadRequests[key] = request
        storeData(key, (_data.value[key]?.copy() ?: JSONObject()).apply { remove("_error"); put("_refreshing", true); if (!has("project") && !has("projects")) put("_loading", true) })
        val job = viewModelScope.launch {
            fun current() = client === active && client?.scope == owner && generation == originGeneration && _ui.value.authenticated && loadRequests[key] == request
            try {
                val result = c.get("/api/projects/detail", mapOf("id" to projectId))
                if (current()) storeData(key, result)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (current()) storeData(key, (_data.value[key]?.copy() ?: JSONObject()).apply { remove("_loading"); remove("_refreshing"); put("_error", e.message ?: "Could not load projects. Try again.") })
            } finally { c.closeNative() }
        }
        loads[key] = job
        return job
    }
    fun navigate(destination: String) {
        if (destination == "Bots") { toggleBotMode(); return }
        val route = dev.hermes.android.ui.navigationRoute(destination)
        if (route.tab == "More") more(route.page) else tab(route.tab)
    }
    fun more(value: String) {
        if (_ui.value.botModeSwitching) return
        if (_ui.value.tab == "More" && _ui.value.morePage == value) { toggleSessions(false); return }
        val owner = (client?.scope ?: cachedOwnerScope)?.key.orEmpty()
        val history = saved.get<String>("settings-navigation")?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.string("scope") == owner }?.optJSONArray("routes") ?: JSONArray()
        if (history.length() < 24) history.put(json("tab" to _ui.value.tab, "page" to _ui.value.morePage))
        saved["settings-navigation"] = json("scope" to owner, "routes" to history).toString()
        ++navigationGeneration
        _ui.update { it.copy(tab = "More", morePage = value, showSessions = false) }
        saved["tab"] = "More"; saved["more"] = value
    }
    fun backFromSettings() {
        if (_ui.value.botModeSwitching) return
        val owner = (client?.scope ?: cachedOwnerScope)?.key.orEmpty()
        val history = saved.get<String>("settings-navigation")?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.string("scope") == owner }
        val routes = history?.optJSONArray("routes") ?: JSONArray()
        val previous = if (routes.length() > 0) routes.remove(routes.length() - 1) as? JSONObject else null
        val targetTab = previous?.string("tab") ?: if (_ui.value.morePage.isBlank()) "Chat" else "More"
        val targetPage = previous?.string("page") ?: dev.hermes.android.ui.settingsParent(_ui.value.morePage)
        saved["settings-navigation"] = json("scope" to owner, "routes" to routes).toString()
        ++navigationGeneration
        _ui.update { it.copy(tab = targetTab, morePage = targetPage, showSessions = false) }
        saved["tab"] = targetTab; saved["more"] = targetPage
    }
    fun toggleSessions(show: Boolean = !_ui.value.showSessions) { _ui.update { it.copy(showSessions = show) } }
    fun switchProfile(name: String): Job = viewModelScope.launch {
        if (_ui.value.botModeEnabled) { toggleBotMode().join(); if (_ui.value.botModeEnabled) return@launch }
        switchProfile(name, false).join()
    }
    private fun switchProfile(name: String, retainBotMode: Boolean) = viewModelScope.launch {
        val c = client?.detached() ?: return@launch; val oldScope = c.scope; val generation = ++originGeneration
        val navigation = navigationGeneration
        if (!retainBotMode) botMode.clear()
        cancelStartup()
        resetAppUpdate()
        cancelNewChatWork()
        try {
            checkpointVisible()
            if (generation != originGeneration || client?.scope != oldScope) return@launch
            c.post("/api/profile/switch", json("name" to name))
            val bootstrap = c.bootstrap()
            require(c.auth.owner == oldScope.owner) { "Profile switch changed account identity. Sign in again." }
            if (generation != originGeneration || client?.scope != oldScope) return@launch
            sessionWatch?.cancel(); selectionJob?.cancel(); sourceScope = null; ++sessionGeneration
            app.auth.persist(c.auth); client = c.detached(); cachedOwnerScope = null; retireSavedDrafts(null)
            val restoredClient = client ?: return@launch
            restorePhoneSettings(restoredClient)
            if (generation != originGeneration || client !== restoredClient) return@launch
            app.updates.authorize(requireNotNull(client)); restoreAppUpdate()
            _data.value = emptyMap(); _chat.value = ChatState(); _composer.value = TextFieldValue(); _attachments.value = emptyList(); clearDetails()
            _ui.update { it.copy(profile = c.auth.profile, owner = c.auth.owner, bootstrap = bootstrap, xaraOpening = false, xaraError = "", newChatOpening = false, newChatError = "", initialChatReady = false, morePage = if (retainBotMode && navigation == navigationGeneration) "Bots" else if (retainBotMode) it.morePage else "", query = "", sessions = emptyList()) }
            prepareStartup()
            val next = requireNotNull(client)
            val shell = app.cache.read(next.scope, "new-chat-shell")
            if (generation != originGeneration || client !== next) return@launch
            shell?.let { initialShells[next.scope.key] = it }
            if (!retainBotMode) {
                if (_ui.value.tab == "Xara") openXara() else restoreInitialDraft(next.scope, generation)
            }
        } catch (e: Throwable) { if (generation == originGeneration && client?.scope == oldScope) fail(e) }
    }
    private suspend fun openBotChat(profile: String, storedId: String) {
        val owner = client?.auth ?: error("Reconnect before opening Bot Mode")
        val navigation = navigationGeneration
        fun selected() = _ui.value.botModeEnabled && !_ui.value.botModeSwitching && _ui.value.tab == "More" && _ui.value.morePage == "Bots"
        if (!selected()) throw CancellationException("Bot Mode was closed")
        if (owner.profile != profile) {
            val switching = switchProfile(profile, true)
            try { switching.join() } catch (error: CancellationException) { switching.cancel(); throw error }
        }
        if (navigationGeneration != navigation || !selected()) throw CancellationException("Bot Mode navigation changed")
        val active = client ?: error("Reconnect before opening Bot Mode")
        require(active.auth.owner == owner.owner && active.auth.origin == owner.origin && active.auth.profile == profile) { "Bot profile could not be opened" }
        selectSession(storedId)
        val selection = selectionJob
        val selectionNavigation = navigationGeneration
        try { selection?.join() } catch (error: CancellationException) { selection?.cancel(); throw error }
        if (navigationGeneration != selectionNavigation || !selected()) throw CancellationException("Bot conversation navigation changed")
        require(client === active && sourceScope == active.scope.session(storedId) && _chat.value.sessionId == storedId && _chat.value.raw.length() > 0) { "Bot conversation could not be loaded. Retry to reopen it." }
        _ui.update { it.copy(tab = "More", morePage = "Bots", showSessions = false) }
        saved["tab"] = "More"; saved["more"] = "Bots"
    }
    private fun modeCacheScope(owner: Scope) = owner.copy(profile = "default", session = "")
    private suspend fun restoreBotMode(owner: Scope) {
        val encoded = app.cache.read(modeCacheScope(owner), "bot-mode-return")
        normalModeReturn = encoded?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf {
            it.string("origin") == owner.origin && it.string("owner") == owner.owner && it.string("profile").isNotBlank()
        }
        _ui.update { it.copy(botModeEnabled = normalModeReturn != null, botModeSwitching = false) }
        if (normalModeReturn != null && _ui.value.tab == "Chat") {
            _ui.update { it.copy(tab = "More", morePage = "Bots") }
            saved["tab"] = "More"; saved["more"] = "Bots"
        }
    }
    fun toggleBotMode(): Job {
        modeTransition?.takeIf { it.isActive }?.let { return it }
        val active = client ?: return viewModelScope.launch { notice("Reconnect before switching modes. Your drafts are kept.") }
        val owner = active.scope
        val enabling = !_ui.value.botModeEnabled
        ++navigationGeneration
        botMode.pause()
        return viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            _ui.update { it.copy(botModeSwitching = true) }
            try {
                cancelNewChatWork()
                selectionJob?.cancelAndJoin()
                checkpointVisible()
                require(client?.scope == owner) { "The account changed. Try switching modes again." }
                if (enabling) {
                    val current = _ui.value
                    val target = json("origin" to owner.origin, "owner" to owner.owner, "profile" to owner.profile,
                        "session" to _chat.value.sessionId, "tab" to current.tab, "page" to current.morePage,
                        "query" to current.query, "archived" to sessionIndexArchived)
                    app.cache.write(modeCacheScope(owner), "bot-mode-return", target.toString(), protected = true)
                    normalModeReturn = target
                    _ui.update { it.copy(botModeEnabled = true, tab = "More", morePage = "Bots") }
                    saved["tab"] = "More"; saved["more"] = "Bots"
                    botMode.refresh(silent = true)
                } else {
                    val target = requireNotNull(normalModeReturn) { "The original chat could not be restored. Reconnect and try again." }
                    require(target.string("owner") == owner.owner && target.string("origin") == owner.origin) { "The saved chat belongs to another account." }
                    if (owner.profile != target.string("profile")) {
                        val switching = switchProfile(target.string("profile"), true)
                        try { switching.join() } catch (error: CancellationException) { switching.cancel(); throw error }
                    }
                    val restored = requireNotNull(client)
                    val restoredGeneration = originGeneration
                    require(restored.scope == owner.copy(profile = target.getString("profile"))) { "Your original profile could not be restored. Try again." }
                    val id = target.string("session")
                    if (id.isNotBlank()) {
                        val hydrated = CompletableDeferred<Unit>()
                        selectSession(id, closeMenu = false, onCachedReady = { hydrated.complete(Unit) })
                        val selection = selectionJob
                        val selectedNavigation = navigationGeneration
                        try { kotlinx.coroutines.selects.select<Unit> { hydrated.onAwait { }; selection?.onJoin { } } } catch (error: CancellationException) { selection?.cancel(); throw error }
                        // Leaving Bot Mode must always succeed: an unsent new chat has no history yet, and a load failure stays in the chat with its retry.
                        require(client === restored && originGeneration == restoredGeneration && navigationGeneration == selectedNavigation &&
                            sourceScope == restored.scope.session(id) && _chat.value.sessionId == id) {
                            "Your original conversation could not be restored. Its draft is kept; try switching back again."
                        }
                    }
                    else {
                        sessionWatch?.cancel(); ++sessionGeneration; sourceScope = null
                        _chat.value = ChatState(); _composer.value = TextFieldValue(); _attachments.value = emptyList()
                        restoreInitialDraft(restored.scope)
                    }
                    require(client === restored && originGeneration == restoredGeneration) { "The account changed while restoring your chat." }
                    app.cache.delete(modeCacheScope(owner), "bot-mode-return")
                    currentCoroutineContext().ensureActive()
                    if (client !== restored || originGeneration != restoredGeneration) return@launch
                    normalModeReturn = null
                    val tab = target.string("tab", "Chat")
                    val page = target.string("page")
                    _ui.update { it.copy(botModeEnabled = false, tab = tab, morePage = page, query = target.string("query")) }
                    saved["tab"] = tab; saved["more"] = page
                    refreshSessions(target.string("query"), target.optBoolean("archived"))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notice(error.message ?: "Could not switch modes. Your drafts are kept.")
            } finally { _ui.update { it.copy(botModeSwitching = false) }; if (enabling && _ui.value.botModeEnabled) botMode.resume() }
        }.also { modeTransition = it }
    }
    fun showBotMode() {
        if (!_ui.value.botModeEnabled || _ui.value.botModeSwitching) return
        botMode.home(); ++navigationGeneration
        _ui.update { it.copy(tab = "More", morePage = "Bots", showSessions = false) }
        saved["tab"] = "More"; saved["more"] = "Bots"
    }
    fun openBot(bot: BotModeBot) {
        if (!_ui.value.botModeEnabled || _ui.value.botModeSwitching) return
        botMode.back(); ++navigationGeneration
        _ui.update { it.copy(tab = "More", morePage = "Bots", showSessions = false) }
        saved["tab"] = "More"; saved["more"] = "Bots"
        botMode.openBot(bot)
    }
    fun openBotSpace(id: String) {
        if (!_ui.value.botModeEnabled || _ui.value.botModeSwitching) return
        ++navigationGeneration
        _ui.update { it.copy(tab = "More", morePage = "Bots", showSessions = false) }
        saved["tab"] = "More"; saved["more"] = "Bots"
        botMode.selectSpace(id)
    }
    private fun cancelStartup() {
        startupJob?.cancel(); startupIndex?.cancel(); startupSettings?.cancel(); optionalProbe?.cancel()
        preloadJobs.toList().forEach(Job::cancel); preloadJobs.clear()
        loads.values.toList().forEach(Job::cancel)
        ++sessionIndexRequest
        if (preparedXara?.first != client?.scope) preparedXara = null
    }
    private fun prepareStartup() {
        cancelStartup()
        val active = client ?: return
        val c = active.detached()
        val owner = c.scope
        val generation = originGeneration
        fun current() = generation == originGeneration && client === active && _ui.value.authenticated && foreground
        if (!current()) return
        // Native chat/history are already established; the optional plugin probe only adds features.
        if (optionalFeaturesPending(_ui.value.bootstrap, owner)) optionalProbe = viewModelScope.launch {
            val optional = try { c.probeOptionalFeatures() } catch (e: Exception) { if (e is CancellationException) throw e; return@launch }
            if (!current() || active.scope != owner) return@launch
            val pluginMessage = if (optional.string("plugin") == "absent") "Cypher plugin not installed on this Hermes — chat works; calls need the plugin." else ""
            _ui.update { state -> mergeOptionalFeaturesFor(state.bootstrap, owner, optional)?.let { state.copy(bootstrap = it, setupPluginMessage = pluginMessage) } ?: state }
        }
        viewModelScope.launch { try { val prefs = app.cache.read(owner, "chat-preferences")?.let(::JSONObject) ?: json("render_user_markdown" to true, "send_key" to "enter"); if (current()) storeData("profile-settings", prefs) } catch (e: Exception) { if (e is CancellationException) throw e; if (current()) fail(e) } }
        phoneSettings.loadSkills(force = false)
        phoneSettings.loadMemory(force = false)
        botMode.refresh(silent = true)
        val index = refreshSessions(force = false).also { startupIndex = it }
        startupSettings = viewModelScope.launch {
            withTimeoutOrNull(STARTUP_READ_BUDGET) {
                val slots = kotlinx.coroutines.sync.Semaphore(STARTUP_READ_PARALLELISM)
                coroutineScope {
                    startupReads.forEach { read -> launch {
                        slots.acquire()
                        try {
                            if (!current()) return@launch
                            val job = ensureLoaded(read.key, read.path)
                            preloadJobs.add(job)
                            try { job.join() } finally { preloadJobs.remove(job); if (!currentCoroutineContext().isActive) job.cancel() }
                        } finally { slots.release() }
                    } }
                }
            }
        }
        startupJob = viewModelScope.launch {
            // Extra history work is sequential and expires; navigation owns its own live requests.
            withTimeoutOrNull(8_000) {
                var xara = ""
                if (owner.profile == "default") {
                    try {
                        xara = preparedXara?.takeIf { it.first == owner }?.second
                            ?: saved.get<String>("xara:${owner.key}")
                            ?: app.cache.read(owner, "xara-session").orEmpty()
                        if (!current()) return@withTimeoutOrNull
                        if (xara.isNotBlank()) {
                            preparedXara = owner to xara
                            val response = c.get("/api/session", mapOf("session_id" to xara), maxBytes = 1024 * 1024)
                            if (current() && sourceScope != owner.session(xara)) app.cache.write(owner.session(xara), "session", response.toString())
                        }
                    } catch (e: Throwable) { if (e is CancellationException) throw e; if (current() && e is ApiFailure && e.status == 401) fail(e) }
                }
                index.join()
                if (!current()) return@withTimeoutOrNull
                for (id in startupRecentSessions(_ui.value.sessions.map { it.string("session_id", it.string("id")) }, xara)) {
                    if (!current()) break
                    try {
                        val response = c.get("/api/session", mapOf("session_id" to id), maxBytes = 1024 * 1024)
                        if (current() && sourceScope != owner.session(id)) app.cache.write(owner.session(id), "session", response.toString())
                    } catch (e: Throwable) { if (e is CancellationException) throw e; if (current() && e is ApiFailure && e.status == 401) fail(e) }
                }
            }
        }
    }
    private suspend fun resolveXara(c: CypherClient, current: () -> Boolean): String = xaraResolveLock.withLock {
        preparedXara?.takeIf { it.first == c.scope }?.let { return@withLock it.second }
        val bound = saved.get<String>("xara:${c.scope.key}") ?: app.cache.read(c.scope, "xara-session")
        if (!bound.isNullOrBlank()) { require(current()) { "Account changed while preparing Cypher" }; preparedXara = c.scope to bound; return@withLock bound }
        require(app.cache.read(c.scope, "xara-opening") == null) { "Cypher opening outcome is unknown. Review history before creating another conversation." }
        currentCoroutineContext().ensureActive()
        require(current()) { "Account changed while preparing Cypher" }
        app.cache.write(c.scope, "xara-opening", "unknown", true)
        val result = c.post("/api/xara/open")
        val id = result.string("session_id", result.string("conversation_id", result.optJSONObject("session")?.string("session_id").orEmpty()))
        require(id.isNotBlank()) { "Cypher did not return a conversation" }
        val sessionScope = c.scope.session(id)
        clientCreatedSessions.add(sessionScope.key)
        // The owned creation already happened; a newer navigation must not discard its identity.
        saved["xara:${c.scope.key}"] = id
        if (client?.scope == c.scope) preparedXara = c.scope to id
        runCatching { app.cache.write(c.scope, "xara-session", id, protected = true); app.cache.delete(c.scope, "xara-opening"); app.cache.write(sessionScope, "client-created-shell", "1", protected = true) }.onFailure { if (it is CancellationException) throw it; if (client?.scope == c.scope) notice("Cypher opened; recovery checkpoint failed") }
        id
    }
    private var indexFetch: Job? = null
    private var indexFetchKey = ""
    private var indexReadAt = 0L
    fun refreshSessions(query: String = _ui.value.query, archived: Boolean = false, force: Boolean = true): Job {
        val active = client ?: return viewModelScope.launch {}
        val c = active.detached()
        val scope = c.scope
        val generation = originGeneration
        val fetchKey = "${scope.key}:$generation:$query:$archived"
        if (indexFetchKey == fetchKey) {
            if (!force) indexFetch?.takeIf { it.isActive }?.let { return it }
            if (!force && startupReadFresh(indexReadAt, System.currentTimeMillis())) return viewModelScope.launch {}
        }
        indexFetchKey = fetchKey
        val request = ++sessionIndexRequest
        val revision = sessionIndexRevision
        sessionIndexArchived = archived
        _ui.update { it.copy(query = query) }
        fun current() = client === active && generation == originGeneration && _ui.value.authenticated && sessionIndexCurrent(request, sessionIndexRequest, revision, sessionIndexRevision)
        return viewModelScope.launch {
          try { supervisorScope {
            val params = mutableMapOf("profile" to scope.profile, "include_archived" to if (archived) "1" else "0")
            val sessions = if (query.isBlank()) c.get("/api/sessions", params) else c.get("/api/sessions/search", params + ("q" to query))
            if (!current()) return@supervisorScope
            val serverRows = sessions.rows("sessions").ifEmpty { sessions.rows("results") }
            if (query.isBlank() && !archived) serverRows.forEach { row ->
                val key = "${scope.key}:${sessionRowId(row)}"
                val local = confirmedSessions[key]
                if (local != null && row.optInt("message_count") >= local.optInt("message_count")) confirmedSessions.remove(key)
            }
            val confirmed = confirmedSessions.filterKeys { it.startsWith("${scope.key}:") }.values.toList().asReversed()
            val rows = visibleSessionRows(serverRows, confirmed, query, archived)
            indexReadAt = System.currentTimeMillis()
            _ui.update { it.copy(sessions = rows, connected = true) }
            if (current()) {
                app.auth.persist(c.auth)
                if (query.isBlank() && !archived) app.cache.write(scope, "conversation-index", json("sessions" to JSONArray(rows)).toString())
            }

          } } catch (e: Throwable) { if (e is CancellationException) throw e; if (current()) fail(e) }
        }.also { indexFetch = it }
    }
    private fun publishConfirmedSession(owner: Scope, id: String, response: JSONObject, accepted: Boolean = false) {
        if (client?.scope != owner || !_ui.value.authenticated) return
        val session = response.optJSONObject("session") ?: response
        val existing = _ui.value.sessions.firstOrNull { sessionRowId(it) == id }
        val row = (existing ?: session).copy().put("session_id", id).put("profile", owner.profile)
        if (row.string("title").isBlank()) row.put("title", _chat.value.title.takeIf { _chat.value.sessionId == id } ?: "New conversation")
        if (accepted) row.put("message_count", maxOf(1, row.optInt("message_count"))).put("active_stream_id", response.string("stream_id"))
        // Only server-confirmed IDs enter this temporary index overlay.
        confirmedSessions["${owner.key}:$id"] = row
        ++sessionIndexRevision
        if (_ui.value.query.isBlank() && !sessionIndexArchived) _ui.update { it.copy(sessions = visibleSessionRows(it.sessions, listOf(row), "", false)) }
    }
    private fun initialDraftOwner(): Scope? = (client?.scope ?: cachedOwnerScope)?.takeIf { _ui.value.authenticated && sourceScope == null && _chat.value.sessionId.isBlank() }
    fun canComposeInitialChat(): Boolean = _ui.value.initialChatReady && _ui.value.tab == "Chat" && initialDraftOwner() != null
    fun newChat(body: JSONObject = JSONObject()): Job {
        if (_ui.value.botModeEnabled) { showBotMode(); return viewModelScope.launch {} }
        botMode.back()
        val owner = initialDraftOwner()
        if (owner != null) {
            if (_ui.value.tab != "Chat") tab("Chat", resumeDraft = false)
            val active = client
            val origin = originGeneration
            val options = JSONObject(body.toString())
            newChatJobs[owner.key]?.takeIf { !it.isCompleted }?.let { opening ->
                val shell = initialShells[owner.key] ?: return opening
                return viewModelScope.launch {
                    opening.join()
                    if (client === active && originGeneration == origin && initialDraftOwner() == owner && canComposeInitialChat() &&
                        !initialCreateAttempts.containsKey(owner.key) &&
                        (initialShells[owner.key] == null || options.length() > 0 && initialShells[owner.key] == shell)
                    ) newChat(options).join()
                }
            }
            val navigation = ++navigationGeneration
            return viewModelScope.launch {
                fun current() = client === active && originGeneration == origin && navigationGeneration == navigation && initialDraftOwner() == owner && canComposeInitialChat()
                try {
                    if (!_ui.value.initialChatReady) restoreInitialDraft(owner, origin)
                    if (!current()) return@launch
                    if (initialShells.containsKey(owner.key) && (initialCreateAttempts.containsKey(owner.key) || options.length() == 0)) {
                        resumeInitialChat().join()
                        if (!current()) return@launch
                    }
                    if (initialCreateAttempts.containsKey(owner.key)) { _ui.update { it.copy(newChatError = initialCreateError) }; notice("Use Retry to open a conversation after the unconfirmed attempt. Your draft is retained."); return@launch }
                    // A legitimate New intent from Tasks/Spaces keeps the owner's unsent
                    // draft; explicit workspace/worktree/model options pass through intact.
                    if (options.length() > 0 || !initialShells.containsKey(owner.key)) createNewChat(options, initial = true).join()
                } catch (e: Throwable) { if (client === active && originGeneration == origin && navigationGeneration == navigation && initialDraftOwner() == owner) fail(e) }
            }
        }
        client?.scope?.key?.let { key -> newChatJobs[key]?.takeIf { !it.isCompleted }?.let { return it } }
        ++navigationGeneration
        return createNewChat(body, initial = false)
    }
    fun retryInitialChat(): Job {
        if (!canComposeInitialChat()) return viewModelScope.launch {}
        client?.scope?.key?.let { key -> newChatJobs[key]?.takeIf { !it.isCompleted }?.let { return it } }
        ++navigationGeneration
        _ui.update { it.copy(newChatError = "", notice = "") }
        return if (initialShells.containsKey((client?.scope ?: cachedOwnerScope)?.key)) resumeInitialChat() else createNewChat(json("worktree" to false), true)
    }
    private fun createNewChat(body: JSONObject, initial: Boolean): Job {
        val active = client ?: return viewModelScope.launch { notice("Reconnect to open a conversation. Your draft is retained.") }
        val owner = active.scope
        newChatJobs[owner.key]?.takeIf { !it.isCompleted }?.let { return it }
        val selected = sourceScope
        val origin = originGeneration
        val selection = sessionGeneration
        val navigation = navigationGeneration
        val tab = _ui.value.tab
        val c = active
        fun current() = client === active && active.scope == owner && originGeneration == origin && sessionGeneration == selection && navigationGeneration == navigation && sourceScope == selected && _ui.value.tab == tab
        val request = JSONObject(body.toString()).apply { put("profile", owner.profile); selected?.session?.let { put("prev_session_id", it) } }
        val attempt = if (initial) java.util.UUID.randomUUID().toString().also { initialCreateAttempts[owner.key] = it } else null
        val job = viewModelScope.launch {
            val running = currentCoroutineContext()[Job]
            if (current()) _ui.update { it.copy(newChatOpening = true, newChatError = "") }
            try {
                // A durable owner-bound marker precedes the non-idempotent POST.
                // Leaving Chat, process death or cancellation cannot silently retry it.
                if (attempt != null) app.cache.write(owner, "new-chat-attempt", attempt, protected = true)
                checkpointNow()
                if (!current()) return@launch
                val response = c.post("/api/session/new", request)
                val id = (response.optJSONObject("session") ?: response).getString("session_id")
                require(id.isNotBlank()) { "The server did not return a conversation ID" }
                val createdScope = owner.session(id)
                clientCreatedSessions.add(createdScope.key)
                optionalLocalSave { app.cache.write(createdScope, "client-created-shell", "1", protected = true) }
                if (client === active && originGeneration == origin) publishConfirmedSession(owner, id, response)
                if (initial) {
                    initialShells[owner.key] = id
                    if (initialCreateAttempts[owner.key] == attempt) initialCreateAttempts.remove(owner.key)
                    runCatching { app.cache.write(owner, "new-chat-shell", id, protected = true); app.cache.delete(owner, "new-chat-attempt") }
                        .onFailure { if (it is CancellationException) throw it; if (current()) notice("Conversation opened; local recovery checkpoint failed") }
                }
                if (!current()) return@launch
                optionalLocalSave { app.auth.persist(c.auth) }
                _ui.update { it.copy(tab = "Chat", connected = true, newChatError = "") }; saved["tab"] = "Chat"
                selectSession(id, preserveInitialDraft = initial, initialResponse = response, closeMenu = !initial)
                refreshSessions()
                selectionJob?.join()
            } catch (e: Throwable) {
                // Settle only the original owner's marker, even after navigation.
                // Retry waits for this job to finish, so its marker cannot race this exit.
                if (attempt != null && initialCreateAttempts[owner.key] == attempt) withContext(NonCancellable) {
                    runCatching { app.cache.write(owner, "new-chat-attempt", attempt, protected = true) }
                        .onFailure { if (current()) notice("The opening-attempt checkpoint could not be saved: ${it.message.orEmpty()}") }
                }
                if (current() && e !is CancellationException) { _ui.update { it.copy(newChatError = initialCreateError) }; fail(e) }
            } finally {
                if (newChatJobs[owner.key] === running) {
                    newChatJobs.remove(owner.key)
                    if (client === active && originGeneration == origin) _ui.update { it.copy(newChatOpening = false, newChatError = if (sourceScope == null && initialCreateAttempts.containsKey(owner.key)) initialCreateError else it.newChatError) }
                }
            }
        }
        newChatJobs[owner.key] = job
        return job
    }
    private fun resumeInitialChat(): Job {
        val active = client ?: return viewModelScope.launch {}
        val owner = active.scope
        val id = initialShells[owner.key] ?: return viewModelScope.launch {}
        newChatJobs[owner.key]?.takeIf { !it.isCompleted }?.let { return it }
        val origin = originGeneration; val selection = sessionGeneration; val navigation = navigationGeneration
        val c = active
        val scope = owner.session(id)
        fun current() = client === active && active.scope == owner && originGeneration == origin && sessionGeneration == selection && navigationGeneration == navigation && canComposeInitialChat()
        suspend fun clientCreatedShell() = clientCreatedSessions.contains(scope.key) || try {
            app.cache.read(scope, "client-created-shell") == "1"
        } catch (lookupError: Exception) {
            if (lookupError is CancellationException) throw lookupError
            if (current()) notice("The draft recovery marker could not be checked; your draft is kept")
            true
        }
        suspend fun readInitialSession() = try {
            c.get("/api/session", mapOf("session_id" to id))
        } catch (readError: Throwable) {
            if (readError is CancellationException) throw readError
            if (readError !is ApiFailure || readError.status != 404 || !current() || !clientCreatedShell()) throw readError
            val resumed = c.nativeSession(id)
            require(resumed.storedSessionId == id) { "The resumed conversation changed identity" }
            c.get("/api/session", mapOf("session_id" to id))
        }
        val job = viewModelScope.launch {
            val running = currentCoroutineContext()[Job]
            if (current()) _ui.update { it.copy(newChatOpening = true) }
            try {
                val response = readInitialSession()
                val session = response.optJSONObject("session") ?: response
                require(session.string("session_id") == id) { "The conversation response changed identity" }
                require(session.rows("messages").isEmpty() && session.optInt("message_count") == 0 && session.string("active_stream_id").isBlank() && !session.optBoolean("has_pending_user_message") && session.opt("pending_user_message") in listOf(null, JSONObject.NULL, "") && session.string("profile", "default") == owner.profile) { "The saved conversation is no longer an empty draft. Open it from history or retry a new conversation." }
                if (clientCreatedShell()) clientCreatedSessions.add(scope.key)
                if (!current()) return@launch
                app.cache.delete(owner, "new-chat-attempt"); initialCreateAttempts.remove(owner.key)
                if (!current()) return@launch
                selectSession(id, preserveInitialDraft = true, initialResponse = response, closeMenu = false)
                selectionJob?.join()
            } catch (e: Throwable) {
                if (e !is CancellationException) {
                    fun ownsMissingShell() = client === active && originGeneration == origin && initialShells[owner.key] == id
                    if (missingNativeConversation(e) && ownsMissingShell()) {
                        val clientCreated = clientCreatedShell()
                        val confirmedNativeMissing = e is ApiFailure && e.status == 4007 && e.message == "session not found"
                        // Hermes can keep a minted shell out of REST history until its first native resume.
                        if (clientCreated && !confirmedNativeMissing) {
                            clientCreatedSessions.add(scope.key)
                            optionalLocalSave { app.cache.write(scope, "client-created-shell", "1", protected = true) }
                            if (current()) {
                                selectSession(id, preserveInitialDraft = true, closeMenu = false)
                                selectionJob?.join()
                            }
                            return@launch
                        }
                        val attempt = initialCreateAttempts[owner.key]
                        runCatching {
                            if (app.cache.read(owner, "new-chat-shell") == id && ownsMissingShell()) app.cache.delete(owner, "new-chat-shell")
                            if (app.cache.read(owner, "new-chat-attempt") != null && initialCreateAttempts[owner.key] == attempt && ownsMissingShell()) app.cache.delete(owner, "new-chat-attempt")
                            if (ownsMissingShell()) app.cache.delete(scope, "client-created-shell")
                        }.onFailure {
                            if (it is CancellationException) throw it
                            if (current()) notice("The missing conversation recovery pointers could not be cleared")
                        }
                        if (ownsMissingShell() && initialCreateAttempts[owner.key] == attempt) {
                            initialShells.remove(owner.key)
                            initialCreateAttempts.remove(owner.key)
                            clientCreatedSessions.remove(scope.key)
                        }
                        return@launch
                    }
                    val attempt = java.util.UUID.randomUUID().toString()
                    initialCreateAttempts[owner.key] = attempt
                    runCatching {
                        app.cache.write(owner, "new-chat-attempt", attempt, protected = true)
                        if ((e is ApiFailure && e.status == 404 || e is IllegalArgumentException) && initialShells[owner.key] == id) { initialShells.remove(owner.key); app.cache.delete(owner, "new-chat-shell") }
                    }.onFailure { if (current()) notice("The opening-attempt checkpoint could not be saved: ${it.message.orEmpty()}") }
                    if (current()) { _ui.update { it.copy(newChatError = initialCreateError) }; fail(e) }
                }
            } finally {
                if (newChatJobs[owner.key] === running) { newChatJobs.remove(owner.key); if (client === active && originGeneration == origin) _ui.update { it.copy(newChatOpening = false) } }
            }
        }
        newChatJobs[owner.key] = job
        return job
    }
    fun openConversation(row: JSONObject) {
        val id = sessionRowId(row)
        val source = row.string("session_source", row.string("raw_source", row.string("source_tag", row.string("source")))).lowercase()
        if (source != "webui" && (row.optBoolean("is_cli_session") || source in setOf("telegram", "discord", "slack", "whatsapp", "signal", "matrix", "messenger", "sms", "email"))) {
            action("/api/session/import_cli", json("session_id" to id), refresh = false) { _, _ -> selectSession(id); refreshSessions() }
        } else selectSession(id)
    }
    fun openCurrentCall(expectedId: String = app.calls.state.value.callId): Job = viewModelScope.launch {
        val call=app.calls.state.value
        val target=call.conversationScope ?: return@launch
        val owner=client?.scope ?: cachedOwnerScope ?: return@launch
        if(!call.active||call.callId!=expectedId||owner.origin!=target.origin||owner.owner!=target.owner) return@launch
        if(owner.profile!=target.profile) switchProfile(target.profile).join()
        val current=client?.scope ?: cachedOwnerScope
        if(current!=target.copy(session="")||!app.calls.state.value.active||app.calls.state.value.callId!=expectedId) return@launch
        tab("Chat");selectSession(target.session);selectionJob?.join()
        if(app.calls.state.value.callId==expectedId) app.calls.resumeWindow()
    }
    fun openXara(): Job {
        val active = client
        val owner = active?.scope ?: cachedOwnerScope
        val generation = ++xaraGeneration
        val connection = originGeneration
        val c = active
        fun current() = generation == xaraGeneration && connection == originGeneration && _ui.value.tab == "Xara" && client === active && (client?.scope ?: cachedOwnerScope) == owner
        return viewModelScope.launch {
            if (!current()) return@launch
            if (owner == null || !_ui.value.authenticated) { _ui.update { it.copy(xaraOpening = false, xaraError = "Sign in again to open Cypher's conversation.") }; return@launch }
            if (owner.profile != "default") { _ui.update { it.copy(xaraOpening = false, xaraError = "Cypher belongs to the default profile. Switch profiles to continue.") }; return@launch }
            _ui.update { it.copy(xaraOpening = true, xaraError = "") }
            try {
                val id = if (c == null) (saved.get<String>("xara:${owner.key}") ?: app.cache.read(owner, "xara-session")).orEmpty()
                else preparedXara?.takeIf { it.first == owner }?.second ?: resolveXara(c) { current() }
                require(id.isNotBlank()) { "Reconnect to resolve Cypher's conversation. Your other conversation draft is retained." }
                if (!current()) return@launch
                selectSession(id, onCachedReady = { if (current()) _ui.update { it.copy(xaraOpening = false) } })
                selectionJob?.join()
                if (current()) _ui.update { it.copy(xaraOpening = false) }
            } catch (e: Throwable) { if (current() && e !is CancellationException) { _ui.update { it.copy(xaraOpening = false, xaraError = e.message ?: "Cypher's conversation could not be opened. Retry to continue.") }; fail(e) } }
        }
    }
    fun isCypherConversation(): Boolean {
        val owner = client?.scope ?: cachedOwnerScope
        return canonicalConversationReady(owner, sourceScope, _chat.value.sessionId, true, owner?.let { saved.get<String>("xara:${it.key}") }, _ui.value.xaraOpening, _ui.value.xaraError.isNotBlank())
    }
    private fun admitConversation(): Boolean {
        val owner = client?.scope ?: cachedOwnerScope
        val ready = canonicalConversationReady(owner, sourceScope, _chat.value.sessionId, _ui.value.tab == "Xara", owner?.let { saved.get<String>("xara:${it.key}") }, _ui.value.xaraOpening, _ui.value.xaraError.isNotBlank())
        if (!ready) notice(if (_ui.value.tab == "Xara") "Open Cypher's conversation before sending or changing it. Your scoped draft is retained." else "Open a conversation before continuing.")
        return ready
    }
    fun selectSession(id: String, editorDraft: String? = null, preserveInitialDraft: Boolean = false, initialResponse: JSONObject? = null, closeMenu: Boolean = true, onCachedReady: (() -> Unit)? = null) {
        selectionJob?.cancel()
        val navigation = ++navigationGeneration
        val generation = ++sessionGeneration
        val active = client
        val ownerScope = active?.scope ?: cachedOwnerScope ?: return
        val c = active?.detached()
        selectionJob = viewModelScope.launch {
            try { checkpointVisible() } catch (e: Throwable) { fail(e); return@launch }
            if (generation != sessionGeneration || client !== active || (client?.scope ?: cachedOwnerScope) != ownerScope || preserveInitialDraft && navigationGeneration != navigation) return@launch
            sessionWatch?.cancel()
            val scope = ownerScope.session(id)
            clientCreatedSessions.removeAll { it != scope.key }
            var clientCreated = initialShells[ownerScope.key] == id || clientCreatedSessions.contains(scope.key)
            val initialDraft = if (preserveInitialDraft) draft() else null
            initialDraft?.let { drafts[scope.key] = it }
            sourceScope = scope; clearDetails()
            saved["session:${ownerScope.key}"] = id
            _ui.update { it.copy(showSessions = if (closeMenu) false else it.showSessions, newChatError = "") }
            _chat.value = ChatState(sessionId = id, projectionReady = false)
            if (!preserveInitialDraft) { _composer.value = TextFieldValue(); _attachments.value = emptyList() }
            else initialDraft?.let { restoreDraft(it, preserveActiveInput = true) }
            initialResponse?.let { renderSession(it, scope) }
            try {
                clientCreated = clientCreated || app.cache.read(scope, "client-created-shell") == "1" ||
                    (saved.get<String>("xara:${ownerScope.key}") == id && app.cache.read(scope, "session") == null)
                if (clientCreated) {
                    clientCreatedSessions.add(scope.key)
                    optionalLocalSave { app.cache.write(scope, "client-created-shell", "1", protected = true) }
                }
                if (preserveInitialDraft) checkpointNow()
                app.cache.write(ownerScope, "selected-session", id, protected = true)
                if (!restoreCachedConversation(scope, generation, preserveActiveInput = preserveInitialDraft)) return@launch
                if (_chat.value.raw.length() > 0) onCachedReady?.invoke()
                if (preserveInitialDraft && sourceScope == scope && generation == sessionGeneration && client === active) {
                    app.cache.delete(ownerScope, "initial-draft")
                    if (sourceScope == scope && generation == sessionGeneration && client === active) drafts.remove(ownerScope.key).also { persistedDrafts.remove(ownerScope.key) }
                }
                if (editorDraft != null) { _composer.value = TextFieldValue(editorDraft); checkpoint() }
                if (c == null) { _chat.update { it.copy(projectionReady = true, status = "Cached · reconnect to synchronize") }; return@launch }
                val response = c.get("/api/session", mapOf("session_id" to id))
                if (generation != sessionGeneration || sourceScope != scope || client !== active) return@launch
                renderSession(response, scope)
                app.cache.write(scope, "session", response.toString())
                val session = response.optJSONObject("session") ?: response
                if (session.rows("messages").isNotEmpty() || session.optInt("message_count") > 0 || session.string("active_stream_id").isNotBlank()) {
                    clientCreated = false
                    clientCreatedSessions.remove(scope.key)
                    optionalLocalSave { app.cache.delete(scope, "client-created-shell") }
                }
                if (_chat.value.streamId.isNotBlank()) pollPrompts(c.detached(), scope)
                if (pending.containsKey(scope.key)) reconcile(scope, c.detached())
                val active = (response.optJSONObject("session") ?: response).string("active_stream_id")
                if (active.isNotBlank() && streams[scope.key]?.isActive != true) attachStream(c.detached(), scope, active)
                watchSession(c.detached(), scope)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (sourceScope == scope && generation == sessionGeneration && client === active) {
                    val cached = _chat.value.raw.length() > 0
                    val pendingFirstTurn = e is ApiFailure && e.status == 404 && sessionCanStillBeUnpersisted(clientCreated, _chat.value)
                    _chat.update { it.copy(projectionReady = true, status = if (pendingFirstTurn) "" else when { missingNativeConversation(e) -> "Conversation unavailable · draft retained"; e is ApiFailure && e.status in setOf(401, 403) -> "Access denied · draft retained"; cached -> "Cached · refresh failed"; else -> "Could not load conversation · retry" }) }
                    if (!pendingFirstTurn) fail(e)
                }
            }
        }
    }
    private suspend fun restoreCachedConversation(scope: Scope, generation: Int, preserveActiveInput: Boolean = false): Boolean {
        fun current() = generation == sessionGeneration && sourceScope == scope && (client?.scope ?: cachedOwnerScope) == scope.copy(session = "")
        val cached = app.cache.readMany(scope, listOf("draft", "side-question", "session", "pending"))
        if (!current()) return false
        if (!preserveActiveInput) {
            val localDraft = drafts[scope.key] ?: cached["draft"]?.let(Draft::decode) ?: Draft()
            if (!current()) return false
            drafts[scope.key] = localDraft; restoreDraft(localDraft)
        }
        val side = cached["side-question"]?.let(::JSONObject)
        if (!current()) return false
        if (side != null) {
            _side.value = SideQuestion(scope, side.string("question"), side.getString("session_id"), side.getString("stream_id"), side.string("text"), side.string("status"), side.string("cursor"), side.string("error"))
            if (client != null) resumeSideQuestion()
        }
        val session = cached["session"]?.let(::JSONObject)
        if (!current()) return false
        session?.let { renderSession(it, scope) }
        val receipt = cached["pending"]?.let(::JSONObject)
        if (!current()) return false
        if (receipt != null) {
            val send = PendingSend(receipt.getString("id"), scope, Draft.decode(receipt.getString("draft")))
            pending[scope.key] = send; _chat.update { it.copy(uncertain = send.id) }
        }
        return true
    }
    private suspend fun renderSession(response: JSONObject, scope: Scope, expectedRevision: Long? = null) {
        if (sourceScope != scope || expectedRevision != null && expectedRevision != chatHistoryRevision) return
        val o = (response.optJSONObject("session") ?: response).copy()
        val indexed = _ui.value.sessions.firstOrNull { it.string("session_id") == scope.session }?.opt("archived")
        val prior = _chat.value.takeIf { it.sessionId == scope.session }?.raw?.opt("archived")
        // Native live snapshots omit hidden state; preserve the native list/action receipt.
        (indexed as? Boolean ?: o.opt("archived") as? Boolean ?: prior as? Boolean)?.let { o.put("archived", it) }
        val userRows = o.rows("messages").filter { it.string("role") == "user" && it.string("row_id", it.string("_row_id")).isNotBlank() }
        val receipts = app.cache.readMany(scope, userRows.map { "attachments:${it.string("row_id", it.string("_row_id"))}" })
        for (row in userRows) {
            val id = row.string("row_id", row.string("_row_id"))
            receipts["attachments:$id"]?.let(::JSONObject)?.let { receipt -> row.put("attachments", receipt.optJSONArray("attachments")); row.put("display_text", receipt.string("display_text")) }
        }
        if (sourceScope != scope || expectedRevision != null && expectedRevision != chatHistoryRevision) return
        ++chatHistoryRevision
        renderedServerProjection = scope to response.toString()
        _chat.update { old ->
            val rows = o.rows("messages")
            val active = (o.opt("active_stream_id") as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,256}")) }
            val turn = currentTurnMessages(rows)
            val owned = historyToolIdentities(turn)
            val boundary = liveBoundaries["${scope.key}:${old.streamId}"] ?: LiveAssistantBoundary()
            val proof = persistedTurns["${scope.key}:${old.streamId}"]
            val finalRow = proof?.string("final_assistant_row_id").orEmpty()
            val historyOwnsLive = (finalRow.isNotBlank() && rows.any { it.string("row_id", it.string("_row_id")) == finalRow }) ||
                historyOwnsLiveBoundary(turn, old.liveTools, boundary.closed, boundary.toolIds)
            val streamEnded = historyOwnsLive || (!active.isNullOrBlank() && active != old.streamId)
            old.copy(projectionReady = true,
                approval = if (o.has("pending_approval")) o.optJSONObject("pending_approval") else old.approval,
                clarification = if (o.has("pending_clarification")) o.optJSONObject("pending_clarification") else old.clarification,
                sessionId = scope.session, title = o.string("title").ifBlank { "New conversation" }, messages = rows.mapIndexed { i, m -> Message.from(m, i, scope.session) }, raw = o,
                status = if (streamEnded) "" else if (active.isNullOrBlank() && old.streamId.isNotBlank()) old.status.ifBlank { "Turn status unknown" } else "",
                streamId = active?.takeIf { it.isNotBlank() } ?: if (streamEnded) "" else old.streamId,
                liveText = if (streamEnded) "" else old.liveText,
                liveReasoning = if (streamEnded) "" else old.liveReasoning,
                liveTools = if (streamEnded) emptyList() else old.liveTools.filterNot { toolIdentity(it).isNotBlank() && toolIdentity(it) in owned })
        }
        projectCallTranscripts()
    }
    private val persistedTurns = mutableMapOf<String, JSONObject>()
    private fun projectCallTranscripts(call: CallState = app.calls.state.value) {
        val scope = sourceScope
        val authorized = scope != null && scope.copy(session = "") == client?.scope
        _chat.update { state ->
            val archive = if (authorized && state.raw.has("voice_transcripts")) archivedVoiceTranscripts(scope!!, json("session_id" to state.raw.string("session_id"), "profile" to state.raw.string("profile"), "events" to state.raw.optJSONArray("voice_transcripts"))) else emptyList()
            val live = if (authorized && scope == call.conversationScope) call.transcriptsFor(scope, state.messages) else emptyList()
            state.copy(callMessages = mergeVoiceTranscripts(archive, live, state.messages.map { it.id }.toSet()))
        }
    }
    private fun reconcileCallConversation(call: CallState) {
        val scope = sourceScope ?: return
        val active = client ?: return
        if (scope != call.conversationScope || scope.copy(session = "") != active.scope || !foreground) return
        val request = ScopeSettlement(scope, active, originGeneration, sessionGeneration)
        callReconcile?.cancel()
        callReconcile = viewModelScope.launch {
            delay(100)
            try {
                val response = active.detached().get("/api/session", mapOf("session_id" to scope.session))
                if (!foreground || !request.current()) return@launch
                renderSession(response, scope)
                app.cache.write(scope, "session", response.toString())
            } catch (e: Throwable) { if (e is CancellationException) throw e }
        }
    }
    private fun restoreDraft(d: Draft, preserveActiveInput: Boolean = false) { if (!preserveActiveInput) { _composer.value = TextFieldValue(d.text, TextRange(d.selectionStart, d.selectionEnd)); _attachments.value = d.attachments }; _chat.update { it.copy(queued = d.queued, anchor = d.anchor, anchorOffset = d.anchorOffset, anchorId = d.anchorId) } }
    fun composer(value: TextFieldValue, fromEditor: Boolean = false) {
        if (value.text.length > 50_000) { notice("Draft is limited to 50,000 characters"); return }
        val previous = _composer.value.text
        _composer.value = value; checkpoint()
        if (value.text.isNotBlank() && client != null && canComposeInitialChat() && !initialCreateAttempts.containsKey(client?.scope?.key) && _ui.value.newChatError.isBlank() && !_ui.value.newChatOpening) {
            if (initialShells.containsKey((client?.scope ?: cachedOwnerScope)?.key)) resumeInitialChat() else createNewChat(json("worktree" to false), true)
        }
        val inserted = value.text.length - previous.length
        if (fromEditor && inserted >= 8192 && pasteJob?.isActive != true && _data.value["profile-settings"]?.optBoolean("large_text_paste_as_attachment") == true) {
            val scope = sourceScope ?: return; val c = client?.detached() ?: return
            val start = value.selection.start - inserted
            if (start < 0 || start + inserted > value.text.length || value.text.removeRange(start, start + inserted) != previous) return
            val pasted = value.text.substring(start, start + inserted)
            pasteJob = viewModelScope.launch {
                var file: File? = null
                try {
                    checkpointNow()
                    val prepared = withContext(Dispatchers.IO) { File.createTempFile("pasted", ".txt", app.cacheDir).also { file = it; it.writeText(pasted, Charsets.UTF_8); currentCoroutineContext().ensureActive() } }
                    val response = c.upload("/api/upload", prepared, "pasted-text.txt", "text/plain", scope.session)
                    if (sourceScope == scope && _composer.value.text == value.text) {
                        _attachments.update { it + response.rows("files").ifEmpty { listOf(response.optJSONObject("file") ?: response) } }
                        _composer.value = TextFieldValue(previous, TextRange(start.coerceAtMost(previous.length))); checkpoint()
                        notice("Large paste attached as text. Review it before sending.")
                    }
                } catch (e: Throwable) { if (sourceScope == scope) fail(e) } finally { file?.delete() }
            }
        }
    }
    fun anchor(index: Int, offset: Int, id: String) { _chat.update { it.copy(anchor = index, anchorOffset = offset, anchorId = id) }; checkpoint() }
    private fun draft(): Draft = Draft(_composer.value.text, _composer.value.selection.start, _composer.value.selection.end, _attachments.value, _chat.value.queued, _chat.value.anchor, _chat.value.anchorOffset, _chat.value.anchorId)
    private fun draftScope(): Scope? = if (!_chat.value.projectionReady) null else sourceScope ?: (client?.scope ?: cachedOwnerScope)?.takeIf { _ui.value.authenticated && _chat.value.sessionId.isBlank() }
    private fun draftKind(scope: Scope) = if (scope.session.isBlank()) "initial-draft" else "draft"
    private fun checkpoint() {
        val scope = draftScope() ?: return
        val draft = draft(); drafts[scope.key] = draft
        checkpointJob?.cancel()
        checkpointJob = viewModelScope.launch { delay(200); try { val encoded = draft.encode(); app.cache.write(scope, draftKind(scope), encoded, protected = draft.text.isNotBlank() || draft.attachments.isNotEmpty() || draft.queued.isNotEmpty()); savedDraft(scope, encoded) } catch (e: Throwable) { if (draftScope() == scope) fail(e) } }
    }
    suspend fun checkpointNow() { val scope = draftScope() ?: return; checkpointJob?.cancel(); val d = draft(); drafts[scope.key] = d; val encoded = d.encode(); app.cache.write(scope, draftKind(scope), encoded, d.text.isNotBlank() || d.attachments.isNotEmpty() || d.queued.isNotEmpty()); savedDraft(scope, encoded) }
    fun send(queued: QueuedMessage? = null) = viewModelScope.launch {
        if (_attachmentPreparation.value.isNotEmpty()) { notice("Finish or remove attachments before sending"); return@launch }
        if (!admitConversation()) return@launch
        val active = client ?: run { notice("Reconnect before sending. Your draft is retained."); return@launch }
        val scope = sourceScope ?: return@launch
        val c = if (clientCreatedSessions.contains(scope.key)) active else active.detached()
        val d = draft()
        if (_chat.value.raw.length() == 0) { notice("Wait for this conversation to load before sending"); return@launch }
        if (app.calls.state.value.active || _chat.value.approval != null || _chat.value.clarification != null) { notice("Finish the call or pending request before sending"); return@launch }
        if (_chat.value.raw.optBoolean("read_only") || _chat.value.raw.optBoolean("is_read_only")) { notice("This source is read-only. Branch into a new conversation to continue."); return@launch }
        if (d.text.isBlank() && d.attachments.isEmpty()) return@launch
        if (pending[scope.key] != null) { notice("Check the previous send receipt before sending again"); return@launch }
        if (_chat.value.streamId.isNotBlank()) { queue(); return@launch }
        val send = PendingSend.create(scope, d)
        pending[scope.key] = send
        try {
            app.cache.write(scope, "pending", send.encode(), true)
            if (sourceScope == scope) _chat.update { it.copy(uncertain = send.id, status = "Sending") }
            val model = queued?.model ?: _chat.value.raw.string("model")
            val provider = queued?.provider ?: _chat.value.raw.string("model_provider")
            val o = c.post("/api/chat/start", json("session_id" to scope.session, "message" to d.text, "attachments" to JSONArray(d.attachments), "profile" to scope.profile, "client_request_id" to send.id, "model" to model.ifBlank { null }, "model_provider" to provider.ifBlank { null }, "explicit_model_pick" to (queued != null || saved.get<Boolean>("model_pick:${scope.key}") == true)))
            accepted(scope, c, o, send)
        } catch (e: Throwable) { if (e is CancellationException) throw e; if (e is ApiFailure && e.status == 409) { try { reconcile(scope, c) } catch (receiptError: Throwable) { if (receiptError is CancellationException) throw receiptError; if (sourceScope == scope) fail(receiptError) }; return@launch }; if (e is ApiFailure && e.status in 400..499 && e.status !in setOf(408, 429)) { pending.remove(scope.key, send); optionalLocalSave { app.cache.delete(scope, "pending", send.encode()) }; if (sourceScope == scope && pending[scope.key] == null) _chat.update { it.copy(uncertain = "", status = "") } }; if (sourceScope == scope) fail(e) }
    }
    private suspend fun accepted(scope: Scope, c: CypherClient, result: JSONObject, send: PendingSend) {
        val sent = send.draft
        require(result.string("stream_id").isNotBlank()) { "Run was accepted but stream resolution is pending. Check receipt." }
        val canonical = result.string("session_id", scope.session)
        require(canonical == scope.session) { "Conversation identity changed; reconcile the receipt" }
        require(c.scope == scope.copy(session = "")) { "The receipt belongs to another account" }
        if (result.rows("attachment_receipts").isNotEmpty()) optionalLocalSave { app.cache.write(scope, "attachments-pending:${result.getString("stream_id")}", json("attachments" to result.optJSONArray("attachment_receipts"), "display_text" to sent.text).toString(), true) }
        if (result.has("user_row_id") && result.rows("attachment_receipts").isNotEmpty()) {
            optionalLocalSave { app.cache.write(scope, "attachments:${result.optLong("user_row_id")}", json("attachments" to result.optJSONArray("attachment_receipts"), "display_text" to sent.text).toString(), true) }
        }
        val owner = scope.copy(session = "")
        clientCreatedSessions.remove(scope.key)
        optionalLocalSave { app.cache.delete(scope, "client-created-shell") }
        publishConfirmedSession(owner, scope.session, result, accepted = true)
        if (client?.scope == owner) refreshSessions()
        if (initialShells[owner.key] == scope.session) { initialShells.remove(owner.key); optionalLocalSave { app.cache.delete(owner, "new-chat-shell") } }
        pending.remove(scope.key, send); optionalLocalSave { app.cache.delete(scope, "pending", send.encode()) }
        val current = drafts[scope.key]
        if (current != null && current.afterAccepted(sent) != current) {
            val cleared = current.afterAccepted(sent)
            drafts[scope.key] = cleared; optionalLocalSave { app.cache.write(scope, "draft", cleared.encode(), cleared.queued.isNotEmpty()) }
            if (sourceScope == scope) restoreDraft(cleared)
        }
        liveBoundaries["${scope.key}:${result.getString("stream_id")}"] = LiveAssistantBoundary()
        ++chatHistoryRevision
        if (sourceScope == scope) _chat.update { state ->
            val row = result.string("user_row_id")
            val receipt = result.optJSONArray("attachment_receipts") ?: JSONArray()
            val confirmed = json("row_id" to row, "role" to "user", "content" to sent.text, "attachments" to receipt)
            val append = row.isNotBlank() && state.messages.none { it.id == row }
            state.copy(messages = if (append) state.messages + Message.from(confirmed, state.messages.size, scope.session) else state.messages,
                raw = if (append) state.raw.copy().put("messages", JSONArray(state.raw.rows("messages") + confirmed)) else state.raw,
                uncertain = "", streamId = result.getString("stream_id"), status = "Working", liveText = "", liveReasoning = "", liveTools = emptyList())
        }
        attachStream(c, scope, result.getString("stream_id"))
    }
    fun reconcileSend() = viewModelScope.launch { val scope = sourceScope ?: return@launch; val c = client?.detached() ?: return@launch; try { reconcile(scope, c) } catch (e: Throwable) { if (sourceScope == scope) fail(e) } }
    private suspend fun reconcile(scope: Scope, c: CypherClient) {
        val send = pending[scope.key] ?: return
        val result = c.get("/api/session", mapOf("session_id" to scope.session))
        if (sourceScope != scope || client?.scope != c.scope || pending[scope.key]?.id != send.id) return
        renderSession(result, scope)
        _chat.update { it.copy(uncertain = send.id, status = "Send outcome unknown · review history") }
        notice("History refreshed. Review whether your message arrived, then explicitly mark reviewed or resend. Your draft is retained.")
    }
    fun reviewPendingSend(resend: Boolean = false) = viewModelScope.launch {
        val scope = sourceScope ?: return@launch
        val owner = client?.scope ?: cachedOwnerScope
        val unknown = pending[scope.key] ?: return@launch
        if (scope.copy(session = "") != owner) { notice("Reopen this conversation before reviewing the send"); return@launch }
        if (resend && client == null) { notice("Reconnect before resending. Your draft and send receipt are retained."); return@launch }
        try {
            if (!app.cache.delete(scope, "pending", unknown.encode())) { notice("The saved send receipt changed. Refresh the conversation before reviewing it."); return@launch }
        } catch (e: Exception) { if (e is CancellationException) throw e; if (sourceScope == scope) fail(e); return@launch }
        if (!pending.remove(scope.key, unknown) || sourceScope != scope || (client?.scope ?: cachedOwnerScope) != owner) return@launch
        _chat.update { it.copy(uncertain = "", status = "Draft retained · previous send reviewed") }
        if (resend) send() else notice("Previous send marked reviewed. Your draft is retained.")
    }

    fun queue() { if (!admitConversation()) return; if (_chat.value.uncertain.isNotBlank() || _chat.value.raw.optBoolean("read_only") || _chat.value.raw.optBoolean("is_read_only") || app.calls.state.value.active) { notice("Resolve the current conversation state before queuing"); return }; val text = _composer.value.text; if (text.isBlank() && _attachments.value.isEmpty()) return; val item = QueuedMessage(text, _attachments.value.toList(), _chat.value.raw.string("model"), _chat.value.raw.string("model_provider")); _chat.update { it.copy(queued = it.queued + item) }; _composer.value = TextFieldValue(); _attachments.value = emptyList(); checkpoint() }
    fun removeQueued(index: Int) { _chat.update { it.copy(queued = it.queued.filterIndexed { i, _ -> i != index }) }; checkpoint() }
    fun steer(): Job {
        val state = _chat.value
        if (!admitConversation() || state.streamId.isBlank() || state.uncertain.isNotBlank() || state.approval != null || state.clarification != null || state.raw.optBoolean("read_only") || state.raw.optBoolean("is_read_only") || app.calls.state.value.active || _attachments.value.isNotEmpty() || _composer.value.text.isBlank()) return viewModelScope.launch { notice("Guide requires a running turn and text only") }
        val text = _composer.value.text
        return action("/api/chat/steer", json("session_id" to state.sessionId, "text" to text), refresh = false) { _, response -> require(response.optBoolean("accepted")) { "Guidance was not accepted" }; if (_composer.value.text == text) composer(TextFieldValue()); notice("Guidance accepted") }
    }
    fun cancel() = viewModelScope.launch { val c = client ?: return@launch; try { c.get("/api/chat/cancel", mapOf("stream_id" to _chat.value.streamId)); notice("Stopping the current turn") } catch (e: Throwable) { fail(e) } }
    private fun attachStream(c: CypherClient, scope: Scope, stream: String) {
        if (!foreground || client?.scope != c.scope || scope.copy(session = "") != c.scope) return
        streams[scope.key]?.cancel()
        streams[scope.key] = viewModelScope.launch {
            val frameKey = "${scope.key}:$stream"
            val frame = liveFrames[frameKey] ?: app.cache.read(scope, "live:$stream")?.let(::JSONObject)
            val last = frame?.string("cursor").orEmpty()
            if (frame != null) liveBoundaries[frameKey] = LiveAssistantBoundary.from(frame)
            if (frame != null && sourceScope == scope) _chat.update { it.copy(liveText = frame.string("text"), liveReasoning = frame.string("reasoning"), liveTools = frame.rows("tools"), streamId = stream) }
            if (frame != null && sourceScope == scope && _chat.value.raw.has("messages") && _chat.value.raw.string("active_stream_id") == stream) renderSession(_chat.value.raw, scope)
            val cursor = ReplayCursor(stream, last)
            var lastCheckpoint = 0L
            var retry = 0
            var terminalError = frame?.string("error").orEmpty()
            var terminalObserved = false
            var terminalReplyVisible = false
            if (terminalError.isNotBlank() && sourceScope == scope) _ui.update { it.copy(error = terminalError) }
            try {
                while (isActive) {
                    try {
                        c.stream("/api/chat/stream", mapOf("stream_id" to stream, "session_id" to scope.session, "replay" to "1"), cursor.lastId) { event ->
                            if (cursor.accept(event)) {
                                if (event.type in setOf("done", "error")) terminalObserved = true
                                if (event.type == "done" && event.payload().string("status") == "interrupted") terminalError = "Interrupted"
                                if (event.type in setOf("apperror", "error")) terminalError = event.payload().string("message", event.payload().string("error", "Server reported a problem"))
                                retry = 0
                                event.payload().optJSONObject("persisted_turn")?.let { proof ->
                                    persistedTurns[frameKey] = proof
                                    val rowId = proof.string("user_row_id")
                                    if (rowId.isNotBlank()) app.cache.read(scope, "attachments-pending:$stream")?.let { receipt -> app.cache.write(scope, "attachments:$rowId", receipt, true); app.cache.delete(scope, "attachments-pending:$stream") }
                                }
                                applyEvent(scope, event)
                                if (sourceScope == scope) {
                                    val state = _chat.value
                                    val progress = (liveBoundaries[frameKey] ?: LiveAssistantBoundary()).saveInto(json("cursor" to cursor.lastId, "text" to state.liveText, "reasoning" to state.liveReasoning, "tools" to JSONArray(state.liveTools), "error" to terminalError))
                                    liveFrames[frameKey] = progress
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (now - lastCheckpoint >= 200 || event.type in setOf("done", "state_saved") || event.terminal) { app.cache.write(scope, "live:$stream", progress.toString()); lastCheckpoint = now }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403)) throw e
                        if (sourceScope == scope) _chat.update { it.copy(status = "Connection interrupted · reconnecting") }
                    }
                    if (terminalObserved) break
                    var active: String? = null
                    try {
                        val revision = chatHistoryRevision
                        val response = c.get("/api/session", mapOf("session_id" to scope.session))
                        if (sourceScope == scope && revision != chatHistoryRevision) continue
                        app.cache.write(scope, "session", response.toString()); renderSession(response, scope, revision)
                        active = ((response.optJSONObject("session") ?: response).opt("active_stream_id") as? String)
                            ?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,256}")) }
                        if (!active.isNullOrBlank() && active != stream) {
                            liveFrames.remove(frameKey); liveBoundaries.remove(frameKey); persistedTurns.remove(frameKey); app.cache.delete(scope, "live:$stream")
                            if (sourceScope == scope) _chat.update { it.copy(streamId = active, liveText = "", liveReasoning = "", liveTools = emptyList(), status = "Working") }
                            attachStream(c, scope, active)
                            return@launch
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403)) throw e
                    }
                    val liveStatus = try { c.nativeSessionStatus(scope.session, stream) }
                    catch (e: Throwable) {
                        if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403)) throw e
                        null
                    }
                    if (liveStatus == "idle") { terminalObserved = true; break }
                    if (sourceScope == scope) _chat.update { it.copy(status = when (liveStatus) {
                        "working" -> "Connection interrupted · turn still running · reconnecting"
                        "waiting" -> "Connection interrupted · turn waiting · reconnecting"
                        else -> "Turn status unknown · draft and queued messages are kept"
                    }) }
                    delay((500L shl retry.coerceAtMost(4)).coerceAtMost(8000)); retry++
                }
                // Both a terminal event and a validated idle read need the final saved projection.
                if (terminalObserved) {
                    val revision = chatHistoryRevision
                    try {
                        val response = c.get("/api/session", mapOf("session_id" to scope.session))
                        if (sourceScope == scope && client?.scope == c.scope && revision == chatHistoryRevision) {
                            app.cache.write(scope, "session", response.toString())
                            renderSession(response, scope, revision)
                            val snapshot = response.optJSONObject("session") ?: response
                            terminalReplyVisible = currentTurnMessages(snapshot.rows("messages")).any { it.string("role") == "assistant" }
                            if (!terminalReplyVisible) _chat.update { it.copy(status = terminalError.ifBlank { "Reply ended" } + " · final reply not confirmed; live output retained") }
                        } else if (sourceScope == scope && client?.scope == c.scope) {
                            _chat.update { it.copy(status = terminalError.ifBlank { "Reply ended" } + " · history changed during refresh; live output retained") }
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403)) throw e
                        if (sourceScope == scope && client?.scope == c.scope) _chat.update { it.copy(status = terminalError.ifBlank { "Reply ended" } + " · final history refresh failed; live output retained") }
                    }
                }
                if (terminalObserved) {
                    if (terminalReplyVisible) { liveFrames.remove(frameKey); liveBoundaries.remove(frameKey); persistedTurns.remove(frameKey); app.cache.delete(scope, "live:$stream") }
                    if (sourceScope == scope) {
                        _chat.update { releaseTerminalChat(it, stream, terminalReplyVisible, terminalError) }
                        pollPrompts(c, scope); refreshSessions()
                        if (terminalReplyVisible) {
                            val q = _chat.value.queued.firstOrNull()
                            if (q != null && _composer.value.text.isBlank() && _attachments.value.isEmpty()) { removeQueued(0); _attachments.value = q.attachments; composer(TextFieldValue(q.text)); send(q) }
                        }
                    }
                }
            } catch (e: Throwable) { if (e is CancellationException) throw e; if (sourceScope == scope) fail(e) } finally {
                if (terminalObserved && sourceScope == scope) _chat.update { releaseTerminalChat(it, stream, terminalReplyVisible, terminalError) }
                if (streams[scope.key] == currentCoroutineContext()[Job]) streams.remove(scope.key)
            }
        }
    }
    private fun applyEvent(scope: Scope, event: SseEvent) {
        if (sourceScope != scope) return
        val o = event.payload()
        if (o.string("session_id").isNotBlank() && o.string("session_id") != scope.session) return
        val boundaryKey = "${scope.key}:${_chat.value.streamId}"
        val boundary = liveBoundaries[boundaryKey] ?: LiveAssistantBoundary()
        val previousClosed = boundary.closed
        val nextBoundary = boundary.after(event.type, o)
        liveBoundaries[boundaryKey] = nextBoundary
        if (event.type in setOf("done", "state_saved") && o.optJSONObject("session")?.has("messages") == true) ++chatHistoryRevision
        if (event.type in setOf("apperror", "error")) _ui.update { it.copy(error = o.string("message", o.string("error", "Server reported a problem"))) }
        _chat.update { s -> when (event.type) {
            "token" -> s.copy(liveText = (if (previousClosed) "" else s.liveText) + o.string("text"), liveReasoning = if (previousClosed) "" else s.liveReasoning, status = "Responding")
            "reasoning" -> s.copy(liveText = if (previousClosed) "" else s.liveText, liveReasoning = (if (previousClosed) "" else s.liveReasoning) + o.string("text"), status = "Thinking")
            "tool", "tool_complete" -> {
                val turn = currentTurnMessages(s.raw.rows("messages"))
                val ownsBody = historyOwnsLiveBoundary(turn, emptyList(), nextBoundary.closed, nextBoundary.toolIds)
                s.copy(liveText = if (ownsBody) "" else s.liveText, liveReasoning = if (ownsBody) "" else s.liveReasoning,
                    liveTools = if (toolIdentity(o).isNotBlank() && toolIdentity(o) in historyToolIdentities(turn)) s.liveTools else mergeLiveTool(s.liveTools, o),
                    status = if (event.type == "tool") "Using ${o.string("name")}" else "Working")
            }
            "interim_assistant" -> interimAssistantBody(s.liveText, s.liveReasoning, o).let { body ->
                val owned = historyOwnsLiveBoundary(currentTurnMessages(s.raw.rows("messages")), emptyList(), nextBoundary.closed, nextBoundary.toolIds)
                s.copy(liveText = if (owned) "" else body.text, liveReasoning = if (owned) "" else body.reasoning)
            }
            "title" -> s.copy(title = o.string("title").ifBlank { s.title })
            "approval" -> s.copy(approval = o.optJSONObject("pending") ?: o.takeUnless { it.isNull("pending") })
            "clarify" -> s.copy(clarification = o.optJSONObject("pending") ?: o.takeUnless { it.isNull("pending") })
            "request_cancel" -> s.copy(approval = s.approval?.takeUnless { it.string("id") == o.string("id") }, clarification = s.clarification?.takeUnless { it.string("id") == o.string("id") })
            "todo_state" -> s.copy(raw = s.raw.copy().put("todo_state", o))
            "goal" -> s.copy(raw = s.raw.copy().put("goal", o))
            "done", "state_saved" -> { val session = o.optJSONObject("session"); if (session != null) { val merged = s.raw.copy(); session.keys().forEach { merged.put(it, session.opt(it)) }; s.copy(messages = if (session.has("messages")) session.rows("messages").mapIndexed { i, m -> Message.from(m, i, scope.session) } else s.messages, raw = merged, liveText = if (session.has("messages")) "" else s.liveText, liveReasoning = if (session.has("messages")) "" else s.liveReasoning, liveTools = if (session.has("messages")) emptyList() else s.liveTools, status = "Finishing") } else if (event.type == "done") s.copy(status = "Finishing") else s }
            "metering" -> s.copy(raw = s.raw.copy().put("native_usage", o.optJSONObject("usage") ?: s.raw.optJSONObject("native_usage")).put("native_tps", if (o.optBoolean("tps_available") && !o.optBoolean("estimated") && o.optDouble("tps", 0.0) > 0) o.optDouble("tps") else JSONObject.NULL))
            "compressing" -> s.copy(status = "Compressing context")
            "compressed" -> s.copy(status = "Context compressed")
            "warning", "apperror", "error" -> s.copy(status = o.string("message", o.string("error", "Server reported a problem")))
            else -> s
        } }
        if (event.type in setOf("done", "state_saved")) projectCallTranscripts()
    }
    private suspend fun pollPrompts(c: CypherClient, scope: Scope) {
        val approval = runCatching { c.get("/api/approval/pending", mapOf("session_id" to scope.session)).optJSONObject("pending") }.getOrNull()
        val clarify = runCatching { c.get("/api/clarify/pending", mapOf("session_id" to scope.session)).optJSONObject("pending") }.getOrNull()
        if (sourceScope == scope) _chat.update { it.copy(approval = approval, clarification = clarify) }
    }
    private fun watchSession(c: CypherClient, scope: Scope) {
        val active = client ?: return
        val request = ScopeSettlement(scope, active, originGeneration, sessionGeneration)
        if (!foreground || !request.current() || c.scope != active.scope) return
        sessionWatch?.cancel()
        sessionWatch = viewModelScope.launch {
            while (isActive && foreground && request.current()) {
                delay(15_000)
                if (streams[scope.key]?.isActive == true) continue
                try {
                    val revision = chatHistoryRevision
                    val response = c.get("/api/session", mapOf("session_id" to scope.session))
                    if (!foreground || !request.current() || revision != chatHistoryRevision) continue
                    if (renderedServerProjection != (scope to response.toString())) {
                        renderSession(response, scope, revision)
                        app.cache.write(scope, "session", response.toString())
                    }
                    val session = response.optJSONObject("session") ?: response
                    if (session.rows("messages").isNotEmpty() || session.optInt("message_count") > 0 || session.string("active_stream_id").isNotBlank()) {
                        clientCreatedSessions.remove(scope.key)
                        optionalLocalSave { app.cache.delete(scope, "client-created-shell") }
                    }
                    val running = response.string("active_stream_id")
                    if (running.isNotBlank() && streams[scope.key]?.isActive != true) {
                        pollPrompts(c.detached(), scope)
                        attachStream(c.detached(), scope, running)
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    if (e is ApiFailure && e.status == 404 && sessionCanStillBeUnpersisted(clientCreatedSessions.contains(scope.key), _chat.value)) continue
                    if (e is ApiFailure && e.status in setOf(401, 403) || missingNativeConversation(e)) {
                        if (foreground && request.current()) {
                            if (e is ApiFailure && e.status == 401) fail(e)
                            else _chat.update { it.copy(status = "Conversation unavailable · draft retained") }
                        }
                        break
                    }
                }
            }
        }
    }
    private fun acceptsPrompt(shown: JSONObject, pending: JSONObject?, scope: Scope, idKey: String): Boolean {
        if (client == null) { notice("Reconnect before responding. Your answers are kept."); return false }
        val id = shown.string(idKey, shown.string("id"))
        val current = scope == sourceScope && scope.session == _chat.value.sessionId && scope.copy(session = "") == client?.scope &&
            id.isNotBlank() && id == pending?.string(idKey, pending.string("id"))
        if (!current) notice("This request changed. Refresh it before responding.")
        return current
    }
    private fun retirePrompt(scope: Scope, idKey: String, id: String) {
        if (id.isBlank() || sourceScope != scope || _chat.value.sessionId != scope.session || scope.copy(session = "") != client?.scope) return
        _chat.update { state ->
            if (state.sessionId != scope.session) state else when (idKey) {
                "approval_id" -> state.approval?.takeIf { it.string(idKey, it.string("id")) == id }?.let { state.copy(approval = null) } ?: state
                "clarify_id" -> state.clarification?.takeIf { it.string(idKey, it.string("id")) == id }?.let { state.copy(clarification = null) } ?: state
                else -> state
            }
        }
    }
    fun approval(choice: String, shown: JSONObject, scope: Scope) {
        val pending = _chat.value.approval
        if (!acceptsPrompt(shown, pending, scope, "approval_id")) return
        if (choice !in pending?.optJSONArray("choices")?.strings().orEmpty()) { notice("This approval choice was not offered by Hermes"); return }
        val id = shown.string("approval_id", shown.string("id"))
        action("/api/approval/respond", json("session_id" to scope.session, "scope_key" to scope.key, "choice" to choice, "approval_id" to id), successNotice = "", onSuccess = { _, _ -> retirePrompt(scope, "approval_id", id) })
    }
    fun clarify(answer: String, shown: JSONObject, scope: Scope) {
        if (!acceptsPrompt(shown, _chat.value.clarification, scope, "clarify_id")) return
        val id = shown.string("clarify_id", shown.string("id"))
        action("/api/clarify/respond", json("session_id" to scope.session, "scope_key" to scope.key, "response" to answer, "clarify_id" to id), successNotice = "", onSuccess = { _, _ -> retirePrompt(scope, "clarify_id", id) })
    }
    fun clarifyBatch(answers: JSONObject, shown: JSONObject, scope: Scope) {
        if (!acceptsPrompt(shown, _chat.value.clarification, scope, "clarify_id")) return
        val id = shown.string("clarify_id", shown.string("id"))
        action("/api/clarify/respond", json("session_id" to scope.session, "scope_key" to scope.key, "answers" to answers, "clarify_id" to id), successNotice = "", onSuccess = { _, _ -> retirePrompt(scope, "clarify_id", id) })
    }
    fun sessionAction(path: String, body: JSONObject = JSONObject(), callback: (suspend (JSONObject) -> Unit)? = null) { if (!admitConversation()) return; if (app.calls.state.value.active && app.calls.state.value.conversationScope == sourceScope && path in setOf("/api/session/delete", "/api/session/undo", "/api/session/compress")) { notice("End this call before changing its conversation."); return }; body.put("session_id", _chat.value.sessionId); action(path, body) { _, o -> callback?.invoke(o) } }
    private suspend fun retainSideQuestion() { val side = _side.value ?: return; app.cache.write(side.scope, "side-question", json("question" to side.question, "session_id" to side.sessionId, "stream_id" to side.streamId, "text" to side.text, "status" to side.status, "cursor" to side.cursor, "error" to side.error).toString(), side.status !in setOf("Complete", "Stopped")) }
    fun resumeSideQuestion(): Job? {
        val side = _side.value ?: return null; val c = client?.detached() ?: return null
        if (side.scope != sourceScope || side.scope.copy(session = "") != c.scope) return null
        sideJob?.cancel()
        sideJob = viewModelScope.launch {
            val cursor = ReplayCursor(side.streamId, side.cursor)
            var terminalError = side.error
            try {
                try {
                    c.stream("/api/chat/stream", mapOf("session_id" to side.sessionId, "stream_id" to side.streamId, "replay" to "1"), cursor.lastId) { event ->
                        if (_side.value?.scope != side.scope || _side.value?.streamId != side.streamId || !cursor.accept(event)) return@stream
                        val p = event.payload()
                        if (event.type in setOf("error", "apperror")) terminalError = p.string("message", p.string("error", "Server reported a problem"))
                        _side.update { old -> old?.copy(cursor = cursor.lastId, error = terminalError, text = when (event.type) { "token" -> old.text + p.string("text"); "done", "state_saved" -> p.optJSONObject("session")?.rows("messages")?.lastOrNull { it.string("role") == "assistant" }?.string("content") ?: old.text; else -> old.text }, status = when (event.type) { "stream_end" -> "Complete"; "cancel" -> "Stopped"; "error", "apperror" -> terminalError; else -> old.status }) }
                        sideCheckpointJob?.cancel(); sideCheckpointJob = viewModelScope.launch { delay(200); runCatching { retainSideQuestion() }.onFailure(::fail) }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403)) throw e
                }
                val response = c.get("/api/session", mapOf("session_id" to side.sessionId))
                if (_side.value?.scope != side.scope || _side.value?.streamId != side.streamId) return@launch
                val session = response.optJSONObject("session") ?: response
                val active = session.string("active_stream_id")
                val text = session.rows("messages").lastOrNull { it.string("role") == "assistant" }?.string("content")
                _side.update { it?.copy(error = if (active.isNotBlank() && active != side.streamId) "" else terminalError, text = if (active.isNotBlank() && active != side.streamId) "" else if (active.isBlank()) text ?: it.text else it.text, streamId = active.ifBlank { it.streamId }, cursor = if (active.isNotBlank() && active != side.streamId) "" else it.cursor, status = if (active.isNotBlank() && active != side.streamId) "Responding" else terminalError.ifBlank { if (active.isBlank()) { if (it.status == "Stopped") "Stopped" else "Complete" } else "Stream closed · reconnect to reconcile" }) }
                retainSideQuestion()
                if (active.isNotBlank() && active != side.streamId) resumeSideQuestion()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (e is ApiFailure && e.status in setOf(401, 403)) fail(e)
                else if (_side.value?.scope == side.scope && _side.value?.streamId == side.streamId) _side.update { it?.copy(status = terminalError.ifBlank { "${e.message.orEmpty()} · reconnect available" }) }
            }
        }
        return sideJob
    }

    fun closeSideQuestion() { sideJob?.cancel(); sideCheckpointJob?.cancel(); val side = _side.value; _side.value = null; if (side != null) viewModelScope.launch { app.cache.delete(side.scope, "side-question") } }
    fun ensureLoaded(key: String, path: String, query: Map<String, String> = emptyMap()): Job = load(key, path, query, force = false)
    fun load(key: String, path: String, query: Map<String, String> = emptyMap(), force: Boolean = true): Job {
        val active = client ?: return viewModelScope.launch { storeData(key, (_data.value[key]?.copy() ?: JSONObject()).apply { remove("_loading"); put("_error", "Reconnect to refresh this page.") }) }
        val c = active.detached(); val scope = c.scope; val generation = originGeneration
        val request = "${scope.key}:$generation:$path:${query.toSortedMap()}"
        if (loadRequests[key] == request) loads[key]?.takeIf { it.isActive }?.let { return it }
        loads[key]?.cancel()
        loadRequests[key] = request
        val session = query["session_id"]
        val cacheable = (query - "profile").isEmpty() && (query["profile"] == null || query["profile"] == scope.profile) && startupReads.any { it.path == path }
        val cacheKey = "read:$path"
        fun visible() = client === active && generation == originGeneration && _ui.value.authenticated && loadRequests[key] == request && (session == null || sourceScope?.session == session)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
          try {
            if (!force && cacheable) {
                val invalidatedAt = readInvalidations[scope.key] ?: (app.cache.read(scope, "read-invalidated-at")?.toLongOrNull() ?: 0L).also { readInvalidations[scope.key] = it }
                val at = loadedAt[request] ?: 0L
                if (visible() && _data.value[key]?.has("_error") == false && _data.value[key]?.optLong("_cached_at") == at && _data.value[key]?.string("_request_key") == request && startupReadFresh(at, System.currentTimeMillis(), invalidatedAt)) return@launch
                val cached = app.cache.read(scope, cacheKey)?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (!visible()) return@launch
                cached?.optJSONObject("value")?.let { value ->
                    val cachedAt = cached.optLong("at")
                    val fresh = startupReadFresh(cachedAt, System.currentTimeMillis(), invalidatedAt)
                    loadedAt[request] = cachedAt
                    _data.update { it + (key to value.copy().put("_stale", !fresh).put("_cached_at", cachedAt).put("_request_key", request)) }
                    if (fresh) return@launch
                }
            }
            _refreshing.update { it + key }
            val response = c.get(path, query, maxBytes = if (cacheable) 1024 * 1024 else 32 * 1024 * 1024)
            if (!visible()) return@launch
            val at = System.currentTimeMillis()
            if (cacheable) app.cache.write(scope, cacheKey, json("at" to at, "value" to response).toString())
            if (key == "profile-settings") app.cache.write(scope, "profile-settings", response.toString())
            if (visible()) { loadedAt[request] = at; _data.update { it + (key to if (cacheable) response.copy().put("_cached_at", at).put("_request_key", request) else response) }; _ui.update { it.copy(connected = true) } }
          } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (visible()) {
                _data.update { values -> values + (key to ((if (cacheable) values[key]?.copy() else null) ?: JSONObject()).put("_error", e.message ?: "Unavailable").apply { if (cacheable && length() > 1) put("_stale", true); if (path == "/api/commands/catalog") { put("available", false); if (!has("commands")) put("commands", JSONArray()) } }) }
                if (force || !cacheable || e is ApiFailure && e.status == 401) fail(e)
            }
          } finally { c.closeNative(); if (loads[key] === currentCoroutineContext()[Job]) _refreshing.update { it - key } }
        }
        loads[key] = job; job.start(); return job
    }
    fun action(path: String, body: JSONObject = JSONObject(), method: String = "POST", refresh: Boolean = true, successNotice: String = "Saved", onSuccess: (suspend (CypherClient, JSONObject) -> Unit)? = null) = viewModelScope.launch {
        val submission = _form.value?.takeIf { it.path == path && it.id == _submittingForm.value }?.id
        val active = client; val generation = originGeneration; val selection = sessionGeneration
        val c = active?.detached() ?: run { notice("Reconnect before saving changes."); return@launch }; val scope = c.scope; val selected = sourceScope
        fun current() = client === active && originGeneration == generation && (!body.has("session_id") || sourceScope == selected && sessionGeneration == selection)
        val removing = path == "/api/session/delete"
        var committed = false
        try {
            if (path in setOf("/api/session/delete", "/api/session/undo", "/api/session/compress")) {
                require(selected != null && body.string("session_id") == selected.session) { "Reopen this action in its original conversation." }
                require(!(app.calls.state.value.active && app.calls.state.value.conversationScope == selected)) { "End this call before changing its conversation." }
            }
            if (removing) { require(_chat.value.streamId.isBlank()) { "Stop the current reply before deleting this conversation." }; sessionWatch?.cancelAndJoin() }
            val result = c.call(path, method = method, data = body)
            if (result.has("ok") && !result.optBoolean("ok")) error(result.string("message", result.string("status", "Operation was not accepted")))
            committed = true
            if (removing && selected != null) {
                clientCreatedSessions.remove(selected.key)
                optionalLocalSave { app.cache.delete(selected, "client-created-shell") }
            }
            if (current()) {
                if (path == "/api/session/archive" && selected != null) {
                    val archived = result.getBoolean("hidden")
                    _chat.update { it.copy(raw = it.raw.copy().put("archived", archived)) }
                    _ui.update { it.copy(sessions = it.sessions.map { row -> if (row.string("session_id") == selected.session) row.copy().put("archived", archived) else row }) }
                }
                if (path != "/api/profile/switch") app.auth.persist(c.auth)
                val invalidation = System.currentTimeMillis(); readInvalidations[scope.key] = invalidation
                val cacheError = runCatching { app.cache.write(scope, "read-invalidated-at", invalidation.toString()) }.exceptionOrNull()
                val message = if (path == "/api/session/update" && result.optBoolean("deferred")) "Model saved for the next reply" else successNotice
                _ui.update { it.copy(connected = true, error = "", notice = if (cacheError == null) message else "$message. Refresh this page; the local settings cache could not be updated.") }
                onSuccess?.invoke(c, result)
                if (refresh && current() && selected != null) {
                    val revision = chatHistoryRevision; val response = if (path == "/api/session/update" && result.has("session")) result else c.get("/api/session", mapOf("session_id" to selected.session))
                    if (current() && revision == chatHistoryRevision) { renderSession(response, selected, revision)
                        val running = _chat.value.streamId
                        if (running.isNotBlank() && streams[selected.key]?.isActive != true) attachStream(c.detached(), selected, running)
                        if (current()) refreshSessions() }
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (current() && e is NativeModelConfirmation && selected != null) {
                closeCatalog()
                openForm(dev.hermes.android.ui.ActionForm("Confirm model change", path, emptyList(), body.copy().put("confirm_expensive_model", true).put("scope_key", selected.key), explanation = "Model: ${body.string("model")}\n\n${e.message.orEmpty()}", actionLabel = "Use model"))
            } else if (current()) { _form.update { if (submission != null && it?.id == submission) it.copy(error = e.message ?: "Could not save. Try again.") else it }; fail(e) }
        } finally {
            c.closeNative()
            if (removing && !committed && current() && selected != null) watchSession(active.detached(), selected)
        }
    }
    fun upload(uri: Uri, workspace: Boolean = false, directory: String = "."): Job {
        val id = java.util.UUID.randomUUID().toString()
        val active = client
        val scope = if (workspace) pcScope() else sourceScope
        val listingRequest = if (workspace) loadRequests["files"] else null
        val listingDirectory = _data.value["files"]?.takeIf { workspace && it.has("_requested_path") }?.string("_requested_path") ?: directory
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            if (active == null || scope == null) return@launch
            val c = active.detached()
            var name = "Attachment"
            fun current() = client === active && (if (workspace) pcScope() else sourceScope) == scope && uploadJobs.containsKey(id)
            try {
                val resolver = app.contentResolver
                name = withContext(Dispatchers.IO) { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }?.takeIf(String::isNotBlank) ?: "Attachment"
                if (!current()) return@launch
                if (!workspace) _attachmentPreparation.update { it + json("id" to id, "name" to name, "status" to "preparing") }
                uploadSlots.withPermit {
                    if (!current()) return@withPermit
                    val mime = resolver.getType(uri) ?: "application/octet-stream"
                    val file = File.createTempFile("upload", ".data", app.cacheDir)
                    try {
                        withContext(Dispatchers.IO) {
                            resolver.openInputStream(uri)?.use { input -> file.outputStream().use { out ->
                                val bytes = ByteArray(32768); var total = 0L
                                while (true) {
                                    ensureActive()
                                    val n = input.read(bytes); if (n < 0) break
                                    total += n; require(total <= 32L * 1024 * 1024) { "Attachment exceeds 32 MiB" }
                                    out.write(bytes, 0, n)
                                }
                            } } ?: error("Could not read attachment")
                        }
                        val response = c.upload(if (workspace) "/api/workspace/upload" else "/api/upload", file, name, mime, scope.session, directory)
                        if (current()) {
                            if (workspace) {
                            notice("File uploaded")
                            if (loadRequests["files"] == listingRequest) {
                                loads["files"]?.cancelAndJoin()
                                if (current() && loadRequests["files"] == listingRequest) load("files", "/api/pc/files", mapOf("path" to listingDirectory)).join()
                            }
                        }
                            else {
                                val items = response.rows("files").ifEmpty { listOf(response.optJSONObject("file") ?: response) }
                                _attachments.update { it + items }
                                _attachmentPreparation.update { it.filterNot { row -> row.string("id") == id } }
                                checkpoint()
                            }
                        }
                    } finally { file.delete() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (current()) {
                    if (workspace || e is ApiFailure && e.status in setOf(401, 403)) fail(e)
                    else _attachmentPreparation.update { rows -> rows.filterNot { it.string("id") == id } + json("id" to id, "name" to name, "status" to "error", "error" to (e.message ?: "Couldn't prepare attachment. Remove it and choose the file again.")) }
                }
            } finally {
                uploadJobs.remove(id)
                _attachmentPreparation.update { rows -> rows.filterNot { it.string("id") == id && it.string("status") != "error" } }
                c.closeNative()
            }
        }
        uploadJobs[id] = job
        job.invokeOnCompletion { uploadJobs.remove(id) }
        job.start()
        return job
    }
    fun dismissAttachmentPreparation(id: String) {
        uploadJobs.remove(id)?.cancel()
        _attachmentPreparation.update { it.filterNot { row -> row.string("id") == id } }
    }
    fun removeAttachment(index: Int) { _attachments.update { it.filterIndexed { i, _ -> i != index } }; checkpoint() }
    fun dictated(text: String, append: Boolean = true) { val next = if (append) (_composer.value.text + " " + text).trim() else text; composer(TextFieldValue(next, TextRange(next.length))) }
    fun authenticatedScope(): Scope? = sourceScope
    fun followNativeReference(reference: NativeReference, origin: Scope): Job {
        val active = client
        val generation = originGeneration
        var selection = sessionGeneration
        fun owned() = active != null && client === active && originGeneration == generation && sessionGeneration == selection && active.scope == origin.copy(session = "")
        return viewModelScope.launch {
            if (!owned()) return@launch
            when (reference) {
                is NativeReference.Conversation -> { tab("Chat"); selectSession(reference.sessionId) }
                is NativeReference.Workspace -> {
                    tab("Spaces")
                    if (sourceScope != origin) { selectSession(origin.session); selection = sessionGeneration; selectionJob?.join() }
                    if (owned() && sourceScope == origin) openFile(reference.path)
                }
                else -> Unit
            }
        }
    }
    fun pcFolder(): String = saved.get<String>("pc-folder-scope").takeIf { it == client?.scope?.key }?.let { saved.get<String>("pc-folder") }.orEmpty()
    fun rememberPcFolder(path: String) { saved["pc-folder-scope"] = client?.scope?.key; saved["pc-folder"] = path }
    fun browsePcFolder(path: String) { rememberPcFolder(path); tab("Spaces") }
    fun pcScope(): Scope? = client?.scope ?: cachedOwnerScope
    fun openFile(path: String, pc: Boolean = false): Job {
        val token = ++editorReadGeneration
        var target = path
        val active = client
        val scope = if (pc) pcScope() else sourceScope
        val request = if (active != null && scope != null) ScopeSettlement(scope, active, originGeneration, sessionGeneration) else null
        val c = active?.detached()
        val generation = originGeneration
        fun current() = token == editorReadGeneration && (if (pc) client === active && generation == originGeneration && pcScope() == scope else request?.current() == true)
        return viewModelScope.launch {
            if (request == null || c == null || !current()) return@launch
            try {
                checkpointEditorNow()
                if (!current()) return@launch
                target = if (pc) path else NativeOperations(c).workspacePath(request.scope.session, path)
                if (!current()) return@launch
                _editor.value = EditorState(request.scope, target, loading = true)
                val savedEditor = app.cache.read(request.scope, "editor:$target")?.let(::JSONObject)
                if (!current() || _editor.value.path != target) return@launch
                val metadata = c.get(if (pc) "/api/pc/file" else "/api/file", mapOf("session_id" to request.scope.session, "path" to target))
                if (!current() || _editor.value.scope != request.scope || _editor.value.path != target) return@launch
                val serverText = metadata.string("content")
                val draft = restoreEditorDraft(savedEditor, serverText)
                _editor.value = EditorState(request.scope, target, TextFieldValue(draft.text, TextRange(draft.start, draft.end)), draft.base, metadata,
                    reviewRequired = draft.reviewRequired)
            } catch (e: Throwable) { if (current()) { if (_editor.value.scope == request.scope && _editor.value.path == target) _editor.update { it.copy(loading = false, error = e.message.orEmpty()) }; fail(e) } } finally { c.closeNative() }
        }
    }
    fun editorText(text: TextFieldValue) { _editor.update { it.copy(text = text) }; editorCheckpoint?.cancel(); val editor = _editor.value; editorCheckpoint = viewModelScope.launch { try { delay(200); retainEditor(editor) } catch (e: Throwable) { if (sourceScope == editor.scope) fail(e) } } }
    private suspend fun retainEditor(editor: EditorState) {
        val scope = editor.scope ?: return
        if (editor.path.isBlank() || editor.loading) return
        editorCacheLock.withLock {
                val snapshot = editorDraftSnapshot(editor.text.text, editor.serverText, editor.text.selection.start, editor.text.selection.end, editor.reviewRequired)
                if (snapshot == null) app.cache.delete(scope, "editor:${editor.path}")
                else app.cache.write(scope, "editor:${editor.path}", snapshot.toString(), protected = true)
        }
    }
    private suspend fun checkpointEditorNow() { editorCheckpoint?.cancelAndJoin(); retainEditor(_editor.value) }
    fun discardEditor() = viewModelScope.launch {
        val e = _editor.value
        try {
            editorCheckpoint?.cancelAndJoin()
            editorCacheLock.withLock { if (e.scope != null) app.cache.delete(e.scope, "editor:${e.path}") }
            _editor.update { if (it.scope == e.scope && it.path == e.path && it.text == e.text)
                it.copy(text = TextFieldValue(e.metadata.string("content", e.serverText)), serverText = e.metadata.string("content", e.serverText), error = "", reviewRequired = false) else it }
        } catch (failure: Throwable) { if (_editor.value.scope == e.scope) fail(failure) }
    }
    fun acceptEditorBase() {
        val e = _editor.value
        if (!e.reviewRequired || e.loading || e.saving) return
        _editor.value = e.copy(serverText = e.metadata.string("content", e.serverText), reviewRequired = false, error = "")
        editorText(_editor.value.text)
    }
    fun closeEditor(): Job {
        ++editorReadGeneration
        return viewModelScope.launch {
        val e = _editor.value
        try { checkpointEditorNow(); if (_editor.value.scope == e.scope && _editor.value.path == e.path) _editor.value = EditorState() }
        catch (failure: Throwable) { if (_editor.value.scope == e.scope) fail(failure) }
    }
    }
    fun saveEditor() = viewModelScope.launch {
        val editor = _editor.value; val scope = editor.scope ?: return@launch
        if (editor.loading || editor.saving || editor.reviewRequired) return@launch
        val c = client?.detached() ?: return@launch
        try {
            require(scope.copy(session = "") == c.scope) { "Reopen the editor in its authorized profile" }
            _editor.update { it.copy(saving = true) }; retainEditor(editor.copy(saving = true))
            val response = c.post(if (editor.metadata.optBoolean("_pc")) "/api/pc/file/save" else if (editor.metadata.string("preview_kind") == "office") "/api/file/office-save" else "/api/file/save", json("session_id" to scope.session, "path" to editor.path, "content" to editor.text.text, "expected_content" to editor.serverText))
            if (_editor.value.scope == scope && _editor.value.path == editor.path) _editor.update {
                val confirmed = response.string("content", editor.text.text)
                it.copy(serverText = confirmed, text = if (it.text.text == editor.text.text) TextFieldValue(confirmed, TextRange(it.text.selection.start.coerceAtMost(confirmed.length), it.text.selection.end.coerceAtMost(confirmed.length))) else it.text, saving = false, error = "", metadata = if (response.has("content")) response else it.metadata.copy().put("content", confirmed))
            }
            val visible = _editor.value
            try {
                if (visible.scope == scope && visible.path == editor.path) retainEditor(visible) else editorCacheLock.withLock {
                    val retained = app.cache.read(scope, "editor:${editor.path}")?.let(::JSONObject)
                    if (retained == null || retained.string("text") == editor.text.text) app.cache.delete(scope, "editor:${editor.path}")
                }
                if (sourceScope == scope) notice("File saved")
            } catch (cacheError: Throwable) {
                if (cacheError is CancellationException) throw cacheError
                if (sourceScope == scope) notice("File saved on your computer; its local draft could not be updated.")
            }
        } catch (e: Throwable) { if (_editor.value.scope == scope && _editor.value.path == editor.path) { _editor.update { it.copy(saving = false, error = "Save not confirmed. The local edit is retained. ${e.message.orEmpty()}") }; fail(e) } }
    }
    fun exportFile(uri: Uri, path: String, folder: Boolean = false, pc: Boolean = false) = viewModelScope.launch {
        val scope = (if (pc) pcScope() else sourceScope) ?: return@launch
        val c = client?.detached() ?: return@launch
        fun current() = client?.scope == c.scope && (if (pc) pcScope() else sourceScope) == scope
        try {
            exportDownload(c, uri, if (pc) "/api/files/download" else if (folder) "/api/folder/download" else "/api/file/raw", mapOf("session_id" to scope.session, "path" to path), 256L * 1024 * 1024)
            if (current()) notice("Download saved")
        } catch (e: Throwable) { if (current()) fail(e) } finally { c.closeNative() }
    }

    fun readAloud(text: String) {
        if (app.calls.state.value.active) { notice("End the current call before starting read aloud."); return }
        stopReadAloud()
        if (text.isBlank()) return
        val sequence = readSequence
        val source = sourceScope
        _readingAloud.value = true
        fun speak() { if (readSequence != sequence || sourceScope != source) return; speech?.language = java.util.Locale.forLanguageTag(preferences.value.language); if (speech?.speak(text.take(5000), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, sequence.toString()) == android.speech.tts.TextToSpeech.ERROR) { _readingAloud.value = false; notice("Android read aloud failed") } }
        if (speechReady) { speak(); return }
        speechPending = ::speak
        if (speech == null) speech = android.speech.tts.TextToSpeech(app) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                speechReady = true
                speech?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { finished(utteranceId, false) }
                    @Deprecated("Android callback") override fun onError(utteranceId: String?) { finished(utteranceId, true) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { finished(utteranceId, false) }
                    private fun finished(id: String?, error: Boolean) { viewModelScope.launch { if (id == readSequence.toString()) { _readingAloud.value = false; if (error) notice("Android read aloud failed") } } }
                })
                speechPending?.invoke(); speechPending = null
            } else { speechPending = null; _readingAloud.value = false; speech?.shutdown(); speech = null; notice("Android read aloud is unavailable on this device") }
        }
    }
    fun stopReadAloud() { readSequence++; speechPending = null; speech?.stop(); _readingAloud.value = false }
    fun declineIncoming(source: String, event: String) = viewModelScope.launch {
        if (_incomingBusy.value) return@launch
        _incomingBusy.value = true
        try { dev.hermes.android.voice.declineIncoming(app, source, event); notice("Cypher invitation declined. The result stays in Cypher history.") }
        catch (e: Throwable) { if (e !is CancellationException) fail(e) }
        finally { _incomingBusy.value = false }
    }
    fun handlePush(source: String, event: String, action: String = "") = viewModelScope.launch {
        val startsCall=action in setOf("answer","callback")
        if (startsCall) { if (_incomingBusy.value) return@launch; _incomingBusy.value = true }
        var generation = -1
        try {
            val credentialVersion=app.auth.authorityEpoch.readVersion()
            require(dev.hermes.android.push.validOpaque(source) && dev.hermes.android.push.validOpaque(event)) { "Notification route is invalid" }
            val binding = app.push.binding(source) ?: error("Sign in to the owner account before opening this notification")
            val origin = trustedOrigin(binding.getString("origin"))
            val owner = binding.getString("owner")
            val notificationScope = Scope(origin, owner, binding.string("profile", "default"))
            require(app.auth.read(origin).authorizesPushSource(notificationScope)) { "Sign in to the notification's owner account before opening this activity" }
            modeTransition?.cancelAndJoin()
            generation = ++originGeneration
            cancelStartup()
            resetAppUpdate()
            cancelNewChatWork()
            val c = app.push.clientFor(binding)
            val bootstrap = c.bootstrap()
            require(c.auth.owner == owner && c.auth.profile == binding.string("profile", "default")) { "Notification authorization no longer matches this account" }
            val route = c.nativeRequest("/api/plugins/cypher/mobile/notifications/events/$event")
            require(route.string("profile") == c.auth.profile && route.string("source_ref") == source) { "Notification belongs to another profile" }
            require(app.push.binding(source) != null && app.auth.read(origin).authorizesPushSource(notificationScope)) { "The notification's owner signed out" }
            if (action=="callback") dev.hermes.android.voice.requireMissedCallbackRoute(route,requireNotNull(app.push.missedInvitation(source,event)) { "This missed call is no longer available" },c.scope)
            else if (dev.hermes.android.voice.incomingRouteEnded(route, source, c.auth.profile)) app.push.discardInvitation(source, event)
            if (action == "answer") dev.hermes.android.voice.requireIncomingRoute(route, source, c.auth.profile)
            val id = route.string("session_id")
            if (generation != originGeneration) return@launch
            checkpointVisible()
            if (generation != originGeneration) return@launch
            clearDetails(); retireSavedDrafts(null); sessionWatch?.cancel(); selectionJob?.cancel(); sourceScope = null; ++sessionGeneration
            if (app.calls.state.value.active && !keepsRestoredCall(app.calls.state.value.conversationScope,c.scope,false)) app.calls.hangup()
            require(persistAuthIfCurrent(c.auth, credentialVersion)) { "Sign-in changed while opening this notification" }
            if (generation != originGeneration) return@launch
            client = c.detached(); cachedOwnerScope = null
            val restoredClient = requireNotNull(client)
            restoreBotMode(restoredClient.scope)
            if (generation != originGeneration || client !== restoredClient) return@launch
            restorePhoneSettings(restoredClient)
            if (generation != originGeneration || client !== restoredClient) return@launch
            app.updates.authorize(requireNotNull(client)); restoreAppUpdate()
            _data.value = emptyMap()
            _chat.value = ChatState(); _composer.value = TextFieldValue(); _attachments.value = emptyList()
            val destination = when { route.string("kind") == "incoming_call" -> "Xara"; route.string("kind") == "release" -> "More"; route.string("kind") == "task_result" && id.isBlank() -> "Tasks"; else -> "Chat" }
            val destinationPage = if (route.string("kind") == "release") "App updates" else ""
            saved["tab"] = destination; saved["more"] = destinationPage
            _ui.update { it.copy(origin = origin, owner = owner, profile = c.auth.profile, xaraOpening = false, xaraError = "", newChatOpening = false, newChatError = "", initialChatReady = false, query = "", authenticated = true, connected = true, restoringSession = false, bootstrap = bootstrap, tab = destination, morePage = destinationPage) }
            prepareStartup()
            if (route.string("kind") == "incoming_call") {
                if (action!="callback") { if (route.string("state") == "pending") app.push.presentInvitation(source, event) else app.push.discardInvitation(source, event) }
                openXara()
                if (action == "answer" && generation == originGeneration) {
                    stopReadAloud()
                    require(app.push.authorizeKeepConnectedAction(source, event, "answer")) { "This Cypher invitation has ended" }
                    app.calls.answer(source, event)
                }
                if (action == "callback" && generation == originGeneration) {
                    stopReadAloud()
                    app.calls.start(requireNotNull(client),id)
                    app.push.discardInvitation(source,event)
                }
            } else if (id.isNotBlank()) selectSession(id) else {
                val active = requireNotNull(client)
                val shell = app.cache.read(active.scope, "new-chat-shell")
                if (generation != originGeneration || client !== active) return@launch
                shell?.let { initialShells[active.scope.key] = it }
                restoreInitialDraft(active.scope, generation)
            }
        } catch (e: Throwable) {
            if (action == "answer" && (dev.hermes.android.voice.incomingFailureTerminal(e) || !app.push.permitsIncoming(source))) app.push.discardInvitation(source, event)
            val needsVoiceKey=e is MissingVoiceKeyException&&startsCall&&generation==originGeneration
            if(needsVoiceKey) { pendingIncomingAfterKey=Triple(source,event,action);more(voiceKeySetupPageIfMissing(false)!!) }
            if (e !is CancellationException && !needsVoiceKey && (generation == -1 || generation == originGeneration)) _ui.update { it.copy(loading = false, restoringSession = false, error = if (e is ApiFailure && e.status == 401) "Sign in to the notification's owner account to open this activity" else e.message.orEmpty()) }
        }
        finally { if (startsCall) _incomingBusy.value = false }
    }
    override fun onCleared() { botMode.clear(); sessionWatch?.cancel(); stopReadAloud(); speech?.shutdown(); super.onCleared() }
}
