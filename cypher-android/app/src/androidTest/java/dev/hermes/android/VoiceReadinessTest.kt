package dev.hermes.android

import android.content.Context
import android.util.Base64
import android.media.AudioManager
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
    @Test fun ownedRingingStopsOnMuteAndAuthorizationRevocation() {
        val app = ApplicationProvider.getApplicationContext<CypherApplication>()
        val origin = "https://localhost:8796"
        val old = app.auth.read(origin)
        val source = "native-${UUID.randomUUID()}"
        val event = "ring-${UUID.randomUUID()}"
        val auth = AuthContext(origin, emptyList(), "fixture-owner", "default", backend = "hermes-dashboard")
        val key = "source:${source.digest()}"
        val prefs = app.getSharedPreferences("push-binding", Context.MODE_PRIVATE)
        val binding = json("source" to source, "origin" to origin, "owner" to auth.owner, "profile" to auth.profile, "backend" to "hermes-dashboard", "cookies" to org.json.JSONArray())
        val audio = app.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_RING)
        fun waitFor(condition: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 8000
            while (!condition()) { if (android.os.SystemClock.elapsedRealtime() > end) fail("Owned ringtone did not settle"); Thread.sleep(25) }
        }
        app.auth.write(auth)
        prefs.edit().putString(key, Base64.encodeToString(app.cipher.encrypt(binding.toString().toByteArray(), key), Base64.NO_WRAP)).commit()
        try {
            receivePush(app, mapOf("source_ref" to source, "event_id" to event, "conversation_ref" to "fixture-xara", "kind" to "incoming_call", "expires_at" to (System.currentTimeMillis() / 1000 + 60).toString()))
            waitFor { IncomingRingtoneService.playback(source, event) == (true to true) }
            audio.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            waitFor { IncomingRingtoneService.playback(source, event) == null }
            audio.setStreamVolume(AudioManager.STREAM_RING, volume, 0)
            waitFor { IncomingRingtoneService.playback(source, event) == (true to true) }
            val stops = List(8) { Thread { IncomingRingtoneService.silence(source, event) } }
            stops.forEach { it.start() }; stops.forEach { it.join() }
            waitFor { IncomingRingtoneService.playback(source, event) == null }
            receivePush(app, mapOf("source_ref" to source, "event_id" to "$event-again", "conversation_ref" to "fixture-xara", "kind" to "incoming_call", "expires_at" to (System.currentTimeMillis() / 1000 + 60).toString()))
            waitFor { IncomingRingtoneService.playback(source, "$event-again") == (true to true) }
            prefs.edit().remove(key).commit()
            waitFor {
                app.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 42 } &&
                    IncomingRingtoneService.playback(source, "$event-again") == null
            }
            assertNull(IncomingRingtoneService.playback(source, "$event-again"))
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_RING, volume, 0)
            app.push.discardInvitation(source, event); app.push.discardInvitation(source, "$event-again")
            prefs.edit().remove(key).commit(); app.auth.write(old)
        }
    }
}
