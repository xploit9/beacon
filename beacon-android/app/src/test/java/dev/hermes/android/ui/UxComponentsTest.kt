package dev.hermes.android.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class UxComponentsTest {
    @Test fun tilesFillTwoColumnsInFourPairs() {
        assertEquals(
            listOf(listOf("Skills", "Memory"), listOf("Cron", "Files"), listOf("Projects", "Bots"), listOf("Kanban", "Settings")),
            navigationRows(2),
        )
        assertEquals(2, navigationRows(4).size)
        assertEquals(navigationTiles, navigationRows(4).flatten())
    }

    @Test fun theCronTileReadsSchedulesAndKeepsItsName() {
        assertEquals("Schedules", navigationLabel("Cron"))
        assertEquals("Files", navigationLabel("Files"))
        // The test tag and the route come from the name, so they stay "cron".
        assertEquals(NavigationRoute("Tasks"), navigationRoute("Cron"))
    }

    @Test fun inlineCodeTakesTheThemedBackground() {
        val paragraph = parseNativeMarkdown("Run `ls` now").children().first()
        val themed = Color.Red.copy(alpha = .08f)
        val annotated = inlineAnnotations(paragraph, Color.Blue, codeBackground = themed)
        assertTrue(annotated.spanStyles.any { it.item.background == themed && annotated.text.substring(it.start, it.end) == "ls" })
        val fallback = inlineAnnotations(paragraph, Color.Blue)
        assertTrue(fallback.spanStyles.any { it.item.background == Color.Gray.copy(alpha = .12f) })
    }
}
