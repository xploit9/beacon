package dev.hermes.android.phone

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.ui.PhoneAccessBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Settings > Phone access: its switches, the Hermes choices and the Activity list. */
class PhoneAccessUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val before = PhoneCapability.entries.associateWith { PhoneAccess.switchedOn(context, it) }
    private val hermesBefore = PhoneAccess.hermesAllowed(context) to PhoneAccess.askBeforeHermesReads(context)
    private val actsBefore = PhoneAccess.askBeforeHermesActs(context)
    private val log = PhoneActivityLog.of(context)
    private val logBefore = log.recent().reversed()

    @Before fun setUp() {
        PhoneCapability.entries.forEach { PhoneAccess.setSwitchedOn(context, it, false) }
        PhoneAccess.setHermesAllowed(context, false)
        PhoneAccess.setAskBeforeHermesReads(context, true)
        PhoneAccess.setAskBeforeHermesActs(context, true)
        log.clear()
    }

    @After fun tearDown() {
        before.forEach { (capability, on) -> PhoneAccess.setSwitchedOn(context, capability, on) }
        PhoneAccess.setHermesAllowed(context, hermesBefore.first)
        PhoneAccess.setAskBeforeHermesReads(context, hermesBefore.second)
        PhoneAccess.setAskBeforeHermesActs(context, actsBefore)
        log.clear()
        logBefore.forEach(log::add)
    }

    // The Activity list is one item of the page (it scrolls inside itself), built once the page reaches it.
    private fun toActivity() = compose.onNodeWithTag("phone-access").performScrollToNode(hasTestTag("phone-access-activity-list"))

    private fun show(notificationsOn: (() -> Boolean)? = null) = compose.setContent {
        MaterialTheme(colorScheme = darkColorScheme()) { Column(Modifier.fillMaxSize()) { PhoneAccessBody(Modifier.weight(1f), notificationsOn) } }
    }

    @Test fun everySwitchStartsOffAndHermesAccessIsOffToo() {
        show()
        for (capability in PhoneCapability.entries) compose.onNodeWithTag("phone-access-${capability.key}").performScrollTo().assertIsOff()
        compose.onNodeWithTag("phone-access-hermes-switch").performScrollTo().assertIsOff()
        compose.onNodeWithText("Nothing yet").performScrollTo()
    }

    @Test fun aSwitchWithoutAnAndroidPermissionTurnsOnAtOnceAndOffAgain() {
        show()
        val messages = compose.onNodeWithTag("phone-access-messages").performScrollTo()
        messages.performClick()
        compose.waitForIdle()
        messages.assertIsOn()
        assertTrue(PhoneAccess.switchedOn(context, PhoneCapability.Messages))
        assertTrue("phone_message_draft" in PhoneTools.enabledTools(context))
        messages.performClick()
        compose.waitForIdle()
        messages.assertIsOff()
        assertFalse(PhoneAccess.switchedOn(context, PhoneCapability.Messages))
        assertFalse("phone_message_draft" in PhoneTools.enabledTools(context))
    }

    @Test fun alarmsAreANormalPermissionSoTheSwitchTurnsOnAtOnce() {
        show()
        val alarms = compose.onNodeWithTag("phone-access-alarms").performScrollTo()
        alarms.performClick()
        compose.waitForIdle()
        alarms.assertIsOn()
        assertEquals(setOf("phone_alarm_set", "phone_timer_set"), PhoneTools.enabledTools(context))
    }

    @Test fun aStoredSwitchWhoseAndroidPermissionWasTakenAwayReadsOff() {
        // The calendar needs runtime permissions; with the switch stored on and the permission denied the page must show it off.
        val granted = PhoneAccess.permissionsGranted(context, PhoneCapability.Calendar)
        PhoneAccess.setSwitchedOn(context, PhoneCapability.Calendar, true)
        show()
        val row = compose.onNodeWithTag("phone-access-calendar").performScrollTo()
        if (granted) row.assertIsOn() else row.assertIsOff()
        assertEquals(granted, "phone_calendars" in PhoneTools.enabledTools(context))
    }

    @Test fun theCallsRowSaysSavedContactsAreCalledAfterThreeSecondsAndTheSectionSaysBeaconAsksFirst() {
        show()
        // Short enough for its two lines at large text, so nothing of it is cut off; the section's line covers every other number.
        compose.onNodeWithText("Call saved contacts after 3 seconds").performScrollTo().assertExists()
        compose.onNodeWithText("Write message drafts").performScrollTo().assertExists()
        // The section says what Ask for approval does now (Always by default).
        compose.onNodeWithText("Hermes asks before it reads or changes anything").performScrollTo().assertExists()
        compose.onNodeWithText("Only what you turned on above").performScrollTo().assertExists()
    }

    @Test fun aSwitchWhosePermissionWasTakenAwayIsStoredOffNotJustShownOff() {
        // Only meaningful where Android has not granted the calendar permission to Beacon.
        org.junit.Assume.assumeFalse(PhoneAccess.permissionsGranted(context, PhoneCapability.Calendar))
        PhoneAccess.setSwitchedOn(context, PhoneCapability.Calendar, true)
        show()
        compose.waitForIdle()
        assertFalse("allowing the permission again must not switch it back on", PhoneAccess.switchedOn(context, PhoneCapability.Calendar))
    }

    // Owner, 4 Nov 2026: the two switches were confusing; one menu, Ask for approval, answers whether Hermes asks at all.
    @Test fun askForApprovalIsOneMenuThatMovesBothStoredChoicesAndTheEngine() {
        PhoneAccess.setHermesAllowed(context, true)
        PhoneAccess.setApprovalChoice(context, ApprovalChoice.Always)
        show()
        compose.onAllNodesWithTag("phone-access-hermes-ask").assertCountEquals(0)
        compose.onAllNodesWithTag("phone-access-hermes-ask-acts").assertCountEquals(0)
        compose.onNodeWithTag("approval-choice").performScrollTo().assertTextContains("Ask for approval").assertTextContains("Always")
        compose.onNodeWithText("Hermes asks before it reads or changes anything").performScrollTo().assertExists()

        compose.onNodeWithTag("approval-choice").performClick()
        compose.onNodeWithTag("approval-choice-Changes").performClick()
        compose.waitForIdle()
        assertFalse(PhoneAccess.askBeforeHermesReads(context))
        assertTrue(PhoneAccess.askBeforeHermesActs(context))
        compose.onNodeWithText("Hermes asks before it changes anything").performScrollTo().assertExists()
        assertFalse(PhoneTools.needsApproval(context, "phone_calendars", PhoneOrigin.Hermes))
        assertTrue(PhoneTools.needsApproval(context, "phone_calendar_create", PhoneOrigin.Hermes))

        compose.onNodeWithTag("approval-choice").performScrollTo().performClick()
        compose.onNodeWithTag("approval-choice-Never").performClick()
        compose.waitForIdle()
        assertEquals(ApprovalChoice.Never, PhoneAccess.approvalChoice(context))
        compose.onNodeWithText("Hermes acts without asking. Phone calls still ask.").performScrollTo().assertExists()
        // A change needs no tap from a chat or the assistant; a phone call still does.
        assertFalse(PhoneTools.needsApproval(context, "phone_calendar_create", PhoneOrigin.Hermes))
        assertFalse(PhoneTools.needsApproval(context, "phone_calendar_create", PhoneOrigin.Overlay))
        assertTrue(PhoneTools.needsApproval(context, "phone_call", PhoneOrigin.Hermes))
    }

    @Test fun theMenuShowsEvenWithHermesAccessOffBecauseItAlsoCoversCallsAndTheSideButton() {
        PhoneAccess.setApprovalChoice(context, ApprovalChoice.Changes)
        show()
        compose.onNodeWithTag("approval-choice").performScrollTo().assertTextContains("Only for changes")
    }

    @Test fun withNotificationsOffAndHermesAccessOnThePageSaysRequestsShowOnlyInTheOpenApp() {
        PhoneAccess.setHermesAllowed(context, true)
        show(notificationsOn = { false })
        compose.onNodeWithTag("phone-access-notifications-off").performScrollTo().assertExists()
        compose.onNodeWithText("Notifications are off. Hermes requests show only when Beacon is open.").assertExists()
        compose.onNodeWithTag("phone-access-notifications-settings").performScrollTo().assertExists()
    }

    @Test fun theNotificationsLineIsNotThereWhenNotificationsAreOnOrHermesAccessIsOff() {
        PhoneAccess.setHermesAllowed(context, true)
        show(notificationsOn = { true })
        compose.onAllNodesWithTag("phone-access-notifications-off").assertCountEquals(0)
        compose.onAllNodesWithTag("phone-access-notifications-settings").assertCountEquals(0)
    }

    @Test fun withHermesAccessOffThereIsNothingToSayAboutNotifications() {
        show(notificationsOn = { false })
        compose.onAllNodesWithTag("phone-access-notifications-off").assertCountEquals(0)
    }

    @Test fun aRequestRefusedBecauseThePhoneWasLockedIsListedWithItsReason() {
        log.add(PhoneActivityEntry(System.currentTimeMillis() - 30_000, PhoneOrigin.Overlay, "phone_calendars", PhoneLine("Read your calendars"), "locked"))
        show()
        toActivity()
        compose.onAllNodesWithTag("phone-access-entry")[0].performScrollTo().assertTextContains("Read your calendars", substring = true)
        compose.onAllNodesWithTag("phone-access-entry")[0].assertTextContains("Unlock your phone first.", substring = true)
        compose.onAllNodesWithTag("phone-access-entry")[0].assertTextContains("Assistant", substring = true)
    }

    @Test fun theActivityListShowsRecentRequestsNewestFirstAndClearEmptiesIt() {
        log.add(PhoneActivityEntry(System.currentTimeMillis() - 120_000, PhoneOrigin.Hermes, "phone_timer_set", PhoneLine("Start a timer for {0}", listOf("5 min")), "done"))
        log.add(PhoneActivityEntry(System.currentTimeMillis() - 60_000, PhoneOrigin.LiveCall, "phone_call", PhoneLine("Call {0} {1}", listOf("Mona", "+966501234567")), "denied"))
        show()
        toActivity()
        compose.onAllNodesWithTag("phone-access-entry").assertCountEquals(2)
        // The owner's own words (a name, a number, a time) are isolated, so a name in the other script never flips the line.
        compose.onAllNodesWithTag("phone-access-entry")[0].performScrollTo().assertTextContains(PhoneLine("Call {0} {1}", listOf("Mona", "+966501234567")).shown())
        compose.onAllNodesWithTag("phone-access-entry")[0].assertTextContains("Said no", substring = true)
        // A call made in a live conversation says Voice call, not just Call.
        compose.onAllNodesWithTag("phone-access-entry")[0].assertTextContains("Voice call", substring = true)
        compose.onAllNodesWithTag("phone-access-entry")[1].assertTextContains(PhoneLine("Start a timer for {0}", listOf("5 min")).shown())
        // Clear is a full-size target.
        compose.onNodeWithTag("phone-access-clear").performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("phone-access-clear").performClick()
        compose.onNodeWithTag("phone-access-clear-confirm").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("phone-access-entry").assertCountEquals(0)
        assertTrue(log.recent().isEmpty())
        compose.onNodeWithText("Nothing yet").assertExists()
    }
}
