package dev.hermes.android.voice

import android.app.*
import android.content.*
import android.media.AudioManager
import android.os.IBinder
import android.os.Vibrator
import android.os.VibrationEffect
import androidx.core.app.NotificationCompat
import dev.hermes.android.core.digest
import dev.hermes.android.CypherApplication
import dev.hermes.android.push.incomingCallCardOptions
import dev.hermes.android.push.incomingCallChannel
import dev.hermes.android.push.incomingCallMayAlert
import dev.hermes.android.push.incomingCallMayVibrate
import kotlinx.coroutines.*

/** Keeps the phone-call foreground slot and deadline; Android's call channel owns the ringtone. Never opens the microphone. */
class IncomingRingtoneService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var vibrating = false
    private val vibrator get() = getSystemService(Vibrator::class.java)
    @Volatile private var source = ""
    @Volatile private var event = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.coroutineContext.cancelChildren()
        vibrator.cancel(); vibrating = false
        source = intent?.getStringExtra("push_source").orEmpty()
        event = intent?.getStringExtra("push_event").orEmpty()
        val app = application as CypherApplication
        val invite = app.push.ringingInvitation(source, event) ?: run { stopSelf(); return START_NOT_STICKY }
        val audio = getSystemService(AudioManager::class.java)
        if (!callbackMayAlert(app.calls.state.value.active, audio.mode == AudioManager.MODE_NORMAL) || suppressed(source, event)) {
            stopSelf(); return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        val posted = intent?.getParcelableExtra<Notification>("notification") ?: run { stopSelf(); return START_NOT_STICKY }
        val duration = invite.ringDurationMillis()
        if (duration <= 0) { app.push.expireInvitation(source, event); stopSelf(); return START_NOT_STICKY }
        val channel = manager.getNotificationChannel(incomingCallChannel(manager))
        val alert = posted.group != "silent" && incomingCallMayAlert(
            activeCall = app.calls.state.value.active,
            audioModeNormal = audio.mode == AudioManager.MODE_NORMAL,
            invitationLive = duration > 0,
            invitationSilenced = suppressed(source, event),
            notificationsEnabled = manager.areNotificationsEnabled(),
            channelImportance = channel?.importance ?: NotificationManager.IMPORTANCE_NONE,
            channelSound = channel?.sound != null,
            ringerMode = audio.ringerMode,
            ringVolume = audio.getStreamVolume(AudioManager.STREAM_RING),
        )
        val options = incomingCallCardOptions(duration, silent = !alert)
        val builder = NotificationCompat.Builder(this, posted)
            .setCategory(options.category)
            .setTimeoutAfter(options.timeoutAfterMillis)
            .setOnlyAlertOnce(options.onlyAlertOnce)
            .setSilent(options.silent)
        if (!options.fullScreenIntent) builder.setFullScreenIntent(null, false)
        val notification = builder.build().apply {
            this.flags = if (options.insistent) this.flags or Notification.FLAG_INSISTENT else this.flags and Notification.FLAG_INSISTENT.inv()
        }
        try { startForeground(42, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL) }
        catch (error: RuntimeException) {
            android.util.Log.w("CypherRinging", "Phone-call foreground launch rejected; retaining the call card", error)
            try { manager.notify("$source\u0000$event".digest(), 2, notification) } catch (_: SecurityException) { }
            stopSelf(); return START_NOT_STICKY
        }
        active?.takeIf { it !== this }?.stopSelf()
        active = this
        manager.cancel("$source\u0000$event".digest(), 2)
        scope.launch {
            try {
                val ringUntil = android.os.SystemClock.elapsedRealtime() + duration
                withTimeoutOrNull(duration) {
                    while (isActive && android.os.SystemClock.elapsedRealtime() < ringUntil &&
                        !suppressed(source, event) && app.push.invitation(source, event) == invite) {
                        val audioNow = getSystemService(AudioManager::class.java)
                        if (!callbackMayAlert(app.calls.state.value.active, audioNow.mode == AudioManager.MODE_NORMAL)) break
                        val currentChannel = manager.getNotificationChannel(incomingCallChannel(manager))
                        val mayVibrate = incomingCallMayVibrate(
                            notificationSilent = options.silent,
                            notificationsEnabled = manager.areNotificationsEnabled(),
                            channelImportance = currentChannel?.importance ?: 0,
                            channelVibrates = currentChannel?.shouldVibrate() == true,
                            ringerMode = audioNow.ringerMode,
                            dndAllows = manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL,
                        )
                        if (mayVibrate && !vibrating) {
                            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 1000), 0)); vibrating = true
                        } else if (!mayVibrate && vibrating) { vibrator.cancel(); vibrating = false }
                        delay(250)
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: RuntimeException) { android.util.Log.w("CypherRinging", "Incoming call alert unavailable", error) }
            finally {
                vibrator.cancel(); vibrating = false
                app.push.expireInvitation(invite.source, invite.event)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel(); vibrator.cancel(); vibrating = false
        if (active === this) active = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        @Volatile private var active: IncomingRingtoneService? = null
        private val silenced = java.util.concurrent.ConcurrentHashMap<String, Long>()

        private fun suppressed(source: String, event: String): Boolean {
            val now = android.os.SystemClock.elapsedRealtime()
            silenced.entries.removeIf { it.value <= now }
            return silenced.containsKey("$source\u0000$event")
        }

        internal fun isSilenced(source: String, event: String): Boolean = suppressed(source, event)

        fun silence(source: String, event: String) {
            silenced["$source\u0000$event"] = android.os.SystemClock.elapsedRealtime() + INCOMING_RING_SECONDS * 1000
            // Push/storage callbacks can run off-main; serialize with Service lifecycle and a new invitation.
            active?.let { service -> service.scope.launch {
                if (service.source == source && service.event == event) {
                    service.vibrator.cancel(); service.vibrating = false; service.stopSelf()
                }
            } }
        }
    }
}
