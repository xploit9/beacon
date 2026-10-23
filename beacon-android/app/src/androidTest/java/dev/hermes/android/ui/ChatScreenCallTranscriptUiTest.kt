package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
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
import dev.hermes.android.AppState
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.ChatState
import dev.hermes.android.NativeExperienceFixture
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.voice.CallTranscript
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import dev.hermes.android.BeaconTheme
import dev.hermes.android.preferences
import org.junit.Test

class ChatScreenCallTranscriptUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    @Test fun chatShowsGroupedCallsAndSearchFindsATermInACollapsedLine() {
        assertFalse("Never replace an active real call", app.calls.state.value.active || app.calls.service != null)
        val priorOwnerScope = app.calls.ownerScope
        val priorPreferences = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture()
        val owner = fixture.scope
        val chatId = "chat-a"
        val startedAt = System.currentTimeMillis() / 1000
        fixture.callHistoryResponse = json("calls" to JSONArray()
            .put(json("id" to "call-one", "conversation_id" to chatId, "started_at" to startedAt,
                "duration_s" to 240, "provider" to "gemini"))
            .put(json("id" to "call-two", "conversation_id" to chatId, "started_at" to startedAt + 1,
                "duration_s" to 180, "provider" to "grok")), "next" to null)
        val transcript = buildList {
            (1..5).forEach { turn ->
                val role = if (turn % 2 == 1) "user" else "assistant"
                add(CallTranscript("call-one:$role:$turn", role,
                    if (turn == 1) "Hidden orchid clue" else "First call line $turn", at = startedAt * 1000 + turn, turnId = turn.toString()))
            }
            (1..5).forEach { turn ->
                val role = if (turn % 2 == 1) "user" else "assistant"
                add(CallTranscript("call-two:$role:$turn", role, "Second call line $turn", at = startedAt * 1000 + 100 + turn, turnId = turn.toString()))
            }
        }
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        try {
            runBlocking {
                app.preferences.edit {
                    it[stringPreferencesKey("origin")] = ""
                    it[stringPreferencesKey("assistant_profile")] = "default"
                }
            }
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "sourceScope", owner.session(chatId))
                app.calls.ownerScope = owner
                flow<AppState>(vm, "_ui").value = AppState(
                    origin = owner.origin, authenticated = true, owner = owner.owner, profile = owner.profile,
                    chatProfile = owner.profile, tab = "Chat", connected = false, restoringSession = false,
                )
                flow<ChatState>(vm, "_chat").value = ChatState(
                    sessionId = chatId, title = "Two calls", callMessages = transcript, projectionReady = true,
                )
            }
            compose.setContent {
                NativeLocalization("en") { BeaconTheme {
                    CompositionLocalProvider(LocalAssistantName provides "Beacon", LocalBeaconSettings provides JSONObject()) {
                        ChatScreen(vm)
                    }
                } }
            }

            compose.waitUntil(10_000) {
                fixture.requests.any { it.string("path") == "/api/plugins/beacon/mobile/calls" } &&
                    compose.onAllNodesWithTag("call-transcript-card:call-one").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("call-transcript-card:call-two").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("Call · 4 min · Gemini").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("Call · 3 min · Grok").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Call · 4 min · Gemini").assertExists()
            compose.onNodeWithText("Call · 3 min · Grok").assertExists()
            compose.onAllNodesWithContentDescription("Call transcript").assertCountEquals(2)
            val ownerTextLayout = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("First call line 3", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(ownerTextLayout) }
            assertTrue("Owner line layout should be available", ownerTextLayout.isNotEmpty())
            assertTrue("Owner line should be start-aligned", ownerTextLayout.first().layoutInput.style.textAlign == TextAlign.Start)
            compose.onNodeWithText("Hidden orchid clue").assertDoesNotExist()
            (2..5).forEach { compose.onNodeWithText("First call line $it").assertExists() }
            compose.onNodeWithTag("call-transcript-toggle:call-one").assertTextEquals("Show all")
            // The expanded card can scroll under the floating chat header, so use the toggle's click action, not a tap.
            compose.onNodeWithTag("call-transcript-toggle:call-one").assertExists().performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Hidden orchid clue").assertExists()
            compose.onNodeWithTag("call-transcript-toggle:call-one").assertTextEquals("Show less").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Hidden orchid clue").assertDoesNotExist()

            val firstTop = compose.onNodeWithTag("call-transcript-card:call-one").fetchSemanticsNode().boundsInRoot.top
            val secondTop = compose.onNodeWithTag("call-transcript-card:call-two").fetchSemanticsNode().boundsInRoot.top
            assertTrue("Call cards stay in first-event order", firstTop < secondTop)

            compose.onNodeWithContentDescription("Search this conversation").performClick()
            compose.onNode(hasSetTextAction() and hasText("Find in conversation")).performTextInput("orchid")
            compose.onNodeWithTag("call-transcript-card:call-one").assertIsDisplayed()
            compose.onNodeWithTag("call-transcript-card:call-two").assertDoesNotExist()
            // Release review 1.1.2: a search shows its matching line even when the card is collapsed.
            compose.onNodeWithText("Hidden orchid clue").assertExists()
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative(); app.calls.ownerScope = priorOwnerScope }
            runBlocking {
                app.preferences.updateData { priorPreferences }
                app.auth.remove(owner.origin)
            }
        }
    }
}
