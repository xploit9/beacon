@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.AppState
import dev.hermes.android.BuildConfig
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.updates.AppUpdateScreen
import dev.hermes.android.updates.UpdatePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

fun settingsParent(page: String): String = when {
    page.startsWith("category:") -> ""
    page == "Commands" -> "Tools & commands"
    else -> settingsCategories.entries.firstOrNull { page in it.value }?.let { "category:${it.key}" }.orEmpty()
}
/** One page padding for every Settings page. */
internal val SettingsPagePadding = 16.dp

/** One icon per destination; the drawer and the pages that open from a destination use the same one. */
internal val AssistantIcon = Icons.Rounded.Face
internal val ProfilesIcon = Icons.Rounded.SmartToy
internal val SkillsIcon = Icons.Rounded.Extension
internal val MemoryIcon = Icons.Rounded.Psychology

private fun settingsIcon(name: String): ImageVector = when (name) {
    "Voice & calls", "Beacon" -> Icons.Rounded.GraphicEq
    "Assistant" -> AssistantIcon
    "Appearance" -> Icons.Rounded.Palette
    "Chat", "Settings" -> Icons.Rounded.ChatBubbleOutline
    "Notifications & Keep connected", "Notifications" -> Icons.Rounded.NotificationsNone
    "App updates" -> Icons.Rounded.SystemUpdate
    "Server", "Health", "Logs", "Server updates", "Session checkpoints" -> Icons.Rounded.SettingsSuggest
    "Profiles & model", "Profiles" -> ProfilesIcon
    "Providers & models" -> Icons.Rounded.Tune
    "Tools & MCP", "Tools & commands", "MCP servers" -> Icons.Rounded.Build
    "About & licenses", "Licenses" -> Icons.Rounded.Info
    "Connections", "Connection" -> Icons.Rounded.Link
    "Skills" -> SkillsIcon
    "Memory" -> MemoryIcon
    else -> Icons.Rounded.FolderOpen
}

/** Touch floor of every Settings row; UI tests assert against this same value. */
internal val SettingsRowMinHeight = 78.dp

/** The overview's row summaries: fixed words through [uiText], user data (profile, version, language name) verbatim. */
@Composable internal fun localizedSettingsSummaries(snapshot: SettingsSnapshot): Map<String, String> =
    settingsSearchRoutes.associateWith { route -> settingsSummary(route, snapshot).map { if (it.translate) uiText(it.text) else it.text }.joinSummary() }

