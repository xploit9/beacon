package dev.hermes.android.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G3F: "Open Beacon to finish answering Beacon." was left on the process-wide call state by a call test and shown over the approval buttons of a
 * chat opened minutes later. A line a call leaves behind belongs to a call nobody can answer once its ring window is over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallLineTest {
    private val line = "Open Beacon to finish answering Beacon."
    private val lifetime = 20_000L

    @Test fun theRingWindowIsTheLifeOfALineByDefault() {
        assertEquals(RelayCallback.RING_WINDOW_MS, CallLine.lifetimeMs())
        assertEquals(20_000L, CallLine.lifetimeMs())
    }

    @Test fun aLineLeftByARefusedStartFadesWhenItsRingWindowIsOver() = runTest {
        val state = MutableStateFlow(CallState(error = line))
        val fading = launch { fadeIdleLines(state) { lifetime } }
        runCurrent()
        advanceTimeBy(lifetime - 1)
        runCurrent()
        assertEquals("Still there just before the window ends", line, state.value.error)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("Gone once the window is over", "", state.value.error)
        fading.cancel()
    }

    @Test fun aLineThatArrivesLaterGetsItsOwnFullLife() = runTest {
        val state = MutableStateFlow(CallState())
        val fading = launch { fadeIdleLines(state) { lifetime } }
        runCurrent()
        advanceTimeBy(lifetime * 3)
        runCurrent()
        state.update { it.copy(error = line) }
        runCurrent()
        advanceTimeBy(lifetime - 1)
        runCurrent()
        assertEquals(line, state.value.error)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("", state.value.error)
        fading.cancel()
    }

    @Test fun aNewerLineIsNotClearedByTheTimerOfAnOlderOne() = runTest {
        val state = MutableStateFlow(CallState(error = "Couldn't start the call"))
        val fading = launch { fadeIdleLines(state) { lifetime } }
        runCurrent()
        advanceTimeBy(lifetime - 5_000)
        state.update { it.copy(error = line) }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("The older line's time is up, the newer line's is not", line, state.value.error)
        advanceTimeBy(lifetime)
        runCurrent()
        assertEquals("", state.value.error)
        fading.cancel()
    }

    @Test fun aCallThatStartsBeforeTheLineFadesIsNeverTouched() = runTest {
        val state = MutableStateFlow(CallState(error = line))
        val fading = launch { fadeIdleLines(state) { lifetime } }
        runCurrent()
        advanceTimeBy(5_000)
        state.value = CallState(active = true, state = "connecting", callId = "call-1")
        runCurrent()
        advanceTimeBy(lifetime * 2)
        runCurrent()
        assertTrue(state.value.active)
        assertEquals("call-1", state.value.callId)
        fading.cancel()
    }

    @Test fun anErrorOnALiveCallStaysForTheCallPanel() = runTest {
        val state = MutableStateFlow(CallState(active = true, state = "listening", callId = "call-1", error = "Camera didn't start."))
        val fading = launch { fadeIdleLines(state) { lifetime } }
        runCurrent()
        advanceTimeBy(lifetime * 3)
        runCurrent()
        assertEquals("Camera didn't start.", state.value.error)
        fading.cancel()
    }

    @Test fun aFailedCallKeepsItsReasonUntilTheOwnerClosesIt() = runTest {
        val state = MutableStateFlow(CallState(callId = "call-1", failureReason = "Check the API key", error = "Check the API key"))
        val fading = launch { fadeIdleLines(state) { lifetime } }
        runCurrent()
        advanceTimeBy(lifetime * 3)
        runCurrent()
        assertEquals("Check the API key", state.value.failureReason)
        assertEquals("Check the API key", state.value.error)
        fading.cancel()
    }

    @Test fun onlyTheLineIsClearedAndNothingElseOfTheCallState() = runTest {
        val state = MutableStateFlow(CallState(callId = "call-1", transcriptStatus = "saved", error = line))
        val fading = launch { fadeIdleLines(state) { lifetime } }
        runCurrent()
        advanceTimeBy(lifetime + 1)
        runCurrent()
        assertEquals("", state.value.error)
        assertEquals("call-1", state.value.callId)
        assertEquals("saved", state.value.transcriptStatus)
        fading.cancel()
    }

    @Test fun idleLineIsTheErrorOnlyWhenNoCallIsLiveAndNoFailureIsKept() {
        assertEquals(line, CallState(error = line).idleLine())
        assertEquals("", CallState(active = true, error = line).idleLine())
        assertEquals("", CallState(failureReason = "Check the API key", error = "Check the API key").idleLine())
        assertEquals("", CallState().idleLine())
    }

    @Test fun aLineIsNewTheFirstTimeAScreenAsksAndNotWhenAnotherScreenAsksLater() {
        val once = LineOnce()
        assertTrue("The first screen shows it", once.isNew(line))
        assertFalse("A screen that opens later does not show it again", once.isNew(line))
        assertFalse(once.isNew(line))
    }

    @Test fun aLineIsNewAgainOnceItHasCleared() {
        val once = LineOnce()
        assertTrue(once.isNew(line))
        assertFalse("Nothing to show while there is no line", once.isNew(""))
        assertTrue("The same words after the line cleared are a new line", once.isNew(line))
    }

    @Test fun aDifferentLineIsNewEvenWhileTheLastOneIsStillThere() {
        val once = LineOnce()
        assertTrue(once.isNew(line))
        assertTrue(once.isNew("Couldn't start the call"))
        assertFalse(once.isNew("Couldn't start the call"))
    }
}
