package dev.hermes.android.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import dev.hermes.android.data.ApiFailure
import org.json.JSONArray
import org.json.JSONObject
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

    // Owner trial, 7 Nov 2026: the first tool search after a quiet hour timed out and the call said "Discover tools failed".
    private fun tools() = JSONObject().put("tools",JSONArray().put(JSONObject().put("name","get_weather")))
    private fun starting() = JSONObject().put("status","starting").put("tools",JSONArray())

    @Test fun aFirstDiscoverTimeoutIsRetriedOnceAfterAShortPauseAndThenSucceeds() = runTest {
        val pauses=mutableListOf<Long>();val budgets=mutableListOf<Long>();var attempts=0
        val answer=discoverWithRetry({ budget -> budgets+=budget;attempts++;if(attempts==1) throw SocketTimeoutException("Tool discovery timed out") else tools() },{ pauses+=it })
        assertEquals(2,attempts)
        assertEquals("one pause of about two seconds, between the tries",listOf(DISCOVER_RETRY_DELAY_MS),pauses)
        assertEquals(2000L,DISCOVER_RETRY_DELAY_MS)
        assertTrue(answer.has("tools"))
        assertTrue("the first try keeps the phone's whole patience, the retry less",budgets[1]<budgets[0])
    }

    @Test fun aGatewayTimeoutFromThePluginIsRetriedOnce() = runTest {
        var attempts=0
        val answer=discoverWithRetry({ attempts++;if(attempts==1) throw ApiFailure(504,"Tool lookup still settling") else tools() },{})
        assertEquals(2,attempts);assertTrue(answer.has("tools"))
    }

    @Test fun aStillStartingAnswerIsRetriedOnceAndThenReportedAsStartingNeverAsFailed() = runTest {
        var attempts=0
        val pauses=mutableListOf<Long>()
        val ready=discoverWithRetry({ attempts++;if(attempts==1) starting() else tools() },{ pauses+=it })
        assertEquals(2,attempts);assertEquals(1,pauses.size);assertTrue(ready.has("tools"))
        attempts=0
        val stillStarting=discoverWithRetry({ attempts++;starting() },{})
        assertEquals("one retry, not a loop",2,attempts)
        assertTrue(discoverStarting(stillStarting))
        assertEquals("starting",stillStarting.getString("status"))
        // The retry failing after a "starting" answer is still "starting": the build is running, the tools are not missing.
        attempts=0
        val afterTimeout=discoverWithRetry({ attempts++;if(attempts==1) starting() else throw SocketTimeoutException("late") },{})
        assertTrue(discoverStarting(afterTimeout))
    }

    @Test fun twoTimeoutsAreReportedAsTheTimeout() = runTest {
        var attempts=0
        val error=runCatching { discoverWithRetry({ attempts++;throw SocketTimeoutException("Tool discovery timed out") },{}) }.exceptionOrNull()
        assertEquals(2,attempts)
        assertTrue(error.toString(),error is SocketTimeoutException)
    }

    @Test fun anAnswerAndErrorsThatAreNotTransientAreNeverRetried() = runTest {
        var attempts=0
        assertTrue(discoverWithRetry({ attempts++;tools() },{}).has("tools"));assertEquals(1,attempts)
        for(failure in listOf(ApiFailure(401,"Sign in again"),ApiFailure(503,"Hermes is updating"),ApiFailure(409,"Call closed"),IllegalStateException("Call authority changed"))) {
            attempts=0
            val error=runCatching { discoverWithRetry({ attempts++;throw failure },{}) }.exceptionOrNull()
            assertSame(failure,error);assertEquals("$failure is not retried",1,attempts)
        }
        // The old words for a transient 503 stay unchanged: it is reported at once.
        assertFalse(discoverRetryable(ApiFailure(503,"Temporarily unavailable")))
        assertTrue(discoverRetryable(ApiFailure(504,"x")));assertTrue(discoverRetryable(SocketTimeoutException("x")))
    }

    @Test fun cancellationIsNeverRetried() = runTest {
        var attempts=0
        val cancelled=CancellationException("Owner left")
        val error=runCatching { discoverWithRetry({ attempts++;throw cancelled },{}) }.exceptionOrNull()
        assertEquals(1,attempts);assertEquals(cancelled.message,error?.message)
        attempts=0
        val second=runCatching { discoverWithRetry({ attempts++;if(attempts==1) throw SocketTimeoutException("x") else throw cancelled },{}) }.exceptionOrNull()
        assertEquals(2,attempts);assertTrue(second is CancellationException)
    }

    // Ponytail review, 7 Nov 2026 (finding 7): "still starting" has an end on the phone too.
    @Test fun aCallIsToldStillStartingOnceAndTheSecondRoundInARowIsAFailure() {
        val count=StartingAnswers()
        assertFalse("the first one passes to the model",count.tooMany(starting(),began=0,returned=10))
        assertTrue("the second one, asked after that answer, is a failure",count.tooMany(starting(),began=20,returned=30))
        assertTrue("and every one after it",count.tooMany(starting(),began=40,returned=50))
        assertEquals(2,DISCOVER_STARTING_ANSWERS)
    }

    @Test fun anyOtherAnswerStartsTheStillStartingCountAgain() {
        val count=StartingAnswers()
        assertFalse(count.tooMany(starting(),began=0,returned=10))
        assertFalse("a list resets the count",count.tooMany(tools(),began=20,returned=30))
        assertFalse("so the next cold start is told once again",count.tooMany(starting(),began=40,returned=50))
        assertTrue(count.tooMany(starting(),began=60,returned=70))
    }

    // Ponytail re-review, 7 Nov 2026 (N3): two searches of one turn are one round.
    @Test fun searchesAskedTogetherAreOneRoundAndOnlyASearchAskedAfterTheAnswerIsTheSecond() {
        val count=StartingAnswers()
        assertFalse("mail, asked at 0",count.tooMany(starting(),began=0,returned=10))
        assertFalse("calendar, asked at 0 as well, answered a little later: the same round",count.tooMany(starting(),began=1,returned=11))
        assertFalse("and a third asked before the first answer came back is still that round",count.tooMany(starting(),began=9,returned=12))
        assertTrue("the model's next search, asked after the first answer, is the second round",count.tooMany(starting(),began=10,returned=20))
    }

    @Test fun aSearchAskedWhileAnEarlierOneIsStillOutButAfterTheCountedAnswerIsTheSecondRound() {
        val count=StartingAnswers()
        assertFalse(count.tooMany(starting(),began=0,returned=10))
        // Asked at 11, after the first answer, though a slower search of the first turn is still out until 15.
        assertTrue(count.tooMany(starting(),began=11,returned=20))
    }

    @Test fun theStillStartingHintAsksForOneMoreSearchAndThenHandsOff() {
        assertTrue(DISCOVER_STILL_STARTING.contains("once more"))
        assertTrue(DISCOVER_STILL_STARTING.contains("still starting, call hermes_work"))
    }

    @Test fun theModelIsToldToHandALongRequestToHermesWorkWhenTheToolListFails() {
        assertTrue(HANDOFF_AFTER_FAILED_LOOKUP.contains("Call hermes_work now"))
        assertTrue(HANDOFF_AFTER_FAILED_LOOKUP.contains("in this same turn"))
        assertTrue(DISCOVER_STILL_STARTING.contains("hermes_work"))
        assertEquals(DISCOVER_STILL_STARTING,callToolHint("discover_tools",starting()))
        assertEquals("",callToolHint("discover_tools",tools()))
    }
}
