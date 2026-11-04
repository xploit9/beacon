package dev.hermes.android.assist

import dev.hermes.android.core.Scope
import dev.hermes.android.ui.BeaconDustMood
import dev.hermes.android.ui.sphere.SphereState
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallToolApproval
import dev.hermes.android.voice.CallToolReceipt
import dev.hermes.android.voice.CallTranscript
import dev.hermes.android.voice.PhoneApprovalText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lane AO: the side-button assistant talks at once, in one surface. The overlay's reducer and pure rules, without Android: a fresh
 * show goes live (or asks for the microphone), a re-show keeps what was on screen, closing ends the conversation, the text box stays
 * with the orb showing the conversation, the captions are the last two lines, and phone approvals show inside the overlay. All of it
 * holds locked too: the show starts the conversation, which is untrusted until the unlock (AssistLockedTalkTest, Lane LS).
 */
class AssistLiveModelTest {
    private fun shown(fresh: Boolean = true, callActive: Boolean = false) = reduce(AssistOverlayState(), OverlayEvent.Show(fresh = fresh, callActive = callActive))
    private fun open(callActive: Boolean = false) = reduce(shown(callActive = callActive), OverlayEvent.Summoned)

    // --- a fresh show talks at once ---

    @Test fun aFreshShowStartsTheConversationWhenSignedInWithTheMicrophone() {
        assertTrue(talksOnShow(reshow = false))
        assertEquals(LiveStart.Start, liveStart(callActive = false, signedIn = true, micGranted = true))
        // The first frame starts it, the state goes live at once and the pill is still there to hold the orb.
        val live = reduce(open(), OverlayEvent.LiveStarted)
        assertEquals(OverlaySurface.Live, live.surface)
        assertNull(live.line)
    }

    @Test fun aRestoringSessionIsSignedInForTheStartAndTheMissingPiecesAskInOrder() {
        // "Signed in" is the controller's rule (a restoring session counts); the decision only orders what is missing.
        assertEquals(LiveStart.SignIn, liveStart(callActive = false, signedIn = false, micGranted = true))
        assertEquals(LiveStart.SignIn, liveStart(callActive = false, signedIn = false, micGranted = false))
        assertEquals(LiveStart.Microphone, liveStart(callActive = false, signedIn = true, micGranted = false))
        // A conversation that already runs is joined, whatever else is missing.
        assertEquals(LiveStart.Join, liveStart(callActive = true, signedIn = false, micGranted = false))
    }

    @Test fun withoutTheMicrophoneTheFreshShowKeepsThePillWithItsOneTapLine() {
        val line = reduce(open(), OverlayEvent.Line(OverlayLine.Microphone))
        assertEquals("Never a dead end: the pill stays, with its one-tap line", OverlaySurface.Pill, line.surface)
        assertEquals(OverlayLine.Microphone, line.line)
        assertEquals(LineAction.Allow, lineAction(OverlayLine.Microphone))
        // Starting needs nothing but the microphone, locked or not (AssistLockedTalkTest).
        assertFalse(needsUnlock(OverlayAction.Live, locked = false))
        assertFalse(needsUnlock(OverlayAction.Live, locked = true))
        // The orb is the talk button again, ready for a tap once the microphone is allowed.
        assertEquals(OrbFace.Talk, orbFace(live = false, call = CallState()))
    }

    @Test fun aReshowKeepsWhatWasOnScreenAndDoesNotStartAnything() {
        assertFalse(talksOnShow(reshow = true))
        val answered = reduce(reduce(open(), OverlayEvent.Asked("Hi", false, "s1")), OverlayEvent.Progress("Hello.", streaming = false))
        val back = reduce(reduce(answered, OverlayEvent.Hidden), OverlayEvent.Show(fresh = false, callActive = false))
        assertEquals(OverlaySurface.Answer, back.surface)
        assertEquals("Hello.", back.ask?.answer)
        // A conversation that is really running is what a show comes back to; one that ended is not kept.
        assertEquals(OverlaySurface.Live, shown(fresh = false, callActive = true).surface)
        val ended = reduce(reduce(open(), OverlayEvent.LiveStarted), OverlayEvent.Hidden)
        assertEquals(OverlaySurface.Pill, reduce(ended, OverlayEvent.Show(fresh = false, callActive = false)).surface)
    }

