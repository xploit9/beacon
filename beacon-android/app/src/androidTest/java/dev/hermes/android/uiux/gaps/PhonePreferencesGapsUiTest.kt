package dev.hermes.android.uiux.gaps

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.ui.NativePhonePreferences
import org.junit.Rule
import org.junit.Test

/**
 * The Skills and Memory shells while no profile is bound (no connection): the header, a placeholder page instead of an empty list, and a
 * Refresh icon that waits. A tap on Refresh needs a live profile, which a device test without a server cannot give it.
 */
class PhonePreferencesGapsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun skillsShowsPlaceholdersAndAnOffRefreshUntilAProfileIsBound() = withClientlessVm(compose) { vm ->
        compose.showPage { NativePhonePreferences(vm, "Skills") }
        compose.onNodeWithText("Skills").assertIsDisplayed()
        compose.onNodeWithTag("settings-binding").assertIsDisplayed()
        compose.onNodeWithTag("settings-binding-section").assertIsDisplayed()
        compose.onNodeWithTag("settings-skeleton").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh skills").assertIsNotEnabled()
        compose.onNodeWithTag("skills-page").assertDoesNotExist()
        compose.onNodeWithText("No skills yet").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test fun memoryShowsPlaceholdersAndAnOffRefreshUntilAProfileIsBound() = withClientlessVm(compose) { vm ->
        compose.showPage { NativePhonePreferences(vm, "Memory") }
        compose.onNodeWithText("Memory").assertIsDisplayed()
        compose.onNodeWithTag("settings-binding").assertIsDisplayed()
        compose.onNodeWithTag("settings-skeleton").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh memory").assertIsNotEnabled()
        compose.onNodeWithTag("memory-page").assertDoesNotExist()
        compose.onNodeWithText("No notes yet").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }
}
