package dev.hermes.android.data

import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.serverHealthStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX review P1-10: Server > Health said "Hermes is healthy" while Server > Logs showed `FATAL: state.db was replaced underneath the gateway`.
 * Hermes' /api/status builds that verdict from a read-only probe (gateway/readiness.py `_probe_state_db`, hermes_cli/web_routers/status.py
 * `_component_health`) and only reports a corrupt file, never a file replaced under the running gateway (hermes_state_errors.py
 * `_STATE_DB_REPLACED_MSG`). The phone cannot change Hermes' verdict, so it adds what the logs say: a CRITICAL or FATAL line, or the refused write
 * Hermes logs at ERROR with its own "FATAL:" head (hermes_state.py:1288), as recent as the log's own latest activity turns Healthy into Needs attention.
 */
class ServerRecentErrorTest {
    private val fatal = "2026-11-13 07:55:23,967 ERROR hermes_state: FATAL: state.db was replaced underneath the gateway; refusing further writes to this file. " +
        "Divert transcripts to sessions/<id>.jsonl (and the gateway pending_messages spool) and restore or reopen after operator intervention."
    private fun info(time: String) = "2026-11-13 $time,001 INFO gateway.run: inbound message"

    @Test fun anErrorAsRecentAsTheLogsLatestActivityIsReportedAsItsFirstSentence() {
        assertEquals("FATAL: state.db was replaced underneath the gateway; refusing further writes to this file.",
            recentServerError(errors = listOf(fatal), activity = listOf(info("07:55:25"))))
    }

    @Test fun anErrorWithinTheWindowBeforeTheLatestActivityStillCounts() {
        assertEquals("FATAL: state.db was replaced underneath the gateway; refusing further writes to this file.",
            recentServerError(errors = listOf(fatal), activity = listOf(info("08:05:00"))))
    }

    @Test fun anErrorOlderThanTheWindowBeforeTheLatestActivityIsHistoryNotAWarning() {
        assertEquals("", recentServerError(errors = listOf(fatal), activity = listOf(info("08:06:30"))))
    }

    @Test fun anErrorFromAnEarlierDayIsNeverRecentEvenAtTheSameClockTime() {
        assertEquals("", recentServerError(errors = listOf(fatal), activity = listOf("2026-11-14 07:55:25,001 INFO gateway.run: inbound message")))
    }

    @Test fun theNewestStampedErrorWinsAndATracebackUnderItIsSkipped() {
        val older = "2026-11-13 07:00:01,100 ERROR gateway.run: Older failure. More words."
        val newer = "2026-11-13 07:55:01,100 CRITICAL agent.loop: The model call failed. Retrying."
        val frames = listOf("Traceback (most recent call last):", "  File \"x.py\", line 1, in run", "ValueError: boom")
        assertEquals("The model call failed.", recentServerError(errors = listOf(older, newer) + frames, activity = listOf(info("07:56:00"))))
    }

    @Test fun aWarningIsNotAnError() {
        assertEquals("", recentServerError(errors = listOf("2026-11-13 07:55:01,100 WARNING gateway.run: Slow reply"), activity = listOf(info("07:56:00"))))
    }

    @Test fun withNoLogActivityToCompareAgainstTheAppSaysNothingRatherThanGuess() {
        assertEquals("", recentServerError(errors = listOf(fatal), activity = emptyList()))
        assertEquals("", recentServerError(errors = emptyList(), activity = listOf(info("07:56:00"))))
        assertEquals("", recentServerError(errors = listOf("not a log line"), activity = listOf("also not")))
    }

    /** Review R25 Should 2: a dropped Telegram poll or one failed tool is a routine ERROR line; it stays in Logs and never changes the verdict. */
    @Test fun aRoutineErrorLineIsLeftToLogs() {
        for (line in listOf("2026-11-13 07:55:01,100 ERROR telegram.ext.Updater: Exception happened while polling for updates.",
            "2026-11-13 07:55:01,100 ERROR [local-${"a".repeat(64)}] tools.mcp_tool: Tool call failed. Retrying."))
            assertEquals(line, "", recentServerError(errors = listOf(line), activity = listOf(info("07:56:00"))))
    }

