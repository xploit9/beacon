package dev.hermes.android.ui

import androidx.activity.ComponentActivity
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
import dev.hermes.android.*
import dev.hermes.android.core.*
import dev.hermes.android.data.archivedVoiceTranscripts
import dev.hermes.android.data.nativeCompletedTurn
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicInteger

/** In-process fixture only; no real call, account, provider, sensor or owner endpoint is used. */
class MixedTimelineJourneyUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) = BeaconViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
    private fun field(vm: BeaconViewModel, name: String, value: Any?) = BeaconViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(vm, value)

    /** Native mutation boundary for the form test; REST reads still use the shared in-process fixture. */
    private fun undoClient(fixture: NativeExperienceFixture, target: Scope, mutations: AtomicInteger): BeaconClient {
        val revision = AtomicInteger()
        val sockets = WebSocket.Factory { request, listener ->
            val socket = object : WebSocket {
                override fun request(): Request = request
                override fun queueSize() = 0L
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
                override fun send(bytes: ByteString) = false
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text); val method = frame.string("method")
                    val params = frame.optJSONObject("params") ?: JSONObject()
                    val session = fixture.sessions.getValue(target.session)
                    val rows = session.rows("messages")
                    val result = when (method) {
                        "session.resume" -> json("session_id" to target.session, "stored_session_id" to target.session,
                            "message_count" to rows.size, "messages" to JSONArray(rows), "running" to false, "pending" to JSONArray(),
                            "revision" to revision.get(), "execution_generation" to 7, "replay_epoch" to "undo-fixture", "last_sequence" to 0,
                            "info" to session.getJSONObject("info").copy().put("profile_name", target.profile).put("profile_id", "undo-profile").put("yolo", false))
                        "session.info" -> session.getJSONObject("info").copy().put("profile_name", target.profile).put("profile_id", "undo-profile")
                        "session.events.since" -> json("epoch" to "undo-fixture", "latest_seq" to 0, "truncated" to false, "events" to JSONArray())
                        "session.mutate" -> {
                            assertEquals("rewind", params.string("operation"))
                            assertEquals(revision.get(), params.optInt("expected_revision"))
                            val id = params.getJSONObject("payload").getLong("target_message_id")
                            val index = rows.indexOfFirst { it.optLong("row_id") == id && it.string("role") == "user" }
                            check(index >= 0)
                            session.put("messages", JSONArray(rows.take(index)))
                            mutations.incrementAndGet()
                            json("session_id" to target.session, "operation" to "rewind", "revision" to revision.incrementAndGet(),
                                "execution_generation" to 7, "rewound_count" to rows.size - index,
                                "target_message" to json("id" to id, "role" to "user"))
                        }
                        "config.get" -> json("value" to if (params.string("key") == "approvals.mode") "manual" else "medium")
                        else -> JSONObject()
                    }
                    listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "result" to result).toString())
                    return true
                }
            }
            listener.onOpen(socket, Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(101).message("Synthetic").build())
            listener.onMessage(socket, json("jsonrpc" to "2.0", "method" to "event", "params" to json("type" to "gateway.ready", "payload" to json("session_authority" to true))).toString())
            socket
        }
        val bearer = dev.hermes.android.data.NativeBearerRecord("synthetic-undo-access", "synthetic-undo-refresh", System.currentTimeMillis() + 3_600_000,
            target.origin, target.owner, target.profile, target.nativeProvider)
        return BeaconClient(AuthContext(target.origin, emptyList(), target.owner, target.profile, backend = "hermes-dashboard", nativeProvider = target.nativeProvider, bearer = bearer),
            null, sockets, configureHttp = fixture.configureHttp)
    }

    @Test fun undoFormWithRefreshFalseCannotResurrectTheConfirmedRemovedReplyOnReselectOrReopen() {
        assertFalse("Never replace an active real call", app.calls.state.value.active || app.calls.service != null)
        val priorOwner = app.calls.ownerScope
        val priorPreferences = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture("undo-form-fixture")
        val scope = fixture.scope.session("chat-a")
        val priorAuth = app.auth.read(scope.origin)
        val mutations = AtomicInteger(); val callbacks = AtomicInteger()
        val client = undoClient(fixture, scope, mutations)
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        val rows = listOf(json("row_id" to 101, "role" to "user", "content" to "Undo this turn", "timestamp" to 1_800_000_001.0),
            json("row_id" to 102, "role" to "assistant", "content" to "Removed reply", "timestamp" to 1_800_000_002.0))
        fixture.sessions.getValue(scope.session).put("messages", JSONArray(rows))
        val related = json("id" to "unconfirmed-history:102", "role" to "assistant", "text" to "Removed reply", "actionable" to false,
            "display_kind" to "unconfirmed_reply", "_history_row_id" to "102", "_history_user_row_id" to "101")
        val unrelated = json("id" to "unconfirmed:earlier", "role" to "assistant", "text" to "Unrelated uncertainty", "actionable" to false,
            "display_kind" to "unconfirmed_reply", "submission_id" to "earlier-unbound")
        val raw = json("session_id" to scope.session, "profile" to scope.profile, "messages" to JSONArray(rows),
            "_unconfirmed_replies" to JSONArray().put(related).put(unrelated))
        try {
            runBlocking {
                app.preferences.edit { it[stringPreferencesKey("origin")] = "" }
                app.auth.write(client.auth)
                assertEquals(client.scope, app.auth.read(scope.origin).scope())
                app.cache.writeSession(scope, raw.toString())
            }
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
                field(vm, "originGeneration", 100); field(vm, "client", client); field(vm, "sourceScope", scope)
                app.calls.ownerScope = scope.copy(session = "")
                flow<AppState>(vm, "_ui").value = AppState(origin = scope.origin, authenticated = true, owner = scope.owner,
                    profile = scope.profile, chatProfile = scope.profile, tab = "Chat", connected = false, restoringSession = false)
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = scope.session, raw = raw,
                    messages = rows.mapIndexed { i, row -> Message.from(row, i, scope.session) }, projectionReady = true)
            }
            compose.setContent { NativeLocalization("en") { BeaconTheme {
                CompositionLocalProvider(LocalAssistantName provides "Beacon", LocalBeaconSettings provides JSONObject()) { ChatScreen(vm) }
            } } }
            compose.runOnIdle {
                vm.openForm(ActionForm("Undo last turn", "/api/session/undo", emptyList(), json("session_id" to scope.session),
                    onResult = { callbacks.incrementAndGet(); vm.selectSession(scope.session) }))
                vm.submitForm()
            }
            compose.waitUntil(15_000) { callbacks.get() == 1 && vm.chat.value.projectionReady && vm.chat.value.messages.isEmpty() }
            compose.waitForIdle()
            assertEquals(1, mutations.get())
            assertEquals(listOf("Unrelated uncertainty"), vm.chat.value.raw.rows("_unconfirmed_replies").map { it.string("text") })
            // The callback reselects this same chat; wait for that reload to settle, then read the onscreen transcript.
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("Unrelated uncertainty"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.onNode(hasText("Removed reply"), useUnmergedTree = true).assertDoesNotExist()
            compose.onNode(hasText("Unrelated uncertainty"), useUnmergedTree = true).assertExists()
            compose.onNode(hasTestTag("unconfirmed-reply:unconfirmed:earlier"), useUnmergedTree = true).assertExists()
            compose.onNode(hasText("Reply not confirmed"), useUnmergedTree = true).assertExists()
            val saved = runBlocking { JSONObject(requireNotNull(app.cache.read(scope, "session"))) }
            assertTrue(saved.rows("messages").isEmpty())
            assertEquals(listOf("Unrelated uncertainty"), saved.rows("_unconfirmed_replies").map { it.string("text") })
        } finally {
            compose.runOnUiThread { store.clear(); client.closeNative(); fixture.client.closeNative(); app.calls.ownerScope = priorOwner }
            runBlocking {
                app.cache.delete(scope, "session"); app.cache.delete(scope, "draft")
                app.preferences.updateData { priorPreferences }
                if (priorAuth.owner.isBlank() && priorAuth.bearer == null && priorAuth.cookies.isEmpty()) app.auth.remove(scope.origin) else app.auth.write(priorAuth)
            }
        }
    }

    @Test fun callThenImageThenReplySurviveArchiveFailureResetAndEncryptedReopen() {
        assertFalse("Never replace an active real call", app.calls.state.value.active || app.calls.service != null)
        val priorOwner = app.calls.ownerScope
        val priorPreferences = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture("mixed-timeline-fixture")
        val owner = fixture.scope
        val scope = owner.session("mixed-image-fixture")
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        val image = json("id" to "image-fixture", "name" to "image.png", "mime" to "image/png", "scope_key" to scope.key,
            "staged_base64" to "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a2ZkAAAAASUVORK5CYII=")
        val input = QueuedMessage("fix it please", listOf(image), id = "local-image", state = "accepted", afterRowId = "",
            admissionId = "admission-image", submissionId = "submission-image", createdAt = 1_800_000_010_000L)
        val voice = json("call_id" to "call-a", "event_id" to "call-a:user:1", "turn_id" to "1", "session_id" to scope.session,
            "role" to "user", "text" to "Earlier completed call", "final" to true, "created_at" to 1_800_000_001.0)
        val base = json("session_id" to scope.session, "profile" to scope.profile, "messages" to JSONArray(),
            "voice_transcripts" to JSONArray().put(voice), "voice_transcripts_available" to true,
            "replay_epoch" to "first-epoch", "_display_submission_id" to input.submissionId, "_display_admission_id" to input.admissionId)
        fun calls(raw: JSONObject) = archivedVoiceTranscripts(scope, json("session_id" to scope.session, "profile" to scope.profile, "events" to raw.optJSONArray("voice_transcripts")))
        try {
            runBlocking {
                app.cache.write(scope, "running-turn", "old-submission")
                app.cache.write(scope, "running-turn", "new-submission")
                assertFalse(app.cache.delete(scope, "running-turn", "old-submission"))
                assertEquals("new-submission", app.cache.read(scope, "running-turn"))
                assertTrue(app.cache.delete(scope, "running-turn", "new-submission"))
            }
            runBlocking { app.preferences.edit { it[stringPreferencesKey("origin")] = "" } }
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "sourceScope", scope)
                app.calls.ownerScope = owner
                flow<AppState>(vm, "_ui").value = AppState(origin = owner.origin, authenticated = true, owner = owner.owner,
                    profile = owner.profile, chatProfile = owner.profile, tab = "Chat", connected = false, restoringSession = false)
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = scope.session, title = "Mixed timeline", raw = base,
                    queued = listOf(input), callMessages = calls(base), streamId = scope.session, liveText = "The image is being fixed.", projectionReady = true)
            }
            compose.setContent { NativeLocalization("en") { BeaconTheme {
                CompositionLocalProvider(LocalAssistantName provides "Beacon", LocalBeaconSettings provides JSONObject()) { ChatScreen(vm) }
            } } }
            compose.onNodeWithTag("queued-message:${input.id}").assertExists()
            val callTop = compose.onNodeWithTag("call-transcript-card:call-a").fetchSemanticsNode().boundsInRoot.top
            val inputTop = compose.onNodeWithTag("queued-message:${input.id}").fetchSemanticsNode().boundsInRoot.top
            val replyTop = compose.onNodeWithTag("live-reply").fetchSemanticsNode().boundsInRoot.top
            assertTrue("Call, admitted image and reply retain their chronology", callTop < inputTop && inputTop < replyTop)
            compose.runOnIdle {
                val failed = base.copy().put("voice_transcripts_available", false).put("voice_transcripts", JSONArray())
                val before = vm.chat.value
                val refreshed = requireNotNull(liveAfterEvent(before, "state_saved", json("session" to failed), false, LiveAssistantBoundary()))
                val reset = failed.copy().put("replay_epoch", "second-epoch").put("running", false)
                    .put("pending", JSONArray().put(json("status" to "unknown")))
                val next = requireNotNull(liveAfterEvent(refreshed, "state_saved", json("replay_reset" to true, "session" to reset), false, LiveAssistantBoundary()))
                flow<ChatState>(vm, "_chat").value = next.copy(callMessages = calls(next.raw))
            }
            compose.onNodeWithTag("call-transcript-card:call-a").assertExists()
            compose.onAllNodesWithText("The image is being fixed.").assertCountEquals(1)
            compose.onNodeWithTag("live-reply").assertDoesNotExist()
            runBlocking {
                app.cache.writeSession(scope, vm.chat.value.raw.toString())
                app.cache.write(scope, "draft", Draft(queued = vm.chat.value.queued).encode(), true)
            }
            val raw = runBlocking { JSONObject(requireNotNull(app.cache.read(scope, "session"))) }
            val draft = runBlocking { Draft.decode(requireNotNull(app.cache.read(scope, "draft"))) }
            compose.runOnIdle {
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = scope.session, raw = raw, queued = draft.queued, callMessages = calls(raw))
            }
            compose.onAllNodesWithText("The image is being fixed.").assertCountEquals(1)
            assertEquals(input.submissionId, vm.chat.value.queued.single().submissionId)
            assertEquals("image.png", vm.chat.value.queued.single().attachments.single().string("name"))

            val rows = listOf(json("row_id" to 101, "role" to "user", "content" to input.text, "timestamp" to 1_800_000_010.0, "attachments" to JSONArray().put(image)),
                json("row_id" to 102, "role" to "assistant", "content" to "The image is fixed.", "timestamp" to 1_800_000_020.0))
            val proof = json("complete" to true, "submission_id" to input.submissionId, "user_row_id" to 101, "user_row_ids" to JSONArray().put(101),
                "final_assistant_row_id" to 102, "row_ids" to JSONArray().put(101).put(102))
            val receipt = json("admission_id" to input.admissionId, "status" to "terminal", "outcome" to "completed", "authority_epoch" to 3, "execution_generation" to 7)
            val wire = json("session_id" to scope.session, "replay_epoch" to "second-epoch", "seq" to 1, "type" to "message.complete",
                "admission_id" to input.admissionId, "authority_epoch" to 3, "execution_generation" to 7,
                "payload" to json("admission_id" to input.admissionId, "persisted_turn" to proof))
            val replay = json("epoch" to "second-epoch", "latest_seq" to 1, "truncated" to false, "events" to JSONArray().put(wire))
            receipt.put("_confirmed_persisted_turn", requireNotNull(nativeCompletedTurn(replay, scope.session, "second-epoch", receipt, input.submissionId, 0)))
            val linked = requireNotNull(linkedQueuedInputRow(input, receipt, rows, listOf(input)))
            compose.runOnIdle {
                val completed = raw.copy().put("messages", JSONArray(rows)).put("_unconfirmed_replies", JSONArray())
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = scope.session, raw = completed, callMessages = calls(completed),
                    messages = rows.mapIndexed { i, row -> Message.from(row, i, scope.session) }, queued = listOf(input.copy(rowId = linked, state = "settled", outcome = "completed")))
            }
            compose.onNodeWithTag("queued-message:${input.id}").assertDoesNotExist()
            compose.onNodeWithTag("message-row:101").assertExists()
            compose.onNodeWithText("The image is fixed.").assertExists()
            assertTrue(compose.onNodeWithTag("call-transcript-card:call-a").fetchSemanticsNode().boundsInRoot.top <
                compose.onNodeWithTag("message-row:101").fetchSemanticsNode().boundsInRoot.top)
            compose.runOnIdle {
                val partial = vm.chat.value.raw.copy().put("messages", JSONArray().put(rows.first())).apply {
                    remove("replay_epoch"); remove("last_sequence")
                }
                val next = requireNotNull(liveAfterEvent(vm.chat.value, "state_saved", json("session" to partial), false, LiveAssistantBoundary()))
                flow<ChatState>(vm, "_chat").value = next.copy(callMessages = calls(next.raw))
            }
            compose.onNodeWithTag("message-row:102").assertDoesNotExist()
            compose.onNodeWithTag("unconfirmed-reply:unconfirmed-history:102").assertExists()
            compose.onAllNodesWithText("The image is fixed.").assertCountEquals(1)
            runBlocking { app.cache.writeSession(scope, vm.chat.value.raw.toString()) }
            val retained = runBlocking { JSONObject(requireNotNull(app.cache.read(scope, "session"))) }
            compose.runOnIdle {
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = scope.session, raw = retained,
                    messages = retained.rows("messages").mapIndexed { i, row -> Message.from(row, i, scope.session) }, callMessages = calls(retained))
            }
            compose.onAllNodesWithText("The image is fixed.").assertCountEquals(1)
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative(); app.calls.ownerScope = priorOwner }
            runBlocking {
                app.cache.delete(scope, "session"); app.cache.delete(scope, "draft"); app.cache.delete(scope, "running-turn")
                app.preferences.updateData { priorPreferences }
                app.auth.remove(owner.origin)
            }
        }
    }
}
