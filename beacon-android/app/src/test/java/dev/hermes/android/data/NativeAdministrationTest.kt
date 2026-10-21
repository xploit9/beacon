package dev.hermes.android.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAdministrationTest {
    @Test fun unresolvedPriorUpdateBlocksAnotherApplyAfterRecreation() {
        val ready = JSONObject("""{"can_apply":true,"update_available":true}""")
        assertTrue(NativeServerState(update = ready).canApply)
        assertFalse(NativeServerState(update = ready, requestedAt = "2026-10-14T10:00:00Z", applying = false).canApply)
    }

    @Test fun staleReceiptNeverProvesThisUpdate() {
        val receipt = JSONObject("""{"receipt":{"outcome":"success","started_at":"2026-10-13T10:00:00Z","finished_at":"2026-10-13T10:01:00Z"}}""")
        assertEquals("unconfirmed", confirmedUpdateOutcome(null, receipt, "new-action", "2026-10-14T10:00:00Z"))
        assertEquals("unconfirmed", confirmedUpdateOutcome(null, receipt, "", ""))
    }
    @Test fun currentPartialReceiptAndRunningActionStayHonest() {
        val receipt = JSONObject("""{"receipt":{"outcome":"partial","started_at":"2026-10-14T10:00:01Z","finished_at":"2026-10-14T10:01:00Z"}}""")
        assertEquals("partial", confirmedUpdateOutcome(null, receipt, "new", "2026-10-14T10:00:00Z"))
        assertEquals("running", confirmedUpdateOutcome(JSONObject("""{"running":true}"""), receipt, "new", "2026-10-14T10:00:00Z"))
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
