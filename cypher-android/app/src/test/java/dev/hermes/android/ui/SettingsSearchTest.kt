package dev.hermes.android.ui

import dev.hermes.android.core.json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsSearchTest {
    @Test fun searchesVisibleLabelAndDescriptionWithoutChangingRoute() {
        assertEquals("Chat", settingsLabel("Settings"))
        assertEquals("Voice & calls", settingsLabel("Cypher"))
        val chatDescription = settingsDescriptions.getValue("Chat")
        assertTrue(settingsSearchMatches(settingsLabel("Chat"), chatDescription, "chat preferences"))
        assertTrue(settingsSearchMatches(settingsLabel("Chat"), chatDescription, "SEND"))
        assertTrue(settingsSearchMatches(settingsLabel("Chat"), chatDescription, "sending  display"))
        assertTrue(settingsSearchMatches("Tools & MCP", "Tool groups and MCP servers", "tools & mcp"))
        assertFalse(settingsSearchMatches(settingsLabel("Chat"), chatDescription, "camera"))
        assertFalse(settingsSearchMatches(settingsLabel("Chat"), chatDescription, "sending camera"))
        assertTrue(settingsSearchMatches("Appearance", "Theme, text size and language", " "))
        assertTrue(settingsSearchMatches("Appearance", "Theme, text size and language", " appearance "))
        assertTrue(modelSearchMatches("provider", "model", "Configured model", " model "))
    }
    @Test fun unicodeWhitespaceSeparatesSearchTerms() {
        val label = settingsLabel("Chat"); val description = settingsDescriptions.getValue("Chat")
        for (space in listOf(" ", "　", " ", " ", " ", "\t ")) {
            assertTrue("U+%04X must split terms".format(space.last().code), settingsSearchMatches(label, description, "send${space}display"))
            assertTrue(settingsSearchMatches(label, description, "${space}chat$space"))
            assertFalse(settingsSearchMatches(label, description, "send${space}camera"))
        }
        assertTrue(modelSearchMatches("provider", "model", "Configured model", "configured provider"))
    }
    @Test fun everyIncumbentDestinationStaysSearchableInOrder() {
        assertEquals(
            listOf("Connection", "Chat", "Voice & calls", "Notifications & Keep connected", "Appearance", "Profiles & model", "Skills", "Memory", "Tools & MCP", "Health, logs & updates", "About & licenses",
                "Profiles", "Providers & models", "Tools & commands", "MCP servers", "Health", "Logs", "Server updates", "App updates", "Session checkpoints"),
            settingsSearchRoutes,
        )
        for (route in settingsSearchRoutes) {
            val description = settingsDescriptions[route]
            assertNotNull("$route lost its search description", description)
            assertTrue("$route is not found by its visible label", settingsSearchMatches(settingsLabel(route), description!!, settingsLabel(route)))
            assertTrue("$route is not found by its description", settingsSearchMatches(settingsLabel(route), description, description))
        }
        assertEquals("Each destination appears once", settingsSearchRoutes.size, settingsSearchRoutes.toSet().size)
        assertEquals("Each destination opens a distinct route", settingsSearchRoutes.size, settingsSearchRoutes.map(::settingsRoute).toSet().size)
        // Description-only words still find the row whose label does not contain them.
        assertTrue(settingsSearchMatches(settingsLabel("Session checkpoints"), settingsDescriptions.getValue("Session checkpoints"), "recovery"))
        assertTrue(settingsSearchMatches(settingsLabel("Chat"), settingsDescriptions.getValue("Chat"), "sending"))
    }
    @Test fun rowsOpenTheIncumbentRouteArguments() {
        assertEquals(
            listOf("Connection", "Settings", "Cypher", "Notifications", "Appearance", "category:Profiles & model", "Skills", "Memory", "category:Tools & MCP", "category:Health, logs & updates", "Licenses"),
            settingsHubRoutes.map(::settingsRoute),
        )
        assertEquals(listOf("Profiles", "Providers & models"), settingsCategories.getValue("Profiles & model"))
        assertEquals(listOf("Tools & commands", "MCP servers"), settingsCategories.getValue("Tools & MCP"))
        assertEquals(listOf("Health", "Logs", "Server updates", "App updates", "Session checkpoints"), settingsCategories.getValue("Health, logs & updates"))
        assertTrue("A page name must never collide with a category name", settingsCategories.values.flatten().none { it in settingsCategories })
    }
    @Test fun onlyPluginBackedPagesShowTheMissingPluginState() {
        assertFalse(settingsNeedsCypherPlugin("Skills", "absent"))
        assertFalse(settingsNeedsCypherPlugin("Memory", "absent"))
        assertFalse(settingsNeedsCypherPlugin("Server updates", "absent"))
        assertFalse(settingsNeedsCypherPlugin("Cypher", "absent"))
        assertTrue(settingsNeedsCypherPlugin("Notifications", "absent"))
        assertFalse(settingsNeedsCypherPlugin("Tools & commands", "absent"))
        assertFalse(settingsNeedsCypherPlugin("App updates", "absent"))
        assertFalse(settingsNeedsCypherPlugin("Skills", "error"))
        assertFalse(settingsNeedsCypherPlugin("Skills", "not_probed"))
    }
    @Test fun backParentsStayCategoryAware() {
        assertEquals("category:Health, logs & updates", settingsParent("Health"))
        assertEquals("category:Tools & MCP", settingsParent("MCP servers"))
        assertEquals("category:Profiles & model", settingsParent("Providers & models"))
        assertEquals("Tools & commands", settingsParent("Commands"))
        assertEquals("", settingsParent("category:Profiles & model"))
        for (route in settingsHubRoutes.map(::settingsRoute)) assertEquals("$route backs out to the Settings overview", "", settingsParent(route))
        for ((category, pages) in settingsCategories) pages.forEach { assertEquals("category:$category", settingsParent(it)) }
    }
    private fun summary(route: String, state: SettingsSnapshot) = settingsSummary(route, state).map { it.text }.joinSummary()
    @Test fun summariesReflectOnlyKnownState() {
        val unknown = SettingsSnapshot()
        for (route in settingsSearchRoutes - "Connection") assertEquals("$route must not invent a value", "", summary(route, unknown))
        assertEquals("Connection needs attention", summary("Connection", unknown))
        val known = SettingsSnapshot(
            connected = true, profile = "work", sendKey = "ctrl_enter", formatMessages = false, showThinking = true,
            theme = "dark", colorStyle = "graphite", fontSize = "xlarge", language = "Deutsch", reducedMotion = true,
            installedVersion = "0.4.14", voiceProviderId = "gemini", voiceKeySaved = true, autoFollow = true, animateBackground = false,
        )
        assertEquals("Connected privately · work", summary("Connection", known))
        assertEquals("Connection needs attention · work", summary("Connection", known.copy(connected = false)))
        assertEquals("Active profile · work", summary("Profiles & model", known))
        assertEquals("Ctrl + Enter to send · Plain messages · Thinking shown · Auto-follow on · Background animation off", summary("Chat", known))
        assertEquals("Background animation off", summary("Chat", SettingsSnapshot(reducedMotion = true, animateBackground = true)))
        assertEquals("Dark · Graphite · Extra large text · Deutsch · Reduced motion", summary("Appearance", known))
        assertEquals("Follow device · Cypher · Standard text · English · Right-to-left", summary("Appearance", SettingsSnapshot(theme = "system", colorStyle = "default", fontSize = "default", language = "English", rtl = true)))
        assertEquals("Installed · 0.4.14", summary("App updates", known))
        assertEquals("Installed · 0.4.14", summary("Health, logs & updates", known))
        assertEquals("Installed · 0.4.14", summary("About & licenses", known))
        assertEquals("Installed · 0.4.14", summary("About & licenses", known.copy(updateName = "0.4.15")))
        assertEquals("Update available · 0.4.15", summary("App updates", known.copy(updateName = "0.4.15")))
        assertEquals("Update ready to install", summary("App updates", known.copy(updateName = "0.4.15", updateReady = true)))
        assertEquals("Gemini Live · Key saved", summary("Voice & calls", known))
        assertEquals("Graphite · Extra large text", summary("Appearance", SettingsSnapshot(theme = "sepia", colorStyle = "graphite", fontSize = "xlarge")))
        assertEquals("Enter to send", summary("Chat", SettingsSnapshot(sendKey = "enter")))
        assertEquals("", summary("Chat", SettingsSnapshot(sendKey = "shift_enter")))
        for ((stored, label) in mapOf("enter" to "Enter to send", "ctrl_enter" to "Ctrl + Enter to send", "ctrl+enter" to "Ctrl + Enter to send", "shift+enter" to "Shift + Enter to send")) assertEquals(label, summary("Chat", SettingsSnapshot(sendKey = stored)))
        for (stored in listOf("", "shift_enter", "alt+enter", "Enter ")) assertNull("$stored is not a composer shortcut", settingsSendKeyLabel(stored))
        for (route in settingsSearchRoutes - setOf("Connection", "Profiles", "Profiles & model", "Chat", "Appearance", "App updates", "Voice & calls", "Health, logs & updates", "About & licenses")) assertTrue("$route has no observable state to summarize", settingsSummary(route, known).isEmpty())
    }
    @Test fun userDataStaysVerbatimWhileFixedWordsTranslate() {
        val parts = settingsSummary("Profiles & model", SettingsSnapshot(profile = "Settings"))
        assertEquals(listOf(SettingsSummaryPart("Active profile", true), SettingsSummaryPart("Settings", false)), parts)
        assertTrue(settingsSummary("App updates", SettingsSnapshot(installedVersion = "0.4.14")).last().let { !it.translate && it.text == "0.4.14" })
        // Native language names are user-facing data in their own script and never pass through the catalog.
        assertEquals(listOf(SettingsSummaryPart("العربية", false)), settingsSummary("Appearance", SettingsSnapshot(language = "العربية")))
        assertEquals(listOf(SettingsSummaryPart("Deutsch", false)), settingsSummary("Appearance", SettingsSnapshot(language = "Deutsch")))
    }
    @Test fun colorStyleTranslatesLikeTheAppearancePicker() {
        // Known skins use the picker's label, which the picker passes through uiText.
        assertEquals(listOf(SettingsSummaryPart("Graphite", true)), settingsSummary("Appearance", SettingsSnapshot(colorStyle = "graphite")))
        assertEquals(listOf(SettingsSummaryPart("Cypher", true)), settingsSummary("Appearance", SettingsSnapshot(colorStyle = "default")))
        assertEquals(listOf(SettingsSummaryPart("Neon Soft", true)), settingsSummary("Appearance", SettingsSnapshot(colorStyle = "neon-soft")))
        // An unknown stored key is shown as recorded, like the picker, and is not translated.
        assertEquals(listOf(SettingsSummaryPart("custom-skin", false)), settingsSummary("Appearance", SettingsSnapshot(colorStyle = "custom-skin")))
        for (skin in NativeSkins) assertEquals(skinLabels.getValue(skin), settingsSummary("Appearance", SettingsSnapshot(colorStyle = skin)).single().text)
    }
    private val base = SettingsSnapshot(connected = true, profile = "work", theme = "dark")
    @Test fun absentFailedOrLoadingRecordsLeaveEverySummaryUnknown() {
        // Each chat record carries non-default values; absent, failed and loading records still leave it unknown.
        fun chat() = json("send_key" to "ctrl_enter", "render_user_markdown" to false, "show_thinking" to true)
        val records = listOf<JSONObject?>(
            null,
            chat().put("_error", "Reconnect to refresh this page."),
            chat().put("_loading", true),
        )
        // The same values without the error/loading marker do produce summaries.
        assertEquals("Ctrl + Enter to send · Plain messages · Thinking shown", summary("Chat", base.withLoaded(chat())))
        assertEquals("Grok · Add key", summary("Voice & calls", base.copy(voiceProviderId = "grok", voiceKeySaved = false)))
        for (record in records) {
            val state = base.withLoaded(record)
            assertEquals("Chat must keep its description for $record", "", summary("Chat", state))
            assertEquals("Voice & calls must keep its description for $record", "", summary("Voice & calls", state))
            assertEquals("Nothing may be filled in for $record", base, state)
        }
    }
    @Test fun missingKeysAreNeverDefaulted() {
        val onlySendKey = base.withLoaded(json("send_key" to "ctrl_enter"))
        assertEquals("Ctrl + Enter to send", summary("Chat", onlySendKey))
        assertNull(onlySendKey.formatMessages)
        assertNull(onlySendKey.showThinking)
        assertNull(onlySendKey.autoFollow)
        assertNull(onlySendKey.animateBackground)
        assertEquals("A loaded but empty record claims nothing", "", summary("Chat", base.withLoaded(JSONObject())))
        assertEquals("Enter to send", summary("Chat", base.withLoaded(json("send_key" to "enter", "render_user_markdown" to "false", "show_thinking" to JSONObject.NULL, "auto_scroll_follow" to "false", "animate_background" to JSONObject.NULL))))
        assertEquals("Enter to send · Plain messages · Thinking shown · Auto-follow off · Background animation on", summary("Chat", base.withLoaded(json("send_key" to "enter", "render_user_markdown" to false, "show_thinking" to true, "auto_scroll_follow" to false, "animate_background" to true))))
    }
    @Test fun voiceSummaryNeedsKnownPhoneProviderAndKeyState() {
        assertEquals("Gemini Live · Key saved", summary("Voice & calls", base.copy(voiceProviderId = "gemini", voiceKeySaved = true)))
        assertEquals("Grok · Add key", summary("Voice & calls", base.copy(voiceProviderId = "grok", voiceKeySaved = false)))
        assertEquals("OpenAI Realtime · Key saved", summary("Voice & calls", base.copy(voiceProviderId = "openai", voiceKeySaved = true)))
        assertEquals("", summary("Voice & calls", base.copy(voiceProviderId = "gemini")))
        assertEquals("", summary("Voice & calls", base.copy(voiceProviderId = "unknown", voiceKeySaved = true)))
    }
    @Test fun overviewNeverAdvertisesRelayOnlyOrRemovedControls() {
        val forbidden = Regex("read.?aloud|insecure|http toggle|interrupt|plugin|relay", RegexOption.IGNORE_CASE)
        val known = SettingsSnapshot(connected = true, profile = "work", sendKey = "enter", formatMessages = true, showThinking = true, theme = "light", colorStyle = "default", fontSize = "default", language = "English", installedVersion = "0.4.14", voiceProviderId = "gemini", voiceKeySaved = true)
        for (route in settingsSearchRoutes) {
            val visible = "${settingsLabel(route)} ${settingsDescriptions[route].orEmpty()} ${summary(route, known)}"
            assertFalse("$route advertises unsupported capability: $visible", forbidden.containsMatchIn(visible))
        }
    }
    @Test fun skillInstructionsHideEmptyAndWhitespaceDelimitedFrontmatter() {
        for (source in listOf("---\n---\nBody", "---\nname: test\n--- \t\nBody", "\uFEFF---\r\nname: test\r\n---\r\nBody")) {
            assertEquals("Body", skillInstructionBody(source))
        }
        assertEquals("---\nUnclosed", skillInstructionBody("---\nUnclosed"))
    }
}
