package dev.hermes.android.assist

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lane LK (owner decision, 3 Nov): on a locked phone the side-button assistant asks for the unlock first, and nothing is heard or said
 * until then. The overlay's pure rules, and the controller's wiring of them read from its source (the controller needs a voice-
 * interaction session, so, like the other assist tests, its order of steps is pinned in the source): a locked fresh show never reaches
 * the live start, the call entry, the warming, the app's model, dictation or a sent question, and asks for the unlock once; the unlock
 * brings the overlay back as a fresh start; a cancelled unlock leaves the line and its action asks again. voice/CallTrust.kt stays
 * as defense in depth (CallTrustTest and LockedStartCallTest are unchanged).
 */
class AssistUnlockFirstTest {
    private fun shown(locked: Boolean, fresh: Boolean = true, callActive: Boolean = false, from: AssistOverlayState = AssistOverlayState()) =
        reduce(from, OverlayEvent.Show(fresh = fresh, callActive = callActive, locked = locked))

    // --- a locked fresh show starts nothing ---

    @Test fun aFreshShowOnALockedPhoneSaysToUnlockFirstAndStartsNothing() {
        val locked = shown(locked = true)
        assertEquals(OverlayLine.Unlock, locked.line)
        assertEquals("No conversation: the bare pill", OverlaySurface.Pill, locked.surface)
        assertFalse(locked.dictating)
        assertNull("No question, no answer", locked.ask)
        assertEquals(OverlayPhase.Summoning, locked.phase)
        // The same show on an unlocked phone has no line: the conversation starts at its first frame.
        assertNull(shown(locked = false).line)
        // The line is the unlock's own: the owner's one action asks Android for the unlock again.
        assertEquals(LineAction.Unlock, lineAction(OverlayLine.Unlock))
    }

    @Test fun theFirstFrameOfALockedFreshShowAsksForTheUnlockOnceAndLoadsWarmsAndStartsNothing() {
        val talks = talksOnShow(reshow = false)
        assertTrue(talks)
        assertEquals(FirstFrame.AskUnlock, firstFrame(locked = true, callActive = false, talks = talks))
        // Asked once: the controller spends the show's start, so a later look at the same show only holds.
        assertEquals(FirstFrame.Hold, firstFrame(locked = true, callActive = false, talks = false))
        // Unlocked, the same frame warms and starts as before.
        assertEquals(FirstFrame.WarmAndTalk, firstFrame(locked = false, callActive = false, talks = true))
        assertEquals(FirstFrame.Warm, firstFrame(locked = false, callActive = false, talks = false))
    }

    @Test fun lockedNothingStartsButAConversationThatRunsIsJoinedAsBefore() {
        assertTrue(startNeedsUnlock(locked = true, callActive = false))
        assertFalse(startNeedsUnlock(locked = false, callActive = false))
        // A conversation that runs was started unlocked, so it is trusted: the overlay comes back to it, whatever the lock says.
        assertFalse(startNeedsUnlock(locked = true, callActive = true))
        val joined = shown(locked = true, callActive = true)
        assertEquals(OverlaySurface.Live, joined.surface)
        assertNull(joined.line)
        assertEquals(FirstFrame.WarmAndTalk, firstFrame(locked = true, callActive = true, talks = true))
        assertEquals(LiveStart.Join, liveStart(callActive = true, signedIn = false, micGranted = false))
        // A re-show that brings an answer back starts nothing, locked or not, and says nothing of the unlock.
        assertNull(shown(locked = true, fresh = false).line)
        assertEquals(FirstFrame.Hold, firstFrame(locked = true, callActive = false, talks = talksOnShow(reshow = true)))
    }

