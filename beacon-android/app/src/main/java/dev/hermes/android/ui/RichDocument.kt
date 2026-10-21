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
internal fun calloutLabel(node: BlockQuote): String? = Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\\]", RegexOption.IGNORE_CASE).find(node.plain())?.groupValues?.get(1)?.uppercase()
