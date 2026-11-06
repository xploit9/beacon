package dev.hermes.android.ui

internal fun settingsLabel(route: String): String = when (route) {
    "Settings" -> "Chat"
    "Beacon" -> "Voice & calls"
    // Keep connected is the first thing on the page, so the page and its hub row are simply Notifications; search still finds them by Keep connected.
    "Notifications", "Notifications & Keep connected" -> "Notifications"
    "Licenses" -> "About & licenses"
    "Tools & commands" -> "Tools"
    "Session checkpoints" -> "File backups"
    else -> route
}

// \p{Z} adds Unicode separators (no-break, ideographic, line/paragraph) that \s alone misses on the JVM.
private val searchWhitespace = Regex("[\\s\\p{Z}]+")
// The old, longer descriptions, kept as search words so the shorter row text loses no match.
private val settingsSearchKeywords = mapOf(
    "Connection" to "Server, signed-in account, and phones address sign out plugin health",
    "Assistant" to "What needs you proactive alerts watch check-up checkup scheduled calls",
    "Chat" to "while replying steer queue change the reply send after",
    "Voice & calls" to "API key Voice provider and calls",
    "Notifications" to "Notification delivery keep connected",
    "Devices" to "Manage linked phones",
    "Appearance" to "Theme and color style dark light",
    "Memory" to "What Hermes knows about you",
    "Phone access" to "Calendar reminders alarms timers contacts calls messages text SMS permissions Hermes activity",
    "App updates" to "Install the latest Beacon version",
    "Profiles" to "Profile defaults and behavior",
    "File backups" to "Session checkpoints",
)
private fun searchTermsMatch(text: String, query: String): Boolean =
    query.splitToSequence(searchWhitespace).filter(String::isNotBlank).all { text.contains(it, ignoreCase = true) }

internal fun settingsSearchMatches(visibleLabel: String, description: String, query: String): Boolean =
    searchTermsMatch("$visibleLabel $description ${settingsSearchKeywords[visibleLabel].orEmpty()}", query)

/** The extra search words of a hub row, in English; the hub shows them through uiText so a phone in another language searches them in its own. */
internal fun settingsKeywordsOf(route: String): String = settingsSearchKeywords[settingsLabel(route)].orEmpty()

/**
 * Whether a hub row matches [query]: its label, description and keywords as the phone shows them (in the phone's language) and in English,
 * so "sign out" finds Connection in every language and so does the translated word.
 */
internal fun settingsRowMatches(route: String, shownLabel: String, shownDescription: String, shownKeywords: String, query: String): Boolean =
    searchTermsMatch("$shownLabel $shownDescription $shownKeywords ${settingsLabel(route)} ${settingsDescriptions[route].orEmpty()} ${settingsKeywordsOf(route)}", query)

internal fun modelSearchMatches(provider: String, id: String, label: String, query: String): Boolean =
    searchTermsMatch("$provider $id $label", query)

internal val settingsCategories = linkedMapOf(
    "Profiles & model" to listOf("Profiles", "Providers & models"),
    "Tools & MCP" to listOf("Tools & commands", "MCP servers"),
    "Server" to listOf("Health", "Logs", "Server updates", "Session checkpoints"),
)
// One wording per page: the aliases (Settings, Beacon, Notifications, Licenses) read the same constant as their hub row.
private const val ChatDescription = "Sending and display"
private const val VoiceDescription = "Voice and provider"
private const val NotificationsDescription = "Alerts and calls"
private const val LicensesDescription = "Version and licenses"
internal val settingsDescriptions = mapOf(
    "Connection" to "Server, account, phones",
    "Devices" to "Linked phones",
    "Assistant" to "Name and profile",
    "Chat" to ChatDescription,
    "Voice & calls" to VoiceDescription,
    "Notifications & Keep connected" to NotificationsDescription,
    "Phone access" to "Calendar, contacts and calls",
    "Appearance" to "Theme and colors",
    "Profiles & model" to "Profiles and models",
    "Tools & MCP" to "Tool groups and MCP servers",
    "Server" to "Health, logs and updates",
    "About & licenses" to LicensesDescription,
    "Profiles" to "Defaults and behavior",
    "Settings" to ChatDescription,
    "Beacon" to VoiceDescription,
    "Notifications" to NotificationsDescription,
    "App updates" to "Latest Beacon",
    "Providers & models" to "Models for chats and bots", "Tools & commands" to "Tool groups and commands",
    "Skills" to "Installed skills", "Memory" to "Notes Hermes keeps",
    "MCP servers" to "Services Hermes can use",
    "Health" to "Check the dashboard and agent", "Logs" to "Recent server activity",
    "Server updates" to "Check and update Hermes", "Session checkpoints" to "Earlier file versions", "Licenses" to LicensesDescription,
)

