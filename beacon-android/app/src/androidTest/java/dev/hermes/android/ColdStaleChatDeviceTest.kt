package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import dev.hermes.android.data.NativeBearerRecord
import dev.hermes.android.ui.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * TRACE-COLD-121: a cold start whose saved chat Hermes no longer has (deleted elsewhere after this phone saved it).
 *
 * UX-121 P3-11 captured it on the visual tour's first open: the Chat tab showed "New chat" holding the old chat's messages under "Saved copy · reconnect to update"
 * while the app read Connected, and later "Couldn't load reasoning" and an app-wide error row under Server address. Every test signs in through the real `connect()` against
 * the synthetic native authority (a saved bearer, so the cold start restores the saved account and its selected chat before Hermes answers), seeds the cache with what a phone
 * that had this chat open keeps (`selected-session`, a saved `session` with two user rows and no title), and never lets the fixture hold that chat. The saved copy's exact
 * contents in the tour are not known (the emulator state was restored after each run), so the seed is a stand-in with the same shape, not a copy.
 * Synthetic text only. The fixture's own chats are chat-a and chat-b; the saved chat below is in neither.
 */
class ColdStaleChatDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    private companion object {
        const val GONE = "cold-gone-chat"
        const val STALE_ONE = "Synthetic saved prompt one of the deleted chat"
        const val STALE_TWO = "Synthetic saved prompt two of the deleted chat"
        const val OTHER_COPY = "Synthetic saved copy of chat-a"
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) = BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
    private fun selecting(vm: BeaconViewModel) = (BeaconViewModel::class.java.getDeclaredField("selectionJob").apply { isAccessible = true }.get(vm) as Job?)?.isActive == true
    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }

    /** The saved copy of a chat Hermes deleted: two user rows, no assistant row and no title (a chat Hermes had not titled), in the shape `/api/session` returns it. */
    private fun staleSnapshot(fixture: NativeExperienceFixture): JSONObject = fixture.snapshot("chat-a").apply {
        remove("runtime_session_id")
        // A saved copy names its profile (passiveHistory puts it on every projection); the call-transcript projection refuses a copy of another scope.
        put("session_id", GONE); put("stored_session_id", GONE); put("profile", fixture.scope.profile); put("title", ""); put("message_count", 2)
        put("messages", JSONArray().put(json("role" to "user", "text" to STALE_ONE, "row_id" to 1)).put(json("role" to "user", "text" to STALE_TWO, "row_id" to 2)))
    }

    private fun describe(vm: BeaconViewModel, fixture: NativeExperienceFixture): String {
        val chat = vm.chat.value; val ui = vm.ui.value
        return "chat='${chat.sessionId}' title='${chat.title}' rows=${chat.messages.size} status='${chat.status}' projection=${chat.projectionReady} " +
            "connected=${ui.connected} tab=${ui.tab} wizard=${ui.setupWizard} appError='${ui.error}' creates=${fixture.requests.count { it.string("method") == "session.create" }} " +
            "ofTheDeletedChat=${fixture.requests.filter { it.toString().contains(GONE) }.map { "${it.string("method")} ${it.string("path")}" }}"
    }

    /**
     * A phone that was closed with [GONE] open: the saved sign-in (a bearer, so a start restores the saved account), the saved selection and the saved copy.
     * [setupDone]: the wizard is over (a plain cold start) or the debug app's first run, where `finishSetupWizard` follows the sign-in as the visual tour does.
     */
    private fun withColdPhone(canonical: Boolean = true, setupDone: Boolean, sessionReadDelayMs: Long = 0, assistantReturn: String? = null, goneDraft: Draft? = null, block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val fixture = NativeExperienceFixture().apply { canonicalAuthority = canonical; currentRefreshToken = "cold-refresh" }
        val scope = fixture.scope
        val auth = AuthContext(scope.origin, emptyList(), scope.owner, scope.profile, backend = "hermes-dashboard", nativeProvider = scope.nativeProvider,
            bearer = NativeBearerRecord("cold-access", "cold-refresh", 2_000_000_000L, scope.origin, scope.owner, scope.profile, scope.nativeProvider, issuedAt = 200))
        assertEquals("The saved sign-in restores the fixture's own account", scope, auth.scope())
        val prior = runBlocking { app.preferences.data.first() }
        val owners = listOf(scope, scope.copy(profile = "coder"))
        val ownerKinds = listOf("new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session", "conversation-index", "beacon-session", "beacon-opening", "read-invalidated-at")
        val chatKinds = listOf("pending", "draft", "session", "running-turn", "side-question", "alerts", "client-created-shell", "live:runtime-chat-a", "live:chat-a")
        fun clean() = runBlocking {
            owners.forEach { owner ->
                ownerKinds.forEach { app.cache.delete(owner, it) }
                listOf(GONE, "chat-a", "chat-b").forEach { id -> chatKinds.forEach { kind -> app.cache.delete(owner.session(id), kind) } }
                (1..8).forEach { n -> listOf("pending", "draft", "session", "client-created-shell", "running-turn").forEach { app.cache.delete(owner.session("new-$n"), it) } }
            }
        }
        val animatorScale = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 0")
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        try {
            runBlocking {
                app.preferences.edit { values -> values[stringPreferencesKey("origin")] = ""; values.remove(stringPreferencesKey("assistant_return")); assistantReturn?.let { values[stringPreferencesKey("assistant_return")] = it } }
                app.auth.persist(auth)
            }
            clean()
            runBlocking {
                app.cache.write(scope, "selected-session", GONE, protected = true)
                // Written to disk only: a cold process holds no in-memory transcripts (ProjectionCache.recent).
                app.cache.write(scope.session(GONE), "session", staleSnapshot(fixture).toString())
                // CHATFIX-121 follow-up 1: the gone chat holds the owner's own unsent words (a draft or a waiting message).
                goneDraft?.let { app.cache.write(scope.session(GONE), "draft", it.encode(), protected = true) }
            }
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            compose.waitUntil(10_000) { !vm.ui.value.restoringSession }
            compose.runOnIdle {
                // [sessionReadDelayMs]: a Hermes that was restarted seconds ago (the visual tour's first open) answers the read of the saved chat late; its other answers are not slowed.
                vm.profileSwitchClientFactoryForTest = { next ->
                    if (sessionReadDelayMs <= 0) fixture.freshClientFor(next, app.auth)
                    else dev.hermes.android.data.BeaconClient(initial = next, authStore = app.auth, socketFactory = fixture, persistContext = false, configureHttp = {},
                        sharedHttp = fixture.client.http.newBuilder().apply {
                            interceptors().add(0, okhttp3.Interceptor { chain -> if (chain.request().url.encodedPath == "/api/sessions/$GONE") Thread.sleep(sessionReadDelayMs); chain.proceed(chain.request()) })
                        }.build())
                }
                if (setupDone) flow<AppState>(vm, "_ui").value = vm.ui.value.copy(setupWizard = false, setupComplete = true, tab = "Chat")
            }
            compose.setContent {
                val settings by vm.data.collectAsState()
                val prefs by vm.preferences.collectAsState()
                CompositionLocalProvider(LocalBeaconSettings provides (settings["profile-settings"] ?: JSONObject()), LocalAssistantName provides prefs.assistantName) {
                    NativeLocalization("en") { BeaconTheme(dark = true) { NativeWindowLayout(compose.activity) { Box(Modifier.fillMaxSize()) { BeaconRoot(vm) } } } }
                }
            }
            block(vm, fixture)
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative() }
            runBlocking { app.preferences.updateData { prior }; app.auth.remove(scope.origin) }
            clean()
            shell(if (animatorScale == "null" || animatorScale.isBlank()) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $animatorScale")
        }
    }

    /** The visual tour's start: sign in, then the wizard's last step opens Chat. A plain cold start only signs in (the wizard is over). */
    private fun startUp(vm: BeaconViewModel, fixture: NativeExperienceFixture, wizard: Boolean) {
        compose.runOnIdle { vm.connect(fixture.scope.origin) }
        compose.waitUntil(30_000) { vm.ui.value.authenticated && vm.ui.value.connected && !vm.ui.value.loading }
        if (wizard) compose.runOnIdle { vm.finishSetupWizard(keepConnected = false) }
    }

    /**
     * Waits until the open of the saved chat has finished (nothing selecting, Hermes asked about it) and then a little longer, so a late write would show too;
     * returns the distinct states the chat went through (status, rows, app-wide error), the evidence for "what it ends on".
     */
    private fun settle(vm: BeaconViewModel, fixture: NativeExperienceFixture): List<String> {
        val seen = ArrayList<String>()
        val began = android.os.SystemClock.elapsedRealtime()
        var quietSince = 0L
        var last = ""
        while (android.os.SystemClock.elapsedRealtime() - began < 45_000) {
            val now = android.os.SystemClock.elapsedRealtime()
            val state = describe(vm, fixture)
            if (state != last) { last = state; seen += "+${now - began}ms $state"; quietSince = now }
            if (vm.ui.value.connected && !selecting(vm) && now - quietSince > 4_000) break
            Thread.sleep(100)
        }
        // What Hermes was asked while the phone opened the saved chat (REST paths and socket methods), and what it refused: the open's own read is among them or it never went out.
        seen += "asked: " + fixture.requests.map { it.string("method") + " " + it.string("path") + it.optJSONObject("params")?.optString("session_id").orEmpty().let { id -> if (id.isBlank()) "" else " [$id]" } }.filterNot { it.startsWith("POST /auth/native") }
        seen += "refused: " + fixture.rejectedRequests.map { "${it.string("method")} ${it.string("path")} ${it.optInt("status")}" }
        seen += probeRead(vm)
        seen.forEach { println("COLD_STALE $it") }
        return seen
    }

    /** What Hermes (the fixture, which no longer holds the chat) answers to the read the open makes: the exception the open's catch sees. */
    private fun probeRead(vm: BeaconViewModel): String {
        val client = requireNotNull(vm.client).detached()
        val failure = try { runBlocking { client.get("/api/session", mapOf("session_id" to GONE)) }; null } catch (e: Throwable) { e } finally { client.closeNative() }
        val api = failure as? dev.hermes.android.data.ApiFailure
        return "the read of the deleted chat answers: ${failure?.javaClass?.simpleName ?: "no failure"} " + if (api != null) "status=${api.status} detail='${api.detail}' plugin=${api.plugin}" else "message='${failure?.message}'"
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val directory = File(app.filesDir, "trace-cold-121").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** What the person sees first, before Hermes has answered: the saved copy, one chat, its own rows. Recorded, so the later tests read against it. */
    @Test(timeout = 120_000) fun theSavedCopyDrawsFirstUnderTheDeletedChatsOwnId() = withColdPhone(setupDone = true) { vm, fixture ->
        startUp(vm, fixture, wizard = false)
        val seen = settle(vm, fixture)
        capture("plain-cold-start")
        val first = seen.first { it.contains("rows=2") }
        assertTrue("The saved rows draw under the saved chat's own id: $first", first.contains("chat='$GONE'"))
        assertTrue("Only the saved chat's own rows: ${vm.chat.value.messages.map { it.text }}", vm.chat.value.messages.all { it.text == STALE_ONE || it.text == STALE_TWO })
        assertTrue("A saved copy with no title is a 'New conversation' (the header's 'New chat'), not another chat's title: ${vm.chat.value.title}", vm.chat.value.title in setOf("New conversation", ""))
    }

    /** Hermes answered that the chat is gone and the app is connected: the line under the chat must not still say it is a saved copy to be updated. */
    @Test(timeout = 120_000) fun connectedWithTheOpenedChatGoneTheStatusIsNotASavedCopyAfterAPlainColdStart() = withColdPhone(setupDone = true) { vm, fixture ->
        startUp(vm, fixture, wizard = false)
        val seen = settle(vm, fixture)
        capture("plain-cold-start-settled")
        assertTrue("Connected, and Hermes was asked about the chat: ${describe(vm, fixture)}", vm.ui.value.connected && fixture.requests.any { it.toString().contains(GONE) })
        assertFalse("The status still reads as a saved copy while connected. Timeline: $seen", vm.chat.value.status.startsWith("Saved copy"))
    }

    /** The same in the visual tour's order (sign in inside the first-run wizard, then the wizard's last step opens Chat). */
    @Test(timeout = 120_000) fun connectedWithTheOpenedChatGoneTheStatusIsNotASavedCopyInTheTourOrder() = withColdPhone(setupDone = false) { vm, fixture ->
        startUp(vm, fixture, wizard = true)
        val seen = settle(vm, fixture)
        capture("tour-order-settled")
        assertTrue("Connected, and Hermes was asked about the chat: ${describe(vm, fixture)}", vm.ui.value.connected && fixture.requests.any { it.toString().contains(GONE) })
        assertFalse("The status still reads as a saved copy while connected. Timeline: $seen", vm.chat.value.status.startsWith("Saved copy"))
    }

    /**
     * The visual tour's first open: Hermes had been restarted seconds before and answers the read of the saved chat late. The app already reads Connected, so the line the offline
     * page drew for the saved copy ("Saved copy · reconnect to update", ChatScreen.transcriptStatusShown hides it only while not connected) is on screen with nothing left to reconnect.
     */
    @Test(timeout = 120_000) fun connectedWhileHermesIsSlowToAnswerTheSavedCopyLineIsNotOnScreen() = withColdPhone(setupDone = true, sessionReadDelayMs = 6_000) { vm, fixture ->
        startUp(vm, fixture, wizard = false)
        // The tour's own wait before a capture.
        Thread.sleep(2_500)
        compose.waitForIdle()
        capture("slow-hermes-connected")
        val line = compose.onAllNodesWithText("Saved copy · reconnect to update", useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue("Connected and the open of the saved chat is still waiting for Hermes: ${describe(vm, fixture)}", vm.ui.value.connected && selecting(vm))
        assertEquals("'Saved copy · reconnect to update' is drawn while the app is connected: ${describe(vm, fixture)}", 0, line)
    }

    /**
     * A leftover "where the chats were" record from the assistant tab (S3ComposerUiTest branch 3 writes the same one) makes `connect()` run `returnFromAssistant()` instead of
     * opening the saved chat (BeaconViewModel.kt:3227). With no session in the record and a chat already restored, nothing selects that chat (:3962-3973: prepareInitialChatIfNone
     * needs no open chat), so the restore's own line is the last word: connected, a saved copy under it, and no request for the chat ever sent.
     */
    @Test(timeout = 120_000) fun aLeftoverAssistantReturnDoesNotLeaveTheSavedCopyLineOnAConnectedApp() = withColdPhone(setupDone = true, assistantReturn = JSONObject().put("profile", "default").put("session", "").toString()) { vm, fixture ->
        startUp(vm, fixture, wizard = false)
        val seen = settle(vm, fixture)
        capture("assistant-return-leftover")
        val line = compose.onAllNodesWithText("Saved copy · reconnect to update", useUnmergedTree = true).fetchSemanticsNodes().size
        val asked = fixture.requests.any { it.string("path") == "/api/sessions/$GONE" }
        println("COLD_STALE assistant-return asked=$asked line=$line ${describe(vm, fixture)}")
        assertTrue("Connected: ${describe(vm, fixture)}", vm.ui.value.connected)
        assertFalse("The chat on screen was never asked about (no read of $GONE) and still reads as a saved copy while connected. Timeline: $seen", !asked && vm.chat.value.status.startsWith("Saved copy"))
        assertEquals("'Saved copy · reconnect to update' is drawn while the app is connected. Timeline: $seen", 0, line)
    }

    /**
     * The saved selection is what brings the gone chat back: Hermes' answer (404) must end it, so the next cold start opens something Hermes has, not the same gone chat again.
     * (Today only a phone-made empty chat that Hermes lost clears it: recoverMissingInitialShell; a chat with saved rows keeps it for good.)
     */
    @Test(timeout = 120_000) fun aChatHermesAnsweredIsGoneIsNotTheSavedSelectionForTheNextColdStart() = withColdPhone(setupDone = true) { vm, fixture ->
        startUp(vm, fixture, wizard = false)
        val seen = settle(vm, fixture)
        assertTrue("Hermes was asked about the chat and refused it: ${fixture.rejectedRequests.map { "${it.string("path")} ${it.optInt("status")}" }}", fixture.rejectedRequests.any { it.string("path") == "/api/sessions/$GONE" && it.optInt("status") == 404 })
        val kept = runBlocking { app.cache.read(fixture.scope, "selected-session") }
        assertNotEquals("The next cold start opens the gone chat again (selected-session still names it). Timeline: $seen", GONE, kept)
    }

    /** The reopen at start-up is not something the person asked for: a chat gone from Hermes must not put an app-wide error row on every page (Server address, Devices). */
    @Test(timeout = 120_000) fun theStartUpReopenOfAGoneChatRaisesNoAppWideError() = withColdPhone(setupDone = true) { vm, fixture ->
        startUp(vm, fixture, wizard = false)
        val seen = settle(vm, fixture)
        assertEquals("No app-wide error after a start nobody asked anything of. Timeline: $seen", "", vm.ui.value.error)
    }

    /** What the tour does next: the person opens Reasoning (a read for the open chat's id). A chat Hermes lacks must not leave "Couldn't load reasoning" and an app-wide error. */
    @Test(timeout = 120_000) fun askingForTheReasoningOfAGoneChatLeavesNoAppWideError() = withColdPhone(setupDone = true) { vm, fixture ->
        startUp(vm, fixture, wizard = false)
        settle(vm, fixture)
        compose.runOnIdle { flow<AppState>(vm, "_ui").value = vm.ui.value.copy(error = "") }
        compose.runOnIdle { vm.openCatalog("Reasoning") }
        compose.waitUntil(20_000) { vm.data.value["picker:reasoning"]?.let { it.has("_error") || it.has("options") } == true }
        Thread.sleep(1_500)
        capture("reasoning-of-a-gone-chat")
        val reasoning = vm.data.value["picker:reasoning"]
        println("COLD_STALE reasoning=${reasoning?.optString("_error")} appError='${vm.ui.value.error}' requests=${fixture.requests.filter { it.toString().contains(GONE) }.map { "${it.string("method")} ${it.string("path")}" }}")
        // Root (CHATFIX-121 follow-up 1, 3b declined): the read the owner asked for keeps its banner rule; the sheet says it in its own row, and the
        // Connection page draws no error under Server address for it (it is not a connection error).
        assertTrue("The Reasoning sheet shows its own row: ${reasoning?.optString("_error")}", reasoning?.optString("_error").orEmpty().isNotBlank())
        compose.runOnIdle { vm.tab("More"); vm.more("Connection") }
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("connection-server-row")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("No error under Server address for a chat setting: appError='${vm.ui.value.error}'", 0, compose.onAllNodes(androidx.compose.ui.test.hasTestTag("connection-error")).fetchSemanticsNodes().size)
    }

    /** Fix 2's other side: a gone chat that holds the owner's unsent words stays the saved selection, on screen with its line, so the words are never lost. */
    private fun aGoneChatHoldingTheOwnersWordsStaysTheSavedSelection(draft: Draft) = withColdPhone(setupDone = true, goneDraft = draft) { vm, fixture ->
        startUp(vm, fixture, wizard = false)
        val seen = settle(vm, fixture)
        assertTrue("Hermes was asked about the chat and refused it", fixture.rejectedRequests.any { it.string("path") == "/api/sessions/$GONE" && it.optInt("status") == 404 })
        assertEquals("The chat stays on screen with its line. Timeline: $seen", "Chat unavailable · draft kept", vm.chat.value.status)
        assertEquals("It stays the saved selection. Timeline: $seen", GONE, runBlocking { app.cache.read(fixture.scope, "selected-session") })
    }

    /**
     * REVIEW-FIXCHAT-121 P2: Hermes answered 404 for the reopened chat and its empty draft let the selection go. The owner then types into it (the
     * composer stays usable: "draft kept") and leaves; the next start must bring the chat back with the words.
     */
    @Test(timeout = 150_000) fun wordsTypedIntoAGoneChatComeBackAfterARestart() = withColdPhone(setupDone = true) { vm, fixture ->
        val words = "Synthetic words typed after the 404"
        startUp(vm, fixture, wizard = false)
        val seen = settle(vm, fixture)
        assertNotEquals("The 404 let the selection go first. Timeline: $seen", GONE, runBlocking { app.cache.read(fixture.scope, "selected-session") })
        compose.runOnIdle { vm.composer(androidx.compose.ui.text.input.TextFieldValue(words)) }
        compose.runOnIdle { vm.foreground(false) }
        compose.waitUntil(10_000) { runBlocking { app.cache.read(fixture.scope.session(GONE), "draft") }.orEmpty().contains(words) }
        // The app ends: the next start is a new view model on the same storage.
        val restarted = ViewModelStore()
        try {
            lateinit var next: BeaconViewModel
            compose.runOnUiThread { next = ViewModelProvider(restarted, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            compose.waitUntil(10_000) { !next.ui.value.restoringSession }
            compose.runOnIdle {
                next.profileSwitchClientFactoryForTest = { auth -> fixture.freshClientFor(auth, app.auth) }
                // Past setup, on Chat, as the first start in this harness (the debug app's own first run would show the wizard).
                flow<AppState>(next, "_ui").value = next.ui.value.copy(setupWizard = false, setupComplete = true, tab = "Chat")
                next.connect(fixture.scope.origin)
            }
            compose.waitUntil(30_000) { next.ui.value.authenticated && next.ui.value.connected && !next.ui.value.loading }
            runCatching { compose.waitUntil(30_000) { next.chat.value.sessionId == GONE && next.composer.value.text == words } }
            assertEquals("The gone chat opens again after the restart (selected-session=${runBlocking { app.cache.read(fixture.scope, "selected-session") }}): ${describe(next, fixture)}", GONE, next.chat.value.sessionId)
            assertEquals("with the words typed into it", words, next.composer.value.text)
        } finally { compose.runOnUiThread { restarted.clear() } }
    }

    @Test(timeout = 120_000) fun aGoneChatWithADraftStaysTheSavedSelection() =aGoneChatHoldingTheOwnersWordsStaysTheSavedSelection(Draft("Synthetic unsent words"))

    @Test(timeout = 120_000) fun aGoneChatWithAWaitingMessageStaysTheSavedSelection() = aGoneChatHoldingTheOwnersWordsStaysTheSavedSelection(Draft(queued = listOf(QueuedMessage("Synthetic waiting words"))))

    /**
     * Privacy: nothing of the gone chat's saved rows may show under another chat, and nothing of another chat under it. The person opens chat-a (which has its own saved copy
     * and its own Hermes rows) right after the cold start.
     */
    @Test(timeout = 120_000) fun openingAnotherChatAfterAColdStartOnAGoneOneShowsOnlyThatChatsRows() = withColdPhone(setupDone = true) { vm, fixture ->
        runBlocking {
            val copy = fixture.snapshot("chat-a").apply { remove("runtime_session_id"); put("profile", fixture.scope.profile); put("messages", JSONArray().put(json("role" to "user", "text" to OTHER_COPY, "row_id" to 10))) }
            app.cache.write(fixture.scope.session("chat-a"), "session", copy.toString())
        }
        startUp(vm, fixture, wizard = false)
        settle(vm, fixture)
        compose.runOnIdle { vm.selectSession("chat-a") }
        val seenRows = ArrayList<String>()
        compose.waitUntil(20_000) {
            val chat = vm.chat.value
            if (chat.sessionId == "chat-a") seenRows += "${chat.title}|${chat.messages.map { it.text }}|${chat.raw.string("session_id")}"
            chat.sessionId == "chat-a" && chat.projectionReady && chat.messages.any { it.text.startsWith("Compare the two plans") }
        }
        Thread.sleep(1_000)
        seenRows += "${vm.chat.value.title}|${vm.chat.value.messages.map { it.text }}|${vm.chat.value.raw.string("session_id")}"
        seenRows.forEach { println("COLD_STALE chat-a $it") }
        for (state in seenRows) {
            assertFalse("The deleted chat's rows under chat-a: $state", state.contains(STALE_ONE) || state.contains(STALE_TWO))
            assertFalse("chat-a's raw projection carries another chat's id: $state", state.substringAfterLast('|') == GONE)
        }
        assertEquals("chat-a opens under its own title. States: ${seenRows.distinct()}", "Travel planning", vm.chat.value.title)
    }
}
