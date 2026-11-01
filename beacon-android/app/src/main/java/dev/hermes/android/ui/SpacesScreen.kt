package dev.hermes.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.BeaconClient
import org.json.JSONObject

/** One chip of the folder trail: what it says and the folder it opens. */
internal data class FileCrumb(val label: String, val path: String)

/** The folders from the root down to [path], on Windows (`D:\work\notes`, `D:/work/notes`, `\\server\share\x`) and POSIX paths alike; nothing for no path. */
internal fun fileBreadcrumbs(path: String): List<FileCrumb> {
    val trimmed = path.trim().trimEnd('/', '\\').ifEmpty { path.trim().take(1) }
    if (trimmed.isEmpty()) return emptyList()
    // The separator the host wrote first, so D:\work and D:/work each keep their own.
    val sep = path.trim().firstOrNull { it == '\\' || it == '/' } ?: '/'
    val parts = trimmed.split('\\', '/').toMutableList()
    val crumbs = mutableListOf<FileCrumb>()
    var base = ""
    when {
        // \\server\share: one root chip holding both.
        sep == '\\' && trimmed.startsWith("\\\\") && parts.size >= 4 -> { base = "\\\\${parts[2]}\\${parts[3]}"; crumbs += FileCrumb("${parts[2]}\\${parts[3]}", base); parts.subList(0, 4).clear() }
        // C: is a drive, not a folder: its chip opens C:\ (or C:/ as the host wrote it).
        parts[0].matches(Regex("[A-Za-z]:")) -> { base = parts[0]; crumbs += FileCrumb(parts[0], base + sep); parts.removeAt(0) }
        parts[0].isEmpty() -> { base = "$sep"; crumbs += FileCrumb("$sep", base); parts.removeAt(0) }
    }
    parts.filter { it.isNotEmpty() }.forEach { part -> base = if (base.isEmpty() || base.endsWith(sep)) base + part else "$base$sep$part"; crumbs += FileCrumb(part, base) }
    return crumbs
}

/** A trail of chips: the [shown] folders, with the [hidden] levels above them folded into one "…" chip. */
internal data class FileTrail(val hidden: List<FileCrumb>, val shown: List<FileCrumb>)

/** Keeps the last [keep] levels visible. Folding a single level would only swap its name for "…", so a trail folds only when two or more would hide. */
internal fun collapseFileTrail(trail: List<FileCrumb>, keep: Int = 2): FileTrail =
    if (trail.size - keep >= 2) FileTrail(trail.dropLast(keep), trail.takeLast(keep)) else FileTrail(emptyList(), trail)

/** How much of the trail the row shows, fullest first. */
internal enum class TrailFold { Whole, HomeFolded, LastOnly }

/** One fold of the trail: [menu] levels (and Home, when [homeInMenu]) behind the "…" chip, then the [shown] chips. */
internal class FileTrailView(val menu: List<FileCrumb>, val homeInMenu: Boolean, val shown: List<FileCrumb>) {
    val hasMenu: Boolean get() = homeInMenu || menu.isNotEmpty()
}

/** What [fold] of [trail] shows. Home folds into the "…" menu before any level the row keeps; a trail with no levels is only ever whole. */
internal fun fileTrailView(trail: List<FileCrumb>, fold: TrailFold): FileTrailView {
    val kept = collapseFileTrail(trail)
    return when {
        trail.isEmpty() || fold == TrailFold.Whole -> FileTrailView(kept.hidden, false, kept.shown)
        fold == TrailFold.HomeFolded -> FileTrailView(kept.hidden, true, kept.shown)
        else -> FileTrailView(trail.dropLast(1), true, trail.takeLast(1))
    }
}

/** The folds worth measuring for [trail], fullest first; the last-level-only fold is left out when folding Home already leaves one level. */
internal fun fileTrailFolds(trail: List<FileCrumb>): List<TrailFold> = when {
    trail.isEmpty() -> listOf(TrailFold.Whole)
    fileTrailView(trail, TrailFold.HomeFolded).shown.size > 1 -> TrailFold.entries
    else -> listOf(TrailFold.Whole, TrailFold.HomeFolded)
}

/** The first of [folds] whose measured width ([widths], in the same order, in pixels) fits [available]; the last fold when none does. */
internal fun chooseTrailFold(folds: List<TrailFold>, widths: List<Int>, available: Int): TrailFold =
    folds.zip(widths).firstOrNull { (_, width) -> width <= available }?.first ?: folds.last()

