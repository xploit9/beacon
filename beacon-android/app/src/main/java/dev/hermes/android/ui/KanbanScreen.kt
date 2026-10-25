@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.hermes.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

internal fun kanbanLaterColumnTaskCount(cards: List<NativeBoardCard>, shownStatuses: List<String>): Int {
    val laterStatuses = shownStatuses.drop(3).toSet()
    return cards.count { it.status in laterStatuses }
}

internal fun kanbanCompletionFields(result: String): JSONObject {
    val evidence = result.trim()
    require(evidence.isNotBlank()) { "Enter a short result before marking the task done." }
    return boardMoveFields("done").put("result", evidence)
}

internal fun kanbanErrorMessage(error: Throwable): String =
    if (((error as? ApiFailure)?.detail ?: error.message.orEmpty()).contains("has no result or summary evidence", ignoreCase = true))
        "Add a short result before marking this task done."
    else "Couldn't update this task. Refresh the board and try again."

internal fun kanbanDraftKey(id: String) = if (id.isBlank()) "new" else "task:$id"

internal fun kanbanDraftRecords(value: String): JSONObject {
    val saved = runCatching { JSONObject(value) }.getOrNull() ?: return JSONObject()
    // Keep drafts written by earlier versions, which stored a single task per board.
    return if (saved.has("key")) JSONObject().put(kanbanDraftKey(saved.string("id")), saved) else saved
}

internal fun kanbanDraftChanged(record: JSONObject): Boolean {
    val base = record.optJSONObject("base")
    return listOf("title", "body", "assignee").any { record.string(it) != base?.string(it).orEmpty() }
}

internal fun kanbanMissingSavedTask(record: JSONObject?, error: Throwable): Boolean =
    record?.string("id")?.isNotBlank() == true && error is ApiFailure && error.status == 404

private fun kanbanProfileLabel(profile: String, profiles: List<JSONObject>, loading: Boolean): String = when {
    profile.isBlank() -> "Unassigned"
    profiles.any { it.string("name") == profile } -> profileDisplayName(profile, profiles)
    loading -> "Loading profiles…"
    else -> "Unavailable profile"
}

