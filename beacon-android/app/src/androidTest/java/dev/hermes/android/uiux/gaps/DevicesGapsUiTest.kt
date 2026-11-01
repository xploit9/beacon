package dev.hermes.android.uiux.gaps

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.data.PairedDevice
import dev.hermes.android.ui.DevicesList
import dev.hermes.android.ui.DevicesSettingsSection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The Devices section of Connection on a device: this phone, the others with Remove, Show all, the add-this-phone offer, a failure line, and the missing-plugin and signed-out states. */
class DevicesGapsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun phone(id: String, name: String = "Phone $id") = PairedDevice(id, name, "")
    private fun others(count: Int) = (1..count).map { phone("o$it") }

    @Test fun thisPhoneLeadsWithALinkedPillAndHasNoRemoveWhileOthersDo() {
        val removed = mutableListOf<PairedDevice>()
        compose.showPage { DevicesList(listOf(phone("a"), phone("b"), phone("c")), "a") { removed += it } }
        compose.onNodeWithTag("settings-device-a").assertIsDisplayed().assertTextContains("This phone").assertTextContains("Linked")
        compose.onNodeWithTag("settings-device-remove-a").assertDoesNotExist()
        compose.onNodeWithTag("settings-device-remove-b").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(phone("b")), removed) }
        compose.onAllNodesWithText("Last seen unavailable", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("Other phones").assertIsDisplayed()
    }

    @Test fun withNoOtherPhonesTheSectionSaysSoAndHasNoOtherPhonesList() {
        compose.showPage { DevicesList(listOf(phone("a")), "a") {} }
        compose.onNodeWithText("No other phones").assertIsDisplayed()
        compose.onNodeWithText("Other phones").assertDoesNotExist()
    }

    @Test fun fourOtherPhonesShowInFullButMoreFoldToThreeWithShowAll() {
        compose.showPage { DevicesList(listOf(phone("a")) + others(4), "a") {} }
        for (n in 1..4) compose.onNodeWithTag("settings-device-o$n").assertExists()
        compose.onNodeWithTag("devices-show-all").assertDoesNotExist()
    }

    @Test fun moreThanFourOtherPhonesFoldAndShowAllOpensThemAll() {
        compose.showPage { DevicesList(listOf(phone("a")) + others(6), "a") {} }
        for (n in 1..3) compose.onNodeWithTag("settings-device-o$n").assertExists()
        compose.onNodeWithTag("settings-device-o4").assertDoesNotExist()
        compose.onNodeWithTag("devices-show-all").assertTextContains("Show all (6)").performClick()
        for (n in 1..6) compose.onNodeWithTag("settings-device-o$n").assertExists()
        compose.onNodeWithTag("devices-show-all").assertDoesNotExist()
    }

    @Test fun whenThisPhoneIsNotListedTheSectionOffersToAddIt() {
        var added = 0
        compose.showPage { DevicesList(listOf(phone("b")), "a", onAddThisPhone = { added++ }) {} }
        compose.onNodeWithTag("devices-link-this-phone").assertIsEnabled().assertTextContains("Not linked").performClick()
        compose.runOnIdle { assertEquals(1, added) }
    }

    @Test fun addingThisPhoneWaitsWhileAChangeRuns() {
        compose.showPage { DevicesList(listOf(phone("b")), "a", enabled = false, onAddThisPhone = {}) {} }
        compose.onNodeWithTag("devices-link-this-phone").assertIsNotEnabled()
        compose.onNodeWithTag("settings-device-remove-b").assertIsNotEnabled()
    }

    @Test fun aFailureShowsAboveTheRowsWithoutRetryAndTheRowsStay() {
        compose.showPage { DevicesList(listOf(phone("a"), phone("b")), "a", error = "Couldn't remove this device. Try again.") {} }
        compose.onNodeWithText("Couldn't remove this device. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithTag("settings-device-a").assertIsDisplayed()
        compose.onNodeWithTag("settings-device-b").assertIsDisplayed()
    }

    @Test fun aMissingPluginReplacesTheListWithItsOwnState() = withClientlessVm(compose) { vm ->
        compose.showPage { DevicesSettingsSection(vm, pluginAbsent = true) }
        compose.onNodeWithTag("settings-section:Devices").assertIsDisplayed()
        compose.onNodeWithText("Reconnect to see devices").assertDoesNotExist()
        compose.onNodeWithTag("settings-devices-refresh").assertDoesNotExist()
    }

    @Test fun withoutAConnectionTheSectionOffersReconnectAndNoList() = withClientlessVm(compose) { vm ->
        compose.showPage { DevicesSettingsSection(vm, pluginAbsent = false) }
        compose.onNodeWithText("Reconnect to see devices").assertIsDisplayed()
        compose.onNodeWithText("Reconnect").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Other phones").assertDoesNotExist()
        compose.onNodeWithTag("devices-link-this-phone").assertDoesNotExist()
    }
}
