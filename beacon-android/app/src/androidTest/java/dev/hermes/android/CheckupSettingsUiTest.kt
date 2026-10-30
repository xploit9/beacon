package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic requests only; the coordinator runs this alongside the integrated native UI suite. */
class CheckupSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST") private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
    private fun field(vm: BeaconViewModel, name: String, value: Any?) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)

    @Test fun assistantUsesActiveProfileAndNeverWritesUntilConfirmed() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        check(!app.calls.state.value.active && app.calls.service == null)
        val prior = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture()
        fixture.checkupCapabilities = json("timezone" to "Asia/Dubai", "checkups_ready" to true)
        fixture.checkupPostEnabled = true
        fixture.checkupCapabilitiesFailure = true
        fixture.jobs.getValue("task-a").put("paused", true).put("enabled", false)
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        try {
            runBlocking { app.preferences.edit { it[stringPreferencesKey("origin")] = ""; it[stringPreferencesKey("assistant_profile")] = "coder" } }
            runBlocking { app.auth.persist(fixture.client.auth) }
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "sourceScope", fixture.scope.session("chat-a"))
                flow<AppState>(vm, "_ui").value = AppState(origin = fixture.scope.origin, owner = fixture.scope.owner, profile = fixture.scope.profile, authenticated = true, connected = true, restoringSession = false)
            }
            compose.setContent {
                NativeLocalization("en") { BeaconTheme { AssistantSettings(vm); val form by vm.form.collectAsState(); form?.let { NativeForm(vm, it) } } }
            }
            compose.waitUntil(15_000) { vm.data.value.any { (key, value) -> key.startsWith("checkup-capabilities:") && value.has("_error") } }
            val writes = { fixture.requests.filter { it.string("path").endsWith("/tasks/checkups") && it.string("method") == "POST" } }
            assertTrue(writes().isEmpty())
            compose.onNodeWithTag("assistant-settings").performScrollToNode(hasText("Check-up calls"))
            compose.onNode(hasText("Check-up calls") and hasClickAction()).performClick()
            compose.onNodeWithText("Check-up setup isn't available. Check your network, then retry.").assertExists()
            compose.onNode(hasText("Turn on") and hasClickAction()).assertIsNotEnabled()
            fixture.checkupCapabilitiesFailure = false
            compose.onNode(hasText("Retry") and hasClickAction()).performScrollTo().performClick()
            compose.waitUntil(15_000) { vm.data.value.values.any { it.optBoolean("checkups_ready") } && vm.refreshing.value.none { it.startsWith("checkup-capabilities:") } }
            compose.onNode(hasText("Turn on") and hasClickAction()).performScrollTo().assertIsEnabled().performClick()
            compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
            assertTrue(writes().isEmpty())
            compose.onNode(hasText("Turn on") and hasClickAction()).performScrollTo().assertIsEnabled().performClick()
            compose.onNode(hasText("Turn on") and hasClickAction() and hasAnyAncestor(isDialog())).assertIsEnabled().performClick()
            compose.waitUntil(15_000) { writes().size == 1 && vm.form.value == null }
            val request = writes().single()
            assertEquals("default", request.string("profile"))
            assertEquals(0, request.getJSONObject("body").length())
            assertTrue(fixture.jobs.getValue("task-a").optBoolean("paused"))
            assertFalse(fixture.jobs.getValue("task-a").optBoolean("enabled"))
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative() }
            runBlocking { app.auth.remove(fixture.scope.origin); app.preferences.updateData { prior } }
        }
    }
}
