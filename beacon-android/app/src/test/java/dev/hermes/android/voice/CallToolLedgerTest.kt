package dev.hermes.android.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The call's read reuse (CallToolLedger.kt) against a live read: the agent phone's screen changes by itself (D2, 22 Nov). */
class CallToolLedgerTest {
    private fun completed(requestId: String, live: Boolean) = JSONObject().put("request_id", requestId).put("tool", "agent_phone")
        .put("tool_name", "agent_phone").put("status", "completed").put("read_only", true).put("result", JSONObject().put("success", true))
        .apply { if (live) put("live", true) }

    @Test fun twoIdenticalLooksBothGoToHermesWhileAnOrdinaryReadIsReused() {
        val ledger = DirectGatewayReceipts()
        val look = JSONObject().put("action", "capture")
        assertNull(ledger.reserve("look-1", "function-1", "agent_phone", look))
        ledger.record("look-1", completed("look-1", live = true))
        // Moments later the same look is asked of Hermes again: the screen may have moved by itself.
        assertNull(ledger.reserve("look-2", "function-2", "agent_phone", JSONObject(look.toString())))
        ledger.record("look-2", completed("look-2", live = true))
        assertNull(ledger.reserve("look-3", "function-3", "agent_phone", JSONObject(look.toString())))
        // A look still on its way is joined, not sent twice.
        val joined = ledger.reserve("look-4", "function-4", "agent_phone", JSONObject(look.toString()))!!
        assertEquals("already_requested", joined.getString("status"))
        assertEquals("look-3", joined.getString("request_id"))
        // An ordinary read is still answered from its receipt within 10 s.
        val apps = JSONObject().put("action", "list_apps")
        assertNull(ledger.reserve("apps-1", "function-5", "agent_phone", apps))
        ledger.record("apps-1", completed("apps-1", live = false))
        val again = ledger.reserve("apps-2", "function-6", "agent_phone", JSONObject(apps.toString()))!!
        assertTrue(again.getBoolean("repeated_request"))
        assertEquals("apps-1", again.getString("request_id"))
    }
}
