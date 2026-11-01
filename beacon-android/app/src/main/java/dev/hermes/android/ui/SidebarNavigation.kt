@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.hermes.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.updates.AppUpdateUi
import dev.hermes.android.updates.PluginStatus
import dev.hermes.android.updates.UpdatePhase
import org.json.JSONObject

internal data class NavigationRoute(val tab: String, val page: String = "")
internal val navigationTiles = listOf("Skills", "Memory", "Cron", "Files", "Projects", "Bots", "Kanban", "Settings")
internal fun navigationRoute(destination: String): NavigationRoute = when (destination) {
    "Skills", "Memory", "Bots" -> NavigationRoute("More", destination)
    "Cron" -> NavigationRoute("Tasks")
    "Files" -> NavigationRoute("Spaces")
    "Projects", "Kanban" -> NavigationRoute(destination)
    "Settings" -> NavigationRoute("More")
    "Beacon" -> NavigationRoute("Beacon")
    "Chat" -> NavigationRoute("Chat")
    else -> error("Unknown navigation destination: $destination")
}

/** Above this font scale the drawer keeps four tiles and puts the rest behind More. */
internal const val NavigationLargeTextScale = 1.1f
/** The four tiles used most, kept in view at a large font; the others open from More. */
internal val navigationMainTiles = listOf("Cron", "Files", "Projects", "Settings")
internal fun navigationCompact(fontScale: Float): Boolean = fontScale > NavigationLargeTextScale
internal fun navigationColumns(width: Float, fontScale: Float): Int = if (width >= 240 && !navigationCompact(fontScale)) 4 else 2
/** The tiles in the grid at [fontScale]: all eight, or the four main ones at a large font. */
internal fun navigationShown(fontScale: Float): List<String> = if (navigationCompact(fontScale)) navigationMainTiles else navigationTiles
/** The tiles the More sheet lists at [fontScale]; none while the grid shows them all. */
internal fun navigationMore(fontScale: Float): List<String> = navigationTiles - navigationShown(fontScale).toSet()
// The tiles are in pairs, so two columns give full rows and no tile stretches alone.
internal fun navigationRows(columns: Int, fontScale: Float = 1f): List<List<String>> = navigationShown(fontScale).chunked(columns)
/** What a tile says; its name and test tag stay "Cron". */
internal fun navigationLabel(name: String): String = if (name == "Cron") "Schedules" else name
private fun navigationIcon(name: String): ImageVector = when (name) {
    "Skills" -> Icons.Rounded.AutoAwesome
    "Memory" -> Icons.Rounded.Psychology
    "Cron" -> Icons.Rounded.Schedule
    "Files" -> Icons.Rounded.FolderOpen
    "Projects" -> Icons.Rounded.Workspaces
    "Bots" -> Icons.Rounded.SmartToy
    "Kanban" -> Icons.Rounded.ViewKanban
    else -> Icons.Rounded.Tune
}

/** The tile names the drawer shows through uiText ("Cron" reads Schedules). */
internal val NavigationStrings = navigationTiles.map(::navigationLabel)

/** The two worlds the drawer switches between: the owner's chats, or Bot Mode with its bots and shared spaces. */
internal enum class DrawerMode { Chats, Bots }
internal enum class DrawerModeAction { None, EnterBots, LeaveBots, OpenBotHome }

/** What a tap on [tapped] does: leave or enter Bot Mode, or (already in it) go to its home. Chats while in Chats does nothing. */
internal fun drawerModeAction(botMode: Boolean, tapped: DrawerMode): DrawerModeAction = when (tapped) {
    DrawerMode.Chats -> if (botMode) DrawerModeAction.LeaveBots else DrawerModeAction.None
    DrawerMode.Bots -> if (botMode) DrawerModeAction.OpenBotHome else DrawerModeAction.EnterBots
}

/** Each segment keeps the tag of the control it replaced: Leave Bot Mode and the Bot Mode home button. */
internal fun drawerModeTag(botMode: Boolean, mode: DrawerMode): String = when (mode) {
    DrawerMode.Chats -> if (botMode) "navigation-leave-bot-mode" else "navigation-mode-chats"
    DrawerMode.Bots -> if (botMode) "navigation-bot-mode-home" else "navigation-mode-bots"
}

/** An update waits for the owner: a newer app, or a Hermes plugin that can update, or a download that needs install permission. */
internal fun drawerUpdateReady(state: AppUpdateUi): Boolean = state.supported && when (state.phase) {
    UpdatePhase.NeedsPermission -> true
    UpdatePhase.Available -> state.release != null || state.plugin.status in setOf(PluginStatus.Available, PluginStatus.NeedsReview, PluginStatus.Restart)
    else -> false
}

