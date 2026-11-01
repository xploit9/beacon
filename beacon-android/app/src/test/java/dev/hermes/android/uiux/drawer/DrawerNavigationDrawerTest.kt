package dev.hermes.android.uiux.drawer

import dev.hermes.android.core.Release
import dev.hermes.android.ui.DrawerMode
import dev.hermes.android.ui.DrawerModeAction
import dev.hermes.android.ui.NavigationStrings
import dev.hermes.android.ui.drawerModeAction
import dev.hermes.android.ui.drawerModeTag
import dev.hermes.android.ui.drawerUpdateReady
import dev.hermes.android.ui.navigationColumns
import dev.hermes.android.ui.navigationCompact
import dev.hermes.android.ui.navigationLabel
import dev.hermes.android.ui.navigationMainTiles
import dev.hermes.android.ui.navigationMore
import dev.hermes.android.ui.navigationRoute
import dev.hermes.android.ui.navigationRows
import dev.hermes.android.ui.navigationShown
import dev.hermes.android.ui.navigationTiles
import dev.hermes.android.updates.AppUpdateUi
import dev.hermes.android.updates.PluginRow
import dev.hermes.android.updates.PluginStatus
import dev.hermes.android.updates.UpdatePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerNavigationDrawerTest {
    @Test fun theChatsAndBotsSwitchEntersLeavesAndReturnsHome() {
        assertEquals(DrawerModeAction.EnterBots, drawerModeAction(botMode = false, tapped = DrawerMode.Bots))
        assertEquals(DrawerModeAction.LeaveBots, drawerModeAction(botMode = true, tapped = DrawerMode.Chats))
        // The segment already chosen: Bots opens the Bot Mode home (the old home button), Chats does nothing.
        assertEquals(DrawerModeAction.OpenBotHome, drawerModeAction(botMode = true, tapped = DrawerMode.Bots))
        assertEquals(DrawerModeAction.None, drawerModeAction(botMode = false, tapped = DrawerMode.Chats))
    }

    @Test fun eachSegmentKeepsTheTagOfTheControlItReplaced() {
        assertEquals("navigation-leave-bot-mode", drawerModeTag(botMode = true, mode = DrawerMode.Chats))
        assertEquals("navigation-bot-mode-home", drawerModeTag(botMode = true, mode = DrawerMode.Bots))
        assertEquals("navigation-mode-chats", drawerModeTag(botMode = false, mode = DrawerMode.Chats))
        assertEquals("navigation-mode-bots", drawerModeTag(botMode = false, mode = DrawerMode.Bots))
        val tags = listOf(true, false).flatMap { bot -> DrawerMode.entries.map { drawerModeTag(bot, it) } }
        assertEquals("Every tag is its own", tags.size, tags.toSet().size)
    }

    @Test fun theBotsTileIsADestinationNotASwitch() {
        // Same eight destinations and routes as before; the tile now reads "Bots" like the segment.
        assertEquals(listOf("Skills", "Memory", "Cron", "Files", "Projects", "Bots", "Kanban", "Settings"), navigationTiles)
        assertEquals("Bots", navigationLabel("Bots"))
        assertEquals("Schedules", navigationLabel("Cron"))
        assertTrue("Bots" in NavigationStrings && "Schedules" in NavigationStrings)
        assertEquals(navigationTiles.size, NavigationStrings.size)
        assertEquals("More", navigationRoute("Bots").tab)
        assertEquals("Bots", navigationRoute("Bots").page)
    }

    @Test fun anUpToDateBuildShowsAllEightTilesInPairs() {
        for (fontScale in listOf(0.85f, 1f, 1.1f)) {
            assertFalse(navigationCompact(fontScale))
            assertEquals(navigationTiles, navigationShown(fontScale))
            assertEquals(emptyList<String>(), navigationMore(fontScale))
        }
        assertEquals(4, navigationColumns(320f, 1.1f))
        assertEquals(listOf(listOf("Skills", "Memory", "Cron", "Files"), listOf("Projects", "Bots", "Kanban", "Settings")), navigationRows(4, 1f))
    }

    @Test fun aboveOnePointOneTheGridKeepsFourTilesAndMoreHoldsTheRest() {
        for (fontScale in listOf(1.11f, 1.3f, 1.6f, 2f)) {
            assertTrue(navigationCompact(fontScale))
            val columns = navigationColumns(320f, fontScale)
            assertEquals(2, columns)
            assertEquals(listOf(listOf("Cron", "Files"), listOf("Projects", "Settings")), navigationRows(columns, fontScale))
            assertEquals(listOf("Skills", "Memory", "Bots", "Kanban"), navigationMore(fontScale))
            // Nothing is lost: every destination is in the grid or under More, exactly once.
            assertEquals(navigationTiles.sorted(), (navigationShown(fontScale) + navigationMore(fontScale)).sorted())
        }
        assertEquals(4, navigationMainTiles.size)
        assertTrue(navigationTiles.containsAll(navigationMainTiles))
        // Settings carries the update badge, so it stays in view.
        assertTrue("Settings" in navigationMainTiles)
    }

    private val release = Release(132, "1.1.22", "dev.hermes.android", "https://example.com/Beacon-1.1.22.apk", "b".repeat(64), 1024, "a".repeat(64), "1.1.22")

    @Test fun updateReadyFollowsTheExistingUpdateState() {
        assertTrue(drawerUpdateReady(AppUpdateUi(phase = UpdatePhase.Available, release = release)))
        assertTrue(drawerUpdateReady(AppUpdateUi(phase = UpdatePhase.NeedsPermission, release = release)))
        // A plugin that can update counts when the app is current; one that failed or is busy does not read as "ready".
        assertTrue(drawerUpdateReady(AppUpdateUi(phase = UpdatePhase.Available, plugin = PluginRow(PluginStatus.Available))))
        assertTrue(drawerUpdateReady(AppUpdateUi(phase = UpdatePhase.Available, plugin = PluginRow(PluginStatus.NeedsReview))))
        assertTrue(drawerUpdateReady(AppUpdateUi(phase = UpdatePhase.Available, plugin = PluginRow(PluginStatus.Restart))))
        assertFalse(drawerUpdateReady(AppUpdateUi(phase = UpdatePhase.Available, plugin = PluginRow(PluginStatus.Failed))))
        assertFalse(drawerUpdateReady(AppUpdateUi(phase = UpdatePhase.Available, plugin = PluginRow(PluginStatus.Busy))))
        for (phase in listOf(UpdatePhase.Idle, UpdatePhase.Checking, UpdatePhase.Downloading, UpdatePhase.Installing, UpdatePhase.Failed)) {
            assertFalse("$phase", drawerUpdateReady(AppUpdateUi(phase = phase, release = release)))
        }
        // Preview and debug builds never update themselves, so they never show the chip.
        assertFalse(drawerUpdateReady(AppUpdateUi(phase = UpdatePhase.Available, release = release, supported = false)))
    }
}
