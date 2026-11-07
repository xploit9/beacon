package dev.hermes.android.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAdministrationTest {
    @Test fun unresolvedPriorUpdateBlocksAnotherApplyAfterRecreation() {
        val ready = JSONObject("""{"can_apply":true,"update_available":true}""")
        assertFalse(NativeServerState(update = ready).canApply)
        assertTrue(NativeServerState(update = ready, updateStatusFresh = true).canApply)
        assertFalse(NativeServerState(update = ready, requestedAt = "2026-10-14T10:00:00Z", applying = false).canApply)
    }

    @Test fun staleReceiptNeverProvesThisUpdate() {
        val receipt = JSONObject("""{"receipt":{"outcome":"success","started_at":"2026-10-13T10:00:00Z","finished_at":"2026-10-13T10:01:00Z"}}""")
        assertEquals("unconfirmed", confirmedUpdateOutcome(null, receipt, "new-action", "2026-10-14T10:00:00Z"))
        assertEquals("unconfirmed", confirmedUpdateOutcome(null, receipt, "", ""))
    }
    @Test fun currentPartialReceiptAndRunningActionStayHonest() {
        val receipt = JSONObject("""{"receipt":{"action_id":"new","outcome":"partial","started_at":"2026-10-14T10:00:01Z","finished_at":"2026-10-14T10:01:00Z"}}""")
        assertEquals("partial", confirmedUpdateOutcome(null, receipt, "new", "2026-10-14T10:00:00Z"))
        assertEquals("partial", confirmedUpdateOutcome(JSONObject("""{"running":true}"""), receipt, "new", "2026-10-14T10:00:00Z"))
    }
    private fun receipt(id: String = "current", outcome: String = "success", finished: String = "2026-10-14T10:01:00Z") = JSONObject()
        .put("action_id", id).put("started_at", "2026-10-14T10:00:01Z").put("finished_at", finished).put("outcome", outcome)
    private val requested = "2026-10-14T10:00:00Z"

    @Test fun terminalReceiptWinsWhileAnotherBusyActionStillBlocksApply() {
        for (outcome in listOf("success", "partial", "failed", "refused", "interrupted")) {
            val state = NativeServerState(update = JSONObject("""{"can_apply":true,"update_available":true}"""), requestedAt = requested, requestedActionId = "current")
                .withUpdateStatus(JSONObject().put("action", JSONObject("""{"running":true,"action_id":"other"}"""))
                    .put("receipt", JSONObject().put("receipt", receipt(outcome = outcome))).put("host_busy", true))
            assertEquals(outcome, state.outcome)
            assertFalse(state.applying)
            assertFalse(state.canApply)
        }
    }

    @Test fun onlyOwnReceiptCanResolveAndSummaryCanOutliveDashboardRegistry() {
        val summary = receipt(outcome = "partial")
        val action = JSONObject().put("running", false).put("receipt", summary)
        val full = JSONObject().put("receipt", receipt(id = "other"))
        assertEquals("partial", confirmedUpdateOutcome(action, full, "current", requested))
        assertEquals("partial", confirmedUpdateOutcome(action, JSONObject().put("receipt", receipt(outcome = "running", finished = "")), "current", requested))
        assertEquals("unconfirmed", confirmedUpdateOutcome(null, full, "current", requested))
        assertEquals("unconfirmed", confirmedUpdateOutcome(null, JSONObject().put("receipt", receipt()), "", requested))
        assertEquals("unconfirmed", confirmedUpdateOutcome(null, JSONObject().put("receipt", receipt().put("action_id", JSONObject.NULL)), "current", requested))
        assertEquals("unconfirmed", confirmedUpdateOutcome(null, JSONObject().put("receipt", receipt().put("started_at", "2020-01-01T00:00:00Z")), "current", requested))
    }

    @Test fun bothNativeInterruptedFormsAreTerminalButUnfinishedRunningIsNotLiveness() {
        for (finished in listOf("", "2026-10-14T10:01:00Z")) {
            assertEquals("interrupted", confirmedUpdateOutcome(null, JSONObject().put("receipt", receipt(outcome = "interrupted", finished = finished)), "current", requested))
        }
        val running = JSONObject().put("receipt", receipt(outcome = "running", finished = ""))
        assertEquals("unconfirmed", confirmedUpdateOutcome(JSONObject("""{"running":false}"""), running, "current", requested))
        assertEquals("running", confirmedUpdateOutcome(JSONObject().put("running", true).put("receipt", receipt(outcome = "running", finished = "")), running, "current", requested))
        assertEquals("unconfirmed", confirmedUpdateOutcome(JSONObject("""{"running":true,"action_id":"other"}"""), running, "current", requested))
    }

    @Test fun cachedStatusCannotAuthorizeAnUpdateOrKeepAnOfflineSpinnerRunning() {
        val state = NativeServerState(update = JSONObject("""{"can_apply":true,"update_available":true}"""), requestedAt = requested, requestedActionId = "current",
            action = JSONObject("""{"running":true,"action_id":"current"}"""), receipt = JSONObject().put("receipt", receipt(outcome = "running", finished = "")), updateStatusFresh = false)
        assertEquals("unconfirmed", state.outcome)
        assertFalse(state.canApply)
        assertFalse(state.canReviewUnknown)
        val completed = state.copy(receipt = JSONObject().put("receipt", receipt()))
        assertEquals("success", completed.outcome)
        assertFalse(completed.canApply)
    }

    @Test fun correlatedNativeRestartWarningsSurviveSuccessfulCompletion() {
        val own = receipt().put("followups", org.json.JSONArray("""[{"step":"build","reason":"retry next launch"}]"""))
            .put("gateway_restart", JSONObject("""{"incomplete":true,"phase_error":"restart interrupted","failed_units":["gateway.service"],"fresh_recovery":{"failed":["work"],"skipped":[{"kind":"dashboard","reason":"manual restart"}]}}"""))
            .put("pending_manual_serves", org.json.JSONArray("""[{"kind":"serve","profile":"work","reason":"restart required"}]"""))
            .put("user_action", JSONObject("""{"step":"dependencies","reason":"repair required"}"""))
        val state = NativeServerState(requestedAt = requested, requestedActionId = "current").withUpdateStatus(
            JSONObject().put("receipt", JSONObject().put("receipt", own)).put("host_busy", false))
        assertEquals("success", state.outcome)
        assertTrue(state.updateWarnings.any { it.contains("restart interrupted") })
        assertTrue(state.updateWarnings.any { it.contains("gateway.service") })
        assertTrue(state.updateWarnings.any { it.contains("manual restart") })
        assertTrue(state.updateWarnings.any { it.contains("repair required") })
        assertTrue(state.updateWarnings.any { it.contains("retry next launch") })
        assertTrue(state.updateWarnings.any { it.contains("restart required") })
        assertTrue(state.copy(requestedActionId = "foreign").updateWarnings.isEmpty())
    }
    @Test fun nativeHealthAndCheckpointFieldsHaveUsefulProjection() {
        val health = parseServerStatus(JSONObject("""{"version":"1","overall":"degraded","gateway_state":"stopped","components":{"gateway":{"status":"degraded","state":"stopped"},"platforms":{"status":"ok","configured":2,"connected":2},"storage":{"status":"ok","reason":null}}}"""))
        assertEquals("stopped", health.gatewayState)
        assertEquals("", health.components.single { it.name == "storage" }.detail)
        assertEquals("2 / 2 connected", health.components.single { it.name == "platforms" }.detail)
        val checkpoints = parseCheckpoints(JSONObject("""{"total_bytes":17,"sessions":[{"session":"native","files":2,"bytes":17}]}"""))
        assertEquals(17L, checkpoints.checkpointBytes)
        assertEquals(2, checkpoints.checkpoints.single().files)
    }
    @Test fun modelSavePinsOriginalRevision() {
        val bot = NativeBot("default", "Bot", "", "", "provider", "model", "original")
        assertEquals("original", bot.saveModel("provider", "new").getString("revision"))
    }
}
