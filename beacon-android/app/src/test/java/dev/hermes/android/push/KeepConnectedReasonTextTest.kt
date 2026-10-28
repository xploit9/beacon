package dev.hermes.android.push

import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.NativeAuthExpiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class KeepConnectedReasonTextTest {
    // Every reason goes through the real keepConnectedFailureReason, so rewording one there fails here.
    private fun say(error: Throwable, profile: String = "default") = keepConnectedReasonText(keepConnectedFailureReason(profile, error))

    @Test fun eachStoredReasonReadsInShortUserWords() {
        assertEquals("Sign in again", say(NativeAuthExpiredException()))
        assertEquals("Sign in again", say(ApiFailure(401, "")))
        assertEquals("Hermes didn't answer. Retrying…", say(InterruptedIOException()))
        assertEquals("Can't reach Hermes. Retrying…", say(IOException()))
        assertEquals("Hermes refused the connection. Retrying…", say(ApiFailure(403, "")))
        assertEquals("Hermes refused the connection. Retrying…", say(ApiFailure(409, "")))
        assertEquals("Check the Beacon plugin on Hermes.", say(ApiFailure(404, "")))
        listOf(500, 503, 418).forEach { assertEquals("Hermes had a problem. Retrying…", say(ApiFailure(it, ""))) }
        assertEquals("Hermes had a problem. Retrying…", say(KeepConnectedPageException()))
        assertEquals("Hermes had a problem. Retrying…", say(IllegalStateException()))
        assertEquals("Hermes had a problem. Retrying…", keepConnectedReasonText("Hermes returned no authorized profiles"))
    }

    @Test fun noStoredReasonJustSaysRetrying() {
        assertEquals("Retrying…", keepConnectedReasonText(""))
    }

    @Test fun aProfileNameCannotPassForAStatusOrASentence() {
        val reason = keepConnectedFailureReason("work", ApiFailure(403, ""), "Sign-in expired (401)")
        assertEquals("Hermes refused the connection. Retrying…", keepConnectedReasonText(reason))
    }

    @Test fun reconnectingWithANetworkSaysWhyAndWithoutOneKeepsTheGenericLine() {
        val reason = keepConnectedFailureReason("default", IOException())
        assertEquals("Reconnecting…" to "Can't reach Hermes. Retrying…",
            keepConnectedNotificationText(KeepConnectedService.STATUS_RECONNECTING, "Cypher", reason, online = true))
        assertEquals("Reconnecting…" to "Cypher can call you again once you're back online.",
            keepConnectedNotificationText(KeepConnectedService.STATUS_RECONNECTING, "Cypher", reason, online = false))
    }

    @Test fun otherStatusesIgnoreTheReasonAndTheNetworkAndKeepNamingTheAssistant() {
        for (status in listOf("off", "starting", "connected", "signed_out")) {
            for (online in listOf(true, false)) {
                val text = keepConnectedNotificationText(status, "Cypher", "Could not reach Hermes", online)
                assertEquals(status, keepConnectedText(status, "Cypher"), text)
                assertFalse(status, "Hermes" in text.first || "Hermes" in text.second)
            }
        }
        assertTrue("Cypher" in keepConnectedNotificationText(KeepConnectedService.STATUS_CONNECTED, "Cypher", "", true).second)
    }
}