    @Test fun lockedAskingDictatingAndStartingNeedTheUnlockWhileEndingAndClosingNever() {
        listOf(OverlayAction.Ask, OverlayAction.Dictate, OverlayAction.Live).forEach { assertTrue("$it waits for the unlock", needsUnlock(it, locked = true)) }
        listOf(OverlayAction.EndCall, OverlayAction.Type, OverlayAction.CloseCard, OverlayAction.Dismiss, OverlayAction.AttachScreen)
            .forEach { assertFalse("$it still works locked", needsUnlock(it, locked = true)) }
        OverlayAction.entries.forEach { assertFalse("$it unlocked", needsUnlock(it, locked = false)) }
        // The text box is inert locked with no conversation running; a running one takes typed turns (it is trusted).
        assertTrue(pillInert(locked = true, live = false))
        assertFalse(pillInert(locked = true, live = true))
        assertFalse(pillInert(locked = false, live = false))
    }

    // --- the controller carries the rules out in this order ---

    @Test fun theLockedFirstFrameAsksForTheUnlockAndTouchesNothingElse() {
        val controller = source("AssistOverlayController.kt")
        val drawn = controller.substringAfter("fun drawn()").substringBefore("fun summoned()")
        // Posted, so the first frame never waits; the post only settles.
        assertTrue(drawn.contains("android.os.Handler(android.os.Looper.getMainLooper()).post { settle() }"))
        val settle = drawn.substringAfter("private fun settle()").substringBefore("private fun warm()")
        assertTrue(settle.contains("firstFrame(overlayLocked(locked, keyguardLocked()), app.calls.state.value.active, talks)"))
        val askUnlock = settle.substringAfter("FirstFrame.AskUnlock ->").substringBefore("FirstFrame.Hold ->")
        assertTrue(askUnlock.contains("unlockFirst()"))
        assertTrue("the show's start is spent, so the unlock is asked once", askUnlock.contains("talkOnFrame = false"))
        listOf("startLive", "warm()", "ensureVm", "assistPrewarm", "callBeacon", "dictate", "LiveStarted", "ask(").forEach { assertFalse("a locked first frame must not reach $it", askUnlock.contains(it)) }
        assertEquals("Hold does nothing at all", "Unit", settle.substringAfter("FirstFrame.Hold ->").substringBefore("FirstFrame.Warm ->").trim())
        // The app's model and the call session are warmed in one place, which only an unlocked frame reaches.
        assertEquals(1, Regex("""assistPrewarm\(true\)""").findAll(controller).count())
        assertTrue(controller.contains("private fun warm() { ensureVm().foreground(true); assistPrewarm(true) }"))
        assertEquals(1, Regex("""ensureVm\(\)\.foreground\(true\)""").findAll(controller).count())
        assertEquals("outside the settle step warm() is only defined, never called", 1, Regex("""\bwarm\(\)""").findAll(controller.replace(settle, "")).count())
        // The unlock is asked through one function, which hides nothing and shows the line.
        val unlockFirst = controller.substringAfter("private fun unlockFirst()").substringBefore("fun unlock()")
        assertTrue(unlockFirst.contains("OverlayEvent.Line(OverlayLine.Unlock)"))
        assertTrue(unlockFirst.contains("unlock()"))
        assertFalse(unlockFirst.contains("hide()"))
        assertEquals("Android is asked in one place", 1, Regex("""AssistUnlockActivity\.talk\(""").findAll(controller).count())
    }

