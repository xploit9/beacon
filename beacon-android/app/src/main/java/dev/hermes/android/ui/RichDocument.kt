package dev.hermes.android.ui

import org.commonmark.node.*
import dev.hermes.android.core.digest

internal data class ChecklistItem(val key: String, val checked: Boolean, val label: String)
internal fun checklistItem(node: Node): ChecklistItem? {
    if(node !is ListItem) return null
    val text = (node.firstChild as? Paragraph)?.firstChild as? Text ?: return null
    val match = Regex("^\\[([ xX])\\]\\s+(.*)", RegexOption.DOT_MATCHES_ALL).find(text.literal) ?: return null
    val items = generateSequence(node.parent) { it.parent }.last().descendants().filterIsInstance<ListItem>().toList()
    return ChecklistItem("${items.indexOf(node)}:${node.plain().substring(4).digest()}", match.groupValues[1] != " ", node.plain().substring(4).trim())
}
internal fun tableRowOrder(rows: List<List<String>>, column: Int?, descending: Boolean): List<Int> {
    if(rows.size < 2 || column == null) return rows.indices.toList()
    val numeric = rows.drop(1).all { it.getOrNull(column)?.toDoubleOrNull()?.isFinite() == true }
    val body = (1 until rows.size).sortedWith { a,b ->
        val left=rows[a].getOrNull(column).orEmpty(); val right=rows[b].getOrNull(column).orEmpty()
        val x=left.toDoubleOrNull(); val y=right.toDoubleOrNull()
        val result=if(numeric) x!!.compareTo(y!!) else left.compareTo(right, ignoreCase=true)
        if(descending) -result else result
    }
    return listOf(0) + body
}
internal fun tableClipboard(rows: List<List<String>>): String = rows.joinToString("\n") { it.joinToString("\t") }.also { ensureClipboardFits(it) }

/** A table's rule row ("---", ":--:"), which a table GFM refused still writes and which is not a row of cells. */
private val tableRuleCell = Regex("^:?-+:?$")

/** Two or more spaces: how a reply keeps a table's columns apart, as against one space inside a cell ("1 (4pt)", a name). */
private val tableColumnGap = Regex(" {2,}")
private val tableWord = Regex("\\S+")

/** One line's cells when the line is a pipe table's row (GFM allows the outer pipes to be left off); null when it is not one. */
private fun pipeCells(line: String): List<String>? {
    val trimmed = line.trim()
    if (trimmed.length < 3 || '|' !in trimmed || "\\|" in trimmed) return null
    val body = if (trimmed.startsWith('|') && trimmed.endsWith('|')) trimmed.substring(1, trimmed.lastIndex) else trimmed
    val cells = body.split('|').map(String::trim)
    return cells.takeIf { it.size in 1..12 && it.none(String::isEmpty) }
}

/** A space-aligned line cut at its runs of two or more spaces: its candidate cells. */
private fun alignedByGaps(line: String): List<String> =
    tableColumnGap.split(line.trim()).map(String::trim).filter(String::isNotEmpty)

/** Where each column of a space-aligned table starts, or null when the first line is a sentence rather than a table's labels. */
private fun alignedColumnStarts(header: String): List<Int>? {
    val line = header.trim()
    val starts = mutableListOf(0)
    tableColumnGap.findAll(line).forEach { gap -> if (gap.range.last + 1 < line.length) starts.add(gap.range.last + 1) }
    if (starts.size !in 3..12) return null
    val labels = tableColumnGap.split(line).filter(String::isNotEmpty)
    // A header is a handful of short labels ("Document", "Pages"), never a wrapped sentence.
    if (labels.size != starts.size || labels.any { it.length > 24 || it.last() in ",.;:?!" }) return null
    return starts
}

/**
 * One aligned line's cells: each word belongs to the column its end falls in, since a number leans right against its column's end (and a
 * cell may hold one space, as "1 (4pt)"). Null when a column is empty or a word runs across the last column's edge.
 */
private fun alignedCells(line: String, starts: List<Int>): List<String>? {
    val text = line.trim()
    val edges = starts.drop(1) + text.length
    val first = IntArray(starts.size) { -1 }
    val last = IntArray(starts.size) { -1 }
    for (word in tableWord.findAll(text)) {
        val end = word.range.last
        val column = (0 until starts.size).firstOrNull { end < edges[it] } ?: return null
        if (first[column] < 0) first[column] = word.range.first
        last[column] = end
    }
    if (first.any { it < 0 }) return null
    return first.indices.map { column -> text.substring(first[column], last[column] + 1) }
}

