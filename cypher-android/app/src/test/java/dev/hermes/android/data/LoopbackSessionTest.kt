package dev.hermes.android.data

import java.security.MessageDigest
import java.util.UUID
import dev.hermes.android.NativeSetupAuthMode
import dev.hermes.android.nativeSetupAuthMode
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking

class LoopbackSessionTest {
    private val origin = "https://hermes.example"
    private fun token() = UUID.randomUUID().toString().replace("-", "")
    private fun assertToken(expected: String, actual: String?) {
        assertTrue(actual != null && MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray()))
    }
    private fun response(request: Request, code: Int, body: String, type: String = "application/json") =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .header("Content-Type", type).body(body.toResponseBody(type.toMediaType())).build()
    private fun client(session: LoopbackSessionRecord, handler: (Request) -> Response) =
        CypherClient(AuthContext(origin, emptyList(), session.owner, "default", backend = "hermes-dashboard", loopbackSession = session), null) { builder ->
            builder.addInterceptor { chain -> handler(chain.request()) }
        }

    @Test fun setupUsesLoopbackOrNativePkceAndNeverInventsAPasswordFallback() {
        assertEquals(NativeSetupAuthMode.Loopback, nativeSetupAuthMode(JSONObject().put("auth_required", false)))
        assertEquals(NativeSetupAuthMode.NativePkce, nativeSetupAuthMode(JSONObject().put("auth_required", true).put("auth_flows", JSONArray().put("cookie").put("native_pkce"))))
        assertEquals(NativeSetupAuthMode.Unsupported, nativeSetupAuthMode(JSONObject().put("auth_required", true).put("auth_flows", JSONArray().put("cookie"))))
        assertEquals(NativeSetupAuthMode.Unsupported, nativeSetupAuthMode(JSONObject()))
    }

    @Test fun spaSessionTokenAssignmentIsParsedWithoutPuttingItInErrors() {
        val secret = token()
        val html = "<script>window.__HERMES_SESSION_TOKEN__=${JSONObject.quote(secret)};</script>"
        assertToken(secret, parseLoopbackSessionToken(html))
        val error = runCatching { parseLoopbackSessionToken("<html>login</html>") }.exceptionOrNull()
        assertTrue(error is LoopbackSessionRefreshFailed)
        assertFalse(error.toString().contains(secret))
    }

    @Test fun loopbackIdentityIsOriginBoundAndSecretsStayOutOfText() {
        val secret = token()
        val record = LoopbackSessionRecord(secret, origin, "local", verified = true, persisted = true)
        val auth = AuthContext(origin, emptyList(), "local", backend = "hermes-dashboard", loopbackSession = record)

        assertNotNull(auth.cachedScopeFor(origin, replacingAccount = false))
        assertNull(auth.copy(loopbackSession = record.copy(verified = false)).cachedScopeFor(origin, replacingAccount = false))
        assertNull(auth.copy(loopbackSession = record.copy(persisted = false)).cachedScopeFor(origin, replacingAccount = false))
        assertNull(auth.copy(loopbackSession = record.copy(origin = "https://other.example"))
            .cachedScopeFor(origin, replacingAccount = false))
        assertFalse(auth.toString().contains(secret))
        assertFalse(record.toString().contains(secret))
    }

    @Test fun loopbackRequestsUseOnlyTheSessionHeaderAndRootTokenUsesTheSameOrigin() {
        val secret = token()
        val session = LoopbackSessionRecord(secret, origin, "local")
        val requests = mutableListOf<Request>()
        val client = client(session) { request ->
            requests += request
            response(request, 200, "{}")
        }
        try {
            val ticket = client.request("/api/auth/ws-ticket").build()
            assertToken(secret, ticket.header(HERMES_LOOPBACK_SESSION_HEADER))
            assertNull(ticket.header("Authorization"))
            assertNull(ticket.url.queryParameter("session"))
            assertNull(client.request("/api/ws").build().header(HERMES_LOOPBACK_SESSION_HEADER))
            assertTrue(NativeOperationPolicy.direct("/", "GET"))
            assertTrue(runCatching { client.request("/", method = "POST").build() }.isFailure)
            assertTrue(requests.all { it.url.host == "hermes.example" && it.url.scheme == "https" })
        } finally { client.closeNative() }
    }

    @Test fun unauthorizedLoopbackRequestReadsRootTokenAndRetriesOnce() = runBlocking {
        val oldToken = token()
        val newToken = token()
        val requests = mutableListOf<Request>()
        var protectedAttempts = 0
        var rootReads = 0
        val client = client(LoopbackSessionRecord(oldToken, origin, "local")) { request ->
            requests += request
            when (request.url.encodedPath) {
                "/" -> {
                    rootReads++
                    response(request, 200, "<script>window.__HERMES_SESSION_TOKEN__=${JSONObject.quote(newToken)};</script>", "text/html")
                }
                else -> {
                    protectedAttempts++
                    if (protectedAttempts == 1) response(request, 401, "{\"detail\":\"Unauthorized\"}")
                    else response(request, 200, "{\"ok\":true}")
                }
            }
        }
        try {
            assertTrue(client.get("/api/status").getBoolean("ok"))
            assertEquals(2, protectedAttempts)
            assertEquals(1, rootReads)
            assertToken(oldToken, requests.first { it.url.encodedPath == "/api/status" }.header(HERMES_LOOPBACK_SESSION_HEADER))
            assertToken(newToken, requests.last { it.url.encodedPath == "/api/status" }.header(HERMES_LOOPBACK_SESSION_HEADER))
            assertNull(requests.first { it.url.encodedPath == "/" }.header(HERMES_LOOPBACK_SESSION_HEADER))
            assertToken(newToken, client.auth.loopbackSession?.token)
        } finally { client.closeNative() }
    }

    @Test fun ownerDeniedPlugin401DoesNotRotateTheDashboardSession() = runBlocking {
        val secret = token()
        var rootReads = 0
        val client = client(LoopbackSessionRecord(secret, origin, "local")) { request ->
            if (request.url.encodedPath == "/") rootReads++
            response(request, 401, "{\"detail\":\"Owner not authorized\"}")
        }
        try {
            val error = runCatching { client.nativeRequest("/api/plugins/cypher/mobile/bootstrap") }.exceptionOrNull()
            assertTrue(error is ApiFailure && error.status == 401)
            assertEquals(0, rootReads)
        } finally { client.closeNative() }
    }

    @Test fun failedSessionRotationStopsAfterOneRootReadWithSafeMessage() = runBlocking<Unit> {
        val secret = token()
        var protectedAttempts = 0
        var rootReads = 0
        val client = client(LoopbackSessionRecord(secret, origin, "local")) { request ->
            when (request.url.encodedPath) {
                "/" -> { rootReads++; response(request, 200, "<html>login</html>", "text/html") }
                else -> { protectedAttempts++; response(request, 401, "{\"detail\":\"Unauthorized\"}") }
            }
        }
        try {
            val error = runCatching { client.get("/api/status") }.exceptionOrNull()
            assertTrue(error is LoopbackSessionRefreshFailed)
            assertEquals("Hermes restarted. Scan the Connect QR again.", error?.message)
            assertEquals(1, protectedAttempts)
            assertEquals(1, rootReads)
            assertFalse(error.toString().contains(secret))
        } finally { client.closeNative() }
    }
}