/** Hub sections in Relay's order (connection first, then Hermes, then the app), with the assistant's own section up front. */
internal val settingsHubSections = linkedMapOf(
    "" to listOf("Connection"),
    "Assistant" to listOf("Assistant", "Voice & calls", "Phone access", "Notifications & Keep connected"),
    "Hermes" to listOf("Chat", "Profiles & model", "Skills", "Tools & MCP", "Server"),
    "App" to listOf("Appearance", "App updates", "About & licenses"),
)
internal val settingsHubRoutes = settingsHubSections.values.flatten()
/** Search includes both the hub and its focused destinations. */
internal val settingsSearchRoutes = settingsHubRoutes + settingsCategories.values.flatten() + listOf("Devices", "Memory")
// Skills, Memory and Server updates are native Hermes routes; only plugin-backed pages are gated.
private val pluginSettingsRoutes = setOf("Notifications", "Bots", "Vault")
internal fun settingsNeedsBeaconPlugin(page: String, pluginStatus: String): Boolean = pluginStatus == "absent" && page in pluginSettingsRoutes

/** The exact `BeaconViewModel.more` argument for a row; categories open their own list, everything else opens its page. */
internal fun settingsRoute(name: String): String = when (name) {
    "Chat" -> "Settings"
    "Voice & calls" -> "Beacon"
    "Notifications & Keep connected" -> "Notifications"
    "Devices" -> "Connection"
    "Memory" -> "Profiles"
    "About & licenses" -> "Licenses"
    "Models & tools" -> "category:Profiles & model"
    else -> if (name in settingsCategories) "category:$name" else name
}

/** Old category names can remain in the per-owner navigation history after a settings rename. */
internal fun normalizeSettingsPage(page: String): String = when (page) {
    "Models & tools", "category:Models & tools" -> "category:Profiles & model"
    "Server & support", "category:Server & support", "Health, logs & updates", "category:Health, logs & updates" -> "category:Server"
    else -> page
}

internal val settingsThemeLabels = mapOf("system" to "System", "dark" to "Dark", "light" to "Light")

/** The short pill words of the hub rows, which reach uiText through a summary part rather than a literal. */
internal val settingsPillWords = listOf("Connected", "Offline", "Sign in", "Key saved", "Add key", "Keep connected is on", "Keep connected is off", "Update ready", "Update available",
    "Update the Beacon plugin", "Restart Hermes", "Restart dashboard", "Healthy", "Needs attention", "Failed", "Reconnecting…", "{0} on", "{0} servers", "1 server", "{0} profiles", "Off")

/**
 * The fixed texts that reach uiText through variables and so are checked by the translation test: the hub's row names and descriptions, section and
 * category titles, theme names and search words, the reasoning words and sentences, the offline and default error texts and the call card's title words.
 */
internal val SettingsStrings = (settingsSearchRoutes.map(::settingsLabel) + settingsDescriptions.values + settingsHubSections.keys.filter(String::isNotBlank) +
    settingsCategories.keys + settingsThemeLabels.values + listOf("Tools and services", "Settings", "Change the reply", "Send after") + settingsPillWords + settingsSearchKeywords.values +
    ReasoningStrings + listOf(OfflineReadError, DefaultReadError) + CallTitleStrings).distinct()

/**
 * Values the overview may summarize. Blank or null means unknown on this phone, and unknown values produce no summary,
 * so the row keeps its static description. Nothing here is ever filled in with an assumed default.
 *
 * Pattern credit (behavior only, no Hermes Relay code, strings or resources are copied): Relay's `SettingsScreen.kt`
 * (status shown from observed connection state, `chatPill`/`dashboardPill`) and `ConnectionsSettingsScreen.kt`
 * (`resolveConnectionClarityPresentation` maps "never checked" to its own Unchecked state instead of assuming health).
 */
