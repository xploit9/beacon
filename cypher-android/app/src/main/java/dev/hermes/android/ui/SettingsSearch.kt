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
    "Health, logs & updates" to listOf("Health", "Logs", "Server updates", "App updates", "Session checkpoints"),
)
internal val settingsDescriptions = mapOf(
    "Connection" to "Server and signed-in account",
    "Chat" to "Chat preferences for sending and display",
    "Voice & calls" to "Voice model and call capabilities",
    "Notifications & Keep connected" to "Manage notification delivery on this phone",
    "Appearance" to "Theme, text size and language",
    "Profiles & model" to "Active profile and model choices",
    "Tools & MCP" to "Tool groups and MCP servers",
    "Health, logs & updates" to "Hermes health, logs and updates",
    "About & licenses" to "App details and open-source acknowledgements",
    "Profiles" to "Profile defaults, purpose and behavior",
    "Settings" to "Send messages and control the chat display",
    "Cypher" to "Choose the voice provider for your next call",
    "Notifications" to "Notification delivery on this phone",
    "App updates" to "Install the latest Cypher version",
    "Providers & models" to "Models for this conversation and your bots", "Tools & commands" to "Enable tool groups and browse commands",
    "Skills" to "Read, enable and adjust installed skills", "Memory" to "Agent notes and what Hermes knows about you",
    "MCP servers" to "Manage and test service connections",
    "Health" to "Check the dashboard and agent", "Logs" to "Recent server activity",
    "Server updates" to "Check and update Hermes", "Session checkpoints" to "Saved file recovery points", "Licenses" to "App details and open-source acknowledgements",
)

/** Top-level list follows Relay's section order while every item remains a Cypher route. */
internal val settingsHubRoutes = listOf(
    "Connection", "Chat", "Voice & calls", "Notifications & Keep connected", "Appearance", "Profiles & model",
    "Skills", "Memory", "Tools & MCP", "Health, logs & updates", "About & licenses",
)
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
    else -> page
}

internal val settingsThemeLabels = mapOf("system" to "Follow device", "dark" to "Dark", "light" to "Light")
internal val settingsFontSizeLabels = mapOf("small" to "Small", "default" to "Standard", "large" to "Large", "xlarge" to "Extra large")

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
    val theme: String = "", /** Stored skin key, not its label. */ val colorStyle: String = "", val fontSize: String = "", val language: String = "",
    val reducedMotion: Boolean = false, val rtl: Boolean = false,
    val installedVersion: String = "", val updateName: String = "", val updateReady: Boolean = false,
    val voiceProviderId: String = "", val voiceKeySaved: Boolean? = null,
    val autoFollow: Boolean? = null, val animateBackground: Boolean? = null,
    val keepConnectedEnabled: Boolean? = null,
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
            s.formatMessages?.let { SettingsSummaryPart(if (it) "Messages formatted" else "Plain messages") },
            s.showThinking?.let { SettingsSummaryPart(if (it) "Thinking shown" else "Thinking hidden") },
            s.autoFollow?.let { SettingsSummaryPart(if (it) "Auto-follow on" else "Auto-follow off") },
            s.animateBackground?.let { SettingsSummaryPart(if (it && !s.reducedMotion) "Background animation on" else "Background animation off") },
        )
        "Appearance" -> listOfNotNull(
            settingsThemeLabels[s.theme]?.let { SettingsSummaryPart(it) },
            // Same rule as the Appearance picker: a known skin label translates, an unknown stored key stays verbatim.
            skinLabels[s.colorStyle]?.let { SettingsSummaryPart(it) } ?: literal(s.colorStyle),
            settingsFontSizeLabels[s.fontSize]?.let { SettingsSummaryPart("$it text") },
            literal(s.language),
            if (s.reducedMotion) SettingsSummaryPart("Reduced motion") else null,
            if (s.rtl) SettingsSummaryPart("Right-to-left") else null,
        )
        "App updates", "Health, logs & updates" -> updateSummary()
        "About & licenses" -> literal(s.installedVersion)?.let { listOf(SettingsSummaryPart("Installed"), it) }.orEmpty()
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