@Composable internal fun SidebarNavigation(vm: BeaconViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val update by vm.app.updates.state.collectAsStateWithLifecycle()
    val updateReady = drawerUpdateReady(update)
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val currentProfile = ui.chatProfile.ifBlank { ui.profile }
    val currentProfileLabel = profileDisplayLabel(currentProfile, profileRows, ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading")))
    LaunchedEffect(ui.origin, ui.owner, ui.connected) { if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    var profilesOpen by remember(ui.origin, ui.owner, ui.profile) { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    if (naming) NewThreadDialog(vm, onDismiss = { naming = false })
    val openMode: (DrawerMode) -> Unit = { mode ->
        when (drawerModeAction(ui.botModeEnabled, mode)) {
            DrawerModeAction.EnterBots, DrawerModeAction.LeaveBots -> { vm.toggleBotMode() }
            DrawerModeAction.OpenBotHome -> vm.showBotMode()
            DrawerModeAction.None -> {}
        }
    }
    // A destination tile opens its page; Bots is the tile that enters Bot Mode, or goes to its home once inside.
    val openTile: (String) -> Unit = { name -> if (name == "Bots") openMode(DrawerMode.Bots) else vm.navigate(name) }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DrawerModeSwitch(ui.botModeEnabled, ui.botModeSwitching, openMode)
        if (!ui.botModeEnabled) {
            Box(Modifier.fillMaxWidth()) {
                var pillWidth by remember { mutableStateOf(0.dp) }
                val density = LocalDensity.current
                OutlinedButton(onClick = { profilesOpen = true; vm.load("navigation-profiles", "/api/profiles", force = false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).onSizeChanged { pillWidth = with(density) { it.width.toDp() } }.testTag("navigation-profile")) {
                    // The chats' profile, even while the assistant tab uses its own; the avatar is the one Bot Mode shows for it.
                    ChatAvatar(profileDisplayName(currentProfile, profileRows), currentProfile, size = 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(currentProfileLabel, style = MaterialTheme.typography.titleMedium.contentDirection(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(uiText("Switch profile"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.ExpandMore, null)
                }
                ProfileSwitcherMenu(
                    expanded = profilesOpen, width = pillWidth, catalog = profileCatalog, profileRows = profileRows, currentProfile = currentProfile,
                    onDismiss = { profilesOpen = false },
                    onChoose = { id -> profilesOpen = false; if (id != currentProfile) vm.chooseProfile(id) },
                    onRetry = { vm.load("navigation-profiles", "/api/profiles") },
                    onManage = { profilesOpen = false; vm.more("Profiles") },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.tab("Chat"); vm.newChat() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("navigation-new-chat"), shape = RoundedCornerShape(14.dp), enabled = !ui.newChatOpening) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(uiText(if (ui.newChatOpening) "Opening chat…" else "New chat"))
                }
                FilledTonalButton(onClick = { naming = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("navigation-new-thread"), shape = RoundedCornerShape(14.dp), enabled = !ui.newChatOpening) {
                    Icon(Icons.Rounded.Forum, null); Spacer(Modifier.width(8.dp)); Text(uiText("New thread"))
                }
            }
        }
        if (updateReady) UpdateReadyChip(onClick = vm::openAppUpdates)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fontScale = LocalDensity.current.fontScale
            val columns = navigationColumns(maxWidth.value, fontScale)
            val overflow = navigationMore(fontScale)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                navigationRows(columns, fontScale).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { name ->
                            val route = navigationRoute(name)
                            NavigationTile(name, active = ui.tab == route.tab && ui.morePage == route.page, enabled = name != "Bots" || !ui.botModeSwitching,
                                updateReady = name == "Settings" && updateReady, onClick = { openTile(name) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (overflow.isNotEmpty()) NavigationMoreTile(onClick = { moreOpen = true })
            }
            if (moreOpen && overflow.isNotEmpty()) NavigationMoreSheet(
                names = overflow, isActive = { name -> navigationRoute(name).let { ui.tab == it.tab && ui.morePage == it.page } }, updateReady = updateReady,
                onPick = { name -> moreOpen = false; openTile(name) }, onDismiss = { moreOpen = false },
            )
        }
    }
}

/** Chats | Bots, always the first thing in the drawer: the two worlds are one switch, not a tile. */
@Composable internal fun DrawerModeSwitch(botMode: Boolean, switching: Boolean, onTap: (DrawerMode) -> Unit, modifier: Modifier = Modifier) {
    val modes = DrawerMode.entries
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            val chosen = (mode == DrawerMode.Bots) == botMode
            SegmentedButton(
                selected = chosen,
                onClick = { onTap(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                enabled = !switching,
                modifier = Modifier.heightIn(min = 48.dp).testTag(drawerModeTag(botMode, mode)),
                icon = {
                    SegmentedButtonDefaults.Icon(
                        active = chosen,
                        activeContent = { Icon(Icons.Rounded.Check, null, Modifier.size(SegmentedButtonDefaults.IconSize)) },
                        inactiveContent = { Icon(if (mode == DrawerMode.Bots) Icons.Rounded.SmartToy else Icons.Rounded.ChatBubbleOutline, null, Modifier.size(SegmentedButtonDefaults.IconSize)) },
                    )
                },
            ) { Text(uiText(if (mode == DrawerMode.Bots) "Bots" else "Chats"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** One line under the drawer's top controls: an update waits, and a tap opens App updates, where the install is one more tap. */
@Composable internal fun UpdateReadyChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ink = rememberSettingsStatusPalette().ink(SettingsStatus.Attention)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(ink.copy(alpha = SettingsStatusTint))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp).testTag("navigation-update-ready"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.SystemUpdateAlt, null, Modifier.size(20.dp), tint = ink)
        Text(uiText("Update ready"), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(20.dp), tint = ink)
    }
}

/** The small light on the Settings tile while an update waits; [ring] is the tile's own colour, so it reads as a cut-out. */
@Composable private fun UpdateDot(ring: Color, modifier: Modifier = Modifier) {
    val ink = rememberSettingsStatusPalette().ink(SettingsStatus.Attention)
    Box(modifier.size(11.dp).clip(CircleShape).background(ink).border(2.dp, ring, CircleShape).testTag("navigation-settings-update-dot"))
}

@Composable private fun NavigationTile(name: String, active: Boolean, enabled: Boolean, updateReady: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fill = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val waiting = if (updateReady) uiText("Update ready") else ""
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = modifier.semantics { role = Role.Button; selected = active; if (updateReady) stateDescription = waiting }.testTag("navigation-${name.lowercase()}"),
        shape = RoundedCornerShape(12.dp), color = fill, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box {
                Icon(navigationIcon(name), null, Modifier.size(20.dp))
                if (updateReady) UpdateDot(fill, Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-4).dp))
            }
            Text(uiText(navigationLabel(name)), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** At a large font the grid is four tiles; this full-width row opens the rest. */
@Composable private fun NavigationMoreTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick, modifier = modifier.fillMaxWidth().semantics { role = Role.Button }.testTag("navigation-more"),
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.MoreHoriz, null, Modifier.size(20.dp))
            Text(uiText("More"), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(20.dp))
        }
    }
}

/** The tiles that do not fit the large-font grid, one row each; they keep their tags. */
@Composable private fun NavigationMoreSheet(names: List<String>, isActive: (String) -> Boolean, updateReady: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("navigation-more-sheet")) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            names.forEach { name ->
                val active = isActive(name)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(selected = active, role = Role.Button, onClick = { onPick(name) })
                        .padding(horizontal = 24.dp).testTag("navigation-${name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(navigationIcon(name), null, Modifier.size(24.dp), tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    Text(uiText(navigationLabel(name)), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (name == "Settings" && updateReady) UpdateDot(MaterialTheme.colorScheme.surfaceContainerLow)
                }
            }
        }
    }
}

/**
 * The profile list as a menu as wide as the pill that opens it: letter avatars (the colours Bot Mode shows), the current profile
 * checked, a divider, then Manage profiles. Rows are our own, because a menu item stops at 280dp.
 */
@Composable internal fun ProfileSwitcherMenu(
    expanded: Boolean, width: Dp, catalog: JSONObject?, profileRows: List<JSONObject>, currentProfile: String,
    onDismiss: () -> Unit, onChoose: (String) -> Unit, onRetry: () -> Unit, onManage: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = (if (width > 0.dp) Modifier.width(width) else Modifier).testTag("navigation-profile-menu")) {
        when {
            catalog == null || catalog.optBoolean("_loading") -> MenuRow(Modifier.testTag("navigation-profile-loading")) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(uiText("Loading profiles…"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            }
            catalog.string("_error").isNotBlank() -> MenuRow(Modifier.clickable(role = Role.Button, onClick = onRetry).testTag("navigation-profile-retry")) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(24.dp))
                Text(uiText("Retry profiles"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            }
            else -> catalog.rows("profiles").forEach { profile ->
                val id = profile.string("name")
                if (id.isNotBlank()) {
                    val current = id == currentProfile
                    val name = profileDisplayName(id, profileRows)
                    MenuRow(Modifier.selectable(selected = current, role = Role.RadioButton, onClick = { onChoose(id) }).testTag("navigation-profile-option:$id")) {
                        ChatAvatar(name, id, size = 32.dp)
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.contentDirection(), fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (current) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        MenuRow(Modifier.clickable(role = Role.Button, onClick = onManage).testTag("navigation-profile-manage")) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Settings, null, Modifier.size(18.dp))
            }
            Text(uiText("Manage profiles"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable private fun MenuRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
