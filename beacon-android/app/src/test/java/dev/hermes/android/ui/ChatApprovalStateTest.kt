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

    @Test fun aNewUnsentChatShowsTheProfileDefaultButNeverHidesAReportedState() {
        val unreported = record().put("session_status_known", false)
        assertEquals("smart", chatApprovalSelection(unreported, "owner-profile", "chat-a", newChat = true))
        assertEquals("manual", chatApprovalSelection(record("manual").put("session_status_known", false), "owner-profile", "chat-a", newChat = true))
        assertEquals("unknown", chatApprovalSelection(unreported, "owner-profile", "chat-a"))
        assertEquals("unknown", chatApprovalSelection(record("").put("session_status_known", false), "owner-profile", "chat-a", newChat = true))
        assertEquals("unknown", chatApprovalSelection(unreported, "owner-profile", "chat-b", newChat = true))
        assertEquals("off", chatApprovalSelection(record("off").put("session_status_known", false), "owner-profile", "chat-a", newChat = true))
        assertEquals("yolo", chatApprovalSelection(record(bypass = true), "owner-profile", "chat-a", newChat = true))
    }

    @Test fun autoAndBypassNeverFallBackToManual() {
        assertEquals("smart", chatApprovalSelection(record(), "owner-profile", "chat-a"))
        assertEquals("Auto", approvalChoiceLabel("smart"))
        assertEquals("yolo", chatApprovalSelection(record(bypass = true), "owner-profile", "chat-a"))
        assertEquals("off", chatApprovalSelection(record("off").put("session_status_known", false), "owner-profile", "chat-a"))
    }

    // Live 2026-10-26: enabling YOLO in a just-created chat showed "Unknown" for about 5 s while Hermes saved it.
    @Test fun aSaveOrReadInFlightKeepsAKnownStateOnTheShield() {
        assertEquals("The change just asked for shows at once", "yolo", shownApprovalSelection("smart", inFlight = true, asked = "yolo"))
        assertEquals("Turning YOLO off shows Ask first", "smart", shownApprovalSelection("yolo", inFlight = true, asked = "smart"))
        assertEquals("A save that failed returns to what was last read", "smart", shownApprovalSelection("smart", inFlight = true, asked = null))
        assertEquals("Nothing known stays unknown", "unknown", shownApprovalSelection("unknown", inFlight = true, asked = null))
        assertEquals("With nothing in flight the read state rules", "smart", shownApprovalSelection("smart", inFlight = false, asked = "yolo"))
    }
}
