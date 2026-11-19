package dev.hermes.android.voice

import dev.hermes.android.phone.ApprovalChoice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner, 4 Nov 2026 (shopping calls): with Full access the approval card flashed and the model said "tap approve". Approvals is
 * Manual, Auto or Full access; the call carries the choice to Hermes and tells its model, and the voice agent acts as Hermes does in chat.
 */
class CallApprovalsTest {
    @Test fun eachChoiceIsWhatTheToolRequestCarries() {
        assertEquals("ask", callApprovalMode(ApprovalChoice.Always, heldBack = false))
        assertEquals("changes", callApprovalMode(ApprovalChoice.Changes, heldBack = false))
        assertEquals("owner", callApprovalMode(ApprovalChoice.Never, heldBack = false))
        // A locked phone that holds back what is personal asks for changes, as Auto does.
        assertEquals("changes", callApprovalMode(ApprovalChoice.Never, heldBack = true))
        assertEquals("ask", callApprovalMode(ApprovalChoice.Always, heldBack = true))
    }

    // The approval line and the rest of the call instruction are lane C's (CallInstruction.kt, CallInstructionTest), calls contract v3 section 7.

    @Test fun aRequestHermesFilledInWithDefaultsIsKeptAndAChangedOneIsNot() {
        val tool = "a".repeat(64)
        fun receipt(arguments: JSONObject) = JSONObject().put("request_id", "request").put("tool_id", tool).put("tool_name", "make_request")
            .put("status", "needs_approval").put("proposal", JSONObject().put("id", "p".repeat(32)).put("tool_name", "make_request").put("arguments", arguments))
        val given = JSONObject().put("url", "https://example.test/").put("timeout", "60000")
        val ledger = DirectGatewayReceipts().apply { reserve("request", "function-id", tool, given) }
        // Hermes added a GET method and typed the timeout as a number: what the model asked for is all there.
        ledger.record("request", receipt(JSONObject().put("url", "https://example.test/").put("timeout", 60000).put("method", "GET")))
        val other = DirectGatewayReceipts().apply { reserve("request", "function-id", tool, given) }
        assertThrows(IllegalArgumentException::class.java) {
            other.record("request", receipt(JSONObject().put("url", "https://example.test/other").put("timeout", 60000)))
        }
        assertThrows(IllegalArgumentException::class.java) { other.record("request", receipt(JSONObject().put("url", "https://example.test/"))) }
    }
}
