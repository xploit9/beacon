package dev.hermes.android.ui

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.beta.Scanner

class MathNode(val expression: String, val source: String, val display: Boolean) : CustomNode()
class NativeMediaNode(val reference: String, val source: String) : CustomNode()
/** A keyed effect may still hold its old value until it starts. Admit only the
 * exact current scope/content, and the same client for network-bound results. */
internal data class PreparedContent<T>(val scopeKey: String, val source: Any, val value: T, val client: Any? = null) {
    fun forContext(scopeKey: String, source: Any, client: Any? = null): T? = value.takeIf { this.scopeKey == scopeKey && this.source == source && this.client === client }
    fun forGrowingText(scopeKey: String, source: String): T? = value.takeIf { this.scopeKey == scopeKey && this.client == null && this.source is String && source.startsWith(this.source) }
}

/** CommonMark owns code, emphasis and links. Its extension API recognizes math before the
 * built-in backslash parser can consume \( or \[. Expressions remain opaque to Markdown. */
private object MathInlineFactory : InlineContentParserFactory {
    override fun getTriggerCharacters() = setOf('$', '\\')
    override fun create(): InlineContentParser = InlineContentParser { state ->
        val scan = state.scanner()
        val begin = scan.position()
        val opening = when {
            scan.next("\\(") -> "\\("
            scan.next("\\[") -> "\\["
            scan.next("$$") -> "$$"
            scan.next('$') -> "$"
            else -> return@InlineContentParser ParsedInline.none()
        }
        val closing = when (opening) { "\\(" -> "\\)"; "\\[" -> "\\]"; else -> opening }
        val display = opening == "$$" || opening == "\\["
        val content = scan.position()
        if (!display && (scan.peek() == Scanner.END || scan.peek().isWhitespace())) return@InlineContentParser ParsedInline.none()
        var length = 0
        while (scan.hasNext() && length <= 64_000) {
            val end = scan.position()
            if (scan.next(closing)) {
                val expression = scan.getSource(content, end).content
                // Pandoc's rule: a closing $ followed by a digit is a price ("$600 card ... $1,400"), not math.
                if (expression.isBlank() || opening == "$" && (expression.last().isWhitespace() || scan.peek().isDigit())) return@InlineContentParser ParsedInline.none()
                return@InlineContentParser ParsedInline.of(MathNode(expression, scan.getSource(begin, scan.position()).content, display), scan.position())
            }
            if (!display && scan.peek() == '\n') return@InlineContentParser ParsedInline.none()
            // Escaped dollars/backslashes belong to TeX, rather than ending the expression.
            if (scan.peek() == '\\') { scan.next(); length++; if (scan.hasNext()) { scan.next(); length++ } }
            else { scan.next(); length++ }
        }
        ParsedInline.none()
    }
}

val nativeMarkdownParser: Parser = Parser.builder().extensions(listOf(TablesExtension.create())).customInlineContentParserFactory(MathInlineFactory).build()
/** Inspect text nodes after CommonMark has protected code and link destinations. */
fun parseNativeMarkdown(source: String): Node = nativeMarkdownParser.parse(source).also { document ->
    val references = Regex("MEDIA:([^\\s\\)\\]]+)|file://[^\\s\\)\\]]+", RegexOption.IGNORE_CASE)
    document.descendants().filterIsInstance<Text>().toList().forEach { text ->
        var parent = text.parent
        var protected = false
        while (parent != null) { if (parent is Link || parent is Image) protected = true; parent = parent.parent }
        if (!protected) {
            var start = 0
            val matches = references.findAll(text.literal).toList()
            matches.forEach { match ->
                if (match.range.first > start) text.insertBefore(Text(text.literal.substring(start, match.range.first)))
                text.insertBefore(NativeMediaNode(match.groups[1]?.value ?: match.value, match.value))
                start = match.range.last + 1
            }
            if (matches.isNotEmpty()) { if (start < text.literal.length) text.insertBefore(Text(text.literal.substring(start))); text.unlink() }
        }
    }
}
internal fun Node.children(): List<Node> = buildList { var c = firstChild; while (c != null) { add(c); c = c.next } }
internal fun Node.plain(): String = when (this) { is Text -> literal; is Code -> literal; is MathNode -> source; is NativeMediaNode -> source; is SoftLineBreak, is HardLineBreak -> "\n"; else -> children().joinToString("") { it.plain() } }
internal fun Node.descendants(): Sequence<Node> = sequence { for (child in children()) { yield(child); yieldAll(child.descendants()) } }

/** The same annotated path is used with bitmap placeholders and readable source fallback. [codeBackground] is the themed ground of inline code. */
internal fun inlineAnnotations(node: Node, primary: Color, math: Map<MathNode, String> = emptyMap(), onLink: ((String) -> Unit)? = null, codeBackground: Color = Color.Gray.copy(alpha = .12f)): AnnotatedString = buildAnnotatedString {
    fun appendNode(n: Node) {
        when (n) {
            is Text -> append(n.literal)
            is MathNode -> math[n]?.let { appendInlineContent(it, n.source) } ?: append(n.source)
            is NativeMediaNode -> append(n.source)
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(n.literal) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { n.children().forEach(::appendNode) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { n.children().forEach(::appendNode) }
            is Link -> {
                val styles = TextLinkStyles(style = SpanStyle(color = primary, textDecoration = TextDecoration.Underline))
                if (onLink != null) withLink(LinkAnnotation.Clickable(n.destination, styles) { onLink(n.destination) }) { n.children().forEach(::appendNode) }
                else if (n.destination.startsWith("https://") || n.destination.startsWith("http://")) withLink(LinkAnnotation.Url(n.destination, styles)) { n.children().forEach(::appendNode) }
                else n.children().forEach(::appendNode)
            }
            is Image -> append(n.plain().ifBlank { "Image" })
            is SoftLineBreak -> append(' ')
            is HardLineBreak -> append('\n')
            else -> n.children().forEach(::appendNode)
        }
    }
    node.children().forEach(::appendNode)
}

/** Escape embedded HTML and reject unsafe URL schemes; never infer tables from literal pipes. */
internal fun formattedClipboard(source: String): Pair<String, String> {
    ensureClipboardFits(source)
    val extensions = listOf(TablesExtension.create())
    val document = Parser.builder().extensions(extensions).build().parse(source)
    val html = org.commonmark.renderer.html.HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build().render(document)
    val text = org.commonmark.renderer.text.TextContentRenderer.builder().extensions(extensions).build().render(document)
    ensureClipboardFits(text, html)
    return text to html
}

internal fun ensureClipboardFits(vararg parts: String) {
    require(parts.sumOf { it.length.toLong() } <= 200_000) { "Too much text to copy at once. Select a smaller section." }
}

internal fun formattedShareIntent(source: String): android.content.Intent {
    val (text, html) = formattedClipboard(source)
    return android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_TEXT, text).putExtra(android.content.Intent.EXTRA_HTML_TEXT, html)
}
