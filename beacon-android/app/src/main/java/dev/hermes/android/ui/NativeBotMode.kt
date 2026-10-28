package dev.hermes.android.ui

import dev.hermes.android.core.rows
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.ArrowUpward
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
            Column(Modifier.padding(16.dp)) { Text(uiText(state.error), color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = { vm.showBotMode() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Review Bot Mode")) } }
        }
        item { Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(uiText("Bots"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { vm.botMode.refresh() }, enabled = !state.loading, modifier = Modifier.testTag("bot-mode-refresh")) { Icon(Icons.Rounded.Refresh, uiText("Refresh Bot Mode")) }
        } }
        if (state.bots.isEmpty() && !state.loading) item { EmptyState(uiText("No bots available"), uiText("Refresh to check again."), ProfilesIcon, action = uiText("Refresh"), onAction = { vm.botMode.refresh() }) }
        items(state.bots, key = { "bot:${it.profile}" }) { bot ->
            val active = state.activeBot?.profile == bot.profile
            ListItem(headlineContent = { Text(profileDisplayLabel(bot.profile, profileRows, profileLoading), maxLines = 2) },
                supportingContent = if (bot.description.isNotBlank()) ({ Text(bot.description, maxLines = 2, style = MaterialTheme.typography.bodySmall) }) else null,
                leadingContent = { Icon(Icons.Rounded.SmartToy, null) },
                colors = ListItemDefaults.colors(containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { selected = active }.clickable { vm.openBot(bot) }.testTag("bot-row-${bot.profile}"))
        }
        item { Text(uiText("Shared spaces"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        items(state.spaces, key = { "space:${it.id}" }) { space ->
            val active = state.activeBot == null && state.selectedSpace == space.id
            ListItem(headlineContent = { Text(space.name, maxLines = 2) }, leadingContent = { Icon(Icons.Rounded.Forum, null) },
                colors = ListItemDefaults.colors(containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { selected = active }.clickable { vm.openBotSpace(space.id) }.testTag("bot-space-${space.id}"))
        }
        if (state.spaces.isEmpty() && !state.loading) item { Text(uiText("Create a shared space from the overview."), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
        if (state.moreSpaces) item { Text(uiText("Showing your most recent shared spaces."), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
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
        if (state.activeBot != null && chat.sessionId == state.sessionId) ChatScreen(vm, titleOverride = "$botName · ${uiText("Bot Chat")}")
        else Column(Modifier.fillMaxSize()) {
            Header("$botName · ${uiText("Bot Chat")}", menu = LocalOpenNavigationMenu.current)
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(uiText("Opening Bot Chat…"), Modifier.padding(SettingsPagePadding))
            } else {
                Text(if (state.error.isBlank()) uiText("This Bot Chat could not be opened.") else uiText(state.error), Modifier.padding(SettingsPagePadding), color = MaterialTheme.colorScheme.error)
                OutlinedButton({ controller.openBot(bot) }, Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp)) { Text(uiText("Retry")) }
            }
        }
        return
    }
    val shared = state.selectedSpace.isNotBlank()
    val spaceName = state.spaces.firstOrNull { it.id == state.selectedSpace }?.name ?: uiText("Shared space")
    var menu by remember(owner.origin, owner.owner, owner.profile, state.selectedSpace) { mutableStateOf(false) }
    var editing by rememberSaveable(owner.origin, owner.owner, owner.profile, state.selectedSpace) { mutableStateOf(false) }
    var draftName by rememberSaveable(owner.origin, owner.owner, owner.profile, state.selectedSpace) { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var creating by rememberSaveable(owner.origin, owner.owner) { mutableStateOf(false) }
    var deleting by rememberSaveable(owner.origin, owner.owner, state.selectedSpace) { mutableStateOf(false) }
    LaunchedEffect(state.selectedSpace) { if (state.selectedSpace.isNotBlank()) creating = false }
    BackHandler(creating) { creating = false }
    if (deleting && shared) AlertDialog(onDismissRequest = { if (!state.loading) deleting = false }, title = { Text(uiText("Delete shared space?")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(spaceName, style = MaterialTheme.typography.titleMedium); Text(uiText("Its bots stop and it leaves your list. Hermes may keep its history for a while. Your Bot Chats stay.")); if (state.error.isNotBlank()) Text(uiText(state.error), color = MaterialTheme.colorScheme.error) } },
        confirmButton = { TextButton({ controller.deleteSpace() }, enabled = !state.loading && state.canDelete) { Text(uiText(if (state.loading) "Deleting…" else "Delete space"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ deleting = false }, enabled = !state.loading) { Text(uiText("Cancel")) } })
    LaunchedEffect(state.loading, state.error, spaceName) {
        if (submitted && !state.loading && state.error.isBlank() && spaceName == draftName.trim()) { editing = false; submitted = false }
    }
    if (editing && shared) AlertDialog(onDismissRequest = { if (!state.loading) editing = false },
        title = { Text(uiText("Edit shared space")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(draftName, { draftName = it.take(120) }, label = { Text(uiText("Name")) }, singleLine = true, enabled = !state.loading, modifier = Modifier.testTag("shared-space-name"))
            Text(uiText("Bots"), style = MaterialTheme.typography.titleSmall)
            Text(state.members.map { profile -> profileDisplayLabel(profile, profileRows, profileLoading) }.joinToString(", "))
            Text(uiText("Bots can't be changed in an existing space. Create another space with the bots you need."))
            TextButton({ editing = false; controller.home(); creating = true }, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Create another space")) }
            if (!state.canRename) Text(uiText("Renaming isn't available for this space."))
            if (state.error.isNotBlank()) Text(uiText(state.error), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = { submitted = true; controller.renameSpace(draftName) }, enabled = !state.loading && state.canRename && draftName.isNotBlank()) { Text(uiText(if (state.loading) "Saving…" else "Save")) } },
        dismissButton = { TextButton(onClick = { editing = false }, enabled = !state.loading) { Text(uiText("Cancel")) } })
    Column(Modifier.fillMaxSize().imePadding().testTag(if (shared) "bot-mode-shared" else "bot-mode-home")) {
        Header(if (shared) spaceName else if (creating) uiText("New shared space") else uiText("Bot Mode"), back = if (creating) ({ creating = false }) else null, menu = if (creating) null else LocalOpenNavigationMenu.current, actions = {
            if (shared) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, uiText("Shared-space actions")) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(uiText("Edit shared space")) }, onClick = { menu = false; draftName = spaceName; submitted = false; editing = true })
                    DropdownMenuItem({ Text(uiText("Create another space")) }, onClick = { menu = false; controller.home(); creating = true }, enabled = !state.loading)
                    HorizontalDivider()
                    DropdownMenuItem({ Text(uiText(if (state.canDelete) "Delete shared space" else "Deletion unavailable"), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = true }, enabled = !state.loading && state.canDelete)
                }
            } else if (!creating) {
                IconButton(onClick = { controller.refresh() }, enabled = !state.loading) { Icon(Icons.Rounded.Refresh, uiText("Refresh Bot Mode")) }
                IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, uiText("New shared space")) }
            }
        })
        if (state.loading && state.events.isEmpty() && (shared || state.bots.isEmpty())) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error.isNotBlank()) {
            Text(uiText(state.error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = SettingsPagePadding))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(SettingsPagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!shared && !creating) {
                item { Text(uiText("Chat with one bot, or bring several into a shared space."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Text(uiText("Bots"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
                items(state.bots, key = { "overview-bot:${it.profile}" }) { bot ->
                    ActionRow(profileDisplayLabel(bot.profile, profileRows, profileLoading), bot.description, Icons.Rounded.SmartToy) { vm.openBot(bot) }
                }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(uiText("Shared spaces"), style = MaterialTheme.typography.titleLarge)
                    Button({ creating = true }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Rounded.Add, null); Text(uiText("New"), Modifier.padding(start = 8.dp)) }
                } }
                if (state.spaces.isEmpty() && !state.loading) item { EmptyState(uiText("No shared spaces yet"), uiText("Give a team of bots a task in one shared conversation."), Icons.Rounded.Forum, action = uiText("New shared space"), onAction = { creating = true }) }
                items(state.spaces, key = { "overview-space:${it.id}" }) { space ->
                    ActionRow(space.name, uiText("Team conversation"), Icons.Rounded.Forum) { vm.openBotSpace(space.id) }
                }
            } else if (creating) {
                item { Text(uiText("Create a shared space"), style = MaterialTheme.typography.titleLarge); Text(uiText("Choose two to six bots to work together.")) }
                val using = controller.usingBot()
                items(state.bots, key = { "member:${it.profile}" }) { member ->
                    val chosen = member.profile in state.selectedBots || member.profile == using
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = chosen, enabled = !state.loading && member.profile != using, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = { controller.toggleBot(member.profile) }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = chosen, onCheckedChange = null, enabled = !state.loading && member.profile != using)
                        Text(profileDisplayLabel(member.profile, profileRows, profileLoading), Modifier.padding(start = 12.dp))
                    }
                }
                item { Button(onClick = { controller.createSpace() }, enabled = !state.loading && (state.selectedBots + using).size in 2..6) { Text(uiText("Create shared space")) } }
            } else {
                item {
                    Text(uiText(state.status), style = MaterialTheme.typography.bodyMedium)
                    if (state.events.any { it.failed }) OutlinedButton(onClick = { vm.more("Profiles") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Review bot profiles")) }
                    if (state.earlierEventsOmitted) Text(uiText("Showing the latest messages."), style = MaterialTheme.typography.bodySmall)
                    if (state.events.isEmpty() && !state.loading) Text(uiText("Send the bots a task to start the discussion."))
                }
                items(state.events, key = { "event:${it.id}" }) { event ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (event.speaker == "You") Arrangement.End else Arrangement.Start) {
                        Surface(Modifier.widthIn(max = 560.dp).padding(start = if (event.speaker == "You") 24.dp else 0.dp, end = if (event.speaker == "You") 0.dp else 24.dp), shape = MaterialTheme.shapes.large,
                            color = if (event.failed) MaterialTheme.colorScheme.errorContainer else if (event.speaker == "You") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text((if (event.speaker == "You") uiText("You") else event.speaker) + if (event.failed) " · ${uiText("failed")}" else "", style = MaterialTheme.typography.labelLarge)
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
            OutlinedTextField(state.draft, controller::draft, placeholder = { Text(uiText("Message the shared space")) }, shape = RoundedCornerShape(24.dp), maxLines = 6, enabled = !state.sending,
                trailingIcon = { FilledIconButton(onClick = { controller.send() }, enabled = !state.loading && !state.sending && state.draft.isNotBlank(), shape = CircleShape, modifier = Modifier.testTag("bot-shared-send")) { Icon(Icons.Rounded.ArrowUpward, uiText(if (state.sending) "Sending…" else "Send")) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsPagePadding, vertical = 12.dp).testTag("bot-shared-composer"))
        }
    }
}

@Composable private fun NativeBotRoomAction(action: BotModeAction, loading: Boolean, resolve: (BotModeAction, String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (action.kind == "approval") {
                Text("${action.member} ${uiText("needs permission")}", style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(action.description.ifBlank { uiText("Tool approval requested") }) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if ("once" in action.choices) Button(onClick = { resolve(action, "once") }, enabled = !loading && action.description.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Allow once")) }
                    if ("deny" in action.choices) OutlinedButton(onClick = { resolve(action, "deny") }, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(uiText("Deny")) }
                }
            } else {
                Text(action.description)
            }
        }
    }
}
