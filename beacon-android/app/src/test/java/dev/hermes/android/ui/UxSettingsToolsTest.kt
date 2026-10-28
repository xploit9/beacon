package dev.hermes.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The wording the Settings pages share: one name per thing, and no assumptions about the user's network. */
class UxSettingsToolsTest {
    @Test fun toolsAndMcpServersKeepOneNameEverywhere() {
        assertEquals("Tools", settingsLabel("Tools & commands"))
        assertEquals("MCP servers", settingsLabel("MCP servers"))
        assertEquals("Tools & MCP", settingsLabel("Tools & MCP"))
        assertFalse(settingsDescriptions.getValue("MCP servers").contains("connection", ignoreCase = true))
    }

    @Test fun theConnectionSummarySaysConnectedWithoutAssumingTailscale() {
        val connected = settingsSummary("Connection", SettingsSnapshot(connected = true)).map { it.text }
        val offline = settingsSummary("Connection", SettingsSnapshot(connected = false)).map { it.text }
        assertEquals(listOf("Connected"), connected)
        assertEquals(listOf("Connection needs attention"), offline)
    }
}
