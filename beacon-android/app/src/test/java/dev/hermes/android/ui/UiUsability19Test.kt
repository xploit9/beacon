package dev.hermes.android.ui

import org.junit.Assert.*
import org.junit.Test

class UiUsability19Test {
    @Test fun choicesLabelOfferedBlankValuesWithoutChangingRawIds() {
        assertEquals("Default", choiceDisplayLabel("", emptyMap(), true))
        assertEquals("Choose", choiceDisplayLabel("", emptyMap(), false))
        assertEquals("Use profile default", choiceDisplayLabel("", mapOf("" to "Use profile default"), true))
        assertEquals("Use profile default", choiceDisplayLabel("", mapOf("" to "Use profile default"), false))
        assertEquals("Default", choiceDisplayLabel("", mapOf("" to " "), true))
        assertEquals("Default tier", choiceDisplayLabel("default", mapOf("default" to "Default tier"), true))
        assertEquals("priority", choiceDisplayLabel("priority", emptyMap(), true))
        assertEquals("provider/Model_ID-v1", choiceDisplayLabel("provider/Model_ID-v1", emptyMap(), true))
    }

    @Test fun slashCommandsShowNoUsageOrParentheticalMechanics() {
        val commands = listOf(
            dev.hermes.android.core.json("name" to "new", "description" to "Start a new session (fresh session ID + history) (usage: /new [name])"),
            dev.hermes.android.core.json("name" to "plugin-thing", "description" to "Do a thing (usage: /plugin-thing <x>)"),
        )
        val shown = mobileSlashCommands(commands).map { it.getString("description") }
        assertEquals(listOf("New chat", "Do a thing"), shown)
        assertTrue(shown.none { it.contains("(usage:") })
    }

    @Test fun composerBlurCannotChangeTheDraftDuringCallOrAfterScopeChange() {
        val originalClient = Any()
        assertTrue(acceptsComposerEdit(originalClient, originalClient, "owner/profile/session", "owner/profile/session", false))
        assertFalse(acceptsComposerEdit(originalClient, originalClient, "owner/profile/session", "owner/profile/session", true))
        val anotherCallActiveHere = true && "owner/profile/other-session" == "owner/profile/session"
        assertTrue(acceptsComposerEdit(originalClient, originalClient, "owner/profile/session", "owner/profile/session", anotherCallActiveHere))
        assertFalse(acceptsComposerEdit(originalClient, Any(), "owner/profile/session", "owner/profile/session", false))
        for (changed in listOf("new-owner/profile/session", "owner/new-profile/session", "owner/profile/new-session")) {
            assertFalse(acceptsComposerEdit(originalClient, originalClient, "owner/profile/session", changed, false))
        }
        assertTrue(acceptsComposerEdit(originalClient, originalClient, null, null, false))
        assertFalse(acceptsComposerEdit(originalClient, originalClient, null, "owner/profile/session", false))
    }

}
