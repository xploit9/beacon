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
import dev.hermes.android.data.ChatAttachmentStore
import dev.hermes.android.ui.ChatScreen
import dev.hermes.android.ui.NativeLocalization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CHAT-121 follow-up 5, Root's rule (as with Telegram): once Hermes admits a message it shows as sent, as Hermes' own saved row, at once, whatever its turn
 * does after. A session authority answers every fresh send "queued" and saves the user row when the turn starts, before the model is asked
 * (agent/turn_context._persist_turn_start), with the phone's submission id on it (display_metadata.submission_id, gateway/response_filters
 * .persisted_user_metadata_for_event). The synthetic authority here does what Hermes does: the turn's stamped start frame, then the stamped row.
 * Synthetic text only.
 */
class AdmittedRowUiTest {
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
        val fixture = NativeExperienceFixture().apply { canonicalAuthority = true }
        val store = ViewModelStore()
        val scope = fixture.scope.session("chat-a")
        val cacheScopes = listOf(scope, fixture.scope.session("chat-b"))
        // Chat join: also the pending attachment record of each stream this test uses and the receipts of its rows. A kept receipt for row 9101 puts
        // its own words on that row; one can be left when this test's row links while another send's pending record for stream chat-a is kept.
        val cacheKinds = listOf("pending", "draft", "session", "running-turn", "live:runtime-chat-a", "live:chat-a", "live:runtime-chat-b", "live:chat-b",
            "attachments-pending:chat-a", "attachments-pending:runtime-chat-a", "attachments-pending:chat-b", "attachments-pending:runtime-chat-b",
            "attachments:9101", "attachments:9102")
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
            compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) { rendered.value?.let { current -> ChatScreen(current) } } } }
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

    private fun openWatchedChat(vm: BeaconViewModel) {
        compose.runOnIdle { field(vm, "streamWatchMs", 150L); vm.selectSession("chat-a") }
        val watcher = BeaconViewModel::class.java.getDeclaredField("sessionWatch").apply { isAccessible = true }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady && (watcher.get(vm) as? kotlinx.coroutines.Job)?.isActive == true }
    }

    private fun typeAndSend(text: String) {
        compose.onNodeWithTag("message-input").performTextInput(text)
        compose.onNodeWithTag("message-send").performClick()
    }

    private fun inTranscript(text: String) = hasText(text) and hasAnyAncestor(hasTestTag("conversation-transcript"))
    private fun savedRows(fixture: NativeExperienceFixture): JSONArray = fixture.sessions.getValue("chat-a").getJSONArray("messages")
    /** What Hermes writes when the admission's turn starts: the user row, with this phone's submission id on it. */
    private fun hermesSavesTheUserRow(fixture: NativeExperienceFixture, rowId: Long, text: String, submission: String) {
        savedRows(fixture).put(json("id" to rowId, "role" to "user", "content" to text, "timestamp" to System.currentTimeMillis() / 1000.0,
            "display_metadata" to json("submission_id" to submission)))
    }

    /** A send Hermes answered "queued" and then held (as behind a turn that is just ending), so the test decides when its turn starts. */
    private fun sendHeld(vm: BeaconViewModel, fixture: NativeExperienceFixture, text: String): QueuedMessage {
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        fixture.nativeTurnStatus = "working"
        fixture.queueNextCanonicalSubmission.set(true)
        typeAndSend(text)
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.let { it.state == "accepted" && it.admissionId.isNotBlank() } == true }
        compose.waitUntil(15_000) { vm.chat.value.streamId == "chat-a" && fixture.liveCollectorSubscriptions.get() > 0 }
        return vm.chat.value.queued.single()
    }

    @Test fun anAdmittedMessageShowsAsHermesSavedRowWhileItsReplyRuns() = withFixture { vm, fixture ->
        openWatchedChat(vm)
        val sent = sendHeld(vm, fixture, "Synthetic admitted message")
        // Admitted, its turn not started yet: the phone's copy is in the chat.
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:${sent.id}"))
        // Hermes starts the turn (the stamped start frame), then saves the user row a moment later, before the model answers. Hermes' row carries its
        // attachment note below the words (as for a sent document), so the words alone would not match the copy: only Hermes' stamp ties them.
        fixture.startCanonicalTurn(sent.admissionId, live = true)
        Thread.sleep(600)
        hermesSavesTheUserRow(fixture, 9101, "Synthetic admitted message\n\n--- Attached Context ---\n\nSynthetic note about a document", sent.submissionId)
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.rowId == "9101" && vm.chat.value.messages.any { it.id == "9101" && it.role == "user" } }
        // While the reply still runs (nothing has ended it), the message is Hermes' saved row, once, and the phone's copy is gone from the chat.
        assertTrue("The reply is still running", vm.chat.value.streamId.isNotBlank())
        assertEquals("The receipt still settles the turn itself", "accepted", vm.chat.value.queued.single().state)
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("message-time:9101"))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("queued-message:${sent.id}").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodes(inTranscript("Synthetic admitted message"), useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("Attached Context", substring = true).assertCountEquals(0)
        // The reply streams on below it.
        fixture.emitNativeEvent("message.delta", json("text" to "Synthetic reply words"))
        compose.waitUntil(15_000) { vm.chat.value.liveText == "Synthetic reply words" }
        compose.onAllNodesWithTag("queued-message:${sent.id}").assertCountEquals(0)
        compose.onAllNodes(inTranscript("Synthetic admitted message"), useUnmergedTree = true).assertCountEquals(1)
        // The turn ends as Hermes ends one: its receipt proves both rows, and the copy is settled by it as before.
        savedRows(fixture).put(json("id" to 9102, "role" to "assistant", "content" to "Synthetic reply words"))
        fixture.setCanonicalAdmission(sent.admissionId, "terminal", "completed")
        runCatching {
            fixture.emitNativeEvent("message.complete", json("text" to "Synthetic reply words", "admission_id" to sent.admissionId, "status" to "completed",
                "persisted_turn" to json("complete" to true, "submission_id" to sent.submissionId, "user_row_id" to 9101, "user_row_ids" to JSONArray(listOf(9101)),
                    "final_assistant_row_id" to 9102, "row_ids" to JSONArray(listOf(9101, 9102)))))
        }
        compose.waitUntil(30_000) { vm.chat.value.streamId.isBlank() && vm.chat.value.messages.any { it.id == "9102" } }
        compose.waitUntil(15_000) { vm.chat.value.queued.none { it.id == sent.id && it.state != "settled" } }
        assertEquals("One saved row for the message", 1, vm.chat.value.messages.count { it.id == "9101" })
        compose.onAllNodesWithTag("queued-message:${sent.id}").assertCountEquals(0)
        compose.onAllNodes(inTranscript("Synthetic admitted message"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun aRowHermesSavedForAnotherSendIsNeverThisMessage() = withFixture { vm, fixture ->
        openWatchedChat(vm)
        // Another client's turn runs; Hermes holds this send queued behind it.
        fixture.executingAdmission = "admission-other-client"
        val sent = sendHeld(vm, fixture, "Book the 11:45 instead")
        val seen = CopyOnWriteArrayList<String>()
        val recorder = CoroutineScope(Dispatchers.Default).launch { vm.chat.collect { chat -> chat.queued.firstOrNull { it.id == sent.id }?.rowId?.let(seen::add) } }
        try {
            // The other client sent the same words; Hermes saved them with that client's own submission id.
            hermesSavesTheUserRow(fixture, 9201, "Book the 11:45 instead", "desktop-send-7")
            fixture.emitNativeEvent("message.delta", json("text" to "Another client's reply is streaming"))
            compose.waitUntil(15_000) { vm.chat.value.liveText == "Another client's reply is streaming" }
            // This message's own turn starts; Hermes saves its row with this phone's stamp.
            fixture.startCanonicalTurn(sent.admissionId, live = true)
            Thread.sleep(600)
            hermesSavesTheUserRow(fixture, 9202, "Book the 11:45 instead", sent.submissionId)
            compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.rowId == "9202" }
        } finally { recorder.cancel() }
        assertFalse("Never tied to the other send's row, not even for a moment: $seen", "9201" in seen)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("queued-message:${sent.id}").fetchSemanticsNodes().isEmpty() }
        // Both sends are in the chat, each once, as Hermes saved them.
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("message-time:9202"))
        assertEquals(listOf("9201", "9202"), vm.chat.value.messages.filter { it.text == "Book the 11:45 instead" }.map { it.id })
    }

    @Test fun aRowHermesSavesLateIsShownOnALaterFrameOfItsTurn() = withFixture { vm, fixture ->
        openWatchedChat(vm)
        val sent = sendHeld(vm, fixture, "Synthetic slow start")
        fixture.startCanonicalTurn(sent.admissionId, live = true)
        // A cold or heavy profile can take longer to build its agent than the first reads after the start frame (12.4 s): the row comes after them.
        Thread.sleep(14_000)
        assertEquals("No row yet", "", vm.chat.value.queued.single().rowId)
        hermesSavesTheUserRow(fixture, 9301, "Synthetic slow start", sent.submissionId)
        // Hermes' frames go on (its wait line, the model's progress): a later one reads the chat again.
        val deadline = System.currentTimeMillis() + 30_000
        while (vm.chat.value.queued.singleOrNull()?.rowId != "9301" && System.currentTimeMillis() < deadline) {
            fixture.emitNativeEvent("thinking.delta", json("text" to "Waiting for the model"))
            Thread.sleep(1_000)
        }
        assertEquals("9301", vm.chat.value.queued.single().rowId)
        assertTrue("The reply is still running", vm.chat.value.streamId.isNotBlank())
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("queued-message:${sent.id}").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodes(inTranscript("Synthetic slow start"), useUnmergedTree = true).assertCountEquals(1)
    }

    /** A kept copy of a large file (follow-up 4), as the picker leaves it: the file in the app's own storage, the attachment saying staged_file. */
    private fun keptFile(name: String): Pair<java.io.File, org.json.JSONObject> {
        val id = java.util.UUID.randomUUID().toString()
        val source = java.io.File(app.cacheDir, "admitted-row-kept-$id").apply { writeBytes(ByteArray(64 * 1024) { 7 }) }
        return ChatAttachmentStore.adopt(source, id) to json("id" to id, "name" to name, "mime" to "application/pdf", "size" to 64 * 1024, "staged_file" to true)
    }

    /** A send whose answer was lost, and whose receipt then says Hermes admitted it (queued, no row yet), as reconcile reads it. */
    private fun admittedWithoutItsRow(vm: BeaconViewModel, fixture: NativeExperienceFixture, attachment: org.json.JSONObject, keepInComposer: Boolean) {
        val scope = fixture.scope.session("chat-a")
        val send = PendingSend("kept-send-${attachment.string("id").take(8)}", scope, Draft("Synthetic document check", attachments = listOf(attachment)),
            admissionId = "admission-kept-send", submissionId = "submission-kept-send", afterRowId = "11")
        runBlocking { app.cache.write(scope, "pending", send.encode(), true) }
        compose.runOnIdle {
            @Suppress("UNCHECKED_CAST")
            (BeaconViewModel::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(vm) as MutableMap<String, PendingSend>)[scope.key] = send
            if (keepInComposer) flow<List<org.json.JSONObject>>(vm, "_attachments").value = listOf(attachment)
            vm.reconcileSend()
        }
        compose.waitUntil(15_000) { vm.chat.value.queued.any { it.id == send.id && it.state == "accepted" } }
    }

    /**
     * REVIEW-121B F2-P3-5: the kept copy outlived the send until its turn completed (minutes with a slow model) or the 7-day sweep, though Hermes has the file
     * once it admitted the message. It goes when Hermes admits the send, row known or not.
     */
    @Test fun aKeptFileGoesOnceHermesAdmitsTheSendWithoutItsRow() = withFixture { vm, fixture ->
        openWatchedChat(vm)
        val (file, attachment) = keptFile("Synthetic report.pdf")
        try {
            admittedWithoutItsRow(vm, fixture, attachment, keepInComposer = false)
            compose.waitUntil(5_000) { !file.exists() }
        } finally { file.delete() }
    }

    /** A file the composer still lists (the owner kept it there while the send went) is still to be sent: it stays. */
    @Test fun aKeptFileTheComposerStillListsStays() = withFixture { vm, fixture ->
        openWatchedChat(vm)
        val (file, attachment) = keptFile("Synthetic report.pdf")
        try {
            admittedWithoutItsRow(vm, fixture, attachment, keepInComposer = true)
            Thread.sleep(1_000)
            assertTrue(file.isFile)
        } finally { file.delete() }
    }

    /** A message removed from the box takes the file kept for it along. */
    @Test fun aKeptFileGoesWithAMessageRemovedFromTheBox() = withFixture { vm, _ ->
        openWatchedChat(vm)
        val (file, attachment) = keptFile("Synthetic report.pdf")
        val notSent = QueuedMessage("Synthetic document check", listOf(attachment), state = "settled", outcome = "cancelled", admissionId = "admission-cancelled")
        try {
            compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(queued = listOf(notSent)) }
            runBlocking { vm.checkpointNow() }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("queue-remove:${notSent.id}").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(file.isFile)
            compose.onNodeWithTag("queue-remove:${notSent.id}").performClick()
            compose.waitUntil(15_000) { vm.chat.value.queued.none { it.id == notSent.id } }
            compose.waitUntil(5_000) { !file.exists() }
        } finally { file.delete() }
    }

    /** A large file the phone kept for a send (CHAT-121 follow-up 4) goes once Hermes' saved row names the send: Hermes holds the file then. */
    @Test fun aKeptFileGoesWhenHermesSavedRowNamesTheSend() = withFixture { vm, fixture ->
        openWatchedChat(vm)
        val id = java.util.UUID.randomUUID().toString()
        val source = java.io.File(app.cacheDir, "admitted-row-kept-$id").apply { writeBytes(ByteArray(64 * 1024) { 7 }) }
        val kept = ChatAttachmentStore.adopt(source, id)
        val attachment = json("id" to id, "name" to "Synthetic report.pdf", "mime" to "application/pdf", "size" to 64 * 1024, "staged_file" to true)
        val copy = QueuedMessage("Synthetic document check", listOf(attachment), state = "accepted", admissionId = "admission-kept", submissionId = "submission-kept",
            afterRowId = "11", admissionStatus = "started")
        try {
            // Hermes admitted it and its turn saved the row (with its note about the document) while this chat was closed.
            compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(queued = listOf(copy)) }
            runBlocking { vm.checkpointNow() }
            assertTrue(kept.isFile)
            hermesSavesTheUserRow(fixture, 9401, "Synthetic document check\n\n--- Attached Context ---\n\nSynthetic note about a document", "submission-kept")
            compose.runOnIdle { vm.selectSession("chat-a") }
            compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.rowId == "9401" && vm.chat.value.messages.any { it.id == "9401" } }
            compose.waitUntil(5_000) { !kept.exists() }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("queued-message:${copy.id}").fetchSemanticsNodes().isEmpty() }
            compose.onAllNodesWithText("Attached Context", substring = true).assertCountEquals(0)
        } finally { kept.delete(); source.delete() }
    }
}
