@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.hermes.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.contentDescription
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
    // Bots is the one switch for Bot Mode: a tap turns it on, and off again (navigate("Bots") toggles).
    val openTile: (String) -> Unit = vm::navigate
    val botMode = ui.botModeEnabled
    // New bot and New space open the roster's own dialogs; outside the Bot Mode drawer this row hosts them itself.
    val hostedDialog = LocalBotModeDialog.current
    val ownDialog = rememberSaveable { mutableStateOf("") }
    val botDialog = hostedDialog ?: ownDialog
    if (botMode && hostedDialog == null) BotModeDialogs(vm, ownDialog)
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The drawer keeps one layout in both modes, so switching Bot Mode never moves the tiles.
        run {
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
        }
        // The two buttons at the top: New chat and New thread, or New bot and New space in Bot Mode. At large text they stack
        // full width, so the second never wraps beside the first.
        val stacked = isLargeText(LocalDensity.current.fontScale)
        val primary: @Composable (Modifier) -> Unit = { modifier ->
            if (botMode) DrawerAction(uiText("New bot"), Icons.Rounded.Add, "bot-mode-sidebar-new-bot", modifier, primary = true, enabled = !ui.botModeSwitching) { botDialog.value = "newBot:" }
            else DrawerAction(uiText(if (ui.newChatOpening) "Opening chat…" else "New chat"), Icons.Rounded.Add, "navigation-new-chat", modifier, primary = true, enabled = !ui.newChatOpening) { vm.tab("Chat"); vm.newChat() }
        }
        val secondary: @Composable (Modifier) -> Unit = { modifier ->
            if (botMode) DrawerAction(uiText("New space"), Icons.Rounded.Groups, "bot-mode-sidebar-new-chat", modifier, primary = false, enabled = !ui.botModeSwitching) { vm.botMode.startNewChat(); botDialog.value = "newChat:" }
            else DrawerAction(uiText("New thread"), Icons.Rounded.Forum, "navigation-new-thread", modifier, primary = false, enabled = !ui.newChatOpening) { naming = true }
        }
        if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { primary(Modifier.fillMaxWidth()); secondary(Modifier.fillMaxWidth()) }
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { primary(Modifier.weight(1f)); secondary(Modifier.weight(1f)) }
        if (updateReady) UpdateReadyChip(onClick = vm::openAppUpdates)
        // The same tiles in both modes; in Bot Mode the Bots tile shows on, and a tap on it leaves Bot Mode.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fontScale = LocalDensity.current.fontScale
            val columns = navigationColumns(maxWidth.value, fontScale)
            val overflow = navigationMore(fontScale)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                navigationRows(columns, fontScale).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { name ->
                            val route = navigationRoute(name)
                            NavigationTile(name, active = if (name == "Bots") ui.botModeEnabled else ui.tab == route.tab && ui.morePage == route.page, enabled = name != "Bots" || !ui.botModeSwitching,
                                updateReady = name == "Settings" && updateReady, onClick = { openTile(name) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (overflow.isNotEmpty()) NavigationMoreTile(onClick = { moreOpen = true })
            }
            if (moreOpen && overflow.isNotEmpty()) NavigationMoreSheet(
                names = overflow, isActive = { name -> if (name == "Bots") ui.botModeEnabled else navigationRoute(name).let { ui.tab == it.tab && ui.morePage == it.page } }, updateReady = updateReady,
                onPick = { name -> moreOpen = false; openTile(name) }, onDismiss = { moreOpen = false },
            )
        }
    }
}

/** One of the two buttons at the top of the drawer: the filled [primary] one, or the tonal one beside it. Both keep one size and shape in every mode. */
@Composable private fun DrawerAction(label: String, icon: ImageVector, tag: String, modifier: Modifier, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val at = modifier.heightIn(min = 48.dp).testTag(tag)
    if (primary) Button(onClick = onClick, modifier = at, shape = RoundedCornerShape(14.dp), enabled = enabled, content = content)
    else FilledTonalButton(onClick = onClick, modifier = at, shape = RoundedCornerShape(14.dp), enabled = enabled, content = content)
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
            catalog == null || catalog.optBoolean("_loading") -> {
                val loading = uiText("Loading profiles…")
                // Skeleton rows the shape of the profile rows, so the menu does not resize when the list arrives.
                Box(Modifier.fillMaxWidth().widthIn(min = 200.dp).testTag("navigation-profile-loading").semantics { contentDescription = loading }) { SettingsSkeletonRows(2) }
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
