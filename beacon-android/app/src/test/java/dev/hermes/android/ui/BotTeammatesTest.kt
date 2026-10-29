package dev.hermes.android.ui

import dev.hermes.android.core.json
import dev.hermes.android.data.BotModeBot
import dev.hermes.android.data.BotModePreview
import dev.hermes.android.data.BotModeSpace
import org.junit.Assert.assertEquals
import org.junit.Test

class BotTeammatesTest {
    private val profiles = listOf(
        json("name" to "coder", "display_name" to "Code profile", "model" to "gpt-x"),
        json("name" to "writer", "display_name" to "", "model" to "claude-y"),
        json("name" to "plain"),
    )

    @Test fun aBotsNameIsHermesDisplayNameThenThePluginTitleThenTheId() {
        assertEquals("Code profile", botName(BotModeBot("coder", "coder", ""), profiles))
        assertEquals("Writer", botName(BotModeBot("writer", "Writer", ""), profiles))
        assertEquals("plain", botName(BotModeBot("plain", "", ""), profiles))
        // Before the profile list has loaded the plugin's title is used rather than a placeholder.
        assertEquals("Maya", botName(BotModeBot("maya", "Maya", ""), emptyList()))
    }

    @Test fun theLineUnderABotIsItsRoleElseTheModelElseNothing() {
        assertEquals("Reviews code", botRole(BotModeBot("coder", "coder", "  Reviews code "), profiles))
        assertEquals("claude-y", botRole(BotModeBot("writer", "writer", ""), profiles))
        assertEquals("claude-y", botRole(BotModeBot("writer", "writer", "   "), profiles))
        assertEquals("", botRole(BotModeBot("plain", "plain", ""), profiles))
        assertEquals("", botRole(BotModeBot("unknown", "unknown", ""), profiles))
    }

    @Test fun aGroupsSecondLineIsTheNewestMessageWithItsSenderElseItsBots() {
        val name = { id: String -> id.replaceFirstChar { it.uppercase() } }
        assertEquals("You: Plan it", spacePreview(BotModeSpace("r", "Room", listOf("coder"), last = BotModePreview("You", "Plan it")), name, "You"))
        assertEquals("Coder: Done", spacePreview(BotModeSpace("r", "Room", listOf("coder"), last = BotModePreview("coder", "Done")), name, "You"))
        assertEquals("Vous: Salut", spacePreview(BotModeSpace("r", "Room", last = BotModePreview("You", "Salut")), name, "Vous"))
        assertEquals("Default, Coder", spacePreview(BotModeSpace("r", "Room", listOf("default", "coder")), name, "You"))
        assertEquals("", spacePreview(BotModeSpace("r", "Room"), name, "You"))
    }

    @Test fun newChatSuggestsTheNamesOfTheBotsInTheOrderTheyAreListed() {
        val bots = listOf(NewChatBot("default", "Default"), NewChatBot("coder", "Coder"), NewChatBot("writer", "Writer"))
        assertEquals("Default, Writer", newChatName(bots, setOf("writer"), "default"))
        assertEquals("Default, Coder, Writer", newChatName(bots, setOf("writer", "coder"), "default"))
        assertEquals("Default", newChatName(bots, emptySet(), "default"))
        assertEquals("Coder", newChatName(bots, setOf("coder"), ""))
        assertEquals(120, newChatName(listOf(NewChatBot("a", "x".repeat(100)), NewChatBot("b", "y".repeat(100))), setOf("b"), "a").length)
    }
}
