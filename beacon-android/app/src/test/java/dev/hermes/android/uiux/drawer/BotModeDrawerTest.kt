package dev.hermes.android.uiux.drawer

import dev.hermes.android.data.BotModeMember
import dev.hermes.android.data.BotModeSpace
import dev.hermes.android.ui.SUGGESTED_ROLES_BELOW
import dev.hermes.android.ui.SuggestedRole
import dev.hermes.android.ui.botSpaceCounts
import dev.hermes.android.ui.botStatus
import dev.hermes.android.ui.botsReplying
import dev.hermes.android.ui.suggestedRolesOffered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Bot Mode drawer says about each bot, and which roles it suggests; the layout itself is in BotModeDrawerUiTest. */
class BotModeDrawerTest {
    private val roster = listOf(BotModeMember("m-coder", "coder", "coder"), BotModeMember("m-default", "default", "default"))
    private val catalog = listOf(
        SuggestedRole("researcher", "Researcher", "Finds sources and sums them up."),
        SuggestedRole("planner", "Planner", "Turns goals into clear next steps."),
        SuggestedRole("writer", "Writer", "Drafts and polishes your text."),
        SuggestedRole("inbox-helper", "Inbox helper", "Sorts messages and drafts replies."),
    )

    @Test fun aBotIsReplyingWhenHermesNamesItsMemberAsTyping() {
        assertEquals(setOf("coder"), botsReplying(listOf("m-coder"), roster))
        // A member id can be the profile itself, and an id nobody knows is kept as it came.
        assertEquals(setOf("coder", "writer"), botsReplying(listOf("coder", "writer"), roster))
        assertEquals(emptySet<String>(), botsReplying(emptyList(), roster))
    }

    @Test fun spacesAreCountedPerBotOnceEach() {
        val spaces = listOf(
            BotModeSpace("a", "Plan", listOf("default", "coder")),
            BotModeSpace("b", "Review", listOf("coder", "coder", "writer")),
        )
        assertEquals(mapOf("default" to 1, "coder" to 2, "writer" to 1), botSpaceCounts(spaces))
        assertEquals(emptyMap<String, Int>(), botSpaceCounts(emptyList()))
    }

    @Test fun theStatusIsTypingFirstThenTheSpacesTheBotIsInThenNothing() {
        assertEquals("Typing…", botStatus(true, 3, "Typing…", "Spaces: {0}"))
        assertEquals("Spaces: 2", botStatus(false, 2, "Typing…", "Spaces: {0}"))
        // No spaces: nothing is invented.
        assertEquals("", botStatus(false, 0, "Typing…", "Spaces: {0}"))
    }

    @Test fun suggestedRolesAreForASmallTeamAndSkipWhatAlreadyExists() {
        assertEquals(catalog.map { it.id }, suggestedRolesOffered(catalog, listOf("default", "coder"), 2).map { it.id })
        // A role whose id is already a profile is not offered a second time.
        assertEquals(listOf("planner", "writer", "inbox-helper"), suggestedRolesOffered(catalog, listOf("default", "researcher"), 2).map { it.id })
        // Nothing is left to suggest once all four exist, and a full team needs no suggestions.
        assertTrue(suggestedRolesOffered(catalog, catalog.map { it.id }, 2).isEmpty())
        assertTrue(suggestedRolesOffered(catalog, listOf("default"), SUGGESTED_ROLES_BELOW).isEmpty())
    }
}
