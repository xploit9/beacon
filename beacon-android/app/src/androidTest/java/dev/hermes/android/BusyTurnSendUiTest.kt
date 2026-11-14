package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
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
import dev.hermes.android.ui.MoreScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real chat controls with a synthetic native authority and held acknowledgements. */
class BusyTurnSendUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val showSettings = mutableStateOf(false)
    private val rendered = mutableStateOf<BeaconViewModel?>(null)

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun withFixture(canonical: Boolean = false, catalogUnavailable: Boolean = false, block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val prior = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture().apply { canonicalAuthority = canonical; commandCatalogFailure = catalogUnavailable }
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
            compose.setContent { NativeLocalization("en") { BeaconTheme { rendered.value?.let { current -> if (showSettings.value) MoreScreen(current) else ChatScreen(current) } } } }
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

    private fun startTurn(vm: BeaconViewModel, fixture: NativeExperienceFixture, hold: Boolean = false) {
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        fixture.holdPromptAcknowledgement = hold
        compose.onNodeWithTag("message-input").performTextInput("Begin a synthetic turn")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "prompt.submit" } }
        val first = fixture.requests.first { it.string("method") == "prompt.submit" }.getJSONObject("params")
        assertEquals("runtime-chat-a", first.string("session_id"))
        assertEquals("default", first.string("profile"))
        assertEquals("Begin a synthetic turn", first.string("text"))
        if (hold) {
            assertTrue("The first acknowledgement is still held", fixture.holdPromptAcknowledgement)
            fixture.releasePromptAcknowledgement()
        }
        compose.waitUntil(15_000) { vm.chat.value.streamId == "runtime-chat-a" && fixture.liveCollectorSubscriptions.get() > 0 }
    }

    private fun awaitCatalogFailure(vm: BeaconViewModel, fixture: NativeExperienceFixture) {
        compose.waitUntil(15_000) {
            fixture.requests.any { it.string("method") == "commands.catalog" } &&
                vm.data.value["slash:catalog"]?.string("_error")?.isNotBlank() == true && "slash:catalog" !in vm.refreshing.value
        }
    }

    private fun openWatchedChat(vm: BeaconViewModel, id: String = "chat-a") {
        compose.runOnIdle { field(vm, "streamWatchMs", 150L); vm.selectSession(id) }
        val watcher = BeaconViewModel::class.java.getDeclaredField("sessionWatch").apply { isAccessible = true }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == id && vm.chat.value.projectionReady && (watcher.get(vm) as? Job)?.isActive == true }
    }

    private fun enqueueThroughComposer(text: String) {
        compose.onNodeWithTag("message-input").performTextInput("/queue $text")
        compose.onNodeWithTag("message-send").performClick()
    }

    private fun submits(fixture: NativeExperienceFixture) = fixture.requests.count { it.string("method") == "prompt.submit" }

    /** The words of a message in the chat transcript itself (not the box row above the composer, not the composer). */
    private fun inTranscript(text: String) = hasText(text) and hasAnyAncestor(hasTestTag("conversation-transcript"))

    /** One of the two ticks a message Hermes has taken shows (grey: Hermes has it; blue: a reply is on its way or follows). */
    private fun hasDeliveredTick() = hasTestTag("send-tick:delivered") or hasTestTag("send-tick:answered")

    private fun queueCanonicalHead(vm: BeaconViewModel, fixture: NativeExperienceFixture): Pair<String, String> {
        fixture.queueNextCanonicalSubmission.set(true)
        enqueueThroughComposer("First queued input")
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.let { it.state == "accepted" && it.admissionId.isNotBlank() } == true }
        val first = vm.chat.value.queued.single()
        val submitted = fixture.requests.single { it.string("method") == "prompt.submit" }.getJSONObject("params")
        assertEquals("chat-a", submitted.string("session_id")); assertEquals("default", submitted.string("profile"))
        assertEquals(first.submissionId, submitted.string("submission_id")); assertTrue(submitted.optBoolean("queued"))
        assertTrue(fixture.canonicalAdmissions.containsKey(first.admissionId))
        enqueueThroughComposer("Second queued input")
        compose.waitUntil(15_000) { vm.chat.value.queued.size == 2 && vm.composer.value.text.isBlank() }
        return first.id to first.admissionId
    }

    @Test fun terminalCanonicalAdmissionWakesTheNextPhoneMessageWithoutManualReview() = withFixture(canonical = true) { vm, fixture ->
        openWatchedChat(vm)
        val (firstId, admission) = queueCanonicalHead(vm, fixture)
        compose.onNodeWithTag("message-input").performTextInput("Newer draft stays here")
        fixture.holdPromptAcknowledgement = true
        fixture.setCanonicalAdmission(admission, "terminal", "completed")
        compose.waitUntil(10_000) { fixture.requests.any { it.string("method") == "prompt.receipt" && it.getJSONObject("params").string("admission_id") == admission } }
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "prompt.submit" } == 2 }
        val receipt = fixture.requests.first { it.string("method") == "prompt.receipt" }.getJSONObject("params")
        assertEquals("chat-a", receipt.string("session_id")); assertEquals("default", receipt.string("profile")); assertEquals(admission, receipt.string("admission_id"))
        val second = fixture.requests.filter { it.string("method") == "prompt.submit" }.last().getJSONObject("params")
        assertEquals("chat-a", second.string("session_id")); assertEquals("default", second.string("profile")); assertEquals("Second queued input", second.string("text"))
        assertEquals("settled", vm.chat.value.queued.single { it.id == firstId }.state)
        assertEquals("Newer draft stays here", vm.composer.value.text)
        assertEquals(0, fixture.liveCollectorSubscriptions.get())
    }

    /**
     * Round 13 (T1 left this case unproven): the reply-end case on the canonical fixture, where Hermes names the admission and the finished turn can be proven.
     * A message typed in Queue mode while a reply runs waits in the box and is not a chat bubble; when the reply ends it is sent by itself, exactly once, and
     * moves from the box into the chat, once, with its ticks.
     */
    @Test fun aMessageQueuedWhileAReplyRunsIsSentOnceByItselfWhenTheReplyEndsAndMovesFromTheBoxIntoTheChat() = withFixture(canonical = true) { vm, fixture ->
        openWatchedChat(vm)
        // A quiet stream asks Hermes soon, so the end of the turn is found even if the fixture's done event reaches a socket nobody reads.
        compose.runOnIdle { field(vm, "streamSilenceMs", 500L); vm.setBusySendAction("queue") }
        compose.waitUntil(15_000) { vm.preferences.value.busySendAction == "queue" && vm.preferences.value.busySendLoaded }
        // A reply is running: the first message started a turn on Hermes and the chat follows its stream.
        compose.onNodeWithTag("message-input").performTextInput("Begin a synthetic turn")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { submits(fixture) == 1 }
        compose.waitUntil(15_000) { vm.chat.value.streamId == "chat-a" && fixture.liveCollectorSubscriptions.get() > 0 }
        val admission = fixture.canonicalAdmissions.keys.single()
        val head = compose.runOnIdle { vm.chat.value.queued.single { it.admissionId == admission } }

        // 1. Typed in Queue mode while the reply runs: it is in the box and not in the chat.
        compose.onNodeWithText("Queue a message…").assertExists()
        compose.onNodeWithTag("message-input").performTextInput("Newer message while it runs")
        compose.onNodeWithTag("composer-queue-caption").assertTextEquals("Queue")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.any { it.text == "Newer message while it runs" && it.state == "queued" } && vm.composer.value.text.isBlank() }
        val id = vm.chat.value.queued.single { it.text == "Newer message while it runs" }.id
        compose.onNodeWithTag("queue-box").assertIsDisplayed()
        compose.onNodeWithTag("queue-text:$id").assertTextEquals("Newer message while it runs")
        compose.onAllNodesWithTag("queued-message:$id").assertCountEquals(0)
        compose.onAllNodes(inTranscript("Newer message while it runs"), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("Newer message while it runs").assertCountEquals(1)
        assertEquals("Hermes has only the first message", 1, submits(fixture))
        // It waits for as long as the reply runs: nothing is sent, and it stays in the box and out of the chat.
        runCatching { compose.waitUntil(2_500) { submits(fixture) > 1 } }
        assertEquals("Nothing is sent while the reply runs", 1, submits(fixture))
        compose.onNodeWithTag("queue-text:$id").assertIsDisplayed()
        compose.onAllNodesWithTag("queued-message:$id").assertCountEquals(0)

        // 2. The reply ends, as Hermes ends one: the turn is terminal and its done event carries the proof (both rows are in the saved history).
        val rows = fixture.sessions.getValue("chat-a").getJSONArray("messages")
        rows.put(json("id" to 9001, "role" to "user", "text" to "Begin a synthetic turn")).put(json("id" to 9002, "role" to "assistant", "text" to "Synthetic reply"))
        fixture.setCanonicalAdmission(admission, "terminal", "completed")
        runCatching {
            fixture.emitNativeEvent("message.complete", json("text" to "Synthetic reply", "admission_id" to admission, "status" to "completed",
                "persisted_turn" to json("complete" to true, "user_row_id" to 9001, "user_row_ids" to org.json.JSONArray(listOf(9001)),
                    "final_assistant_row_id" to 9002, "row_ids" to org.json.JSONArray(listOf(9001, 9002)))))
        }
        compose.waitUntil(30_000) { submits(fixture) == 2 }
        runCatching { compose.waitUntil(3_000) { submits(fixture) > 2 } }
        assertEquals("The queued message was sent by itself, exactly once", 2, submits(fixture))
        val sent = fixture.requests.filter { it.string("method") == "prompt.submit" }.last().getJSONObject("params")
        assertEquals("Newer message while it runs", sent.string("text")); assertEquals("chat-a", sent.string("session_id")); assertEquals("default", sent.string("profile"))
        assertNotEquals("a new submission, not the first message again", head.submissionId, sent.string("submission_id"))

        // 3. The box is empty and the message is in the chat, once, with its ticks.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("queue-box").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithTag("queue-text:$id").assertCountEquals(0)
        compose.waitUntil(15_000) { vm.chat.value.queued.firstOrNull { it.id == id }?.state in setOf("accepted", "sent", "settled") }
        compose.waitUntil(15_000) { compose.onAllNodes(inTranscript("Newer message while it runs"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(inTranscript("Newer message while it runs"), useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(hasDeliveredTick() and hasAnyAncestor(hasTestTag("queued-message:$id")), useUnmergedTree = true).assertExists()
        assertEquals("still exactly one send after the box emptied", 2, submits(fixture))
    }

    private fun retryAfterIdleWithoutStream(failFirstProbe: Boolean) = withFixture { vm, fixture ->
        fixture.nativeTurnStatus = if (failFirstProbe) "idle" else "working"
        fixture.nativeStatusFailure = failFirstProbe
        openWatchedChat(vm)
        val probesBefore = fixture.nativeStatusProbes.size
        enqueueThroughComposer("Wait for the other client")
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.state == "queued" && fixture.nativeStatusProbes.size > probesBefore }
        assertEquals(if (failFirstProbe) "failed" else "working", fixture.nativeStatusProbes[probesBefore])
        assertTrue(fixture.requests.none { it.string("method") == "prompt.submit" })
        fixture.holdPromptAcknowledgement = true
        fixture.nativeStatusFailure = false
        fixture.nativeTurnStatus = "idle"
        compose.waitUntil(10_000) { fixture.requests.any { it.string("method") == "prompt.submit" } }
        val sent = fixture.requests.single { it.string("method") == "prompt.submit" }.getJSONObject("params")
        assertEquals("runtime-chat-a", sent.string("session_id")); assertEquals("default", sent.string("profile")); assertEquals("Wait for the other client", sent.string("text"))
        assertTrue(sent.optBoolean("queued")); assertEquals(0, fixture.liveCollectorSubscriptions.get())
    }

    @Test fun externallyBusyConversationRetriesWhenItBecomesIdleWithoutAStreamCallback() = retryAfterIdleWithoutStream(false)
    @Test fun failedIdleProbeRetriesWhenTheSameConversationBecomesReachable() = retryAfterIdleWithoutStream(true)

    @Test fun removedHeadIsNotSentWhenItsHeldIdleProbeReturns() = withFixture { vm, fixture ->
        openWatchedChat(vm)
        fixture.holdActiveListAcknowledgement = true
        val before = fixture.requests.count { it.string("method") == "session.active_list" }
        enqueueThroughComposer("Removed while checking")
        compose.waitUntil(10_000) { vm.chat.value.queued.singleOrNull()?.state == "queued" && fixture.requests.count { it.string("method") == "session.active_list" } > before }
        val first = vm.chat.value.queued.single()
        compose.onNodeWithTag("queue-remove:${first.id}").performClick()
        compose.waitUntil(10_000) { vm.chat.value.queued.isEmpty() }
        enqueueThroughComposer("The replacement message")
        compose.waitUntil(10_000) { vm.chat.value.queued.singleOrNull()?.text == "The replacement message" }
        fixture.releasePromptAcknowledgement()
        compose.waitUntil(10_000) { fixture.requests.any { it.string("method") == "prompt.submit" } }
        val submitted = fixture.requests.filter { it.string("method") == "prompt.submit" }.map { it.getJSONObject("params").string("text") }
        assertEquals(listOf("The replacement message"), submitted)
    }

    @Test fun heldIdleProbeCannotSendInTheBackground() = withFixture { vm, fixture ->
        openWatchedChat(vm)
        fixture.holdActiveListAcknowledgement = true
        val before = fixture.requests.count { it.string("method") == "session.active_list" }
        enqueueThroughComposer("Wait for the foreground")
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "session.active_list" } > before }
        compose.runOnIdle { vm.foreground(false) }
        fixture.releasePromptAcknowledgement()
        compose.waitForIdle()
        assertTrue(fixture.requests.none { it.string("method") == "prompt.submit" })
        assertEquals("queued", vm.chat.value.queued.single().state)
        fixture.holdPromptAcknowledgement = true
        compose.runOnIdle { vm.foreground(true) }
        compose.waitUntil(10_000) { fixture.requests.any { it.string("method") == "prompt.submit" } }
    }

    @Test fun heldIdleProbeCannotSendAfterChangingConversations() = withFixture { vm, fixture ->
        openWatchedChat(vm)
        fixture.holdActiveListAcknowledgement = true
        val before = fixture.requests.count { it.string("method") == "session.active_list" }
        enqueueThroughComposer("Keep this in chat A")
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "session.active_list" } > before }
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        fixture.releasePromptAcknowledgement()
        compose.waitForIdle()
        assertTrue(fixture.requests.none { it.string("method") == "prompt.submit" })
        fixture.holdPromptAcknowledgement = true
        compose.runOnIdle { vm.selectSession("chat-a") }
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "prompt.submit" } }
        val sent = fixture.requests.single { it.string("method") == "prompt.submit" }.getJSONObject("params")
        assertEquals("runtime-chat-a", sent.string("session_id")); assertEquals("Keep this in chat A", sent.string("text"))
    }

    @Test fun foreignAdmissionReceiptCannotReleaseThePhoneQueue() = withFixture(canonical = true) { vm, fixture ->
        openWatchedChat(vm)
        val (firstId, admission) = queueCanonicalHead(vm, fixture)
        fixture.admissionReceiptRefOverride = "chat-b"
        val before = fixture.requests.count { it.string("method") == "prompt.receipt" }
        fixture.setCanonicalAdmission(admission, "terminal", "completed")
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "prompt.receipt" } >= before + 2 }
        assertEquals("accepted", vm.chat.value.queued.single { it.id == firstId }.state)
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
        fixture.admissionReceiptRefOverride = null
        fixture.holdPromptAcknowledgement = true
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "prompt.submit" } == 2 }
    }

    @Test fun foreignProfileReceiptCannotReleaseThePhoneQueue() = withFixture(canonical = true) { vm, fixture ->
        openWatchedChat(vm)
        val (firstId, admission) = queueCanonicalHead(vm, fixture)
        fixture.admissionReceiptProfileOverride = "synthetic-foreign-authority"
        val before = fixture.requests.count { it.string("method") == "prompt.receipt" }
        fixture.setCanonicalAdmission(admission, "terminal", "completed")
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "prompt.receipt" } >= before + 2 }
        assertEquals("accepted", vm.chat.value.queued.single { it.id == firstId }.state)
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
        fixture.admissionReceiptProfileOverride = null
        fixture.holdPromptAcknowledgement = true
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "prompt.submit" } == 2 }
    }

    @Test fun foreignAdmissionIdCannotReleaseThePhoneQueue() = withFixture(canonical = true) { vm, fixture ->
        openWatchedChat(vm)
        val (firstId, admission) = queueCanonicalHead(vm, fixture)
        fixture.admissionReceiptIdOverride = "other-admission"
        val before = fixture.requests.count { it.string("method") == "prompt.receipt" }
        fixture.setCanonicalAdmission(admission, "terminal", "completed")
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "prompt.receipt" } >= before + 2 }
        assertEquals("accepted", vm.chat.value.queued.single { it.id == firstId }.state)
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
        fixture.admissionReceiptIdOverride = null
        fixture.holdPromptAcknowledgement = true
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "prompt.submit" } == 2 }
    }

    @Test fun missingResumeProfileUsesSessionInfoOnTheReceiptSocket() = withFixture(canonical = true) { vm, fixture ->
        fixture.omitCanonicalResumeProfile = true
        openWatchedChat(vm)
        val (_, admission) = queueCanonicalHead(vm, fixture)
        fixture.holdPromptAcknowledgement = true
        fixture.setCanonicalAdmission(admission, "terminal", "completed")
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "prompt.submit" } == 2 }
        val info = fixture.requests.first { it.string("method") == "session.info" }.getJSONObject("params")
        assertEquals("chat-a", info.string("session_id")); assertEquals("default", info.string("profile"))
        val methodsBySocket = fixture.nativeSocketMethods.groupBy({ it.first }, { it.second })
        assertTrue(methodsBySocket.values.any { it.containsAll(listOf("session.resume", "session.info", "prompt.receipt")) })
    }

    @Test fun unavailableAuthorityIdentityLeavesAdmissionUnconfirmed() = withFixture(canonical = true) { vm, fixture ->
        fixture.omitCanonicalResumeProfile = true
        fixture.omitCanonicalInfoProfile = true
        openWatchedChat(vm)
        val (firstId, admission) = queueCanonicalHead(vm, fixture)
        val before = fixture.requests.count { it.string("method") == "session.info" }
        fixture.setCanonicalAdmission(admission, "terminal", "completed")
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "session.info" } >= before + 2 }
        assertEquals("accepted", vm.chat.value.queued.single { it.id == firstId }.state)
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
        fixture.omitCanonicalInfoProfile = false
        fixture.holdPromptAcknowledgement = true
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "prompt.submit" } == 2 }
    }

    @Test fun definiteModelRefusalWaitsForExplicitRetry() = withFixture { vm, fixture ->
        fixture.nativeTurnStatus = "working"
        openWatchedChat(vm)
        val before = fixture.nativeStatusProbes.size
        enqueueThroughComposer("Keep this model snapshot")
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.state == "queued" && fixture.nativeStatusProbes.size > before }
        val item = vm.chat.value.queued.single()
        fixture.sessions.getValue("chat-a").getJSONObject("info").put("model", "different-synthetic-model")
        fixture.nativeTurnStatus = "idle"
        compose.waitUntil(10_000) { vm.ui.value.notice == "The model changed. Pick this message's model to send it." && vm.chat.value.queued.singleOrNull()?.state == "queued" }
        val probes = fixture.nativeStatusProbes.size
        val metadata = fixture.requests.count { it.string("path") == "/api/sessions/chat-a" && it.string("method") == "GET" }
        compose.waitUntil(10_000) { fixture.requests.count { it.string("path") == "/api/sessions/chat-a" && it.string("method") == "GET" } >= metadata + 2 }
        assertEquals(probes, fixture.nativeStatusProbes.size)
        assertTrue(fixture.requests.none { it.string("method") == "prompt.submit" })
        fixture.sessions.getValue("chat-a").getJSONObject("info").put("model", item.model)
        fixture.holdPromptAcknowledgement = true
        compose.onNodeWithTag("queue-send:${item.id}").performClick()
        compose.waitUntil(10_000) { fixture.requests.any { it.string("method") == "prompt.submit" } }
        assertEquals("Keep this model snapshot", fixture.requests.single { it.string("method") == "prompt.submit" }.getJSONObject("params").string("text"))
    }

    @Test fun unknownCanonicalAdmissionNeverReplaysOrReleasesTheQueue() = withFixture(canonical = true) { vm, fixture ->
        openWatchedChat(vm)
        val (firstId, admission) = queueCanonicalHead(vm, fixture)
        val before = fixture.requests.count { it.string("method") == "prompt.receipt" }
        fixture.setCanonicalAdmission(admission, "unknown")
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "prompt.receipt" } >= before + 2 }
        assertTrue(vm.chat.value.queued.single { it.id == firstId }.state in setOf("accepted", "unknown"))
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
        assertEquals(admission, vm.chat.value.queued.single { it.id == firstId }.admissionId)
    }

    @Test fun startedCanonicalAdmissionFollowsItsStreamAndNativeApproval() = withFixture(canonical = true) { vm, fixture ->
        openWatchedChat(vm)
        val (_, admission) = queueCanonicalHead(vm, fixture)
        fixture.retainNativeServerRequestForReplay("chat-a", "approval", "queue-approval", json("request_id" to "queue-approval", "description" to "Synthetic approval", "choices" to org.json.JSONArray().put("once").put("deny")))
        fixture.setCanonicalAdmission(admission, "started")
        compose.waitUntil(10_000) { fixture.requests.any { it.string("method") == "prompt.receipt" } && vm.chat.value.streamId == "chat-a" && fixture.liveCollectorSubscriptions.get() > 0 }
        compose.waitUntil(10_000) { vm.chat.value.approval?.string("approval_id") == "queue-approval" }
        compose.onNodeWithText("Allow once").performScrollTo().performClick()
        compose.waitUntil(10_000) { fixture.nativeServerResponses.any { it.string("id") == "queue-approval" } }
        assertEquals("once", fixture.nativeServerResponses.single { it.string("id") == "queue-approval" }.getJSONObject("result").string("choice"))
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
        assertEquals("chat-a", vm.chat.value.streamId)
    }

    @Test fun aHeldReceiptCannotReleaseTheQueueWhileTheAppIsBackgrounded() = withFixture(canonical = true) { vm, fixture ->
        openWatchedChat(vm)
        val (firstId, admission) = queueCanonicalHead(vm, fixture)
        fixture.holdReceiptAcknowledgement = true
        val before = fixture.requests.count { it.string("method") == "prompt.receipt" }
        fixture.setCanonicalAdmission(admission, "terminal", "completed")
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "prompt.receipt" } > before }
        compose.runOnIdle { vm.foreground(false) }
        fixture.releasePromptAcknowledgement()
        compose.waitForIdle()
        assertEquals("accepted", vm.chat.value.queued.single { it.id == firstId }.state)
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
        fixture.holdPromptAcknowledgement = true
        compose.runOnIdle { vm.foreground(true) }
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "prompt.submit" } == 2 }
    }

    @Test fun aHeldReceiptCannotMutateOrSendAfterSwitchingChats() = withFixture(canonical = true) { vm, fixture ->
        openWatchedChat(vm)
        val (firstId, admission) = queueCanonicalHead(vm, fixture)
        fixture.holdReceiptAcknowledgement = true
        val before = fixture.requests.count { it.string("method") == "prompt.receipt" }
        fixture.setCanonicalAdmission(admission, "terminal", "completed")
        compose.waitUntil(10_000) { fixture.requests.count { it.string("method") == "prompt.receipt" } > before }
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        val beforeQueue = vm.chat.value.queued.map { it.id to it.state }
        compose.runOnIdle { vm.composer(TextFieldValue("Draft for chat B")) }
        fixture.releasePromptAcknowledgement()
        compose.waitForIdle()
        assertEquals("chat-b", vm.chat.value.sessionId)
        assertEquals("Draft for chat B", vm.composer.value.text)
        assertEquals(beforeQueue, vm.chat.value.queued.map { it.id to it.state })
        assertTrue(vm.chat.value.queued.none { it.id == firstId })
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
    }

    @Test fun explicitQueueAppearsInTranscriptWithoutCommandCatalog() = withFixture(catalogUnavailable = true) { vm, fixture ->
        awaitCatalogFailure(vm, fixture)
        startTurn(vm, fixture, hold = true)
        compose.onNodeWithTag("message-input").performTextInput("/queue Explicit next turn")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.isNotEmpty() || vm.ui.value.notice.isNotBlank() }
        // A queued message waits in the box above the composer; it is not in the transcript until it is sent.
        compose.onNode(hasText("Explicit next turn") and hasAnyAncestor(hasTestTag("queue-box")), useUnmergedTree = true).assertExists()
        compose.onAllNodes(hasText("Explicit next turn") and hasAnyAncestor(hasTestTag("conversation-transcript")), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("composer-queue").assertDoesNotExist()
        compose.waitUntil(15_000) { vm.composer.value.text.isBlank() }
        assertEquals("A phone queue does not submit during the running turn", 1, fixture.requests.count { it.string("method") == "prompt.submit" })
    }

    @Test fun defaultSteerShowsTheBubbleBeforeAckAndKeepsLiveOutput() = withFixture { vm, fixture ->
        fixture.steerStatus = "queued"
        startTurn(vm, fixture)
        fixture.emitNativeEvent("message.delta", json("text" to "Existing live output"))
        compose.waitUntil(15_000) { vm.chat.value.liveText == "Existing live output" }
        val collectorSocket = fixture.liveCollectorSocketId
        @Suppress("UNCHECKED_CAST")
        val streamClient = compose.runOnIdle {
            (BeaconViewModel::class.java.getDeclaredField("streamClients").apply { isAccessible = true }.get(vm) as Map<String, dev.hermes.android.data.BeaconClient>)[fixture.scope.session("chat-a").key]
        }
        assertNotNull(streamClient)
        assertEquals(fixture.scope, streamClient!!.scope)
        assertNotSame(fixture.client, streamClient)
        fixture.holdSteerAcknowledgement = true
        compose.onNodeWithTag("message-input").performTextInput("Please be concise")
        compose.onNodeWithTag("composer-queue-caption").assertTextEquals("Send now")
        compose.onNodeWithTag("message-send").assertContentDescriptionEquals("Send now").performClick()
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "session.steer" } }
        val sent = fixture.requests.single { it.string("method") == "session.steer" }.getJSONObject("params")
        assertEquals("runtime-chat-a", sent.string("session_id")); assertEquals("default", sent.string("profile")); assertEquals("Please be concise", sent.string("text"))
        assertEquals("Steer must use the live collector's socket", collectorSocket, fixture.nativeSocketMethods.single { it.second == "session.steer" }.first)
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasText("Please be concise"))
        // A steer is in the chat at once, marked Steer, with one grey tick until Hermes acknowledges it.
        val steerId = vm.chat.value.queued.single().id
        compose.onNode(hasTestTag("steer-marker:$steerId") and hasAnyAncestor(hasTestTag("queued-message:$steerId")), useUnmergedTree = true).assertExists()
        compose.onNode(hasTestTag("send-tick:pending") and hasAnyAncestor(hasTestTag("queued-message:$steerId")), useUnmergedTree = true).assertExists()
        assertEquals("Existing live output", vm.chat.value.liveText)
        assertEquals("runtime-chat-a", vm.chat.value.streamId)
        fixture.releasePromptAcknowledgement()
        compose.waitUntil(15_000) { vm.chat.value.queued.single().state == "accepted" }
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasText("Please be concise"))
        // Hermes has it and Beacon's reply is streaming now, so the two ticks are blue.
        compose.onNode(hasTestTag("send-tick:answered") and hasAnyAncestor(hasTestTag("queued-message:$steerId")), useUnmergedTree = true).assertExists()
        assertEquals("Existing live output", vm.chat.value.liveText)
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
    }

    @Test fun queuePrefixRequiresPayloadAndDoesNotCatchANearMatch() = withFixture(catalogUnavailable = true) { vm, fixture ->
        awaitCatalogFailure(vm, fixture)
        startTurn(vm, fixture)
        compose.onNodeWithTag("message-input").performTextInput("/queue")
        compose.onNodeWithTag("message-send").performClick()
        assertEquals("/queue", vm.composer.value.text)
        assertEquals("Type a message after /queue", vm.ui.value.notice)
        compose.runOnIdle { vm.notice(""); vm.composer(TextFieldValue("/queuefoo")) }
        compose.onNodeWithTag("message-send").performClick()
        assertEquals("/queuefoo", vm.composer.value.text)
        assertTrue(vm.chat.value.queued.isEmpty())
        compose.runOnIdle { vm.notice(""); vm.composer(TextFieldValue("/queue list")) }
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.any { it.text == "list" } && vm.composer.value.text.isBlank() }
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
    }

    private fun captureShot(name: String) {
        compose.waitForIdle()
        val directory = java.io.File(compose.activity.filesDir, "native-experience/queue-box").apply { mkdirs() }
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun localPreferenceWorksOfflineAndSurvivesViewModelRecreation() = withFixture { vm, _ ->
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        assertEquals("steer", vm.preferences.value.busySendAction)
        compose.runOnIdle {
            field(vm, "client", null)
            flow<AppState>(vm, "_ui").value = vm.ui.value.copy(connected = false)
            vm.more("Settings")
            showSettings.value = true
        }
        compose.onNodeWithText("If you send while Beacon replies").assertExists()
        // The two choices say what they do (Send now, Queue), so the line that explained them is gone.
        compose.onNodeWithText("Steer changes the reply now. Queue sends it after.").assertDoesNotExist()
        // The page slides in over the chat: let the transition finish and a frame draw before the picture (the first attempt caught the chat underneath).
        compose.waitForIdle(); Thread.sleep(1_000); compose.waitForIdle()
        captureShot("r7-2-setting-hint")
        // Both choices are on screen as segments: Steer starts selected, so Queue is one tap.
        compose.onNodeWithTag("choice-option:While Beacon is replying:steer").assertIsSelected()
        compose.onNodeWithTag("choice-option:While Beacon is replying:queue").performClick()
        compose.waitUntil(15_000) { vm.preferences.value.busySendAction == "queue" }
        val recreatedStore = ViewModelStore()
        lateinit var recreated: BeaconViewModel
        try {
            compose.runOnUiThread { recreated = ViewModelProvider(recreatedStore, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            compose.waitUntil(15_000) { recreated.preferences.value.busySendAction == "queue" }
        } finally { compose.runOnUiThread { recreatedStore.clear() } }
    }

    @Test fun identicalQueuedSendsKeepIdsAndDrainWhileANewerDraftExists() = withFixture { vm, fixture ->
        fixture.persistTerminalMessages = true
        compose.runOnIdle { vm.setBusySendAction("queue") }
        compose.waitUntil(15_000) { vm.preferences.value.busySendAction == "queue" }
        startTurn(vm, fixture)
        repeat(2) {
            compose.onNodeWithTag("message-input").performTextInput("Same follow-up")
            compose.onNodeWithTag("message-send").assertContentDescriptionEquals("Queue").performClick()
            compose.waitUntil(15_000) { vm.chat.value.queued.size == it + 1 && vm.composer.value.text.isBlank() }
        }
        val ids = vm.chat.value.queued.map { it.id }
        val firstTime = vm.chat.value.queued.first().createdAt
        assertEquals(2, ids.toSet().size)
        compose.onNodeWithTag("message-input").performTextInput("Same follow-up")
        fixture.holdPromptAcknowledgement = true
        fixture.nativeTurnStatus = "idle"
        fixture.emitNativeEvent("message.complete", json("text" to "First reply"))
        // 1.1.34 (93c0090b): a queued follow-up is sent once the finished turn is proven (FinishedTurnTest.aQueuedFollowUpIsSentOnlyOnceTheFinishedTurnIsProven; on the device
        // terminalCanonicalAdmissionWakesTheNextPhoneMessageWithoutManualReview). This legacy gateway names no admission, so nothing can prove the turn: both identical
        // messages stay queued with their ids, the newer draft stays in the composer, and nothing is sent until the chat is opened again.
        compose.waitUntil(15_000) { vm.chat.value.streamId.isBlank() }
        runCatching { compose.waitUntil(3_000) { fixture.requests.count { it.string("method") == "prompt.submit" } > 1 } }
        assertEquals("Nothing is sent before the turn is proven", 1, fixture.requests.count { it.string("method") == "prompt.submit" })
        assertEquals("Both identical messages stay queued with their ids", ids, vm.chat.value.queued.filter { it.state == "queued" }.map { it.id })
        assertEquals("The newer draft stays in the composer", "Same follow-up", vm.composer.value.text)
        // Where a held message shows (T1): both identical messages are rows of the queue box above the composer, never chat bubbles, until sent.
        compose.onNodeWithTag("queue-box").assertIsDisplayed()
        ids.forEach { held ->
            compose.onNodeWithTag("queue-text:$held").assertIsDisplayed()
            compose.onAllNodesWithTag("queued-message:$held").assertCountEquals(0)
        }
        compose.onAllNodes(inTranscript("Same follow-up"), useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { vm.selectSession("chat-a") }
        compose.waitUntil(15_000) { fixture.requests.count { it.string("method") == "prompt.submit" } == 2 }
        val next = fixture.requests.filter { it.string("method") == "prompt.submit" }.last().getJSONObject("params")
        assertEquals("Same follow-up", next.string("text")); assertEquals("runtime-chat-a", next.string("session_id")); assertEquals("default", next.string("profile"))
        assertTrue("An idle race must still request the native queue", next.optBoolean("queued"))
        assertEquals("Same follow-up", vm.composer.value.text)
        assertEquals(ids.first(), vm.chat.value.queued.first().id)
        // The server's row is visible before the held ACK, but its identity is not known to this send yet.
        compose.runOnIdle { vm.reconcileSend() }
        compose.waitUntil(15_000) { vm.chat.value.messages.count { it.role == "user" && it.text == "Same follow-up" } == 1 }
        assertTrue(vm.chat.value.uncertain.isNotBlank())
        fixture.releasePromptAcknowledgement()
        compose.waitUntil(15_000) { vm.chat.value.queued.first().state == "sent" }
        assertEquals(ids.first(), vm.chat.value.queued.first().id)
        assertEquals(1, vm.chat.value.messages.count { it.role == "user" && it.text == "Same follow-up" })
        assertEquals(vm.chat.value.queued.first().rowId, vm.chat.value.messages.single { it.role == "user" && it.text == "Same follow-up" }.id)
        assertEquals("Same follow-up", vm.composer.value.text)
        val saved = runBlocking { requireNotNull(app.cache.read(fixture.scope.session("chat-a"), "draft")) }.let(Draft::decode)
        assertEquals(firstTime, saved.queued.first { it.id == ids.first() }.createdAt)
        assertEquals("synthetic-model", saved.queued.first { it.id == ids.first() }.model)
        assertEquals("Same follow-up", saved.text)
        runCatching { compose.waitUntil(3_000) { fixture.requests.count { it.string("method") == "prompt.submit" } > 2 } }
        assertEquals("Opening the chat again sent the first queued message once", 2, fixture.requests.count { it.string("method") == "prompt.submit" })
        // The sent one left the box and is in the chat once, as its saved row, with its ticks; the second identical message still waits in the box, not in the chat.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("queue-text:${ids.first()}").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("queue-text:${ids[1]}").assertIsDisplayed()
        compose.onAllNodesWithTag("queued-message:${ids[1]}").assertCountEquals(0)
        val sentRow = "message-row:${vm.chat.value.queued.first { it.id == ids.first() }.rowId}"
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag(sentRow))
        compose.onAllNodes(inTranscript("Same follow-up"), useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(hasDeliveredTick() and hasAnyAncestor(hasTestTag(sentRow)), useUnmergedTree = true).assertExists()
    }

    @Test fun queuedBubbleKeepsEditAndRemoveActions() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.setBusySendAction("queue") }
        compose.waitUntil(15_000) { vm.preferences.value.busySendAction == "queue" }
        startTurn(vm, fixture)
        for (text in listOf("Edit this queued text", "Remove this queued text")) {
            compose.onNodeWithTag("message-input").performTextInput(text)
            compose.onNodeWithTag("message-send").performClick()
            compose.waitUntil(15_000) { vm.chat.value.queued.any { it.text == text } && vm.composer.value.text.isBlank() }
        }
        val first = vm.chat.value.queued.first().id
        compose.onNodeWithTag("queue-edit:$first").performClick()
        compose.waitUntil(15_000) { vm.composer.value.text == "Edit this queued text" && vm.chat.value.queued.size == 1 }
        compose.runOnIdle { vm.composer(TextFieldValue()) }
        val second = vm.chat.value.queued.single().id
        compose.onNodeWithTag("queue-remove:$second").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.isEmpty() }
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
    }

    @Test fun lateSteerReplyCannotChangeAnotherConversationOrReplay() = withFixture { vm, fixture ->
        fixture.steerStatus = "queued"
        startTurn(vm, fixture)
        fixture.holdSteerAcknowledgement = true
        compose.onNodeWithTag("message-input").performTextInput("Late guidance")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "session.steer" } }
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        compose.runOnIdle { vm.composer(TextFieldValue("Draft in the other chat")) }
        fixture.releasePromptAcknowledgement()
        compose.waitForIdle()
        assertEquals("chat-b", vm.chat.value.sessionId)
        assertEquals("Draft in the other chat", vm.composer.value.text)
        assertTrue(vm.chat.value.queued.isEmpty())
        compose.runOnIdle { vm.selectSession("chat-a") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-a" && vm.chat.value.queued.any { it.text == "Late guidance" } }
        assertEquals("unknown", vm.chat.value.queued.single { it.text == "Late guidance" }.state)
        assertEquals(1, fixture.requests.count { it.string("method") == "session.steer" })
    }

    @Test fun lateSteerReplyCannotCrossARealProfileSwitch() = withFixture { vm, fixture ->
        fixture.steerStatus = "queued"
        startTurn(vm, fixture)
        fixture.holdSteerAcknowledgement = true
        compose.onNodeWithTag("message-input").performTextInput("Default profile guidance")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "session.steer" } }
        compose.runOnIdle { vm.switchProfile("coder") }
        // The coder chat opens a moment after the switch; the late reply is released only once its scope exists.
        compose.waitUntil(30_000) { vm.ui.value.profile == "coder" && vm.client?.scope?.profile == "coder" && vm.authenticatedScope()?.profile == "coder" }
        val currentScope = vm.authenticatedScope()
        fixture.releasePromptAcknowledgement()
        compose.waitForIdle()
        assertEquals("coder", vm.ui.value.profile)
        assertEquals(currentScope, vm.authenticatedScope())
        assertTrue(vm.chat.value.queued.none { it.text == "Default profile guidance" })
        assertEquals(1, fixture.requests.count { it.string("method") == "session.steer" })
    }

    @Test fun lateSteerReplyCannotCrossAnAccountReconnect() = withFixture { vm, fixture ->
        fixture.steerStatus = "queued"
        startTurn(vm, fixture)
        fixture.holdSteerAcknowledgement = true
        compose.onNodeWithTag("message-input").performTextInput("First account guidance")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "session.steer" } }
        val replacement = NativeExperienceFixture("synthetic-other-owner")
        try {
            runBlocking { app.auth.persist(replacement.client.auth) }
            compose.runOnIdle {
                field(vm, "profileSwitchClientFactoryForTest", { auth: dev.hermes.android.data.AuthContext -> replacement.freshClientFor(auth, app.auth) })
                vm.connect(replacement.scope.origin)
            }
            compose.waitUntil(30_000) { vm.ui.value.owner == replacement.scope.owner && vm.client?.scope?.owner == replacement.scope.owner }
            val current = vm.authenticatedScope()
            fixture.releasePromptAcknowledgement()
            compose.waitForIdle()
            assertEquals(replacement.scope.owner, vm.ui.value.owner)
            assertEquals(current, vm.authenticatedScope())
            assertTrue(vm.chat.value.queued.none { it.text == "First account guidance" })
            assertEquals(1, fixture.requests.count { it.string("method") == "session.steer" })
        } finally { replacement.client.closeNative() }
    }

    @Test fun definiteLegacySteerRefusalKeepsThePayloadInThePhoneQueue() = withFixture { vm, fixture ->
        fixture.steerStatus = "rejected"
        startTurn(vm, fixture)
        compose.onNodeWithTag("message-input").performTextInput("Guidance after refusal")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.let { it.text == "Guidance after refusal" && it.kind == "queue" && it.state == "queued" } == true }
        assertEquals(1, fixture.requests.count { it.string("method") == "session.steer" })
        assertEquals(fixture.liveCollectorSocketId, fixture.nativeSocketMethods.single { it.second == "session.steer" }.first)
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
        assertEquals("runtime-chat-a", vm.chat.value.streamId)
    }

    @Test fun lostSteerAcknowledgementStaysUnknownAndIsNotReplayed() = withFixture { vm, fixture ->
        fixture.steerStatus = "queued"
        startTurn(vm, fixture)
        fixture.holdSteerAcknowledgement = true
        compose.onNodeWithTag("message-input").performTextInput("Uncertain guidance")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "session.steer" } }
        fixture.interruptActiveNativeStream()
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.state == "unknown" }
        fixture.nativeTurnStatus = "idle"
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        fixture.releasePromptAcknowledgement()
        compose.runOnIdle { vm.selectSession("chat-a") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-a" && vm.chat.value.queued.any { it.text == "Uncertain guidance" } }
        assertEquals("unknown", vm.chat.value.queued.single { it.text == "Uncertain guidance" }.state)
        assertEquals(1, fixture.requests.count { it.string("method") == "session.steer" })
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
    }

    @Test fun canonicalReceiptRemainsReviewableAfterReopeningTheConversation() = withFixture(canonical = true) { vm, fixture ->
        val item = QueuedMessage("Saved admission", state = "accepted", admissionId = "admission-synthetic")
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(queued = listOf(item)) }
        runBlocking { vm.checkpointNow() }
        val recreatedStore = ViewModelStore()
        lateinit var recreated: BeaconViewModel
        try {
            compose.runOnUiThread { recreated = ViewModelProvider(recreatedStore, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            compose.runOnIdle {
                field(recreated, "originGeneration", 101); field(recreated, "client", fixture.client)
                flow<AppState>(recreated, "_ui").value = AppState(origin = fixture.scope.origin, owner = fixture.scope.owner, profile = fixture.scope.profile, authenticated = true, connected = true, restoringSession = false)
                rendered.value = recreated; recreated.selectSession("chat-a")
            }
            // The queued copy comes back from the saved draft before the chat's history is read, and the transcript list exists only after it is:
            // the screen is waited for, not only the view model, or the first lookup below can find the skeleton.
            compose.waitUntil(15_000) {
                recreated.chat.value.sessionId == "chat-a" && recreated.chat.value.queued.any { it.id == item.id } &&
                    compose.onAllNodesWithTag("conversation-transcript").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("admission-synthetic", recreated.chat.value.queued.single { it.id == item.id }.admissionId)
            fixture.admissionReceiptStatus = "terminal"; fixture.admissionReceiptOutcome = "failed"
            val before = fixture.requests.count { it.string("method") == "prompt.receipt" }
            compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-bubble:${item.id}"))
            compose.onNodeWithTag("queued-bubble:${item.id}").performTouchInput { longClick() }
            compose.onNodeWithText("Check if it arrived").performClick()
            compose.waitUntil(15_000) { recreated.chat.value.queued.single { it.id == item.id }.state == "settled" && fixture.requests.count { it.string("method") == "prompt.receipt" } > before }
            val sent = fixture.requests.last { it.string("method") == "prompt.receipt" }.getJSONObject("params")
            assertEquals("chat-a", sent.string("session_id")); assertEquals("default", sent.string("profile")); assertEquals("admission-synthetic", sent.string("admission_id"))
            assertEquals("failed", recreated.chat.value.queued.single { it.id == item.id }.outcome)
            // Hermes started this admission (it only settles a started one as failed), so the message reached the agent: it stays in the chat with
            // two blue ticks (Hermes started it), is not offered again, and nothing is sent.
            compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:${item.id}"))
            compose.onNode(hasTestTag("send-tick:answered") and hasAnyAncestor(hasTestTag("queued-message:${item.id}")), useUnmergedTree = true).assertExists()
            compose.onAllNodesWithTag("queue-not-sent:${item.id}").assertCountEquals(0)
            compose.onAllNodesWithTag("queue-retry:${item.id}").assertCountEquals(0)
            assertTrue(fixture.requests.none { it.string("method") == "prompt.submit" })
        } finally { compose.runOnUiThread { recreatedStore.clear(); rendered.value = vm } }
    }

    @Test fun legacyPendingReceiptCanBeReviewedWithoutLosingItsDraft() = withFixture { vm, fixture ->
        val scope = fixture.scope.session("chat-a")
        val legacy = json("id" to "legacy-send", "draft" to Draft("Legacy retained text").encode()).toString()
        runBlocking { app.cache.write(scope, "pending", legacy, true) }
        compose.runOnIdle { vm.selectSession("chat-a") }
        compose.waitUntil(15_000) { vm.chat.value.uncertain == "legacy-send" && vm.chat.value.projectionReady }
        compose.runOnIdle { vm.reviewPendingSend() }
        compose.waitUntil(15_000) { vm.chat.value.uncertain.isBlank() && vm.chat.value.queued.any { it.id == "legacy-send" } }
        assertEquals("Legacy retained text", vm.chat.value.queued.single { it.id == "legacy-send" }.text)
        assertEquals("unknown", vm.chat.value.queued.single { it.id == "legacy-send" }.state)
        assertNull(runBlocking { app.cache.read(scope, "pending") })
        assertTrue(fixture.requests.none { it.string("method") == "prompt.submit" })
    }

    @Test fun aNewerComposerEditSurvivesAQueuedCheckpoint() = withFixture { vm, fixture ->
        startTurn(vm, fixture)
        val lock = app.cache.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(app.cache) as Mutex
        runBlocking { lock.lock() }
        try {
            compose.runOnIdle {
                flow<List<JSONObject>>(vm, "_attachments").value = listOf(json("id" to "attachment-a", "name" to "sample.txt", "mime" to "text/plain",
                    "scope_key" to fixture.scope.session("chat-a").key, "staged_base64" to "c2FtcGxl"))
                vm.composer(TextFieldValue("Original queued text")); vm.queue()
            }
            compose.waitUntil(15_000) { vm.chat.value.queued.isNotEmpty() }
            compose.runOnIdle { vm.composer(TextFieldValue("Newer composer text")) }
        } finally { lock.unlock() }
        compose.waitUntil(15_000) { vm.composer.value.text == "Newer composer text" && vm.chat.value.queued.singleOrNull()?.text == "Original queued text" }
        assertEquals("sample.txt", vm.chat.value.queued.single().attachments.single().string("name"))
        assertEquals("synthetic-model", vm.chat.value.queued.single().model)
        assertEquals("fixture", vm.chat.value.queued.single().provider)
        compose.waitUntil(15_000) {
            runBlocking { app.cache.read(fixture.scope.session("chat-a"), "draft")?.let(Draft::decode)?.let { it.text == "Newer composer text" && it.queued.singleOrNull()?.text == "Original queued text" } == true }
        }
    }

    @Test fun checkpointFailureKeepsTheOnlyDraftAndAttachments() = withFixture { vm, fixture ->
        startTurn(vm, fixture)
        val tooLarge = json("id" to "synthetic", "name" to "large.txt", "mime" to "text/plain", "scope_key" to fixture.scope.session("chat-a").key,
            "staged_base64" to "A".repeat(17 * 1024 * 1024))
        compose.runOnIdle { vm.composer(TextFieldValue("Keep this draft")); flow<List<JSONObject>>(vm, "_attachments").value = listOf(tooLarge); vm.queue() }
        compose.waitUntil(30_000) { vm.ui.value.notice == "Couldn't save the message. Keep the draft open and try again." && vm.chat.value.queued.isEmpty() }
        assertEquals("Keep this draft", vm.composer.value.text)
        assertEquals("large.txt", vm.attachments.value.single().string("name"))
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
    }
}
