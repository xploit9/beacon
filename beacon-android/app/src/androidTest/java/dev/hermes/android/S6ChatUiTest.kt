package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
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
import dev.hermes.android.ui.ChatScreen
import dev.hermes.android.ui.NativeLocalization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Lane S6 on a real screen with a synthetic native authority (the fixture's request log is what Hermes received):
 * a New chat left empty is the one the next New chat opens, and on a Hermes without admissions a queued message is sent once when the done event's rows are in the history.
 * Synthetic text only.
 */
class S6ChatUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val rendered = mutableStateOf<BeaconViewModel?>(null)

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun withFixture(block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val prior = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture()
        val store = ViewModelStore()
        val scope = fixture.scope.session("chat-a")
        val cacheScopes = listOf(scope, fixture.scope.session("chat-b"))
        val cacheKinds = listOf("pending", "draft", "session", "running-turn", "live:runtime-chat-a", "live:chat-a", "live:runtime-chat-b", "live:chat-b")
        val cacheEntries = cacheScopes.flatMap { savedScope -> cacheKinds.map { savedScope to it } }
        val priorCache = runBlocking { cacheEntries.associateWith { (savedScope, kind) -> app.cache.read(savedScope, kind) } }
        val ownerKinds = listOf("new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session", "conversation-index")
        val priorOwner = runBlocking { ownerKinds.associateWith { app.cache.read(fixture.scope, it) } }
        lateinit var vm: BeaconViewModel
        var constructed: BeaconViewModel? = null
        try {
            runBlocking {
                app.preferences.edit { it[stringPreferencesKey("origin")] = ""; it.remove(stringPreferencesKey("busy_send_action")) }
                app.auth.persist(fixture.client.auth)
                cacheEntries.forEach { (savedScope, kind) -> app.cache.delete(savedScope, kind) }
                ownerKinds.forEach { app.cache.delete(fixture.scope, it) }
            }
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            constructed = vm
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "profileSwitchClientFactoryForTest", { auth: dev.hermes.android.data.AuthContext -> fixture.freshClientFor(auth, app.auth) })
                field(vm, "sourceScope", scope)
                flow<AppState>(vm, "_ui").value = AppState(origin = scope.origin, owner = scope.owner, profile = scope.profile, authenticated = true, connected = true, restoringSession = false)
                val snapshot = fixture.snapshot("chat-a").apply { remove("runtime_session_id"); put("model_provider", string("provider")) }
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = "chat-a", title = "Travel planning", raw = snapshot,
                    messages = snapshot.rows("messages").mapIndexed { index, row -> Message.from(row, index, "chat-a") })
                vm.storeData("profile-settings", json("send_key" to "enter"))
            }
            rendered.value = vm
            compose.setContent { NativeLocalization("en") { BeaconTheme { rendered.value?.let { current -> ChatScreen(current) } } } }
            block(vm, fixture)
        } finally {
            fixture.releasePromptAcknowledgement()
            compose.runOnUiThread { constructed?.client?.closeNative(); store.clear(); fixture.client.closeNative() }
            runBlocking {
                cacheEntries.forEach { entry -> priorCache[entry]?.let { app.cache.write(entry.first, entry.second, it, true) } ?: app.cache.delete(entry.first, entry.second) }
                ownerKinds.forEach { kind -> priorOwner[kind]?.let { app.cache.write(fixture.scope, kind, it, true) } ?: app.cache.delete(fixture.scope, kind) }
                fixture.sessions.keys.filter { it.startsWith("new-") }.forEach { id ->
                    val created = fixture.scope.session(id)
                    app.cache.delete(created, "client-created-shell"); app.cache.delete(created, "session"); app.cache.delete(created, "draft")
                }
                app.auth.remove(fixture.scope.origin)
                app.preferences.updateData { prior }
            }
        }
    }

    private fun creates(fixture: NativeExperienceFixture) = fixture.requests.count { it.string("method") == "session.create" }
    private fun submits(fixture: NativeExperienceFixture) = fixture.requests.count { it.string("method") == "prompt.submit" }
    private fun awaitNewChat(vm: BeaconViewModel) =
        compose.waitUntil(15_000) { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }

    /** Live on the demo Hermes (9 Nov): opening an existing chat and then New chat left one more empty untitled session each time. */
    @Test fun aNewChatLeftEmptyIsTheOneTheNextNewChatOpensNotAnotherSession() = withFixture { vm, fixture ->
        val before = creates(fixture)
        compose.runOnIdle { vm.newChat() }
        awaitNewChat(vm)
        val shell = vm.chat.value.sessionId
        assertEquals("The first New chat makes the one empty chat", before + 1, creates(fixture))
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        compose.runOnIdle { vm.newChat() }
        awaitNewChat(vm)
        assertEquals("New chat opens the empty chat made before", shell, vm.chat.value.sessionId)
        runCatching { compose.waitUntil(2_000) { creates(fixture) > before + 1 } }
        assertEquals("No second session for the same empty chat", before + 1, creates(fixture))
        compose.runOnIdle { vm.selectSession("chat-a") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
        compose.runOnIdle { vm.newChat() }
        awaitNewChat(vm)
        assertEquals("Still the same one the third time", shell, vm.chat.value.sessionId)
        assertEquals(before + 1, creates(fixture))
    }

    @Test fun aNewChatThatHasAMessageIsNotReusedAndTheNextOneIsFresh() = withFixture { vm, fixture ->
        val before = creates(fixture)
        compose.runOnIdle { vm.newChat() }
        awaitNewChat(vm)
        val used = vm.chat.value.sessionId
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        compose.onNodeWithTag("message-input").performTextInput("First words in a new chat")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { submits(fixture) == 1 }
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        compose.runOnIdle { vm.newChat() }
        awaitNewChat(vm)
        assertNotEquals("A chat with a message is not the empty one", used, vm.chat.value.sessionId)
        assertEquals(before + 2, creates(fixture))
    }

    /** Root's check (9 Nov): a reused empty chat carries nothing over from before. Hermes fixes a chat's model when it makes it, so after the profile's default changed the old empty chat is not the chat a new one would be. */
    @Test fun anEmptyChatMadeBeforeTheProfileDefaultChangedIsNotReused() = withFixture { vm, fixture ->
        val before = creates(fixture)
        compose.runOnIdle { vm.newChat() }
        awaitNewChat(vm)
        val old = vm.chat.value.sessionId
        fixture.profileModel = "other-model"
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        compose.runOnIdle { vm.newChat() }
        awaitNewChat(vm)
        assertNotEquals("The old empty chat keeps the model it was made with", old, vm.chat.value.sessionId)
        assertEquals(before + 2, creates(fixture))
        assertEquals("The new chat is made with the profile's current default", "other-model", fixture.sessions.getValue(vm.chat.value.sessionId).getJSONObject("info").string("model"))
    }

    /**
     * R9 Nice 6 (Root, 9 Nov): the words typed in the empty chat itself are the owner's own, not carried from anywhere else, and the chat is hidden from History while it is empty,
     * so New chat is the only way back to it. A reused empty chat opens with that draft (its attachments live in the same saved draft). What a reused chat never carries over is
     * a held send, a receipt or a queued message (the chat is then not reused at all), and settings Hermes saved on it (see the two tests below).
     */
    @Test fun aReusedNewChatKeepsTheDraftTypedInThatSameChat() = withFixture { vm, fixture ->
        val before = creates(fixture)
        compose.runOnIdle { vm.newChat() }
        awaitNewChat(vm)
        val shell = vm.chat.value.sessionId
        compose.runOnIdle { vm.composer(androidx.compose.ui.text.input.TextFieldValue("Typed before leaving")) }
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        compose.runOnIdle { vm.newChat() }
        awaitNewChat(vm)
        assertEquals("the empty chat is reused", shell, vm.chat.value.sessionId)
        assertEquals(before + 1, creates(fixture))
        compose.waitUntil(15_000) { vm.composer.value.text == "Typed before leaving" }
        assertEquals("the words typed in this very chat are still there", "Typed before leaving", vm.composer.value.text)
        assertTrue(vm.chat.value.queued.isEmpty())
    }

    /** Leaves the empty chat for another one and taps New chat; returns the chat that opened. */
    private fun leaveAndTapNewChat(vm: BeaconViewModel): String {
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        compose.runOnIdle { vm.newChat() }
        awaitNewChat(vm)
        return vm.chat.value.sessionId
    }

    /**
     * R9 Should 1: Hermes saves a chat's "Never ask" on its row (hermes_state_sessions.set_session_yolo -> model_config.yolo_mode) and it survives a resume, so an empty chat the
     * owner set to Never ask is not a new chat: reusing it would run tools without asking in a chat that looks new.
     */
    @Test fun anEmptyChatSetToNeverAskIsNotReusedAndTheNewChatAsksAsUsual() = withFixture { vm, fixture ->
        val before = creates(fixture)
        compose.runOnIdle { vm.newChat() }
        awaitNewChat(vm)
        val old = vm.chat.value.sessionId
        fixture.sessions.getValue(old).put("model_config", """{"yolo_mode":true}""")
        val opened = leaveAndTapNewChat(vm)
        assertNotEquals("A chat that skips approvals is not the empty chat to open", old, opened)
        assertEquals(before + 2, creates(fixture))
        assertFalse("The new chat holds no saved Never ask", fixture.sessions.getValue(opened).optString("model_config").contains("yolo_mode\":true"))
    }

    /** R9 Should 1, Fast mode: Hermes saves it as model_config.service_tier (tui_gateway/agent_factory._persist_live_session_runtime, written by config.set fast). */
    @Test fun anEmptyChatSetToFastModeIsNotReused() = withFixture { vm, fixture ->
        val before = creates(fixture)
        compose.runOnIdle { vm.newChat() }
        awaitNewChat(vm)
        val old = vm.chat.value.sessionId
        fixture.sessions.getValue(old).put("model_config", """{"service_tier":"priority"}""")
        val opened = leaveAndTapNewChat(vm)
        assertNotEquals("A chat in Fast mode is not the empty chat to open", old, opened)
        assertEquals(before + 2, creates(fixture))
    }

    /**
     * A Hermes without admissions (the legacy gateway): a message queued while a reply runs is sent once, by itself, when the reply ends and the done event's rows are in
     * the saved history. Without that proof it stays held (BusyTurnSendUiTest.identicalQueuedSendsKeepIdsAndDrainWhileANewerDraftExists).
     * A regression guard, not a discriminator: on the base app this fixture also releases the queue (its saved reply has the same words as the streamed one, so the
     * words-based pairing clears the unconfirmed copy that otherwise holds it). The decision itself is proven by FinishedTurnTest (red on the base).
     */
    @Test fun aQueuedMessageOnAGatewayWithoutAdmissionsIsSentOnceWhenTheDoneEventsRowsAreInTheHistory() = withFixture { vm, fixture ->
        // The quiet-stream check (it settles a stream Hermes reports idle, 20 s by default) is switched off, so only the done event's rows can release the queue here.
        compose.runOnIdle { field(vm, "streamWatchMs", 150L); field(vm, "streamSilenceMs", 600_000L); vm.selectSession("chat-a") }
        val watcher = BeaconViewModel::class.java.getDeclaredField("sessionWatch").apply { isAccessible = true }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady && (watcher.get(vm) as? kotlinx.coroutines.Job)?.isActive == true }
        compose.runOnIdle { vm.setBusySendAction("queue") }
        compose.waitUntil(15_000) { vm.preferences.value.busySendAction == "queue" && vm.preferences.value.busySendLoaded }
        compose.onNodeWithTag("message-input").performTextInput("Begin a synthetic turn")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { submits(fixture) == 1 }
        compose.waitUntil(15_000) { vm.chat.value.streamId == "runtime-chat-a" && fixture.liveCollectorSubscriptions.get() > 0 }
        compose.onNodeWithTag("message-input").performTextInput("Follow-up after the reply")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.any { it.text == "Follow-up after the reply" && it.state == "queued" } && vm.composer.value.text.isBlank() }
        val queued = vm.chat.value.queued.single { it.text == "Follow-up after the reply" }
        compose.onNodeWithTag("queue-text:${queued.id}").assertIsDisplayed()
        runCatching { compose.waitUntil(2_500) { submits(fixture) > 1 } }
        assertEquals("Nothing is sent while the reply runs", 1, submits(fixture))

        // The reply ends as a legacy Hermes ends one: no admission, a done event that lists the committed rows, and both rows in the saved history.
        fixture.sessions.getValue("chat-a").getJSONArray("messages")
            .put(json("id" to 9001, "role" to "user", "text" to "Begin a synthetic turn")).put(json("id" to 9002, "role" to "assistant", "text" to "Synthetic reply"))
        fixture.nativeTurnStatus = "idle"
        val emittedAt = System.nanoTime()
        fixture.emitNativeEvent("message.complete", json("text" to "Synthetic reply", "status" to "complete",
            "persisted_turn" to json("complete" to true, "user_row_id" to 9001, "user_row_ids" to JSONArray(listOf(9001)), "final_assistant_row_id" to 9002, "row_ids" to JSONArray(listOf(9001, 9002)))))
        compose.waitUntil(12_000) { submits(fixture) == 2 }
        val tookMs = (System.nanoTime() - emittedAt) / 1_000_000
        println("S6 legacy drain: the queued message was sent $tookMs ms after the done event")
        runCatching { compose.waitUntil(3_000) { submits(fixture) > 2 } }
        assertEquals("The queued message was sent by itself, exactly once", 2, submits(fixture))
        val sent = fixture.requests.filter { it.string("method") == "prompt.submit" }.last().getJSONObject("params")
        assertEquals("Follow-up after the reply", sent.string("text")); assertEquals("runtime-chat-a", sent.string("session_id")); assertEquals("default", sent.string("profile"))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("queue-text:${queued.id}").fetchSemanticsNodes().isEmpty() }
        assertEquals("Still exactly one send after the box emptied", 2, submits(fixture))
    }
}
