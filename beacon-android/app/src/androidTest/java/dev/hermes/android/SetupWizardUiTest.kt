package dev.hermes.android

import dev.hermes.android.ui.setupNeedsBackgroundExemption
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.graphics.asAndroidBitmap
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
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.NativeBearerRecord
import dev.hermes.android.data.NativeAuthExpiredException
import dev.hermes.android.data.NativeAuth
import dev.hermes.android.data.NativePairing
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.data.VoicePreferences
import dev.hermes.android.push.KeepConnectedService
import okhttp3.CookieJar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import dev.hermes.android.data.PairedDevice
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.SetupWizardScreen
import dev.hermes.android.ui.CallFailurePanel
import dev.hermes.android.ui.DevicesList
import dev.hermes.android.ui.KeepConnectedSetupGroup
import dev.hermes.android.ui.SetupSea
import dev.hermes.android.ui.VoiceCatalog
import dev.hermes.android.ui.VoiceCatalogCache
import dev.hermes.android.ui.defaultVoiceChoices
import dev.hermes.android.ui.nativeContrast
import dev.hermes.android.ui.parseBeaconConnectInput
import dev.hermes.android.ui.setupColorScheme
import dev.hermes.android.ui.setupStepNumber
import dev.hermes.android.ui.voiceProviderInfo
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
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
import androidx.core.content.ContextCompat
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
        assertEquals("https://dashboard.example.ts.net:8443", parseBeaconConnectInput("dashboard.example.ts.net:8443"))
        val error = runCatching { parseBeaconConnectInput("http://dashboard.example.ts.net") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertFalse(error?.message.orEmpty().contains("Expected URL scheme"))
    }

    @Test fun backgroundUseActionIsOnlyNeededWhileBatteryOptimized() {
        assertTrue(setupNeedsBackgroundExemption(isIgnoringBatteryOptimizations = false))
        assertFalse(setupNeedsBackgroundExemption(isIgnoringBatteryOptimizations = true))
    }

    @Test fun keepConnectedBackgroundActionHasVisibleButtonContainer() {
        var openedSettings = false
        compose.setContent {
            NativeLocalization("en") { BeaconTheme(dark = true) {
                KeepConnectedSetupGroup(
                    keepConnected = true,
                    notificationAllowed = true,
                    backgroundActionVisible = true,
                    onKeepConnectedChange = {},
                    onOpenBackgroundSettings = { openedSettings = true },
                )
            } }
        }

        val action = compose.onNodeWithTag("setup-background-use")
        action.assertIsDisplayed()
        action.assert(hasClickAction())
        action.assert(hasAnyAncestor(hasTestTag("setup-keep-connected-group")))
        val image = action.captureToImage().asAndroidBitmap()
        assertTrue(image.getPixel(image.width / 2, 0) != image.getPixel(image.width / 2, image.height / 4))
        action.performClick()
        assertTrue(openedSettings)
    }

    @Test fun connectIsOnlyAPasteFieldAndContinue() {
        compose.setContent {
            NativeLocalization("en") { BeaconTheme(dark = true) {
                SetupWizardScreen(AppState(setupStep = "connect"), onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
            } }
        }
        compose.onNodeWithText("Connect to Hermes").assertIsDisplayed()
        compose.onNodeWithText("Paste the link from your Hermes dashboard.").assertIsDisplayed()
        compose.onNodeWithText("Step 1 of 4").assertIsDisplayed()
        compose.onNodeWithTag("setup-address").assertIsDisplayed()
        compose.onNodeWithContentDescription("Paste link").assertIsDisplayed()
        compose.onNodeWithTag("setup-connect").assertIsDisplayed()
        // Signing in is what links this phone: no QR, image, code or "other ways" step is offered.
        for (gone in listOf("setup-scan-qr", "setup-choose-image", "setup-method-code", "setup-pairing-code", "setup-pairing-redeem")) compose.onNodeWithTag(gone).assertDoesNotExist()
        for (text in listOf("Scan QR code", "Choose QR image", "Enter pairing code", "Other ways", "Pairing code")) compose.onNodeWithText(text).assertDoesNotExist()
    }

    @Test fun connectStepFitsA360dpPhoneAtLargeFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                Box(Modifier.width(360.dp)) {
                    BeaconTheme(dark = true) {
                        SetupWizardScreen(AppState(setupStep = "connect"), onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
                    }
                }
            }
        }
        for (text in listOf("Connect to Hermes", "Paste the link from your Hermes dashboard.")) compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithTag("setup-address").assertIsDisplayed()
        compose.onNodeWithTag("setup-connect").assertIsDisplayed()
        val continueBounds = compose.onNodeWithTag("setup-connect").getUnclippedBoundsInRoot()
        assertTrue("Continue stays inside the 360 dp width", continueBounds.left >= 0.dp && continueBounds.right <= 360.dp)
        assertTrue("Continue keeps its 52 dp target", continueBounds.height >= 52.dp)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Connect to Hermes").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertTrue("The title wraps instead of clipping", layouts.single().lineCount <= 2)
    }

    @Test fun voiceChoicesAreSelectableListsThatKeepTheirOrderAtLargeFontScale() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val store = VoiceKeyStore(app)
        val previousProvider = store.settings().provider
        val previous = store.settings("gemini")
        val previousKey = store.key("gemini")
        val previousLoader = VoiceCatalogCache.loader
        val longVoice = "AVeryLongProviderVoiceNameThatWrapsCleanlyInsideItsRow"
        val extended = "models/gemini-3.8-live-extended-thinking"
        try {
            store.saveKey("gemini", "r5u-fixture-key")
            store.setSelection(VoicePreferences(provider = "gemini", voice = "Fenrir"))
            VoiceCatalogCache.clear("gemini")
            // Offline: this stands in for the provider catalog.
            VoiceCatalogCache.loader = { _, _ -> VoiceCatalog(listOf("models/gemini-3.8-live", extended), defaultVoiceChoices("gemini") + longVoice) }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    Box(Modifier.width(320.dp)) {
                        NativeLocalization("en") { BeaconTheme(dark = true) {
                            SetupWizardScreen(AppState(setupStep = "voice-customize", authenticated = true), onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
                        } }
                    }
                }
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("voice-option-voice-Fenrir").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("voice-option-voice-Fenrir").assertIsSelected()
            val fenrirTop = compose.onNodeWithTag("voice-option-voice-Fenrir").fetchSemanticsNode().positionInRoot.y

            compose.onNodeWithTag("voice-option-voice-Puck").performScrollTo().performClick()
            compose.onNodeWithTag("voice-option-voice-Puck").assertIsSelected()
            compose.onNodeWithTag("voice-option-voice-Fenrir").assertIsNotSelected()
            compose.waitUntil(5_000) { store.settings("gemini").voice == "Puck" }
            compose.onNodeWithTag("voice-option-voice-Fenrir").performScrollTo()
            assertEquals("Choosing a voice never moves the list", fenrirTop, compose.onNodeWithTag("voice-option-voice-Fenrir").fetchSemanticsNode().positionInRoot.y, 1f)

            // The rest of the catalog sits behind "More voices", and a long name wraps inside its row.
            compose.onNodeWithTag("voice-more-voices").performScrollTo().performClick()
            compose.onNodeWithTag("voice-option-voice-$longVoice").performScrollTo().performClick()
            compose.onNodeWithTag("voice-option-voice-$longVoice").assertIsSelected()
            compose.waitUntil(5_000) { store.settings("gemini").voice == longVoice }

            compose.onNodeWithTag("voice-option-personality-warm").performScrollTo().performClick()
            compose.onNodeWithTag("voice-option-personality-warm").assertIsSelected()
            compose.waitUntil(5_000) { store.settings("gemini").personality == "warm" }

            compose.onNodeWithTag("voice-option-model-$extended").performScrollTo().performClick()
            compose.onNodeWithTag("voice-option-model-$extended").assertIsSelected()
            compose.waitUntil(5_000) { store.settings("gemini").model == extended }
        } finally {
            VoiceCatalogCache.loader = previousLoader
            VoiceCatalogCache.clear("gemini")
            if (previousKey == null) store.removeKey("gemini") else store.saveKey("gemini", previousKey)
            store.setSelection(previous)
            store.setSelection(store.settings(previousProvider))
        }
    }

    @Test fun failedCallPanelKeepsTheReasonVisibleAndOffersRetryAndClose() {
        val reason = "Gemini didn't accept the API key. Check it in Settings › Voice & calls."
        var retried = false
        var closed = false
        compose.setContent {
            NativeLocalization("en") {
                BeaconTheme(dark = true) {
                    CallFailurePanel(reason, onRetry = { retried = true }, onClose = { closed = true })
                }
            }
        }

        compose.onNodeWithTag("beacon-call-failure-reason").assertIsDisplayed().assertTextEquals(reason)
        compose.onNodeWithTag("beacon-call-failure-retry").assertIsDisplayed().performClick()
        compose.onNodeWithTag("beacon-call-failure-close").assertIsDisplayed().performClick()
        assertTrue(retried)
        assertTrue(closed)
    }

    @Test fun savedVoiceKeyOpensCustomizationAndContinueGoesToMicrophone() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val before = runBlocking { app.preferences.data.first() }
        val voiceKeys = VoiceKeyStore(app)
        val provider = voiceKeys.settings().provider
        val previousKey = voiceKeys.key(provider)
        val previousLoader = VoiceCatalogCache.loader
        val setupStepKey = stringPreferencesKey("setup_step")
        val setupCompleteKey = booleanPreferencesKey("setup_complete")
        val setupActiveKey = booleanPreferencesKey("setup_active")
        val setupOriginKey = stringPreferencesKey("setup_origin")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val hadMicrophonePermission = ContextCompat.checkSelfPermission(targetContext, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hadMicrophonePermission) instrumentation.uiAutomation.revokeRuntimePermission(app.packageName, android.Manifest.permission.RECORD_AUDIO)
        val store = ViewModelStore()
        var viewModel: BeaconViewModel? = null
        try {
            voiceKeys.saveKey(provider, "r5u-fixture-key")
            // Keep this UI test offline; the choices fall back to the built-in lists.
            VoiceCatalogCache.loader = { _, _ -> VoiceCatalog() }
            compose.runOnUiThread { viewModel = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            compose.waitUntil(5_000) { viewModel?.ui?.value?.restoringSession == false }
            val vm = requireNotNull(viewModel)
            compose.runOnUiThread {
                flow<AppState>(vm, "_ui").value = vm.ui.value.copy(
                    authenticated = true,
                    setupWizard = true,
                    setupComplete = false,
                    setupStep = "voice-provider",
                    setupOrigin = "https://hermes.example",
                    restoringSession = false,
                )
                vm.advanceSetupPermission()
            }
            // A provider that already has a key skips the key page.
            compose.waitUntil(5_000) { vm.ui.value.setupStep == "voice-customize" }
            compose.setContent {
                val state by vm.ui.collectAsState()
                NativeLocalization("en") { BeaconTheme(dark = true) {
                    SetupWizardScreen(
                        state = state,
                        onProbe = {}, onSignIn = {}, onAdvancePermission = vm::advanceSetupPermission,
                        onFinish = {}, onReopen = {}, onCancel = {}, onVoiceKeySaved = vm::voiceKeyConfigured, onVoicePage = vm::showVoiceSetupPage,
                        ownerName = "Maya", onOwnerNameChange = {},
                    )
                } }
            }
            compose.onNodeWithText("Make it yours").assertIsDisplayed()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("setup-owner-name").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("setup-owner-name").performScrollTo().assertIsDisplayed().assertTextContains("Maya")
            // One Continue, no duplicate Skip.
            compose.onNodeWithTag("setup-voice-customize-skip").assertDoesNotExist()
            compose.onNodeWithTag("setup-voice-customize-continue").performClick()
            compose.waitUntil(5_000) { vm.ui.value.setupStep == "permission-microphone" }
            compose.onNodeWithText("Microphone").assertIsDisplayed()
        } finally {
            VoiceCatalogCache.loader = previousLoader
            viewModel?.let { compose.runOnUiThread { store.clear() } }
            if (previousKey == null) voiceKeys.removeKey(provider) else voiceKeys.saveKey(provider, previousKey)
            runBlocking { app.preferences.edit { values ->
                before[setupStepKey]?.let { values[setupStepKey] = it } ?: values.remove(setupStepKey)
                before[setupCompleteKey]?.let { values[setupCompleteKey] = it } ?: values.remove(setupCompleteKey)
                before[setupActiveKey]?.let { values[setupActiveKey] = it } ?: values.remove(setupActiveKey)
                before[setupOriginKey]?.let { values[setupOriginKey] = it } ?: values.remove(setupOriginKey)
            } }
            if (hadMicrophonePermission) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.RECORD_AUDIO)
        }
    }

    @Test fun setupWizardSuppressedNoticeAndErrorAreClearedWhenItCloses() {
        var setupWizard by mutableStateOf(true)
        var error by mutableStateOf("")
        var notice by mutableStateOf("Hermes restarted. Paste the link again.")
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

    @Suppress("UNCHECKED_CAST") private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    @Test fun openChatAfterFirstSetupAndSettingsRerunKeepsTheNativeConversationAndDraft() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        check(!app.calls.state.value.active && app.calls.service == null && app.push.incoming.value == null)
        check(!KeepConnectedService.isEnabled(app)) { "Run setup routing checks with Keep connected off" }
        val keepPrefs = app.getSharedPreferences(KeepConnectedService.PREFS, android.content.Context.MODE_PRIVATE)
        val keepBefore = keepPrefs.all
        val stringKeys = listOf("origin", "setup_step", "setup_origin").map(::stringPreferencesKey)
        val booleanKeys = listOf("setup_complete", "setup_active").map(::booleanPreferencesKey)
        val before = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture().apply { pluginStatus = 404; bootstrapPluginStatus = "absent" }
        val chatScope = fixture.scope.session("chat-a")
        val priorDraft = runBlocking { app.cache.read(chatScope, "draft") }
        val saved = SavedStateHandle(mapOf("tab" to "Chat", "more" to ""))
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        try {
            runBlocking { app.preferences.edit { it[stringPreferencesKey("origin")] = "" } }
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, saved) } })[BeaconViewModel::class.java] }
            compose.waitUntil(5_000) { !vm.ui.value.restoringSession }
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "sourceScope", chatScope)
                field(vm, "assistantReturnValue", null)
                flow<AppState>(vm, "_ui").value = AppState(
                    origin = fixture.scope.origin, owner = fixture.scope.owner, profile = fixture.scope.profile,
                    authenticated = true, connected = true, restoringSession = false, setupWizard = true, setupStep = "done",
                    bootstrap = dev.hermes.android.core.json("optional_features" to dev.hermes.android.core.json("plugin" to "absent")),
                )
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = "chat-a", raw = fixture.snapshot("chat-a"), messages = listOf(Message("saved", "assistant", "Earlier native answer")))
                flow<TextFieldValue>(vm, "_composer").value = TextFieldValue("Keep this unfinished message")
            }
            compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) { BeaconRoot(vm) } } }

            for (rerun in listOf(false, true)) {
                if (rerun) {
                    compose.runOnIdle { vm.more("Connection") }
                    compose.onNodeWithText("Set up again").performScrollTo().performClick()
                    // Seed completed auth/voice prerequisites; keep the real completion callback and root routing.
                    compose.runOnIdle { flow<AppState>(vm, "_ui").value = vm.ui.value.copy(setupStep = "done") }
                } else {
                    compose.onNodeWithTag("setup-keep-connected").performScrollTo().performClick()
                }
                compose.onNodeWithTag("setup-keep-connected").assertIsOff()
                compose.onNodeWithTag("setup-finish").performClick()
                compose.waitUntil(5_000) { !vm.ui.value.setupWizard }
                compose.runOnIdle {
                    assertEquals("Open chat must leave Connection settings", "Chat", vm.ui.value.tab)
                    assertEquals("", vm.ui.value.morePage)
                    assertEquals("Chat", saved.get<String>("tab"))
                    assertEquals("", saved.get<String>("more"))
                    assertNull(saved.get<String>("settings-navigation"))
                    assertTrue(vm.ui.value.authenticated && vm.ui.value.setupComplete)
                    assertEquals(fixture.scope.origin, vm.ui.value.origin)
                    assertEquals(fixture.scope.owner, vm.ui.value.owner)
                    assertEquals(fixture.scope.profile, vm.ui.value.profile)
                    assertSame(fixture.client, vm.client)
                    assertEquals(chatScope, vm.authenticatedScope())
                    assertEquals("chat-a", vm.chat.value.sessionId)
                    assertEquals("Earlier native answer", vm.chat.value.messages.single().text)
                    assertEquals("Keep this unfinished message", vm.composer.value.text)
                    assertFalse(KeepConnectedService.isEnabled(app))
                }
                compose.onNodeWithTag("setup-finish").assertDoesNotExist()
                compose.onNodeWithTag("message-input").assertIsDisplayed().assertTextContains("Keep this unfinished message")
                compose.onNodeWithText("Earlier native answer").assertIsDisplayed()
            }
            compose.waitUntil(5_000) { runBlocking { app.preferences.data.first()[booleanPreferencesKey("setup_active")] == false } }
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative() }
            runBlocking {
                if (priorDraft == null) app.cache.delete(chatScope, "draft") else app.cache.write(chatScope, "draft", priorDraft, true)
                app.auth.remove(fixture.scope.origin)
                app.preferences.edit { values ->
                    stringKeys.forEach { key -> before[key]?.let { values[key] = it } ?: values.remove(key) }
                    booleanKeys.forEach { key -> before[key]?.let { values[key] = it } ?: values.remove(key) }
                }
            }
            assertTrue(keepPrefs.edit().apply {
                listOf(KeepConnectedService.KEY_ENABLED, KeepConnectedService.KEY_STATUS, KeepConnectedService.KEY_REASON).forEach { key ->
                    when (val value = keepBefore[key]) {
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                        null -> remove(key)
                        else -> error("Unexpected Keep connected preference type")
                    }
                }
            }.commit())
        }
    }

    @Test fun returningToTheAppWhileSignedOutNeverStartsAConnection() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BeaconApplication
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        try {
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            compose.waitUntil(5_000) { !vm.ui.value.restoringSession }
            val generation = BeaconViewModel::class.java.getDeclaredField("originGeneration").apply { isAccessible = true }
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

    @Test fun authReturnDeepLinkReturnsToBeacon() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${app.packageName}://auth/complete")).setPackage(app.packageName)
        // Other Beacon builds use their own package as the scheme, so the shared old link no longer reaches this one.
        assertNull(app.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("beacon://auth/complete")).setPackage(app.packageName), PackageManager.MATCH_DEFAULT_ONLY))
        val target = app.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals("dev.hermes.android.AuthReturnActivity", target?.activityInfo?.name)
        val unrelated = Intent(Intent.ACTION_VIEW, Uri.parse("${app.packageName}://auth/other")).setPackage(app.packageName)
        assertTrue(app.packageManager.resolveActivity(unrelated, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.name != "dev.hermes.android.AuthReturnActivity")
        val monitor = instrumentation.addMonitor(BeaconActivity::class.java.name, null, false)
        try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val activity = monitor.waitForActivityWithTimeout(5_000)
        assertNotNull("Auth return trampoline should bring Beacon to the foreground", activity)
            assertTrue(activity is BeaconActivity)
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
            NativeLocalization("en") { BeaconTheme(dark = true) {
                SetupWizardScreen(
                    state = state,
                    onProbe = { raw -> reachedOrigin = parseBeaconConnectInput(raw); state = state.copy(origin = reachedOrigin, setupStep = "signin", error = "") },
                    onSignIn = { state = state.copy(authenticated = true, setupStep = "permission-microphone", setupPluginMessage = "Beacon plugin not installed on this Hermes — chat works; calls need the plugin.") },
                    onAdvancePermission = { state = state.copy(setupStep = when (state.setupStep) { "permission-microphone" -> "permission-notifications"; "permission-notifications" -> "permission-full-screen"; else -> "done" }) },
                    onFinish = { finished = true; state = state.copy(setupWizard = false, setupStep = "done") },
                    onReopen = { state = state.copy(origin = it, setupStep = "connect") },
                    onCancel = {},
                )
            } }
        }

        compose.onNodeWithTag("setup-address").performTextInput("beacon://connect?url=https%3A%2F%2Fhermes.example&name=Hermes")
        compose.onNodeWithText("Beacon password").assertDoesNotExist()
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
        compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) {
            SetupWizardScreen(
                state = state,
                onProbe = { raw -> probes++; probedOrigin = parseBeaconConnectInput(raw) },
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
        val input = Uri.parse("beacon://connect?url=https%3A%2F%2Fhermes.example&name=Hermes&session=$token&session=duplicate&SESSION=uppercase")

        val external = sanitizeExternalConnectLink(input)
        val safeUri = Uri.parse(external)

        assertEquals("https://hermes.example", safeUri.getQueryParameter("url"))
        assertNull(safeUri.getQueryParameter("session"))
        assertFalse(external.contains(token))
        assertFalse(external.contains("duplicate"))
        assertFalse(external.contains("uppercase"))

        val plain = sanitizeExternalConnectLink(Uri.parse("beacon://connect?url=https%3A%2F%2Fhermes.example"))
        assertEquals("https://hermes.example", Uri.parse(plain).getQueryParameter("url"))
    }

    @Test fun malformedAndTypedConnectLinksStayVisibleUntilContinue() {
        var probed = ""
        val state = AppState(setupWizard = true, setupStep = "connect", restoringSession = false)
        compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) {
            SetupWizardScreen(
                state = state,
                onProbe = { probed = it }, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {},
            )
        } } }

        val malformed = "beacon://connect?session=private-token"
        compose.onNodeWithTag("setup-address").performTextInput(malformed)
        compose.onNodeWithTag("setup-address").assertTextEquals("Paste link or address", malformed)

        compose.onNodeWithTag("setup-address").performTextClearance()
        compose.onNodeWithTag("setup-address").performTextInput("beacon://connect?url=https%3A%2F%2Fhermes.example&name=Hermes")
        val typed = "beacon://connect?url=https%3A%2F%2Fhermes.example&name=Hermes"
        compose.onNodeWithTag("setup-address").assertTextEquals("Paste link or address", typed)
        compose.onNodeWithTag("setup-connect").performClick()
        assertEquals(typed, probed)

        val address = "https://hermes.example"
        compose.onNodeWithTag("setup-address").performTextClearance()
        compose.onNodeWithTag("setup-address").performTextInput(address)
        compose.onNodeWithTag("setup-connect").performClick()
        assertEquals(address, probed)
    }

    @Test fun anOldPairingBlockPastedInTheAddressFieldKeepsOnlyTheAddress() {
        var probed = ""
        compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) {
            SetupWizardScreen(AppState(setupStep = "connect"), onProbe = { probed = it }, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
        } } }
        compose.onNodeWithTag("setup-address").performTextInput("Dashboard address: https://hermes.example\nPairing code: abc-123\nExpires: 10 minutes")
        compose.onNodeWithTag("setup-address").assertTextEquals("Paste link or address", "https://hermes.example")
        compose.onNodeWithTag("setup-pairing-code").assertDoesNotExist()
        compose.onNodeWithTag("setup-connect").performClick()
        assertEquals("https://hermes.example", probed)
        // A link or block that still carries a code is reduced to its address too.
        assertEquals("https://hermes.example", parseBeaconConnectInput("beacon://connect?url=https%3A%2F%2Fhermes.example&code=abc-123"))
        assertEquals("https://hermes.example", parseBeaconConnectInput("Dashboard: https://hermes.example\nPairing code: K7Q-4ZP"))
    }

    @Test fun primaryAndErrorColorsReadOnTheWizardSurfaceInLightAndDark() {
        val bases = listOf(
            lightColorScheme(primary = Color(0xFF80511D), error = Color(0xFFA93128)),
            darkColorScheme(primary = Color(0xFFE7B579), error = Color(0xFFFFB4AB)),
        )
        // The light-mode brown and red failed 4.5:1 on the sea background before the fix.
        assertTrue(nativeContrast(bases[0].primary, SetupSea) < 4.5f)
        for (base in bases) {
            val scheme = setupColorScheme(base)
            for (surface in listOf(SetupSea, scheme.surface, scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)) {
                assertTrue("primary on $surface", nativeContrast(scheme.primary, surface) >= 4.5f)
                assertTrue("error on $surface", nativeContrast(scheme.error, surface) >= 4.5f)
            }
            assertTrue("label on the filled button", nativeContrast(scheme.onPrimary, scheme.primary) >= 4.5f)
            assertTrue("body text", nativeContrast(scheme.onBackground, scheme.background) >= 4.5f)
        }
        // A colour that already reads is left alone.
        assertEquals(bases[1].primary, setupColorScheme(bases[1]).primary)
    }

    @Test fun everyRouteCountsTheSameFourSteps() {
        assertEquals(listOf(1, 1, 1), listOf("connect", "probe", "unsupported").map(::setupStepNumber))
        assertEquals(2, setupStepNumber("signin"))
        assertEquals(listOf(3, 3, 3, 3, 3, 3), listOf("voice-provider", "voice-key", "voice-customize", "permission-microphone", "permission-notifications", "permission-full-screen").map(::setupStepNumber))
        assertEquals(4, setupStepNumber("done"))
    }

    @Test fun voiceSetupMovesProviderToKeyToChoicesAndBack() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val store = VoiceKeyStore(app)
        val providers = listOf("gemini", "grok", "openai")
        val previousProvider = store.settings().provider
        val previousSelections = providers.associateWith { store.settings(it) }
        val previousKeys = providers.associateWith { store.key(it) }
        val previousLoader = VoiceCatalogCache.loader
        var state by mutableStateOf(AppState(origin = "https://hermes.example", authenticated = true, setupWizard = true, setupStep = "voice-provider", restoringSession = false))
        try {
            providers.forEach(store::removeKey)
            store.setSelection(VoicePreferences(provider = "gemini"))
            VoiceCatalogCache.loader = { _, _ -> VoiceCatalog() }
            compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) {
                SetupWizardScreen(
                    state = state, onProbe = {}, onSignIn = {}, onFinish = {}, onReopen = {}, onCancel = {},
                    // The ViewModel's rule: a provider with a key goes to the choices, one without goes to the key page.
                    onAdvancePermission = { state = state.copy(setupStep = when (state.setupStep) {
                        "voice-provider" -> if (store.hasKey(store.settings().provider)) "voice-customize" else "voice-key"
                        "voice-key" -> "voice-customize"
                        else -> "permission-microphone"
                    }) },
                    onVoicePage = { state = state.copy(setupStep = it) },
                )
            } } }
            // Provider: the saved provider is preselected and Continue is always on.
            compose.onNodeWithText("Choose a voice provider").assertIsDisplayed()
            compose.onNodeWithText("Step 3 of 4").assertIsDisplayed()
            compose.onNodeWithTag("voice-model-gemini").assertIsSelected()
            compose.onNodeWithTag("voice-model-grok").performClick()
            compose.onNodeWithTag("voice-model-grok").assertIsSelected()
            compose.onNodeWithTag("setup-voice-continue").performClick()

            // Key: a provider without a key asks for it, and nothing was committed yet.
            compose.waitUntil(5_000) { state.setupStep == "voice-key" }
            compose.onNodeWithText("Add your Grok key").assertIsDisplayed()
            compose.onNodeWithTag("voice-provider-key").assertIsDisplayed()
            assertEquals("gemini", store.settings().provider)
            // Back returns to the providers with the choice kept in the store, not changed.
            compose.onNodeWithTag("voice-page-back").performClick()
            compose.waitUntil(5_000) { state.setupStep == "voice-provider" }
            compose.onNodeWithTag("voice-model-gemini").assertIsSelected()

            // A provider that already has a key goes straight to the choices and is committed.
            store.saveKey("grok", "r5u-fixture-key")
            compose.onNodeWithTag("voice-model-grok").performClick()
            compose.onNodeWithTag("setup-voice-continue").performClick()
            compose.waitUntil(5_000) { state.setupStep == "voice-customize" }
            compose.onNodeWithText("Make it yours").assertIsDisplayed()
            assertEquals("grok", store.settings().provider)
            // Back from the choices returns to the providers; Continue again skips the key page.
            compose.onNodeWithTag("voice-page-back").performClick()
            compose.waitUntil(5_000) { state.setupStep == "voice-provider" }
            compose.onNodeWithTag("voice-model-grok").assertIsSelected()
            compose.onNodeWithTag("voice-key-test").assertDoesNotExist()
            compose.onNodeWithTag("setup-voice-continue").performClick()
            compose.waitUntil(5_000) { state.setupStep == "voice-customize" }
        } finally {
            VoiceCatalogCache.loader = previousLoader
            providers.forEach { provider -> previousKeys[provider]?.let { store.saveKey(provider, it) } ?: store.removeKey(provider) }
            previousSelections.values.forEach(store::setSelection)
            store.setSelection(previousSelections.getValue(previousProvider))
        }
    }

    @Test fun theKeyPageEnablesTestOnceAKeyIsTypedAndNeverCallsTheProviderByItself() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val store = VoiceKeyStore(app)
        val provider = store.settings().provider
        val previousKey = store.key(provider)
        val short = voiceProviderInfo(provider).short
        try {
            store.removeKey(provider)
            compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) {
                SetupWizardScreen(AppState(origin = "https://hermes.example", authenticated = true, setupWizard = true, setupStep = "voice-key", restoringSession = false),
                    onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
            } } }
            compose.onNodeWithText("Calls use your $short account.").assertIsDisplayed()
            compose.onNodeWithTag("voice-key-test").assertIsNotEnabled()
            compose.onNodeWithTag("voice-key-get").assertIsDisplayed()
            // The call-sharing note sits on the page where the key is handed over.
            compose.onNodeWithText("During a call, Beacon sends your assistant's instructions", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("voice-provider-key").performTextInput("not-a-real-key")
            compose.onNodeWithTag("voice-key-test").assertIsEnabled()
            compose.onNodeWithTag("voice-page-back").assertIsDisplayed()
            compose.onNodeWithTag("setup-voice-skip").assertIsDisplayed()
            // Leaving the page saves what is typed, so clear it before this test restores the real key.
            compose.onNodeWithTag("voice-provider-key").performTextClearance()
        } finally {
            previousKey?.let { store.saveKey(provider, it) } ?: store.removeKey(provider)
        }
    }

    @Test fun leavingTheKeyPageSavesAFirstKeyButNeverReplacesASavedOne() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val store = VoiceKeyStore(app)
        val provider = store.settings().provider
        val previousKey = store.key(provider)
        var shown by mutableStateOf(true)
        try {
            store.saveKey(provider, "saved-original-key")
            compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) {
                if (shown) SetupWizardScreen(AppState(origin = "https://hermes.example", authenticated = true, setupWizard = true, setupStep = "voice-key", restoringSession = false),
                    onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
            } } }
            // A stray paste over a working key is not saved when the page closes; Test key is what replaces it.
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Paste a new key to replace it.", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("voice-provider-key").performTextInput("stray-clipboard-text")
            compose.runOnUiThread { shown = false }
            compose.waitForIdle()
            assertEquals("saved-original-key", store.key(provider))
            // With no key saved yet, what was typed is kept.
            store.removeKey(provider)
            compose.runOnUiThread { shown = true }
            compose.onNodeWithTag("voice-provider-key").performTextInput("first-typed-key")
            compose.runOnUiThread { shown = false }
            compose.waitForIdle()
            assertEquals("first-typed-key", store.key(provider))
        } finally {
            previousKey?.let { store.saveKey(provider, it) } ?: store.removeKey(provider)
        }
    }

    @Test fun devicesListShowsThisPhoneAndEachOtherPhoneWithRemoveOnlyForOthers() {
        val removed = mutableListOf<String>()
        compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) {
            DevicesList(
                listOf(PairedDevice("this-1", "Pixel 9", "2026-10-24T10:00:00Z"), PairedDevice("other-2", "Galaxy Fold", "2026-10-23T09:00:00Z")),
                thisDeviceId = "this-1",
            ) { removed += it.id }
        } } }
        compose.onNodeWithText("This phone").assertIsDisplayed()
        compose.onNodeWithText("Pixel 9").assertIsDisplayed()
        compose.onNodeWithText("Linked", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Other phones").assertIsDisplayed()
        compose.onNodeWithText("Galaxy Fold").assertIsDisplayed()
        compose.onNodeWithTag("settings-device-remove-this-1").assertDoesNotExist()
        compose.onNodeWithTag("settings-device-remove-other-2").assertIsDisplayed().performClick()
        assertEquals(listOf("other-2"), removed)
    }

    private fun withConnectedFixture(pluginFree: Boolean, block: (BeaconViewModel, NativeExperienceFixture, BeaconApplication) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        check(!app.calls.state.value.active && app.calls.service == null)
        val stringKeys = listOf("origin", "setup_step", "setup_origin").map(::stringPreferencesKey)
        val booleanKeys = listOf("setup_complete", "setup_active").map(::booleanPreferencesKey)
        val before = runBlocking { app.preferences.data.first() }
        runBlocking { app.preferences.edit { it[stringPreferencesKey("origin")] = "" } }
        val fixture = NativeExperienceFixture().apply { if (pluginFree) { pluginStatus = 404; bootstrapPluginStatus = "absent" } }
        val pairingPrefs = app.getSharedPreferences("native-pairing", android.content.Context.MODE_PRIVATE)
        fun forgetLink() { pairingPrefs.edit().remove(NativePairing.accountKey(fixture.scope)).remove("unlinked:" + NativePairing.accountKey(fixture.scope).removePrefix("device:")).commit() }
        forgetLink()
        runBlocking { app.auth.persist(fixture.client.auth) }
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        try {
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            compose.waitUntil(5_000) { !vm.ui.value.restoringSession }
            compose.runOnIdle { vm.profileSwitchClientFactoryForTest = { auth -> fixture.freshClientFor(auth, app.auth) } }
            block(vm, fixture, app)
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative() }
            forgetLink()
            runBlocking {
                app.auth.remove(fixture.scope.origin)
                app.preferences.edit { values ->
                    stringKeys.forEach { key -> before[key]?.let { values[key] = it } ?: values.remove(key) }
                    booleanKeys.forEach { key -> before[key]?.let { values[key] = it } ?: values.remove(key) }
                }
            }
        }
    }

    @Test fun startupLinksThisPhoneAfterSignInAndKeepsOneIdForTheAccount() = withConnectedFixture(pluginFree = false) { vm, fixture, app ->
        compose.runOnIdle { vm.connect(fixture.scope.origin) }
        compose.waitUntil(15_000) { fixture.deviceLinkCount.get() == 1 }
        val first = fixture.deviceLinkBodies.single()
        assertTrue(first.string("device_name").isNotBlank())
        assertFalse("the first link lets Hermes create the record", first.has("device_id"))
        compose.waitUntil(5_000) { NativePairing.deviceId(app, fixture.scope) == "device-123" }
        // The next start sends the stored id and refreshes the same record: one id per account.
        compose.runOnIdle { vm.connect(fixture.scope.origin) }
        compose.waitUntil(15_000) { fixture.deviceLinkCount.get() == 2 }
        assertEquals("device-123", fixture.deviceLinkBodies.last().string("device_id"))
        assertEquals(1, fixture.pairedDevices.size)
        assertTrue(fixture.requests.none { it.string("path").contains("/pairing") })
    }

    @Test fun aHermesWithoutThePluginIsNeverAskedToLinkThisPhone() = withConnectedFixture(pluginFree = true) { vm, fixture, app ->
        compose.runOnIdle { vm.connect(fixture.scope.origin) }
        compose.waitUntil(15_000) { vm.ui.value.authenticated && vm.ui.value.setupPluginMessage.isNotBlank() }
        compose.waitForIdle()
        assertEquals(0, fixture.deviceLinkCount.get())
        assertNull(NativePairing.deviceId(app, fixture.scope))
        assertTrue("chat stays usable without the plugin", vm.ui.value.authenticated)
    }

    @Test fun aPhoneRemovedOnThePcIsOnlyLinkedAgainByASignInOrAddThisPhone() = withConnectedFixture(pluginFree = false) { _, fixture, app ->
        runBlocking {
            assertEquals("device-123", NativePairing.link(app, fixture.client))
            // Removed on the PC: an automatic link (app start) leaves it removed.
            NativePairing.clearDeviceId(app, fixture.scope)
            assertNull(NativePairing.link(app, fixture.client, explicit = false))
            assertEquals(1, fixture.deviceLinkCount.get())
            assertNull(NativePairing.deviceId(app, fixture.scope))
            // Signing in again, or "Add this phone", links it.
            assertEquals("device-123", NativePairing.link(app, fixture.client, explicit = true))
            assertEquals("device-123", NativePairing.deviceId(app, fixture.scope))
            assertEquals("device-123", NativePairing.link(app, fixture.client, explicit = false))
        }
    }

    @Test fun openingExternalLinkStoresPendingOriginAndCancelKeepsTheActiveAccount() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val originKey = stringPreferencesKey("origin")
        val setupOriginKey = stringPreferencesKey("setup_origin")
        val setupStepKey = stringPreferencesKey("setup_step")
        val setupCompleteKey = booleanPreferencesKey("setup_complete")
        val setupActiveKey = booleanPreferencesKey("setup_active")
        val before = app.preferences.data.first()
        val activeOrigin = "https://active-${java.util.UUID.randomUUID()}.example"
        val pendingOrigin = "https://pending-${java.util.UUID.randomUUID()}.example"
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        try {
            app.preferences.edit { values ->
                values[originKey] = activeOrigin
                values[setupCompleteKey] = true
                values[setupActiveKey] = false
                values.remove(setupOriginKey)
            }
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
            }
            compose.waitUntil(5_000) { !vm.ui.value.restoringSession }
            val link = "beacon://connect?url=${Uri.encode(pendingOrigin)}&name=Hermes"
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
        compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) {
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
        compose.setContent { BeaconTheme(dark = true) {
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

    @Test fun missingPluginMessageIsShownWithoutBlockingSignIn() {
        val message = "Beacon plugin not installed on this Hermes — chat works; calls need the plugin."
        val state = AppState(origin = "https://hermes.example", authenticated = true, setupWizard = true, setupStep = "done", restoringSession = false, setupPluginMessage = message)
        compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) {
            SetupWizardScreen(state, onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
        } } }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithTag("setup-finish").assertIsDisplayed()
    }

    @Test fun anInterruptedSetupResumesAtItsIncompletePermissionStep() {
        val state = AppState(origin = "https://hermes.example", authenticated = true, setupWizard = true, setupStep = "permission-notifications", restoringSession = false)
        compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) {
            SetupWizardScreen(state, onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
        } } }
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        compose.onNodeWithTag("setup-permission-primary").assertIsDisplayed()
    }

    @Test fun expiredRefreshOffersOneTapSignInHidesPrivateStateAndRetainsTheScopedDraft() {
        check(!ApplicationProvider.getApplicationContext<BeaconApplication>().calls.state.value.active)
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val originKey = stringPreferencesKey("origin")
        val setupStepKey = stringPreferencesKey("setup_step")
        val setupCompleteKey = androidx.datastore.preferences.core.booleanPreferencesKey("setup_complete")
        val setupActiveKey = androidx.datastore.preferences.core.booleanPreferencesKey("setup_active")
        val priorPreferences = runBlocking { app.preferences.data.first() }
        runBlocking { app.preferences.edit { it[originKey] = "" } }
        val store = ViewModelStore()
        val origin = "https://expired-${java.util.UUID.randomUUID()}.example"
        val scope = Scope(origin, "fixture-owner", "work", nativeProvider = "fixture")
        val client = BeaconClient(AuthContext(origin, emptyList(), scope.owner, scope.profile, backend = "hermes-dashboard", nativeProvider = scope.nativeProvider))
        lateinit var vm: BeaconViewModel
        try {
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
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
            val fail = BeaconViewModel::class.java.getDeclaredMethod("fail", Throwable::class.java).apply { isAccessible = true }
            compose.runOnIdle { fail.invoke(vm, NativeAuthExpiredException()) }
            compose.waitUntil(5_000) { vm.ui.value.setupWizard && vm.ui.value.setupStep == "signin" }
            assertEquals("", vm.composer.value.text)
            assertTrue(vm.chat.value.messages.isEmpty())
            compose.waitUntil(5_000) { runBlocking { app.cache.read(scope.session("chat-a"), "draft") != null } }
            val retained = runBlocking { app.cache.read(scope.session("chat-a"), "draft") }
            assertEquals("Keep this unfinished answer", dev.hermes.android.core.Draft.decode(checkNotNull(retained)).text)
            assertNull(runBlocking { app.cache.read(scope.copy(nativeProvider = "other").session("chat-a"), "draft") })

            var signInTaps = 0
            compose.setContent { val state by vm.ui.collectAsState(); NativeLocalization("en") { BeaconTheme(dark = true) {
                SetupWizardScreen(state, onProbe = {}, onSignIn = { signInTaps++ }, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
            } } }
            compose.onNodeWithTag("setup-signin").performClick()
            assertEquals(1, signInTaps)
            assertEquals("", vm.composer.value.text)
            assertTrue(vm.chat.value.messages.isEmpty())
            assertEquals(retained, runBlocking { app.cache.read(scope.session("chat-a"), "draft") })
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
