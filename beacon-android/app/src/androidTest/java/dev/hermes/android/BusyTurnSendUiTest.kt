package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
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
import dev.hermes.android.ui.TranscriptScrollProbe
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

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

    private fun withFixture(canonical: Boolean = false, catalogUnavailable: Boolean = false, seed: (NativeExperienceFixture) -> Unit = {}, block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val prior = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture().apply { canonicalAuthority = canonical; commandCatalogFailure = catalogUnavailable; seed(this) }
        val store = ViewModelStore()
        val scope = fixture.scope.session("chat-a")
        val cacheScopes = listOf(scope, fixture.scope.session("chat-b"))
        val cacheKinds = listOf("pending", "draft", "session", "running-turn", "live:runtime-chat-a", "live:chat-a", "live:runtime-chat-b", "live:chat-b")
        // The account's outbox list too (CHATQA-121 follow-up 5: a lost answer lists its chat), so no test leaves it to the next.
        val cacheEntries = cacheScopes.flatMap { savedScope -> cacheKinds.map { savedScope to it } } + (fixture.scope to OFFLINE_OUTBOX_KIND)
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

    /** Enough rows that the transcript is a scrolled list, not one screen. */
    private fun seedLongChat(fixture: NativeExperienceFixture, pairs: Int = 18) {
        val rows = fixture.sessions.getValue("chat-a").getJSONArray("messages")
        repeat(pairs) { i ->
            rows.put(json("role" to "user", "text" to "Seed question $i", "row_id" to 2_000L + i * 2))
            rows.put(json("role" to "assistant", "text" to "Seed answer $i.\n\nA short paragraph with enough height to make the transcript scroll.", "row_id" to 2_001L + i * 2))
        }
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

    /**
     * The return from the background before recovery reattached (owner, 24 Nov 19:08): the stream job and its live collector
     * are gone while the chat still shows the running turn (its runtime id and its tool rows). Nothing here writes Hermes.
     */
    private fun loseTheLiveCollector(vm: BeaconViewModel, fixture: NativeExperienceFixture) {
        val key = fixture.scope.session("chat-a").key
        @Suppress("UNCHECKED_CAST")
        val streams = BeaconViewModel::class.java.getDeclaredField("streams").apply { isAccessible = true }.get(vm) as MutableMap<String, Job>
        @Suppress("UNCHECKED_CAST")
        val clients = BeaconViewModel::class.java.getDeclaredField("streamClients").apply { isAccessible = true }.get(vm) as MutableMap<String, dev.hermes.android.data.BeaconClient>
        val watcher = BeaconViewModel::class.java.getDeclaredField("sessionWatch").apply { isAccessible = true }.get(vm) as? Job
        compose.waitUntil(15_000) { clients[key] != null && streams[key]?.isActive == true && vm.chat.value.streamId == "runtime-chat-a" }
        // No watcher may quietly put the collector back before the tap: this is the moment between the return and its reattach.
        runBlocking { watcher?.cancelAndJoin(); streams[key]?.cancelAndJoin() }
        compose.runOnIdle { streams.remove(key); clients.remove(key) }
        compose.waitUntil(10_000) { streams[key] == null && clients[key] == null }
        assertEquals("the chat still shows the running turn", "runtime-chat-a", vm.chat.value.streamId)
    }

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

    /**
     * S1d (R3 review #7): the box's Send takes over a passive drain whose turn look never answers. Within its own bound (EXPLICIT_DRAIN_LOOK_MS) it
     * says it could not reach Hermes, nothing is sent meanwhile, and once the held looks answer the message goes at most once.
     */
    @Test fun theBoxSendTakesOverAHungDrainSaysWhyWithinItsBoundAndSendsAtMostOnce() = withFixture { vm, fixture ->
        openWatchedChat(vm)
        fixture.holdActiveListAcknowledgement = true
        val before = fixture.requests.count { it.string("method") == "session.active_list" }
        enqueueThroughComposer("Wait for my tap")
        compose.waitUntil(10_000) { vm.chat.value.queued.singleOrNull()?.state == "queued" && fixture.requests.count { it.string("method") == "session.active_list" } > before }
        val item = vm.chat.value.queued.single()
        compose.runOnIdle { vm.notice("") }
        val tapped = android.os.SystemClock.elapsedRealtime()
        compose.onNodeWithTag("queue-send:${item.id}").performClick()
        compose.waitUntil(EXPLICIT_DRAIN_LOOK_MS + 5_000) { vm.ui.value.notice == "Reconnect before sending. Your draft is kept." }
        val took = android.os.SystemClock.elapsedRealtime() - tapped
        assertTrue("said within its bound: $took ms", took in (EXPLICIT_DRAIN_LOOK_MS - 1_000)..(EXPLICIT_DRAIN_LOOK_MS + 5_000))
        assertEquals("nothing sent while the look hung", 0, submits(fixture))
        assertEquals("queued", vm.chat.value.queued.single { it.id == item.id }.state)
        // The held looks answer now: the cancelled drain sends nothing, and the message goes at most once.
        fixture.holdActiveListAcknowledgement = false
        fixture.releasePromptAcknowledgement()
        Thread.sleep(3_000); compose.waitForIdle()
        assertTrue("never twice: ${submits(fixture)}", submits(fixture) <= 1)
    }

    /** Whether [c] holds an open native socket (its gateway's current connection). */
    private fun holdsSocket(c: dev.hermes.android.data.BeaconClient): Boolean {
        val native = c.javaClass.getDeclaredField("native").apply { isAccessible = true }.get(c)
        return native.javaClass.getDeclaredField("connection").apply { isAccessible = true }.get(native) != null
    }

    /** The chats a profile-switch test made, in both profiles, must not reopen in a later test (each switch lands on a new chat). */
    private fun forgetSwitchChats(fixture: NativeExperienceFixture, chats: List<String>, coderChats: List<String>) = runBlocking {
        val coder = fixture.scope.copy(profile = "coder")
        for (owner in listOf(fixture.scope, coder)) {
            for (kind in listOf("new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session", "conversation-index", OFFLINE_OUTBOX_KIND)) app.cache.delete(owner, kind)
        }
        for (id in chats) for (kind in listOf("client-created-shell", "session", "draft", "pending")) app.cache.delete(fixture.scope.session(id), kind)
        for (id in coderChats) for (kind in listOf("client-created-shell", "session", "draft", "pending")) app.cache.delete(coder.session(id), kind)
    }

    /**
     * S1d (R3 review #4): a profile switch closes the own client it replaces. A new chat's first send rides that client (the socket that created the
     * chat). Closed under it, its answer was lost and the message stayed unknown even after the owner's Check (A/B on 5578). So a client a send waits
     * on closes only once the answer is in: never lost, never admitted twice, the Check settles, and the next message is not refused.
     */
    @Test fun aNewChatsFirstSendInFlightAtAProfileSwitchIsAskedAgainNeverLostNeverTwice() = withFixture(canonical = true) { vm, fixture ->
        val chats = mutableListOf<String>()
        val coderChats = mutableListOf<String>()
        try {
            val words = "Synthetic first words of a new chat"
            fixture.persistTerminalMessages = true; fixture.persistCanonicalUserRows = true
            compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
            compose.runOnIdle { vm.newChat() }
            compose.waitUntil(15_000) { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
            val chat = vm.chat.value.sessionId
            chats += chat
            val owned = vm.client
            fixture.holdPromptAcknowledgement = true
            compose.runOnIdle { vm.composer(TextFieldValue(words)); vm.send() }
            compose.waitUntil(15_000) { submitParams(fixture).size == 1 }
            val submission = submitParams(fixture).single().getString("submission_id")
            compose.waitUntil(5_000) { fixture.canonicalAdmissionsFor(submission) == 1 }
            compose.runOnIdle { vm.switchProfile("coder") }
            compose.waitUntil(30_000) { vm.client?.scope?.profile == "coder" && vm.client !== owned && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
            coderChats += vm.chat.value.sessionId
            // The switch kept the replaced client's socket for the send; its answer comes now, late, on it, and then that socket closes.
            assertTrue("kept while the send waits for its answer", holdsSocket(requireNotNull(owned)))
            fixture.releasePromptAcknowledgement()
            compose.waitUntil(15_000) { !holdsSocket(owned) }
            compose.runOnIdle { vm.switchProfile("default") }
            compose.waitUntil(30_000) { vm.client?.scope?.profile == "default" && vm.chat.value.sessionId == chat && vm.chat.value.projectionReady }
            // The reopened chat asks once more under the same submission; the words show once (settled, or unknown until the Check below).
            compose.waitUntil(30_000) { submitParams(fixture).size >= 2 && vm.chat.value.messages.count { it.role == "user" && it.text == words } == 1 &&
                (vm.chat.value.uncertain.isBlank() || vm.ui.value.notice == "Tap your message to check it.") }
            Thread.sleep(2_000); compose.waitForIdle()
            assertEquals("one admission", 1, fixture.canonicalAdmissionsFor(submission))
            assertEquals("one row", 1, fixture.userRows(words))
            assertTrue("asked again only under its own submission: ${submitParams(fixture).map { it.string("submission_id") }}",
                submitParams(fixture).all { it.getString("submission_id") == submission })
            assertTrue("not in the box", vm.chat.value.queued.none { it.text == words && it.inBox() })
            assertEquals("not back in the draft", "", vm.composer.value.text)
            // The owner's check settles it (Hermes holds it once), and the next message is not refused for it.
            if (vm.chat.value.uncertain.isNotBlank()) {
                compose.onNodeWithTag("unconfirmed-bubble").performClick()
                compose.onNodeWithTag("send-check").performClick()
                compose.waitUntil(15_000) { vm.chat.value.uncertain.isBlank() }
            }
            assertEquals("still one admission", 1, fixture.canonicalAdmissionsFor(submission))
            val next = "Synthetic next words"
            compose.runOnIdle { vm.notice(""); vm.composer(TextFieldValue(next)); vm.send() }
            compose.waitUntil(15_000) { submitParams(fixture).any { it.string("text") == next } || vm.chat.value.queued.any { it.text == next } }
            assertNotEquals("Check that your last message arrived first.", vm.ui.value.notice)
        } finally { forgetSwitchChats(fixture, chats, coderChats) }
    }

    /**
     * S1d (R3 review #4): a send refused on its own client keeps that client's socket (CHAT-121 follow-up 11: a first prompt sent again needs the
     * socket that made the chat). Replaced while the send waited, nothing would use that socket again: it closes once the refusal is in.
     */
    @Test fun aRefusedFirstSendOnAReplacedClientLetsItsSocketGo() = withFixture(canonical = true) { vm, fixture ->
        val chats = mutableListOf<String>()
        val coderChats = mutableListOf<String>()
        try {
            compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
            compose.runOnIdle { vm.newChat() }
            compose.waitUntil(15_000) { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
            chats += vm.chat.value.sessionId
            val owned = requireNotNull(vm.client)
            fixture.failNextPrompt = 4002 to "Synthetic refusal"
            fixture.holdPromptAcknowledgement = true
            compose.runOnIdle { vm.composer(TextFieldValue("Synthetic words Hermes refuses")); vm.send() }
            compose.waitUntil(15_000) { submitParams(fixture).size == 1 }
            compose.runOnIdle { vm.switchProfile("coder") }
            compose.waitUntil(30_000) { vm.client?.scope?.profile == "coder" && vm.client !== owned && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
            coderChats += vm.chat.value.sessionId
            assertTrue("kept while the send waits for its answer", holdsSocket(owned))
            fixture.releasePromptAcknowledgement()
            compose.waitUntil(15_000) { !holdsSocket(owned) }
        } finally { forgetSwitchChats(fixture, chats, coderChats) }
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
        // A card that has just come up or moved takes no tap for a moment; the coordinate tap is repeated until it goes through.
        CardMoveProbe.answerWhenSettled(compose, "Allow once") { fixture.nativeServerResponses.any { it.string("id") == "queue-approval" } }
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

    /**
     * Owner, 22 Nov: "when writing during a turn there are two useless buttons named send now and stop, remove them". With words typed during a turn the
     * trailing button is the only send control and nothing else offers Stop; it steers or queues as the owner's setting says.
     */
    @Test fun duringATurnWithWordsTypedTheTrailingButtonIsTheOnlySendControl() = withFixture { vm, fixture ->
        fixture.steerStatus = "queued"
        startTurn(vm, fixture)
        fun controls(label: String) = compose.onAllNodes(hasClickAction() and (hasText(label) or hasContentDescription(label)))
        compose.onNodeWithTag("message-input").performTextInput("Steered words")
        controls("Steer").assertCountEquals(1)
        controls("Stop").assertCountEquals(0)
        compose.onNodeWithTag("message-send").assertContentDescriptionEquals("Steer").performClick()
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "session.steer" } }
        compose.waitUntil(15_000) { vm.composer.value.text.isBlank() }
        compose.runOnIdle { vm.setBusySendAction("queue") }
        compose.waitUntil(15_000) { vm.preferences.value.busySendAction == "queue" }
        compose.onNodeWithTag("message-input").performTextInput("Queued words")
        controls("Queue").assertCountEquals(1)
        controls("Steer").assertCountEquals(0)
        controls("Stop").assertCountEquals(0)
        compose.onNodeWithTag("message-send").assertContentDescriptionEquals("Queue").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.any { it.text == "Queued words" && it.kind == "queue" } }
        assertEquals("the queued words wait; only the first went as a steer", 1, fixture.requests.count { it.string("method") == "session.steer" })
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
        compose.onNodeWithTag("composer-queue-caption").assertTextEquals("Steer")
        compose.onNodeWithTag("message-send").assertContentDescriptionEquals("Steer").performClick()
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

    /**
     * Owner, 24 Nov 19:08: with the setting on Send now, a message sent mid-turn after leaving and returning showed
     * "Couldn't send it now. It's in the queue." and nothing reached Hermes. The chat still showed the turn; only its live
     * collector was gone, so the steer must bind the turn again instead of returning the words to the box.
     */
    @Test fun aSteerAfterALostLiveCollectorStillReachesTheRunningTurn() = withFixture { vm, fixture ->
        fixture.steerStatus = "queued"
        startTurn(vm, fixture)
        loseTheLiveCollector(vm, fixture)
        compose.onNodeWithTag("message-input").performTextInput("Steer through the return")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(20_000) {
            fixture.requests.any { it.string("method") == "session.steer" } ||
                vm.chat.value.queued.any { it.text == "Steer through the return" && it.state == "queued" }
        }
        assertTrue("a live turn steers; the words must not be put in the queue (notice: ${vm.ui.value.notice})",
            fixture.requests.any { it.string("method") == "session.steer" })
        assertEquals("Steer through the return", fixture.requests.single { it.string("method") == "session.steer" }.getJSONObject("params").string("text"))
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.state == "accepted" }
        assertTrue("nothing waits in the box", vm.chat.value.queued.none { it.state == "queued" })
        assertEquals(1, fixture.requests.count { it.string("method") == "session.steer" })
    }

    /** The same return, through the queue box's own Steer action. */
    @Test fun theQueueBoxSteerAfterALostLiveCollectorStillReachesTheRunningTurn() = withFixture { vm, fixture ->
        fixture.steerStatus = "queued"
        compose.runOnIdle { vm.setBusySendAction("queue") }
        compose.waitUntil(15_000) { vm.preferences.value.busySendAction == "queue" && vm.preferences.value.busySendLoaded }
        startTurn(vm, fixture)
        compose.onNodeWithTag("message-input").performTextInput("Box steer through the return")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.let { it.text == "Box steer through the return" && it.state == "queued" && it.kind == "queue" } == true && vm.composer.value.text.isBlank() }
        val id = vm.chat.value.queued.single().id
        loseTheLiveCollector(vm, fixture)
        compose.onNodeWithTag("queue-steer:$id").performClick()
        compose.waitUntil(20_000) {
            fixture.requests.any { it.string("method") == "session.steer" } ||
                (vm.chat.value.queued.singleOrNull()?.state == "queued" && vm.ui.value.notice == "Couldn't send it now. It's in the queue.")
        }
        assertTrue("the box's Steer must reach Hermes (notice: ${vm.ui.value.notice})",
            fixture.requests.any { it.string("method") == "session.steer" })
        assertEquals("Box steer through the return", fixture.requests.single { it.string("method") == "session.steer" }.getJSONObject("params").string("text"))
        compose.waitUntil(15_000) { vm.chat.value.queued.singleOrNull()?.state == "accepted" }
        assertEquals(1, fixture.requests.count { it.string("method") == "session.steer" })
    }

    /**
     * Owner, 25 Nov: after Send, the thinking/working row appeared and bumped the transcript up and down. The list must
     * follow its growing tail one way, never reverse while that row is placed; this measures the real LazyColumn scroll.
     */
    @Test fun aSentMessageDoesNotBounceTheTranscriptWhileTheThinkingRowAppears() = withFixture(seed = { seedLongChat(it) }) { vm, fixture ->
        compose.waitUntil(15_000) { vm.chat.value.projectionReady && vm.chat.value.messages.count { it.role == "user" } >= 18 }
        val list = compose.runOnIdle { TranscriptScrollProbe.state }
        assertNotNull("the transcript state", list)
        compose.waitUntil(15_000) { !list!!.canScrollForward }
        val priorAuto = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { vm.composer(TextFieldValue("Scroll check")); vm.send() }
        val offsets = mutableListOf<Float>()
        val sizes = mutableListOf<Int>()
        var token = 0
        repeat(180) { frame ->
            compose.mainClock.advanceTimeByFrame()
            // The frame clock is manual; give the app's own coroutines and stream a moment too.
            if (frame % 4 == 0) Thread.sleep(2)
            val layout = list!!.layoutInfo
            // The live row keeps its key while it grows; its top is the thinking row's position on screen.
            layout.visibleItemsInfo.firstOrNull { it.key == "live" }?.let { offsets += it.offset.toFloat(); sizes += it.size }
            if (token < 40 && frame % 2 == 0 && vm.chat.value.streamId == "runtime-chat-a" && fixture.liveCollectorSubscriptions.get() > 0) {
                runCatching { fixture.emitNativeEvent("message.delta", json("text" to "word $token ")) }
                token++
            }
        }
        compose.mainClock.autoAdvance = priorAuto
        assertTrue("the send must reach Hermes", fixture.requests.any { it.string("method") == "prompt.submit" })
        assertEquals("the thinking row must follow the send", "runtime-chat-a", vm.chat.value.streamId)
        val steps = offsets.zipWithNext { a, b -> abs(b - a) }
        val maxStep = steps.maxOrNull() ?: 0f
        val bigSteps = steps.count { it > 300f }
        var layout = list!!.layoutInfo
        var tail = layout.visibleItemsInfo.firstOrNull { it.key == "live" }
        var settled = tail != null && tail.offset + tail.size <= layout.viewportEndOffset + 1
        var settleFrames = 0
        while (!settled && settleFrames < 120) {
            compose.mainClock.advanceTimeByFrame(); settleFrames++
            layout = list.layoutInfo
            tail = layout.visibleItemsInfo.firstOrNull { it.key == "live" }
            settled = tail != null && tail.offset + tail.size <= layout.viewportEndOffset + 1
        }
        java.io.File(app.filesDir, "scroll-probe.txt").writeText("offsets=$offsets\nsizes=$sizes\nsteps=$steps\nmaxStep=$maxStep\nbigSteps=$bigSteps\nsettled=$settled\ntokens=$token\n")
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            java.io.File(app.filesDir, "scroll-capture.png").outputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
        }
        assertTrue("the thinking row did not settle in view: $settled", settled)
        // One 300 px+ step stays: the ack replaces the pending bubble with the sent one and the tail is pinned to the bottom.
        // The follow itself adds no jump: the base has two (that replacement plus its snap), the fixed build must have one.
        assertTrue("transcript had $bigSteps jumps over 300 px while thinking: $steps", bigSteps <= 1)
    }

    /**
     * Owner, 26 Nov (independent review 1.2.4): a chat that composes empty must arm follow when its first row and first streamed
     * words arrive. The v2 effect keyed only on anchorRestored/follow/search ran once against the empty chat and never re-armed,
     * so the reply streamed past the bottom with no follow and no Scroll-to-latest (follow stayed true).
     */
    @Test fun aFreshChatArmsFollowForItsFirstMessageAndStreamingReply() = withFixture(
        seed = { it.sessions.getValue("chat-a").put("messages", org.json.JSONArray()) },
    ) { vm, fixture ->
        compose.waitUntil(15_000) { vm.chat.value.projectionReady && vm.chat.value.messages.isEmpty() && vm.chat.value.liveText.isEmpty() }
        val list = compose.runOnIdle { TranscriptScrollProbe.state }
        assertNotNull("the transcript state", list)
        val widthDp = compose.runOnIdle { with(compose.activity.resources.displayMetrics) { widthPixels / density } }
        assertTrue("this proof runs at phone width, not $widthDp dp", widthDp < 600f)
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        compose.onNodeWithTag("message-input").performTextInput("First words in a new chat")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "prompt.submit" } }
        compose.waitUntil(15_000) { vm.chat.value.streamId == "runtime-chat-a" && fixture.liveCollectorSubscriptions.get() > 0 }
        // The first user row lands before the reply; the effect has to re-arm on that transition, not on every token.
        compose.waitUntil(15_000) { vm.chat.value.messages.any { it.role == "user" && it.text == "First words in a new chat" } }
        val lines = 40
        repeat(lines) { i -> fixture.emitNativeEvent("message.delta", json("text" to "Streamed line $i with enough words to make the reply taller than the phone.\n\n")) }
        compose.waitUntil(15_000) { vm.chat.value.liveText.contains("Streamed line ${lines - 1}") }
        val followed = runCatching { compose.waitUntil(20_000) { !list!!.canScrollForward && list.canScrollBackward } }.isSuccess
        val atBottom = list!!.layoutInfo
        assertTrue(
            "a fresh chat must follow its first streamed reply (first visible ${list.firstVisibleItemIndex} of ${atBottom.totalItemsCount}, " +
                "canScrollForward=${list.canScrollForward}, canScrollBackward=${list.canScrollBackward})",
            followed,
        )
        val tail = atBottom.visibleItemsInfo.firstOrNull { it.key == "live" }
        assertNotNull("the streamed tail is in view", tail)
        assertTrue("the streamed tail ends at the viewport bottom", tail!!.offset + tail.size <= atBottom.viewportEndOffset + 2)
        // Scrolling up still turns follow off and offers the way back.
        compose.onNodeWithTag("conversation-transcript").performTouchInput { swipeDown() }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("scroll-to-latest").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("scroll-to-latest").performClick()
        compose.waitUntil(15_000) { !list.canScrollForward && compose.onAllNodesWithTag("scroll-to-latest").fetchSemanticsNodes().isEmpty() }
        runCatching {
            val directory = java.io.File(app.filesDir, "scroll-fix-124").apply { mkdirs() }
            java.io.File(directory, "fresh-chat-follow.txt").writeText(
                "widthDp=$widthDp\nitems=${list.layoutInfo.totalItemsCount}\ncanScrollForward=${list.canScrollForward}\n" +
                    "canScrollBackward=${list.canScrollBackward}\nfirstVisible=${list.firstVisibleItemIndex}\ntail=${tail.key}\n",
            )
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            java.io.File(directory, "fresh-chat-follow.png").outputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
        }
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
        // The two choices say what they do (Steer, Queue), so the line that explained them is gone.
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
            // two grey ticks (it was never answered: Root's rule, TURN-121), is not offered again, and nothing is sent.
            compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-message:${item.id}"))
            compose.onNode(hasTestTag("send-tick:delivered") and hasAnyAncestor(hasTestTag("queued-message:${item.id}")), useUnmergedTree = true).assertExists()
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

    /** CHATQA-121 follow-up 3: a chat Hermes runs a reply in, which this phone never sent (the outbox, another device, a schedule), seeded with the person's row only. */
    private fun holdReplyFromElsewhere(fixture: NativeExperienceFixture, id: String = "chat-held") {
        fixture.addProfileConversation(fixture.scope.profile, id, "Held reply")
        fixture.nativeTurnStatus = "working"
        fixture.holdUnseenTurn(id)
    }
    private fun forgetHeldChat(fixture: NativeExperienceFixture, id: String = "chat-held") {
        fixture.unseenTurns.clear()
        val held = fixture.scope.session(id)
        val runtime = fixture.unseenRuntime(id)
        runBlocking { listOf("session", "draft", "pending", "running-turn", "selected-session", "live:$id", "live:$runtime").forEach { app.cache.delete(held, it) } }
    }
    private fun methodCount(fixture: NativeExperienceFixture, method: String) = fixture.requests.count { it.string("method") == method }

    /**
     * CHATQA-121 follow-up 3: opening a chat while Hermes runs a reply this phone never sent shows it in progress with Stop at once, keeps it so while
     * Hermes holds the turn silent (a rate-limited model) across several quiet windows, then follows its stream. Opening it sends nothing.
     */
    @Test fun aChatOpenedWhileAReplyFromElsewhereRunsShowsItInProgressAndFollowsIt() = withFixture(canonical = true) { vm, fixture ->
        try {
            holdReplyFromElsewhere(fixture)
            field(vm, "streamSilenceMs", 1_000L)
            val submits = methodCount(fixture, "prompt.submit")
            val subscriptions = fixture.streamSubscriptions.get()
            compose.runOnIdle { vm.selectSession("chat-held") }
            compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-held" && vm.chat.value.projectionReady && vm.chat.value.streamId == "chat-held" }
            compose.onNodeWithTag("message-stop").assertIsDisplayed().assertIsEnabled()
            // Admitted, no stream for a while: still in progress after several quiet windows.
            Thread.sleep(4_000); compose.waitForIdle()
            assertEquals("chat-held", vm.chat.value.streamId)
            compose.onNodeWithTag("message-stop").assertIsDisplayed()
            // Then it streams.
            compose.waitUntil(15_000) { fixture.streamSubscriptions.get() > subscriptions }
            fixture.emitNativeEvent("message.delta", json("text" to "Words from the held reply"))
            compose.waitUntil(15_000) { vm.chat.value.liveText.contains("Words from the held reply") }
            fixture.persistTerminalMessages = true
            fixture.nativeTurnStatus = "idle"
            fixture.emitNativeEvent("message.complete", json("text" to "Words from the held reply, done."))
            compose.waitUntil(15_000) { vm.chat.value.streamId.isBlank() && vm.chat.value.messages.any { it.text.contains("Words from the held reply, done.") } }
            assertEquals("Opening the chat sent nothing", submits, methodCount(fixture, "prompt.submit"))
        } finally { forgetHeldChat(fixture) }
    }

    /** CHATQA-121 follow-up 3: Stop on that reply interrupts its runtime (an older gateway), and nothing is sent. */
    @Test fun stopOnAReplyFromElsewhereInterruptsItsRuntime() = withFixture { vm, fixture ->
        try {
            holdReplyFromElsewhere(fixture)
            val runtime = fixture.unseenRuntime("chat-held")
            compose.runOnIdle { vm.selectSession("chat-held") }
            compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-held" && vm.chat.value.streamId == runtime }
            compose.onNodeWithTag("message-stop").assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "session.interrupt" } }
            assertEquals(listOf(runtime), fixture.requests.filter { it.string("method") == "session.interrupt" }.map { it.getJSONObject("params").string("session_id") })
            assertEquals("Nothing sent", 0, methodCount(fixture, "prompt.submit"))
            assertTrue("No error: ${vm.ui.value.error}", vm.ui.value.error.isBlank())
        } finally { fixture.nativeTurnStatus = "idle"; forgetHeldChat(fixture) }
    }

    /** CHATQA-121 follow-up 3: on a Hermes with session authority, Stop on that reply interrupts its running generation and Hermes confirms it. */
    @Test fun stopOnACanonicalReplyFromElsewhereInterruptsItsGeneration() = withFixture(canonical = true) { vm, fixture ->
        try {
            holdReplyFromElsewhere(fixture)
            compose.runOnIdle { vm.selectSession("chat-held") }
            compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-held" && vm.chat.value.streamId == "chat-held" }
            compose.onNodeWithTag("message-stop").assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "session.interrupt" } }
            val interrupt = fixture.requests.single { it.string("method") == "session.interrupt" }.getJSONObject("params")
            assertEquals("chat-held", interrupt.string("session_id"))
            assertEquals(7, interrupt.optInt("execution_generation", -1))
            Thread.sleep(1_000); compose.waitForIdle()
            assertTrue("Hermes confirmed the stop: ${vm.ui.value.error}", vm.ui.value.error.isBlank())
            assertEquals("Nothing sent", 0, methodCount(fixture, "prompt.submit"))
        } finally { fixture.nativeTurnStatus = "idle"; forgetHeldChat(fixture) }
    }

    /** CHATQA-121 follow-up 3: an open chat that ends on a reply is never asked about; when another device starts a turn in it, Hermes saves the person's row and the chat follows the reply. */
    @Test fun anOpenChatFollowsAReplyAnotherDeviceStartsInIt() = withFixture(canonical = true) { vm, fixture ->
        try {
            field(vm, "streamWatchMs", 300L)
            compose.runOnIdle { vm.selectSession("chat-a") }
            compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady && vm.chat.value.streamId.isBlank() }
            Thread.sleep(1_000); compose.waitForIdle()
            val looks = methodCount(fixture, "session.active_list")
            Thread.sleep(1_500); compose.waitForIdle()
            assertEquals("An idle chat that ends on a reply is not asked about", looks, methodCount(fixture, "session.active_list"))
            val subscriptions = fixture.streamSubscriptions.get()
            fixture.nativeTurnStatus = "working"
            fixture.sessions.getValue("chat-a").getJSONArray("messages").put(json("role" to "user", "text" to "Sent from another device", "row_id" to 12))
            fixture.holdUnseenTurn("chat-a")
            compose.waitUntil(15_000) { vm.chat.value.streamId == "chat-a" }
            compose.onNodeWithTag("message-stop").assertIsDisplayed()
            compose.waitUntil(15_000) { fixture.streamSubscriptions.get() > subscriptions }
            fixture.emitNativeEvent("message.delta", json("text" to "Answer to the other device"))
            compose.waitUntil(15_000) { vm.chat.value.liveText.contains("Answer to the other device") }
            assertEquals("Nothing sent", 0, methodCount(fixture, "prompt.submit"))
        } finally { fixture.nativeTurnStatus = "idle"; fixture.unseenTurns.clear() }
    }

    /** CHATQA-121 follow-up 3: a chat whose history stops inside a turn while Hermes runs nothing there shows no reply in progress; the look only lists, it never resumes. */
    @Test fun aChatWhoseHistoryStopsInsideATurnShowsNothingWhileHermesIsIdle() = withFixture(canonical = true) { vm, fixture ->
        try {
            fixture.addProfileConversation(fixture.scope.profile, "chat-held", "Held reply")
            val looks = methodCount(fixture, "session.active_list")
            val resumes = methodCount(fixture, "session.resume")
            compose.runOnIdle { vm.selectSession("chat-held") }
            compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-held" && vm.chat.value.projectionReady && methodCount(fixture, "session.active_list") > looks }
            Thread.sleep(1_500); compose.waitForIdle()
            assertTrue(vm.chat.value.streamId.isBlank())
            compose.onNodeWithTag("message-stop").assertDoesNotExist()
            assertEquals("Browsing never resumes", resumes, methodCount(fixture, "session.resume"))
            assertEquals("Nothing sent", 0, methodCount(fixture, "prompt.submit"))
        } finally { forgetHeldChat(fixture) }
    }

    /**
     * CHATQA-121 follow-up 4: the network drops while a send's answer is on its way (Hermes took it and keeps the person's row; the phone never hears
     * back), so the send is "Not confirmed yet". Remove this copy removes the phone's copy at once and sends nothing; reopening the chat (its saved
     * records read again) brings nothing back, and Hermes' own row of the message shows once.
     */
    @Test fun removingAnUnconfirmedSendRemovesItAtOnceAndNeverSendsIt() = withFixture { vm, fixture ->
        val scope = fixture.scope.session("chat-a")
        val words = "Words lost on the way"
        fun copies() = vm.chat.value.queued.count { it.text == words }
        fun hermesRows() = vm.chat.value.messages.count { it.role == "user" && it.text == words }
        fun submits() = fixture.requests.count { it.string("method") == "prompt.submit" }
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        fixture.persistTerminalMessages = true
        fixture.holdPromptAcknowledgement = true
        compose.onNodeWithTag("message-input").performTextInput(words)
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { submits() == 1 }
        fixture.dropHeldAcknowledgements()
        fixture.nativeTurnStatus = "idle"
        compose.waitUntil(15_000) { vm.chat.value.uncertain.isNotBlank() && vm.chat.value.status != "Sending" }
        compose.onNodeWithTag("unconfirmed-bubble").assertIsDisplayed().performClick()
        compose.onNodeWithTag("send-dismiss").assertIsDisplayed().performClick()
        // At once: the bubble and every saved trace of the copy are gone.
        compose.waitUntil(2_000) { vm.chat.value.uncertain.isBlank() && copies() == 0 }
        compose.onNodeWithTag("unconfirmed-bubble").assertDoesNotExist()
        assertNull("the saved send record", runBlocking { app.cache.read(scope, "pending") })
        assertEquals("the box's saved copy", 0, runBlocking { app.cache.read(scope, "draft")?.let(Draft::decode)?.queued.orEmpty() }.count { it.text == words })
        // Nothing sends it, and opening the chat again (its saved records read again) brings nothing back.
        Thread.sleep(3_000); compose.waitForIdle()
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        compose.runOnIdle { vm.selectSession("chat-a") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady && hermesRows() == 1 }
        Thread.sleep(2_000); compose.waitForIdle()
        assertTrue("no unconfirmed send came back", vm.chat.value.uncertain.isBlank())
        assertEquals("no copy came back", 0, copies())
        assertEquals("never sent again", 1, submits())
        // Hermes kept it: its own row shows it, once.
        assertEquals(1, hermesRows())
        compose.onNodeWithTag("unconfirmed-bubble").assertDoesNotExist()
    }

    /**
     * CHATQA-121 follow-up 4: the same for a message the box sent. Its copy in the box is saved in the chat's draft (where TURN's hold also lives),
     * so Remove takes that saved copy too, and reopening the chat brings nothing back.
     */
    @Test fun removingAnUnconfirmedSendFromTheBoxRemovesItsSavedCopy() = withFixture { vm, fixture ->
        val scope = fixture.scope.session("chat-a")
        val words = "Box words lost on the way"
        fun copies() = vm.chat.value.queued.count { it.text == words }
        fun savedCopies() = runBlocking { app.cache.read(scope, "draft")?.let(Draft::decode)?.queued.orEmpty() }.count { it.text == words }
        fun submits() = fixture.requests.count { it.string("method") == "prompt.submit" }
        compose.runOnIdle { vm.setBusySendAction("queue") }
        compose.waitUntil(15_000) { vm.preferences.value.busySendAction == "queue" }
        startTurn(vm, fixture)
        compose.onNodeWithTag("message-input").performTextInput(words)
        compose.onNodeWithTag("message-send").assertContentDescriptionEquals("Queue").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.any { it.text == words && it.state == "queued" } }
        fixture.nativeTurnStatus = "idle"
        fixture.emitNativeEvent("message.complete", json("text" to "First reply"))
        compose.waitUntil(15_000) { vm.chat.value.streamId.isBlank() }
        // The box sends it once the chat is opened again (this gateway proves the finished turn only then), and the network drops on the way back.
        fixture.holdPromptAcknowledgement = true
        compose.runOnIdle { vm.selectSession("chat-a") }
        compose.waitUntil(15_000) { submits() == 2 }
        fixture.dropHeldAcknowledgements()
        compose.waitUntil(15_000) { vm.chat.value.queued.any { it.text == words && it.state == "unknown" } && vm.chat.value.uncertain.isNotBlank() && vm.chat.value.status != "Sending" }
        val id = vm.chat.value.queued.single { it.text == words }.id
        assertEquals("the box's copy is saved", 1, savedCopies())
        compose.onNodeWithTag("queued-bubble:$id").performScrollTo().performClick()
        compose.onNodeWithTag("send-dismiss").assertIsDisplayed().performClick()
        compose.waitUntil(2_000) { vm.chat.value.uncertain.isBlank() && copies() == 0 }
        assertNull("the saved send record", runBlocking { app.cache.read(scope, "pending") })
        assertEquals("the box's saved copy", 0, savedCopies())
        Thread.sleep(2_000); compose.waitForIdle()
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        compose.runOnIdle { vm.selectSession("chat-a") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
        Thread.sleep(2_000); compose.waitForIdle()
        assertTrue("no unconfirmed send came back", vm.chat.value.uncertain.isBlank())
        assertEquals("no copy came back", 0, copies())
        assertEquals("never sent again", 2, submits())
    }

    /** CHATQA-121 follow-up 5: Hermes answers again after it was out of reach (the reach check's banner comes and goes), as when the connection comes back. */
    private fun hermesAnswersAgain(vm: BeaconViewModel, fixture: NativeExperienceFixture) {
        val unreachable = BeaconViewModel::class.java.getDeclaredField("hermesWasUnreachable").apply { isAccessible = true }
        // Any answer from Hermes clears the mark, so it is set again until the app has seen it.
        compose.waitUntil(10_000) {
            dev.hermes.android.data.HermesReach.failed(fixture.scope.origin, java.net.ConnectException("Synthetic: Hermes out of reach"))
            unreachable.getBoolean(vm)
        }
        dev.hermes.android.data.HermesReach.answered(fixture.scope.origin)
        compose.waitUntil(10_000) { !unreachable.getBoolean(vm) }
    }
    private fun submitParams(fixture: NativeExperienceFixture) = fixture.requests.filter { it.string("method") == "prompt.submit" }.map { it.getJSONObject("params") }
    /** The submit's payload as Hermes digests it (the runtime handle aside). */
    private fun payload(params: JSONObject) = params.copy().apply { remove("session_id") }.toString()

    /**
     * Owner 17:07 (S1): Send with a photo while the chat's model believes the app is not in front, one of the conditions that stop send() before any
     * request. It used to vanish while the composer, which still held it, showed it again with no word (the owner's video, frame by frame); now it waits
     * in the box as Not sent with its words and photo, the composer is empty, nothing reached Hermes, and the chat says so. Back in front (onStart,
     * which a relaunch used to skip: ForegroundAfterRelaunchDeviceTest), it goes by itself, once, with its photo.
     */
    @Test fun aPhotoSendStoppedBeforeHermesWaitsInTheBoxAsNotSentNeverBackInTheDraft() = withFixture(canonical = true) { vm, fixture ->
        val words = "Synthetic long request with a photo"
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        compose.runOnIdle {
            flow<List<JSONObject>>(vm, "_attachments").value = listOf(json("id" to "00000000-0000-4000-8000-000000000007", "name" to "Photo.png", "mime" to "image/png",
                "scope_key" to fixture.scope.session("chat-a").key, "staged_base64" to "iVBORw0KGgo="))
        }
        compose.onNodeWithTag("message-input").performTextInput(words)
        try {
            compose.runOnIdle { vm.foreground(false) }
            compose.onNodeWithTag("message-send").performClick()
            compose.waitUntil(15_000) { vm.chat.value.queued.any { it.text == words } }
            val row = vm.chat.value.queued.single { it.text == words }
            assertTrue("Not sent", queueBoxSaysNotSent(row) && row.waitingForConnection())
            assertEquals("its photo stays with it", "Photo.png", row.attachments.single().string("name"))
            assertEquals("never back in the draft", "", vm.composer.value.text)
            assertTrue(vm.attachments.value.isEmpty())
            compose.waitUntil(15_000) { vm.chat.value.uncertain.isBlank() && vm.ui.value.notice.isNotBlank() }
            assertEquals("Couldn't send it now. It's in the queue.", vm.ui.value.notice)
            assertEquals("never back in the draft once the send let go", "", vm.composer.value.text)
            assertEquals("nothing reached Hermes", 0, submitParams(fixture).size)
            // In front again (the activity's onStart): the copy in the box goes by itself, once, with its photo, and no tap.
            compose.runOnIdle { vm.foreground(true) }
            compose.waitUntil(20_000) { submitParams(fixture).size == 1 }
            compose.waitUntil(15_000) { vm.chat.value.queued.none { it.text == words && it.inBox() } }
            assertEquals("its photo went with it", 1, fixture.requests.count { it.string("method") == "image.attach_bytes" })
            Thread.sleep(2_000); compose.waitForIdle()
            assertEquals("sent once", 1, submitParams(fixture).size)
            assertEquals("", vm.composer.value.text)
        } finally { runBlocking { app.cache.delete(fixture.scope, OFFLINE_OUTBOX_KIND) } }
    }

    /**
     * CHATQA-121 follow-up 5: Hermes took the send (its admission and the person's row), and the network dropped before its answer came back.
     * When Hermes answers again the send is asked once more under the same submission id with exactly the same payload, its staged image too
     * (never staged again); Hermes answers with the admission it holds, and the bubble follows it like any accepted send: the reply comes, and
     * Hermes keeps one admission and one row, shown once.
     */
    @Test fun aSendWhoseAnswerWasLostIsAskedAgainAndFollowsTheAdmissionHermesHolds() = withFixture(canonical = true) { vm, fixture ->
        val words = "Synthetic words whose answer was lost"
        fun stagings() = fixture.requests.count { it.string("method") == "image.attach_bytes" }
        try {
            compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
            fixture.persistTerminalMessages = true; fixture.persistCanonicalUserRows = true
            fixture.holdPromptAcknowledgement = true
            compose.runOnIdle {
                flow<List<JSONObject>>(vm, "_attachments").value = listOf(json("id" to "00000000-0000-4000-8000-000000000005", "name" to "photo.png", "mime" to "image/png",
                    "scope_key" to fixture.scope.session("chat-a").key, "staged_base64" to "iVBORw0KGgo="))
            }
            compose.onNodeWithTag("message-input").performTextInput(words)
            compose.onNodeWithTag("message-send").performClick()
            compose.waitUntil(15_000) { submitParams(fixture).size == 1 }
            val first = submitParams(fixture).single()
            val submission = first.getString("submission_id")
            assertEquals("Hermes took it", 1, fixture.canonicalAdmissionsFor(submission))
            fixture.dropHeldAcknowledgements()
            // Only the socket dropped, so Hermes answers at once: asked again, under the same identity and payload, with the staged image's path.
            compose.waitUntil(20_000) { submitParams(fixture).size == 2 }
            val second = submitParams(fixture)[1]
            assertEquals(submission, second.getString("submission_id"))
            assertEquals("the same payload", payload(first), payload(second))
            assertEquals("the staged image is sent again, never staged again", 1, stagings())
            compose.waitUntil(20_000) { vm.chat.value.uncertain.isBlank() && vm.chat.value.streamId.isNotBlank() }
            // The stream the app follows is subscribed a moment after the bubble settles.
            compose.waitUntil(15_000) { fixture.activeRuntimeForTest() != null }
            fixture.nativeTurnStatus = "idle"
            fixture.emitNativeEvent("message.complete", json("text" to "Synthetic reply"))
            compose.waitUntil(20_000) { vm.chat.value.streamId.isBlank() && vm.chat.value.messages.any { it.role == "assistant" && it.text == "Synthetic reply" } }
            compose.waitUntil(15_000) { vm.chat.value.messages.count { it.role == "user" && it.text == words } == 1 }
            assertEquals("one admission", 1, fixture.canonicalAdmissionsFor(submission))
            assertEquals("one row", 1, fixture.userRows(words))
            assertNull("no saved send record", runBlocking { app.cache.read(fixture.scope.session("chat-a"), "pending") })
            Thread.sleep(2_000); compose.waitForIdle()
            assertEquals("asked once", 2, submitParams(fixture).size)
            compose.onNodeWithTag("unconfirmed-bubble").assertDoesNotExist()
        } finally { runBlocking { app.cache.delete(fixture.scope, OFFLINE_OUTBOX_KIND) } }
    }

    /**
     * CHATQA-121 follow-up 5, the demo case: the network dropped as the send went, before Hermes read it (no admission, no row). When Hermes answers
     * again it is asked once more under the same identity: Hermes admits it once, the reply comes, and Hermes keeps one admission and one row.
     */
    @Test fun aSendHermesNeverGotIsAskedAgainAndGoesExactlyOnce() = withFixture(canonical = true) { vm, fixture ->
        val words = "Synthetic words dropped before they left"
        try {
            compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
            fixture.persistTerminalMessages = true; fixture.persistCanonicalUserRows = true
            fixture.dropNextPromptUnread = true
            compose.onNodeWithTag("message-input").performTextInput(words)
            compose.onNodeWithTag("message-send").performClick()
            compose.waitUntil(20_000) { fixture.unreadPrompts.get() == 1 && submitParams(fixture).size == 1 }
            val submission = submitParams(fixture).single().getString("submission_id")
            compose.waitUntil(20_000) { vm.chat.value.uncertain.isBlank() && vm.chat.value.streamId.isNotBlank() }
            // The stream the app follows is subscribed a moment after the bubble settles.
            compose.waitUntil(15_000) { fixture.activeRuntimeForTest() != null }
            fixture.nativeTurnStatus = "idle"
            fixture.emitNativeEvent("message.complete", json("text" to "Synthetic reply to the dropped words"))
            compose.waitUntil(20_000) { vm.chat.value.streamId.isBlank() && vm.chat.value.messages.any { it.role == "assistant" && it.text == "Synthetic reply to the dropped words" } }
            compose.waitUntil(15_000) { vm.chat.value.messages.count { it.role == "user" && it.text == words } == 1 }
            assertEquals("one admission", 1, fixture.canonicalAdmissionsFor(submission))
            assertEquals("one row", 1, fixture.userRows(words))
            Thread.sleep(2_000); compose.waitForIdle()
            assertEquals("Hermes got it once", 1, submitParams(fixture).size)
            compose.onNodeWithTag("unconfirmed-bubble").assertDoesNotExist()
        } finally { runBlocking { app.cache.delete(fixture.scope, OFFLINE_OUTBOX_KIND) } }
    }

    /**
     * CHATQA-121 follow-up 5: the turn finished while the send's answer was lost (Hermes ran it and wrote its reply). Asked again, Hermes answers with
     * the finished admission ("terminal"): the bubble is confirmed, the reply shows, and the message shows once.
     */
    @Test fun aSendWhoseTurnFinishedWhileItsAnswerWasLostShowsItsReply() = withFixture(canonical = true) { vm, fixture ->
        val words = "Synthetic words whose turn finished meanwhile"
        try {
            compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
            fixture.persistTerminalMessages = true; fixture.persistCanonicalUserRows = true
            fixture.holdPromptAcknowledgement = true
            compose.onNodeWithTag("message-input").performTextInput(words)
            compose.onNodeWithTag("message-send").performClick()
            compose.waitUntil(15_000) { submitParams(fixture).size == 1 }
            val submission = submitParams(fixture).single().getString("submission_id")
            // Hermes runs the turn to its end and writes the reply while the answer is on its way.
            val admission = fixture.canonicalAdmissions.keys.single()
            fixture.setCanonicalAdmission(admission, "terminal", "completed")
            fixture.sessions.getValue("chat-a").getJSONArray("messages").put(json("role" to "assistant", "text" to "Synthetic finished reply", "row_id" to 990_001))
            fixture.dropHeldAcknowledgements()
            compose.waitUntil(20_000) { submitParams(fixture).size == 2 }
            compose.waitUntil(20_000) { vm.chat.value.uncertain.isBlank() && vm.chat.value.messages.any { it.role == "assistant" && it.text == "Synthetic finished reply" } }
            compose.waitUntil(15_000) { vm.chat.value.messages.count { it.role == "user" && it.text == words } == 1 }
            assertEquals("one admission", 1, fixture.canonicalAdmissionsFor(submission))
            assertEquals("one row", 1, fixture.userRows(words))
            assertNull("no saved send record", runBlocking { app.cache.read(fixture.scope.session("chat-a"), "pending") })
            Thread.sleep(2_000); compose.waitForIdle()
            compose.onNodeWithTag("unconfirmed-bubble").assertDoesNotExist()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(words).fetchSemanticsNodes().size == 1 }
            assertEquals("asked once", 2, submitParams(fixture).size)
        } finally { runBlocking { app.cache.delete(fixture.scope, OFFLINE_OUTBOX_KIND) } }
    }

    /** Follow-up 5: the send's answer and the ask's answer are both lost (Hermes took the send): it stays "Not confirmed yet", asked nothing more. */
    private fun loseTwice(vm: BeaconViewModel, fixture: NativeExperienceFixture, words: String): String {
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        fixture.persistTerminalMessages = true; fixture.persistCanonicalUserRows = true
        fixture.holdPromptAcknowledgement = true
        compose.onNodeWithTag("message-input").performTextInput(words)
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { submitParams(fixture).size == 1 }
        fixture.dropHeldAcknowledgements(keepHolding = true)
        compose.waitUntil(20_000) { submitParams(fixture).size == 2 }
        fixture.dropHeldAcknowledgements()
        compose.waitUntil(20_000) { vm.chat.value.uncertain.isNotBlank() && vm.chat.value.status != "Sending" }
        // No timer asks again: it waits for the next time Hermes answers.
        Thread.sleep(6_000); compose.waitForIdle()
        assertEquals("one ask per time", 2, submitParams(fixture).size)
        assertTrue(vm.chat.value.uncertain.isNotBlank())
        compose.onNodeWithTag("unconfirmed-bubble").assertIsDisplayed()
        return submitParams(fixture).first().getString("submission_id")
    }

    /** CHATQA-121 follow-up 5: an ask whose answer is lost again stays "Not confirmed yet" until Hermes answers again, then is asked once more and settles. */
    @Test fun anAskLostAgainWaitsForTheNextTimeHermesAnswers() = withFixture(canonical = true) { vm, fixture ->
        val words = "Synthetic words lost twice"
        try {
            val submission = loseTwice(vm, fixture, words)
            val collectors = fixture.liveCollectorSubscriptions.get()
            hermesAnswersAgain(vm, fixture)
            compose.waitUntil(20_000) { submitParams(fixture).size == 3 }
            compose.waitUntil(20_000) { vm.chat.value.uncertain.isBlank() && vm.chat.value.streamId.isNotBlank() }
            // The stream the app follows is subscribed a moment after the bubble settles. An earlier attempt's replay already marked the runtime active,
            // so the wait is for this send's own collector, with the live frames routed to it (CHATFIX-121, REVIEW-JOIN-121 P3-4).
            compose.waitUntil(15_000) { fixture.liveCollectorSubscriptions.get() > collectors && fixture.liveRoutedToCollector() }
            fixture.nativeTurnStatus = "idle"
            fixture.emitNativeEvent("message.complete", json("text" to "Synthetic reply after two drops"))
            compose.waitUntil(20_000) { vm.chat.value.messages.any { it.role == "assistant" && it.text == "Synthetic reply after two drops" } }
            compose.waitUntil(15_000) { vm.chat.value.messages.count { it.role == "user" && it.text == words } == 1 }
            assertEquals("one admission", 1, fixture.canonicalAdmissionsFor(submission))
            assertEquals("one row", 1, fixture.userRows(words))
            assertEquals(3, submitParams(fixture).size)
        } finally { runBlocking { app.cache.delete(fixture.scope, OFFLINE_OUTBOX_KIND) } }
    }

    /** CHATQA-121 follow-up 5: a send the person removed (fix 4) is never asked again, however often Hermes answers again; Hermes' own row shows it. */
    @Test fun aRemovedSendIsNeverAskedAgain() = withFixture(canonical = true) { vm, fixture ->
        val words = "Synthetic words removed after two drops"
        try {
            loseTwice(vm, fixture, words)
            compose.onNodeWithTag("unconfirmed-bubble").performClick()
            compose.onNodeWithTag("send-dismiss").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { vm.chat.value.uncertain.isBlank() }
            hermesAnswersAgain(vm, fixture)
            compose.runOnIdle { vm.selectSession("chat-b") }
            compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
            compose.runOnIdle { vm.selectSession("chat-a") }
            compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
            Thread.sleep(3_000); compose.waitForIdle()
            assertEquals("never asked again", 2, submitParams(fixture).size)
            assertTrue(vm.chat.value.uncertain.isBlank())
            compose.waitUntil(15_000) { vm.chat.value.messages.count { it.role == "user" && it.text == words } == 1 }
            fixture.nativeTurnStatus = "idle"
        } finally { runBlocking { app.cache.delete(fixture.scope, OFFLINE_OUTBOX_KIND) } }
    }

    /**
     * Chat join (Root msg_22371a4afdad, rules19): Remove deletes what is saved before the bubble goes, so a restart keeps nothing to ask about. The
     * restart is a fresh view model on the same phone: it starts from the saved state only, and Hermes answering again asks nothing.
     */
    @Test fun aRemovedSendLeavesNoRecordBeforeItsBubbleGoesAndIsNeverAskedAgainAfterARestart() = withFixture(canonical = true) { vm, fixture ->
        val words = "Synthetic words removed before a restart"
        val chat = fixture.scope.session("chat-a")
        val restartStore = ViewModelStore()
        try {
            loseTwice(vm, fixture, words)
            assertNotNull("the unconfirmed send is saved", runBlocking { app.cache.read(chat, "pending") })
            compose.onNodeWithTag("unconfirmed-bubble").performClick()
            compose.onNodeWithTag("send-dismiss").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { vm.chat.value.uncertain.isBlank() }
            // The bubble is gone: nothing saved can bring the copy back.
            assertNull("no saved send record once its bubble is gone", runBlocking { app.cache.read(chat, "pending") })
            assertNull("no run marker either", runBlocking { app.cache.read(chat, "running-turn") })
            lateinit var restarted: BeaconViewModel
            compose.runOnUiThread { restarted = ViewModelProvider(restartStore, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            compose.runOnIdle {
                field(restarted, "originGeneration", 100)
                field(restarted, "client", fixture.client)
                flow<AppState>(restarted, "_ui").value = AppState(origin = chat.origin, owner = chat.owner, profile = chat.profile, authenticated = true, connected = true, restoringSession = false)
                restarted.selectSession("chat-a")
            }
            compose.waitUntil(15_000) { restarted.chat.value.sessionId == "chat-a" && restarted.chat.value.projectionReady }
            hermesAnswersAgain(restarted, fixture)
            Thread.sleep(3_000); compose.waitForIdle()
            assertEquals("never asked again after the restart", 2, submitParams(fixture).size)
            assertTrue("the restarted chat holds no unconfirmed send", restarted.chat.value.uncertain.isBlank())
            fixture.nativeTurnStatus = "idle"
        } finally {
            compose.runOnUiThread { restartStore.clear() }
            runBlocking { app.cache.delete(fixture.scope, OFFLINE_OUTBOX_KIND) }
        }
    }

    /** CHATQA-121 follow-up 5: Hermes holds another payload under the submission id (admission_conflict): its own row shows, and nothing is sent again. */
    @Test fun hermesHoldingAnotherPayloadShowsItsRowAndSendsNothing() = withFixture(canonical = true) { vm, fixture ->
        val words = "Synthetic words Hermes holds otherwise"
        try {
            compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
            fixture.persistTerminalMessages = true; fixture.persistCanonicalUserRows = true
            fixture.holdPromptAcknowledgement = true
            compose.onNodeWithTag("message-input").performTextInput(words)
            compose.onNodeWithTag("message-send").performClick()
            compose.waitUntil(15_000) { submitParams(fixture).size == 1 }
            fixture.holdOtherPayloadUnder(submitParams(fixture).single().getString("submission_id"))
            fixture.dropHeldAcknowledgements()
            compose.waitUntil(20_000) { submitParams(fixture).size == 2 }
            compose.waitUntil(20_000) { vm.chat.value.uncertain.isBlank() && vm.chat.value.messages.count { it.role == "user" && it.text == words } == 1 }
            assertNull("no saved send record", runBlocking { app.cache.read(fixture.scope.session("chat-a"), "pending") })
            fixture.nativeTurnStatus = "idle"
            hermesAnswersAgain(vm, fixture)
            Thread.sleep(3_000); compose.waitForIdle()
            assertEquals("never sent again", 2, submitParams(fixture).size)
            assertEquals(1, fixture.userRows(words))
            compose.onNodeWithTag("unconfirmed-bubble").assertDoesNotExist()
        } finally { runBlocking { app.cache.delete(fixture.scope, OFFLINE_OUTBOX_KIND) } }
    }

    /** CHATQA-121 follow-up 5: a Hermes without session authority is never asked again; the send keeps today's bubble and its choices. */
    @Test fun aHermesWithoutSessionAuthorityIsNeverAskedAgain() = withFixture { vm, fixture ->
        val words = "Synthetic words on a legacy Hermes"
        try {
            compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
            fixture.persistTerminalMessages = true
            fixture.holdPromptAcknowledgement = true
            compose.onNodeWithTag("message-input").performTextInput(words)
            compose.onNodeWithTag("message-send").performClick()
            compose.waitUntil(15_000) { submitParams(fixture).size == 1 }
            fixture.dropHeldAcknowledgements()
            fixture.nativeTurnStatus = "idle"
            compose.waitUntil(15_000) { vm.chat.value.uncertain.isNotBlank() && vm.chat.value.status != "Sending" }
            hermesAnswersAgain(vm, fixture)
            Thread.sleep(3_000); compose.waitForIdle()
            assertEquals("never asked again", 1, submitParams(fixture).size)
            compose.onNodeWithTag("unconfirmed-bubble").assertIsDisplayed().performClick()
            compose.onNodeWithTag("send-resend").assertIsDisplayed()
            compose.onNodeWithTag("send-dismiss").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { vm.chat.value.uncertain.isBlank() }
        } finally { runBlocking { app.cache.delete(fixture.scope, OFFLINE_OUTBOX_KIND) } }
    }

    private fun submittedTexts(fixture: NativeExperienceFixture): List<Pair<String, String>> =
        fixture.requests.filter { it.string("method") == "prompt.submit" }.map { it.getJSONObject("params").let { p -> p.string("session_id") to p.string("text") } }

    /** CHATQA-121 P2-1: a profile switch that fails after it bumped the sign-in generation keeps the chat open; its reply must keep coming. */
    @Test fun aFailedProfileSwitchKeepsTheRunningReplyComing() = withFixture { vm, fixture ->
        startTurn(vm, fixture)
        val collectors = fixture.liveCollectorSubscriptions.get()
        compose.runOnIdle { vm.switchProfile("no-such-profile") }
        compose.waitUntil(15_000) { vm.ui.value.error.isNotBlank() }
        assertEquals("the chat stays the one open", "chat-a", vm.chat.value.sessionId)
        // The stricter fence muted the old stream job; the chat follows its reply again on a new subscription.
        compose.waitUntil(20_000) { fixture.liveCollectorSubscriptions.get() > collectors }
        fixture.emitNativeEvent("message.delta", json("text" to "Words after the failed switch"))
        compose.waitUntil(15_000) { vm.chat.value.liveText.contains("Words after the failed switch") }
    }

    /** CHATQA-121 P2-1: the switch fails because Hermes is out of reach; once Hermes answers again the chat follows its reply again. */
    @Test fun aProfileSwitchThatCannotReachHermesKeepsTheReplyComingOnceHermesAnswers() = withFixture { vm, fixture ->
        startTurn(vm, fixture)
        val collectors = fixture.liveCollectorSubscriptions.get()
        fixture.refuseConnection = true
        try {
            compose.runOnIdle { vm.switchProfile("coder") }
            compose.waitUntil(15_000) { vm.ui.value.error.isNotBlank() }
            assertEquals("chat-a", vm.chat.value.sessionId)
        } finally { fixture.refuseConnection = false }
        // Hermes answers again (the reach check gets through): the reply is followed on a new subscription.
        try {
            compose.waitUntil(45_000) { fixture.liveCollectorSubscriptions.get() > collectors }
            fixture.emitNativeEvent("message.delta", json("text" to "Words once Hermes answered"))
            compose.waitUntil(15_000) { vm.chat.value.liveText.contains("Words once Hermes answered") }
        } finally { dev.hermes.android.data.HermesReach.answered(fixture.scope.origin) }
    }

    /** CHATQA-121 P2-2: an app started offline has no client; Send still works, the message waits as "Not sent" and goes once when Hermes is back. */
    @Test fun withNoConnectionSendQueuesAsNotSentAndGoesOnceWhenHermesIsBack() = withFixture { vm, fixture ->
        val owner = fixture.scope
        try {
            compose.runOnIdle { field(vm, "client", null); field(vm, "cachedOwnerScope", owner); flow<AppState>(vm, "_ui").value = vm.ui.value.copy(connected = false) }
            compose.onNodeWithTag("message-input").performTextInput("Running late, start without me")
            compose.onNodeWithTag("message-send").assertIsEnabled().performClick()
            compose.waitUntil(15_000) { vm.chat.value.queued.any { it.text == "Running late, start without me" && it.waitingForConnection() } }
            assertEquals("", vm.composer.value.text)
            // H1b P2-14: the row says why.
            compose.onNodeWithText("Not sent · Offline").assertExists()
            assertTrue(submittedTexts(fixture).isEmpty())
            compose.runOnIdle { field(vm, "client", fixture.client); field(vm, "cachedOwnerScope", null); flow<AppState>(vm, "_ui").value = vm.ui.value.copy(connected = true) }
            compose.runOnIdle { vm.drainOfflineOutbox() }
            compose.waitUntil(20_000) { submittedTexts(fixture).size == 1 }
            assertEquals("runtime-chat-a" to "Running late, start without me", submittedTexts(fixture).single())
            Thread.sleep(1_500); compose.waitForIdle()
            assertEquals("sent once", 1, submittedTexts(fixture).size)
        } finally { runBlocking { app.cache.delete(owner, OFFLINE_OUTBOX_KIND) } }
    }

    /**
     * CHATQA-121 P2-3: when Hermes is back, a chat that is not open sends its waiting messages too, each once and in order, the next only
     * once the reply to the one before is over. (The open chat's own queue is withNoConnectionSendQueuesAsNotSentAndGoesOnceWhenHermesIsBack;
     * an open and a closed chat together are checked on the demo.)
     */
    @Test fun aChatThatIsNotOpenSendsItsWaitingMessagesOnceInOrderWhenHermesIsBack() = withFixture { vm, fixture ->
        val owner = fixture.scope
        val chatB = owner.session("chat-b")
        val first = QueuedMessage("First for the reading list", id = "offline-b-1", state = "queued", outcome = OFFLINE_OUTCOME, submissionId = "offline-b-1")
        val second = QueuedMessage("Second for the reading list", id = "offline-b-2", state = "queued", outcome = OFFLINE_OUTCOME, submissionId = "offline-b-2")
        try {
            // chat-b was left while offline: only its saved draft and the outbox hold its two messages.
            runBlocking {
                app.cache.write(chatB, "draft", Draft(queued = listOf(first, second)).encode(), true)
                app.cache.write(owner, OFFLINE_OUTBOX_KIND, org.json.JSONArray().put("chat-b").toString(), true)
            }
            compose.runOnIdle { vm.drainOfflineOutbox() }
            fun report(): String {
                val savedB = runBlocking { app.cache.read(chatB, "draft")?.let(Draft::decode) }
                return "submitted=${submittedTexts(fixture)} chatB=${savedB?.queued?.map { it.state + "/" + it.outcome }} outbox=${runBlocking { app.cache.read(owner, OFFLINE_OUTBOX_KIND) }} " +
                    "notice=${vm.ui.value.notice} error=${vm.ui.value.error} methods=${fixture.requests.takeLast(14).map { it.string("method") }}"
            }
            try { compose.waitUntil(30_000) { submittedTexts(fixture).size == 1 } }
            catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError(report(), timeout) }
            // The second waits while the first one's reply runs.
            Thread.sleep(4_000); compose.waitForIdle()
            assertEquals("the second waits for the reply to the first", 1, submittedTexts(fixture).size)
            fixture.nativeTurnStatus = "idle"
            try { compose.waitUntil(30_000) { submittedTexts(fixture).size == 2 } }
            catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError(report(), timeout) }
            assertEquals(listOf("runtime-chat-b" to "First for the reading list", "runtime-chat-b" to "Second for the reading list"), submittedTexts(fixture))
            Thread.sleep(1_500); compose.waitForIdle()
            assertEquals("each once", 2, submittedTexts(fixture).size)
            assertEquals("the open chat sent nothing of chat-b's", "chat-a", vm.chat.value.sessionId)
            val savedB = runBlocking { app.cache.read(chatB, "draft")?.let(Draft::decode) }
            assertEquals("Hermes has both: they left chat-b's box", emptyList<String>(), savedB?.queued?.map { it.text })
            assertFalse("chat-b has nothing left waiting", runBlocking { app.cache.read(owner, OFFLINE_OUTBOX_KIND) }.orEmpty().contains("chat-b"))
            // Opening chat-b later shows no waiting row and sends nothing again.
            compose.runOnIdle { vm.selectSession("chat-b") }
            compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
            Thread.sleep(2_000); compose.waitForIdle()
            assertTrue(vm.chat.value.queued.none { it.text in setOf("First for the reading list", "Second for the reading list") })
            assertEquals("nothing sent twice", 2, submittedTexts(fixture).size)
        } finally { runBlocking { app.cache.delete(owner, OFFLINE_OUTBOX_KIND) } }
    }
}
