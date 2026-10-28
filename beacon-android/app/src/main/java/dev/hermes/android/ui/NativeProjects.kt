package dev.hermes.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun nativeProjectSessionAllowed(id: String, rowProfile: String, profile: String, allowedIds: Set<String>, archived: Boolean): Boolean =
    id.isNotBlank() && id in allowedIds && (rowProfile.isBlank() || rowProfile == profile) && !archived
internal fun nativeProjectDetailCurrent(requestedId: String, returnedId: String): Boolean = requestedId.isNotBlank() && returnedId == requestedId

/** What a project offers a chat, in the chat's project list. */
internal fun projectFolderSummary(count: Int, none: String = "No folder yet", one: String = "1 linked folder", many: String = "{0} linked folders"): String =
    when (count) { 0 -> none; 1 -> one; else -> many.replace("{0}", "$count") }
/** [projectFolderSummary] in the app's language: [none], [one] and [many] are whole translated phrases, and {0} is the count. */
@Composable private fun folderSummary(count: Int): String = projectFolderSummary(count, uiText("No folder yet"), uiText("1 linked folder"), uiText("{0} linked folders"))

/** The project Hermes files [sessionId] under, from the cached `project-of` read; null when it is in none or not read yet. */
internal fun chatProjectOf(record: JSONObject?, sessionId: String): JSONObject? =
    record?.takeIf { it.string("session_id") == sessionId }?.optJSONObject("project")?.takeIf { it.string("id").isNotBlank() && it.string("name").isNotBlank() }

private const val PROJECT_OPEN_KEY = "projects-open"
private const val PROJECT_OPEN_WINDOW_MS = 10_000L

/** The project a chat asked the Projects tab to open, while that request is fresh; "" otherwise. */
internal fun projectOpenRequest(record: JSONObject?, now: Long): String =
    record?.takeIf { now - it.optLong("at") in 0..PROJECT_OPEN_WINDOW_MS }?.string("id").orEmpty()

/** Opens the Projects tab on [projectId]; the screen reads the request once. */
internal fun openProjectFromChat(vm: BeaconViewModel, projectId: String) {
    vm.storeData(PROJECT_OPEN_KEY, json("id" to projectId, "at" to System.currentTimeMillis()))
    vm.tab("Projects")
}

/** A project rename, delete or folder change can move the open chat in or out of it. */
private fun refreshOpenChatProject(vm: BeaconViewModel) {
    vm.chat.value.sessionId.takeIf(String::isNotBlank)?.let { vm.loadConversationProject(it, quiet = true) }
}

/** After a chat joins or leaves a project: show the new answer at once, then refresh the project views that list it. */
private fun followProjectChange(vm: BeaconViewModel, job: Job, sessionId: String, project: JSONObject?, touched: List<String>) {
    // Not the chat's lifetime: the menu or sheet that started this is usually gone by now.
    val active = vm.client
    vm.viewModelScope.launch {
        job.join()
        if (vm.client !== active) return@launch
        // The action clears the error when it starts, so one left now is its own.
        if (vm.ui.value.error.isBlank()) vm.storeData("project-of:$sessionId", json("session_id" to sessionId, "project" to project))
        // A quiet read: a failure here stays on its key instead of raising an app-wide error.
        vm.load("native-projects", "/api/projects", force = true, quiet = true)
        touched.filter { it.isNotBlank() && vm.data.value["native-project:$it"] != null }.forEach { vm.loadNativeProjects(it, force = true) }
    }
}

/** Files the chat under [project], first linking [path] to it when [link]; [previous] is the project it leaves, if any. */
internal fun addChatToProject(vm: BeaconViewModel, sessionId: String, project: JSONObject, path: String, link: Boolean, previous: JSONObject?): Job {
    val id = project.getString("id")
    val running = "${vm.client?.scope?.session(sessionId)?.key}|/api/projects/assign" in vm.conversationPending.value
    val job = vm.conversationAction(sessionId, "/api/projects/assign", json("id" to id, "path" to path).apply { if (link) put("link", true) }, "Added to ${project.string("name")}")
    if (!running) followProjectChange(vm, job, sessionId, project, listOf(id, previous?.string("id").orEmpty()))
    return job
}

