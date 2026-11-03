package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallToolReceipt
import dev.hermes.android.voice.CallTranscript
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The live call window fits the phone it runs on: one fixed layout with no scroll of its own, the controls whole at the bottom,
 * the tool window and the presence never over them. Only the caption card and the notice blocks scroll, and only inside themselves.
 * Synthetic call state only; no call, sensor or provider is touched.
 */
@RunWith(AndroidJUnit4::class)
class LiveCallFitUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Screen(val name: String, val width: Int, val height: Int, val fontScale: Float = 1f, val bottomBar: Int = 0, val rtl: Boolean = false)

    private var screen by mutableStateOf(Screen("start", 360, 640))
    private var call by mutableStateOf(busyCall())
    private var darkTheme by mutableStateOf(true)
    private val actions = CallUiActions(mute = {}, camera = {}, flip = {}, allowBubble = {}, end = {}, route = {})

    /**
     * A call with everything on it: the tool window (its approval outcome, a context notice and the save state are on the state too,
     * to show the call screen draws no line for them), two caption lines.
     */
    private fun busyCall() = CallState(
        active = true, state = "speaking", callId = "fit-busy",
        toolReceipts = listOf(requireNotNull(CallToolReceipt.from(JSONObject()
            .put("request_id", "request-1").put("tool_name", "mcp__gmail__search_messages")
            .put("status", "running").put("approved_by", "chat_yolo")))),
        transcripts = listOf(
            CallTranscript("fit-busy:user:1", "user", "Could you check my inbox and tell me what needs a reply today, and also move my three o'clock to the afternoon?"),
            CallTranscript("fit-busy:assistant:1", "assistant", "Yes. Two messages need a reply and the three o'clock can move to the afternoon. I will check the calendar first, then tell you what I found in each place."),
        ),
        transcriptStatus = "unavailable",
        contextNotice = "Earlier parts of this conversation were shortened to keep the call going.",
    )

    private fun quietCall() = CallState(active = true, state = "listening", callId = "fit-quiet")

    private fun show(language: String = "en") = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, screen.fontScale),
            LocalLayoutDirection provides if (screen.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            NativeLocalization(language) { BeaconTheme(dark = darkTheme) {
                // requiredSize: the shape is tested whatever the size of the device window is.
                Box(Modifier.requiredSize(screen.width.dp, screen.height.dp).testTag("fit-screen")) {
                    // The device window's own bars are not part of the simulated screen: the app's Scaffold consumes them before the call
                    // window, so they are consumed here too, and a simulated bottom bar is the only inset.
                    Box(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.safeDrawing).padding(bottom = screen.bottomBar.dp)) {
                        LiveCallSurface(call, actions, false, Modifier.fillMaxSize(), onConversation = {}) { Box(it) }
                    }
                }
            } }
        }
    }

    private fun move(next: Screen, state: CallState = call) {
        compose.runOnIdle { screen = next; call = state }
        compose.waitForIdle()
    }

    /** Bounds relative to the test screen, so the check does not depend on where the device window puts it. */
    private fun bounds(tag: String): DpRect {
        val origin = compose.onNodeWithTag("fit-screen").getUnclippedBoundsInRoot()
        val b = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        return DpRect(b.left - origin.left, b.top - origin.top, b.right - origin.left, b.bottom - origin.top)
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun presenceTag() = if (exists("beacon-call-hero")) "beacon-call-hero" else "beacon-voice-presence"

    private fun overlaps(a: DpRect, b: DpRect) =
        a.left.value < b.right.value - .5f && b.left.value < a.right.value - .5f && a.top.value < b.bottom.value - .5f && b.top.value < a.bottom.value - .5f

    private fun assertInside(label: String, tag: String, s: Screen) {
        val b = bounds(tag)
        val bottom = (s.height - s.bottomBar).toFloat()
        assertTrue("$label: $tag is inside the ${s.width} x $bottom dp window: $b",
            b.left.value >= -.6f && b.top.value >= -.6f && b.right.value <= s.width + .6f && b.bottom.value <= bottom + .6f)
    }

    private val scrolls = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun assertFits(s: Screen, busy: Boolean) {
        val label = "${s.name} ${s.width}x${s.height}${if (s.fontScale != 1f) " @${s.fontScale}x" else ""}${if (s.rtl) " rtl" else ""}${if (busy) " with tool window" else ""}"
        val presence = presenceTag()
        // The window itself has no vertical scroll: the surface is not scrollable and neither the presence nor the controls sit in a scrolling container.
        compose.onNodeWithTag("beacon-live-call-surface").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.ScrollBy))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange))
        for (tag in listOf(presence, "beacon-call-dock", "beacon-hang-up", "beacon-mute", "beacon-speaker", "beacon-camera", "beacon-share-screen", "beacon-call-status", "beacon-view-conversation"))
            compose.onNodeWithTag(tag).assert(hasAnyAncestor(scrolls).not())
        // Everything the call shows is whole inside the window, the controls above the bottom bar.
        val shown = mutableListOf(presence, "beacon-call-controls", "beacon-call-dock", "beacon-hang-up", "beacon-mute", "beacon-speaker", "beacon-camera", "beacon-share-screen",
            "beacon-call-status", "beacon-call-timer", "beacon-view-conversation")
        if (busy) shown += listOf("beacon-call-tool-chip", "beacon-live-captions")
        for (tag in shown) assertInside(label, tag, s)
        // Presence, tool window, captions and controls never overlap each other or the header.
        val parts = shown.filter { it in setOf(presence, "beacon-call-tool-chip", "beacon-live-captions", "beacon-call-controls", "beacon-call-status") }
        for (i in parts.indices) for (j in i + 1 until parts.size)
            assertTrue("$label: ${parts[i]} and ${parts[j]} do not overlap: ${bounds(parts[i])} vs ${bounds(parts[j])}", !overlaps(bounds(parts[i]), bounds(parts[j])))
        // The presence is still a presence, not a sliver.
        val hero = bounds(presence)
        assertTrue("$label: the presence keeps some size: $hero", hero.bottom.value - hero.top.value >= 56f && hero.right.value - hero.left.value >= 56f)
        // In portrait the controls sit at the bottom edge.
        if (s.width < s.height) assertTrue("$label: the controls are pinned to the bottom: ${bounds("beacon-call-controls")}",
            (s.height - s.bottomBar) - bounds("beacon-call-controls").bottom.value <= 8.5f)
    }

    private val phones = listOf(
        Screen("small phone", 360, 560),
        Screen("small phone", 360, 640),
        Screen("small phone, large text", 360, 640, fontScale = 1.3f),
        Screen("normal phone", 411, 891),
        Screen("normal phone, large text", 411, 891, fontScale = 1.3f),
        Screen("normal phone with a navigation bar", 411, 891, bottomBar = 48),
        Screen("unfolded", 840, 900),
        Screen("unfolded, large text", 840, 900, fontScale = 1.3f),
        Screen("landscape phone", 800, 360),
        Screen("landscape phone, large text", 915, 412, fontScale = 1.3f),
        Screen("landscape unfolded", 1100, 820),
    )

    @Test fun theCallWindowFitsEveryPhoneWithTheToolWindowShown() {
        show()
        for (s in phones) { move(s, busyCall()); assertFits(s, busy = true) }
    }

    @Test fun aQuietCallFitsEveryPhoneToo() {
        show()
        for (s in phones) { move(s, quietCall()); assertFits(s.copy(name = "${s.name}, quiet"), busy = false) }
    }

    @Test fun theCallWindowFitsInArabicRightToLeft() {
        // Read the catalog first, so the first frame is already Arabic and nothing moves while the checks run.
        (compose.activity.applicationContext as BeaconApplication).readTranslations()
        show("ar")
        for (s in listOf(Screen("small phone", 360, 640), Screen("small phone, large text", 360, 640, fontScale = 1.3f), Screen("normal phone", 411, 891), Screen("landscape phone", 800, 360)).map { it.copy(rtl = true) }) {
            move(s, busyCall()); assertFits(s, busy = true)
        }
    }

    @Test fun aShortScreenGetsOneRowOfControlsAndATallOneKeepsTwo() {
        show()
        move(Screen("short", 360, 560), busyCall())
        val row = listOf("beacon-camera", "beacon-share-screen", "beacon-mute", "beacon-speaker", "beacon-hang-up").map { bounds(it) }
        assertTrue("one row: ${row.map { it.top }}", row.all { kotlin.math.abs(it.top.value - row.first().top.value) <= 14f })
        move(Screen("tall", 411, 891), busyCall())
        assertTrue("two rows: Camera and Share sit above Mute", bounds("beacon-share-screen").bottom.value <= bounds("beacon-mute").top.value)
    }

    @Test fun scrollingTheCallNeverMovesThePresenceOrTheControls() {
        val s = Screen("small phone", 360, 560)
        show()
        move(s, busyCall())
        val before = listOf(presenceTag(), "beacon-call-dock", "beacon-hang-up", "beacon-call-tool-chip").map { it to bounds(it) }
        // A swipe on the presence, on the caption card and on the controls moves nothing: the window is not a scroll container.
        for (tag in listOf(presenceTag(), "beacon-live-captions", "beacon-call-dock")) {
            compose.onNodeWithTag(tag).performTouchInput { swipeUp() }
            compose.waitForIdle()
            for ((name, was) in before) assertEquals("$name stays put after a swipe on $tag", was, bounds(name))
        }
    }

    @Test fun onlyTheCaptionCardMayScrollAndOnlyWhenItsTextOverflows() {
        show()
        // A short exchange needs no scroll: the card is exactly as tall as its text and the range is empty.
        val short = quietCall().copy(transcripts = listOf(CallTranscript("fit-quiet:user:1", "user", "Hi"), CallTranscript("fit-quiet:assistant:1", "assistant", "Hello")))
        move(Screen("normal phone", 411, 891), short)
        val range = scrollRanges(compose.onNodeWithTag("beacon-live-captions").fetchSemanticsNode())
        assertTrue("the caption card scrolls inside itself", range.isNotEmpty())
        assertTrue("a short exchange fits without scrolling: ${range.map { it.maxValue() }}", range.all { it.maxValue() <= 0f })
    }

    @Test fun theCallScreenCarriesNoExplanatoryLines() {
        show()
        move(Screen("normal phone", 411, 891), busyCall())
        // Not the server's context notice, not the save state of the transcript, not the tool's approval caption.
        for (tag in listOf("beacon-context-notice", "beacon-tool-approval-caption")) compose.onAllNodesWithTag(tag).assertCountEquals(0)
        for (text in listOf("Earlier parts of this conversation were shortened to keep the call going.", "Some of this call wasn't saved to the chat",
            "Saving to the chat", "Saved to the chat", "Approvals skipped in this chat"))
            compose.onAllNodesWithText(text).assertCountEquals(0)
        // The tool and the controls are still there.
        compose.onNodeWithTag("beacon-call-tool-chip").assertExists()
        compose.onNodeWithTag("beacon-hang-up").assertExists()
    }

    @Test fun theToolIsNamedInOnePlaceAndTheHeaderSaysOnlyTheState() {
        show()
        move(Screen("normal phone", 411, 891), busyCall())
        compose.onNodeWithTag("beacon-call-status").assertTextEquals("Speaking")
        compose.onNodeWithTag("beacon-call-tool-status").assertTextEquals("Using Gmail…")
        compose.onAllNodesWithText("Using Gmail…").assertCountEquals(1)
        compose.onAllNodesWithText("Checking Gmail…").assertCountEquals(0)
    }

    /** The speaker has no output to choose from in a bare call, so it is disabled; it must still be seen on the always-dark call surface in either theme. */
    @Test fun theDisabledSpeakerIsVisibleInTheLightThemeAsInTheDarkOne() {
        show()
        for (dark in listOf(false, true)) {
            compose.runOnIdle { darkTheme = dark }
            move(Screen("normal phone", 411, 891), quietCall())
            compose.onNodeWithTag("beacon-speaker").assertIsNotEnabled()
            val pixels = compose.onNodeWithTag("beacon-speaker").captureToImage().toPixelMap()
            var brightest = 0f
            for (x in 0 until pixels.width) for (y in 0 until pixels.height) brightest = maxOf(brightest, pixels[x, y].luminance())
            assertTrue("the speaker's glyph is light enough to see (dark theme: $dark, brightest pixel $brightest)", brightest > .35f)
            // And the fill is the call surface's own dark control colour, not the theme's near-white disabled fill.
            val corner = pixels[pixels.width / 2, 2]
            assertTrue("the speaker's fill is dark (dark theme: $dark, luminance ${corner.luminance()})", corner.luminance() < .2f)
        }
    }

    private fun scrollRanges(node: SemanticsNode): List<ScrollAxisRange> =
        (if (SemanticsProperties.VerticalScrollAxisRange in node.config) listOf(node.config[SemanticsProperties.VerticalScrollAxisRange]) else emptyList()) +
            node.children.flatMap { scrollRanges(it) }
}