@Composable fun KanbanScreen(vm: BeaconViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val client = vm.client
    val origin = client?.scope
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val profileLoading = ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading"))
    val repo = remember(client, origin?.key) { client?.let { active -> NativeBoards(active) { key, document -> vm.keepScreen(active.scope, key, document) } } }
    // The last boards and cards seen for this account open at once; the refresh below replaces them.
    val cachedChoices = remember(origin?.key) { vm.data.value[KANBAN_BOARDS_KEY]?.let { runCatching { nativeBoardChoices(it) }.getOrNull() } }
    var boards by remember(origin?.key) { mutableStateOf(cachedChoices?.first.orEmpty()) }
    var board by rememberSaveable(origin?.key) { mutableStateOf(cachedChoices?.let { (list, current) -> current.takeIf { slug -> list.any { it.slug == slug } } ?: list.firstOrNull()?.slug }.orEmpty()) }
    var cards by remember(origin?.key, board) { mutableStateOf(vm.data.value[kanbanBoardKey(board)]?.let { runCatching { nativeBoardCards(it) }.getOrNull() }.orEmpty()) }
    var loading by remember(origin?.key, board) { mutableStateOf(true) }
    var busy by remember(origin?.key, board) { mutableStateOf(false) }
    var error by remember(origin?.key, board) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var selectedColumn by rememberSaveable(origin?.key, board) { mutableStateOf("") }
    var draftTaskKey by rememberSaveable(origin?.key, board) { mutableStateOf("new") }
    var savedDrafts by rememberSaveable(origin?.key, board, stateSaver = Saver(
        save = { value: String -> kanbanDraftRecords(value).optJSONObject(draftTaskKey)?.let { JSONObject().put(draftTaskKey, it).toString() }.orEmpty() },
        restore = { it },
    )) { mutableStateOf("") }
    val records = kanbanDraftRecords(savedDrafts)
    val draft = records.optJSONObject(draftTaskKey)?.toString().orEmpty()
    var editing by rememberSaveable(origin?.key, board) { mutableStateOf(false) }
    var confirm by remember(origin?.key, board) { mutableStateOf<Pair<NativeBoardCard, String>?>(null) }
    var conflict by remember(origin?.key, board) { mutableStateOf<NativeBoardCard?>(null) }
    val latestBoard by rememberUpdatedState(board)
    val scope = rememberCoroutineScope()
    val draftLock = remember(origin?.key, board) { Mutex() }
    val draftKind = "kanban-draft:$board"
    fun current(requestedBoard: String? = null) = client != null && vm.client === client && vm.client?.scope == origin && vm.ui.value.profile == ui.profile && (requestedBoard == null || latestBoard == requestedBoard)
    fun persist(value: String) {
        val updated = kanbanDraftRecords(savedDrafts)
        if (value.isBlank()) updated.remove(draftTaskKey) else updated.put(draftTaskKey, JSONObject(value))
        savedDrafts = if (updated.length() == 0) "" else updated.toString()
        val snapshot = savedDrafts
        if (origin != null && board.isNotBlank()) vm.viewModelScope.launch {
            draftLock.withLock { try {
                if (snapshot.isBlank()) vm.app.cache.delete(origin, draftKind) else vm.app.cache.write(origin, draftKind, snapshot, protected = true)
            } catch (e: Exception) { if (e is CancellationException) throw e; if (current()) vm.notice("This task draft could not be saved on the phone") } }
        }
    }
    suspend fun refresh() {
        if (repo == null) return
        if (board.isBlank()) {
            loading = true; error = ""
            try {
                val choices = repo.choices()
                if (current()) { boards = choices.first; board = choices.second.takeIf { candidate -> boards.any { it.slug == candidate } } ?: boards.firstOrNull()?.slug.orEmpty() }
            } catch (e: Exception) { if (e is CancellationException) throw e; if (current()) error = "Couldn't load boards. Check your connection and try again." }
            finally { if (current()) loading = false }
            return
        }
        val requestedBoard = board
        loading = true; error = ""
        try { val result = repo.read(requestedBoard); if (current(requestedBoard)) cards = result }
        catch (e: Exception) { if (e is CancellationException) throw e; if (current(requestedBoard)) error = "Couldn't load this board. Check your connection and try again." }
        finally { if (current(requestedBoard)) loading = false }
    }
    LaunchedEffect(ui.origin, ui.owner, ui.connected) { if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    LaunchedEffect(client, origin?.key) {
        try {
            val result = repo?.choices() ?: return@LaunchedEffect
            if (current()) { boards = result.first; if (boards.none { it.slug == board }) board = result.second.takeIf { choice -> boards.any { it.slug == choice } } ?: boards.firstOrNull()?.slug.orEmpty(); if (board.isBlank()) loading = false }
        } catch (e: Exception) { if (e is CancellationException) throw e; if (current()) { error = "Couldn't load boards. Check your connection and try again."; loading = false } }
    }
    LaunchedEffect(client, origin?.key, board) {
        if (origin != null && board.isNotBlank()) {
            val requestedBoard = board
            try { draftLock.withLock {
                val saved = vm.app.cache.read(origin, draftKind)
                if (current(requestedBoard)) {
                    val merged = kanbanDraftRecords(saved.orEmpty())
                    val restored = kanbanDraftRecords(savedDrafts)
                    restored.keys().forEach { merged.put(it, restored.opt(it)) }
                    savedDrafts = if (merged.length() == 0) "" else merged.toString()
                }
            } }
            catch (e: Exception) { if (e is CancellationException) throw e; if (current(requestedBoard)) vm.notice("The saved task draft could not be read. It has been retained on this phone.") }
            refresh()
        }
    }
    fun act(action: suspend () -> Unit) {
        if (!current() || busy || loading) return
        val requestedBoard = board
        busy = true; error = ""
        scope.launch { try { if (current(requestedBoard)) action() } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (current(requestedBoard)) {
                val missingSavedTask = editing && kanbanMissingSavedTask(runCatching { JSONObject(draft) }.getOrNull(), e)
                if (missingSavedTask) { persist(""); editing = false; conflict = null }
                error = if (missingSavedTask) "This task no longer exists. Its saved draft was cleared." else kanbanErrorMessage(e)
            }
        } finally { if (current(requestedBoard)) busy = false } }
    }
    fun edit(card: NativeBoardCard?) {
        if (busy || loading) return
        draftTaskKey = kanbanDraftKey(card?.id.orEmpty())
        if (kanbanDraftRecords(savedDrafts).has(draftTaskKey)) { editing = true; return }
        val requestedBoard = board
        act {
            val fresh = card?.let { repo!!.detail(requestedBoard, it.id) }
            if (current(requestedBoard)) { persist(json("id" to fresh?.id.orEmpty(), "title" to fresh?.title.orEmpty(), "body" to fresh?.body.orEmpty(), "assignee" to fresh?.assignee.orEmpty(), "key" to UUID.randomUUID().toString(), "base" to fresh?.json()).toString()); editing = true }
        }
    }
    Column(Modifier.fillMaxSize().testTag("kanban-screen")) {
        Header("Kanban", menu = LocalOpenNavigationMenu.current) {
            IconButton(onClick = { scope.launch { refresh() } }, enabled = !loading && !busy) { Icon(Icons.Rounded.Refresh, "Refresh board") }
            IconButton(onClick = { edit(null) }, enabled = repo != null && !busy && !loading && board.isNotBlank(), modifier = Modifier.testTag("kanban-new-task")) { Icon(Icons.Rounded.Add, "New task") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box { OutlinedButton(onClick = { menu = true }, enabled = boards.isNotEmpty() && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(boards.firstOrNull { it.slug == board }?.name ?: "Choose board"); Icon(Icons.Rounded.ExpandMore, null) }; DropdownMenu(menu, { menu = false }) { boards.forEach { choice -> DropdownMenuItem({ Text(choice.name) }, { menu = false; editing = false; board = choice.slug }) } } }
            Spacer(Modifier.weight(1f)); Text("${cards.size} tasks", style = MaterialTheme.typography.labelMedium)
        }
        if (records.length() > 0 && !editing) OutlinedButton(onClick = { if (!records.has(draftTaskKey)) draftTaskKey = records.keys().next(); editing = true }, enabled = !busy && !loading, modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp)) { Icon(Icons.Rounded.EditNote, null); Text("Continue saved task draft", Modifier.padding(start = 8.dp)) }
        if (error.isNotBlank()) Column(Modifier.padding(16.dp)) { Text(error, color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = { scope.launch { refresh() } }, enabled = !loading && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") } }
        if (loading && cards.isEmpty() || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && board.isBlank() && error.isBlank()) EmptyState("No boards yet", "Create a Kanban board in your Hermes dashboard, then refresh it here.", Icons.Rounded.ViewKanban)
        val shownStatuses = boardStatuses.keys.filter { it != "archived" || cards.any { card -> card.status == "archived" } }
        val visibleColumn = selectedColumn.takeIf { it in shownStatuses } ?: cards.firstOrNull { it.status in shownStatuses }?.status ?: "todo"
        BoxWithConstraints(Modifier.weight(1f)) {
            val expanded = maxWidth >= 720.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            val laterTaskCount = if (expanded) kanbanLaterColumnTaskCount(cards, shownStatuses) else 0
            @Composable fun column(status: String, modifier: Modifier) {
                val rows = cards.filter { it.status == status }
                Column(modifier) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(boardStatuses.getValue(status), style = MaterialTheme.typography.titleSmall); Text(rows.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (rows.isEmpty() && !loading) item { Text("No tasks here", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(rows, key = { it.id }) { card ->
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(card.title, style = MaterialTheme.typography.titleMedium)
                                if (card.body.isNotBlank()) Text(card.body, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(kanbanProfileLabel(card.assignee, profileRows, profileLoading), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                if (card.summary.isNotBlank()) Text(card.summary, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                var moving by remember(card.id) { mutableStateOf(false) }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedButton(onClick = { edit(card) }, enabled = !busy && !loading, modifier = Modifier.heightIn(min = 48.dp).testTag("kanban-edit:${card.id}")) { Text("Edit") }
                                    Box { OutlinedButton(onClick = { moving = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Move") }; DropdownMenu(moving, { moving = false }) { boardStatuses.filterKeys { it != card.status && it != "running" }.forEach { (target, label) -> DropdownMenuItem({ Text(label) }, { moving = false; error = ""; confirm = card to target }) } } }
                                    OutlinedButton(onClick = { error = ""; confirm = card to "delete" }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                                }
                            } }
                        }
                    }
                }
            }
            if (expanded) Column(Modifier.fillMaxSize()) {
                if (laterTaskCount > 0) Text("$laterTaskCount ${if (laterTaskCount == 1) "task" else "tasks"} in later columns",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) { shownStatuses.forEach { status -> column(status, Modifier.width(300.dp).fillMaxHeight()) } }
            }
            else Column { key(shownStatuses) { ScrollableTabRow(selectedTabIndex = shownStatuses.indexOf(visibleColumn), edgePadding = 8.dp) { shownStatuses.forEach { status -> Tab(selected = visibleColumn == status, onClick = { selectedColumn = status }, text = { Text("${boardStatuses[status]} ${cards.count { it.status == status }}") }) } } }; column(visibleColumn, Modifier.weight(1f)) }
        }
    }
    confirm?.let { (card, destination) ->
        var completionResult by remember(card.id, destination) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!busy) { confirm = null; error = "" } },
            title = { Text(if (destination == "delete") "Delete task?" else "Move to ${boardStatuses[destination]}?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(when (destination) {
                    "delete" -> "This removes “${card.title}” from the native Hermes board."
                    "ready" -> "An assigned bot may begin this task when the dispatcher picks it up."
                    "done" -> "Add a short result before marking this task done."
                    "review" -> "Send this task for review."
                    else -> card.title
                })
                if (destination == "done") OutlinedTextField(completionResult, { completionResult = it }, label = { Text("Result") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth().testTag("kanban-completion-result"), enabled = !busy)
                if (destination in setOf("done", "review") && card.status == "running") Text("A worker is actively working on this task. This change will override its claim.", color = MaterialTheme.colorScheme.error)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = {
                TextButton(
                    enabled = !busy && (destination != "done" || completionResult.isNotBlank()),
                    onClick = { act {
                        if (destination == "delete") {
                            repo!!.delete(board, card.id)
                            if (current()) cards = cards.filterNot { it.id == card.id }
                        } else {
                            val fields = if (destination == "done") kanbanCompletionFields(completionResult) else boardMoveFields(destination)
                            val changed = repo!!.update(board, card.id, fields)
                            if (current()) { cards = cards.filterNot { it.id == changed.id } + changed; selectedColumn = changed.status }
                        }
                        if (current()) confirm = null
                    } },
                ) { Text(if (destination == "delete") "Delete" else "Move") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirm = null; error = "" }) { Text("Cancel") } },
        )
    }
    if (editing && draft.isNotBlank()) {
        val record = runCatching { JSONObject(draft) }.getOrNull()
        if (record != null) {
            val editable = !busy && !loading
            var profileMenu by remember(record.string("key")) { mutableStateOf(false) }
            fun change(key: String, value: String) { persist(record.copy().put(key, value).toString()) }
            fun cancelEdit() { if (!kanbanDraftChanged(record)) persist(""); editing = false; error = ""; conflict = null }
            BackHandler { if (editable) cancelEdit() }
            AlertDialog(onDismissRequest = { if (editable) cancelEdit() }, title = { Text(if (record.string("id").isBlank()) "New task" else "Edit task") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(record.string("title"), { change("title", it.take(160)) }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth().testTag("kanban-title"), enabled = editable)
                OutlinedTextField(record.string("body"), { change("body", it.take(16_000)) }, label = { Text("Description") }, minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth(), enabled = editable)
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { profileMenu = true }, enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Bot profile: ${kanbanProfileLabel(record.string("assignee"), profileRows, profileLoading)}", Modifier.weight(1f))
                        Icon(Icons.Rounded.ExpandMore, null)
                    }
                    DropdownMenu(profileMenu, { profileMenu = false }) {
                        DropdownMenuItem(text = { Text("Unassigned") }, onClick = { profileMenu = false; change("assignee", "") })
                        when {
                            profileCatalog == null || profileCatalog.optBoolean("_loading") -> DropdownMenuItem(text = { Text("Loading profiles…") }, onClick = {}, enabled = false)
                            profileCatalog.string("_error").isNotBlank() -> DropdownMenuItem(text = { Text("Profiles unavailable") }, onClick = {}, enabled = false)
                            else -> profileRows.forEach { profile ->
                                val name = profile.string("name")
                                if (name.isNotBlank()) DropdownMenuItem(text = { Text(profileDisplayName(name, profileRows)) }, onClick = { profileMenu = false; change("assignee", name) })
                            }
                        }
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            } }, confirmButton = { TextButton(enabled = editable && record.string("title").isNotBlank(), onClick = { act {
                val fields = boardTaskFields(record.string("title"), record.string("body"), record.string("assignee"))
                val id = record.string("id")
                val result = if (id.isBlank()) repo!!.create(board, fields, record.getString("key")) else {
                    val fresh = repo!!.detail(board, id)
                    val base = record.optJSONObject("base")
                    if (base == null || listOf("title", "body", "assignee").any { fresh.json().string(it) != base.string(it) }) { conflict = fresh; error("This task changed. Review the latest version before saving.") }
                    repo.update(board, id, fields)
                }
                if (current()) { cards = cards.filterNot { it.id == result.id } + result; selectedColumn = result.status; persist(""); editing = false }
            } }) { Text(if (busy) "Saving…" else "Save") } }, dismissButton = { Row {
                TextButton(enabled = editable, onClick = { persist(""); editing = false; error = ""; conflict = null }, modifier = Modifier.testTag("kanban-discard")) { Text("Discard") }
                TextButton(enabled = editable, onClick = ::cancelEdit) { Text("Cancel") }
            } })
            conflict?.let { latest -> AlertDialog(onDismissRequest = { conflict = null }, title = { Text("Task changed on the dashboard") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(latest.title); Text(latest.body); Text("Bot: ${kanbanProfileLabel(latest.assignee, profileRows, profileLoading)}"); Text("Review this version, then keep your edits or use the latest details.") } }, confirmButton = { TextButton(onClick = { persist(record.copy().put("base", latest.json()).toString()); conflict = null; error = "" }) { Text("Keep my edits") } }, dismissButton = { TextButton(onClick = { persist(record.copy().put("title", latest.title).put("body", latest.body).put("assignee", latest.assignee).put("base", latest.json()).toString()); conflict = null; error = "" }) { Text("Use latest") } }) }
        }
    }
}
