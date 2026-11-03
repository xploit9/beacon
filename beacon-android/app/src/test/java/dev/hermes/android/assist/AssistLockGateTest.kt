package dev.hermes.android.assist

import dev.hermes.android.voice.CallToolApproval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Strix vuln-0011 (low): the overlay's approval card read the controller's cached lock flag, while the owner's words, the tool line and
 * the answer read the lock screen live as well, so on a frame where the two disagreed the full card (contact, number, message) was
 * composed over the lock screen. Every private surface now reads one value: [overlayLocked] of the cached flag and a live read.
 */
class AssistLockGateTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun source(path: String) = File(app, "src/main/java/dev/hermes/android/$path").readText().replace("\r\n", "\n")
    private val approval = CallToolApproval("r1", "p".repeat(32), "phone_call", """{"number":"+15550100"}""", allowCall = false)

    @Test fun theLockIsTheBroadcastsLastWordOrTheLockScreenRightNow() {
        assertFalse(overlayLocked(cached = false, keyguardNow = false))
        assertTrue(overlayLocked(cached = true, keyguardNow = false))
        assertTrue("a lock that came up between two broadcasts is not missed", overlayLocked(cached = false, keyguardNow = true))
        assertTrue(overlayLocked(cached = true, keyguardNow = true))
    }

    @Test fun theApprovalCardCollapsesWhenTheLockScreenIsUpWhateverTheBroadcastSaid() {
        assertEquals(ApprovalShow.Locked, approvalShow(true, approval, overlayLocked(cached = false, keyguardNow = true)))
        assertEquals(ApprovalShow.Card, approvalShow(true, approval, overlayLocked(cached = false, keyguardNow = false)))
        assertEquals(ApprovalShow.None, approvalShow(false, approval, overlayLocked(cached = true, keyguardNow = true)))
    }

    @Test fun theApprovalCardCaptionsAndTheToolLineAllReadTheSameValueOnTheFrame() {
        val overlay = source("assist/AssistOverlay.kt")
        assertTrue(overlay.contains("val phoneLocked = overlayLocked(c.locked, keyguardLocked())"))
        assertFalse("the card must not read the cached flag alone", overlay.contains("approvalShow(live, approval, c.locked)"))
        assertTrue(overlay.contains("approvalShow(live, approval, phoneLocked)"))
        assertTrue(overlay.contains("phoneLocked = phoneLocked)"))
        assertTrue(overlay.contains("liveStatusLabel(call, phoneLocked)"))
        // The answer card and the chips take the same value as a parameter.
        assertTrue(overlay.contains("AnswerCard(it, vm, clock, motion, phoneLocked, c::closeCard, c::reveal)"))
        assertTrue(overlay.contains("Chips(c, vm, chipShows, motion, field, phoneLocked)"))
        assertTrue(overlay.contains("val body = answerBody(locked, ask)"))
        assertTrue(overlay.contains("assistChips(c.screen != null, locked, last, waiting)"))
        // No other place in the overlay's window decides a private surface from the cached flag.
        assertEquals(1, Regex("""\bc\.locked\b""").findAll(overlay).count())
    }

    /** Strix vuln-0005: the owner's typed question is a private surface like the reply: the card and the text box draw it through the lock value. */
    @Test fun theOwnersTypedWordsAreDrawnThroughTheLockGate() {
        val overlay = source("assist/AssistOverlay.kt")
        assertFalse("the card must not draw the question straight from the ask", overlay.contains("Text(ask.question"))
        assertTrue(overlay.contains("answerQuestion(locked, ask)"))
        assertFalse("the text box must not draw the draft straight while locked", overlay.contains("val draft = p.draft()"))
        assertTrue(overlay.contains("val draft = if (p.locked) TextFieldValue() else p.draft()"))
        assertTrue("the rule block names the question", source("assist/AssistOverlayModel.kt").contains("the typed question"))
    }

    @Test fun everyLockReadIsTheOneHelperTheCountdownAndTheTapsUse() {
        assertTrue(source("ui/PhoneCallCountdown.kt").contains("deviceLockedNow(context)"))
        assertFalse(source("ui/PhoneCallCountdown.kt").contains("isKeyguardLocked"))
        val controller = source("assist/AssistOverlayController.kt")
        assertTrue(controller.contains("private fun keyguardLocked() = deviceLockedNow(session.context)"))
        assertFalse(controller.contains("isKeyguardLocked"))
        // A tap that approves reads the lock live, with the cached flag, through the same helper.
        assertTrue(controller.contains("if (decision != \"deny\" && overlayLocked(locked, keyguardLocked())) return"))
        // So does the decision to unlock before opening the app.
        assertTrue(controller.contains("needsUnlock(action, overlayLocked(locked, keyguardLocked()))"))
        assertTrue(controller.contains("needsUnlock(OverlayAction.Settings, overlayLocked(locked, keyguardLocked()))"))
        assertTrue(source("phone/PhoneTools.kt").contains("locked = { deviceLockedNow(app) }"))
    }

    @Test fun theRuleIsWrittenInTheOverlaysCommentBlock() {
        val model = source("assist/AssistOverlayModel.kt")
        // Lane LK: the overlay never starts one locked; the trust rule stays below it as defense in depth.
        assertTrue(model.contains("The overlay never starts a conversation on a locked phone."))
        assertTrue(model.contains("A conversation is TRUSTED when it started on an unlocked phone"))
        assertTrue(model.contains("That stays as defense in depth"))
        assertTrue(model.contains("Unlock your phone first."))
        assertTrue(model.contains("Once trusted it stays trusted for that conversation"))
        assertFalse("the old rule, a locked start that talks, is gone", model.contains("talks at once, but until it is trusted"))
    }
}
