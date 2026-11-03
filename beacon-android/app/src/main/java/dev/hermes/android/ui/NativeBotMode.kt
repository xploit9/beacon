package dev.hermes.android.ui

import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.data.BotModeBot
import dev.hermes.android.data.BotModeSpace
import dev.hermes.android.data.NativeBotModeState
import dev.hermes.android.data.botModePollMillis
import dev.hermes.android.tasks.scheduleNextRun
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject

// Bot Mode's roster (the drawer's list and the home screen share the rows in BotTeammates.kt) and the shared space's menu.

/**
 * The dialog slot of the Bot Mode drawer: New bot and New space sit in SidebarNavigation, above the roster, but open the roster's own
 * dialogs. Null when SidebarNavigation is not inside this drawer.
 */
internal val LocalBotModeDialog = compositionLocalOf<MutableState<String>?> { null }

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
    val statusOf = rememberBotStatus(state)
    val starters = suggestedRolesOffered(suggestedRoleCatalog(), state.bots.map { it.profile } + profileRows.map { it.string("name") }, state.bots.size)
    CompositionLocalProvider(LocalBotModeDialog provides dialog) {
        LazyColumn(modifier.background(MaterialTheme.colorScheme.surfaceContainer).testTag("bot-mode-sidebar"), contentPadding = PaddingValues(bottom = 16.dp)) {
            item("destinations") { beforeRoster() }
            if (state.loading && state.bots.isEmpty() && state.spaces.isEmpty()) item { Glass(Modifier.padding(12.dp)) { SettingsSkeletonRows(2) } }
            if (state.error.isNotBlank() && !(ui.tab == "More" && ui.morePage == "Bots")) item {
                Glass(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { SettingsErrorRow(uiText(state.error), { vm.showBotMode() }, uiText("Review Bot Mode"), retryTestTag = "bot-mode-review") }
            }
            item {
                RosterHeader(uiText("Bots"), end = {
                    IconButton(onClick = { vm.botMode.refresh() }, enabled = !state.loading, modifier = Modifier.testTag("bot-mode-refresh")) { Icon(Icons.Rounded.Refresh, uiText("Refresh Bot Mode")) }
                })
            }
            if (state.bots.isEmpty() && !state.loading) item { EmptyState(uiText("No bots available"), uiText("Refresh to check again."), ProfilesIcon, action = uiText("Refresh"), onAction = { vm.botMode.refresh() }) }
            items(state.bots, key = { "bot:${it.profile}" }) { bot ->
                TeammateRow(botName(bot, profileRows), bot.profile, botRole(bot, profileRows), state.activeBot?.profile == bot.profile,
                    botActions(vm, bot, vm.canDeleteProfile(bot.profile), dialog), "bot-row-${bot.profile}",
                    status = statusOf(bot.profile)) { vm.openBot(bot) }
            }
            // Not before the first read, and not blinking out while a refresh runs over bots already shown.
            if (starters.isNotEmpty() && (state.bots.isNotEmpty() || !state.loading)) item("suggested-roles") { SuggestedRolesRow(starters) { dialog.value = "newBot:$it" } }
            item { RosterHeader(uiText("Shared spaces")) }
            items(state.spaces, key = { "space:${it.id}" }) { space ->
                GroupRow(vm, space, profileRows, state, you, is24Hour, dialog, "bot-space-${space.id}", isOpen = state.activeBot == null && state.selectedSpace == space.id)
            }
            if (state.spaces.isEmpty() && !state.loading) item { Text(uiText("No shared spaces yet"), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            if (state.moreSpaces) item { Text(uiText("Recent spaces"), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun GroupRow(vm: BeaconViewModel, space: BotModeSpace, profileRows: List<JSONObject>, state: NativeBotModeState, you: String, is24Hour: Boolean, dialog: MutableState<String>, tag: String, isOpen: Boolean) {
    val name = { profile: String -> profileDisplayName(profile, profileRows) }
    val botCount = uiText("{0} bots")
    SpaceListRow(space.name, space.members.map { name(it) to it }, spacePreview(space, name, you, botCount), chatListTime(space.updatedAt * 1000, is24Hour = is24Hour), isOpen,
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
                val opening = uiText("Opening Bot Chat…")
                Glass(Modifier.padding(SettingsPagePadding).semantics { contentDescription = opening }) { SettingsSkeletonRows(2) }
            } else {
                Glass(Modifier.padding(SettingsPagePadding)) { SettingsErrorRow(if (state.error.isBlank()) uiText("This Bot Chat could not be opened.") else uiText(state.error), { controller.openBot(bot) }, uiText("Retry"), retryTestTag = "bot-chat-retry") }
            }
        }
        return
    }
    val shared = state.selectedSpace.isNotBlank()
    val spaceName = state.spaces.firstOrNull { it.id == state.selectedSpace }?.name ?: uiText("Shared space")
    var menu by remember(owner.origin, owner.owner, owner.profile, state.selectedSpace) { mutableStateOf(false) }
    val dialog = rememberSaveable(owner.origin, owner.owner) { mutableStateOf("") }
    val openNewChat = { controller.startNewChat(); dialog.value = "newChat:" }
    // Up next reads the schedules Hermes keeps for this profile, the Schedules screen's own list; they are asked for only when not loaded yet.
    LaunchedEffect(owner.origin, owner.owner, owner.profile, owner.connected, shared) { if (owner.connected && !shared) vm.ensureLoaded("crons", "/api/crons") }
    BotModeDialogs(vm, dialog)
    // New bot and New space are the two buttons at the top of the home screen, not in its header.
    val headerActions: @Composable RowScope.() -> Unit = {
        if (shared) Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.MoreVert, uiText("Shared-space actions")) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(uiText("Edit shared space")) }, onClick = { menu = false; dialog.value = "renameSpace:${state.selectedSpace}" })
                DropdownMenuItem({ Text(uiText("Create another space")) }, onClick = { menu = false; controller.home(); openNewChat() }, enabled = !state.loading)
                HorizontalDivider()
                DropdownMenuItem({ Text(uiText(if (state.canDelete) "Delete shared space" else "Deletion unavailable"), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; dialog.value = "deleteSpace:${state.selectedSpace}" }, enabled = !state.loading && state.canDelete)
            }
        } else IconButton(onClick = { controller.refresh() }, enabled = !state.loading, modifier = Modifier.size(48.dp).testTag("bot-mode-home-refresh")) { Icon(Icons.Rounded.Refresh, uiText("Refresh Bot Mode")) }
    }
    Column(Modifier.fillMaxSize().imePadding().testTag(if (shared) "bot-mode-shared" else "bot-mode-home")) {
        if (shared) SharedSpaceHeader(spaceName, state, profileRows, LocalOpenNavigationMenu.current, headerActions)
        else Header(uiText("Bot Mode"), menu = LocalOpenNavigationMenu.current, actions = headerActions)
        // Roster or room already shown: a thin bar while it refreshes; the skeleton is for the first read only.
        if (state.loading && (shared && state.events.isEmpty() || !shared && state.bots.isNotEmpty())) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        if (state.error.isNotBlank()) {
            Glass(Modifier.padding(horizontal = SettingsPagePadding, vertical = 4.dp)) { SettingsErrorRow(uiText(state.error), { controller.refresh() }, uiText("Retry"), retryTestTag = "bot-mode-retry") }
        }
        if (shared) SharedSpaceChat(state, profileRows, controller::draft, controller::send, controller::action, { vm.more("Profiles") }) { text, eventId ->
            Markdown(text, vm, origin = null, documentKey = "room:${state.selectedSpace}:$eventId")
        }
        else BotModeHome(vm, state, profileRows, data["crons"], owner.profile, owner.botModeSwitching, dialog, Modifier.weight(1f))
    }
}

/** The width from which the home screen sets its two lists side by side; narrower, it is one column, kept to a readable width. */
private val HomeTwoColumnsFrom = 720.dp

/**
 * The Bot Mode home screen: New bot and New space on top, then the team (bots, each with its role and a real status, and suggested roles
 * while the team is small) and what it does (the next scheduled runs, the shared spaces). Wide, the team is the left list and the rest the
 * right one; narrow, they follow each other. [crons] is Schedules' own list for this profile, [profile] the one it was read for.
 */
@Composable private fun BotModeHome(
    vm: BeaconViewModel, state: NativeBotModeState, profileRows: List<JSONObject>, crons: JSONObject?, profile: String, switching: Boolean,
    dialog: MutableState<String>, modifier: Modifier,
) {
    val controller = vm.botMode
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val you = uiText("You")
    val statusOf = rememberBotStatus(state)
    val starters = suggestedRolesOffered(suggestedRoleCatalog(), state.bots.map { it.profile } + profileRows.map { it.string("name") }, state.bots.size)
    val untitled = uiText("Untitled schedule")
    val upNext = remember(crons, profile, untitled) {
        if (crons == null || crons.has("_error") || crons.optBoolean("cron_unavailable")) emptyList() else upNextRuns(crons.rows("jobs"), profile, untitled)
    }
    val whenWords = rememberScheduleWhen()
    val phoneZone = remember { java.time.ZoneId.systemDefault() }
    // Read once per fresh list, as the Schedules screen does, so a run's relative time is as old as the data under it.
    val now = remember(crons) { java.time.Instant.now() }
    val team: LazyListScope.() -> Unit = {
        if (state.loading && state.bots.isEmpty()) item("loading") { Glass(Modifier.padding(horizontal = SettingsPagePadding, vertical = 8.dp)) { SettingsSkeletonRows(3) } }
        item("bots") { RosterHeader(uiText("Bots")) }
        if (state.bots.isEmpty() && !state.loading) item("no-bots") { EmptyState(uiText("No bots available"), uiText("Refresh to check again."), ProfilesIcon, action = uiText("Refresh"), onAction = { controller.refresh() }) }
        items(state.bots, key = { "teammate:${it.profile}" }) { member ->
            val name = botName(member, profileRows)
            TeammateRow(name, member.profile, botRole(member, profileRows), false, botActions(vm, member, vm.canDeleteProfile(member.profile), dialog), "teammate-row:${member.profile}",
                status = statusOf(member.profile), quick = botGroupQuick(vm, member, name, "teammate-space:${member.profile}", dialog)) { vm.openBot(member) }
        }
        // Not before the first read, and not blinking out while a refresh runs over bots already shown.
        // The same compact chips as the drawer's, so the team does not push Up next and the shared spaces off the screen.
        if (starters.isNotEmpty() && (state.bots.isNotEmpty() || !state.loading)) item("suggested-roles") { SuggestedRolesRow(starters) { dialog.value = "newBot:$it" } }
    }
    val activity: LazyListScope.() -> Unit = {
        if (upNext.isNotEmpty()) item("up-next") {
            Column(Modifier.fillMaxWidth().testTag("bot-mode-up-next")) {
                RosterHeader(uiText("Up next"))
                upNext.forEach { run ->
                    val bot = state.bots.firstOrNull { it.profile == run.profile }
                    UpNextRow(run.title, bot?.let { botName(it, profileRows) } ?: profileDisplayName(run.profile, profileRows), run.profile,
                        scheduleNextRun(run.nextRunAt, "", whenWords, now, phoneZone, is24Hour), "up-next:${run.id}") { vm.navigate("Cron") }
                }
            }
        }
        item("spaces") { RosterHeader(uiText("Shared spaces")) }
        if (state.spaces.isEmpty() && !state.loading) item("no-spaces") { EmptyState(uiText("No shared spaces yet"), uiText("Give a team of bots a task in one shared conversation."), Icons.Rounded.Forum) }
        items(state.spaces, key = { "overview-space:${it.id}" }) { space ->
            GroupRow(vm, space, profileRows, state, you, is24Hour, dialog, "space-row:${space.id}", isOpen = false)
        }
        if (state.moreSpaces) item("more-spaces") { Text(uiText("Recent spaces"), Modifier.padding(horizontal = SettingsPagePadding), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    val actions: @Composable (Modifier) -> Unit = { at ->
        BotModeActions({ dialog.value = "newBot:" }, { controller.startNewChat(); dialog.value = "newChat:" }, "bot-mode-new-bot", "bot-mode-new-chat", at.padding(horizontal = 12.dp, vertical = 4.dp), enabled = !switching)
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= HomeTwoColumnsFrom && !isLargeText(LocalDensity.current.fontScale)) Column(Modifier.widthIn(max = 1120.dp).fillMaxSize().align(Alignment.TopCenter)) {
            actions(Modifier.widthIn(max = 520.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("bot-mode-home-team"), contentPadding = PaddingValues(bottom = 24.dp), content = team)
                LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("bot-mode-home-activity"), contentPadding = PaddingValues(bottom = 24.dp), content = activity)
            }
        } else LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().align(Alignment.TopCenter).testTag("bot-mode-home-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item("actions") { actions(Modifier) }
            team()
            activity()
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
            Text(uiText("Bots can't be changed in a space. Create another space."))
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
            Text(uiText("Bots can't be changed in a space. Create another space."), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Close")) } })
}

/** Delete shared space: the one confirmation for the space's menu and for a long press on its row. */
@Composable internal fun SpaceDeleteDialog(id: String, state: NativeBotModeState, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val space = state.spaces.firstOrNull { it.id == id }
    // The space left the list: it is deleted.
    LaunchedEffect(space == null) { if (space == null) onDismiss() }
    AlertDialog(onDismissRequest = { if (!state.loading) onDismiss() }, title = { Text(uiText("Delete shared space?")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(space?.name.orEmpty(), style = MaterialTheme.typography.titleMedium); Text(uiText("Delete removes the space from your list and stops its bots. Hermes may keep its history for a while. Your Bot Chats stay.")); if (state.error.isNotBlank()) Text(uiText(state.error), color = MaterialTheme.colorScheme.error) } },
        confirmButton = { TextButton({ onDelete() }, enabled = !state.loading && state.canDelete) { Text(uiText(if (state.loading) "Deleting…" else "Delete space"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ onDismiss() }, enabled = !state.loading) { Text(uiText("Cancel")) } })
}
