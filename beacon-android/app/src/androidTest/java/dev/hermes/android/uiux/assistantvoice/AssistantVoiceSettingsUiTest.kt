package dev.hermes.android.uiux.assistantvoice

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.data.PairedDevice
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Lane assistant-voice (uiux-20261031): Devices rows, the key page and Voice & calls, without a server. Keys used here are fixtures and are restored. */
class AssistantVoiceSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        NativeLocalization("en") { BeaconTheme(dark = true) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) { content() }
        } }
    }

    @Composable private fun page(page: SetupPage) = Column(verticalArrangement = Arrangement.spacedBy(24.dp)) { page.content(this); page.actions(this) }

    @Test fun otherPhonesCollapseToThreeAndEachHasALabelledRemoveTarget() {
        val removed = mutableListOf<String>()
        val others = (1..6).map { PairedDevice("other-$it", "Phone $it", "2026-10-2${it}T09:00:00Z") }
        show { DevicesList(listOf(PairedDevice("this-1", "Pixel 9", "2026-10-30T10:00:00Z")) + others, thisDeviceId = "this-1") { removed += it.id } }
        compose.onNodeWithText("This phone").assertIsDisplayed()
        compose.onNodeWithText("Linked").assertIsDisplayed()
        compose.onNodeWithText("Other phones").assertIsDisplayed()
        // Six other phones: three show, then "Show all (6)".
        compose.onNodeWithTag("settings-device-other-3").assertExists()
        compose.onAllNodesWithTag("settings-device-other-4").assertCountEquals(0)
        compose.onAllNodesWithText("Seen ", substring = true).assertCountEquals(3)
        compose.onNodeWithTag("settings-device-remove-other-1").assertContentDescriptionEquals("Remove Phone 1").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("devices-show-all").assertTextEquals("Show all (6)").performScrollTo().performClick()
        compose.onNodeWithTag("settings-device-other-6").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("devices-show-all").assertCountEquals(0)
        compose.onNodeWithTag("settings-device-remove-other-6").performClick()
        assertEquals(listOf("other-6"), removed)
        compose.onAllNodesWithTag("settings-device-remove-this-1").assertCountEquals(0)
    }

    @Test fun aLoneLinkedPhoneSaysThereAreNoOthers() {
        show { DevicesList(listOf(PairedDevice("this-1", "Pixel 9", "2026-10-30T10:00:00Z")), thisDeviceId = "this-1") {} }
        compose.onNodeWithText("No other phones").assertIsDisplayed()
        compose.onAllNodesWithText("Other phones").assertCountEquals(0)
    }

    @Test fun anUnlinkedPhoneIsOfferedToBeAdded() {
        var added = false
        show { DevicesList(emptyList(), thisDeviceId = null, onAddThisPhone = { added = true }) {} }
        compose.onNodeWithTag("devices-link-this-phone").assertTextContains("Add this phone").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(true, added)
    }

    @Test fun theKeyPageShowsTheSavedKeyAPrivacyLineAndRemovesOnlyAfterConfirming() {
        val store = VoiceKeyStore(app)
        val provider = store.settings().provider
        val previousKey = store.key(provider)
        val short = voiceProviderInfo(provider).short
        var removed = false
        try {
            store.saveKey(provider, "fixture-key-AB12")
            show { page(voiceKeyPage(provider, onKeySaved = {}, onVerified = {}, onContinueAnyway = {}, onBack = {}, removable = true, onRemoved = { removed = true })) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("••••AB12").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Saved").assertIsDisplayed()
            compose.onNodeWithText("Your provider hears you and speaks the replies").assertIsDisplayed()
            compose.onNodeWithTag("voice-key-get").assertHasClickAction()
            compose.onNodeWithTag("voice-key-test").assertIsEnabled()
            compose.onNodeWithTag("voice-key-remove").performScrollTo().performClick()
            compose.onNodeWithText("Remove the $short key from this phone?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals("fixture-key-AB12", store.key(provider))
            compose.onNodeWithTag("voice-key-remove").performScrollTo().performClick()
            compose.onNodeWithTag("voice-key-remove-confirm").performClick()
            compose.waitUntil(5_000) { removed }
            assertNull(store.key(provider))
        } finally {
            previousKey?.let { store.saveKey(provider, it) } ?: store.removeKey(provider)
        }
    }

    @Test fun voiceAndCallsLeadsWithTheProviderAndShowsChoicesAsCards() {
        val store = VoiceKeyStore(app)
        val previousSelection = store.settings()
        val previousKey = store.key("gemini")
        val previousLoader = VoiceCatalogCache.loader
        try {
            // Offline: the built-in lists stand in for the provider catalog.
            VoiceCatalogCache.loader = { _, _ -> VoiceCatalog() }
            store.saveKey("gemini", "fixture-gemini-key")
            store.setSelection(store.settings("gemini"))
            show { page(voiceChoicesPage(settingsMode = true)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Key saved").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("voice-hero-title").assertTextEquals("Gemini Live")
            compose.onNodeWithText("Key on this phone").assertIsDisplayed()
            compose.onNodeWithTag("voice-provider-change").assertHasClickAction()
            compose.onAllNodesWithTag("voice-add-key").assertCountEquals(0)
            // Eight built-in voices: six cards and "More voices (2)", unless the saved voice sits further down.
            compose.onNodeWithTag("voice-more-voices").performScrollTo().assertTextContains("More voices", substring = true)
            compose.onNodeWithTag("voice-option-personality-balanced").performScrollTo().assertTextContains("Everyday")
            compose.onNodeWithTag("voice-option-personality-concise").assertTextContains("Short answers")
            compose.onNodeWithTag("voice-option-personality-warm").assertTextContains("Friendly")
            // The chosen Live model speaks the replies itself: no separate speech model is shown.
            compose.onAllNodesWithTag("voice-spoken-replies").assertCountEquals(0)
            compose.onAllNodesWithText("The selected Live model listens", substring = true).assertCountEquals(0)
            compose.onAllNodesWithText("Provider for one-tap calls", substring = true).assertCountEquals(0)
        } finally {
            VoiceCatalogCache.loader = previousLoader
            VoiceCatalogCache.clear("gemini")
            previousKey?.let { store.saveKey("gemini", it) } ?: store.removeKey("gemini")
            store.setSelection(previousSelection)
        }
    }
}