/** The server named by its address ("hermes.local"), without scheme, port or path; blank when the address holds no host. */
internal fun serverHostLabel(origin: String): String =
    runCatching { java.net.URI(origin.trim()).host }.getOrNull().orEmpty().ifBlank { origin.trim().substringAfter("://").substringBefore('/').substringBefore(':') }

/** Where Files opens: the folder last browsed on this account, else the main folder of the first project that has one. */
internal fun initialFilesFolder(saved: String, projects: JSONObject?): String {
    if (saved.isNotBlank()) return saved
    return projects?.rows("projects").orEmpty().filterNot { it.optBoolean("archived") }.firstNotNullOfOrNull { project ->
        val folders = project.rows("folders")
        (folders.firstOrNull { it.optBoolean("is_primary") } ?: folders.firstOrNull())?.string("path")?.takeIf(String::isNotBlank)
    }.orEmpty()
}

private val modifiedKeys = listOf("modified", "modified_at", "mtime", "updated_at", "last_modified")

/** When a file or folder last changed, in epoch milliseconds, from whichever time field Hermes sent (seconds, milliseconds or ISO text); 0 when none. */
internal fun fileModifiedMillis(row: JSONObject): Long {
    for (key in modifiedKeys) {
        val value = row.opt(key)
        val millis = when (value) {
            is Number -> value.toDouble().takeIf { it.isFinite() && it > 0 }?.toLong()?.let { if (it < 100_000_000_000L) it * 1000 else it }
            is String -> value.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000 else it }
                ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull() ?: runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
            else -> null
        }
        if (millis != null && millis > 0) return millis
    }
    return 0L
}

