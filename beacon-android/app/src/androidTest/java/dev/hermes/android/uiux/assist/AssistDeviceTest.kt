package dev.hermes.android.uiux.assist

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.assist.AssistEntryActivity
import dev.hermes.android.assist.PhoneAssistantSection
import dev.hermes.android.assist.PhoneAssistantState
import dev.hermes.android.assist.phoneAssistantState
import dev.hermes.android.assist.rememberPhoneAssistantState
import dev.hermes.android.ui.NativeLocalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Lane assist (uiux-20261031). Nothing here starts a call or changes the phone's default assistant. */
class AssistDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    @Test fun theAssistActionResolvesToBeaconsEntryActivity() {
        val assist = Intent(Intent.ACTION_ASSIST).setPackage(app.packageName)
        val found = app.packageManager.queryIntentActivities(assist, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(listOf(AssistEntryActivity::class.java.name), found.map { it.activityInfo.name })
        assertTrue("The entry must be exported to be listed as an assistant", found.single().activityInfo.exported)
        assertNotNull(app.packageManager.resolveActivity(assist, PackageManager.MATCH_DEFAULT_ONLY))
    }

    @Test fun thePhoneRoleStateMatchesAndroidsAnswer() {
        val roles = app.getSystemService(RoleManager::class.java)
        val expected = when {
            !roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) -> PhoneAssistantState.Unavailable
            roles.isRoleHeld(RoleManager.ROLE_ASSISTANT) -> PhoneAssistantState.Active
            else -> PhoneAssistantState.NotSet
        }
        assertEquals(expected, phoneAssistantState(app))
    }

    @Test fun theSettingsRowShowsTheRoleStateAndOpensOnTap() {
        var opened = 0
        compose.setContent {
            NativeLocalization("en") { BeaconTheme(dark = true) {
                val state = rememberPhoneAssistantState()
                // Phones without an assistant picker (Android Go, work profiles) show no row at all.
                if (state != PhoneAssistantState.Unavailable) PhoneAssistantSection(state) { opened++ }
            } }
        }
        val state = phoneAssistantState(app)
        if (state == PhoneAssistantState.Unavailable) {
            compose.onNodeWithTag("phone-assistant-section").assertDoesNotExist()
            return
        }
        compose.onNodeWithTag("phone-assistant").assertIsDisplayed().assertTextContains("Phone assistant")
        compose.onNodeWithText(if (state == PhoneAssistantState.Active) "Active" else "Choose Beacon to open it over any app").assertIsDisplayed()
        compose.onNodeWithTag("phone-assistant").performClick()
        assertEquals(1, opened)
    }
}
