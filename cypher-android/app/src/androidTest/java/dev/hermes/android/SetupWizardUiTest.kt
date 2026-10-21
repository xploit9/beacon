package dev.hermes.android

import dev.hermes.android.ui.setupNeedsBackgroundExemption
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.activity.ComponentActivity
import androidx.compose.ui.text.input.TextFieldValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.Scope
import dev.hermes.android.core.string
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.CypherClient
import dev.hermes.android.data.NativeAuthExpiredException
import dev.hermes.android.data.NativeAuth
import okhttp3.CookieJar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.SetupWizardScreen
import dev.hermes.android.ui.QrScanner
import dev.hermes.android.ui.INVALID_CYPHER_QR_HINT
import dev.hermes.android.ui.handleQrScanResult
import dev.hermes.android.ui.parseCypherConnectInput
import dev.hermes.android.ui.stageCypherConnectQr
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import android.content.pm.PackageManager
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

class SetupWizardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun bareDashboardHostDefaultsToHttpsAndInvalidInputUsesPlainLanguage() {
        assertEquals("https://dashboard.example.ts.net:8443", parseCypherConnectInput("dashboard.example.ts.net:8443"))
        val error = runCatching { parseCypherConnectInput("http://dashboard.example.ts.net") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertFalse(error?.message.orEmpty().contains("Expected URL scheme"))
    }

    @Test fun backgroundUseActionIsOnlyNeededWhileBatteryOptimized() {
        assertTrue(setupNeedsBackgroundExemption(isIgnoringBatteryOptimizations = false))
        assertFalse(setupNeedsBackgroundExemption(isIgnoringBatteryOptimizations = true))
    }

