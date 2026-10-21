package dev.hermes.android.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallEndReportTest {
    private fun receipt(function: String, status: String, readOnly: Boolean? = null) =
        JSONObject().put("function_name", function).put("status", status).also { if (readOnly != null) it.put("read_only", readOnly) }

    @Test fun onlyUnconfirmedHandOffsAndUncertainActionsNeedTheOwnersCheck() {
        assertTrue(outcomeUnknownAtCallEnd(receipt("hermes_work", "submitting_native")))
        assertTrue(outcomeUnknownAtCallEnd(receipt("hermes_work", "submission_unconfirmed")))
        assertTrue(outcomeUnknownAtCallEnd(receipt("invoke_tool", "uncertain")))
        // A tool still running at hang-up is cancelled, not unknown work; a queued hand-off is accepted.
        assertFalse(outcomeUnknownAtCallEnd(receipt("invoke_tool", "running")))
        assertFalse(outcomeUnknownAtCallEnd(receipt("invoke_tool", "ready")))
        assertFalse(outcomeUnknownAtCallEnd(receipt("invoke_tool", "uncertain", readOnly = true)))
        assertFalse(outcomeUnknownAtCallEnd(receipt("hermes_work", "queued")))
    }

    @Test fun callEndAndHandOffTextIsPlain() {
        assertEquals("1 request wasn't sent before the call ended.", callEndNotSentText(1))
        assertEquals("Cypher couldn't confirm 2 requests. Check the chat before asking again.", callEndUnknownText(2))
        assertTrue(handoffWithoutRowMessage("queued").startsWith("Hermes queued this"))
        assertTrue(handoffWithoutRowMessage("streaming").startsWith("Hermes accepted this"))
        for (text in listOf(callEndNotSentText(3), callEndUnknownText(1), handoffWithoutRowMessage("queued")))
            assertFalse(text, Regex("native|linkage|receipt|resubmit", RegexOption.IGNORE_CASE).containsMatchIn(text))
    }
}
