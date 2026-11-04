package dev.hermes.android.voice

import dev.hermes.android.phone.ApprovalChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner, 4 Nov 2026: "even though I activated the settings that hermes doesnt need to ask for me approval it still does". */
class ApprovalChoiceTest {
    private fun approval(name: String, phone: Boolean = false) = CallToolApproval("r1", "p".repeat(32), name, "{}",
        phone = if (phone) PhoneApprovalText("Call Mona", "+966501234567", "", "", 3, 0) else null)

    @Test fun theMenuReadsAndWritesTheTwoStoredChoices() {
        assertEquals(ApprovalChoice.Always, ApprovalChoice.of(asksReads = true, asksChanges = true))
        assertEquals(ApprovalChoice.Changes, ApprovalChoice.of(asksReads = false, asksChanges = true))
        assertEquals(ApprovalChoice.Never, ApprovalChoice.of(asksReads = false, asksChanges = false))
        // A pair the menu cannot make reads as the stricter choice.
        assertEquals(ApprovalChoice.Always, ApprovalChoice.of(asksReads = true, asksChanges = false))
        for (choice in ApprovalChoice.entries) assertEquals(choice, ApprovalChoice.of(choice.asksReads, choice.asksChanges))
    }

    @Test fun onNeverThePhoneApprovesHermesOwnToolsItselfButOnlyUnlocked() {
        val calendar = approval("mcp__tool_gateway_write__google_workspace_manage_event")
        assertTrue(approvesItself(ApprovalChoice.Never, locked = false, approval = calendar))
        assertFalse("a change on a locked phone still needs the unlock", approvesItself(ApprovalChoice.Never, locked = true, approval = calendar))
        assertFalse(approvesItself(ApprovalChoice.Changes, locked = false, approval = calendar))
        assertFalse(approvesItself(ApprovalChoice.Always, locked = false, approval = calendar))
    }

    @Test fun thePhonesOwnActionsKeepTheirRulesOnNever() {
        // A phone call waits for the tap or its countdown whatever the choice.
        assertFalse(approvesItself(ApprovalChoice.Never, locked = false, approval = approval("phone_call", phone = true)))
        assertFalse(approvesItself(ApprovalChoice.Never, locked = false, approval = approval("phone_call")))
    }
}
