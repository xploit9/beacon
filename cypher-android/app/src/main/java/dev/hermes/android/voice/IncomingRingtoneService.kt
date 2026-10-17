package dev.hermes.android.voice

import android.app.*
import android.content.*
import android.media.*
import android.os.IBinder
import android.os.Vibrator
import android.os.VibrationEffect
import dev.hermes.android.core.digest
import dev.hermes.android.CypherApplication
import dev.hermes.android.push.incomingCallChannel
import kotlinx.coroutines.*

/** Owns ringing independently of notification alert-once behavior; never opens the microphone. */
class IncomingRingtoneService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var ringtone: Ringtone? = null
    private var vibrating = false
    private val vibrator get() = getSystemService(Vibrator::class.java)
    @Volatile private var source = ""
    @Volatile private var event = ""
    private fun stopTone() {
        val previous = ringtone
        ringtone = null
        try { previous?.stop() }
        catch (error: RuntimeException) { android.util.Log.w("CypherRinging", "Ringtone cleanup failed", error) }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.coroutineContext.cancelChildren()
        stopTone(); vibrator.cancel(); vibrating = false
        source = intent?.getStringExtra("push_source").orEmpty()
        event = intent?.getStringExtra("push_event").orEmpty()
        val app = application as CypherApplication
        val invite = app.push.ringingInvitation(source, event) ?: run { stopSelf(); return START_NOT_STICKY }
        if(!callbackMayAlert(app.calls.state.value.active,getSystemService(AudioManager::class.java).mode==AudioManager.MODE_NORMAL)) { stopSelf();return START_NOT_STICKY }
        if (suppressed(source, event)) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        val posted = intent?.getParcelableExtra<Notification>("notification") ?: run { stopSelf(); return START_NOT_STICKY }
        val notification = androidx.core.app.NotificationCompat.Builder(this, posted)
            .setTimeoutAfter(invite.ringDurationMillis().coerceAtLeast(1)).build()
        try { startForeground(42, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL) }
        catch (error: RuntimeException) {
            android.util.Log.w("CypherRinging", "Phone-call foreground launch rejected; retaining silent call card", error)

            stopSelf(); return START_NOT_STICKY
        }
        active?.takeIf { it !== this }?.stopSelf()
        active = this
        manager.cancel("$source\u0000$event".digest(), 2)
        scope.coroutineContext.cancelChildren()
        scope.launch {
            try {
                val duration = invite.ringDurationMillis()
                val ringUntil = android.os.SystemClock.elapsedRealtime() + duration
                withTimeoutOrNull(duration) {
                    while (isActive && android.os.SystemClock.elapsedRealtime() < ringUntil && !suppressed(source, event) && app.push.invitation(source, event) == invite) {
                        val channel = manager.getNotificationChannel(incomingCallChannel(manager))
                        val audio = getSystemService(AudioManager::class.java)
                        if(!callbackMayAlert(app.calls.state.value.active,audio.mode==AudioManager.MODE_NORMAL)) { stopTone();vibrator.cancel();vibrating=false;break }
                        val allowed = mayRing(manager.areNotificationsEnabled(), channel?.importance ?: 0,
                            channel?.sound != null, audio.ringerMode == AudioManager.RINGER_MODE_NORMAL,
                            audio.getStreamVolume(AudioManager.STREAM_RING), manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
                        val mayVibrate = manager.areNotificationsEnabled() && (channel?.importance ?: 0) >= NotificationManager.IMPORTANCE_DEFAULT && channel?.shouldVibrate() == true && audio.ringerMode != AudioManager.RINGER_MODE_SILENT && manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
                        if (mayVibrate && !vibrating) {
                            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 1000), 0)); vibrating = true
                        } else if (!mayVibrate && vibrating) { vibrator.cancel(); vibrating = false }
                        if (!allowed) { stopTone() }
                        else if (ringtone == null) {
                            ringtone = RingtoneManager.getRingtone(this@IncomingRingtoneService, channel!!.sound)?.apply {
                                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                                isLooping = true
                                play()
                            }
                        }
                        delay(250)
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: RuntimeException) { android.util.Log.w("CypherRinging", "Native ringtone unavailable", error) }
            finally { stopTone(); vibrator.cancel(); app.push.expireInvitation(invite.source, invite.event); stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        scope.cancel(); stopTone(); vibrator.cancel(); vibrating = false
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
        internal fun playback(source: String, event: String): Pair<Boolean, Boolean>? =
            active?.takeIf { it.source == source && it.event == event }?.ringtone?.let { it.isPlaying to it.isLooping }
        fun silence(source: String, event: String) {
            silenced["$source\u0000$event"] = android.os.SystemClock.elapsedRealtime() + INCOMING_RING_SECONDS * 1000
            // Push/storage callbacks can run off-main; serialize with Service lifecycle and a new invitation.
            active?.let { service -> service.scope.launch {
                if (service.source == source && service.event == event) {
                    service.stopTone(); service.vibrator.cancel(); service.stopSelf()
                }
            } }
        }
    }
}

internal fun mayRing(notifications: Boolean, importance: Int, sound: Boolean, normalRinger: Boolean, volume: Int, dndAllows: Boolean): Boolean =
    notifications && importance >= NotificationManager.IMPORTANCE_DEFAULT && sound && normalRinger && volume > 0 && dndAllows
