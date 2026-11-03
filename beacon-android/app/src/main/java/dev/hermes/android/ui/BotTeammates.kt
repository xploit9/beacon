@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.hermes.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.data.BotModeBot
import dev.hermes.android.data.BotModeMember
import dev.hermes.android.data.BotModeSpace
import dev.hermes.android.data.NativeBotModeState
import dev.hermes.android.data.botModeChosen
import dev.hermes.android.tasks.scheduleEnded
import dev.hermes.android.tasks.scheduleIsCallback
import dev.hermes.android.tasks.scheduleNameOnly
import dev.hermes.android.tasks.schedulePaused
import dev.hermes.android.tasks.scheduleTitle
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

/**
 * A group's second line: its newest message with the sender in front, else its bots' names until the group has been opened on this phone.
 * A group named after its bots would repeat itself, so the line counts them instead ([bots] is the translated "{0} bots", blank for none).
 */
internal fun spacePreview(space: BotModeSpace, name: (String) -> String, you: String, bots: String = ""): String {
    space.last?.let { return "${if (it.speaker == "You") you else name(it.speaker)}: ${it.text}" }
    val members = space.members.joinToString(", ", transform = name)
    return if (members.equals(space.name.trim(), ignoreCase = true)) bots.replace("{0}", "${space.members.size}") else members
}

/** What New chat suggests as the group's name when the owner types none. */
internal fun newChatName(bots: List<NewChatBot>, selected: Set<String>, using: String): String =
    bots.filter { it.profile in selected || it.profile == using }.joinToString(", ") { it.name }.take(120).trim()

internal data class NewChatBot(val profile: String, val name: String)

/** The bots Hermes reports as answering in the open space, by profile ([typing] names members; a member id may be the profile itself). */
internal fun botsReplying(typing: List<String>, roster: List<BotModeMember>): Set<String> =
    typing.map { id -> roster.firstOrNull { it.id == id }?.profile ?: roster.firstOrNull { it.profile == id }?.profile ?: id }.toSet()

/** How many of [spaces] each bot is in. */
internal fun botSpaceCounts(spaces: List<BotModeSpace>): Map<String, Int> = spaces.flatMap { it.members.distinct() }.groupingBy { it }.eachCount()

/** A bot row's status: typing now, else the shared spaces it is in ([spacesText] is the translated "Spaces: {0}"), else nothing. */
internal fun botStatus(replying: Boolean, spaces: Int, typingText: String, spacesText: String): String =
    if (replying) typingText else if (spaces > 0) spacesText.replace("{0}", "$spaces") else ""

/**
 * What each bot row says on its right, for the drawer and the home screen alike: typing now (in the open space), else the shared spaces
 * it is in. The spaces list is cut at the recent ones, so with more behind it a count would be too low and is left out.
 */
@Composable internal fun rememberBotStatus(state: NativeBotModeState): (String) -> String {
    val replying = botsReplying(state.typing, state.roster)
    val inSpaces = remember(state.spaces, state.moreSpaces) { if (state.moreSpaces) emptyMap() else botSpaceCounts(state.spaces) }
    val typingText = uiText("Typing…")
    val spacesText = uiText("Spaces: {0}")
    return { profile -> botStatus(profile in replying, inSpaces[profile] ?: 0, typingText, spacesText) }
}

/** One scheduled run for "Up next": whose it is ([profile]), what it is called and when it runs next ([nextRunAt], Hermes' own timestamp). */
internal class UpNextRun(val id: String, val title: String, val profile: String, val nextRunAt: String, val at: java.time.Instant)

/** How many runs the home screen lists; the full list is on the Schedules screen. */
internal const val UP_NEXT_LIMIT = 3

/**
 * The soonest runs among [jobs] (Hermes' schedules as the Schedules screen reads them): running ones only, so nothing paused, finished or
 * without a next time, no callback (a one-time call-back the assistant set up, not a bot's routine) and none of the jobs the Beacon plugin
 * keeps in shape itself (the check-ups, watching email, the nightly memory), which are read from their markers and never from their names.
 * A job that names its own `profile` belongs to that bot; any other belongs to [defaultProfile], the profile the list was read for.
 */
