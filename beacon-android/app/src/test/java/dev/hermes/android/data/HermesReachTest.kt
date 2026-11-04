package dev.hermes.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner, 4 Nov 2026: Tailscale dropped and the app never said Hermes was out of reach. */
class HermesReachTest {
    private val origin = "https://reach-fixture.example.test"

    @Test fun aRequestThatCannotConnectMarksHermesUnreachableUntilItAnswers() {
        HermesReach.failed(origin, java.net.ConnectException("Failed to connect"))
        assertTrue(origin in HermesReach.origins.value)
        HermesReach.answered(origin)
        assertFalse(origin in HermesReach.origins.value)
    }

    @Test fun aFailureAfterTheRequestLeftThePhoneIsNotUnreachable() {
        // A reply that timed out or a Hermes error says Hermes is there; only a connection that never opened counts.
        HermesReach.failed(origin, java.net.SocketTimeoutException("timeout"))
        HermesReach.failed(origin, ApiFailure(500, "Hermes failed"))
        assertFalse(origin in HermesReach.origins.value)
        HermesReach.failed(origin, java.io.IOException("Native connection interrupted", java.net.NoRouteToHostException("No route to host")))
        assertTrue(origin in HermesReach.origins.value)
        HermesReach.answered(origin)
    }
}
