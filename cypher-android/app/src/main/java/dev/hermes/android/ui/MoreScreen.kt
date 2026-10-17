@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BuildConfig
import dev.hermes.android.CypherViewModel
import dev.hermes.android.core.*
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private val settingsCategories = linkedMapOf(
    "Models & tools" to listOf("Providers & models", "Tools & commands", "MCP servers"),
    "Server & support" to listOf("Health", "Logs", "Server updates", "Session checkpoints", "Licenses"),
)
private val descriptions = mapOf(
    "Cypher" to "Voice model and call capabilities",
    "Profiles" to "Profile defaults, purpose and behavior",
    "Appearance" to "Theme, text size and language", "Settings" to "Reading and sending messages",
    "Notifications" to "Calls, requests and completed work", "App updates" to "Install the latest Cypher version",
    "Models & tools" to "Models, tools and service connections", "Server & support" to "Health, logs and Hermes updates",
    "Providers & models" to "Models for this conversation and your bots", "Tools & commands" to "Enable tool groups and browse commands",
    "Skills" to "Read, enable and adjust installed skills", "Memory" to "Agent notes and what Hermes knows about you",
    "Connection" to "Your private Hermes connection", "MCP servers" to "Manage and test service connections",
    "Health" to "Check the dashboard and agent", "Logs" to "Recent server activity",
    "Server updates" to "Check and update Hermes", "Session checkpoints" to "Saved file recovery points", "Licenses" to "Open-source acknowledgements",
)
fun settingsParent(page: String): String = when {
    page.startsWith("category:") -> ""
    page == "Commands" -> "Tools & commands"
    else -> settingsCategories.entries.firstOrNull { page in it.value }?.let { "category:${it.key}" }.orEmpty()
}
private fun settingsIcon(name: String): ImageVector = when (name) {
    "Cypher" -> Icons.Rounded.GraphicEq
    "Appearance" -> Icons.Rounded.Palette
    "Settings" -> Icons.Rounded.Tune
    "Notifications" -> Icons.Rounded.NotificationsNone
    "App updates" -> Icons.Rounded.SystemUpdate
    "Models & tools" -> Icons.Rounded.Build
    "Connections", "Connection" -> Icons.Rounded.Link
    "Server & support", "Health" -> Icons.Rounded.SettingsSuggest
    "Providers & models" -> Icons.Rounded.AutoAwesome
    "Skills" -> Icons.Rounded.AutoAwesome
    "Memory" -> Icons.Rounded.Psychology
    else -> Icons.Rounded.FolderOpen
}
@Composable private fun SettingsRow(name: String, description: String = descriptions[name].orEmpty(), onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(uiText(settingsLabel(name)), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) },
        supportingContent = if (description.isBlank()) null else ({ Text(uiText(description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }),
        leadingContent = { Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .09f)) { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(settingsIcon(name), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) } } },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 78.dp).clickable(onClick = onClick),
    )
}
@Composable private fun SettingsGroup(title: String, names: List<String>, modifier: Modifier = Modifier, select: (String) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiText(title), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Glass { Column { names.forEachIndexed { index, name ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            SettingsRow(name) { select(name) }
        } } }
    }
}
@Composable private fun SettingsHome(vm: CypherViewModel, category: String = "") {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    var query by rememberSaveable(category) { mutableStateOf("") }
    val all = listOf("Profiles", "Appearance", "Settings", "Skills", "Memory", "Cypher", "Notifications", "App updates", "Connection") + settingsCategories.keys + settingsCategories.values.flatten()
    val matches = all.filter { settingsSearchMatches(uiText(settingsLabel(it)), uiText(descriptions[it].orEmpty()), query) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = settingsUseColumns(maxWidth.value, fontScale)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(uiText("Search settings")) }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) }
        if (query.isNotBlank()) {
            item { if (matches.isEmpty()) Text(uiText("No matching settings"), style = MaterialTheme.typography.bodyLarge) else Glass { Column { matches.forEach { name -> SettingsRow(name) { vm.more(if (name in settingsCategories) "category:$name" else name) } } } } }
        } else if (category == "Advanced") {
            item { SettingsGroup("Tools and services", settingsCategories.keys.toList()) { vm.more("category:$it") } }
        } else if (category.isNotBlank()) {
            item { Glass { Column { settingsCategories[category].orEmpty().forEachIndexed { index, name -> if (index > 0) HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)); SettingsRow(name) { vm.more(name) } } } } }
        } else {
            item { Row(Modifier.fillMaxWidth().clickable { vm.more("Connection") }.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) { Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Text("C", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) } }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(uiText("Connection"), style = MaterialTheme.typography.titleMedium); Text(uiText(if (ui.connected) "Connected privately" else "Connection needs attention") + " · " + ui.profile, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            item {
                val personal: @Composable (Modifier) -> Unit = { modifier -> SettingsGroup("Preferences", listOf("Settings", "Appearance", "Notifications", "App updates"), modifier) { vm.more(it) } }
                val workspace: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        SettingsGroup("Assistant & server", listOf("Cypher", "Profiles") + settingsCategories.keys) { vm.more(if(it in setOf("Cypher", "Profiles")) it else "category:$it") }
                    }
                }
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) { personal(Modifier.weight(1f)); workspace(Modifier.weight(1f)) }
                else Column(verticalArrangement = Arrangement.spacedBy(24.dp)) { personal(Modifier.fillMaxWidth()); workspace(Modifier.fillMaxWidth()) }
            }
        }
    }
    }
}