internal fun upNextRuns(jobs: List<JSONObject>, defaultProfile: String, untitled: String, limit: Int = UP_NEXT_LIMIT): List<UpNextRun> =
    jobs.mapNotNull { job ->
        if (scheduleIsCallback(job) || scheduleNameOnly(job) || schedulePaused(job) || scheduleEnded(job)) return@mapNotNull null
        val next = job.string("next_run_at")
        val at = runCatching { java.time.OffsetDateTime.parse(next).toInstant() }.getOrNull() ?: return@mapNotNull null
        UpNextRun(job.string("id"), scheduleTitle(job, untitled, ""), job.string("profile").ifBlank { defaultProfile }, next, at)
    }.sortedBy { it.at }.take(limit)

/** A one-tap start for a new bot: a profile [id] (lowercase, as Hermes names profiles), its [name] and the one-line [role] it gets. */
internal class SuggestedRole(val id: String, val name: String, val role: String)

/** Below this many bots the drawer suggests roles to start from. */
internal const val SUGGESTED_ROLES_BELOW = 4

/** Four generic roles, one job each; the ids stay as written, the name and the role are in the owner's language. */
@Composable internal fun suggestedRoleCatalog(): List<SuggestedRole> = listOf(
    SuggestedRole("researcher", uiText("Researcher"), uiText("Finds sources and sums them up.")),
    SuggestedRole("planner", uiText("Planner"), uiText("Turns goals into clear next steps.")),
    SuggestedRole("writer", uiText("Writer"), uiText("Drafts and polishes your text.")),
    SuggestedRole("inbox-helper", uiText("Inbox helper"), uiText("Sorts messages and drafts replies.")),
)

/** The roles worth offering: only while the team is small, and never one whose id is already a profile ([taken]). */
internal fun suggestedRolesOffered(catalog: List<SuggestedRole>, taken: Collection<String>, bots: Int): List<SuggestedRole> =
    if (bots >= SUGGESTED_ROLES_BELOW) emptyList() else catalog.filter { it.id !in taken }

