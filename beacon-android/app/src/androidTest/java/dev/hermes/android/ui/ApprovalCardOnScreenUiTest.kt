package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * An approval's card tells the call it is really on screen: drawn while its screen is started. The call then posts a notification
 * for every approval whose card is not reported, so no approval is left with neither.
 */
class ApprovalCardOnScreenUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val reports = mutableListOf<Pair<String, Boolean>>()

    @Test fun theCardIsReportedWhileItIsOnScreenAndTakenBackWhenItLeaves() {
        var present by mutableStateOf(true)
        compose.setContent { if (present) ApprovalCardOnScreen("r1", { id, on -> reports += id to on }) }
        compose.waitForIdle()
        assertEquals(listOf("r1" to true), reports)
        present = false
        compose.waitForIdle()
        assertEquals(listOf("r1" to true, "r1" to false), reports)
    }

    @Test fun aScreenThatStopsIsNotShowingTheCardAndShowsItAgainWhenItReturns() {
        compose.setContent { ApprovalCardOnScreen("r1", { id, on -> reports += id to on }) }
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        assertEquals("the card was on screen, then the screen stopped", listOf("r1" to true, "r1" to false), reports)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(listOf("r1" to true, "r1" to false, "r1" to true), reports)
    }

    @Test fun aCardThatIsNotDrawnReportsNothingUntilItIs() {
        var drawn by mutableStateOf(false)
        compose.setContent { ApprovalCardOnScreen("r1", { id, on -> reports += id to on }, shown = drawn) }
        compose.waitForIdle()
        assertEquals("a locked overlay draws no card, so the notification must stay", emptyList<Pair<String, Boolean>>(), reports)
        drawn = true
        compose.waitForIdle()
        assertEquals(listOf("r1" to true), reports)
        drawn = false
        compose.waitForIdle()
        assertEquals(listOf("r1" to true, "r1" to false), reports)
    }

    @Test fun eachApprovalReportsItsOwnId() {
        var second by mutableStateOf(false)
        compose.setContent {
            ApprovalCardOnScreen("a", { id, on -> reports += id to on })
            if (second) ApprovalCardOnScreen("b", { id, on -> reports += id to on })
        }
        compose.waitForIdle()
        second = true
        compose.waitForIdle()
        assertEquals(listOf("a" to true, "b" to true), reports)
    }
}
