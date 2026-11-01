@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.ui

import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.AppState
import dev.hermes.android.BuildConfig
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.updates.AppUpdateScreen
import dev.hermes.android.updates.PluginRow
import dev.hermes.android.updates.PluginStatus
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
    "Server" -> Icons.Rounded.SettingsSuggest
    "Health" -> Icons.Rounded.MonitorHeart
    "Logs" -> Icons.AutoMirrored.Rounded.Notes
    "Server updates" -> Icons.Rounded.SystemUpdateAlt
    "Session checkpoints" -> Icons.Rounded.Restore
    "Profiles & model", "Profiles" -> ProfilesIcon
    "Providers & models" -> Icons.Rounded.Tune
    "Tools & MCP", "Tools & commands" -> Icons.Rounded.Build
    "MCP servers" -> Icons.Rounded.Hub
    "About & licenses", "Licenses" -> Icons.Rounded.Info
    "Connections", "Connection" -> Icons.Rounded.Link
    "Devices" -> Icons.Rounded.PhoneAndroid
    "Skills" -> SkillsIcon
    "Memory" -> MemoryIcon
    else -> Icons.Rounded.FolderOpen
}

/** Touch floor of every Settings row; UI tests assert against this same value. */
internal val SettingsRowMinHeight = 78.dp

/** The overview's row summaries: fixed words through [uiText], user data (profile, version, language name) verbatim. */
@Composable internal fun localizedSettingsSummaries(snapshot: SettingsSnapshot): Map<String, SettingsRowSummary> =
    settingsSearchRoutes.associateWith { route -> settingsRowSummary(settingsSummary(route, snapshot)) { uiText(it) } }

