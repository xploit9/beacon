package dev.hermes.android.assist

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lane LS (owner decision, 4 Nov): "Talk now, unlock for private". Holding the side button always starts the assistant at once, locked
 * or not. Locked, the conversation is an untrusted one (voice/CallTrust.kt, CallTrustTest and LockedStartCallTest): it talks, and
 * everything private (Hermes' memory, mail, calendar, contacts, calls, phone actions, every tool) asks the owner to unlock first. This
 * replaces the Lane LK rule that a locked show said "Unlock your phone first." and started nothing. The overlay's pure rules, and the
 * controller's wiring of them read from its source (the controller needs a voice-interaction session, so, like the other assist tests,
 * its order of steps is pinned in the source).
 */
class AssistLockedTalkTest {
    private fun shown(fresh: Boolean = true, callActive: Boolean = false, from: AssistOverlayState = AssistOverlayState()) =
        reduce(from, OverlayEvent.Show(fresh = fresh, callActive = callActive))

    // --- a locked fresh show talks at once ---

    @Test fun aFreshShowOnALockedPhoneStartsTheConversationAtOnceWithNoUnlockLine() {
        // The show itself has no lock input any more: the same state, so the same first frame, locked or not.
        val locked = shown()
        assertNull("no unlock line before it talks", locked.line)
        assertEquals(OverlaySurface.Pill, locked.surface)
        assertEquals(OverlayPhase.Summoning, locked.phase)
        assertTrue(talksOnShow(reshow = false))
        assertEquals("starting a conversation never waits for the unlock", false, needsUnlock(OverlayAction.Live, locked = true))
        assertEquals(OverlaySurface.Live, reduce(locked, OverlayEvent.LiveStarted).surface)
    }

    @Test fun theLockedFirstFrameStartsTheConversationAndTheUnlockedOneWarmsItFirst() {
        // Locked: the conversation starts at its first frame. It is not warmed ahead (a warm session carries the owner's memory).
        assertEquals(FirstFrame.TalkLocked, firstFrame(locked = true, callActive = false, talks = true))
        assertEquals("a locked re-show starts nothing", FirstFrame.Hold, firstFrame(locked = true, callActive = false, talks = false))
        // Unlocked, or joining a conversation that runs: as before.
        assertEquals(FirstFrame.WarmAndTalk, firstFrame(locked = false, callActive = false, talks = true))
        assertEquals(FirstFrame.Warm, firstFrame(locked = false, callActive = false, talks = false))
        assertEquals(FirstFrame.WarmAndTalk, firstFrame(locked = true, callActive = true, talks = true))
        assertEquals(LiveStart.Join, liveStart(callActive = true, signedIn = false, micGranted = false))
        assertEquals(LiveStart.Start, liveStart(callActive = false, signedIn = true, micGranted = true))
    }

    @Test fun lockedWritingAndDictatingStillWaitForTheUnlockButTalkingNever() {
        // A written question goes into the assistant's chat, with its memory and history; dictation ends in one. Both are private.
        listOf(OverlayAction.Ask, OverlayAction.Dictate).forEach { assertTrue("$it waits for the unlock", needsUnlock(it, locked = true)) }
        listOf(OverlayAction.Live, OverlayAction.EndCall, OverlayAction.Type, OverlayAction.CloseCard, OverlayAction.Dismiss, OverlayAction.AttachScreen)
            .forEach { assertFalse("$it works locked", needsUnlock(it, locked = true)) }
        OverlayAction.entries.forEach { assertFalse("$it unlocked", needsUnlock(it, locked = false)) }
        // The text box is inert locked with no conversation running; the orb beside it talks. A running one takes typed turns.
        assertTrue(pillInert(locked = true, live = false))
        assertFalse(pillInert(locked = true, live = true))
        assertFalse(pillInert(locked = false, live = false))
    }

    // --- the controller carries the rules out in this order ---

