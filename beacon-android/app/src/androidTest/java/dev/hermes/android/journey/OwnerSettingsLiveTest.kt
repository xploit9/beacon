package dev.hermes.android.journey

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.BeaconActivity
import dev.hermes.android.ui.settingsCategories
import dev.hermes.android.ui.settingsHubRoutes
import dev.hermes.android.ui.settingsRoute
import dev.hermes.android.ui.nativeLanguageFor
import dev.hermes.android.ui.translateUi
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Navigation exercises real Compose controls; it never changes a saved setting. */
class OwnerSettingsLiveTest {
    private val compose = createAndroidComposeRule<BeaconActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ownerRuntimeOptIn).around(compose)

    @Test(timeout = 300_000) fun settingsSearchRoutesAndBackUseTheConnectedApp() = runBlocking {
        val h = OwnerJourneyHarness(compose, "settings")
        h.checked {
            compose.runOnIdle { vm.tab("Chat") }
            compose.onNodeWithTag("navigation-toggle").performClick()
            compose.onNodeWithTag("navigation-settings").performClick()
            await { vm.ui.value.tab == "More" && vm.ui.value.morePage.isBlank() }
            compose.onNodeWithTag("settings-overview").performScrollToIndex(0)
            compose.onNodeWithTag("settings-search").performTextInput("connection")
            compose.onNodeWithTag("settings-row:Connection").performClick()
            await { vm.ui.value.morePage == "Connection" }
            compose.onNodeWithTag("settings-connection-page").assertExists()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            await { vm.ui.value.morePage.isBlank() }
            compose.onNodeWithTag("settings-overview").performScrollToIndex(0)
            compose.onNodeWithTag("settings-search").performTextClearance()
            mark("S-01", "search_and_back", "passed")

            val compatibility = mapOf("Connection" to "settings-connection", "Chat" to "settings-row:Settings",
                "Voice & calls" to "settings-row:Beacon", "Notifications & Keep connected" to "settings-row:Notifications",
                "Profiles & model" to "settings-row:Profiles")
            settingsHubRoutes.forEachIndexed { index, route ->
                current()
                compose.onNodeWithTag(compatibility[route] ?: "settings-row:$route").performScrollTo().performClick()
                await { vm.ui.value.morePage == settingsRoute(route) }
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                await { vm.ui.value.tab == "More" && vm.ui.value.morePage.isBlank() }
                mark("S-01", "hub_route_${index + 1}", "passed")
            }
            settingsCategories.entries.forEachIndexed { categoryIndex, (category, children) ->
                compose.onNodeWithTag(compatibility[category] ?: "settings-row:$category").performScrollTo().performClick()
                await { vm.ui.value.morePage == "category:$category" }
                compose.onNodeWithTag("settings-overview").performScrollToIndex(1)
                children.forEachIndexed { childIndex, child ->
                    current()
                    compose.onNodeWithTag("settings-row:$child").performScrollTo().performClick()
                    await { vm.ui.value.morePage == settingsRoute(child) }
                    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                    await { vm.ui.value.morePage == "category:$category" }
                    mark("S-01", "nested_${categoryIndex + 1}_${childIndex + 1}", "passed")
                }
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                await { vm.ui.value.morePage.isBlank() }
            }
            current()
        }
    }

    @Test(timeout = 120_000) fun connectionConfirmationsCancelWithoutChangingTheVerifiedSession() = runBlocking {
        val h = OwnerJourneyHarness(compose, "connection")
        h.checked {
            qa()
            check(vm.ui.value.setupComplete)
            try {
                compose.runOnIdle { vm.tab("Chat") }
                compose.onNodeWithTag("navigation-toggle").performClick()
                compose.onNodeWithTag("navigation-settings").performClick()
                compose.onNodeWithTag("settings-connection").performScrollTo().performClick()
                await { vm.ui.value.morePage == "Connection" }
                compose.onNodeWithTag("settings-connection-page").performScrollToIndex(4)
                compose.onNodeWithTag("connection-sign-out").performClick()
                await { vm.form.value != null }
                val language = nativeLanguageFor(compose.activity.resources.configuration.locales[0])
                val dictionary = if (language == "en") JSONObject() else app.readTranslations().optJSONObject(language) ?: JSONObject()
                compose.onNodeWithText(translateUi(dictionary, "Cancel")).performClick()
                await { vm.form.value == null }
                current()
                mark("O-02", "signout_cancel", "passed")

                compose.onNodeWithTag("settings-connection-page").performScrollToIndex(1)
                compose.onNodeWithTag("connection-server-row").performClick()
                compose.onNodeWithTag("connection-change-server").performClick()
                await { vm.ui.value.setupWizard }
                compose.onNodeWithTag("setup-address").performTextReplacement("://")
                compose.onNodeWithTag("setup-address-help").performClick()
                compose.onNodeWithTag("setup-connect").performClick()
                await { vm.ui.value.setupStep == "connect" && vm.ui.value.error.isNotBlank() }
                compose.onNodeWithTag("setup-close").performClick()
                await { !vm.ui.value.setupWizard }
                current()
                mark("O-01", "invalid_address_cancel", "passed")
            } finally {
                if (vm.ui.value.setupWizard && vm.ui.value.authenticated) compose.runOnIdle { vm.cancelSetupWizard() }
            }
        }
    }
}