internal data class SettingsSnapshot(
    val connected: Boolean = false, /** The phone is reconnecting or restoring its sign-in (the Connection page reads Reconnecting…). */ val connecting: Boolean = false, val profile: String = "",
    val sendKey: String = "", val formatMessages: Boolean? = null, val showThinking: Boolean? = null,
    val theme: String = "", /** Stored skin key, not its label. */ val colorStyle: String = "",
    val installedVersion: String = "", val updateName: String = "", val updateReady: Boolean = false,
    val voiceProviderId: String = "", val voiceKeySaved: Boolean? = null,
    val autoFollow: Boolean? = null, val animateBackground: Boolean? = null,
    val keepConnectedEnabled: Boolean? = null,
    val assistantName: String = "", val assistantProfile: String = "",
    /** The signed-in account, verbatim. */ val account: String = "",
    /** True once an address is saved but its sign-in is gone. */ val signedOut: Boolean = false,
    /** "Update the Beacon plugin", "Restart Hermes" or "Restart dashboard" while the update screen has a pending plugin step, else blank. */ val pluginAction: String = "",
    val profileCount: Int? = null, val skillsOn: Int? = null, val toolsOn: Int? = null, val mcpServers: Int? = null,
    val serverHealthy: Boolean? = null, /** Hermes' own overall word ("ok", "degraded", …); wins over [serverHealthy] and maps like the Health page. */ val serverOverall: String = "", val serverUpdateAvailable: Boolean? = null, val checkpointSize: String = "",
    /** The Phone access switches that are on and usable, by their English names; empty is a known "none", null is unread. */ val phoneAccess: List<String>? = null,
)
/**
 * Mirrors the composer's key handling (`ChatScreen.kt` `onPreviewKeyEvent`): it recognises `ctrl_enter`/`ctrl+enter` and
 * `shift+enter`, and treats everything else as plain Enter. Only a value the composer recognises on purpose is reported,
 * so a stored `shift_enter` (not matched by the composer) is left unknown instead of being described as a shortcut.
 */
internal fun settingsSendKeyLabel(sendKey: String): String? = when (sendKey) {
    "enter" -> "Enter to send"
    "ctrl_enter", "ctrl+enter" -> "Ctrl + Enter to send"
    "shift+enter" -> "Shift + Enter to send"
    else -> null
}

/**
 * One fact of a row's summary. [translate] is false for values that belong to the user's data (profile, version, native language
 * name) and must stay verbatim. A part with a [status] is the row's pill: [text] is the sentence form, [pill] the short word shown.
 * [args] fill `{0}`, `{1}` in [text] after it is translated.
 */
internal data class SettingsSummaryPart(val text: String, val translate: Boolean = true, val status: SettingsStatus? = null, val pill: String = text, val args: List<String> = emptyList())
internal fun List<String>.joinSummary(): String = joinToString(" · ")

/** What a hub row shows under its name: the value, then at most one status pill. A blank value with no pill means the row keeps its description. */
@androidx.compose.runtime.Immutable internal data class SettingsRowSummary(val value: String = "", val status: SettingsStatus? = null, val statusLabel: String = "")

/** Fills `{0}`, `{1}` in already translated [text]. */
internal fun settingsFilled(text: String, args: List<String>): String = args.foldIndexed(text) { index, acc, arg -> acc.replace("{" + index + "}", arg) }

/** Splits [parts] into the value line and the pill, passing every fixed word through [localize] (inline, so it may be a composable uiText). */
internal inline fun settingsRowSummary(parts: List<SettingsSummaryPart>, localize: (String) -> String): SettingsRowSummary {
    val pill = parts.firstOrNull { it.status != null }
    val value = parts.filter { it.status == null }.map { settingsFilled(if (it.translate) localize(it.text) else it.text, it.args) }
    return SettingsRowSummary(
        value = value.joinToString(" · "),
        status = pill?.status, statusLabel = pill?.let { settingsFilled(if (it.translate) localize(it.pill) else it.pill, it.args) }.orEmpty(),
    )
}

private val voiceProviderLabels = mapOf("gemini" to "Gemini Live", "grok" to "Grok", "openai" to "OpenAI Realtime")

