package dev.hermes.android.agentphone

import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconTheme
import dev.hermes.android.data.*
import dev.hermes.android.ui.*
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Checks that need the device's own settings (Root's follow-up 1):
 * - the Remove dialog at the device's font scale (its own window takes the system setting, not a test's density): the lane sets
 *   `settings put system font_scale` to 1.5 and 2.0, runs these, and restores it; each run saves remove-dialog-<scale>-<look>.png;
 * - [hold] keeps one screen up in real time (instrumentation argument `screen` = welcome | page | dialog, `seconds`) with
 *   accessibility services left running, so TalkBack can be used on it and the welcome's dust recorded. Without `screen` it does nothing.
 */
class AgentPhoneDeviceChecksTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val notSetUp = """{"installed": false, "phone": "off", "install": {"state": "idle"}}"""
    private val on = """{"installed": true, "profile": "default", "yours": true, "phone": "on", "lease": {"holder": "agent", "you": false, "epoch": 3, "away": false},
        "install": {"state": "idle"}, "setup": {"google": true, "chrome": 136, "chrome_autofill": true, "logins": 3}, "storage_bytes": 10522669875,
        "helper": "ready", "in_use": false}"""
    private val activity = listOf(
        AgentPhoneActivity(1_794_916_000_000L, "Kanto Foods", "tap", "Place order", true, "done"),
        AgentPhoneActivity(1_794_915_400_000L, "Kanto Foods", "fill", "", false, "done"),
    )
    private val onPage = AgentPhoneUi(status = parseAgentPhoneStatus(JSONObject(on)), loaded = true,
        policy = AgentPhonePolicy(true, AgentPhoneAsk.PaymentsAndSends), activity = activity)
    private val failedStart = """{"installed": true, "profile": "default", "yours": true, "phone": "off", "install": {"state": "idle"},
        "error": "The phone's process could not start.", "helper": "missing"}"""

    /** The screen the hold shows, by name: the walk covers the welcome, the page's top and lower half, a notice, a failed start. */
    private fun holdState(screen: String): AgentPhoneUi = when (screen) {
        "welcome" -> AgentPhoneUi(status = parseAgentPhoneStatus(JSONObject(notSetUp)), loaded = true)
        "page-end" -> onPage.copy(status = parseAgentPhoneStatus(JSONObject(on.replace(""""helper": "ready"""", """"helper": "update""""))))
        "page-needs-you" -> onPage.copy(needsYou = listOf(AgentPhoneNeedsYou("c".repeat(32), "The Kanto Foods login didn't work.", "Kanto Foods")))
        "page-failed" -> onPage.copy(status = parseAgentPhoneStatus(JSONObject(failedStart)))
        else -> onPage
    }

    @Composable private fun Look(dark: Boolean, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalAssistantName provides "Maya") {
            NativeLocalization("en") { BeaconTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            } }
        }
    }

    private fun removeDialog(dark: Boolean) {
        val scale = compose.activity.resources.configuration.fontScale
        compose.setContent { Look(dark) { AgentPhoneRemoveDialog(10_522_669_875L, removing = false, onRemove = {}, onDismiss = {}) } }
        compose.waitForIdle()
        val problems = mutableListOf<String>()
        val density = compose.activity.resources.displayMetrics.density
        val nodes = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
        for (node in nodes) {
            if (SemanticsActions.GetTextLayoutResult in node.config) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                val layout = layouts.firstOrNull()
                if (layout != null && layout.lineCount > 0) {
                    val text = layout.layoutInput.text.text
                    val last = layout.lineCount - 1
                    if (layout.isLineEllipsized(last) || layout.getLineEnd(last, visibleEnd = true) < text.trimEnd().length) problems += "text cut: $text"
                }
            }
            if (SemanticsActions.OnClick in node.config && (node.size.width < 48f * density - 1f || node.size.height < 48f * density - 1f))
                problems += "small target ${node.config.getOrNull(SemanticsProperties.TestTag) ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()} " +
                    "${node.size.width / density}x${node.size.height / density} dp"
        }
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("agent-phone-fu1")
        if (dir != null) {
            dir.mkdirs()
            val image = compose.onNode(isDialog()).captureToImage()
            File(dir, "remove-dialog-$scale-${if (dark) "dark" else "light"}.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        assertTrue("font scale $scale: ${problems.joinToString("; ")}", problems.isEmpty())
    }

    @Test fun theRemoveDialogFitsAtTheDevicesFontScaleInDark() = removeDialog(dark = true)
    @Test fun theRemoveDialogFitsAtTheDevicesFontScaleInLight() = removeDialog(dark = false)

    @Test fun hold() {
        val args = InstrumentationRegistry.getArguments()
        val screen = args.getString("screen") ?: return
        val seconds = args.getString("seconds")?.toLongOrNull() ?: 30L
        val dark = args.getString("look") != "light"
        // Keep TalkBack running while this test drives the screen (UiAutomation would otherwise suppress it).
        val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        // The welcome's dust is an infinite animation: a test cancels one that starts while its clock auto-advances, so the clock goes
        // manual before it is composed. The page screens have none, and keep the automatic clock for their scroll.
        val welcome = screen == "welcome"
        if (welcome) compose.mainClock.autoAdvance = false
        compose.setContent {
            Look(dark) {
                Column(Modifier.fillMaxSize()) {
                    Header(uiText("{name}'s phone").named(), back = {})
                    AgentPhonePage(holdState(screen), "default", AgentPhonePageActions(), Modifier.weight(1f))
                }
                if (screen == "dialog") AgentPhoneRemoveDialog(10_522_669_875L, removing = false, onRemove = {}, onDismiss = {})
            }
        }
        if (!welcome) {
            compose.waitForIdle()
            // The lower half of the page: scrolled to its end before the walk.
            if (screen == "page-end") compose.onNodeWithTag("agent-phone-page").performScrollToNode(hasTestTag("agent-phone-remove-section"))
            compose.mainClock.autoAdvance = false
        }
        frames(seconds * 1000)
        if (args.getString("walk") == "true") talkBackWalk(automation, screen)
    }

    /** Frames in real time, so the dust moves as on a phone and TalkBack's focus lands on a live screen. */
    private fun frames(millis: Long) {
        val end = SystemClock.uptimeMillis() + millis
        while (SystemClock.uptimeMillis() < end) {
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(16)
        }
    }

    /**
     * With TalkBack running: moves accessibility focus to each item TalkBack stops on, top to bottom, and lets TalkBack speak it.
     * Each step logs what the item gives TalkBack to read (text, label, role, heading, state) to talkback-<screen>.txt and saves a
     * screenshot with TalkBack's focus frame.
     */
    private fun talkBackWalk(automation: UiAutomation, screen: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("agent-phone-fu1"), "talkback").apply { mkdirs() }
        val root = automation.rootInActiveWindow ?: error("no active window")
        val stops = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, inside: Boolean) {
            if (!node.isVisibleToUser) return
            val readable = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            val stop = node.isScreenReaderFocusable || node.isClickable || node.isCheckable || (readable && !inside)
            if (stop) stops += node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, inside || stop) }
        }
        visit(root, inside = false)
        val lines = mutableListOf<String>()
        stops.forEachIndexed { index, node ->
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            frames(1800)
            val words = listOfNotNull(node.contentDescription?.toString(), node.text?.toString()).filter(String::isNotBlank).distinct().joinToString(" | ")
            val kind = node.className?.toString()?.substringAfterLast('.').orEmpty()
            val state = listOfNotNull(
                "heading".takeIf { node.isHeading }, "button".takeIf { node.isClickable && !node.isCheckable },
                (if (node.isChecked) "checked" else "not checked").takeIf { node.isCheckable }, node.stateDescription?.toString(),
                "disabled".takeIf { !node.isEnabled },
            ).joinToString(", ")
            lines += "%02d  %s  [%s] %s".format(index, words.ifBlank { "(nothing to read)" }, kind, state)
            automation.takeScreenshot()?.let { shot ->
                File(dir, "$screen-%02d.png".format(index)).outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
            }
        }
        File(dir, "talkback-$screen.txt").writeText(lines.joinToString("\n") + "\n")
        android.util.Log.i("A11Y-TOUR", "$screen: ${stops.size} stops\n" + lines.joinToString("\n"))
    }
}
