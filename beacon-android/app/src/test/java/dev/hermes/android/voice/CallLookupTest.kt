package dev.hermes.android.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class CallLookupTest {
    @Test fun slowAnswerBecomesAVisibleErrorInsteadOfCancellation() = runTest {
        val error=runCatching { callLookup { delay(15001);"Late invitation" } }.exceptionOrNull()
        assertTrue(error.toString(),error is SocketTimeoutException)
        assertTrue(error!!.message.orEmpty().contains("timed out"))
        assertFalse(error is CancellationException)
    }

    @Test fun parentCancellationIsNeverConvertedIntoATimeout() = runTest {
        val cancelled=CancellationException("Owner left")
        val error=runCatching { callLookup<String> { throw cancelled } }.exceptionOrNull()
        // Coroutine stack recovery may copy the cancellation exception.
        assertEquals(cancelled.javaClass,error?.javaClass)
        assertEquals(cancelled.message,error?.message)
        assertTrue(runCatching { withTimeout(1) { callLookup { delay(15001);"Late" } } }.exceptionOrNull() is TimeoutCancellationException)
        assertEquals("Current invitation",callLookup { "Current invitation" })
    }
}
