package dev.hermes.android.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

class NativeOperationPolicyTest {
    @Test fun encodedToolsetNamesStillCannotEscapeTheHttpPathBoundary() {
        val client = CypherClient(AuthContext("https://native.example", emptyList()))
        assertEquals("/api/tools/toolsets/mcp.research", client.url("/api/tools/toolsets/mcp.research").encodedPath)
        for (path in listOf("/api/tools/toolsets/%2e%2e", "/api/tools/toolsets/x%2F..%2Fauth", "/api/tools/toolsets/x%3Fsecret=1", "/api/tools/toolsets/%00")) {
            assertTrue(path, runCatching { client.url(path) }.isFailure)
        }
    }

    @Test fun supportedNativeHistoryAndProfileRoutesAreAllowed() {
        for (path in listOf(
            "/api/sessions",
            "/api/sessions/search",
            "/api/sessions/20261014_125947_0d8269",
            "/api/sessions/20261014_125947_0d8269/messages",
            "/api/sessions/20261014_125947_0d8269/timeline",
            "/api/sessions/20261014_125947_0d8269/latest-descendant",
            "/api/profiles",
            "/api/profiles/active",
            "/api/auth/me",
            "/api/auth/ws-ticket",
        )) assertTrue(path, NativeOperationPolicy.direct(path, "GET"))

        for (path in listOf(
            "/api/sessions/x/delete",
            "/api/sessions/..",
            "/api/sessions/x/../messages",
            "/api/sessions/x/messages/../../auth/me",
            "/api/sessions/x/latest-descendant/extra",
            "/api/sessions/x/descendants",
            "/api/plugins/cypherx/mobile/bootstrap",
            "/api/mobile/bootstrap",
        )) assertFalse(path, NativeOperationPolicy.direct(path, "GET"))

        val client = CypherClient(AuthContext("https://native.example", emptyList()))
        assertTrue(runCatching { client.url("/api/sessions/%2e%2e") }.isFailure)
        assertTrue(runCatching { client.url("/api/sessions/x%2F..%2Fauth") }.isFailure)
    }

    @Test fun sessionRoutesAreReadOnlyHttpGet() {
        for (path in listOf(
            "/api/sessions",
            "/api/sessions/search",
            "/api/sessions/session-a",
            "/api/sessions/session-a/messages",
            "/api/sessions/session-a/timeline",
            "/api/sessions/session-a/latest-descendant",
        )) {
            assertTrue("GET $path", NativeOperationPolicy.direct(path, "GET"))
            for (method in listOf("POST", "PUT", "PATCH", "DELETE", "get")) {
                assertFalse("$method $path", NativeOperationPolicy.direct(path, method))
            }
        }
        assertTrue("Other allowlisted routes retain their methods", NativeOperationPolicy.direct("/api/model/set", "POST"))
    }

    @Test fun clientBoundariesRejectSessionMutationsBeforeTransport() = runBlocking<Unit> {
        val requests = AtomicInteger()
        val client = CypherClient(AuthContext("https://native.example", emptyList()), null) { builder ->
            builder.addInterceptor { chain ->
                requests.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            val session = "/api/sessions/session-a"
            assertTrue(runCatching { client.request(session, method = "DELETE") }.isFailure)
            assertTrue(runCatching { client.nativeRequest(session, method = "DELETE") }.isFailure)
            assertTrue(runCatching { client.nativeGet(session, method = "DELETE") }.isFailure)
            assertTrue(runCatching { client.call(session, method = "DELETE") }.isFailure)
            assertTrue(runCatching { client.copyTo(session, ByteArrayOutputStream(), data = JSONObject()) }.isFailure)
            assertEquals(0, requests.get())
        } finally { client.closeNative() }
    }

    @Test fun oldRoutesCannotEscapeAdapter() {
        assertFalse(NativeOperationPolicy.direct("/api/chat/start", "GET"))
        assertFalse(NativeOperationPolicy.direct("/api/mobile/bootstrap", "GET"))
        assertFalse(NativeOperationPolicy.direct("/api/transcribe", "GET"))
        assertFalse(NativeOperationPolicy.direct("//foreign/api/model/info", "GET"))
        assertTrue(NativeOperationPolicy.direct("/api/model/info", "GET"))
        assertTrue(NativeOperationPolicy.direct("/api/tools/toolsets/mcp.research", "GET"))
        assertTrue(NativeOperationPolicy.direct("/api/plugins/cypher/mobile/bootstrap", "GET"))
    }
}
