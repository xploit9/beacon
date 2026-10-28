package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
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
import dev.hermes.android.ui.KanbanScreen
import dev.hermes.android.ui.NativeLocalization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

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
        runBlocking { app.auth.persist(fixture.client.auth) }
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
                app.auth.remove(fixture.scope.origin)
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
        compose.onNodeWithText("Keep draft").performClick()
        compose.onNodeWithTag("kanban-edit:second").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("kanban-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("kanban-title").assertTextContains("Second task")
        compose.onNodeWithText("Keep draft").performClick()
        compose.onNodeWithTag("kanban-edit:first").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("kanban-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("kanban-title").performTextReplacement("Retained first draft")
        compose.onNodeWithText("Keep draft").performClick()
        compose.onNodeWithTag("kanban-new-task").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("kanban-title").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("", compose.onNodeWithTag("kanban-title").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("Keep draft").performClick()
        compose.onNodeWithTag("kanban-edit:first").performClick()
        compose.onNodeWithTag("kanban-title").assertTextContains("Retained first draft")
        fixture.boardTasks.remove("first")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("This task no longer exists. Its saved draft was cleared.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("kanban-title").assertDoesNotExist()
        compose.onNodeWithTag("kanban-edit:second").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("kanban-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("kanban-title").assertTextContains("Second task")
        compose.onNodeWithText("Keep draft").performClick()
    }
}
