package dev.hermes.android.ui

import org.junit.Assert.*
import org.junit.Test

class SettingsSearchTest {
    @Test fun searchesVisibleLabelAndDescriptionWithoutChangingRoute() {
        assertEquals("Chat preferences", settingsLabel("Settings"))
        assertTrue(settingsSearchMatches(settingsLabel("Settings"), "Reading and sending messages", "chat preferences"))
        assertTrue(settingsSearchMatches(settingsLabel("Settings"), "Reading and sending messages", "READING"))
        assertTrue(settingsSearchMatches(settingsLabel("Settings"), "Reading and sending messages", "reading  preferences"))
        assertTrue(settingsSearchMatches("Models & tools", "Models, tools and service connections", "models & tools"))
        assertFalse(settingsSearchMatches(settingsLabel("Settings"), "Reading and sending messages", "camera"))
        assertFalse(settingsSearchMatches(settingsLabel("Settings"), "Reading and sending messages", "reading camera"))
        assertTrue(settingsSearchMatches("Appearance", "Theme, text size and language", " "))
        assertTrue(settingsSearchMatches("Appearance", "Theme, text size and language", " appearance "))
        assertTrue(modelSearchMatches("provider", "model", "Configured model", " model "))
    }
    @Test fun skillInstructionsHideEmptyAndWhitespaceDelimitedFrontmatter() {
        for (source in listOf("---\n---\nBody", "---\nname: test\n--- \t\nBody", "\uFEFF---\r\nname: test\r\n---\r\nBody")) {
            assertEquals("Body", skillInstructionBody(source))
        }
        assertEquals("---\nUnclosed", skillInstructionBody("---\nUnclosed"))
    }
}
