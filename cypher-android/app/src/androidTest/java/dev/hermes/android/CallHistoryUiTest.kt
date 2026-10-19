package dev.hermes.android

import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.*
import dev.hermes.android.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CallHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<CypherApplication>()
    private val args get() = InstrumentationRegistry.getArguments()

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: CypherViewModel, name: String) =
        CypherViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: CypherViewModel, name: String, value: Any?) {
        CypherViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    @Test fun assistantCallHistoryListsCallsAndReturnsToTheInlineTranscript() {
        check(args.getString("native_ui_probe") == "1") { "Explicit synthetic UI probe flag required" }
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val priorCallOwnerScope = app.calls.ownerScope
        val priorPreferences = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture()
        val ownerCache = fixture.scope
        val chatScope = ownerCache.session("chat-a")
        runBlocking {
            app.preferences.edit {
                it[stringPreferencesKey("origin")] = ""
                it[stringPreferencesKey("assistant_profile")] = "default"
            }
        }
        val callId = "call-history-completed"
        val now = System.currentTimeMillis() / 1000
        fixture.callHistoryResponse = json("calls" to JSONArray()
            .put(json("id" to callId, "direction" to "outgoing", "state" to "completed", "started_at" to now,
                "duration_s" to 83, "provider" to "gemini", "conversation_id" to "chat-a", "reason" to null, "has_transcript" to true))
            .put(json("id" to "call-history-missed", "direction" to "incoming", "state" to "missed", "started_at" to now - 60,
                "duration_s" to null, "provider" to null, "conversation_id" to "chat-b", "reason" to "No answer", "has_transcript" to false)), "next" to null)
        val messages = buildList {
            (1..45).forEach { index ->
                if (index == 23) add(Message("stored-call-row", "user", "Transcript from completed call", sessionId = "chat-a"))
                add(Message("saved-$index", "user", "Saved history item $index", sessionId = "chat-a"))
            }
        }
        val store = ViewModelStore()
        lateinit var vm: CypherViewModel
        try {
            compose.runOnUiThread {
                val saved = SavedStateHandle(mapOf("cypher:${ownerCache.key}" to "chat-a", "tab" to "Cypher"))
                vm = ViewModelProvider(store, viewModelFactory { initializer { CypherViewModel(app, saved) } })[CypherViewModel::class.java]
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "sourceScope", chatScope)
                app.calls.ownerScope = ownerCache
                val snapshot = fixture.snapshot("chat-a")
                    .put("profile", ownerCache.profile)
                    .put("session_id", "chat-a")
                    .put("voice_transcripts", JSONArray().put(json(
                        "call_id" to callId, "event_id" to "$callId:user:turn-1", "session_id" to "chat-a",
                        "role" to "user", "text" to "Transcript from completed call", "message_id" to "stored-call-row",
                        "final" to true,
                    )))
                fixture.snapshot("chat-b")
                flow<AppState>(vm, "_ui").value = AppState(
                    origin = ownerCache.origin, authenticated = true, profile = ownerCache.profile, chatProfile = ownerCache.profile,
                    owner = ownerCache.owner, tab = "Cypher", connected = false, restoringSession = false,
                    bootstrap = json("optional_features" to json("plugin" to "available")),
                    sessions = fixture.sessions.keys.sorted().map { fixture.snapshot(it) },
                )
                flow<ChatState>(vm, "_chat").value = ChatState(
                    sessionId = "chat-a", title = "Travel planning", raw = snapshot, messages = messages,
                    projectionReady = true,
                )
            }
            compose.setContent {
                NativeLocalization("en") { CypherTheme {
                    CompositionLocalProvider(LocalAssistantName provides "Cypher", LocalCypherSettings provides JSONObject()) {
                        ChatScreen(vm, cypher = true)
                    }
                } }
            }

            compose.onNodeWithText("Transcript from completed call").assertDoesNotExist()
            compose.onNodeWithContentDescription("Conversation actions").performClick()
            compose.onNodeWithTag("call-history-open").performClick()
            compose.waitUntil(10_000) {
                fixture.requests.any { it.string("path") == "/api/plugins/cypher/mobile/calls" && it.string("profile") == "default" } &&
                    compose.onAllNodesWithText("Outgoing call").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Outgoing call").assertExists()
            compose.onNodeWithText("Missed call").assertExists()
            compose.onNodeWithText(" · 1:23 · Gemini", substring = true).assertExists()
            compose.onNodeWithTag("call-history-row:$callId").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Transcript from completed call").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Transcript from completed call").assertIsDisplayed()
            compose.onNodeWithContentDescription("Conversation actions").performClick()
            compose.onNodeWithTag("call-history-open").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Missed call").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("call-history-row:call-history-missed").performClick()
            compose.waitUntil(5_000) { vm.chat.value.sessionId == "chat-b" }
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative(); app.calls.ownerScope = priorCallOwnerScope }
            runBlocking {
                app.preferences.updateData { priorPreferences }
                app.auth.remove(ownerCache.origin)
            }
        }
    }
}
