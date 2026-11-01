package dev.hermes.android.uiux.defects

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.hermes.android.data.NativeMemoryDocument
import dev.hermes.android.data.NativeMemoryState
import dev.hermes.android.ui.CallTranscriptCard
import dev.hermes.android.ui.CommandRow
import dev.hermes.android.ui.NativeMemoryScreen
import dev.hermes.android.ui.SettingsOption
import dev.hermes.android.ui.SettingsOptionCards
import dev.hermes.android.uiux.gaps.showPage
import dev.hermes.android.voice.CallTranscript
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Layout defects of the visual tour on a device: large text on the Memory notes and option cards, and English inside the right-to-left interface. */
class DefectsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first()
    }
    private fun largeText(content: @androidx.compose.runtime.Composable () -> Unit): @androidx.compose.runtime.Composable () -> Unit = {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) { content() }
    }

    // Large text

    private val longNote = "Maya's inbox, calendar and tasks are available through the 'personal' MCP server (read-only) and the work calendar is only shared on weekdays."

    private fun memory(note: String = longNote) = NativeMemoryState(
        scope = "synthetic", documents = listOf(NativeMemoryDocument("memory", note, "r1", true, 2200)),
    )

    @Test fun aLongMemoryNoteEndsInAnEllipsisNotInAHalfLine() {
        compose.showPage(largeText { NativeMemoryScreen(memory(), {}, {}, {}, {}) })
        val note = compose.onNode(hasText(longNote, substring = true), useUnmergedTree = true)
        val folded = layout(note)
        assertEquals("Three lines at most", 3, folded.lineCount)
        assertTrue("The cut ends in an ellipsis", folded.hasVisualOverflow)
        // A note that folds opens on tap, and then shows every line.
        compose.onNodeWithTag("memory-note-0").assertHasClickAction().performClick()
        val opened = layout(compose.onNode(hasText(longNote, substring = true), useUnmergedTree = true))
        assertTrue(opened.lineCount > 3)
        assertFalse(opened.hasVisualOverflow)
        compose.onNodeWithTag("memory-note-0").performClick()
        assertEquals(3, layout(compose.onNode(hasText(longNote, substring = true), useUnmergedTree = true)).lineCount)
    }

    @Test fun aShortMemoryNoteIsWholeAndHasNothingToOpen() {
        compose.showPage(largeText { NativeMemoryScreen(memory("Window cleaner comes Friday."), {}, {}, {}, {}) })
        assertFalse(layout(compose.onNode(hasText("Window cleaner comes Friday."), useUnmergedTree = true)).hasVisualOverflow)
        compose.onNodeWithTag("memory-note-0").assertHasNoClickAction()
    }

    @Test fun anOptionLabelTakesTwoLinesBeforeItIsCut() {
        compose.showPage(largeText {
            androidx.compose.foundation.layout.Box(Modifier.width(300.dp)) {
                SettingsOptionCards(listOf(SettingsOption("a", "Balanced tone"), SettingsOption("b", "Short")), selected = "b", onSelect = {}, columns = 2)
            }
        })
        val label = layout(compose.onNode(hasText("Balanced tone"), useUnmergedTree = true))
        assertEquals("The whole label is read, over two lines", 2, label.lineCount)
        assertFalse(label.hasVisualOverflow)
    }

    // Right-to-left

    private fun rtl(content: @androidx.compose.runtime.Composable () -> Unit): @androidx.compose.runtime.Composable () -> Unit = {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { content() }
    }

    @Test fun englishContentInTheArabicInterfaceKeepsItsPunctuationOnTheRight() {
        val line = CallTranscript("call-1:1", "assistant", "Call me now about Daniel's email.")
        compose.showPage(rtl { CallTranscriptCard("call-1", listOf(line), assistantName = "Beacon") })
        val shown = layout(compose.onNode(hasText("Call me now about Daniel's email."), useUnmergedTree = true))
        assertEquals("English reads left to right, so its full stop ends the sentence", ResolvedTextDirection.Ltr, shown.getParagraphDirection(0))
    }

    @Test fun aSlashCommandNameIsNotFlippedInTheArabicInterface() {
        compose.showPage(rtl { CommandRow("new", "Start a new chat") {} })
        val name = layout(compose.onNode(hasText("/new"), useUnmergedTree = true))
        assertEquals("\"/new\" keeps its slash in front", ResolvedTextDirection.Ltr, name.getParagraphDirection(0))
    }

    @Test fun anArabicLineInTheArabicInterfaceStaysRightToLeft() {
        val line = CallTranscript("call-2:1", "user", "اتصل بي الآن.")
        compose.showPage(rtl { CallTranscriptCard("call-2", listOf(line), assistantName = "Beacon") })
        assertEquals(ResolvedTextDirection.Rtl, layout(compose.onNode(hasText("اتصل بي الآن."), useUnmergedTree = true)).getParagraphDirection(0))
    }
}
