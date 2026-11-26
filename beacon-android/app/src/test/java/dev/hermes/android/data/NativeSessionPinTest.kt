package dev.hermes.android.data

import dev.hermes.android.core.json
import dev.hermes.android.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSessionPinTest {
    private class Fixture(var patchStatus: Int = 200, val canonical: Boolean = false, val resumeRefusal: String? = null,
                          val pluginStatus: Int = 200, val keyed: Boolean = true) {
        val requests = mutableListOf<Pair<Request, JSONObject>>()
        val pluginRequests = mutableListOf<Pair<Request, JSONObject>>()
        val bootstrapRequests = mutableListOf<Request>()
        val wire = NativeOperationsTest.Fixture(canonical = canonical, profile = "work").apply {
            resumeRefusal?.let { refusal -> onRpc = { method, _ -> if (method == "session.resume") json("error" to json("code" to 4001, "message" to refusal)) else null } }
        }
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
                if (path == LEGACY_SESSION_PIN_PATH) {
                    pluginRequests += request to body
                    val payload = if (pluginStatus == 200) json("session_id" to body.string("session_id"), "pinned" to body.getBoolean("pinned"))
                        else json("detail" to "Hermes kept this chat read-only")
                    return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(pluginStatus).message("Fixture")
                        .body(payload.toString().toResponseBody("application/json".toMediaType())).build()
                }
                if (path == "/api/sessions/chat-a" && request.method == "GET") {
                    requests += request to body
                    // A keyed cli row. In the demo store the adopted ones also hold a canonical
                    // gateway.local_policy.v1:<id> receipt in state_meta, which the phone cannot see.
                    val payload = if (keyed) json("id" to "chat-a", "profile" to "work", "source" to "cli",
                        "session_key" to "agent:main:local:dm:local-a", "chat_id" to "local-a",
                        "origin_json" to "{\"platform\":\"local\"}")
                    else json("id" to "chat-a", "profile" to "work", "source" to "cli")
                    return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
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
        val fixture = Fixture(keyed = false)
        try {
            val result = fixture.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to true))
            assertTrue(result.getBoolean("pinned"))
            // The removed pre-check used to add a leading session-record GET; the write itself is unchanged.
            val write = fixture.requests.dropWhile { it.first.url.encodedPath == "/api/sessions/chat-a" }
            assertEquals(listOf("GET", "PATCH"), write.map { it.first.method })
            assertTrue(fixture.requests.none { it.first.url.encodedPath.startsWith("/api/plugins/beacon/") })
            assertEquals("/api/sessions/chat-a/mutation-snapshot", write.first().first.url.encodedPath)
            assertEquals("/api/sessions/chat-a", write.last().first.url.encodedPath)
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
        val fixture = Fixture(keyed = false)
        try {
            val result = fixture.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to false))
            assertFalse(result.getBoolean("pinned"))
            assertFalse(fixture.requests.last().second.getBoolean("pinned"))
            fixture.assertBootstrap()
        } finally { fixture.close() }
    }

    @Test fun aTrulyLegacyKeyedRowTriesCanonicalFirstThenSucceedsThroughThePlugin() = runBlocking {
        val fixture = Fixture(canonical = true, resumeRefusal = "not_found")
        try {
            val result = fixture.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to false))
            assertFalse(result.getBoolean("pinned"))
            assertTrue("Canonical is asked before any fallback", fixture.wire.methods.contains("session.resume"))
            assertFalse("A refused adoption never reaches a canonical sidebar mutation", fixture.wire.methods.contains("session.mutate"))
            assertEquals(1, fixture.pluginRequests.size)
            val (request, body) = fixture.pluginRequests.single()
            assertEquals(LEGACY_SESSION_PIN_PATH, request.url.encodedPath)
            assertEquals("chat-a", body.string("session_id"))
            assertEquals("work", body.string("profile"))
            assertFalse(body.getBoolean("pinned"))
        } finally { fixture.close() }
    }

    /**
     * The demo store's keyed cli/local/desktop rows also hold a canonical gateway.local_policy.v1 receipt: they are
     * adopted history, so session.mutate is their writer and publishes session.updated (gateway/session_mutations.py:334).
     * The phone cannot see that receipt, which is why the route must ask canonical first instead of judging the row itself.
     */
    @Test fun aKeyedAdoptedRowPublishesTheCanonicalSidebarMutationNotThePlugin() = runBlocking {
        val fixture = Fixture(canonical = true)
        val updates = Channel<JSONObject>(Channel.UNLIMITED)
        val ready = CompletableDeferred<Unit>()
        var collector: Job? = null
        try {
            val result = fixture.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to true))
            assertTrue(result.getBoolean("pinned"))
            assertEquals("The row record is never read to guess the route", 0, fixture.requests.size)
            assertTrue(fixture.wire.methods.contains("session.resume"))
            val mutate = fixture.wire.calls.single { it.getString("method") == "session.mutate" }
            assertEquals("sidebar", mutate.getJSONObject("params").getString("operation"))
            assertTrue(mutate.getJSONObject("params").getJSONObject("payload").getBoolean("pinned"))
            assertEquals("An adopted row is never written behind canonical", 0, fixture.pluginRequests.size)
            // Hermes publishes session.updated from that mutation; the phone's own stream receives it.
            collector = launch { fixture.client.nativeEvents { frame ->
                when (frame.optJSONObject("params")?.optString("type")) {
                    "gateway.ready" -> ready.complete(Unit)
                    "session.updated" -> updates.send(frame)
                }
            } }
            withTimeout(10_000) { ready.await() }
            fixture.wire.emitEvent("stored-a", 9, "session.updated", json("pinned" to true))
            val frame = withTimeout(5_000) { updates.receive() }
            assertTrue(frame.getJSONObject("params").getJSONObject("payload").getBoolean("pinned"))
        } finally {
            collector?.cancelAndJoin()
            fixture.close()
        }
    }

    @Test fun withNoPluginRouteAnAdoptedRowStillPinsCanonicallyAndALegacyRowFailsHonestly() = runBlocking {
        val adopted = Fixture(canonical = true, pluginStatus = 404)
        try {
            val result = adopted.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to true))
            assertTrue(result.getBoolean("pinned"))
            assertEquals("An absent plugin route never decides an adopted row", 0, adopted.pluginRequests.size)
        } finally { adopted.close() }
        val legacy = Fixture(canonical = true, resumeRefusal = "not_found", pluginStatus = 404)
        try {
            val failure = runCatching { legacy.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to true)) }.exceptionOrNull()
            assertTrue(failure is ApiFailure && failure.status == 404)
            assertEquals(1, legacy.pluginRequests.size)
            assertEquals("This item is no longer available. Refresh and try again.",
                conversationActionFailure("/api/session/pin", failure as ApiFailure, pinned = true).message)
        } finally { legacy.close() }
    }

    @Test fun canonicalRefusalOtherThanNotFoundKeepsHermesReasonAndDoesNotFallBack() = runBlocking {
        val fixture = Fixture(canonical = true, resumeRefusal = "permission_denied", keyed = false)
        try {
            val failure = runCatching { fixture.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to false)) }.exceptionOrNull()
            assertTrue(failure is ApiFailure && failure.status == 4001 && failure.detail == "permission_denied")
            assertTrue(fixture.pluginRequests.isEmpty())
            assertEquals("Hermes didn't allow that.", pinFailureText(failure as ApiFailure))
        } finally { fixture.close() }
    }

    @Test fun aFailedLegacyStoreWriteIsNotReportedAsASuccessfulUnpin() = runBlocking {
        val fixture = Fixture(canonical = true, resumeRefusal = "not_found", pluginStatus = 404)
        try {
            val failure = runCatching { fixture.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to false)) }.exceptionOrNull()
            assertTrue(failure is ApiFailure && failure.status == 404)
            assertEquals(1, fixture.pluginRequests.size)
        } finally { fixture.close() }
    }

    @Test fun aRefusedPatchFailsOnceWithoutAnotherRoute() = runBlocking {
        for (status in listOf(403, 404, 409)) {
            val fixture = Fixture(status, keyed = false)
            try {
                val failure = runCatching { fixture.client.call("/api/session/pin", method = "POST", data = json("session_id" to "chat-a", "pinned" to true)) }.exceptionOrNull()
                assertTrue(failure is ApiFailure && failure.status == status)
                val write = fixture.requests.dropWhile { it.first.url.encodedPath == "/api/sessions/chat-a" }
                assertEquals(listOf("GET", "PATCH"), write.map { it.first.method })
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

    @Test fun pinRefusalsUseHermesOwnReasonAndSayUnpinWhenUnpinning() {
        assertEquals("Hermes didn't allow that.", conversationActionFailure("/api/session/pin", ApiFailure(4001, "permission_denied"), pinned = false).message)
        assertEquals("This item is no longer available. Refresh and try again.",
            conversationActionFailure("/api/session/pin", ApiFailure(4001, "not_found"), pinned = false).message)
        assertEquals("Couldn't unpin this chat. Refresh and try again.",
            conversationActionFailure("/api/session/pin", IllegalStateException("local comparison"), pinned = false).message)
        assertEquals("Updating…", conversationPendingLabel("/api/session/pin"))
    }
}
