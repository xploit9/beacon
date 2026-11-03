package dev.hermes.android.uiux.updates

import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.updates.AppUpdateUi
import dev.hermes.android.updates.HermesPluginUpdater
import dev.hermes.android.updates.PluginRow
import dev.hermes.android.updates.PluginStatus
import dev.hermes.android.updates.PluginVersions
import dev.hermes.android.updates.RestartMemo
import dev.hermes.android.updates.RestartResult
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the phone does with Hermes' restart answers: the reason is kept, a restart in flight is polled before another is asked for, and the pid is remembered. */
class RestartHonestyTest {
    private class Answer(val code: Int, val body: String)

    private val restart = "/api/plugins/beacon/admin/plugin/restart"
    private val status = "/api/plugins/beacon/admin/plugin/restart/status"
    private val now = 1_000_000L

    private fun withClient(calls: MutableList<String>, handler: (String, String?) -> Answer, block: suspend (HermesPluginUpdater) -> Unit) {
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                // The client adds its profile to plugin routes; only the method, the path and the polled pid matter here.
                calls += request.method + " " + request.url.encodedPath + (request.url.queryParameter("pid")?.let { "?pid=$it" } ?: "")
                val answer = handler(request.url.encodedPath, request.url.query)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(answer.code).message("Fixture")
                    .body(answer.body.toResponseBody("application/json".toMediaType())).build()
            }
        }
        try { runBlocking { block(HermesPluginUpdater(client) { now }) } } finally { client.closeNative() }
    }

    @Test(timeout = 5000) fun theVersionRouteGivesTheGatewayVersionOrNothing() {
        fun read(body: String): PluginVersions {
            var versions: PluginVersions? = null
            withClient(mutableListOf(), { _, _ -> Answer(200, body) }) { versions = it.version() }
            return versions!!
        }
        assertEquals(PluginVersions("1.1.3", "1.1.3", "1.1.2"), read("""{"running":"1.1.3","installed":"1.1.3","gateway":"1.1.2"}"""))
        // An older plugin sends no gateway; Hermes that cannot tell sends "" or null; anything that is not a version is not one.
        assertEquals(PluginVersions("1.1.3", "1.1.3", ""), read("""{"running":"1.1.3","installed":"1.1.3"}"""))
        assertEquals("", read("""{"running":"1.1.3","installed":"1.1.3","gateway":""}""").gateway)
        assertEquals("", read("""{"running":"1.1.3","installed":"1.1.3","gateway":null}""").gateway)
        assertEquals("", read("""{"running":"1.1.3","installed":"1.1.3","gateway":"main"}""").gateway)
    }

    @Test(timeout = 5000) fun everyRefusalReasonSurvivesInsteadOfCollapsingIntoFalse() {
        val expected = mapOf("owner_call" to RestartResult.Call, "failed" to RestartResult.Failed, "" to RestartResult.Failed, "other" to RestartResult.Failed)
        expected.forEach { (code, result) ->
            val calls = mutableListOf<String>()
            val body = if (code.isEmpty()) """{"restart_started":false}""" else """{"restart_started":false,"restart_error_code":"$code"}"""
            withClient(calls, { path, _ -> if (path == restart) Answer(200, body) else error("no status poll for a refusal") }) {
                assertEquals(code, result, it.restart())
            }
            assertEquals(listOf("POST $restart"), calls)
        }
    }

    @Test(timeout = 5000) fun anOwnerCallRefusalIsA409AndMapsToCall() {
        val calls = mutableListOf<String>()
        withClient(calls, { _, _ -> Answer(409, """{"detail":"call"}""") }) { assertEquals(RestartResult.Call, it.restart()) }
        assertEquals(listOf("POST $restart"), calls)
    }

    @Test(timeout = 8000) fun theStartedPidIsRememberedBeforeWaitingAndMarkedDoneWhenItFinishes() {
        val remembered = mutableListOf<RestartMemo>()
        withClient(mutableListOf(), { path, _ ->
            when (path) {
                restart -> Answer(200, """{"restart_started":true,"restart_pid":321}""")
                status -> Answer(200, """{"pid":321,"completed":true}""")
                "/api/status" -> Answer(200, """{"gateway_running":true,"gateway_state":"running"}""")
                else -> error(path)
            }
        }) { assertEquals(RestartResult.Completed, it.restart(RestartMemo(), remembered::add)) }
        assertEquals(listOf(RestartMemo(321, now), RestartMemo(321, now, completed = true)), remembered)
    }

    @Test(timeout = 8000) fun aRestartStillInFlightIsPolledBeforeAnotherIsAskedFor() {
        val calls = mutableListOf<String>()
        val remembered = mutableListOf<RestartMemo>()
        val last = RestartMemo(55, now - 20_000)
        withClient(calls, { path, _ ->
            when (path) {
                status -> Answer(200, """{"pid":55,"completed":true}""")
                "/api/status" -> Answer(200, """{"gateway_running":true,"gateway_state":"running"}""")
                else -> error("a restart in flight must not be asked for again: $path")
            }
        }) { assertEquals(RestartResult.Completed, it.restart(last, remembered::add)) }
        assertFalse(calls.any { it.startsWith("POST") })
        assertTrue(calls.first().endsWith("$status?pid=55"))
        assertEquals(listOf(last.copy(completed = true)), remembered)
    }

    @Test(timeout = 8000) fun aRestartThatFailedOrWasReplacedIsClearedAndAskedForAgain() {
        val calls = mutableListOf<String>()
        val remembered = mutableListOf<RestartMemo>()
        withClient(calls, { path, _ ->
            when (path) {
                status -> Answer(200, """{"pid":55,"failed":true}""")
                restart -> Answer(200, """{"restart_started":false,"restart_error_code":"failed"}""")
                else -> error(path)
            }
        }) { assertEquals(RestartResult.Failed, it.restart(RestartMemo(55, now - 1_000), remembered::add)) }
        assertEquals(listOf(RestartMemo()), remembered)
        assertEquals(listOf("GET $status?pid=55", "POST $restart"), calls)
    }

    @Test(timeout = 5000) fun anOldPidIsHistoryNotARestartInFlight() {
        val calls = mutableListOf<String>()
        withClient(calls, { path, _ -> if (path == restart) Answer(200, """{"restart_started":false,"restart_error_code":"failed"}""") else error(path) }) {
            assertEquals(RestartResult.Failed, it.restart(RestartMemo(55, now - RestartMemo.RESTART_MEMORY_MS - 1)))
        }
        assertEquals(listOf("POST $restart"), calls)
    }

    // Owner, 3 Nov: an update asked for from the phone restarts Hermes whatever it is doing. A plugin from before 1.1.27 still answers
    // "busy", so the phone asks Hermes for its own forced restart and waits on that one like any other.
    @Test(timeout = 8000) fun aBusyRefusalGoesToHermesOwnForcedRestart() {
        val calls = mutableListOf<String>()
        val remembered = mutableListOf<RestartMemo>()
        withClient(calls, { path, _ ->
            when (path) {
                restart -> Answer(200, """{"restart_started":false,"restart_error_code":"live_sessions"}""")
                "/api/gateway/restart" -> Answer(200, """{"ok":true,"pid":909,"name":"gateway-restart"}""")
                status -> Answer(200, """{"pid":909,"completed":true}""")
                "/api/status" -> Answer(200, """{"gateway_running":true,"gateway_state":"running"}""")
                else -> error(path)
            }
        }) { assertEquals(RestartResult.Completed, it.restart(RestartMemo(), remembered::add)) }
        assertEquals(listOf("POST $restart", "POST /api/gateway/restart"), calls.filter { it.startsWith("POST") })
        assertEquals(listOf(RestartMemo(909, now), RestartMemo(909, now, completed = true)), remembered)
    }

    @Test(timeout = 5000) fun aForcedRestartHermesDidNotStartIsAFailureNotAWait() {
        withClient(mutableListOf(), { path, _ ->
            when (path) {
                restart -> Answer(200, """{"restart_started":false,"restart_error_code":"live_sessions"}""")
                "/api/gateway/restart" -> Answer(500, """{"detail":"Failed to restart gateway"}""")
                else -> error(path)
            }
        }) { assertEquals(RestartResult.Failed, it.restart()) }
    }

    @Test(timeout = 8000) fun aStalePidMeansTheEarlierRestartIsTheOneToWaitFor() {
        val calls = mutableListOf<String>()
        withClient(calls, { path, _ ->
            when (path) {
                restart -> Answer(200, """{"restart_started":false,"restart_pid":77,"restart_error_code":"stale"}""")
                status -> Answer(200, """{"pid":77,"completed":true}""")
                "/api/status" -> Answer(200, """{"gateway_running":true,"gateway_state":"running"}""")
                else -> error(path)
            }
        }) { assertEquals(RestartResult.Completed, it.restart()) }
        assertEquals(1, calls.count { it.startsWith("POST") })
        // With no pid at all there is nothing to wait on: not a failure, the next tap checks again.
        withClient(mutableListOf(), { _, _ -> Answer(200, """{"restart_started":false,"restart_error_code":"stale"}""") }) { assertEquals(RestartResult.Pending, it.restart()) }
    }

    @Test fun theHubCanReadWhetherAPluginActionIsPending() {
        assertFalse(AppUpdateUi().pluginPending)
        assertFalse(AppUpdateUi(plugin = PluginRow(PluginStatus.UpToDate, "1.1.3", "1.1.3")).pluginPending)
        listOf(PluginStatus.Available, PluginStatus.NeedsReview, PluginStatus.Restart, PluginStatus.Waiting, PluginStatus.RestartFailed,
            PluginStatus.DashboardRestart, PluginStatus.Newer, PluginStatus.Failed, PluginStatus.Busy).forEach {
            assertTrue(it.name, AppUpdateUi(plugin = PluginRow(it, "1.1.1", "1.1.3")).pluginPending)
        }
    }
}