/**
 * The rows of a table a reply wrote without GFM syntax, or null for ordinary prose. Hermes' own replies use two shapes: pipes with no
 * delimiter row (a table GFM refuses), and columns kept apart by two or more spaces (25 Nov 2026: "Final state in Overleaf"). Every line
 * must fill every column, so a wrapped sentence, a paragraph or a two-column list stays words.
 *
 * shortcut: a space-aligned table whose first column is right-aligned, or aligned with tabs, stays text; add it when a reply shows one.
 */
internal fun tableRowsFromText(text: String): List<List<String>>? {
    if ('\n' !in text) return null
    val lines = text.lines().map(String::trim).filter(String::isNotBlank)
    if (lines.size !in 2..200 || lines.any { it.length > 2000 }) return null
    val piped = if (lines.any { '|' in it }) pipeTableRows(lines) else null
    val aligned = if (piped == null) alignedTableRows(lines) else null
    val rows = (piped ?: aligned)?.filterNot { row -> row.all(tableRuleCell::matches) } ?: return null
    val columns = rows.first().size
    // Pipes are an explicit table (a header and its one row is enough); aligned spaces need two rows, as a sentence's wrap cannot fill them.
    if (rows.size < if (piped != null) 2 else 3) return null
    if (rows.any { it.size != columns || it.first().isEmpty() || it.any(String::isEmpty) }) return null
    return rows
}

/**
 * A table written with pipes but no delimiter row. GFM may leave the outer pipes off, and then a sentence that happens to hold a pipe
 * ("Run a | b") would look like a row: an unbounded table must have three or more columns, as a header row does.
 */
private fun pipeTableRows(lines: List<String>): List<List<String>>? {
    val bounded = lines.all { it.startsWith('|') && it.endsWith('|') }
    val rows = lines.map { pipeCells(it) ?: return null }
    val columns = rows.first().size
    return rows.takeIf { columns in 2..12 && (bounded || columns >= 3) && it.all { row -> row.size == columns } }
}

private fun alignedTableRows(lines: List<String>): List<List<String>>? {
    val starts = alignedColumnStarts(lines.first()) ?: return null
    // Usually each row's own gaps give the header's cells, which keeps a short first cell's number in its own column. A cell may also hold a
    // single space ("1 (4pt) 33", which joins two gaps); then the header's column starts cut every row. A wrapped sentence has fewer gaps in
    // its wrap lines, so it still fails here and stays prose.
    val byGaps = lines.map(::alignedByGaps)
    if (byGaps.all { it.size == starts.size }) return byGaps
    if (byGaps.drop(1).any { it.size < starts.size - 1 }) return null
    return lines.map { alignedCells(it, starts) ?: return null }
}

/** A GFM delimiter row: "---", ":--:" cells, which only a table writes under its header. */
private val tableRule = Regex("\\|?[ \\t]*:?-+:?[ \\t]*(?:\\|[ \\t]*:?-+:?[ \\t]*)*\\|?")

/** Whether [text] holds a table to lay out wider than prose: a GFM table, or a block of rows a reply wrote without one. */
internal fun holdsTableText(text: String): Boolean {
    val lines = text.lines()
    // A delimiter row under a piped row is GFM's own table, however few columns it names.
    if ((1 until lines.size).any { i -> '|' in lines[i] && '|' in lines[i - 1] && tableRule.matches(lines[i].trim()) }) return true
    return text.split(Regex("\\n[ \\t]*\\n")).any { paragraph -> tableRowsFromText(paragraph) != null }
}

/** The paragraph's words with their line breaks, when it holds no link, math or file (a cell may be bold or code): a table's shape. */
internal fun tableText(node: Node): String? = if (node is Paragraph && node.descendants().all {
    it is Text || it is SoftLineBreak || it is HardLineBreak || it is StrongEmphasis || it is Emphasis || it is Code
}) node.plain() else null
internal fun calloutLabel(node: BlockQuote): String? = Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\\]", RegexOption.IGNORE_CASE).find(node.plain())?.groupValues?.get(1)?.uppercase()
