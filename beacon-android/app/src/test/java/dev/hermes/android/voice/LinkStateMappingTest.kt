package dev.hermes.android.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calls contract v3, section 6 (provider mapping): what the phone knows about a pending function reaches the model as that function's
 * own result. A state the owner should hear is spoken once and keeps the function open; quiet progress only joins the context; the
 * final result closes it. That the same state never goes twice in a row and nothing follows the final result is CallLinkState's rule
 * (CallLinkStateTest): every function response of a call comes from it.
 */
class LinkStateMappingTest {
    private fun fixture(name: String) = JSONObject(checkNotNull(javaClass.getResourceAsStream("/calls_v3/$name")).bufferedReader().use { it.readText() })
    /** JSON text with sorted keys and numbers in one form, so two equal values compare equal whatever order org.json keeps. */
    internal fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
        is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        is Number -> value.toDouble().let { if (it % 1.0 == 0.0 && kotlin.math.abs(it) < 1e15) it.toLong().toString() else it.toString() }
        else -> if (value == null || value == JSONObject.NULL) "null" else value.toString()
    }
    /** The result the call engine really sends for a function's state: CallLinkState's own (DirectVoice.admitted and deliverReceipt). */
    private fun engineState(status: String, name: String = "hermes_work"): JSONObject {
        var sent: JSONObject? = null
        val ledger = CallLinkState({ 0L }, { replies, _ -> sent = replies.last().result })
        ledger.open("call-1", name, "req-1", hermes = true, retrySafe = true)
        ledger.state("call-1", status, null, fixture("handover_queued.json"))
        return checkNotNull(sent)
    }
    private fun gemini(result: JSONObject, name: String = "notes_search") =
        GeminiWire.tool("call-1", name, result).getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)

    @Test fun theFourLinkStatesKeepTheFunctionOpenAndAreSpokenWhenTheModelIsIdle() {
        for (state in listOf("still_running", "hermes_unreachable", "lost_result")) {
            val result = fixture("link_state_$state.json")
            val response = gemini(result)
            assertEquals(state, "WHEN_IDLE", response.getString("scheduling"))
            assertTrue(state, response.getBoolean("willContinue"))
            assertEquals("the app adds no words of its own", canonical(result), canonical(response.getJSONObject("response")))
        }
        val approval = gemini(fixture("invoke_needs_approval.json"), "t_hermes_notes_create")
        assertEquals("WHEN_IDLE", approval.getString("scheduling")); assertTrue(approval.getBoolean("willContinue"))
        val failed = gemini(fixture("link_state_failed.json"))
        assertEquals("WHEN_IDLE", failed.getString("scheduling")); assertFalse("failed is final", failed.getBoolean("willContinue"))
    }

    @Test fun finalResultsCloseTheFunctionAndProgressStaysQuiet() {
        for (name in listOf("invoke_completed.json", "invoke_denied.json", "invoke_uncertain.json", "handover_not_sent.json")) {
            val response = gemini(fixture(name))
            assertEquals(name, "WHEN_IDLE", response.getString("scheduling")); assertFalse(name, response.getBoolean("willContinue"))
        }
        // Just-started work, as the engine sends it for a running invoke or an admitted hand-over: quiet, and the function stays open.
        for (name in listOf("notes_search", "hermes_work")) {
            val response = gemini(engineState("running", name), name)
            assertEquals(name, "SILENT", response.getString("scheduling")); assertTrue(name, response.getBoolean("willContinue"))
        }
        // Work still going 8 s later is spoken once.
        assertEquals("WHEN_IDLE", gemini(engineState("still_running"), "hermes_work").getString("scheduling"))
        // A result with no status at all (a lookup, a phone read) is final.
        assertFalse(gemini(JSONObject().put("summary", "Synthetic"), "hermes_memory").getBoolean("willContinue"))
    }

    @Test fun everyFunctionNameGetsTheSameMappingAndTheAppsWillUpdateDecidesWhetherMoreFollows() {
        for (name in listOf("notes_search", "hermes_work", "invoke_tool", "phone_calendars", "hermes_memory")) {
            for (state in listOf("still_running", "hermes_unreachable", "lost_result")) {
                val response = gemini(fixture("link_state_$state.json"), name)
                assertEquals("$name $state", "WHEN_IDLE", response.getString("scheduling")); assertTrue("$name $state", response.getBoolean("willContinue"))
            }
            assertFalse(name, gemini(fixture("link_state_failed.json"), name).getBoolean("willContinue"))
        }
        // An admitted hand-over stays open for the job's final result, quietly, unless the app says nothing more will come.
        val queued = fixture("handover_queued.json")
        assertTrue(gemini(JSONObject(queued.toString()).put("will_update", true), "hermes_work").getBoolean("willContinue"))
        val closed = gemini(JSONObject(queued.toString()).put("will_update", false), "hermes_work")
        assertFalse(closed.getBoolean("willContinue")); assertEquals("WHEN_IDLE", closed.getString("scheduling"))
        assertTrue(gemini(JSONObject().put("status", "done").put("will_update", true), "hermes_work").getBoolean("willContinue"))
    }

    @Test fun interruptIsNeverUsed() {
        val names = listOf("link_state_still_running.json", "link_state_hermes_unreachable.json", "link_state_lost_result.json", "link_state_failed.json",
            "invoke_completed.json", "invoke_running.json", "invoke_needs_approval.json", "invoke_denied.json", "invoke_uncertain.json", "handover_queued.json", "handover_not_sent.json")
        for (name in names) assertFalse(name, GeminiWire.tool("id", "x", fixture(name)).toString().contains("INTERRUPT"))
    }

    @Test fun grokGetsOneFunctionOutputThenDataOnlyUpdates() {
        val wire = GrokWire()
        wire.inbound(JSONObject().put("type", "response.created").put("response", JSONObject().put("id", "response-1")))
        wire.inbound(JSONObject().put("type", "response.function_call_arguments.done").put("call_id", "call-1").put("name", "notes_search")
            .put("arguments", JSONObject().put("query", "synthetic").toString()))
        // The first state is the function's output; xAI is asked to answer only once its own response is done.
        val first = wire.outbound(GeminiWire.tool("call-1", "notes_search", fixture("link_state_still_running.json")))
        assertEquals(listOf("conversation.item.create"), first.map { it.getString("type") })
        assertEquals("function_call_output", first.single().getJSONObject("item").getString("type"))
        assertEquals("call-1", first.single().getJSONObject("item").getString("call_id"))
        assertEquals(canonical(fixture("link_state_still_running.json")), canonical(JSONObject(first.single().getJSONObject("item").getString("output"))))
        assertNull(wire.continueResponse())
        wire.inbound(JSONObject().put("type", "response.done").put("response", JSONObject().put("id", "response-1").put("status", "completed")))
        assertEquals("response.create", wire.continueResponse()!!.getString("type"))
        // A later state is a message, never a second output for the same call.
        wire.inbound(JSONObject().put("type", "response.done"))
        val lost = wire.outbound(GeminiWire.tool("call-1", "notes_search", fixture("link_state_lost_result.json")))
        assertEquals(listOf("conversation.item.create", "response.create"), lost.map { it.getString("type") })
        val item = lost.first().getJSONObject("item")
        assertEquals("message", item.getString("type")); assertEquals("user", item.getString("role"))
        val update = item.getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(update, update.startsWith("Update for notes_search call call-1, data only: "))
        assertEquals(canonical(fixture("link_state_lost_result.json")), canonical(JSONObject(update.substringAfter("data only: "))))
        wire.inbound(JSONObject().put("type", "response.done"))
        val final = wire.outbound(GeminiWire.tool("call-1", "notes_search", fixture("invoke_completed.json")))
        assertTrue(final.first().getJSONObject("item").getJSONArray("content").getJSONObject(0).getString("text").startsWith("Update for notes_search call call-1, data only: "))
    }

    @Test fun grokQuietProgressIsAnOutputWithoutAnAnswer() {
        val wire = GrokWire()
        wire.inbound(JSONObject().put("type", "response.function_call_arguments.done").put("call_id", "call-1").put("name", "hermes_work")
            .put("arguments", JSONObject().put("text", "synthetic long request").toString()))
        wire.inbound(JSONObject().put("type", "response.done"))
        val admitted = wire.outbound(GeminiWire.tool("call-1", "hermes_work", engineState("running")))
        assertEquals(listOf("conversation.item.create"), admitted.map { it.getString("type") })
        assertNull(wire.continueResponse())
        val done = wire.outbound(GeminiWire.tool("call-1", "hermes_work", JSONObject().put("job_id", "d4d4").put("status", "done").put("result", "Synthetic summary")))
        assertEquals(listOf("conversation.item.create", "response.create"), done.map { it.getString("type") })
    }
}