    @Test fun aRoutineErrorAfterARefusedWriteDoesNotHideIt() {
        val blip = "2026-11-13 07:58:00,001 ERROR telegram.ext.Updater: Network error. Reconnecting."
        assertEquals("FATAL: state.db was replaced underneath the gateway; refusing further writes to this file.",
            recentServerError(errors = listOf(fatal, blip), activity = listOf(info("07:59:00"))))
    }

    /** Hermes writes a session tag between the level and the logger (hermes_logging.py `_LOG_FORMAT`); the row shows neither. */
    @Test fun aSessionTagBeforeTheLoggerIsNotShown() {
        val tagged = "2026-11-13 07:55:23,967 ERROR [local-${"0f".repeat(32)}] hermes_state: FATAL: session storage stopped writing because another Hermes process still holds a deleted state.db-wal. Nothing is lost."
        assertEquals("FATAL: session storage stopped writing because another Hermes process still holds a deleted state.db-wal.",
            recentServerError(errors = listOf(tagged), activity = listOf(info("07:55:30"))))
        assertEquals("The model call failed.", recentServerError(errors = listOf("2026-11-13 07:55:23,967 CRITICAL [cron_1] agent.loop: The model call failed. Retrying."), activity = listOf(info("07:55:30"))))
    }

    /** Review R26 Should 2: Hermes logs a refused write once per process and refuses every later write silently (hermes_state.py:1278-1289, :1321-1327). */
    private fun gatewayStart(time: String, day: String = "2026-11-13") = "$day $time,100 INFO gateway.run: Gateway housekeeping started (interval=60s)"

    @Test fun aRefusedWriteStaysCurrentUntilTheGatewayRestarts() {
        // 35 minutes of later activity, and the last gateway start is from before the refused write: still refused.
        assertEquals("FATAL: state.db was replaced underneath the gateway; refusing further writes to this file.",
            recentServerError(errors = listOf(fatal), activity = listOf(info("08:30:00")), gatewayStarts = listOf(gatewayStart("04:14:30"))))
        assertEquals("FATAL: state.db was replaced underneath the gateway; refusing further writes to this file.",
            recentServerError(errors = listOf(fatal), activity = listOf("2026-11-14 09:00:00,001 INFO cron.scheduler: tick"), gatewayStarts = listOf(gatewayStart("07:55:23"))))
    }

    @Test fun aGatewayStartAfterTheRefusedWriteClearsIt() {
        assertEquals("", recentServerError(errors = listOf(fatal), activity = listOf(info("07:56:00")), gatewayStarts = listOf(gatewayStart("07:55:40"))))
        assertEquals("", recentServerError(errors = listOf(fatal), activity = listOf(info("08:30:00")), gatewayStarts = listOf(gatewayStart("08:01:00"))))
    }

    @Test fun withNoGatewayStartInViewTheTenMinuteWindowStillDecides() {
        assertEquals("", recentServerError(errors = listOf(fatal), activity = listOf(info("08:30:00")), gatewayStarts = listOf("not a log line")))
        assertEquals("FATAL: state.db was replaced underneath the gateway; refusing further writes to this file.",
            recentServerError(errors = listOf(fatal), activity = listOf(info("08:00:00")), gatewayStarts = emptyList()))
    }

    @Test fun aCriticalLineKeepsTheTenMinuteWindow() {
        val critical = "2026-11-13 07:55:01,100 CRITICAL agent.loop: The model call failed. Retrying."
        assertEquals("", recentServerError(errors = listOf(critical), activity = listOf(info("08:30:00")), gatewayStarts = listOf(gatewayStart("04:14:30"))))
    }

