package dev.hermes.android.phone

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.PhoneCallCountdownDialog
import dev.hermes.android.ui.rememberCountdownRun
import dev.hermes.android.voice.CallToolApproval
import dev.hermes.android.voice.PhoneApprovalText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The card for a call placed from a live call: who and which number, "Calling in 3, 2, 1", a large Cancel, Call now. */
class PhoneCallCountdownUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val answers = mutableListOf<String>()
    private var phoneLocked = false
    // The phone's elapsed clock when the card was asked; the test clock starts there and moves with the compose clock.
    private val asked = 100_000L
    private val approval = CallToolApproval(
        "r1", "p".repeat(32), "phone_call", """{"number":"+966501234567"}""", allowCall = false,
        phone = PhoneApprovalText("Call Mona Test", "+966501234567", "", "", countdown = 3, askedAt = asked),
    )

    private fun show(card: CallToolApproval = approval) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            NativeLocalization("en") {
                MaterialTheme(colorScheme = darkColorScheme()) { PhoneCallCountdownDialog(card, { answers += it }, now = { asked + compose.mainClock.currentTime }, locked = { phoneLocked }) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test fun itShowsWhoAndTheFullNumberWithACountdownAndTwoButtons() {
        show()
        compose.onNodeWithTag("phone-countdown-who").assertIsDisplayed().assertTextEquals("Call Mona Test")
        compose.onNodeWithTag("phone-countdown-number").assertIsDisplayed().assertTextEquals("+966501234567")
        compose.onNodeWithTag("phone-countdown-title").assertTextEquals("Calling in 3")
        compose.onNodeWithTag("phone-countdown-cancel").assertIsDisplayed().assertTextContains("Cancel")
        compose.onNodeWithTag("phone-countdown-now").assertIsDisplayed().assertTextContains("Call now")
    }

    @Test fun theCountdownReadsThreeTwoOneAndThenAnswersYesOnce() {
        show()
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithTag("phone-countdown-title").assertTextEquals("Calling in 2")
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("phone-countdown-title").assertTextEquals("Calling in 1")
        assertEquals("not before the end", emptyList<String>(), answers)
        compose.mainClock.advanceTimeBy(1_200)
        assertEquals(listOf("approve"), answers)
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(listOf("approve"), answers)
    }

    @Test fun cancelAnswersNoAndTheCountdownStops() {
        show()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("phone-countdown-cancel").performClick()
        assertEquals(listOf("deny"), answers)
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(listOf("deny"), answers)
    }

    @Test fun callNowAnswersYesAtOnceAndOnlyOnce() {
        show()
        compose.onNodeWithTag("phone-countdown-now").performClick()
        assertEquals(listOf("approve"), answers)
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(listOf("approve"), answers)
    }

    @Test fun leavingTheScreenBeforeTheEndAnswersNoAndNothingIsPlaced() {
        show()
        compose.mainClock.advanceTimeBy(1_000)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(listOf("deny"), answers)
    }

    @Test fun aCardThatWaitedBeforeItWasDrawnShowsTheOrdinaryAllowDenyCardAndNeverCountsDown() {
        // The screen was off for 20 seconds: nothing is placed by itself, the owner taps.
        show(approval.copy(phone = approval.phone!!.copy(askedAt = asked - 20_000)))
        compose.onNodeWithTag("phone-countdown-title").assertDoesNotExist()
        compose.onNodeWithTag("phone-countdown-now").assertDoesNotExist()
        compose.onNodeWithTag("beacon-tool-allow").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(10_000)
        assertEquals("no countdown ends by itself", emptyList<String>(), answers)
        compose.onNodeWithTag("beacon-tool-deny").performClick()
        assertEquals(listOf("deny"), answers)
    }

    @Test fun theScreenLockingOnARunningCountdownAnswersNoAndNothingIsPlaced() {
        show()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(emptyList<String>(), answers)
        phoneLocked = true
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf("deny"), answers)
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(listOf("deny"), answers)
    }

    @Test fun aCardWithNoCountdownGetsNoRunEvenWhenItIsFresh() {
        var run: Any? = "not composed"
        compose.setContent {
            run = rememberCountdownRun(approval.copy(phone = approval.phone!!.copy(countdown = 0)), { answers += it }, now = { asked })
        }
        compose.waitForIdle()
        assertNull("a card that asked for no countdown does not get a one-second one", run)
        assertEquals(emptyList<String>(), answers)
    }

    @Test fun aCardThatWasNeverStampedAlsoWaitsForTheTap() {
        show(approval.copy(phone = approval.phone!!.copy(askedAt = 0)))
        compose.onNodeWithTag("phone-countdown-title").assertDoesNotExist()
        compose.onNodeWithTag("beacon-tool-allow").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(10_000)
        assertEquals(emptyList<String>(), answers)
    }

    @Test fun aNumberWithoutAContactNameStillShowsInFull() {
        show(approval.copy(phone = PhoneApprovalText("Call +442071234567", "", "Opens the dialer. You press call.", "", countdown = 3, askedAt = asked)))
        compose.onNodeWithTag("phone-countdown-who").assertTextEquals("Call +442071234567")
        compose.onNodeWithTag("phone-countdown-title").assertTextEquals("Calling in 3")
    }
}