@Composable fun MoreScreen(vm: CypherViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val page = ui.morePage
    val remotePage = page in (settingsCategories.values.flatten().filterNot { it == "Licenses" } + listOf("Skills", "Memory", "Cypher", "Bots", "Profiles", "Commands", "Session usage"))
    if (remotePage && vm.client == null && !ui.loading) {
        androidx.activity.compose.BackHandler { vm.backFromSettings() }
        Column(Modifier.fillMaxSize()) {
            Header(page, back = if (page in setOf("Skills", "Bots")) null else ({ vm.backFromSettings() }), menu = LocalOpenNavigationMenu.current)
            EmptyState("Reconnect to Hermes", "Keep Tailscale connected, then retry. Your saved drafts stay on this phone.", action = "Reconnect", onAction = { vm.connect(ui.origin) })
        }
        return
    }
    LaunchedEffect(page) { if (page.startsWith("category:") && page.removePrefix("category:") !in settingsCategories.keys && page != "category:Advanced") vm.tab("More") }
    when {
        page in setOf("Skills", "Memory") -> NativePhonePreferences(vm, page)
        page == "Bots" -> NativeBotModeScreen(vm)
        page == "Profiles" -> NativeProfilesPage(vm)
        page in setOf("Health", "Logs", "Server updates", "Session checkpoints") -> NativeServerPage(vm, page)
        page.isBlank() || page.startsWith("category:") -> Column(Modifier.fillMaxSize()) {
            Header(if (page.isBlank()) "Settings" else page.removePrefix("category:"), back = if (page.isBlank()) null else ({ vm.backFromSettings() }), menu = LocalOpenNavigationMenu.current)
            SettingsHome(vm, page.removePrefix("category:"))
        }
        page == "Settings" -> NativeChatPreferences(vm)
        page in setOf("Appearance", "Connection", "Cypher", "Notifications", "App updates", "Licenses") -> Column(Modifier.fillMaxSize()) {
            Header(page, back = if (page in setOf("Skills", "Bots")) null else ({ vm.backFromSettings() }), menu = LocalOpenNavigationMenu.current)
            when (page) {
                "Cypher" -> CypherSettings(vm)
                "Appearance" -> AppearanceSettings(vm)
                "Connection" -> ConnectionSettings(vm)
                "Notifications" -> NotificationSettings(vm)
                "App updates" -> AppUpdates(vm)
                "Licenses" -> LicenseScreen(vm)
            }
        }
        else -> NativeAdministration(vm, page)
    }
    NativeCatalog(vm)
}

