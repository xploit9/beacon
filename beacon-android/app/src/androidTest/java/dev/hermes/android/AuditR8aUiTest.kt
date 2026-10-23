package dev.hermes.android

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.*
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.NativeBearerRecord
import dev.hermes.android.data.NativePairing
import dev.hermes.android.ui.KanbanScreen
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.SetupWizard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Run only in the coordinator's isolated synthetic UI installation. */
class AuditR8aUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    private fun withVm(block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        check(InstrumentationRegistry.getArguments().getString("native_ui_probe") == "1")
        check(!app.calls.state.value.active && app.calls.service == null)
        val priorPreferences = runBlocking { app.preferences.data.first() }
        runBlocking { app.preferences.edit { it[stringPreferencesKey("origin")] = "" } }
        val fixture = NativeExperienceFixture()
        val draftKind = "kanban-draft:default"
        val priorDraft = runBlocking { app.cache.read(fixture.scope, draftKind) }
        runBlocking { app.cache.delete(fixture.scope, draftKind) }
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        try {
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
                BeaconViewModel::class.java.getDeclaredField("originGeneration").apply { isAccessible = true }.setInt(vm, 100)
                BeaconViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.set(vm, fixture.client)
                @Suppress("UNCHECKED_CAST")
                val state = BeaconViewModel::class.java.getDeclaredField("_ui").apply { isAccessible = true }.get(vm) as MutableStateFlow<AppState>
                state.value = AppState(origin = fixture.scope.origin, setupOrigin = fixture.scope.origin, owner = fixture.scope.owner,
                    profile = fixture.scope.profile, authenticated = true, connected = true, restoringSession = false, setupWizard = true, setupStep = "connect")
            }
            block(vm, fixture)
        } finally {
            compose.runOnUiThread { store.clear(); vm.client?.closeNative(); fixture.client.closeNative() }
            runBlocking {
                app.preferences.updateData { priorPreferences }
                if (priorDraft == null) app.cache.delete(fixture.scope, draftKind) else app.cache.write(fixture.scope, draftKind, priorDraft, true)
            }
        }
    }

    @Test fun kanbanCancelIsTaskScopedAndMissingTaskDropsOnlyItsDraft() = withVm { vm, fixture ->
        fixture.boardTasks.clear()
        fixture.boardTasks["first"] = json("id" to "first", "title" to "First task", "body" to "First notes", "assignee" to "default", "status" to "todo")
        fixture.boardTasks["second"] = json("id" to "second", "title" to "Second task", "body" to "Second notes", "assignee" to "default", "status" to "todo")
        compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) { KanbanScreen(vm) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("kanban-edit:first").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("kanban-edit:first").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("kanban-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("kanban-edit:second").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("kanban-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("kanban-title").assertTextContains("Second task")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("kanban-edit:first").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("kanban-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("kanban-title").performTextReplacement("Retained first draft")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("kanban-new-task").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("kanban-title").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("", compose.onNodeWithTag("kanban-title").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("kanban-edit:first").performClick()
        compose.onNodeWithTag("kanban-title").assertTextContains("Retained first draft")
        fixture.boardTasks.remove("first")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("This task no longer exists. Its saved draft was cleared.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("kanban-title").assertDoesNotExist()
        compose.onNodeWithTag("kanban-edit:second").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("kanban-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("kanban-title").assertTextContains("Second task")
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun pairingRedeemContinuesAcrossSavedStateRestoration() = pairingSurvivesRestoration("/pairing/redeem")

    @Test fun redeemedTicketIsNotSpentAgainAfterSavedStateRestoration() = pairingSurvivesRestoration("/devices/link")

    private fun pairingSurvivesRestoration(pausedPath: String) = withVm { vm, fixture ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val redemptionRequests = AtomicInteger()
        val scope = fixture.scope
        val auth = AuthContext(scope.origin, emptyList(), scope.owner, scope.profile, backend = "hermes-dashboard",
            bearer = NativeBearerRecord("fixture-access", "fixture-refresh", Long.MAX_VALUE, scope.origin, scope.owner, scope.profile))
        val client = BeaconClient(auth, null, fixture, persistContext = false) { builder ->
            builder.addInterceptor { chain ->
                if (chain.request().url.encodedPath.endsWith("/pairing/redeem")) {
                    redemptionRequests.incrementAndGet()
                }
                if (chain.request().url.encodedPath.endsWith(pausedPath)) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                chain.proceed(chain.request())
            }
            fixture.configureHttp(builder)
        }
        val pairingPreferences = app.getSharedPreferences("native-pairing", Context.MODE_PRIVATE)
        val deviceKey = "device:${"${scope.origin}\u0000${scope.owner}".digest()}"
        val priorDevice = pairingPreferences.getString(deviceKey, null)
        try {
            compose.runOnIdle { BeaconViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.set(vm, client) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent { NativeLocalization("en") { BeaconTheme(dark = true) { SetupWizard(vm) } } }
            compose.onNodeWithTag("setup-method-code").performClick()
            compose.onNodeWithTag("setup-pairing-code").performTextInput("abc123")
            compose.onNodeWithTag("setup-pairing-redeem").performClick()
            compose.onNodeWithTag("setup-pairing-confirm").performClick()
            // Advance Compose's test clock so the click's launched pairing work can enter the request.
            compose.waitUntil(5_000) { entered.count == 0L }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Linking this phone").assertIsDisplayed()
            assertEquals(1, redemptionRequests.get())
            release.countDown()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Linked").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, redemptionRequests.get())
            assertEquals(1, fixture.pairingRedeemCount.get())
            assertEquals(1, fixture.pairingLinkCount.get())
            assertEquals("device-123", NativePairing.deviceId(app, scope))
        } finally {
            release.countDown()
            client.closeNative()
            if (priorDevice == null) pairingPreferences.edit().remove(deviceKey).commit() else pairingPreferences.edit().putString(deviceKey, priorDevice).commit()
        }
    }
}
