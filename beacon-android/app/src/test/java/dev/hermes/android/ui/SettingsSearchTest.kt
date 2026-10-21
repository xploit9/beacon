package dev.hermes.android.ui

import dev.hermes.android.core.json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsSearchTest {
    @Test fun searchesVisibleLabelAndDescriptionWithoutChangingRoute() {
        assertEquals("Chat", settingsLabel("Settings"))
        assertEquals("Voice & calls", settingsLabel("Beacon"))
        val chatDescription = settingsDescriptions.getValue("Chat")
        assertTrue(settingsSearchMatches(settingsLabel("Chat"), chatDescription, "chat display"))
        assertTrue(settingsSearchMatches(settingsLabel("Chat"), chatDescription, "SEND"))
        assertTrue(settingsSearchMatches(settingsLabel("Chat"), chatDescription, "sending  display"))
        assertTrue(settingsSearchMatches("Tools & MCP", "Tool groups and MCP servers", "tools & mcp"))
        assertFalse(settingsSearchMatches(settingsLabel("Chat"), chatDescription, "camera"))
        assertFalse(settingsSearchMatches(settingsLabel("Chat"), chatDescription, "sending camera"))
        assertTrue(settingsSearchMatches("Appearance", "Theme and color style", " "))
        assertTrue(settingsSearchMatches("Appearance", "Theme and color style", " appearance "))
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
            listOf("Connection", "Assistant", "Voice & calls", "Notifications & Keep connected", "Chat", "Profiles & model", "Skills", "Memory", "Tools & MCP", "Server", "Appearance", "App updates", "About & licenses",
                "Profiles", "Providers & models", "Tools & commands", "MCP servers", "Health", "Logs", "Server updates", "Session checkpoints"),
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
            listOf("Connection", "Assistant", "Beacon", "Notifications", "Settings", "category:Profiles & model", "Skills", "Memory", "category:Tools & MCP", "category:Server", "Appearance", "App updates", "Licenses"),
            settingsHubRoutes.map(::settingsRoute),
        )
        assertEquals("Sections in Relay's order with the assistant up front", listOf("", "Assistant", "Hermes", "App"), settingsHubSections.keys.toList())
        assertEquals(listOf("Profiles", "Providers & models"), settingsCategories.getValue("Profiles & model"))
        assertEquals(listOf("Tools & commands", "MCP servers"), settingsCategories.getValue("Tools & MCP"))
        assertEquals(listOf("Health", "Logs", "Server updates", "Session checkpoints"), settingsCategories.getValue("Server"))
        assertTrue("A page name must never collide with a category name", settingsCategories.values.flatten().none { it in settingsCategories })
    }
    @Test fun aBlankOrBrokenAssistantNameFallsBackToBeacon() {
        assertEquals("Nova", dev.hermes.android.data.assistantNameOf("  Nova "))
        for (bad in listOf(null, "", "   ", "No\u0000va")) assertEquals("Beacon", dev.hermes.android.data.assistantNameOf(bad))
        assertEquals(dev.hermes.android.data.ASSISTANT_NAME_MAX, dev.hermes.android.data.assistantNameOf("x".repeat(80)).length)
    }
    @Test fun retiredModelsAndToolsRoutesResolveToTheCurrentCategory() {
        assertEquals("category:Profiles & model", settingsRoute("Models & tools"))
        assertEquals("category:Profiles & model", normalizeSettingsPage("Models & tools"))
        assertEquals("category:Profiles & model", normalizeSettingsPage("category:Models & tools"))
        assertEquals("category:Server", normalizeSettingsPage("category:Server & support"))
        assertEquals("category:Server", normalizeSettingsPage("category:Health, logs & updates"))
        assertTrue(normalizeSettingsPage("category:Server & support").removePrefix("category:") in settingsCategories.keys)
        assertEquals("category:Tools & MCP", settingsRoute("Tools & MCP"))
    }
    @Test fun onlyPluginBackedPagesShowTheMissingPluginState() {
        assertFalse(settingsNeedsBeaconPlugin("Skills", "absent"))
        assertFalse(settingsNeedsBeaconPlugin("Memory", "absent"))
        assertFalse(settingsNeedsBeaconPlugin("Server updates", "absent"))
        assertFalse(settingsNeedsBeaconPlugin("Beacon", "absent"))
        assertTrue(settingsNeedsBeaconPlugin("Notifications", "absent"))
        assertFalse(settingsNeedsBeaconPlugin("Tools & commands", "absent"))
        assertFalse(settingsNeedsBeaconPlugin("App updates", "absent"))
        assertFalse(settingsNeedsBeaconPlugin("Skills", "error"))
        assertFalse(settingsNeedsBeaconPlugin("Skills", "not_probed"))
    }
    @Test fun backParentsStayCategoryAware() {
        assertEquals("category:Server", settingsParent("Health"))
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
            theme = "dark", colorStyle = "graphite",
            installedVersion = "0.4.14", voiceProviderId = "gemini", voiceKeySaved = true, autoFollow = true, animateBackground = false,
            keepConnectedEnabled = true,
        )
        assertEquals("Connected privately · work", summary("Connection", known))
        assertEquals("Connection needs attention · work", summary("Connection", known.copy(connected = false)))
        assertEquals("Active profile · work", summary("Profiles & model", known))
        // Two facts at most, so a hub row stays on one line.
        assertEquals("Ctrl + Enter to send · Thinking shown", summary("Chat", known))
        assertEquals("Dark · Graphite", summary("Appearance", known))
        assertEquals("Follow device · Beacon", summary("Appearance", SettingsSnapshot(theme = "system", colorStyle = "default")))
        assertEquals("Installed · 0.4.14", summary("App updates", known))
        assertEquals("Nova · coder", summary("Assistant", known.copy(assistantName = "Nova", assistantProfile = "coder")))
        assertEquals("Version · 0.4.14", summary("About & licenses", known))
        assertEquals("Version · 0.4.14", summary("About & licenses", known.copy(updateName = "0.4.15")))
        assertEquals("Update available · 0.4.15", summary("App updates", known.copy(updateName = "0.4.15")))
        assertEquals("Update ready to install", summary("App updates", known.copy(updateName = "0.4.15", updateReady = true)))
        assertEquals("Gemini Live · Key saved", summary("Voice & calls", known))
        assertEquals("Keep connected on", summary("Notifications & Keep connected", known))
        assertEquals("Keep connected off", summary("Notifications & Keep connected", known.copy(keepConnectedEnabled = false)))
        assertEquals("Graphite", summary("Appearance", SettingsSnapshot(theme = "sepia", colorStyle = "graphite")))
        assertEquals("Enter to send", summary("Chat", SettingsSnapshot(sendKey = "enter")))
        assertEquals("", summary("Chat", SettingsSnapshot(sendKey = "shift_enter")))
        for ((stored, label) in mapOf("enter" to "Enter to send", "ctrl_enter" to "Ctrl + Enter to send", "ctrl+enter" to "Ctrl + Enter to send", "shift+enter" to "Shift + Enter to send")) assertEquals(label, summary("Chat", SettingsSnapshot(sendKey = stored)))
        for (stored in listOf("", "shift_enter", "alt+enter", "Enter ")) assertNull("$stored is not a composer shortcut", settingsSendKeyLabel(stored))
        for (route in settingsSearchRoutes - setOf("Connection", "Profiles", "Profiles & model", "Chat", "Appearance", "App updates", "Voice & calls", "Notifications & Keep connected", "Assistant", "About & licenses")) assertTrue("$route has no observable state to summarize", settingsSummary(route, known).isEmpty())
    }
    @Test fun userDataStaysVerbatimWhileFixedWordsTranslate() {
        val parts = settingsSummary("Profiles & model", SettingsSnapshot(profile = "Settings"))
        assertEquals(listOf(SettingsSummaryPart("Active profile", true), SettingsSummaryPart("Settings", false)), parts)
        assertTrue(settingsSummary("App updates", SettingsSnapshot(installedVersion = "0.4.14")).last().let { !it.translate && it.text == "0.4.14" })
    }
    @Test fun colorStyleTranslatesLikeTheAppearancePicker() {
        // Known skins use the picker's label, which the picker passes through uiText.
        assertEquals(listOf(SettingsSummaryPart("Graphite", true)), settingsSummary("Appearance", SettingsSnapshot(colorStyle = "graphite")))
        assertEquals(listOf(SettingsSummaryPart("Beacon", true)), settingsSummary("Appearance", SettingsSnapshot(colorStyle = "default")))
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
        assertEquals("Ctrl + Enter to send · Thinking shown", summary("Chat", base.withLoaded(chat())))
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
        assertEquals("Enter to send · Thinking shown", summary("Chat", base.withLoaded(json("send_key" to "enter", "render_user_markdown" to false, "show_thinking" to true, "auto_scroll_follow" to false, "animate_background" to true))))
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
        val known = SettingsSnapshot(connected = true, profile = "work", sendKey = "enter", formatMessages = true, showThinking = true, theme = "light", colorStyle = "default", installedVersion = "0.4.14", voiceProviderId = "gemini", voiceKeySaved = true)
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