@Composable fun SpacesScreen(vm: BeaconViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val scope = vm.pcScope()
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    // With no folder browsed yet, open in the first project's folder instead of the host's whole home.
    val projectFolder = initialFilesFolder("", data["native-projects"])
    var navigated by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf(false) }
    var directory by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf(initialFilesFolder(vm.pcFolder(), data["native-projects"])) }
    LaunchedEffect(projectFolder) { if (directory.isBlank() && !navigated && projectFolder.isNotBlank()) directory = projectFolder }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, ui.connected) { if (ui.connected && vm.pcFolder().isBlank()) vm.ensureLoaded("native-projects", "/api/projects") }
    var chooseFolder by remember(ui.origin, ui.owner, ui.profile) { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val record = data["files"]?.takeIf { it.string("_requested_path") == directory || it.has("_error") }
    val actual = record?.string("path").orEmpty()
    val refresh: () -> Unit = { vm.load("files", "/api/pc/files", mapOf("path" to directory)) }
    fun changeDirectory(path: String) { vm.closeEditor(); navigated = true; directory = path; vm.rememberPcFolder(path) }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, directory) { refresh() }
    BackHandler(editor.path.isNotBlank() && editor.scope?.copy(session = "") == scope) { vm.closeEditor() }
    val reopenUpload = uiText("Reopen upload in the original account and profile.")
    val reopenDownload = uiText("Reopen download in the original account and profile.")
    var uploadRequest by remember { mutableStateOf<Triple<BeaconClient, Scope?, String>?>(null) }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val request = uploadRequest; uploadRequest = null
        if (uris.isNotEmpty()) {
            if (request != null && vm.client === request.first && vm.pcScope() == request.second) uris.distinct().forEach { vm.upload(it, true, request.third) }
            else vm.notice(reopenUpload)
        }
    }
    var downloadRequest by remember { mutableStateOf<Pair<Pair<BeaconClient, Scope?>, String>?>(null) }
    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val request = downloadRequest; downloadRequest = null
        if (uri != null) {
            if (request != null && vm.client === request.first.first && vm.pcScope() == request.first.second) vm.exportFile(uri, request.second, pc = true)
            else vm.notice(reopenDownload)
        }
    }
    if (chooseFolder) PcFolderPicker(vm, "Browse computer folders", onDismiss = { chooseFolder = false }, onChoose = { chooseFolder = false; changeDirectory(it) })
    val createFile = { vm.openForm(ActionForm("Create file", "/api/pc/file/create", listOf(Field("path", "File path", required = true), Field("content", "Initial content", FieldType.MULTILINE)), json("path" to actual.trimEnd('/', '\\') + "/"), onResult = { refresh() })) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight().align(Alignment.TopCenter).testTag("files-content")) {
            if (wide || editor.path.isBlank() || editor.scope?.copy(session = "") != scope) Header(uiText("Files"), serverHostLabel(ui.origin).ifBlank { uiText("Hermes") }, menu = LocalOpenNavigationMenu.current) {
                IconButton(onClick = refresh) { Icon(Icons.Rounded.Refresh, uiText("Refresh files")) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("files-menu")) { Icon(Icons.Rounded.MoreVert, uiText("File actions")) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(uiText("New file")) }, { menu = false; createFile() }, Modifier.testTag("files-new-file"), leadingIcon = { Icon(Icons.AutoMirrored.Rounded.NoteAdd, null) }, enabled = actual.isNotBlank())
                        DropdownMenuItem({ Text(uiText("Show hidden files")) }, { menu = false; showHidden = !showHidden },
                            Modifier.testTag("files-show-hidden").semantics { role = Role.Checkbox; toggleableState = ToggleableState(showHidden) },
                            leadingIcon = { if (showHidden) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) else Spacer(Modifier.size(24.dp)) })
                        DropdownMenuItem({ Text(uiText("Choose computer folder")) }, { menu = false; chooseFolder = true }, Modifier.testTag("files-choose-folder"), leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) })
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                val tree: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier) {
                        Column(Modifier.fillMaxSize()) {
                            FileTrailRow(remember(actual) { fileBreadcrumbs(actual) }, ::changeDirectory)
                            Box(Modifier.weight(1f)) {
                                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                                    if (record == null) item { Glass(Modifier.padding(16.dp)) { SettingsSkeletonRows(4) } }
                                    else if (record.has("_error")) item { Glass(Modifier.padding(16.dp)) { SettingsErrorRow(uiText("Couldn't load this folder. Check your connection and try again."), refresh, uiText("Retry")) } }
                                    else {
                                        val allEntries = record.rows("entries")
                                        // Dot files and folders (settings, caches) stay out of the way unless asked for, like a desktop file manager.
                                        val entries = if (showHidden) allEntries else allEntries.filterNot { it.string("name").startsWith(".") }
                                        if (allEntries.isEmpty()) item { EmptyState(uiText("This folder is empty"), uiText("Create or upload a file to start working here."), Icons.Rounded.FolderOpen, action = if (actual.isNotBlank()) uiText("Create file") else "", onAction = createFile) }
                                        else if (entries.isEmpty()) item { EmptyState(uiText("Only hidden items here"), uiText("Show hidden files to see them."), Icons.Rounded.VisibilityOff, action = uiText("Show hidden files"), onAction = { showHidden = true }) }
                                        items(entries, key = ::fileRowKey) { file ->
                                            val path = file.getString("path")
                                            val folder = file.optBoolean("is_directory")
                                            var actions by remember(path) { mutableStateOf(false) }
                                            val changed = fileModifiedMillis(file).takeIf { it > 0 }?.let { chatListTime(it, is24Hour = is24Hour) }.orEmpty()
                                            val size = if (folder) "" else android.text.format.Formatter.formatShortFileSize(LocalContext.current, file.optLong("size"))
                                            val detail = listOf(size, changed).filter { it.isNotBlank() }.joinToString(" · ")
                                            ListItem(headlineContent = { Text(file.getString("name"), style = LocalTextStyle.current.contentDirection(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                supportingContent = if (detail.isBlank()) null else ({ Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1) }),
                                                leadingContent = { SettingsIconTile(if (folder) Icons.Rounded.Folder else Icons.Rounded.Description) },
                                                trailingContent = if (folder) ({ Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }) else ({ Box {
                                                    IconButton(onClick = { actions = true }) { Icon(Icons.Rounded.MoreVert, uiText("File actions")) }
                                                    DropdownMenu(actions, { actions = false }) {
                                                        DropdownMenuItem({ Text(uiText("Download")) }, onClick = { actions = false; vm.client?.let { downloadRequest = (it to scope) to path; download.launch(file.getString("name")) } })
                                                    }
                                                } }),
                                                modifier = Modifier.clickable { if (folder) changeDirectory(path) else vm.openFile(path, pc = true) }.heightIn(min = 64.dp),
                                                colors = ListItemDefaults.colors(containerColor = if (editor.path == path) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent))
                                        }
                                    }
                                }
                                // A background refresh over a folder already shown: a thin bar, never a blank list.
                                if (record != null && "files" in refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter).testTag("files-refreshing"))
                            }
                        }
                        if (actual.isNotBlank()) {
                            val uploadLabel = uiText("Upload to this folder")
                            ExtendedFloatingActionButton(
                                onClick = { vm.client?.let { uploadRequest = Triple(it, scope, actual); upload.launch(arrayOf("*/*")) } },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).heightIn(min = 56.dp).testTag("files-upload").semantics { contentDescription = uploadLabel },
                                icon = { Icon(Icons.Rounded.Upload, null) }, text = { Text(uiText("Upload")) })
                        }
                    }
                }
                if (wide) Row(Modifier.fillMaxSize()) { tree(Modifier.width(300.dp).fillMaxHeight()); VerticalDivider(); FileEditor(vm, Modifier.weight(1f), back = { vm.closeEditor() }) }
                else if (editor.path.isBlank() || editor.scope?.copy(session = "") != scope) tree(Modifier.fillMaxSize())
                else FileEditor(vm, Modifier.fillMaxSize(), back = { vm.closeEditor() })
            }
        }
    }
}
/**
 * The folder trail on one line. The fullest fold that the measured row fits is shown: Home folds into the "…" menu before a level
 * is dropped, so a deep path reads "… › Documents › Atelier relaunch" from the leading edge. It scrolls, with a fade, only when even the last level does not fit.
 */