    // --- closing ends it ---

    @Test fun closingTheOverlayLeavesNothingLive() {
        val live = reduce(open(), OverlayEvent.LiveStarted)
        // The dust falls away with the captions still up; once it is gone the live surface is gone with it.
        val leaving = reduce(live, OverlayEvent.Dismiss)
        assertEquals(OverlayPhase.Dismissing, leaving.phase)
        assertEquals(OverlaySurface.Live, leaving.surface)
        assertEquals(BeaconDustMood.Dismiss, dustMood(leaving, SphereState.Listening))
        val hidden = reduce(leaving, OverlayEvent.Hidden)
        assertEquals(OverlayPhase.Hidden, hidden.phase)
        assertEquals(OverlaySurface.Pill, hidden.surface)
        // Whatever closes it (the system hiding it, an unlock, another app) ends the conversation too: it is the one hook.
        assertEquals(OverlaySurface.Pill, reduce(live, OverlayEvent.Hidden).surface)
        // A written answer that was under the conversation is back, not lost.
        val withCard = reduce(reduce(reduce(open(), OverlayEvent.Asked("Hi", false, "s1")), OverlayEvent.Progress("Hello.", streaming = false)), OverlayEvent.LiveStarted)
        assertEquals(OverlaySurface.Answer, reduce(withCard, OverlayEvent.Hidden).surface)
    }

    @Test fun aSessionStillRestoringIsWaitedForBeforeTheChatsCallEntryIsUsed() {
        // The chat's call entry gives up on a signed-out chat, so a first show right after the app started must not call it too early.
        val start = source("AssistOverlayController.kt").substringAfter("fun startLive()").substringBefore("private fun startRequestedCall")
        assertTrue(start.indexOf("restoringSession") in 0 until start.indexOf("model.callBeacon()"))
        assertTrue("A start that was closed or ended meanwhile never calls", start.indexOf("if (!live.wanted) return@launch") in 0 until start.indexOf("model.callBeacon()"))
        assertTrue("A session that ends up signed out says to sign in", start.contains("endWanted(OverlayLine.SignIn)"))
    }

    @Test fun theConversationEndsWithTheOrbAndThePillIsItsOwnAgain() {
        val ended = reduce(reduce(open(), OverlayEvent.LiveStarted), OverlayEvent.LiveEnded)
        assertEquals(OverlaySurface.Pill, ended.surface)
        assertEquals(OrbFace.Talk, orbFace(live = false, call = CallState(active = true, state = "listening")))
    }

    @Test fun theOverlayHoldsNoOtherWindowForTheConversation() {
        // One surface: no band of its own, no "Open call" panel, no call screen, no floating call window, no hint above the orb.
        assertEquals(listOf("Pill", "Answer", "Live"), OverlaySurface.entries.map { it.name })
        val overlay = source("AssistOverlay.kt") + source("AssistLive.kt")
        listOf("LiveBand", "assist-live-band", "assist-live-open", "Open call", "AssistPurpose.ShowCall", "OverlayAction.OpenCall", "TalkHint", "Talk live", "playTalkHint")
            .forEach { assertFalse("$it must be gone from the overlay", overlay.contains(it)) }
        assertTrue(OverlayAction.entries.none { it.name == "OpenCall" })
        // The window the app would float or open for a call is held away the whole time the overlay shows, and let go when it hides.
        val controller = source("AssistOverlayController.kt")
        assertTrue(controller.contains("holdCallWindow()"))
        assertTrue(controller.contains("app.calls.uiVisible = true"))
        assertTrue(controller.contains("releaseCallWindow()"))
        assertFalse("The overlay never opens the app's call screen", controller.contains("ShowCall"))
        // The first frame is drawn before anything of the conversation starts, and the hang-up comes before the window is let go.
        val drawn = controller.substringAfter("fun drawn()").substringBefore("fun summoned()")
        assertTrue(drawn.indexOf("firstFrames +=") < drawn.indexOf("startLive()"))
        assertTrue(drawn.contains("Handler(android.os.Looper.getMainLooper()).post"))
        val hidden = controller.substringAfter("fun hidden()").substringBefore("fun destroyed()")
        assertTrue(hidden.indexOf("closeLive()") in 0 until hidden.indexOf("releaseCallWindow()"))
        assertTrue(controller.substringAfter("fun dismiss()").substringBefore("fun closeCard()").contains("closeLive()"))
    }