/** "Suggested roles": a chip per role, in the drawer and on the home screen alike; a tap opens New bot with that name and role filled in. */
@Composable internal fun SuggestedRolesRow(roles: List<SuggestedRole>, onPick: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("bot-mode-suggested-roles")) {
        RosterHeader(uiText("Suggested roles"))
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            roles.forEach { starter ->
                Surface(
                    onClick = { onPick(starter.id) }, shape = CircleShape, color = scheme.surface, border = BorderStroke(1.dp, scheme.outlineVariant),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.Button }.testTag("bot-mode-suggested-role:${starter.id}"),
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Text(starter.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * A scheduled run in "Up next": the bot's avatar and the schedule's name on the full width, then the bot's name with when it runs next
 * ([whenText], in the phone's clock) beside it, so a long name is not cut by the time. A tap opens Schedules ([onOpen]).
 */
@Composable internal fun UpNextRow(title: String, bot: String, profile: String, whenText: String, tag: String, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClickLabel = uiText("Schedules"), role = Role.Button, onClick = onOpen).semantics(mergeDescendants = true) {}
        .padding(horizontal = 12.dp, vertical = 8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        ChatAvatar(bot, profile, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium).rowStart(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bot, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.rowStart(), color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(whenText, Modifier.padding(start = 8.dp), style = nativeMetadataStyle(), color = scheme.primary, maxLines = 1)
            }
        }
    }
}

/**
 * New bot (filled) and New space (tonal) side by side, one size and shape; at large text they stack full width so neither wraps.
 * The drawer's top buttons look the same, so the two places read as one pattern.
 */
@Composable internal fun BotModeActions(onNewBot: () -> Unit, onNewSpace: () -> Unit, newBotTag: String, newSpaceTag: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(14.dp)
    val newBot: @Composable (Modifier) -> Unit = { at ->
        Button(onClick = onNewBot, modifier = at.heightIn(min = 48.dp).testTag(newBotTag), shape = shape, enabled = enabled) {
            Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(uiText("New bot"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val newSpace: @Composable (Modifier) -> Unit = { at ->
        FilledTonalButton(onClick = onNewSpace, modifier = at.heightIn(min = 48.dp).testTag(newSpaceTag), shape = shape, enabled = enabled) {
            Icon(Icons.Rounded.Groups, null); Spacer(Modifier.width(8.dp)); Text(uiText("New space"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (isLargeText(LocalDensity.current.fontScale)) Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { newBot(Modifier.fillMaxWidth()); newSpace(Modifier.fillMaxWidth()) }
    else Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { newBot(Modifier.weight(1f)); newSpace(Modifier.weight(1f)) }
}

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

/** An icon button at the end of a row, with the [label] a screen reader says. */
internal class RowQuick(val icon: ImageVector, val label: String, val tag: String, val onClick: () -> Unit)

/**
 * A bot as a chat-list row: its avatar in the colour it has in the group chat, its name and its role, and on the right its [status] (blank for none).
 * A tap opens its chat; a long press opens [actions]; [quick] adds one more button at the end of the row.
 */
@Composable internal fun TeammateRow(name: String, profile: String, role: String, isOpen: Boolean, actions: List<RowAction>, tag: String, status: String = "", quick: RowQuick? = null, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChatListRow(
                id = profile, title = name, preview = role, time = status, selected = isOpen,
                modifier = Modifier.weight(1f).semantics { selected = isOpen }.combinedClickable(onLongClickLabel = uiText("Bot actions"), onLongClick = { menu = true }, onClick = onOpen).testTag(tag),
            )
            quick?.let { IconButton(onClick = it.onClick, modifier = Modifier.size(48.dp).testTag(it.tag)) { Icon(it.icon, it.label) } }
        }
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
            GroupAvatars(members, size = GroupRowAvatarSize, slots = 2)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium).rowStart(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (time.isNotBlank()) Text(time, Modifier.padding(start = 8.dp), style = nativeMetadataStyle(), color = scheme.onSurfaceVariant, maxLines = 1)
                }
                Text(preview, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium.rowStart(), color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        RowMenu(menu, { menu = false }, actions, "$tag-menu")
    }
}

/** How far an avatar reaches over the one before it, as a share of its size. */
private const val GROUP_AVATAR_OVERLAP = .3f

/** The width of [slots] overlapping avatars of size 1: the first whole, each next one adds what is not covered. Two are 1.7, three 2.4. */
internal fun groupAvatarsWidth(slots: Int): Float = 1f + (1f - GROUP_AVATAR_OVERLAP) * (slots - 1)

/** How many of [count] members get an avatar of their own when [slots] places are offered: all of them, or one less than the places when the last place is the "+N". */
internal fun groupAvatarsShown(count: Int, slots: Int): Int = if (count > slots) slots - 1 else count

/** The size of a group row's avatars: two of them take exactly the 48 dp a bot row's avatar takes, so the names of both line up. */
private val GroupRowAvatarSize = 48.dp / groupAvatarsWidth(2)

/**
 * A group's bots' avatars (title and profile each), overlapping, in [slots] places: one avatar each, or with more bots than places the first
 * ones and "+N" in the last. The width is fixed, so the names of all rows line up.
 */
@Composable internal fun GroupAvatars(members: List<Pair<String, String>>, modifier: Modifier = Modifier, size: Dp = 32.dp, slots: Int = 3) {
    val ring = MaterialTheme.colorScheme.surface
    val shown = members.take(groupAvatarsShown(members.size, slots))
    Box(modifier.width(size * groupAvatarsWidth(slots))) {
        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).testTag("group-avatars"), horizontalArrangement = Arrangement.spacedBy(-(size * GROUP_AVATAR_OVERLAP)), verticalAlignment = Alignment.CenterVertically) {
            shown.forEach { (title, profile) -> ChatAvatar(title, profile, Modifier.border(2.dp, ring, CircleShape), size = size) }
            if (members.size > shown.size) Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
                Text("+${members.size - shown.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A roster section title with an optional control ([end], such as a refresh) on its right. */
@Composable internal fun RosterHeader(title: String, end: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** The home screen's one button on a bot's row: start a shared space with this bot already picked (the long-press menu's "Start a group with…"). */
@Composable internal fun botGroupQuick(vm: BeaconViewModel, bot: BotModeBot, name: String, tag: String, dialog: MutableState<String>): RowQuick =
    RowQuick(Icons.Rounded.Groups, "${uiText("Start a group with…").trimEnd('…', ' ')} $name", tag) { vm.botMode.startNewChat(bot.profile); dialog.value = "newChat:" }

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
            Text(uiText("New space"), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
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
        // "newBot:researcher" starts from a suggested role; "newBot:" from nothing.
        "newBot" -> NewProfileDialog(profileRows.map { it.string("name") to profileDisplayName(it.string("name"), profileRows) }, admin,
            onCreate = { name, from, role -> vm.profileAdmin.create(name, from, role, profileRows.map { it.string("name") }) }, onDismiss = close,
            initialName = id, initialRole = suggestedRoleCatalog().firstOrNull { it.id == id }?.role.orEmpty())
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
