package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.CypherViewModel
import dev.hermes.android.core.*

internal data class NavigationRoute(val tab: String, val page: String = "")
internal val navigationTiles = listOf("Skills", "Memory", "Cron", "Files", "Projects", "Bots", "Kanban", "Settings")
internal fun navigationRoute(destination: String): NavigationRoute = when (destination) {
    "Skills", "Memory", "Bots" -> NavigationRoute("More", destination)
    "Cron" -> NavigationRoute("Tasks")
    "Files" -> NavigationRoute("Spaces")
    "Projects", "Kanban" -> NavigationRoute(destination)
    "Settings" -> NavigationRoute("More")
    "Cypher" -> NavigationRoute("Cypher")
    "Chat" -> NavigationRoute("Chat")
    else -> error("Unknown navigation destination: $destination")
}
internal fun navigationColumns(width: Float, fontScale: Float): Int = if (width >= 240 && fontScale <= 1.1f) 4 else 2
internal fun navigationRows(columns: Int): List<List<String>> = if (columns == 4) navigationTiles.chunked(4) else listOf(
    listOf("Skills", "Memory"), listOf("Cron", "Files"), listOf("Projects"), listOf("Bots", "Kanban"), listOf("Settings"),
)
internal fun navigationHistoryGroups(pinned: List<Boolean>): List<Pair<String, List<Int>>> = listOf(
    "Pinned" to pinned.indices.filter { pinned[it] }, "Recent" to pinned.indices.filter { !pinned[it] },
)
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

@Composable internal fun SidebarNavigation(vm: CypherViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val currentProfile = ui.chatProfile.ifBlank { ui.profile }
    val currentProfileLabel = profileDisplayLabel(currentProfile, profileRows, ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading")))
    LaunchedEffect(ui.origin, ui.owner, ui.connected) { if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    var profilesOpen by remember(ui.origin, ui.owner, ui.profile) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ui.botModeEnabled) {
            TextButton(onClick = vm::showBotMode, enabled = !ui.botModeSwitching, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("navigation-bot-mode-home")) {
                Icon(Icons.Rounded.SmartToy, null, Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(uiText("Bot Mode"), style = MaterialTheme.typography.titleMedium)
                    Text(uiText("Bots & shared spaces"), style = MaterialTheme.typography.labelSmall)
                }
                Icon(Icons.Rounded.ChevronRight, null)
            }
            TextButton(onClick = vm::toggleBotMode, enabled = !ui.botModeSwitching, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("navigation-leave-bot-mode")) {
                Text(uiText("Leave Bot Mode"))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { profilesOpen = true; vm.load("navigation-profiles", "/api/profiles", force = false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("navigation-profile")) {
                        Icon(Icons.Rounded.AccountCircle, null, Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                            // The chats' profile, even while the assistant tab uses its own.
                            Text(currentProfileLabel, style = MaterialTheme.typography.titleMedium)
                            Text(uiText("Switch profile"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.ExpandMore, null)
                    }
                    DropdownMenu(expanded = profilesOpen, onDismissRequest = { profilesOpen = false }) {
                        val record = data["navigation-profiles"]
                        when {
                            record == null || record.optBoolean("_loading") -> DropdownMenuItem(text = { Text(uiText("Loading profiles…")) }, onClick = {}, enabled = false)
                            record.string("_error").isNotBlank() -> DropdownMenuItem(text = { Text(uiText("Retry profiles")) }, onClick = { vm.load("navigation-profiles", "/api/profiles") })
                            else -> record.rows("profiles").forEach { profile ->
                                val id = profile.string("name")
                                if (id.isNotBlank()) DropdownMenuItem(text = { Text(profileDisplayName(id, profileRows)) }, leadingIcon = { if (id == currentProfile) Icon(Icons.Rounded.Check, null) }, onClick = { profilesOpen = false; if (id != currentProfile) vm.chooseProfile(id) })
                            }
                        }
                        DropdownMenuItem(text = { Text(uiText("Manage profiles")) }, onClick = { profilesOpen = false; vm.more("Profiles") })
                    }
                }
            }
            Button(onClick = { vm.tab("Chat"); vm.newChat() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("navigation-new-chat"), shape = RoundedCornerShape(14.dp), enabled = !ui.newChatOpening) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(uiText(if (ui.newChatOpening) "Opening chat…" else "New chat"))
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = navigationColumns(maxWidth.value, LocalDensity.current.fontScale)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                navigationRows(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { name ->
                            val route = navigationRoute(name)
                            val isBotToggle = name == "Bots"
                            val active = if (isBotToggle) ui.botModeEnabled else ui.tab == route.tab && ui.morePage == route.page
                            Surface(onClick = { vm.navigate(name) }, enabled = !isBotToggle || !ui.botModeSwitching, modifier = Modifier.weight(1f).shadow(if (isBotToggle && active) 12.dp else 0.dp, RoundedCornerShape(12.dp), clip = false, ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary).semantics { role = if (isBotToggle) Role.Switch else Role.Button; if (isBotToggle) { toggleableState = if (ui.botModeEnabled) ToggleableState.On else ToggleableState.Off; stateDescription = if (ui.botModeSwitching) "Switching" else if (ui.botModeEnabled) "On" else "Off" } else selected = active }.testTag("navigation-${name.lowercase()}"), shape = RoundedCornerShape(12.dp), color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, border = androidx.compose.foundation.BorderStroke(if (isBotToggle && active) 2.dp else 1.dp, if (isBotToggle && active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                                Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(navigationIcon(name), null, Modifier.size(20.dp), tint = if (isBotToggle && active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                                    Text(uiText(if (isBotToggle) "Bot Mode" else name), style = MaterialTheme.typography.labelMedium, color = if (isBotToggle && active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                                    // Not uiText: the bundled catalog's "on" is the preposition ("в" in Russian), not the switch state.
                                    if (isBotToggle && active) Text("On", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        NavigationDrawerItem(label = { Text(assistantText("Beacon chat")) }, selected = ui.tab == "Cypher", onClick = { vm.navigate("Cypher") }, icon = { BeaconMark(contentDescription = assistantText("Beacon")) }, modifier = Modifier.testTag("navigation-cypher"))
        HorizontalDivider()
    }
}