/** [summary] is the row's current value and state pill, already localized; a row with neither keeps its static description. */
@Composable private fun SettingsRow(name: String, summary: SettingsRowSummary, compatibilityTag: String? = null, selected: Boolean? = null, onClick: () -> Unit) {
    val pill = summary.status != null && summary.statusLabel.isNotBlank()
    SettingsNavRow(
        label = uiText(settingsLabel(name)), onClick = onClick, icon = settingsIcon(name),
        value = summary.value.ifBlank { if (pill) "" else uiText(settingsDescriptions[name].orEmpty()) },
        status = summary.status.takeIf { pill }, statusLabel = summary.statusLabel,
        selected = selected, minHeight = SettingsRowMinHeight, valueMaxLines = 2,
        testTag = compatibilityTag ?: "settings-row:$name",
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
@Composable private fun SettingsRows(names: List<String>, summaries: Map<String, SettingsRowSummary>, selectedPage: String?, onOpen: (String) -> Unit, tagged: Boolean = true) {
    names.forEachIndexed { index, name ->
        if (index > 0) SettingsDivider()
        SettingsRow(name, summaries[name] ?: SettingsRowSummary(), if (tagged) hubCompatibilityTag(name) else null, selected = selectedPage?.let { settingsRoute(name) == it }) { onOpen(settingsRoute(name)) }
    }
}
@Composable private fun SettingsGroup(title: String, names: List<String>, summaries: Map<String, SettingsRowSummary>, modifier: Modifier = Modifier, selectedPage: String? = null, onOpen: (String) -> Unit) {
    SettingsSection(if (title.isBlank()) "" else uiText(title), modifier, testTag = "settings-group:$title") { SettingsRows(names, summaries, selectedPage, onOpen) }
}
/**
 * Stateless Settings overview: current values at a glance, one tap to the focused page. [onOpen] receives the exact
 * `BeaconViewModel.more` argument, [summaries] maps a row name to its localized value and pill (absent = the row's description).
 */
@Composable internal fun SettingsOverview(summaries: Map<String, SettingsRowSummary>, onOpen: (String) -> Unit, category: String = "", selectedPage: String? = null) {
    var query by rememberSaveable(category) { mutableStateOf("") }
    val matches = settingsSearchRoutes.filter { route ->
        settingsRowMatches(route, uiText(settingsLabel(route)), uiText(settingsDescriptions[route].orEmpty()), settingsKeywordsOf(route).takeIf(String::isNotBlank)?.let { uiText(it) }.orEmpty(), query)
    }
    val focus = LocalFocusManager.current
    SettingsList(testTag = "settings-overview") {
        item {
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(uiText("Search settings")) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, uiText("Clear search")) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().testTag("settings-search"))
        }
        if (query.isNotBlank()) {
            item {
                SettingsSection(testTag = "settings-results") {
                    if (matches.isEmpty()) SettingsEmpty(uiText("No matching settings"), Icons.Rounded.SearchOff, action = SettingsAction(uiText("Clear search"), { query = "" }))
                    else SettingsRows(matches, summaries, selectedPage, onOpen, tagged = false)
                }
            }
        } else if (category == "Advanced") {
            item { SettingsGroup("Tools and services", settingsCategories.keys.toList(), summaries, selectedPage = selectedPage, onOpen = onOpen) }
        } else if (category.isNotBlank()) {
            item { SettingsSection(testTag = "settings-category:$category") { SettingsRows(settingsCategories[category].orEmpty(), summaries, selectedPage, onOpen, tagged = false) } }
        } else {
            // One item: every hub row stays composed, so scroll-to and screen readers reach all of them.
            item { Column(verticalArrangement = Arrangement.spacedBy(SettingsKit.SectionGap)) { settingsHubSections.forEach { (title, names) -> SettingsGroup(title, names, summaries, selectedPage = selectedPage, onOpen = onOpen) } } }
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

/** A cached list record that is loaded and not failed; anything else is unknown, never an empty count. */
private fun JSONObject?.loadedRecord() = this?.takeIf { recordError(it).isBlank() && !it.optBoolean("_loading") }

/** The one pending plugin step the hub's App updates row points at, or blank when the plugin needs nothing. */
internal fun pluginActionWord(plugin: PluginRow): String = when {
    !plugin.actionable -> ""
    plugin.status in setOf(PluginStatus.Restart, PluginStatus.Waiting, PluginStatus.RestartFailed) -> "Restart Hermes"
    plugin.status == PluginStatus.DashboardFailed -> "Restart dashboard"
    else -> "Update the Beacon plugin"
}

/** Reads the same local state as the focused pages, so a summary never claims more than the page it opens will show. */
@Composable private fun SettingsHome(vm: BeaconViewModel, category: String = "", selectedPage: String? = null) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val update by vm.app.updates.state.collectAsStateWithLifecycle()
    val skills by vm.phoneSettings.skills.collectAsStateWithLifecycle()
    val server by vm.phoneSettings.server.collectAsStateWithLifecycle()
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
    val tools = data["native-tools"].loadedRecord()?.rows("items")
    val mcp = data["native-mcp"].loadedRecord()?.rows("servers")
    val checkpointSize = if (server.checkpointBytes > 0) Formatter.formatShortFileSize(context, server.checkpointBytes) else ""
    // The Connection page's own verdict decides the row too, so the two never disagree.
    val verdict = connectionVerdict(ui.connected, ui.loading || ui.restoringSession, ui.authenticated, ui.origin.isNotBlank())
    val snapshot = SettingsSnapshot(
        connected = ui.connected, connecting = verdict == ConnectionVerdict.Checking, profile = if (profileLoading) "" else profileDisplayName(ui.profile, profileRows),
        theme = prefs.theme, colorStyle = prefs.skin,
        installedVersion = BuildConfig.VERSION_NAME, updateName = update.release?.name.orEmpty(), updateReady = update.phase == UpdatePhase.NeedsPermission || update.phase == UpdatePhase.Installing,
        voiceProviderId = phoneVoice?.first.orEmpty(), voiceKeySaved = phoneVoice?.second,
        keepConnectedEnabled = keepConnectedEnabled,
        assistantName = prefs.assistantName, assistantProfile = profileDisplayLabel(prefs.assistantProfile, profileRows, profileLoading),
        account = ui.owner, signedOut = verdict == ConnectionVerdict.SignedOut,
        pluginAction = pluginActionWord(update.plugin),
        profileCount = profileRows.size.takeIf { !profileLoading && profileRows.isNotEmpty() },
        skillsOn = skills.skills.takeIf { it.isNotEmpty() && skills.profile == ui.profile }?.count { it.enabled },
        toolsOn = tools?.count { it.optBoolean("enabled") }, mcpServers = mcp?.size,
        serverOverall = server.overall,
        serverUpdateAvailable = server.update?.optBoolean("update_available"),
        checkpointSize = checkpointSize,
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

/** A route's header with the body below it, for the states that replace a page (missing plugin, reconnect, restoring). */
@Composable private fun SettingsStatePage(vm: BeaconViewModel, page: String, back: Boolean = true, refreshing: Boolean = false, content: LazyListScope.() -> Unit) {
    androidx.activity.compose.BackHandler { vm.backFromSettings() }
    Column(Modifier.fillMaxSize()) {
        Header(uiText(settingsLabel(page)), back = if (back) ({ vm.backFromSettings() }) else null, menu = LocalOpenNavigationMenu.current)
        SettingsList(Modifier.weight(1f), refreshing = refreshing, content = content)
    }
}

@Composable private fun MoreSettingsRoute(vm: BeaconViewModel, ui: AppState, page: String, pluginStatus: String) {
    if (settingsNeedsBeaconPlugin(page, pluginStatus)) {
        SettingsStatePage(vm, page) { item { SettingsSection(testTag = "settings-plugin-missing") { MissingBeaconPluginState(compact = true) } } }
        return
    }
    val remotePage = page in (settingsCategories.values.flatten() + listOf("Skills", "Memory", "Beacon", "Notifications", "Bots", "Profiles", "Commands", "Session usage")) && page !in setOf("Beacon", "App updates")
    if (remotePage && vm.client == null && !ui.loading) {
        SettingsStatePage(vm, page, back = page !in setOf("Skills", "Memory", "Bots")) {
            item {
                SettingsStatusHero(SettingsStatus.Error, uiText("Reconnect to Hermes"), line = uiText("Check your network"),
                    primary = SettingsAction(uiText("Reconnect"), { vm.connect(ui.origin) }, testTag = "settings-reconnect"))
            }
        }
        return
    }
    val restoringProfile by vm.settingsProfileRestoring.collectAsStateWithLifecycle()
    val restoreError by vm.settingsProfileRestoreError.collectAsStateWithLifecycle()
    if (restoringProfile && page in setOf("Skills", "Memory", "Profiles")) {
        SettingsStatePage(vm, page, refreshing = restoreError.isBlank()) {
            item {
                SettingsSection(uiText("Profile settings"), testTag = "settings-restoring") {
                    if (restoreError.isBlank()) SettingsSkeletonRows(2)
                    else SettingsErrorRow(uiText(restoreError), vm::restoreSettingsProfile, uiText("Retry"), retryTestTag = "settings-restore-retry")
                }
            }
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

/** The option tags tests and saved layouts know: English label and stored value, whatever language or assistant name the row shows. */
private fun choiceTags(label: String, options: List<String>) = options.associateWith { "choice-option:$label:$it" }

@Composable private fun NativeChatPreferences(vm: BeaconViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val busyOverride by vm.busySendOverride.collectAsStateWithLifecycle()
    val record = data["profile-settings"]
    val settings = record ?: LocalBeaconSettings.current
    val failed = recordError(record).isNotBlank()
    val busyOptions = listOf("steer", "queue")
    val keyOptions = listOf("enter", "ctrl_enter")
    Column(Modifier.fillMaxSize()) {
        Header(uiText(settingsLabel("Settings")), back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current)
        SettingsList(refreshing = record?.optBoolean("_loading") == true, testTag = "chat-preferences") {
            if (failed) item { SettingsSection(testTag = "chat-preferences-error") { SettingsErrorRow(uiText("Couldn't load chat settings"), null, "") } }
            item {
                SettingsSection(uiText("When you send"), testTag = "settings-section:Sending") {
                    // The stored values stay steer and queue, and the option tags keep the old English label, so saved choices and tests still find them.
                    SettingsChoiceRow(
                        assistantText("If you send while Beacon replies"), busyOverride ?: preferences.busySendAction, busyOptions, { vm.setBusySendAction(it) },
                        icon = Icons.AutoMirrored.Rounded.CallSplit, optionLabels = mapOf("steer" to "Change the reply", "queue" to "Send after"),
                        optionIcons = mapOf("steer" to Icons.AutoMirrored.Rounded.CallSplit, "queue" to Icons.AutoMirrored.Rounded.PlaylistAdd),
                        optionTestTags = choiceTags("While Beacon is replying", busyOptions), enabled = preferences.busySendLoaded, style = ChoiceStyle.Segmented, fitSegments = true,
                    )
                    SettingsDivider()
                    SettingsChoiceRow(
                        uiText("Send shortcut"), settings.string("send_key", "enter"), keyOptions, { vm.nativePreferences(json("send_key" to it)) },
                        icon = Icons.Rounded.Keyboard, optionLabels = mapOf("enter" to "Enter", "ctrl_enter" to "Ctrl + Enter"),
                        optionTestTags = choiceTags("Send shortcut", keyOptions), enabled = !failed, style = ChoiceStyle.Segmented, fitSegments = true,
                    )
                }
            }
            item {
                SettingsSection(uiText("Display")) {
                    SettingsSwitchRow(uiText("Format your messages"), settings.optBoolean("render_user_markdown", true), { vm.nativePreferences(json("render_user_markdown" to it)) }, icon = Icons.Rounded.TextFormat, enabled = !failed, testTag = "chat-pref-format")
                    SettingsDivider()
                    SettingsSwitchRow(uiText("Show thinking"), settings.optBoolean("show_thinking", true), { vm.nativePreferences(json("show_thinking" to it)) }, icon = Icons.Rounded.Psychology, enabled = !failed, testTag = "chat-pref-thinking")
                    SettingsDivider()
                    SettingsSwitchRow(uiText("Follow new replies"), settings.optBoolean("auto_scroll_follow", true), { vm.nativePreferences(json("auto_scroll_follow" to it)) }, icon = Icons.Rounded.VerticalAlignBottom, enabled = !failed, testTag = "chat-pref-follow")
                    SettingsDivider()
                    SettingsSwitchRow(uiText("Background animation"), settings.optBoolean("animate_background", true), { vm.nativePreferences(json("animate_background" to it)) }, icon = Icons.Rounded.AutoAwesome, enabled = !failed, testTag = "chat-pref-animation")
                }
            }
        }
    }
}

@Composable fun StructuredRead(value: JSONObject, depth: Int = 0) {
    val keys = remember(value) { value.keys().asSequence().filter { !it.startsWith('_') && !privateStructuredKey(it) }.toList() }
    var visible by remember(value) { mutableIntStateOf(24) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        keys.take(visible).forEach { key ->
            val item = value.opt(key)
            if (item != JSONObject.NULL && item != null && (item is JSONObject || item is JSONArray || item.toString().isNotBlank())) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(key.replace('_', ' ').replaceFirstChar(Char::titlecase), style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StructuredReadValue(item, depth + 1)
                }
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
                if (expanded) Box(Modifier.padding(start = 12.dp)) { StructuredRead(value, depth) }
            }
        }
        value is JSONArray -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var visible by remember(value) { mutableIntStateOf(24) }
            if (value.length() == 0) Text(uiText("No entries"), style = MaterialTheme.typography.bodySmall)
            (0 until minOf(value.length(), visible)).forEach { index -> StructuredReadValue(value.opt(index) ?: "", depth + 1) }
            if (visible < value.length()) TextButton(onClick = { visible += 24 }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Show more")) }
        }
        else -> SelectionContainer { Text(value.toString(), style = MaterialTheme.typography.bodyLarge) }
    }
}

/** Where the phone stands with Hermes. Signed out and checking get their own verdicts so "Offline" never blames the network for a missing sign-in. */
internal enum class ConnectionVerdict { Connected, Checking, SignedOut, Offline }
internal fun connectionVerdict(connected: Boolean, working: Boolean, authenticated: Boolean, hasAddress: Boolean): ConnectionVerdict = when {
    connected -> ConnectionVerdict.Connected
    working -> ConnectionVerdict.Checking
    hasAddress && !authenticated -> ConnectionVerdict.SignedOut
    else -> ConnectionVerdict.Offline
}
/** The address without its scheme or path, as people say it; a value that is not a URL is returned as typed. */
internal fun settingsHost(address: String): String {
    val trimmed = address.trim()
    return runCatching { java.net.URI(trimmed).authority }.getOrNull()?.takeIf(String::isNotBlank) ?: trimmed.removeSuffix("/")
}

@Composable private fun ConnectionSettings(vm: BeaconViewModel, pluginAbsent: Boolean) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val profileLoading = ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading"))
    val profileName = if (profileLoading) "" else profileDisplayName(ui.profile, profileRows)
    LaunchedEffect(ui.origin, ui.owner, ui.connected) { if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    var origin by rememberSaveable(ui.origin) { mutableStateOf(ui.origin) }
    var editing by rememberSaveable(ui.origin) { mutableStateOf(false) }
    val signOutTitle = uiText("Sign out of Beacon")
    val signOutExplanation = uiText("Remove the saved Hermes sign-in from this phone. Your encrypted drafts stay here.")
    val signOutLabel = uiText("Sign out")
    val host = settingsHost(ui.origin)
    val verdict = connectionVerdict(ui.connected, ui.loading || ui.restoringSession, ui.authenticated, ui.origin.isNotBlank())
    val status = when (verdict) {
        ConnectionVerdict.Connected -> SettingsStatus.Ok
        ConnectionVerdict.Checking -> SettingsStatus.Working
        ConnectionVerdict.SignedOut -> SettingsStatus.Attention
        ConnectionVerdict.Offline -> SettingsStatus.Error
    }
    val title = uiText(when (verdict) {
        ConnectionVerdict.Connected -> "Connected"
        ConnectionVerdict.Checking -> "Reconnecting…"
        ConnectionVerdict.SignedOut -> "Signed out"
        ConnectionVerdict.Offline -> "Can't reach Hermes"
    })
    val motion = rememberNativeMotionEnabled()
    SettingsList(testTag = "settings-connection-page") {
        item {
            SettingsStatusHero(
                status, title, line = if (verdict == ConnectionVerdict.Offline) uiText("Check your network") else host, lineTestTag = "connection-host",
                // An address is read from both ends: one line, the middle cut, so the port always shows.
                lineMaxLines = 1, lineOverflow = TextOverflow.MiddleEllipsis,
                meta = if (ui.owner.isBlank() && profileName.isBlank()) null else ({
                    if (ui.owner.isNotBlank()) SettingsBadge(ui.owner, testTag = "connection-account")
                    if (profileName.isNotBlank()) SettingsBadge(profileName, testTag = "connection-profile")
                }),
                primary = if (verdict == ConnectionVerdict.Connected) null else SettingsAction(uiText("Reconnect"), { vm.connect(ui.origin) }, enabled = ui.origin.isNotBlank(), loading = verdict == ConnectionVerdict.Checking, testTag = "connection-reconnect"),
            )
        }
        item {
            SettingsSection(uiText("Server"), testTag = "settings-section:Server") {
                Column(Modifier.animateContentSize(tween(if (motion) 200 else 0))) {
                    SettingsNavRow(uiText("Server address"), { editing = !editing }, icon = Icons.Rounded.Dns, value = host, valueTestTag = "connection-server-value", testTag = "connection-server-row", valueOverflow = TextOverflow.MiddleEllipsis)
                    if (ui.error.isNotBlank()) { SettingsDivider(); SettingsErrorRow(uiText(ui.error), null, "", Modifier.testTag("connection-error")) }
                    if (editing) {
                        SettingsDivider()
                        SettingsTextFieldRow(uiText("Server address"), origin, { origin = it }, placeholder = "https://…", enabled = !ui.loading, fieldTestTag = "connection-address")
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                            FilledTonalButton(onClick = { vm.reopenSetupWizard(origin) }, enabled = !ui.loading && origin.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("connection-change-server")) { Text(uiText("Change server")) }
                        }
                    }
                }
            }
        }
        item { PluginHealthSection(vm, pluginAbsent) }
        item { DevicesSettingsSection(vm, pluginAbsent) }
        item {
            SettingsSection(testTag = "settings-section:sign-out") {
                SettingsDestructiveRow(signOutLabel, { vm.openForm(ActionForm(signOutTitle, "", emptyList(), explanation = signOutExplanation, actionLabel = signOutLabel, onResult = { vm.logout() })) },
                    icon = Icons.AutoMirrored.Rounded.Logout, testTag = "connection-sign-out")
            }
        }
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
    AppearanceContent(prefs.theme, prefs.skin, rememberAppearanceSwatches(), { vm.nativePreferences(json("theme" to it)) }, { vm.nativePreferences(json("skin" to it)) })
}

/** One tile per colour style, each in that style's own accent and background for the current theme; neutral tiles show until the palettes are read. */
@Composable internal fun rememberAppearanceSwatches(): List<SettingsSwatch> {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val colors by produceState<Map<String, Pair<Color, Color>>>(emptyMap(), dark) {
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
    return NativeSkins.map { skin -> SettingsSwatch(skin, uiText(skinLabels.getValue(skin)), colors[skin]?.first, colors[skin]?.second) }
}

/** The Appearance page: the theme (System, Dark, Light) and the colour style. Text size, language, text direction and motion follow the phone's own settings. */
@Composable internal fun AppearanceContent(theme: String, skin: String, swatches: List<SettingsSwatch>, onTheme: (String) -> Unit, onSkin: (String) -> Unit) {
    val themes = listOf("system", "dark", "light")
    SettingsList(testTag = "appearance-settings") {
        item {
            SettingsSection(testTag = "settings-section:Theme") {
                SettingsChoiceRow(
                    uiText("Theme"), theme, themes, onTheme,
                    icon = Icons.Rounded.Palette, optionLabels = settingsThemeLabels,
                    optionIcons = mapOf("system" to Icons.Rounded.BrightnessAuto, "dark" to Icons.Rounded.DarkMode, "light" to Icons.Rounded.LightMode),
                    optionTestTags = choiceTags("Theme", themes), style = ChoiceStyle.Auto, fitSegments = true,
                )
            }
        }
        item {
            SettingsSection(uiText("Color style"), testTag = "settings-section:Color style") {
                SettingsSwatchPicker(swatches, skin, onSkin)
            }
        }
    }
}
