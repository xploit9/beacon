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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BuildConfig
import dev.hermes.android.CypherApplication
import dev.hermes.android.CypherViewModel
import dev.hermes.android.isMissingCypherPluginRoute
import dev.hermes.android.core.*
import dev.hermes.android.data.CypherClient
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.updates.HermesPluginUpdater
import dev.hermes.android.updates.PluginUpdateRowState
import dev.hermes.android.updates.PluginVersions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun settingsParent(page: String): String = when {
    page.startsWith("category:") -> ""
    page == "Commands" -> "Tools & commands"
    else -> settingsCategories.entries.firstOrNull { page in it.value }?.let { "category:${it.key}" }.orEmpty()
}
private fun settingsIcon(name: String): ImageVector = when (name) {
    "Voice & calls", "Cypher" -> Icons.Rounded.GraphicEq
    "Assistant" -> Icons.Rounded.Face
    "Appearance" -> Icons.Rounded.Palette
    "Chat", "Settings" -> Icons.Rounded.ChatBubbleOutline
    "Notifications & Keep connected", "Notifications" -> Icons.Rounded.NotificationsNone
    "App updates" -> Icons.Rounded.SystemUpdate
    "Server", "Health", "Logs", "Server updates", "Session checkpoints" -> Icons.Rounded.SettingsSuggest
    "Profiles & model", "Profiles", "Providers & models" -> Icons.Rounded.AutoAwesome
    "Tools & MCP", "Tools & commands", "MCP servers" -> Icons.Rounded.Build
    "About & licenses", "Licenses" -> Icons.Rounded.Info
    "Connections", "Connection" -> Icons.Rounded.Link
    "Skills" -> Icons.Rounded.Extension
    "Memory" -> Icons.Rounded.Psychology
    else -> Icons.Rounded.FolderOpen
}

private fun pluginAutoUpdateLine(status: JSONObject): String {
    val message=status.optString("message").ifBlank {
        if (status.optString("result")=="never") {
            if (status.optBoolean("enabled",true)) "Automatic updates are on." else "Automatic updates are off."
        } else "Up to date."
    }
    val versions=listOfNotNull(
        status.optString("app_version").takeIf(String::isNotBlank)?.let { "App $it" },
        status.optString("plugin_version").takeIf(String::isNotBlank)?.let { "Plugin $it" },
    ).joinToString(" · ")
    val checked=status.optDouble("checked_at",0.0)
    val whenText=runCatching {
        if (checked<=0) "" else {
            val zone=ZoneId.systemDefault()
            val value=Instant.ofEpochMilli((checked*1000).toLong()).atZone(zone)
            val time=value.format(DateTimeFormatter.ofPattern("HH:mm",Locale.getDefault()))
            if (value.toLocalDate()==LocalDate.now(zone)) "Checked today $time"
            else "Checked ${value.format(DateTimeFormatter.ofPattern("MMM d HH:mm",Locale.getDefault()))}"
        }
    }.getOrDefault("")
    return listOf(whenText,versions,message).filter(String::isNotBlank).joinToString(" · ")
}

/** Touch floor of every Settings row; UI tests assert against this same value. */
internal val SettingsRowMinHeight = 78.dp

/** The overview's row summaries: fixed words through [uiText], user data (profile, version, language name) verbatim. */
@Composable internal fun localizedSettingsSummaries(snapshot: SettingsSnapshot): Map<String, String> =
    settingsSearchRoutes.associateWith { route -> settingsSummary(route, snapshot).map { if (it.translate) uiText(it.text) else it.text }.joinSummary() }

