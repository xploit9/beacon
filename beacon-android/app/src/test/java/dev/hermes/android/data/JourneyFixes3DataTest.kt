package dev.hermes.android.data

import dev.hermes.android.core.json
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class JourneyFixes3DataTest {
    @Test fun aSendThatFailedWhileConnectingNeverLeftThePhone() {
        assertTrue(requestNeverSent(UnknownHostException("hermes.example")))
        assertTrue(requestNeverSent(ConnectException("Connection refused")))
        // The gateway wraps a failed socket connect; the cause still says it was only a connect.
        assertTrue(requestNeverSent(IOException("Native connection interrupted", ConnectException("failed"))))
        assertTrue(requestNeverSent(SocketTimeoutException("failed to connect to /100.64.0.1 (port 443) after 15000ms")))
    }

    @Test fun aSendThatMayHaveReachedHermesStaysUncertain() {
        assertFalse(requestNeverSent(IOException(NATIVE_OUTCOME_UNKNOWN, ConnectException("closed"))))
        assertFalse(requestNeverSent(IOException(NATIVE_OUTCOME_UNKNOWN, java.util.concurrent.TimeoutException())))
        assertFalse(requestNeverSent(SocketTimeoutException("timeout")))
        assertFalse(requestNeverSent(IOException("Native connection closed (1006)")))
        assertFalse(requestNeverSent(ApiFailure(503, "busy")))
    }

    @Test fun aRejectedModelGetsOneShortLine() {
        // TURN-121: trying again cannot change a rejection, so the line names the fix only.
        val line = "The model rejected this request. Pick another model."
        for (code in listOf("model_not_found", "model_entitlement", "provider_policy_blocked", "format_error")) {
            val event = json("status" to "error", "error" to "HTTP 400 model_not_supported", "error_surface" to json("layer" to "provider", "code" to code, "retryable" to false))
            assertEquals(code, line, turnErrorText(event))
        }
    }

    @Test fun aRateLimitSaysTheModelIsBusyAndOtherFailuresKeepTheirGenericText() {
        // TURN-121 (CHATQA-121 D3): Hermes' failure code is no longer dropped; a frame that names no known code keeps the generic line.
        val rateLimited = json("error" to "HTTP 429", "error_surface" to json("layer" to "provider", "code" to "rate_limit", "retryable" to true))
        assertEquals("The model is busy. Try again in a moment.", turnErrorHeadline(turnErrorText(rateLimited)))
        assertEquals(apiFailureText(500, "boom"), turnErrorText(json("message" to "boom")))
        assertEquals(apiFailureText(500, ""), turnErrorText(json()))
        // TURN-121 follow-up 1 (decision 7): a dropped stream has its own line.
        assertEquals("The connection to the model dropped. Try again.", turnErrorHeadline(turnErrorText(json("message" to "stream_drop", "error_surface" to json("layer" to "streaming", "code" to "stream_drop", "retryable" to true)))))
    }
}