    @Test fun startingTheConversationIsGatedBeforeAnythingOfTheAppLoadsAndBeforeTheCall() {
        val controller = source("AssistOverlayController.kt")
        val start = controller.substringAfter("fun startLive()").substringBefore("private fun startRequestedCall")
        val gate = "startNeedsUnlock(overlayLocked(locked, keyguardLocked()), app.calls.state.value.active)) { unlockFirst(); return }"
        assertTrue(start.contains(gate))
        assertTrue("the gate comes before the app's model, the sign-in and the microphone", start.indexOf(gate) in 0 until start.indexOf("ensureVm()"))
        assertTrue(start.indexOf(gate) < start.indexOf("granted()"))
        // A start already on its way is not asked for twice (an unlock seen here, then the unlock's own fresh show).
        assertTrue(start.indexOf("if (live.wanted) { state = reduce(state, OverlayEvent.LiveStarted); return }") in 0 until start.indexOf("live.requested()"))
        // The phone may lock while the sign-in is restored or the chat opens: both steps before a call exists look again.
        assertEquals(1, Regex("""model\.callBeacon\(\)""").findAll(controller).count())
        assertTrue(start.indexOf("endWanted(OverlayLine.Unlock)") in 0 until start.indexOf("model.callBeacon()"))
        val requested = controller.substringAfter("private fun startRequestedCall").substringBefore("private fun endWanted")
        assertEquals(1, Regex("""app\.calls\.start\(""").findAll(controller).count())
        assertTrue("the last gate before the call exists", requested.indexOf("overlayLocked(locked, keyguardLocked())) { endWanted(OverlayLine.Unlock); return@launch }") in 0 until requested.indexOf("app.calls.start("))
        // The orb and the line's Call again reach the same gate.
        val overlay = source("AssistOverlay.kt")
        assertTrue(overlay.contains("onOrb = { if (live) c.endCall() else c.startLive() }"))
        assertTrue(overlay.contains("LineAction.CallAgain -> c::startLive"))
    }

    @Test fun typedAndDictatedWordsGoNowhereWhileLocked() {
        val controller = source("AssistOverlayController.kt")
        // A typed turn into a conversation that runs is the first branch and is unchanged; the written question is gated before the model exists.
        val ask = controller.substringAfter("fun ask(").substringBefore("/** The question didn't reach Hermes")
        val askGate = "needsUnlock(OverlayAction.Ask, overlayLocked(locked, keyguardLocked()))) return"
        assertTrue(ask.indexOf("OverlaySurface.Live") in 0 until ask.indexOf(askGate))
        assertTrue(ask.indexOf(askGate) in 0 until ask.indexOf("ensureVm()"))
        assertTrue(ask.indexOf(askGate) < ask.indexOf("OverlayEvent.Asked("))
        assertTrue(ask.indexOf(askGate) < ask.indexOf("model.send()"))
        // The mic: no microphone question, no recognizer.
        val dictate = controller.substringAfter("fun dictate()").substringBefore("private fun dictationEvent")
        val dictateGate = "needsUnlock(OverlayAction.Dictate, overlayLocked(locked, keyguardLocked()))) return"
        assertTrue(dictate.indexOf(dictateGate) in 0 until dictate.indexOf("granted()"))
        assertTrue(dictate.indexOf(dictateGate) < dictate.indexOf("dictation.start()"))
        assertTrue(dictate.indexOf(dictateGate) < dictate.indexOf("ensureVm()"))
        // A dictation that finishes, and Try again, go through ask().
        assertTrue(controller.substringAfter("is DictationEvent.Done").substringBefore("is DictationEvent.Failed").contains("ask(event.text)"))
        assertTrue(controller.contains("fun retry() = ask("))
    }

    @Test fun theLockedTextBoxIsInertAndTheLineIsTheOnlyThingThatShows() {
        val overlay = source("AssistOverlay.kt")
        // One value decides, from the one lock read of the frame.
        assertTrue(overlay.contains("val pillLocked = pillInert(phoneLocked, live)"))
        assertTrue(overlay.contains("locked = pillLocked,"))
        assertEquals("the lock is still read once, as phoneLocked", 1, Regex("""\bc\.locked\b""").findAll(overlay).count())
        // The text box: no typing, no send, no mic, no hint of its own; the orb stays as the way to ask for the unlock.
        val pill = overlay.substringAfter("@Composable internal fun Pill(").substringBefore("/** A gold pearl")
        assertTrue(pill.contains("val canSend = !p.locked &&"))
        assertTrue(pill.contains("enabled = !p.locked,"))
        assertTrue(pill.contains("else if (!live && !p.locked) IconButton(p.onDictate"))
        assertTrue(pill.contains("p.locked -> \"\""))
        assertTrue(pill.contains("if (draft.text.isEmpty() && hint.isNotEmpty()) Text(hint"))
        assertTrue(pill.contains("IconButton(p.onOrb,"))
        // No chip while the unlock line waits; the chips call keeps its one value for the other lock rules.
        assertTrue(overlay.contains("state.line != OverlayLine.Unlock) Chips(c, vm, chipShows, motion, field, phoneLocked)"))
        // The line: existing words and action, no close (the phone is still locked; Back or a tap outside closes the overlay).
        assertTrue(overlay.contains("OverlayLine.Unlock -> uiText(\"Unlock your phone first.\")"))
        assertTrue(overlay.contains("LineAction.Unlock -> uiText(\"Unlock\")"))
        assertTrue(overlay.contains("LineAction.Unlock -> c::unlock"))
        assertTrue(overlay.contains("if (line == OverlayLine.Unlock) Spacer(Modifier.width(8.dp))\n        else IconButton(onClear)"))
        assertTrue("Back and the outside tap close as today", outsideTapCloses(OverlaySurface.Pill))
        assertTrue(source("BeaconAssistSession.kt").contains("override fun onBackPressed() { controller.dismiss() }"))
    }

