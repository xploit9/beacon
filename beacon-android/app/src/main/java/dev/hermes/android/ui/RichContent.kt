package dev.hermes.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.widget.VideoView
import android.widget.ImageView
import com.caverock.androidsvg.SVG
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.NativeMediaBytes
import dev.hermes.android.data.externalMediaBytes
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.*
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.json.JSONObject
import ru.noties.jlatexmath.JLatexMathDrawable
import org.scilab.forge.jlatexmath.BeaconMathDefinitions
import java.io.File
import java.nio.ByteBuffer
import okhttp3.HttpUrl.Companion.toHttpUrl

@Composable fun Markdown(source: String, vm: BeaconViewModel, origin: Scope? = vm.authenticatedScope(), snapshots: Map<String, String> = emptyMap(), documentKey: String = "", interactive: Boolean = true) {
    val checklist = checklistReading(vm, origin, documentKey, source, interactive)
    val scopeKey = origin?.key ?: vm.client?.scope?.key.orEmpty()
    val parsed by produceState<PreparedContent<Node>?>(null, source, scopeKey) { value = PreparedContent(scopeKey, source, withContext(Dispatchers.Default) { parseNativeMarkdown(source) }) }
    // Retain the same message's rendered prefix while streaming text is parsed; avoid a zero-height frame.
    val document = parsed?.forGrowingText(scopeKey, source)
    CompositionLocalProvider(LocalChecklist provides checklist) { SelectionContainer { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { document?.children()?.forEach { block -> MarkdownBlock(block, vm, origin, snapshots) } } } }
}
@Composable private fun MarkdownBlock(block: Node, vm: BeaconViewModel, origin: Scope?, snapshots: Map<String, String>) {
    when (block) {
        is Heading -> RichInline(block, vm, origin, snapshots, when (block.level) { 1 -> MaterialTheme.typography.headlineMedium; 2 -> MaterialTheme.typography.headlineSmall; else -> MaterialTheme.typography.titleLarge })
        is org.commonmark.node.Paragraph -> RichInline(block, vm, origin, snapshots)
        is FencedCodeBlock -> when(block.info.trim().substringBefore(' ').lowercase()) {
            "mermaid" -> RichSandbox(block.literal, diagram=true)
            "html" -> RichSandbox(block.literal)
            "svg" -> SvgPreview(remember(block.literal) { block.literal.toByteArray(Charsets.UTF_8) },"Inline SVG",origin?.key.orEmpty())
            else -> CodeBlock(block.info,block.literal)
        }
        is IndentedCodeBlock -> CodeBlock("", block.literal)
        is BlockQuote -> Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { calloutLabel(block)?.let { Text(it, color=MaterialTheme.colorScheme.primary, style=MaterialTheme.typography.titleSmall) }; block.children().forEach { MarkdownBlock(it, vm, origin, snapshots) } } }
        is BulletList, is OrderedList -> {
            val reading = LocalChecklist.current
            val entries = remember(block) { block.children().map { it to checklistItem(it) } }
            val tasks = entries.mapNotNull { it.second }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if(tasks.isNotEmpty()) { val done=tasks.count { reading.values[it.key] ?: it.checked }; Text("$done / ${tasks.size} complete · local reading state", style=MaterialTheme.typography.labelMedium); LinearProgressIndicator(progress={ done.toFloat()/tasks.size }, modifier=Modifier.fillMaxWidth()) }
                entries.forEachIndexed { i,(item,task) ->
                    Row(verticalAlignment=androidx.compose.ui.Alignment.Top) {
                        if(task != null) Checkbox(checked=reading.values[task.key] ?: task.checked, onCheckedChange={ reading.toggle(task.key to it) }, enabled=reading.ready, modifier=Modifier.semantics { contentDescription=task.label })
                        else Text(if(block is OrderedList) "${block.startNumber+i}. " else "• ", color=MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            item.children().forEach { child ->
                                MarkdownBlock(child,vm,origin,snapshots)
                            }
                        }
                    }
                }
            }
        }
        is ThematicBreak -> HorizontalDivider()
        is TableBlock -> {
            val cells=block.children().flatMap { if(it is TableHead || it is TableBody) it.children() else listOf(it) }.filterIsInstance<TableRow>().map { it.children() }
            NativeTable(cells.map { row -> row.map { it.plain() } }) { row,column ->
                cells[row].getOrNull(column)?.let { cell -> RichInline(cell,vm,origin,snapshots,MaterialTheme.typography.bodyMedium.copy(textAlign=when((cell as? TableCell)?.alignment?.name) { "CENTER" -> TextAlign.Center; "RIGHT" -> TextAlign.Right; else -> TextAlign.Start })) }
            }
        }
        is HtmlBlock -> if(block.literal.trimStart().startsWith("<svg", ignoreCase=true)) SvgPreview(remember(block.literal) { block.literal.toByteArray(Charsets.UTF_8) }, "Inline SVG", origin?.key.orEmpty()) else RichSandbox(block.literal)
        else -> if (block.plain().isNotBlank()) Text(block.plain(), style = MaterialTheme.typography.bodyLarge)
    }
}
@Composable private fun RichInline(node: Node, vm: BeaconViewModel, origin: Scope?, snapshots: Map<String, String>, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val uriHandler = LocalUriHandler.current
    var linkedMedia by remember(node, origin?.key) { mutableStateOf<Pair<NativeReference, String>?>(null) }
    MathParagraph(node, origin?.key ?: vm.client?.scope?.key.orEmpty(), style) { destination ->
        if (origin == null) vm.notice("Select an authenticated conversation to open this reference") else when (val reference = resolveNativeReference(destination, origin, snapshots, media = false)) {
            is NativeReference.Conversation, is NativeReference.Workspace -> vm.followNativeReference(reference, origin)
            is NativeReference.LocalMedia, is NativeReference.DataImage -> linkedMedia = reference to node.descendants().filterIsInstance<org.commonmark.node.Link>().firstOrNull { it.destination == destination }?.plain().orEmpty().ifBlank { "Attachment" }
            is NativeReference.ExternalLink -> runCatching { uriHandler.openUri(reference.url) }.onFailure { vm.notice("This link could not be opened") }
            is NativeReference.Unsupported -> vm.notice(reference.reason)
            else -> Unit
        }
    }
    node.descendants().filter { it is org.commonmark.node.Image || it is NativeMediaNode }.forEach { media ->
        key(media, origin?.key) {
            val reference = if (media is org.commonmark.node.Image) media.destination else (media as NativeMediaNode).reference
            val label = if (media is org.commonmark.node.Image) media.plain().ifBlank { media.title?.takeIf(String::isNotBlank) ?: reference } else (media as NativeMediaNode).source
            if (origin != null) {
                val resolved = resolveNativeReference(reference, origin, snapshots)
                if (resolved is NativeReference.ExternalMedia && externalMediaNeedsTap(resolved, origin.origin.toHttpUrl())) {
                    var loadExternalImage by remember { mutableStateOf(false) }
                    if (loadExternalImage) NativeReferencePreview(vm, resolved, label, origin)
                    else OutlinedButton(onClick = { loadExternalImage = true }) { Text((if (media is org.commonmark.node.Image) "Load image from " else "Load media from ") + resolved.url.host) }
                } else NativeReferencePreview(vm, resolved, label, origin)
            } else Text("$reference · Select an authenticated conversation to preview", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    linkedMedia?.let { (reference, label) -> AlertDialog(onDismissRequest = { linkedMedia = null }, title = { Text(label.take(160)) }, text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { if (origin != null) NativeReferencePreview(vm, reference, label, origin) } }, confirmButton = { TextButton(onClick = { linkedMedia = null }) { Text(uiText("Close")) } }) }
}
@Composable private fun MathParagraph(node: Node, scopeKey: String, style: TextStyle = MaterialTheme.typography.bodyLarge, onLink: (String) -> Unit) {
    val math = remember(node) { node.descendants().filterIsInstance<MathNode>().take(64).toList() }
    val density = LocalDensity.current
    val sizePx = with(density) { style.fontSize.toPx() }
    val primary = MaterialTheme.colorScheme.primary
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val contentKey = Triple(node, sizePx, color)
    val rendered by produceState<PreparedContent<List<Bitmap?>>?>(null, scopeKey, contentKey) {
        value = null
        val output = withContext(Dispatchers.Default) { val budget = MathBitmapBudget(); math.map { formula ->
            runCatching {
                require(formula.expression.length <= 4096)
                BeaconMathDefinitions.render {
                    val drawable = JLatexMathDrawable.builder(formula.expression).textSize(sizePx).color(color).padding(2).build()
                    require(budget.reserve(drawable.intrinsicWidth, drawable.intrinsicHeight)) { "Math preview exceeds the paragraph pixel budget" }
                    Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888).also { bitmap -> drawable.setBounds(0, 0, bitmap.width, bitmap.height); drawable.draw(android.graphics.Canvas(bitmap)) }
                }
            }.getOrNull()
        } }
        value = PreparedContent(scopeKey, contentKey, output)
    }
    val bitmaps = rendered?.forContext(scopeKey, contentKey).orEmpty()
    val ids = math.mapIndexedNotNull { i, formula -> if (bitmaps.getOrNull(i) != null) formula to "math:$i" else null }.toMap()
    val inline = math.indices.mapNotNull { i -> bitmaps.getOrNull(i)?.let { bitmap ->
        "math:$i" to InlineTextContent(Placeholder(with(density) { bitmap.width.toSp() }, with(density) { bitmap.height.toSp() }, PlaceholderVerticalAlign.Center)) { Image(bitmap.asImageBitmap(), math[i].source) }
    } }.toMap()
    val annotated = inlineAnnotations(node, primary, ids, onLink)
    val prefix = if(node is org.commonmark.node.Paragraph && checklistItem(node.parent) != null) 4 else if(node is org.commonmark.node.Paragraph && node.parent is BlockQuote && calloutLabel(node.parent as BlockQuote) != null && node === node.parent.firstChild) Regex("^\\[![A-Za-z]+\\]\\s*").find(annotated.text)?.value?.length ?: 0 else 0
    Text(if(prefix > 0 && annotated.length >= prefix) annotated.subSequence(prefix,annotated.length) else annotated, inlineContent = inline, style = style, modifier = Modifier.fillMaxWidth())
}
@Composable fun CodeBlock(language: String, source: String) {
    var expanded by rememberSaveable(source.take(100), language) { mutableStateOf(false) }
    var wrap by rememberSaveable(language) { mutableStateOf(false) }
    var copyError by remember(source) { mutableStateOf("") }
    val lineCount=remember(source) { source.count { it == '\n' } + 1 }
    val shown = if(expanded) source.take(256_000) else source.lineSequence().take(18).joinToString("\n").take(256_000)
    val context = androidx.compose.ui.platform.LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val comment = MaterialTheme.colorScheme.onSurfaceVariant
    val additions = if(MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFF81C995) else Color(0xFF206A3B)
    val removals = MaterialTheme.colorScheme.error
    val settings = LocalBeaconSettings.current
    val structured by produceState<Any?>(null, source, language) { value = withContext(Dispatchers.Default) { runCatching { if (source.length > 256_000) null else if (language.lowercase().startsWith("jsonl")) org.json.JSONArray(source.lineSequence().filter(String::isNotBlank).take(200).map { org.json.JSONTokener(it).nextValue() }) else org.json.JSONTokener(source).nextValue() }.getOrNull()?.takeIf { it is JSONObject || it is org.json.JSONArray } } }
    var treeChoice by rememberSaveable(language) { mutableStateOf<Boolean?>(null) }
    val tree = treeChoice ?: structuredTreeDefault(structured != null, settings.string("structured_code_default_view", "auto"), lineCount, settings.optInt("structured_code_auto_tree_lines", 12))
    val code by produceState(AnnotatedString(shown), shown, language, accent, comment, additions, removals) { value = withContext(Dispatchers.Default) { buildAnnotatedString { append(shown.trimEnd()); if(language.trim().lowercase() in listOf("diff","patch")) { var offset=0; shown.lineSequence().forEach { line -> if(line.startsWith("+") || line.startsWith("-")) addStyle(SpanStyle(color=if(line.startsWith("+")) additions else removals),offset,minOf(offset+line.length,length)); offset+=line.length+1 } }; Regex("(?m)(#[^\n]*|//[^\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\b(?:fun|val|var|class|def|return|if|else|import|from|for|while|const|let|async|await|public|private|true|false|null|None|SELECT|FROM|WHERE)\\b)").findAll(shown).forEach { match -> if (match.range.last < length) addStyle(SpanStyle(color = if (match.value.startsWith('#') || match.value.startsWith("//")) comment else accent), match.range.first, match.range.last + 1) } } } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start=12.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text(language.ifBlank { "Code" },Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                IconButton(onClick={ runCatching { ensureClipboardFits(source); (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Code",source)) }.onFailure { copyError=it.message.orEmpty() } }) { Icon(Icons.Rounded.ContentCopy,uiText("Copy code")) }
            }
            Row {
                TextButton(onClick={ wrap=!wrap }) { Text(if(wrap) "No wrap" else "Wrap") }
                if(structured != null) TextButton(onClick={ treeChoice=!tree }) { Text(uiText(if(tree) "Source" else "Tree")) }
            }
            if(copyError.isNotBlank()) Text(copyError,Modifier.padding(horizontal=12.dp),color=MaterialTheme.colorScheme.error)
            if(tree && structured != null) Column(Modifier.padding(12.dp)) { structured?.let { JsonTree(it) } }
            else SelectionContainer { Text(code,(if(wrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())).padding(16.dp),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodyMedium) }
            if(lineCount>18) TextButton(onClick={ expanded=!expanded }) { Text(if(expanded) "Collapse code" else "Show all $lineCount lines") }
            if(source.length>256_000) Text("Preview limited to 256,000 characters",Modifier.padding(12.dp),style=MaterialTheme.typography.labelSmall)
        }
    }
}

/** A table wider than the bubble fades at the side that has more columns, so it reads as scrollable. */
internal fun Modifier.scrollEdgeFade(state: androidx.compose.foundation.ScrollState): Modifier =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val fade = 40.dp.toPx().coerceAtMost(size.width / 4)
        if (state.canScrollForward) drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
            topLeft = androidx.compose.ui.geometry.Offset(size.width - fade, 0f), size = androidx.compose.ui.geometry.Size(fade, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
        if (state.canScrollBackward) drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
            size = androidx.compose.ui.geometry.Size(fade, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }
@Composable fun NativeTable(rows: List<List<String>>, cellContent: (@Composable (Int,Int) -> Unit)? = null) {
    if(rows.isEmpty()) return
    val context=androidx.compose.ui.platform.LocalContext.current
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val cellStyle=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.SemiBold)
    // Measured, so amounts and dates stay on one line at any font size; very long lines just take the widest column.
    val widths=remember(rows,cellStyle,density) { tableColumnWidths(rows) { line -> if(line.length>80) 1000 else with(density) { measurer.measure(line,cellStyle,softWrap=false,maxLines=1).size.width.toDp().value.toInt()+4 } } }
    var column by remember(rows) { mutableStateOf<Int?>(null) }
    var descending by remember(rows) { mutableStateOf(false) }
    val order=remember(rows,column,descending) { tableRowOrder(rows,column,descending) }
    val sorted=remember(rows,order) { order.map(rows::get) }
    var visible by remember(rows) { mutableIntStateOf(24) }
    var copyError by remember(rows) { mutableStateOf("") }
    val horizontal=rememberScrollState()
    Column {
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text(if(rows.size==2) "1 row" else "${rows.size-1} rows", Modifier.weight(1f), style=MaterialTheme.typography.labelSmall)
            IconButton(onClick={ runCatching { (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Table",tableClipboard(sorted))) }.onFailure { copyError=it.message.orEmpty() } }) { Icon(Icons.Rounded.ContentCopy,"Copy table") }
        }
        if(copyError.isNotBlank()) Text(copyError,color=MaterialTheme.colorScheme.error)
        Column(Modifier.scrollEdgeFade(horizontal).horizontalScroll(horizontal).border(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
            sorted.take(visible).forEachIndexed { index,row ->
                Row(Modifier.background(if(index==0) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)) {
                    widths.indices.forEach { col ->
                        val cell=row.getOrNull(col).orEmpty()
                        val modifier=Modifier.width(widths[col].dp).heightIn(min=48.dp)
                        if(index==0) TextButton(onClick={ if(column==col) descending=!descending else { column=col; descending=false } }, modifier=modifier) { Text(cell + if(column==col) if(descending) " ↓" else " ↑" else "", fontWeight=FontWeight.SemiBold) }
                        else Column(modifier.padding(12.dp).semantics { contentDescription="${rows.first().getOrNull(col).orEmpty()}, row $index: $cell" }) { if(cellContent != null) cellContent(order[index],col) else Text(cell,style=MaterialTheme.typography.bodyMedium) }
                    }
                }
                HorizontalDivider()
            }
        }
        if(visible<rows.size) TextButton(onClick={ visible+=24 }) { Text("Show next 24 rows") }
    }
}
internal fun structuredTreeDefault(ready: Boolean, preference: String, lines: Int, threshold: Int): Boolean = ready && (preference == "on" || preference == "auto" && lines >= threshold)
internal fun imagePanOffset(scale: Float, offset: androidx.compose.ui.geometry.Offset, pan: androidx.compose.ui.geometry.Offset): androidx.compose.ui.geometry.Offset = if (scale <= 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
@Composable fun ZoomImage(bitmap: Bitmap, description: String) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = imagePanOffset(scale, offset, pan)
    }
    Column {
        Box(Modifier.fillMaxWidth().clipToBounds()) {
            Image(bitmap.asImageBitmap(), description, Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 440.dp).graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y).transformable(state))
        }
        if (scale > 1f || offset != androidx.compose.ui.geometry.Offset.Zero) TextButton(onClick = { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero }) { Text(uiText("Reset zoom")) }
    }
}
@Composable fun SecureAttachment(vm: BeaconViewModel, attachment: JSONObject, origin: Scope? = vm.authenticatedScope(), snapshots: Map<String, String> = emptyMap()) {
    var open by remember(attachment, origin?.key) { mutableStateOf(false) }
    val name = attachment.string("name", attachment.string("filename", "Attachment"))
    val path = attachment.string("path").ifBlank { attachment.optJSONObject("image_url")?.string("url") ?: attachment.string("image_url", attachment.string("url")) }
    OutlinedButton(onClick = { open = !open }) { Icon(Icons.Rounded.AttachFile, null); Text(name, Modifier.padding(start = 8.dp)) }
    if (open) { if (origin != null && path.isNotBlank()) NativeReferencePreview(vm, resolveNativeReference(path, origin, snapshots), name, origin) else Text(uiText("No authorized file reference was supplied by the server"), color = MaterialTheme.colorScheme.error) }
}
@Composable fun MediaPreview(vm: BeaconViewModel, path: String, name: String = path, endpoint: String = "/api/file/raw", origin: Scope? = vm.authenticatedScope()) {
    if (origin != null) NativeReferencePreview(vm, NativeReference.LocalMedia(path, origin.session, null, endpoint), name, origin)
}
@Composable private fun NativeReferencePreview(vm: BeaconViewModel, reference: NativeReference, name: String, origin: Scope) {
    val active = vm.client
    val resultClient = active.takeIf { reference is NativeReference.LocalMedia }
    val client = remember(origin.key, active) { active?.takeIf { it.scope == origin.copy(session = "") }?.detached() }
    var error by remember(reference, origin.key, resultClient) { mutableStateOf("") }
    var retry by remember(reference, origin.key, resultClient) { mutableIntStateOf(0) }
    val loaded by produceState<PreparedContent<NativeMediaBytes>?>(null, reference, origin.key, resultClient, retry) {
        value = null; error = ""
        try { val payload = when (reference) {
            is NativeReference.LocalMedia -> { require(client != null) { "Sign in to the originating profile to preview this file" }; NativeMediaBytes(client.bytes(reference.endpoint, reference.query())) }
            is NativeReference.DataImage -> withContext(Dispatchers.Default) { NativeMediaBytes(reference.bytes(), reference.mime) }
            is NativeReference.ExternalMedia -> externalMediaBytes(reference)
            is NativeReference.Unsupported -> kotlin.error(reference.reason)
            else -> kotlin.error("This reference opens a conversation or workspace rather than a media preview")
        }; value = PreparedContent(origin.key, reference, payload, resultClient) } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.message ?: "Preview unavailable" }
    }
    if (error.isNotBlank()) { Text(error, color = MaterialTheme.colorScheme.error); if (reference !is NativeReference.Unsupported) TextButton(onClick = { retry++ }) { Text("Retry preview") }; Disclosure("Reference", when (reference) { is NativeReference.Unsupported -> reference.source; is NativeReference.LocalMedia -> reference.path; is NativeReference.DataImage -> reference.source; is NativeReference.ExternalMedia -> reference.url.toString(); else -> name }); return }
    val payload = loaded?.forContext(origin.key, reference, resultClient) ?: run { LinearProgressIndicator(); return }
    val data = payload.bytes
    val filename = when (reference) { is NativeReference.LocalMedia -> reference.path; is NativeReference.ExternalMedia -> reference.url.pathSegments.lastOrNull().orEmpty(); else -> name }
    val extension = when (payload.mime.lowercase(java.util.Locale.ROOT)) { "image/svg+xml" -> "svg"; "image/gif" -> "gif"; "application/pdf" -> "pdf"; else -> filename.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) }
    when {
        extension == "svg" -> SvgPreview(data, name, origin.key)
        extension == "gif" -> AnimatedPreview(data, name, !rememberNativeMotionEnabled(), origin.key)
        extension == "pdf" || data.take(5).toByteArray().toString(Charsets.US_ASCII) == "%PDF-" -> PdfPreview(vm, data, origin.key, reference)
        payload.mime.startsWith("video/") || extension in setOf("mp4", "webm", "mkv", "mov") -> LocalMedia(vm, data, extension.ifBlank { "media" }, true, origin.key)
        payload.mime.startsWith("audio/") || extension in setOf("mp3", "wav", "m4a", "ogg", "flac", "aac") -> LocalMedia(vm, data, extension.ifBlank { "media" }, false, origin.key)
        else -> { val rendered by produceState<PreparedContent<DecodedBitmap>?>(null, data, origin.key) { value = null; value = PreparedContent(origin.key, data, DecodedBitmap(withContext(Dispatchers.Default) { boundedBitmap(data) })) }; val result = rendered?.forContext(origin.key, data); if (result == null) LinearProgressIndicator() else result.bitmap?.let { ZoomImage(it, name) } ?: SelectionContainer { Text("$name · This media type could not be decoded by Android", style = MaterialTheme.typography.bodyMedium) } }
    }
}
@Composable private fun AnimatedPreview(data: ByteArray, name: String, reducedMotion: Boolean, scopeKey: String) {
    var error by remember(data, scopeKey) { mutableStateOf("") }
    val decoded by produceState<PreparedContent<Drawable>?>(null, data, scopeKey) { value = null; try { value = PreparedContent(scopeKey, data, withContext(Dispatchers.Default) { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ -> val scale = minOf(1f, 2048f / info.size.width, 2048f / info.size.height); decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1)) } }) } catch (e: Throwable) { if (e is CancellationException) throw e; error = "Animated image could not be decoded" } }
    val drawable = decoded?.forContext(scopeKey, data)
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    else if (drawable == null) LinearProgressIndicator()
    drawable?.let { image -> AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true; contentDescription = name } }, update = { it.contentDescription = name; if (it.drawable !== image) (it.drawable as? AnimatedImageDrawable)?.stop(); it.setImageDrawable(image); (image as? AnimatedImageDrawable)?.let { animation -> if (reducedMotion) animation.stop() else animation.start() } }, onRelease = { (it.drawable as? AnimatedImageDrawable)?.stop() }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 440.dp)) }
}
@Composable private fun SvgPreview(data: ByteArray, name: String, scopeKey: String) {
    var error by remember(data, scopeKey) { mutableStateOf("") }
    val rendered by produceState<PreparedContent<Bitmap>?>(null, data, scopeKey) {
        value = null
        try { val bitmap = withContext(Dispatchers.Default) {
            require(data.size <= 2 * 1024 * 1024) { "SVG exceeds the 2 MiB preview limit" }
            val source = data.toString(Charsets.UTF_8)
            require(!Regex("<!DOCTYPE|<!ENTITY|<script|<foreignObject|@import|https?://|file://", RegexOption.IGNORE_CASE).containsMatchIn(source.replace(Regex("xmlns(?::[\\w-]+)?=[\"'][^\"']*[\"']"), ""))) { "SVG external assets, scripts and entities are not permitted in a native preview" }
            SVG.setInternalEntitiesEnabled(false)
            SVG.deregisterExternalFileResolver()
            require(!Regex("(?:xlink:)?href\\s*=\\s*[\"'](?!#)", RegexOption.IGNORE_CASE).containsMatchIn(source)) { "SVG external and embedded asset references are not supported" }
            require(Regex("<[a-zA-Z]").findAll(source).take(5001).count() <= 5000) { "SVG contains too many elements for a native preview" }
            val svg = SVG.getFromString(source)
            val ratio = svg.documentAspectRatio.takeIf { it.isFinite() && it > 0 } ?: 1f
            val width = if (ratio >= 1) 1440 else (1440 * ratio).toInt().coerceAtLeast(1)
            val height = if (ratio <= 1) 1440 else (1440 / ratio).toInt().coerceAtLeast(1)
            svg.setDocumentWidth(width.toFloat()); svg.setDocumentHeight(height.toFloat())
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(android.graphics.Canvas(it)) }
        }; value = PreparedContent(scopeKey, data, bitmap) } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.message ?: "SVG preview failed" }
    }
    val bitmap = rendered?.forContext(scopeKey, data)
    if (error.isNotBlank()) { Text(error, color = MaterialTheme.colorScheme.error); Disclosure("SVG source", data.take(2 * 1024 * 1024).toByteArray().toString(Charsets.UTF_8)) } else if (bitmap == null) LinearProgressIndicator() else ZoomImage(bitmap!!, name)
}
@Composable private fun JsonTree(value: Any, label: String = "", depth: Int = 0) {
    if (depth > 12) { Text(uiText("Nested value · view source for more")); return }
    if (value is JSONObject || value is org.json.JSONArray) {
        var expanded by rememberSaveable(label, depth) { mutableStateOf(depth == 0) }
        val count = if (value is JSONObject) value.length() else (value as org.json.JSONArray).length()
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 4.dp)) { Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight, null); Text("$label ${if (value is JSONObject) "{ $count }" else "[ $count ]"}", fontFamily = FontFamily.Monospace) }
        if (expanded) Column(Modifier.padding(start = 16.dp)) { if (value is JSONObject) value.keys().asSequence().take(24).forEach { key -> JsonTree(value.opt(key) ?: JSONObject.NULL, key, depth + 1) } else (0 until minOf((value as org.json.JSONArray).length(), 24)).forEach { index -> JsonTree(value.opt(index) ?: JSONObject.NULL, "[$index]", depth + 1) }; if (count > 24) Text(uiText("First 24 values shown · view source for the rest")) }
    } else SelectionContainer { Text("${label.takeIf(String::isNotBlank)?.plus(": ").orEmpty()}$value", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
}
private data class DecodedBitmap(val bitmap: Bitmap?)
private data class PdfPage(val bitmap: Bitmap, val count: Int, val index: Int)
@Composable private fun PdfPreview(vm: BeaconViewModel, data: ByteArray, scopeKey: String, reference: NativeReference) {
    var page by rememberSaveable(scopeKey, reference) { mutableIntStateOf(0) }
    val contentKey = data to page
    var error by remember(contentKey, scopeKey) { mutableStateOf("") }
    val rendered by produceState<PreparedContent<PdfPage>?>(null, scopeKey, contentKey) {
        value = null
        try { val result = withContext(Dispatchers.IO) {
            val file = File.createTempFile("preview", ".pdf", vm.app.cacheDir)
            try { file.writeBytes(data); currentCoroutineContext().ensureActive(); ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount > 0) { "PDF has no pages" }
                val index = contentKey.second.coerceIn(0, renderer.pageCount - 1)
                renderer.openPage(index).use { p -> require(p.width > 0 && p.height > 0) { "PDF has an invalid page" }; val scale = minOf(1440f / p.width, 2400f / p.height, 2f); val bitmap = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE); p.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }; PdfPage(bitmap, renderer.pageCount, index) }
            } } } finally { file.delete() }
        }; value = PreparedContent(scopeKey, contentKey, result) } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.message ?: "PDF could not be rendered" }
    }
    val result = rendered?.forContext(scopeKey, contentKey)
    Column {
        if (error.isNotBlank()) SelectionContainer { Text(error, color = MaterialTheme.colorScheme.error) }
        else if (result == null) LinearProgressIndicator()
        result?.let { current ->
            ZoomImage(current.bitmap, "PDF page ${current.index + 1}")
            val previousLabel = uiText("Previous PDF page")
            val nextLabel = uiText("Next PDF page")
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = { page = current.index - 1 }, enabled = current.index > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("pdf-previous").semantics { contentDescription = previousLabel }) { Text(uiText("Previous"), style = MaterialTheme.typography.bodyLarge) }
                Text("${current.index + 1} / ${current.count}", Modifier.padding(horizontal = 8.dp).testTag("pdf-page-count"), style = MaterialTheme.typography.bodyLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
                TextButton(onClick = { page = current.index + 1 }, enabled = current.index + 1 < current.count, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("pdf-next").semantics { contentDescription = nextLabel }) { Text(uiText("Next"), style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}
@Composable internal fun LocalMedia(vm: BeaconViewModel, data: ByteArray, extension: String, video: Boolean, scopeKey: String) {
    val contentKey = Triple(data, extension, video)
    val call by vm.app.calls.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var fileError by remember(contentKey, scopeKey) { mutableStateOf("") }
    val produced by produceState<PreparedContent<File>?>(null, contentKey, scopeKey) {
        value = null
        var created: File? = null
        try {
            val file = withContext(Dispatchers.IO) { File.createTempFile("preview", ".$extension", vm.app.cacheDir).also { created = it; it.writeBytes(data); currentCoroutineContext().ensureActive() } }
            value = PreparedContent(scopeKey, contentKey, file)
            awaitDispose { created?.delete() }
        } catch (e: Throwable) { created?.delete(); if (e is CancellationException) throw e; fileError = e.message ?: "Media preview could not be prepared" }
    }
    if (fileError.isNotBlank()) { SelectionContainer { Text(fileError, color = MaterialTheme.colorScheme.error) }; return }
    val playable = produced?.forContext(scopeKey, contentKey) ?: run { LinearProgressIndicator(); return }
    var player by remember(playable) { mutableStateOf<MediaPlayer?>(null) }
    var videoView by remember(playable) { mutableStateOf<VideoView?>(null) }
    var videoControls by remember(playable) { mutableStateOf<android.widget.MediaController?>(null) }
    var playing by remember(playable) { mutableStateOf(false) }
    var position by remember(playable) { mutableFloatStateOf(0f) }
    var duration by remember(playable) { mutableIntStateOf(0) }
    var mediaError by remember(playable) { mutableStateOf("") }
    DisposableEffect(playable) { if (!video) { runCatching { player = MediaPlayer().apply { setDataSource(playable.absolutePath); setOnPreparedListener { duration = it.duration }; setOnCompletionListener { playing = false }; setOnErrorListener { _, _, _ -> playing = false; mediaError = "Audio playback failed"; true }; prepareAsync() } }.onFailure { mediaError = it.message.orEmpty() } }; onDispose { player?.release(); playable.delete() } }
    fun pausePreview() { runCatching { player?.pause() }; runCatching { videoView?.pause() }; videoControls?.hide(); playing = false }
    DisposableEffect(playable, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) pausePreview() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(call.active) { if (call.active) pausePreview() }
    LaunchedEffect(player, playing) { while (playing) { position = player?.currentPosition?.toFloat() ?: 0f; delay(250) } }
    if (call.active) Text("End the call to play attachments.", style = MaterialTheme.typography.bodySmall)
    if (mediaError.isNotBlank()) SelectionContainer { Text(mediaError, color = MaterialTheme.colorScheme.error) }
    if (video) key(scopeKey, data, playable.absolutePath) { AndroidView(factory = { context -> VideoView(context).apply { tag = playable.absolutePath; setVideoPath(playable.absolutePath); videoView = this; videoControls = android.widget.MediaController(context).also { setMediaController(it) }; setOnPreparedListener { duration = it.duration }; setOnErrorListener { _, _, _ -> mediaError = "Video playback failed"; true } } }, update = { it.setMediaController(if (call.active) null else videoControls); if (call.active) { it.pause(); videoControls?.hide() }; if (it.tag != playable.absolutePath) { it.stopPlayback(); it.tag = playable.absolutePath; it.setVideoPath(playable.absolutePath) } }, modifier = Modifier.fillMaxWidth().height(260.dp), onRelease = { it.stopPlayback(); if (videoView === it) videoView = null }) }
    else Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { FilledIconButton(onClick = { runCatching { if (playing) player?.pause() else player?.start(); playing = !playing }.onFailure { mediaError = it.message.orEmpty() } }, enabled = duration > 0 && !call.active && mediaError.isBlank()) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause audio" else "Play audio") }; Slider(position, { position = it; player?.seekTo(it.toInt()) }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f), enabled = duration > 0 && !call.active && mediaError.isBlank()); Text("${(position / 1000).toInt()}s", style = MaterialTheme.typography.bodySmall) }
}

private fun boundedBitmap(data: ByteArray): Bitmap? { val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(data, 0, data.size, bounds); if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null; val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= 1440 && bounds.outHeight / it <= 1440 }; return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) }