    @Test fun theLockedFirstFrameStartsAtOnceAndWarmsNothing() {
        val controller = source("AssistOverlayController.kt")
        val settle = controller.substringAfter("private fun settle()").substringBefore("private fun warm()")
        assertTrue(settle.contains("firstFrame(overlayLocked(locked, keyguardLocked()), app.calls.state.value.active, talks)"))
        val talkLocked = settle.substringAfter("FirstFrame.TalkLocked ->").substringBefore("FirstFrame.Hold ->")
        assertTrue(talkLocked.contains("ensureVm().foreground(true)"))
        assertTrue(talkLocked.contains("startLive()"))
        assertTrue("the show's start is spent", talkLocked.contains("talkOnFrame = false"))
        assertFalse("a warm session carries the owner's memory: none is opened locked", talkLocked.contains("assistPrewarm") || talkLocked.contains("warm()"))
        assertEquals("Hold does nothing at all", "Unit", settle.substringAfter("FirstFrame.Hold ->").substringBefore("FirstFrame.Warm ->").trim())
        // The call session is warmed in one place, which only an unlocked frame reaches.
        assertEquals(1, Regex("""assistPrewarm\(true\)""").findAll(controller).count())
        assertTrue(controller.contains("private fun warm() { ensureVm().foreground(true); assistPrewarm(true) }"))
    }

    @Test fun startingTheConversationIsNotGatedByTheLockAnywhere() {
        val controller = source("AssistOverlayController.kt")
        assertFalse("no unlock-first gate is left", controller.contains("startNeedsUnlock") || controller.contains("fun unlockFirst"))
        val start = controller.substringAfter("fun startLive()").substringBefore("private fun startRequestedCall")
        assertFalse("a phone that locks while the start is on its way does not end it", start.contains("OverlayLine.Unlock"))
        val requested = controller.substringAfter("private fun startRequestedCall").substringBefore("private fun endWanted")
        assertFalse(requested.contains("OverlayLine.Unlock") || requested.contains("overlayLocked"))
        // The call is made once, by the same entry as an unlocked start; the call service reads the lock itself (CallTrust.forPhone).
        assertEquals(1, Regex("""model\.callBeacon\(\)""").findAll(controller).count())
        assertEquals(1, Regex("""app\.calls\.start\(""").findAll(controller).count())
        // The orb and the line's Call again reach the same start.
        val overlay = source("AssistOverlay.kt")
        assertTrue(overlay.contains("onOrb = { if (live) c.endCall() else c.startLive() }"))
        assertTrue(overlay.contains("LineAction.CallAgain -> c::startLive"))
    }

    @Test fun lockingWhileTheOverlayIsOpenNeverEndsTheConversationItStarts() {
        val controller = source("AssistOverlayController.kt")
        assertTrue(controller.contains("if (locked) lockedWhileOpen() else unlockedWhileOpen()"))
        assertTrue(controller.contains("locked = intent.action == Intent.ACTION_SCREEN_OFF || keyguardLocked()"))
        val locking = controller.substringAfter("private fun lockedWhileOpen()").substringBefore("The phone unlocked with the overlay open")
        // Only what is heard for a written answer stops (dictation ends in the private chat); a start on its way, or a call, carries on.
        assertTrue(locking.contains("dictation.cancel()"))
        listOf("endCall", "live.wanted", "OverlayLine", "startLive", "ensureVm", "warm()", "unlock()", "callBeacon").forEach { assertFalse("locking must not reach $it", locking.contains(it)) }
        // An unlock only takes the unlock line away: the conversation upgrades itself (voice/CallTrust.kt), nothing starts here.
        val unlocked = controller.substringAfter("private fun unlockedWhileOpen()").substringBefore("fun summoned()")
        assertTrue(unlocked.contains("OverlayEvent.Line(null)"))
        listOf("settle()", "talkOnFrame", "startLive", "endCall").forEach { assertFalse("an unlock must not reach $it", unlocked.contains(it)) }
    }

    @Test fun aShowThatHasNoFirstFrameStillStartsTheConversation() {
        val controller = source("AssistOverlayController.kt")
        // The first dust frame can wait (a screen that comes on late, a window the system has not drawn yet): the start never depends on it.
        assertTrue(controller.contains("FIRST_FRAME_FALLBACK_MILLIS"))
        val shown = controller.substringAfter("fun shown(args: Bundle?)").substringBefore("/**\n     * Every drawn dust frame")
        assertTrue(shown.contains("scope.launch { delay(FIRST_FRAME_FALLBACK_MILLIS)"))
        assertTrue("only for the show that scheduled it", shown.contains("shownAt == at"))
        val drawn = controller.substringAfter("fun drawn()").substringBefore("/** What the first frame of a show leads to")
        assertTrue(drawn.contains("firstFrames += "))
        assertTrue("a fallback is not a measured frame", controller.substringAfter("FIRST_FRAME_FALLBACK_MILLIS)").substringBefore("}").contains("settle()"))
        assertTrue(AssistOverlayController.FIRST_FRAME_FALLBACK_MILLIS in 500L..3_000L)
    }