internal fun settingsSummary(route: String, s: SettingsSnapshot): List<SettingsSummaryPart> {
    fun literal(value: String) = value.takeIf(String::isNotBlank)?.let { SettingsSummaryPart(it, translate = false) }
    fun count(text: String, value: Int?) = value?.let { SettingsSummaryPart(text, args = listOf(it.toString())) }
    fun attention(text: String, pill: String = text) = SettingsSummaryPart(text, status = SettingsStatus.Attention, pill = pill)
    // A pending plugin step only earns the pill when no app update already claims it.
    fun updateSummary() = when {
        s.updateReady -> listOfNotNull(attention("Update ready to install", "Update ready"), literal(s.updateName.ifBlank { s.installedVersion }))
        s.updateName.isNotBlank() -> listOfNotNull(attention("Update available"), literal(s.updateName))
        s.pluginAction.isNotBlank() -> listOfNotNull(attention(s.pluginAction), literal(s.installedVersion))
        else -> listOfNotNull(literal(s.installedVersion))
    }
    return when (route) {
        "Connection" -> listOfNotNull(
            when {
                s.connected -> SettingsSummaryPart("Connected", status = SettingsStatus.Ok)
                s.connecting -> SettingsSummaryPart("Reconnecting…", status = SettingsStatus.Working)
                s.signedOut -> attention("Signed out", "Sign in")
                else -> SettingsSummaryPart("Connection needs attention", status = SettingsStatus.Error, pill = "Offline")
            },
            literal(s.account), literal(s.profile),
        )
        "Profiles & model" -> listOfNotNull(literal(s.profile))
        "Profiles" -> listOfNotNull(literal(s.profile), if (s.profile.isBlank()) null else count("{0} profiles", s.profileCount))
        "Chat", "Settings" -> listOfNotNull(
            settingsSendKeyLabel(s.sendKey)?.let { SettingsSummaryPart(it) },
            s.showThinking?.let { SettingsSummaryPart(if (it) "Thinking shown" else "Thinking hidden") },
        )
        "Appearance" -> listOfNotNull(
            settingsThemeLabels[s.theme]?.let { SettingsSummaryPart(it) },
            // Same rule as the Appearance picker: a known skin label translates, an unknown stored key stays verbatim.
            skinLabels[s.colorStyle]?.let { SettingsSummaryPart(it) } ?: literal(s.colorStyle),
        )
        "App updates" -> updateSummary()
        "Assistant" -> listOfNotNull(literal(s.assistantName), literal(s.assistantProfile))
        "About & licenses" -> literal(s.installedVersion)?.let { listOf(SettingsSummaryPart("Version"), it) }.orEmpty()
        "Beacon", "Voice & calls" -> s.voiceKeySaved?.let { keySaved ->
            voiceProviderLabels[s.voiceProviderId]?.let { provider ->
                listOf(SettingsSummaryPart(provider, translate = false),
                    if (keySaved) SettingsSummaryPart("Key saved", status = SettingsStatus.Ok) else attention("Add key"))
            }
        }.orEmpty()
        // Nothing on is a state of its own (Off); otherwise the row names what is on, as its neighbours name their own state.
        "Phone access" -> s.phoneAccess?.let { on -> if (on.isEmpty()) listOf(SettingsSummaryPart("Off", status = SettingsStatus.Off)) else on.map { SettingsSummaryPart(it) } }.orEmpty()
        "Notifications & Keep connected" -> s.keepConnectedEnabled?.let {
            listOf(SettingsSummaryPart(if (it) "Keep connected is on" else "Keep connected is off", status = if (it) SettingsStatus.Ok else SettingsStatus.Off))
        }.orEmpty()
        "Skills" -> listOfNotNull(count("{0} on", s.skillsOn))
        "Tools & commands" -> listOfNotNull(count("{0} on", s.toolsOn))
        "MCP servers" -> listOfNotNull(if (s.mcpServers == 1) SettingsSummaryPart("1 server") else count("{0} servers", s.mcpServers))
        // The Health page's own mapping: only "degraded" needs attention; any other word that is not ok is a failure.
        "Health" -> (if (s.serverOverall.isNotBlank()) serverHealthStatus(s.serverOverall, loading = false) else s.serverHealthy?.let { if (it) SettingsStatus.Ok else SettingsStatus.Attention })
            ?.let { status -> listOf(SettingsSummaryPart(serverHealthWord(status), status = status)) }.orEmpty()
        "Server updates" -> if (s.serverUpdateAvailable == true) listOf(attention("Update available")) else emptyList()
        "Session checkpoints" -> listOfNotNull(literal(s.checkpointSize))
        else -> emptyList()
    }
}
