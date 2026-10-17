package dev.hermes.android

import android.graphics.Bitmap
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import dev.hermes.android.data.startupReads
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
    private val app get() = ApplicationProvider.getApplicationContext<CypherApplication>()
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

    @Suppress("UNCHECKED_CAST") private fun <T> flow(vm: CypherViewModel, name: String) =
        CypherViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
    private fun field(vm: CypherViewModel, name: String, value: Any?) {
        CypherViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
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
    private fun withFixture(pickerRegistry: ActivityResultRegistry? = null, restoration: StateRestorationTester? = null, block: (CypherViewModel, NativeExperienceFixture) -> Unit) {
        check(args.getString("native_ui_probe") == "1") { "Explicit synthetic UI probe flag required" }
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        prepareWindow()
        val originKey = stringPreferencesKey("origin")
        val motionKey = stringPreferencesKey("reduced_motion")
        val prior = runBlocking { app.preferences.data.first() }
        runBlocking { app.preferences.edit { it[originKey] = ""; it[motionKey] = "true" } }
        val store = ViewModelStore()
        val fixture = NativeExperienceFixture()
        runBlocking {
            for (key in startupReads.map { "read:${it.path}" } + listOf("read-invalidated-at", "settings:skills", "settings:memory")) app.cache.delete(fixture.scope, key)
        }
        val priorChatPreferences = runBlocking { app.cache.read(fixture.scope, "chat-preferences") }
        runBlocking { app.cache.write(fixture.scope, "chat-preferences", json("render_user_markdown" to true, "show_thinking" to true, "send_key" to "enter").toString(), true) }
        runBlocking { app.cache.delete(fixture.scope, "bot-mode-return"); app.cache.delete(fixture.scope, "xara-session"); app.cache.delete(fixture.scope, "xara-opening"); app.cache.delete(fixture.scope, "phone-editors"); app.cache.delete(fixture.scope, "editor:C:/synthetic-workspace/notes.md") }
        lateinit var vm: CypherViewModel
        try {
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { CypherViewModel(app, SavedStateHandle()) } })[CypherViewModel::class.java] }
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "sourceScope", fixture.scope.session("chat-a"))
                app.calls.ownerScope = fixture.scope
                flow<AppState>(vm, "_ui").value = AppState(origin = fixture.scope.origin, authenticated = true, owner = fixture.scope.owner, connected = true, restoringSession = false,
                    bootstrap = json("voice" to json("available" to false)), sessions = fixture.sessions.keys.sorted().map { fixture.snapshot(it) })
                val snapshot = fixture.snapshot("chat-a")
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = "chat-a", title = "Travel planning", raw = snapshot,
                    messages = snapshot.rows("messages").mapIndexed { index, row -> Message.from(row, index, "chat-a") })
                vm.storeData("profile-settings", json("render_user_markdown" to true, "show_thinking" to true, "send_key" to "enter"))
            }
            val content: @Composable () -> Unit = {
                val settings by vm.data.collectAsState()
                val density = LocalDensity.current
                val font = args.getString("native_ui_font", "1.0").orEmpty().toFloatOrNull() ?: 1f
                CompositionLocalProvider(LocalDensity provides Density(density.density, font), LocalCypherSettings provides (settings["profile-settings"] ?: JSONObject()), LocalActivityResultRegistryOwner provides (pickerRegistry?.let { registry -> object : ActivityResultRegistryOwner { override val activityResultRegistry = registry } } ?: requireNotNull(LocalActivityResultRegistryOwner.current))) {
                    NativeLocalization("en") { CypherTheme(dark = args.getString("native_ui_theme") != "light") {
                        NativeWindowLayout(compose.activity) { Box(Modifier.fillMaxSize().testTag("experience-viewport")) { CypherRoot(vm) } }
                    } }
                }
            }
            if (restoration == null) compose.setContent(content) else restoration.setContent(content)
            block(vm, fixture)
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative(); app.calls.ownerScope = null }
            runBlocking { app.preferences.edit {
                prior[originKey]?.let { value -> it[originKey] = value } ?: it.remove(originKey)
                prior[motionKey]?.let { value -> it[motionKey] = value } ?: it.remove(motionKey)
                for (name in listOf("theme", "skin", "font_size", "language", "rtl")) {
                    val key = stringPreferencesKey(name); prior[key]?.let { value -> it[key] = value } ?: it.remove(key)
                }
            } }
            runBlocking {
                app.auth.remove(fixture.scope.origin)
                if (priorChatPreferences == null) app.cache.delete(fixture.scope, "chat-preferences") else app.cache.write(fixture.scope, "chat-preferences", priorChatPreferences, true)
            }
            runBlocking { app.cache.delete(fixture.scope, "bot-mode-return"); app.cache.delete(fixture.scope, "xara-session"); app.cache.delete(fixture.scope, "xara-opening"); app.cache.delete(fixture.scope, "phone-editors"); app.cache.delete(fixture.scope, "editor:C:/synthetic-workspace/notes.md") }
        }
    }
    private fun clickText(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().performClick()
    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)

    private class PickerRegistry(private val context: Context) : ActivityResultRegistry() {
        var code = -1
        var intent: Intent? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            code = requestCode; intent = contract.createIntent(context, input)
        }
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
        compose.onNodeWithText("Refresh projects").performScrollTo().assertIsEnabled()
        receipts += "Restored Projects reads the installed native client after same-owner reconnect without requiring page re-entry."
        capture("audit-reconnect-projects")
    }

    @Test fun reconnectingDuringAToolWriteDoesNotLeaveControlsDisabled() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.more("Tools & commands") }
        await { vm.data.value["native-tools"] != null && "native-tools" !in vm.refreshing.value }
        fixture.holdNextToolOperation.set(true)
        try {
            compose.onNodeWithContentDescription("Web research").performScrollTo().performClick()
            assertTrue(fixture.toolOperationCaptured.await(5, java.util.concurrent.TimeUnit.SECONDS))
            compose.runOnIdle {
                field(vm, "client", fixture.client.detached())
                flow<AppState>(vm, "_ui").value = vm.ui.value.copy(notice = "Connection restored")
            }
            await { "native-tools" !in vm.refreshing.value }
            compose.onNodeWithText("Refresh").performScrollTo().assertIsEnabled()
            fixture.releaseToolOperation.countDown()
            compose.onNodeWithText("Refresh").performClick()
            await { "native-tools" !in vm.refreshing.value }
            receipts += "A replacement client resets only its own operation latch; old completion cannot disable the new tool controls."
            capture("audit-reconnect-tools")
        } finally { fixture.releaseToolOperation.countDown() }
    }

    @Test fun commandsLeaveTheirPickerAndExplicitRefreshReadsTheServer() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.openCatalog("Toolsets") }
        await { vm.data.value["native-tools"] != null }
        clickText("Browse chat commands")
        await { vm.catalog.value.isBlank() && vm.ui.value.morePage == "Commands" && vm.data.value["slash:catalog"] != null }
        val before = fixture.requests.count { it.string("method") == "commands.catalog" }
        clickText("Refresh commands")
        await { fixture.requests.count { it.string("method") == "commands.catalog" } > before }
        compose.onNodeWithContentDescription("Back").performClick()
        await { vm.ui.value.tab == "Chat" }
        receipts += "Browse commands closes Toolsets; explicit Refresh bypasses metadata freshness and Back returns to Chat."
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
        val pending = CypherViewModel::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(vm) as MutableMap<String, PendingSend>
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
        fun approval(id: String) = json("approval_id" to id, "command" to "Synthetic $id", "choices" to JSONArray().put("once").put("deny"))
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(approval = approval("old-approval")) }
        val staleApproval = requireNotNull(compose.onNodeWithText("Allow once").performScrollTo().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        compose.runOnIdle {
            flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(approval = approval("new-approval"))
            assertTrue(staleApproval())
            assertTrue(vm.ui.value.notice.contains("request changed"))
        }
        compose.waitForIdle()
        assertTrue(fixture.requests.none { it.string("method") == "approval.respond" })
        compose.runOnIdle { vm.clearMessage() }
        await { compose.onAllNodesWithText("This request changed. Refresh it before responding.").fetchSemanticsNodes().isEmpty() }
        await { compose.onAllNodesWithText("Synthetic new-approval").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Allow once").performScrollTo().performClick()
        await { fixture.requests.any { it.string("method") == "approval.respond" } }
        assertEquals("new-approval", fixture.requests.single { it.string("method") == "approval.respond" }.getJSONObject("params").string("request_id"))
        await { vm.chat.value.approval == null }
        fun question(id: String) = json("clarify_id" to id, "question" to "Synthetic $id", "choices" to JSONArray().put("Choose this"))
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(approval = null, clarification = question("old-question")) }
        val staleAnswer = requireNotNull(compose.onNodeWithText("Choose this").performScrollTo().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        compose.runOnIdle {
            flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(clarification = question("new-question"))
            assertTrue(staleAnswer())
            assertTrue(vm.ui.value.notice.contains("request changed"))
        }
        compose.waitForIdle()
        assertTrue(fixture.requests.none { it.string("method") == "request.answer" })
        compose.runOnIdle { vm.clearMessage() }
        await { compose.onAllNodesWithText("This request changed. Refresh it before responding.").fetchSemanticsNodes().isEmpty() }
        await { compose.onAllNodesWithText("Synthetic new-question").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Choose this").performScrollTo().performClick()
        await { fixture.requests.any { it.string("method") == "request.answer" } }
        assertEquals("new-question", fixture.requests.single { it.string("method") == "request.answer" }.getJSONObject("params").string("id"))
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
        clickText("Pause"); await { fixture.jobs.getValue("task-a").optBoolean("paused") }
        compose.waitForIdle(); clickText("Resume"); await { !fixture.jobs.getValue("task-a").optBoolean("paused") }
        clickText("Run now"); await { vm.form.value != null }
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(fixture.requests.any { it.string("path").endsWith("/trigger") })
        clickText("Run now"); await { vm.form.value != null }
        compose.onAllNodesWithText("Run now", useUnmergedTree = true).onLast().performClick()
        await { vm.form.value == null && fixture.requests.any { it.string("path").endsWith("/trigger") } }
        clickText("Run history"); await { vm.data.value["cron-history:task-a"] != null }
        clickText("Synthetic completed run"); await { vm.chat.value.sessionId == "chat-b" }
        compose.runOnIdle { vm.tab("Tasks") }
        clickText("Delete"); await { vm.form.value != null }
        compose.onAllNodesWithText("Delete", useUnmergedTree = true).onLast().performClick()
        await { fixture.jobs.isEmpty() && vm.form.value == null }
        clickText("Create schedule"); await { vm.form.value != null }
        compose.runOnIdle { vm.formValue("name", "Owned schedule fixture"); vm.formValue("prompt", "Summarize the synthetic workspace"); vm.formValue("schedule", "every 2h") }
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        await { fixture.jobs.containsKey("task-new") && vm.form.value == null }
        assertEquals("Owned schedule fixture", fixture.jobs.getValue("task-new").string("name"))
        clickText("Call and email tasks")
        compose.onNodeWithText("Create check-up task").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Create vetted mail task").performScrollTo().assertIsNotEnabled()
        receipts += "Schedule create, pause, resume, run/cancel, history/result and delete use actual controls; unavailable call/mail prerequisites disable their actions."
        capture("audit-schedule-workflow")
    }

    @Test fun kanbanMoveEditAndDeletingTheLastArchivedCardKeepAValidColumn() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.navigate("Kanban") }
        await { compose.onAllNodesWithText("Review travel options").fetchSemanticsNodes().isNotEmpty() }
        clickText("Edit")
        compose.onNodeWithTag("kanban-title").performTextReplacement("Review the itinerary")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        await { fixture.boardTasks.getValue("t_fixture").string("title") == "Review the itinerary" }
        clickText("Move")
        compose.onNode(hasText("Archived") and hasAnyAncestor(isPopup())).performScrollTo().performClick()
        compose.onAllNodesWithText("Move", useUnmergedTree = true).onLast().performClick()
        await { fixture.boardTasks.getValue("t_fixture").string("status") == "archived" }
        clickText("Delete")
        compose.onAllNodesWithText("Delete", useUnmergedTree = true).onLast().performClick()
        await { fixture.boardTasks.isEmpty() }
        compose.onAllNodes(hasText("Archived", substring = true)).assertCountEquals(1).onFirst().assertIsNotDisplayed()
        compose.onNode(hasText("Triage 0") and isSelectable()).assertIsSelected()
        receipts += "Kanban edit and archive use native receipts; deleting the final archived card selects an existing column without a stranded page."
        capture("audit-kanban-lifecycle")
    }

    @Test fun kanbanRetryKeepsTheServerSelectedBoard() = withFixture { vm, fixture ->
        fixture.boardFailure = true
        fixture.preferredBoard = "work"
        compose.runOnIdle { vm.navigate("Kanban") }
        await { compose.onAllNodesWithText("Boards are offline").fetchSemanticsNodes().isNotEmpty() }
        fixture.boardFailure = false
        compose.onNodeWithText("Retry", useUnmergedTree = true).assertIsDisplayed().performClick()
        await { compose.onAllNodesWithText("Work").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Personal").assertCountEquals(0)
        await { compose.onAllNodesWithText("Review travel options").fetchSemanticsNodes().isNotEmpty() }
        receipts += "Recovering an offline Kanban catalog selects the native current board, with shared retry ownership and error handling."
        capture("audit-kanban-retry")
    }

    @Test fun sharedSpaceCreationSendRetryApprovalAndTaskRecoveryKeepTheirIdentity() = withFixture { vm, fixture ->
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
        compose.onNodeWithText("Send").performClick()
        await { !vm.botMode.state.value.loading && vm.botMode.state.value.error.isNotBlank() }
        assertEquals("Plan the synthetic review", vm.botMode.state.value.draft)
        fixture.rejectRoomSend = false
        compose.onNodeWithText("Send").performClick()
        await { vm.botMode.state.value.draft.isBlank() && !vm.botMode.state.value.loading }
        val sends = fixture.requests.filter { it.string("path").endsWith("/messages") }
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
        compose.onNodeWithText("Retry bot task").performScrollTo().performClick()
        await { fixture.roomActions.isEmpty() && !vm.botMode.state.value.loading }
        assertEquals("retry", fixture.requests.last { it.string("path").endsWith("/action") }.getJSONObject("body").string("kind"))
        receipts += "Shared-space creation and messages use stable identity across a failed attempt; allow/deny and explicit task retry bind to the displayed native request."
        capture("audit-shared-space-actions")
    }

    @Test fun messageCopyShareAndBothBranchActionsUseTheSelectedMessage() = withFixture { vm, fixture ->
        val original = vm.chat.value.messages.first()
        fun action(label: String) = compose.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("message-actions:${original.id}")))
        action("More message actions").performScrollTo().performClick()
        compose.onNodeWithText("Copy source").performClick()
        val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals(original.text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        action("Share text").performScrollTo().performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        await { automation.rootInActiveWindow?.packageName?.toString()?.let { it != app.packageName } == true }
        assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitForIdle()
        action("More message actions").performScrollTo().performClick()
        compose.onNodeWithText("Branch from here").performClick()
        await { vm.chat.value.sessionId.startsWith("branch-") }
        assertEquals(1, fixture.requests.last { it.string("method") == "session.branch" }.getJSONObject("params").getInt("count"))
        compose.runOnIdle { vm.selectSession("chat-a") }
        await { vm.chat.value.sessionId == "chat-a" && vm.chat.value.messages.isNotEmpty() }
        action("More message actions").performScrollTo().performClick()
        compose.onNodeWithText("Edit and branch").performClick()
        await { vm.chat.value.sessionId.startsWith("branch-") && vm.composer.value.text == original.text }
        assertEquals(0, fixture.requests.last { it.string("method") == "session.branch" }.getJSONObject("params").getInt("count"))
        assertFalse(fixture.requests.any { it.string("method") == "prompt.submit" })
        receipts += "Copy source preserves the selected message; system Share opens and cancels without sending; branch and edit/branch use exact native message boundaries."
        capture("audit-message-actions")
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
        compose.onNodeWithText("Read test PDF").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
        val linkPosition = textLayouts.single().getBoundingBox(2).center
        compose.onNodeWithText("Read test PDF").performTouchInput { click(linkPosition) }
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
        compose.onNodeWithText("Tree").performScrollTo().performClick()
        compose.onNodeWithText("Source").performClick()
        compose.onNodeWithText("Tree").assertIsDisplayed()
        receipts += "Native PDF page navigation reaches both pages and disables boundaries; its dialog closes, and JSON switches between source and tree."
    }

    @Test fun pcFileCreationAndSystemDownloadKeepTheExactBytes() {
        val picker = PickerRegistry(app)
        withFixture(picker) { vm, fixture ->
            val file = File(app.cacheDir, "exports/audit-downloaded.txt").apply { parentFile!!.mkdirs(); writeText("") }
            try {
                compose.runOnIdle { vm.navigate("Files") }
                await { vm.data.value["files"] != null }
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
            try {
                compose.onNodeWithTag("composer-attach").performClick()
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent?.action)
                assertTrue(picker.intent!!.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
                compose.runOnUiThread { picker.deliver(listOf(uri(one), uri(two), uri(one))) }
                await { vm.attachments.value.size == 2 && vm.attachmentPreparation.value.isEmpty() }
                assertEquals(setOf(one.name, two.name), vm.attachments.value.map { it.string("name") }.toSet())
                assertEquals(setOf("First selected file", "Second selected file"), vm.attachments.value.map { String(android.util.Base64.decode(it.string("staged_base64"), android.util.Base64.DEFAULT)) }.toSet())
                compose.onNodeWithTag("composer-attachments").assertIsDisplayed()
                capture("multiple-attachment-chips")
                compose.onNodeWithContentDescription("Remove ${one.name}").performScrollTo().performClick()
                await { vm.attachments.value.size == 1 }
                assertEquals(two.name, vm.attachments.value.single().string("name"))
                compose.onNodeWithTag("composer-attach").performClick()
                compose.runOnUiThread { picker.deliver(emptyList()) }
                assertEquals(1, vm.attachments.value.size)
                compose.onNodeWithTag("composer-attach").performClick()
                compose.runOnUiThread { picker.deliver(listOf(uri(one), Uri.parse("content://missing-fixture/not-found"))) }
                await { vm.attachments.value.size == 2 && vm.attachmentPreparation.value.any { it.string("status") == "error" } }
                assertTrue(vm.attachmentPreparation.value.single().string("error").isNotBlank())
                compose.runOnIdle { vm.dismissAttachmentPreparation(vm.attachmentPreparation.value.single().string("id")) }
                compose.onNodeWithTag("composer-attach").performClick()
                compose.runOnIdle { vm.selectSession("chat-b") }
                await { vm.chat.value.sessionId == "chat-b" }
                compose.runOnUiThread { picker.deliver(listOf(uri(one), uri(two))) }
                compose.waitForIdle()
                assertTrue(vm.attachments.value.isEmpty())
                assertTrue(vm.attachmentPreparation.value.isEmpty())
                assertFalse(fixture.requests.any { it.string("method") == "prompt.submit" })
                receipts += "System multi-document result preserves each file, deduplicates selection, supports removal/cancel/failure and rejects stale conversation results. No prompt sent."
            } finally { one.delete(); two.delete() }
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
                fixture.releaseFileList.countDown()
                await { vm.data.value["files"]?.rows("entries")?.map { it.string("name") }?.containsAll(files.map { it.name }) == true }
                files.forEach { compose.onNodeWithText(it.name).assertExists() }
                receipts += "Workspace multi-selection refreshes after both writes, even when an older listing is in flight."
                capture("workspace-multiple-files")
            } finally { fixture.releaseFileList.countDown(); files.forEach { it.delete() } }
        }
    }

    @Test fun approvalsUseNativeModesAndOffRequiresConfirmation() = withFixture { vm, fixture ->
        fun open() {
            await { compose.onAllNodes(hasTestTag("composer-approvals") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("composer-approvals").performClick()
            try { await { vm.data.value["picker:approvals"]?.string("value") in dev.hermes.android.data.nativeApprovalModes && "picker:approvals" !in vm.refreshing.value } }
            catch (error: Throwable) {
                capture("approval-read-failure")
                val pending = CypherViewModel::class.java.getDeclaredField("loads").apply { isAccessible = true }.get(vm) as Map<*, *>
                throw AssertionError("Approval read: record=${vm.data.value["picker:approvals"]}, refreshing=${vm.refreshing.value}, active=${(pending["picker:approvals"] as? kotlinx.coroutines.Job)?.isActive}, error=${vm.ui.value.error}", error)
            }
        }
        open()
        val smart = compose.onNodeWithTag("approval-mode-smart").performScrollTo().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { smart(); smart() }
        await { fixture.approvalMode == "smart" && compose.onAllNodesWithTag("approvals-menu").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, fixture.requests.count { it.string("method") == "config.set" && it.optJSONObject("params")?.string("key") == "approvals.mode" })
        open();compose.onNodeWithTag("approval-mode-off").performScrollTo().performClick()
        compose.onNodeWithText("Turn off Hermes approvals?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("smart", fixture.approvalMode)
        open();compose.onNodeWithTag("approval-mode-off").performScrollTo().performClick()
        compose.onNodeWithText("Turn off", substring = false).performClick()
        await { fixture.approvalMode == "off" }
        open();capture("native-approvals-modes")
        fixture.rejectApprovalWrite = true
        compose.onNodeWithTag("approval-mode-manual").performScrollTo().performClick()
        await { vm.ui.value.error.isNotBlank() && compose.onAllNodesWithTag("approvals-menu").fetchSemanticsNodes().isEmpty() }
        assertEquals("off", fixture.approvalMode)
        fixture.rejectApprovalWrite = false
        open();compose.onNodeWithTag("approval-mode-manual").performScrollTo().performClick()
        await { fixture.approvalMode == "manual" }
        open();compose.onNodeWithTag("approval-mode-off").performScrollTo().performClick()
        val before = fixture.requests.count { it.string("method") == "config.set" }
        compose.runOnIdle { vm.selectSession("chat-b") }
        await { vm.chat.value.sessionId == "chat-b" }
        compose.onNodeWithText("Turn off Hermes approvals?").assertDoesNotExist()
        assertEquals(before, fixture.requests.count { it.string("method") == "config.set" })
        receipts += "Approvals use native Manual/Smart/Off values, Off requires confirmation, failed saves keep the real value, and changing conversation cancels stale confirmation."
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
        fun toggle() {
            compose.onNodeWithTag(if (vm.ui.value.botModeEnabled) "bot-mode-sidebar" else "navigation-menu").performScrollToNode(hasTestTag("navigation-bots"))
            compose.onNodeWithTag("navigation-bots").performClick()
        }
        fun coder() {
            compose.onNodeWithTag("bot-mode-sidebar").performScrollToNode(hasTestTag("bot-row-coder"))
            compose.onNodeWithTag("bot-row-coder").performClick()
        }
        compose.runOnIdle { vm.composer(TextFieldValue("Normal chat draft")); vm.toggleSessions(true) }
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
        compose.onNodeWithContentDescription("New conversation").assertDoesNotExist()
        compose.runOnIdle { vm.composer(TextFieldValue("Coder bot draft")); vm.toggleSessions(true) }
        toggle()
        await { !vm.ui.value.botModeEnabled && !vm.ui.value.botModeSwitching }
        assertEquals("default", vm.ui.value.profile)
        assertEquals("chat-a", vm.chat.value.sessionId)
        assertEquals("Normal chat draft", vm.composer.value.text)
        assertEquals("Travel", vm.ui.value.query)
        compose.onNodeWithTag("navigation-bots").assertIsOff()
        compose.onNodeWithTag("navigation-menu").performScrollToNode(hasText("Travel"))
        compose.onNodeWithText("Travel", substring = false).assertExists()
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

    @Test fun referenceNavigationAndNativeBoardControls() = withFixture { vm, fixture ->
        runBlocking { app.cache.delete(fixture.scope, "kanban-draft:default") }
        compose.runOnIdle { vm.toggleSessions(true) }
        compose.onNodeWithTag("navigation-profile").assertIsDisplayed().performClick()
        await { vm.data.value["navigation-profiles"] != null }
        capture("reference-profiles")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("navigation-kanban").performScrollTo().performClick()
        await { compose.onAllNodesWithText("Review travel options").fetchSemanticsNodes().isNotEmpty() }
        capture("reference-kanban")
        compose.onNodeWithContentDescription("New task").performClick()
        compose.onNodeWithTag("kanban-title").performTextInput("Prepare the itinerary")
        compose.onNodeWithText("Keep draft").performClick()
        compose.onNodeWithText("Continue saved task draft").performClick()
        compose.onNodeWithTag("kanban-title").assertTextContains("Prepare the itinerary")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        await { fixture.boardTasks["t_new"] != null && compose.onAllNodesWithTag("kanban-title").fetchSemanticsNodes().isEmpty() }
        assertEquals("Prepare the itinerary", fixture.boardTasks.getValue("t_new").string("title"))
        val submit = fixture.requests.single { it.string("path") == "/api/plugins/kanban/tasks" && it.string("method") == "POST" }
        assertTrue(submit.getJSONObject("body").string("idempotency_key").startsWith("cypher-"))
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
                if (view is android.webkit.WebView && view.contentDescription == if (diagram) "Mermaid diagram preview" else "Isolated HTML card") return view
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
        compose.onNodeWithTag("message-actions:11").performScrollTo()
        compose.onNode(hasContentDescription("Copy formatted message") and hasAnyAncestor(hasTestTag("message-actions:11"))).assertIsDisplayed().performClick()
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
        await {
            val decor = compose.activity.window.decorView
            val bottom = androidx.core.view.ViewCompat.getRootWindowInsets(decor)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
            val keyboardTop = decor.height - bottom
            compose.onNodeWithTag("message-input").fetchSemanticsNode().boundsInWindow.bottom <= keyboardTop + 2 &&
                compose.onNodeWithTag("message-send").fetchSemanticsNode().boundsInWindow.bottom <= keyboardTop + 2
        }
        receipts += "Android reports the software keyboard visible while the composer stays reachable."
        val inputBounds = compose.onNodeWithTag("message-input").fetchSemanticsNode().boundsInWindow
        val sendBounds = compose.onNodeWithTag("message-send").fetchSemanticsNode().boundsInWindow
        assertTrue("Primary action is oval and below the text area", sendBounds.width > sendBounds.height && sendBounds.top >= inputBounds.bottom)
        val attachBounds = compose.onNodeWithTag("composer-attach").fetchSemanticsNode().boundsInWindow
        val permissionBounds = compose.onNodeWithTag("composer-approvals").fetchSemanticsNode().boundsInWindow
        val modelBounds = compose.onNodeWithTag("composer-model-effort").fetchSemanticsNode().boundsInWindow
        assertTrue("Composer controls retain their requested reading order", attachBounds.right <= permissionBounds.left && permissionBounds.right <= modelBounds.left && modelBounds.right <= sendBounds.left)
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
        compose.onNodeWithTag("message-send").assertIsEnabled().assertIsDisplayed()
        val viewport = compose.onNodeWithTag("experience-viewport").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag("message-send").fetchSemanticsNode().boundsInRoot
        assertTrue("Queue must stay inside the usable window", send.left >= viewport.left && send.right <= viewport.right && send.bottom <= viewport.bottom)
        compose.onNodeWithTag("message-send").performClick()
        assertEquals(1, vm.chat.value.queued.size)
        assertTrue(vm.composer.value.text.isBlank())
        assertTrue("Queue must not dispatch another prompt", fixture.requests.none { it.string("method") == "prompt.submit" })
        receipts += "Busy primary queues locally; no prompt dispatched."
        capture("busy-composer")
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
        compose.runOnIdle { vm.tab("Xara") }
        await { fixture.requests.count { it.string("method") == "session.create" } == 1 }
        compose.runOnIdle { repeat(50) { vm.openXara() } }
        fixture.releaseCreateAcknowledgement()
        await { !vm.ui.value.xaraOpening && vm.chat.value.sessionId.startsWith("new-") }
        assertEquals(1, fixture.requests.count { it.string("method") == "session.create" })
        assertTrue(vm.ui.value.xaraError.isBlank())
        receipts += "Fifty repeated Cypher opens while create ACK is delayed resolve one durable conversation."
        capture("stable-cypher")
    }

    @Test fun deletedInitialConversationKeepsDraftAndNewChatRecovers() = withFixture { vm, fixture ->
        @Suppress("UNCHECKED_CAST")
        val shells = CypherViewModel::class.java.getDeclaredField("initialShells").apply { isAccessible = true }.get(vm) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val attempts = CypherViewModel::class.java.getDeclaredField("initialCreateAttempts").apply { isAccessible = true }.get(vm) as MutableMap<String, String>
        try {
            compose.runOnIdle {
                field(vm, "sourceScope", null)
                flow<ChatState>(vm, "_chat").value = ChatState()
                flow<AppState>(vm, "_ui").value = vm.ui.value.copy(tab = "More", morePage = "Cypher", initialChatReady = true)
                flow<TextFieldValue>(vm, "_composer").value = TextFieldValue("Retain my unfinished draft")
                shells[fixture.scope.key] = "deleted-shell"
                attempts[fixture.scope.key] = "prior-read-failure"
                vm.newChat(json("title" to "Recovered draft"))
            }
            await { vm.chat.value.sessionId.startsWith("new-") && !vm.ui.value.newChatOpening }
            assertEquals("Retain my unfinished draft", vm.composer.value.text)
            assertEquals(1, fixture.requests.count { it.string("method") == "session.create" })
            assertFalse(attempts.containsKey(fixture.scope.key))
            assertTrue(vm.ui.value.newChatError.isBlank())
            receipts += "Missing native shell code 4007 clears only stale pointers; explicit New chat keeps the draft and creates once."
        } finally {
            runBlocking { app.cache.delete(fixture.scope, "new-chat-shell"); app.cache.delete(fixture.scope, "new-chat-attempt"); app.cache.delete(fixture.scope, "initial-draft") }
        }
    }

    @Test fun scheduleEditUsesOnlyEditableFieldsAndPreservesFailedDraft() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.tab("Tasks") }
        await { vm.data.value["crons"] != null }
        compose.onNodeWithText("Morning summary").assertExists()
        capture("tasks")
        clickText("Edit")
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
        clickText("Edit")
        await { vm.form.value != null }
        compose.runOnIdle { vm.formValue("name", "An unsaved draft"); vm.dismissForm() }
        clickText("Edit")
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
        capture("settings")
        compose.runOnIdle { vm.more("Settings") }
        compose.onNodeWithText("Unavailable in native Hermes").assertDoesNotExist()
        capture("chat-preferences")
        compose.runOnIdle { vm.more("Notifications") }
        await { vm.data.value["push-status"] != null }
        compose.onNodeWithText("Proactive calls").assertDoesNotExist()
        val notifications = app.getSystemService(android.app.NotificationManager::class.java)
        listOf("hermes-messages", "hermes-tasks", "hermes-approvals", "hermes-updates").forEach { assertNotNull("Advertised notification category exists: $it", notifications.getNotificationChannel(it)) }
        assertTrue(fixture.requests.none { it.string("path") == "/api/mobile/proactive" })
        receipts += "Local chat preferences are reachable; notification settings do not schedule proactive calls."
        capture("notification-categories")
    }

    @Test fun callControlsExposeStateWithoutStartingMedia() {
        check(args.getString("native_ui_probe") == "1")
        prepareWindow()
        var call by mutableStateOf(CallState(active = true, state = "listening", routes = listOf(AudioRoute("speaker", "Speaker")), route = "speaker"))
        var ended = false
        compose.setContent { CypherTheme(dark = args.getString("native_ui_theme") != "light") {
            LiveCallSurface(call, CallUiActions(mute = { call = call.copy(muted = !call.muted) }, camera = {}, flip = {}, allowBubble = {}, end = { ended = true }, route = {}, screen = {}), false, Modifier.fillMaxSize(), onConversation = {}) { Box(it) }
        } }
        compose.onNodeWithTag("xara-mute").assertIsDisplayed().performClick()
        assertTrue(call.muted)
        compose.onNodeWithTag("xara-mute").assertContentDescriptionEquals("Unmute")
        receipts += "Actual call control toggles synthetic mute; no sensor, service or provider."
        capture("call-muted")
        compose.runOnIdle { call=call.copy(state="connecting",held=true) }
        compose.onNodeWithTag("xara-camera").assertIsNotEnabled()
        compose.onNodeWithTag("cypher-share-screen").assertIsNotEnabled()
        compose.runOnIdle { call=call.copy(state="listening", held=false, cameraStopPending=true) }
        compose.onNodeWithTag("cypher-share-screen").assertIsNotEnabled()
        compose.onNodeWithTag("xara-camera").assertIsEnabled()
        compose.runOnIdle { call=call.copy(cameraStopPending=false,screenStopPending=true) }
        compose.onNodeWithTag("cypher-share-screen").assertIsEnabled()
        compose.onNodeWithTag("xara-hang-up").performClick(); assertTrue(ended)
    }
    @Test fun fileDraftNeverOverwritesNewerFileAndDiscardCannotResurrect() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.navigate("Files") }
        await { compose.onAllNodesWithText("notes.md").fetchSemanticsNodes().isNotEmpty() }
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
        compose.onNodeWithText("Search settings").performTextInput("Chat preferences")
        compose.onNode(hasText("Chat preferences") and hasClickAction() and !hasSetTextAction()).assertExists()
        compose.onNode(hasSetTextAction() and hasText("Chat preferences")).performTextReplacement("Models & tools")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNode(hasText("Models & tools") and hasClickAction() and !hasSetTextAction()).performClick()
        await { vm.ui.value.morePage == "category:Models & tools" }
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
            lateinit var recreated: CypherViewModel
            compose.runOnUiThread { recreated=ViewModelProvider(recreatedStore,viewModelFactory { initializer { CypherViewModel(app,SavedStateHandle()) } })[CypherViewModel::class.java];field(recreated,"client",fixture.client) }
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
        for (page in listOf("Skills", "Memory", "Bots", "Files", "Cron", "Projects", "Kanban")) {
            compose.runOnIdle { vm.navigate(page) }
            compose.onNodeWithText("Reconnect to Hermes").assertIsDisplayed()
            compose.onAllNodesWithText("Reconnect").fetchSemanticsNodes().also { assertTrue(it.isNotEmpty()) }
        }
        compose.runOnIdle { field(vm,"client",fixture.client) }
        @Suppress("UNCHECKED_CAST")
        val state=CallController::class.java.getDeclaredField("_state").apply { isAccessible=true }.get(app.calls) as MutableStateFlow<CallState>
        val before=state.value
        try {
            compose.runOnIdle { state.value=CallState(active=true,callId="synthetic-update-guard");vm.installAppUpdate() }
            assertTrue(vm.appUpdate.value.status.contains("End your call"))
            compose.runOnIdle { vm.downloadAppUpdate() }
            assertTrue(vm.appUpdate.value.status.contains("End your call"))
        } finally { compose.runOnIdle { state.value=before } }
        receipts += "Settings Back returns to chat; file Back closes the editor; seven offline destinations explain reconnect; both update entry points reject an active call."
    }

    @Test fun readAloudStopsAndSystemDictationCancelsWithoutSending() = withFixture { vm, _ ->
        org.junit.Assume.assumeTrue(args.getString("cypherNoHostAudio")=="1")
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val audio=app.getSystemService(android.media.AudioManager::class.java)
        try {
            compose.onAllNodesWithContentDescription("Read aloud").onFirst().performScrollTo().performClick()
            await { vm.readingAloud.value && audio.activePlaybackConfigurations.isNotEmpty() }
            compose.onAllNodesWithContentDescription("Stop read aloud").onFirst().performClick()
            await { !vm.readingAloud.value && audio.activePlaybackConfigurations.isEmpty() }
            val draft=vm.composer.value.text
            compose.onNodeWithContentDescription("Dictate").performClick()
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
        compose.setContent { CypherTheme(dark=true) {
            CompositionLocalProvider(LocalCypherSettings provides json("structured_code_default_view" to "on")) { CodeBlock("json",source) }
        } }
        await { compose.onAllNodesWithText("Source").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Source").performClick()
        compose.onNodeWithText("Tree").assertExists()
        compose.runOnIdle { source="{\n\"answer\":2,\n\"status\":\"streaming\"\n}" }
        compose.onNodeWithText("Tree").assertExists()
        compose.onNodeWithText("Source").assertDoesNotExist()
        receipts += "Manual Source mode survives changing streamed JSON; automatic Tree mode waits for valid parsing."
    }

    @Test fun callFeedbackDoesNotCoverControlsAndHangupRemainsTappable() = withFixture { vm, fixture ->
        val before=app.calls.state.value
        try {
            compose.runOnIdle { app.calls.update { CallState(active=true,state="listening",callId="synthetic-feedback",conversationScope=fixture.scope.session("chat-a")) };vm.notice("A previous action finished. This feedback must not block your call controls.") }
            await { compose.onAllNodesWithText("A previous action finished. This feedback must not block your call controls.").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val feedback=compose.onNodeWithTag("native-feedback").fetchSemanticsNode().boundsInRoot
            for(tag in listOf("xara-camera","xara-hang-up","cypher-share-screen")) {
                val control=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue("Feedback overlaps $tag",control.bottom<=feedback.top || control.top>=feedback.bottom)
            }
            capture("call-feedback-clear-controls")
            compose.runOnIdle {
                vm.tab("More"); vm.more("Bots")
                @Suppress("UNCHECKED_CAST")
                val botState = dev.hermes.android.data.NativeBotModeController::class.java.getDeclaredField("mutable").apply { isAccessible=true }.get(vm.botMode) as MutableStateFlow<dev.hermes.android.data.NativeBotModeState>
                botState.value = dev.hermes.android.data.NativeBotModeState(activeBot=dev.hermes.android.data.BotModeBot("default","Cypher",""),sessionId="chat-a")
            }
            compose.onNodeWithContentDescription("Show navigation").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back to bots").assertDoesNotExist()
            compose.onNodeWithText("Open call").assertDoesNotExist()
            compose.onAllNodesWithTag("xara-hang-up").assertCountEquals(1)
            compose.onNodeWithTag("xara-hang-up").performClick()
            await { !app.calls.state.value.active }
            receipts += "Feedback reserves actual call space; camera, sharing and hangup targets are unobscured, and tapping Hang up reaches the controller."
        } finally { compose.runOnIdle { app.calls.update { before };vm.clearMessage() } }
    }

    @Test fun disconnectedSettingsAndProjectPickerExplainHowToRecover() = withFixture { vm, _ ->
        compose.runOnIdle { field(vm, "client", null); vm.more("Memory") }
        compose.onNodeWithText("Reconnect to Hermes").assertIsDisplayed()
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
        compose.onNode(hasText("Projects") and hasAnyAncestor(hasTestTag("conversation-actions-menu")), useUnmergedTree = true).assertExists()
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
        compose.onNodeWithText("Link PC folder").performScrollTo().performClick()
        await { compose.onAllNodesWithText("research", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("research", useUnmergedTree = true).performClick()
        await { vm.data.value["project-folder"]?.string("path") == "C:/synthetic-workspace/research" }
        compose.onNodeWithText("Use this folder").performClick()
        await { project.rows("folders").size == 1 }
        compose.runOnIdle { vm.tab("Chat"); vm.composer(TextFieldValue("Retain during project move")) }
        compose.onNodeWithContentDescription("Conversation actions").performClick()
        compose.onNode(hasText("Projects") and hasAnyAncestor(hasTestTag("conversation-actions-menu")), useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Research", useUnmergedTree = true).performClick()
        compose.onNodeWithText("C:/synthetic-workspace/research", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Move chat", useUnmergedTree = true).performClick()
        await { vm.chat.value.raw.string("workspace") == "C:/synthetic-workspace/research" }
        assertEquals("Retain during project move", vm.composer.value.text)
        assertEquals("chat-a", vm.chat.value.sessionId)
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
        compose.onNode(hasText("Reasoning effort:", substring = true) and hasClickAction()).performClick()
        compose.onNodeWithTag("choice-option:Reasoning effort:high").performClick()
        compose.onNodeWithText("Apply to conversation").performClick()
        await { vm.catalog.value.isBlank() && vm.chat.value.raw.string("reasoning_effort") == "high" }
        assertEquals("high", fixture.reasoningEffort)
        receipts += "Configured/custom models show fresh native readback; native confirmation cancel makes no change; confirmed selection preserves messages; effort applies and returns to chat."
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

    @Test fun newChatReplacesAConfirmedMissingInitialShellWithoutLosingTheDraft() = withFixture { vm, fixture ->
        compose.runOnIdle {
            field(vm, "sourceScope", null)
            flow<ChatState>(vm, "_chat").value = ChatState()
            flow<AppState>(vm, "_ui").value = vm.ui.value.copy(tab = "Chat", initialChatReady = true)
            @Suppress("UNCHECKED_CAST")
            val shells = CypherViewModel::class.java.getDeclaredField("initialShells").apply { isAccessible = true }.get(vm) as MutableMap<String, String>
            shells[fixture.scope.key] = "removed-initial-shell"
            vm.composer(TextFieldValue("Keep this unsent thought"))
            vm.newChat()
        }
        try { await { vm.chat.value.sessionId.isNotBlank() && !vm.ui.value.newChatOpening } }
        catch (error: Throwable) {
            val calls = fixture.requests.map { it.string("method", it.string("path")) }.takeLast(10)
            throw AssertionError("Initial recovery: tab=${vm.ui.value.tab} ready=${vm.ui.value.initialChatReady} opening=${vm.ui.value.newChatOpening} id=${vm.chat.value.sessionId} error=${vm.ui.value.error} notice=${vm.ui.value.notice} requests=$calls", error)
        }
        assertEquals("Keep this unsent thought", vm.composer.value.text)
        assertEquals(1, fixture.requests.count { it.string("method") == "session.create" })
        assertTrue(fixture.sessions.containsKey(vm.chat.value.sessionId))
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

    @Test fun connectionDetailsAndSignOutRequireTheExplicitConfirmation() = withFixture { vm, _ ->
        compose.runOnIdle { vm.more("Connection") }
        compose.onNodeWithText("Technical details").performScrollTo().performClick()
        compose.onNodeWithText("synthetic-owner").assertExists()
        compose.onNodeWithText("Hide technical details").performScrollTo().performClick()
        compose.onNodeWithText("Account identifier").assertDoesNotExist()
        compose.onNodeWithText("Sign out", ignoreCase = true).performScrollTo().performClick()
        compose.onNodeWithText("Sign out of Cypher").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(vm.ui.value.authenticated)
        compose.onNodeWithText("Sign out", ignoreCase = true).performScrollTo().performClick()
        compose.onAllNodesWithText("Sign out", ignoreCase = true, useUnmergedTree = true).onLast().performClick()
        await { !vm.ui.value.authenticated && vm.client == null }
        assertFalse(app.calls.state.value.active)
        receipts += "Connection details disclose only on request; cancel keeps the account, and confirmed sign-out clears native authority."
    }

    @Test fun appearanceChatAndLicenseControlsHaveWorkingChoicesAndRetainStoredPreferences() = withFixture { vm, _ ->
        compose.runOnIdle { vm.more("Appearance") }
        fun choice(label: String, value: String) {
            compose.onNode(hasText("$label:", substring = true) and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithTag("choice-option:$label:$value").performScrollTo().performClick()
        }
        for (theme in listOf("light", "dark", "system")) { choice("Theme", theme); await { vm.preferences.value.theme == theme } }
        for (skin in NativeSkins) { choice("Color style", skin); await { vm.preferences.value.skin == skin } }
        for (size in nativeFontSizes) { choice("Text size", size); await { vm.preferences.value.fontSize == size } }
        for (language in listOf("日本語", "العربية", "English")) {
            choice("Language", language)
            await { NativeLanguages[vm.preferences.value.language] == language }
        }
        val motion = vm.preferences.value.reducedMotion
        compose.onNode(hasText("Reduce motion") and isToggleable()).performScrollTo().performClick()
        await { vm.preferences.value.reducedMotion != motion }
        val rtl = vm.preferences.value.rtl
        compose.onNode(hasText("Right-to-left layout") and isToggleable()).performScrollTo().performClick()
        await { vm.preferences.value.rtl != rtl }
        capture("audit-appearance")
        compose.runOnIdle { vm.more("Settings") }
        choice("Send shortcut", "ctrl_enter")
        await { vm.data.value["profile-settings"]?.string("send_key") == "ctrl_enter" }
        for (label in listOf("Format your messages", "Show thinking")) compose.onNode(hasText(label) and isToggleable()).performScrollTo().performClick()
        await { vm.data.value["profile-settings"]?.optBoolean("render_user_markdown") == false && vm.data.value["profile-settings"]?.optBoolean("show_thinking") == false }
        compose.runOnIdle { vm.more("Licenses") }
        for (document in listOf("hermes-LICENSE.txt", "termux-LICENSE.txt", "conduit-LICENSE.txt", "androidsvg-LICENSE.txt", "androidsvg-NOTICE.txt", "jlatexmath-LICENSE.txt", "jlatexmath-COPYING.txt", "rich/MERMAID-LICENSE", "rich/JSOUP-LICENSE", "rich/CORE-JS-LICENSE", "hermes-mobile-app-LICENSE.txt")) {
            choice("Document", document)
            val expected = app.assets.open(if (document.startsWith("rich/")) document else "licenses/$document").bufferedReader().use { it.readText().take(80) }
            await { compose.onAllNodes(hasText(expected, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        }
        receipts += "Appearance choices persist every shipped skin, themes, font sizes, representative languages, motion and RTL; chat preferences and every bundled license document control were executed."
        capture("audit-licenses")
    }

    @Test fun bothVoiceSettingsProvidersPersistVoicesAndPersonalityWithoutStartingACall() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.more("Cypher") }
        await { vm.data.value["voice-settings"] != null }
        for (provider in listOf("grok", "gemini")) {
            compose.onNodeWithTag("voice-model-$provider").performScrollTo().performClick()
            await { fixture.voiceProvider == provider && "voice-settings" !in vm.refreshing.value }
            compose.onNodeWithText("Voice: ${fixture.voices[provider]}").performScrollTo().performClick()
            val voice = if (provider == "grok") "eve" else "Puck"
            compose.onNodeWithText(voice).performClick()
            await { fixture.voices[provider] == voice && "voice-settings" !in vm.refreshing.value }
            val label = "Personality: ${fixture.personalities[provider]!!.replaceFirstChar(Char::titlecase)}"
            val nodes = compose.onAllNodesWithText(label)
            (if (provider == "grok") nodes.onLast() else nodes.onFirst()).performScrollTo().performClick()
            compose.onNodeWithText("Concise").performClick()
            await { fixture.personalities[provider] == "concise" && "voice-settings" !in vm.refreshing.value }
        }
        assertFalse(app.calls.state.value.active)
        assertNull(app.calls.service)
        receipts += "Gemini/Grok model, voice and personality settings save through the native endpoint; settings changes do not start recording or a call."
        capture("audit-voice-settings")
    }

    @Test fun conversationManagementBranchesArchivesAndDeletesOnlyAnIdleOwnedConversation() = withFixture { vm, fixture ->
        fun menu(label: String) {
            compose.onNodeWithContentDescription("Conversation actions").performClick()
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
        compose.onNodeWithText("Delete", useUnmergedTree = true).assertIsEnabled()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        try { await { !fixture.sessions.containsKey(branch) && vm.chat.value.sessionId != branch && !vm.ui.value.newChatOpening } }
        catch (error: Throwable) {
            val calls = fixture.requests.map { it.string("method", it.string("path")) }.takeLast(10)
            throw AssertionError("Delete recovery: present=${fixture.sessions.containsKey(branch)} id=${vm.chat.value.sessionId} opening=${vm.ui.value.newChatOpening} form=${vm.form.value?.error} error=${vm.ui.value.error} requests=$calls", error)
        }
        assertTrue(fixture.closedSessions.contains(branch))
        assertEquals(2, fixture.sessions.getValue("chat-a").getJSONArray("messages").length())
        assertTrue(fixture.sessions.containsKey("chat-b"))
        receipts += "Conversation archive/unarchive, compression result, branch/rename/undo and deletion use native receipts. Active work refuses deletion; only the idle confirmed target closes and deletes."
        capture("audit-conversation-management")
    }

    @Test fun skillEditorBackReturnsOneLevelAndKeepsUnsubmittedInstructions() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.more("Skills") }
        await { vm.phoneSettings.skills.value.skills.isNotEmpty() && !vm.phoneSettings.skills.value.loading }
        compose.onNodeWithText("Enabled", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Needs setup").performClick()
        compose.onNodeWithText("No skills match this filter and search.").assertExists()
        compose.onNodeWithText("All", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Read & adjust").performScrollTo().performClick()
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
        compose.onNodeWithText("All skills").performScrollTo().performClick()
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
        compose.onNodeWithText("View 1 tools").performScrollTo().performClick()
        compose.onNodeWithText("web_search").assertExists()
        compose.onNodeWithText("Hide tools").performClick()
        compose.onNodeWithContentDescription("Web research").performScrollTo().performClick()
        await { !fixture.toolEnabled && "native-tools" !in vm.refreshing.value }
        compose.onNodeWithContentDescription("Web research").performClick()
        await { fixture.toolEnabled && "native-tools" !in vm.refreshing.value }
        compose.onNode(hasSetTextAction() and hasText("Search tools")).performScrollTo().performTextReplacement("no-match")
        compose.onNodeWithText("No matches. Try another search.").assertExists()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Browse chat commands").performScrollTo().performClick()
        await { vm.ui.value.morePage == "Commands" }
        compose.onNodeWithText("Refresh commands").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Search commands")).performTextReplacement("help")
        compose.onNodeWithText("/help").performScrollTo().performClick()
        assertEquals("/help ", vm.composer.value.text)
        assertFalse(fixture.requests.any { it.string("method") == "chat.send" })
        compose.runOnIdle { vm.more("MCP servers") }
        await { vm.data.value["native-mcp"] != null }
        compose.onNodeWithText("Test connection").performScrollTo().performClick()
        await { vm.data.value["mcp-test:fixture_mcp"] != null }
        compose.onNodeWithText("View 1 tools").performScrollTo().performClick()
        compose.onNodeWithText("read_fixture").assertExists()
        compose.onNodeWithContentDescription("fixture_mcp").performScrollTo().performClick()
        await { !fixture.connectionEnabled && "native-mcp" !in vm.refreshing.value }
        compose.onNodeWithContentDescription("fixture_mcp").performClick()
        await { fixture.connectionEnabled && "native-mcp" !in vm.refreshing.value }
        receipts += "Tool groups and MCP search, enable/disable, test connection, tool disclosures and command preparation use real callbacks; selecting a command never sends it."
        capture("audit-connections")
    }

    @Test fun profileAndServerSettingsHaveCompleteNativeNavigationAndConfirmation() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.more("Profiles") }
        await { vm.phoneSettings.bots.value.profiles.isNotEmpty() && !vm.phoneSettings.bots.value.loading }
        compose.onNodeWithText("View profile settings").performScrollTo().performClick()
        await { vm.phoneSettings.bots.value.selected != null && !vm.phoneSettings.bots.value.loading }
        compose.onNodeWithText("Read behavior instructions").performScrollTo().performClick()
        compose.onNodeWithText("Check sources before answering.").performScrollTo().assertExists()
        compose.onNodeWithText("Hide behavior instructions").performScrollTo().performClick()
        compose.onNodeWithText("Change default model").performScrollTo().performClick()
        compose.onNodeWithText("Enter a configured model ID").performScrollTo().performClick()
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
        compose.onNodeWithText("Refresh").performScrollTo().performClick()
        compose.runOnIdle { vm.more("Logs") }
        await { !vm.phoneSettings.server.value.loading }
        for (file in listOf("errors", "agent", "gateway", "gui", "desktop", "mcp")) {
            compose.onNodeWithText(file).performScrollTo().performClick()
            await { vm.phoneSettings.server.value.logFile == file && !vm.phoneSettings.server.value.loading }
        }
        compose.onNode(hasSetTextAction() and hasText("Search log entries")).performScrollTo().performTextReplacement("none")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Refresh").performScrollTo().performClick()
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

    @Test fun botOverviewIsNavigationAndMembershipDeletionAreExplicitActions() = withFixture { vm, fixture ->
        fixture.botProfiles = listOf("default", "coder", "academic")
        compose.runOnIdle { vm.navigate("Bots") }
        await { vm.botMode.state.value.bots.size == 3 && !vm.ui.value.botModeSwitching }
        compose.onNodeWithText("Create shared space").assertDoesNotExist()
        compose.onNode(hasText("Shared workspace") and hasAnyAncestor(hasTestTag("bot-mode-home"))).performScrollTo().performClick()
        await { vm.botMode.state.value.selectedSpace == "fixture-room" && !vm.botMode.state.value.loading }
        compose.runOnIdle { vm.botMode.draft("Keep this team draft") }
        compose.onNodeWithContentDescription("Shared-space actions").performClick()
        compose.onNodeWithText("Choose bots").performClick()
        compose.onNode(hasText("coder") and isToggleable()).performClick()
        compose.onNode(hasText("academic") and isToggleable()).performClick()
        compose.onNodeWithText("Save bots").performClick()
        await { !vm.botMode.state.value.loading && vm.botMode.state.value.members.toSet() == setOf("default", "academic") }
        assertEquals("Keep this team draft", vm.botMode.state.value.draft)
        assertEquals(1, fixture.requests.count { it.string("path").endsWith("/members") })
        compose.onNodeWithContentDescription("Shared-space actions").performClick()
        compose.onNodeWithText("Delete shared space").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(fixture.roomDeleted)
        compose.onNodeWithContentDescription("Shared-space actions").performClick()
        compose.onNodeWithText("Delete shared space").performClick()
        compose.onNodeWithText("Delete space").performClick()
        await { fixture.roomDeleted && vm.botMode.state.value.selectedSpace.isBlank() }
        assertEquals(1, fixture.requests.count { it.string("path").endsWith("/fixture-room/delete") })
        receipts += "Bot overview navigates directly; explicit roster save retains draft, cancel does not delete, confirmed deletion removes only the selected room."
        capture("flow-bot-overview")
    }

    @Test fun reopeningSavedChatDoesNotResumeAnAgentAndShowsTailImmediately() = withFixture { vm, fixture ->
        val rows = JSONArray((1..45).map { json("role" to "user", "text" to "Saved message $it", "row_id" to it) })
        fixture.sessions.getValue("chat-b").put("messages", rows)
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

}
