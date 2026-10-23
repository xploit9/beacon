package dev.hermes.android.push

import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.hermes.android.BuildConfig
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.R
import dev.hermes.android.core.Scope
import dev.hermes.android.core.digest
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.AuthStore
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.NativeAuthExpiredException
import dev.hermes.android.data.authorizesPushSource
import dev.hermes.android.data.cachedScopeFor
import dev.hermes.android.data.validNativeProfile
import dev.hermes.android.voice.RelayCallback
import dev.hermes.android.voice.RelayCallbackBinding
import dev.hermes.android.voice.RelayCallbackPhase
import dev.hermes.android.voice.RelayCallbackPreferences
import dev.hermes.android.voice.RelayCallbackProtocol
import dev.hermes.android.voice.RelayCallbackTransition
import dev.hermes.android.voice.RelayCallbackWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID

internal data class KeepConnectedPage(val events: List<JSONObject>, val nextCursor: Long, val reset: Boolean = false)
internal class KeepConnectedPageException : IOException()
private class KeepConnectedProfilesException : IOException()

internal const val KEEP_CONNECTED_LONG_POLL_SECONDS = 25
internal const val KEEP_CONNECTED_POLL_TIMEOUT_MILLIS = 35_000L
internal const val KEEP_CONNECTED_MAX_PROFILE_POLLS = 8

internal fun parseKeepConnectedPage(response: JSONObject, after: Long): KeepConnectedPage {
    val rows = response.optJSONArray("events") ?: throw KeepConnectedPageException()
    val next = when (val value = response.opt("next_cursor")) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw KeepConnectedPageException()
    }
    val reset = when (val value = response.opt("reset")) {
        null -> false
        is Boolean -> value
        else -> throw KeepConnectedPageException()
    }
    if (after < 0 || next < 0 || (!reset && next < after) || rows.length() > 100) throw KeepConnectedPageException()
    val events = (0 until rows.length()).map { rows.optJSONObject(it) ?: throw KeepConnectedPageException() }
    return KeepConnectedPage(events, next, reset)
}

internal fun keepConnectedPushData(event: JSONObject): Map<String, String> =
    listOf("source_ref", "event_id", "conversation_ref", "kind", "activity_ref", "expires_at", "release_version")
        .associateWith(event::optString)

internal fun keepConnectedFailureReason(profile: String, error: Throwable, profileLabel: String = profile): String = when (error) {
    is NativeAuthExpiredException -> "Hermes sign-in expired"
    is KeepConnectedPageException -> "Hermes returned an invalid notification page"
    is KeepConnectedProfilesException -> "Hermes returned no authorized profiles"
    is InterruptedIOException -> "Hermes did not answer in time"
    is ApiFailure -> when {
        error.status == 401 -> "Hermes rejected the connection (401)"
        error.status == 403 -> "Hermes refused profile ${profileLabel.takeIf(String::isNotBlank) ?: profile.takeIf(::validNativeProfile)?.take(32) ?: "current"} (403)"
        error.status == 404 -> "Beacon plugin route missing (404)"
        error.status == 409 -> "Hermes refused the notification request (409)"
        error.status in 500..599 -> "Hermes server error (${error.status})"
        else -> "Hermes request failed (${error.status})"
    }
    is IOException -> "Could not reach Hermes"
    else -> "Could not process Hermes notifications"
}

private fun logKeepConnectedFailure(error: Throwable) {
    if (BuildConfig.DEBUG) {
        val status = (error as? ApiFailure)?.status?.toString() ?: "-"
        android.util.Log.d("KeepConnected", "notification poll failed type=${error.javaClass.simpleName} status=$status")
    }
}

internal sealed interface KeepConnectedCycle {
    data object Connected : KeepConnectedCycle
    data class Reconnecting(val delayMillis: Long) : KeepConnectedCycle
    data object SignedOut : KeepConnectedCycle
}

