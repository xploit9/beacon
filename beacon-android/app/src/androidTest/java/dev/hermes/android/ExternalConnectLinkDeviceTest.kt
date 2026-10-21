package dev.hermes.android

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ExternalConnectLinkDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<BeaconActivity>()

    @Suppress("UNCHECKED_CAST")
    private fun ui(vm: BeaconViewModel) = BeaconViewModel::class.java.getDeclaredField("_ui")
        .apply { isAccessible = true }.get(vm) as MutableStateFlow<AppState>

    private fun activityViewModel(): BeaconViewModel {
        lateinit var vm: BeaconViewModel
        compose.waitUntil(5_000) {
            BeaconActivity::class.java.getDeclaredField("vm").apply { isAccessible = true }
                .get(compose.activity) != null
        }
        compose.runOnUiThread {
            vm = BeaconActivity::class.java.getDeclaredField("vm").apply { isAccessible = true }
                .get(compose.activity) as BeaconViewModel
        }
        return vm
    }

    private fun link(origin: String) = Intent(Intent.ACTION_VIEW, Uri.parse("beacon://connect?url=${Uri.encode(origin)}"))

    // Deliver the link like a browser does (singleTop onNewIntent), then restore the launch intent:
    // ActivityScenario ignores lifecycle events for an activity whose intent no longer matches its launch intent.
    private fun openLink(activity: BeaconActivity, origin: String) {
        val launch = compose.runOnUiThread { activity.intent }
        compose.runOnUiThread { activity.startActivity(link(origin).setPackage(activity.packageName)) }
        compose.waitUntil(5_000) { compose.runOnUiThread { activity.intent !== launch } }
        compose.runOnUiThread { activity.intent = launch }
    }

    @Test fun setUpLinkRequiresConfirmationBeforeEnteringOrPersistingSetup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val setupOrigin = stringPreferencesKey("setup_origin")
        val setupStep = stringPreferencesKey("setup_step")
        val setupComplete = booleanPreferencesKey("setup_complete")
        val setupActive = booleanPreferencesKey("setup_active")
        val before = app.preferences.data.first()
        val incomingOrigin = "https://incoming.example:9443"
        val vm = activityViewModel()
        val activity = compose.activity
        // The home screen's ambient motion follows "Remove animations"; still frames let Compose go idle (as in NativeExperienceUiTest).
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shellRun(command: String): String = shell.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        val priorAnimatorScale = shellRun("settings get global animator_duration_scale")
        shellRun("settings put global animator_duration_scale 0")
        try {
            compose.waitUntil(5_000) { !vm.ui.value.restoringSession }
            app.preferences.edit {
                it[setupComplete] = true
                it[setupActive] = false
                it.remove(setupOrigin)
            }
            compose.runOnUiThread {
                BeaconViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.set(vm, null)
                ui(vm).value = AppState(origin = "https://active.example", authenticated = true, connected = true, setupComplete = true, restoringSession = false)
            }

            openLink(activity, incomingOrigin)
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

            openLink(activity, incomingOrigin)
            compose.onNodeWithText("Connect to $incomingOrigin?").assertIsDisplayed()
            compose.onNodeWithText("Connect").performClick()
            compose.waitUntil(5_000) { vm.ui.value.setupWizard && vm.ui.value.setupOrigin == incomingOrigin }
            compose.onNodeWithText("Address from external link: $incomingOrigin").assertIsDisplayed()
            compose.waitUntil(5_000) { runBlocking { app.preferences.data.first()[setupOrigin] == incomingOrigin } }
        } finally {
            compose.runOnUiThread { activity.finish() }
            compose.waitUntil(5_000) { compose.runOnUiThread { activity.isDestroyed } }
            app.preferences.edit { values ->
                before[setupOrigin]?.let { values[setupOrigin] = it } ?: values.remove(setupOrigin)
                before[setupStep]?.let { values[setupStep] = it } ?: values.remove(setupStep)
                before[setupComplete]?.let { values[setupComplete] = it } ?: values.remove(setupComplete)
                before[setupActive]?.let { values[setupActive] = it } ?: values.remove(setupActive)
            }
            shellRun(if (priorAnimatorScale == "null" || priorAnimatorScale.isBlank()) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $priorAnimatorScale")
        }
    }

    @Test fun signInRecoveryLinkRequiresConfirmationBeforeReplacingKnownOrigin() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val origin = stringPreferencesKey("origin")
        val setupOrigin = stringPreferencesKey("setup_origin")
        val setupStep = stringPreferencesKey("setup_step")
        val setupComplete = booleanPreferencesKey("setup_complete")
        val setupActive = booleanPreferencesKey("setup_active")
        val before = app.preferences.data.first()
        val storedOrigin = "https://expired.example"
        val incomingOrigin = "https://replacement.example:9443"
        val vm = activityViewModel()
        val activity = compose.activity
        try {
            compose.waitUntil(5_000) { !vm.ui.value.restoringSession }
            app.preferences.edit {
                it[origin] = storedOrigin
                it.remove(setupOrigin)
                it[setupStep] = "signin"
                it[setupComplete] = false
                it[setupActive] = true
            }
            compose.runOnUiThread {
                BeaconViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.set(vm, null)
                ui(vm).value = AppState(
                    origin = storedOrigin,
                    setupWizard = true,
                    setupComplete = false,
                    setupStep = "signin",
                    restoringSession = false,
                )
            }

            openLink(activity, incomingOrigin)
            compose.onNodeWithText("Connect to $incomingOrigin?").assertIsDisplayed()
            assertEquals(storedOrigin, vm.ui.value.origin)
            assertEquals("", vm.ui.value.setupOrigin)
            assertNull(app.preferences.data.first()[setupOrigin])
            compose.onNodeWithText("Cancel").performClick()
            assertEquals("", vm.ui.value.setupOrigin)
            assertNull(app.preferences.data.first()[setupOrigin])

            app.preferences.edit {
                it.remove(origin)
                it[setupOrigin] = storedOrigin
            }
            compose.runOnUiThread {
                ui(vm).value = vm.ui.value.copy(origin = "", setupOrigin = storedOrigin, setupStep = "connect")
            }
            openLink(activity, incomingOrigin)
            compose.onNodeWithText("Connect to $incomingOrigin?").assertIsDisplayed()
            assertEquals(storedOrigin, app.preferences.data.first()[setupOrigin])
            compose.onNodeWithText("Connect").performClick()
            compose.waitUntil(5_000) { vm.ui.value.setupWizard && vm.ui.value.setupOrigin == incomingOrigin }
            compose.waitUntil(5_000) { runBlocking { app.preferences.data.first()[setupOrigin] == incomingOrigin } }
        } finally {
            compose.runOnUiThread { activity.finish() }
            compose.waitUntil(5_000) { compose.runOnUiThread { activity.isDestroyed } }
            app.preferences.edit { values ->
                before[origin]?.let { values[origin] = it } ?: values.remove(origin)
                before[setupOrigin]?.let { values[setupOrigin] = it } ?: values.remove(setupOrigin)
                before[setupStep]?.let { values[setupStep] = it } ?: values.remove(setupStep)
                before[setupComplete]?.let { values[setupComplete] = it } ?: values.remove(setupComplete)
                before[setupActive]?.let { values[setupActive] = it } ?: values.remove(setupActive)
            }
        }
    }

    @Test fun firstRunLinkPrefillsSetupWithAnExternalLinkLabelWithoutConfirmation() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val origin = stringPreferencesKey("origin")
        val setupOrigin = stringPreferencesKey("setup_origin")
        val setupStep = stringPreferencesKey("setup_step")
        val setupComplete = booleanPreferencesKey("setup_complete")
        val setupActive = booleanPreferencesKey("setup_active")
        val before = app.preferences.data.first()
        val incomingOrigin = "https://first-run.example"
        val vm = activityViewModel()
        val activity = compose.activity
        try {
            compose.waitUntil(5_000) { !vm.ui.value.restoringSession }
            app.preferences.edit {
                it.remove(origin)
                it[setupComplete] = false
                it[setupActive] = false
                it.remove(setupOrigin)
                it.remove(setupStep)
            }
            compose.runOnUiThread {
                BeaconViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.set(vm, null)
                ui(vm).value = AppState(origin = "", restoringSession = false)
            }
            openLink(activity, incomingOrigin)

            compose.waitUntil(5_000) { vm.ui.value.setupWizard && vm.ui.value.setupOrigin == incomingOrigin }
            compose.onNodeWithText("Connect to $incomingOrigin?").assertDoesNotExist()
            compose.onNodeWithText("Address from external link: $incomingOrigin").assertIsDisplayed()
            assertNull(app.preferences.data.first()[setupOrigin])
            assertFalse(app.preferences.data.first()[setupActive] ?: false)
        } finally {
            compose.runOnUiThread { activity.finish() }
            compose.waitUntil(5_000) { compose.runOnUiThread { activity.isDestroyed } }
            app.preferences.edit { values ->
                before[origin]?.let { values[origin] = it } ?: values.remove(origin)
                before[setupOrigin]?.let { values[setupOrigin] = it } ?: values.remove(setupOrigin)
                before[setupStep]?.let { values[setupStep] = it } ?: values.remove(setupStep)
                before[setupComplete]?.let { values[setupComplete] = it } ?: values.remove(setupComplete)
                before[setupActive]?.let { values[setupActive] = it } ?: values.remove(setupActive)
            }
        }
    }
}
