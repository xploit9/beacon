package dev.hermes.android.uiux.gaps

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.ui.MoreScreen
import dev.hermes.android.ui.SettingsOverview
import dev.hermes.android.ui.SettingsSnapshot
import dev.hermes.android.ui.localizedSettingsSummaries
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * The Settings hub and the route gates on a device: search with its clear actions, the Tools and services list, the child rows that read the
 * saved Tools and MCP lists, the wide layout's empty detail, the Reconnect gate every Hermes page shares, and the restoring-profile state.
 */
class HubAndRoutesGapsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // The overview on plain state

    @Test fun theClearIconEmptiesTheSearchAndBringsTheHubBack() {
        compose.showPage { SettingsOverview(emptyMap(), {}) }
        compose.onNodeWithTag("settings-search").performTextReplacement("conn")
        compose.onNodeWithTag("settings-results").assertIsDisplayed()
        compose.onNodeWithTag("settings-group:Assistant").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithTag("settings-results").assertDoesNotExist()
        compose.onNodeWithTag("settings-group:Assistant").assertExists()
    }

    @Test fun aSearchWithNoMatchOffersClearSearchWhichBringsTheHubBack() {
        compose.showPage { SettingsOverview(emptyMap(), {}) }
        compose.onNodeWithTag("settings-search").performTextReplacement("zzz-no-such-setting")
        compose.onNodeWithText("No matching settings").assertIsDisplayed()
        compose.onNodeWithText("Clear search").performClick()
        compose.onNodeWithText("No matching settings").assertDoesNotExist()
        compose.onNodeWithTag("settings-group:Assistant").assertExists()
    }

    @Test fun theToolsAndServicesListOpensEachCategory() {
        val opened = mutableListOf<String>()
        compose.showPage { SettingsOverview(emptyMap(), { opened += it }, category = "Advanced") }
        compose.onNodeWithTag("settings-group:Tools and services").assertIsDisplayed()
        compose.onNodeWithTag("settings-row:Profiles").performClick()
        compose.onNodeWithTag("settings-row:Tools & MCP").performClick()
        compose.onNodeWithTag("settings-row:Server").performClick()
        compose.runOnIdle { assertEquals(listOf("category:Profiles & model", "category:Tools & MCP", "category:Server"), opened) }
    }

    @Test fun theServerCategoryShowsTheHealthPillAndTheCheckpointSize() {
        val opened = mutableListOf<String>()
        compose.showPage {
            SettingsOverview(localizedSettingsSummaries(SettingsSnapshot(serverHealthy = false, checkpointSize = "12 MB")), { opened += it }, category = "Server")
        }
        compose.onNodeWithTag("settings-category:Server").assertIsDisplayed()
        compose.onNodeWithTag("settings-row:Health").assertTextContains("Needs attention")
        compose.onNodeWithTag("settings-row:Session checkpoints").assertTextContains("12 MB").performClick()
        compose.runOnIdle { assertEquals(listOf("Session checkpoints"), opened) }
    }

    // The child rows read the saved lists

    @Test fun theToolsAndMcpChildRowsCountWhatIsSavedAndFallBackWhenTheListFailed() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { vm.tab("More"); vm.more("category:Tools & MCP") }
        compose.waitUntil(5_000) { vm.ui.value.morePage == "category:Tools & MCP" }
        compose.showPage { MoreScreen(vm) }
        compose.onNodeWithTag("settings-row:Tools & commands").assertIsDisplayed().assertTextContains("Tool groups and commands")
        compose.runOnIdle {
            vm.storeData("native-tools", json("items" to JSONArray().put(json("enabled" to true)).put(json("enabled" to false)).put(json("enabled" to true))))
            vm.storeData("native-mcp", json("servers" to JSONArray().put(json("name" to "synthetic_server", "enabled" to true))))
        }
        compose.onNodeWithTag("settings-row:Tools & commands").assertTextContains("2 on")
        compose.onNodeWithTag("settings-row:MCP servers").assertTextContains("1 server")
        compose.runOnIdle { vm.storeData("native-tools", json("_error" to "Synthetic failure")) }
        compose.onNodeWithTag("settings-row:Tools & commands").assertTextContains("Tool groups and commands")
        compose.onNodeWithTag("settings-row:MCP servers").assertTextContains("1 server")
    }

    @Test fun theWideLayoutAsksForASettingUntilOneIsChosen() = withClientlessVm(compose) { vm ->
        // 900dp is wider than the 840dp the layout needs, whatever the phone is.
        compose.showPage { Box(Modifier.requiredWidth(900.dp)) { MoreScreen(vm) } }
        compose.onNodeWithTag("settings-wide-list").assertExists()
        compose.onNodeWithTag("settings-wide-detail").assertExists()
        compose.onNodeWithText("Choose a setting").assertExists()
        compose.onNodeWithText("Pick one from the list to see it here.").assertExists()
        compose.onNodeWithTag("settings-row:Appearance").assertExists()
        compose.runOnIdle { vm.tab("More"); vm.more("Appearance") }
        compose.waitUntil(5_000) { vm.ui.value.morePage == "Appearance" }
        compose.onNodeWithText("Choose a setting").assertDoesNotExist()
        compose.onNodeWithTag("appearance-settings").assertExists()
    }

    // The gate every Hermes page shares

    @Test fun everyHermesPageOffersReconnectWhenThePhoneHasNoConnection() = withClientlessVm(compose) { vm ->
        compose.showPage { MoreScreen(vm) }
        val routes = listOf("Session usage", "Commands", "Notifications", "Bots", "Skills", "Memory", "Profiles", "Providers & models", "Tools & commands", "MCP servers", "Health", "Logs", "Server updates", "Session checkpoints")
        for (route in routes) {
            compose.runOnIdle { vm.tab("More"); vm.more(route) }
            compose.waitUntil(5_000) { vm.ui.value.morePage == route }
            compose.onNodeWithTag("settings-hero-title").assertIsDisplayed().assertTextContains("Reconnect to Hermes")
            compose.onNodeWithTag("settings-reconnect").assertIsDisplayed().assertHasClickAction()
        }
    }

    @Test fun theGateKeepsItsBackArrowExceptOnTheTopLevelHermesPages() = withClientlessVm(compose) { vm ->
        compose.showPage { MoreScreen(vm) }
        for (route in listOf("Session usage", "Commands", "Notifications", "Health")) {
            compose.runOnIdle { vm.tab("More"); vm.more(route) }
            compose.waitUntil(5_000) { vm.ui.value.morePage == route }
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        }
        for (route in listOf("Skills", "Memory", "Bots")) {
            compose.runOnIdle { vm.tab("More"); vm.more(route) }
            compose.waitUntil(5_000) { vm.ui.value.morePage == route }
            compose.onNodeWithTag("settings-reconnect").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        }
    }

    @Test fun aMissingBeaconPluginReplacesOnlyThePluginPages() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { vm.appFlow.update { it.copy(bootstrap = json("optional_features" to json("plugin" to "absent"))) } }
        compose.showPage { MoreScreen(vm) }
        for (route in listOf("Notifications", "Bots")) {
            compose.runOnIdle { vm.tab("More"); vm.more(route) }
            compose.waitUntil(5_000) { vm.ui.value.morePage == route }
            compose.onNodeWithTag("settings-plugin-missing").assertIsDisplayed()
            compose.onNodeWithTag("settings-reconnect").assertDoesNotExist()
        }
        compose.runOnIdle { vm.tab("More"); vm.more("Skills") }
        compose.waitUntil(5_000) { vm.ui.value.morePage == "Skills" }
        compose.onNodeWithTag("settings-plugin-missing").assertDoesNotExist()
        compose.onNodeWithTag("settings-reconnect").assertIsDisplayed()
    }

    // The chat settings page with a failed record

    @Test fun aFailedChatRecordShowsItsErrorRowAndTurnsTheDisplayChoicesOff() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { vm.tab("More"); vm.more("Settings") }
        compose.waitUntil(5_000) { vm.ui.value.morePage == "Settings" }
        compose.showPage { MoreScreen(vm) }
        compose.onNodeWithTag("chat-preferences-error").assertDoesNotExist()
        compose.runOnIdle { vm.storeData("profile-settings", json("_error" to "Synthetic failure")) }
        compose.onNodeWithTag("chat-preferences-error").assertIsDisplayed()
        compose.onNodeWithText("Couldn't load chat settings").assertIsDisplayed()
        for (tag in listOf("chat-pref-format", "chat-pref-thinking", "chat-pref-follow", "chat-pref-animation")) {
            compose.onNodeWithTag("chat-preferences").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        }
        // Steer / Queue is kept on the phone, so a failed record on the server does not turn it off.
        compose.onNodeWithTag("choice-option:While Beacon is replying:steer").assertIsEnabled()
        compose.onNodeWithTag("choice-option:While Beacon is replying:queue").assertIsEnabled()
        compose.runOnIdle { vm.storeData("profile-settings", json("send_key" to "enter")) }
        compose.onNodeWithTag("chat-preferences-error").assertDoesNotExist()
        compose.onNodeWithTag("chat-preferences").performScrollToNode(hasTestTag("chat-pref-follow"))
        compose.onNodeWithTag("chat-pref-follow").assertIsEnabled()
    }

    // The restoring-profile state of Profiles, Skills and Memory

    @Test fun whileTheProfileIsRestoredThePageShowsPlaceholdersThenItsErrorWithRetry() = withClientlessVm(compose) { vm ->
        // A client that is never used: nothing connects, and the restore itself waits for a live connection.
        vm.useClient(BeaconClient(AuthContext("https://hub.example.com", emptyList()), null, false))
        compose.runOnIdle { vm.stateFlowNamed<Boolean>("_settingsProfileRestoring").value = true; vm.tab("More"); vm.more("Memory") }
        compose.waitUntil(5_000) { vm.ui.value.morePage == "Memory" }
        compose.showPage { MoreScreen(vm) }
        compose.onNodeWithTag("settings-restoring").assertIsDisplayed()
        compose.onNodeWithTag("settings-skeleton").assertIsDisplayed()
        compose.onNodeWithTag("settings-restore-retry").assertDoesNotExist()
        compose.runOnIdle { vm.stateFlowNamed<String>("_settingsProfileRestoreError").value = "Synthetic restore failure" }
        compose.onNodeWithText("Synthetic restore failure").assertIsDisplayed()
        compose.onNodeWithTag("settings-skeleton").assertDoesNotExist()
        compose.onNodeWithTag("settings-restore-retry").assertIsDisplayed().assertHasClickAction()
        // The page stays in this state: leaving it would start the page's own reads against the unused client.
    }
}
