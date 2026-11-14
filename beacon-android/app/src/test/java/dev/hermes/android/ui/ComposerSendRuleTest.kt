package dev.hermes.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R10F-A round 4: on a retained page (the chat stays that chat because Hermes does not serve Beacon for the profile, or because opening it was refused) Send is off. The page's own
 * line already says why; a tap that did nothing would look like a lost message. Typing stays allowed and saved (the field's own rule is separate); only Send follows this rule.
 */
class ComposerSendRuleTest {
    private fun enabled(editable: Boolean = true, preparing: Boolean = false, unconfirmedSend: Boolean = false, callActive: Boolean = false, readOnly: Boolean = false, retainedPage: Boolean = false) =
        composerSendEnabled(editable, preparing, unconfirmedSend, callActive, readOnly, retainedPage)

    @Test fun sendIsOffOnARetainedPageAndOnOtherwise() {
        assertTrue(enabled())
        assertFalse(enabled(retainedPage = true))
    }

    /** R11 Nice 4: Attach opens a menu whose picks end in a notice, because a retained page has no scope to send in; it is off there like Send. */
    @Test fun attachIsOffOnARetainedPageAndOnOtherwise() {
        assertTrue(composerAttachEnabled(canonical = true, editable = true, callActive = false, readOnly = false, retainedPage = false))
        assertFalse(composerAttachEnabled(canonical = true, editable = true, callActive = false, readOnly = false, retainedPage = true))
        assertFalse("also when only the chat id is there", composerAttachEnabled(canonical = true, editable = false, callActive = false, readOnly = false, retainedPage = true))
    }

    /** R11 Nice 4: the "Send as text" / "Queue as text" row of the menu stops at the same place Send does, so it is off on a retained page. */
    @Test fun sendAsTextIsOffOnARetainedPageAndFollowsItsOwnRulesOtherwise() {
        assertTrue(sendAsTextEnabled(canonical = true, activeHere = false, unconfirmedSend = false, retainedPage = false))
        assertFalse(sendAsTextEnabled(canonical = true, activeHere = false, unconfirmedSend = false, retainedPage = true))
        assertFalse(sendAsTextEnabled(canonical = false, activeHere = false, unconfirmedSend = false, retainedPage = false))
        assertFalse(sendAsTextEnabled(canonical = true, activeHere = true, unconfirmedSend = false, retainedPage = false))
        assertFalse(sendAsTextEnabled(canonical = true, activeHere = false, unconfirmedSend = true, retainedPage = false))
    }

    @Test fun everyOtherReasonStillTurnsSendOff() {
        assertFalse(enabled(editable = false))
        assertFalse(enabled(preparing = true))
        assertFalse(enabled(unconfirmedSend = true))
        assertFalse(enabled(callActive = true))
        assertFalse(enabled(readOnly = true))
    }
}