    @Test fun connectScreenOffersCameraScanAndPhotoPicker() {
        compose.setContent {
            MaterialTheme {
                SetupWizardScreen(AppState(setupStep = "connect"), onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
            }
        }
        compose.onNodeWithTag("setup-scan-qr").assertIsDisplayed()
        compose.onNodeWithTag("setup-choose-image").assertIsDisplayed()
    }

    @Test fun setupWizardSuppressedNoticeAndErrorAreClearedWhenItCloses() {
        var setupWizard by mutableStateOf(true)
        var error by mutableStateOf("")
        var notice by mutableStateOf("For this dashboard, scan the QR code with Cypher's scanner to sign in.")
        var cleared = 0
        val snackbar = SnackbarHostState()
        compose.setContent { MaterialTheme {
            NativeFeedbackEffect(error, notice, setupWizard, snackbar) {
                cleared++
                error = ""
                notice = ""
            }
            SnackbarHost(snackbar)
        } }
        compose.waitForIdle()
        assertNull(snackbar.currentSnackbarData)

        compose.runOnIdle { setupWizard = false }
        compose.waitUntil(5_000) { cleared == 1 || snackbar.currentSnackbarData != null }
        assertEquals(1, cleared)
        assertNull(snackbar.currentSnackbarData)

        compose.runOnIdle { setupWizard = true; error = "Setup step failed" }
        compose.waitForIdle()
        compose.runOnIdle { setupWizard = false }
        compose.waitUntil(5_000) { cleared == 2 || snackbar.currentSnackbarData != null }
        assertEquals(2, cleared)
        assertNull(snackbar.currentSnackbarData)
    }

    @Suppress("UNCHECKED_CAST") private fun <T> flow(vm: CypherViewModel, name: String) =
        CypherViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
    private fun field(vm: CypherViewModel, name: String, value: Any?) {
        CypherViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    @Test fun returningToTheAppWhileSignedOutNeverStartsAConnection() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CypherApplication
        val store = ViewModelStore()
        lateinit var vm: CypherViewModel
        try {
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { CypherViewModel(app, SavedStateHandle()) } })[CypherViewModel::class.java] }
            compose.waitUntil(5_000) { !vm.ui.value.restoringSession }
            val generation = CypherViewModel::class.java.getDeclaredField("originGeneration").apply { isAccessible = true }
            compose.runOnUiThread {
                field(vm, "client", null)
                flow<AppState>(vm, "_ui").value = vm.ui.value.copy(authenticated = false, loading = false, error = "", setupWizard = true, setupStep = "connect")
            }
            val before = generation.getInt(vm)
            compose.runOnUiThread { vm.foreground(false); vm.foreground(true) }
            compose.waitForIdle()
            assertEquals("A signed-out foreground (setup, sign-in return) must not connect and reset state", before, generation.getInt(vm))
            assertFalse(vm.ui.value.loading)
            assertEquals("", vm.ui.value.error)
            assertTrue(vm.ui.value.setupWizard)
        } finally { compose.runOnUiThread { store.clear() } }
    }

    @Test fun authReturnDeepLinkReturnsToCypher() {
        val app = ApplicationProvider.getApplicationContext<CypherApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${app.packageName}://auth/complete")).setPackage(app.packageName)
        // Other Cypher builds use their own package as the scheme, so the shared old link no longer reaches this one.
        assertNull(app.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("cypher://auth/complete")).setPackage(app.packageName), PackageManager.MATCH_DEFAULT_ONLY))
        val target = app.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals("dev.hermes.android.AuthReturnActivity", target?.activityInfo?.name)
        val unrelated = Intent(Intent.ACTION_VIEW, Uri.parse("${app.packageName}://auth/other")).setPackage(app.packageName)
        assertTrue(app.packageManager.resolveActivity(unrelated, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.name != "dev.hermes.android.AuthReturnActivity")
        val monitor = instrumentation.addMonitor(CypherActivity::class.java.name, null, false)
        try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val activity = monitor.waitForActivityWithTimeout(5_000)
            assertNotNull("Auth return trampoline should bring Cypher to the foreground", activity)
            assertTrue(activity is CypherActivity)
            assertTrue(activity?.intent?.data == null)
            instrumentation.runOnMainSync { activity?.finish() }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun connectionLinkContinuesThroughSignInPermissionsAndChat() {
        var state by mutableStateOf(AppState(origin = "", setupWizard = true, setupStep = "connect", restoringSession = false))
        var reachedOrigin = ""
        var finished = false
        compose.setContent {
            NativeLocalization("en") { CypherTheme(dark = true) {
                SetupWizardScreen(
                    state = state,
                    onProbe = { raw -> reachedOrigin = parseCypherConnectInput(raw); state = state.copy(origin = reachedOrigin, setupStep = "signin", error = "") },
                    onSignIn = { state = state.copy(authenticated = true, setupStep = "permission-microphone", setupPluginMessage = "Cypher plugin not installed on this Hermes — chat works; calls need the plugin.") },
                    onAdvancePermission = { state = state.copy(setupStep = when (state.setupStep) { "permission-microphone" -> "permission-notifications"; "permission-notifications" -> "permission-full-screen"; else -> "done" }) },
                    onFinish = { finished = true; state = state.copy(setupWizard = false, setupStep = "done") },
                    onReopen = { state = state.copy(origin = it, setupStep = "connect") },
                    onCancel = {},
                )
            } }
        }

        compose.onNodeWithTag("setup-address").performTextInput("cypher://connect?url=https%3A%2F%2Fhermes.example&name=Hermes")
        compose.onNodeWithText("Cypher password").assertDoesNotExist()
        compose.onNodeWithText("Hermes username").assertDoesNotExist()
        compose.onNodeWithTag("setup-connect").performClick()
        assertEquals("https://hermes.example", reachedOrigin)
        compose.onNodeWithTag("setup-signin").performClick()
        compose.onNodeWithText("Microphone").assertIsDisplayed()
        compose.onNodeWithText("Skip for now").performClick()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        compose.onNodeWithText("Skip for now").performClick()
        compose.onNodeWithText("Full-screen call alerts").assertIsDisplayed()
        compose.onNodeWithText("Skip for now").performClick()
        compose.onNodeWithTag("setup-finish").performClick()
        assertTrue(finished)
    }

    @Test fun externalConnectionLinkPrefillsButWaitsForContinueAndCancelKeepsActiveOrigin() {
        val activeOrigin = "https://current.example"
        val pendingOrigin = "https://next.example"
        var probes = 0
        var probedOrigin = ""
        var cancelled = false
        var state by mutableStateOf(AppState(origin = activeOrigin, setupOrigin = pendingOrigin, authenticated = true, setupWizard = true, setupStep = "connect", restoringSession = false))
        compose.setContent { NativeLocalization("en") { CypherTheme(dark = true) {
            SetupWizardScreen(
                state = state,
                onProbe = { raw -> probes++; probedOrigin = parseCypherConnectInput(raw) },
                onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {},
                onCancel = { cancelled = true; state = state.copy(setupWizard = false) },
            )
        } } }

        compose.onNodeWithTag("setup-address").assertTextEquals("Paste link or address", pendingOrigin)
        assertEquals(0, probes)
        compose.onNodeWithTag("setup-connect").performClick()
        assertEquals(1, probes)
        assertEquals(pendingOrigin, probedOrigin)
        assertEquals(activeOrigin, state.origin)
        compose.onNodeWithText("Close setup").performClick()
        assertTrue(cancelled)
        assertEquals(activeOrigin, state.origin)
    }

    @Test fun externalConnectIntentDropsSessionButKeepsTheServerAddress() {
        val token = "private-loopback-session-token"
        val input = Uri.parse("cypher://connect?url=https%3A%2F%2Fhermes.example&name=Hermes&session=$token&session=duplicate&SESSION=uppercase")

        val external = sanitizeExternalConnectLink(input)
        val safeUri = Uri.parse(external.link)

        assertTrue(external.sessionRemoved)
        assertEquals("https://hermes.example", safeUri.getQueryParameter("url"))
        assertNull(safeUri.getQueryParameter("session"))
        assertFalse(external.link.contains(token))
        assertFalse(external.link.contains("duplicate"))
        assertFalse(external.link.contains("uppercase"))

        val plain = sanitizeExternalConnectLink(Uri.parse("cypher://connect?url=https%3A%2F%2Fhermes.example"))
        assertFalse(plain.sessionRemoved)
        assertEquals("https://hermes.example", Uri.parse(plain.link).getQueryParameter("url"))
    }

    @Test fun malformedAndTypedConnectLinksStayVisibleUntilContinue() {
        var staged = ""
        var probed = ""
        val state = AppState(setupWizard = true, setupStep = "connect", restoringSession = false)
        compose.setContent { NativeLocalization("en") { CypherTheme(dark = true) {
            SetupWizardScreen(
                state = state,
                onProbe = { probed = it }, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {},
                onStageLink = { staged = it },
            )
        } } }

        val malformed = "cypher://connect?session=private-token"
        compose.onNodeWithTag("setup-address").performTextInput(malformed)
        compose.onNodeWithTag("setup-address").assertTextEquals("Paste link or address", malformed)
        assertEquals("", staged)

        compose.onNodeWithTag("setup-address").performTextClearance()
        compose.onNodeWithTag("setup-address").performTextInput("cypher://connect?url=https%3A%2F%2Fhermes.example&name=Hermes")
        val typed = "cypher://connect?url=https%3A%2F%2Fhermes.example&name=Hermes"
        compose.onNodeWithTag("setup-address").assertTextEquals("Paste link or address", typed)
        assertEquals("", staged)
        compose.onNodeWithTag("setup-connect").performClick()
        assertEquals(typed, probed)

        val address = "https://hermes.example"
        compose.onNodeWithTag("setup-address").performTextClearance()
        compose.onNodeWithTag("setup-address").performTextInput(address)
        compose.onNodeWithTag("setup-connect").performClick()
        assertEquals(address, probed)
    }

    @Test fun scannerStagesOnlyDashboardCypherQrPayloadsWhileTypedAddressesStaySupported() {
        val staged = mutableListOf<String>()
        val link = "cypher://connect?url=https%3A%2F%2Fhermes.example&name=Hermes"
        val payload = """{"type":"cypher-connect","url":"https://hermes.example","session":"session-token"}"""
        val detected = AtomicBoolean(true)
        var hint = ""

        assertFalse(stageCypherConnectQr("https://poster.example") { staged += it })
        assertFalse(stageCypherConnectQr("WIFI:T:WPA;S:guest;P:password;;") { staged += it })
        assertFalse(handleQrScanResult("https://poster.example", detected,
            { value -> stageCypherConnectQr(value) { staged += it } },
            { hint = INVALID_CYPHER_QR_HINT },
        ))
        assertFalse(detected.get())
        assertEquals(INVALID_CYPHER_QR_HINT, hint)
        assertTrue(stageCypherConnectQr(link) { staged += it })
        assertTrue(stageCypherConnectQr(payload) { staged += it })

        assertEquals(listOf(link, payload), staged)
    }

    @Test fun openingExternalLinkStoresPendingOriginAndCancelKeepsTheActiveAccount() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<CypherApplication>()
        val originKey = stringPreferencesKey("origin")
        val setupOriginKey = stringPreferencesKey("setup_origin")
        val setupStepKey = stringPreferencesKey("setup_step")
        val setupCompleteKey = booleanPreferencesKey("setup_complete")
        val setupActiveKey = booleanPreferencesKey("setup_active")
        val before = app.preferences.data.first()
        val activeOrigin = "https://active-${java.util.UUID.randomUUID()}.example"
        val pendingOrigin = "https://pending-${java.util.UUID.randomUUID()}.example"
        val store = ViewModelStore()
        lateinit var vm: CypherViewModel
        try {
            app.preferences.edit { values ->
                values[originKey] = activeOrigin
                values[setupCompleteKey] = true
                values[setupActiveKey] = false
                values.remove(setupOriginKey)
            }
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { CypherViewModel(app, SavedStateHandle()) } })[CypherViewModel::class.java]
            }
            compose.waitUntil(5_000) { !vm.ui.value.restoringSession }
            val link = "cypher://connect?url=${Uri.encode(pendingOrigin)}&name=Hermes"
            compose.runOnUiThread { vm.openSetupLink(link) }
            compose.waitUntil(5_000) { vm.ui.value.setupWizard && vm.ui.value.setupStep == "connect" && vm.ui.value.setupOrigin == pendingOrigin }

            assertEquals(activeOrigin, vm.ui.value.origin)
            assertEquals(activeOrigin, app.preferences.data.first()[originKey])
            compose.runOnUiThread {
                flow<AppState>(vm, "_ui").value = vm.ui.value.copy(authenticated = true, setupComplete = true)
                vm.cancelSetupWizard()
            }
            compose.waitUntil(5_000) { !vm.ui.value.setupWizard }
            withTimeout(5_000) { while (app.preferences.data.first()[setupOriginKey] != null) delay(10) }
            assertTrue(vm.ui.value.authenticated)
            assertEquals(activeOrigin, vm.ui.value.origin)
            assertEquals(activeOrigin, app.preferences.data.first()[originKey])
        } finally {
            compose.runOnUiThread { store.clear() }
            app.preferences.edit { values ->
                before[originKey]?.let { values[originKey] = it } ?: values.remove(originKey)
                before[setupOriginKey]?.let { values[setupOriginKey] = it } ?: values.remove(setupOriginKey)
                before[setupStepKey]?.let { values[setupStepKey] = it } ?: values.remove(setupStepKey)
                before[setupCompleteKey]?.let { values[setupCompleteKey] = it } ?: values.remove(setupCompleteKey)
                before[setupActiveKey]?.let { values[setupActiveKey] = it } ?: values.remove(setupActiveKey)
            }
        }
    }

    @Test fun fixtureReplacesTheCustomTabAndReturnsToTheLoopbackCallback() = runBlocking {
        val fixture = NativeExperienceFixture()
        fixture.installCustomTabSeam()
        try {
            val transport = fixture.client.http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
            val outcome = runCatching { NativeAuth(fixture.scope.origin, transport).authorize(compose.activity) }
            fixture.callbackFailure?.let { throw AssertionError("Synthetic OAuth callback failed", it) }
            val result = outcome.getOrThrow()
            assertEquals(fixture.scope.owner, result.owner)
            assertEquals(1, fixture.customTabLaunches)
            val authRequests = fixture.requests.filter { it.string("path").startsWith("/auth/native/") }
            val tokenFields = authRequests.single { it.string("path") == "/auth/native/token" }.getJSONArray("body_fields")
            assertTrue((0 until tokenFields.length()).any { tokenFields.optString(it) == "code_verifier" })
            assertTrue(authRequests.all { request ->
                val fields = request.optJSONArray("body_fields") ?: return@all false
                (0 until fields.length()).none { fields.optString(it) in setOf("username", "password") }
            })
        } finally {
            fixture.clearCustomTabSeam()
            fixture.client.closeNative()
        }
    }

    @Test fun cancellingNativeSignInClosesItsLoopbackListener() = runBlocking {
        val callback = AtomicReference<Uri?>()
        NativeAuth.customTabLauncher = { _, authorization ->
            callback.set(Uri.parse(authorization.getQueryParameter("redirect_uri") ?: error("Missing loopback redirect")))
        }
        val signIn = launch(Dispatchers.Main) { runCatching { NativeAuth("https://hermes.example").authorize(compose.activity) } }
        try {
            withTimeout(5_000) { while (callback.get() == null) delay(10) }
            val redirect = callback.get() ?: error("Native sign-in did not create its loopback listener")
            signIn.cancelAndJoin()
            Socket().use { socket ->
                val failure = runCatching { socket.connect(InetSocketAddress("127.0.0.1", redirect.port), 500) }.exceptionOrNull()
                assertTrue("The callback port should be closed after sign-in is cancelled", failure is ConnectException)
            }
        } finally {
            signIn.cancelAndJoin()
            NativeAuth.customTabLauncher = null
        }
    }

    @Test fun unreachableServerShowsRetry() {
        var retries = 0
        val state = AppState(origin = "https://hermes.example", setupWizard = true, setupStep = "probe", restoringSession = false, error = "Can't reach Hermes. Check Tailscale/VPN is on, then Retry.")
        compose.setContent { NativeLocalization("en") { CypherTheme(dark = true) {
            SetupWizardScreen(state, onProbe = { retries++ }, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
        } } }
        compose.onNodeWithText("Can't reach Hermes. Check Tailscale/VPN is on, then Retry.").assertIsDisplayed()
        compose.onNodeWithTag("setup-probe-retry").performClick()
        assertEquals(1, retries)
    }

    @Test fun errorSnackbarSurvivesNoticeRecompositionAndUsesLongDuration() {
        val snackbar = SnackbarHostState()
        var notice by mutableStateOf("")
        val error = "Owned resource unavailable"
        compose.setContent { CypherTheme(dark = true) {
            NativeFeedbackEffect(error = error, notice = notice, setupWizard = false, snackbar = snackbar) {}
            SnackbarHost(snackbar)
        } }

        compose.waitUntil(5_000) { snackbar.currentSnackbarData != null }
        val original = snackbar.currentSnackbarData
        compose.runOnIdle { notice = "An unrelated status update" }
        compose.waitForIdle()
        assertSame(original, snackbar.currentSnackbarData)
        compose.mainClock.advanceTimeBy(4_500)
        compose.onNodeWithText(error).assertIsDisplayed()
    }

    @Test fun qrScannerRefreshesCameraPermissionAfterResume() {
        // Revoking a granted runtime permission kills the app process (and this test), so the check is simulated:
        // the user grants camera access in system Settings while the scanner is in the background.
        var granted = false
        compose.setContent { QrScanner(onResult = { true }, onClose = {}, hasCamera = { granted }) }
        compose.onNodeWithTag("setup-camera-permission").assertIsDisplayed()

        granted = true
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("setup-camera-permission").assertDoesNotExist() }.isSuccess
        }
        compose.onNodeWithTag("setup-camera-permission").assertDoesNotExist()
    }

    @Test fun missingPluginMessageIsShownWithoutBlockingSignIn() {
        val message = "Cypher plugin not installed on this Hermes — chat works; calls need the plugin."
        val state = AppState(origin = "https://hermes.example", authenticated = true, setupWizard = true, setupStep = "done", restoringSession = false, setupPluginMessage = message)
        compose.setContent { NativeLocalization("en") { CypherTheme(dark = true) {
            SetupWizardScreen(state, onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
        } } }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithTag("setup-finish").assertIsDisplayed()
    }

    @Test fun anInterruptedSetupResumesAtItsIncompletePermissionStep() {
        val state = AppState(origin = "https://hermes.example", authenticated = true, setupWizard = true, setupStep = "permission-notifications", restoringSession = false)
        compose.setContent { NativeLocalization("en") { CypherTheme(dark = true) {
            SetupWizardScreen(state, onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
        } } }
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        compose.onNodeWithTag("setup-permission-primary").assertIsDisplayed()
    }

    @Test fun expiredRefreshOffersOneTapSignInAndRetainsTheComposerAndChat() {
        check(!ApplicationProvider.getApplicationContext<CypherApplication>().calls.state.value.active)
        val app = ApplicationProvider.getApplicationContext<CypherApplication>()
        val originKey = stringPreferencesKey("origin")
        val setupStepKey = stringPreferencesKey("setup_step")
        val setupCompleteKey = androidx.datastore.preferences.core.booleanPreferencesKey("setup_complete")
        val setupActiveKey = androidx.datastore.preferences.core.booleanPreferencesKey("setup_active")
        val priorPreferences = runBlocking { app.preferences.data.first() }
        runBlocking { app.preferences.edit { it[originKey] = "" } }
        val store = ViewModelStore()
        val origin = "https://expired-${java.util.UUID.randomUUID()}.example"
        val scope = Scope(origin, "fixture-owner", "work")
        val client = CypherClient(AuthContext(origin, emptyList(), scope.owner, scope.profile, backend = "hermes-dashboard"))
        lateinit var vm: CypherViewModel
        try {
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { CypherViewModel(app, SavedStateHandle()) } })[CypherViewModel::class.java]
            }
            compose.waitUntil(5_000) { !vm.ui.value.restoringSession }
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                field(vm, "client", client)
                field(vm, "sourceScope", scope.session("chat-a"))
                app.calls.ownerScope = scope
                flow<AppState>(vm, "_ui").value = AppState(origin = origin, authenticated = true, owner = scope.owner, profile = scope.profile, connected = true, restoringSession = false, setupComplete = true)
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = "chat-a", messages = listOf(Message("saved", "assistant", "Earlier answer")))
                flow<TextFieldValue>(vm, "_composer").value = TextFieldValue("Keep this unfinished answer")
            }
            val fail = CypherViewModel::class.java.getDeclaredMethod("fail", Throwable::class.java).apply { isAccessible = true }
            compose.runOnIdle { fail.invoke(vm, NativeAuthExpiredException()) }
            compose.waitUntil(5_000) { vm.ui.value.setupWizard && vm.ui.value.setupStep == "signin" }
            assertEquals("Keep this unfinished answer", vm.composer.value.text)
            assertEquals("Earlier answer", vm.chat.value.messages.single().text)

            var signInTaps = 0
            compose.setContent { val state by vm.ui.collectAsState(); NativeLocalization("en") { CypherTheme(dark = true) {
                SetupWizardScreen(state, onProbe = {}, onSignIn = { signInTaps++ }, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
            } } }
            compose.onNodeWithTag("setup-signin").performClick()
            assertEquals(1, signInTaps)
            assertEquals("Keep this unfinished answer", vm.composer.value.text)
            assertEquals("Earlier answer", vm.chat.value.messages.single().text)
            compose.waitUntil(5_000) { runBlocking { app.cache.read(scope.session("chat-a"), "draft") != null } }
        } finally {
            compose.runOnUiThread { store.clear(); client.closeNative(); app.calls.ownerScope = null }
            runBlocking {
                app.cache.delete(scope.session("chat-a"), "draft")
                app.auth.remove(origin)
                app.preferences.edit { values ->
                    priorPreferences[originKey]?.let { values[originKey] = it } ?: values.remove(originKey)
                    priorPreferences[setupStepKey]?.let { values[setupStepKey] = it } ?: values.remove(setupStepKey)
                    priorPreferences[setupCompleteKey]?.let { values[setupCompleteKey] = it } ?: values.remove(setupCompleteKey)
                    priorPreferences[setupActiveKey]?.let { values[setupActiveKey] = it } ?: values.remove(setupActiveKey)
                }
            }
        }
    }
}
