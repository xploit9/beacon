package dev.hermes.android.voice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CallToolApprovalTest {
    private fun proposal()=JSONObject().put("request_id","request-1").put("proposal_id","p".repeat(32)).put("tool_name","draft_email").put("arguments",JSONObject().put("to","fixture@example.invalid").put("body","Exact reviewed content"))
    @Test fun exactArgumentsAndIdentitySurviveApprovalPresentation() {
        val value=CallToolApproval.from(proposal())
        assertEquals("request-1",value.requestId)
        assertEquals("p".repeat(32),value.proposalId)
        assertEquals("draft_email",value.toolName)
        assertEquals("fixture@example.invalid",JSONObject(value.arguments).getString("to"))
        assertEquals("Exact reviewed content",JSONObject(value.arguments).getString("body"))
        assertFalse(value.busy)
        for (text in listOf("https://example.invalid/a", "literal \\/ slash", "C:\\work\\note")) {
            val original = proposal().put("arguments", JSONObject().put("text", text))
            assertEquals(text, JSONObject(CallToolApproval.from(original).arguments).getString("text"))
        }
    }
    @Test fun invalidProposalAndNonObjectArgumentsNeverCreateAnApproval() {
        for(value in listOf(proposal().put("proposal_id",""),proposal().put("request_id","../other"),proposal().put("arguments","hidden"),proposal().put("tool_name",""),
            proposal().put("request_id","r".repeat(161)), proposal().put("proposal_id","p".repeat(31)), proposal().put("proposal_id","p".repeat(81)),
            proposal().put("tool_name","n".repeat(201)), proposal().put("arguments",JSONObject().put("text","é".repeat(32769))))) {
            val error=runCatching { CallToolApproval.from(value) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException || error is org.json.JSONException)
        }
        CallToolApproval.from(proposal().put("request_id","r".repeat(160)).put("proposal_id","p".repeat(80)).put("tool_name","n".repeat(200)))
    }
}