/** [summary] is the row's current value, already localized; a row without one keeps its static description. */
@Composable private fun SettingsRow(name: String, summary: String = "", compatibilityTag: String? = null, onClick: () -> Unit) {
    val detail = summary.ifBlank { uiText(settingsDescriptions[name].orEmpty()) }
    // One clickable node: ListItem merges its own text, so a wrapping clickable would be an unlabeled button for TalkBack.
    ListItem(
        headlineContent = { Text(uiText(settingsLabel(name)), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) },
        supportingContent = if (detail.isBlank()) null else ({ Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }),
        leadingContent = { Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .09f)) { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(settingsIcon(name), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) } } },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).fillMaxWidth().heightIn(min = SettingsRowMinHeight).testTag(compatibilityTag ?: "settings-row:$name"),
    )
}
/** Tags older tests and saved routes rely on for renamed hub rows. */
private fun hubCompatibilityTag(name: String): String? = when (name) {
    "Connection" -> "settings-connection"
    "Chat" -> "settings-row:Settings"
    "Voice & calls" -> "settings-row:Cypher"
    "Notifications & Keep connected" -> "settings-row:Notifications"
    "Profiles & model" -> "settings-row:Profiles"
    else -> null
}
@Composable private fun SettingsGroup(title: String, names: List<String>, summaries: Map<String, String>, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    Column(modifier.testTag("settings-group:$title"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title.isNotBlank()) Text(uiText(title), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Glass { Column { names.forEachIndexed { index, name ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            SettingsRow(name, summaries[name].orEmpty(), hubCompatibilityTag(name)) { onOpen(settingsRoute(name)) }
        } } }
    }
}
/**
 * Stateless Settings overview: current values at a glance, one tap to the focused page. [onOpen] receives the exact
 * `CypherViewModel.more` argument, [summaries] maps a row name to its localized current value (absent = no summary).
 */
@Composable internal fun SettingsOverview(summaries: Map<String, String>, onOpen: (String) -> Unit, category: String = "") {
    var query by rememberSaveable(category) { mutableStateOf("") }
    val matches = settingsSearchRoutes.filter { settingsSearchMatches(uiText(settingsLabel(it)), uiText(settingsDescriptions[it].orEmpty()), query) }
    LazyColumn(Modifier.fillMaxSize().testTag("settings-overview"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(uiText("Search settings")) }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().testTag("settings-search")) }
        if (query.isNotBlank()) {
            item { if (matches.isEmpty()) Text(uiText("No matching settings"), style = MaterialTheme.typography.bodyLarge) else Glass { Column { matches.forEach { name -> SettingsRow(name, summaries[name].orEmpty()) { onOpen(settingsRoute(name)) } } } } }
        } else if (category == "Advanced") {
            item { SettingsGroup("Tools and services", settingsCategories.keys.toList(), summaries, onOpen = onOpen) }
        } else if (category.isNotBlank()) {
            item { Glass { Column { settingsCategories[category].orEmpty().forEachIndexed { index, name -> if (index > 0) HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)); SettingsRow(name, summaries[name].orEmpty()) { onOpen(settingsRoute(name)) } } } } }
        } else {
            // One item: every hub row stays composed, so scroll-to and screen readers reach all of them.
            item { Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { settingsHubSections.forEach { (title, names) -> SettingsGroup(title, names, summaries, onOpen = onOpen) } } }
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
@Composable private fun SettingsHome(vm: CypherViewModel, category: String = "") {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val update by vm.appUpdate.collectAsStateWithLifecycle()
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
    val phoneVoice by produceState<Pair<String, Boolean>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val store = VoiceKeyStore(context)
                val provider = store.settings().provider
                provider to (store.maskedKey(provider) != null)
            }.getOrNull()
        }
    }
    val snapshot = SettingsSnapshot(
        connected = ui.connected, profile = profileDisplayLabel(ui.profile, profileRows, profileLoading),
        theme = prefs.theme, colorStyle = prefs.skin,
        installedVersion = BuildConfig.VERSION_NAME, updateName = update.release?.name.orEmpty(), updateReady = update.ready,
        voiceProviderId = phoneVoice?.first.orEmpty(), voiceKeySaved = phoneVoice?.second,
        keepConnectedEnabled = keepConnectedEnabled,
        assistantName = prefs.assistantName, assistantProfile = profileDisplayLabel(prefs.assistantProfile, profileRows, profileLoading),
    ).withLoaded(data["profile-settings"])
    val summaries = localizedSettingsSummaries(snapshot)
    SettingsOverview(summaries, { vm.more(it) }, category)
}

