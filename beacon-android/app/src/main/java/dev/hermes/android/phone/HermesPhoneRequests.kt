package dev.hermes.android.phone

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.R
import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.core.trustedOrigin
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.push.boundedNotificationText
import dev.hermes.android.push.ensureActivityChannels
import dev.hermes.android.push.promptNotificationChannel
import dev.hermes.android.push.serviceText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/*
 * Hermes asks this phone to use phone access (calendar, alarms, contacts, calls, messages). The plugin queues each request and the
 * phone decides: a write or action always waits for the owner's tap on a card that shows what it will do, a read waits too unless
 * "Ask before Hermes reads" is off, and nothing runs while Hermes access or that capability is switched off. A request runs at most
 * once and never after its two minutes; the result goes back only to the plugin that asked.
 */

internal const val PHONE_REQUEST_SECONDS = 120L
internal const val PHONE_OPEN_REQUESTS = 8
private const val PHONE_REQUEST_BYTES = 8192
private const val PHONE_RESULT_BYTES = 16000
private const val HANDLED_KEEP_MILLIS = 3_600_000L
private const val HANDLED_LIMIT = 200
private const val PHONE_PATH = "/api/plugins/beacon/mobile/phone"
private val requestIdPattern = Regex("[0-9a-f]{32}")

/** One tool call Hermes asked of this phone. [deadline] is on the phone's own clock (elapsed millis), set when the request was read. */
internal data class HermesPhoneRequest(val id: String, val tool: String, val args: JSONObject, val deadline: Long) {
    fun secondsLeft(now: Long): Long = ((deadline - now) / 1000).coerceAtLeast(0)

    companion object {
        /** The request in [row], or null when anything about it is off: the phone never trusts what the plugin already checked. */
        fun parse(row: JSONObject, now: Long, known: Set<String>): HermesPhoneRequest? {
            val id = row.optString("id")
            val tool = row.optString("tool")
            val args = row.optJSONObject("args")
            val seconds = row.optLong("expires_in_seconds", -1)
            if (!requestIdPattern.matches(id) || tool !in known || args == null || args.toString().length > PHONE_REQUEST_BYTES) return null
            if (seconds !in 1..PHONE_REQUEST_SECONDS) return null
            return HermesPhoneRequest(id, tool, args, now + seconds * 1000)
        }

        fun parseAll(page: JSONObject, now: Long, known: Set<String>): List<HermesPhoneRequest> {
            val rows = page.optJSONArray("requests") ?: return emptyList()
            return (0 until minOf(rows.length(), PHONE_OPEN_REQUESTS)).mapNotNull { rows.optJSONObject(it) }
                .mapNotNull { parse(it, now, known) }.distinctBy { it.id }
        }
    }
}

/** What this phone's phone-access tools offer, so the engine can be tested without Android. */
internal interface PhoneToolPort {
    val names: Set<String>
    fun isWrite(name: String): Boolean
    fun enabled(): Set<String>
    fun hermesAccess(): Boolean
    fun askBeforeReads(): Boolean
    /** The card for a request, with its names looked up, exactly as a live call builds it; throws [PhoneFailure] when the phone would refuse the request. */
    suspend fun prepare(name: String, args: JSONObject): PhonePrepared
    suspend fun run(name: String, args: JSONObject, decision: PhoneDecision?): JSONObject
    /** Runs exactly what [prepared], the card the owner tapped on, showed. */
    suspend fun runPrepared(prepared: PhonePrepared, decision: PhoneDecision?): JSONObject
    /** Puts what happened to a request the phone never ran (said no, expired, switched off, refused) in the activity list. */
    fun record(name: String, line: PhoneLine, outcome: String)
    /** The line for a request that has no card. */
    fun plainLine(name: String): PhoneLine
}

internal class RealPhoneTools(private val context: Context) : PhoneToolPort {
    override val names: Set<String> get() = PhoneTools.names
    override fun isWrite(name: String) = PhoneTools.isWrite(name)
    override fun enabled() = PhoneTools.enabledTools(context)
    override fun hermesAccess() = PhoneTools.hermesAccess(context)
    override fun askBeforeReads() = PhoneTools.askBeforeHermesReads(context)
    override suspend fun prepare(name: String, args: JSONObject) = PhoneTools.prepare(context, name, args, PhoneOrigin.Hermes)
    override suspend fun run(name: String, args: JSONObject, decision: PhoneDecision?) =
        PhoneTools.run(context, name, args, PhoneOrigin.Hermes, decision)
    override suspend fun runPrepared(prepared: PhonePrepared, decision: PhoneDecision?) =
        PhoneTools.runPrepared(context, prepared, PhoneOrigin.Hermes, decision)
    override fun record(name: String, line: PhoneLine, outcome: String) = PhoneTools.record(context, name, line, PhoneOrigin.Hermes, outcome)
    override fun plainLine(name: String) = PhoneTools.plainLine(name)
}

