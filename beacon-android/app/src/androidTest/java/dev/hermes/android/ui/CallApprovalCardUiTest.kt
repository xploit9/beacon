package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.CallToolApprovalDialog
import dev.hermes.android.voice.CallToolApproval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Inject through Android's top window at the tagged control, as an owner's touch does. */
internal fun androidx.compose.ui.test.junit4.ComposeTestRule.touchCallControl(tag: String) {
    val node = onNodeWithTag(tag).fetchSemanticsNode()
    val x = node.positionOnScreen.x + node.size.width / 2f
    val y = node.positionOnScreen.y + node.size.height / 2f
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val at = android.os.SystemClock.uptimeMillis()
    for ((action, time) in listOf(android.view.MotionEvent.ACTION_DOWN to at, android.view.MotionEvent.ACTION_UP to at + 60)) {
        val event = android.view.MotionEvent.obtain(at, time, action, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
    }
    waitForIdle()
}

/** D1: the call tool approval is a compact card. It never covers End call or the watched phone, and Back still denies. */
@RunWith(AndroidJUnit4::class)
class CallApprovalCardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun bounds(tag: String): Rect {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return Rect(node.positionOnScreen, androidx.compose.ui.geometry.Size(node.size.width.toFloat(), node.size.height.toFloat()))
    }

    @Test fun theCardLeavesTheWatchedPhoneAndEndCallVisibleAndTappable() {
        val decisions = mutableListOf<String>()
        var ended = 0
        val approval = CallToolApproval("request-1", "p".repeat(32), "write_file",
            org.json.JSONObject().put("path", "/data/Documents/Notes/plan.md").put("content", "Keep the rollback note.").toString(2))
        compose.setContent {
            MaterialTheme {
                // The call screen as it is with the phone in view: a phone panel in the middle, End call at the bottom.
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(0.4f))
                    Box(Modifier.fillMaxWidth().fillMaxHeight(0.45f).background(Color.DarkGray).testTag("phone-panel")) { Text("Phone") }
                    Button({ ended++ }, Modifier.fillMaxWidth().testTag("end-call")) { Text("End call") }
                }
                CallToolApprovalDialog(approval) { decisions += it }
            }
        }
        compose.onNodeWithTag("beacon-tool-approval").assertIsDisplayed()
        val card = bounds("beacon-tool-approval")
        for (tag in listOf("phone-panel", "end-call")) {
            val other = bounds(tag)
            assertFalse("the card covers $tag: $card over $other", card.overlaps(other))
        }
        compose.onNodeWithTag("end-call").performClick()
        assertEquals(1, ended)
        compose.onNodeWithTag("beacon-tool-allow").performClick()
        assertEquals(listOf("approve"), decisions)
    }

    private val phoneStep = CallToolApproval("request-5", "p".repeat(32), "agent_phone",
        org.json.JSONObject().put("action", "open_app").put("package", "com.google.android.deskclock").toString(2), allowCall = false, appLabel = "Clock")

    /** The call's phone view: its own window (a secure dialog, as CallPhoneView), the strip with End call, then the watched phone. */
    @androidx.compose.runtime.Composable private fun PhoneView(card: @androidx.compose.runtime.Composable () -> Unit, onEnd: () -> Unit) {
        androidx.compose.ui.window.Dialog({}, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = CallBackground) {
                dev.hermes.android.agentphone.CallPhonePanel(dev.hermes.android.voice.CallState(active = true, state = "listening", callId = "fixture"),
                    {}, onEnd, {}, {}, card = card) {
                    Box(Modifier.fillMaxSize().background(Color.DarkGray).testTag("watched-phone")) { Text("Phone") }
                }
            }
        }
    }

    // Q2 R3-F9 (the control): the app's card stays behind the phone view's window, so a tap where it is never answers it.
    @Test fun theAppsCardBehindThePhoneViewCannotBeAnswered() {
        val decisions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                CallToolApprovalDialog(phoneStep) { decisions += it }
                PhoneView(card = {}, onEnd = {})
            }
        }
        compose.onNodeWithTag("watched-phone").assertIsDisplayed()
        compose.touchCallControl("beacon-tool-allow")
        Thread.sleep(300)
        assertTrue("answered through the phone view: $decisions", compose.runOnIdle { decisions.isEmpty() })
    }

    // R3-F9: over the watched phone the same card is answered there; End call stays uncovered and the phone stays in view.
    @Test fun overTheWatchedPhoneTheCardIsAnsweredThereAndEndCallStaysUncovered() {
        val decisions = mutableListOf<String>()
        var ended = 0
        compose.setContent { MaterialTheme { PhoneView(card = { dev.hermes.android.agentphone.CallCardOverPhone(phoneStep) { decisions += it } }, onEnd = { ended++ }) } }
        compose.onNodeWithTag("beacon-tool-approval").assertIsDisplayed()
        val card = bounds("beacon-tool-approval")
        assertFalse("the card covers End call: $card", card.overlaps(bounds("call-phone-end")))
        val phone = bounds("watched-phone")
        assertTrue("the card sits over the phone's own area: $card in $phone", card.top >= phone.top && card.bottom < phone.bottom)
        assertEquals("Open Clock", action())
        compose.touchCallControl("call-phone-end")
        assertEquals(1, compose.runOnIdle { ended })
        compose.touchCallControl("beacon-tool-allow")
        compose.waitUntil(5_000) { decisions.isNotEmpty() }
        assertEquals(listOf("approve"), decisions)
        compose.touchCallControl("beacon-tool-deny")
        compose.waitUntil(5_000) { decisions.size == 2 }
        assertEquals(listOf("approve", "deny"), decisions)
    }

    /** The card's headline as read, without the isolation marks that keep the app's own name in its direction. */
    private fun action() = compose.onNodeWithTag("beacon-tool-approval-action").fetchSemanticsNode().config
        .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.joinToString { it.text }.filterNot { it in '\u2066'..'\u2069' }

    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    // R3-F9 on an unfolded width (1800x1600 at 320 dpi: 900 dp, the call beside the phone), in Arabic at 2x text, as LiveCallInterfaceTest
    // sizes the display: the card stays over the phone's pane, never wider than it, clear of End call, and a real touch answers it.
    @Test fun unfoldedInArabicAtLargeTextTheCardSitsOverThePhonePaneAndIsAnsweredThere() {
        val decisions = mutableListOf<String>()
        try {
            shell("wm size 1800x1600"); shell("wm density 320"); shell("settings put system font_scale 2.0")
            Thread.sleep(1_500)
            compose.activityRule.scenario.recreate()  // a fresh activity in the new size and text scale, before any content
            val shape = compose.activity.resources.configuration
            assertTrue("unfolded at 2x: ${shape.screenWidthDp} dp, ${shape.fontScale}", shape.screenWidthDp >= 700 && shape.fontScale >= 1.9f)
            compose.setContent {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
                    NativeLocalization("ar") { MaterialTheme { PhoneView(card = { dev.hermes.android.agentphone.CallCardOverPhone(phoneStep) { decisions += it } }, onEnd = {}) } }
                }
            }
            compose.onNodeWithTag("beacon-tool-approval").assertIsDisplayed()
            val card = bounds("beacon-tool-approval")
            val phone = bounds("watched-phone")
            val end = bounds("call-phone-end")
            assertTrue("the wide layout puts End call beside the phone: $end, $phone", end.right <= phone.left || end.left >= phone.right)
            assertFalse("the card covers End call: $card", card.overlaps(end))
            assertTrue("the card sits within the phone's pane: $card in $phone", card.left >= phone.left - 1 && card.right <= phone.right + 1 && card.top >= phone.top)
            assertTrue(action(), action().startsWith("فتح") && "Clock" in action())
            for (tag in listOf("beacon-tool-allow", "beacon-tool-deny", "beacon-tool-approval-details-toggle")) compose.onNodeWithTag(tag).assertIsDisplayed()
            capture("approval-over-phone-unfolded-ar-2x")
            compose.touchCallControl("beacon-tool-allow")
            compose.waitUntil(5_000) { decisions.isNotEmpty() }
            assertEquals(listOf("approve"), decisions)
        } finally {
            shell("wm size reset"); shell("wm density reset"); shell("settings put system font_scale 1.0")
        }
    }

    @Test fun backDeniesTheApproval() {
        val decisions = mutableListOf<String>()
        val approval = CallToolApproval("request-2", "p".repeat(32), "terminal", org.json.JSONObject().put("command", "git status").toString(2))
        val shown = mutableStateOf(true)
        compose.setContent { MaterialTheme { if (shown.value) CallToolApprovalDialog(approval) { decisions += it; shown.value = false } } }
        compose.onNodeWithTag("beacon-tool-approval").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { decisions.isNotEmpty() }
        assertEquals(listOf("deny"), decisions)
        compose.onAllNodesWithTag("beacon-tool-approval").assertCountEquals(0)
    }

    private fun capture(name: String) {
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = java.io.File(compose.activity.getExternalFilesDir(null), "approval-card-123").apply { mkdirs() }
        java.io.File(dir, "$name.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun inArabicAtDoubleTextTheCardFitsReadsInOrderAndLeavesEndCallReachable() {
        val decisions = mutableListOf<String>()
        var ended = 0
        val approval = CallToolApproval("request-3", "p".repeat(32), "write_file",
            org.json.JSONObject().put("path", "/data/Documents/Notes/plan.md").put("content", "Keep the rollback note.").toString(2))
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f),
            ) {
                dev.hermes.android.ui.NativeLocalization("ar") {
                    MaterialTheme {
                        // The watched-phone view of the call: the phone frame in the middle, End call under it.
                        Column(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().fillMaxHeight(0.55f))
                            Box(Modifier.fillMaxWidth(0.5f).fillMaxHeight(0.3f).background(Color.DarkGray).testTag("phone-panel")) { Text("Phone") }
                            CallEndButton({ ended++ }, "End call", Modifier.testTag("end-call"))
                        }
                        CallToolApprovalDialog(approval) { decisions += it }
                    }
                }
            }
        }
        compose.onNodeWithTag("beacon-tool-approval").assertIsDisplayed()
        for (tag in listOf("beacon-tool-allow", "beacon-tool-deny", "beacon-tool-approval-details-toggle")) compose.onNodeWithTag(tag).assertIsDisplayed()
        val card = bounds("beacon-tool-approval")
        assertFalse("the card covers End call at 2x text", card.overlaps(bounds("end-call")))
        assertTrue("the card stays inside the screen width", card.left >= 0f && card.right <= compose.activity.resources.displayMetrics.widthPixels.toFloat())
        compose.onNodeWithTag("end-call").performClick()
        assertEquals(1, ended)
        capture("approval-card-ar-2x")
    }

    @Test fun screenReaderGoesAllowOnceThenDenyThenDetails() {
        val approval = CallToolApproval("request-4", "p".repeat(32), "terminal", org.json.JSONObject().put("command", "git status").toString(2))
        compose.setContent { MaterialTheme { CallToolApprovalDialog(approval) { } } }
        compose.onNodeWithTag("beacon-tool-approval").assertIsDisplayed()
        capture("approval-card-en")
        // Compose hands a screen reader these indexes: lower is read first, whatever the on-screen order (Details, Deny, Allow once).
        fun index(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TraversalIndex]
        assertTrue(index("beacon-tool-allow") < index("beacon-tool-deny"))
        assertTrue(index("beacon-tool-deny") < index("beacon-tool-approval-details-toggle"))
        // The card announces itself first, by its pane title.
        assertEquals("Allow this action?", compose.onNodeWithTag("beacon-tool-approval").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.PaneTitle])
    }
}
