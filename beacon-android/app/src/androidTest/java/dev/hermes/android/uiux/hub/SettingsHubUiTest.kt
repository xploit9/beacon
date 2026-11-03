package dev.hermes.android.uiux.hub

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertCountEquals
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.preferences
import dev.hermes.android.ui.MoreScreen
import dev.hermes.android.ui.NativeSkins
import dev.hermes.android.ui.SettingsOverview
import dev.hermes.android.ui.SettingsSnapshot
import dev.hermes.android.ui.localizedSettingsSummaries
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Settings hub, Connection, Chat and Appearance on a device: hub rows carry a value and a state pill under their old tags, and the
 * pages show their choices as segments and swatches. The page checks use a real ViewModel with a blank origin, so nothing contacts Hermes.
 */
class SettingsHubUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val signedIn = SettingsSnapshot(
        connected = true, account = "maya", profile = "Personal", installedVersion = "1.1.22", voiceProviderId = "gemini", voiceKeySaved = true,
        keepConnectedEnabled = true, theme = "dark", colorStyle = "graphite",
    )

    private fun showHub(snapshot: SettingsSnapshot?): androidx.compose.runtime.MutableState<SettingsSnapshot?> {
        val state = mutableStateOf(snapshot)
        compose.setContent {
            BeaconTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        SettingsOverview(state.value?.let { localizedSettingsSummaries(it) } ?: emptyMap(), {})
                    }
                }
            }
        }
        return state
    }
    private fun row(tag: String) = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()

    @Test fun hubRowsShowTheirValueAndStatePillUnderTheOldTags() {
        showHub(signedIn)
        row("settings-connection").assertTextContains("maya · Personal").assertTextContains("Connected")
        row("settings-row:Beacon").assertTextContains("Gemini Live").assertTextContains("Key saved")
        row("settings-row:Notifications").assertTextContains("Keep connected is on")
        row("settings-row:App updates").assertTextContains("1.1.22")
        for (section in listOf("Assistant", "Hermes", "App")) compose.onNodeWithTag("settings-group:$section").assertExists()
    }

    @Test fun theConnectionPillNamesWhyThePhoneIsNotConnected() {
        val state = showHub(signedIn.copy(connected = false))
        row("settings-connection").assertTextContains("Offline")
        compose.runOnIdle { state.value = signedIn.copy(connected = false, signedOut = true) }
        row("settings-connection").assertTextContains("Sign in")
        compose.runOnIdle { state.value = signedIn.copy(pluginAction = "Restart Hermes") }
        row("settings-row:App updates").assertTextContains("Restart Hermes")
        compose.runOnIdle { state.value = signedIn.copy(updateName = "1.1.23", pluginAction = "Restart Hermes") }
        row("settings-row:App updates").assertTextContains("Update available")
    }

    @Test fun unknownRowsKeepTheirShortDescriptionAndShowNoPill() {
        showHub(null)
        row("settings-connection").assertTextContains("Server, account, phones")
        row("settings-row:Appearance").assertTextContains("Theme and colors")
        row("settings-row:Notifications").assertTextContains("Alerts and calls")
        row("settings-row:Beacon").assertTextContains("Voice and provider")
    }

    @Test fun phoneAccessSaysOffUntilSomethingIsOnThenNamesWhatIsOn() {
        val state = showHub(signedIn.copy(phoneAccess = emptyList()))
        row("settings-row:Phone access").assertTextContains("Off")
        compose.runOnIdle { state.value = signedIn.copy(phoneAccess = listOf("Contacts", "Messages")) }
        row("settings-row:Phone access").assertTextContains("Contacts · Messages")
        compose.onAllNodesWithText("Off").assertCountEquals(0)
    }

    @Test fun phoneAccessKeepsItsDescriptionUntilTheSwitchesAreRead() {
        showHub(signedIn)
        row("settings-row:Phone access").assertTextContains("Calendar, contacts and calls")
    }

    @Test fun searchingAnOldDescriptionWordStillFindsItsRow() {
        showHub(signedIn)
        compose.onNodeWithTag("settings-overview").performScrollToIndex(0)
        compose.onNodeWithTag("settings-search").performTextReplacement("delivery")
        compose.onNodeWithTag("settings-row:Notifications & Keep connected").assertIsDisplayed()
        compose.onNodeWithTag("settings-search").performTextReplacement("zzz-no-such-setting")
        compose.onNodeWithText("No matching settings").assertIsDisplayed()
    }

    private fun withPage(route: String, check: () -> Unit) {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val prior = runBlocking { app.preferences.data.first() }
        val store = ViewModelStore()
        try {
            runBlocking { app.preferences.edit { it[stringPreferencesKey("origin")] = "" } }
            val vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
            compose.setContent {
                BeaconTheme(dark = true) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MoreScreen(vm) } }
            }
            compose.waitUntil(5_000) { vm.ui.value.origin.isBlank() && !vm.ui.value.restoringSession }
            compose.runOnIdle { vm.tab("More"); vm.more(route) }
            compose.waitUntil(5_000) { vm.ui.value.morePage == route }
            check()
        } finally {
            compose.runOnUiThread { store.clear() }
            runBlocking { app.preferences.updateData { prior } }
        }
    }
    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    /** The pages are lazy lists: bring a tagged part into composition before asserting on it. */
    private fun reach(list: String, tag: String) = compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag).assertIsDisplayed() }

    @Test fun chatPageShowsSegmentedChoicesAndFourSwitchRows() = withPage("Settings") {
        compose.onNodeWithTag("settings-section:Sending").assertExists()
        for (option in listOf("While Beacon is replying:steer", "While Beacon is replying:queue", "Send shortcut:enter", "Send shortcut:ctrl_enter")) {
            compose.onNodeWithTag("choice-option:$option").assertIsDisplayed()
        }
        compose.onNodeWithTag("choice-option:Send shortcut:enter").assertIsSelected()
        compose.onNodeWithTag("choice-option:While Beacon is replying:steer").assertIsSelected()
        for (tag in listOf("chat-pref-format", "chat-pref-thinking", "chat-pref-follow", "chat-pref-animation")) reach("chat-preferences", tag).assert(role(Role.Switch))
        compose.onNodeWithText("Follow new replies").assertExists()
    }

    @Test fun appearancePageShowsThreeThemeSegmentsAndEverySwatch() = withPage("Appearance") {
        for (theme in listOf("system", "dark", "light")) compose.onNodeWithTag("choice-option:Theme:$theme").assertIsDisplayed()
        assertEquals(21, NativeSkins.size)
        for (skin in NativeSkins) reach("appearance-settings", "skin-option:$skin").assert(role(Role.RadioButton))
        compose.onAllNodesWithTag("skin-option:default").assertCountEquals(1)
    }

    @Test fun connectionPageLeadsWithAStatusHeroAndEndsWithSignOut() = withPage("Connection") {
        compose.onNodeWithTag("settings-hero").assertIsDisplayed()
        // A blank origin is not connected: the hero says so and offers the way back.
        compose.onNodeWithTag("settings-hero-title").assertTextContains("Can't reach Hermes")
        compose.onNodeWithTag("connection-reconnect").assertExists()
        reach("settings-connection-page", "connection-server-row")
        compose.onAllNodes(hasTestTag("connection-address")).assertCountEquals(0)
        compose.onNodeWithTag("connection-server-row").performClick()
        reach("settings-connection-page", "connection-address")
        compose.onNodeWithTag("connection-change-server").assertExists()
        reach("settings-connection-page", "connection-sign-out").assert(role(Role.Button))
    }
}
