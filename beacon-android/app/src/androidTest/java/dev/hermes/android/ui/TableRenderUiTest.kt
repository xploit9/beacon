package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * D21: the owner's "Final state in Overleaf" table arrives as space-aligned lines with no pipe, so commonmark makes one paragraph and the
 * renderer used to lay every line's words into one flowing run. Every cell must stay inside its column and no row may reach another row.
 */
class TableRenderUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    private val ownerReply = """
        Final state in Overleaf

          Document           Pages  Errors  Overfull  Refs  Captions  Figure
          elsevier_paper      17      0        0       33     14        1
          ieee_paper          13      0        0       33     14        1
          tf_paper            25      0        1 (4pt) 33     13        1

        The single remaining 4 pt overhang in tf_paper is one prose line 1.4 mm past the margin.
    """.trimIndent()

    private val rows = listOf(
        listOf("Document", "Pages", "Errors", "Overfull", "Refs", "Captions", "Figure"),
        listOf("elsevier_paper", "17", "0", "0", "33", "14", "1"),
        listOf("ieee_paper", "13", "0", "0", "33", "14", "1"),
        listOf("tf_paper", "25", "0", "1 (4pt)", "33", "13", "1"),
    )
    private val gfm = rows.joinToString("\n") { row -> "| " + row.joinToString(" | ") + " |" }
        .lines().let { lines -> lines.first() + "\n| --- | --- | --- | --- | --- | --- | --- |\n" + lines.drop(1).joinToString("\n") }
    private val noDelimiter = rows.joinToString("\n") { row -> "| " + row.joinToString(" | ") + " |" }
    private val longCell = (0..2).joinToString("\n") { row ->
        "| " + listOf("paper_" + row, row.toString(), "A note long enough to need more than one phone column, so it has to wrap inside its own cell.").joinToString(" | ") + " |"
    }

    private fun show(source: String, width: Int? = null) {
        val vm = BeaconViewModel(app, SavedStateHandle())
        compose.setContent {
            NativeLocalization("en") {
                BeaconTheme {
                    if (width == null) Markdown(source, vm) else Box(Modifier.width(width.dp)) { Markdown(source, vm) }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Every cell of each row exists at a readable width, and the cells on screen sit side by side in column order: a cell never reaches into
     * the cell before it, and no row rises into the row above. Columns past the right edge are clipped to nothing until the table scrolls.
     */
    private fun assertCellsInColumns(columns: Int, rowsExpected: Int, at: String): List<IntSize> {
        val density = compose.density.density
        val sizes = mutableListOf<IntSize>()
        for (row in 0 until rowsExpected) {
            var previous: Rect? = null
            for (column in 0 until columns) {
                val node = compose.onNodeWithTag("table-cell:" + row + ":" + column, useUnmergedTree = true)
                node.assertExists(at + ": row " + row + " column " + column + " is missing")
                val measured = node.fetchSemanticsNode()
                val width = measured.size.width
                assertTrue(at + ": row " + row + " column " + column + " is " + (width / density) + " dp wide", width >= 24 * density)
                val cell = measured.boundsInRoot
                if (previous != null && cell.width > 0f) assertTrue(at + ": row " + row + " column " + column + " (left " + cell.left + ") runs into its neighbour (right " + previous.right + ")", cell.left >= previous.right - 1f)
                previous = cell
                sizes.add(measured.size)
            }
            if (row > 0) {
                val top = compose.onNodeWithTag("table-cell:" + row + ":0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
                val above = compose.onNodeWithTag("table-cell:" + (row - 1) + ":0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
                assertTrue(at + ": row " + row + " starts at " + top + ", inside row " + (row - 1), top >= above + 24 * density)
            }
        }
        return sizes
    }

    private fun capture(name: String) {
        val out = File(app.getExternalFilesDir(null), "table-render-" + name + ".png")
        out.outputStream().use { stream ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
        assertTrue("captured " + out.absolutePath, out.length() > 0)
    }

    @Test fun theOwnersSpaceAlignedTableKeepsEveryCellInItsColumnAtPhoneWidth() {
        show(ownerReply)
        capture("owner-aligned")
        assertCellsInColumns(7, 4, "owner reply")
    }

    @Test fun theSameTableWithPipesKeepsEveryCellInItsColumnAtPhoneWidth() {
        show(gfm)
        assertCellsInColumns(7, 4, "gfm")
        capture("gfm")
    }

    @Test fun theSameTableWithPipesAndNoDelimiterRowKeepsEveryCellInItsColumn() {
        show(noDelimiter)
        capture("no-delimiter")
        assertCellsInColumns(7, 4, "no delimiter")
    }

    @Test fun oneLongCellWrapsInsideItsOwnColumnAndKeepsTheRowsApart() {
        show(longCell)
        val sizes = assertCellsInColumns(3, 3, "long cell")
        val density = compose.density.density
        assertTrue("the long note wraps inside its column", sizes[2].height > 48 * density)
    }

    @Test fun aWideTableScrollsSidewaysAndReachesItsLastColumn() {
        show(noDelimiter)
        compose.onNodeWithTag("table-more-columns", useUnmergedTree = true).assertExists("a table wider than the bubble offers More columns")
        compose.onNodeWithTag("table-more-columns", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        // After one tap the last columns are on screen and still in order.
        val last = compose.onNodeWithTag("table-cell:0:6", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("column 6 is on screen after More columns: " + last.boundsInRoot, last.boundsInRoot.width > 0f)
        val before = compose.onNodeWithTag("table-cell:0:5", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("column 6 starts after column 5", last.boundsInRoot.left >= before.right - 1f)
        capture("scrolled")
    }

    @Test fun aNarrowPhoneStillKeepsTheCellsInOrderAt360dp() {
        show(ownerReply, width = 360)
        assertCellsInColumns(7, 4, "360 dp")
        capture("owner-360dp")
    }

    @Test fun proseWithDoubleSpacesIsStillOneParagraphAndNoTable() {
        show("this  sentence  has  double  spaces")
        compose.onNodeWithTag("table-cell:0:0", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("this  sentence  has  double  spaces", substring = true), useUnmergedTree = true).assertExists()
    }
}
