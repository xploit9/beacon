package dev.hermes.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class BeaconClientSensitiveTest {
    private val path = "/api/plugins/beacon/mobile/vault/items"
    private val canary = "synthetic-sensitive-canary"
    private fun auth(origin: String = "https://vault-fixture.invalid", kind: String = "cookie"): AuthContext = AuthContext(
        origin, if (kind == "cookie") listOf("hermes_session=fixture; Path=/; Secure") else emptyList(),
        if (kind == "loopback") "local" else "owner", "default", backend = "hermes-dashboard", nativeProvider = if (kind == "loopback") "loopback" else "basic",
        bearer = if (kind == "bearer") NativeBearerRecord("fixture-access", "fixture-refresh", 4_000_000_000, origin, "owner", "default", "basic") else null,
        loopbackSession = if (kind == "loopback") LoopbackSessionRecord("fixture-loopback", origin, "local", verified = true) else null,
    )
    private fun response(request: okhttp3.Request, status: Int, body: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(status).message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()

    private class SwitchingBody(private val duringSerialization: () -> Unit) : JSONObject() {
        private val switched = AtomicBoolean()
        override fun toString(): String {
            if (switched.compareAndSet(false, true)) duringSerialization()
            return """{"label":"synthetic"}"""
        }
    }

    @Test fun sensitiveDispatchDoesNotRereadARefreshedSharedCredentialSource() = runBlocking {
        val sensitive = mutableListOf<Pair<String?, String?>>()
        val refreshes = AtomicInteger()
        val source = BeaconClient(auth(kind = "bearer"), null, persistContext = false) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                when (request.url.encodedPath) {
                    "/auth/native/refresh" -> {
                        refreshes.incrementAndGet()
                        response(request, 200, """{"access_token":"rotated-access","refresh_token":"rotated-refresh","token_type":"Bearer","user_id":"owner","provider":"basic"}""")
                    }
                    "/api/files" -> if (request.header("Authorization") == "Bearer rotated-access") response(request, 200, "{}") else response(request, 401, "{}")
                    else -> {
                        sensitive += request.header("Authorization") to request.url.queryParameter("beacon_profile")
                        response(request, 200, """{"request_id":"fixture","removed":true}""")
                    }
                }
            }
        }
        val view = source.forAuthorizedProfile("work")
        try {
            val body = SwitchingBody { runBlocking { source.nativeRequest("/api/files") } }
            val result = runCatching { view.nativeSensitiveRequest(path, "POST", body) }
            assertEquals(1, refreshes.get())
            assertEquals("rotated-access", source.auth.bearer?.accessToken)
            assertTrue(sensitive.size <= 1)
            if (sensitive.isNotEmpty()) assertEquals("Bearer fixture-access" to "work", sensitive.single())
            else assertTrue(result.isFailure)
        } finally { view.closeNative(); source.closeNative() }
    }

    @Test fun sensitiveDispatchUsesTheCookieJarCapturedBeforeSerialization() = runBlocking {
        val origin = "https://vault-fixture.invalid"
        val initial = auth().copy(cookies = listOf("hermes_session=before; Path=/; Secure"))
        val seen = mutableListOf<String>()
        lateinit var client: BeaconClient
        client = BeaconClient(initial, null, persistContext = false) { builder ->
            builder.addInterceptor { chain ->
                val field = chain.call().javaClass.getDeclaredField("client").apply { isAccessible = true }
                val executing = field.get(chain.call()) as OkHttpClient
                val header = chain.request().header("Cookie") ?: executing.cookieJar.loadForRequest(chain.request().url).joinToString("; ") { "${it.name}=${it.value}" }
                seen += header
                response(chain.request(), 200, """{"request_id":"fixture","removed":true}""")
            }
        }
        try {
            val body = SwitchingBody {
                val rotated = requireNotNull(Cookie.parse(origin.toHttpUrl(), "hermes_session=after; Path=/; Secure"))
                client.jar.saveFromResponse(origin.toHttpUrl(), listOf(rotated))
            }
            val result = runCatching { client.nativeSensitiveRequest(path, "POST", body) }
            assertTrue(seen.size <= 1)
            if (seen.isNotEmpty()) assertEquals("hermes_session=before", seen.single()) else assertTrue(result.isFailure)
        } finally { client.closeNative() }
    }

    @Test fun sensitiveWritesAreOneShotAndNeverReplayAfterAnyAuthRejection() = runBlocking {
        for (kind in listOf("cookie", "bearer", "loopback")) {
            val requests = AtomicInteger()
            val client = BeaconClient(auth(kind = kind), null, persistContext = false) { builder ->
                builder.addInterceptor { chain ->
                    requests.incrementAndGet()
                    assertEquals(path, chain.request().url.encodedPath)
                    assertEquals("default", chain.request().url.queryParameter("profile"))
                    assertTrue(chain.request().body!!.isOneShot())
                    response(chain.request(), 401, """{"detail":"$canary","code":"session_expired"}""")
                }
            }
            try {
                val failure = runCatching { client.nativeSensitiveRequest(path, "POST", JSONObject().put("secret", canary)) }.exceptionOrNull()
                assertNotNull(failure); assertFalse(failure!!.toString().contains(canary)); assertEquals(1, requests.get())
                assertEquals(kind == "bearer", client.auth.bearer != null)
            } finally { client.closeNative() }
        }
    }

    @Test fun successAcceptsOnlyDocumentedMetadataAndRejectsSecretOrUnexpectedFields() = runBlocking {
        val bodies = listOf(
            """{"item":{"id":"vault_012345abcdef","kind":"login","label":"Fixture","origin":"https://fixture.invalid","identifier":"fixture-user","backend":"local"},"request_id":"fixture"}""" to true,
            """{"item":{"password":"$canary"}}""" to false,
            """{"debug":"$canary"}""" to false,
            """{"items":[{"nested":{"value":"$canary"}}]}""" to false,
            """{"error":"$canary"}""" to false,
            canary to false,
        )
        for ((body, accepted) in bodies) {
            val client = BeaconClient(auth(), null, persistContext = false) { builder ->
                builder.addInterceptor { chain -> response(chain.request(), 200, body) }
            }
            try {
                val result = runCatching { client.nativeSensitiveRequest(path, "POST", JSONObject()) }
                assertEquals(accepted, result.isSuccess)
                if (!accepted) assertFalse(result.exceptionOrNull().toString().contains(canary))
            } finally { client.closeNative() }
        }
    }

    @Test fun invalidOriginsRoutesMethodsAndForeignProfilesNeverSendSecrets() = runBlocking {
        val requests = AtomicInteger()
        assertTrue(runCatching { BeaconClient(auth("http://remote-fixture.invalid")) }.isFailure)
        for ((origin, route, method, payload) in listOf(
            arrayOf("https://vault-fixture.invalid", "/api/terminal", "POST", "{}"),
            arrayOf("https://vault-fixture.invalid", path, "GET", "{}"),
            arrayOf("https://vault-fixture.invalid", path, "POST", """{"profile":"foreign"}"""),
        )) {
            val client = BeaconClient(auth(origin), null, persistContext = false) { builder ->
                builder.addInterceptor { chain -> requests.incrementAndGet(); response(chain.request(), 200, "{}") }
            }
            try { assertTrue(runCatching { client.nativeSensitiveRequest(route, method, JSONObject(payload)) }.isFailure) }
            finally { client.closeNative() }
        }
        assertEquals(0, requests.get())
    }

    @Test fun redirectsAndOversizeResponsesAreUnconfirmedWithoutFollowingOrEchoing() = runBlocking {
        for (status in listOf(307, 200)) {
            val count = AtomicInteger()
            val client = BeaconClient(auth(), null, persistContext = false) { builder ->
                builder.addInterceptor { chain ->
                    count.incrementAndGet()
                    response(chain.request(), status, if (status == 307) canary else "x".repeat(1024 * 1024 + 1))
                        .newBuilder().header("Location", "https://foreign-fixture.invalid/collect").build()
                }
            }
            try {
                assertFalse(client.http.followRedirects); assertFalse(client.http.followSslRedirects); assertFalse(client.http.retryOnConnectionFailure)
                val result = runCatching { client.nativeSensitiveRequest(path, "POST", null) }
                assertTrue(result.isFailure); assertFalse(result.exceptionOrNull().toString().contains(canary)); assertEquals(1, count.get())
            } finally { client.closeNative() }
        }
    }

    @Test fun aResponseCannotBeAcceptedAfterTheClientChangesProfile() = runBlocking {
        lateinit var client: BeaconClient
        client = BeaconClient(auth(), null, persistContext = false) { builder ->
            builder.addInterceptor { chain ->
                runBlocking { client.bindProfile("foreign") }
                response(chain.request(), 200, """{"removed":true,"request_id":"fixture"}""")
            }
        }
        try {
            val result = runCatching { client.nativeSensitiveRequest(path, "DELETE", JSONObject()) }
            assertEquals(409, (result.exceptionOrNull() as ApiFailure).status)
        } finally { client.closeNative() }
    }

    @Test fun onlyReadsRetryOneBrokenSocketAndMutationsKeepTheirUnknownOutcome() = runBlocking {
        for (method in listOf("GET", "POST")) {
            val count = AtomicInteger()
            val client = BeaconClient(auth(), null, persistContext = false) { builder ->
                builder.addInterceptor { chain ->
                    if (count.incrementAndGet() == 1) throw java.net.SocketException(canary)
                    response(chain.request(), 200, "{}")
                }
            }
            try {
                val result = runCatching { client.nativeRequest("/api/plugins/beacon/mobile/health", method, if (method == "POST") JSONObject() else null) }
                assertEquals(method == "GET", result.isSuccess)
                assertEquals(if (method == "GET") 2 else 1, count.get())
            } finally { client.closeNative() }
        }
    }
}
