package dev.hermes.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hermes.android.core.*
import dev.hermes.android.data.AppCipher
import dev.hermes.android.data.AuthContext
import dev.hermes.android.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeFlowTest {
    @Test fun imagePreviewsStayWithinTheNativeBitmapBudget() {
        val source = android.graphics.Bitmap.createBitmap(2160, 2160, android.graphics.Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.RED)
        val bytes = java.io.ByteArrayOutputStream()
        try { assertTrue(source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)) } finally { source.recycle() }
        val decoder = Class.forName("dev.hermes.android.ui.RichContentKt").getDeclaredMethod("boundedBitmap", ByteArray::class.java).apply { isAccessible = true }
        val decoded = requireNotNull(decoder.invoke(null, bytes.toByteArray()) as? android.graphics.Bitmap)
        try {
            assertTrue(decoded.width in 1..1440 && decoded.height in 1..1440)
            assertEquals(android.graphics.Color.RED, decoded.getPixel(0, 0))
        } finally { decoded.recycle() }
        assertNull(decoder.invoke(null, byteArrayOf()))
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<CypherApplication>()
    @Test fun encryptedDraftCheckpointIsBoundToOriginOwnerProfileAndConversation() = runBlocking {
        val cipher = AppCipher()
        val a = Scope("https://fixture.example", "fixture-owner", "default", "native-test-a")
        val payload = Draft("private composed text", 4, 7, queued = listOf(QueuedMessage("later")), anchor = 9).encode()
        val sealed = cipher.encrypt(payload.toByteArray(), a.key)
        assertFalse(String(sealed).contains("private composed text"))
        assertEquals(payload, String(cipher.decrypt(sealed, a.key)))
        listOf(a.copy(owner = "other"), a.copy(profile = "other"), a.session("b"), a.copy(origin = "https://other.example")).forEach { wrong -> assertTrue(runCatching { cipher.decrypt(sealed, wrong.key) }.isFailure) }
        app.cache.write(a, "draft-test", payload, true)
        assertEquals(payload, app.cache.read(a, "draft-test"))
        assertNull(app.cache.read(a.copy(profile = "research"), "draft-test"))
        app.cache.trim()
        assertEquals(payload, app.cache.read(a, "draft-test"))
        app.cache.delete(a, "draft-test")
    }
    @Test fun pushDedupPersistsAcrossNewManagerWithoutNetworkAndRejectsForgedRoutes() {
        val source = "instrumented-source-${System.nanoTime()}"
        val event = "instrumented-event"
        val first = dev.hermes.android.push.PushManager(app)
        assertTrue(first.firstArrival(source, event)); assertFalse(first.firstArrival(source, event))
        assertFalse(dev.hermes.android.push.PushManager(app).firstArrival(source, event))
        assertFalse(first.firstArrival("https://evil.example", event))
        assertNull(first.binding("unregistered-ref"))
    }
    @Test fun nativeTypedConfigurationSubmitsActualControlsAndDoesNotExposeSecretText() {
        val vm = CypherViewModel(app, SavedStateHandle())
        var result = ""
        compose.runOnIdle { vm.openForm(ActionForm("Configure native test", "", listOf(Field("name", required = true), Field("enabled", type = FieldType.BOOLEAN), Field("timeout", type = FieldType.INTEGER), Field("key", "Secret", FieldType.SECRET)), json("enabled" to false, "timeout" to 15), onResult = { result = it.toString() })) }
        compose.setContent { CypherTheme(dark = true) { val form by vm.form.collectAsState(); form?.let { NativeForm(vm, it) } } }
        compose.onNode(hasText("Name *") and hasSetTextAction()).performTextInput("fixture-server")
        compose.onNode(hasText("Timeout") and hasSetTextAction()).performTextClearance()
        compose.onNode(hasText("Timeout") and hasSetTextAction()).performTextInput("45")
        compose.onNodeWithText("Enabled").assertIsToggleable().assertIsOff().performClick().assertIsOn()
        compose.onNode(hasText("Secret") and hasSetTextAction()).performTextInput("test-secret")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { result.isNotBlank() }
        val body = org.json.JSONObject(result)
        assertEquals("fixture-server", body.getString("name")); assertEquals(45, body.getInt("timeout")); assertTrue(body.getBoolean("enabled")); assertEquals("test-secret", body.getString("key"))
        compose.onNodeWithText("test-secret").assertDoesNotExist()
    }
    @Test fun nativeMarkdownCodeTablesAndUnsupportedMathRemainReadable() {
        val vm = CypherViewModel(app, SavedStateHandle())
        val unsupported = "\$\\hermesunsupportedcommand{a}\$"
        val finite = "\\(x+1\\)"
        compose.setContent { CypherTheme(dark = true) { Markdown("# Native content\n\nA calm **transcript**. $finite $unsupported\n\n```kotlin\nval native = true\n```\n\n| State | Result |\n|---|---|\n| Done | Confirmed |\n", vm) } }
        // The parser and bitmap producer run off the Compose test clock. Observe
        // their accepted native Image, rather than treating initial empty state as final.
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(finite, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(finite, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Native content").assertExists()
        compose.onNodeWithText("val native = true").assertExists()
        compose.onNodeWithText("Confirmed").assertExists()
        compose.onNodeWithText(unsupported, substring = true).assertExists()
    }
    @Test fun nativeFormulaDefinitionsRemainLocalAndRestoreAfterFailedRendering() {
        val macro = "hermesprivateformula"
        val color = "hermesprivatecolor"
        // Pinned 0.2.0 registers textcolor (PredefinedCommands.java:235), not color.
        val expression = "\\newcommand{\\$macro}{PRIVATE}\\definecolor{$color}{rgb}{1,0,0}\\textcolor{$color}{\\$macro}"
        val originalSize = org.scilab.forge.jlatexmath.TeXIcon.defaultSize
        val originalMagnification = org.scilab.forge.jlatexmath.TeXIcon.magFactor
        val width = org.scilab.forge.jlatexmath.CypherMathDefinitions.render {
            val drawable = ru.noties.jlatexmath.JLatexMathDrawable.builder(expression).textSize(18f).build()
            assertTrue(org.scilab.forge.jlatexmath.MacroInfo.Commands.containsKey(macro))
            assertTrue(org.scilab.forge.jlatexmath.ColorAtom.Colors.containsKey(color))
            val bitmap = android.graphics.Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                drawable.draw(android.graphics.Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("The scoped named color must reach actual formula drawing", pixels.any { android.graphics.Color.alpha(it) > 0 && android.graphics.Color.red(it) > android.graphics.Color.green(it) && android.graphics.Color.red(it) > android.graphics.Color.blue(it) })
            } finally { bitmap.recycle() }
            drawable.intrinsicWidth
        }
        assertTrue(width > 0)
        assertFalse(org.scilab.forge.jlatexmath.MacroInfo.Commands.containsKey(macro))
        assertFalse(org.scilab.forge.jlatexmath.ColorAtom.Colors.containsKey(color))
        assertTrue(runCatching { org.scilab.forge.jlatexmath.CypherMathDefinitions.render { ru.noties.jlatexmath.JLatexMathDrawable.builder("\\$macro").build() } }.isFailure)
        val failure = runCatching { org.scilab.forge.jlatexmath.CypherMathDefinitions.render {
            ru.noties.jlatexmath.JLatexMathDrawable.builder(expression + "\\DeclareMathSizes{40}{30}{20}{10}\\magnification{3000}").textSize(18f).build()
            assertEquals(40f, org.scilab.forge.jlatexmath.TeXIcon.defaultSize, 0f)
            assertEquals(3f, org.scilab.forge.jlatexmath.TeXIcon.magFactor, 0f)
            error("Forced layout failure")
        } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Forced layout failure", failure?.message)
        assertFalse(org.scilab.forge.jlatexmath.MacroInfo.Commands.containsKey(macro))
        assertFalse(org.scilab.forge.jlatexmath.ColorAtom.Colors.containsKey(color))
        assertEquals(originalSize, org.scilab.forge.jlatexmath.TeXIcon.defaultSize, 0f)
        assertEquals(originalMagnification, org.scilab.forge.jlatexmath.TeXIcon.magFactor, 0f)
    }
    @Test(timeout = 15_000) fun nativeParserAcceptsFiniteMacrosAndEnvironmentsAndRejectsRecursiveExpansion() {
        fun render(source: String) = org.scilab.forge.jlatexmath.CypherMathDefinitions.render { ru.noties.jlatexmath.JLatexMathDrawable.builder(source).textSize(18f).build().intrinsicWidth }
        fun assertBudgetFailure(source: String, action: () -> Any) {
            val failure = runCatching(action).exceptionOrNull()
            assertNotNull("Expected native budget rejection: $source", failure)
            // Pinned MacroInfo.java:137 and PredefMacroInfo.java:589 preserve the
            // underlying message, even when the exception cause is discarded.
            val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
            assertTrue("Unexpected failure for $source: ${messages.joinToString(" | ")}", messages.any { it.contains("Math macro expansion exceeds the native formula budget") })
        }
        assertTrue(render("\\newcommand{\\hermespair}[1]{#1+#1}\\hermespair{x}") > 0)
        assertTrue(render("\\begin{matrix}1&2\\\\3&4\\end{matrix}") > 0)
        // Builder.textSize has no default in pinned 0.2.0 (JLatexMathDrawable.java:183).
        assertTrue(org.scilab.forge.jlatexmath.CypherMathDefinitions.render { org.scilab.forge.jlatexmath.NewEnvironmentMacro.addNewEnvironment("hermesfinite", "\\left(", "\\right)", 0); ru.noties.jlatexmath.JLatexMathDrawable.builder("\\begin{hermesfinite}x\\end{hermesfinite}").textSize(18f).build().intrinsicWidth } > 0)
        listOf("\\newcommand{\\hermesloop}{\\hermesloop}\\hermesloop", "\\newcommand{\\hermesa}{\\hermesb}\\newcommand{\\hermesb}{\\hermesa}\\hermesa", "\\newcommand{\\hermesgrow}[1]{\\hermesgrow{#1#1}}\\hermesgrow{x}", "\\newcommand{\\hermesargs}[999999999]{#1}\\hermesargs{x}").forEach { source -> assertBudgetFailure(source) { render(source) } }
        assertBudgetFailure("recursive custom environment") {
            org.scilab.forge.jlatexmath.CypherMathDefinitions.render {
                org.scilab.forge.jlatexmath.NewEnvironmentMacro.addNewEnvironment("hermesrecursive", "\\begin{hermesrecursive}", "\\end{hermesrecursive}", 0)
                ru.noties.jlatexmath.JLatexMathDrawable.builder("\\begin{hermesrecursive}x\\end{hermesrecursive}").textSize(18f).build()
            }
        }
        assertFalse(org.scilab.forge.jlatexmath.MacroInfo.Commands.containsKey("hermesloop"))
        assertFalse(org.scilab.forge.jlatexmath.MacroInfo.Commands.containsKey("hermesfinite@env"))
        assertFalse(org.scilab.forge.jlatexmath.MacroInfo.Commands.containsKey("hermesrecursive@env"))
        assertTrue(render("x+1") > 0)
    }
}