    // --- private things ask for the unlock ---

    @Test fun aPrivateAskOverALockedPhoneShowsTheUnlockLineOnlyForALiveConversation() {
        assertTrue(unlockAsked(asks = 2, seen = 1, locked = true, live = true))
        assertFalse("nothing new was refused", unlockAsked(asks = 1, seen = 1, locked = true, live = true))
        assertFalse("unlocked, nothing waits for the unlock", unlockAsked(asks = 2, seen = 1, locked = false, live = true))
        assertFalse("no conversation, no line", unlockAsked(asks = 2, seen = 1, locked = true, live = false))
        // The line is the existing one: the owner's one action asks Android for the unlock.
        assertEquals(LineAction.Unlock, lineAction(OverlayLine.Unlock))
        val line = reduce(reduce(shown(), OverlayEvent.LiveStarted), OverlayEvent.Line(OverlayLine.Unlock))
        assertEquals(OverlaySurface.Live, line.surface)
        assertEquals("the conversation goes on under the line", OverlayLine.Unlock, line.line)
        // The conversation ending, or an unlocked show, takes the line away.
        assertNull(reduce(line, OverlayEvent.LiveEnded).line)
        assertNull(shown(from = line).line)
        assertNull(shown(fresh = false, from = line).line)
        // Another line is never taken away by the end of the conversation.
        assertEquals(OverlayLine.NotSent, reduce(reduce(reduce(shown(), OverlayEvent.LiveStarted), OverlayEvent.Line(OverlayLine.NotSent)), OverlayEvent.LiveEnded).line)
    }

    @Test fun theControllerWatchesTheConversationsRefusalsAndSaysToUnlock() {
        val controller = source("AssistOverlayController.kt")
        assertTrue(controller.contains("CallTrust.lockedAsks.collect"))
        val watch = controller.substringAfter("private fun askedToUnlock(").substringBefore("fun summoned()")
        assertTrue(watch.contains("unlockAsked(asks, asksSeen, overlayLocked(locked, keyguardLocked()), state.surface == OverlaySurface.Live)"))
        assertTrue(watch.contains("OverlayEvent.Line(OverlayLine.Unlock)"))
        // Each show counts from the refusals it finds, so an old one is never shown again.
        assertTrue(controller.substringAfter("fun shown(args: Bundle?)").substringBefore("fun drawn()").contains("asksSeen = CallTrust.lockedAsks.value"))
    }

    // --- the unlock, and what it brings back ---

    @Test fun theUnlockLineAsksAndTheConversationCarriesOnAfterIt() {
        val activity = source("AssistUnlockActivity.kt")
        assertTrue(activity.contains("private const val THEN_CARRY_ON = \"carry_on\""))
        assertTrue(activity.contains("internal fun carryOn(context: Context) = start(context, THEN_CARRY_ON) {}"))
        // The conversation is the same one: the overlay comes back with what it held (no fresh show, no second start).
        assertTrue(activity.contains("THEN_CARRY_ON -> carryOn()"))
        val carry = activity.substringAfter("private fun carryOn()").substringBefore("companion object")
        // The overlay is usually still up under the unlock, so it is only told; the broadcasts are not what it relies on.
        assertTrue(carry.contains("BeaconAssistSession.current?.get()?.controller?.unlockSeen() == true"))
        assertTrue(carry.indexOf("unlockSeen()") < carry.indexOf("BeaconVoiceInteractionService.reshow()"))
        val seen = source("AssistOverlayController.kt").substringAfter("fun unlockSeen(): Boolean").substringBefore("/** The live conversation turned something private away")
        assertTrue(seen.contains("if (state.phase == OverlayPhase.Hidden) return false"))
        assertTrue(seen.contains("locked = keyguardLocked()"))
        assertTrue(seen.contains("unlockedWhileOpen()"))
        assertTrue(carry.contains("BeaconVoiceInteractionService.reshow()"))
        // Never left with nothing after the PIN: when the overlay can't come back, the app shows the conversation.
        assertTrue(carry.contains("openBeacon(this, AssistTarget(AssistPurpose.ShowCall))"))
        assertFalse(activity.contains("THEN_TALK"))
        assertEquals("the controller asks Android in one place", 1, Regex("""AssistUnlockActivity\.carryOn\(""").findAll(source("AssistOverlayController.kt")).count())
        assertTrue(source("AssistOverlayController.kt").substringAfter("fun unlock()").substringBefore("\n").contains("AssistUnlockActivity.carryOn(app)"))
        assertTrue(source("AssistOverlay.kt").contains("LineAction.Unlock -> c::unlock"))
        // A reshow starts nothing and keeps the running conversation as the surface.
        assertFalse(talksOnShow(reshow = true))
        assertEquals(OverlaySurface.Live, shown(fresh = false, callActive = true).surface)
        assertEquals(FirstFrame.WarmAndTalk, firstFrame(locked = false, callActive = true, talks = talksOnShow(reshow = false)))
        assertEquals(FirstFrame.Warm, firstFrame(locked = false, callActive = true, talks = talksOnShow(reshow = true)))
    }

