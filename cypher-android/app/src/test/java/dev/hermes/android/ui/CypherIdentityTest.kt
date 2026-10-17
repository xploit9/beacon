package dev.hermes.android.ui

import org.junit.Assert.*
import org.junit.Test
import dev.hermes.android.voice.CypherCallService
import dev.hermes.android.push.CypherMessagingService
import dev.hermes.android.updates.UpdateManager

class CypherIdentityTest {
    @Test fun retiredDefaultNamesDisplayCypherWithoutRewritingCustomNames() {
        for (name in listOf("", "Hermes", "hermes", "Xara", "xara", "Cipher", "cipher", "Cypher")) assertEquals("Cypher", assistantDisplayName(name))
        assertEquals("My research assistant", assistantDisplayName("My research assistant"))
    }
    @Test fun notificationIdsRemainCompatibleWithInstalledPreferences() {
        assertEquals("xara-calls", CypherCallService.CHANNEL)
        assertEquals("hermes-activity", CypherMessagingService.CHANNEL)
        assertEquals("xara-incoming-call-v2", CypherMessagingService.INCOMING_CHANNEL)
        assertEquals("hermes-updates", UpdateManager.CHANNEL)
        assertEquals("hermes.call.HANGUP", CypherCallService.HANGUP)
    }
}
