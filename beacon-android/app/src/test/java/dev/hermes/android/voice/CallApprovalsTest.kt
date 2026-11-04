package dev.hermes.android.voice

import dev.hermes.android.phone.ApprovalChoice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner, 4 Nov 2026 (shopping calls): with Full access the approval card flashed and the model said "tap approve". Approvals is
 * Manual, Auto or Full access; the call carries the choice to Hermes and tells its model, and the voice agent acts as Hermes does in chat.
 */
class CallApprovalsTest {
    private fun instruction(approval: String, phoneTools: Set<String> = emptySet()) =
        GeminiWire.setup("model", "", null, JSONObject(), phoneTools, approval).getJSONObject("setup")
            .getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")

    @Test fun eachChoiceIsWhatTheToolRequestCarries() {
        assertEquals("ask", callApprovalMode(ApprovalChoice.Always, heldBack = false))
        assertEquals("changes", callApprovalMode(ApprovalChoice.Changes, heldBack = false))
        assertEquals("owner", callApprovalMode(ApprovalChoice.Never, heldBack = false))
        // A locked phone that holds back what is personal asks for changes, as Auto does.
        assertEquals("changes", callApprovalMode(ApprovalChoice.Never, heldBack = true))
        assertEquals("ask", callApprovalMode(ApprovalChoice.Always, heldBack = true))
    }

    @Test fun withFullAccessTheModelNeverAsksTheOwnerToApprove() {
        val text = instruction("owner", setOf("phone_alarm"))
        assertTrue(text.contains("The owner gave Hermes full access"))
        assertFalse(text.contains("wait for the owner's app approval"))
        assertTrue(text.contains(PhoneVoiceTools.FULL_ACCESS_INSTRUCTION))
        assertFalse(text.contains("Every change or message waits for the owner's tap"))
    }

    @Test fun autoAndManualKeepTheirApprovals() {
        assertTrue(instruction("changes").contains("Mutations may return needs_approval: wait for the owner's app approval"))
        assertTrue(instruction("changes", setOf("phone_alarm")).contains(PhoneVoiceTools.INSTRUCTION))
        assertTrue(instruction("ask").contains("The owner approves every tool"))
    }

    @Test fun theVoiceAgentIsHermesOnTheCall() {
        for (approval in listOf("ask", "changes", "owner")) {
            val text = instruction(approval)
            assertTrue(text.contains(GeminiWire.HERMES_ON_THE_CALL))
        }
        assertTrue(GeminiWire.HERMES_ON_THE_CALL.contains("hermes_work"))
        assertTrue(GeminiWire.HERMES_ON_THE_CALL.contains("visible browser window"))
    }

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
