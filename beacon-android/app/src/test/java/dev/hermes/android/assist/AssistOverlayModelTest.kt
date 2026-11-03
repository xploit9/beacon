package dev.hermes.android.assist

import dev.hermes.android.ChatState
import dev.hermes.android.Message
import dev.hermes.android.ui.BeaconDustMood
import dev.hermes.android.ui.normalized
import dev.hermes.android.ui.outlinePoint
import dev.hermes.android.ui.sphere.SphereState
import dev.hermes.android.voice.CALL_DROPPED
import dev.hermes.android.voice.CALL_HERMES_NO_ANSWER
import dev.hermes.android.voice.CALL_MIC_FAILED
import dev.hermes.android.voice.CALL_SIGN_IN_EXPIRED
import dev.hermes.android.voice.CALL_VOICE_NO_ANSWER
import dev.hermes.android.voice.CALL_VOICE_STOPPED
import dev.hermes.android.voice.CallFailureStrings
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Lanes assist-overlay, ux-overlay and ux2-overlay: the overlay's state machine, its dust, its chips, the call-failure reasons, the
 * first summon's hint and the lock-screen privacy rules, without Android.
 */
class AssistOverlayModelTest {
    private fun shown(callActive: Boolean = false) = reduce(AssistOverlayState(), OverlayEvent.Show(fresh = true, callActive = callActive))
    private fun open(callActive: Boolean = false) = reduce(shown(callActive), OverlayEvent.Summoned)

    // --- the state machine ---

    @Test fun aGestureSummonsThePillThenOpens() {
        val summoning = shown()
        assertEquals(OverlayPhase.Summoning, summoning.phase)
        assertEquals(OverlaySurface.Pill, summoning.surface)
        assertEquals(1, summoning.shows)
        assertEquals(OverlayPhase.Open, reduce(summoning, OverlayEvent.Summoned).phase)
        // A late summon tick after the owner already closed it does not reopen anything.
        val leaving = reduce(summoning, OverlayEvent.Dismiss)
        assertEquals(OverlayPhase.Dismissing, reduce(leaving, OverlayEvent.Summoned).phase)
    }

    @Test fun aRunningCallOpensStraightIntoTheLiveConversation() {
        assertEquals(OverlaySurface.Live, shown(callActive = true).surface)
        val asked = reduce(open(), OverlayEvent.Asked("Weather?", false, "s1"))
        // A call started elsewhere while an answer shows takes over; when it ends the answer is back.
        val live = reduce(asked, OverlayEvent.LiveStarted)
        assertEquals(OverlaySurface.Live, live.surface)
        assertEquals(OverlaySurface.Answer, reduce(live, OverlayEvent.LiveEnded).surface)
        assertEquals(OverlaySurface.Pill, reduce(reduce(open(), OverlayEvent.LiveStarted), OverlayEvent.LiveEnded).surface)
    }

    @Test fun askingShowsTheCardAndTheReplyStreamsIntoIt() {
        val asked = reduce(open(), OverlayEvent.Asked("  What's next today?  ", withScreen = true, sessionId = "s1"))
        assertEquals(OverlaySurface.Answer, asked.surface)
        assertEquals("What's next today?", asked.ask?.question)
        assertTrue(asked.ask!!.streaming && asked.ask!!.withScreen)
        val streaming = reduce(asked, OverlayEvent.Progress("Your", streaming = true))
        val done = reduce(streaming, OverlayEvent.Progress("Your day is clear.", streaming = false))
        assertEquals("Your day is clear.", done.ask?.answer)
        assertFalse(done.ask!!.streaming)
    }

    @Test fun aQuestionThatWasNotSentSaysSoAndStopsThinking() {
        val failed = reduce(reduce(open(), OverlayEvent.Asked("Hi", false, "")), OverlayEvent.NotSent)
        assertFalse(failed.ask!!.streaming)
        assertTrue(failed.ask!!.failed)
        assertEquals(OverlayLine.NotSent, failed.line)
        assertEquals("The surface goes back to the pill: no empty card repeats the question", OverlaySurface.Pill, failed.surface)
        assertEquals(AnswerBody.Empty, answerBody(locked = false, ask = failed.ask))
        // Without a question in flight there is nothing to fail.
        assertEquals(open(), reduce(open(), OverlayEvent.NotSent))
    }

    @Test fun dictationShowsWhatItHearsAndALineStopsIt() {
        val listening = reduce(open(), OverlayEvent.DictationStarted)
        assertTrue(listening.dictating)
        assertEquals("turn on", reduce(listening, OverlayEvent.Heard("turn on")).heard)
        // Words that arrive after dictation stopped are dropped.
        assertEquals("", reduce(open(), OverlayEvent.Heard("late")).heard)
        val blocked = reduce(listening, OverlayEvent.Line(OverlayLine.Microphone))
        assertFalse(blocked.dictating)
        assertEquals(OverlayLine.Microphone, blocked.line)
    }

