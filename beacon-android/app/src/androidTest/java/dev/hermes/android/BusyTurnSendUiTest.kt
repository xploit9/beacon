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
        compose.onNodeWithTag("queued-actions:${first.id}").performClick()
        compose.onNodeWithText("Remove queued message").performClick()
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
        compose.waitUntil(10_000) { vm.ui.value.notice == "The queued message's model changed. Select its saved model before sending it." && vm.chat.value.queued.singleOrNull()?.state == "queued" }
        val probes = fixture.nativeStatusProbes.size
        val metadata = fixture.requests.count { it.string("path") == "/api/sessions/chat-a" && it.string("method") == "GET" }
        compose.waitUntil(10_000) { fixture.requests.count { it.string("path") == "/api/sessions/chat-a" && it.string("method") == "GET" } >= metadata + 2 }
        assertEquals(probes, fixture.nativeStatusProbes.size)
        assertTrue(fixture.requests.none { it.string("method") == "prompt.submit" })
        fixture.sessions.getValue("chat-a").getJSONObject("info").put("model", item.model)
        fixture.holdPromptAcknowledgement = true
        compose.onNodeWithTag("queued-actions:${item.id}").performClick()
        compose.onNodeWithText("Retry queued message").performClick()
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
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasText("Explicit next turn"))
        compose.onNode(hasText("Explicit next turn") and hasAnyAncestor(hasTestTag("conversation-transcript")), useUnmergedTree = true).assertExists()
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
        compose.onNodeWithTag("composer-queue-caption").assertTextEquals("Steer")
        compose.onNodeWithTag("message-send").assertContentDescriptionEquals("Steer").performClick()
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "session.steer" } }
        val sent = fixture.requests.single { it.string("method") == "session.steer" }.getJSONObject("params")
        assertEquals("runtime-chat-a", sent.string("session_id")); assertEquals("default", sent.string("profile")); assertEquals("Please be concise", sent.string("text"))
        assertEquals("Steer must use the live collector's socket", collectorSocket, fixture.nativeSocketMethods.single { it.second == "session.steer" }.first)
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasText("Please be concise"))
        compose.onNodeWithText("Sending guidance").assertExists()
        assertEquals("Existing live output", vm.chat.value.liveText)
        assertEquals("runtime-chat-a", vm.chat.value.streamId)
        fixture.releasePromptAcknowledgement()
        compose.waitUntil(15_000) { vm.chat.value.queued.single().state == "accepted" }
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasText("Please be concise"))
        compose.onNodeWithText("Accepted; delivery pending").assertExists()
        assertEquals("Existing live output", vm.chat.value.liveText)
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
    }

    @Test fun queuePrefixRequiresPayloadAndDoesNotCatchANearMatch() = withFixture(catalogUnavailable = true) { vm, fixture ->
        awaitCatalogFailure(vm, fixture)
        startTurn(vm, fixture)
        compose.onNodeWithTag("message-input").performTextInput("/queue")
        compose.onNodeWithTag("message-send").performClick()
        assertEquals("/queue", vm.composer.value.text)
        assertEquals("Usage: /queue <message>", vm.ui.value.notice)
        compose.runOnIdle { vm.notice(""); vm.composer(TextFieldValue("/queuefoo")) }
        compose.onNodeWithTag("message-send").performClick()
        assertEquals("/queuefoo", vm.composer.value.text)
        assertTrue(vm.chat.value.queued.isEmpty())
        compose.runOnIdle { vm.notice(""); vm.composer(TextFieldValue("/queue list")) }
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { vm.chat.value.queued.any { it.text == "list" } && vm.composer.value.text.isBlank() }
        assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
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
        compose.onNodeWithText("While Beacon is replying").assertExists()
        compose.onNodeWithText("Steer").performClick()
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
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-actions:$first"))
        compose.onNodeWithTag("queued-actions:$first").performClick()
        compose.onNodeWithText("Edit queued message").performClick()
        compose.waitUntil(15_000) { vm.composer.value.text == "Edit this queued text" && vm.chat.value.queued.size == 1 }
        compose.runOnIdle { vm.composer(TextFieldValue()) }
        val second = vm.chat.value.queued.single().id
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-actions:$second"))
        compose.onNodeWithTag("queued-actions:$second").performClick()
        compose.onNodeWithText("Remove queued message").performClick()
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
        compose.waitUntil(30_000) { vm.ui.value.profile == "coder" && vm.client?.scope?.profile == "coder" }
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
            compose.waitUntil(15_000) { recreated.chat.value.sessionId == "chat-a" && recreated.chat.value.queued.any { it.id == item.id } }
            assertEquals("admission-synthetic", recreated.chat.value.queued.single { it.id == item.id }.admissionId)
            fixture.admissionReceiptStatus = "terminal"; fixture.admissionReceiptOutcome = "failed"
            val before = fixture.requests.count { it.string("method") == "prompt.receipt" }
            compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("queued-actions:${item.id}"))
            compose.onNodeWithTag("queued-actions:${item.id}").performClick()
            compose.onNodeWithText("Check receipt").performClick()
            compose.waitUntil(15_000) { recreated.chat.value.queued.single { it.id == item.id }.state == "settled" && fixture.requests.count { it.string("method") == "prompt.receipt" } > before }
            val sent = fixture.requests.last { it.string("method") == "prompt.receipt" }.getJSONObject("params")
            assertEquals("chat-a", sent.string("session_id")); assertEquals("default", sent.string("profile")); assertEquals("admission-synthetic", sent.string("admission_id"))
            assertEquals("failed", recreated.chat.value.queued.single { it.id == item.id }.outcome)
            compose.onNodeWithText("Turn failed; check history").assertExists()
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