/** Takes the chat out of [project] by moving it back to the profile's own folder. */
internal fun removeChatFromProject(vm: BeaconViewModel, sessionId: String, project: JSONObject): Job {
    val id = project.string("id")
    val running = "${vm.client?.scope?.session(sessionId)?.key}|/api/projects/detach" in vm.conversationPending.value
    val job = vm.conversationAction(sessionId, "/api/projects/detach", json("id" to id), "Removed from ${project.string("name")}")
    if (!running) followProjectChange(vm, job, sessionId, null, listOf(id))
    return job
}

private data class ProjectEditDraft(val id: String, val originalName: String, val originalDescription: String, val name: String, val description: String) : java.io.Serializable {
    val dirty get() = name != originalName || description != originalDescription
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NativeProjectsScreen(vm: BeaconViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    // A chat's project name opens this tab on that project.
    val requested = projectOpenRequest(data[PROJECT_OPEN_KEY], System.currentTimeMillis())
    var selected by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf(requested) }
    LaunchedEffect(requested) { if (requested.isNotBlank()) { selected = requested; vm.storeData(PROJECT_OPEN_KEY, JSONObject()) } }
    var folderPicker by rememberSaveable(ui.origin, ui.owner, ui.profile, selected) { mutableStateOf(false) }
    var menu by remember(selected) { mutableStateOf(false) }
    var editDraft by rememberSaveable { mutableStateOf<ProjectEditDraft?>(null) }
    var discardEdit by rememberSaveable { mutableStateOf(false) }
    var editSaving by remember { mutableStateOf(false) }
    var updatedHeader by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    val editScope = rememberCoroutineScope()
    // The actions below run outside composition, so their words are looked up here.
    val projectUpdated = uiText("Project updated")
    val folderLinked = uiText("Folder linked")
    val deleteConsequence = uiText("Its conversations and files on your computer will remain.")
    LaunchedEffect(ui.origin, ui.owner, ui.profile, ui.connected, vm.client, selected) { vm.loadNativeProjects(selected) }
    BackHandler(selected.isNotBlank()) { selected = "" }
    val key = if (selected.isBlank()) "native-projects" else "native-project:$selected"
    val record = data[key]
    val project = record?.optJSONObject("project")?.takeIf { nativeProjectDetailCurrent(selected, it.string("id")) }
    val headerName = updatedHeader?.takeIf { it.first == selected }?.second ?: project?.string("name") ?: uiText("Projects")
    LaunchedEffect(selected, project?.string("name"), updatedHeader) {
        val override = updatedHeader
        if (override != null && (override.first != selected || project?.string("name") == override.second)) updatedHeader = null
    }
    fun edit(value: JSONObject? = null) {
        val id = value?.string("id").orEmpty()
        if (id.isNotBlank()) {
            val name = value?.string("name").orEmpty()
            val description = value?.string("description").orEmpty()
            editDraft = ProjectEditDraft(id, name, description, name, description)
            return
        }
        vm.openForm(ActionForm("New project", "/api/projects/create",
            listOf(Field("name", "Name", required = true), Field("description", "Description", FieldType.MULTILINE)),
            seed = json("request_id" to java.util.UUID.randomUUID().toString()),
            explanation = "Organize chats around a project, then link its folders on your computer.",
            onResult = { result -> selected = result.getJSONObject("project").getString("id"); vm.loadNativeProjects(selected) }))
    }
    fun requestEditDismiss() {
        if (editSaving) return
        if (editDraft?.dirty == true) discardEdit = true else editDraft = null
    }
    fun saveEdit() {
        val draft = editDraft ?: return
        if (editSaving || !draft.dirty || draft.name.isBlank() || vm.client == null) return
        editSaving = true
        val job = vm.action(
            "/api/projects/update",
            json("id" to draft.id, "name" to draft.name.trim(), "description" to draft.description.trim()),
            refresh = false,
            successNotice = projectUpdated,
            onSuccess = { _, result ->
                val updated = result.getJSONObject("project")
                val id = updated.getString("id")
                updatedHeader = id to updated.getString("name")
                selected = id
                editDraft = null
                discardEdit = false
                vm.loadNativeProjects(id)
                refreshOpenChatProject(vm)
            },
        )
        editScope.launch {
            job.join()
            editSaving = false
        }
    }
    fun detach(row: JSONObject) {
        val id = row.string("session_id", row.string("id"))
        val owner = vm.client?.scope ?: return
        vm.openForm(ActionForm("Remove chat from project?", "/api/projects/detach", emptyList(),
            seed = json("id" to selected, "session_id" to id, "scope_key" to owner.session(id).key, "event_id" to java.util.UUID.randomUUID().toString()),
            explanation = "The chat stays in your history.", actionLabel = "Remove from project", onResult = { vm.loadNativeProjects(selected); vm.loadConversationProject(id) }))
    }
    BackHandler(editDraft != null) {
        if (!editSaving) {
            if (discardEdit) discardEdit = false else requestEditDismiss()
        }
    }
    if (folderPicker && project != null) PcFolderPicker(vm, "Link computer folder", onDismiss = { folderPicker = false }, onChoose = { path ->
        folderPicker = false
        vm.action("/api/projects/folders/add", json("id" to selected, "path" to path), refresh = false, successNotice = folderLinked, onSuccess = { _, _ -> vm.loadNativeProjects(selected); refreshOpenChatProject(vm) })
    })
    Column(Modifier.fillMaxSize().testTag("projects-screen")) {
        Header(headerName, back = if (selected.isBlank()) null else ({ selected = "" }), menu = LocalOpenNavigationMenu.current) {
            if (selected.isBlank()) IconButton(onClick = { edit() }) { Icon(Icons.Rounded.Add, uiText("New project")) }
            else if (project != null) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, uiText("Project actions")) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(uiText("Edit project")) }, onClick = { menu = false; edit(project) })
                    DropdownMenuItem({ Text(uiText("Delete project"), color = MaterialTheme.colorScheme.error) }, onClick = {
                        menu = false
                        vm.openForm(ActionForm("Delete project?", "/api/projects/delete", emptyList(), seed = json("id" to selected),
                            explanation = "${project.string("name")}\n\n$deleteConsequence", actionLabel = "Delete project",
                            onResult = { selected = ""; vm.loadNativeProjects(); refreshOpenChatProject(vm) }, destructive = true))
                    })
                }
            }
        }
        PullToRefreshBox(
            isRefreshing = key in refreshing || record?.optBoolean("_refreshing") == true,
            onRefresh = { vm.loadNativeProjects(selected, force = true) },
            modifier = Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth()
                .align(androidx.compose.ui.Alignment.CenterHorizontally),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                record == null || record.optBoolean("_loading") -> item { BeaconSignal(BeaconMotionMode.Sweep, Modifier.fillMaxWidth().wrapContentWidth(), 32.dp) }
                record.string("_error").isNotBlank() -> item { Text(uiText("Couldn't load projects. Check your connection and try again."), color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = { vm.loadNativeProjects(selected) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Retry")) } }
                selected.isBlank() -> {
                    val projects = record.rows("projects").filterNot { it.optBoolean("archived") }
                    if (projects.isEmpty()) item {
                        Glass(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                BeaconSignal(BeaconMotionMode.Sweep, size = 56.dp)
                                Text(uiText("Your projects"), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                                Text(uiText("Create a project to keep related chats and folders together."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = { edit() }) { Text(uiText("New project")) }
                            }
                        }
                    }
                    items(projects, key = { it.getString("id") }) { value ->
                        // Clipped to the card's shape, so the ripple stays inside it.
                        Glass(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).clickable(role = Role.Button) { selected = value.getString("id") }) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(value.string("name").ifBlank { uiText("Project") }, style = MaterialTheme.typography.titleMedium)
                                    Text(value.string("description").ifBlank { folderSummary(value.rows("folders").size) }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                project == null -> item { EmptyState(uiText("Project not found"), uiText("It may have been removed on another device."), action = uiText("All projects"), onAction = { selected = "" }) }
                else -> {
                    if (project.string("description").isNotBlank()) item { Text(project.string("description"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Text(uiText("Linked folders"), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium) }
                    items(project.rows("folders"), key = { it.getString("path") }) { folder ->
                        var actions by remember { mutableStateOf(false) }
                        val path = folder.getString("path")
                        ListItem(headlineContent = { Text(folder.string("label").ifBlank { path.replace('\\', '/').substringAfterLast('/').ifBlank { path } }) },
                            supportingContent = { Text((if (folder.optBoolean("is_primary")) uiText("Main folder") + " · " else "") + path) },
                            leadingContent = { Icon(Icons.Rounded.Folder, null) }, modifier = Modifier.clickable { vm.browsePcFolder(path) },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            trailingContent = { Box {
                                IconButton(onClick = { actions = true }) { Icon(Icons.Rounded.MoreVert, uiText("Folder actions")) }
                                DropdownMenu(actions, { actions = false }) {
                                    if (!folder.optBoolean("is_primary")) DropdownMenuItem({ Text(uiText("Use as main folder")) }, onClick = { actions = false; vm.action("/api/projects/folders/primary", json("id" to selected, "path" to path), refresh = false, onSuccess = { _, _ -> vm.loadNativeProjects(selected) }) })
                                    DropdownMenuItem({ Text(uiText("Unlink folder")) }, onClick = { actions = false
                                        vm.openForm(ActionForm("Unlink folder?", "/api/projects/folders/remove", emptyList(), seed = json("id" to selected, "path" to path),
                                            explanation = "Files stay on your computer. Chats in this folder may appear outside this project.", actionLabel = "Unlink", onResult = { vm.loadNativeProjects(selected); refreshOpenChatProject(vm) }, destructive = true))
                                    })
                                }
                            } })
                    }
                    item { OutlinedButton(onClick = { folderPicker = true }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Rounded.Add, null); Text(uiText("Link computer folder"), Modifier.padding(start = 8.dp)) } }
                    item { Text(uiText("Conversations"), Modifier.padding(top = 12.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium) }
                    val tree = record.optJSONObject("tree")
                    val allowed = tree?.optJSONArray("sessionIds")?.strings().orEmpty().toSet()
                    val rows = (tree?.rows("repos").orEmpty().flatMap { it.rows("groups").flatMap { group -> group.rows("sessions") } } + tree?.rows("previewSessions").orEmpty())
                        .filter { nativeProjectSessionAllowed(it.string("id", it.string("session_id")), it.string("profile"), ui.profile, allowed, it.optBoolean("archived")) }.distinctBy { it.string("id", it.string("session_id")) }
                    if (rows.isEmpty()) item { Text(uiText("Open a chat’s menu and choose Add to project to move it here."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(rows, key = { it.string("id", it.string("session_id")) }) { row ->
                        var actions by remember { mutableStateOf(false) }
                        val sessionId = row.string("id", row.string("session_id"))
                        // The same row as the drawer's chat list, with the project's own menu beside it.
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            ChatListRow(sessionId, row.string("title").ifBlank { uiText("Untitled conversation") }, preview = "", time = "",
                                modifier = Modifier.weight(1f).clickable { vm.tab("Chat"); vm.selectSession(sessionId) })
                            Box {
                                IconButton({ actions = true }) { Icon(Icons.Rounded.MoreVert, uiText("Chat project actions")) }
                                DropdownMenu(actions, { actions = false }) { DropdownMenuItem({ Text(uiText("Remove from project")) }, { actions = false; detach(row) }) }
                            }
                        }
                    }
                }
                }
            }
        }
    }
    if (discardEdit) AlertDialog(
        onDismissRequest = { discardEdit = false },
        title = { Text(uiText("Discard changes?")) },
        text = { Text(uiText("Your unsaved project edits will be lost.")) },
        confirmButton = { TextButton(onClick = { editDraft = null; discardEdit = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(uiText("Discard")) } },
        dismissButton = { TextButton(onClick = { discardEdit = false }) { Text(uiText("Keep editing")) } },
    ) else editDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = ::requestEditDismiss,
            title = { Text(uiText("Edit project")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { editDraft = draft.copy(name = it) },
                        label = { Text(uiText("Name") + " *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("project-edit-name"),
                    )
                    OutlinedTextField(
                        value = draft.description,
                        onValueChange = { editDraft = draft.copy(description = it) },
                        label = { Text(uiText("Description")) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().testTag("project-edit-description"),
                    )
                    if (vm.client == null) Text(uiText("Reconnect to save this project."), color = MaterialTheme.colorScheme.error)
                    else if (ui.error.isNotBlank()) Text(uiText(ui.error), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = ::saveEdit,
                    enabled = draft.name.isNotBlank() && draft.dirty && !editSaving && vm.client != null,
                    modifier = Modifier.testTag("project-edit-save"),
                ) { Text(uiText(if (editSaving) "Saving…" else "Save")) }
            },
            dismissButton = { TextButton(onClick = ::requestEditDismiss, enabled = !editSaving) { Text(uiText("Cancel")) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PcFolderPicker(vm: BeaconViewModel, title: String, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    var directory by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf("") }
    var address by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf("") }
    var showHidden by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf(false) }
    val record = data["project-folder"]?.takeIf { it.string("_requested_path") == directory || it.has("_error") }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, ui.connected, vm.client, directory) { vm.load("project-folder", "/api/pc/files", mapOf("path" to directory)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().fillMaxHeight(.85f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiText(title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(address, { address = it }, label = { Text(uiText("Computer folder path")) }, singleLine = true, modifier = Modifier.weight(1f).testTag("pc-folder-address"))
                TextButton(onClick = { val target = address.trim(); if (target == directory) vm.load("project-folder", "/api/pc/files", mapOf("path" to target)) else directory = target }) { Text(uiText("Go")) }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = { directory = ""; address = "" }) { Icon(Icons.Rounded.Home, uiText("Home folder")) }
                IconButton(onClick = { directory = record?.string("parent").orEmpty(); address = directory }, enabled = !record?.string("parent").isNullOrBlank()) { Icon(Icons.Rounded.ArrowUpward, uiText("Parent folder")) }
                Text(record?.string("path").orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { showHidden = !showHidden }, modifier = Modifier.testTag("project-folders-show-hidden")) {
                    Icon(if (showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, uiText(if (showHidden) "Hide hidden folders" else "Show hidden folders"))
                }
            }
            Button(onClick = { record?.getString("path")?.let(onChoose) }, enabled = record != null && !record.has("_error") && record.string("path").isNotBlank(), modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) { Text(uiText("Use this folder")) }
            LazyColumn(Modifier.weight(1f)) {
                if (record == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else if (record.has("_error")) item { Text(uiText("Couldn't load this folder. Check your connection and try again."), color = MaterialTheme.colorScheme.error); TextButton(onClick = { vm.load("project-folder", "/api/pc/files", mapOf("path" to directory)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Retry")) } }
                else {
                    val allFolders = record.rows("entries").filter { it.optBoolean("is_directory") }
                    val folders = if (showHidden) allFolders else allFolders.filterNot { it.string("name").startsWith(".") }
                    if (allFolders.isEmpty()) item { Text(uiText("No subfolders"), Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else if (folders.isEmpty()) item { Text(uiText("Only hidden folders here. Show hidden folders to see them."), Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(folders, key = { it.getString("path") }) { row -> ActionRow(row.getString("name"), icon = Icons.Rounded.Folder) { directory = row.getString("path"); address = directory } }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ChatProjectPicker(vm: BeaconViewModel, sessionId: String, onDismiss: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val scope = vm.client?.scope?.session(sessionId)
    val coroutine = rememberCoroutineScope()
    var saving by remember(scope) { mutableStateOf(false) }
    var attempted by remember(sessionId) { mutableStateOf(false) }
    var linking by remember(sessionId) { mutableStateOf(false) }
    var selectedId by remember(ui.origin, ui.owner, ui.profile, sessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, ui.connected, vm.client) { vm.loadNativeProjects() }
    LaunchedEffect(sessionId) { vm.loadConversationProject(sessionId) }
    val current = chatProjectOf(data["project-of:$sessionId"], sessionId)
    val record = data["native-projects"]
    // Read from the latest list each time, so a folder linked just now shows.
    val selected = selectedId?.let { id -> record?.rows("projects")?.firstOrNull { it.string("id") == id } }
    val reconnect = uiText("Reconnect to choose a project.")
    fun assign(project: JSONObject, path: String, link: Boolean = false) {
        if (saving) return
        if (scope == null) vm.notice(reconnect) else {
            saving = true
            attempted = true
            val job = addChatToProject(vm, sessionId, project, path, link, current)
            coroutine.launch {
                try { job.join(); if (vm.ui.value.error.isBlank()) onDismiss() } finally { saving = false }
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(max = 520.dp)) {
            Text(uiText(if (current != null) "Move to project" else "Add to project"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
            if (saving) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(uiText("Moving chat…"), Modifier.testTag("project-chat-saving")) }
            if (scope == null) Text(reconnect, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp))
            // A failed move stays on this sheet, so say why here.
            if (attempted && !saving && ui.error.isNotBlank()) Text(uiText(ui.error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp).testTag("project-chat-error"))
            if (scope != null && current != null) TextButton({
                onDismiss()
                removeChatFromProject(vm, sessionId, current)
            }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Rounded.FolderOff, null); Text(uiText("Remove from") + " " + current.string("name"), Modifier.padding(start = 8.dp)) }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (record == null || record.optBoolean("_loading")) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else if (record.has("_error")) item { Text(uiText("Couldn't load projects. Check your connection and try again."), color = MaterialTheme.colorScheme.error); TextButton(onClick = { vm.loadNativeProjects() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Retry")) } }
                else if (selected == null) {
                    val projects = record.rows("projects").filterNot { it.optBoolean("archived") }
                    if (projects.isEmpty()) item { Text(uiText("Create a project and link a folder to get started.")) }
                    items(projects, key = { it.getString("id") }) { project ->
                        val here = project.string("id") == current?.string("id")
                        val folderCount = project.rows("folders").size
                        // A row that moves the chat at once has no chevron; only one that opens a folder list does.
                        ActionRow(project.string("name"), if (here) uiText("Current project") else folderSummary(folderCount), icon = if (here || folderCount == 1) null else Icons.AutoMirrored.Rounded.KeyboardArrowRight) {
                            // The chat is already here; one folder: nothing to choose, so the chat moves there at once.
                            if (here) onDismiss()
                            else project.rows("folders").singleOrNull()?.let { assign(project, it.getString("path")) } ?: run { selectedId = project.getString("id") }
                        }
                    }
                } else {
                    item { TextButton(onClick = { selectedId = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("All projects")) } }
                    val folders = selected.rows("folders")
                    if (folders.isEmpty()) {
                        item { Text(uiText("Pick a computer folder to link. The chat moves there."), Modifier.padding(vertical = 8.dp)) }
                        item {
                            Button(onClick = { linking = true }, enabled = scope != null && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("project-link-folder")) {
                                Icon(Icons.Rounded.CreateNewFolder, null); Text(uiText("Link a computer folder"), Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    items(if (scope == null) emptyList() else folders, key = { it.getString("path") }) { folder ->
                        ActionRow(folder.string("label").ifBlank { folder.getString("path") }, if (folder.optBoolean("is_primary")) uiText("Main folder") else "", icon = null) { assign(selected, folder.getString("path")) }
                    }
                }
            }
            TextButton(onClick = { onDismiss(); vm.tab("Projects") }, modifier = Modifier.padding(vertical = 12.dp).heightIn(min = 48.dp)) { Text(uiText("Manage projects")) }
        }
    }
    // The same computer folder browser the Projects screen uses; the chosen folder is linked and the chat moves there in one step.
    if (linking && selected != null) PcFolderPicker(vm, "Link computer folder", onDismiss = { linking = false }, onChoose = { path -> linking = false; assign(selected, path, link = true) })
}