    @Test fun closingTheCardLeavesThePillAndAFreshGestureClearsTheLastQuestion() {
        val asked = reduce(open(), OverlayEvent.Asked("Hi", false, "s1"))
        val closed = reduce(asked, OverlayEvent.CloseCard)
        assertEquals(OverlaySurface.Pill, closed.surface)
        assertNull(closed.ask)
        val hidden = reduce(asked, OverlayEvent.Hidden)
        assertNull(reduce(hidden, OverlayEvent.Show(fresh = true, callActive = false)).ask)
        // An unlock brings the same answer back instead of a fresh pill.
        val back = reduce(hidden, OverlayEvent.Show(fresh = false, callActive = false))
        assertEquals("Hi", back.ask?.question)
        assertEquals(OverlaySurface.Answer, back.surface)
        assertEquals(OverlayPhase.Summoning, back.phase)
    }

    @Test fun dismissFallsAwayOnceAndHidingEndsDictation() {
        val listening = reduce(open(), OverlayEvent.DictationStarted)
        val leaving = reduce(listening, OverlayEvent.Dismiss)
        assertEquals(OverlayPhase.Dismissing, leaving.phase)
        assertFalse(leaving.dictating)
        val hidden = reduce(leaving, OverlayEvent.Hidden)
        assertEquals(OverlayPhase.Hidden, hidden.phase)
        assertEquals(hidden, reduce(hidden, OverlayEvent.Dismiss))
    }

    // --- "Couldn't send": the way back ---

    @Test fun couldntSendOffersTryAgainAndPutsTheQuestionBack() {
        assertEquals(LineAction.TryAgain, lineAction(OverlayLine.NotSent))
        assertEquals("What's next today?", draftAfterNotSent("", "What's next today?"))
        assertEquals("What's next today?", draftAfterNotSent("   ", "What's next today?"))
        // Something the owner typed since stays.
        assertEquals("Actually, tomorrow", draftAfterNotSent("Actually, tomorrow", "What's next today?"))
    }

    @Test fun aLateReplyClearsTheStaleCouldntSendLine() {
        val failed = reduce(reduce(open(), OverlayEvent.Asked("Hi", false, "s1")), OverlayEvent.NotSent)
        // A chat change that shows nothing of the question keeps it failed: no "Thinking…" under "Couldn't send".
        assertEquals(failed, reduce(failed, OverlayEvent.Progress("", streaming = true, accepted = false)))
        // Hermes held it after all: the line goes and the answer streams in.
        val late = reduce(failed, OverlayEvent.Progress("Hello", streaming = true, accepted = true))
        assertNull(late.line)
        assertFalse(late.ask!!.failed)
        assertEquals("Hello", late.ask.answer)
        assertEquals(AnswerBody.Text, answerBody(locked = false, ask = late.ask))
        // The question put back in the field goes with it, unless the owner changed it.
        assertEquals("", draftAfterLateAnswer("Hi", "Hi"))
        assertEquals("Hi there", draftAfterLateAnswer("Hi there", "Hi"))
    }

    @Test fun aReplyToAnotherQuestionLeavesAnUnrelatedCouldntSendLine() {
        // A typed turn in a call that didn't go says so; the earlier written answer still updating must not take that line away.
        val answered = reduce(reduce(open(), OverlayEvent.Asked("Hi", false, "s1")), OverlayEvent.Progress("Hello", streaming = false))
        val typedFailed = reduce(answered, OverlayEvent.Line(OverlayLine.NotSent))
        assertEquals(OverlayLine.NotSent, reduce(typedFailed, OverlayEvent.Progress("Hello again", streaming = false)).line)
    }

    // --- a call that didn't start ---

    @Test fun aCallFailureKeepsItsShortReasonAndOffersCallAgain() {
        val failed = reduce(open(), OverlayEvent.CallFailed(CallReason.HermesNoAnswer))
        assertEquals(OverlayLine.CallFailed, failed.line)
        assertEquals(CallReason.HermesNoAnswer, failed.callFailure)
        assertEquals(LineAction.CallAgain, lineAction(OverlayLine.CallFailed, failed.callFailure))
        // No reason the overlay has a short line for: the plain line, never an older reason.
        assertNull(reduce(failed, OverlayEvent.Line(OverlayLine.CallFailed)).callFailure)
        assertNull(reduce(open(), OverlayEvent.CallFailed(null)).callFailure)
        // Calling again clears it; so does closing the line.
        val again = reduce(failed, OverlayEvent.LiveStarted)
        assertNull(again.line)
        assertNull(again.callFailure)
        assertNull(reduce(failed, OverlayEvent.Line(null)).callFailure)
    }