/** [summary] is the row's current value, already localized; a row without one keeps its static description. */
@Composable private fun SettingsRow(name: String, summary: String = "", compatibilityTag: String? = null, selected: Boolean? = null, onClick: () -> Unit) {
    val detail = summary.ifBlank { uiText(settingsDescriptions[name].orEmpty()) }
    val rowModifier = if (selected == null) Modifier.clickable(role = Role.Button, onClick = onClick)
    else Modifier.selectable(selected = selected, role = Role.Button, onClick = onClick)
    // One clickable node: ListItem merges its own text, so a wrapping clickable would be an unlabeled button for TalkBack.
    ListItem(
        headlineContent = { Text(uiText(settingsLabel(name)), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) },
        supportingContent = if (detail.isBlank()) null else ({ Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }),
        leadingContent = { Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .09f)) { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(settingsIcon(name), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) } } },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
        colors = ListItemDefaults.colors(containerColor = if (selected == true) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
        modifier = rowModifier.fillMaxWidth().heightIn(min = SettingsRowMinHeight).testTag(compatibilityTag ?: "settings-row:$name"),
    )
}
/** Tags older tests and saved routes rely on for renamed hub rows. */
private fun hubCompatibilityTag(name: String): String? = when (name) {
    "Connection" -> "settings-connection"
    "Chat" -> "settings-row:Settings"
    "Voice & calls" -> "settings-row:Beacon"
    "Notifications & Keep connected" -> "settings-row:Notifications"
    "Profiles & model" -> "settings-row:Profiles"
    else -> null
}
@Composable private fun SettingsGroup(title: String, names: List<String>, summaries: Map<String, String>, modifier: Modifier = Modifier, selectedPage: String? = null, onOpen: (String) -> Unit) {
    Column(modifier.testTag("settings-group:$title"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title.isNotBlank()) Text(uiText(title), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Glass { Column { names.forEachIndexed { index, name ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            SettingsRow(name, summaries[name].orEmpty(), hubCompatibilityTag(name), selected = selectedPage?.let { settingsRoute(name) == it }) { onOpen(settingsRoute(name)) }
        } } }
    }
}
/**
 * Stateless Settings overview: current values at a glance, one tap to the focused page. [onOpen] receives the exact
 * `BeaconViewModel.more` argument, [summaries] maps a row name to its localized current value (absent = no summary).
 */
@Composable internal fun SettingsOverview(summaries: Map<String, String>, onOpen: (String) -> Unit, category: String = "", selectedPage: String? = null) {
    var query by rememberSaveable(category) { mutableStateOf("") }
    val matches = settingsSearchRoutes.filter { settingsSearchMatches(uiText(settingsLabel(it)), uiText(settingsDescriptions[it].orEmpty()), query) }
    val focus = LocalFocusManager.current
    LazyColumn(Modifier.fillMaxSize().testTag("settings-overview"), contentPadding = PaddingValues(start = SettingsPagePadding, end = SettingsPagePadding, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(uiText("Search settings")) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, uiText("Clear search")) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().testTag("settings-search"))
        }
        if (query.isNotBlank()) {
            item { if (matches.isEmpty()) EmptyState(uiText("No matching settings"), uiText("Try another word."), Icons.Rounded.SearchOff, action = uiText("Clear search"), onAction = { query = "" }) else Glass { Column { matches.forEach { name -> SettingsRow(name, summaries[name].orEmpty(), selected = selectedPage?.let { settingsRoute(name) == it }) { onOpen(settingsRoute(name)) } } } } }
        } else if (category == "Advanced") {
            item { SettingsGroup("Tools and services", settingsCategories.keys.toList(), summaries, selectedPage = selectedPage, onOpen = onOpen) }
        } else if (category.isNotBlank()) {
            item { Glass { Column { settingsCategories[category].orEmpty().forEachIndexed { index, name -> if (index > 0) HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)); SettingsRow(name, summaries[name].orEmpty(), selected = selectedPage?.let { settingsRoute(name) == it }) { onOpen(settingsRoute(name)) } } } } }
        } else {
            // One item: every hub row stays composed, so scroll-to and screen readers reach all of them.
            item { Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { settingsHubSections.forEach { (title, names) -> SettingsGroup(title, names, summaries, selectedPage = selectedPage, onOpen = onOpen) } } }
        }
    }
}

/**
 * Overlays the chat record the ViewModel actually holds. A record that is absent, failed (`_error`) or still loading
 * (`_loading`) contributes nothing, and a key the record lacks stays unknown: no default is ever substituted, so the row
 * keeps its static description instead. Voice selection and key state come from phone storage, never the server record.
 */
internal fun SettingsSnapshot.withLoaded(chat: JSONObject?): SettingsSnapshot {
    fun JSONObject?.usable() = this?.takeIf { it.string("_error").isBlank() && !it.optBoolean("_loading") }
    val preferences = chat.usable()
    return copy(
        sendKey = preferences?.string("send_key").orEmpty(),
        formatMessages = preferences?.opt("render_user_markdown") as? Boolean,
        showThinking = preferences?.opt("show_thinking") as? Boolean,
        autoFollow = preferences?.opt("auto_scroll_follow") as? Boolean,
        animateBackground = preferences?.opt("animate_background") as? Boolean,
    )
}

