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
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.*
import dev.hermes.android.core.*
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.CypherClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual renderer/control proof with generated PDF bytes in the disposable fixture workspace. */
class Usability19Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun fixture(block: (CypherViewModel, CypherClient) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("controls_probe") == "1")
        val origin = args.getString("rc8Fixture", "https://localhost:8798")
        require(origin.matches(Regex("https://localhost:[0-9]+")))
        val app = ApplicationProvider.getApplicationContext<CypherApplication>()
        val prefs = runBlocking { app.preferences.data.first() }; val auth = app.auth.read(origin)
        val store = ViewModelStore(); lateinit var vm: CypherViewModel
        val control = CypherClient(AuthContext(origin, emptyList()), app.auth)
        runBlocking { control.login("fixture-only"); control.post("/api/profile/switch", json("name" to "default")); control.bootstrap() }
        val owner = control.scope
        val kinds = listOf("initial-draft", "new-chat-attempt", "new-chat-shell", "selected-session")
        val retained = runBlocking { kinds.associateWith { app.cache.read(owner, it) } }
        try {
            runBlocking { kinds.forEach { app.cache.delete(owner, it) }; app.preferences.edit { it[stringPreferencesKey("origin")] = "" } }
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { CypherViewModel(app, SavedStateHandle()) } })[CypherViewModel::class.java] }
            compose.runOnIdle { vm.connect(origin) }
            compose.waitUntil(25_000) { vm.ui.value.authenticated && !vm.ui.value.loading && vm.ui.value.initialChatReady }
            val sid = runBlocking { control.post("/api/session/import", json("title" to "Native PDF pager", "messages" to org.json.JSONArray(listOf(json("role" to "user", "content" to "Synthetic PDF pager"))))) }.getString("session_id")
            compose.runOnIdle { vm.selectSession(sid) }
            compose.waitUntil(25_000) { vm.authenticatedScope()?.session == sid && vm.chat.value.sessionId == sid }
            block(vm, control)
        } finally {
            compose.runOnUiThread { store.clear() }
            runBlocking { retained.forEach { (kind, value) -> if (value == null) app.cache.delete(owner, kind) else app.cache.write(owner, kind, value, true) }; if (auth.owner.isBlank()) app.auth.clear(origin) else app.auth.write(auth); app.preferences.updateData { prefs } }
        }
    }
    @Test fun pdfPagerHasVisibleNamesCountsAndDisabledBoundaries() = fixture { vm, control ->
        val app = ApplicationProvider.getApplicationContext<CypherApplication>()
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
            runBlocking { control.upload("/api/workspace/upload", file, name, "application/pdf", sid) }
            var screen by mutableStateOf("pdf")
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, args.getString("audit_font", "1").toFloat()), LocalLayoutDirection provides if (args.getString("audit_rtl") == "true") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    CypherTheme(dark = args.getString("audit_theme") != "light") {
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
            fun ready(page: Int) = compose.waitUntil(25_000) { compose.onAllNodesWithContentDescription("PDF page $page").fetchSemanticsNodes().isNotEmpty() }
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
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Reset zoom").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Reset zoom").assertIsDisplayed().assertIsEnabled().performClick()
            compose.onNodeWithText("Reset zoom").assertDoesNotExist()
            compose.runOnIdle { screen = "code" }
            compose.onNodeWithText("Tree").assertIsDisplayed().performClick(); compose.onNodeWithText("Source").assertIsDisplayed().performClick()
            compose.onNodeWithText("Tree").assertIsDisplayed(); capture("code-controls")
            compose.runOnIdle { screen = "link" }
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Native pager PDF").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Native pager PDF").performTouchInput { click() }
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Close").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Close").assertIsDisplayed().assertIsEnabled().performClick(); compose.onNodeWithText("Close").assertDoesNotExist()
            compose.runOnIdle { screen = "diagram" }
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Retry diagram").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Retry diagram").assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Retry diagram").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Diagram exceeds the rendering limit").assertIsDisplayed(); capture("diagram-retry")
            println("RICH15_ACTUAL_RESET_TREE_SOURCE_CLOSE_RETRY_VISIBLE_OK")
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
                CypherTheme(dark = args.getString("audit_theme") != "light") {
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
            val app = ApplicationProvider.getApplicationContext<CypherApplication>(); val out = app.getExternalFilesDir(null)
            File(out, "labels18-$stage.txt").writeText(compose.onAllNodes(isRoot(), true).printToString(maxDepth = 1000))
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap -> File(out, "labels18-$stage.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        }
        compose.onNodeWithText("Generic blank: Default").assertIsDisplayed().performClick()
        compose.onNodeWithTag("choice-option:Generic blank:").assertTextEquals("Default").assertIsDisplayed().performClick()
        assertEquals("", generic); compose.onNodeWithText("Generic blank: Default").assertExists()
        compose.onNodeWithText("Generic blank: Default").performClick()
        compose.onNodeWithTag("choice-option:Generic blank:provider/Model_ID-v1").assertTextEquals("provider/Model_ID-v1").performClick()
        assertEquals("provider/Model_ID-v1", generic)
        val field = Field("advanced.service_tier", "Service tier", FieldType.CHOICE, options = listOf("", "default", "priority"), optionLabels = mapOf("" to "Use profile default", "default" to "Default tier", "priority" to "Priority tier"))
        fun open(required: Boolean = false) = compose.runOnIdle { vm.openForm(ActionForm("Choice proof", "", listOf(field.copy(required = required)), json("advanced" to json("service_tier" to "priority")), onResult = { saved = it })) }
        open(); compose.onNodeWithText("Service tier: Priority tier").performClick()
        compose.onNodeWithTag("choice-option:Service tier:").assertTextEquals("Use profile default").assertIsEnabled().performClick()
        assertEquals("", vm.form.value!!.values[field.key]); compose.onNodeWithText("Service tier: Use profile default").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick(); compose.waitUntil(25_000) { vm.form.value == null }; assertNull(saved)
        open(); compose.onNodeWithText("Service tier: Priority tier").performClick()
        compose.onNodeWithTag("choice-option:Service tier:").performClick(); capture("blank-selected")
        compose.onNodeWithText("Save").performClick(); compose.waitUntil(25_000) { saved != null && vm.form.value == null }
        assertEquals("", saved!!.getJSONObject("advanced").getString("service_tier"))
        saved = null; open(required = true); compose.onNodeWithText("Service tier: Priority tier").performClick()
        compose.onNodeWithTag("choice-option:Service tier:").performClick(); compose.onNodeWithText("Save").performClick()
        compose.waitUntil(25_000) { vm.form.value?.error?.contains("Service tier is required") == true }
        compose.onNodeWithText("Service tier is required").assertIsDisplayed(); assertNull(saved); capture("blank-required-error")
        compose.onNodeWithText("Cancel").performClick(); compose.waitUntil(25_000) { vm.form.value == null }
        println("LABELS18_ACTUAL_BLANK_SAVE_CANCEL_REQUIRED_VALIDATION_OK")
    }
}
