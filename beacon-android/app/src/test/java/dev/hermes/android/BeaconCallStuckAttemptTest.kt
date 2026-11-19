package dev.hermes.android

import dev.hermes.android.data.APP_CONNECTION_ERROR
import dev.hermes.android.voice.CALL_HERMES_NO_ANSWER
import dev.hermes.android.voice.GRANT_REACH_MS
import dev.hermes.android.voice.HermesUnreachable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REVIEW-121B on fix 5 (16 Nov 2026), its proofs inverted (Review121bTest in the review-121b worktree passed while each defect was there).
 * P2-1: after a black hole, "Call again" followed the press's own connect, still hung past the outage (OkHttp: connect timeout 15 s, read
 * timeout 60 s), and said "Hermes didn't answer" although Hermes was back. P3-1: a reach read stuck in a host lookup held the 3 s reach
 * budget as long as the lookup. Times are the coroutine test clock, except the lookups (real threads).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BeaconCallStuckAttemptTest {
    private val origin = "https://hermes.example.com"

    /** Signed in from this phone's saved sign-in, a connection attempt running (connect() reads loading meanwhile), no client yet. */
    private fun stuck() = AppState(origin = origin, authenticated = true, connected = false, restoringSession = false, loading = true)

    /** The app as the wait sees it: [restart] starts a new attempt (connect()), which connects [connectsAfterMs] later, or never (null). */
    private class App(scope: TestScope, initial: AppState, connectsAfterMs: Long?) {
        val ui = MutableStateFlow(initial)
        var client = false
        var restarts = 0
        val restart: suspend () -> Boolean = {
            restarts++
            ui.value = ui.value.copy(loading = true)
            if (connectsAfterMs != null) scope.launch { delay(connectsAfterMs); client = true; ui.value = ui.value.copy(connected = true, loading = false) }
            true
        }
    }

    @Test fun callAgainAfterHermesIsBackStartsTheEndedAttemptOverAndConnects() = runTest {
        // Review121bTest (a): the old connect ends 9 s after the tap; Hermes answers the reach read. It said "didn't answer" at 9 s. (An old attempt
        // that showed Hermes no progress is started over sooner, at the 3 s bound: anAttemptThatShowedNoProgress... This one did, so only its end
        // starts it over.)
        val app = App(this, stuck(), connectsAfterMs = 1_500)
        launch { delay(9_000); app.ui.value = app.ui.value.copy(loading = false) }
        val ended = awaitCallConnection(app.ui, { app.client }, 15_000, reach = { hermesReachFailure { } }, restart = app.restart, progressed = { true })
        assertEquals(CallConnection.Up, ended)
        assertEquals("one new attempt, once the old one ended", 1, app.restarts)
        assertEquals(10_500L, currentTime)
    }

    @Test fun anAttemptKnownStuckInTheOutageIsStartedOverAsSoonAsHermesAnswers() = runTest {
        // Review121bTest (b): a request stalled after its connect (read timeout 60 s): three Call agains in a row each waited 15 s and failed.
        // An attempt that was in flight while a press found Hermes unreachable is stuck in that outage (callBeacon passes stallMs = 0).
        repeat(3) {
            val tap = currentTime
            val app = App(this, stuck(), connectsAfterMs = 1_500)
            val ended = awaitCallConnection(app.ui, { app.client }, 15_000, reach = { hermesReachFailure { delay(300) } }, restart = app.restart, stallMs = 0)
            assertEquals(CallConnection.Up, ended)
            assertEquals(1, app.restarts)
            assertEquals("Hermes answered at 0.3 s, the new attempt connected 1.5 s later", 1_800L, currentTime - tap)
        }
    }

    @Test fun anyAttemptStillLoadingWellAfterHermesAnsweredIsStartedOverOnce() = runTest {
        val app = App(this, stuck(), connectsAfterMs = 1_500)
        val ended = awaitCallConnection(app.ui, { app.client }, 15_000, reach = { hermesReachFailure { delay(300) } }, restart = app.restart)
        assertEquals(CallConnection.Up, ended)
        assertEquals(1, app.restarts)
        assertEquals("the bound: ${BEACON_CALL_STALL_MS} ms after Hermes answered, then the new attempt", 300 + BEACON_CALL_STALL_MS + 1_500, currentTime)
        assertTrue("inside the press's own limit", currentTime < 15_000)
    }

    @Test fun aSlowLinkOrABusyHermesIsNotCut() = runTest {
        // Every byte 1 s late (device, fix 5, diag-1): the reach read's handshake answered within its budget, the app's connect 2.2 s later.
        val slow = App(this, stuck(), connectsAfterMs = null)
        launch { delay(2_500 + 2_200); slow.client = true; slow.ui.value = slow.ui.value.copy(connected = true, loading = false) }
        assertEquals(CallConnection.Up, awaitCallConnection(slow.ui, { slow.client }, 15_000, freshAttempt = true, reach = { hermesReachFailure { delay(2_500) } }, restart = slow.restart))
        assertEquals(0, slow.restarts)
        assertEquals(4_700L, currentTime)
        // A Hermes that answers at once but takes 9.9 s to let the app in is not started over either (BeaconCallReachTest: 9 s): the attempt's
        // own connection reached Hermes during the press, so it shows progress.
        val start = currentTime
        val busy = App(this, stuck(), connectsAfterMs = null)
        launch { delay(300 + 9_900); busy.client = true; busy.ui.value = busy.ui.value.copy(connected = true, loading = false) }
        assertEquals(CallConnection.Up, awaitCallConnection(busy.ui, { busy.client }, 15_000, freshAttempt = true, reach = { hermesReachFailure { delay(300) } }, restart = busy.restart,
            progressed = { true }))
        assertEquals(0, busy.restarts)
        assertEquals(10_200L, currentTime - start)
    }

    @Test fun aConnectHermesServesSlowlyAfterItsHandshakeIsNotCut() = runTest {
        // REVIEW-F7 P3-3: the handshake is answered at 0.3 s (TLS ends at a front proxy) and Hermes serves the connect 11 s later. Fix 7 started it
        // over at 10.3 s and failed at 16.3 s; the attempt reached Hermes during the press, so it shows progress and is never started over.
        val app = App(this, stuck(), connectsAfterMs = null)
        launch { delay(11_300); app.client = true; app.ui.value = app.ui.value.copy(connected = true, loading = false) }
        val ended = awaitCallConnection(app.ui, { app.client }, 15_000, freshAttempt = true, reach = { hermesReachFailure { delay(300) } }, restart = app.restart,
            progressed = { true })
        assertEquals(CallConnection.Up, ended)
        assertEquals(0, app.restarts)
        assertEquals(11_300L, currentTime)
    }

    @Test fun anAttemptThatShowedNoProgressIsStartedOverThreeSecondsAfterHermesAnswers() = runTest {
        // REVIEW-F7 P3-1: an unmarked stuck attempt (a drop that lost the answer, say) is started over 3 s after Hermes answered, not 10 s.
        val app = App(this, stuck(), connectsAfterMs = 1_500)
        val ended = awaitCallConnection(app.ui, { app.client }, 15_000, reach = { hermesReachFailure { delay(300) } }, restart = app.restart)
        assertEquals(CallConnection.Up, ended)
        assertEquals(1, app.restarts)
        assertEquals(300L + 3_000 + 1_500, currentTime)
    }

    @Test fun aMarkedAttemptThatShowsProgressAgainIsNotStartedOver() = runTest {
        // A marked attempt whose held connection came back (it reached Hermes during this press) goes on: progress beats the mark.
        val app = App(this, stuck(), connectsAfterMs = 1_500)
        launch { delay(2_000); app.client = true; app.ui.value = app.ui.value.copy(connected = true, loading = false) }
        val ended = awaitCallConnection(app.ui, { app.client }, 15_000, reach = { hermesReachFailure { delay(300) } }, restart = app.restart, stallMs = 0,
            progressed = { true })
        assertEquals(CallConnection.Up, ended)
        assertEquals(0, app.restarts)
        assertEquals(2_000L, currentTime)
    }

    @Test fun anAttemptStillLoadingWhenThePressGivesUpIsStartedOverThen() = runTest {
        // REVIEW-F8 P3-1: the attempt reached Hermes (progress), Hermes refreshed a lapsed access token (which rotates the refresh token) and
        // the answer was lost on the way back. Fix 8 waited to the limit and left the attempt loading until OkHttp's 60 s read timeout; its
        // replay of the spent token then came after Hermes' 30 s refresh cache, where a provider with reuse detection signs the owner out.
        // The press now starts the attempt over as it gives up: nothing is cut inside the press, and the replay lands inside the cache.
        val app = App(this, stuck(), connectsAfterMs = null)
        val ended = awaitCallConnection(app.ui, { app.client }, 15_000, freshAttempt = true, reach = { hermesReachFailure { delay(300) } }, restart = app.restart,
            progressed = { true })
        assertEquals(CallConnection.Failed(CALL_HERMES_NO_ANSWER), ended)
        assertEquals("started over once, as the press gave up", 1, app.restarts)
        assertEquals(15_000L, currentTime)
    }

    @Test fun oneStartOverAtMostThenTheNewAttemptsOwnEndIsTheAnswer() = runTest {
        val app = App(this, stuck(), connectsAfterMs = null)
        launch {
            delay(2_000); app.ui.value = app.ui.value.copy(loading = false)
            // The new attempt runs a second and ends without connecting.
            delay(1_000); app.ui.value = app.ui.value.copy(loading = false)
        }
        val ended = awaitCallConnection(app.ui, { app.client }, 15_000, reach = { hermesReachFailure { } }, restart = app.restart)
        assertEquals(CallConnection.Failed(CALL_HERMES_NO_ANSWER), ended)
        assertEquals(1, app.restarts)
        assertEquals(3_000L, currentTime)
    }

    @Test fun aLateStartOverStillEndsBeforeTheOverlayGivesUp() = runTest {
        // The old attempt ends at 14 s; the new one never connects. The wait gives it time, but never past its limit + 3 s (the overlay gives up
        // 25 s after the ask, and the chat and the call's start come after the wait).
        // (stallMs out of the way: here the old attempt itself ends late.)
        val app = App(this, stuck(), connectsAfterMs = null)
        launch { delay(14_000); app.ui.value = app.ui.value.copy(loading = false) }
        assertEquals(CallConnection.Failed(CALL_HERMES_NO_ANSWER), awaitCallConnection(app.ui, { app.client }, 15_000, reach = { hermesReachFailure { } }, restart = app.restart, stallMs = 60_000))
        assertEquals("one start-over in the press, then one as it gives up on the new attempt, still loading (REVIEW-F8 P3-1)", 2, app.restarts)
        assertEquals(15_000L + BEACON_CALL_RESTART_EXTRA_MS, currentTime)
        // An early one keeps the press's own limit: at least BEACON_CALL_RESTARTED_MS for the new attempt, here inside the 15 s.
        val start = currentTime
        val early = App(this, stuck(), connectsAfterMs = null)
        launch { delay(4_000); early.ui.value = early.ui.value.copy(loading = false) }
        assertEquals(CallConnection.Failed(CALL_HERMES_NO_ANSWER), awaitCallConnection(early.ui, { early.client }, 15_000, reach = { hermesReachFailure { } }, restart = early.restart,
            stallMs = 60_000))
        assertEquals(15_000L, currentTime - start)
    }

    @Test fun aStartOverTheAppDeclinesLeavesTheWaitAsItWas() = runTest {
        // callBeacon starts one only where the app would reconnect by itself (signed in, not in setup, no client).
        val ui = MutableStateFlow(stuck())
        launch { delay(2_000); ui.value = ui.value.copy(loading = false) }
        var asked = 0
        assertEquals(CallConnection.Failed(CALL_HERMES_NO_ANSWER), awaitCallConnection(ui, { false }, 15_000, reach = { hermesReachFailure { } }, restart = suspend { asked++; false }))
        assertEquals(1, asked)
        assertEquals(2_000L, currentTime)
    }

    @Test fun onlyAnAttemptLeftInFlightByAReadThatGotNowhereIsMarkedStuck() {
        val unreachable = CallConnection.Failed(APP_CONNECTION_ERROR)
        assertEquals("a black hole", 7, stuckAttemptMark(unreachable, readGotSomewhere = false, hasClient = false, loading = true, attempt = 7, marked = -1))
        assertEquals("a slow link whose read missed the budget mid-handshake (REVIEW-121B P3-2)", -1, stuckAttemptMark(unreachable, true, false, true, 7, -1))
        assertEquals("nothing in flight", -1, stuckAttemptMark(unreachable, false, false, false, 7, -1))
        assertEquals("the app has its client", -1, stuckAttemptMark(unreachable, false, true, true, 7, -1))
        assertEquals("Hermes answered", -1, stuckAttemptMark(CallConnection.Failed(CALL_HERMES_NO_ANSWER), false, false, true, 7, -1))
        assertTrue(stuckAttemptMarked(7, 7, hasClient = false, loading = true))
        assertFalse("a newer attempt is not that one", stuckAttemptMarked(7, 8, false, true))
        assertFalse("ended", stuckAttemptMarked(7, 7, false, false))
        assertFalse("connected", stuckAttemptMarked(7, 7, true, true))
    }

    @Test fun thePresssOwnAttemptThatEndsIsTheAnswerAsBefore() = runTest {
        // An attempt this press started (the last one had ended) that ends without connecting while Hermes answers: Hermes did not answer it.
        val app = App(this, stuck(), connectsAfterMs = 1_500)
        launch { delay(500); app.ui.value = app.ui.value.copy(loading = false) }
        val ended = awaitCallConnection(app.ui, { app.client }, 15_000, freshAttempt = true, reach = { hermesReachFailure { } }, restart = app.restart)
        assertEquals(CallConnection.Failed(CALL_HERMES_NO_ANSWER), ended)
        assertEquals(0, app.restarts)
        assertEquals(500L, currentTime)
    }

    @Test fun nothingIsStartedOverWhileHermesCannotBeReached() = runTest {
        val app = App(this, stuck(), connectsAfterMs = 1_500)
        val ended = awaitCallConnection(app.ui, { app.client }, 15_000, reach = { hermesReachFailure { awaitCancellation() } }, restart = app.restart, stallMs = 0)
        assertEquals(CallConnection.Failed(APP_CONNECTION_ERROR), ended)
        assertEquals(0, app.restarts)
        assertEquals(GRANT_REACH_MS, currentTime)
    }

    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { java.io.File(it) }.firstOrNull { java.io.File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run from the Android project"
    }

    /** The view model needs Android to run, so callBeacon's wiring is read from source (as BeaconCallReachTest does). */
    @Test fun aPressStartsAStuckAttemptOverOnlyByTheReconnectRuleAndMarksOneLeftInAnOutage() {
        val call = java.io.File(app, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
            .substringAfter("fun callBeacon() {").substringBefore("private suspend fun beaconChatOpened(")
        val vm = java.io.File(app, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val startOver = vm.substringAfter("private suspend fun startOverAttempt(origin: String): Boolean {").substringBefore("\n    }\n")
        assertTrue("callBeacon starts over through startOverAttempt", call.contains("restart = { startOverAttempt(origin) }"))
        assertTrue("a new attempt only where the app would reconnect by itself (signed in, not in setup, no client)",
            startOver.contains("if (client != null || !reconnectOnNetworkReturn(_ui.value.copy(loading = false))) return false"))
        assertTrue("the superseded attempt's rotation is kept before the new connect (REVIEW-F7 P3-2)",
            startOver.indexOf("keepSupersededRotation(") in 0 until startOver.indexOf("connect(origin)"))
        // Only for an attempt still in flight: an ended attempt's own catch keeps its rotation (an IOException) or keeps none (a refused
        // sign-in). The start-over may run inline with that catch's ui update, before fail() signs out, so the catch marks it ended first.
        assertTrue("only an attempt still in flight", startOver.contains("connectAttempt?.takeIf { it.generation == originGeneration && !it.ended }"))
        val connectCatch = vm.substringAfter("connectAttempt = ConnectAttempt(generation, next, trustedCache, credentialVersion)").substringAfter("        } catch (e: Throwable) {\n")
        assertTrue("connect()'s catch marks its attempt ended before anything else",
            connectCatch.lineSequence().filter { it.isNotBlank() && !it.trim().startsWith("//") }.first().trim() == "connectAttempt?.takeIf { it.generation == generation }?.ended = true")
        assertTrue("progress just before the press counts (a request sent then, waiting on a slow Hermes)",
            call.contains("val waitFrom = progressClockMs() - if (stuck) 0L else BEACON_CALL_PROGRESS_LOOKBACK_MS"))
        assertTrue("an attempt that showed Hermes progress during the press is never cut (REVIEW-F7 P3-3)",
            call.contains("progressed = { connectAttempt?.takeIf { it.generation == originGeneration }?.client?.hermesProgressAt?.let { it >= waitFrom } == true }"))
        assertTrue("an attempt left in flight by a press that found Hermes unreachable is marked",
            call.contains("unreachableAttempt = stuckAttemptMark(connection, readGotSomewhere.get(), client != null, _ui.value.loading, originGeneration, unreachableAttempt)"))
        assertTrue("and the next press starts it over as soon as Hermes answers", call.contains("val stuck = stuckAttemptMarked(unreachableAttempt, originGeneration, client != null, before.loading)")
            && call.contains("stallMs = if (stuck) 0L else BEACON_CALL_STALL_MS"))
        assertTrue("the reach read runs where the wait does not join it, and says whether it reached Hermes' host",
            call.contains("hermesReachFailure { readHermesStatus(origin, viewModelScope) { readGotSomewhere.set(true) } }"))
    }

    private fun blockingDns(ms: Long) = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> { Thread.sleep(ms); throw java.net.UnknownHostException(hostname) }
    }
    private val reads = CoroutineScope(Dispatchers.IO)

    @Test(timeout = 30_000) fun aReadThatReachedHermesHostIsASlowLinkAndOneThatGotNowhereIsNot() = runBlocking {
        // A host that takes the connection and never answers its TLS: no answer within the budget, but its socket was up.
        java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()).use { server ->
            var held: java.net.Socket? = null
            val taker = Thread { held = runCatching { server.accept() }.getOrNull() }.apply { isDaemon = true; start() }
            val loopback = object : okhttp3.Dns { override fun lookup(hostname: String) = listOf(java.net.InetAddress.getLoopbackAddress()) }
            val got = java.util.concurrent.atomic.AtomicBoolean(false)
            val failure = hermesReachFailure { readHermesStatus("https://hermes.example.com:${server.localPort}", reads, loopback) { got.set(true) } }
            assertTrue("no answer in time: $failure", failure is HermesUnreachable)
            assertTrue("its socket was up: a slow link, not a black hole", got.get())
            taker.join(1_000); held?.close()
        }
        val nowhere = java.util.concurrent.atomic.AtomicBoolean(false)
        assertTrue(hermesReachFailure { readHermesStatus(origin, reads, blockingDns(6_000)) { nowhere.set(true) } } is HermesUnreachable)
        assertFalse("a lookup that never ends got nowhere", nowhere.get())
    }

    @Test(timeout = 30_000) fun aStatusReadWhoseClientCannotBeMadeAnswersWithThatFailureAndNothingEscapes() = runBlocking {
        // The read runs where nothing awaits it, so a failure there must be its answer: BeaconClient parses the origin as it is made.
        val uncaught = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val scope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> uncaught.set(e) })
        val answer = kotlinx.coroutines.withTimeoutOrNull(5_000) { runCatching { readHermesStatus("not an origin", scope) }.exceptionOrNull() }
        delay(200)
        assertTrue("the read answered with its failure, not silence: $answer", answer != null && answer !is kotlinx.coroutines.CancellationException)
        assertEquals("nothing escaped the read", null, uncaught.get())
    }

    @Test(timeout = 30_000) fun aReachReadStuckInALookupEndsAtTheReachBudget() = runBlocking {
        // Review121bTest: a 6 s lookup gave its verdict only after >= 5.5 s, against a budget of 3 s.
        val started = System.nanoTime()
        val failure = hermesReachFailure { readHermesStatus(origin, reads, blockingDns(6_000)) }
        val took = (System.nanoTime() - started) / 1_000_000
        assertTrue("unreachable: $failure", failure is HermesUnreachable)
        assertTrue("The reach budget is $GRANT_REACH_MS ms, the verdict came after $took ms", took < GRANT_REACH_MS + 1_500)
    }

    @Test(timeout = 30_000) fun aPressWhoseReachReadSitsInALookupEndsWithItsLineOnTime() = runBlocking {
        // A lookup past the overlay's give-up: the press still says "Can't reach Hermes" at the reach budget.
        val ui = MutableStateFlow(stuck())
        val started = System.nanoTime()
        val ended = awaitCallConnection(ui, { false }, 15_000, reach = { hermesReachFailure { readHermesStatus(origin, reads, blockingDns(8_000)) } }, restart = null)
        val took = (System.nanoTime() - started) / 1_000_000
        assertEquals(CallConnection.Failed(APP_CONNECTION_ERROR), ended)
        assertTrue("said after $took ms", took < GRANT_REACH_MS + 1_500)
        // Review121bTest, scaled down: a 2 s limit returned only after >= 4.5 s while a 5 s lookup ran. It now ends at its limit.
        val again = System.nanoTime()
        val limited = awaitCallConnection(ui, { false }, 2_000, reach = { hermesReachFailure { readHermesStatus(origin, reads, blockingDns(5_000)) } }, restart = null)
        val tookLimited = (System.nanoTime() - again) / 1_000_000
        assertEquals(CallConnection.Failed(CALL_HERMES_NO_ANSWER), limited)
        assertTrue("a 2 s limit returned after $tookLimited ms", tookLimited < 3_500)
    }
}
