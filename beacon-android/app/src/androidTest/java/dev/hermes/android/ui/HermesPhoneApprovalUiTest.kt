package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.hermes.android.BeaconTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The card the owner sees before Hermes uses their phone: the exact details, Allow and Deny, and no way to allow while locked or expired. */
class HermesPhoneApprovalUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val line = "Add 'Dentist' Tue 4 Nov 3:00 PM to Personal"
    private var allowed = 0
    private var denied = 0
    private var closed = 0

    private fun show(expired: Boolean = false, locked: Boolean = false, busy: Boolean = false, retry: Boolean = false,
                     target: String = "", details: List<String> = emptyList(), preview: String = "") {
        allowed = 0; denied = 0; closed = 0
        compose.setContent {
            NativeLocalization("en") {
                BeaconTheme(dark = true) {
                    PhoneApprovalCard(line, expired, locked, busy, onAllow = { allowed++ }, onDeny = { denied++ }, onClose = { closed++ },
                        target = target, details = details, preview = preview, retry = retry)
                }
            }
        }
    }

    @Test fun showsExactlyWhatHermesWillDoAndLetsTheOwnerAllowOrDeny() {
        show()
        compose.onNodeWithText("Hermes wants to use your phone").assertIsDisplayed()
        compose.onNodeWithTag("phone-request-line").assertIsDisplayed()
        compose.onNodeWithText(line).assertIsDisplayed()
        compose.onNodeWithTag("phone-request-note").assertDoesNotExist()
        compose.onNodeWithTag("phone-request-allow").assertIsEnabled().performClick()
        assertEquals(1, allowed)
        compose.onNodeWithTag("phone-request-deny").assertIsEnabled().performClick()
        assertEquals(1, denied)
        assertEquals(0, closed)
    }

    @Test fun showsWhoWhereTheDetailsAndTheWholeNotes() {
        val notes = (1..50).joinToString(" ") { "note$it" }.take(500)
        show(target = "+966501234567", details = listOf("Opens the dialer. You press call.", "Place: Clinic"), preview = notes)
        compose.onNodeWithTag("phone-request-target").assertIsDisplayed().assertTextEquals("+966501234567")
        compose.onAllNodesWithTag("phone-request-detail").assertCountEquals(2)
        compose.onNodeWithText("Opens the dialer. You press call.").assertIsDisplayed()
        // The notes read as the card's own block; there is no label in front of them.
        compose.onNodeWithText("Content preview").assertDoesNotExist()
        compose.onNodeWithTag("phone-request-preview").assertTextEquals(notes)
        compose.onNodeWithTag("phone-request-allow").assertIsEnabled()
    }

    @Test fun aCardWithOnlyAHeadlineShowsNoEmptyParts() {
        show()
        compose.onNodeWithTag("phone-request-target").assertDoesNotExist()
        compose.onNodeWithTag("phone-request-detail").assertDoesNotExist()
        compose.onNodeWithText("Content preview").assertDoesNotExist()
    }

    @Test fun anAnswerThatDidNotGetThroughSaysSoAndAllowsAnotherTap() {
        show(retry = true)
        compose.onNodeWithTag("phone-request-note").assertTextEquals("Hermes didn't answer. Try again.")
        compose.onNodeWithTag("phone-request-allow").assertIsEnabled().performClick()
        assertEquals(1, allowed)
    }

    @Test fun aLockedPhoneCannotAllowAndSaysWhy() {
        show(locked = true)
        compose.onNodeWithText("Unlock your phone to allow this.").assertIsDisplayed()
        compose.onNodeWithTag("phone-request-allow").assertIsNotEnabled()
        compose.onNodeWithTag("phone-request-deny").assertIsEnabled()
        compose.onNodeWithText(line).assertIsDisplayed()
        assertEquals(0, allowed)
    }

    @Test fun anExpiredRequestCanOnlyBeClosed() {
        show(expired = true)
        compose.onNodeWithText("This request expired.").assertIsDisplayed()
        compose.onNodeWithTag("phone-request-allow").assertDoesNotExist()
        compose.onNodeWithTag("phone-request-deny").assertDoesNotExist()
        compose.onNodeWithTag("phone-request-close").performClick()
        assertEquals(1, closed)
        assertEquals(0, allowed + denied)
    }

    /** In a right-to-left screen a plain "+966..." puts its plus sign after the digits; between the left-to-right marks the card carries it stays in front. */
    @Test fun aNumberBetweenTheMarksKeepsItsPlusSignInFrontInARightToLeftScreen() {
        val marked = "\u2066+966501234567\u2069"
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                NativeLocalization("en") { BeaconTheme(dark = true) {
                    Column {
                        Text(marked, Modifier.testTag("marked"))
                        Text("+966501234567", Modifier.testTag("plain"))
                    }
                } }
            }
        }
        fun plusIsLeftOfTheDigits(tag: String, plusAt: Int): Boolean {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val layout = layouts.single()
            return layout.getBoundingBox(plusAt).left < layout.getBoundingBox(plusAt + 1).left
        }
        assertTrue("the marked number reads +966 from the left", plusIsLeftOfTheDigits("marked", 1))
        // Whether an unmarked number reorders depends on the platform's bidi rules; only the marked one is ours to guarantee.
    }

    @Test fun allowDenyAndDoneAreFullSizeTargets() {
        show()
        compose.onNodeWithTag("phone-request-allow").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("phone-request-deny").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @Test fun doneOnAnExpiredRequestIsAFullSizeTarget() {
        show(expired = true)
        compose.onNodeWithTag("phone-request-close").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @Test fun whileAnAnswerIsBeingSentNeitherButtonCanBePressedTwice() {
        show(busy = true)
        compose.onNodeWithTag("phone-request-allow").assertIsNotEnabled()
        compose.onNodeWithTag("phone-request-deny").assertIsNotEnabled()
    }
}
