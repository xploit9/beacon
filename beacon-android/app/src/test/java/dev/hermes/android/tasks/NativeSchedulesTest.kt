package dev.hermes.android.tasks
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class NativeSchedulesTest {
    @Test fun nativeSchedulesUseEditableCanonicalValuesInsteadOfDisplayLabels() {
        fun job(kind: String, key: String, value: Any) = JSONObject().put("schedule",JSONObject().put("kind",kind).put(key,value).put("display","once at an unparseable display label"))
        assertEquals("0 10-22/2 * * *",nativeScheduleInput(job("cron","expr","0 10-22/2 * * *")))
        assertEquals("2026-10-14T12:00:00+04:00",nativeScheduleInput(job("once","run_at","2026-10-14T12:00:00+04:00")))
        assertEquals("every 120m",nativeScheduleInput(job("interval","minutes",120)))
        assertEquals("every 2h",nativeScheduleInput(JSONObject().put("schedule","every 2h")))
    }
    @Test fun editsDoNotSendImmutableRuntimeOrUneditedAdvancedExecutionFields() {
        val original = JSONObject().put("name", "old").put("schedule", "every 2h").put("deliver", "local")
        val body = JSONObject(original.toString()).put("name", "new").put("id", "immutable").put("job_id", "identity").put("next_run_at", "stale").put("script", "server-owned").put("enabled_toolsets", "private").put("_original", original)
        val payload = scheduleUpdates(body)
        assertEquals(setOf("name"), payload.keys().asSequence().toSet())
        assertEquals("new", payload.getString("name"))
        assertFalse(schedulePayload(body).has("script"))
    }
    @Test fun fixedWindowHasExplicitEndInclusivityAndRejectsRanges() {
        val body=JSONObject().put("_start_hour",10).put("_end_hour",22).put("_every_hours",2)
        assertEquals("0 10-21/2 * * *", fixedWindowSchedule(body))
        assertEquals("0 10-22/2 * * *", fixedWindowSchedule(body.put("_include_end",true)))
        assertTrue(runCatching { fixedWindowSchedule(body.put("_start_hour",23)) }.isFailure)
        assertTrue(runCatching { fixedWindowSchedule(body.put("_start_hour",10).put("_every_hours",0)) }.isFailure)
    }
    @Test fun statusNeverInfersRunningFromPersistence() {
        assertEquals("Execution unconfirmed",scheduleState(JSONObject()))
        assertEquals("Completed",scheduleState(JSONObject().put("enabled",false).put("state","completed")))
        assertEquals("Waiting",scheduleState(JSONObject().put("scheduler_heartbeat_age_s",200).put("next_run_at","tomorrow")))
        assertEquals("Failed",scheduleState(JSONObject().put("last_status","error")))
        assertEquals("Paused",scheduleState(JSONObject().put("paused",true)))
    }
    @Test fun staleSchedulerHeartbeatMovesTheExplanationToTheScreenLevel() {
        val stale = JSONObject().put("scheduler_heartbeat_age_s", 200).put("next_run_at", "tomorrow")
        assertTrue(scheduleGatewayNeedsAttention(stale))
        assertEquals("Waiting", scheduleState(stale))
        assertFalse(scheduleGatewayNeedsAttention(JSONObject().put("scheduler_heartbeat_age_s", 120)))
    }
    @Test fun pausedFollowsHermesEnabledAndStateOverAStaleLegacyFlag() {
        // Live 2026-10-19: enabled, state "scheduled", still running every 5 min, but a stale "paused": true.
        val running = JSONObject().put("paused",true).put("enabled",true).put("state","scheduled").put("next_run_at","2026-10-19T00:40:00+04:00")
        assertFalse(schedulePaused(running)); assertEquals("Waiting",scheduleState(running))
        assertEquals("Failed",scheduleState(JSONObject(running.toString()).put("last_status","error")))
        assertTrue(schedulePaused(JSONObject().put("enabled",false).put("state","paused")))
        assertTrue(schedulePaused(JSONObject().put("enabled",true).put("state","paused")))
        assertEquals("Paused",scheduleState(JSONObject().put("enabled",false).put("state","scheduled")))
        assertFalse(schedulePaused(JSONObject().put("enabled",false).put("state","completed")))
    }
}