internal class KeepConnectedCursors(
    private val read: (String) -> String?,
    private val write: (String, String) -> Boolean,
) {
    fun readIfPresent(origin: String, owner: String, profile: String): Long? =
        read(key(origin, owner, profile))?.toLongOrNull()?.takeIf { it >= 0 }

    fun read(origin: String, owner: String, profile: String): Long = readIfPresent(origin, owner, profile) ?: 0

    fun advance(origin: String, owner: String, profile: String, cursor: Long) {
        require(cursor >= read(origin, owner, profile)) { "Notification cursor moved backwards" }
        check(write(key(origin, owner, profile), cursor.toString())) { "Could not persist notification cursor" }
    }

    fun reset(origin: String, owner: String, profile: String, cursor: Long) {
        require(cursor >= 0) { "Invalid notification cursor" }
        check(write(key(origin, owner, profile), cursor.toString())) { "Could not persist notification cursor" }
    }

    private fun key(origin: String, owner: String, profile: String) =
        "cursor:${"$origin\u0000$owner\u0000$profile".digest()}"
}

internal fun keepConnectedBackoffMs(failures: Int): Long =
    (1_000L * (1L shl (failures - 1).coerceIn(0, 6))).coerceAtMost(60_000L)

internal const val KEEP_CONNECTED_MIN_CYCLE_MILLIS = 500L
internal fun keepConnectedPollDelay(cycleDurationMillis: Long): Long =
    (KEEP_CONNECTED_MIN_CYCLE_MILLIS - cycleDurationMillis.coerceAtLeast(0)).coerceAtLeast(0)

