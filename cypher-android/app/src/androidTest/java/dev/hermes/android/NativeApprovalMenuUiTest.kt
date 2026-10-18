package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.*
import dev.hermes.android.ui.NativeApprovalMenu
import dev.hermes.android.ui.NativeLocalization
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NativeApprovalMenuUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<CypherApplication>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: CypherViewModel, name: String) =
        CypherViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<T>

    private fun field(vm: CypherViewModel, name: String, value: Any?) {
        CypherViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun withFixture(block: (CypherViewModel, NativeExperienceFixture) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val originKey = stringPreferencesKey("origin")
        val prior = runBlocking { app.preferences.data.first() }
        runBlocking { app.preferences.edit { it[originKey] = "" } }
        val store = ViewModelStore()
        val fixture = NativeExperienceFixture()
        lateinit var vm: CypherViewModel
        try {
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { CypherViewModel(app, SavedStateHandle()) } })[CypherViewModel::class.java]
            }
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "sourceScope", fixture.scope.session("chat-a"))
                app.calls.ownerScope = fixture.scope
                flow<AppState>(vm, "_ui").value = AppState(
                    origin = fixture.scope.origin, profile = fixture.scope.profile, authenticated = true,
                    owner = fixture.scope.owner, connected = true, restoringSession = false,
                    bootstrap = json("voice" to json("available" to false)),
                    sessions = fixture.sessions.keys.sorted().map(fixture::snapshot),
                )
                val snapshot = fixture.snapshot("chat-a")
                flow<ChatState>(vm, "_chat").value = ChatState(
                    sessionId = "chat-a", title = "Travel planning", raw = snapshot,
                    messages = snapshot.rows("messages").mapIndexed { index, row -> Message.from(row, index, "chat-a") },
                )
            }
            compose.setContent { NativeLocalization("en") { CypherTheme(dark = true) { NativeApprovalMenu(vm, enabled = true) } } }
            block(vm, fixture)
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative(); app.calls.ownerScope = null }
            runBlocking {
                app.preferences.edit { prior[originKey]?.let { value -> it[originKey] = value } ?: it.remove(originKey) }
                app.auth.remove(fixture.scope.origin)
            }
        }
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)
    private fun open(vm: CypherViewModel) {
        compose.onNodeWithTag("composer-approvals").performClick()
        await { vm.data.value["picker:approvals"] != null && "picker:approvals" !in vm.refreshing.value }
    }

    @Test fun yoloRequiresConfirmationAndCanBeTurnedOffForTheSession() = withFixture { vm, fixture ->
        open(vm)
        compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        compose.onNodeWithText("Enable YOLO for this chat?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, fixture.requests.count { it.string("method") == "config.set" && it.optJSONObject("params")?.string("key") == "yolo" })

        open(vm)
        compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        compose.onNodeWithText("Enable YOLO").performClick()
        await { vm.data.value["picker:approvals"]?.optBoolean("session_bypass") == true }
        val enable = fixture.requests.single { it.string("method") == "config.set" && it.optJSONObject("params")?.string("key") == "yolo" }.getJSONObject("params")
        assertEquals("1", enable.string("value")); assertEquals("session", enable.string("scope"))
        assertEquals("runtime-chat-a", enable.string("session_id"))
        compose.onNodeWithContentDescription("Approvals · YOLO on").assertIsDisplayed()

        open(vm)
        compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        await { vm.data.value["picker:approvals"]?.optBoolean("session_bypass") == false }
        val writes = fixture.requests.filter { it.string("method") == "config.set" && it.optJSONObject("params")?.string("key") == "yolo" }
        assertEquals("0", writes.last().getJSONObject("params").string("value"))
        assertEquals("manual", vm.data.value["picker:approvals"]?.string("value"))
    }

    @Test fun profileOffDisablesYoloWithTheReason() = withFixture { vm, fixture ->
        fixture.approvalMode = "off"
        open(vm)
        compose.onNodeWithText("Profile approvals are Off, so this chat already bypasses prompts. Choose Manual or Smart before using the per-chat override.").assertIsDisplayed()
        compose.onNodeWithTag("approval-yolo").assertIsNotEnabled()
        assertTrue(fixture.requests.none { it.string("method") == "config.set" && it.optJSONObject("params")?.string("key") == "yolo" })
    }

    @Test fun unknownYoloStatusOffersSessionScopedDisable() = withFixture { vm, fixture ->
        fixture.sessions.getValue("chat-a").getJSONObject("info").remove("yolo")
        open(vm)
        compose.onNodeWithContentDescription("Approvals · YOLO status unknown for this chat").assertIsDisplayed()
        compose.onNodeWithText("YOLO status unknown for this chat").assertIsDisplayed()
        compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        await { vm.data.value["picker:approvals"]?.optBoolean("session_status_known") == true }
        val write = fixture.requests.single { it.string("method") == "config.set" && it.optJSONObject("params")?.string("key") == "yolo" }.getJSONObject("params")
        assertEquals("0", write.string("value")); assertEquals("session", write.string("scope"))
        assertEquals("runtime-chat-a", write.string("session_id"))
        compose.onNodeWithContentDescription("Approvals").assertIsDisplayed()
    }

    @Test fun freshRefreshKeepsTheLastVerifiedYoloStateWhenHermesOmitsIt() = withFixture { vm, fixture ->
        open(vm)
        compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        compose.onNodeWithText("Enable YOLO").performClick()
        await { vm.data.value["picker:approvals"]?.let { it.optBoolean("session_status_known") && it.optBoolean("session_bypass") } == true }
        compose.runOnIdle { }
        fixture.sessions.getValue("chat-a").getJSONObject("info").remove("yolo")

        val beforeRefresh = fixture.requests.count { it.string("path") == "/api/auth/ws-ticket" }
        compose.onNodeWithTag("composer-approvals").performClick()
        await { fixture.requests.count { it.string("path") == "/api/auth/ws-ticket" } == beforeRefresh + 1 && "picker:approvals" !in vm.refreshing.value }
        compose.onNodeWithContentDescription("Approvals · YOLO status unknown for this chat").assertIsDisplayed()
        compose.onNodeWithText("YOLO status unknown for this chat. Last verified YOLO: on.").assertIsDisplayed()
        compose.onNodeWithText("Turn off YOLO for this chat").assertIsDisplayed()
    }

    @Test fun fixtureRetainsTerminalEventsOnTheMatchingConversation() {
        val fixture = NativeExperienceFixture()
        fixture.persistTerminalMessages = true
        try {
            val chatA = fixture.sessions.getValue("chat-a").getJSONArray("messages")
            val chatB = fixture.sessions.getValue("chat-b").getJSONArray("messages")
            val beforeA = chatA.length()
            val beforeB = chatB.length()
            val event = fixture.retainNativeEvent("runtime-chat-b", "message.complete", json("text" to "Only chat B finished"))
            assertEquals("runtime-chat-b", event.getString("session_id"))
            assertEquals(beforeA, chatA.length())
            assertEquals(beforeB + 1, chatB.length())
            assertEquals("Only chat B finished", chatB.getJSONObject(beforeB).getString("text"))
        } finally {
            fixture.client.closeNative()
        }
    }
}
