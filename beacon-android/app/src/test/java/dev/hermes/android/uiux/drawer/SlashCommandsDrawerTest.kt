package dev.hermes.android.uiux.drawer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.ui.ConversationStrings
import dev.hermes.android.ui.SlashCommandStrings
import dev.hermes.android.ui.mobileSlashCommands
import dev.hermes.android.ui.slashCommandIcon
import dev.hermes.android.ui.slashCommandText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsDrawerTest {
    private fun described(name: String, description: String) = json("name" to name, "description" to description)

    @Test fun commonCommandsReadAsShortUserText() {
        val shown = mobileSlashCommands(listOf(
            described("new", "Start a new session (fresh session ID + history) (usage: /new [name])"),
            described("save", "Export the current conversation (bare /save shows usage)…"),
            described("retry", "Retry the last message (resend to agent)"),
            described("/clear", "Clear screen and start a new session"),
        ))
        assertEquals(listOf("New chat", "Export chat", "Try again", "Start fresh"), shown.map { it.string("description") })
        // The command itself keeps its name, so what runs is unchanged.
        assertEquals(listOf("new", "save", "retry", "/clear"), shown.map { it.string("name") })
    }

    @Test fun noRowKeepsUsageOrParentheticalMechanics() {
        val raw = listOf(
            described("alpha", "Do the alpha thing (usage: /alpha [name])"),
            described("beta", "Do the beta thing (see docs) (alias: b)"),
            described("gamma", "Do the gamma thing (usage: /gamma [option (optional)])"),
            described("delta", "Do the delta thing (usage: /delta [argu"),
            described("epsilon", "(usage: /epsilon)"),
        )
        val shown = mobileSlashCommands(raw).map { it.string("description") }
        assertEquals(listOf("Do the alpha thing", "Do the beta thing", "Do the gamma thing", "Do the delta thing", "(usage: /epsilon)"), shown)
        assertTrue(shown.dropLast(1).none { "(" in it || "usage" in it })
    }

    @Test fun anUnknownCommandKeepsHermesWordsAndTheSameObject() {
        val plain = described("zeta", "Summon the zeta helper")
        assertSame(plain, mobileSlashCommands(listOf(plain)).single())
        assertEquals("Summon the zeta helper", slashCommandText("zeta", "Summon the zeta helper"))
    }

    @Test fun terminalOnlyCommandsAreStillLeftOut() {
        val shown = mobileSlashCommands(listOf(
            described("redraw", "Redraw the screen"),
            described("skin", "Change the skin (CLI only)"),
            described("help", "Show available commands"),
        ))
        assertEquals(listOf("help"), shown.map { it.string("name") })
        assertEquals("Show commands", shown.single().string("description"))
    }

    @Test fun theOriginalRowIsNeverChanged() {
        val original = described("new", "Start a new session (usage: /new)")
        mobileSlashCommands(listOf(original))
        assertEquals("Start a new session (usage: /new)", original.string("description"))
    }

    @Test fun everyRowHasAnIconAndTheUnknownOnesShareTheTerminalMark() {
        assertSame(Icons.Rounded.Terminal, slashCommandIcon("zeta"))
        assertSame(Icons.Rounded.Terminal, slashCommandIcon("/anything-else"))
        val known = listOf("new", "clear", "history", "save", "retry", "undo", "title", "branch", "compress", "stop", "status", "model", "usage", "queue", "background", "reasoning", "skills", "tools")
        assertTrue(known.all { slashCommandIcon(it) !== Icons.Rounded.Terminal })
        assertSame(slashCommandIcon("new"), slashCommandIcon("/new"))
    }

    @Test fun theShortTextsAreInTheTranslatedStringList() {
        assertTrue(SlashCommandStrings.isNotEmpty() && SlashCommandStrings.toSet().size == SlashCommandStrings.size)
        assertTrue(SlashCommandStrings.all { it in ConversationStrings })
        assertFalse(SlashCommandStrings.any { "usage" in it.lowercase() && "(" in it })
    }
}