/** The request is no longer open: it expired, or another phone already took it. */
internal class PhoneRequestGone(val expired: Boolean) : Exception()

/** The plugin's phone endpoints for one signed-in profile. */
internal interface PhoneLink {
    suspend fun pending(): JSONObject
    /** Approving claims the request for this phone; throws [PhoneRequestGone] when it can no longer be claimed or denied. */
    suspend fun decide(id: String, approved: Boolean)
    suspend fun result(id: String, result: JSONObject)
    suspend fun state(state: JSONObject)
}

private class ClientLink(private val client: BeaconClient) : PhoneLink {
    override suspend fun pending() = client.phoneRequests()
    override suspend fun decide(id: String, approved: Boolean) {
        try { client.phoneDecision(id, approved) }
        catch (e: ApiFailure) {
            if (e.plugin && e.status in setOf(404, 409)) throw PhoneRequestGone(expired = e.message == "expired")
            throw e
        }
    }
    override suspend fun result(id: String, result: JSONObject) { client.phoneResult(id, result) }
    override suspend fun state(state: JSONObject) { client.phoneState(state) }
}

/** The ids this phone already acted on, kept for an hour: a request is run once even if the app restarts between asking and answering. */
internal class HandledRequests(
    private val seen: MutableMap<String, Long>,
    private val save: (Map<String, Long>) -> Boolean,
    private val clock: () -> Long,
) {
    @Synchronized fun has(id: String): Boolean = id in seen

    /** Gives [id] back after the plugin could not be reached, so the owner can tap again; the plugin itself still claims a request once. */
    @Synchronized fun forget(id: String) { if (seen.remove(id) != null) save(seen) }

    /** True the first time an id is taken, and only if that could be recorded; a request that cannot be recorded is never run. */
    @Synchronized fun take(id: String): Boolean {
        val now = clock()
        seen.entries.removeAll { now - it.value > HANDLED_KEEP_MILLIS || it.value > now }
        if (id in seen) return false
        while (seen.size >= HANDLED_LIMIT) seen.remove(seen.minByOrNull { it.value }?.key ?: break)
        seen[id] = now
        if (!save(seen)) { seen.remove(id); return false }
        return true
    }
}

/** [Retry]: the plugin could not be reached, so nothing was claimed and the card stays for another tap. */
internal enum class PhoneOutcome { Ran, Denied, Off, Expired, Gone, Locked, Handled, Retry }

/**
 * Answers what Hermes asks of this phone. Everything that touches the phone's data or storage (the card's lookups, the handled ids, the
 * activity list) runs on [io], never on the thread of the screen that asked.
 */
