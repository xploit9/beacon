package dev.hermes.android.updates

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import dev.hermes.android.ui.children
import dev.hermes.android.ui.inlineAnnotations
import dev.hermes.android.ui.parseNativeMarkdown
import dev.hermes.android.ui.plain
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.ThematicBreak

/**
 * A release body (public GitHub text, so untrusted) as one run of text, parsed and styled by the app's own Markdown path.
 * Images, tables and HTML become plain text and nothing is fetched; every link goes to [onLink], which decides what may open.
 */
internal fun releaseNotesText(source: String, primary: Color, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    fun newBlock() { if (length > 0) append('\n') }
    fun block(node: Node, bullet: String) {
        when (node) {
            is Heading -> { newBlock(); withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inlineAnnotations(node, primary, onLink = onLink)) } }
            is Paragraph -> { newBlock(); append(bullet); append(inlineAnnotations(node, primary, onLink = onLink)) }
            is BulletList -> node.children().forEach { item -> item.children().forEachIndexed { index, child -> block(child, if (index == 0) "• " else "") } }
            is OrderedList -> node.children().forEachIndexed { number, item -> item.children().forEachIndexed { index, child -> block(child, if (index == 0) "${node.startNumber + number}. " else "") } }
            is BlockQuote -> node.children().forEach { block(it, "") }
            is FencedCodeBlock -> { newBlock(); withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.literal.trimEnd()) } }
            is IndentedCodeBlock -> { newBlock(); withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.literal.trimEnd()) } }
            is ThematicBreak -> Unit
            else -> node.plain().takeIf { it.isNotBlank() }?.let { newBlock(); append(it.trim()) }
        }
    }
    // A leading heading ("## Beacon 1.1.3", "What's new") repeats the card's own title and label.
    parseNativeMarkdown(source).children().dropWhile { it is Heading && it.previous == null }.forEach { block(it, "") }
}
