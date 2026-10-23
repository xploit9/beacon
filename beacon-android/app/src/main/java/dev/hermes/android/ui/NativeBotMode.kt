package dev.hermes.android.ui

import dev.hermes.android.core.rows
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.data.BotModeAction
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable fun NativeBotModeSidebar(vm: BeaconViewModel, modifier: Modifier = Modifier, beforeRoster: @Composable () -> Unit = {}) {
    val state by vm.botMode.state.collectAsState()
    val ui by vm.ui.collectAsState()
    val data by vm.data.collectAsState()
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val profileLoading = ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading"))
    LaunchedEffect(ui.origin, ui.owner, ui.connected) { if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    LaunchedEffect(vm.botMode) { vm.botMode.refresh(silent = true) }
    LazyColumn(modifier.background(MaterialTheme.colorScheme.surfaceContainer).testTag("bot-mode-sidebar"), contentPadding = PaddingValues(bottom = 16.dp)) {
        item("destinations") { beforeRoster() }
        if (state.loading && state.bots.isEmpty() && state.spaces.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.error.isNotBlank() && !(ui.tab == "More" && ui.morePage == "Bots")) item {
            Column(Modifier.padding(16.dp)) { Text(state.error, color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = { vm.showBotMode() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Review Bot Mode") } }
        }
        item { Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Bots", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { vm.botMode.refresh() }, enabled = !state.loading, modifier = Modifier.testTag("bot-mode-refresh")) { Icon(Icons.Rounded.Refresh, "Refresh Bot Mode") }
        } }
        if (state.bots.isEmpty() && !state.loading) item { Text("No bots available.", Modifier.padding(16.dp)) }
        items(state.bots, key = { "bot:${it.profile}" }) { bot ->
            val active = state.activeBot?.profile == bot.profile
            ListItem(headlineContent = { Text(profileDisplayLabel(bot.profile, profileRows, profileLoading), maxLines = 2) },
                supportingContent = if (bot.description.isNotBlank()) ({ Text(bot.description, maxLines = 2, style = MaterialTheme.typography.bodySmall) }) else null,
                leadingContent = { Icon(Icons.Rounded.SmartToy, null) },
                colors = ListItemDefaults.colors(containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { selected = active }.clickable { vm.openBot(bot) }.testTag("bot-row-${bot.profile}"))
        }
        item { Text("Shared spaces", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        items(state.spaces, key = { "space:${it.id}" }) { space ->
            val active = state.activeBot == null && state.selectedSpace == space.id
            ListItem(headlineContent = { Text(space.name, maxLines = 2) }, leadingContent = { Icon(Icons.Rounded.Forum, null) },
                colors = ListItemDefaults.colors(containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { selected = active }.clickable { vm.openBotSpace(space.id) }.testTag("bot-space-${space.id}"))
        }
        if (state.spaces.isEmpty() && !state.loading) item { Text("Create a shared space from the overview.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
        if (state.moreSpaces) item { Text("Showing the first 100 shared spaces.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable fun NativeBotModeScreen(vm: BeaconViewModel) {
    val controller = vm.botMode
    val state by controller.state.collectAsState()
    val chat by vm.chat.collectAsState()
    val owner by vm.ui.collectAsState()
    val data by vm.data.collectAsState()
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val profileLoading = owner.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading"))
    LaunchedEffect(owner.origin, owner.owner, owner.connected) { if (owner.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    BackHandler(state.activeBot != null || state.openingBot != null || state.selectedSpace.isNotBlank()) { vm.showBotMode() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller, lifecycle, state.selectedSpace, state.activeBot) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            controller.refresh(silent = true)
            while (state.selectedSpace.isNotBlank() && state.activeBot == null) { delay(3000); controller.refresh(silent = true) }
        }
    }
    val bot = state.activeBot ?: state.openingBot
    if (bot != null) {
        val botName = profileDisplayLabel(bot.profile, profileRows, profileLoading)
        if (state.activeBot != null && chat.sessionId == state.sessionId) ChatScreen(vm, titleOverride = "$botName · Bot Chat")
        else Column(Modifier.fillMaxSize()) {
            Header("$botName · Bot Chat", menu = LocalOpenNavigationMenu.current)
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Opening Bot Chat…", Modifier.padding(20.dp))
            } else {
                Text(state.error.ifBlank { "This Bot Chat could not be opened." }, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                OutlinedButton({ controller.openBot(bot) }, Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp)) { Text("Retry") }
            }
        }
        return
    }
    val shared = state.selectedSpace.isNotBlank()
    val spaceName = state.spaces.firstOrNull { it.id == state.selectedSpace }?.name ?: "Shared space"
    var menu by remember(owner.origin, owner.owner, owner.profile, state.selectedSpace) { mutableStateOf(false) }
    var editing by rememberSaveable(owner.origin, owner.owner, owner.profile, state.selectedSpace) { mutableStateOf(false) }
    var draftName by rememberSaveable(owner.origin, owner.owner, owner.profile, state.selectedSpace) { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var creating by rememberSaveable(owner.origin, owner.owner) { mutableStateOf(false) }
    var deleting by rememberSaveable(owner.origin, owner.owner, state.selectedSpace) { mutableStateOf(false) }
    var choosingMembers by rememberSaveable(owner.origin, owner.owner, state.selectedSpace) { mutableStateOf(false) }
    var members by remember { mutableStateOf(emptySet<String>()) }
    var membersSubmitted by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading, state.error, state.members) {
        if (membersSubmitted && !state.loading && state.error.isBlank() && state.members.toSet() == members) { choosingMembers = false; membersSubmitted = false }
    }
    LaunchedEffect(state.selectedSpace) { if (state.selectedSpace.isNotBlank()) creating = false }
    BackHandler(creating) { creating = false }
    if (choosingMembers && shared) AlertDialog(onDismissRequest = { if (!state.loading) choosingMembers = false },
        title = { Text("Choose bots") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose 2–6 bots. Changing the team stops its current work.")
            state.bots.forEach { bot ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(bot.profile in members, enabled = !state.loading, role = androidx.compose.ui.semantics.Role.Checkbox) {
                    members = if (bot.profile in members) members - bot.profile else if (members.size < 6) members + bot.profile else members
                }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(bot.profile in members, onCheckedChange = null); Text(profileDisplayLabel(bot.profile, profileRows, profileLoading), Modifier.padding(start = 8.dp))
                }
            }
            if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton({ membersSubmitted = true; controller.updateMembers(members) }, enabled = !state.loading && members.size in 2..6 && members != state.members.toSet()) { Text(if (state.loading) "Saving…" else "Save bots") } },
        dismissButton = { TextButton({ choosingMembers = false }, enabled = !state.loading) { Text("Cancel") } })
    if (deleting && shared) AlertDialog(onDismissRequest = { if (!state.loading) deleting = false }, title = { Text("Delete shared space?") },
        text = { Column { Text("Remove $spaceName from active spaces and stop its work. Individual Bot Chats are kept."); if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error) } },
        confirmButton = { TextButton({ controller.deleteSpace() }, enabled = !state.loading) { Text(if (state.loading) "Deleting…" else "Delete space", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ deleting = false }, enabled = !state.loading) { Text("Cancel") } })
    LaunchedEffect(state.loading, state.error, spaceName) {
        if (submitted && !state.loading && state.error.isBlank() && spaceName == draftName.trim()) { editing = false; submitted = false }
    }
    if (editing && shared) AlertDialog(onDismissRequest = { if (!state.loading) editing = false },
        title = { Text("Edit shared space") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(draftName, { draftName = it.take(120) }, label = { Text("Name") }, singleLine = true, enabled = !state.loading, modifier = Modifier.testTag("shared-space-name"))
            Text("Bots", style = MaterialTheme.typography.titleSmall)
            Text(state.members.map { profile -> profileDisplayLabel(profile, profileRows, profileLoading) }.joinToString(", "))
            OutlinedButton({ editing = false; members = state.members.toSet(); membersSubmitted = false; choosingMembers = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change bots") }
            if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = { submitted = true; controller.renameSpace(draftName) }, enabled = !state.loading && draftName.isNotBlank()) { Text(if (state.loading) "Saving…" else "Save") } },
        dismissButton = { TextButton(onClick = { editing = false }, enabled = !state.loading) { Text("Cancel") } })
    Column(Modifier.fillMaxSize().imePadding().testTag(if (shared) "bot-mode-shared" else "bot-mode-home")) {
        Header(if (shared) spaceName else if (creating) "New shared space" else "Bot Mode", back = if (creating) ({ creating = false }) else null, menu = if (creating) null else LocalOpenNavigationMenu.current, actions = {
            if (shared) Box {
                IconButton(onClick = { menu = true }, enabled = state.pendingChange == null) { Icon(Icons.Rounded.MoreVert, "Shared-space actions") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Edit shared space") }, onClick = { menu = false; draftName = spaceName; submitted = false; editing = true })
                    DropdownMenuItem({ Text("Choose bots") }, onClick = { menu = false; members = state.members.toSet(); membersSubmitted = false; choosingMembers = true })
                    HorizontalDivider()
                    DropdownMenuItem({ Text("Delete shared space", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = true })
                }
            } else if (!creating) {
                IconButton(onClick = { controller.refresh() }, enabled = !state.loading) { Icon(Icons.Rounded.Refresh, "Refresh Bot Mode") }
                IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "New shared space") }
            }
        })
        if (state.loading && state.events.isEmpty() && (shared || state.bots.isEmpty())) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error.isNotBlank()) {
            Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!shared && !creating) {
                item { Text("Chat with one bot, or bring several into a shared space.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Text("Bots", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
                items(state.bots, key = { "overview-bot:${it.profile}" }) { bot ->
                    ActionRow(profileDisplayLabel(bot.profile, profileRows, profileLoading), bot.description, Icons.Rounded.SmartToy) { vm.openBot(bot) }
                }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Shared spaces", style = MaterialTheme.typography.titleLarge)
                    Button({ creating = true }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Rounded.Add, null); Text("New", Modifier.padding(start = 8.dp)) }
                } }
                if (state.spaces.isEmpty() && !state.loading) item { Text("Give a team of bots a task in one shared conversation.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.spaces, key = { "overview-space:${it.id}" }) { space ->
                    ActionRow(space.name, "Team conversation", Icons.Rounded.Forum) { vm.openBotSpace(space.id) }
                }
            } else if (creating) {
                item { Text("Create a shared space", style = MaterialTheme.typography.titleLarge); Text("Choose two to six bots to work together.") }
                items(state.bots, key = { "member:${it.profile}" }) { member ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = member.profile in state.selectedBots, enabled = !state.loading, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = { controller.toggleBot(member.profile) }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = member.profile in state.selectedBots, onCheckedChange = null, enabled = !state.loading)
                        Text(profileDisplayLabel(member.profile, profileRows, profileLoading), Modifier.padding(start = 12.dp))
                    }
                }
                item { Button(onClick = { controller.createSpace() }, enabled = !state.loading && state.selectedBots.size in 2..6) { Text("Create shared space") } }
            } else {
                item {
                    if (state.pendingChange != null) {
                        Text("Your change is waiting for the bots’ current work to stop.")
                        OutlinedButton(controller::retryChange, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry pending change") }
                    } else Text(state.status, style = MaterialTheme.typography.bodyMedium)
                    if (state.events.any { it.failed }) OutlinedButton(onClick = { vm.more("Profiles") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Review bot profiles") }
                    if (state.earlierEventsOmitted) Text("Showing the latest 200 shared-space events.", style = MaterialTheme.typography.bodySmall)
                    if (state.events.isEmpty() && !state.loading) Text("Send the bots a task to start the discussion.")
                }
                items(state.events, key = { "event:${it.id}" }) { event ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (event.speaker == "You") Arrangement.End else Arrangement.Start) {
                        Surface(Modifier.widthIn(max = 560.dp).padding(start = if (event.speaker == "You") 24.dp else 0.dp, end = if (event.speaker == "You") 0.dp else 24.dp), shape = MaterialTheme.shapes.large,
                            color = if (event.failed) MaterialTheme.colorScheme.errorContainer else if (event.speaker == "You") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(event.speaker + if (event.failed) " · failed" else "", style = MaterialTheme.typography.labelLarge)
                                Markdown(event.text, vm, origin = null, documentKey = "room:${state.selectedSpace}:${event.id}")
                            }
                        }
                    }
                }
                items(state.actions, key = { "action:${it.task}:${it.request}" }) { action ->
                    NativeBotRoomAction(action, state.loading, controller::action)
                }
            }
        }
        if (shared) Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state.draft, controller::draft, label = { Text("Message the shared space") }, modifier = Modifier.fillMaxWidth().testTag("bot-shared-composer"), maxLines = 6, enabled = !state.sending)
                Button(onClick = { controller.send() }, enabled = !state.loading && !state.sending && state.pendingChange == null && state.draft.isNotBlank(), modifier = Modifier.align(androidx.compose.ui.Alignment.End)) { Text(if (state.sending) "Sending…" else "Send") }
            }
        }
    }
}

@Composable private fun NativeBotRoomAction(action: BotModeAction, loading: Boolean, resolve: (BotModeAction, String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (action.kind == "approval") {
                Text("${action.member} needs permission", style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(action.description.ifBlank { "Native tool approval requested" }) }
                Row {
                    if ("once" in action.choices) Button(onClick = { resolve(action, "once") }, enabled = !loading && action.description.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow once") }
                    if ("deny" in action.choices) OutlinedButton(onClick = { resolve(action, "deny") }, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Deny") }
                }
            } else {
                Text("A bot task needs review before retrying. Its earlier outcome may be unknown.")
                OutlinedButton(onClick = { resolve(action, "") }, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry bot task") }
            }
        }
    }
}