@Composable private fun FileTrailRow(trail: List<FileCrumb>, onOpen: (String) -> Unit) {
    val folds = remember(trail) { fileTrailFolds(trail) }
    val scroll = rememberScrollState()
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        // Each fold is measured as it would be drawn, with no width limit, fullest first, until one fits; those the row cannot hold are never placed.
        val widths = mutableListOf<Int>()
        for (fold in folds.dropLast(1)) {
            widths += subcompose(fold) { TrailChips(trail, fileTrailView(trail, fold), onOpen, probe = true) }.maxOf { it.measure(Constraints()).width }
            if (widths.last() <= constraints.maxWidth) break
        }
        val chosen = chooseTrailFold(folds, widths, constraints.maxWidth)
        val row = subcompose("visible") {
            Box(Modifier.fillMaxWidth().chipRowEdgeFade(scroll).horizontalScroll(scroll).testTag("files-path")) { TrailChips(trail, fileTrailView(trail, chosen), onOpen, probe = false) }
        }.map { it.measure(constraints) }
        layout(row.maxOf { it.width }, row.maxOf { it.height }) { row.forEach { it.place(0, 0) } }
    }
}

/** The chips of one fold; [probe] draws the same chips with no tags or semantics, only to be measured. */
@Composable private fun TrailChips(trail: List<FileCrumb>, view: FileTrailView, onOpen: (String) -> Unit, probe: Boolean) {
    fun Modifier.tagged(tag: String) = if (probe) this else testTag(tag)
    val arrow: @Composable () -> Unit = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    Row((if (probe) Modifier.clearAndSetSemantics { } else Modifier).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!view.homeInMenu) FilterChip(selected = trail.isEmpty(), onClick = { onOpen("") }, label = { Text(uiText("Home")) }, leadingIcon = { Icon(Icons.Rounded.Home, null, Modifier.size(18.dp)) }, modifier = Modifier.tagged("files-crumb-home"))
        if (view.hasMenu) {
            if (!view.homeInMenu) arrow()
            // The levels above the ones shown (and Home, once it folds) sit behind one chip that opens the full path.
            var fullPath by remember { mutableStateOf(false) }
            val fullPathLabel = uiText("Show full path")
            Box {
                FilterChip(selected = false, onClick = { fullPath = true }, label = { Text("…") }, modifier = Modifier.semantics { contentDescription = fullPathLabel }.tagged("files-crumb-more"))
                DropdownMenu(fullPath, { fullPath = false }) {
                    if (view.homeInMenu) DropdownMenuItem({ Text(uiText("Home")) }, { fullPath = false; onOpen("") }, Modifier.testTag("files-crumb-home-menu"), leadingIcon = { Icon(Icons.Rounded.Home, null) })
                    view.menu.forEach { crumb ->
                        DropdownMenuItem({ Text(crumb.label, style = LocalTextStyle.current.contentDirection(), maxLines = 1, overflow = TextOverflow.Ellipsis) }, { fullPath = false; onOpen(crumb.path) })
                    }
                }
            }
        }
        view.shown.forEachIndexed { shownIndex, crumb ->
            val index = trail.size - view.shown.size + shownIndex
            arrow()
            FilterChip(selected = index == trail.lastIndex, onClick = { if (index != trail.lastIndex) onOpen(crumb.path) }, label = { Text(crumb.label, style = LocalTextStyle.current.contentDirection(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) }, modifier = Modifier.tagged("files-crumb:$index"))
        }
    }
}

