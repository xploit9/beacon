package dev.hermes.android

import dev.hermes.android.voice.CALL_HERMES_NO_ANSWER
import dev.hermes.android.voice.CALL_SIGN_IN_EXPIRED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device round 2 (15 Nov 2026): the first side-button press after the app's process started always showed "Couldn't start the call". The
 * overlay asks for the call once the saved sign-in is restored, which is before Hermes answered; the call gave up at once on the missing
 * client. A call asked for then waits for the connection within its budget; only a link that does not come up, or no sign-in, fails it.
 * (A Hermes that cannot be reached at all ends the wait sooner: BeaconCallReachTest.)
 */
class BeaconCallConnectionTest {
    /** The saved sign-in restored, the connection to Hermes still being made (connect() reads loading until it ends). */
    private fun restored() = AppState(authenticated = true, connected = false, restoringSession = false, loading = true)

    @Test fun aPressRightAfterAColdStartWaitsForTheConnectionAndGoesOn() = runBlocking {
        val ui = MutableStateFlow(restored())
        var client = false
        launch { delay(300); client = true; ui.value = ui.value.copy(connected = true, loading = false) }
        val started = System.nanoTime()
        assertEquals("The call goes on once Hermes answered", CallConnection.Up, awaitCallConnection(ui, { client }, 5_000))
        assertTrue("It waited for the connection", (System.nanoTime() - started) / 1_000_000 >= 250)
    }

    @Test fun aPressWhileTheSavedSignInIsStillBeingRestoredWaitsToo() = runBlocking {
        val ui = MutableStateFlow(AppState(authenticated = false, connected = false, restoringSession = true, loading = true))
        var client = false
        launch { delay(150); ui.value = restored(); delay(150); client = true; ui.value = ui.value.copy(connected = true, loading = false) }
        assertEquals(CallConnection.Up, awaitCallConnection(ui, { client }, 5_000))
    }

    @Test fun anAppAlreadyConnectedGoesOnAtOnce() = runBlocking {
        val ui = MutableStateFlow(restored().copy(connected = true, loading = false))
        val started = System.nanoTime()
        assertEquals(CallConnection.Up, awaitCallConnection(ui, { true }, 5_000))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 200)
    }

    @Test fun aLinkThatDoesNotComeUpFailsWhenItsBudgetEnds() = runBlocking {
        val ui = MutableStateFlow(restored())
        val started = System.nanoTime()
        assertEquals("Hermes never answered: a real failure", CallConnection.Failed(CALL_HERMES_NO_ANSWER), awaitCallConnection(ui, { false }, 400))
        val waited = (System.nanoTime() - started) / 1_000_000
        assertTrue("Within its budget, not at once and not forever: $waited ms", waited in 350..2_000)
    }

    @Test fun aSignedOutAppFailsAtOnce() = runBlocking {
        val ui = MutableStateFlow(AppState(authenticated = false, connected = false, restoringSession = false))
        val started = System.nanoTime()
        assertEquals(CallConnection.Failed(CALL_SIGN_IN_EXPIRED), awaitCallConnection(ui, { false }, 5_000))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 200)
    }

    @Test fun aConnectedFlagWithoutTheClientYetIsNotEnough() = runBlocking {
        val ui = MutableStateFlow(restored().copy(connected = true, loading = false))
        var client = false
        // The client arrives with a later state change (as connect() sets the client before the state that reads connected).
        launch { delay(200); client = true; ui.value = ui.value.copy(notice = "connected") }
        assertEquals(CallConnection.Up, awaitCallConnection(ui, { client }, 5_000))
    }

    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { java.io.File(it) }.firstOrNull { java.io.File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run from the Android project"
    }

    /** The view model needs Android to run, so its wiring is read from source (as BeaconStaleChatTest does). */
    @Test fun aBeaconCallWaitsForTheConnectionBeforeItChecksOrOpensTheChat() {
        val vm = java.io.File(app, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val call = vm.substringAfter("fun callBeacon() {").substringBefore("private suspend fun beaconChatOpened(")
        val wait = call.indexOf("awaitCallConnection(_ui, { client != null }, BEACON_CALL_CONNECT_MS")
        assertTrue("callBeacon waits for the connection", wait >= 0)
        assertTrue("before it reads the kept chat", wait < call.indexOf("keptBeaconChat()"))
        assertTrue("before it opens Beacon", wait < call.indexOf("navigate(\"Beacon\")"))
    }
}