    /**
     * Review R27 Should 1: on a busy Hermes, routine errors with tracebacks push the once-logged refused write out of the 100-line tail within hours
     * (hermes_cli/logs.py:138-153, 249-256), so it is read on its own with Hermes' search.
     */
    @Test fun aRefusedWriteBehindAHundredTracebackLinesIsStillRead() = kotlinx.coroutines.runBlocking {
        val tail = (0 until 20).flatMap { i -> listOf("2026-11-13 ${"%02d".format(8 + i / 6)}:${"%02d".format(i % 6 * 10)}:00,100 ERROR telegram.ext.Updater: Network error. Reconnecting.",
            "Traceback (most recent call last):", "  File \"x.py\", line 1, in poll", "    raise NetworkError()", "telegram.error.NetworkError: timed out") }
        assertEquals(100, tail.size)
        val queries = mutableListOf<Map<String, String>>()
        val shown = readRecentServerError { query ->
            queries += query
            when {
                query["search"] == "FATAL:" -> listOf(fatal)
                query["search"] == GATEWAY_START_LINE -> listOf(gatewayStart("04:14:30"))
                query["file"] == "agent" -> listOf(info("11:30:00"))
                else -> tail
            }
        }
        assertEquals("FATAL: state.db was replaced underneath the gateway; refusing further writes to this file.", shown)
        assertTrue(mapOf("file" to "errors", "level" to "ERROR", "search" to "FATAL:", "lines" to "20") in queries)
    }

    @Test fun aFailedSearchLeavesTheTailToDecide() = kotlinx.coroutines.runBlocking {
        val critical = "2026-11-13 07:55:01,100 CRITICAL agent.loop: The model call failed. Retrying."
        val shown = readRecentServerError { query ->
            when {
                query.containsKey("search") -> throw java.io.IOException("Synthetic search failure")
                query["file"] == "agent" -> listOf(info("07:56:00"))
                else -> listOf(critical)
            }
        }
        assertEquals("The model call failed.", shown)
    }

    /** Review R27 Nice 2: the newest alarm that is still current wins, so a later CRITICAL line that aged out cannot hide the refused write behind it. */
    @Test fun aLaterCriticalLineDoesNotHideTheRefusedWriteOnceItAgesOut() {
        val critical = "2026-11-13 08:00:00,100 CRITICAL agent.loop: The model call failed. Retrying."
        assertEquals("FATAL: state.db was replaced underneath the gateway; refusing further writes to this file.",
            recentServerError(errors = listOf(fatal, critical), activity = listOf(info("08:30:00")), gatewayStarts = listOf(gatewayStart("04:14:30"))))
        assertEquals("The model call failed.", recentServerError(errors = listOf(fatal, critical), activity = listOf(info("08:05:00")), gatewayStarts = listOf(gatewayStart("04:14:30"))))
        assertEquals("", recentServerError(errors = listOf(fatal, critical), activity = listOf(info("08:30:00")), gatewayStarts = listOf(gatewayStart("07:58:00"))))
    }

    @Test fun aVeryLongMessageIsCutToOneReadableLine() {
        val long = "2026-11-13 07:55:23,967 CRITICAL x: " + "word ".repeat(80)
        assertEquals(true, recentServerError(errors = listOf(long), activity = listOf(info("07:55:30"))).length <= 160)
    }

    @Test fun aHealthyVerdictWithARecentErrorReadsAsNeedsAttentionOnThePageAndTheHub() {
        val status = JSONObject("""{"version":"0.23.0","overall":"ok","gateway_state":"running","active_agents":0}""")
        val state = parseServerStatus(status, recentError = "FATAL: state.db was replaced underneath the gateway; refusing further writes to this file.")
        assertEquals("degraded", state.overall)
        assertEquals(SettingsStatus.Attention, serverHealthStatus(state.overall, loading = false))
        assertEquals("FATAL: state.db was replaced underneath the gateway; refusing further writes to this file.", state.recentError)
    }

    @Test fun withoutARecentErrorHermesOwnVerdictIsKeptAsItIs() {
        val ok = parseServerStatus(JSONObject("""{"overall":"ok"}"""))
        assertEquals("ok", ok.overall); assertEquals("", ok.recentError)
        assertEquals("degraded", parseServerStatus(JSONObject("""{"overall":"degraded"}"""), recentError = "x").overall)
        assertEquals("a failed read is not made healthy or degraded by the log", "", parseServerStatus(JSONObject("""{"overall":""}"""), recentError = "x").overall)
    }
}
