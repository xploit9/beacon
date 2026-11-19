package dev.hermes.android.voice

import dev.hermes.android.core.Scope
import dev.hermes.android.data.APP_CONNECTION_ERROR
import dev.hermes.android.data.ApiFailure
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/** Calls contract section 5, phone side: one row per call start, kept on the phone and sent only to a Hermes that takes them. */
class CallStartDiagnosticsTest {
    @get:Rule val folder = TemporaryFolder()
    private val owner = Scope("https://hermes.example", "owner-synthetic", "default")
    private val other = Scope("https://other.example", "owner-synthetic", "default")
    private fun fixture(name: String) = JSONObject(requireNotNull(javaClass.getResource("/calls_v3/$name")).readText())
    private fun stage(name: String, at: Long, extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("type", "media_diagnostic").put("stage", name).put("elapsed_ms", at).put("provider", "gemini").put("attempt", 0).apply(extra)
    private fun v3() = fixture("context_v3.json")

    @Test fun anUnreachableStartIsTheContractRowExactly() {
        // The stages a start that never reached Hermes keeps (BeaconCallService earlyStage): grant_sent, then reach_failed.
        val stages = listOf(stage("grant_sent", 25), stage("reach_failed", 3012) { put("error_code", "timeout"); put("cause_class", "socket_timeout") })
        val row = callStartRow("6f1c2a5e-0b7d-4c3a-9e2f-1a2b3c4d5e6f", "", 1794651219848, "gemini", "tap", "1.2.1", 3, stages, failed = true, shownLine = APP_CONNECTION_ERROR)
        val expected = fixture("diagnostics_request.json").getJSONArray("diagnostics").getJSONObject(0)
        assertEquals("Same fields as the contract fixture", expected.keys().asSequence().toSet(), row.keys().asSequence().toSet())
        for (key in listOf("client_event_id", "tapped_at", "provider", "trigger", "call_contract", "outcome", "failed_stage", "shown")) assertEquals(key, expected.get(key), row.get(key))
        assertTrue(row.isNull("call_id"))
        assertEquals(listOf("grant_sent", "reach_failed"), row.getJSONArray("stages").let { s -> (0 until s.length()).map { s.getJSONObject(it).getString("stage") } })
        assertEquals("timeout", row.getJSONArray("stages").getJSONObject(1).getString("error_code"))
    }

    @Test fun rowsCarryOnlyAllowedValues() {
        val stages = (0 until 40).map { stage("capture_started", it.toLong()) } + listOf(
            stage("made_up_stage", 41), stage("grant_ok", -1),
            stage("voice_error", 50) { put("failed_stage", "provider_setup_ok"); put("error_code", "secret detail"); put("cause_class", "socket_timeout"); put("text", "never sent") })
        val row = callStartRow("id", "call-synthetic", 1, "elsewhere", "made-up", "1.2.1", 2, stages, failed = true, shownLine = CALL_VOICE_NO_ANSWER)
        val kept = row.getJSONArray("stages")
        assertEquals(32, kept.length())
        assertEquals("The failure stage is kept even past the cap", "voice_error", kept.getJSONObject(31).getString("stage"))
        assertFalse(kept.getJSONObject(31).has("error_code")); assertFalse(kept.getJSONObject(31).has("text"))
        assertEquals("socket_timeout", kept.getJSONObject(31).getString("cause_class"))
        (0 until kept.length()).forEach { assertTrue(kept.getJSONObject(it).getString("stage") in START_DIAGNOSTIC_STAGES) }
        assertEquals("gemini", row.getString("provider")); assertEquals("tap", row.getString("trigger"))
        assertEquals("provider_setup_ok", row.getString("failed_stage")); assertEquals("voice_no_answer", row.getString("shown"))
        assertEquals("call-synthetic", row.getString("call_id"))
    }

    @Test fun outcomesFollowTheStages() {
        val connected = CALL_START_STAGES.mapIndexed { i, s -> stage(s, i * 100L) } + stage("first_model_audio", 900)
        callStartRow("a", "c", 1, "gemini", "tap", "1", 3, connected, failed = true, shownLine = CALL_DROPPED).let {
            assertEquals("connected", it.getString("outcome")); assertFalse(it.has("failed_stage")); assertFalse(it.has("shown"))
        }
        callStartRow("b", null, 1, "grok", "callback_answer", "1", 3, listOf(stage("grant_sent", 1), stage("grant_ok", 200)), failed = false, shownLine = null).let {
            assertEquals("cancelled", it.getString("outcome")); assertEquals("context_ok", it.getString("failed_stage")); assertFalse(it.has("shown"))
        }
        assertEquals("hermes_no_answer", shownKey(CALL_HERMES_NO_ANSWER)); assertEquals("mic_failed", shownKey(CALL_MIC_FAILED))
        assertEquals("sign_in_expired", shownKey(CALL_SIGN_IN_EXPIRED)); assertEquals("start_failed", shownKey("Android couldn't start the call. Try again."))
    }

    @Test fun theStoreKeepsFiftyRowsForSevenDaysPerAccount() {
        var now = 1_000_000_000_000L
        val file = folder.newFile("diagnostics.json").apply { delete() }
        val store = CallStartDiagnostics(file, { now })
        repeat(55) { store.record(owner, JSONObject().put("client_event_id", "row-$it")) }
        store.record(other, JSONObject().put("client_event_id", "other"))
        val reopened = CallStartDiagnostics(file, { now })
        assertEquals("The newest 50 across accounts", 49, reopened.pending(owner).size)
        assertEquals("row-54", reopened.pending(owner).last().getString("client_event_id"))
        assertEquals(listOf("other"), reopened.pending(other).map { it.getString("client_event_id") })
        assertFalse("No address is kept on the phone", file.readText().contains("hermes.example"))
        now += 7 * 24 * 3_600_000L + 1
        assertTrue(CallStartDiagnostics(file, { now }).pending(owner).isEmpty())
    }

    @Test fun rowsWaitUntilHermesSaysItTakesThem() = runBlocking {
        val store = CallStartDiagnostics(folder.newFile().apply { delete() })
        val sent = CopyOnWriteArrayList<JSONObject>()
        store.record(owner, JSONObject().put("client_event_id", "a"))
        assertFalse("Unknown Hermes: nothing sent", store.flush(owner, { _, _, body -> sent += body!!; fixture("diagnostics_response.json") }))
        store.learned(owner, JSONObject(v3().toString()).put("call_contract", 2))
        assertFalse("A v2 context", store.flush(owner, { _, _, body -> sent += body!!; fixture("diagnostics_response.json") }))
        store.learned(owner, JSONObject(v3().toString()).put("call_capabilities", JSONObject(v3().getJSONObject("call_capabilities").toString()).put("call_diagnostics", false)))
        assertFalse("The capability is off", store.flush(owner, { _, _, body -> sent += body!!; fixture("diagnostics_response.json") }))
        assertTrue(sent.isEmpty()); assertEquals(2, store.contract(owner))
        store.learned(owner, v3())
        assertEquals(3, store.contract(owner)); assertFalse(store.takes(other))
        val paths = CopyOnWriteArrayList<String>()
        assertTrue(store.flush(owner, { path, method, body -> paths += "$method $path"; sent += body!!; fixture("diagnostics_response.json") }))
        assertEquals(listOf("POST $CALL_DIAGNOSTICS_PATH"), paths.toList())
        assertEquals("a", sent.single().getJSONArray("diagnostics").getJSONObject(0).getString("client_event_id"))
        assertTrue(store.pending(owner).isEmpty())
    }

    @Test fun batchesStayWithinTheRouteLimits() = runBlocking {
        val store = CallStartDiagnostics(folder.newFile().apply { delete() })
        val big = JSONArray((0 until 32).map { JSONObject().put("stage", "capture_started").put("elapsed_ms", 1_000_000L + it).put("error_code", "timeout").put("cause_class", "socket_timeout") })
        repeat(45) { store.record(owner, JSONObject().put("client_event_id", "row-$it").put("stages", big)) }
        store.learned(owner, v3())
        val bodies = CopyOnWriteArrayList<JSONObject>()
        assertTrue(store.flush(owner, { _, _, body -> bodies += body!!; JSONObject().put("stored", body.getJSONArray("diagnostics").length()) }))
        assertTrue(bodies.size > 3)
        bodies.forEach { body ->
            assertTrue(body.getJSONArray("diagnostics").length() in 1..20)
            assertTrue(body.toString().toByteArray().size <= 16_384)
        }
        assertEquals((0 until 45).map { "row-$it" }, bodies.flatMap { b -> b.getJSONArray("diagnostics").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("client_event_id") } } })
    }

    @Test fun aLostBatchStaysAndARefusedOneIsDropped() = runBlocking {
        val store = CallStartDiagnostics(folder.newFile().apply { delete() })
        store.record(owner, JSONObject().put("client_event_id", "a"))
        store.learned(owner, v3())
        assertFalse(store.flush(owner, { _, _, _ -> throw java.io.IOException("Synthetic lost link") }))
        assertFalse(store.flush(owner, { _, _, _ -> throw ApiFailure(503, "Hermes is updating", plugin = true) }))
        assertFalse(store.flush(owner, { _, _, _ -> throw ApiFailure(404, "Not Found", plugin = true) }))
        assertFalse(store.flush(owner, { _, _, _ -> JSONObject() }))
        assertFalse(store.flush(owner, { _, _, _ -> kotlinx.coroutines.delay(10_000); JSONObject().put("stored", 1) }, timeoutMs = 50))
        assertEquals(1, store.pending(owner).size)
        assertTrue("Invalid rows can never be stored: dropped", store.flush(owner, { _, _, _ -> throw ApiFailure(400, "Invalid diagnostics", plugin = true) }))
        assertTrue(store.pending(owner).isEmpty())
    }

    @Test fun aCorruptStoreIsEmptyNotFatal() {
        val file = folder.newFile().apply { writeText("{not json") }
        val store = CallStartDiagnostics(file)
        assertTrue(store.pending(owner).isEmpty())
        store.record(owner, JSONObject().put("client_event_id", "a"))
        assertEquals(1, store.pending(owner).size)
    }
}
