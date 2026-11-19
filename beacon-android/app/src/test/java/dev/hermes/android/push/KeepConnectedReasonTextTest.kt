package dev.hermes.android.push

import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.NativeAuthExpiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class KeepConnectedReasonTextTest {
    // Every reason goes through the real keepConnectedFailureReason, so rewording one there fails here.
    private fun say(error: Throwable, profile: String = "default") = keepConnectedReasonText(keepConnectedFailureReason(profile, error))

    @Test fun eachStoredReasonReadsInShortUserWords() {
        assertEquals("Sign in again", say(NativeAuthExpiredException()))
        assertEquals("Sign in again", say(ApiFailure(401, "")))
        // A timeout or a dropped connection does not blame Hermes: the stall is usually the phone's path (KEEP-E, 14 Nov 2026).
        assertEquals("Lost the connection to Hermes. Retrying…", say(InterruptedIOException("timeout")))
        assertEquals("Lost the connection to Hermes. Retrying…", say(SocketTimeoutException("timeout")))
        assertEquals("Lost the connection to Hermes. Retrying…", say(IOException("unexpected end of stream")))
        // A request that never left the phone keeps 1.2.0's line: it is also what the phone sees while Hermes' computer is off.
        for (offline in listOf(ConnectException("Failed to connect"), SocketTimeoutException("connect timed out"),
                UnknownHostException("hermes.example.ts.net"), NoRouteToHostException("No route to host"))) {
            assertEquals(offline.toString(), "Can't reach Hermes. Retrying…", say(offline))
        }
        // 1.2.0's stored timeout reason reads the same until the first cycle after the update replaces it.
        assertEquals("Lost the connection to Hermes. Retrying…", keepConnectedReasonText("Hermes did not answer in time"))
        assertEquals("Hermes refused the connection. Retrying…", say(ApiFailure(403, "")))
        assertEquals("Hermes refused the connection. Retrying…", say(ApiFailure(409, "")))
        assertEquals("Check Beacon on Hermes.", say(ApiFailure(404, "")))
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
        val reason = keepConnectedFailureReason("default", ConnectException("Failed to connect"))
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
