package dev.hermes.android.ui

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import dev.hermes.android.*
import dev.hermes.android.core.*
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.voice.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Coordinator-only synthetic UI checks: no provider, real call, camera, or installation. */
class ReleaseReviewR11UiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun withVm(makeClient: (NativeExperienceFixture) -> BeaconClient = { it.client }, block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        check(InstrumentationRegistry.getArguments().getString("native_ui_probe") == "1")
        check(!app.calls.state.value.active && app.calls.service == null)
        val priorPreferences = runBlocking { app.preferences.data.first() }
        val priorOwner = app.calls.ownerScope
        val priorCall = app.calls.state.value
        runBlocking { app.preferences.edit { it[stringPreferencesKey("origin")] = "" } }
        val fixture = NativeExperienceFixture()
        val client = makeClient(fixture)
        runBlocking { app.auth.persist(client.auth) }
        val kind = "kanban-draft:default"
        val priorDraft = runBlocking { app.cache.read(fixture.scope, kind) }
        runBlocking { app.cache.delete(fixture.scope, kind) }
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        try {
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
                field(vm, "originGeneration", 100)
                field(vm, "client", client)
                field(vm, "sourceScope", fixture.scope.session("chat-a"))
                app.calls.ownerScope = fixture.scope
                app.calls.update { CallState() }
                flow<AppState>(vm, "_ui").value = AppState(origin = fixture.scope.origin, owner = fixture.scope.owner,
                    profile = fixture.scope.profile, chatProfile = fixture.scope.profile, authenticated = true, connected = true, restoringSession = false)
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = "chat-a", title = "Fixture chat", projectionReady = true,
                    raw = fixture.snapshot("chat-a").apply { remove("runtime_session_id") })
            }
            block(vm, fixture)
        } finally {
            compose.runOnUiThread { store.clear(); client.closeNative(); fixture.client.closeNative(); app.calls.update { priorCall }; app.calls.ownerScope = priorOwner }
            runBlocking {
                app.auth.remove(client.auth.origin)
                app.preferences.updateData { priorPreferences }
                if (priorDraft == null) app.cache.delete(fixture.scope, kind) else app.cache.write(fixture.scope, kind, priorDraft, true)
            }
        }
    }

    @Test fun cameraFlipOnlyAppearsForASharedFlippableCameraAndInvokesItsAction() {
        var call by mutableStateOf(CallState(active = true, state = "listening", cameraSharing = true, cameraCanFlip = true))
        var flips = 0
        compose.setContent { NativeLocalization("en") { BeaconTheme {
            LiveCallSurface(call, CallUiActions({}, {}, { flips++; call = call.copy(cameraRear = true) }, {}, {}, {}),
                motionEnabled = false, onConversation = {}, cameraPreview = {})
        } } }
        compose.onNodeWithTag("beacon-camera-flip").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, flips); assertTrue(call.cameraRear) }
        compose.runOnIdle { call = call.copy(cameraCanFlip = false) }
        compose.onNodeWithTag("beacon-camera-flip").assertDoesNotExist()
        compose.runOnIdle { call = call.copy(cameraCanFlip = true, cameraSharing = false) }
        compose.onNodeWithTag("beacon-camera-flip").assertDoesNotExist()
    }

    @Test fun searchShowsTheHiddenMatchingCallLineAndClearingItRestoresCollapse() {
        var query by mutableStateOf("")
        val lines = (1..6).map { turn -> CallTranscript("call:user:$turn", "user",
            if (turn == 1) "Hidden orchid clue" else "Line $turn", turnId = turn.toString()) }
        compose.setContent { NativeLocalization("en") { BeaconTheme { CallTranscriptCard("call", lines, searchQuery = query) } } }
        compose.onNodeWithText("Hidden orchid clue").assertDoesNotExist()
        compose.runOnIdle { query = "ORCHID" }
        compose.onNodeWithText("Hidden orchid clue").assertIsDisplayed()
        compose.runOnIdle { query = "" }
        compose.onNodeWithText("Hidden orchid clue").assertDoesNotExist()
        compose.onNodeWithTag("call-transcript-toggle:call").assertTextEquals("Show all")
    }

    @Test fun newTask404KeepsItsDraftAndDiscardRemovesTheSavedDraft() {
        val creates = AtomicInteger()
        withVm(makeClient = { fixture -> BeaconClient(fixture.client.auth, null, fixture) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                if (request.url.encodedPath == "/api/plugins/kanban/tasks" && request.method == "POST") {
                    creates.incrementAndGet()
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(404).message("Fixture")
                        .body("{\"detail\":\"Not Found\"}".toResponseBody("application/json".toMediaType())).build()
                } else chain.proceed(request)
            }
            fixture.configureHttp(builder)
        } }) { vm, _ ->
            compose.setContent { NativeLocalization("en") { BeaconTheme { KanbanScreen(vm) } } }
            compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("kanban-new-task") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("kanban-new-task").assertIsEnabled().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("kanban-title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("kanban-title").performTextReplacement("Keep my new task")
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(5000) { creates.get() == 1 && compose.onAllNodesWithText("Couldn't update this task. Refresh the board and try again.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("kanban-title").assertTextContains("Keep my new task")
            compose.onNodeWithText("Keep draft").performClick()
            compose.onNodeWithText("Continue saved task draft").assertIsDisplayed().performClick()
            compose.onNodeWithTag("kanban-title").assertTextContains("Keep my new task")
            compose.onNodeWithTag("kanban-discard").performClick()
            compose.onNodeWithText("Continue saved task draft").assertDoesNotExist()
            compose.onNodeWithTag("kanban-title").assertDoesNotExist()
        }
    }

    @Test fun tappingApprovalsInABrowsedChatNeverResumesIt() = withVm { vm, fixture ->
        compose.setContent { NativeLocalization("en") { BeaconTheme { NativeApprovalMenu(vm, enabled = true) } } }
        compose.waitUntil(5000) {
            val approvals = vm.data.value["picker:approvals"]
            approvals?.let { it.string("profile_mode") == "manual" && it.optBoolean("session_status_known") && !it.optBoolean("session_bypass") } == true &&
                "picker:approvals" !in vm.refreshing.value
        }
        // The browsed chat shows its own saved choice (not YOLO), never Unknown, and still never resumes.
        compose.onNodeWithContentDescription("Approvals: Manual").assertIsDisplayed().performClick()
        compose.onNodeWithTag("approval-ask").assertExists()
        lateinit var read: kotlinx.coroutines.Job
        compose.runOnIdle { read = vm.loadChatApprovals() }
        runBlocking { kotlinx.coroutines.withTimeout(5000) { read.join() } }
        assertTrue(fixture.requests.none { it.string("method") in setOf("session.resume", "session.create", "prompt.submit") })
    }

    @Test fun sourceCopyHidesPrivatePathsAndEditBranchRetainsTheAttachmentReference() = withVm { vm, _ ->
        val source = "Please describe this image.\n@image:/workspace/fixture.png"
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        val priorClip = clipboard.primaryClip
        try {
            compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = listOf(Message("source-message", "user", source, sessionId = "chat-a"))) }
            compose.setContent { NativeLocalization("en") { BeaconTheme { ChatScreen(vm) } } }
            compose.onNodeWithTag("message-bubble:source-message").performTouchInput { longClick() }
            compose.onNodeWithTag("copy-source-action").performClick()
            compose.runOnIdle { assertEquals("Please describe this image.", clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
            compose.onNodeWithTag("message-bubble:source-message").performTouchInput { longClick() }
            compose.onNodeWithText("Edit and branch").performClick()
            compose.waitUntil(10000) { vm.composer.value.text == source }
            assertEquals(source, vm.composer.value.text)
        } finally { if (priorClip != null) clipboard.setPrimaryClip(priorClip) else clipboard.clearPrimaryClip() }
    }

    @Test fun leavingAndReenteringChatDoesNotRepeatTheFailedCallRedirect() = withVm { vm, fixture ->
        var shown by mutableStateOf(true)
        compose.runOnIdle { app.calls.update { CallState(callId = "failed-call", failureReason = "Check the API key", conversationScope = fixture.scope.session("chat-b")) } }
        compose.setContent { NativeLocalization("en") { BeaconTheme { if (shown) ChatScreen(vm) else Text("Elsewhere") } } }
        compose.waitUntil(10000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        compose.runOnIdle { shown = false; vm.selectSession("chat-a") }
        compose.waitUntil(10000) { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
        compose.runOnIdle { shown = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("chat-a", vm.chat.value.sessionId) }
    }
}
