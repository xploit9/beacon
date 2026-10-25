package dev.hermes.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.ui.text.input.TextFieldValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.Draft
import dev.hermes.android.core.digest
import dev.hermes.android.core.json
import dev.hermes.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.Cookie
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** Synthetic transport, real ViewModel/keystore store and controlled commit ordering; no shared fixture edits. */
class NativeAuthLifetimeTest {
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val old = NativeBearerRecord("old-access", "old-refresh", 100, "https://native.example", "alice", "a", "basic", issuedAt = 1)
    private fun context(token: NativeBearerRecord) = AuthContext(token.origin, emptyList(), token.owner, token.profile, backend = "hermes-dashboard", bearer = token)
    private fun response(request: Request, status: Int, body: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(status).message("Synthetic fixture").body(body.toResponseBody("application/json".toMediaType())).build()

    /** Only the first AuthStore dispatch is held; logout and replacement writes can complete normally. */
    private class HoldOneWrite : CoroutineDispatcher() {
        private val armed = AtomicBoolean(true)
        private val held = AtomicReference<Pair<CoroutineContext, Runnable>?>(null)
        val queued = CompletableDeferred<Unit>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (armed.compareAndSet(true, false)) {
                check(held.compareAndSet(null, context to block))
                queued.complete(Unit)
            } else Dispatchers.IO.dispatch(context, block)
        }
        fun releaseIfHeld(): CountDownLatch? {
            val (context, block) = held.getAndSet(null) ?: return null
            val finished = CountDownLatch(1)
            Dispatchers.IO.dispatch(context, Runnable { try { block.run() } finally { finished.countDown() } })
            return finished
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) = BeaconViewModel::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.get(vm) as MutableStateFlow<T>
    }
    private fun field(vm: BeaconViewModel, name: String, value: Any?) = BeaconViewModel::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.set(vm, value)
    }

    private suspend fun withModel(initial: (AuthContext) -> AuthContext = { it }, block: suspend (BeaconViewModel, AuthContext) -> Unit) {
        check(BuildConfig.DEBUG && InstrumentationRegistry.getArguments().getString("native_ui_probe") == "1")
        check(!app.calls.state.value.active && app.calls.service == null)
        val previousPreferences = app.preferences.data.first()
        val previousDispatcher = app.auth.ioDispatcher
        val auth = initial(context(old.copy(origin = "https://lifetime-${UUID.randomUUID()}.example")))
        val selected = auth.scope().session("draft-chat")
        val models = ViewModelStore()
        val client = BeaconClient(auth, app.auth, persistContext = false, configureHttp = { builder ->
            builder.addInterceptor { chain ->
                check(chain.request().url.encodedPath in setOf("/api/fs/write-text", "/auth/logout", "/api/sessions"))
                response(chain.request(), 200, if (chain.request().url.encodedPath == "/api/sessions") """{"sessions":[]}""" else """{"ok":true}""")
            }
        })
        try {
            app.preferences.edit { it[stringPreferencesKey("origin")] = "" }
            app.auth.persist(auth)
            val vm = withContext(Dispatchers.Main.immediate) {
                ViewModelProvider(models, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java].also {
                    field(it, "originGeneration", 100)
                    field(it, "client", client)
                    field(it, "sourceScope", selected)
                    flow<AppState>(it, "_ui").value = AppState(origin = auth.origin, owner = auth.owner, profile = auth.profile, authenticated = true, connected = true, restoringSession = false)
                    flow<ChatState>(it, "_chat").value = ChatState(sessionId = selected.session)
                    flow<TextFieldValue>(it, "_composer").value = TextFieldValue("Private unsent draft")
                }
            }
            block(vm, auth)
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main.immediate) { models.clear(); client.closeNative() }
                (app.auth.ioDispatcher as? HoldOneWrite)?.releaseIfHeld()?.let { check(it.await(5, TimeUnit.SECONDS)) }
                app.auth.ioDispatcher = previousDispatcher
                app.auth.remove(auth.origin)
                for (kind in listOf("draft", "read-invalidated-at")) app.cache.delete(selected, kind)
                for (kind in listOf("read-invalidated-at", "phone-editors", "bot-mode-return", "conversation-index")) app.cache.delete(auth.scope(), kind)
                app.cache.delete(auth.scope().copy(profile = "", session = ""), "screens")
                app.preferences.updateData { previousPreferences }
            }
        }
    }

    private suspend fun lateActionWrite(initial: (AuthContext) -> AuthContext = { it }, replacement: (AuthContext) -> AuthContext?) = withModel(initial) { vm, auth ->
        val queue = HoldOneWrite()
        val staleSuccess = AtomicBoolean()
        app.auth.ioDispatcher = queue
        val action = withContext(Dispatchers.Main.immediate) {
            vm.action("/api/fs/write-text", json("path" to "/notes.md", "content" to "Confirmed mutation"), refresh = false, successNotice = "STALE SUCCESS") { _, _ -> staleSuccess.set(true) }
        }
        queue.queued.await() // The actual action succeeded and passed current(); its disk write has not started.
        withContext(Dispatchers.Main.immediate) { vm.logout() }.join()
        assertEquals(AuthContext(auth.origin, emptyList()), app.auth.read(auth.origin))
        val newer = replacement(auth)
        if (newer != null) app.auth.persist(newer) // Deliberate fresh sign-in must still be allowed after logout.
        queue.releaseIfHeld()!!
        action.join()
        assertEquals(newer ?: AuthContext(auth.origin, emptyList()), app.auth.read(auth.origin))
        assertFalse(staleSuccess.get())
        assertFalse(vm.ui.value.authenticated)
        assertFalse(vm.ui.value.connected)
        assertNotEquals("STALE SUCCESS", vm.ui.value.notice)
        val draft = requireNotNull(app.cache.read(auth.scope().session("draft-chat"), "draft"))
        assertEquals("Private unsent draft", Draft.decode(draft).text)
        if (newer != null && newer.scope() != auth.scope()) assertNull(app.cache.read(newer.scope().session("draft-chat"), "draft"))
    }

    @Test fun queuedSuccessfulActionCannotReviveSignedOutCredentials() = runBlocking {
        withTimeout(15_000) { lateActionWrite { null } }
    }

    @Test fun queuedSuccessfulActionCannotOverwriteAFreshSameAccountLogin() = runBlocking {
        withTimeout(15_000) { lateActionWrite { auth -> auth.copy(profile = "b", bearer = auth.bearer!!.copy(profile = "b", accessToken = "replacement", refreshToken = "replacement-refresh", issuedAt = 2)) } }
    }

    @Test fun queuedSuccessfulActionCannotOverwriteANewIssuerWithTheSameSubject() = runBlocking {
        withTimeout(15_000) { lateActionWrite { auth -> auth.copy(nativeProvider = "oidc", bearer = auth.bearer!!.copy(provider = "oidc", accessToken = "oidc-access", refreshToken = "oidc-refresh", issuedAt = 2)) } }
    }

    @Test fun queuedSuccessfulActionCannotOverwriteAnotherOwner() = runBlocking {
        withTimeout(15_000) { lateActionWrite { auth -> auth.copy(owner = "bob", bearer = auth.bearer!!.copy(owner = "bob", accessToken = "bob-access", refreshToken = "bob-refresh", issuedAt = 2)) } }
    }

    @Test fun queuedSuccessfulCookieActionCannotReviveSignedOutCredentials() = runBlocking {
        withTimeout(15_000) {
            lateActionWrite(initial = { auth -> auth.copy(bearer = null, cookies = listOf("__Host-hermes_session_rt=fixture; Path=/; Secure; HttpOnly")) }) { null }
        }
    }

    @Test fun queuedSuccessfulLoopbackActionCannotReviveSignedOutCredentials() = runBlocking {
        withTimeout(15_000) {
            lateActionWrite(initial = { auth -> auth.copy(owner = "local", bearer = null, nativeProvider = "loopback",
                loopbackSession = LoopbackSessionRecord("fixture-loopback", auth.origin, "local", verified = true, persisted = true)) }) { null }
        }
    }

    @Test fun logoutAfterTheWriteButBeforeItsMainContinuationSuppressesStaleSuccess() = runBlocking {
        withTimeout(15_000) { withModel { vm, auth ->
            val queue = HoldOneWrite()
            val staleSuccess = AtomicBoolean()
            app.auth.ioDispatcher = queue
            val action = withContext(Dispatchers.Main.immediate) {
                vm.action("/api/fs/write-text", json("path" to "/notes.md", "content" to "Confirmed mutation"), refresh = false) { _, _ -> staleSuccess.set(true) }
            }
            queue.queued.await()
            val logout = withContext(Dispatchers.Main.immediate) {
                // Main cannot resume action until this block exits; the real IO write completes first.
                assertTrue(queue.releaseIfHeld()!!.await(5, TimeUnit.SECONDS))
                assertEquals(auth.bearer, app.auth.read(auth.origin).bearer)
                vm.logout() // Advances the generation before action's already-enqueued continuation can run.
            }
            action.join(); logout.join()
            assertFalse(staleSuccess.get())
            assertNull(app.auth.read(auth.origin).bearer)
            assertFalse(vm.ui.value.connected)
        } }
    }

    private suspend fun queuedSnapshotKeepsRefresh(issuedAt: Long) = withModel(initial = { auth -> auth.copy(bearer = auth.bearer!!.copy(issuedAt = 200)) }) { vm, auth ->
        val queue = HoldOneWrite()
        val success = AtomicBoolean()
        app.auth.ioDispatcher = queue
        val action = withContext(Dispatchers.Main.immediate) {
            vm.action("/api/fs/write-text", json("path" to "/notes.md", "content" to "Confirmed mutation"), refresh = false) { _, _ -> success.set(true) }
        }
        queue.queued.await()
        val rotated = auth.bearer!!.copy(accessToken = "rotated", refreshToken = "rotated-refresh", issuedAt = issuedAt)
        assertTrue(app.auth.replaceBearer(auth.bearer!!, rotated)) // Refresh CAS deliberately does not advance the account epoch.
        queue.releaseIfHeld()!!
        action.join()
        assertEquals(rotated, app.auth.read(auth.origin).bearer)
        assertTrue(success.get())
        assertTrue(vm.ui.value.authenticated)
    }

    @Test fun queuedSnapshotKeepsAnExactRefreshWhenIssueSecondsTie() = runBlocking { withTimeout(15_000) { queuedSnapshotKeepsRefresh(200) } }
    @Test fun queuedSnapshotKeepsAnExactRefreshWhenTheClockMovesBack() = runBlocking { withTimeout(15_000) { queuedSnapshotKeepsRefresh(100) } }

    private fun cookieContext(auth: AuthContext) = auth.copy(bearer = null, cookies = listOf(Cookie.Builder()
        .name("__Host-hermes_session_rt").value("fixture-cookie").hostOnlyDomain(auth.origin.toHttpUrl().host).path("/").secure().httpOnly().build().toString()))

    private suspend fun cookieSnapshotCompletion(
        initial: (AuthContext) -> AuthContext = ::cookieContext,
        replacement: ((AuthContext) -> AuthContext)? = null,
        succeeds: Boolean = true,
    ) = withModel(initial) { vm, auth ->
        val queue = HoldOneWrite()
        val completed = AtomicBoolean()
        app.auth.ioDispatcher = queue
        val action = withContext(Dispatchers.Main.immediate) {
            vm.openForm(dev.hermes.android.ui.ActionForm("Edit synthetic note", "/api/fs/write-text", emptyList()))
            vm.action("/api/fs/write-text", json("path" to "/notes.md", "content" to "Confirmed mutation"), refresh = false, successNotice = "Confirmed cookie save") { _, _ ->
                completed.set(true); vm.dismissForm()
            }
        }
        queue.queued.await()
        withContext(Dispatchers.Main.immediate) { vm.refreshSessions(allProfiles = false) }.join()
        assertNotNull("History completed its real snapshot write", app.cache.read(auth.scope(), "conversation-index"))
        val stored = replacement?.invoke(auth) ?: auth
        if (replacement != null) app.auth.persist(stored)
        val version = app.auth.authorityEpoch.readVersion()
        queue.releaseIfHeld()!!
        action.join()
        assertEquals(stored, app.auth.read(auth.origin))
        assertEquals("A snapshot continuation must not change credential authority", version, app.auth.authorityEpoch.readVersion())
        assertEquals(succeeds, completed.get())
        assertEquals(succeeds, vm.form.value == null)
        assertEquals(succeeds, app.cache.read(auth.scope(), "read-invalidated-at") != null)
        if (succeeds) assertEquals("Confirmed cookie save", vm.ui.value.notice)
        else assertTrue(vm.ui.value.notice.contains("Sign-in changed"))
        assertTrue(vm.ui.value.authenticated)
    }

    @Test fun unchangedNativeCookieActionCompletesAfterConcurrentHistorySnapshot() = runBlocking {
        withTimeout(15_000) { cookieSnapshotCompletion() }
    }

    @Test fun cookieContinuationRejectsCredentialCsrfIssuerOwnerProfileAndBackendReplacement() = runBlocking {
        withTimeout(30_000) {
            for (replace in listOf<(AuthContext) -> AuthContext>(
                { it.copy(cookies = it.cookies.map { raw -> raw.replace("fixture-cookie", "replacement-cookie") }) },
                { it.copy(csrf = "replacement-csrf") }, { it.copy(nativeProvider = "oidc") },
                { it.copy(owner = "bob") }, { it.copy(profile = "b") }, { it.copy(backend = "other-backend") },
            )) cookieSnapshotCompletion(replacement = replace, succeeds = false)
        }
    }

    @Test fun cookieContinuationRejectsEmptyNonNativeExpiredAndUnsafeCredentials() = runBlocking {
        withTimeout(30_000) {
            for (cookies in listOf(emptyList(), listOf("tracking=fixture; path=/; secure; httponly"),
                listOf("__Host-hermes_session_rt=fixture; max-age=0; path=/; secure; httponly"),
                listOf("hermes_session_rt=fixture; path=/; httponly"),
                listOf("__Host-hermes_session_rt=; path=/; secure; httponly"),
                listOf("__Host-hermes_session_rt=fixture; path=/; secure"),
                listOf("hermes_session_rt=fixture; path=/other; secure; httponly"))) {
                // An unchanged but unsafe stored context still cannot acknowledge a fenced continuation.
                cookieSnapshotCompletion(initial = { cookieContext(it).copy(cookies = cookies) }, replacement = { it }, succeeds = false)
            }
        }
    }

    private suspend fun withNativeModel(
        initial: (AuthContext) -> AuthContext = { it },
        failAfterRefresh: Boolean = false,
        block: suspend (BeaconViewModel, AuthContext, NativeExperienceFixture) -> Unit,
    ) {
        check(!dev.hermes.android.push.KeepConnectedService.isEnabled(app) && !app.updates.hasPendingInstall()) { "Run saved-flow controls in an idle preview without Keep connected or a pending install" }
        val fixture = NativeExperienceFixture()
        val clients = mutableListOf<BeaconClient>()
        fun fixtureRequest(request: Request) = request.newBuilder().url(request.url.newBuilder().host("ui-fixture.invalid").build()).build()
        val sockets = object : okhttp3.WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: okhttp3.WebSocketListener) = fixture.newWebSocket(fixtureRequest(request), listener)
        }
        try {
            withModel(initial = { auth -> initial(context(auth.bearer!!.copy(owner = fixture.scope.owner,
                profile = fixture.scope.profile, provider = fixture.scope.nativeProvider, issuedAt = 200))) }) { vm, auth ->
                field(vm, "foreground", false) // Exercise connection persistence without unrelated startup work.
                fixture.currentRefreshToken = auth.bearer?.refreshToken.orEmpty()
                if (failAfterRefresh) fixture.forceExpiredAccessToken = auth.bearer!!.accessToken
                vm.profileSwitchClientFactoryForTest = { current ->
                    BeaconClient(current, app.auth, sockets, persistContext = false, configureHttp = { builder ->
                        builder.addInterceptor { chain ->
                            val request = chain.request()
                            val loopback = current.loopbackSession
                            check(request.url.host == auth.origin.removePrefix("https://"))
                            if (request.url.encodedPath == "/api/auth/me" && loopback != null) {
                                check(request.header(HERMES_LOOPBACK_SESSION_HEADER) == loopback.token)
                                response(request, 200, """{"user_id":"local","provider":"loopback"}""")
                            } else if (failAfterRefresh && request.url.encodedPath == "/api/auth/me" && request.header("Authorization") != "Bearer ${auth.bearer!!.accessToken}")
                                throw java.io.IOException("Synthetic identity read failed after refresh")
                            else chain.proceed(fixtureRequest(request))
                        }
                        fixture.configureHttp(builder)
                    }).also(clients::add)
                }
                block(vm, auth, fixture)
            }
        } finally { clients.forEach { it.closeNative() }; fixture.client.closeNative() }
    }

    @Test fun profileSwitchPersistsAfterAnInterveningSameAccountHistorySnapshot() = runBlocking {
        withTimeout(20_000) { withNativeModel { vm, auth, _ ->
            val queue = HoldOneWrite()
            app.auth.ioDispatcher = queue
            val switching = withContext(Dispatchers.Main.immediate) {
                field(vm, "client", requireNotNull(vm.profileSwitchClientFactoryForTest).invoke(auth))
                vm.switchProfile("coder")
            }
            queue.queued.await()
            withContext(Dispatchers.Main.immediate) { vm.refreshSessions(allProfiles = false) }.join()
            assertNotNull("History persisted before the held profile switch", app.cache.read(auth.scope(), "conversation-index"))
            queue.releaseIfHeld()!!
            switching.join()
            val stored = app.auth.read(auth.origin)
            assertEquals("coder", stored.profile)
            assertEquals(auth.bearer!!.copy(profile = "coder"), stored.bearer)
            assertEquals("coder", vm.ui.value.profile)
            assertEquals(stored, vm.client?.auth)
            assertFalse(vm.ui.value.notice.contains("Sign-in changed"))
        } }
    }

    @Test fun delayedHistorySnapshotKeepsTheNewlySelectedProfile() = runBlocking {
        withTimeout(20_000) { withNativeModel { vm, auth, _ ->
            val queue = HoldOneWrite()
            app.auth.ioDispatcher = queue
            val history = withContext(Dispatchers.Main.immediate) {
                field(vm, "client", requireNotNull(vm.profileSwitchClientFactoryForTest).invoke(auth))
                vm.refreshSessions(allProfiles = false)
            }
            queue.queued.await()
            withContext(Dispatchers.Main.immediate) { vm.switchProfile("coder") }.join()
            assertEquals("coder", app.auth.read(auth.origin).profile)
            queue.releaseIfHeld()!!
            history.join()
            assertEquals("coder", app.auth.read(auth.origin).profile)
            assertEquals("coder", app.auth.read(auth.origin).bearer?.profile)
            assertEquals("coder", vm.client?.auth?.profile)
        } }
    }

    @Test fun delayedHistoryCannotReplaceCookiesRetainedByProfileBootstrapOrReconnect() = runBlocking {
        withTimeout(20_000) { withNativeModel(initial = ::cookieContext) { vm, auth, _ ->
            val queue = HoldOneWrite()
            app.auth.ioDispatcher = queue
            val factory = requireNotNull(vm.profileSwitchClientFactoryForTest)
            val active = factory(auth)
            val history = withContext(Dispatchers.Main.immediate) {
                field(vm, "client", active)
                vm.refreshSessions(allProfiles = false)
            }
            queue.queued.await()
            val rotated = requireNotNull(Cookie.parse(auth.origin.toHttpUrl(), "__Host-hermes_session_rt=rotated-cookie; Path=/; Secure; HttpOnly"))
            // Deliver native response cookies through the real jar before the detached profile bootstrap.
            active.http.cookieJar.saveFromResponse(auth.origin.toHttpUrl().resolve("/api/auth/me")!!, listOf(rotated))
            val version = app.auth.authorityEpoch.readVersion()
            withContext(Dispatchers.Main.immediate) { vm.switchProfile("coder") }.join()
            assertEquals("coder", vm.ui.value.profile)
            assertEquals(listOf(rotated.toString()), app.auth.read(auth.origin).cookies)
            queue.releaseIfHeld()!!
            history.join()
            assertEquals(listOf(rotated.toString()), app.auth.read(auth.origin).cookies)
            assertEquals("coder", app.auth.read(auth.origin).profile)
            assertEquals(version, app.auth.authorityEpoch.readVersion())
            val reconnectCookies = AtomicReference<List<String>>()
            vm.profileSwitchClientFactoryForTest = { current -> reconnectCookies.set(current.cookies); factory(current) }
            withContext(Dispatchers.Main.immediate) { vm.connect(auth.origin) }.join()
            assertEquals(listOf(rotated.toString()), reconnectCookies.get())
            assertEquals(listOf(rotated), vm.client!!.http.cookieJar.loadForRequest(auth.origin.toHttpUrl()))
            assertEquals(listOf(rotated.toString()), app.auth.read(auth.origin).cookies)
            assertTrue(vm.ui.value.authenticated)
        } }
    }

    @Test fun detachedBackgroundProfileResponseRetainsRotatedCookiesWithoutSelectingItsProfile() = runBlocking {
        withTimeout(15_000) { withModel(initial = { cookieContext(it).copy(cookies = listOf("__Host-hermes_session_rt=fixture-cookie; Path=/; Secure; HttpOnly")) }) { _, auth ->
            lateinit var detached: BeaconClient
            val rotated = requireNotNull(Cookie.parse(auth.origin.toHttpUrl(), "__Host-hermes_session_rt=detached-rotation; Path=/; Secure; HttpOnly"))
            val source = BeaconClient(auth, app.auth, persistContext = false, configureHttp = { builder ->
                builder.addInterceptor { chain ->
                    detached.http.cookieJar.saveFromResponse(chain.request().url, listOf(rotated))
                    response(chain.request(), 200, """{"ok":true}""")
                }
            })
            detached = source.detached()
            try {
                val version = app.auth.authorityEpoch.readVersion()
                assertTrue(app.auth.persistIfUnchanged(auth.copy(profile = "b"), version, snapshot = true, selectProfileIf = { true }))
                detached.nativeGetForProfile("/api/plugins/beacon/mobile/notifications/events", auth.profile, listOf(auth.profile))
                assertEquals(listOf(rotated.toString()), detached.auth.cookies)
                assertEquals(listOf(rotated.toString()), app.auth.read(auth.origin).cookies)
                assertEquals("b", app.auth.read(auth.origin).profile)
                assertEquals(version, app.auth.authorityEpoch.readVersion())
                assertFalse(app.auth.replaceCookies(auth, auth.cookies, version))
                assertEquals(listOf(rotated.toString()), app.auth.read(auth.origin).cookies)
                assertEquals(auth, source.auth)
            } finally { detached.closeNative(); source.closeNative() }
        } }
    }

    @Test fun downloadAndWorkspaceUploadRetainRotationsAndUseThemOnTheNextRequest() = runBlocking {
        withTimeout(30_000) {
            for (upload in listOf(false, true)) for (status in listOf(200, 503)) withModel(initial = ::cookieContext) { _, auth ->
                val file = java.io.File.createTempFile("cookie-transfer", ".txt", app.cacheDir).apply { writeText("fixture") }
                val path = if (upload) "/api/files/upload-stream" else "/api/fs/download"
                val rotated = requireNotNull(Cookie.parse(auth.origin.toHttpUrl(), "__Host-hermes_session_rt=transfer-rotation; Path=/; Secure; HttpOnly"))
                val sent = mutableListOf<List<Cookie>>()
                lateinit var detached: BeaconClient
                val source = BeaconClient(auth, app.auth, persistContext = false, configureHttp = { builder ->
                    builder.addInterceptor { chain ->
                        val request = chain.request()
                        // Synthetic transport exercises the same jar callbacks as OkHttp's network bridge.
                        sent += detached.http.cookieJar.loadForRequest(request.url)
                        val transfer = request.url.encodedPath == path
                        if (transfer) detached.http.cookieJar.saveFromResponse(request.url, listOf(rotated))
                        response(request, if (transfer) status else 200, """{"ok":true}""")
                    }
                })
                detached = source.detached()
                try {
                    val version = app.auth.authorityEpoch.readVersion()
                    val result = runCatching {
                        if (upload) detached.upload("/api/workspace/upload", file, "fixture.txt", "text/plain", "fixture-session", directory = "/fixture")
                        else detached.copyTo("/api/fs/download", java.io.ByteArrayOutputStream())
                    }
                    if (status == 200) result.getOrThrow() else assertEquals(status, (result.exceptionOrNull() as ApiFailure).status)
                    assertEquals(auth.copy(cookies = listOf(rotated.toString())), app.auth.read(auth.origin))
                    assertEquals(listOf(rotated.toString()), detached.auth.cookies)
                    detached.nativeRequest("/api/files")
                    assertEquals(listOf("fixture-cookie", "transfer-rotation"), sent.map { it.single().value })
                    assertEquals(version, app.auth.authorityEpoch.readVersion())
                    assertFalse(app.auth.replaceCookies(auth, auth.cookies, version))
                } finally { detached.closeNative(); source.closeNative(); file.delete() }
            }
        }
    }

    @Test fun optionalProbeRetainsOnlyNativeSessionCookiesWithoutChangingVerifiedIdentity() = runBlocking {
        withTimeout(30_000) {
            for ((status, owner) in listOf(200 to "alice", 200 to "another-owner", 503 to "alice")) withModel(initial = ::cookieContext) { _, auth ->
                val rotated = requireNotNull(Cookie.parse(auth.origin.toHttpUrl(), "__Host-hermes_session_rt=probe-rotation; Path=/; Secure; HttpOnly"))
                val sent = mutableListOf<List<Cookie>>()
                lateinit var executing: BeaconClient
                val source = BeaconClient(auth, app.auth, persistContext = false, configureHttp = { builder ->
                    builder.addInterceptor { chain ->
                        val request = chain.request()
                        sent += executing.http.cookieJar.loadForRequest(request.url)
                        val probe = request.url.encodedPath == "/api/plugins/beacon/mobile/bootstrap"
                        if (probe) executing.http.cookieJar.saveFromResponse(request.url, listOf(rotated,
                            requireNotNull(Cookie.parse(request.url, "preferences=plugin; Path=/; Secure"))))
                        response(request, if (probe) status else 200, if (probe)
                            """{"contract_version":2,"backend":"hermes-dashboard","owner_id":"$owner","profile":"${auth.profile}"}""" else """{"ok":true}""")
                    }
                })
                val probe = source.detached()
                executing = probe
                try {
                    val projection = probe.probeOptionalFeatures()
                    assertEquals(status == 200 && owner == auth.owner, projection.getString("plugin") == "available")
                    assertEquals(auth.copy(cookies = listOf(rotated.toString())), probe.auth)
                    assertEquals(auth.copy(cookies = listOf(rotated.toString())), app.auth.read(auth.origin))
                    executing = source
                    source.nativeRequest("/api/files")
                    assertEquals(listOf("fixture-cookie", "probe-rotation"), sent.map { it.single().value })
                    assertEquals(auth.copy(cookies = listOf(rotated.toString())), source.auth)
                } finally { probe.closeNative(); source.closeNative() }
            }
        }
    }

    @Test fun detachedCookieRotationReachesTheSourceAndLaterCopiesBeforeTheirNextRequest() = runBlocking {
        withTimeout(15_000) { withModel(initial = ::cookieContext) { _, auth ->
            val sent = mutableListOf<List<Cookie>>()
            // Max-Age response expiry can have milliseconds that the persisted cookie text loses.
            var rotation: Cookie? = Cookie.Builder().name("__Host-hermes_session_rt").value("first-rotation")
                .hostOnlyDomain(auth.origin.toHttpUrl().host).path("/").secure().httpOnly().expiresAt(4_000_000_000_123L).build()
            lateinit var executing: BeaconClient
            val source = BeaconClient(auth, app.auth, persistContext = false, configureHttp = { builder ->
                builder.addInterceptor { chain ->
                    val request = chain.request()
                    sent += executing.http.cookieJar.loadForRequest(request.url)
                    rotation?.let { executing.http.cookieJar.saveFromResponse(request.url, listOf(it)); rotation = null }
                    response(request, 200, """{"ok":true}""")
                }
            })
            val first = source.detached()
            var later: BeaconClient? = null
            try {
                executing = first
                first.nativeRequest("/api/files")
                assertEquals("first-rotation", Cookie.parse(auth.origin.toHttpUrl(), app.auth.read(auth.origin).cookies.single())!!.value)
                // Construct while the source still has its old snapshot; both must adopt before sending.
                val copy = source.detached().also { later = it }
                executing = source
                source.nativeRequest("/api/files")
                executing = copy
                copy.nativeRequest("/api/files")
                assertEquals(listOf("fixture-cookie", "first-rotation", "first-rotation"), sent.map { it.single().value })
                // Adopting also advances the exact CAS baseline for the next rotation.
                rotation = requireNotNull(Cookie.parse(auth.origin.toHttpUrl(), "__Host-hermes_session_rt=second-rotation; Path=/; Secure; HttpOnly"))
                executing = source
                source.nativeRequest("/api/files")
                executing = first
                first.nativeRequest("/api/files")
                assertEquals(listOf("fixture-cookie", "first-rotation", "first-rotation", "first-rotation", "second-rotation"), sent.map { it.single().value })
                assertEquals(source.auth.cookies, app.auth.read(auth.origin).cookies)
                assertEquals(source.auth.cookies, first.auth.cookies)
                val pending = requireNotNull(Cookie.parse(auth.origin.toHttpUrl(), "__Host-hermes_session_rt=pending-response; Path=/; Secure; HttpOnly"))
                copy.http.cookieJar.saveFromResponse(auth.origin.toHttpUrl(), listOf(pending))
                assertEquals(listOf(pending), copy.http.cookieJar.loadForRequest(auth.origin.toHttpUrl()))
                assertEquals(source.auth.cookies, app.auth.read(auth.origin).cookies)
            } finally { later?.closeNative(); first.closeNative(); source.closeNative() }
        } }
    }

    @Test fun capturedCookieViewsCannotAdoptLogoutOrFreshAccountAdmissions() = runBlocking {
        withTimeout(30_000) {
            for (replacement in listOf<(AuthContext) -> AuthContext?>(
                { null }, { it }, { it.copy(nativeProvider = "oidc") }, { it.copy(owner = "bob") },
            )) withModel(initial = ::cookieContext) { _, auth ->
                val source = BeaconClient(auth, app.auth, persistContext = false)
                val first = source.detached()
                var later: BeaconClient? = null
                var cleanup: BeaconClient? = null
                try {
                    app.auth.remove(auth.origin)
                    val newer = replacement(auth)?.copy(cookies = listOf("__Host-hermes_session_rt=fresh-admission; Path=/; Secure; HttpOnly"))
                    if (newer != null) app.auth.persist(newer)
                    val copy = source.detached().also { later = it }
                    val logout = source.detachedForLogout().also { cleanup = it }
                    for (captured in listOf(source, first, copy, logout)) {
                        assertEquals("fixture-cookie", captured.http.cookieJar.loadForRequest(auth.origin.toHttpUrl()).single().value)
                        captured.retainCookieRotation()
                        assertEquals(newer ?: AuthContext(auth.origin, emptyList()), app.auth.read(auth.origin))
                    }
                } finally { cleanup?.closeNative(); later?.closeNative(); first.closeNative(); source.closeNative() }
            }
        }
    }

    @Test fun detachedCookieRotationCannotPersistAcrossLogoutFreshAdmissionOrLogoutCleanup() = runBlocking {
        withTimeout(30_000) {
            for (replacement in listOf<(AuthContext) -> AuthContext?>(
                { null }, { it }, { it.copy(nativeProvider = "oidc") }, { it.copy(owner = "bob") },
                { it.copy(cookies = it.cookies.map { raw -> raw.replace("fixture-cookie", "late-rotation") }) },
            )) withModel(initial = ::cookieContext) { _, auth ->
                val arrived = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val firstResponse = AtomicBoolean(true)
                lateinit var detached: BeaconClient
                val source = BeaconClient(auth, app.auth, persistContext = false, configureHttp = { builder ->
                    builder.addInterceptor { chain ->
                        arrived.complete(Unit)
                        check(release.await(5, TimeUnit.SECONDS))
                        val value = if (firstResponse.compareAndSet(true, false)) "late-rotation" else "later-rotation"
                        detached.http.cookieJar.saveFromResponse(chain.request().url, listOf(requireNotNull(Cookie.parse(chain.request().url,
                            "__Host-hermes_session_rt=$value; Path=/; Secure; HttpOnly"))))
                        response(chain.request(), 200, """{"ok":true}""")
                    }
                })
                detached = source.detached()
                try {
                    val work = async(Dispatchers.IO) { detached.nativeRequest("/api/files") }
                    arrived.await()
                    app.auth.remove(auth.origin)
                    val newer = replacement(auth)
                    if (newer != null) app.auth.persist(newer)
                    release.countDown()
                    work.await()
                    assertEquals(newer ?: AuthContext(auth.origin, emptyList()), app.auth.read(auth.origin))
                    // Even a later request from this captured view cannot adopt the replacement's epoch.
                    detached.nativeRequest("/api/files")
                    assertEquals(newer ?: AuthContext(auth.origin, emptyList()), app.auth.read(auth.origin))
                    detached.closeNative()
                    detached = source.detachedForLogout()
                    detached.nativeRequest("/api/files")
                    assertEquals(newer ?: AuthContext(auth.origin, emptyList()), app.auth.read(auth.origin))
                } finally { release.countDown(); detached.closeNative(); source.closeNative() }
            }
        }
    }

    @Test fun anOldDefaultPersistingBootstrapCannotReviveLogout() = runBlocking {
        withTimeout(15_000) { withModel(initial = ::cookieContext) { _, auth ->
            val source = BeaconClient(auth, app.auth, configureHttp = { builder ->
                builder.addInterceptor { chain ->
                    val body = when (chain.request().url.encodedPath) {
                        "/api/auth/me" -> """{"user_id":"alice","provider":"basic"}"""
                        "/api/profiles" -> """{"profiles":[{"name":"a"}]}"""
                        else -> error("Unexpected native bootstrap request")
                    }
                    response(chain.request(), 200, body)
                }
            })
            try {
                app.auth.remove(auth.origin)
                source.bootstrap()
                assertEquals(AuthContext(auth.origin, emptyList()), app.auth.read(auth.origin))
            } finally { source.closeNative() }
        } }
    }

    @Test fun aSupersededQueuedProfileSwitchCannotReplaceTheCurrentSelection() = runBlocking {
        withTimeout(20_000) { withNativeModel { vm, auth, _ ->
            val queue = HoldOneWrite()
            app.auth.ioDispatcher = queue
            val first = withContext(Dispatchers.Main.immediate) {
                field(vm, "client", requireNotNull(vm.profileSwitchClientFactoryForTest).invoke(auth))
                vm.switchProfile("coder")
            }
            queue.queued.await()
            withContext(Dispatchers.Main.immediate) { vm.switchProfile("default") }.join()
            queue.releaseIfHeld()!!
            first.join()
            assertEquals("default", app.auth.read(auth.origin).profile)
            assertEquals("default", app.auth.read(auth.origin).bearer?.profile)
            assertEquals("default", vm.client?.auth?.profile)
            assertEquals("default", vm.ui.value.profile)
        } }
    }

    @Test fun cookieOnlyNewChatSelectsItsCreatedConversationAfterAHistorySnapshot() = runBlocking {
        withTimeout(20_000) { withNativeModel(initial = ::cookieContext) { vm, auth, fixture ->
            val queue = HoldOneWrite()
            app.auth.ioDispatcher = queue
            val active = requireNotNull(vm.profileSwitchClientFactoryForTest).invoke(auth)
            val opening = withContext(Dispatchers.Main.immediate) {
                field(vm, "client", active)
                vm.newChat()
            }
            queue.queued.await()
            // A harmless cookie change makes the two real snapshots differ without changing native credentials.
            active.jar.saveFromResponse(auth.origin.toHttpUrl(), listOf(Cookie.Builder().name("fixture_preferences")
                .value("history").hostOnlyDomain(auth.origin.toHttpUrl().host).path("/").secure().build()))
            withContext(Dispatchers.Main.immediate) { vm.refreshSessions(allProfiles = false) }.join()
            assertNotNull("History persisted while new-chat persistence was held", app.cache.read(auth.scope(), "conversation-index"))
            assertNotEquals(auth.cookies, app.auth.read(auth.origin).cookies)
            queue.releaseIfHeld()!!
            opening.join()
            withTimeout(5_000) { vm.chat.first { it.sessionId.startsWith("new-") } }
            val selected = vm.chat.value.sessionId
            assertTrue(fixture.sessions.containsKey(selected))
            assertEquals(1, fixture.requests.count { it.optString("method") == "session.create" })
            assertNotEquals("draft-chat", selected)
            assertEquals(auth.owner, app.auth.read(auth.origin).owner)
            assertNull(app.auth.read(auth.origin).bearer)
            assertTrue(vm.ui.value.authenticated)
            assertFalse(vm.ui.value.notice.contains("Reconnect to continue"))
        } }
    }

    @Test fun logoutPushCommitFailureStillRemovesAuthAndTearsDownLocalState() = runBlocking {
        withTimeout(20_000) { withModel { vm, auth ->
            val push = app.push
            val prefsField = push.javaClass.getDeclaredField("privatePrefs").apply { isAccessible = true }
            val previous = prefsField.get(push) as SharedPreferences
            val failed = AtomicBoolean()
            val failing = object : SharedPreferences by previous {
                override fun edit(): SharedPreferences.Editor {
                    val edit = previous.edit()
                    return object : SharedPreferences.Editor by edit {
                        override fun commit(): Boolean {
                            return if (failed.compareAndSet(false, true)) false else edit.commit()
                        }
                    }
                }
            }
            val keep = app.getSharedPreferences(dev.hermes.android.push.KeepConnectedService.PREFS, Context.MODE_PRIVATE)
            val oldOrigin = keep.getString(dev.hermes.android.push.KeepConnectedService.KEY_ORIGIN, null)
            val oldStatus = keep.getString(dev.hermes.android.push.KeepConnectedService.KEY_STATUS, null)
            val source = "logout-${UUID.randomUUID()}"
            val stream = Job()
            val watch = Job()
            val selection = Job()
            try {
                assertFalse(dev.hermes.android.push.KeepConnectedService.isEnabled(app))
                assertTrue(push.bindKeepConnectedSource(auth.scope(), source))
                check(keep.edit().putString(dev.hermes.android.push.KeepConnectedService.KEY_ORIGIN, auth.origin)
                    .putString(dev.hermes.android.push.KeepConnectedService.KEY_STATUS, dev.hermes.android.push.KeepConnectedService.STATUS_CONNECTED).commit())
                withContext(Dispatchers.Main.immediate) {
                    field(vm, "streams", mutableMapOf("fixture" to stream))
                    field(vm, "sessionWatch", watch)
                    field(vm, "selectionJob", selection)
                    vm.openForm(dev.hermes.android.ui.ActionForm("Synthetic private editor", "/api/fs/write-text", emptyList()))
                    prefsField.set(push, failing)
                    vm.logout()
                }.join()
                assertTrue("The real local push commit failed", failed.get())
                assertEquals(AuthContext(auth.origin, emptyList()), app.auth.read(auth.origin))
                assertEquals(dev.hermes.android.push.KeepConnectedService.STATUS_SIGNED_OUT, dev.hermes.android.push.KeepConnectedService.status(app))
                assertTrue(stream.isCancelled && watch.isCancelled && selection.isCancelled)
                assertNull(vm.client)
                assertFalse(vm.ui.value.authenticated)
                assertFalse(vm.ui.value.connected)
                assertTrue(vm.ui.value.setupWizard)
                assertEquals("", vm.ui.value.owner)
                assertEquals("", vm.chat.value.sessionId)
                assertEquals("", vm.composer.value.text)
                assertNull(vm.form.value)
                assertTrue(vm.ui.value.notice.contains("Push notification cleanup"))
                assertEquals("Private unsent draft", Draft.decode(requireNotNull(app.cache.read(auth.scope().session("draft-chat"), "draft"))).text)
            } finally {
                prefsField.set(push, previous)
                stream.cancel(); watch.cancel(); selection.cancel()
                check(previous.edit().remove("source:" + source.digest()).remove("active:" + auth.scope().key).commit())
                check(keep.edit().apply {
                    if (oldOrigin == null) remove(dev.hermes.android.push.KeepConnectedService.KEY_ORIGIN) else putString(dev.hermes.android.push.KeepConnectedService.KEY_ORIGIN, oldOrigin)
                    if (oldStatus == null) remove(dev.hermes.android.push.KeepConnectedService.KEY_STATUS) else putString(dev.hermes.android.push.KeepConnectedService.KEY_STATUS, oldStatus)
                }.commit())
            }
        } }
    }

    private suspend fun savedFlowKeepsRefresh(flow: String, backward: Boolean) = withNativeModel(failAfterRefresh = flow == "recovery") { vm, auth, fixture ->
        val queue = HoldOneWrite()
        val source = "lifetime-${UUID.randomUUID()}"
        val event = "lifetime-event"
        val previousFactory = dev.hermes.android.push.KeepConnectedService.clientFactoryForTest
        try {
            if (flow == "notification") {
                assertTrue(app.push.bindKeepConnectedSource(auth.scope(), source))
                fixture.incomingNotification = json("event_id" to event, "profile" to auth.profile, "source_ref" to source, "kind" to "task_result")
                dev.hermes.android.push.KeepConnectedService.clientFactoryForTest = { current, _ -> requireNotNull(vm.profileSwitchClientFactoryForTest).invoke(current) }
            }
            app.auth.ioDispatcher = queue
            val work = withContext(Dispatchers.Main.immediate) {
                if (flow == "notification") vm.handlePush(source, event) else vm.connect(auth.origin)
            }
            queue.queued.await() // Holds the actual reconnect, recovery-catch or notification disk write.
            val captured = requireNotNull(app.auth.read(auth.origin).bearer)
            if (flow == "recovery") {
                assertEquals(listOf(auth.bearer!!.refreshToken), fixture.refreshTokensUsed.toList())
                assertNotEquals(auth.bearer, captured)
            }
            val version = app.auth.authorityEpoch.readVersion()
            val rotated = captured.copy(accessToken = "winning-access", refreshToken = "winning-refresh", issuedAt = captured.issuedAt - if (backward) 1 else 0)
            assertTrue(app.auth.replaceBearer(captured, rotated))
            assertEquals(version, app.auth.authorityEpoch.readVersion())
            queue.releaseIfHeld()!!
            work.join()
            assertEquals(rotated, app.auth.read(auth.origin).bearer)
            if (flow == "recovery") assertEquals(APP_CONNECTION_ERROR, vm.ui.value.error)
            else {
                assertTrue(vm.ui.value.authenticated)
                assertEquals(auth.nativeProvider, vm.client?.auth?.nativeProvider)
                if (flow == "notification") {
                    assertEquals("Tasks", vm.ui.value.tab)
                    assertEquals(rotated, vm.client?.auth?.bearer)
                }
            }
        } finally {
            dev.hermes.android.push.KeepConnectedService.clientFactoryForTest = previousFactory
            check(app.getSharedPreferences("push-binding", Context.MODE_PRIVATE).edit().remove("source:" + source.digest()).commit())
        }
    }

    @Test fun savedReconnectKeepsExactRefreshWhenIssueSecondsTie() = runBlocking { withTimeout(20_000) { savedFlowKeepsRefresh("reconnect", false) } }
    @Test fun savedReconnectKeepsExactRefreshWhenClockMovesBack() = runBlocking { withTimeout(20_000) { savedFlowKeepsRefresh("reconnect", true) } }
    @Test fun savedReconnectRecoveryKeepsExactRefreshWhenIssueSecondsTie() = runBlocking { withTimeout(20_000) { savedFlowKeepsRefresh("recovery", false) } }
    @Test fun savedReconnectRecoveryKeepsExactRefreshWhenClockMovesBack() = runBlocking { withTimeout(20_000) { savedFlowKeepsRefresh("recovery", true) } }
    @Test fun notificationOpeningKeepsExactRefreshWhenIssueSecondsTie() = runBlocking { withTimeout(20_000) { savedFlowKeepsRefresh("notification", false) } }
    @Test fun notificationOpeningKeepsExactRefreshWhenClockMovesBack() = runBlocking { withTimeout(20_000) { savedFlowKeepsRefresh("notification", true) } }

    private fun connectFresh(vm: BeaconViewModel, auth: AuthContext): Job = BeaconViewModel::class.java
        .getDeclaredMethod("connect", String::class.java, AuthContext::class.java).let {
            it.isAccessible = true; it.invoke(vm, auth.origin, auth) as Job
        }

    @Test fun freshSameSecondSignInInstallsItsIncomingCredentials() = runBlocking {
        withTimeout(20_000) { withNativeModel { vm, auth, _ ->
            val signedIn = auth.copy(bearer = auth.bearer!!.copy(accessToken = "fresh-login", refreshToken = "fresh-login-refresh"))
            withContext(Dispatchers.Main.immediate) { connectFresh(vm, signedIn) }.join()
            assertEquals(signedIn.bearer, app.auth.read(auth.origin).bearer)
            assertTrue(vm.ui.value.authenticated)
        } }
    }

    @Test fun freshLoopbackAdmissionInstallsOnlyAfterNativeIdentityVerification() = runBlocking {
        withTimeout(20_000) { withNativeModel { vm, auth, _ ->
            val session = LoopbackSessionRecord("fresh-loopback", auth.origin, "local", verified = true)
            withContext(Dispatchers.Main.immediate) { connectFresh(vm, AuthContext(auth.origin, emptyList(), owner = "local", backend = "hermes-dashboard", loopbackSession = session)) }.join()
            val stored = app.auth.read(auth.origin)
            assertEquals(session.copy(verified = true, persisted = true), stored.loopbackSession)
            assertEquals("loopback", stored.nativeProvider)
            assertNull(stored.bearer)
            assertTrue(vm.ui.value.authenticated)
        } }
    }

    @Test fun savedUnstampedNativeCookiesAndBearersLearnTheirVerifiedIssuer() = runBlocking {
        withTimeout(30_000) {
            for (cookie in listOf(false, true)) withNativeModel(initial = { auth ->
                (if (cookie) cookieContext(auth) else auth.copy(bearer = auth.bearer!!.copy(provider = ""))).copy(nativeProvider = "")
            }) { vm, auth, fixture ->
                withContext(Dispatchers.Main.immediate) { vm.connect(auth.origin) }.join()
                val stored = app.auth.read(auth.origin)
                assertEquals(fixture.scope.nativeProvider, stored.nativeProvider)
                assertEquals(auth.owner, stored.owner)
                assertEquals(auth.profile, stored.profile)
                if (cookie) assertEquals(auth.cookies, stored.cookies)
                else assertEquals(auth.bearer!!.copy(provider = fixture.scope.nativeProvider), stored.bearer)
                assertTrue(vm.ui.value.authenticated)
            }
        }
    }

    private suspend fun withStore(block: suspend (AuthStore) -> Unit) {
        val name = "auth-lifetime-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(app) {
            override fun getSharedPreferences(ignored: String, mode: Int) = app.getSharedPreferences(name, mode)
        }
        try { block(AuthStore(isolated, AppCipher())) } finally { app.deleteSharedPreferences(name) }
    }

    private suspend fun retainedProfile(alreadyRotated: Boolean = false, switchDuringRefresh: Boolean = false) = withStore { store ->
        val auth = context(old)
        store.write(auth)
        val requests = mutableListOf<Request>()
        val source = BeaconClient(auth, store, persistContext = false, configureHttp = { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request(); requests += request
                val refresh = request.url.encodedPath == "/auth/native/refresh"
                if (refresh && switchDuringRefresh) store.write(context(old.copy(profile = "c")))
                val status = if (refresh || request.header("Authorization") == "Bearer rotated") 200 else 401
                val body = if (refresh) """{"access_token":"rotated","refresh_token":"rotated-refresh","token_type":"Bearer","provider":"basic","user_id":"alice"}"""
                    else if (status == 200) """{"ok":true}""" else """{"error":"session_expired"}"""
                response(request, status, body)
            }
        })
        val retained = source.detached()
        try {
            // Matches the saved B selection after a real UI switch, without rebinding source/retained A.
            store.write(context(old.copy(profile = "b", accessToken = if (alreadyRotated) "rotated" else old.accessToken,
                refreshToken = if (alreadyRotated) "rotated-refresh" else old.refreshToken, issuedAt = if (alreadyRotated) 2 else old.issuedAt)))
            assertTrue(retained.nativeRequest("/api/files", params = mapOf("profile" to "a")).getBoolean("ok"))
            assertEquals(if (alreadyRotated) 0 else 1, requests.count { it.url.encodedPath == "/auth/native/refresh" })
            assertEquals(2, requests.count { it.url.encodedPath == "/api/files" })
            assertTrue(requests.filter { it.url.encodedPath == "/api/files" }.all { it.url.queryParameter("profile") == "a" })
            assertEquals("a", retained.auth.profile)
            assertEquals("a", source.auth.profile)
            assertEquals("a", source.auth.bearer?.profile)
            assertEquals("rotated", source.auth.bearer?.accessToken)
            val selected = store.read(old.origin)
            assertEquals(if (switchDuringRefresh) "c" else "b", selected.profile)
            assertEquals(selected.profile, selected.bearer?.profile)
            assertEquals("rotated", selected.bearer?.accessToken)
            assertEquals("rotated-refresh", selected.bearer?.refreshToken)
        } finally { retained.closeNative(); source.closeNative() }
    }

    @Test fun detachedARefreshesWhileBRemainsSelected() = runBlocking { withTimeout(15_000) { retainedProfile() } }
    @Test fun detachedAAdoptsBRotationWithoutAnotherRefresh() = runBlocking { withTimeout(15_000) { retainedProfile(alreadyRotated = true) } }
    @Test fun detachedARefreshCannotOverwriteBToCSelectionDuringTheRequest() = runBlocking { withTimeout(15_000) { retainedProfile(switchDuringRefresh = true) } }

    @Test fun detachedARejectsLoggedOutForeignProviderOwnerAndOriginBeforeRefresh() = runBlocking {
        withTimeout(15_000) {
            for (replacement in listOf(null, old.copy(profile = "b", provider = "oidc"), old.copy(profile = "b", owner = "bob"), old.copy(origin = "https://other.example"))) withStore { store ->
                store.write(context(old))
                val requests = mutableListOf<Request>()
                val source = BeaconClient(context(old), store, persistContext = false, configureHttp = { builder ->
                    builder.addInterceptor { chain ->
                        requests += chain.request()
                        response(chain.request(), 401, """{"error":"session_expired"}""")
                    }
                })
                val retained = source.detached()
                try {
                    store.clear(old.origin)
                    replacement?.let { store.write(context(it)) }
                    assertTrue(runCatching { retained.nativeRequest("/api/files", params = mapOf("profile" to "a")) }.exceptionOrNull() is NativeAuthException)
                    assertEquals(listOf("/api/files"), requests.map { it.url.encodedPath })
                    assertEquals(replacement?.takeIf { it.origin == old.origin }, store.read(old.origin).bearer)
                    if (replacement?.origin != old.origin && replacement != null) assertEquals(replacement, store.read(replacement.origin).bearer)
                } finally { retained.closeNative(); source.closeNative() }
            }
        }
    }

    @Test fun refreshCannotCommitAfterLogoutOrExactTokenOrIssuerReplacement() = runBlocking {
        withTimeout(15_000) {
            for (replacement in listOf(null, old.copy(profile = "b", accessToken = "new-login", refreshToken = "new-login-refresh", issuedAt = 2), old.copy(profile = "b", provider = "oidc"))) withStore { store ->
                store.write(context(old.copy(profile = "b")))
                val requests = mutableListOf<Request>()
                val source = BeaconClient(context(old), store, persistContext = false, configureHttp = { builder ->
                    builder.addInterceptor { chain ->
                        val request = chain.request(); requests += request
                        val refresh = request.url.encodedPath == "/auth/native/refresh"
                        if (refresh) {
                            store.clear(old.origin)
                            replacement?.let { store.write(context(it)) }
                        }
                        response(request, if (refresh) 200 else 401,
                            if (refresh) """{"access_token":"rotated","refresh_token":"rotated-refresh","token_type":"Bearer","provider":"basic","user_id":"alice"}""" else """{"error":"session_expired"}""")
                    }
                })
                val retained = source.detached()
                try {
                    assertTrue(runCatching { retained.nativeRequest("/api/files", params = mapOf("profile" to "a")) }.exceptionOrNull() is NativeAuthException)
                    assertEquals(listOf("/api/files", "/auth/native/refresh"), requests.map { it.url.encodedPath })
                    assertEquals(replacement, store.read(old.origin).bearer)
                    assertEquals("a", retained.auth.profile)
                } finally { retained.closeNative(); source.closeNative() }
            }
        }
    }

    @Test fun rejectedRefreshClearsTheSameAccountAcrossProfilesButKeepsAReplacement() = runBlocking {
        withTimeout(15_000) {
            for (replacement in listOf(old.copy(profile = "b"), old.copy(profile = "b", accessToken = "replacement", refreshToken = "replacement-refresh", issuedAt = 2), old.copy(profile = "b", provider = "oidc"))) withStore { store ->
                store.write(context(old.copy(profile = "b")))
                val source = BeaconClient(context(old), store, persistContext = false, configureHttp = { builder ->
                    builder.addInterceptor { chain ->
                        if (chain.request().url.encodedPath == "/auth/native/refresh") store.write(context(replacement))
                        response(chain.request(), 401, """{"error":"session_expired"}""")
                    }
                })
                val retained = source.detached()
                try {
                    assertTrue(runCatching { retained.nativeRequest("/api/files", params = mapOf("profile" to "a")) }.exceptionOrNull() is NativeAuthExpiredException)
                    assertEquals(replacement.takeUnless { it == old.copy(profile = "b") }, store.read(old.origin).bearer)
                } finally { retained.closeNative(); source.closeNative() }
            }
        }
    }
}
