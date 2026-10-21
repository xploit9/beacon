package dev.hermes.android.voice

import android.app.NotificationManager
import android.media.AudioManager
import dev.hermes.android.push.incomingCallMayAlert
import dev.hermes.android.push.incomingCallMayVibrate
import org.junit.Assert.*
import org.junit.Test

class VoiceReadinessTest {
    @Test fun incomingCallAlertHonorsAudioMuteAndNotificationSuppression() {
        fun mayAlert(
            activeCall: Boolean = false,
            audioModeNormal: Boolean = true,
            invitationLive: Boolean = true,
            invitationSilenced: Boolean = false,
            notificationsEnabled: Boolean = true,
            channelImportance: Int = NotificationManager.IMPORTANCE_HIGH,
            channelSound: Boolean = true,
            ringerMode: Int = AudioManager.RINGER_MODE_NORMAL,
            ringVolume: Int = 5,
        ) = incomingCallMayAlert(activeCall, audioModeNormal, invitationLive, invitationSilenced,
            notificationsEnabled, channelImportance, channelSound, ringerMode, ringVolume)

        assertTrue(mayAlert())
        assertFalse(mayAlert(activeCall = true))
        assertFalse(mayAlert(audioModeNormal = false))
        assertFalse(mayAlert(invitationLive = false))
        assertFalse(mayAlert(invitationSilenced = true))
        assertFalse(mayAlert(notificationsEnabled = false))
        assertFalse(mayAlert(channelImportance = NotificationManager.IMPORTANCE_NONE))
        assertFalse(mayAlert(channelSound = false))
        assertFalse(mayAlert(ringerMode = AudioManager.RINGER_MODE_SILENT))
        assertFalse(mayAlert(ringerMode = AudioManager.RINGER_MODE_VIBRATE))
        assertFalse(mayAlert(ringVolume = 0))

        assertTrue(incomingCallMayVibrate(true, true, NotificationManager.IMPORTANCE_HIGH, true, AudioManager.RINGER_MODE_VIBRATE, true))
        assertTrue(incomingCallMayVibrate(true, true, NotificationManager.IMPORTANCE_HIGH, true, AudioManager.RINGER_MODE_NORMAL, true))
        assertFalse(incomingCallMayVibrate(false, true, NotificationManager.IMPORTANCE_HIGH, true, AudioManager.RINGER_MODE_VIBRATE, true))
        assertFalse(incomingCallMayVibrate(true, false, NotificationManager.IMPORTANCE_HIGH, true, AudioManager.RINGER_MODE_VIBRATE, true))
        assertFalse(incomingCallMayVibrate(true, true, NotificationManager.IMPORTANCE_NONE, true, AudioManager.RINGER_MODE_VIBRATE, true))
        assertFalse(incomingCallMayVibrate(true, true, NotificationManager.IMPORTANCE_HIGH, false, AudioManager.RINGER_MODE_VIBRATE, true))
        assertFalse(incomingCallMayVibrate(true, true, NotificationManager.IMPORTANCE_HIGH, true, AudioManager.RINGER_MODE_SILENT, true))
        assertFalse(incomingCallMayVibrate(true, true, NotificationManager.IMPORTANCE_HIGH, true, AudioManager.RINGER_MODE_VIBRATE, false))
        assertFalse(incomingCallMayVibrate(false, true, NotificationManager.IMPORTANCE_HIGH, true, AudioManager.RINGER_MODE_NORMAL, true))
    }

    @Test fun healthyConnectionsRestoreRecoveryButRapidFlappingStillStops() {
        var now=0L
        val reconnects=VoiceReconnects { now }
        repeat(10) { reconnects.admitted();now+=30_000;reconnects.failed();assertEquals(1,reconnects.failures) }
        repeat(4) { reconnects.admitted();now+=100;reconnects.failed() }
        assertTrue(reconnects.exhausted)
    }

    @Test fun transportCauseNeverIncludesPrivateExceptionMessages() {
        val secret="token=private-secret"
        assertEquals("ssl_handshake",voiceTransportCause(javax.net.ssl.SSLHandshakeException(secret)))
        assertEquals("ssl_peer",voiceTransportCause(javax.net.ssl.SSLPeerUnverifiedException(secret)))
        assertEquals("ssl_protocol",voiceTransportCause(javax.net.ssl.SSLProtocolException(secret)))
        assertEquals("io",voiceTransportCause(java.io.IOException(secret)))
        assertEquals("other",voiceTransportCause(IllegalArgumentException(secret)))
    }

    @Test fun nativeJobApiVocabularyStartsOnlyActiveSubscriptions() {
        assertTrue(voiceNeedsNativeEvents("working"));assertTrue(voiceNeedsNativeEvents("needs_input"))
        for(status in listOf("watching","running","done","failed","cancelled")) assertFalse(voiceNeedsNativeEvents(status))
    }

    @Test fun endedTransportRejectsLateSetupAndPlannedCloseDiagnostics() {
        assertTrue(voiceProviderCurrent(1,1,false,false))
        assertFalse(voiceProviderCurrent(1,1,false,true))
        assertFalse(voiceProviderCurrent(1,2,false,false))
        assertFalse(voiceProviderCurrent(1,1,true,false))
        // goAway completes ended before closing; its echoed onClosed is no longer current.
        assertFalse(voiceProviderCurrent(1,1,false,true))
    }

    @Test fun callbackRejectionsDoNotGuessAboutAmbiguousCronErrors() {
        for(code in listOf(401,403,404,413,429)) assertTrue(callbackDefinitelyRejected(code,"private"))
        assertTrue(callbackDefinitelyRejected(409,"Choose delay_seconds or at"))
        assertTrue(callbackDefinitelyRejected(409,"Scheduled callbacks are disabled on the host"))
        assertFalse(callbackDefinitelyRejected(409,"Callback request_id already used for another request"))
        assertFalse(callbackDefinitelyRejected(400,"provider may have saved job before failing"))
        assertFalse(callbackDefinitelyRejected(503,"failure"));assertFalse(callbackDefinitelyRejected(504,"timeout"))
    }

}
