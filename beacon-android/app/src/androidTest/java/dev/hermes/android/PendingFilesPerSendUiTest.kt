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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * CHATQA-121 follow-up 6 (backlog 80): the files a send carried are kept for that send's own row only. They were kept under the chat stream and moved
 * to whichever row linked next in that chat, so a send whose row never linked (removed, failed, lost) handed its files and words to the next message.
 * Synthetic text only.
 */
class PendingFilesPerSendUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val rendered = mutableStateOf<BeaconViewModel?>(null)
    private val submissions = mutableListOf<String>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
    private fun field(vm: BeaconViewModel, name: String, value: Any?) { BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value) }

    private val kinds = listOf("pending", "draft", "session", "running-turn", "live:runtime-chat-a", "live:chat-a", "attachments-pending:chat-a", "attachments-pending:runtime-chat-a",
        "attachments:9101", "attachments:9102")

    private fun withFixture(block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val prior = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture().apply { canonicalAuthority = true }
        val store = ViewModelStore()
        val scope = fixture.scope.session("chat-a")
        val entries = kinds.map { scope to it }
        val priorCache = runBlocking { entries.associateWith { (s, kind) -> app.cache.read(s, kind) } }
        lateinit var vm: BeaconViewModel
        var constructed: BeaconViewModel? = null
        try {
            runBlocking {
                app.preferences.edit { it[stringPreferencesKey("origin")] = ""; it.remove(stringPreferencesKey("busy_send_action")) }
                app.auth.persist(fixture.client.auth)
                entries.forEach { (s, kind) -> app.cache.delete(s, kind) }
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
            compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) { rendered.value?.let { current -> ChatScreen(current) } } } }
            block(vm, fixture)
        } finally {
            fixture.releasePromptAcknowledgement()
            compose.runOnUiThread { constructed?.client?.closeNative(); store.clear(); fixture.client.closeNative() }
            runBlocking {
                submissions.forEach { app.cache.delete(scope, "attachments-pending:sub:$it") }
                entries.forEach { entry -> priorCache[entry]?.let { app.cache.write(entry.first, entry.second, it, true) } ?: app.cache.delete(entry.first, entry.second) }
                app.auth.remove(fixture.scope.origin)
                app.preferences.updateData { prior }
            }
        }
    }

    private fun openWatchedChat(vm: BeaconViewModel) {
        compose.runOnIdle { field(vm, "streamWatchMs", 150L); vm.selectSession("chat-a") }
        val watcher = BeaconViewModel::class.java.getDeclaredField("sessionWatch").apply { isAccessible = true }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady && (watcher.get(vm) as? kotlinx.coroutines.Job)?.isActive == true }
    }
    private fun savedRows(fixture: NativeExperienceFixture): JSONArray = fixture.sessions.getValue("chat-a").getJSONArray("messages")
    private fun hermesSavesTheUserRow(fixture: NativeExperienceFixture, rowId: Long, text: String, submission: String) {
        savedRows(fixture).put(json("id" to rowId, "role" to "user", "content" to text, "timestamp" to System.currentTimeMillis() / 1000.0, "display_metadata" to json("submission_id" to submission)))
    }
    private fun photo(fixture: NativeExperienceFixture) = json("id" to "00000000-0000-4000-8000-000000000006", "name" to "photo.png", "mime" to "image/png",
        "scope_key" to fixture.scope.session("chat-a").key, "staged_base64" to "iVBORw0KGgo=")

    /** A send Hermes answered "queued" and held (as behind a turn that is just ending), with [files] in the composer. */
    private fun sendHeld(vm: BeaconViewModel, fixture: NativeExperienceFixture, text: String, files: List<JSONObject> = emptyList()): QueuedMessage {
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        fixture.nativeTurnStatus = "working"
        fixture.queueNextCanonicalSubmission.set(true)
        if (files.isNotEmpty()) compose.runOnIdle { flow<List<JSONObject>>(vm, "_attachments").value = files }
        compose.onNodeWithTag("message-input").performTextInput(text)
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.let { it.state == "accepted" && it.admissionId.isNotBlank() } == true }
        compose.waitUntil(15_000) { vm.chat.value.streamId == "chat-a" && fixture.liveCollectorSubscriptions.get() > 0 }
        return vm.chat.value.queued.single().also { submissions += it.submissionId }
    }

    /** The turn starts, Hermes saves the stamped user row 9101 and its reply 9102, and the turn's receipt settles the send (where the pending files are linked). */
    private fun hermesRunsTheTurn(vm: BeaconViewModel, fixture: NativeExperienceFixture, sent: QueuedMessage, rowText: String, beforeRowSaved: () -> Unit = {}) {
        fixture.startCanonicalTurn(sent.admissionId, live = true)
        Thread.sleep(600)
        beforeRowSaved()
        hermesSavesTheUserRow(fixture, 9101, rowText, sent.submissionId)
        savedRows(fixture).put(json("id" to 9102, "role" to "assistant", "content" to "Synthetic reply words"))
        fixture.setCanonicalAdmission(sent.admissionId, "terminal", "completed")
        runCatching {
            fixture.emitNativeEvent("message.complete", json("text" to "Synthetic reply words", "admission_id" to sent.admissionId, "status" to "completed",
                "persisted_turn" to json("complete" to true, "submission_id" to sent.submissionId, "user_row_id" to 9101, "user_row_ids" to JSONArray(listOf(9101)),
                    "final_assistant_row_id" to 9102, "row_ids" to JSONArray(listOf(9101, 9102)))))
        }
        compose.waitUntil(30_000) { vm.chat.value.streamId.isBlank() && vm.chat.value.messages.any { it.id == "9102" } }
        compose.waitUntil(15_000) { vm.chat.value.queued.none { it.id == sent.id && it.state != "settled" } }
        // Hermes' saved rows are read again (as a reopen does), so the kept receipts are applied to them.
        compose.runOnIdle { vm.selectSession("chat-a") }
        compose.waitUntil(15_000) { vm.chat.value.messages.any { it.id == "9101" && it.role == "user" } }
    }

    private fun cached(fixture: NativeExperienceFixture, kind: String): String? = runBlocking { app.cache.read(fixture.scope.session("chat-a"), kind) }

    /** A record a 1.2.0 app left for another send of this chat stream (a send whose row never linked) is never put on this message's row. */
    @Test fun aLeftoverRecordOfAnotherSendIsNeverShownOnTheNextRow() = withFixture { vm, fixture ->
        val leftover = json("attachments" to JSONArray().put(json("name" to "leftover.pdf", "mime" to "application/pdf", "path" to "/tmp/leftover.pdf")), "display_text" to "Words of another send").toString()
        runBlocking { app.cache.write(fixture.scope.session("chat-a"), "attachments-pending:chat-a", leftover, true) }
        openWatchedChat(vm)
        val sent = sendHeld(vm, fixture, "Synthetic words of this send")
        hermesRunsTheTurn(vm, fixture, sent, "Synthetic words of this send")
        compose.waitUntil(15_000) { cached(fixture, "attachments-pending:chat-a") == null }
        assertNull("the other send's files and words are not put on this row", cached(fixture, "attachments:9101"))
        compose.onAllNodesWithText("Words of another send", substring = true).assertCountEquals(0)
        assertTrue(vm.chat.value.messages.first { it.id == "9101" }.attachments.isEmpty())
    }

    /** "Remove this copy" on a send Hermes admitted (files kept for its row) leaves no kept files behind. */
    @Test fun removingASendDropsItsKeptFiles() = withFixture { vm, fixture ->
        openWatchedChat(vm)
        val sent = sendHeld(vm, fixture, "Synthetic words with a photo", listOf(photo(fixture)))
        val own = "attachments-pending:sub:${sent.submissionId}"
        // Fix 5 kept it under the chat stream; either way a record of this send exists while the copy is in the chat.
        assertTrue("the send's files are kept", cached(fixture, own) != null || cached(fixture, "attachments-pending:chat-a") != null)
        compose.runOnIdle { vm.removeUnresolvedLocalMessage(sent.id) }
        compose.waitUntil(15_000) { vm.chat.value.queued.none { it.id == sent.id } }
        compose.waitUntil(15_000) { cached(fixture, own) == null && cached(fixture, "attachments-pending:chat-a") == null }
    }

    /** A message's own files go on its own row, tied to its submission. */
    @Test fun aSendsFilesGoOnItsOwnRow() = withFixture { vm, fixture ->
        openWatchedChat(vm)
        val sent = sendHeld(vm, fixture, "Synthetic words with a photo", listOf(photo(fixture)))
        hermesRunsTheTurn(vm, fixture, sent, "Synthetic words with a photo")
        compose.waitUntil(15_000) { cached(fixture, "attachments:9101") != null }
        val record = JSONObject(cached(fixture, "attachments:9101")!!)
        assertEquals("Synthetic words with a photo", record.getString("display_text"))
        assertEquals(1, record.getJSONArray("attachments").length())
        assertEquals("the record names its send", sent.submissionId, record.optString("submission_id"))
        assertNull("nothing is left waiting", cached(fixture, "attachments-pending:sub:${sent.submissionId}"))
        assertNull(cached(fixture, "attachments-pending:chat-a"))
    }

    /** A kept record that names another send is not applied to a row Hermes stamped with a different submission. */
    @Test fun aKeptRecordOfAnotherSendIsNotAppliedToThisRow() = withFixture { vm, fixture ->
        openWatchedChat(vm)
        val sent = sendHeld(vm, fixture, "Synthetic words of this send")
        val foreign = json("attachments" to JSONArray(), "display_text" to "Words of another send", "submission_id" to "another-send").toString()
        hermesRunsTheTurn(vm, fixture, sent, "Synthetic words of this send") {
            runBlocking { app.cache.write(fixture.scope.session("chat-a"), "attachments:9101", foreign) }
        }
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("message-time:9101"))
        compose.onAllNodesWithText("Words of another send", substring = true).assertCountEquals(0)
        assertEquals("Synthetic words of this send", vm.chat.value.messages.first { it.id == "9101" }.text)
    }
}