/** One cursor and one admission callback per authorized Hermes profile. */
internal class KeepConnectedPoller(
    private val origin: String,
    private val owner: String,
    private val cursors: KeepConnectedCursors,
    private val profiles: suspend () -> List<String>,
    private val signedInProfile: suspend () -> String = { "" },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val poll: suspend (String, Long) -> KeepConnectedPage,
    private val onEvent: suspend (String, JSONObject) -> Unit,
    private val initialize: suspend (String, Long) -> KeepConnectedPage = poll,
    private val profileLabel: (String) -> String = { it },
) {
    private data class ProfileFailure(val attempts: Int, val retryAtMillis: Long, val reason: String)
    private data class ProfilePollResult(val profile: String, val failure: Exception?, val completedAtMillis: Long)

    private val profileFailures = linkedMapOf<String, ProfileFailure>()
    private var primaryProfile = ""
    private var discoveryFailures = 0
    private var discoveryRetryAtMillis = 0L
    private var discoveryFailureReason = ""
    private var stopped = false
    val lastFailureReason: String
        get() = if (discoveryFailureReason.isNotBlank()) discoveryFailureReason
        else profileFailures[primaryProfile]?.reason ?: profileFailures.values.lastOrNull()?.reason.orEmpty()

    suspend fun pollOnce(): KeepConnectedCycle {
        if (stopped) return KeepConnectedCycle.SignedOut
        val now = nowMillis()
        if (discoveryRetryAtMillis > now) return KeepConnectedCycle.Reconnecting(discoveryRetryAtMillis - now)
        val authorized = try {
            profiles().filter(::validNativeProfile).distinct().take(256)
        } catch (error: NativeAuthExpiredException) {
            stopped = true
            discoveryFailureReason = keepConnectedFailureReason("", error)
            return KeepConnectedCycle.SignedOut
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recordDiscoveryFailure(error)
            return KeepConnectedCycle.Reconnecting(discoveryRetryAtMillis - nowMillis())
        }
        if (authorized.isEmpty()) {
            recordDiscoveryFailure(KeepConnectedProfilesException())
            return KeepConnectedCycle.Reconnecting(discoveryRetryAtMillis - nowMillis())
        }
        discoveryFailures = 0
        discoveryRetryAtMillis = 0L
        discoveryFailureReason = ""
        profileFailures.keys.retainAll(authorized.toSet())
        primaryProfile = signedInProfile().takeIf { it in authorized } ?: authorized.first()

        val cycleNow = nowMillis()
        val due = authorized.filter { profile -> profileFailures[profile]?.retryAtMillis?.let { cycleNow >= it } ?: true }
        if (due.isEmpty()) {
            val retryAt = profileFailures[primaryProfile]?.retryAtMillis ?: cycleNow
            return KeepConnectedCycle.Reconnecting((retryAt - cycleNow).coerceAtLeast(1L))
        }
        var anySucceeded = false
        try {
            coroutineScope {
                // Eight keeps the normal five-profile owner concurrent while bounding larger accounts.
                val permits = Semaphore(KEEP_CONNECTED_MAX_PROFILE_POLLS)
                val channel = Channel<ProfilePollResult>(KEEP_CONNECTED_MAX_PROFILE_POLLS)
                val firstPollAcks = due.associateWith { CompletableDeferred<Unit>() }
                val workers = due.map { profile -> launch {
                    var firstPoll = true
                    // Let each fast profile resume as soon as its first result is recorded, without waiting for peers.
                    while (currentCoroutineContext().isActive) {
                        val result = pollProfile(profile, permits)
                        channel.send(result)
                        if (result.failure != null) break
                        if (firstPoll) {
                            firstPoll = false
                            firstPollAcks.getValue(profile).await()
                        }
                    }
                } }
                val completedProfiles = mutableSetOf<String>()
                try {
                    while (completedProfiles.size < due.size) {
                        val result = channel.receive()
                        if (result.failure == null) anySucceeded = true
                        recordProfileResult(result)
                        if (completedProfiles.add(result.profile) && result.failure == null && completedProfiles.size < due.size) {
                            firstPollAcks.getValue(result.profile).complete(Unit)
                        }
                    }
                } finally {
                    workers.forEach { it.cancel() }
                    withContext(NonCancellable) { workers.joinAll() }
                    while (true) {
                        val queued = channel.tryReceive().getOrNull() ?: break
                        if (queued.failure == null) anySucceeded = true
                        recordProfileResult(queued)
                    }
                    channel.close()
                }
            }
        } catch (_: NativeAuthExpiredException) {
            stopped = true
            discoveryFailureReason = keepConnectedFailureReason(primaryProfile, NativeAuthExpiredException())
            return KeepConnectedCycle.SignedOut
        } catch (error: CancellationException) {
            throw error
        }
        val primaryFailure = profileFailures[primaryProfile]
        return if (primaryFailure == null) KeepConnectedCycle.Connected
        else KeepConnectedCycle.Reconnecting(if (anySucceeded) KEEP_CONNECTED_MIN_CYCLE_MILLIS else (primaryFailure.retryAtMillis - nowMillis()).coerceAtLeast(1L))
    }

    private suspend fun pollProfile(profile: String, permits: Semaphore): ProfilePollResult {
        val startedAt = nowMillis()
        return try {
            val savedCursor = cursors.readIfPresent(origin, owner, profile)
            if (savedCursor == null) {
                var after = 0L
                var pages = 0
                while (true) {
                    val page = permits.withPermit { initialize(profile, after) }
                    if (page.nextCursor < 0 || (!page.reset && page.nextCursor < after) || page.events.size > 100) throw KeepConnectedPageException()
                    when {
                        page.reset -> { after = page.nextCursor; break }
                        page.events.isEmpty() -> { after = page.nextCursor; break }
                        page.nextCursor <= after -> throw KeepConnectedPageException()
                        else -> after = page.nextCursor
                    }
                    pages++
                    if (pages > 1_000) throw KeepConnectedPageException()
                }
                cursors.reset(origin, owner, profile, after)
                return ProfilePollResult(profile, null, nowMillis())
            }
            val after = savedCursor
            val page = permits.withPermit {
                val result = poll(profile, after)
                if (result.nextCursor < 0 || (!result.reset && result.nextCursor < after) || result.events.size > 100) throw KeepConnectedPageException()
                result.events.forEach { onEvent(profile, it) }
                if (result.reset) cursors.reset(origin, owner, profile, result.nextCursor)
                else cursors.advance(origin, owner, profile, result.nextCursor)
                result
            }
            if (page.events.isEmpty() || page.nextCursor == after) {
                // Avoid spinning if an empty or non-advancing long poll returns before the 500 ms minimum cycle.
                keepConnectedPollDelay(nowMillis() - startedAt).takeIf { it > 0 }?.let { delay(it) }
            }
            ProfilePollResult(profile, null, nowMillis())
        } catch (error: NativeAuthExpiredException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logKeepConnectedFailure(error)
            ProfilePollResult(profile, error, nowMillis())
        }
    }

    private fun recordProfileResult(result: ProfilePollResult) {
        val error = result.failure
        if (error == null) profileFailures.remove(result.profile)
        else {
            val attempts = (profileFailures[result.profile]?.attempts ?: 0) + 1
            profileFailures.remove(result.profile)
            profileFailures[result.profile] = ProfileFailure(
                attempts,
                result.completedAtMillis + keepConnectedBackoffMs(attempts),
                keepConnectedFailureReason(result.profile, error, profileLabel(result.profile)),
            )
        }
    }

    private fun recordDiscoveryFailure(error: Exception) {
        discoveryFailures += 1
        discoveryFailureReason = keepConnectedFailureReason("", error)
        discoveryRetryAtMillis = nowMillis() + keepConnectedBackoffMs(discoveryFailures)
        logKeepConnectedFailure(error)
    }
}

