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
    private fun fail(ws: WebSocket) { synchronized(lock) { dead = true }; ws.cancel() }

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
    private fun ended(deliver: (WebSocketListener) -> Unit) {
        val forward = synchronized(lock) { dead = true; listener ?: run { ending = deliver; null } }
        forward?.let(deliver)
    }
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = ended { it.onClosing(webSocket, code, reason) }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended { it.onClosed(webSocket, code, reason) }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = ended { it.onFailure(webSocket, t, response) }

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
    fun close() { synchronized(lock) { dead = true; held.clear() }; socket.close(1000, null); socket.cancel() }
}

/** Two sessions are interchangeable only for the same conversation, voice settings, key and instructions. */
internal fun liveSessionKey(target: Scope, credentials: VoiceSessionCredentials, context: JSONObject): String =
    listOf(target.key, credentials.provider, credentials.model, credentials.voice, credentials.personality, credentials.keyRevision,
        credentials.apiKey.hashCode(), liveSetup(credentials, context)).joinToString("\u0000")

/**
 * Keeps one [LiveSession] and the context it was set up with while any [VoicePrewarm] source is visible. The session closes
 * when every entry leaves, after [warmIdleLimitMs], on another account, profile, conversation, voice, key or context, and on
 * provider errors; a call takes it with [claim].
 */
internal class CallPrewarm(private val app: BeaconApplication, private val ownerScope: () -> Scope?, private val callBusy: () -> Boolean,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val poke = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var warm: LiveSession? = null // guarded by lock
    private var context: PrefetchedContext? = null // guarded by lock
    private var rested: String? = null // a session key that reached its idle limit; guarded by lock
    private var legacyUntil = 0L // the plugin has no session context route: no prewarm until then
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

    /** The warm session of [target] for these voice settings, taken out of the prewarm; null when none matches. */
    fun claim(target: Scope, credentials: VoiceSessionCredentials): LiveSession? = synchronized(lock) {
        val session = warm?.takeIf { !it.dead && it.target == target && it.credentials.sameVoice(credentials) } ?: return@synchronized null
        warm = null; rested = null; armedAt = -1L
        session
    }.also { changed() }

    /** The fresh context of [target], when the prewarm holds one. */
    fun context(target: Scope, ownerName: String): JSONObject? = synchronized(lock) {
        context?.takeIf { it.scope == target && it.ownerName == ownerName && clock() - it.at <= PREWARM_CONTEXT_MAX_AGE_MS }?.value?.let { JSONObject(it.toString()) }
    }

    private fun drop() { synchronized(lock) { warm.also { warm = null } }?.close() }
    /** While true nothing is prepared (live timing measures one cold call with the Call button on screen). */
    @Volatile internal var paused = false
    /** Forgets the warm session and the prefetched context, so the next call starts cold (live timing measures one). */
    internal fun forget() { drop(); synchronized(lock) { context = null; rested = null } }
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
        val read = try {
            withTimeout(8_000) { client.nativeVoiceContext(target.session, ownerName) }.takeIf { contextMatches(it, target) }
        } catch (error: ApiFailure) {
            if (error.status in setOf(404, 405) && error.message.orEmpty().contains("Not Found", ignoreCase = true)) legacyUntil = clock() + 10 * 60_000L
            status = "context http ${error.status}"; null
        } catch (error: CancellationException) { currentCoroutineContext().ensureActive(); status = "context timeout"; null }
        catch (error: Exception) { status = "context ${error.javaClass.simpleName}"; null }
        val next = read?.let { PrefetchedContext(target, ownerName, it, clock()) }
            ?: known?.takeIf { clock() - it.at <= PREWARM_CONTEXT_MAX_AGE_MS }
        synchronized(lock) { context = next }
        return next
    }

    private suspend fun run() {
        while (currentCoroutineContext().isActive) {
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
                if (credentials == null) status = "no voice key" else if (callBusy()) status = "call active" else if (!status.startsWith("context")) status = "no context"
                drop(); withTimeoutOrNull(PREWARM_REFRESH_MS) { poke.receive() }; continue
            }
            val key = liveSessionKey(scoped, credentials, prefetched.value)
            val wait = synchronized(lock) {
                val current = warm
                when {
                    // A call that started meanwhile took the session; nothing new opens beside it.
                    callBusy() -> { warm = null; current?.close(); null }
                    rested == key -> { warm = null; current?.close(); null }
                    // A press that did not become a call: that session's greeting is never heard; a fresh one replaces it.
                    current != null && current.cueRequested && clock() - armedAt > ARMED_LIMIT_MS -> { warm = null; current.close(); 50L }
                    current != null && current.cueRequested -> ARMED_LIMIT_MS + 50
                    current != null && !current.dead && current.key == key -> {
                        val left = current.openedAt + warmIdleLimitMs(credentials.provider) - clock()
                        when {
                            left > 0 -> left
                            // Grok bills every open minute, so an idle session rests until the entry shows again.
                            !warmRenewsWhenIdle(credentials.provider) -> { rested = key; warm = null; current.close(); null }
                            // Token-billed sessions cost nothing idle: a fresh one replaces it, so a tap never meets a cold start.
                            else -> {
                                current.close()
                                warm = LiveSession(scoped, credentials, prefetched.value, providerHttp, clock)
                                warmIdleLimitMs(credentials.provider)
                            }
                        }
                    }
                    else -> {
                        current?.close()
                        warm = LiveSession(scoped, credentials, prefetched.value, providerHttp, clock)
                        warmIdleLimitMs(credentials.provider)
                    }
                }
            }
            status = synchronized(lock) { warm?.let { if (it.dead) "session ended" else if (it.setupAt >= 0) "ready" else "connecting" } ?: if (rested == key) "idle limit" else "no session" }
            withTimeoutOrNull(minOf(wait ?: PREWARM_REFRESH_MS, PREWARM_REFRESH_MS).coerceAtLeast(50)) { poke.receive() }
        }
    }
}

/** Same provider, model, voice, personality and key: a session set up with one serves the other. */
internal fun VoiceSessionCredentials.sameVoice(other: VoiceSessionCredentials) = provider == other.provider && model == other.model &&
    voice == other.voice && personality == other.personality && keyRevision == other.keyRevision && apiKey == other.apiKey
