package dev.hermes.android.ui

import dev.hermes.android.core.json
import org.junit.Assert.*
import org.junit.Test

class ChatApprovalStateTest {
    private fun record(mode: String = "smart", bypass: Boolean = false) = json("scope_key" to "owner-profile", "session_id" to "chat-a", "profile_mode" to mode, "session_status_known" to true, "session_bypass" to bypass)

    @Test fun missingForeignOrUnreportedStateIsNeutral() {
        assertEquals("unknown", chatApprovalSelection(null, "owner-profile", "chat-a"))
        assertEquals("unknown", chatApprovalSelection(record(), "other-owner", "chat-a"))
        assertEquals("unknown", chatApprovalSelection(record(), "owner-profile", "chat-b"))
        assertEquals("unknown", chatApprovalSelection(record().put("session_status_known", false), "owner-profile", "chat-a"))
        assertEquals("unknown", chatApprovalSelection(record("manual").put("session_status_known", false), "owner-profile", "chat-a"))
        assertEquals("unknown", chatApprovalSelection(record("").put("session_status_known", false), "owner-profile", "chat-a"))
        assertEquals("Unknown", approvalChoiceLabel("unknown"))
    }

    @Test fun autoAndBypassNeverFallBackToManual() {
        assertEquals("smart", chatApprovalSelection(record(), "owner-profile", "chat-a"))
        assertEquals("Auto", approvalChoiceLabel("smart"))
        assertEquals("yolo", chatApprovalSelection(record(bypass = true), "owner-profile", "chat-a"))
        assertEquals("off", chatApprovalSelection(record("off").put("session_status_known", false), "owner-profile", "chat-a"))
    }
}
