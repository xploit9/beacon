package dev.hermes.android.uiux.v2b

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconTheme
import dev.hermes.android.ui.ChatListRow
import dev.hermes.android.ui.RowAction
import dev.hermes.android.ui.ScheduleStatusChip
import dev.hermes.android.ui.SettingsTextFieldRow
import dev.hermes.android.ui.SpaceListRow
import dev.hermes.android.ui.UpNextRow
import dev.hermes.android.ui.nativeMetadataStyle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * Full-pass visual fixes, part B, on a device: Latin names beside their avatar in a right-to-left list, the larger small text, the failed
 * schedule's reason, and the labelled name field. Synthetic rows only; no Hermes and no network.
 */
class VisualFixesV2bUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val density get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    private fun show(rtl: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) = compose.setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            LocalDensity provides Density(base.density, fontScale = fontScale),
        ) {
            BeaconTheme(dark = true) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box { Column(Modifier.width(360.dp)) { content() } } } }
        }
    }

    /** Where the first line of the text [text] starts and ends, in pixels from the left edge of the screen. */
    private fun box(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun lineSpan(text: String): Pair<Float, Float> {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        val read = node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) ?: false
        assertTrue("no text layout for \"$text\"", read && results.isNotEmpty())
        val x = node.positionInRoot.x
        return x + results[0].getLineLeft(0) to x + results[0].getLineRight(0)
    }

    // Item 1: Latin titles sit against the avatar in an Arabic list.

    @Test fun inArabicALatinNameAndItsPreviewSitRightBesideTheAvatar() {
        show(rtl = true) {
            ChatListRow(id = "alpha", title = "Alpha one", preview = "gpt-6-luna", time = "المساحات: 1")
        }
        compose.waitForIdle()
        val avatarStart = compose.onAllNodesWithTag("chat-avatar", useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot.left
        val gap = 12 * density
        // The avatar is at the start (right) edge; the name and the preview end one gap from it.
        for (text in listOf("Alpha one", "gpt-6-luna")) {
            val (_, end) = lineSpan(text)
            assertTrue("\"$text\" ends ${avatarStart - end}px from the avatar, wanted about ${gap}px", abs((avatarStart - end) - gap) <= 2f)
        }
    }

    @Test fun inEnglishANameStillSitsBesideTheAvatarOnTheLeft() {
        show(rtl = false) {
            ChatListRow(id = "alpha", title = "Alpha one", preview = "gpt-6-luna", time = "Spaces: 1")
        }
        compose.waitForIdle()
        val avatarEnd = compose.onAllNodesWithTag("chat-avatar", useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot.right
        val gap = 12 * density
        for (text in listOf("Alpha one", "gpt-6-luna")) {
            val (start, _) = lineSpan(text)
            assertTrue("\"$text\" starts ${start - avatarEnd}px from the avatar", abs((start - avatarEnd) - gap) <= 2f)
        }
    }

    @Test fun inArabicTheUpNextAndSpaceRowsAlignToTheStartEdgeToo() {
        show(rtl = true) {
            UpNextRow("Morning brief", "Bravo two", "bravo", "12:00", "up-next", {})
            SpaceListRow("Charlie, Delta", listOf("Charlie" to "charlie", "Delta" to "delta"), "Bots: 2", "الخميس", false, emptyList<RowAction>(), "space-row", {})
        }
        compose.waitForIdle()
        val screenWidth = 360 * density
        for (text in listOf("Morning brief", "Bravo two", "Charlie, Delta", "Bots: 2")) {
            val (_, end) = lineSpan(text)
            // Each ends next to its avatar, which sits 12 dp in from the right edge of the row plus the avatar and a gap: far from the left edge.
            assertTrue("\"$text\" ends at ${end}px of $screenWidth, so it is not at the start (right) edge", end > screenWidth * .6f)
        }
    }

    @Test fun theSpacesLabelIsFarFromAShortNameInArabic() {
        show(rtl = true) {
            ChatListRow(id = "alpha", title = "Personal", preview = "gpt-6-luna", time = "المساحات: 1")
        }
        compose.waitForIdle()
        val (nameStart, nameEnd) = lineSpan("Personal")
        val labelEnd = box("المساحات: 1").right // the label wraps its text, so its box is where its glyphs are
        // The name is at the avatar's side (right) and the label at the other end (left), with the row's width between them.
        assertTrue("name spans $nameStart..$nameEnd, label ends at $labelEnd", nameStart - labelEnd > 80 * density)
    }

    @Test fun aLongNameIsCutBeforeItReachesTheSpacesLabel() {
        val name = "A rather long teammate name that needs more than the whole row to say"
        show(rtl = true) {
            ChatListRow(id = "alpha", title = name, preview = "gpt-6-luna", time = "المساحات: 12")
        }
        compose.waitForIdle()
        val (nameStart, _) = lineSpan(name)
        val labelEnd = box("المساحات: 12").right // the label wraps its text, so its box is where its glyphs are
        // The name's slot ends where the label's gap begins: the two never overlap.
        assertTrue("label ends at $labelEnd, name starts at $nameStart", labelEnd <= nameStart + 1f)
    }

    // Item 2: small text in Arabic.

    @Test fun smallTextInArabicIsTwelveAndAHalfSp() {
        var style: TextStyle? = null
        var labelSmall: TextStyle? = null
        show(rtl = true) { style = nativeMetadataStyle(); labelSmall = MaterialTheme.typography.labelSmall; Text("12:00") }
        compose.waitForIdle()
        assertEquals(12.5f, style!!.fontSize.value, 0f)
        assertTrue(style!!.fontSize.value > labelSmall!!.fontSize.value)
    }

    @Test fun smallTextInEnglishKeepsLabelSmall() {
        var style: TextStyle? = null
        var labelSmall: TextStyle? = null
        show(rtl = false) { style = nativeMetadataStyle(); labelSmall = MaterialTheme.typography.labelSmall; Text("12:00") }
        compose.waitForIdle()
        assertEquals(labelSmall!!.fontSize, style!!.fontSize)
    }

    // Item 3: a Failed chip shows a short reason.

    private fun job(error: String) = JSONObject().put("id", "j").put("state", "scheduled").put("next_run_at", "2026-11-01T08:00:00Z")
        .put("last_status", "error").put("last_error", error)

    @Test fun aFailedChipShowsItsReasonBesideIt() {
        show(rtl = false) { ScheduleStatusChip(job("Gateway timed out")) }
        compose.onNodeWithTag("schedule-status:j").assertIsDisplayed()
        compose.onNodeWithText("Failed").assertIsDisplayed()
        compose.onNodeWithTag("schedule-failure:j").assertIsDisplayed().assertTextContains("Gateway timed out")
    }

    @Test fun aFailedChipWithoutAReasonIsJustTheChip() {
        show(rtl = false) { ScheduleStatusChip(job("")) }
        compose.onNodeWithTag("schedule-status:j").assertIsDisplayed()
        compose.onNodeWithText("Failed").assertIsDisplayed()
        compose.onAllNodesWithTag("schedule-failure:j").assertCountEquals(0)
    }

    @Test fun aFailedChipAndItsReasonStillShowAtLargeText() {
        show(rtl = false, fontScale = 1.3f) { ScheduleStatusChip(job("The model returned an empty answer, so nothing was sent to your chat")) }
        compose.onNodeWithTag("schedule-status:j").assertIsDisplayed()
        compose.onNodeWithTag("schedule-failure:j").assertIsDisplayed()
    }

    // Item 5: "What Beacon calls you" keeps a label, empty or filled.

    @Test fun theNameFieldKeepsItsLabelWhetherEmptyOrFilled() {
        show(rtl = false) {
            SettingsTextFieldRow("What Beacon calls you", "", {}, placeholder = "Optional", fieldTestTag = "empty-name")
            SettingsTextFieldRow("What Beacon calls you", "Sam", {}, placeholder = "Optional", fieldTestTag = "filled-name")
        }
        compose.waitForIdle()
        assertTrue("both fields show the label", compose.onAllNodesWithText("What Beacon calls you", useUnmergedTree = true).fetchSemanticsNodes().size >= 2)
    }
}
