package dev.hermes.android

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.json
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * Local-fixture proof for the Settings overview. The stateless overview checks use fixed summaries; the navigation and
 * storage checks use real ViewModels with a blank origin, so they exercise routes and summaries without contacting Hermes.
 * Phone and tablet widths come from a density override, so the layout rule is exercised on any device.
 * A node only counts once it has been scrolled into the viewport and asserted displayed: a lazy list can compose
 * nodes that are off screen, and existing there proves nothing about the user seeing or reaching them.
 */
class SettingsMigrationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** [snapshot] null means no summaries at all; otherwise rows show exactly what the production overview derives. */
    private class Host(snapshot: SettingsSnapshot?) {
        var snapshot by mutableStateOf(snapshot)
        var category by mutableStateOf("")
        var pluginAbsent by mutableStateOf(false)
        val opened = mutableListOf<String>()
    }

    private val known = SettingsSnapshot(
        connected = true, profile = "work", sendKey = "ctrl_enter", formatMessages = false, showThinking = true,
        autoFollow = true, animateBackground = true,
        theme = "dark", colorStyle = "graphite", installedVersion = "0.4.14", voiceProviderId = "gemini", voiceKeySaved = true,
        hardwareKeyboard = true,
    )

    private fun show(widthDp: Float, fontScale: Float = 1f, snapshot: SettingsSnapshot? = known): Host {
        val host = Host(snapshot)
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalDensity provides Density(constraints.maxWidth / widthDp, fontScale)) {
                    BeaconTheme(dark = true) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Box(Modifier.fillMaxSize()) {
                                // Same translate-flag handling as SettingsHome: fixed words via uiText, user data verbatim.
                                val summaries = host.snapshot?.let { localizedSettingsSummaries(it) } ?: emptyMap()
                                SettingsOverview(summaries, { host.opened += it }, host.category)
                                VoiceSettingsPluginNote(host.pluginAbsent)
                            }
                        }
                    }
                }
            }
        }
        return host
    }
    private fun reach(node: SemanticsNodeInteraction) = node.performScrollTo().assertIsDisplayed()
    private fun top() { compose.onNodeWithTag("settings-overview").performScrollToIndex(0) }
    private fun row(name: String) = compose.onNodeWithTag(when (name) {
        "Connection" -> "settings-connection"
        "Chat" -> "settings-row:Settings"
        "Voice & calls" -> "settings-row:Beacon"
        "Notifications & Keep connected" -> "settings-row:Notifications"
        "Profiles & model" -> "settings-row:Profiles"
        else -> "settings-row:$name"
    })
    private fun shown(name: String) = reach(row(name))
    private fun tap(name: String) { shown(name).performClick() }
    private fun tapSearch(name: String) { reach(compose.onNodeWithTag("settings-row:$name")).performClick() }
    /** The field is the first lazy item, so it is gone after scrolling down a long result list: return to it, prove it is shown, then type. */
    private fun search(text: String) {
        top()
        compose.onNodeWithTag("settings-search").assertIsDisplayed().performTextReplacement(text)
    }
    @Test fun phoneShowsRelayOrderedHubRowsAndLiveSummaries() {
        show(widthDp = PhoneWidthDp)
        shown("Connection").assertTextContains("work")
        shown("Connection").assertTextContains("Connected")
        shown("Chat").assertTextContains("Enter adds a line · Thinking shown")
        shown("Voice & calls").assertTextContains("Gemini Live")
        shown("Voice & calls").assertTextContains("Key saved")
        shown("Appearance").assertTextContains("Dark · Graphite")
        shown("Profiles & model").assertTextContains("work")
        shown("App updates").assertTextContains("0.4.14")
        shown("About & licenses").assertTextContains("Version · 0.4.14")
        val positions = settingsHubRoutes.map { shown(it).fetchSemanticsNode().positionInRoot }
        assertTrue("The hub uses one column", positions.all { it.x == positions.first().x })
        for (section in listOf("Assistant", "Hermes", "App")) compose.onNodeWithTag("settings-group:$section").assertExists()
        compose.onNodeWithTag("settings-group:Preferences").assertDoesNotExist()
        compose.onNodeWithTag("settings-group:Assistant & server").assertDoesNotExist()
    }

    @Test fun pairedDevicesAreReachableFromConnectionInHubAndSearch() {
        val host = show(widthDp = PhoneWidthDp)
        tap("Connection")
        assertEquals(listOf("Connection"), host.opened)

        search("phones")
        compose.onNodeWithTag("settings-row:Connection").assertIsDisplayed()
        tapSearch("Connection")
        assertEquals(listOf("Connection", "Connection"), host.opened)
    }

    @Test fun tabletKeepsTheSameSingleOrderedList() {
        show(widthDp = TabletWidthDp)
        val positions = settingsHubRoutes.map { shown(it).fetchSemanticsNode().positionInRoot }
        assertTrue("The hub stays in one column on tablets", positions.all { it.x == positions.first().x })
    }

    @Test fun largeFontOnPhoneKeepsRowsInsideTheScreenAndTouchable() {
        show(widthDp = PhoneWidthDp, fontScale = 2f)
        val rootWidthPx = compose.onRoot().fetchSemanticsNode().size.width
        val pxPerDp = rootWidthPx / PhoneWidthDp
        for (name in settingsHubRoutes) {
            shown(name)
            val node = row(name).fetchSemanticsNode()
            assertTrue("$name is shorter than its ${SettingsRowMinHeight.value}dp row floor", node.size.height / pxPerDp >= SettingsRowMinHeight.value - .5f)
            assertTrue("$name overflows the screen width", node.positionInRoot.x + node.size.width <= rootWidthPx + 1f)
        }
        shown("Appearance").assertTextContains("Dark · Graphite")
    }

    @Test fun everyHubRowEmitsItsRoute() {
        val host = show(widthDp = PhoneWidthDp)
        for (name in settingsHubRoutes) {
            tap(name)
            assertEquals(settingsRoute(name), host.opened.last())
        }
    }

    @Test fun searchFindsEveryDestinationAndOpensItsRoute() {
        val host = show(widthDp = PhoneWidthDp)
        val expected = mutableListOf<String>()
        for (route in settingsSearchRoutes) {
            search(settingsLabel(route))
            tapSearch(route)
            expected += settingsRoute(route)
        }
        assertEquals(expected, host.opened)
        search("zzz-no-such-setting")
        compose.onNodeWithText("No matching settings").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("settings-row:Settings")).assertCountEquals(0)
        search("appearance")
        shown("Appearance").assertTextContains("Dark · Graphite")
    }

    @Test fun searchFieldIsReachableAgainAfterAScrolledResultList() {
        show(widthDp = PhoneWidthDp, fontScale = 2f)
        search("e")
        shown("About & licenses")
        search("appearance")
        shown("Appearance")
    }

    @Test fun categoryListsKeepTheirPagesAndPlainRoutes() {
        val host = show(widthDp = PhoneWidthDp)
        val pages = settingsCategories
        val expected = mutableListOf<String>()
        for ((category, leaves) in pages) {
            compose.runOnIdle { host.category = category }
            compose.waitForIdle()
            for (leaf in leaves) { tap(leaf); expected += leaf }
        }
        compose.runOnIdle { host.category = "" }
        compose.waitForIdle()
        for (category in pages.keys) { tap(category); expected += settingsRoute(category) }
        assertEquals(expected, host.opened)
    }

    @Test fun summariesKeepUnknownsHonestAndExplainTheMissingPlugin() {
        val host = show(widthDp = PhoneWidthDp, snapshot = null)
        shown("Appearance").assertTextContains("Theme and colors")
        shown("Chat").assertTextContains("Sending and display")
        shown("Connection").assertTextContains("Server, account, phones")
        compose.runOnIdle { host.snapshot = known }
        shown("Appearance").assertTextContains("Dark · Graphite")
        shown("Chat").assertTextContains("Enter adds a line · Thinking shown")
        compose.runOnIdle { host.snapshot = known.copy(theme = "light", updateName = "0.4.15") }
        shown("Appearance").assertTextContains("Light · Graphite")
        shown("App updates").assertTextContains("Update available")
        shown("App updates").assertTextContains("0.4.15")
        compose.runOnIdle { host.snapshot = known.copy(connected = false) }
        shown("Connection").assertTextContains("Offline")
        shown("Connection").assertTextContains("work")
        shown("Notifications & Keep connected").assertTextContains("Alerts and calls")
        assertTrue("Reading a summary must not open a route", host.opened.isEmpty())
        compose.runOnIdle { host.pluginAbsent = true }
        compose.onNodeWithText("Add Beacon to Hermes").assertIsDisplayed()
        compose.runOnIdle { host.pluginAbsent = false }
        compose.onNodeWithText("Calls need the Beacon plugin on your Hermes.").assertDoesNotExist()
    }

    @Test fun unloadedOrFailedChatRecordsKeepDescriptionsAndVoiceSummaryUsesPhoneState() {
        val host = show(widthDp = PhoneWidthDp, snapshot = known.withLoaded(null))
        // Real non-default values, so the static descriptions prove the markers suppressed them.
        fun chat() = json("send_key" to "ctrl_enter", "render_user_markdown" to false, "show_thinking" to true)
        val unavailable = listOf<JSONObject?>(
            null,
            chat().put("_error", "Reconnect to refresh this page."),
            chat().put("_loading", true),
        )
        for (record in unavailable) {
            compose.runOnIdle { host.snapshot = known.withLoaded(record) }
            shown("Chat").assertTextContains("Sending and display")
            shown("Voice & calls").assertTextContains("Gemini Live")
            shown("Voice & calls").assertTextContains("Key saved")
            shown("Appearance").assertTextContains("Dark · Graphite")
        }
        compose.runOnIdle { host.snapshot = known.withLoaded(json("send_key" to "shift+enter")) }
        shown("Chat").assertTextContains("Enter adds a line")
        shown("Voice & calls").assertTextContains("Gemini Live")
        shown("Voice & calls").assertTextContains("Key saved")
        compose.runOnIdle { host.snapshot = known.copy(voiceProviderId = "grok", voiceKeySaved = false).withLoaded(json("send_key" to "enter")) }
        shown("Chat").assertTextContains("Enter sends")
        shown("Voice & calls").assertTextContains("Grok")
        shown("Voice & calls").assertTextContains("Add key")
        assertTrue(host.opened.isEmpty())
    }

    @Test fun composedOverviewTracksLocalVoiceSelectionAndKeyChanges() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        check(BuildConfig.DEBUG && app.packageName.endsWith(".debug")) { "Use an isolated debug install" }
        val priorPreferences = runBlocking { app.preferences.data.first() }
        val originKey = stringPreferencesKey("origin")
        check(priorPreferences[originKey].isNullOrBlank()) { "Use a debug fixture without a saved connection" }
        val prefix = "settings-voice-summary-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            // SettingsHome uses the application context for both the listener and encrypted store.
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
        }
        val voicePreferences = context.getSharedPreferences(VoiceKeyStore.PREFS, Context.MODE_PRIVATE)
        val voiceStore = VoiceKeyStore(context)
        val store = ViewModelStore()
        var mounted by mutableStateOf(true)
        // The provider is the row's value and the key state its pill, so both must show on the one row.
        fun summary(provider: String, pill: String) {
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasTestTag("settings-row:Beacon") and hasText(provider) and hasText(pill)).fetchSemanticsNodes().size == 1
            }
            shown("Voice & calls").assertTextContains(provider)
            shown("Voice & calls").assertTextContains(pill)
        }
        try {
            runBlocking { app.preferences.edit { it[originKey] = "" } }
            voiceStore.setSelection(voiceStore.settings("gemini"))
            voiceStore.saveKey("gemini", "synthetic-gemini-key")
            val geminiCiphertext = voicePreferences.getString("key.gemini", null)
            lateinit var vm: BeaconViewModel
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
            }
            compose.setContent {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(constraints.maxWidth / TabletWidthDp, 1f)) {
                        BeaconTheme(dark = true) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                if (mounted) MoreScreen(vm)
                            }
                        }
                    }
                }
            }
            compose.waitUntil(5_000) { vm.ui.value.origin.isBlank() && !vm.ui.value.restoringSession }
            compose.onNodeWithTag("settings-wide-list").assertIsDisplayed()
            compose.onNodeWithTag("settings-wide-detail").assertIsDisplayed()
            summary("Gemini Live", "Key saved")
            voiceStore.setSelection(voiceStore.settings("grok"))
            summary("Grok", "Add key")
            voiceStore.saveKey("grok", "synthetic-grok-key")
            summary("Grok", "Key saved")
            voiceStore.removeKey("grok")
            summary("Grok", "Add key")
            voiceStore.setSelection(voiceStore.settings("gemini"))
            summary("Gemini Live", "Key saved")
            assertEquals(geminiCiphertext, voicePreferences.getString("key.gemini", null))
            assertFalse(voicePreferences.contains("key.grok"))
        } finally {
            try {
                compose.runOnIdle { mounted = false }
                compose.waitForIdle()
            } finally {
                compose.runOnUiThread { store.clear() }
                runBlocking { app.preferences.updateData { priorPreferences } }
                check(voicePreferences.edit().clear().commit())
            }
        }
    }

    private fun withOfflineSettings(
        widthDp: Float = PhoneWidthDp,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        verify: (VoiceKeyStore) -> Unit,
    ) {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        check(BuildConfig.DEBUG && app.packageName.endsWith(".debug")) { "Use an isolated debug install" }
        check(runBlocking { app.preferences.data.first()[stringPreferencesKey("origin")] }.isNullOrBlank()) {
            "Use a debug fixture without a saved connection"
        }
        val prefix = "settings-offline-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
        }
        val store = ViewModelStore()
        var mounted by mutableStateOf(true)
        try {
            lateinit var vm: BeaconViewModel
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
            }
            compose.setContent {
                NativeLocalization(if (direction == LayoutDirection.Rtl) "ar" else "en") {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        CompositionLocalProvider(
                            LocalContext provides context,
                            LocalDensity provides Density(constraints.maxWidth / widthDp, fontScale),
                            LocalLayoutDirection provides direction,
                        ) {
                            BeaconTheme(dark = true) {
                                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                    if (mounted) MoreScreen(vm)
                                }
                            }
                        }
                    }
                }
            }
            compose.waitUntil(5_000) { vm.ui.value.origin.isBlank() && !vm.ui.value.restoringSession }
            compose.runOnIdle { vm.tab("More") }
            verify(VoiceKeyStore(context))
        } finally {
            try {
                compose.runOnIdle { mounted = false }
                compose.waitForIdle()
            } finally {
                compose.runOnUiThread { store.clear() }
                app.deleteSharedPreferences("$prefix-${VoiceKeyStore.PREFS}")
            }
        }
    }

    @Test fun savedVoiceKeyCanBeManagedAndRemovedFromSettings() {
        val previousLoader = VoiceCatalogCache.loader
        try {
            VoiceCatalogCache.loader = { _, _ -> VoiceCatalog() }
            withOfflineSettings { keys ->
                val other = keys.settings("grok").copy(model = "fixture-voice-model", voice = "ara", personality = "warm")
                keys.setSelection(other)
                keys.saveKey("grok", "synthetic-other-provider-key")
                val selected = keys.settings("gemini").copy(voice = "Kore", personality = "concise")
                keys.setSelection(selected)
                fun click(tag: String) { reach(compose.onNodeWithTag(tag)).performClick() }
                fun back() { compose.onNodeWithContentDescription("Back").performClick() }
                fun switchProvider(provider: String, name: String) {
                    click("voice-provider-change")
                    click("voice-model-$provider")
                    click("setup-voice-continue")
                    compose.onNodeWithTag("voice-hero-title").assertTextEquals(name)
                }

                tap("Voice & calls")
                click("voice-add-key")
                compose.onNodeWithTag("voice-provider-key").performTextReplacement("synthetic-gemini-key")
                back()
                compose.waitUntil(5_000) { keys.hasKey("gemini") }
                back()
                shown("Voice & calls").assertTextContains("Key saved")
                tap("Voice & calls")
                switchProvider("grok", "Grok")
                switchProvider("gemini", "Gemini Live")

                click("voice-manage-key")
                compose.onNodeWithText("Saved").assertIsDisplayed()
                compose.onNodeWithTag("voice-provider-key").performTextReplacement("synthetic-unsaved-replacement")
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                click("voice-key-remove")
                compose.onNodeWithText("Cancel").performClick()
                assertEquals("synthetic-gemini-key", keys.key("gemini"))
                click("voice-key-remove")
                compose.onNodeWithTag("voice-key-remove-confirm").performClick()
                reach(compose.onNodeWithTag("voice-add-key"))
                back()
                shown("Voice & calls").assertTextContains("Add key")
                tap("Voice & calls")
                reach(compose.onNodeWithTag("voice-add-key")).assertTextEquals("Add key")
                compose.onNodeWithTag("voice-manage-key").assertDoesNotExist()
                compose.onNodeWithTag("voice-provider-summary").assertTextEquals("Add a key to call")
                assertNull(keys.session())
                assertEquals(selected, keys.settings())
                assertEquals(other, keys.settings("grok"))
                assertEquals("synthetic-other-provider-key", keys.key("grok"))
            }
        } finally {
            VoiceCatalogCache.loader = previousLoader
        }
    }

    @Test fun colorStyleNamesFitAtLargeTextOnAPhone() = verifyColorStyleNames(411f, 1.8f, LayoutDirection.Ltr)
    @Test fun colorStyleNamesFitAtMaximumTextOnANarrowPhone() = verifyColorStyleNames(320f, 2f, LayoutDirection.Ltr)
    @Test fun colorStyleNamesFitAtLargeTextInRtl() = verifyColorStyleNames(411f, 1.8f, LayoutDirection.Rtl)
    @Test fun colorStyleNamesFitAtMaximumTextOnANarrowRtlPhone() = verifyColorStyleNames(320f, 2f, LayoutDirection.Rtl)

    private fun verifyColorStyleNames(widthDp: Float, fontScale: Float, direction: LayoutDirection) =
        withOfflineSettings(widthDp, fontScale, direction) {
            tap("Appearance")
            val page = compose.onNodeWithTag("appearance-settings")
            val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
            val textLayout = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
            for (skin in NativeSkins) {
                val tag = "skin-option:$skin"
                page.performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertIsDisplayed().assert(radio).assertHasClickAction()
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNode(textLayout and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                    .assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                val text = layout.layoutInput.text.text
                val lastLine = layout.lineCount - 1
                val label = "$text at ${widthDp}dp, font $fontScale, $direction"
                // The layout reconstructed from semantics can report overflow for text that fits.
                assertFalse("$label ends in an ellipsis", layout.isLineEllipsized(lastLine))
                assertEquals("$label shows every character", text.length, layout.getLineEnd(lastLine, visibleEnd = true))
                for (line in 1 until layout.lineCount) {
                    val start = layout.getLineStart(line)
                    assertTrue("$label breaks inside a word at $start", text[start - 1].isWhitespace() || text[start].isWhitespace())
                }
            }
            if (direction == LayoutDirection.Rtl && widthDp == 411f) {
                page.performScrollToNode(hasTestTag("skin-option:default"))
                val first = compose.onNodeWithTag("skin-option:default").getUnclippedBoundsInRoot()
                val second = compose.onNodeWithTag("skin-option:ares").getUnclippedBoundsInRoot()
                assertTrue("The first row starts on the right", first.top == second.top && first.left > second.left)
            }
        }

    @Test fun settingsPagesShowTheirTitleOnlyInTheTopBar() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val priorPreferences = runBlocking { app.preferences.data.first() }
        val store = ViewModelStore()
        try {
            runBlocking { app.preferences.edit { it[stringPreferencesKey("origin")] = "" } }
            val vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
            var page by mutableStateOf("Assistant")
            compose.setContent {
                BeaconTheme(dark = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        if (page == "Assistant") Column {
                            Header(page, back = {}, menu = null); AssistantSettings(vm)
                        } else NativeProfilesPage(vm)
                    }
                }
            }

            compose.onAllNodesWithText("Assistant").assertCountEquals(1)
            compose.onNodeWithTag("owner-name").assertIsDisplayed()
            compose.runOnIdle { page = "Profiles" }
            compose.waitForIdle()
            compose.onAllNodesWithText("Profiles").assertCountEquals(1)
        } finally {
            compose.runOnUiThread { store.clear() }
            runBlocking { app.preferences.updateData { priorPreferences } }
        }
    }

    @Test fun everyHubRowNavigatesAndBackReturnsToTheHub() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val priorPreferences = runBlocking { app.preferences.data.first() }
        val store = ViewModelStore()
        try {
            runBlocking { app.preferences.edit { it[stringPreferencesKey("origin")] = "" } }
            val vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
            compose.setContent {
                BeaconTheme(dark = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MoreScreen(vm) }
                }
            }
            compose.waitUntil(5_000) { vm.ui.value.origin.isBlank() && !vm.ui.value.restoringSession }
            compose.runOnIdle { vm.tab("More") }
            for (name in settingsHubRoutes) {
                shown(name).performClick()
                val route = settingsRoute(name)
                compose.waitUntil(5_000) { vm.ui.value.tab == "More" && vm.ui.value.morePage == route }
                compose.runOnIdle { vm.backFromSettings() }
                compose.waitUntil(5_000) { vm.ui.value.tab == "More" && vm.ui.value.morePage.isBlank() }
                compose.onNodeWithTag("settings-overview").assertExists()
            }
        } finally {
            compose.runOnUiThread { store.clear() }
            runBlocking { app.preferences.updateData { priorPreferences } }
        }
    }

    private companion object {
        const val PhoneWidthDp = 360f
        const val TabletWidthDp = 1000f
    }
}
