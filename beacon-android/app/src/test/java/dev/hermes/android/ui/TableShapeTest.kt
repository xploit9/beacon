package dev.hermes.android.ui

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.junit.Assert.*
import org.junit.Test

/** D21: a reply's table keeps its rows and columns, written with pipes or only with aligned spaces. */
class TableShapeTest {
    private val gfm = """
        | Document | Pages | Errors | Overfull | Refs | Captions | Figure |
        | --- | --- | --- | --- | --- | --- | --- |
        | elsevier_paper | 17 | 0 | 0 | 33 | 14 | 1 |
        | ieee_paper | 13 | 0 | 0 | 33 | 14 | 1 |
        | tf_paper | 25 | 0 | 1 (4pt) | 33 | 13 | 1 |
    """.trimIndent()

    /** The owner's reply of 25 Nov 2026, as stored by Hermes: two-space indent, columns kept apart by two or more spaces, no pipe at all. */
    private val aligned = """
        Final state in Overleaf

          Document           Pages  Errors  Overfull  Refs  Captions  Figure
          elsevier_paper      17      0        0       33     14        1
          ieee_paper          13      0        0       33     14        1
          tf_paper            25      0        1 (4pt) 33     13        1

        The single remaining 4 pt overhang in tf_paper is one prose line 1.4 mm past the margin.
    """.trimIndent()

    private val alignedRows = listOf(
        listOf("Document", "Pages", "Errors", "Overfull", "Refs", "Captions", "Figure"),
        listOf("elsevier_paper", "17", "0", "0", "33", "14", "1"),
        listOf("ieee_paper", "13", "0", "0", "33", "14", "1"),
        listOf("tf_paper", "25", "0", "1 (4pt)", "33", "13", "1"),
    )

    @Test fun theOwnersAlignedTableIsReadRowByRow() {
        val table = aligned.lines().drop(2).dropLast(2).joinToString("\n")
        assertEquals(alignedRows, tableRowsFromText(table))
    }

    @Test fun theOwnersReplyHoldsOneAlignedTableAndTheRestStaysProse() {
        val table = aligned.lines().drop(2).dropLast(2).joinToString("\n")
        assertEquals(alignedRows, tableRowsFromText(table))
        assertNull("the sentence above it is prose", tableRowsFromText("Final state in Overleaf"))
        assertNull("and so is the paragraph below", tableRowsFromText("The single remaining 4 pt overhang in tf_paper is one prose line 1.4 mm past the margin."))
        assertNull("a two-column list is a list, not a table", tableRowsFromText("  Name    Value\n  one     1\n  two     2"))
    }

    @Test fun theSameTableWithPipesButNoDelimiterRowStillParsesAsARowPerLine() {
        val noDelimiter = gfm.lines().filterNot { line -> line.contains("---") }.joinToString("\n")
        val document = parseNativeMarkdown(noDelimiter)
        assertNull("commonmark does not make a table without a delimiter row", document.descendants().filterIsInstance<TableBlock>().firstOrNull())
        assertEquals(alignedRows, tableRowsFromText(noDelimiter))
    }

    @Test fun aGfmTableStillParsesAsATableBlockWithOneCellPerColumn() {
        val document = parseNativeMarkdown(gfm)
        val block = document.descendants().filterIsInstance<TableBlock>().single()
        val rows = block.children().flatMap { if (it is TableHead || it is TableBody) it.children() else listOf(it) }
            .filterIsInstance<TableRow>().map { row -> row.children().map { it.plain() } }
        assertEquals(alignedRows, rows)
    }

    @Test fun oneLongCellKeepsItsColumnAndTheRowCount() {
        val longNote = "A long note that keeps going for far more than one phone column can hold, so it wraps inside its own cell."
        val rows = listOf(
            listOf("Submission", "Status", "Note"),
            listOf("elsevier_paper", "0", longNote),
            listOf("tf_paper", "1", "short"),
        )
        val text = rows.joinToString("\n") { row -> row[0].padEnd(18) + row[1].padEnd(10) + row[2] }
        assertEquals(rows, tableRowsFromText(text))
        val piped = rows.joinToString("\n") { row -> "| " + row.joinToString(" | ") + " |" }
        assertEquals(rows, tableRowsFromText(piped))
    }

    @Test fun aSentenceWithDoubleSpacesAndProseStayPlainText() {
        assertNull(tableRowsFromText("the  quick  brown  fox"))
        assertNull(tableRowsFromText("One line only  with  spaces"))
        assertNull("a wrapped sentence is not a table", tableRowsFromText("The  Quick  Brown  Fox\njumps over  the lazy dog\nsleeps all  day long"))
        assertNull("a header and one row is prose, not a table", tableRowsFromText("a  b  c\nd  e"))
        assertNull("single spaces do not mark columns, as one may sit inside a cell", tableRowsFromText("one two three\nfour five six\nseven eight nine"))
        assertEquals("a header and two rows of even columns is a table", 3, tableRowsFromText("one    two    three\nfour   five   six\nseven  eight  nine")?.size)
        assertNull(tableRowsFromText("| one | two |"))
        assertNull("an escaped pipe is one cell's text", tableRowsFromText("| a \\| b | c |\n| 1 | 2 |"))
        assertNull("a pipe inside a sentence is not a table", tableRowsFromText("Use the a | b operator\nfor alternatives"))
        assertNull("two prose lines that each hold a pipe are not a two-column table", tableRowsFromText("Run a | b here\nThen c | d there"))
        assertEquals("a delimiter row left in the text is the table's rule, not a row",
            listOf(listOf("Document", "Pages"), listOf("a", "1")), tableRowsFromText("| Document | Pages |\n| --- | --- |\n| a | 1 |"))
    }

    @Test fun aReplyHoldingAnAlignedTableTakesTheTableWidth() {
        val table = aligned.lines().drop(2).dropLast(2).joinToString("\n")
        assertTrue(holdsMarkdownTable(table))
        assertTrue(holdsMarkdownTable(gfm))
        assertTrue(holdsMarkdownTable(gfm.lines().filterNot { "---" in it }.joinToString("\n")))
        assertTrue("a reply whose table follows a heading still takes the table width", holdsMarkdownTable(aligned))
        assertFalse(holdsMarkdownTable("Two spaces  in a sentence."))
        assertFalse(holdsMarkdownTable("Title\n---\ntext"))
        assertFalse(holdsMarkdownTable("The  Quick  Brown  Fox\njumps over  the lazy dog\nsleeps all  day long"))
    }

    @Test fun aBoldHeaderKeepsItsTableShape() {
        val text = "**Document**  Pages  Errors\nelsevier_paper  17  0\ntf_paper  25  1"
        val paragraph = parseNativeMarkdown(text).firstChild
        assertEquals(3, tableRowsFromText(requireNotNull(tableText(paragraph)))?.size)
    }
}
