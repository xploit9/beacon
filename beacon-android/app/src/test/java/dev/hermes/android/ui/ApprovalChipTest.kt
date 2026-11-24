package dev.hermes.android.ui

import org.junit.Assert.*
import org.junit.Test

/** The composer's approval shield names a mode only for the chat's own record, and never says "Unknown". */
class ApprovalChipTest {
    @Test fun aChatNotYetReadIsAPlainShieldNotAWord() {
        assertEquals("Approvals", shieldDescription("unknown", "Approvals", approvalChoiceLabel("unknown")))
    }

    @Test fun aKnownModeIsNamedWithTheOwnersWords() {
        assertEquals("Approvals: Ask for risky ones", shieldDescription("manual", "Approvals", approvalChoiceLabel("manual")))
        assertEquals("Approvals: Hermes decides", shieldDescription("smart", "Approvals", approvalChoiceLabel("smart")))
        assertEquals("Approvals: Don't ask", shieldDescription("yolo", "Approvals", approvalChoiceLabel("yolo")))
        assertFalse(listOf("manual", "smart", "off", "yolo").any { shieldDescription(it, "Approvals", approvalChoiceLabel(it)).contains("Unknown") })
    }

    @Test fun aChatWhoseRecordIsMissingOrAnotherChatsIsUnknownNeverTheProfilesMode() {
        val profileOnly = org.json.JSONObject().put("scope_key", "scope-1").put("session_id", "chat-a").put("profile_mode", "manual").put("session_status_known", true)
        assertEquals("manual", chatApprovalSelection(profileOnly, "scope-1", "chat-a"))
        assertEquals("unknown", chatApprovalSelection(null, "scope-1", "chat-a"))
        assertEquals("another chat's record is not this chat's", "unknown", chatApprovalSelection(profileOnly, "scope-1", "chat-b"))
    }
}
