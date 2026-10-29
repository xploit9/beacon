package dev.hermes.android.ui

import dev.hermes.android.core.rows
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.data.BotModeBot
import dev.hermes.android.data.BotModeSpace
import dev.hermes.android.data.NativeBotModeState
import dev.hermes.android.data.botModePollMillis
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject

// Bot Mode's roster (the drawer's list and the home screen share the rows in BotTeammates.kt) and the shared space's menu.

@Composable fun NativeBotModeSidebar(vm: BeaconViewModel, modifier: Modifier = Modifier, beforeRoster: @Composable () -> Unit = {}) {
    val state by vm.botMode.state.collectAsState()
    val ui by vm.ui.collectAsState()
    val data by vm.data.collectAsState()
    val profileRows = data["navigation-profiles"]?.rows("profiles").orEmpty()
    val dialog = rememberSaveable { mutableStateOf("") }
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val you = uiText("You")
    LaunchedEffect(ui.origin, ui.owner, ui.connected) { if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    LaunchedEffect(vm.botMode) { vm.botMode.refresh(silent = true) }
    BotModeDialogs(vm, dialog)
    LazyColumn(modifier.background(MaterialTheme.colorScheme.surfaceContainer).testTag("bot-mode-sidebar"), contentPadding = PaddingValues(bottom = 16.dp)) {
        item("destinations") { beforeRoster() }
        if (state.loading && state.bots.isEmpty() && state.spaces.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.error.isNotBlank() && !(ui.tab == "More" && ui.morePage == "Bots")) item {
            Column(Modifier.padding(16.dp)) { Text(uiText(state.error), color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = { vm.showBotMode() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Review Bot Mode")) } }
        }
        item {
            RosterHeader(uiText("Bots"), uiText("New bot"), "bot-mode-sidebar-new-bot", end = {
                IconButton(onClick = { vm.botMode.refresh() }, enabled = !state.loading, modifier = Modifier.testTag("bot-mode-refresh")) { Icon(Icons.Rounded.Refresh, uiText("Refresh Bot Mode")) }
            }) { dialog.value = "newBot:" }
        }
        if (state.bots.isEmpty() && !state.loading) item { EmptyState(uiText("No bots available"), uiText("Refresh to check again."), ProfilesIcon, action = uiText("Refresh"), onAction = { vm.botMode.refresh() }) }
        items(state.bots, key = { "bot:${it.profile}" }) { bot ->
            TeammateRow(botName(bot, profileRows), bot.profile, botRole(bot, profileRows), state.activeBot?.profile == bot.profile,
                botActions(vm, bot, vm.canDeleteProfile(bot.profile), dialog), "bot-row-${bot.profile}") { vm.openBot(bot) }
        }
        item { RosterHeader(uiText("Shared spaces"), uiText("New chat"), "bot-mode-sidebar-new-chat") { vm.botMode.startNewChat(); dialog.value = "newChat:" } }
        items(state.spaces, key = { "space:${it.id}" }) { space ->
            GroupRow(vm, space, profileRows, state, you, is24Hour, dialog, "bot-space-${space.id}", isOpen = state.activeBot == null && state.selectedSpace == space.id)
        }
        if (state.spaces.isEmpty() && !state.loading) item { Text(uiText("No shared spaces yet"), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
        if (state.moreSpaces) item { Text(uiText("Showing your most recent shared spaces."), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun GroupRow(vm: BeaconViewModel, space: BotModeSpace, profileRows: List<JSONObject>, state: NativeBotModeState, you: String, is24Hour: Boolean, dialog: MutableState<String>, tag: String, isOpen: Boolean) {
    val name = { profile: String -> profileDisplayName(profile, profileRows) }
    SpaceListRow(space.name, space.members.map { name(it) to it }, spacePreview(space, name, you), chatListTime(space.updatedAt * 1000, is24Hour = is24Hour), isOpen,
        spaceActions(vm, space, state.canRename, state.canDelete, dialog), tag) { vm.openBotSpace(space.id) }
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
            while (state.selectedSpace.isNotBlank() && state.activeBot == null) { delay(botModePollMillis(controller.state.value.working)); controller.refresh(silent = true) }
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
    val dialog = rememberSaveable(owner.origin, owner.owner) { mutableStateOf("") }
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val you = uiText("You")
    val newChatLabel = uiText("New chat")
    val openNewChat = { controller.startNewChat(); dialog.value = "newChat:" }
    BotModeDialogs(vm, dialog)
    val headerActions: @Composable RowScope.() -> Unit = {
        if (shared) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, uiText("Shared-space actions")) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(uiText("Edit shared space")) }, onClick = { menu = false; dialog.value = "renameSpace:${state.selectedSpace}" })
                DropdownMenuItem({ Text(uiText("Create another space")) }, onClick = { menu = false; controller.home(); openNewChat() }, enabled = !state.loading)
                HorizontalDivider()
                DropdownMenuItem({ Text(uiText(if (state.canDelete) "Delete shared space" else "Deletion unavailable"), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; dialog.value = "deleteSpace:${state.selectedSpace}" }, enabled = !state.loading && state.canDelete)
            }
        } else {
            IconButton(onClick = { controller.refresh() }, enabled = !state.loading) { Icon(Icons.Rounded.Refresh, uiText("Refresh Bot Mode")) }
            FilledTonalButton(onClick = openNewChat, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = newChatLabel }.testTag("bot-mode-new-chat")) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(uiText("New"))
            }
        }
    }
    Column(Modifier.fillMaxSize().imePadding().testTag(if (shared) "bot-mode-shared" else "bot-mode-home")) {
        if (shared) SharedSpaceHeader(spaceName, state, profileRows, LocalOpenNavigationMenu.current, headerActions)
        else Header(uiText("Bot Mode"), menu = LocalOpenNavigationMenu.current, actions = headerActions)
        if (state.loading && state.events.isEmpty() && (shared || state.bots.isEmpty())) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error.isNotBlank()) {
            Text(uiText(state.error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = SettingsPagePadding))
        }
        if (shared) SharedSpaceChat(state, profileRows, controller::draft, controller::send, controller::action, { vm.more("Profiles") }) { text, eventId ->
            Markdown(text, vm, origin = null, documentKey = "room:${state.selectedSpace}:$eventId")
        }
        else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { RosterHeader(uiText("Bots"), uiText("New bot"), "bot-mode-new-bot") { dialog.value = "newBot:" } }
            if (state.bots.isEmpty() && !state.loading) item { EmptyState(uiText("No bots available"), uiText("Refresh to check again."), ProfilesIcon, action = uiText("Refresh"), onAction = { controller.refresh() }) }
            items(state.bots, key = { "teammate:${it.profile}" }) { member ->
                TeammateRow(botName(member, profileRows), member.profile, botRole(member, profileRows), false,
                    botActions(vm, member, vm.canDeleteProfile(member.profile), dialog), "teammate-row:${member.profile}") { vm.openBot(member) }
            }
            item { RosterHeader(uiText("Shared spaces")) }
            if (state.spaces.isEmpty() && !state.loading) item { EmptyState(uiText("No shared spaces yet"), uiText("Give a team of bots a task in one shared conversation."), Icons.Rounded.Forum, action = newChatLabel, onAction = openNewChat) }
            items(state.spaces, key = { "overview-space:${it.id}" }) { space ->
                GroupRow(vm, space, profileRows, state, you, is24Hour, dialog, "space-row:${space.id}", isOpen = false)
            }
            if (state.moreSpaces) item { Text(uiText("Showing your most recent shared spaces."), Modifier.padding(horizontal = SettingsPagePadding), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Edit shared space, from the space's own menu or a long press on its row: its name, its bots (fixed once made) and a way to start another. */
@Composable internal fun SpaceEditDialog(id: String, state: NativeBotModeState, members: List<String>, profiles: List<JSONObject>, onRename: (String) -> Unit, onCreateAnother: () -> Unit, onDismiss: () -> Unit) {
    val current = state.spaces.firstOrNull { it.id == id }?.name.orEmpty()
    var draftName by rememberSaveable(id) { mutableStateOf(current) }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading, state.error, current) { if (submitted && !state.loading && state.error.isBlank() && current == draftName.trim()) { submitted = false; onDismiss() } }
    AlertDialog(onDismissRequest = { if (!state.loading) onDismiss() },
        title = { Text(uiText("Edit shared space")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(draftName, { draftName = it.take(120) }, label = { Text(uiText("Name")) }, singleLine = true, enabled = !state.loading, modifier = Modifier.testTag("shared-space-name"))
            Text(uiText("Bots"), style = MaterialTheme.typography.titleSmall)
            Text(members.joinToString(", ") { profileDisplayName(it, profiles) })
            Text(uiText("Bots can't be changed in an existing space. Create another space with the bots you need."))
            TextButton(onCreateAnother, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Create another space")) }
            if (!state.canRename) Text(uiText("Renaming isn't available for this space."))
            if (state.error.isNotBlank()) Text(uiText(state.error), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = { submitted = true; onRename(draftName) }, enabled = !state.loading && state.canRename && draftName.isNotBlank()) { Text(uiText(if (state.loading) "Saving…" else "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.loading) { Text(uiText("Cancel")) } })
}

/** Members, from a long press on a group: who is in it, with each bot's role. */
@Composable internal fun SpaceMembersDialog(space: BotModeSpace?, bots: List<BotModeBot>, profiles: List<JSONObject>, onDismiss: () -> Unit) {
    if (space == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(space.name) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            space.members.forEach { profile ->
                val name = profileDisplayName(profile, profiles)
                val role = bots.firstOrNull { it.profile == profile }?.let { botRole(it, profiles) }.orEmpty()
                Row(Modifier.testTag("space-member:$profile"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChatAvatar(name, profile, size = 40.dp)
                    Column { Text(name, style = MaterialTheme.typography.titleMedium); if (role.isNotBlank()) Text(role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Text(uiText("Bots can't be changed in an existing space. Create another space with the bots you need."), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Close")) } })
}

/** Delete shared space: the one confirmation for the space's menu and for a long press on its row. */
@Composable internal fun SpaceDeleteDialog(id: String, state: NativeBotModeState, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val space = state.spaces.firstOrNull { it.id == id }
    // The space left the list: it is deleted.
    LaunchedEffect(space == null) { if (space == null) onDismiss() }
    AlertDialog(onDismissRequest = { if (!state.loading) onDismiss() }, title = { Text(uiText("Delete shared space?")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(space?.name.orEmpty(), style = MaterialTheme.typography.titleMedium); Text(uiText("Its bots stop and it leaves your list. Hermes may keep its history for a while. Your Bot Chats stay.")); if (state.error.isNotBlank()) Text(uiText(state.error), color = MaterialTheme.colorScheme.error) } },
        confirmButton = { TextButton({ onDelete() }, enabled = !state.loading && state.canDelete) { Text(uiText(if (state.loading) "Deleting…" else "Delete space"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ onDismiss() }, enabled = !state.loading) { Text(uiText("Cancel")) } })
}
