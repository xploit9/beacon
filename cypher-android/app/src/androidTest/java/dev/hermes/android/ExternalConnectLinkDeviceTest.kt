package dev.hermes.android

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ExternalConnectLinkDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<CypherActivity>()

    @Suppress("UNCHECKED_CAST")
    private fun ui(vm: CypherViewModel) = CypherViewModel::class.java.getDeclaredField("_ui")
        .apply { isAccessible = true }.get(vm) as MutableStateFlow<AppState>

    private fun activityViewModel(): CypherViewModel {
        lateinit var vm: CypherViewModel
        compose.waitUntil(5_000) {
            CypherActivity::class.java.getDeclaredField("vm").apply { isAccessible = true }
                .get(compose.activity) != null
        }
        compose.runOnUiThread {
            vm = CypherActivity::class.java.getDeclaredField("vm").apply { isAccessible = true }
                .get(compose.activity) as CypherViewModel
        }
        return vm
    }

    private fun link(origin: String) = Intent(Intent.ACTION_VIEW, Uri.parse("cypher://connect?url=${Uri.encode(origin)}"))

    @Test fun setUpLinkRequiresConfirmationBeforeEnteringOrPersistingSetup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<CypherApplication>()
        val setupOrigin = stringPreferencesKey("setup_origin")
        val setupStep = stringPreferencesKey("setup_step")
        val setupComplete = booleanPreferencesKey("setup_complete")
        val setupActive = booleanPreferencesKey("setup_active")
        val before = app.preferences.data.first()
        val incomingOrigin = "https://incoming.example:9443"
        val vm = activityViewModel()
        try {
            compose.waitUntil(5_000) { !vm.ui.value.restoringSession }
            app.preferences.edit {
                it[setupComplete] = true
                it[setupActive] = false
                it.remove(setupOrigin)
            }
            compose.runOnUiThread {
                CypherViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.set(vm, null)
                ui(vm).value = AppState(origin = "https://active.example", authenticated = true, connected = true, setupComplete = true, restoringSession = false)
            }

            compose.runOnUiThread { compose.activity.onNewIntent(link(incomingOrigin)) }
            compose.onNodeWithText("Connect to $incomingOrigin?").assertIsDisplayed()
            assertFalse(vm.ui.value.setupWizard)
            assertEquals("", vm.ui.value.setupOrigin)
            assertNull(app.preferences.data.first()[setupOrigin])
            assertFalse(app.preferences.data.first()[setupActive] ?: false)
            compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
            assertFalse(vm.ui.value.setupWizard)
            assertEquals("", vm.ui.value.setupOrigin)
            assertNull(app.preferences.data.first()[setupOrigin])
            assertFalse(app.preferences.data.first()[setupActive] ?: false)

            compose.runOnUiThread { compose.activity.onNewIntent(link(incomingOrigin)) }
            compose.onNodeWithText("Connect to $incomingOrigin?").assertIsDisplayed()
            compose.onNodeWithText("Connect").performClick()
            compose.waitUntil(5_000) { vm.ui.value.setupWizard && vm.ui.value.setupOrigin == incomingOrigin }
            compose.onNodeWithText("Address from external link: $incomingOrigin").assertIsDisplayed()
            compose.waitUntil(5_000) { runBlocking { app.preferences.data.first()[setupOrigin] == incomingOrigin } }
        } finally {
            compose.runOnUiThread { compose.activity.finish() }
            compose.waitUntil(5_000) { compose.activity.isDestroyed }
            app.preferences.edit { values ->
                before[setupOrigin]?.let { values[setupOrigin] = it } ?: values.remove(setupOrigin)
                before[setupStep]?.let { values[setupStep] = it } ?: values.remove(setupStep)
                before[setupComplete]?.let { values[setupComplete] = it } ?: values.remove(setupComplete)
                before[setupActive]?.let { values[setupActive] = it } ?: values.remove(setupActive)
            }
        }
    }

    @Test fun firstRunLinkPrefillsSetupWithAnExternalLinkLabelWithoutConfirmation() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<CypherApplication>()
        val setupOrigin = stringPreferencesKey("setup_origin")
        val setupStep = stringPreferencesKey("setup_step")
        val setupComplete = booleanPreferencesKey("setup_complete")
        val setupActive = booleanPreferencesKey("setup_active")
        val before = app.preferences.data.first()
        val incomingOrigin = "https://first-run.example"
        val vm = activityViewModel()
        try {
            compose.waitUntil(5_000) { !vm.ui.value.restoringSession }
            app.preferences.edit {
                it[setupComplete] = false
                it[setupActive] = false
                it.remove(setupOrigin)
            }
            compose.runOnUiThread {
                CypherViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.set(vm, null)
                ui(vm).value = AppState(origin = "", restoringSession = false)
                compose.activity.onNewIntent(link(incomingOrigin))
            }

            compose.waitUntil(5_000) { vm.ui.value.setupWizard && vm.ui.value.setupOrigin == incomingOrigin }
            compose.onNodeWithText("Connect to $incomingOrigin?").assertDoesNotExist()
            compose.onNodeWithText("Address from external link: $incomingOrigin").assertIsDisplayed()
            compose.waitUntil(5_000) { runBlocking { app.preferences.data.first()[setupOrigin] == incomingOrigin } }
        } finally {
            compose.runOnUiThread { compose.activity.finish() }
            compose.waitUntil(5_000) { compose.activity.isDestroyed }
            app.preferences.edit { values ->
                before[setupOrigin]?.let { values[setupOrigin] = it } ?: values.remove(setupOrigin)
                before[setupStep]?.let { values[setupStep] = it } ?: values.remove(setupStep)
                before[setupComplete]?.let { values[setupComplete] = it } ?: values.remove(setupComplete)
                before[setupActive]?.let { values[setupActive] = it } ?: values.remove(setupActive)
            }
        }
    }
}
