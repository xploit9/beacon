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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * CHAT-121 follow-up 11 (backlog 82; Root msg_ec3f92a4d60a). No reply ever hangs, and no failure takes the chat's connection down.
 *
 * A send the link cannot carry (its connection is not ready within the admission's 15 s: follow-up 10's windows 3 and 4, a call warm-up's 43 KB ahead of it in
 * the phone's one upload queue) used to put its words back in the composer with a 10 s notice. Now it waits in the box with the outbox's look and words and goes
 * again by itself under its own submission id; after three tries in all it is "Not sent" with its Send, and nothing sends it again.
 *
 * A reply whose stream is lost comes back or says so: Hermes closes its socket, or its socket goes quiet (the watcher asks Hermes and follows again), or it goes
 * quiet while Hermes cannot even be asked (the chat says "Reconnecting…", never a running turn with nothing said). A socket that fails outright is
 * NativeExperienceUiTest.interruptedTurnKeepsLiveProgressAndQueuedDraftUntilTerminalReplay. Synthetic text only.
 */
class LinkAndStreamLossUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val rendered = mutableStateOf<BeaconViewModel?>(null)

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun withFixture(canonical: Boolean, block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val prior = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture().apply { canonicalAuthority = canonical }
        val store = ViewModelStore()
        val scope = fixture.scope.session("chat-a")
        val cacheKinds = listOf("pending", "draft", "session", "running-turn", "live:runtime-chat-a", "live:chat-a", "attachments-pending:chat-a", "attachments-pending:runtime-chat-a")
        val priorCache = runBlocking { cacheKinds.associateWith { app.cache.read(scope, it) } }
        val priorOutbox = runBlocking { app.cache.read(fixture.scope.copy(session = ""), dev.hermes.android.OFFLINE_OUTBOX_KIND) }
        lateinit var vm: BeaconViewModel
        var constructed: BeaconViewModel? = null
        try {
            runBlocking {
                app.preferences.edit { it[stringPreferencesKey("origin")] = ""; it.remove(stringPreferencesKey("busy_send_action")) }
                app.auth.persist(fixture.client.auth)
                cacheKinds.forEach { app.cache.delete(scope, it) }
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
            fixture.holdReady.set(0)
            fixture.releasePromptAcknowledgement()
            compose.runOnUiThread { constructed?.client?.closeNative(); store.clear(); fixture.client.closeNative() }
            runBlocking {
                cacheKinds.forEach { kind -> priorCache[kind]?.let { app.cache.write(scope, kind, it, true) } ?: app.cache.delete(scope, kind) }
                priorOutbox?.let { app.cache.write(fixture.scope.copy(session = ""), dev.hermes.android.OFFLINE_OUTBOX_KIND, it, true) }
                    ?: app.cache.delete(fixture.scope.copy(session = ""), dev.hermes.android.OFFLINE_OUTBOX_KIND)
                app.auth.remove(fixture.scope.origin)
                app.preferences.updateData { prior }
            }
        }
    }

    private fun prompts(fixture: NativeExperienceFixture) = fixture.requests.count { it.string("method") == "prompt.submit" }
    private fun composerText(vm: BeaconViewModel) = flow<TextFieldValue>(vm, "_composer").value.text

    /** The link does not carry the first try (every socket opens, none becomes ready); then it does. */
    @Test(timeout = 150_000) fun aSendTheLinkCannotCarryWaitsInTheBoxAndGoesByItselfOnceUnderItsOwnSubmissionId() = withFixture(canonical = true) { vm, fixture ->
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        fixture.holdReady.set(Int.MAX_VALUE)
        compose.runOnIdle { vm.composer(TextFieldValue("Synthetic link check, held once")); vm.send() }
        compose.waitUntil(45_000) { vm.chat.value.queued.singleOrNull()?.waitingForConnection() == true }
        val waiting = vm.chat.value.queued.single()
        fixture.holdReady.set(0)
        assertEquals("Never back in the composer", "", composerText(vm))
        compose.waitUntil(5_000) { vm.ui.value.notice == OFFLINE_SEND_NOTICE }
        assertEquals("Nothing reached Hermes", 0, prompts(fixture))
        compose.waitForIdle()
        compose.onNodeWithTag("queue-text:${waiting.id}").assertExists()
        // It goes again by itself, under its own submission id, once the link carries it.
        compose.waitUntil(45_000) { prompts(fixture) == 1 }
        val submitted = fixture.requests.first { it.string("method") == "prompt.submit" }.getJSONObject("params")
        assertTrue("Under its own submission id: $submitted", submitted.toString().contains(waiting.submissionId))
        compose.waitUntil(15_000) { vm.chat.value.queued.none { it.id == waiting.id && it.waitingForConnection() } }
        Thread.sleep(8_000)
        assertEquals("Hermes got it once", 1, prompts(fixture))
        assertEquals("Never back in the composer", "", composerText(vm))
    }

    /** CHATFIX-121 follow-up 1 (JOINBFIX's live check): "Not sent yet. It goes when Hermes is back." goes once the waiting message is taken, with nothing else waiting. */
    @Test(timeout = 150_000) fun theNotSentYetNoticeGoesOnceTheWaitingMessageIsTaken() = withFixture(canonical = true) { vm, fixture ->
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        fixture.holdReady.set(Int.MAX_VALUE)
        compose.runOnIdle { vm.composer(TextFieldValue("Synthetic link check, notice")); vm.send() }
        compose.waitUntil(45_000) { vm.chat.value.queued.singleOrNull()?.waitingForConnection() == true }
        val waiting = vm.chat.value.queued.single()
        compose.waitUntil(5_000) { vm.ui.value.notice == OFFLINE_SEND_NOTICE }
        fixture.holdReady.set(0)
        compose.waitUntil(45_000) { prompts(fixture) == 1 }
        compose.waitUntil(15_000) { vm.chat.value.queued.none { it.id == waiting.id && it.waitingForConnection() } }
        runCatching { compose.waitUntil(10_000) { vm.ui.value.notice != OFFLINE_SEND_NOTICE } }
        assertTrue("The message went: the notice that it waits is gone. notice='${vm.ui.value.notice}' queued=${vm.chat.value.queued.map { it.state }}", vm.ui.value.notice != OFFLINE_SEND_NOTICE)
    }

    /**
     * CHATFIX-121 follow-up 2: two chats wait for Hermes and the other chat's message is the last to go. While it still waits, "Not sent yet" stays;
     * once it goes, nothing waits anywhere, so the notice goes too.
     */
    @Test(timeout = 300_000) fun theNotSentYetNoticeGoesWhenAnotherChatsMessageIsTheLastToGo() = withFixture(canonical = true) { vm, fixture ->
        val owner = fixture.scope.copy(session = "")
        val other = fixture.scope.session("chat-b")
        val priorOtherDraft = runBlocking { app.cache.read(other, "draft") }
        fun submitted(id: String) = fixture.requests.count { it.string("method") == "prompt.submit" && it.optJSONObject("params")?.string("session_id") == id }
        fun outbox() = runBlocking { app.cache.read(owner, dev.hermes.android.OFFLINE_OUTBOX_KIND) }.orEmpty()
        try {
            compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
            // The other chat's last turn was answered (the fixture's chat-b ends on a question with no reply, which reads as a lost one and holds what waits).
            fixture.sessions.getValue("chat-b").getJSONArray("messages").put(json("role" to "assistant", "text" to "Synthetic reply", "row_id" to 21))
            // The other chat already holds a message waiting for Hermes, listed in the outbox as the app lists one.
            runBlocking {
                app.cache.write(other, "draft", Draft(queued = listOf(QueuedMessage("Synthetic other chat words", outcome = dev.hermes.android.OFFLINE_OUTCOME))).encode(), true)
                app.cache.write(owner, dev.hermes.android.OFFLINE_OUTBOX_KIND, org.json.JSONArray(listOf("chat-b")).toString(), true)
            }
            fixture.holdReady.set(Int.MAX_VALUE)
            compose.runOnIdle { vm.composer(TextFieldValue("Synthetic open chat words")); vm.send() }
            compose.waitUntil(45_000) { vm.chat.value.queued.singleOrNull()?.waitingForConnection() == true }
            compose.waitUntil(5_000) { vm.ui.value.notice == OFFLINE_SEND_NOTICE }
            // The other chat's message goes last: Hermes holds its answer until the open chat's message is in.
            fixture.holdPromptOf = "chat-b"
            fixture.holdReady.set(0)
            compose.waitUntil(45_000) { submitted("chat-a") == 1 && vm.chat.value.queued.none { it.waitingForConnection() } }
            // The open chat's turn ends, so the other chat is idle and its message may go (this fixture keeps one turn status for all chats). Hermes starts that
            // turn while it handles the submit, after the frame is counted; ending it sooner is undone by the start (follow-up 4: one run in 20 did that).
            compose.waitUntil(15_000) { fixture.executingAdmission?.let { fixture.canonicalAdmissions[it]?.optJSONObject("ref")?.optString("session_id") } == "chat-a" }
            fixture.canonicalAdmissions.filterValues { it.optString("status") != "terminal" }.keys.forEach { fixture.setCanonicalAdmission(it, "terminal", "completed") }
            compose.runOnIdle { vm.drainOfflineOutbox() }
            // The other chat's drain asks whether that chat is idle, more slowly each time (up to 30 s apart), so its message can take a while.
            runCatching { compose.waitUntil(90_000) { submitted("chat-b") == 1 } }
            assertEquals("The other chat's message went to Hermes. turn=${fixture.nativeTurnStatus} admissions=${fixture.canonicalAdmissions.mapValues { it.value.optString("status") }} " +
                "outbox=${outbox()} draft=${runBlocking { app.cache.read(other, "draft") }} " +
                "requests=${fixture.requests.filter { it.toString().contains("chat-b") }.map { it.optString("method").ifBlank { it.optString("path") } + ":" + it.optJSONObject("params")?.optString("session_id").orEmpty() }}", 1, submitted("chat-b"))
            Thread.sleep(1_000); compose.waitForIdle()
            assertEquals("The other chat's message still waits: the notice stays", OFFLINE_SEND_NOTICE, vm.ui.value.notice)
            fixture.holdPromptOf = null
            fixture.releasePromptAcknowledgement()
            compose.waitUntil(30_000) { "chat-b" !in outbox() }
            runCatching { compose.waitUntil(10_000) { vm.ui.value.notice != OFFLINE_SEND_NOTICE } }
            assertTrue("The last waiting message went: the notice is gone. notice='${vm.ui.value.notice}' outbox=${outbox()}", vm.ui.value.notice != OFFLINE_SEND_NOTICE)
        } finally {
            fixture.holdPromptOf = null
            runBlocking { priorOtherDraft?.let { app.cache.write(other, "draft", it, true) } ?: app.cache.delete(other, "draft") }
        }
    }

    /**
     * CHATFIX-121 follow-up 3 (Root): "Not sent yet" belongs to its profile. A switch to another profile clears it; the first profile's message still
     * waits, as "Not sent" in its own row.
     */
    @Test(timeout = 200_000) fun theNotSentYetNoticeBelongsToItsProfile() = withFixture(canonical = true) { vm, fixture ->
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        fixture.holdReady.set(Int.MAX_VALUE)
        compose.runOnIdle { vm.composer(TextFieldValue("Synthetic words of the first profile")); vm.send() }
        compose.waitUntil(45_000) { vm.chat.value.queued.singleOrNull()?.waitingForConnection() == true }
        val waiting = vm.chat.value.queued.single()
        compose.waitUntil(5_000) { vm.ui.value.notice == OFFLINE_SEND_NOTICE }
        compose.runOnIdle { vm.switchProfile("coder") }
        compose.waitUntil(30_000) { vm.ui.value.profile == "coder" && vm.client?.scope?.profile == "coder" }
        runCatching { compose.waitUntil(10_000) { vm.ui.value.notice != OFFLINE_SEND_NOTICE } }
        assertNotEquals("Another profile is on screen: the first profile's notice is not shown there", OFFLINE_SEND_NOTICE, vm.ui.value.notice)
        val saved = runBlocking { app.cache.read(fixture.scope.session("chat-a"), "draft") }?.let(Draft::decode)
        assertTrue("The first profile's message still waits as Not sent: ${saved?.queued?.map { it.state + "/" + it.outcome }}",
            saved?.queued?.any { it.id == waiting.id && it.waitingForConnection() } == true)
    }

    /**
     * CHATFIX-121 follow-up 5 (REVIEW-FIXCHAT-121 R2 #8): "Not sent yet" belongs to its account too. The same account connecting again while Hermes is
     * still down keeps it; another account signing in on the same server clears it, since the first account's message cannot go from there.
     */
    @Test(timeout = 200_000) fun theNotSentYetNoticeStaysWithItsAccount() = withFixture(canonical = true) { vm, fixture ->
        val second = "synthetic-second-owner"
        try {
            compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
            fixture.holdReady.set(Int.MAX_VALUE)
            compose.runOnIdle { vm.composer(TextFieldValue("Synthetic words of the first account")); vm.send() }
            compose.waitUntil(45_000) { vm.chat.value.queued.singleOrNull()?.waitingForConnection() == true }
            compose.waitUntil(5_000) { vm.ui.value.notice == OFFLINE_SEND_NOTICE }
            // The same account connects again; Hermes is still down, so its message still waits.
            val first = vm.client
            compose.runOnIdle { vm.connect(fixture.scope.origin) }
            compose.waitUntil(30_000) { vm.client !== first && vm.ui.value.authenticated && !vm.ui.value.loading }
            Thread.sleep(1_000); compose.waitForIdle()
            assertEquals("The same account is back and its message still waits: the notice stays", OFFLINE_SEND_NOTICE, vm.ui.value.notice)
            // Another account signs in on the same server.
            fixture.signedInOwner = second
            runBlocking { app.auth.persist(app.auth.read(fixture.scope.origin).copy(owner = second)) }
            compose.runOnIdle { vm.connect(fixture.scope.origin) }
            compose.waitUntil(30_000) { vm.ui.value.owner == second && vm.client?.scope?.owner == second && !vm.ui.value.loading }
            runCatching { compose.waitUntil(10_000) { vm.ui.value.notice != OFFLINE_SEND_NOTICE } }
            assertNotEquals("Another account is on screen: the first account's message cannot go from it", OFFLINE_SEND_NOTICE, vm.ui.value.notice)
        } finally {
            // The second account's screens leave the phone too (synthetic, but nothing is left behind).
            if (vm.client?.scope?.owner == second) { compose.runOnIdle { vm.forgetScreens() }; Thread.sleep(1_000) }
        }
    }

    /** The link never carries it: three tries in all, then "Not sent" with its Send, and nothing more by itself. */
    @Test(timeout = 240_000) fun aSendTheLinkNeverCarriesIsNotSentWithItsSendAfterThreeTriesAndNothingMore() = withFixture(canonical = true) { vm, fixture ->
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        fixture.holdReady.set(Int.MAX_VALUE)
        compose.runOnIdle { vm.composer(TextFieldValue("Synthetic link check, never carried")); vm.send() }
        compose.waitUntil(45_000) { vm.chat.value.queued.singleOrNull()?.waitingForConnection() == true }
        val item = vm.chat.value.queued.single()
        val began = android.os.SystemClock.elapsedRealtime()
        compose.waitUntil(150_000) { vm.chat.value.queued.singleOrNull()?.fileNotSent() == true }
        val spentAfterMs = android.os.SystemClock.elapsedRealtime() - began
        // Two more tries after pauses of 5 s and 15 s; each try's own time depends on how its drain fails (its probe), so only the pauses are fixed.
        assertTrue("Two more tries, after pauses of 5 s and 15 s: $spentAfterMs ms", spentAfterMs in 20_000L..120_000L)
        assertEquals("Nothing reached Hermes", 0, prompts(fixture))
        assertEquals("Never back in the composer", "", composerText(vm))
        compose.waitForIdle()
        compose.onNodeWithTag("queue-not-sent:${item.id}").assertExists()
        compose.onNodeWithTag("queue-send:${item.id}").assertExists()
        Thread.sleep(30_000)
        assertTrue("Nothing sends it again by itself", vm.chat.value.queued.singleOrNull()?.fileNotSent() == true)
        assertEquals(0, prompts(fixture))
    }

    /** Root's order rule: a message sent while an earlier one waits in the box goes behind it, never first, and each reaches Hermes once. */
    @Test(timeout = 180_000) fun aMessageSentWhileAnotherWaitsInTheBoxGoesAfterIt() = withFixture(canonical = true) { vm, fixture ->
        compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
        fixture.holdReady.set(Int.MAX_VALUE)
        compose.runOnIdle { vm.composer(TextFieldValue("Synthetic first in line")); vm.send() }
        compose.waitUntil(45_000) { vm.chat.value.queued.singleOrNull()?.waitingForConnection() == true }
        compose.runOnIdle { vm.composer(TextFieldValue("Synthetic second in line")); vm.send() }
        compose.waitUntil(10_000) { vm.chat.value.queued.size == 2 }
        assertEquals("The second waits behind the first", listOf("Synthetic first in line", "Synthetic second in line"), vm.chat.value.queued.map { it.text })
        assertEquals("Nothing reached Hermes", 0, prompts(fixture))
        fixture.holdReady.set(0)
        compose.waitUntil(60_000) { prompts(fixture) >= 1 }
        val first = fixture.requests.first { it.string("method") == "prompt.submit" }.getJSONObject("params").toString()
        assertTrue("The first goes first: $first", first.contains("Synthetic first in line"))
        assertFalse(first.contains("Synthetic second in line"))
    }

    /** A reply on its way; returns once its first words are on screen. Legacy socket: its reply is a stream of its own (streamSubscriptions). */
    private fun replyRunning(vm: BeaconViewModel, fixture: NativeExperienceFixture) {
        runBlocking { app.cache.delete(fixture.scope.session("chat-a"), "live:runtime-chat-a") }
        val subscriptions = fixture.streamSubscriptions.get()
        compose.runOnIdle { fixture.persistTerminalMessages = true; vm.composer(TextFieldValue("Synthetic first prompt")); vm.send() }
        compose.waitUntil(15_000) { prompts(fixture) == 1 && vm.chat.value.streamId == "runtime-chat-a" }
        compose.waitUntil(15_000) { fixture.streamSubscriptions.get() > subscriptions }
        fixture.nativeTurnStatus = "working"
        fixture.emitNativeEvent("message.delta", json("text" to "Partial answer"))
        compose.waitUntil(15_000) { vm.chat.value.liveText == "Partial answer" }
    }

    /** The turn ends while the phone follows it again: its reply shows, once, and the chat says nothing more. */
    private fun replyEnds(vm: BeaconViewModel, fixture: NativeExperienceFixture) {
        fixture.terminalOnActiveList = json("text" to "Completed answer")
        fixture.interruptActiveNativeStream()
        compose.waitUntil(30_000) { vm.chat.value.streamId.isBlank() && vm.chat.value.messages.any { it.text == "Completed answer" } }
        assertEquals(1, vm.chat.value.messages.count { it.text == "Completed answer" })
        assertNotEquals("Reconnecting…", vm.chat.value.status)
    }

    @Test(timeout = 120_000) fun aReplyWhoseSocketHermesClosesIsFollowedAgainAndEnds() = withFixture(canonical = false) { vm, fixture ->
        replyRunning(vm, fixture)
        val before = fixture.streamSubscriptions.get()
        fixture.closeActiveNativeStream(1011)
        compose.waitUntil(30_000) { fixture.streamSubscriptions.get() > before }
        assertEquals("The words so far stay", "Partial answer", vm.chat.value.liveText)
        assertEquals("runtime-chat-a", vm.chat.value.streamId)
        replyEnds(vm, fixture)
    }

    @Test(timeout = 120_000) fun aReplyThatGoesQuietIsAskedAboutAndFollowedAgain() = withFixture(canonical = false) { vm, fixture ->
        compose.runOnIdle { field(vm, "streamSilenceMs", 2_000L); field(vm, "streamWatchMs", 300L) }
        replyRunning(vm, fixture)
        val before = fixture.streamSubscriptions.get()
        // Its socket stays open and nothing more comes; Hermes says the turn still runs.
        compose.waitUntil(30_000) { fixture.streamSubscriptions.get() > before }
        assertEquals("Partial answer", vm.chat.value.liveText)
        replyEnds(vm, fixture)
    }

    @Test(timeout = 120_000) fun aReplyThatGoesQuietWhileHermesCannotBeAskedSaysReconnecting() = withFixture(canonical = false) { vm, fixture ->
        compose.runOnIdle { field(vm, "streamSilenceMs", 2_000L); field(vm, "streamWatchMs", 300L) }
        replyRunning(vm, fixture)
        fixture.nativeStatusFailure = true
        // Never a running turn with nothing said: the chat says it is reconnecting while Hermes cannot be asked.
        compose.waitUntil(30_000) { vm.chat.value.status == "Reconnecting…" }
        assertEquals("runtime-chat-a", vm.chat.value.streamId)
        val before = fixture.streamSubscriptions.get()
        fixture.nativeStatusFailure = false
        compose.waitUntil(30_000) { fixture.streamSubscriptions.get() > before }
        replyEnds(vm, fixture)
    }
}
