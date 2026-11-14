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
    private class Fixture(var patchStatus: Int = 200) {
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
                val patch = request.method == "PATCH"
                val payload = when {
                    request.url.encodedPath.endsWith("/mutation-snapshot") -> json("exists" to true, "runtime_revision" to 4, "runtime_generation" to 2)
                    patch && patchStatus != 200 -> json("detail" to "Session changed in Hermes")
                    else -> json("session_id" to "chat-a", "pinned" to body.getBoolean("pinned"))
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (patch) patchStatus else 200).message("Fixture")
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

    @Test fun pinUsesHermesRevisionCheckedPatchAndNeverAsksThePlugin() = runBlocking {
        val fixture = Fixture()
        try {
            val result = fixture.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to true))
            assertTrue(result.getBoolean("pinned"))
            assertEquals(listOf("GET", "PATCH"), fixture.requests.map { it.first.method })
            assertTrue(fixture.requests.none { it.first.url.encodedPath.startsWith("/api/plugins/beacon/") })
            assertEquals("/api/sessions/chat-a/mutation-snapshot", fixture.requests.first().first.url.encodedPath)
            assertEquals("/api/sessions/chat-a", fixture.requests.last().first.url.encodedPath)
            val patch = fixture.requests.last().second
            assertEquals(4, patch.getInt("expected_revision"))
            assertEquals(2, patch.getInt("expected_generation"))
            assertTrue(patch.string("request_id").isNotBlank())
            assertEquals("work", patch.string("profile"))
            assertTrue(patch.getBoolean("pinned"))
            fixture.assertBootstrap()
        } finally { fixture.close() }
    }

    @Test fun unpinSendsTheSamePatchWithFalse() = runBlocking {
        val fixture = Fixture()
        try {
            val result = fixture.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to false))
            assertFalse(result.getBoolean("pinned"))
            assertFalse(fixture.requests.last().second.getBoolean("pinned"))
            fixture.assertBootstrap()
        } finally { fixture.close() }
    }

    @Test fun aRefusedPatchFailsOnceWithoutAnotherRoute() = runBlocking {
        for (status in listOf(403, 404, 409)) {
            val fixture = Fixture(status)
            try {
                val failure = runCatching { fixture.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to true)) }.exceptionOrNull()
                assertTrue(failure is ApiFailure && failure.status == status)
                assertEquals(listOf("GET", "PATCH"), fixture.requests.map { it.first.method })
                fixture.assertBootstrap()
            } finally { fixture.close() }
        }
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
