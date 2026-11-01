package dev.hermes.android.updates

import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** What the phone does with the plugin's dashboard-restart routes: a missing route is the old plugin, an outage is "reconnecting", only "completed" is done. */
class DashboardRestartEndpointTest {
    private val restart = "/api/plugins/beacon/admin/dashboard/restart"
    private val status = "/api/plugins/beacon/admin/dashboard/restart/status"
    private val id = "a".repeat(32)

    /** A scripted reply: a status code and body, or an outage (the connection fails). */
    private sealed interface Reply {
        data class Answer(val code: Int, val body: String) : Reply
        data object Outage : Reply
    }
    private fun ok(body: String) = Reply.Answer(200, body)

    private fun withEndpoint(requests: MutableList<String>, answer: (path: String, call: Int) -> Reply, block: suspend (HermesPluginUpdater) -> Unit) {
        val counts = mutableMapOf<String, Int>()
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                requests += request.method + " " + path + (request.url.queryParameter("id")?.let { "?id=$it" } ?: "")
                val call = counts.merge(path, 1, Int::plus)!!
                when (val reply = answer(path, call)) {
                    is Reply.Outage -> throw IOException("Connection refused")
                    is Reply.Answer -> Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.code).message("Fixture")
                        .body(reply.body.toResponseBody("application/json".toMediaType())).build()
                }
            }
        }
        try { runBlocking { block(HermesPluginUpdater(client).also { it.pause = {} }) } } finally { client.closeNative() }
    }

    private fun restartWith(requests: MutableList<String> = mutableListOf(), reconnecting: MutableList<Boolean> = mutableListOf(), answer: (String, Int) -> Reply): DashboardRestartResult {
        var result: DashboardRestartResult? = null
        withEndpoint(requests, answer) { result = it.restartDashboard { value -> reconnecting += value } }
        return result!!
    }

    @Test(timeout = 5000) fun aPluginWithoutTheRouteIsAnOlderPluginAndLeavesTheRestartToTheOwner() {
        for (code in listOf(404, 405)) assertEquals(DashboardRestartResult.Unsupported, restartWith { _, _ -> Reply.Answer(code, "{}") })
    }

    @Test(timeout = 5000) fun aCallInProgressAndAnyOtherRefusalAreTold() {
        assertEquals(DashboardRestartResult.Call, restartWith { _, _ -> Reply.Answer(409, "{}") })
        for (code in listOf(401, 403, 500, 503)) assertEquals(code.toString(), DashboardRestartResult.Failed, restartWith { _, _ -> Reply.Answer(code, "{}") })
        assertEquals(DashboardRestartResult.Failed, restartWith { _, _ -> Reply.Outage })
    }

    @Test(timeout = 5000) fun aBusyHermesWaitsAndAMachineThatCannotRestartIsUnsupportedWithoutPolling() {
        val requests = mutableListOf<String>()
        assertEquals(DashboardRestartResult.Waiting, restartWith(requests) { _, _ -> ok("""{"restart_started":false,"restart_error_code":"live_sessions"}""") })
        assertEquals(DashboardRestartResult.Unsupported, restartWith(requests) { _, _ -> ok("""{"restart_started":false,"restart_error_code":"unsupported"}""") })
        assertEquals(listOf("POST $restart", "POST $restart"), requests)
    }

    @Test(timeout = 5000) fun aRestartThatNeitherStartedNorCanBeJoinedFails() {
        assertEquals(DashboardRestartResult.Failed, restartWith { _, _ -> ok("""{"restart_started":false}""") })
        assertEquals(DashboardRestartResult.Failed, restartWith { _, _ -> ok("""{"restart_started":true}""") })
    }

    @Test(timeout = 5000) fun theOutageOfTheDashboardIsReconnectingAndOnlyCompletedIsDone() {
        val requests = mutableListOf<String>()
        val reconnecting = mutableListOf<Boolean>()
        val result = restartWith(requests, reconnecting) { path, call ->
            when {
                path == restart -> ok("""{"restart_started":true,"restart_id":"$id"}""")
                call <= 2 -> Reply.Outage                                                    // the dashboard is down
                call == 3 -> ok("""{"id":"$id","state":"running","step":"verifying"}""")      // the new one is up, still checking
                else -> ok("""{"id":"$id","state":"completed","step":"done","mode":"relaunched"}""")
            }
        }
        assertEquals(DashboardRestartResult.Completed, result)
        assertEquals(listOf(true, true, false, false), reconnecting)
        assertEquals(listOf("POST $restart") + List(4) { "GET $status?id=$id" }, requests)
    }

    @Test(timeout = 5000) fun aRestartAlreadyRunningIsJoinedNotStartedTwice() {
        val requests = mutableListOf<String>()
        val result = restartWith(requests) { path, _ ->
            if (path == restart) ok("""{"restart_started":false,"restart_error_code":"in_progress","restart_id":"$id"}""")
            else ok("""{"id":"$id","state":"completed"}""")
        }
        assertEquals(DashboardRestartResult.Completed, result)
        assertEquals(listOf("POST $restart", "GET $status?id=$id"), requests)
    }

    @Test(timeout = 5000) fun aFailedHelperIsAFailureAndABusyOneWaits() {
        fun finish(state: String) = restartWith { path, _ ->
            if (path == restart) ok("""{"restart_started":true,"restart_id":"$id"}""") else ok(state)
        }
        assertEquals(DashboardRestartResult.Failed, finish("""{"state":"failed","reason":"not_back"}"""))
        assertEquals(DashboardRestartResult.Failed, finish("""{"state":"failed","reason":"lost"}"""))
        assertEquals(DashboardRestartResult.Waiting, finish("""{"state":"failed","reason":"live_sessions"}"""))
        // A dashboard that no longer knows this restart cannot vouch for it.
        assertEquals(DashboardRestartResult.Failed, finish("""{"id":"$id","state":"unknown"}"""))
    }

    @Test(timeout = 8000) fun aDashboardThatNeverComesBackIsAFailureAfterTheWaitNotAHang() {
        val requests = mutableListOf<String>()
        val reconnecting = mutableListOf<Boolean>()
        val result = restartWith(requests, reconnecting) { path, _ -> if (path == restart) ok("""{"restart_started":true,"restart_id":"$id"}""") else Reply.Outage }
        assertEquals(DashboardRestartResult.Failed, result)
        assertEquals(150, requests.count { it.startsWith("GET") })
        assertTrue(reconnecting.isNotEmpty() && reconnecting.all { it })
    }

    @Test(timeout = 5000) fun onlyTheTwoDashboardRoutesAreEverCalledAndNeverTheForceEndpoints() {
        val requests = mutableListOf<String>()
        restartWith(requests) { path, _ -> if (path == restart) ok("""{"restart_started":true,"restart_id":"$id"}""") else ok("""{"state":"completed"}""") }
        assertTrue(requests.all { it.substringAfter(' ').substringBefore('?') in setOf(restart, status) })
        assertFalse(requests.any { it.contains("/api/gateway/restart") || it.contains("interrupt") })
    }
}