    // --- the unlock, and a cancelled one ---

    @Test fun theUnlockBringsTheOverlayBackAsAFreshStartLikeAnUnlockedGesture() {
        val activity = source("AssistUnlockActivity.kt")
        assertTrue(activity.contains("private const val THEN_TALK = \"talk\""))
        assertTrue(activity.contains("THEN_TALK -> BeaconVoiceInteractionService.show()"))
        assertTrue(activity.contains("internal fun talk(context: Context) = start(context, THEN_TALK) {}"))
        // The fresh show is the gesture's own bundle: no reshow marker, so the last question is cleared and the conversation talks at once.
        val service = source("BeaconVoiceInteractionService.kt")
        assertTrue(service.contains("fun show(): Boolean = showWith(Bundle())"))
        assertTrue(service.contains("fun reshow(): Boolean = showWith(Bundle().apply { putBoolean(EXTRA_RESHOW, true) })"))
        assertTrue(source("AssistOverlayController.kt").contains("val reveal = args?.getBoolean(EXTRA_RESHOW) == true"))
        assertTrue(talksOnShow(reshow = false))
        // That show, now unlocked, is the unlocked path end to end: no line, and the first frame warms and starts the conversation.
        val back = shown(locked = false, from = reduce(shown(locked = true), OverlayEvent.Summoned))
        assertNull(back.line)
        assertEquals(OverlaySurface.Pill, back.surface)
        assertEquals(FirstFrame.WarmAndTalk, firstFrame(locked = false, callActive = false, talks = talksOnShow(reshow = false)))
        assertEquals(LiveStart.Start, liveStart(callActive = false, signedIn = true, micGranted = true))
        assertEquals(OverlaySurface.Live, reduce(back, OverlayEvent.LiveStarted).surface)
    }

    @Test fun aCancelledOrFailedUnlockLeavesTheLineAndItsActionAsksAgain() {
        // The unlock activity does nothing on a cancel or an error (it only leaves): the overlay is as it was.
        val activity = source("AssistUnlockActivity.kt")
        assertEquals("= finish()", activity.substringAfter("override fun onDismissCancelled()").substringBefore("\n").trim())
        assertEquals("= finish()", activity.substringAfter("override fun onDismissError()").substringBefore("\n").trim())
        val waiting = reduce(shown(locked = true), OverlayEvent.Summoned)
        assertEquals(OverlayLine.Unlock, waiting.line)
        assertEquals(OverlayPhase.Open, waiting.phase)
        // The action asks Android again, and the overlay stays up while it does (it never hides itself for this unlock).
        val unlock = source("AssistOverlayController.kt").substringAfter("fun unlock()").substringBefore("\n")
        assertTrue(unlock.contains("AssistUnlockActivity.talk(app)"))
        assertFalse(unlock.contains("hide()"))
        assertTrue(source("AssistOverlay.kt").contains("LineAction.Unlock -> c::unlock"))
        // Nothing the overlay does while the unlock line is up clears it but a fresh show of an unlocked phone: a late tick, a summon, the dust.
        assertEquals(OverlayLine.Unlock, reduce(waiting, OverlayEvent.Summoned).line)
        assertEquals(OverlayLine.Unlock, reduce(waiting, OverlayEvent.Hidden).line)
        assertNull(shown(locked = false, from = waiting).line)
        // A reshow of an answer does not carry a stale unlock line back either.
        assertNull(shown(locked = false, fresh = false, from = waiting).line)
    }

