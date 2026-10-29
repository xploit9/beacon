@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.hermes.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.data.BotModeBot
import dev.hermes.android.data.BotModeSpace
import dev.hermes.android.data.botModeChosen
import org.json.JSONObject

// Bot Mode as a team of teammates, after Grok Bot (docs.x.ai/grok-bot): each bot is a person with its own chat, "New chat" picks two to
// six of them for a group, and a long press on a bot or a group opens its actions. Everything runs over routes Hermes and the Beacon
// plugin already have; the group chat itself is SharedSpaceChat.kt.

/** A bot's name: the profile's display name from Hermes, else the plugin's title, else the profile id. */
internal fun botName(bot: BotModeBot, profiles: List<JSONObject>): String =
    profileDisplayName(bot.profile, profiles).takeIf { it != bot.profile } ?: bot.title.ifBlank { bot.profile }

/** The one line under a bot's name: its role (the profile description Hermes keeps), else the model it runs, else nothing. */
internal fun botRole(bot: BotModeBot, profiles: List<JSONObject>): String =
    bot.description.trim().ifBlank { profiles.firstOrNull { it.string("name") == bot.profile }?.string("model").orEmpty().trim() }

/** A group's second line: its newest message with the sender in front, else its bots' names until the group has been opened on this phone. */
internal fun spacePreview(space: BotModeSpace, name: (String) -> String, you: String): String =
    space.last?.let { "${if (it.speaker == "You") you else name(it.speaker)}: ${it.text}" } ?: space.members.joinToString(", ", transform = name)

/** What New chat suggests as the group's name when the owner types none. */
internal fun newChatName(bots: List<NewChatBot>, selected: Set<String>, using: String): String =
    bots.filter { it.profile in selected || it.profile == using }.joinToString(", ") { it.name }.take(120).trim()

internal data class NewChatBot(val profile: String, val name: String)

/** One entry of a row's long-press menu. */
internal class RowAction(val label: String, val tag: String, val danger: Boolean = false, val divider: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

@Composable private fun RowMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<RowAction>, tag: String) {
    DropdownMenu(expanded, onDismiss, modifier = Modifier.testTag(tag)) {
        actions.forEach { action ->
            if (action.divider) HorizontalDivider()
            DropdownMenuItem({ Text(action.label, color = if (action.danger) MaterialTheme.colorScheme.error else Color.Unspecified) }, onClick = { onDismiss(); action.onClick() }, enabled = action.enabled, modifier = Modifier.testTag(action.tag))
        }
    }
}

/** A bot as a chat-list row: its avatar in the colour it has in the group chat, its name and its role. A long press opens [actions]. */
@Composable internal fun TeammateRow(name: String, profile: String, role: String, isOpen: Boolean, actions: List<RowAction>, tag: String, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ChatListRow(
            id = profile, title = name, preview = role, time = "", selected = isOpen,
            modifier = Modifier.semantics { selected = isOpen }.combinedClickable(onLongClickLabel = uiText("Bot actions"), onLongClick = { menu = true }, onClick = onOpen).testTag(tag),
        )
        RowMenu(menu, { menu = false }, actions, "$tag-menu")
    }
}

/** A group as a chat-list row: its bots' avatars overlapping, its name, the newest line and the time. A long press opens [actions]. */
@Composable internal fun SpaceListRow(name: String, members: List<Pair<String, String>>, preview: String, time: String, isOpen: Boolean, actions: List<RowAction>, tag: String, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box {
        Row(
            Modifier.fillMaxWidth().background(if (isOpen) scheme.secondaryContainer else Color.Transparent).heightIn(min = 72.dp)
                .semantics { selected = isOpen }.combinedClickable(onLongClickLabel = uiText("Group actions"), onLongClick = { menu = true }, onClick = onOpen).testTag(tag)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupAvatars(members)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (time.isNotBlank()) Text(time, Modifier.padding(start = 6.dp), style = nativeMetadataStyle(), color = scheme.onSurfaceVariant, maxLines = 1)
                }
                Text(preview, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        RowMenu(menu, { menu = false }, actions, "$tag-menu")
    }
}

/** Three bots' avatars (title and profile each), overlapping; with more, two avatars and "+N". The width is fixed so the names of all rows line up. */
@Composable internal fun GroupAvatars(members: List<Pair<String, String>>, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val ring = MaterialTheme.colorScheme.surface
    val shown = if (members.size > 3) members.take(2) else members
    Box(modifier.width(size * 2.4f)) {
        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).testTag("group-avatars"), horizontalArrangement = Arrangement.spacedBy(-(size * .3f)), verticalAlignment = Alignment.CenterVertically) {
            shown.forEach { (title, profile) -> ChatAvatar(title, profile, Modifier.border(2.dp, ring, CircleShape), size = size) }
            if (members.size > 3) Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
                Text("+${members.size - 2}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A roster section title with an optional action on its right. */
@Composable internal fun RosterHeader(title: String, action: String = "", tag: String = "", end: @Composable RowScope.() -> Unit = {}, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action.isNotBlank()) TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(action) }
        end()
    }
}

