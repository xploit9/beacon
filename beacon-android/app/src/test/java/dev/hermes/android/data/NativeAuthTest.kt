package dev.hermes.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeAuthTest {
    @Test fun nativeOAuthRoutesAreAllowed() {
        assertTrue(NativeOperationPolicy.direct("/auth/native/authorize", "GET"))
        assertTrue(NativeOperationPolicy.direct("/auth/native/token", "GET"))
        assertTrue(NativeOperationPolicy.direct("/auth/native/refresh", "GET"))
        assertTrue(NativeOperationPolicy.direct("/auth/logout", "GET"))
        assertTrue(NativeOperationPolicy.direct("/api/status", "GET"))
    }

    @Test fun pkceS256MatchesTheRfc7636Vector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            NativeAuth.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun callbackRejectsAMismatchedState() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeAuth.callbackCode("/callback?code=one-time&state=attacker", "expected")
        }
    }

    @Test fun callbackSkipsAnEmptyPreconnectSocketThenAcceptsValidCallback() {
        val server = callbackServer()
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<String> { receiveCallback(server, "expected") }
        try {
            assertTrue(sendCallback(server.localPort, null).startsWith("HTTP/1.1 400"))
            val response = runCatching { sendCallback(server.localPort, "/callback?code=one-time&state=expected") }.getOrNull()
            assertNotNull("listener should accept the valid callback after the empty socket", response)
            assertTrue(response.orEmpty().startsWith("HTTP/1.1 302 Found"))
            assertEquals("one-time", runCatching { result.get(5, TimeUnit.SECONDS) }.getOrNull())
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun callbackIgnoresFaviconAndWrongStateBeforeValidCallback() {
        val server = callbackServer()
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<String> { receiveCallback(server, "expected") }
        try {
            assertTrue(sendCallback(server.localPort, "/favicon.ico").startsWith("HTTP/1.1 400"))
            val wrongState = runCatching { sendCallback(server.localPort, "/callback?code=attacker&state=wrong") }.getOrNull()
            assertNotNull("listener should continue after the favicon request", wrongState)
            assertTrue(wrongState.orEmpty().startsWith("HTTP/1.1 400"))
            val response = runCatching { sendCallback(server.localPort, "/callback?code=accepted&state=expected") }.getOrNull()
            assertNotNull("listener should continue after the wrong state", response)
            assertTrue(response.orEmpty().startsWith("HTTP/1.1 302 Found"))
            assertEquals("accepted", runCatching { result.get(5, TimeUnit.SECONDS) }.getOrNull())
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun successfulCallbackRedirectsToCredentialFreeBeaconUri() {
        val server = callbackServer()
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<String> { receiveCallback(server, "expected") }
        try {
            val response = sendCallback(server.localPort, "/callback?code=one-time&state=expected")
            assertEquals("one-time", result.get(5, TimeUnit.SECONDS))
            val location = response.lineSequence().firstOrNull { it.startsWith("Location:", ignoreCase = true) }?.substringAfter(':')?.trim()
            assertEquals("${dev.hermes.android.BuildConfig.APPLICATION_ID}://auth/complete", location)
            assertFalse(location.orEmpty().contains('?'))
            assertTrue(response.contains("Content-Type: text/html"))
            assertTrue(response.contains("Cache-Control: no-store"))
        assertTrue(response.contains("Return to Beacon"))
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun hermesErrorCallbackUsesPlainLanguage() {
        val error = assertThrows(NativeAuthException::class.java) {
            NativeAuth.callbackCode("/callback?error=access_denied&state=expected", "expected")
        }
        assertEquals("Hermes sign-in was declined. Try again.", error.message)
    }

    @Test fun ioFailuresUsePlainNetworkMessage() {
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("Broken pipe") }.build()
        val auth = NativeAuth("https://native.example", client)
        val current = NativeBearerRecord("access", "refresh", 0, "https://native.example", "owner", "work", "fixture")
        val error = assertThrows(NativeAuthException::class.java) {
            runBlocking { auth.refresh(current, { current }, { _, _ -> true }) }
        }
        assertEquals("Couldn't reach Hermes. Check Tailscale or VPN, then try again.", error.message)
        assertFalse(error.message.orEmpty().contains("Broken pipe"))
    }

    @Test fun activityResumeDoesNotCloseListenerBeforeCallbackOrTimeout() {
        var closed = false
        var delay = 0L
        var scheduled: (() -> Unit)? = null
        val pending = PendingNativeAuthorization(
            closeListener = { closed = true },
            schedule = { wait, action -> delay = wait; scheduled = action },
        )
        pending.onActivityResumed()
        assertEquals(1_500L, delay)
        assertFalse(closed)
        scheduled!!.invoke()
        assertFalse("an unrelated resume must not close the callback listener", closed)
        assertTrue(pending.cancelledOnResume)
        assertEquals("Sign-in wasn't finished. Tap Sign in with Hermes to try again.", NativeAuth.mapSignInFailure(IOException("Socket closed"), true).message)
        pending.callbackCaptured()
        assertTrue(closed)
        assertFalse(pending.cancelledOnResume)

        var callbackListenerClosed = false
        var callbackDecision: (() -> Unit)? = null
        val completed = PendingNativeAuthorization(
            closeListener = { callbackListenerClosed = true },
            schedule = { _, action -> callbackDecision = action },
        )
        completed.onActivityResumed()
        completed.callbackCaptured()
        callbackDecision!!.invoke()
        assertTrue(callbackListenerClosed)
        assertFalse("a callback that arrived during the grace period must win", completed.cancelledOnResume)
    }

    @Test fun tokenExchangeWaitsUntilBeaconResumes() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val pending = pendingAuthorization(initiallyForeground = false)
        val auth = NativeAuth("https://native.example", tokenExchangeHttp(requests))

        val exchange = async(start = CoroutineStart.UNDISPATCHED) {
            auth.exchangeWhenForeground(pending, "test-code", "test-verifier", 1_000)
        }
        yield()
        assertTrue(requests.isEmpty())

        pending.onActivityResumed()
        exchange.await()
        assertEquals(1, requests.size)
        assertEquals("/auth/native/token", requests.single().url.encodedPath)
    }

    @Test fun tokenExchangeStartsImmediatelyWhenBeaconIsAlreadyForeground() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val pending = pendingAuthorization(initiallyForeground = true)
        val auth = NativeAuth("https://native.example", tokenExchangeHttp(requests))

        auth.exchangeWhenForeground(pending, "test-code", "test-verifier", 1_000)

        assertEquals(1, requests.size)
        assertEquals("/auth/native/token", requests.single().url.encodedPath)
    }

    @Test fun backgroundIOExceptionWaitsForResumeAndRetriesOnlyOnce() = runBlocking {
        supervisorScope {
            val requests = CopyOnWriteArrayList<Request>()
            val pending = pendingAuthorization(initiallyForeground = true)
            val auth = NativeAuth("https://native.example", tokenExchangeHttp(requests) {
                pending.onActivityPaused()
                throw IOException("background network policy")
            })

            val exchange = async(start = CoroutineStart.UNDISPATCHED) {
                auth.exchangeWhenForeground(pending, "test-code", "test-verifier", 1_000)
            }
            assertEquals(1, requests.size)
            pending.onActivityResumed()

            val error = runCatching { exchange.await() }.exceptionOrNull()
            assertTrue(error is NativeAuthException)
            assertEquals("Couldn't reach Hermes. Check Tailscale or VPN, then try again.", error?.message)
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.url.encodedPath == "/auth/native/token" })
        }
    }

    @Test fun foregroundWaitTimeoutUsesPlainLanguageMessage() {
        val requests = CopyOnWriteArrayList<Request>()
        val pending = pendingAuthorization(initiallyForeground = false)
        val auth = NativeAuth("https://native.example", tokenExchangeHttp(requests))

        val error = assertThrows(NativeAuthException::class.java) {
            runBlocking { auth.exchangeWhenForeground(pending, "test-code", "test-verifier", 10) }
        }

        assertEquals("Hermes sign-in timed out. Try again.", error.message)
        assertTrue(requests.isEmpty())
    }

    private fun pendingAuthorization(initiallyForeground: Boolean) = PendingNativeAuthorization(
        closeListener = {},
        schedule = { _, _ -> },
        initiallyForeground = initiallyForeground,
    )

    private fun tokenExchangeHttp(
        requests: CopyOnWriteArrayList<Request>,
        beforeResponse: (Request) -> Unit = {},
    ) = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).addInterceptor { chain ->
        val request = chain.request()
        requests += request
        beforeResponse(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
            .body("""{"access_token":"unit-access","refresh_token":"unit-refresh","token_type":"Bearer","expires_at":1900000000,"provider":"fixture","user_id":"owner"}""".toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private fun callbackServer() = ServerSocket().apply {
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        soTimeout = 5_000
    }

    private fun receiveCallback(server: ServerSocket, state: String): String {
        return NativeAuth("https://native.example").receiveCallback(server, state)
    }

    private fun sendCallback(port: Int, target: String?): String = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
        socket.soTimeout = 5_000
        target?.let { socket.getOutputStream().write("GET $it HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.US_ASCII)) }
        socket.getOutputStream().flush()
        socket.shutdownOutput()
        socket.getInputStream().bufferedReader(Charsets.US_ASCII).use { it.readText() }
    }

    @Test fun refreshCoalescesConcurrentRefreshTokenRotation() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val http = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).addInterceptor { chain ->
            requests += chain.request()
            Thread.sleep(60)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body("""{"access_token":"fresh-access","refresh_token":"fresh-refresh","token_type":"Bearer","expires_at":1900000000,"provider":"fixture","user_id":"owner"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val origin = "https://native.example"
        val auth = NativeAuth(origin, http)
        val old = NativeBearerRecord("old-access", "old-refresh", 0, origin, "owner", "work", "fixture")
        var saved = old
        val latest = { synchronized(this) { saved } }
        val persist: (NativeBearerRecord, NativeBearerRecord) -> Boolean = { expected, replacement ->
            synchronized(this) {
                if (saved != expected) false else { saved = replacement; true }
            }
        }
        val result = (1..5).map {
            async(Dispatchers.IO) { auth.refresh(old, latest, persist) }
        }.awaitAll()

        assertEquals(1, requests.size)
        assertTrue(result.all { it.accessToken == "fresh-access" && it.refreshToken == "fresh-refresh" })
        assertTrue(result.all { it.issuedAt > 0 })
        val payload = Buffer().also { requests.single().body!!.writeTo(it) }.readUtf8()
        assertEquals("old-refresh", JSONObject(payload).getString("refresh_token"))
    }

    @Test fun unauthorizedBearerIsRefreshedAndRetriedOnlyOnce() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val original = NativeBearerRecord("old-access", "old-refresh", 0, "https://native.example", "owner", "work", "fixture")
        val client = BeaconClient(AuthContext(original.origin, emptyList(), original.owner, original.profile, backend = "hermes-dashboard", bearer = original), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val refresh = request.url.encodedPath == "/auth/native/refresh"
                val status = if (refresh) 200 else if (request.header("Authorization") == "Bearer fresh-access") 200 else 401
                val body = if (refresh) """{"access_token":"fresh-access","refresh_token":"fresh-refresh","token_type":"Bearer","expires_at":1900000000,"provider":"fixture","user_id":"owner"}"""
                    else if (status == 200) """{"user_id":"owner"}""" else """{"detail":"Unauthorized"}"""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }

        val identity = client.get("/api/auth/me")

        assertEquals("owner", identity.getString("user_id"))
        assertEquals(listOf("/api/auth/me", "/auth/native/refresh", "/api/auth/me"), requests.map { it.url.encodedPath })
        assertEquals("Bearer old-access", requests[0].header("Authorization"))
        assertEquals("Bearer fresh-access", requests[2].header("Authorization"))
        client.closeNative()
    }

    @Test fun refreshUnauthorizedClearsRejectedBearerForJsonAndHtmlBodies() = runBlocking {
        val origin = "https://native.example"
        for ((body, mediaType) in listOf(
            """{"detail":"Unauthorized"}""" to "application/json",
            "<html>Unauthorized</html>" to "text/html",
        )) {
            val requests = CopyOnWriteArrayList<Request>()
            val original = NativeBearerRecord("old-access", "old-refresh", 0, origin, "owner", "work", "fixture")
            val client = BeaconClient(AuthContext(origin, emptyList(), original.owner, original.profile, backend = "hermes-dashboard", bearer = original), null) { builder ->
                builder.addInterceptor { chain ->
                    val request = chain.request(); requests += request
                    val refresh = request.url.encodedPath == "/auth/native/refresh"
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                        .body((if (refresh) body else """{"detail":"Unauthorized"}""").toResponseBody(mediaType.toMediaType())).build()
                }
            }

            assertTrue(runCatching { client.get("/api/auth/me") }.exceptionOrNull() is NativeAuthExpiredException)
            assertNull("A rejected refresh credential must be removed", client.auth.bearer)
            assertEquals(listOf("/api/auth/me", "/auth/native/refresh"), requests.map { it.url.encodedPath })
            client.closeNative()
        }
    }

    @Test fun wsTicketAndPluginRequestsShareTheSameBearerWithoutSendingLegacyCookies() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val origin = "https://native.example"
        val cookie = Cookie.Builder().name("hermes_session_at").value("legacy-cookie").hostOnlyDomain("native.example").path("/").secure().httpOnly().expiresAt(System.currentTimeMillis() + 60_000).build()
        val bearer = NativeBearerRecord("access", "refresh", 0, origin, "owner", "work", "fixture")
        val client = BeaconClient(AuthContext(origin, listOf(cookie.toString()), bearer.owner, bearer.profile, backend = "hermes-dashboard", bearer = bearer), null) { builder ->
            builder.addInterceptor { chain ->
                requests += chain.request()
                val path = chain.request().url.encodedPath
                val body = if (path == "/api/auth/ws-ticket") """{"ticket":"once"}""" else """{"contract_version":2,"backend":"hermes-dashboard","owner_id":"owner","profile":"work","voice":{"available":false}}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }

        client.nativeRequest("/api/auth/ws-ticket", "POST", JSONObject())
        client.nativeRequest("/api/plugins/beacon/mobile/bootstrap")

        assertEquals(listOf("/api/auth/ws-ticket", "/api/plugins/beacon/mobile/bootstrap"), requests.map { it.url.encodedPath })
        assertTrue(requests.all { it.header("Authorization") == "Bearer access" })
        assertTrue(requests.all { it.header("Cookie").isNullOrBlank() })
        client.closeNative()
    }

    @Test fun rejectedRetryDoesNotRefreshAgain() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val original = NativeBearerRecord("old-access", "old-refresh", 0, "https://native.example", "owner", "work", "fixture")
        val client = BeaconClient(AuthContext(original.origin, emptyList(), original.owner, original.profile, backend = "hermes-dashboard", bearer = original), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val refresh = request.url.encodedPath == "/auth/native/refresh"
                val body = if (refresh) """{"access_token":"fresh-access","refresh_token":"fresh-refresh","token_type":"Bearer","expires_at":1900000000,"provider":"fixture","user_id":"owner"}""" else """{"detail":"Unauthorized"}"""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (refresh) 200 else 401).message("Fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }

        val error = try { client.get("/api/auth/me"); null } catch (failure: Throwable) { failure }
        assertTrue(error is ApiFailure)

        assertEquals(listOf("/api/auth/me", "/auth/native/refresh", "/api/auth/me"), requests.map { it.url.encodedPath })
        client.closeNative()
    }

    @Test fun pluginRequestRefreshesOnlyForHermesExpiredSessionEnvelope() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val original = NativeBearerRecord("old-access", "old-refresh", 0, "https://native.example", "owner", "work", "fixture")
        val client = BeaconClient(AuthContext(original.origin, emptyList(), original.owner, original.profile, backend = "hermes-dashboard", bearer = original), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val path = request.url.encodedPath
                val status = when {
                    path == "/auth/native/refresh" -> 200
                    request.header("Authorization") == "Bearer fresh-access" -> 200
                    else -> 401
                }
                val body = when {
                    path == "/auth/native/refresh" -> """{"access_token":"fresh-access","refresh_token":"fresh-refresh","token_type":"Bearer","expires_at":1900000000,"provider":"fixture","user_id":"owner"}"""
                    status == 200 -> """{"ok":true}"""
                    else -> """{"error":"session_expired","detail":"Unauthorized","reason":"invalid_or_expired_session","login_url":"/login"}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }

        assertTrue(client.nativeRequest("/api/plugins/beacon/mobile/bootstrap").getBoolean("ok"))

        assertEquals(listOf("/api/plugins/beacon/mobile/bootstrap", "/auth/native/refresh", "/api/plugins/beacon/mobile/bootstrap"), requests.map { it.url.encodedPath })
        assertEquals("Bearer fresh-access", requests.last().header("Authorization"))
        client.closeNative()
    }

    @Test fun pluginSpecificUnauthorizedResponseDoesNotRefreshOrRetry() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val original = NativeBearerRecord("old-access", "old-refresh", 0, "https://native.example", "owner", "work", "fixture")
        val client = BeaconClient(AuthContext(original.origin, emptyList(), original.owner, original.profile, backend = "hermes-dashboard", bearer = original), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                requests += request
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(401).message("Plugin unauthorized")
                    .body("""{"detail":"Plugin-specific denial"}""".toResponseBody("application/json".toMediaType())).build()
            }
        }

        assertThrows(ApiFailure::class.java) { runBlocking { client.nativeRequest("/api/plugins/beacon/mobile/bootstrap") } }

        assertEquals(listOf("/api/plugins/beacon/mobile/bootstrap"), requests.map { it.url.encodedPath })
        client.closeNative()
    }

    @Test fun detachedRequestWithOldTokenRetriesWithCurrentStoredTokenWithoutRefreshing() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val original = NativeBearerRecord("old-access", "old-refresh", 0, "https://native.example", "owner", "work", "fixture")
        val client = BeaconClient(AuthContext(original.origin, emptyList(), original.owner, original.profile, backend = "hermes-dashboard", bearer = original), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val path = request.url.encodedPath
                val status = when {
                    path == "/auth/native/refresh" -> 200
                    request.header("Authorization") == "Bearer fresh-access" -> 200
                    else -> 401
                }
                val body = if (path == "/auth/native/refresh") """{"access_token":"fresh-access","refresh_token":"fresh-refresh","token_type":"Bearer","expires_at":1900000000,"provider":"fixture","user_id":"owner"}"""
                    else if (status == 200) """{"user_id":"owner"}""" else """{"detail":"Unauthorized"}"""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }
        val detached = client.detached()

        client.get("/api/auth/me")
        detached.get("/api/auth/me")

        assertEquals(1, requests.count { it.url.encodedPath == "/auth/native/refresh" })
        assertEquals(listOf("Bearer old-access", "Bearer fresh-access"), requests.takeLast(2).map { it.header("Authorization") })
        client.closeNative(); detached.closeNative()
    }

    @Test fun bootstrapRejectsBearerOwnerMismatchInsteadOfRebindingIt() = runBlocking {
        val bearer = NativeBearerRecord("access", "refresh", 0, "https://native.example", "oauth-owner", "work", "fixture")
        val client = BeaconClient(AuthContext(bearer.origin, emptyList(), owner = "", profile = bearer.profile, bearer = bearer), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body("""{"user_id":"different-owner"}""".toResponseBody("application/json".toMediaType())).build()
            }
        }

        val failure = runCatching { client.bootstrap() }.exceptionOrNull()

        assertTrue(failure is CachedIdentityMismatch)
        assertEquals("oauth-owner", client.auth.bearer?.owner)
        client.closeNative()
    }

    @Test fun legacyCookieLogoutPostsAndAcceptsRedirect() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val cookie = Cookie.Builder().name("hermes_session_at").value("legacy-cookie").hostOnlyDomain("native.example").path("/").secure().httpOnly().expiresAt(System.currentTimeMillis() + 60_000).build()
        val client = BeaconClient(AuthContext("https://native.example", listOf(cookie.toString())), null) { builder ->
            builder.addInterceptor { chain ->
                requests += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Signed out")
                    .body("".toResponseBody("text/plain".toMediaType())).build()
            }
        }

        client.logout()

        assertEquals(1, requests.size)
        assertEquals("/auth/logout", requests.single().url.encodedPath)
        assertEquals("POST", requests.single().method)
        client.closeNative()
    }

    @Test fun bearerLogoutDoesNotCallCookieOnlyServerRevoke() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val bearer = NativeBearerRecord("access", "refresh", 0, "https://native.example", "owner", "work", "fixture")
        val client = BeaconClient(AuthContext(bearer.origin, emptyList(), bearer.owner, bearer.profile, bearer = bearer), null) { builder ->
            builder.addInterceptor { chain ->
                requests += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Unexpected request")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }
        }

        client.logout()

        assertTrue(requests.isEmpty())
        client.closeNative()
    }

    @Test fun transientRefreshFailurePreservesBearerAndAllowsTheNextRequestToRetry() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        var refreshAttempts = 0
        val bearer = NativeBearerRecord("old-access", "old-refresh", 0, "https://native.example", "owner", "work", "fixture")
        val client = BeaconClient(AuthContext(bearer.origin, emptyList(), bearer.owner, bearer.profile, backend = "hermes-dashboard", bearer = bearer), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val status = when {
                    request.url.encodedPath == "/auth/native/refresh" -> if (++refreshAttempts == 1) 503 else 200
                    request.header("Authorization") == "Bearer fresh-access" -> 200
                    else -> 401
                }
                val body = when {
                    request.url.encodedPath == "/auth/native/refresh" && status == 200 -> """{"access_token":"fresh-access","refresh_token":"fresh-refresh","token_type":"Bearer","expires_at":1900000000,"provider":"fixture","user_id":"owner"}"""
                    status == 200 -> """{"user_id":"owner"}"""
                    else -> """{"detail":"Unauthorized"}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }

        val firstFailure = runCatching { client.get("/api/auth/me") }.exceptionOrNull()

        assertTrue(firstFailure is NativeAuthException)
        assertFalse(firstFailure is NativeAuthExpiredException)
        assertEquals(bearer, client.auth.bearer)
        assertEquals("owner", client.get("/api/auth/me").getString("user_id"))
        assertEquals(2, requests.count { it.url.encodedPath == "/auth/native/refresh" })
        assertEquals("fresh-access", client.auth.bearer?.accessToken)
        client.closeNative()
    }
}
