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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hermes.android.CypherViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

@Composable fun KanbanScreen(vm: CypherViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val client = vm.client
    val origin = client?.scope
    val repo = remember(client, origin?.key) { client?.let(::NativeBoards) }
    var boards by remember(origin?.key) { mutableStateOf(emptyList<NativeBoardChoice>()) }
    var board by rememberSaveable(origin?.key) { mutableStateOf("") }
    var cards by remember(origin?.key, board) { mutableStateOf(emptyList<NativeBoardCard>()) }
    var loading by remember(origin?.key, board) { mutableStateOf(true) }
    var busy by remember(origin?.key, board) { mutableStateOf(false) }
    var error by remember(origin?.key, board) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var selectedColumn by rememberSaveable(origin?.key, board) { mutableStateOf("todo") }
    var draft by rememberSaveable(origin?.key, board) { mutableStateOf("") }
    var editing by rememberSaveable(origin?.key, board) { mutableStateOf(false) }
    var confirm by remember(origin?.key, board) { mutableStateOf<Pair<NativeBoardCard, String>?>(null) }
    var conflict by remember(origin?.key, board) { mutableStateOf<NativeBoardCard?>(null) }
    val latestBoard by rememberUpdatedState(board)
    val scope = rememberCoroutineScope()
    val draftLock = remember(origin?.key, board) { Mutex() }
    val draftKind = "kanban-draft:$board"
    fun current(requestedBoard: String? = null) = client != null && vm.client === client && vm.client?.scope == origin && vm.ui.value.profile == ui.profile && (requestedBoard == null || latestBoard == requestedBoard)
    fun persist(value: String) {
        draft = value
        if (origin != null && board.isNotBlank()) vm.viewModelScope.launch {
            draftLock.withLock { try {
                if (value.isBlank()) vm.app.cache.delete(origin, draftKind) else vm.app.cache.write(origin, draftKind, value, protected = true)
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
            } catch (e: Exception) { if (e is CancellationException) throw e; if (current()) error = e.message ?: "Could not load boards" }
            finally { if (current()) loading = false }
            return
        }
        val requestedBoard = board
        loading = true; error = ""
        try { val result = repo.read(requestedBoard); if (current(requestedBoard)) cards = result }
        catch (e: Exception) { if (e is CancellationException) throw e; if (current(requestedBoard)) error = e.message ?: "Could not load this board" }
        finally { if (current(requestedBoard)) loading = false }
    }
    LaunchedEffect(client, origin?.key) {
        try {
            val result = repo?.choices() ?: return@LaunchedEffect
            if (current()) { boards = result.first; if (boards.none { it.slug == board }) board = result.second.takeIf { choice -> boards.any { it.slug == choice } } ?: boards.firstOrNull()?.slug.orEmpty(); if (board.isBlank()) loading = false }
        } catch (e: Exception) { if (e is CancellationException) throw e; if (current()) { error = e.message ?: "Kanban is unavailable"; loading = false } }
    }
    LaunchedEffect(client, origin?.key, board) {
        if (origin != null && board.isNotBlank()) {
            val requestedBoard = board
            try { draftLock.withLock { val saved = vm.app.cache.read(origin, draftKind); if (current(requestedBoard) && draft.isBlank()) draft = saved.orEmpty() } }
            catch (e: Exception) { if (e is CancellationException) throw e; if (current(requestedBoard)) vm.notice("The saved task draft could not be read. It has been retained on this phone.") }
            refresh()
        }
    }
    fun act(action: suspend () -> Unit) {
        if (!current() || busy) return
        val requestedBoard = board
        busy = true; error = ""
        scope.launch { try { if (current(requestedBoard)) action() } catch (e: Exception) { if (e is CancellationException) throw e; if (current(requestedBoard)) error = e.message ?: "Change was not confirmed. Refresh the board before trying again." } finally { if (current(requestedBoard)) busy = false } }
    }
    fun edit(card: NativeBoardCard?) {
        if (draft.isNotBlank()) { editing = true; return }
        act {
            val fresh = card?.let { repo!!.detail(board, it.id) }
            if (current()) { persist(json("id" to fresh?.id.orEmpty(), "title" to fresh?.title.orEmpty(), "body" to fresh?.body.orEmpty(), "assignee" to fresh?.assignee.orEmpty(), "key" to UUID.randomUUID().toString(), "base" to fresh?.json()).toString()); editing = true }
        }
    }
    Column(Modifier.fillMaxSize().testTag("kanban-screen")) {
        Header("Kanban", boards.firstOrNull { it.slug == board }?.name ?: "Native Hermes boards", menu = LocalOpenNavigationMenu.current) {
            IconButton(onClick = { scope.launch { refresh() } }, enabled = !loading && !busy) { Icon(Icons.Rounded.Refresh, "Refresh board") }
            IconButton(onClick = { edit(null) }, enabled = repo != null && !busy && board.isNotBlank()) { Icon(Icons.Rounded.Add, "New task") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box { TextButton(onClick = { menu = true }, enabled = boards.isNotEmpty() && !busy) { Text(boards.firstOrNull { it.slug == board }?.name ?: "Choose board"); Icon(Icons.Rounded.ExpandMore, null) }; DropdownMenu(menu, { menu = false }) { boards.forEach { choice -> DropdownMenuItem({ Text(choice.name) }, { menu = false; editing = false; board = choice.slug }) } } }
            Spacer(Modifier.weight(1f)); Text("${cards.size} tasks", style = MaterialTheme.typography.labelMedium)
        }
        if (draft.isNotBlank() && !editing) TextButton(onClick = { editing = true }, modifier = Modifier.padding(horizontal = 16.dp)) { Icon(Icons.Rounded.EditNote, null); Text("Continue saved task draft") }
        if (error.isNotBlank()) Column(Modifier.padding(16.dp)) { Text(error, color = MaterialTheme.colorScheme.error); TextButton(onClick = { scope.launch { refresh() } }, enabled = !loading && !busy) { Text("Retry") } }
        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && board.isBlank() && error.isBlank()) EmptyState("No boards yet", "Create a Kanban board in your Hermes dashboard, then refresh it here.", Icons.Rounded.ViewKanban)
        val shownStatuses = boardStatuses.keys.filter { it != "archived" || cards.any { card -> card.status == "archived" } }
        val visibleColumn = selectedColumn.takeIf { it in shownStatuses } ?: shownStatuses.first()
        BoxWithConstraints(Modifier.weight(1f)) {
            val expanded = maxWidth >= 720.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
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
                                Text(card.assignee.ifBlank { "Unassigned" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                if (card.summary.isNotBlank()) Text(card.summary, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                var moving by remember(card.id) { mutableStateOf(false) }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { edit(card) }, enabled = !busy) { Text("Edit") }
                                    Box { TextButton(onClick = { moving = true }, enabled = !busy) { Text("Move") }; DropdownMenu(moving, { moving = false }) { boardStatuses.filterKeys { it != card.status && it != "running" }.forEach { (target, label) -> DropdownMenuItem({ Text(label) }, { moving = false; confirm = card to target }) } } }
                                    TextButton(onClick = { confirm = card to "delete" }, enabled = !busy) { Text("Delete") }
                                }
                            } }
                        }
                    }
                }
            }
            if (expanded) Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) { shownStatuses.forEach { status -> column(status, Modifier.width(300.dp).fillMaxHeight()) } }
            else Column { key(shownStatuses) { ScrollableTabRow(selectedTabIndex = shownStatuses.indexOf(visibleColumn), edgePadding = 8.dp) { shownStatuses.forEach { status -> Tab(selected = visibleColumn == status, onClick = { selectedColumn = status }, text = { Text("${boardStatuses[status]} ${cards.count { it.status == status }}") }) } } }; column(visibleColumn, Modifier.weight(1f)) }
        }
    }
    confirm?.let { (card, destination) ->
        AlertDialog(onDismissRequest = { if (!busy) confirm = null }, title = { Text(if (destination == "delete") "Delete task?" else "Move to ${boardStatuses[destination]}?") }, text = { Text(when (destination) { "delete" -> "This removes “${card.title}” from the native Hermes board."; "ready" -> "An assigned bot may begin this task when the dispatcher picks it up."; "done", "review" -> "This is your manual status update. It may override an active worker’s claim."; else -> card.title }) }, confirmButton = { TextButton(enabled = !busy, onClick = { act {
            if (destination == "delete") { repo!!.delete(board, card.id); if (current()) cards = cards.filterNot { it.id == card.id } }
            else { val changed = repo!!.update(board, card.id, boardMoveFields(destination)); if (current()) { cards = cards.filterNot { it.id == changed.id } + changed; selectedColumn = changed.status } }
            if (current()) confirm = null
        } }) { Text(if (destination == "delete") "Delete" else "Move") } }, dismissButton = { TextButton(enabled = !busy, onClick = { confirm = null }) { Text("Cancel") } })
    }
    if (editing && draft.isNotBlank()) {
        val record = runCatching { JSONObject(draft) }.getOrNull()
        if (record != null) {
            var discard by remember { mutableStateOf(false) }
            fun change(key: String, value: String) { persist(record.copy().put(key, value).toString()) }
            BackHandler { if (!busy) editing = false }
            AlertDialog(onDismissRequest = { if (!busy) editing = false }, title = { Text(if (record.string("id").isBlank()) "New task" else "Edit task") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(record.string("title"), { change("title", it.take(160)) }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth().testTag("kanban-title"), enabled = !busy)
                OutlinedTextField(record.string("body"), { change("body", it.take(16_000)) }, label = { Text("Description") }, minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                OutlinedTextField(record.string("assignee"), { change("assignee", it.take(80)) }, label = { Text("Bot profile (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { discard = true }, enabled = !busy) { Text("Discard draft") }
            } }, confirmButton = { TextButton(enabled = !busy && record.string("title").isNotBlank(), onClick = { act {
                val fields = boardTaskFields(record.string("title"), record.string("body"), record.string("assignee"))
                val id = record.string("id")
                val result = if (id.isBlank()) repo!!.create(board, fields, record.getString("key")) else {
                    val fresh = repo!!.detail(board, id)
                    val base = record.optJSONObject("base")
                    if (base == null || listOf("title", "body", "assignee").any { fresh.json().string(it) != base.string(it) }) { conflict = fresh; error("This task changed. Review the latest version before saving.") }
                    repo.update(board, id, fields)
                }
                if (current()) { cards = cards.filterNot { it.id == result.id } + result; selectedColumn = result.status; persist(""); editing = false }
            } }) { Text(if (busy) "Saving…" else "Save") } }, dismissButton = { TextButton(enabled = !busy, onClick = { editing = false }) { Text("Keep draft") } })
            if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard task draft?") }, text = { Text("Your saved task stays unchanged. Only these unsaved edits will be removed.") }, confirmButton = { TextButton(onClick = { persist(""); editing = false; discard = false }) { Text("Discard") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
            conflict?.let { latest -> AlertDialog(onDismissRequest = { conflict = null }, title = { Text("Task changed on the dashboard") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(latest.title); Text(latest.body); Text("Bot: ${latest.assignee.ifBlank { "Unassigned" }}"); Text("Review this version, then keep your edits or use the latest details.") } }, confirmButton = { TextButton(onClick = { persist(record.copy().put("base", latest.json()).toString()); conflict = null; error = "" }) { Text("Keep my edits") } }, dismissButton = { TextButton(onClick = { persist(record.copy().put("title", latest.title).put("body", latest.body).put("assignee", latest.assignee).put("base", latest.json()).toString()); conflict = null; error = "" }) { Text("Use latest") } }) }
        }
    }
}
