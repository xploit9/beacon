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
import dev.hermes.android.voice.FailedCallRedirect
import dev.hermes.android.voice.failedCallConversation
import dev.hermes.android.voice.transcriptsFor
import dev.hermes.android.voice.deriveCallEndTitle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val streamIdPattern = Regex("[A-Za-z0-9_.:-]{1,256}")
private const val MAX_CHAT_ATTACHMENT_BYTES = 12L * 1024 * 1024
private const val LOCAL_CHECKPOINT_LIMIT_BYTES = 16L * 1024 * 1024
private const val LEGACY_BEACON_SESSION_CACHE_KIND = "xara-session" // Migrate the existing encrypted conversation pointer on first read.
private const val LEGACY_BEACON_OPENING_CACHE_KIND = "xara-opening" // Migrate the existing encrypted creation marker on first read.
private const val LEGACY_BEACON_SESSION_HANDLE_PREFIX = "xara:" // Migrate existing scoped SavedStateHandle pointers on first read.
private const val LEGACY_BEACON_TAB_VALUE = "Xara" // Replace the released assistant tab value when restoring state.
internal fun migratedTab(tab: String) = if (tab == LEGACY_BEACON_TAB_VALUE) "Beacon" else tab
internal fun assistantConversationToAdopt(currentId: String, registeredId: String?): String? =
    registeredId?.takeIf { it.isNotBlank() && it != currentId }
/** The name Hermes gave a new branch, so its top bar never shows the parent's. */
internal fun branchTitle(response: JSONObject): String = (response.optJSONObject("session") ?: response).string("title")
/** A load that still echoes the title [hint] replaced (the parent's) must not take the top bar back from it. */
internal fun titleAfterLoad(hint: String, replaced: String, loaded: String): String = if (hint.isNotBlank() && loaded == replaced) hint else loaded
/** Hermes prunes a ringing invitation's notice once it expires, so Call back must not depend on it; 404 or 410 on that path means it is gone. */
internal fun missedCallGone(action: String, error: Throwable): Boolean = action == "callback" && error is ApiFailure && error.status in setOf(404, 410)
/** The route of a missed call whose notice is gone, rebuilt from the owner's own call history (same event, still missed) with the chat it was asked from. */
internal fun missedCallRouteFromHistory(history: JSONObject, missed: dev.hermes.android.voice.IncomingCall, scope: Scope): JSONObject? {
    val chat = history.rows("calls").firstOrNull { it.string("id") == missed.event && it.string("direction") == "incoming" && it.string("state") == "missed" }
        ?.string("conversation_id")?.takeIf { streamIdPattern.matches(it) } ?: return null
    return json("event_id" to missed.event, "source_ref" to missed.source, "owner_id" to scope.owner, "profile" to scope.profile, "kind" to "incoming_call",
        "state" to "expired", "conversation_ref" to missed.conversation, "session_id" to chat, "conversation_id" to chat)
}
/** Where a notification opens: a call or result in another chat opens that chat, not the Beacon tab, which only shows the Beacon chat. */
internal fun pushDestination(kind: String, id: String, beaconChat: String?): String = when {
    kind == "task_result" && id.isBlank() -> "Tasks"
    kind in setOf("incoming_call", "task_result") && (id.isBlank() || id == beaconChat) -> "Beacon"
    else -> "Chat"
}
/** The plugin has no Beacon chat for this account (404), is too old to say (405) or refuses this session (401 on a dashboard without sign-in, 403); anything else is a failed lookup. */
internal fun beaconChatLookupMissing(error: ApiFailure): Boolean = error.status in setOf(401, 403, 404, 405)
internal fun registeredBeaconChat(response: JSONObject?): String? =
    response?.string("conversation_id")?.takeIf { streamIdPattern.matches(it) }
internal fun chatApprovalReadQuery(chat: ChatState, passive: Boolean = chat.raw.string("runtime_session_id").isBlank()): Map<String, String> =
    mapOf("session_id" to chat.sessionId) + if (passive) mapOf("passive" to "1") else emptyMap()
internal fun assistantConversationToSelect(openScope: Scope?, openSessionId: String, assistantScope: Scope, currentId: String, registeredId: String?, openMessagesEmpty: Boolean, streamId: String): String? {
    if (!openMessagesEmpty || streamId.isNotBlank()) return null
    val open = openScope ?: return null
    val shared = assistantConversationToAdopt(currentId, registeredId) ?: return null
    return shared.takeIf { open.copy(session = "") == assistantScope.copy(session = "") && open.session == currentId && openSessionId == currentId }
}

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

internal fun stoppingNoticeAfterEvent(notice: String, event: String): String =
    if (notice == "Stopping the current turn" && event in setOf("done", "state_saved", "cancel", "stream_end", "error", "apperror")) "" else notice

/** Status after a history render. A refresh during an in-flight send keeps it in flight (otherwise the unknown-outcome banner would flash). */
/** Errors that end the native sign-in. A plugin's 401 is not one: a no-account loopback dashboard answers 401 to plugin routes. */
internal fun endsSignIn(error: Throwable): Boolean =
    error is LoopbackSessionRefreshFailed || error is ApiFailure && error.status == 401 && !error.plugin || error is CachedIdentityMismatch
internal fun isMissingBeaconPluginRoute(error: Throwable): Boolean =
    (error as? ApiFailure)?.let { it.plugin && (it.status == 404 || it.detail.contains("unknown route", ignoreCase = true) || it.detail.contains("route not found", ignoreCase = true)) } == true
internal fun beaconOwnerSetupMessage(error: Throwable): String? =
    (error as? ApiFailure)?.takeIf { it.plugin && it.status == 403 && it.detail.equals("Owner not authorized", ignoreCase = true) }
        ?.let { "Finish setup on your Hermes dashboard: open the Beacon tab." }
internal fun updatedHistoryRows(rows: List<JSONObject>, id: String, path: String, body: JSONObject, result: JSONObject, archivedView: Boolean, query: String, profile: String = ""): List<JSONObject> =
    rows.mapNotNull { row ->
        if (sessionRowId(row) != id || profile.isNotBlank() && row.string("profile", profile) != profile) return@mapNotNull row
        when (path) {
            "/api/session/rename" -> row.copy().put("title", result.optJSONObject("session")?.string("title")?.takeIf(String::isNotBlank) ?: result.string("title").takeIf(String::isNotBlank) ?: body.string("title"))
                .takeIf { query.isBlank() || it.string("title").contains(query, ignoreCase = true) }
            "/api/session/pin" -> row.copy().put("pinned", result.optBoolean("pinned", body.optBoolean("pinned")))
            "/api/session/archive" -> result.optBoolean("hidden", body.optBoolean("archived")).let { archived ->
                if (archived != archivedView) null else row.copy().put("archived", archived)
            }
            "/api/session/delete" -> null
            else -> row
        }
    }
internal fun renderedChatStatus(old: ChatState, active: String?, streamEnded: Boolean): String = when {
    old.uncertain.isNotBlank() && old.status == "Sending" -> old.status
    streamEnded -> ""
    active.isNullOrBlank() && old.streamId.isNotBlank() -> old.status.ifBlank { "Turn status unknown" }
    else -> ""
}
/** Saved history shown offline for a signed-in account that is not already reconnecting. */
internal fun reconnectOnNetworkReturn(ui: AppState): Boolean = ui.authenticated && !ui.connected && !ui.loading && !ui.setupWizard && ui.origin.isNotBlank()
/** Messages of yours with exactly this text. */
internal fun sentCopies(messages: List<Message>, text: String): Int = messages.count { it.role == "user" && it.text.trim() == text.trim() }
/** A text-only send is proved delivered when history holds more copies of it than when the send began; an unknown start proves nothing. */
internal fun sendArrived(before: Int?, messages: List<Message>, draft: Draft): Boolean =
    before != null && draft.text.isNotBlank() && draft.attachments.isEmpty() && sentCopies(messages, draft.text) > before
internal enum class SilentStream { Settle, Resubscribe, Wait }
/** The longest a quiet running turn goes between checks with Hermes. */
internal const val STREAM_QUIET_MAX_MS = 10 * 60_000L
/** A stream can go deaf for any reason (a dropped socket, a reconnect; on older Hermes another connection can take its events), and the turn then ends unseen.
 *  Once Hermes reports the turn, an ended one is settled from history and a running one resubscribes; a waiting or unknown one is left alone. */
internal fun silentStreamAction(status: String?): SilentStream = when (status) {
    "idle" -> SilentStream.Settle
    "working" -> SilentStream.Resubscribe
    else -> SilentStream.Wait
}
data class ChatState(val sessionId: String = "", val title: String = "New conversation", val messages: List<Message> = emptyList(), val raw: JSONObject = JSONObject(), val streamId: String = "", val liveText: String = "", val liveReasoning: String = "", val liveTools: List<JSONObject> = emptyList(), val status: String = "", val approval: JSONObject? = null, val clarification: JSONObject? = null, val uncertain: String = "", val queued: List<QueuedMessage> = emptyList(), val anchor: Int = 0, val anchorOffset: Int = 0, val anchorId: String = "", val callMessages: List<CallTranscript> = emptyList(), val projectionReady: Boolean = true, val sessionReasoningEffort: String = "")
internal fun sessionCanStillBeUnpersisted(clientCreated: Boolean, chat: ChatState): Boolean =
    clientCreated && chat.messages.isEmpty() && chat.callMessages.isEmpty() && chat.liveText.isBlank() && chat.streamId.isBlank() && chat.raw.optInt("message_count") == 0
/** The open chat is a phone-created one that was never sent, so New conversation keeps it instead of adding another empty row. */
internal fun reusableEmptyChat(clientCreated: Boolean, chat: ChatState, sendInFlight: Boolean): Boolean =
    sessionCanStillBeUnpersisted(clientCreated, chat) && chat.projectionReady && chat.sessionId.isNotBlank() && chat.uncertain.isBlank() && !sendInFlight
/** Hermes stores a new chat at its first message; until then reads of the open, phone-created chat answer 404. */
internal fun unsavedOpenChatRead(error: Throwable, id: String, clientCreated: Boolean, chat: ChatState): Boolean =
    error is ApiFailure && error.status == 404 && id.isNotBlank() && chat.sessionId == id && sessionCanStillBeUnpersisted(clientCreated, chat)
/** A prompt event wraps its prompt in `pending` (null clears it); a Hermes server request is the prompt itself.
 *  `isNull` is also true for a missing key, which dropped every server-request prompt. */
internal fun pendingPrompt(event: JSONObject): JSONObject? = event.optJSONObject("pending") ?: event.takeUnless { it.has("pending") }
// Hermes stores model-switch steering as user rows, so the reserved prefix identifies it.
internal fun visibleTranscriptMessages(messages: List<Message>): List<Message> =
    messages.filterNot { it.text.trimStart().startsWith("[System:") }
// The send receipt holds the draft as JSON inside JSON, so it is the largest local checkpoint of the attachments.
internal fun attachmentFitsLocalCheckpoint(send: PendingSend): Boolean =
    send.encode().toByteArray().size.toLong() + APP_CIPHER_OVERHEAD <= LOCAL_CHECKPOINT_LIMIT_BYTES
internal fun releaseTerminalChat(chat: ChatState, stream: String, replyConfirmed: Boolean, terminalStatus: String): ChatState {
    if (chat.streamId != stream) return chat
    return if (replyConfirmed) chat.copy(streamId = "", liveText = "", liveReasoning = "", liveTools = emptyList(), status = terminalStatus)
    else chat.copy(streamId = "", liveTools = emptyList())
}
data class EditorState(val scope: Scope? = null, val path: String = "", val text: TextFieldValue = TextFieldValue(), val serverText: String = "", val metadata: JSONObject = JSONObject(), val loading: Boolean = false, val saving: Boolean = false, val error: String = "", val reviewRequired: Boolean = false) { val dirty get() = text.text != serverText }
data class SideQuestion(val scope: Scope, val question: String, val sessionId: String, val streamId: String, val text: String = "", val status: String = "Responding", val cursor: String = "", val error: String = "")
data class AppState(val origin: String = BuildConfig.DEFAULT_ORIGIN, val authenticated: Boolean = false, val profile: String = "default", val chatProfile: String = "", val owner: String = "", val tab: String = "Chat", val connected: Boolean = false, val loading: Boolean = false, val error: String = "", val notice: String = "", val bootstrap: JSONObject = JSONObject(), val sessions: List<JSONObject> = emptyList(), val query: String = "", val showSessions: Boolean = false, val morePage: String = "", val beaconOpening: Boolean = false, val beaconError: String = "", val newChatOpening: Boolean = false, val newChatError: String = "", val initialChatReady: Boolean = false, val restoringSession: Boolean = true, val botModeEnabled: Boolean = false, val botModeSwitching: Boolean = false, val setupWizard: Boolean = false, val setupComplete: Boolean = false, val setupStep: String = "connect", val setupPluginMessage: String = "", val setupOrigin: String = "", val historyLoading: Boolean = false, val assistantSessionId: String = "", val historyUnreadable: List<String> = emptyList())
internal fun voiceKeySetupPage(): String = "Beacon"
internal data class VoiceCallRequest(val scope:Scope,val conversationId:String)

internal enum class NativeSetupAuthMode { Loopback, NativePkce, Unsupported }
internal fun nativeSetupAuthMode(status: JSONObject): NativeSetupAuthMode = when (status.opt("auth_required")) {
    false -> NativeSetupAuthMode.Loopback
    true -> if ((status.optJSONArray("auth_flows") ?: JSONArray()).let { flows -> (0 until flows.length()).any { flows.optString(it) == "native_pkce" } }) NativeSetupAuthMode.NativePkce else NativeSetupAuthMode.Unsupported
    else -> NativeSetupAuthMode.Unsupported
}

data class VoiceHandoff(val scope:Scope,val media:String,val targetCall:String="")
private data class PendingVisionSwitch(val scope: Scope, val callId: String, val media: String)

class BeaconViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
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
                    more(voiceKeySetupPage())
                    notice("Add a Gemini key in Voice & calls to continue this call's screen sharing.")
                } else notice(appErrorText(error))
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
    val app = application as BeaconApplication
    internal val screens = ScreenCache(read = { app.cache.read(it, "screens") }, write = { scope, value -> app.cache.write(scope, "screens", value) }, drop = { app.cache.delete(it, "screens") }, jobs = viewModelScope)
    val phoneSettings = NativePhoneSettings(viewModelScope, saved, draftChanged = ::checkpointPhoneSettings, screens = screens) { client }
    val botMode = NativeBotModeController(viewModelScope, saved, openChat = ::openBotChat) { client }
    val preferences = app.preferences.data.map(AppPreferences::read).stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())
    private fun restoredTab(): String {
        val tab = saved.get<String>("tab") ?: return "Chat"
        return migratedTab(tab).also { if (it != tab) saved["tab"] = it }
    }
    private fun beaconSessionHandleKey(scope: Scope) = "beacon:${scope.key}"
    private fun readBeaconSessionHandle(scope: Scope): String? {
        val key = beaconSessionHandleKey(scope)
        val oldKey = "$LEGACY_BEACON_SESSION_HANDLE_PREFIX${scope.key}"
        val value = saved.get<String>(key)
        if (value != null) { saved.remove<String>(oldKey); return value }
        val old = saved.get<String>(oldKey) ?: return null
        saved[key] = old
        saved.remove<String>(oldKey)
        return old
    }
    private fun writeBeaconSessionHandle(scope: Scope, id: String) {
        saved[beaconSessionHandleKey(scope)] = id
        saved.remove<String>("$LEGACY_BEACON_SESSION_HANDLE_PREFIX${scope.key}")
    }
    private fun clearBeaconSessionHandle(scope: Scope) {
        saved.remove<String>(beaconSessionHandleKey(scope))
        saved.remove<String>("$LEGACY_BEACON_SESSION_HANDLE_PREFIX${scope.key}")
    }
    private suspend fun readBeaconCache(scope: Scope): String? = app.cache.readMigrating(scope, "beacon-session", LEGACY_BEACON_SESSION_CACHE_KIND)
    private suspend fun readBeaconOpening(scope: Scope): String? = app.cache.readMigrating(scope, "beacon-opening", LEGACY_BEACON_OPENING_CACHE_KIND)
    private suspend fun readBeaconSession(scope: Scope): String? = readBeaconSessionHandle(scope) ?: readBeaconCache(scope)
    private val _ui = MutableStateFlow(AppState(tab = restoredTab(), morePage = saved["more"] ?: ""))
    private var normalModeReturn: JSONObject? = null
    private var modeTransition: Job? = null
    val ui = _ui.asStateFlow()
    private val _voiceCallRequest = MutableStateFlow<VoiceCallRequest?>(null)
    private val failedCallRedirect = FailedCallRedirect()
    internal val voiceCallRequest = _voiceCallRequest.asStateFlow()
    private var pendingVoiceCallAfterKey:VoiceCallRequest?=null
    private var pendingIncomingAfterKey:Triple<String,String,String>?=null
    private var pendingVisionSwitchAfterKey: PendingVisionSwitch? = null
    private val _chat = MutableStateFlow(ChatState())
    val chat = _chat.asStateFlow()
    private val _composer = MutableStateFlow(TextFieldValue())
    val composer = _composer.asStateFlow()
    private val _attachments = MutableStateFlow<List<JSONObject>>(emptyList())
    val attachments = _attachments.asStateFlow()
    private val _attachmentPreparation = MutableStateFlow<List<JSONObject>>(emptyList())
    val attachmentPreparation = _attachmentPreparation.asStateFlow()
    private val uploadSlots = kotlinx.coroutines.sync.Semaphore(2)
    private val chatAttachmentLock = Mutex()
    private val uploadJobs = mutableMapOf<String, Job>()
    private val _data = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
    val data = _data.asStateFlow()
    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshing = _refreshing.asStateFlow()
    private val _reasoningSaving = MutableStateFlow(false)
    val reasoningSaving = _reasoningSaving.asStateFlow()
    private var reasoningChange: Job? = null
    private val _modelSaving = MutableStateFlow("")
    val modelSaving = _modelSaving.asStateFlow()
    private var modelChange: Job? = null
    private val conversationChanges = mutableMapOf<String, Job>()
    private val _conversationPending = MutableStateFlow<Set<String>>(emptySet())
    val conversationPending = _conversationPending.asStateFlow()
    private val _archiveUndo = MutableStateFlow<Scope?>(null)
    val archiveUndo = _archiveUndo.asStateFlow()
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
    @Volatile var client: BeaconClient? = null
        private set(value) {
            if (value == null || field?.scope?.let { !it.sameAccount(value.scope) } == true) botMode.clear()
            if (value != null && field?.scope?.let { !it.sameAccount(value.scope) } == true) {
                normalModeReturn = null
                _ui.update { it.copy(botModeEnabled = false, botModeSwitching = false) }
            }
            field=value; app.calls.ownerScope=value?.scope
        }
    internal var profileSwitchClientFactoryForTest: ((AuthContext) -> BeaconClient)? = null
    @Volatile private var sourceScope: Scope? = null
    private var cachedOwnerScope: Scope? = null
    private var checkpointJob: Job? = null
    private var selectionJob: Job? = null
    private var startupJob: Job? = null
    private var startupIndex: Job? = null
    private var startupSettings: Job? = null
    private var optionalProbe: Job? = null
    private var preparedBeacon: Pair<Scope, String>? = null
    private val beaconResolveLock = Mutex()
    private var recoveryJob: Job? = null
    private var sessionWatch: Job? = null
    private var connectionProbe: Job? = null
    private var renderedServerProjection: Pair<Scope, String>? = null
    private var callReconcile: Job? = null
    private val streams = mutableMapOf<String, Job>()
    /** The client each scope's stream chain uses; a re-attach hands the same detached client to its successor. */
    private val streamClients = mutableMapOf<String, BeaconClient>()
    /** A stream silent this long asks Hermes about its turn, checked this often; fields so a device case can shorten them. */
    @Volatile private var streamSilenceMs = 45_000L
    @Volatile private var streamWatchMs = 15_000L
    private val drafts = mutableMapOf<String, Draft>()
    private val persistedDrafts = mutableMapOf<String, String>()
    private val initialShells = mutableMapOf<String, String>()
    private val clientCreatedSessions = mutableSetOf<String>()
    private val registeredAssistantChats = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val initialCreateAttempts = mutableMapOf<String, String>()
    private val newChatJobs = mutableMapOf<String, Job>()
    private val initialCreateError = "Conversation opening failed or was not confirmed. Your draft is safe. Retry to open an empty conversation."
    private val pending = mutableMapOf<String, PendingSend>()
    /** How many identical messages of yours the chat held when a send began (scope key → send id, count); delivery shows as one more. shortcut: in memory only, after an app restart the receipt stays uncertain with Resend and Dismiss; store it with the receipt if that proves too common. */
    private val sentBaseline = mutableMapOf<String, Pair<String, Int>>()
    private val loads = mutableMapOf<String, Job>()
    private val loadRequests = mutableMapOf<String, String>()
    private val loadedAt = mutableMapOf<String, Long>()
    private val readInvalidations = mutableMapOf<String, Long>()
    private val preloadJobs = mutableSetOf<Job>()
    private val confirmedSessions = mutableMapOf<String, JSONObject>()
    private var sessionIndexRequest = 0L
    private var sessionIndexRevision = 0L
    private var sessionIndexArchived = false
    private var sessionIndexThreads = false
    private var sessionIndexAllProfiles = false
    /** Thread names awaiting Hermes's first-turn title (scope key → name). shortcut: in memory only; a restart before the first reply leaves Hermes's own title. */
    private val pendingThreadTitles = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** The open chat's title hint with the title it replaced (scope, hint, replaced); dropped once a load carries another title. */
    private var heldTitle: Triple<Scope, String, String>? = null
    private var assistantReturnInitialized = false
    private var assistantReturnValue: JSONObject? = null
    private val liveBoundaries = java.util.concurrent.ConcurrentHashMap<String, LiveAssistantBoundary>()
    private var chatHistoryRevision = 0L
    @Volatile private var originGeneration = 0
    @Volatile private var sessionGeneration = 0
    private var navigationGeneration = 0
    private var beaconGeneration = 0
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
    /** Set by a sign-in in the setup wizard: the next startup links this phone even if it was removed on the PC before. */
    private var linkOnNextStartup = false
    private val _voicePage = MutableStateFlow("home")
    /** The Voice & calls page Settings shows ("home", "provider" or "key"), so Back steps out one level at a time. */
    val voicePage = _voicePage.asStateFlow()
    fun setVoicePage(page: String) { if (page in setOf("home", "provider", "key")) _voicePage.value = page }

    /** The provider a call, an incoming call or a screen share is waiting on a key for ("" = the selected one), or null when nothing waits. */
    internal fun pendingVoiceKeyProvider(): String? = when {
        pendingVisionSwitchAfterKey != null -> "gemini"
        pendingVoiceCallAfterKey != null || pendingIncomingAfterKey != null -> ""
        else -> null
    }

    /**
     * Links this phone to the account when the Beacon plugin is there; a plugin-free Hermes shows nothing and a failure stays quiet.
     * The next sign-in or app start tries again.
     */
    private fun linkThisPhone(active: BeaconClient) {
        val explicit = linkOnNextStartup
        linkOnNextStartup = false
        val generation = originGeneration
        viewModelScope.launch {
            val c = active.detached()
            try {
                val linked = NativePairing.link(app, c, explicit)
                if (linked != null && client === active && generation == originGeneration) prefetchDevices(active)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* retried silently on the next sign-in or app start */ }
            finally { c.closeNative() }
        }
    }

    @Volatile private var networkValidated = false
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    /** Reconnects once when Android reports a validated network again while Beacon shows its saved history offline. */
    private fun reconnectWhenNetworkReturns() {
        val manager = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        networkValidated = manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
                val returned = !networkValidated && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                networkValidated = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (returned) viewModelScope.launch { if (reconnectOnNetworkReturn(_ui.value)) connect(_ui.value.origin) }
            }
            override fun onLost(network: android.net.Network) { networkValidated = false }
        }
        try { manager.registerDefaultNetworkCallback(callback); networkCallback = callback }
        catch (_: Exception) { /* Reconnect stays a tap if Android refuses the callback. */ }
    }

    init {
        reconnectWhenNetworkReturns()
        viewModelScope.launch {
            app.push.emailAlerts.collect { (scope, card) -> storeData("task-result:${scope.key}", card) }
        }
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
                if (!assistantReturnInitialized) setAssistantReturn(prefs[stringPreferencesKey("assistant_return")]?.let { runCatching { JSONObject(it) }.getOrNull() })
                val origin = prefs[stringPreferencesKey("origin")] ?: BuildConfig.DEFAULT_ORIGIN
                if (generation != originGeneration) return@launch
                val auth = if (origin.isBlank()) AuthContext(origin, emptyList()) else withContext(Dispatchers.IO) { app.auth.read(origin) }
                if (generation != originGeneration) return@launch
                val cached = origin.isNotBlank() && auth.cachedScopeFor(origin, replacingAccount = false) != null
                val complete = prefs[booleanPreferencesKey("setup_complete")] == true
                val activeSetup = prefs[booleanPreferencesKey("setup_active")] == true
                val setupOriginKey = stringPreferencesKey("setup_origin")
                val storedSetupOrigin = prefs[setupOriginKey].orEmpty()
                val setupOrigin = runCatching { dev.hermes.android.ui.parseBeaconConnectInput(storedSetupOrigin) }.getOrDefault("")
                if (setupOrigin != storedSetupOrigin) app.preferences.edit { values ->
                    if (values[setupOriginKey] == storedSetupOrigin) {
                        if (setupOrigin.isBlank()) values.remove(setupOriginKey) else values[setupOriginKey] = setupOrigin
                    }
                }
                if (generation != originGeneration) return@launch
                val knownSteps = setOf("connect", "probe", "signin", "unsupported", "voice-provider", "voice-key", "voice-customize", "permission-microphone", "permission-notifications", "permission-full-screen", "done")
                val savedStep = prefs[stringPreferencesKey("setup_step")].orEmpty().takeIf { it in knownSteps } ?: if (complete) "done" else "connect"
                val step = when {
                    origin.isBlank() -> "connect"
                    !cached && (complete || savedStep.startsWith("permission-") || savedStep == "done") -> "signin"
                    cached && !complete && !activeSetup && savedStep == "connect" -> firstIncompleteSetupPermission()
                    savedStep == "done" && !complete && !activeSetup -> "connect"
                    else -> savedStep
                }
                _ui.update { it.copy(origin = origin, setupOrigin = setupOrigin, setupWizard = activeSetup || !complete || !cached, setupComplete = complete, setupStep = step) }
                if (auth.canReconnectNative(origin)) connect(origin) else _ui.update { it.copy(restoringSession = false, setupWizard = true) }
            } catch (e: Throwable) {
                if (generation == originGeneration) { _ui.update { it.copy(restoringSession = false) }; fail(e) }
            }
        }
    }
    fun clearMessage() { _archiveUndo.value = null; _ui.update { it.copy(error = "", notice = "") } }
    private suspend fun optionalLocalSave(block: suspend () -> Unit) {
        try { block() } catch (e: Exception) { if (e is CancellationException) throw e; notice("Couldn't save a local copy. Refresh before leaving.") }
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
            val job = if (form.path.isEmpty()) viewModelScope.launch { try { form.onResult?.invoke(body); if (_form.value?.id == form.id) _form.value = null } catch (e: Throwable) { _form.update { if (it?.id == form.id) it.copy(error = appErrorText(e)) else it }; fail(e) } }
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
        } catch (e: Throwable) { _submittingForm.value = ""; _form.update { if (it?.id == form.id) it.copy(error = appErrorText(e)) else it }; fail(e) }
    }
    fun notice(value: String) { _archiveUndo.value = null; _ui.update { it.copy(error = if (value.isNotBlank()) "" else it.error, notice = value) } }
    fun undoArchive() {
        val target = _archiveUndo.value ?: return
        val owner = client?.scope ?: return
        if (!owner.sameAccount(target)) return
        _archiveUndo.value = null
        conversationAction(target.session, "/api/session/archive", json("archived" to false), "Unarchived", target.profile)
    }
    fun openVoiceKeySettingsForCall(active:BeaconClient,conversationId:String) {
        pendingVoiceCallAfterKey=VoiceCallRequest(active.scope,conversationId)
        more(voiceKeySetupPage())
    }
    private fun clearPendingVoiceKeyRequests() {
        pendingVoiceCallAfterKey = null
        pendingIncomingAfterKey = null
        pendingVisionSwitchAfterKey = null
    }
    private fun clearPendingVoiceKeyRequestsIfLeavingVoiceKeyPage(tab: String, page: String) {
        val keyPage = voiceKeySetupPage()
        if (_ui.value.tab == "More" && _ui.value.morePage == keyPage && (tab != "More" || page != keyPage)) clearPendingVoiceKeyRequests()
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
        app.preferences.edit { prefs -> values.keys().forEach { key -> if (key in setOf("theme", "skin")) prefs[stringPreferencesKey(key)] = values.opt(key)?.toString().orEmpty() } }
        } } catch (e: Exception) { if (e is CancellationException) throw e; if ((client?.scope ?: cachedOwnerScope) == owner) fail(e) }
    }
    fun loadSlashCatalog(force: Boolean = false) = load("slash:catalog", "/api/commands/catalog", force = force)
    fun runSlashCommand(name: String, argument: String = "") {
        if (!admitConversation()) return
        val commandName = name.removePrefix("/").lowercase()
        if (chatCommandChangesProfile(commandName, argument)) { notice("Change this for all chats in Settings."); return }
        if (commandName == "yolo") { notice("Use Approvals beside the composer to change YOLO."); return }
        if (_chat.value.raw.length() == 0 || _chat.value.streamId.isNotBlank() || _chat.value.uncertain.isNotBlank() || _chat.value.approval != null || _chat.value.clarification != null || app.calls.state.value.active || _attachments.value.isNotEmpty() || _chat.value.raw.optBoolean("read_only") || _chat.value.raw.optBoolean("is_read_only")) { notice("Finish the current turn and attachments before running a command"); return }
        if (commandName == "model" && argument.isBlank()) { openCatalog("Model & provider"); return }
        if (commandName == "model") {
            val choice = runCatching { chatModelChoice(argument) }.getOrElse { notice("Choose the model and provider in the chat's model picker."); return }
            pickModel(choice.first, choice.second); return
        }
        if (commandName == "reasoning" && argument.isBlank()) { openCatalog("Reasoning"); return }
        action("/api/commands/exec", json("session_id" to _chat.value.sessionId, "name" to commandName, "argument" to argument), refresh = commandName in setOf("model", "reasoning", "fast")) { _, result ->
            val type = result.string("type")
            if (type in setOf("send", "skill", "prefill", "alias")) notice(result.string("display", result.string("notice", "This command requires the native desktop composer")))
            else { notice(result.string("output", result.string("notice", "Command completed"))); val command = "/${name.removePrefix("/")}" + argument.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty(); if (_composer.value.text == command) composer(TextFieldValue()) }
        }
    }
    fun storeData(key: String, value: JSONObject) { _data.update { it + (key to value) } }
    fun clearTaskResult(scope: Scope, noticeId: String, dismiss: Boolean = false) = viewModelScope.launch {
        val key = "task-result:${scope.key}"
        if (noticeId.isBlank()) {
            if (dismiss && _data.value[key]?.string("notice_id").isNullOrBlank()) storeData(key, JSONObject())
            return@launch
        }
        try {
            val stored = app.cache.read(scope, "task-result")
            if (stored != null && runCatching { JSONObject(stored).string("notice_id") }.getOrNull() == noticeId)
                app.cache.delete(scope, "task-result", stored)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            notice("The saved email alert may return when this chat is reopened.")
        }
        val current = _data.value[key] ?: return@launch
        if (current.string("notice_id") == noticeId)
            storeData(key, if (dismiss) JSONObject() else current.copy().put("feedback_sent", true))
    }
    fun setKeepConnectedEnabled(enabled: Boolean) {
        runCatching { dev.hermes.android.push.KeepConnectedService.setEnabled(app, client?.auth, enabled) }
            .onFailure { notice(appErrorText(it)) }
    }
    private suspend fun savedNativeSessionValid(auth: AuthContext?): Boolean = withContext(Dispatchers.IO) {
        auth?.let { if (it.bearer == null) true else app.auth.read(it.origin).let { saved -> saved.scope().sameAccount(it.scope()) && saved.bearer != null } } ?: true
    }
    fun foreground(active: Boolean) {
        val changed = foreground != active
        foreground = active
        if (active && _ui.value.authenticated) {
            val generation = originGeneration
            val currentClient = client
            viewModelScope.launch {
                if (!savedNativeSessionValid(currentClient?.auth)) {
                    if (generation == originGeneration && client === currentClient && _ui.value.authenticated) fail(NativeAuthExpiredException())
                    return@launch
                }
                if (generation != originGeneration || client !== currentClient || !foreground) return@launch
                if (!changed) return@launch
                if (client == null && !_ui.value.loading) connect(_ui.value.origin)
                else { prepareStartup(); recoverForeground() }
            }
            return
        }
        if (!changed) return
        if (!active) { cancelStartup(); recoveryJob?.cancel(); sessionWatch?.cancel(); sideJob?.cancel(); streams.values.toList().forEach(Job::cancel); viewModelScope.launch { runCatching { checkpointVisible() }.onFailure(::fail) } }
    }
    /** An update notification opens the update screen, which downloads and verifies the release from GitHub. */
    fun downloadAppUpdate(notificationToken: String) {
        if (_ui.value.authenticated) { tab("More"); more("App updates") }
        app.updates.updateFromNotification(notificationToken, client)
    }
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
                app.cache.writeSession(scope, response.toString())
                if (!request.current() || !foreground) return@launch
                reconcile(scope, c)
                if (!request.current() || !foreground) return@launch
                val stream = _chat.value.streamId
                if (stream.isNotBlank() && streams[scope.key]?.isActive != true) {
                    val nativeStatus = try { c.recoveredNativeSessionStatus(scope.session) }
                    catch (e: Throwable) { if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403)) throw e; null }
                    if (!request.current() || !foreground) return@launch
                    attachStream(c, scope, stream, terminalAlreadyObserved = nativeStatus == "idle")
                }
                watchSession(c, scope)
                _side.value?.takeIf { it.scope == scope && it.status !in setOf("Complete", "Stopped") }?.let { resumeSideQuestion() }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (!request.current() || !foreground) return@launch
                if (unsavedOpenChatRead(e, scope.session, clientCreatedSessions.contains(scope.key), _chat.value)) watchSession(c, scope) else fail(e)
            }
        }
    }
    private fun checkpointPhoneSettings() {
        val owner = client?.scope ?: return
        val snapshot = runCatching { phoneSettings.snapshotDraft() }.getOrElse { notice("This draft is too large to keep. Shorten it."); return }
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
        // Like the debounced path: an oversized settings draft must not abort connecting, signing out or switching chats.
        try { retainPhoneSettings(owner, phoneSettings.snapshotDraft()) }
        catch (e: Throwable) { if (e is CancellationException) throw e; notice("This draft is too large to keep. Shorten it.") }
    }
    internal suspend fun restorePhoneSettings(active: BeaconClient) {
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
    private fun clearDetails() { loadedAt.clear(); _data.update { it - "picker:reasoning" - "picker:approvals" - "profile-defaults" }; reasoningChange = null; _reasoningSaving.value = false; modelChange = null; _modelSaving.value = ""; recoveryJob?.cancel(); stopReadAloud(); pasteJob?.cancel(); uploadJobs.values.toList().forEach(Job::cancel); uploadJobs.clear(); _attachmentPreparation.value = emptyList(); sideCheckpointJob?.cancel(); _editor.value = EditorState(); _form.value = null; _side.value = null; _catalog.value = ""; sideJob?.cancel() }
    fun loadChatApprovals(passive: Boolean = _chat.value.raw.string("runtime_session_id").isBlank()): Job {
        sourceScope?.takeIf { it.session == _chat.value.sessionId && it.copy(session = "") == client?.scope }
            ?: return viewModelScope.launch {}
        return load("picker:approvals", "/api/approval/mode", chatApprovalReadQuery(_chat.value, passive))
    }
    fun openCatalog(kind: String) { if (!admitConversation()) return; _catalog.value = kind; when (kind) { "Model & provider" -> load("picker:models", "/api/models"); "Toolsets" -> load("picker:toolsets", "/api/tools/toolsets"); "Reasoning" -> { _data.update { it - "picker:reasoning" }; load("picker:reasoning", if (isBeaconConversation()) "/api/mobile/beacon/reasoning" else "/api/reasoning", mapOf("session_id" to _chat.value.sessionId, "model" to _chat.value.raw.string("model"), "provider" to _chat.value.raw.string("model_provider"))) } } }
    fun setReasoning(effort: String) {
        if (!admitConversation() || _reasoningSaving.value) return
        if (conversationChanges["${sourceScope?.key}|/api/reasoning"]?.isCompleted == false) return
        _reasoningSaving.value = true
        val job = action("/api/reasoning", json("session_id" to _chat.value.sessionId, "effort" to effort), refresh = false, successNotice = "Reasoning updated for this chat") { _, result ->
            val effective = result.getString("value")
            _chat.update { it.copy(sessionReasoningEffort = effective, raw = it.raw.copy().put("reasoning_effort", effective)) }
            notice("Reasoning effort: $effective")
            if (_catalog.value == "Reasoning") closeCatalog()
        }
        reasoningChange = job
        job.invokeOnCompletion { if (reasoningChange === job) _reasoningSaving.value = false }
    }
    fun closeCatalog() { _catalog.value = "" }
    fun pickModel(model: String, provider: String) {
        if (!admitConversation() || _modelSaving.value.isNotBlank()) return
        val scope = sourceScope ?: return
        if (conversationChanges["${scope.key}|/api/session/update"]?.isCompleted == false) return
        _modelSaving.value = "$provider:$model"
        notice("Applying model…")
        val job = action("/api/session/update", json("session_id" to scope.session, "model" to model, "model_provider" to provider.ifBlank { null }), successNotice = "Model updated for this chat") { _, _ -> saved["model_pick:${scope.key}"] = true; closeCatalog() }
        modelChange = job
        job.invokeOnCompletion { if (modelChange === job) _modelSaving.value = "" }
    }
    private suspend fun exportDownload(c: BeaconClient, uri: Uri, path: String, query: Map<String, String>, limit: Long) = withContext(Dispatchers.IO) {
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
            cancelStartup(); ++sessionGeneration
            if (draftScope != null) {
                drafts[draftScope.key] = retainedDraft
                viewModelScope.launch {
                    runCatching {
                        app.cache.write(draftScope, draftKind(draftScope), retainedDraft.encode(), retainedDraft.text.isNotBlank() || retainedDraft.attachments.isNotEmpty() || retainedDraft.queued.isNotEmpty())
                        if (retainedEditor.scope != null && retainedEditor.path.isNotBlank()) app.cache.write(retainedEditor.scope, "editor:${retainedEditor.path}", json("text" to retainedEditor.text.text, "start" to retainedEditor.text.selection.start, "end" to retainedEditor.text.selection.end).toString(), retainedEditor.dirty || retainedEditor.saving)
                    }.onFailure { if (originGeneration == invalidation) notice("The latest local checkpoint could not be saved: ${appErrorText(it)}") }
                }
            }
            cancelNewChatWork(); app.calls.hangup(); sessionWatch?.cancel(); selectionJob?.cancel()
            streams.values.toList().forEach(Job::cancel); loads.values.toList().forEach(Job::cancel)
            lapsedAccount = owner ?: lapsedAccount
            client = null; cachedOwnerScope = null; sourceScope = null
            _chat.value = ChatState(); _composer.value = TextFieldValue(); _attachments.value = emptyList(); _data.value = emptyMap(); clearDetails()
            _ui.update { it.copy(origin = owner?.origin ?: it.origin, owner = "", profile = owner?.profile ?: it.profile, authenticated = false, connected = false, loading = false, restoringSession = false, setupWizard = true, setupComplete = false, setupStep = "signin", error = appErrorText(error)) }
            saveSetupProgress("signin", complete = false)
            return
        }
        if (endsSignIn(error)) {
            val loopbackRestart = error is LoopbackSessionRefreshFailed
            val origin = client?.auth?.origin ?: cachedOwnerScope?.origin ?: runCatching { trustedOrigin(_ui.value.origin) }.getOrNull()
            val draftScope = draftScope()
            val retainedDraft = draft()
            val retainedEditor = _editor.value
            checkpointJob?.cancel(); editorCheckpoint?.cancel()
            val invalidation = ++originGeneration
            cancelStartup()
            ++sessionGeneration
            if (draftScope != null) {
                drafts[draftScope.key] = retainedDraft
                viewModelScope.launch {
                    runCatching {
                        app.cache.write(draftScope, draftKind(draftScope), retainedDraft.encode(), retainedDraft.text.isNotBlank() || retainedDraft.attachments.isNotEmpty() || retainedDraft.queued.isNotEmpty())
                        if (retainedEditor.scope != null && retainedEditor.path.isNotBlank()) app.cache.write(retainedEditor.scope, "editor:${retainedEditor.path}", json("text" to retainedEditor.text.text, "start" to retainedEditor.text.selection.start, "end" to retainedEditor.text.selection.end).toString(), retainedEditor.dirty || retainedEditor.saving)
                    }.onFailure { if (originGeneration == invalidation) notice("The latest local checkpoint could not be saved: ${appErrorText(it)}") }
                }
            }
            cancelNewChatWork(); app.calls.hangup(); sessionWatch?.cancel(); selectionJob?.cancel()
            streams.values.toList().forEach(Job::cancel); loads.values.toList().forEach(Job::cancel)
            lapsedAccount = client?.scope ?: cachedOwnerScope ?: lapsedAccount
            client = null; cachedOwnerScope = null; sourceScope = null
            val message = if (loopbackRestart) appErrorText(error) else "Sign in again. Your local draft is retained."
            origin?.let { revoked -> val version = app.auth.authorityEpoch.readVersion(); viewModelScope.launch { runCatching { app.auth.removeIfUnchanged(revoked, version) }.onFailure { if (originGeneration == invalidation) notice("Local credential removal failed: ${appErrorText(it)}") } } }
            _ui.update { it.copy(authenticated = false, connected = false, loading = false, restoringSession = false, setupWizard = true, setupComplete = false, setupStep = if (loopbackRestart) "connect" else "signin", setupOrigin = if (loopbackRestart) origin ?: it.setupOrigin else it.setupOrigin, beaconOpening = false, beaconError = "", newChatOpening = false, newChatError = "", initialChatReady = false, owner = "", query = "", sessions = emptyList(), bootstrap = JSONObject(), error = message) }
            saveSetupProgress(if (loopbackRestart) "connect" else "signin", complete = false, setupOrigin = if (loopbackRestart) origin else null)
            _chat.value = ChatState(); _composer.value = TextFieldValue(); _attachments.value = emptyList(); _data.value = emptyMap(); clearDetails()
        } else {
            _ui.update { it.copy(error = beaconOwnerSetupMessage(error) ?: appErrorText(error)) }
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
        cancelNewChatWork()
        try { checkpointVisible() } catch (e: Throwable) { _ui.update { it.copy(restoringSession = false) }; fail(e); return@launch }
        if (generation != originGeneration) return@launch
        sessionWatch?.cancel(); selectionJob?.cancel(); ++sessionGeneration
        streams.values.forEach(Job::cancel); streams.clear()
        _ui.update { it.copy(loading = true, restoringSession = true, error = "", beaconOpening = false, beaconError = "", newChatOpening = false, newChatError = "", initialChatReady = false, authenticated = false, connected = false, owner = "", query = "", sessions = emptyList(), bootstrap = JSONObject()) }
        val previousAccount = client?.scope ?: cachedOwnerScope ?: lapsedAccount
        _chat.value = ChatState(); _composer.value = TextFieldValue(); _attachments.value = emptyList(); _data.value = emptyMap(); clearDetails(); sourceScope = null; cachedOwnerScope = null; client = null
        var restoringClient: BeaconClient? = null
        var restoringScope: Scope? = null
        val credentialVersion = app.auth.authorityEpoch.readVersion()
        try {
            val origin = trustedOrigin(input)
            _ui.update { it.copy(origin = origin) }
            val auth = initialAuth?.also { require(it.origin == origin) } ?: withContext(Dispatchers.IO) { app.auth.read(origin) }
            if (generation != originGeneration) return@launch
            // Fresh sign-in and native bootstrap's first verified legacy issuer install new authority.
            val snapshot = initialAuth == null && auth.nativeProvider.isNotBlank()
            val next = profileSwitchClientFactoryForTest?.invoke(auth) ?: BeaconClient(auth, app.auth, persistContext = false)
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
            val beacon = readBeaconCache(next.scope)
            if (generation != originGeneration) return@launch
            beacon?.let { writeBeaconSessionHandle(next.scope, it) }
            if (!persistAuthIfCurrent(next.auth, credentialVersion, snapshot = snapshot, selectProfileIf = { generation == originGeneration })) {
                if (generation == originGeneration) {
                    app.calls.hangup(); cachedOwnerScope = null; sourceScope = null
                    _ui.update { it.copy(authenticated = false, loading = false, owner = "", error = "Sign-in changed while reconnecting. Reconnect to use the current account.") }
                }
                return@launch
            }
            next.markAuthPersisted()
            if (generation != originGeneration) return@launch
            app.preferences.edit { it[stringPreferencesKey("origin")] = origin }
            if (generation != originGeneration) return@launch
            client = next; cachedOwnerScope = null; retireSavedDrafts(null)
            previousAccount?.takeIf { !it.sameAccount(next.scope) }?.let { forgetScreens(it) }
            lapsedAccount = null
            hydrateScreens(next.scope)
            restoreBotMode(next.scope)
            restorePhoneSettings(next)
            if (generation != originGeneration || client !== next) return@launch
            if (app.firebaseConfigured && (app.push.registeredTarget(next.scope) != null || app.push.hasUnstampedPush(next.scope))) {
                viewModelScope.launch { runCatching { app.push.register(next.detached()) } }
            }
            val previousSetupStep = _ui.value.setupStep
            // A provider that already has a key skips straight to "Make it yours".
            val wizardStep = when {
                !setupFlow -> previousSetupStep
                previousSetupStep in setOf("connect", "probe") && initialAuth == null -> previousSetupStep
                previousSetupStep == "signin" || previousSetupStep.startsWith("voice-") ->
                    if (withContext(Dispatchers.IO) { VoiceKeyStore(app).let { it.hasKey(it.settings().provider) } }) "voice-customize" else "voice-provider"
                else -> firstIncompleteSetupPermission()
            }
            if (setupFlow && initialAuth != null) linkOnNextStartup = true
            if (setupFlow) saveSetupProgress(wizardStep, setupOrigin = origin)
            _ui.update { it.copy(origin = origin, owner = next.auth.owner, profile = next.auth.profile, authenticated = true, connected = true, bootstrap = bootstrap, loading = false, setupWizard = setupFlow || it.setupWizard, setupStep = if (setupFlow) wizardStep else it.setupStep) }
            dev.hermes.android.push.KeepConnectedService.startIfEnabled(app)
            prepareStartup()
            if (_ui.value.tab == "Beacon") openBeacon() else if (assistantReturn() != null) returnFromAssistant() else {
                val selected = saved.get<String>("session:${next.scope.key}") ?: sourceScope?.takeIf { it.copy(session = "") == next.scope }?.session ?:
                    try { app.cache.authorizedLegacySelection(next) } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        if (endsSignIn(error) || error is NativeAuthExpiredException) throw error
                        null
                    }
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
                            retained.retainCookieRotation()
                            // shortcut: snapshot mode keeps the stored bearer and cookies, so this rewrites the same record (replaceBearer
                            // and retainCookieRotation already saved any rotation); NativeAuthLifetimeTest savedReconnectRecovery* holds it. Remove both together.
                            persistAuthIfCurrent(retained.auth, credentialVersion, snapshot = true)
                        } catch (saveError: Throwable) {
                            if (saveError is CancellationException) throw saveError
                            if (generation == originGeneration) notice("The refreshed sign-in could not be retained. Reconnect before leaving Beacon.")
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
        val safeAddress = runCatching { dev.hermes.android.ui.parseBeaconConnectInput(address) }.getOrDefault("")
        _ui.update { it.copy(setupOrigin = safeAddress, setupWizard = true, setupStep = "connect", loading = false, restoringSession = false, setupPluginMessage = "", error = "", notice = "") }
        saveSetupProgress("connect", active = true, setupOrigin = safeAddress)
    }
    fun openSetupLink(value: String, confirmed: Boolean = false) {
        ++originGeneration
        setupProbeJob?.cancel(); ++setupProbeGeneration
        val parsed = try { dev.hermes.android.ui.parseBeaconConnectLink(value) }
        catch (error: Exception) {
            _ui.update { it.copy(setupOrigin = "", setupWizard = true, setupStep = "connect", loading = false, restoringSession = false, error = appErrorText(error), notice = "") }
            if (confirmed) saveSetupProgress("connect", active = true, setupOrigin = "")
            return
        }
        _ui.update { it.copy(setupOrigin = parsed.origin, setupWizard = true, setupStep = "connect", loading = false, restoringSession = false, setupPluginMessage = "", error = "", notice = "") }
        if (confirmed) saveSetupProgress("connect", active = true, setupOrigin = parsed.origin)
    }
    fun cancelSetupWizard() {
        clearPendingVoiceKeyRequests()
        setupProbeJob?.cancel(); ++setupProbeGeneration
        if (_ui.value.authenticated) {
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
        clearPendingVoiceKeyRequests()
        setKeepConnectedEnabled(keepConnected)
        _ui.update { it.copy(setupWizard = false, setupComplete = true, setupStep = "done", setupOrigin = "", error = "") }
        saveSetupProgress("done", complete = true, active = false)
        tab("Chat")
        openInitialChat()
    }
    private suspend fun persistAuthIfCurrent(auth: AuthContext, version: Long?, snapshot: Boolean, selectProfileIf: (() -> Boolean)? = null): Boolean {
        if (app.auth.persistIfUnchanged(auth, version, snapshot, selectProfileIf)) return true
        return withContext(Dispatchers.IO) {
            app.auth.read(auth.origin).let { current ->
                current.scope().sameAccount(auth.scope()) && current.profile == auth.profile &&
                    current.backend == auth.backend && (auth.bearer?.let { it == current.bearer } == true ||
                    auth.loopbackSession?.takeIf { it.verified }?.let { session -> current.loopbackSession?.copy(persisted = false) == session.copy(persisted = false) } == true ||
                    auth.bearer == null && auth.loopbackSession == null && auth.nativeProvider.isNotBlank() &&
                    current == auth && current.canReconnectNative(auth.origin))
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
                    val safeAddress = runCatching { dev.hermes.android.ui.parseBeaconConnectInput(address) }.getOrDefault("")
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
        if (_ui.value.setupStep == "voice-provider") {
            viewModelScope.launch {
                val store = VoiceKeyStore(app)
                val hasKey = withContext(Dispatchers.IO) { val provider = store.settings().provider; store.hasKey(provider) }
                if (_ui.value.setupStep != "voice-provider") return@launch
                val next = if (hasKey) "voice-customize" else "voice-key"
                _ui.update { it.copy(setupStep = next, error = "") }
                saveSetupProgress(next)
            }
            return
        }
        val next = when (_ui.value.setupStep) {
            "voice-key" -> "voice-customize"
            "voice-customize" -> firstIncompleteSetupPermission()
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
    fun skipVoiceSetup() {
        val next = when (_ui.value.setupStep) {
            "voice-provider", "voice-key", "voice-customize" -> firstIncompleteSetupPermission()
            else -> return
        }
        _ui.update { it.copy(setupStep = next, error = "") }
        saveSetupProgress(next)
    }
    /** Moves between the three Voice & calls pages (Provider, Key, Make it yours), including Back. */
    fun showVoiceSetupPage(step: String) {
        val pages = setOf("voice-provider", "voice-key", "voice-customize")
        if (_ui.value.setupStep !in pages || step !in pages) return
        _ui.update { it.copy(setupStep = step, error = "") }
        saveSetupProgress(step)
    }
    fun probeSetupAddress(value: String) {
        setupProbeJob?.cancel()
        val parsed = try { dev.hermes.android.ui.parseBeaconConnectLink(value) }
        catch (error: Exception) {
            val safeAddress = runCatching { dev.hermes.android.ui.parseBeaconConnectInput(value) }.getOrDefault("")
            _ui.update { it.copy(setupOrigin = safeAddress, setupWizard = true, setupStep = "connect", loading = false, error = appErrorText(error)) }
            saveSetupProgress("connect", active = true, setupOrigin = safeAddress)
            return
        }
        val origin = parsed.origin
        val generation = ++setupProbeGeneration
        _ui.update { it.copy(setupOrigin = origin, setupWizard = true, setupStep = "probe", loading = true, error = "") }
        saveSetupProgress("probe", active = true, setupOrigin = origin)
        setupProbeJob = viewModelScope.launch {
            var probe: BeaconClient? = null
            try {
                val statusClient = BeaconClient(AuthContext(origin, emptyList()))
                probe = statusClient
                val status = withTimeoutOrNull(45_000) { statusClient.get("/api/status") }
                    ?: throw java.io.IOException("Hermes server probe timed out")
                require(status.string("version").isNotBlank() && status.has("gateway_state") && status.optJSONObject("components") != null) {
                    "This address doesn't look like a Hermes server. Check the address, then Retry."
                }
                if (generation != setupProbeGeneration) return@launch
                when (nativeSetupAuthMode(status)) {
                    NativeSetupAuthMode.Loopback -> {
                        val session = withTimeoutOrNull(30_000) { statusClient.readLoopbackSessionToken() }
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
                    loopbackRestart -> appErrorText(error)
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
        catch (error: Exception) { _ui.update { it.copy(setupStep = "connect", loading = false, error = appErrorText(error)) }; return@launch }
        _ui.update { it.copy(setupWizard = true, setupStep = "signin", loading = true, error = "") }
        try {
            val bearer = NativeAuth(origin).authorize(context)
            val profile = _ui.value.profile.takeIf(::validNativeProfile) ?: "default"
            val signedIn = AuthContext(origin, emptyList(), bearer.owner, profile, bearer = bearer.copy(profile = profile))
            app.preferences.edit { it[stringPreferencesKey("setup_origin")] = dev.hermes.android.ui.parseBeaconConnectInput(origin); it[booleanPreferencesKey("setup_active")] = true; it[stringPreferencesKey("setup_step")] = "signin" }
            connect(origin, signedIn).join()
        } catch (error: CancellationException) { throw error }
        catch (error: Throwable) { _ui.update { it.copy(loading = false, error = appErrorText(error)) } }
    }
    private suspend fun restoreCachedOwner(scope: Scope, generation: Int) {
        val index = app.cache.read(scope, "conversation-index")?.let(::JSONObject)
        val settings = app.cache.read(scope, "profile-settings")?.let(::JSONObject)
        val beacon = readBeaconCache(scope)
        val selected = saved.get<String>("session:${scope.key}") ?: app.cache.read(scope, "selected-session")
        val shell = app.cache.read(scope, "new-chat-shell")
        val screenData = cachedScreens(scope)
        if (generation != originGeneration) return
        cachedOwnerScope = scope
        restoreBotMode(scope)
        app.calls.ownerScope = scope
        if (beacon != null) writeBeaconSessionHandle(scope, beacon)
        shell?.let { initialShells[scope.key] = it }
        _data.update { screenData + it }
        settings?.let { _data.update { data -> data + ("profile-settings" to it) } }
        _ui.update { it.copy(origin = scope.origin, owner = scope.owner, profile = scope.profile, authenticated = true, connected = false, sessions = index?.rows("sessions").orEmpty()) }
        val id = if (_ui.value.tab == "Beacon") beacon else selected
        if (!id.isNullOrBlank()) {
            sourceScope = scope.session(id)
            val selection = ++sessionGeneration
            _chat.value = ChatState(sessionId = id, status = "Cached · reconnecting")
            if (restoreCachedConversation(scope.session(id), selection) && generation == originGeneration && sourceScope == scope.session(id)) _chat.update { it.copy(status = "Cached · reconnect to synchronize") }
        } else restoreInitialDraft(scope, generation)
    }
    private suspend fun restoreInitialDraft(owner: Scope, generation: Int = originGeneration, open: Boolean = true) {
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
        if (current()) {
            _ui.update { it.copy(newChatError = if (initialCreateAttempts.containsKey(owner.key)) initialCreateError else "", initialChatReady = true) }
            if (open) openInitialChat()
        }
    }
    private suspend fun recoverMissingInitialShell(owner: Scope, id: String, current: () -> Boolean) {
        if (!current()) return
        val active = client ?: return
        val origin = originGeneration
        val scope = owner.session(id)
        val retained = draft()
        // Hermes forgot the pinned Beacon conversation before its first turn: resolve a fresh one there instead of bouncing to Chat forever.
        val pinned = readBeaconSessionHandle(owner) == id || preparedBeacon?.let { it.first == owner && it.second == id } == true ||
            runCatching { readBeaconCache(owner) == id }.getOrDefault(false)
        if (pinned) {
            clearBeaconSessionHandle(owner)
            if (preparedBeacon?.second == id) preparedBeacon = null
            try { app.cache.delete(owner, "beacon-session"); app.cache.delete(owner, LEGACY_BEACON_SESSION_CACHE_KIND); app.cache.delete(scope, "client-created-shell"); app.cache.delete(scope, "session") }
            catch (error: Throwable) { if (error is CancellationException) throw error; notice("Your draft is safe. Refresh to finish.") }
            if (!current()) return
            if (_ui.value.tab == "Beacon") {
                sessionWatch?.cancel(); ++sessionGeneration; sourceScope = null; clearDetails(); _chat.value = ChatState()
                openBeacon(editorDraft = retained.text.takeIf { it.isNotBlank() })
                return
            }
        }
        try {
            app.cache.write(owner, "initial-draft", retained.encode(), retained.text.isNotBlank() || retained.attachments.isNotEmpty() || retained.queued.isNotEmpty())
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (current()) { _chat.update { it.copy(status = "Draft kept · a fresh conversation could not be prepared") }; notice(appErrorText(error)) }
            return
        }
        if (!current()) return
        if (saved.get<String>("session:${owner.key}") == id) saved.remove<String>("session:${owner.key}")
        var cleanupFailed = false
        try { if (app.cache.read(owner, "selected-session") == id) app.cache.delete(owner, "selected-session") }
        catch (error: Throwable) { if (error is CancellationException) throw error; cleanupFailed = true }
        if (!current()) return
        if (initialShells[owner.key] == id) {
            // Hermes confirmed this shell is gone, so the create attempt that produced it is settled even when only memory held the pointer.
            try {
                if (app.cache.read(owner, "new-chat-shell") == id) app.cache.delete(owner, "new-chat-shell")
                app.cache.delete(owner, "new-chat-attempt")
            }
            catch (error: Throwable) { if (error is CancellationException) throw error; cleanupFailed = true }
            initialShells.remove(owner.key)
            initialCreateAttempts.remove(owner.key)
        }
        try { app.cache.delete(scope, "client-created-shell"); app.cache.delete(scope, "session") }
        catch (error: Throwable) { if (error is CancellationException) throw error; cleanupFailed = true }
        if (!current()) return
        val running = currentCoroutineContext()[Job]
        if (newChatJobs[owner.key] === running) newChatJobs.remove(owner.key)
        drafts[owner.key] = retained
        val botReturn = normalModeReturn?.takeIf { it.string("session") == id }
        if (botReturn != null) {
            normalModeReturn = null
            try { app.cache.delete(modeCacheScope(owner), "bot-mode-return") }
            catch (error: Throwable) { if (error is CancellationException) throw error; cleanupFailed = true }
        }
        clearPendingVoiceKeyRequestsIfLeavingVoiceKeyPage("Chat", "")
        ++navigationGeneration; ++sessionGeneration
        sessionWatch?.cancel(); sourceScope = null; clearDetails(); _chat.value = ChatState()
        saved.remove<String>("settings-navigation")
        saved["tab"] = "Chat"; saved["more"] = ""
        _ui.update { it.copy(tab = "Chat", morePage = "", showSessions = false, initialChatReady = false, newChatOpening = false, newChatError = "", botModeEnabled = if (botReturn != null) false else it.botModeEnabled, botModeSwitching = if (botReturn != null) false else it.botModeSwitching, beaconOpening = false, beaconError = "") }
        if (cleanupFailed) notice("Your draft is safe. Refresh to finish.")
        if (client !== active || originGeneration != origin) return
        restoreInitialDraft(owner, origin, open = false)
        if (client === active && originGeneration == origin && canComposeInitialChat() && newChatJobs[owner.key]?.isActive != true) createNewChat(json("worktree" to false), initial = true)
    }
    fun logout() = viewModelScope.launch {
        linkOnNextStartup = false
        clearPendingVoiceKeyRequests()
        setupProbeJob?.cancel(); ++setupProbeGeneration
        val generation = ++originGeneration
        val credentialVersion = app.auth.authorityEpoch.readVersion()
        cancelStartup()
        cancelNewChatWork()
        val cached = cachedOwnerScope
        val current = client?.detachedForLogout() ?: cached?.let { owner ->
            withContext(Dispatchers.IO) { runCatching { BeaconClient(app.auth.read(owner.origin).also { it.requireCachedIdentity(owner) }, persistContext = false).detachedForLogout() }.getOrNull() }
        }
        val loopback = current?.auth?.loopbackSession != null
        try { checkpointVisible() } catch (e: Throwable) { fail(e); return@launch }
        if (generation != originGeneration) return@launch
        val origin = current?.auth?.origin ?: cached?.origin
        try {
            if (origin != null && !app.auth.removeIfUnchanged(origin, credentialVersion)) {
                if (generation == originGeneration) notice("Sign-in changed while signing out. Try again to sign out the current account.")
                return@launch
            }
        } catch (e: Throwable) { fail(e); return@launch }
        if (generation != originGeneration) return@launch
        (current?.scope ?: cached)?.let { forgetScreens(it) }
        val pushRevocations = runCatching { current?.let { app.push.prepareOwnerRevocation(it.scope) }.orEmpty() }
            .getOrElse {
                notice("Signed out on this phone. Push notification cleanup could not be confirmed: ${appErrorText(it)}")
                emptyList()
            }
        origin?.let { dev.hermes.android.push.KeepConnectedService.onUserSignedOut(app, it) }
        modeTransition?.cancelAndJoin()
        if (generation != originGeneration) return@launch
        (current?.scope ?: cached)?.let { optionalLocalSave { app.cache.delete(modeCacheScope(it), "bot-mode-return") } }
        if (generation != originGeneration) return@launch
        normalModeReturn = null
        _ui.update { it.copy(botModeEnabled = false, botModeSwitching = false) }
        app.calls.hangup(); streams.values.toList().forEach(Job::cancel); streams.clear(); sessionWatch?.cancel(); selectionJob?.cancel(); ++sessionGeneration
        loads.values.toList().forEach(Job::cancel)
        current?.let { frozen ->
            // Runs local push revocation before its first network suspension.
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching { try { app.push.revokeOwner(frozen, pushRevocations) } finally { frozen.logout() } }
                    .onFailure { if (generation == originGeneration) notice("Signed out on this phone. Push notification cleanup could not be confirmed: ${appErrorText(it)}") }
            }
        }
        client = null; cachedOwnerScope = null; sourceScope = null; retireSavedDrafts(null); _chat.value = ChatState(); _composer.value = TextFieldValue(); _data.value = emptyMap(); _attachments.value = emptyList(); clearDetails()
        val setupStep = if (loopback) "connect" else "signin"
        _ui.update { it.copy(authenticated = false, connected = false, loading = false, restoringSession = false, setupWizard = true, setupComplete = false, setupStep = setupStep, setupOrigin = if (loopback) origin.orEmpty() else it.setupOrigin, beaconOpening = false, beaconError = "", newChatOpening = false, newChatError = "", initialChatReady = false, sessions = emptyList(), query = "", owner = "", bootstrap = JSONObject()) }
        saveSetupProgress(setupStep, complete = false, setupOrigin = if (loopback) origin else null)
        // A load that began between the generation bump and here may have reopened the account; nothing can reopen it now.
        (current?.scope ?: cached)?.let { forgetScreens(it) }
    }
    fun tab(value: String) { if (_ui.value.botModeSwitching) return; botMode.back(); tab(value, resumeDraft = true) }
    private fun tab(value: String, resumeDraft: Boolean): Job? {
        val previous = _ui.value.tab
        if (value == "Beacon" && previous != "Beacon") noteAssistantReturn(previous)
        saved.remove<String>("settings-navigation")
        ++navigationGeneration
        clearPendingVoiceKeyRequestsIfLeavingVoiceKeyPage(value, "")
        _ui.update { it.copy(tab = value, morePage = "", showSessions = false) }; saved["tab"] = value; saved["more"] = ""
        var transition: Job? = null
        if (value == "Beacon" && _ui.value.authenticated) transition = openBeacon()
        else if (value != "Beacon") {
            ++beaconGeneration; _ui.update { it.copy(beaconOpening = false, beaconError = "") }
            if (assistantReturn() != null) transition = returnFromAssistant()
            if (value == "Chat" && resumeDraft && canComposeInitialChat()) {
                val active = client
                val owner = active?.scope ?: cachedOwnerScope
                val navigation = navigationGeneration
                _ui.update { it.copy(newChatError = if (initialCreateAttempts.containsKey(owner?.key) && !it.newChatOpening) initialCreateError else it.newChatError) }
                // Returning to Chat is a new user intent. Wait for its owned attempt,
                // then admit only this still-current return, never the earlier request.
                viewModelScope.launch {
                    newChatJobs[owner?.key]?.join()
                    // A caller that opens another chat right after tab() moves the navigation first, so no empty chat is created for it.
                    if (!initialShells.containsKey(owner?.key)) yield()
                    if (client === active && (client?.scope ?: cachedOwnerScope) == owner && navigationGeneration == navigation && canComposeInitialChat()) {
                        if (initialShells.containsKey(owner?.key)) resumeInitialChat() else openInitialChat()
                    }
                }
            }
        }
        return transition
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
                if (current()) storeData(key, (_data.value[key]?.copy() ?: JSONObject()).apply { remove("_loading"); remove("_refreshing"); put("_error", appErrorText(e)) })
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
        clearPendingVoiceKeyRequestsIfLeavingVoiceKeyPage("More", value)
        _ui.update { it.copy(tab = "More", morePage = value, showSessions = false) }
        saved["tab"] = "More"; saved["more"] = value
    }
    fun backFromSettings() {
        if (_ui.value.botModeSwitching) return
        // Inside Voice & calls, the header arrow steps back one page first, as system Back does.
        if (_voicePage.value != "home" && dev.hermes.android.ui.normalizeSettingsPage(_ui.value.morePage) in setOf("Beacon", "Voice & calls")) { _voicePage.value = "home"; return }
        val owner = (client?.scope ?: cachedOwnerScope)?.key.orEmpty()
        val history = saved.get<String>("settings-navigation")?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.string("scope") == owner }
        val routes = history?.optJSONArray("routes") ?: JSONArray()
        val previous = if (routes.length() > 0) routes.remove(routes.length() - 1) as? JSONObject else null
        val targetTab = previous?.string("tab")?.let(::migratedTab) ?: if (_ui.value.morePage.isBlank()) "Chat" else "More"
        val targetPage = previous?.string("page") ?: dev.hermes.android.ui.settingsParent(_ui.value.morePage)
        saved["settings-navigation"] = json("scope" to owner, "routes" to routes).toString()
        ++navigationGeneration
        clearPendingVoiceKeyRequestsIfLeavingVoiceKeyPage(targetTab, targetPage)
        _ui.update { it.copy(tab = targetTab, morePage = targetPage, showSessions = false) }
        saved["tab"] = targetTab; saved["more"] = targetPage
    }
    fun toggleSessions(show: Boolean = !_ui.value.showSessions) { _ui.update { it.copy(showSessions = show) } }
    /** The drawer's profile choice is for chats; on the assistant tab it applies when the Chat tab opens. */
    fun chooseProfile(name: String): Job = viewModelScope.launch {
        if (_ui.value.tab != "Beacon") { switchProfile(name).join(); return@launch }
        val record = json("profile" to name)
        setAssistantReturn(record); persistAssistantReturn(record); _ui.update { it.copy(chatProfile = name) }
        tab("Chat")
    }
    /** Where the chats were when the assistant tab took over: their profile and open conversation. */
    private fun assistantReturn(): JSONObject? = assistantReturnValue?.copy()
    private fun setAssistantReturn(value: JSONObject?) {
        assistantReturnValue = value?.copy(); assistantReturnInitialized = true
    }
    private suspend fun persistAssistantReturn(value: JSONObject?) {
        val persisted = value?.toString()
        app.preferences.edit { if (persisted == null) it.remove(stringPreferencesKey("assistant_return")) else it[stringPreferencesKey("assistant_return")] = persisted }
    }
    private fun noteAssistantReturn(previousTab: String) {
        if (assistantReturnInitialized && assistantReturnValue != null) return
        val profile = (client?.scope ?: cachedOwnerScope)?.profile ?: return
        val session = _chat.value.sessionId.takeIf { previousTab == "Chat" && it.isNotBlank() && it != (client?.scope ?: cachedOwnerScope)?.let(::readBeaconSessionHandle) }.orEmpty()
        _ui.update { it.copy(chatProfile = profile) }
        val record = json("profile" to profile, "session" to session)
        setAssistantReturn(record)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { persistAssistantReturn(record) }
    }
    private fun returnFromAssistant(): Job = viewModelScope.launch {
        val back = assistantReturn() ?: return@launch
        setAssistantReturn(null); persistAssistantReturn(null); _ui.update { it.copy(chatProfile = "") }
        val profile = back.string("profile")
        if (profile.isNotBlank() && (client?.scope ?: cachedOwnerScope)?.profile != profile) switchProfile(profile, false).join()
        val session = back.string("session")
        if (session.isNotBlank() && _ui.value.tab == "Chat" && (client?.scope ?: cachedOwnerScope)?.profile == profile) selectSession(session)
    }
    /** An explicit Settings change applies now; reopening a chat preserves its own later model choice. */
    private fun keepAssistantModel(model: String, provider: String) {
        val raw = _chat.value.raw
        if (model.isBlank() || raw.string("model") == model && (provider.isBlank() || raw.string("model_provider") == provider)) return
        val scope = sourceScope ?: return
        sessionAction("/api/session/update", json("model" to model, "model_provider" to provider.ifBlank { null })) { saved["model_pick:${scope.key}"] = true }
    }
    fun setAssistantModel(model: String, provider: String) = viewModelScope.launch {
        app.preferences.edit { it[stringPreferencesKey("assistant_model")] = model; it[stringPreferencesKey("assistant_provider")] = provider }
        if (_ui.value.tab == "Beacon") keepAssistantModel(model, provider)
    }
    fun setAssistantName(name: String) = viewModelScope.launch { app.preferences.edit { it[stringPreferencesKey("assistant_name")] = dev.hermes.android.data.assistantNameOf(name) } }
    fun setOwnerName(name: String) = viewModelScope.launch { app.preferences.edit { it[stringPreferencesKey("owner_name")] = dev.hermes.android.data.ownerNameOf(name) } }
    fun setAssistantProfile(profile: String) = viewModelScope.launch { app.preferences.edit { it[stringPreferencesKey("assistant_profile")] = profile } }
    fun switchProfile(name: String): Job = viewModelScope.launch {
        if (_ui.value.botModeEnabled) { toggleBotMode().join(); if (_ui.value.botModeEnabled) return@launch }
        switchProfile(name, false).join()
    }
    private fun switchProfile(name: String, retainBotMode: Boolean) = viewModelScope.launch {
        clearPendingVoiceKeyRequests()
        val c = client?.detached() ?: return@launch; val oldScope = c.scope; val generation = ++originGeneration
        val credentialVersion = app.auth.authorityEpoch.readVersion()
        val navigation = navigationGeneration
        if (!retainBotMode) botMode.clear()
        cancelStartup()
        cancelNewChatWork()
        try {
            checkpointVisible()
            if (generation != originGeneration || client?.scope != oldScope) return@launch
            c.post("/api/profile/switch", json("name" to name))
            val bootstrap = c.bootstrap()
            require(c.scope.sameAccount(oldScope)) { "Profile switch changed account identity. Sign in again." }
            if (generation != originGeneration || client?.scope != oldScope) return@launch
            if (!persistAuthIfCurrent(c.auth, credentialVersion, snapshot = true, selectProfileIf = { generation == originGeneration })) {
                if (generation == originGeneration && client?.scope == oldScope) notice("Sign-in changed while switching profiles. Reconnect and try again.")
                return@launch
            }
            if (generation != originGeneration || client?.scope != oldScope) return@launch
            val switchedAuth = withContext(Dispatchers.IO) { app.auth.read(c.auth.origin) }.takeIf { it.scope() == c.scope } ?: return@launch
            if (generation != originGeneration || client?.scope != oldScope) return@launch
            sessionWatch?.cancel(); selectionJob?.cancel(); sourceScope = null; ++sessionGeneration
            client = profileSwitchClientFactoryForTest?.invoke(switchedAuth) ?: BeaconClient(switchedAuth, app.auth, persistContext = false)
            cachedOwnerScope = null; retireSavedDrafts(null)
            val restoredClient = client ?: return@launch
            restorePhoneSettings(restoredClient)
            if (generation != originGeneration || client !== restoredClient) return@launch
            _data.value = emptyMap(); _chat.value = ChatState(); _composer.value = TextFieldValue(); _attachments.value = emptyList(); clearDetails()
            _ui.update { it.copy(profile = c.auth.profile, owner = c.auth.owner, bootstrap = bootstrap, beaconOpening = false, beaconError = "", newChatOpening = false, newChatError = "", initialChatReady = false, morePage = if (retainBotMode && navigation == navigationGeneration) "Bots" else if (retainBotMode) it.morePage else "", query = "", sessions = emptyList()) }
            hydrateScreens(restoredClient.scope)
            viewModelScope.launch {
                val index = try { app.cache.read(restoredClient.scope, "conversation-index")?.let(::JSONObject) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (index != null && generation == originGeneration && client === restoredClient && _ui.value.sessions.isEmpty()) _ui.update { it.copy(sessions = index.rows("sessions")) }
            }
            prepareStartup()
            val next = requireNotNull(client)
            val shell = app.cache.read(next.scope, "new-chat-shell")
            // Coming back to a profile brings back the chat that was open there; an unsent new chat returns with its draft.
            val selected = saved.get<String>("session:${next.scope.key}") ?: app.cache.read(next.scope, "selected-session")
            if (generation != originGeneration || client !== next) return@launch
            shell?.let { initialShells[next.scope.key] = it }
            if (!retainBotMode) {
                if (_ui.value.tab == "Beacon") openBeacon()
                else if (!selected.isNullOrBlank()) selectSession(selected, closeMenu = false)
                else restoreInitialDraft(next.scope, generation)
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
        require(active.scope.sameAccount(owner.scope()) && active.auth.profile == profile) { "Bot profile could not be opened" }
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
                        "query" to current.query, "archived" to sessionIndexArchived, "threads" to sessionIndexThreads)
                    app.cache.write(modeCacheScope(owner), "bot-mode-return", target.toString(), protected = true)
                    normalModeReturn = target
                    clearPendingVoiceKeyRequestsIfLeavingVoiceKeyPage("More", "Bots")
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
                    val tab = migratedTab(target.string("tab", "Chat"))
                    val page = target.string("page")
                    _ui.update { it.copy(botModeEnabled = false, tab = tab, morePage = page, query = target.string("query")) }
                    saved["tab"] = tab; saved["more"] = page
                    openInitialChat()
                    refreshSessions(target.string("query"), target.optBoolean("archived"), threads = target.optBoolean("threads"))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notice(appErrorText(error))
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
        startupJob?.cancel(); startupIndex?.cancel(); startupSettings?.cancel(); optionalProbe?.cancel(); prefetchJob?.cancel()
        preloadJobs.toList().forEach(Job::cancel); preloadJobs.clear()
        loads.values.toList().forEach(Job::cancel)
        ++sessionIndexRequest
        if (preparedBeacon?.first != client?.scope) preparedBeacon = null
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
            val pluginMessage = if (optional.string("plugin") == "absent") "Beacon plugin not installed on this Hermes — chat works; calls need the plugin." else ""
            _ui.update { state -> mergeOptionalFeaturesFor(state.bootstrap, owner, optional)?.let { state.copy(bootstrap = it, setupPluginMessage = pluginMessage) } ?: state }
            // The plugin is there: signing in is what links this phone, with nothing for the user to do.
            if (optional.string("plugin") in setOf("available", "unauthorized")) linkThisPhone(active)
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
                var beacon = ""
                if (owner.profile == preferences.value.assistantProfile) {
                    try {
                        beacon = preparedBeacon?.takeIf { it.first == owner }?.second ?: readBeaconSession(owner).orEmpty()
                        if (!current()) return@withTimeoutOrNull
                        if (beacon.isNotBlank()) {
                            preparedBeacon = owner to beacon
                            // The Beacon screen trusts only the saved handle; a cached chat restores it as connect() does.
                            writeBeaconSessionHandle(owner, beacon)
                            val response = c.get("/api/session", mapOf("session_id" to beacon), maxBytes = 1024 * 1024)
                            if (current() && sourceScope != owner.session(beacon)) app.cache.writeSession(owner.session(beacon), response.toString())
                        }
                    } catch (e: Throwable) { if (e is CancellationException) throw e; if (current() && e is ApiFailure && e.status == 401) fail(e) }
                }
                index.join()
                if (!current()) return@withTimeoutOrNull
                for (id in startupRecentSessions(_ui.value.sessions.map { it.string("session_id", it.string("id")) }, beacon)) {
                    if (!current()) break
                    try {
                        val response = c.get("/api/session", mapOf("session_id" to id), maxBytes = 1024 * 1024)
                        if (current() && sourceScope != owner.session(id)) app.cache.writeSession(owner.session(id), response.toString())
                    } catch (e: Throwable) { if (e is CancellationException) throw e; if (current() && e is ApiFailure && e.status == 401) fail(e) }
                }
            }
        }
        prefetchScreens(active, generation)
    }
    /** Alerts and calls go to the Beacon chat: tell the plugin which one, once per run. Hermes saves a chat only at its
     *  first message, so an unsaved chat is registered again after its first prompt or on a later run. */
    private fun registerAssistantChat(c: BeaconClient, id: String) {
        val key = c.scope.session(id).key
        if (!registeredAssistantChats.add(key)) return
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val response = try {
                withTimeoutOrNull(5_000L) { c.nativeRequest("/api/plugins/beacon/mobile/assistant-conversation", "PUT", json("profile" to c.scope.profile, "session_id" to id)) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (response == null) {
                registeredAssistantChats.remove(key)
                return@launch
            }
            assistantConversationToAdopt(id, response.string("conversation_id"))?.let { shared ->
                writeBeaconSessionHandle(c.scope, shared)
                optionalLocalSave { app.cache.write(c.scope, "beacon-session", shared, protected = true) }
                if (client?.scope == c.scope) preparedBeacon = c.scope to shared
                registeredAssistantChats.add(c.scope.session(shared).key)
                val openChat = _chat.value
                assistantConversationToSelect(sourceScope, openChat.sessionId, c.scope, id, shared, openChat.messages.isEmpty(), openChat.streamId)?.let { selectSession(it) }
            }
        }
    }
    /** The Beacon chat registered for this account; null only when the plugin is missing, has none or refuses this session, so a slow lookup fails instead of starting a second chat. */
    private suspend fun accountBeaconChat(c: BeaconClient): String? = try {
        // The plugin itself allows 8 s for this lookup.
        registeredBeaconChat(withTimeoutOrNull(10_000L) { c.nativeRequest("/api/plugins/beacon/mobile/assistant-conversation") } ?: throw java.io.IOException("Beacon chat lookup timed out"))
    } catch (e: ApiFailure) { if (beaconChatLookupMissing(e)) null else throw e }
    private suspend fun resolveBeacon(c: BeaconClient, fixed: AppPreferences, current: () -> Boolean): String = beaconResolveLock.withLock {
        preparedBeacon?.takeIf { it.first == c.scope }?.let { return@withLock it.second }
        val bound = readBeaconSession(c.scope)
        if (!bound.isNullOrBlank()) { require(current()) { "Account changed while preparing ${dev.hermes.android.data.currentAssistantName}" }; preparedBeacon = c.scope to bound; writeBeaconSessionHandle(c.scope, bound); registerAssistantChat(c, bound); return@withLock bound }
        // One Beacon chat per account: a phone without one takes the registered chat before it starts another.
        accountBeaconChat(c)?.let { shared ->
            require(current()) { "Account changed while preparing ${dev.hermes.android.data.currentAssistantName}" }
            writeBeaconSessionHandle(c.scope, shared)
            optionalLocalSave { app.cache.write(c.scope, "beacon-session", shared, protected = true) }
            preparedBeacon = c.scope to shared
            registerAssistantChat(c, shared)
            return@withLock shared
        }
        require(readBeaconOpening(c.scope) == null) { "${dev.hermes.android.data.currentAssistantName} opening outcome is unknown. Review history before creating another conversation." }
        currentCoroutineContext().ensureActive()
        require(current()) { "Account changed while preparing ${dev.hermes.android.data.currentAssistantName}" }
        app.cache.write(c.scope, "beacon-opening", "unknown", true)
        val result = c.post("/api/beacon/open", JSONObject().apply { if (fixed.assistantModel.isNotBlank()) { put("model", fixed.assistantModel); if (fixed.assistantProvider.isNotBlank()) put("model_provider", fixed.assistantProvider) } })
        val id = result.string("session_id", result.string("conversation_id", result.optJSONObject("session")?.string("session_id").orEmpty()))
        require(id.isNotBlank()) { "${dev.hermes.android.data.currentAssistantName} did not return a conversation" }
        val sessionScope = c.scope.session(id)
        clientCreatedSessions.add(sessionScope.key)
        // The owned creation already happened; a newer navigation must not discard its identity.
        writeBeaconSessionHandle(c.scope, id)
        if (client?.scope == c.scope) preparedBeacon = c.scope to id
        runCatching { app.cache.write(c.scope, "beacon-session", id, protected = true); app.cache.delete(c.scope, "beacon-opening"); app.cache.delete(c.scope, LEGACY_BEACON_OPENING_CACHE_KIND); app.cache.write(sessionScope, "client-created-shell", "1", protected = true) }.onFailure { if (it is CancellationException) throw it; if (client?.scope == c.scope) notice("${dev.hermes.android.data.currentAssistantName} opened; recovery checkpoint failed") }
        id
    }
    private var indexFetch: Job? = null
    private var indexFetchKey = ""
    private var indexReadAt = 0L
    /** Defaults keep the drawer's current view (archived/threads), so a background refresh never swaps the list under it. */
    fun refreshSessions(query: String = _ui.value.query, archived: Boolean = sessionIndexArchived, force: Boolean = true, threads: Boolean = sessionIndexThreads, allProfiles: Boolean = sessionIndexAllProfiles): Job {
        val active = client ?: return viewModelScope.launch {}
        val c = active.detached()
        val scope = c.scope
        val generation = originGeneration
        val credentialVersion = app.auth.authorityEpoch.readVersion()
        val hidden = preferences.value.historyHiddenSources.sorted().joinToString(",")
        val order = preferences.value.historyOrder
        val fetchKey = "${scope.key}:$generation:$query:$archived:$threads:$allProfiles:$hidden:$order"
        if (indexFetchKey == fetchKey) {
            if (!force) indexFetch?.takeIf { it.isActive }?.let { return it }
            if (!allProfiles && !force && startupReadFresh(indexReadAt, System.currentTimeMillis())) return viewModelScope.launch {}
        }
        indexFetchKey = fetchKey
        val request = ++sessionIndexRequest
        val revision = sessionIndexRevision
        sessionIndexArchived = archived
        sessionIndexThreads = threads
        sessionIndexAllProfiles = allProfiles
        _ui.update { it.copy(query = query, historyLoading = true) }
        fun current() = client === active && generation == originGeneration && _ui.value.authenticated && sessionIndexCurrent(request, sessionIndexRequest, revision, sessionIndexRevision)
        return viewModelScope.launch {
          try { supervisorScope {
            val assistantScope = scope.copy(profile = preferences.value.assistantProfile)
            val assistantSessionId = readBeaconSession(assistantScope).orEmpty()
            val params = mutableMapOf("profile" to scope.profile, "include_archived" to if (archived) "1" else "0", "hidden_sources" to hidden, "order" to order)
            if (threads) params["view"] = "threads"
            val path = if (query.isBlank()) "/api/sessions" else "/api/sessions/search"
            val profileIds = if (allProfiles) (listOf(scope.profile) + c.nativeAuthorizedProfiles()).distinct() else listOf(scope.profile)
            var staleRequest = false
            val unreadable = mutableListOf<String>()
            val serverRows = buildList {
                for (profile in profileIds) {
                    if (!current()) { staleRequest = true; break }
                    val profileClient = if (profile == scope.profile) c else c.forAuthorizedProfile(profile)
                    try {
                        val queryParams = params.toMutableMap().apply { put("profile", profile); if (query.isNotBlank()) put("q", query) }
                        val result = profileClient.get(path, queryParams)
                        val listed = result.rows("sessions").ifEmpty { result.rows("results") }
                        addAll(listed.map { it.copy().put("profile", profile) })
                    } catch (error: ApiFailure) {
                        // A profile whose gateway never ran has no store: Hermes answers 503, and its own profile sidebar lists it as empty.
                        if (error.status == 503 && error.detail.startsWith("Session store is not initialized")) continue
                        if (profile != scope.profile && error.status in setOf(403, 404)) continue
                        // One unreadable profile must not hide every other profile's chats.
                        if (!allProfiles || error.status == 401) throw error
                        unreadable += profile
                    } finally { if (profileClient !== c) profileClient.closeNative() }
                }
            }
            if (staleRequest || !current()) return@supervisorScope
            if (query.isBlank() && !archived) serverRows.forEach { row ->
                val rowScope = scope.copy(profile = row.string("profile", scope.profile))
                val key = "${rowScope.key}:${sessionRowId(row)}"
                val local = confirmedSessions[key]
                if (local != null && row.optInt("message_count") >= local.optInt("message_count")) confirmedSessions.remove(key)
            }
            val confirmed = (if (allProfiles) profileIds.flatMap { profile -> confirmedSessions.filterKeys { it.startsWith("${scope.copy(profile = profile).key}:") }.values.toList().asReversed() }
                else confirmedSessions.filterKeys { it.startsWith("${scope.key}:") }.values.toList().asReversed())
                .filter { !threads || dev.hermes.android.ui.isThreadSource(it.string("source")) }
            val rows = visibleSessionRows(serverRows, confirmed, query, archived)
            indexReadAt = System.currentTimeMillis()
            storeData("history:search", json("query" to query, "scope_key" to scope.key, "archived" to archived, "threads" to threads,
                "all_profiles" to allProfiles, "hidden_sources" to hidden, "order" to order, "sessions" to JSONArray(rows)))
            _ui.update { it.copy(sessions = if (query.isBlank()) rows else it.sessions, connected = true, assistantSessionId = assistantSessionId, historyUnreadable = unreadable) }
            if (current()) {
                if (!persistAuthIfCurrent(c.auth, credentialVersion, snapshot = true) || !current()) return@supervisorScope
                if (!allProfiles && query.isBlank() && !archived && !threads) app.cache.write(scope, "conversation-index", json("sessions" to JSONArray(rows)).toString())
            }

          } } catch (e: Throwable) { if (e is CancellationException) throw e; if (current()) fail(conversationActionFailure("/api/sessions", e)) }
          finally { if (request == sessionIndexRequest) _ui.update { it.copy(historyLoading = false) } }
        }.also { indexFetch = it }
    }
    private fun publishConfirmedSession(owner: Scope, id: String, response: JSONObject, accepted: Boolean = false) {
        if (client?.scope != owner || !_ui.value.authenticated) return
        val session = response.optJSONObject("session") ?: response
        val existing = _ui.value.sessions.firstOrNull { it.string("profile", owner.profile) == owner.profile && sessionRowId(it) == id }
        val row = (existing ?: session).copy().put("session_id", id).put("profile", owner.profile)
        if (row.string("title").isBlank()) row.put("title", _chat.value.title.takeIf { _chat.value.sessionId == id } ?: "New conversation")
        if (accepted) row.put("message_count", maxOf(1, row.optInt("message_count"))).put("active_stream_id", response.string("stream_id"))
        // Only server-confirmed IDs enter this temporary index overlay.
        confirmedSessions["${owner.key}:$id"] = row
        ++sessionIndexRevision
        if (_ui.value.query.isBlank() && !sessionIndexArchived && (!sessionIndexThreads || dev.hermes.android.ui.isThreadSource(row.string("source")))) _ui.update { it.copy(sessions = visibleSessionRows(it.sessions, listOf(row), "", false, owner.profile)) }
    }
    private fun initialDraftOwner(): Scope? = (client?.scope ?: cachedOwnerScope)?.takeIf { _ui.value.authenticated && sourceScope == null && _chat.value.sessionId.isBlank() }
    fun canComposeInitialChat(): Boolean = _ui.value.initialChatReady && _ui.value.tab == "Chat" && initialDraftOwner() != null
    /** A new chat with no conversation behind it leaves its model, approvals and call unusable: resume the saved empty one or create one, as New conversation does. A create whose outcome is unknown waits for Retry. */
    private fun openInitialChat() {
        val owner = client?.scope ?: return
        val ui = _ui.value
        if (!foreground || !canComposeInitialChat() || ui.setupWizard || ui.newChatOpening || ui.newChatError.isNotBlank() || initialCreateAttempts.containsKey(owner.key)) return
        if (initialShells.containsKey(owner.key)) resumeInitialChat() else createNewChat(json("worktree" to false), true)
    }
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
                    if (!_ui.value.initialChatReady) restoreInitialDraft(owner, origin, open = false)
                    if (!current()) return@launch
                    if (initialShells.containsKey(owner.key) && (initialCreateAttempts.containsKey(owner.key) || options.length() == 0)) {
                        resumeInitialChat().join()
                        if (!current()) return@launch
                    }
                    if (initialCreateAttempts.containsKey(owner.key)) { _ui.update { it.copy(newChatError = initialCreateError) }; notice("Tap Retry to open the chat. Your draft is kept."); return@launch }
                    // A legitimate New intent from Tasks/Spaces keeps the owner's unsent
                    // draft; explicit workspace/worktree/model options pass through intact.
                    if (options.length() > 0 || !initialShells.containsKey(owner.key)) createNewChat(options, initial = true).join()
                } catch (e: Throwable) { if (client === active && originGeneration == origin && navigationGeneration == navigation && initialDraftOwner() == owner) fail(e) }
            }
        }
        client?.scope?.key?.let { key -> newChatJobs[key]?.takeIf { !it.isCompleted }?.let { return it } }
        // Options (a thread's title, a workspace) ask for a different chat; a plain New conversation on an unsent new chat keeps it.
        val openScope = sourceScope
        if (body.length() == 0 && openScope != null && _ui.value.tab == "Chat" && openScope.copy(session = "") == client?.scope && _chat.value.sessionId == openScope.session &&
            readBeaconSessionHandle(openScope.copy(session = "")) != openScope.session &&
            reusableEmptyChat(clientCreatedSessions.contains(openScope.key), _chat.value, pending.containsKey(openScope.key)) &&
            // An unsent thread is not a plain chat: its first message would land in the thread.
            !pendingThreadTitles.containsKey(openScope.key) && !dev.hermes.android.ui.isThreadSource(_chat.value.raw.string("source"))
        ) {
            _ui.update { it.copy(showSessions = false) }
            return viewModelScope.launch {}
        }
        ++navigationGeneration
        return createNewChat(body, initial = false)
    }
    /** A named thread (Relay-style): a new native chat with its own source, titled by the user, not by Hermes's auto-title. */
    fun newThread(name: String): Job {
        val title = dev.hermes.android.ui.uniqueThreadTitle(name, _ui.value.sessions.map { it.string("title") })
        if (title.isBlank()) return viewModelScope.launch { notice("Name the thread first") }
        return newChat(json("title" to title, "thread" to true))
    }
    /** Hermes drops a first-turn title another conversation already holds (titles are unique per profile); keep the thread named. */
    private suspend fun keepThreadTitle(c: BeaconClient, scope: Scope, wanted: String, actual: String) {
        pendingThreadTitles.remove(scope.key)
        if (actual == wanted) return
        val candidates = listOf(wanted) + (2..9).map { "$wanted $it" }
        for ((index, candidate) in candidates.withIndex()) {
            try {
                c.post("/api/session/rename", json("session_id" to scope.session, "title" to candidate))
                if (sourceScope == scope) _chat.update { it.copy(title = candidate) }
                refreshSessions()
                return
            } catch (e: Throwable) { if (e is CancellationException || e !is ApiFailure || e.status != 4022 || index == candidates.lastIndex) throw e }
        }
    }
    /** Which project (if any) holds a conversation, as Hermes files it; read when a conversation menu opens. */
    // Keyed by its chat, so it is not tied to the open chat like session_id page reads are.
    fun loadConversationProject(id: String, force: Boolean = true) = load("project-of:$id", "/api/projects/for-session", mapOf("chat" to id), force = force)
    /** Pin, rename, archive, project and delete for any listed conversation; the open chat follows the change. */
    fun conversationAction(id: String, path: String, body: JSONObject = JSONObject(), done: String = "Saved", profile: String = client?.scope?.profile.orEmpty()): Job {
        val targetKey = client?.scope?.copy(profile = profile.ifBlank { client?.scope?.profile.orEmpty() }, session = id)?.key.orEmpty()
        val pendingKey = "$targetKey|$path"
        conversationChanges[pendingKey]?.takeIf { !it.isCompleted }?.let { return it }
        notice(conversationPendingLabel(path))
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
        val active = client ?: run { notice("Reconnect before changing this conversation."); return@launch }
        val c = if (profile.isBlank() || profile == active.scope.profile) active.detached() else active.forAuthorizedProfile(profile)
        val target = c.scope.session(id)
        try {
            val result = c.call(path, method = "POST", data = body.copy().put("session_id", id).put("scope_key", target.key))
            if (client !== active) return@launch
            if (sourceScope == target) _chat.update { chat ->
                when (path) {
                    "/api/session/rename" -> {
                        val title = result.optJSONObject("session")?.string("title")?.takeIf(String::isNotBlank) ?: result.string("title").takeIf(String::isNotBlank) ?: body.string("title")
                        chat.copy(title = title, raw = chat.raw.copy().put("title", title))
                    }
                    "/api/session/pin" -> chat.copy(raw = chat.raw.copy().put("pinned", body.getBoolean("pinned")))
                    "/api/session/archive" -> chat.copy(raw = chat.raw.copy().put("archived", result.optBoolean("hidden", body.optBoolean("archived"))))
                    else -> chat
                }
            }
            _ui.update { state -> state.copy(sessions = updatedHistoryRows(state.sessions, id, path, body, result, sessionIndexArchived, "", target.profile)) }
            _data.update { it - "history:search" }
            if (path == "/api/session/archive") confirmedSessions.remove(target.key)
            if (path.startsWith("/api/projects/")) {
                loadConversationProject(id)
                // The open chat's working folder changed with its project; show the new one.
                if (sourceScope == target) c.get("/api/session", mapOf("session_id" to id)).let { if (sourceScope == target && client === active) renderSession(it, target) }
            }
            if (path == "/api/session/delete") { confirmedSessions.remove(target.key); clientCreatedSessions.remove(target.key); optionalLocalSave { app.cache.delete(target, "client-created-shell") } }
            notice(done)
            if (path == "/api/session/archive" && body.optBoolean("archived")) _archiveUndo.value = target
            refreshSessions()
        } catch (e: Throwable) { if (e is CancellationException) throw e; if (client === active) fail(conversationActionFailure(path, e)) } finally { c.closeNative() }
        }
        conversationChanges[pendingKey] = job
        _conversationPending.update { it + pendingKey }
        job.invokeOnCompletion { if (conversationChanges[pendingKey] === job) { conversationChanges.remove(pendingKey); _conversationPending.update { it - pendingKey } } }
        job.start()
        return job
    }
    fun setHistoryFilters(hidden: Set<String>, order: String) = viewModelScope.launch {
        app.preferences.edit { it[stringPreferencesKey("history_hidden_sources")] = hidden.sorted().joinToString(","); it[stringPreferencesKey("history_order")] = order }
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
        val credentialVersion = app.auth.authorityEpoch.readVersion()
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
                if (request.optBoolean("thread")) request.string("title").takeIf(String::isNotBlank)?.let { pendingThreadTitles[createdScope.key] = it }
                clientCreatedSessions.add(createdScope.key)
                optionalLocalSave { app.cache.write(createdScope, "client-created-shell", "1", protected = true) }
                // An empty new chat joins History with its first message (accepted() publishes it); a named thread is listed at once.
                if (client === active && originGeneration == origin && request.optBoolean("thread")) publishConfirmedSession(owner, id, response)
                if (initial) {
                    initialShells[owner.key] = id
                    if (initialCreateAttempts[owner.key] == attempt) initialCreateAttempts.remove(owner.key)
                    runCatching { app.cache.write(owner, "new-chat-shell", id, protected = true); app.cache.delete(owner, "new-chat-attempt") }
                        .onFailure { if (it is CancellationException) throw it; if (current()) notice("Conversation opened; local recovery checkpoint failed") }
                }
                if (!current()) return@launch
                var authRetained = false
                optionalLocalSave { authRetained = persistAuthIfCurrent(c.auth, credentialVersion, snapshot = true) }
                if (!current()) return@launch
                if (!authRetained) { notice("Conversation opened on Hermes. Reconnect to continue; your draft is retained."); return@launch }
                clearPendingVoiceKeyRequestsIfLeavingVoiceKeyPage("Chat", "")
                _ui.update { it.copy(tab = "Chat", connected = true, newChatError = "") }; saved["tab"] = "Chat"
                selectSession(id, preserveInitialDraft = initial, initialResponse = response, closeMenu = !initial)
                refreshSessions()
            } catch (e: Throwable) {
                // Settle only the original owner's marker, even after navigation.
                // Retry waits for this job to finish, so its marker cannot race this exit.
                if (attempt != null && initialCreateAttempts[owner.key] == attempt) withContext(NonCancellable) {
                    runCatching { app.cache.write(owner, "new-chat-attempt", attempt, protected = true) }
                        .onFailure { if (current()) notice("The opening-attempt checkpoint could not be saved: ${appErrorText(it)}") }
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
            val resumed = c.nativeSession(id, lazy = true)
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
                        val confirmedNativeMissing = e is ApiFailure && e.status == 4007 && e.detail == "session not found"
                        // Hermes can keep a minted shell out of REST history until its first native resume.
                        if (confirmedNativeMissing) {
                            recoverMissingInitialShell(owner, id) { current() && ownsMissingShell() }
                            return@launch
                        }
                        if (clientCreated) {
                            clientCreatedSessions.add(scope.key)
                            optionalLocalSave { app.cache.write(scope, "client-created-shell", "1", protected = true) }
                            if (current()) {
                                selectSession(id, preserveInitialDraft = true, closeMenu = false)
                                selectionJob?.join()
                            }
                            return@launch
                        }
                        // A shell this phone did not create has no first-prompt ownership to protect: REST 404 is authoritative (F11).
                        recoverMissingInitialShell(owner, id) { current() && ownsMissingShell() }
                        return@launch
                    }
                    val attempt = java.util.UUID.randomUUID().toString()
                    initialCreateAttempts[owner.key] = attempt
                    runCatching {
                        app.cache.write(owner, "new-chat-attempt", attempt, protected = true)
                    }.onFailure { if (current()) notice("The opening-attempt checkpoint could not be saved: ${appErrorText(it)}") }
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
        val currentProfile = (client?.scope ?: cachedOwnerScope)?.profile.orEmpty()
        val targetProfile = row.string("profile", currentProfile)
        if (targetProfile.isNotBlank() && targetProfile != currentProfile) {
            viewModelScope.launch {
                switchProfile(targetProfile).join()
                if (client?.scope?.profile == targetProfile) openConversation(row)
            }
            return
        }
        val source = row.string("session_source", row.string("raw_source", row.string("source_tag", row.string("source")))).lowercase()
        if (source != "webui" && (row.optBoolean("is_cli_session") || source in setOf("telegram", "discord", "slack", "whatsapp", "signal", "matrix", "messenger", "sms", "email"))) {
            action("/api/session/import_cli", json("session_id" to id), refresh = false) { _, _ -> selectSession(id); refreshSessions() }
        } else selectSession(id)
    }
    fun retryCall(expectedId: String): Job = viewModelScope.launch {
        val target = app.calls.state.value.failedCallConversation(expectedId) ?: return@launch
        openCurrentCall(expectedId).join()
        val active = client?.takeIf { it.scope == target.copy(session = "") } ?: return@launch
        try { stopReadAloud(); app.calls.start(active, target.session) }
        catch (error: CancellationException) { throw error }
        catch (error: MissingVoiceKeyException) { openVoiceKeySettingsForCall(active, target.session) }
        catch (error: Exception) { notice(appErrorText(error)) }
    }
    fun closeCallFailure(expectedId: String): Job = viewModelScope.launch {
        if (app.calls.state.value.failedCallConversation(expectedId) == null) return@launch
        openCurrentCall(expectedId).join()
        app.calls.dismissFailure(expectedId)
    }
    fun openFailedCallOnce(expectedId: String) {
        val call = app.calls.state.value
        if (call.callId == expectedId && failedCallRedirect.claim(call)) openCurrentCall(expectedId)
    }
    fun openCurrentCall(expectedId: String = app.calls.state.value.callId): Job = viewModelScope.launch {
        val call=app.calls.state.value
        val target=call.conversationScope ?: return@launch
        val owner=client?.scope ?: cachedOwnerScope ?: return@launch
        if((!call.active&&call.failedCallConversation(expectedId)==null)||call.callId!=expectedId||owner.origin!=target.origin||owner.owner!=target.owner) return@launch
        if (_ui.value.tab == "Beacon") {
            val targetOwner = target.copy(session = "")
            val assistantSession = readBeaconSession(targetOwner)
            val currentCall=app.calls.state.value
            if (_ui.value.tab != "Beacon" || (client?.scope ?: cachedOwnerScope) != owner || currentCall.callId != expectedId || (!currentCall.active&&currentCall.failureReason.isBlank())) return@launch
            if (_ui.value.botModeSwitching) return@launch
            if (assistantSession == target.session) tab("Beacon", resumeDraft = false)?.join()
            else {
                val record = json("profile" to target.profile, "session" to target.session)
                setAssistantReturn(record); viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { persistAssistantReturn(record) }
                tab("Chat", resumeDraft = false)?.join()
                selectionJob?.join()
            }
            val selected = _ui.value.tab in setOf("Chat", "Beacon") && authenticatedScope() == target && _chat.value.projectionReady
            if(selected&&app.calls.state.value.active&&app.calls.state.value.callId==expectedId) app.calls.resumeWindow()
            return@launch
        }
        if(owner.profile!=target.profile) switchProfile(target.profile).join()
        val current=client?.scope ?: cachedOwnerScope
        val currentCall=app.calls.state.value
        if(current!=target.copy(session="")||currentCall.callId!=expectedId||(!currentCall.active&&currentCall.failureReason.isBlank())) return@launch
        tab("Chat");selectSession(target.session);selectionJob?.join()
        if(app.calls.state.value.active&&app.calls.state.value.callId==expectedId) app.calls.resumeWindow()
    }
    fun openBeacon(editorDraft: String? = null): Job {
        val active = client
        val owner = active?.scope ?: cachedOwnerScope
        val generation = ++beaconGeneration
        val connection = originGeneration
        val c = active
        fun current() = generation == beaconGeneration && connection == originGeneration && _ui.value.tab == "Beacon" && client === active && (client?.scope ?: cachedOwnerScope) == owner
        return viewModelScope.launch {
            val fixed = app.preferences.data.first().let(AppPreferences::read)
            if (!current()) return@launch
            if (owner == null || !_ui.value.authenticated) { _ui.update { it.copy(beaconOpening = false, beaconError = "Sign in again to open ${dev.hermes.android.data.currentAssistantName}'s conversation.") }; return@launch }
            val wanted = fixed.assistantProfile
            if (owner.profile != wanted) {
                // The assistant keeps its own profile; switching reopens this tab there, and leaving gives the chats' profile back.
                _ui.update { it.copy(beaconOpening = true, beaconError = "") }
                switchProfile(wanted, false).join()
                if (_ui.value.tab == "Beacon" && (client?.scope ?: cachedOwnerScope)?.profile != wanted) _ui.update { it.copy(beaconOpening = false, beaconError = "The $wanted profile could not be opened. Choose another in Settings › Assistant.") }
                return@launch
            }
            _ui.update { it.copy(beaconOpening = true, beaconError = "") }
            try {
                val id = if (c == null) readBeaconSession(owner).orEmpty()
                else preparedBeacon?.takeIf { it.first == owner }?.second ?: resolveBeacon(c, fixed) { current() }
                require(id.isNotBlank()) { "Reconnect to resolve ${dev.hermes.android.data.currentAssistantName}'s conversation. Your other conversation draft is retained." }
                if (!current()) return@launch
                selectSession(id, editorDraft = editorDraft, onCachedReady = { if (current()) _ui.update { it.copy(beaconOpening = false) } })
                selectionJob?.join()
                if (current()) _ui.update { it.copy(beaconOpening = false) }
            } catch (e: Throwable) { if (current() && e !is CancellationException) { _ui.update { it.copy(beaconOpening = false, beaconError = appErrorText(e)) }; fail(e) } }
        }
    }
    fun isBeaconConversation(): Boolean {
        val owner = client?.scope ?: cachedOwnerScope
        return canonicalConversationReady(owner, sourceScope, _chat.value.sessionId, true, owner?.let(::readBeaconSessionHandle), _ui.value.beaconOpening, _ui.value.beaconError.isNotBlank(), preferences.value.assistantProfile)
    }
    private fun admitConversation(): Boolean {
        val owner = client?.scope ?: cachedOwnerScope
        val ready = canonicalConversationReady(owner, sourceScope, _chat.value.sessionId, _ui.value.tab == "Beacon", owner?.let(::readBeaconSessionHandle), _ui.value.beaconOpening, _ui.value.beaconError.isNotBlank(), preferences.value.assistantProfile)
        if (!ready) notice(if (_ui.value.tab == "Beacon") "Open ${dev.hermes.android.data.currentAssistantName}'s conversation before sending or changing it. Your scoped draft is retained." else "Open a conversation before continuing.")
        return ready
    }
    fun selectSession(id: String, editorDraft: String? = null, preserveInitialDraft: Boolean = false, initialResponse: JSONObject? = null, closeMenu: Boolean = true, onCachedReady: (() -> Unit)? = null, titleHint: String = "") {
        selectionJob?.cancel()
        val navigation = ++navigationGeneration
        val generation = ++sessionGeneration
        val active = client
        val origin = originGeneration
        val ownerScope = active?.scope ?: cachedOwnerScope ?: return
        val c = active?.detached()
        selectionJob = viewModelScope.launch {
            try { checkpointVisible() } catch (e: Throwable) { fail(e); return@launch }
            if (generation != sessionGeneration || client !== active || (client?.scope ?: cachedOwnerScope) != ownerScope || preserveInitialDraft && navigationGeneration != navigation) return@launch
            sessionWatch?.cancel()
            val scope = ownerScope.session(id)
            fun current() = generation == sessionGeneration && origin == originGeneration && navigation == navigationGeneration && sourceScope == scope && client === active
            val previousEffort = _chat.value.takeIf { it.sessionId == id }?.sessionReasoningEffort.orEmpty()
            clientCreatedSessions.removeAll { it != scope.key }
            var clientCreated = initialShells[ownerScope.key] == id || clientCreatedSessions.contains(scope.key)
            var nativeResumeAttempted = false
            val initialDraft = if (preserveInitialDraft) draft() else null
            initialDraft?.let { drafts[scope.key] = it }
            sourceScope = scope; clearDetails()
            saved["session:${ownerScope.key}"] = id
            _ui.update { it.copy(showSessions = if (closeMenu) false else it.showSessions, newChatError = "") }
            heldTitle = titleHint.takeIf { it.isNotBlank() }?.let { Triple(scope, it, _chat.value.title) }
            _chat.value = ChatState(sessionId = id, title = titleHint.ifBlank { "New conversation" }, projectionReady = false, sessionReasoningEffort = previousEffort)
            if (!preserveInitialDraft) { _composer.value = TextFieldValue(); _attachments.value = emptyList() }
            else initialDraft?.let { restoreDraft(it, preserveActiveInput = true) }
            initialResponse?.let { renderSession(it, scope) }
            try {
                clientCreated = clientCreated || app.cache.read(scope, "client-created-shell") == "1" ||
                    (readBeaconSessionHandle(ownerScope) == id && app.cache.read(scope, "session") == null)
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
                val response = try { initialResponse?.takeIf { it.optBoolean("acknowledged_shell") } ?: c.get("/api/session", mapOf("session_id" to id)) }
                catch (readError: Throwable) {
                    if (readError is CancellationException) throw readError
                    if (readError !is ApiFailure || readError.status != 404 || !sessionCanStillBeUnpersisted(clientCreated, _chat.value) || !current()) throw readError
                    nativeResumeAttempted = true
                    val resumed = try { requireNotNull(active).nativeSession(id, lazy = true) }
                    catch (resumeError: Throwable) {
                        if (resumeError is CancellationException) throw resumeError
                        if (resumeError is ApiFailure && resumeError.status == 4007 && resumeError.detail == "session not found" && clientCreated) {
                            recoverMissingInitialShell(ownerScope, id) { current() }
                            return@launch
                        }
                        throw resumeError
                    }
                    require(resumed.scope == ownerScope && resumed.storedSessionId == id) { "The resumed conversation changed identity" }
                    if (!current()) return@launch
                    try { c.get("/api/session", mapOf("session_id" to id)) }
                    catch (afterResume: Throwable) {
                        if (afterResume is CancellationException) throw afterResume
                        if (afterResume !is ApiFailure || afterResume.status != 404) throw afterResume
                        val snapshot = requireNotNull(active).nativeSessionSnapshot()
                        require(snapshot.opt("message_count") is Number && snapshot.optInt("message_count") == 0 && snapshot.string("active_stream_id").isBlank() && !snapshot.optBoolean("running") && !snapshot.optBoolean("has_pending_user_message") && snapshot.opt("pending_user_message") in listOf(null, JSONObject.NULL, "")) {
                            "The saved conversation is no longer an empty draft. Open it from history or retry a new conversation."
                        }
                        (snapshot.optJSONObject("info")?.copy() ?: JSONObject()).apply {
                            snapshot.keys().forEach { put(it, snapshot.opt(it)) }
                            if (has("provider")) put("model_provider", opt("provider"))
                            if (has("cwd")) put("workspace", opt("cwd"))
                            put("session_id", id)
                            put("stored_session_id", id)
                            put("runtime_session_id", resumed.runtimeSessionId)
                            put("profile", ownerScope.profile)
                        }
                    }
                }
                if (!current()) return@launch
                renderSession(response, scope)
                // No per-chat effort read here: native config.get needs a resumed session, and browsing never resumes an agent.
                app.cache.writeSession(scope, response.toString())
                val session = response.optJSONObject("session") ?: response
                if (session.rows("messages").isNotEmpty() || session.optInt("message_count") > 0 || session.string("active_stream_id").isNotBlank()) {
                    clientCreated = false
                    clientCreatedSessions.remove(scope.key)
                    optionalLocalSave { app.cache.delete(scope, "client-created-shell") }
                }
                if (_chat.value.streamId.isNotBlank()) pollPrompts(c.detached(), scope)
                val reconciling = pending.containsKey(scope.key)
                if (reconciling) reconcile(scope, c.detached())
                // The receipt check reads history again; a turn it found running (a send that started while this chat loaded) needs its stream too.
                val active = (response.optJSONObject("session") ?: response).string("active_stream_id").ifBlank { if (reconciling && current()) _chat.value.streamId else "" }
                if (active.isNotBlank() && streams[scope.key]?.isActive != true) attachStream(c.detached(), scope, active)
                watchSession(c.detached(), scope)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (current()) {
                    if (e is ApiFailure && !e.plugin && e.status in setOf(401, 403)) {
                        val retainedDraft = draft()
                        val retainedEditor = _editor.value
                        drafts[scope.key] = retainedDraft
                        checkpointJob?.cancel(); editorCheckpoint?.cancel()
                        viewModelScope.launch {
                            runCatching {
                                app.cache.write(scope, "draft", retainedDraft.encode(), retainedDraft.text.isNotBlank() || retainedDraft.attachments.isNotEmpty() || retainedDraft.queued.isNotEmpty())
                                retainEditor(retainedEditor)
                            }.onFailure { if (generation == sessionGeneration) notice("The latest local checkpoint could not be saved; its draft remains on this phone.") }
                        }
                        _chat.value = ChatState(sessionId = id, status = "Access denied · draft retained")
                        _composer.value = TextFieldValue(); _attachments.value = emptyList(); _data.value = emptyMap(); clearDetails()
                        sourceScope = null
                        fail(e)
                        return@launch
                    }
                    val cached = _chat.value.raw.length() > 0
                    val pendingFirstTurn = !nativeResumeAttempted && e is ApiFailure && e.status == 404 && sessionCanStillBeUnpersisted(clientCreated, _chat.value)
                    _chat.update { it.copy(projectionReady = true, status = if (pendingFirstTurn) "" else when { missingNativeConversation(e) -> "Conversation unavailable · draft retained"; e is ApiFailure && e.status in setOf(401, 403) -> "Access denied · draft retained"; cached -> "Cached · refresh failed"; else -> "Could not load conversation · retry" }) }
                    if (!pendingFirstTurn) fail(e)
                }
            }
        }
    }
    private suspend fun restoreCachedConversation(scope: Scope, generation: Int, preserveActiveInput: Boolean = false): Boolean {
        fun current() = generation == sessionGeneration && sourceScope == scope && (client?.scope ?: cachedOwnerScope) == scope.copy(session = "")
        val cached = app.cache.readMany(scope, listOf("draft", "side-question", "session", "pending", "task-result"))
        if (!current()) return false
        cached["task-result"]?.let { raw ->
            val key = "task-result:${scope.key}"
            val card = runCatching { JSONObject(raw) }.getOrNull()
            if (card != null && card.optLong("expires_at") > System.currentTimeMillis() / 1000) {
                val shown = _data.value[key]
                val feedbackSent = shown?.let { it.optBoolean("feedback_sent") && it.string("notice_id") == card.string("notice_id") } == true
                if (!feedbackSent) {
                    if (shown == null || shown.optLong("expires_at") < card.optLong("expires_at")) storeData(key, card)
                }
            } else {
                optionalLocalSave { app.cache.delete(scope, "task-result", raw) }
                if (!current()) return false
                if (card != null && _data.value[key]?.string("notice_id") == card.string("notice_id")) storeData(key, JSONObject())
            }
        }
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
        // A cached runtime id is not a live binding; opening history must remain passive.
        session?.let { renderSession(it.apply { remove("runtime_session_id") }, scope) }
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
        val o = storedSessionSettings(response.optJSONObject("session") ?: response)
        val indexedRow = _ui.value.sessions.firstOrNull { it.string("session_id") == scope.session }
        val indexed = indexedRow?.opt("archived")
        val priorRaw = _chat.value.takeIf { it.sessionId == scope.session }?.raw
        val prior = priorRaw?.opt("archived")
        // Native live snapshots omit hidden state; preserve the native list/action receipt.
        (indexed as? Boolean ?: o.opt("archived") as? Boolean ?: prior as? Boolean)?.let { o.put("archived", it) }
        // They never carry the pinned state either; archiving drops the chat's History row, so keep what was last known.
        (indexedRow?.opt("pinned") as? Boolean ?: o.opt("pinned") as? Boolean ?: priorRaw?.opt("pinned") as? Boolean)?.let { o.put("pinned", it) }
        val userRows = o.rows("messages").filter { it.string("role") == "user" && it.string("row_id", it.string("_row_id")).isNotBlank() }
        val receipts = app.cache.readMany(scope, userRows.map { "attachments:${it.string("row_id", it.string("_row_id"))}" })
        for (row in userRows) {
            val id = row.string("row_id", row.string("_row_id"))
            receipts["attachments:$id"]?.let(::JSONObject)?.let { receipt ->
                val cached = receipt.rows("attachments").filter { (!it.has("scope_key") || it.string("scope_key") == scope.key) && !it.has("history_row_id") && !it.has("history_image_index") }
                val server = row.rows("attachments")
                val merged = server.map { attachment ->
                    cached.singleOrNull { attachment.string("path").isNotBlank() && it.string("path") == attachment.string("path") }?.copy()?.apply {
                        val name = string("name", attachment.string("name"))
                        attachment.keys().forEach { key -> put(key, attachment.opt(key)) }
                        put("name", name)
                    } ?: attachment
                }
                // Cached names remain useful, but only the current history may identify a saved image.
                row.put("attachments", JSONArray(merged + cached.filter { local ->
                    !local.string("mime").startsWith("image/", true) && server.none { it.string("path").isNotBlank() && it.string("path") == local.string("path") }
                }))
                row.put("display_text", receipt.string("display_text"))
            }
        }
        if (sourceScope != scope || expectedRevision != null && expectedRevision != chatHistoryRevision) return
        ++chatHistoryRevision
        renderedServerProjection = scope to response.toString()
        val held = heldTitle?.takeIf { it.first == scope }
        val loadedTitle = o.string("title")
        if (held != null && loadedTitle.isNotBlank() && loadedTitle != held.third) heldTitle = null
        val shownTitle = if (held != null) titleAfterLoad(held.second, held.third, loadedTitle) else loadedTitle
        _chat.update { old ->
            val rows = o.rows("messages")
            val active = (o.opt("active_stream_id") as? String)?.takeIf { it.matches(streamIdPattern) }
            val turn = currentTurnMessages(rows)
            val owned = historyToolIdentities(turn)
            val boundary = liveBoundaries["${scope.key}:${old.streamId}"] ?: LiveAssistantBoundary()
            val proof = persistedTurns["${scope.key}:${old.streamId}"]
            val finalRow = proof?.string("final_assistant_row_id").orEmpty()
            val historyOwnsLive = (finalRow.isNotBlank() && rows.any { it.string("row_id", it.string("_row_id")) == finalRow }) ||
                historyOwnsLiveBoundary(turn, old.liveTools, boundary.closed, boundary.toolIds)
            val streamEnded = historyOwnsLive || (!active.isNullOrBlank() && active != old.streamId)
            old.copy(projectionReady = true,
                sessionReasoningEffort = o.string("reasoning_effort").ifBlank { if (o.has("model_config")) "" else old.sessionReasoningEffort },
                approval = if (o.has("pending_approval")) o.optJSONObject("pending_approval") else old.approval,
                clarification = if (o.has("pending_clarification")) o.optJSONObject("pending_clarification") else old.clarification,
                sessionId = scope.session, title = shownTitle.ifBlank { old.title.takeIf { old.sessionId == scope.session } ?: "New conversation" }, messages = rows.mapIndexed { i, m -> Message.from(m, i, scope.session) }, raw = o,
                status = renderedChatStatus(old, active, streamEnded),
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
    internal fun firstCallTranscript(callId: String): CallTranscript? {
        if (callId.isBlank()) return null
        val scope = sourceScope ?: return null
        val state = _chat.value
        if (scope.copy(session = "") != client?.scope || state.sessionId != scope.session) return null
        return archivedVoiceTranscripts(scope, json(
            "session_id" to state.raw.string("session_id"),
            "profile" to state.raw.string("profile"),
            "events" to state.raw.optJSONArray("voice_transcripts"),
        )).firstOrNull { it.eventId.startsWith("$callId:") && it.text.isNotBlank() }
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
                val client = active.detached()
                var response = client.get("/api/session", mapOf("session_id" to scope.session))
                if (!foreground || !request.current()) return@launch
                if (!call.active) {
                    val session = response.optJSONObject("session") ?: response
                    val title = deriveCallEndTitle(session.string("title"), call.transcripts, dev.hermes.android.data.currentAssistantName)
                    if (title != null) {
                        val body = json("session_id" to scope.session, "title" to title)
                        client.post("/api/session/rename", body)
                        if (!foreground || !request.current()) return@launch
                        refreshSessions()
                        response = client.get("/api/session", mapOf("session_id" to scope.session))
                    }
                }
                if (!foreground || !request.current()) return@launch
                renderSession(response, scope)
                app.cache.writeSession(scope, response.toString())
            } catch (e: Throwable) { if (e is CancellationException) throw e }
        }
    }
    private fun restoreDraft(d: Draft, preserveActiveInput: Boolean = false) { if (!preserveActiveInput) { _composer.value = TextFieldValue(d.text, TextRange(d.selectionStart, d.selectionEnd)); _attachments.value = d.attachments }; _chat.update { it.copy(queued = d.queued, anchor = d.anchor, anchorOffset = d.anchorOffset, anchorId = d.anchorId) } }
    /** Relay's "Quote in reply": the message's excerpt goes above the draft as a Markdown quote, cursor at the end. */
    fun quoteInReply(text: String) {
        val excerpt = dev.hermes.android.ui.quoteExcerpt(text).takeIf(String::isNotBlank) ?: return
        val quoted = dev.hermes.android.ui.quotedDraft(excerpt, _composer.value.text)
        composer(TextFieldValue(quoted, TextRange(quoted.length)))
    }
    fun composer(value: TextFieldValue, fromEditor: Boolean = false) {
        if (value.text.length > 50_000) { notice("Draft is limited to 50,000 characters"); return }
        val previous = _composer.value.text
        _composer.value = value; checkpoint()
        if (value.text.isNotBlank()) openInitialChat()
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
        sentBaseline[scope.key] = send.id to sentCopies(_chat.value.messages, d.text)
        if (sourceScope == scope) _chat.update { it.copy(uncertain = send.id, status = "Sending") }
        try {
            // Nothing was sent if the receipt cannot be kept: settle the in-flight state instead of reporting an unknown outcome.
            try { app.cache.write(scope, "pending", send.encode(), true) }
            catch (e: Throwable) { if (e !is CancellationException) { pending.remove(scope.key, send); if (sourceScope == scope && _chat.value.uncertain == send.id) _chat.update { it.copy(uncertain = "", status = "") } }; throw e }
            val model = queued?.model ?: _chat.value.raw.string("model")
            val provider = queued?.provider ?: _chat.value.raw.string("model_provider")
            val o = c.post("/api/chat/start", json("session_id" to scope.session, "message" to d.text, "attachments" to JSONArray(d.attachments), "profile" to scope.profile, "client_request_id" to send.id, "model" to model.ifBlank { null }, "model_provider" to provider.ifBlank { null }, "explicit_model_pick" to (queued != null || saved.get<Boolean>("model_pick:${scope.key}") == true)))
            accepted(scope, c, o, send)
        } catch (e: Throwable) { if (e is CancellationException) throw e; if (e is ApiFailure && e.status == 409) { try { reconcile(scope, c) } catch (receiptError: Throwable) { if (receiptError is CancellationException) throw receiptError; if (sourceScope == scope) fail(receiptError) }; return@launch }; if (requestNeverSent(e) || e is ApiFailure && e.status in 400..499 && e.status !in setOf(408, 429)) { pending.remove(scope.key, send); optionalLocalSave { app.cache.delete(scope, "pending", send.encode()) }; if (sourceScope == scope && pending[scope.key] == null) _chat.update { it.copy(uncertain = "", status = "") } }; if (sourceScope == scope && _chat.value.uncertain == send.id) _chat.update { it.copy(status = "Send outcome unknown") }; if (sourceScope == scope) fail(e) }
    }
    private suspend fun accepted(scope: Scope, c: BeaconClient, result: JSONObject, send: PendingSend) {
        val sent = send.draft
        require(result.string("stream_id").isNotBlank()) { "Run was accepted but stream resolution is pending. Check receipt." }
        val canonical = result.string("session_id", scope.session)
        require(canonical == scope.session) { "Conversation identity changed; reconcile the receipt" }
        require(c.scope == scope.copy(session = "")) { "The receipt belongs to another account" }
        if (result.rows("attachment_receipts").isNotEmpty()) optionalLocalSave { app.cache.write(scope, "attachments-pending:${result.getString("stream_id")}", json("attachments" to result.optJSONArray("attachment_receipts"), "display_text" to sent.text).toString(), true) }
        if (result.has("user_row_id") && result.rows("attachment_receipts").isNotEmpty()) {
            optionalLocalSave { app.cache.write(scope, "attachments:${result.optLong("user_row_id")}", json("attachments" to result.optJSONArray("attachment_receipts"), "display_text" to sent.text).toString(), true) }
        }
        promptDelivered(scope, send, result, accepted = true)
        liveBoundaries["${scope.key}:${result.getString("stream_id")}"] = LiveAssistantBoundary()
        ++chatHistoryRevision
        if (sourceScope == scope) _chat.update { state ->
            val row = result.string("user_row_id")
            val receipt = result.optJSONArray("attachment_receipts") ?: JSONArray()
            val confirmed = json("row_id" to row, "role" to "user", "content" to sent.text, "attachments" to receipt, "timestamp" to System.currentTimeMillis() / 1000.0)
            val append = row.isNotBlank() && state.messages.none { it.id == row }
            state.copy(messages = if (append) state.messages + Message.from(confirmed, state.messages.size, scope.session) else state.messages,
                raw = if (append) state.raw.copy().put("messages", JSONArray(state.raw.rows("messages") + confirmed)) else state.raw,
                uncertain = "", streamId = result.getString("stream_id"), status = "Working", liveText = "", liveReasoning = "", liveTools = emptyList())
        }
        attachStream(c, scope, result.getString("stream_id"))
    }
    /** Hermes holds the prompt, whether its start was acknowledged or only found in history: the first-prompt markers, the receipt and the draft are done with. */
    private suspend fun promptDelivered(scope: Scope, send: PendingSend, response: JSONObject, accepted: Boolean) {
        val owner = scope.copy(session = "")
        // The creating socket owns only the first accepted prompt; later turns resume by stored identity.
        clientCreatedSessions.remove(scope.key)
        optionalLocalSave { app.cache.delete(scope, "client-created-shell") }
        publishConfirmedSession(owner, scope.session, response, accepted)
        if (client?.scope == owner) refreshSessions()
        if (initialShells[owner.key] == scope.session) { initialShells.remove(owner.key); optionalLocalSave { app.cache.delete(owner, "new-chat-shell") } }
        pending.remove(scope.key, send); optionalLocalSave { app.cache.delete(scope, "pending", send.encode()) }
        clearSentDraft(scope, send.draft)
        if (readBeaconSessionHandle(owner) == scope.session) viewModelScope.launch {
            delay(3_000)
            client?.takeIf { it.scope == owner }?.let { registerAssistantChat(it.detached(), scope.session) }
        }
    }
    fun reconcileSend() = viewModelScope.launch { val scope = sourceScope ?: return@launch; val c = client?.detached() ?: return@launch; try { reconcile(scope, c) } catch (e: Throwable) { if (sourceScope == scope) fail(e) } }
    private suspend fun reconcile(scope: Scope, c: BeaconClient) {
        val send = pending[scope.key] ?: return
        val result = c.get("/api/session", mapOf("session_id" to scope.session))
        if (sourceScope != scope || client?.scope != c.scope || pending[scope.key]?.id != send.id) return
        renderSession(result, scope)
        if (sendArrived(sentBaseline[scope.key]?.takeIf { it.first == send.id }?.second, _chat.value.messages, send.draft)) {
            // History holds the message: settle it as an accepted send does instead of asking again.
            promptDelivered(scope, send, result, accepted = false)
            if (sourceScope == scope) _chat.update { it.copy(uncertain = "", status = "") }
            notice("Your message arrived.")
            return
        }
        _chat.update { it.copy(uncertain = send.id, status = "Send outcome unknown · review history") }
        notice("Check whether your message arrived, then resend or dismiss.")
    }
    private suspend fun clearSentDraft(scope: Scope, sent: Draft) {
        val current = drafts[scope.key]
        if (current != null && current.afterAccepted(sent) != current) {
            val cleared = current.afterAccepted(sent)
            drafts[scope.key] = cleared; optionalLocalSave { app.cache.write(scope, "draft", cleared.encode(), cleared.queued.isNotEmpty()) }
            if (sourceScope == scope) restoreDraft(cleared)
        }
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
    fun cancel() = viewModelScope.launch {
        val c = client ?: return@launch
        val stream = _chat.value.streamId
        notice("Stopping the current turn")
        try {
            c.get("/api/chat/cancel", mapOf("stream_id" to stream))
            if (stream.isBlank() || _chat.value.streamId != stream) notice("")
        } catch (e: Throwable) {
            notice("")
            if (e is CancellationException) throw e
            fail(e)
        }
    }
    private fun attachStream(c: BeaconClient, scope: Scope, stream: String, terminalAlreadyObserved: Boolean = false, quietMs: Long = streamSilenceMs) {
        if (!foreground || client?.scope != c.scope || scope.copy(session = "") != c.scope) return
        streams[scope.key]?.cancel()
        streamClients[scope.key] = c
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
            var terminalObserved = terminalAlreadyObserved
            var terminalReplyVisible = false
            var heardAt = android.os.SystemClock.elapsedRealtime()
            if (terminalError.isNotBlank() && sourceScope == scope) _ui.update { it.copy(error = terminalError) }
            // A silent stream asks Hermes instead of waiting forever; only for the open chat, and less often while a long turn stays quiet.
            var quiet = quietMs
            val silence = if (terminalAlreadyObserved) null else launch {
                while (true) {
                    delay(streamWatchMs)
                    if (sourceScope != scope || android.os.SystemClock.elapsedRealtime() - heardAt < quiet) continue
                    val probe = c.detached()
                    val status = try { probe.recoveredNativeSessionStatus(scope.session) }
                    catch (e: Throwable) { if (e is CancellationException) throw e; null }
                    finally { probe.closeNative() }
                    when (silentStreamAction(status)) {
                        SilentStream.Settle -> attachStream(c, scope, stream, terminalAlreadyObserved = true)
                        SilentStream.Resubscribe -> attachStream(c, scope, stream, quietMs = minOf(quiet * 2, STREAM_QUIET_MAX_MS))
                        // Only Hermes' own answer stretches the window; a probe that failed checks again at the same pace.
                        SilentStream.Wait -> { heardAt = android.os.SystemClock.elapsedRealtime(); if (status != null) quiet = minOf(quiet * 2, STREAM_QUIET_MAX_MS); continue }
                    }
                    return@launch
                }
            }
            try {
                while (isActive && !terminalObserved) {
                    try {
                        heardAt = android.os.SystemClock.elapsedRealtime()
                        c.stream("/api/chat/stream", mapOf("stream_id" to stream, "session_id" to scope.session, "replay" to "1"), cursor.lastId) { event ->
                            heardAt = android.os.SystemClock.elapsedRealtime()
                            if (cursor.accept(event)) {
                                quiet = quietMs
                                if (event.type in setOf("done", "error")) terminalObserved = true
                                if (event.type == "done" && event.payload().string("status") == "interrupted") terminalError = "Interrupted"
                                if (event.type in setOf("apperror", "error")) terminalError = turnErrorText(event.payload())
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
                        renderSession(response, scope, revision)
                        app.cache.writeSession(scope, response.toString())
                        active = ((response.optJSONObject("session") ?: response).opt("active_stream_id") as? String)
                            ?.takeIf { it.matches(streamIdPattern) }
                        if (!active.isNullOrBlank() && active != stream) {
                            liveFrames.remove(frameKey); liveBoundaries.remove(frameKey); persistedTurns.remove(frameKey); app.cache.delete(scope, "live:$stream")
                            if (sourceScope == scope) _chat.update { it.copy(streamId = active, liveText = "", liveReasoning = "", liveTools = emptyList(), status = "Working") }
                            attachStream(c, scope, active)
                            return@launch
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403)) throw e
                    }
                    val liveStatus = try { c.recoveredNativeSessionStatus(scope.session) }
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
                            renderSession(response, scope, revision)
                            app.cache.writeSession(scope, response.toString())
                            val snapshot = response.optJSONObject("session") ?: response
                            terminalReplyVisible = currentTurnMessages(snapshot.rows("messages")).any { it.string("role") == "assistant" }
                            pendingThreadTitles[scope.key]?.let { wanted -> keepThreadTitle(c, scope, wanted, snapshot.string("title")) }
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
                        // An unconfirmed reply after a live terminal event keeps the queue for review; a validated idle settled it.
                        val q = _chat.value.queued.firstOrNull()?.takeIf { terminalReplyVisible || terminalAlreadyObserved && terminalError.isBlank() }
                        if (q != null && _composer.value.text.isBlank() && _attachments.value.isEmpty()) { removeQueued(0); _attachments.value = q.attachments; composer(TextFieldValue(q.text)); send(q) }
                    }
                }
            } catch (e: Throwable) { if (e is CancellationException) throw e; if (sourceScope == scope) fail(e) } finally {
                silence?.cancel()
                if (terminalObserved && sourceScope == scope) _chat.update { releaseTerminalChat(it, stream, terminalReplyVisible, terminalError) }
                if (streams[scope.key] == currentCoroutineContext()[Job]) { streams.remove(scope.key); streamClients.remove(scope.key) }
                // Release this chain's detached socket once no successor reuses it; never the main client (it may hold a creating runtime).
                if (c !== client && streamClients.values.none { it === c }) c.closeNative()
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
        if (event.type in setOf("apperror", "error")) _ui.update { it.copy(error = turnErrorText(o)) }
        _chat.update { s -> when (event.type) {
            "token" -> s.copy(liveText = (if (previousClosed) "" else s.liveText) + o.string("text"), liveReasoning = if (previousClosed) "" else s.liveReasoning, status = "Responding")
            "reasoning" -> s.copy(liveText = if (previousClosed) "" else s.liveText, liveReasoning = (if (previousClosed) "" else s.liveReasoning) + o.string("text"), status = "Thinking")
            "tool", "tool_complete" -> {
                val turn = currentTurnMessages(s.raw.rows("messages"))
                val ownsBody = historyOwnsLiveBoundary(turn, emptyList(), nextBoundary.closed, nextBoundary.toolIds)
                s.copy(liveText = if (ownsBody) "" else s.liveText, liveReasoning = if (ownsBody) "" else s.liveReasoning,
                    liveTools = if (toolIdentity(o).isNotBlank() && toolIdentity(o) in historyToolIdentities(turn)) s.liveTools else mergeLiveTool(s.liveTools, o),
                    status = if (event.type == "tool") "Using ${dev.hermes.android.ui.toolDisplayName(o)}" else "Working")
            }
            "interim_assistant" -> interimAssistantBody(s.liveText, s.liveReasoning, o).let { body ->
                val owned = historyOwnsLiveBoundary(currentTurnMessages(s.raw.rows("messages")), emptyList(), nextBoundary.closed, nextBoundary.toolIds)
                s.copy(liveText = if (owned) "" else body.text, liveReasoning = if (owned) "" else body.reasoning)
            }
            "title" -> s.copy(title = o.string("title").ifBlank { s.title })
            "approval" -> s.copy(approval = pendingPrompt(o))
            "clarify" -> s.copy(clarification = pendingPrompt(o))
            "request_cancel" -> s.copy(approval = s.approval?.takeUnless { it.string("id") == o.string("id") }, clarification = s.clarification?.takeUnless { it.string("id") == o.string("id") })
            "todo_state" -> s.copy(raw = s.raw.copy().put("todo_state", o))
            "goal" -> s.copy(raw = s.raw.copy().put("goal", o))
            "done", "state_saved" -> { val session = o.optJSONObject("session"); if (session != null) { val merged = s.raw.copy(); session.keys().forEach { merged.put(it, session.opt(it)) }; s.copy(messages = if (session.has("messages")) session.rows("messages").mapIndexed { i, m -> Message.from(m, i, scope.session) } else s.messages, raw = merged, liveText = if (session.has("messages")) "" else s.liveText, liveReasoning = if (session.has("messages")) "" else s.liveReasoning, liveTools = if (session.has("messages")) emptyList() else s.liveTools, status = "Finishing") } else if (event.type == "done") s.copy(status = "Finishing") else s }
            "metering" -> s.copy(raw = s.raw.copy().put("native_usage", o.optJSONObject("usage") ?: s.raw.optJSONObject("native_usage")).put("native_tps", if (o.optBoolean("tps_available") && !o.optBoolean("estimated") && o.optDouble("tps", 0.0) > 0) o.optDouble("tps") else JSONObject.NULL))
            "compressing" -> s.copy(status = "Compressing context")
            "compressed" -> s.copy(status = "Context compressed")
            "warning" -> s.copy(status = o.string("message", o.string("error", "Hermes needs your attention.")))
            "apperror", "error" -> s.copy(status = turnErrorText(o))
            else -> s
        } }
        if (event.type in setOf("done", "state_saved")) projectCallTranscripts()
        if (event.type in setOf("done", "state_saved", "cancel", "stream_end", "error", "apperror"))
            _ui.update { it.copy(notice = stoppingNoticeAfterEvent(it.notice, event.type)) }
    }
    private suspend fun pollPrompts(c: BeaconClient, scope: Scope) {
        val approval = runCatching { c.get("/api/approval/pending", mapOf("session_id" to scope.session)).optJSONObject("pending") }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        val clarify = runCatching { c.get("/api/clarify/pending", mapOf("session_id" to scope.session)).optJSONObject("pending") }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        if (sourceScope == scope) _chat.update { it.copy(approval = approval, clarification = clarify) }
    }
    private fun watchSession(c: BeaconClient, scope: Scope) {
        val active = client ?: return
        val request = ScopeSettlement(scope, active, originGeneration, sessionGeneration)
        if (!foreground || !request.current() || c.scope != active.scope) return
        sessionWatch?.cancel()
        sessionWatch = viewModelScope.launch {
            // Only the raw stamp carried by displayed history may establish the watcher baseline.
            var observedMetadata = displayedNativeHistoryStamp(_chat.value.raw)
            var reconciledAt = android.os.SystemClock.elapsedRealtime()
            while (isActive && foreground && request.current()) {
                delay(15_000)
                if (streams[scope.key]?.isActive == true) continue
                try {
                    val revision = chatHistoryRevision
                    val metadata = c.nativeRequest("/api/sessions/${scope.session}", params = mapOf("profile" to scope.profile))
                    require(metadata.string("id", metadata.string("session_id")) == scope.session && metadata.string("profile", scope.profile) == scope.profile) { "Conversation scope changed" }
                    if (!foreground || !request.current() || revision != chatHistoryRevision) continue
                    val stamp = nativeHistoryStamp(metadata)
                    // Reconcile the optional voice archive periodically even if SQLite metadata is unchanged.
                    if (!nativeHistoryNeedsRefresh(observedMetadata, stamp) && android.os.SystemClock.elapsedRealtime() - reconciledAt < 300_000L) {
                        observedMetadata = stamp
                        continue
                    }
                    val response = c.get("/api/session", mapOf("session_id" to scope.session))
                    if (!foreground || !request.current() || revision != chatHistoryRevision) continue
                    val projection = scope to response.toString()
                    if (renderedServerProjection != projection) {
                        renderSession(response, scope, revision)
                        if (!foreground || !request.current() || renderedServerProjection != projection) continue
                        app.cache.writeSession(scope, response.toString())
                    }
                    if (!foreground || !request.current() || renderedServerProjection != projection) continue
                    observedMetadata = displayedNativeHistoryStamp(_chat.value.raw)
                    reconciledAt = android.os.SystemClock.elapsedRealtime()
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
    fun sessionAction(path: String, body: JSONObject = JSONObject(), callback: (suspend (JSONObject) -> Unit)? = null) {
        if (!admitConversation()) return
        if (app.calls.state.value.active && app.calls.state.value.conversationScope == sourceScope && path in setOf("/api/session/delete", "/api/session/undo", "/api/session/compress")) { notice("End this call before changing its conversation."); return }
        val target = sourceScope ?: return
        val pendingKey = "${target.key}|$path"
        if (conversationChanges[pendingKey]?.isCompleted == false) return
        body.put("session_id", target.session)
        notice(conversationPendingLabel(path))
        action(path, body) { _, o -> callback?.invoke(o) }
    }
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
                        if (event.type in setOf("error", "apperror")) terminalError = turnErrorText(p)
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
                else if (_side.value?.scope == side.scope && _side.value?.streamId == side.streamId) _side.update { it?.copy(status = terminalError.ifBlank { "${appErrorText(e)} · reconnect available" }) }
            }
        }
        return sideJob
    }

    fun closeSideQuestion() { sideJob?.cancel(); sideCheckpointJob?.cancel(); val side = _side.value; _side.value = null; if (side != null) viewModelScope.launch { app.cache.delete(side.scope, "side-question") } }
    /** The newest cached answer of each screen key under [scope], marked stale until a fetch confirms it. */
    private suspend fun cachedScreens(scope: Scope): Map<String, JSONObject> = try {
        screens.open(scope)
        val invalidatedAt = readInvalidations[scope.key] ?: (app.cache.read(scope, "read-invalidated-at")?.toLongOrNull() ?: 0L).also { readInvalidations[scope.key] = it }
        val now = System.currentTimeMillis()
        withContext(Dispatchers.Default) { screens.latest(scope).associate { it.key to it.value.copy().put("_stale", !startupReadFresh(it.at, now, invalidatedAt)).put("_cached_at", it.at) } }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
    /** Screens render from the last good answers before any network call; answers already loaded in this run win. */
    private fun hydrateScreens(scope: Scope) {
        viewModelScope.launch {
            val cached = cachedScreens(scope)
            if ((client?.scope ?: cachedOwnerScope) == scope) _data.update { cached + it }
        }
    }
    /** For a screen that loads outside [load] (Kanban, devices): show its answer at once next time, and keep it on the phone. */
    fun keepScreen(scope: Scope, key: String, value: JSONObject) {
        if (client?.scope != scope) return
        storeData(key, value)
        viewModelScope.launch(Dispatchers.Default) { screens.set(scope, key, value) }
    }
    /** Device removal, sign-out and account change: nothing cached for the account outlives them (older per-read rows included). */
    private suspend fun forgetScreens(scope: Scope) {
        screens.clear(scope)
        try {
            startupReads.forEach { app.cache.delete(scope, "read:${it.path}") }
            listOf("settings:skills", "settings:memory").forEach { app.cache.delete(scope, it) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* those older rows expire after two days */ }
    }
    fun forgetScreens() { val scope = client?.scope ?: return; viewModelScope.launch { forgetScreens(scope) } }
    private val forgetOnDeviceRemoval: (Scope) -> Unit = { scope -> viewModelScope.launch { forgetScreens(scope) } }
    init { NativeDeviceRemoval.onForgetScreens = forgetOnDeviceRemoval }
    private var prefetchJob: Job? = null
    private var prefetched = "" to 0L
    /** The account whose sign-in ended without a sign-out; if a different account connects next, its cached screens go. */
    private var lapsedAccount: Scope? = null
    /** Warms what the drawer and Settings open next, two reads at a time, once the chat is usable and no reply, send or call is running. */
    private fun prefetchScreens(active: BeaconClient, generation: Int) {
        val scope = active.scope
        if (prefetched.first == scope.key && System.currentTimeMillis() - prefetched.second < PREFETCH_REWARM_AFTER) return
        fun current() = generation == originGeneration && client === active && _ui.value.authenticated && foreground
        fun idle() = !app.calls.state.value.active && _chat.value.streamId.isBlank() && pending.isEmpty()
        prefetchJob = viewModelScope.launch {
            var waited = 0L
            while (current() && waited < PREFETCH_READY_WAIT && !(_ui.value.initialChatReady || _chat.value.projectionReady)) { delay(250); waited += 250 }
            delay(PREFETCH_DELAY)
            if (!current()) return@launch
            val plugin = _ui.value.bootstrap.optJSONObject("optional_features")?.optString("plugin") == "available"
            val reads = (prefetchReads + StartupRead("files", "/api/pc/files", mapOf("path" to pcFolder()))).filter { plugin || "/plugins/beacon/" !in it.path }
            // Warming never cancels or replaces what a screen is loading or showing: only cold, stale or failed answers are fetched.
            fun cold(read: StartupRead) = loads[read.key]?.isActive != true && _data.value[read.key].let { shown ->
                shown == null || (shown.optBoolean("_stale") || shown.has("_error")) && shown.optString("_requested_path").let { it.isEmpty() || it == read.query["path"] } }
            val steps = reads.map<StartupRead, suspend () -> Unit> { read -> { if (cold(read)) ensureLoaded(read.key, read.path, read.query).join() } } + listOf<suspend () -> Unit>(
                { if (plugin) phoneSettings.loadBots()?.join() }, { prefetchKanban(active) }, { if (plugin) prefetchDevices(active) })
            val slots = kotlinx.coroutines.sync.Semaphore(PREFETCH_PARALLELISM)
            coroutineScope {
                steps.forEach { step -> launch {
                    slots.acquire()
                    try {
                        while (current() && !idle()) delay(1_000)
                        if (current()) step()
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { /* a read that fails stays cold until its screen opens */ }
                    finally { slots.release() }
                } }
            }
            if (current()) prefetched = scope.key to System.currentTimeMillis()
        }
    }
    private suspend fun prefetchKanban(active: BeaconClient) {
        val c = active.detached()
        try {
            val repo = NativeBoards(c) { key, document -> keepScreen(c.scope, key, document) }
            val (boards, current) = repo.choices()
            (current.takeIf { slug -> boards.any { it.slug == slug } } ?: boards.firstOrNull()?.slug)?.let { repo.read(it) }
        } finally { c.closeNative() }
    }
    private suspend fun prefetchDevices(active: BeaconClient) {
        val c = active.detached()
        try { keepScreen(c.scope, PAIRED_DEVICES_KEY, pairedDevicesJson(NativePairing.devices(c))) } finally { c.closeNative() }
    }
    fun ensureLoaded(key: String, path: String, query: Map<String, String> = emptyMap()): Job = load(key, path, query, force = false, quiet = true)
    /** A [quiet] read warms data nobody asked for yet: a failure stays on its key and never raises the app-wide error (an expired sign-in still does). */
    fun load(key: String, path: String, query: Map<String, String> = emptyMap(), force: Boolean = true, quiet: Boolean = false): Job {
        val active = client ?: return viewModelScope.launch { storeData(key, (_data.value[key]?.copy() ?: JSONObject()).apply { remove("_loading"); put("_error", "Reconnect to refresh this page.") }) }
        val c = active.detached(); val scope = c.scope; val generation = originGeneration
        val request = "${scope.key}:$generation:$path:${query.toSortedMap()}"
        if (loadRequests[key] == request) loads[key]?.takeIf { it.isActive }?.let { return it }
        loads[key]?.cancel()
        loadRequests[key] = request
        val session = query["session_id"]
        val cacheable = screenReadCacheable(path, query, scope.profile)
        val stored = screenRequest(path, query)
        fun visible() = client === active && generation == originGeneration && _ui.value.authenticated && loadRequests[key] == request && (session == null || sourceScope?.session == session)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
          try {
            if (cacheable) {
                // Stale-while-revalidate: the last good answer is shown at once, then replaced when the fetch lands.
                if (!visible()) return@launch // sign-out already cleared this account; opening it again would bring the row back
                screens.open(scope)
                val invalidatedAt = readInvalidations[scope.key] ?: (app.cache.read(scope, "read-invalidated-at")?.toLongOrNull() ?: 0L).also { readInvalidations[scope.key] = it }
                val at = loadedAt[request] ?: 0L
                if (!force && visible() && _data.value[key]?.has("_error") == false && _data.value[key]?.optLong("_cached_at") == at && _data.value[key]?.string("_request_key") == request && startupReadFresh(at, System.currentTimeMillis(), invalidatedAt)) return@launch
                if (!visible()) return@launch
                // The same answer this run already fetched (under any key) is reused; an older saved copy only fills the screen first.
                (screens.exact(scope, key, stored) ?: screens.sameRequest(scope, stored))?.takeIf { (loadedAt[request] ?: it.at) == it.at }?.let { cached ->
                    val fresh = startupReadFresh(cached.at, System.currentTimeMillis(), invalidatedAt)
                    loadedAt[request] = cached.at
                    _data.update { it + (key to cached.value.copy().put("_stale", !fresh).put("_cached_at", cached.at).put("_request_key", request)) }
                    if (fresh && !force) return@launch
                }
            }
            _refreshing.update { it + key }
            val response = c.get(path, query, maxBytes = if (cacheable && startupReads.any { it.path == path }) 1024 * 1024 else LOCAL_CHECKPOINT_LIMIT_BYTES.toInt())
            if (!visible()) return@launch
            val at = System.currentTimeMillis()
            if (cacheable) withContext(Dispatchers.Default) { screens.put(scope, key, stored, at, response) }
            if (key == "profile-settings") app.cache.write(scope, "profile-settings", response.toString())
            if (visible()) { loadedAt[request] = at; _data.update { it + (key to if (cacheable) response.copy().put("_cached_at", at).put("_request_key", request) else response) }; _ui.update { it.copy(connected = true) } }
          } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (visible()) {
                val failure = conversationActionFailure(path, e)
                _data.update { values -> values + (key to ((if (cacheable) values[key]?.copy() else null) ?: JSONObject()).put("_error", beaconOwnerSetupMessage(e) ?: appErrorText(failure)).apply { if (cacheable && length() > 1) put("_stale", true); if (path == "/api/commands/catalog") { put("available", false); if (!has("commands")) put("commands", JSONArray()) } }) }
                val chatId = query["chat"].orEmpty()
                val unsaved = path == "/api/projects/for-session" && unsavedOpenChatRead(e, chatId, clientCreatedSessions.contains(scope.session(chatId).key), _chat.value)
                if (!unsaved && ((force || !cacheable) && !quiet || e is ApiFailure && e.status == 401)) fail(failure)
            }
          } finally { c.closeNative(); if (loads[key] === currentCoroutineContext()[Job]) _refreshing.update { it - key } }
        }
        loads[key] = job; job.start(); return job
    }
    fun action(path: String, body: JSONObject = JSONObject(), method: String = "POST", refresh: Boolean = true, successNotice: String = "Saved", onSuccess: (suspend (BeaconClient, JSONObject) -> Unit)? = null): Job {
        val tracked = path in setOf("/api/session/update", "/api/session/branch", "/api/session/pin", "/api/session/archive", "/api/session/rename", "/api/session/delete", "/api/session/undo", "/api/session/compress", "/api/reasoning", "/api/approval/mode", "/api/profile/approval-mode", "/api/commands/exec")
        val pendingKey = if (tracked) "${client?.scope?.copy(session = body.string("session_id"))?.key}|$path" else null
        pendingKey?.let { conversationChanges[it]?.takeIf { job -> !job.isCompleted }?.let { job -> return job } }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
        val submission = _form.value?.takeIf { it.path == path && it.id == _submittingForm.value }?.id
        val active = client; val generation = originGeneration; val selection = sessionGeneration
        val credentialVersion = app.auth.authorityEpoch.readVersion()
        val c = active?.detached() ?: run { notice("Reconnect before saving changes."); return@launch }; val scope = c.scope; val selected = sourceScope
        fun current() = client === active && originGeneration == generation && (!body.has("session_id") || sourceScope == selected && sessionGeneration == selection)
        val removing = path == "/api/session/delete"
        var committed = false
        try {
            if (path in setOf("/api/session/update", "/api/reasoning", "/api/approval/mode", "/api/commands/exec")) {
                require(selected != null && selected.copy(session = "") == scope && body.string("session_id") == selected.session && body.string("scope_key", selected.key) == selected.key) { "Reopen this setting in its original chat." }
            }
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
                    confirmedSessions.remove(selected.key)
                    val archived = result.getBoolean("hidden")
                    _chat.update { it.copy(raw = it.raw.copy().put("archived", archived)) }
                    _ui.update { it.copy(sessions = updatedHistoryRows(it.sessions, selected.session, path, body, result, sessionIndexArchived, "", selected.profile)) }
                    _data.update { it - "history:search" }
                }
                if (path != "/api/profile/switch" && !persistAuthIfCurrent(c.auth, credentialVersion, snapshot = true)) {
                    if (current()) notice("Saved on Hermes. Sign-in changed; reconnect before making more changes.")
                    return@launch
                }
                if (!current()) return@launch
                val invalidation = System.currentTimeMillis(); readInvalidations[scope.key] = invalidation
                screens.invalidate(scope, path)
                val cacheError = runCatching { app.cache.write(scope, "read-invalidated-at", invalidation.toString()) }.exceptionOrNull()
                if (!current()) return@launch
                val message = when {
                    path == "/api/session/update" && result.optBoolean("deferred") -> "Model saved for the next reply"
                    path == "/api/session/archive" -> if (body.optBoolean("archived")) "Archived" else "Unarchived"
                    else -> successNotice
                }
                _ui.update { it.copy(connected = true, error = "", notice = if (cacheError == null) message else "$message. Refresh this page; the local settings cache could not be updated.") }
                if (path == "/api/session/archive" && body.optBoolean("archived")) _archiveUndo.value = selected
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
            } else if (current()) { val failure = conversationActionFailure(path, e); _form.update { if (submission != null && it?.id == submission) it.copy(error = appErrorText(failure)) else it }; fail(failure) }
        } finally {
            c.closeNative()
            if (removing && !committed && current() && selected != null) watchSession(active.detached(), selected)
        }
        }
        if (pendingKey != null) {
            conversationChanges[pendingKey] = job
            _conversationPending.update { it + pendingKey }
            job.invokeOnCompletion { if (conversationChanges[pendingKey] === job) { conversationChanges.remove(pendingKey); _conversationPending.update { it - pendingKey } } }
        }
        job.start()
        return job
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
            var chatLocked = false
            fun current() = client === active && (if (workspace) pcScope() else sourceScope) == scope && uploadJobs.containsKey(id)
            try {
                if (!workspace) { chatAttachmentLock.lock(); chatLocked = true }
                val resolver = app.contentResolver
                val details = withContext(Dispatchers.IO) {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) "" to null else {
                            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                            (if (nameColumn >= 0) cursor.getString(nameColumn).orEmpty() else "") to
                                sizeColumn.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)?.takeIf { it >= 0 }
                        }
                    } ?: ("" to null)
                }
                name = details.first.takeIf(String::isNotBlank) ?: "Attachment"
                if (!current()) return@launch
                if (!workspace) _attachmentPreparation.update { it + json("id" to id, "name" to name, "status" to "preparing") }
                uploadSlots.withPermit {
                    if (!current()) return@withPermit
                    val mime = resolver.getType(uri) ?: "application/octet-stream"
                    val sizeHint = details.second
                    if (!workspace && sizeHint != null && sizeHint > MAX_CHAT_ATTACHMENT_BYTES) error("Attachments are limited to about 11 MiB in total")
                    val file = File.createTempFile("upload", ".data", app.cacheDir)
                    try {
                        withContext(Dispatchers.IO) {
                            resolver.openInputStream(uri)?.use { input -> file.outputStream().use { out ->
                                val bytes = ByteArray(32768); var total = 0L
                                while (true) {
                                    ensureActive()
                                    val n = input.read(bytes); if (n < 0) break
                                    total += n; require(total <= if (workspace) 32L * 1024 * 1024 else MAX_CHAT_ATTACHMENT_BYTES) { if (workspace) "Attachment exceeds 32 MiB" else "Attachments are limited to about 11 MiB in total" }
                                    out.write(bytes, 0, n)
                                }
                            } } ?: error("Could not read attachment")
                        }
                        val response = c.upload(if (workspace) "/api/workspace/upload" else "/api/upload", file, name, mime, scope.session, directory)
                        val items = if (workspace) emptyList() else response.rows("files").ifEmpty { listOf(response.optJSONObject("file") ?: response) }
                        if (!workspace && !withContext(Dispatchers.IO) { attachmentFitsLocalCheckpoint(PendingSend.create(scope, draft().let { it.copy(attachments = it.attachments + items) })) }) error("Attachments are limited to about 11 MiB in total")
                        if (current()) {
                            if (workspace) {
                            notice("File uploaded")
                            if (loadRequests["files"] == listingRequest) {
                                loads["files"]?.cancelAndJoin()
                                if (current() && loadRequests["files"] == listingRequest) load("files", "/api/pc/files", mapOf("path" to listingDirectory)).join()
                            }
                        }
                            else {
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
                    else _attachmentPreparation.update { rows -> rows.filterNot { it.string("id") == id } + json("id" to id, "name" to name, "status" to "error", "error" to (appErrorText(e))) }
                }
            } finally {
                if (chatLocked) chatAttachmentLock.unlock()
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
            } catch (e: Throwable) { if (current()) { if (_editor.value.scope == request.scope && _editor.value.path == target) _editor.update { it.copy(loading = false, error = appErrorText(e)) }; fail(e) } } finally { c.closeNative() }
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
        } catch (e: Throwable) { if (_editor.value.scope == scope && _editor.value.path == editor.path) { _editor.update { it.copy(saving = false, error = "Save not confirmed. The local edit is retained. ${appErrorText(e)}") }; fail(e) } }
    }
    fun exportFile(uri: Uri, path: String, folder: Boolean = false, pc: Boolean = false) = viewModelScope.launch {
        val scope = (if (pc) pcScope() else sourceScope) ?: return@launch
        val c = client?.detached() ?: return@launch
        fun current() = client?.scope == c.scope && (if (pc) pcScope() else sourceScope) == scope
        try {
            exportDownload(c, uri, if (pc) "/api/files/download" else if (folder) "/api/folder/download" else "/api/file/raw", mapOf("session_id" to scope.session, "path" to path), 256L * 1024 * 1024)
            if (current()) notice("Download saved")
        } catch (e: Throwable) { if (e is CancellationException) throw e else if (current()) notice(dev.hermes.android.ui.fileDownloadError(e)) } finally { c.closeNative() }
    }

    fun readAloud(text: String) {
        if (app.calls.state.value.active) { notice("End the current call before starting read aloud."); return }
        stopReadAloud()
        if (text.isBlank()) return
        val sequence = readSequence
        val source = sourceScope
        _readingAloud.value = true
        fun speak() { if (readSequence != sequence || sourceScope != source) return; speech?.language = java.util.Locale.getDefault(); if (speech?.speak(text.take(5000), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, sequence.toString()) == android.speech.tts.TextToSpeech.ERROR) { _readingAloud.value = false; notice("Android read aloud failed") } }
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
        try { dev.hermes.android.voice.declineIncoming(app, source, event); notice("Call declined.") }
        catch (e: Throwable) { if (e !is CancellationException) fail(e) }
        finally { _incomingBusy.value = false }
    }
    fun handlePush(source: String, event: String, action: String = "") = viewModelScope.launch {
        val startsCall=action in setOf("answer","callback")
        if (startsCall) { if (_incomingBusy.value) return@launch; _incomingBusy.value = true }
        var generation = -1
        var temporaryClient: BeaconClient? = null
        try {
            val credentialVersion=app.auth.authorityEpoch.readVersion()
            require(dev.hermes.android.push.validOpaque(source) && dev.hermes.android.push.validOpaque(event)) { "Notification route is invalid" }
            val binding = app.push.binding(source) ?: error("Sign in to the owner account before opening this notification")
            val origin = trustedOrigin(binding.getString("origin"))
            val owner = binding.getString("owner")
            val notificationScope = Scope(origin, owner, binding.string("profile", "default"), nativeProvider = binding.string("native_provider"))
            require(app.auth.read(origin).authorizesPushSource(notificationScope)) { "Sign in to the notification's owner account before opening this activity" }
            modeTransition?.cancelAndJoin()
            setupProbeJob?.cancel(); ++setupProbeGeneration
            clearPendingVoiceKeyRequests()
            generation = ++originGeneration
            cancelStartup()
            cancelNewChatWork()
            val c = app.push.clientFor(binding)
            temporaryClient = c
            val bootstrap = c.bootstrap()
            require(c.scope == notificationScope) { "Notification authorization no longer matches this account" }
            val route = try { c.nativeRequest("/api/plugins/beacon/mobile/notifications/events/$event") }
            catch (pruned: ApiFailure) {
                if (!missedCallGone(action, pruned)) throw pruned
                val missed = requireNotNull(app.push.deadlineInvitation(source, event)) { "This missed call is no longer available" }
                missedCallRouteFromHistory(c.nativeRequest("/api/plugins/beacon/mobile/calls", params = mapOf("limit" to "50")), missed, c.scope) ?: throw pruned
            }
            require(route.string("profile") == c.auth.profile && route.string("source_ref") == source) { "Notification belongs to another profile" }
            require(app.push.binding(source) != null && app.auth.read(origin).authorizesPushSource(notificationScope)) { "The notification's owner signed out" }
            if (action=="callback") dev.hermes.android.voice.requireMissedCallbackRoute(route,requireNotNull(app.push.deadlineInvitation(source,event)) { "This missed call is no longer available" },c.scope)
            else if (dev.hermes.android.voice.incomingRouteEnded(route, source, c.auth.profile)) app.push.discardInvitation(source, event)
            if (action == "answer") dev.hermes.android.voice.requireIncomingRoute(route, source, c.auth.profile)
            val id = route.string("session_id")
            // The saved handle is empty after a cold start; the prepared chat or the cache still name the Beacon chat. Read before the guard below.
            val beaconChat = preparedBeacon?.takeIf { it.first == c.scope }?.second ?: readBeaconSession(c.scope)
            if (generation != originGeneration) return@launch
            checkpointVisible()
            if (generation != originGeneration) return@launch
            clearDetails(); retireSavedDrafts(null); sessionWatch?.cancel(); selectionJob?.cancel(); sourceScope = null; ++sessionGeneration
            if (app.calls.state.value.active && !keepsRestoredCall(app.calls.state.value.conversationScope,c.scope,false)) app.calls.hangup()
            require(persistAuthIfCurrent(c.auth, credentialVersion, snapshot = true, selectProfileIf = { generation == originGeneration })) { "Sign-in changed while opening this notification" }
            if (generation != originGeneration) return@launch
            val refreshedAuth = withContext(Dispatchers.IO) { app.auth.read(origin) }
            require(refreshedAuth.authorizesPushSource(notificationScope) && refreshedAuth.profile == notificationScope.profile) { "Sign-in changed while opening this notification" }
            client = profileSwitchClientFactoryForTest?.invoke(refreshedAuth) ?: BeaconClient(refreshedAuth, app.auth, persistContext = false)
            cachedOwnerScope = null
            val restoredClient = requireNotNull(client)
            restoreBotMode(restoredClient.scope)
            if (generation != originGeneration || client !== restoredClient) return@launch
            restorePhoneSettings(restoredClient)
            if (generation != originGeneration || client !== restoredClient) return@launch
            _data.value = emptyMap()
            hydrateScreens(restoredClient.scope)
            _chat.value = ChatState(); _composer.value = TextFieldValue(); _attachments.value = emptyList()
            if (route.string("kind") == "task_result" && id.isNotBlank()) {
                val emailAlert = route.optBoolean("email_alert")
                val expiresAt = route.optLong("expires_at")
                route.string("reason").takeIf { it.isNotBlank() && (!emailAlert || expiresAt > System.currentTimeMillis() / 1000) }?.let {
                    storeData("task-result:${c.scope.session(id).key}", json("reason" to it, "notice_id" to route.string("event_id").ifBlank { event }, "email_alert" to emailAlert, "expires_at" to expiresAt))
                }
            }
            // The Beacon screen trusts only the saved handle, which a notification cold start skips (connect() never runs); restore it as connect() does.
            beaconChat?.let { writeBeaconSessionHandle(c.scope, it) }
            val destination = pushDestination(route.string("kind"), id, beaconChat)
            saved["tab"] = destination; saved["more"] = ""
            _ui.update { it.copy(origin = origin, owner = owner, profile = c.auth.profile, beaconOpening = false, beaconError = "", newChatOpening = false, newChatError = "", initialChatReady = false, query = "", authenticated = true, connected = true, restoringSession = false, bootstrap = bootstrap, tab = destination, morePage = "") }
            prepareStartup()
            if (route.string("kind") == "incoming_call") {
                if (action!="callback") { if (route.string("state") == "pending") app.push.presentInvitation(source, event) else app.push.discardInvitation(source, event) }
                // A call belongs to the chat it was asked from; open that chat so the call screen shows there, not a bar over another chat.
                if (id.isNotBlank() && beaconChat != id) selectSession(id) else openBeacon()
                if (action == "answer" && generation == originGeneration) {
                    stopReadAloud()
                    require(app.push.authorizeKeepConnectedAction(source, event, "answer")) { "This ${dev.hermes.android.data.currentAssistantName} invitation has ended" }
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
            val gone = missedCallGone(action, e)
            if (action == "answer" && (dev.hermes.android.voice.incomingFailureTerminal(e) || !app.push.permitsIncoming(source)) || gone) app.push.discardInvitation(source, event)
            val needsVoiceKey=e is MissingVoiceKeyException&&startsCall&&generation==originGeneration
            if(needsVoiceKey) { pendingIncomingAfterKey=Triple(source,event,action);more(voiceKeySetupPage()) }
            if (e !is CancellationException && !needsVoiceKey && (generation == -1 || generation == originGeneration)) _ui.update { it.copy(loading = false, restoringSession = false, error = if (e is ApiFailure && e.status == 401) "Sign in to the notification's owner account to open this activity" else if (gone) "This missed call has expired. Call from the chat." else if (action == "callback") "Couldn't start the call. Try again." else appErrorText(e)) }
        }
        finally { temporaryClient?.closeNative(); if (startsCall) _incomingBusy.value = false }
    }
    override fun onCleared() { if (NativeDeviceRemoval.onForgetScreens === forgetOnDeviceRemoval) NativeDeviceRemoval.onForgetScreens = null; networkCallback?.let { runCatching { app.getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }; botMode.clear(); sessionWatch?.cancel(); stopReadAloud(); speech?.shutdown(); super.onCleared() }
}
