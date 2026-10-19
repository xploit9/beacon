package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import dev.hermes.android.core.*
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.CypherClient
import dev.hermes.android.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProactiveUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<CypherApplication>()
    private fun proactiveKey(fixture: NativeExperienceFixture) = proactiveSettingsKey(fixture.scope.copy(profile = "coder"))

    @Test fun switchUsesAssistantProfileAndOnlySendsEnabled() = withFixture { vm, fixture ->
        await { vm.preferences.value.assistantProfile == "coder" }
        compose.runOnIdle { vm.more("Assistant") }
        await { fixture.requests.any { it.string("path") == PROACTIVE_SETTINGS_PATH && it.string("method") == "GET" && it.string("profile") == "coder" } }
        await { vm.data.value[proactiveKey(fixture)]?.has("enabled") == true }

        compose.onNodeWithTag("assistant-proactive-toggle").performScrollTo().performClick()
        await { fixture.proactiveEnabled["coder"] == true && vm.data.value[proactiveKey(fixture)]?.optBoolean("enabled") == true }
        val enabledWrite = fixture.requests.last { it.string("path") == PROACTIVE_SETTINGS_PATH && it.string("method") == "PUT" }
        assertEquals("coder", enabledWrite.string("profile"))
        assertEquals(1, enabledWrite.getJSONObject("body").length())
        assertTrue(enabledWrite.getJSONObject("body").getBoolean("enabled"))

        compose.onNodeWithTag("proactive-service-status-gmail", useUnmergedTree = true).performScrollTo().assertTextEquals("Not connected")
        compose.onNodeWithTag("proactive-service-status-google_calendar", useUnmergedTree = true).performScrollTo().assertTextEquals("Connected")
        compose.onNodeWithTag("proactive-service-status-drive", useUnmergedTree = true).performScrollTo().assertTextEquals("Unknown")
        compose.onNodeWithTag("proactive-service-setup-gmail").performScrollTo().performClick()
        compose.onNodeWithText("Connect Gmail with the Google Workspace skill on Hermes.").assertExists()
        compose.onNodeWithText("Done").performClick()

        compose.onNodeWithTag("assistant-proactive-toggle").performScrollTo().performClick()
        await { fixture.proactiveEnabled["coder"] == false && vm.data.value[proactiveKey(fixture)]?.optBoolean("enabled") == false }
        val writes = fixture.requests.filter { it.string("path") == PROACTIVE_SETTINGS_PATH && it.string("method") == "PUT" }
        assertEquals(2, writes.size)
        assertEquals("coder", writes.last().string("profile"))
        assertEquals(1, writes.last().getJSONObject("body").length())
        assertFalse(writes.last().getJSONObject("body").getBoolean("enabled"))
    }

    @Test fun assistantPromptDismissesUntilItsStateChangesAndOpensAssistantSettings() = withFixture { vm, fixture ->
        await { vm.preferences.value.assistantProfile == "coder" }
        compose.runOnIdle { vm.tab("Xara") }
        await { vm.ui.value.tab == "Xara" && vm.ui.value.profile == "coder" && !vm.ui.value.xaraOpening && vm.chat.value.projectionReady && vm.chat.value.sessionId.startsWith("new-") }
        await { vm.data.value[proactiveKey(fixture)]?.has("enabled") == true }
        compose.onNodeWithText("Let Cypher keep an eye on your day").assertExists()

        compose.onNodeWithTag("proactive-prompt-dismiss").performClick()
        await { compose.onAllNodesWithText("Let Cypher keep an eye on your day").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { vm.tab("Chat") }
        await { vm.ui.value.tab == "Chat" && vm.ui.value.profile == "default" && vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
        compose.runOnIdle { vm.tab("Xara") }
        await { vm.ui.value.tab == "Xara" && vm.ui.value.profile == "coder" && !vm.ui.value.xaraOpening && vm.chat.value.projectionReady && vm.chat.value.sessionId.startsWith("new-") }
        compose.onAllNodesWithText("Let Cypher keep an eye on your day").assertCountEquals(0)

        fixture.proactiveEnabled["coder"] = true
        fixture.proactiveServices = fixture.proactiveServices.map { service -> service.copy().apply {
            if (string("id") == "gmail") put("status", "connected")
            if (string("id") == "google_calendar") put("status", "missing")
        } }
        vm.load(proactiveKey(fixture), PROACTIVE_SETTINGS_PATH, mapOf("profile" to "coder"), force = true)
        await { vm.data.value[proactiveKey(fixture)]?.optBoolean("enabled") == true }
        compose.onNodeWithText("Connect Google Calendar so Cypher can keep up with your day").assertExists()
        compose.onNodeWithTag("proactive-prompt-settings").performClick()
        await { vm.ui.value.tab == "More" && vm.ui.value.morePage == "Assistant" }
        compose.onNodeWithTag("assistant-proactive-toggle").assertExists()
    }

    @Test fun proactiveControlsAndPromptStayHiddenWhenPluginIsAbsent() = withFixture(pluginStatus = "absent") { vm, fixture ->
        await { vm.preferences.value.assistantProfile == "coder" }
        compose.runOnIdle { vm.more("Assistant") }
        await { compose.onAllNodesWithText("Proactive").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { vm.tab("Xara") }
        await { vm.ui.value.tab == "Xara" && vm.ui.value.profile == "coder" && !vm.ui.value.xaraOpening && vm.chat.value.projectionReady && vm.chat.value.sessionId.startsWith("new-") }
        compose.onAllNodesWithTag("proactive-prompt-card").assertCountEquals(0)
        assertFalse(fixture.requests.any { it.string("path") == PROACTIVE_SETTINGS_PATH })
    }

    @Suppress("UNCHECKED_CAST") private fun <T> flow(vm: CypherViewModel, name: String) =
        CypherViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: CypherViewModel, name: String, value: Any?) {
        CypherViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun withFixture(pluginStatus: String = "present", block: (CypherViewModel, NativeExperienceFixture) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val prior = runBlocking { app.preferences.data.first() }
        val dismissalKey = stringPreferencesKey("proactive_prompt_dismissed")
        runBlocking {
            app.preferences.edit {
                it[stringPreferencesKey("origin")] = ""
                it[stringPreferencesKey("assistant_name")] = "Cypher"
                it[stringPreferencesKey("assistant_profile")] = "coder"
                it.remove(dismissalKey)
            }
        }
        val store = ViewModelStore()
        val fixture = NativeExperienceFixture().apply { bootstrapPluginStatus = pluginStatus }
        val profileScopes = listOf(fixture.scope, fixture.scope.copy(profile = "coder"))
        runBlocking {
            app.auth.remove(fixture.scope.origin)
            profileScopes.forEach { owner ->
                listOf("xara-session", "xara-opening", "bot-mode-return", "new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session").forEach { app.cache.delete(owner, it) }
                (1..4).forEach { n -> listOf("pending", "draft", "session", "client-created-shell").forEach { app.cache.delete(owner.session("new-$n"), it) } }
            }
        }
        lateinit var vm: CypherViewModel
        val profileSwitchClientFactory: (AuthContext) -> CypherClient = { auth -> fixture.freshClientFor(auth, app.auth) }
        try {
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { CypherViewModel(app, SavedStateHandle()) } })[CypherViewModel::class.java] }
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                vm.client = fixture.client
                field(vm, "profileSwitchClientFactoryForTest", profileSwitchClientFactory)
                field(vm, "sourceScope", fixture.scope.session("chat-a"))
                app.calls.ownerScope = fixture.scope
                flow<AppState>(vm, "_ui").value = AppState(
                    origin = fixture.scope.origin,
                    authenticated = true,
                    owner = fixture.scope.owner,
                    connected = true,
                    restoringSession = false,
                    bootstrap = json("voice" to json("available" to false), "optional_features" to json("plugin" to pluginStatus)),
                    sessions = fixture.sessions.keys.sorted().map { fixture.snapshot(it) },
                )
                val snapshot = fixture.snapshot("chat-a")
                flow<ChatState>(vm, "_chat").value = ChatState(
                    sessionId = "chat-a", title = "Travel planning", raw = snapshot,
                    messages = snapshot.rows("messages").mapIndexed { index, row -> Message.from(row, index, "chat-a") },
                )
                vm.storeData("profile-settings", json("render_user_markdown" to true, "show_thinking" to true, "send_key" to "enter"))
            }
            compose.setContent {
                val settings by vm.data.collectAsState()
                val prefs by vm.preferences.collectAsState()
                CompositionLocalProvider(
                    LocalCypherSettings provides (settings["profile-settings"] ?: JSONObject()),
                    LocalAssistantName provides prefs.assistantName,
                ) {
                    NativeLocalization("en") {
                        CypherTheme(dark = true) {
                            NativeWindowLayout(compose.activity) {
                                Box(Modifier.fillMaxSize().testTag("proactive-test-viewport")) { CypherRoot(vm) }
                            }
                        }
                    }
                }
            }
            block(vm, fixture)
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative(); app.calls.ownerScope = null }
            runBlocking {
                app.auth.remove(fixture.scope.origin)
                profileScopes.forEach { owner ->
                    listOf("xara-session", "xara-opening", "bot-mode-return", "new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session").forEach { app.cache.delete(owner, it) }
                    (1..4).forEach { n -> listOf("pending", "draft", "session", "client-created-shell").forEach { app.cache.delete(owner.session("new-$n"), it) } }
                }
                app.preferences.updateData { prior }
            }
        }
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)
}
