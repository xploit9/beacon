package dev.hermes.android.push
import dev.hermes.android.core.json
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.nativeErrorMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class StaleDevicePushTest {
    // Built through nativeErrorMessage, so the failure reads exactly as nativeRequest raises it for the plugin's JSON answer.
    private fun refused(detail: String, status: Int = 403, plugin: Boolean = true) =
        ApiFailure(status, nativeErrorMessage(json("detail" to detail), status), plugin)
    @Test fun anUnknownDeviceIdIsLinkedAgainAndRetried() {
        assertTrue(staleDeviceFailure(refused("device_required")))
    }
    @Test fun aStaleDeviceIsLinkedOnceAndTheRequestSentAgain() = runBlocking {
        val failures = ArrayDeque(listOf(refused("device_required")))
        var sent = 0
        var linked = 0
        val result = retryOnceAfterRelink({ sent++; failures.removeFirstOrNull()?.let { throw it }; "target" }) { linked++ }
        assertEquals("target", result)
        assertEquals(2, sent)
        assertEquals(1, linked)
    }
    @Test fun aSecondStaleFailureIsThrownNotRetriedAgain() = runBlocking {
        var sent = 0
        var linked = 0
        val error = runCatching { retryOnceAfterRelink<String>({ sent++; throw refused("device_required") }) { linked++ } }.exceptionOrNull()
        assertTrue(staleDeviceFailure(error!!))
        assertEquals(2, sent)
        assertEquals(1, linked)
    }
    @Test fun aRemovedPhoneOrOtherFailureIsNeitherLinkedNorRetried() = runBlocking {
        listOf(refused("device_removed"), refused("Owner not authorized"), refused("device_required", status = 500)).forEach { failure ->
            var sent = 0
            var linked = 0
            assertSame(failure, runCatching { retryOnceAfterRelink<String>({ sent++; throw failure }) { linked++ } }.exceptionOrNull())
            assertEquals(1, sent)
            assertEquals(0, linked)
        }
    }
    @Test fun aRemovedPhoneIsRefusedNotLinkedAgain() {
        assertFalse(staleDeviceFailure(refused("device_removed")))
    }
    @Test fun everyOtherFailureIsRefused() {
        listOf(
            refused("device_required", status = 500),
            refused("device_required", plugin = false),
            refused("Owner not authorized"),
            refused("permission_denied"),
            refused("Target capacity reached", status = 409),
        ).forEach { assertFalse(it.message, staleDeviceFailure(it)) }
        assertFalse(staleDeviceFailure(java.io.IOException("device_required")))
    }
}