    @Test fun everyLineThatCanBeFixedHereOffersItsOneAction() {
        assertEquals(LineAction.Allow, lineAction(OverlayLine.Microphone))
        assertEquals(LineAction.Open, lineAction(OverlayLine.VoiceKey))
        assertEquals(LineAction.Open, lineAction(OverlayLine.SignIn))
        assertEquals(LineAction.OpenWaiting, lineAction(OverlayLine.Waiting))
        assertNull(lineAction(OverlayLine.NotHeard))
        assertNull(lineAction(OverlayLine.NoRecognizer))
    }

    // --- the dust ---

    @Test fun theDustFollowsWhatTheOverlayIsDoing() {
        assertEquals(BeaconDustMood.Summon, dustMood(shown()))
        assertEquals(BeaconDustMood.Idle, dustMood(open()))
        assertEquals(BeaconDustMood.Listening, dustMood(reduce(open(), OverlayEvent.DictationStarted)))
        val asked = reduce(open(), OverlayEvent.Asked("Hi", false, "s1"))
        assertEquals(BeaconDustMood.Thinking, dustMood(asked))
        assertEquals(BeaconDustMood.Speaking, dustMood(reduce(asked, OverlayEvent.Progress("Hello", streaming = true))))
        assertEquals(BeaconDustMood.Idle, dustMood(reduce(asked, OverlayEvent.Progress("Hello", streaming = false))))
        assertEquals(BeaconDustMood.Dismiss, dustMood(reduce(open(), OverlayEvent.Dismiss)))
    }

    @Test fun inALiveCallTheDustIsTheCallsOwnState() {
        val live = open(callActive = true)
        assertEquals(BeaconDustMood.Listening, dustMood(live, SphereState.Listening))
        assertEquals(BeaconDustMood.Thinking, dustMood(live, SphereState.Thinking))
        assertEquals(BeaconDustMood.Speaking, dustMood(live, SphereState.Speaking))
        assertEquals(BeaconDustMood.Idle, dustMood(live, SphereState.Error))
    }

    @Test fun summonedDustCondensesOntoThePillsOutline() {
        val pill = Rect(16f, 900f, 1064f, 1028f)
        val r = pill.height / 2f
        for (i in 0..200) {
            val p = outlinePoint(i / 200f, pill)
            // On a straight edge, or exactly one radius from a cap's centre.
            val straight = p.x in pill.left + r - .01f..pill.right - r + .01f && (abs(p.y - pill.top) < .01f || abs(p.y - pill.bottom) < .01f)
            val capCentre = if (p.x < pill.center.x) pill.left + r else pill.right - r
            val cap = abs(hypot(p.x - capCentre, p.y - pill.center.y) - r) < .05f
            assertTrue("point $i at $p is off the outline", straight || cap)
        }
    }

    @Test fun theScreenStaysOnOnlyWhileTheOverlayWorksOrWasJustRead() {
        assertFalse("A bare pill that nobody touched for a while sleeps like the phone does", keepsScreenOn(open(), reading = false))
        assertTrue(keepsScreenOn(reduce(open(), OverlayEvent.DictationStarted), reading = false))
        assertTrue(keepsScreenOn(open(callActive = true), reading = false))
        val asked = reduce(open(), OverlayEvent.Asked("Hi", false, "s1"))
        assertTrue(keepsScreenOn(asked, reading = false))
        val answered = reduce(asked, OverlayEvent.Progress("Hello.", streaming = false))
        assertTrue("Half a minute to read it", keepsScreenOn(answered, reading = true))
        assertFalse("Then the phone may sleep", keepsScreenOn(answered, reading = false))
        assertFalse("Never once hidden", keepsScreenOn(reduce(asked, OverlayEvent.Hidden), reading = true))
    }