/** The long-press menu of a bot. Delete is offered only when [deletable] (see profileDeletable: never default, the bot in use or the assistant's). */
@Composable internal fun botMenu(deletable: Boolean, open: () -> Unit, group: () -> Unit, role: () -> Unit, settings: () -> Unit, delete: () -> Unit): List<RowAction> = listOfNotNull(
    RowAction(uiText("Open chat"), "bot-action-open", onClick = open),
    RowAction(uiText("Start a group with…"), "bot-action-group", onClick = group),
    RowAction(uiText("Edit role"), "bot-action-role", onClick = role),
    RowAction(uiText("Profile settings"), "bot-action-settings", onClick = settings),
    if (deletable) RowAction(uiText("Delete bot"), "bot-action-delete", danger = true, divider = true, onClick = delete) else null,
)

/** The long-press menu of a group: the same actions, with the same confirmations, as the menu inside it. */
@Composable internal fun spaceMenu(canRename: Boolean, canDelete: Boolean, open: () -> Unit, rename: () -> Unit, members: () -> Unit, delete: () -> Unit): List<RowAction> = listOf(
    RowAction(uiText("Open"), "space-action-open", onClick = open),
    RowAction(uiText("Rename"), "space-action-rename", enabled = canRename, onClick = rename),
    RowAction(uiText("Members"), "space-action-members", onClick = members),
    RowAction(uiText(if (canDelete) "Delete" else "Deletion unavailable"), "space-action-delete", danger = true, divider = true, enabled = canDelete, onClick = delete),
)

@Composable internal fun botActions(vm: BeaconViewModel, bot: BotModeBot, deletable: Boolean, dialog: MutableState<String>): List<RowAction> = botMenu(deletable,
    open = { vm.openBot(bot) }, group = { vm.botMode.startNewChat(bot.profile); dialog.value = "newChat:" }, role = { dialog.value = "role:${bot.profile}" },
    settings = { vm.profileAdmin.openInSettings(bot.profile); vm.more("Profiles") }, delete = { dialog.value = "deleteBot:${bot.profile}" })

@Composable internal fun spaceActions(vm: BeaconViewModel, space: BotModeSpace, canRename: Boolean, canDelete: Boolean, dialog: MutableState<String>): List<RowAction> = spaceMenu(canRename, canDelete,
    open = { vm.openBotSpace(space.id) }, rename = { dialog.value = "renameSpace:${space.id}" }, members = { dialog.value = "members:${space.id}" }, delete = { dialog.value = "deleteSpace:${space.id}" })

