package dev.hermes.android.ui

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.hermes.android.core.mediaTags
import dev.hermes.android.core.receivedFileName
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.beta.Scanner

class MathNode(val expression: String, val source: String, val display: Boolean) : CustomNode()
/** A file reference in reply text. [mediaTag]: Hermes' `MEDIA:` delivery tag, whose [reference] is the path it names; otherwise a bare file:// address. */
class NativeMediaNode(val reference: String, val source: String, val mediaTag: Boolean = false) : CustomNode()
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

// Source spans let a MEDIA: path be read as written: CommonMark would turn C:\work\.cache\a.pdf into C:\work.cache\a.pdf (a backslash escape).
val nativeMarkdownParser: Parser = Parser.builder().extensions(listOf(TablesExtension.create())).customInlineContentParserFactory(MathInlineFactory)
    .includeSourceSpans(org.commonmark.parser.IncludeSourceSpans.BLOCKS_AND_INLINES).build()
private val fileAddress = Regex("file://[^\\s\\)\\]]+", RegexOption.IGNORE_CASE)

/** The file references in one run of text, in order: Hermes' MEDIA: tags ([raw], the text as written, gives their paths when it holds the same tags) and bare file:// addresses. */
internal fun inlineFileReferences(literal: String, raw: String? = null): List<Triple<Int, Int, NativeMediaNode>> {
    val tags = mediaTags(literal)
    val written = raw?.takeIf { it != literal }?.let(::mediaTags)?.takeIf { it.size == tags.size }
    val media = tags.mapIndexed { i, tag -> Triple(tag.start, tag.end, NativeMediaNode(written?.get(i)?.path ?: tag.path, literal.substring(tag.start, tag.end), mediaTag = true)) }
    val files = fileAddress.findAll(literal).filter { found -> media.none { found.range.first < it.second && it.first <= found.range.last } }
        .map { Triple(it.range.first, it.range.last + 1, NativeMediaNode(it.value, it.value)) }
    return (media + files).sortedBy { it.first }
}

// GFM's extended autolinks, web addresses only: no other scheme becomes a link by itself.
// An address also ends at CJK punctuation (、。「」 and the fullwidth forms), which Chinese and Japanese write right after it with no space:
// U+3000-303F (CJK symbols and punctuation) and U+FF01-FF0F, FF1A-FF20, FF3B-FF40, FF5B-FF65 (fullwidth punctuation), as base.py's terminators.
private const val CJK_PUNCTUATION = "\\u3000-\\u303F\\uFF01-\\uFF0F\\uFF1A-\\uFF20\\uFF3B-\\uFF40\\uFF5B-\\uFF65"
private val webAddress = Regex("(?<![\\p{L}\\p{N}_/])https?://[\\p{L}\\p{N}][^\\s<>\"`$CJK_PUNCTUATION]*", RegexOption.IGNORE_CASE)

/** Bare web addresses in one run of text. As GFM: trailing sentence punctuation, and a ")" that closes nothing in the address, stay text. */
internal fun webAddressRanges(literal: String): List<IntRange> = webAddress.findAll(literal).map { found ->
    var end = found.range.last + 1
    while (end > found.range.first) {
        val last = literal[end - 1]
        val address = literal.substring(found.range.first, end)
        if (last in "?!.,:;*_~'" || last == ')' && address.count { it == ')' } > address.count { it == '(' }) end-- else break
    }
    found.range.first until end
}.filter { range -> literal.substring(range).substringAfter("://").isNotEmpty() }.toList()

/**
 * Inspect text nodes after CommonMark has protected code and link destinations (Hermes masks code the same way before it lifts MEDIA: tags):
 * MEDIA: tags and file:// addresses become file references, then bare web addresses become links (D19).
 */
fun parseNativeMarkdown(source: String): Node = nativeMarkdownParser.parse(source).also { document ->
    document.descendants().filterIsInstance<Text>().toList().forEach { text ->
        var parent = text.parent
        var protected = false
        while (parent != null) { if (parent is Link || parent is Image) protected = true; parent = parent.parent }
        if (!protected) {
            val raw = runCatching { text.sourceSpans.joinToString("") { source.substring(it.inputIndex, it.inputIndex + it.length) } }.getOrNull()
            var start = 0
            val files: List<Triple<Int, Int, Node>> = inlineFileReferences(text.literal, raw)
            val links = webAddressRanges(text.literal).filter { range -> files.none { range.first < it.second && it.first <= range.last } }
                .map { range -> literalLink(text.literal.substring(range)).let { Triple(range.first, range.last + 1, it) } }
            val matches = (files + links).sortedBy { it.first }
            matches.forEach { (from, to, node) ->
                if (from > start) text.insertBefore(Text(text.literal.substring(start, from)))
                text.insertBefore(node)
                start = to
            }
            if (matches.isNotEmpty()) { if (start < text.literal.length) text.insertBefore(Text(text.literal.substring(start))); text.unlink() }
        }
    }
}

private fun literalLink(address: String): Link = Link(address, null).apply { appendChild(Text(address)) }

/** Whether Markdown text holds a table, GFM or written with aligned spaces: such a reply takes the transcript's width (D20, D21). */
internal fun holdsMarkdownTable(text: String): Boolean = holdsTableText(text)

/** A paragraph that only delivers files (a MEDIA: line) shows its cards and no text of its own. */
internal fun deliversOnlyFiles(node: Node): Boolean {
    val inline = node.descendants().toList()
    return inline.any { it is NativeMediaNode && it.mediaTag } && inline.all { it is NativeMediaNode && it.mediaTag || it is SoftLineBreak || it is HardLineBreak || it is Emphasis || it is StrongEmphasis ||
        it is Text && it.literal.all { c -> c.isWhitespace() || c in ".,;:!?-–—`\"'*_()[]" } }
}
internal fun Node.children(): List<Node> = buildList { var c = firstChild; while (c != null) { add(c); c = c.next } }
internal fun Node.plain(): String = when (this) { is Text -> literal; is Code -> literal; is MathNode -> source; is NativeMediaNode -> source; is SoftLineBreak, is HardLineBreak -> "\n"; else -> children().joinToString("") { it.plain() } }
internal fun Node.descendants(): Sequence<Node> = sequence { for (child in children()) { yield(child); yieldAll(child.descendants()) } }

/** The same annotated path is used with bitmap placeholders and readable source fallback. [codeBackground] is the themed ground of inline code. */
internal fun inlineAnnotations(node: Node, primary: Color, math: Map<MathNode, String> = emptyMap(), onLink: ((String) -> Unit)? = null, codeBackground: Color = Color.Gray.copy(alpha = .12f), fileNames: Boolean = true): AnnotatedString = buildAnnotatedString {
    fun appendNode(n: Node) {
        when (n) {
            is Text -> append(n.literal)
            is MathNode -> math[n]?.let { appendInlineContent(it, n.source) } ?: append(n.source)
            // In a reply the card under the paragraph carries the file and the sentence keeps only its name, never the tag or the folders on
            // the computer; the owner's own message ([fileNames] false) keeps what was typed.
            is NativeMediaNode -> append(if (n.mediaTag && fileNames) receivedFileName(n.reference) else n.source)
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
