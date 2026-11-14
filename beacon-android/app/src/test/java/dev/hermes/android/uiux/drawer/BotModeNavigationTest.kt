package dev.hermes.android.uiux.drawer

import dev.hermes.android.ui.BotModeNavigationStrings
import dev.hermes.android.ui.botModeCurrentBot
import dev.hermes.android.ui.navigationBotLabel
import dev.hermes.android.ui.navigationBotTile
import dev.hermes.android.ui.navigationColumns
import dev.hermes.android.ui.navigationLabel
import dev.hermes.android.ui.navigationMore
import dev.hermes.android.ui.navigationRoute
import dev.hermes.android.ui.navigationRows
import dev.hermes.android.ui.navigationShown
import dev.hermes.android.ui.navigationTiles
import dev.hermes.android.ui.navigationTilesFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bot Mode keeps the drawer's eight slots and swaps a tile only where Beacon's Bot Mode has one that fits: the slots, never the sizes. */
class BotModeNavigationTest {
    @Test fun botModeKeepsEightSlotsInTheSameOrderAndSwapsOnlyProjects() {
        val bot = navigationTilesFor(true)
        assertEquals(listOf("Skills", "Vault", "Cron", "Files", "Memory", "Bots", "Kanban", "Settings"), bot)
        assertEquals(navigationTiles, navigationTilesFor(false))
        assertEquals(navigationTiles.size, bot.size)
        // Every other slot is the normal tile; Bots stays in its slot because it is the switch back.
        navigationTiles.indices.filter { navigationTiles[it] != "Projects" }.forEach { assertEquals(navigationTiles[it], bot[it]) }
        assertEquals(navigationTiles.indexOf("Bots"), bot.indexOf("Bots"))
        assertEquals("Memory", navigationBotTile("Projects"))
        navigationTiles.filter { it != "Projects" }.forEach { assertEquals(it, navigationBotTile(it)) }
    }

    @Test fun theGridHasTheSameShapeInBothModesAtEveryFontScale() {
        for (fontScale in listOf(0.85f, 1f, 1.1f, 1.15f, 1.3f, 2f)) {
            val columns = navigationColumns(320f, fontScale)
            val normal = navigationRows(columns, fontScale)
            val bot = navigationRows(columns, fontScale, botMode = true)
            assertEquals("rows at $fontScale", normal.map { it.size }, bot.map { it.size })
            assertEquals("More at $fontScale", navigationMore(fontScale).size, navigationMore(fontScale, botMode = true).size)
            // The slot of every tile that is not swapped is unchanged.
            normal.flatten().zip(bot.flatten()).forEach { (a, b) -> assertEquals(navigationBotTile(a), b) }
            // Nothing is lost: each Bot Mode tile is in the grid or under More, once.
            assertEquals(navigationTilesFor(true).sorted(), (navigationShown(fontScale, botMode = true) + navigationMore(fontScale, botMode = true)).sorted())
        }
        assertEquals(listOf(listOf("Skills", "Vault", "Cron", "Files"), listOf("Memory", "Bots", "Kanban", "Settings")), navigationRows(4, 1f, botMode = true))
        // At a large font the four main tiles and More, with Memory in the Projects slot.
        assertEquals(listOf(listOf("Cron", "Files"), listOf("Memory", "Settings")), navigationRows(2, 1.3f, botMode = true))
        assertEquals(listOf("Skills", "Vault", "Bots", "Kanban"), navigationMore(1.3f, botMode = true))
    }

    @Test fun theSchedulesTileReadsRoutinesInBotModeAndKeepsItsDestination() {
        assertEquals("Schedules", navigationLabel("Cron"))
        assertEquals("Schedules", navigationBotLabel("Cron"))
        navigationTiles.filter { it != "Cron" }.forEach { assertEquals(navigationLabel(it), navigationBotLabel(it)) }
        assertEquals("Memory", navigationBotLabel("Memory"))
        assertEquals("Tasks", navigationRoute("Cron").tab)
        assertEquals("More", navigationRoute("Memory").tab)
        assertEquals("Memory", navigationRoute("Memory").page)
    }

    @Test fun everyBotModeTileWordIsListedForTheTranslationCheck() {
        assertEquals(8, BotModeNavigationStrings.size)
        assertTrue("Schedules" in BotModeNavigationStrings && "Memory" in BotModeNavigationStrings && "Routines" !in BotModeNavigationStrings)
    }

    @Test fun theMemoryTileOpensTheBotTheDrawerIsOn() {
        // An open Bot Chat wins; otherwise the bot Bot Mode runs as.
        assertEquals("coder", botModeCurrentBot("coder", "default"))
        assertEquals("default", botModeCurrentBot(null, "default"))
        assertEquals("default", botModeCurrentBot("", "default"))
        assertEquals("", botModeCurrentBot(null, ""))
    }
}
