package dev.hermes.android

import dev.hermes.android.data.ApiFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backlog 57, device round 1 (14 Nov 2026): the phone kept a Beacon chat Hermes' plugin no longer holds (saved once, now not linked to
 * this sign-in), and the side button showed "Couldn't start the call". A call from the side button, the drawer or the Beacon tab checks
 * the kept chat as it opens, and a refused one gives way to the account's current Beacon chat, found as a fresh start finds it.
 *
 * Device round 3 (F1, 15 Nov 2026), proven on the emulator with a log build: on a cold process the check was cut at 1.5 s before Hermes
 * answered, and when it did answer (404 "Owned assistant conversation unavailable") it was not believed, because the chats this phone makes
 * keep their client-shell mark (or have no saved copy), so the stale chat took the call and its grant was refused (404 "Owned resource
 * unavailable") on the first try and the overlay's retry. Hermes' answer is now waited for, and the plugin's 404 is settled by asking for
 * the live draft this phone may have made: Hermes has neither (native not_found) means the chat is gone.
 */
class BeaconStaleChatTest {
    private val refused = ApiFailure(404, "Owned assistant conversation unavailable", plugin = true)
    private val notFound = ApiFailure(4001, "not_found")
    private val reach = 3_000L

    @Test fun aChatHermesStillHasSavedTakesTheCall() = runTest {
        var resumed = false
        assertEquals(true, beaconChatCallable(reach, register = {}, resume = { resumed = true }))
        assertFalse("A saved chat needs no second question", resumed)
    }

    @Test fun aChatHermesNoLongerHasIsNotCalled() = runTest {
        // Deleted elsewhere, or a draft Hermes no longer keeps: no saved chat and no live draft (round 3's seeds A and B).
        assertEquals(false, beaconChatCallable(reach, register = { throw refused }, resume = { throw notFound }))
    }

    @Test fun aDraftThisPhoneMadeThatHermesStillKeepsTakesTheCall() = runTest {
        // Hermes saves a chat at its first message; until then the plugin cannot register it, but the live draft is there and the grant takes it.
        assertEquals(true, beaconChatCallable(reach, register = { throw refused }, resume = {}))
    }

    @Test fun anAnswerThatComesAfterOneAndAHalfSecondsIsWaitedFor() = runTest {
        // Round 3's cold press: the registration answered after more than 1.5 s, the live-draft look-up after about 1.4 s more.
        val verdict = beaconChatCallable(reach, register = { delay(2_400); throw refused }, resume = { delay(1_400); throw notFound })
        assertEquals(false, verdict)
        assertEquals(3_800L, currentTime)
    }

    @Test fun aHermesThatDoesNotAnswerIsARealFailureNotAVerdict() = runTest {
        assertNull(beaconChatCallable(reach, register = { delay(60_000) }, resume = { error("never asked") }))
        assertEquals("Bounded by the call's own reach budget, then the call meets the failure", reach, currentTime)
        assertNull("A live-draft look-up that does not answer", beaconChatCallable(reach, register = { throw refused }, resume = { delay(60_000) }))
    }

    @Test fun anOlderPluginAndOtherFailuresSayNothingAboutTheChat() = runTest {
        suspend fun after(error: Exception) = beaconChatCallable(reach, register = { throw error }, resume = { throw notFound })
        assertNull("A plugin without the route", after(ApiFailure(404, "Not Found", plugin = true)))
        assertNull("Hermes' own 404 is not the plugin's verdict", after(ApiFailure(404, "Session not found")))
        assertNull(after(ApiFailure(403, "Plugin not authorized", plugin = true)))
        assertNull(after(ApiFailure(503, "Native lookup unavailable", plugin = true)))
        assertNull(after(java.io.IOException("timeout")))
        assertNull("A look-up that fails for another reason", beaconChatCallable(reach, register = { throw refused }, resume = { throw java.io.IOException("reset") }))
        assertNull("Hermes' not_found about something else", beaconChatCallable(reach, register = { throw refused }, resume = { throw ApiFailure(4001, "busy") }))
    }

    private val chatScope = dev.hermes.android.core.Scope("https://hermes.example.com", "owner", "default", "kept")
    private val asked = 1_000L

    @Test fun aStartRefusedAtItsGrantIsMet() = runTest {
        val calls = kotlinx.coroutines.flow.MutableStateFlow(dev.hermes.android.voice.CallState())
        val watched = async { beaconCallStartFailure(calls, chatScope, asked, 30_000) }
        delay(200); calls.value = dev.hermes.android.voice.CallState(active = true, state = "connecting", startedAt = asked + 50, conversationScope = chatScope)
        delay(500); calls.value = calls.value.copy(active = false, state = "ending", error = "Owned resource unavailable", failureReason = "Owned resource unavailable")
        assertEquals("Owned resource unavailable", watched.await()?.failureReason)
    }

    @Test fun aStartThatConnectsIsLeftAlone() = runTest {
        val calls = kotlinx.coroutines.flow.MutableStateFlow(dev.hermes.android.voice.CallState())
        val watched = async { beaconCallStartFailure(calls, chatScope, asked, 30_000) }
        delay(200); calls.value = dev.hermes.android.voice.CallState(active = true, state = "connecting", startedAt = asked + 50, conversationScope = chatScope)
        delay(500); calls.value = calls.value.copy(state = "listening")
        delay(500); calls.value = calls.value.copy(active = false, failureReason = "The call dropped")
        assertNull("Connected first: a later end is the call's own", watched.await())
    }

    @Test fun anEarlierStartOrAnotherChatIsNotThisStart() = runTest {
        val earlier = dev.hermes.android.voice.CallState(active = false, startedAt = asked - 5_000, conversationScope = chatScope, failureReason = "Owned resource unavailable")
        val calls = kotlinx.coroutines.flow.MutableStateFlow(earlier)
        val watched = async { beaconCallStartFailure(calls, chatScope, asked, 30_000) }
        delay(200); calls.value = earlier.copy(startedAt = asked + 50, conversationScope = chatScope.copy(session = "other"))
        delay(200); calls.value = dev.hermes.android.voice.CallState(active = true, state = "speaking", startedAt = asked + 400, conversationScope = chatScope)
        assertNull(watched.await())
    }

    @Test fun aStartThatNeverComesStopsBeingWatched() = runTest {
        assertNull(beaconCallStartFailure(kotlinx.coroutines.flow.MutableStateFlow(dev.hermes.android.voice.CallState()), chatScope, asked, 30_000))
        assertEquals(30_000L, currentTime)
    }

    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { java.io.File(it) }.firstOrNull { java.io.File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run from the Android project"
    }
    private fun source(path: String) = java.io.File(app, "src/main/java/dev/hermes/android/$path").readText().replace("\r\n", "\n")

    /** The view model needs Android to run, so its wiring is read from source, as LockedStartCallTest does for the call service. */
    @Test fun theSideButtonTheDrawerAndTheBeaconTabCheckTheKeptChatAndCarryOnWithTheCurrentOne() {
        val vm = source("BeaconViewModel.kt")
        val call = vm.substringAfter("fun callBeacon() {").substringBefore("private suspend fun beaconChatOpened(")
        // Checked while it opens, not before: a held chat costs the call nothing.
        assertTrue(call.indexOf("beaconChatHeld(") in 0 until call.indexOf("navigate(\"Beacon\")"))
        assertTrue(call.contains("beaconChatOpened { refusedEarly }"))
        // Hermes' answer is waited for, not cut at a fixed time (round 3, F1); its own bound is the call's reach budget.
        assertTrue(call.contains("val verdict = if (held == null || opened == \"\") null else held.await()"))
        assertFalse("No fixed cut of the check", vm.contains("BEACON_CHAT_CHECK_MS"))
        // A refused chat is forgotten and the tab opens the account's current one (resolveBeacon: registered, else new), in the same press.
        assertTrue(call.contains("beaconCallChat(kept.second, opened, verdict) == null) opened = replaceGoneBeaconChat(kept)"))
        val recover = vm.substringAfter("private suspend fun replaceGoneBeaconChat(").substringBefore("private suspend fun beaconChatOpened(")
        assertTrue(recover.indexOf("forgetBeaconChat(") in 0 until recover.indexOf("openBeacon("))
        assertTrue(recover.indexOf("return beaconChatOpened()") > recover.indexOf("openBeacon("))
        assertTrue("No line for the owner on the way", !recover.contains("notice("))
        assertTrue(call.contains("_voiceCallRequest.value = VoiceCallRequest(active.scope, chat)"))
        // A chat that still passed the quick check and is then refused at its grant or context: the refusal is the answer too (round 3, F1).
        val watch = call.substringAfter("_voiceCallRequest.value = VoiceCallRequest(active.scope, chat)")
        assertTrue(watch.contains("beaconCallStartFailure(app.calls.state, active.scope.session(chat), askedAt, BEACON_CALL_START_WATCH_MS)"))
        assertTrue("Checked again without the quick path", watch.contains("beaconChatHeld(kept.first, chat, quick = false) == false"))
        assertTrue("The failure line goes before the current chat takes the call",
            watch.indexOf("app.calls.dismissFailure(failed.callId)") in 0 until watch.indexOf("chat = replaceGoneBeaconChat(kept)"))
        assertTrue("Once per press", watch.contains("if (!replaced && kept != null && kept.second == chat"))
        assertTrue("Any other failure stays, the overlay's retry goes to the same chat", watch.contains("} else if (!beaconCallAskedAgain) return@launch"))
        assertTrue(call.contains("BeaconCallPhase.Deciding -> { beaconCallAskedAgain = true; return }"))
        // A context read the plugin refuses stops vouching for the chat in the prewarm.
        val prewarm = source("voice/VoicePrewarm.kt")
        assertTrue(prewarm.contains("else if (error.plugin && error.status == 404) refused = true"))
        assertTrue(prewarm.contains("if (refused) { synchronized(lock) { if (context?.scope == target) context = null }; return null }"))
        // The check is the registration the app makes anyway, or no request at all when already known this run.
        val held = vm.substringAfter("private suspend fun beaconChatHeld(").substringBefore("private suspend fun forgetBeaconChat(")
        assertTrue(held.contains("if (quick && (registeredAssistantChats.contains(scope.key) || app.calls.prewarm.context(scope, preferences.value.ownerName) != null)) return true"))
        assertTrue(held.contains("\"/api/plugins/beacon/mobile/assistant-conversation\", \"PUT\""))
        // The plugin's 404 is settled by the live draft.
        assertTrue(held.contains("beaconChatCallable(dev.hermes.android.voice.callStartReachMs(callbackAnswer = false),"))
        // Asked of Hermes on a client of its own: the model's client may still hold a deleted chat open and answer from that (device, fix4).
        assertTrue(held.contains("val probe = c.detached()"))
        assertTrue(held.contains("resume = { probe.nativeSession(id, lazy = true) }"))
        assertTrue(held.contains("} finally { probe.closeNative() }"))
        assertFalse("A saved copy no longer decides it", held.contains("everSaved"))
        // Nothing is keyed to an account: the kept chat is whichever this phone holds for the signed-in owner.
        assertTrue(vm.contains("val id = preparedBeacon?.takeIf { it.first == c.scope }?.second ?: try { readBeaconSession(c.scope) }"))
        // The Beacon tab's own call button goes the same way.
        assertTrue(source("ui/ChatScreen.kt").contains("val callThisChat: () -> Unit = { if(ui.tab==\"Beacon\") vm.callBeacon() else vm.client?.let { requestCall(it,chat.sessionId) };Unit }"))
    }

    @Test fun aCallFromARefusedChatGoesToTheAccountsCurrentBeaconChat() {
        assertNull("Refused after it opened: the account's current one is found", beaconCallChat(kept = "kept", opened = "kept", held = false))
        assertNull("Refused while it was still opening", beaconCallChat(kept = "kept", opened = null, held = false))
        assertEquals("A held chat takes the call", "kept", beaconCallChat(kept = "kept", opened = "kept", held = true))
        assertEquals("No verdict in time: the opened chat, as before", "kept", beaconCallChat(kept = "kept", opened = "kept", held = null))
        assertEquals("Another chat opened: it takes the call", "other", beaconCallChat(kept = "kept", opened = "other", held = false))
        assertNull("Nothing opened and nothing refused", beaconCallChat(kept = "kept", opened = null, held = null))
    }
}
