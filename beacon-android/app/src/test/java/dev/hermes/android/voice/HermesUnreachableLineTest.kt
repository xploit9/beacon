package dev.hermes.android.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Root, 14 Nov 2026: while the phone cannot reach Hermes (a function was told hermes_unreachable or lost_result), the approval card and the
 * tool chip say one short line, "Can't reach Hermes", instead of spinning; the card itself stays until Hermes decides it.
 */
class HermesUnreachableLineTest {
    private fun receipt(status: String, tool: String = "mcp__notes__search") = CallToolReceipt("req-1", tool, status)
    private val card = CallToolApproval("req-1", "proposal-synthetic-0000000000000000000000000000", "mcp__notes__create", "{}")

    @Test fun theChipSaysItWhileATerminalStateIsLeftAsItIs() {
        for (status in listOf("ready", "running", "needs_approval")) {
            assertEquals(status, HERMES_UNREACHABLE_LINE, receipt(status).chipText(unreachable = true))
            assertEquals(status, receipt(status).chipLabel(), receipt(status).chipText(unreachable = false))
        }
        assertEquals("Using {0}…", receipt("running").activityTemplate())
        // A tool that already finished keeps its own line.
        assertEquals(receipt("failed").chipLabel(), receipt("failed").chipText(unreachable = true))
        assertTrue("translated with the other call tool lines", HERMES_UNREACHABLE_LINE in CallToolStrings)
    }

    @Test fun theCardSaysItInsteadOfSpinningAndKeepsItsAnswers() {
        val deciding = card.copy(busy = true)
        assertEquals(HERMES_UNREACHABLE_LINE, deciding.line(unreachable = true)); assertFalse(deciding.showsProgress(unreachable = true))
        assertNull(deciding.line(unreachable = false)); assertTrue(deciding.showsProgress(unreachable = false))
        assertEquals(HERMES_UNREACHABLE_LINE, card.line(unreachable = true))
        assertEquals(callToolApprovalErrorMessage("Approval proposal mismatch"), card.copy(error = "Approval proposal mismatch").line(unreachable = false))
    }

    @Test fun aLinkStateNeverClosesAnApprovalCardButHermesOwnAnswerDoes() {
        val cards = listOf(card)
        for (status in listOf("still_running", "hermes_unreachable", "lost_result")) {
            val state = JSONObject().put("type", "tool_receipt").put("request_id", "req-1").put("tool_name", "mcp__notes__create").put("status", status)
            assertEquals(status, cards, pendingCallToolApprovals(cards, state))
        }
        val answered = JSONObject().put("type", "tool_receipt").put("request_id", "req-1").put("tool_name", "mcp__notes__create").put("status", "completed")
        assertTrue(pendingCallToolApprovals(cards, answered).isEmpty())
    }
}
