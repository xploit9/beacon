package dev.hermes.android.ui

import org.junit.Assert.*
import org.junit.Test
import dev.hermes.android.voice.BeaconCallService
import dev.hermes.android.push.BeaconMessagingService
import dev.hermes.android.updates.UpdateManager

class BeaconIdentityTest {
    @Test fun activeNotificationIdsRemainStable() {
        assertEquals("xara-calls", BeaconCallService.CHANNEL)
        assertEquals("xara-incoming-call-v2", BeaconMessagingService.INCOMING_CHANNEL)
        assertEquals("hermes-updates", UpdateManager.CHANNEL)
        assertEquals("hermes.call.HANGUP", BeaconCallService.HANGUP)
    }
}
