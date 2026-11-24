package dev.hermes.android.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** D1: source guards for the call fixes Q2 found live. The device and Compose checks are in IncomingDialerDeviceTest, CallsSetupUiTest and CallApprovalCardUiTest. */
class CallFixesD1Test {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun source(path: String) = File(app, "src/main/java/dev/hermes/android/$path").readText().replace("\r\n", "\n")

    @Test fun aCallAnsweredOnTheLockScreenDialerHasAWindowSoItIsNotRefused() {
        val service = source("voice/BeaconCallService.kt")
        assertTrue(service.contains("if (!uiVisible && !dialerVisible) refuse(CALL_OPEN_TO_ANSWER)"))
        assertTrue("a dialer-held call never minimizes into a bubble", service.contains("app.calls.uiVisible||app.calls.dialerVisible"))
        val dialer = source("voice/IncomingDialerActivity.kt")
        assertTrue(dialer.contains("calls.dialerVisible = true"))
        assertTrue(dialer.contains("calls.dialerVisible = false"))
    }

    @Test fun theBubbleQuestionSeesAMinimizeEvenWhileTheActivityIsStopped() {
        val host = source("ui/LiveCallUi.kt").substringAfter("internal fun CallBubbleAskHost")
        assertTrue("collected outside the lifecycle", host.contains(").collect { state ->"))
        assertTrue(host.contains("minimizedSeen = minimizedSeen"))
        assertFalse(host.substringBefore("CallBubbleAsk(call").contains("collectAsStateWithLifecycle"))
    }

    @Test fun theApprovalIsACompactCardNotAModalDialog() {
        val card = source("BeaconActivity.kt").substringAfter("internal fun CallToolApprovalDialog").substringBefore("\n}\n")
        assertFalse("a dialog covers End call and the watched phone", card.contains("AlertDialog("))
        assertTrue(card.contains("Popup("))
        // Back stays Deny on the call screen; over the watched phone (R3-F9) Back leaves the phone view and answers nothing.
        assertTrue("Back stays Deny", card.contains("BackHandler(!approval.busy && !overPhone) { onDecision(\"deny\") }"))
    }

    @Test fun theSystemIncomingCardShowsNoCountdown() {
        val push = source("push/BeaconMessagingService.kt")
        assertFalse(push.contains("setChronometerCountDown"))
        assertFalse(push.contains("setUsesChronometer"))
    }

    @Test fun theRingStopsBeforeTheAnswerAndDeclineHaptic() {
        val button = source("BeaconActivity.kt").substringAfter("private fun IncomingRoundButton").substringBefore("testTag(tag)")
        assertTrue(button.indexOf("silenceActive()") in 0 until button.indexOf("tapHaptic"))
    }

    @Test fun aRingThatTimesOutReportsMissedOnceAndDeclineDoesNot() {
        val push = source("push/BeaconMessagingService.kt").substringAfter("fun expireInvitation").substringBefore("fun discardInvitation")
        // Only a ringing or retryable invitation reaches the report, and it becomes missed in the same step: a second timeout, or a Decline (ended), returns first.
        assertTrue(push.contains("if (invite.state !in setOf(\"ringing\", \"retryable\")) return"))
        assertTrue(push.indexOf("state = \"missed\"") < push.indexOf("reportMissedIncoming"))
        assertTrue(push.split("reportMissedIncoming").size == 2)
        val decline = source("voice/IncomingCall.kt").substringAfter("suspend fun declineIncoming").substringBefore("suspend fun reportMissedIncoming")
        assertFalse(decline.contains("missed"))
        assertTrue(source("voice/IncomingCall.kt").contains("/callbacks/\$event/missed\", \"POST\", org.json.JSONObject()"))
    }
}