/** Reads the same local state as the focused pages, so a summary never claims more than the page it opens will show. */
@Composable private fun SettingsHome(vm: BeaconViewModel, category: String = "", selectedPage: String? = null) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val update by vm.app.updates.state.collectAsStateWithLifecycle()
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val profileLoading = ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading"))
    LaunchedEffect(ui.origin, ui.owner, ui.connected) { if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    val context = LocalContext.current.applicationContext
    var keepConnectedEnabled by remember(context) { mutableStateOf(KeepConnectedService.isEnabled(context)) }
    DisposableEffect(context) {
        val preferences = context.getSharedPreferences(KeepConnectedService.PREFS, android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KeepConnectedService.KEY_ENABLED) keepConnectedEnabled = KeepConnectedService.isEnabled(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var voicePreferencesVersion by remember(context) { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val preferences = context.getSharedPreferences(VoiceKeyStore.PREFS, android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> voicePreferencesVersion++ }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val phoneVoice by produceState<Pair<String, Boolean>?>(null, context, voicePreferencesVersion) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val store = VoiceKeyStore(context)
                val provider = store.settings().provider
                provider to store.hasKey(provider)
            }.getOrNull()
        }
    }
    val snapshot = SettingsSnapshot(
        connected = ui.connected, profile = profileDisplayLabel(ui.profile, profileRows, profileLoading),
        theme = prefs.theme, colorStyle = prefs.skin,
        installedVersion = BuildConfig.VERSION_NAME, updateName = update.release?.name.orEmpty(), updateReady = update.phase == UpdatePhase.NeedsPermission || update.phase == UpdatePhase.Installing,
        voiceProviderId = phoneVoice?.first.orEmpty(), voiceKeySaved = phoneVoice?.second,
        keepConnectedEnabled = keepConnectedEnabled,
        assistantName = prefs.assistantName, assistantProfile = profileDisplayLabel(prefs.assistantProfile, profileRows, profileLoading),
    ).withLoaded(data["profile-settings"])
    val summaries = localizedSettingsSummaries(snapshot)
    SettingsOverview(summaries, { vm.more(it) }, category, selectedPage)
}

@Composable fun MoreScreen(vm: BeaconViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val restoringProfile by vm.settingsProfileRestoring.collectAsStateWithLifecycle()
    LaunchedEffect(vm, restoringProfile, ui.connected, ui.owner, ui.profile, ui.morePage, vm.client) { if (ui.connected && restoringProfile) vm.restoreSettingsProfile() }
    val page = normalizeSettingsPage(ui.morePage)
    val pluginStatus = ui.bootstrap.optJSONObject("optional_features")?.optString("plugin").orEmpty()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(360.dp).fillMaxHeight().testTag("settings-wide-list")) {
                    Header(uiText("Settings"), menu = LocalOpenNavigationMenu.current)
                    Box(Modifier.weight(1f).fillMaxWidth()) { SettingsHome(vm, selectedPage = page) }
                }
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight().testTag("settings-wide-detail")) {
                        if (page.isBlank()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyState(uiText("Choose a setting"), uiText("Pick one from the list to see it here."), Icons.Rounded.Tune, centered = true)
                        } else MoreSettingsRoute(vm, ui, page, pluginStatus)
                    }
                }
            }
        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight()) {
                MoreSettingsRoute(vm, ui, page, pluginStatus)
            }
        }
    }
    NativeCatalog(vm, containedActions = true)
}

