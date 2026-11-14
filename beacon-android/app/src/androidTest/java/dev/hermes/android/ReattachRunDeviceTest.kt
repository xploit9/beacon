package dev.hermes.android

import android.app.Notification
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
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
import dev.hermes.android.push.receivePush
import dev.hermes.android.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Pins the 1.1.15 fixes that had no test (ponytail-1115a-check.md, finding 6): the cold reattach of a reply that was running when Beacon was killed,
 * and quiet working events with meaningful reply notices. The synthetic fixture and Android's own notifications only; no service, model or network.
 */
class ReattachRunDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val shown = mutableStateOf<BeaconViewModel?>(null)
    private val stores = mutableListOf<ViewModelStore>()
    private val clients = mutableListOf<BeaconClient>()

    // BeaconViewModel's RUN_MARKER is private. Its stored key is pinned here: a renamed key strands every marker already saved on a phone.
    private val marker = "running-turn"
    private val runtime = "runtime-chat-a"
    private val reply = "Finished while Beacon was gone"

    @Suppress("UNCHECKED_CAST") private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }
    private fun marked(fixture: NativeExperienceFixture, id: String) = runBlocking { app.cache.read(fixture.scope.session(id), marker) } != null
    private fun forgetChats(fixture: NativeExperienceFixture) = runBlocking {
        listOf("chat-a", "chat-b").forEach { id -> listOf(marker, "live:runtime-$id", "session", "draft", "pending").forEach { app.cache.delete(fixture.scope.session(id), it) } }
    }
    private fun await(vm: BeaconViewModel, fixture: NativeExperienceFixture, what: String, condition: () -> Boolean) {
        try { compose.waitUntil(15_000, condition) }
        catch (error: Throwable) {
            throw AssertionError("$what: stream=${vm.chat.value.streamId} status=${vm.chat.value.status} requests=${fixture.requests.takeLast(10).map { it.string("method") + " " + it.string("path") }}", error)
        }
    }
    /** Hermes was asked to resume this stored chat after [mark] requests: the one lazy resume a marked chat earns. */
    private fun resumed(fixture: NativeExperienceFixture, mark: Int, id: String) =
        fixture.requests.drop(mark).any { it.string("method") == "session.resume" && it.getJSONObject("params").string("session_id") == id }
    private fun promptSubmits(fixture: NativeExperienceFixture) = fixture.requests.count { it.string("method") == "prompt.submit" }

    private fun withFixture(block: (NativeExperienceFixture) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val originKey = stringPreferencesKey("origin")
        val prior = runBlocking { app.preferences.data.first() }
        runBlocking { app.preferences.edit { it[originKey] = "" } }
        // Motion follows the phone's "Remove animations" setting; still frames keep the live row deterministic.
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shellRun(command: String): String = shell.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        val priorAnimatorScale = shellRun("settings get global animator_duration_scale")
        shellRun("settings put global animator_duration_scale 0")
        val fixture = NativeExperienceFixture()
        runBlocking { app.auth.persist(fixture.client.auth) }
        // An earlier case may have left a marker or a saved frame for these fixture chats.
        forgetChats(fixture)
        compose.setContent {
            val current = shown.value
            if (current != null) key(current) {
                val settings by current.data.collectAsState()
                val prefs by current.preferences.collectAsState()
                CompositionLocalProvider(LocalBeaconSettings provides (settings["profile-settings"] ?: JSONObject()), LocalAssistantName provides prefs.assistantName) {
                    NativeLocalization("en") { BeaconTheme(dark = true) { NativeWindowLayout(compose.activity) { Box(Modifier.fillMaxSize()) { BeaconRoot(current) } } } }
                }
            }
        }
        try { block(fixture) } finally {
            compose.runOnUiThread { stores.forEach { it.clear() }; clients.forEach { it.closeNative() }; fixture.client.closeNative(); app.calls.ownerScope = null }
            runBlocking { app.preferences.updateData { prior }; app.auth.remove(fixture.scope.origin) }
            shellRun(if (priorAnimatorScale == "null" || priorAnimatorScale.isBlank()) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $priorAnimatorScale")
            forgetChats(fixture)
        }
    }

    /** A view model signed in to the fixture; with [showing] that chat is already open, as in a running app. */
    private fun newViewModel(store: ViewModelStore, fixture: NativeExperienceFixture, client: BeaconClient, showing: String?): BeaconViewModel {
        stores += store
        lateinit var vm: BeaconViewModel
        compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
        compose.runOnIdle {
            field(vm, "originGeneration", 100)
            field(vm, "client", client)
            field(vm, "cachedOwnerScope", fixture.scope)
            app.calls.ownerScope = fixture.scope
            flow<AppState>(vm, "_ui").value = AppState(origin = fixture.scope.origin, profile = fixture.scope.profile, authenticated = true, owner = fixture.scope.owner, tab = "Chat",
                connected = true, restoringSession = false, initialChatReady = true, bootstrap = json("voice" to json("available" to false)), sessions = fixture.sessions.keys.sorted().map(fixture::snapshot))
            if (showing != null) {
                field(vm, "sourceScope", fixture.scope.session(showing))
                // Opening stock history binds no runtime (as in NativeOperations.passiveHistory).
                val snapshot = fixture.snapshot(showing).apply { remove("runtime_session_id") }
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = showing, title = snapshot.string("title"), raw = snapshot,
                    messages = snapshot.rows("messages").mapIndexed { index, row -> Message.from(row, index, showing) })
            }
        }
        return vm
    }

    /** What the restarted app is: a new view model and a new client on the same fixture, with only the saved marker and history left from the first. */
    private fun restart(fixture: NativeExperienceFixture, showing: String? = null, then: (BeaconViewModel) -> Unit): BeaconViewModel {
        val client = fixture.clientFor(fixture.client.auth, app.auth).also(clients::add)
        val vm = newViewModel(ViewModelStore(), fixture, client, showing)
        compose.runOnIdle { shown.value = vm; then(vm) }
        return vm
    }

    /** Delivers a prompt in chat-a (which writes the marker), then drops the view model, its streams and its sockets as a killed process does. */
    private fun sendThenDie(fixture: NativeExperienceFixture) {
        fixture.persistTerminalMessages = true
        val store = ViewModelStore()
        val vm = newViewModel(store, fixture, fixture.client, "chat-a")
        compose.runOnIdle { vm.composer(TextFieldValue("Plan the trip while I am away.")); vm.send() }
        await(vm, fixture, "Prompt not delivered") { promptSubmits(fixture) == 1 && vm.chat.value.streamId == runtime }
        assertTrue("Delivering the prompt writes the run marker", marked(fixture, "chat-a"))
        compose.runOnUiThread { store.clear(); fixture.client.closeNative() }
    }

    private fun assertLiveStream(vm: BeaconViewModel, fixture: NativeExperienceFixture) {
        await(vm, fixture, "Reply not followed again") { vm.chat.value.streamId == runtime && compose.onAllNodesWithTag("message-stop").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("message-stop").assertIsDisplayed()
        compose.onNodeWithTag("message-activity-status", useUnmergedTree = true).assertTextEquals("Working")
        assertTrue("The marker stays while the reply runs", marked(fixture, "chat-a"))
        assertEquals("Following a reply never sends the prompt again", 1, promptSubmits(fixture))
    }

    @Test fun aReplyStillRunningWhenBeaconWasKilledComesBackWithItsStatusAndStop() = withFixture { fixture ->
        sendThenDie(fixture)
        fixture.nativeTurnStatus = "working"
        val mark = fixture.requests.size
        val vm = restart(fixture) { it.selectSession("chat-a") }
        assertLiveStream(vm, fixture)
        assertTrue("Opening the marked chat asked Hermes", resumed(fixture, mark, "chat-a"))
    }

    @Test fun aReplyThatEndedWhileBeaconWasGoneSettlesFromHistoryAndDropsTheMarker() = withFixture { fixture ->
        sendThenDie(fixture)
        fixture.nativeTurnStatus = "idle"
        fixture.retainNativeEvent(runtime, "message.complete", json("text" to reply))
        val mark = fixture.requests.size
        val vm = restart(fixture) { it.selectSession("chat-a") }
        await(vm, fixture, "Reply not settled") { !marked(fixture, "chat-a") && vm.chat.value.streamId.isBlank() && vm.chat.value.messages.any { it.text == reply } }
        assertTrue("Opening the marked chat asked Hermes", resumed(fixture, mark, "chat-a"))
        compose.onAllNodesWithTag("message-stop").assertCountEquals(0)
        compose.onAllNodesWithTag("live-status", useUnmergedTree = true).assertCountEquals(0)
        assertEquals("Settling reads history; it never sends the prompt again", 1, promptSubmits(fixture))
    }

    @Test fun queuedMessagesResumeFifoAfterColdSettleAndAuthoritativeIdle() = withFixture { fixture ->
        fixture.persistTerminalMessages = true
        val store = ViewModelStore()
        val first = newViewModel(store, fixture, fixture.client, "chat-a")
        compose.runOnIdle { first.composer(TextFieldValue("Plan the trip while I am away.")); first.send() }
        await(first, fixture, "Prompt not delivered") { promptSubmits(fixture) == 1 && first.chat.value.streamId == runtime }
        val queued = "Also check the weather there."
        val later = "Then check the forecast tomorrow."
        compose.runOnIdle { first.composer(TextFieldValue(queued)); first.queue() }
        await(first, fixture, "First queued message missing") { first.chat.value.queued.size == 1 }
        compose.runOnIdle { first.composer(TextFieldValue(later)); first.queue() }
        await(first, fixture, "Second queued message missing") { first.chat.value.queued.size == 2 }
        val ids = first.chat.value.queued.map { it.id }
        val newerDraft = "Keep my newer draft"
        compose.runOnIdle { first.composer(TextFieldValue(newerDraft)) }
        runBlocking { first.checkpointNow() }
        compose.runOnUiThread { store.clear(); fixture.client.closeNative() }
        fixture.nativeTurnStatus = "idle"
        fixture.retainNativeEvent(runtime, "message.complete", json("text" to reply))
        fixture.holdActiveListAcknowledgement = true
        val mark = fixture.requests.size
        val vm = restart(fixture) { it.selectSession("chat-a") }
        try {
            await(vm, fixture, "Completed history not restored") { vm.chat.value.messages.any { it.text == reply } }
            fun idleProbe() = fixture.requests.drop(mark).lastOrNull { it.string("method") == "session.active_list" &&
                it.getJSONObject("params").string("current_session_id") == runtime }?.getJSONObject("params")
            await(vm, fixture, "Scoped idle not requested") { idleProbe() != null }
            val probe = checkNotNull(idleProbe())
            assertEquals(runtime, probe.string("current_session_id"))
            assertEquals(fixture.scope.profile, probe.string("profile"))
            assertEquals("No FIFO send before the idle response", 1, promptSubmits(fixture))
        } finally { fixture.releaseCreateAcknowledgement() }
        await(vm, fixture, "First queued message not submitted") { promptSubmits(fixture) == 2 }
        await(vm, fixture, "First queued receipt not retained") { vm.chat.value.queued.firstOrNull()?.state == "sent" }
        val submissions = fixture.requests.filter { it.string("method") == "prompt.submit" }
        val next = submissions.last().getJSONObject("params")
        assertEquals(queued, next.string("text"))
        assertEquals(runtime, next.string("session_id"))
        assertEquals(fixture.scope.profile, next.string("profile"))
        assertTrue(next.optBoolean("queued"))
        assertEquals(ids, vm.chat.value.queued.map { it.id })
        val sent = vm.chat.value.queued.first()
        assertTrue("The sent item retains its exact row receipt", sent.rowId.isNotBlank())
        await(vm, fixture, "Acknowledged row not projected") { vm.chat.value.messages.any { it.role == "user" && it.id == sent.rowId } }
        assertEquals(sent.rowId, vm.chat.value.messages.single { it.role == "user" && it.text == queued }.id)
        assertEquals("The next FIFO item still waits", "queued", vm.chat.value.queued[1].state)
        assertEquals(newerDraft, vm.composer.value.text)
        assertEquals("No duplicate or second FIFO submission", 2, promptSubmits(fixture))
    }

    /**
     * R10 Should 2: a live frame belongs to the runtime id, not to one turn. An earlier turn that ended with an error left its frame (`terminal: true`) behind; the chat has no turn of its
     * own on this phone (a cold open, the run marker only), and a newer turn now runs in the same runtime (another client's, or a watch's). The frame's end is honoured only for the
     * turn this chat follows: here it must not end the attach. The newer reply streams, Working stays until the reply is over, and a follow-up queued meanwhile is not sent early.
     */
    @Test fun aLeftoverTerminalFrameOfAnEarlierTurnDoesNotEndTheAttachOfANewerTurn() = withFixture { fixture ->
        val scope = fixture.scope.session("chat-a")
        runBlocking {
            app.cache.write(scope, marker, "turn-of-another-client")
            app.cache.write(scope, "live:$runtime", json("cursor" to "", "text" to "", "reasoning" to "", "tools" to org.json.JSONArray(), "error" to "", "terminal" to true, "submission_id" to "earlier-turn").toString())
        }
        fixture.persistTerminalMessages = true
        fixture.nativeTurnStatus = "working"
        val mark = fixture.requests.size
        val vm = restart(fixture) { it.selectSession("chat-a") }
        await(vm, fixture, "The newer turn was not followed") { vm.chat.value.streamId == runtime && compose.onAllNodesWithTag("message-stop").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("Opening the marked chat asked Hermes", resumed(fixture, mark, "chat-a"))
        compose.onNodeWithTag("message-activity-status", useUnmergedTree = true).assertTextEquals("Working")
        await(vm, fixture, "The stream was not subscribed") { fixture.liveCollectorSubscriptions.get() > 0 }
        // A follow-up typed while the newer reply runs waits for it.
        val followUp = "Then check the forecast tomorrow."
        compose.runOnIdle { vm.composer(TextFieldValue(followUp)); vm.queue() }
        await(vm, fixture, "The follow-up was not queued") { vm.chat.value.queued.singleOrNull()?.text == followUp }
        fixture.emitNativeEvent("message.delta", json("text" to "The newer reply is streaming"))
        await(vm, fixture, "The newer reply did not stream") { vm.chat.value.liveText.contains("The newer reply is streaming") }
        Thread.sleep(2_000)
        assertEquals("Working stays while the reply runs", runtime, vm.chat.value.streamId)
        assertEquals("The follow-up is not released early", "queued", vm.chat.value.queued.single().state)
        assertEquals("Nothing was sent by this phone", 0, promptSubmits(fixture))
        // The reply ends: Working goes, and only then may the follow-up move.
        fixture.nativeTurnStatus = "idle"
        fixture.emitNativeEvent("message.complete", json("text" to "The newer reply is streaming"))
        await(vm, fixture, "Working did not end with the reply") { vm.chat.value.streamId.isBlank() }
        compose.onAllNodesWithTag("message-stop").assertCountEquals(0)
    }

    private val ownFailedTurn = "own-failed-turn"
    private val oldPartialReply = "Let me look up the fore"
    private val oldPartialReasoning = "Checking the weather for Berlin"

    /**
     * What a failed turn of this chat leaves behind: its saved frame (`terminal`, naming the chat's own submission id, with the partial reply and reasoning it had streamed and [oldError]),
     * and, when [marked], the run marker. The chat itself keeps that turn's `_display_submission_id` ([carryOwnSubmission]).
     */
    private fun seedOwnFailedTurn(fixture: NativeExperienceFixture, oldError: String, marked: Boolean) {
        val scope = fixture.scope.session("chat-a")
        runBlocking {
            if (marked) app.cache.write(scope, marker, "turn-of-another-client")
            app.cache.write(scope, "live:$runtime", json("cursor" to "", "text" to oldPartialReply, "reasoning" to oldPartialReasoning, "tools" to org.json.JSONArray(), "error" to oldError, "terminal" to true, "submission_id" to ownFailedTurn).toString())
        }
    }
    /** This phone sent that failed turn from this chat: the open chat still carries its submission id. */
    private fun carryOwnSubmission(vm: BeaconViewModel) {
        val chat = flow<ChatState>(vm, "_chat")
        chat.value = chat.value.copy(raw = chat.value.raw.copy().put("_display_submission_id", ownFailedTurn))
    }
    /**
     * The attach with no question to Hermes before it: how a watcher, a history read or a send reaches it (the cold-run path asks first and passes its answer on, so an idle or unreadable
     * answer never gets as far as the attach).
     */
    private fun attachWithoutAskingFirst(vm: BeaconViewModel, fixture: NativeExperienceFixture) {
        val scope = fixture.scope.session("chat-a")
        val silence = BeaconViewModel::class.java.getDeclaredField("streamSilenceMs").apply { isAccessible = true }.getLong(vm)
        val attach = BeaconViewModel::class.java.getDeclaredMethod("attachStream", BeaconClient::class.java, scope::class.java, String::class.java, java.lang.Boolean.TYPE, java.lang.Long.TYPE).apply { isAccessible = true }
        compose.runOnIdle { check(attach.invoke(vm, checkNotNull(vm.client).detached(), scope, runtime, false, silence) as Boolean) { "The attach did not start" } }
    }
    /** The chat shows Working for [runtime] first, so a follow-up queued now has a reply to wait for and is not sent at once. */
    private fun queuedFollowUp(vm: BeaconViewModel, fixture: NativeExperienceFixture): String {
        val followUp = "Then check the forecast tomorrow."
        compose.runOnIdle { flow<ChatState>(vm, "_chat").let { it.value = it.value.copy(streamId = runtime) }; vm.composer(TextFieldValue(followUp)); vm.queue() }
        await(vm, fixture, "The follow-up was not queued") { vm.chat.value.queued.singleOrNull()?.text == followUp }
        return followUp
    }

    /**
     * R11 Should 1: the same leftover frame, but of this chat's OWN failed turn. The chat keeps the submission id of that turn (`_display_submission_id`) and the frame names it, so the frame
     * alone cannot tell it from the end of the turn being followed. Hermes decides: it says a newer turn (another client's, a watch's) is running, so the attach follows it live, Working
     * stays until the reply is over, and a follow-up queued meanwhile is not released early. [oldError] is what the failed turn left in its frame ("" or its error line). R12 Should 1: the
     * failed turn's partial reply and reasoning, saved in the same frame, are not restored into the newer turn either; only the frame's cursor is kept.
     */
    private fun followsANewerTurnAfterTheChatsOwnFailedOne(fixture: NativeExperienceFixture, oldError: String): BeaconViewModel {
        seedOwnFailedTurn(fixture, oldError, marked = true)
        fixture.persistTerminalMessages = true
        fixture.nativeTurnStatus = "working"
        val vm = restart(fixture, showing = "chat-a") {
            carryOwnSubmission(it)
            it.foreground(false); it.foreground(true)
        }
        await(vm, fixture, "The newer turn was not followed") { vm.chat.value.streamId == runtime && compose.onAllNodesWithTag("message-stop").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("message-activity-status", useUnmergedTree = true).assertTextEquals("Working")
        await(vm, fixture, "The stream was not subscribed") { fixture.liveCollectorSubscriptions.get() > 0 }
        assertEquals("The failed turn's partial reply is not put into the newer turn", "", vm.chat.value.liveText)
        assertEquals("Nor its reasoning", "", vm.chat.value.liveReasoning)
        // A follow-up typed while the newer reply runs waits for it.
        val followUp = "Then check the forecast tomorrow."
        compose.runOnIdle { vm.composer(TextFieldValue(followUp)); vm.queue() }
        await(vm, fixture, "The follow-up was not queued") { vm.chat.value.queued.singleOrNull()?.text == followUp }
        fixture.emitNativeEvent("message.delta", json("text" to "The newer reply is streaming"))
        await(vm, fixture, "The newer reply did not stream") { vm.chat.value.liveText.contains("The newer reply is streaming") }
        assertEquals("The newer reply is shown alone, not after the failed one's words", "The newer reply is streaming", vm.chat.value.liveText)
        Thread.sleep(2_000)
        assertEquals("Working stays while the reply runs", runtime, vm.chat.value.streamId)
        assertEquals("The follow-up is not released early", "queued", vm.chat.value.queued.single().state)
        assertEquals("Nothing was sent by this phone", 0, promptSubmits(fixture))
        assertEquals("The failed turn's old error is not shown for the newer turn", "", vm.ui.value.error)
        // The reply ends: Working goes, and only then may the follow-up move.
        fixture.nativeTurnStatus = "idle"
        fixture.emitNativeEvent("message.complete", json("text" to "The newer reply is streaming"))
        await(vm, fixture, "Working did not end with the reply") { vm.chat.value.streamId.isBlank() }
        compose.onAllNodesWithTag("message-stop").assertCountEquals(0)
        assertEquals("The old error does not come back when the newer reply ends", "", vm.ui.value.error)
        return vm
    }

    @Test fun aLeftoverTerminalFrameOfTheChatsOwnFailedTurnDoesNotEndTheAttachOfANewerTurn() = withFixture { fixture ->
        followsANewerTurnAfterTheChatsOwnFailedOne(fixture, oldError = "")
    }

    @Test fun theOldErrorOfTheChatsOwnFailedTurnIsNotShownWhileANewerTurnIsFollowed() = withFixture { fixture ->
        followsANewerTurnAfterTheChatsOwnFailedOne(fixture, oldError = "Interrupted")
    }

    /**
     * R12 Should 3, the idle branch: Hermes says the turn is over, so the saved frame of the chat's own failed turn does end the attach, as it always did. Working clears at once, no stream
     * is subscribed, the old error shows, and a follow-up queued before stays queued (an error ending does not release it).
     */
    @Test fun aTerminalFrameOfTheChatsOwnFailedTurnStillEndsTheAttachWhenHermesSaysIdle() = withFixture { fixture ->
        seedOwnFailedTurn(fixture, oldError = "Interrupted", marked = false)
        fixture.persistTerminalMessages = true
        fixture.nativeTurnStatus = "idle"
        val vm = restart(fixture, showing = "chat-a") { carryOwnSubmission(it) }
        val followUp = queuedFollowUp(vm, fixture)
        val subscriptions = fixture.liveCollectorSubscriptions.get()
        attachWithoutAskingFirst(vm, fixture)
        await(vm, fixture, "Working did not clear") { vm.chat.value.streamId.isBlank() && fixture.nativeStatusProbes.isNotEmpty() }
        compose.onAllNodesWithTag("message-stop").assertCountEquals(0)
        assertEquals("No stream is followed for a turn that is over", subscriptions, fixture.liveCollectorSubscriptions.get())
        assertEquals("The failed turn's error shows", "Interrupted", vm.ui.value.error)
        Thread.sleep(2_000)
        assertEquals("The follow-up stays queued", followUp, vm.chat.value.queued.single().text)
        assertEquals("queued", vm.chat.value.queued.single().state)
        assertEquals("Nothing was sent by this phone", 0, promptSubmits(fixture))
    }

    /**
     * R12 Should 3, the unreadable branch: Hermes cannot be asked, so nothing says the turn is over. The turn is followed, Working stays, the old error is kept (it may be this very turn's),
     * and the follow-up waits. When the reply ends, Working goes.
     */
    @Test fun aTerminalFrameOfTheChatsOwnFailedTurnIsFollowedWhenHermesCannotBeAsked() = withFixture { fixture ->
        seedOwnFailedTurn(fixture, oldError = "Interrupted", marked = false)
        fixture.persistTerminalMessages = true
        fixture.nativeTurnStatus = "working"
        fixture.nativeStatusFailure = true
        val vm = restart(fixture, showing = "chat-a") { carryOwnSubmission(it) }
        val followUp = queuedFollowUp(vm, fixture)
        attachWithoutAskingFirst(vm, fixture)
        await(vm, fixture, "The stream was not subscribed") { fixture.liveCollectorSubscriptions.get() > 0 }
        assertTrue("Hermes was asked and could not answer", fixture.nativeStatusProbes.contains("failed"))
        assertEquals("Working stays", runtime, vm.chat.value.streamId)
        assertEquals("The old error is kept", "Interrupted", vm.ui.value.error)
        assertEquals("The follow-up waits", followUp, vm.chat.value.queued.single().text)
        assertEquals("queued", vm.chat.value.queued.single().state)
        assertEquals("Nothing was sent by this phone", 0, promptSubmits(fixture))
        fixture.nativeStatusFailure = false
        fixture.nativeTurnStatus = "idle"
        fixture.emitNativeEvent("message.complete", json("text" to "The reply is over"))
        await(vm, fixture, "Working did not end with the reply") { vm.chat.value.streamId.isBlank() }
    }

    // The other call site of the same reattach: the app returning to the foreground with the chat already open.
    @Test fun aMarkedRunIsAlsoFollowedWhenTheAppReturnsToTheForeground() = withFixture { fixture ->
        sendThenDie(fixture)
        fixture.nativeTurnStatus = "working"
        val mark = fixture.requests.size
        val vm = restart(fixture, showing = "chat-a") { it.foreground(false); it.foreground(true) }
        assertLiveStream(vm, fixture)
        assertTrue("Returning to the marked chat asked Hermes", resumed(fixture, mark, "chat-a"))
    }

    @Test fun aChatBeaconNeverSentToOpensWithoutAnyResumeRequest() = withFixture { fixture ->
        assertFalse("chat-b starts unmarked", marked(fixture, "chat-b"))
        val mark = fixture.requests.size
        val vm = restart(fixture) { it.selectSession("chat-b") }
        await(vm, fixture, "chat-b not opened") { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady && vm.chat.value.messages.isNotEmpty() }
        // The open, including its decision to reattach or not, is over when its selection job is.
        val selection = BeaconViewModel::class.java.getDeclaredField("selectionJob").apply { isAccessible = true }.get(vm) as Job?
        runBlocking { withTimeout(15_000) { selection?.join() } }
        val asked = fixture.requests.drop(mark).filter { it.string("method") == "session.resume" ||
            it.string("method") == "session.active_list" && it.getJSONObject("params").string("current_session_id").isNotBlank() }
        assertTrue("Opening an unmarked chat neither resumes nor probes its turn: $asked", asked.isEmpty())
        assertTrue(vm.chat.value.streamId.isBlank())
        compose.onAllNodesWithTag("message-stop").assertCountEquals(0)
    }

    // ---- Lane D-REOPEN, R22 Should 1: the view model's wiring of "a stream job follows a chat only while it was started by the open that is current" ----
    // ReopenedRunningChatTest calls the pure rule (streamFollows). Nothing there fails if the view model stops recording the request a job started with (every normal turn's own job would then count as not
    // following and be re-attached on every watch tick), or if one of the three call sites goes back to "any live job". This case runs the real selectSession, watch and stream on the fixture.

    private val longTool = "terminal-1"
    private val longReply = "The long check finished."
    /** Inside the watch's own 5 s period, so only the open itself can be what asks; about the 3 s the owner would wait. */
    private val reopenBudgetMs = 4_000L

    private fun selectionJob(vm: BeaconViewModel) = BeaconViewModel::class.java.getDeclaredField("selectionJob").apply { isAccessible = true }.get(vm) as Job?
    private fun watchRuns(vm: BeaconViewModel) = (BeaconViewModel::class.java.getDeclaredField("sessionWatch").apply { isAccessible = true }.get(vm) as? Job)?.isActive == true
    private fun stopShown() = compose.onAllNodesWithTag("message-stop").fetchSemanticsNodes().isNotEmpty()
    private fun repliesShown() = compose.onAllNodes(hasText(longReply) and hasAnyAncestor(hasTestTag("conversation-transcript")), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun descriptions(tag: String): List<String> = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .map { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty() }
    private fun framesSince(fixture: NativeExperienceFixture, mark: Int, vararg methods: String) = fixture.requests.drop(mark).filter { it.string("method") in methods }
    /** What the phone and the fixture showed, so one device run says why a check failed. */
    private fun seen(vm: BeaconViewModel, fixture: NativeExperienceFixture, mark: Int) =
        "stream=${vm.chat.value.streamId} status='${vm.chat.value.status}' liveTools=${vm.chat.value.liveTools.size} liveText='${vm.chat.value.liveText}' stop=${stopShown()} " +
            "steps=${descriptions("tool-steps")} cards=${descriptions("tool-card")} attaches=${fixture.liveCollectorSubscriptions.get()} " +
            "frames since the mark=${fixture.requests.drop(mark).map { it.string("method") + ":" + it.optJSONObject("params")?.string("session_id").orEmpty() }}"
    /** A condition that must hold before [deadline] (a real signal, polled by the compose clock), or the failure names what was on screen. */
    private fun within(vm: BeaconViewModel, fixture: NativeExperienceFixture, mark: Int, deadline: Long, what: String, condition: () -> Boolean) {
        try { compose.waitUntil((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(250L), condition) }
        catch (error: Throwable) { throw AssertionError("$what: ${seen(vm, fixture, mark)}", error) }
    }
    /** Measurements ride the instrumentation status stream, so the device log keeps them. */
    private fun record(key: String, value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(key, value) })

    /** Hermes saves the model's message with its tool call before it runs the tool (turn_tool_round.py); the call has no result until the tool ends. */
    private fun saveToolCall(fixture: NativeExperienceFixture) {
        fixture.sessions.getValue("chat-a").getJSONArray("messages").put(json("role" to "assistant", "text" to "", "row_id" to "saved-tool-call",
            "tool_calls" to JSONArray().put(json("tool_call_id" to longTool, "name" to "terminal", "args" to "command=sleep 54"))))
    }
    /** A view model that opened chat-a through selectSession (so its watch runs), with the first stream job's own quiet timer ruled out (as S6ChatUiTest does). */
    private fun openChatA(fixture: NativeExperienceFixture, watchMs: Long): BeaconViewModel {
        val vm = restart(fixture) { field(it, "streamSilenceMs", 600_000L); field(it, "streamWatchMs", watchMs); it.selectSession("chat-a") }
        await(vm, fixture, "chat-a not opened") { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady && vm.chat.value.messages.isNotEmpty() && watchRuns(vm) }
        return vm
    }
    /** Delivers a prompt in chat-a (the marker is written) and leaves its reply on a tool that never ends: its stream is subscribed, Stop shows, and no event follows. */
    private fun sendAndRunALongTool(vm: BeaconViewModel, fixture: NativeExperienceFixture) {
        fixture.persistTerminalMessages = true
        val submits = promptSubmits(fixture)
        val subscribed = fixture.liveCollectorSubscriptions.get()
        compose.runOnIdle { vm.composer(TextFieldValue("Run the long check.")); vm.send() }
        await(vm, fixture, "Prompt not delivered") { promptSubmits(fixture) == submits + 1 && vm.chat.value.streamId == runtime }
        await(vm, fixture, "The reply's stream was not subscribed") { fixture.liveCollectorSubscriptions.get() > subscribed }
        assertTrue("Delivering the prompt writes the run marker", marked(fixture, "chat-a"))
        fixture.emitNativeEvent("tool.start", json("tool_call_id" to longTool, "name" to "terminal", "context" to ""))
        await(vm, fixture, "The long tool was not shown") { vm.chat.value.liveTools.size == 1 }
    }
    private fun finishTheTurn(vm: BeaconViewModel, fixture: NativeExperienceFixture) {
        fixture.nativeTurnStatus = "idle"
        fixture.emitNativeEvent("message.complete", json("text" to longReply))
        await(vm, fixture, "The turn did not settle") { vm.chat.value.streamId.isBlank() && vm.chat.value.messages.any { it.text == longReply } }
    }
    /** Drops the view model, its streams and sockets, and puts the fixture's chat-a back as it was, so the next check starts from nothing. */
    private fun cleanForTheNextCheck(fixture: NativeExperienceFixture, savedRows: Int) {
        compose.runOnUiThread { stores.forEach { it.clear() }; stores.clear(); clients.forEach { it.closeNative() }; clients.clear(); fixture.client.closeNative() }
        fixture.nativeTurnStatus = "idle"
        fixture.sessions.getValue("chat-a").getJSONArray("messages").let { rows -> while (rows.length() > savedRows) rows.remove(rows.length() - 1) }
        forgetChats(fixture)
    }

    /**
     * R22 check 1, as the owner's Hermes does it (R23 Should 1). A reply runs a long tool and Hermes has saved the tool call before the tool runs, so the history holds the call and no result.
     * The person opens chat-b and then chat-a again, inside the old stream job's quiet window (the timer is 600 s here, the watch's period 5 s, so neither can be what answers). The old job is
     * still registered but was started by an earlier open, so it follows nothing; the open must still ask Hermes at once: exactly two resumes (the ask, and the stream's own bind), Stop, one
     * stream attach, and the stored row of the call reads Running (not "No result recorded"), as the only row for the tool. Stop interrupts chat-a's runtime and the turn settles once; the call that
     * never got a result reads "No result recorded" again.
     */
    private fun reopenInsideTheQuietWindow(fixture: NativeExperienceFixture) {
        val vm = openChatA(fixture, watchMs = 5_000L)
        sendAndRunALongTool(vm, fixture)
        saveToolCall(fixture)
        compose.runOnIdle { vm.selectSession("chat-b") }
        await(vm, fixture, "chat-b not opened") { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady && vm.chat.value.messages.isNotEmpty() }
        runBlocking { withTimeout(15_000) { selectionJob(vm)?.join() } }
        val mark = fixture.requests.size
        val attached = fixture.liveCollectorSubscriptions.get()
        val started = SystemClock.elapsedRealtime()
        val deadline = started + reopenBudgetMs
        compose.runOnIdle { vm.selectSession("chat-a") }
        within(vm, fixture, mark, deadline, "Opening the marked chat did not ask Hermes") { resumed(fixture, mark, "chat-a") }
        val askedAfter = SystemClock.elapsedRealtime() - started
        within(vm, fixture, mark, deadline, "Stop did not come back") { vm.chat.value.streamId == runtime && stopShown() }
        val stopAfter = SystemClock.elapsedRealtime() - started
        within(vm, fixture, mark, deadline, "The reply's stream was not followed again") { fixture.liveCollectorSubscriptions.get() > attached }
        within(vm, fixture, mark, deadline, "The stored row of the call does not read Running") { descriptions("tool-card-status") == listOf("Running") }
        val rowAfter = SystemClock.elapsedRealtime() - started
        assertEquals("The tool has one row, not a stored one and a live one", 1, descriptions("tool-card").size)
        val resumes = framesSince(fixture, mark, "session.resume").count { it.getJSONObject("params").string("session_id") == "chat-a" }
        assertEquals("Reopening asks Hermes once and the stream binds once (measured on this deterministic fixture): ${seen(vm, fixture, mark)}", 2, resumes)
        record("d_reopen_reopen", "asked after $askedAfter ms, Stop after $stopAfter ms, stored row Running after $rowAfter ms, $resumes session.resume, ${fixture.liveCollectorSubscriptions.get() - attached} stream attach")

        compose.onNodeWithTag("message-stop").performClick()
        await(vm, fixture, "Stop did not interrupt chat-a's runtime") { framesSince(fixture, mark, "session.interrupt").any { it.getJSONObject("params").string("session_id") == runtime } }
        finishTheTurn(vm, fixture)
        await(vm, fixture, "The run marker was not dropped when the turn settled") { !marked(fixture, "chat-a") }
        within(vm, fixture, mark, SystemClock.elapsedRealtime() + 5_000L, "The reply does not show exactly once") { repliesShown() == 1 }
        within(vm, fixture, mark, SystemClock.elapsedRealtime() + 5_000L, "The call that never got a result does not read No result recorded once the turn ended") { descriptions("tool-card-status") == listOf("No result recorded") }
        assertEquals("Stop interrupted the turn once", 1, framesSince(fixture, mark, "session.interrupt").size)
        assertEquals("One stream followed the reopened chat, and no second one replaced it", attached + 1, fixture.liveCollectorSubscriptions.get())
        compose.onAllNodesWithTag("message-stop").assertCountEquals(0)
    }

    /**
     * R22 check 2. The same chat is selected again mid-turn (a reconnect or a refresh) and Hermes has saved the tool call by then. The old job was started by an earlier open, so the watch (500 ms
     * here) must replace it with a current one: Stop, the words said after the re-select, and exactly one new stream attach, which stays one over the next two watch periods (R23 Nice 1: the
     * watch does not re-attach on every tick). The view model's own request.current() compares the chat only, so on F7 too the old job still delivers the words here (the RED run showed it);
     * the new attach is what tells the two builds apart.
     */
    private fun sameChatSelectedMidTurn(fixture: NativeExperienceFixture) {
        val vm = openChatA(fixture, watchMs = 500L)
        sendAndRunALongTool(vm, fixture)
        saveToolCall(fixture)
        val mark = fixture.requests.size
        val attached = fixture.liveCollectorSubscriptions.get()
        val said = "Still running the check."
        val started = SystemClock.elapsedRealtime()
        val deadline = started + reopenBudgetMs
        compose.runOnIdle { vm.selectSession("chat-a") }
        fixture.emitNativeEvent("message.delta", json("text" to said))
        within(vm, fixture, mark, deadline, "Stop did not come back") { vm.chat.value.streamId == runtime && stopShown() }
        val stopAfter = SystemClock.elapsedRealtime() - started
        within(vm, fixture, mark, deadline, "The words said after the re-select did not show") { vm.chat.value.liveText.contains(said) }
        within(vm, fixture, mark, deadline, "The reply's stream was not followed again") { fixture.liveCollectorSubscriptions.get() > attached }
        // Two more watch periods (500 ms each), read from the clock: the one attach must stay the one attach.
        val replaced = SystemClock.elapsedRealtime()
        compose.waitUntil(5_000L) { SystemClock.elapsedRealtime() - replaced >= 1_000L }
        assertEquals("The re-select re-attached exactly once, not on every watch tick: ${seen(vm, fixture, mark)}", attached + 1, fixture.liveCollectorSubscriptions.get())
        record("d_reopen_reselect", "Stop and the new words after $stopAfter ms, ${framesSince(fixture, mark, "session.resume").size} session.resume, ${fixture.liveCollectorSubscriptions.get() - attached} stream attach (still one after 2 watch periods)")
        finishTheTurn(vm, fixture)
    }

    /**
     * R22 check 3, the guard for the normal case. A turn sent from the open chat is followed by its own job: with the watch at 150 ms, at least five ticks pass during the long tool and
     * nothing asks Hermes or attaches a second time. A job that stopped recording its request would count as not following and be re-attached on every tick. A tick of a followed chat does
     * nothing observable, so (R23 Nice 1) a probe on the same dispatcher, with the same period, counts the ticks that ran in the 3 s window; it cannot be higher than the watch's own count would be.
     */
    private fun aNormalTurnIsNeverReattached(fixture: NativeExperienceFixture) {
        val vm = openChatA(fixture, watchMs = 150L)
        val before = fixture.liveCollectorSubscriptions.get()
        sendAndRunALongTool(vm, fixture)
        val mark = fixture.requests.size
        val attached = fixture.liveCollectorSubscriptions.get()
        assertTrue("The send's own stream is subscribed", attached > before)
        val windowMs = 3_000L
        val mainTicks = java.util.concurrent.atomic.AtomicInteger()
        val probe = CoroutineScope(Dispatchers.Main).launch { while (true) { delay(150L); mainTicks.incrementAndGet() } }
        val from = SystemClock.elapsedRealtime()
        try { compose.waitUntil(windowMs + 10_000L) { SystemClock.elapsedRealtime() - from >= windowMs } } finally { probe.cancel() }
        assertTrue("At least five ticks of the watch's period ran in the window (${mainTicks.get()}), so the guard was exercised", mainTicks.get() >= 5)
        assertTrue("The watch ran through the long tool", watchRuns(vm))
        val asked = framesSince(fixture, mark, "session.resume", "session.active_list", "session.events.since")
        assertTrue("A turn the chat follows is not asked about or attached again: $asked; ${seen(vm, fixture, mark)}", asked.isEmpty())
        assertEquals("No second stream attach while the long tool ran: the send's own job is the one", attached, fixture.liveCollectorSubscriptions.get())
        assertEquals("Still following the turn", runtime, vm.chat.value.streamId)
        assertTrue("Stop is still shown", stopShown())
        record("d_reopen_normal", "${mainTicks.get()} main-thread ticks of 150 ms in $windowMs ms, ${asked.size} resume/status/replay frames, ${fixture.liveCollectorSubscriptions.get() - before} stream attach")
        finishTheTurn(vm, fixture)
    }

    @Test fun aRunningChatOpenedAgainIsFollowedAtOnceAndANormalTurnIsNeverReattached() = withFixture { fixture ->
        val savedRows = fixture.sessions.getValue("chat-a").getJSONArray("messages").length()
        val failures = mutableListOf<String>()
        // Each check runs on its own view model and reports on its own, so one device run shows which of the three a change breaks.
        listOf<Pair<String, () -> Unit>>(
            "Opened again inside the quiet window" to { reopenInsideTheQuietWindow(fixture) },
            "The same chat selected again mid-turn" to { sameChatSelectedMidTurn(fixture) },
            "A normal turn is never re-attached" to { aNormalTurnIsNeverReattached(fixture) },
        ).forEach { (name, check) ->
            try { check() } catch (error: Throwable) { failures += "$name -> ${error.message}" }
            finally { cleanForTheNextCheck(fixture, savedRows) }
        }
        assertTrue("${failures.size} of 3 checks failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    // Beacon 1.1.15 (ffdce12) moved replies to hermes-messages-v2 and deleted hermes-messages. The "working" notification still named the deleted channel, so Android dropped it.
    // Kept here, not in NativeActivityDeviceTest (the closest fit), because that file is not this leaf's to edit; it uses the same source binding.
    @Test fun aWorkingEventPostsNoCardAndPreservesChannelsWhileAReplyStillPosts() {
        BackgroundActivityFixture(app).use { fixture ->
            fixture.postLegacyWorkingCard()
            val channels = fixture.channelSettings()
            assertNull(receivePush(app, fixture.data("working"), null, deferPreview = true))
            fixture.await { fixture.manager.activeNotifications.none { it.tag == fixture.nativeTag && it.id == 3 } }
            assertNull("Ordinary work has no redundant alert", fixture.card(fixture.event))
            assertEquals("Working does not change channel preferences", channels, fixture.channelSettings())
            app.calls.uiVisible = true
            val reply = "completion-${fixture.event}"
            assertNotNull(receivePush(app, fixture.data("completion", reply).minus("activity_ref"), null, deferPreview = true))
            fixture.await { fixture.card(reply) != null }
            val posted = checkNotNull(fixture.card(reply))
            assertNotNull(fixture.manager.getNotificationChannel(posted.channelId))
            assertTrue("A meaningful reply is still an alert card", posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
        }
    }
}
