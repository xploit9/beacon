package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.*
import dev.hermes.android.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual renderer/control proof with generated PDF bytes in the disposable fixture workspace. */
class Usability19Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun semanticsSnapshot() = runCatching {
        compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 100)
    }.getOrElse { "<semantics unavailable: ${it.message}>" }.take(8_000)
    private fun await(label: String, timeoutMillis: Long, observed: () -> String, condition: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis) { condition() } }
        catch (error: ComposeTimeoutException) {
            val state = runCatching(observed).getOrElse { "<state unavailable: ${it.message}>" }
            throw AssertionError("$label timed out: $state", error)
        }
    }
    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) = BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
    private fun fixture(block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val prefs = runBlocking { app.preferences.data.first() }
        val store = ViewModelStore(); lateinit var vm: BeaconViewModel
        val fixture = NativeExperienceFixture()
        val owner = fixture.scope
        val kinds = listOf("initial-draft", "new-chat-attempt", "new-chat-shell", "selected-session")
        val retained = runBlocking { kinds.associateWith { app.cache.read(owner, it) } }
        try {
            runBlocking {
                kinds.forEach { app.cache.delete(owner, it) }
                // Keep ViewModel startup from restoring the owner's saved server into this in-memory fixture.
                app.preferences.edit {
                    it[stringPreferencesKey("origin")] = ""
                    it[booleanPreferencesKey("setup_complete")] = true
                    it[booleanPreferencesKey("setup_active")] = false
                }
            }
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
                field(vm, "client", fixture.client)
                field(vm, "sourceScope", owner.session("chat-a"))
                app.calls.ownerScope = owner
                val snapshot = fixture.snapshot("chat-a")
                flow<AppState>(vm, "_ui").value = AppState(origin = owner.origin, authenticated = true, connected = true, owner = owner.owner, profile = owner.profile, setupComplete = true,
                    initialChatReady = true, restoringSession = false, bootstrap = json("voice" to json("available" to false)), sessions = fixture.sessions.keys.sorted().map(fixture::snapshot))
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = "chat-a", title = "Travel planning", raw = snapshot,
                    messages = snapshot.rows("messages").mapIndexed { index, row -> Message.from(row, index, "chat-a") })
            }
            block(vm, fixture)
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative(); app.calls.ownerScope = null }
            runBlocking { retained.forEach { (kind, value) -> if (value == null) app.cache.delete(owner, kind) else app.cache.write(owner, kind, value, true) }; app.preferences.updateData { prefs } }
        }
    }
    @Test fun systemBackCancelsAnAuthenticatedSetupWizard() = fixture { vm, fixture ->
        compose.setContent {
            NativeLocalization("en") { BeaconTheme {
                NativeWindowLayout(compose.activity) { Box(Modifier.fillMaxSize()) { BeaconRoot(vm) } }
            } }
        }
        compose.runOnIdle { vm.tab("More"); vm.reopenSetupWizard(fixture.scope.origin) }
        await("Authenticated setup wizard opened", 15_000, { "ui=${vm.ui.value}; tree=${semanticsSnapshot()}" }) {
            vm.ui.value.setupWizard && !vm.ui.value.restoringSession
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        await("System Back canceled the setup wizard", 5_000, { "ui=${vm.ui.value}; tree=${semanticsSnapshot()}" }) {
            !vm.ui.value.setupWizard
        }
        assertTrue(vm.ui.value.authenticated)
    }
    @Test fun pdfPagerHasVisibleNamesCountsAndDisabledBoundaries() = fixture { vm, fixture ->
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val args = InstrumentationRegistry.getArguments()
        val sid = requireNotNull(vm.authenticatedScope()).session
        val file = File.createTempFile("native-pdf-pager-", ".pdf", app.cacheDir)
        val name = "native-pdf-pager-${System.nanoTime()}.pdf"
        try {
            val pdf = android.graphics.pdf.PdfDocument()
            try {
                for (number in 1..2) {
                    val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(320, 240, number).create())
                    page.canvas.drawText("Native pager page $number", 24f, 60f, android.graphics.Paint().apply { textSize = 18f })
                    pdf.finishPage(page)
                }
                file.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
            fixture.downloads[name] = file.readBytes()
            var screen by mutableStateOf("pdf")
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, args.getString("audit_font", "1").toFloat()), LocalLayoutDirection provides if (args.getString("audit_rtl") == "true") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    BeaconTheme(dark = args.getString("audit_theme") != "light") {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            when (screen) {
                                "pdf" -> MediaPreview(vm, name)
                                "code" -> CodeBlock("json", "{\"one\":[1,2]}")
                                "link" -> Markdown("[Native pager PDF](/api/file/raw?session_id=$sid&path=$name)", vm)
                                else -> Markdown("```mermaid\n" + "x".repeat(64_001) + "\n```", vm)
                            }
                        }
                        }
                    }
                }
            }
            fun previewState() = "requests=${fixture.requests.takeLast(8).map { "${it.string("method")} ${it.string("path")}" }}; files=${fixture.fileRequests.takeLast(8)}; tree=${semanticsSnapshot()}"
            fun ready(page: Int) = await("PDF page $page rendered", 25_000, { previewState() }) {
                runCatching { compose.onNodeWithContentDescription("PDF page $page").assertIsDisplayed() }.isSuccess
            }
            fun capture(stage: String) {
                val out = app.getExternalFilesDir(null)
                File(out, "pdf15-$stage.txt").writeText(compose.onAllNodes(isRoot(), true).printToString(maxDepth = 1000))
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap -> File(out, "pdf15-$stage.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
            }
            fun assertCount(expected: String) {
                val count = compose.onNodeWithTag("pdf-page-count")
                count.assertTextEquals(expected).assertIsDisplayed()
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                count.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(1, layouts.size)
                assertTrue("Numeric PDF count is visually reversed in RTL", layouts.single().getBoundingBox(0).left < layouts.single().getBoundingBox(expected.lastIndex).left)
            }
            ready(1)
            assertCount("1 / 2")
            compose.onNodeWithText("Previous").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText("Next").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithContentDescription("Previous PDF page").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Next PDF page").assertIsEnabled()
            val density = app.resources.displayMetrics.density
            for (tag in listOf("pdf-previous", "pdf-next")) assertTrue("Pager target below 48dp", compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height / density >= 48f)
            compose.onNodeWithTag("pdf-previous").performTouchInput { click() }; compose.waitForIdle()
            assertCount("1 / 2"); capture("first")
            compose.onNodeWithText("Next").performClick(); ready(2)
            assertCount("2 / 2")
            compose.onNodeWithText("Next").assertIsNotEnabled(); compose.onNodeWithText("Previous").assertIsEnabled()
            compose.onNodeWithTag("pdf-next").performTouchInput { click() }; compose.waitForIdle()
            assertCount("2 / 2"); capture("second")
            compose.onNodeWithText("Previous").performClick(); ready(1)
            assertCount("1 / 2")
            compose.onNodeWithText("Previous").assertIsNotEnabled(); compose.onNodeWithText("Next").assertIsEnabled(); capture("returned")
            println("PDF15_ACTUAL_NEXT_PREVIOUS_COUNT_BOUNDS_OK")
            compose.onNodeWithContentDescription("PDF page 1").performTouchInput {
                down(0, androidx.compose.ui.geometry.Offset(centerX - 20, centerY)); down(1, androidx.compose.ui.geometry.Offset(centerX + 20, centerY))
                moveTo(0, androidx.compose.ui.geometry.Offset(centerX - 100, centerY)); moveTo(1, androidx.compose.ui.geometry.Offset(centerX + 100, centerY)); up(0); up(1)
            }
            await("PDF zoom controls appeared", 25_000, { previewState() }) {
                compose.onAllNodesWithText("Reset zoom").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Reset zoom").assertIsDisplayed().assertIsEnabled().performClick()
            compose.onNodeWithText("Reset zoom").assertDoesNotExist()
            compose.runOnIdle { screen = "code" }
            compose.onNodeWithContentDescription("Tree").assertIsDisplayed().performClick(); compose.onNodeWithContentDescription("Source").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("Tree").assertIsDisplayed(); capture("code-controls")
            compose.runOnIdle { screen = "link" }
            await("PDF link name appeared", 25_000, { previewState() }) {
                compose.onAllNodesWithText("Native pager PDF").fetchSemanticsNodes().isNotEmpty()
            }
            val linkLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("Native pager PDF").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(linkLayouts) }
            val linkPosition = linkLayouts.single().getBoundingBox(2).center
            compose.onNodeWithText("Native pager PDF").performTouchInput { click(linkPosition) }
            await("Linked PDF viewer opened", 25_000, { previewState() }) {
                compose.onAllNodesWithText("Close").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Close").assertIsDisplayed().assertIsEnabled().performClick()
            await("Linked PDF viewer closed", 25_000, { previewState() }) {
                compose.onAllNodesWithText("Close").fetchSemanticsNodes().isEmpty()
            }
            compose.runOnIdle { screen = "diagram" }
            await("Oversized diagram fallback appeared", 25_000, { previewState() }) {
                compose.onAllNodesWithText("This preview can't be shown.").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("This preview can't be shown.").assertIsDisplayed()
            compose.onNodeWithText("Source").assertIsDisplayed()
            compose.onNodeWithText("Fullscreen diagram").assertIsNotEnabled()
            compose.onNodeWithText("mermaid").assertIsDisplayed(); capture("diagram-fallback")
            println("RICH15_ACTUAL_RESET_TREE_SOURCE_CLOSE_OVERSIZED_DIAGRAM_FALLBACK_VISIBLE_OK")
        } finally { file.delete() }
    }
    @Test fun choiceLabelsPreserveBlankSaveAndRequiredValidation() = fixture { vm, _ ->
        val args = InstrumentationRegistry.getArguments()
        var saved: org.json.JSONObject? = null
        var generic by mutableStateOf("")
        compose.setContent {
            val density = LocalDensity.current
            val form by vm.form.collectAsState()
            CompositionLocalProvider(LocalDensity provides Density(density.density, args.getString("audit_font", "1").toFloat()), LocalLayoutDirection provides if (args.getString("audit_rtl") == "true") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                BeaconTheme(dark = args.getString("audit_theme") != "light") {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(16.dp)) {
                            Choice("Generic blank", generic, listOf("", "default", "provider/Model_ID-v1"), { generic = it })
                        }
                        form?.let { NativeForm(vm, it) }
                    }
                }
            }
        }
        fun capture(stage: String) {
            val app = ApplicationProvider.getApplicationContext<BeaconApplication>(); val out = app.getExternalFilesDir(null)
            File(out, "labels18-$stage.txt").writeText(compose.onAllNodes(isRoot(), true).printToString(maxDepth = 1000))
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap -> File(out, "labels18-$stage.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        }
        fun formState() = "form=${vm.form.value}; saved=$saved; tree=${semanticsSnapshot()}"
        fun selectBlank() {
            await("Blank choice option appeared", 5_000, { formState() }) {
                compose.onAllNodesWithTag("choice-option:Service tier:").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("choice-option:Service tier:").performClick()
        }
        compose.onNode(hasText("Generic blank") and hasText("Default")).assertIsDisplayed().performClick()
        compose.onNodeWithTag("choice-option:Generic blank:").assertTextEquals("Default").assertIsDisplayed().performClick()
        assertEquals("", generic); compose.onNode(hasText("Generic blank") and hasText("Default")).assertExists()
        compose.onNode(hasText("Generic blank") and hasText("Default")).performClick()
        compose.onNodeWithTag("choice-option:Generic blank:provider/Model_ID-v1").assertTextEquals("provider/Model_ID-v1").performClick()
        assertEquals("provider/Model_ID-v1", generic)
        val field = Field("advanced.service_tier", "Service tier", FieldType.CHOICE, options = listOf("", "default", "priority"), optionLabels = mapOf("" to "Use profile default", "default" to "Default tier", "priority" to "Priority tier"))
        fun open(required: Boolean = false) = compose.runOnIdle { vm.openForm(ActionForm("Choice proof", "", listOf(field.copy(required = required)), json("advanced" to json("service_tier" to "priority")), onResult = { saved = it })) }
        open(); compose.onNode(hasText("Service tier") and hasText("Priority tier")).performClick()
        selectBlank()
        assertEquals("", vm.form.value!!.values[field.key]); compose.onNode(hasText("Service tier") and hasText("Use profile default")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        await("Choice form closed after cancel", 25_000, { formState() }) { vm.form.value == null }
        assertNull(saved)
        open(); compose.onNode(hasText("Service tier") and hasText("Priority tier")).performClick()
        selectBlank(); capture("blank-selected")
        compose.onNodeWithText("Save").performClick()
        await("Blank choice saved", 25_000, { formState() }) { saved != null && vm.form.value == null }
        assertEquals("", saved!!.getJSONObject("advanced").getString("service_tier"))
        saved = null; open(required = true); compose.onNode(hasText("Service tier") and hasText("Priority tier")).performClick()
        selectBlank(); assertEquals("", vm.form.value!!.values[field.key]); compose.onNodeWithText("Save").performClick()
        await("Required choice validation appeared", 25_000, { formState() }) {
            vm.form.value?.error == "Enter Service tier."
        }
        compose.onNodeWithText("Enter Service tier.").assertIsDisplayed(); assertNull(saved); capture("blank-required-error")
        compose.onNodeWithText("Cancel").performClick()
        await("Required choice form closed after cancel", 25_000, { formState() }) { vm.form.value == null }
        println("LABELS18_ACTUAL_BLANK_SAVE_CANCEL_REQUIRED_VALIDATION_OK")
    }
}
