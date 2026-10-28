package dev.hermes.android

import android.graphics.Bitmap
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.*
import dev.hermes.android.data.NativePairing
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.ui.*
import dev.hermes.android.voice.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import java.io.File

/** Real Compose controls and Android rendering, explicitly synthetic state and native responses. */
class NativeExperienceUiTest {
    companion object { private var artifactsPrepared = false }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val receipts = mutableListOf<String>()
    @Before fun prepareArtifacts() {
        check(args.getString("native_ui_probe") == "1")
        val case = args.getString("native_ui_case", "default")
        require(case in setOf("compact", "expanded", "large-text", "light", "default"))
        if (!artifactsPrepared) {
            File(app.filesDir, "native-experience/$case").listFiles()?.filter { it.isFile && (it.extension == "png" || it.name == "checks.txt") }?.forEach { check(it.delete()) }
            artifactsPrepared = true
        }
    }

    @Suppress("UNCHECKED_CAST") private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }
    private fun prepareWindow() = compose.runOnUiThread {
        val bars = if (args.getString("native_ui_theme") == "light") SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        compose.activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        @Suppress("DEPRECATION")
        compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350, 3000)
        val directory = File(app.filesDir, "native-experience/${args.getString("native_ui_case", "default")}").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val checks = File(directory, "checks.txt")
        checks.writeText(((if (checks.exists()) checks.readLines() else emptyList()) + receipts).distinct().joinToString("\n"))
    }
    private fun withFixture(pickerRegistry: ActivityResultRegistry? = null, restoration: StateRestorationTester? = null, block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        check(args.getString("native_ui_probe") == "1") { "Explicit synthetic UI probe flag required" }
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        prepareWindow()
        val originKey = stringPreferencesKey("origin")
        val prior = runBlocking { app.preferences.data.first() }
        runBlocking { app.preferences.edit { it[originKey] = "" } }
        // Motion follows the phone's "Remove animations" setting; still frames keep the synthetic UI deterministic.
        val shell = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shellRun(command: String): String = shell.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        val priorAnimatorScale = shellRun("settings get global animator_duration_scale")
        shellRun("settings put global animator_duration_scale 0")
        val store = ViewModelStore()
        val fixture = NativeExperienceFixture()
        runBlocking { app.auth.persist(fixture.client.auth) }
        runBlocking {
            for (key in listOf("read-invalidated-at", "settings:skills", "settings:memory")) app.cache.delete(fixture.scope, key)
            app.cache.delete(fixture.scope.copy(profile = "", session = ""), "screens")
        }
        val priorChatPreferences = runBlocking { app.cache.read(fixture.scope, "chat-preferences") }
        runBlocking { app.cache.write(fixture.scope, "chat-preferences", json("render_user_markdown" to true, "show_thinking" to true, "send_key" to "enter").toString(), true) }
        // Each case starts without another case's initial-chat pointers (a shell id unknown to this fixture would block sends).
        // A profile switch opens a new chat in the other profile too, so the coder profile starts clean as well.
        val profiles = listOf(fixture.scope, fixture.scope.copy(profile = "coder"))
        runBlocking { profiles.forEach { owner -> listOf("new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session").forEach { app.cache.delete(owner, it) } } }
        // Fixture chat ids (new-N) repeat across cases and runs; drop their per-chat receipts, drafts and projections too.
        // shortcut: ids come from one counter shared with projects and branches; 24 covers every current case, widen it if one creates more.
        runBlocking { profiles.forEach { owner -> (1..24).forEach { n -> listOf("pending", "draft", "session", "client-created-shell", "side-question").forEach { app.cache.delete(owner.session("new-$n"), it) } } } }
        runBlocking { app.cache.delete(fixture.scope, "bot-mode-return"); app.cache.delete(fixture.scope, "beacon-session"); app.cache.delete(fixture.scope, "beacon-opening"); app.cache.delete(fixture.scope, "phone-editors"); app.cache.delete(fixture.scope, "editor:C:/synthetic-workspace/notes.md") }
        lateinit var vm: BeaconViewModel
        val profileSwitchClientFactory: (dev.hermes.android.data.AuthContext) -> dev.hermes.android.data.BeaconClient = { auth -> fixture.freshClientFor(auth, app.auth) }
        try {
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "profileSwitchClientFactoryForTest", profileSwitchClientFactory)
                field(vm, "sourceScope", fixture.scope.session("chat-a"))
                app.calls.ownerScope = fixture.scope
                flow<AppState>(vm, "_ui").value = AppState(origin = fixture.scope.origin, authenticated = true, owner = fixture.scope.owner, connected = true, restoringSession = false,
                    bootstrap = json("voice" to json("available" to false)), sessions = fixture.sessions.keys.sorted().map { fixture.snapshot(it) })
                // Opening stock history does not bind a runtime (as in NativeOperations.passiveHistory).
                val snapshot = fixture.snapshot("chat-a").apply { remove("runtime_session_id") }
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = "chat-a", title = "Travel planning", raw = snapshot,
                    messages = snapshot.rows("messages").mapIndexed { index, row -> Message.from(row, index, "chat-a") })
                vm.storeData("profile-settings", json("render_user_markdown" to true, "show_thinking" to true, "send_key" to "enter"))
            }
            val content: @Composable () -> Unit = {
                val settings by vm.data.collectAsState()
                val prefs by vm.preferences.collectAsState()
                val density = LocalDensity.current
                val font = args.getString("native_ui_font", "1.0").orEmpty().toFloatOrNull() ?: 1f
                CompositionLocalProvider(LocalDensity provides Density(density.density, font), LocalBeaconSettings provides (settings["profile-settings"] ?: JSONObject()), LocalAssistantName provides prefs.assistantName, LocalActivityResultRegistryOwner provides (pickerRegistry?.let { registry -> object : ActivityResultRegistryOwner { override val activityResultRegistry = registry } } ?: requireNotNull(LocalActivityResultRegistryOwner.current))) {
                    NativeLocalization("en") { BeaconTheme(dark = args.getString("native_ui_theme") != "light") {
                        NativeWindowLayout(compose.activity) { Box(Modifier.fillMaxSize().testTag("experience-viewport")) { BeaconRoot(vm) } }
                    } }
                }
            }
            if (restoration == null) compose.setContent(content) else restoration.setContent(content)
            block(vm, fixture)
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative(); fixture.clearCustomTabSeam(); app.calls.ownerScope = null }
            // Restore every preference, including setup_* written by wizard and sign-out cases, so the real app never reopens on the wizard.
            runBlocking { app.preferences.updateData { prior } }
            shellRun(if (priorAnimatorScale == "null" || priorAnimatorScale.isBlank()) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $priorAnimatorScale")
            runBlocking {
                app.auth.remove(fixture.scope.origin)
                if (priorChatPreferences == null) app.cache.delete(fixture.scope, "chat-preferences") else app.cache.write(fixture.scope, "chat-preferences", priorChatPreferences, true)
            }
            runBlocking { app.cache.delete(fixture.scope, "bot-mode-return"); app.cache.delete(fixture.scope, "beacon-session"); app.cache.delete(fixture.scope, "beacon-opening"); app.cache.delete(fixture.scope, "phone-editors"); app.cache.delete(fixture.scope, "editor:C:/synthetic-workspace/notes.md") }
        }
    }
    private fun clickText(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().performClick()
    private fun clickLabel(label: String) = compose.onNodeWithContentDescription(label, useUnmergedTree = true).performScrollTo().performClick()
    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)
    /** Hermes reports display names on its first profile read; a case that adds them after the UI loaded the catalog reloads it. */
    private fun showProfileDisplayNames(vm: BeaconViewModel, fixture: NativeExperienceFixture) {
        fixture.profileDisplayNames = mapOf("default" to "Personal profile", "coder" to "Code profile")
        compose.runOnIdle { vm.load("navigation-profiles", "/api/profiles", force = true) }
        await { vm.data.value["navigation-profiles"]?.rows("profiles")?.any { it.string("display_name") == "Code profile" } == true }
    }
    private fun recentCalls(fixture: NativeExperienceFixture) = fixture.requests.takeLast(10).map { "${it.string("method")} ${it.string("path")}".trim() }
    /** Menus and sheets are checked with the IME away, so neither a keyboard nor a stale focus can cover them. */
    private fun closeKeyboard() {
        compose.runOnIdle { compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0) }
        await { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true }
    }

    /** A modal sheet taller than half the window opens half-expanded, with its lower rows off screen. Use the sheet's own Expand action when it offers one. */
    private fun expandModalSheet() {
        val expand = compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Expand), useUnmergedTree = true)
        if (expand.fetchSemanticsNodes().isNotEmpty()) expand.onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Expand) { it() }
        compose.waitForIdle()
    }

    private class PickerRegistry(private val context: Context) : ActivityResultRegistry() {
        var code = -1
        var intent: Intent? = null
        var contract: ActivityResultContract<*, *>? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            code = requestCode; this.contract = contract; intent = contract.createIntent(context, input)
        }
        fun deliver(granted: Boolean) { check(dispatchResult(code, granted)) }
        fun deliver(resultCode: Int, data: Intent? = null) { check(dispatchResult(code, resultCode, data)) }
        fun deliver(uris: List<Uri>) {
            val result = Intent().apply { data = uris.singleOrNull() }
            if (uris.isNotEmpty()) result.clipData = ClipData.newRawUri("Selected files", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            check(dispatchResult(code, if (uris.isEmpty()) Activity.RESULT_CANCELED else Activity.RESULT_OK, result))
        }
    }

    @Test fun reconnectingTheSameOwnerLoadsMemoryAfterTheClientIsInstalled() = withFixture { vm, fixture ->
        compose.runOnIdle {
            field(vm, "client", null)
            flow<AppState>(vm, "_ui").value = vm.ui.value.copy(connected = false, loading = true)
            vm.more("Memory")
        }
        compose.waitForIdle()
        assertEquals("", vm.phoneSettings.scopeKey)
        fixture.memoryNotes["memory"] = "Notes refreshed after reconnect"
        compose.runOnIdle {
            field(vm, "client", fixture.client.detached())
            flow<AppState>(vm, "_ui").value = vm.ui.value.copy(connected = true, loading = false)
        }
        await { vm.phoneSettings.memory.value.documents.any { it.content == "Notes refreshed after reconnect" } }
        compose.onNodeWithText("Notes refreshed after reconnect").assertIsDisplayed()
        receipts += "Same-owner reconnect binds the installed client and reads native memory without a stranded spinner."
        capture("audit-reconnect-memory")
    }

    @Test fun boardRefreshCannotHideAnUnreadableRetainedTaskDraft() = withFixture { vm, fixture ->
        val kind = "kanban-draft:default"
        val key = "${fixture.scope.key}:${kind.digest()}"
        val prior = runBlocking { app.cache.read(fixture.scope, kind) }
        val db = dev.hermes.android.data.ProjectionCache::class.java.getDeclaredField("db").apply { isAccessible = true }.get(app.cache) as dev.hermes.android.data.ProjectionDb
        runBlocking { db.rows().put(dev.hermes.android.data.Projection(key, fixture.scope.key, byteArrayOf(1, 2, 3), System.currentTimeMillis(), true, 3)) }
        try {
            compose.runOnIdle { vm.tab("Kanban") }
            val warning = "The saved task draft could not be read. It has been retained on this phone."
            await { compose.onAllNodesWithText("Review travel options").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(warning).assertIsDisplayed()
            assertNotNull(runBlocking { db.rows().get(key) })
            receipts += "A corrupt encrypted task draft stays retained and its warning remains visible after the board refresh completes."
            capture("audit-kanban-retained-draft-warning")
        } finally {
            runBlocking { if (prior == null) app.cache.delete(fixture.scope, kind) else app.cache.write(fixture.scope, kind, prior, protected = true) }
        }
    }

    @Test fun projectsRecoverWhenTheSameOwnersClientFinishesConnecting() = withFixture { vm, fixture ->
        compose.runOnIdle {
            field(vm, "client", null)
            flow<AppState>(vm, "_ui").value = vm.ui.value.copy(connected = false, loading = true)
            vm.tab("Projects")
        }
        await { vm.data.value["native-projects"]?.string("_error")?.contains("Reconnect") == true }
        compose.runOnIdle {
            field(vm, "client", fixture.client.detached())
            flow<AppState>(vm, "_ui").value = vm.ui.value.copy(connected = true, loading = false)
        }
        await { vm.data.value["native-projects"]?.has("projects") == true }
        assertFalse(vm.data.value.getValue("native-projects").has("_error"))
        val beforeRefresh = fixture.requests.count { it.string("method") == "projects.list" }
        compose.onNodeWithTag("projects-screen").performTouchInput { swipeDown(startY = top + height * 0.3f, endY = bottom) }
        await { fixture.requests.count { it.string("method") == "projects.list" } > beforeRefresh }
        compose.onNodeWithText("Refresh projects").assertDoesNotExist()
        receipts += "Restored Projects reads the installed native client after same-owner reconnect without requiring page re-entry."
        capture("audit-reconnect-projects")
    }

    @Test fun reconnectingDuringAToolWriteDoesNotLeaveControlsDisabled() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.more("Tools & commands") }
        await { vm.data.value["native-tools"] != null && "native-tools" !in vm.refreshing.value }
        fixture.holdNextToolOperation.set(true)
        try {
            compose.onNode(hasText("Web research") and isToggleable()).performScrollTo().performClick()
            assertTrue(fixture.toolOperationCaptured.await(5, java.util.concurrent.TimeUnit.SECONDS))
            compose.runOnIdle {
                field(vm, "client", fixture.client.detached())
                flow<AppState>(vm, "_ui").value = vm.ui.value.copy(notice = "Connection restored")
            }
            await { "native-tools" !in vm.refreshing.value }
            compose.onNodeWithContentDescription("Refresh tools").assertIsEnabled()
            fixture.releaseToolOperation.countDown()
            compose.onNodeWithContentDescription("Refresh tools").performClick()
            await { "native-tools" !in vm.refreshing.value }
            receipts += "A replacement client resets only its own operation latch; old completion cannot disable the new tool controls."
            capture("audit-reconnect-tools")
        } finally { fixture.releaseToolOperation.countDown() }
    }

    @Test fun commandsLeaveTheirPickerAndExplicitRefreshReadsTheServer() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.openCatalog("Toolsets") }
        // The chat's Toolsets sheet is the Settings Tools page itself.
        await { vm.data.value["native-tools"] != null }
        clickText("Browse chat commands")
        await { vm.catalog.value.isBlank() && vm.ui.value.morePage == "Commands" && vm.data.value["slash:catalog"] != null }
        val before = fixture.requests.count { it.string("method") == "commands.catalog" }
        compose.onNodeWithContentDescription("Refresh commands").performClick()
        await { fixture.requests.count { it.string("method") == "commands.catalog" } > before }
        compose.onNodeWithContentDescription("Back").performClick()
        await { vm.ui.value.tab == "Chat" }
        receipts += "The chat's Toolsets sheet holds the Settings Tools page; Browse commands closes the sheet, explicit Refresh reads Hermes, and Back returns to Chat."
        capture("audit-commands-picker-return")
    }

    @Test fun clarificationAnswersSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        withFixture(restoration = restoration) { vm, _ ->
            compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(clarification = json("clarify_id" to "clarify-fixture", "questions" to JSONArray()
                .put(json("qid" to "first", "question" to "First choice"))
                .put(json("qid" to "second", "question" to "Second choice")))) }
            compose.onAllNodesWithText("Your answer").assertCountEquals(2).onFirst().performTextInput("Retain one")
            compose.onAllNodesWithText("Your answer").onLast().performTextInput("Retain two")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Retain one").assertExists()
            compose.onNodeWithText("Retain two").assertExists()
            compose.onNodeWithText("Continue").assertIsEnabled()
            receipts += "Multiple clarification answers survive the Compose saved-state lifecycle and remain submittable."
            capture("audit-restored-questions")
        }
    }

    @Test fun offlineSendReviewPreservesResendUntilConnectedAndKeepsTheDraft() = withFixture { vm, fixture ->
        val scope = fixture.scope.session("chat-a")
        val receipt = PendingSend.create(scope, Draft("Keep this message", 0, 0, emptyList()))
        @Suppress("UNCHECKED_CAST")
        val pending = BeaconViewModel::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(vm) as MutableMap<String, PendingSend>
        runBlocking { app.cache.write(scope, "pending", receipt.encode(), protected = true) }
        try {
            compose.runOnIdle {
                pending[scope.key] = receipt
                field(vm, "client", null); field(vm, "cachedOwnerScope", fixture.scope)
                flow<AppState>(vm, "_ui").value = vm.ui.value.copy(connected = false)
                flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(uncertain = receipt.id)
                vm.composer(TextFieldValue("Keep this message"))
            }
            runBlocking { compose.runOnIdle { vm.reviewPendingSend(resend = true) }.join() }
            assertTrue(vm.ui.value.notice.contains("Reconnect"))
            assertEquals(receipt.encode(), runBlocking { app.cache.read(scope, "pending") })
            runBlocking { compose.runOnIdle { vm.reviewPendingSend() }.join() }
            assertNull(runBlocking { app.cache.read(scope, "pending") })
            assertTrue(vm.chat.value.uncertain.isBlank())
            assertEquals("Keep this message", vm.composer.value.text)
            receipts += "Offline resend keeps its receipt; explicit local review clears only the matching receipt and retains draft text."
            capture("audit-offline-send-review")
        } finally { runBlocking { app.cache.delete(scope, "pending") }; compose.runOnIdle { pending.remove(scope.key) } }
    }

    @Test fun unconfirmedTextSendOffersResendAndDismissOnTheVisibleRow() = withFixture { vm, fixture ->
        val scope = fixture.scope.session("chat-a")
        val receipt = PendingSend.create(scope, Draft("Keep this message", 0, 0, emptyList()))
        @Suppress("UNCHECKED_CAST")
        val pending = BeaconViewModel::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(vm) as MutableMap<String, PendingSend>
        runBlocking { app.cache.write(scope, "pending", receipt.encode(), protected = true) }
        try {
            compose.runOnIdle {
                pending[scope.key] = receipt
                flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(uncertain = receipt.id)
                vm.composer(TextFieldValue("Keep this message"))
            }
            compose.onNodeWithTag("send-resend").assertIsDisplayed().performClick()
            compose.onNodeWithText("Resend retained draft").assertIsDisplayed()
            compose.runOnIdle { vm.dismissForm() }
            compose.onNodeWithTag("send-dismiss").performClick()
            await { vm.chat.value.uncertain.isBlank() }
            assertNull(runBlocking { app.cache.read(scope, "pending") })
            assertEquals("Keep this message", vm.composer.value.text)
            receipts += "A text-only unconfirmed send shows Resend and Dismiss on the chat row; Resend asks first, Dismiss clears the receipt and keeps the draft."
            capture("journey3-unconfirmed-send-row")
        } finally { runBlocking { app.cache.delete(scope, "pending") }; compose.runOnIdle { pending.remove(scope.key) } }
    }

    @Test fun historyRowsWithTheSupportedIdAliasOpenTheRightConversation() = withFixture { vm, fixture ->
        compose.runOnIdle {
            val rows = listOf("chat-a", "chat-b").map { fixture.snapshot(it).apply { put("id", getString("session_id")); remove("session_id") } }
            flow<AppState>(vm, "_ui").value = vm.ui.value.copy(sessions = rows)
            vm.toggleSessions(true)
        }
        compose.onNodeWithText("Reading list").performScrollTo().performClick()
        await { vm.chat.value.sessionId == "chat-b" }
        receipts += "Legacy-compatible id-only rows have distinct keys and open their exact native conversation."
        capture("audit-history-id-alias")
    }

    @Test fun stalePromptButtonsCannotApproveOrAnswerTheNextRequest() = withFixture { vm, fixture ->
        fun approval(id: String) = json("request_id" to "queue-$id", "command" to "Synthetic $id", "choices" to JSONArray().put("once").put("deny"))
        fun shownApproval(id: String) = json("approval_id" to "queue-$id", "command" to "Synthetic $id", "choices" to JSONArray().put("once").put("deny"))
        fun question(id: String) = json("questions" to JSONArray().put(json("qid" to "qid-$id", "question" to "Synthetic $id", "choices" to JSONArray().put("Choose this"))))
        val subscriptionsBeforeSend = fixture.streamSubscriptions.get()
        compose.runOnIdle { vm.composer(TextFieldValue("Ask for permission")); vm.send() }
        await { fixture.requests.any { it.string("method") == "prompt.submit" } && fixture.streamSubscriptions.get() > subscriptionsBeforeSend }

        fixture.emitNativeServerRequest("approval", "wire-old-approval", approval("old-approval"))
        await { vm.chat.value.approval?.string("approval_id") == "queue-old-approval" }
        val staleApproval = requireNotNull(compose.onNodeWithText("Allow once").performScrollTo().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        fixture.emitNativeServerRequest("approval", "wire-new-approval", approval("new-approval"))
        await { vm.chat.value.approval?.string("approval_id") == "queue-new-approval" }
        compose.runOnIdle { assertTrue(staleApproval()); assertTrue(vm.ui.value.notice.contains("request changed")) }
        compose.waitForIdle()
        assertTrue(fixture.nativeServerResponses.isEmpty())
        fixture.emitNativeEvent("request.cancel", json("id" to "wire-old-approval", "method" to "approval", "reason" to "test"))
        compose.runOnIdle { vm.clearMessage() }
        await { compose.onAllNodesWithText("This request changed. Refresh it before responding.").fetchSemanticsNodes().isEmpty() }
        await { compose.onAllNodesWithText("Synthetic new-approval").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Allow once").performScrollTo().performClick()
        await { fixture.nativeServerResponses.size == 1 }
        assertEquals("wire-new-approval", fixture.nativeServerResponses.last().getString("id"))
        assertEquals("once", fixture.nativeServerResponses.last().getJSONObject("result").getString("choice"))
        await { vm.chat.value.approval == null }

        compose.runOnIdle {
            fixture.onPromptResponse = { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(approval = shownApproval("replacement-approval")) }
        }
        fixture.emitNativeServerRequest("approval", "wire-racing-approval", approval("racing-approval"))
        await { vm.chat.value.approval?.string("approval_id") == "queue-racing-approval" }
        compose.onNodeWithText("Allow once").performScrollTo().performClick()
        await { fixture.nativeServerResponses.size == 2 }
        assertEquals("wire-racing-approval", fixture.nativeServerResponses.last().getString("id"))
        await { vm.chat.value.approval?.string("approval_id") == "queue-replacement-approval" }

        compose.runOnIdle {
            fixture.rejectPromptResponse = true
        }
        fixture.emitNativeServerRequest("approval", "wire-failed-approval", approval("failed-approval"))
        await { vm.chat.value.approval?.string("approval_id") == "queue-failed-approval" }
        compose.onNodeWithText("Allow once").performScrollTo().performClick()
        await { vm.ui.value.error == dev.hermes.android.data.APP_CONNECTION_ERROR }
        assertEquals(2, fixture.nativeServerResponses.size)
        assertEquals("A failed socket send retains the displayed prompt", "queue-failed-approval", vm.chat.value.approval?.string("approval_id"))
        fixture.emitNativeEvent("request.cancel", json("id" to "wire-failed-approval", "method" to "approval", "reason" to "test"))
        await { vm.chat.value.approval == null }
        compose.runOnIdle {
            vm.clearMessage()
        }
        fixture.emitNativeServerRequest("clarify", "wire-old-question", question("old-question"))
        await { vm.chat.value.clarification?.string("clarify_id") == "wire-old-question" }
        compose.onNodeWithText("Your answer").performScrollTo().performTextInput("Old answer")
        val staleAnswer = requireNotNull(compose.onNodeWithText("Continue").performScrollTo().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        fixture.emitNativeServerRequest("clarify", "wire-new-question", question("new-question"))
        await { vm.chat.value.clarification?.string("clarify_id") == "wire-new-question" }
        compose.runOnIdle { assertTrue(staleAnswer()); assertTrue(vm.ui.value.notice.contains("request changed")) }
        compose.waitForIdle()
        assertEquals(2, fixture.nativeServerResponses.size)
        fixture.emitNativeEvent("request.cancel", json("id" to "wire-old-question", "method" to "clarify", "reason" to "test"))
        compose.runOnIdle { vm.clearMessage() }
        await { compose.onAllNodesWithText("This request changed. Refresh it before responding.").fetchSemanticsNodes().isEmpty() }
        await { compose.onAllNodesWithText("Synthetic new-question").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Your answer").performScrollTo().performTextInput("Keep rollback ready")
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        await { fixture.nativeServerResponses.size == 3 }
        assertEquals("wire-new-question", fixture.nativeServerResponses.last().getString("id"))
        assertEquals("Keep rollback ready", fixture.nativeServerResponses.last().getJSONObject("result").getJSONObject("answers").getString("qid-new-question"))
        assertEquals("submitted", fixture.nativeServerResponses.last().getJSONObject("result").getString("outcome"))
        await { vm.chat.value.clarification == null }
        assertTrue(fixture.requests.none { it.string("method") in setOf("approval.respond", "request.answer") })
        receipts += "A retained click from a replaced approval/question sends nothing; fresh actions carry only their displayed request ID."
        capture("audit-prompt-identity")
    }

    @Test fun restoringALongComposerIsNotTreatedAsAPaste() = withFixture { vm, fixture ->
        val text = "Retained long draft. ".repeat(600)
        compose.runOnIdle {
            vm.storeData("profile-settings", json("large_text_paste_as_attachment" to true))
            vm.composer(TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length)))
        }
        compose.waitForIdle()
        assertEquals(text, vm.composer.value.text)
        assertTrue(vm.attachments.value.isEmpty())
        assertFalse(fixture.requests.any { it.string("path").contains("upload") })
        compose.runOnIdle { vm.composer(TextFieldValue()) }
        compose.onNodeWithTag("message-input").performTextInput(text)
        await { vm.attachments.value.size == 1 }
        val staged = vm.attachments.value.single()
        assertEquals("pasted-text.txt", staged.string("name"))
        assertEquals(text, String(android.util.Base64.decode(staged.string("staged_base64"), android.util.Base64.DEFAULT), Charsets.UTF_8))
        assertTrue(vm.composer.value.text.isBlank())
        assertFalse(fixture.requests.any { it.string("path").contains("upload") })
        receipts += "Restored long text stays in the composer; an editor paste stages its exact bytes locally without sending them."

    }

    @Test fun scheduleControlsCreatePauseResumeRunOpenHistoryAndDelete() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.tab("Tasks") }
        await { vm.data.value["crons"] != null }
        clickLabel("Pause"); await { fixture.jobs.getValue("task-a").optBoolean("paused") }
        compose.waitForIdle(); clickLabel("Resume"); await { !fixture.jobs.getValue("task-a").optBoolean("paused") }
        assertTrue(compose.onAllNodesWithText("Run now", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        clickLabel("Run"); await { vm.form.value != null }
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(fixture.requests.any { it.string("path").endsWith("/trigger") })
        clickLabel("Run"); await { vm.form.value != null }
        compose.onAllNodesWithText("Run", useUnmergedTree = true).onLast().performClick()
        await { vm.form.value == null && fixture.requests.any { it.string("path").endsWith("/trigger") } }
        clickLabel("Run history"); await { vm.data.value["cron-history:task-a"] != null }
        clickText("Synthetic completed run"); await { vm.chat.value.sessionId == "chat-b" }
        compose.runOnIdle { vm.tab("Tasks") }
        clickLabel("Delete"); await { vm.form.value != null }
        compose.onAllNodesWithText("Delete", useUnmergedTree = true).onLast().performClick()
        await { fixture.jobs.isEmpty() && vm.form.value == null }
        clickText("Create schedule"); await { vm.form.value != null }
        compose.runOnIdle { vm.formValue("name", "Owned schedule fixture"); vm.formValue("prompt", "Summarize the synthetic workspace"); vm.formValue("schedule", "every 2h") }
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        await { fixture.jobs.containsKey("task-new") && vm.form.value == null }
        assertEquals("Owned schedule fixture", fixture.jobs.getValue("task-new").string("name"))
        clickText("Check-up calls")
        compose.onNodeWithText("Turn on").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Create vetted mail task").assertDoesNotExist()
        receipts += "Schedule create, pause, resume, run/cancel, history/result and delete use actual controls; check-up calls respect their prerequisites and the retired mail preset is absent."
        capture("audit-schedule-workflow")
    }

    @Test fun stoppedSchedulerIsExplainedOnceAndJobStatusStaysShort() = withFixture { vm, fixture ->
        fixture.jobs.getValue("task-a").put("scheduler_heartbeat_age_s", 200)
        fixture.jobs["task-b"] = JSONObject(fixture.jobs.getValue("task-a").toString()).put("id", "task-b").put("name", "Evening summary")
        compose.runOnIdle { vm.tab("Tasks") }
        await { vm.data.value["crons"]?.rows("jobs")?.size == 2 }
        compose.onNodeWithText("Schedules aren't running. Start Hermes on your computer.").assertExists()
        compose.onAllNodesWithText("Schedules aren't running. Start Hermes on your computer.").assertCountEquals(1)
        compose.onAllNodesWithText("Scheduler needs attention").assertCountEquals(0)
        compose.onAllNodesWithText("Waiting", substring = true).assertCountEquals(2)
    }

    @Test fun kanbanStartsOnTheFirstColumnWithTasksAndShowsTheBoardNameOnce() = withFixture { vm, fixture ->
        fixture.boardTasks.getValue("t_fixture").put("status", "done")
        compose.runOnIdle { vm.navigate("Kanban") }
        await { compose.onAllNodesWithText("Review travel options").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Done 1") and isSelectable()).assertIsSelected()
        compose.onAllNodesWithText("Personal").assertCountEquals(1)
    }

    @Test fun kanbanCompletionSendsResultAndOnlyWarnsForRunningTasks() = withFixture { vm, fixture ->
        fixture.boardTasks["t_claimed"] = json("id" to "t_claimed", "title" to "Claimed task", "body" to "In progress", "status" to "running", "assignee" to "coder", "priority" to 1)
        compose.runOnIdle { vm.navigate("Kanban") }
        await { compose.onAllNodesWithText("Review travel options").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("Task actions").onFirst().performClick()
        compose.onNode(hasText("Move to Done") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithTag("kanban-completion-result").assertExists()
        compose.onNodeWithText("A worker is actively working on this task. This change will override its claim.").assertDoesNotExist()
        compose.onNode(hasText("Move") and hasClickAction() and hasAnyAncestor(isDialog())).assertIsNotEnabled()
        compose.onNodeWithTag("kanban-completion-result").performTextInput("Reviewed the work and confirmed the result.")
        compose.onNode(hasText("Move") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        await { fixture.boardTasks.getValue("t_fixture").string("status") == "done" }
        val patch = fixture.requests.single { it.string("method") == "PATCH" && it.string("path").endsWith("/tasks/t_fixture") }
        assertEquals("done", patch.getJSONObject("body").string("status"))
        assertEquals("Reviewed the work and confirmed the result.", patch.getJSONObject("body").string("result"))

        compose.onNode(hasText("Running 1") and isSelectable()).performClick()
        compose.onAllNodesWithContentDescription("Task actions").onFirst().performClick()
        compose.onNode(hasText("Move to Done") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("A worker is actively working on this task. This change will override its claim.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun kanbanMoveEditAndDeletingTheLastArchivedCardKeepAValidColumn() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.navigate("Kanban") }
        await { compose.onAllNodesWithText("Review travel options").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("kanban-edit:t_fixture").performClick()
        compose.onNodeWithTag("kanban-title").performTextReplacement("Review the itinerary")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        await { fixture.boardTasks.getValue("t_fixture").string("title") == "Review the itinerary" }
        compose.onAllNodesWithContentDescription("Task actions").onFirst().performClick()
        // A move between idle columns applies at once; only Done, Ready, Delete and moves off a running task ask first.
        compose.onNode(hasText("Move to Archived") and hasAnyAncestor(isPopup())).performScrollTo().performClick()
        await { fixture.boardTasks.getValue("t_fixture").string("status") == "archived" }
        compose.onAllNodesWithContentDescription("Task actions").onFirst().performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isPopup())).performClick()
        compose.onNode(hasText("Delete") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        await { fixture.boardTasks.isEmpty() }
        // The emptied Archived column is hidden, and the board falls back to an existing column.
        compose.onAllNodes(hasText("Archived", substring = true) and isSelectable()).assertCountEquals(0)
        compose.onNode(hasText("To do 0") and isSelectable()).assertIsSelected()
        receipts += "Kanban edit and archive use native receipts; deleting the final archived card selects an existing column without a stranded page."
        capture("audit-kanban-lifecycle")
    }

    @Test fun kanbanRetryKeepsTheServerSelectedBoard() = withFixture { vm, fixture ->
        fixture.boardFailure = true
        fixture.preferredBoard = "work"
        compose.runOnIdle { vm.navigate("Kanban") }
        await { compose.onAllNodesWithText("Couldn't load boards. Check your connection and try again.").fetchSemanticsNodes().isNotEmpty() }
        fixture.boardFailure = false
        compose.onNodeWithText("Retry", useUnmergedTree = true).assertIsDisplayed().performClick()
        await { compose.onAllNodesWithText("Work").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Personal").assertCountEquals(0)
        await { compose.onAllNodesWithText("Review travel options").fetchSemanticsNodes().isNotEmpty() }
        receipts += "Recovering an offline Kanban catalog selects the native current board, with shared retry ownership and error handling."
        capture("audit-kanban-retry")
    }

    @Test fun sharedSpaceCreationSendRetryApprovalAndUnsupportedRecoveryKeepTheirIdentity() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.navigate("Bots") }
        await { vm.ui.value.botModeEnabled && !vm.ui.value.botModeSwitching && vm.botMode.state.value.bots.size == 2 && !vm.botMode.state.value.loading }
        compose.onNodeWithContentDescription("New shared space").performClick()
        for (name in listOf("default", "coder")) {
            val choice = compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag("bot-mode-home"))).assertIsToggleable()
            if (name !in vm.botMode.state.value.selectedBots) choice.performScrollTo().performClick()
            choice.assertIsOn()
        }
        compose.onNodeWithText("Create shared space").performScrollTo().performClick()
        await { vm.botMode.state.value.selectedSpace == "fixture-room" && !vm.botMode.state.value.loading }
        fixture.rejectRoomSend = true
        compose.onNodeWithTag("bot-shared-composer").performTextInput("Plan the synthetic review")
        compose.onNodeWithTag("bot-shared-send").performClick()
        await { !vm.botMode.state.value.loading && vm.botMode.state.value.error.isNotBlank() }
        assertEquals("Plan the synthetic review", vm.botMode.state.value.draft)
        fixture.rejectRoomSend = false
        compose.onNodeWithTag("bot-shared-send").performClick()
        await { vm.botMode.state.value.draft.isBlank() && !vm.botMode.state.value.loading }
        // Only shared-space sends: stock history reads also end in /messages.
        val sends = fixture.requests.filter { it.string("path").endsWith("/space/fixture-room/messages") }
        assertEquals(2, sends.size)
        assertEquals(sends[0].getJSONObject("body").string("event_id"), sends[1].getJSONObject("body").string("event_id"))
        assertEquals(1, fixture.roomEvents.size)
        for ((choice, label) in listOf("once" to "Allow once", "deny" to "Deny")) {
            fixture.roomActions += json("kind" to "approval", "task_id" to "task-$choice", "member_id" to "coder", "request_id" to "request-$choice", "execution_generation" to 1,
                "approval" to json("description" to "Inspect a synthetic file", "command" to "echo fixture", "choices" to org.json.JSONArray(listOf("once", "deny"))))
            compose.runOnIdle { vm.botMode.refresh() }
            await { vm.botMode.state.value.actions.isNotEmpty() && !vm.botMode.state.value.loading }
            compose.onNodeWithText(label).performScrollTo().performClick()
            await { fixture.roomActions.isEmpty() && !vm.botMode.state.value.loading }
            assertEquals(choice, fixture.requests.last { it.string("path").endsWith("/action") }.getJSONObject("body").string("choice"))
        }
        fixture.roomActions += json("kind" to "retry", "task_id" to "task-retry")
        compose.runOnIdle { vm.botMode.refresh() }
        await { vm.botMode.state.value.actions.isNotEmpty() && !vm.botMode.state.value.loading }
        compose.onNodeWithText("This Hermes version cannot safely resolve work", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry bot task").assertDoesNotExist()
        assertEquals(1, fixture.roomActions.size)
        assertFalse(fixture.requests.any { it.string("path").endsWith("/action") && it.getJSONObject("body").string("kind") == "retry" })
        receipts += "Shared-space send retry keeps its event identity; supported allow/deny bind to the displayed request while ambiguous task recovery remains unavailable without replay."
        capture("audit-shared-space-actions")
    }

    @Test fun messageCopyShareAndBothBranchActionsUseTheSelectedMessage() = withFixture { vm, fixture ->
        // Hermes keeps model-switch steering as a user row: hidden here, but counted by branch keep_count.
        fixture.sessions.getValue("chat-a").put("messages", JSONArray().put(json("role" to "user", "text" to "[System: The active model changed]", "row_id" to 9)).apply { fixture.sessions.getValue("chat-a").rows("messages").forEach { put(it) } })
        compose.runOnIdle { vm.selectSession("chat-a") }
        await { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady && vm.chat.value.messages.size == 3 }
        val original = vm.chat.value.messages[1]
        val latestAssistant = vm.chat.value.messages.last { it.role == "assistant" && it.text.isNotBlank() }
        // No always-visible row: the actions are in the menu a long-press opens, attached to the bubble.
        compose.onNodeWithTag("message-actions:${latestAssistant.id}").assertDoesNotExist()
        compose.onNodeWithTag("message-actions:${original.id}").assertDoesNotExist()
        fun openMenu() {
            compose.onNodeWithTag("message-bubble:${original.id}").performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithTag("message-actions:${original.id}").assertExists()
        }
        openMenu()
        listOf("Copy", "Quote in reply", "Share", "Read aloud", "Copy source", "Branch from here", "Edit and branch").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Copy source").performClick()
        val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals(original.text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        openMenu()
        compose.onNodeWithText("Share").performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        await { automation.rootInActiveWindow?.packageName?.toString()?.let { it != app.packageName } == true }
        assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitForIdle()
        openMenu()
        compose.onNodeWithText("Branch from here").performClick()
        await { vm.chat.value.sessionId.startsWith("branch-") }
        await { vm.ui.value.notice == "Branched into a new conversation" }
        compose.onNodeWithText("Branched into a new conversation").assertExists()
        // History lists the new branch without a manual refresh, and the open branch carries its own title, not its parent's.
        await { vm.ui.value.sessions.any { it.string("session_id") == vm.chat.value.sessionId } }
        assertEquals("Branch", vm.chat.value.title)
        assertEquals(2, fixture.requests.last { it.string("method") == "session.branch" }.getJSONObject("params").getInt("count"))
        compose.runOnIdle { vm.selectSession("chat-a") }
        await { vm.chat.value.sessionId == "chat-a" && vm.chat.value.messages.isNotEmpty() }
        openMenu()
        compose.onNodeWithText("Edit and branch").performClick()
        await { vm.chat.value.sessionId.startsWith("branch-") && vm.composer.value.text == original.text }
        assertEquals(1, fixture.requests.last { it.string("method") == "session.branch" }.getJSONObject("params").getInt("count"))
        assertFalse(fixture.requests.any { it.string("method") == "prompt.submit" })
        receipts += "Copy source preserves the selected message; system Share opens and cancels without sending; branch and edit/branch use exact native message boundaries."
        capture("audit-message-actions")
    }

    @Test fun systemSteeringNoteStaysInRawHistoryButIsNotRendered() = withFixture { vm, _ ->
        val before = vm.chat.value
        val note = json("id" to "system-steering", "role" to "user", "text" to "[System: The active model changed]")
        val message = Message.from(note, before.messages.size, before.sessionId)
        val raw = before.raw.copy().put("messages", JSONArray().apply { before.raw.rows("messages").forEach { put(it.copy()) }; put(note.copy()) })
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = before.copy(messages = before.messages + message, raw = raw) }
        compose.waitForIdle()

        compose.onNodeWithTag("message-row:${message.id}").assertDoesNotExist()
        assertTrue(vm.chat.value.messages.any { it.id == message.id })
        assertTrue(vm.chat.value.raw.rows("messages").any { it.string("text").startsWith("[System:") })
        receipts += "Hermes system steering rows stay in raw history but never become transcript bubbles."
    }

    @Test fun nativePdfPagerAndJsonTreeHaveWorkingBoundaries() = withFixture { vm, fixture ->
        val pdf = android.graphics.pdf.PdfDocument()
        val bytes = java.io.ByteArrayOutputStream()
        try {
            for (number in 1..2) {
                val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(320, 240, number).create())
                page.canvas.drawText("Synthetic page $number", 20f, 50f, android.graphics.Paint().apply { textSize = 18f })
                pdf.finishPage(page)
            }
            pdf.writeTo(bytes)
        } finally { pdf.close() }
        fixture.downloads["C:/synthetic-workspace/reader.pdf"] = bytes.toByteArray()
        fun message(text: String) = compose.runOnIdle {
            flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = listOf(Message.from(json("row_id" to 910, "role" to "assistant", "content" to text), 0, "chat-a")))
        }
        message("[Read test PDF](/api/file/raw?path=C:/synthetic-workspace/reader.pdf)")
        val textLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNode(inConversation(hasText("Read test PDF")), useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
        val linkPosition = textLayouts.single().getBoundingBox(2).center
        compose.onNode(inConversation(hasText("Read test PDF")), useUnmergedTree = true).performTouchInput { click(linkPosition) }
        await { compose.onAllNodesWithContentDescription("PDF page 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pdf-page-count").assertTextEquals("1 / 2")
        compose.onNodeWithContentDescription("Previous PDF page").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next PDF page").performScrollTo().performClick()
        await { compose.onAllNodesWithContentDescription("PDF page 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pdf-page-count").assertTextEquals("2 / 2")
        compose.onNodeWithContentDescription("Next PDF page").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous PDF page").performClick()
        await { compose.onAllNodesWithContentDescription("PDF page 1").fetchSemanticsNodes().isNotEmpty() }
        capture("audit-pdf-pager")
        compose.onNodeWithText("Close").performClick()
        message("```json\n{\"plan\": [1, 2]}\n```")
        compose.onNodeWithContentDescription("Tree").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Source").performClick()
        compose.onNodeWithContentDescription("Tree").assertIsDisplayed()
        receipts += "Native PDF page navigation reaches both pages and disables boundaries; its dialog closes, and JSON switches between source and tree."
    }

    @Test fun pcFileCreationAndSystemDownloadKeepTheExactBytes() {
        val picker = PickerRegistry(app)
        withFixture(picker) { vm, fixture ->
            val file = File(app.cacheDir, "exports/audit-downloaded.txt").apply { parentFile!!.mkdirs(); writeText("") }
            try {
                compose.runOnIdle { vm.navigate("Files") }
                await { vm.data.value["files"] != null }
                // Hidden dot folders stay out of the list until asked for.
                await { compose.onAllNodesWithText("research").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(".cache").assertDoesNotExist()
                compose.onNodeWithTag("files-show-hidden").performClick()
                await { compose.onAllNodesWithText(".cache").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("files-show-hidden").performClick()
                compose.onNodeWithContentDescription("Create file").performClick()
                await { vm.form.value != null }
                compose.runOnIdle { vm.formValue("path", "C:/synthetic-workspace/created-audit.txt"); vm.formValue("content", "Exact synthetic text") }
                compose.onNodeWithText("Save").performClick()
                await { fixture.workspaceFiles.containsKey("created-audit.txt") && vm.form.value == null }
                compose.onNode(hasContentDescription("File actions") and hasAnyAncestor(hasText("created-audit.txt"))).performScrollTo().performClick()
                compose.onNodeWithText("Download").performClick()
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent!!.action)
                val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", file)
                compose.runOnUiThread { picker.deliver(listOf(uri)) }
                await { file.readText() == "Exact synthetic text" }
                receipts += "PC Create file uses the native action; Download opens Android's document destination and writes the exact returned bytes."
            } finally { file.delete() }
        }
    }

    @Test fun searchingWhileHistoryArrivesAndMalformedQuestionsRemainRecoverable() = withFixture { vm, _ ->
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = emptyList(), anchor = 15, anchorId = "row-15") }
        compose.onNodeWithContentDescription("Search this conversation").performClick()
        val search = compose.onNode(hasSetTextAction() and hasText("Find in conversation"))
        search.performTextInput("needle")
        compose.runOnIdle {
            flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = (0 until 60).map { i ->
                Message.from(json("row_id" to "row-$i", "role" to "assistant", "content" to if (i == 15) "The needle is here" else "History row $i"), i, "chat-a")
            })
        }
        compose.onNodeWithText("The needle is here").assertExists()
        compose.onNode(hasSetTextAction() and hasText("needle")).performTextReplacement("no matching phrase")
        compose.onNodeWithText("No messages match your search.").assertExists()
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.onNodeWithText("No messages match your search.").assertDoesNotExist()
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(clarification = json("clarify_id" to "broken-question", "questions" to org.json.JSONArray().put(json("question" to "Missing identity")))) }
        compose.onNodeWithText("Refresh question").performScrollTo().performClick()
        await { vm.chat.value.clarification == null && vm.chat.value.messages.size == 2 }
        assertTrue(vm.ui.value.error.isBlank())
        receipts += "Search remains responsive as history arrives, shows an empty result, and closes cleanly. Malformed questions offer recovery and cannot submit a partial answer."
        capture("audit-search-recovery")
    }

    @Test fun multiFilePickerStagesEachFileAndRejectsAChangedConversation() {
        val picker = PickerRegistry(app)
        withFixture(picker) { vm, fixture ->
            val directory = File(app.cacheDir, "exports").apply { mkdirs() }
            val one = File(directory, "picker-first.txt").apply { writeText("First selected file") }
            val two = File(directory, "picker-second.txt").apply { writeText("Second selected file") }
            fun uri(file: File) = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", file)
            // Attach opens a Photos/Files menu; only the chosen entry launches the system picker.
            fun openPicker(entry: String) {
                compose.onNodeWithTag("composer-attach").performClick()
                compose.onNodeWithTag("attach-menu").assertIsDisplayed()
                compose.onNodeWithTag(entry).performClick()
                await { compose.onAllNodesWithTag("attach-menu").fetchSemanticsNodes().isEmpty() }
            }
            fun openFiles() = openPicker("attach-files")
            try {
                compose.onNodeWithTag("composer-attach").performClick()
                compose.onNodeWithTag("attach-photos").assertIsDisplayed()
                compose.onNodeWithTag("attach-files").assertIsDisplayed()
                compose.onAllNodesWithTag("attach-paste-image").assertCountEquals(0)
                compose.onAllNodesWithTag("attach-commands").assertCountEquals(0)
                assertNull("Opening the menu alone launches nothing", picker.intent)
                compose.onNodeWithTag("attach-photos").performClick()
                await { compose.onAllNodesWithTag("attach-menu").fetchSemanticsNodes().isEmpty() }
                assertTrue("Photos uses the system visual media contract", picker.contract is ActivityResultContracts.PickVisualMedia)
                assertNotEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent?.action)
                compose.runOnUiThread { picker.deliver(emptyList()) }
                assertTrue(vm.attachments.value.isEmpty())
                openFiles()
                assertTrue("Files retains the document picker contract", picker.contract is ActivityResultContracts.OpenMultipleDocuments)
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent?.action)
                assertTrue(picker.intent!!.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
                assertArrayEquals("Files asks the system picker for any document", arrayOf("*/*"), picker.intent!!.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
                compose.runOnUiThread { picker.deliver(listOf(uri(one), uri(two), uri(one))) }
                await { vm.attachments.value.size == 2 && vm.attachmentPreparation.value.isEmpty() }
                assertEquals(setOf(one.name, two.name), vm.attachments.value.map { it.string("name") }.toSet())
                assertEquals(setOf("First selected file", "Second selected file"), vm.attachments.value.map { String(android.util.Base64.decode(it.string("staged_base64"), android.util.Base64.DEFAULT)) }.toSet())
                compose.onNodeWithTag("composer-attachments").assertIsDisplayed()
                capture("multiple-attachment-chips")
                compose.onNodeWithContentDescription("Remove ${one.name}").performScrollTo().performClick()
                await { vm.attachments.value.size == 1 }
                assertEquals(two.name, vm.attachments.value.single().string("name"))
                openFiles()
                compose.runOnUiThread { picker.deliver(emptyList()) }
                assertEquals(1, vm.attachments.value.size)
                openFiles()
                compose.runOnUiThread { picker.deliver(listOf(uri(one), Uri.parse("content://missing-fixture/not-found"))) }
                await { vm.attachments.value.size == 2 && vm.attachmentPreparation.value.any { it.string("status") == "error" } }
                assertTrue(vm.attachmentPreparation.value.single().string("error").isNotBlank())
                compose.runOnIdle { vm.dismissAttachmentPreparation(vm.attachmentPreparation.value.single().string("id")) }
                openFiles()
                compose.runOnIdle { vm.selectSession("chat-b") }
                await { vm.chat.value.sessionId == "chat-b" }
                compose.runOnUiThread { picker.deliver(listOf(uri(one), uri(two))) }
                compose.waitForIdle()
                assertTrue(vm.attachments.value.isEmpty())
                assertTrue(vm.attachmentPreparation.value.isEmpty())
                assertFalse(fixture.requests.any { it.string("method") == "prompt.submit" })
                receipts += "Attach opens Photos/Files; Photos requests image/* and Files */*. System multi-document result preserves each file, deduplicates selection, supports removal/cancel/failure and rejects stale conversation results. No prompt sent."
            } finally { one.delete(); two.delete() }
        }
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class) @Test fun imageClipboardPasteAttachesToTheComposerAndShowsAThumbnail() = withFixture { vm, fixture ->
        val image = File.createTempFile("clipboard-image-", ".png", File(app.cacheDir, "exports").apply { mkdirs() })
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            image.outputStream().use { assertTrue(compress(Bitmap.CompressFormat.PNG, 100, it)) }
            recycle()
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", image)
        try {
            compose.runOnUiThread {
                app.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newUri(app.contentResolver, "Clipboard image", uri)
                )
            }
            compose.onNodeWithTag("message-input").performClick().performKeyInput {
                keyDown(Key.CtrlLeft); pressKey(Key.V); keyUp(Key.CtrlLeft)
            }
            await { vm.attachments.value.any { it.string("name") == image.name } }
            assertEquals("Image paste attaches without inserting a URI or path into the draft", "", vm.composer.value.text)
            compose.onNodeWithTag("composer-attachments").assertIsDisplayed()
            await { compose.onAllNodesWithTag("pending-attachment-thumbnail", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pending-attachment-thumbnail", useUnmergedTree = true).assertIsDisplayed()
            val attachment = vm.attachments.value.single()
            assertEquals("image/png", attachment.string("mime"))
            assertEquals(fixture.scope.session("chat-a").key, attachment.string("scope_key"))
            assertArrayEquals(image.readBytes(), android.util.Base64.decode(attachment.string("staged_base64"), android.util.Base64.NO_WRAP))
            assertFalse(fixture.requests.any { it.string("path") == "/api/upload" || it.string("method") == "prompt.submit" })
        } finally {
            compose.runOnUiThread { app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", "")) }
            image.delete()
        }
    }

    @Test fun historyImageReferenceShowsOnlyItsSafeFilename() = withFixture { vm, _ ->
        val path = "C:\\SyntheticOwner\\Documents\\.beacon-attachments\\123e4567-e89b-12d3-a456-426614174000-private-photo.png"
        compose.runOnIdle {
            flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = listOf(
                Message("history-image", "user", "Please check this image:\n@image:$path\nThanks")
            ))
        }
        await { compose.onAllNodesWithText("private-photo.png").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("private-photo.png").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(path, substring = true).assertCountEquals(0)
        // Long press already opens More; tapping its anchor again would dismiss the popup.
        compose.onNodeWithTag("message-bubble:history-image").performTouchInput { longClick() }
        compose.onNodeWithTag("copy-source-action").performScrollTo()
        compose.onNodeWithTag("copy-source-icon", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("copy-source-action").performClick()
        val copied = requireNotNull(app.getSystemService(ClipboardManager::class.java).primaryClip).getItemAt(0).coerceToText(app).toString()
        assertFalse(copied.contains(path))
    }

    @Test fun interruptedTurnShowsStoppedInsteadOfHermesError() = withFixture { vm, _ ->
        compose.runOnIdle {
            flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = listOf(
                Message("stopped-turn", "assistant", "Operation interrupted: waiting for model response (6.8s elapsed).")
            ))
        }
        await { compose.onAllNodesWithTag("message-stopped:stopped-turn").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("message-stopped:stopped-turn").performScrollTo().assertTextEquals("Stopped")
        compose.onAllNodesWithText("Operation interrupted", substring = true).assertCountEquals(0)
    }

    @Test fun earlierMessageReasoningStaysAboveItsAnswerAndSphereOnlyShowsWhenEmpty() = withFixture { vm, _ ->
        compose.runOnIdle {
            flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = listOf(
                Message("reasoned-answer", "assistant", "The answer is ready.", reasoning = "Reasoning stays with this message.")
            ))
        }
        await { compose.onAllNodesWithTag("message-reasoning:reasoned-answer").fetchSemanticsNodes().isNotEmpty() }
        val reasoning = compose.onNodeWithTag("message-reasoning:reasoned-answer").performScrollTo().fetchSemanticsNode().boundsInRoot
        val answer = compose.onNodeWithTag("message-bubble:reasoned-answer").performScrollTo().fetchSemanticsNode().boundsInRoot
        assertTrue("Reasoning belongs above its answer", reasoning.top < answer.top)
        compose.onNodeWithTag("message-bubble:reasoned-answer").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithTag("copy-source-action").performScrollTo()
        compose.onNodeWithTag("copy-source-icon", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("copy-source-action").performClick()
        compose.onAllNodesWithTag("chat-sphere-background").assertCountEquals(0)
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = emptyList()) }
        await { compose.onAllNodesWithTag("chat-sphere-empty").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-sphere-empty").assertIsDisplayed()
        compose.onAllNodesWithTag("chat-sphere-background").assertCountEquals(0)
        compose.onAllNodesWithText("Model", substring = false).assertCountEquals(0)
        compose.onAllNodesWithText("Effort", substring = false).assertCountEquals(0)
    }

    @Test fun cameraResultRestoresItsConversationScopeAfterSavedStateRecreation() {
        val picker = PickerRegistry(app)
        val restoration = StateRestorationTester(compose)
        withFixture(picker, restoration) { vm, fixture ->
            fun photos() = vm.attachments.value.count { it.string("name").startsWith("photo-") }
            fun takePhotoThenRestore() {
                picker.intent = null
                compose.onNodeWithTag("composer-attach").performClick()
                compose.onNodeWithTag("attach-camera").performClick()
                if (picker.intent?.action.orEmpty().contains("REQUEST_PERMISSIONS")) picker.deliver(true)
                await { picker.intent?.action == android.provider.MediaStore.ACTION_IMAGE_CAPTURE }
                restoration.emulateSavedInstanceStateRestore()
                // Like the camera app: write the photo to the FileProvider URI before returning RESULT_OK.
                @Suppress("DEPRECATION") val output = requireNotNull(picker.intent?.getParcelableExtra<Uri>(android.provider.MediaStore.EXTRA_OUTPUT))
                app.contentResolver.openOutputStream(output)!!.use { it.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())) }
            }
            takePhotoThenRestore()
            picker.deliver(Activity.RESULT_OK)
            await { photos() == 1 }
            assertTrue(vm.attachmentPreparation.value.isEmpty())

            // After process death the result arrives while the restored client is still connecting.
            takePhotoThenRestore()
            compose.runOnIdle { field(vm, "client", null); flow<AppState>(vm, "_ui").let { it.value = it.value.copy(connected = false) } }
            picker.deliver(Activity.RESULT_OK)
            compose.waitForIdle()
            assertEquals(1, photos())
            compose.runOnIdle { field(vm, "client", fixture.client); flow<AppState>(vm, "_ui").let { it.value = it.value.copy(connected = true) } }
            await { photos() == 2 }
            assertTrue(vm.attachmentPreparation.value.isEmpty())
            receipts += "The camera URI and original conversation key survive saved-state recreation; the result uploads when the restored account and conversation still match, also once a client that was still connecting connects."
        }
    }

    @Test fun attachmentCheckMeasuresTheRealAndroidSendReceipt() {
        // Android org.json writes '/' as '\/', and the receipt nests the draft as a JSON string, so base64 grows past the 4/3 estimate.
        val scope = Scope("https://ui-fixture.invalid", "synthetic-owner", "default", "attachment-budget")
        val random = java.util.Random(7)
        fun send(fileBytes: Int) = PendingSend.create(scope, dev.hermes.android.core.Draft("A short message", attachments = listOf(json("id" to "id", "name" to "photo.jpg", "mime" to "image/jpeg",
            "staged_base64" to android.util.Base64.encodeToString(ByteArray(fileBytes).also(random::nextBytes), android.util.Base64.NO_WRAP)))))
        try {
            val fits = send(11 * 1024 * 1024)
            assertTrue(attachmentFitsLocalCheckpoint(fits))
            runBlocking { app.cache.write(scope, "pending", fits.encode(), true) }
            // 11.7 MiB passed the old estimate but cannot be saved as a receipt.
            val tooLarge = send(11 * 1024 * 1024 + 700 * 1024)
            assertFalse(attachmentFitsLocalCheckpoint(tooLarge))
            assertTrue(runCatching { runBlocking { app.cache.write(scope, "pending", tooLarge.encode(), true) } }.isFailure)
            receipts += "The attachment limit measures the real encoded send receipt: what it admits can be saved, what it refuses cannot."
        } finally { runBlocking { app.cache.delete(scope, "pending") } }
    }

    @Test fun aReopenedFilesPageShowsTheSavedListingAtOnceThenTheFreshOne() {
        val restoration = StateRestorationTester(compose)
        withFixture(restoration = restoration) { vm, fixture ->
            compose.runOnIdle { vm.navigate("Files") }
            await { vm.data.value["files"] != null && "files" !in vm.refreshing.value }
            compose.onNodeWithText("notes.md").assertExists()
            runBlocking { vm.screens.flush(fixture.scope) }
            assertNotNull("the listing is kept on the phone", runBlocking { app.cache.read(fixture.scope.copy(profile = "", session = ""), "screens") })
            compose.runOnIdle { vm.tab("Chat") }
            // A restart keeps only what is saved: nothing in memory, a fresh cache object reading the stored row, a new composition.
            compose.runOnIdle {
                field(vm, "screens", dev.hermes.android.data.ScreenCache(read = { app.cache.read(it, "screens") }, write = { scope, value -> app.cache.write(scope, "screens", value) },
                    drop = { app.cache.delete(it, "screens") }, jobs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())))
                flow<Map<String, JSONObject>>(vm, "_data").value = vm.data.value - "files"
                (BeaconViewModel::class.java.getDeclaredField("loadedAt").apply { isAccessible = true }.get(vm) as MutableMap<*, *>).clear()
                BeaconViewModel::class.java.getDeclaredMethod("hydrateScreens", fixture.scope.javaClass).apply { isAccessible = true }.invoke(vm, fixture.scope)
            }
            await { vm.data.value["files"] != null }
            fixture.workspaceFiles["fresh.txt"] = "new on the server"
            fixture.holdNextFileList.set(true)
            try {
                compose.runOnIdle { vm.navigate("Files") }
                restoration.emulateSavedInstanceStateRestore()
                assertTrue("The reopened page asks for a fresh listing",
                    fixture.fileListCaptured.await(5, java.util.concurrent.TimeUnit.SECONDS))
                // The fresh listing is still on its way: the saved one is already on screen, with no loading bar.
                compose.onNodeWithText("notes.md").assertExists()
                compose.onAllNodes(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate) and hasAnyAncestor(hasTestTag("files-content"))).assertCountEquals(0)
                compose.onNodeWithText("fresh.txt").assertDoesNotExist()
            } finally { fixture.releaseFileList.countDown() }
            await { vm.data.value["files"]?.rows("entries")?.any { it.string("name") == "fresh.txt" } == true }
            compose.onNodeWithText("fresh.txt").assertExists()
            receipts += "A reopened Files page renders its saved listing at once, then replaces it with the fresh one."
            capture("precache-files-reopen")
        }
    }

    @Test fun workspaceMultiPickerRefreshesAfterAnOlderListingFinishes() {
        val picker = PickerRegistry(app)
        withFixture(picker) { vm, fixture ->
            val directory = File(app.cacheDir, "exports").apply { mkdirs() }
            val files = listOf("workspace-one.txt", "workspace-two.txt").map { File(directory, it).apply { writeText("Synthetic multi-file upload") } }
            try {
                compose.runOnIdle { vm.navigate("Files") }
                await { vm.data.value["files"] != null }
                fixture.holdNextFileList.set(true)
                compose.runOnIdle { vm.load("files", "/api/pc/files", mapOf("path" to "")) }
                assertTrue(fixture.fileListCaptured.await(5, java.util.concurrent.TimeUnit.SECONDS))
                compose.onNodeWithContentDescription("Upload to this folder").performClick()
                assertTrue(picker.intent!!.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
                compose.runOnUiThread { picker.deliver(files.map { androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", it) }) }
                await { fixture.workspaceFiles.size == 2 }
                val uploads = fixture.requests.filter { it.string("path") == "/api/files/upload-stream" }
                assertEquals(2, uploads.size)
                assertTrue(uploads.all { it.string("method") == "POST" && it.string("profile") == fixture.scope.profile })
                files.forEach { assertEquals(it.readText(), String(android.util.Base64.decode(fixture.workspaceFiles.getValue(it.name), android.util.Base64.NO_WRAP), Charsets.UTF_8)) }
                fixture.releaseFileList.countDown()
                await { vm.data.value["files"]?.rows("entries")?.map { it.string("name") }?.containsAll(files.map { it.name }) == true }
                files.forEach { compose.onNodeWithText(it.name).assertExists() }
                receipts += "Workspace multi-selection refreshes after both writes, even when an older listing is in flight."
                capture("workspace-multiple-files")
            } finally { fixture.releaseFileList.countDown(); files.forEach { it.delete() } }
        }
    }

    @Test fun chatApprovalsOnlyOfferAskFirstOrConfirmedYolo() = withFixture { vm, fixture ->
        fun open() {
            await { compose.onAllNodes(hasTestTag("composer-approvals") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("composer-approvals").performClick()
            await { vm.data.value["picker:approvals"]?.string("session_id") == vm.chat.value.sessionId && "picker:approvals" !in vm.refreshing.value }
        }
        open()
        compose.onNodeWithTag("approval-mode-off").assertDoesNotExist()
        compose.onNodeWithTag("approval-mode-smart").assertDoesNotExist()
        compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        compose.onNodeWithText("Skip approvals for this chat?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(fixture.requests.none { it.string("method") == "config.set" })
        open(); compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        compose.onNode(hasText("Skip approvals") and hasAnyAncestor(isDialog())).performClick()
        await { vm.data.value["picker:approvals"]?.optBoolean("session_bypass") == true }
        open(); compose.onNodeWithTag("approval-ask").performScrollTo().performClick()
        await { vm.data.value["picker:approvals"]?.let { it.optBoolean("session_status_known") && !it.optBoolean("session_bypass") } == true }
        val writes = fixture.requests.filter { it.string("method") == "config.set" }.map { it.getJSONObject("params") }
        assertEquals(listOf("1", "0"), writes.map { it.string("value") })
        assertTrue(writes.all { it.string("key") == "yolo" && it.string("scope") == "session" })
        assertEquals("manual", fixture.approvalMode)
        open(); compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        compose.runOnIdle { vm.selectSession("chat-b") }
        await { vm.chat.value.sessionId == "chat-b" }
        compose.onNodeWithText("Skip approvals for this chat?").assertDoesNotExist()
        assertEquals(2, fixture.requests.count { it.string("method") == "config.set" })
        receipts += "Chat approvals only change session YOLO; enabling confirms, Ask first restores the profile policy, and switching chat cancels stale confirmation."
    }

    @Test fun synchronouslyRejectedReadAlwaysSettlesItsLoadingState() = withFixture { vm, _ ->
        compose.runOnIdle { vm.load("rejected-read", "/api/approval/mode", mapOf("profile" to "other")) }
        await { recordError(vm.data.value["rejected-read"]).isNotBlank() }
        assertFalse("A rejected read must not leave an endless loading indicator", "rejected-read" in vm.refreshing.value)
    }

    @Test fun scopedMetadataCacheReusesReadsAndInvalidatesAfterMutation() = withFixture { vm, fixture ->
        val path = "/api/tools/toolsets"
        runBlocking { vm.load("native-tools", path).join() }
        val before = fixture.requests.count { it.string("path") == path }
        val seen = java.util.concurrent.CopyOnWriteArrayList<Set<String>>()
        val observer = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { vm.refreshing.collect { seen += it } }
        try {
            runBlocking {
                vm.load("tool-alias", path, force = false).join()
                vm.load("native-tools", path, mapOf("profile" to "default"), force = false).join()
            }
            assertEquals(before, fixture.requests.count { it.string("path") == path })
            assertFalse(seen.any { "tool-alias" in it || "native-tools" in it })
            compose.runOnIdle { vm.storeData("tool-alias", vm.data.value.getValue("tool-alias").copy().put("_request_key", "different-resource").put("wrong_resource", true)) }
            runBlocking { vm.load("tool-alias", path, force = false).join() }
            assertFalse(vm.data.value.getValue("tool-alias").has("wrong_resource"))
            assertEquals(before, fixture.requests.count { it.string("path") == path })
            runBlocking {
                vm.action("/api/plugins/kanban/tasks", json("title" to "Synthetic cache invalidation"), refresh = false, successNotice = "").join()
                vm.load("native-tools", path, force = false).join()
            }
            assertEquals(before + 1, fixture.requests.count { it.string("path") == path })
            assertTrue(vm.refreshing.value.isEmpty())
            receipts += "Profile-scoped metadata is reused across UI aliases without loading pulses; an actual fixture mutation invalidates it and triggers a new read."
        } finally { observer.cancel() }
    }

    @Test fun backgroundCatalogFailureRetainsSuggestionsWithoutGlobalErrorOrAccidentalSend() = withFixture { vm, fixture ->
        runBlocking {
            vm.loadSlashCatalog(force = true).join()
            vm.action("/api/plugins/kanban/tasks", json("title" to "Synthetic catalog invalidation"), refresh = false, successNotice = "").join()
        }
        fixture.commandCatalogFailure = true
        runBlocking { vm.loadSlashCatalog().join() }
        val record = vm.data.value.getValue("slash:catalog")
        assertTrue(record.has("available"));assertFalse(record.optBoolean("available"))
        assertTrue(record.rows("commands").isNotEmpty())
        assertTrue(recordError(record).isNotBlank())
        assertTrue(vm.ui.value.error.isBlank())
        compose.runOnIdle { vm.composer(TextFieldValue("/help")) }
        compose.onNodeWithTag("message-send").performClick()
        assertFalse(fixture.requests.any { it.string("method") in setOf("command.dispatch", "prompt.submit") })
        assertEquals("/help", vm.composer.value.text)
        fixture.commandCatalogFailure = false
        compose.onNodeWithText("Retry commands").performScrollTo().performClick()
        await { recordError(vm.data.value["slash:catalog"]).isBlank() && vm.data.value["slash:catalog"]?.optBoolean("available") == true }
        receipts += "Background command metadata failures retain scoped suggestions quietly and never turn an unavailable slash command into an ordinary prompt; explicit retry recovers."
    }

    @Test fun slashNamePrefixesPrecedeDescriptionsBeforeTheResultLimit() = withFixture { vm, fixture ->
        fixture.commandDistractors = 30
        runBlocking { vm.loadSlashCatalog(force = true).join() }
        compose.runOnIdle { vm.composer(TextFieldValue("/hel")) }
        compose.onNodeWithText("/help", substring = false).assertIsDisplayed()
        val help = compose.onNodeWithText("/help", substring = false).getUnclippedBoundsInRoot()
        val input = compose.onNodeWithTag("message-input").getUnclippedBoundsInRoot()
        assertTrue(help.bottom <= input.top)
        compose.onNodeWithText("/help", substring = false).performClick()
        assertEquals("/help ", vm.composer.value.text)
        assertFalse(fixture.requests.any { it.string("method") in setOf("command.dispatch", "prompt.submit") })
        receipts += "A real command-name prefix outranks more than twenty description matches before truncation; selection only prefills."
    }

    @Test fun botPollingKeepsRosterPositionAndStopsWhenBackgrounded() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.toggleBotMode() }
        await { vm.ui.value.botModeEnabled && !vm.ui.value.botModeSwitching && vm.botMode.state.value.bots.isNotEmpty() }
        compose.runOnIdle { vm.openBotSpace("fixture-room") }
        await { vm.botMode.state.value.selectedSpace == "fixture-room" && !vm.botMode.state.value.loading }
        compose.runOnIdle { vm.toggleSessions(true) }
        compose.onNodeWithTag("bot-mode-sidebar").performScrollToNode(hasTestTag("bot-row-default"))
        val before = compose.onNodeWithTag("bot-row-default").getUnclippedBoundsInRoot()
        fixture.holdNextRoomRead.set(true)
        try {
            compose.runOnIdle { vm.botMode.refresh(silent = true) }
            assertTrue(fixture.roomReadCaptured.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(vm.botMode.state.value.loading)
            assertEquals(before, compose.onNodeWithTag("bot-row-default").getUnclippedBoundsInRoot())
            capture("background-refresh-stable")
        } finally { fixture.releaseRoomRead.countDown() }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        val calls = fixture.requests.count { it.string("path").endsWith("/space/fixture-room") }
        Thread.sleep(3400)
        assertEquals(calls, fixture.requests.count { it.string("path").endsWith("/space/fixture-room") })
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        await { fixture.requests.count { it.string("path").endsWith("/space/fixture-room") } > calls }
        receipts += "A held background room refresh preserves roster coordinates; polling pauses with Activity stop and resumes on return."
    }

    @Test fun botModeToggleRestoresProfileChatDraftAndHistory() = withFixture { vm, fixture ->
        showProfileDisplayNames(vm, fixture)
        fun toggle() {
            compose.onNodeWithTag(if (vm.ui.value.botModeEnabled) "bot-mode-sidebar" else "navigation-menu").performScrollToNode(hasTestTag("navigation-bots"))
            compose.onNodeWithTag("navigation-bots").performClick()
        }
        fun coder() {
            compose.onNodeWithTag("bot-mode-sidebar").performScrollToNode(hasTestTag("bot-row-coder"))
            compose.onNodeWithTag("bot-row-coder").performClick()
        }
        compose.runOnIdle { vm.composer(TextFieldValue("Normal chat draft")); vm.toggleSessions(true) }
        compose.onNodeWithTag("history-search-toggle").performClick()
        compose.onNodeWithText("Search conversations").performTextInput("Travel")
        await { vm.ui.value.query == "Travel" }
        val normalTiles = navigationTiles.associateWith { compose.onNodeWithTag("navigation-${it.lowercase()}").getUnclippedBoundsInRoot() }
        toggle()
        await { vm.ui.value.botModeEnabled && !vm.ui.value.botModeSwitching && vm.botMode.state.value.bots.size == 2 }
        val botTiles = navigationTiles.associateWith { compose.onNodeWithTag("navigation-${it.lowercase()}").getUnclippedBoundsInRoot() }
        for (name in navigationTiles) {
            assertEquals("Menu tile width changed for $name", (normalTiles.getValue(name).right - normalTiles.getValue(name).left).value, (botTiles.getValue(name).right - botTiles.getValue(name).left).value, 0.5f)
            assertEquals("Menu tile alignment changed for $name", normalTiles.getValue(name).left.value, botTiles.getValue(name).left.value, 0.5f)
            assertEquals("Menu row changed for $name", (normalTiles.getValue(name).top - normalTiles.getValue("Skills").top).value, (botTiles.getValue(name).top - botTiles.getValue("Skills").top).value, 0.5f)
        }
        receipts += "Normal and Bot Mode tile widths, left alignment and relative rows match."
        compose.onNodeWithTag("navigation-bots").assertIsOn()
        // The light is the visible state; the row's state description still tells TalkBack, and no "On" text stretches the tile.
        compose.onNodeWithText("On").assertDoesNotExist()
        compose.onNodeWithTag("navigation-bots").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "On"))
        compose.onNodeWithTag("navigation-leave-bot-mode").assertTextContains("Leave Bot Mode")
        compose.onNodeWithTag("bot-mode-sidebar").performScrollToNode(hasTestTag("bot-row-coder"))
        compose.onNodeWithTag("bot-row-coder").assertTextContains("Code profile")
        compose.onNodeWithText("Search conversations").assertDoesNotExist()
        capture("bot-mode-toggle-on")
        coder()
        await { vm.ui.value.profile == "coder" && vm.botMode.state.value.activeBot?.profile == "coder" }
        assertEquals("bot-coder", vm.chat.value.sessionId)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        androidx.test.espresso.Espresso.pressBack()
        await { vm.botMode.state.value.activeBot == null }
        assertTrue(vm.ui.value.botModeEnabled)
        compose.runOnIdle { vm.toggleSessions(true) }; coder()
        await { vm.botMode.state.value.activeBot?.profile == "coder" }
        compose.runOnIdle { vm.composer(TextFieldValue("Coder bot draft")); vm.toggleSessions(true) }
        compose.onNodeWithTag("bot-mode-sidebar").performScrollToNode(hasTestTag("navigation-leave-bot-mode"))
        compose.onNodeWithTag("navigation-leave-bot-mode").performClick()
        await { !vm.ui.value.botModeEnabled && !vm.ui.value.botModeSwitching }
        assertEquals("default", vm.ui.value.profile)
        assertEquals("chat-a", vm.chat.value.sessionId)
        assertEquals("Normal chat draft", vm.composer.value.text)
        assertEquals("Travel", vm.ui.value.query)
        compose.onNodeWithTag("navigation-bots").assertIsOff()
        compose.onNodeWithTag("navigation-menu").performScrollToNode(hasText("Travel"))
        // The chat header now names Travel as chat-a's project too, so look only inside the menu.
        compose.onNode(hasText("Travel", substring = false) and hasAnyAncestor(hasTestTag("navigation-menu"))).assertExists()
        assertEquals(0, fixture.requests.count { it.string("method") == "session.create" })
        capture("bot-mode-toggle-restored")
        toggle()
        await { vm.ui.value.botModeEnabled && !vm.ui.value.botModeSwitching }
        coder()
        await { vm.botMode.state.value.activeBot?.profile == "coder" }
        assertEquals("Coder bot draft", vm.composer.value.text)
        compose.runOnIdle { vm.openBotSpace("fixture-room") }
        await { vm.botMode.state.value.selectedSpace == "fixture-room" && !vm.botMode.state.value.loading }
        compose.onNodeWithTag("bot-shared-composer").performTextInput("Shared room draft")
        capture("bot-mode-shared-space")
        compose.runOnIdle { vm.toggleBotMode() }
        await { !vm.ui.value.botModeEnabled && !vm.ui.value.botModeSwitching }
        assertEquals("Normal chat draft", vm.composer.value.text)
        assertEquals(0, fixture.requests.count { it.string("path").endsWith("/messages") && it.string("method") == "POST" })
        receipts += "Bot Mode visibly toggles, opens a different profile's permanent chat, preserves both unsent drafts, restores normal history search/profile/chat, and never auto-sends or creates a normal session."
    }

    @Test fun historyShowsLoadingInsteadOfEmptyWhileTheListIsFetched() = withFixture { vm, fixture ->
        // Live 2026-10-19: over a slow link the drawer said "No conversations here" for seconds before the list arrived.
        compose.runOnIdle { flow<AppState>(vm, "_ui").value = vm.ui.value.copy(sessions = emptyList()) }
        fixture.holdNextSessionList.set(true)
        try {
            compose.runOnIdle { vm.refreshSessions(""); vm.toggleSessions(true) }
            assertTrue(fixture.sessionListCaptured.await(5, java.util.concurrent.TimeUnit.SECONDS))
            compose.onNodeWithTag("history-loading").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("No conversations here").assertDoesNotExist()
        } finally { fixture.releaseSessionList.countDown() }
        await { !vm.ui.value.historyLoading && vm.ui.value.sessions.isNotEmpty() }
        compose.onNodeWithTag("history-loading").assertDoesNotExist()
        receipts += "While the conversation list is being fetched the drawer shows a loading row, never a false empty state."
    }

    @Test fun initialConversationShellIsUsableBeforeTheDetailReadFinishes() = withFixture { vm, fixture ->
        compose.runOnUiThread {
            field(vm, "sourceScope", null)
            flow<ChatState>(vm, "_chat").value = ChatState()
            flow<AppState>(vm, "_ui").value = vm.ui.value.copy(tab = "Chat", initialChatReady = true, newChatOpening = false, sessions = emptyList())
        }
        compose.runOnIdle { vm.newChat() }
        await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
        val id = vm.chat.value.sessionId
        // The chat's project lookup reads the stored row once (ChatScreen); opening the shell adds no detail read of its own.
        await { vm.data.value["project-of:$id"]?.has("project") == true }
        assertEquals(1, fixture.requests.count { it.string("path") == "/api/sessions/$id" })
        compose.onNodeWithTag("message-input").performClick().performTextInput("Draft after the shell opens")
        await { vm.composer.value.text == "Draft after the shell opens" }
        receipts += "The acknowledged native shell opens the composer without waiting for a redundant detail read; Hermes-side new-session delay remains."
    }

    @Test fun historySearchShowsProgressAndFiltersStaleRowsImmediately() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.toggleSessions(true) }
        // The drawer reads its list after a short debounce; hold the search that follows, not that first read.
        await { fixture.requests.any { it.string("path") == "/api/sessions" } && vm.ui.value.sessions.isNotEmpty() && !vm.ui.value.historyLoading }
        fixture.holdNextSessionList.set(true)
        try {
            compose.onNodeWithTag("history-search-toggle").performClick()
            compose.onNodeWithTag("history-search").performTextReplacement("No matching conversation")
            // waitUntil advances the test clock past the search debounce; a blocking latch wait would not.
            compose.waitUntil(5_000) { fixture.sessionListCaptured.count == 0L }
            assertEquals("No matching conversation", vm.ui.value.query)
            compose.onNodeWithTag("history-searching").assertExists()
            // R6M F27 retains the loaded index but filters visible titles before the server responds.
            assertTrue(vm.ui.value.sessions.any { sessionRowId(it) == "chat-a" })
            compose.onNodeWithTag("history-row:chat-a").assertDoesNotExist()
            compose.onNodeWithTag("history-no-matches").assertTextEquals("No matching chats")
        } finally { fixture.releaseSessionList.countDown() }
        await { !vm.ui.value.historyLoading }
        compose.onNodeWithTag("history-searching").assertDoesNotExist()
        receipts += "A slow history search shows Searching, retains the loaded index and immediately hides nonmatching titles."
    }

    @Test fun historyAllProfilesShowsDisplayNamesAndSwitchesToTheSelectedProfile() = withFixture { vm, fixture ->
        showProfileDisplayNames(vm, fixture)
        fixture.profileSessionIds = mapOf("default" to setOf("chat-a", "chat-b"))
        fixture.addProfileConversation("coder", "coder-chat", "Coder notes")
        compose.runOnIdle { vm.toggleSessions(true) }
        await { vm.ui.value.sessions.any { sessionRowId(it) == "chat-a" } && !vm.ui.value.historyLoading }
        compose.onNodeWithTag("history-filter").performScrollTo().performClick()
        compose.onNodeWithTag("history-all-profiles").performClick()
        await { vm.data.value["navigation-profiles"]?.rows("profiles")?.any { it.string("name") == "coder" && it.string("display_name") == "Code profile" } == true && vm.ui.value.sessions.any { it.string("profile") == "coder" && sessionRowId(it) == "coder-chat" } && !vm.ui.value.historyLoading }
        compose.onNodeWithTag("history-row:coder:coder-chat").assertTextContains("Code profile", substring = true).performClick()
        await { vm.ui.value.profile == "coder" && vm.chat.value.sessionId == "coder-chat" && vm.chat.value.projectionReady }
        assertTrue(fixture.requests.any { it.string("path") == "/api/sessions" && it.string("profile") == "coder" })
        receipts += "All Profiles labels history rows with Hermes display names and opens a selected row after switching to its authorized profile."
    }

    @Test fun signOutForgetsChatPreviewsForEveryKnownProfile() = withFixture { vm, fixture ->
        val profiles = listOf("default", "coder").map { fixture.scope.copy(profile = it, session = "") }
        runBlocking { profiles.forEach { app.cache.write(it, "chat-glances", "{}", protected = true); app.cache.write(it, "conversation-index", json("sessions" to org.json.JSONArray()).toString()) } }
        compose.runOnIdle { vm.storeData("navigation-profiles", json("profiles" to org.json.JSONArray().put(json("name" to "default")).put(json("name" to "coder")))) }
        val otherAccount = fixture.scope.copy(owner = "someone-else", profile = "default", session = "")
        runBlocking { app.cache.write(otherAccount, "chat-glances", "{}", protected = true) }
        compose.runOnIdle { vm.forgetScreens() }
        await { runBlocking { profiles.all { app.cache.read(it, "chat-glances") == null && app.cache.read(it, "conversation-index") == null } } }
        assertNotNull("Another account's previews are never touched", runBlocking { app.cache.read(otherAccount, "chat-glances") })
        runBlocking { app.cache.delete(otherAccount, "chat-glances") }
        receipts += "Sign-out and device removal forget the chat list and its previews for every profile the app knows."
    }

    @Test fun signOutForgetsPreviewsOfAProfileOnlyTheAccountRowNames() = withFixture { vm, fixture ->
        val account = fixture.scope.copy(profile = "", session = "")
        val unlisted = fixture.scope.copy(profile = "scratch", session = "")
        runBlocking {
            app.cache.write(unlisted, "chat-glances", "{}", protected = true)
            app.cache.write(unlisted, "conversation-index", json("sessions" to org.json.JSONArray()).toString())
            app.cache.write(account, GLANCE_PROFILES_KIND, glanceProfilesJson(listOf("scratch")), protected = true)
        }
        // The profile list does not name it and this run never opened it: only the account row does.
        compose.runOnIdle { vm.storeData("navigation-profiles", json("profiles" to org.json.JSONArray().put(json("name" to "default")))) }
        compose.runOnIdle { vm.forgetScreens() }
        await { runBlocking { listOf("chat-glances", "conversation-index").all { app.cache.read(unlisted, it) == null } && app.cache.read(account, GLANCE_PROFILES_KIND) == null } }
        receipts += "Sign-out forgets the previews of a profile that only the account row names, then the account row."
    }

    /** What the view model has already put in the account row this run (private state, read to know the first save really recorded it). */
    @Suppress("UNCHECKED_CAST") private fun recordedGlanceProfiles(vm: BeaconViewModel) =
        (BeaconViewModel::class.java.getDeclaredField("glanceRecorded").apply { isAccessible = true }.get(vm) as Set<String>).toSet()

    @Test fun signingInAgainAfterSignOutRecordsTheProfileInTheAccountRowAgainSoTheNextSignOutForgetsItsPreviews() = withFixture { vm, fixture ->
        val account = fixture.scope.copy(profile = "", session = "")
        val chatList = fixture.scope.copy(session = "")
        fun stored(scope: Scope, kind: String) = runBlocking { app.cache.read(scope, kind) }
        fun named() = decodeGlanceProfiles(stored(account, GLANCE_PROFILES_KIND))
        val latest = requireNotNull(vm.chat.value.messages.lastOrNull { (it.role == "user" || it.role == "assistant") && it.text.isNotBlank() })
        // The first sign-in saves a preview; the account row names the profile and this run knows it did.
        compose.runOnIdle { vm.noteChatViewed("chat-a", latest) }
        await { chatList.key in compose.runOnUiThread { recordedGlanceProfiles(vm) } && stored(chatList, "chat-glances") != null && fixture.scope.profile in named() }
        // Sign-out forgets both. The fixture keeps its client, which is all the glance state sees of signing in again to the same account.
        compose.runOnIdle { vm.forgetScreens() }
        await { stored(chatList, "chat-glances") == null && stored(account, GLANCE_PROFILES_KIND) == null }
        // The preview saved after that must name the profile again, or the next sign-out could not find it by the account row.
        compose.runOnIdle { vm.noteChatViewed("chat-a", latest) }
        await { stored(chatList, "chat-glances") != null }
        assertTrue("The account row names the profile again", fixture.scope.profile in named())
        runBlocking { app.cache.write(chatList, "conversation-index", json("sessions" to org.json.JSONArray()).toString()) }
        compose.runOnIdle { vm.forgetScreens() }
        await { listOf("chat-glances", "conversation-index").all { stored(chatList, it) == null } && stored(account, GLANCE_PROFILES_KIND) == null }
        receipts += "Signing in again after a sign-out records the profile's previews in the account row again, and the next sign-out forgets them and the row."
    }

    @Test fun historyAllProfilesListsOtherProfilesWhenTheCurrentOneHasNoStore() = withFixture { vm, fixture ->
        // An earlier test may have cached the profile catalogue (fresh for two minutes) without these names.
        runBlocking { listOf("default", "coder").forEach { app.cache.delete(fixture.scope.copy(profile = it), "read:/api/profiles") } }
        // The chat list opens from cache first: an earlier test's cached coder list would stand in for the empty one.
        runBlocking { listOf("conversation-index", "chat-glances").forEach { app.cache.delete(fixture.scope.copy(profile = "coder", session = ""), it) } }
        fixture.profileDisplayNames = mapOf("default" to "Personal profile", "coder" to "Code profile")
        fixture.profileSessionIds = mapOf("default" to setOf("chat-a", "chat-b"))
        // Real Hermes: a profile whose gateway never ran has no state.db, and its list read answers this 503.
        val noStore = 503 to "Session store is not initialized. Start this profile's gateway to initialize it."
        fixture.sessionListFailures = mapOf("coder" to noStore)
        compose.runOnIdle { vm.switchProfile("coder") }
        await { vm.ui.value.profile == "coder" }
        compose.runOnIdle { vm.toggleSessions(true) }
        await { fixture.requests.any { it.string("path") == "/api/sessions" && it.string("profile") == "coder" } && !vm.ui.value.historyLoading }
        compose.onNodeWithText("No conversations here").assertExists()
        compose.onNodeWithTag("history-filter").performScrollTo().performClick()
        compose.onNodeWithTag("history-all-profiles").performClick()
        await { vm.ui.value.sessions.count { it.string("profile") == "default" } == 2 && !vm.ui.value.historyLoading }
        compose.onNodeWithTag("history-row:default:chat-a").assertExists()
        assertTrue(vm.ui.value.historyUnreadable.isEmpty())
        // A store Hermes cannot read is named instead of looking like an empty list.
        fixture.sessionListFailures = mapOf("coder" to noStore, "default" to (503 to "Session store is busy (disk I/O or lock). Retry; the list was not cleared."))
        compose.runOnIdle { vm.refreshSessions() }
        await { vm.ui.value.historyUnreadable == listOf("default") && !vm.ui.value.historyLoading && vm.data.value["navigation-profiles"]?.rows("profiles")?.any { it.string("display_name") == "Personal profile" } == true }
        compose.onNodeWithTag("history-unreadable:default").assertTextEquals("Couldn't load conversations: Personal profile")
        compose.onNodeWithText("No conversations here").assertDoesNotExist()
        receipts += "All Profiles lists another profile's chats when the current profile has no store yet, and names a profile whose chats could not be read."
    }

    @Test fun reasoningDisclosureUsesMarkdownWithOneVisibleToggle() = withFixture { vm, _ ->
        val reasoning = "**Why this follows**\n\n- First step\n- Second step"
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = vm.chat.value.messages + Message("reasoning-disclosure", "assistant", "Answer", reasoning = reasoning, sessionId = vm.chat.value.sessionId)) }
        await { compose.onAllNodesWithTag("disclosure:Reasoning").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("disclosure:Reasoning").performClick()
        compose.onNodeWithText("Details").assertDoesNotExist()
        compose.onNodeWithText("Why this follows").assertExists()
        compose.onNodeWithText("First step").assertExists()
        receipts += "Reasoning uses the native Markdown renderer and one chevron/title disclosure control."
    }

    @Test fun aThreadKeepsItsNameWhenAnotherConversationHoldsIt() = withFixture { vm, fixture ->
        val taken = fixture.sessions.getValue("chat-b").getJSONObject("info").string("title")
        fixture.dropCreateTitle = true
        // The Threads lane lists only threads, so the clash is with a conversation the phone cannot see.
        compose.runOnIdle { flow<AppState>(vm, "_ui").value = vm.ui.value.copy(sessions = emptyList()); vm.tab("Chat"); vm.newThread(taken) }
        await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady }
        val thread = vm.chat.value.sessionId
        // Fixture ids repeat across runs; a stale stream cursor would skip this run's first event.
        kotlinx.coroutines.runBlocking { app.cache.delete(fixture.scope.session(thread), "live:runtime-$thread") }
        val subscriptionsBefore = fixture.streamSubscriptions.get()
        compose.runOnIdle { fixture.persistTerminalMessages = true; vm.composer(TextFieldValue("First thread message")); vm.send() }
        await { fixture.requests.any { it.string("method") == "prompt.submit" } && vm.chat.value.streamId.isNotBlank() }
        await { fixture.streamSubscriptions.get() > subscriptionsBefore }
        fixture.nativeTurnStatus = "idle"
        fixture.emitNativeEvent("message.complete", json("text" to "Thread reply"))
        await { fixture.sessions.getValue(thread).getJSONObject("info").string("title") == "$taken 2" }
        await { vm.chat.value.title == "$taken 2" }
        receipts += "A thread whose name another conversation holds is renamed with the next free number instead of losing its name."
    }

    // Live 2026-10-27: a thread started from Beacon's chat was made in Hermes, but the screen stayed on the chat open before Beacon,
    // because that chat's return finished opening after the create began and the create took it for the owner moving elsewhere.
    private fun startFromBeaconsChat(vm: BeaconViewModel, fixture: NativeExperienceFixture, start: () -> Unit, expect: (ChatState) -> Boolean) {
        await { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
        compose.runOnIdle { vm.tab("Beacon") }
        await { vm.ui.value.tab == "Beacon" && vm.isBeaconConversation() && vm.chat.value.projectionReady }
        val beacon = vm.chat.value.sessionId // also a new-N id in the fixture, so the check below must rule it out
        try {
            val creates = fixture.requests.count { it.string("method") == "session.create" }
            compose.runOnIdle { vm.tab("Chat"); start() } // what the New thread dialog and the drawer's New chat do
            await { fixture.requests.count { it.string("method") == "session.create" } == creates + 1 }
            await { vm.ui.value.tab == "Chat" && vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady }
            Thread.sleep(500); compose.waitForIdle()
            assertTrue("The new chat stays open: ${vm.chat.value.sessionId} ${vm.chat.value.title}",
                vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.sessionId != beacon && expect(vm.chat.value))
        } finally { runBlocking { app.preferences.edit { it.remove(stringPreferencesKey("assistant_return")) } } }
    }

    @Test fun aThreadStartedFromBeaconsChatOpensTheThread() = withFixture { vm, fixture ->
        startFromBeaconsChat(vm, fixture, { vm.newThread("🔒 Security news") }) { it.title == "🔒 Security news" }
    }

    @Test fun aNewChatStartedFromBeaconsChatOpensTheNewChat() = withFixture { vm, fixture ->
        startFromBeaconsChat(vm, fixture, { vm.newChat() }) { it.messages.isEmpty() }
    }

    @Test fun aThreadStopsRenamingAfterAnUnrelatedHermesError() = withFixture { vm, fixture ->
        val taken = fixture.sessions.getValue("chat-b").getJSONObject("info").string("title")
        fixture.dropCreateTitle = true
        fixture.renameFailure = "Synthetic rename service failure"
        compose.runOnIdle { flow<AppState>(vm, "_ui").value = vm.ui.value.copy(sessions = emptyList()); vm.tab("Chat"); vm.newThread(taken) }
        await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady }
        val thread = vm.chat.value.sessionId
        kotlinx.coroutines.runBlocking { app.cache.delete(fixture.scope.session(thread), "live:runtime-$thread") }
        val subscriptionsBefore = fixture.streamSubscriptions.get()
        compose.runOnIdle { fixture.persistTerminalMessages = true; vm.composer(TextFieldValue("First thread message")); vm.send() }
        await { fixture.requests.any { it.string("method") == "prompt.submit" } && vm.chat.value.streamId.isNotBlank() }
        await { fixture.streamSubscriptions.get() > subscriptionsBefore }
        fixture.nativeTurnStatus = "idle"
        fixture.emitNativeEvent("message.complete", json("text" to "Thread reply"))

        await { fixture.requests.count { it.string("method") == "session.title" } == 1 && vm.chat.value.streamId.isBlank() }
        assertEquals(1, fixture.requests.count { it.string("method") == "session.title" })
        receipts += "A non-duplicate Hermes rename failure stops after one title request instead of cycling numbered titles."
    }

    @Test fun threadsLaneStartsANamedThreadAndDrawerFiltersRunInHermes() = withFixture { vm, fixture ->
        try {
            compose.runOnIdle { vm.toggleSessions(true) }
            compose.onNodeWithTag("history-threads").performScrollTo().performClick()
            compose.waitUntil(5_000) { fixture.sessionListQueries.any { it.string("sources") == "android_thread,phone" } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("No threads yet").fetchSemanticsNodes().isNotEmpty() }

            compose.onNodeWithTag("navigation-new-thread").performScrollTo().performClick()
            compose.onNodeWithTag("new-thread-name").performTextInput("Trip planning")
            compose.onNodeWithTag("new-thread-start").performClick()
            await { fixture.requests.any { it.string("method") == "session.create" && it.getJSONObject("params").string("source") == "android_thread" } }
            assertEquals("Trip planning", fixture.requests.last { it.string("method") == "session.create" }.getJSONObject("params").string("title"))
            await { vm.chat.value.sessionId.isNotBlank() && vm.chat.value.title == "Trip planning" }
            // With a thread listed, starting the next one stays in sight instead of only in the filter menu.
            compose.runOnIdle { vm.toggleSessions(true) }
            compose.onNodeWithTag("history-threads").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("threads-new-thread").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("threads-new-thread").performScrollTo().performClick()
            compose.onNodeWithTag("new-thread-start").assertExists()
            androidx.test.espresso.Espresso.pressBack()

            compose.runOnIdle { vm.setHistoryFilters(setOf("telegram"), "created") }
            await { vm.preferences.value.historyOrder == "created" && vm.preferences.value.historyHiddenSources == setOf("telegram") }
            compose.runOnIdle { vm.toggleSessions(true) }
            compose.onNodeWithTag("history-view-all").performScrollTo().performClick()
            compose.waitUntil(5_000) { fixture.sessionListQueries.any { it.string("exclude_sources") == "cron,telegram" && it.string("order") == "created" } }
            compose.onNodeWithTag("history-filter").performScrollTo().assertIsSelected()
        } finally {
            compose.runOnIdle { vm.setHistoryFilters(emptySet(), "recent") }
            await { vm.preferences.value.historyOrder == "recent" && vm.preferences.value.historyHiddenSources.isEmpty() }
        }
        receipts += "Threads and New thread sit in the compact history filter row; the lane asks Hermes for Beacon and Relay threads only, creates a named thread natively, and applies source/order filters through Hermes."
    }

    @Test fun unsentChatComposerShowsTheProfileModelAndEffort() = withFixture { vm, fixture ->
        fixture.profileModel = "profile-default-model"; fixture.reasoningEffort = "high"
        compose.runOnIdle {
            field(vm, "sourceScope", null)
            flow<ChatState>(vm, "_chat").value = ChatState()
            flow<AppState>(vm, "_ui").value = vm.ui.value.copy(tab = "Chat", initialChatReady = true)
            // chat-a already loaded the defaults on first composition; read the changed profile again.
            vm.load("profile-defaults", "/api/profile/defaults")
        }
        await { vm.data.value["profile-defaults"]?.string("model") == "profile-default-model" }
        // The chip merges its labels into one Text list.
        compose.onNodeWithTag("composer-model-effort").assert(hasText("profile-default-model")).assert(hasText("high"))
        receipts += "A chat with no messages shows the model and effort Hermes starts this profile's chats with, not placeholder labels."
    }

    @Test fun chatFollowingItsProfileShowsTheProfileEffort() = withFixture { vm, fixture ->
        // The defaults load on first composition, so the fixture's own profile effort is expected.
        assertTrue(vm.chat.value.messages.isNotEmpty() && vm.chat.value.raw.string("reasoning_effort").isBlank())
        await { vm.data.value["profile-defaults"]?.string("reasoning_effort") == fixture.reasoningEffort }
        compose.onNodeWithTag("composer-model-effort").assert(hasText("synthetic-model")).assert(hasText(fixture.reasoningEffort))
        receipts += "A chat whose effort Hermes reports blank (it follows the profile) shows the profile's effort."
    }

    @Test fun botModeLeavesToAnUnsentNewChat() = withFixture { vm, fixture ->
        // Live 2026-10-19: entered from an empty new chat, Bot Mode could never be left because that chat has no history to load yet.
        var shellId = ""
        try {
            compose.runOnIdle { vm.newChat() }
            await { vm.chat.value.sessionId.startsWith("new-") && !vm.ui.value.newChatOpening && vm.chat.value.projectionReady }
            shellId = vm.chat.value.sessionId
            // The fixture names the runtime it creates for a chat runtime-<id>; the REST projection carries no runtime id.
            val creatingRuntime = "runtime-" + shellId
            fixture.markUnpromptedShell(shellId)
            runBlocking { app.cache.delete(fixture.scope.session(shellId), "session") }
            compose.runOnIdle { vm.composer(TextFieldValue("Unsent first message")) }
            compose.runOnIdle { vm.toggleBotMode() }
            await { vm.ui.value.botModeEnabled && !vm.ui.value.botModeSwitching }
            compose.runOnIdle { vm.toggleBotMode() }
            await { !vm.ui.value.botModeEnabled && !vm.ui.value.botModeSwitching }
            assertEquals(shellId, vm.chat.value.sessionId)
            assertEquals("Chat", vm.ui.value.tab)
            assertEquals("Unsent first message", vm.composer.value.text)
            assertEquals(0, fixture.requests.count { it.string("method") == "prompt.submit" })
            compose.runOnIdle { vm.send() }
            try { await { fixture.requests.count { it.string("method") == "prompt.submit" } == 1 } }
            catch (error: Throwable) { throw AssertionError("Send blocked: notice=${vm.ui.value.notice} error=${vm.ui.value.error} status=${vm.chat.value.status} raw=${vm.chat.value.raw.length()} ready=${vm.chat.value.projectionReady} stream=${vm.chat.value.streamId} id=${vm.chat.value.sessionId} requests=${recentCalls(fixture)}", error) }
            val submit = fixture.requests.single { it.string("method") == "prompt.submit" }.getJSONObject("params")
            assertEquals("Unsent first message", submit.string("text"))
            assertEquals("The first prompt stays with the runtime that created this chat", creatingRuntime, submit.string("session_id"))
            assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
            receipts += "Leaving Bot Mode returns to its unsent new chat, preserves the draft, and sends exactly one first prompt to the creating runtime."
        } finally {
            if (shellId.isNotBlank()) runBlocking { listOf("client-created-shell", "session", "draft").forEach { app.cache.delete(fixture.scope.session(shellId), it) } }
        }
    }

    @Test fun missingUnsentShellFallsBackToFreshSendableChatWithDraft() = withFixture { vm, fixture ->
        var shellId = ""
        var freshId = ""
        runBlocking { listOf("selected-session", "new-chat-shell", "new-chat-attempt", "initial-draft").forEach { app.cache.delete(fixture.scope, it) } }
        try {
            compose.runOnIdle { vm.newChat() }
            await { vm.chat.value.sessionId.startsWith("new-") && !vm.ui.value.newChatOpening && vm.chat.value.projectionReady }
            shellId = vm.chat.value.sessionId
            fixture.markUnpromptedShell(shellId)
            fixture.forgetUnpromptedShell(shellId)
            runBlocking { app.cache.delete(fixture.scope.session(shellId), "session") }
            compose.runOnIdle { vm.composer(TextFieldValue("Send after the shell expired")); vm.selectSession(shellId) }

            try { await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.sessionId != shellId && !vm.ui.value.newChatOpening && vm.chat.value.projectionReady } }
            catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                // This case only times out under full-suite load; keep the state that shows where the fallback stopped.
                val chat = vm.chat.value; val ui = vm.ui.value
                throw AssertionError("No fresh chat: shell=${chat.sessionId == shellId} session=${chat.sessionId.take(12)} ready=${chat.projectionReady} " +
                    "status=${chat.status} opening=${ui.newChatOpening} newChatError=${ui.newChatError} error=${ui.error} notice=${ui.notice} " +
                    "resumeSent=${fixture.requests.any { it.string("method") == "session.resume" && it.getJSONObject("params").string("session_id") == shellId }} " +
                    "requests=${fixture.requests.takeLast(10).map { it.string("method") + " " + it.string("path") }}", timeout)
            }
            freshId = vm.chat.value.sessionId
            // The fixture names the runtime it creates for a chat runtime-<id>; the REST projection carries no runtime id.
            val freshRuntime = "runtime-" + freshId
            assertEquals("Send after the shell expired", vm.composer.value.text)
            assertTrue("The expired shell was resumed", fixture.requests.any {
                it.string("method") == "session.resume" && it.getJSONObject("params").string("session_id") == shellId
            })
            assertEquals(0, fixture.requests.count { it.string("method") == "prompt.submit" })

            compose.runOnIdle { vm.send() }
            await { fixture.requests.count { it.string("method") == "prompt.submit" } == 1 }
            val submit = fixture.requests.single { it.string("method") == "prompt.submit" }.getJSONObject("params")
            assertEquals("Send after the shell expired", submit.string("text"))
            assertEquals(freshRuntime, submit.string("session_id"))
            assertEquals(1, fixture.requests.count { it.string("method") == "prompt.submit" })
            receipts += "When Hermes has forgotten an unsent shell, Beacon preserves its draft in a fresh sendable chat and submits once to the new creating runtime."
        } finally {
            runBlocking {
                listOf(shellId, freshId).filter(String::isNotBlank).forEach { id ->
                    listOf("client-created-shell", "session", "draft", "pending").forEach { app.cache.delete(fixture.scope.session(id), it) }
                }
                listOf("selected-session", "new-chat-shell", "new-chat-attempt", "initial-draft").forEach { app.cache.delete(fixture.scope, it) }
            }
        }
    }

    @Test fun theAssistantTabUsesItsOwnProfileAndGivesTheChatsProfileBack() = withFixture { vm, fixture ->
        val coder = fixture.scope.copy(profile = "coder")
        runBlocking { app.preferences.edit { it[stringPreferencesKey("assistant_profile")] = "coder" } }
        try {
            await { vm.preferences.value.assistantProfile == "coder" && vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
            compose.runOnIdle { vm.tab("Beacon") }
            await { vm.ui.value.tab == "Beacon" && vm.ui.value.profile == "coder" && !vm.ui.value.beaconOpening && vm.chat.value.projectionReady && vm.chat.value.sessionId.startsWith("new-") }
            assertTrue("The assistant's conversation is created in its profile", fixture.requests.any { it.string("method") == "session.create" && it.optJSONObject("params")?.string("profile") == "coder" })
            assertEquals("The drawer keeps the chats' profile", "default", vm.ui.value.chatProfile)
            assertTrue("The borrowed profile's assistant conversation is usable", vm.isBeaconConversation())
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("composer-attach").fetchSemanticsNodes().isNotEmpty() }

            compose.runOnIdle { vm.tab("Chat") }
            await { vm.ui.value.profile == "default" && vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
            assertEquals("", vm.ui.value.chatProfile)
            receipts += "The assistant tab always opens in its own profile; returning to Chat restores the chats' profile and the chat that was open."
        } finally {
            runBlocking {
                app.preferences.edit { it.remove(stringPreferencesKey("assistant_profile")); it.remove(stringPreferencesKey("assistant_return")) }
                listOf("beacon-session", "beacon-opening", "new-chat-shell", "selected-session").forEach { app.cache.delete(coder, it) }
            }
        }
    }

    @Test fun theAssistantTabReadsItsProfileFromSettingsNotTheLoadingFlow() = withFixture { vm, fixture ->
        val coder = fixture.scope.copy(profile = "coder")
        val switchesBefore = fixture.requests.count { it.string("path") == "/api/auth/me" }
        try {
            // Same main-thread step: the preferences flow cannot catch up between the write and the tap.
            compose.runOnUiThread { runBlocking { app.preferences.edit { it[stringPreferencesKey("assistant_profile")] = "coder" } }; vm.tab("Beacon") }
            await { vm.ui.value.tab == "Beacon" && vm.ui.value.profile == "coder" && !vm.ui.value.beaconOpening && vm.chat.value.projectionReady && vm.chat.value.sessionId.isNotBlank() }
            assertEquals("One switch to the assistant's profile", switchesBefore + 1, fixture.requests.count { it.string("path") == "/api/auth/me" })
            receipts += "The assistant tab opens in the profile just chosen in Settings, through one switch, without waiting for the settings to reload."
        } finally {
            runBlocking {
                app.preferences.edit { it.remove(stringPreferencesKey("assistant_profile")); it.remove(stringPreferencesKey("assistant_return")) }
                listOf("beacon-session", "beacon-opening", "new-chat-shell", "selected-session").forEach { app.cache.delete(coder, it) }
            }
        }
    }

    @Test fun aColdStartKeepsTheSavedAssistantReturn() {
        val key = stringPreferencesKey("assistant_return")
        val saved = json("profile" to "default", "session" to "chat-b").toString()
        runBlocking { app.preferences.edit { it[key] = saved } }
        try {
            withFixture { vm, _ ->
                await { BeaconViewModel::class.java.getDeclaredField("assistantReturnInitialized").apply { isAccessible = true }.getBoolean(vm) }
                compose.runOnIdle { vm.tab("Beacon") }
                await { vm.ui.value.tab == "Beacon" && vm.isBeaconConversation() && vm.chat.value.projectionReady }
                compose.runOnIdle { vm.tab("Chat") }
                await { vm.ui.value.tab == "Chat" && vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
                receipts += "A return record saved before a restart is loaded with the settings, so opening the assistant tab early cannot replace it."
            }
        } finally { runBlocking { app.preferences.edit { it.remove(key) } } }
    }

    @Test fun openingANonAssistantCallFromBeaconReturnsToItsProfileAndChatOnce() = withFixture { vm, fixture ->
        val assistantScope = fixture.scope.copy(profile = "coder")
        val callId = "synthetic-return-call"
        runBlocking { app.preferences.edit { it[stringPreferencesKey("assistant_profile")] = "coder" } }
        try {
            await { vm.preferences.value.assistantProfile == "coder" }
            compose.runOnIdle { vm.tab("Beacon") }
            await { vm.ui.value.tab == "Beacon" && vm.ui.value.profile == "coder" && !vm.ui.value.beaconOpening && vm.chat.value.sessionId.isNotBlank() }
            // Switching is client-side (bindProfile); each switch re-verifies the account with exactly one /api/auth/me.
            val switchesBefore = fixture.requests.count { it.string("path") == "/api/auth/me" }
            val target = fixture.scope.session("chat-b")
            app.calls.update { CallState(active = true, state = "listening", callId = callId, conversationScope = target) }
            lateinit var opening: kotlinx.coroutines.Job
            compose.runOnIdle { opening = vm.openCurrentCall(callId) }
            runBlocking { kotlinx.coroutines.withTimeout(15_000) { opening.join() } }
            await { vm.ui.value.tab == "Chat" && vm.ui.value.profile == target.profile && vm.chat.value.sessionId == target.session && vm.chat.value.projectionReady }
            assertEquals(switchesBefore + 1, fixture.requests.count { it.string("path") == "/api/auth/me" })
            assertTrue(app.calls.state.value.active)
            receipts += "Opening a call from Beacon returns directly to the call's profile and conversation through one profile switch."
        } finally {
            app.calls.update { CallState() }
            runBlocking {
                app.preferences.edit { it.remove(stringPreferencesKey("assistant_profile")); it.remove(stringPreferencesKey("assistant_return")) }
                listOf("beacon-session", "beacon-opening", "selected-session", "new-chat-shell").forEach { app.cache.delete(assistantScope, it) }
            }
        }
    }

    @Test fun openingACallForBeaconsConversationStaysOnBeacon() = withFixture { vm, _ ->
        val callId = "synthetic-beacon-call"
        compose.runOnIdle { vm.tab("Beacon") }
        await { vm.ui.value.tab == "Beacon" && vm.isBeaconConversation() && vm.chat.value.projectionReady }
        val target = (vm.client?.scope ?: error("Beacon client missing")).session(vm.chat.value.sessionId)
        app.calls.update { CallState(active = true, state = "listening", callId = callId, conversationScope = target) }
        try {
            compose.runOnIdle { vm.openCurrentCall(callId) }
            await { vm.ui.value.tab == "Beacon" && vm.chat.value.sessionId == target.session && vm.chat.value.projectionReady }
            assertTrue(app.calls.state.value.active)
            receipts += "Opening a call for Beacon's own conversation keeps the assistant tab selected."
        } finally {
            app.calls.update { CallState() }
            runBlocking { app.preferences.edit { it.remove(stringPreferencesKey("assistant_return")) } }
        }
    }

    @Test fun profileSwitchRefreshesAndRotatesTheStoredBearer() = withFixture { vm, fixture ->
        val now = System.currentTimeMillis() / 1000
        val refreshToken = "lane-r1-refresh"
        // Issued before the refresh, like a real stored sign-in: AuthStore keeps the incoming token when issue seconds tie.
        val bearer = dev.hermes.android.data.NativeBearerRecord("lane-r1-access", refreshToken, now + 3600, fixture.scope.origin, fixture.scope.owner, "default", "fixture", now - 60)
        val auth = dev.hermes.android.data.AuthContext(fixture.scope.origin, emptyList(), fixture.scope.owner, "default", backend = "hermes-dashboard", bearer = bearer)
        fixture.currentRefreshToken = refreshToken
        runBlocking { app.auth.persist(auth) }
        compose.runOnUiThread {
            field(vm, "client", fixture.clientFor(auth, app.auth))
        }
        try {
            runBlocking { vm.switchProfile("coder").join() }
            await { vm.ui.value.profile == "coder" && vm.client?.scope?.profile == "coder" }
            fixture.forceExpiredAccessToken = bearer.accessToken
            runBlocking { vm.refreshSessions(force = true).join() }
            await { fixture.refreshTokensUsed.isNotEmpty() && app.auth.read(auth.origin).bearer?.refreshToken == fixture.currentRefreshToken }
            assertEquals(listOf(refreshToken), fixture.refreshTokensUsed.toList())
            assertEquals(fixture.currentRefreshToken, vm.client?.auth?.bearer?.refreshToken)
            assertTrue(vm.ui.value.authenticated)

            val stale = fixture.freshClientFor(auth.copy(profile = "coder", bearer = bearer.copy(profile = "coder")))
            try {
                val failure = runBlocking { runCatching { stale.get("/api/profiles") }.exceptionOrNull() }
                assertTrue(failure is dev.hermes.android.data.NativeAuthExpiredException)
                assertTrue(fixture.rejectedRefreshTokens.contains(refreshToken))
            } finally { stale.closeNative() }
            receipts += "After a profile switch an expired access token refreshes through the active profile client; the store keeps the rotated token, and replaying its predecessor is rejected."
        } finally { app.calls.update { CallState() } }
    }

    @Test fun pushForAnotherProfilePersistsSubsequentBearerRefreshes() = withFixture { vm, fixture ->
        val source = "push-refresh-source"
        val event = "push-refresh-event"
        val scope = fixture.scope.copy(profile = "coder")
        val now = System.currentTimeMillis() / 1000
        val refreshToken = "push-refresh-token"
        val bearer = dev.hermes.android.data.NativeBearerRecord("push-access", refreshToken, now + 3600, fixture.scope.origin, fixture.scope.owner, "default", "fixture", now - 60)
        val auth = dev.hermes.android.data.AuthContext(fixture.scope.origin, emptyList(), fixture.scope.owner, "default", backend = "hermes-dashboard", bearer = bearer)
        val previousFactory = dev.hermes.android.push.KeepConnectedService.clientFactoryForTest
        val pushClients = mutableListOf<dev.hermes.android.data.BeaconClient>()
        val profileClients = mutableListOf<dev.hermes.android.data.BeaconClient>()
        try {
            fixture.currentRefreshToken = refreshToken
            runBlocking { app.auth.persist(auth) }
            assertTrue(app.push.bindKeepConnectedSource(scope, source))
            fixture.incomingNotification = json(
                "seq" to 1,
                "event_id" to event,
                "owner_id" to scope.owner,
                "profile" to scope.profile,
                "kind" to "task_result",
                "state" to "finished",
                "source_ref" to source,
                "session_id" to "chat-b",
                "reason" to "Profile refresh test",
            )
            dev.hermes.android.push.KeepConnectedService.clientFactoryForTest = { current, store -> fixture.freshClientFor(current, store).also(pushClients::add) }
            val profileSwitchClientFactory: (dev.hermes.android.data.AuthContext) -> dev.hermes.android.data.BeaconClient = { current ->
                fixture.freshClientFor(current, app.auth).also(profileClients::add)
            }
            field(vm, "profileSwitchClientFactoryForTest", profileSwitchClientFactory)
            fixture.forceExpiredAccessToken = bearer.accessToken
            vm.handlePush(source, event)
            await { vm.ui.value.tab == "Chat" && vm.ui.value.profile == scope.profile && vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
            val firstRefresh = requireNotNull(app.auth.read(auth.origin).bearer)
            assertEquals(scope.profile, firstRefresh.profile)
            assertEquals(fixture.currentRefreshToken, firstRefresh.refreshToken)
            assertEquals(listOf(refreshToken), fixture.refreshTokensUsed.toList())

            fixture.forceExpiredAccessToken = firstRefresh.accessToken
            runBlocking { vm.refreshSessions(force = true).join() }
            await { fixture.refreshTokensUsed.size == 2 && app.auth.read(auth.origin).bearer?.refreshToken == fixture.currentRefreshToken }
            assertEquals(listOf(refreshToken, firstRefresh.refreshToken), fixture.refreshTokensUsed.toList())
            assertEquals(fixture.currentRefreshToken, vm.client?.auth?.bearer?.refreshToken)
            receipts += "Opening a notification for another profile rebuilds the active client from stored auth, so its next bearer rotation is persisted."
        } finally {
            fixture.forceExpiredAccessToken = null
            vm.client?.closeNative()
            profileClients.forEach { it.closeNative() }
            pushClients.forEach { it.closeNative() }
            dev.hermes.android.push.KeepConnectedService.clientFactoryForTest = previousFactory
            app.getSharedPreferences("push-binding", android.content.Context.MODE_PRIVATE).edit().remove("source:" + source.digest()).commit()
        }
    }

    @Test fun leavingTheAssistantTabNeverLeavesItsConversationInChat() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.newChat() }
        await { vm.chat.value.sessionId.isBlank() || vm.chat.value.sessionId.startsWith("new-") && !vm.ui.value.newChatOpening }
        val before = vm.chat.value.sessionId
        compose.runOnIdle { vm.tab("Beacon") }
        await { vm.ui.value.tab == "Beacon" && !vm.ui.value.beaconOpening && vm.chat.value.projectionReady && vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != before }
        val assistant = vm.chat.value.sessionId
        compose.runOnIdle { vm.tab("Chat") }
        compose.waitUntil(5_000) { vm.chat.value.sessionId != assistant }
        assertEquals("Chat returns to the new chat it had", before, vm.chat.value.sessionId)
        receipts += "Leaving the assistant tab returns Chat to its own chat, even a new one, never the assistant's conversation."
    }

    @Test fun leavingAssistantThroughSettingsReturnsToChatsAndReenteringFromMoreStaysOnBeacon() = withFixture { vm, fixture ->
        val assistantScope = fixture.scope.copy(profile = "coder")
        val profileClients = mutableListOf<dev.hermes.android.data.BeaconClient>()
        var assistantSession = ""
        try {
            val profileSwitchClientFactory: (dev.hermes.android.data.AuthContext) -> dev.hermes.android.data.BeaconClient = { auth ->
                fixture.freshClientFor(auth, app.auth).also(profileClients::add)
            }
            field(vm, "profileSwitchClientFactoryForTest", profileSwitchClientFactory)
            runBlocking {
                app.cache.delete(fixture.scope.session("chat-b"), "session")
                listOf("beacon-session", "beacon-opening", "selected-session").forEach { app.cache.delete(assistantScope, it) }
                app.preferences.edit { it[stringPreferencesKey("assistant_profile")] = "coder" }
            }
            await { vm.preferences.value.assistantProfile == "coder" }
            compose.runOnIdle { vm.selectSession("chat-b") }
            await { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
            compose.runOnIdle { vm.tab("Beacon") }
            await { vm.ui.value.tab == "Beacon" && vm.ui.value.profile == "coder" && !vm.ui.value.beaconOpening && vm.chat.value.projectionReady && vm.isBeaconConversation() }
            assistantSession = vm.chat.value.sessionId

            compose.runOnIdle { vm.more("Settings") }
            await { vm.ui.value.tab == "More" && vm.ui.value.morePage == "Settings" }
            compose.runOnIdle { vm.tab("Chat") }
            await { vm.ui.value.tab == "Chat" && vm.ui.value.profile == fixture.scope.profile && vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }

            compose.runOnIdle { vm.more("Settings"); vm.tab("Beacon") }
            await { vm.ui.value.tab == "Beacon" && vm.ui.value.profile == "coder" && !vm.ui.value.beaconOpening && vm.chat.value.sessionId == assistantSession && vm.chat.value.projectionReady }
            receipts += "Leaving Beacon through Settings restores the original chat profile and session once; entering Beacon from More keeps its assistant profile."
        } finally {
            vm.client?.closeNative()
            profileClients.forEach { it.closeNative() }
            runBlocking {
                app.preferences.edit { it.remove(stringPreferencesKey("assistant_profile")); it.remove(stringPreferencesKey("assistant_return")) }
                app.cache.delete(fixture.scope.session("chat-b"), "session")
                app.cache.delete(fixture.scope, "selected-session")
                listOf("beacon-session", "beacon-opening", "selected-session").forEach { app.cache.delete(assistantScope, it) }
                if (assistantSession.isNotBlank()) app.cache.delete(assistantScope.session(assistantSession), "session")
            }
        }
    }

    @Test fun pickingAProfileOnTheAssistantTabIsForTheChats() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.tab("Beacon") }
        await { vm.ui.value.tab == "Beacon" && !vm.ui.value.beaconOpening && vm.chat.value.projectionReady }
        try {
            compose.runOnIdle { vm.chooseProfile("coder") }
            await { vm.ui.value.tab == "Chat" && vm.ui.value.profile == "coder" }
            receipts += "A profile picked while on the assistant tab applies to the chats and opens Chat, leaving the assistant's profile alone."
        } finally {
            runBlocking {
                app.preferences.edit { it.remove(stringPreferencesKey("assistant_return")) }
                listOf("beacon-session", "new-chat-shell", "selected-session").forEach { app.cache.delete(fixture.scope.copy(profile = "coder"), it) }
            }
        }
    }

    @Test fun newChatComposerLoadsProfileDefaultsInsteadOfThePreviousChatsOverrides() = withFixture { vm, fixture ->
        fixture.profileModel = "profile-model"
        fixture.reasoningEffort = "low"
        compose.runOnIdle { vm.pickModel("other-model", "fixture") }
        await { vm.modelSaving.value.isBlank() && vm.chat.value.raw.string("model") == "other-model" }
        compose.runOnIdle { vm.setReasoning("high") }
        await { !vm.reasoningSaving.value && vm.chat.value.sessionReasoningEffort == "high" }
        compose.runOnIdle { vm.newChat() }
        await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && vm.data.value["profile-defaults"]?.string("session_id") == vm.chat.value.sessionId }
        compose.onNodeWithTag("composer-model-effort", useUnmergedTree = true).assert(hasAnyDescendant(hasText("profile-model"))).assert(hasAnyDescendant(hasText("low")))
        assertEquals("profile-model", fixture.profileModel)
        assertEquals("low", fixture.reasoningEffort)
        receipts += "A new chat displays the profile defaults without inheriting the previous chat's model or reasoning override."
    }

    @Test fun assistantChatModelChoiceKeepsItsSettingsDefaultAndSurvivesReopening() = withFixture { vm, fixture ->
        var assistantScope = fixture.scope
        var assistantChat = ""
        try {
            assistantScope = fixture.scope.copy(profile = runBlocking {
                app.preferences.data.first()[stringPreferencesKey("assistant_profile")].orEmpty().ifBlank { "default" }
            })
            if (assistantScope != fixture.scope) runBlocking {
                listOf("new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session", "beacon-session", "beacon-opening").forEach { app.cache.delete(assistantScope, it) }
                (1..24).forEach { n -> listOf("pending", "draft", "session", "client-created-shell", "side-question").forEach { app.cache.delete(assistantScope.session("new-$n"), it) } }
            }
            compose.runOnIdle { vm.tab("Beacon") }
            await {
                val sessionId = vm.chat.value.sessionId
                vm.ui.value.tab == "Beacon" && !vm.ui.value.beaconOpening && vm.isBeaconConversation() && vm.chat.value.projectionReady &&
                    fixture.sessions[sessionId]?.getJSONObject("info")?.string("model") == vm.chat.value.raw.string("model")
            }
            assistantChat = vm.chat.value.sessionId
            assertEquals(assistantChat, runBlocking { app.cache.read(assistantScope, "beacon-session") })
            compose.runOnIdle { vm.setAssistantModel("other-model", "fixture") }
            await {
                vm.preferences.value.assistantModel == "other-model" && vm.chat.value.sessionId == assistantChat &&
                    vm.chat.value.raw.string("model") == "other-model" && vm.conversationPending.value.isEmpty()
            }
            compose.onNodeWithTag("composer-model-effort").performClick()
            compose.onNodeWithText("Model & provider").performClick()
            await { vm.data.value["picker:models"]?.rows("groups")?.isNotEmpty() == true && "picker:models" !in vm.refreshing.value }
            compose.onNodeWithTag("model:fixture:synthetic-model").performClick()
            await {
                vm.modelSaving.value.isBlank() && vm.chat.value.sessionId == assistantChat &&
                    vm.chat.value.raw.string("model") == "synthetic-model" && vm.catalog.value.isBlank() && vm.conversationPending.value.isEmpty()
            }
            assertEquals("other-model", vm.preferences.value.assistantModel)
            compose.runOnIdle { vm.tab("Chat") }
            await { vm.ui.value.tab == "Chat" && vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
            compose.runOnIdle { vm.tab("Beacon") }
            await {
                vm.ui.value.tab == "Beacon" && !vm.ui.value.beaconOpening && vm.isBeaconConversation() &&
                    vm.chat.value.sessionId == assistantChat && vm.chat.value.projectionReady &&
                    fixture.sessions[assistantChat]?.getJSONObject("info")?.string("model") == vm.chat.value.raw.string("model")
            }
            assertEquals("synthetic-model", vm.chat.value.raw.string("model"))
            assertEquals(assistantChat, runBlocking { app.cache.read(assistantScope, "beacon-session") })
            receipts += "An assistant chat model pick stays in that chat, preserves its Settings default, and survives reopening."
        } finally {
            runBlocking {
                app.preferences.edit { it.remove(stringPreferencesKey("assistant_model")); it.remove(stringPreferencesKey("assistant_provider")) }
                if (assistantScope != fixture.scope) {
                    val watcher = BeaconViewModel::class.java.getDeclaredField("sessionWatch").apply { isAccessible = true }.get(vm) as? kotlinx.coroutines.Job
                    watcher?.cancel()
                    watcher?.join()
                    val id = assistantChat.ifBlank { app.cache.read(assistantScope, "beacon-session").orEmpty() }
                    listOf("new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session", "beacon-session", "beacon-opening").forEach { app.cache.delete(assistantScope, it) }
                    if (id.isNotBlank()) listOf("pending", "draft", "session", "client-created-shell", "side-question").forEach { app.cache.delete(assistantScope.session(id), it) }
                }
            }
        }
    }

    @Test fun theAssistantsNameShowsWhereverTheAssistantIsNamed() = withFixture { vm, fixture ->
        try {
            compose.runOnIdle { vm.more("Assistant") }
            compose.onNodeWithTag("assistant-name").performTextReplacement("Nova")
            compose.onNodeWithTag("assistant-name-save").performClick()
            await { vm.preferences.value.assistantName == "Nova" }
            // The process-wide copy for services follows the saved setting asynchronously.
            await { dev.hermes.android.data.currentAssistantName == "Nova" }

            compose.runOnIdle { vm.tab("Beacon") }
            await { vm.ui.value.tab == "Beacon" && !vm.ui.value.beaconOpening && vm.chat.value.projectionReady }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Message Nova", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { vm.toggleSessions(true) }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("history-beacon-row") and hasText("Nova", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            receipts += "Renaming the assistant in Settings shows the new name on its tab and composer."
        } finally {
            runBlocking { app.preferences.edit { it.remove(stringPreferencesKey("assistant_name")) } }
        }
    }

    @Test fun forgottenPinnedBeaconConversationIsReplacedInPlaceWithItsDraft() = withFixture { vm, fixture ->
        // Live 2026-10-19: the pinned Beacon chat kept a pointer to a shell Hermes never saved and refused every send.
        var pinnedId = ""
        var freshId = ""
        try {
            compose.runOnIdle { vm.tab("Beacon") }
            await { vm.ui.value.tab == "Beacon" && vm.chat.value.sessionId.startsWith("new-") && !vm.ui.value.beaconOpening && vm.chat.value.projectionReady }
            pinnedId = vm.chat.value.sessionId
            assertEquals(pinnedId, runBlocking { app.cache.read(fixture.scope, "beacon-session") })
            fixture.markUnpromptedShell(pinnedId)
            fixture.forgetUnpromptedShell(pinnedId)
            runBlocking { app.cache.delete(fixture.scope.session(pinnedId), "session") }
            compose.runOnIdle { vm.composer(TextFieldValue("Pinned draft")); vm.selectSession(pinnedId) }

            await { vm.ui.value.tab == "Beacon" && vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.sessionId != pinnedId && !vm.ui.value.beaconOpening && vm.chat.value.projectionReady }
            freshId = vm.chat.value.sessionId
            assertEquals(freshId, runBlocking { app.cache.read(fixture.scope, "beacon-session") })
            assertTrue("The forgotten pinned shell was resumed", fixture.requests.any {
                it.string("method") == "session.resume" && it.getJSONObject("params").string("session_id") == pinnedId
            })
            assertEquals("Pinned draft", vm.composer.value.text)
            compose.runOnIdle { vm.send() }
            await { fixture.requests.count { it.string("method") == "prompt.submit" } == 1 }
            assertEquals("Pinned draft", fixture.requests.single { it.string("method") == "prompt.submit" }.getJSONObject("params").string("text"))
            receipts += "A pinned Beacon conversation Hermes has forgotten is replaced by a fresh pinned one in place, with its draft, and sends once."
        } finally {
            runBlocking {
                listOf(pinnedId, freshId).filter(String::isNotBlank).forEach { id ->
                    listOf("client-created-shell", "session", "draft", "pending").forEach { app.cache.delete(fixture.scope.session(id), it) }
                }
                listOf("beacon-session", "beacon-opening", "selected-session", "initial-draft").forEach { app.cache.delete(fixture.scope, it) }
            }
        }
    }

    @Test fun referenceNavigationAndNativeBoardControls() = withFixture { vm, fixture ->
        runBlocking { app.cache.delete(fixture.scope, "kanban-draft:default") }
        showProfileDisplayNames(vm, fixture)
        compose.runOnIdle { vm.toggleSessions(true) }
        compose.onNodeWithTag("navigation-profile").assertIsDisplayed().assertTextContains("Personal profile").performClick()
        await { vm.data.value["navigation-profiles"] != null }
        compose.onNode(hasText("Personal profile") and hasAnyAncestor(isPopup())).assertExists()
        compose.onNode(hasText("Code profile") and hasAnyAncestor(isPopup())).assertExists()
        capture("reference-profiles")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("navigation-kanban").performScrollTo().performClick()
        await { compose.onAllNodesWithText("Review travel options").fetchSemanticsNodes().isNotEmpty() }
        capture("reference-kanban")
        compose.onNodeWithContentDescription("New task").performClick()
        compose.onNodeWithTag("kanban-title").performTextInput("Prepare the itinerary")
        compose.onNodeWithText("Bot profile: Unassigned").performScrollTo().performClick()
        compose.onNode(hasText("Code profile") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("Keep draft").performClick()
        compose.onNodeWithText("Continue saved task draft").performClick()
        compose.onNodeWithTag("kanban-title").assertTextContains("Prepare the itinerary")
        compose.onNodeWithText("Bot profile: Code profile").assertExists()
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        await { fixture.boardTasks["t_new"] != null && compose.onAllNodesWithTag("kanban-title").fetchSemanticsNodes().isEmpty() }
        assertEquals("Prepare the itinerary", fixture.boardTasks.getValue("t_new").string("title"))
        assertEquals("coder", fixture.boardTasks.getValue("t_new").string("assignee"))
        val submit = fixture.requests.single { it.string("path") == "/api/plugins/kanban/tasks" && it.string("method") == "POST" }
        assertTrue(submit.getJSONObject("body").string("idempotency_key").startsWith("beacon-"))
        receipts += "Reference drawer opens native Kanban; task draft survives closing and explicit Save dispatches one native request."
        capture("reference-kanban-saved")
        compose.runOnIdle { vm.navigate("Projects") }
        await { vm.data.value["native-projects"] != null }
        compose.onNodeWithText("Travel", useUnmergedTree = true).performClick()
        await { vm.data.value["native-project:p_project_a"] != null }
        compose.onNode(hasText("Travel planning") and hasAnyAncestor(hasTestTag("projects-screen")), useUnmergedTree = true).performClick()
        await { vm.chat.value.sessionId == "chat-a" && vm.ui.value.tab == "Chat" }
        receipts += "Native project tree and scoped conversation drill-in use their real RPC contracts."
        capture("reference-project-chat")
        compose.runOnIdle { vm.navigate("Files") }
        await { compose.onAllNodesWithText("notes.md").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Terminal").assertDoesNotExist()
        compose.onNodeWithText("notes.md").performClick()
        await { vm.editor.value.path == "C:/synthetic-workspace/notes.md" && !vm.editor.value.loading }
        assertTrue(vm.editor.value.text.text.contains("Travel notes"))
        capture("reference-files")
        compose.runOnIdle { vm.navigate("Skills") }
        await { compose.onAllNodesWithText("travel-planning").fetchSemanticsNodes().isNotEmpty() }
        capture("reference-skills")
        compose.runOnIdle { vm.navigate("Memory") }
        await { vm.phoneSettings.memory.value.documents.size == 2 && !vm.phoneSettings.memory.value.loading }
        compose.onNodeWithContentDescription("Show navigation").assertIsDisplayed()
        compose.onNodeWithText("Agent notes").assertExists()
        capture("reference-memory")
    }

    @Test fun richDocumentHasRealDiagramsAndSafeHtml() = withFixture { vm, fixture ->
        runBlocking { app.cache.delete(fixture.scope.session("chat-a"), "reading-checklist:700") }
        val content = """
            ## Launch plan
            - [ ] Review the itinerary
            - [x] Check the dates

            | Plan | Days |
            |---|---:|
            | City | 3 |
            | Mountain | 5 |

            > [!TIP]
            > Check the forecast before departure.

            ```mermaid
            flowchart TD
              Start[Travel request] --> Check{Ready?}
              Check -->|Yes| Go[Book trip]
              Check -->|No| Edit[Review plan]
            ```

            ```html
            <h2>Trip card</h2><p>Ready for review</p><script>window.unsafeRan=true</script><img src="https://private.invalid/tracker">
            ```

            ```diff
            - old plan
            + reviewed plan
            ```
        """.trimIndent()
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = listOf(Message.from(json("role" to "assistant", "text" to content, "row_id" to 700), 0, "chat-a"))) }
        await { compose.onAllNodesWithContentDescription("Review the itinerary").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Review the itinerary").performScrollTo().performClick().assertIsOn()
        capture("reference-checklist")
        compose.onNodeWithTag("rich-mermaid").performScrollTo()
        fun web(diagram: Boolean): android.webkit.WebView? {
            fun find(view: android.view.View): android.webkit.WebView? {
                if (view is android.webkit.WebView && view.contentDescription == if (diagram) "Mermaid diagram preview" else "HTML card") return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            return find(compose.activity.window.decorView)
        }
        var svgCount = 0
        val deadline = System.currentTimeMillis() + 20_000
        while (svgCount == 0 && System.currentTimeMillis() < deadline) {
            val latch = java.util.concurrent.CountDownLatch(1)
            compose.runOnUiThread { requireNotNull(web(true)).evaluateJavascript("document.querySelectorAll('svg').length") { svgCount = it.toIntOrNull() ?: 0; latch.countDown() } }
            assertTrue(latch.await(3, java.util.concurrent.TimeUnit.SECONDS))
            if (svgCount == 0) Thread.sleep(200)
        }
        assertTrue("Bundled Mermaid must render an actual SVG in Android WebView", svgCount > 0)
        capture("reference-mermaid")
        compose.onNodeWithText("Fullscreen diagram").performScrollTo().performClick()
        compose.onNodeWithText("Close fullscreen").assertIsDisplayed()
        var originalScale = 0f
        val dialogWebView = androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.webkit.WebView::class.java)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
        dialogWebView.check { view, failure -> if (failure != null) throw failure; @Suppress("DEPRECATION") originalScale = (view as android.webkit.WebView).scale }
        compose.onNodeWithContentDescription("Zoom in preview").performClick()
        var zoomed = false
        val zoomDeadline = System.currentTimeMillis() + 3000
        while (!zoomed && System.currentTimeMillis() < zoomDeadline) {
            dialogWebView.check { view, failure -> if (failure != null) throw failure; @Suppress("DEPRECATION") zoomed = (view as android.webkit.WebView).scale > originalScale }
            if (!zoomed) Thread.sleep(50)
        }
        assertTrue("Accessible zoom control must increase actual WebView scale", zoomed)
        capture("reference-mermaid-fullscreen")
        compose.onNodeWithText("Close fullscreen").performClick()
        compose.onNodeWithTag("rich-html").performScrollTo()
        compose.runOnUiThread { val html = requireNotNull(web(false)); assertFalse(html.settings.javaScriptEnabled); assertFalse(html.settings.allowFileAccess); assertFalse(html.settings.allowContentAccess); assertTrue(html.settings.blockNetworkLoads) }
        capture("reference-html")
        compose.runOnUiThread {
            val html = requireNotNull(web(false))
            assertTrue(html.webViewClient.onRenderProcessGone(html, object : android.webkit.RenderProcessGoneDetail() {
                override fun didCrash() = true
                override fun rendererPriorityAtExit() = android.webkit.WebView.RENDERER_PRIORITY_BOUND
            }))
        }
        compose.onNodeWithText("Preview stopped. Your message is unchanged.").assertIsDisplayed()
        compose.onNodeWithText("Retry preview").performClick()
        compose.waitForIdle()
        compose.runOnUiThread { assertNotNull(web(false)) }
        assertTrue(fixture.requests.none { it.string("path").contains("mermaid") || it.string("path").contains("tracker") })
        receipts += "Android WebView renders local Mermaid SVG and fullscreen; HTML has JavaScript/file/content/network disabled; checklist toggles natively."
    }

    @Test fun historyComposerAndNavigationKeepTheirMeaning() = withFixture { vm, fixture ->
        assertEquals("Compare the two plans.", vm.chat.value.messages.first().text)
        assertEquals(2, vm.chat.value.messages.size)
        val share = formattedShareIntent(vm.chat.value.messages.last().text)
        assertTrue(share.getStringExtra(android.content.Intent.EXTRA_HTML_TEXT).orEmpty().contains("<table"))
        assertTrue(share.getStringExtra(android.content.Intent.EXTRA_TEXT).orEmpty().contains("Mountains"))
        receipts += "Share payload carries safe HTML and readable plain text; receiving-app formatting remains its responsibility."
        await { compose.onAllNodesWithText("Mountains and the surrounding national park", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("message-bubble:11").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithTag("message-copy:11").assertIsDisplayed().performClick()
        val clipboard = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        compose.waitForIdle()
        await { clipboard.primaryClip?.getItemAt(0)?.htmlText?.contains("<table") == true }
        val copied = requireNotNull(clipboard.primaryClip).getItemAt(0)
        assertTrue("Formatted copy keeps table cells", copied.htmlText.orEmpty().contains("<table"))
        assertTrue("Readable plain-text fallback is retained", copied.text.toString().contains("Mountains"))
        receipts += "Production formatted-copy action publishes HTML table plus readable text fallback."
        capture("chat-table")
        compose.onNodeWithTag("message-input").performClick().performTextInput("Keep this draft during navigation")
        compose.runOnIdle {
            val input = compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            input.showSoftInput(compose.activity.currentFocus, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        await { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
        fun keyboardTop(): Int {
            val decor = compose.activity.window.decorView
            val bottom = androidx.core.view.ViewCompat.getRootWindowInsets(decor)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
            return decor.height - bottom
        }
        await {
            compose.onNodeWithTag("message-input").fetchSemanticsNode().boundsInWindow.bottom <= keyboardTop() + 2 &&
                compose.onNodeWithTag("message-send").fetchSemanticsNode().boundsInWindow.bottom <= keyboardTop() + 2
        }
        receipts += "Android reports the software keyboard visible while the composer stays reachable."
        val inputBounds = compose.onNodeWithTag("message-input").fetchSemanticsNode().boundsInWindow
        val sendBounds = compose.onNodeWithTag("message-send").fetchSemanticsNode().boundsInWindow
        // The primary action is a fixed 42dp circle that sits in the control row below the editor, never wider than tall.
        val sendFootprint = compose.onNodeWithTag("message-send").getUnclippedBoundsInRoot()
        assertEquals("Primary action is 42dp wide", 42f, (sendFootprint.right - sendFootprint.left).value, .75f)
        assertEquals("Primary action is 42dp tall", 42f, (sendFootprint.bottom - sendFootprint.top).value, .75f)
        // The 42dp circle keeps a 48dp tap space, so Dictate ends at least 3dp before it and a near miss still sends.
        val dictateFootprint = compose.onNodeWithTag("composer-dictate").getUnclippedBoundsInRoot()
        assertTrue("Dictate leaves the send tap space", (sendFootprint.left - dictateFootprint.right).value >= 2.25f)
        assertTrue("Primary action is below the text area and above the keyboard", sendBounds.top >= inputBounds.bottom && sendBounds.bottom <= keyboardTop() + 2)
        val attachBounds = compose.onNodeWithTag("composer-attach").fetchSemanticsNode().boundsInWindow
        val permissionBounds = compose.onNodeWithTag("composer-approvals").fetchSemanticsNode().boundsInWindow
        val modelBounds = compose.onNodeWithTag("composer-model-effort").fetchSemanticsNode().boundsInWindow
        val dictateBounds = compose.onNodeWithTag("composer-dictate").fetchSemanticsNode().boundsInWindow
        assertTrue("Composer controls retain their requested reading order", attachBounds.right <= permissionBounds.left && permissionBounds.right <= modelBounds.left && modelBounds.right <= dictateBounds.left && dictateBounds.right <= sendBounds.left)
        val composerViewport = compose.onNodeWithTag("experience-viewport").fetchSemanticsNode().boundsInRoot
        listOf("composer-attach", "composer-approvals", "composer-model-effort", "composer-dictate", "message-send").forEach { tag ->
            val control = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag stays inside the usable window", control.left >= composerViewport.left && control.right <= composerViewport.right && control.bottom <= composerViewport.bottom)
        }
        capture("composer-keyboard")
        compose.onNodeWithContentDescription("Conversation actions").performClick()
        compose.onNodeWithTag("conversation-actions-menu").assertIsDisplayed()
        compose.onNodeWithText("Delete conversation").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reasoning", useUnmergedTree = true).assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("navigation-toggle").performClick()
        compose.onNodeWithTag("navigation-hide").assertDoesNotExist()
        compose.onNodeWithTag("navigation-menu").performTouchInput { swipeLeft() }
        await { !vm.ui.value.showSessions }
        assertEquals("Keep this draft during navigation", vm.composer.value.text)
        receipts += "Navigation open/close preserves draft and selected session."
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(streamId = "runtime-chat-a", liveText = "Preparing the comparison.") }
        compose.onNodeWithTag("message-send").assertIsEnabled().assertIsDisplayed().assertContentDescriptionEquals("Queue")
        val viewport = compose.onNodeWithTag("experience-viewport").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag("message-send").fetchSemanticsNode().boundsInRoot
        assertTrue("Queue must stay inside the usable window", send.left >= viewport.left && send.right <= viewport.right && send.bottom <= viewport.bottom)
        compose.onNodeWithTag("message-send").performClick()
        assertEquals(1, vm.chat.value.queued.size)
        assertTrue(vm.composer.value.text.isBlank())
        assertTrue("Queue must not dispatch another prompt", fixture.requests.none { it.string("method") == "prompt.submit" })
        receipts += "Busy primary queues locally; no prompt dispatched."
        // With the draft now empty and the turn still streaming, the trailing voice slot becomes Stop, in the same 42dp footprint.
        compose.onNodeWithTag("message-stop").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("message-send").assertDoesNotExist()
        compose.onNodeWithTag("chat-call-beacon").assertDoesNotExist()
        val stopFootprint = compose.onNodeWithTag("message-stop").getUnclippedBoundsInRoot()
        assertEquals("Stop is 42dp wide", 42f, (stopFootprint.right - stopFootprint.left).value, .75f)
        assertEquals("Stop is 42dp tall", 42f, (stopFootprint.bottom - stopFootprint.top).value, .75f)
        capture("busy-composer")
        compose.onNodeWithTag("message-stop").performClick()
        await { fixture.requests.any { it.string("method") == "session.interrupt" } }
        assertEquals("Stop sends one native interrupt for the displayed runtime stream", listOf("runtime-chat-a"), fixture.requests.filter { it.string("method") == "session.interrupt" }.map { it.getJSONObject("params").string("session_id") })
        assertEquals("Stop is not a send: the queue is untouched", 1, vm.chat.value.queued.size)
        assertTrue("Stop never submits or creates a conversation", fixture.requests.none { it.string("method") in setOf("prompt.submit", "session.create") })
        compose.runOnIdle { vm.clearMessage() }
        receipts += "Stop replaces voice when an empty composer streams, keeps the 42dp footprint, and its existing callback sends exactly one native session.interrupt for the runtime id without touching the queue."
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(streamId = "", liveText = ""); vm.composer(TextFieldValue("/")) }
        await { vm.data.value["slash:catalog"] != null }
        compose.onNodeWithTag("message-input").performClick()
        capture("slash-above-keyboard")
        val suggestions = compose.onNodeWithTag("slash-suggestions").getUnclippedBoundsInRoot()
        val input = compose.onNodeWithTag("message-input").getUnclippedBoundsInRoot()
        assertTrue("Slash suggestions belong above the editor", suggestions.bottom <= input.top)
        assertTrue("Slash suggestions must stay inside the window", suggestions.top >= 0.dp)
        compose.onNodeWithText("/help", substring = true).assertExists()
        assertEquals(0, fixture.requests.count { it.string("method") == "session.create" })
        receipts += "Typing slash discovers commands without creating a conversation."
        capture("slash-commands")
        fixture.holdCreateAcknowledgement = true
        compose.runOnIdle { vm.tab("Beacon") }
        await { fixture.requests.count { it.string("method") == "session.create" } == 1 }
        compose.runOnIdle { repeat(50) { vm.openBeacon() } }
        fixture.releaseCreateAcknowledgement()
        await { !vm.ui.value.beaconOpening && vm.chat.value.sessionId.startsWith("new-") }
        assertEquals(1, fixture.requests.count { it.string("method") == "session.create" })
        assertTrue(vm.ui.value.beaconError.isBlank())
        receipts += "Fifty repeated Beacon opens while create ACK is delayed resolve one durable conversation."
        capture("stable-beacon")
    }

    /** Combined root wiring for the chat reference states: recorded time, tool outcomes, live work and the unchanged Beacon shell. */
    @Test fun migrationChatReferenceStates() = withFixture { vm, fixture ->
        // One fixed instant. The expected label comes from this device's zone and locale, never a hard-coded local clock.
        val known = java.time.Instant.parse("2026-10-14T09:41:00Z").toEpochMilli()
        val zone = java.util.TimeZone.getDefault()
        val locale = java.util.Locale.getDefault()
        val shown = java.text.SimpleDateFormat(dev.hermes.android.ui.nativeClockPattern(locale, false), locale).apply { timeZone = zone }.format(java.util.Date(known))
        assertEquals(shown, messageTimeLabel(known, zone, locale))
        assertEquals("Native seconds and milliseconds name the same instant", shown, messageTimeLabel(known / 1000, zone, locale))
        assertEquals("9:41 AM", messageTimeLabel(known, java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.US)?.replace(' ', ' '))
        assertEquals("09:41", messageTimeLabel(known, java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.GERMANY, is24Hour = true))
        assertNull("An unrecorded time stays unknown", messageTimeLabel(0))
        val passedArguments = "gradle test\n--tests dev.hermes.ChatTest\n--info"
        val passedResult = "> Task :app:test\nBUILD SUCCESSFUL in 4s\n12 tests passed"
        val failedArguments = "path=C:/synthetic-workspace/missing.txt\nencoding=utf-8"
        val failedResult = "Error: file not found\nChecked 2 locations"
        val silentArguments = "query=synthetic release notes\nlimit=3"
        val passed = json("tool_call_id" to "ref-tool-passed", "name" to "run_tests", "args" to passedArguments, "duration_s" to 1.2)
        val failed = json("tool_call_id" to "ref-tool-failed", "name" to "read_file", "args" to failedArguments, "duration_s" to 0.4)
        val silent = json("tool_call_id" to "ref-tool-silent", "name" to "web_search", "args" to silentArguments)
        // Tool lifecycle: a stored call that only ever said "running" has no terminal evidence. Its live sibling below has the same payload.
        val stale = json("tool_call_id" to "ref-tool-stale", "name" to "sync_notes", "args" to "target=notes.md", "status" to "running")
        // The native row key is `timestamp`; a row that has none must stay unrecorded.
        fun row(id: String, role: String, text: String, timestamp: Long? = null, vararg tools: JSONObject) =
            json("row_id" to id, "role" to role, "text" to text, "tool_calls" to JSONArray().apply { tools.forEach { put(it) } }).apply { timestamp?.let { put("timestamp", it) } }
        val passedRow = json("id" to "tool-result-passed", "role" to "tool", "tool_call_id" to "ref-tool-passed", "tool_name" to "run_tests", "content" to passedResult)
        val failedRow = json("id" to "tool-result-failed", "role" to "tool", "tool_call_id" to "ref-tool-failed", "tool_name" to "read_file", "content" to failedResult, "is_error" to true)
        val orphanRow = json("id" to "tool-result-orphan", "role" to "tool", "tool_call_id" to "missing-call", "tool_name" to "read_file", "content" to "orphan output")
        val rows = listOf(
            row("ref-user-known", "user", "Summarize the build log.", known),
            row("ref-assistant-known", "assistant", "The build finished with one failed step.", known / 1000, passed, failed),
            passedRow,
            failedRow,
            row("ref-user-unknown", "user", "Try the follow-up search."),
            row("ref-assistant-unknown", "assistant", "No search completion was recorded.", null, silent, stale),
            orphanRow,
        )
        val messages = rows.mapIndexed { index, row -> Message.from(row, index, "chat-a") }
        // Parser wiring, not a test-side copy: native milliseconds and seconds both reach the same instant, and no timestamp stays 0.
        assertTrue(messages[0].recordedAt > 0 && messages[1].recordedAt > 0)
        assertEquals(shown, messageTimeLabel(messages[0].recordedAt, zone, locale))
        assertEquals(shown, messageTimeLabel(messages[1].recordedAt, zone, locale))
        assertEquals(0L, messages.first { it.id == "ref-user-unknown" }.recordedAt)
        assertEquals(0L, messages.first { it.id == "ref-assistant-unknown" }.recordedAt)
        val raw = JSONObject(vm.chat.value.raw.toString()).put("messages", JSONArray().apply { rows.forEach { put(it) } })
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = messages, raw = raw) }
        fun card(description: String): SemanticsMatcher = hasTestTag("tool-card") and hasContentDescription(description)
        fun part(card: SemanticsMatcher, tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(card), useUnmergedTree = true)
        fun toggle(card: SemanticsMatcher) = compose.onNode(hasTestTag("tool-card-toggle") and hasAnyAncestor(card), useUnmergedTree = true).performScrollTo().performClick()
        // Click the real toggle only when the card is folded.
        fun ensureExpanded(card: SemanticsMatcher) {
            if (compose.onAllNodes(hasTestTag("tool-card-details") and hasAnyAncestor(card), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) toggle(card)
            part(card, "tool-card-details").assertExists()
        }
        fun reveal(matcher: SemanticsMatcher, towardOlder: Boolean) {
            val target = compose.onNode(matcher, useUnmergedTree = true)
            val transcript = compose.onNodeWithTag("conversation-transcript")
            repeat(8) {
                if (runCatching { target.assertIsDisplayed() }.isSuccess) return
                transcript.performTouchInput { if (towardOlder) swipeDown() else swipeUp() }
                compose.waitForIdle()
            }
            target.assertIsDisplayed()
        }
        val passedCard = card("Run tests, Completed, 1.2s · $shown")
        val failedCard = card("Read file, Failed, 0.4s · $shown")
        val silentCard = card("Web search, No result recorded")
        val staleCard = card("Sync notes, No result recorded")
        await { compose.onAllNodes(silentCard, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("message-row:tool-result-passed", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("message-row:tool-result-failed", useUnmergedTree = true).assertDoesNotExist()
        // The orphan result is the last row; a compact window composes it only once it is scrolled into view.
        compose.onNodeWithTag("conversation-transcript").performScrollToNode(hasTestTag("message-row:tool-result-orphan"))
        compose.onNodeWithTag("message-row:tool-result-orphan", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("disclosure:Read file result").performClick()
        compose.onNodeWithText("orphan output", useUnmergedTree = true).assertExists()

        // The tail is the unrecorded exchange: no time anywhere, and a tool without a completion is never successful.
        compose.onNodeWithTag("message-row:ref-assistant-unknown", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("message-time:ref-assistant-unknown", useUnmergedTree = true).assertCountEquals(0)
        part(silentCard, "tool-card-meta").assertTextEquals("No result")
        ensureExpanded(silentCard)
        part(silentCard, "tool-card-status").assertTextEquals("Status: No result recorded")
        part(silentCard, "tool-card-arguments").assertTextEquals(silentArguments)
        compose.onAllNodes(hasTestTag("tool-card-result") and hasAnyAncestor(silentCard), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("tool-card-empty-result") and hasAnyAncestor(silentCard), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes((hasText("Completed", substring = true) or hasContentDescription("Completed", substring = true)) and hasAnyAncestor(silentCard), useUnmergedTree = true).assertCountEquals(0)
        compose.onNode(hasText("No completion was recorded for this tool.") and hasAnyAncestor(silentCard), useUnmergedTree = true).assertExists()
        // The stored "running" sibling is also No result: no spinner, no Running label, no Completed.
        part(staleCard, "tool-card-meta").assertTextEquals("No result")
        compose.onAllNodes(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate) and hasAnyAncestor(staleCard), useUnmergedTree = true).assertCountEquals(0)
        ensureExpanded(staleCard)
        part(staleCard, "tool-card-status").assertTextEquals("Status: No result recorded")
        part(staleCard, "tool-card-arguments").assertTextEquals("target=notes.md")
        compose.onAllNodes((hasText("Running", substring = true) or hasText("Completed", substring = true) or hasContentDescription("Running", substring = true) or hasContentDescription("Completed", substring = true)) and hasAnyAncestor(staleCard), useUnmergedTree = true).assertCountEquals(0)
        compose.onNode(hasText("No completion was recorded for this tool.") and hasAnyAncestor(staleCard), useUnmergedTree = true).assertExists()
        part(staleCard, "tool-card-details").performScrollTo()
        // Cards keep to the 840dp transcript column on every width and font scale; long text wraps or scrolls inside the card.
        val transcriptBounds = compose.onNodeWithTag("conversation-transcript").getUnclippedBoundsInRoot()
        val silentBounds = compose.onNode(silentCard, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Tool cards stay inside the transcript column", silentBounds.left >= transcriptBounds.left && silentBounds.right <= transcriptBounds.right && silentBounds.right - silentBounds.left <= 840.dp)
        receipts += "A stored tool whose only status is running has no terminal evidence, so it reads No result with no spinner; its live twin below stays Running."
        capture("migration-tool-no-result")

        // Expanding a tool stops tail-following, so explicit scrolling to older rows holds.
        reveal(hasTestTag("message-row:ref-user-unknown"), towardOlder = true)
        compose.onNodeWithTag("message-row:ref-user-unknown", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("message-time:ref-user-unknown", useUnmergedTree = true).assertCountEquals(0)
        reveal(hasTestTag("message-row:ref-user-known"), towardOlder = true)
        compose.onNodeWithTag("message-time:ref-user-known", useUnmergedTree = true).assertIsDisplayed().assertTextEquals(shown)
        reveal(hasTestTag("message-time:ref-assistant-known"), towardOlder = false)
        compose.onNodeWithTag("message-time:ref-assistant-known", useUnmergedTree = true).assertIsDisplayed().assertTextEquals(shown)
        part(passedCard, "tool-card-meta").assertTextEquals("1.2s · $shown")
        part(failedCard, "tool-card-meta").assertTextEquals("0.4s · $shown")
        receipts += "A recorded time shows in the device zone and locale on the bubble and on finished tools; an unrecorded message shows none, and a tool with no recorded completion says so and is never Completed."
        capture("migration-chat-recorded-times")

        ensureExpanded(passedCard)
        part(passedCard, "tool-card-status").assertTextEquals("Status: Completed")
        compose.onNode(hasText("Result") and hasAnyAncestor(passedCard), useUnmergedTree = true).assertExists()
        part(passedCard, "tool-card-arguments").assertTextEquals(passedArguments)
        part(passedCard, "tool-card-result").assertTextEquals(passedResult)
        compose.onAllNodes(hasTestTag("tool-card-empty-result") and hasAnyAncestor(passedCard), useUnmergedTree = true).assertCountEquals(0)
        part(passedCard, "tool-card-details").performScrollTo()
        capture("migration-tool-completed")
        ensureExpanded(failedCard)
        part(failedCard, "tool-card-status").assertTextEquals("Status: Failed")
        compose.onNode(hasContentDescription("Failed") and hasAnyAncestor(failedCard), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Error") and hasAnyAncestor(failedCard), useUnmergedTree = true).assertExists()
        part(failedCard, "tool-card-arguments").assertTextEquals(failedArguments)
        part(failedCard, "tool-card-result").assertTextEquals(failedResult)
        part(failedCard, "tool-card-details").performScrollTo()
        receipts += "Expanded tools show their full multi-line arguments and result with the recorded status; a failed call is labelled Error, not Result."
        capture("migration-tool-failed")

        // Live work through the real composer and root state: an unfinished tool, a queued message and then a prompt.
        // Same payload as the stored `stale` call, but live: it stays Running. The finished sibling's result beats its stale status.
        val running = json("tool_call_id" to "ref-live-build", "name" to "build_project", "args" to "gradle assembleDebug\n--console=plain", "status" to "running")
        val listed = json("tool_call_id" to "ref-live-list", "name" to "list_files", "args" to "path=C:/synthetic-workspace", "status" to "running", "result" to "notes.md\nresearch", "duration_s" to 0.3)
        fun assertBackgroundStripExcludesReplyActivity() {
            val inStrip = hasAnyAncestor(hasTestTag("chat-activity-strip"))
            listOf("Build project", "List files", "queued", "Waiting for you").forEach { text ->
                compose.onAllNodes(hasText(text, substring = true, ignoreCase = true) and inStrip, useUnmergedTree = true).assertCountEquals(0)
            }
        }
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(streamId = "runtime-chat-a", liveText = "Checking the build log.", liveTools = listOf(running, listed)) }
        compose.waitForIdle()
        assertBackgroundStripExcludesReplyActivity()
        reveal(hasTestTag("message-activity-status"), towardOlder = false)
        compose.onNodeWithTag("message-activity-status", useUnmergedTree = true).assertTextEquals("Working")
        val buildCard = card("Build project, Running")
        // Live steps sit behind one current-step row; opening it shows each folded card, opened with its real toggle.
        compose.onAllNodesWithTag("tool-steps", useUnmergedTree = true).onLast().performScrollTo().performClick()
        ensureExpanded(buildCard)
        part(buildCard, "tool-card-status").assertTextEquals("Status: Running")
        part(buildCard, "tool-card-arguments").assertTextEquals("gradle assembleDebug\n--console=plain")
        compose.onAllNodes(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate) and hasAnyAncestor(buildCard), useUnmergedTree = true).assertCountEquals(1)
        // Live tool events carry no clock time, so none is invented for them. A terminal result beats the sibling's stale running status.
        compose.onNode(card("List files, Completed, 0.3s"), useUnmergedTree = true).assertExists()
        receipts += "A live running preview keeps its spinner and Status: Running, while a live tool with a result is Completed even if its status still says running."
        compose.runOnIdle { vm.composer(TextFieldValue("Then update the notes.")) }
        compose.onNodeWithTag("message-send").assertIsEnabled().assertContentDescriptionEquals("Queue").performClick()
        await { vm.chat.value.queued.size == 1 }
        compose.onNodeWithText(queuedHeading(1), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("message-stop").assertIsDisplayed().assertIsEnabled()
        assertBackgroundStripExcludesReplyActivity()
        compose.onNodeWithTag("composer-queue").assertIsDisplayed()
        closeKeyboard()
        compose.onNodeWithContentDescription("Conversation actions").performClick()
        compose.onNodeWithText("Message queue", useUnmergedTree = true).assertExists()
        androidx.test.espresso.Espresso.pressBack()
        await { compose.onAllNodesWithTag("conversation-actions-menu").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Remove queued message").performScrollTo().performClick()
        await { vm.chat.value.queued.isEmpty() }
        compose.runOnIdle {
            flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(approval = json("approval_id" to "ref-approval", "description" to "Run the synthetic build task", "command" to "gradle assembleDebug", "choices" to JSONArray().put("once").put("deny")))
        }
        assertBackgroundStripExcludesReplyActivity()
        reveal(hasText("Approval needed"), towardOlder = false)
        compose.onNodeWithText("Allow once").performScrollTo().assertIsDisplayed()
        capture("migration-chat-pending")

        // The unchanged Beacon shell stays functional with work pending: top-right actions, then the left navigation.
        closeKeyboard()
        compose.onNodeWithContentDescription("Conversation actions").performClick()
        compose.onNodeWithTag("conversation-actions-menu").assertIsDisplayed()
        compose.onNodeWithText("Message queue", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Delete conversation").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reasoning", useUnmergedTree = true).assertDoesNotExist()
        capture("migration-chat-actions-menu")
        androidx.test.espresso.Espresso.pressBack()
        await { compose.onAllNodesWithTag("conversation-actions-menu").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("navigation-toggle").performClick()
        compose.onNodeWithTag("navigation-menu").assertIsDisplayed()
        capture("migration-chat-navigation")
        compose.onNodeWithTag("navigation-menu").performTouchInput { swipeLeft() }
        await { !vm.ui.value.showSessions }
        assertEquals("chat-a", vm.chat.value.sessionId)

        // The transcript's folded row owns the current reply's tools; queue and prompt remain in their own composer/transcript surfaces.
        assertBackgroundStripExcludesReplyActivity()

        // A clarification replaces the approval; the strip still waits for the user and nothing was answered.
        compose.runOnIdle {
            flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(approval = null, clarification = json("clarify_id" to "ref-question", "question" to "Which notes file should be updated?", "choices" to JSONArray().put("notes.md")))
        }
        reveal(hasText("Which notes file should be updated?"), towardOlder = false)
        compose.onNodeWithText("notes.md").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Approval needed").assertCountEquals(0)
        assertBackgroundStripExcludesReplyActivity()
        capture("migration-chat-question")

        // When the turn ends the strip leaves with it and the voice slot returns, unavailable here because the fixture has no voice.
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(clarification = null, streamId = "", liveText = "", liveTools = emptyList()) }
        compose.onAllNodesWithTag("chat-activity-strip", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("message-stop").assertDoesNotExist()
        compose.onNodeWithTag("chat-call-beacon").assertIsNotEnabled()
        assertTrue(vm.chat.value.queued.isEmpty())
        assertTrue("Reference states never send or stop anything", fixture.requests.none { it.string("method") in setOf("prompt.submit", "session.create", "session.interrupt") })
        assertTrue("Reference states never answer a native server request", fixture.nativeServerResponses.isEmpty())
        receipts += "The current reply's folded tools stay in its transcript; foreground tools, queue and prompts do not appear as background activity. The Beacon shell menus keep working, and nothing was sent."
        capture("migration-chat-settled")
    }

    @Test fun deletedInitialConversationKeepsDraftAndNewChatRecovers() = withFixture { vm, fixture ->
        @Suppress("UNCHECKED_CAST")
        val shells = BeaconViewModel::class.java.getDeclaredField("initialShells").apply { isAccessible = true }.get(vm) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val attempts = BeaconViewModel::class.java.getDeclaredField("initialCreateAttempts").apply { isAccessible = true }.get(vm) as MutableMap<String, String>
        val staleScope = fixture.scope.session("deleted-shell")
        runBlocking { app.cache.delete(staleScope, "client-created-shell"); app.cache.delete(staleScope, "session") }
        runBlocking { app.cache.write(staleScope, "client-created-shell", "1", protected = true) }
        var createdId = ""
        try {
            compose.runOnIdle {
                field(vm, "sourceScope", null)
                flow<ChatState>(vm, "_chat").value = ChatState()
                flow<AppState>(vm, "_ui").value = vm.ui.value.copy(tab = "More", morePage = "Beacon", initialChatReady = true)
                flow<TextFieldValue>(vm, "_composer").value = TextFieldValue("Retain my unfinished draft")
                shells[fixture.scope.key] = "deleted-shell"
                attempts[fixture.scope.key] = "prior-read-failure"
                vm.newChat(json("title" to "Recovered draft"))
            }
            try { await { vm.chat.value.sessionId.startsWith("new-") && !vm.ui.value.newChatOpening } }
            catch (error: Throwable) {
                val calls = recentCalls(fixture)
                throw AssertionError("Deleted-shell recovery: tab=${vm.ui.value.tab} ready=${vm.ui.value.initialChatReady} opening=${vm.ui.value.newChatOpening} id=${vm.chat.value.sessionId} error=${vm.ui.value.error} newChatError=${vm.ui.value.newChatError} notice=${vm.ui.value.notice} requests=$calls", error)
            }
            assertEquals("Retain my unfinished draft", vm.composer.value.text)
            createdId = vm.chat.value.sessionId
            val calls = recentCalls(fixture)
            assertEquals("Expected one native session.create; requests=$calls", 1, fixture.requests.count { it.string("method") == "session.create" })
            assertEquals(vm.chat.value.sessionId, shells[fixture.scope.key])
            assertFalse(attempts.containsKey(fixture.scope.key))
            assertNull(runBlocking { app.cache.read(staleScope, "client-created-shell") })
            assertTrue(vm.ui.value.newChatError.isBlank())
            receipts += "A legacy client-created marker is cleared only after native resume confirms the shell is missing; explicit New retains the draft and creates once."
        } finally {
            runBlocking {
                app.cache.delete(fixture.scope, "new-chat-shell"); app.cache.delete(fixture.scope, "new-chat-attempt"); app.cache.delete(fixture.scope, "initial-draft")
                app.cache.delete(fixture.scope, "selected-session"); app.cache.delete(fixture.scope, "conversation-index")
                app.cache.delete(staleScope, "client-created-shell"); app.cache.delete(staleScope, "session")
                if (createdId.isNotBlank()) {
                    val createdScope = fixture.scope.session(createdId)
                    app.cache.delete(createdScope, "client-created-shell"); app.cache.delete(createdScope, "session"); app.cache.delete(createdScope, "draft")
                }
            }
        }
    }

    @Test fun scheduleEditUsesOnlyEditableFieldsAndPreservesFailedDraft() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.tab("Tasks") }
        await { vm.data.value["crons"] != null }
        compose.onNodeWithText("Morning summary").assertExists()
        capture("tasks")
        clickLabel("Edit")
        await { vm.form.value != null }
        fixture.cronFailure = "Choose a single interval or a cron expression for this schedule."
        compose.runOnIdle { vm.formValue("name", "A revised summary"); vm.formValue("schedule", "every hour to two hours") }
        compose.onNodeWithText("Save").assertIsDisplayed().performClick()
        await { vm.submittingForm.value.isBlank() }
        assertNotNull("Validation must retain the editor", vm.form.value)
        assertEquals("A revised summary", vm.form.value!!.values["name"])
        assertTrue(vm.form.value!!.error.isNotBlank())
        capture("schedule-validation")
        fixture.cronFailure = null
        compose.runOnIdle { vm.formValue("schedule", "0 10-22/2 * * *") }
        compose.onNodeWithText("Save").assertIsDisplayed().performClick()
        await { vm.form.value == null }
        assertEquals("A revised summary", fixture.jobs.getValue("task-a").string("name"))
        val payload = fixture.requests.last { it.string("method") == "PUT" }.getJSONObject("body").getJSONObject("updates")
        assertFalse(payload.has("id")); assertFalse(payload.has("next_run_at")); assertFalse(payload.has("last_status"))
        receipts += "Production task form keeps validation draft; native update excludes immutable/runtime fields."
        capture("schedule-saved")
        clickLabel("Edit")
        await { vm.form.value != null }
        compose.runOnIdle { vm.formValue("name", "An unsaved draft"); vm.dismissForm() }
        clickLabel("Edit")
        await { vm.form.value?.values?.get("name") == "An unsaved draft" }
        compose.onNodeWithContentDescription("Discard draft").performClick()
        compose.onNodeWithText("Discard", useUnmergedTree = true).performClick()
        await { vm.form.value == null }
        assertNull(runBlocking { app.cache.read(fixture.scope, "schedule-form:/api/crons/update:task-a") })
        assertEquals("A revised summary", fixture.jobs.getValue("task-a").string("name"))
        receipts += "Closing keeps a scoped draft; explicit discard clears it without changing the saved task."
        capture("schedule-draft-discarded")
    }

    @Test fun settingsAndMenusExposeHonestState() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.tab("More"); vm.more("") }
        await { vm.data.value["navigation-profiles"]?.rows("profiles")?.isNotEmpty() == true }
        fun summary(route: String, text: String, substring: Boolean = false) =
            compose.onNode(hasText(text, substring = substring) and hasAnyAncestor(hasTestTag("settings-row:$route")), useUnmergedTree = true)
        // The overview reports only local state it actually holds; the Beacon left menu is still the way into navigation.
        compose.onNodeWithTag("settings-overview").assertIsDisplayed()
        compose.onNodeWithTag("navigation-toggle").assertIsDisplayed()
        compose.onNode(hasText("Connected · default") and hasAnyAncestor(hasTestTag("settings-connection")), useUnmergedTree = true).assertExists()
        summary("Settings", "Enter to send · Thinking shown").assertExists()
        summary("Profiles", "Active profile · default").assertExists()
        summary("Appearance", settingsThemeLabels.getValue(vm.preferences.value.theme), substring = true).assertExists()
        val voiceStore = VoiceKeyStore(app)
        val localProvider = voiceStore.settings().provider
        val localProviderLabel = when (localProvider) {
            "gemini" -> "Gemini Live"
            "grok" -> "Grok"
            "openai" -> "OpenAI Realtime"
            else -> error("Unsupported phone voice provider")
        }
        val localKeyStatus = if (voiceStore.maskedKey(localProvider) != null) "Key saved" else "Add key"
        val localVoiceSummary = "$localProviderLabel · $localKeyStatus"
        await { compose.onAllNodes(hasText(localVoiceSummary) and hasAnyAncestor(hasTestTag("settings-row:Beacon")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val keepConnectedEnabled = dev.hermes.android.push.KeepConnectedService.isEnabled(app)
        val keepConnectedSummary = if (keepConnectedEnabled) "Keep connected on" else "Keep connected off"
        val keepConnectedStatus = if (keepConnectedEnabled) "Keep connected is on" else "Keep connected is off"
        await { compose.onAllNodes(hasText(keepConnectedSummary) and hasAnyAncestor(hasTestTag("settings-row:Notifications")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        capture("settings")
        compose.runOnIdle { vm.load("voice-settings", "/api/plugins/beacon/mobile/voice-settings") }
        await { vm.data.value["voice-settings"]?.has("providers") == true }
        // Loading server voice capabilities must leave the phone-local provider and key summary unchanged.
        summary("Beacon", localVoiceSummary).assertExists()
        // A real preference write moves its summary, the row opens the page that holds the saved value, and Back returns to the overview.
        compose.runOnIdle { vm.nativePreferences(json("send_key" to "ctrl_enter", "show_thinking" to false)) }
        await { vm.data.value["profile-settings"]?.let { it.string("send_key") == "ctrl_enter" && !it.optBoolean("show_thinking", true) } == true }
        await { compose.onAllNodes(hasText("Ctrl + Enter to send · Thinking hidden") and hasAnyAncestor(hasTestTag("settings-row:Settings")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        capture("settings-summaries-updated")
        compose.onNodeWithTag("settings-row:Settings").performScrollTo().performClick()
        await { vm.ui.value.tab == "More" && vm.ui.value.morePage == "Settings" }
        compose.onNode(hasText("Send shortcut") and hasText("Ctrl + Enter")).assertExists()
        compose.onNode(hasText("Show thinking") and isToggleable()).performScrollTo().assertIsOff()
        compose.onNode(hasText("Format your messages") and isToggleable()).performScrollTo().assertIsOn()
        compose.onNode(hasText("Send shortcut") and hasText("Ctrl + Enter")).performScrollTo()
        capture("settings-detail-saved")
        compose.onNodeWithContentDescription("Back").performClick()
        await { vm.ui.value.tab == "More" && vm.ui.value.morePage.isBlank() }
        summary("Settings", "Ctrl + Enter to send · Thinking hidden").assertExists()
        compose.onNodeWithTag("navigation-toggle").performClick()
        compose.onNodeWithTag("navigation-menu").assertIsDisplayed()
        capture("settings-navigation-open")
        compose.onNodeWithTag("navigation-menu").performTouchInput { swipeLeft() }
        await { !vm.ui.value.showSessions }
        // Restore the fixture's own values, so the page captures below keep showing the defaults.
        compose.runOnIdle { vm.nativePreferences(json("send_key" to "enter", "show_thinking" to true)) }
        await { vm.data.value["profile-settings"]?.let { it.string("send_key") == "enter" && it.optBoolean("show_thinking", false) } == true }
        summary("Settings", "Enter to send · Thinking shown").assertExists()
        receipts += "Settings overview summarizes local state, including the phone-selected voice provider and key presence; loading server voice capabilities leaves that summary unchanged; native chat preference writes update their row, the row opens the saved page, Back returns to the overview, and the left navigation still opens and closes."
        compose.runOnIdle { vm.more("Settings") }
        compose.onNodeWithText("Unavailable in native Hermes").assertDoesNotExist()
        capture("chat-preferences")
        compose.runOnIdle { vm.more("Notifications") }
        assertFalse("The preview build does not configure Firebase push", app.firebaseConfigured)
        compose.onNodeWithText(keepConnectedStatus).assertIsDisplayed()
        compose.onNodeWithText("Calls and alerts arrive through Keep connected.").assertIsDisplayed()
        compose.onNodeWithText("Notifications for this profile").assertDoesNotExist()
        compose.onNodeWithText("Send a test notification").assertDoesNotExist()
        compose.onNodeWithText("Proactive calls").assertDoesNotExist()
        val notifications = app.getSystemService(android.app.NotificationManager::class.java)
        listOf("hermes-messages", "hermes-tasks", "hermes-approvals", "hermes-updates").forEach { assertNotNull("Advertised notification category exists: $it", notifications.getNotificationChannel(it)) }
        assertTrue(fixture.requests.none { it.string("path") == "/api/mobile/proactive" })
        receipts += "Local chat preferences are reachable; Keep connected state leads Notifications, Firebase-only controls stay hidden in this build, and no proactive calls are scheduled."
        capture("notification-categories")
    }

    @Test fun callControlsExposeStateWithoutStartingMedia() {
        check(args.getString("native_ui_probe") == "1")
        prepareWindow()
        var call by mutableStateOf(CallState(active = true, state = "listening", routes = listOf(AudioRoute("speaker", "Speaker")), route = "speaker"))
        var ended = false
        compose.setContent { BeaconTheme(dark = args.getString("native_ui_theme") != "light") {
            LiveCallSurface(call, CallUiActions(mute = { call = call.copy(muted = !call.muted) }, camera = {}, flip = {}, allowBubble = {}, end = { ended = true }, route = {}, screen = {}), false, Modifier.fillMaxSize(), onConversation = {}) { Box(it) }
        } }
        compose.onNodeWithTag("beacon-mute").assertIsDisplayed().performClick()
        assertTrue(call.muted)
        compose.onNodeWithTag("beacon-mute").assertContentDescriptionEquals("Unmute")
        receipts += "Actual call control toggles synthetic mute; no sensor, service or provider."
        capture("call-muted")
        compose.runOnIdle { call=call.copy(state="connecting",held=true) }
        compose.onNodeWithTag("beacon-camera").assertIsNotEnabled()
        compose.onNodeWithTag("beacon-share-screen").assertIsNotEnabled()
        compose.runOnIdle { call=call.copy(state="listening", held=false, cameraStopPending=true) }
        compose.onNodeWithTag("beacon-share-screen").assertIsNotEnabled()
        compose.onNodeWithTag("beacon-camera").assertIsEnabled()
        compose.runOnIdle { call=call.copy(cameraStopPending=false,screenStopPending=true) }
        compose.onNodeWithTag("beacon-share-screen").assertIsEnabled()
        compose.onNodeWithTag("beacon-hang-up").performClick(); assertTrue(ended)
    }
    @Test fun fileDraftNeverOverwritesNewerFileAndDiscardCannotResurrect() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.navigate("Files") }
        await { compose.onAllNodesWithText("notes.md").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("research").assertExists()
        compose.onNodeWithText("Folder").assertDoesNotExist()
        compose.onNodeWithText("28 B").assertExists()
        compose.onNodeWithText("notes.md").performClick()
        await { vm.editor.value.path == "C:/synthetic-workspace/notes.md" && !vm.editor.value.loading }
        compose.runOnIdle { vm.closeEditor() }
        await { vm.editor.value.path.isBlank() }
        fixture.fileText = "First newer file"
        compose.onNodeWithText("notes.md").performClick()
        await { !vm.editor.value.loading && vm.editor.value.text.text == fixture.fileText }
        assertFalse(vm.editor.value.dirty)
        compose.onNode(hasSetTextAction() and hasText("First newer file")).performTextReplacement("My local draft")
        compose.runOnIdle { vm.closeEditor() }
        await { vm.editor.value.path.isBlank() }
        val cached = runBlocking { app.cache.read(fixture.scope, "editor:C:/synthetic-workspace/notes.md") }!!
        assertEquals("First newer file", JSONObject(cached).getString("base"))
        fixture.fileText = "Second newer file"
        compose.onNodeWithText("notes.md").performClick()
        await { !vm.editor.value.loading && vm.editor.value.reviewRequired }
        assertEquals("My local draft", vm.editor.value.text.text)
        assertEquals("First newer file", vm.editor.value.serverText)
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Review changes").performClick()
        compose.onNodeWithText("Your draft").assertExists()
        compose.onNodeWithText("Use my draft").performClick()
        assertEquals("Second newer file", vm.editor.value.serverText)
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        await { fixture.fileWrites == 1 && !vm.editor.value.saving }
        assertEquals("My local draft", fixture.fileText)
        compose.onNode(hasSetTextAction() and hasText("My local draft")).performTextReplacement("Discard this immediate edit")
        compose.runOnIdle { vm.discardEditor() }
        await { !vm.editor.value.dirty }
        runBlocking { kotlinx.coroutines.delay(350) }
        assertNull(runBlocking { app.cache.read(fixture.scope, "editor:C:/synthetic-workspace/notes.md") })
        assertEquals("My local draft", vm.editor.value.text.text)
        assertEquals(1, fixture.fileWrites)
        receipts += "Clean files refresh; dirty drafts retain their base; explicit review precedes save; discard cancels old checkpoints."
        capture("file-draft-reviewed")
    }

    @Test fun settingsSearchUsesVisibleNamesAndDirtyNotesRecoverFromEncryptedCache() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.navigate("Settings") }
        compose.onNodeWithText("Search settings").performTextInput("Chat display")
        compose.onNodeWithTag("settings-row:Chat").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Chat display")).performTextReplacement("Tools & MCP")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("settings-row:Tools & MCP").performClick()
        await { vm.ui.value.morePage == "category:Tools & MCP" }
        capture("settings-category-search")
        compose.onNodeWithText("MCP servers",ignoreCase=true).assertIsDisplayed()
        compose.runOnIdle { vm.navigate("Memory") }
        await { vm.phoneSettings.memory.value.documents.size == 2 && !vm.phoneSettings.memory.value.loading }
        val base = vm.phoneSettings.memory.value.documents.first { it.target == "memory" }
        clickText("Edit agent notes")
        compose.onNode(hasSetTextAction() and hasText(base.content)).performTextReplacement("Retained local notes draft")
        runBlocking { kotlinx.coroutines.withTimeout(5000) {
            while (app.cache.read(fixture.scope, "phone-editors") == null) kotlinx.coroutines.delay(20)
        } }
        val savedDraft = JSONObject(runBlocking { app.cache.read(fixture.scope, "phone-editors") }!!)
        val recreatedStore = ViewModelStore()
        try {
            lateinit var recreated: BeaconViewModel
            compose.runOnUiThread { recreated=ViewModelProvider(recreatedStore,viewModelFactory { initializer { BeaconViewModel(app,SavedStateHandle()) } })[BeaconViewModel::class.java];field(recreated,"client",fixture.client) }
            runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { recreated.restorePhoneSettings(fixture.client) } }
            assertEquals("Retained local notes draft", recreated.phoneSettings.memory.value.draft)
            assertEquals(base.content, recreated.phoneSettings.memory.value.documents.single().content)
            assertEquals(base.revision, recreated.phoneSettings.memory.value.documents.single().revision)
            assertTrue(fixture.requests.none { it.string("method") != "GET" && it.string("path").contains("memory/documents") })
        } finally { compose.runOnUiThread { recreatedStore.clear() } }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        clickText("Cancel edit")
        compose.onNodeWithText("Discard changes").performClick()
        await { vm.phoneSettings.memory.value.editing.isBlank() }
        runBlocking { kotlinx.coroutines.withTimeout(5000) {
            while (app.cache.read(fixture.scope, "phone-editors") != null) kotlinx.coroutines.delay(20)
        } }
        receipts += "Visible settings/category search works; encrypted notes draft restores its original revision without writing the server; explicit discard clears it."
        capture("settings-draft-recovery")
    }

    @Test fun settingsBackOfflineRoutesAndInstallGuardHaveRealOutcomes() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.navigate("Settings") }
        androidx.test.espresso.Espresso.pressBack()
        await { vm.ui.value.tab == "Chat" }
        compose.runOnIdle { vm.navigate("Files") }
        await { compose.onAllNodesWithText("notes.md").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("notes.md").performClick()
        await { vm.editor.value.path.isNotBlank() && !vm.editor.value.loading }
        compose.onNodeWithContentDescription("Back").performClick()
        await { vm.editor.value.path.isBlank() }
        compose.onNodeWithText("notes.md").assertExists()
        compose.runOnIdle { field(vm, "client", null); flow<AppState>(vm,"_ui").value=vm.ui.value.copy(connected=false,loading=false) }
        assertTrue(app.packageName.endsWith(".debug"))
        val voiceStore = VoiceKeyStore(app)
        val provider = voiceStore.settings().provider
        val previousKey = voiceStore.key(provider)
        voiceStore.removeKey(provider)
        try {
            compose.runOnIdle { vm.more("Beacon") }
            // Without a key the page offers "Add key"; the key page opens from there.
            compose.onNodeWithTag("voice-add-key").performClick()
            compose.onNodeWithTag("voice-provider-key").performTextInput("not-a-real-provider-key")
            compose.onNodeWithText("Test key").assertIsEnabled()
            compose.runOnIdle { vm.more("App updates") }
            compose.onNodeWithText("Preview builds are installed manually.").assertIsDisplayed()
            compose.onNodeWithText("Check for updates").assertDoesNotExist()
            compose.onNodeWithText("Reconnect to Hermes").assertDoesNotExist()
        } finally { previousKey?.let { voiceStore.saveKey(provider, it) } ?: voiceStore.removeKey(provider) }
        for (page in listOf("Skills", "Memory", "Bots", "Files", "Cron", "Projects", "Kanban")) {
            compose.runOnIdle { vm.navigate(page) }
            compose.onNodeWithText("Reconnect to Hermes").assertIsDisplayed()
            compose.onAllNodesWithText("Reconnect").fetchSemanticsNodes().also { assertTrue(it.isNotEmpty()) }
        }
        compose.runOnIdle { field(vm,"client",fixture.client) }
        receipts += "Settings Back returns to chat; file Back closes the editor; seven offline destinations explain reconnect."
    }

    @Test fun readAloudStopsAndSystemDictationCancelsWithoutSending() = withFixture { vm, _ ->
        org.junit.Assume.assumeTrue(args.getString("beaconNoHostAudio")=="1")
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val audio=app.getSystemService(android.media.AudioManager::class.java)
        try {
            val reply = vm.chat.value.messages.last { it.role == "assistant" && it.text.isNotBlank() }
            compose.onNodeWithTag("message-bubble:${reply.id}").performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithTag("message-read-aloud:${reply.id}").performClick()
            await { vm.readingAloud.value && audio.activePlaybackConfigurations.isNotEmpty() }
            compose.onNodeWithTag("message-bubble:${reply.id}").performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithTag("message-read-aloud:${reply.id}").performClick()
            await { !vm.readingAloud.value && audio.activePlaybackConfigurations.isEmpty() }
            val draft=vm.composer.value.text
            compose.onNodeWithTag("composer-dictate").performClick()
            val end=android.os.SystemClock.elapsedRealtime()+10_000
            while(automation.rootInActiveWindow?.packageName?.toString()==app.packageName && android.os.SystemClock.elapsedRealtime()<end) Thread.sleep(50)
            val speechPackage=automation.rootInActiveWindow?.packageName?.toString().orEmpty()
            assertTrue("System speech UI must open",speechPackage.isNotBlank() && speechPackage!=app.packageName)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent 4")).use { it.readBytes() }
            await { automation.rootInActiveWindow?.packageName?.toString()==app.packageName }
            assertEquals(draft,vm.composer.value.text)
            await { audio.activeRecordingConfigurations.isEmpty() }
            receipts += "Read-aloud button starts real Android playback and Stop ends it. Android dictation opens, cancellation returns unchanged draft and no recorder remains. Spoken recognition quality is not tested."
            capture("dictation-cancelled")
        } finally { compose.runOnIdle { vm.stopReadAloud() } }
    }

    @Test fun structuredSourceChoiceSurvivesIncomingText() {
        var source by mutableStateOf("{\n\"answer\":1\n}")
        compose.setContent { BeaconTheme(dark=true) {
            CompositionLocalProvider(LocalBeaconSettings provides json("structured_code_default_view" to "on")) { CodeBlock("json",source) }
        } }
        await { compose.onAllNodesWithContentDescription("Source").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Source").performClick()
        compose.onNodeWithContentDescription("Tree").assertExists()
        compose.runOnIdle { source="{\n\"answer\":2,\n\"status\":\"streaming\"\n}" }
        compose.onNodeWithContentDescription("Tree").assertExists()
        compose.onNodeWithContentDescription("Source").assertDoesNotExist()
        receipts += "Manual Source mode survives changing streamed JSON; automatic Tree mode waits for valid parsing."
    }

    @Test fun callFeedbackDoesNotCoverControlsAndHangupRemainsTappable() = withFixture { vm, fixture ->
        val before=app.calls.state.value
        try {
            compose.runOnIdle { app.calls.update { CallState(active=true,state="listening",callId="synthetic-feedback",conversationScope=fixture.scope.session("chat-a")) };vm.notice("A previous action finished. This feedback must not block your call controls.") }
            await { compose.onAllNodesWithText("A previous action finished. This feedback must not block your call controls.").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val feedback=compose.onNodeWithTag("native-feedback").fetchSemanticsNode().boundsInRoot
            for(tag in listOf("beacon-camera","beacon-hang-up","beacon-share-screen")) {
                val control=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue("Feedback overlaps $tag",control.bottom<=feedback.top || control.top>=feedback.bottom)
            }
            capture("call-feedback-clear-controls")
            compose.runOnIdle {
                vm.tab("More"); vm.more("Bots")
                @Suppress("UNCHECKED_CAST")
                val botState = dev.hermes.android.data.NativeBotModeController::class.java.getDeclaredField("mutable").apply { isAccessible=true }.get(vm.botMode) as MutableStateFlow<dev.hermes.android.data.NativeBotModeState>
                botState.value = dev.hermes.android.data.NativeBotModeState(activeBot=dev.hermes.android.data.BotModeBot("default","Beacon",""),sessionId="chat-a")
            }
            compose.onNodeWithContentDescription("Show navigation").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back to bots").assertDoesNotExist()
            compose.onNodeWithText("Open call").assertDoesNotExist()
            compose.onAllNodesWithTag("beacon-hang-up").assertCountEquals(1)
            compose.onNodeWithTag("beacon-hang-up").performClick()
            await { !app.calls.state.value.active }
            receipts += "Feedback reserves actual call space; camera, sharing and hangup targets are unobscured, and tapping Hang up reaches the controller."
        } finally { compose.runOnIdle { app.calls.update { before };vm.clearMessage() } }
    }

    @Test fun disconnectedSettingsAndProjectPickerExplainHowToRecover() = withFixture { vm, _ ->
        compose.runOnIdle { field(vm, "client", null); vm.more("Memory") }
        compose.onNodeWithText("Reconnect to Hermes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show navigation").assertIsDisplayed()
        compose.onNodeWithText("Reconnect").assertIsEnabled()
        compose.runOnIdle { vm.more("Connection") }
        assertEquals("Connection", vm.ui.value.morePage)
        compose.runOnIdle { vm.loadNativeProjects(); vm.load("navigation-profiles", "/api/profiles") }
        await { vm.data.value["native-projects"]?.has("_error") == true && vm.data.value["navigation-profiles"]?.has("_error") == true }
        assertFalse(vm.data.value.getValue("native-projects").optBoolean("_loading"))
        receipts += "Disconnected settings and project/profile reads expose recovery instead of an endless loading indicator."
    }

    @Test fun settingsReturnToEntryAndChatMenuHasOnePlaceForEachAction() = withFixture { vm, _ ->
        compose.runOnIdle { vm.composer(TextFieldValue("Keep this draft")) }
        compose.onNodeWithContentDescription("Conversation actions").performClick()
        compose.onNodeWithText("Profiles", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Spaces", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Attachments, queue & status", useUnmergedTree = true).assertDoesNotExist()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("conversation-project-remove").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("conversation-project-move").assertExists()
        compose.onNodeWithText("Chat preferences", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        await { vm.ui.value.tab == "Chat" }
        assertEquals("Keep this draft", vm.composer.value.text)
        assertEquals("chat-a", vm.chat.value.sessionId)
        compose.runOnIdle { vm.navigate("Files"); vm.navigate("Settings"); vm.more("Appearance") }
        compose.onNodeWithContentDescription("Back").performClick()
        await { vm.ui.value.tab == "More" && vm.ui.value.morePage.isBlank() }
        androidx.test.espresso.Espresso.pressBack()
        await { vm.ui.value.tab == "Spaces" }
        compose.runOnIdle { vm.more("Connection") }
        androidx.test.espresso.Espresso.pressBack()
        await { vm.ui.value.tab == "Spaces" }
        receipts += "Chat preferences returns to the original chat/draft; nested Settings returns to its origin; redundant profile/attachment/space menu entries are absent."
        capture("settings-origin-return")
    }

    @Test fun sharedSpaceNameEditUsesExistingRoomAndRetainsDraft() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.navigate("Bots") }
        await { vm.ui.value.botModeEnabled && vm.botMode.state.value.spaces.isNotEmpty() }
        compose.runOnIdle { vm.openBotSpace("fixture-room") }
        await { vm.botMode.state.value.selectedSpace == "fixture-room" && !vm.botMode.state.value.loading }
        compose.runOnIdle { vm.botMode.draft("Unsent team note") }
        compose.onNodeWithContentDescription("Shared-space actions").performClick()
        compose.onNodeWithText("Edit shared space").performClick()
        compose.onNodeWithTag("shared-space-name").performTextReplacement("Research group")
        compose.onNodeWithText("Save").performClick()
        await { fixture.roomName == "Research group" && !vm.botMode.state.value.loading }
        assertEquals("Unsent team note", vm.botMode.state.value.draft)
        assertEquals("fixture-room", vm.botMode.state.value.selectedSpace)
        assertEquals(1, fixture.requests.count { it.string("path").endsWith("/space/fixture-room/rename") })
        assertFalse(fixture.requests.any { it.string("method") == "POST" && it.string("path").endsWith("/bot-mode/space") })
        receipts += "Shared-space rename updates the existing room and sidebar title, with original draft and history identity retained."
        capture("shared-space-renamed")
    }

    @Test fun relayChatExtrasQuoteCommandsAndConversationId() = withFixture { vm, fixture ->
        val message = vm.chat.value.messages.last { it.role == "assistant" && it.text.isNotBlank() }
        compose.onNodeWithTag("message-bubble:${message.id}").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithTag("message-quote:${message.id}", useUnmergedTree = true).performClick()
        await { vm.composer.value.text.startsWith("> " + dev.hermes.android.ui.quoteExcerpt(message.text).take(12)) && vm.composer.value.text.endsWith("\n\n") }

        compose.runOnIdle { vm.composer(TextFieldValue("")) }
        compose.onNodeWithContentDescription("Conversation actions").performClick()
        compose.onNodeWithTag("conversation-more").performScrollTo().performClick()
        compose.onNodeWithText("Copy conversation ID").performScrollTo().performClick()
        compose.runOnIdle {
            val clip = (app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            assertEquals("chat-a", clip?.getItemAt(0)?.text?.toString())
        }

        compose.onNodeWithTag("composer-attach").performClick()
        compose.onNodeWithTag("attach-camera").assertExists()
        // R6C OWNER-5 removed these duplicate entries; paste uses the field menu and commands use '/'.
        compose.onNodeWithTag("attach-paste-image").assertDoesNotExist()
        compose.onNodeWithTag("attach-commands").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("message-input").performClick().performTextInput("/")
        await { vm.composer.value.text == "/" }
        compose.onNodeWithTag("slash-suggestions").assertIsDisplayed()
        receipts += "Quote in reply, Copy conversation ID and Camera remain reachable; commands use '/' and image paste uses the field menu."
    }

    @Test fun bubblesSitOnTheirSideGroupedWithATimeAndTickAndNoNameLabels() = withFixture { vm, _ ->
        val now = System.currentTimeMillis()
        val chatId = vm.chat.value.sessionId
        val messages = listOf(
            Message("tg-old", "user", "From two days ago", sessionId = chatId, recordedAt = now - 2 * 24 * 60 * 60 * 1000L),
            Message("tg-u1", "user", "Short question", sessionId = chatId, recordedAt = now),
            Message("tg-a1", "assistant", "First part.", sessionId = chatId, recordedAt = now),
            Message("tg-a2", "assistant", "Second part.", sessionId = chatId, recordedAt = now),
            Message("tg-long", "assistant", "A **long** answer " + "with many words ".repeat(60), sessionId = chatId, recordedAt = now),
            Message("tg-post", "user", "[Cron delivery: Cyber news]\nTop story: patch Tuesday.", sessionId = chatId, recordedAt = now, displayKind = "cron_delivery"),
        )
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = messages) }
        val transcript = compose.onNodeWithTag("conversation-transcript")
        await { compose.onAllNodesWithTag("message-bubble:tg-post").fetchSemanticsNodes().isNotEmpty() }
        fun bounds(id: String) = compose.onNodeWithTag("message-bubble:$id").getUnclippedBoundsInRoot()
        val layer = compose.onNodeWithTag("transcript-layer").getUnclippedBoundsInRoot()

        // The transcript follows the newest message until the owner drags it, so reveal older bubbles with a real swipe.
        repeat(8) {
            if (compose.onAllNodesWithTag("message-bubble:tg-a2").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithTag("message-bubble:tg-u1").fetchSemanticsNodes().isNotEmpty()) return@repeat
            transcript.performTouchInput { swipeDown() }
            compose.waitForIdle()
        }
        val own = bounds("tg-u1"); val first = bounds("tg-a1"); val second = bounds("tg-a2")
        assertTrue("Own messages sit on the right", layer.right - own.right < 24.dp)
        assertTrue("The assistant's sit on the left", first.left - layer.left < 24.dp)
        assertEquals("One sender's bubbles are tight", 3f, (second.top - first.bottom).value, 1f)
        assertEquals("Groups sit further apart", 10f, (first.top - own.bottom).value, 1f)
        val limit = minOf((layer.right - layer.left).value * .8f, 560f)
        assertTrue("A short plain message shrinks to its words", (own.right - own.left).value < limit / 2)
        // No name above a message; the time sits inside the bubble and only the owner's own message carries the sent tick.
        compose.onAllNodes(hasText("You") and hasAnyAncestor(hasTestTag("conversation-transcript"))).assertCountEquals(0)
        compose.onAllNodes(hasText(dev.hermes.android.data.currentAssistantName) and hasAnyAncestor(hasTestTag("conversation-transcript"))).assertCountEquals(0)
        compose.onNodeWithTag("message-time:tg-u1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasTestTag("send-tick:sent") and hasAnyAncestor(hasTestTag("message-row:tg-u1")), useUnmergedTree = true).assertExists()
        compose.onAllNodes(hasTestTag("send-tick:sent") and hasAnyAncestor(hasTestTag("message-row:tg-a1")), useUnmergedTree = true).assertCountEquals(0)
        // TalkBack gets each action by name.
        compose.onNodeWithTag("message-bubble:tg-a1").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.CustomActions))
        // The day between messages is a centred chip.
        compose.onNode(hasText("Today") and hasAnyAncestor(hasTestTag("date-chip"))).assertExists()
        assertTrue(compose.onAllNodes(hasTestTag("date-chip")).fetchSemanticsNodes().isNotEmpty())

        transcript.performScrollToNode(hasTestTag("message-bubble:tg-long"))
        val long = bounds("tg-long")
        assertTrue("A wide bubble stops near 80% of the width, never past 560dp", (long.right - long.left).value <= limit + 1f)
        // A scheduled result filed in the thread is a post: on the assistant's side, headed by its job, without Hermes' label or a tick.
        transcript.performScrollToNode(hasTestTag("message-bubble:tg-post"))
        compose.onNode(hasText("Cyber news") and hasAnyAncestor(hasTestTag("channel-post:tg-post"))).assertExists()
        compose.onNode(inConversation(hasText("Top story: patch Tuesday.", substring = true))).assertExists()
        compose.onAllNodesWithText("[Cron delivery", substring = true).assertCountEquals(0)
        assertTrue("A post sits on the assistant's side", bounds("tg-post").left - layer.left < 24.dp)
        compose.onAllNodes(hasTestTag("send-tick:sent") and hasAnyAncestor(hasTestTag("message-row:tg-post")), useUnmergedTree = true).assertCountEquals(0)
        receipts += "Bubbles: own on the right, the assistant's on the left, grouped 3dp apart and groups 10dp apart; no name labels, the time and sent tick inside the bubble, date chips between days, width capped at 80% or 560dp."
        capture("telegram-bubbles")
    }

    @Test fun theSlashButtonOpensTheCommandListFromAnEmptyComposer() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.composer(TextFieldValue("")) }
        compose.onNodeWithTag("composer-commands").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        await { vm.composer.value.text == "/" }
        await { vm.data.value["slash:catalog"] != null }
        compose.onNodeWithTag("slash-suggestions").assertIsDisplayed()
        compose.onNodeWithText("/help", substring = true).assertExists()
        // The button gives way to the text; the list filters as the command is typed and stays above the field.
        compose.onNodeWithTag("composer-commands").assertDoesNotExist()
        compose.onNodeWithTag("message-input").performTextInput("he")
        await { vm.composer.value.text == "/he" }
        compose.onNodeWithText("/help", substring = true).assertExists()
        val suggestions = compose.onNodeWithTag("slash-suggestions").getUnclippedBoundsInRoot()
        val input = compose.onNodeWithTag("message-input").getUnclippedBoundsInRoot()
        assertTrue("The list sits above the field", suggestions.bottom <= input.top)
        assertEquals("Opening the list creates nothing", 0, fixture.requests.count { it.string("method") == "session.create" })
        compose.runOnIdle { vm.composer(TextFieldValue("")) }
        compose.onNodeWithTag("composer-commands").assertIsDisplayed()
        receipts += "The slash button starts the command list from an empty composer; the list filters as you type and the button returns when the draft is empty."
        capture("telegram-slash-button")
    }

    @Test fun theHeaderNamesWhatTheAssistantIsDoingAndItsTitleOpensTheChatMenu() = withFixture { vm, _ ->
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(streamId = "runtime-chat-a", liveText = "") }
        compose.onNodeWithTag("chat-header-subtitle", useUnmergedTree = true).assertTextEquals("thinking…")
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(streamId = "runtime-chat-a", liveText = "Working on it") }
        compose.onNodeWithTag("chat-header-subtitle", useUnmergedTree = true).assertTextEquals("typing…")
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(streamId = "", liveText = "") }
        compose.onNodeWithTag("chat-header-title").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("conversation-actions-menu").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        receipts += "The header shows an avatar, thinking or typing while a turn streams, and its title opens the chat menu."
    }

    @Test fun aNewThreadAsksForANameWithAnOptionalEmojiStoredAsTheHermesTitle() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.toggleSessions(true) }
        compose.onNodeWithTag("navigation-new-thread").performScrollTo().performClick()
        val emoji = threadEmojis.first()
        compose.onNodeWithTag("new-thread-emoji:$emoji").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("new-thread-name").performTextInput("Cyber news")
        compose.onNodeWithTag("new-thread-start").performClick()
        await { fixture.requests.any { it.string("method") == "session.create" && it.getJSONObject("params").string("source") == "android_thread" } }
        assertEquals("The emoji is only the start of the Hermes title", "$emoji Cyber news", fixture.requests.last { it.string("method") == "session.create" }.getJSONObject("params").string("title"))
        await { vm.chat.value.sessionId.isNotBlank() && vm.chat.value.title == "$emoji Cyber news" }
        // Saved in Hermes at once, so a scheduled post can land before the first message (live 26 Oct: an empty thread refused posts).
        val threadId = vm.chat.value.sessionId
        await { fixture.requests.any { it.string("path") == "/api/plugins/beacon/mobile/sessions/$threadId/reserve" } }
        // The emoji is the avatar, so the header text is the name alone.
        compose.onNode(hasText("Cyber news") and hasAnyAncestor(hasTestTag("chat-header-title")), useUnmergedTree = true).assertExists()
        receipts += "New thread takes a name and an optional emoji; both become the Hermes title and the thread's avatar and header show them."
    }

    @Test fun theBeaconChatLeadsTheListInTheSameRowStyleAsTheOthers() = withFixture { vm, _ ->
        compose.runOnIdle { vm.toggleSessions(true) }
        await { vm.ui.value.sessions.any { sessionRowId(it) == "chat-b" } && !vm.ui.value.historyLoading }
        compose.onNodeWithTag("history-view-all").performScrollTo()
        val filters = compose.onNodeWithTag("history-view-all").getUnclippedBoundsInRoot()
        val beacon = compose.onNodeWithTag("history-beacon-row").getUnclippedBoundsInRoot()
        val firstChat = compose.onNodeWithTag("history-row:chat-a").getUnclippedBoundsInRoot()
        assertTrue("The Beacon row is the first of the list", filters.top < beacon.top && beacon.top < firstChat.top)
        assertEquals("Same row height", (firstChat.bottom - firstChat.top).value, (beacon.bottom - beacon.top).value, 1f)
        // Telegram rows: a title, then one line with what Hermes knows of the chat.
        compose.onNodeWithTag("history-row:chat-b").assert(SemanticsMatcher("a title and a preview line") { (it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.size ?: 0) >= 2 })
        compose.onNodeWithTag("history-beacon-row").performClick()
        await { vm.ui.value.tab == "Beacon" }
        receipts += "The Beacon chat is the first row of the history list in the same style as every chat row, and opens the Beacon tab."
    }

    @Test fun aNewChatOffersStarterPromptsThatFillTheComposer() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.newChat() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("starter-prompts").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Plan my day").performClick()
        await { vm.composer.value.text == "Plan my day" }
        receipts += "A new chat offers starter prompts; a tap fills the composer for editing."
    }

    @Test fun historyRowsHaveALongPressMenuForAnyConversation() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.toggleSessions(true) }
        await { vm.ui.value.sessions.any { sessionRowId(it) == "chat-b" } }
        fun menu() {
            compose.onNodeWithTag("history-row:chat-b").performScrollTo().performTouchInput { longClick() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("history-row-menu").fetchSemanticsNodes().isNotEmpty() }
        }
        menu()
        // Project membership loads for the long-pressed chat, not only the open one (the fixture files it under Travel).
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("conversation-project-remove").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("conversation-project-remove").assertTextContains("Remove from Travel")
        compose.onNodeWithTag("conversation-pin").performClick()
        await { fixture.sessions.getValue("chat-b").getJSONObject("info").optBoolean("pinned") }
        assertTrue("Pinning uses the gateway-safe plugin route", fixture.requests.any { it.string("method") == "POST" && it.string("path") == "/api/plugins/beacon/sessions/pin" && it.getJSONObject("body").string("session_id") == "chat-b" })
        assertTrue(fixture.requests.none { it.string("method") == "PATCH" && it.string("path") == "/api/sessions/chat-b" })
        await { vm.ui.value.sessions.single { sessionRowId(it) == "chat-b" }.optBoolean("pinned") }

        menu()
        compose.onNodeWithText("Unpin").assertExists()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag("conversation-rename-field").performTextReplacement("Renamed from history")
        fixture.holdNextSessionList.set(true)
        try {
            compose.onNodeWithText("Save").performClick()
            assertTrue(fixture.sessionListCaptured.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await { vm.ui.value.sessions.single { sessionRowId(it) == "chat-b" }.string("title") == "Renamed from history" }
            compose.onNodeWithTag("history-row:chat-b").assertTextContains("Renamed from history")
        } finally { fixture.releaseSessionList.countDown() }
        await { fixture.sessions.getValue("chat-b").getJSONObject("info").string("title") == "Renamed from history" }
        assertEquals("Acting on another conversation leaves the open chat in place", "chat-a", vm.chat.value.sessionId)

        menu()
        compose.onNodeWithTag("conversation-delete").performClick()
        compose.onNodeWithTag("conversation-delete-confirm").performClick()
        await { !fixture.sessions.containsKey("chat-b") }
        assertEquals("chat-a", vm.chat.value.sessionId)
        receipts += "Every history row has a long-press menu: pin, rename, project, archive and delete act on that conversation without opening it."
    }

    @Test fun theChatMenuRemovesTheChatFromItsProjectAndAddsItToAnother() = withFixture { vm, fixture ->
        fixture.projects["p_research"] = json("id" to "p_research", "name" to "Research", "archived" to false, "primary_path" to "C:/synthetic-workspace/research",
            "folders" to org.json.JSONArray().put(json("path" to "C:/synthetic-workspace/research", "label" to "research", "is_primary" to true)))
        fun menuShows(tag: String) {
            compose.onNodeWithContentDescription("Conversation actions").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        }
        fun headerShows(project: String) = compose.onAllNodes(hasTestTag("chat-header-project") and hasText(project, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        // The fixture files chat-a under Travel by its folder, and the header says so.
        await { headerShows("Travel") }
        menuShows("conversation-project-remove")
        compose.onNodeWithTag("conversation-project-open").assertTextContains("Open Travel")
        compose.onNodeWithTag("conversation-project-remove").performScrollTo().assertTextContains("Remove from Travel").performClick()
        await { fixture.sessions.getValue("chat-a").getJSONObject("info").string("cwd") == "C:/synthetic-home" }
        await { vm.data.value["project-of:chat-a"]?.let { it.has("project") && it.optJSONObject("project") == null } == true }
        // The header follows without a refresh: a chat in no project shows nothing.
        await { compose.onAllNodesWithTag("chat-header-project", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

        menuShows("conversation-project-add")
        compose.onNodeWithTag("conversation-project-add").performScrollTo().performClick()
        compose.onNodeWithText("Add to project").assertExists()
        // A project with one folder takes the chat at once.
        compose.onNodeWithText("Research", useUnmergedTree = true).performClick()
        await { fixture.sessions.getValue("chat-a").getJSONObject("info").string("cwd") == "C:/synthetic-workspace/research" }
        await { vm.data.value["project-of:chat-a"]?.optJSONObject("project")?.string("id") == "p_research" }
        await { headerShows("Research") }
        menuShows("conversation-project-remove")
        compose.onNodeWithTag("conversation-project-remove").assertTextContains("Remove from Research")
        receipts += "The chat menu removes a chat from its project and adds it to another, from Hermes's own filing, and the chat header follows each change."
    }

    @Test fun aChatReadsItsProjectAgainAfterItsFirstSendButNotOnEveryOpen() = withFixture { vm, fixture ->
        // Every fixture chat shares one folder, so a chat's own reads are told apart by its answer's load time.
        fun loadedAt() = vm.data.value["project-of:chat-a"]?.optLong("_cached_at") ?: 0L
        await { vm.data.value["project-of:chat-a"]?.has("project") == true && loadedAt() > 0 }
        compose.waitForIdle()
        val first = loadedAt()
        // Opening a saved chat again uses the answer it has.
        compose.runOnIdle { vm.selectSession("chat-b") }
        await { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        compose.runOnIdle { vm.selectSession("chat-a") }
        await { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
        Thread.sleep(1_000); compose.waitForIdle()
        assertEquals("A saved chat is not read again on every open", first, loadedAt())
        // A new chat's answer is read again once its first message is in.
        compose.runOnIdle { vm.newChat() }
        await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
        val fresh = vm.chat.value.sessionId
        compose.waitForIdle()
        val beforeSend = fixture.requests.count { it.string("method") == "projects.for_cwd" }
        compose.runOnIdle { fixture.persistTerminalMessages = true; vm.composer(TextFieldValue("Hello")); vm.send() }
        await { vm.chat.value.sessionId == fresh && vm.chat.value.messages.isNotEmpty() }
        await { fixture.requests.count { it.string("method") == "projects.for_cwd" } == beforeSend + 1 }
        Thread.sleep(500); compose.waitForIdle()
        assertEquals("One read after the first send, not one per update", beforeSend + 1, fixture.requests.count { it.string("method") == "projects.for_cwd" })
        receipts += "A saved chat keeps its project answer when reopened; a new chat reads its project once after its first message."
    }

    @Test fun aProjectThatCouldNotBeReadOffersAnotherTry() = withFixture { vm, fixture ->
        await { vm.data.value["project-of:chat-a"]?.has("project") == true }
        // A failed refresh keeps a known answer on screen (memory and the phone's cache); the retry is for a chat whose project was never read.
        compose.runOnIdle { vm.screens.invalidate(fixture.scope, "/api/projects/for-session"); vm.storeData("project-of:chat-a", JSONObject()) }
        fixture.projectLookupFailure = true
        compose.runOnIdle { vm.loadConversationProject("chat-a") }
        await { vm.data.value["project-of:chat-a"]?.has("_error") == true }
        compose.onNodeWithContentDescription("Conversation actions").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("conversation-project-retry").fetchSemanticsNodes().isNotEmpty() }
        fixture.projectLookupFailure = false
        compose.onNodeWithTag("conversation-project-retry").performScrollTo().performClick()
        // The answer comes back, so the item becomes the chat's own project actions.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("conversation-project-remove").fetchSemanticsNodes().isNotEmpty() }
        receipts += "A project read that failed offers Try again in the chat menu, and a retry brings the project actions back."
    }

    @Test fun theChatHeaderShowsItsProjectAndTheMenuOpensThatProject() = withFixture { vm, _ ->
        // The fixture files chat-a under Travel by its folder.
        await { compose.onAllNodes(hasTestTag("chat-header-project") and hasText("Travel", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // Thinking and typing take the line over from the project.
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(streamId = "runtime-chat-a", liveText = "") }
        compose.onNodeWithTag("chat-header-project", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("chat-header-subtitle", useUnmergedTree = true).assertTextEquals("thinking…")
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(streamId = "", liveText = "") }
        await { compose.onAllNodesWithTag("chat-header-project", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Conversation actions").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("conversation-project-open").fetchSemanticsNodes().isNotEmpty() }
        // The chat menu is height-capped, so the item is scrolled into view before the tap.
        compose.onNodeWithTag("conversation-project-open").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        await { vm.ui.value.tab == "Projects" }
        // The project's own page opens, not the list of projects.
        await { vm.data.value["native-project:p_project_a"]?.optJSONObject("project") != null }
        compose.onNodeWithText("Linked folders").assertExists()
        receipts += "The chat header names the chat's project beside the model, Thinking or Typing takes the line over, and the menu's Open <project> opens the Projects tab on that project."
    }

    @Test fun aProjectWithoutAFolderOffersLinkingOneAndThenTakesTheChat() = withFixture { vm, fixture ->
        fixture.projects["p_fresh"] = json("id" to "p_fresh", "name" to "Fresh start", "archived" to false, "folders" to org.json.JSONArray())
        compose.runOnIdle { vm.loadNativeProjects(force = true) }
        await { vm.data.value["native-projects"]?.rows("projects")?.any { it.string("id") == "p_fresh" } == true }
        compose.onNodeWithContentDescription("Conversation actions").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("conversation-project-move").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("conversation-project-move").performScrollTo().performClick()
        compose.onNodeWithText("Move to project").assertExists()
        compose.onNodeWithText("No folder yet").assertExists()
        compose.onNodeWithText("Fresh start", useUnmergedTree = true).performClick()
        // Nothing to choose from, so the sheet offers to link a folder here instead of ending.
        compose.onNodeWithTag("project-link-folder").assertHeightIsAtLeast(48.dp).performClick()
        await { compose.onAllNodesWithText("research", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("research", useUnmergedTree = true).performClick()
        await { vm.data.value["project-folder"]?.string("path") == "C:/synthetic-workspace/research" }
        compose.onNodeWithText("Use this folder").performClick()
        await { fixture.sessions.getValue("chat-a").getJSONObject("info").string("cwd") == "C:/synthetic-workspace/research" }
        assertEquals("The folder is linked to the project", 1, fixture.projects.getValue("p_fresh").rows("folders").size)
        await { vm.data.value["project-of:chat-a"]?.optJSONObject("project")?.string("id") == "p_fresh" }
        await { compose.onAllNodes(hasTestTag("chat-header-project") and hasText("Fresh start", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        receipts += "A project made on the phone has no folder: the chat sheet links a PC folder right there, then moves the chat into it and the header shows the project."
    }

    @Test fun theHistoryMenuAddsAChatToAProjectAndRemovesItAgain() = withFixture { vm, fixture ->
        fixture.projects["p_research"] = json("id" to "p_research", "name" to "Research", "archived" to false, "primary_path" to "C:/synthetic-workspace/research",
            "folders" to org.json.JSONArray().put(json("path" to "C:/synthetic-workspace/research", "label" to "research", "is_primary" to true)))
        compose.runOnIdle { vm.loadNativeProjects(force = true); vm.toggleSessions(true) }
        await { vm.data.value["native-projects"]?.rows("projects")?.any { it.string("id") == "p_research" } == true }
        await { vm.ui.value.sessions.any { sessionRowId(it) == "chat-b" } }
        fun menuShows(tag: String) {
            compose.onNodeWithTag("history-row:chat-b").performScrollTo().performTouchInput { longClick() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        }
        // The fixture files chat-b under Travel by its folder.
        menuShows("conversation-project-remove")
        compose.onNodeWithTag("conversation-project-remove").assertTextContains("Remove from Travel").performClick()
        await { fixture.sessions.getValue("chat-b").getJSONObject("info").string("cwd") == "C:/synthetic-home" }
        await { vm.data.value["project-of:chat-b"]?.let { it.has("project") && it.optJSONObject("project") == null } == true }
        assertEquals("Acting on another conversation leaves the open chat in place", "chat-a", vm.chat.value.sessionId)

        menuShows("conversation-project-add")
        compose.onNodeWithTag("conversation-project-add").performClick()
        compose.onNodeWithText("Add to project").assertExists()
        compose.onNodeWithText("Research", useUnmergedTree = true).performClick()
        await { fixture.sessions.getValue("chat-b").getJSONObject("info").string("cwd") == "C:/synthetic-workspace/research" }
        await { vm.data.value["project-of:chat-b"]?.optJSONObject("project")?.string("id") == "p_research" }
        assertEquals("chat-a", vm.chat.value.sessionId)
        receipts += "The history long-press menu adds a chat to a project and removes it again without opening the chat."
    }

    @Test fun nativeProjectManagementAssignsFolderWithoutDeletingChatsOrFiles() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.navigate("Projects") }
        await { vm.data.value["native-projects"]?.rows("projects")?.isNotEmpty() == true }
        compose.onNodeWithContentDescription("New project").performClick()
        compose.onNode(hasSetTextAction() and hasText("Name *")).performTextInput("Research")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        await { fixture.projects.values.any { it.string("name") == "Research" } }
        val project = fixture.projects.values.single { it.string("name") == "Research" }
        val id = project.getString("id")
        await { vm.data.value["native-project:$id"] != null }
        compose.onNodeWithText("Link computer folder").performScrollTo().performClick()
        await { compose.onAllNodesWithText("research", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Show hidden folders").assertIsDisplayed()
        compose.onNodeWithText(".cache", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Show hidden folders").performClick()
        compose.onNodeWithText(".cache", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide hidden folders").performClick()
        compose.onNodeWithTag("pc-folder-address").performClick()
        compose.onNodeWithTag("pc-folder-address").assertIsFocused()
        var sheetDecor: android.view.View? = null
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check { view, missing ->
                if (missing != null) throw missing
                sheetDecor = view
            }
        await { androidx.core.view.ViewCompat.getRootWindowInsets(requireNotNull(sheetDecor))?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
        fun keyboardTop(): Int {
            val decor = requireNotNull(sheetDecor)
            val bottom = androidx.core.view.ViewCompat.getRootWindowInsets(decor)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
            return decor.height - bottom
        }
        await {
            val buttons = compose.onAllNodesWithText("Use this folder").fetchSemanticsNodes()
            buttons.isNotEmpty() && buttons.first().boundsInWindow.bottom <= keyboardTop() + 2
        }
        compose.onNodeWithText("Use this folder").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("research", useUnmergedTree = true).performClick()
        await { vm.data.value["project-folder"]?.string("path") == "C:/synthetic-workspace/research" }
        compose.onNodeWithText("Use this folder").performClick()
        await { project.rows("folders").size == 1 }
        compose.runOnIdle { vm.tab("Chat"); vm.composer(TextFieldValue("Retain during project move")) }
        compose.onNodeWithContentDescription("Conversation actions").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("conversation-project-move").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("conversation-project-move").performScrollTo().performClick()
        compose.onNodeWithText("Move to project").assertExists()
        compose.onNodeWithText("Research", useUnmergedTree = true).performClick()
        await { vm.chat.value.raw.string("workspace") == "C:/synthetic-workspace/research" }
        assertEquals("Retain during project move", vm.composer.value.text)
        assertEquals("chat-a", vm.chat.value.sessionId)
        await { compose.onAllNodes(hasTestTag("chat-header-project") and hasText("Research", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { vm.navigate("Projects") }
        await { vm.data.value["native-projects"]?.rows("projects")?.any { it.string("id") == id } == true }
        compose.onNodeWithText("Research", useUnmergedTree = true).performClick()
        await { compose.onAllNodesWithContentDescription("Project actions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Project actions").performClick()
        compose.onNodeWithText("Delete project", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Delete project", useUnmergedTree = true).performClick()
        await { !fixture.projects.containsKey(id) }
        assertTrue("Conversations remain", fixture.sessions.keys.containsAll(listOf("chat-a", "chat-b")))
        assertEquals("# Travel notes\nCheck the weather.", fixture.fileText)
        receipts += "Native create/link/assign/delete preserves conversation identity, draft and PC file content."
        capture("native-projects-managed")
    }

    @Test fun projectEditUpdatesItsHeaderBeforeRefreshAndAsksBeforeDiscarding() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.navigate("Projects") }
        await { vm.data.value["native-projects"]?.rows("projects")?.isNotEmpty() == true }
        compose.onNodeWithText("Travel", useUnmergedTree = true).performClick()
        await { vm.data.value["native-project:p_project_a"]?.optJSONObject("project") != null }
        compose.onNodeWithContentDescription("Project actions").performClick()
        compose.onNodeWithText("Edit project", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("project-edit-name").performTextReplacement("Trip plans")

        fixture.holdNextProjectDetail = true
        compose.onNodeWithTag("project-edit-save").performClick()
        val detailHeld = fixture.projectDetailCaptured.await(5, java.util.concurrent.TimeUnit.SECONDS)
        try {
            assertTrue("Project detail refresh should be held after Save", detailHeld)
            compose.onNodeWithText("Trip plans", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            fixture.releaseProjectDetail.countDown()
        }
        await { vm.data.value["native-project:p_project_a"]?.optJSONObject("project")?.string("name") == "Trip plans" }

        compose.onNodeWithContentDescription("Project actions").performClick()
        compose.onNodeWithText("Edit project", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("project-edit-description").performTextInput("Unsaved description")
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        if (compose.onAllNodesWithText("Discard changes?").fetchSemanticsNodes().isEmpty()) {
            androidx.test.espresso.Espresso.pressBack()
        }
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithTag("project-edit-name").assertDoesNotExist()
        assertEquals("Trip plans", fixture.projects.getValue("p_project_a").string("name"))
    }

    @Test fun pcFilesOpenWithoutAnyConversationAndKeepAccountScope() = withFixture { vm, fixture ->
        compose.runOnIdle {
            flow<ChatState>(vm, "_chat").value = ChatState()
            field(vm, "sourceScope", null)
            vm.navigate("Files")
        }
        await { compose.onAllNodesWithText("notes.md").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("notes.md").performClick()
        await { !vm.editor.value.loading && vm.editor.value.path.endsWith("notes.md") }
        assertEquals(fixture.scope, vm.editor.value.scope)
        assertEquals(fixture.fileText, vm.editor.value.text.text)
        assertFalse(fixture.requests.any { it.string("method") == "session.create" })
        receipts += "PC file browsing and reading work with no selected or newly created conversation."
        capture("pc-files-without-chat")
    }

    @Test fun chatFileReferencesOpenInPcBrowserWithPinnedAbsolutePath() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.tab("Spaces"); vm.openFile("notes.md") }
        await { vm.editor.value.path == "C:/synthetic-workspace/notes.md" && !vm.editor.value.loading }
        compose.onNode(hasSetTextAction() and hasText(fixture.fileText)).assertIsDisplayed()
        assertEquals(fixture.scope.session("chat-a"), vm.editor.value.scope)
        fixture.sessions.getValue("chat-a").getJSONObject("info").put("cwd", "C:/another-folder")
        assertEquals("C:/synthetic-workspace/notes.md", vm.editor.value.path)
        compose.onNodeWithContentDescription("Back").performClick()
        await { vm.editor.value.path.isBlank() }
        receipts += "Chat file references retain their absolute PC path and original scope when the chat working folder changes."
        capture("chat-file-reference")
    }

    @Test fun projectCreationRetryUsesTheSameNativeRecord() = withFixture { _, fixture ->
        val request = json("request_id" to java.util.UUID.randomUUID().toString(), "name" to "Retry-safe project", "description" to "Retain one intent")
        runBlocking {
            val first = fixture.client.post("/api/projects/create", request).getJSONObject("project")
            val again = fixture.client.post("/api/projects/create", request).getJSONObject("project")
            assertEquals(first.getString("id"), again.getString("id"))
            assertEquals(1, fixture.projects.values.count { it.string("name") == "Retry-safe project" })
            assertTrue(runCatching { fixture.client.post("/api/projects/create", request.copy().put("name", "Changed after uncertain save")) }.isFailure)
            assertEquals(2, fixture.projects.size)
        }
        receipts += "A repeated creation intent returns its native project; changing a possibly committed intent fails without creating another record."
    }

    @Test fun unfinishedChecklistsStayStableAndFinalChecksSurviveLeavingTheMessage() = withFixture { vm, fixture ->
        val id = "audit-checklist-" + java.util.UUID.randomUUID()
        val scope = fixture.scope.session("chat-a")
        val original = vm.chat.value
        val text = "- [ ] Review the audit checklist"
        try {
            compose.runOnIdle { flow<ChatState>(vm, "_chat").value = original.copy(messages = emptyList(), streamId = "runtime-chat-a", liveText = text) }
            await { compose.onAllNodesWithContentDescription("Review the audit checklist").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Review the audit checklist").assertIsNotEnabled()
            compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(liveText = text + "\n- [ ] Another task") }
            compose.onNodeWithContentDescription("Review the audit checklist").assertIsNotEnabled()
            val finished = original.copy(messages = listOf(Message(id, "assistant", text, sessionId = "chat-a")))
            compose.runOnIdle { flow<ChatState>(vm, "_chat").value = finished }
            await { compose.onAllNodes(hasContentDescription("Review the audit checklist") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Review the audit checklist").performClick().assertIsOn()
            compose.runOnIdle { flow<ChatState>(vm, "_chat").value = original.copy(messages = emptyList()) }
            runBlocking { kotlinx.coroutines.withTimeout(5000) {
                while (app.cache.read(scope, "reading-checklist:$id")?.let(::JSONObject)?.let { saved -> saved.keys().asSequence().any { saved.optBoolean(it) } } != true) kotlinx.coroutines.delay(25)
            } }
            compose.runOnIdle { flow<ChatState>(vm, "_chat").value = finished }
            await { compose.onAllNodes(hasContentDescription("Review the audit checklist") and isOn()).fetchSemanticsNodes().isNotEmpty() }
            capture("audit-checklist-recovery")
        } finally { runBlocking { app.cache.delete(scope, "reading-checklist:$id") } }
    }

    @Test fun modelAndEffortPickersConfirmNativeResultsWithoutDroppingMessages() = withFixture { vm, fixture ->
        fun models() {
            compose.onNodeWithTag("composer-model-effort").performClick()
            compose.onNodeWithText("Model & provider").performClick()
            await { vm.data.value["picker:models"] != null }
        }
        models()
        compose.onNode(hasText("other-model") and hasClickAction()).performScrollTo().performClick()
        await { vm.catalog.value.isBlank() && vm.chat.value.raw.string("model") == "other-model" }
        assertEquals(2, vm.chat.value.messages.size)
        models()
        compose.onNode(hasText("confirm-model") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Confirm model change").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("other-model", fixture.sessions.getValue("chat-a").getJSONObject("info").getString("model"))
        assertFalse(fixture.requests.any { it.string("method") == "config.set" && it.optJSONObject("params")?.optBoolean("confirm_expensive_model") == true })
        models()
        compose.onNode(hasText("confirm-model") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Use model").performClick()
        await { vm.form.value == null && vm.chat.value.raw.string("model") == "confirm-model" }
        assertEquals(2, vm.chat.value.messages.size)
        models()
        compose.onNodeWithText("Enter a configured model ID").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Model *")).performTextReplacement("custom-model")
        compose.onNode(hasSetTextAction() and hasText("Provider ID")).performTextReplacement("fixture")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        await { vm.form.value == null && vm.chat.value.raw.string("model") == "custom-model" }
        assertEquals(2, vm.chat.value.messages.size)
        compose.onNodeWithTag("composer-model-effort").performClick()
        compose.onNodeWithText("Reasoning effort").performClick()
        await { vm.data.value["picker:reasoning"]?.optJSONArray("options")?.strings()?.contains("high") == true }
        compose.onNodeWithTag("reasoning-level:low").performScrollTo().performClick()
        compose.onNodeWithText("Apply to conversation").performClick()
        await { vm.catalog.value.isBlank() && vm.chat.value.raw.string("reasoning_effort") == "low" && vm.chat.value.sessionReasoningEffort == "low" }
        assertEquals("medium", fixture.reasoningEffort)
        assertEquals("low", fixture.sessionReasoning["chat-a"])
        compose.onNodeWithTag("message-input").performClick().performTextInput("Keep low reasoning for this conversation")
        compose.onNodeWithTag("message-send").performClick()
        await { fixture.requests.any { it.string("method") == "prompt.submit" && it.optJSONObject("params")?.string("text") == "Keep low reasoning for this conversation" } }
        compose.onNodeWithTag("composer-model-effort").assertTextContains("low")
        compose.runOnIdle { vm.selectSession("chat-a") }
        await { vm.chat.value.projectionReady && vm.chat.value.sessionReasoningEffort == "low" }
        compose.onNodeWithTag("composer-model-effort").assertTextContains("low")
        receipts += "Configured/custom models show fresh native readback; native confirmation cancel makes no change; confirmed selection preserves messages; low effort stays session-scoped after send and refresh."
        capture("audit-model-effort")
    }

    @Test fun retiredSettingsRoutesReturnToAUsableRootWithoutABackLoop() = withFixture { vm, _ ->
        for (page in listOf("retired-settings-page", "category:retired-category")) {
            compose.runOnIdle { vm.more(page) }
            await { vm.ui.value.tab == "More" && vm.ui.value.morePage.isBlank() }
            androidx.test.espresso.Espresso.pressBack()
            await { vm.ui.value.tab == "Chat" }
        }
    }

    @Test fun clientCreatedUnpromptedShellResumesFromCacheAfterViewModelRestart() = withFixture { vm, fixture ->
        val owner = fixture.scope
        val initialShellBefore = runBlocking { app.cache.read(owner, "new-chat-shell") }
        val initialAttemptBefore = runBlocking { app.cache.read(owner, "new-chat-attempt") }
        val initialDraftBefore = runBlocking { app.cache.read(owner, "initial-draft") }
        val selectedBefore = runBlocking { app.cache.read(owner, "selected-session") }
        val indexBefore = runBlocking { app.cache.read(owner, "conversation-index") }
        val likelyShell = owner.session("new-2")
        val clientCreatedBefore = runBlocking { app.cache.read(likelyShell, "client-created-shell") }
        val projectionBefore = runBlocking { app.cache.read(likelyShell, "session") }
        val draftBefore = runBlocking { app.cache.read(likelyShell, "draft") }
        runBlocking { app.cache.delete(likelyShell, "client-created-shell"); app.cache.delete(likelyShell, "session") }
        val createsBefore = fixture.requests.count { it.string("method") == "session.create" }
        var shellId = ""
        var restartedClient: dev.hermes.android.data.BeaconClient? = null
        val restartedStore = ViewModelStore()
        try {
            compose.runOnIdle {
                field(vm, "sourceScope", null)
                flow<ChatState>(vm, "_chat").value = ChatState()
                flow<AppState>(vm, "_ui").value = vm.ui.value.copy(tab = "Chat", initialChatReady = true)
                vm.newChat()
            }
            await { vm.chat.value.sessionId.startsWith("new-") && !vm.ui.value.newChatOpening }
            shellId = vm.chat.value.sessionId
            assertEquals(createsBefore + 1, fixture.requests.count { it.string("method") == "session.create" })
            // The first app leaves the shell before it is marked unsaved: while it shows or is bound to the shell, its own reads
            // (the approval shield, a background socket reopen) could resume and so save it, leaving the restarted app nothing to resume.
            compose.runOnIdle { vm.selectSession("chat-a") }
            await { vm.chat.value.sessionId == "chat-a" }
            compose.waitForIdle()
            runBlocking { fixture.client.nativeSession("chat-a") }
            fixture.markUnpromptedShell(shellId)
            assertFalse(fixture.sessions.containsKey(shellId))
            val cachedShell = requireNotNull(runBlocking { app.cache.read(owner, "new-chat-shell") })
            assertEquals(shellId, cachedShell)
            assertEquals("1", runBlocking { app.cache.read(owner.session(shellId), "client-created-shell") })

            val auth = requireNotNull(vm.client).auth
            val newClient = fixture.clientFor(auth, app.auth)
            restartedClient = newClient
            lateinit var restarted: BeaconViewModel
            assertFalse("The shell is still unsaved when the app restarts", fixture.sessions.containsKey(shellId))
            fun shellResumes() = fixture.requests.count { it.string("method") == "session.resume" && it.getJSONObject("params").string("session_id") == shellId }
            val resumesBefore = shellResumes()
            compose.runOnUiThread {
                restarted = ViewModelProvider(restartedStore, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
                field(restarted, "client", newClient)
                field(restarted, "cachedOwnerScope", owner)
                field(restarted, "sourceScope", null)
                flow<AppState>(restarted, "_ui").value = vm.ui.value.copy(tab = "Chat", initialChatReady = true, newChatOpening = false, newChatError = "", restoringSession = false)
                flow<ChatState>(restarted, "_chat").value = ChatState()
                @Suppress("UNCHECKED_CAST")
                val shells = BeaconViewModel::class.java.getDeclaredField("initialShells").apply { isAccessible = true }.get(restarted) as MutableMap<String, String>
                shells[owner.key] = cachedShell
            }
            compose.runOnIdle {
                restarted.composer(TextFieldValue("Keep this draft after restart"))
                restarted.newChat()
            }
            try { await { restarted.chat.value.sessionId == shellId && !restarted.ui.value.newChatOpening } }
            catch (error: Throwable) {
                val calls = recentCalls(fixture)
                throw AssertionError("Restarted unprompted shell: id=${restarted.chat.value.sessionId} opening=${restarted.ui.value.newChatOpening} error=${restarted.ui.value.error} notice=${restarted.ui.value.notice} requests=$calls", error)
            }
            assertEquals("Keep this draft after restart", restarted.composer.value.text)
            assertEquals(createsBefore + 1, fixture.requests.count { it.string("method") == "session.create" })
            assertTrue(fixture.requests.any { it.string("method") == "GET" && it.string("path") == "/api/sessions/$shellId" })
            assertEquals(resumesBefore + 1, shellResumes())
            assertTrue(fixture.sessions.containsKey(shellId))
            receipts += "After a fresh ViewModel restores the owner-bound shell, passive history may be absent until Hermes resumes the minted key; the same shell resumes without another create and the draft survives."
        } finally {
            compose.runOnUiThread { restartedStore.clear(); restartedClient?.closeNative() }
            runBlocking {
                if (initialShellBefore == null) app.cache.delete(owner, "new-chat-shell") else app.cache.write(owner, "new-chat-shell", initialShellBefore, true)
                if (initialAttemptBefore == null) app.cache.delete(owner, "new-chat-attempt") else app.cache.write(owner, "new-chat-attempt", initialAttemptBefore, true)
                if (initialDraftBefore == null) app.cache.delete(owner, "initial-draft") else app.cache.write(owner, "initial-draft", initialDraftBefore, true)
                if (selectedBefore == null) app.cache.delete(owner, "selected-session") else app.cache.write(owner, "selected-session", selectedBefore, true)
                if (indexBefore == null) app.cache.delete(owner, "conversation-index") else app.cache.write(owner, "conversation-index", indexBefore, true)
                if (shellId.isNotBlank() && shellId != likelyShell.session) {
                    val session = owner.session(shellId)
                    app.cache.delete(session, "client-created-shell")
                    app.cache.delete(session, "session")
                    app.cache.delete(session, "draft")
                }
                if (clientCreatedBefore == null) app.cache.delete(likelyShell, "client-created-shell") else app.cache.write(likelyShell, "client-created-shell", clientCreatedBefore, true)
                if (projectionBefore == null) app.cache.delete(likelyShell, "session") else app.cache.write(likelyShell, "session", projectionBefore, true)
                if (draftBefore == null) app.cache.delete(likelyShell, "draft") else app.cache.write(likelyShell, "draft", draftBefore, true)
            }
        }
    }

    @Test fun newChatReplacesAConfirmedMissingInitialShellWithoutLosingTheDraft() = withFixture { vm, fixture ->
        val staleScope = fixture.scope.session("removed-initial-shell")
        // Older runs could leave a false phone-created marker for this never-created fixture id.
        runBlocking { app.cache.delete(staleScope, "client-created-shell"); app.cache.delete(staleScope, "session") }
        var createdId = ""
        try {
            compose.runOnIdle {
                field(vm, "sourceScope", null)
                flow<ChatState>(vm, "_chat").value = ChatState()
                flow<AppState>(vm, "_ui").value = vm.ui.value.copy(tab = "Chat", initialChatReady = true)
                @Suppress("UNCHECKED_CAST")
                val shells = BeaconViewModel::class.java.getDeclaredField("initialShells").apply { isAccessible = true }.get(vm) as MutableMap<String, String>
                shells[fixture.scope.key] = "removed-initial-shell"
                vm.composer(TextFieldValue("Keep this unsent thought"))
                vm.newChat()
            }
            try { await { vm.chat.value.sessionId.startsWith("new-") && !vm.ui.value.newChatOpening } }
            catch (error: Throwable) {
                val calls = recentCalls(fixture)
                throw AssertionError("Initial recovery: tab=${vm.ui.value.tab} ready=${vm.ui.value.initialChatReady} opening=${vm.ui.value.newChatOpening} id=${vm.chat.value.sessionId} error=${vm.ui.value.error} newChatError=${vm.ui.value.newChatError} notice=${vm.ui.value.notice} requests=$calls", error)
            }
            assertEquals("Keep this unsent thought", vm.composer.value.text)
            createdId = vm.chat.value.sessionId
            val calls = recentCalls(fixture)
            assertEquals("Expected one native session.create; requests=$calls", 1, fixture.requests.count { it.string("method") == "session.create" })
            assertTrue(fixture.sessions.containsKey(vm.chat.value.sessionId))
            @Suppress("UNCHECKED_CAST")
            val shells = BeaconViewModel::class.java.getDeclaredField("initialShells").apply { isAccessible = true }.get(vm) as MutableMap<String, String>
            assertEquals(vm.chat.value.sessionId, shells[fixture.scope.key])
        } finally {
            runBlocking {
                app.cache.delete(fixture.scope, "new-chat-shell"); app.cache.delete(fixture.scope, "new-chat-attempt"); app.cache.delete(fixture.scope, "initial-draft")
                app.cache.delete(fixture.scope, "selected-session"); app.cache.delete(fixture.scope, "conversation-index")
                app.cache.delete(staleScope, "client-created-shell"); app.cache.delete(staleScope, "session")
                if (createdId.isNotBlank()) {
                    val createdScope = fixture.scope.session(createdId)
                    app.cache.delete(createdScope, "client-created-shell"); app.cache.delete(createdScope, "session"); app.cache.delete(createdScope, "draft")
                }
            }
        }
    }

    @Test fun aNewChatAndItsDraftComeBackAfterSwitchingProfileAndBack() = withFixture { vm, fixture ->
        val createsBefore = fixture.requests.count { it.string("method") == "session.create" }
        compose.runOnIdle { vm.newChat() }
        await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
        val shell = vm.chat.value.sessionId
        compose.runOnIdle { vm.composer(TextFieldValue("Keep this across profiles")) }
        compose.runOnIdle { vm.switchProfile("coder") }
        // The other profile opens its own new chat; wait for it so the switch back is not racing it.
        await { vm.client?.scope?.profile == "coder" && vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.sessionId != shell && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
        compose.runOnIdle { vm.switchProfile("default") }
        await { vm.client?.scope?.profile == "default" && vm.chat.value.sessionId == shell && vm.composer.value.text == "Keep this across profiles" }
        assertEquals("", vm.ui.value.newChatError)
        assertEquals("The unsent chat is reopened, not replaced: one chat per profile and no extra shell on the way back", createsBefore + 2, fixture.requests.count { it.string("method") == "session.create" })
        receipts += "Switching profile and back reopens the unsent new chat with its draft instead of a blank new-chat screen."
    }

    @Test fun aNewChatAfterSwitchingProfileHasUsableControlsBeforeTheFirstMessage() = withFixture { vm, fixture ->
        // Nothing was ever opened through the app in either profile, so each switch lands on a new chat with no chat behind it.
        compose.runOnIdle { vm.switchProfile("coder") }
        await { vm.client?.scope?.profile == "coder" && vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
        compose.runOnIdle { vm.switchProfile("default") }
        await { vm.client?.scope?.profile == "default" && vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
        await { compose.onAllNodes(hasTestTag("composer-approvals") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        await { compose.onAllNodes(hasTestTag("composer-model-effort") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        // The shield shows the profile default (the fixture's profile mode), not Unknown.
        await { compose.onAllNodesWithContentDescription("Approvals: ${approvalChoiceLabel(fixture.approvalMode)}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("Approvals: Unknown", useUnmergedTree = true).assertCountEquals(0)
        assertEquals("", vm.ui.value.newChatError)
        receipts += "A new chat after a profile switch is a real chat: the approvals shield shows the profile default and the model chip is enabled before the first message."
    }

    @Test fun selectingTheCurrentSettingsDestinationClosesTheDrawerWithoutAddingBackHistory() = withFixture { vm, _ ->
        compose.runOnIdle { vm.composer(TextFieldValue("Keep this draft")); vm.more("") }
        compose.onNodeWithTag("navigation-toggle").performClick()
        compose.onNodeWithTag("navigation-settings").performScrollTo().performClick()
        compose.runOnIdle { assertFalse("Current destination must dismiss the menu", vm.ui.value.showSessions) }
        compose.runOnIdle { vm.backFromSettings() }
        assertEquals("Chat", vm.ui.value.tab)
        assertEquals("Keep this draft", vm.composer.value.text)
    }

    @Test fun connectionSummaryAndSignOutRequireTheExplicitConfirmation() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.more("Connection") }
        compose.onNodeWithText("Signed-in account · synthetic-owner").assertExists()
        compose.onNodeWithText("Profile · ${fixture.scope.profile}").assertExists()
        compose.onNodeWithText("Technical details").assertDoesNotExist()
        compose.onNodeWithText("Account identifier").assertDoesNotExist()
        compose.onNodeWithText("Sign out", ignoreCase = true).performScrollTo().performClick()
        compose.onNodeWithText("Sign out of Beacon").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(vm.ui.value.authenticated)
        compose.onNodeWithText("Sign out", ignoreCase = true).performScrollTo().performClick()
        compose.onAllNodesWithText("Sign out", ignoreCase = true, useUnmergedTree = true).onLast().performClick()
        await { !vm.ui.value.authenticated && vm.client == null }
        assertFalse(app.calls.state.value.active)
        receipts += "Connection details disclose only on request; cancel keeps the account, and confirmed sign-out clears native authority."
    }

    @Test fun connectionListsThisPhoneAndOtherPhonesAndRemovesOnlyAnotherPhoneAfterConfirming() = withFixture { vm, fixture ->
        val pairing = app.getSharedPreferences("native-pairing", android.content.Context.MODE_PRIVATE)
        val key = NativePairing.accountKey(fixture.scope)
        val prior = pairing.getString(key, null)
        fixture.pairedDevices += json("device_id" to "device-123", "name" to "Pixel 9", "last_seen" to "2026-10-24T10:00:00Z")
        fixture.pairedDevices += json("device_id" to "device-other", "name" to "Galaxy Fold", "last_seen" to "2026-10-23T09:00:00Z")
        pairing.edit().putString(key, "device-123").commit()
        try {
            compose.runOnIdle { vm.more("Connection") }
            await { compose.onAllNodesWithText("Galaxy Fold").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("This phone").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Pixel 9").assertIsDisplayed()
            compose.onNodeWithText("Other phones").assertIsDisplayed()
            // Each phone keeps its own id; this phone leaves with Sign out, so it has no Remove.
            compose.onNodeWithTag("settings-device-device-123").assertExists()
            compose.onNodeWithTag("settings-device-device-other").assertExists()
            compose.onNodeWithTag("settings-device-remove-device-123").assertDoesNotExist()
            compose.onNodeWithTag("settings-device-remove-device-other").performScrollTo().performClick()
            compose.onNodeWithText("Remove Galaxy Fold?").assertIsDisplayed()
            compose.onNodeWithText("It stops getting calls and alerts from Beacon.").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(2, fixture.pairedDevices.size)
            compose.onNodeWithTag("settings-device-remove-device-other").performScrollTo().performClick()
            compose.onNodeWithTag("settings-device-remove-confirm").performClick()
            await { fixture.pairedDevices.none { it.string("device_id") == "device-other" } }
            await { compose.onAllNodesWithText("Galaxy Fold").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Pixel 9").assertExists()
            receipts += "Connection lists this phone and each other linked phone by its own id; only another phone can be removed, and only after the confirm dialog."
        } finally {
            if (prior == null) pairing.edit().remove(key).commit() else pairing.edit().putString(key, prior).commit()
        }
    }

    @Test fun appearanceChatAndLicenseControlsHaveWorkingChoicesAndRetainStoredPreferences() = withFixture { vm, _ ->
        compose.runOnIdle { vm.more("Appearance") }
        fun choice(label: String, value: String) {
            compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithTag("choice-option:$label:$value").performScrollTo().performClick()
        }
        for (theme in listOf("light", "dark", "system")) { choice("Theme", theme); await { vm.preferences.value.theme == theme } }
        for (skin in NativeSkins) { compose.onNodeWithTag("skin-option:$skin").performScrollTo().performClick(); await { vm.preferences.value.skin == skin } }
        // Text size, language, text direction and motion belong to the phone's own settings.
        for (removed in listOf("Text size", "Language", "Reduce motion", "Right-to-left layout")) compose.onAllNodesWithText(removed, substring = true).assertCountEquals(0)
        capture("audit-appearance")
        compose.runOnIdle { vm.more("Settings") }
        choice("Send shortcut", "ctrl_enter")
        await { vm.data.value["profile-settings"]?.string("send_key") == "ctrl_enter" }
        for (label in listOf("Format your messages", "Show thinking")) compose.onNode(hasText(label) and isToggleable()).performScrollTo().performClick()
        await { vm.data.value["profile-settings"]?.optBoolean("render_user_markdown") == false && vm.data.value["profile-settings"]?.optBoolean("show_thinking") == false }
        compose.runOnIdle { vm.more("Licenses") }
        for (document in listOf("hermes-LICENSE.txt", "conduit-LICENSE.txt", "androidsvg-LICENSE.txt", "androidsvg-NOTICE.txt", "jlatexmath-LICENSE.txt", "jlatexmath-COPYING.txt", "rich/MERMAID-LICENSE", "rich/JSOUP-LICENSE", "rich/CORE-JS-LICENSE", "hermes-mobile-app-LICENSE.txt")) {
            choice("Document", document)
            val expected = app.assets.open(if (document.startsWith("rich/")) document else "licenses/$document").bufferedReader().use { it.readText().take(80) }
            await { compose.onAllNodes(hasText(expected, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        }
        receipts += "Appearance choices persist every shipped skin and theme (text size, language, direction and motion follow the phone); chat preferences and every bundled license document control were executed."
        capture("audit-licenses")
    }

    @Test fun voiceSettingsPersistOnThePhoneWithoutStartingACall() = withFixture { vm, fixture ->
        val store = VoiceKeyStore(app)
        val providers = listOf("gemini", "grok", "openai")
        val previousProvider = store.settings().provider
        val previousSelections = providers.associateWith { store.settings(it) }
        val previousKeys = providers.associateWith { store.key(it) }
        val previousLoader = VoiceCatalogCache.loader
        try {
            // Offline: the built-in lists stand in for the provider catalogs.
            VoiceCatalogCache.loader = { _, _ -> VoiceCatalog() }
            providers.forEach { store.saveKey(it, "fixture-$it-key") }
            compose.runOnIdle { vm.more("Beacon") }
            for (provider in listOf("grok", "gemini")) {
                // Change opens the provider list; Continue commits the choice because its key is saved.
                compose.onNodeWithTag("voice-provider-change").performScrollTo().performClick()
                compose.onNodeWithTag("voice-model-$provider").performScrollTo().performClick()
                compose.onNodeWithTag("setup-voice-continue").performScrollTo().performClick()
                await { store.settings().provider == provider }
                val before = store.settings(provider)
                // The stored and requested voice id keeps the provider's spelling.
                val choices = if (provider == "grok") listOf("eve", "ara", "rex", "sal", "leo") else listOf("Puck", "Charon", "Kore", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr")
                val voice = choices.first { it != before.voice }
                // The voice list comes in with the next step's animation; wait until it is settled before scrolling to it.
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("voice-option-voice-$voice").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("voice-option-voice-$voice").performScrollTo().performClick()
                await { store.settings(provider).voice == voice }
                compose.onNodeWithTag("voice-option-voice-$voice").assertIsSelected()
                val currentPersonality = store.settings(provider).personality
                val personality = if (currentPersonality == "concise") "warm" else "concise"
                compose.onNodeWithTag("voice-option-personality-$personality").performScrollTo().performClick()
                await { store.settings(provider).personality == personality }
            }
            assertFalse(fixture.requests.any { it.string("path").endsWith("/voice-settings") && it.string("method") != "GET" })
        } finally {
            VoiceCatalogCache.loader = previousLoader
            providers.forEach { provider -> previousKeys[provider]?.let { store.saveKey(provider, it) } ?: store.removeKey(provider) }
            previousSelections.values.forEach(store::setSelection)
            store.setSelection(previousSelections.getValue(previousProvider))
        }
        assertFalse(app.calls.state.value.active)
        assertNull(app.calls.service)
        receipts += "Gemini/Grok voice and personality choices persist in phone storage without server voice-settings writes or starting a call."
        capture("audit-voice-settings")
    }

    @Test fun conversationManagementBranchesArchivesAndDeletesOnlyAnIdleOwnedConversation() = withFixture { vm, fixture ->
        fun menu(label: String) {
            compose.onNodeWithContentDescription("Conversation actions").performClick()
            if (label in dev.hermes.android.ui.conversationMoreActions) compose.onNodeWithTag("conversation-more").performScrollTo().performClick()
            compose.onNodeWithTag("conversation-action:$label").performScrollTo().performClick()
        }
        menu("Archive")
        await { fixture.sessions.getValue("chat-a").getJSONObject("info").optBoolean("hidden") && vm.chat.value.raw.optBoolean("archived") }
        compose.onNodeWithContentDescription("Conversation actions").performClick()
        compose.onNodeWithText("Unarchive").performScrollTo().performClick()
        await { !fixture.sessions.getValue("chat-a").getJSONObject("info").optBoolean("hidden") && !vm.chat.value.raw.optBoolean("archived") }
        menu("Compress context")
        await { vm.ui.value.notice.contains("not needed") }
        menu("Branch")
        compose.onNode(hasSetTextAction() and hasText("Branch title")).performTextReplacement("Branch for audit")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        await { vm.chat.value.sessionId.startsWith("branch-") && vm.chat.value.messages.size == 2 }
        val branch = vm.chat.value.sessionId
        assertEquals(2, fixture.sessions.getValue("chat-a").getJSONArray("messages").length())
        menu("Rename")
        compose.onNode(hasSetTextAction() and hasText("Conversation title *")).performTextReplacement("Renamed branch")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        await { vm.chat.value.title == "Renamed branch" }
        menu("Undo last turn")
        compose.onNodeWithText("Undo", useUnmergedTree = true).performClick()
        await { vm.chat.value.messages.isEmpty() && vm.chat.value.raw.string("session_id") == branch }
        fixture.sessions.getValue(branch).put("running", true)
        menu("Delete conversation")
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        await { vm.form.value?.error?.contains("Stop the current reply") == true }
        await { vm.submittingForm.value.isBlank() }
        assertTrue(fixture.sessions.containsKey(branch))
        assertFalse(fixture.closedSessions.contains(branch))
        fixture.sessions[branch] = fixture.sessions.getValue(branch).copy().put("running", false)
        fixture.deleteMissingAfterClose = true
        compose.onNodeWithText("Delete", useUnmergedTree = true).assertIsEnabled()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        try { await { !fixture.sessions.containsKey(branch) && vm.chat.value.sessionId != branch && !vm.ui.value.newChatOpening } }
        catch (error: Throwable) {
            val calls = fixture.requests.map { it.string("method", it.string("path")) }.takeLast(10)
            throw AssertionError("Delete recovery: present=${fixture.sessions.containsKey(branch)} id=${vm.chat.value.sessionId} opening=${vm.ui.value.newChatOpening} form=${vm.form.value?.error} error=${vm.ui.value.error} requests=$calls", error)
        }
        assertTrue(fixture.closedSessions.contains(branch))
        assertFalse(fixture.requests.any { it.string("path").startsWith("/api/plugins/beacon/mobile/sessions/") && it.string("path").endsWith("/reserve") })
        assertEquals(2, fixture.sessions.getValue("chat-a").getJSONArray("messages").length())
        assertTrue(fixture.sessions.containsKey("chat-b"))
        receipts += "Conversation archive/unarchive, compression result, branch/rename/undo and deletion use native receipts. Active work refuses deletion; an already-missing chat after confirmed close counts as deleted without the optional plugin."
        capture("audit-conversation-management")
    }

    @Test fun skillEditorBackReturnsOneLevelAndKeepsUnsubmittedInstructions() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.more("Skills") }
        await { vm.phoneSettings.skills.value.skills.isNotEmpty() && !vm.phoneSettings.skills.value.loading }
        compose.onNodeWithText("Enabled", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Needs setup").performClick()
        compose.onNodeWithText("No matching skills").assertExists()
        // The drawer's history "All" chip stays composed off-screen; pick the skills filter.
        compose.onNode(hasText("All") and !hasAnyAncestor(hasTestTag("history-view-all")), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("skill-row-travel-planning").performScrollTo().performClick()
        await { vm.phoneSettings.skills.value.content != null }
        compose.onNodeWithText("Adjust instructions").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertFalse(vm.phoneSettings.skills.value.editing)
        assertNotNull("Back from editor should keep the skill detail open", vm.phoneSettings.skills.value.selected)
        compose.onNodeWithText("Adjust instructions").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Skill instructions")).performScrollTo().performTextReplacement("Unsaved instructions")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Keep editing").performClick()
        assertEquals("Unsaved instructions", vm.phoneSettings.skills.value.draft)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes").performClick()
        assertNotNull(vm.phoneSettings.skills.value.selected)
        assertEquals(fixture.skillText, vm.phoneSettings.skills.value.content?.content)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Enable travel-planning").performScrollTo().performClick()
        await { !fixture.skillEnabled && !vm.phoneSettings.skills.value.loading }
        compose.onNodeWithContentDescription("Enable travel-planning").performClick()
        await { fixture.skillEnabled && !vm.phoneSettings.skills.value.loading }
        receipts += "Skills filters, open, clean Back, dirty Back/keep/discard, All skills and enable/disable use the native controller without saving a discarded edit."
        capture("audit-skills")
    }

    @Test fun memoryTabsReadExplanationSaveConflictAndCancelRetainTheRightDocument() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.more("Memory") }
        await { vm.phoneSettings.memory.value.documents.size == 2 && !vm.phoneSettings.memory.value.loading }
        compose.onNodeWithText("How memory works").performScrollTo().performClick()
        compose.onNodeWithText("Hide how memory works").performClick()
        compose.onNodeWithText("About you").performScrollTo().performClick()
        compose.onNodeWithText("Edit about you").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Edit about you")).performTextReplacement("Prefers evidence and clear answers.")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("Refresh memory").assertIsNotEnabled()
        fixture.rejectMemorySave = true
        compose.onNodeWithText("Save notes").performScrollTo().performClick()
        await { vm.phoneSettings.memory.value.error.isNotBlank() }
        assertEquals("Prefers evidence and clear answers.", vm.phoneSettings.memory.value.draft)
        assertEquals("Synthetic owner", fixture.memoryNotes["user"])
        fixture.rejectMemorySave = false
        compose.onNodeWithText("Save notes").performScrollTo().performClick()
        await { vm.phoneSettings.memory.value.editing.isBlank() }
        assertEquals("Prefers evidence and clear answers.", fixture.memoryNotes["user"])
        assertEquals("Prefers quiet places", fixture.memoryNotes["memory"])
        compose.onNodeWithText("Agent notes").performScrollTo().performClick()
        compose.onNodeWithText("Edit agent notes").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Edit agent notes")).performTextReplacement("Discard this draft")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Cancel edit").performScrollTo().performClick()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Cancel edit").performScrollTo().performClick()
        compose.onNodeWithText("Discard changes").performClick()
        compose.onNodeWithContentDescription("Refresh memory").performClick()
        await { !vm.phoneSettings.memory.value.loading }
        assertEquals("Prefers quiet places", fixture.memoryNotes["memory"])
        receipts += "Memory tabs, explanation, edit/save/conflict retry, cancel/keep/discard and refresh preserve the selected document and native acknowledgement."
        capture("audit-memory")
    }

    @Test fun toolAndConnectionControlsChangeNativeStateAndShowVerifiedTools() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.more("Tools & commands") }
        await { vm.data.value["native-tools"] != null }
        compose.onNodeWithText("View tools · 1").performScrollTo().performClick()
        compose.onNodeWithText("web_search").assertExists()
        compose.onNodeWithText("Hide tools").performClick()
        compose.onNode(hasText("Web research") and isToggleable()).performScrollTo().performClick()
        await { !fixture.toolEnabled && "native-tools" !in vm.refreshing.value }
        compose.onNode(hasText("Web research") and isToggleable()).performClick()
        await { fixture.toolEnabled && "native-tools" !in vm.refreshing.value }
        compose.onNode(hasSetTextAction() and hasText("Search tools")).performScrollTo().performTextReplacement("no-match")
        compose.onNodeWithText("No matches").assertExists()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Browse chat commands").performScrollTo().performClick()
        await { vm.ui.value.morePage == "Commands" }
        compose.onNodeWithContentDescription("Refresh commands").performClick()
        compose.onNode(hasSetTextAction() and hasText("Search commands")).performTextReplacement("help")
        compose.onNodeWithText("/help").performScrollTo().performClick()
        assertEquals("/help ", vm.composer.value.text)
        assertFalse(fixture.requests.any { it.string("method") == "chat.send" })
        compose.runOnIdle { vm.more("MCP servers") }
        await { vm.data.value["native-mcp"] != null }
        compose.onNodeWithText("Test server").performScrollTo().performClick()
        await { vm.data.value["mcp-test:fixture_mcp"] != null }
        compose.onNodeWithText("View tools · 1").performScrollTo().performClick()
        compose.onNodeWithText("read_fixture").assertExists()
        compose.onNode(hasText("fixture_mcp") and isToggleable()).performScrollTo().performClick()
        await { !fixture.connectionEnabled && "native-mcp" !in vm.refreshing.value }
        compose.onNode(hasText("fixture_mcp") and isToggleable()).performClick()
        await { fixture.connectionEnabled && "native-mcp" !in vm.refreshing.value }
        receipts += "Tool groups and MCP search, enable/disable, test connection, tool disclosures and command preparation use real callbacks; selecting a command never sends it."
        capture("audit-connections")
    }

    @Test fun profileAndServerSettingsHaveCompleteNativeNavigationAndConfirmation() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.more("Profiles") }
        await { vm.phoneSettings.bots.value.profiles.isNotEmpty() && !vm.phoneSettings.bots.value.loading }
        compose.onNodeWithText("View profile").performScrollTo().performClick()
        await { vm.phoneSettings.bots.value.selected != null && !vm.phoneSettings.bots.value.loading }
        compose.onNodeWithText("Read behavior instructions").performScrollTo().performClick()
        compose.onNodeWithText("Check sources before answering.").performScrollTo().assertExists()
        compose.onNodeWithText("Hide behavior instructions").performScrollTo().performClick()
        compose.onNodeWithText("Change default model").performScrollTo().performClick()
        await { vm.phoneSettings.bots.value.editing }
        val modelList = compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().maxBy { it.boundsInRoot.right }
        compose.onNode(SemanticsMatcher("Profile model settings list") { it.id == modelList.id })
            .performScrollToNode(hasText("Enter a configured model ID"))
        compose.onNodeWithText("Enter a configured model ID").performClick()
        compose.onNode(hasSetTextAction() and hasText("Model ID")).performScrollTo().performTextReplacement("next-model")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Save default model").performScrollTo().performClick()
        await { fixture.profileModel == "next-model" && !vm.phoneSettings.bots.value.editing }
        compose.onNodeWithText("Skills for this profile").performScrollTo().performClick()
        await { vm.ui.value.morePage == "Skills" }
        compose.runOnIdle { vm.more("Health") }
        await { vm.phoneSettings.server.value.version == "fixture" && !vm.phoneSettings.server.value.loading }
        compose.onNodeWithContentDescription("Refresh").performClick()
        compose.runOnIdle { vm.more("Logs") }
        await { !vm.phoneSettings.server.value.loading }
        for (file in listOf("errors", "agent", "gateway", "gui", "desktop", "mcp")) {
            compose.onNodeWithText(file).performScrollTo().performClick()
            await { vm.phoneSettings.server.value.logFile == file && !vm.phoneSettings.server.value.loading }
        }
        compose.onNode(hasSetTextAction() and hasText("Search log entries")).performScrollTo().performTextReplacement("none")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("Refresh").performClick()
        compose.runOnIdle { vm.more("Session checkpoints") }
        await { !vm.phoneSettings.server.value.loading }
        compose.onNodeWithText("No session checkpoints are stored for this profile.").assertExists()
        compose.runOnIdle { vm.more("Server updates") }
        await { vm.phoneSettings.server.value.update != null && !vm.phoneSettings.server.value.loading }
        compose.onNodeWithText("Check now").performClick()
        await { !vm.phoneSettings.server.value.loading }
        compose.onNodeWithText("Apply server update").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(fixture.updateApplied)
        compose.onNodeWithText("Apply server update").performScrollTo().performClick()
        compose.onNodeWithText("Apply update").performClick()
        await { vm.phoneSettings.server.value.outcome == "success" }
        assertTrue(fixture.updateApplied)
        compose.onNodeWithText("Server update completed").performScrollTo().assertExists()
        receipts += "Profile behavior, model edit/keep/save, cross-page return, health/log categories/search, checkpoints and server-update cancel/apply/result exercised on isolated native responses."
        capture("audit-server-update")
    }

    @Test fun profileDisplayNamesReachSettingsConnectionAssistantAndProfiles() = withFixture { vm, fixture ->
        // The assistant uses a different profile than the chats, so both labels are checked.
        runBlocking { app.preferences.edit { it[stringPreferencesKey("assistant_profile")] = "coder"; it[stringPreferencesKey("assistant_name")] = "Beacon" } }
        await { vm.preferences.value.assistantProfile == "coder" && vm.preferences.value.assistantName == "Beacon" }
        showProfileDisplayNames(vm, fixture)
        compose.runOnIdle { vm.more("") }
        await { vm.ui.value.tab == "More" && vm.ui.value.morePage.isBlank() }
        // Hub rows keep their compatibility tags (Connection is settings-connection).
        fun summary(tag: String, text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        summary("settings-connection", "Connected · Personal profile").assertExists()
        summary("settings-row:Profiles", "Active profile · Personal profile").assertExists()
        summary("settings-row:Assistant", "Beacon · Code profile").assertExists()

        compose.runOnIdle { vm.more("Connection") }
        compose.onNodeWithText("Profile · Personal profile").assertExists()
        compose.runOnIdle { vm.more("Assistant") }
        compose.onNode(hasText("Profile") and hasText("Code profile")).assertExists()
        compose.onNodeWithText("What needs you · Code profile").assertExists()
        compose.runOnIdle { vm.more("Profiles") }
        await { vm.phoneSettings.bots.value.profiles.isNotEmpty() && !vm.phoneSettings.bots.value.loading }
        // The closed drawer's profile switcher shows the same name.
        compose.onNode(hasText("Personal profile") and !hasAnyAncestor(hasTestTag("navigation-menu"))).assertExists()
        compose.runOnIdle { vm.tab("Tasks") }
        await { vm.data.value["crons"]?.rows("jobs")?.isNotEmpty() == true }
        compose.onNode(hasText(" · Personal profile · ", substring = true)).assertExists()
        compose.runOnIdle { vm.tab("Chat") }
        compose.onNodeWithTag("composer-approvals").performClick()
        compose.onNodeWithTag("approvals-menu").assertExists()
        // R6M moved policy changes to profile Settings; this menu describes the chat's bypass.
        compose.onNodeWithText("Approvals in this chat").assertExists()
        await { vm.data.value["picker:approvals"]?.let { it.string("scope_key") == fixture.scope.key && it.string("session_id") == vm.chat.value.sessionId } == true && "picker:approvals" !in vm.refreshing.value }
        compose.onNodeWithContentDescription("Approvals: Manual").assertExists()
        compose.onNodeWithTag("approval-ask").assertTextContains("Manual").assertTextContains("Ask before actions").assertIsSelected()
    }

    @Test fun botOverviewKeepsUnsupportedMembershipAndConfirmedDeletionExplicit() = withFixture { vm, fixture ->
        fixture.botProfiles = listOf("default", "coder", "academic")
        compose.runOnIdle { vm.navigate("Bots") }
        await { vm.botMode.state.value.bots.size == 3 && !vm.ui.value.botModeSwitching }
        compose.onNodeWithText("Create shared space").assertDoesNotExist()
        compose.onNode(hasText("Shared workspace") and hasAnyAncestor(hasTestTag("bot-mode-home"))).performScrollTo().performClick()
        await { vm.botMode.state.value.selectedSpace == "fixture-room" && !vm.botMode.state.value.loading }
        compose.runOnIdle { vm.botMode.draft("Keep this team draft") }
        compose.onNodeWithContentDescription("Shared-space actions").performClick()
        compose.onNodeWithText("Change bots unavailable").assertDoesNotExist()
        compose.onNodeWithText("Edit shared space").performClick()
        compose.onNodeWithText("Change bots unavailable").assertDoesNotExist()
        compose.onNode(hasText("Create another space") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(setOf("default", "coder"), vm.botMode.state.value.members.toSet())
        assertEquals("Keep this team draft", vm.botMode.state.value.draft)
        assertFalse(fixture.requests.any { it.string("path").endsWith("/members") })
        compose.onNodeWithContentDescription("Shared-space actions").performClick()
        compose.onNodeWithText("Delete shared space").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(fixture.roomDeleted)
        compose.onNodeWithContentDescription("Shared-space actions").performClick()
        compose.onNodeWithText("Delete shared space").performClick()
        compose.onNodeWithText("Delete space").performClick()
        await { fixture.roomDeleted && vm.botMode.state.value.selectedSpace.isBlank() }
        assertEquals(1, fixture.requests.count { it.string("path").endsWith("/fixture-room/delete") })
        receipts += "Bot overview navigates directly; unsupported roster edits retain members/draft with no mutation, cancel does not delete, and native disband confirmation removes only the selected room."
        capture("flow-bot-overview")
    }

    @Test fun absentNativeBotCapabilitiesDisableApprovalRenameAndDeletion() = withFixture { vm, fixture ->
        fixture.roomCapabilitiesAvailable = false
        fixture.roomActions += json("kind" to "approval", "task_id" to "task-once", "member_id" to "coder",
            "request_id" to "request-once", "execution_generation" to 1,
            "approval" to json("description" to "Inspect a synthetic file", "choices" to JSONArray(listOf("once", "deny"))))
        compose.runOnIdle { vm.navigate("Bots") }
        await { vm.ui.value.botModeEnabled && !vm.ui.value.botModeSwitching && !vm.botMode.state.value.loading }
        compose.runOnIdle { vm.openBotSpace("fixture-room") }
        await { vm.botMode.state.value.selectedSpace == "fixture-room" && !vm.botMode.state.value.loading }
        compose.onNodeWithText("Native approval support could not be confirmed.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Allow once").assertDoesNotExist()
        compose.onNodeWithText("Deny").assertDoesNotExist()
        compose.onNodeWithContentDescription("Shared-space actions").performClick()
        compose.onNodeWithText("Deletion unavailable").assertIsNotEnabled()
        compose.onNodeWithText("Edit shared space").performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(fixture.requests.any { row -> listOf("/action", "/members", "/rename", "/delete").any { row.string("path").endsWith(it) } })
        assertFalse(fixture.roomDeleted)
        assertEquals(1, fixture.roomActions.size)
    }

    @Test fun reopeningSavedChatDoesNotResumeAnAgentAndShowsTailImmediately() = withFixture { vm, fixture ->
        val rows = JSONArray((1..45).map { json("role" to "user", "text" to "Saved message $it", "row_id" to it) })
        fixture.sessions.getValue("chat-b").put("messages", rows)
        // A prior live snapshot can be cached; its old runtime id does not authorize a new resume.
        runBlocking { app.cache.write(fixture.scope.session("chat-b"), "session", fixture.snapshot("chat-b").toString()) }
        compose.runOnIdle { vm.selectSession("chat-b") }
        await { vm.chat.value.projectionReady && vm.chat.value.messages.size == 45 }
        compose.onNodeWithText("Saved message 45").assertIsDisplayed()
        compose.onNodeWithText("Saved message 1").assertDoesNotExist()
        assertFalse(fixture.requests.any { it.string("method") in setOf("session.resume", "session.history", "session.create", "prompt.submit") })
        val count = fixture.requests.size
        compose.runOnIdle { vm.more("Appearance"); vm.backFromSettings() }
        compose.onNodeWithText("Saved message 45").assertIsDisplayed()
        assertFalse(fixture.requests.drop(count).any { it.string("method") == "prompt.submit" })
        receipts += "Initial native history is measured at the tail and returning from settings preserves it; reads never resume/create a Hermes agent."
        capture("flow-passive-history-tail")
    }

    @Test fun optionalPluginOutageKeepsStockHistoryBrowsingAndNativeSend() = withFixture { vm, fixture ->
        val passive = setOf("session.resume", "session.history", "session.create", "prompt.submit")
        for ((status, id) in listOf(404 to "chat-b", 500 to "chat-a")) {
            fixture.pluginStatus = status
            fixture.sessions.getValue(id).put("messages", JSONArray((1..3).map { json("id" to it, "role" to "user", "text" to "Stock saved $id $it during plugin $status") }))
            val before = fixture.requests.size
            compose.runOnIdle { vm.selectSession(id) }
            await { vm.chat.value.sessionId == id && vm.chat.value.projectionReady && vm.chat.value.messages.any { it.text == "Stock saved $id 3 during plugin $status" } }
            compose.onNodeWithText("Stock saved $id 3 during plugin $status").assertIsDisplayed()
            val reads = fixture.requests.drop(before)
            assertTrue("History came from the stock route", reads.any { it.string("path") == "/api/sessions/$id/messages" && it.string("profile") == fixture.scope.profile })
            assertTrue("Plugin history routes are not used", reads.none { it.string("path").startsWith("/api/plugins/beacon/mobile/sessions") && !it.string("path").endsWith("/voice-history") })
            assertTrue("Browsing never resumes, creates or sends", reads.none { it.string("method") in passive })
            assertFalse(vm.chat.value.raw.optBoolean("voice_transcripts_available"))
            assertFalse(vm.chat.value.status.contains("Could not load"))
        }
        compose.onNodeWithTag("message-input").performClick().performTextInput("Native send while the plugin is down")
        compose.onNodeWithTag("message-send").assertIsEnabled().performClick()
        await { fixture.requests.any { it.string("method") == "prompt.submit" } }
        val submit = fixture.requests.single { it.string("method") == "prompt.submit" }.getJSONObject("params")
        assertEquals("The native send resumes only the selected conversation", "runtime-chat-a", submit.string("session_id"))
        assertTrue(submit.string("text").contains("Native send while the plugin is down"))
        receipts += "With the optional plugin returning 404/500, saved history loads from stock /api/sessions routes without resuming an agent, and a native send still resumes and submits to the selected conversation."
        capture("flow-stock-history-plugin-outage")
    }

    @Test fun sendInFlightShowsTheMessageOnceWithoutTheUnknownOutcomeBanner() = withFixture { vm, fixture ->
        fixture.holdPromptAcknowledgement = true
        try {
            compose.runOnIdle { vm.composer(TextFieldValue("In flight message")); vm.send() }
            await { fixture.requests.any { it.string("method") == "prompt.submit" } }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("sending-message").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("The message shows once: in the transcript, not again in the composer", 1, compose.onAllNodesWithText("In flight message").fetchSemanticsNodes().size)
            assertEquals("A send still in flight is not an unknown outcome", 0, compose.onAllNodesWithText("Checking whether your last message arrived. Your draft is saved.").fetchSemanticsNodes().size)
            assertEquals("The draft stays held until Hermes accepts the turn", "In flight message", vm.composer.value.text)
        } finally { fixture.releasePromptAcknowledgement() }
        await { vm.chat.value.uncertain.isBlank() && vm.chat.value.streamId.isNotBlank() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("sending-message").fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithTag("live-reply").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("The accepted draft is cleared", "", vm.composer.value.text)
    }

    @Test fun queuedPromptWaitsWhenAFinishedTurnsReplyIsNotInHistory() = withFixture { vm, fixture ->
        kotlinx.coroutines.runBlocking { app.cache.delete(fixture.scope.session("chat-a"), "live:runtime-chat-a") }
        val subscriptionsBeforeSend = fixture.streamSubscriptions.get()
        // Hermes saves the prompt when the turn starts...
        compose.runOnIdle { fixture.persistTerminalMessages = true; vm.composer(TextFieldValue("First prompt")); vm.send() }
        await { fixture.requests.any { it.string("method") == "prompt.submit" } && vm.chat.value.streamId == "runtime-chat-a" }
        await { fixture.streamSubscriptions.get() > subscriptionsBeforeSend }
        compose.runOnIdle { vm.composer(TextFieldValue("Queued follow-up")); vm.queue() }
        await { vm.chat.value.queued.singleOrNull()?.text == "Queued follow-up" }
        // ...but this reply never reaches the saved history.
        fixture.persistTerminalMessages = false
        fixture.emitNativeEvent("message.complete", json("text" to "A reply history never saved"))
        // An unconfirmed reply keeps its live output; what matters is that the queue does not advance.
        // Every unproven ending (reply not confirmed, history changed, refresh failed) keeps live output and says so.
        try { await { vm.chat.value.status.contains("live output retained") } }
        catch (error: Throwable) { throw AssertionError("Unproven ending not reported: status=${vm.chat.value.status} stream=${vm.chat.value.streamId} queued=${vm.chat.value.queued.map { it.text }} submits=${fixture.requests.count { it.string("method") == "prompt.submit" }}", error) }
        runCatching { compose.waitUntil(3_000) { fixture.requests.count { it.string("method") == "prompt.submit" } > 1 } }
        assertEquals("The queued prompt waits for review", "Queued follow-up", vm.chat.value.queued.single().text)
        assertEquals("Nothing more is sent after an unconfirmed reply", 1, fixture.requests.count { it.string("method") == "prompt.submit" })
    }

    // Live 2026-10-26: a message sent seconds after the network returned finished in Hermes, but the chat stayed "Working" until the app was backgrounded.
    @Test fun aStreamThatLostItsSubscriptionSettlesFromHistoryWithoutSendingAgain() = withFixture { vm, fixture ->
        field(vm, "streamSilenceMs", 1_500L)
        field(vm, "streamWatchMs", 300L)
        kotlinx.coroutines.runBlocking { app.cache.delete(fixture.scope.session("chat-a"), "live:runtime-chat-a") }
        val subscriptionsBeforeSend = fixture.streamSubscriptions.get()
        compose.runOnIdle { fixture.persistTerminalMessages = true; vm.composer(TextFieldValue("Prompt whose events go astray")); vm.send() }
        await { fixture.requests.any { it.string("method") == "prompt.submit" } && vm.chat.value.streamId == "runtime-chat-a" }
        await { fixture.streamSubscriptions.get() > subscriptionsBeforeSend }
        // Another connection subscribes to the same turn, as an overlapping attach or a reconnect does: Hermes now sends the turn's events there.
        val other = fixture.client.detached()
        try {
            runBlocking { other.nativeSession("chat-a"); other.nativeReplay() }
            fixture.nativeTurnStatus = "idle"
            fixture.emitNativeEvent("message.complete", json("text" to "A reply the first stream never heard"))
            try { await { vm.chat.value.streamId.isBlank() && vm.chat.value.messages.any { it.text == "A reply the first stream never heard" } } }
            catch (error: Throwable) { throw AssertionError("Chat stayed on its stream: status=${vm.chat.value.status} stream=${vm.chat.value.streamId} requests=${recentCalls(fixture)}", error) }
            assertEquals("Settling reads history; it never sends the prompt again", 1, fixture.requests.count { it.string("method") == "prompt.submit" })
            receipts += "A stream another connection took over asks Hermes after a quiet spell and settles the finished turn from history."
        } finally { other.closeNative() }
    }

    @Test fun browsingAChatAcrossWatcherTicksNeverResumesIt() = withFixture { vm, fixture ->
        // The app's rule: reading a chat opens or resumes no native session. The re-arm only asks Hermes about a chat that shows a running turn.
        field(vm, "streamWatchMs", 300L)
        // Opened the normal way, so its watcher runs with the short tick.
        compose.runOnIdle { vm.selectSession("chat-a") }
        await { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady && vm.chat.value.streamId.isBlank() }
        compose.waitForIdle()
        fun asks() = fixture.requests.count { it.string("method") in setOf("session.resume", "session.active_list") }
        val before = asks()
        Thread.sleep(1_500); compose.waitForIdle()
        assertEquals("An idle chat is not resumed or probed on watcher ticks", before, asks())
    }

    @Test fun aRunningTurnInAChatThatWasDeletedElsewhereSaysSoInsteadOfReconnecting() = withFixture { vm, fixture ->
        field(vm, "streamWatchMs", 300L)
        compose.runOnIdle { vm.newChat() }
        await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
        val stored = vm.chat.value.sessionId
        compose.runOnIdle { vm.composer(TextFieldValue("Still there?")); vm.send() }
        await { fixture.requests.any { it.string("method") == "prompt.submit" } && vm.chat.value.streamId.isNotBlank() }
        @Suppress("UNCHECKED_CAST")
        val streams = BeaconViewModel::class.java.getDeclaredField("streams").apply { isAccessible = true }.get(vm) as MutableMap<String, kotlinx.coroutines.Job>
        compose.runOnIdle { streams.values.toList().forEach { it.cancel() }; streams.clear() }
        // Deleted on another device: its runtime and its saved row are gone.
        fixture.reapRuntime(stored)
        fixture.sessions.remove(stored)
        await { vm.chat.value.status.contains("unavailable", ignoreCase = true) }
        val requests = fixture.requests.size
        Thread.sleep(1_500)
        assertTrue("No reconnect loop for a chat that is gone: ${fixture.requests.drop(requests).map { it.string("method") + " " + it.string("path") }}", fixture.requests.size - requests <= 2)
        receipts += "A running turn in a chat deleted elsewhere ends with Conversation unavailable instead of a reconnect loop."
    }

    // Live 2026-10-27: a new chat's first message finished in Hermes in 4.3 s, but the chat stayed "Working" with Stop for minutes, until the app was backgrounded.
    // The socket that sent the prompt closed at once, no stream socket ever opened, and Hermes dropped the idle runtime a few seconds later.
    // Before the fix nothing re-armed such a chat: a saved history names no running stream and cannot end a turn nobody heard, and the silence watcher lives inside the stream.
    @Test fun aNewChatsFirstReplySettlesWhenNoStreamFollowedItAndHermesDroppedTheRuntime() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.newChat() }
        await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
        val stored = vm.chat.value.sessionId
        val creatingRuntime = "runtime-$stored"
        fixture.persistTerminalMessages = true
        compose.runOnIdle { vm.composer(TextFieldValue("QA unfolded check. Reply ok.")); vm.send() }
        await { fixture.requests.any { it.string("method") == "prompt.submit" } && vm.chat.value.streamId == creatingRuntime }
        // The stream that should follow the reply is gone, as in the live run.
        @Suppress("UNCHECKED_CAST")
        val streams = BeaconViewModel::class.java.getDeclaredField("streams").apply { isAccessible = true }.get(vm) as MutableMap<String, kotlinx.coroutines.Job>
        compose.runOnIdle { streams.values.toList().forEach { it.cancel() }; streams.clear() }
        // Hermes finishes the turn, then drops the idle runtime: a call naming it now answers 4001, a resume by the stored id mints another.
        fixture.nativeTurnStatus = "idle"
        fixture.retainNativeEvent(creatingRuntime, "message.complete", json("text" to "ok"))
        fixture.reapRuntime(stored)
        fun storedResumes() = fixture.requests.count { it.string("method") == "session.resume" && it.getJSONObject("params").string("session_id") == stored }
        val resumesBefore = storedResumes()
        // Within the silence window (45 s), without leaving the app.
        try { compose.waitUntil(40_000) { vm.chat.value.streamId.isBlank() && vm.chat.value.messages.any { it.text == "ok" } } }
        catch (error: Throwable) { throw AssertionError("Chat stayed on its turn: status=${vm.chat.value.status} stream=${vm.chat.value.streamId} requests=${recentCalls(fixture)}", error) }
        assertEquals("Settling reads history; it never sends the prompt again", 1, fixture.requests.count { it.string("method") == "prompt.submit" })
        assertTrue("The stored conversation was resumed, not the dropped runtime", storedResumes() > resumesBefore)
        receipts += "A new chat whose first reply nothing followed asks Hermes after a quiet spell, finds the runtime gone, and settles the finished turn from history without leaving the app."
    }

    @Test fun aSendFoundInHistoryClearsTheFirstPromptMarkersLikeAnAcceptedOne() = withFixture { vm, fixture ->
        val owner = fixture.scope
        var shellId = ""
        try {
            compose.runOnUiThread {
                field(vm, "sourceScope", null)
                flow<ChatState>(vm, "_chat").value = ChatState()
                flow<AppState>(vm, "_ui").value = vm.ui.value.copy(tab = "Chat", initialChatReady = true, newChatOpening = false, sessions = emptyList())
            }
            compose.runOnIdle { vm.newChat() }
            await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
            shellId = vm.chat.value.sessionId
            val shell = owner.session(shellId)
            @Suppress("UNCHECKED_CAST")
            val created = BeaconViewModel::class.java.getDeclaredField("clientCreatedSessions").apply { isAccessible = true }.get(vm) as Set<String>
            @Suppress("UNCHECKED_CAST")
            val shells = BeaconViewModel::class.java.getDeclaredField("initialShells").apply { isAccessible = true }.get(vm) as Map<String, String>
            assertTrue(created.contains(shell.key))
            assertEquals(shellId, shells[owner.key])
            assertEquals("1", runBlocking { app.cache.read(shell, "client-created-shell") })
            assertEquals(shellId, runBlocking { app.cache.read(owner, "new-chat-shell") })

            fixture.persistTerminalMessages = true
            fixture.holdPromptAcknowledgement = true
            fun projectReads() = fixture.requests.count { it.string("method") == "projects.for_cwd" }
            compose.waitForIdle()
            val projectReadsBefore = projectReads()
            try {
                compose.runOnIdle { vm.composer(TextFieldValue("First words")); vm.send() }
                await { fixture.requests.any { it.string("method") == "prompt.submit" } }
                // Hermes saved the prompt, but its acknowledgement never arrives: the receipt check finds it in history.
                compose.runOnIdle { vm.reconcileSend() }
                await { vm.chat.value.uncertain.isBlank() && vm.chat.value.messages.any { it.text == "First words" } }
                // A send settled from history saves the chat too, so its project is read again once.
                await { projectReads() == projectReadsBefore + 1 }
                Thread.sleep(500); compose.waitForIdle()
                assertEquals("One project read, not one per update", projectReadsBefore + 1, projectReads())
                assertFalse("The creating-client marker ends once Hermes holds the prompt", created.contains(shell.key))
                assertNull(runBlocking { app.cache.read(shell, "client-created-shell") })
                assertNull(shells[owner.key])
                assertNull(runBlocking { app.cache.read(owner, "new-chat-shell") })
                // History gets a summary row, not the chat's messages.
                await { vm.ui.value.sessions.any { it.string("session_id") == shellId } }
                assertTrue(vm.ui.value.sessions.filter { it.string("session_id") == shellId }.none { it.has("messages") || it.has("voice_transcripts") })
            } finally { fixture.releasePromptAcknowledgement() }
            await { vm.chat.value.streamId.isNotBlank() }
            assertEquals("The settled send is not sent again", 1, fixture.requests.count { it.string("method") == "prompt.submit" })
            receipts += "A first prompt found in history by the receipt check clears the new-chat markers exactly as an acknowledged one does."
        } finally {
            runBlocking {
                listOf("new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session", "conversation-index").forEach { app.cache.delete(owner, it) }
                if (shellId.isNotBlank()) listOf("client-created-shell", "session", "draft", "pending").forEach { app.cache.delete(owner.session(shellId), it) }
            }
        }
    }

    @Test fun newConversationKeepsTheOpenUnsentChatInsteadOfAddingEmptyRows() = withFixture { vm, fixture ->
        val owner = fixture.scope
        val created = mutableListOf<String>()
        try {
            compose.runOnIdle { vm.newChat() }
            await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
            val shell = vm.chat.value.sessionId.also { created += it }
            val createsBefore = fixture.requests.count { it.string("method") == "session.create" }
            repeat(3) { compose.runOnIdle { vm.newChat() } }
            compose.waitForIdle()
            assertEquals("Tapping New conversation again keeps the open unsent chat", shell, vm.chat.value.sessionId)
            assertEquals("...and creates no further empty chat", createsBefore, fixture.requests.count { it.string("method") == "session.create" })
            // A named thread asks for a chat of its own.
            compose.runOnIdle { vm.newChat(json("title" to "A separate thread")) }
            await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.sessionId != shell && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
            created += vm.chat.value.sessionId
            assertEquals(createsBefore + 1, fixture.requests.count { it.string("method") == "session.create" })
            receipts += "New conversation reuses the open unsent chat, so History gains no empty rows; a chat with options still opens its own."
        } finally {
            runBlocking { created.forEach { id -> listOf("client-created-shell", "session", "draft", "pending").forEach { app.cache.delete(owner.session(id), it) } } }
        }
    }

    @Test fun anEmptyNewChatJoinsHistoryWithItsFirstMessageAndANamedThreadAtOnce() = withFixture { vm, fixture ->
        val owner = fixture.scope
        val created = mutableListOf<String>()
        fun listed(id: String) = vm.ui.value.sessions.any { it.string("session_id") == id }
        fun listReads() = fixture.requests.count { it.string("method") == "GET" && it.string("path") == "/api/sessions" }
        try {
            // The list's own first read must be in before the baseline, or it lands later and looks like a read the open caused.
            await { listReads() > 0 && !vm.ui.value.historyLoading }
            compose.waitForIdle()
            val readsBefore = listReads()
            compose.runOnIdle { vm.newChat() }
            await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
            val plain = vm.chat.value.sessionId.also { created += it }
            assertFalse("An empty new chat is not a History row", listed(plain))
            Thread.sleep(1_000)
            compose.waitForIdle()
            val extra = fixture.requests.filter { it.string("method") == "GET" && it.string("path") == "/api/sessions" }.drop(readsBefore).map { it.string("query") }
            assertEquals("Opening a chat does not read the History list again: $extra", readsBefore, listReads())
            // Its first message puts it there, as a summary row.
            fixture.persistTerminalMessages = true
            compose.runOnIdle { vm.composer(TextFieldValue("First words")); vm.send() }
            await { fixture.requests.any { it.string("method") == "prompt.submit" } && listed(plain) }
            assertTrue(vm.ui.value.sessions.first { it.string("session_id") == plain }.optInt("message_count") >= 1)
            // A named thread is listed as soon as it exists, empty.
            compose.runOnIdle { vm.newThread("A fresh thread") }
            await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.sessionId != plain && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
            val thread = vm.chat.value.sessionId.also { created += it }
            assertTrue("A named thread is listed at once", listed(thread))
            receipts += "An empty new chat is listed only after its first message; a named thread at once; opening a chat reads no History list."
        } finally {
            runBlocking { created.forEach { id -> listOf("client-created-shell", "session", "draft", "pending").forEach { app.cache.delete(owner.session(id), it) } } }
        }
    }

    @Test fun newConversationOpensAChatOfItsOwnAfterAnUnsentThreadOrOptionChat() = withFixture { vm, fixture ->
        val owner = fixture.scope
        val created = mutableListOf<String>()
        fun creates() = fixture.requests.count { it.string("method") == "session.create" }
        fun marker(id: String) = runBlocking { app.cache.read(owner.session(id), "client-created-shell") }
        try {
            for (options in listOf(json("title" to "An unsent thread", "thread" to true), json("title" to "A titled chat", "model" to "other-model", "model_provider" to "fixture"))) {
                compose.runOnIdle { vm.newChat(options) }
                await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.sessionId !in created && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
                val apart = vm.chat.value.sessionId.also { created += it }
                assertEquals("A chat with options is remembered as one kept apart", "apart", marker(apart))
                val createsBefore = creates()
                compose.runOnIdle { vm.newChat() }
                await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.sessionId !in created && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
                val plain = vm.chat.value.sessionId.also { created += it }
                assertEquals("New conversation does not reuse it", createsBefore + 1, creates())
                assertEquals("A plain chat keeps the plain marker", "1", marker(plain))
            }
            receipts += "Plain New conversation opens its own chat after an unsent thread or a chat opened with a title or model."
        } finally {
            runBlocking { created.forEach { id -> listOf("client-created-shell", "session", "draft", "pending").forEach { app.cache.delete(owner.session(id), it) } } }
        }
    }

    @Test fun anUnsentThreadStaysApartFromNewConversationAfterAnAppRestart() = withFixture { vm, fixture ->
        val owner = fixture.scope
        val saved = listOf("new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session", "conversation-index").associateWith { runBlocking { app.cache.read(owner, it) } }
        val created = mutableListOf<String>()
        var restartedClient: dev.hermes.android.data.BeaconClient? = null
        val restartedStore = ViewModelStore()
        try {
            compose.runOnIdle {
                field(vm, "sourceScope", null)
                flow<ChatState>(vm, "_chat").value = ChatState()
                flow<AppState>(vm, "_ui").value = vm.ui.value.copy(tab = "Chat", initialChatReady = true, newChatOpening = false)
                vm.newChat(json("title" to "An unsent thread", "thread" to true))
            }
            await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
            val thread = vm.chat.value.sessionId.also { created += it }
            // After a restart Hermes has no row for the unsent chat and its resume snapshot carries no source: only the saved marker remains.
            fixture.markUnpromptedShell(thread)
            assertEquals("apart", runBlocking { app.cache.read(owner.session(thread), "client-created-shell") })
            val cachedShell = requireNotNull(runBlocking { app.cache.read(owner, "new-chat-shell") })
            val newClient = fixture.clientFor(requireNotNull(vm.client).auth, app.auth)
            restartedClient = newClient
            lateinit var restarted: BeaconViewModel
            compose.runOnUiThread {
                restarted = ViewModelProvider(restartedStore, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
                field(restarted, "client", newClient)
                field(restarted, "cachedOwnerScope", owner)
                field(restarted, "sourceScope", null)
                flow<AppState>(restarted, "_ui").value = vm.ui.value.copy(tab = "Chat", initialChatReady = true, newChatOpening = false, newChatError = "", restoringSession = false)
                flow<ChatState>(restarted, "_chat").value = ChatState()
                @Suppress("UNCHECKED_CAST")
                val shells = BeaconViewModel::class.java.getDeclaredField("initialShells").apply { isAccessible = true }.get(restarted) as MutableMap<String, String>
                shells[owner.key] = cachedShell
            }
            compose.runOnIdle { restarted.newChat() }
            await { restarted.chat.value.sessionId == thread && restarted.chat.value.projectionReady && !restarted.ui.value.newChatOpening }
            val createsBefore = fixture.requests.count { it.string("method") == "session.create" }
            compose.runOnIdle { restarted.newChat() }
            await { restarted.chat.value.sessionId.startsWith("new-") && restarted.chat.value.sessionId != thread && restarted.chat.value.projectionReady && !restarted.ui.value.newChatOpening }
            created += restarted.chat.value.sessionId
            assertEquals("The restored thread is not reused", createsBefore + 1, fixture.requests.count { it.string("method") == "session.create" })
            receipts += "A thread that was never sent still gets a plain chat of its own from New conversation after the app restarts."
        } finally {
            compose.runOnUiThread { restartedStore.clear(); restartedClient?.closeNative() }
            runBlocking {
                saved.forEach { (key, value) -> if (value == null) app.cache.delete(owner, key) else app.cache.write(owner, key, value, true) }
                created.forEach { id -> listOf("client-created-shell", "session", "draft", "pending").forEach { app.cache.delete(owner.session(id), it) } }
            }
        }
    }

    @Test fun aCreateThatNeverLeftThePhoneLeavesNoUnconfirmedAttemptToRetry() = withFixture { vm, fixture ->
        val owner = fixture.scope
        val saved = listOf("new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session", "conversation-index").associateWith { runBlocking { app.cache.read(owner, it) } }
        val created = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        val attempts = BeaconViewModel::class.java.getDeclaredField("initialCreateAttempts").apply { isAccessible = true }.get(vm) as Map<String, String>
        try {
            compose.runOnIdle {
                field(vm, "sourceScope", null)
                flow<ChatState>(vm, "_chat").value = ChatState()
                flow<AppState>(vm, "_ui").value = vm.ui.value.copy(tab = "Chat", initialChatReady = true, newChatOpening = false, newChatError = "", error = "")
                // The network is down: no connection can open, so nothing reaches Hermes.
                fixture.client.closeNative()
                fixture.refuseConnection = true
                vm.newChat()
            }
            await { !vm.ui.value.newChatOpening && vm.ui.value.error.isNotBlank() }
            assertEquals("No Retry state waits for a create that never left the phone", "", vm.ui.value.newChatError)
            assertTrue(attempts.isEmpty())
            assertNull("...and no unconfirmed-attempt marker is kept", runBlocking { app.cache.read(owner, "new-chat-attempt") })
            assertEquals(0, fixture.requests.count { it.string("method") == "session.create" })
            // Typing while Hermes is unreachable does not retry the create on every key.
            val refused = fixture.refusedConnections.get()
            compose.runOnIdle { listOf("h", "he", "hey").forEach { vm.composer(androidx.compose.ui.text.input.TextFieldValue(it)) } }
            Thread.sleep(1000); compose.waitForIdle()
            assertEquals(refused, fixture.refusedConnections.get())
            compose.runOnIdle { vm.composer(androidx.compose.ui.text.input.TextFieldValue()) }
            // Back online, the next try opens a chat.
            fixture.refuseConnection = false
            compose.runOnIdle { vm.newChat() }
            await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
            created += vm.chat.value.sessionId
            assertEquals(1, fixture.requests.count { it.string("method") == "session.create" })
            receipts += "A new chat that failed before anything was sent leaves no unconfirmed attempt: no Retry state, no marker, and the next try opens a chat."
        } finally {
            fixture.refuseConnection = false
            runBlocking {
                saved.forEach { (key, value) -> if (value == null) app.cache.delete(owner, key) else app.cache.write(owner, key, value, true) }
                created.forEach { id -> listOf("client-created-shell", "session", "draft", "pending").forEach { app.cache.delete(owner.session(id), it) } }
            }
        }
    }

    @Test fun aQuietRunningTurnIsAskedAboutLessAndLessOftenAndNeverSentAgain() = withFixture { vm, fixture ->
        field(vm, "streamSilenceMs", 800L)
        field(vm, "streamWatchMs", 200L)
        kotlinx.coroutines.runBlocking { app.cache.delete(fixture.scope.session("chat-a"), "live:runtime-chat-a") }
        val subscriptionsBeforeSend = fixture.streamSubscriptions.get()
        fixture.nativeTurnStatus = "working"
        compose.runOnIdle { vm.composer(TextFieldValue("A long quiet task")); vm.send() }
        await { fixture.requests.any { it.string("method") == "prompt.submit" } && vm.chat.value.streamId == "runtime-chat-a" }
        await { fixture.streamSubscriptions.get() > subscriptionsBeforeSend }
        val probesBefore = fixture.requests.count { it.string("method") == "session.active_list" }
        Thread.sleep(7_000)
        // Windows of 0.8 s, 1.6 s, 3.2 s: about three questions in seven seconds, not one every 0.8 s.
        val probes = fixture.requests.count { it.string("method") == "session.active_list" } - probesBefore
        assertTrue("Hermes is asked about the quiet turn (asked $probes times)", probes >= 1)
        assertTrue("Each answer of \"still working\" stretches the wait (asked $probes times)", probes <= 4)
        assertEquals("Asking never sends the prompt again", 1, fixture.requests.count { it.string("method") == "prompt.submit" })
        assertTrue("The turn is still shown as running", vm.chat.value.streamId.isNotBlank())
        receipts += "A quiet turn Hermes still runs is asked about less and less often, picked up again each time, and never sent twice."
    }

    @Test fun theBeaconChatNamedOnlyByTheCacheIsRestoredWhenTheAssistantProfileDiffersFromTheChats() = withFixture { vm, fixture ->
        val coder = fixture.scope.copy(profile = "coder")
        fun handle(): String? = BeaconViewModel::class.java.getDeclaredMethod("readBeaconSessionHandle", Scope::class.java).apply { isAccessible = true }.invoke(vm, coder) as String?
        runBlocking {
            app.preferences.edit { it[stringPreferencesKey("assistant_profile")] = "coder" }
            app.cache.write(coder, "beacon-session", "chat-b", protected = true)
        }
        try {
            await { vm.preferences.value.assistantProfile == "coder" }
            assertNull("Only the cache names the Beacon chat", compose.runOnIdle { handle() })
            compose.runOnIdle { vm.tab("Beacon") }
            await { vm.ui.value.tab == "Beacon" && vm.ui.value.profile == "coder" && !vm.ui.value.beaconOpening && vm.chat.value.projectionReady && vm.chat.value.sessionId == "chat-b" }
            assertEquals("The saved handle is restored from the cache", "chat-b", compose.runOnIdle { handle() })
            assertTrue("The Beacon screen trusts only that handle", vm.isBeaconConversation())
            receipts += "The Beacon chat the cache names is restored as the saved handle for an assistant profile other than the chats'."
        } finally {
            runBlocking {
                app.preferences.edit { it.remove(stringPreferencesKey("assistant_profile")); it.remove(stringPreferencesKey("assistant_return")) }
                listOf("beacon-session", "beacon-opening", "new-chat-shell", "selected-session").forEach { app.cache.delete(coder, it) }
            }
        }
    }

    @Test fun interruptedTurnKeepsLiveProgressAndQueuedDraftUntilTerminalReplay() = withFixture { vm, fixture ->
        kotlinx.coroutines.runBlocking { app.cache.delete(fixture.scope.session("chat-a"), "live:runtime-chat-a") }
        val subscriptionsBeforeSend = fixture.streamSubscriptions.get()
        compose.runOnIdle { fixture.persistTerminalMessages = true; vm.composer(TextFieldValue("First prompt")); vm.send() }
        await { fixture.requests.any { it.string("method") == "prompt.submit" } && vm.chat.value.streamId == "runtime-chat-a" }
        await { fixture.streamSubscriptions.get() > subscriptionsBeforeSend }
        fixture.emitNativeEvent("message.delta", json("text" to "Partial answer"))
        await { vm.chat.value.liveText == "Partial answer" }

        compose.runOnIdle { vm.composer(TextFieldValue("Queued follow-up")); vm.queue() }
        await { vm.chat.value.queued.singleOrNull()?.text == "Queued follow-up" }
        val subscriptionsBeforeReconnect = fixture.streamSubscriptions.get()
        fixture.nativeStatusFailure = true
        fixture.interruptActiveNativeStream()
        await { fixture.requests.any { it.string("method") == "session.active_list" } }
        await { fixture.streamSubscriptions.get() > subscriptionsBeforeReconnect }

        assertEquals("The interrupted runtime remains selected", "runtime-chat-a", vm.chat.value.streamId)
        assertEquals("Live text survives passive history without active_stream_id", "Partial answer", vm.chat.value.liveText)
        assertEquals("The queued draft is retained", "Queued follow-up", vm.chat.value.queued.single().text)
        assertTrue("The UI keeps explicit recovery uncertainty", vm.chat.value.status.contains("unknown", ignoreCase = true))
        assertFalse("An unproven outcome is not shown as complete", vm.chat.value.status.contains("complete", ignoreCase = true))
        assertEquals("No queued prompt is submitted on an unproven outcome", 1, fixture.requests.count { it.string("method") == "prompt.submit" })
        val activeList = fixture.requests.last { it.string("method") == "session.active_list" }.getJSONObject("params")
        assertEquals("runtime-chat-a", activeList.getString("current_session_id"))
        assertEquals(fixture.scope.profile, activeList.getString("profile"))

        fixture.nativeStatusFailure = false
        val requestsBeforeCompletion = fixture.requests.size
        fixture.terminalOnActiveList = json("text" to "Completed answer")
        fixture.interruptActiveNativeStream()
        await { fixture.requests.count { it.string("method") == "prompt.submit" } == 2 }
        val queuedSubmitIndex = fixture.requests.indexOfLast { it.string("method") == "prompt.submit" }
        assertTrue("The terminal history refresh happens before the queued prompt", fixture.requests.toList().subList(requestsBeforeCompletion, queuedSubmitIndex).any { it.string("path") == "/api/sessions/chat-a/messages" })
        assertTrue("The final assistant reply is visible before another history poll", vm.chat.value.messages.any { it.text == "Completed answer" })
        compose.onNodeWithText("Completed answer", useUnmergedTree = true).assertIsDisplayed()
        await { vm.chat.value.queued.isEmpty() }
        receipts += "A real synthetic WebSocket failure preserves partial progress and the queued follow-up while native status is unknown; a replayed terminal event persists the final assistant row, refreshes history immediately, and only then advances the queue."
    }

    @Test fun backgroundCompletedTurnSettlesOnResumeBeforeQueuedSend() = withFixture { vm, fixture ->
        val subscriptionsBefore = fixture.streamSubscriptions.get()
        compose.runOnIdle { vm.newChat() }
        await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
        // The fixture names the runtime it creates for a chat runtime-<id>; the REST projection carries no runtime id.
        val creatingRuntime = "runtime-" + vm.chat.value.sessionId
        fixture.persistTerminalMessages = true
        compose.runOnIdle { vm.composer(TextFieldValue("First prompt")); vm.send() }
        try { await { fixture.requests.any { it.string("method") == "prompt.submit" } && vm.chat.value.streamId.isNotBlank() } }
        catch (error: Throwable) { throw AssertionError("Send blocked: notice=${vm.ui.value.notice} error=${vm.ui.value.error} status=${vm.chat.value.status} raw=${vm.chat.value.raw.length()} ready=${vm.chat.value.projectionReady} stream=${vm.chat.value.streamId} id=${vm.chat.value.sessionId} requests=${recentCalls(fixture)}", error) }
        val firstRuntime = vm.chat.value.streamId
        assertEquals("The first prompt stays on the runtime that created this chat", creatingRuntime,
            fixture.requests.last { it.string("method") == "prompt.submit" }.getJSONObject("params").string("session_id"))
        @Suppress("UNCHECKED_CAST")
        val clientCreatedSessions = BeaconViewModel::class.java.getDeclaredField("clientCreatedSessions").apply { isAccessible = true }.get(vm) as Set<String>
        assertFalse("The creating-client marker ends after the first accepted prompt", clientCreatedSessions.contains(fixture.scope.session(vm.chat.value.sessionId).key))
        await { fixture.streamSubscriptions.get() > subscriptionsBefore }
        compose.runOnIdle { vm.composer(TextFieldValue("Queued follow-up")); vm.queue() }
        await { vm.chat.value.queued.singleOrNull()?.text == "Queued follow-up" }

        val observer = LifecycleEventObserver { _, event -> when (event) {
            androidx.lifecycle.Lifecycle.Event.ON_STOP -> vm.foreground(false)
            androidx.lifecycle.Lifecycle.Event.ON_START -> vm.foreground(true)
            else -> Unit
        } }
        compose.runOnUiThread { compose.activity.lifecycle.addObserver(observer) }
        try {
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            fixture.nativeTurnStatus = "idle"
            fixture.resumeRuntimeSessionIds[vm.chat.value.sessionId] = "$firstRuntime-resumed"
            fixture.retainNativeEvent(firstRuntime, "message.complete", json("text" to "Completed while backgrounded"))
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)

            await { vm.chat.value.messages.any { it.text == "Completed while backgrounded" } &&
                fixture.requests.count { it.string("method") == "prompt.submit" } == 2 && vm.chat.value.queued.isEmpty() }
            compose.onNodeWithText("Completed while backgrounded", useUnmergedTree = true).assertIsDisplayed()

            await { fixture.streamSubscriptions.get() > subscriptionsBefore + 1 }
            fixture.nativeTurnStatus = "idle"
            fixture.emitNativeEvent("message.complete", json("text" to "Queued follow-up completed"))
            fun followUpSettled() = vm.chat.value.messages.any { it.text == "Queued follow-up completed" } && vm.chat.value.streamId.isBlank()
            // The fixture pushes a live event only to the socket subscribed at that instant; if the app was re-subscribing,
            // the reply is still persisted, so foreground recovery (the behaviour under test above) must settle it.
            if (!runCatching { compose.waitUntil(5_000) { followUpSettled() } }.isSuccess) {
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            }
            try { await { followUpSettled() } }
            catch (error: Throwable) {
                throw AssertionError("Queued follow-up: stream=${vm.chat.value.streamId} status=${vm.chat.value.status} texts=${vm.chat.value.messages.map { it.text.take(30) }} live=${vm.chat.value.liveText.take(40)} active=${fixture.activeRuntimeForTest()} requests=${recentCalls(fixture)}", error)
            }
            compose.onNode(inConversation(hasText("Queued follow-up completed")), useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("message-input").performClick().performTextInput("Send after recovery")
            compose.onNodeWithTag("message-send").assertIsEnabled().performClick()
            await { fixture.requests.count { it.string("method") == "prompt.submit" } == 3 }
            assertEquals("The first accepted prompt releases the creating-client marker for later sends", "$firstRuntime-resumed",
                fixture.requests.last { it.string("method") == "prompt.submit" }.getJSONObject("params").string("session_id"))
        } finally { compose.runOnUiThread { compose.activity.lifecycle.removeObserver(observer) } }
        receipts += "A first prompt remains owner-bound to its creating runtime; terminal idle from a resumed runtime after Activity stop restores history, advances the queued prompt, and returns Send after completion."
    }

}

/** A node in the open conversation, not the chat list beside it (which previews each chat's latest message). */
private val inChatList = SemanticsMatcher("in the chat list") {
    it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.let { tag -> tag.startsWith("history-row") || tag == "history-beacon-row" } == true
}
internal fun inConversation(matcher: SemanticsMatcher): SemanticsMatcher = matcher and !inChatList and !hasAnyAncestor(inChatList)