internal class HermesPhoneEngine(
    private val tools: PhoneToolPort,
    private val link: PhoneLink,
    private val handled: HandledRequests,
    private val now: () -> Long,
    private val unlocked: () -> Boolean,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * A request waiting for the owner's tap, with what it will do ([prepared], the card a live call shows for the same request). The
     * tap runs exactly [prepared], never the request's raw arguments again.
     */
    data class Card(val request: HermesPhoneRequest, val prepared: PhonePrepared) {
        val card: PhoneCard get() = prepared.card
    }

    /** The headline of each carded request, for the activity list when it ends without running. */
    private val lines = ConcurrentHashMap<String, PhoneLine>()
    private fun lineOf(request: HermesPhoneRequest) = lines[request.id] ?: tools.plainLine(request.tool)

    /**
     * Reads what Hermes is waiting on. A request for something switched off is answered "off"; a request the phone would refuse is
     * answered with that error and gets no card; a read runs at once only when "Ask before Hermes reads" is off; everything else comes
     * back as a card for the owner to allow or deny. [have] gives the card already made for a request, so it is looked up once.
     */
    suspend fun refresh(have: (String) -> PhonePrepared? = { null }): List<Card> = withContext(io) {
        val cards = mutableListOf<Card>()
        for (request in HermesPhoneRequest.parseAll(link.pending(), now(), tools.names)) {
            try {
                when {
                    !tools.hermesAccess() || request.tool !in tools.enabled() -> answerOff(request)
                    !tools.isWrite(request.tool) && !tools.askBeforeReads() -> execute(request, null)
                    handled.has(request.id) -> Unit
                    else -> (have(request.id) ?: prepare(request))?.let { prepared ->
                        lines[request.id] = prepared.card.lines.first()
                        cards += Card(request, prepared)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* One request that cannot be read or answered must not hide the others; it expires on its own. */ }
        }
        cards
    }

    /** What [request] will do, or null after answering it with the error the phone gave. */
    private suspend fun prepare(request: HermesPhoneRequest): PhonePrepared? = try { tools.prepare(request.tool, request.args) }
        catch (refused: PhoneFailure) { answer(request, refused.toJson(), PhoneOutcome.Gone, refused.logged); null }

    /** The owner tapped Allow on [card]. Needs the phone unlocked; a request that expired or is gone is never run. */
    suspend fun approve(card: Card): PhoneOutcome = withContext(io) {
        if (!unlocked()) PhoneOutcome.Locked else execute(card.request, PhoneDecision(true), card.prepared)
    }

    suspend fun deny(request: HermesPhoneRequest): PhoneOutcome = withContext(io) {
        claim(request, false) ?: run { tools.record(request.tool, lineOf(request), "denied"); PhoneOutcome.Denied }
    }

    /** A card ended without an answer because its time ran out: the activity list says so, once. */
    suspend fun expired(request: HermesPhoneRequest) {
        withContext(io) { if (!handled.has(request.id)) tools.record(request.tool, lineOf(request), "expired") }
        lines.remove(request.id)
    }

    /**
     * Takes [request] for this phone and tells the plugin; null when that worked, else the outcome to give. When the plugin cannot be
     * reached the request is given back, so the owner can tap again: the plugin lets a request be claimed only once.
     */
    private suspend fun claim(request: HermesPhoneRequest, approved: Boolean): PhoneOutcome? {
        if (!handled.take(request.id)) return PhoneOutcome.Handled
        try { link.decide(request.id, approved); return null }
        catch (e: PhoneRequestGone) {
            // The plugin ran out of time for it first: the id stays taken, so this is the one place that says so in the activity list.
            if (e.expired) tools.record(request.tool, lineOf(request), "expired")
            return if (e.expired) PhoneOutcome.Expired else PhoneOutcome.Gone
        }
        catch (e: CancellationException) { handled.forget(request.id); throw e }
        catch (_: Exception) { handled.forget(request.id); return PhoneOutcome.Retry }
    }

    /** [prepared] is the card the owner tapped on; a read that runs without a tap has none and runs its arguments. */
    private suspend fun execute(request: HermesPhoneRequest, decision: PhoneDecision?, prepared: PhonePrepared? = null): PhoneOutcome {
        if (now() >= request.deadline) return PhoneOutcome.Expired
        // The switches are read again at the moment of running: the owner may have turned one off since the card appeared.
        if (!tools.hermesAccess() || request.tool !in tools.enabled()) return answerOff(request)
        claim(request, true)?.let { return it }
        val result = try { if (prepared != null) tools.runPrepared(prepared, decision) else tools.run(request.tool, request.args, decision) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failure("unavailable", "The phone could not do that.") }
        report(request.id, result)
        return PhoneOutcome.Ran
    }

    private suspend fun answerOff(request: HermesPhoneRequest): PhoneOutcome =
        answer(request, failure("off", "This is switched off in Beacon on the phone."), PhoneOutcome.Off, "off")

    /** Claims [request], puts [logged] in the activity list and sends Hermes [error]; the claim's own outcome when that fails. */
    private suspend fun answer(request: HermesPhoneRequest, error: JSONObject, done: PhoneOutcome, logged: String): PhoneOutcome {
        claim(request, true)?.let { return it }
        tools.record(request.tool, lineOf(request), logged)
        report(request.id, error)
        return done
    }

    private fun failure(code: String, message: String) = json("error" to code, "message" to message)

    private suspend fun report(id: String, result: JSONObject) {
        val bounded = if (result.toString().length > PHONE_RESULT_BYTES) failure("unavailable", "The answer was too long to send.") else result
        for (attempt in 0..1) {
            try { link.result(id, bounded); return }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (attempt == 0) pause(500) }
        }
    }

    /** What this phone allows Hermes, as the plugin stores it. */
    fun stateSnapshot(): JSONObject = json(
        "hermes_access" to tools.hermesAccess(), "ask_reads" to tools.askBeforeReads(),
        "tools" to JSONArray(tools.enabled().filter { it in tools.names }.sorted()),
    )

    /**
     * Tells the plugin when what this phone allows changed. Returns what was last sent: nothing is sent for a phone that never turned
     * Hermes access on, and nothing again while it is unchanged.
     */
    suspend fun syncState(last: String?): String? {
        val snapshot = stateSnapshot()
        val text = snapshot.toString()
        if (text == last || last == null && !snapshot.getBoolean("hermes_access")) return last
        link.state(snapshot)
        return text
    }
}

/** A request waiting for the owner, as the approval card shows it; [scope] is the signed-in profile it came from. */
internal class HermesPhoneCard(val scope: Scope, val held: HermesPhoneEngine.Card, val engine: HermesPhoneEngine) {
    val request: HermesPhoneRequest get() = held.request
    val prepared: PhonePrepared get() = held.prepared
    val card: PhoneCard get() = held.card
}

object HermesPhoneRequests {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val cardsLock = Any()
    private val _cards = MutableStateFlow<List<HermesPhoneCard>>(emptyList())
    internal val cards: StateFlow<List<HermesPhoneCard>> = _cards.asStateFlow()
    private var handledRequests: HandledRequests? = null

    private fun elapsed() = SystemClock.elapsedRealtime()
    private fun isLocked(context: Context) = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false
    internal fun locked(context: Context) = isLocked(context)

    @Synchronized private fun handled(context: Context): HandledRequests = handledRequests ?: run {
        val prefs = context.applicationContext.getSharedPreferences("hermes-phone-requests", Context.MODE_PRIVATE)
        val seen = HashMap<String, Long>()
        prefs.all.forEach { (key, value) -> if (value is Long) seen[key] = value }
        HandledRequests(seen, { map ->
            val edit = prefs.edit().clear()
            map.forEach { (key, value) -> edit.putLong(key, value) }
            edit.commit()
        }, System::currentTimeMillis).also { handledRequests = it }
    }

    private fun stateStore(context: Context) = context.applicationContext.getSharedPreferences("hermes-phone-requests-state", Context.MODE_PRIVATE)

    private fun engine(context: Context, client: BeaconClient) = HermesPhoneEngine(
        RealPhoneTools(context.applicationContext), ClientLink(client), handled(context), ::elapsed, { !isLocked(context) },
    )

    /** A push said Hermes has a phone request: read what is waiting and show it. */
    fun onPush(context: Context, source: String) {
        val app = context.applicationContext as BeaconApplication
        scope.launch {
            try {
                withTimeout(20_000) {
                    if (!app.push.permitsIncoming(source)) return@withTimeout
                    val binding = app.push.binding(source) ?: return@withTimeout
                    val bound = Scope(trustedOrigin(binding.getString("origin")), binding.getString("owner"), binding.getString("profile"), nativeProvider = binding.string("native_provider"))
                    val client = app.push.clientFor(binding)
                    client.bootstrap()
                    if (client.scope != bound || !app.push.permitsIncoming(source)) return@withTimeout
                    refresh(app, client)
                }
            } catch (e: TimeoutCancellationException) { /* Offline: the open app asks again. */ }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* An older plugin, a signed-out account or no network: nothing to show. */ }
        }
    }

    /**
     * Reads what Hermes is waiting on for this signed-in profile and shows what needs the owner. Everything here, the cards' lookups
     * included, runs off the main thread whoever calls.
     */
    internal suspend fun refresh(context: Context, client: BeaconClient) = withContext(Dispatchers.IO) { lock.withLock {
        val profile = client.scope
        val engine = engine(context, client)
        val shown = _cards.value.filter { it.scope == profile }.associate { it.request.id to it.prepared }
        val found = engine.refresh { shown[it] }
        val now = elapsed()
        val live = found.filter { it.request.deadline > now }
        val gone: List<HermesPhoneCard>
        val fresh: List<HermesPhoneEngine.Card>
        synchronized(cardsLock) {
            val kept = _cards.value
            fresh = live.filter { card -> kept.none { it.scope == profile && it.request.id == card.request.id } }
            gone = kept.filter { it.scope == profile && live.none { card -> card.request.id == it.request.id } }
            _cards.value = kept - gone.toSet() + fresh.map { HermesPhoneCard(profile, it, engine) }
        }
        gone.forEach { cancelNotification(context, it.request.id); it.engine.expired(it.request) }
        fresh.forEach { notify(context, it.request, it.card) }
    } }

    /** Keeps the plugin told what this phone allows, and reads what is waiting while Hermes may use the phone. */
    internal suspend fun keepCurrent(context: Context, client: BeaconClient) = withContext(Dispatchers.IO) {
        val engine = engine(context, client)
        val key = client.scope.key
        try {
            val store = stateStore(context)
            val before = store.getString(key, null)
            val sent = engine.syncState(before)
            if (sent != before) store.edit().putString(key, sent).apply()
            if (engine.stateSnapshot().getBoolean("hermes_access")) refresh(context, client)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Offline or an older plugin: the next pass tries again. */ }
    }

    /** The owner tapped Allow on [card]. A card stays on screen while the phone is locked, the plugin could not be reached, or the request ended unfinished. */
    internal suspend fun approve(context: Context, card: HermesPhoneCard): PhoneOutcome = withContext(Dispatchers.IO) {
        val outcome = try { card.engine.approve(card.held) } catch (e: CancellationException) { throw e } catch (_: Exception) { PhoneOutcome.Gone }
        if (outcome !in setOf(PhoneOutcome.Locked, PhoneOutcome.Retry, PhoneOutcome.Expired, PhoneOutcome.Gone)) dismiss(context, card)
        outcome
    }

    internal suspend fun deny(context: Context, card: HermesPhoneCard): PhoneOutcome = withContext(Dispatchers.IO) {
        val outcome = try { card.engine.deny(card.request) } catch (e: CancellationException) { throw e } catch (_: Exception) { PhoneOutcome.Gone }
        if (outcome !in setOf(PhoneOutcome.Retry, PhoneOutcome.Expired, PhoneOutcome.Gone)) dismiss(context, card)
        outcome
    }

    internal fun dismiss(context: Context, card: HermesPhoneCard) {
        synchronized(cardsLock) { _cards.value = _cards.value.filter { it.request.id != card.request.id || it.scope != card.scope } }
        cancelNotification(context, card.request.id)
    }

    /** Nothing left to approve, for example after the phone is removed from Hermes. */
    fun clear(context: Context) {
        val dropped = synchronized(cardsLock) { _cards.value.also { _cards.value = emptyList() } }
        dropped.forEach { cancelNotification(context, it.request.id) }
    }

    private fun tag(id: String) = "phone:$id"

    private fun cancelNotification(context: Context, id: String) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(tag(id), NOTIFICATION_ID) }
    }

    /** What the card says, in the phone's language and whole, as lines: the headline, who or where, the details and the notes or message. */
    private fun cardText(context: Context, card: PhoneCard): String =
        (listOf(PhoneTools.render(context, card.headline), card.target) + card.details().map { PhoneTools.render(context, it) } + card.preview)
            .filter { it.isNotBlank() }.joinToString("\n")

    private fun notify(context: Context, request: HermesPhoneRequest, card: PhoneCard) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureActivityChannels(manager)
        val channel = promptNotificationChannel(manager)
        val open = PendingIntent.getActivity(
            context, request.id.hashCode(),
            Intent(context, BeaconActivity::class.java).setData(android.net.Uri.parse("hermes://phone/${request.id}"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = serviceText(context, "Phone request")
        val text = boundedNotificationText(cardText(context, card), 640, preserveWhitespace = true)
        val public = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_beacon_notification)
            .setContentTitle(title).setContentText(serviceText(context, "Open Beacon to review this request.")).build()
        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_beacon_notification)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true).setContentIntent(open).setTimeoutAfter((request.deadline - elapsed()).coerceAtLeast(1)).build()
        try { manager.notify(tag(request.id), NOTIFICATION_ID, notification) } catch (_: SecurityException) { /* Notification permission was revoked. */ }
    }

    private const val NOTIFICATION_ID = 6
}