    @Test fun aCancelledOrFailedUnlockLeavesTheLineAndItsActionAsksAgain() {
        val activity = source("AssistUnlockActivity.kt")
        assertEquals("= finish()", activity.substringAfter("override fun onDismissCancelled()").substringBefore("\n").trim())
        assertEquals("= finish()", activity.substringAfter("override fun onDismissError()").substringBefore("\n").trim())
        val waiting = reduce(reduce(reduce(shown(), OverlayEvent.Summoned), OverlayEvent.LiveStarted), OverlayEvent.Line(OverlayLine.Unlock))
        assertEquals(OverlayLine.Unlock, waiting.line)
        assertEquals(OverlayPhase.Open, waiting.phase)
        // The overlay stays up while Android is asked, and nothing but an unlock, the end of the conversation or a new show clears the line.
        assertFalse(source("AssistOverlayController.kt").substringAfter("fun unlock()").substringBefore("\n").contains("hide()"))
        assertEquals(OverlayLine.Unlock, reduce(waiting, OverlayEvent.Summoned).line)
        assertEquals(OverlayLine.Unlock, reduce(waiting, OverlayEvent.Hidden).line)
    }

    @Test fun anUnlockActivityThatIsRecreatedAfterTheUnlockStillFinishesItsWork() {
        val activity = source("AssistUnlockActivity.kt")
        val create = activity.substringAfter("override fun onCreate").substringBefore("private fun proceed()")
        // The phone's process was killed while the PIN was typed (or the screen turned): the unlock was done, so the work is, once.
        assertTrue(create.contains("if (savedInstanceState != null)"))
        val recreated = create.substringAfter("if (savedInstanceState != null)").substringBefore("setShowWhenLocked(true)")
        assertTrue(recreated.contains("proceed()"))
        assertTrue("only when the phone is unlocked now: a recreation never asks again", recreated.contains("isKeyguardLocked"))
    }

    // --- the fallback entry keeps its unlock-first route (phones without the voice-interaction overlay) ---

    @Test fun theFullAppEntryStillAsksForTheUnlockFirst() {
        val entry = source("AssistEntryActivity.kt")
        assertTrue(entry.contains("deviceLockedNow(this)"))
        assertTrue(entry.contains("AssistUnlockActivity.open(this, AssistTarget())"))
        assertTrue(source("PhoneAssistant.kt").contains("AssistAction.StartCall -> if (dev.hermes.android.phone.deviceLockedNow(context)) AssistUnlockActivity.open(context, target) else vm.callBeacon()"))
    }

    // --- words ---

    @Test fun theLineUsesTheExistingStringsInEveryLanguage() {
        val catalog = JSONObject(File(appRoot, "src/main/assets/native-locales.json").readText(Charsets.UTF_8).removePrefix("﻿"))
        for (language in catalog.keys().asSequence().filter { it != "en" }) {
            val words = catalog.getJSONObject(language)
            assertTrue("$language has Unlock your phone first.", words.optString("unlock your phone first.").isNotBlank())
            assertTrue("$language has Unlock", words.optString("unlock").isNotBlank())
        }
    }

    @Test fun theRuleIsWrittenInTheCommentBlock() {
        val model = source("AssistOverlayModel.kt")
        assertTrue(model.contains("The side button always starts the conversation, locked or not"))
        assertTrue(model.contains("Talk now, unlock for private"))
        assertTrue(model.contains("A conversation is TRUSTED when it started on an unlocked phone"))
        assertTrue(model.contains("Once trusted it stays trusted for that conversation"))
        assertFalse("the Lane LK rule is gone", model.contains("The overlay never starts a conversation on a locked phone."))
        assertFalse(model.contains("asks for the unlock first"))
    }

    private val appRoot = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it).absoluteFile.normalize() }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun source(name: String) = File(appRoot, "src/main/java/dev/hermes/android/assist/$name").readText().replace("\r\n", "\n")
}
