package dev.hermes.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
import dev.hermes.android.push.cancelNativeActivities
import dev.hermes.android.push.presentNativeActivity
import dev.hermes.android.ui.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Pins the 1.1.15 fixes that had no test (ponytail-1115a-check.md, finding 6): the cold reattach of a reply that was running when Beacon was killed,
 * and the "working" notification's channel. The synthetic fixture and Android's own notifications only; no service, model or network.
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

    // A message queued behind the reply goes back in the box after a cold settle; it is never sent by itself.
    @Test fun aQueuedMessageReturnsToTheComposerUnsentAfterAColdSettle() = withFixture { fixture ->
        fixture.persistTerminalMessages = true
        val store = ViewModelStore()
        val first = newViewModel(store, fixture, fixture.client, "chat-a")
        compose.runOnIdle { first.composer(TextFieldValue("Plan the trip while I am away.")); first.send() }
        await(first, fixture, "Prompt not delivered") { promptSubmits(fixture) == 1 && first.chat.value.streamId == runtime }
        val queued = "Also check the weather there."
        compose.runOnIdle { first.composer(TextFieldValue(queued)); first.queue() }
        await(first, fixture, "Queued message not saved") { runBlocking { app.cache.read(fixture.scope.session("chat-a"), "draft") }?.contains(queued) == true }
        compose.runOnUiThread { store.clear(); fixture.client.closeNative() }
        fixture.nativeTurnStatus = "idle"
        fixture.retainNativeEvent(runtime, "message.complete", json("text" to reply))
        val vm = restart(fixture) { it.selectSession("chat-a") }
        await(vm, fixture, "Reply not settled") { !marked(fixture, "chat-a") && vm.chat.value.messages.any { it.text == reply } && vm.composer.value.text == queued }
        assertTrue("The queued message left the queue for the box", vm.chat.value.queued.isEmpty())
        assertEquals("Nothing was sent by itself", 1, promptSubmits(fixture))
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
        val asked = fixture.requests.drop(mark).filter { it.string("method") in setOf("session.resume", "session.active_list") }
        assertTrue("Opening an unmarked chat asks Hermes nothing: $asked", asked.isEmpty())
        assertTrue(vm.chat.value.streamId.isBlank())
        compose.onAllNodesWithTag("message-stop").assertCountEquals(0)
    }

    // Beacon 1.1.15 (ffdce12) moved replies to hermes-messages-v2 and deleted hermes-messages. The "working" notification still named the deleted channel, so Android dropped it.
    // Kept here, not in NativeActivityDeviceTest (the closest fit), because that file is not this leaf's to edit; it uses the same source binding.
    @Test fun aWorkingNotificationIsPostedOnAChannelThatExists() {
        val context = app
        val manager = context.getSystemService(NotificationManager::class.java)
        val source = "synthetic-working-source"
        val origin = "https://notification-fixture.invalid"
        val previousAuth = context.auth.read(origin)
        val auth = AuthContext(origin, emptyList(), "synthetic-owner", "default", backend = "hermes-dashboard", nativeProvider = "fixture")
        val prefs = context.getSharedPreferences("push-binding", Context.MODE_PRIVATE)
        val key = "source:${source.digest()}"
        try {
            context.auth.write(auth)
            val binding = json("source" to source, "origin" to origin, "owner" to auth.owner, "profile" to auth.profile, "native_provider" to auth.nativeProvider, "backend" to "hermes-dashboard")
            assertTrue(prefs.edit().putString(key, android.util.Base64.encodeToString(context.cipher.encrypt(binding.toString().toByteArray(), key), android.util.Base64.NO_WRAP)).commit())
            presentNativeActivity(context, source, "synthetic-working-event", "synthetic-working-${System.currentTimeMillis()}", "working", System.currentTimeMillis() / 1000 + 120)
            val deadline = System.currentTimeMillis() + 3000
            while (manager.activeNotifications.none { it.notification.extras.getString("native_source") == source } && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val posted = manager.activeNotifications.singleOrNull { it.notification.extras.getString("native_source") == source }
            assertNotNull("The working notification is not among the active notifications (posted to a channel that does not exist?)", posted)
            val channel = posted!!.notification.channelId
            assertNotNull("The working notification names channel $channel, which does not exist", manager.getNotificationChannel(channel))
            assertTrue("A working notification stays until the turn ends", posted.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        } finally { cancelNativeActivities(context, source); prefs.edit().remove(key).commit(); context.auth.write(previousAuth) }
    }
}
