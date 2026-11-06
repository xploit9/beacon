package dev.hermes.android.data

import dev.hermes.android.core.json
import dev.hermes.android.core.string
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSessionPinTest {
    private class Fixture(var pluginStatus: Int = 200, var pluginError: String = "Not Found") {
        val requests = mutableListOf<Pair<Request, JSONObject>>()
        val bootstrapRequests = mutableListOf<Request>()
        private val wire = NativeOperationsTest.Fixture(canonical = false)
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "work", backend = "hermes-dashboard"), null, wire) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer(); request.body?.writeTo(buffer)
                val body = buffer.readUtf8().takeIf(String::isNotBlank)?.let(::JSONObject) ?: json()
                val path = request.url.encodedPath
                if (path == CANONICAL_TICKET_PATH || path == "/api/auth/ws-ticket") {
                    bootstrapRequests += request
                    val canonical = path == CANONICAL_TICKET_PATH
                    val payload = if (canonical) json("detail" to "Not Found") else json("ticket" to "fixture-ticket")
                    return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(if (canonical) 404 else 200).message("Fixture")
                        .body(payload.toString().toResponseBody("application/json".toMediaType())).build()
                }
                requests += request to body
                val plugin = request.url.encodedPath == "/api/plugins/beacon/sessions/pin"
                val payload = when {
                    plugin && pluginStatus == 200 -> json("session_id" to body.string("session_id"), "pinned" to body.getBoolean("pinned"))
                    plugin -> json("detail" to pluginError)
                    request.url.encodedPath.endsWith("/mutation-snapshot") -> json("exists" to true, "runtime_revision" to 4, "runtime_generation" to 2)
                    else -> json("session_id" to "chat-a", "pinned" to body.getBoolean("pinned"))
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (plugin) pluginStatus else 200).message("Fixture")
                    .body(payload.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        fun assertBootstrap() {
            assertEquals(2, bootstrapRequests.size)
            assertEquals(listOf(CANONICAL_TICKET_PATH, "/api/auth/ws-ticket"), bootstrapRequests.map { it.url.encodedPath })
            assertEquals(listOf("POST", "POST"), bootstrapRequests.map { it.method })
            assertEquals(listOf("client.capabilities"), wire.methods)
        }
        fun close() { client.closeNative(); wire.client.closeNative() }
    }

    @Test fun pinUsesThePluginWithExplicitProfileAndNoMaintenancePatch() = runBlocking {
        val fixture = Fixture()
        try {
            val result = fixture.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to true))
            assertTrue(result.getBoolean("pinned"))
            val (request, body) = fixture.requests.single()
            assertEquals("/api/plugins/beacon/sessions/pin", request.url.encodedPath)
            assertEquals("POST", request.method)
            assertEquals("work", request.url.queryParameter("beacon_profile"))
            assertEquals("work", body.string("profile"))
            assertEquals("chat-a", body.string("session_id"))
            fixture.assertBootstrap()
        } finally { fixture.close() }
    }

    @Test fun missingPluginRouteKeepsTheRevisionCheckedStockPatch() = runBlocking {
        val fixture = Fixture(pluginStatus = 404)
        try {
            fixture.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to true))
            assertEquals(listOf("POST", "GET", "PATCH"), fixture.requests.map { it.first.method })
            val patch = fixture.requests.last().second
            assertEquals(4, patch.getInt("expected_revision"))
            assertEquals(2, patch.getInt("expected_generation"))
            assertTrue(patch.string("request_id").isNotBlank())
            assertEquals("work", patch.string("profile"))
            fixture.assertBootstrap()
        } finally { fixture.close() }
    }

    @Test fun ownerOrSessionDenialNeverFallsThroughToPatch() = runBlocking {
        for ((status, detail) in listOf(403 to "Owner not authorized", 404 to "Conversation not found.")) {
            val fixture = Fixture(status, detail)
            try {
                assertTrue(runCatching { fixture.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to true)) }.isFailure)
                assertEquals(1, fixture.requests.size)
                fixture.assertBootstrap()
            } finally { fixture.close() }
        }
    }

    @Test fun plugin401UsesTheRevisionCheckedNativePinWithoutChangingCredentials() = runBlocking {
        val fixture = Fixture(pluginStatus = 401, pluginError = "Pairing expired")
        try {
            val result = fixture.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to true))
            assertTrue(result.getBoolean("pinned"))
            assertEquals(listOf("POST", "GET", "PATCH"), fixture.requests.map { it.first.method })
            assertEquals(4, fixture.requests.last().second.getInt("expected_revision"))
            assertEquals(2, fixture.requests.last().second.getInt("expected_generation"))
            assertEquals("owner", fixture.client.auth.owner)
            fixture.assertBootstrap()
        } finally { fixture.close() }
    }

    @Test fun conversationMutationFailuresCannotExposeServerPaths() {
        val raw = ApiFailure(409, "Exclusive maintenance refused: private profile path")
        for (path in listOf("/api/session/pin", "/api/session/archive", "/api/session/rename")) {
            val safe = conversationActionFailure(path, raw)
            assertFalse(safe.message.orEmpty().contains("private profile path"))
            assertTrue(safe is ApiFailure && safe.status == 409)
        }
        val expired = NativeAuthExpiredException()
        val restarted = LoopbackSessionRefreshFailed()
        assertSame(expired, conversationActionFailure("/api/session/pin", expired))
        assertSame(restarted, conversationActionFailure("/api/session/archive", restarted))
    }
}