    @Test fun lockingWhileTheOverlayIsOpenAndNotTalkingLeavesTheUnlockStateAndStartsNothing() {
        val controller = source("AssistOverlayController.kt")
        assertTrue(controller.contains("if (locked) lockedWhileOpen() else unlockedWhileWaiting()"))
        assertTrue(controller.contains("locked = intent.action == Intent.ACTION_SCREEN_OFF || keyguardLocked()"))
        val locking = controller.substringAfter("private fun lockedWhileOpen()").substringBefore("The phone unlocked while the unlock line waits")
        // A conversation that runs carries on (it was started unlocked, so it is trusted); nothing else is left going.
        assertTrue(locking.contains("app.calls.state.value.active) return"))
        assertTrue(locking.contains("dictation.cancel()"))
        assertTrue("a start still on its way ends here", locking.contains("if (live.wanted) endCall()"))
        assertTrue(locking.contains("state.surface == OverlaySurface.Pill) state = reduce(state, OverlayEvent.Line(OverlayLine.Unlock))"))
        listOf("startLive", "ensureVm", "warm()", "unlock()", "callBeacon").forEach { assertFalse("locking must not reach $it", locking.contains(it)) }
        // An unlock seen while the line waits carries the show on as the unlocked gesture does, through the same settle step.
        val unlocked = controller.substringAfter("private fun unlockedWhileWaiting()").substringBefore("fun summoned()")
        assertTrue(unlocked.contains("if (state.line != OverlayLine.Unlock"))
        assertTrue(unlocked.contains("settle()"))
        // The running conversation is untouched by the pure rules too: a lock does not turn the Live surface into the unlock line.
        val live = reduce(shown(locked = false), OverlayEvent.LiveStarted)
        assertEquals(OverlaySurface.Live, live.surface)
        assertNull(live.line)
    }

    // --- words, docs ---

    @Test fun theLineUsesTheExistingStringsInEveryLanguage() {
        val catalog = JSONObject(File(appRoot, "src/main/assets/native-locales.json").readText(Charsets.UTF_8).removePrefix("﻿"))
        for (language in catalog.keys().asSequence().filter { it != "en" }) {
            val words = catalog.getJSONObject(language)
            assertTrue("$language has Unlock your phone first.", words.optString("unlock your phone first.").isNotBlank())
            assertTrue("$language has Unlock", words.optString("unlock").isNotBlank())
        }
    }

    @Test fun theRuleIsWrittenInTheCommentBlockAndTheDocs() {
        val model = source("AssistOverlayModel.kt")
        assertTrue(model.contains("The overlay never starts a conversation on a locked phone."))
        assertTrue(model.contains("asks for the unlock first"))
        assertTrue(model.contains("That stays as defense in depth"))
        assertFalse("the old talk-while-locked rule is gone", model.contains("it\n *    talks at once, but until it is trusted"))
        val doc = File(appRoot.parentFile.parentFile, "docs/phone-access.md").readText().replace("\r\n", "\n")
        assertTrue(doc.contains("On a locked phone the side button asks you to unlock first; the assistant hears and says nothing until you do. A call you started while unlocked keeps its tools when the screen turns off."))
        assertFalse(doc.contains("A conversation started on a locked phone"))
    }

    private val appRoot = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it).absoluteFile.normalize() }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun source(name: String) = File(appRoot, "src/main/java/dev/hermes/android/assist/$name").readText().replace("\r\n", "\n")
}
