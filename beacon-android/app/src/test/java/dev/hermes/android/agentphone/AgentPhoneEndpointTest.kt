package dev.hermes.android.agentphone

import dev.hermes.android.data.AgentPhoneAction
import dev.hermes.android.data.AgentPhoneApi
import dev.hermes.android.data.AgentPhoneDriverState
import dev.hermes.android.data.AgentPhoneResult
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.ClientAgentPhoneEndpoint
import dev.hermes.android.data.NativeBearerRecord
import dev.hermes.android.data.valueOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real endpoint, through BeaconClient's own request path (the BeaconClientAuthTest pattern: a fixture interceptor answers as the
 * plugin would): the profile query, the methods and bodies, and the three answers whose refusal is a body with its own line.
 */
class AgentPhoneEndpointTest {
    private class Seen(val method: String, val path: String, val query: Map<String, String>, val body: String, val authorization: String?)

    private fun client(seen: MutableList<Seen>, answer: (String, String) -> Pair<Int, String>): BeaconClient {
        val origin = "https://hermes.example"
        val bearer = NativeBearerRecord("fixture-access", "fixture-refresh", 4_000_000_000L, origin, "alice", "default", "basic")
        val auth = AuthContext(origin, emptyList(), "alice", "default", backend = "hermes-dashboard", nativeProvider = "basic", bearer = bearer)
        return BeaconClient(auth, null, persistContext = false) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
                val url = request.url
                seen += Seen(request.method, url.encodedPath, url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() }, body, request.header("Authorization"))
                val (code, text) = answer(request.method, url.encodedPath.removePrefix("/api/plugins/beacon/mobile/agent-phone"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                    .body(text.toResponseBody("application/json".toMediaType())).build()
            }
        }
    }

    @Test fun everyRouteCarriesTheProfileAndItsOwnMethodAndBody() = runBlocking {
        val seen = mutableListOf<Seen>()
        val client = client(seen) { method, route ->
            when ("$method $route") {
                "GET " -> 200 to """{"installed": true, "profile": "default", "yours": true, "phone": "on", "install": {"state": "idle"}}"""
                "GET /activity" -> 200 to """{"activity": []}"""
                "POST /install" -> 202 to """{"state": "running"}"""
                "PUT /policy" -> 200 to """{"enabled": false, "ask": "payments_and_sends"}"""
                else -> 404 to """{"detail": "Not Found"}"""
            }
        }
        try {
            val api = AgentPhoneApi(ClientAgentPhoneEndpoint(client))
            assertTrue(api.status() is AgentPhoneResult.Done)
            assertTrue(api.activity() is AgentPhoneResult.Done)
            assertTrue(api.install(listOf("android-sdk-license"), continueWarnings = true) is AgentPhoneResult.Done)
            assertTrue(api.setPolicy(enabled = false) is AgentPhoneResult.Done)
            assertEquals(listOf("GET", "GET", "POST", "PUT"), seen.map { it.method })
            assertTrue(seen.all { it.query["beacon_profile"] == "default" })
            assertEquals("/api/plugins/beacon/mobile/agent-phone/activity", seen[1].path)
            assertEquals("50", seen[1].query["limit"])
            val install = org.json.JSONObject(seen[2].body)
            assertEquals(setOf("accept", "continue"), install.keys().asSequence().toSet())
            assertEquals("android-sdk-license", install.getJSONArray("accept").getString(0))
            assertEquals(true, install.getBoolean("continue"))
            assertEquals(setOf("enabled"), org.json.JSONObject(seen[3].body).keys().asSequence().toSet())
        } finally { client.closeNative() }
    }

    @Test fun startsRefusalKeepsTheLineFromItsBody() = runBlocking {
        val client = client(mutableListOf()) { _, _ -> 409 to """{"phone": "off", "line": "Turn on Hermes sign-in first."}""" }
        try {
            val refused = AgentPhoneApi(ClientAgentPhoneEndpoint(client)).start() as AgentPhoneResult.Refused
            assertEquals(409, refused.status)
            assertEquals("Turn on Hermes sign-in first.", refused.line.template)
            assertEquals(AgentPhoneAction.How, refused.line.action)
        } finally { client.closeNative() }
    }

    @Test fun phoneTasksUseTheAuthenticatedProfileAndCancelTheReturnedId() = runBlocking {
        val seen = mutableListOf<Seen>()
        val running = """{"id":"server-id","state":"running","steps":1}"""
        val client = client(seen) { method, route ->
            when ("$method $route") {
                "GET " -> 200 to """{"control_driver":"phonedriver","installed":true,"yours":true,"driver_task":$running}"""
                "POST /task" -> 202 to running
                "POST /task/server-id/cancel" -> 200 to running.replace("running", "cancelled")
                else -> 404 to """{"detail":"Not Found"}"""
            }
        }
        try {
            val api = AgentPhoneApi(ClientAgentPhoneEndpoint(client))
            val goal = "افتح الإعدادات"
            val requestId = "1795260000000-12345678-1234-4234-8234-123456789abc"
            val started = requireNotNull(api.giveTask(requestId, goal).valueOrNull())
            assertEquals(AgentPhoneDriverState.Running, started.state)
            assertEquals(started, api.status().valueOrNull()?.first?.driverTask)
            assertEquals(AgentPhoneDriverState.Cancelled, api.cancelTask(started.id).valueOrNull()?.state)
            assertEquals(listOf("POST", "GET", "POST"), seen.map { it.method })
            assertTrue(seen.all { it.query["beacon_profile"] == "default" && it.authorization == "Bearer fixture-access" })
            assertEquals("/api/plugins/beacon/mobile/agent-phone/task", seen[0].path)
            assertEquals("/api/plugins/beacon/mobile/agent-phone/task/server-id/cancel", seen[2].path)
            val request = JSONObject(seen[0].body)
            assertEquals(setOf("request_id", "task"), request.keys().asSequence().toSet())
            assertEquals(requestId, request.getString("request_id"))
            assertEquals(goal, request.getString("task"))
        } finally { client.closeNative() }
    }

    @Test fun termsThatCantBeFetchedAreNotTurnedIntoHermesIsBusy() = runBlocking {
        val client = client(mutableListOf()) { _, _ -> 502 to """{"line": "Download stopped. Check the Hermes computer's internet.", "fix": null, "retry": true}""" }
        try {
            val refused = AgentPhoneApi(ClientAgentPhoneEndpoint(client)).terms() as AgentPhoneResult.Refused
            assertEquals(502, refused.status)
            assertEquals("Download stopped. Check the Hermes computer's internet.", refused.line.template)
            assertEquals(AgentPhoneAction.TryAgain, refused.line.action)
        } finally { client.closeNative() }
    }

    @Test fun oldChecksAtInstallAskToCheckAgain() = runBlocking {
        val client = client(mutableListOf()) { _, _ -> 409 to """{"detail": "Check this computer first."}""" }
        try {
            val refused = AgentPhoneApi(ClientAgentPhoneEndpoint(client)).install(listOf("x"), false) as AgentPhoneResult.Refused
            assertEquals(AgentPhoneAction.CheckAgain, refused.line.action)
        } finally { client.closeNative() }
    }

    @Test fun anotherRoutesRefusalKeepsFastApisDetail() = runBlocking {
        val client = client(mutableListOf()) { _, _ -> 503 to """{"detail": "Update in progress"}""" }
        try {
            val refused = AgentPhoneApi(ClientAgentPhoneEndpoint(client)).stop() as AgentPhoneResult.Refused
            assertEquals(503, refused.status)
            assertEquals("Hermes is updating. Try again in a minute.", refused.line.template)
        } finally { client.closeNative() }
    }
}