    @Test fun anOpenIdleOverlayHoldsTheScreenAfterTheLastInteractionAndThenLetsItSleep() {
        // Summoned, typed into or touched a moment ago: the phone's 10 s lock timeout must not close an overlay the owner is thinking over.
        assertTrue(keepsScreenOn(open(), reading = false, idleHold = true))
        assertTrue(keepsScreenOn(shown(), reading = false, idleHold = true))
        assertFalse("Once the window has passed, the screen sleeps as normal", keepsScreenOn(open(), reading = false, idleHold = false))
        // A hidden overlay never holds the screen, whatever the window says; working and answering still hold it on their own.
        assertFalse(keepsScreenOn(reduce(open(), OverlayEvent.Hidden), reading = false, idleHold = true))
        assertTrue(keepsScreenOn(reduce(open(), OverlayEvent.DictationStarted), reading = false, idleHold = false))
        assertTrue(keepsScreenOn(reduce(open(), OverlayEvent.Asked("Hi", false, "s1")), reading = false, idleHold = false))
        assertEquals(30_000L, IDLE_HOLD_MILLIS)
        // The overlay wires the window to every touch (seen before the controls, nothing consumed), typing, summons and changes.
        val overlay = source("AssistOverlay.kt")
        assertTrue(overlay.contains("keepsScreenOn(state, reading, idleHold)"))
        assertTrue(overlay.contains("awaitPointerEvent(PointerEventPass.Initial)"))
        assertTrue(overlay.contains("holdWhileIdle({ SystemClock.uptimeMillis() }, { lastTouch[0] }, { idleHold = it }, IDLE_HOLD_MILLIS)"))
        assertTrue(overlay.contains("LaunchedEffect(state.shows, state.surface, state.line, state.dictating, state.ask?.streaming, c.draft.text)"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun theIdleHoldEndsThirtySecondsAfterTheLastInteractionAndATouchExtendsIt() = runTest {
        val log = mutableListOf<String>()
        var last = 0L
        val job = launch { holdWhileIdle({ currentTime }, { last }, { log += "$currentTime $it" }, IDLE_HOLD_MILLIS) }
        runCurrent()
        assertEquals(listOf("0 true"), log)
        // Still held a moment before the half minute is up; released exactly at it.
        advanceTimeBy(IDLE_HOLD_MILLIS - 1); runCurrent()
        assertEquals(listOf("0 true"), log)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("0 true", "30000 false"), log)
        assertFalse(job.isActive)
        // A touch 20 s in moves the end to 50 s.
        log.clear()
        val base = currentTime
        last = base
        val touched = launch { holdWhileIdle({ currentTime }, { last }, { log += "${currentTime - base} $it" }, IDLE_HOLD_MILLIS) }
        runCurrent()
        advanceTimeBy(20_000); runCurrent()
        last = currentTime
        advanceTimeBy(29_999); runCurrent()
        assertEquals("Held 49.999 s after the start, as the touch at 20 s moved the end to 50 s", listOf("0 true"), log)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("0 true", "50000 false"), log)
        assertFalse(touched.isActive)
    }

    // --- the lock screen ---

    @Test fun lockedEveryWrittenAnswerWaitsForAnUnlock() {
        // The chat a locked question goes to carries history and memory, so no answer is plain enough to show locked.
        assertEquals(AnswerReveal.UnlockToSee, answerReveal(locked = true))
        assertEquals(AnswerReveal.Shown, answerReveal(locked = false))
        val done = OverlayAsk("What is 2+2?", answer = "4", streaming = false)
        assertEquals(AnswerBody.Locked, answerBody(locked = true, ask = done))
        assertEquals(AnswerBody.Text, answerBody(locked = false, ask = done))
        // Finished with no words at all, it still waits: the lock row never says what is behind it.
        assertEquals(AnswerBody.Locked, answerBody(locked = true, ask = done.copy(answer = "")))
    }

    @Test fun whileALockedAnswerStreamsTheCardOnlyThinks() {
        val partial = OverlayAsk("What did I ask this morning?", answer = "You asked about", streaming = true)
        assertEquals(AnswerBody.Thinking, answerBody(locked = true, ask = partial))
        assertEquals(AnswerBody.Thinking, answerBody(locked = true, ask = partial.copy(answer = "")))
        // Unlocked, the reply streams in as it comes.
        assertEquals(AnswerBody.Text, answerBody(locked = false, ask = partial))
        assertEquals(AnswerBody.Thinking, answerBody(locked = false, ask = partial.copy(answer = "")))
        // Once the phone unlocks the same answer shows at once; nothing is asked again.
        val asked = reduce(open(), OverlayEvent.Asked("What did I ask this morning?", false, "s1"))
        val finished = reduce(asked, OverlayEvent.Progress("You asked about the weather.", streaming = false))
        assertEquals(AnswerBody.Locked, answerBody(locked = true, ask = finished.ask!!))
        val back = reduce(reduce(finished, OverlayEvent.Hidden), OverlayEvent.Show(fresh = false, callActive = false))
        assertEquals(AnswerBody.Text, answerBody(locked = false, ask = back.ask!!))
        assertEquals("You asked about the weather.", back.ask.answer)
    }

    /** Strix vuln-0005: the card drew the owner's own question over the lock screen while only the reply waited for the unlock. */
    @Test fun lockedTheOwnersQuestionIsHiddenLikeTheReply() {
        val question = "What did the clinic say about my biopsy results?"
        val done = OverlayAsk(question, answer = "Clear. No further tests until March.", streaming = false)
        assertEquals("", answerQuestion(locked = true, ask = done))
        assertEquals("a reply still streaming hides it too", "", answerQuestion(locked = true, ask = done.copy(answer = "", streaming = true)))
        assertEquals(question, answerQuestion(locked = false, ask = done))
        // The phone locks with the card up: the state keeps the question (it is what comes back after the unlock); the draw drops it.
        val answered = reduce(reduce(reduce(open(), OverlayEvent.Asked(question, false, "s1")), OverlayEvent.Progress(done.answer, streaming = false)), OverlayEvent.DictationStopped)
        assertEquals(OverlaySurface.Answer, answered.surface)
        assertEquals(question, answered.ask?.question)
        assertEquals("", answerQuestion(locked = true, ask = answered.ask!!))
        // Hide, lock, show again: the card returns with its question held, and it is drawn only once the phone is unlocked.
        val reshown = reduce(reduce(answered, OverlayEvent.Hidden), OverlayEvent.Show(fresh = false, callActive = false, locked = true))
        assertEquals(OverlaySurface.Answer, reshown.surface)
        assertEquals("", answerQuestion(locked = true, ask = reshown.ask!!))
        assertEquals(question, answerQuestion(locked = false, ask = reshown.ask))
    }

    @Test fun lockedNothingIsAskedHeardOrStartedAndOpeningAnythingUnlocksFirst() {
        // On a locked phone the side button asks for the unlock first (Lane LK): asking, dictating and starting wait for it, as does
        // everything that opens the app or a settings screen. Ending a conversation, closing and dismissing never do.
        val free = listOf(OverlayAction.EndCall, OverlayAction.Type, OverlayAction.CloseCard, OverlayAction.Dismiss, OverlayAction.AttachScreen)
        val gated = listOf(OverlayAction.Ask, OverlayAction.Dictate, OverlayAction.Live,
            OverlayAction.OpenInBeacon, OverlayAction.ContinueChat, OverlayAction.OpenWaiting, OverlayAction.Reveal, OverlayAction.Settings)
        assertEquals(OverlayAction.entries.toSet(), (free + gated).toSet())
        free.forEach { assertFalse("$it must work locked", needsUnlock(it, locked = true)) }
        gated.forEach { assertTrue("$it must unlock first", needsUnlock(it, locked = true)) }
        OverlayAction.entries.forEach { assertFalse(needsUnlock(it, locked = false)) }
    }

    @Test fun theLockScreenNeverShowsHistoryOrTheScreen() {
        val last = AssistChip.Continue("Trip to Lisbon", "c1", "default")
        val waiting = AssistChip.Waiting(approval = true)
        // Only context: what waits, the screen, the last chat. Talking live is the orb in the pill, so it has no chip.
        assertEquals(listOf(waiting, AssistChip.AboutScreen, last), assistChips(screenshot = true, locked = false, last = last, waiting = waiting))
        // Locked: no chat title and no screen, even if a screenshot somehow arrived; what waits shows without saying what.
        assertEquals(listOf(waiting), assistChips(screenshot = true, locked = true, last = last, waiting = waiting))
        assertEquals(emptyList<AssistChip>(), assistChips(screenshot = false, locked = true, last = last, waiting = null))
        assertEquals(emptyList<AssistChip>(), assistChips(false, false, AssistChip.Continue(" ", "c1", "default"), null))
    }

    // --- the chips fit ---

    private val waitingChip = AssistChip.Waiting(approval = true)
    private val continueChip = AssistChip.Continue("Budget for the move in M…", "c1", "default")
    private fun widths(waiting: Int = 150, screen: Int = 200, continued: Int = 300): (AssistChip) -> Int = { chip ->
        when (chip) { is AssistChip.Waiting -> waiting; AssistChip.AboutScreen -> screen; is AssistChip.Continue -> continued }
    }

    @Test fun everyChipFitsWholeAtRest() {
        val chips = listOf(waitingChip, AssistChip.AboutScreen, continueChip)
        // Room for all: each at its own width.
        assertEquals(listOf(waitingChip to 150, AssistChip.AboutScreen to 200, continueChip to 300), fitChips(chips, room = 700, gap = 10, continueMin = 120, width = widths()))
        // Tighter: Continue takes what is left, its title ellipsized; nothing reaches past the edge.
        val tight = fitChips(chips, room = 560, gap = 10, continueMin = 120, width = widths())
        assertEquals(listOf(waitingChip to 150, AssistChip.AboutScreen to 200, continueChip to 190), tight)
        assertTrue(tight.sumOf { it.second } + 10 * (tight.size - 1) <= 560)
        // Too tight for a useful Continue: it is left out instead of being cut.
        assertEquals(listOf(waitingChip to 150, AssistChip.AboutScreen to 200), fitChips(chips, room = 450, gap = 10, continueMin = 120, width = widths()))
    }

    @Test fun atLargeTextTheScreenChipGivesWayButWhatWaitsAlwaysShows() {
        val chips = listOf(waitingChip, AssistChip.AboutScreen, continueChip)
        // Large text: the screen chip no longer fits beside what waits; the pill's + attaches the screen all the same.
        assertEquals(listOf(waitingChip to 200), fitChips(chips, room = 380, gap = 10, continueMin = 180, width = widths(waiting = 200, screen = 260, continued = 400)))
        // Wider than the whole row, what waits still shows, ellipsized to the row.
        assertEquals(listOf(waitingChip to 380), fitChips(listOf(waitingChip), room = 380, gap = 10, continueMin = 180, width = widths(waiting = 500)))
        // Without it, the screen chip and a shortened Continue share the line.
        assertEquals(listOf(AssistChip.AboutScreen to 200, continueChip to 170), fitChips(listOf(AssistChip.AboutScreen, continueChip), room = 380, gap = 10, continueMin = 120, width = widths()))
        assertEquals(emptyList<Pair<AssistChip, Int>>(), fitChips(emptyList(), room = 380, gap = 10, continueMin = 120, width = widths()))
    }

    @Test fun aLongChatTitleIsCutAtTwentyFourCharactersWithAnEllipsis() {
        assertEquals("Trip to Lisbon", chipTitle("Trip to Lisbon"))
        assertEquals("Exactly twenty-four char", chipTitle("Exactly twenty-four char"))
        assertEquals("Budget for the move in M…", chipTitle("Budget for the move in March and the long list of things to sort"))
        // Never half an emoji, and no space before the ellipsis.
        assertEquals("Weekend plans with 🌍🌍…", chipTitle("Weekend plans with 🌍🌍🌍 and more"))
        assertEquals("Weekend plans with…", chipTitle("Weekend plans with      something"))
    }

    // --- the reply, read from the assistant chat ---

    private fun message(id: String, role: String, text: String, tools: Boolean = false) =
        Message(id, role, text, tools = if (tools) listOf(JSONObject().put("name", "memory")) else emptyList())

    @Test fun theReplyIsWhatArrivedAfterTheQuestionInTheSameChat() {
        val before = setOf("m1", "m2")
        val old = listOf(message("m1", "user", "earlier"), message("m2", "assistant", "earlier answer"))
        val sending = ChatState(sessionId = "s1", messages = old, uncertain = "send-1")
        assertEquals(AnswerProgress("", streaming = true, accepted = true), answerFrom(sending, before, "s1"))
        val streaming = ChatState(sessionId = "s1", messages = old + message("m3", "user", "Hi"), streamId = "st", liveText = "Hel", liveTools = listOf(JSONObject()))
        assertEquals(AnswerProgress("Hel", streaming = true, accepted = true), answerFrom(streaming, before, "s1"))
        val done = ChatState(sessionId = "s1", messages = old + message("m3", "user", "Hi") + message("m4", "assistant", "Hello there.", tools = true))
        assertEquals(AnswerProgress("Hello there.", streaming = false, accepted = true), answerFrom(done, before, "s1"))
        // Another chat opened meanwhile: nothing is read from it.
        assertNull(answerFrom(done.copy(sessionId = "s2"), before, "s1"))
        assertNull(answerFrom(done, before, ""))
    }

    @Test fun aSendThatNeverLandedIsNotAccepted() {
        val idle = ChatState(sessionId = "s1", messages = listOf(message("m1", "user", "earlier")))
        val progress = answerFrom(idle, setOf("m1"), "s1")!!
        assertFalse(progress.accepted)
        assertTrue(progress.streaming)
    }

    @Test fun continueOffersTheNewestTitledChatThatIsNotTheAssistants() {
        val rows = listOf(
            JSONObject().put("session_id", "beacon").put("title", "Beacon"),
            JSONObject().put("session_id", "untitled").put("title", ""),
            JSONObject().put("session_id", "c2").put("title", "  Budget for the move in March and the long list of things to sort  ").put("profile", "work"),
            JSONObject().put("session_id", "c3").put("title", "Older"),
        )
        val chip = lastChat(rows, assistantSession = "beacon", profile = "default")!!
        assertEquals("c2", chip.sessionId)
        assertEquals("work", chip.profile)
        assertEquals("Budget for the move in M…", chip.title)
        assertEquals("default", lastChat(listOf(JSONObject().put("id", "c9").put("title", "Plan")), "beacon", "default")!!.profile)
        assertNull(lastChat(listOf(rows[0]), "beacon", "default"))
    }

    // --- N-2: the short reasons of a call that didn't start ---

    /** The six reasons DirectVoice.kt words, each with the short line the overlay shows beside its one action. */
    private val shortReasons = mapOf(
        CallReason.HermesNoAnswer to ("Hermes didn't answer" to CALL_HERMES_NO_ANSWER),
        CallReason.VoiceNoAnswer to ("The voice service didn't answer" to CALL_VOICE_NO_ANSWER),
        CallReason.MicFailed to ("The microphone didn't start" to CALL_MIC_FAILED),
        CallReason.Dropped to ("The call dropped" to CALL_DROPPED),
        CallReason.VoiceStopped to ("Voice stopped answering" to CALL_VOICE_STOPPED),
        CallReason.SignInExpired to ("Your sign-in expired" to CALL_SIGN_IN_EXPIRED),
    )

    private val appRoot = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun catalog() = JSONObject(File(appRoot, "src/main/assets/native-locales.json").readText())
    private fun source(name: String) = File(appRoot, "src/main/java/dev/hermes/android/assist/$name").readText()

    @Test fun everyCallConstantMapsToItsShortReasonOrToTheGenericLine() {
        // The six constants of DirectVoice.kt each map to their reason, whatever whitespace the call state carries around them.
        shortReasons.forEach { (reason, texts) ->
            assertEquals(reason, callReason(texts.second))
            assertEquals(reason, callReason("  ${texts.second} "))
            assertEquals(texts.second, reason.full)
        }
        assertEquals(CallReason.entries.toSet(), shortReasons.keys)
        // Every other fixed line the call service can word (BeaconCallService.kt) has no short line: "Couldn't start the call".
        val others = CallFailureStrings.filterNot { line -> shortReasons.values.any { it.second == line } }
        assertTrue("The call service's own start lines are checked too", others.size >= 11)
        others.forEach { assertNull("$it has no short reason", callReason(it)) }
        // Anything else a call reports (a refused grant's own words, a provider's text, nothing at all) is generic as well.
        listOf("", "   ", "Check the API key", "Hermes didn't answer", "Bad key").forEach { assertNull(it, callReason(it)) }
    }

    @Test fun theCallServicesTranslatedWordingMapsToTheSameReason() {
        // The service words its reason in the phone's language before the call state carries it (serviceText), so every
        // catalog language must still lead to the same short reason, and no two reasons may share a wording.
        val catalog = catalog()
        val languages = catalog.keys().asSequence().filter { it != "en" }.toList()
        assertEquals(15, languages.size)
        languages.forEach { language ->
            val words = catalog.getJSONObject(language)
            fun said(constant: String) = words.getString(normalized(constant))
            shortReasons.forEach { (reason, texts) ->
                assertEquals("$language ${texts.second}", reason, callReason(said(texts.second)) { listOf(said(it)) })
            }
            assertEquals("$language has six different wordings", 6, shortReasons.values.map { said(it.second) }.toSet().size)
            // A translated line of another kind is generic.
            assertNull(language, callReason(said("Couldn't start the call")) { listOf(said(it)) })
        }
    }

    @Test fun theShortReasonsAreTranslatedShortInEveryLanguage() {
        val catalog = catalog()
        catalog.keys().asSequence().filter { it != "en" }.forEach { language ->
            val words = catalog.getJSONObject(language)
            shortReasons.forEach { (_, texts) ->
                val short = words.optString(normalized(texts.first))
                val full = words.getString(normalized(texts.second))
                assertTrue("$language is missing \"${texts.first}\"", short.isNotBlank())
                assertTrue("$language \"$short\" keeps a second sentence", short.none { it == '.' || it == '。' })
                assertTrue("$language \"$short\" is not shorter than \"$full\"", short.length < full.length)
                assertTrue("$language \"$short\" is the English text", short != texts.first)
            }
        }
        // The overlay line shows exactly these keys, one per reason, and the generic one for any other.
        val line = source("AssistOverlay.kt")
        shortReasons.forEach { (reason, texts) -> assertTrue("${reason.name} shows \"${texts.first}\"", line.contains("CallReason.${reason.name} -> uiText(\"${texts.first}\")")) }
        assertTrue(line.contains("null -> uiText(\"Couldn't start the call\")"))
    }

    @Test fun anExpiredSignInOffersSignInNotCallAgain() {
        val expired = reduce(open(), OverlayEvent.CallFailed(CallReason.SignInExpired))
        assertEquals(CallReason.SignInExpired, expired.callFailure)
        assertEquals(LineAction.SignIn, lineAction(OverlayLine.CallFailed, expired.callFailure))
        (CallReason.entries - CallReason.SignInExpired).forEach { assertEquals(it.name, LineAction.CallAgain, lineAction(OverlayLine.CallFailed, it)) }
        assertEquals(LineAction.CallAgain, lineAction(OverlayLine.CallFailed, null))
        assertEquals(LineAction.CallAgain, lineAction(OverlayLine.CallFailed))
        // The reason changes the action of the call line only.
        assertEquals(LineAction.TryAgain, lineAction(OverlayLine.NotSent, CallReason.SignInExpired))
        // It opens Beacon through the unlock path every opening uses: locked, the phone unlocks first.
        assertTrue(needsUnlock(OverlayAction.OpenInBeacon, locked = true))
        assertFalse(needsUnlock(OverlayAction.OpenInBeacon, locked = false))
        val line = source("AssistOverlay.kt")
        assertTrue(line.contains("LineAction.SignIn -> uiText(\"Sign in\")"))
        assertTrue(line.contains("LineAction.Open, LineAction.SignIn -> { { c.open(AssistTarget(AssistPurpose.OpenAssistant)) } }"))
    }

    // --- N-3: a send that failed goes back to the pill ---

    @Test fun aSendThatFailedLeavesThePillWithTheLineAndNoEmptyCard() {
        val asked = reduce(open(), OverlayEvent.Asked("Hi", false, "s1"))
        assertEquals(OverlaySurface.Answer, asked.surface)
        val failed = reduce(asked, OverlayEvent.NotSent)
        assertEquals(OverlaySurface.Pill, failed.surface)
        assertEquals(OverlayLine.NotSent, failed.line)
        assertEquals(LineAction.TryAgain, lineAction(failed.line!!))
        // Try again still has the question, and asking again brings the card back.
        assertEquals("Hi", failed.ask?.question)
        assertEquals(OverlaySurface.Answer, reduce(failed, OverlayEvent.Asked("Hi", false, "")).surface)
        // The lost card is not brought back by an unlock's reshow, nor when a call ends.
        val reshown = reduce(reduce(failed, OverlayEvent.Hidden), OverlayEvent.Show(fresh = false, callActive = false))
        assertEquals(OverlaySurface.Pill, reshown.surface)
        assertEquals(OverlayLine.NotSent, reshown.line)
        assertEquals(OverlaySurface.Pill, reduce(reduce(failed, OverlayEvent.LiveStarted), OverlayEvent.LiveEnded).surface)
        // Hermes held the question after all: the line goes and the card comes back with the answer.
        val late = reduce(failed, OverlayEvent.Progress("Hello", streaming = true, accepted = true))
        assertEquals(OverlaySurface.Answer, late.surface)
        assertNull(late.line)
        assertEquals(failed, reduce(failed, OverlayEvent.Progress("", streaming = true, accepted = false)))
        // Closing the line leaves the bare pill.
        assertEquals(OverlaySurface.Pill, reduce(failed, OverlayEvent.Line(null)).surface)
    }

    @Test fun aSendThatFailsDuringACallStaysInTheCallAndAnAnsweredCardStaysACard() {
        val live = reduce(reduce(open(), OverlayEvent.Asked("Hi", false, "s1")), OverlayEvent.LiveStarted)
        assertEquals(OverlaySurface.Live, reduce(live, OverlayEvent.NotSent).surface)
        val answered = reduce(reduce(open(), OverlayEvent.Asked("Hi", false, "s1")), OverlayEvent.Progress("Hello.", streaming = false))
        assertEquals(OverlaySurface.Answer, reduce(reduce(answered, OverlayEvent.Hidden), OverlayEvent.Show(fresh = false, callActive = false)).surface)
        assertEquals(OverlaySurface.Answer, reduce(reduce(answered, OverlayEvent.LiveStarted), OverlayEvent.LiveEnded).surface)
    }

    // --- UX-18: the ground under the surfaces ---

    @Test fun theGroundIsSolidInLightAndAlmostSolidInDarkFromTwentyFourDpAboveTheTopSurface() {
        assertEquals(1f, groundAlpha(dark = false))
        assertEquals(.98f, groundAlpha(dark = true))
        assertEquals(24, GROUND_LEAD_DP)
        // The overlay draws exactly that, from the stack of chips, line, card and pill, under the dust.
        val overlay = source("AssistOverlay.kt")
        assertTrue(overlay.contains("stack.top - GROUND_LEAD_DP.dp.toPx()"))
        assertTrue(overlay.contains("background.copy(alpha = groundAlpha(dark) * appear)"))
    }
}