@Composable fun MoreScreen(vm: CypherViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val page = normalizeSettingsPage(ui.morePage)
    val pluginStatus = ui.bootstrap.optJSONObject("optional_features")?.optString("plugin").orEmpty()
    if (settingsNeedsCypherPlugin(page, pluginStatus)) {
        androidx.activity.compose.BackHandler { vm.backFromSettings() }
        Column(Modifier.fillMaxSize()) {
            Header(uiText(settingsLabel(page)), back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { MissingCypherPluginState() }
        }
        return
    }
    val remotePage = page in (settingsCategories.values.flatten() + listOf("Skills", "Memory", "Cypher", "Notifications", "Bots", "Profiles", "Commands", "Session usage")) && page !in setOf("Cypher", "App updates")
    if (remotePage && vm.client == null && !ui.loading) {
        androidx.activity.compose.BackHandler { vm.backFromSettings() }
        Column(Modifier.fillMaxSize()) {
            Header(uiText(settingsLabel(page)), back = if (page in setOf("Skills", "Memory", "Bots")) null else ({ vm.backFromSettings() }), menu = LocalOpenNavigationMenu.current)
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
            Header(uiText(if (page.isBlank()) "Settings" else page.removePrefix("category:")), back = if (page.isBlank()) null else ({ vm.backFromSettings() }), menu = LocalOpenNavigationMenu.current)
            SettingsHome(vm, page.removePrefix("category:"))
        }
        page == "Settings" -> NativeChatPreferences(vm)
        page in setOf("Appearance", "Assistant", "Connection", "Cypher", "Notifications", "App updates", "Licenses") -> Column(Modifier.fillMaxSize()) {
            // Fixed route names only reach this branch; Skills/Bots are handled above and keep their own headers.
            Header(uiText(settingsLabel(page)), back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current)
            when (page) {
                "Cypher" -> CypherSettings(vm, pluginStatus == "absent")
                "Assistant" -> AssistantSettings(vm)
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
        Header(uiText(settingsLabel("Settings")), back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current)
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Saved on this phone for the connected account and profile.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Choice("Send shortcut", settings.string("send_key", "enter"), listOf("enter", "ctrl_enter"), { vm.nativePreferences(json("send_key" to it)) }, optionLabels = mapOf("enter" to "Enter", "ctrl_enter" to "Ctrl + Enter")) }
            item { LabeledToggle("Format your messages", settings.optBoolean("render_user_markdown", true), { vm.nativePreferences(json("render_user_markdown" to it)) }, "Render Markdown while preserving the original source.") }
            item { LabeledToggle("Show thinking", settings.optBoolean("show_thinking", true), { vm.nativePreferences(json("show_thinking" to it)) }, "Show available reasoning disclosures. This does not change model reasoning effort.") }
            item { LabeledToggle("Auto-follow streaming", settings.optBoolean("auto_scroll_follow", true), { vm.nativePreferences(json("auto_scroll_follow" to it)) }, "Keep the latest streaming reply in view.") }
            item { LabeledToggle("Background animation", settings.optBoolean("animate_background", true), { vm.nativePreferences(json("animate_background" to it)) }, uiText("Turn it off to pause the Beacon sphere. Reduce motion can also pause it.")) }
            item { Text("While a reply runs, Queue waits for it and Guide steers it.", style = MaterialTheme.typography.bodyMedium) }
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
    val data by vm.data.collectAsStateWithLifecycle()
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val profileLabel = profileDisplayLabel(ui.profile, profileRows, ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading")))
    LaunchedEffect(ui.origin, ui.owner, ui.connected) { if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    var origin by rememberSaveable(ui.origin) { mutableStateOf(ui.origin) }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (ui.connected) "Connected privately" else "Connection needs attention", style = MaterialTheme.typography.titleLarge)
            Text("Signed-in account · ${ui.owner.ifBlank { "Not signed in" }}", style = MaterialTheme.typography.bodyLarge)
            Text("Profile · $profileLabel", style = MaterialTheme.typography.bodyLarge)
            Text("Keep Tailscale connected to reach your private Hermes server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        OutlinedTextField(origin, { origin = it }, label = { Text(uiText("Server address")) }, supportingText = { Text("Your private Hermes address, starting with https://") }, enabled = !ui.loading, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (ui.error.isNotBlank()) Text(ui.error, color = MaterialTheme.colorScheme.error)
        Button(onClick = { vm.reopenSetupWizard(origin) }, enabled = !ui.loading && origin.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(uiText("Set up again")) }
        TextButton(onClick = { vm.openForm(ActionForm("Sign out of Beacon", "", emptyList(), explanation = "Remove the saved Hermes sign-in from this phone. Your encrypted drafts stay here.", actionLabel = "Sign out", onResult = { vm.logout() })) }) { Text(uiText("Sign out")) }
    }
}
@Composable private fun AppUpdates(vm: CypherViewModel) {
    val update by vm.appUpdate.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val call by vm.app.calls.state.collectAsStateWithLifecycle()
    val incoming by vm.incomingBusy.collectAsStateWithLifecycle()
    val callBusy = call.active || incoming || call.state == "ending"
    val context = LocalContext.current
    val app=context.applicationContext as CypherApplication
    val previewBuild = BuildConfig.APPLICATION_ID.endsWith(".debug")
    val stableBuild = BuildConfig.APPLICATION_ID.endsWith(".stable")
    val client = vm.client
    val scope = rememberCoroutineScope()
    var appAutoUpdates by remember(context) { mutableStateOf(app.updates.autoUpdateEnabled()) }
    var releaseCheckComplete by remember(client, ui.origin, ui.owner) { mutableStateOf(false) }
    var releaseCheckBusy by remember { mutableStateOf(false) }
    var releaseCheckError by remember { mutableStateOf(false) }
    var pluginVersions by remember(client, ui.origin, ui.owner) { mutableStateOf<PluginVersions?>(null) }
    var pluginChecking by remember(client, ui.origin, ui.owner) { mutableStateOf(false) }
    var pluginBusy by remember(client, ui.origin, ui.owner) { mutableStateOf(false) }
    var pluginError by remember(client, ui.origin, ui.owner) { mutableStateOf(false) }
    var consentRequired by remember(client, ui.origin, ui.owner) { mutableStateOf(false) }
    var consentLines by remember(client, ui.origin, ui.owner) { mutableStateOf(emptyList<String>()) }
    var pluginAutoUpdates by remember(client,ui.origin,ui.owner) { mutableStateOf(true) }
    var pluginAutoSettings by remember(client,ui.origin,ui.owner) { mutableStateOf(JSONObject()) }
    var pluginAutoSettingsAvailable by remember(client,ui.origin,ui.owner) { mutableStateOf(false) }
    var pluginAutoSettingsMissing by remember(client,ui.origin,ui.owner) { mutableStateOf(false) }
    var pluginAutoSettingsError by remember(client,ui.origin,ui.owner) { mutableStateOf(false) }
    var pluginAutoSaving by remember { mutableStateOf(false) }
    val pluginRow = PluginUpdateRowState(pluginVersions, pluginChecking, pluginBusy, consentRequired, pluginError)
    suspend fun refreshPluginVersion(active: CypherClient) {
        if (vm.client !== active) return
        pluginChecking = true
        pluginError = false
        val detached = active.detached()
        try {
            val versions = HermesPluginUpdater(detached).version()
            if (vm.client === active) pluginVersions = versions
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (vm.client === active) pluginError = true }
        finally { detached.closeNative(); if (vm.client === active) pluginChecking = false }
    }
    suspend fun refreshPluginAutoUpdates(active: CypherClient) {
        if (vm.client !== active) return
        val detached=active.detached()
        try {
            val state=detached.nativeRequest("/api/plugins/cypher/mobile/update-settings")
            if (vm.client===active) {
                pluginAutoSettings=state
                pluginAutoUpdates=state.optBoolean("enabled",true)
                pluginAutoSettingsAvailable=true
                pluginAutoSettingsMissing=false
                pluginAutoSettingsError=false
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (vm.client===active) {
                pluginAutoSettingsAvailable=false
                pluginAutoSettingsMissing=isMissingCypherPluginRoute(error)
                pluginAutoSettingsError=!pluginAutoSettingsMissing
            }
        } finally { detached.closeNative() }
    }
    fun setPluginAutoUpdates(enabled: Boolean) {
        val active=vm.client ?: return
        val previous=pluginAutoUpdates
        pluginAutoUpdates=enabled
        scope.launch {
            if (vm.client!==active) return@launch
            pluginAutoSaving=true
            val detached=active.detached()
            try {
                val state=detached.nativeRequest("/api/plugins/cypher/mobile/update-settings","PUT",JSONObject().put("enabled",enabled))
                if (vm.client===active) {
                    pluginAutoSettings=state
                    pluginAutoUpdates=state.optBoolean("enabled",enabled)
                    pluginAutoSettingsAvailable=true
                    pluginAutoSettingsMissing=false
                    pluginAutoSettingsError=false
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (vm.client===active) {
                    pluginAutoUpdates=previous
                    pluginAutoSettingsMissing=isMissingCypherPluginRoute(error)
                    pluginAutoSettingsAvailable=false
                    pluginAutoSettingsError=!pluginAutoSettingsMissing
                }
            } finally { detached.closeNative(); if (vm.client===active) pluginAutoSaving=false }
        }
    }
    fun updatePlugin(acceptCapabilities: Boolean = false) {
        val active = vm.client ?: return
        if (!ui.connected) return
        scope.launch {
            if (vm.client !== active) return@launch
            pluginBusy = true
            pluginError = false
            val detached = active.detached()
            try {
                val updater = HermesPluginUpdater(detached)
                val current = runCatching { updater.version() }.getOrNull()
                if (vm.client !== active) return@launch
                if (current != null) {
                    pluginVersions = current
                    if (current.restartRequired) return@launch
                }
                val response = updater.update(acceptCapabilities)
                if (vm.client !== active) return@launch
                if (response.optBoolean("consent_required")) {
                    val changes = response.optJSONArray("delta_lines")
                    consentLines = changes?.let { rows -> (0 until rows.length()).mapNotNull { index -> rows.optString(index).takeIf { it.isNotBlank() && it != "null" } } }.orEmpty()
                    consentRequired = true
                    return@launch
                }
                if (!response.optBoolean("ok")) throw IllegalStateException()
                var latest = runCatching { updater.version() }.getOrNull()
                if (!response.optBoolean("unchanged")) {
                    for (attempt in 0 until 10) {
                        if (latest != null && latest != current) break
                        delay(1000)
                        latest = runCatching { updater.version() }.getOrNull() ?: latest
                    }
                }
                if (vm.client === active) {
                    pluginVersions = latest
                    if (latest == null) pluginError = true
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (vm.client === active) pluginError = true }
            finally { detached.closeNative(); if (vm.client === active) pluginBusy = false }
        }
    }
    fun checkAppUpdate() {
        val active = vm.client
        if (!stableBuild || active == null) { vm.checkForAppUpdate(); return }
        scope.launch {
            releaseCheckBusy = true
            releaseCheckError = false
            releaseCheckComplete = false
            val detached = active.detached()
            try {
                detached.nativeRequest("/api/plugins/cypher/mobile/releases/check", "POST", JSONObject())
                if (vm.client === active) {
                    vm.checkForAppUpdate()
                    releaseCheckComplete = true
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (vm.client === active) releaseCheckError = true }
            finally { detached.closeNative(); releaseCheckBusy = false }
        }
    }
    LaunchedEffect(client, ui.origin, ui.owner, ui.connected) {
        pluginVersions = null
        pluginError = false
        consentRequired = false
        consentLines = emptyList()
        pluginAutoSettings=JSONObject()
        pluginAutoUpdates=true
        pluginAutoSettingsAvailable=false
        pluginAutoSettingsMissing=false
        pluginAutoSettingsError=false
        if (ui.connected && client != null) {
            refreshPluginVersion(client)
            refreshPluginAutoUpdates(client)
        }
    }
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
                Text(update.release?.takeIf { !stableBuild || releaseCheckComplete }?.let { "Beacon ${it.name}" } ?: "Beacon", style = MaterialTheme.typography.headlineSmall)
                if (!previewBuild) LabeledToggle("Update the app automatically",appAutoUpdates,{ enabled ->
                    appAutoUpdates=enabled
                    app.updates.setAutoUpdateEnabled(enabled)
                },"Install verified app updates when your connection allows.")
                val appMessage = when {
                    previewBuild -> "Preview builds are installed manually."
                    releaseCheckBusy -> "Checking for updates through Hermes…"
                    releaseCheckError -> "Couldn't check for updates. Check your Hermes connection and try again."
                    update.release != null || update.ready -> update.status
                    update.status.contains("up to date", ignoreCase = true) -> "You're up to date."
                    update.status.startsWith("Reconnect to Tailscale") -> "Reconnect to Tailscale to check for updates."
                    update.status.startsWith("Beacon checks for updates") -> "" // the switch above already says so
                    update.busy -> "Checking for an update…"
                    else -> "Check for updates when you're connected to Hermes."
                }
                if (appMessage.isNotBlank()) Text(appMessage, style = MaterialTheme.typography.bodyLarge)
                if (!previewBuild) {
                    if (update.busy || releaseCheckBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val installing = update.ready || update.release != null && (!stableBuild || releaseCheckComplete)
                    Button(onClick = { if (!installing) checkAppUpdate() else vm.downloadAppUpdate() }, enabled = !update.busy && !releaseCheckBusy && !(callBusy && installing), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(when { releaseCheckBusy || update.busy -> "Checking…"; update.ready -> "Install update"; update.release != null && (!stableBuild || releaseCheckComplete) -> "Update"; else -> "Check for updates" })
                    }
                    if (callBusy && (update.release != null || update.ready)) Text(if(call.state=="ending") "Call cleanup is finishing. The update will be available shortly." else "Finish your call before installing the update.", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Current version · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Glass(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Beacon plugin on Hermes", style = MaterialTheme.typography.titleLarge)
                Text(pluginVersions?.summary() ?: pluginRow.message, style = MaterialTheme.typography.bodyLarge)
                if (pluginVersions?.restartRequired == true || pluginError) Text(pluginRow.message, color = if (pluginError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (pluginChecking || pluginBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LabeledToggle("Update the Beacon plugin automatically",pluginAutoUpdates,{ enabled -> setPluginAutoUpdates(enabled) },
                    "Update the plugin on Hermes when a new version is ready.",enabled=pluginAutoSettingsAvailable && !pluginAutoSaving)
                when {
                    pluginAutoSettingsAvailable -> Text(pluginAutoUpdateLine(pluginAutoSettings),style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    pluginAutoSettingsMissing -> Text("Update the plugin to turn this on.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    pluginAutoSettingsError -> Text("Couldn't load automatic plugin updates. Try again.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                    !ui.connected || client==null -> Text("Connect to Hermes to change this setting.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pluginAutoSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick = { updatePlugin() }, enabled = ui.connected && client != null && pluginRow.canUpdate, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(pluginRow.buttonText) }
            }
        }
        if (!previewBuild) {
            Text("Beacon verifies each app update before installing it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!alertsAllowed) OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Allow update notifications") }
            Text("Automatic app updates need a connection to Hermes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    }
    if (consentRequired) AlertDialog(
        onDismissRequest = { consentRequired = false; consentLines = emptyList() },
        title = { Text("Review plugin capability changes") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This update changes what the Beacon plugin can access. Review the changes before continuing.")
                if (consentLines.isEmpty()) Text("Hermes did not provide a change list. Review the plugin update on your PC before allowing it.")
                consentLines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { consentRequired = false; updatePlugin(acceptCapabilities = true) }) { Text("Allow and update") } },
        dismissButton = { TextButton(onClick = { consentRequired = false; consentLines = emptyList() }) { Text("Cancel") } },
    )
}

@Composable private fun AppearanceSettings(vm: CypherViewModel) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    // Text size, language, text direction and motion follow the phone's own settings.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Glass { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Choice("Theme", prefs.theme, listOf("system", "dark", "light"), { vm.nativePreferences(json("theme" to it)) }, optionLabels = settingsThemeLabels)
            Choice("Color style", prefs.skin, NativeSkins, { vm.nativePreferences(json("skin" to it)) }, optionLabels = skinLabels)
        } } }
    }
}
