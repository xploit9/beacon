package dev.hermes.android.ui

import org.junit.Assert.*
import org.junit.Test

class RedesignNavigationTest {
    @Test fun everyShortcutHasAnHonestNativeDestination() {
        assertEquals(listOf("Skills", "Memory", "Cron", "Files", "Projects", "Bots", "Kanban", "Settings"), navigationTiles)
        assertEquals(NavigationRoute("More", "Skills"), navigationRoute("Skills"))
        assertEquals(NavigationRoute("More", "Memory"), navigationRoute("Memory"))
        assertEquals(NavigationRoute("Tasks"), navigationRoute("Cron"))
        assertEquals(NavigationRoute("Spaces"), navigationRoute("Files"))
        assertEquals(NavigationRoute("Projects"), navigationRoute("Projects"))
        assertEquals(NavigationRoute("More", "Bots"), navigationRoute("Bots"))
        assertEquals(NavigationRoute("Kanban"), navigationRoute("Kanban"))
        assertEquals(NavigationRoute("More"), navigationRoute("Settings"))
        assertEquals(NavigationRoute("Beacon"), navigationRoute("Beacon"))
    }
    @Test fun largeTypeReflowsIntoFullRows() {
        assertEquals(4, navigationColumns(320f, 1f))
        assertEquals(2, navigationColumns(320f, 2f))
        assertEquals(4, navigationColumns(260f, 1f))
        assertEquals(2, navigationColumns(230f, 1f))
        assertEquals(2, navigationColumns(260f, 1.3f))
        for (columns in listOf(2, 4)) {
            val rows = navigationRows(columns)
            assertEquals(navigationTiles, rows.flatten())
            // No tile is left alone on a row, stretched wider than the others.
            assertTrue(rows.all { it.size == columns })
        }
    }
    @Test fun projectSessionsRejectForeignHiddenAndUnlistedSessions() {
        assertTrue(nativeProjectSessionAllowed("stored:1", "personal", "personal", setOf("stored:1"), false))
        assertTrue(nativeProjectSessionAllowed("stored:1", "", "personal", setOf("stored:1"), false))
        assertFalse(nativeProjectSessionAllowed("stored:1", "other", "personal", setOf("stored:1"), false))
        assertFalse(nativeProjectSessionAllowed("foreign", "personal", "personal", setOf("stored:1"), false))
        assertFalse(nativeProjectSessionAllowed("stored:1", "personal", "personal", setOf("stored:1"), true))
        assertFalse(nativeProjectSessionAllowed("", "personal", "personal", setOf(""), false))
    }
    @Test fun projectDrillInRejectsMismatchedOrMissingProjectIdentity() {
        assertTrue(nativeProjectDetailCurrent("project-a", "project-a"))
        assertFalse(nativeProjectDetailCurrent("project-a", "project-b"))
        assertFalse(nativeProjectDetailCurrent("", ""))
    }
    @Test(expected = IllegalStateException::class) fun unknownDestinationsCannotSilentlyOpenSettings() { navigationRoute("Unsupported") }
}
