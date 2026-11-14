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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The queue box and the ticks on a real screen with a synthetic native authority: what is on screen and what Hermes received (the fixture's
 * request log). Only what was sent to the agent is in the chat; what the phone holds, and what failed, is in the box above the composer.
 * Synthetic text only.
 */
class QueueBoxUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val rendered = mutableStateOf<BeaconViewModel?>(null)

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun withFixture(canonical: Boolean = false, light: Boolean = false, skin: String = "default", block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val prior = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture().apply { canonicalAuthority = canonical }
        val store = ViewModelStore()
        val scope = fixture.scope.session("chat-a")
        val cacheScopes = listOf(scope, fixture.scope.session("chat-b"))
        val cacheKinds = listOf("pending", "draft", "session", "running-turn", "live:runtime-chat-a", "live:chat-a", "live:runtime-chat-b", "live:chat-b")
        val cacheEntries = cacheScopes.flatMap { savedScope -> cacheKinds.map { savedScope to it } }
        val priorCache = runBlocking { cacheEntries.associateWith { (savedScope, kind) -> app.cache.read(savedScope, kind) } }
        lateinit var vm: BeaconViewModel
        var constructed: BeaconViewModel? = null
        try {
            runBlocking {
                app.preferences.edit { it[stringPreferencesKey("origin")] = ""; it.remove(stringPreferencesKey("busy_send_action")) }
                app.auth.persist(fixture.client.auth)
                cacheEntries.forEach { (savedScope, kind) -> app.cache.delete(savedScope, kind) }
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
            compose.setContent { NativeLocalization("en") { BeaconTheme(dark = !light, skin = skin) { rendered.value?.let { current -> ChatScreen(current) } } } }
            block(vm, fixture)
        } finally {
            fixture.releasePromptAcknowledgement()
            compose.runOnUiThread { constructed?.client?.closeNative(); store.clear(); fixture.client.closeNative() }
            runBlocking {
                cacheEntries.forEach { entry -> priorCache[entry]?.let { app.cache.write(entry.first, entry.second, it, true) } ?: app.cache.delete(entry.first, entry.second) }
                app.auth.remove(fixture.scope.origin)
                app.preferences.updateData { prior }
            }
        }
    }

    private fun startTurn(vm: BeaconViewModel, fixture: NativeExperienceFixture) {
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        compose.onNodeWithTag("message-input").performTextInput("Begin a synthetic turn")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "prompt.submit" } }
        compose.waitUntil(15_000) { vm.chat.value.streamId == "runtime-chat-a" && fixture.liveCollectorSubscriptions.get() > 0 }
    }

    private fun openWatchedChat(vm: BeaconViewModel) {
        compose.runOnIdle { field(vm, "streamWatchMs", 150L); vm.selectSession("chat-a") }
        val watcher = BeaconViewModel::class.java.getDeclaredField("sessionWatch").apply { isAccessible = true }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady && (watcher.get(vm) as? kotlinx.coroutines.Job)?.isActive == true }
    }

    private fun typeAndSend(text: String) {
        compose.onNodeWithTag("message-input").performTextInput(text)
        compose.onNodeWithTag("message-send").performClick()
    }

    private fun submitted(fixture: NativeExperienceFixture) = fixture.requests.filter { it.string("method") == "prompt.submit" }.map { it.getJSONObject("params").string("text") }
    private fun tickIn(id: String, tick: String) = hasTestTag("send-tick:$tick") and hasAnyAncestor(hasTestTag("queued-message:$id"))

    @Test fun aQueuedMessageIsInTheBoxNotTheChatWhileTheReplyRuns() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.setBusySendAction("queue") }
        compose.waitUntil(15_000) { vm.preferences.value.busySendAction == "queue" }
        startTurn(vm, fixture)
        // The empty composer says what Send will do while a reply runs; once a message is typed, the pill says it too.
        compose.onNodeWithText("Queue a message…").assertExists()
        compose.onAllNodesWithText("Correct the response...").assertCountEquals(0)
        compose.onNodeWithTag("message-input").performTextInput("Wait for the reply")
        compose.onNodeWithTag("composer-queue-caption").assertTextEquals("Queue")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.state == "queued" && vm.composer.value.text.isBlank() }
        val id = vm.chat.value.queued.single().id
        // On screen: in the box, once, and not a chat bubble.
        compose.onNodeWithTag("queue-box").assertIsDisplayed()
        compose.onNodeWithTag("queue-text:$id").assertTextEquals("Wait for the reply")
        // While a reply runs the button is Steer; a waiting message never says Retry.
        compose.onNodeWithTag("queue-steer:$id").assertIsDisplayed()
        compose.onAllNodesWithTag("queue-retry:$id").assertCountEquals(0)
        compose.onAllNodesWithTag("queue-send:$id").assertCountEquals(0)
        compose.onAllNodesWithTag("queued-message:$id").assertCountEquals(0)
        compose.onAllNodesWithText("Wait for the reply").assertCountEquals(1)
        // TalkBack: Edit and Remove name the message they belong to, as the attachment's remove button does.
        compose.onNodeWithTag("queue-edit:$id").assertContentDescriptionEquals("Edit Wait for the reply")
        compose.onNodeWithTag("queue-remove:$id").assertContentDescriptionEquals("Remove Wait for the reply")
        // Hermes received only the first message.
        assertEquals(listOf("Begin a synthetic turn"), submitted(fixture))
        // Not covered here: the reply ending and the message being sent by itself. On this legacy gateway (no admission) the finished turn cannot be
        // proven (1.1.34), so the message waits for a reopen (BusyTurnSendUiTest.identicalQueuedSendsKeepIdsAndDrainWhileANewerDraftExists). On the canonical
        // fixture the whole path (box, reply ends, sent once, in the chat with its ticks) is BusyTurnSendUiTest.aMessageQueuedWhileAReplyRunsIsSentOnceByItselfWhenTheReplyEndsAndMovesFromTheBoxIntoTheChat.
        // What happens once a box message is sent is also covered by retryOnANotSentMessageSendsItExactlyOnce_andItMovesIntoTheChat.
    }

    @Test fun theSteerButtonSendsTheQueuedMessageNowAsASteer_andTheChatMarksIt() = withFixture { vm, fixture ->
        fixture.steerStatus = "queued"
        compose.runOnIdle { vm.setBusySendAction("queue") }
        compose.waitUntil(15_000) { vm.preferences.value.busySendAction == "queue" }
        startTurn(vm, fixture)
        fixture.emitNativeEvent("message.delta", json("text" to "Existing live output"))
        compose.waitUntil(15_000) { vm.chat.value.liveText == "Existing live output" }
        typeAndSend("Please focus on cost")
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.state == "queued" }
        val id = vm.chat.value.queued.single().id
        compose.onNodeWithTag("queue-steer:$id").assertIsDisplayed()
        assertTrue("nothing was steered yet", fixture.requests.none { it.string("method") == "session.steer" })
        fixture.holdSteerAcknowledgement = true
        compose.onNodeWithTag("queue-steer:$id").performClick()
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "session.steer" } }
        val steer = fixture.requests.single { it.string("method") == "session.steer" }.getJSONObject("params")
        assertEquals("Please focus on cost", steer.string("text")); assertEquals("runtime-chat-a", steer.string("session_id"))
        // It left the box and is in the chat at once: Steer marker, one grey tick until Hermes acknowledges.
        compose.onAllNodesWithTag("queue-row:$id").assertCountEquals(0)
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:$id"))
        compose.onNode(hasTestTag("steer-marker:$id") and hasAnyAncestor(hasTestTag("queued-message:$id")), useUnmergedTree = true).assertExists()
        compose.onNode(tickIn(id, "pending"), useUnmergedTree = true).assertExists()
        fixture.releasePromptAcknowledgement()
        compose.waitUntil(15_000) { vm.chat.value.queued.single().state == "accepted" }
        // Hermes has it and the reply is streaming now: two blue ticks.
        compose.onNode(tickIn(id, "answered"), useUnmergedTree = true).assertExists()
        assertEquals("sent once, as a steer", 1, fixture.requests.count { it.string("method") == "session.steer" })
        assertEquals("not also queued as a second prompt", listOf("Begin a synthetic turn"), submitted(fixture))
    }

    @Test fun steerModeSendsAtOnceWithTheMarker_andThereIsNoBox() = withFixture { vm, fixture ->
        fixture.steerStatus = "queued"
        startTurn(vm, fixture)
        fixture.emitNativeEvent("message.delta", json("text" to "Existing live output"))
        compose.waitUntil(15_000) { vm.chat.value.liveText == "Existing live output" }
        compose.onNodeWithText("Change the reply…").assertExists()
        compose.onNodeWithTag("message-input").performTextInput("Be concise")
        compose.onNodeWithTag("composer-queue-caption").assertTextEquals("Send now")
        compose.onNodeWithTag("message-send").assertContentDescriptionEquals("Send now").performClick()
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "session.steer" } }
        assertEquals("Be concise", fixture.requests.single { it.string("method") == "session.steer" }.getJSONObject("params").string("text"))
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.kind == "steer" }
        val id = vm.chat.value.queued.single().id
        compose.onAllNodesWithTag("queue-box").assertCountEquals(0)
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:$id"))
        compose.onNode(hasTestTag("steer-marker:$id") and hasAnyAncestor(hasTestTag("queued-message:$id")), useUnmergedTree = true).assertExists()
    }

    @Test fun editPutsTheWordsBackInTheComposer_andRemoveDropsTheMessage_neitherSendsAnything() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.setBusySendAction("queue") }
        compose.waitUntil(15_000) { vm.preferences.value.busySendAction == "queue" }
        startTurn(vm, fixture)
        for (text in listOf("Edit this one", "Remove this one")) {
            typeAndSend(text)
            compose.waitUntil(15_000) { vm.chat.value.queued.any { it.text == text } && vm.composer.value.text.isBlank() }
        }
        // Oldest first, two rows in one box.
        val first = vm.chat.value.queued.first { it.text == "Edit this one" }.id
        val second = vm.chat.value.queued.first { it.text == "Remove this one" }.id
        assertTrue(compose.onNodeWithTag("queue-row:$first").fetchSemanticsNode().boundsInRoot.top < compose.onNodeWithTag("queue-row:$second").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithTag("queue-edit:$first").performClick()
        compose.waitUntil(15_000) { vm.composer.value.text == "Edit this one" && vm.chat.value.queued.size == 1 }
        compose.onAllNodesWithTag("queue-row:$first").assertCountEquals(0)
        compose.runOnIdle { vm.composer(androidx.compose.ui.text.input.TextFieldValue()) }
        compose.onNodeWithTag("queue-remove:$second").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.isEmpty() }
        compose.onAllNodesWithTag("queue-box").assertCountEquals(0)
        assertEquals("neither action sent anything", listOf("Begin a synthetic turn"), submitted(fixture))
    }

    @Test fun aDoubleTapOnSteerSteersOnlyOnce() = withFixture { vm, fixture ->
        fixture.steerStatus = "queued"
        compose.runOnIdle { vm.setBusySendAction("queue") }
        compose.waitUntil(15_000) { vm.preferences.value.busySendAction == "queue" }
        startTurn(vm, fixture)
        fixture.emitNativeEvent("message.delta", json("text" to "Existing live output"))
        compose.waitUntil(15_000) { vm.chat.value.liveText == "Existing live output" }
        typeAndSend("Please focus on cost")
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.state == "queued" }
        val id = vm.chat.value.queued.single().id
        fixture.holdSteerAcknowledgement = true
        // Two taps in one gesture: the second reaches the button before the first has changed the screen.
        compose.onNodeWithTag("queue-steer:$id").performTouchInput { click(); click() }
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "session.steer" } }
        Thread.sleep(2_000)
        fixture.releasePromptAcknowledgement()
        compose.waitForIdle()
        assertEquals("one tap pair, one steer", 1, fixture.requests.count { it.string("method") == "session.steer" })
    }

    @Test fun twoSteerQueuedCallsInARowSteerOnce() = withFixture { vm, fixture ->
        fixture.steerStatus = "queued"
        compose.runOnIdle { vm.setBusySendAction("queue") }
        compose.waitUntil(15_000) { vm.preferences.value.busySendAction == "queue" }
        startTurn(vm, fixture)
        fixture.emitNativeEvent("message.delta", json("text" to "Existing live output"))
        compose.waitUntil(15_000) { vm.chat.value.liveText == "Existing live output" }
        typeAndSend("Please focus on cost")
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.state == "queued" }
        val id = vm.chat.value.queued.single().id
        fixture.holdSteerAcknowledgement = true
        // Both calls start before the screen state changes: the second still sees the row waiting.
        compose.runOnUiThread { vm.steerQueued(id); vm.steerQueued(id) }
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "session.steer" } }
        Thread.sleep(2_000)
        fixture.releasePromptAcknowledgement()
        compose.waitForIdle()
        assertEquals("the same steer is never posted twice", 1, fixture.requests.count { it.string("method") == "session.steer" })
        assertEquals(1, vm.chat.value.queued.count { it.id == id })
    }

    @Test fun aTapOnAHeldBubbleOpensItsMenuLikeALongPress() = withFixture { vm, _ ->
        val now = System.currentTimeMillis()
        val unknown = QueuedMessage("Synthetic message of unknown outcome", state = "unknown", admissionId = "admission-synthetic", createdAt = now - 20_000)
        val saved = QueuedMessage("Synthetic message Hermes saved", state = "sent", rowId = "", createdAt = now - 10_000)
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(queued = listOf(unknown, saved)) }
        runBlocking { vm.checkpointNow() }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-bubble:${unknown.id}"))
        // A bubble with nothing to offer opens nothing on a tap.
        compose.onNodeWithTag("queued-bubble:${saved.id}").performClick()
        compose.onAllNodesWithTag("send-check").assertCountEquals(0)
        // A tap opens the same menu a long-press opens.
        compose.onNodeWithTag("queued-bubble:${unknown.id}").performClick()
        compose.onNodeWithTag("send-check").assertIsDisplayed()
        compose.onNodeWithTag("send-resend").assertIsDisplayed()
        compose.onNodeWithTag("send-dismiss").assertIsDisplayed()
    }

    @Test fun sendInTheBoxSaysWhatBlocksItWhenAnEarlierMessageIsNotConfirmed() = withFixture { vm, fixture ->
        val now = System.currentTimeMillis()
        val earlier = QueuedMessage("Synthetic earlier message", state = "unknown", admissionId = "admission-synthetic", createdAt = now - 20_000)
        val waiting = QueuedMessage("Synthetic waiting message", createdAt = now - 10_000)
        showChat(vm, emptyList(), listOf(earlier, waiting))
        compose.onNodeWithTag("queue-send:${waiting.id}").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { vm.ui.value.notice.isNotBlank() }
        assertEquals("Tap your message to check it.", vm.ui.value.notice)
        assertTrue("nothing was sent", fixture.requests.none { it.string("method") == "prompt.submit" })
    }

    /**
     * The review's exact case on a screen: the chat is in use from another client, Hermes answered this phone's send "queued" behind that turn, and that
     * turn's reply streams. This message gets two grey ticks and never blue; a message whose own admission started is answered by the reply that streams.
     */
    @Test fun aMessageQueuedBehindAnotherTurnKeepsTwoGreyTicksWhileThatReplyStreams() = withFixture { vm, _ ->
        val now = System.currentTimeMillis()
        val rows = listOf(
            Message("1", "user", "Which flights go to Lisbon on Friday?", sessionId = "chat-a", recordedAt = now - 300_000),
            Message("2", "assistant", "Three options: 07:10, 11:45 and 18:20.", sessionId = "chat-a", recordedAt = now - 290_000),
        )
        val behind = QueuedMessage("Book the 11:45 instead", state = "accepted", admissionId = "admission-behind", admissionStatus = "queued", createdAt = now - 30_000, afterRowId = "2")
        val running = QueuedMessage("Also compare hotels near the airport", state = "accepted", admissionId = "admission-running", admissionStatus = "started", createdAt = now - 20_000, afterRowId = "2")
        showChat(vm, rows, listOf(behind, running))
        // Another turn's reply streams now.
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(streamId = "runtime-chat-a", liveText = "Another client's reply is streaming…") }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:${running.id}"))
        compose.onNode(tickIn(behind.id, "delivered"), useUnmergedTree = true).assertExists()
        compose.onAllNodes(tickIn(behind.id, "answered"), useUnmergedTree = true).assertCountEquals(0)
        compose.onNode(tickIn(running.id, "answered"), useUnmergedTree = true).assertExists()
    }

    /**
     * The commonest path, end to end with Hermes' real sequence: Hermes answers this send "queued" (it does to every fresh send), then starts the turn
     * and streams its reply. The ticks turn blue while the reply is still streaming, not when it ends.
     */
    @Test fun aSendOnAnIdleChatTurnsBlueWhileItsReplyStreams() = withFixture(canonical = true) { vm, fixture ->
        openWatchedChat(vm)
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        typeAndSend("Begin a synthetic turn")
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.let { it.state == "accepted" && it.admissionId.isNotBlank() } == true }
        val id = vm.chat.value.queued.single().id
        assertEquals("Hermes answered the send as queued, as it does for every fresh send", listOf("queued"), fixture.submitAnswers.toList())
        compose.waitUntil(15_000) { vm.chat.value.streamId == "chat-a" && fixture.liveCollectorSubscriptions.get() > 0 }
        fixture.emitNativeEvent("message.delta", json("text" to "The reply is still streaming"))
        compose.waitUntil(15_000) { vm.chat.value.liveText == "The reply is still streaming" }
        // The reply has not ended, and the ticks are blue.
        assertTrue(vm.chat.value.streamId.isNotBlank())
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.admissionStatus == "started" }
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:$id"))
        compose.onNode(tickIn(id, "answered"), useUnmergedTree = true).assertExists()
        compose.onAllNodes(tickIn(id, "delivered"), useUnmergedTree = true).assertCountEquals(0)
    }

    /** The other half: another client's turn runs, Hermes holds this send queued behind it. Grey while that reply streams, blue once Hermes starts THIS message. */
    @Test fun aSendBehindAnotherTurnStaysGreyUntilItsOwnTurnStarts() = withFixture(canonical = true) { vm, fixture ->
        openWatchedChat(vm)
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        fixture.nativeTurnStatus = "working"
        fixture.executingAdmission = "admission-other-client"
        fixture.queueNextCanonicalSubmission.set(true)
        typeAndSend("Book the 11:45 instead")
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.let { it.state == "accepted" && it.admissionId.isNotBlank() } == true }
        val sent = vm.chat.value.queued.single()
        compose.waitUntil(15_000) { vm.chat.value.streamId == "chat-a" && fixture.liveCollectorSubscriptions.get() > 0 }
        // The other client's reply streams: its frames name its own admission.
        fixture.emitNativeEvent("message.delta", json("text" to "Another client's reply is streaming"))
        compose.waitUntil(15_000) { vm.chat.value.liveText == "Another client's reply is streaming" }
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:${sent.id}"))
        compose.onNode(tickIn(sent.id, "delivered"), useUnmergedTree = true).assertExists()
        compose.onAllNodes(tickIn(sent.id, "answered"), useUnmergedTree = true).assertCountEquals(0)
        assertEquals("queued", vm.chat.value.queued.single().admissionStatus)
        // Hermes now claims this message: the frames name its admission.
        fixture.startCanonicalTurn(sent.admissionId, live = true)
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.admissionStatus == "started" }
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:${sent.id}"))
        compose.onNode(tickIn(sent.id, "answered"), useUnmergedTree = true).assertExists()
    }

    /**
     * R9 Nice 8: Hermes answers a fresh send "queued", claims the row afterwards and pushes the stamped `message.start` to the stream the phone has attached
     * (gateway/session_events.py publish -> fanout.write). The ticks are grey until that live frame, blue when it arrives, and the stamp is gone after the terminal frame.
     */
    @Test fun theStampedStartThatArrivesLiveAfterTheQueuedAnswerTurnsTheTicksBlue() = withFixture(canonical = true) { vm, fixture ->
        openWatchedChat(vm)
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        // Answered "queued"; Hermes shows the chat working as it takes the row, and the stamped start comes later, as a live frame on the stream the phone has attached.
        fixture.nativeTurnStatus = "working"
        fixture.queueNextCanonicalSubmission.set(true)
        typeAndSend("Begin a synthetic turn")
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.let { it.state == "accepted" && it.admissionId.isNotBlank() } == true }
        val sent = vm.chat.value.queued.single()
        assertEquals(listOf("queued"), fixture.submitAnswers.toList())
        compose.waitUntil(15_000) { vm.chat.value.streamId == "chat-a" && fixture.liveCollectorSubscriptions.get() > 0 }
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:${sent.id}"))
        compose.onNode(tickIn(sent.id, "delivered"), useUnmergedTree = true).assertExists()
        compose.onAllNodes(tickIn(sent.id, "answered"), useUnmergedTree = true).assertCountEquals(0)
        assertNull("Hermes has not claimed the row yet", fixture.executingAdmission)
        // The claim: the stamped start goes to the attached stream as a live frame.
        fixture.startCanonicalTurn(sent.admissionId, live = true)
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.admissionStatus == "started" }
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:${sent.id}"))
        compose.onNode(tickIn(sent.id, "answered"), useUnmergedTree = true).assertExists()
        // The reply ends: the terminal frame is the last one that carries the stamp.
        fixture.emitNativeEvent("message.delta", json("text" to "The reply is streaming"))
        compose.waitUntil(15_000) { vm.chat.value.liveText == "The reply is streaming" }
        fixture.nativeTurnStatus = "idle"
        fixture.emitNativeEvent("message.complete", json("text" to "The reply is streaming"))
        assertNull("The stamp is cleared right after the terminal frame", fixture.executingAdmission)
    }

    @Test fun aMessageHermesDidNotTakeGoesBackToTheBoxAtOnce_withNoGapAndNoCopy() = withFixture(canonical = true) { vm, fixture ->
        val item = QueuedMessage("Synthetic failing message", state = "accepted", admissionId = "admission-synthetic")
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(queued = listOf(item)) }
        runBlocking { vm.checkpointNow() }
        // As sent: a chat bubble with two grey ticks, and nothing in the box.
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:${item.id}"))
        compose.onNode(tickIn(item.id, "delivered"), useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("queue-box").assertCountEquals(0)
        // Record every state the chat passes through while the receipt check settles it as failed.
        val seen = CopyOnWriteArrayList<ChatState>()
        val recorder = CoroutineScope(Dispatchers.Default).launch { vm.chat.collect { seen += it } }
        // Hermes cancelled the admission before it ever started: the only receipt that proves the message was never taken.
        fixture.admissionReceiptStatus = "terminal"; fixture.admissionReceiptOutcome = "cancelled"
        compose.onNodeWithTag("queued-bubble:${item.id}").performTouchInput { longClick() }
        compose.onNodeWithText("Check if it arrived").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.let { it.state == "settled" && it.outcome == "cancelled" } == true }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("queue-not-sent:${item.id}").fetchSemanticsNodes().isNotEmpty() }
        recorder.cancel()
        // On screen: gone from the chat, in the box as Not sent, with Retry, Edit and Remove.
        compose.onAllNodesWithTag("queued-message:${item.id}").assertCountEquals(0)
        compose.onAllNodesWithText("Synthetic failing message").assertCountEquals(1)
        compose.onNodeWithTag("queue-not-sent:${item.id}").assertIsDisplayed()
        listOf("queue-retry", "queue-edit", "queue-remove").forEach { compose.onNodeWithTag("$it:${item.id}").assertIsDisplayed() }
        // In every state the chat held, the message was in exactly one place: never in neither, never in both.
        for (state in seen.filter { it.queued.isNotEmpty() }) {
            val inBox = queueBoxItems(state.queued).any { it.id == item.id }
            val inChat = chatTimelineMessages(state.messages, state.queued).any { it.id == "queued:${item.id}" }
            assertNotEquals("in the box: $inBox, in the chat: $inChat", inBox, inChat)
        }
        assertTrue("nothing was sent again by itself", fixture.requests.none { it.string("method") == "prompt.submit" })
    }

    @Test fun aMessageWhoseTurnFailedStaysInTheChatWithTwoBlueTicks_andNothingOffersToSendItAgain() = withFixture(canonical = true) { vm, fixture ->
        val item = QueuedMessage("Synthetic message whose turn failed", state = "accepted", admissionId = "admission-synthetic")
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(queued = listOf(item)) }
        runBlocking { vm.checkpointNow() }
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:${item.id}"))
        compose.onNode(tickIn(item.id, "delivered"), useUnmergedTree = true).assertExists()
        // Hermes started this admission and its turn failed: the message reached the agent.
        fixture.admissionReceiptStatus = "terminal"; fixture.admissionReceiptOutcome = "failed"
        compose.onNodeWithTag("queued-bubble:${item.id}").performTouchInput { longClick() }
        compose.onNodeWithText("Check if it arrived").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.let { it.state == "settled" && it.outcome == "failed" } == true }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:${item.id}"))
        // Hermes settles a failed turn only for one it started: the message was started, so the ticks are blue though no reply follows.
        compose.onNode(tickIn(item.id, "answered"), useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Synthetic message whose turn failed").assertCountEquals(1)
        compose.onAllNodesWithTag("queue-box").assertCountEquals(0)
        compose.onAllNodesWithTag("queue-not-sent:${item.id}").assertCountEquals(0)
        compose.onAllNodesWithTag("queue-retry:${item.id}").assertCountEquals(0)
        compose.onAllNodesWithTag("send-resend").assertCountEquals(0)
        assertTrue("nothing was sent again", fixture.requests.none { it.string("method") == "prompt.submit" })
    }

    private fun showChat(vm: BeaconViewModel, rows: List<Message>, queued: List<QueuedMessage>) {
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = rows, queued = queued) }
        compose.waitForIdle()
    }

    private fun captureShot(name: String) {
        compose.waitForIdle()
        val directory = java.io.File(compose.activity.filesDir, "native-experience/queue-box").apply { mkdirs() }
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** Screenshots for the owner's review, synthetic text only: the three ticks and a Steer bubble, the box with two items, and Not sent in the box. */
    @Test fun screenshotsOfTheTicksTheSteerMarkerAndTheBox() = withFixture { vm, _ ->
        val now = System.currentTimeMillis()
        val rows = listOf(
            Message("1", "user", "Which flights go to Lisbon on Friday?", sessionId = "chat-a", recordedAt = now - 300_000),
            Message("2", "assistant", "Three options: 07:10, 11:45 and 18:20.", sessionId = "chat-a", recordedAt = now - 290_000),
            Message("3", "user", "Thanks, check the prices for the morning one.", sessionId = "chat-a", recordedAt = now - 60_000),
        )
        val unconfirmed = QueuedMessage("Book the 11:45 instead", state = "unknown", createdAt = now - 30_000, afterRowId = "3")
        val steer = QueuedMessage("Only direct flights, please", state = "accepted", kind = "steer", createdAt = now - 20_000, afterRowId = "3")
        val waiting = listOf(
            QueuedMessage("Also compare hotels near the airport", createdAt = now - 10_000),
            QueuedMessage("Remind me to pack a charger", createdAt = now - 5_000),
        )
        fun show(queued: List<QueuedMessage>) = showChat(vm, rows, queued)
        fun capture(name: String) = captureShot(name)
        show(listOf(unconfirmed, steer))
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:${steer.id}"))
        compose.onNode(hasTestTag("send-tick:answered") and hasAnyAncestor(hasTestTag("message-row:1")), useUnmergedTree = true).assertExists()
        compose.onNode(hasTestTag("send-tick:delivered") and hasAnyAncestor(hasTestTag("message-row:3")), useUnmergedTree = true).assertExists()
        compose.onNode(tickIn(unconfirmed.id, "pending"), useUnmergedTree = true).assertExists()
        compose.onNode(hasTestTag("steer-marker:${steer.id}") and hasAnyAncestor(hasTestTag("queued-message:${steer.id}")), useUnmergedTree = true).assertExists()
        capture("1-ticks-and-steer")
        show(waiting)
        compose.onNodeWithTag("queue-box").assertIsDisplayed()
        compose.onNodeWithTag("queue-row:${waiting[0].id}").assertIsDisplayed()
        compose.onNodeWithTag("queue-row:${waiting[1].id}").assertIsDisplayed()
        // No reply is running and nothing failed: a waiting message says Send, never Retry.
        waiting.forEach { compose.onNodeWithTag("queue-send:${it.id}").assertIsDisplayed(); compose.onAllNodesWithTag("queue-retry:${it.id}").assertCountEquals(0) }
        capture("2-box-with-two-items")
        // The same box while a reply runs: Steer is offered on each plain-text message.
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(streamId = "runtime-chat-a", liveText = "Checking prices for the morning flight…") }
        compose.waitForIdle()
        compose.onNodeWithTag("queue-steer:${waiting[0].id}").assertIsDisplayed()
        compose.onNodeWithTag("queue-steer:${waiting[1].id}").assertIsDisplayed()
        capture("2b-box-while-a-reply-runs")
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(streamId = "", liveText = "") }
        compose.waitForIdle()
        show(listOf(QueuedMessage("Send the itinerary to my team", state = "settled", outcome = "cancelled", createdAt = now - 15_000), waiting[0]))
        val notSent = vm.chat.value.queued.first()
        compose.onNodeWithTag("queue-not-sent:${notSent.id}").assertIsDisplayed()
        // Retry only on the message that was not sent; the waiting one still says Send.
        compose.onNodeWithTag("queue-retry:${notSent.id}").assertIsDisplayed()
        compose.onAllNodesWithTag("queue-send:${notSent.id}").assertCountEquals(0)
        compose.onNodeWithTag("queue-send:${waiting[0].id}").assertIsDisplayed()
        compose.onAllNodesWithTag("queue-retry:${waiting[0].id}").assertCountEquals(0)
        capture("3-not-sent-in-the-box")
    }

    /** An own bubble the phone still tracks has no dots button: a long-press opens the menu, and TalkBack gets a long-click label and one action per item. */
    @Test fun aHeldBubbleHasNoDotsButAndALongPressOpensItsMenu_withTalkBackActions() = withFixture { vm, _ ->
        val now = System.currentTimeMillis()
        val unknown = QueuedMessage("Synthetic message of unknown outcome", state = "unknown", admissionId = "admission-synthetic", createdAt = now - 20_000)
        val settled = QueuedMessage("Synthetic message Hermes saved", state = "sent", rowId = "", createdAt = now - 10_000)
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(queued = listOf(unknown, settled)) }
        runBlocking { vm.checkpointNow() }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-bubble:${unknown.id}"))
        // No dots button, for either bubble.
        listOf(unknown, settled).forEach { compose.onAllNodesWithTag("queued-actions:${it.id}").assertCountEquals(0) }
        // TalkBack: a long-click label naming the menu, and one custom action per item, in the menu's order.
        val config = compose.onNodeWithTag("queued-bubble:${unknown.id}").fetchSemanticsNode().config
        assertEquals("Message actions", config[androidx.compose.ui.semantics.SemanticsActions.OnLongClick].label)
        assertEquals(listOf("Check if it arrived", "Resend", "Remove this copy"), config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label })
        // A bubble with nothing to offer has no gesture and no accessibility actions.
        val plain = compose.onNodeWithTag("queued-bubble:${settled.id}").fetchSemanticsNode().config
        assertFalse(plain.contains(androidx.compose.ui.semantics.SemanticsActions.CustomActions))
        assertFalse(plain.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick))
        compose.onNodeWithTag("queued-bubble:${settled.id}").performTouchInput { longClick() }
        compose.onAllNodesWithTag("send-resend").assertCountEquals(0)
        // A long-press opens the same menu, with the same item tags.
        compose.onNodeWithTag("queued-bubble:${unknown.id}").performTouchInput { longClick() }
        compose.onNodeWithTag("send-check").assertIsDisplayed()
        compose.onNodeWithTag("send-resend").assertIsDisplayed()
        compose.onNodeWithTag("send-dismiss").assertIsDisplayed()
        // The item works: "Remove this copy" drops the held message.
        compose.onNodeWithTag("send-dismiss").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.none { it.id == unknown.id } }
        compose.onAllNodesWithTag("queued-bubble:${unknown.id}").assertCountEquals(0)
    }

    /** The same screen in the light theme: blue ticks on a light bubble, and the box with a Not sent row and a waiting one. */
    @Test fun screenshotInTheLightTheme() = withFixture(light = true) { vm, _ ->
        val now = System.currentTimeMillis()
        val rows = listOf(
            Message("1", "user", "Which flights go to Lisbon on Friday?", sessionId = "chat-a", recordedAt = now - 300_000),
            Message("2", "assistant", "Three options: 07:10, 11:45 and 18:20.", sessionId = "chat-a", recordedAt = now - 290_000),
            Message("3", "user", "Thanks, check the prices for the morning one.", sessionId = "chat-a", recordedAt = now - 60_000),
        )
        val unconfirmed = QueuedMessage("Book the 11:45 instead", state = "unknown", createdAt = now - 30_000, afterRowId = "3")
        val steer = QueuedMessage("Only direct flights, please", state = "accepted", kind = "steer", createdAt = now - 20_000, afterRowId = "3")
        val notSent = QueuedMessage("Send the itinerary to my team", state = "settled", outcome = "cancelled", createdAt = now - 15_000)
        val waiting = QueuedMessage("Also compare hotels near the airport", createdAt = now - 10_000)
        showChat(vm, rows, listOf(unconfirmed, steer))
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:${steer.id}"))
        compose.onNode(hasTestTag("send-tick:answered") and hasAnyAncestor(hasTestTag("message-row:1")), useUnmergedTree = true).assertExists()
        compose.onNode(hasTestTag("send-tick:delivered") and hasAnyAncestor(hasTestTag("message-row:3")), useUnmergedTree = true).assertExists()
        compose.onNode(tickIn(unconfirmed.id, "pending"), useUnmergedTree = true).assertExists()
        compose.onNode(hasTestTag("steer-marker:${steer.id}") and hasAnyAncestor(hasTestTag("queued-message:${steer.id}")), useUnmergedTree = true).assertExists()
        // No dots button beside own bubbles: the menu opens by long-press.
        compose.onAllNodesWithTag("queued-actions:${unconfirmed.id}").assertCountEquals(0)
        compose.onAllNodesWithTag("unconfirmed-actions").assertCountEquals(0)
        captureShot("1-ticks-and-steer-light")
        showChat(vm, rows, listOf(unconfirmed, steer, notSent, waiting))
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:${steer.id}"))
        compose.onNodeWithTag("queue-not-sent:${notSent.id}").assertIsDisplayed()
        compose.onNodeWithTag("queue-retry:${notSent.id}").assertIsDisplayed()
        compose.onNodeWithTag("queue-send:${waiting.id}").assertIsDisplayed()
        captureShot("4-light-ticks-and-box")
    }

    /** R7 screenshots (synthetic text, dark default skin): the unconfirmed bubble with its menu open after a tap. */
    @Test fun screenshotOfTheUnconfirmedBubbleMenuAfterATap() = withFixture { vm, fixture ->
        val scope = fixture.scope.session("chat-a")
        val receipt = PendingSend.create(scope, Draft("Book the 11:45 instead", 0, 0, emptyList()))
        @Suppress("UNCHECKED_CAST")
        val pending = BeaconViewModel::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(vm) as MutableMap<String, PendingSend>
        runBlocking { app.cache.write(scope, "pending", receipt.encode(), protected = true) }
        try {
            val now = System.currentTimeMillis()
            val rows = listOf(
                Message("1", "user", "Which flights go to Lisbon on Friday?", sessionId = "chat-a", recordedAt = now - 300_000),
                Message("2", "assistant", "Three options: 07:10, 11:45 and 18:20.", sessionId = "chat-a", recordedAt = now - 290_000),
            )
            compose.runOnIdle {
                pending[scope.key] = receipt
                flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = rows, uncertain = receipt.id)
            }
            compose.waitForIdle()
            compose.onNodeWithTag("unconfirmed-bubble").assertIsDisplayed().performClick()
            compose.onNodeWithTag("send-check").assertIsDisplayed()
            compose.onNodeWithTag("send-resend").assertIsDisplayed()
            compose.onNodeWithTag("send-dismiss").assertIsDisplayed()
            captureShot("r7-1-unconfirmed-menu-after-tap")
        } finally { runBlocking { app.cache.delete(scope, "pending") }; compose.runOnIdle { pending.remove(scope.key) } }
    }

    /** R7 screenshot: the five tick states in the dark Graphite skin, where the old blue was 2.98:1. */
    @Test fun screenshotOfTheTicksInTheDarkGraphiteSkin() = withFixture(skin = "graphite") { vm, _ ->
        val now = System.currentTimeMillis()
        val rows = listOf(
            Message("1", "user", "Which flights go to Lisbon on Friday?", sessionId = "chat-a", recordedAt = now - 300_000),
            Message("2", "assistant", "Three options: 07:10, 11:45 and 18:20.", sessionId = "chat-a", recordedAt = now - 290_000),
            Message("3", "user", "Thanks, check the prices for the morning one.", sessionId = "chat-a", recordedAt = now - 60_000),
        )
        val unconfirmed = QueuedMessage("Book the 11:45 instead", state = "unknown", createdAt = now - 30_000, afterRowId = "3")
        val steer = QueuedMessage("Only direct flights, please", state = "accepted", kind = "steer", createdAt = now - 20_000, afterRowId = "3")
        showChat(vm, rows, listOf(unconfirmed, steer))
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:${steer.id}"))
        compose.onNode(hasTestTag("send-tick:answered") and hasAnyAncestor(hasTestTag("message-row:1")), useUnmergedTree = true).assertExists()
        compose.onNode(hasTestTag("send-tick:delivered") and hasAnyAncestor(hasTestTag("message-row:3")), useUnmergedTree = true).assertExists()
        compose.onNode(tickIn(unconfirmed.id, "pending"), useUnmergedTree = true).assertExists()
        captureShot("r7-3-ticks-dark-graphite")
    }

    @Test fun retryOnANotSentMessageSendsItExactlyOnce_andItMovesIntoTheChat() = withFixture { vm, fixture ->
        openWatchedChat(vm)
        fixture.nativeTurnStatus = "idle"
        val item = QueuedMessage("Synthetic retried message", state = "settled", outcome = "cancelled")
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(queued = listOf(item)) }
        runBlocking { vm.checkpointNow() }
        compose.onNodeWithTag("queue-not-sent:${item.id}").assertIsDisplayed()
        compose.onAllNodesWithTag("queued-message:${item.id}").assertCountEquals(0)
        assertTrue(fixture.requests.none { it.string("method") == "prompt.submit" })
        compose.onNodeWithTag("queue-retry:${item.id}").performClick()
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "prompt.submit" } }
        val sent = fixture.requests.single { it.string("method") == "prompt.submit" }.getJSONObject("params")
        assertEquals("Synthetic retried message", sent.string("text"))
        assertNotEquals("a fresh submission, not the one that failed", item.submissionId, sent.string("submission_id"))
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.state in setOf("sent", "accepted") }
        compose.onAllNodesWithTag("queue-not-sent:${item.id}").assertCountEquals(0)
        compose.onAllNodesWithTag("queue-box").assertCountEquals(0)
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
    }
}