@Composable private fun NativeChatPreferences(vm: CypherViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val settings = data["profile-settings"] ?: LocalCypherSettings.current
    Column(Modifier.fillMaxSize()) {
        Header("Chat preferences", back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current)
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Saved on this phone for the connected account and profile.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Choice("Send shortcut", settings.string("send_key", "enter"), listOf("enter", "ctrl_enter"), { vm.nativePreferences(json("send_key" to it)) }, optionLabels = mapOf("enter" to "Enter", "ctrl_enter" to "Ctrl + Enter")) }
            item { LabeledToggle("Format your messages", settings.optBoolean("render_user_markdown", true), { vm.nativePreferences(json("render_user_markdown" to it)) }, "Render Markdown while preserving the original source.") }
            item { LabeledToggle("Show thinking", settings.optBoolean("show_thinking", true), { vm.nativePreferences(json("show_thinking" to it)) }, "Show available reasoning disclosures. This does not change model reasoning effort.") }
            item { Text("Send starts a reply. Queue waits until the current reply finishes. Guide adds direction to the running reply.", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable fun StructuredRead(value: JSONObject, depth: Int = 0) {
    val keys = remember(value) { value.keys().asSequence().filter { !it.startsWith('_') && !privateStructuredKey(it) }.toList() }
    var visible by remember(value) { mutableIntStateOf(24) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.take(visible).forEach { key ->
            val item = value.opt(key)
            if (item != JSONObject.NULL && item != null && (item is JSONObject || item is JSONArray || item.toString().isNotBlank())) {
                Text(key.replace('_', ' ').replaceFirstChar(Char::titlecase), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StructuredReadValue(item, depth + 1)
            }
        }
        if (visible < keys.size) TextButton(onClick = { visible += 24 }) { Text("Show next 24 fields · ${keys.size} total") }
    }
}
@Composable private fun StructuredReadValue(value: Any, depth: Int = 0) {
    when {
        depth > 8 -> Text("Further nested details are available in the native agent on your computer.", style = MaterialTheme.typography.bodySmall)
        value is JSONObject -> {
            var expanded by remember(value) { mutableStateOf(depth <= 1) }
            Column {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details · ${value.length()} fields" else "Show details · ${value.length()} fields") }
                if (expanded) StructuredRead(value, depth)
            }
        }
        value is JSONArray -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var visible by remember(value) { mutableIntStateOf(24) }
            if (value.length() == 0) Text("No entries", style = MaterialTheme.typography.bodySmall)
            (0 until minOf(value.length(), visible)).forEach { index -> StructuredReadValue(value.opt(index) ?: "", depth + 1) }
            if (visible < value.length()) TextButton(onClick = { visible += 24 }) { Text("Show next 24 · ${value.length()} total") }
        }
        else -> SelectionContainer { Text(value.toString(), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable private fun ConnectionSettings(vm: CypherViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var origin by rememberSaveable(ui.origin) { mutableStateOf(ui.origin) }
    var details by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (ui.connected) "Connected privately" else "Connection needs attention", style = MaterialTheme.typography.titleLarge)
            Text("Profile · ${ui.profile}", style = MaterialTheme.typography.bodyLarge)
            Text("Keep Tailscale connected to reach your private Cypher server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        OutlinedTextField(origin, { origin = it }, label = { Text(uiText("HTTPS origin")) }, supportingText = { Text("Your private server address, starting with https://") }, enabled = !ui.loading, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (ui.error.isNotBlank()) Text(ui.error, color = MaterialTheme.colorScheme.error)
        Button(onClick = { vm.connect(origin) }, enabled = !ui.loading && origin.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (ui.loading) "Connecting…" else uiText("Connect")) }
        TextButton(onClick = { details = !details }) { Text(if (details) "Hide technical details" else "Technical details") }
        if (details) SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Account identifier", style = MaterialTheme.typography.labelLarge); Text(ui.owner.ifBlank { "Not signed in" }, style = MaterialTheme.typography.bodyMedium) } }
        TextButton(onClick = { vm.openForm(ActionForm("Sign out of Cypher", "", emptyList(), explanation = "Sign out of this connection and end the active Cypher call. Your encrypted drafts stay on this device.", actionLabel = "Sign out", onResult = { vm.logout() })) }) { Text(uiText("Sign out")) }
    }
}
@Composable private fun AppUpdates(vm: CypherViewModel) {
    val update by vm.appUpdate.collectAsStateWithLifecycle()
    val call by vm.app.calls.state.collectAsStateWithLifecycle()
    val incoming by vm.incomingBusy.collectAsStateWithLifecycle()
    val callBusy = call.active || incoming || call.state == "ending"
    val context = LocalContext.current
    fun alertsEnabled(): Boolean = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(dev.hermes.android.updates.UpdateManager.CHANNEL)?.importance != android.app.NotificationManager.IMPORTANCE_NONE
    var alertsAllowed by remember { mutableStateOf(alertsEnabled()) }
    androidx.lifecycle.compose.LifecycleResumeEffect(context) {
        val allowed = alertsEnabled()
        val enabledOnReturn = allowed && !alertsAllowed
        alertsAllowed = allowed
        if (enabledOnReturn) vm.checkForAppUpdate()
        onPauseOrDispose { }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Glass(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Text(update.release?.let { "Cypher ${it.name}" } ?: "Keep Cypher up to date", style = MaterialTheme.typography.headlineSmall)
                Text(update.status, style = MaterialTheme.typography.bodyLarge)
                if (update.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick = { if (update.release == null && !update.ready) vm.checkForAppUpdate() else vm.downloadAppUpdate() }, enabled = !update.busy && !(callBusy && (update.release != null || update.ready)), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(when { update.busy -> "Updating…"; update.ready -> "Install update"; update.release != null -> "Update"; else -> "Check for updates" })
                }
                if (callBusy && (update.release != null || update.ready)) Text(if(call.state=="ending") "Call cleanup is finishing. The update will be available shortly." else "Finish your call before installing the update.", style = MaterialTheme.typography.bodyMedium)
                Text("Installed · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Cypher checks, downloads and verifies the release. Confirm installation in Android to finish.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!alertsAllowed) OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Allow update notifications") }
        Text("Keep Tailscale connected for background checks. Android may delay checks to save battery.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    }
}

@Composable private fun AppearanceSettings(vm: CypherViewModel) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("Appearance on this phone", style = MaterialTheme.typography.titleLarge); Text("These changes apply to this app. Your browser preferences stay separate.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Glass { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText("Display"), style = MaterialTheme.typography.titleMedium)
            Choice("Theme", prefs.theme, listOf("system", "dark", "light"), { vm.nativePreferences(json("theme" to it)) }, optionLabels = mapOf("system" to "Follow device", "dark" to "Dark", "light" to "Light"))
            Choice("Color style", prefs.skin, NativeSkins, { vm.nativePreferences(json("skin" to it)) }, optionLabels = skinLabels)
        } } }
        item { Glass { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText("Reading"), style = MaterialTheme.typography.titleMedium)
            Choice("Text size", prefs.fontSize, nativeFontSizes, { vm.nativePreferences(json("font_size" to it)) }, optionLabels = mapOf("small" to "Small", "default" to "Standard", "large" to "Large", "xlarge" to "Extra large"))
            Choice(uiText("Language"), NativeLanguages[prefs.language] ?: prefs.language, NativeLanguages.values.toList(), { label -> NativeLanguages.entries.firstOrNull { it.value == label }?.let { vm.nativePreferences(json("language" to it.key)) } })
        } } }
        item { Glass { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiText("Comfort"), style = MaterialTheme.typography.titleMedium)
            LabeledToggle(uiText("Reduce motion"), prefs.reducedMotion, { vm.nativePreferences(json("reduced_motion" to it)) }, "Keep transitions still")
            LabeledToggle(uiText("Right-to-left layout"), prefs.rtl, { vm.nativePreferences(json("rtl" to it)) })
        } } }
    }
}
