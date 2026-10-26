package dev.hermes.android.tasks
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import org.json.JSONArray
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
    @Test fun aRefusedDeliveryIsAFailureWithItsReason() {
        val refused = JSONObject().put("last_status", "delivery_failed").put("last_delivery_error", "That thread is no longer available").put("next_run_at", "2026-10-27T08:00:00+04:00")
        assertEquals("Failed", scheduleState(refused))
        assertEquals("That thread is no longer available", scheduleDeliveryError(refused))
        assertEquals("An older error is not shown after a good run", "", scheduleDeliveryError(JSONObject(refused.toString()).put("last_status", "ok")))
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
    private fun ids(native: JSONObject, threads: List<JSONObject>) = deliveryTargets(native, threads).rows("targets").map { it.string("id") }
    @Test fun theEditorOffersBeaconChatThenEachThreadThenTheOtherPlatforms() {
        val native = JSONObject().put("targets", JSONArray()
            .put(json("id" to "local", "name" to "Local (save only)", "home_target_set" to true))
            .put(json("id" to "phone", "name" to "Phone", "home_target_set" to true, "home_env_var" to "PHONE_HOME_CHANNEL"))
            .put(json("id" to "telegram", "name" to "Telegram", "home_target_set" to false, "home_env_var" to "TELEGRAM_HOME_CHANNEL")))
        val rows = deliveryTargets(native, listOf(json("id" to "t1", "title" to "Cybersecurity news"), json("id" to "t2", "title" to ""))).rows("targets")
        assertEquals(listOf("local", "phone", "phone:t1", "phone:t2", "telegram"), rows.map { it.string("id") })
        assertEquals(listOf("Local (save only)", "Beacon chat", "Cybersecurity news", "Untitled thread", "Telegram"), rows.map { it.string("name") })
        assertTrue(rows[2].optBoolean("home_target_set"))
        assertEquals("Phone", native.rows("targets")[1].string("name"))
    }
    @Test fun withoutAPhonePlatformThreadsComeLastAndNoThreadsLeavesHermesTargetsAlone() {
        val native = JSONObject().put("targets", JSONArray().put(json("id" to "local", "name" to "Local")).put(json("id" to "telegram", "name" to "Telegram")))
        assertEquals(listOf("local", "telegram"), ids(native, emptyList()))
        assertEquals(listOf("local", "telegram", "phone:t1"), ids(native, listOf(json("id" to "t1", "title" to "News"))))
        assertEquals(listOf("phone:t1"), ids(JSONObject(), listOf(json("id" to "t1", "title" to "News"), json("title" to "no id"))))
    }
    @Test fun aJobNamesItsTargetAndAGoneThreadStillReadsAsOne() {
        val names = deliveryNames(listOf(json("id" to "phone", "name" to "Beacon chat"), json("id" to "phone:t1", "name" to "Cybersecurity news"), json("id" to "telegram", "name" to "Telegram")))
        assertEquals("Save result in Beacon", deliveryLabel("local", names))
        assertEquals("Save result in Beacon", deliveryLabel("", names))
        assertEquals("Cybersecurity news", deliveryLabel("phone:t1", names))
        assertEquals("Beacon chat, Telegram", deliveryLabel("phone, telegram", names))
        assertEquals("A thread", deliveryLabel("phone:gone", names))
    }
    @Test fun aThreadIsFoundInAJobsTargetsByItsExactId() {
        assertTrue(deliversTo(json("deliver" to "telegram, phone:t1"), threadTarget("t1")))
        assertFalse(deliversTo(json("deliver" to "phone:t10"), threadTarget("t1")))
        assertFalse(deliversTo(json("deliver" to "phone"), threadTarget("t1")))
        assertFalse(deliversTo(JSONObject(), threadTarget("t1")))
    }
    @Test fun theThreadSheetCanOfferItsThreadBeforeTheListLoadsAndNeverTwice() {
        val own = listOf(json("id" to "local", "name" to "Local"))
        val added = withThreadTarget(own, "t1", "News")
        assertEquals(listOf("local", "phone:t1"), added.map { it.string("id") })
        assertSame(added, withThreadTarget(added, "t1", "Other"))
        // A thread Hermes lists without a title yet takes the open thread's own name.
        val listed = deliveryTargets(JSONObject().put("targets", JSONArray()), listOf(JSONObject().put("id", "t2").put("title", ""))).rows("targets")
        assertEquals("Untitled thread", listed.single().string("name"))
        assertEquals("📰 QA channel", withThreadTarget(listed, "t2", "📰 QA channel").single().string("name"))
    }
    @Test fun editingAJobKeepsItsThreadUnlessTheTargetChanges() {
        val original = JSONObject().put("name", "News").put("schedule", "every day at 8am").put("deliver", "phone:t1")
        val renamed = JSONObject(original.toString()).put("name", "Morning news").put("_original", original)
        assertEquals(setOf("name"), scheduleUpdates(renamed).keys().asSequence().toSet())
        val moved = JSONObject(original.toString()).put("deliver", "phone:t2").put("_original", original)
        assertEquals("phone:t2", scheduleUpdates(moved).getString("deliver"))
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
