package dev.hermes.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import org.json.JSONObject

internal fun nativeProjectSessionAllowed(id: String, rowProfile: String, profile: String, allowedIds: Set<String>, archived: Boolean): Boolean =
    id.isNotBlank() && id in allowedIds && (rowProfile.isBlank() || rowProfile == profile) && !archived
internal fun nativeProjectDetailCurrent(requestedId: String, returnedId: String): Boolean = requestedId.isNotBlank() && returnedId == requestedId

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NativeProjectsScreen(vm: BeaconViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var selected by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf("") }
    var folderPicker by rememberSaveable(ui.origin, ui.owner, ui.profile, selected) { mutableStateOf(false) }
    var menu by remember(selected) { mutableStateOf(false) }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, ui.connected, vm.client, selected) { vm.loadNativeProjects(selected) }
    BackHandler(selected.isNotBlank()) { selected = "" }
    val key = if (selected.isBlank()) "native-projects" else "native-project:$selected"
    val record = data[key]
    val project = record?.optJSONObject("project")?.takeIf { nativeProjectDetailCurrent(selected, it.string("id")) }
    fun edit(value: JSONObject? = null) {
        val id = value?.string("id").orEmpty()
        vm.openForm(ActionForm(if (id.isBlank()) "New project" else "Edit project", if (id.isBlank()) "/api/projects/create" else "/api/projects/update",
            listOf(Field("name", "Name", required = true), Field("description", "Description", FieldType.MULTILINE)),
            seed = value?.let { json("id" to id, "name" to it.string("name"), "description" to it.string("description")) } ?: json("request_id" to java.util.UUID.randomUUID().toString()),
            explanation = if (id.isBlank()) "Organize chats around a project, then link its folders on your computer." else "Saved in this Hermes profile.",
            onResult = { result -> selected = result.getJSONObject("project").getString("id"); vm.loadNativeProjects(selected) }))
    }
    fun detach(row: JSONObject) {
        val id = row.string("session_id", row.string("id"))
        val owner = vm.client?.scope ?: return
        vm.openForm(ActionForm("Remove chat from project?", "/api/projects/detach", emptyList(),
            seed = json("id" to selected, "session_id" to id, "scope_key" to owner.session(id).key, "event_id" to java.util.UUID.randomUUID().toString()),
            explanation = "The chat stays in your history.", actionLabel = "Remove from project", onResult = { vm.loadNativeProjects(selected) }))
    }
    if (folderPicker && project != null) PcFolderPicker(vm, "Link PC folder", onDismiss = { folderPicker = false }, onChoose = { path ->
        folderPicker = false
        vm.action("/api/projects/folders/add", json("id" to selected, "path" to path), refresh = false, successNotice = "Folder linked", onSuccess = { _, _ -> vm.loadNativeProjects(selected) })
    })
    Column(Modifier.fillMaxSize().testTag("projects-screen")) {
        Header(project?.string("name") ?: "Projects", back = if (selected.isBlank()) null else ({ selected = "" }), menu = LocalOpenNavigationMenu.current) {
            if (selected.isBlank()) IconButton(onClick = { edit() }) { Icon(Icons.Rounded.Add, "New project") }
            else if (project != null) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Project actions") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Edit project") }, onClick = { menu = false; edit(project) })
                    DropdownMenuItem({ Text("Delete project") }, onClick = {
                        menu = false
                        vm.openForm(ActionForm("Delete project?", "/api/projects/delete", emptyList(), seed = json("id" to selected),
                            explanation = "Remove ${project.string("name")} from this profile. Its conversations and files on your computer will remain.", actionLabel = "Delete project",
                            onResult = { selected = ""; vm.loadNativeProjects() }))
                    })
                }
            }
        }
        PullToRefreshBox(
            isRefreshing = key in refreshing || record?.optBoolean("_refreshing") == true,
            onRefresh = { vm.loadNativeProjects(selected, force = true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                record == null || record.optBoolean("_loading") -> item { BeaconSignal(BeaconMotionMode.Sweep, Modifier.fillMaxWidth().wrapContentWidth(), 32.dp) }
                record.string("_error").isNotBlank() -> item { Text(record.string("_error"), color = MaterialTheme.colorScheme.error); TextButton(onClick = { vm.loadNativeProjects(selected) }) { Text("Retry") } }
                selected.isBlank() -> {
                    val projects = record.rows("projects").filterNot { it.optBoolean("archived") }
                    if (projects.isEmpty()) item {
                        Glass(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                BeaconSignal(BeaconMotionMode.Sweep, size = 56.dp)
                                Text("Your projects", style = MaterialTheme.typography.titleLarge)
                                Text("Create a project to keep related chats and folders together.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = { edit() }) { Text("New project") }
                            }
                        }
                    }
                    items(projects, key = { it.getString("id") }) { value ->
                        Glass(Modifier.fillMaxWidth().clickable { selected = value.getString("id") }) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(value.string("name", "Project"), style = MaterialTheme.typography.titleMedium)
                                    Text(value.string("description").ifBlank { "${value.rows("folders").size} linked folder${if (value.rows("folders").size == 1) "" else "s"}" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                project == null -> item { EmptyState("Project not found", "It may have been removed on another device.", action = "All projects", onAction = { selected = "" }) }
                else -> {
                    if (project.string("description").isNotBlank()) item { Text(project.string("description"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Text("Linked folders", style = MaterialTheme.typography.titleMedium) }
                    items(project.rows("folders"), key = { it.getString("path") }) { folder ->
                        var actions by remember { mutableStateOf(false) }
                        val path = folder.getString("path")
                        ListItem(headlineContent = { Text(folder.string("label").ifBlank { path.replace('\\', '/').substringAfterLast('/').ifBlank { path } }) },
                            supportingContent = { Text((if (folder.optBoolean("is_primary")) "Main folder · " else "") + path) },
                            leadingContent = { Icon(Icons.Rounded.Folder, null) }, modifier = Modifier.clickable { vm.browsePcFolder(path) },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            trailingContent = { Box {
                                IconButton(onClick = { actions = true }) { Icon(Icons.Rounded.MoreVert, "Folder actions") }
                                DropdownMenu(actions, { actions = false }) {
                                    if (!folder.optBoolean("is_primary")) DropdownMenuItem({ Text("Use as main folder") }, onClick = { actions = false; vm.action("/api/projects/folders/primary", json("id" to selected, "path" to path), refresh = false, onSuccess = { _, _ -> vm.loadNativeProjects(selected) }) })
                                    DropdownMenuItem({ Text("Unlink folder") }, onClick = { actions = false
                                        vm.openForm(ActionForm("Unlink folder?", "/api/projects/folders/remove", emptyList(), seed = json("id" to selected, "path" to path),
                                            explanation = "Files stay on your computer. Chats in this folder may appear outside this project.", actionLabel = "Unlink", onResult = { vm.loadNativeProjects(selected) }))
                                    })
                                }
                            } })
                    }
                    item { OutlinedButton(onClick = { folderPicker = true }) { Icon(Icons.Rounded.Add, null); Text("Link PC folder", Modifier.padding(start = 8.dp)) } }
                    item { Text("Conversations", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                    val tree = record.optJSONObject("tree")
                    val allowed = tree?.optJSONArray("sessionIds")?.strings().orEmpty().toSet()
                    val rows = (tree?.rows("repos").orEmpty().flatMap { it.rows("groups").flatMap { group -> group.rows("sessions") } } + tree?.rows("previewSessions").orEmpty())
                        .filter { nativeProjectSessionAllowed(it.string("id", it.string("session_id")), it.string("profile"), ui.profile, allowed, it.optBoolean("archived")) }.distinctBy { it.string("id", it.string("session_id")) }
                    if (rows.isEmpty()) item { Text("Choose Projects from a chat’s menu to move it here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(rows, key = { it.string("id", it.string("session_id")) }) { row ->
                        var actions by remember { mutableStateOf(false) }
                        ListItem(headlineContent = { Text(row.string("title").ifBlank { "Untitled conversation" }) },
                            modifier = Modifier.clickable { vm.tab("Chat"); vm.selectSession(row.string("id", row.string("session_id"))) },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            trailingContent = { Box {
                                IconButton({ actions = true }) { Icon(Icons.Rounded.MoreVert, "Chat project actions") }
                                DropdownMenu(actions, { actions = false }) { DropdownMenuItem({ Text("Remove from project") }, { actions = false; detach(row) }) }
                            } })
                    }
                }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PcFolderPicker(vm: BeaconViewModel, title: String, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    var directory by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf("") }
    var address by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf("") }
    val record = data["project-folder"]?.takeIf { it.string("_requested_path") == directory || it.has("_error") }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, ui.connected, vm.client, directory) { vm.load("project-folder", "/api/pc/files", mapOf("path" to directory)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(address, { address = it }, label = { Text("PC folder path") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { val target = address.trim(); if (target == directory) vm.load("project-folder", "/api/pc/files", mapOf("path" to target)) else directory = target }) { Text("Go") }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = { directory = ""; address = "" }) { Icon(Icons.Rounded.Home, "Home folder") }
                IconButton(onClick = { directory = record?.string("parent").orEmpty(); address = directory }, enabled = !record?.string("parent").isNullOrBlank()) { Icon(Icons.Rounded.ArrowUpward, "Parent folder") }
                Text(record?.string("path").orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.weight(1f)) {
                if (record == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else if (record.has("_error")) item { Text(record.string("_error"), color = MaterialTheme.colorScheme.error); TextButton(onClick = { vm.load("project-folder", "/api/pc/files", mapOf("path" to directory)) }) { Text("Retry") } }
                else {
                    val folders = record.rows("entries").filter { it.optBoolean("is_directory") }
                    if (folders.isEmpty()) item { Text("No subfolders", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(folders, key = { it.getString("path") }) { row -> ActionRow(row.getString("name"), icon = Icons.Rounded.Folder) { directory = row.getString("path"); address = directory } }
                }
            }
            Button(onClick = { record?.getString("path")?.let(onChoose) }, enabled = record != null && !record.has("_error") && record.string("path").isNotBlank(), modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) { Text("Use this folder") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ChatProjectPicker(vm: BeaconViewModel, sessionId: String, onDismiss: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val scope = vm.client?.scope?.session(sessionId)
    var selected by remember(ui.origin, ui.owner, ui.profile, sessionId) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, ui.connected, vm.client) { vm.loadNativeProjects() }
    LaunchedEffect(sessionId) { vm.loadConversationProject(sessionId) }
    fun assign(project: JSONObject, path: String) {
        if (scope == null) vm.notice("Reconnect to choose a project.") else {
            onDismiss()
            vm.conversationAction(sessionId, "/api/projects/assign", json("id" to project.getString("id"), "path" to path), "Added to ${project.string("name")}")
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(max = 520.dp)) {
            val current = data["project-of:$sessionId"]?.takeIf { it.string("session_id") == sessionId }?.optJSONObject("project")
            Text(if (current != null) "Move to project" else "Add to project", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            if (scope == null) Text("Reconnect to choose a project.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp))
            if (scope != null && current != null) TextButton({
                onDismiss()
                vm.conversationAction(sessionId, "/api/projects/detach", json("id" to current.string("id")), "Removed from ${current.string("name")}")
            }) { Icon(Icons.Rounded.FolderOff, null); Text("Remove from ${current.string("name")}", Modifier.padding(start = 8.dp)) }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                val record = data["native-projects"]
                if (record == null || record.optBoolean("_loading")) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else if (record.has("_error")) item { Text(record.string("_error"), color = MaterialTheme.colorScheme.error); TextButton(onClick = { vm.loadNativeProjects() }) { Text("Retry") } }
                else if (selected == null) {
                    val projects = record.rows("projects").filterNot { it.optBoolean("archived") }
                    if (projects.isEmpty()) item { Text("Create a project and link a folder to get started.") }
                    items(projects, key = { it.getString("id") }) { project -> ActionRow(project.string("name"), "${project.rows("folders").size} linked folder${if (project.rows("folders").size == 1) "" else "s"}") {
                        // One folder: nothing to choose, so the chat moves there at once.
                        project.rows("folders").singleOrNull()?.let { assign(project, it.getString("path")) } ?: run { selected = project }
                    } }
                } else {
                    item { TextButton(onClick = { selected = null }) { Text("All projects") } }
                    if (selected!!.rows("folders").isEmpty()) item { Text("Link a PC folder to this project before moving a chat here.") }
                    items(if (scope == null) emptyList() else selected!!.rows("folders"), key = { it.getString("path") }) { folder ->
                        ActionRow(folder.string("label").ifBlank { folder.getString("path") }, if (folder.optBoolean("is_primary")) "Main folder" else "") { assign(selected!!, folder.getString("path")) }
                    }
                }
            }
            TextButton(onClick = { onDismiss(); vm.tab("Projects") }, modifier = Modifier.padding(vertical = 12.dp)) { Text("Manage projects") }
        }
    }
}