/** The New chat sheet: pick two to six bots (the one in use is always in), optionally name the group, and start it. */
@Composable internal fun NewChatSheet(
    bots: List<NewChatBot>, using: String, selected: Set<String>, canName: Boolean, loading: Boolean, error: String,
    onToggle: (String) -> Unit, onStart: (String) -> Unit, onNewBot: () -> Unit, onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val suggested = newChatName(bots, selected, using)
    val ready = botModeChosen(selected, using) != null && !loading
    ModalBottomSheet(onDismissRequest = { if (!loading) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.testTag("new-chat-sheet").imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(uiText("New chat"), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(uiText("Choose two to six bots."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bots.forEach { bot ->
                    val locked = bot.profile == using
                    BotChoiceChip(bot.name, bot.profile, chosen = locked || bot.profile in selected, enabled = !locked && !loading) { onToggle(bot.profile) }
                }
            }
            bots.firstOrNull { it.profile == using }?.let { Text(uiText("{0} is always included.").replace("{0}", it.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (bots.size < 2) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(uiText("Add another bot to start a group."), Modifier.weight(1f))
                TextButton(onClick = onNewBot, modifier = Modifier.heightIn(min = 48.dp).testTag("new-chat-new-bot")) { Text(uiText("New bot")) }
            }
            if (canName) OutlinedTextField(name, { name = it.take(120) }, label = { Text(uiText("Name (optional)")) }, placeholder = { Text(suggested) }, singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("new-chat-name"))
            if (error.isNotBlank()) Text(uiText(error), color = MaterialTheme.colorScheme.error)
            Button(onClick = { onStart(name.trim().ifEmpty { suggested }) }, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("new-chat-start")) { Text(uiText(if (loading) "Starting…" else "Start")) }
        }
    }
}

@Composable private fun BotChoiceChip(name: String, profile: String, chosen: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape, color = if (chosen) scheme.secondaryContainer else scheme.surface, border = BorderStroke(1.dp, if (chosen) Color.Transparent else scheme.outlineVariant),
        modifier = Modifier.heightIn(min = 48.dp).toggleable(value = chosen, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }).testTag("new-chat-bot:$profile"),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatAvatar(name, profile, size = 32.dp)
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            if (chosen) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
        }
    }
}

/** Every dialog and sheet Bot Mode can open from a row, one at a time. [dialog] is "kind:id" (empty for none) and survives rotation. */
@Composable internal fun BotModeDialogs(vm: BeaconViewModel, dialog: MutableState<String>) {
    val controller = vm.botMode
    val state by controller.state.collectAsState()
    val admin by vm.profileAdmin.state.collectAsState()
    val data by vm.data.collectAsState()
    val profileRows = data["navigation-profiles"]?.rows("profiles").orEmpty()
    val kind = dialog.value.substringBefore(':')
    val id = dialog.value.substringAfter(':', "")
    val close = { dialog.value = "" }
    LaunchedEffect(dialog.value) { vm.profileAdmin.clearError() }
    LaunchedEffect(state.selectedSpace) { if (kind == "newChat" && state.selectedSpace.isNotBlank()) close() }
    when (kind) {
        "newChat" -> NewChatSheet(
            bots = state.bots.map { NewChatBot(it.profile, botName(it, profileRows)) }, using = controller.usingBot(), selected = state.selectedBots, canName = state.canRename,
            loading = state.loading, error = state.error, onToggle = controller::toggleBot,
            onStart = { name -> vm.showBotMode(); controller.createSpace(name) },
            onNewBot = { controller.cancelNewChat(); dialog.value = "newBot:" },
            onDismiss = { controller.cancelNewChat(); close() })
        "newBot" -> NewProfileDialog(profileRows.map { it.string("name") to profileDisplayName(it.string("name"), profileRows) }, admin,
            onCreate = { name, from, role -> vm.profileAdmin.create(name, from, role, profileRows.map { it.string("name") }) }, onDismiss = close)
        "role" -> EditRoleDialog(id, state.bots.firstOrNull { it.profile == id }?.description.orEmpty(), admin, onSave = { vm.profileAdmin.setRole(id, it) }, onDismiss = close)
        "deleteBot" -> {
            val deletable = vm.canDeleteProfile(id)
            LaunchedEffect(deletable) { if (!deletable) close() }
            if (deletable) DeleteProfileDialog(id, state.bots.firstOrNull { it.profile == id }?.let { botName(it, profileRows) } ?: profileDisplayName(id, profileRows), bot = true, admin = admin, onDelete = { vm.deleteProfile(id) }, onDismiss = close, groups = state.spaces.count { id in it.members })
        }
        "renameSpace" -> SpaceEditDialog(id, state, state.spaces.firstOrNull { it.id == id }?.members.orEmpty(), profileRows,
            onRename = { controller.renameSpace(it, id) }, onCreateAnother = { controller.home(); controller.startNewChat(); dialog.value = "newChat:" }, onDismiss = close)
        "members" -> SpaceMembersDialog(state.spaces.firstOrNull { it.id == id }, state.bots, profileRows, onDismiss = close)
        "deleteSpace" -> SpaceDeleteDialog(id, state, onDelete = { controller.deleteSpace(id) }, onDismiss = close)
    }
}
