package dev.hermes.android

import android.content.Context
import android.util.Base64
import android.media.AudioManager
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.*
import dev.hermes.android.data.AuthContext
import dev.hermes.android.push.receivePush
import dev.hermes.android.voice.IncomingRingtoneService
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Exercises the application ringing service, not a separate test-created Ringtone. */
class VoiceReadinessTest {
    @Test fun incomingCallUsesChannelAlertAndStopsOnSilenceAndAuthorizationRevocation() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val origin = "https://localhost:8796"
        val old = app.auth.read(origin)
        val source = "native-${UUID.randomUUID()}"
        val event = "ring-${UUID.randomUUID()}"
        val auth = AuthContext(origin, emptyList(), "fixture-owner", "default", backend = "hermes-dashboard", nativeProvider = "fixture")
        val key = "source:${source.digest()}"
        val prefs = app.getSharedPreferences("push-binding", Context.MODE_PRIVATE)
        val binding = json("source" to source, "origin" to origin, "owner" to auth.owner, "profile" to auth.profile, "native_provider" to auth.nativeProvider, "backend" to "hermes-dashboard", "cookies" to org.json.JSONArray())
        val audio = app.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_RING)
        fun waitFor(condition: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 8000
            while (!condition()) { if (android.os.SystemClock.elapsedRealtime() > end) fail("Owned ringtone did not settle"); Thread.sleep(25) }
        }
        app.auth.write(auth)
        prefs.edit().putString(key, Base64.encodeToString(app.cipher.encrypt(binding.toString().toByteArray(), key), Base64.NO_WRAP)).commit()
        val manager = app.getSystemService(NotificationManager::class.java)
        try {
            assertTrue(manager.activeNotifications.none { it.id == 42 })
            receivePush(app, mapOf("source_ref" to source, "event_id" to event, "conversation_ref" to "fixture-beacon", "kind" to "incoming_call", "expires_at" to (System.currentTimeMillis() / 1000 + 60).toString()))
            waitFor { manager.activeNotifications.any { it.id == 42 } }
            val firstCard = manager.activeNotifications.single { it.id == 42 }.notification
            val channel = manager.getNotificationChannel(firstCard.channelId)
            val alerting = dev.hermes.android.push.incomingCallMayAlert(false, audio.mode == AudioManager.MODE_NORMAL,
                true, false, manager.areNotificationsEnabled(), channel?.importance ?: NotificationManager.IMPORTANCE_NONE,
                channel?.sound != null, audio.ringerMode, audio.getStreamVolume(AudioManager.STREAM_RING))
            assertEquals(Notification.CATEGORY_CALL, firstCard.category)
            // setSilent() marks GROUP_ALERT_SUMMARY; Android 15+ swaps its "silent" group for a hidden flag on the posted card.
            assertEquals(alerting, firstCard.groupAlertBehavior != Notification.GROUP_ALERT_SUMMARY)
            assertEquals(alerting, (firstCard.flags and Notification.FLAG_INSISTENT) != 0)
            assertNull("The user-configured call channel is the ringtone source", firstCard.sound)
            assertFalse("Ringing does not start an active microphone call", app.calls.state.value.active)
            val postedAt = manager.activeNotifications.first { it.id == 42 }.postTime
            audio.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            assertFalse(dev.hermes.android.push.incomingCallMayAlert(false, audio.mode == AudioManager.MODE_NORMAL,
                true, false, manager.areNotificationsEnabled(), channel?.importance ?: NotificationManager.IMPORTANCE_NONE,
                channel?.sound != null, audio.ringerMode, audio.getStreamVolume(AudioManager.STREAM_RING)))
            audio.setStreamVolume(AudioManager.STREAM_RING, volume, 0)
            assertEquals("Muting and unmuting keeps the one ringing card; it is not posted again", postedAt, manager.activeNotifications.first { it.id == 42 }.postTime)
            val stops = List(8) { Thread { IncomingRingtoneService.silence(source, event) } }
            stops.forEach { it.start() }; stops.forEach { it.join() }
            waitFor { manager.activeNotifications.none { it.id == 42 } }
            receivePush(app, mapOf("source_ref" to source, "event_id" to "$event-again", "conversation_ref" to "fixture-beacon", "kind" to "incoming_call", "expires_at" to (System.currentTimeMillis() / 1000 + 60).toString()))
            waitFor { manager.activeNotifications.any { it.id == 42 } }
            assertFalse("Incoming ringing never starts the microphone service", app.calls.state.value.active)
            prefs.edit().remove(key).commit()
            waitFor {
                app.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 42 } &&
                    !app.calls.state.value.active
            }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_RING, volume, 0)
            app.push.discardInvitation(source, event); app.push.discardInvitation(source, "$event-again")
            prefs.edit().remove(key).commit(); app.auth.write(old)
        }
    }

    @Test fun endedInvitationStartStopsWithoutCrashingTheApp() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        // An invitation that ends before the service starts must still promote; stopping first crashes the app.
        androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, IncomingRingtoneService::class.java)
            .putExtra("push_source", "ended-${UUID.randomUUID()}").putExtra("push_event", "ended-${UUID.randomUUID()}"))
        // The crash, if any, lands right after onStartCommand; this wait lets it surface before the run reports.
        Thread.sleep(1500)
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 42 })
    }
}