@Composable private fun MoreSettingsRoute(vm: BeaconViewModel, ui: AppState, page: String, pluginStatus: String) {
    if (settingsNeedsBeaconPlugin(page, pluginStatus)) {
        androidx.activity.compose.BackHandler { vm.backFromSettings() }
        Column(Modifier.fillMaxSize()) {
            Header(uiText(settingsLabel(page)), back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { MissingBeaconPluginState() }
        }
        return
    }
    val remotePage = page in (settingsCategories.values.flatten() + listOf("Skills", "Memory", "Beacon", "Notifications", "Bots", "Profiles", "Commands", "Session usage")) && page !in setOf("Beacon", "App updates")
    if (remotePage && vm.client == null && !ui.loading) {
        androidx.activity.compose.BackHandler { vm.backFromSettings() }
        Column(Modifier.fillMaxSize()) {
            Header(uiText(settingsLabel(page)), back = if (page in setOf("Skills", "Memory", "Bots")) null else ({ vm.backFromSettings() }), menu = LocalOpenNavigationMenu.current)
            EmptyState(uiText("Reconnect to Hermes"), uiText("Check your network, then retry. Your saved drafts stay on this phone."), action = uiText("Reconnect"), onAction = { vm.connect(ui.origin) })
        }
        return
    }
    val restoringProfile by vm.settingsProfileRestoring.collectAsStateWithLifecycle()
    val restoreError by vm.settingsProfileRestoreError.collectAsStateWithLifecycle()
    if (restoringProfile && page in setOf("Skills", "Memory", "Profiles")) {
        androidx.activity.compose.BackHandler { vm.backFromSettings() }
        Column(Modifier.fillMaxSize()) {
            Header(uiText(settingsLabel(page)), back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current)
            if (restoreError.isBlank()) LinearProgressIndicator(Modifier.fillMaxWidth())
            else EmptyState(uiText("Profile settings"), uiText(restoreError), action = uiText("Retry"), onAction = vm::restoreSettingsProfile)
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
            Header(uiText(if (page.isBlank()) "Settings" else page.removePrefix("category:")), back = if (page.isBlank()) null else ({ vm.backFromSettings() }), menu = LocalOpenNavigationMenu.current)
            SettingsHome(vm, page.removePrefix("category:"))
        }
        page == "Settings" -> NativeChatPreferences(vm)
        page in setOf("Appearance", "Assistant", "Connection", "Beacon", "Notifications", "App updates", "Licenses") -> Column(Modifier.fillMaxSize()) {
            // Fixed route names only reach this branch; Skills/Bots are handled above and keep their own headers.
            Header(uiText(settingsLabel(page)), back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current)
            when (page) {
                "Beacon" -> BeaconSettings(vm, pluginStatus == "absent")
                "Assistant" -> AssistantSettings(vm)
                "Appearance" -> AppearanceSettings(vm)
                "Connection" -> ConnectionSettings(vm, pluginStatus == "absent")
                "Notifications" -> NotificationSettings(vm)
                "App updates" -> AppUpdates(vm)
                "Licenses" -> LicenseScreen(vm)
            }
        }
        else -> NativeAdministration(vm, page)
    }
}

@Composable private fun NativeChatPreferences(vm: BeaconViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val busyOverride by vm.busySendOverride.collectAsStateWithLifecycle()
    val settings = data["profile-settings"] ?: LocalBeaconSettings.current
    Column(Modifier.fillMaxSize()) {
        Header(uiText(settingsLabel("Settings")), back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current)
        LazyColumn(contentPadding = PaddingValues(SettingsPagePadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Choice(assistantText("While Beacon is replying"), busyOverride ?: preferences.busySendAction, listOf("steer", "queue"), { vm.setBusySendAction(it) }, optionLabels = mapOf("steer" to "Steer", "queue" to "Queue"), enabled = preferences.busySendLoaded) }
            item { Choice("Send shortcut", settings.string("send_key", "enter"), listOf("enter", "ctrl_enter"), { vm.nativePreferences(json("send_key" to it)) }, optionLabels = mapOf("enter" to "Enter", "ctrl_enter" to "Ctrl + Enter")) }
            item { LabeledToggle(uiText("Format your messages"), settings.optBoolean("render_user_markdown", true), { vm.nativePreferences(json("render_user_markdown" to it)) }) }
            item { LabeledToggle(uiText("Show thinking"), settings.optBoolean("show_thinking", true), { vm.nativePreferences(json("show_thinking" to it)) }) }
            item { LabeledToggle(uiText("Auto-follow streaming"), settings.optBoolean("auto_scroll_follow", true), { vm.nativePreferences(json("auto_scroll_follow" to it)) }) }
            item { LabeledToggle(uiText("Background animation"), settings.optBoolean("animate_background", true), { vm.nativePreferences(json("animate_background" to it)) }) }
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
        if (visible < keys.size) TextButton(onClick = { visible += 24 }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Show more")) }
    }
}
@Composable private fun StructuredReadValue(value: Any, depth: Int = 0) {
    when {
        depth > 8 -> Text(uiText("More details are available on your computer."), style = MaterialTheme.typography.bodySmall)
        value is JSONObject -> {
            var expanded by remember(value) { mutableStateOf(depth <= 1) }
            Column {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText(if (expanded) "Hide details" else "Show details")) }
                if (expanded) StructuredRead(value, depth)
            }
        }
        value is JSONArray -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var visible by remember(value) { mutableIntStateOf(24) }
            if (value.length() == 0) Text(uiText("No entries"), style = MaterialTheme.typography.bodySmall)
            (0 until minOf(value.length(), visible)).forEach { index -> StructuredReadValue(value.opt(index) ?: "", depth + 1) }
            if (visible < value.length()) TextButton(onClick = { visible += 24 }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Show more")) }
        }
        else -> SelectionContainer { Text(value.toString(), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable private fun ConnectionSettings(vm: BeaconViewModel, pluginAbsent: Boolean) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val profileLabel = profileDisplayLabel(ui.profile, profileRows, ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading")))
    LaunchedEffect(ui.origin, ui.owner, ui.connected) { if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    var origin by rememberSaveable(ui.origin) { mutableStateOf(ui.origin) }
    val signOutTitle = uiText("Sign out of Beacon")
    val signOutExplanation = uiText("Remove the saved Hermes sign-in from this phone. Your encrypted drafts stay here.")
    val signOutLabel = uiText("Sign out")
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(SettingsPagePadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText(if (ui.connected) "Connected" else "Connection needs attention"), style = MaterialTheme.typography.titleLarge)
            Text("${uiText("Signed-in account")} · ${ui.owner.ifBlank { uiText("Not signed in") }}", style = MaterialTheme.typography.bodyLarge)
            Text("${uiText("Profile")} · $profileLabel", style = MaterialTheme.typography.bodyLarge)
            if (!ui.connected) Text(uiText("Check your network, then retry."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        OutlinedTextField(origin, { origin = it }, label = { Text(uiText("Server address")) }, supportingText = { Text(uiText("Your Hermes address, starting with https://")) }, enabled = !ui.loading, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (ui.error.isNotBlank()) Text(uiText(ui.error), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { vm.reopenSetupWizard(origin) }, enabled = !ui.loading && origin.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(uiText("Change server")) }
        OutlinedButton(onClick = { vm.openForm(ActionForm(signOutTitle, "", emptyList(), explanation = signOutExplanation, actionLabel = signOutLabel, onResult = { vm.logout() })) }, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(signOutLabel) }
        DevicesSettingsSection(vm, pluginAbsent)
    }
}
/** Settings > App updates. The state lives in [dev.hermes.android.updates.UpdateManager], so a download goes on while this page is closed. */
@Composable private fun AppUpdates(vm: BeaconViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val call by vm.app.calls.state.collectAsStateWithLifecycle()
    val incoming by vm.incomingBusy.collectAsStateWithLifecycle()
    val pluginAbsent = ui.bootstrap.optJSONObject("optional_features")?.optString("plugin") == "absent"
    AppUpdateScreen(vm.app.updates, vm.client, ui.connected, pluginAbsent, callBusy = call.active || incoming || call.state == "ending")
}

@Composable private fun AppearanceSettings(vm: BeaconViewModel) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    // Each style's own primary and background for the current theme; rows show without dots until they are read.
    val dots by produceState<Map<String, Pair<Color, Color>>>(emptyMap(), dark) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val palettes = context.assets.open("native-skins.json").bufferedReader().use { JSONObject(it.readText()) }
                NativeSkins.mapNotNull { skin ->
                    val values = palettes.optJSONObject(skin)?.optJSONObject(if (dark) "dark" else "light") ?: return@mapNotNull null
                    nativeSkinColorScheme(values, dark).let { skin to (it.primary to it.background) }
                }.toMap()
            }.getOrDefault(emptyMap())
        }
    }
    // Text size, language, text direction and motion follow the phone's own settings.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(SettingsPagePadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Glass { Column(Modifier.padding(20.dp)) {
            Choice("Theme", prefs.theme, listOf("system", "dark", "light"), { vm.nativePreferences(json("theme" to it)) }, optionLabels = settingsThemeLabels)
        } } }
        item { Text(uiText("Color style"), Modifier.padding(start = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Glass { Column(Modifier.selectableGroup()) { NativeSkins.forEachIndexed { index, skin ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            val chosen = skin == prefs.skin
            ListItem(
                headlineContent = { Text(uiText(skinLabels.getValue(skin)), style = MaterialTheme.typography.bodyLarge) },
                leadingContent = { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val colors = dots[skin]
                    if (colors == null) Spacer(Modifier.width(40.dp))
                    else listOf(colors.first, colors.second).forEach { dot -> Box(Modifier.size(18.dp).clip(CircleShape).background(dot).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)) }
                } },
                trailingContent = { if (chosen) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = chosen, role = Role.RadioButton) { vm.nativePreferences(json("skin" to skin)) }.testTag("skin-option:$skin"),
            )
        } } } }
    }
}
