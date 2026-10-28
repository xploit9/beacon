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

    private val hash = "0123456789abcdef".repeat(4) // 64 hex, as the plugin names a callback
    private fun callbackJob() = json("id" to "cb1", "name" to "Beacon callback $hash", "script" to "beacon-callback-$hash.py", "no_agent" to true, "state" to "scheduled",
        "schedule" to json("kind" to "once", "run_at" to "2026-10-28T19:01:00+04:00", "display" to "once at 2026-10-28 19:01"))
    private fun watchJob(stored: String = "*/15 10-21 * * *") = json("id" to "w1", "name" to "Beacon: what needs you", "deliver" to "local", "schedule" to json("kind" to "cron", "expr" to stored, "display" to stored),
        "prompt" to "<!-- BEACON_PROACTIVE_JOB:watch_email -->\nBefore using any tool, require a fresh guard marker.")

    @Test fun finishedOneTimeSchedulesMoveToCompletedAndFailedOnesStay() {
        val once = json("kind" to "once", "run_at" to "2026-10-14T09:00:00+04:00")
        val daily = json("kind" to "cron", "expr" to "0 8 * * *")
        val upcoming = json("id" to "a", "state" to "scheduled", "next_run_at" to "2026-10-29T08:00:00+04:00", "schedule" to daily)
        val done = json("id" to "b", "state" to "completed", "enabled" to false, "last_status" to "ok", "schedule" to once)
        val failed = json("id" to "c", "state" to "completed", "enabled" to false, "last_status" to "error", "schedule" to once)
        val pendingOnce = json("id" to "d", "state" to "scheduled", "next_run_at" to "2026-10-29T19:01:00+04:00", "schedule" to once)
        val limitReached = json("id" to "e", "state" to "completed", "enabled" to false, "schedule" to daily, "repeat" to json("times" to 3, "completed" to 3))
        val limitNotReached = json("id" to "f", "state" to "completed", "enabled" to false, "schedule" to daily, "repeat" to json("times" to 3, "completed" to 1))
        val pausedOnce = json("id" to "g", "state" to "paused", "enabled" to false, "schedule" to once)
        val unknown = json("id" to "h", "state" to "completed", "enabled" to false)
        val (active, completed) = splitSchedules(listOf(upcoming, done, failed, pendingOnce, limitReached, limitNotReached, pausedOnce, unknown))
        assertEquals(listOf("a", "c", "d", "f", "g", "h"), active.map { it.string("id") })
        assertEquals(listOf("b", "e"), completed.map { it.string("id") })
        // A one-time job that ran badly is a Failed schedule to look at, not a completed one, and it is finished, so it cannot be paused.
        assertEquals("Failed", scheduleState(failed)); assertTrue(scheduleEnded(failed)); assertEquals("Completed", scheduleState(done))
        assertFalse(scheduleEnded(pendingOnce)); assertFalse(scheduleEnded(pausedOnce))
        assertEquals("Failed", scheduleState(JSONObject(done.toString()).put("last_status", "delivery_failed")))
        // A one-time job a gateway restart cut off ends with a status Hermes names freely, but always with last_error.
        val interrupted = JSONObject(done.toString()).put("last_status", "interrupted").put("last_error", "interrupted")
        assertEquals("Failed", scheduleState(interrupted)); assertTrue(splitSchedules(listOf(interrupted)).second.isEmpty())
        assertEquals(emptyList<JSONObject>() to emptyList<JSONObject>(), splitSchedules(emptyList()))
    }

    @Test fun aCallbackIsTitledCallbackAndNeverShowsItsHash() {
        val callback = callbackJob()
        assertTrue(scheduleIsCallback(callback))
        assertTrue("by name alone", scheduleIsCallback(json("name" to "Beacon callback $hash")))
        assertTrue("by script alone, in a folder", scheduleIsCallback(json("script" to "C:\\Users\\x\\scripts\\beacon-callback-$hash.py")))
        assertFalse(scheduleIsCallback(json("name" to "Beacon callback notes", "script" to "daily-notes.py")))
        assertFalse(scheduleIsCallback(json("name" to "Morning summary")))
        assertEquals("Callback", scheduleTitle(callback, "Untitled schedule", "Callback"))
        assertEquals("Morning summary", scheduleTitle(json("name" to "Morning summary"), "Untitled schedule", "Callback"))
        assertEquals("Untitled schedule", scheduleTitle(JSONObject(), "Untitled schedule", "Callback"))
        val shown = listOf(scheduleTitle(callback, "Untitled schedule", "Callback"), scheduleBody(callback, ""), describeSchedule(callback.getJSONObject("schedule"), scheduleDisplay(callback), "Asia/Dubai", ScheduleFormat(true, java.util.Locale.US, { it }, "d MMM")))
        shown.forEach { assertFalse(it, it.contains(hash.take(8))) }
        assertEquals("Once · 28 Oct, 19:01", shown[2])
    }

    @Test fun aCallbackBodyIsTheReasonThePluginGivesOrNothing() {
        val capabilities = json("callbacks" to json("cb1" to json("reason" to "  Follow up on the invoice ")))
        assertEquals("Follow up on the invoice", callbackReason(capabilities, "cb1"))
        assertEquals("", callbackReason(capabilities, "other"))
        assertEquals("", callbackReason(null, "cb1"))
        assertEquals("", callbackReason(JSONObject(), "cb1"))
        assertEquals("", callbackReason(json("callbacks" to json("cb1" to json("reason" to JSONObject.NULL))), "cb1"))
        assertEquals("Follow up on the invoice", scheduleBody(callbackJob(), callbackReason(capabilities, "cb1")))
        assertEquals("", scheduleBody(callbackJob(), ""))
    }

    @Test fun aBeaconManagedJobReadsInOneLineAndAnOwnersPromptLosesItsComments() {
        val watch = watchJob()
        assertEquals("watch_email", proactiveKind(watch))
        assertEquals("Looks through your connected sources and lets you know when something needs you.", scheduleBody(watch, ""))
        assertEquals("Tidies up what Beacon remembers about you.", scheduleBody(json("prompt" to "<!-- BEACON_PROACTIVE_JOB:nightly_memory -->\nReview only this profile's memory."), ""))
        assertEquals("Runs in the background for Beacon.", scheduleBody(json("prompt" to "<!-- BEACON_PROACTIVE_JOB:future_kind -->\nx"), ""))
        assertFalse(scheduleBody(watch, "").contains("<!--"))
        assertEquals("Summarize the news.", scheduleBody(json("prompt" to "<!-- note -->\nSummarize the news.\n<!-- two\nlines -->"), ""))
        assertEquals("", scheduleBody(json("prompt" to "<!-- only a comment -->"), ""))
        // The marker counts only at the start of the prompt.
        assertNull(proactiveKind(json("prompt" to "Keep <!-- BEACON_PROACTIVE_JOB:watch_email --> out of it")))
        assertTrue(scheduleNameOnly(watch)); assertTrue(scheduleNameOnly(json("script" to "C:/x/beacon-checkups.py")))
        assertFalse(scheduleNameOnly(json("prompt" to "Keep <!-- BEACON_PROACTIVE_JOB:watch_email --> out of it"))); assertFalse(scheduleNameOnly(JSONObject()))
    }

    private fun openedAsTheFormSeedsIt(job: JSONObject, today: java.time.LocalDate = java.time.LocalDate.of(2026, 10, 28)): JSONObject {
        val stored = nativeScheduleInput(job)
        val seed = schedulePayload(job)
        parseScheduleString(stored).formSeed(today).forEach { (key, value) -> seed.put(key, value) }
        seed.put("_timezone", "Asia/Dubai").put("schedule", stored)
        return seed.put("_original", JSONObject(seed.toString()))
    }

    @Test fun renamingABeaconManagedJobSendsOnlyTheName() {
        val body = JSONObject(openedAsTheFormSeedsIt(watchJob()).toString()).put("name", "Renamed")
        body.put("schedule", formSchedule(body))
        assertEquals(setOf("name"), scheduleUpdates(body).keys().asSequence().toSet())
        assertEquals("*/15 10-21 * * *", body.getString("schedule"))
    }

    @Test fun aSavedDailyScheduleOpensAsDailyAndOnlyChangesWhenItIsChanged() {
        val job = json("id" to "d1", "name" to "Cybersecurity news", "deliver" to "local", "schedule" to json("kind" to "cron", "expr" to "0 8 * * *", "display" to "0 8 * * *"))
        val seed = openedAsTheFormSeedsIt(job)
        assertEquals("Daily", seed.getString("_schedule_mode")); assertEquals(8, seed.getInt("_hour")); assertEquals(0, seed.getInt("_minute"))
        val untouched = JSONObject(seed.toString()).put("name", "Security digest")
        untouched.put("schedule", formSchedule(untouched))
        assertEquals(setOf("name"), scheduleUpdates(untouched).keys().asSequence().toSet())
        val later = JSONObject(seed.toString()).put("_hour", 9L)
        later.put("schedule", formSchedule(later))
        assertEquals(setOf("schedule"), scheduleUpdates(later).keys().asSequence().toSet()); assertEquals("0 9 * * *", scheduleUpdates(later).getString("schedule"))
        // Another mode builds its own string and ignores the stale text field; Weekly starts on Monday to Friday.
        val weekly = JSONObject(seed.toString()).put("_schedule_mode", "Weekly")
        assertEquals("0 8 * * 1,2,3,4,5", formSchedule(weekly))
        assertEquals("0 8 * * 1,3", formSchedule(weekly.put("_wd_2", false).put("_wd_4", false).put("_wd_5", false)))
    }

    @Test fun aStoredIntervalOpensInItsLargestUnitAndIsNotRewrittenByAnUnrelatedEdit() {
        val job = json("id" to "i1", "name" to "Poll", "deliver" to "local", "schedule" to json("kind" to "interval", "minutes" to 120, "display" to "every 120m"))
        val seed = openedAsTheFormSeedsIt(job)
        assertEquals("Every", seed.getString("_schedule_mode")); assertEquals(2, seed.getInt("_every_n")); assertEquals("hours", seed.getString("_every_unit"))
        val renamed = JSONObject(seed.toString()).put("name", "Poll often")
        renamed.put("schedule", formSchedule(renamed))
        assertEquals("every 120m", renamed.getString("schedule"))
        assertEquals(setOf("name"), scheduleUpdates(renamed).keys().asSequence().toSet())
    }

    @Test fun aThreadTargetNeverShowsItsRawIdOnACard() {
        val thread = "phone:$hash"
        assertEquals("A thread", deliveryLabel(thread, deliveryNames(listOf(json("id" to "phone", "name" to "Beacon chat")))))
        assertEquals("Cybersecurity news", deliveryLabel(thread, deliveryNames(listOf(json("id" to thread, "name" to "Cybersecurity news")))))
        assertEquals("Beacon chat, A thread", deliveryLabel("phone, $thread", deliveryNames(listOf(json("id" to "phone", "name" to "Beacon chat")))))
        assertEquals("Untitled thread", deliveryLabel(thread, deliveryNames(listOf(json("id" to thread, "name" to "Untitled thread")))))
    }

    @Test fun hermesDisplayTextIsOnlyAFallbackForTheScheduleLine() {
        assertEquals("every 2h", scheduleDisplay(json("schedule" to "every 2h")))
        assertEquals("every 15m", scheduleDisplay(json("schedule" to json("kind" to "interval", "display" to "every 15m"), "schedule_display" to "ignored")))
        assertEquals("0 10 * * *", scheduleDisplay(json("schedule" to json("kind" to "cron"), "schedule_display" to "0 10 * * *")))
        assertEquals("", scheduleDisplay(json("schedule" to json("kind" to "cron"))))
    }
}