@Composable private fun FileEditor(vm: BeaconViewModel, modifier: Modifier, back: () -> Unit) {
    val editor by vm.editor.collectAsStateWithLifecycle()
    var preview by rememberSaveable(editor.path) { mutableStateOf(false) }
    var review by remember(editor.scope, editor.path) { mutableStateOf(false) }
    if (editor.path.isBlank()) { Box(modifier) { EmptyState(uiText("Open a file"), uiText("Browse your computer to edit text, inspect documents or preview media."), Icons.Rounded.Description) }; return }
    val extension = editor.path.substringAfterLast('.').lowercase()
    val media = extension in setOf("png", "jpeg", "jpg", "webp", "gif", "bmp", "svg", "pdf", "mp4", "webm", "mov", "mp3", "wav", "m4a", "ogg", "flac", "aac")
    val editable = !editor.loading && !editor.metadata.optBoolean("truncated") && !media && (editor.metadata.string("preview_kind") != "office" || editor.metadata.optBoolean("editable")) && !editor.metadata.optBoolean("binary")
    Column(modifier.fillMaxHeight()) {
        Header(editor.path.replace('\\', '/').substringAfterLast('/'), uiText(if (editor.reviewRequired) "Review restored changes" else if (editor.dirty) "Unsaved changes" else if (editable) "Edit file" else "Preview"), back) {
            if (!media) IconButton(onClick = { preview = !preview }, enabled = !editor.loading) { Icon(if (preview) Icons.Rounded.Edit else Icons.Rounded.Visibility, uiText(if (preview) "Edit source" else "Preview")) }
            if (editable) Button(onClick = { vm.saveEditor() }, enabled = editor.dirty && !editor.saving && !editor.reviewRequired, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText(if (editor.saving) "Saving" else "Save")) }
        }
        if (editor.error.isNotBlank()) Text(uiText(editor.error), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        if (editor.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (editor.metadata.string("edit_blocked_reason").isNotBlank()) Text(editor.metadata.string("edit_blocked_reason"), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        if (editor.reviewRequired) {
            Text(uiText("The file changed since your draft. Compare before saving."), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { review = true }, enabled = !editor.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Review changes")) }
        }
        if (editor.dirty || editor.reviewRequired) OutlinedButton(enabled = !editor.saving && !editor.loading, onClick = { vm.openForm(ActionForm("Discard local changes", "", emptyList(), explanation = "Discard your edits and show the saved file again.", actionLabel = "Discard", onResult = { vm.discardEditor() }, destructive = true)) }, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(uiText("Discard changes")) }
        if (media) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) { MediaPreview(vm, editor.path, endpoint = if (editor.metadata.optBoolean("_pc")) "/api/files/download" else "/api/file/raw", origin = editor.scope) }
        else if (extension == "xlsx") LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) { editor.text.text.split(Regex("(?m)(?=^Sheet: )")).forEach { sheet -> item { Text(sheet.lineSequence().firstOrNull().orEmpty(), style = MaterialTheme.typography.titleMedium); NativeTable(sheet.lineSequence().drop(1).filter(String::isNotBlank).map { it.split('\t') }.toList(), vm) } } }
        else if (extension == "pptx") LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) { editor.text.text.split(Regex("(?m)(?=^Slide )")).forEach { slide -> item { Surface(color = MaterialTheme.colorScheme.surfaceContainer) { Text(slide, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge) } } } }
        else if (preview || !editable) LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) { item { if (extension in setOf("md", "markdown")) Markdown(editor.text.text, vm) else if (extension == "docx") Markdown(editor.text.text, vm) else CodeBlock(extension, editor.text.text, vm) } }
        else OutlinedTextField(editor.text, vm::editorText, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).imePadding(), textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (extension == "docx") FontFamily.Default else FontFamily.Monospace), enabled = editable, placeholder = { Text(uiText("File content")) })
    }
    if (review && editor.reviewRequired) AlertDialog(onDismissRequest = { review = false }, title = { Text(uiText("Review file versions")) },
        text = { LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(uiText("Use your draft, then Save to replace the file.")) }
            item { Text(uiText("Saved on computer"), style = MaterialTheme.typography.titleSmall); CodeBlock("text", editor.metadata.string("content", editor.serverText), vm) }
            item { Text(uiText("Your draft"), style = MaterialTheme.typography.titleSmall); CodeBlock("text", editor.text.text, vm) }
        } }, confirmButton = { TextButton(onClick = { review = false; vm.acceptEditorBase() }) { Text(uiText("Use my draft")) } },
        dismissButton = { TextButton(onClick = { review = false }) { Text(uiText("Back to editor")) } })
}

/** A file or folder row's list key. Windows shortcut folders (My Documents, Application Data) are listed under the path of the
 *  folder they point to, so a path alone repeats in one listing and a lazy list throws on the duplicate key while scrolling. */
internal fun fileRowKey(row: org.json.JSONObject): String = row.string("path") + "\u0000" + row.string("name")