    // --- one surface: the text box stays, the captions sit above it ---

    @Test fun theCaptionsAreTheLastTwoLinesTheOwnersThenTheAssistants() {
        fun line(id: String, role: String, text: String) = CallTranscript(id, role, text)
        val lines = listOf(line("1", "user", "What is on today?"), line("2", "assistant", "Two meetings."), line("3", "user", "Move the second."), line("4", "assistant", "  Done. It is at 4.  "))
        assertEquals(listOf(Caption(true, "Move the second."), Caption(false, "Done. It is at 4.")), liveCaptions(lines, emptyList()))
        // Blank lines (a turn that has only just begun) never take a place; typed turns are the owner's own words.
        assertEquals(listOf(Caption(false, "Two meetings."), Caption(true, "Thanks")), liveCaptions(lines.take(2) + line("5", "user", "   "), listOf("Thanks")))
        assertEquals(emptyList<Caption>(), liveCaptions(emptyList(), emptyList()))
    }

    @Test fun theOrbShowsListeningSpeakingWorkingOrConnecting() {
        fun face(call: CallState) = orbFace(live = true, call = call)
        assertEquals(OrbFace.Connecting, face(CallState()))
        assertEquals(OrbFace.Connecting, face(CallState(active = true, state = "connecting")))
        assertEquals(OrbFace.Listening, face(CallState(active = true, state = "listening")))
        assertEquals(OrbFace.Speaking, face(CallState(active = true, state = "speaking")))
        assertEquals(OrbFace.Speaking, face(CallState(active = true, state = "listening", remoteSpeaking = true)))
        assertEquals(OrbFace.Working, face(CallState(active = true, state = "thinking")))
        // A tool at work is working, too; a muted conversation still just listens.
        val tool = CallToolReceipt("r1", "mcp__gmail__search", "running")
        assertEquals(OrbFace.Working, face(CallState(active = true, state = "listening", toolReceipts = listOf(tool))))
        assertEquals(OrbFace.Listening, face(CallState(active = true, state = "listening", muted = true)))
    }

    @Test fun theTextBoxKeepsTypingAndTheOrbTapEndsTheConversation() {
        // Typing during the conversation is a typed turn: the controller sends it into the call, never into a written answer.
        val controller = source("AssistOverlayController.kt")
        val ask = controller.substringAfter("fun ask(").substringBefore("/** The question didn't reach Hermes")
        assertTrue(ask.indexOf("OverlaySurface.Live") in 0 until ask.indexOf("awaitAssistantChat"))
        assertTrue(ask.contains("typeIntoCall"))
        // The pill stays whatever the surface: it is drawn once, after what sits above it, live or not.
        val overlay = source("AssistOverlay.kt")
        assertEquals(1, Regex("""Pill\(PillInput\(""").findAll(overlay).count())
        assertTrue(overlay.contains("onOrb = { if (live) c.endCall() else c.startLive() }"))
        assertTrue(overlay.indexOf("LiveAbovePill(") < overlay.indexOf("Pill(PillInput("))
        // The orb is "End call" for TalkBack while live, and its words are the conversation's state.
        assertTrue(overlay.contains("uiText(\"End call\")"))
        assertTrue(overlay.contains("stateDescription = p.status"))
    }

    // --- approvals inside the overlay ---

    private val scope = Scope("https://hermes.example.com", "owner", "default")
    private val countdown = CallToolApproval("r1", "p".repeat(32), "phone_call", """{"number":"+15550100"}""", allowCall = false,
        phone = PhoneApprovalText("Call Alex Example", "+15550100", "", "", countdown = 3))
    private val request = CallToolApproval("r2", "q".repeat(32), "phone_send_sms", """{"to":"+15550100","body":"On my way"}""",
        phone = PhoneApprovalText("Text Alex Example", "+15550100", "", "On my way"))

