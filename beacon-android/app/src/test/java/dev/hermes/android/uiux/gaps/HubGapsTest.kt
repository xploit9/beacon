package dev.hermes.android.uiux.gaps

import dev.hermes.android.ui.SettingsRowSummary
import dev.hermes.android.ui.SettingsSnapshot
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.serverHealthStatus
import dev.hermes.android.ui.settingsCategories
import dev.hermes.android.ui.settingsDescriptions
import dev.hermes.android.ui.settingsLabel
import dev.hermes.android.ui.settingsKeywordsOf
import dev.hermes.android.ui.settingsRoute
import dev.hermes.android.ui.settingsRowMatches
import dev.hermes.android.ui.settingsRowSummary
import dev.hermes.android.ui.settingsSearchMatches
import dev.hermes.android.ui.settingsSearchRoutes
import dev.hermes.android.ui.settingsSummary
import org.junit.Assert.*
import org.junit.Test

/** Settings hub: search keywords and the clear action, the Tools and Server child rows, and the Health pill against the Health page. */
class HubGapsTest {
    private fun row(route: String, snapshot: SettingsSnapshot) = settingsRowSummary(settingsSummary(route, snapshot)) { it }

    @Test fun aBlankSearchMatchesEveryRowSoClearingItBringsTheListBack() {
        for (route in settingsSearchRoutes) {
            assertTrue("$route must match an empty search", settingsSearchMatches(settingsLabel(route), settingsDescriptions[route].orEmpty(), ""))
            assertTrue("$route must match a blank search", settingsSearchMatches(settingsLabel(route), settingsDescriptions[route].orEmpty(), "    "))
        }
    }

    @Test fun anEnglishKeywordFindsItsRowByTheEnglishLabel() {
        assertTrue(settingsSearchMatches("Connection", settingsDescriptions.getValue("Connection"), "sign out"))
        assertTrue(settingsSearchMatches("Chat", settingsDescriptions.getValue("Chat"), "steer"))
        assertFalse(settingsSearchMatches("Connection", settingsDescriptions.getValue("Connection"), "steer"))
    }

    /** The hub gives its row the label, description and keywords as the phone shows them; the English route name lets the English words match in every language. */
    @Test fun theKeywordsFindTheirRowInEveryLanguage() {
        val probes = mapOf("Connection" to "sign out", "Chat" to "steer", "Appearance" to "dark", "Notifications & Keep connected" to "delivery")
        val misses = GapsAssets.languages.filter { it != "en" }.flatMap { language ->
            probes.mapNotNull { (route, query) ->
                fun shown(text: String) = GapsAssets.translated(language, text).ifBlank { text }
                "$language: $route / $query".takeUnless {
                    settingsRowMatches(route, shown(settingsLabel(route)), shown(settingsDescriptions.getValue(route)), shown(settingsKeywordsOf(route)), query)
                }
            }
        }
        assertTrue("Keywords that find nothing:\n" + misses.take(20).joinToString("\n"), misses.isEmpty())
    }

    @Test fun theToolsAndMcpChildRowsShowKnownCountsOnlyAndAZeroIsKnown() {
        assertEquals(SettingsRowSummary("3 on"), row("Tools & commands", SettingsSnapshot(toolsOn = 3)))
        assertEquals("None enabled is a known count, not an unknown one", SettingsRowSummary("0 on"), row("Tools & commands", SettingsSnapshot(toolsOn = 0)))
        assertEquals(SettingsRowSummary("2 servers"), row("MCP servers", SettingsSnapshot(mcpServers = 2)))
        assertEquals("One server is not \"1 servers\"", SettingsRowSummary("1 server"), row("MCP servers", SettingsSnapshot(mcpServers = 1)))
        assertEquals(SettingsRowSummary("0 servers"), row("MCP servers", SettingsSnapshot(mcpServers = 0)))
        assertEquals("An unread list keeps the row's own description", SettingsRowSummary(), row("Tools & commands", SettingsSnapshot()))
        assertEquals(SettingsRowSummary(), row("MCP servers", SettingsSnapshot()))
        assertEquals(listOf("Tools & commands", "MCP servers"), settingsCategories.getValue("Tools & MCP"))
    }

    @Test fun theCheckpointRowShowsTheStoredSizeVerbatimOrNothing() {
        assertEquals(SettingsRowSummary("12.4 MB"), row("Session checkpoints", SettingsSnapshot(checkpointSize = "12.4 MB")))
        assertEquals(SettingsRowSummary(), row("Session checkpoints", SettingsSnapshot()))
        assertEquals("A blank size is no size", SettingsRowSummary(), row("Session checkpoints", SettingsSnapshot(checkpointSize = "  ")))
    }

    @Test fun everyCategoryOpensItsOwnListAndAdvancedIsNotACategory() {
        for (category in settingsCategories.keys) assertEquals("category:$category", settingsRoute(category))
        assertFalse("The Advanced list is a page name only; no hub row names it", "Advanced" in settingsCategories.keys)
        assertEquals("An unknown name opens a page of that name", "Advanced", settingsRoute("Advanced"))
    }

    /** The hub row reads Hermes' own overall word through the Health page's mapping, so a failed server reads as a failure in both places. */
    @Test fun theHubHealthPillAndTheHealthHeroAgreeOnAFailedServer() {
        val hub = row("Health", SettingsSnapshot(serverOverall = "down")).status
        assertEquals(serverHealthStatus("down", loading = false), hub)
        assertEquals(SettingsStatus.Error, hub)
        for (overall in listOf("ok", "degraded", "down", "error", "stopped")) {
            assertEquals("$overall reads the same on the hub and on the page", serverHealthStatus(overall, loading = false), row("Health", SettingsSnapshot(serverOverall = overall)).status)
        }
    }
}
