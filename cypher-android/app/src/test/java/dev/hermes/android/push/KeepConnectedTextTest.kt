package dev.hermes.android.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeepConnectedTextTest {
    @Test fun theNotificationNamesTheAssistantNotHermes() {
        assertEquals("Reconnecting…" to "Dottie can call you again once you're back online.",
            keepConnectedText(KeepConnectedService.STATUS_RECONNECTING, "Dottie"))
        assertEquals("Connected" to "Cypher can call you.", keepConnectedText(KeepConnectedService.STATUS_CONNECTED, "Cypher"))
        for (status in listOf("off", "starting", "connected", "reconnecting", "signed_out")) {
            val (title, body) = keepConnectedText(status, "Dottie")
            assertFalse(status, "Hermes" in title || "Hermes" in body)
        }
    }
}
