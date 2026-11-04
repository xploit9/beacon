package dev.hermes.android.assist

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.voice.CallToolApproval
import dev.hermes.android.voice.PhoneApprovalText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Lane AO: the live conversation inside the overlay, as Compose runs it: the captions sit directly above the text box and the text
 * box stays, the orb shows the conversation and ends it, a phone approval (the call's 3-second countdown or any other request) shows
 * above the captions and answers without the app, locked it only says something waits, and none of it opens another window.
 * It runs the overlay's own composables without a voice session, so no call, no network and no demo Hermes are needed.
 */
class AssistLiveUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val answers = mutableListOf<String>()
    private val sent = mutableListOf<String>()
    private var orbTaps = 0
    private var phoneLocked = false
    // The overlay's lock flag, as the controller keeps it: state, so the live block follows a lock or an unlock at once.
    private var lockedNow by mutableStateOf(false)
    // The phone's elapsed clock when the card was asked; the test clock starts there and moves with the compose clock.
    private val asked = 100_000L

    private val countdown = CallToolApproval(
        "r1", "p".repeat(32), "phone_call", """{"number":"+15550100"}""", allowCall = false,
        phone = PhoneApprovalText("Call Alex Example", "+15550100", "", "", countdown = 3, askedAt = asked),
    )
    private val request = CallToolApproval(
        "r2", "q".repeat(32), "phone_send_sms", """{"to":"+15550100","body":"On my way"}""",
        phone = PhoneApprovalText("Text Alex Example", "+15550100", "", "On my way"),
    )
    private val captions = listOf(Caption(true, "What is on today?"), Caption(false, "Two meetings, the first at nine."))

    /** The live stack as the overlay arranges it, bottom up: the text box, the captions above it, any approval above them. */
    private fun show(
        captions: List<Caption> = this.captions,
        activity: String? = null,
        approval: CallToolApproval? = null,
        show: ApprovalShow = if (approval == null) ApprovalShow.None else ApprovalShow.Card,
        face: OrbFace = OrbFace.Listening,
        status: String = "Listening",
        height: Dp? = null,
        scheme: ColorScheme = darkColorScheme(),
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalAssistantName provides "Beacon") {
                    var draft by remember { mutableStateOf(TextFieldValue()) }
                    val clock = remember { mutableFloatStateOf(0f) }
                    // A short screen is a column of that height: the overlay's stack must fit it, whatever the approval and captions need.
                    Column((if (height != null) Modifier.fillMaxWidth().height(height) else Modifier.fillMaxSize()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.Bottom)) {
                        if (face != OrbFace.Talk) LiveAbovePill(captions, activity, approval, show, { answers += it }, now = { asked + compose.mainClock.currentTime }, locked = { phoneLocked }, phoneLocked = lockedNow)
                        Pill(PillInput(
                            state = AssistOverlayState(surface = if (face == OrbFace.Talk) OverlaySurface.Pill else OverlaySurface.Live, phase = OverlayPhase.Open),
                            draft = { draft }, onDraft = { draft = it }, screen = false, screenAttached = false, onAttach = {}, heardLevel = { 0f },
                            face = face, status = status, voice = { 0f },
                            onSend = { sent += draft.text; draft = TextFieldValue() }, onDictate = {}, onOrb = { orbTaps++ },
                        ), remember { FocusRequester() }, motion = false, clock = clock)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
    private fun bottom(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.bottom

    @Test fun theCaptionsSitDirectlyAboveTheTextBoxAndTheTextBoxStays() {
        show(activity = "Using Gmail…")
        compose.onNodeWithTag("assist-pill").assertIsDisplayed()
        compose.onNodeWithTag("assist-field").assertIsDisplayed()
        compose.onNodeWithTag("assist-captions").assertIsDisplayed()
        compose.onNodeWithTag("assist-caption-owner").assertTextEquals("What is on today?")
        compose.onNodeWithTag("assist-caption-reply").assertTextEquals("Two meetings, the first at nine.")
        compose.onNodeWithTag("assist-live-activity").assertTextEquals("Using Gmail…")
        val density = compose.density.density
        // Directly above: the captions end where the text box begins, a gap of no more than the stack's own 10 dp.
        assertTrue("captions ${bottom("assist-captions")} over the text box ${top("assist-pill")}", bottom("assist-captions") <= top("assist-pill") + 1f)
        assertTrue("nothing sits between them", top("assist-pill") - bottom("assist-captions") <= 12 * density)
        // The owner's words first, then the assistant's reply, then the tool activity.
        assertTrue(top("assist-caption-owner") < top("assist-caption-reply"))
        assertTrue(top("assist-caption-reply") < top("assist-live-activity"))
    }

    /** While the conversation is live the box says what it is doing, not "Type a message"; typing in it still works. */
    @Test fun whileLiveTheTextBoxHintIsTheConversationsStatus() {
        show(captions = emptyList(), status = "Listening")
        compose.onNodeWithText("Listening").assertIsDisplayed()
        compose.onNodeWithText("Type a message").assertDoesNotExist()
        // TalkBack still hears a text box that can be typed in, and the orb says the state.
        compose.onNodeWithTag("assist-field").assertContentDescriptionEquals("Type a message")
        compose.onNodeWithTag("assist-live").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Listening"))
    }

    @Test fun whileConnectingTheTextBoxHintSaysConnecting() {
        show(captions = emptyList(), status = "Connecting", face = OrbFace.Connecting)
        compose.onNodeWithText("Connecting").assertIsDisplayed()
        compose.onNodeWithText("Type a message").assertDoesNotExist()
    }

    @Test fun whileWorkingTheTextBoxHintSaysWhatItWorksOn() {
        show(captions = emptyList(), status = "Using Gmail…", face = OrbFace.Working)
        compose.onNodeWithText("Using Gmail…").assertIsDisplayed()
        compose.onNodeWithText("Type a message").assertDoesNotExist()
    }

    @Test fun beforeTheConversationTheHintStillInvitesTheOwnerToAsk() {
        show(face = OrbFace.Talk, status = "")
        compose.onNodeWithText("Ask Beacon").assertIsDisplayed()
        compose.onNodeWithText("Listening").assertDoesNotExist()
    }

    @Test fun typingStillSendsATypedTurnWhileTheHintShowsTheStatus() {
        show(captions = emptyList(), status = "Listening")
        compose.onNodeWithTag("assist-field").performTextInput("hello")
        compose.mainClock.advanceTimeByFrame()
        // The status gives way to the typed words, and the send button is there.
        compose.onNodeWithText("Listening").assertDoesNotExist()
        compose.onNodeWithTag("assist-send").performClick()
        assertEquals(listOf("hello"), sent)
    }

    /** The activity line is read on the glass of the theme: its own accent on light, gold on dark. */
    private fun activityColor(): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("assist-live-activity").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single().layoutInput.style.color
    }

    @Test fun theToolActivityLineUsesGoldOnADarkCard() {
        show(activity = "Using Gmail…")
        assertEquals(Gold, activityColor())
    }

    @Test fun theToolActivityLineUsesTheThemesAccentOnALightCard() {
        val accent = Color(0xFF7A4B00)
        show(activity = "Using Gmail…", scheme = lightColorScheme(primary = accent))
        assertEquals(accent, activityColor())
    }

    /** The review's lock-screen rule: the owner's words, the reply and the tool in use are private, so they are not on the window at all while locked. */
    @Test fun onALockedPhoneNoCaptionAndNoToolActivityIsOnTheWindowOnlyTheStateIs() {
        lockedNow = true
        show(activity = "Using Gmail…", status = "Working…", face = OrbFace.Working)
        for (tag in listOf("assist-captions", "assist-caption-owner", "assist-caption-reply", "assist-live-activity")) compose.onNodeWithTag(tag).assertDoesNotExist()
        compose.onNodeWithText("What is on today?").assertDoesNotExist()
        compose.onNodeWithText("Two meetings, the first at nine.").assertDoesNotExist()
        compose.onNodeWithText("Using Gmail…").assertDoesNotExist()
        // The text box and its orb are still there and say what the conversation is doing, nothing more.
        compose.onNodeWithTag("assist-pill").assertIsDisplayed()
        compose.onNodeWithText("Working…").assertIsDisplayed()
        compose.onNodeWithTag("assist-live").assertContentDescriptionEquals("End call")
    }

    @Test fun theWordsComeBackWhenThePhoneIsUnlockedAndGoAgainWhenItLocks() {
        lockedNow = true
        show(activity = "Using Gmail…")
        compose.onNodeWithTag("assist-captions").assertDoesNotExist()
        compose.runOnIdle { lockedNow = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("assist-captions").assertIsDisplayed()
        compose.onNodeWithTag("assist-caption-owner").assertTextEquals("What is on today?")
        compose.onNodeWithTag("assist-caption-reply").assertTextEquals("Two meetings, the first at nine.")
        compose.onNodeWithTag("assist-live-activity").assertTextEquals("Using Gmail…")
        compose.runOnIdle { lockedNow = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("assist-captions").assertDoesNotExist()
        compose.onNodeWithTag("assist-live-activity").assertDoesNotExist()
    }

    @Test fun theLockedApprovalRowStaysWhileTheWordsAreHidden() {
        lockedNow = true
        show(approval = countdown, show = ApprovalShow.Locked)
        compose.onNodeWithTag("assist-approval-locked").assertIsDisplayed()
        compose.onNodeWithTag("assist-captions").assertDoesNotExist()
        compose.onNodeWithTag("assist-pill").assertIsDisplayed()
    }

    @Test fun aQuietConversationShowsOnlyTheTextBox() {
        show(captions = emptyList())
        compose.onNodeWithTag("assist-pill").assertIsDisplayed()
        compose.onNodeWithTag("assist-captions").assertDoesNotExist()
        compose.onNodeWithTag("assist-approval").assertDoesNotExist()
    }

    @Test fun theOrbEndsTheConversationAndThereIsNoMicToCompeteWithIt() {
        show()
        compose.onNodeWithTag("assist-live").assertContentDescriptionEquals("End call")
        compose.onNodeWithTag("assist-mic").assertDoesNotExist()
        compose.onNodeWithTag("assist-attach").assertDoesNotExist()
        compose.onNodeWithTag("assist-live").performClick()
        assertEquals(1, orbTaps)
    }

    @Test fun beforeTheConversationTheOrbTalksAndTheMicIsThere() {
        show(face = OrbFace.Talk)
        compose.onNodeWithTag("assist-live").assertContentDescriptionEquals("Talk to Beacon")
        compose.onNodeWithTag("assist-mic").assertIsDisplayed()
        compose.onNodeWithTag("assist-captions").assertDoesNotExist()
    }

    @Test fun typingInTheTextBoxDuringTheConversationSendsATypedTurn() {
        show()
        compose.onNodeWithTag("assist-send").assertDoesNotExist()
        compose.onNodeWithTag("assist-field").performTextInput("Move it to five")
        // The test clock is manual, so the typed words are drawn (and the send button with them) when the next frame runs.
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("assist-send").performClick()
        assertEquals(listOf("Move it to five"), sent)
        // The words are in the box until sent, and the box is empty again after.
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("assist-send").assertDoesNotExist()
    }

    @Test fun theCountdownCardShowsAboveTheCaptionsAndTheTextBoxAndCancelsWithoutTheApp() {
        show(approval = countdown)
        compose.onNodeWithTag("assist-approval").assertIsDisplayed()
        compose.onNodeWithTag("phone-countdown-who").assertTextEquals("Call Alex Example")
        compose.onNodeWithTag("phone-countdown-number").assertTextEquals("+15550100")
        compose.onNodeWithTag("phone-countdown-title").assertTextEquals("Calling in 3")
        assertTrue("the card is above the captions", bottom("assist-approval") <= top("assist-captions") + 1f)
        assertTrue("and the captions above the text box", bottom("assist-captions") <= top("assist-pill") + 1f)
        compose.onNodeWithTag("assist-pill").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithTag("phone-countdown-title").assertTextEquals("Calling in 2")
        compose.onNodeWithTag("phone-countdown-cancel").performClick()
        assertEquals(listOf("deny"), answers)
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals("Cancel is the only answer", listOf("deny"), answers)
    }

    @Test fun theCountdownPlacesTheCallOnceWhenItEnds() {
        show(approval = countdown)
        compose.mainClock.advanceTimeBy(3_300)
        assertEquals(listOf("approve"), answers)
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(listOf("approve"), answers)
    }

    @Test fun leavingTheOverlayBeforeTheCountdownEndsAnswersNo() {
        show(approval = countdown)
        compose.mainClock.advanceTimeBy(1_000)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(listOf("deny"), answers)
    }

    @Test fun aCardThatWaitedBeforeItWasDrawnShowsThePlainAllowAndDenyCardAndNeverCountsDown() {
        // The screen was off for 20 seconds: nothing is placed by itself, the owner taps.
        show(approval = countdown.copy(phone = countdown.phone!!.copy(askedAt = asked - 20_000)))
        compose.onNodeWithTag("phone-countdown-title").assertDoesNotExist()
        compose.onNodeWithTag("phone-countdown-now").assertDoesNotExist()
        compose.onNodeWithTag("assist-approval-allow").assertIsDisplayed()
        compose.onNodeWithTag("assist-approval-deny").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(10_000)
        assertEquals("no countdown ends by itself", emptyList<String>(), answers)
        compose.onNodeWithTag("assist-approval-deny").performClick()
        assertEquals(listOf("deny"), answers)
    }

    @Test fun lockingThePhoneDuringTheCountdownAnswersNoOnceAndPlacesNothing() {
        show(approval = countdown)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(emptyList<String>(), answers)
        phoneLocked = true
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf("deny"), answers)
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals("one answer, and the countdown does not go on to place the call", listOf("deny"), answers)
    }

    @Test fun aCountdownDrawnOnALockedPhoneNeverStarts() {
        phoneLocked = true
        show(approval = countdown)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf("deny"), answers)
    }

    @Test fun aLongNoteOrMessageShowsWholeAndAllowAndDenyStayInReachUnderIt() {
        val note = (1..40).joinToString(" ") { "Bring signed form $it to the front desk." }.take(1000)
        show(approval = request.copy(phone = request.phone!!.copy(preview = note, details = "Tue 4 Nov 3:00 PM\nPlace: Clinic on the second floor")))
        compose.onNodeWithTag("assist-approval-preview").assertTextEquals(note)
        // Not one character is cut: every line the text needs is laid out, with no ellipsis, where the old card kept six.
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("assist-approval-preview").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        assertTrue("${layout.lineCount} lines", layout.lineCount > 6)
        assertTrue((0 until layout.lineCount).none { layout.isLineEllipsized(it) })
        // The answers are pinned under the scrolling text, inside the card and on screen.
        compose.onNodeWithTag("assist-approval-deny").assertIsDisplayed()
        compose.onNodeWithTag("assist-approval-allow").assertIsDisplayed()
        assertTrue(bottom("assist-approval-allow") <= bottom("assist-approval") + 1f)
        assertTrue("the answers sit below the text", top("assist-approval-deny") >= bottom("assist-approval-action"))
        compose.onNodeWithTag("assist-approval-allow").performClick()
        assertEquals(listOf("approve"), answers)
    }

    @Test fun aLongCardNeverPushesTheCaptionsOrTheTextBoxOffScreen() {
        val note = "x".repeat(900)
        show(approval = request.copy(phone = request.phone!!.copy(preview = note)))
        compose.onNodeWithTag("assist-pill").assertIsDisplayed()
        compose.onNodeWithTag("assist-approval-allow").assertIsDisplayed()
        assertTrue(bottom("assist-approval") <= top("assist-captions") + 1f)
    }

    /** A short or sideways screen: the card, the captions and the text box together are taller than it is. */
    @Test fun onAShortScreenTheTextBoxAndTheAnswersStayOnItWhateverTheCardAndCaptionsNeed() {
        val note = (1..40).joinToString(" ") { "Bring signed form $it to the front desk." }.take(1000)
        val screen = 320.dp
        show(approval = request.copy(phone = request.phone!!.copy(preview = note)), activity = "Using Gmail…", height = screen)
        val limit = with(compose.density) { screen.toPx() }
        compose.onNodeWithTag("assist-pill").assertIsDisplayed()
        compose.onNodeWithTag("assist-field").assertIsDisplayed()
        compose.onNodeWithTag("assist-approval-deny").assertIsDisplayed()
        compose.onNodeWithTag("assist-approval-allow").assertIsDisplayed()
        // Nothing is pushed off either end of the screen, and the card ends above the text box.
        assertTrue("the text box ends at ${bottom("assist-pill")} on a $limit px screen", bottom("assist-pill") <= limit + 1f)
        // ...and keeps its whole height rather than being squeezed by what is above it.
        assertTrue("the text box is ${bottom("assist-pill") - top("assist-pill")} px tall", bottom("assist-pill") - top("assist-pill") >= with(compose.density) { 64.dp.toPx() } - 1f)
        assertTrue("the card starts at ${top("assist-approval")}", top("assist-approval") >= -1f)
        assertTrue(bottom("assist-approval") <= top("assist-pill") + 1f)
        assertTrue(bottom("assist-approval-allow") <= bottom("assist-approval") + 1f)
        // The note is still whole: it scrolls inside the card.
        compose.onNodeWithTag("assist-approval-preview").assertTextEquals(note)
        compose.onNodeWithTag("assist-approval-allow").performClick()
        assertEquals(listOf("approve"), answers)
    }

    @Test fun onAShortScreenTheCaptionsGiveWayBeforeTheTextBoxDoes() {
        val screen = 150.dp
        show(activity = "Using Gmail…", height = screen)
        val limit = with(compose.density) { screen.toPx() }
        compose.onNodeWithTag("assist-pill").assertIsDisplayed()
        assertTrue("the text box ends at ${bottom("assist-pill")} on a $limit px screen", bottom("assist-pill") <= limit + 1f)
        assertTrue(top("assist-pill") >= -1f)
        assertTrue("the text box is ${bottom("assist-pill") - top("assist-pill")} px tall", bottom("assist-pill") - top("assist-pill") >= with(compose.density) { 64.dp.toPx() } - 1f)
    }

    /** The note gets the room: beside the card only the last caption line shows, and the tool line is gone. */
    @Test fun whileAnApprovalCardIsUpOnlyTheLastCaptionLineShowsAndNoToolLine() {
        show(activity = "Using Gmail…", approval = request)
        compose.onNodeWithTag("assist-approval").assertIsDisplayed()
        compose.onNodeWithTag("assist-caption-reply").assertTextEquals("Two meetings, the first at nine.")
        compose.onNodeWithTag("assist-caption-owner").assertDoesNotExist()
        compose.onNodeWithTag("assist-live-activity").assertDoesNotExist()
        compose.onNodeWithTag("assist-pill").assertIsDisplayed()
    }

    /** A note longer than the card scrolls inside it, with the answers pinned; a short one needs no scroll (and so no fade). */
    @Test fun aLongNoteScrollsInsideTheCardWithTheAnswersPinned() {
        val note = (1..40).joinToString(" ") { "Bring signed form $it to the front desk." }.take(1000)
        show(approval = request.copy(phone = request.phone!!.copy(preview = note)), height = 420.dp)
        val range = compose.onNodeWithTag("assist-approval-scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("the note scrolls: max ${range.maxValue()}", range.maxValue() > 0f)
        compose.onNodeWithTag("assist-approval-allow").assertIsDisplayed()
        compose.onNodeWithTag("assist-approval-deny").assertIsDisplayed()
    }

    @Test fun aShortNoteNeedsNoScroll() {
        show(approval = request)
        val range = compose.onNodeWithTag("assist-approval-scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(0f, range.maxValue(), 0f)
    }

    @Test fun anotherRequestShowsWhatWillRunWithDenyAndAllowOnce() {
        show(approval = request)
        compose.onNodeWithTag("assist-approval").assertIsDisplayed()
        compose.onNodeWithTag("assist-approval-action").assertTextEquals("Text Alex Example")
        compose.onNodeWithTag("assist-approval-target").assertTextEquals("+15550100")
        compose.onNodeWithTag("assist-approval-deny").assertIsDisplayed()
        compose.onNodeWithTag("assist-approval-allow-call").assertIsDisplayed()
        compose.onNodeWithTag("assist-approval-allow").performClick()
        assertEquals(listOf("approve"), answers)
    }

    @Test fun lockedTheCardOnlySaysSomethingWaitsAndOffersNo() {
        show(approval = countdown, show = ApprovalShow.Locked)
        compose.onNodeWithTag("assist-approval-locked").assertIsDisplayed()
        compose.onNodeWithText("Unlock to approve").assertIsDisplayed()
        // None of what the request would do is on screen, and nothing counts down or can be allowed.
        compose.onNodeWithText("Call Alex Example").assertDoesNotExist()
        compose.onNodeWithText("+15550100").assertDoesNotExist()
        compose.onNodeWithTag("phone-countdown-title").assertDoesNotExist()
        compose.onNodeWithTag("assist-approval-allow").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals("Nothing is answered by itself", emptyList<String>(), answers)
        compose.onNodeWithTag("assist-approval-deny").performClick()
        assertEquals(listOf("deny"), answers)
    }

    /** Strix vuln-0011: the broadcasts' last word says unlocked while the lock screen read now says locked; the card and the words follow the one value. */
    @Test fun theApprovalCardFollowsTheLockScreenReadNowWhateverTheBroadcastsLastWordWas() {
        phoneLocked = true
        lockedNow = false
        val locked = overlayLocked(cached = lockedNow, keyguardNow = phoneLocked)
        show(approval = countdown, show = approvalShow(true, countdown, locked))
        compose.onNodeWithTag("assist-approval-locked").assertIsDisplayed()
        compose.onNodeWithText("Call Alex Example").assertDoesNotExist()
        compose.onNodeWithText("+15550100").assertDoesNotExist()
        compose.onNodeWithTag("assist-approval-allow").assertDoesNotExist()
        compose.onNodeWithText("What is on today?").assertDoesNotExist()
    }

    @Test fun noOtherWindowOpensForAnApproval() {
        show(approval = countdown)
        // The card is part of the overlay's own content: no dialog window is created for it.
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("phone-countdown").assertDoesNotExist()
    }
}
