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

    /** Opens Settings › Assistant on [fixture] with the Assistant chat on profile "coder" while the active client stays on the default profile. */
    private fun withAssistant(fixture: NativeExperienceFixture, block: (BeaconViewModel) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        check(!app.calls.state.value.active && app.calls.service == null)
        val prior = runBlocking { app.preferences.data.first() }
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
            block(vm)
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative() }
            runBlocking { app.auth.remove(fixture.scope.origin); app.preferences.updateData { prior } }
        }
    }

    @Test fun assistantUsesActiveProfileAndNeverWritesUntilConfirmed() {
        val fixture = NativeExperienceFixture()
        fixture.checkupCapabilities = json("timezone" to "Asia/Dubai", "checkups_ready" to true)
        fixture.checkupPostEnabled = true
        fixture.checkupCapabilitiesFailure = true
        fixture.jobs.getValue("task-a").put("paused", true).put("enabled", false)
        withAssistant(fixture) { vm ->
            compose.waitUntil(15_000) { vm.data.value.any { (key, value) -> key.startsWith("checkup-capabilities:") && value.has("_error") } }
            val writes = { fixture.requests.filter { it.string("path").endsWith("/tasks/checkups") && it.string("method") == "POST" } }
            assertTrue(writes().isEmpty())
            // Check-up calls are a section of their own, with no expander; a failed read shows a short error and Retry instead of the switch.
            compose.onNodeWithTag("assistant-settings").performScrollToNode(hasText("Check-up calls"))
            compose.onNodeWithText("Couldn't load check-up calls").assertExists()
            compose.onAllNodesWithTag("assistant-checkups-toggle").assertCountEquals(0)
            fixture.checkupCapabilitiesFailure = false
            compose.onNodeWithTag("checkups-retry").performScrollTo().performClick()
            compose.waitUntil(15_000) { vm.data.value.values.any { it.optBoolean("checkups_ready") } && vm.refreshing.value.none { it.startsWith("checkup-capabilities:") } }
            // The switch is off (no check-up task in this profile yet) and asks before it turns anything on.
            compose.onNodeWithTag("assistant-checkups-toggle").performScrollTo().assertIsEnabled().assertIsOff().performClick()
            compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
            assertTrue(writes().isEmpty())
            compose.onNodeWithTag("assistant-checkups-toggle").performScrollTo().assertIsEnabled().performClick()
            compose.onNode(hasText("Turn on") and hasClickAction() and hasAnyAncestor(isDialog())).assertIsEnabled().performClick()
            compose.waitUntil(15_000) { writes().size == 1 && vm.form.value == null }
            val request = writes().single()
            assertEquals("default", request.string("profile"))
            assertEquals(0, request.getJSONObject("body").length())
            assertTrue(fixture.jobs.getValue("task-a").optBoolean("paused"))
            assertFalse(fixture.jobs.getValue("task-a").optBoolean("enabled"))
        }
    }

    @Test fun checkupCallsTurnOffByDeletingOnlyTheirOwnTaskAfterConfirming() {
        val fixture = NativeExperienceFixture()
        fixture.checkupCapabilities = json("timezone" to "Asia/Dubai", "checkups_ready" to true)
        fixture.jobs["checkups-job"] = json("id" to "checkups-job", "name" to "Beacon check-ups · 3–4 daily · local 10–22", "script" to "beacon-checkups.py",
            "no_agent" to true, "deliver" to "local", "paused" to false, "enabled" to true, "schedule" to json("kind" to "interval", "display" to "every 1m"))
        withAssistant(fixture) { vm ->
            val deletes = { fixture.requests.filter { it.string("path").startsWith("/api/cron/jobs/") && it.string("method") == "DELETE" } }
            compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("assistant-checkups-toggle").assertIsOn() }.isSuccess }
            compose.onNodeWithTag("assistant-checkups-toggle").performScrollTo().assertIsEnabled().performClick()
            // Turning off asks first; Cancel deletes nothing.
            compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
            assertTrue(deletes().isEmpty())
            compose.onNodeWithTag("assistant-checkups-toggle").performScrollTo().performClick()
            compose.onNode(hasText("Turn off") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(15_000) { deletes().size == 1 && vm.form.value == null }
            assertEquals("/api/cron/jobs/checkups-job", deletes().single().string("path"))
            assertEquals("default", deletes().single().string("profile"))
            assertFalse(fixture.jobs.containsKey("checkups-job"))
            // The owner's other schedules are untouched, and the switch follows the refreshed list.
            assertTrue(fixture.jobs.containsKey("task-a"))
            compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("assistant-checkups-toggle").assertIsOff() }.isSuccess }
        }
    }
}
