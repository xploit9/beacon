package dev.hermes.android.data

import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.hermes.android.core.Scope
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BeaconClientAuthTest {
    private fun emptyAuthStore(): AuthStore {
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "getString") args!![1] else error("Unexpected preference operation")
        } as SharedPreferences
        return AuthStore(object : ContextWrapper(null) {
            override fun getSharedPreferences(ignored: String, mode: Int) = prefs
        }, AppCipher())
    }

    private fun trackedBody(closed: AtomicBoolean): ResponseBody {
        val source = object : ForwardingSource(Buffer().writeUtf8("{}")) {
            override fun close() { closed.set(true); super.close() }
        }.buffer()
        return object : ResponseBody() {
            override fun contentType(): MediaType = "application/json".toMediaType()
            override fun contentLength(): Long = 2
            override fun source(): BufferedSource = source
        }
    }

    @Test fun rejectedCookieRotationCannotBecomeTheNextRequestCredential() = runBlocking {
        val origin = "https://native.example"
        val old = "__Host-hermes_session_rt=before; Path=/; Secure; HttpOnly"
        val initial = AuthContext(origin, listOf(old), "alice", backend = "hermes-dashboard", nativeProvider = "basic")
        val store = emptyAuthStore()
        val observed = mutableListOf<String>()
        lateinit var client: BeaconClient
        client = BeaconClient(initial, store, persistContext = false) { builder ->
            builder.addInterceptor { chain ->
                observed += client.http.cookieJar.loadForRequest(chain.request().url).joinToString(";") { it.value }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            val rotated = requireNotNull(Cookie.parse(origin.toHttpUrl(), "__Host-hermes_session_rt=after; Path=/; Secure; HttpOnly"))
            client.jar.saveFromResponse(origin.toHttpUrl(), listOf(rotated))
            val retained = runCatching { client.retainCookieRotation() }
            runCatching { client.nativeRequest("/api/files") }
            assertTrue(retained.isFailure || client.auth.cookies.none { it.contains("=after") })
            assertTrue(observed.none { it.contains("after") })
        } finally { client.closeNative() }
    }

    @Test fun concurrentRequestCannotUseACookieBeforeItsRejectedPersistence() = runBlocking {
        val origin = "https://native.example"
        val initial = AuthContext(origin, listOf("__Host-hermes_session_rt=before; Path=/; Secure; HttpOnly"), "alice", backend = "hermes-dashboard", nativeProvider = "basic")
        val store = emptyAuthStore()
        val observed = mutableListOf<String>()
        val entered = AtomicBoolean()
        lateinit var client: BeaconClient
        client = BeaconClient(initial, store, persistContext = false) { builder ->
            builder.addInterceptor { chain ->
                if (chain.request().url.encodedPath == "/api/status") {
                    entered.set(true)
                    val rotated = requireNotNull(Cookie.parse(origin.toHttpUrl(), "__Host-hermes_session_rt=after; Path=/; Secure; HttpOnly"))
                    client.http.cookieJar.saveFromResponse(chain.request().url, listOf(rotated))
                    runCatching { runBlocking { client.nativeRequest("/api/files") } }
                } else {
                    observed += client.http.cookieJar.loadForRequest(chain.request().url).joinToString(";") { it.value }
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            runCatching { client.nativeRequest("/api/status") }
            assertTrue(entered.get())
            assertTrue(observed.none { it.contains("after") })
        } finally { client.closeNative() }
    }

    @Test fun originalUnauthorizedResponseClosesWhenRefreshThrows() = runBlocking {
        for (failure in listOf(IOException("synthetic-io"), IllegalStateException("synthetic-runtime"))) {
            val origin = "https://native.example"
            val bearer = NativeBearerRecord("old-access", "old-refresh", 100, origin, "alice", "default", "basic")
            val closed = AtomicBoolean()
            val client = BeaconClient(AuthContext(origin, emptyList(), "alice", backend = "hermes-dashboard", bearer = bearer), null, persistContext = false) { builder ->
                builder.addInterceptor { chain ->
                    val request = chain.request()
                    if (request.url.encodedPath == "/auth/native/refresh") throw failure
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(401).message("Fixture").body(trackedBody(closed)).build()
                }
            }
            try {
                assertTrue(runCatching { client.nativeRequest("/api/files") }.isFailure)
                assertTrue("The original 401 body must be closed for ${failure.javaClass.simpleName}", closed.get())
            } finally { client.closeNative() }
        }
    }

    @Test fun lateDeviceRemovedRejectionNeverForgetsANewOrRevisitedProfile() = runBlocking {
        for (mode in listOf("same", "changed", "aba")) {
            val origin = "https://native.example"
            val initial = AuthContext(origin, listOf("__Host-hermes_session_rt=fixture; Path=/; Secure; HttpOnly"), "alice", backend = "hermes-dashboard", nativeProvider = "basic")
            val removed = mutableListOf<Scope>()
            val previousRemoved = NativeDeviceRemoval.onRemoved; val previousForget = NativeDeviceRemoval.onForgetScreens
            NativeDeviceRemoval.onRemoved = { removed += it.scope }; NativeDeviceRemoval.onForgetScreens = null
            lateinit var client: BeaconClient
            client = BeaconClient(initial, null, persistContext = false) { builder ->
                builder.addInterceptor { chain ->
                    if (mode != "same") runBlocking { client.bindProfile("work"); if (mode == "aba") client.bindProfile("default") }
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Fixture")
                        .body("""{"code":"device_removed"}""".toResponseBody("application/json".toMediaType())).build()
                }
            }
            try {
                assertEquals(403, (runCatching { client.nativeRequest("/api/plugins/beacon/mobile/health") }.exceptionOrNull() as ApiFailure).status)
                assertEquals(mode, if (mode == "same") listOf(initial.scope()) else emptyList(), removed)
            } finally {
                client.closeNative(); NativeDeviceRemoval.onRemoved = previousRemoved; NativeDeviceRemoval.onForgetScreens = previousForget
            }
        }
    }

    @Test fun lateSuccessfulDeviceRemovalNeverForgetsANewOrRevisitedProfile() = runBlocking {
        val field = NativePairing::class.java.getDeclaredField("store").apply { isAccessible = true }
        val previousStore = field.get(NativePairing)
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            if (method.name == "getString") "phone-1" else error("Unexpected preference operation")
        } as SharedPreferences
        val previousRemoved = NativeDeviceRemoval.onRemoved; val previousForget = NativeDeviceRemoval.onForgetScreens
        field.set(NativePairing, prefs)
        try {
            for (mode in listOf("same", "changed", "aba")) {
                val removed = mutableListOf<Scope>()
                NativeDeviceRemoval.onRemoved = { removed += it.scope }; NativeDeviceRemoval.onForgetScreens = null
                val initial = AuthContext("https://native.example", listOf("__Host-hermes_session_rt=fixture; Path=/; Secure; HttpOnly"), "alice", backend = "hermes-dashboard", nativeProvider = "basic")
                lateinit var client: BeaconClient
                client = BeaconClient(initial, null, persistContext = false) { builder ->
                    builder.addInterceptor { chain ->
                        if (mode != "same") runBlocking { client.bindProfile("work"); if (mode == "aba") client.bindProfile("default") }
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                            .body("""{"ok":true}""".toResponseBody("application/json".toMediaType())).build()
                    }
                }
                try {
                    assertTrue(client.nativeRequest("/api/plugins/beacon/devices/phone-1", "DELETE").getBoolean("ok"))
                    assertEquals(mode, if (mode == "same") listOf(initial.scope()) else emptyList(), removed)
                } finally { client.closeNative() }
            }
        } finally {
            field.set(NativePairing, previousStore); NativeDeviceRemoval.onRemoved = previousRemoved; NativeDeviceRemoval.onForgetScreens = previousForget
        }
    }
    @Test fun devicesReadKeepsHermesCookieRotation() = runBlocking {
        val origin = "https://native.example"
        val old = Cookie.Builder().name("__Host-hermes_session_rt").value("before").hostOnlyDomain("native.example").path("/").secure().httpOnly().build()
        val rotated = requireNotNull(Cookie.parse(origin.toHttpUrl(), "__Host-hermes_session_rt=after; Path=/; Secure; HttpOnly"))
        lateinit var client: BeaconClient
        client = BeaconClient(AuthContext(origin, listOf(old.toString()), "alice", backend = "hermes-dashboard", nativeProvider = "basic"), null, persistContext = false) { builder ->
            builder.addInterceptor { chain ->
                client.http.cookieJar.saveFromResponse(chain.request().url, listOf(rotated))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body("""{"devices":[{"device_id":"phone-1","name":"Phone"}]}""".toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            assertEquals(listOf("phone-1"), NativePairing.devices(client).map { it.id })
            assertEquals(listOf(rotated.toString()), client.auth.cookies)
        } finally { client.closeNative() }
    }

    @Test fun bearerAndLoopbackRequestsNeverReadTheAuthStoreForCookieRotation() = runBlocking {
        val origin = "https://native.example"
        val bearer = NativeBearerRecord("access", "refresh", 4_000_000_000, origin, "alice", "default", "basic")
        val loopback = LoopbackSessionRecord("loopback-token", origin, "local", verified = true, persisted = true)
        val cases = mapOf(
            "bearer" to AuthContext(origin, emptyList(), "alice", backend = "hermes-dashboard", bearer = bearer),
            "loopback" to AuthContext(origin, emptyList(), "local", backend = "hermes-dashboard", loopbackSession = loopback),
            // Control: a cookie session still checks the store, so the counter below can see a read.
            "cookie" to AuthContext(origin, listOf("hermes_session_rt=cookie; Path=/; Secure; HttpOnly"), "alice", backend = "hermes-dashboard", nativeProvider = "basic"),
        )
        for ((name, auth) in cases) {
            val reads = AtomicInteger()
            val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                if (method.name == "getString") { reads.incrementAndGet(); args!![1] } else error("Unexpected preference operation ${method.name}")
            } as SharedPreferences
            val store = AuthStore(object : ContextWrapper(null) {
                override fun getSharedPreferences(ignored: String, mode: Int) = prefs
            }, AppCipher())
            val server = MockWebServer().apply { enqueue(MockResponse().setBody("""{"ok":true}""")) }
            val client = BeaconClient(auth, store, persistContext = false) { builder ->
                builder.addInterceptor { chain ->
                    val request = chain.request()
                    chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
                }
            }
            try {
                assertTrue(client.nativeRequest("/api/files").getBoolean("ok"))
                assertEquals(name, name != "cookie", reads.get() == 0)
            } finally { client.closeNative(); server.shutdown() }
        }
    }

    @Test fun detachedNativeResponseKeepsItsRotatedCookieContext() = runBlocking {
        for (status in listOf(200, 503)) {
            val origin = "https://native.example"
            val old = Cookie.Builder().name("__Host-hermes_session_rt").value("before").hostOnlyDomain("native.example").path("/").secure().httpOnly().build()
            val rotated = requireNotNull(Cookie.parse(origin.toHttpUrl(), "__Host-hermes_session_rt=after; Path=/; Secure; HttpOnly"))
            val initial = AuthContext(origin, listOf(old.toString()), "alice", backend = "hermes-dashboard", nativeProvider = "basic")
            lateinit var executing: BeaconClient
            val source = BeaconClient(initial, null, persistContext = false) { builder ->
                builder.addInterceptor { chain ->
                    executing.http.cookieJar.saveFromResponse(chain.request().url, listOf(rotated))
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                        .body("""{"ok":true}""".toResponseBody("application/json".toMediaType())).build()
                }
            }
            executing = source.detached()
            try {
                val result = runCatching { executing.nativeRequest("/api/files") }
                if (status == 200) assertTrue(result.getOrThrow().getBoolean("ok"))
                else assertEquals(status, (result.exceptionOrNull() as ApiFailure).status)
                assertEquals(listOf(rotated.toString()), executing.auth.cookies)
                assertEquals(listOf(rotated), executing.jar.loadForRequest(origin.toHttpUrl()))
                assertEquals(initial, source.auth)
            } finally { executing.closeNative(); source.closeNative() }
        }
    }

    @Test fun optionalProbeCannotContaminateTheNextNativeCookieSnapshot() = runBlocking {
        val origin = "https://native.example"
        val old = Cookie.Builder().name("__Host-hermes_session_rt").value("native").hostOnlyDomain("native.example").path("/").secure().httpOnly().build()
        val initial = AuthContext(origin, listOf(old.toString()), "alice", backend = "hermes-dashboard", nativeProvider = "basic")
        lateinit var client: BeaconClient
        client = BeaconClient(initial, null, persistContext = false) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val probe = request.url.encodedPath == "/api/plugins/beacon/mobile/bootstrap"
                if (probe) client.http.cookieJar.saveFromResponse(request.url, listOf(requireNotNull(Cookie.parse(request.url, "preferences=plugin; Path=/; Secure; HttpOnly"))))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body((if (probe) """{"contract_version":2,"backend":"hermes-dashboard","owner_id":"alice","profile":"default"}""" else """{"ok":true}""").toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            assertEquals("available", client.probeOptionalFeatures().getString("plugin"))
            assertEquals(initial, client.auth)
            assertEquals(listOf(old), client.jar.loadForRequest(origin.toHttpUrl()))
            client.nativeRequest("/api/files")
            assertEquals(initial, client.auth)
        } finally { client.closeNative() }
    }

    @Test fun optionalProbeKeepsNativeCookieRotationsEvenWhenItsProjectionFails() = runBlocking {
        for ((status, owner) in listOf(200 to "alice", 200 to "another-owner", 503 to "alice")) {
            val origin = "https://native.example"
            val initial = AuthContext(origin, listOf("__Host-hermes_session_rt=before; Path=/; Secure; HttpOnly"), "alice", backend = "hermes-dashboard", nativeProvider = "basic")
            val rotated = requireNotNull(Cookie.parse(origin.toHttpUrl(), "__Host-hermes_session_rt=after; Path=/; Secure; HttpOnly"))
            lateinit var client: BeaconClient
            client = BeaconClient(initial, null, persistContext = false) { builder ->
                builder.addInterceptor { chain ->
                    val request = chain.request()
                    client.http.cookieJar.saveFromResponse(request.url, listOf(rotated, requireNotNull(Cookie.parse(request.url, "preferences=plugin; Path=/; Secure"))))
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                        .body("""{"contract_version":2,"backend":"hermes-dashboard","owner_id":"$owner","profile":"default"}""".toResponseBody("application/json".toMediaType())).build()
                }
            }
            try {
                val projection = client.probeOptionalFeatures()
                assertEquals(status == 200 && owner == initial.owner, projection.getString("plugin") == "available")
                assertEquals(initial.copy(cookies = listOf(rotated.toString())), client.auth)
                assertEquals(listOf(rotated), client.http.cookieJar.loadForRequest(origin.toHttpUrl()))
            } finally { client.closeNative() }
        }
    }

    @Test fun downloadAndWorkspaceUploadKeepRotationsBeforeStatusAndBodyFailures() = runBlocking {
        val file = File.createTempFile("cookie-transfer", ".txt").apply { writeText("fixture") }
        try {
            for (upload in listOf(false, true)) for (failure in listOf("none", "status", "body")) {
                val origin = "https://native.example"
                val initial = AuthContext(origin, listOf("__Host-hermes_session_rt=before; Path=/; Secure; HttpOnly"), "alice", backend = "hermes-dashboard", nativeProvider = "basic")
                val rotated = requireNotNull(Cookie.parse(origin.toHttpUrl(), "__Host-hermes_session_rt=after; Path=/; Secure; HttpOnly"))
                lateinit var client: BeaconClient
                client = BeaconClient(initial, null, persistContext = false) { builder ->
                    builder.addInterceptor { chain ->
                        client.http.cookieJar.saveFromResponse(chain.request().url, listOf(rotated))
                        val body = if (failure == "body" && upload) "not JSON" else """{"ok":true}"""
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (failure == "status") 503 else 200).message("Fixture")
                            .body(body.toResponseBody("application/json".toMediaType())).build()
                    }
                }
                try {
                    val result = runCatching {
                        if (upload) client.upload("/api/workspace/upload", file, "fixture.txt", "text/plain", "fixture-session", directory = "/fixture")
                        else client.copyTo("/api/fs/download", ByteArrayOutputStream(), limit = if (failure == "body") 1 else 1024)
                    }
                    if (failure == "none") result.getOrThrow() else assertTrue(result.isFailure)
                    if (failure == "status") assertEquals(503, (result.exceptionOrNull() as ApiFailure).status)
                    assertEquals(listOf(rotated.toString()), client.auth.cookies)
                    assertEquals(listOf(rotated), client.http.cookieJar.loadForRequest(origin.toHttpUrl()))
                } finally { client.closeNative() }
            }
        } finally { file.delete() }
    }

    @Test fun unverifiedLoopbackTransferCannotRetainNativeCookies() = runBlocking {
        val origin = "https://native.example"
        val initial = AuthContext(origin, emptyList(), "local", backend = "hermes-dashboard", loopbackSession = LoopbackSessionRecord("fixture", origin, "local"))
        lateinit var client: BeaconClient
        client = BeaconClient(initial, null, persistContext = false) { builder ->
            builder.addInterceptor { chain ->
                client.http.cookieJar.saveFromResponse(chain.request().url, listOf(requireNotNull(Cookie.parse(chain.request().url,
                    "__Host-hermes_session_rt=unverified; Path=/; Secure; HttpOnly"))))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body("fixture".toResponseBody()).build()
            }
        }
        try {
            assertEquals(7L, client.copyTo("/api/fs/download", ByteArrayOutputStream()))
            assertEquals(initial, client.auth)
            assertFalse(client.jar.snapshot().isEmpty())
        } finally { client.closeNative() }
    }

    @Test fun authorizedProfileViewRefreshesThroughItsAccountWithoutRebindingTheRequest() = runBlocking {
        val old = NativeBearerRecord("access", "refresh", 100, "https://native.example", "alice", "b", "basic")
        val requests = mutableListOf<Request>()
        val source = BeaconClient(AuthContext(old.origin, emptyList(), old.owner, old.profile, backend = "hermes-dashboard", bearer = old), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request(); requests += request
                val refresh = request.url.encodedPath == "/auth/native/refresh"
                val status = if (refresh || request.header("Authorization") == "Bearer rotated") 200 else 401
                val body = if (refresh) """{"access_token":"rotated","refresh_token":"rotated-refresh","token_type":"Bearer","user_id":"alice","provider":"basic"}"""
                    else if (status == 200) """{"ok":true}""" else """{"error":"session_expired"}"""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }
        val retained = source.forAuthorizedProfile("a").detached()
        try {
            assertTrue(retained.nativeRequest("/api/files", params = mapOf("profile" to "a")).getBoolean("ok"))
            assertEquals(listOf("/api/files", "/auth/native/refresh", "/api/files"), requests.map { it.url.encodedPath })
            assertTrue(requests.filter { it.url.encodedPath == "/api/files" }.all { it.url.queryParameter("profile") == "a" })
            assertEquals("a", retained.auth.profile)
            assertEquals("b", source.auth.profile)
            assertEquals("b", source.auth.bearer?.profile)
            assertEquals("rotated", source.auth.bearer?.accessToken)
        } finally { retained.closeNative(); source.closeNative() }
    }

    @Test fun legacyCookieIdentityIsLearnedFromNativeRestWithNoPluginDependency() = runBlocking {
        val origin = "https://native.example"
        val requests = mutableListOf<Request>()
        val client = BeaconClient(AuthContext(origin, listOf("hermes_session_rt=old; Path=/; Secure; HttpOnly"), "alice", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                requests += chain.request()
                val body = when (path) {
                    "/api/auth/me" -> """{"user_id":"alice","provider":"basic"}"""
                    "/api/profiles" -> """{"profiles":[{"name":"default"}]}"""
                    else -> error("Legacy sign-in must use only native routes: $path")
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            assertTrue(client.auth.canReconnectNative(origin))
            assertTrue(client.auth.cachedScopeFor(origin, false) == null)
            client.bootstrap()
            assertEquals("basic", client.auth.nativeProvider)
            assertEquals(client.scope, client.auth.cachedScopeFor(origin, false))
            assertEquals(listOf("/api/auth/me", "/api/profiles"), requests.map { it.url.encodedPath })
        } finally { client.closeNative() }
    }

    @Test fun bootstrapRejectsSameSubjectFromAnotherNativeProviderBeforeReadingProfiles() = runBlocking {
        val origin = "https://native.example"
        val bearer = NativeBearerRecord("access", "refresh", 100, origin, "alice", "default", "basic")
        val requests = mutableListOf<Request>()
        val client = BeaconClient(AuthContext(origin, emptyList(), "alice", bearer = bearer), null) { builder ->
            builder.addInterceptor { chain ->
                requests += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body("""{"user_id":"alice","provider":"oidc"}""".toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            assertTrue(runCatching { client.bootstrap() }.exceptionOrNull() is CachedIdentityMismatch)
            assertEquals(listOf("/api/auth/me"), requests.map { it.url.encodedPath })
            assertEquals(bearer, client.auth.bearer)
        } finally { client.closeNative() }
    }

    private fun unauthorizedClient(auth: AuthContext, requests: MutableList<Request>, body: String) = BeaconClient(auth, null) { builder ->
        builder.addInterceptor { chain ->
            val request = chain.request(); requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    @Test fun loopbackPluginUnauthorizedNeverEndsSignInAndKeepsTheSession() = runBlocking {
        val origin = "https://native.example"
        val loopback = LoopbackSessionRecord("loopback-token", origin, "local", verified = true)
        val requests = mutableListOf<Request>()
        val client = unauthorizedClient(
            AuthContext(origin, emptyList(), "local", backend = "hermes-dashboard", loopbackSession = loopback),
            requests,
            """{"detail":"Verified native session required"}""",
        )

        val error = runCatching { client.nativeRequest("/api/plugins/beacon/mobile/bootstrap") }.exceptionOrNull()

        assertTrue(error is ApiFailure)
        assertEquals(401, (error as ApiFailure).status)
        assertTrue(error.plugin)
        assertTrue(!dev.hermes.android.endsSignIn(error))
        assertEquals("loopback-token", requests.single().header(HERMES_LOOPBACK_SESSION_HEADER))
        assertEquals(loopback, client.auth.loopbackSession)
        client.closeNative()
    }

    @Test fun nativeUnauthorizedRemains401() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = unauthorizedClient(AuthContext("https://native.example", emptyList()), requests, """{"detail":"Unauthorized"}""")

        val error = runCatching { client.nativeRequest("/api/auth/me") }.exceptionOrNull()

        assertTrue(error is ApiFailure)
        assertEquals(401, (error as ApiFailure).status)
        assertTrue(dev.hermes.android.endsSignIn(error))
        assertEquals("/api/auth/me", requests.single().url.encodedPath)
        client.closeNative()
    }
}
