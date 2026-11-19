package dev.hermes.android.voice

import dev.hermes.android.BeaconApplication
import dev.hermes.android.core.Scope
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.data.VoiceSessionCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import dev.hermes.android.preferences
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * A call entry on screen (the chat's Call button, the assistant overlay) keeps one call ready: the conversation's Hermes
 * context is fresh and a live provider session has already answered setup with it, so a tap only attaches the microphone
 * and asks the live model to greet. Nothing here plays audio or reaches Hermes' agent.
 */
object VoicePrewarm {
    private val visible = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var chat: Pair<BeaconClient, String>? = null
    @Volatile internal var engine: CallPrewarm? = null
        set(value) { field = value; value?.changed() }

    /** [visible] is true while the [source]'s call entry is on screen ("chat", "assist-overlay"), false when it leaves. */
    fun hint(visible: Boolean, source: String) {
        if (source.isBlank()) return
        val changed = if (visible) this.visible.add(source) else this.visible.remove(source)
        // A newly shown entry starts a fresh idle allowance.
        if (visible && changed) engine?.pressed(arm = false) else if (changed) engine?.changed()
    }
    internal fun sources(): Set<String> = visible.toSet()
    /** The open chat's signed-in client and conversation; another source warms the account's Beacon chat. */
    internal fun chat(client: BeaconClient?, session: String) {
        val next = client?.takeIf { session.isNotBlank() }?.let { it to session }
        val previous = chat
        chat = next
        if (previous?.first?.scope != next?.first?.scope || previous?.second != next?.second) engine?.changed()
    }
    internal fun chatTarget(): Pair<BeaconClient, String>? = chat
    /**
     * The Call button is touched: the tap has begun. A warm session is asked to greet now (nothing is heard until the call
     * connects); with nothing warm (first use, or after the idle limit) the session connects now.
     */
    internal fun pressed() { engine?.pressed(arm = true) }
}

/**
 * A call entry's share of [VoicePrewarm]: while [visible] and the screen is resumed, [session]'s call is kept ready; a press
 * connects at once when nothing is warm. Returns the button's interaction source.
 */
@Composable
internal fun rememberCallEntryPrewarm(client: BeaconClient?, session: String, visible: Boolean, source: String = "chat"): MutableInteractionSource {
    val press = remember { MutableInteractionSource() }
    val shown = visible && client != null && session.isNotBlank()
    LifecycleResumeEffect(client, session, shown) {
        if (shown) { if (source == "chat") VoicePrewarm.chat(client, session); VoicePrewarm.hint(true, source) }
        onPauseOrDispose { VoicePrewarm.hint(false, source) }
    }
    LaunchedEffect(press) { press.interactions.collect { if (it is PressInteraction.Press) VoicePrewarm.pressed() } }
    return press
}

/** How long a warm session waits idle for a tap. Grok bills each minute a session is open; Gemini and OpenAI bill tokens. */
internal fun warmIdleLimitMs(provider: String): Long = if (provider == "grok") 60_000L else 240_000L
/** Token-billed sessions cost nothing while idle, so one that reaches its limit is replaced while the entry stays on screen. */
internal fun warmRenewsWhenIdle(provider: String): Boolean = provider != "grok"
/** A visible entry re-reads its context this often; the plugin serves a warm read from its cache. */
internal const val PREWARM_REFRESH_MS = 25_000L
/** A session asked to greet at touch-down that no call takes within this is discarded, never heard. */
internal const val ARMED_LIMIT_MS = 2_000L
/** An entry that leaves and returns within this keeps its warm session. */
internal const val PREWARM_GRACE_MS = 3_000L
/** A context older than this is not used for a call: the tap reads it again. */
internal const val PREWARM_CONTEXT_MAX_AGE_MS = 5 * 60_000L
/** While a call is starting the prewarm looks again this soon: its claim pokes it at once, a start that fails before claiming does not. */
internal const val CALL_START_LOOK_MS = 250L
/**
 * A warm-up sends the call's whole setup in one message, about 43 KB (CHAT-121 follow-up 10), into the phone's one upload queue: everything Hermes' sockets
 * send waits behind it, their pongs too (22 s on a 2 KB/s link, about 86 s on 0.5 KB/s). A provider answers a setup 0.7-1.3 s after its socket opens on a
 * working link (414 connected Gemini calls, 16 Nov 2026); one not answered within this says the link is too slow for a call, and the warm-up rests.
 */
internal const val WARM_SLOW_ANSWER_MS = 6_000L
/** How long the warm-up rests after a setup the provider refused: doubled while it keeps refusing, up to [WARM_REST_MAX_MS]. */
internal const val WARM_REST_FIRST_MS = 2 * 60_000L
internal const val WARM_REST_MAX_MS = 32 * 60_000L
/**
 * How long the warm-up rests after a setup the link carried slowly, unless the phone moves to another network first: a slow link stays slow on its
 * network, and each try there is another whole setup in the queue.
 */
internal const val WARM_SLOW_REST_MS = 30 * 60_000L
/** How soon the prewarm looks again at a setup still on its way. */
internal const val WARM_SETUP_LOOK_MS = 500L

/** The context of one conversation as the plugin returned it, and when (phone elapsed clock). */
internal class PrefetchedContext(val scope: Scope, val ownerName: String, val value: JSONObject, val at: Long)

/** Whether [value] is the call context of [scope] (account, profile and conversation). */
internal fun contextMatches(value: JSONObject, scope: Scope): Boolean =
    scope.session.isNotBlank() && value.optString("profile") == scope.profile && value.optString("selected_session_id") == scope.session

/** The live session's instructions: the whole call brief, so the live model answers the owner itself and greets once when the call starts. */
internal fun liveInstruction(context: JSONObject, personality: String = "", model: String = ""): String =
    GeminiWire.setup(model, "", null, JSONObject(context.toString()).put("personality_instruction", personality))
        .getJSONObject("setup").getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")

/**
 * The live session's setup in the Gemini shape (OpenAI's is converted here, Grok's by its wire adapter): the full call brief
 * and tool set. [resume] continues a Gemini session; without it [recent] is what was said so far in this call.
 */
internal fun liveSetup(credentials: VoiceSessionCredentials, context: JSONObject, resume: String? = null, recent: String = ""): JSONObject {
    val gemini = GeminiWire.setup(credentials.model, credentials.voice, resume, JSONObject(context.toString()).apply {
        put("personality_instruction", credentials.personalityInstruction)
        if (resume == null && recent.isNotBlank()) put("live_context_summary", recent.takeLast(6000))
    })
    return if (credentials.provider == "openai") OpenAiWire.setup(gemini) else gemini
}

/** The note that asks the live model to greet; it carries the owner's local time from the profile's zone. */
internal fun liveStartCue(context: JSONObject, now: Long = System.currentTimeMillis()): String {
    val zone = runCatching { java.time.ZoneId.of(context.optString("zone")) }.getOrNull() ?: java.time.ZoneId.systemDefault()
    val time = java.time.Instant.ofEpochMilli(now).atZone(zone)
    val day = time.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
    val clock = String.format(java.util.Locale.ROOT, "%02d:%02d", time.hour, time.minute)
    return "[Call-start note from the app, not the owner: the call is connected. The owner's local time is $day $clock. Greet the owner now.]"
}

/** The provider frames that send [cue] as one text turn and ask for a spoken answer. */
internal fun liveCueFrames(provider: String, cue: String): List<JSONObject> = if (provider == "gemini")
    listOf(JSONObject().put("clientContent", JSONObject().put("turns", JSONArray().put(JSONObject().put("role", "user")
        .put("parts", JSONArray().put(JSONObject().put("text", cue))))).put("turnComplete", true)))
else listOf(GrokWire.text(cue), JSONObject().put("type", "response.create"))

internal fun liveProviderRequest(credentials: VoiceSessionCredentials): VoiceProviderRequest = when (credentials.provider) {
    "gemini" -> GeminiWire.request(credentials.apiKey)
    "grok" -> GrokWire.request(credentials.apiKey, credentials.model)
    else -> OpenAiWire.request(credentials.apiKey, credentials.model)
}

/** True when [frame] (a raw provider frame) answers setup. */
internal fun liveSetupAnswered(provider: String, frame: JSONObject): Boolean =
    if (provider == "gemini") frame.has("setupComplete") else frame.optString("type") == "session.updated"

/**
 * One live provider session opened ahead of a call, with [context] in its setup. Until a call [attach]es, every frame it
 * receives is held (nothing is presented); the call then reads them in order, as if it had opened the socket itself.
 */
internal class LiveSession(val target: Scope, val credentials: VoiceSessionCredentials, val context: JSONObject,
    sockets: WebSocket.Factory, private val clock: () -> Long) : WebSocketListener() {
    val key = liveSessionKey(target, credentials, context)
    val openedAt = clock()
    @Volatile var socketOpenAt = -1L; private set
    @Volatile var setupAt = -1L; private set
    @Volatile var cueSentAt = -1L; private set
    @Volatile var dead = false; private set
    /** When this session ended before its setup was answered (the provider closed it, or the connection failed); -1 while it has not. */
    @Volatile var endedBeforeSetupAt = -1L; private set
    /** The provider itself closed it before answering its setup (a close code): it refused the setup. */
    @Volatile var refused = false; private set
    /** This side closed it (replaced, dropped, a send that failed): its end says nothing of the provider or the link. */
    @Volatile var closedHere = false; private set
    /** The call asked this session for its greeting at the tap. */
    @Volatile var cueRequested = false; private set
    private val lock = Any()
    private var openResponse: Response? = null
    private val held = ArrayList<String>()
    private var heldChars = 0
    private var listener: WebSocketListener? = null
    private var ending: ((WebSocketListener) -> Unit)? = null
    private var cue: String? = null
    private val socket: WebSocket = sockets.newWebSocket(liveProviderRequest(credentials).rawRequest(), this)

    // Callbacks use the socket they were given: the first can arrive before the constructor stored it.
    private fun send(ws: WebSocket, frames: List<JSONObject>): Boolean = frames.all { ws.send(it.toString()) }
    private fun setupFrames(): List<JSONObject> {
        val setup = liveSetup(credentials, context)
        return if (credentials.provider == "grok") GrokWire().outbound(setup) else listOf(setup)
    }
    private fun sendCue(ws: WebSocket, text: String) {
        if (send(ws, liveCueFrames(credentials.provider, text))) cueSentAt = clock() else fail(ws)
    }
    private fun fail(ws: WebSocket) { synchronized(lock) { closedHere = true; dead = true }; ws.cancel() }
    /** How long the provider took to answer the setup (setupComplete, or ending the session first) from the socket's opening; -1 while it has not. */
    fun answerMs(): Long {
        val answer = if (setupAt >= 0) setupAt else endedBeforeSetupAt
        return if (answer < 0) -1L else answer - (if (socketOpenAt >= 0) socketOpenAt else openedAt)
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socketOpenAt = clock()
        if (!send(webSocket, setupFrames())) { fail(webSocket); return }
        val forward = synchronized(lock) { listener ?: run { openResponse = response; null } }
        forward?.onOpen(webSocket, response)
    }
    override fun onMessage(webSocket: WebSocket, text: String) {
        val forward = synchronized(lock) {
            if (setupAt < 0 && runCatching { liveSetupAnswered(credentials.provider, JSONObject(text)) }.getOrDefault(false)) {
                setupAt = clock(); cue?.let { cue = null; sendCue(webSocket, it) }
            }
            listener ?: run {
                heldChars += text.length
                // A session nobody claims for this long has nothing worth holding; it is replaced, never presented.
                if (heldChars > 4_000_000) { dead = true; webSocket.cancel() } else held += text
                null
            }
        }
        forward?.onMessage(webSocket, text)
    }
    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = onMessage(webSocket, bytes.utf8())
    private fun ended(byProvider: Boolean, deliver: (WebSocketListener) -> Unit) {
        val forward = synchronized(lock) {
            if (!closedHere && setupAt < 0 && endedBeforeSetupAt < 0) { endedBeforeSetupAt = clock(); refused = byProvider }
            dead = true; listener ?: run { ending = deliver; null }
        }
        forward?.let(deliver)
    }
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = ended(true) { it.onClosing(webSocket, code, reason) }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(true) { it.onClosed(webSocket, code, reason) }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = ended(false) { it.onFailure(webSocket, t, response) }

    /** Sends the start-of-call note now, or as soon as the provider has answered setup. */
    fun startTurn(text: String) = synchronized(lock) { cueRequested = true; if (setupAt >= 0) sendCue(socket, text) else cue = text }
    /** The call takes the socket: held frames reach [to] first, in order, then live ones. */
    fun attach(to: WebSocketListener): WebSocket {
        synchronized(lock) {
            listener = to
            openResponse?.let { openResponse = null; to.onOpen(socket, it) }
            held.forEach { to.onMessage(socket, it) }
            held.clear(); heldChars = 0
            ending?.let { ending = null; it(to) }
        }
        return socket
    }
    fun close() { synchronized(lock) { closedHere = true; dead = true; held.clear() }; socket.close(1000, null); socket.cancel() }
}

/** Two sessions are interchangeable only for the same conversation, voice settings, key and instructions. */
internal fun liveSessionKey(target: Scope, credentials: VoiceSessionCredentials, context: JSONObject): String =
    listOf(target.key, credentials.provider, credentials.model, credentials.voice, credentials.personality, credentials.keyRevision,
        credentials.apiKey.hashCode(), liveSetup(credentials, context)).joinToString("\u0000")

/**
 * Keeps one [LiveSession] and the context it was set up with while any [VoicePrewarm] source is visible. The session closes
 * when every entry leaves, after [warmIdleLimitMs], on another account, profile, conversation, voice, key or context, and on
 * provider errors; a call takes it with [claim]. A setup the provider refused, or the link carried slowly, opens no other for a while ([learn]).
 */
internal class CallPrewarm(private val app: BeaconApplication, private val ownerScope: () -> Scope?, private val callBusy: () -> Boolean,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
    /** Each context read from Hermes, as it arrived (it says which call contract that Hermes speaks). */
    private val contextRead: (BeaconClient, JSONObject) -> Unit = { _, _ -> },
    /** A call has begun and not yet taken its session (BeaconCallService between `starting` and its claim). */
    private val callStarting: () -> Boolean = { false },
    /**
     * The phone's own networks now, under any VPN (a slow link's rest ends when they change). A VPN (the owner's Tailscale) is the default network and
     * keeps its identity when Wi-Fi and mobile data swap under it, so the networks that are not a VPN are what say the phone moved.
     */
    private val network: () -> String? = { runCatching {
        val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return@runCatching null
        @Suppress("DEPRECATION") connectivity.allNetworks.filter { network -> connectivity.getNetworkCapabilities(network)?.let {
            it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } == true }.map { it.toString() }.sorted().joinToString(",")
    }.getOrNull() }) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val poke = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var warm: LiveSession? = null // guarded by lock
    private var context: PrefetchedContext? = null // guarded by lock
    private var rested: String? = null // a session key that reached its idle limit; guarded by lock
    private var legacyUntil = 0L // the plugin has no session context route: no prewarm until then
    // The warm-up's rest (guarded by lock): until when, how long it was, for which voice, and on which network a slow link's rest began (null: a refusal's).
    private var restUntil = 0L
    private var restMs = 0L
    private var restVoice = ""
    private var restSlowOn: String? = null
    private var learnedFrom: LiveSession? = null
    // A dropped session whose setup was still on its way: kept, never presented, until what it shows of the link is learned (guarded by lock).
    private var draining: LiveSession? = null
    private var loop: Job? = null
    private var overlayClient: BeaconClient? = null
    /** What the prewarm is doing, content-free (logged on change; live timing prints it). */
    @Volatile internal var status = "idle"
        private set(value) { if (field != value) { field = value; runCatching { android.util.Log.i("BeaconPrewarm", value) } } }
    // Deliberately separate from authenticated Hermes HTTP, like the call's own provider socket.
    private val providerHttp by lazy { OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .pingInterval(20, TimeUnit.SECONDS).build() }

    fun changed() {
        poke.trySend(Unit)
        synchronized(lock) { if (loop?.isActive != true) loop = scope.launch { run() } }
    }
    @Volatile private var armedAt = -1L
    fun pressed(arm: Boolean) {
        synchronized(lock) {
            rested = null
            if (arm) warm?.takeIf { !it.dead && it.setupAt >= 0 && !it.cueRequested && !callBusy() }?.let { it.startTurn(liveStartCue(it.context)); armedAt = clock() }
        }
        changed()
    }

    /**
     * The call that starts takes the warm session of [target] for these voice settings, ready or still connecting. One it does not take
     * (another chat or voice, or [target] null: an incoming call, a locked phone) is closed here, so a call never has a second provider
     * session beside its own.
     */
    fun claim(target: Scope?, credentials: VoiceSessionCredentials): LiveSession? = synchronized(lock) {
        val current = warm
        warm = null; rested = null; armedAt = -1L
        current?.takeIf { !it.dead && target != null && it.target == target && it.credentials.sameVoice(credentials) }
            ?: run { current?.close(); null }
    }.also { changed() }

    /**
     * A call has begun and its claim is still to come: the warm session is left exactly as it is for it, and nothing new opens. Reads
     * busy first: a call that is busy at the first read and not starting at the second has finished its start (taken or closed the
     * session at its claim), and any session found after that sits beside a live call.
     * Device round 1 (14 Nov 2026), leg C and the baseline: the loop closed the session in this window and the call connected again.
     */
    private fun callTaking(): Boolean = callBusy() && callStarting()

    /** The fresh context of [target], when the prewarm holds one. */
    fun context(target: Scope, ownerName: String): JSONObject? = synchronized(lock) {
        context?.takeIf { it.scope == target && it.ownerName == ownerName && clock() - it.at <= PREWARM_CONTEXT_MAX_AGE_MS }?.value?.let { JSONObject(it.toString()) }
    }

    /**
     * Closes the warm session (no entry, no key, no context), unless a starting call is about to take it. What it showed is learned first. One whose
     * setup is still on its way is kept, never presented, until it is answered or [WARM_SLOW_ANSWER_MS] has passed, and learned from then: the Call
     * button leaves when a message is typed, often seconds after a warm-up went, and a setup the link carries slowly must still rest the warm-up
     * (CHAT-121 follow-up 10, window 3: dropped unlearned, the button's return sent another whole setup into a slow link).
     */
    internal fun drop() {
        var settle = false
        synchronized(lock) {
            if (callTaking()) return@synchronized null
            val dropped = warm ?: return@synchronized null
            warm = null
            learn(dropped, dropped.credentials, clock())
            if (learnedFrom === dropped || dropped.dead || dropped.socketOpenAt < 0 && dropped.endedBeforeSetupAt >= 0) dropped
            else { settle = true; draining.also { draining = dropped } }
        }?.close()
        if (settle) scope.launch { delay(WARM_SLOW_ANSWER_MS + 50); synchronized(lock) { settleDraining(clock()) }?.close() }
    }
    /** Learns from the dropped session still on its way once it has shown something; returns it, to be closed, once it has (guarded by lock). */
    private fun settleDraining(now: Long): LiveSession? {
        val session = draining ?: return null
        learn(session, session.credentials, now)
        val shown = learnedFrom === session || session.dead || session.setupAt >= 0 || session.socketOpenAt >= 0 && now - session.socketOpenAt > WARM_SLOW_ANSWER_MS
        return if (shown) session.also { draining = null } else null
    }
    /** While true nothing is prepared (live timing measures one cold call with the Call button on screen). */
    @Volatile internal var paused = false
    /** Forgets the warm session and the prefetched context, so the next call starts cold (live timing measures one). */
    internal fun forget() {
        synchronized(lock) { listOfNotNull(warm, draining).also { warm = null; draining = null } }.forEach { it.close() }
        synchronized(lock) { context = null; rested = null }
    }
    /** Whether a session for [target] has answered setup and waits for a tap. */
    internal fun ready(target: Scope): Boolean = synchronized(lock) { warm?.let { !it.dead && it.target == target && it.setupAt >= 0 } == true }

    private suspend fun target(): Pair<BeaconClient, String>? {
        val sources = VoicePrewarm.sources()
        val owner = ownerScope()?.copy(session = "") ?: return null
        if ("chat" in sources) VoicePrewarm.chatTarget()?.takeIf { it.first.scope.copy(session = "") == owner }?.let { return it }
        if (sources.none { it != "chat" }) return null
        // The assistant overlay calls the account's Beacon chat.
        val session = runCatching { app.cache.read(owner, "beacon-session") }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val client = VoicePrewarm.chatTarget()?.first?.takeIf { it.scope.copy(session = "") == owner }
            ?: overlayClient?.takeIf { it.scope == owner }
            ?: runCatching { app.auth.read(owner.origin).takeIf { it.scope() == owner }?.let { BeaconClient(it, app.auth, persistContext = false) } }
                .getOrNull()?.also { overlayClient = it }
            ?: return null
        return client to session
    }

    private suspend fun ownerName(): String = try {
        app.preferences.data.first().let(dev.hermes.android.data.AppPreferences::read).ownerName
    } catch (error: CancellationException) { throw error } catch (_: Exception) { "" }

    private suspend fun fresh(client: BeaconClient, target: Scope, ownerName: String): PrefetchedContext? {
        val known = synchronized(lock) { context?.takeIf { it.scope == target && it.ownerName == ownerName } }
        if (known != null && clock() - known.at < PREWARM_REFRESH_MS) return known
        if (clock() < legacyUntil) return null
        var refused = false
        val read = try {
            withTimeout(8_000) { client.nativeVoiceContext(target.session, ownerName) }.takeIf { contextMatches(it, target) }
        } catch (error: ApiFailure) {
            if (error.status in setOf(404, 405) && error.message.orEmpty().contains("Not Found", ignoreCase = true)) legacyUntil = clock() + 10 * 60_000L
            else if (error.plugin && error.status == 404) refused = true
            status = "context http ${error.status}"; null
        } catch (error: CancellationException) { currentCoroutineContext().ensureActive(); status = "context timeout"; null }
        catch (error: Exception) { status = "context ${error.javaClass.simpleName}"; null }
        // The plugin no longer has this chat for the account (deleted elsewhere): what was read before stops vouching for it, so a call's
        // check asks Hermes instead of taking the old context as held (fix4, device round 3 F1).
        if (refused) { synchronized(lock) { if (context?.scope == target) context = null }; return null }
        read?.let { runCatching { contextRead(client, it) } }
        return read?.let { PrefetchedContext(target, ownerName, it, clock()) }
            ?: known?.takeIf { clock() - it.at <= PREWARM_CONTEXT_MAX_AGE_MS }
    }

    /**
     * What [session] showed, once. Each warm-up is a whole setup in the phone's one upload queue (CHAT-121 follow-up 10: chat-121-10 sent one at every look,
     * 25 s apart, while the provider refused them), so: a setup answered more than [WARM_SLOW_ANSWER_MS] after its socket opened, or not answered by then,
     * says the link is too slow for a call, and nothing new opens for [WARM_SLOW_REST_MS] or until the phone is on another network; a setup the provider
     * refused in time (it closed the session) rests it for [WARM_REST_FIRST_MS], doubled while it keeps refusing; a setup answered in time ends any rest.
     * A socket that never opened sent no setup, and a connection lost (not closed by the provider) in time or one closed here says neither: no rest.
     */
    private fun learn(session: LiveSession, credentials: VoiceSessionCredentials, now: Long) {
        if (session === learnedFrom || session.socketOpenAt < 0) return
        val answered = session.answerMs()
        if (answered < 0 && (session.closedHere || now - session.socketOpenAt <= WARM_SLOW_ANSWER_MS)) return
        val slow = answered !in 0..WARM_SLOW_ANSWER_MS
        if (!slow && session.setupAt < 0 && !session.refused) return
        learnedFrom = session
        if (!slow && session.setupAt >= 0) { restUntil = 0; restMs = 0; restSlowOn = null; return }
        restMs = if (slow) WARM_SLOW_REST_MS else (restMs * 2).coerceIn(WARM_REST_FIRST_MS, WARM_REST_MAX_MS)
        restUntil = now + restMs
        restVoice = voiceOf(credentials)
        restSlowOn = if (slow) network().orEmpty() else null
    }
    private fun voiceOf(credentials: VoiceSessionCredentials) = listOf(credentials.provider, credentials.model, credentials.keyRevision).joinToString("|")
    /** Whether the warm-up rests now; another voice or key, or (after a slow link) another network, ends the rest. */
    private fun resting(credentials: VoiceSessionCredentials, now: Long): Boolean {
        if (now >= restUntil) return false
        if (restVoice != voiceOf(credentials) || restSlowOn != null && network().orEmpty() != restSlowOn) { restUntil = 0; restMs = 0; restSlowOn = null; return false }
        return true
    }

    /**
     * Publishes [prefetched] and the live session set up with it in one step. A call that finds this conversation's context also
     * finds its session, ready or still connecting (the call carries it on): it never meets the context alone and opens a second
     * connection beside the one being prepared (fault suite, 14 Nov 2026: a tap 0.36 s after a chat switch did, 2.2 s instead of
     * 1.8 s to the first audio). Returns how long to wait before looking again; null for the refresh interval.
     */
    internal fun prepare(scoped: Scope, credentials: VoiceSessionCredentials, prefetched: PrefetchedContext, sockets: WebSocket.Factory = providerHttp): Long? {
        val key = liveSessionKey(scoped, credentials, prefetched.value)
        return synchronized(lock) {
            // Busy is read once, then starting (see callTaking): a starting call takes what is here next, so its session and context stay.
            val busy = callBusy()
            if (busy && callStarting()) { status = "call starting"; return@synchronized CALL_START_LOOK_MS }
            val now = clock()
            settleDraining(now)?.close()
            val current = warm
            current?.let { learn(it, credentials, now) }
            val resting = resting(credentials, now)
            // This chat's newer context does not replace a setup still on its way: a second whole setup would wait in the same upload queue behind it.
            // The context it was set up with stays published with it, so a call meets the two together.
            if (!busy && !resting && current != null && !current.dead && current.setupAt < 0 && !current.cueRequested && current.target == scoped &&
                current.key != key && rested != key) { status = "connecting"; return@synchronized WARM_SETUP_LOOK_MS }
            context = prefetched
            // A new session, unless the warm-up rests; the next look comes in time to learn a slow setup.
            fun open(): Long = if (resting) { warm = null; maxOf(50L, restUntil - now) }
                else { warm = LiveSession(scoped, credentials, prefetched.value, sockets, clock); WARM_SLOW_ANSWER_MS + 50 }
            val wait = when {
                // A live call took the session or closed it at its claim: nothing opens beside it.
                busy -> { warm = null; current?.close(); null }
                rested == key -> { warm = null; current?.close(); null }
                // A press that did not become a call: that session's greeting is never heard; a fresh one replaces it.
                current != null && current.cueRequested && clock() - armedAt > ARMED_LIMIT_MS -> { warm = null; current.close(); 50L }
                current != null && current.cueRequested -> ARMED_LIMIT_MS + 50
                current != null && !current.dead && current.key == key -> {
                    val left = current.openedAt + warmIdleLimitMs(credentials.provider) - now
                    when {
                        left > 0 -> if (current.setupAt < 0) minOf(left, WARM_SLOW_ANSWER_MS + 50) else left
                        // Grok bills every open minute, so an idle session rests until the entry shows again.
                        !warmRenewsWhenIdle(credentials.provider) -> { rested = key; warm = null; current.close(); null }
                        // Token-billed sessions cost nothing idle: a fresh one replaces it, so a tap never meets a cold start.
                        else -> { current.close(); open() }
                    }
                }
                else -> { current?.close(); open() }
            }
            status = warm?.let { if (it.dead) "session ended" else if (it.setupAt >= 0) "ready" else "connecting" }
                ?: if (rested == key) "idle limit" else if (resting) (if (restSlowOn != null) "resting: slow link" else "resting: setup refused") else "no session"
            wait
        }
    }

    private suspend fun run() {
        while (currentCoroutineContext().isActive) {
            // A starting call takes the session next (its claim pokes this loop); nothing here touches it meanwhile.
            if (callTaking()) { status = "call starting"; withTimeoutOrNull(CALL_START_LOOK_MS) { poke.receive() }; continue }
            val target = if (VoicePrewarm.sources().isEmpty() || paused) null else target()
            if (target == null) {
                status = if (VoicePrewarm.sources().isEmpty()) "no entry" else "no conversation"
                // An entry that comes straight back (the composer emptied again, a quick tab switch) keeps its session.
                if (VoicePrewarm.sources().isEmpty() && withTimeoutOrNull(PREWARM_GRACE_MS) { poke.receive() } != null) continue
                drop()
                if (VoicePrewarm.sources().isEmpty()) { synchronized(lock) { loop = null }; return }
                withTimeoutOrNull(PREWARM_REFRESH_MS) { poke.receive() }; continue
            }
            val (client, session) = target
            val scoped = client.scope.session(session)
            val credentials = withContext(Dispatchers.IO) { runCatching { VoiceKeyStore(app).session() }.getOrNull() }
            val prefetched = if (credentials == null) null else fresh(client, scoped, ownerName())
            // A call in progress owns the provider; the next one is prepared after it.
            if (credentials == null || prefetched == null || callBusy()) {
                if (callTaking()) continue
                if (credentials != null) synchronized(lock) { context = prefetched }
                if (credentials == null) status = "no voice key" else if (callBusy()) status = "call active" else if (!status.startsWith("context")) status = "no context"
                drop(); withTimeoutOrNull(PREWARM_REFRESH_MS) { poke.receive() }; continue
            }
            val wait = prepare(scoped, credentials, prefetched)
            withTimeoutOrNull(minOf(wait ?: PREWARM_REFRESH_MS, PREWARM_REFRESH_MS).coerceAtLeast(50)) { poke.receive() }
        }
    }
}

/** Same provider, model, voice, personality and key: a session set up with one serves the other. */
internal fun VoiceSessionCredentials.sameVoice(other: VoiceSessionCredentials) = provider == other.provider && model == other.model &&
    voice == other.voice && personality == other.personality && keyRevision == other.keyRevision && apiKey == other.apiKey
