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
import dev.hermes.android.ui.AttachedMedia
import dev.hermes.android.ui.CommandReport
import dev.hermes.android.ui.commandNoticeText
import dev.hermes.android.ui.commandRanClearsDraft
import dev.hermes.android.ui.commandReportIsLong
import dev.hermes.android.ui.refreshPageNotice
import dev.hermes.android.ui.ChatGlances
import dev.hermes.android.ui.GLANCE_PROFILES_KIND
import dev.hermes.android.ui.chatPreviewText
import dev.hermes.android.ui.decodeGlanceProfiles
import dev.hermes.android.ui.decodeGlances
import dev.hermes.android.ui.encodeFor
import dev.hermes.android.ui.epochMillis
import dev.hermes.android.ui.glanceProfilesJson
import dev.hermes.android.ui.glanceProfilesToForget
import dev.hermes.android.ui.mentionsHermesNote
import dev.hermes.android.ui.rowActivityMillis
import dev.hermes.android.ui.splitAttachedMedia
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallTranscript
import dev.hermes.android.voice.FailedCallRedirect
import dev.hermes.android.voice.failedCallConversation
import dev.hermes.android.voice.transcriptsFor
import dev.hermes.android.voice.deriveCallEndTitle
import dev.hermes.android.voice.renameCallChat
import dev.hermes.android.voice.interleaveCallMessages
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
/** Value of the client-created-shell marker for a chat opened with options; a plain one keeps "1". */
private const val KEPT_APART_SHELL = "apart"
/** S1: how long the box's Send waits for Hermes' answer about the turn before it says it could not reach Hermes. */
internal const val EXPLICIT_DRAIN_LOOK_MS = 10_000L
/** Tries (and the wait between them) the command list takes to load again after a profile switch. */
internal const val COMMAND_RELOAD_TRIES = 3
internal const val COMMAND_RELOAD_DELAY_MS = 2_000L
/** Per-conversation marker (the cache is scoped by origin, account, profile and session): Beacon delivered a prompt here and has not seen that reply end. It survives process death and expires with the cache. */
private const val RUN_MARKER = "running-turn"
/** R2C: the turn the open chat shows ({"stream_id", "submission_id"}), kept with the chat so a cold start paints it on its first frame. */
internal const val LIVE_TURN = "live-turn"
/** How many finished turns a thread's wanted name is asked for when Hermes refuses it for a reason other than "that title is taken". */
internal const val THREAD_TITLE_FAILED_TURNS = 2
/** How long a Beacon call asked for while the app is still reaching Hermes waits for the connection: the overlay's own restore budget. */
private const val BEACON_CALL_CONNECT_MS = 15_000L
/** How long a Beacon call watches the start it asked for, to meet a refused grant or context ([beaconCallStartFailure]). */
private const val BEACON_CALL_START_WATCH_MS = 30_000L
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
internal fun pushDestination(kind: String, id: String, beaconChat: String?, assistantChat: Boolean = false): String {
    if (kind == "task_result" && id.isBlank()) return "Tasks"
    // The Beacon chat's own notice: Hermes says so (also after it moved the chat to a new session id), or the chat it names is the one this phone keeps.
    val beacon = assistantChat || id.isNotBlank() && id == beaconChat
    return when {
        kind in setOf("incoming_call", "task_result") && (id.isBlank() || beacon) -> "Beacon"
        kind in setOf("completion", "failure", "approval", "clarify") && beacon -> "Beacon"
        else -> "Chat"
    }
}
/** The plugin has no Beacon chat for this account (404), is too old to say (405) or refuses this session (401 on a dashboard without sign-in, 403); anything else is a failed lookup. */
internal fun beaconChatLookupMissing(error: ApiFailure): Boolean = error.status in setOf(401, 403, 404, 405)
/** How the marker of a Beacon chat create still waiting for its answer names that create's request id (Hermes' retry identity). */
internal const val BEACON_OPENING_REQUEST = "request:"
private const val BEACON_DRAFT_TRANSFER = "beacon-draft-transfer"
/**
 * The request id of an earlier Beacon chat create whose answer never came, kept in [marker]: sent again, Hermes returns the chat that create made, or
 * makes it now. Null when there is none, or only a marker that names none (1.2.0's "unknown", which once blocked Beacon for good): a new create starts.
 */
internal fun beaconOpeningKept(marker: String?): String? =
    marker?.takeIf { it.startsWith(BEACON_OPENING_REQUEST) }?.removePrefix(BEACON_OPENING_REQUEST)?.takeIf { it.matches(Regex("[A-Za-z0-9-]{8,64}")) }
/**
 * Hermes itself refused a kept create's id (its 4xxx refusal, not a lost answer), so that id can never open Beacon: the chat it made was deleted
 * (`not_found`: Hermes never makes a deleted id again, hermes_state_local.py), the request changed (`invalid_params`: the assistant's model was changed
 * meanwhile), or another refusal of it. One new create starts. Hermes restarting (`runtime_draining`) keeps the id: the same create works once it is back.
 * A refusal of the connection itself (`permission_denied`, `profile_mismatch`: Hermes refuses before it reads the id, session_local.py) keeps it too:
 * a new id is refused the same way, so keeping it costs nothing, and the chat the kept id may have made is not left behind (REVIEW-F6 P3-2).
 */
internal fun beaconOpeningRetryRefused(error: Throwable): Boolean =
    error is ApiFailure && !error.plugin && error.status in 4000..4999 && error.detail !in BEACON_OPENING_KEEPS_ID
/** Hermes' refusals that keep a kept create's id ([beaconOpeningRetryRefused]): a restart, and refusals of the connection itself. */
private val BEACON_OPENING_KEEPS_ID = setOf("runtime_draining", "permission_denied", "profile_mismatch")
/**
 * Whether Hermes still has the Beacon chat a call is about to use (backlog 57; device round 3, F1). True when the plugin registers it as the
 * account's assistant chat ([register], which needs Hermes' saved chat), or, when the plugin has no saved chat by that id (its 404, not a
 * missing route), when [resume] still finds the live draft this phone made: Hermes saves a chat at its first message, and this phone keeps
 * marking the chats it made, so a saved copy here does not tell the two apart. False when Hermes has neither (a chat deleted elsewhere, a
 * draft Hermes no longer keeps): the call goes to the account's current Beacon chat instead. Each answer is waited for, whenever it comes,
 * up to [reachMs]; null when Hermes did not answer in that time or said nothing about the chat (an older plugin, another failure): the call
 * goes on and meets that failure as before.
 */
internal suspend fun beaconChatCallable(reachMs: Long, register: suspend () -> Unit, resume: suspend () -> Unit): Boolean? {
    try {
        return if (withTimeoutOrNull(reachMs) { register() } != null) true else null
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        if (!(e is ApiFailure && e.plugin && e.status == 404 && !e.message.contains("Not Found", ignoreCase = true))) return null
    }
    return try { if (withTimeoutOrNull(reachMs) { resume() } != null) true else null }
    catch (e: CancellationException) { throw e } catch (e: Exception) { if (nativeSessionNotFound(e)) false else null }
}
/** A failure that came at once (refused, reset) is asked once more this much later, so a blink in the network is not reported ([hermesReachFailure]). */
internal const val CALL_REACH_RETRY_MS = 1_000L

/**
 * Whether Hermes answers this phone at all, by the call's own reach rule ([dev.hermes.android.voice.reachHermes]): [read] is a status read that
 * changes nothing. Null when Hermes answered (an error status is an answer too); otherwise the failure: no answer within [reachMs] (a black
 * hole), or a connection that failed (refused, reset, no route, a proxy that could not reach it). A failure that came at once is asked once
 * more [retryMs] later.
 */
internal suspend fun hermesReachFailure(reachMs: Long = dev.hermes.android.voice.GRANT_REACH_MS, retryMs: Long = CALL_REACH_RETRY_MS, read: suspend () -> Unit): Throwable? {
    suspend fun once(): Throwable? = try { dev.hermes.android.voice.reachHermes(reachMs) { read(); JSONObject() }; null }
        catch (e: CancellationException) { throw e }
        catch (e: ApiFailure) { null }
        catch (e: dev.hermes.android.voice.HermesUnreachable) { e }
        catch (e: java.io.IOException) { e }
        catch (e: Exception) { null }
    val first = once() ?: return null
    if (first is dev.hermes.android.voice.HermesUnreachable) return first
    delay(retryMs)
    return once()
}

/**
 * Whether Hermes answers this phone: its status, read with no sign-in on a client of its own (as setup's probe reads it). Hermes' TLS
 * handshake is already its answer, so a slow link (bytes late, not lost) is not taken for one that loses them: with every byte 1 s late
 * the whole read took 4.4 s, past the reach budget, and the app still connected (device, fix 5).
 * The read runs in [reads], where nothing waits for it to end, and only its answer is awaited: OkHttp looks Hermes' host up on the reading
 * thread, and cancelling cannot stop a lookup that hangs (REVIEW-121B P3-1: a 6 s lookup held the 3 s reach budget for 6 s, and one past about
 * 23 s let the overlay's give-up show "Couldn't start the call"). So the reach budget ends the wait on time; a stuck read ends with its lookup,
 * its answer dropped. [gotSomewhere]: the read reached Hermes' host (its socket, or the proxy's tunnel to it, is up), so a slow link
 * rather than a black hole. [dns] is for tests.
 */
internal suspend fun readHermesStatus(origin: String, reads: CoroutineScope, dns: okhttp3.Dns? = null, gotSomewhere: () -> Unit = {}) {
    val answered = CompletableDeferred<Unit>()
    val read = reads.launch(Dispatchers.IO) {
        // Nothing here may throw past this job (no one awaits it): every failure, the client's own included, is the read's answer.
        var status: BeaconClient? = null
        try {
            status = BeaconClient(AuthContext(origin, emptyList()), null, configureHttp = { http ->
                dns?.let(http::dns)
                http.eventListener(object : okhttp3.EventListener() {
                    override fun secureConnectStart(call: okhttp3.Call) = gotSomewhere()
                    override fun connectEnd(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) = gotSomewhere()
                    override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) { gotSomewhere(); answered.complete(Unit) }
                })
            })
            status.get("/api/status"); answered.complete(Unit)
        } catch (e: Throwable) { answered.completeExceptionally(e) } finally { runCatching { status?.closeNative() } }
    }
    try { answered.await() } finally { read.cancel() }
}

/** How a Beacon call's wait for the app's connection to Hermes ended ([awaitCallConnection]). */
internal sealed interface CallConnection {
    data object Up : CallConnection
    /** The call does not start; [line] is the call's own line for why (the overlay shows its short form). */
    data class Failed(val line: String) : CallConnection
}

/** What [hermesReachFailure] said so far, for [callConnectionVerdict]. */
internal enum class HermesReached { Asking, Yes, No }

/**
 * A model that can start a call: connected with a client, or with the client its last connection made while no new attempt runs (a later
 * request failed and marked the app not connected; the call then meets Hermes itself, by its own reach rule and line).
 */
internal fun callCanStart(state: AppState, client: Boolean): Boolean = client && (state.connected || !state.loading)

/**
 * Where the wait stands for [state] (null: still waiting). A model that can start a call goes on ([callCanStart]); a sign-in that is gone ends
 * it with the call's sign-in line; a Hermes that cannot be reached ends it with the call's line for that, at once; and a connection attempt
 * that ended without connecting ([attemptSeen]: this wait saw it run) while Hermes answers ends it with the call's line for a Hermes that did
 * not answer.
 */
internal fun callConnectionVerdict(state: AppState, client: Boolean, reached: HermesReached, attemptSeen: Boolean): CallConnection? = when {
    callCanStart(state, client) -> CallConnection.Up
    !state.authenticated && !state.restoringSession -> CallConnection.Failed(dev.hermes.android.voice.CALL_SIGN_IN_EXPIRED)
    reached == HermesReached.No -> CallConnection.Failed(APP_CONNECTION_ERROR)
    reached == HermesReached.Yes && attemptSeen && !state.loading && !state.restoringSession && !state.connected -> CallConnection.Failed(dev.hermes.android.voice.CALL_HERMES_NO_ANSWER)
    else -> null
}

/**
 * An attempt that showed Hermes no progress during the press ([awaitCallConnection]'s `progressed`: no connection reached Hermes, no answer came
 * back) and is still loading this long after Hermes answered the call's reach read is started over, once. One that showed progress is never
 * started over, however slow Hermes is (REVIEW-F7 P3-3: a connect Hermes served 10-15 s after its handshake was cut at 10 s and failed). So
 * the bound can be short (REVIEW-F7 P3-1, 10 s before): the reach read has just made a whole TLS handshake on the same path within its 3 s
 * budget, so a live attempt shows progress within this; the attempts it catches are stuck (a black hole that ended while the connect hung, a
 * drop that lost the answer), or older than the press and waiting on a request Hermes may never answer, where a fresh connect does no worse.
 * An attempt already known to be stuck (in flight while Hermes could not be reached, [callBeacon]) is started over as soon as Hermes answers.
 */
internal const val BEACON_CALL_STALL_MS = 3_000L
/**
 * Progress this long before a press counts as during it ([callBeacon]): a model whose request went just before the press (a warm or quick cold
 * connect) and waits on a slow Hermes is live. Not for an attempt known to be stuck: its request went before the outage, and Call again can
 * come within 2 s of that.
 */
internal const val BEACON_CALL_PROGRESS_LOOKBACK_MS = 2_000L
/**
 * After a press: the connection attempt ([attempt], connect()'s generation) it leaves marked as stuck in an outage, else [marked]. Marked:
 * still in flight with no client when the press found Hermes unreachable and its reach read got nowhere (no socket or tunnel to Hermes'
 * host), a black hole. A slow link whose read missed the reach budget mid-handshake (REVIEW-121B P3-2) is not marked: its connect may still
 * end, and the 10 s bound covers it.
 */
internal fun stuckAttemptMark(connection: CallConnection, readGotSomewhere: Boolean, hasClient: Boolean, loading: Boolean, attempt: Int, marked: Int): Int =
    if (connection == CallConnection.Failed(APP_CONNECTION_ERROR) && !readGotSomewhere && !hasClient && loading) attempt else marked
/** Whether the attempt in flight is one an earlier press marked stuck ([stuckAttemptMark]): it is started over as soon as Hermes answers. */
internal fun stuckAttemptMarked(marked: Int, attempt: Int, hasClient: Boolean, loading: Boolean): Boolean = !hasClient && loading && marked == attempt
/**
 * Before a start-over supersedes the attempt [client] (connect()'s, for the account [owner]), that attempt's sign-in rotation is kept, checked
 * against the account as its own failure path checks it, then [persist]ed: its `/api/auth/me` may have rotated the refresh token (the
 * answer's headers came, its body stalled), and the start-over reads the stored sign-in. Without this the replacement replays the spent
 * token, and a provider with reuse detection signs the owner out (REVIEW-F7 P3-2; the attempt's own catch keeps it only at its read timeout,
 * after the replacement). False when nothing was kept: no account to check against, another account's rotation, or a failure.
 */
internal suspend fun keepSupersededRotation(client: BeaconClient, owner: Scope?, persist: suspend (AuthContext) -> Unit): Boolean {
    if (owner == null) return false
    return try {
        client.auth.requireCachedIdentity(owner)
        client.retainCookieRotation()
        persist(client.auth)
        true
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { false }
}
/** A started-over attempt gets at least this long, past the wait's own limit if need be ([awaitCallConnection]). */
internal const val BEACON_CALL_RESTARTED_MS = 6_000L
/** ...but never this much past it: the overlay gives up 25 s after the ask, and the chat and the call's start still follow the wait. */
internal const val BEACON_CALL_RESTART_EXTRA_MS = 3_000L

/**
 * A call asked for right after the app's process starts comes once the saved sign-in is restored from this phone (restoring is over, the account
 * reads signed in) but before Hermes answered and the client exists. It waits for that connection: [CallConnection.Up] once [ui] reads
 * connected with a client ([hasClient]), or at once for a model that keeps the client of its last connection ([callCanStart]).
 * Device round 4 (F3, 16 Nov 2026): with Hermes unreachable the wait ran its whole 15 s and the overlay then gave up with only "Couldn't start
 * the call". So Hermes is asked directly as well ([reach], [hermesReachFailure]): one that cannot be reached ends the wait at once with the
 * call's line for that, after the call's reach budget at most. A Hermes that answers but is slow to connect is waited for within [limitMs]
 * (then the call's line for no answer). [freshAttempt]: the caller has just asked for a new connection, so the state of the attempt before it
 * (ended, not connected) is not this attempt's.
 * With [restart] (a new connection attempt; false when it started none), an attempt Hermes' answer shows to be stuck is started over, once per
 * wait (REVIEW-121B P2-1: after a black hole, "Call again" followed the press's own connect, still hung, and said "Hermes didn't answer" although
 * Hermes was back): one this wait did not start that ends without connecting while Hermes answers, or any still loading [stallMs] after Hermes
 * answered. The started-over attempt gets [restartedMs] at least, within [limitMs] + [BEACON_CALL_RESTART_EXTRA_MS].
 * [progressed]: the attempt in flight has shown Hermes progress during this wait (a connection reached it, an answer came back); such an
 * attempt is never started over by the time bound, however slow (REVIEW-F7 P3-3).
 * When the wait gives up at its limit on an attempt still loading with no client, that attempt is started over through [restart] then, past
 * the press, whether or not one was started over already (REVIEW-F8 P3-1). It may have reached Hermes and lost its answer: with a lapsed
 * access token Hermes refreshed it, rotating the refresh token, and the rotated session is handed out again only within Hermes' 30 s
 * refresh cache. The attempt itself replays the spent token only after OkHttp's 60 s read timeout, where a provider with reuse detection
 * signs the owner out; the start-over replays it about 20 s after the refresh at most, inside the cache, and keeps any rotation that did
 * arrive. Nothing inside the press is cut.
 */
internal suspend fun awaitCallConnection(ui: kotlinx.coroutines.flow.StateFlow<AppState>, hasClient: () -> Boolean, limitMs: Long,
    freshAttempt: Boolean = false, reach: suspend () -> Throwable? = { null }, restart: (suspend () -> Boolean)? = null,
    stallMs: Long = BEACON_CALL_STALL_MS, restartedMs: Long = BEACON_CALL_RESTARTED_MS, progressed: () -> Boolean = { false }): CallConnection = coroutineScope {
    if (callCanStart(ui.value, hasClient())) return@coroutineScope CallConnection.Up
    val reached = MutableStateFlow(HermesReached.Asking)
    val asking = launch { reached.value = if (reach() == null) HermesReached.Yes else HermesReached.No }
    // The wait's clock: its limit, and once an attempt is started over that attempt's own time, never past the extra margin.
    val limitUp = MutableStateFlow(false); val restartedUp = MutableStateFlow(true); val capUp = MutableStateFlow(false); val stalled = MutableStateFlow(false)
    val clocks = mutableListOf(launch { delay(limitMs); limitUp.value = true }, launch { delay(limitMs + BEACON_CALL_RESTART_EXTRA_MS); capUp.value = true },
        launch { reached.first { it == HermesReached.Yes }; delay(stallMs); stalled.value = true })
    val timeUp = combine(limitUp, restartedUp, capUp) { limit, restarted, cap -> limit && restarted || cap }
    var attemptSeen = !freshAttempt
    var restartable = restart != null
    // The one start-over: when the caller starts none (its own rule declined), the wait goes on as it would have.
    suspend fun startOver(otherwise: CallConnection?): CallConnection? {
        restartable = false
        if (restart?.invoke() != true) return otherwise
        attemptSeen = false; restartedUp.value = false
        clocks += launch { delay(restartedMs); restartedUp.value = true }
        return null
    }
    try {
        combine(ui, reached, stalled, timeUp) { state, r, stall, up ->
            if (state.loading) attemptSeen = true
            val verdict = callConnectionVerdict(state, hasClient(), r, attemptSeen)
            when {
                restartable && !freshAttempt && r == HermesReached.Yes && verdict == CallConnection.Failed(dev.hermes.android.voice.CALL_HERMES_NO_ANSWER) -> startOver(verdict)
                restartable && verdict == null && stall && state.loading && !progressed() -> startOver(null)
                verdict != null -> verdict
                up -> {
                    // The press gives up on an attempt still loading with no client (REVIEW-F8 P3-1): start it over now, past the press.
                    if (restart != null && state.loading && !hasClient()) restart.invoke()
                    CallConnection.Failed(dev.hermes.android.voice.CALL_HERMES_NO_ANSWER)
                }
                else -> null
            }
        }.filterNotNull().first()
    } finally { asking.cancel(); clocks.forEach(Job::cancel) }
}
/**
 * How the start a Beacon call asked for on [target] at [askedAt] (wall clock, before the request) went: its state when it ended before
 * connecting with a failure (a refused grant or context, device round 3 F1), or null once it connected, or when nothing came within
 * [limitMs]. A state from an earlier start on the same chat is not this one ([CallState.startedAt]).
 */
internal suspend fun beaconCallStartFailure(calls: kotlinx.coroutines.flow.StateFlow<CallState>, target: Scope, askedAt: Long, limitMs: Long): CallState? =
    withTimeoutOrNull(limitMs) {
        calls.first { it.conversationScope == target && it.startedAt >= askedAt && (it.active && it.state in setOf("listening", "thinking", "speaking") || !it.active && it.failureReason.isNotBlank()) }
    }?.takeIf { !it.active }
/** Where a Beacon call goes once its chat opened: [opened], or null (the account's current Beacon chat) when the plugin refused [kept]. */
internal fun beaconCallChat(kept: String?, opened: String?, held: Boolean?): String? =
    if (held == false && (opened == null || opened == kept)) null else opened
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

data class Message(val id: String, val role: String, val text: String, val reasoning: String = "", val tools: List<JSONObject> = emptyList(), val attachments: List<JSONObject> = emptyList(), val mediaSnapshots: Map<String, String> = emptyMap(), val sessionId: String = "", val interrupted: Boolean = false, val voice: Boolean = false, val recordedAt: Long = 0L, val displayKind: String = "",
    /** The photos and files the stored user row names (Hermes' note and the path are never part of [text]). */
    val media: List<AttachedMedia> = emptyList(),
    /** Hermes' `error_surface` of its failed_turn row (display_metadata, agent/conversation_loop.py), as JSON text: the reason the chat shows under the row. */
    val failure: String = "",
    /** The phone's submission id Hermes stamped on this user row ([rowSubmissionId]): the row IS that send, whatever its words. */
    val submission: String = "") {
    companion object {
        fun from(o: JSONObject, index: Int, sessionId: String = ""): Message {
            val completionText = if (o.string("role") == "user" && o.string("display_kind") in setOf("async_delegation_complete", "process_complete"))
                (o.optJSONObject("display_metadata")?.opt("display_text") as? String)?.takeIf { it.isNotBlank() } else null
            val role = if (completionText != null) "assistant" else o.string("role", "assistant")
            val content = completionText ?: historyRowContent(o)
            val parts = (content as? JSONArray)?.objects().orEmpty()
            val shown = messageContentText(role, content)
            // Hermes rewrites a user row that carries a file: its own note and the file's path stand in front of what the owner typed. The owner sees the words
            // they typed; the stored content still names the file when a display text hides the note.
            val typed = if (role == "user" && mentionsHermesNote(shown)) splitAttachedMedia(shown) else null
            val stored = if (role == "user" && typed == null) messageContentText("user", o.opt("content")).takeIf { it != shown && mentionsHermesNote(it) }?.let { splitAttachedMedia(it).media }.orEmpty() else emptyList()
            val text = typed?.text ?: shown
            val media = (typed?.media.orEmpty() + stored).distinctBy { it.path }
            val snapshots = o.optJSONObject("_media_snapshots")?.let { values -> values.keys().asSequence().take(256).mapNotNull { path -> values.optString(path).takeIf(::validMediaSnapshot)?.takeIf { path.length in 1..8192 && path.none { it.code < 32 || it.code == 127 } }?.let { path to it.lowercase() } }.toMap() }.orEmpty()
            return Message(o.string("id", o.string("message_id", o.string("row_id", o.string("_row_id", o.string("tool_call_id", "${o.string("role")}:$index"))))), role, text, o.string("reasoning", o.string("reasoning_content")), o.rows("tool_calls"), o.rows("attachments") + parts.filter { it.string("type").contains("image") }, snapshots, sessionId, o.optBoolean("interrupted") || o.optJSONObject("display_metadata")?.optBoolean("interrupted") == true || o.string("finish_reason") == "cancelled", o.optJSONObject("display_metadata")?.optString("channel") == "voice", recordedAt = recordedMessageTime(o.opt("timestamp")), displayKind = o.string("display_kind"), media = media,
                failure = o.optJSONObject("display_metadata")?.optJSONObject("error_surface")?.takeIf { o.string("display_kind") == "failed_turn" }?.toString().orEmpty(),
                submission = if (role == "user") rowSubmissionId(o) else "")
        }
    }
}

/** An email alert Beacon posted into the chat. [verdict] stays blank until the owner marks it "important" or "not_important". */
data class ChatAlert(val noticeId: String, val text: String, val source: String, val createdAt: Long, val expiresAt: Long, val verdict: String = "") {
    fun toJson() = json("notice_id" to noticeId, "text" to text, "source" to source, "created_at" to createdAt / 1000.0, "expires_at" to expiresAt, "verdict" to verdict)
    companion object {
        /** A plugin or saved row; its times are in seconds. */
        fun from(row: JSONObject): ChatAlert? {
            val id = row.string("notice_id").takeIf { dev.hermes.android.push.validOpaque(it) } ?: return null
            val text = row.string("text").takeIf { it.isNotBlank() } ?: return null
            return ChatAlert(id, text, row.string("source"), Math.round(row.optDouble("created_at", 0.0) * 1000), row.optLong("expires_at"), row.string("verdict").takeIf { it in setOf("important", "not_important") }.orEmpty())
        }
    }
}
private const val CHAT_ALERT_LIMIT = 100
/** The plugin keeps an alert only while its notice lives; the saved copy keeps the rest, so alerts the plugin no longer lists stay in the chat. */
internal fun mergeChatAlerts(saved: List<ChatAlert>, fresh: List<ChatAlert>): List<ChatAlert> =
    (saved.associateBy { it.noticeId } + fresh.associateBy { it.noticeId }).values.sortedBy { it.createdAt }.takeLast(CHAT_ALERT_LIMIT)
internal fun decodeChatAlerts(raw: String): List<ChatAlert> = runCatching { JSONArray(raw).objects().mapNotNull(ChatAlert::from) }.getOrDefault(emptyList())

internal fun stoppingNoticeAfterEvent(notice: String, event: String): String =
    if (notice == "Stopping…" && event in setOf("done", "state_saved", "cancel", "stream_end", "error", "apperror")) "" else notice

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
/** The plain line under a reply Hermes' restart cut, when nothing waits behind it (no question; with messages waiting the chat asks instead, [lostChoiceShown]). */
internal const val LOST_REPLY_STATUS = "Hermes restarted during this reply."
/** Hermes holds this chat behind a reply its last run lost (a pending admission `unknown`): nothing runs and nothing queued starts until it is closed. */
internal fun lostTurnHeld(session: JSONObject?): Boolean =
    session != null && session.opt("running") != true && session.rows("pending").any { it.string("status") == "unknown" }
/** The restart line's choice ("Send your next message?") shows while the owner's messages wait behind a lost reply and nothing runs. */
/** A chat's box (open or saved) holds a message of the owner's still waiting to be answered: a look at its lost reply must not close it then. */
/**
 * The phone's box holds a message of the owner's that waits: one Hermes has not taken yet. An `accepted` one is Hermes' (its own snapshot
 * counts what waits there), and is often the cut turn's own message until its saved row pairs: counting it kept every look from closing a
 * lone lost reply (live, 17 Nov: the choice instead of the plain line).
 */
internal fun boxHoldsWaiting(queued: List<QueuedMessage>): Boolean = queued.any { it.kind == "queue" && it.state !in setOf("sent", "settled", "accepted") }
/**
 * "Not sent yet" ends when the open chat [open] holds no waiting message and no other chat in the [outbox] still waits (CHATFIX-121 follow-up 1), and
 * only from the account and profile it was raised for, [raisedFor] (follow-up 3, Root: the notice belongs to its profile).
 */
internal fun offlineNoticeEnds(raisedFor: Scope?, open: Scope, openWaits: Boolean, outbox: List<String>): Boolean =
    raisedFor == open.copy(session = "") && !openWaits && outbox.all { it == open.session }
internal fun lostChoiceShown(chat: ChatState): Boolean = chat.lostWaiting && chat.streamId.isBlank()
/**
 * A Bot Mode row shows "Reply cut off" when the bot's own Bot Chat holds a lost reply: [AppState.lostBotChats] names that chat and
 * [AppState.lostChats] keeps it as "bot:chat" (so opening it, which lets the reply go, clears the row too). A bot whose profile has other
 * lost chats is not marked for them.
 */
internal fun botCutOff(ui: AppState, bot: String): Boolean = ui.lostBotChats[bot]?.let { "$bot:$it" in ui.lostChats } == true
internal fun renderedChatStatus(old: ChatState, active: String?, streamEnded: Boolean): String = when {
    old.uncertain.isNotBlank() && old.status == "Sending" -> old.status
    active.isNullOrBlank() && old.streamId.isBlank() && old.status == LOST_REPLY_STATUS -> old.status
    !active.isNullOrBlank() -> if (active == old.streamId) old.status.ifBlank { "Working" } else "Working"
    streamEnded -> ""
    active.isNullOrBlank() && old.streamId.isNotBlank() -> old.status.ifBlank { "Reply status unknown" }
    else -> ""
}
/** Saved history shown offline for a signed-in account that is not already reconnecting. */
internal fun reconnectOnNetworkReturn(ui: AppState): Boolean = ui.authenticated && !ui.connected && !ui.loading && !ui.setupWizard && ui.origin.isNotBlank()
/** Only an actual native row identity can link a saved receipt to history. */
internal fun sendArrived(rowId: String, messages: List<Message>): Boolean =
    rowId.isNotBlank() && messages.any { it.role == "user" && it.id == rowId }
/**
 * CHATQA-121 follow-up 5: Hermes' own user row for [submissionId] among history [rows]: the turn saves the client's submission id in the row's display
 * metadata (gateway/session_display.py:65-82 submission_turn_scope). Null when no row carries it.
 */
internal fun submissionRowId(rows: List<JSONObject>, submissionId: String): String? = submissionId.takeIf(String::isNotBlank)?.let { id ->
    rows.lastOrNull { row -> row.string("role") == "user" && (row.optJSONObject("display_metadata")
        ?: (row.opt("display_metadata") as? String)?.let { runCatching { JSONObject(it) }.getOrNull() })?.opt("submission_id") == id }
        ?.let { row -> row.string("row_id", row.string("_row_id", row.string("id"))).takeIf(String::isNotBlank) }
}
/** Follow-up 5: the request may have reached Hermes and only its answer was lost (the gateway cannot tell whether the bytes left). */
internal fun answerLost(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8).any { it.message == NATIVE_OUTCOME_UNKNOWN }
/**
 * Follow-up 5: Hermes already holds a send under this submission id, with another payload. Only the refusal naming `request_id` proves that
 * (hermes_state_runtime.py:35-38 ADMITTED_REQUEST_CONFLICT); any other admission_conflict (route, binding, lineage) proves nothing about the send.
 */
internal fun admissionConflict(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8)
    .any { it is NativeRefusal && it.message == "admission_conflict" && it.field == "request_id" }
/** Follow-up 5: a send whose answer was lost can be asked again only with exactly its first payload: text alone, or with the staged attachments that payload carried. */
internal fun askableAgain(send: PendingSend): Boolean = send.submissionSaved && send.submissionId.isNotBlank() && (send.draft.attachments.isEmpty() || send.staged.isNotBlank())
/**
 * Follow-up 5: the request the first attempt made (send() and the outbox build it the same way), asked again under its submission id. Hermes
 * answers with the admission it holds under that id, or admits it once if it never arrived (hermes_state_runtime.py admit_session_input).
 */
internal fun askAgainBody(scope: Scope, send: PendingSend): JSONObject = json("session_id" to scope.session, "message" to send.draft.text,
    "attachments" to JSONArray(send.draft.attachments), "profile" to scope.profile, "client_request_id" to send.submissionId, "model" to send.model.ifBlank { null },
    "model_provider" to send.provider.ifBlank { null }, "queued_model_snapshot" to send.queued, "resubmit" to true)
    .apply { if (send.staged.isNotBlank()) put("staged_attachments", JSONArray(send.staged)) }
/** The receipt lookup validates native scope/epoch/generation before publishing this proof. Linking never retires the draft. */
internal fun linkedQueuedInputRow(item: QueuedMessage, receipt: JSONObject, rows: List<JSONObject>, queued: List<QueuedMessage>): String? {
    if (item.kind != "queue" || item.admissionId.isBlank() || item.submissionId.isBlank() ||
        receipt.string("status") != "terminal" || receipt.string("outcome") != "completed" ||
        receipt.string("admission_id") != item.admissionId || queued.count { it.submissionId == item.submissionId || it.admissionId == item.admissionId } != 1) return null
    val proof = receipt.optJSONObject("_confirmed_persisted_input") ?: receipt.optJSONObject("_confirmed_persisted_turn") ?: return null
    if (proof.string("submission_id") != item.submissionId) return null
    fun positive(value: Any?): Long? = (when (value) { is Int -> value.toLong(); is Long -> value; else -> null })?.takeIf { it > 0 }
    val user = positive(proof.opt("user_row_id")) ?: return null
    val users = proof.optJSONArray("user_row_ids") ?: return null
    val rowIds = proof.optJSONArray("row_ids") ?: return null
    if (users.length() < 1 || positive(users.opt(0)) != user || (0 until users.length()).map { positive(users.opt(it)) ?: return null }.distinct().size != users.length() ||
        (0 until rowIds.length()).none { positive(rowIds.opt(it)) == user } ||
        item.rowId.isNotBlank() && item.rowId != user.toString() && item.outcome != PAIRED_BY_WORDS) return null
    return user.toString().takeIf { id -> rows.filter { it.string("row_id", it.string("_row_id")) == id }.singleOrNull()?.string("role") == "user" }
}
/** A finished local input can leave only when both ends of its native turn are in displayed history. */
internal fun completedQueuedInputRow(item: QueuedMessage, receipt: JSONObject, history: List<JSONObject>, refreshed: List<JSONObject>, queued: List<QueuedMessage>): String? {
    if (item.kind != "queue" || item.state != "settled" || item.outcome != "completed" ||
        item.admissionId.isBlank() || item.submissionId.isBlank() || receipt.string("admission_id") != item.admissionId ||
        receipt.string("status") != "terminal" || receipt.string("outcome") != "completed" ||
        queued.count { it.submissionId == item.submissionId || it.admissionId == item.admissionId } != 1) return null
    val proof = receipt.optJSONObject("_confirmed_persisted_turn") ?: return null
    if (proof.opt("complete") != true || proof.string("submission_id") != item.submissionId) return null
    fun positive(value: Any?): Long? = (when (value) { is Int -> value.toLong(); is Long -> value; else -> null })?.takeIf { it > 0 }
    val user = positive(proof.opt("user_row_id")) ?: return null
    val final = positive(proof.opt("final_assistant_row_id")) ?: return null
    val users = proof.optJSONArray("user_row_ids") ?: return null
    val rows = proof.optJSONArray("row_ids") ?: return null
    if (users.length() != 1 || positive(users.opt(0)) != user || user == final) return null
    val ids = (0 until rows.length()).map { positive(rows.opt(it)) ?: return null }
    if (ids.distinct().size != ids.size || user !in ids || final !in ids || item.rowId.isNotBlank() && item.rowId != user.toString()) return null
    fun confirmed(rows: List<JSONObject>, id: Long, role: String): Boolean = rows.filter { it.string("row_id", it.string("_row_id")) == id.toString() }
        .singleOrNull()?.string("role") == role
    return user.toString().takeIf { listOf(history, refreshed).all { confirmed(it, user, "user") && confirmed(it, final, "assistant") } }
}
/** The queued item after its settled turn names the real user row, or null when the receipt does not prove that row in [rows]. */
internal fun boundQueuedInput(item: QueuedMessage, receipt: JSONObject, rows: List<JSONObject>, queued: List<QueuedMessage>): QueuedMessage? =
    linkedQueuedInputRow(item, receipt, rows, queued)?.let { item.copy(rowId = it, state = "settled", outcome = receipt.string("outcome")) }
/** The saved final reply of a proven turn is in [rows]: the live copy of that reply is done with. */
internal fun replyShownInHistory(receipt: JSONObject?, rows: List<JSONObject>): Boolean =
    receipt?.optJSONObject("_confirmed_persisted_turn")?.string("final_assistant_row_id")?.takeIf(String::isNotBlank)?.let { final ->
        rows.filter { it.string("row_id", it.string("_row_id")) == final }.singleOrNull()?.string("role") == "assistant"
    } == true

/** The turn a finished stream belongs to: the submission Beacon made and the admission Hermes gave it. */
internal data class TurnIdentity(val submission: String, val admission: String)
/** How a finished turn was proven: [step] is receipt, done_event, retry, saved_rows, or none. */
internal class FinishedTurn(val receipt: JSONObject?, val step: String)
private val turnEndedBadly = setOf("error", "failed", "failure", "interrupted", "cancelled", "canceled", "rejected")
private fun settledReceipt(identity: TurnIdentity, stream: String, proof: JSONObject) = json("status" to "terminal", "outcome" to "completed",
    "admission_id" to identity.admission, "stream_id" to stream, "_confirmed_persisted_input" to proof, "_confirmed_persisted_turn" to proof)
/**
 * The done event's own `persisted_turn` when it is complete and names this exact submission and admission. Hermes builds it from the same settlement
 * as the receipt, so it proves the turn when the receipt lookup cannot be read.
 */
internal fun doneEventReceipt(completion: JSONObject?, identity: TurnIdentity, stream: String): JSONObject? {
    if (completion == null || identity.submission.isBlank() || identity.admission.isBlank() || completion.string("admission_id") != identity.admission) return null
    if (completion.string("status") in turnEndedBadly || completion.string("outcome").let { it.isNotBlank() && it != "completed" } ||
        completion.string("error").isNotBlank() || completion.string("failure_reason").isNotBlank()) return null
    val turn = completion.optJSONObject("persisted_turn")?.takeIf { it.string("submission_id") == identity.submission } ?: return null
    val settled = nativeSettledTurn(json("status" to "terminal", "outcome" to "completed", "result" to json("persisted_turn" to turn)), identity.submission)
        ?: nativeSteeredTurn(turn, identity.submission) ?: return null
    return settledReceipt(identity, stream, settled)
}
/**
 * A Hermes without session authority names no submission and no admission, but its done event still lists the rows it committed for the turn (the same
 * `persisted_turn` Hermes builds for every host). That list proves the turn when it is complete (one user row, a final reply), the turn ended cleanly, and both ends
 * are in the saved [rows]. A done event that names an admission or a submission belongs to a gateway this does not apply to, and proves nothing here.
 */
internal fun legacyDoneReceipt(completion: JSONObject?, identity: TurnIdentity, stream: String, clean: Boolean, rows: List<JSONObject>): JSONObject? {
    if (!clean || completion == null || identity.admission.isNotBlank() || completion.string("admission_id").isNotBlank()) return null
    if (completion.string("status") in turnEndedBadly || completion.string("outcome").let { it.isNotBlank() && it != "completed" } ||
        completion.string("error").isNotBlank() || completion.string("failure_reason").isNotBlank()) return null
    val turn = completion.optJSONObject("persisted_turn") ?: return null
    if (turn.opt("complete") != true || turn.string("submission_id").isNotBlank()) return null
    fun positive(value: Any?): Long? = (when (value) { is Int -> value.toLong(); is Long -> value; else -> null })?.takeIf { it > 0 }
    fun list(key: String): List<Long>? = turn.optJSONArray(key)?.let { array -> (0 until array.length()).map { positive(array.opt(it)) ?: return null } }
    val user = positive(turn.opt("user_row_id")) ?: return null
    val final = positive(turn.opt("final_assistant_row_id")) ?: return null
    val ids = list("row_ids") ?: return null
    if (final == user || ids.distinct().size != ids.size || user !in ids || final !in ids || list("user_row_ids") != listOf(user)) return null
    fun role(id: Long) = rows.filter { it.string("row_id", it.string("_row_id")) == id.toString() }.singleOrNull()?.string("role")
    if (role(user) != "user" || role(final) != "assistant") return null
    return settledReceipt(identity, stream, turn.copy())
}
/**
 * Both ends of the turn in the saved [rows]: the user row this send was linked to ([knownUserRow], never found by its words) and the last assistant row
 * before the next user row. Used only when nothing else could prove a turn that ended cleanly.
 */
internal fun savedRowsReceipt(identity: TurnIdentity, knownUserRow: String, rows: List<JSONObject>, stream: String): JSONObject? {
    if (identity.submission.isBlank() || identity.admission.isBlank()) return null
    fun id(row: JSONObject) = row.string("row_id", row.string("_row_id"))
    val user = knownUserRow.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val at = rows.indexOfFirst { id(it) == knownUserRow }
    if (at < 0 || rows.count { id(it) == knownUserRow } != 1 || rows[at].string("role") != "user") return null
    // A steer is a user row too, so the turn goes on past it; only another prompt ends it.
    val turn = rows.drop(at + 1).takeWhile { it.string("role") != "user" || it.string("display_kind") == "steer" }
    val finalAt = turn.indexOfLast { it.string("role") == "assistant" }
    if (finalAt < 0) return null
    val span = turn.take(finalAt + 1)
    fun rowNumber(row: JSONObject) = id(row).toLongOrNull()?.takeIf { it > 0 }
    val ids = listOf(user) + span.map { rowNumber(it) ?: return null }
    if (ids.distinct().size != ids.size) return null
    val steers = span.filter { it.string("role") == "user" }.map { rowNumber(it) ?: return null }
    // Like Hermes' own receipt, a turn with a steer lists every user row and is never called complete.
    return settledReceipt(identity, stream, json("complete" to steers.isEmpty(), "submission_id" to identity.submission, "user_row_id" to user,
        "user_row_ids" to JSONArray(listOf(user) + steers), "final_assistant_row_id" to ids.last(), "row_ids" to JSONArray(ids)))
}
/** When the saved chat is read after the first frame of a turn whose copy has no saved row yet: at once, then again while the row is not there (12.4 s in all). */
internal val ADMITTED_ROW_READS_MS = listOf(0L, 400L, 800L, 1_600L, 3_200L, 6_400L)
/** While the copy still has no row after those reads (a cold or heavy profile builds its agent before Hermes saves the row), a later frame of the turn reads once more, at most this often. */
internal const val ADMITTED_ROW_AGAIN_MS = 15_000L
/** How long Hermes is asked for a receipt: each ask, and both together. The chat stays "Working" until the answer or the cap, so neither may be long. */
internal const val RECEIPT_ASK_MS = 5_000L
internal const val RECEIPT_TOTAL_MS = 8_000L
/**
 * Proves a turn that ended. The done event's own proof comes first and is used at once (Hermes builds it from the same settlement as the receipt);
 * only without it is Hermes asked for the receipt, then once more on a fresh lease, each ask and both together capped; last come the saved rows
 * ([clean] only, a failed or stopped turn is not settled from rows). A null [lookup] or [retry] skips that step. Every receipt step that did not prove
 * the turn goes to [failed] with its step name (and the error, when it failed) so it can be logged without any message content.
 */
internal suspend fun proveFinishedTurn(identity: TurnIdentity, stream: String, completion: JSONObject?, clean: Boolean, knownUserRow: String, rows: List<JSONObject>,
    lookup: (suspend () -> JSONObject?)?, retry: (suspend () -> JSONObject?)?, failed: (String, Throwable?) -> Unit = { _, _ -> },
    askMs: Long = RECEIPT_ASK_MS, totalMs: Long = RECEIPT_TOTAL_MS): FinishedTurn {
    doneEventReceipt(completion, identity, stream)?.let { return FinishedTurn(it, "done_event") }
    legacyDoneReceipt(completion, identity, stream, clean, rows)?.let { return FinishedTurn(it, "done_event") }
    suspend fun ask(step: String, read: (suspend () -> JSONObject?)?): JSONObject? {
        if (read == null) return null
        var answered = false
        val receipt = try { withTimeoutOrNull(askMs) { read().also { answered = true } } }
            catch (e: Exception) { if (e is CancellationException) throw e; failed(step, e); return null }
        if (!answered) { failed(step, java.net.SocketTimeoutException("The receipt did not answer in time")); return null }
        return receipt?.takeIf { it.optJSONObject("_confirmed_persisted_turn") != null } ?: run { failed(step, null); null }
    }
    withTimeoutOrNull(totalMs) {
        ask("receipt", lookup)?.let { return@withTimeoutOrNull FinishedTurn(it, "receipt") }
        ask("retry", retry)?.let { return@withTimeoutOrNull FinishedTurn(it, "retry") }
        null
    }?.let { return it }
    if (clean) savedRowsReceipt(identity, knownUserRow, rows, stream)?.let { return FinishedTurn(it, "saved_rows") }
    return FinishedTurn(null, "none")
}
/** Words compared as the reader sees them: runs of white space are one space. Only ever used to hide a second copy of what is already shown, never to link a send. */
internal fun sameWords(text: String) = text.trim().replace(Regex("[ \t\r\n]+"), " ")
private val outOfBandWrapper = Regex("^\\s*\\[OUT-OF-BAND USER MESSAGE[^\\]]*\\]\\s*(.*?)\\s*\\[/OUT-OF-BAND USER MESSAGE\\]\\s*$", RegexOption.DOT_MATCHES_ALL)
/** What the owner typed for a message Hermes saved as a steer row: the row's text without the note Hermes wraps around it. */
internal fun steerRowText(row: JSONObject): String =
    messageContentText("user", historyRowContent(row)).let { text -> (outOfBandWrapper.matchEntire(text)?.groupValues?.get(1) ?: text).let { if (mentionsHermesNote(it)) splitAttachedMedia(it).text else it } }
/**
 * The saved user row of each steer bubble still waiting for one (local copy id to row id). Hermes saves a steer as its own user row with no submission identity,
 * so a row is told by being a saved steer of THIS turn (after the turn's prompt row, and listed by the turn's receipt when there is one) with the same words,
 * not held by another bubble, and each row pairs once. A bubble that cannot be paired stays on screen: nothing is hidden on doubt.
 */
internal fun steerRowPairs(items: List<QueuedMessage>, proofUserRows: List<Long>, rows: List<JSONObject>): Map<String, String> {
    val open = items.filter { it.kind == "steer" && it.rowId.isBlank() && it.state in setOf("accepted", "unknown") }.sortedBy { it.createdAt }
    if (open.isEmpty()) return emptyMap()
    fun id(row: JSONObject) = row.string("row_id", row.string("_row_id"))
    // The prompt: the receipt's first user row, else the newest user row that is neither a steer nor a steering note (the turn is still running).
    fun isPrompt(row: JSONObject) = row.string("role") == "user" && row.string("display_kind") != "steer" && !isSteeringText(messageContentText("user", historyRowContent(row)))
    val prompt = proofUserRows.firstOrNull()?.toString() ?: rows.lastOrNull(::isPrompt)?.let(::id) ?: return emptyMap()
    val promptAt = rows.indexOfFirst { id(it) == prompt }
    if (promptAt < 0) return emptyMap()
    val listed = proofUserRows.drop(1).map(Long::toString).toSet()
    val claimed = items.map { it.rowId }.filter(String::isNotBlank).toMutableSet()
    val saved = rows.drop(promptAt + 1).filter { row ->
        row.string("role") == "user" && row.string("display_kind") == "steer" && id(row) !in claimed && (proofUserRows.isEmpty() || id(row) in listed)
    }
    val pairs = linkedMapOf<String, String>()
    for (item in open) {
        val row = saved.firstOrNull { id(it) !in claimed && sameWords(steerRowText(it)) == sameWords(item.text) } ?: continue
        claimed += id(row); pairs[item.id] = id(row)
    }
    return pairs
}
/**
 * H1b P0-1: what of a chat's draft is still the owner's to send once that chat is gone: the words, the files and the box messages (never what
 * Hermes had); null when nothing. The box messages come as Not sent because their chat was deleted (Root msg_f2774c2f5936): only Retry sends one.
 */
internal fun Draft.unsentPart(): Draft? =
    Draft(text, selectionStart, selectionEnd, attachments, queued.filter { it.inBox() }.map { it.copy(state = "settled", outcome = CHAT_GONE_OUTCOME, held = "", admissionId = "", admissionStatus = "") })
        .takeIf { it.text.isNotEmpty() || it.attachments.isNotEmpty() || it.queued.isNotEmpty() }
/** H1b P0-1 (related): the chat the Chat tab reopens, never Beacon's own ([beacon]): Beacon's chat opens on its own tab, under Beacon's name. */
internal fun chatTabSelection(selected: String?, beacon: String?): String? = selected?.takeIf { it.isNotBlank() && it != beacon }
/** P1-2: a chat's notice belongs to the chat: leaving Chat or Beacon for another page clears it, so it never reads as that page's failure. */
internal fun leavesChat(from: String, to: String): Boolean = from in setOf("Chat", "Beacon") && to != from
/** P1-2: notices said only for a moment (a short snackbar), not one to act on. */
internal val SHORT_NOTICES = setOf("Open a chat first.")
/** P1-3: the steer copies a history load pairs with their saved rows ([steerRowPairs]) in the same update that draws the rows, so a cold return never shows one twice. */
internal fun steersPairedOnRender(queued: List<QueuedMessage>, rows: List<JSONObject>): List<QueuedMessage> {
    val pairs = steerRowPairs(queued, emptyList(), rows)
    return if (pairs.isEmpty()) queued else queued.map { q -> pairs[q.id]?.let { q.copy(rowId = it, state = "sent") } ?: q }
}
/** A steer bubble still waiting for its saved row. */
internal fun QueuedMessage.openSteer(): Boolean = kind == "steer" && rowId.isBlank() && state in setOf("accepted", "unknown")
/** Hermes joins the steers it takes together with a newline (agent/interrupt_control.steer): [text] is one of them when it is a whole line of [joined]. */
internal fun steerLine(joined: String, text: String): Boolean = "\n$joined\n".contains("\n${text.trim()}\n")
/** The steer rows Hermes saved in the turn that just ended (after its prompt: the receipt's first user row, else the newest prompt), as row id to words. */
internal fun turnSteerRows(rows: List<JSONObject>, proofUserRows: List<Long>): List<Pair<String, String>> {
    fun id(row: JSONObject) = row.string("row_id", row.string("_row_id"))
    fun isPrompt(row: JSONObject) = row.string("role") == "user" && row.string("display_kind") != "steer" && !isSteeringText(messageContentText("user", historyRowContent(row)))
    val prompt = proofUserRows.firstOrNull()?.toString() ?: rows.lastOrNull(::isPrompt)?.let(::id) ?: return emptyList()
    val at = rows.indexOfFirst { id(it) == prompt }.takeIf { it >= 0 } ?: return emptyList()
    return rows.drop(at + 1).takeWhile { !isPrompt(it) }.filter { it.string("role") == "user" && it.string("display_kind") == "steer" }.map { id(it) to steerRowText(it) }
}
/**
 * R3-F5: a steer still open when its turn ended. Hermes folds a steer in only after a tool step; one that came later is handed back
 * (`pending_steer`) and goes as the next turn by itself, as Hermes' own CLI sends it. Several folded during one step are saved as ONE steer row
 * of the turn ([turnSteers]: row id to its words): a copy that is a line of one is that row. Where Hermes runs a leftover itself
 * (`steer_followup`) the copy stays. Anything else Hermes did not take: it waits in the box as Not sent.
 */
internal fun steerAfterTurn(copy: QueuedMessage, receipt: JSONObject?, turnSteers: List<Pair<String, String>> = emptyList()): QueuedMessage {
    if ((receipt?.opt("pending_steer") as? String)?.let { steerLine(it, copy.text) } == true) return copy.heldAgain()
    turnSteers.firstOrNull { steerLine(it.second, copy.text) }?.let { return copy.copy(rowId = it.first, state = "sent") }
    return if (receipt?.optBoolean("steer_followup") == true) copy else copy.copy(kind = "queue", state = "settled", outcome = NOT_SENT_OUTCOMES.first())
}
/**
 * The phone's submission id Hermes stamped on a saved user row, or "". A session authority writes it on the row an admission's turn saves
 * (`display_metadata.submission_id`: gateway/session_display.submission_turn_scope, gateway/response_filters.persisted_user_metadata_for_event;
 * Hermes pins it in tests/gateway/test_submission_row_identity.py) so a client can tell its own send's row. An older Hermes writes none.
 */
internal fun rowSubmissionId(row: JSONObject): String {
    val meta = row.opt("display_metadata")
    val fields = meta as? JSONObject ?: (meta as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
    return fields?.opt("submission_id") as? String ?: ""
}
/**
 * The saved row of each phone copy Hermes accepted (copy id to row id), as Hermes names it: the one user row stamped with the copy's submission id.
 * Hermes saves that row when the turn starts, before the model is asked (agent/turn_context._persist_turn_start), so it is the message's row from then on,
 * whatever the turn does after. Nothing is guessed: no stamped row, two of them, a row another copy holds, or a submission id two copies share, and the
 * copy is left as it is.
 */
internal fun submittedRowPairs(items: List<QueuedMessage>, rows: List<JSONObject>): Map<String, String> {
    fun id(row: JSONObject) = row.string("row_id", row.string("_row_id"))
    val claimed = items.map { it.rowId }.filter(String::isNotBlank).toMutableSet()
    val pairs = linkedMapOf<String, String>()
    for (item in items) {
        if (item.kind != "queue" || item.rowId.isNotBlank() || item.submissionId.isBlank() || item.state !in setOf("accepted", "settled") ||
            items.count { it.submissionId == item.submissionId } != 1) continue
        val row = rows.filter { it.string("role") == "user" && rowSubmissionId(it) == item.submissionId }.singleOrNull()?.let(::id)
            ?.takeIf { (it.toLongOrNull() ?: 0L) > 0 && it !in claimed } ?: continue
        claimed += row; pairs[item.id] = row
    }
    return pairs
}
/**
 * The copy once Hermes named its saved row: only the row is set. It keeps its state, so the turn's own receipt still settles it and retires it as before
 * ([linkedQueuedInputRow], [completedQueuedInputRow]); meanwhile the chat shows Hermes' row in its place ([chatTimelineMessages]).
 */
internal fun namedCopy(q: QueuedMessage, row: String): QueuedMessage =
    if (q.kind != "queue" || q.rowId.isNotBlank() || q.state !in setOf("accepted", "settled")) q else q.copy(rowId = row)
/**
 * The saved row of each phone copy still waiting for one (copy id to row id). Hermes can accept a turn (a photo, whose note it writes first) without naming its
 * row, so the copy keeps none while the turn runs, and a history load then shows the copy beside the row. Hermes saves the phone's own row as the first user row
 * after the row the chat ended on when the message was sent, so only that row (the first no other copy holds, steering and hidden rows aside) can be the copy's:
 * it must have the same words as the reader sees them (Hermes' note cut away) and carry a photo or file exactly when the copy does. If it is someone else's
 * message the copy is not looked for further down: it stays visible, and the completed turn's receipt ties it to its row later. A copy Hermes accepted, or one
 * whose turn was stopped, failed, interrupted or rejected (Hermes settles those only for a turn it started), can pair; a completed one is tied by its receipt, not by words. A copy whose outcome is unknown, one
 * with no known place to look from, and one nothing matches stay on screen: nothing is hidden on doubt, and nothing is sent again here. Each row pairs once.
 * A row Hermes stamped with a submission id ([rowSubmissionId]) is that send's row: it pairs only by its stamp ([submittedRowPairs]), never by words.
 */
internal fun acceptedRowPairs(items: List<QueuedMessage>, rows: List<JSONObject>): Map<String, String> {
    val open = items.filter { it.kind == "queue" && it.rowId.isBlank() && it.afterRowId != null &&
        (it.state == "accepted" || it.state == "settled" && it.outcome != "completed") }
        // A send still running takes its row before a stopped copy does: a queued message cancelled before it ran was never saved, and must not take the row of the same words sent again.
        .sortedWith(compareBy<QueuedMessage>({ it.state != "accepted" }, { it.createdAt }))
    if (open.isEmpty()) return emptyMap()
    fun id(row: JSONObject) = row.string("row_id", row.string("_row_id"))
    val claimed = items.map { it.rowId }.filter(String::isNotBlank).toMutableSet()
    val pairs = linkedMapOf<String, String>()
    for (item in open) {
        val from = if (item.afterRowId == "") 0 else rows.indexOfFirst { id(it) == item.afterRowId }.takeIf { it >= 0 }?.plus(1) ?: continue
        val row = rows.drop(from).firstOrNull { row ->
            row.string("role") == "user" && row.string("display_kind") !in setOf("steer", "hidden") && id(row).isNotBlank() && id(row) !in claimed &&
                rowSubmissionId(row).isBlank() && !isSteeringText(messageContentText("user", historyRowContent(row)))
        } ?: continue
        val content = messageContentText("user", historyRowContent(row))
        val shown = if (mentionsHermesNote(content)) splitAttachedMedia(content) else null
        val words = sameWords(shown?.text ?: content)
        val carriesMedia = shown?.media?.isNotEmpty() == true || row.rows("attachments").isNotEmpty()
        if (words != sameWords(item.text) || carriesMedia != item.attachments.isNotEmpty() || words.isBlank() && !carriesMedia) continue
        claimed += id(row); pairs[item.id] = id(row)
    }
    return pairs
}
/**
 * Marks a phone copy whose row id was found by words (acceptedRowPairs), not named by Hermes: a completed turn's receipt may still correct it, a row Hermes named itself never is.
 * It stands in the copy's outcome while the turn runs (the copy is "sent"); the receipt's own outcome replaces it.
 */
internal const val PAIRED_BY_WORDS = "paired_by_words"
/** The copy after a history load paired it with [row]: an accepted one is "sent" and marked, a stopped one only gets the row; a copy that has a row, or is not a plain queued send, is left alone. */
internal fun pairedCopy(q: QueuedMessage, row: String): QueuedMessage =
    if (q.kind != "queue" || q.rowId.isNotBlank()) q else when (q.state) {
        "accepted" -> q.copy(rowId = row, state = "sent", outcome = PAIRED_BY_WORDS)
        "settled" -> q.copy(rowId = row)
        else -> q
    }
/**
 * The copy as it is now. The render that ends a turn can pair the copy by words after the finish block read it; binding then starts from the paired copy,
 * which carries the marker and the row a correction needs. A copy already known as paired is used as given.
 */
internal fun currentCopy(queued: List<QueuedMessage>, item: QueuedMessage): QueuedMessage =
    if (item.outcome == PAIRED_BY_WORDS) item else queued.firstOrNull { it.id == item.id && it.submissionId == item.submissionId && it.admissionId == item.admissionId } ?: item
/** The row whose picture a word pairing wrongly gave away once the receipt proves [linked]; null when nothing was guessed or the guess was right. */
internal fun wrongPairedRow(item: QueuedMessage, linked: String): String? = item.rowId.takeIf { item.outcome == PAIRED_BY_WORDS && it != linked }
/** The copies the 60 s sweep asks Hermes about: finished ones to retire, and "sent" copies paired by words, which no other path would ever settle. */
internal fun receiptSweepCandidate(item: QueuedMessage): Boolean = item.kind == "queue" && item.admissionId.isNotBlank() && item.submissionId.isNotBlank() &&
    (item.state == "settled" && item.outcome == "completed" || item.state == "sent" && item.outcome == PAIRED_BY_WORDS)
/** Whether the copy a receipt refresh was started for ([item]) is still the one in the chat ([now]) and may be refreshed; a paired copy stays so until its receipt has settled it. */
internal fun refreshableCopy(item: QueuedMessage, now: QueuedMessage, automatic: Boolean): Boolean = when {
    item.state == "settled" && item.outcome == "completed" -> now.state == "settled" && now.outcome == "completed"
    item.state == "sent" && item.outcome == PAIRED_BY_WORDS -> now.state == "sent" && now.outcome == PAIRED_BY_WORDS || now.state == "settled" && now.outcome == "completed"
    else -> now.state in (if (automatic) setOf("accepted", "unknown") else setOf("accepted", "unknown", "settled"))
}
/** A paired copy changes only when its receipt is terminal: still running or unreadable changes nothing (and keeps the marker for the later correction). */
internal fun pairedCopyAfterReceipt(q: QueuedMessage, receiptState: String, outcome: String): QueuedMessage =
    if (q.state != "sent" || q.outcome != PAIRED_BY_WORDS || receiptState != "settled") q else q.copy(state = "settled", outcome = outcome)
/** Where the proof of a finished turn starts looking in saved history: the copy's row when Hermes named it, never one found by words. */
internal fun knownTurnRow(item: QueuedMessage?): String = item?.takeIf { it.outcome != PAIRED_BY_WORDS }?.rowId.orEmpty()
/** The chat without the photos [copyAttachmentIds] that a wrong word pairing put under [wrongRow]; attachments the row had of its own stay. */
internal fun releasedAttachmentChat(state: ChatState, current: () -> Boolean, wrongRow: String, copyAttachmentIds: Set<String>): ChatState {
    if (!current()) return state
    fun ours(attachments: List<JSONObject>) = attachments.isNotEmpty() && attachments.all { it.string("id") in copyAttachmentIds }
    val rawRows = state.raw.rows("messages").map { row ->
        if (row.string("row_id", row.string("_row_id")) == wrongRow && row.string("role") == "user" && ours(row.rows("attachments"))) row.copy().put("attachments", JSONArray()) else row
    }
    return state.copy(
        messages = state.messages.map { message -> if (message.id == wrongRow && message.role == "user" && ours(message.attachments)) message.copy(attachments = emptyList()) else message },
        raw = state.raw.copy().put("messages", JSONArray(rawRows)))
}
/**
 * The button on the first-chat card: none while it opens; for a profile Hermes refused, the one its line names ([beaconOffAction]: Turn on when the profile turned Beacon off,
 * Retry when turning it on did not work, nothing when Hermes no longer has the profile); else Retry.
 */
internal fun newChatAction(error: String, opening: Boolean): String = when {
    error.isBlank() || opening -> ""
    error in BeaconOffLines -> beaconOffAction(error)
    else -> "Retry"
}
/** The Attach tap on a first chat that has not opened: the notice names the button of the card on screen (none for a profile Hermes no longer has). */
internal fun newChatOpenNotice(error: String): String = when (newChatAction(error, false)) {
    "Turn on" -> OPEN_CHAT_NOTICE_TURN_ON
    "Retry" -> OPEN_CHAT_NOTICE_RETRY
    else -> error
}
/** The status of the page of a chat whose open was refused (401/403): it stays that chat, and what was typed stays with it. */
internal const val ACCESS_DENIED_RETAINED = "Not allowed · draft kept"
/**
 * The two pages of a chat that cannot open now but stays that chat, told by [status]: Hermes refused Beacon for the profile and turning it on did not work (the page says so in
 * one of [BeaconOffLines] with its button), or the open was refused ([ACCESS_DENIED_RETAINED]). Both keep the chat's draft in the composer; every other status belongs to a chat
 * that is still open or to no chat.
 */
internal fun isRetainedPage(status: String): Boolean = status in BeaconOffLines || status == ACCESS_DENIED_RETAINED
/**
 * The chat whose draft the composer holds while the page shows [chat] only because it cannot open now ([isRetainedPage]): that chat itself. The page has no source scope, so
 * without this what was typed there was kept nowhere and opening the chat again restored the older saved draft over it. Null for every other page.
 */
internal fun retainedDraftScope(chat: ChatState, sourceScope: Scope?, owner: Scope?): Scope? =
    if (owner != null && sourceScope == null && chat.sessionId.isNotBlank() && isRetainedPage(chat.status)) owner.session(chat.sessionId) else null
/**
 * The page of chat [id] that cannot open now ([status]). The retained [draft] stays with it, so what is saved and what the composer holds are the same draft: its queued messages
 * and place stay on the page (the composer and attachments are not cleared, see `retainDraftAndLeave`).
 */
internal fun retainedChatState(id: String, status: String, draft: Draft): ChatState =
    ChatState(sessionId = id, status = status, queued = draft.queued, anchor = draft.anchor, anchorOffset = draft.anchorOffset, anchorId = draft.anchorId)
/**
 * What a send that failed shows the owner: the chat's one-line page, a short notice (the message did not go out), or the app's error. Hermes refuses the Beacon plugin for the profile when
 * the phone asks for the gateway ticket, before anything is sent: sending cannot work until Beacon is turned on, so the chat stays that chat and says so with Turn on, as a chat that could
 * not be opened for the same reason ([isRetainedPage]). Only a message that was not sent gives way to the page; one that may have reached Hermes keeps the app's error and its receipt check.
 */
internal enum class SendFailureView { BeaconOffPage, Notice, Error }
internal fun sendFailureView(error: Throwable): SendFailureView = when {
    error is NativeTurnNotSent && beaconOffForProfile(error) -> SendFailureView.BeaconOffPage
    error is NativeTurnNotSent -> SendFailureView.Notice
    else -> SendFailureView.Error
}
/** What asking for the empty chat behind a new conversation does now. Every trigger (returning to the app, the chat screen, an Attach tap, typing) asks through this, so only one creates. */
internal enum class InitialChatStep { Wait, Resume, Create }
internal fun initialChatStep(unreachable: Boolean, connected: Boolean, foreground: Boolean, composable: Boolean, setupWizard: Boolean, opening: Boolean, failed: Boolean,
    attemptPending: Boolean, shellKept: Boolean, selecting: Boolean = false): InitialChatStep = when {
    unreachable && !connected -> InitialChatStep.Wait
    // A chat being selected (the one just created included) takes its place a moment later; asking again in between would read the same chat a second time.
    !foreground || !composable || setupWizard || opening || failed || attemptPending || selecting -> InitialChatStep.Wait
    shellKept -> InitialChatStep.Resume
    else -> InitialChatStep.Create
}
/**
 * Whether the recovery of a first chat Hermes no longer has may go on: the open it belongs to is still current ([selectionCurrent]) and nobody replaced the remembered shell
 * ([held] is the shell now remembered): it is still [remembered], or the recovery already forgot it. The recovery removes the shell itself, so a check that needed the shell
 * to still be there ended the recovery after it cleared the markers and before it made the new chat.
 */
internal fun shellRecoveryCurrent(selectionCurrent: Boolean, held: String?, remembered: String): Boolean = selectionCurrent && (held == null || held == remembered)
/**
 * Whether a route that ended on the Chat tab must prepare the new chat itself: signed in, on Chat, never made ready, no conversation open ([noConversation]) and none being
 * selected. Every other trigger waits for the chat to be ready, so without this a route that skipped sign-in's own start leaves it dimmed. Preparing restores the draft only;
 * the conversation itself is still opened by [initialChatStep], which waits for setup to end.
 */
internal fun newChatNeedsPreparing(authenticated: Boolean, tab: String, ready: Boolean, noConversation: Boolean, selecting: Boolean): Boolean =
    authenticated && tab == "Chat" && !ready && noConversation && !selecting
/**
 * Whether the phone's queued follow-up may be sent now that a stream has ended: once the finished turn's reply is proven to be in the saved history, when the
 * turn had already ended before this stream began and ended without an error, or when Hermes ended the turn with a failure that trying again can fix (its
 * error_surface says retryable and no reset lies ahead). Any other failure, a stop and a restart keep the follow-up in the box with its Send button: a person decides.
 * A turn nothing can prove (a gateway without session authority names no admission) leaves the follow-up queued until the chat is opened again. The drain itself
 * still asks Hermes that the chat is idle first.
 */
internal fun drainsQueueAfterTerminal(replyConfirmed: Boolean, terminalAlreadyObserved: Boolean, terminalError: String, nowMs: Long = System.currentTimeMillis()): Boolean =
    replyConfirmed || terminalAlreadyObserved && terminalError.isBlank() || retryCanHelp(terminalError, nowMs)

/** Every message still waiting in the box is held with [reason], the line the turn before it ended on; a message already held keeps its first reason. */
internal fun holdFollowUps(queued: List<QueuedMessage>, reason: String): List<QueuedMessage> =
    queued.map { if (it.kind == "queue" && it.state == "queued" && it.held.isBlank()) it.copy(held = reason) else it }
/** A held message is never sent by a passive drain, only by the owner's Send. */
internal fun passiveDrainSkips(item: QueuedMessage, explicit: Boolean): Boolean = item.held.isNotBlank() && !explicit
/**
 * TURN's drain rule for a chat that is not open (the outbox, P2-3; Root msg_333cceb80261): its last turn's line and end time when that turn
 * failed and its error_surface says retrying does not help now; null when what waits behind it may go.
 */
internal fun failedTurnHold(rows: List<JSONObject>, nowMs: Long = System.currentTimeMillis()): Pair<String, Long>? {
    val row = rows.lastOrNull() ?: return null
    val last = Message.from(row, rows.lastIndex)
    if (last.role != "assistant" || last.displayKind != "failed_turn" || last.failure.isBlank()) return null
    val surface = runCatching { JSONObject(last.failure) }.getOrNull() ?: return null
    val endedAt = dev.hermes.android.ui.epochMillis(last.recordedAt)
    val line = dev.hermes.android.data.turnErrorText(json("error_surface" to surface), endedAt.takeIf { it > 0 } ?: nowMs)
    return (line to endedAt).takeUnless { drainsQueueAfterTerminal(false, false, line, nowMs) }
}
/** The outbox's hold: each message that waited behind the failed turn (made before it ended; every one when Hermes gave no time) is held with its line. */
internal fun holdBehindFailedTurn(queued: List<QueuedMessage>, line: String, endedAt: Long): List<QueuedMessage> =
    queued.map { if (endedAt <= 0 || it.createdAt <= endedAt) holdFollowUps(listOf(it), line).single() else it }
/** The owner's Send releases every hold at once: one tap decides what goes next (the head goes first, the rest follow as after any turn). */
internal fun releaseHolds(queued: List<QueuedMessage>): List<QueuedMessage> = queued.map { if (it.held.isNotBlank()) it.copy(held = "") else it }
/** The chat's line for a turn the owner stopped. */
internal const val TURN_STOPPED = "Interrupted"
/** The chat's line for a turn Hermes cut for its own restart or shutdown: WEDGE-121's line and string key, so a person decides what goes next. */
internal const val TURN_RESTARTED = LOST_REPLY_STATUS
/** The status is TURN's restart line (plain or encoded): WEDGE's choice row already says it with its Send, so the line is not drawn twice (R1). */
internal fun isRestartLine(status: String): Boolean = dev.hermes.android.data.TurnLine.decode(status).phone == TURN_RESTARTED
/**
 * The line for a turn Hermes settled `interrupted`. Only a cancel this phone sent is the owner's Stop. Hermes names its own restart and shutdown interrupts in the frame's
 * `warning` (gateway/session_settlement_recovery.py RESTART_INTERRUPT_NOTICE, SHUTDOWN_INTERRUPT_NOTICE); a turn stopped anywhere else is still "Interrupted".
 */
internal fun interruptedTurnLine(payload: JSONObject, stoppedHere: Boolean): String = when {
    stoppedHere -> TURN_STOPPED
    payload.string("warning").let { it.startsWith("Gateway restarting:") || it.startsWith("Gateway shutting down:") } -> TURN_RESTARTED
    else -> TURN_STOPPED
}
/** The live status a saved frame keeps: Hermes' wait line, so a reconnect or reopen during a long wait does not look stuck. */
internal fun savedWaitLine(status: String): String = status.takeIf(::hermesWaitLine).orEmpty()
/** The status a restored frame brings back: its wait line while its turn still runs, else what the chat shows. */
internal fun statusAfterRestore(current: String, frame: JSONObject?): String =
    frame?.string("wait")?.takeIf { hermesWaitLine(it) && !frame.optBoolean("terminal") } ?: current
/** The reason under Hermes' failed_turn row, from its own descriptor; none for a stopped turn's row, or when the phone has no line more telling than the generic one. */
internal fun failedTurnReason(message: Message, nowMs: Long = System.currentTimeMillis()): String {
    if (message.displayKind != "failed_turn" || message.failure.isBlank()) return ""
    val surface = runCatching { JSONObject(message.failure) }.getOrNull() ?: return ""
    // Judged at the moment the turn failed (the row's own time): a limit that has since reset is still why it failed; only its passed reset time is not drawn.
    val line = dev.hermes.android.data.turnErrorText(json("error_surface" to surface), message.recordedAt.takeIf { it > 0 } ?: nowMs)
    return line.takeIf { dev.hermes.android.data.turnErrorHeadline(it) != dev.hermes.android.data.apiFailureText(500, "") }.orEmpty()
}
/**
 * Whether a saved live frame says that the turn this chat follows has ended. A frame belongs to the runtime id, which is the same for every turn of that runtime, so a frame
 * marked terminal ends the attach only when it is provably this turn's: the chat has a submission of its own ([followedSubmission]) and the frame names it. A frame left by an
 * earlier turn (an errored or interrupted one) must not hide a newer turn that another client, a watch or the desktop started in the same runtime.
 */
internal fun frameEndedTurn(frame: JSONObject?, followedSubmission: String): Boolean =
    followedSubmission.isNotBlank() && frame != null && frame.optBoolean("terminal") && frame.string("submission_id") == followedSubmission
/**
 * What Hermes' own answer makes of a saved `terminal` frame that names this chat's turn. The chat keeps the submission id of its own failed turn and that turn left its frame, so a frame
 * that names the chat's id can still be an earlier turn's: only an `idle` turn is over. One Hermes reports working or waiting is a newer turn, and an answer that could not be read
 * changes nothing (the turn is followed).
 */
internal enum class SavedTerminalFrame { Ended, NewerTurn, Unknown }
internal fun savedTerminalFrame(hermesStatus: String?): SavedTerminalFrame = when (hermesStatus) {
    "idle" -> SavedTerminalFrame.Ended
    "working", "waiting" -> SavedTerminalFrame.NewerTurn
    else -> SavedTerminalFrame.Unknown
}
/**
 * The part of a saved frame that is put back on screen when its runtime is followed again. A newer turn (the frame is the failed turn's) keeps only the frame's cursor, where this
 * runtime's events go on: the failed turn's partial text, reasoning, tools, boundary and error stay out of it. Any other frame is restored as it is.
 */
internal fun restoredFrame(frame: JSONObject?, saved: SavedTerminalFrame?): JSONObject? =
    if (saved == SavedTerminalFrame.NewerTurn && frame != null) json("cursor" to frame.string("cursor")) else frame
/** The chat following [stream] again with a saved frame's words, reasoning, steps and wait line; what the chat's saved rows already hold is not drawn twice. */
internal fun restoredLive(chat: ChatState, live: JSONObject?, stream: String): ChatState {
    if (live == null) return chat.copy(streamId = stream)
    val restored = chat.copy(liveText = live.string("text"), liveReasoning = live.string("reasoning"), liveTools = live.rows("tools"), liveSealed = sealedFromFrame(live), liveSealedAt = sealedPlacesFromFrame(live), liveBase = live.optInt("base"), liveBasePrompt = live.optString("base_prompt"), liveOrdinal = live.optInt("ordinal"), streamId = stream,
        status = statusAfterRestore(chat.status, live))
    return if (restored.raw.has("messages")) withoutSavedLive(restored, restored.raw.rows("messages"), LiveAssistantBoundary.from(live)) else restored
}
/**
 * R2C: the turn a chat showed, painted back on a cold start from its saved record ([record]: the stream and the submission it was bound to) and the
 * stream's live frame, so the first frame shows it working with its words so far. Null when there is nothing to paint: no record, another send owns the
 * chat now, or the frame says this turn already ended. Hermes' own answer settles the painted turn once the app is connected again.
 */
internal fun paintedTurn(chat: ChatState, record: JSONObject?, frame: JSONObject?): ChatState? {
    if (record == null) return null
    val stream = record.string("stream_id").takeIf { it.matches(streamIdPattern) } ?: return null
    val shown = chat.raw.string("_display_submission_id")
    val bound = record.string("submission_id")
    if (shown.isNotBlank() && bound.isNotBlank() && shown != bound) return null
    val owner = shown.ifBlank { bound }
    val live = frame?.takeIf { owner.isBlank() || it.string("submission_id") == owner }
    if (live?.optBoolean("terminal") == true) return null
    return restoredLive(chat.copy(status = "Working"), live, stream)
}
/**
 * R2C: a history read that names no running stream (Beacon's native reads never do: history is passive) does not end the turn the chat shows while
 * that history still stops inside a turn. Only the stream's own end or Hermes' answer about the turn ends it; before, a return from another app drew
 * the chat idle for seconds while Hermes was still working.
 */
internal fun passiveReadKeepsTurn(shownStream: String, namesStream: Boolean, rows: List<JSONObject>): Boolean =
    shownStream.isNotBlank() && !namesStream && historyStopsInsideATurn(rows)
/**
 * R2C: what the open chat's turn record ([LIVE_TURN]) becomes when the chat or its stream changes from [left] (chat id, stream id) to [shown]: the record
 * to write, "" to delete it, or null to leave it. A released stream deletes it; so does a chat opened idle while [connected] (its turn ended unseen).
 * A cold restore opens its chat before it is connected, so it never deletes the record it is about to paint.
 */
internal fun liveTurnRecordChange(left: Pair<String, String>?, shown: ChatState, connected: Boolean): String? = when {
    shown.sessionId.isBlank() -> null
    shown.streamId.isNotBlank() -> json("stream_id" to shown.streamId, "submission_id" to shown.raw.string("_display_submission_id")).toString()
    left != null && left.first == shown.sessionId -> if (left.second.isNotBlank()) "" else null
    connected -> ""
    else -> null
}
/**
 * The queued copy and the ids a finished stream belongs to. The done event names the admission; a turn settled without it (the stream went quiet and Hermes
 * was asked) is the one the chat followed, so the ids the chat displays stand in. Without a queued copy the ids count only when they are the displayed ones.
 */
internal fun finishedTurnIdentity(queued: List<QueuedMessage>, completion: JSONObject?, displaySubmission: String, displayAdmission: String): Pair<QueuedMessage?, TurnIdentity> {
    val submission = completion?.optJSONObject("persisted_turn")?.string("submission_id").orEmpty().ifBlank { displaySubmission }
    val admission = completion?.string("admission_id").orEmpty().ifBlank { displayAdmission }
    val item = if (admission.isBlank()) null else queued.singleOrNull { it.submissionId == submission && it.admissionId == admission }
    val identity = item?.let { TurnIdentity(it.submissionId, it.admissionId) }
        ?: TurnIdentity(submission, admission).takeIf { submission.isNotBlank() && admission.isNotBlank() && displaySubmission == submission && displayAdmission == admission }
        ?: TurnIdentity("", "")
    return item to identity
}
/** How long a running reply may stay silent before Hermes is asked whether it already ended, and how often that is checked. */
internal const val STREAM_QUIET_START_MS = 20_000L
internal const val STREAM_QUIET_CHECK_MS = 5_000L
internal enum class SilentStream { Settle, Resubscribe, Wait, Lost }
/** The longest a quiet running turn goes between checks with Hermes. */
internal const val STREAM_QUIET_MAX_MS = 10 * 60_000L
/** How long a stream may stay quiet before Hermes is asked about its turn. Only Hermes' own answer (working, waiting) stretches it; a probe that failed says nothing, and an event heard starts it over at [base]. */
internal class QuietWindow(private val base: Long, start: Long = base) {
    var ms = start
        private set
    fun stretched() = minOf(ms * 2, STREAM_QUIET_MAX_MS)
    fun stretch() { ms = stretched() }
    fun heard() { ms = base }
}
/** A stream can go deaf for any reason (a dropped socket, a reconnect; on older Hermes another connection can take its events), and the turn then ends unseen.
 *  Once Hermes reports the turn, an ended one is settled from history and a running one resubscribes; a waiting or unknown one is left alone. */
internal fun silentStreamAction(status: String?): SilentStream = when (status) {
    "idle" -> SilentStream.Settle
    "working" -> SilentStream.Resubscribe
    // Hermes lost the followed reply (a restart, or a worker that died with the gateway up) and the owner's messages wait behind it: nothing will come.
    "lost" -> SilentStream.Lost
    else -> SilentStream.Wait
}
/** A cold look at a chat (the process restarted, or the chat was left and reopened) asks Hermes about its reply only when Beacon delivered a prompt it never saw end and no stream is shown. Ordinary browsing never asks. */
internal fun shouldAskRunning(marked: Boolean, shownStream: String): Boolean = marked && shownStream.isBlank()
/** A stream job reports to a chat only while the request it started with is current: opening a chat moves the session generation on, which makes every job started before it drop its events until its own quiet window ends. Such a job follows nothing, so the open asks Hermes. */
internal fun streamFollows(alive: Boolean, started: ScopeSettlement?, scope: Scope?, client: Any?, origin: Int, generation: Int): Boolean =
    alive && started?.current(scope, client, origin, generation) == true
internal enum class ColdRun { Keep, Follow, Settle }
/** What a look at a chat's lost reply through Beacon's read-only route did: let a lone one go, left one held (the choice, Hermes' refusal, a reply now shown running), or found none (or could not read the route). */
internal enum class LostOnOpen { Closed, Held, NotLost }
/** Hermes' answer to that one question. A reply still working or waiting for the user is followed again; an ended one is settled from history, which also drops the marker; an unreadable answer changes nothing and keeps the marker. */
internal fun coldRunAction(status: String?): ColdRun = when (status) {
    "working", "waiting" -> ColdRun.Follow
    "idle" -> ColdRun.Settle
    else -> ColdRun.Keep
}
/**
 * CHATQA-121 follow-up 3: the saved history stops inside a turn, by Hermes' own rule (agent/turn_failure_copy.py OPEN_TURN_TAIL_ROLES: the newest
 * conversation row is the person's or a tool's), or on an assistant row whose tool calls are not answered yet. Only then can a reply still run there,
 * so only then does the chat look at Hermes' live inventory; a chat that ends on a reply is never asked about.
 */
internal fun historyStopsInsideATurn(rows: List<JSONObject>): Boolean {
    val last = rows.lastOrNull { it.string("role") in setOf("user", "assistant", "tool") } ?: return false
    return when (last.string("role")) {
        "user", "tool" -> true
        else -> (last.optJSONArray("tool_calls")?.length() ?: 0) > 0
    }
}
/**
 * CHATQA-121 follow-up 3: the runtime of the reply Hermes runs in [storedSessionId], read from its live inventory (`session.active_list`, which never
 * resumes, creates or sends). One row only may name the chat (as background activity reads it), and only "working" or "waiting" is followed: a started
 * turn, which Delete also counts as running and Stop can interrupt. Idle, starting (admitted, not started: no row of the person's yet, nothing to stop),
 * unknown or ambiguous rows give null.
 */
internal fun observedRunningTurn(inventory: JSONObject?, storedSessionId: String): String? {
    val runtime = dev.hermes.android.ui.backgroundActivityRuntime(inventory, storedSessionId) ?: return null
    val row = inventory?.rows("sessions")?.singleOrNull { it.opt("id") == runtime } ?: return null
    return runtime.takeIf { row.opt("status") in setOf("working", "waiting") && it.matches(streamIdPattern) }
}
data class ChatState(val sessionId: String = "", val title: String = "New conversation", val messages: List<Message> = emptyList(), val raw: JSONObject = JSONObject(), val streamId: String = "", val liveText: String = "", val liveReasoning: String = "", val liveTools: List<JSONObject> = emptyList(), val status: String = "", val approval: JSONObject? = null, val clarification: JSONObject? = null, val uncertain: String = "", val queued: List<QueuedMessage> = emptyList(), val anchor: Int = 0, val anchorOffset: Int = 0, val anchorId: String = "", val callMessages: List<CallTranscript> = emptyList(), val alerts: List<ChatAlert> = emptyList(), val projectionReady: Boolean = true, val sessionReasoningEffort: String = "", val waitingApprovals: List<JSONObject> = emptyList(), val waitingClarifications: List<JSONObject> = emptyList(), val lastAnswerAt: Long? = null, val endedPrompts: List<Pair<String, Long>> = emptyList(), val liveSealed: List<String> = emptyList(), val liveSealedAt: List<Int> = emptyList(), val liveBase: Int = 0, val liveBasePrompt: String = "", val liveOrdinal: Int = 0,
    /** Hermes lost this chat's reply in a restart and the owner's messages wait behind it: the chat offers to send them ([lostChoiceShown]). */
    val lostWaiting: Boolean = false)
internal fun sessionCanStillBeUnpersisted(clientCreated: Boolean, chat: ChatState): Boolean =
    clientCreated && chat.messages.isEmpty() && chat.callMessages.isEmpty() && chat.liveText.isBlank() && chat.streamId.isBlank() && chat.raw.optInt("message_count") == 0
/** The open chat is a phone-created one that was never sent, so New conversation keeps it instead of adding another empty row.
 *  A chat opened with a title, workspace or model ([keptApart]) is not a plain one: a message typed there would land in it. */
internal fun reusableEmptyChat(clientCreated: Boolean, chat: ChatState, sendInFlight: Boolean, keptApart: Boolean): Boolean =
    sessionCanStillBeUnpersisted(clientCreated, chat) && chat.projectionReady && chat.sessionId.isNotBlank() && chat.uncertain.isBlank() && !sendInFlight && !keptApart
/** Android's photo picker keeps a photo's own file name private and names it by its media number ("753.png"); such a photo is called "Photo". */
internal fun attachmentName(uri: String, shown: String): String {
    val name = shown.trim()
    val number = Regex("""\d+(\.[A-Za-z0-9]{1,8})?""").matchEntire(name)
    return when {
        name.isBlank() -> "Attachment"
        number != null && "picker" in uri.removePrefix("content://").substringBefore('?') -> "Photo" + number.groupValues[1].lowercase()
        else -> name
    }
}
/** The send refusals that say their own reason; any other never-sent failure gets a generic line, or waits for Hermes when out of reach. */
internal val SEND_REFUSALS = setOf(
    "Couldn't save the message. Keep the draft open and try again.",
    // The same refusal on a full phone (R2 #9), in words that say why.
    DRAFT_STORE_FULL_ERROR,
    "The model changed. Pick this message's model to send it.",
)
internal const val OFFLINE_SEND_NOTICE = "Not sent yet. It goes when Hermes is back."
/** A send Hermes or the phone refused with its own words (the model, saving, a refused file by name): never "offline", never sent again by itself. */
internal fun refusedWithItsOwnWords(e: Throwable): Boolean = waitsForOwner(e) || e is NativeTurnNotSent && e.message in SEND_REFUSALS
/**
 * CHAT-121 follow-up 11 (backlog 82, Root msg_ec3f92a4d60a): the send never left the phone because the link could not carry it now, while Hermes
 * itself answers: its connection was not ready within the admission's 15 s (a call warm-up's 43 KB ahead of it in the phone's one upload queue,
 * windows 3 and 4 of follow-up 10), or it dropped before the prompt went. Hermes' own refusals (an ApiFailure in the chain) and refusals with
 * words of their own are not this.
 */
internal fun linkCouldNotCarry(e: Throwable): Boolean {
    if (e !is NativeTurnNotSent || e is AttachmentStalled || refusedWithItsOwnWords(e)) return false
    if (e.message == "Native connection changed before the request") return true
    if (e.message != "Couldn't open the chat. Your message is kept.") return false
    val chain = generateSequence(e.cause) { it.cause }.take(8).toList()
    // Live (follow-up 11's first block): a ticket whose request waited behind 43 KB came back older than its 20 s by the phone's clock, the link's doing too.
    return chain.any { it is java.io.IOException || it is IllegalStateException && it.message == "Canonical gateway ticket expired" } &&
        chain.none { it is ApiFailure || it is NativeTurnNotSent || it is dev.hermes.android.data.CanonicalRouteMissing }
}
/** The pauses before a message the link could not carry goes again by itself: after the first and the second failed try. */
internal val LINK_RETRY_PAUSES_MS = listOf(5_000L, 15_000L)
/** The pause before the next try after [failedTries] tries the link could not carry, or null once they are spent (the file rule's limit): "Not sent" with Send. */
internal fun nextLinkTryPause(failedTries: Int): Long? =
    if (failedTries >= ATTACHMENT_STALL_TRIES) null else LINK_RETRY_PAUSES_MS[(failedTries - 1).coerceIn(0, LINK_RETRY_PAUSES_MS.lastIndex)]
/** When this phone archived a chat (in its kept row); a list asked for more than [ARCHIVE_SETTLE_MS] later is trusted over it. */
internal const val ARCHIVED_HERE_AT = "_archived_here_at"
internal const val ARCHIVE_SETTLE_MS = 10_000L
/** P2-3: how long a closed chat's next waiting message waits for that chat's reply to end before it is left for the next time Hermes answers. */
internal const val OUTBOX_IDLE_WAIT_MS = 60 * 60_000L
/** Only the automatic open and a bare New conversation ask for a plain chat; any option makes the chat its own. */
internal fun plainChatRequest(body: JSONObject): Boolean = body.keys().asSequence().all { it == "worktree" && !body.optBoolean(it) }
private fun reasoningQuery(sessionId: String, row: JSONObject) =
    mapOf("session_id" to sessionId, "model" to row.string("model"), "provider" to row.string("model_provider"))

/** The last runtime read for this chat and model can hold a pin absent from its history row. */
internal fun reloadedEffort(old: ChatState, sessionId: String, row: JSONObject, picker: JSONObject? = null): String {
    val stored = row.string("reasoning_effort", row.string("reasoning"))
    val sameModel = old.sessionId == sessionId && old.raw.string("model") == row.string("model") &&
        old.raw.string("model_provider") == row.string("model_provider")
    return (picker?.opt("value") as? String)?.ifBlank { stored }
        ?: stored.ifBlank { if (sameModel) old.sessionReasoningEffort else "" }
}

/** The chat while [id] is being selected. Re-selecting the open chat keeps its transcript, readiness and live output on screen
 *  until the fresh projection replaces them; another chat starts closed until its own read completes. "Refresh question" re-reads through
 *  this too and must not remove the question before the reload has brought a snapshot: if the read then fails (offline, timeout) the card
 *  stays and Hermes is still waiting for the answer. Opening another chat never carries a question over. */
internal fun chatOnSelection(previous: ChatState, id: String, titleHint: String, previousEffort: String): ChatState =
    if (previous.sessionId == id) previous
    else ChatState(sessionId = id, title = titleHint.ifBlank { "New conversation" }, projectionReady = false, sessionReasoningEffort = previousEffort)
/** After a successful read Hermes is asked for its pending approval and question when a stream is attached, and always after "Refresh question": its answer decides. */
/** R2C: a turn painted on a cold start ([followed] false) is not asked here: its own look comes next and asks when Hermes waits on a card; asking first held its stream back by two slow reads. */
internal fun asksPromptsAfterRead(dropQuestion: Boolean, streamId: String, followed: Boolean = true): Boolean = dropQuestion || streamId.isNotBlank() && followed
/** A History row is a summary: the chat's messages and call transcripts stay in the chat itself. */
internal fun historyRowOf(session: JSONObject): JSONObject = session.copy().apply { remove("messages"); remove("voice_transcripts") }
/** Hermes stores a new chat at its first message; until then reads of the open, phone-created chat answer 404. */
internal fun unsavedOpenChatRead(error: Throwable, id: String, clientCreated: Boolean, chat: ChatState): Boolean =
    error is ApiFailure && error.status == 404 && id.isNotBlank() && chat.sessionId == id && sessionCanStillBeUnpersisted(clientCreated, chat)
/** A prompt event wraps its prompt in `pending` (null clears it); a Hermes server request is the prompt itself.
 *  `isNull` is also true for a missing key, which dropped every server-request prompt. */
internal fun pendingPrompt(event: JSONObject): JSONObject? = event.optJSONObject("pending") ?: event.takeUnless { it.has("pending") }
/** What the owner sees when they tap a card Hermes already ended. */
internal const val PROMPT_ENDED_NOTICE = "This request has ended."
/**
 * Hermes can hold several open prompts for one chat (parallel tool calls wait on their own approvals). The chat shows one card per kind at a time (the first),
 * and the others wait behind it, oldest first, each known by its own prompt id; answering or a settle shows the next, and none is dropped or answered for another.
 */
private fun promptKeys(prompt: JSONObject) = listOf("id", "approval_id", "clarify_id").map { prompt.string(it) }.filter(String::isNotBlank)
private fun promptIs(prompt: JSONObject, id: String) = id.isNotBlank() && id in promptKeys(prompt)
/** The same request: they share an id. A prompt with no id at all can only be matched with another one that has none. */
private fun sameRequest(a: JSONObject, b: JSONObject) = promptKeys(a).let { keys -> if (keys.isEmpty()) promptKeys(b).isEmpty() else keys.any { it in promptKeys(b) } }
private fun ChatState.promptsOf(kind: String): List<JSONObject> =
    if (kind == "approval") listOfNotNull(approval) + waitingApprovals else listOfNotNull(clarification) + waitingClarifications
/** When the phone first got a prompt, and when it took the front from another card (elapsed-realtime ms, kept inside the prompt itself). */
internal const val PROMPT_SEEN_AT = "_seen_at"
internal const val PROMPT_PROMOTED_AT = "_promoted_at"
/** How long a card that takes the front from another card, or appears right after an answer, stays unanswerable, so the tap that answered the last card cannot answer this one. A var only so a device test can widen it. */
internal object PromptGuard { @Volatile var windowMs = 700L }
/** Carries what the phone knows about a prompt over to a newer copy of the same prompt. */
private fun carried(from: JSONObject?, to: JSONObject): JSONObject {
    if (from == null) return to
    val copy = to.copy()
    for (key in listOf(PROMPT_SEEN_AT, PROMPT_PROMOTED_AT)) if (from.has(key) && !copy.has(key)) copy.put(key, from.opt(key))
    return copy
}
/** When Hermes gives up on the prompt: it declines an approval after its own wait (`timeout_s`), counted here from when the phone first saw it. A prompt with no wait never ends that way. */
private fun expiresAt(prompt: JSONObject): Long? {
    val seconds = prompt.optDouble("timeout_s", 0.0)
    return if (seconds > 0 && prompt.has(PROMPT_SEEN_AT)) prompt.optLong(PROMPT_SEEN_AT) + (seconds * 1000).toLong() else null
}
/**
 * Sets the kind's queue. A prompt whose own wait ran out is not in it, a prompt the phone had not seen is stamped as seen, and a card that is new in front is stamped as
 * promoted (see [PromptGuard]): one that takes the front from another card, or one that appears within the guard window after the owner last answered (the repeat tap of that
 * answer must not reach it). A card that appears with no recent answer is not, and one that stays in front keeps its stamp.
 */
private fun ChatState.withPrompts(kind: String, all: List<JSONObject>, now: Long): ChatState {
    val before = if (kind == "approval") approval else clarification
    val live = all.filterNot { prompt -> expiresAt(prompt)?.let { it <= now } == true }.map { if (it.has(PROMPT_SEEN_AT)) it else it.copy().put(PROMPT_SEEN_AT, now) }
    val front = live.firstOrNull()
    val answeredJustBefore = lastAnswerAt?.let { now - it in 0L until PromptGuard.windowMs } == true
    val ready = if (front != null && (before != null && !sameRequest(before, front) || before == null && answeredJustBefore)) listOf(front.copy().put(PROMPT_PROMOTED_AT, now)) + live.drop(1) else live
    return if (kind == "approval") copy(approval = ready.firstOrNull(), waitingApprovals = ready.drop(1)) else copy(clarification = ready.firstOrNull(), waitingClarifications = ready.drop(1))
}
/** The owner answered a card (the tap, and again when the answer lands). */
internal fun ChatState.answerTapped(now: Long): ChatState = copy(lastAnswerAt = now)
/** Hermes asked [prompt] (null = it holds none). A prompt already known keeps its place and its arrival, and takes the newer content. */
internal fun ChatState.promptAsked(kind: String, prompt: JSONObject?, now: Long): ChatState {
    val all = promptsOf(kind)
    return withPrompts(kind, when {
        prompt == null -> emptyList()
        all.any { sameRequest(it, prompt) } -> all.map { if (sameRequest(it, prompt)) carried(it, prompt) else it }
        else -> all + prompt
    }, now)
}
/** The prompt [id] ended (answered, settled, cancelled); the next one waiting comes up. A blank [kind] means either. */
internal fun ChatState.promptEnded(id: String, now: Long, kind: String = ""): ChatState =
    listOf("approval", "clarify").filter { kind.isBlank() || it == kind }.fold(this) { state, k ->
        val all = state.promptsOf(k)
        val gone = all.filter { promptIs(it, id) }
        // What ended is remembered (the last 32): a poll that began before it must not bring it back.
        if (gone.isEmpty()) state else state.withPrompts(k, all - gone.toSet(), now).copy(endedPrompts = (state.endedPrompts + gone.flatMap(::promptKeys).distinct().map { it to now }).takeLast(32))
    }
/**
 * Hermes' own answer to "what is pending now": its first prompt and the ones behind it replace the queue exactly. A prompt the phone got after the ask began
 * ([since]) may be newer than the answer, so it stays behind the listed ones; one that ended on the phone after the ask began is older than the answer, so it is not brought back.
 */
internal fun ChatState.promptsListed(kind: String, first: JSONObject?, waiting: List<JSONObject>, now: Long, since: Long): ChatState {
    val known = promptsOf(kind)
    val endedSince = endedPrompts.filter { it.second >= since }.map { it.first }
    val listed = (listOfNotNull(first) + waiting).filterNot { prompt -> promptKeys(prompt).any { it in endedSince } }.map { prompt -> carried(known.firstOrNull { sameRequest(it, prompt) }, prompt) }
    val meanwhile = known.filter { it.has(PROMPT_SEEN_AT) && it.optLong(PROMPT_SEEN_AT) >= since && listed.none { prompt -> sameRequest(prompt, it) } }
    return withPrompts(kind, listed + meanwhile, now)
}
/** Both of Hermes' answers at once (null = the ask failed, which is not an answer: that kind stays as it is). */
internal fun ChatState.promptsPolled(approvalReply: JSONObject?, clarifyReply: JSONObject?, now: Long, since: Long): ChatState {
    val withApproval = if (approvalReply == null) this else promptsListed("approval", approvalReply.optJSONObject("pending"), approvalReply.rows("waiting"), now, since)
    return if (clarifyReply == null) withApproval else withApproval.promptsListed("clarify", clarifyReply.optJSONObject("pending"), clarifyReply.rows("waiting"), now, since)
}
/** Drops every prompt whose own wait has run out. */
internal fun ChatState.promptsPruned(now: Long): ChatState = listOf("approval", "clarify").fold(this) { state, k ->
    val all = state.promptsOf(k)
    if (all.any { expiresAt(it)?.let { at -> at <= now } == true }) state.withPrompts(k, all, now) else state
}
/** When the next queued prompt's own wait runs out (elapsed-realtime ms), or null when none will. */
internal fun nextPromptExpiry(state: ChatState): Long? = listOf("approval", "clarify").flatMap { state.promptsOf(it) }.mapNotNull { expiresAt(it) }.minOrNull()
/** Until when (elapsed-realtime ms) a card that took the front from another card, or appeared right after an answer, stays unanswerable; 0 = not at all. */
internal fun promptGuardedUntil(prompt: JSONObject?): Long = if (prompt != null && prompt.has(PROMPT_PROMOTED_AT)) prompt.optLong(PROMPT_PROMOTED_AT) + PromptGuard.windowMs else 0L
internal fun promptGuarded(prompt: JSONObject?, now: Long): Boolean = now < promptGuardedUntil(prompt)
// Hermes stores model-switch steering as user rows, so the reserved prefix identifies it.
internal fun visibleTranscriptMessages(messages: List<Message>): List<Message> =
    messages.filterNot { it.displayKind == "hidden" || isSteeringText(it.text) }

private val runsOfWhitespace = Regex("\\s+")

/**
 * The transcript as Hermes' own view draws a reply whose final message only repeats the text said beside the last tool step (the housekeeping-tool reuse path: Hermes ends the turn on
 * its earlier reply, agent/turn_empty_response.py:151-164, and saves it again as a closing row, agent/turn_finalizer.py:240-265). Hermes folds exactly that shape when it reads a chat back:
 * `dedupeRepeatedTextInParts` (apps/desktop/src/lib/chat-messages/parts.ts:438, applied to each assistant message at hydration.ts:680). In a run of assistant rows (a question starts a new one),
 * when the LAST text equals the text before it once whitespace is collapsed (`normalizeWs`, parts.ts:403), no tool call comes after the last text, and a tool call lies between the two,
 * the earlier text goes and its tool cards stay. A final that only starts with the earlier sentence, an authored repeat in an earlier round, or two texts with no tool call between are left alone.
 */
internal fun foldRepeatedFinal(messages: List<Message>): List<Message> {
    val result = messages.toMutableList()
    var from = 0
    for (end in 0..messages.size) {
        if (end < messages.size && messages[end].role != "user") continue
        val run = (from until end).filter { messages[it].role == "assistant" && messages[it].displayKind.isBlank() }
        val texts = run.filter { messages[it].text.isNotBlank() }
        if (texts.size >= 2) {
            val last = texts.last()
            val earlier = texts[texts.size - 2]
            val key = messages[last].text.replace(runsOfWhitespace, " ").trim()
            if (key.isNotEmpty() && key == messages[earlier].text.replace(runsOfWhitespace, " ").trim() && messages[last].tools.isEmpty() &&
                run.none { it > last && messages[it].tools.isNotEmpty() } && run.any { it in earlier until last && messages[it].tools.isNotEmpty() }) {
                result[earlier] = messages[earlier].copy(text = "")
            }
        }
        from = end + 1
    }
    return result
}

internal fun pendingSendBoundary(messages: List<Message>): String = visibleTranscriptMessages(messages).lastOrNull()?.id.orEmpty()

internal const val UNCONFIRMED_REPLIES = "_unconfirmed_replies"

/**
 * Whether [incoming] is a later snapshot of the chat [previous] holds: the same non-blank session id, and the same profile when both name one.
 * A snapshot that names no profile (a stream's state_saved) cannot say it is another chat, and one that did not must not turn every later merge into
 * a replacement: that dropped the open turn's display identity, so its stream could never release the chat.
 */
internal fun sameChatProjection(previous: JSONObject, incoming: JSONObject): Boolean {
    val session = previous.string("session_id")
    if (session.isBlank() || session != incoming.string("session_id")) return false
    val before = previous.string("profile"); val after = incoming.string("profile")
    return before.isBlank() || after.isBlank() || before == after
}

/** Optional reads may be incomplete; only a complete successful archive can remove its earlier rows. */
internal fun mergeChatProjection(previous: JSONObject, incoming: JSONObject, allowRemoval: Boolean = false, removedUserRowId: String = ""): JSONObject {
    if (!sameChatProjection(previous, incoming)) return incoming.copy()
    val epoch = incoming.string("replay_epoch")
    if (!allowRemoval && epoch.isNotBlank() && epoch == previous.string("replay_epoch") && incoming.opt("last_sequence") is Number &&
        previous.opt("last_sequence") is Number && incoming.optLong("last_sequence") < previous.optLong("last_sequence")) return previous.copy()
    return incoming.copy().apply {
        if (!incoming.has("messages") && previous.has("messages")) put("messages", JSONArray(previous.getJSONArray("messages").toString()))
        if (!incoming.has("voice_transcripts") || incoming.opt("voice_transcripts_available") == false || incoming.optBoolean("voice_transcripts_truncated")) {
            val calls = previous.rows("voice_transcripts").associateByTo(linkedMapOf()) { it.string("event_id") }
            for (fresh in incoming.rows("voice_transcripts")) {
                val id = fresh.string("event_id"); val old = calls[id]
                if (old?.opt("final") == true && fresh.opt("final") == false) continue
                calls[id] = fresh.copy().apply {
                    if (old != null && recordedMessageTime(old.opt("created_at")) > 0) put("created_at", old.opt("created_at"))
                }
            }
            put("voice_transcripts", JSONArray(calls.values))
        }
        fun rowId(row: JSONObject): String = row.string("row_id", row.string("_row_id", row.string("id")))
            .toLongOrNull()?.takeIf { it > 0 }?.toString().orEmpty()
        val currentRows = rows("messages")
        val previousRows = previous.rows("messages")
        val removedAt = previousRows.indexOfFirst { rowId(it) == removedUserRowId && it.string("role") == "user" }
        val removedRows = if (allowRemoval && removedAt >= 0) previousRows.drop(removedAt).map(::rowId).filter(String::isNotBlank).toSet() else emptySet()
        val removeAll = allowRemoval && removedUserRowId.isBlank()
        val retained = (incoming.rows(UNCONFIRMED_REPLIES) + previous.rows(UNCONFIRMED_REPLIES)).associateByTo(linkedMapOf()) { it.string("id") }
        if (!removeAll && incoming.has("messages")) {
            val last = previousRows.lastOrNull { it.string("role") == "assistant" && rowId(it).isNotBlank() &&
                messageContentText("assistant", historyRowContent(it)).isNotBlank() }
            val parent = last?.let { message -> previousRows.take(previousRows.indexOf(message)).lastOrNull { it.string("role") == "user" } }?.let(::rowId).orEmpty()
            if (last != null && !(allowRemoval && (rowId(last) in removedRows || parent == removedUserRowId)) &&
                currentRows.none { rowId(it) == rowId(last) && it.string("role") == "assistant" }) {
                val id = "unconfirmed-history:${rowId(last)}"
                retained[id] = json("id" to id, "role" to "assistant", "display_kind" to "unconfirmed_reply", "actionable" to false,
                    "session_id" to incoming.string("session_id"), "_history_row_id" to rowId(last), "_history_user_row_id" to parent, "timestamp" to last.opt("timestamp"),
                    "text" to messageContentText("assistant", historyRowContent(last)), "reasoning" to last.string("reasoning", last.string("reasoning_content")))
            }
        }
        val unresolved = retained.values.filterNot { artifact ->
            allowRemoval && (artifact.string("_history_row_id") in removedRows ||
                removedUserRowId.isNotBlank() && artifact.string("_history_user_row_id") == removedUserRowId) ||
                artifact.string("_history_row_id").takeIf(String::isNotBlank)?.let { id ->
                    currentRows.any { rowId(it) == id && it.string("role") == "assistant" }
                } == true
        }
        if (removeAll) remove(UNCONFIRMED_REPLIES) else if (retained.isNotEmpty()) put(UNCONFIRMED_REPLIES, JSONArray(keptRowsNotSaved(currentRows, unresolved)))
        for (key in listOf("_display_submission_id", "_display_admission_id", "profile")) if (!has(key) && previous.has(key)) put(key, previous.opt(key))
    }
}

/**
 * The send identity of the chat that is open now. A render builds its projection from the raw it saw at its start and replaces the chat's raw after it has waited (the cache read); a
 * submission id that accepted() wrote in between would be lost, and the stream attached next would follow an empty id (fl6 trace under load). The newer, non-blank id of the same chat wins.
 */
internal fun carryDisplayIdentity(next: JSONObject, current: JSONObject): JSONObject {
    for (key in listOf("_display_submission_id", "_display_admission_id")) if (current.string(key).isNotBlank()) next.put(key, current.opt(key))
    return next
}

/** The id prefix of kept copies judged by Hermes' ids and the turn's order ([keptRowsNotSaved]), never by their words. */
internal const val KEPT_IN_TURN = "unconfirmed:turn:"
/** On a live tool card: how many word segments began after it. Counted from the end, so a saved front of the turn dropping out of the live lists never moves it. */
internal const val WORDS_AFTER = "_words_after"

/**
 * The held prompt row of the running turn, or "" while this phone's own send is not a held row yet (the last held question is then an older turn's).
 * A turn this phone did not send is the last held question's.
 */
internal fun runningPromptRow(chat: ChatState): String {
    fun held(id: String) = (id.toLongOrNull() ?: 0L) > 0 && chat.messages.any { it.id == id && it.role == "user" }
    val own = chat.raw.string("_display_submission_id").takeIf(String::isNotBlank)?.let { id -> chat.queued.firstOrNull { it.submissionId == id } }
    if (own != null) return own.rowId.takeIf(::held).orEmpty()
    return chat.messages.lastOrNull { it.role == "user" && it.displayKind != "steer" && !isSteeringText(it.text) }?.id?.takeIf { (it.toLongOrNull() ?: 0L) > 0 }.orEmpty()
}

/**
 * The row that begins the turn a kept copy belongs to. This phone's own send is its held row; a turn it did not send is the newest question the rows hold. Blank while its own
 * question is not a held row yet ([keptTurnPrompt] then finds it by its place after [lastPromptRow]).
 */
internal fun keptPromptRow(chat: ChatState): String {
    if (ownSend(chat) != null) return runningPromptRow(chat)
    // The rows the chat holds say it; the held messages stand in where a chat has no rows of its own.
    return lastPromptRow(chat.raw.rows("messages")).ifBlank { runningPromptRow(chat) }
}

/** The id of the newest question in [rows] (a steer or Hermes' own note is not one), or "". */
internal fun lastPromptRow(rows: List<JSONObject>): String =
    rows.lastOrNull(::isPromptRow)?.let { it.string("row_id", it.string("_row_id", it.string("id"))) }.orEmpty()

/**
 * The question a kept copy's turn begins with, by ids: the row it was bound to, or, for a copy kept while its own question was not a held row yet (`_after_prompt`: the newest question
 * the phone held then), the first question that comes after that one. "" while that question is not in [rows] yet.
 */
internal fun keptTurnPrompt(row: JSONObject, rows: List<JSONObject>): String {
    row.string("_history_user_row_id").takeIf(String::isNotBlank)?.let { return it }
    if (!row.has("_after_prompt")) return ""
    val after = row.string("_after_prompt")
    val start = if (after.isBlank()) 0 else rows.indexOfFirst { it.string("row_id", it.string("_row_id", it.string("id"))) == after }.let { if (it < 0) return "" else it + 1 }
    return rows.drop(start).firstOrNull(::isPromptRow)?.let { it.string("row_id", it.string("_row_id", it.string("id"))) }.orEmpty()
}

/** The rows of the turn whose prompt is the held row [prompt]: after it, up to the next question (a steer continues the turn); null when [rows] do not hold it. */
internal fun turnRowsAfter(rows: List<JSONObject>, prompt: String): List<JSONObject>? {
    if (prompt.isBlank()) return null
    val at = rows.indexOfFirst { it.string("role") == "user" && (it.string("row_id", it.string("_row_id", it.string("id"))) == prompt) }
    if (at < 0) return null
    return rows.drop(at + 1).takeWhile { !(it.string("role") == "user" && it.string("display_kind") != "steer" && !isSteeringText(messageContentText("user", historyRowContent(it)))) }
}

/** How many model messages with words Hermes holds in a turn's [rows]: it saves one per word segment the model said (Hermes' own notes never count). */
internal fun savedWordRows(rows: List<JSONObject>): Int = savedWordMessages(rows).size

/** The model messages with words in a turn's [rows], in order: segment n's own message is the n-th. */
internal fun savedWordMessages(rows: List<JSONObject>): List<JSONObject> =
    rows.filter { it.string("role") == "assistant" && it.string("display_kind").isBlank() && messageContentText("assistant", historyRowContent(it)).isNotBlank() }

/** The place in a turn's [rows] of the words Hermes saved with (or just before) the tool call [callId]: its model messages with words up to that call; 0 when not held. */
internal fun savedPlaceOfCall(rows: List<JSONObject>, callId: String): Int {
    val at = if (callId.isBlank()) -1 else rows.indexOfFirst { row -> row.string("role") == "assistant" && row.rows("tool_calls").any { toolIdentity(it) == callId } }
    return if (at < 0) 0 else savedWordRows(rows.take(at + 1))
}

/** The place in the turn of each sealed segment, one per sealed text; 0 where it is not known (a live frame of an older build). */
internal fun ChatState.sealedPlaces(): List<Int> = List(liveSealed.size) { liveSealedAt.getOrElse(it) { 0 } }

/** This phone's own send in flight (its queue entry), or null: a turn it did not send (another device, a hand-over) has none. */
internal fun ownSend(chat: ChatState): QueuedMessage? =
    chat.raw.string("_display_submission_id").takeIf(String::isNotBlank)?.let { id -> chat.queued.firstOrNull { it.submissionId == id } }

/** A row that begins a turn: a question, not a steer or Hermes' own "[System: ...]" note. */
private fun isPromptRow(row: JSONObject): Boolean =
    row.string("role") == "user" && row.string("display_kind") != "steer" && !isSteeringText(messageContentText("user", historyRowContent(row)))

/**
 * A turn this phone did not send can start before its question is a held row; the words then count the last held turn's saved messages ([ChatState.liveBase]) into their places.
 * When the question arrives (the newest question in [rows] is another row than the one counted against), that held part comes off every place, so the places are the turn's own again.
 */
internal fun rebasedForTurn(chat: ChatState, rows: List<JSONObject>): ChatState {
    if (chat.liveBase <= 0 || ownSend(chat) != null) return chat
    val now = rows.lastOrNull(::isPromptRow)?.let { it.string("row_id", it.string("_row_id", it.string("id"))) }.orEmpty()
    if (now.isBlank() || now == chat.liveBasePrompt) return chat
    return chat.copy(liveOrdinal = if (chat.liveOrdinal > 0) maxOf(1, chat.liveOrdinal - chat.liveBase) else 0,
        liveSealedAt = chat.liveSealedAt.map { if (it > 0) maxOf(1, it - chat.liveBase) else 0 }, liveBase = 0, liveBasePrompt = now)
}

/**
 * The saved rows of the turn the live stream belongs to: the turn of the held prompt, when it is the newest turn [rows] hold. Null when the phone cannot tell which turn that is
 * (its own send is not a held row yet, or a newer question than the one it holds is already saved): nothing is then judged saved.
 */
internal fun runningTurnRows(chat: ChatState, rows: List<JSONObject>): List<JSONObject>? {
    // A turn this phone did not send is the newest turn the rows hold, whether or not the phone's held messages have caught up with it.
    if (ownSend(chat) == null) return rows.indexOfLast(::isPromptRow).takeIf { it >= 0 }?.let { rows.drop(it + 1) }
    val prompt = runningPromptRow(chat)
    val turn = turnRowsAfter(rows, prompt) ?: return null
    val at = rows.indexOfFirst { it.string("role") == "user" && it.string("row_id", it.string("_row_id", it.string("id"))) == prompt }
    return turn.takeIf { at + 1 + it.size == rows.size }
}

/**
 * The sealed [words] (each with its place in the turn) Hermes' saved [turn] rows do not hold yet. Hermes saves one model message with words per segment, in order, so a segment of
 * place n is held once the turn has n of them: judged by the place, never by the words, so words Hermes saved differently (think tags stripped, a rewrite) never show twice.
 * A segment of unknown place (0) stays until the stream ends. Only the newest [MAX_SEALED_SEGMENTS] are kept.
 */
internal fun sealedNotSaved(words: List<String>, places: List<Int>, turn: List<JSONObject>?): Pair<List<String>, List<Int>> {
    val saved = turn?.let(::savedWordRows) ?: 0
    val kept = words.indices.filter { words[it].isNotBlank() && places[it] !in 1..saved }.takeLast(MAX_SEALED_SEGMENTS)
    return kept.map { words[it] } to kept.map { places[it] }
}

/**
 * The closed live segment [text], of place [place], is held by Hermes' saved [turn] rows: the turn's [place]-th model message with words is saved (by place, never by its words).
 * A message that names tool calls is still told by them ([historyOwnsLiveBoundary]): when it names calls other than the ones this segment closed with, it is another message.
 */
internal fun liveWordsSaved(text: String, place: Int, boundary: LiveAssistantBoundary, turn: List<JSONObject>?): Boolean {
    if (!boundary.closed || text.isBlank() || place <= 0 || turn == null) return false
    val calls = savedWordMessages(turn).getOrNull(place - 1)?.rows("tool_calls") ?: return false
    return calls.isEmpty() || calls.any { toolIdentity(it) in boundary.toolIds }
}

/**
 * The chat when [text] starts a new word segment (after a tool step, or the first words of a segment): every tool card on screen gets one more segment after it,
 * and the segment takes its place in the turn. Hermes cannot hold these words yet, so every model message with words it holds for the turn came before them.
 */
internal fun wordsBegan(s0: ChatState, text: String, previousClosed: Boolean): ChatState {
    if (text.isBlank() || !(previousClosed || s0.liveText.isBlank())) return s0
    val rows = s0.raw.rows("messages")
    val s = rebasedForTurn(s0, rows)
    val held = runningTurnRows(s, rows)?.let(::savedWordRows) ?: 0
    val first = s.liveOrdinal == 0
    val prompt = rows.lastOrNull(::isPromptRow)?.let { it.string("row_id", it.string("_row_id", it.string("id"))) }.orEmpty()
    return s.copy(liveOrdinal = maxOf(s.liveOrdinal + 1, held + 1), liveBase = if (first) held else s.liveBase, liveBasePrompt = if (first) prompt else s.liveBasePrompt, liveTools = if (s.liveTools.isEmpty()) s.liveTools else s.liveTools.map { it.copy().put(WORDS_AFTER, it.optInt(WORDS_AFTER) + 1) })
}

/**
 * The words of a reply a reset or an unconfirmed end cut, kept with the tool steps between them in the order said, until Hermes' saved rows hold them.
 * One row per word segment (sealed ones first, then the live one), each with the tool cards said after it; cards said before any words go with the first.
 * Kept rows have no live stream, tool capability or native message identity. Reasoning or tool steps alone are never kept: a reply that never came leaves no bubble.
 */
internal fun retainUnconfirmedReply(chat: ChatState): ChatState {
    val sealed = chat.liveSealed.zip(chat.sealedPlaces()).filter { it.first.isNotBlank() }
    val segments = sealed.map { it.first } + listOfNotNull(chat.liveText.takeIf(String::isNotBlank))
    if (segments.isEmpty()) return chat
    // The prompt row of this turn (a steer is not a prompt), so the copy is judged against its own turn's saved rows and sits with that turn, never below a later one.
    // While this phone's own question is not a held row yet, the copy names the newest question it held (`_after_prompt`) and finds its own by ids when it arrives.
    val prompt = keptPromptRow(chat)
    val afterPrompt = lastPromptRow(chat.raw.rows("messages"))
    // Each kept segment's place in the turn: the last one is the stream's latest; the ones before it follow on. Unknown (an older live frame) keeps the words-based rule.
    val exact = sealed.isNotEmpty() && sealed.all { it.second > 0 }
    val places = if (exact) sealed.map { it.second } + listOfNotNull(chat.liveOrdinal.takeIf { chat.liveText.isNotBlank() }) else emptyList()
    // A copy this build keeps always has a place, so the words fallback can only meet one with none (an older live frame).
    val placed = if (exact) places.size == segments.size && places.all { it > 0 } && places.zipWithNext().all { (a, b) -> a < b } else chat.liveOrdinal >= segments.size
    val stem = "${chat.sessionId}:${chat.streamId}:${chat.raw.string("replay_epoch")}:${chat.raw.string("_display_submission_id")}:${chat.raw.optLong("execution_generation")}".digest() + ":"
    val key = (if (placed) KEPT_IN_TURN else "unconfirmed:") + stem
    val saved = historyToolIdentities(chat.raw.rows("messages"))
    val after = chat.liveTools.filter { toolIdentity(it).isBlank() || toolIdentity(it) !in saved }.groupBy { (segments.lastIndex - it.optInt(WORDS_AFTER)).coerceAtLeast(-1) }
    fun cards(at: Int) = JSONArray(after[at].orEmpty().map { it.copy().apply { remove(WORDS_AFTER) } })
    val rows = segments.mapIndexed { j, words ->
        json("id" to "$key$j", "text" to words, "reasoning" to if (j == segments.lastIndex) chat.liveReasoning else "", "role" to "assistant",
            "session_id" to chat.sessionId, "actionable" to false, "display_kind" to "unconfirmed_reply", "_history_user_row_id" to prompt, "_after_prompt" to afterPrompt, "_segment" to if (!placed) 0 else if (exact) places[j] else chat.liveOrdinal - segments.lastIndex + j,
            "live_tools_before" to if (j == 0) cards(-1) else JSONArray(), "live_tools" to cards(j),
            "submission_id" to chat.raw.string("_display_submission_id"), "admission_id" to chat.raw.string("_display_admission_id"))
    }
    return chat.copy(raw = chat.raw.copy().put(UNCONFIRMED_REPLIES,
        // Judged at once against the rows the phone holds: a reply whose saved copy is already here is not kept twice (an end Hermes' receipt did not prove).
        JSONArray(chat.raw.rows(UNCONFIRMED_REPLIES).filterNot { it.string("id").removePrefix(KEPT_IN_TURN).removePrefix("unconfirmed:").startsWith(stem) } +
            keptRowsNotSaved(chat.raw.rows("messages"), rows))))
}

/**
 * The kept copies Hermes' saved [rows] do not hold yet, judged by Hermes' ids and the turn's order, never by words. A kept copy of the turn's n-th word segment ([KEPT_IN_TURN],
 * `_segment`) goes once Hermes holds n model messages with words after the turn's prompt (it saves one per segment, in order); its tool cards go once Hermes saved their calls.
 * Kept rows of other kinds, and rows whose prompt the history does not hold, pass unchanged.
 */
internal fun keptRowsNotSaved(rows: List<JSONObject>, kept: List<JSONObject>): List<JSONObject> = kept.mapNotNull { row ->
    val ordinal = row.optInt("_segment")
    val turn = if (!row.string("id").startsWith(KEPT_IN_TURN) || ordinal <= 0) null else turnRowsAfter(rows, keptTurnPrompt(row, rows))
    when {
        turn == null -> row
        savedWordRows(turn) >= ordinal -> null
        else -> {
            val saved = historyToolIdentities(turn)
            fun unsaved(key: String) = row.rows(key).filterNot { toolIdentity(it).isNotBlank() && toolIdentity(it) in saved }
            if (unsaved("live_tools_before").size == row.rows("live_tools_before").size && unsaved("live_tools").size == row.rows("live_tools").size) row
            else row.copy().put("live_tools_before", JSONArray(unsaved("live_tools_before"))).put("live_tools", JSONArray(unsaved("live_tools")))
        }
    }
}

/** Local placement uses the captured send boundary, never content matching or a global timestamp sort. */
/**
 * R2C (owner, 22 Nov): whether [row] is a saved row above the loaded window (a chat reads only its newest rows). Hermes' row ids grow with time, so an id
 * below every loaded one is older than all of them; a row merely missing among the loaded ones (a read that raced its save) is not.
 */
internal fun rowAboveWindow(row: String?, messages: List<Message>): Boolean {
    val id = row?.toLongOrNull()?.takeIf { it > 0 } ?: return false
    val oldest = messages.mapNotNull { it.id.toLongOrNull()?.takeIf { n -> n > 0 } }.minOrNull() ?: return false
    return id < oldest
}
/** R2C: the finished copies a history render retires: Hermes holds their row above the loaded window, where their turn can never be proven again. */
internal fun copiesAboveWindow(queued: List<QueuedMessage>, messages: List<Message>): List<QueuedMessage> =
    queued.filter { it.kind == "queue" && it.state == "settled" && rowAboveWindow(it.rowId, messages) }
internal fun chatTimelineMessages(messages: List<Message>, queued: List<QueuedMessage>): List<Message> {
    // A queued message waits in the box above the composer, and so does one Hermes did not take ("Not sent"): neither is a chat bubble. A copy whose row
    // is above the loaded window is that history row, never a bubble (owner, 22 Nov: a 17 Nov "done" drew below today's rows).
    // P1-3: a copy whose row Hermes stamped with its submission id is that row the moment the row is drawn, never beside it until the copy is paired.
    val stamped = messages.filter { it.role == "user" && it.submission.isNotBlank() }.map { it.submission }.toSet()
    val local = queued.filter { !it.inBox() && !rowAboveWindow(it.rowId, messages) && (it.rowId.isBlank() || messages.none { row -> row.id == it.rowId && row.role == "user" }) &&
        !(it.kind == "queue" && it.submissionId in stamped) }
    val placed = local.filter { it.afterRowId != null }
    fun bubble(item: QueuedMessage) = Message("queued:${item.id}", "user", item.text, attachments = item.attachments,
        recordedAt = item.createdAt, displayKind = "local_input")
    val anchored = buildList {
        placed.filter { it.afterRowId == "" }.forEach { add(bubble(it)) }
        for (message in messages) {
            add(message)
            placed.filter { it.afterRowId == message.id }.forEach { add(bubble(it)) }
        }
    }
    val fallback = local.filter { it !in placed || it.afterRowId != "" && messages.none { row -> row.id == it.afterRowId } }
    // R2C: sent after a row above the loaded window, so older than every loaded row: it comes first, never after newer ones.
    val early = fallback.filter { rowAboveWindow(it.afterRowId, messages) }
    val clearClock = anchored.all { it.recordedAt > 0 } && anchored.zipWithNext().all { (left, right) -> left.recordedAt <= right.recordedAt }
    val timed = fallback.filter { it !in early && it.createdAt > 0 && clearClock && anchored.none { row -> row.recordedAt == it.createdAt } }
    return early.sortedBy { it.createdAt }.map(::bubble) + interleaveCallMessages(anchored, timed.sortedBy { it.createdAt }.map(::bubble)) +
        fallback.filter { it !in timed && it !in early }.map { bubble(it).copy(displayKind = "local_input_unresolved") }
}

/** How many of the newest rows a chat-list preview reads, at most how many bytes, and for how many of the top chats. */
internal const val PREVIEW_READ_WINDOW = 8
internal const val PREVIEW_READ_MAX_BYTES = 1024 * 1024
internal const val PREVIEW_PREFETCH_ROWS = 20
private const val PREVIEW_SKEW_MS = 2_000L
private val previewSessionId = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")

/**
 * The top [limit] chats of [profile] whose list line needs a read: no stored line yet, or a line older than the chat's activity.
 * A chat with no messages has nothing to read; [readAt] gives the activity this run already read a chat at, so it is read once per change.
 */
internal fun previewPrefetchTargets(rows: List<JSONObject>, glances: dev.hermes.android.ui.ChatGlances, profile: String, limit: Int = PREVIEW_PREFETCH_ROWS, readAt: (String) -> Long?): List<JSONObject> =
    rows.asSequence().filter { it.string("profile", profile) == profile }.take(limit).filter { row ->
        val id = sessionRowId(row)
        val activity = dev.hermes.android.ui.rowActivityMillis(row)
        val glance = glances.rows["$profile:$id"]
        id.matches(previewSessionId) && row.optInt("message_count", 1) > 0 && readAt(id) != activity &&
            (glance == null || glance.preview.isBlank() || activity > glance.at + PREVIEW_SKEW_MS)
    }.toList()

/** [glances] with the newest line read for a chat; a blank line, or one older than the line already stored, changes nothing. Read marks stay as they are. */
internal fun withPrefetchedPreview(glances: dev.hermes.android.ui.ChatGlances, profile: String, id: String, line: String, role: String, at: Long): dev.hermes.android.ui.ChatGlances {
    val old = glances.rows["$profile:$id"]
    if (line.isBlank() || old != null && old.preview.isNotBlank() && old.at > at) return glances
    return glances.viewed(profile, id, line, role, at, seen = 0L)
}
// The send receipt holds the draft as JSON inside JSON, so it is the largest local checkpoint of the attachments.

/**
 * Whether a queued message that failed with [error] waits for the owner instead of being sent again by itself. A file Hermes refused stays refused (each try
 * would stage the files that did pass once more, and Hermes keeps no way to take them back); a file the plugin stalled on three times, each in smaller pieces,
 * would only stall again (CHAT-121 follow-up 8); a message whose model changed needs its model picked.
 */
/**
 * Chat join: why a failed queued send waits for the owner's Send (TURN-121's one saved hold), or "" for none. A file that could not get through is
 * not held: CHAT-121's fileNotSent already keeps it from every drain and the outbox, and its row says "Not sent" with Send, so the chat says it once.
 */
internal fun queuedHoldReason(e: Throwable): String = if (e !is AttachmentStalled && waitsForOwner(e)) e.message.orEmpty() else ""
internal fun waitsForOwner(error: Throwable): Boolean =
    error is AttachmentRefused || error is AttachmentStalled || error is NativeTurnNotSent && error.message == "The model changed. Pick this message's model to send it."

/**
 * The first chat's create with its retry identity. A [kept] attempt (an earlier create whose answer was lost) is sent again by [create], so Hermes
 * returns the chat that create made. When Hermes refuses that id ([beaconOpeningRetryRefused], the same rule as Beacon's chat), it is spent: [renew] saves a new id first, and
 * [create] runs once more with it. A failure of the new id keeps it for the next try, so an open never makes more than two creates and a spent id
 * never blocks the first chat for good (CHAT-121 follow-up 4).
 */
internal suspend fun createWithRetryIdentity(kept: Boolean, renew: suspend () -> Unit, create: suspend () -> JSONObject): JSONObject {
    try { return create() }
    catch (e: Exception) { if (!kept || e is CancellationException || !beaconOpeningRetryRefused(e)) throw e }
    renew()
    return create()
}

/**
 * Chat join (TURN follow-up 4 with WEDGE follow-up 3): a stream left on a cut turn, released when the lost reply's choice comes up, lets go of its live
 * words the way TURN releases an unproven end: they are kept ([retainUnconfirmedReply]) and the live fields and their place reset; never dropped.
 * REVIEW-JOIN-121 P2-3: so does a live copy no stream is bound to (the "lost" answer to its reconcile names no stream), or it stays drawn as Working.
 */
internal fun releaseCutStream(chat: ChatState, stream: String): ChatState =
    if ((stream.isNotBlank() && chat.streamId == stream) || liveCopyUnbound(chat))
        retainUnconfirmedReply(chat).copy(streamId = "", liveText = "", liveReasoning = "", liveTools = emptyList(), liveSealed = emptyList(), liveSealedAt = emptyList(),
            liveBase = 0, liveBasePrompt = "", liveOrdinal = 0)
    else chat
internal fun attachmentFitsLocalCheckpoint(send: PendingSend): Boolean =
    send.encode().toByteArray().size.toLong() + APP_CIPHER_OVERHEAD <= LOCAL_CHECKPOINT_LIMIT_BYTES
/** rules24 (b): a live copy (words, reasoning, steps) no stream is bound to. The chat would draw it with Working beside an idle composer: it must not stay. */
internal fun liveCopyUnbound(chat: ChatState): Boolean = chat.streamId.isBlank() &&
    (chat.liveText.isNotBlank() || chat.liveReasoning.isNotBlank() || chat.liveTools.isNotEmpty() || chat.liveSealed.isNotEmpty())
/** rules24 (b): the saved chat holds the end of its last turn: Hermes saved a reply for it, not only its own note on a failed or cut turn. */
internal fun savedTurnHoldsReply(rows: List<JSONObject>): Boolean = runningTurnMessages(rows).any {
    it.string("role") == "assistant" && it.string("display_kind") != "failed_turn" && messageContentText("assistant", historyRowContent(it)).isNotBlank()
}
/**
 * rules24 (b): whether the idle reconcile's release of [chat]'s unbound live copy counts the turn's reply as saved. REVIEW-JOIN-121 P2-2: a placed copy is
 * retired by its place, as TURN's own end does (words the saved chat does not hold at that place stay as a kept copy); only an unplaced one goes when the
 * saved turn holds a reply (it would show twice).
 */
internal fun unboundReleaseConfirmed(chat: ChatState, savedHoldsReply: Boolean): Boolean =
    if (chat.liveOrdinal > 0 || chat.liveSealed.isNotEmpty()) false else savedHoldsReply
/**
 * Sign-out, account change and a phone removed on the computer (Root, 18 Nov): the account's saved chats leave the phone, with its screens. For each of
 * [profiles]: the transcripts of the chats it lists or last opened and the rows that name them (the chat that was open, the Beacon chat, the new-chat shell,
 * the chat settings), then its chat glances and list. Drafts stay: they are the person's own unsent words. The view model's sweep and the push side's
 * (REVIEW-JOIN-121 P3-6: no view model may be alive) both run this one. [found] keeps, per profile, the chats a sweep found: sign-out sweeps twice (a write
 * that was already running can land after the first). The account row naming the profiles goes only when it was [recorded] (read) and every list went.
 */
internal suspend fun forgetAccountChats(cache: ProjectionCache, account: Scope, profiles: List<Scope>, removal: DeviceRemovalAuthority?, chats: Boolean = true,
    recorded: Boolean = true, found: MutableMap<String, MutableSet<String>> = mutableMapOf()) {
    suspend fun forget(scope: Scope, kind: String) { if (removal == null) cache.delete(scope, kind) else cache.deleteIfCurrent(scope, kind, removal) }
    /** One failed delete never keeps the others; what cannot go expires after two days. */
    suspend fun quietly(step: suspend () -> Unit): Boolean = try { step(); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    var wiped = recorded
    for (profile in profiles) {
        if (chats) {
            val listed = try {
                savedChatIds(cache.read(profile, "conversation-index"), listOf(BEACON_SESSION_CACHE_KIND, LEGACY_BEACON_SESSION_CACHE_KIND, "selected-session", "new-chat-shell").map { cache.read(profile, it) })
            } catch (e: CancellationException) { throw e } catch (_: Exception) { emptySet() }
            for (id in found.getOrPut(profile.key) { linkedSetOf() }.also { it += listed }.toList()) {
                quietly { cache.deleteUnprotected(profile.session(id), removal) }
                for (kind in listOf("client-created-shell", "side-question")) quietly { forget(profile.session(id), kind) }
            }
            quietly { cache.deleteUnprotected(profile, removal) }
            for (kind in listOf("session", "selected-session", "profile-settings", "new-chat-shell", "new-chat-attempt", "chat-preferences", "read-invalidated-at", "beacon-opening", LEGACY_BEACON_OPENING_CACHE_KIND, BEACON_SESSION_CACHE_KIND, LEGACY_BEACON_SESSION_CACHE_KIND))
                quietly { forget(profile, kind) }
        }
        // A failed list delete keeps the account row, so the next sweep finds what is left.
        for (kind in listOf("chat-glances", "conversation-index")) if (!quietly { forget(profile, kind) }) wiped = false
    }
    if (chats) quietly { forget(account, "thread-marks") }
    if (wiped) quietly { forget(account, GLANCE_PROFILES_KIND) }
}
/** REVIEW-JOIN-121 P3-6: Hermes removed this phone, and no view model may be alive: the push side forgets the account's chats and received files, as forgetScreens does. */
internal suspend fun forgetRemovedAccountData(app: BeaconApplication, scope: Scope, removal: DeviceRemovalAuthority) {
    val account = scope.copy(profile = "", session = "")
    val recorded = try { decodeGlanceProfiles(app.cache.read(account, GLANCE_PROFILES_KIND)) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    try { forgetAccountChats(app.cache, account, glanceProfilesToForget(scope, emptyList(), emptyList(), recorded.orEmpty()), removal, recorded = recorded != null) }
    finally { ReceivedFiles.store(app).forgetAccount(scope) }
}
/** The lines a followed stream shows while it cannot reach Hermes (REVIEW-JOIN-121 P3-1). */
private val LINK_WAIT_LINES = setOf("Reconnecting…", "Couldn't check the reply · your messages are kept")
/** UX-121 P3-17: the lines that are the only word on a cut or lost reply are read at body size, not as a small status. */
internal fun lostReplyLine(status: String): Boolean = status in LINK_WAIT_LINES || isRestartLine(status)
internal fun releaseTerminalChat(chat: ChatState, stream: String, replyConfirmed: Boolean, terminalStatus: String, submission: String? = null): ChatState {
    // The turn's own end also releases what it painted after its stream id went blank (rules24 (b)); a chat that follows another stream keeps its own.
    if (chat.streamId != stream && !liveCopyUnbound(chat) || submission != null && chat.raw.string("_display_submission_id") != submission) return chat
    return if (replyConfirmed) chat.copy(streamId = "", liveText = "", liveReasoning = "", liveTools = emptyList(), liveSealed = emptyList(), liveSealedAt = emptyList(), liveBase = 0, liveBasePrompt = "", liveOrdinal = 0, status = terminalStatus)
    else retainUnconfirmedReply(chat).copy(streamId = "", liveText = "", liveReasoning = "", liveTools = emptyList(), liveSealed = emptyList(), liveSealedAt = emptyList(), liveBase = 0, liveBasePrompt = "", liveOrdinal = 0)
}

/** The chat without the live segment and tool cards the saved [rows] already hold; the turn itself is not ended here. */
internal fun withoutSavedLive(chat0: ChatState, rows: List<JSONObject>, boundary: LiveAssistantBoundary): ChatState {
    val chat = rebasedForTurn(chat0, rows)
    val turn = runningTurnMessages(rows)
    val owned = historyToolIdentities(turn)
    val mine = runningTurnRows(chat, rows)
    val wordsSaved = liveWordsSaved(chat.liveText, chat.liveOrdinal, boundary, mine)
    val sealed = sealedNotSaved(chat.liveSealed, chat.sealedPlaces(), mine)
    return chat.copy(liveText = if (wordsSaved) "" else chat.liveText, liveReasoning = if (wordsSaved) "" else chat.liveReasoning,
        liveTools = chat.liveTools.filterNot { toolIdentity(it).isNotBlank() && toolIdentity(it) in owned },
        liveSealed = sealed.first, liveSealedAt = sealed.second)
}

/** What the live view keeps once the saved [rows] are known: the segment they hold is dropped, and the turn is over when they hold its end. */
internal data class LiveSettlement(val streamEnded: Boolean, val text: String, val reasoning: String, val tools: List<JSONObject>, val sealed: List<String> = emptyList(), val sealedAt: List<Int> = emptyList(), val ordinal: Int = 0, val base: Int = 0, val basePrompt: String = "")
internal fun settleLive(old: ChatState, rows: List<JSONObject>, active: String?, boundary: LiveAssistantBoundary, finalRow: String): LiveSettlement {
    val turn = runningTurnMessages(rows)
    val historyOwnsLive = (finalRow.isNotBlank() && rows.any { it.string("row_id", it.string("_row_id")) == finalRow }) ||
        historyOwnsLiveBoundary(turn, old.liveTools, boundary.closed, boundary.toolIds)
    val streamEnded = historyOwnsLive || (!active.isNullOrBlank() && active != old.streamId)
    val kept = withoutSavedLive(old, rows, boundary)
    return LiveSettlement(streamEnded,
        text = if (streamEnded) "" else kept.liveText,
        reasoning = if (streamEnded) "" else kept.liveReasoning,
        tools = if (streamEnded) emptyList() else kept.liveTools,
        sealed = if (streamEnded) emptyList() else kept.liveSealed, sealedAt = if (streamEnded) emptyList() else kept.liveSealedAt,
        ordinal = if (streamEnded) 0 else kept.liveOrdinal, base = if (streamEnded) 0 else kept.liveBase, basePrompt = if (streamEnded) "" else kept.liveBasePrompt)
}

/** The chat with its closed live words kept as a sealed segment when a new segment begins; the words the running turn's saved rows already hold are not kept (their row shows them). */
internal fun sealClosedWords(s0: ChatState, previousClosed: Boolean): ChatState {
    if (!previousClosed || s0.liveText.isBlank()) return s0
    val s = rebasedForTurn(s0, s0.raw.rows("messages"))
    val sealed = sealedNotSaved(s.liveSealed + s.liveText, s.sealedPlaces() + s.liveOrdinal, runningTurnRows(s, s.raw.rows("messages")))
    return s.copy(liveSealed = sealed.first, liveSealedAt = sealed.second)
}

/** Hermes' explained waits lead with a status glyph (agent/turn_recovery.py, chat_completion_wait_notice.py, turn_truncation.py); its spinner faces never do. */
internal fun hermesWaitLine(line: String): Boolean = line.firstOrNull() in setOf('⏳', '⏱', '⚠', '↻', '⚙')
/** Hermes' wait line as the owner reads it: its plain first part, without the parenthesised mechanics at its end. */
internal fun plainWaitLine(line: String): String {
    var plain = line.trim()
    while (true) { val next = plain.replace(Regex("""\s*\([^()]*\)\s*$"""), "").trim(); if (next == plain || next.isBlank()) return plain; plain = next }
}

/** The chat after one stream event that moves the live bubble, reasoning or tool cards; null for every other event. [previousClosed] is the boundary before the event, [next] the one it leaves. */
internal fun liveAfterEvent(s: ChatState, type: String, o: JSONObject, previousClosed: Boolean, next: LiveAssistantBoundary): ChatState? = when (type) {
    "state_saved" -> o.optJSONObject("session")?.let { session ->
        val reset = o.optBoolean("replay_reset")
        val kept = if (reset) retainUnconfirmedReply(s) else s
        val projection = mergeChatProjection(kept.raw, session)
        val merged = kept.raw.copy().apply { projection.keys().forEach { put(it, projection.opt(it)) } }
        val rows = projection.rows("messages")
        val active = projection.string("active_stream_id").takeIf(String::isNotBlank)
        val live = if (reset) LiveSettlement(true, "", "", emptyList()) else settleLive(s, rows, active, next, "")
        val ended = live.streamEnded && !passiveReadKeepsTurn(s.streamId, projection.has("active_stream_id"), rows)
        kept.copy(raw = merged, messages = if (projection.has("messages")) rows.mapIndexed { i, row -> Message.from(row, i, s.sessionId) } else s.messages,
            streamId = active ?: if (reset || ended) "" else s.streamId,
            liveText = live.text, liveReasoning = live.reasoning, liveTools = live.tools, liveSealed = live.sealed, liveSealedAt = live.sealedAt, liveBase = live.base, liveBasePrompt = live.basePrompt, liveOrdinal = if (reset || live.streamEnded) 0 else live.ordinal,
            status = if (active == null && lostTurnHeld(projection)) LOST_REPLY_STATUS else renderedChatStatus(s, active, ended))
    }
    "done" -> {
        val projected = o.optJSONObject("session")?.let { session ->
            liveAfterEvent(s, "state_saved", json("session" to session), previousClosed, next)
        } ?: s
        val finalText = messageContentText("assistant", o.opt("text") ?: o.opt("content"))
        val shownText = when {
            // A stopped or failed turn's final text is Hermes' own stop or failure copy ("Operation interrupted: retrying…"), not words of the reply.
            o.string("status") in setOf("interrupted", "error") -> projected.liveText
            o.optBoolean("response_transformed") && finalText.isNotBlank() -> finalText
            o.optBoolean("response_reused") -> projected.liveText
            finalText.isNotBlank() -> finalText
            else -> projected.liveText
        }
        // A final reply that comes whole after a closed segment does not take that segment's words off the screen.
        (if (shownText != projected.liveText) wordsBegan(sealClosedWords(projected, previousClosed), shownText, previousClosed) else projected)
            .copy(liveText = shownText, liveReasoning = o.string("reasoning").ifBlank { projected.liveReasoning }, status = "Finishing")
    }
    // An empty chunk is Hermes' segment boundary, not words: it changes nothing. A new segment after a closed one seals the closed words (they stay on screen until the saved rows hold them).
    "token" -> if (o.string("text").isEmpty()) s else wordsBegan(sealClosedWords(s, previousClosed), o.string("text"), previousClosed).let { b ->
        b.copy(liveText = (if (previousClosed) "" else b.liveText) + o.string("text"), liveReasoning = if (previousClosed) "" else b.liveReasoning, status = "Responding") }
    "reasoning" -> sealClosedWords(s, previousClosed).let { b ->
        b.copy(liveText = if (previousClosed) "" else b.liveText, liveReasoning = (if (previousClosed) "" else b.liveReasoning) + o.string("text"), status = "Thinking") }
    // Hermes' live status line: its explained wait ("⏳ rate limited — resets in ~5m, retrying…") shows while it is current; a spinner face or an empty frame ends it.
    "thinking" -> o.string("text").trim().let { line ->
        when {
            hermesWaitLine(line) -> s.copy(status = plainWaitLine(line))
            line.isNotEmpty() || hermesWaitLine(s.status) -> s.copy(status = "Thinking")
            else -> s
        }
    }
    "tool", "tool_complete" -> {
        val turn = runningTurnMessages(s.raw.rows("messages"))
        // A step Hermes already holds tells the words' true place by its id: a segment shown again (a replay, or commentary sent after its save) did not move the turn on.
        val place = if (type != "tool") 0 else turnRowsAfter(s.raw.rows("messages"), runningPromptRow(s))?.let { savedPlaceOfCall(it, toolIdentity(o)) } ?: 0
        val ordinal = if (place > 0) place else s.liveOrdinal
        val ownsBody = historyOwnsLiveBoundary(turn, emptyList(), next.closed, next.toolIds) || liveWordsSaved(s.liveText, ordinal, next, runningTurnRows(s, s.raw.rows("messages")))
        s.copy(liveText = if (ownsBody) "" else s.liveText, liveReasoning = if (ownsBody) "" else s.liveReasoning, liveOrdinal = ordinal,
            liveTools = if (toolIdentity(o).isNotBlank() && toolIdentity(o) in historyToolIdentities(turn)) s.liveTools else mergeLiveTool(s.liveTools, o),
            status = if (type == "tool") "Using ${dev.hermes.android.ui.toolDisplayName(o)}" else "Working")
    }
    "interim_assistant" -> interimAssistantBody(s.liveText, s.liveReasoning, o).let { body ->
        val turn = runningTurnMessages(s.raw.rows("messages"))
        // Commentary already streamed closes the live segment, which Hermes holds once its place is saved; commentary that comes whole is a new segment, not yet held.
        val owned = historyOwnsLiveBoundary(turn, emptyList(), next.closed, next.toolIds) ||
            body.text == s.liveText && liveWordsSaved(body.text, s.liveOrdinal, next, runningTurnRows(s, s.raw.rows("messages")))
        // Commentary that comes whole (already_streamed false) after a closed segment is a new segment: the closed words stay.
        (if (body.text != s.liveText) (if (owned) sealClosedWords(s, previousClosed) else wordsBegan(sealClosedWords(s, previousClosed), body.text, previousClosed)) else s)
            .copy(liveText = if (owned) "" else body.text, liveReasoning = if (owned) "" else body.reasoning)
    }
    else -> null
}
data class EditorState(val scope: Scope? = null, val path: String = "", val text: TextFieldValue = TextFieldValue(), val serverText: String = "", val metadata: JSONObject = JSONObject(), val loading: Boolean = false, val saving: Boolean = false, val error: String = "", val reviewRequired: Boolean = false) { val dirty get() = text.text != serverText }
data class SideQuestion(val scope: Scope, val question: String, val sessionId: String, val streamId: String, val text: String = "", val status: String = "Responding", val cursor: String = "", val error: String = "")
/** How often a phone that cannot reach Hermes asks again while Beacon is on screen. */
internal const val REACH_CHECK_MS = 5_000L
data class AppState(val origin: String = BuildConfig.DEFAULT_ORIGIN, val authenticated: Boolean = false, val profile: String = "default", val chatProfile: String = "", val owner: String = "", val tab: String = "Chat", val connected: Boolean = false, val loading: Boolean = false, val error: String = "", val notice: String = "", val bootstrap: JSONObject = JSONObject(), val sessions: List<JSONObject> = emptyList(), val query: String = "", val showSessions: Boolean = false, val morePage: String = "", val beaconOpening: Boolean = false, val beaconError: String = "", val newChatOpening: Boolean = false, val newChatError: String = "", val initialChatReady: Boolean = false, val restoringSession: Boolean = true, val botModeEnabled: Boolean = false, val botModeSwitching: Boolean = false, val setupWizard: Boolean = false, val setupComplete: Boolean = false, val setupStep: String = "connect", val setupPluginMessage: String = "", val setupOrigin: String = "", val historyLoading: Boolean = false, val assistantSessionId: String = "", val historyUnreadable: List<String> = emptyList(),
    /** "profile:chat" for the listed chats whose reply Hermes lost in a restart (Beacon's read-only route). */
    val lostChats: Set<String> = emptySet(),
    /** Bot profile to its Bot Chat id, for the bots whose Bot Chat the same route last found holding a lost reply. */
    val lostBotChats: Map<String, String> = emptyMap())
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

/**
 * The per-row attachment receipt is the local record of what this exact user row sent. A native
 * history row may omit its inline image parts after a reopen, so the row's own receipt restores
 * them, deduplicated against any server copy. Entries tagged for another history row never merge.
 */
internal fun mergedRowAttachments(row: JSONObject, receipt: JSONObject, scopeKey: String): JSONArray {
    val rowId = row.string("row_id", row.string("_row_id"))
    val server = row.rows("attachments")
    val cached = receipt.rows("attachments").filter { local ->
        (!local.has("scope_key") || local.string("scope_key") == scopeKey) &&
            (!local.has("history_row_id") || local.string("history_row_id") == rowId)
    }
    val merged = server.map { attachment ->
        cached.singleOrNull { attachment.string("path").isNotBlank() && it.string("path") == attachment.string("path") }?.copy()?.apply {
            val name = string("name", attachment.string("name"))
            attachment.keys().forEach { key -> put(key, attachment.opt(key)) }
            put("name", name)
        } ?: attachment
    }
    val extra = cached.filter { local ->
        server.none { it.string("path").isNotBlank() && it.string("path") == local.string("path") } &&
            server.none { it.has("history_image_index") && local.has("history_image_index") && it.string("history_image_index") == local.string("history_image_index") }
    }
    return JSONArray(merged + extra)
}

/**
 * The open chat after a settled send's display attachments join its persisted user row. The attachment cache
 * saves on IO, so the owner may switch chats after that save starts; [current] is re-evaluated here, at the
 * mutation itself, so a coincident row id in the newly opened chat never receives this chat's media.
 */
internal fun boundAttachmentChat(state: ChatState, current: () -> Boolean, linked: String, display: JSONArray, shown: List<JSONObject>): ChatState {
    if (!current()) return state
    val rawRows = state.raw.rows("messages").map { row ->
        if (row.string("row_id", row.string("_row_id")) == linked && row.string("role") == "user" && row.optJSONArray("attachments")?.length() == 0)
            row.copy().put("attachments", display) else row
    }
    return state.copy(
        messages = state.messages.map { message ->
            if (message.id == linked && message.role == "user" && message.attachments.isEmpty()) message.copy(attachments = shown) else message
        },
        raw = state.raw.copy().put("messages", JSONArray(rawRows)))
}

/**
 * The sent turn's own attachments are the receipt for this exact user row. Native file uploads return
 * attachment_receipts; an image attached directly to the turn may only be named by the draft. Both
 * accepted paths (started and queued) must keep the same local record so a reopen does not lose it.
 */
internal fun sentAttachmentReceipts(result: JSONObject, draft: Draft): JSONArray? {
    result.optJSONArray("attachment_receipts")?.takeIf { it.length() > 0 }?.let { return it }
    return draft.attachments.takeIf { it.isNotEmpty() }?.let { JSONArray(it) }
}

/**
 * The receipts with a small picture of each photo the owner picked, kept with the message (scoped by the account and profile cache it is written to), so the
 * sent bubble shows the photo on this phone without asking Hermes for it. A receipt pairs with its picked file by position, else by name; the full-size
 * data of the picked file never goes into the cache. [thumbnail] makes the small picture (null: none).
 */
internal fun withKeptThumbnails(receipts: JSONArray, picked: List<JSONObject>, thumbnail: (JSONObject) -> String?): JSONArray {
    val list = (0 until receipts.length()).mapNotNull { receipts.optJSONObject(it) }
    return JSONArray(list.mapIndexed { index, receipt ->
        val source = picked.getOrNull(index)?.takeIf { picked.size == list.size } ?: picked.firstOrNull { it.string("name").isNotBlank() && it.string("name") == receipt.string("name") }
        receipt.copy().apply {
            remove("staged_base64")
            source?.let(thumbnail)?.takeIf { it.isNotBlank() }?.let { put("thumb_base64", it) }
        }
    })
}
internal suspend fun keptAttachmentReceipts(result: JSONObject, draft: Draft): JSONArray? =
    sentAttachmentReceipts(result, draft)?.let { receipts -> withContext(Dispatchers.Default) { withKeptThumbnails(receipts, draft.attachments, ::attachmentThumbnail) } }

/**
 * Follow-up 6: the files a send carried wait under the send's own submission id until Hermes' row for that submission is linked.
 * They used to wait under the chat stream (`attachments-pending:<stream>`), which a send whose row never linked left behind for the next
 * linked row of that chat. The cache is scoped per account, profile and chat (Scope.key), and Hermes' row ids are unique inside that.
 */
internal fun pendingAttachmentsKind(submissionId: String) = "attachments-pending:sub:$submissionId"
/** The kind a 1.2.0 app saved under: the chat stream. Read only to move a record that provably belongs to the row being linked; never written. */
internal fun legacyPendingAttachmentsKind(stream: String) = "attachments-pending:$stream"
/** The saved files and display words of one send, with the submission they belong to (a record without it is a 1.2.0 record). */
internal fun attachmentRecord(attachments: JSONArray, displayText: String, submissionId: String): String =
    json("attachments" to attachments, "display_text" to displayText, "submission_id" to submissionId).toString()
/**
 * A saved attachment record may be shown on [row] only when it does not name another submission than the row's: a record that names its send and a row
 * that names its own must agree. A side without a submission id (a record from 1.2.0, a row from a Hermes that does not save it) cannot contradict.
 */
internal fun attachmentRecordFitsRow(record: JSONObject, row: JSONObject): Boolean {
    val mine = record.optString("submission_id"); val theirs = rowSubmissionId(row)
    return mine.isBlank() || theirs.isBlank() || mine == theirs
}
/**
 * The pending record that may be linked to the row [linked] of [item]'s send, or null. The record saved under the send's submission id is the send's own,
 * if the row does not name another submission. A 1.2.0 record (chat stream key) is only moved when its words and files are exactly this send's.
 */
internal fun pendingRecordForRow(own: String?, legacy: String?, item: QueuedMessage, linked: JSONObject?): String? {
    val row = linked ?: JSONObject()
    // A row Hermes stamped with another submission is never this send's, whatever the record says.
    rowSubmissionId(row).let { if (it.isNotBlank() && it != item.submissionId) return null }
    own?.let { raw -> runCatching { JSONObject(raw) }.getOrNull()?.takeIf { it.optString("submission_id") == item.submissionId && attachmentRecordFitsRow(it, row) } }?.let { return own }
    return legacy?.takeIf { raw ->
        runCatching { JSONObject(raw) }.getOrNull()?.let { rec ->
            val names = rec.rows("attachments").map { it.string("name") }
            rec.string("display_text") == item.text && names.isNotEmpty() && names == item.attachments.map { it.string("name") } && attachmentRecordFitsRow(rec, row)
        } == true
    }
}
/** The cache kinds that hold the small pictures kept for the photos of a chat's user rows (`attachments:<row id>`). */
internal fun keptAttachmentKinds(rows: List<JSONObject>): List<String> =
    rows.filter { it.string("role") == "user" }.map { it.string("row_id", it.string("_row_id")) }.filter { id -> (id.toLongOrNull() ?: 0L) > 0 }.map { "attachments:$it" }.distinct()

/** A photo's picked file as a small JPEG (at most 256 px a side), base64; null for anything that is not a readable photo. */
private fun attachmentThumbnail(file: JSONObject): String? {
    if (!dev.hermes.android.ui.imageMimeType(file.string("mime"))) return null
    val encoded = file.string("staged_base64").takeIf { it.isNotBlank() && it.length <= 24 * 1024 * 1024 } ?: return null
    val bytes = runCatching { android.util.Base64.decode(encoded, android.util.Base64.DEFAULT) }.getOrNull() ?: return null
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray().let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
}

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
    private val _settingsProfile = MutableStateFlow("")
    val settingsProfile = _settingsProfile.asStateFlow()
    private val _settingsProfileSelecting = MutableStateFlow(false)
    val settingsProfileSelecting = _settingsProfileSelecting.asStateFlow()
    private val _settingsProfileRestoring = MutableStateFlow(saved.get<String>("settings-edit-profile") != null)
    val settingsProfileRestoring = _settingsProfileRestoring.asStateFlow()
    private val _settingsProfileRestoreError = MutableStateFlow("")
    val settingsProfileRestoreError = _settingsProfileRestoreError.asStateFlow()
    private var settingsOwner: BeaconClient? = null
    private var settingsClient: BeaconClient? = null
    private var settingsEpoch = -1
    private var settingsAuthority = 0L
    private var settingsSelection = 0
    private var settingsNavigation = 0L
    private var settingsSelectionJob: Job? = null
    private var settingsRestoreJob: Job? = null
    val phoneSettings = NativePhoneSettings(viewModelScope, saved, draftChanged = ::checkpointPhoneSettings, screens = screens,
        globalClient = { client }, authorizeProfile = ::authorizeSettingsProfile, profileFailure = ::profileSettingsFailure,
        cachedScope = { if (client == null) cachedOwnerScope else null }) { settingsProfileClient() }
    /** The account the phone restored before its client exists: Skills and Memory show their saved copy under this scope while the app reconnects. */
    internal val settingsRestoredScopeKey: String? get() = if (client == null) cachedOwnerScope?.key else null
    val botMode = NativeBotModeController(viewModelScope, saved, openChat = ::openBotChat, screens = screens, projections = app.cache, restoredScope = ::restoredScope) { client }
    /** The email draft cards of the open chats: each keeps its state while it is scrolled out of view. */
    internal val emailDrafts = dev.hermes.android.ui.EmailDraftStore(viewModelScope)
    /** Makes, describes and deletes profiles for Settings › Profiles and Bot Mode. */
    val profileAdmin = dev.hermes.android.data.NativeProfileAdmin(viewModelScope, { client }, ::profilesChanged)
    /** Beacon's own plugin install from the app; [pluginInstalled] runs when Beacon is ready on this Hermes. */
    internal val pluginInstall = dev.hermes.android.updates.PluginInstallController(viewModelScope, { client }, ::pluginInstalled, ::openAppUpdates, known = { app.updates.state.value.latest })
    /** Settings > Beacon plugin > Repair Beacon: the published plugin put back through Hermes' own routes, never while a call, reply or task runs. */
    internal val pluginRepair = dev.hermes.android.updates.PluginRepairController(viewModelScope, { client }, { app.calls.state.value.active }, ::pluginInstalled, known = { app.updates.state.value.latest })
    val preferences = app.preferences.data.map(AppPreferences::read).stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())
    private val _busySendOverride = MutableStateFlow<String?>(null)
    val busySendOverride = _busySendOverride.asStateFlow()
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
        // The card names the assistant's conversation from this as soon as it exists; a history read copies it only when the read starts, and the drawer's own read is skipped while the last one is fresh.
        if (client?.scope?.copy(profile = preferences.value.assistantProfile) == scope) _ui.update { it.copy(assistantSessionId = id) }
    }
    private fun clearBeaconSessionHandle(scope: Scope) {
        saved.remove<String>(beaconSessionHandleKey(scope))
        saved.remove<String>("$LEGACY_BEACON_SESSION_HANDLE_PREFIX${scope.key}")
    }
    private suspend fun readBeaconCache(scope: Scope): String? = app.cache.readMigrating(scope, BEACON_SESSION_CACHE_KIND, LEGACY_BEACON_SESSION_CACHE_KIND)
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
    /** Only the open chat's id, for screens that must not redraw on every streamed token (the chat list). */
    internal val openSessionId: StateFlow<String> = _chat.map { it.sessionId }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, "")
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
    private var reasoningReadOrder = 0L
    private val appliedReasoningReads = mutableMapOf<String, Long>()
    private val _modelSaving = MutableStateFlow("")
    val modelSaving = _modelSaving.asStateFlow()
    private var modelChange: Job? = null
    private val conversationChanges = mutableMapOf<String, Job>()
    private val _conversationPending = MutableStateFlow<Set<String>>(emptySet())
    val conversationPending = _conversationPending.asStateFlow()
    private val _archiveUndo = MutableStateFlow<Scope?>(null)
    val archiveUndo = _archiveUndo.asStateFlow()
    private val phoneCheckpoints = mutableMapOf<String, Job>()
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
    private val _commandReport = MutableStateFlow<CommandReport?>(null)
    /** A command's long report, open until the person closes it ([closeCommandReport]); a chat or account change drops it ([clearDetails]). */
    internal val commandReport = _commandReport.asStateFlow()
    fun closeCommandReport() { _commandReport.value = null }
    private val _securePrompt = MutableStateFlow<SecurePrompt?>(null)
    internal val securePrompt = _securePrompt.asStateFlow()
    /** Hermes' open secret questions: shown when the person can answer, held (never answered empty) when they are away. */
    private val securePrompts = SecurePromptKeeper(
        answer = { prompt, value -> sendSecurePrompt(prompt, value) },
        notifyHeld = { prompt -> dev.hermes.android.push.SecurePromptNotice.post(app, prompt.method, (prompt.expiresAt - System.nanoTime() / 1_000_000).coerceAtLeast(1_000)) },
        clearNotice = { dev.hermes.android.push.SecurePromptNotice.cancel(app) },
    )
    private val _catalog = MutableStateFlow("")
    val catalog = _catalog.asStateFlow()
    private var sideJob: Job? = null
    private var sideCheckpointJob: Job? = null
    private val liveFrames = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private var editorCheckpoint: Job? = null
    @Volatile var client: BeaconClient? = null
        private set(value) {
            if (value == null || field?.scope?.let { !it.sameAccount(value.scope) } == true) { botMode.clear(); pluginInstall.reset(); pluginRepair.reset(); profileAdmin.reset() }
            if (value != null && field?.scope?.let { !it.sameAccount(value.scope) } == true) {
                normalModeReturn = null
                _ui.update { it.copy(botModeEnabled = false, botModeSwitching = false) }
            }
            val previous = field
            field=value; app.calls.ownerScope=value?.scope
            // S1d (R3 #4): a replaced own client's socket is no one's now; it would hold one of the plugin's 8 for good (S1's budget skips own
            // clients). One a direct send still waits on is closed once its answer is in (ownSendDone). Settings closes its own the same way.
            if (previous != null && previous !== value && previous !== settingsClient && !ownSendsOnTheirWay.containsKey(previous)) previous.closeNative()
        }
    internal var profileSwitchClientFactoryForTest: ((AuthContext) -> BeaconClient)? = null
    @Volatile private var sourceScope: Scope? = null
    private var cachedOwnerScope: Scope? = null
    /** The open chat whose selection a 404 let go while it held no words (REVIEW-FIXCHAT-121 P2): words saved into it make it the selection again. */
    private var forgottenGone: Scope? = null
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
    /** The dedicated native client consuming each scope's current reply. */
    private val streamClients = mutableMapOf<String, BeaconClient>()
    /** The request each registered stream job started with (see [streamFollows]). */
    private val streamStarts = mutableMapOf<String, ScopeSettlement>()
    /** A stream silent this long asks Hermes about its turn, checked this often; fields so a device case can shorten them. */
    @Volatile private var streamSilenceMs = STREAM_QUIET_START_MS
    @Volatile private var streamWatchMs = STREAM_QUIET_CHECK_MS
    private val drafts = mutableMapOf<String, Draft>()
    private val persistedDrafts = mutableMapOf<String, String>()
    private val draftWriteLock = Mutex()
    private var composerRevision = 0L
    private val initialShells = mutableMapOf<String, String>()
    private val clientCreatedSessions = mutableSetOf<String>()
    /** Phone-created chats opened with a title, workspace or model: plain New conversation never reuses them. Persisted as the shell marker's value. */
    private val keptApartShells = mutableSetOf<String>()
    private val registeredAssistantChats = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val initialCreateAttempts = mutableMapOf<String, String>()
    private val newChatJobs = mutableMapOf<String, Job>()
    private val initialCreateError = "Couldn't open the chat. Your draft is kept."
    private val pending = mutableMapOf<String, PendingSend>()
    private val pendingStored = mutableMapOf<String, Pair<PendingSend, String>>()
    private val sentComposerRevision = mutableMapOf<String, Pair<String, Long>>()
    private val queueDrains = mutableMapOf<String, Job>()
    private val lostTurnRecoveries = mutableMapOf<String, Job>()
    /** Chats whose lost reply was already looked at since they were opened and left as it is (waiting for the owner, or Hermes refused): no more passive looks. */
    private val lostTurnLooked = mutableSetOf<String>()
    /** The streams this phone asked to stop ("scope:stream"): only their interrupt is the owner's Stop. */
    private val stopsSentHere = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** Chats whose lone lost reply a look just closed (nothing waited behind it): the settle that follows shows the plain restart line ([showQuietLostClose]). */
    private val quietLostCloses: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val lostReads = mutableMapOf<String, Job>()
    private val loads = mutableMapOf<String, Job>()
    private val loadRequests = mutableMapOf<String, String>()
    private val loadedAt = mutableMapOf<String, Long>()
    /** Reads asked for while the sign-in was still reconnecting; they run once the client exists, so a saved page shown meanwhile is refreshed. */
    private class AskedEarly(val scope: Scope, val path: String, val query: Map<String, String>, val force: Boolean, val quiet: Boolean)
    private val askedEarly = LinkedHashMap<String, AskedEarly>()
    /** The screens of the profiles this run left, by profile scope, so a hop out and back (the assistant's profile) shows them at once. Memory only, never another profile's or account's. */
    private val dataByProfile = dev.hermes.android.data.KeptProfileData()
    private fun forgetKeptData() { askedEarly.clear(); dataByProfile.clear() }
    /** The chats a sign-out has found per profile scope, until the next sign-in. */
    private val chatsToForget = mutableMapOf<String, MutableSet<String>>()
    private fun swapProfileData(left: Scope, entered: Scope): Map<String, JSONObject> = dataByProfile.swap(left.copy(session = "").key, entered.copy(session = "").key, _data.value)
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
    /** Finished turns on which Hermes refused the thread's wanted name for a reason that is not its duplicate answer: the name is dropped after [THREAD_TITLE_FAILED_TURNS] of them. */
    private val threadTitleFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()
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

    /** Beacon was just added to this Hermes: read the plugin again so every screen drops its missing-plugin card, then link this phone as a sign-in does. */
    private fun pluginInstalled() {
        val active = client ?: return
        val c = active.detached()
        val owner = c.scope
        viewModelScope.launch {
            try {
                val optional = c.probeOptionalFeatures()
                if (client?.scope?.sameAccount(owner) != true) return@launch
                val there = optional.string("plugin") in setOf("available", "unauthorized")
                _ui.update { state -> mergeOptionalFeaturesFor(state.bootstrap, owner, optional)?.let { state.copy(bootstrap = it, setupPluginMessage = if (there) "" else state.setupPluginMessage) } ?: state }
                if (there) linkThisPhone(active)
            } catch (e: Exception) { if (e is CancellationException) throw e /* The next app start reads the plugin again. */ }
            finally { c.closeNative() }
        }
    }
    /** "Update Beacon" on a missing-plugin card: the update screen already updates the Hermes plugin, asks the owner to review what changed, and says when Hermes must restart. */
    fun openAppUpdates() { if (_ui.value.authenticated) { tab("More"); more("App updates") } }

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
                if (returned) viewModelScope.launch { if (reconnectOnNetworkReturn(_ui.value)) connect(_ui.value.origin) else { hermesAnsweredAgain(); followMutedReply(); drainOpenQueue(); drainOfflineOutbox() } }
            }
            override fun onLost(network: android.net.Network) { networkValidated = false }
        }
        try { manager.registerDefaultNetworkCallback(callback); networkCallback = callback }
        catch (_: Exception) { /* Reconnect stays a tap if Android refuses the callback. */ }
    }

    private var reachProbe: Job? = null
    /**
     * While Hermes is out of reach, ask it quietly every few seconds (only on screen); its first answer clears "Can't reach Hermes".
     * Nothing else: refreshing the open chat from here raised errors of its own (KeepConnectedUiTest). [now] asks at once (Retry).
     */
    fun checkHermesReach(now: Boolean = false) {
        if (reachProbe?.isActive == true && !now) return
        reachProbe?.cancel()
        reachProbe = viewModelScope.launch {
            var wait = !now
            while (_ui.value.origin in HermesReach.origins.value) {
                if (wait) delay(REACH_CHECK_MS)
                wait = true
                if (!foreground) continue
                val active = client ?: continue
                val c = active.detached()
                try { c.get("/api/status") } catch (e: CancellationException) { throw e } catch (_: Exception) { } finally { runCatching { c.closeNative() } }
            }
        }
    }

    init {
        reconnectWhenNetworkReturns()
        viewModelScope.launch { HermesReach.origins.collect {
            if (_ui.value.origin in it) { hermesWasUnreachable = true; checkHermesReach() }
            else { if (hermesWasUnreachable) { hermesWasUnreachable = false; hermesAnsweredAgain() }; followMutedReply(); drainOpenQueue(); drainOfflineOutbox() }
        } }
        viewModelScope.launch {
            app.push.emailAlerts.collect { scope -> if (scope == sourceScope) client?.let { refreshChatAlerts(scope, it) } }
        }
        viewModelScope.launch { app.push.conversationPosts.collect(::refreshPostedConversation) }
        // Something shared into Beacon from Android's share sheet: the chat the owner chose opens with it in the composer.
        viewModelScope.launch { dev.hermes.android.share.SharedIntake.pending.collect { share -> if (share != null) dev.hermes.android.share.openShared(this@BeaconViewModel, share) } }
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
    /** The phone's monotonic clock (counts sleep) for how long a prompt has been waiting. */
    private fun clockNow() = android.os.SystemClock.elapsedRealtime()
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
            if(client?.scope!=pending.scope) { notice("The chat changed. Start the call again.");return@launch }
            backFromSettings()
            _voiceCallRequest.value=pending
        }
    }
    private val checklistWrites = Mutex()
    fun saveChecklistReading(scope: Scope, documentKey: String, values: Map<String, Boolean>) = viewModelScope.launch {
        try { checklistWrites.withLock { app.cache.write(scope, "reading-checklist:$documentKey", JSONObject(values).toString()) } }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            if (client?.scope == scope.copy(session = "")) notice("Couldn't save the checklist on this phone.")
        }
    }
    private val nativePreferencesLock = Mutex()
    fun nativePreferences(values: JSONObject) = viewModelScope.launch {
        // Theme and color style belong to this phone: they apply signed out, offline or mid-reconnect, never dropped.
        if (values.has("theme") || values.has("skin"))
            app.preferences.edit { prefs -> values.keys().forEach { key -> if (key in setOf("theme", "skin")) prefs[stringPreferencesKey(key)] = values.opt(key)?.toString().orEmpty() } }
        val owner = client?.scope ?: cachedOwnerScope ?: return@launch
        try { nativePreferencesLock.withLock {
        val chat = app.cache.read(owner, "chat-preferences")?.let(::JSONObject) ?: json("render_user_markdown" to true, "send_key" to "enter")
        for (key in setOf("render_user_markdown", "show_thinking", "send_key", "auto_scroll_follow", "animate_background")) if (values.has(key)) {
            if (key != "send_key") chat.put(key, values.getBoolean(key))
            else { require(values.getString(key) in setOf("enter", "ctrl_enter")) { "Choose what the Enter key does." }; chat.put(key, values.getString(key)) }
        }
        app.cache.write(owner, "chat-preferences", chat.toString(), true)
        if ((client?.scope ?: cachedOwnerScope) == owner) storeData("profile-settings", chat)
        } } catch (e: Exception) { if (e is CancellationException) throw e; if ((client?.scope ?: cachedOwnerScope) == owner) fail(e) }
    }
    fun loadSlashCatalog(force: Boolean = false) = load("slash:catalog", "/api/commands/catalog", force = force)
    fun runSlashCommand(name: String, argument: String = "") {
        if (!admitConversation()) return
        val commandName = name.removePrefix("/").lowercase()
        if (chatCommandChangesProfile(commandName, argument)) { notice("Change this for all chats in Settings."); return }
        if (commandName == "yolo") { notice("Use Approvals next to the message box."); return }
        if (_chat.value.raw.length() == 0 || _chat.value.streamId.isNotBlank() || _chat.value.uncertain.isNotBlank() || _chat.value.approval != null || _chat.value.clarification != null || app.calls.state.value.active || _attachments.value.isNotEmpty() || _chat.value.raw.optBoolean("read_only") || _chat.value.raw.optBoolean("is_read_only")) { notice("Wait for the reply and attachments first."); return }
        if (commandName == "model" && argument.isBlank()) { openCatalog("Model & provider"); return }
        if (commandName == "model") {
            val choice = runCatching { chatModelChoice(argument) }.getOrElse { notice("Choose the model and provider in the chat's model picker."); return }
            pickModel(choice.first, choice.second); return
        }
        if (commandName == "reasoning" && argument.isBlank()) { openCatalog("Reasoning"); return }
        action("/api/commands/exec", json("session_id" to _chat.value.sessionId, "name" to commandName, "argument" to argument), refresh = commandName in setOf("model", "reasoning", "fast"), successNotice = "") { _, result ->
            val type = result.string("type")
            if (type in setOf("send", "skill", "prefill", "alias")) notice(result.string("display", result.string("notice", "This command works only on a computer.")))
            else {
                val output = result.string("output", result.string("notice", "Done"))
                if (commandReportIsLong(output)) _commandReport.value = CommandReport("/$commandName", output) else notice(commandNoticeText(output))
                val command = "/${name.removePrefix("/")}" + argument.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                if (commandRanClearsDraft(_composer.value.text, command)) composer(TextFieldValue())
            }
        }
    }
    fun storeData(key: String, value: JSONObject) { _data.update { it + (key to value) } }
    fun dismissTaskResult(scope: Scope) = storeData("task-result:${scope.key}", JSONObject())
    /** An alert's Important / Not important choice reached the plugin: show it at once, then read it back. */
    fun alertChosen(scope: Scope, noticeId: String, verdict: String) {
        if (sourceScope != scope) return
        val chosen = _chat.value.alerts.map { if (it.noticeId == noticeId) it.copy(verdict = verdict) else it }
        _chat.update { it.copy(alerts = chosen) }
        viewModelScope.launch { saveChatAlerts(scope, chosen) }
        client?.let { refreshChatAlerts(scope, it) }
    }
    private var alertsGeneration = 0
    private val _alertFocus = MutableStateFlow<Pair<Scope, String>?>(null)
    /** The alert a tapped notification names; the chat scrolls to it once, then [consumeAlertFocus]. */
    val alertFocus: StateFlow<Pair<Scope, String>?> = _alertFocus
    fun consumeAlertFocus(target: Pair<Scope, String>) { _alertFocus.compareAndSet(target, null) }
    private suspend fun saveChatAlerts(scope: Scope, alerts: List<ChatAlert>) {
        try { app.cache.write(scope, "alerts", JSONArray(alerts.map { it.toJson() }).toString(), protected = true) }
        catch (e: Exception) { if (e is CancellationException) throw e /* The next read from the plugin rebuilds the saved copy. */ }
    }
    /** Cache-first: the saved alerts are already in the chat; this reads the plugin's current list behind them. Only the Beacon chat has alerts. */
    private fun refreshChatAlerts(scope: Scope, active: BeaconClient) {
        // Not isBeaconConversation(): that stays false while Beacon's chat is still opening, which is when its alerts are first read.
        if (scope.profile != preferences.value.assistantProfile || readBeaconSessionHandle(scope.copy(session = "")) != scope.session ||
            _ui.value.bootstrap.optJSONObject("optional_features")?.optString("plugin") != "available") return
        val generation = ++alertsGeneration
        val request = ScopeSettlement(scope, active, originGeneration, sessionGeneration)
        viewModelScope.launch {
            try {
                val reader = active.detached()
                val response = try { reader.get("${dev.hermes.android.ui.PROACTIVE_SETTINGS_PATH}/alerts", mapOf("profile" to scope.profile, "session_id" to scope.session)) } finally { reader.closeNative() }
                if (generation != alertsGeneration || !request.current()) return@launch
                val merged = mergeChatAlerts(_chat.value.alerts, response.rows("alerts").mapNotNull(ChatAlert::from))
                _chat.update { it.copy(alerts = merged) }
                saveChatAlerts(scope, merged)
            } catch (e: Exception) {
                if (e is CancellationException) throw e // Offline or an older plugin: the saved alerts stay as they are.
            } finally {
                // A tapped alert the newest read does not find (pruned, never saved) must not hold the scroll forever.
                if (generation == alertsGeneration) _alertFocus.value?.takeIf { it.first == scope && _chat.value.alerts.none { alert -> alert.noticeId == it.second } }?.let(::consumeAlertFocus)
            }
        }
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
        publishShownChat()
        // A phone that slept while a prompt's wait ran out shows no dead card on waking (the timer runs on awake time, the wait on the phone's own clock).
        if (active) _chat.update { it.promptsPruned(clockNow()) }
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
                // R2C: the open chat's own recovery starts before the warm-up reads, so they do not hold up its stream.
                else { recoverForeground(); prepareStartup(); if (sourceScope == null) openInitialChat() }
            }
            return
        }
        if (!changed) return
        if (!active) {
            refreshSecurePrompt(); cancelStartup(); recoveryJob?.cancel(); sessionWatch?.cancel(); sideJob?.cancel(); streams.values.toList().forEach(Job::cancel)
            val shownOwner = client?.scope ?: cachedOwnerScope
            viewModelScope.launch {
                runCatching { checkpointVisible() }.onFailure(::fail)
                if (shownOwner != null) withContext(Dispatchers.Default) { screens.flush(shownOwner) }
            }
        }
    }
    /** An update notification opens the update screen, which downloads and verifies the release from GitHub. */
    fun downloadAppUpdate(notificationToken: String) {
        if (_ui.value.authenticated) { tab("More"); more("App updates") }
        app.updates.updateFromNotification(notificationToken, client)
    }
    // Qualified: inside a member extension the bare names would read the receiver's own client and generations, and the fence would compare only the chat.
    private fun ScopeSettlement.current() = current(sourceScope, this@BeaconViewModel.client, this@BeaconViewModel.originGeneration, this@BeaconViewModel.sessionGeneration)
    /** True when the stream job registered for [scope] still reports to the open chat; a job the chat was left with is muted by the next open and counts for nothing. */
    private fun followsHere(scope: Scope) = streamFollows(streams[scope.key]?.isActive == true, streamStarts[scope.key], sourceScope, client, originGeneration, sessionGeneration)
    /**
     * P2-1: a profile switch or a notification bumps the sign-in generation before it leaves the open chat. When its next step fails, the
     * chat stays on screen with its own client and the stricter fence (D8) mutes its reply: follow it again, as returning to the app does.
     */
    private fun followKeptChat(generation: Int, keptScope: Scope?) {
        if (generation == originGeneration && client != null && client?.scope == keptScope && sourceScope != null) recoverForeground()
    }
    /** P2-1: Hermes answers again; a reply the open chat no longer follows (the step that failed could not reach Hermes either) is followed again. */
    private fun followMutedReply() {
        val scope = sourceScope ?: return
        if (client != null && _chat.value.streamId.isNotBlank() && !followsHere(scope)) recoverForeground()
    }
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
                // R2C: the turn on screen is followed again first (Hermes' answer, then the stream from its saved place), so its words go on at once; the
                // history read after it only adds what was saved meanwhile.
                val stream = _chat.value.streamId
                // It is only a head start: on any failure the return takes its old path below (the history read first), which words a failure itself.
                if (stream.isNotBlank() && !followsHere(scope) &&
                    !(try { followRunningTurn(active.detached(), scope, stream, request) } catch (e: Throwable) { if (e is CancellationException) throw e; true })) return@launch
                if (!request.current() || !foreground) return@launch
                // A followed turn reads its own history (its settle, its reconnects), as watchSession leaves it to: a second read here raced its settle and left a stale line.
                if (!followsHere(scope)) {
                    val response = c.get("/api/session", mapOf("session_id" to scope.session))
                    if (!request.current() || !foreground) return@launch
                    renderSession(response, scope)
                    app.cache.writeSession(scope, response.toString())
                    if (!request.current() || !foreground) return@launch
                }
                launch { refreshChatEffort() }
                reconcile(scope, c)
                if (!request.current() || !foreground) return@launch
                val shown = _chat.value.streamId
                if (shown.isNotBlank() && !followsHere(scope) && !followRunningTurn(c, scope, shown, request)) return@launch
                if (!reattachMarkedRun(c.detached(), scope, request)) return@launch
                watchSession(c, scope)
                if (_chat.value.streamId.isBlank()) drainQueued(scope, active, request.sessionGeneration, request.originGeneration)
                _side.value?.takeIf { it.scope == scope && it.status !in setOf("Complete", "Stopped") }?.let { resumeSideQuestion() }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (!request.current() || !foreground) return@launch
                if (unsavedOpenChatRead(e, scope.session, clientCreatedSessions.contains(scope.key), _chat.value)) watchSession(c, scope) else fail(e)
            }
        }
    }
    /**
     * The chat shows a running turn that no stream follows (it never connected, or ended unseen, and the socket that sent the prompt is long closed).
     * Hermes says whether the turn ended, which is settled from its saved history, or still runs, which is subscribed to again.
     * False when the chat moved on meanwhile.
     */
    /** rules24 (b): the submission each chat's stream job follows (its owned end is the one for the submission the chat displays). */
    private val streamSubmissions = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** rules24 (b): the check of a live copy left with no stream bound, one per chat at a time. */
    private val unboundLiveChecks = mutableMapOf<String, Job>()
    init { viewModelScope.launch { _chat.map { it.sessionId to liveCopyUnbound(it) }.distinctUntilChanged().collect { (_, unbound) -> if (unbound) reconcileUnboundLive() } } }
    /** R3-F4: the chat the screen shows, for the alerts: Beacon in front with the chat open and nothing over it. */
    private fun publishShownChat() {
        val ui = _ui.value
        dev.hermes.android.push.ShownChat.scope = sourceScope?.takeIf { foreground && ui.tab in setOf("Chat", "Beacon") && ui.morePage.isBlank() && !ui.showSessions && it.session == _chat.value.sessionId }
    }
    init { viewModelScope.launch { kotlinx.coroutines.flow.merge(_ui.map { listOf(it.tab, it.morePage, it.showSessions) }.distinctUntilChanged(), _chat.map { it.sessionId }.distinctUntilChanged()).collect { publishShownChat() } } }
    /**
     * R2C: the open chat's turn record follows its stream binding: written when a stream binds, gone when that chat's stream is released, and gone when a
     * chat opens idle while connected (a turn that ended unseen). A cold restore opens its chat before the client exists, so it never drops the record it reads.
     */
    init {
        viewModelScope.launch {
            var before: Pair<String, String>? = null
            _chat.distinctUntilChangedBy { it.sessionId to it.streamId }.collect { shown ->
                val left = before
                before = shown.sessionId to shown.streamId
                val change = liveTurnRecordChange(left, shown, connected = client != null) ?: return@collect
                val scope = sourceScope?.takeIf { it.session == shown.sessionId } ?: return@collect
                // A record that cannot be saved only costs the cold start its painted turn; the run marker still brings the turn back.
                try { if (change.isNotBlank()) app.cache.write(scope, LIVE_TURN, change) else app.cache.delete(scope, LIVE_TURN) }
                catch (e: Exception) { if (e is CancellationException) throw e }
            }
        }
    }
    private fun ownedEndInFlight(scope: Scope) = followsHere(scope) && streamSubmissions[scope.key] == _chat.value.raw.string("_display_submission_id")
    /**
     * rules24 (b), G6b restart 17: a live copy with no stream bound and no owned end in flight is reconciled at once, by state and not by a timer. Hermes says
     * where the turn is: one still running is followed again (Stop comes back); an ended one is settled from the saved chat, which drops the live copy when
     * the saved chat holds the turn's end (words it does not hold stay as a kept copy, as for any unproven end); a lost one offers the choice.
     */
    private fun reconcileUnboundLive() {
        val scope = sourceScope ?: return
        val active = client ?: return
        if (!foreground || _chat.value.sessionId != scope.session || ownedEndInFlight(scope) || unboundLiveChecks[scope.key]?.isActive == true) return
        val request = ScopeSettlement(scope, active, originGeneration, sessionGeneration)
        val job = viewModelScope.launch {
            val c = active.detached()
            var handed = false
            try {
                val answer = c.lookAtTurn(scope, lostCloseFor(scope))
                if (!request.current() || !foreground || ownedEndInFlight(scope) || !liveCopyUnbound(_chat.value)) return@launch
                val runtime = answer?.first.orEmpty()
                when (answer?.second) {
                    "working", "waiting" -> {
                        _chat.update { if (liveCopyUnbound(it)) it.copy(streamId = runtime, status = "Working") else it }
                        handed = attachStream(c, scope, runtime)
                        if (!handed) _chat.update { if (it.streamId == runtime) it.copy(streamId = "") else it }
                    }
                    "idle" -> {
                        val response = c.get("/api/session", mapOf("session_id" to scope.session))
                        if (!request.current() || !foreground || ownedEndInFlight(scope) || !liveCopyUnbound(_chat.value)) return@launch
                        renderSession(response, scope)
                        val held = savedTurnHoldsReply((response.optJSONObject("session") ?: response).rows("messages"))
                        _chat.update { if (liveCopyUnbound(it)) releaseTerminalChat(it, "", replyConfirmed = unboundReleaseConfirmed(it, held), terminalStatus = "") else it }
                        if (_chat.value.raw.has(UNCONFIRMED_REPLIES)) app.cache.writeSession(scope, _chat.value.raw.toString())
                    }
                    "lost" -> showLostChoice(scope)
                }
            } catch (e: Throwable) {
                // REVIEW-JOIN-121 P2-1: a refused sign-in is shown, not rethrown from a coroutine nothing catches (that would end the app); only for the account
                // still in use (follow-up 1: an answer that lands after a sign-out or an account change never fails the account now open).
                if (e is CancellationException) throw e
                if (e is ApiFailure && e.status in setOf(401, 403) && request.current()) fail(e)
            }
            finally { if (!handed) c.closeNative() }
        }
        unboundLiveChecks[scope.key] = job
        job.invokeOnCompletion { if (unboundLiveChecks[scope.key] === job) unboundLiveChecks.remove(scope.key) }
    }
    private suspend fun followRunningTurn(c: BeaconClient, scope: Scope, stream: String, request: ScopeSettlement): Boolean {
        val status = try { c.lookAtTurn(scope, lostCloseFor(scope))?.second }
        catch (e: Throwable) { if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403) || missingNativeConversation(e)) { c.closeNative(); throw e }; null }
        val current = request.current() && foreground
        // The shown reply was lost in a restart and the owner's messages wait behind it: no stream to follow, the chat offers the choice.
        if (status == "lost") { c.closeNative(); if (current && _chat.value.streamId == stream) showLostChoice(scope, stream); return current }
        // Settled or followed by another path while Hermes was asked: nothing is left to do here.
        val stale = !current || _chat.value.streamId != stream || followsHere(scope)
        if (!stale) attachStream(c, scope, stream, terminalAlreadyObserved = status == "idle")
        // S1: the look's socket resumed the chat, so Hermes sends it every frame of the turn, and the stream runs on a copy of its own: the look's
        // socket is closed whatever the attach did. Left open it stayed until 256 frames overflowed it, or for good after a short turn.
        if (c !== client && streamClients.values.none { it === c }) c.closeNative()
        return current
    }
    /**
     * Hermes lost this chat's reply in a restart and holds the chat until that turn is let go. Hermes keeps what waits behind it for the owner's
     * decision, as its own clients do (Desktop's Discard, /discard): a look closes the turn only when nothing waits ([LostTurnClose.WhenAlone]; never
     * while the phone holds a waiting message), and otherwise the chat offers the choice ([lostChoiceShown]). The owner's Send on it is [decided].
     * A closed turn shows Hermes' note on the cut reply, then the chat follows the next message Hermes starts, or rests idle. Looks are made once
     * per chat open ([lostTurnLooked]); [fresh] is new evidence (Hermes' reconnect projection names the lost turn) and looks again.
     */
    private fun recoverLostTurn(scope: Scope, known: Boolean = true, decided: Boolean = false, fresh: Boolean = false) {
        if (!decided && !fresh && scope.key in lostTurnLooked) return
        if (decided) lostTurnRecoveries[scope.key]?.cancel() else if (lostTurnRecoveries[scope.key]?.isActive == true) return
        val active = client ?: return
        val request = ScopeSettlement(scope, active, originGeneration, sessionGeneration)
        if (!foreground || !request.current()) return
        val job = viewModelScope.launch {
            val mode = lostCloseFor(scope, decided)
            if (sourceScope == scope) _chat.update {
                if (decided) it.copy(lostWaiting = false) else if (known && it.streamId.isBlank() && !it.lostWaiting) it.copy(status = LOST_REPLY_STATUS) else it
            }
            val probe = active.detached()
            val answer = try { probe.lookAtTurn(scope, mode) }
            catch (e: Throwable) { if (e is CancellationException) throw e; if (e is ApiFailure && e.status in setOf(401, 403) && request.current()) fail(e); null }
            finally { probe.closeNative() }
            if (!request.current() || !foreground) return@launch
            val runtime = answer?.first.orEmpty()
            // Both closed answers go through a fresh stream: it replaces the job still subscribed to the cut turn, which would otherwise paint the
            // next turn on this runtime and never settle it (it follows the cut turn's submission). Live run 16 Nov: a stale "OK" and "Working".
            when (answer?.second) {
                // The message queued behind the lost reply runs now: follow it (Stop comes back).
                "working", "waiting" -> {
                    _chat.update { it.copy(streamId = runtime, status = "Working", lostWaiting = false) }
                    if (!attachStream(active, scope, runtime)) _chat.update { if (it.streamId == runtime) it.copy(streamId = "", status = "") else it }
                }
                // Nothing runs: the turn is settled from the saved chat (Hermes' note on the cut turn), then the phone's queue moves on, as for any ended turn.
                "idle" -> {
                    _chat.update { it.copy(status = if (it.status == LOST_REPLY_STATUS) "" else it.status, lostWaiting = false) }
                    // A look that closed a lone lost reply gets the plain line when this settle ends ([showQuietLostClose]); after the owner's Send nothing more is said.
                    if (!attachStream(active, scope, runtime, terminalAlreadyObserved = true)) showQuietLostClose(scope)
                }
                // The owner's messages wait behind the lost reply: they are sent only when the owner says so.
                "lost" -> { lostTurnLooked += scope.key; showLostChoice(scope) }
                // Hermes could not be asked, or would not let the turn go: say so plainly. The next open (or Delete) tries again.
                else -> {
                    lostTurnLooked += scope.key
                    if (known || decided) _chat.update { if (it.streamId.isBlank()) it.copy(status = LOST_REPLY_STATUS, lostWaiting = false) else it }
                }
            }
        }
        lostTurnRecoveries[scope.key] = job
        job.invokeOnCompletion { if (lostTurnRecoveries[scope.key] === job) lostTurnRecoveries.remove(scope.key) }
    }
    /** How a look at [scope]'s lost reply may close it: the owner's act decides; a waiting message on the phone means a look never closes. */
    private suspend fun lostCloseFor(scope: Scope, decided: Boolean = false): LostTurnClose = when {
        decided -> LostTurnClose.Decided
        phoneHoldsWaiting(scope) -> LostTurnClose.Never
        else -> LostTurnClose.WhenAlone
    }
    /**
     * Hermes' answer for [scope]'s turn, closing a lost reply as [mode] lets a look do. Every path that looks (open, foreground, the stream's retry and
     * silence checks, a cold run marker) comes through here, so a lone lost reply a look closed is marked whichever path won ([quietLostCloses]).
     * REVIEW-JOIN-121 P1: so is the one rule that brings a card back. Hermes says the turn waits on the owner and no card shows (a replay that could not
     * carry the request, a reconnect that emptied the chat, a cold start): its pending prompts are read. A card on screen asks nothing more.
     */
    private suspend fun BeaconClient.lookAtTurn(scope: Scope, mode: LostTurnClose): Pair<String, String?>? {
        var closed = false
        return recoveredNativeSession(scope.session, mode) { closed = true; markLostChats(scope.profile, listOf(scope.session), emptySet()) }
            .also { if (closed && mode == LostTurnClose.WhenAlone && it?.second == "idle") quietLostCloses += scope.key }
            .also { if (it?.second == "waiting" && sourceScope == scope && _chat.value.approval == null && _chat.value.clarification == null) pollPrompts(this, scope) }
    }
    /**
     * Opening a chat asks Beacon's read-only route whether Hermes holds a reply its restart cut; nothing is resumed. With messages waiting
     * behind it (in Hermes' queue or the phone's box) the choice shows, as before. A lone one is let go at once, the way Hermes' own Discard
     * does (follow-up 4: prompt.resolve_unknown, no agent runs, nothing is resumed): the chat shows Hermes' note and the plain line, and the
     * person's next Send is one tap. If Hermes refuses that, the plain line stays on the held reply, as in follow-up 3. A plugin that is
     * missing or failing says nothing.
     */
    private fun showLostOnOpen(source: BeaconClient, scope: Scope, request: ScopeSettlement) {
        viewModelScope.launch { closeLostOnOpen(source, scope, request) }
    }
    /** [showLostOnOpen]'s look, also the first question of a cold run marker ([reattachMarkedRun]); it says what it did. */
    private suspend fun closeLostOnOpen(source: BeaconClient, scope: Scope, request: ScopeSettlement): LostOnOpen {
        val probe = source.detached()
        try {
            val lost = try { probe.lostTurnChats(listOf(scope.session)) } catch (e: Throwable) { if (e is CancellationException) throw e; null }
            if (lost == null || !request.current() || !foreground) return LostOnOpen.NotLost
            markLostChats(scope.profile, listOf(scope.session), lost.keys)
            val held = lost[scope.session] ?: return LostOnOpen.NotLost
            // REVIEW-CHATQA rule 1 (chat join): a stream job the chat was left with is alive but muted (D8); only one that reports here follows.
            fun idle() = _chat.value.streamId.isBlank() && !followsHere(scope)
            if (!idle()) return LostOnOpen.Held
            if (held.waiting > 0 || phoneHoldsWaiting(scope)) { showLostChoice(scope); return LostOnOpen.Held }
            val gone = try { probe.discardLostTurns(scope.session, held.unknown) } catch (e: Throwable) { if (e is CancellationException) throw e; false }
            if (gone) {
                markLostChats(scope.profile, listOf(scope.session), emptySet())
                // The person's own copy of the cut message ends as its admission just did (interrupted), so no receipt check resumes the chat to ask.
                val closed = held.unknown.map { it.admissionId }.toSet()
                fun cutCopy(q: QueuedMessage) = q.admissionId in closed && q.state in setOf("accepted", "unknown")
                if (sourceScope == scope) _chat.value.queued.filter(::cutCopy).forEach { item ->
                    updateQueued(scope, item.id) { q -> if (cutCopy(q)) q.afterReceipt("settled", "interrupted", "terminal") else q }
                }
                // Hermes wrote its note on the cut turn: the chat is read again (the same passive read the open made).
                val response = try { probe.get("/api/session", mapOf("session_id" to scope.session)) } catch (e: Throwable) { if (e is CancellationException) throw e; null }
                if (response != null && request.current() && idle()) { renderSession(response, scope); app.cache.writeSession(scope, response.toString()) }
            }
            if (request.current() && idle()) _chat.update { if (it.streamId.isBlank() && it.status.isBlank() && !it.lostWaiting) it.copy(status = LOST_REPLY_STATUS) else it }
            return if (gone) LostOnOpen.Closed else LostOnOpen.Held
        } finally { probe.closeNative() }
    }
    /** A list outside the drawer (a project's chats) marks its rows of the open profile from the same read-only route. */
    fun refreshLostChats(ids: List<String>) {
        val active = client ?: return
        readLostChats(active, active.scope, ids.filter(String::isNotBlank).distinct().take(LOST_TURN_READ_MAX), "ids")
    }
    /** Bot Mode marks each bot whose Bot Chat holds a lost reply; the plugin finds the chat, so nothing is opened or created here. */
    fun refreshLostBots(bots: List<String>) {
        val active = client ?: return
        val asked = bots.filter(String::isNotBlank).distinct().take(64)
        if (asked.isEmpty()) return
        lostReads["bots"]?.cancel()
        lostReads["bots"] = viewModelScope.launch {
            val probe = active.detached()
            val found = try { probe.lostTurnBots(asked) } catch (e: Throwable) { if (e is CancellationException) throw e; emptyMap() }
            finally { probe.closeNative() }
            if (client !== active) return@launch
            // Only the asked bots' Bot Chat marks are replaced: other marks of the same profile (its chat list's) stay.
            _ui.update { ui ->
                val before = asked.mapNotNull { bot -> ui.lostBotChats[bot]?.let { "$bot:$it" } }.toSet()
                ui.copy(lostChats = ui.lostChats - before + found.map { (bot, lost) -> "$bot:${lost.chat}" },
                    lostBotChats = ui.lostBotChats - asked.toSet() + found.mapValues { it.value.chat })
            }
        }
    }
    /** The chat list marks the rows of [scope]'s profile whose reply Hermes lost in a restart (the same read-only route); an unread answer marks none. */
    private fun refreshLostChats(active: BeaconClient, scope: Scope, rows: List<JSONObject>) =
        readLostChats(active, scope, rows.filter { it.string("profile", scope.profile) == scope.profile }.map(::sessionRowId).filter(String::isNotBlank).distinct().take(LOST_TURN_READ_MAX), "list")
    private fun readLostChats(active: BeaconClient, scope: Scope, ids: List<String>, kind: String) {
        if (ids.isEmpty()) return
        lostReads[kind]?.cancel()
        lostReads[kind] = viewModelScope.launch {
            val probe = active.detached()
            val lost = try { probe.lostTurnChats(ids).keys } catch (e: Throwable) { if (e is CancellationException) throw e; emptySet() }
            finally { probe.closeNative() }
            if (client === active) markLostChats(scope.profile, ids, lost)
        }
    }
    private fun markLostChats(profile: String, asked: Collection<String>, lost: Set<String>) {
        _ui.update { ui -> ui.copy(lostChats = ui.lostChats - asked.map { "$profile:$it" }.toSet() + lost.map { "$profile:$it" }) }
    }
    /** The settle after a look closed [scope]'s lone lost reply: one plain line under Hermes' note, no question (Root, follow-up 2). The mark is used once. */
    private fun showQuietLostClose(scope: Scope) {
        if (!quietLostCloses.remove(scope.key) || sourceScope != scope) return
        _chat.update { if (it.streamId.isBlank() && it.status.isBlank() && !it.lostWaiting) it.copy(status = LOST_REPLY_STATUS) else it }
    }
    /** The phone holds a message waiting in [scope]'s box: the open chat's, or the saved draft of a chat not on screen (an unreadable one counts as holding). */
    private suspend fun phoneHoldsWaiting(scope: Scope): Boolean {
        if (sourceScope == scope) return boxHoldsWaiting(_chat.value.queued)
        val saved = drafts[scope.key] ?: try { app.cache.read(scope, draftKind(scope))?.let(Draft::decode) ?: Draft() }
            catch (e: Exception) { if (e is CancellationException) throw e; return true }
        return boxHoldsWaiting(saved.queued)
    }
    /** The owner's box no longer holds anything for the lost reply's choice: look again; a lost reply nothing waits behind is simply closed (WhenAlone). */
    private fun afterBoxShrank(scope: Scope) {
        if (sourceScope == scope && _chat.value.lostWaiting && !boxHoldsWaiting(_chat.value.queued)) recoverLostTurn(scope, known = true, fresh = true)
    }
    /** The open chat offers to send what waits behind its lost reply; a stream left on the cut turn ([stream]) is released (it never ends). */
    private fun showLostChoice(scope: Scope, stream: String = "") {
        if (sourceScope != scope) return
        _chat.update {
            val released = releaseCutStream(it, stream)
            released.copy(lostWaiting = true, status = if (released.status == LOST_REPLY_STATUS || released.streamId.isBlank()) "" else released.status)
        }
        // REVIEW-JOIN-121 P3-3: a card from the lost turn can no longer be answered; Hermes says what it still waits on, and its list replaces the cards.
        if (_chat.value.approval != null || _chat.value.clarification != null)
            client?.takeIf { it.scope == scope.copy(session = "") }?.let { active -> viewModelScope.launch { pollPrompts(active, scope) } }
    }
    /** The owner's Send on the restart line: the lost reply is let go (Hermes' Discard) and what waited behind it is sent. */
    fun sendAfterLostReply() {
        val scope = sourceScope ?: return
        // One tap releases everything (R2): TURN's holds on the waiting messages go first, as the drain after the decided close is passive.
        viewModelScope.launch {
            try { for (held in _chat.value.queued.filter { it.held.isNotBlank() }) updateQueued(scope, held.id) { releaseHolds(listOf(it)).single() } }
            catch (e: Throwable) { if (e is CancellationException) throw e; android.util.Log.w("Beacon", "Couldn't release the held messages", e) }
            if (sourceScope == scope) recoverLostTurn(scope, known = true, decided = true)
        }
    }
    /**
     * Beacon delivered a prompt here and never saw its reply end, yet the chat shows no stream: the process was killed, or the chat was left and reopened.
     * Browsing is passive, so only this evidence earns one lazy resume. A reply still running is shown and followed again (replay skips what the saved live frame already holds),
     * one that ended is settled from history, and an unreadable answer leaves the chat and the marker alone. False when the chat moved on meanwhile.
     */
    private suspend fun reattachMarkedRun(c: BeaconClient, scope: Scope, request: ScopeSettlement): Boolean {
        var handedOver = false
        try {
            val marker = try { app.cache.read(scope, RUN_MARKER) } catch (e: Exception) { if (e is CancellationException) throw e; null }
            if (!request.current() || !foreground) return false
            if (_chat.value.streamId.isNotBlank() || followsHere(scope)) return true
            // Follow-up 3: no run of this phone's is marked here, yet a reply sent from elsewhere still shows (one read-only look, never a resume).
            if (!shouldAskRunning(marker != null, _chat.value.streamId)) return followUnseenTurn(c, scope, request)
            // Follow-up 4 (Root): a reply Hermes lost is let go from Beacon's read-only route first, as on open (prompt.resolve_unknown: no resume, no
            // agent built), and the marker goes with it. Only a reply the route does not hold as lost is asked about below; only a running one is followed.
            val lostLook = closeLostOnOpen(c, scope, request)
            if (!request.current() || !foreground) return false
            if (lostLook != LostOnOpen.NotLost) {
                if (lostLook == LostOnOpen.Closed && marker != null) optionalLocalSave { app.cache.delete(scope, RUN_MARKER, marker) }
                return true
            }
            val answer = try { c.lookAtTurn(scope, lostCloseFor(scope)) }
            catch (e: Throwable) { if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403) || missingNativeConversation(e)) throw e; null }
            // Another account, profile or chat was opened while Hermes was asked, or a send already follows its own stream.
            if (!request.current() || !foreground) return false
            if (_chat.value.streamId.isNotBlank()) return true
            if (answer?.second == "lost") showLostChoice(scope)
            val action = coldRunAction(answer?.second)
            if (action == ColdRun.Keep) return true
            val runtime = answer?.first.orEmpty()
            if (action == ColdRun.Follow) _chat.update { it.copy(streamId = runtime, status = "Working") }
            handedOver = attachStream(c, scope, runtime, terminalAlreadyObserved = action == ColdRun.Settle)
            return true
        } finally { if (!handedOver) c.closeNative() }
    }
    /**
     * CHATQA-121 follow-up 3: Hermes runs a reply in the open chat that no stream on this phone follows and no run marker names (the background outbox,
     * another device or a schedule sent it). Only when the saved history stops inside a turn, one read-only look at Hermes' live inventory: a running
     * reply then shows in progress with Stop, and its stream is followed. Nothing is resumed or sent by the look. False when the chat moved on meanwhile.
     */
    private suspend fun followUnseenTurn(source: BeaconClient, scope: Scope, request: ScopeSettlement): Boolean {
        val shown = _chat.value
        if (shown.sessionId != scope.session || shown.streamId.isNotBlank() || followsHere(scope) || !historyStopsInsideATurn(shown.raw.rows("messages"))) return true
        val c = source.detached()
        var handedOver = false
        try {
            // Chat join (Root msg_b0eda5e7404d): WEDGE's lost-turn route goes first. A reply Hermes lost in a restart shows the line or the choice
            // (showLostOnOpen), never Stop; a plugin without the route answers nothing, and the look goes on.
            if (_chat.value.lostWaiting || _chat.value.status == LOST_REPLY_STATUS) return true
            val lostHere = try { c.lostTurnChats(listOf(scope.session)).containsKey(scope.session) } catch (e: Throwable) { if (e is CancellationException) throw e; false }
            if (lostHere) return true
            if (!request.current() || !foreground) return false
            val inventory = try { c.nativeRpc("session.active_list", JSONObject()) }
            catch (e: Throwable) { if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403)) throw e; return true }
            if (!request.current() || !foreground) return false
            if (_chat.value.streamId.isNotBlank() || followsHere(scope) || _chat.value.lostWaiting || _chat.value.status == LOST_REPLY_STATUS) return true
            val runtime = observedRunningTurn(inventory, scope.session) ?: return true
            _chat.update { it.copy(streamId = runtime, status = "Working") }
            handedOver = attachStream(c, scope, runtime)
            return true
        } finally { if (!handedOver) c.closeNative() }
    }
    internal fun settingsProfileClient(): BeaconClient? {
        if (settingsOwner == null) return client
        return settingsClient?.takeIf { settingsOwner === client && settingsEpoch == originGeneration && _ui.value.authenticated }
    }
    internal fun settingsProfileCurrent(active: BeaconClient) = settingsProfileClient() === active && client?.scope?.sameAccount(active.scope) == true
    internal fun settingsProfileVersion() = settingsAuthority
    /** The restored sign-in's scope while there is no client yet (a cold start, or offline with saved screens): what a page may show its saved copy under, read-only. */
    internal fun restoredScope(): Scope? = if (client == null && _ui.value.authenticated) cachedOwnerScope else null
    internal fun settingsProfileCurrent(active: BeaconClient, version: Long) = settingsAuthority == version && settingsProfileCurrent(active)
    internal fun profileSettingsFailure(error: Throwable) { if (error is NativeAuthExpiredException || endsSignIn(error)) fail(error) }

    internal suspend fun authorizeSettingsProfile(active: BeaconClient, profile: String) {
        val account = client ?: throw CancellationException("Settings authority changed")
        val version = settingsAuthority
        if (!settingsProfileCurrent(active, version)) throw CancellationException("Settings authority changed")
        val generation = originGeneration
        val reader = account.detached()
        try {
            val profiles = try { reader.nativeGet("/api/profiles").rows("profiles") }
            catch (e: ApiFailure) {
                // Older native servers may not expose a catalog; only the already verified active profile remains usable.
                if (e.status in setOf(404, 405, 501) && profile == account.scope.profile) null else throw e
            }
            if (client !== account || generation != originGeneration || !settingsProfileCurrent(active, version)) throw CancellationException("Settings authority changed")
            if (profiles != null && profiles.none { it.string("name") == profile } && !(profiles.isEmpty() && profile == account.scope.profile)) throw ApiFailure(404, "Profile not found")
        } finally { reader.closeNative() }
    }

    private fun selectSettingsProfile(profile: String, after: suspend () -> Unit): Job {
        settingsSelectionJob?.cancel()
        val token = ++settingsSelection
        val account = client
        val editor = settingsProfileClient()
        val generation = originGeneration
        val navigation = settingsNavigation
        fun current() = token == settingsSelection && client === account && generation == originGeneration && navigation == settingsNavigation && _ui.value.tab == "More" && editor != null && settingsProfileCurrent(editor)
        _settingsProfileSelecting.value = true
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (account == null || editor == null) return@launch
                authorizeSettingsProfile(editor, profile)
                if (!current()) return@launch
                if (settingsOwner == null || editor.scope.profile != profile) {
                    val oldSnapshot = phoneSettings.snapshotDraft()
                    val migratingBot = oldSnapshot.optJSONObject("bot")?.takeIf { editor.scope.profile != profile && it.string("profile") == profile }
                    phoneCheckpoints.remove(editor.scope.key)?.cancelAndJoin()
                    retainPhoneSettings(editor.scope, oldSnapshot)
                    if (!current()) return@launch
                    val next = if (account.scope.profile == profile) account else account.forAuthorizedProfile(profile)
                    val previous = settingsClient
                    ++settingsAuthority
                    settingsOwner = account; settingsClient = next; settingsEpoch = generation
                    _settingsProfile.value = profile
                    phoneSettings.bind(force = true)
                    if (previous != null && previous !== account) previous.closeNative()
                    if (migratingBot != null) {
                        try {
                            val moved = phoneSettings.snapshotDraft()
                            if (moved.optJSONObject("bot")?.toString() == migratingBot.toString()) {
                                retainPhoneSettings(next.scope, moved)
                                retainPhoneSettings(editor.scope, oldSnapshot.copy().apply { remove("bot") })
                            }
                        } catch (e: Exception) { if (e is CancellationException) throw e; notice("Couldn't save a local copy. Refresh before leaving.") }
                    }
                    restorePhoneSettings(next)
                }
                if (token == settingsSelection && client === account && generation == originGeneration && navigation == settingsNavigation && _ui.value.tab == "More") {
                    saved["settings-edit-profile"] = json("account" to account.scope.copy(profile = "", session = "").key, "profile" to profile).toString()
                    after()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (current()) {
                    profileSettingsFailure(e)
                    if (current()) { if (_settingsProfileRestoring.value) _settingsProfileRestoreError.value = appErrorText(e) else notice(appErrorText(e)) }
                }
            } finally { if (token == settingsSelection) _settingsProfileSelecting.value = false }
        }
        settingsSelectionJob = job; job.start(); return job
    }
    fun inspectSettingsProfile(profile: String) = selectSettingsProfile(profile) {
        val state = phoneSettings.bots.value
        if (state.selected?.profile != profile || !state.editing) phoneSettings.openBot(profile)
    }
    fun openSettingsProfileTool(profile: String, page: String) = selectSettingsProfile(profile) { more(page) }
    fun closeSettingsProfile() {
        phoneSettings.closeBot()
        val profile = client?.scope?.profile ?: return
        selectSettingsProfile(profile) { saved.remove<String>("settings-edit-profile") }
    }
    internal fun restoreSettingsProfile() {
        if (!_settingsProfileRestoring.value || settingsRestoreJob?.isActive == true || !_ui.value.connected) return
        val account = client ?: return
        val record = saved.get<String>("settings-edit-profile")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val profile = record?.string("profile").orEmpty()
        if (_ui.value.tab != "More" || profile.isBlank() || record?.string("account") != account.scope.copy(profile = "", session = "").key) {
            saved.remove<String>("settings-edit-profile"); _settingsProfileRestoring.value = false; _settingsProfileRestoreError.value = ""; return
        }
        _settingsProfileRestoreError.value = ""
        val navigation = settingsNavigation
        val selection = selectSettingsProfile(profile) {
            val target = settingsProfileClient() ?: return@selectSettingsProfile
            val version = settingsAuthority
            if (phoneSettings.bots.value.selected?.profile != profile) {
                phoneSettings.loadBots()?.join()
                if (settingsProfileCurrent(target, version) && settingsNavigation == navigation) phoneSettings.openBot(profile)?.join()
            }
        }
        val token = settingsSelection
        settingsRestoreJob = viewModelScope.launch {
            selection.join()
            if (token == settingsSelection && client === account && _settingsProfileRestoring.value) {
                if (navigation != settingsNavigation) {
                    settingsRestoreJob = null
                    restoreSettingsProfile()
                } else if (settingsOwner === account && settingsProfileClient()?.scope?.profile == profile) {
                    _settingsProfileRestoring.value = false; _settingsProfileRestoreError.value = ""
                } else if (_settingsProfileRestoreError.value.isBlank()) _settingsProfileRestoreError.value = "Can't reach Hermes. Check your connection, then try again."
            }
        }
    }
    /** [keepLists]: leaving or entering Settings, not changing who is signed in or which profile is edited, so the pages keep the lists they hold. */
    private fun clearSettingsProfile(forget: Boolean = true, keepLists: Boolean = false) {
        ++settingsSelection; ++settingsNavigation; settingsSelectionJob?.cancel(); _settingsProfileSelecting.value = false
        settingsRestoreJob?.cancel()
        if (forget) { saved.remove<String>("settings-edit-profile"); _settingsProfileRestoring.value = false; _settingsProfileRestoreError.value = "" }
        val previous = settingsClient
        if (settingsOwner != null) checkpointPhoneSettings()
        ++settingsAuthority
        settingsOwner = null; settingsClient = null; settingsEpoch = -1; _settingsProfile.value = ""
        if (previous != null && previous !== client) previous.closeNative()
        phoneSettings.bind(force = true, keepLists = keepLists)
    }

    private fun checkpointPhoneSettings() {
        val owner = settingsProfileClient()?.scope ?: return
        val snapshot = runCatching { phoneSettings.snapshotDraft() }.getOrElse { notice("This draft is too large to keep. Shorten it."); return }
        if (snapshot.string("scope") != owner.key) return
        phoneCheckpoints.remove(owner.key)?.cancel()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { delay(200); retainPhoneSettings(owner, snapshot) }
            catch (e: Throwable) { if (e is CancellationException) throw e; if (client?.scope?.sameAccount(owner) == true) fail(e) }
            finally { if (phoneCheckpoints[owner.key] === currentCoroutineContext()[Job]) phoneCheckpoints.remove(owner.key) }
        }
        phoneCheckpoints[owner.key] = job; job.start()
    }
    private suspend fun retainPhoneSettings(owner: Scope, snapshot: JSONObject) = phoneCacheLock.withLock {
        if (snapshot.string("scope") != owner.key) return@withLock
        val hasDraft = listOf("skill", "memory", "bot").any(snapshot::has)
        if (hasDraft) app.cache.write(owner, "phone-editors", snapshot.toString(), protected = true)
        else app.cache.delete(owner, "phone-editors")
    }
    private suspend fun checkpointPhoneSettingsNow() {
        val owner = settingsProfileClient()?.scope ?: return
        phoneCheckpoints.remove(owner.key)?.cancelAndJoin()
        // Like the debounced path: an oversized settings draft must not abort connecting, signing out or switching chats.
        try { retainPhoneSettings(owner, phoneSettings.snapshotDraft()) }
        catch (e: Throwable) { if (e is CancellationException) throw e; notice("This draft is too large to keep. Shorten it.") }
    }
    internal suspend fun restorePhoneSettings(active: BeaconClient) {
        phoneSettings.bind()
        val version = settingsAuthority
        try {
            val snapshot = app.cache.read(active.scope, "phone-editors")
            if (settingsProfileCurrent(active, version) && snapshot != null) phoneSettings.restoreDraft(JSONObject(snapshot))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (settingsProfileCurrent(active, version)) notice("Couldn't restore your unsaved settings.")
        }
    }
    private suspend fun checkpointVisible() { checkpointNow(); checkpointEditorNow(); checkpointPhoneSettingsNow(); retainSideQuestion() }
    private fun retireSavedDrafts(active: Scope? = draftScope()) = pruneSavedDrafts(drafts, persistedDrafts, active?.key)
    private fun savedDraft(scope: Scope, encoded: String) { persistedDrafts[scope.key] = encoded; retireSavedDrafts() }
    private fun cancelNewChatWork() { newChatJobs.values.toList().forEach(Job::cancel); _ui.update { it.copy(newChatOpening = false, newChatError = if (sourceScope == null && initialCreateAttempts.containsKey((client?.scope ?: cachedOwnerScope)?.key)) initialCreateError else it.newChatError) } }
    private fun clearDetails() { cancelSecurePrompt(); loadedAt.clear(); appliedReasoningReads.clear(); _data.update { withoutOtherChatKeys(it - "picker:reasoning" - "picker:approvals" - "profile-defaults", sourceScope?.session.orEmpty()) }; reasoningChange = null; _reasoningSaving.value = false; modelChange = null; _modelSaving.value = ""; recoveryJob?.cancel(); stopReadAloud(); pasteJob?.cancel(); uploadJobs.values.toList().forEach(Job::cancel); uploadJobs.clear(); _attachmentPreparation.value = emptyList(); sideCheckpointJob?.cancel(); _editor.value = EditorState(); _form.value = null; _side.value = null; _commandReport.value = null; _catalog.value = ""; sideJob?.cancel() }
    fun loadChatApprovals(passive: Boolean = _chat.value.raw.string("runtime_session_id").isBlank(), force: Boolean = false): Job {
        sourceScope?.takeIf { it.session == _chat.value.sessionId && it.copy(session = "") == client?.scope }
            ?: return viewModelScope.launch {}
        return load("picker:approvals", CHAT_APPROVAL_PATH, chatApprovalReadQuery(_chat.value, passive), force = force, quiet = true)
    }
    private fun reasoningPath() = if (isBeaconConversation()) "/api/mobile/beacon/reasoning" else "/api/reasoning"
    internal val reasoningSelection get() = sessionGeneration
    internal fun savedReasoning(live: JSONObject?, includeLevelOnly: Boolean = false): JSONObject? {
        val scope = sourceScope ?: return null
        val owner = client?.scope ?: restoredScope() ?: return null
        if (scope.copy(session = "") != owner || _chat.value.sessionId != scope.session) return null
        val path = reasoningPath()
        val query = reasoningQuery(scope.session, _chat.value.raw)
        val id = client?.let { loadRequestId(it, path, query) }
        val saved = live?.takeIf { id != null && it.optString("_request_key") == id }
            ?: screens.exact(owner, "picker:reasoning", screenRequest(path, query))?.value
        return saved?.takeIf { includeLevelOnly || query["model"].isNullOrBlank() || it.has("options") || it.has("_error") }
    }
    private fun showChatEffort(active: BeaconClient, path: String, query: Map<String, String>, answer: JSONObject) {
        storeData("picker:reasoning", answer.copy().put("_request_key", loadRequestId(active, path, query)))
        _chat.update { it.copy(sessionReasoningEffort = reloadedEffort(it, it.sessionId, it.raw, answer)) }
    }

    /** Both readers settle on Main. A slower options response may fill the sheet after a more recent effort read. */
    private fun publishEffortRead(active: BeaconClient, path: String, query: Map<String, String>, order: Long, result: JSONObject) {
        val request = screenRequest(path, query)
        val answer = screens.exact(active.scope, "picker:reasoning", request)?.value?.copy() ?: JSONObject()
        val newer = order > (appliedReasoningReads[request] ?: 0L)
        if (!newer && (!result.has("options") || !answer.has("value"))) return
        require(result.opt("value") is String) { "Hermes returned an unreadable reasoning effort." }
        result.keys().forEach { if (newer || it == "options" || it == "known") answer.put(it, result.get(it)) }
        if (newer) appliedReasoningReads[request] = order
        screens.set(active.scope, "picker:reasoning", answer, request)
        showChatEffort(active, path, query, answer)
    }

    internal suspend fun refreshChatEffort() {
        val active = client ?: return
        val scope = sourceScope?.takeIf { it.copy(session = "") == active.scope && it.session == _chat.value.sessionId } ?: return
        val selection = ScopeSettlement(scope, active, originGeneration, sessionGeneration)
        val path = reasoningPath()
        val query = reasoningQuery(scope.session, _chat.value.raw)
        val version = screens.readVersion(active.scope, path)
        try {
            screens.open(active.scope)
            if (!selection.current()) return
            val order = ++reasoningReadOrder
            val effort = withContext(Dispatchers.IO) {
                val runtime = if (active.nativeSessionAuthority()) scope.session else {
                    // Legacy inventory is process-wide. Only this connection's profile-bound snapshot can identify our runtime.
                    val inventory = active.nativeRpc("session.active_list")
                    if (inventory.getJSONArray("sessions").objects().none { it.string("session_key") == scope.session }) return@withContext ""
                    val bound = active.nativeSessionSnapshot().takeIf { it.length() > 0 }?.let { nativeSessionBinding(active.scope, it) }
                    if (bound?.storedSessionId != scope.session || bound.runtimeSessionId != dev.hermes.android.ui.backgroundActivityRuntime(inventory, scope.session)) return@withContext null
                    bound.runtimeSessionId
                }
                active.nativeRpc("config.get", json("key" to "reasoning", "session_id" to runtime)).getString("value")
            } ?: return
            if (selection.current() && path == reasoningPath() && query == reasoningQuery(scope.session, _chat.value.raw) && screens.readCurrent(active.scope, path, version)) {
                publishEffortRead(active, path, query, order, json("value" to effort))
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (selection.current() && (e is NativeAuthExpiredException || endsSignIn(e))) fail(e) }
    }
    fun openCatalog(kind: String) { if (!admitConversation()) return; _catalog.value = kind; when (kind) { "Model & provider" -> load("picker:models", "/api/models", force = false); "Reasoning" -> {
        val path = reasoningPath()
        val query = reasoningQuery(_chat.value.sessionId, _chat.value.raw)
        // Options already read for this chat's model show at once while Hermes is asked again; another chat's or model's are dropped.
        if (!dev.hermes.android.ui.reasoningKeptOnOpen(_data.value["picker:reasoning"], client?.let { loadRequestId(it, path, query) }.orEmpty())) _data.update { it - "picker:reasoning" }
        load("picker:reasoning", path, query) } } }
    /** The identity [load] gives a read: account, path, query, connection generation, read version and (for a chat's read) the selection. */
    private fun loadRequestId(active: BeaconClient, path: String, query: Map<String, String>): String =
        "${screenReadIdentity(active.scope, path, query, originGeneration)}:${screens.readVersion(active.scope, path)}:${if (query["session_id"] == null) "" else sessionGeneration}"
    fun setReasoning(effort: String) {
        if (!admitConversation() || _reasoningSaving.value) return
        if (conversationChanges["${sourceScope?.key}|/api/reasoning"]?.isCompleted == false) return
        _reasoningSaving.value = true
        val path = reasoningPath()
        val query = reasoningQuery(_chat.value.sessionId, _chat.value.raw)
        val request = screenRequest(path, query)
        val job = action("/api/reasoning", json("session_id" to _chat.value.sessionId, "effort" to effort), refresh = false, successNotice = "Reasoning updated for this chat") { active, result ->
            val effective = result.getString("value")
            val answer = (screens.exact(active.scope, "picker:reasoning", request)?.value?.copy() ?: JSONObject()).put("value", effective)
            val visible = sourceScope == active.scope.session(query.getValue("session_id")) && path == reasoningPath() &&
                query == reasoningQuery(_chat.value.sessionId, _chat.value.raw)
            if (visible) screens.invalidate(active.scope, path, request)
            screens.set(active.scope, "picker:reasoning", answer, request)
            if (visible) {
                _chat.update { it.copy(raw = it.raw.copy().put("reasoning_effort", effective)) }
                showChatEffort(active, path, query, answer)
                if (_catalog.value == "Reasoning") closeCatalog()
            }
            withContext(Dispatchers.Default) { screens.flush(active.scope) }
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
            clearSettingsProfile()
            val owner = client?.scope ?: cachedOwnerScope
            error.retirementAuthority?.let { dev.hermes.android.push.KeepConnectedService.onUserSignedOut(app, it) }
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
                        draftWriteLock.withLock {
                            val current = drafts[draftScope.key] ?: retainedDraft
                            val encoded = current.encode(); app.cache.write(draftScope, draftKind(draftScope), encoded, current.text.isNotBlank() || current.attachments.isNotEmpty() || current.queued.isNotEmpty()); savedDraft(draftScope, encoded)
                        }
                        if (retainedEditor.scope != null && retainedEditor.path.isNotBlank()) app.cache.write(retainedEditor.scope, "editor:${retainedEditor.path}", json("text" to retainedEditor.text.text, "start" to retainedEditor.text.selection.start, "end" to retainedEditor.text.selection.end).toString(), retainedEditor.dirty || retainedEditor.saving)
                    }.onFailure { if (originGeneration == invalidation) notice("Couldn't save on this phone.") }
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
            clearSettingsProfile()
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
                        draftWriteLock.withLock {
                            val current = drafts[draftScope.key] ?: retainedDraft
                            val encoded = current.encode(); app.cache.write(draftScope, draftKind(draftScope), encoded, current.text.isNotBlank() || current.attachments.isNotEmpty() || current.queued.isNotEmpty()); savedDraft(draftScope, encoded)
                        }
                        if (retainedEditor.scope != null && retainedEditor.path.isNotBlank()) app.cache.write(retainedEditor.scope, "editor:${retainedEditor.path}", json("text" to retainedEditor.text.text, "start" to retainedEditor.text.selection.start, "end" to retainedEditor.text.selection.end).toString(), retainedEditor.dirty || retainedEditor.saving)
                    }.onFailure { if (originGeneration == invalidation) notice("Couldn't save on this phone.") }
                }
            }
            cancelNewChatWork(); app.calls.hangup(); sessionWatch?.cancel(); selectionJob?.cancel()
            streams.values.toList().forEach(Job::cancel); loads.values.toList().forEach(Job::cancel)
            lapsedAccount = client?.scope ?: cachedOwnerScope ?: lapsedAccount
            client = null; cachedOwnerScope = null; sourceScope = null
            val message = if (loopbackRestart) appErrorText(error) else "Sign in again. Your draft is kept."
            origin?.let { revoked -> val version = app.auth.authorityEpoch.readVersion(); viewModelScope.launch { runCatching { app.auth.removeIfUnchanged(revoked, version) }.onFailure { if (originGeneration == invalidation) notice("Couldn't remove the sign-in from this phone.") } } }
            _ui.update { it.copy(authenticated = false, connected = false, loading = false, restoringSession = false, setupWizard = true, setupComplete = false, setupStep = if (loopbackRestart) "connect" else "signin", setupOrigin = if (loopbackRestart) origin ?: it.setupOrigin else it.setupOrigin, beaconOpening = false, beaconError = "", newChatOpening = false, newChatError = "", initialChatReady = false, owner = "", query = "", sessions = emptyList(), bootstrap = JSONObject(), error = message) }
            saveSetupProgress(if (loopbackRestart) "connect" else "signin", complete = false, setupOrigin = if (loopbackRestart) origin else null)
            _chat.value = ChatState(); _composer.value = TextFieldValue(); _attachments.value = emptyList(); _data.value = emptyMap(); clearDetails()
        } else {
            _ui.update { it.copy(error = beaconOwnerSetupMessage(error) ?: appErrorText(error)) }
            if (error is java.io.IOException && error !is ApiFailure && error !is NativeThreadUnsupported && connectionProbe?.isActive != true) {
                val active = client ?: return
                connectionProbe = viewModelScope.launch {
                    val c = active.detached()
                    val reachable = try { withTimeout(4000) { c.nativeRequest("/api/auth/me").string("user_id") == active.auth.owner } }
                        catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e; false }
                        catch (_: Exception) { false }
                    if (client === active) { _ui.update { it.copy(connected = reachable) }; if (reachable) openInitialChat() }
                }
            }
        }
    }
    fun connect(input: String) = connect(input, null)
    private fun connect(input: String, initialAuth: AuthContext?) = viewModelScope.launch {
        val setupFlow = _ui.value.setupWizard
        modeTransition?.cancelAndJoin()
        val restoreSettings = _ui.value.tab == "More" && saved.get<String>("settings-edit-profile") != null
        checkpointPhoneSettingsNow()
        // R3-F7: a notification opened meanwhile owns what shows next (its open sets up its own client); this connect, already on its way (a start,
        // a return, the network back), would bump the generation after it, end the open without a word and reopen the last chat.
        if (initialAuth == null && pushOpening?.isActive == true) { connectAfterPush = input; return@launch }
        clearSettingsProfile(forget = !restoreSettings)
        if (restoreSettings) _settingsProfileRestoring.value = true
        val generation = ++originGeneration
        cancelStartup()
        cancelNewChatWork()
        try { checkpointVisible() } catch (e: Throwable) { _ui.update { it.copy(restoringSession = false) }; fail(e); return@launch }
        if (generation != originGeneration) return@launch
        sessionWatch?.cancel(); selectionJob?.cancel(); ++sessionGeneration
        streams.values.forEach(Job::cancel); streams.clear()
        _ui.update { it.copy(loading = true, restoringSession = true, error = "", beaconOpening = false, beaconError = "", newChatOpening = false, newChatError = "", initialChatReady = false, authenticated = false, connected = false, owner = "", query = "", sessions = emptyList(), bootstrap = JSONObject()) }
        val previousAccount = client?.scope ?: cachedOwnerScope ?: lapsedAccount
        // The page asks to restore as soon as it is marked restoring, while this sign-in still reads connected; a restore that attached to the client dropped below must not outlive it.
        if (settingsOwner != null) clearSettingsProfile(forget = !restoreSettings)
        _chat.value = ChatState(); _composer.value = TextFieldValue(); _attachments.value = emptyList(); _data.value = emptyMap(); forgetKeptData(); chatsToForget.clear(); clearDetails(); sourceScope = null; cachedOwnerScope = null; client = null
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
            // A call press's start-over keeps this attempt's sign-in rotation before it supersedes it (REVIEW-F7 P3-2), and reads its progress (P3-3).
            connectAttempt = ConnectAttempt(generation, next, trustedCache, credentialVersion)
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
            // Follow-up 5: connected again; a lost answer is asked once more (by the chat's selection, or below).
            bumpReachEpoch()
            // Another account (R2 #8): the first account's message cannot go from this one, so its "Not sent yet" goes; the same account's reconnect keeps it.
            previousAccount?.takeIf { !it.sameAccount(next.scope) }?.let { dropNotSentYet(); forgetScreens(it, chats = true) }
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
            val selectionAtConnect = sessionGeneration
            _ui.update { it.copy(origin = origin, owner = next.auth.owner, profile = next.auth.profile, authenticated = true, connected = true, bootstrap = bootstrap, loading = false, setupWizard = setupFlow || it.setupWizard, setupStep = if (setupFlow) wizardStep else it.setupStep) }
            dev.hermes.android.push.KeepConnectedService.startIfEnabled(app)
            prepareStartup()
            replayEarlyReads(next)
            if (_ui.value.tab == "Beacon") openBeacon() else if (assistantReturn() != null) returnFromAssistant() else {
                val selected = chatTabSelection(saved.get<String>("session:${next.scope.key}") ?: sourceScope?.takeIf { it.copy(session = "") == next.scope }?.session ?:
                    try { app.cache.authorizedLegacySelection(next) } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        if (endsSignIn(error) || error is NativeAuthExpiredException) throw error
                        null
                    }, readBeaconSessionHandle(next.scope))
                val shell = app.cache.read(next.scope, "new-chat-shell")
                if (generation != originGeneration || client !== next) return@launch
                shell?.let { initialShells[next.scope.key] = it }
                if (!selected.isNullOrBlank()) {
                    // A new chat asked for once the app read connected goes first, and a chat opened since (that new chat or another) is newer
                    // than the one this start reopens. Selecting the restored chat over it made the new chat on Hermes and never opened it
                    // (CHAT-121, a New chat within about half a second). A new chat that did not open leaves the restored chat to reopen here.
                    newChatJobs[next.scope.key]?.takeIf { it.isActive }?.join()
                    if (generation == originGeneration && client === next && sessionGeneration == selectionAtConnect) selectSession(selected, reopen = true)
                } else restoreInitialDraft(next.scope, generation)
            }
            // P2-3: connected again; the waiting messages of every chat go.
            askAgainUnconfirmed()
            drainOfflineOutbox()
        } catch (e: Throwable) {
            // Ended: from here only this catch keeps its rotation, never a call press's start-over (it may run inline with the update below).
            connectAttempt?.takeIf { it.generation == generation }?.ended = true
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
                            if (generation == originGeneration) notice("Couldn't keep your sign-in. Reconnect before leaving.")
                        }
                    }
                }
                if (generation != originGeneration) return@launch
                _ui.update { it.copy(loading = false, restoringSession = false) }
                if (cachedOwnerScope == null) client = null
                // R2C: a turn painted from the saved chat cannot be asked about yet: it says so instead of looking like it is working.
                if (client == null) _chat.update { if (it.streamId.isNotBlank()) it.copy(status = "Reconnecting…") else it }
                fail(e)
            }
        } finally {
            connectAttempt?.takeIf { it.generation == generation }?.ended = true
            if (generation == originGeneration) _ui.update { it.copy(restoringSession = false) }
        }
    }
    fun reopenSetupWizard(address: String = _ui.value.origin) {
        clearSettingsProfile()
        ++originGeneration
        setupProbeJob?.cancel(); ++setupProbeGeneration
        val safeAddress = runCatching { dev.hermes.android.ui.parseBeaconConnectInput(address) }.getOrDefault("")
        _ui.update { it.copy(setupOrigin = safeAddress, setupWizard = true, setupStep = "connect", loading = false, restoringSession = false, setupPluginMessage = "", error = "", notice = "") }
        saveSetupProgress("connect", active = true, setupOrigin = safeAddress)
    }
    fun openSetupLink(value: String, confirmed: Boolean = false) {
        clearSettingsProfile()
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
    /** [openSettings] names a settings page to open once setup is done (the wizard's last screen links Phone access and Proactive). */
    fun finishSetupWizard(keepConnected: Boolean? = null, openSettings: String? = null) {
        if (!_ui.value.authenticated) return
        clearPendingVoiceKeyRequests()
        dev.hermes.android.ui.keepConnectedChangeOnFinish(keepConnected, dev.hermes.android.push.KeepConnectedService.isEnabled(app))?.let(::setKeepConnectedEnabled)
        _ui.update { it.copy(setupWizard = false, setupComplete = true, setupStep = "done", setupOrigin = "", error = "") }
        saveSetupProgress("done", complete = true, active = false)
        tab("Chat")
        openInitialChat()
        openSettings?.let { tab("More"); more(it) }
    }
    private suspend fun persistAuthIfCurrent(auth: AuthContext, version: Long?, snapshot: Boolean, selectProfileIf: (() -> Boolean)? = null): Boolean {
        if (app.auth.persistIfUnchanged(auth, version, snapshot, selectProfileIf)) return true
        return withContext(Dispatchers.IO) { app.auth.read(auth.origin).continuesSignInOf(auth) }
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
                    generateSequence(error as Throwable?) { it.cause }.any { it is javax.net.ssl.SSLException } -> "Can't connect securely. Check the address and the server's certificate."
                    error.message?.startsWith("This address doesn't look like a Hermes") == true -> "This address doesn't look like a Hermes server. Check the address, then Retry."
                    error is ApiFailure -> "This address doesn't look like a Hermes server. Check the address, then Retry."
                    else -> "Can't reach Hermes. Check your network or VPN, then Retry."
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
        catch (error: Throwable) { _ui.update { it.copy(loading = false, error = signInErrorText(error)) } }
    }
    private suspend fun restoreCachedOwner(scope: Scope, generation: Int) {
        val index = app.cache.read(scope, "conversation-index")?.let(::JSONObject)
        openGlances(scope.copy(session = ""))
        val settings = app.cache.read(scope, "profile-settings")?.let(::JSONObject)
        val beacon = readBeaconCache(scope)
        val selected = chatTabSelection(saved.get<String>("session:${scope.key}") ?: app.cache.read(scope, "selected-session"), beacon)
        val shell = app.cache.read(scope, "new-chat-shell")
        val screenData = cachedScreens(scope)
        if (generation != originGeneration) return
        cachedOwnerScope = scope
        restoreBotMode(scope)
        app.calls.ownerScope = scope
        if (beacon != null) writeBeaconSessionHandle(scope, beacon)
        shell?.let { initialShells[scope.key] = it }
        val id = if (_ui.value.tab == "Beacon") beacon else selected
        _data.update { screenData + screens.composer(scope.session(id.orEmpty())) + it }
        // Skills, Memory and the Server pages open from their saved copy while the app reconnects, so they are pointed at the restored account now.
        phoneSettings.bind(); phoneSettings.seedSaved()
        settings?.let { _data.update { data -> data + ("profile-settings" to it) } }
        _ui.update { it.copy(origin = scope.origin, owner = scope.owner, profile = scope.profile, authenticated = true, connected = false, sessions = index?.rows("sessions").orEmpty()) }
        if (!id.isNullOrBlank()) {
            sourceScope = scope.session(id)
            val selection = ++sessionGeneration
            _chat.value = ChatState(sessionId = id, status = "Saved copy · reconnecting")
            // R2C: a painted turn keeps its own line; the saved-copy line is for an idle chat.
            if (restoreCachedConversation(scope.session(id), selection, paintTurn = true) && generation == originGeneration && sourceScope == scope.session(id)) _chat.update { if (it.streamId.isBlank()) it.copy(status = "Saved copy · reconnect to update") else it }
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
    /**
     * A route that ended on the Chat tab (a return from the assistant tab, a tab switch) prepares the new chat the way sign-in does, when nothing else will:
     * no conversation is open or being selected and the chat was never made ready. Anything already ready, open or opening is left alone.
     */
    private var preparingInitialChat = false
    private suspend fun prepareInitialChatIfNone(generation: Int = originGeneration, restoreDraft: Boolean = false) {
        val owner = client?.scope ?: return
        val ui = _ui.value
        // [restoreDraft]: a return from the assistant tab cleared the composer for the assistant's conversation, so a chat that was already ready still needs its draft back.
        if (preparingInitialChat || !newChatNeedsPreparing(ui.authenticated, ui.tab, ui.initialChatReady && !restoreDraft, initialDraftOwner() == owner, selectionJob?.isActive == true)) return
        preparingInitialChat = true
        try { restoreInitialDraft(owner, generation) } finally { preparingInitialChat = false }
    }
    /** The saved selection (this run's and the saved row) stops naming chat [id], so the next start does not reopen it; false when the row could not be cleared. */
    private suspend fun forgetSelection(owner: Scope, id: String): Boolean {
        if (saved.get<String>("session:${owner.key}") == id) saved.remove<String>("session:${owner.key}")
        return try { if (app.cache.read(owner, "selected-session") == id) app.cache.delete(owner, "selected-session"); true }
        catch (error: Throwable) { if (error is CancellationException) throw error; false }
    }
    /** A first-turn chat can live only in Hermes' runtime. Both it and the store must report the id absent. */
    private suspend fun beaconChatAbsent(c: BeaconClient, scope: Scope, error: Throwable): Boolean {
        if (_ui.value.tab != "Beacon" || c.scope.session(scope.session) != scope || readBeaconSessionHandle(c.scope) != scope.session || sourceScope != scope) return false
        if (generateSequence(error) { it.cause }.take(8).any {
            it is ApiFailure && it.status in setOf(401, 403) || it is NativeAuthExpiredException || it is LoopbackSessionRefreshFailed || it is CachedIdentityMismatch
        }) return false
        val profile = mapOf("profile" to scope.profile)
        return try {
            val page = c.nativeGet("/api/sessions", profile + mapOf("min_messages" to "0", "archived" to "include", "order" to "recent", "limit" to "20"))
            val rows = page.getJSONArray("sessions")
            if (!page.isNull("storage") && page.getJSONObject("storage").length() > 0) return false
            if ((0 until rows.length()).any {
                val row = rows.getJSONObject(it)
                row.getString("id") == scope.session || row.string("chat_id") == scope.session || row.optString("profile", scope.profile) != scope.profile
            }) return false
            // Legacy active_list includes every live session, including idle drafts; id is the runtime id and session_key the stored id.
            val live = c.nativeRpc("session.active_list", json("profile" to scope.profile)).getJSONArray("sessions")
            for (i in 0 until live.length()) {
                val row = live.getJSONObject(i)
                val runtime = row.opt("id") as? String
                val key = row.opt("session_key") as? String
                if (runtime.isNullOrBlank() || key.isNullOrBlank() || runtime == scope.session || key == scope.session) return false
            }
            // Check the exact row last: an initially unpersisted chat can gain its first stored turn during these reads.
            val stored = c.nativeGet("/api/sessions/${scope.session}/mutation-snapshot", profile)
            stored.string("session_id") == scope.session && stored.opt("exists") == false
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }

    private suspend fun keepBeaconTransfer(scope: Scope, current: () -> Boolean): Boolean = draftWriteLock.withLock {
        if (!current()) return@withLock false
        val owner = scope.copy(session = "")
        val transferId = java.util.UUID.randomUUID().toString()
        val visible = sourceScope == scope && _chat.value.sessionId == scope.session
        try {
            // A call can discover a deleted chat before the tab has loaded its saved draft.
            val saved = if (visible) null else {
                if (app.cache.read(owner, BEACON_DRAFT_TRANSFER) != null) return@withLock current()
                drafts[scope.key] ?: app.cache.read(scope, "draft")?.let(Draft::decode)
            }
            do {
                if (!current()) return@withLock false
                val unsent = (if (visible) draft() else saved)?.unsentPart()
                val transfer = JSONObject((unsent ?: Draft()).encode()).put("from", scope.session).put("transfer_id", transferId).toString()
                app.cache.write(owner, BEACON_DRAFT_TRANSFER, transfer, protected = true)
                if (!current()) {
                    app.cache.delete(owner, BEACON_DRAFT_TRANSFER, transfer)
                    return@withLock false
                }
            } while (visible && unsent != draft().unsentPart())
        } catch (e: CancellationException) { throw e } catch (e: Exception) { if (current()) fail(e); return@withLock false }
        goneBeaconChats += scope.key
        if (visible) {
            sessionWatch?.cancel(); ++sessionGeneration; sourceScope = null; clearDetails()
            _chat.value = ChatState(projectionReady = false); _composer.value = TextFieldValue(); _attachments.value = emptyList()
        }
        true
    }

    private suspend fun recoverGoneBeaconChat(scope: Scope, current: () -> Boolean) {
        if (!keepBeaconTransfer(scope, current)) return
        optionalLocalSave { app.cache.delete(scope, "client-created-shell"); app.cache.delete(scope, "session") }
        if (_ui.value.tab == "Beacon" && (client?.scope ?: cachedOwnerScope) == scope.copy(session = "")) openBeacon()
    }

    private suspend fun recoverMissingInitialShell(owner: Scope, id: String, current: () -> Boolean) {
        if (!current()) return
        val active = client ?: return
        val origin = originGeneration
        val scope = owner.session(id)
        var retained = draft()
        // Hermes forgot the pinned Beacon conversation before its first turn: resolve a fresh one there instead of bouncing to Chat forever.
        val pinned = readBeaconSessionHandle(owner) == id || preparedBeacon?.let { it.first == owner && it.second == id } == true ||
            runCatching { readBeaconCache(owner) == id }.getOrDefault(false)
        if (pinned) {
            if (_ui.value.tab == "Beacon") { recoverGoneBeaconChat(scope, current); return }
            clearBeaconSessionHandle(owner)
            if (preparedBeacon?.second == id) preparedBeacon = null
            try { app.cache.delete(owner, BEACON_SESSION_CACHE_KIND); app.cache.delete(owner, LEGACY_BEACON_SESSION_CACHE_KIND); app.cache.delete(scope, "client-created-shell"); app.cache.delete(scope, "session") }
            catch (error: Throwable) { if (error is CancellationException) throw error; notice("Your draft is safe. Refresh to finish.") }
            if (!current()) return
        }
        try {
            draftWriteLock.withLock {
                if (!current()) return@withLock
                retained = draft()
                val encoded = retained.encode(); app.cache.write(owner, "initial-draft", encoded, retained.text.isNotBlank() || retained.attachments.isNotEmpty() || retained.queued.isNotEmpty()); savedDraft(owner, encoded)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (current()) { _chat.update { it.copy(status = "Draft kept · couldn't start a new chat") }; notice(appErrorText(error)) }
            return
        }
        if (!current()) return
        var cleanupFailed = !forgetSelection(owner, id)
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
        checkpointPhoneSettingsNow()
        clearSettingsProfile()
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
        var retirement: DeviceRemovalAuthority? = null
        try {
            val retired = (current?.scope ?: cached)?.let { app.auth.retireIfUnchanged(it, credentialVersion) }
            if (origin != null && retired?.removed != true) {
                if (generation == originGeneration) notice("Sign-in changed while signing out. Try again to sign out the current account.")
                return@launch
            }
            retirement = retired?.authority
        } catch (e: Throwable) { fail(e); return@launch }
        if (generation != originGeneration) return@launch
        (current?.scope ?: cached)?.let { owner -> retirement?.let { forgetScreens(owner, it, chats = true) } }
        if (generation != originGeneration || retirement?.ifCurrent {} == false) return@launch
        // Pictures and files shown for this account stay in memory only while it is signed in.
        dev.hermes.android.ui.MediaCache.clear()
        dev.hermes.android.ui.CallHistoryMemory.clear()
        withContext(Dispatchers.IO) { ChatAttachmentStore.clear() }
        val pushRevocations = runCatching { current?.let { active -> retirement?.let { app.push.prepareOwnerRevocation(active.scope, it) } }.orEmpty() }
            .getOrElse {
                notice("Signed out. Notifications may still arrive for a while.")
                emptyList()
            }
        retirement?.let { dev.hermes.android.push.KeepConnectedService.onUserSignedOut(app, it) }
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
                    .onFailure { if (generation == originGeneration) notice("Signed out. Notifications may still arrive for a while.") }
            }
        }
        client = null; cachedOwnerScope = null; sourceScope = null; retireSavedDrafts(null); _chat.value = ChatState(); _composer.value = TextFieldValue(); _data.value = emptyMap(); forgetKeptData(); _attachments.value = emptyList(); clearDetails()
        // REVIEW-JOIN-121 P3-5: the live reply words held in memory go with the account too.
        liveFrames.clear(); liveBoundaries.clear(); persistedTurns.clear()
        val setupStep = if (loopback) "connect" else "signin"
        _ui.update { it.copy(authenticated = false, connected = false, loading = false, restoringSession = false, setupWizard = true, setupComplete = false, setupStep = setupStep, setupOrigin = if (loopback) origin.orEmpty() else it.setupOrigin, beaconOpening = false, beaconError = "", newChatOpening = false, newChatError = "", initialChatReady = false, sessions = emptyList(), query = "", owner = "", bootstrap = JSONObject()) }
        saveSetupProgress(setupStep, complete = false, setupOrigin = if (loopback) origin else null)
        // A load that began between the generation bump and here may have reopened the account; nothing can reopen it now.
        (current?.scope ?: cached)?.let { owner -> retirement?.let { forgetScreens(owner, it, chats = true) } }
    }
    fun tab(value: String) { if (_ui.value.botModeSwitching) return; botMode.back(); tab(value, resumeDraft = true) }
    private fun tab(value: String, resumeDraft: Boolean): Job? {
        val previous = _ui.value.tab
        if (leavesChat(previous, value)) clearMessage()
        if (previous == "More") clearSettingsProfile(keepLists = true)
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
            } else if (value == "Chat" && resumeDraft && !_ui.value.initialChatReady) {
                // The chat was never made ready (sign-in returned to another tab, or an assistant return was spent elsewhere): opening Chat prepares it, after any return in flight.
                viewModelScope.launch { transition?.join(); prepareInitialChatIfNone() }
            }
        }
        return transition
    }
    fun loadNativeProjects(projectId: String = "", force: Boolean = false): Job {
        if (projectId.isBlank()) return load("native-projects", "/api/projects", force = force)
        return load("native-project:$projectId", "/api/projects/detail", mapOf("id" to projectId), force = force, quiet = true)
    }
    private var beaconCall: Job? = null
    private val _beaconCallFailures = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** The line of a Beacon call that ended before a call could start ([callBeacon]): the overlay shows its short form, the app a notice. */
    internal val beaconCallFailures: SharedFlow<String> = _beaconCallFailures.asSharedFlow()
    private fun beaconCallFailed(line: String) {
        if (_ui.value.authenticated) notice(line)
        _beaconCallFailures.tryEmit(line)
    }
    /**
     * One tap from the drawer, the side button or the Beacon tab's own call button: open the Beacon chat, then hand it the request its
     * call button makes, so permission, voice key and errors are handled in one place.
     * The Beacon chat this phone keeps may be one Hermes' plugin no longer holds (gone, or not linked to this sign-in), where a call never
     * starts (backlog 57; device round 1, 14 Nov 2026: the side button showed "Couldn't start the call"). It is checked while it opens,
     * and a refused one gives way to the account's current Beacon chat, found as a fresh start finds it (or a new one), in this same
     * press and with no message.
     */
    fun callBeacon() {
        if (beaconCall?.isActive == true) {
            // Asked again while a refused start is being checked (the overlay's own retry): that check calls again. While a start is only
            // watched, a new ask starts afresh, as it did once the request had gone out. A second tap while Beacon opens is the same call.
            when (beaconCallPhase) {
                BeaconCallPhase.Deciding -> { beaconCallAskedAgain = true; return }
                BeaconCallPhase.Watching -> beaconCall?.cancel()
                BeaconCallPhase.Opening -> return
            }
        }
        beaconCallAskedAgain = false
        beaconCallPhase = BeaconCallPhase.Opening
        beaconCall = viewModelScope.launch {
            // A press right after the app's process starts (the side button's own model, or this one) comes once the saved sign-in is restored
            // but before Hermes answered: it waits for the connection, the owner seeing the call connect meanwhile, and only a link that does
            // not come up in time fails it (device round 2, 15 Nov 2026: every first side-button press failed at once, "Couldn't start the call").
            // A model with no client whose attempt already failed is not waited for: the press asks for a new one (Call again once Hermes is
            // back), and Hermes is asked directly too, so one that cannot be reached is said at once (device round 4, F3: 15 s, then only
            // "Couldn't start the call"). A model that has its client goes straight on: the call meets Hermes itself.
            val before = _ui.value
            val fresh = client == null && reconnectOnNetworkReturn(before)
            if (fresh) connect(before.origin)
            val origin = before.origin
            // An attempt Hermes' answer shows to be stuck (a black hole that ended while its connect hung) is started over once, so Call again
            // works once Hermes is back (REVIEW-121B P2-1); only by the reconnect rule, never past a sign-in or setup. One that was in flight
            // while an earlier press found Hermes unreachable (and could not reach its host at all) is stuck in that outage: it is started over
            // as soon as Hermes answers.
            val stuck = stuckAttemptMarked(unreachableAttempt, originGeneration, client != null, before.loading)
            val readGotSomewhere = java.util.concurrent.atomic.AtomicBoolean(false)
            val waitFrom = progressClockMs() - if (stuck) 0L else BEACON_CALL_PROGRESS_LOOKBACK_MS
            val connection = awaitCallConnection(_ui, { client != null }, BEACON_CALL_CONNECT_MS, freshAttempt = fresh,
                reach = { if (origin.isBlank()) null else hermesReachFailure { readHermesStatus(origin, viewModelScope) { readGotSomewhere.set(true) } } },
                restart = { startOverAttempt(origin) },
                stallMs = if (stuck) 0L else BEACON_CALL_STALL_MS,
                progressed = { connectAttempt?.takeIf { it.generation == originGeneration }?.client?.hermesProgressAt?.let { it >= waitFrom } == true })
            if (connection is CallConnection.Failed) {
                unreachableAttempt = stuckAttemptMark(connection, readGotSomewhere.get(), client != null, _ui.value.loading, originGeneration, unreachableAttempt)
                beaconCallFailed(connection.line); return@launch
            }
            val kept = keptBeaconChat()
            var refusedEarly = false
            val held = kept?.let { (c, id) -> async { beaconChatHeld(c, id).also { if (it == false) refusedEarly = true } } }
            // The Beacon chat already on screen is not opened again.
            if (_ui.value.tab != "Beacon" || !isBeaconConversation()) navigate("Beacon")
            var opened = beaconChatOpened { refusedEarly }
            // Hermes' own answer, whenever it comes (bounded inside the check by the call's reach budget). Device round 3 (F1): cut at
            // 1.5 s, a cold process called a chat Hermes no longer had, and its grant was refused (404) on every try.
            val verdict = if (held == null || opened == "") null else held.await()
            if (opened != "" && kept != null && verdict == false && beaconCallChat(kept.second, opened, verdict) == null) opened = replaceGoneBeaconChat(kept)
            held?.cancel()
            val first = client
            if (opened == "") return@launch
            if (opened == null || first == null) { notice("${dev.hermes.android.data.currentAssistantName} didn't open in time. Try again."); return@launch }
            var active: BeaconClient = first
            // A chat Hermes no longer has can still pass the quick check (a context read before it went, a registration earlier in this run):
            // its grant or its context is then refused before the call connects. That refusal is Hermes' answer too: the chat is checked
            // again, and a gone one is forgotten, its failure line cleared, and the call goes to the account's current Beacon chat in the
            // same press (device round 3, F1). Any other failure stays; a retry the overlay asked for meanwhile goes to the same chat.
            val job = coroutineContext[Job]
            var chat: String = opened
            var replaced = false
            try {
                while (true) {
                    val askedAt = System.currentTimeMillis()
                    beaconCallPhase = BeaconCallPhase.Watching
                    _voiceCallRequest.value = VoiceCallRequest(active.scope, chat)
                    val failed = beaconCallStartFailure(app.calls.state, active.scope.session(chat), askedAt, BEACON_CALL_START_WATCH_MS) ?: return@launch
                    beaconCallPhase = BeaconCallPhase.Deciding
                    beaconCallAskedAgain = false
                    if (!replaced && kept != null && kept.second == chat && beaconChatHeld(kept.first, chat, quick = false) == false) {
                        replaced = true
                        app.calls.dismissFailure(failed.callId)
                        chat = replaceGoneBeaconChat(kept).takeUnless { it.isNullOrEmpty() } ?: return@launch
                        active = client ?: return@launch
                    } else if (!beaconCallAskedAgain) return@launch
                }
            } finally { if (beaconCall === job || beaconCall == null) beaconCallPhase = BeaconCallPhase.Opening }
        }
    }
    /**
     * The latest connection attempt (connect()): its generation, its client, the account it restores and the sign-in version it read.
     * [ended] once its own catch or finally began: an ended attempt's rotation is its catch's to keep (an IOException), or never (a refused
     * sign-in), so a start-over keeps nothing for it.
     */
    private class ConnectAttempt(val generation: Int, val client: BeaconClient, val owner: Scope?, val credentialVersion: Long?) {
        @Volatile var ended = false
    }
    @Volatile private var connectAttempt: ConnectAttempt? = null
    /** The connection attempt ([originGeneration]) that was still in flight when a press found Hermes unreachable: stuck in that outage. */
    private var unreachableAttempt = -1
    /**
     * A call press's start-over ([awaitCallConnection]): only where the app would reconnect by itself (signed in, not in setup, no client).
     * The superseded attempt's sign-in rotation is kept first (REVIEW-F7 P3-2), only while that attempt is still in flight; if it connected
     * meanwhile, nothing starts over.
     */
    private suspend fun startOverAttempt(origin: String): Boolean {
        if (client != null || !reconnectOnNetworkReturn(_ui.value.copy(loading = false))) return false
        val superseded = connectAttempt?.takeIf { it.generation == originGeneration && !it.ended }
        superseded?.let { a -> keepSupersededRotation(a.client, a.owner) { auth -> persistAuthIfCurrent(auth, a.credentialVersion, snapshot = true) } }
        if (client != null || superseded != null && superseded.generation != originGeneration) return false
        connect(origin)
        return true
    }
    /** Where a Beacon call is: opening its chat, watching the start it asked for, or deciding after that start was refused ([callBeacon]). */
    private enum class BeaconCallPhase { Opening, Watching, Deciding }
    @Volatile private var beaconCallPhase = BeaconCallPhase.Opening
    @Volatile private var beaconCallAskedAgain = false
    /**
     * [kept] is gone from Hermes: this phone stops keeping it and the Beacon tab opens the account's current Beacon chat (resolveBeacon: the
     * registered one, else a new one), as when Hermes forgets the Beacon chat before its first turn (recoverMissingInitialShell). Returns that
     * chat once open ([beaconChatOpened]).
     */
    private suspend fun replaceGoneBeaconChat(kept: Pair<BeaconClient, String>): String? {
        val scope = kept.first.scope.session(kept.second)
        fun showing() = _ui.value.tab == "Beacon" && (client?.scope ?: cachedOwnerScope) == kept.first.scope
        fun replacing() = showing() && _chat.value.sessionId in setOf(kept.second, "")
        if (replacing()) {
            if (!keepBeaconTransfer(scope, ::replacing)) return null
            forgetBeaconChat(kept.first.scope, kept.second)
            if (replacing()) openBeacon()
        }
        return beaconChatOpened()
    }
    /**
     * The Beacon chat once it is open, by the same rule the chat uses before sending; "" when the owner left the tab (that cancels the call),
     * null when it did not open in time or [stop] says the chat it is opening was refused.
     */
    private suspend fun beaconChatOpened(stop: () -> Boolean = { false }): String? = withTimeoutOrNull(10_000L) {
        while (true) {
            if (_ui.value.tab != "Beacon" || !_ui.value.authenticated) return@withTimeoutOrNull ""
            if (!_ui.value.beaconOpening && _chat.value.sessionId.isNotBlank() && _chat.value.streamId.isBlank() && isBeaconConversation()) return@withTimeoutOrNull _chat.value.sessionId
            if (stop()) return@withTimeoutOrNull null
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") ""
    }
    /** The Beacon chat this phone keeps for the signed-in account, with its client; null when it has none yet or the assistant uses another profile (opening switches first). */
    private suspend fun keptBeaconChat(): Pair<BeaconClient, String>? {
        val c = client ?: return null
        if (c.scope.profile != preferences.value.assistantProfile) return null
        val id = preparedBeacon?.takeIf { it.first == c.scope }?.second ?: try { readBeaconSession(c.scope) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        return id?.takeIf { it.isNotBlank() }?.let { c.detached() to it }
    }
    /**
     * Whether Hermes still has [id] for a call ([beaconChatCallable]). Registered this run, or its call context read by the prewarm just now:
     * held, with no request ([quick]). Otherwise the registration the app makes anyway, and, when Hermes has no saved chat by that id, the
     * live draft asked of Hermes on a client of its own: this model's own client may still hold the chat open from earlier in the run and
     * would answer from that (device, fix4: a chat deleted elsewhere read as live). The resume keeps a real draft open when that client goes.
     */
    private suspend fun beaconChatHeld(c: BeaconClient, id: String, quick: Boolean = true): Boolean? {
        val scope = c.scope.session(id)
        if (quick && (registeredAssistantChats.contains(scope.key) || app.calls.prewarm.context(scope, preferences.value.ownerName) != null)) return true
        val probe = c.detached()
        return try {
            beaconChatCallable(dev.hermes.android.voice.callStartReachMs(callbackAnswer = false),
                register = { c.nativeRequest("/api/plugins/beacon/mobile/assistant-conversation", "PUT", json("profile" to c.scope.profile, "session_id" to id)); registeredAssistantChats.add(scope.key) },
                resume = { probe.nativeSession(id, lazy = true) })
        } finally { probe.closeNative() }
    }
    /** P0-1: Beacon chats Hermes answered gone this run (scope keys): never taken back from the plugin's registry or an adopt, which may still name one. */
    private val goneBeaconChats = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** This phone stops keeping [id] as [owner]'s Beacon chat (its saved copy stays), so the next opening finds the current one as a fresh start does. */
    private suspend fun forgetBeaconChat(owner: Scope, id: String) {
        if (readBeaconSessionHandle(owner) == id) clearBeaconSessionHandle(owner)
        if (preparedBeacon?.let { it.first == owner && it.second == id } == true) preparedBeacon = null
        registeredAssistantChats.remove(owner.session(id).key)
        try { if (readBeaconCache(owner) == id) { app.cache.delete(owner, BEACON_SESSION_CACHE_KIND, id); app.cache.delete(owner, LEGACY_BEACON_SESSION_CACHE_KIND, id) } }
        catch (e: Throwable) { if (e is CancellationException) throw e }
    }
    fun navigate(destination: String) {
        if (destination == "Bots") { toggleBotMode(); return }
        if (destination == "Vault" && _ui.value.tab == "More") clearSettingsProfile(keepLists = true)
        val route = dev.hermes.android.ui.navigationRoute(destination)
        if (route.tab == "More") more(route.page) else tab(route.tab)
    }
    fun more(value: String) {
        if (_ui.value.botModeSwitching) return
        if (leavesChat(_ui.value.tab, "More")) clearMessage()
        if (_ui.value.tab == "More" && _ui.value.morePage == value) { toggleSessions(false); return }
        if (_ui.value.tab != "More" || value == "Bots") clearSettingsProfile(keepLists = true)
        ++settingsNavigation
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
        ++settingsNavigation
        val owner = (client?.scope ?: cachedOwnerScope)?.key.orEmpty()
        val history = saved.get<String>("settings-navigation")?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.string("scope") == owner }
        val routes = history?.optJSONArray("routes") ?: JSONArray()
        val previous = if (routes.length() > 0) routes.remove(routes.length() - 1) as? JSONObject else null
        val targetTab = previous?.string("tab")?.let(::migratedTab) ?: if (_ui.value.morePage.isBlank()) "Chat" else "More"
        val targetPage = previous?.string("page") ?: dev.hermes.android.ui.settingsParent(_ui.value.morePage)
        if (targetTab != "More" || targetPage == "Bots") clearSettingsProfile(keepLists = true)
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
    private fun returnFromAssistant(): Job {
        // A New chat or New thread asked for right after leaving Beacon's chat is newer than this return, so it wins.
        // shortcut: when Beacon runs in its own profile, that new chat is made in Beacon's profile, not the chats' one, and the app stays there; make New chat wait for this return's profile switch if separate Beacon profiles become common.
        val navigation = navigationGeneration
        return viewModelScope.launch {
            val back = assistantReturn() ?: return@launch
            setAssistantReturn(null); persistAssistantReturn(null); _ui.update { it.copy(chatProfile = "") }
            if (navigationGeneration != navigation) return@launch
            val profile = back.string("profile")
            if (profile.isNotBlank() && (client?.scope ?: cachedOwnerScope)?.profile != profile) switchProfile(profile, false).join()
            val session = back.string("session")
            if (session.isNotBlank() && _ui.value.tab == "Chat" && (client?.scope ?: cachedOwnerScope)?.profile == profile) selectSession(session)
            // Nothing to go back to (the chats had an unsent new chat): the assistant's conversation does not stay in Chat; the new chat is prepared as sign-in does.
            // A profile switch has already done this for its own profile, so this then changes nothing.
            else if (session.isBlank() && navigationGeneration == navigation && _ui.value.tab == "Chat") {
                if (isBeaconConversation()) {
                    try { checkpointVisible() } catch (e: Throwable) { if (e is CancellationException) throw e; fail(e); return@launch }
                    if (navigationGeneration != navigation || !isBeaconConversation()) return@launch
                    sessionWatch?.cancel(); ++sessionGeneration; sourceScope = null; clearDetails()
                    _chat.value = ChatState(); _composer.value = TextFieldValue(); _attachments.value = emptyList()
                }
                // TRACE-COLD-121 fix 1: connect() ran this return instead of opening the chat it restored; that chat is opened as connect() opens it.
                else if (sourceScope != null && _chat.value.sessionId.isNotBlank()) { selectSession(_chat.value.sessionId, reopen = true); return@launch }
                // The chat may already be ready (its create failed or Hermes was unreachable while the person typed): the draft saved for it comes back all the same.
                prepareInitialChatIfNone(restoreDraft = true)
            }
        }
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
    fun setBusySendAction(action: String): Job {
        if (action !in setOf("steer", "queue")) return viewModelScope.launch { }
        _busySendOverride.value = action
        return viewModelScope.launch {
            try {
                app.preferences.edit { it[stringPreferencesKey("busy_send_action")] = action }
                if (_busySendOverride.value == action) preferences.first { it.busySendLoaded && it.busySendAction == action }
            } catch (e: Throwable) { if (e is CancellationException) throw e; notice("Couldn't save this setting. Try again.") }
            finally { if (_busySendOverride.value == action) _busySendOverride.value = null }
        }
    }
    fun switchProfile(name: String): Job = viewModelScope.launch {
        if (_ui.value.botModeEnabled) { toggleBotMode().join(); if (_ui.value.botModeEnabled) return@launch }
        switchProfile(name, false).join()
    }
    private fun switchProfile(name: String, retainBotMode: Boolean) = viewModelScope.launch {
        // No client (a cache-first start can show the profile list offline): say so, and leave everything as it is.
        val active = client ?: run { notice("Reconnect before switching profiles. Your drafts are kept."); return@launch }
        clearPendingVoiceKeyRequests()
        checkpointPhoneSettingsNow()
        clearSettingsProfile()
        val c = active.detached(); val oldScope = c.scope; val generation = ++originGeneration
        var left = false
        val credentialVersion = app.auth.authorityEpoch.readVersion()
        val navigation = navigationGeneration
        if (!retainBotMode) botMode.clear()
        cancelStartup()
        cancelNewChatWork()
        // Said only while this switch is still the current one; a switch another tap replaced says nothing.
        fun signInChanged() { if (generation == originGeneration && client?.scope == oldScope) notice("Sign-in changed while switching profiles. Reconnect and try again.") }
        try {
            checkpointVisible()
            if (generation != originGeneration || client?.scope != oldScope) return@launch
            c.post("/api/profile/switch", json("name" to name))
            val bootstrap = c.bootstrap()
            require(c.scope.sameAccount(oldScope)) { "Profile switch changed account identity. Sign in again." }
            if (generation != originGeneration || client?.scope != oldScope) return@launch
            if (!persistAuthIfCurrent(c.auth, credentialVersion, snapshot = true, selectProfileIf = { generation == originGeneration })) {
                signInChanged()
                return@launch
            }
            if (generation != originGeneration || client?.scope != oldScope) return@launch
            val switchedAuth = withContext(Dispatchers.IO) { app.auth.read(c.auth.origin) }.takeIf { it.scope() == c.scope }
            if (switchedAuth == null) { signInChanged(); return@launch }
            val destination = switchedAuth.scope()
            val screenData = cachedScreens(destination)
            val index = try { app.cache.read(destination, "conversation-index")?.let(::JSONObject) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (generation != originGeneration || client?.scope != oldScope) return@launch
            left = true
            sessionWatch?.cancel(); selectionJob?.cancel(); sourceScope = null; ++sessionGeneration
            dropNotSentYet()
            client = profileSwitchClientFactoryForTest?.invoke(switchedAuth) ?: BeaconClient(switchedAuth, app.auth, persistContext = false)
            cachedOwnerScope = null; retireSavedDrafts(null)
            val restoredClient = client ?: return@launch
            _data.value = screenData + swapProfileData(oldScope, destination); _chat.value = ChatState(); _composer.value = TextFieldValue(); _attachments.value = emptyList(); clearDetails()
            _data.update { it + screens.composer(destination) }
            phoneSettings.bind(); phoneSettings.seedSaved()
            _ui.update { it.copy(profile = c.auth.profile, owner = c.auth.owner, bootstrap = bootstrap, beaconOpening = false, beaconError = "", newChatOpening = false, newChatError = "", initialChatReady = false, morePage = if (retainBotMode && navigation == navigationGeneration) "Bots" else if (retainBotMode) it.morePage else "", query = "", sessions = index?.rows("sessions").orEmpty()) }
            viewModelScope.launch { openGlances(destination) }
            restorePhoneSettings(restoredClient)
            if (generation != originGeneration || client !== restoredClient) return@launch
            prepareStartup()
            val next = requireNotNull(client)
            val shell = app.cache.read(next.scope, "new-chat-shell")
            // Coming back to a profile brings back the chat that was open there; an unsent new chat returns with its draft.
            val selected = chatTabSelection(saved.get<String>("session:${next.scope.key}") ?: app.cache.read(next.scope, "selected-session"), readBeaconSession(next.scope))
            if (generation != originGeneration || client !== next) return@launch
            shell?.let { initialShells[next.scope.key] = it }
            if (!retainBotMode) {
                if (_ui.value.tab == "Beacon") openBeacon()
                else if (!selected.isNullOrBlank()) selectSession(selected, closeMenu = false)
                else restoreInitialDraft(next.scope, generation)
            }
            reloadCommandsAfterSwitch(next, generation)
            drainOfflineOutbox()
        } catch (e: Throwable) { if (generation == originGeneration && client?.scope == oldScope) { fail(e) } }
        finally { if (!left) followKeptChat(generation, oldScope) }
    }
    /**
     * The command list of the profile just switched to loads again by itself, a few tries while Hermes settles, until its record is clean: the chat's own load can land
     * while the switch is still finishing, and a failed one leaves "Retry commands" and a blocked first command (G5 check on F10, backlog 54).
     */
    private fun reloadCommandsAfterSwitch(next: BeaconClient, generation: Int) = viewModelScope.launch {
        for (attempt in 0 until COMMAND_RELOAD_TRIES) {
            if (attempt > 0) delay(COMMAND_RELOAD_DELAY_MS)
            if (client !== next || generation != originGeneration) return@launch
            // Quiet: a failed try shows no app-wide error (a background read), and the next try follows.
            load("slash:catalog", "/api/commands/catalog", force = true, quiet = true).join()
            if (dev.hermes.android.ui.recordError(_data.value["slash:catalog"]).isBlank()) return@launch
        }
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
                            "Couldn't reopen your chat. Its draft is kept."
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
    /** Never deleted from the app: Hermes' default, the profile in use, the assistant's, and the one leaving Bot Mode switches back to. */
    fun canDeleteProfile(name: String): Boolean = profileDeletable(name, _ui.value.profile, preferences.value.assistantProfile, normalModeReturn?.string("profile").orEmpty())
    fun deleteProfile(name: String) = profileAdmin.delete(name, preferences.value.assistantProfile, normalModeReturn?.string("profile").orEmpty())
    /** A profile was made, described or deleted: every list that names profiles reads again, and a deleted profile's copies on this phone go. */
    private fun profilesChanged(deleted: String, message: String) {
        if (deleted.isNotBlank() && settingsClient?.scope?.profile == deleted) clearSettingsProfile()
        load("navigation-profiles", "/api/profiles", quiet = true)
        phoneSettings.loadBots()
        // Bot Mode is the plugin's; with it off, a plain Hermes is never asked.
        if (_ui.value.botModeEnabled) botMode.rosterChanged()
        notice(message)
        val account = client?.scope ?: return
        if (deleted.isBlank()) return
        viewModelScope.launch {
            for (kind in listOf("chat-glances", "conversation-index", "selected-session", "new-chat-shell", "initial-draft", "new-chat-attempt")) {
                try { app.cache.delete(account.copy(profile = deleted, session = ""), kind) } catch (e: Exception) { if (e is CancellationException) throw e }
            }
            // Bot Mode opens from its cached roster; the one listing the deleted bot goes, here and in the deleted profile.
            for (profile in setOf(account.profile, deleted)) try { screens.remove(account.copy(profile = profile, session = ""), "bot-mode:roster") } catch (e: Exception) { if (e is CancellationException) throw e }
        }
        // Delete is never offered for the profile in use; if it was removed from elsewhere meanwhile, leave it for the assistant's.
        if (account.profile == deleted) switchProfile(preferences.value.assistantProfile)
    }
    fun showBotMode() {
        if (!_ui.value.botModeEnabled || _ui.value.botModeSwitching) return
        clearSettingsProfile()
        botMode.home(); ++navigationGeneration
        _ui.update { it.copy(tab = "More", morePage = "Bots", showSessions = false) }
        saved["tab"] = "More"; saved["more"] = "Bots"
    }
    fun openBot(bot: BotModeBot) {
        if (!_ui.value.botModeEnabled || _ui.value.botModeSwitching) return
        clearSettingsProfile()
        botMode.back(); ++navigationGeneration
        _ui.update { it.copy(tab = "More", morePage = "Bots", showSessions = false) }
        saved["tab"] = "More"; saved["more"] = "Bots"
        botMode.openBot(bot)
    }
    fun openBotSpace(id: String) {
        if (!_ui.value.botModeEnabled || _ui.value.botModeSwitching) return
        clearSettingsProfile()
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
            val pluginMessage = if (optional.string("plugin") == "absent") "Calls need Beacon on Hermes." else ""
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
                            // A read that runs past the budget is not cancelled: the warm-up shares its job, and cancelStartup() still ends it. Only a finished wait leaves the set.
                            try { job.join() } finally { if (currentCoroutineContext().isActive) preloadJobs.remove(job) }
                        } finally { slots.release() }
                    } }
                }
            }
        }
        startupJob = viewModelScope.launch {
            // Extra history work expires; navigation owns its own live requests. The phone's limits are read now, in the foreground, and nothing here runs in the background.
            val conditions = readWarmConditions(app)
            val savedAt: suspend (Scope) -> Long = { session -> try { app.cache.updatedAt(session, "session") ?: 0L } catch (e: CancellationException) { throw e } catch (_: Exception) { 0L } }
            // Each read has its own short-lived client: two chats read at once must not share one connection's state.
            suspend fun warm(id: String, activityMs: Long) {
                // Not worth a read: this is not the current account, the chat is open (and live), or its saved copy is already newer than its last activity.
                if (!current() || sourceScope == owner.session(id) || transcriptFresh(savedAt(owner.session(id)), activityMs, System.currentTimeMillis())) return
                val reader = active.detached()
                try {
                    val response = reader.get("/api/session", mapOf("session_id" to id), maxBytes = 1024 * 1024)
                    if (current() && sourceScope != owner.session(id)) app.cache.writeSession(owner.session(id), response.toString())
                } catch (e: Throwable) { if (e is CancellationException) throw e; if (current() && e is ApiFailure && e.status == 401) fail(e) }
                finally { reader.closeNative() }
            }
            fun activityOf(id: String) = _ui.value.sessions.firstOrNull { it.string("session_id", it.string("id")) == id }?.let { dev.hermes.android.ui.rowActivityMillis(it) } ?: 0L
            var beacon = ""
            if (owner.profile == preferences.value.assistantProfile) {
                try {
                    beacon = preparedBeacon?.takeIf { it.first == owner }?.second ?: readBeaconSession(owner).orEmpty()
                    if (beacon.isNotBlank() && current()) {
                        preparedBeacon = owner to beacon
                        // The Beacon screen trusts only the saved handle; a cached chat restores it as connect() does.
                        writeBeaconSessionHandle(owner, beacon)
                    }
                } catch (e: Throwable) { if (e is CancellationException) throw e; if (current() && e is ApiFailure && e.status == 401) fail(e) }
            }
            if (!current()) return@launch
            coroutineScope {
                // The Beacon chat has its own short budget and runs beside the others, so a slow answer there cannot use up the time the recent chats need.
                if (beacon.isNotBlank()) launch { withTimeoutOrNull(WARM_BEACON_BUDGET) { warm(beacon, activityOf(beacon)) } }
                // The recent chats, two at a time under their own budget: the top few the list shows, less the ones whose saved copy is already current.
                launch {
                    // The list is waited for on its own, so a slow list does not use up the time the reads have.
                    withTimeoutOrNull(WARM_INDEX_WAIT) { index.join() }
                    withTimeoutOrNull(WARM_CHAT_BUDGET) {
                        if (!current()) return@withTimeoutOrNull
                        val rows = _ui.value.sessions.map { row -> WarmRow(row.string("session_id", row.string("id")), dev.hermes.android.ui.rowActivityMillis(row)) }
                        val limit = warmChatLimit(conditions)
                        val saved = startupWarmCandidates(rows, beacon, limit).associate { it.id to savedAt(owner.session(it.id)) }
                        val targets = startupWarmTargets(rows, beacon, limit, { saved[it] ?: 0L }, System.currentTimeMillis())
                        val slots = kotlinx.coroutines.sync.Semaphore(WARM_CHAT_PARALLELISM)
                        coroutineScope {
                            targets.forEach { row -> launch { slots.acquire(); try { warm(row.id, row.activityMs) } finally { slots.release() } } }
                        }
                    }
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
            assistantConversationToAdopt(id, response.string("conversation_id"))?.takeIf { c.scope.session(it).key !in goneBeaconChats }?.let { shared ->
                writeBeaconSessionHandle(c.scope, shared)
                optionalLocalSave { app.cache.write(c.scope, BEACON_SESSION_CACHE_KIND, shared, protected = true) }
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
        preparedBeacon?.takeIf { it.first == c.scope && c.scope.session(it.second).key !in goneBeaconChats }?.let { return@withLock it.second }
        val bound = readBeaconSession(c.scope)?.takeUnless { c.scope.session(it).key in goneBeaconChats }
        if (!bound.isNullOrBlank()) { require(current()) { "Account changed while preparing ${dev.hermes.android.data.currentAssistantName}" }; preparedBeacon = c.scope to bound; writeBeaconSessionHandle(c.scope, bound); registerAssistantChat(c, bound); return@withLock bound }
        // One Beacon chat per account: a phone without one takes the registered chat before it starts another (never one Hermes said is gone).
        accountBeaconChat(c)?.takeIf { c.scope.session(it).key !in goneBeaconChats }?.let { shared ->
            require(current()) { "Account changed while preparing ${dev.hermes.android.data.currentAssistantName}" }
            writeBeaconSessionHandle(c.scope, shared)
            optionalLocalSave { app.cache.write(c.scope, BEACON_SESSION_CACHE_KIND, shared, protected = true) }
            preparedBeacon = c.scope to shared
            registerAssistantChat(c, shared)
            return@withLock shared
        }
        // A create whose answer never came (cut off, the app stopped) leaves its marker with its request id: Hermes' retry identity, so the same create returns
        // the chat that one made, or makes it now, and no second Beacon chat starts. A marker without one (1.2.0's "unknown") can't be retried: it no longer
        // blocks Beacon for good (demo, fix 6: "Can't open Beacon · Hermes couldn't do that" after a create was cut off, Retry useless); a new create starts.
        val kept = beaconOpeningKept(readBeaconOpening(c.scope))
        var request = kept ?: java.util.UUID.randomUUID().toString()
        currentCoroutineContext().ensureActive()
        require(current()) { "Account changed while preparing ${dev.hermes.android.data.currentAssistantName}" }
        app.cache.write(c.scope, "beacon-opening", BEACON_OPENING_REQUEST + request, true)
        fun open(request: String) = JSONObject().apply { if (fixed.assistantModel.isNotBlank()) { put("model", fixed.assistantModel); if (fixed.assistantProvider.isNotBlank()) put("model_provider", fixed.assistantProvider) }; put("request_id", request) }
        val result = try { c.post("/api/beacon/open", open(request)) } catch (e: ApiFailure) {
            // Hermes refused the kept id itself (its chat was deleted meanwhile, or the request changed): it can't open Beacon any more, so a new create starts once.
            if (kept == null || !beaconOpeningRetryRefused(e)) throw e
            request = java.util.UUID.randomUUID().toString()
            app.cache.write(c.scope, "beacon-opening", BEACON_OPENING_REQUEST + request, true)
            c.post("/api/beacon/open", open(request))
        }
        val id = result.string("session_id", result.string("conversation_id", result.optJSONObject("session")?.string("session_id").orEmpty()))
        require(id.isNotBlank()) { "${dev.hermes.android.data.currentAssistantName} did not return a conversation" }
        val sessionScope = c.scope.session(id)
        clientCreatedSessions.add(sessionScope.key)
        // The owned creation already happened; a newer navigation must not discard its identity.
        writeBeaconSessionHandle(c.scope, id)
        if (client?.scope == c.scope) preparedBeacon = c.scope to id
        runCatching { app.cache.write(c.scope, BEACON_SESSION_CACHE_KIND, id, protected = true); app.cache.delete(c.scope, "beacon-opening"); app.cache.delete(c.scope, LEGACY_BEACON_OPENING_CACHE_KIND); app.cache.write(sessionScope, "client-created-shell", "1", protected = true) }.onFailure { if (it is CancellationException) throw it; if (client?.scope == c.scope) notice("${dev.hermes.android.data.currentAssistantName} opened; recovery checkpoint failed") }
        id
    }
    // What the chat list shows beside each chat (its latest line, when the owner last looked): kept encrypted per profile on this phone, never sent to Hermes.
    private val _glances = MutableStateFlow(ChatGlances())
    internal val glances = _glances.asStateFlow()
    private val glanceLock = Mutex()
    private var glanceAccount = ""
    private val glanceLoaded = HashMap<String, Scope>()
    private val glanceDirty = LinkedHashMap<String, Scope>()
    // Profiles whose name this run has already put in the account row; cleared wherever glanceLoaded is.
    private val glanceRecorded = HashSet<String>()
    private var glanceWrite: Job? = null
    // Per row ("profile scope key:id"), the list activity whose newest line this run has already read.
    private val previewsRead = HashMap<String, Long>()
    private var previewPrefetch: Job? = null
    /** Loads a profile's stored glances once per account; a different account starts empty. Unreadable storage is the same as none. */
    private suspend fun openGlances(profileScope: Scope) = glanceLock.withLock {
        val account = profileScope.copy(profile = "", session = "").key
        if (glanceAccount != account) { glanceAccount = account; glanceLoaded.clear(); glanceRecorded.clear(); glanceDirty.clear(); _glances.value = ChatGlances(account = account) }
        if (glanceLoaded.put(profileScope.key, profileScope) == null) {
            val stored = try { app.cache.read(profileScope, "chat-glances")?.let { decodeGlances(profileScope.profile, it) } }
                catch (e: Exception) { if (e is CancellationException) { glanceLoaded.remove(profileScope.key); throw e }; null }
            if (stored != null) {
                _glances.update { it.merged(stored) }
                // Rows written before the account row existed are named as soon as their profile is opened.
                try { rememberGlanceProfile(profileScope) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* the next write records it */ }
            }
        }
    }
    /** The account row names every profile with previews on this phone, and is written before that profile's first preview row, so sign-out never has to guess. */
    private val glanceIndexLock = Mutex()
    private suspend fun rememberGlanceProfile(profileScope: Scope) = glanceIndexLock.withLock {
        if (profileScope.key !in glanceRecorded) {
            val account = profileScope.copy(profile = "", session = "")
            val recorded = decodeGlanceProfiles(app.cache.read(account, GLANCE_PROFILES_KIND))
            if (profileScope.profile.isNotBlank() && profileScope.profile !in recorded) app.cache.write(account, GLANCE_PROFILES_KIND, glanceProfilesJson(recorded + profileScope.profile), protected = true)
            glanceRecorded.add(profileScope.key)
        }
    }
    private fun queueGlanceWrite(profileScope: Scope) {
        glanceDirty[profileScope.key] = profileScope
        glanceWrite?.cancel()
        glanceWrite = viewModelScope.launch {
            delay(1_000)
            val snapshot = _glances.value
            // Derived data: a failed write is retried by the next change, never surfaced.
            glanceDirty.values.toList().forEach { dirty ->
                try { rememberGlanceProfile(dirty); app.cache.write(dirty, "chat-glances", snapshot.encodeFor(dirty.profile), protected = true); glanceDirty.remove(dirty.key) }
                catch (e: Exception) { if (e is CancellationException) throw e }
            }
        }
    }
    /** Sign-out and device removal: the account's chat lists, previews and read marks leave the phone with its screens,
     *  for every profile the account row, the profile list or this run names, and a pending write cannot bring them back. */
    private suspend fun forgetGlances(scope: Scope, removal: DeviceRemovalAuthority? = null, chats: Boolean = true) {
        fun mutate(action: () -> Unit): Boolean = removal?.ifCurrent(action) ?: run { action(); true }
        val account = scope.copy(profile = "", session = "")
        val listed = _data.value["navigation-profiles"]?.rows("profiles").orEmpty().map { it.string("name") }
        // A preview read still running must not bring a line back after the wipe.
        val preview = previewPrefetch
        if (!mutate {}) return
        preview?.cancelAndJoin()
        if (!mutate { previewsRead.clear() }) return
        var wiped = true
        val stored = glanceLock.withLock {
            // No write may be running while the account row is read, or it could name a profile the wipe then misses.
            val pendingWrite = glanceWrite
            if (!mutate {}) return@withLock emptyList<Scope>()
            pendingWrite?.cancelAndJoin()
            if (!mutate { glanceDirty.clear() }) return@withLock emptyList<Scope>()
            val recorded = try { decodeGlanceProfiles(app.cache.read(account, GLANCE_PROFILES_KIND)) } catch (e: CancellationException) { throw e } catch (_: Exception) { wiped = false; emptyList<String>() }
            val profiles = glanceProfilesToForget(scope, glanceLoaded.values, listed, recorded)
            if (!mutate {
                if (glanceAccount == account.key) { glanceLoaded.clear(); glanceRecorded.clear(); _glances.value = ChatGlances(account = account.key) }
            }) return@withLock emptyList<Scope>()
            profiles
        }
        forgetAccountChats(app.cache, account, stored, removal, chats, recorded = wiped, found = chatsToForget)
        // A profile opened while the rows were going was recorded in a row that is now gone.
        mutate { glanceRecorded.clear() }
    }
    /** The account the glances belong to; the list ignores any other account's. */
    internal fun glanceAccountKey(): String = (client?.scope ?: cachedOwnerScope)?.copy(profile = "", session = "")?.key.orEmpty()
    /** Loads this profile's stored glances, so the chat list opens with its previews and unread marks already in place (also offline). */
    fun loadGlances() {
        val profileScope = (client?.scope ?: cachedOwnerScope)?.copy(session = "") ?: return
        viewModelScope.launch { openGlances(profileScope) }
    }
    /** The open chat has been looked at: keep its latest line and that everything up to it is read. */
    fun noteChatViewed(sessionId: String, latest: Message?) {
        val chatScope = authenticatedScope()?.takeIf { it.session == sessionId } ?: return
        val profileScope = chatScope.copy(session = "")
        viewModelScope.launch {
            openGlances(profileScope)
            if (authenticatedScope() != chatScope) return@launch
            val row = _ui.value.sessions.firstOrNull { sessionRowId(it) == sessionId && it.string("profile", profileScope.profile) == profileScope.profile }
            val at = latest?.let { epochMillis(it.recordedAt) } ?: 0L
            val (line, role) = dev.hermes.android.ui.chatListLine(latest)
            val before = _glances.value
            val after = before.viewed(profileScope.profile, sessionId, line, role, at, maxOf(at, row?.let(::rowActivityMillis) ?: 0L))
            if (after !== before) { _glances.value = after; queueGlanceWrite(profileScope) }
        }
    }
    /**
     * The chat list shows each chat's newest line, not Hermes' first prompt: for the top rows whose stored line is missing or
     * older than their activity, read only the newest few messages, in the background, into the same encrypted glances the list
     * opens with. Nothing shows while it runs, read marks are untouched, and a failed read keeps the line already shown.
     */
    private fun prefetchPreviews(source: BeaconClient, scope: Scope, rows: List<JSONObject>, current: () -> Boolean) {
        val profileScope = scope.copy(session = "")
        previewPrefetch?.cancel()
        previewPrefetch = viewModelScope.launch {
            openGlances(profileScope)
            val wanted = previewPrefetchTargets(rows, _glances.value, profileScope.profile) { id -> previewsRead["${profileScope.key}:$id"] }
            if (wanted.isEmpty()) return@launch
            val reader = source.detached()
            try {
                for (row in wanted) {
                    if (!current()) return@launch
                    val id = sessionRowId(row)
                    val page = try {
                        reader.nativeGet("/api/sessions/$id/messages", mapOf("profile" to profileScope.profile, "order" to "latest", "limit" to "$PREVIEW_READ_WINDOW", "offset" to "0", "inline_images" to "false"), maxBytes = PREVIEW_READ_MAX_BYTES)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // A sign-in that ended stops the reads; any other failure only skips this row until the next list refresh.
                        if (endsSignIn(e)) return@launch else continue
                    }
                    if (!current() || page.string("profile").let { it.isNotBlank() && it != profileScope.profile }) continue
                    val messages = page.rows("messages").mapIndexed { index, item -> Message.from(item, index, id) }
                    val latest = visibleTranscriptMessages(messages).lastOrNull { it.role == "user" || it.role == "assistant" && it.text.isNotBlank() }
                    previewsRead["${profileScope.key}:$id"] = rowActivityMillis(row)
                    val (line, role) = dev.hermes.android.ui.chatListLine(latest)
                    val at = latest?.let { epochMillis(it.recordedAt) } ?: 0L
                    val account = profileScope.copy(profile = "").key
                    var changed = false
                    // Another account's glances are never written; a chat opened meanwhile keeps the newer line it noted.
                    _glances.update { now -> if (now.account != account) now else withPrefetchedPreview(now, profileScope.profile, id, line, role, at).also { changed = it !== now } }
                    if (changed) queueGlanceWrite(profileScope)
                }
            } finally { reader.closeNative() }
        }
    }
    /** The first full list of a profile is the baseline: chats already there are not "new" on a fresh install. */
    private suspend fun noteIndexListed(scope: Scope, rows: List<JSONObject>) {
        val profileScope = scope.copy(session = "")
        openGlances(profileScope)
        val newest = rows.filter { it.string("profile", scope.profile) == scope.profile }.maxOfOrNull(::rowActivityMillis) ?: 0L
        val before = _glances.value
        val after = before.withBase(scope.profile, newest)
        if (after !== before) { _glances.value = after; queueGlanceWrite(profileScope) }
    }
    private var indexFetch: Job? = null
    private var indexFetchKey = ""
    private var indexReadAt = 0L
    /** Defaults keep the drawer's current view (archived/threads), so a background refresh never swaps the list under it. */
    /** [joinActive]: a read of the same list already on its way is the answer (its loading row stays), as the drawer opening asks (D4). */
    fun refreshSessions(query: String = _ui.value.query, archived: Boolean = sessionIndexArchived, force: Boolean = true, threads: Boolean = sessionIndexThreads, allProfiles: Boolean = sessionIndexAllProfiles, joinActive: Boolean = false): Job {
        val active = client ?: return viewModelScope.launch {}
        val c = active.detached()
        val scope = c.scope
        val generation = originGeneration
        val credentialVersion = app.auth.authorityEpoch.readVersion()
        val hidden = preferences.value.historyHiddenSources.sorted().joinToString(",")
        val order = preferences.value.historyOrder
        val fetchKey = "${scope.key}:$generation:$query:$archived:$threads:$allProfiles:$hidden:$order"
        val filterRequest = historyFilterRequest(query, archived, threads, hidden, order)
        val filterVersion = screens.readVersion(scope, filterRequest)
        if (indexFetchKey == fetchKey) {
            if (!force || joinActive) indexFetch?.takeIf { it.isActive }?.let { return it }
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
            val listedAfter = System.currentTimeMillis()
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
            if (!archived) forgetUnarchivedElsewhere(serverRows, scope, listedAfter)
            val rows = withArchivedHere(visibleSessionRows(serverRows, confirmed, query, archived), scope, profileIds, query, archived)
            indexReadAt = System.currentTimeMillis()
            val result = json("scope_key" to scope.key, "archived" to archived, "threads" to threads,
                "all_profiles" to allProfiles, "hidden_sources" to hidden, "order" to order, "sessions" to JSONArray(rows))
            if (!allProfiles && (query.isNotBlank() || archived || threads))
                screens.put(scope, "history-filter", filterRequest, indexReadAt, result, filterVersion)
            storeData("history:search", result.put("query", query))
            _ui.update { it.copy(sessions = if (query.isBlank()) rows else it.sessions, connected = true, assistantSessionId = assistantSessionId, historyUnreadable = unreadable) }
            if (query.isBlank() && !archived) refreshLostChats(active, scope, rows)
            if (current()) {
                if (!persistAuthIfCurrent(c.auth, credentialVersion, snapshot = true) || !current()) return@supervisorScope
                if (!allProfiles && query.isBlank() && !archived && !threads) {
                    app.cache.write(scope, "conversation-index", json("sessions" to JSONArray(rows)).toString())
                    noteIndexListed(scope, rows)
                    prefetchPreviews(c, scope, rows) { client === active && generation == originGeneration && _ui.value.authenticated }
                }
            }

          } } catch (e: Throwable) { if (e is CancellationException) throw e; if (current()) fail(conversationActionFailure("/api/sessions", e)) }
          finally { if (request == sessionIndexRequest) _ui.update { it.copy(historyLoading = false) } }
        }.also { indexFetch = it }
    }
    /** D5: chats archived from this phone (its account and profile, the row as listed). Hermes' hidden-chat scan is bounded and can lag, so they show under Archived at once and stay out of All; Unarchive drops them. */
    private val archivedHere = LinkedHashMap<String, Pair<Scope, JSONObject>>()
    private fun rememberArchived(target: Scope, id: String, archived: Boolean) {
        val key = target.session(id).key
        if (!archived) { archivedHere.remove(key); return }
        val known = _ui.value.sessions.firstOrNull { sessionRowId(it) == id && it.string("profile", target.profile) == target.profile }
            ?: _data.value["history:search"]?.rows("sessions")?.firstOrNull { sessionRowId(it) == id && it.string("profile", target.profile) == target.profile }
        val row = (known?.copy() ?: json("title" to (_chat.value.title.takeIf { _chat.value.sessionId == id }.orEmpty()))).put("session_id", id).put("profile", target.profile).put("archived", true)
            .put(ARCHIVED_HERE_AT, System.currentTimeMillis())
        archivedHere.remove(key); archivedHere[key] = target.copy(session = "") to row
        while (archivedHere.size > 50) archivedHere.remove(archivedHere.keys.first())
    }
    /**
     * P3-3: Hermes lists a chat this phone archived as not archived again, in a list asked for well after the archive: it was unarchived
     * elsewhere (the Hermes desktop), so this phone forgets it.
     */
    private fun forgetUnarchivedElsewhere(serverRows: List<JSONObject>, scope: Scope, listedAfter: Long) {
        val listed = serverRows.filter { !it.optBoolean("archived") }.map { sessionRowKey(it, scope.profile) }.toSet()
        archivedHere.entries.removeAll { (_, entry) ->
            entry.first.copy(profile = scope.profile) == scope.copy(session = "") && sessionRowKey(entry.second) in listed &&
                listedAfter - entry.second.optLong(ARCHIVED_HERE_AT) > ARCHIVE_SETTLE_MS
        }
    }
    private fun withArchivedHere(rows: List<JSONObject>, scope: Scope, profiles: List<String>, query: String, archived: Boolean): List<JSONObject> {
        val mine = archivedHere.values.filter { (owner, _) -> owner.copy(profile = scope.profile) == scope.copy(session = "") && owner.profile in profiles }.map { it.second }
        if (mine.isEmpty()) return rows
        val keys = mine.map { sessionRowKey(it) }.toSet()
        if (!archived) return rows.mapNotNull { row -> if (sessionRowKey(row, scope.profile) !in keys) row else if (query.isBlank()) null else row.copy().put("archived", true) }
        val listed = rows.map { sessionRowKey(it, scope.profile) }.toSet()
        return mine.filter { sessionRowKey(it) !in listed && (query.isBlank() || it.string("title").contains(query.trim(), ignoreCase = true)) }.map { it.copy() } + rows
    }
    private fun publishConfirmedSession(owner: Scope, id: String, response: JSONObject, accepted: Boolean = false) {
        if (client?.scope != owner || !_ui.value.authenticated) return
        val session = response.optJSONObject("session") ?: response
        val existing = _ui.value.sessions.firstOrNull { it.string("profile", owner.profile) == owner.profile && sessionRowId(it) == id }
        val row = historyRowOf(existing ?: session).put("session_id", id).put("profile", owner.profile)
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
    // The account whose last new-chat create never left the phone: typing waits for Hermes to be reachable again instead of retrying on every key.
    private var unreachableCreateOwner: String? = null
    private val _profileTurningOn = MutableStateFlow(false)
    /** True while the plugin is turning Beacon on for the open profile (the card says so and offers nothing to tap). */
    val profileTurningOn: StateFlow<Boolean> = _profileTurningOn
    /** Opens (or resumes) the empty chat behind a new conversation; null when nothing is to be done now. Returning to the app and the chat screen itself ask again, so a missed trigger never leaves it unopened. */
    internal fun openInitialChat(): Job? {
        val owner = client?.scope ?: return null
        val ui = _ui.value
        return when (initialChatStep(unreachableCreateOwner == owner.key, ui.connected, foreground, canComposeInitialChat(), ui.setupWizard, ui.newChatOpening,
            ui.newChatError.isNotBlank(), initialCreateAttempts.containsKey(owner.key), initialShells.containsKey(owner.key), selectionJob?.isActive == true)) {
            InitialChatStep.Wait -> null
            InitialChatStep.Resume -> resumeInitialChat()
            InitialChatStep.Create -> createNewChat(json("worktree" to false), true)
        }
    }
    /**
     * The Attach tap on a new chat with no conversation yet: the open that starts now, or the one already running (so the tap waits for it and the choices follow);
     * a failed open says so as New conversation does, never silence. Null when nothing can be waited for.
     */
    internal fun initialChatOpening(): Job? = openingInitialChat()?.let { opening ->
        // The create job ends once it has started selecting the conversation it made; the tap waits for that selection too, so the choices follow a conversation that exists.
        viewModelScope.launch { opening.join(); selectionJob?.join() }
    }
    private fun openingInitialChat(): Job? {
        openInitialChat()?.let { return it }
        val owner = (client?.scope ?: cachedOwnerScope) ?: return null
        newChatJobs[owner.key]?.takeIf { !it.isCompleted }?.let { return it }
        if (_ui.value.newChatOpening) return viewModelScope.launch { _ui.first { !it.newChatOpening } }
        if (_ui.value.newChatError.isNotBlank()) notice(newChatOpenNotice(_ui.value.newChatError))
        return null
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
                    if (initialCreateAttempts.containsKey(owner.key)) {
                        // A create whose answer never came back (the app was killed, or Hermes did not answer in time) left its attempt id on this phone. The tap asks Hermes again with
                        // that id, its own retry identity: Hermes returns the chat the first create made, or makes one, so the tap is never a dead end. The marker keeps the plain
                        // request's id only, so that request settles first; a thread or workspace chat the tap asked for follows.
                        createNewChat(json("worktree" to false), initial = true).join()
                        if (initialCreateAttempts.containsKey(owner.key) || options.length() == 0 || client !== active || originGeneration != origin) return@launch
                        newChat(options).join()
                        return@launch
                    }
                    // A legitimate New intent from Tasks/Spaces keeps the owner's unsent
                    // draft; explicit workspace/worktree/model options pass through intact.
                    if (options.length() > 0 || !initialShells.containsKey(owner.key)) createNewChat(options, initial = true).join()
                } catch (e: Throwable) { if (client === active && originGeneration == origin && navigationGeneration == navigation && initialDraftOwner() == owner) fail(e) }
            }
        }
        client?.scope?.key?.let { key -> newChatJobs[key]?.takeIf { !it.isCompleted }?.let { return it } }
        // Options (a thread's title, a workspace) ask for a different chat; a plain New conversation on an unsent plain chat keeps it.
        val openScope = sourceScope
        if (body.length() == 0 && openScope != null && _ui.value.tab == "Chat" && openScope.copy(session = "") == client?.scope && _chat.value.sessionId == openScope.session &&
            readBeaconSessionHandle(openScope.copy(session = "")) != openScope.session &&
            reusableEmptyChat(clientCreatedSessions.contains(openScope.key), _chat.value, pending.containsKey(openScope.key), keptApartShells.contains(openScope.key))
        ) {
            _ui.update { it.copy(showSessions = false) }
            return viewModelScope.launch {}
        }
        ++navigationGeneration
        return createNewChat(body, initial = false)
    }
    /** A thread is a channel from the start: Hermes saves it now (not at its first message), so a scheduled post can land in it at once.
     *  Best effort: without the Beacon plugin it stays a draft until its first message, as before. */
    fun reserveThread(sessionId: String) {
        val active = client ?: return
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            try { active.nativeRequest("/api/plugins/beacon/mobile/sessions/$sessionId/reserve", "POST", JSONObject()) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { /* the plugin is optional; the first message saves it */ }
        }
    }
    /** A named thread (Relay-style): a new native chat with its own source, titled by the user, not by Hermes's auto-title. */
    fun newThread(name: String): Job {
        val title = dev.hermes.android.ui.uniqueThreadTitle(name, _ui.value.sessions.map { it.string("title") })
        if (title.isBlank()) return viewModelScope.launch { notice("Name the thread first") }
        return newChat(json("title" to title, "thread" to true))
    }
    /** Hermes drops a first-turn title another conversation already holds (titles are unique per profile); keep the thread named. */
    private suspend fun keepThreadTitle(c: BeaconClient, scope: Scope, wanted: String, actual: String) {
        // The wanted name leaves the list only once it is given or already there: a rename that did not happen (a failed request, or the stream cancelled, which throws from the
        // first request) leaves it for the thread's next finished turn.
        if (actual == wanted) { pendingThreadTitles.remove(scope.key, wanted); threadTitleFailures.remove(scope.key); return }
        val candidates = listOf(wanted) + (2..9).map { "$wanted $it" }
        for ((index, candidate) in candidates.withIndex()) {
            try {
                c.post("/api/session/rename", json("session_id" to scope.session, "title" to candidate))
                pendingThreadTitles.remove(scope.key, wanted); threadTitleFailures.remove(scope.key)
                if (sourceScope == scope) _chat.update { it.copy(title = candidate) }
                refreshSessions()
                return
            } catch (e: Throwable) { if (e is CancellationException || e !is ApiFailure || e.status != 4022 || index == candidates.lastIndex) throw e }
        }
    }
    /** Which project (if any) holds a conversation, as Hermes files it; read when a conversation menu opens. */
    // Keyed by its chat, so it is not tied to the open chat like session_id page reads are.
    fun loadConversationProject(id: String, force: Boolean = true, quiet: Boolean = false) = load("project-of:$id", "/api/projects/for-session", mapOf("chat" to id), force = force, quiet = quiet)
    /** Pin, rename, archive, project and delete for any listed conversation; the open chat follows the change. */
    fun conversationAction(id: String, path: String, body: JSONObject = JSONObject(), done: String = "Saved", profile: String = client?.scope?.profile.orEmpty()): Job {
        val targetKey = client?.scope?.copy(profile = profile.ifBlank { client?.scope?.profile.orEmpty() }, session = id)?.key.orEmpty()
        val pendingKey = "$targetKey|$path"
        conversationChanges[pendingKey]?.takeIf { !it.isCompleted }?.let { return it }
        notice(conversationPendingLabel(path))
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
        val active = client ?: run { notice("Reconnect before changing this chat."); return@launch }
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
            if (path == "/api/session/archive") rememberArchived(target, id, result.optBoolean("hidden", body.optBoolean("archived")))
            _ui.update { state -> state.copy(sessions = updatedHistoryRows(state.sessions, id, path, body, result, sessionIndexArchived, "", target.profile)) }
            _data.update { it - "history:search" }
            if (path == "/api/session/archive") confirmedSessions.remove(target.key)
            if (path.startsWith("/api/projects/")) {
                loadConversationProject(id)
                // The open chat's working folder changed with its project; show the new one.
                if (sourceScope == target) c.get("/api/session", mapOf("session_id" to id)).let { if (sourceScope == target && client === active) renderSession(it, target) }
            }
            if (path == "/api/session/delete") {
                confirmedSessions.remove(target.key); clientCreatedSessions.remove(target.key); archivedHere.remove(target.key)
                val keptRows = if (sourceScope == target) _chat.value.raw.rows("messages") else null
                optionalLocalSave { app.cache.delete(target, "client-created-shell"); forgetKeptAttachments(target, keptRows) }
            }
            notice(done)
            if (path == "/api/session/archive" && body.optBoolean("archived")) _archiveUndo.value = target
            refreshSessions()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (e is DeleteKeptWaiting) restoreWithdrawn(target, e.withdrawn)
            if (client === active) fail(conversationActionFailure(path, e))
        } finally { c.closeNative() }
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
    /** The chat the page shows only because Hermes refused Beacon for this profile (it is not the first-chat card): the id to open again once it is on. */
    internal fun beaconOffChatId(): String? = _chat.value.takeIf { it.sessionId.isNotBlank() && it.status in BeaconOffLines && sourceScope == null }?.sessionId
    /** True while the page shows a chat only because it cannot open now (Beacon off for the profile, or the open was refused): the composer keeps typing but Send is off there ([composerSendEnabled]). */
    internal fun onRetainedPage(): Boolean = retainedDraftScope(_chat.value, sourceScope, client?.scope ?: cachedOwnerScope) != null
    fun retryInitialChat(): Job {
        // Turn on and Retry on such a chat open that chat again, never a new one.
        beaconOffChatId()?.let { id ->
            _ui.update { it.copy(newChatError = "", notice = "") }
            selectSession(id)
            return selectionJob ?: viewModelScope.launch {}
        }
        if (!canComposeInitialChat()) return viewModelScope.launch {}
        client?.scope?.key?.let { key -> newChatJobs[key]?.takeIf { !it.isCompleted }?.let { return it } }
        ++navigationGeneration
        _ui.update { it.copy(newChatError = "", notice = "") }
        return if (initialShells.containsKey((client?.scope ?: cachedOwnerScope)?.key)) resumeInitialChat() else createNewChat(json("worktree" to false), true)
    }
    /**
     * The button beside a refused profile's line: the plugin turns Beacon on for this profile through Hermes' own activation, then the chat that was waiting (the first chat, or
     * the chat the owner opened) opens by itself. [auto] false is the owner's own Turn on for a profile that turned Beacon off on Hermes; true is Retry after the phone's own
     * turn-on failed. A failure shows the one line again ([beaconOffLine]); no connection keeps the line and says so.
     */
    fun turnOnBeaconForProfile(auto: Boolean = false): Job {
        val active = client ?: return viewModelScope.launch {}
        if (_profileTurningOn.value) return viewModelScope.launch {}
        val owner = active.scope
        val origin = originGeneration
        fun current() = client === active && active.scope == owner && originGeneration == origin
        _profileTurningOn.value = true
        // The message bar's line goes while Hermes works; a failure shows it again.
        if (_ui.value.error in BeaconOffLines) clearMessage()
        return viewModelScope.launch {
            var failure: Throwable? = null
            try { if (active.turnOnBeaconForProfile(auto).opt("enabled") != true) failure = BeaconOffFailure(null) } catch (e: Throwable) { failure = e }
            _profileTurningOn.value = false
            if (failure is CancellationException || !current()) return@launch
            when {
                failure == null -> retryInitialChat()
                failure is NativeAuthExpiredException || failure is DeviceRemovedFailure || failure is LoopbackSessionRefreshFailed -> fail(failure)
                // No connection is not a refusal: the line keeps its button and the person is told.
                failure is java.io.IOException && failure !is ApiFailure -> notice(appErrorText(failure))
                else -> showBeaconOff(beaconOffLine(failure as? BeaconOffFailure ?: BeaconOffFailure(failure)))
            }
        }
    }
    /** The refused profile's line where the person is: the first-chat card or the page of the chat that waits for it, else the message bar with the same button. */
    private fun showBeaconOff(line: String) {
        when {
            beaconOffChatId() != null -> _chat.update { it.copy(status = line) }
            _chat.value.sessionId.isBlank() && sourceScope == null -> _ui.update { it.copy(newChatError = line) }
            else -> _ui.update { it.copy(error = line) }
        }
    }
    private fun clientShellMarker(key: String) = if (key in keptApartShells) KEPT_APART_SHELL else "1"
    /** True when [scope] carries the phone-created marker; a kept-apart one is remembered here so it survives an app restart. */
    private suspend fun readClientShell(scope: Scope): Boolean {
        val marker = app.cache.read(scope, "client-created-shell")
        if (marker == KEPT_APART_SHELL) keptApartShells.add(scope.key)
        return marker == "1" || marker == KEPT_APART_SHELL
    }
    private fun createNewChat(body: JSONObject, initial: Boolean): Job {
        val active = client ?: return viewModelScope.launch { notice("Reconnect to open a chat. Your draft is kept.") }
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
        // The first chat's create carries its attempt as Hermes' retry identity: a Retry after a lost answer reuses the pending attempt, so Hermes returns the chat the first
        // create made instead of making another. A finished or cleared attempt has no marker, so the next first chat starts a new one.
        val keptAttempt = initial && initialCreateAttempts[owner.key] != null
        var attempt = if (initial) (initialCreateAttempts[owner.key] ?: java.util.UUID.randomUUID().toString()).also { initialCreateAttempts[owner.key] = it } else null
        val request = JSONObject(body.toString()).apply { put("profile", owner.profile); selected?.session?.let { put("prev_session_id", it) }; attempt?.let { put("request_id", it) } }
        val job = viewModelScope.launch {
            val running = currentCoroutineContext()[Job]
            if (current()) _ui.update { it.copy(newChatOpening = true, newChatError = "") }
            try {
                // A durable owner-bound marker precedes the non-idempotent POST.
                // Leaving Chat, process death or cancellation cannot silently retry it.
                attempt?.let { app.cache.write(owner, "new-chat-attempt", it, protected = true) }
                checkpointNow()
                if (!current()) return@launch
                // A plain New chat opens the empty chat this phone already made for the account and profile, so Hermes does not collect one empty session per tap.
                val standing = initialShells[owner.key]?.takeIf { !initial && plainChatRequest(body) }
                if (standing != null) {
                    val reusable = standingShellIsFresh(c, owner, standing)
                    if (!current()) return@launch
                    if (reusable) {
                        val shell = owner.session(standing)
                        // What was typed or attached in this very chat is the owner's own, not carried from elsewhere, and the empty chat is hidden from History, so New chat
                        // is the only way back to it: the saved draft stays. A side question's answer does not come along.
                        optionalLocalSave { app.cache.delete(shell, "side-question") }
                        if (!current()) return@launch
                        clientCreatedSessions.add(shell.key)
                        _ui.update { it.copy(tab = "Chat", connected = true, newChatError = "") }; saved["tab"] = "Chat"
                        selectSession(standing, preserveInitialDraft = false, closeMenu = true)
                        return@launch
                    }
                    // Gone, or used from somewhere else: it is no longer the empty chat to open.
                    if (initialShells[owner.key] == standing) { initialShells.remove(owner.key); optionalLocalSave { app.cache.delete(owner, "new-chat-shell") } }
                }
                // A kept attempt Hermes refused (its chat deleted after a lost answer, or a changed request) is spent: one fresh create, with its id saved first.
                val response = if (attempt == null) c.post("/api/session/new", request) else createWithRetryIdentity(keptAttempt, renew = {
                    val fresh = java.util.UUID.randomUUID().toString()
                    attempt = fresh; initialCreateAttempts[owner.key] = fresh
                    app.cache.write(owner, "new-chat-attempt", fresh, protected = true)
                    request.put("request_id", fresh)
                }) { c.post("/api/session/new", request) }
                val id = (response.optJSONObject("session") ?: response).getString("session_id")
                require(id.isNotBlank()) { "The server did not return a conversation ID" }
                val createdScope = owner.session(id)
                if (request.optBoolean("thread")) request.string("title").takeIf(String::isNotBlank)?.let { pendingThreadTitles[createdScope.key] = it }
                clientCreatedSessions.add(createdScope.key)
                if (!plainChatRequest(body)) keptApartShells.add(createdScope.key)
                optionalLocalSave { app.cache.write(createdScope, "client-created-shell", clientShellMarker(createdScope.key), protected = true) }
                // An empty new chat joins History with its first message (accepted() publishes it); a named thread is listed at once.
                if (client === active && originGeneration == origin && request.optBoolean("thread")) { publishConfirmedSession(owner, id, response); reserveThread(id) }
                if (initial) {
                    initialShells[owner.key] = id
                    if (initialCreateAttempts[owner.key] == attempt) initialCreateAttempts.remove(owner.key)
                    runCatching { app.cache.write(owner, "new-chat-shell", id, protected = true); app.cache.delete(owner, "new-chat-attempt") }
                        .onFailure { if (it is CancellationException) throw it; if (current()) notice("Conversation opened; local recovery checkpoint failed") }
                } else if (plainChatRequest(body)) {
                    // The next plain New chat opens this one while it is still empty (its first sent message ends that: promptDelivered).
                    initialShells[owner.key] = id
                    optionalLocalSave { app.cache.write(owner, "new-chat-shell", id, protected = true) }
                }
                if (!current()) return@launch
                var authRetained = false
                optionalLocalSave { authRetained = persistAuthIfCurrent(c.auth, credentialVersion, snapshot = true) }
                if (!current()) return@launch
                if (!authRetained) { notice("Chat opened. Reconnect to continue; your draft is kept."); return@launch }
                clearPendingVoiceKeyRequestsIfLeavingVoiceKeyPage("Chat", "")
                _ui.update { it.copy(tab = "Chat", connected = true, newChatError = "") }; saved["tab"] = "Chat"
                selectSession(id, preserveInitialDraft = initial, initialResponse = response, closeMenu = !initial)
            } catch (e: Throwable) {
                // A create that never left the phone cannot have made a chat, so nothing needs the unconfirmed-attempt marker or its Retry.
                // Hermes refusing the plugin for this profile is a definite "not sent" too, but not an unreachable Hermes: the card says so with its button.
                val off = e !is CancellationException && beaconOffForProfile(e)
                val neverSent = off || e !is CancellationException && requestNeverSent(e)
                // Settle only the original owner's marker, even after navigation.
                // Retry waits for this job to finish, so its marker cannot race this exit.
                if (attempt != null && initialCreateAttempts[owner.key] == attempt) withContext(NonCancellable) {
                    if (neverSent) {
                        initialCreateAttempts.remove(owner.key)
                        runCatching { app.cache.delete(owner, "new-chat-attempt") }
                    } else runCatching { attempt?.let { app.cache.write(owner, "new-chat-attempt", it, protected = true) } }
                        .onFailure { if (current()) notice("Couldn't save on this phone.") }
                }
                if (neverSent && !off) unreachableCreateOwner = owner.key
                if (current() && e !is CancellationException) { _ui.update { it.copy(newChatError = if (off && attempt != null) beaconOffLine(e) else if (neverSent) "" else initialCreateError, connected = it.connected && (!neverSent || off)) }; if (!(off && attempt != null)) fail(e) }
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
    /**
     * Whether [id], the empty chat this phone made, is as good as a new one: nothing in it, nothing running or waiting, no title, no "Never ask" or Fast mode saved on it, and the
     * model and effort a new chat would start with (Hermes fixes a chat's model when it makes it, so one made before the profile's default changed would keep the old one).
     * The phone must hold no send, receipt or queued message for it either. Only then does New chat open it instead of making another; anything else is not carried over, and a
     * new chat is made. What the owner typed or attached in this very chat is their own and stays (see the reuse in [createNewChat]).
     */
    private suspend fun standingShellIsFresh(c: BeaconClient, owner: Scope, id: String): Boolean {
        val shell = owner.session(id)
        if (pending.containsKey(shell.key)) return false
        return try {
            if (app.cache.read(shell, "pending") != null) return false
            val saved = drafts[shell.key] ?: app.cache.read(shell, "draft")?.let(Draft::decode)
            if (saved?.queued?.isNotEmpty() == true) return false
            val response = c.get("/api/session", mapOf("session_id" to id))
            val session = response.optJSONObject("session") ?: response
            val untitled = session.string("title").let { it.isBlank() || it == "New conversation" }
            if (!(untitled && session.string("session_id") == id && session.rows("messages").isEmpty() && session.optInt("message_count") == 0 && session.string("active_stream_id").isBlank() &&
                    !session.optBoolean("has_pending_user_message") && session.opt("pending_user_message") in listOf(null, JSONObject.NULL, "") && session.string("profile", "default") == owner.profile)) return false
            // Hermes keeps "Never ask" and Fast mode per chat, on its saved row; a chat set to either is not what a new chat starts as.
            if (chatKeepsApprovalOrSpeed(session)) return false
            val model = session.string("model")
            val effort = session.string("reasoning_effort", session.string("reasoning"))
            if (model.isBlank() && effort.isBlank()) return true
            val defaults = c.get(PROFILE_DEFAULTS_PATH, emptyMap())
            (model.isBlank() || model == defaults.string("model")) && (effort.isBlank() || effort == defaults.string("reasoning_effort"))
        } catch (e: Throwable) {
            if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403)) throw e
            false
        }
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
            readClientShell(scope)
        } catch (lookupError: Exception) {
            if (lookupError is CancellationException) throw lookupError
            if (current()) notice("Your draft is kept.")
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
                require(session.rows("messages").isEmpty() && session.optInt("message_count") == 0 && session.string("active_stream_id").isBlank() && !session.optBoolean("has_pending_user_message") && session.opt("pending_user_message") in listOf(null, JSONObject.NULL, "") && session.string("profile", "default") == owner.profile) { "This chat already has messages. Open it from History." }
                if (clientCreatedShell()) clientCreatedSessions.add(scope.key)
                if (!current()) return@launch
                app.cache.delete(owner, "new-chat-attempt"); initialCreateAttempts.remove(owner.key)
                if (!current()) return@launch
                selectSession(id, preserveInitialDraft = true, initialResponse = response, closeMenu = false)
                selectionJob?.join()
            } catch (e: Throwable) {
                if (e !is CancellationException) {
                    fun ownsMissingShell() = client === active && originGeneration == origin && initialShells[owner.key] == id
                    // The recovery forgets the remembered shell itself, so what it re-checks is that nobody replaced it (still this one, or already forgotten), never that it is still held:
                    // a check that needs the shell removes itself and ends the recovery after clearing the markers and before making the new chat.
                    fun recoveryCurrent() = shellRecoveryCurrent(current() && client === active && originGeneration == origin, initialShells[owner.key], id)
                    if (missingNativeConversation(e) && ownsMissingShell()) {
                        val clientCreated = clientCreatedShell()
                        val confirmedNativeMissing = nativeSessionNotFound(e)
                        // Hermes can keep a minted shell out of REST history until its first native resume.
                        if (confirmedNativeMissing) {
                            recoverMissingInitialShell(owner, id) { recoveryCurrent() }
                            return@launch
                        }
                        if (clientCreated) {
                            clientCreatedSessions.add(scope.key)
                            optionalLocalSave { app.cache.write(scope, "client-created-shell", clientShellMarker(scope.key), protected = true) }
                            if (current()) {
                                selectSession(id, preserveInitialDraft = true, closeMenu = false)
                                selectionJob?.join()
                            }
                            return@launch
                        }
                        // A shell this phone did not create has no first-prompt ownership to protect: REST 404 is authoritative (F11).
                        recoverMissingInitialShell(owner, id) { recoveryCurrent() }
                        return@launch
                    }
                    if (beaconOffForProfile(e)) { if (current()) _ui.update { it.copy(newChatError = beaconOffLine(e)) }; return@launch }
                    val attempt = java.util.UUID.randomUUID().toString()
                    initialCreateAttempts[owner.key] = attempt
                    runCatching {
                        app.cache.write(owner, "new-chat-attempt", attempt, protected = true)
                    }.onFailure { if (current()) notice("Couldn't save on this phone.") }
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
    private suspend fun restoreBeaconTransfer(owner: Scope, id: String, transfer: String, current: () -> Boolean) = draftWriteLock.withLock {
        if (!current() || app.cache.read(owner, BEACON_DRAFT_TRANSFER) != transfer) return@withLock
        val scope = owner.session(id)
        val record = JSONObject(transfer)
        require(record.getString("from") != id)
        val transferId = record.getString("transfer_id")
        val stored = app.cache.read(scope, "draft")
        if (stored == null || JSONObject(stored).string("beacon_transfer_id") != transferId) {
            val kept = drafts[scope.key] ?: stored?.let(Draft::decode) ?: Draft()
            val incoming = Draft.decode(transfer)
            var merged = kept
            if (incoming.text.isNotEmpty() || incoming.attachments.isNotEmpty()) {
                merged = if (kept.text.isEmpty() && kept.attachments.isEmpty()) kept.copy(text = incoming.text, selectionStart = incoming.selectionStart,
                    selectionEnd = incoming.selectionEnd, attachments = incoming.attachments)
                else kept.copy(queued = kept.queued + QueuedMessage(incoming.text, incoming.attachments, id = transferId, state = "settled", outcome = CHAT_GONE_OUTCOME))
            }
            merged = merged.copy(queued = (merged.queued + incoming.queued).distinctBy { it.id })
            if (!current()) return@withLock
            val encoded = merged.encode()
            // The stamp and the draft share a write, so a restart before the handoff is cleared cannot merge it twice.
            app.cache.write(scope, "draft", JSONObject(encoded).put("beacon_transfer_id", transferId).toString(),
                protected = merged.text.isNotEmpty() || merged.attachments.isNotEmpty() || merged.queued.isNotEmpty())
            drafts[scope.key] = merged; savedDraft(scope, encoded)
        }
        if (!current()) return@withLock
        app.cache.write(owner, BEACON_SESSION_CACHE_KIND, id, protected = true)
        app.cache.delete(owner, BEACON_DRAFT_TRANSFER, transfer)
    }

    fun openBeacon(): Job {
        val active = client
        val owner = active?.scope ?: cachedOwnerScope
        val generation = ++beaconGeneration
        val connection = originGeneration
        val c = active
        fun current() = generation == beaconGeneration && connection == originGeneration && _ui.value.tab == "Beacon" && client === active && (client?.scope ?: cachedOwnerScope) == owner
        return viewModelScope.launch {
            val fixed = app.preferences.data.first().let(AppPreferences::read)
            if (!current()) return@launch
            if (owner == null || !_ui.value.authenticated) { _ui.update { it.copy(beaconOpening = false, beaconError = "Sign in again to open ${dev.hermes.android.data.currentAssistantName}.") }; return@launch }
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
                val transfer = app.cache.read(owner, BEACON_DRAFT_TRANSFER)
                if (!current()) return@launch
                transfer?.let {
                    val from = JSONObject(it).getString("from")
                    goneBeaconChats += owner.session(from).key
                    forgetBeaconChat(owner, from)
                }
                if (!current()) return@launch
                val id = if (c == null) readBeaconSession(owner).orEmpty()
                else resolveBeacon(c, fixed) { current() }
                require(id.isNotBlank()) { "Reconnect to open ${dev.hermes.android.data.currentAssistantName}. Your other draft is kept." }
                if (transfer != null) restoreBeaconTransfer(owner, id, transfer) { current() }
                if (!current()) return@launch
                selectSession(id, onCachedReady = { if (current()) _ui.update { it.copy(beaconOpening = false) } })
                selectionJob?.join()
                if (current()) _ui.update { it.copy(beaconOpening = false) }
            } catch (e: Throwable) { if (current() && e !is CancellationException) { _ui.update { it.copy(beaconOpening = false, beaconError = appErrorText(e)) }; fail(e) } }
        }
    }
    /** [whileOpening]: the chat on screen is already the account's Beacon chat, though an open of it is running (UX-121 P3-10: a re-open keeps it shown). */
    fun isBeaconConversation(whileOpening: Boolean = false): Boolean {
        val owner = client?.scope ?: cachedOwnerScope
        return canonicalConversationReady(owner, sourceScope, _chat.value.sessionId, true, owner?.let(::readBeaconSessionHandle), _ui.value.beaconOpening && !whileOpening, _ui.value.beaconError.isNotBlank(), preferences.value.assistantProfile)
    }
    private fun admitConversation(): Boolean {
        val owner = client?.scope ?: cachedOwnerScope
        val ready = canonicalConversationReady(owner, sourceScope, _chat.value.sessionId, _ui.value.tab == "Beacon", owner?.let(::readBeaconSessionHandle), _ui.value.beaconOpening, _ui.value.beaconError.isNotBlank(), preferences.value.assistantProfile)
        if (!ready) notice(if (_ui.value.tab == "Beacon") "Open ${dev.hermes.android.data.currentAssistantName} first. Your draft is kept." else "Open a chat first.")
        return ready
    }
    /** Returns once the chat just selected has finished loading (its saved draft restored); the share hand-off adds to it only then. */
    internal suspend fun awaitSelection() { selectionJob?.join() }
    fun selectSession(id: String, editorDraft: String? = null, preserveInitialDraft: Boolean = false, initialResponse: JSONObject? = null, closeMenu: Boolean = true, onCachedReady: (() -> Unit)? = null, titleHint: String = "", dropQuestion: Boolean = false, reopen: Boolean = false) {
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
            clientCreatedSessions.removeAll { it != scope.key }; keptApartShells.removeAll { it != scope.key }
            var clientCreated = initialShells[ownerScope.key] == id || clientCreatedSessions.contains(scope.key)
            var nativeResumeAttempted = false
            var draftRestored = false
            val initialDraft = if (preserveInitialDraft) draft() else null
            initialDraft?.let { drafts[scope.key] = it }
            val openPicker = _catalog.value.takeIf { reopen && sourceScope == scope && _chat.value.sessionId == id && it.isNotBlank() }
            sourceScope = scope; clearDetails()
            // P2-3: the chat just left may still hold waiting messages; they go from the background now.
            drainOfflineOutbox(openQueue = false)
            _data.update { it + screens.composer(scope) }
            // An authenticated disk restore can already have a picker open when bootstrap reselects this same chat.
            if (openPicker != null) openCatalog(openPicker)
            // H1b P0-1 (related): Beacon's chat is never the Chat tab's remembered chat, or the next start shows it there as an untitled "New chat".
            val beaconChat = readBeaconSessionHandle(ownerScope) == id
            if (!beaconChat) saved["session:${ownerScope.key}"] = id
            _ui.update { it.copy(showSessions = if (closeMenu) false else it.showSessions, newChatError = "") }
            heldTitle = titleHint.takeIf { it.isNotBlank() }?.let { Triple(scope, it, _chat.value.title) }
            // Re-selecting the open conversation (a reconnect/refresh) keeps its visible transcript, live
            // output and composer until the refreshed projection replaces them: a working reply must not
            // blink out behind a loading skeleton or a typed draft vanish because the same chat is reloaded.
            val sameSession = _chat.value.sessionId == id
            _chat.value = chatOnSelection(_chat.value, id, titleHint, previousEffort)
            if (!preserveInitialDraft && !sameSession) { _composer.value = TextFieldValue(); _attachments.value = emptyList() }
            else initialDraft?.let { restoreDraft(it, preserveActiveInput = true) }
            // A transcript this process saved a moment ago draws in this frame, not after the disk reads below.
            if (initialResponse != null) renderSession(initialResponse, scope)
            else if (!sameSession) app.cache.recentSession(scope)?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }?.let { renderSession(it, scope) }
            try {
                // Always read the marker: a known shell may still be one kept apart, and the write below must not turn it into a plain one.
                val marked = readClientShell(scope)
                clientCreated = clientCreated || marked ||
                    (readBeaconSessionHandle(ownerScope) == id && app.cache.read(scope, "session") == null)
                if (clientCreated) {
                    clientCreatedSessions.add(scope.key)
                    optionalLocalSave { app.cache.write(scope, "client-created-shell", clientShellMarker(scope.key), protected = true) }
                }
                if (preserveInitialDraft) checkpointNow()
                if (!beaconChat) app.cache.write(ownerScope, "selected-session", id, protected = true)
                if (!restoreCachedConversation(scope, generation, preserveActiveInput = preserveInitialDraft)) return@launch
                draftRestored = true
                if (_chat.value.raw.length() > 0) onCachedReady?.invoke()
                if (preserveInitialDraft && sourceScope == scope && generation == sessionGeneration && client === active) {
                    app.cache.delete(ownerScope, "initial-draft")
                    if (sourceScope == scope && generation == sessionGeneration && client === active) drafts.remove(ownerScope.key).also { persistedDrafts.remove(ownerScope.key) }
                }
                if (editorDraft != null) { _composer.value = TextFieldValue(editorDraft); checkpoint() }
                if (c == null) { _chat.update { it.copy(projectionReady = true, status = "Saved copy · reconnect to update") }; return@launch }
                // R2C: connected again, so the saved-copy line has nothing left to say; the read below words any failure itself.
                _chat.update { if (it.sessionId == id && it.status.startsWith("Saved copy")) it.copy(status = "") else it }
                val response = try { initialResponse?.takeIf { it.optBoolean("acknowledged_shell") } ?: c.get("/api/session", mapOf("session_id" to id)) }
                catch (readError: Throwable) {
                    if (readError is CancellationException) throw readError
                    if (readError !is ApiFailure || readError.status != 404 || !sessionCanStillBeUnpersisted(clientCreated, _chat.value) || !current()) throw readError
                    nativeResumeAttempted = true
                    val resumed = try { requireNotNull(active).nativeSession(id, lazy = true) }
                    catch (resumeError: Throwable) {
                        if (resumeError is CancellationException) throw resumeError
                        if (nativeSessionNotFound(resumeError) && clientCreated && !(beaconChat && _ui.value.tab == "Beacon")) {
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
                            "This chat already has messages. Open it from History."
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
                app.cache.writeSession(scope, response.toString())
                client?.let { refreshChatAlerts(scope, it) }
                val session = response.optJSONObject("session") ?: response
                if (session.rows("messages").isNotEmpty() || session.optInt("message_count") > 0 || session.string("active_stream_id").isNotBlank()) {
                    clientCreated = false
                    clientCreatedSessions.remove(scope.key)
                    optionalLocalSave { app.cache.delete(scope, "client-created-shell") }
                }
                if (asksPromptsAfterRead(dropQuestion, _chat.value.streamId, followsHere(scope))) pollPrompts(c, scope)
                if (pending.containsKey(scope.key)) {
                    // Follow-up 5: a send this chat can ask about again is checked quietly; the ask below settles it.
                    val asking = pending[scope.key]?.let { askableAgain(it) && askedAgain[it.id] != reachEpoch && (_chat.value.uncertain == it.id || askedAgain.containsKey(it.id)) } == true &&
                        runCatching { c.nativeSessionAuthority() }.getOrDefault(false)
                    val receiptClient = c.detached()
                    try { reconcile(scope, receiptClient, quiet = asking) } finally { if (streamClients.values.none { it === receiptClient }) receiptClient.closeNative() }
                    askAgainUnconfirmed()
                }
                val active = (response.optJSONObject("session") ?: response).string("active_stream_id")
                // R2C: a turn the chat still shows that nothing follows (painted from its saved record on a cold start, or kept while reselected) is asked about at once.
                val shownTurn = _chat.value.streamId
                if (active.isNotBlank() && !followsHere(scope)) attachStream(c.detached(), scope, active)
                else if (active.isBlank() && shownTurn.isNotBlank() && !followsHere(scope)) { if (!followRunningTurn(c.detached(), scope, shownTurn, ScopeSettlement(scope, client ?: return@launch, origin, generation))) return@launch }
                else if (active.isBlank() && !reattachMarkedRun(c.detached(), scope, ScopeSettlement(scope, client ?: return@launch, origin, generation))) return@launch
                watchSession(c.detached(), scope)
                // Browsing never resumes an agent: a reply Hermes lost in a restart is looked at only on evidence (the phone's run marker or receipt,
                // the stream, Hermes' reconnect projection) or on the person's Send or Stop. Each open starts those looks afresh.
                lostTurnLooked.remove(scope.key)
                if (_chat.value.streamId.isBlank()) showLostOnOpen(c, scope, ScopeSettlement(scope, client ?: return@launch, origin, generation))
                if (_chat.value.streamId.isBlank()) client?.let { drainQueued(scope, it, generation, origin) }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (current()) {
                    // Hermes refused Beacon for this profile and the client could not turn it on (its ticket is refused). The first chat the phone made for the profile is the profile's
                    // own card with the line. Any other chat stays that chat: its draft and attachments stay with it, it says so in one line with its button, and the button opens it
                    // again (never a new chat, never its words in another).
                    if (beaconOffForProfile(e)) {
                        if (initialShells[ownerScope.key] != id) { retainDraftAndLeave(scope, id, generation, beaconOffLine(e)); return@launch }
                        sourceScope = null
                        _chat.value = ChatState()
                        _ui.update { it.copy(newChatError = beaconOffLine(e), newChatOpening = false, initialChatReady = true) }
                        return@launch
                    }
                    if (e is ApiFailure && !e.plugin && e.status in setOf(401, 403)) {
                        retainDraftAndLeave(scope, id, generation, ACCESS_DENIED_RETAINED)
                        fail(e)
                        return@launch
                    }
                    val cached = _chat.value.raw.length() > 0
                    val pendingFirstTurn = !nativeResumeAttempted && e is ApiFailure && e.status == 404 && sessionCanStillBeUnpersisted(clientCreated, _chat.value)
                    // P0-1: the account's Beacon chat was deleted on Hermes (History, the dashboard). This phone stops keeping it and the Beacon tab opens Beacon
                    // the normal way (the registered chat, else a new one) with the unsent words, quietly, as when Hermes forgets it before its first turn.
                    if (draftRestored && !pendingFirstTurn && c != null && beaconChatAbsent(c, scope, e)) {
                        recoverGoneBeaconChat(scope) { current() }
                        return@launch
                    }
                    if (!current()) return@launch
                    _chat.update { it.copy(projectionReady = true, status = if (pendingFirstTurn) "" else when { missingNativeConversation(e) -> "Chat unavailable · draft kept"; e is ApiFailure && e.status in setOf(401, 403) -> ACCESS_DENIED_RETAINED; cached -> "Saved copy · couldn't refresh"; else -> "Couldn't load this chat · retry" }) }
                    // A confirmed Beacon deletion returned above. An inconclusive open must keep its selection.
                    val gone = !pendingFirstTurn && !(beaconChat && _ui.value.tab == "Beacon") && missingNativeConversation(e)
                    // TRACE-COLD-121 fix 2: a chat Hermes no longer has stops being the saved selection, unless it holds the owner's unsent words or a send not
                    // yet settled: then it stays (on screen with its line) so they are never lost.
                    // Fix 3a (Root): the start-up reopen nobody asked for raises no app-wide error for a gone chat; its own line says so. An open the owner
                    // asked for keeps the rule (NativeOperationPolicy). Shown before the selection is forgotten below, while this open is still current().
                    if (!pendingFirstTurn && !(gone && reopen)) fail(e)
                    if (gone && draft().let { it.text.isBlank() && it.attachments.isEmpty() && it.queued.isEmpty() } && pending[scope.key] == null && forgetSelection(ownerScope, id)) forgottenGone = scope
                }
            }
        }
    }
    /** The chat [id] cannot be opened now: its draft, attachments and editor are saved under it and stay in the composer, and the page shows [status] until the chat opens again. */
    private fun retainDraftAndLeave(scope: Scope, id: String, generation: Int, status: String) {
        val retainedDraft = draft()
        val retainedEditor = _editor.value
        drafts[scope.key] = retainedDraft
        checkpointJob?.cancel(); editorCheckpoint?.cancel()
        viewModelScope.launch {
            runCatching {
                draftWriteLock.withLock {
                    val current = drafts[scope.key] ?: retainedDraft
                    val encoded = current.encode(); app.cache.write(scope, "draft", encoded, current.text.isNotBlank() || current.attachments.isNotEmpty() || current.queued.isNotEmpty()); savedDraft(scope, encoded)
                }
                retainEditor(retainedEditor)
            }.onFailure { if (generation == sessionGeneration) notice(if (draftStoreFull(it)) DRAFT_STORE_FULL_ERROR else "Couldn't save the latest change. Your draft stays on this phone.") }
        }
        _chat.value = retainedChatState(id, status, retainedDraft)
        // The draft stays in the composer on both pages: the person may keep typing, it is saved with this chat (see [retainedDraftScope]) and is what the composer holds when the chat
        // opens again (Turn on, or the chat opened from History once access is back).
        _data.update { it.filterKeys { key -> !isChatScreenKey(key) } }; clearDetails()
        sourceScope = null
    }
    private suspend fun restoreCachedConversation(scope: Scope, generation: Int, preserveActiveInput: Boolean = false, paintTurn: Boolean = false): Boolean {
        fun current() = generation == sessionGeneration && sourceScope == scope && (client?.scope ?: cachedOwnerScope) == scope.copy(session = "")
        val cached = app.cache.readMany(scope, listOf("draft", "side-question", "session", "pending", "alerts") + if (paintTurn) listOf(LIVE_TURN) else emptyList())
        if (!current()) return false
        cached["alerts"]?.let { raw -> _chat.update { it.copy(alerts = decodeChatAlerts(raw)) } }
        // shortcut: 1.1.14 saved email alerts as a pinned "task-result" card; remove this once no 1.1.14 install remains.
        optionalLocalSave { app.cache.delete(scope, "task-result") }
        if (!preserveActiveInput) {
            val rawDraft = cached["draft"]
            var localDraft = (drafts[scope.key] ?: rawDraft?.let(Draft::decode) ?: Draft()).let { saved ->
                saved.copy(queued = saved.queued.map { if (it.state == "sending") it.copy(state = "unknown") else it })
            }
            val oldQueue = rawDraft?.let { JSONObject(it).optJSONArray("queue") }
            val needsMigration = drafts[scope.key] == null && oldQueue != null && (0 until oldQueue.length()).any { index ->
                val item = oldQueue.optJSONObject(index)
                item == null || item.string("id").isBlank() || !item.has("created_at") || item.string("state") in setOf("saving", "sending")
            }
            if (needsMigration) try {
                draftWriteLock.withLock {
                    if (!current()) return@withLock
                    localDraft = drafts[scope.key] ?: localDraft
                    val encoded = localDraft.encode(); app.cache.write(scope, "draft", encoded, true); savedDraft(scope, encoded)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                localDraft = localDraft.copy(queued = localDraft.queued.map { it.copy(state = "unknown") })
                if (current()) notice("Couldn't update saved messages. Review them before sending.")
            }
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
        // R2C: a cold start paints the turn the chat last showed, with its words so far; Hermes' answer settles it once connected (selectSession).
        val record = cached[LIVE_TURN]?.let { runCatching { JSONObject(it) }.getOrNull() }
        val turnStream = record?.string("stream_id")?.takeIf { it.matches(streamIdPattern) }
        if (turnStream != null) {
            val frame = liveFrames["${scope.key}:$turnStream"] ?: try { app.cache.read(scope, "live:$turnStream")?.let(::JSONObject) } catch (e: Exception) { if (e is CancellationException) throw e; null }
            if (!current()) return false
            paintedTurn(_chat.value, record, frame)?.let { _chat.value = it }
        }
        // R2C: the newest bubbles are parsed before the first frame of a cold start, so they do not grow (and move the list) a moment after it.
        if (paintTurn) _chat.value.let { shown ->
            withContext(Dispatchers.Default) { runCatching { dev.hermes.android.ui.prepareBubbles(shown.messages.takeLast(12).map { dev.hermes.android.ui.bubbleDisplayText(it.role, it.text, it.displayKind) } + shown.liveSealed + shown.liveText) } }
        }
        val receipt = cached["pending"]
        if (!current()) return false
        if (receipt != null) {
            val send = PendingSend.decode(scope, receipt)
            pendingStored[scope.key] = send to receipt
            pending[scope.key] = send; _chat.update { state -> state.copy(uncertain = send.id,
                queued = if (send.queued) state.queued.map { if (it.id == send.id && it.state != "sent") it.copy(state = "unknown") else it } else state.queued) }
        }
        return true
    }
    private suspend fun renderSession(response: JSONObject, scope: Scope, expectedRevision: Long? = null, allowRemoval: Boolean = false, removedUserRowId: String = "") {
        if (sourceScope != scope || expectedRevision != null && expectedRevision != chatHistoryRevision) return
        val o = mergeChatProjection(_chat.value.raw, storedSessionSettings(response.optJSONObject("session") ?: response), allowRemoval, removedUserRowId)
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
            receipts["attachments:$id"]?.let(::JSONObject)?.takeIf { attachmentRecordFitsRow(it, row) }?.let { receipt ->
                row.put("attachments", mergedRowAttachments(row, receipt, scope.key))
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
        val queuedBefore = _chat.value.queued
        val picker = screens.exact(scope.copy(session = ""), "picker:reasoning", screenRequest(reasoningPath(), reasoningQuery(scope.session, o)))?.value
        _chat.update { old ->
            val rows = o.rows("messages")
            val active = (o.opt("active_stream_id") as? String)?.takeIf { it.matches(streamIdPattern) }
            val boundary = liveBoundaries["${scope.key}:${old.streamId}"] ?: LiveAssistantBoundary()
            val proof = persistedTurns["${scope.key}:${old.streamId}"]
            val live = settleLive(old, rows, active, boundary, proof?.string("final_assistant_row_id").orEmpty())
            val ended = live.streamEnded && !(old.sessionId == scope.session && passiveReadKeepsTurn(old.streamId, o.has("active_stream_id"), rows))
            old.copy(projectionReady = true,
                sessionReasoningEffort = reloadedEffort(old, scope.session, o, picker),
                sessionId = scope.session, title = shownTitle.ifBlank { old.title.takeIf { old.sessionId == scope.session } ?: "New conversation" }, messages = rows.mapIndexed { i, m -> Message.from(m, i, scope.session) },
                raw = if (old.sessionId == scope.session) carryDisplayIdentity(o, old.raw) else o,
                status = renderedChatStatus(old, active, ended),
                streamId = active?.takeIf { it.isNotBlank() } ?: if (ended) "" else old.streamId,
                liveText = live.text, liveReasoning = live.reasoning, liveTools = live.tools, liveSealed = live.sealed, liveSealedAt = live.sealedAt, liveBase = live.base, liveBasePrompt = live.basePrompt,
                liveOrdinal = if (live.streamEnded || old.sessionId != scope.session) 0 else live.ordinal,
                // P1-3: a steer whose saved row arrives in this history takes it in the same frame, never shown twice in between (saved below).
                queued = if (old.sessionId == scope.session) steersPairedOnRender(old.queued, rows) else old.queued)
        }
        projectCallTranscripts()
        if (sourceScope == scope && _chat.value.queued != queuedBefore) checkpoint()
        // A steer Hermes already saved is not shown a second time while the turn still runs.
        if (_chat.value.queued.any { it.kind == "steer" && it.rowId.isBlank() && it.state in setOf("accepted", "unknown") }) pairSteerCopies(scope, emptyList(), o.rows("messages")) { sourceScope == scope }
        // The same for a message (a photo) Hermes accepted without naming its row: the saved row replaces the phone's copy instead of showing beside it.
        if (_chat.value.queued.any { it.kind == "queue" && it.rowId.isBlank() && it.state in setOf("accepted", "settled") }) pairAcceptedCopies(scope, o.rows("messages")) { sourceScope == scope }
        // R2C (owner, 22 Nov): a finished copy whose row left the loaded window can never retire by its proof; Hermes holds the row, so the copy goes now.
        // This also heals a queue saved before the fix on its first load.
        for (stale in copiesAboveWindow(_chat.value.queued, _chat.value.messages)) {
            updateQueued(scope, stale.id, { sourceScope == scope }) { q -> if (q.submissionId == stale.submissionId && copiesAboveWindow(listOf(q), _chat.value.messages).isNotEmpty()) null else q }
        }
        val shown = _chat.value
        val followsProfile = shown.raw.string("model").isBlank() || shown.raw.string("reasoning_effort", shown.raw.string("reasoning")).isBlank() && shown.sessionReasoningEffort.isBlank()
        val defaults = _data.value["profile-defaults"]
        if (followsProfile && (defaults == null || defaults.string("session_id") != scope.session || defaults.string("scope_key") != client?.scope?.key))
            load("profile-defaults", PROFILE_DEFAULTS_PATH, mapOf("session_id" to scope.session))
        val cached = response.optJSONObject("session") ?: response
        for (key in listOf("voice_transcripts", UNCONFIRMED_REPLIES, "_display_submission_id", "_display_admission_id")) if (o.has(key)) cached.put(key, o.opt(key))
    }
    private val persistedTurns = mutableMapOf<String, JSONObject>()
    /** A deleted chat takes the small pictures kept for its photos with it (the open chat's rows, else the saved snapshot's). */
    private suspend fun forgetKeptAttachments(scope: Scope, openRows: List<JSONObject>?) {
        val rows = openRows ?: app.cache.read(scope, "session")?.let { runCatching { JSONObject(it).rows("messages") }.getOrNull() }.orEmpty()
        keptAttachmentKinds(rows).forEach { kind -> app.cache.delete(scope, kind) }
    }
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
    /** A scheduled post landed in the open chat: re-read it so the post shows at once, never during a turn of its own. */
    private fun refreshPostedConversation(posted: Scope) {
        val scope = sourceScope ?: return
        val active = client ?: return
        val chat = _chat.value
        if (scope != posted || scope.copy(session = "") != active.scope || !foreground || chat.streamId.isNotBlank() || pending.containsKey(scope.key)) return
        val request = ScopeSettlement(scope, active, originGeneration, sessionGeneration)
        viewModelScope.launch {
            try {
                val response = active.detached().get("/api/session", mapOf("session_id" to scope.session))
                if (!foreground || !request.current() || _chat.value.streamId.isNotBlank() || pending.containsKey(scope.key)) return@launch
                renderSession(response, scope)
                app.cache.writeSession(scope, response.toString())
            } catch (e: Throwable) { if (e is CancellationException) throw e }
        }
    }
    /** The titles this phone gave call chats, per account and profile: an empty chat joins the listed History only with its first message, so the list may not hold the last call's title yet. */
    private val callChatTitles = mutableMapOf<String, MutableSet<String>>()
    /**
     * The words of a call's chat title: "Call with {name}" in the phone's language with the assistant's name as it is now (the owner can rename it), and the call's start in the
     * app's own day and clock style for the phone's locale and 12/24-hour setting.
     */
    private suspend fun callTitleWords(): Pair<String, (Long) -> String> {
        val locale = java.util.Locale.getDefault()
        val language = dev.hermes.android.ui.nativeLanguageFor(locale)
        val dictionary = if (language == "en") null else withContext(Dispatchers.IO) { app.readTranslations().optJSONObject(language) }
        val phrase = if (dictionary == null) "Call with Beacon" else dev.hermes.android.ui.translateUi(dictionary, "Call with Beacon")
        val pattern = dev.hermes.android.ui.nativeDayPattern(locale, withYear = false, withWeekday = false) + ", " +
            dev.hermes.android.ui.nativeClockPattern(locale, android.text.format.DateFormat.is24HourFormat(app))
        val zone = java.time.ZoneId.systemDefault()
        return dev.hermes.android.ui.assistantName(phrase, dev.hermes.android.data.currentAssistantName) to { millis ->
            runCatching { java.time.format.DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(java.time.Instant.ofEpochMilli(millis)) }.getOrDefault("")
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
                val client = active.detached()
                var response = client.get("/api/session", mapOf("session_id" to scope.session))
                if (!foreground || !request.current()) return@launch
                if (!call.active) {
                    val session = response.optJSONObject("session") ?: response
                    val ownerKey = scope.copy(session = "").key
                    val (callWith, startedLabel) = callTitleWords()
                    val title = deriveCallEndTitle(session.string("title"), call.transcripts, dev.hermes.android.data.currentAssistantName,
                        takenTitles = _ui.value.sessions.map { it.string("title") } + callChatTitles[ownerKey].orEmpty(), startedAt = call.startedAt,
                        callWith = callWith, startedLabel = startedLabel)
                    // A rename Hermes refuses must not stop the chat from rendering and saving below.
                    if (renameCallChat(title) { client.post("/api/session/rename", json("session_id" to scope.session, "title" to it)) }) {
                        title?.let { callChatTitles.getOrPut(ownerKey) { mutableSetOf() }.add(it) }
                        if (!foreground || !request.current()) return@launch
                        refreshSessions()
                        response = try { client.get("/api/session", mapOf("session_id" to scope.session)) } catch (e: CancellationException) { throw e } catch (_: Exception) { response }
                    }
                }
                if (!foreground || !request.current()) return@launch
                renderSession(response, scope)
                app.cache.writeSession(scope, response.toString())
            } catch (e: Throwable) { if (e is CancellationException) throw e }
        }
    }
    private fun restoreDraft(d: Draft, preserveActiveInput: Boolean = false) { if (!preserveActiveInput) { _composer.value = TextFieldValue(d.text, TextRange(d.selectionStart, d.selectionEnd)); _attachments.value = d.attachments }; _chat.update { it.copy(queued = steersPairedOnRender(d.queued, it.raw.rows("messages")), anchor = d.anchor, anchorOffset = d.anchorOffset, anchorId = d.anchorId) } }
    /** Relay's "Quote in reply": the message's excerpt goes above the draft as a Markdown quote, cursor at the end. */
    fun quoteInReply(text: String) {
        val excerpt = dev.hermes.android.ui.quoteExcerpt(text).takeIf(String::isNotBlank) ?: return
        val quoted = dev.hermes.android.ui.quotedDraft(excerpt, composerBase())
        composer(TextFieldValue(quoted, TextRange(quoted.length)))
    }
    /** A draft card's Send, when Hermes has to send the email itself, says so in the chat as the owner's own message: possible while the composer is free. */
    fun canSendAsOwner() = _composer.value.text.isBlank() && _attachments.value.isEmpty()
    fun sendAsOwner(text: String) {
        if (!canSendAsOwner()) { notice("Send or clear your message first."); return }
        composer(TextFieldValue(text, TextRange(text.length)))
        send()
    }
    fun composer(value: TextFieldValue, fromEditor: Boolean = false) {
        if (value.text.length > 50_000) { notice("This message is too long. Shorten it."); return }
        // A new message replaces the words a send is taking, files included (those stay with the send).
        if (heldBySend()) _attachments.value = emptyList()
        val previous = _composer.value.text
        composerRevision++
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
    private fun draftScope(): Scope? = retainedDraftScope(_chat.value, sourceScope, client?.scope ?: cachedOwnerScope)
        ?: if (!_chat.value.projectionReady) null else sourceScope ?: (client?.scope ?: cachedOwnerScope)?.takeIf { _ui.value.authenticated && _chat.value.sessionId.isBlank() }
    private fun draftKind(scope: Scope) = if (scope.session.isBlank()) "initial-draft" else "draft"
    private fun checkpoint() {
        val scope = draftScope() ?: return
        drafts[scope.key] = draft()
        checkpointJob?.cancel()
        checkpointJob = viewModelScope.launch {
            delay(200)
            try { draftWriteLock.withLock {
                val current = if (draftScope() == scope) draft() else drafts[scope.key] ?: return@withLock
                val encoded = current.encode()
                app.cache.write(scope, draftKind(scope), encoded, protected = current.text.isNotBlank() || current.attachments.isNotEmpty() || current.queued.isNotEmpty())
                savedDraft(scope, encoded)
                keepGoneChatWithWords(scope, current)
            } } catch (e: Throwable) { if (e is CancellationException) throw e; if (draftScope() == scope) fail(e) }
        }
    }
    suspend fun checkpointNow() {
        val scope = draftScope() ?: return
        checkpointJob?.cancelAndJoin()
        draftWriteLock.withLock {
            val current = if (draftScope() == scope) draft() else drafts[scope.key] ?: return@withLock
            drafts[scope.key] = current
            val encoded = current.encode(); app.cache.write(scope, draftKind(scope), encoded, current.text.isNotBlank() || current.attachments.isNotEmpty() || current.queued.isNotEmpty()); savedDraft(scope, encoded)
            keepGoneChatWithWords(scope, current)
        }
        if (sourceScope == scope && _chat.value.raw.has(UNCONFIRMED_REPLIES)) app.cache.writeSession(scope, _chat.value.raw.toString())
    }
    /** REVIEW-FIXCHAT-121 P2: the next start reopens a gone chat that holds the owner's words again (the 404 let its selection go while it was empty). */
    private suspend fun keepGoneChatWithWords(scope: Scope, current: Draft) {
        if (forgottenGone != scope || current.text.isBlank() && current.attachments.isEmpty() && current.queued.isEmpty()) return
        val owner = scope.copy(session = "")
        app.cache.write(owner, "selected-session", scope.session, protected = true); saved["session:${owner.key}"] = scope.session; forgottenGone = null
    }
    private suspend fun savePending(send: PendingSend) {
        val encoded = send.encode()
        app.cache.write(send.scope, "pending", encoded, true)
        pendingStored[send.scope.key] = send to encoded
    }
    private suspend fun deletePending(send: PendingSend): Boolean {
        val stored = pendingStored[send.scope.key]
        val expected = stored?.takeIf { it.first == send }?.second ?: send.encode()
        val deleted = app.cache.delete(send.scope, "pending", expected)
        if (deleted && pendingStored[send.scope.key] === stored) pendingStored.remove(send.scope.key)
        return deleted
    }
    fun send(queued: QueuedMessage? = null) = viewModelScope.launch {
        if (_attachmentPreparation.value.isNotEmpty()) { notice("Finish or remove attachments before sending"); return@launch }
        if (!admitConversation()) return@launch
        // P2-2: with no connection (an app started offline has no client) Send still works: the words wait as "Not sent" and go once Hermes is back.
        val active = client ?: run { if (queued == null) queueOffline(); return@launch }
        val scope = sourceScope ?: run { notice("Open a chat first."); return@launch }
        val c = if (clientCreatedSessions.contains(scope.key)) active else active.detached()
        val d = queued?.let { Draft(it.text, attachments = it.attachments) } ?: draft().copy(queued = emptyList())
        var generation = sessionGeneration
        val origin = originGeneration
        var request = ScopeSettlement(scope, active, origin, generation)
        if (queued != null && phoneQueueHead()
                ?.let { it.id == queued.id && it.submissionId == queued.submissionId && it.state == "queued" } != true) return@launch
        if (_chat.value.raw.length() == 0) { notice("Wait for this chat to load."); return@launch }
        if (app.calls.state.value.active || _chat.value.approval != null || _chat.value.clarification != null) { notice("Finish the call or answer the request first."); return@launch }
        if (_chat.value.raw.optBoolean("read_only") || _chat.value.raw.optBoolean("is_read_only")) { notice("This chat is read-only. Branch it to continue."); return@launch }
        if (d.text.isBlank() && d.attachments.isEmpty()) return@launch
        if (pending[scope.key] != null) { notice("Check that your last message arrived first."); return@launch }
        if (_chat.value.streamId.isNotBlank()) {
            if (queued == null) {
                val action = _busySendOverride.value ?: preferences.value.takeIf { it.busySendLoaded }?.busySendAction
                if (d.attachments.isNotEmpty() || action == "queue") queue()
                else if (action == "steer") steer() else notice("Chat settings are loading. Try again.")
            }
            return@launch
        }
        if (queued == null && _chat.value.queued.any { it.kind == "queue" && it.state !in setOf("sent", "settled") }) { queue(); return@launch }
        val send = PendingSend.create(scope, d, queued).copy(afterRowId = pendingSendBoundary(_chat.value.messages)).let { pending ->
            if (queued == null) pending.copy(model = _chat.value.raw.string("model"), provider = _chat.value.raw.string("model_provider")) else pending
        }
        pending[scope.key] = send
        if (queued == null) sentComposerRevision[scope.key] = send.id to composerRevision
        var attempted = false
        var ownHeld = false
        if (sourceScope == scope) _chat.update { it.copy(uncertain = send.id, status = "Sending") }
        try {
            // Nothing was sent if the receipt cannot be kept: settle the in-flight state instead of reporting an unknown outcome.
            try { savePending(send) }
            catch (e: Throwable) {
                if (e is CancellationException) throw e
                pending.remove(scope.key, send)
                if (sourceScope == scope && _chat.value.uncertain == send.id) _chat.update { it.copy(uncertain = "", status = "") }
                throw NativeTurnNotSent(if (draftStoreFull(e)) DRAFT_STORE_FULL_ERROR else "Couldn't save the message. Keep the draft open and try again.", e)
            }
            if (queued != null) {
                var marked = false
                val saved = updateQueued(scope, queued.id, { request.current() && phoneQueueHead()
                    ?.let { it.id == queued.id && it.submissionId == queued.submissionId && it.state == "queued" } == true }) {
                    if (it.submissionId == queued.submissionId && it.state == "queued") { marked = true; it.copy(state = "sending", outcome = "") } else it
                }
                if (!saved || !marked) {
                    pending.remove(scope.key, send); deletePending(send)
                    if (request.current() && _chat.value.uncertain == send.id) _chat.update { it.copy(uncertain = "", status = "") }
                    return@launch
                }
            }
            val state = _chat.value
            val blocked = queued != null && (app.calls.state.value.active || state.approval != null || state.clarification != null ||
                state.streamId.isNotBlank() || state.liveText.isNotBlank() || state.liveTools.isNotEmpty())
            if (!request.current() || !foreground || blocked) {
                if (queued != null) updateQueued(scope, queued.id) { if (it.submissionId == queued.submissionId && it.state == "sending") it.copy(state = "queued") else it }
                // S1 (owner 17:07): a direct send stopped here never reached Hermes. It waits in its chat's box as Not sent, with its words and files and
                // the line that says so, and goes by itself once the chat is followed again (its drain); it used to vanish back into the composer.
                else try { returnUnsentWords(scope, send, offline = true) }
                    catch (save: Throwable) { if (save is CancellationException) throw save; if (sourceScope == scope) fail(save); return@launch }
                pending.remove(scope.key, send); deletePending(send)
                if (request.current() && _chat.value.uncertain == send.id) _chat.update { it.copy(uncertain = "", status = "") }
                if (queued == null && sourceScope == scope) notice("Couldn't send it now. It's in the queue.")
                return@launch
            }
            val model = queued?.model ?: _chat.value.raw.string("model")
            val provider = queued?.provider ?: _chat.value.raw.string("model_provider")
            if (queued == null) releaseSentComposer(scope, send, request)
            attempted = true
            sendsOnTheirWay += send.submissionId
            if (c === active) { ownHeld = true; ownSendsOnTheirWay.merge(c, 1, Int::plus) }
            val o = c.post("/api/chat/start", json("session_id" to scope.session, "message" to d.text, "attachments" to JSONArray(d.attachments), "profile" to scope.profile, "client_request_id" to send.submissionId, "model" to model.ifBlank { null }, "model_provider" to provider.ifBlank { null }, "queued_model_snapshot" to (queued != null), "explicit_model_pick" to (saved.get<Boolean>("model_pick:${scope.key}") == true)))
            sendsOnTheirWay -= send.submissionId
            if (ownHeld) { ownHeld = false; ownSendDone(c) }
            linkFailures.remove(send.submissionId)
            // Follow-up 11: the creating socket owns only the first prompt Hermes took. That is a fact about the chat, not the screen: a screen change
            // between the tap and the answer (which skips accepted() below) must not leave the chat "created here", or its next send rides the chat's own
            // client and a failure there closed the chat's socket (the follow-up 10 cut, window 4).
            if (c === active && o.string("status") != "unknown") { clientCreatedSessions.remove(scope.key); optionalLocalSave { app.cache.delete(scope, "client-created-shell") } }
            // Hermes took the prompt and runs the turn whatever the screen did meanwhile: the same chat opened again (a new selection of it) still learns the outcome of its own send.
            // Opening the chat again also restores its stored send as a new object, so the send is matched by its identity, not by reference.
            val restored = pending[scope.key]
            if (sessionGeneration != generation && sourceScope == scope && originGeneration == origin && client === active &&
                restored != null && restored.id == send.id && restored.submissionId == send.submissionId) {
                pending[scope.key] = send
                generation = sessionGeneration; request = ScopeSettlement(scope, active, origin, generation)
            }
            if (sourceScope != scope || sessionGeneration != generation || originGeneration != origin || client !== active || pending[scope.key] !== send) return@launch
            when (o.string("status")) {
                "queued" -> acceptedWithoutRow(scope, o, send, request)
                "terminal" -> {
                    acceptedWithoutRow(scope, o, send, request)
                    if (request.current()) {
                        updateQueued(scope, send.id) { it.copy(state = "settled", outcome = o.string("outcome")) }
                        drainQueued(scope, active, generation, origin)
                    }
                }
                "unknown" -> {
                    if (queued != null) updateQueued(scope, queued.id) { it.copy(state = "unknown", admissionId = o.string("admission_id")) }
                    val unknown = send.copy(admissionId = o.string("admission_id"))
                    savePending(unknown); pending[scope.key] = unknown
                    if (send.draft.attachments.isNotEmpty() || o.rows("attachment_receipts").isNotEmpty()) {
                        val attachments = keptAttachmentReceipts(o, send.draft) ?: JSONArray()
                        val payload = attachmentRecord(attachments, send.draft.text, send.submissionId)
                        optionalLocalSave { app.cache.write(scope, pendingAttachmentsKind(send.submissionId), payload) }
                        if (o.string("user_row_id").isNotBlank()) {
                            optionalLocalSave { app.cache.write(scope, "attachments:${o.optLong("user_row_id")}", payload) }
                        }
                    }
                    if (sourceScope == scope) _chat.update { it.copy(status = "") }
                }
                else -> accepted(scope, c, o, send, request)
            }
        } catch (e: Throwable) {
            sendsOnTheirWay -= send.submissionId
            if (ownHeld) { ownHeld = false; ownSendDone(c) }
            if (e is CancellationException) throw e
            // A send that could not reach Hermes says so in the app's banner too, not only by giving the draft back. A file that could not get through on its
            // own connection says nothing about Hermes being reachable (CHAT-121 follow-up 9).
            if (e !is AttachmentStalled) HermesReach.failed(scope.origin, e)
            // Follow-up 5: the answer was lost. Its staged attachments are saved with it (asking again re-sends them unchanged), the chat is listed so the
            // send is asked again even once the chat is left, and it is asked the next time Hermes answers (not before).
            // (Matched by identity: opening the chat again meanwhile restores the same send as a new object.)
            // Chat join (Root): a file that could not get through is never asked again, even when its one frame's answer was lost (AttachmentStalled
            // then wraps NATIVE_OUTCOME_UNKNOWN); it stays "Not sent" with its Send (fileNotSent).
            val lostAnswer = attempted && answerLost(e) && e !is AttachmentStalled && pending[scope.key]?.id == send.id
            if (lostAnswer) {
                askedAgain[send.id] = reachEpoch
                generateSequence(e) { it.cause }.take(8).filterIsInstance<NativeSubmitUnknown>().firstOrNull()?.takeIf { it.staged.length() > 0 }?.let { lost ->
                    val kept = send.copy(staged = lost.staged.toString())
                    try { savePending(kept); pending[scope.key] = kept } catch (save: Throwable) { if (save is CancellationException) throw save }
                }
                optionalLocalSave { rememberOutbox(scope) }
            }
            if (sourceScope != scope || sessionGeneration != generation || originGeneration != origin || client !== active) { if (lostAnswer) lookForHermesAfterLostAnswer(scope.origin); return@launch }
            if (attempted && pending[scope.key] !== send && !lostAnswer) return@launch
            val gone = attempted && !lostAnswer && !requestNeverSent(e) && e !is AttachmentStalled && beaconChatAbsent(active, scope, e)
            if (!request.current()) return@launch
            val notSent = gone || e is NativeTurnNotSent || requestNeverSent(e)
            // A file Hermes refused stays refused: a queued message with it waits for the owner instead of being asked again (each try would stage the
            // files that did pass once more, and Hermes keeps no way to take them back).
            // Out of reach: the native gateway reports a send that never left the phone as NativeTurnNotSent, often with no connect error in its chain,
            // so Hermes' reach state and the network count too. A refusal with its own words (model, attachment, saving) is never "offline".
            val refusal = refusedWithItsOwnWords(e)
            val unreachable = requestNeverSent(e) || scope.origin in HermesReach.origins.value || !networkValidated
            // Follow-up 11 (Root msg_ec3f92a4d60a): Hermes answers but the link could not carry this send now. It waits in the box with the outbox's look and
            // words, never back in the composer, and goes again by itself under the same submission id, at most ATTACHMENT_STALL_TRIES tries in all; then
            // it is "Not sent" with its Send, and nothing sends it again by itself.
            val carried = !gone && notSent && !refusal && !unreachable && linkCouldNotCarry(e)
            val linkPause = if (carried) nextLinkTryPause(linkFailures.merge(send.submissionId, 1, Int::plus) ?: 1) else null
            val linkSpent = carried && linkPause == null
            val offline = !gone && notSent && !refusal && (unreachable || linkPause != null)
            // TURN-121's one hold (QueuedMessage.held, saved with the draft): the refusal's own line is why it waits for the owner's Send.
            // CHAT-121 follow-up 8: waitsForOwner adds a file that stalled three times (a retry would only stall again).
            val holdReason = if (queued != null) queuedHoldReason(e) else ""
            // CHAT-121 follow-up 9 (chat join): a file that could not get through is saved as "Not sent" (fileNotSent), never as offline, so neither the
            // outbox nor a drain sends it again by itself; only its row's Send does. One helper keeps the save and its in-memory fallback the same.
            fun failed(item: QueuedMessage) = if (gone) item.copy(state = "settled", outcome = CHAT_GONE_OUTCOME, held = "") else item.copy(state = if (notSent) "queued" else "unknown",
                outcome = if (e is AttachmentStalled || linkSpent) FILE_NOT_SENT_OUTCOME else if (notSent && offline) OFFLINE_OUTCOME else item.outcome, held = holdReason.ifBlank { item.held })
            if (queued != null) {
                try { updateQueued(scope, queued.id) { failed(it) } }
                catch (save: Throwable) {
                    if (save is CancellationException) throw save
                    if (request.current()) {
                        _chat.update { current -> current.copy(status = "", queued = current.queued.map { if (it.id == queued.id) failed(it) else it }) }
                        notice(if (draftStoreFull(save)) DRAFT_STORE_FULL_ERROR else "Couldn't save this change. Keep the chat open and check the message.")
                    }
                    return@launch
                }
            }
            if (notSent) {
                // The words go back first: if that cannot be saved, the receipt keeps them and the chat asks to check the message.
                if (queued == null) try { returnUnsentWords(scope, send, offline, page = sendFailureView(e) == SendFailureView.BeaconOffPage, gone = gone) } catch (save: Throwable) { if (save is CancellationException) throw save; if (sourceScope == scope) fail(save); return@launch }
                try {
                    if (deletePending(send)) { pending.remove(scope.key, send); sentComposerRevision.remove(scope.key) }
                } catch (save: Throwable) { if (save is CancellationException) throw save; if (sourceScope == scope) fail(save); return@launch }
                if (sourceScope == scope && pending[scope.key] == null) _chat.update { it.copy(uncertain = "", status = "") }
            }
            if (sourceScope == scope && _chat.value.uncertain == send.id) _chat.update { it.copy(status = "") }
            if (sourceScope == scope && sessionGeneration == generation && originGeneration == origin && client === active) {
                if (gone) {
                    recoverGoneBeaconChat(scope) { request.current() }
                    return@launch
                }
                when (sendFailureView(e)) {
                    // Nothing was sent and its receipt is gone: the chat stays this chat, its words stay in the composer and the draft, and the page says why with its button (as for a chat that
                    // could not be opened for the same reason); the button opens this chat again.
                    SendFailureView.BeaconOffPage -> retainDraftAndLeave(scope, scope.session, generation, beaconOffLine(e))
                    // D6: the words wait in the queue box and go by themselves.
                    SendFailureView.Notice, SendFailureView.Error -> if (offline && queued == null) noticeNotSentYet(scope)
                        // Follow-up 11: a message the link could not carry that waits again in its row says nothing more.
                        else if (linkPause != null) Unit
                        else if (sendFailureView(e) == SendFailureView.Error) fail(e)
                        // A refused file is named: its line is translated here (the name stays as it is) and shown as it comes.
                        else if (e is AttachmentRefused) notice(dev.hermes.android.push.serviceText(app, e.line).replace("{0}", e.file).replace("{1}", e.limitMb))
                        // CHAT-7: the plugin could not take a large file now (every phone's files at its memory budget, or this phone's other file in flight): the busy line.
                        else if (nativeGatewayBusy(e)) notice(appErrorText(e))
                        else notice(e.message?.takeIf { it in SEND_REFUSALS } ?: "Couldn't send the message. Review it and try again.")
                }
            }
            if (!notSent && request.current()) {
                val receiptClient = active.detached()
                try { reconcile(scope, receiptClient) }
                catch (read: Throwable) { if (read is CancellationException) throw read }
                finally { receiptClient.closeNative() }
            }
            if (linkPause != null) retryAfterLink(scope, active, send.submissionId, linkPause)
            if (lostAnswer) lookForHermesAfterLostAnswer(scope.origin)
        }
    }
    /** Follow-up 11: the tries the link could not carry, by submission id (main thread; a restart starts again, as the outbox does when Hermes answers). */
    private val linkFailures = mutableMapOf<String, Int>()
    /** The chat's next try of a message the link could not carry, by scope key. */
    private val linkRetries = mutableMapOf<String, Job>()
    /** After [pauseMs], the open chat's queue goes again if [submission] still waits at its head (the drain checks Hermes' turn first, as always). */
    private fun retryAfterLink(scope: Scope, active: BeaconClient, submission: String, pauseMs: Long) {
        linkRetries[scope.key]?.cancel()
        val job = viewModelScope.launch {
            delay(pauseMs)
            // Away from the app meanwhile: it goes once the app is back (leaving the chat instead is fine: opening a chat drains its box).
            while (!foreground && sourceScope == scope && client === active) delay(1_000)
            if (sourceScope != scope || client !== active) return@launch
            val head = phoneQueueHead()?.takeIf { it.submissionId == submission && it.waitingForConnection() } ?: return@launch
            if (head.held.isBlank()) drainQueued(scope, active)
        }
        linkRetries[scope.key] = job
        job.invokeOnCompletion { if (linkRetries[scope.key] === job) linkRetries.remove(scope.key) }
    }
    /** A try of [item] whose drain could not even ask Hermes (the link): it counts, and the next one is planned or the message is "Not sent" with Send. */
    private suspend fun linkTryFailed(scope: Scope, active: BeaconClient, item: QueuedMessage) {
        val pause = nextLinkTryPause(linkFailures.merge(item.submissionId, 1, Int::plus) ?: 1)
        if (pause != null) retryAfterLink(scope, active, item.submissionId, pause)
        else updateQueued(scope, item.id) { if (it.submissionId == item.submissionId && it.waitingForConnection()) it.copy(outcome = FILE_NOT_SENT_OUTCOME) else it }
    }
    /** D10: the composer lets go of the words being sent (they stay in [send]); edits made since the tap are the next message and are kept. */
    private suspend fun releaseSentComposer(scope: Scope, send: PendingSend, request: ScopeSettlement) {
        val revision = sentComposerRevision[scope.key]?.takeIf { it.first == send.id }?.second ?: return
        if (!request.current() || composerRevision != revision || _composer.value.text != send.draft.text ||
            _attachments.value.map { it.toString() } != send.draft.attachments.map { it.toString() }) return
        _composer.value = TextFieldValue(); _attachments.value = emptyList(); composerRevision++
        checkpointNow()
    }
    /**
     * D6, D10: a direct send that never reached Hermes gets its words back, never lost. They are saved with that chat whether or not it is still
     * open, before the receipt that holds them is deleted. S1 (owner's queue rule, owner 17:07): it always waits in the queue box as "Not sent" with
     * its words and files, never silently back in the draft. Words the send still held in the composer (heldBySend) leave it for the box. One that
     * never left the phone ([offline]) keeps its submission id and goes by itself once Hermes answers again, so it is never sent twice.
     * G3H ([page]): the Beacon-off page answers a send with its own line and Retry, and its words stay in (or go back to) the composer instead.
     */
    private suspend fun returnUnsentWords(scope: Scope, send: PendingSend, offline: Boolean, page: Boolean = false, gone: Boolean = false) {
        val words = send.draft
        fun same(text: String, files: List<JSONObject>) = text == words.text && files.map { it.toString() } == words.attachments.map { it.toString() }
        val item = QueuedMessage(words.text, words.attachments, send.model, send.provider, id = send.id, createdAt = send.createdAt,
            state = if (offline) "queued" else "settled", outcome = if (gone) CHAT_GONE_OUTCOME else if (offline) OFFLINE_OUTCOME else NOT_SENT_OUTCOMES.first(),
            submissionId = send.submissionId, afterRowId = send.afterRowId)
        if (sourceScope == scope) checkpointJob?.cancelAndJoin()
        draftWriteLock.withLock {
            val saved = if (sourceScope == scope) draft() else drafts[scope.key] ?: app.cache.read(scope, draftKind(scope))?.let(Draft::decode) ?: Draft()
            if (saved.queued.any { it.id == item.id }) return@withLock
            val held = words.text.isNotEmpty() || words.attachments.isNotEmpty()
            val leaves = held && same(saved.text, saved.attachments)
            if (page && leaves) return@withLock
            val back = page && saved.text.isBlank() && saved.attachments.isEmpty()
            val end = words.text.length
            val next = if (back) saved.copy(text = words.text, selectionStart = end, selectionEnd = end, attachments = words.attachments)
                else (if (leaves) saved.copy(text = "", selectionStart = 0, selectionEnd = 0, attachments = emptyList()) else saved).let { it.copy(queued = it.queued + item) }
            // The open chat shows them at once (nothing runs between reading its composer and this); the saved copy follows.
            if (sourceScope == scope) {
                if (back) { _composer.value = TextFieldValue(words.text, TextRange(end)); _attachments.value = words.attachments; composerRevision++ }
                else {
                    if (leaves) { _composer.value = TextFieldValue(); _attachments.value = emptyList(); composerRevision++ }
                    _chat.update { state -> if (state.queued.any { it.id == item.id }) state else state.copy(queued = state.queued + item) }
                }
            }
            val encoded = next.encode(); app.cache.write(scope, draftKind(scope), encoded, true); drafts[scope.key] = next; savedDraft(scope, encoded)
        }
        if (offline) rememberOutbox(scope)
    }
    /**
     * P2-2: Send with no connection. The composer's words wait in the queue box as "Not sent" (a new submission id, so Hermes takes them
     * once) and go by themselves when Hermes answers again; [payload] is a Queue tap's own words.
     */
    private suspend fun queueOffline(payload: String? = null) {
        val scope = sourceScope ?: run { notice("Open a chat first."); return }
        val state = _chat.value
        if (state.raw.length() == 0) { notice("Wait for this chat to load."); return }
        if (state.raw.optBoolean("read_only") || state.raw.optBoolean("is_read_only")) { notice("This chat is read-only. Branch it to continue."); return }
        val before = draft()
        val words = if (payload != null) Draft(payload) else before.copy(queued = emptyList())
        if (words.text.isBlank() && words.attachments.isEmpty()) return
        val send = PendingSend.create(scope, words).copy(model = state.raw.string("model"), provider = state.raw.string("model_provider"), afterRowId = pendingSendBoundary(state.messages))
        // The composer lets go first, so the words go to the queue box (returnUnsentWords leaves words that are still in the composer there).
        if (payload == null) { _composer.value = TextFieldValue(); _attachments.value = emptyList(); composerRevision++ }
        try { returnUnsentWords(scope, send, offline = true) }
        catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (payload == null && _composer.value.text.isEmpty() && _attachments.value.isEmpty()) {
                _composer.value = TextFieldValue(words.text, TextRange(words.text.length)); _attachments.value = words.attachments; composerRevision++
            }
            notice(if (draftStoreFull(e)) DRAFT_STORE_FULL_ERROR else "Couldn't save the message. Keep the draft open and try again."); return
        }
        noticeNotSentYet(scope)
    }
    /** "Not sent yet" is raised for the account and profile of [scope] (follow-up 3, Root): only that owner's state ends it, and leaving that owner clears it ([dropNotSentYet]). */
    private fun noticeNotSentYet(scope: Scope) { offlineNoticeOwner = scope.copy(session = ""); notice(OFFLINE_SEND_NOTICE) }
    /** The owner left the account or profile the notice was raised for (a profile switch, another account): it goes; that chat's row still says "Not sent". */
    private fun dropNotSentYet() { offlineNoticeOwner = null; _ui.update { if (it.notice == OFFLINE_SEND_NOTICE) it.copy(notice = "") else it } }
    private var offlineNoticeOwner: Scope? = null
    // P2-3: the chats of an account and profile that hold "Not sent" messages, saved so a restart keeps them.
    private val outboxLock = Mutex()
    /** CHATQA-121 follow-up 5: the times Hermes answered again (network back, reach check through, connected); a lost answer is asked once per time (main thread). */
    private var reachEpoch = 0
    private val askedAgain = mutableMapOf<String, Int>()
    /** The ask on its way per send: another report of the same return (network, reach check, reconnect) finds it and asks nothing more. */
    private val askJobs = mutableMapOf<String, Job>()
    /** The submission ids whose first request is on its way now (a send, or the outbox's): they are never asked about until it answers. */
    private val sendsOnTheirWay = mutableSetOf<String>()
    /** S1d: own clients a direct send waits on (a new chat's first prompt rides the client that made it); a replaced one closes once its answer is in. */
    private val ownSendsOnTheirWay = java.util.concurrent.ConcurrentHashMap<BeaconClient, Int>()
    private fun ownSendDone(c: BeaconClient) {
        if (ownSendsOnTheirWay.computeIfPresent(c) { _, n -> (n - 1).takeIf { it > 0 } } == null && c !== client && c !== settingsClient) c.closeNative()
    }
    private var hermesWasUnreachable = false
    private fun bumpReachEpoch() { reachEpoch++ }
    private fun hermesAnsweredAgain() { bumpReachEpoch(); askAgainUnconfirmed() }
    /** The chats whose waiting messages are going now, by scope key (main thread only). */
    private val outboxSends = mutableMapOf<String, Job>()
    private suspend fun readOutbox(owner: Scope): List<String> =
        app.cache.read(owner, OFFLINE_OUTBOX_KIND)?.let { raw -> runCatching { JSONArray(raw).let { a -> (0 until a.length()).map(a::optString) } }.getOrNull() }.orEmpty().filter(String::isNotBlank)
    // CHATFIX-121 follow-up 1 (JOINBFIX's live check): "Not sent yet" goes once nothing waits any more, in the open chat's box or another chat's.
    // It is asked when the open chat's box stops waiting, and (follow-up 2) when another chat's last waiting message goes ([drainOfflineOutbox]).
    init { viewModelScope.launch { _chat.map { boxHoldsWaiting(it.queued) }.distinctUntilChanged().collect { waiting -> if (!waiting) endOfflineNoticeWhenNothingWaits() } } }
    private suspend fun endOfflineNoticeWhenNothingWaits() {
        val open = sourceScope ?: return
        if (boxHoldsWaiting(_chat.value.queued) || _ui.value.notice != OFFLINE_SEND_NOTICE) return
        val outbox = try { readOutbox(open.copy(session = "")) } catch (e: Exception) { if (e is CancellationException) throw e; return }
        // Decided again after the read: the chat may have changed meanwhile, and its box is the one that must be empty.
        if (sourceScope == open && offlineNoticeEnds(offlineNoticeOwner, open, boxHoldsWaiting(_chat.value.queued), outbox))
            _ui.update { if (it.notice == OFFLINE_SEND_NOTICE) it.copy(notice = "") else it }
    }
    private suspend fun rememberOutbox(scope: Scope) = outboxLock.withLock {
        val owner = scope.copy(session = "")
        val ids = readOutbox(owner)
        if (scope.session !in ids) app.cache.write(owner, OFFLINE_OUTBOX_KIND, JSONArray(ids + scope.session).toString(), true)
    }
    private suspend fun forgetOutbox(scope: Scope) = outboxLock.withLock {
        val owner = scope.copy(session = "")
        val ids = readOutbox(owner)
        if (scope.session in ids) app.cache.write(owner, OFFLINE_OUTBOX_KIND, JSONArray(ids - scope.session).toString(), true)
    }
    /**
     * P2-3: Hermes answers again. Every chat's "Not sent" messages go, open or not: the open chat's through its own queue, any other
     * chat's here, each once (claimed before it goes, with its own submission id) and in order (the next waits until Hermes took the one before).
     */
    internal fun drainOfflineOutbox(openQueue: Boolean = true): Job? {
        val owner = client?.scope?.copy(session = "") ?: return null
        return viewModelScope.launch {
            for (id in readOutbox(owner)) {
                if (outboxClient(owner) == null) return@launch
                val scope = owner.session(id)
                // The open chat goes through its own queue; every other chat on its own, side by side. A chat already going keeps going.
                if (sourceScope == scope) { if (openQueue) drainOpenQueue(); continue }
                if (outboxSends[scope.key]?.isActive == true) continue
                val job = viewModelScope.launch {
                    try { if (!sendWaiting(owner, scope)) { forgetOutbox(scope); endOfflineNoticeWhenNothingWaits() } }
                    catch (e: Throwable) { if (e is CancellationException) throw e }
                }
                outboxSends[scope.key] = job
                job.invokeOnCompletion { if (outboxSends[scope.key] === job) outboxSends.remove(scope.key) }
            }
        }
    }
    /** The client of the account and profile [owner] while it is the current one (a reconnect gives a new one; a switch away ends the outbox's work). */
    private fun outboxClient(owner: Scope): BeaconClient? = client?.takeIf { it.scope.copy(session = "") == owner }
    /** Sends the waiting messages of [scope], a chat that is not open, one by one; true while some still wait (Hermes out of reach again, or the chat opened). */
    private suspend fun sendWaiting(owner: Scope, scope: Scope): Boolean {
        while (true) {
            if (outboxClient(owner) == null || sourceScope == scope) return true
            // Follow-up 5: a send of this chat whose answer was lost settles first; until it does, nothing behind it goes.
            if (!askAgainSaved(owner, scope)) return true
            // A chat stays listed while any of its "Not sent" messages still waits (behind one Hermes must settle first) or is going now.
            var stillWaiting = false
            val waiting = editSavedDraft(scope) { draft ->
                stillWaiting = draft.queued.any { it.kind == "queue" && (it.waitingForConnection() || it.state == "sending") }
                nextOfflineToSend(draft) to draft
            } ?: return sourceScope == scope || stillWaiting
            // As the open chat's queue does, a message goes only once the chat's reply is over (a Hermes without session authority refuses one
            // sent into a running turn), so the next one waits for the one before it.
            if (!awaitChatIdle(owner, scope)) return true
            val c = outboxClient(owner)?.detached() ?: return true
            // TURN's drain rule (chat join): the turn this message waited behind failed, and retrying does not help now. The messages that waited
            // are held with its line, as the open chat holds them; only their Send releases them.
            val hold = try { failedTurnHold(c.get("/api/session", mapOf("session_id" to scope.session)).let { it.optJSONObject("session") ?: it }.rows("messages")) }
                catch (e: Throwable) { if (e is CancellationException) throw e; c.closeNative(); return true }
            if (hold != null) {
                c.closeNative()
                editSavedDraft(scope) { draft -> Unit to draft.copy(queued = holdBehindFailedTurn(draft.queued, hold.first, hold.second)) }
                return true
            }
            // Claimed first: "sending" is saved before it goes, so a restart never sends it a second time (a reopened chat asks Hermes instead).
            val item = editSavedDraft(scope) { draft ->
                val next = nextOfflineToSend(draft)?.takeIf { it.id == waiting.id }
                next to (if (next == null) draft else draft.copy(queued = draft.queued.map { if (it.id == next.id) it.copy(state = "sending") else it }))
            } ?: run { c.closeNative(); return true }
            // Follow-up 5: saved before it goes, as the open chat's send is, so a lost answer (or a restart) can be asked about again under this identity.
            val record = PendingSend.create(scope, Draft(item.text, attachments = item.attachments), item)
            optionalLocalSave { app.cache.write(scope, "pending", record.encode(), true) }
            var lostAnswer = false
            var lostStaged = ""
            sendsOnTheirWay += item.submissionId
            val after = try {
                val o = c.post("/api/chat/start", json("session_id" to scope.session, "message" to item.text, "attachments" to JSONArray(item.attachments), "profile" to scope.profile,
                    "client_request_id" to item.submissionId, "model" to item.model.ifBlank { null }, "model_provider" to item.provider.ifBlank { null }, "queued_model_snapshot" to true))
                if (o.string("status") == "unknown") item.copy(state = "unknown", admissionId = o.string("admission_id"))
                else item.copy(state = "accepted", outcome = "", admissionId = o.string("admission_id"))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                val refused = refusedWithItsOwnWords(e)
                if (!refused && (e is NativeTurnNotSent || requestNeverSent(e))) item.copy(state = "queued", outcome = OFFLINE_OUTCOME)
                else if (refused) item.copy(state = "settled", outcome = NOT_SENT_OUTCOMES.first())
                else {
                    lostAnswer = answerLost(e)
                    lostStaged = generateSequence(e) { it.cause }.take(8).filterIsInstance<NativeSubmitUnknown>().firstOrNull()?.staged?.takeIf { it.length() > 0 }?.toString().orEmpty()
                    item.copy(state = "unknown")
                }
            } finally { c.closeNative(); sendsOnTheirWay -= item.submissionId }
            // The record goes once Hermes answered either way; a lost answer keeps it (with its staged attachments) to be asked again the next time Hermes answers.
            if (lostAnswer) { askedAgain[record.id] = reachEpoch; optionalLocalSave { app.cache.write(scope, "pending", record.copy(staged = lostStaged).encode(), true) } }
            else optionalLocalSave { app.cache.delete(scope, "pending", record.encode()) }
            // The chat may have opened meanwhile: its own queue then shows the outcome and settles it with Hermes.
            // Follow-up 5: opened while this went, the chat restored its saved record as unconfirmed; asked now, Hermes' answer settles it.
            if (sourceScope == scope) { updateQueued(scope, item.id) { if (it.id == item.id) after else it }; askAgainUnconfirmed(); return true }
            // Hermes took it: it is the chat's now (its history shows it), so it leaves the box; any other outcome stays for the chat to settle.
            editSavedDraft(scope) { draft -> Unit to draft.copy(queued = if (after.state == "accepted") draft.queued.filterNot { it.id == item.id } else draft.queued.map { if (it.id == item.id) after else it }) }
            // Follow-up 3: Hermes took it, so this phone has a run in that chat it has not seen end, as after the open chat's send; opening the chat asks about it.
            if (after.state == "accepted") optionalLocalSave { app.cache.write(scope, RUN_MARKER, item.submissionId) }
            if (lostAnswer) return true
            if (after.state != "accepted") return after.waitingForConnection()
        }
    }
    /**
     * Follow-up 5: the outbox's side of the ask. A chat that is not open and holds a saved send whose answer was lost asks Hermes once more under the
     * same identity (once per time Hermes answers again). True when nothing waits on such a send any more.
     */
    private suspend fun askAgainSaved(owner: Scope, scope: Scope): Boolean {
        val raw = app.cache.read(scope, "pending") ?: return true
        val send = runCatching { PendingSend.decode(scope, raw) }.getOrNull() ?: return false
        if (!askableAgain(send) || send.submissionId in sendsOnTheirWay || askedAgain[send.id] == reachEpoch) return false
        askedAgain[send.id] = reachEpoch
        val c = outboxClient(owner)?.detached() ?: return false
        val answered = try {
            if (!c.nativeSessionAuthority()) return false
            c.post("/api/chat/start", askAgainBody(scope, send)).let { o ->
                // Hermes holds it but its outcome is unknown: it stays unconfirmed (with its admission) until Hermes answers again.
                if (o.string("status") != "unknown") true
                else { optionalLocalSave { app.cache.write(scope, "pending", send.copy(admissionId = o.string("admission_id")).encode(), true) }; false }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // admission_conflict: Hermes holds a send under that id, so its own row is the message; anything else is lost again.
            admissionConflict(e)
        } finally { c.closeNative() }
        if (!answered || sourceScope == scope) return false
        // Hermes has it: the record and the box's copy go (its history shows the message), and opening the chat follows its reply.
        app.cache.delete(scope, "pending", raw)
        pending[scope.key]?.takeIf { it.id == send.id }?.let { pending.remove(scope.key, it) }
        if (send.queued) editSavedDraft(scope) { draft -> Unit to draft.copy(queued = draft.queued.filterNot { it.id == send.id }) }
        optionalLocalSave { app.cache.write(scope, RUN_MARKER, send.submissionId) }
        return true
    }
    /**
     * The chat [scope] has no reply running on Hermes. Asked again with a growing pause (3 s up to 30 s, at most [OUTBOX_IDLE_WAIT_MS]): a reply
     * that runs long, or a state Hermes cannot name yet (a turn just settling), is waited out. False once Hermes is out of reach or the chat opened.
     */
    private suspend fun awaitChatIdle(owner: Scope, scope: Scope): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + OUTBOX_IDLE_WAIT_MS
        var pause = 3_000L
        while (true) {
            if (sourceScope == scope) return false
            val probe = outboxClient(owner)?.detached() ?: return false
            // A look at the turn (WEDGE): a lost reply is closed only as lostCloseFor lets it (never while this chat's saved box holds the owner's
            // messages), and a lost one ends the wait at once: the chat's own choice decides what goes next (R7).
            val status = try { probe.recoveredNativeSessionStatus(scope.session, lostCloseFor(scope)) }
                catch (e: Throwable) { if (e is CancellationException) throw e; return false }
                finally { probe.closeNative() }
            if (status == "idle") return true
            if (status == "lost") return false
            if (android.os.SystemClock.elapsedRealtime() > deadline) return false
            delay(pause); pause = minOf(pause * 2, 30_000L)
        }
    }
    /** Changes the saved draft of [scope], a chat that is not open, under the draft lock; null (nothing changed) once it is open. */
    private suspend fun <T> editSavedDraft(scope: Scope, change: (Draft) -> Pair<T?, Draft>): T? = draftWriteLock.withLock {
        if (sourceScope == scope) return@withLock null
        val saved = drafts[scope.key] ?: app.cache.read(scope, draftKind(scope))?.let(Draft::decode) ?: return@withLock null
        val (value, next) = change(saved)
        if (next != saved) { val encoded = next.encode(); app.cache.write(scope, draftKind(scope), encoded, true); drafts[scope.key] = next; savedDraft(scope, encoded) }
        value
    }
    /** D6: Hermes answers again (the network came back, or a reach check got through): a message waiting in the open chat's box goes now. */
    private fun drainOpenQueue() {
        val scope = sourceScope ?: return
        val active = client ?: return
        if (phoneQueueHead()?.state == "queued") drainQueued(scope, active)
    }
    /** D7: why a tap on Send would be refused now, or null when it sends. The button is off while this is not null; send() says the same line. */
    internal fun sendRefusal(): String? = when {
        !canonicalConversationReady(client?.scope ?: cachedOwnerScope, sourceScope, _chat.value.sessionId, _ui.value.tab == "Beacon", (client?.scope ?: cachedOwnerScope)?.let(::readBeaconSessionHandle),
            _ui.value.beaconOpening, _ui.value.beaconError.isNotBlank(), preferences.value.assistantProfile) -> if (_ui.value.tab == "Beacon") "Open ${dev.hermes.android.data.currentAssistantName} first. Your draft is kept." else "Open a chat first."
        _chat.value.raw.length() == 0 -> "Wait for this chat to load."
        else -> null
    }
    private suspend fun acceptedWithoutRow(scope: Scope, result: JSONObject, send: PendingSend, request: ScopeSettlement) {
        if (!request.current()) return
        saveUnlinkedAcceptance(scope, result, send, request)
        if (!request.current()) return
        if (send.draft.attachments.isNotEmpty() || result.rows("attachment_receipts").isNotEmpty()) {
            // A queued acceptance has no row yet; keep the sent files under the runtime and move them to
            // the persisted user row when its receipt settles (bindPersistedInput), so a reopen keeps them.
            val attachments = keptAttachmentReceipts(result, send.draft) ?: JSONArray()
            val payload = attachmentRecord(attachments, send.draft.text, send.submissionId)
            optionalLocalSave { app.cache.write(scope, pendingAttachmentsKind(send.submissionId), payload) }
            if (result.string("user_row_id").isNotBlank()) {
                optionalLocalSave { app.cache.write(scope, "attachments:${result.optLong("user_row_id")}", payload) }
            }
        }
        // Hermes admitted the message, row known or not, so it holds the sent files (file.attach answered before the submit): the copies kept on this phone go.
        // An admission Hermes cancelled before it started goes back to the box as "Not sent" and keeps its files for the retry.
        if (!(result.string("status") == "terminal" && result.string("outcome") in NOT_SENT_OUTCOMES)) releaseKeptFiles(scope, send.draft.attachments)
        check(deletePending(send)) { "The saved send receipt changed" }
        pending.remove(scope.key, send); sentComposerRevision.remove(scope.key)
        if (request.current() && _chat.value.uncertain == send.id) _chat.update { it.copy(uncertain = "", status = "") }
        if (result.string("status") == "queued" && request.current() && foreground && !followsHere(scope)) {
            val follower = client?.detached() ?: return
            try {
                val answer = follower.recoveredNativeSession(scope.session, lostCloseFor(scope)) ?: return
                // Hermes holds the new message behind a reply its restart cut: the owner chooses to send it.
                if (request.current() && foreground && answer.second == "lost") showLostChoice(scope)
                if (request.current() && foreground && answer.second in setOf("working", "waiting") && !followsHere(scope)) {
                    optionalLocalSave { app.cache.write(scope, RUN_MARKER, send.submissionId) }
                    if (!request.current() || !foreground) return
                    _chat.update { it.copy(streamId = answer.first, status = "Working") }
                    attachStream(follower, scope, answer.first)
                }
            } catch (e: Throwable) { if (e is CancellationException) throw e }
            finally { follower.closeNative() }
        }
    }
    private suspend fun saveUnlinkedAcceptance(scope: Scope, result: JSONObject, send: PendingSend, request: ScopeSettlement) {
        if (request.current()) _chat.update { it.copy(raw = it.raw.copy().put("_display_submission_id", send.submissionId).put("_display_admission_id", result.string("admission_id"))) }
        val item = if (send.queued) null else QueuedMessage(send.draft.text, send.draft.attachments, send.model, send.provider,
            id = send.id, createdAt = send.createdAt, state = "accepted", submissionId = send.submissionId, afterRowId = send.afterRowId)
        if (item != null) {
            val saved = draftWriteLock.withLock {
                if (!request.current()) return@withLock null
                val current = draft()
                val canClear = sentComposerRevision[scope.key] == (send.id to composerRevision)
                // Hermes says whether it started this admission or holds it queued behind another turn; blue ticks need that (QueuedMessage.queuedBehindAnotherTurn).
                val retained = item.copy(admissionId = result.string("admission_id"), admissionStatus = admissionStatusOf(result.string("status")))
                val queue = if (current.queued.any { it.id == item.id }) current.queued.map { if (it.id == item.id) retained else it } else current.queued + retained
                val next = (if (canClear) current.afterAccepted(send.draft) else current).copy(queued = queue)
                val encoded = next.encode(); app.cache.write(scope, draftKind(scope), encoded, true); drafts[scope.key] = next; savedDraft(scope, encoded)
                next to canClear
            } ?: return
            val (next, canClear) = saved
            if (request.current()) {
                if (canClear && sentComposerRevision[scope.key] == (send.id to composerRevision) && _composer.value.text == send.draft.text &&
                    _attachments.value.map { it.toString() } == send.draft.attachments.map { it.toString() }) {
                    _composer.value = TextFieldValue(); _attachments.value = emptyList(); composerRevision++
                }
                val retained = next.queued.first { it.id == item.id }
                _chat.update { current -> current.copy(queued = if (current.queued.any { it.id == item.id }) current.queued.map { if (it.id == item.id) retained else it } else current.queued + retained) }
                checkpointNow()
            }
        } else check(updateQueued(scope, send.id) { it.copy(state = "accepted", admissionId = result.string("admission_id"), afterRowId = send.afterRowId, admissionStatus = admissionStatusOf(result.string("status"))) })
    }
    private suspend fun accepted(scope: Scope, c: BeaconClient, result: JSONObject, send: PendingSend, request: ScopeSettlement) {
        val sent = send.draft
        require(result.string("stream_id").isNotBlank()) { "Run was accepted but stream resolution is pending. Check receipt." }
        val canonical = result.string("session_id", scope.session)
        require(canonical == scope.session) { "Conversation identity changed; reconcile the receipt" }
        require(c.scope == scope.copy(session = "")) { "The receipt belongs to another account" }
        if (request.current()) _chat.update { it.copy(raw = it.raw.copy().put("_display_submission_id", send.submissionId).put("_display_admission_id", result.string("admission_id"))) }
        val identified = send.copy(rowId = result.string("user_row_id"), admissionId = result.string("admission_id"))
        if (identified != send) {
            savePending(identified)
            if (!request.current()) return
            if (pending[scope.key]?.id == send.id) pending[scope.key] = identified
        }
        if (identified.rowId.isBlank()) saveUnlinkedAcceptance(scope, result, send, request)
        else if (send.queued) {
            check(updateQueued(scope, send.id) { it.copy(state = if (identified.rowId.isBlank()) "accepted" else "sent", rowId = identified.rowId, admissionId = identified.admissionId) }) {
                "This queued message changed. Check it before sending."
            }
        }
        if (send.draft.attachments.isNotEmpty() || result.rows("attachment_receipts").isNotEmpty()) {
            // The sent turn's own attachments are the receipt for this exact user row. Native file uploads
            // return attachment_receipts; an image attached directly to the turn returns the row without
            // them, so the draft's own attachments keep the row's local display identity after a reopen.
            val attachments = keptAttachmentReceipts(result, send.draft) ?: JSONArray()
            val payload = attachmentRecord(attachments, sent.text, send.submissionId)
            optionalLocalSave { app.cache.write(scope, pendingAttachmentsKind(send.submissionId), payload) }
            // Hermes holds the sent files now (its own copies under attachments/); the copies kept on this phone go.
            releaseKeptFiles(scope, send.draft.attachments)
            if (result.string("user_row_id").isNotBlank()) {
                optionalLocalSave { app.cache.write(scope, "attachments:${result.optLong("user_row_id")}", payload) }
            }
        }
        if (!request.current() || !promptDelivered(scope, identified, result, accepted = true, request = request)) return
        val frameKey = "${scope.key}:${result.getString("stream_id")}"
        liveFrames.remove(frameKey); persistedTurns.remove(frameKey)
        optionalLocalSave { app.cache.delete(scope, "live:${result.getString("stream_id")}") }
        if (!request.current()) return
        liveBoundaries[frameKey] = LiveAssistantBoundary()
        ++chatHistoryRevision
        if (request.current()) _chat.update { state ->
            val row = result.string("user_row_id")
            val receipt = result.optJSONArray("attachment_receipts") ?: JSONArray()
            val confirmed = json("row_id" to row, "role" to "user", "content" to sent.text, "attachments" to receipt, "timestamp" to System.currentTimeMillis() / 1000.0)
            val append = row.isNotBlank() && state.messages.none { it.id == row }
            state.copy(messages = if (append) state.messages + Message.from(confirmed, state.messages.size, scope.session) else state.messages,
                raw = if (append) state.raw.copy().put("messages", JSONArray(state.raw.rows("messages") + confirmed)) else state.raw,
                uncertain = "", streamId = result.getString("stream_id"), status = "Working", liveText = "", liveReasoning = "", liveTools = emptyList(), liveSealed = emptyList(), liveSealedAt = emptyList(), liveBase = 0, liveBasePrompt = "", liveOrdinal = 0)
        }
        attachStream(c, scope, result.getString("stream_id"))
    }
    /**
     * The large files kept on this phone for [items] (ChatAttachmentStore), once nothing here still needs them: Hermes holds them, or the message they belong to
     * is gone. A file the composer, the saved draft or a message waiting in the box still lists stays (it is still to be sent).
     */
    private suspend fun releaseKeptFiles(scope: Scope, items: List<JSONObject>) {
        val kept = items.filter { it.optBoolean("staged_file") }
        if (kept.isEmpty()) return
        val draft = drafts[scope.key] ?: try { app.cache.read(scope, draftKind(scope))?.let(Draft::decode) } catch (e: Exception) { if (e is CancellationException) throw e; return }
        val inUse = ((if (sourceScope == scope) _attachments.value else emptyList()) + draft?.attachments.orEmpty() +
            draft?.queued.orEmpty().filter { it.inBox() }.flatMap { it.attachments }).map { it.string("id") }.toSet()
        withContext(Dispatchers.IO) { ChatAttachmentStore.deleteAll(kept.filter { it.string("id") !in inUse }) }
    }
    /** Hermes holds the prompt, whether its start was acknowledged or only found in history: the first-prompt markers, the receipt and the draft are done with. */
    private suspend fun promptDelivered(scope: Scope, send: PendingSend, response: JSONObject, accepted: Boolean, request: ScopeSettlement): Boolean {
        if (!request.current()) return false
        val owner = scope.copy(session = "")
        // From here a restart cannot lose the reply: the marker tells the next cold open to ask Hermes (a history-found send may already be over; the ask settles it).
        optionalLocalSave { app.cache.write(scope, RUN_MARKER, send.submissionId) }
        if (!request.current()) return false
        // The creating socket owns only the first accepted prompt; later turns resume by stored identity.
        clientCreatedSessions.remove(scope.key)
        optionalLocalSave { app.cache.delete(scope, "client-created-shell") }
        if (!request.current()) return false
        publishConfirmedSession(owner, scope.session, response, accepted)
        if (client?.scope == owner) refreshSessions()
        if (initialShells[owner.key] == scope.session) { initialShells.remove(owner.key); optionalLocalSave { app.cache.delete(owner, "new-chat-shell") } }
        if (!request.current()) return false
        if (!deletePending(send)) return false
        pending.remove(scope.key, send)
        if (!send.queued) clearSentDraft(scope, send, request)
        sentComposerRevision.remove(scope.key)
        if (readBeaconSessionHandle(owner) == scope.session) viewModelScope.launch {
            delay(3_000)
            client?.takeIf { it.scope == owner }?.let { registerAssistantChat(it.detached(), scope.session) }
        }
        return request.current()
    }
    fun reconcileSend() = viewModelScope.launch {
        val scope = sourceScope ?: return@launch; val c = client?.detached() ?: return@launch
        try { reconcile(scope, c) } catch (e: Throwable) { if (e is CancellationException) throw e; if (sourceScope == scope) fail(e) }
        finally { if (streamClients.values.none { it === c }) c.closeNative() }
    }
    private suspend fun admissionReceipt(c: BeaconClient, scope: Scope, submission: String, admission: String, completedProof: Boolean = false): Pair<JSONObject, String>? {
        require(c.scope == scope.copy(session = "")) { "The admission belongs to another conversation" }
        val result = c.get("/api/mobile/receipts/$submission", mapOf("session_id" to scope.session, "admission_id" to admission) +
            if (completedProof) mapOf("completed_proof" to "1") else emptyMap())
        val runtime = result.string("stream_id")
        return if (runtime.isBlank()) null else result to runtime
    }
    private suspend fun reconcile(scope: Scope, c: BeaconClient, quiet: Boolean = false) {
        val active = client ?: return
        val request = ScopeSettlement(scope, active, originGeneration, sessionGeneration)
        val send = pending[scope.key] ?: return
        val result = c.get("/api/session", mapOf("session_id" to scope.session))
        if (!request.current() || client?.scope != c.scope || pending[scope.key] !== send) return
        renderSession(result, scope)
        if (!request.current() || pending[scope.key] !== send) return
        if (sendArrived(send.rowId, _chat.value.messages)) {
            // History holds the message: settle it as an accepted send does instead of asking again.
            if (send.queued) updateQueued(scope, send.id) { it.copy(state = "sent", rowId = send.rowId, admissionId = send.admissionId) }
            if (!request.current() || !promptDelivered(scope, send, result, accepted = false, request = request)) return
            if (sourceScope == scope) _chat.update { it.copy(uncertain = "", status = "") }
            notice("Your message arrived.")
            return
        }
        val admission = try { admissionReceipt(c, scope, send.submissionId, send.admissionId) }
            catch (e: Throwable) { if (e is CancellationException) throw e; null }
        if (!request.current() || pending[scope.key] !== send) return
        if (admission != null) {
            val receipt = admission.first
            when (receipt.string("status")) {
                "queued" -> { acceptedWithoutRow(scope, receipt, send, request); return }
                "started" -> {
                    accepted(scope, c, receipt.copy().put("session_id", scope.session).put("stream_id", admission.second), send, request)
                    return
                }
                "terminal" -> {
                    acceptedWithoutRow(scope, receipt, send, request)
                    if (request.current()) {
                        updateQueued(scope, send.id) { it.copy(state = "settled", outcome = receipt.string("outcome")) }
                        client?.let { drainQueued(scope, it) }
                    }
                    return
                }
            }
        }
        if (quiet) return
        _chat.update { it.copy(uncertain = send.id, status = "") }
        notice("Tap your message to check it.")
    }
    private suspend fun clearSentDraft(scope: Scope, sent: PendingSend, request: ScopeSettlement) {
        val revision = sentComposerRevision[scope.key]?.takeIf { it.first == sent.id }?.second ?: return
        if (!request.current() || composerRevision != revision) return
        draftWriteLock.withLock {
            if (!request.current() || composerRevision != revision) return@withLock null
            val current = draft(); val next = current.afterAccepted(sent.draft)
            if (next == current) return@withLock null
            val encoded = next.encode(); app.cache.write(scope, draftKind(scope), encoded, next.queued.isNotEmpty()); drafts[scope.key] = next; savedDraft(scope, encoded)
            next
        } ?: return
        if (request.current() && composerRevision == revision && _composer.value.text == sent.draft.text &&
            _attachments.value.map { it.toString() } == sent.draft.attachments.map { it.toString() }) {
            _composer.value = TextFieldValue(); _attachments.value = emptyList(); composerRevision++
        }
        if (request.current()) checkpointNow()
    }
    fun reviewPendingSend(resend: Boolean = false) = viewModelScope.launch {
        val scope = sourceScope ?: return@launch
        val generation = sessionGeneration; val origin = originGeneration; val active = client
        val owner = client?.scope ?: cachedOwnerScope
        val unknown = pending[scope.key] ?: return@launch
        // Follow-up 5: Hermes is being asked about it under its own identity; its answer settles it first (a resend now could make it twice).
        if (askJobs[unknown.id]?.isActive == true) return@launch
        if (scope.copy(session = "") != owner) { notice("Reopen this chat to check the message."); return@launch }
        if (resend && client == null) { notice("Reconnect before resending. Your draft is kept."); return@launch }
        try {
            checkpointJob?.cancelAndJoin()
            val retained = draftWriteLock.withLock {
                if (sourceScope != scope || sessionGeneration != generation || originGeneration != origin || client !== active) return@withLock false
                val current = draft()
                val item = current.queued.firstOrNull { it.id == unknown.id } ?: QueuedMessage(unknown.draft.text, unknown.draft.attachments,
                    unknown.model, unknown.provider, id = unknown.id, createdAt = unknown.createdAt, admissionId = unknown.admissionId, submissionId = unknown.submissionId)
                val retained = item.copy(state = if (resend) "queued" else "unknown", kind = "queue", admissionId = if (resend) "" else item.admissionId, admissionStatus = if (resend) "" else item.admissionStatus,
                    submissionId = if (resend) java.util.UUID.randomUUID().toString() else item.submissionId)
                val queue = if (current.queued.any { it.id == item.id }) current.queued.map { if (it.id == item.id) retained else it } else current.queued + retained
                val next = current.copy(queued = queue)
                val encoded = next.encode(); app.cache.write(scope, draftKind(scope), encoded, true); drafts[scope.key] = next; savedDraft(scope, encoded)
                if (sourceScope == scope && sessionGeneration == generation && originGeneration == origin && client === active) _chat.update { state ->
                    state.copy(queued = if (state.queued.any { it.id == item.id }) state.queued.map { if (it.id == item.id) retained else it } else state.queued + retained)
                }
                true
            }
            if (!retained) return@launch
            if (sourceScope != scope || sessionGeneration != generation || originGeneration != origin || client !== active) return@launch
            if (!deletePending(unknown)) { notice("This message changed. Refresh the chat to check it."); return@launch }
        } catch (e: Exception) { if (e is CancellationException) throw e; if (sourceScope == scope) fail(e); return@launch }
        if (!pending.remove(scope.key, unknown) || sourceScope != scope || sessionGeneration != generation || originGeneration != origin || client !== active || (client?.scope ?: cachedOwnerScope) != owner) return@launch
        _chat.update { it.copy(uncertain = "", status = "Draft kept") }
        if (resend) client?.let { drainQueued(scope, it) } else notice("Checked. Your draft is kept.")
    }
    /**
     * CHATQA-121 follow-up 5: the open chat's send whose answer was lost asks Hermes once more under the same identity, as Hermes' own CLI does
     * (hermes_cli/gateway_queue.py submit_queued). Hermes answers with the admission it holds under that submission id (the first send arrived) or admits
     * it once (it never did); the bubble then follows that admission like any accepted send. Hermes keeps an admission while its session lives (it is
     * removed only when the session is deleted or swept whole: hermes_state_mutation_retirement.py retire_sessions), so the answer is Hermes' own. Once per
     * time Hermes answers again or the chat is opened; never for a removed send, a Hermes without session authority or a payload that cannot be sent
     * again unchanged (askableAgain). Lost again: "Not confirmed yet" until the next time.
     */
    private fun askAgainUnconfirmed(): Job? {
        val scope = sourceScope ?: return null
        val active = client ?: return null
        val send = pending[scope.key]?.takeIf(::askableAgain) ?: return null
        // Only a send known to be unconfirmed (never one whose first request is still on its way), once per time, never twice at once.
        if (send.submissionId in sendsOnTheirWay || _chat.value.uncertain != send.id && !askedAgain.containsKey(send.id)) return null
        if (askJobs[send.id]?.isActive == true || askedAgain[send.id] == reachEpoch) return null
        askedAgain[send.id] = reachEpoch
        val request = ScopeSettlement(scope, active, originGeneration, sessionGeneration)
        return viewModelScope.launch(start = CoroutineStart.LAZY) {
            val c = active.detached()
            var handedOver = false
            // Until Hermes answers, the send stays "Not confirmed yet" (today's bubble and choices, also on a Hermes that is not asked again).
            var unconfirmed = true
            try {
                if (!c.nativeSessionAuthority()) return@launch
                if (!request.current() || pending[scope.key] !== send) { unconfirmed = false; return@launch }
                // While Hermes is asked, the bubble shows it going, as any send on its way (Remove waits for the answer, fix 4's rule for a send still going out).
                _chat.update { if (it.uncertain == send.id) it.copy(status = "Sending") else it }
                val o = c.post("/api/chat/start", askAgainBody(scope, send))
                if (!request.current() || pending[scope.key] !== send) { unconfirmed = false; return@launch }
                unconfirmed = o.string("status") == "unknown"
                when (o.string("status")) {
                    "queued" -> acceptedWithoutRow(scope, o, send, request)
                    "terminal" -> {
                        acceptedWithoutRow(scope, o, send, request)
                        if (request.current()) {
                            // The turn finished while the answer was lost: its rows (the message and the reply) are in Hermes' history now.
                            val history = c.get("/api/session", mapOf("session_id" to scope.session))
                            renderSession(history, scope)
                            // Hermes' own row for this submission shows the message, so the phone's copy goes (never a second bubble); a turn that ended
                            // before it started has no row, and its copy stays settled with Hermes' outcome.
                            if (submissionRowId((history.optJSONObject("session") ?: history).rows("messages"), send.submissionId) != null) { updateQueued(scope, send.id) { null }; forgetPendingAttachments(scope, send.submissionId) }
                            else updateQueued(scope, send.id) { it.copy(state = "settled", outcome = o.string("outcome")) }
                            client?.let { drainQueued(scope, it) }
                        }
                    }
                    "unknown" -> {
                        if (send.queued) updateQueued(scope, send.id) { it.copy(state = "unknown", admissionId = o.string("admission_id")) }
                        val unknown = send.copy(admissionId = o.string("admission_id"))
                        savePending(unknown); pending[scope.key] = unknown
                    }
                    else -> { handedOver = true; accepted(scope, c, o, send, request) }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) { unconfirmed = false; throw e }
                // Hermes holds a send under that id with another payload: its own row is the message, and nothing is sent again.
                if (admissionConflict(e) && request.current() && pending[scope.key] === send) { unconfirmed = false; forgetAnsweredCopy(scope, send, c, request) }
                // Otherwise the answer is lost again (or Hermes is out of reach): "Not confirmed yet" until Hermes answers again.
            } finally {
                if (unconfirmed && sourceScope == scope && request.current() && pending[scope.key]?.id == send.id) _chat.update { it.copy(uncertain = send.id, status = "") }
                else if (sourceScope == scope && _chat.value.uncertain == send.id && _chat.value.status == "Sending") _chat.update { it.copy(status = "") }
                if (!handedOver) c.closeNative()
                askJobs.remove(send.id)
            }
        }.also { askJobs[send.id] = it; it.start() }
    }
    /** Follow-up 5: Hermes answered admission_conflict: it holds a send under this submission id, so its own row is the message. The phone's copy goes, nothing is sent again, and the chat reads Hermes' history. */
    private suspend fun forgetAnsweredCopy(scope: Scope, send: PendingSend, c: BeaconClient, request: ScopeSettlement) {
        if (!pending.remove(scope.key, send)) return
        sentComposerRevision[scope.key]?.takeIf { it.first == send.id }?.let { sentComposerRevision.remove(scope.key) }
        // Chat join (Root, rules15): the saved record and the box's copy go first, the bubble last, so a kill or a slow device between them never
        // leaves a record behind that brings the copy back.
        if (send.queued) updateQueued(scope, send.id) { null }
        if (!deletePending(send)) app.cache.read(scope, "pending")
            ?.takeIf { raw -> runCatching { PendingSend.decode(scope, raw).id == send.id }.getOrDefault(false) }
            ?.let { raw -> app.cache.delete(scope, "pending", raw) }
        forgetPendingAttachments(scope, send.submissionId)
        if (sourceScope == scope && _chat.value.uncertain == send.id) _chat.update { it.copy(uncertain = "", status = "") }
        if (request.current()) renderSession(c.get("/api/session", mapOf("session_id" to scope.session)), scope)
    }
    /** Follow-up 5: after an answer was lost, one look whether Hermes answers now (a dropped socket, not a lost network): then the send is asked again at once; otherwise the reach check asks when Hermes answers again. */
    private fun lookForHermesAfterLostAnswer(origin: String) {
        val active = client ?: return
        viewModelScope.launch {
            val c = active.detached()
            val answered = try { c.get("/api/status"); true }
            catch (e: Throwable) { if (e is CancellationException) throw e; HermesReach.failed(origin, e); false }
            finally { runCatching { c.closeNative() } }
            if (answered && client === active) { hermesAnsweredAgain(); drainOfflineOutbox() }
        }
    }
    /**
     * CHATQA-121 follow-up 4: "Remove this copy" on a send whose outcome is not known. The phone's copy goes at once and is never sent again:
     * the bubble, the copy in the box (and any hold on it), the saved send record and the run marker it wrote, so a restart brings none of them
     * back. If Hermes did take the message, its own row shows it, as any message of the chat (the phone keeps no copy to show twice).
     */
    fun removePendingSend() = viewModelScope.launch {
        val scope = sourceScope ?: return@launch
        val unknown = pending[scope.key] ?: return@launch
        // Still going out: its outcome is not known yet, so there is no copy to remove.
        if (_chat.value.uncertain == unknown.id && _chat.value.status == "Sending") return@launch
        // From here nothing follows, checks or sends this copy.
        if (!pending.remove(scope.key, unknown)) return@launch
        sentComposerRevision[scope.key]?.takeIf { it.first == unknown.id }?.let { sentComposerRevision.remove(scope.key) }
        // Chat join: no pause map here; the copy's hold (QueuedMessage.held) goes with the copy below.
        // Chat join (Root, rules19): what is saved goes first and the bubble last, so an app that dies between them never wakes up with a removed
        // message still saved, which fix 5's ask-again could send. A local delete takes milliseconds, so the bubble still goes at once.
        try {
            updateQueued(scope, unknown.id) { null }
            if (!deletePending(unknown)) app.cache.read(scope, "pending")
                ?.takeIf { raw -> runCatching { PendingSend.decode(scope, raw).id == unknown.id }.getOrDefault(false) }
                ?.let { raw -> app.cache.delete(scope, "pending", raw) }
            optionalLocalSave { app.cache.delete(scope, RUN_MARKER, unknown.submissionId) }
            forgetPendingAttachments(scope, unknown.submissionId)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // The copy is still saved: it stays this phone's unconfirmed send, on screen, for another Remove.
            pending.putIfAbsent(scope.key, unknown)
            if (sourceScope == scope) fail(e)
            return@launch
        }
        // REVIEW-JOINB-121 P3-1: once nothing saved names the copy, its kept files go too (files still in the composer or another message stay).
        releaseKeptFiles(scope, unknown.draft.attachments)
        if (sourceScope == scope && _chat.value.uncertain == unknown.id) _chat.update { it.copy(uncertain = "", status = "") }
    }

    /**
     * Delete withdrew [withdrawn] (admission id, words) from Hermes' queue and then could not finish: each goes back to this chat's queue on the
     * phone (its own copy held again, or a new waiting message), so the owner's message is never lost silently. It waits there like any other.
     */
    private suspend fun restoreWithdrawn(scope: Scope, withdrawn: List<Pair<String, String>>) {
        if (withdrawn.isEmpty()) return
        if (sourceScope == scope) checkpointJob?.cancelAndJoin()
        draftWriteLock.withLock {
            val open = sourceScope == scope
            val current = if (open) draft() else drafts[scope.key] ?: app.cache.read(scope, draftKind(scope))?.let(Draft::decode) ?: Draft()
            var queued = current.queued
            val back = mutableListOf<String>()
            for ((admission, text) in withdrawn) {
                val copy = queued.firstOrNull { admission.isNotBlank() && it.admissionId == admission }
                val held = (copy?.heldAgain() ?: QueuedMessage(text).takeIf { text.isNotBlank() } ?: continue)
                    .copy(held = dev.hermes.android.data.DELETE_KEPT_WAITING_ERROR)
                queued = if (copy != null) queued.map { if (it === copy) held else it } else queued + held
                back += held.id
            }
            // Held (as a failed turn's follow-up is, TURN-121's saved hold): no drain sends a handed-back message by itself; its row's Send does.
            val next = current.copy(queued = queued)
            val encoded = next.encode()
            app.cache.write(scope, draftKind(scope), encoded, true)
            drafts[scope.key] = next; savedDraft(scope, encoded)
            if (open) _chat.update { it.copy(queued = queued) }
        }
    }
    private suspend fun updateQueued(scope: Scope, id: String, allowed: () -> Boolean = { true }, change: (QueuedMessage) -> QueuedMessage?): Boolean {
        val generation = sessionGeneration
        val origin = originGeneration
        val active = client
        if (sourceScope == scope) checkpointJob?.cancelAndJoin()
        return draftWriteLock.withLock {
            if (!allowed() || sourceScope == scope && (sessionGeneration != generation || originGeneration != origin || client !== active)) return@withLock false
            val current = if (sourceScope == scope) draft() else drafts[scope.key] ?: app.cache.read(scope, draftKind(scope))?.let(Draft::decode) ?: return@withLock false
            if (current.queued.none { it.id == id }) return@withLock false
            val next = current.copy(queued = current.queued.mapNotNull { if (it.id == id) change(it) else it })
            val encoded = next.encode()
            app.cache.write(scope, draftKind(scope), encoded, next.text.isNotBlank() || next.attachments.isNotEmpty() || next.queued.isNotEmpty())
            drafts[scope.key] = next; savedDraft(scope, encoded)
            if (sourceScope == scope && sessionGeneration == generation && originGeneration == origin && client === active) {
                val replacement = next.queued.firstOrNull { it.id == id }
                _chat.update { state -> state.copy(queued = if (replacement == null) state.queued.filterNot { it.id == id } else state.queued.map { if (it.id == id) replacement else it }) }
            }
            true
        }
    }
    fun queue(payload: String? = null): Job = submitBusy(payload, steer = false)
    fun steer(): Job = submitBusy(null, steer = true)
    private fun submitBusy(payload: String?, steer: Boolean): Job = viewModelScope.launch {
        if (!admitConversation()) return@launch
        val active = client ?: run { queueOffline(payload); return@launch }
        // P3-5: as in send(), a tap with no open chat says why.
        val scope = sourceScope ?: run { notice("Open a chat first."); return@launch }
        val state = _chat.value
        if (_attachmentPreparation.value.isNotEmpty() || state.uncertain.isNotBlank() || state.approval != null || state.clarification != null ||
            state.raw.optBoolean("read_only") || state.raw.optBoolean("is_read_only") || app.calls.state.value.active) {
            notice("Wait for this chat to load, then try again."); return@launch
        }
        val before = draft()
        val text = payload ?: before.text
        if (text.isBlank() && before.attachments.isEmpty()) return@launch
        val useSteer = steer && state.streamId.isNotBlank() && before.attachments.isEmpty() && text.isNotBlank()
        val generation = sessionGeneration
        val origin = originGeneration
        val revision = composerRevision
        val attachments = before.attachments.map { it.copy() }
        val item = QueuedMessage(text, attachments, state.raw.string("model"), state.raw.string("model_provider"),
            state = if (useSteer) "sending" else "queued", kind = if (useSteer) "steer" else "queue")
        val turnClient = streamClients[scope.key]
        _chat.update { it.copy(queued = it.queued + item) }
        try {
            checkpointJob?.cancelAndJoin()
            draftWriteLock.withLock {
                val saved = if (sourceScope == scope && sessionGeneration == generation && originGeneration == origin && client === active) draft()
                    else drafts[scope.key] ?: app.cache.read(scope, draftKind(scope))?.let(Draft::decode) ?: before
                val current = if (saved.queued.any { it.id == item.id }) saved else saved.copy(queued = saved.queued + item)
                val clearText = composerRevision == revision && current.text == before.text
                val clearFiles = current.attachments.map { it.toString() } == before.attachments.map { it.toString() }
                val next = current.copy(text = if (clearText) "" else current.text, selectionStart = if (clearText) 0 else current.selectionStart,
                    selectionEnd = if (clearText) 0 else current.selectionEnd, attachments = if (clearFiles) emptyList() else current.attachments)
                val encoded = next.encode()
                app.cache.write(scope, draftKind(scope), encoded, true)
                drafts[scope.key] = next; savedDraft(scope, encoded)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (sourceScope == scope && sessionGeneration == generation) {
                _chat.update { it.copy(queued = it.queued.filterNot { q -> q.id == item.id }) }
                notice(if (draftStoreFull(e)) DRAFT_STORE_FULL_ERROR else "Couldn't save the message. Keep the draft open and try again.")
            }
            return@launch
        }
        if (sourceScope != scope || sessionGeneration != generation || originGeneration != origin || client !== active) return@launch
        if (composerRevision == revision && _composer.value.text == before.text) { _composer.value = TextFieldValue(); composerRevision++ }
        if (_attachments.value.map { it.toString() } == before.attachments.map { it.toString() }) _attachments.value = emptyList()
        try { checkpointNow() } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (useSteer && sourceScope == scope) _chat.update { state -> state.copy(queued = state.queued.map { if (it.id == item.id) it.copy(state = "queued", kind = "queue") else it }) }
            notice("Couldn't save the latest edit. Keep this chat open and try again."); return@launch
        }
        if (!useSteer) { if (state.streamId.isBlank()) drainQueued(scope, active, generation, origin); return@launch }
        sendSteer(scope, active, item, state.streamId, turnClient, generation, origin)
    }
    /** Sends [item] (already held as a steer, state "sending") into the reply running as [streamId]; a steer that did not go out goes back to the queue box. */
    private suspend fun sendSteer(scope: Scope, active: BeaconClient, item: QueuedMessage, streamId: String, turnClient: BeaconClient?, generation: Int, origin: Int) {
        if (turnClient == null || turnClient.scope != scope.copy(session = "")) {
            updateQueued(scope, item.id) { it.copy(state = "queued", kind = "queue") }
            notice("Couldn't send it now. It's in the queue.")
            if (_chat.value.streamId.isBlank()) drainQueued(scope, active, generation, origin)
            return
        }
        try {
            val result = turnClient.post("/api/chat/steer", json("session_id" to scope.session, "runtime_session_id" to streamId, "text" to item.text))
            if (sourceScope != scope || sessionGeneration != generation || originGeneration != origin || client !== active) return
            val next = when {
                result.string("status") == "queued" -> item.copy(state = "accepted")
                result.string("status") == "rejected" && result.optBoolean("safe_to_queue") -> item.copy(state = "queued", kind = "queue")
                else -> item.copy(state = "unknown")
            }
            updateQueued(scope, item.id) { next }
            if (next.state == "queued" && _chat.value.streamId.isBlank()) drainQueued(scope, active, generation, origin)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (sourceScope != scope || sessionGeneration != generation || originGeneration != origin || client !== active) return
            val notSent = e is NativeTurnNotSent || requestNeverSent(e)
            try { updateQueued(scope, item.id) { it.copy(state = if (notSent) "queued" else "unknown", kind = if (notSent) "queue" else it.kind) } }
            catch (save: Throwable) {
                if (save is CancellationException) throw save
                if (sourceScope == scope && sessionGeneration == generation && originGeneration == origin && client === active) {
                    _chat.update { current -> current.copy(queued = current.queued.map { if (it.id == item.id) it.copy(state = if (notSent) "queued" else "unknown", kind = if (notSent) "queue" else it.kind) else it }) }
                    notice(if (draftStoreFull(save)) DRAFT_STORE_FULL_ERROR else "Couldn't save this change. Keep the chat open and check the message.")
                }
                return
            }
            if (notSent) {
                notice("Couldn't send it now. It's in the queue.")
                if (_chat.value.streamId.isBlank()) drainQueued(scope, active, generation, origin)
            } else fail(e)
        }
    }
    /** The Steer button on a queued message: send it now, into the reply that is running. It leaves the box and lands in the chat with the Steer marker. */
    fun steerQueued(id: String) = viewModelScope.launch {
        if (!admitConversation()) return@launch
        val active = client ?: run { notice("Reconnect before sending. Your draft is kept."); return@launch }
        val scope = sourceScope ?: run { notice("Open a chat first."); return@launch }
        val state = _chat.value
        if (state.uncertain.isNotBlank() || state.approval != null || state.clarification != null || state.raw.optBoolean("read_only") || state.raw.optBoolean("is_read_only") || app.calls.state.value.active) {
            notice("Wait for this chat to load, then try again."); return@launch
        }
        val generation = sessionGeneration
        val origin = originGeneration
        val runtime = state.streamId
        val held = state.queued.firstOrNull { it.id == id } ?: return@launch
        if (!canSteerQueued(held, runtime.isNotBlank())) return@launch
        val turnClient = streamClients[scope.key]
        // updateQueued says "the row exists", not "this call moved it": a second tap that waited for the draft lock finds the row already sending.
        // Only the call whose change really moved the row may steer (the same flag send() uses), so one instruction is never posted twice.
        var moved = false
        try { updateQueued(scope, id) { claimSteer(it, runtime.isNotBlank())?.also { moved = true } ?: it } }
            catch (e: Throwable) { if (e is CancellationException) throw e; if (sourceScope == scope) fail(e); return@launch }
        val item = _chat.value.queued.firstOrNull { it.id == id }?.takeIf { moved && it.state == "sending" && it.kind == "steer" } ?: return@launch
        sendSteer(scope, active, item, runtime, turnClient, generation, origin)
    }
    fun removeQueued(id: String) = viewModelScope.launch {
        val scope = sourceScope ?: return@launch
        val item = _chat.value.queued.firstOrNull { it.id == id && it.inBox() } ?: return@launch
        try {
            if (updateQueued(scope, item.id) { if (it.inBox()) null else it } && sourceScope == scope && _chat.value.queued.none { it.id == id })
                releaseKeptFiles(scope, item.attachments)  // the message is gone, and so are the files kept for it
            if (_chat.value.queued.none { it.id == id }) forgetPendingAttachments(scope, item.submissionId)
            afterBoxShrank(scope)
        } catch (e: Throwable) { if (e is CancellationException) throw e; if (sourceScope == scope) fail(e) }
    }
    fun removeUnresolvedLocalMessage(id: String) = viewModelScope.launch {
        val scope = sourceScope ?: return@launch
        val item = _chat.value.queued.firstOrNull { it.id == id && it.state in setOf("accepted", "unknown", "settled") && pending[scope.key]?.id != it.id } ?: return@launch
        var removed = false
        try { updateQueued(scope, item.id) { if (it.state in setOf("accepted", "unknown", "settled") && pending[scope.key]?.id != it.id) { removed = true; null } else it }
            // The copy and its files go whatever chat is open now (REVIEW-JOINB-121 P3-1); only the drain belongs to the open chat.
            if (removed) { releaseKeptFiles(scope, item.attachments); forgetPendingAttachments(scope, item.submissionId) }
            if (removed && sourceScope == scope) client?.let { drainQueued(scope, it) }
        }
        catch (e: Throwable) { if (e is CancellationException) throw e; if (sourceScope == scope) fail(e) }
    }
    fun removeConfirmedLocalCopy(id: String) = viewModelScope.launch {
        val scope = sourceScope ?: return@launch
        val item = _chat.value.queued.firstOrNull { it.id == id && it.state == "sent" && it.rowId.isNotBlank() } ?: return@launch
        if (_chat.value.messages.none { it.id == item.rowId && it.role == "user" }) return@launch
        try { updateQueued(scope, id) { if (it.state == "sent" && _chat.value.messages.any { row -> row.id == it.rowId && row.role == "user" }) null else it } }
        catch (e: Throwable) { if (e is CancellationException) throw e; if (sourceScope == scope) fail(e) }
    }
    private fun phoneQueueHead() = _chat.value.queued.firstOrNull { it.kind == "queue" && it.state !in setOf("sent", "settled") }
    /** A steer bubble whose saved row is now known stops being a second copy: it takes the row's identity and the history row is the one shown. */
    private suspend fun pairSteerCopies(scope: Scope, proofUserRows: List<Long>, rows: List<JSONObject>, current: () -> Boolean) {
        for ((id, row) in steerRowPairs(_chat.value.queued, proofUserRows, rows)) {
            if (!current()) return
            updateQueued(scope, id, current) { q -> if (q.kind == "steer" && q.rowId.isBlank()) q.copy(rowId = row, state = "sent") else q }
        }
    }
    /** R3-F5: the steers still open when their turn ended are settled by the turn's receipt ([steerAfterTurn]); a leftover then goes with the queue's drain. */
    private suspend fun settleOpenSteers(c: BeaconClient, scope: Scope, identity: TurnIdentity, rows: List<JSONObject>, turnUsers: List<Long>, current: () -> Boolean) {
        if (_chat.value.queued.none { it.openSteer() }) return
        val savedSteers = turnSteerRows(rows, turnUsers)
        // A Hermes without session authority (tui_gateway) sends a leftover steer as the next turn itself.
        if (!(try { c.nativeSessionAuthority() } catch (e: Throwable) { if (e is CancellationException) throw e; true })) return
        val receipt = if (identity.submission.isBlank() || identity.admission.isBlank()) null else try {
            withTimeoutOrNull(RECEIPT_ASK_MS) { admissionReceipt(c, scope, identity.submission, identity.admission, completedProof = true)?.first }
        } catch (e: Throwable) { if (e is CancellationException) throw e; null }
        var notSent = false
        for (copy in _chat.value.queued.filter { it.openSteer() }) {
            if (!current()) return
            updateQueued(scope, copy.id, current) { q -> if (q.openSteer()) steerAfterTurn(q, receipt, savedSteers).also { if (it.notSent()) notSent = true } else q }
        }
        if (notSent && current()) notice("Couldn't change the reply. Your message is kept.")
    }
    private suspend fun pairAcceptedCopies(scope: Scope, rows: List<JSONObject>, current: () -> Boolean) {
        // Hermes' own stamp first: that row is the copy's, the chat shows it in place of the copy, and the copy keeps its state for the turn's receipt.
        for ((id, row) in submittedRowPairs(_chat.value.queued, rows)) {
            if (!current()) return
            val item = _chat.value.queued.firstOrNull { it.id == id } ?: continue
            var named = false
            updateQueued(scope, id, current) { q -> namedCopy(q, row).also { named = it !== q } }
            if (!named || !current()) continue
            keepLinkedAttachments(scope, item, row, current)
            // Hermes' saved row holds the sent files now (its own copies under attachments/): the copies kept on this phone go, as when a send is accepted with its row.
            releaseKeptFiles(scope, item.attachments)
        }
        for ((id, row) in acceptedRowPairs(_chat.value.queued, rows)) {
            if (!current()) return
            val item = _chat.value.queued.firstOrNull { it.id == id } ?: continue
            // Only the call whose change really paired the copy keeps its pictures: updateQueued also says true for a copy a concurrent pass had already paired.
            var paired = false
            updateQueued(scope, id, current) { q -> pairedCopy(q, row).also { paired = it !== q } }
            if (paired && current()) { keepLinkedAttachments(scope, item, row, current); forgetPendingAttachments(scope, item.submissionId) }
        }
    }
    private suspend fun bindPersistedInput(scope: Scope, given: QueuedMessage, receipt: JSONObject, rows: List<JSONObject>, current: () -> Boolean): String? {
        if (!current()) return null
        val item = currentCopy(_chat.value.queued, given)
        val linked = boundQueuedInput(item, receipt, rows, _chat.value.queued)?.rowId ?: return null
        val wrongRow = wrongPairedRow(item, linked)
        if (!updateQueued(scope, item.id, current) { q -> if (q.submissionId == item.submissionId && q.admissionId == item.admissionId)
            q.copy(rowId = linked, state = "settled", outcome = receipt.string("outcome")) else q }) return null
        if (!current()) return null
        if (wrongRow != null) {
            // The words had led to another message's row: its picture goes, the real row gets it below.
            optionalLocalSave { app.cache.delete(scope, "attachments:$wrongRow") }
            val ids = item.attachments.map { it.string("id") }.filter(String::isNotBlank).toSet()
            _chat.update { state -> releasedAttachmentChat(state, current, wrongRow, ids) }
        }
        // Follow-up 6: the send's own record (by its submission id) goes to the row Hermes linked it to. A 1.2.0 record (by chat stream) moves only when it is exactly this send's.
        val stream = receipt.string("stream_id")
        val own = app.cache.read(scope, pendingAttachmentsKind(item.submissionId))
        val legacy = stream.takeIf(String::isNotBlank)?.let { app.cache.read(scope, legacyPendingAttachmentsKind(it)) }
        pendingRecordForRow(own, legacy, item, rows.firstOrNull { it.string("row_id", it.string("_row_id")) == linked })?.let { app.cache.write(scope, "attachments:$linked", it) }
        forgetPendingAttachments(scope, item.submissionId, stream)
        keepLinkedAttachments(scope, item, linked, current)
        val final = receipt.optJSONObject("_confirmed_persisted_turn")?.string("final_assistant_row_id").orEmpty()
        if (current() && final.isNotBlank() && rows.any { it.string("row_id", it.string("_row_id")) == final && it.string("role") == "assistant" }) {
            _chat.update { state -> state.copy(raw = state.raw.copy().put(UNCONFIRMED_REPLIES, JSONArray(state.raw.rows(UNCONFIRMED_REPLIES).filterNot {
                it.string("submission_id") == item.submissionId && it.string("admission_id") == item.admissionId
            }))) }
        }
        return linked
    }
    /** Follow-up 6: the files kept for a send that has no row (removed, not sent, lost, or its row is linked) are dropped, with a 1.2.0 record of the same chat stream. */
    private suspend fun forgetPendingAttachments(scope: Scope, submissionId: String, stream: String = "") {
        if (submissionId.isNotBlank()) optionalLocalSave { app.cache.delete(scope, pendingAttachmentsKind(submissionId)) }
        if (stream.isNotBlank()) optionalLocalSave { app.cache.delete(scope, legacyPendingAttachmentsKind(stream)) }
    }
    /** The sent photos and files of [item] stay with the saved row [linked]: their small pictures are kept for a reopen and shown in the row at once. */
    private suspend fun keepLinkedAttachments(scope: Scope, item: QueuedMessage, linked: String, current: () -> Boolean) {
        val display = withContext(Dispatchers.Default) { withKeptThumbnails(JSONArray(item.attachments.map { it.copy() }), item.attachments, ::attachmentThumbnail) }
        if (display.length() > 0) {
            // The queued item itself is the exact send for this admission; keep its display attachments
            // under the settled row even when the acceptance receipt did not carry a pending attachment
            // record (no media payload: just name/mime/identity for rendering after a reopen).
            optionalLocalSave { app.cache.write(scope, "attachments:$linked", attachmentRecord(display, item.text, item.submissionId)) }
            val shown = (0 until display.length()).mapNotNull { display.optJSONObject(it) }
            // The attachment cache saves on IO, so selection may change across that suspension; the mutation
            // itself re-checks the caller's existing current predicate.
            _chat.update { state -> boundAttachmentChat(state, current, linked, display, shown) }
        }
    }
    /**
     * Reads the chat again, ties the copy [source] to the row its completed [receipt] proves, and removes it once both ends of the turn are shown.
     * False when the chat moved on meanwhile (the caller stops).
     */
    private suspend fun bindAndRetire(scope: Scope, c: BeaconClient, source: QueuedMessage, receipt: JSONObject, matches: (QueuedMessage) -> Boolean, eligible: () -> Boolean): Boolean {
        val revision = chatHistoryRevision
        val response = c.get("/api/session", mapOf("session_id" to scope.session))
        if (!eligible() || pending[scope.key] != null || revision != chatHistoryRevision) return false
        val fresh = response.optJSONObject("session") ?: response
        if (fresh.string("session_id") != scope.session || fresh.string("profile") != scope.profile || fresh.optJSONArray("messages") == null) return false
        val refreshed = fresh.rows("messages")
        bindPersistedInput(scope, source, receipt, refreshed) { eligible() && pending[scope.key] == null && revision == chatHistoryRevision }
        if (!eligible() || pending[scope.key] != null || revision != chatHistoryRevision) return false
        val bound = _chat.value.queued.firstOrNull(matches) ?: source
        val row = completedQueuedInputRow(bound, receipt, _chat.value.raw.rows("messages"), refreshed, _chat.value.queued)
        if (row != null) updateQueued(scope, source.id, { eligible() && pending[scope.key] == null && revision == chatHistoryRevision }) { q ->
            if (matches(q) && completedQueuedInputRow(q, receipt, _chat.value.raw.rows("messages"), refreshed, _chat.value.queued) == row) null else q
        }
        return true
    }
    private suspend fun refreshQueuedReceipt(scope: Scope, active: BeaconClient, item: QueuedMessage, request: ScopeSettlement, automatic: Boolean) {
        fun matches(q: QueuedMessage) = q.id == item.id && q.submissionId == item.submissionId && q.kind == item.kind && q.admissionId == item.admissionId
        val byWords = item.state == "sent" && item.outcome == PAIRED_BY_WORDS
        fun eligible() = foreground && request.current() && pending[scope.key]?.id != item.id &&
            (!automatic || pending[scope.key] == null && (if (item.state == "settled" || byWords) !followsHere(scope) else phoneQueueHead()?.id == item.id)) &&
            _chat.value.queued.any { matches(it) && refreshableCopy(item, it, automatic) }
        val c = active.detached()
        try {
            val (receipt, runtime) = admissionReceipt(c, scope, item.submissionId, item.admissionId, completedProof = true) ?: return
            currentCoroutineContext().ensureActive()
            if (!eligible()) return
            // A reply Hermes lost in a restart holds this message (or is it): close that turn first; the next check reads what Hermes made of it.
            if (receipt.optBoolean("lost_turn")) { recoverLostTurn(scope); return }
            if (_chat.value.lostWaiting && sourceScope == scope) _chat.update { it.copy(lostWaiting = false) }
            val status = receipt.string("status")
            val state = when (status) { "queued", "started" -> "accepted"; "terminal" -> "settled"; else -> "unknown" }
            val outcome = if (state == "settled") receipt.string("outcome") else ""
            val shown = _chat.value.queued.firstOrNull(::matches) ?: return
            if (byWords && shown.state == "sent" && shown.outcome == PAIRED_BY_WORDS) {
                // A copy paired by words changes only on a terminal receipt; the marker stays until the receipt's own row and outcome replace it.
                if (state != "settled") return
                if (outcome != "completed") updateQueued(scope, item.id, { eligible() }) { q -> if (matches(q)) pairedCopyAfterReceipt(q, state, outcome) else q }
                else if (receipt.optJSONObject("_confirmed_persisted_input") != null || receipt.optJSONObject("_confirmed_persisted_turn") != null) {
                    if (!bindAndRetire(scope, c, shown, receipt, ::matches) { eligible() }) return
                } else return
                drainQueued(scope, active, request.sessionGeneration, request.originGeneration)
                return
            }
            if (shown.state == "settled" && state != "settled") return
            // The admission going from queued to started changes only its status: that is what lets the reply that streams for it turn its ticks blue.
            if (shown.state != state || shown.outcome != outcome || state == "accepted" && shown.admissionStatus != admissionStatusOf(status).ifBlank { shown.admissionStatus }) {
                if (!updateQueued(scope, item.id, { eligible() }) { q ->
                    if (matches(q) && (q.state in setOf("accepted", "unknown") || q.state == "settled" && state == "settled")) q.afterReceipt(state, outcome, status) else q
                }) return
            }
            if (!foreground || !request.current()) return
            val current = _chat.value.queued.firstOrNull(::matches) ?: return
            if (current.state != state) return
            if (state == "settled") {
                if (current.state == "settled" && current.outcome == "completed" && (receipt.optJSONObject("_confirmed_persisted_input") != null || receipt.optJSONObject("_confirmed_persisted_turn") != null)) {
                    if (!bindAndRetire(scope, c, current, receipt, ::matches) { eligible() }) return
                }
                if (!automatic || item.state != "settled") drainQueued(scope, active, request.sessionGeneration, request.originGeneration)
                return
            }
            fun canFollow() = status == "started" && item.kind == "queue" && foreground && request.current() &&
                pending[scope.key] == null && phoneQueueHead()?.let { matches(it) && it.state == "accepted" } == true &&
                !app.calls.state.value.active && _chat.value.streamId.isBlank() && !followsHere(scope)
            if (!canFollow()) return
            try { app.cache.write(scope, RUN_MARKER, item.submissionId) }
            catch (e: Exception) { if (e is CancellationException) throw e; if (foreground && request.current()) notice("Couldn't save a local copy. Refresh before leaving.") }
            if (!canFollow()) return
            ++chatHistoryRevision
            _chat.update { it.copy(streamId = runtime, status = "Working") }
            if (!attachStream(c, scope, runtime) && foreground && request.current()) _chat.update { if (it.streamId == runtime) it.copy(streamId = "") else it }
        } catch (e: Throwable) { if (e is CancellationException) throw e; if (!automatic && foreground && request.current()) fail(e) }
        finally { c.closeNative() }
    }
    fun checkQueuedReceipt(id: String) = viewModelScope.launch {
        val scope = sourceScope ?: return@launch
        val active = client ?: return@launch
        val request = ScopeSettlement(scope, active, originGeneration, sessionGeneration)
        val item = _chat.value.queued.firstOrNull { it.id == id && it.state in setOf("accepted", "unknown", "settled") } ?: return@launch
        refreshQueuedReceipt(scope, active, item, request, automatic = false)
    }
    fun resendUnresolved(id: String) = viewModelScope.launch {
        val scope = sourceScope ?: return@launch
        val active = client ?: run { notice("Reconnect before sending. Your draft is kept."); return@launch }
        val item = _chat.value.queued.firstOrNull { it.id == id && it.state in setOf("accepted", "unknown") && pending[scope.key]?.id != id } ?: return@launch
        var held = false
        try {
            updateQueued(scope, item.id) { current ->
                if (current.state in setOf("accepted", "unknown") && pending[scope.key]?.id != id) { held = true; current.heldAgain() } else current
            }
            if (held && sourceScope == scope && client === active) drainQueued(scope, active)
        } catch (e: Throwable) { if (e is CancellationException) throw e; if (sourceScope == scope) fail(e) }
    }
    /**
     * Send on a box row: a waiting message is sent now (the oldest waiting message goes first); one that was not sent is held again as a new submission,
     * then sent. A tap is never a silent no-op: while something is in the way it says what, and the drain says so too when Hermes is not idle.
     */
    fun retryQueued(id: String) {
        val scope = sourceScope ?: return
        val active = client ?: run { notice("Reconnect before sending. Your draft is kept."); return }
        val item = _chat.value.queued.firstOrNull { it.id == id } ?: return
        if (item.state == "queued") {
            val state = _chat.value
            val blocked = queueSendBlockedNotice(app.calls.state.value.active, state.approval != null, state.clarification != null, state.uncertain.isNotBlank(),
                state.streamId.isNotBlank() || state.liveText.isNotBlank() || state.liveTools.isNotEmpty(), phoneQueueHead()?.state)
            if (blocked != null) { notice(blocked); return }
            // One whose file could not get through goes once more on this tap: it is a waiting message again (CHAT-121 follow-up 9).
            if (item.fileNotSent()) viewModelScope.launch {
                try {
                    linkFailures.remove(item.submissionId)
                    updateQueued(scope, id) { if (it.fileNotSent()) it.copy(outcome = "") else it }
                    if (sourceScope == scope && client === active) drainQueued(scope, active, explicit = true)
                } catch (e: Throwable) { if (e is CancellationException) throw e; if (sourceScope == scope) fail(e) }
            }
            else drainQueued(scope, active, explicit = true)
        }
        else if (item.notSent()) viewModelScope.launch {
            var held = false
            try {
                updateQueued(scope, id) { if (it.notSent()) { held = true; it.heldAgain() } else it }
                if (held && sourceScope == scope && client === active) drainQueued(scope, active, explicit = true)
            } catch (e: Throwable) { if (e is CancellationException) throw e; if (sourceScope == scope) fail(e) }
        }
    }
    /** The words of the message the phone sent and could not confirm (a send with no copy in the queue); the chat shows them as its unconfirmed bubble. */
    fun pendingSendText(): String = sourceScope?.let { pending[it.key] }?.draft?.text.orEmpty()
    /** D10: the composer still shows the words of the direct send in flight (the moment between the tap and the receipt). The field shows them as empty. */
    internal fun heldBySend(): Boolean {
        val send = sourceScope?.let { pending[it.key] } ?: return false
        val chat = _chat.value
        return !send.queued && chat.uncertain == send.id && chat.status == "Sending" && _composer.value.text == send.draft.text &&
            _attachments.value.map { it.toString() } == send.draft.attachments.map { it.toString() }
    }
    /** What a quote, dictation or share adds to: the composer, without the words a send is taking (they must never go a second time). */
    internal fun composerBase(): String = if (heldBySend()) "" else _composer.value.text
    fun editQueued(id: String) = viewModelScope.launch {
        val scope = sourceScope ?: return@launch
        val generation = sessionGeneration; val origin = originGeneration; val active = client
        val item = _chat.value.queued.firstOrNull { it.id == id && it.inBox() } ?: return@launch
        if (_composer.value.text.isNotBlank() || _attachments.value.isNotEmpty()) { notice("Clear your draft before editing a queued message"); return@launch }
        val revision = composerRevision
        try {
            checkpointJob?.cancelAndJoin()
            draftWriteLock.withLock {
                val current = draft()
                if (sourceScope != scope || sessionGeneration != generation || originGeneration != origin || client !== active || current.text.isNotBlank() || current.attachments.isNotEmpty() || current.queued.none { it.id == item.id && it.inBox() }) return@withLock null
                val edited = current.copy(text = item.text, selectionStart = item.text.length, selectionEnd = item.text.length,
                    attachments = item.attachments, queued = current.queued.filterNot { it.id == item.id })
                val encoded = edited.encode(); app.cache.write(scope, draftKind(scope), encoded, true); drafts[scope.key] = edited; savedDraft(scope, encoded)
                edited
            } ?: return@launch
            if (sourceScope == scope && sessionGeneration == generation && originGeneration == origin && client === active && composerRevision == revision && _composer.value.text.isBlank() && _attachments.value.isEmpty()) {
                _composer.value = TextFieldValue(item.text, TextRange(item.text.length)); _attachments.value = item.attachments
                _chat.update { it.copy(queued = it.queued.filterNot { q -> q.id == item.id }) }; composerRevision++
            }
            forgetPendingAttachments(scope, item.submissionId)
            afterBoxShrank(scope)
            if (sourceScope == scope && sessionGeneration == generation && originGeneration == origin && client === active) checkpointNow()
        } catch (e: Throwable) { if (e is CancellationException) throw e; if (sourceScope == scope) fail(e) }
    }
    /** [explicit] is a tap on a box button: when the drain then cannot start, it says why instead of giving up silently. */
    private fun drainQueued(scope: Scope, active: BeaconClient, generation: Int = sessionGeneration, origin: Int = originGeneration, explicit: Boolean = false) {
        // S1: the owner's tap never waits silently behind a passive drain (its look can hang on a link that is gone): it takes over, and says why if it cannot send.
        queueDrains[scope.key]?.takeIf { it.isActive }?.let { if (explicit) it.cancel() else return }
        fun current() = foreground && sourceScope == scope && client === active && sessionGeneration == generation && originGeneration == origin
        fun ready() = current() && !app.calls.state.value.active && _chat.value.let {
            it.streamId.isBlank() && it.uncertain.isBlank() && it.approval == null && it.clarification == null && it.liveText.isBlank() && it.liveTools.isEmpty()
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            if (!ready()) return@launch
            val item = phoneQueueHead()?.takeIf { it.state == "queued" } ?: return@launch
            // TURN's hold waits for an explicit Send; a file that could not get through goes only after its row's Send cleared fileNotSent (CHAT-121 fu9).
            if (passiveDrainSkips(item, explicit) || item.fileNotSent()) return@launch
            val probe = active.detached()
            var looked = false
            var refusal: String? = null
            // A queued message waiting behind a reply Hermes lost in a restart goes only on the owner's Send ([explicit]); otherwise the chat offers it.
            // The owner's tap gets Hermes' answer within EXPLICIT_DRAIN_LOOK_MS, or the line that says it could not reach it.
            val status = try {
                withTimeoutOrNull(if (explicit) EXPLICIT_DRAIN_LOOK_MS else Long.MAX_VALUE) {
                    probe.recoveredNativeSessionStatus(scope.session, if (explicit) LostTurnClose.Decided else LostTurnClose.Never).also { looked = true }
                }
            }
            catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (explicit && current() && beaconChatAbsent(active, scope, e)) {
                    recoverGoneBeaconChat(scope) { current() }
                    return@launch
                }
                if (e is ApiFailure && _ui.value.connected) refusal = appErrorText(e)
                null
            } finally { probe.closeNative() }
            val unreachable = !looked
            val idle = status == "idle"
            if (unreachable && !explicit && current() && item.waitingForConnection() && item.submissionId in linkFailures) linkTryFailed(scope, active, item)
            // The owner's Send closed the lost reply (Decided): WEDGE's choice is answered too (R2).
            if (explicit && idle && current()) _chat.update { if (it.lostWaiting) it.copy(lostWaiting = false) else it }
            if (status == "lost" && current()) showLostChoice(scope)
            if (!idle && explicit && current()) notice(refusal ?: if (unreachable) "Reconnect before sending. Your draft is kept." else "Wait for the current reply first.")
            if (!idle || !ready()) return@launch
            // The owner's Send: every hold is released before the head goes (a passive drain never gets here with a held head).
            if (explicit) for (held in _chat.value.queued.filter { it.held.isNotBlank() }) updateQueued(scope, held.id) { releaseHolds(listOf(it)).single() }
            val head = phoneQueueHead()?.takeIf { it.id == item.id && it.submissionId == item.submissionId && it.state == "queued" } ?: return@launch
            send(head).join()
        }
        queueDrains[scope.key] = job
        job.invokeOnCompletion { if (queueDrains[scope.key] === job) queueDrains.remove(scope.key) }
        job.start()
    }
    fun cancel() = viewModelScope.launch {
        val c = client ?: return@launch
        val stream = _chat.value.streamId
        // TURN-121: the interrupt that follows is this phone's Stop (interruptedTurnLine). A Stop that brings no interrupt frame (a lost reply
        // closed, or a cancel that failed) drops its mark, so a later restart on this runtime still reads as one (R6, TF-P3-3).
        val stopMark = sourceScope?.let { "${it.key}:$stream" }
        stopMark?.let { stopsSentHere += it }
        notice("Stopping…")
        try {
            val stopped = c.get("/api/chat/cancel", mapOf("stream_id" to stream) + if (boxHoldsWaiting(_chat.value.queued)) mapOf("waiting" to "1") else emptyMap())
            if (stopped.string("stopped") in setOf("lost_turn", "lost_waiting")) stopMark?.let { stopsSentHere -= it }
            // Stop on a reply Hermes lost in a restart closed it: show the chat as Hermes now has it (idle, or the next message running).
            if (stopped.string("stopped") == "lost_turn") {
                notice("")
                sourceScope?.let { scope -> _chat.update { if (it.streamId == stream) it.copy(streamId = "", status = "") else it }; recoverLostTurn(scope, known = false, fresh = true) }
                return@launch
            }
            // With the owner's messages waiting behind it, Stop starts none of them: the chat offers the choice.
            if (stopped.string("stopped") == "lost_waiting") { notice(""); sourceScope?.let { showLostChoice(it, stream) }; return@launch }
            if (stream.isBlank() || _chat.value.streamId != stream) notice("")
        } catch (e: Throwable) {
            stopMark?.let { stopsSentHere -= it }
            notice("")
            if (e is CancellationException) throw e
            fail(e)
        }
    }
    /** Hermes' status for this conversation's turn, asked once on a connection of its own; null when it cannot be read. */
    private suspend fun turnStatus(c: BeaconClient, scope: Scope): String? {
        val probe = c.detached()
        return try { probe.lookAtTurn(scope, lostCloseFor(scope))?.second }
        catch (e: Throwable) { if (e is CancellationException) throw e; null }
        finally { probe.closeNative() }
    }
    /**
     * REVIEW-JOIN-121 P3-1 (Root msg_e2127e2ea974): each line goes on what proves it. "Reconnecting…" is about the link: a new subscription ends it
     * ([linkLineBack]). "Couldn't check the reply · your messages are kept" is about the turn: only Hermes' answer about the turn ends it, with the
     * link line ([turnLineBack]); a subscription alone does not say the turn still runs.
     */
    private fun linkLineBack(scope: Scope, stream: String) {
        if (sourceScope == scope) _chat.update { if (it.streamId == stream && it.status == "Reconnecting…") it.copy(status = "Working") else it }
    }
    private fun turnLineBack(scope: Scope, stream: String) {
        if (sourceScope == scope) _chat.update { if (it.streamId == stream && it.status in LINK_WAIT_LINES) it.copy(status = "Working") else it }
    }
    /** False when the chat is not in a state to follow a stream (the app is in the background, or another account is signed in); nothing was started then. */
    private fun attachStream(source: BeaconClient, scope: Scope, stream: String, terminalAlreadyObserved: Boolean = false, quietMs: Long = streamSilenceMs): Boolean {
        if (!foreground || client?.scope != source.scope || scope.copy(session = "") != source.scope) return false
        val request = ScopeSettlement(scope, client ?: return false, originGeneration, sessionGeneration)
        val followedSubmission = _chat.value.takeIf { sourceScope == scope }?.raw?.string("_display_submission_id").orEmpty()
        // A stream that has already accepted the end of this send's turn settles it itself (it is reading the saved chat now): a second attach would replace it and then wait for an end that was delivered once.
        val ended = frameEndedTurn(liveFrames["${scope.key}:$stream"], followedSubmission)
        if (ended && followsHere(scope)) {
            if (source !== client && streamClients.values.none { it === source }) source.closeNative()
            return true
        }
        val c = source.detached()
        if (c.scope != scope.copy(session = "") || client?.scope != c.scope) { c.closeNative(); return false }
        streams[scope.key]?.cancel()
        // rules24 (G6b restart 17): the runtime id is the same for every turn, so its boundary and saved-turn proof are this attach's own. They come from the
        // frame it restores below, else start fresh: a cut turn's (left by the look that settled it) made a read with nothing running end the next turn's stream.
        "${scope.key}:$stream".let { liveBoundaries.remove(it); persistedTurns.remove(it) }
        streamSubmissions[scope.key] = followedSubmission
        streamClients[scope.key] = c; streamStarts[scope.key] = request
        streams[scope.key] = viewModelScope.launch {
            val streamJob = currentCoroutineContext()[Job]
            fun ownsCompletion() = request.current() && streams[scope.key] === streamJob && _chat.value.raw.string("_display_submission_id") == followedSubmission
            val runMarker = app.cache.read(scope, RUN_MARKER)
            val frameKey = "${scope.key}:$stream"
            val frame = (liveFrames[frameKey] ?: app.cache.read(scope, "live:$stream")?.let(::JSONObject))?.takeIf {
                val submission = _chat.value.takeIf { sourceScope == scope }?.raw?.string("_display_submission_id").orEmpty()
                submission.isBlank() || it.string("submission_id") == submission
            }
            val last = frame?.string("cursor").orEmpty()
            val cursor = ReplayCursor(stream, last)
            var lastCheckpoint = 0L
            var retry = 0
            // rules27b: this job's stream dropped at least once; its next subscription asks Hermes what it waits on when no card shows (the look before it
            // may not have reached Hermes, or a render that raced it skipped it).
            var droppedOnce = false
            var terminalError = ""
            var terminalObserved = false
            var terminalReplyVisible = false
            var completion: JSONObject? = null
            var listedOnFirstEvent = false
            var heardAt = android.os.SystemClock.elapsedRealtime()
            val quiet = QuietWindow(streamSilenceMs, quietMs)
            var silence: Job? = null
            // Reads of the saved chat for a turn whose copy has no saved row yet (readAdmittedRow), and when each admission's last one began; they end before the turn's end is settled.
            val rowReads = mutableListOf<Job>()
            val rowReadAt = mutableMapOf<String, Long>()
            try {
                // A frame saved at the end of the turn (its cursor is the end) describes a finished turn: there is nothing to wait for, only to settle it from the saved chat.
                // The frame belongs to the runtime, not to one turn: its end counts only for the turn this chat follows (a frame filtered for a chat with no submission of its own could be an earlier turn's).
                // The chat keeps the id of its own failed turn and that turn left its frame, so a frame that names the chat's id can still be an earlier turn's: Hermes decides (only idle ends it).
                // The question is a network round trip, so it is asked inside this try: a cancel while it waits still runs the cleanup below.
                val frameSaysEnded = !terminalAlreadyObserved && frameEndedTurn(frame, followedSubmission)
                val savedFrame = if (frameSaysEnded) savedTerminalFrame(turnStatus(c, scope)) else null
                val alreadyEnded = terminalAlreadyObserved || savedFrame == SavedTerminalFrame.Ended
                // The turn ended by Hermes' idle answer, not by its own end event: a lone lost reply a look closed settles this way.
                var endedByLook = alreadyEnded
                // A newer turn Hermes reports is not the frame's: only the cursor is kept, never the failed turn's text, reasoning, tools, boundary or error.
                val live = restoredFrame(frame, savedFrame)
                terminalError = live?.string("error").orEmpty()
                terminalObserved = alreadyEnded
                if (live != null) liveBoundaries[frameKey] = LiveAssistantBoundary.from(live)
                // A checkpoint can be older than the history read since: what that history holds does not come back.
                if (live != null && sourceScope == scope) _chat.update { chat -> restoredLive(chat, live, stream) }
                if (live != null && sourceScope == scope && _chat.value.raw.has("messages") && _chat.value.raw.string("active_stream_id") == stream) renderSession(_chat.value.raw, scope)
                if (terminalError.isNotBlank() && sourceScope == scope) _ui.update { it.copy(error = turnErrorHeadline(terminalError)) }
                // A silent stream asks Hermes instead of waiting forever; only for the open chat, and less often while a long turn stays quiet.
                heardAt = android.os.SystemClock.elapsedRealtime()
                if (!alreadyEnded) silence = launch {
                    // The final history read and cleanup below can outlast the window; once the turn has ended there is nothing left to ask.
                    while (!terminalObserved) {
                        delay(streamWatchMs)
                        if (sourceScope != scope || android.os.SystemClock.elapsedRealtime() - heardAt < quiet.ms) continue
                        val status = turnStatus(c, scope)
                        when (silentStreamAction(status)) {
                            SilentStream.Settle -> attachStream(c, scope, stream, terminalAlreadyObserved = true)
                            SilentStream.Resubscribe -> { turnLineBack(scope, stream); attachStream(c, scope, stream, quietMs = quiet.stretched()) }
                            // Only Hermes' own answer stretches the window; a probe that failed checks again at the same pace. CHAT-121 follow-up 11: a quiet
                            // stream that cannot even ask Hermes is not left silent on a running turn: the chat says it is reconnecting until Hermes answers.
                            SilentStream.Wait -> {
                                heardAt = android.os.SystemClock.elapsedRealtime()
                                // REVIEW-JOIN-121 P3-1: Hermes answered, so the link works; a line a failed check left goes.
                                if (status != null) { quiet.stretch(); turnLineBack(scope, stream) }
                                else if (sourceScope == scope) _chat.update { if (it.streamId == stream) it.copy(status = "Reconnecting…") else it }
                                continue
                            }
                            // The followed reply will never end: the chat offers the choice, and this stream job ends.
                            SilentStream.Lost -> { showLostChoice(scope, stream); streamJob?.cancel() }
                        }
                        return@launch
                    }
                }
                while (isActive && !terminalObserved) {
                    try {
                        heardAt = android.os.SystemClock.elapsedRealtime()
                        val resumedFrom = cursor.lastId
                        // rules27 (backlog 18): once subscribed, the link works, so a "Reconnecting…" this stream left is cleared. A card it may have missed comes
                        // back through the look that found the turn waiting ([lookAtTurn]), or, after a drop, through this subscription.
                        val opened: suspend () -> Unit = {
                            linkLineBack(scope, stream)
                            if (sourceScope == scope && droppedOnce && resumedFrom.isNotBlank() && _chat.value.approval == null && _chat.value.clarification == null) launch { pollPrompts(c, scope) }
                        }
                        c.nativeStream("/api/chat/stream", mapOf("stream_id" to stream, "session_id" to scope.session, "replay" to "1"), resumedFrom, opened) { event ->
                            if (!request.current()) return@nativeStream
                            heardAt = android.os.SystemClock.elapsedRealtime()
                            if (cursor.accept(event)) {
                                quiet.heard()
                                if (event.type in setOf("done", "error")) terminalObserved = true
                                if (event.type == "done" && event.payload().string("status") == "interrupted") terminalError = interruptedTurnLine(event.payload(), stopsSentHere.remove("${scope.key}:$stream"))
                                if (event.type in setOf("apperror", "error")) terminalError = turnErrorText(event.payload())
                                retry = 0
                                if (event.type == "done") completion = event.payload()
                                if (_chat.value.queued.any { it.queuedBehindAnotherTurn() }) markOwnTurnStarted(scope, event, request)
                                ownTurnWithoutRow(scope, event)?.let { admission ->
                                    // The turn's first frame reads at once and a few times after; while the copy still has no row, a later frame reads once more, spaced, never two at a time.
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    val last = rowReadAt[admission]
                                    if (last == null || now - last >= ADMITTED_ROW_AGAIN_MS && rowReads.none { it.isActive }) {
                                        rowReadAt[admission] = now
                                        rowReads += launch { readAdmittedRow(c, scope, admission, request, if (last == null) ADMITTED_ROW_READS_MS else listOf(0L)) }
                                    }
                                }
                                // rules24 (b): while its reply streams the chat stays bound to it; a read that blanked the stream id without an end does not unbind it.
                                if (sourceScope == scope && _chat.value.streamId.isBlank() && streams[scope.key] === streamJob && ownsCompletion()) _chat.update { if (it.streamId.isBlank()) it.copy(streamId = stream) else it }
                                applyEvent(scope, event)
                                // REVIEW-FIXCHAT-121 P1: a reconcile (a cut-short replay, an epoch change, a gap) is the only time frames were missed, a
                                // card Hermes settled meanwhile among them: Hermes' cards are read then, whatever shows. The two "no card" reads stay gated.
                                if (event.type == "state_saved" && sourceScope == scope) launch { pollPrompts(c, scope) }
                                // D4: the turn's first event means Hermes holds the chat: History and the share sheet's saved list are read again once
                                // (native Hermes sends state_saved only with a reconciliation, so it cannot be the signal).
                                if (!listedOnFirstEvent && sourceScope == scope) { listedOnFirstEvent = true; refreshSessions() }
                                if (sourceScope == scope) {
                                    val state = _chat.value
                                    val progress = (liveBoundaries[frameKey] ?: LiveAssistantBoundary()).saveInto(json("cursor" to cursor.lastId, "text" to state.liveText, "reasoning" to state.liveReasoning, "tools" to JSONArray(state.liveTools), "sealed" to JSONArray(if (terminalObserved || event.terminal) emptyList() else state.liveSealed), "sealed_at" to JSONArray(if (terminalObserved || event.terminal) emptyList() else state.sealedPlaces()), "ordinal" to state.liveOrdinal, "base" to state.liveBase, "base_prompt" to state.liveBasePrompt, "error" to terminalError, "terminal" to terminalObserved, "wait" to savedWaitLine(state.status), "submission_id" to state.raw.string("_display_submission_id")))
                                    liveFrames[frameKey] = progress
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (now - lastCheckpoint >= 200 || event.type in setOf("done", "state_saved") || event.terminal) { app.cache.write(scope, "live:$stream", progress.toString()); lastCheckpoint = now }
                                    if (event.type == "state_saved" && state.raw.has(UNCONFIRMED_REPLIES)) app.cache.writeSession(scope, state.raw.toString())
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403)) throw e
                        if (streamClients[scope.key] === c && _securePrompt.value?.scope == scope) releaseSecurePrompt(scope)
                        if (sourceScope == scope) _chat.update { it.copy(status = "Reconnecting…") }
                        droppedOnce = true
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
                            if (sourceScope == scope) _chat.update { it.copy(streamId = active, liveText = "", liveReasoning = "", liveTools = emptyList(), liveSealed = emptyList(), liveSealedAt = emptyList(), liveBase = 0, liveBasePrompt = "", liveOrdinal = 0, status = "Working") }
                            attachStream(c, scope, active)
                            return@launch
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403)) throw e
                    }
                    val liveStatus = try { c.lookAtTurn(scope, lostCloseFor(scope))?.second }
                    catch (e: Throwable) {
                        if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403)) throw e
                        null
                    }
                    if (liveStatus == "idle") { terminalObserved = true; endedByLook = true; break }
                    // The followed reply was lost in a restart and the owner's messages wait behind it: this job has nothing left to follow.
                    if (liveStatus == "lost") { showLostChoice(scope, stream); break }
                    if (sourceScope == scope) _chat.update { it.copy(status = when (liveStatus) {
                        "working" -> "Reconnecting…"
                        "waiting" -> "Reconnecting…"
                        else -> "Couldn't check the reply · your messages are kept"
                    }) }
                    delay((500L shl retry.coerceAtMost(4)).coerceAtMost(8000)); retry++
                }
                // The end of the turn reads the saved chat itself and checks that nothing else rendered meanwhile: a row read still running would make it skip its proof.
                rowReads.forEach { it.cancelAndJoin() }
                // Both a terminal event and a validated idle read need the final saved projection.
                if (terminalObserved && ownsCompletion()) {
                    val revision = chatHistoryRevision
                    // The title Hermes holds in the saved copy, once it has been read; the name step in the finally below needs only this.
                    var savedTitle: String? = null
                    try {
                        val response = c.get("/api/session", mapOf("session_id" to scope.session))
                        savedTitle = (response.optJSONObject("session") ?: response).string("title")
                        if (sourceScope == scope && client?.scope == c.scope && revision == chatHistoryRevision) {
                            val (item, identity) = finishedTurnIdentity(_chat.value.queued, completion, _chat.value.raw.string("_display_submission_id"), _chat.value.raw.string("_display_admission_id"))
                            val fresh = response.optJSONObject("session") ?: response
                            val rows = fresh.rows("messages")
                            val lookup: (suspend () -> JSONObject?)? = if (item == null) null else ({ admissionReceipt(c, scope, item.submissionId, item.admissionId, completedProof = true)?.first })
                            // The second ask uses its own connection, so a lease the first one left stuck cannot fail it again.
                            val retry: (suspend () -> JSONObject?)? = if (item == null) null else ({
                                val leased = c.detached()
                                try { admissionReceipt(leased, scope, item.submissionId, item.admissionId, completedProof = true)?.first } finally { leased.closeNative() }
                            })
                            val proven = proveFinishedTurn(identity, stream, completion, terminalError.isBlank(), knownTurnRow(item), rows, lookup, retry)
                            if (!ownsCompletion() || revision != chatHistoryRevision) return@launch
                            val checked = proven.receipt
                            val proof = checked?.optJSONObject("_confirmed_persisted_turn")
                            if (proof != null) persistedTurns[frameKey] = proof
                            renderSession(response, scope, revision)
                            val snapshot = response.optJSONObject("session") ?: response
                            if (item != null && checked != null) {
                                bindPersistedInput(scope, item, checked, rows) { ownsCompletion() }
                            }
                            if (!ownsCompletion()) return@launch
                            val turnUsers = checked?.optJSONObject("_confirmed_persisted_turn")?.optJSONArray("user_row_ids")
                                ?.let { list -> (0 until list.length()).mapNotNull { (list.opt(it) as? Number)?.toLong() } }.orEmpty()
                            pairSteerCopies(scope, turnUsers, rows) { ownsCompletion() }
                            if (!ownsCompletion()) return@launch
                            settleOpenSteers(c, scope, identity, rows, turnUsers) { ownsCompletion() }
                            if (!ownsCompletion()) return@launch
                            terminalReplyVisible = replyShownInHistory(checked, rows)
                            app.cache.writeSession(scope, _chat.value.raw.toString())
                            // An unproven ending keeps the streamed reply on screen like any other reply; only a real turn error is worded.
                            if (!terminalReplyVisible) _chat.update { it.copy(status = terminalError) }
                        } else if (sourceScope == scope && client?.scope == c.scope) {
                            _chat.update { it.copy(status = terminalError) }
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403)) throw e
                        if (sourceScope == scope && client?.scope == c.scope) _chat.update { it.copy(status = terminalError.ifBlank { appErrorText(e) }) }
                    } finally {
                        // A thread keeps the name it was given whatever else rendered the chat while its saved copy was read: the screen steps above need a
                        // quiet screen (and may leave early), the name does not. It runs after the render, as before, so the new name is not drawn over.
                        val wanted = pendingThreadTitles[scope.key]
                        val held = savedTitle
                        if (wanted != null && held != null && client?.scope == c.scope) try { keepThreadTitle(c, scope, wanted, held) } catch (e: Throwable) {
                            if (e is CancellationException || e is ApiFailure && e.status in setOf(401, 403)) throw e
                            // A name Hermes keeps refusing is asked for on one more finished turn (a blip), then dropped; a cancelled stream above is not a failed turn.
                            if (threadTitleFailures.merge(scope.key, 1, Int::plus)!! >= THREAD_TITLE_FAILED_TURNS) { pendingThreadTitles.remove(scope.key, wanted); threadTitleFailures.remove(scope.key) }
                            if (sourceScope == scope && client?.scope == c.scope) _chat.update { it.copy(status = terminalError.ifBlank { appErrorText(e) }) }
                        }
                    }
                }
                if (terminalObserved && ownsCompletion()) {
                    // The reply is over; a queued send below writes a fresh marker of its own.
                    val completedFrame = liveFrames[frameKey] ?: frame
                    val completedBoundary = liveBoundaries[frameKey]
                    val completedProof = persistedTurns[frameKey]
                    optionalLocalSave { if (runMarker != null) app.cache.delete(scope, RUN_MARKER, runMarker) }
                    if (terminalReplyVisible && ownsCompletion()) {
                        if (completedFrame != null) { liveFrames.remove(frameKey, completedFrame); app.cache.delete(scope, "live:$stream", completedFrame.toString()) }
                        if (ownsCompletion()) {
                            if (completedBoundary != null) liveBoundaries.remove(frameKey, completedBoundary)
                            if (completedProof != null) persistedTurns.remove(frameKey, completedProof)
                        }
                    }
                    if (ownsCompletion()) {
                        _chat.update { releaseTerminalChat(it, stream, terminalReplyVisible, terminalError, followedSubmission) }
                        // A turn that ended by its own events uses up a stale mark without a line.
                        if (endedByLook) showQuietLostClose(scope) else quietLostCloses.remove(scope.key)
                        if (_chat.value.raw.has(UNCONFIRMED_REPLIES)) app.cache.writeSession(scope, _chat.value.raw.toString())
                        pollPrompts(c, scope); refreshSessions()
                        if (drainsQueueAfterTerminal(terminalReplyVisible, alreadyEnded, terminalError)) client?.let { drainQueued(scope, it) }
                        // A failure trying again cannot fix, a stop or a restart: each waiting message is held, saved with the draft, until the owner's Send.
                        else if (terminalError.isNotBlank()) for (waiting in _chat.value.queued.filter { it.kind == "queue" && it.state == "queued" && it.held.isBlank() })
                            updateQueued(scope, waiting.id, { ownsCompletion() }) { holdFollowUps(listOf(it), terminalError).single() }
                    }
                }
            } catch (e: Throwable) { if (e is CancellationException) throw e; if (sourceScope == scope) fail(e) } finally {
                silence?.cancel()
                rowReads.forEach { it.cancel() }
                if (terminalObserved && ownsCompletion()) _chat.update { releaseTerminalChat(it, stream, terminalReplyVisible, terminalError, followedSubmission) }
                if (streams[scope.key] == currentCoroutineContext()[Job]) {
                    if (_securePrompt.value?.scope == scope) releaseSecurePrompt(scope)
                    streams.remove(scope.key); streamClients.remove(scope.key); streamStarts.remove(scope.key)
                }
                // Release the stream owner and the handed-over source once no successor uses them.
                if (c !== client && streamClients.values.none { it === c }) c.closeNative()
                if (source !== client && streamClients.values.none { it === source }) source.closeNative()
            }
        }
        return true
    }
    private fun secureContext() = SecureContext(sourceScope, originGeneration, sessionGeneration, foreground, _ui.value.authenticated,
        dev.hermes.android.phone.deviceLockedNow(app), System.nanoTime() / 1_000_000)
    private fun securePromptIsCurrent(prompt: SecurePrompt): Boolean = secureContext().let {
        securePromptCurrent(prompt, it.scope, it.originGeneration, it.sessionGeneration, it.foreground, it.authenticated, it.locked, it.now)
    }
    /** The keeper changes under one lock (the poll, the stream and the sheet each call from their own thread); the screen reads what it left. */
    private inline fun <T> securePromptsLocked(block: SecurePromptKeeper.() -> T): T = synchronized(securePrompts) { securePrompts.block().also { _securePrompt.value = securePrompts.shown } }

    private fun securePromptSender(prompt: SecurePrompt): BeaconClient? =
        streamClients[prompt.scope.key] ?: client?.takeIf { it.scope == prompt.scope.copy(session = "") }

    private fun sendSecurePrompt(prompt: SecurePrompt, value: String): Boolean = try {
        securePromptSender(prompt)?.nativeAnswerServerRequest(prompt.method, prompt.id, prompt.runtime,
            json("value" to value, "secure_connection" to prompt.connection))?.optBoolean("ok") == true
    } catch (_: Exception) { false }

    /** The person left the account or profile, or signed out: whatever is on screen is skipped. */
    private fun cancelSecurePrompt() { securePromptsLocked { clear() } }

    /** The chat stopped listening or failed: the question stays open at Hermes and comes back with the stream. Not an answer, not a notice. */
    private fun releaseSecurePrompt(scope: Scope) { securePromptsLocked { release(scope) } }

    /** The person pressed Cancel. */
    internal fun dismissSecurePrompt(prompt: SecurePrompt) { securePromptsLocked { cancel(prompt) } }

    /** The system closed the sheet (app paused, screen off, phone locked). The question is held, not answered. */
    internal fun holdSecurePrompt(prompt: SecurePrompt) { securePromptsLocked { suspend(prompt) } }

    internal fun securePromptClient(prompt: SecurePrompt): BeaconClient? =
        securePromptSender(prompt)?.takeIf { _securePrompt.value == prompt && securePromptIsCurrent(prompt) }

    internal fun refreshSecurePrompt() {
        val scope = sourceScope ?: return
        val authority = client?.nativeAuthorityVersion()
        if (authority == null) { cancelSecurePrompt(); return }
        val open = NativeGateway.pendingSecureRequests(scope, authority).mapNotNull { securePromptFrom(scope, originGeneration, sessionGeneration, it) }
        securePromptsLocked { settle(open, secureContext(), NativeGateway.secureLinkLive(scope, authority)) }
    }

    /** The sheet handed its answer over. A refused answer is not finished: the sheet stays open with what was typed and says so (nothing was answered). */
    internal fun securePromptFinished(prompt: SecurePrompt, sent: Boolean) {
        if (!sent) return
        val shown = _securePrompt.value?.takeIf { securePromptQuestionKey(it) == securePromptQuestionKey(prompt) } ?: return
        securePromptsLocked { finished(shown) }
        notice("Secure input sent. Waiting for Hermes.")
    }

    /** The admission a frame of this chat's stream names, when the phone holds an accepted copy of it with no saved row yet; null otherwise. */
    private fun ownTurnWithoutRow(scope: Scope, event: SseEvent): String? {
        if (sourceScope != scope) return null
        val admission = event.payload().string("turn_admission_id").ifBlank { return null }
        return admission.takeIf { _chat.value.queued.any { q -> q.kind == "queue" && q.state == "accepted" && q.rowId.isBlank() && q.admissionId == admission } }
    }
    /**
     * Hermes saves the user row of an admission when its turn starts, before the model is asked (agent/turn_context._persist_turn_start), stamped with this
     * phone's submission id. A frame of that turn has arrived, so the saved chat is read now and rendered: the render ties the copy to its stamped row
     * ([submittedRowPairs]) and the chat shows Hermes' row while the reply runs, however long the model takes. The frame can come just before the write,
     * so the turn's first frame reads at [waits] ([ADMITTED_ROW_READS_MS]) until the copy has its row, and a later frame once more ([ADMITTED_ROW_AGAIN_MS]).
     * A failed read changes nothing; the turn's end reads again.
     */
    private suspend fun readAdmittedRow(c: BeaconClient, scope: Scope, admission: String, request: ScopeSettlement, waits: List<Long>) {
        fun waiting() = sourceScope == scope && request.current() && _chat.value.queued.any { it.admissionId == admission && it.state == "accepted" && it.rowId.isBlank() }
        for (wait in waits) {
            delay(wait)
            if (!waiting()) return
            val reader = c.detached()
            try {
                val revision = chatHistoryRevision
                val response = reader.get("/api/session", mapOf("session_id" to scope.session))
                if (!waiting() || revision != chatHistoryRevision) continue
                renderSession(response, scope, revision)
                // Saved for a cold open, as the reconnect read saves its render, but only when this render ran: a skipped one would save a copy without the chat's kept keys.
                if (chatHistoryRevision == revision + 1) app.cache.writeSession(scope, response.toString())
            } catch (e: Throwable) { if (e is CancellationException) throw e }
            finally { reader.closeNative() }
        }
    }
    /** A frame of the turn Hermes runs names that turn's admission: a copy Hermes still held as queued is started once a frame names its own (QueuedMessage.afterTurnFrame). */
    private suspend fun markOwnTurnStarted(scope: Scope, event: SseEvent, request: ScopeSettlement) {
        if (sourceScope != scope) return
        val frame = event.payload()
        val admission = frame.string("turn_admission_id").ifBlank { return }
        val outcome = frame.string("outcome")
        val copy = _chat.value.queued.firstOrNull { it.afterTurnFrame(admission, outcome) != null } ?: return
        updateQueued(scope, copy.id, { request.current() }) { q -> q.afterTurnFrame(admission, outcome) ?: q }
    }
    private fun applyEvent(scope: Scope, event: SseEvent) {
        if (sourceScope != scope) return
        val o = event.payload()
        if (event.type == "session_info") {
            loadChatApprovals(force = true)
            if (o.has("model") || o.has("reasoning_effort")) {
                _chat.update { chat ->
                    val raw = chat.raw.copy()
                    for (field in listOf("model", "reasoning_effort")) if (o.opt(field) is String) raw.put(field, o.getString(field))
                    chat.copy(raw = raw, sessionReasoningEffort = o.string("reasoning_effort", chat.sessionReasoningEffort))
                }
            }
            return
        }
        if (o.string("session_id").isNotBlank() && o.string("session_id") != scope.session) return
        if (event.type == "native_event" && o.has("secure_method")) {
            val prompt = securePromptFrom(scope, originGeneration, sessionGeneration, o) ?: return
            if (NativeGateway.pendingSecureRequests(scope, client?.nativeAuthorityVersion()).none { it.optString("id") == prompt.id &&
                    it.optString("secure_connection") == prompt.connection && it.optString("secure_method") == prompt.method }) return
            securePromptsLocked { offer(prompt, secureContext()) }
            return
        }
        if (event.type == "request_cancel") securePromptsLocked { withdrawn(scope, o.string("id"), o.string("method")) }
        val boundaryKey = "${scope.key}:${_chat.value.streamId}"
        val boundary = liveBoundaries[boundaryKey] ?: LiveAssistantBoundary()
        val previousClosed = boundary.closed
        val nextBoundary = boundary.after(event.type, o)
        liveBoundaries[boundaryKey] = nextBoundary
        if (event.type in setOf("done", "state_saved") && o.optJSONObject("session")?.has("messages") == true) ++chatHistoryRevision
        if (event.type in setOf("apperror", "error")) _ui.update { it.copy(error = turnErrorHeadline(turnErrorText(o))) }
        // Hermes came back from a restart holding this chat's reply as lost: close it, so the chat and what was queued behind it move on.
        if (event.type == "state_saved" && o.optJSONObject("session")?.let { it.string("active_stream_id").isBlank() && lostTurnHeld(it) } == true) recoverLostTurn(scope, fresh = true)
        _chat.update { s -> liveAfterEvent(s, event.type, o, previousClosed, nextBoundary) ?: when (event.type) {
            "title" -> s.copy(title = o.string("title").ifBlank { s.title })
            "approval" -> s.promptAsked("approval", pendingPrompt(o), clockNow())
            "clarify" -> s.promptAsked("clarify", pendingPrompt(o), clockNow())
            "request_cancel" -> s.promptEnded(o.string("id"), clockNow())
            "todo_state" -> s.copy(raw = s.raw.copy().put("todo_state", o))
            "goal" -> s.copy(raw = s.raw.copy().put("goal", o))
            "done", "state_saved" -> { val session = o.optJSONObject("session"); if (session != null) { val merged = s.raw.copy(); session.keys().forEach { merged.put(it, session.opt(it)) }; s.copy(messages = if (session.has("messages")) session.rows("messages").mapIndexed { i, m -> Message.from(m, i, scope.session) } else s.messages, raw = merged, liveText = if (session.has("messages")) "" else s.liveText, liveReasoning = if (session.has("messages")) "" else s.liveReasoning, liveTools = if (session.has("messages")) emptyList() else s.liveTools, status = "Finishing") } else if (event.type == "done") s.copy(status = "Finishing") else s }
            "metering" -> s.copy(raw = s.raw.copy().put("native_usage", o.optJSONObject("usage") ?: s.raw.optJSONObject("native_usage")).put("native_tps", if (o.optBoolean("tps_available") && !o.optBoolean("estimated") && o.optDouble("tps", 0.0) > 0) o.optDouble("tps") else JSONObject.NULL))
            "compressing" -> s.copy(status = "Summarizing earlier messages…")
            "compressed" -> s.copy(status = "Earlier messages summarized")
            "warning" -> s.copy(status = o.string("message", o.string("error", "Hermes needs your attention.")))
            "apperror", "error" -> s.copy(status = turnErrorText(o))
            else -> s
        } }
        if (event.type in setOf("done", "state_saved")) {
            projectCallTranscripts()
            if (scope == sourceScope) loadChatApprovals(force = true)
        }
        if (event.type in setOf("done", "state_saved", "cancel", "stream_end", "error", "apperror"))
            _ui.update { it.copy(notice = stoppingNoticeAfterEvent(it.notice, event.type)) }
    }
    /**
     * Asks Hermes for its pending approval and question and shows its answer. A failed ask is not an answer: what is on screen stays, so a question the agent still waits on is not lost to a bad connection.
     * The ask uses a connection of its own and closes it (REVIEW-JOIN-121 P3-2): the caller's connection, the stream's own when a look asks, stays as it was.
     */
    private suspend fun pollPrompts(source: BeaconClient, scope: Scope) {
        val c = source.detached()
        try {
            suspend fun ask(path: String): JSONObject? = runCatching { c.get(path, mapOf("session_id" to scope.session)) }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            val since = clockNow()
            val approval = ask("/api/approval/pending")
            val clarify = ask("/api/clarify/pending")
            if (sourceScope == scope) _chat.update { it.promptsPolled(approval, clarify, clockNow(), since) }
        } finally { c.closeNative() }
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
            var approvalsCheckedAt = reconciledAt
            val finishedChecks = mutableMapOf<Triple<String, String, String>, Long>()
            while (isActive && foreground && request.current()) {
                delay(streamWatchMs)
                val now = android.os.SystemClock.elapsedRealtime()
                if (request.current() && _ui.value.tab in setOf("Chat", "Beacon") && now - approvalsCheckedAt >= 30_000) {
                    approvalsCheckedAt = now
                    loadChatApprovals()
                }
                if (followsHere(scope)) continue
                try {
                    // History alone cannot end a turn nobody heard (a saved projection names no running stream), so a chat that still shows one without a stream asks Hermes.
                    val unfollowed = _chat.value.takeIf { sourceScope == scope && it.sessionId == scope.session && !pending.containsKey(scope.key) }?.streamId.orEmpty()
                    if (unfollowed.isNotBlank()) { followRunningTurn(c.detached(), scope, unfollowed, request); continue }
                    // REVIEW-JOIN-121 P2-4: a live copy no stream is bound to is checked again on each tick until it is settled (one check at a time).
                    if (sourceScope == scope && liveCopyUnbound(_chat.value)) reconcileUnboundLive()
                    if (pending[scope.key] != null && _chat.value.status != "Sending") {
                        val receiptClient = active.detached()
                        try { reconcile(scope, receiptClient) } finally { receiptClient.closeNative() }
                    }
                    val head = phoneQueueHead()
                    if (head != null && head.state in setOf("accepted", "unknown") && pending[scope.key] == null)
                        refreshQueuedReceipt(scope, active, head, request, automatic = true)
                    if (!foreground || !request.current() || followsHere(scope)) continue
                    if (pending[scope.key] == null) {
                        val finished = _chat.value.queued.filter(::receiptSweepCandidate)
                        fun key(item: QueuedMessage) = Triple(item.id, item.submissionId, item.admissionId)
                        finishedChecks.keys.retainAll(finished.map(::key).toSet())
                        val now = android.os.SystemClock.elapsedRealtime()
                        for (item in finished.filter { finishedChecks[key(it)]?.let { last -> now - last < 60_000L } != true }.take(4)) {
                            if (!foreground || !request.current() || pending[scope.key] != null || followsHere(scope)) break
                            finishedChecks[key(item)] = now
                            refreshQueuedReceipt(scope, active, item, request, automatic = true)
                        }
                    }
                    if (!foreground || !request.current() || followsHere(scope)) continue
                    if (phoneQueueHead()?.state == "queued") drainQueued(scope, active, request.sessionGeneration, request.originGeneration)
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
                    val historyMoved = renderedServerProjection != projection
                    if (historyMoved) {
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
                    if (running.isNotBlank() && !followsHere(scope)) {
                        attachStream(c.detached(), scope, running)
                    } else if (historyMoved && running.isBlank()) followUnseenTurn(c, scope, request)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    if (e is ApiFailure && e.status == 404 && sessionCanStillBeUnpersisted(clientCreatedSessions.contains(scope.key), _chat.value)) continue
                    if (e is ApiFailure && e.status in setOf(401, 403) || missingNativeConversation(e)) {
                        if (foreground && request.current()) {
                            if (e is ApiFailure && e.status == 401) fail(e)
                            else _chat.update { it.copy(status = "Chat unavailable · draft kept") }
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
                "approval_id" -> state.promptEnded(id, clockNow(), "approval")
                "clarify_id" -> state.promptEnded(id, clockNow(), "clarify")
                else -> state
            }
        }
    }
    /** The card closes whether Hermes took the answer or had already ended the request; an ended one says so (it never reached Hermes, and it is not a lost connection). */
    private fun promptAnswered(scope: Scope, idKey: String, id: String, result: JSONObject) {
        retirePrompt(scope, idKey, id)
        // The answer landed: a request that follows closely is met by the owner's repeat tap, so the guard window counts from now too.
        _chat.update { it.answerTapped(clockNow()) }
        // Hermes took no answer from this tap: the request had ended, or another phone answered first. Either way the owner is told, so a Deny that met an earlier Allow is never taken for the answer.
        promptAnswerNotice(result.string("status")).takeIf { it.isNotBlank() }?.let { notice(it) }
    }
    /** The answers on their way, by request: a second tap on a request whose answer has not come back is not a second answer (Allow, then Deny, in one breath). It is open again when the first ends, whether it went or failed. */
    private val answering = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun answerPrompt(scope: Scope, idKey: String, id: String, path: String, body: JSONObject) {
        val key = "${scope.key}|$idKey|$id"
        if (!answering.add(key)) return
        _chat.update { it.answerTapped(clockNow()) }
        action(path, body, successNotice = "", onSuccess = { _, result -> promptAnswered(scope, idKey, id, result) }).invokeOnCompletion { answering.remove(key) }
    }
    fun approval(choice: String, shown: JSONObject, scope: Scope) {
        val pending = _chat.value.approval
        if (!acceptsPrompt(shown, pending, scope, "approval_id")) return
        // A card that has just come up after another one is not answerable yet: the tap that answered the last card, repeated, must not answer this one unseen.
        if (promptGuarded(pending, clockNow())) return
        if (choice !in pending?.optJSONArray("choices")?.strings().orEmpty()) { notice("That choice isn't available."); return }
        val id = shown.string("approval_id", shown.string("id"))
        answerPrompt(scope, "approval_id", id, "/api/approval/respond", json("session_id" to scope.session, "scope_key" to scope.key, "choice" to choice, "approval_id" to id))
    }
    fun clarify(answer: String, shown: JSONObject, scope: Scope) {
        if (!acceptsPrompt(shown, _chat.value.clarification, scope, "clarify_id") || promptGuarded(_chat.value.clarification, clockNow())) return
        val id = shown.string("clarify_id", shown.string("id"))
        answerPrompt(scope, "clarify_id", id, "/api/clarify/respond", json("session_id" to scope.session, "scope_key" to scope.key, "response" to answer, "clarify_id" to id))
    }
    fun clarifyBatch(answers: JSONObject, shown: JSONObject, scope: Scope) {
        if (!acceptsPrompt(shown, _chat.value.clarification, scope, "clarify_id") || promptGuarded(_chat.value.clarification, clockNow())) return
        val id = shown.string("clarify_id", shown.string("id"))
        answerPrompt(scope, "clarify_id", id, "/api/clarify/respond", json("session_id" to scope.session, "scope_key" to scope.key, "answers" to answers, "clarify_id" to id))
    }
    fun sessionAction(path: String, body: JSONObject = JSONObject(), callback: (suspend (JSONObject) -> Unit)? = null) {
        if (!admitConversation()) return
        if (app.calls.state.value.active && app.calls.state.value.conversationScope == sourceScope && path in setOf("/api/session/delete", "/api/session/undo", "/api/session/compress")) { notice("End the call before changing this chat."); return }
        val target = sourceScope ?: return
        val pendingKey = "${target.key}|$path"
        if (conversationChanges[pendingKey]?.isCompleted == false) return
        body.put("session_id", target.session)
        notice(conversationPendingLabel(path))
        action(path, body) { _, o -> callback?.invoke(o) }
    }
    private suspend fun retainSideQuestion() { val side = _side.value ?: return; app.cache.write(side.scope, "side-question", json("question" to side.question, "session_id" to side.sessionId, "stream_id" to side.streamId, "text" to side.text, "status" to side.status, "cursor" to side.cursor, "error" to side.error).toString(), side.status !in setOf("Complete", "Stopped")) }
    fun resumeSideQuestion(): Job? {
        val side = _side.value ?: return null; val active = client ?: return null; val c = active.detached()
        if (side.scope != sourceScope || side.scope.copy(session = "") != c.scope) return null
        val request = ScopeSettlement(side.scope, active, originGeneration, sessionGeneration)
        sideJob?.cancel()
        sideJob = viewModelScope.launch {
            val cursor = ReplayCursor(side.streamId, side.cursor)
            var terminalError = side.error
            try {
                try {
                    c.stream("/api/chat/stream", mapOf("session_id" to side.sessionId, "stream_id" to side.streamId, "replay" to "1"), cursor.lastId) { event ->
                        if (_side.value?.scope != side.scope || _side.value?.streamId != side.streamId || !cursor.accept(event)) return@stream
                        val p = event.payload()
                        if (event.type in setOf("error", "apperror")) terminalError = turnErrorHeadline(turnErrorText(p))
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
                _side.update { it?.copy(error = if (active.isNotBlank() && active != side.streamId) "" else terminalError, text = if (active.isNotBlank() && active != side.streamId) "" else if (active.isBlank()) text ?: it.text else it.text, streamId = active.ifBlank { it.streamId }, cursor = if (active.isNotBlank() && active != side.streamId) "" else it.cursor, status = if (active.isNotBlank() && active != side.streamId) "Responding" else terminalError.ifBlank { if (active.isBlank()) { if (it.status == "Stopped") "Stopped" else "Complete" } else "Disconnected · reconnect to see the answer" }) }
                retainSideQuestion()
                if (active.isNotBlank() && active != side.streamId) resumeSideQuestion()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                // Follow-up 2, as A1: a refused sign-in fails only the account still in use (an account change also cancels this job, via clearDetails).
                if (e is ApiFailure && e.status in setOf(401, 403)) { if (request.current()) fail(e) }
                else if (_side.value?.scope == side.scope && _side.value?.streamId == side.streamId) _side.update { it?.copy(status = terminalError.ifBlank { appErrorText(e) }) }
            }
        }
        return sideJob
    }

    fun closeSideQuestion() { sideJob?.cancel(); sideCheckpointJob?.cancel(); val side = _side.value; _side.value = null; if (side != null) viewModelScope.launch { app.cache.delete(side.scope, "side-question") } }
    /** The newest cached answer of each screen key under [scope], marked stale until a fetch confirms it. */
    private suspend fun cachedScreens(scope: Scope): Map<String, JSONObject> = try {
        val generation = originGeneration
        screens.open(scope)
        val version = screens.readVersion(scope, "settings:memory")
        phoneSettings.prepareSavedMemory(scope) { generation == originGeneration && screens.readCurrent(scope, "settings:memory", version) }
        val invalidatedAt = readInvalidations[scope.key] ?: (app.cache.read(scope, "read-invalidated-at")?.toLongOrNull() ?: 0L).also { readInvalidations[scope.key] = it }
        val now = System.currentTimeMillis()
        withContext(Dispatchers.Default) {
            screens.latest(scope).filterNot { it.key in exactScreenKeys || it.key.startsWith("skill-content:") }
                .associate { it.key to it.value.copy().put("_stale", !startupReadFresh(it.at, now, invalidatedAt)).put("_cached_at", it.at) }
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
    /** Screens render from the last good answers before any network call; answers already loaded in this run win. */
    private fun hydrateScreens(scope: Scope) {
        viewModelScope.launch {
            val cached = cachedScreens(scope)
            if ((client?.scope ?: cachedOwnerScope) == scope) {
                val selected = sourceScope?.takeIf { it.copy(session = "") == scope } ?: scope
                _data.update { cached + screens.composer(selected) + it }; phoneSettings.bind(); phoneSettings.seedSaved()
            }
        }
    }
    /** For a screen that loads outside [load] (Kanban, devices): show its answer at once next time, and keep it on the phone. */
    fun keepScreen(scope: Scope, key: String, value: JSONObject) {
        if (client?.scope != scope) return
        val version = screens.readVersion(scope, "")
        storeData(key, value)
        viewModelScope.launch(Dispatchers.Default) { screens.put(scope, key, "", System.currentTimeMillis(), value, version) }
    }
    /** Device removal, sign-out and account change: the account's screens, chat lists and previews go at once; the
     *  per-chat transcripts kept for offline reading expire on their own after two days. */
    private suspend fun deleteForgotten(scope: Scope, kind: String, removal: DeviceRemovalAuthority?) {
        if (removal == null) app.cache.delete(scope, kind) else app.cache.deleteIfCurrent(scope, kind, removal)
    }
    /** Sign-out, account change and a phone removed on the computer (Root, 18 Nov): the saved chats go with the screens; drafts and in-flight sends stay. */
    private suspend fun forgetScreens(scope: Scope, removal: DeviceRemovalAuthority? = null, chats: Boolean = true) {
        forgetKeptData()
        if (removal == null) screens.clear(scope)
        else screens.clear(removal) { account -> app.cache.deleteIfCurrent(account, "screens", removal) }
        if (removal == null) dev.hermes.android.ui.CallHistoryMemory.forget(scope)
        else removal.ifCurrent { dev.hermes.android.ui.CallHistoryMemory.forget(scope) }
        try {
            startupReads.forEach { deleteForgotten(scope, "read:${it.path}", removal) }
            listOf("settings:skills", "settings:memory", dev.hermes.android.agentphone.AGENT_PHONE_LOG_KIND).forEach { deleteForgotten(scope, it, removal) }
            // Chat join (rules22): the agent phone's activity page is kept at its link's scope, the assistant's profile for a push.
            preferences.value.assistantProfile.takeIf { it.isNotBlank() && it != scope.profile }?.let { assistant ->
                deleteForgotten(scope.copy(profile = assistant, session = ""), dev.hermes.android.agentphone.AGENT_PHONE_LOG_KIND, removal)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* those unprotected rows expire after two days */ }
        // Chat join (rules22): and what this process holds of the account's agent phone (the list's last rows, the approval board).
        dev.hermes.android.agentphone.forgetAgentPhoneAccount(scope)
        forgetGlances(scope, removal, chats)
        // Files the agent sent this account leave the phone with its screens.
        dev.hermes.android.data.ReceivedFiles.store(app).forgetAccount(scope)
    }
    fun forgetScreens() { val scope = client?.scope ?: return; viewModelScope.launch { forgetScreens(scope) } }
    private val forgetOnDeviceRemoval: (DeviceRemovalAuthority) -> Unit = { removal -> viewModelScope.launch { forgetScreens(removal.scope, removal) } }
    init { NativeDeviceRemoval.onForgetScreens = forgetOnDeviceRemoval; dev.hermes.android.updates.PluginInstallHub.controller = pluginInstall }
    // Threads made where Hermes cannot store the thread source are marked on this phone; the marks survive a restart in the same cache as the conversation index.
    init {
        // Hermes declines an approval after its own wait and tells the phone only when a snapshot runs, so a prompt whose wait ran out leaves the queue here, with no event:
        // a dead card at the front must not hide a live one behind it.
        viewModelScope.launch {
            _chat.map { nextPromptExpiry(it) }.distinctUntilChanged().collectLatest { at ->
                if (at != null) {
                    while (clockNow() < at) delay((at - clockNow()).coerceAtLeast(1L))
                    _chat.update { it.promptsPruned(clockNow()) }
                }
            }
        }
    }
    init { ThreadMarks.store = object : ThreadMarks.Store { override suspend fun read(account: Scope) = app.cache.read(account, "thread-marks"); override suspend fun write(account: Scope, value: String) = app.cache.write(account, "thread-marks", value, protected = true) } }
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
            // The plugin's own reads wait for the probe that says whether it is there; asked too early they would be left out until the next warm-up.
            withTimeoutOrNull(PREFETCH_PROBE_WAIT) { optionalProbe?.join() }
            if (!current()) return@launch
            // Battery saver keeps the small startup reads and leaves the rest until a screen opens; nothing is retried in the background.
            if (!warmScreensAllowed(readWarmConditions(app))) return@launch
            val plugin = _ui.value.bootstrap.optJSONObject("optional_features")?.optString("plugin") == "available"
            // A startup read that did not finish inside its budget is asked again here (only when it is still cold), then the rest.
            val reads = (startupReads + prefetchReads +
                StartupRead("$HISTORY_RETENTION_READ_KEY:${scope.key}", HISTORY_RETENTION_READ_PATH) + StartupRead(PUSH_STATUS_READ_KEY, PUSH_STATUS_READ_PATH))
                .filter { (plugin || "/plugins/beacon/" !in it.path) && (app.firebaseConfigured || it.key != PUSH_STATUS_READ_KEY) }
            // Warming never cancels or replaces what a screen is loading or showing: only cold, stale or failed answers are fetched.
            fun cold(read: StartupRead) = loads[read.key]?.isActive != true && _data.value[read.key].let { shown ->
                shown == null || (shown.optBoolean("_stale") || shown.has("_error")) && shown.optString("_requested_path").let { it.isEmpty() || it == read.query["path"] } }
            // Files lists the folder it will open in, worked out when this step runs, after the projects it may depend on.
            val files: suspend () -> Unit = { StartupRead("files", "/api/pc/files", mapOf("path" to filesFolder())).let { read -> if (cold(read)) ensureLoaded(read.key, read.path, read.query).join() } }
            // Chat join: CACHEFIX-B's steps and EMU-JOIN's agent phone.
            val steps = reads.map<StartupRead, suspend () -> Unit> { read -> { if (cold(read)) ensureLoaded(read.key, read.path, read.query).join() } } + files + listOf<suspend () -> Unit>(
                { if (plugin) phoneSettings.loadBots()?.join() }, { prefetchKanban(active) }, { prefetchVault(active) }, { if (plugin) prefetchDevices(active) },
                { if (plugin) prefetchPluginHealth(active) }, { if (plugin) prefetchProactive(active) }, { if (plugin) prefetchAgentPhone(active) },
                { if (plugin) {
                    val c = active.detached()
                    try {
                        val page = c.get(dev.hermes.android.ui.CALL_HISTORY_PATH, mapOf("profile" to scope.profile, "limit" to "50"))
                        if (current()) keepScreen(scope, dev.hermes.android.ui.CALL_HISTORY_KEY, dev.hermes.android.ui.callHistoryPage(page))
                    } finally { c.closeNative() }
                } }) + listOf("Health", "Server updates", "Session checkpoints", "Logs").map { page -> suspend { phoneSettings.warmServer(active, page) } }
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
            // The schedules the page opens with: the history of the first few, so a tapped row is already filled.
            if (current()) _data.value["crons"]?.rows("jobs").orEmpty().mapNotNull { it.string("id").takeIf(String::isNotBlank) }.take(PREFETCH_SCHEDULE_HISTORIES).forEach { id ->
                if (current() && idle()) try { ensureLoaded("cron-history:$id", "/api/crons/history", mapOf("job_id" to id)).join() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            // What a screen's own read left cold (a startup read that ran out of its budget shares its job with this warm-up and takes it down with it) is asked once more.
            if (current()) reads.filter(::cold).forEach { read ->
                if (current() && idle()) try { ensureLoaded(read.key, read.path, read.query).join() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            // Files: the folder it opens in is known once the projects are, and a listing of another folder is not the one the page shows.
            if (current() && idle()) filesFolder().let { folder ->
                if (_data.value["files"]?.optString("_requested_path") != folder) try { ensureLoaded("files", "/api/pc/files", mapOf("path" to folder)).join() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            if (current()) prefetched = scope.key to System.currentTimeMillis()
        }
    }
    /** The Connection page's plugin status, kept like every screen's last answer. The route answers the owner only; for anyone else the read fails and the step stays cold. */
    private suspend fun prefetchPluginHealth(active: BeaconClient) {
        val c = active.detached()
        try { keepScreen(c.scope, dev.hermes.android.ui.PLUGIN_HEALTH_KEY, c.nativeRequest(dev.hermes.android.ui.PLUGIN_HEALTH_PATH)) } finally { c.closeNative() }
    }
    /** The Assistant page's watch settings (the sources among them), read for the assistant's profile as that page reads them. */
    private suspend fun prefetchProactive(active: BeaconClient) {
        val profile = preferences.value.assistantProfile.ifBlank { return }
        val key = dev.hermes.android.ui.proactiveSettingsKey((pcScope() ?: active.scope).copy(profile = profile, session = ""))
        keepScreen(active.scope, key, dev.hermes.android.ui.fetchProactiveSettings(active, profile))
    }
    private suspend fun prefetchKanban(active: BeaconClient) {
        val c = active.detached()
        try {
            val repo = NativeBoards(c) { key, document -> keepScreen(c.scope, key, document) }
            val (boards, current) = repo.choices()
            (current.takeIf { slug -> boards.any { it.slug == slug } } ?: boards.firstOrNull()?.slug)?.let { repo.read(it) }
        } finally { c.closeNative() }
    }
    /** Hermes' own vault list (metadata only), so the Vault page opens already loaded; a Hermes without one stays cold. */
    private suspend fun prefetchVault(active: BeaconClient) {
        val c = active.detached()
        try { keepScreen(c.scope, dev.hermes.android.ui.VAULT_CACHE_KEY, NativeVaultApi(c).snapshot().toJson()) } finally { c.closeNative() }
    }
    /** Beacon's phone page for the assistant's profile (status, policy, activity, Needs-you), so it opens filled (cache review, fu2). */
    private suspend fun prefetchAgentPhone(active: BeaconClient) {
        val profile = preferences.value.assistantProfile
        val c = active.detached()
        val scoped = runCatching { c.forAuthorizedProfile(profile) }.getOrNull()
        try {
            val page = scoped?.let { dev.hermes.android.ui.agentPhonePageRead(dev.hermes.android.data.AgentPhoneApi(dev.hermes.android.data.ClientAgentPhoneEndpoint(it))) }
            page?.let { keepScreen(c.scope, dev.hermes.android.ui.agentPhoneCacheKey(profile), it) }
        } finally { scoped?.closeNative(); c.closeNative() }
    }
    private suspend fun prefetchDevices(active: BeaconClient) {
        val c = active.detached()
        try { keepScreen(c.scope, PAIRED_DEVICES_KEY, pairedDevicesJson(NativePairing.devices(c))) } finally { c.closeNative() }
    }
    fun ensureLoaded(key: String, path: String, query: Map<String, String> = emptyMap()): Job = load(key, path, query, force = false, quiet = true)
    /** A [quiet] read warms data nobody asked for yet: a failure stays on its key and never raises the app-wide error (an expired sign-in still does). */
    fun load(key: String, path: String, query: Map<String, String> = emptyMap(), force: Boolean = true, quiet: Boolean = false): Job {
        val active = client ?: return loadWithoutClient(key, path, query, force, quiet)
        val scope = active.scope; val generation = originGeneration
        val version = screens.readVersion(scope, path)
        val session = query["session_id"]
        val selection = sessionGeneration
        val request = loadRequestId(active, path, query)
        if (loadRequests[key] == request) loads[key]?.takeIf { it.isActive }?.let { return it }
        loads[key]?.cancel()
        loadRequests[key] = request
        val cacheable = screenReadCacheable(path, query, scope.profile)
        // Display-only reads are tagged with their request, so a reopened screen can tell an answer for this chat's model from another's.
        val displayOnly = path in setOf("/api/approval/mode", "/api/projects/detail", "/api/reasoning", "/api/mobile/beacon/reasoning")
        val stored = screenRequest(path, query)
        fun visible() = client === active && generation == originGeneration && _ui.value.authenticated && loadRequests[key] == request &&
            screens.readCurrent(scope, path, version) && (session == null || sourceScope?.session == session && sessionGeneration == selection)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
          try {
            val invalidatedAt = readInvalidations[scope.key] ?: (app.cache.read(scope, "read-invalidated-at")?.toLongOrNull() ?: 0L).also { readInvalidations[scope.key] = it }
            val cachedAt = loadedAt[request] ?: 0L
            if (displayOnly && !force && visible() && _data.value[key]?.has("_error") == false &&
                _data.value[key]?.string("_request_key") == request && screenDisplayFresh(path, cachedAt, System.currentTimeMillis(), invalidatedAt)) return@launch
            if (cacheable) {
                // Stale-while-revalidate: the last good answer is shown at once, then replaced when the fetch lands.
                if (!visible()) return@launch // sign-out already cleared this account; opening it again would bring the row back
                screens.open(scope)
                if (!force && visible() && _data.value[key]?.has("_error") == false && _data.value[key]?.optLong("_cached_at") == cachedAt && _data.value[key]?.string("_request_key") == request && screenDisplayFresh(path, cachedAt, System.currentTimeMillis(), invalidatedAt)) return@launch
                if (!visible()) return@launch
                // The same answer this run already fetched (under any key) is reused; an older saved copy only fills the screen first.
                (screens.exact(scope, key, stored) ?: screens.sameRequest(scope, stored))?.takeIf { (loadedAt[request] ?: it.at) == it.at }?.let { cached ->
                    val fresh = loadedAt[request] == cached.at && screenDisplayFresh(path, cached.at, System.currentTimeMillis(), invalidatedAt)
                    val value = cached.value.copy().put("_stale", !fresh).put("_cached_at", cached.at).put("_request_key", request)
                    if (path == PROFILE_DEFAULTS_PATH) value.put("session_id", session.orEmpty())
                    _data.update { it + (key to value) }
                    if (fresh && !force) return@launch
                }
            }
            _refreshing.update { it + key }
            val effortOrder = if (key == "picker:reasoning") ++reasoningReadOrder else 0L
            val response = screens.fetch(scope, request) {
                val c = active.detached()
                try {
                    val read: suspend () -> JSONObject = { c.get(path, query, maxBytes = if (cacheable && startupReads.any { it.path == path }) 1024 * 1024 else LOCAL_CHECKPOINT_LIMIT_BYTES.toInt()) }
                    // A busy Hermes answers the chat's defaults slowly now and then: ask once more before anything is reported.
                    if (path == PROFILE_DEFAULTS_PATH) readDefaultsAgain(read = read) else read()
                }
                finally { c.closeNative() }
            }
            if (!visible()) return@launch
            val at = System.currentTimeMillis()
            if (key == "picker:reasoning") {
                if (path == reasoningPath() && query == reasoningQuery(_chat.value.sessionId, _chat.value.raw)) {
                    publishEffortRead(active, path, query, effortOrder, response)
                    loadedAt[request] = at
                    _ui.update { it.copy(connected = true) }
                }
                return@launch
            }
            if (cacheable) withContext(Dispatchers.Default) { screens.put(scope, key, stored, at, response, version) }
            if (key == "profile-settings") app.cache.write(scope, "profile-settings", response.toString())
            if (visible()) {
                loadedAt[request] = at
                _data.update { it + (key to if (cacheable || displayOnly) response.copy().put("_cached_at", at).put("_request_key", request) else response) }
                _ui.update { it.copy(connected = true) }
            }
          } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (visible()) {
                val failure = conversationActionFailure(path, e)
                if (path == CHAT_APPROVAL_PATH && e !is NativeAuthExpiredException && !(e is ApiFailure && e.status in setOf(401, 403))) {
                    val previous = _data.value[key]?.takeIf { it.string("scope_key") == scope.key && it.string("session_id") == session && it.has("profile_mode") }
                    if (previous != null) {
                        storeData(key, previous.copy().put("_stale", true))
                        return@launch
                    }
                }
                if (path == PROFILE_DEFAULTS_PATH && e !is NativeAuthExpiredException && !(e is ApiFailure && e.status == 401)) {
                    // The chat keeps the last good defaults of this account and profile; only an empty model picker says anything, and never as a banner.
                    _data.update { values -> values + (key to profileDefaultsAfterFailure(values[key], scope.key, scope.profile, query["session_id"].orEmpty(), e)) }
                    return@launch
                }
                _data.update { values -> values + (key to ((if (cacheable || path == "/api/projects/detail") values[key]?.copy() else null) ?: JSONObject()).put("_error", beaconOwnerSetupMessage(e) ?: appErrorText(failure)).apply { if ((cacheable || displayOnly) && length() > 1) put("_stale", true); if (path == "/api/commands/catalog") { put("available", false); if (!has("commands")) put("commands", JSONArray()) } }) }
                if (loadFailureRaisesBanner(path, force, quiet, cacheable) || e is ApiFailure && e.status == 401 || e is NativeAuthExpiredException) fail(failure)
            }
          } finally { if (loads[key] === currentCoroutineContext()[Job]) _refreshing.update { it - key } }
        }
        loads[key] = job; job.start(); return job
    }
    /** No client yet. A restored sign-in shows the saved answer of this read (stale) and asks again once connected; "Reconnect" is said only when nothing is saved and nothing is connecting. */
    private fun loadWithoutClient(key: String, path: String, query: Map<String, String>, force: Boolean, quiet: Boolean): Job {
        val owner = cachedOwnerScope
        val generation = originGeneration
        fun waiting() = generation == originGeneration && client == null && cachedOwnerScope == owner
        if (owner != null) askedEarly[key] = AskedEarly(owner, path, query, force, quiet)
        return viewModelScope.launch {
            var shown = _data.value[key]?.has("_error") == false
            if (owner != null && screenReadCacheable(path, query, owner.profile) && waiting()) {
                screens.open(owner)
                if (waiting()) (screens.exact(owner, key, screenRequest(path, query)) ?: screens.sameRequest(owner, screenRequest(path, query)))?.let { saved ->
                    _data.update { it + (key to saved.value.copy().put("_stale", true).put("_cached_at", saved.at)) }; shown = true
                }
            }
            if (earlyReadShows(owner != null, shown, _ui.value.loading || _ui.value.restoringSession) == EarlyRead.Reconnect && generation == originGeneration && client == null)
                storeData(key, (_data.value[key]?.copy() ?: JSONObject()).apply { remove("_loading"); put("_error", dev.hermes.android.ui.OfflineReadError) })
        }
    }
    /** The connection is up: what pages asked for meanwhile (and a failed first read of the same keys) is read now. */
    private fun replayEarlyReads(active: BeaconClient) {
        val early = askedEarly.entries.filter { it.value.scope.key == active.scope.key }.map { it.key to it.value }
        askedEarly.clear()
        early.forEach { (key, ask) -> load(key, ask.path, ask.query, force = ask.force, quiet = ask.quiet) }
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
        val reasoning = path == "/api/reasoning"
        fun signedInAccount() = _ui.value.authenticated && (client?.scope ?: cachedOwnerScope)?.sameAccount(scope) == true
        val removing = path == "/api/session/delete"
        var committed = false
        try {
            if (path in setOf("/api/session/update", "/api/reasoning", "/api/approval/mode", "/api/commands/exec")) {
                require(selected != null && selected.copy(session = "") == scope && body.string("session_id") == selected.session && body.string("scope_key", selected.key) == selected.key) { "Reopen this setting in its original chat." }
            }
            if (path in setOf("/api/session/delete", "/api/session/undo", "/api/session/compress")) {
                require(selected != null && body.string("session_id") == selected.session) { "Reopen this action in its original conversation." }
                require(!(app.calls.state.value.active && app.calls.state.value.conversationScope == selected)) { "End the call before changing this chat." }
            }
            if (removing) { require(_chat.value.streamId.isBlank()) { "Stop the reply before deleting this chat." }; sessionWatch?.cancelAndJoin() }
            if (path == CHAT_APPROVAL_PATH) {
                val expected = _data.value["picker:approvals"]
                val fresh = c.get(path, mapOf("session_id" to body.getString("session_id")))
                if (!current()) return@launch
                storeData("picker:approvals", fresh)
                require(sameApprovalForChange(expected, fresh, body.getBoolean("yolo"))) { "Refresh Approvals in this chat before changing it." }
            }
            val admission = if (reasoning) withContext(Dispatchers.IO) {
                app.auth.incidentAdmission(scope).takeIf { credentialVersion != null && app.auth.authorityEpoch.current(credentialVersion) }
            } else null
            val result = c.call(path, method = method, data = body)
            if (result.has("ok") && !result.optBoolean("ok")) error(result.string("message", result.string("status", "Hermes didn't accept that.")))
            committed = true
            // Hermes has the answer: the card closes even if the chat was reselected meanwhile (the rest below is then skipped), or it would stay up to be answered a second time.
            if (path in PROMPT_ANSWER_PATHS && selected != null) retirePrompt(selected, if (path == "/api/approval/respond") "approval_id" else "clarify_id", body.string(if (path == "/api/approval/respond") "approval_id" else "clarify_id"))
            if (removing && selected != null) {
                archivedHere.remove(selected.key)
                clientCreatedSessions.remove(selected.key)
                val keptRows = _chat.value.raw.rows("messages")
                optionalLocalSave { app.cache.delete(selected, "client-created-shell"); forgetKeptAttachments(selected, keptRows) }
            }
            if (reasoning) {
                // Navigation may have moved on. A renewed credential can still belong to the same saved sign-in.
                val epoch = app.auth.authorityEpoch.readVersion() ?: return@launch
                if (!signedInAccount()) return@launch
                val sameClient = client === active && originGeneration == generation && epoch == credentialVersion
                if (!sameClient && (admission == null || withContext(Dispatchers.IO) { app.auth.incidentAdmission(scope) } != admission)) return@launch
                screens.open(scope)
                if (!signedInAccount() || !app.auth.authorityEpoch.current(epoch)) return@launch
                onSuccess?.invoke(c, result)
            }
            if (current()) {
                if (path == "/api/session/undo") ++chatHistoryRevision
                if (path == "/api/session/archive" && selected != null) {
                    confirmedSessions.remove(selected.key)
                    val archived = result.getBoolean("hidden")
                    rememberArchived(selected, selected.session, archived)
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
                val approvalRequest = selected?.takeIf { path == CHAT_APPROVAL_PATH }?.let { screenRequest(path, mapOf("session_id" to it.session)) }
                if (!reasoning) screens.invalidate(scope, path, approvalRequest)
                if (path == "/api/profile/approval-mode") screens.invalidate(scope, CHAT_APPROVAL_PATH)
                if (approvalRequest != null) {
                    screens.set(scope, "picker:approvals", result, approvalRequest)
                }
                val cacheError = runCatching { app.cache.write(scope, "read-invalidated-at", invalidation.toString()) }.exceptionOrNull()
                if (!current()) return@launch
                val message = when {
                    path == "/api/session/update" && result.optBoolean("deferred") -> "Model saved for the next reply"
                    path == "/api/session/archive" -> if (body.optBoolean("archived")) "Archived" else "Unarchived"
                    else -> successNotice
                }
                _ui.update { it.copy(connected = true, error = "", notice = if (cacheError == null) message else refreshPageNotice(message)) }
                if (path == "/api/session/archive" && body.optBoolean("archived")) _archiveUndo.value = selected
                val removedUser = if (path == "/api/session/undo") result.optJSONObject("target_message")?.string("id")
                    ?.toLongOrNull()?.takeIf { it > 0 }?.toString().orEmpty() else ""
                var undoReconciled = false
                // Undo's form uses refresh=false and may reselect the cached chat in its callback.
                if (removedUser.isNotBlank() && selected != null && current()) {
                    for (item in _chat.value.queued.filter { it.rowId == removedUser }) {
                        updateQueued(selected, item.id, { current() }) { if (it.rowId == removedUser) null else it }
                    }
                    if (!current()) return@launch
                    val revision = chatHistoryRevision
                    val response = c.get("/api/session", mapOf("session_id" to selected.session))
                    if (current() && revision == chatHistoryRevision) {
                        renderSession(response, selected, revision, allowRemoval = true, removedUserRowId = removedUser)
                        if (current()) app.cache.writeSession(selected, _chat.value.raw.toString())
                        undoReconciled = true
                    }
                }
                if (!current()) return@launch
                if (!reasoning) onSuccess?.invoke(c, result)
                if (refresh && !undoReconciled && current() && selected != null) {
                    val revision = chatHistoryRevision; val response = if (path == "/api/session/update" && result.has("session")) result else c.get("/api/session", mapOf("session_id" to selected.session))
                    if (current() && revision == chatHistoryRevision) { renderSession(response, selected, revision,
                        allowRemoval = path == "/api/session/delete" || path == "/api/session/undo" && removedUser.isNotBlank(), removedUserRowId = removedUser)
                        val running = _chat.value.streamId
                        if (running.isNotBlank() && !followsHere(selected)) attachStream(c.detached(), selected, running)
                        if (current()) refreshSessions() }
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (current() && e is NativeModelConfirmation && selected != null) {
                closeCatalog()
                openForm(dev.hermes.android.ui.ActionForm("Confirm model change", path, emptyList(), body.copy().put("confirm_expensive_model", true).put("scope_key", selected.key), explanation = "Model: ${body.string("model")}\n\n${e.message.orEmpty()}", actionLabel = "Use model"))
            } else {
                if (e is DeleteKeptWaiting && selected != null) restoreWithdrawn(selected, e.withdrawn)
                if (current()) { val failure = conversationActionFailure(path, e); _form.update { if (submission != null && it?.id == submission) it.copy(error = appErrorText(failure)) else it }; fail(failure) }
                // An answer that did not go is never silent, even when the chat was reselected or the connection replaced while it was in flight (the card is still there to answer again).
                else if (!committed && path in PROMPT_ANSWER_PATHS && e !is NativeAuthExpiredException && !(e is ApiFailure && e.status == 401)) notice(lostAnswerNotice(sameChat = selected != null && sourceScope == selected))            }
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
                name = attachmentName(uri.toString(), details.first)
                if (!current()) return@launch
                if (!workspace) _attachmentPreparation.update { it + json("id" to id, "name" to name, "status" to "preparing") }
                uploadSlots.withPermit {
                    if (!current()) return@withPermit
                    val mime = resolver.getType(uri) ?: "application/octet-stream"
                    val sizeHint = details.second
                    // What this chat's connection sends (CHAT-121 follow-up 4). With session authority, a file up to that connection's limit (Hermes' own
                    // cap and what one frame carries, in pieces through a plugin that takes them, about 6 MB through one that does not); a file past one
                    // text frame stays on this phone as a file, not as base64, until it is sent. Without session authority, as before: 11 MB in all.
                    val (authority, limits) = if (workspace) false to AttachmentLimits()
                        else try { active.nativeAttachmentLimits() } catch (e: CancellationException) { throw e } catch (_: Exception) { false to AttachmentLimits() }
                    val fileCap = attachmentLimitBytes(limits, name, mime)
                    val readCap = if (workspace) 32L * 1024 * 1024 else if (authority) fileCap else MAX_CHAT_ATTACHMENT_BYTES
                    fun tooLarge(): Exception = if (workspace) IllegalArgumentException("This file is too large (32 MB at most).")
                        else if (authority) AttachmentTooLarge(fileCap) else IllegalArgumentException("Attachments are too large (11 MB in total at most).")
                    if (sizeHint != null && sizeHint > readCap) throw tooLarge()
                    val file = File.createTempFile("upload", ".data", app.cacheDir)
                    try {
                        withContext(Dispatchers.IO) {
                            resolver.openInputStream(uri)?.use { input -> file.outputStream().use { out ->
                                val bytes = ByteArray(32768); var total = 0L
                                while (true) {
                                    ensureActive()
                                    val n = input.read(bytes); if (n < 0) break
                                    total += n; if (total > readCap) throw tooLarge()
                                    out.write(bytes, 0, n)
                                }
                            } } ?: error("Could not read attachment")
                        }
                        val kept = !workspace && authority && file.length() > textFrameFileBytes(name, mime)
                        val items = if (kept) listOf(json("id" to java.util.UUID.randomUUID().toString(), "state" to "ready", "scope_key" to c.scope.session(scope.session).key,
                            "name" to name, "mime" to mime, "size" to file.length(), "staged_file" to true))
                        else c.upload(if (workspace) "/api/workspace/upload" else "/api/upload", file, name, mime, scope.session, directory).let { response ->
                            if (workspace) emptyList() else response.rows("files").ifEmpty { listOf(response.optJSONObject("file") ?: response) }
                        }
                        if (!workspace && !withContext(Dispatchers.IO) { attachmentFitsLocalCheckpoint(PendingSend.create(scope, draft().let { it.copy(attachments = it.attachments + items) })) }) error("Attachments are too large (11 MB in total at most).")
                        if (current()) {
                            if (workspace) {
                            notice("File uploaded")
                            if (loadRequests["files"] == listingRequest) {
                                loads["files"]?.cancelAndJoin()
                                if (current() && loadRequests["files"] == listingRequest) load("files", "/api/pc/files", mapOf("path" to listingDirectory)).join()
                            }
                        }
                            else {
                                if (kept) withContext(Dispatchers.IO) { ChatAttachmentStore.adopt(file, items.single().string("id")) }
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
                    // A file over the limit says the limit, in the owner's language (the row's text is shown as it is).
                    else _attachmentPreparation.update { rows -> rows.filterNot { it.string("id") == id } + json("id" to id, "name" to name, "status" to "error",
                        "error" to (if (e is AttachmentTooLarge) dev.hermes.android.push.serviceText(app, ATTACHMENT_TOO_LARGE_ROW).replace("{0}", megabytes(e.limitBytes)) else appErrorText(e))) }
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
    fun removeAttachment(index: Int) {
        _attachments.value.getOrNull(index)?.let { item -> viewModelScope.launch(Dispatchers.IO) { ChatAttachmentStore.delete(item) } }
        _attachments.update { it.filterIndexed { i, _ -> i != index } }; checkpoint()
    }
    fun dictated(text: String, append: Boolean = true) { val next = if (append) (composerBase() + " " + text).trim() else text; composer(TextFieldValue(next, TextRange(next.length))) }
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
    /** The folder Files opens in, and the one the warm-up lists: the one chosen, else the one last listed (saved with the screens), else the first project's. */
    fun filesFolder(): String = dev.hermes.android.ui.initialFilesFolder(pcFolder().ifBlank { _data.value["files"]?.takeIf { !it.has("_error") }?.optString("_requested_path").orEmpty() }, _data.value["native-projects"])
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
                if (sourceScope == scope) notice("File saved.")
            }
        } catch (e: Throwable) { if (_editor.value.scope == scope && _editor.value.path == editor.path) { _editor.update { it.copy(saving = false, error = "Couldn't confirm the save. Your edit is kept.") }; fail(e) } }
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
        fun speak() { if (readSequence != sequence || sourceScope != source) return; speech?.language = java.util.Locale.getDefault(); if (speech?.speak(text.take(5000), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, sequence.toString()) == android.speech.tts.TextToSpeech.ERROR) { _readingAloud.value = false; notice("Read aloud failed") } }
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
                    private fun finished(id: String?, error: Boolean) { viewModelScope.launch { if (id == readSequence.toString()) { _readingAloud.value = false; if (error) notice("Read aloud failed") } } }
                })
                speechPending?.invoke(); speechPending = null
            } else { speechPending = null; _readingAloud.value = false; speech?.shutdown(); speech = null; notice("Read aloud isn't available on this phone") }
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
    /** R3-F7: the notification being opened; a reconnect already on its way yields to it ([connect]) and runs after it when the open left the app with no connection. */
    private var pushOpening: Job? = null
    private var connectAfterPush: String? = null
    fun handlePush(source: String, event: String, action: String = "") = viewModelScope.launch {
        pushOpening = coroutineContext[Job]
        val startsCall=action in setOf("answer","callback")
        if (startsCall) { if (_incomingBusy.value) return@launch; _incomingBusy.value = true }
        var generation = -1
        var temporaryClient: BeaconClient? = null
        var keptScope: Scope? = null
        var left = false
        try {
            val credentialVersion=app.auth.authorityEpoch.readVersion()
            require(dev.hermes.android.push.validOpaque(source) && dev.hermes.android.push.validOpaque(event)) { "Notification route is invalid" }
            val binding = app.push.binding(source) ?: error("Sign in to the account this notification is for.")
            val origin = trustedOrigin(binding.getString("origin"))
            val owner = binding.getString("owner")
            val notificationScope = Scope(origin, owner, binding.string("profile", "default"), nativeProvider = binding.string("native_provider"))
            require(app.auth.read(origin).authorizesPushSource(notificationScope)) { "Sign in to the account this notification is for." }
            modeTransition?.cancelAndJoin()
            setupProbeJob?.cancel(); ++setupProbeGeneration
            clearPendingVoiceKeyRequests()
            checkpointPhoneSettingsNow()
            clearSettingsProfile()
            keptScope = client?.scope
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
            // The saved handle is empty after a cold start; the prepared chat or the cache still name the Beacon chat. Read before the guard below.
            val beaconChat = preparedBeacon?.takeIf { it.first == c.scope }?.second ?: readBeaconSession(c.scope)
            val assistantChat = route.optBoolean("assistant_chat")
            // A notice of the Beacon chat opens that chat by the id this phone keeps it under, never as a separate chat by the id a turn finished under.
            val id = route.string("session_id").let { if (assistantChat && !beaconChat.isNullOrBlank()) beaconChat else it }
            if (generation != originGeneration) return@launch
            checkpointVisible()
            if (generation != originGeneration) return@launch
            left = true
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
            val screenData = cachedScreens(restoredClient.scope)
            if (generation != originGeneration || client !== restoredClient) {
                return@launch
            }
            _data.value = screenData + screens.composer(restoredClient.scope.session(id))
            phoneSettings.seedSaved()
            _chat.value = ChatState(); _composer.value = TextFieldValue(); _attachments.value = emptyList()
            if (route.string("kind") == "task_result" && id.isNotBlank()) {
                // An email alert is a message in the chat; the tap scrolls to it. Any other task result keeps its card.
                if (route.optBoolean("email_alert")) _alertFocus.value = c.scope.session(id) to route.string("event_id").ifBlank { event }
                else route.string("reason").takeIf { it.isNotBlank() }?.let { storeData("task-result:${c.scope.session(id).key}", json("reason" to it)) }
            }
            // The Beacon screen trusts only the saved handle, which a notification cold start skips (connect() never runs); restore it as connect() does.
            beaconChat?.let { writeBeaconSessionHandle(c.scope, it) }
            val assistantProfile = app.preferences.data.first().let(AppPreferences::read).assistantProfile
            if (generation != originGeneration) return@launch
            // A former assistant profile's callback must keep its own chat, not switch to today's assistant profile.
            val destination = pushDestination(route.string("kind"), id, beaconChat, assistantChat).let { tab ->
                if (tab == "Beacon" && c.auth.profile != assistantProfile) "Chat" else tab
            }
            saved["tab"] = destination; saved["more"] = ""
            _ui.update { it.copy(origin = origin, owner = owner, profile = c.auth.profile, beaconOpening = false, beaconError = "", newChatOpening = false, newChatError = "", initialChatReady = false, query = "", authenticated = true, connected = true, restoringSession = false, bootstrap = bootstrap, tab = destination, morePage = "") }
            prepareStartup()
            if (route.string("kind") == "incoming_call") {
                if (action!="callback") { if (route.string("state") == "pending") app.push.presentInvitation(source, event) else app.push.discardInvitation(source, event) }
                // A call belongs to the chat it was asked from; open that chat so the call screen shows there, not a bar over another chat.
                if (destination == "Beacon") openBeacon() else if (id.isNotBlank()) selectSession(id)
                // The chat is chosen: a reconnect no longer has to wait while the call is set up.
                if (pushOpening === coroutineContext[Job]) { pushOpening = null; connectAfterPush = null }
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
            } else if (destination == "Beacon" && id.isNotBlank() && id != beaconChat) openBeacon() else if (id.isNotBlank()) selectSession(id) else {
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
        finally {
            temporaryClient?.closeNative(); if (startsCall) _incomingBusy.value = false
            if (!left && generation != -1) followKeptChat(generation, keptScope)
            if (pushOpening === coroutineContext[Job]) {
                pushOpening = null
                connectAfterPush?.let { origin -> connectAfterPush = null; if (!left && client == null) connect(origin) }
            }
        }
    }
    override fun onCleared() { clearSettingsProfile(forget = false); if (NativeDeviceRemoval.onForgetScreens === forgetOnDeviceRemoval) NativeDeviceRemoval.onForgetScreens = null; if (dev.hermes.android.updates.PluginInstallHub.controller === pluginInstall) dev.hermes.android.updates.PluginInstallHub.controller = null; pluginInstall.reset(); pluginRepair.reset(); networkCallback?.let { runCatching { app.getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }; botMode.clear(); sessionWatch?.cancel(); stopReadAloud(); speech?.shutdown(); super.onCleared() }
}
