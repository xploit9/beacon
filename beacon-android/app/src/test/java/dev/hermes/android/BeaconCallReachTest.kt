package dev.hermes.android

import dev.hermes.android.assist.LineAction
import dev.hermes.android.assist.OverlayLine
import dev.hermes.android.assist.callReason
import dev.hermes.android.assist.lineAction
import dev.hermes.android.assist.retryableCallFailure
import dev.hermes.android.data.APP_CONNECTION_ERROR
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.voice.CALL_HERMES_NO_ANSWER
import dev.hermes.android.voice.CALL_SIGN_IN_EXPIRED
import dev.hermes.android.voice.GRANT_REACH_MS
import dev.hermes.android.voice.HermesUnreachable
import dev.hermes.android.voice.grantFailureText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Device round 4, F3 (16 Nov 2026): a side-button press with Hermes unreachable showed "Connecting" for 15 s and then only "Couldn't start
 * the call". The press's own model connects from cold; with Hermes refusing or silent that connection never came, the press waited its
 * whole 15 s, and the overlay's 25 s give-up said the generic line. A press now asks Hermes directly by the call's reach rule and says, with
 * the call's own line, that Hermes cannot be reached: about a second for a refused or reset connection, the call's reach budget for a black
 * hole. A Hermes that answers but is slow is still waited for. Times are the coroutine test clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BeaconCallReachTest {
    /** Signed in from this phone's saved sign-in, the app's connection to Hermes still being made (connect() reads loading meanwhile). */
    private fun connecting() = AppState(authenticated = true, connected = false, restoringSession = false, loading = true)

    @Test fun aPressThatFindsHermesRefusingSaysItCannotBeReachedWithinAboutASecond() = runTest {
        val ui = MutableStateFlow(connecting())
        // The app's own attempt fails at once too.
        launch { delay(200); ui.value = ui.value.copy(loading = false) }
        val ended = awaitCallConnection(ui, { false }, 15_000, reach = { hermesReachFailure { throw java.net.ConnectException("Connection refused") } })
        assertEquals(CallConnection.Failed(APP_CONNECTION_ERROR), ended)
        assertTrue("Said once the refusal was asked again, not after 15 s: $currentTime ms", currentTime in 1_000..1_500)
    }

    @Test fun aBlackHoledHermesIsSaidAtTheCallsReachBudget() = runTest {
        val ui = MutableStateFlow(connecting())
        val ended = awaitCallConnection(ui, { false }, 15_000, reach = { hermesReachFailure { awaitCancellation() } })
        assertEquals(CallConnection.Failed(APP_CONNECTION_ERROR), ended)
        assertEquals("The grant's own reach budget", GRANT_REACH_MS, currentTime)
    }

    @Test fun everyWayAConnectionFailsMeansHermesCannotBeReached() = runTest {
        // The classes the emulator's proxy cannot make (it answers a CONNECT 502 or holds it), and that one too.
        listOf(java.net.ConnectException("Connection refused"), java.net.SocketException("Connection reset"), java.net.NoRouteToHostException("No route to host"),
            java.net.UnknownHostException("hermes.example.com"), java.net.SocketTimeoutException("connect timed out"),
            java.io.IOException("Unexpected response code for CONNECT: 502"),
        ).forEach { failure -> assertNotNull("${failure.javaClass.simpleName} is a Hermes it cannot reach", hermesReachFailure { throw failure }) }
        // Any answer is Hermes reaching this phone, an error status too.
        assertNull(hermesReachFailure { })
        assertNull(hermesReachFailure { throw ApiFailure(401, "Unauthorized") })
        assertNull(hermesReachFailure { throw ApiFailure(503, "Service unavailable") })
    }

    @Test fun aBlinkIsAskedOnceMoreAndAnAnswerThenCounts() = runTest {
        var reads = 0
        val failure = hermesReachFailure { if (++reads == 1) throw java.net.SocketException("Connection reset") }
        assertNull("Hermes answered the second time", failure)
        assertEquals(2, reads)
        assertEquals(CALL_REACH_RETRY_MS, currentTime)
    }

    @Test fun aSlowButReachableHermesStillConnects() = runTest {
        val ui = MutableStateFlow(connecting())
        var client = false
        launch { delay(9_000); client = true; ui.value = ui.value.copy(connected = true, loading = false) }
        val ended = awaitCallConnection(ui, { client }, 15_000, reach = { hermesReachFailure { delay(400) } })
        assertEquals(CallConnection.Up, ended)
        assertEquals(9_000L, currentTime)
    }

    @Test fun aHermesThatAnswersButNeverConnectsSaysItDidNotAnswerWhenTheWaitEnds() = runTest {
        val ui = MutableStateFlow(connecting())
        val ended = awaitCallConnection(ui, { false }, 15_000, reach = { hermesReachFailure { } })
        assertEquals(CallConnection.Failed(CALL_HERMES_NO_ANSWER), ended)
        assertEquals(15_000L, currentTime)
    }

    @Test fun anAttemptThatEndsWithoutConnectingWhileHermesAnswersEndsThen() = runTest {
        val ui = MutableStateFlow(connecting())
        launch { delay(500); ui.value = ui.value.copy(loading = false) }
        val ended = awaitCallConnection(ui, { false }, 15_000, reach = { hermesReachFailure { } })
        assertEquals(CallConnection.Failed(CALL_HERMES_NO_ANSWER), ended)
        assertEquals(500L, currentTime)
    }

    @Test fun aNewAttemptIsWaitedForNotTheOneThatFailedBeforeIt() = runTest {
        // The press finds the last attempt over (not connected) and asks for a new one: that one is waited for.
        val failedBefore = connecting().copy(loading = false)
        val ui = MutableStateFlow(failedBefore)
        var client = false
        launch { delay(100); ui.value = ui.value.copy(loading = true); delay(1_900); client = true; ui.value = ui.value.copy(connected = true, loading = false) }
        assertEquals(CallConnection.Up, awaitCallConnection(ui, { client }, 15_000, freshAttempt = true, reach = { hermesReachFailure { } }))
        assertEquals(2_000L, currentTime)
        // Without a new attempt, the one that is over is the answer at once.
        val over = MutableStateFlow(failedBefore)
        val started = currentTime
        assertEquals(CallConnection.Failed(CALL_HERMES_NO_ANSWER), awaitCallConnection(over, { false }, 15_000, reach = { hermesReachFailure { } }))
        assertEquals(started, currentTime)
    }

    @Test fun aSignInThatIsGoneEndsItAtOnceWithTheCallsSignInLine() = runTest {
        val ui = MutableStateFlow(AppState(authenticated = false, connected = false, restoringSession = false))
        assertEquals(CallConnection.Failed(CALL_SIGN_IN_EXPIRED), awaitCallConnection(ui, { false }, 15_000, reach = { hermesReachFailure { } }))
        assertEquals(0L, currentTime)
    }

    @Test fun anAppThatKeepsItsClientAfterItsLinkDroppedGoesOnToTheCallWhichMeetsHermesItself() = runTest {
        // A warm app whose later request failed reads not connected ("Offline") but keeps its client and its call button: the release made
        // that tap wait its whole 15 s for "didn't open in time" (device, fix 5). The call itself now meets Hermes, by its own reach rule.
        var asked = false
        val ui = MutableStateFlow(connecting().copy(connected = false, loading = false))
        assertEquals(CallConnection.Up, awaitCallConnection(ui, { true }, 15_000, reach = { asked = true; null }))
        assertEquals(0L, currentTime)
        assertFalse(asked)
        // While a new attempt runs (no client yet, or the client of the one before it), the attempt is waited for.
        val attempt = MutableStateFlow(connecting())
        assertFalse(callCanStart(attempt.value, client = true))
        assertTrue(callCanStart(attempt.value.copy(connected = true, loading = false), client = true))
        assertFalse(callCanStart(attempt.value.copy(connected = true, loading = false), client = false))
    }

    @Test fun anAppAlreadyConnectedGoesOnWithoutAskingHermes() = runTest {
        var asked = false
        val ui = MutableStateFlow(connecting().copy(connected = true, loading = false))
        assertEquals(CallConnection.Up, awaitCallConnection(ui, { true }, 15_000, reach = { asked = true; null }))
        assertFalse("A connected press costs nothing new", asked)
    }

    @Test fun theLineIsTheOneTheCallUsesForAHermesItCannotReach() {
        assertEquals(APP_CONNECTION_ERROR, grantFailureText(HermesUnreachable()))
        assertEquals(APP_CONNECTION_ERROR, grantFailureText(java.net.ConnectException("Connection refused")))
    }

    @Test fun theOverlaySaysItInShortWithCallAgainAndDoesNotTryAgainByItself() {
        val reason = callReason(APP_CONNECTION_ERROR)
        assertNotNull("The call's line for a Hermes it cannot reach has its own short line, not \"Couldn't start the call\"", reason)
        assertEquals(LineAction.CallAgain, lineAction(OverlayLine.CallFailed, reason))
        assertFalse("A second start would only say the same later", retryableCallFailure(reason))
        assertTrue("Hermes that did not answer is still tried once more, as before", retryableCallFailure(callReason(CALL_HERMES_NO_ANSWER)))
        assertTrue(retryableCallFailure(null))
        assertFalse(retryableCallFailure(callReason(CALL_SIGN_IN_EXPIRED)))
    }

    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run from the Android project"
    }
    private fun source(path: String) = File(app, "src/main/java/dev/hermes/android/$path").readText().replace("\r\n", "\n")

    /** The view model and the overlay need Android to run, so their wiring is read from source (as BeaconStaleChatTest does). */
    @Test fun aPressAsksForANewConnectionAndAsksHermesBeforeItWaitsAndSaysWhyItEnded() {
        val call = source("BeaconViewModel.kt").substringAfter("fun callBeacon() {").substringBefore("private suspend fun beaconChatOpened(")
        val wait = call.indexOf("awaitCallConnection(_ui, { client != null }, BEACON_CALL_CONNECT_MS, freshAttempt = fresh,")
        assertTrue("callBeacon waits with the reach rule", wait >= 0)
        assertTrue("an attempt that failed is asked again first", call.indexOf("if (fresh) connect(before.origin)") in 0 until wait)
        assertTrue("only by a model with no client, by the existing rule", call.contains("val fresh = client == null && reconnectOnNetworkReturn(before)"))
        assertTrue("Hermes is asked directly", call.contains("hermesReachFailure { readHermesStatus(origin, viewModelScope) { readGotSomewhere.set(true) } }"))
        assertTrue("the press's end is said", call.contains("if (connection is CallConnection.Failed) {") && call.contains("beaconCallFailed(connection.line); return@launch"))
        assertFalse("never the old notice in its place", call.substringBefore("keptBeaconChat()").contains("didn't open in time"))
        assertTrue("before it reads the kept chat", wait < call.indexOf("keptBeaconChat()"))
        val vm = source("BeaconViewModel.kt")
        val head = "internal suspend fun readHermesStatus(origin: String, reads: CoroutineScope, dns: okhttp3.Dns? = null, gotSomewhere: () -> Unit = {}) {"
        assertTrue("the status read is where the test looks", vm.contains(head))
        val status = vm.substringAfter(head).substringBefore("\n}\n")
        assertTrue("with no sign-in, on a client of its own", status.contains("BeaconClient(AuthContext(origin, emptyList()), null,") && status.contains("closeNative()"))
        assertTrue("Hermes' TLS handshake already answers it (a slow link is not a lost one)", status.contains("override fun secureConnectEnd(") && status.contains("answered.await()"))
        assertTrue("the app and the overlay hear it", vm.contains("_beaconCallFailures.tryEmit(line)"))
    }

    @Test fun theOverlayShowsAPressThatEndedAtOnceAndItsGiveUpSaysNothingMore() {
        val controller = source("assist/AssistOverlayController.kt")
        assertTrue(controller.contains("model.beaconCallFailures.collect { line -> endWanted(OverlayLine.CallFailed, callReason(line) { listOf(serviceText(app, it)) }) }"))
        val end = controller.substringAfter("private fun endWanted(").substringBefore("\n    }")
        assertTrue("the 25 s give-up is cancelled", end.contains("giveUp?.cancel()"))
        assertTrue("the failure keeps its reason", end.contains("OverlayEvent.CallFailed(reason)"))
        assertTrue(controller.contains("private fun retryable(reason: String): Boolean = retryableCallFailure("))
        assertTrue(source("assist/AssistOverlay.kt").contains("CallReason.HermesUnreachable -> uiText(\"Can't reach Hermes\")"))
    }
}
