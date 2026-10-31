package dev.hermes.android

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.RichSandbox
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the shipped offline bundle through the app's real WebView and page policy. */
class RichSandboxCompatibilityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun web(view: View = compose.activity.window.decorView): WebView? {
        if (view is WebView && view.contentDescription == "Mermaid diagram preview") return view
        if (view is ViewGroup) for (index in 0 until view.childCount) web(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun evaluate(script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        compose.runOnUiThread { requireNotNull(web()).evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue("WebView evaluation timed out", latch.await(3, TimeUnit.SECONDS))
        return result
    }

    private fun render(source: String, dark: Boolean = false) {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        check(!app.calls.state.value.active && app.calls.service == null) { "Never disturb an active call" }
        compose.setContent { NativeLocalization("en") { BeaconTheme(dark = dark) { RichSandbox(source, diagram = true) } } }
        compose.waitUntil(20_000) { web() != null && evaluate("document.readyState === 'complete' && document.getElementById('failed') !== null && document.getElementById('status') === null") == "true" }
        compose.runOnUiThread {
            val view = requireNotNull(web())
            assertTrue(view.settings.blockNetworkLoads)
            assertFalse(view.settings.allowFileAccess)
            assertFalse(view.settings.allowContentAccess)
            assertFalse(view.settings.domStorageEnabled)
        }
    }

    private fun math(dark: Boolean) {
        render("flowchart TD\n A[\"$$\\frac{a}{b}+x^2$$\"] --> B[\"`**Ready**`\"]", dark)
        val json = evaluate("""JSON.stringify((()=>{
            const math=document.querySelector('math'), svg=document.querySelector('svg');
            const box=math?.getBoundingClientRect(), style=svg && getComputedStyle(svg);
            return {svg:!!svg, native:typeof MathMLElement!=='undefined', math:!!math,
              fraction:!!document.querySelector('mfrac'), power:!!document.querySelector('msup'),
              width:box?.width||0,height:box?.height||0, color:style?.color||'',
              ready:document.body.textContent.includes('Ready'),
              fallback:document.body.textContent.includes('MathML is unsupported')};
        })())""")
        val result = JSONObject(JSONTokener(json).nextValue() as String)
        assertTrue("Bundled flowchart must render: $result", result.getBoolean("svg"))
        assertTrue(result.getBoolean("ready"))
        if (result.getBoolean("native")) {
            assertTrue("Native MathML must retain fraction and superscript: $result", result.getBoolean("math") && result.getBoolean("fraction") && result.getBoolean("power"))
            assertTrue(result.getDouble("width") > 0 && result.getDouble("height") > 0)
        } else assertTrue(result.getBoolean("fallback"))
        assertTrue(result.getString("color").isNotEmpty())
        val painted = CountDownLatch(1)
        compose.runOnUiThread {
            requireNotNull(web()).postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { requireNotNull(web()).invalidate(); painted.countDown() }
            })
        }
        assertTrue("WebView visual state timed out", painted.await(5, TimeUnit.SECONDS))
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350, 3000)
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = File(ApplicationProvider.getApplicationContext<BeaconApplication>().filesDir, "renderer-compatibility").apply { mkdirs() }
        try { File(directory, if (dark) "math-dark.png" else "math-light.png").outputStream().use { assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { screenshot.recycle() }
    }

    @Test fun offlineMathAndMarkdownInLightTheme() = math(false)
    @Test fun offlineMathAndMarkdownInDarkTheme() = math(true)

    @Test fun sequenceDiagramStillRenders() {
        render("sequenceDiagram\n Alice->>Bob: Ready\n Bob-->>Alice: Confirmed")
        assertEquals("true", evaluate("!!document.querySelector('svg') && document.body.textContent.includes('Confirmed')"))
    }

    @Test fun invalidDiagramShowsTheExistingReadableFallback() {
        render("not-a-diagram")
        assertEquals("true", evaluate("!document.querySelector('svg') && document.querySelector('.mermaid').textContent.includes('Diagram could not be rendered')"))
    }
}
