package dev.hermes.android.ui

internal fun settingsLabel(route: String): String = when (route) {
    "Settings" -> "Chat"
    "Cypher" -> "Voice & calls"
    "Notifications" -> "Notifications & Keep connected"
    "Licenses" -> "About & licenses"
    else -> route
}

// \p{Z} adds Unicode separators (no-break, ideographic, line/paragraph) that \s alone misses on the JVM.
private val searchWhitespace = Regex("[\\s\\p{Z}]+")
private fun searchTermsMatch(text: String, query: String): Boolean =
    query.splitToSequence(searchWhitespace).filter(String::isNotBlank).all { text.contains(it, ignoreCase = true) }

internal fun settingsSearchMatches(visibleLabel: String, description: String, query: String): Boolean =
    searchTermsMatch("$visibleLabel $description", query)

internal fun modelSearchMatches(provider: String, id: String, label: String, query: String): Boolean =
    searchTermsMatch("$provider $id $label", query)

internal val settingsCategories = linkedMapOf(
    "Profiles & model" to listOf("Profiles", "Providers & models"),
    "Tools & MCP" to listOf("Tools & commands", "MCP servers"),
    "Server" to listOf("Health", "Logs", "Server updates", "Session checkpoints"),
)
internal val settingsDescriptions = mapOf(
    "Connection" to "Server and signed-in account",
    "Assistant" to "Name and profile",
    "Chat" to "Sending and display",
    "Voice & calls" to "Voice provider and calls",
    "Notifications & Keep connected" to "Notification delivery",
    "Appearance" to "Theme and color style",
    "Profiles & model" to "Profiles and models",
    "Tools & MCP" to "Tool groups and MCP servers",
    "Server" to "Health, logs and updates",
    "About & licenses" to "Version and licenses",
    "Profiles" to "Profile defaults and behavior",
    "Settings" to "Sending and display",
    "Cypher" to "Voice provider for calls",
    "Notifications" to "Notification delivery",
    "App updates" to "Install the latest Beacon version",
    "Providers & models" to "Models for chats and bots", "Tools & commands" to "Tool groups and commands",
    "Skills" to "Installed skills", "Memory" to "What Hermes knows about you",
    "MCP servers" to "Service connections",
    "Health" to "Check the dashboard and agent", "Logs" to "Recent server activity",
    "Server updates" to "Check and update Hermes", "Session checkpoints" to "Saved file recovery points", "Licenses" to "Version and licenses",
)

/** Hub sections in Relay's order (connection first, then Hermes, then the app), with the assistant's own section up front. */
internal val settingsHubSections = linkedMapOf(
    "" to listOf("Connection"),
    "Assistant" to listOf("Assistant", "Voice & calls", "Notifications & Keep connected"),
    "Hermes" to listOf("Chat", "Profiles & model", "Skills", "Memory", "Tools & MCP", "Server"),
    "App" to listOf("Appearance", "App updates", "About & licenses"),
)
internal val settingsHubRoutes = settingsHubSections.values.flatten()
/** Search includes both the hub and its focused destinations. */
internal val settingsSearchRoutes = settingsHubRoutes + settingsCategories.values.flatten()
// Skills, Memory and Server updates are native Hermes routes; only plugin-backed pages are gated.
private val pluginSettingsRoutes = setOf("Notifications", "Bots")
internal fun settingsNeedsCypherPlugin(page: String, pluginStatus: String): Boolean = pluginStatus == "absent" && page in pluginSettingsRoutes

/** The exact `CypherViewModel.more` argument for a row; categories open their own list, everything else opens its page. */
internal fun settingsRoute(name: String): String = when (name) {
    "Chat" -> "Settings"
    "Voice & calls" -> "Cypher"
    "Notifications & Keep connected" -> "Notifications"
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

internal val settingsThemeLabels = mapOf("system" to "Follow device", "dark" to "Dark", "light" to "Light")

/**
 * Values the overview may summarize. Blank or null means unknown on this phone, and unknown values produce no summary,
 * so the row keeps its static description. Nothing here is ever filled in with an assumed default.
 *
 * Pattern credit (behavior only, no Hermes Relay code, strings or resources are copied): Relay's `SettingsScreen.kt`
 * (status shown from observed connection state, `chatPill`/`dashboardPill`) and `ConnectionsSettingsScreen.kt`
 * (`resolveConnectionClarityPresentation` maps "never checked" to its own Unchecked state instead of assuming health).
 */
internal data class SettingsSnapshot(
    val connected: Boolean = false, val profile: String = "",
    val sendKey: String = "", val formatMessages: Boolean? = null, val showThinking: Boolean? = null,
    val theme: String = "", /** Stored skin key, not its label. */ val colorStyle: String = "",
    val installedVersion: String = "", val updateName: String = "", val updateReady: Boolean = false,
    val voiceProviderId: String = "", val voiceKeySaved: Boolean? = null,
    val autoFollow: Boolean? = null, val animateBackground: Boolean? = null,
    val keepConnectedEnabled: Boolean? = null,
    val assistantName: String = "", val assistantProfile: String = "",
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

/** [translate] is false for values that belong to the user's data (profile, version, native language name) and must stay verbatim. */
internal data class SettingsSummaryPart(val text: String, val translate: Boolean = true)
internal fun List<String>.joinSummary(): String = joinToString(" · ")

private val voiceProviderLabels = mapOf("gemini" to "Gemini Live", "grok" to "Grok", "openai" to "OpenAI Realtime")

internal fun settingsSummary(route: String, s: SettingsSnapshot): List<SettingsSummaryPart> {
    fun literal(value: String) = value.takeIf(String::isNotBlank)?.let { SettingsSummaryPart(it, translate = false) }
    fun updateSummary() = when {
        s.updateReady -> listOf(SettingsSummaryPart("Update ready to install"))
        s.updateName.isNotBlank() -> listOfNotNull(SettingsSummaryPart("Update available"), literal(s.updateName))
        s.installedVersion.isNotBlank() -> listOfNotNull(SettingsSummaryPart("Installed"), literal(s.installedVersion))
        else -> emptyList()
    }
    return when (route) {
        "Connection" -> listOfNotNull(SettingsSummaryPart(if (s.connected) "Connected privately" else "Connection needs attention"), literal(s.profile))
        "Profiles", "Profiles & model" -> if (s.profile.isBlank()) emptyList() else listOfNotNull(SettingsSummaryPart("Active profile"), literal(s.profile))
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
        "Cypher", "Voice & calls" -> s.voiceKeySaved?.let { keySaved ->
            voiceProviderLabels[s.voiceProviderId]?.let { provider ->
                listOf(SettingsSummaryPart(provider, translate = false), SettingsSummaryPart(if (keySaved) "Key saved" else "Add key"))
            }
        }.orEmpty()
        "Notifications & Keep connected" -> s.keepConnectedEnabled?.let {
            listOf(SettingsSummaryPart(if (it) "Keep connected on" else "Keep connected off"))
        }.orEmpty()
        else -> emptyList()
    }
}
