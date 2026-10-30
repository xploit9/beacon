package dev.hermes.android.ui

import org.junit.Assert.*
import org.junit.Test

class QueuedUxTest {
    @Test fun composerRulesStayStable() {
        assertTrue(composerUsesVoice("  ", 0, 0))
        assertTrue(composerUsesVoice("", 0, 0))
        assertFalse(composerUsesVoice("hi", 0, 0))
        assertFalse(composerUsesVoice("hello", 0, 0))
        assertFalse(composerUsesVoice("", 1, 0))
        assertFalse(composerUsesVoice("", 0, 1))
        assertEquals(42f, composerActionSize.value, 0f)
    }
    @Test fun profilesAndBotsHaveDistinctRoutes() {
        assertEquals(NavigationRoute("More", "Bots"), navigationRoute("Bots"))
        assertEquals(NavigationRoute("More"), navigationRoute("Settings"))
        assertEquals("category:Profiles & model", settingsParent("Profiles"))
    }
}
