package dev.hermes.android.push

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.hermes.android.CypherActivity
import dev.hermes.android.CypherApplication
import dev.hermes.android.R
import dev.hermes.android.core.Scope
import dev.hermes.android.core.digest
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.AuthStore
import dev.hermes.android.data.CypherClient
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

internal data class KeepConnectedPage(val events: List<JSONObject>, val nextCursor: Long)

internal sealed interface KeepConnectedCycle {
    data object Connected : KeepConnectedCycle
    data class Reconnecting(val delayMillis: Long) : KeepConnectedCycle
    data object SignedOut : KeepConnectedCycle
}

internal class KeepConnectedCursors(
    private val read: (String) -> String?,
    private val write: (String, String) -> Boolean,
) {
    fun read(origin: String, owner: String, profile: String): Long =
        read(key(origin, owner, profile))?.toLongOrNull()?.takeIf { it >= 0 } ?: 0

    fun advance(origin: String, owner: String, profile: String, cursor: Long) {
        require(cursor >= read(origin, owner, profile)) { "Notification cursor moved backwards" }
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
    private val poll: suspend (String, Long) -> KeepConnectedPage,
    private val onEvent: suspend (String, JSONObject) -> Unit,
) {
    private var failures = 0
    private var stopped = false

    suspend fun pollOnce(): KeepConnectedCycle {
        if (stopped) return KeepConnectedCycle.SignedOut
        return try {
            val authorized = profiles().filter(::validNativeProfile).distinct().take(256)
            if (authorized.isEmpty()) throw IOException("Hermes returned no authorized profiles")
            coroutineScope {
                authorized.map { profile -> async {
                    val after = cursors.read(origin, owner, profile)
                    val page = poll(profile, after)
                    require(page.nextCursor >= after && page.events.size <= 100) { "Invalid Hermes event page" }
                    page.events.forEach { onEvent(profile, it) }
                    cursors.advance(origin, owner, profile, page.nextCursor)
                } }.forEach { it.await() }
            }
            failures = 0
            KeepConnectedCycle.Connected
        } catch (_: NativeAuthExpiredException) {
            stopped = true
            KeepConnectedCycle.SignedOut
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failures += 1
            KeepConnectedCycle.Reconnecting(keepConnectedBackoffMs(failures))
        }
    }
}

internal data class PreparedKeepConnectedCall(
    val call: RelayCallback,
    val binding: RelayCallbackBinding,
    val preferences: RelayCallbackPreferences,
    val protocol: RelayCallbackProtocol,
)

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
    private var promoted = false

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (stopFromNotification(this, intent.getStringExtra(EXTRA_STOP_TOKEN))) {
                (application as CypherApplication).push.invalidateKeepConnectedInvitations()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        if (!isEnabled(this)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!promote()) {
            setStatus(this, STATUS_RECONNECTING)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (pollJob?.isActive != true) pollJob = serviceScope.launch { runLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
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

    private suspend fun runLoop() {
        val app = application as CypherApplication
        val prefs = preferences(this)
        val origin = prefs.getString(KEY_ORIGIN, "").orEmpty()
        val owner = prefs.getString(KEY_OWNER, "").orEmpty()
        if (origin.isBlank() || owner.isBlank()) {
            setStatus(this, STATUS_SIGNED_OUT)
            stopSelf()
            return
        }
        val cursors = KeepConnectedCursors(
            read = { key -> prefs.getString(key, null) },
            write = { key, value -> prefs.edit().putString(key, value).commit() },
        )
        var client: CypherClient? = null
        var authorizedProfiles = emptyList<String>()
        val poller = KeepConnectedPoller(
            origin = origin,
            owner = owner,
            cursors = cursors,
            profiles = {
                val auth = app.auth.read(origin)
                val cached = auth.cachedScopeFor(origin, replacingAccount = false)
                if (cached == null || cached.owner != owner) throw NativeAuthExpiredException()
                val current = client
                if (current == null || current.auth.profile != auth.profile || current.auth.bearer != auth.bearer) {
                    current?.closeNative()
                    client = createClient(app, auth)
                }
                authorizedProfiles = requireNotNull(client).nativeAuthorizedProfiles()
                authorizedProfiles
            },
            poll = { profile, after ->
                val response = requireNotNull(client).nativeGetForProfile(
                    "/api/plugins/cypher/mobile/notifications/events",
                    profile,
                    authorizedProfiles,
                    query = mapOf("after" to after.toString(), "wait" to "25"),
                    maxBytes = 1_048_576,
                )
                val rows = response.optJSONArray("events") ?: throw IOException("Hermes returned an invalid event page")
                if (rows.length() > 100) throw IOException("Hermes returned too many events")
                val next = when (val value = response.opt("next_cursor")) {
                    is Int -> value.toLong()
                    is Long -> value
                    else -> throw IOException("Hermes returned an invalid event cursor")
                }
                KeepConnectedPage((0 until rows.length()).map { rows.getJSONObject(it) }, next)
            },
            onEvent = { profile, event -> deliverIncomingCall(app, origin, owner, profile, event) },
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
                        setStatus(this, STATUS_CONNECTED)
                        getSystemService(NotificationManager::class.java).cancel(SIGN_IN_NOTIFICATION_ID)
                        delay(keepConnectedPollDelay(SystemClock.elapsedRealtime() - cycleStarted))
                    }
                    is KeepConnectedCycle.Reconnecting -> {
                        setStatus(this, STATUS_RECONNECTING)
                        delay(result.delayMillis)
                    }
                    KeepConnectedCycle.SignedOut -> {
                        setStatus(this, STATUS_SIGNED_OUT)
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

    private fun deliverIncomingCall(app: CypherApplication, origin: String, owner: String, profile: String, event: JSONObject) {
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
                .setSmallIcon(R.drawable.ic_cypher_notification)
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
                description = "Keeps Cypher connected so Hermes can deliver incoming calls."
                setShowBadge(false)
            })
        }
    }

    private fun connectionNotification(context: Context): android.app.Notification {
        val status = status(context)
        val (title, body) = when (status) {
            STATUS_RECONNECTING -> "Cypher is reconnecting" to "Hermes can call you when the connection is restored."
            STATUS_STARTING -> "Cypher is connecting" to "Keep connected is starting."
            STATUS_SIGNED_OUT -> "Cypher is signed out" to "Sign in again to receive calls."
            STATUS_OFF -> "Keep connected is off" to "Turn it on in Cypher notification settings to receive calls."
            else -> "Cypher is connected" to "Hermes can call you."
        }
        val stop = Intent(context, KeepConnectedService::class.java)
            .setAction(ACTION_STOP)
            .setData(Uri.parse("cypher://keep-connected/stop/${stopToken(context)}"))
            .putExtra(EXTRA_STOP_TOKEN, stopToken(context))
        val stopPending = PendingIntent.getService(context, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cypher_notification)
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
        private const val KEY_STOP_TOKEN = "stop_token"
        const val STATUS_OFF = "off"
        const val STATUS_STARTING = "starting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_RECONNECTING = "reconnecting"
        const val STATUS_SIGNED_OUT = "signed_out"
        private const val CHANNEL_ID = "cypher_keep_connected"
        private const val NOTIFICATION_ID = 4082
        private const val SIGN_IN_NOTIFICATION_ID = 4083
        private const val ACTION_START = "dev.hermes.android.push.KEEP_CONNECTED_START"
        private const val ACTION_STOP = "dev.hermes.android.push.KEEP_CONNECTED_STOP"
        private const val EXTRA_STOP_TOKEN = "stop_token"

        @Volatile internal var clientFactoryForTest: ((AuthContext, AuthStore) -> CypherClient)? = null
        @Volatile private var activeInstance: KeepConnectedService? = null

        private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_ENABLED, false)
        fun status(context: Context): String = preferences(context).getString(KEY_STATUS, STATUS_OFF) ?: STATUS_OFF
        fun isEnabledFor(context: Context, scope: Scope): Boolean {
            val prefs = preferences(context)
            return prefs.getBoolean(KEY_ENABLED, false) && prefs.getString(KEY_ORIGIN, null) == scope.origin &&
                prefs.getString(KEY_OWNER, null) == scope.owner
        }

        fun setEnabled(context: Context, auth: AuthContext?, enabled: Boolean) {
            if (!enabled) {
                disablePreference(context)
                (context.applicationContext as? CypherApplication)?.push?.invalidateKeepConnectedInvitations()
                context.applicationContext.stopService(Intent(context.applicationContext, KeepConnectedService::class.java))
                return
            }
            val current = requireNotNull(auth) { "Sign in with Hermes before enabling Keep connected" }
            val authorized = current.cachedScopeFor(current.origin, replacingAccount = false)
            require(authorized != null && authorized.owner == current.owner) { "Sign in with Hermes before enabling Keep connected" }
            val prefs = preferences(context)
            check(prefs.edit().putBoolean(KEY_ENABLED, true).putString(KEY_ORIGIN, current.origin)
                .putString(KEY_OWNER, current.owner).putString(KEY_STATUS, STATUS_STARTING)
                .putString(KEY_STOP_TOKEN, UUID.randomUUID().toString()).commit()) { "Could not enable Keep connected" }
            start(context)
        }

        fun startIfEnabled(context: Context) {
            val prefs = preferences(context)
            if (!prefs.getBoolean(KEY_ENABLED, false)) return
            val origin = prefs.getString(KEY_ORIGIN, null).orEmpty()
            val owner = prefs.getString(KEY_OWNER, null).orEmpty()
            val app = context.applicationContext as? CypherApplication ?: return
            val auth = app.auth.read(origin)
            val scope = auth.cachedScopeFor(origin, replacingAccount = false)
            if (origin.isBlank() || owner.isBlank() || scope?.owner != owner) {
                setStatus(context, STATUS_SIGNED_OUT)
                app.push.invalidateKeepConnectedInvitations()
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
            (context.applicationContext as? CypherApplication)?.push?.invalidateKeepConnectedInvitations()
            context.applicationContext.stopService(Intent(context.applicationContext, KeepConnectedService::class.java))
        }

        internal fun createClient(app: CypherApplication, auth: AuthContext): CypherClient =
            clientFactoryForTest?.invoke(auth, app.auth) ?: CypherClient(auth, app.auth, persistContext = false)

        private fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context.applicationContext, Intent(context.applicationContext, KeepConnectedService::class.java).setAction(ACTION_START))
            } catch (_: Exception) {
                setStatus(context, STATUS_RECONNECTING)
            }
        }

        private fun setStatus(context: Context, status: String) {
            preferences(context).edit().putString(KEY_STATUS, status).apply()
            activeInstance?.updateNotification()
        }

        private fun disablePreference(context: Context) {
            val prefs = preferences(context)
            check(prefs.edit().putBoolean(KEY_ENABLED, false).putString(KEY_STATUS, STATUS_OFF).commit()) { "Could not stop Keep connected" }
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
            val open = Intent(context, CypherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, requestCode, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