    private fun call(vararg approvals: CallToolApproval, active: Boolean = true, scope: Scope? = this.scope) = CallState(active = active, conversationScope = scope, toolApprovals = approvals.toList())

    @Test fun theApprovalTheConversationWaitsOnIsTheSignedInAccountsAndOnlyWhileTheCallRuns() {
        val same = { c: CallState -> waitingApproval(c, authenticated = true, origin = scope.origin, owner = scope.owner, profile = scope.profile) }
        assertEquals(countdown, same(call(countdown, request)))
        assertNull("No call, no card", same(call(countdown, active = false)))
        assertNull("Another account's request never shows", same(call(countdown, scope = scope.copy(owner = "someone-else"))))
        assertNull(same(call(countdown, scope = scope.copy(profile = "work"))))
        assertNull(same(call(countdown, scope = null)))
        assertNull("Signed out shows nothing", waitingApproval(call(countdown), authenticated = false, origin = scope.origin, owner = scope.owner, profile = scope.profile))
        assertNull(same(call()))
    }

    @Test fun theCardShowsInTheOverlayAndLockedOnlyThatSomethingWaits() {
        assertEquals(ApprovalShow.Card, approvalShow(live = true, approval = countdown, locked = false))
        assertEquals(ApprovalShow.Card, approvalShow(live = true, approval = request, locked = false))
        // Locked, never the details: a row that says something waits and offers no, since allowing needs the phone unlocked.
        assertEquals(ApprovalShow.Locked, approvalShow(live = true, approval = countdown, locked = true))
        assertEquals(ApprovalShow.None, approvalShow(live = false, approval = countdown, locked = false))
        assertEquals(ApprovalShow.None, approvalShow(live = true, approval = null, locked = true))
        val locked = source("AssistLive.kt").substringAfter("fun LockedApprovalRow").substringBefore("/**")
        listOf("summary", "phone", "target", "countdown", "arguments", "toolName").forEach { assertFalse("The locked row never reads $it", locked.contains(it)) }
        // The controller refuses a yes while locked, whatever asks for it, reading the lock screen live at that moment; a no is always allowed.
        val decide = source("AssistOverlayController.kt").substringAfter("fun decide(").substringBefore("private fun typeIntoCall")
        assertTrue(decide.contains("if (decision != \"deny\" && overlayLocked(locked, keyguardLocked())) return"))
        // The lock state follows the screen turning off and on and the unlock while the overlay shows, not only the moment it was shown.
        val controller = source("AssistOverlayController.kt")
        assertTrue(controller.contains("ACTION_SCREEN_OFF") && controller.contains("ACTION_SCREEN_ON") && controller.contains("ACTION_USER_PRESENT"))
        assertTrue(controller.contains("locked = intent.action == Intent.ACTION_SCREEN_OFF || keyguardLocked()"))
    }

    @Test fun theApprovalCardIsAnswerableWithoutTheApp() {
        // Both cards answer through the call's own decision path; the overlay holds the call window so that path is open to it.
        val live = source("AssistLive.kt")
        assertTrue(live.contains("rememberCountdownRun(approval, onDecision, now, locked)"))
        assertTrue(live.contains("PhoneCountdownButtons(approval, run)"))
        assertTrue(live.contains("onDecision(\"deny\")") && live.contains("onDecision(\"approve\")") && live.contains("onDecision(\"approve_call\")"))
        assertTrue(source("AssistOverlayController.kt").contains("app.calls.decideTool(app.calls.state.value.callId, approval.requestId, approval.proposalId, decision)"))
        // The conversation's tool activity is one short line, from the same receipts the call status reads.
        assertTrue(source("AssistOverlay.kt").contains("tool?.statusTemplate()"))
    }

    private val appRoot = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun source(name: String) = File(appRoot, "src/main/java/dev/hermes/android/assist/$name").readText()
}