internal data class PreparedKeepConnectedCall(
    val call: RelayCallback,
    val binding: RelayCallbackBinding,
    val preferences: RelayCallbackPreferences,
    val protocol: RelayCallbackProtocol,
)

internal fun keepConnectedLoopNeedsRestart(
    jobActive: Boolean,
    runningOrigin: String?,
    runningOwner: String?,
    requestedOrigin: String,
    requestedOwner: String,
): Boolean = !jobActive || runningOrigin != requestedOrigin || runningOwner != requestedOwner

/** Translate only the plugin's event fields into the reviewed local Relay reducer envelope. */
internal fun prepareKeepConnectedCall(
    scope: Scope,
    event: JSONObject,
    enabled: Boolean,
    now: Long,
    elapsed: Long,
): PreparedKeepConnectedCall? {
    val eventId = event.optString("event_id")
    val source = event.optString("source_ref")
    val profile = event.optString("profile")
    val session = event.optString("session_id")
    val owner = event.optString("owner_id")
    val seconds = when (val value = event.opt("expires_at")) {
        is Int -> value.toLong()
        is Long -> value
        else -> return null
    }
    if (event.optString("kind") != "incoming_call" || event.optString("state") != "pending" ||
        event.optString("callback_id") != eventId || owner != scope.owner || profile != scope.profile ||
        event.optString("conversation_id") != session || session.isBlank() || session.length > 512 ||
        !validOpaque(source) || !validOpaque(eventId) || !validOpaque(event.optString("conversation_ref")) ||
        seconds <= 0 || now < 0 || now > Long.MAX_VALUE - RelayCallback.RING_WINDOW_MS
    ) return null
    val serverExpiry = runCatching { Math.multiplyExact(seconds, 1_000L) }.getOrNull() ?: return null
    val expires = minOf(serverExpiry, now + RelayCallback.RING_WINDOW_MS)
    if (!enabled || expires <= now) return null
    val created = expires - RelayCallback.RING_WINDOW_MS
    if (created < 0) return null

    val connectionId = "${scope.origin}\u0000${scope.owner}\u0000${scope.profile}".digest()
    val binding = RelayCallbackBinding(connectionId, source, profile, session, scope.origin, scope.owner)
    val preferences = RelayCallbackPreferences(connectionId, calls = true, keepConnected = enabled)
    val payload = JSONObject()
        .put("call_id", eventId)
        .put("device_id", source)
        .put("profile", profile)
        .put("session_id", session)
        .put("title", "Hermes")
        .put("reason", "")
        .put("created_at", created)
        .put("expires_at", expires)
        .put("status", "ringing")
    val protocol = RelayCallbackProtocol()
    val transition: RelayCallbackTransition = protocol.receive(
        RelayCallbackWire.envelope("phone.call.invite", payload, eventId),
        binding,
        preferences,
        now,
        elapsed,
    ) ?: return null
    return transition.takeIf { it.call.phase == RelayCallbackPhase.Ringing }
        ?.let { PreparedKeepConnectedCall(it.call, binding, preferences, protocol) }
}

