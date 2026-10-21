package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.json
import dev.hermes.android.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Local-fixture proof for the Settings overview. The stateless overview checks use fixed summaries; the navigation check
 * uses a real ViewModel with a blank origin, so it exercises routes without contacting Hermes.
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
    )

    private fun show(widthDp: Float, fontScale: Float = 1f, snapshot: SettingsSnapshot? = known): Host {
        val host = Host(snapshot)
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalDensity provides Density(constraints.maxWidth / widthDp, fontScale)) {
                    CypherTheme(dark = true) {
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
        "Voice & calls" -> "settings-row:Cypher"
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
        shown("Connection").assertTextContains("Connected privately · work")
        shown("Chat").assertTextContains("Ctrl + Enter to send · Thinking shown")
        shown("Voice & calls").assertTextContains("Gemini Live · Key saved")
        shown("Appearance").assertTextContains("Dark · Graphite")
        shown("Profiles & model").assertTextContains("Active profile · work")
        shown("App updates").assertTextContains("Installed · 0.4.14")
        shown("About & licenses").assertTextContains("Version · 0.4.14")
        val positions = settingsHubRoutes.map { shown(it).fetchSemanticsNode().positionInRoot }
        assertTrue("The hub uses one column", positions.all { it.x == positions.first().x })
        for (section in listOf("Assistant", "Hermes", "App")) compose.onNodeWithTag("settings-group:$section").assertExists()
        compose.onNodeWithTag("settings-group:Preferences").assertDoesNotExist()
        compose.onNodeWithTag("settings-group:Assistant & server").assertDoesNotExist()
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
        shown("Appearance").assertTextContains("Theme and color style")
        shown("Chat").assertTextContains("Sending and display")
        shown("Connection").assertTextContains("Server and signed-in account")
        compose.runOnIdle { host.snapshot = known }
        shown("Appearance").assertTextContains("Dark · Graphite")
        shown("Chat").assertTextContains("Ctrl + Enter to send · Thinking shown")
        compose.runOnIdle { host.snapshot = known.copy(theme = "light", updateName = "0.4.15") }
        shown("Appearance").assertTextContains("Light · Graphite")
        shown("App updates").assertTextContains("Update available · 0.4.15")
        compose.runOnIdle { host.snapshot = known.copy(connected = false) }
        shown("Connection").assertTextContains("Connection needs attention · work")
        shown("Notifications & Keep connected").assertTextContains("Notification delivery")
        assertTrue("Reading a summary must not open a route", host.opened.isEmpty())
        compose.runOnIdle { host.pluginAbsent = true }
        compose.onNodeWithText("Add Cypher to your Hermes").assertIsDisplayed()
        compose.runOnIdle { host.pluginAbsent = false }
        compose.onNodeWithText("Calls need the Cypher plugin on your Hermes.").assertDoesNotExist()
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
            shown("Voice & calls").assertTextContains("Gemini Live · Key saved")
            shown("Appearance").assertTextContains("Dark · Graphite")
        }
        compose.runOnIdle { host.snapshot = known.withLoaded(json("send_key" to "shift+enter")) }
        shown("Chat").assertTextContains("Shift + Enter to send")
        shown("Voice & calls").assertTextContains("Gemini Live · Key saved")
        compose.runOnIdle { host.snapshot = known.copy(voiceProviderId = "grok", voiceKeySaved = false).withLoaded(json("send_key" to "enter")) }
        shown("Chat").assertTextContains("Enter to send")
        shown("Voice & calls").assertTextContains("Grok · Add key")
        assertTrue(host.opened.isEmpty())
    }

    @Test fun everyHubRowNavigatesAndBackReturnsToTheHub() {
        val app = ApplicationProvider.getApplicationContext<CypherApplication>()
        val priorPreferences = runBlocking { app.preferences.data.first() }
        val store = ViewModelStore()
        try {
            runBlocking { app.preferences.edit { it[stringPreferencesKey("origin")] = "" } }
            val vm = ViewModelProvider(store, viewModelFactory { initializer { CypherViewModel(app, SavedStateHandle()) } })[CypherViewModel::class.java]
            compose.setContent {
                CypherTheme(dark = true) {
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
