package dev.hermes.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.CypherViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.CypherClient

@Composable fun SpacesScreen(vm: CypherViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val scope = vm.pcScope()
    var directory by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf(vm.pcFolder()) }
    var chooseFolder by remember(ui.origin, ui.owner, ui.profile) { mutableStateOf(false) }
    val record = data["files"]?.takeIf { it.string("_requested_path") == directory || it.has("_error") }
    val actual = record?.string("path").orEmpty()
    val refresh: () -> Unit = { vm.load("files", "/api/pc/files", mapOf("path" to directory)) }
    fun changeDirectory(path: String) { vm.closeEditor(); directory = path; vm.rememberPcFolder(path) }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, directory) { refresh() }
    BackHandler(editor.path.isNotBlank() && editor.scope?.copy(session = "") == scope) { vm.closeEditor() }
    var uploadRequest by remember { mutableStateOf<Triple<CypherClient, Scope?, String>?>(null) }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val request = uploadRequest; uploadRequest = null
        if (uris.isNotEmpty()) {
            if (request != null && vm.client === request.first && vm.pcScope() == request.second) uris.distinct().forEach { vm.upload(it, true, request.third) }
            else vm.notice("Reopen upload in the original account and profile.")
        }
    }
    var downloadRequest by remember { mutableStateOf<Pair<Pair<CypherClient, Scope?>, String>?>(null) }
    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val request = downloadRequest; downloadRequest = null
        if (uri != null) {
            if (request != null && vm.client === request.first.first && vm.pcScope() == request.first.second) vm.exportFile(uri, request.second, pc = true)
            else vm.notice("Reopen download in the original account and profile.")
        }
    }
    if (chooseFolder) PcFolderPicker(vm, "Browse PC folders", onDismiss = { chooseFolder = false }, onChoose = { chooseFolder = false; changeDirectory(it) })
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Column(Modifier.fillMaxSize()) {
            if (wide || editor.path.isBlank() || editor.scope?.copy(session = "") != scope) Header("Files", "Your computer", menu = LocalOpenNavigationMenu.current) {
                IconButton(onClick = refresh) { Icon(Icons.Rounded.Refresh, uiText("Refresh files")) }
                IconButton(onClick = { chooseFolder = true }) { Icon(Icons.Rounded.FolderOpen, "Choose PC folder") }
            }
            Box(Modifier.weight(1f)) {
                val tree: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier) {
                        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            IconButton(onClick = { changeDirectory("") }) { Icon(Icons.Rounded.Home, "Home folder") }
                            IconButton(onClick = { changeDirectory(record?.string("parent").orEmpty()) }, enabled = !record?.string("parent").isNullOrBlank()) { Icon(Icons.Rounded.ArrowUpward, "Parent folder") }
                            Text(actual.ifBlank { "Home" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            IconButton(onClick = { vm.client?.let { uploadRequest = Triple(it, scope, actual); upload.launch(arrayOf("*/*")) } }, enabled = actual.isNotBlank()) { Icon(Icons.Rounded.Upload, "Upload to this folder") }
                            IconButton(onClick = { vm.openForm(ActionForm("Create file", "/api/pc/file/create", listOf(Field("path", "PC file path", required = true), Field("content", "Initial content", FieldType.MULTILINE)), json("path" to actual.trimEnd('/', '\\') + "/"), onResult = { refresh() })) }, enabled = actual.isNotBlank()) { Icon(Icons.Rounded.NoteAdd, "Create file") }
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            if (record == null) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                            else if (record.has("_error")) item { Text(record.string("_error"), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error); TextButton(onClick = refresh) { Text("Retry") } }
                            else {
                                if (record.rows("entries").isEmpty()) item { EmptyState("This folder is empty", "Create or upload a file to start working here.", Icons.Rounded.FolderOpen) }
                                items(record.rows("entries"), key = { it.getString("path") }) { file ->
                                    val path = file.getString("path")
                                    val folder = file.optBoolean("is_directory")
                                    var actions by remember(path) { mutableStateOf(false) }
                                    ListItem(headlineContent = { Text(file.getString("name")) },
                                        supportingContent = { Text(if (folder) "Folder" else "${file.optLong("size")} bytes", style = MaterialTheme.typography.bodySmall) },
                                        leadingContent = { Icon(if (folder) Icons.Rounded.Folder else Icons.Rounded.Description, null) },
                                        trailingContent = if (folder) null else ({ Box {
                                            IconButton(onClick = { actions = true }) { Icon(Icons.Rounded.MoreVert, "File actions") }
                                            DropdownMenu(actions, { actions = false }) {
                                                DropdownMenuItem({ Text("Download") }, onClick = { actions = false; vm.client?.let { downloadRequest = (it to scope) to path; download.launch(file.getString("name")) } })
                                            }
                                        } }),
                                        modifier = Modifier.clickable { if (folder) changeDirectory(path) else vm.openFile(path, pc = true) }.heightIn(min = 64.dp),
                                        colors = ListItemDefaults.colors(containerColor = if (editor.path == path) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent))
                                }
                            }
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
@Composable private fun FileEditor(vm: CypherViewModel, modifier: Modifier, back: () -> Unit) {
    val editor by vm.editor.collectAsStateWithLifecycle()
    var preview by rememberSaveable(editor.path) { mutableStateOf(false) }
    var review by remember(editor.scope, editor.path) { mutableStateOf(false) }
    if (editor.path.isBlank()) { Box(modifier) { EmptyState("Open a file", "Browse your computer to edit text, inspect documents or preview media.", Icons.Rounded.Description) }; return }
    val extension = editor.path.substringAfterLast('.').lowercase()
    val media = extension in setOf("png", "jpeg", "jpg", "webp", "gif", "bmp", "svg", "pdf", "mp4", "webm", "mov", "mp3", "wav", "m4a", "ogg", "flac", "aac")
    val editable = !editor.loading && !editor.metadata.optBoolean("truncated") && !media && (editor.metadata.string("preview_kind") != "office" || editor.metadata.optBoolean("editable")) && !editor.metadata.optBoolean("binary")
    Column(modifier.fillMaxHeight()) {
        Header(editor.path.replace('\\', '/').substringAfterLast('/'), if (editor.reviewRequired) "Review restored changes" else if (editor.dirty) "Unsaved changes" else if (editable) "Edit file" else "Preview", back) {
            if (!media) IconButton(onClick = { preview = !preview }, enabled = !editor.loading) { Icon(if (preview) Icons.Rounded.Edit else Icons.Rounded.Visibility, if (preview) "Edit source" else "Preview") }
            if (editable) TextButton(onClick = { vm.saveEditor() }, enabled = editor.dirty && !editor.saving && !editor.reviewRequired) { Text(if (editor.saving) "Saving" else "Save") }
        }
        if (editor.error.isNotBlank()) Text(editor.error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        if (editor.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (editor.metadata.string("edit_blocked_reason").isNotBlank()) Text(editor.metadata.string("edit_blocked_reason"), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        if (editor.reviewRequired) {
            Text("The saved version changed, or this older draft has no version history. Review both copies before saving.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { review = true }, enabled = !editor.saving) { Text("Review changes") }
        }
        if (editor.dirty || editor.reviewRequired) TextButton(enabled = !editor.saving && !editor.loading, onClick = { vm.openForm(ActionForm("Discard local changes", "", emptyList(), explanation = "Discard the retained edits and reload the last confirmed server content.", actionLabel = "Discard", onResult = { vm.discardEditor() })) }) { Text(uiText("Discard changes")) }
        if (media) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) { MediaPreview(vm, editor.path, endpoint = if (editor.metadata.optBoolean("_pc")) "/api/files/download" else "/api/file/raw", origin = editor.scope) }
        else if (extension == "xlsx") LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) { editor.text.text.split(Regex("(?m)(?=^Sheet: )")).forEach { sheet -> item { Text(sheet.lineSequence().firstOrNull().orEmpty(), style = MaterialTheme.typography.titleMedium); NativeTable(sheet.lineSequence().drop(1).filter(String::isNotBlank).map { it.split('\t') }.toList()) } } }
        else if (extension == "pptx") LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) { editor.text.text.split(Regex("(?m)(?=^Slide )")).forEach { slide -> item { Surface(color = MaterialTheme.colorScheme.surfaceContainer) { Text(slide, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge) } } } }
        else if (preview || !editable) LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) { item { if (extension in setOf("md", "markdown")) Markdown(editor.text.text, vm) else if (extension == "docx") Markdown(editor.text.text, vm) else CodeBlock(extension, editor.text.text) } }
        else OutlinedTextField(editor.text, vm::editorText, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).imePadding(), textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (extension == "docx") FontFamily.Default else FontFamily.Monospace), enabled = editable, placeholder = { Text(uiText("File content")) })
    }
    if (review && editor.reviewRequired) AlertDialog(onDismissRequest = { review = false }, title = { Text("Review file versions") },
        text = { LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Compare both copies. Using your draft prepares it to replace this saved version when you press Save.") }
            item { Text("Saved on computer", style = MaterialTheme.typography.titleSmall); CodeBlock("text", editor.metadata.string("content", editor.serverText)) }
            item { Text("Your draft", style = MaterialTheme.typography.titleSmall); CodeBlock("text", editor.text.text) }
        } }, confirmButton = { TextButton(onClick = { review = false; vm.acceptEditorBase() }) { Text("Use my draft") } },
        dismissButton = { TextButton(onClick = { review = false }) { Text("Back to editor") } })
}