class KeepConnectedService : Service() {
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var pollJob: kotlinx.coroutines.Job? = null
    private var pollOrigin: String? = null
    private var pollOwner: String? = null
    private var promoted = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun restartPoll(origin: String, owner: String) {
        val previous = pollJob
        previous?.cancel()
        pollOrigin = origin
        pollOwner = owner
        pollJob = serviceScope.launch {
            previous?.join()
            runLoop(origin, owner)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = networkChanged()
            override fun onLost(network: Network) = networkChanged()
            private fun networkChanged() {
                android.os.Handler(mainLooper).post {
                    if (activeInstance === this@KeepConnectedService && isEnabled(this@KeepConnectedService)) {
                        val origin = pollOrigin
                        val owner = pollOwner
                        if (origin != null && owner != null) restartPoll(origin, owner)
                    }
                }
            }
        }
        try { connectivity.registerDefaultNetworkCallback(callback); networkCallback = callback }
        catch (_: Exception) { /* Poll timeout still bounds recovery if Android refuses the callback. */ }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (stopFromNotification(this, intent.getStringExtra(EXTRA_STOP_TOKEN))) {
                (application as BeaconApplication).push.invalidateKeepConnectedInvitations()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        if (!isEnabled(this)) {
            // A startForegroundService() start must promote before it stops, or Android crashes the app.
            if (promote()) stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!promote()) {
            setStatus(this, STATUS_RECONNECTING)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val prefs = preferences(this)
        val origin = prefs.getString(KEY_ORIGIN, "").orEmpty()
        val owner = prefs.getString(KEY_OWNER, "").orEmpty()
        if (keepConnectedLoopNeedsRestart(pollJob?.isActive == true, pollOrigin, pollOwner, origin, owner)) {
            restartPoll(origin, owner)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        networkCallback = null
        pollOrigin = null
        pollOwner = null
        serviceScope.cancel()
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    @SuppressLint("ForegroundServiceType")
    private fun promote(): Boolean = try {
        ensureChannel()
        val notification = connectionNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        promoted = true
        true
    } catch (_: Exception) {
        false
    }

    private suspend fun runLoop(origin: String, owner: String) {
        val app = application as BeaconApplication
        val prefs = preferences(this)
        if (origin.isBlank() || owner.isBlank()) {
            setStatus(this, STATUS_SIGNED_OUT)
            stopSelf()
            return
        }
        val cursors = KeepConnectedCursors(
            read = { key -> prefs.getString(key, null) },
            write = { key, value -> prefs.edit().putString(key, value).commit() },
        )
        var client: BeaconClient? = null
        var authorizedProfiles = emptyList<String>()
        var profileDisplayNames = emptyMap<String, String>()
        var profilesReadAt = 0L
        val poller = KeepConnectedPoller(
            origin = origin,
            owner = owner,
            cursors = cursors,
            profiles = profiles@ {
                val auth = app.auth.read(origin)
                val cached = auth.cachedScopeFor(origin, replacingAccount = false)
                if (cached == null || cached.owner != owner) throw NativeAuthExpiredException()
                val current = client
                if (current == null || current.auth.profile != auth.profile || current.auth.bearer != auth.bearer) {
                    current?.closeNative()
                    client = createClient(app, auth)
                    profilesReadAt = 0L
                }
                val profileClient = requireNotNull(client)
                if (profilesReadAt > 0 && SystemClock.elapsedRealtime() - profilesReadAt < 300_000L) return@profiles authorizedProfiles
                val profiles = try { profileClient.nativeGet("/api/profiles").rows("profiles") }
                catch (error: NativeAuthExpiredException) { throw error }
                catch (error: CancellationException) { throw error }
                catch (error: ApiFailure) { if (error.status == 401) throw NativeAuthExpiredException() else null }
                catch (_: IOException) { null }
                catch (_: Exception) { null }
                authorizedProfiles = profiles?.map { it.string("name").trim() }?.filter(::validNativeProfile)?.distinct()?.take(256)
                    ?: listOf(profileClient.auth.profile).filter(::validNativeProfile)
                profileDisplayNames = profiles.orEmpty().associate { row ->
                    val id = row.string("name").trim()
                    id to (row.string("display_name").trim().takeIf(String::isNotBlank) ?: id)
                }
                if (profiles != null && authorizedProfiles.isNotEmpty()) profilesReadAt = SystemClock.elapsedRealtime()
                authorizedProfiles
            },
                poll = { profile, after ->
                    val response = requireNotNull(client).nativeGetForProfile(
                        "/api/plugins/beacon/mobile/notifications/events",
                        profile,
                        authorizedProfiles,
                        query = mapOf("after" to after.toString(), "wait" to KEEP_CONNECTED_LONG_POLL_SECONDS.toString(), "package" to BuildConfig.APPLICATION_ID),
                        maxBytes = 1_048_576,
                        timeoutMillis = KEEP_CONNECTED_POLL_TIMEOUT_MILLIS,
                    )
                    parseKeepConnectedPage(response, after)
                },
                initialize = { profile, after ->
                    val response = requireNotNull(client).nativeGetForProfile(
                        "/api/plugins/beacon/mobile/notifications/events",
                        profile,
                        authorizedProfiles,
                        query = mapOf("after" to after.toString(), "wait" to "0", "package" to BuildConfig.APPLICATION_ID),
                        maxBytes = 1_048_576,
                    )
                    parseKeepConnectedPage(response, after)
                },
                signedInProfile = { client?.auth?.profile.orEmpty() },
                onEvent = { profile, event ->
                    if (event.optString("kind") == "incoming_call") deliverIncomingCall(app, origin, owner, profile, event)
                    else if (app.push.bindKeepConnectedSource(Scope(origin, owner, profile), event.optString("source_ref"))) {
                        receivePush(this, keepConnectedPushData(event))
                    }
                },
                profileLabel = { profile -> profileDisplayNames[profile] ?: profile },
            )
        try {
            while (currentCoroutineContext().isActive && isEnabledFor(this, Scope(origin, owner, "default"))) {
                val current = app.auth.read(origin)
                val cached = current.cachedScopeFor(origin, replacingAccount = false)
                if (cached == null || cached.owner != owner) {
                    setStatus(this, STATUS_SIGNED_OUT)
                    stopSelf()
                    return
                }
                val cycleStarted = SystemClock.elapsedRealtime()
                when (val result = poller.pollOnce()) {
                    KeepConnectedCycle.Connected -> {
                        // Clear the stale sign-in prompt before anything can observe "Connected".
                        getSystemService(NotificationManager::class.java).cancel(SIGN_IN_NOTIFICATION_ID)
                        setStatus(this, STATUS_CONNECTED, poller.lastFailureReason)
                        delay(keepConnectedPollDelay(SystemClock.elapsedRealtime() - cycleStarted))
                    }
                    is KeepConnectedCycle.Reconnecting -> {
                        setStatus(this, STATUS_RECONNECTING, poller.lastFailureReason)
                        delay(result.delayMillis)
                    }
                    KeepConnectedCycle.SignedOut -> {
                        setStatus(this, STATUS_SIGNED_OUT, poller.lastFailureReason)
                        app.push.invalidateKeepConnectedInvitations()
                        postSignInNotification()
                        stopSelf()
                        return
                    }
                }
            }
        } finally {
            client?.closeNative()
            if (!isEnabled(this)) setStatus(this, STATUS_OFF)
        }
    }

    private fun deliverIncomingCall(app: BeaconApplication, origin: String, owner: String, profile: String, event: JSONObject) {
        val scope = Scope(origin, owner, profile)
        if (!app.auth.read(origin).authorizesPushSource(scope)) return
        val source = event.optString("source_ref")
        val eventId = event.optString("event_id")
        val token = app.push.prepareKeepConnectedCall(scope, event, System.currentTimeMillis(), SystemClock.elapsedRealtime()) ?: return
        receiveKeepConnectedCall(this, mapOf(
            "source_ref" to source,
            "event_id" to eventId,
            "conversation_ref" to event.optString("conversation_ref"),
            "kind" to "incoming_call",
            "expires_at" to event.optString("expires_at"),
        ), token)
        if (!app.push.hasKeepConnectedInvitation(source, eventId, token)) {
            app.push.removeKeepConnectedCallback(source, eventId, token)
        }
    }

    private fun postSignInNotification() {
        try {
            ensureChannel()
            val pending = openAppPendingIntent(this, 2)
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_beacon_notification)
                .setContentTitle("Sign in again")
                .setContentText("Your Hermes session expired. Sign in to keep receiving calls.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, "Sign in again", pending)
                .build()
            getSystemService(NotificationManager::class.java).notify(SIGN_IN_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) { }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Keep connected", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps you reachable for incoming calls."
                setShowBadge(false)
            })
        }
    }

    private fun connectionNotification(context: Context): android.app.Notification {
        val status = status(context)
        val (title, body) = keepConnectedText(status, dev.hermes.android.data.currentAssistantName)
        val stop = Intent(context, KeepConnectedService::class.java)
            .setAction(ACTION_STOP)
            .setData(Uri.parse("beacon://keep-connected/stop/${stopToken(context)}"))
            .putExtra(EXTRA_STOP_TOKEN, stopToken(context))
        val stopPending = PendingIntent.getService(context, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_beacon_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title — $body"))
            .setContentIntent(openAppPendingIntent(context, 1))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    private fun updateNotification() {
        if (!promoted) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, connectionNotification(this)) }
    }

    companion object {
        const val PREFS = "keep-connected"
        const val KEY_ENABLED = "enabled"
        const val KEY_ORIGIN = "origin"
        const val KEY_OWNER = "owner"
        const val KEY_STATUS = "status"
        const val KEY_REASON = "reason"
        private const val KEY_STOP_TOKEN = "stop_token"
        const val STATUS_OFF = "off"
        const val STATUS_STARTING = "starting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_RECONNECTING = "reconnecting"
        const val STATUS_SIGNED_OUT = "signed_out"
        private const val CHANNEL_ID = "beacon_keep_connected"
        private const val NOTIFICATION_ID = 4082
        private const val SIGN_IN_NOTIFICATION_ID = 4083
        private const val ACTION_START = "dev.hermes.android.push.KEEP_CONNECTED_START"
        private const val ACTION_STOP = "dev.hermes.android.push.KEEP_CONNECTED_STOP"
        private const val EXTRA_STOP_TOKEN = "stop_token"

        @Volatile internal var clientFactoryForTest: ((AuthContext, AuthStore) -> BeaconClient)? = null
        @Volatile private var activeInstance: KeepConnectedService? = null

        private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_ENABLED, false)
        fun status(context: Context): String = preferences(context).getString(KEY_STATUS, STATUS_OFF) ?: STATUS_OFF
        fun failureReason(context: Context): String = preferences(context).getString(KEY_REASON, "").orEmpty()
        fun isEnabledFor(context: Context, scope: Scope): Boolean {
            val prefs = preferences(context)
            return prefs.getBoolean(KEY_ENABLED, false) && prefs.getString(KEY_ORIGIN, null) == scope.origin &&
                prefs.getString(KEY_OWNER, null) == scope.owner
        }

        fun setEnabled(context: Context, auth: AuthContext?, enabled: Boolean) {
            if (!enabled) {
                disablePreference(context)
                (context.applicationContext as? BeaconApplication)?.push?.invalidateKeepConnectedInvitations()
                context.applicationContext.stopService(Intent(context.applicationContext, KeepConnectedService::class.java))
                return
            }
            val current = requireNotNull(auth) { "Sign in with Hermes before enabling Keep connected" }
            val authorized = current.cachedScopeFor(current.origin, replacingAccount = false)
            require(authorized != null && authorized.owner == current.owner) { "Sign in with Hermes before enabling Keep connected" }
            val prefs = preferences(context)
            check(prefs.edit().putBoolean(KEY_ENABLED, true).putString(KEY_ORIGIN, current.origin)
                .putString(KEY_OWNER, current.owner).putString(KEY_STATUS, STATUS_STARTING).putString(KEY_REASON, "")
                .putString(KEY_STOP_TOKEN, UUID.randomUUID().toString()).commit()) { "Could not enable Keep connected" }
            start(context)
        }

        fun startIfEnabled(context: Context) {
            val prefs = preferences(context)
            if (!prefs.getBoolean(KEY_ENABLED, false)) return
            val origin = prefs.getString(KEY_ORIGIN, null).orEmpty()
            val owner = prefs.getString(KEY_OWNER, null).orEmpty()
            val app = context.applicationContext as? BeaconApplication ?: return
            val auth = app.auth.read(origin)
            val scope = auth.cachedScopeFor(origin, replacingAccount = false)
            if (origin.isBlank() || owner.isBlank() || scope?.owner != owner) {
                setStatus(context, STATUS_SIGNED_OUT)
                app.push.invalidateKeepConnectedInvitations()
                cancelSignInNotification(context)
                context.applicationContext.stopService(Intent(context.applicationContext, KeepConnectedService::class.java))
                return
            }
            ensureStopToken(context)
            if (status(context) == STATUS_OFF || status(context) == STATUS_SIGNED_OUT) setStatus(context, STATUS_STARTING)
            start(context)
        }

        fun onUserSignedOut(context: Context, origin: String) {
            val prefs = preferences(context)
            if (prefs.getString(KEY_ORIGIN, null) != origin) return
            setStatus(context, STATUS_SIGNED_OUT)
            (context.applicationContext as? BeaconApplication)?.push?.invalidateKeepConnectedInvitations()
            cancelSignInNotification(context)
            context.applicationContext.stopService(Intent(context.applicationContext, KeepConnectedService::class.java))
        }

        internal fun createClient(app: BeaconApplication, auth: AuthContext): BeaconClient =
            clientFactoryForTest?.invoke(auth, app.auth) ?: BeaconClient(auth, app.auth, persistContext = false)

        private fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context.applicationContext, Intent(context.applicationContext, KeepConnectedService::class.java).setAction(ACTION_START))
            } catch (_: Exception) {
                setStatus(context, STATUS_RECONNECTING)
            }
        }

        private fun setStatus(context: Context, status: String, reason: String = "") {
            val prefs = preferences(context)
            if (prefs.getString(KEY_STATUS, null) == status && prefs.getString(KEY_REASON, "") == reason) return
            prefs.edit().putString(KEY_STATUS, status).putString(KEY_REASON, reason).apply()
            activeInstance?.updateNotification()
        }

        private fun disablePreference(context: Context) {
            val prefs = preferences(context)
            check(prefs.edit().putBoolean(KEY_ENABLED, false).putString(KEY_STATUS, STATUS_OFF).putString(KEY_REASON, "").commit()) { "Could not stop Keep connected" }
            cancelSignInNotification(context)
        }

        private fun cancelSignInNotification(context: Context) {
            context.applicationContext.getSystemService(NotificationManager::class.java).cancel(SIGN_IN_NOTIFICATION_ID)
        }

        private fun stopToken(context: Context): String = ensureStopToken(context)
        private fun ensureStopToken(context: Context): String {
            val prefs = preferences(context)
            prefs.getString(KEY_STOP_TOKEN, null)?.takeIf(String::isNotBlank)?.let { return it }
            val value = UUID.randomUUID().toString()
            check(prefs.edit().putString(KEY_STOP_TOKEN, value).commit()) { "Could not create Keep connected stop action" }
            return value
        }

        private fun stopFromNotification(context: Context, token: String?): Boolean {
            val prefs = preferences(context)
            if (token.isNullOrBlank() || prefs.getString(KEY_STOP_TOKEN, null) != token || !prefs.getBoolean(KEY_ENABLED, false)) return false
            disablePreference(context)
            return true
        }

        private fun openAppPendingIntent(context: Context, requestCode: Int): PendingIntent {
            val open = Intent(context, BeaconActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, requestCode, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

/** The Keep connected notification. Android already shows the app's name above it; the body names the assistant (its rename, or the default). */
internal fun keepConnectedText(status: String, assistant: String): Pair<String, String> = when (status) {
    KeepConnectedService.STATUS_RECONNECTING -> "Reconnecting…" to "$assistant can call you again once you're back online."
    KeepConnectedService.STATUS_STARTING -> "Connecting…" to "Getting ready so $assistant can call you."
    KeepConnectedService.STATUS_SIGNED_OUT -> "Signed out" to "Sign in again so $assistant can call you."
    KeepConnectedService.STATUS_OFF -> "Keep connected is off" to "Turn it on in Settings › Notifications so $assistant can call you."
    else -> "Connected" to "$assistant can call you."
}
