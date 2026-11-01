package dev.hermes.android.ux2text

import dev.hermes.android.ui.FileCrumb
import dev.hermes.android.ui.FileTrailView
import dev.hermes.android.ui.TrailFold
import dev.hermes.android.ui.chooseTrailFold
import dev.hermes.android.ui.fileBreadcrumbs
import dev.hermes.android.ui.fileTrailFolds
import dev.hermes.android.ui.fileTrailView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX-49: the Files trail folds Home into the "…" menu whenever the measured trail is wider than the row, so nothing is sliced. */
class TrailFoldTest {
    private val deep = fileBreadcrumbs("D:\\Shared\\Team\\Documents\\Atelier relaunch")
    // A 411 dp screen at 2.625 px per dp.
    private val row411 = 1079

    private fun labels(crumbs: List<FileCrumb>) = crumbs.map(FileCrumb::label)

    @Test fun aTrailThatFitsTheRowStaysWhole() {
        assertEquals(TrailFold.Whole, chooseTrailFold(TrailFold.entries, listOf(900, 700, 400), row411))
        // Exactly as wide as the row still fits: nothing is cut.
        assertEquals(TrailFold.Whole, chooseTrailFold(TrailFold.entries, listOf(row411, 700, 400), row411))
        // Fullest first: a later fold is never measured once an earlier one fits.
        assertEquals(TrailFold.Whole, chooseTrailFold(TrailFold.entries, listOf(900), row411))
    }

    @Test fun homeFoldsIntoTheMenuBeforeAnyLevelIsDropped() {
        assertEquals(TrailFold.HomeFolded, chooseTrailFold(TrailFold.entries, listOf(row411 + 1, 830, 500), row411))
        val view = fileTrailView(deep, TrailFold.HomeFolded)
        assertTrue(view.homeInMenu)
        assertTrue(view.hasMenu)
        // "… › Documents › Atelier relaunch": the levels the row keeps are the last two, the rest wait behind the "…".
        assertEquals(listOf("Documents", "Atelier relaunch"), labels(view.shown))
        assertEquals(listOf("D:", "Shared", "Team"), labels(view.menu))
    }

    @Test fun onlyTheLastLevelStaysWhenFoldingHomeIsNotEnough() {
        assertEquals(TrailFold.LastOnly, chooseTrailFold(TrailFold.entries, listOf(1500, 1200, 600), row411))
        val view = fileTrailView(deep, TrailFold.LastOnly)
        assertTrue(view.homeInMenu)
        assertEquals(listOf("Atelier relaunch"), labels(view.shown))
        assertEquals(listOf("D:", "Shared", "Team", "Documents"), labels(view.menu))
    }

    @Test fun whenNothingFitsTheLastFoldIsUsed() {
        assertEquals(TrailFold.LastOnly, chooseTrailFold(TrailFold.entries, listOf(3000, 2000, 1500), 1000))
        assertEquals(TrailFold.HomeFolded, chooseTrailFold(listOf(TrailFold.Whole, TrailFold.HomeFolded), listOf(3000), 1000))
    }

    @Test fun everyFoldKeepsEveryLevelReachableInOrder() {
        for (path in listOf("D:\\Shared\\Team\\Documents\\Atelier relaunch", "/home/me/work/project/src", "/home/me", "/", "D:\\a\\b")) {
            val trail = fileBreadcrumbs(path)
            for (fold in fileTrailFolds(trail)) {
                val view = fileTrailView(trail, fold)
                assertEquals("$path $fold", trail, view.menu + view.shown)
                // Home is a chip of its own only while it is not folded.
                assertEquals("$path $fold", fold != TrailFold.Whole, view.homeInMenu)
                // The chip tags count from the whole trail, so the last chip is always the last level.
                assertEquals("$path $fold", trail.lastIndex, trail.size - view.shown.size + view.shown.lastIndex)
            }
        }
    }

    @Test fun foldsThatWouldRepeatEachOtherAreLeftOut() {
        assertEquals(listOf(TrailFold.Whole), fileTrailFolds(emptyList()))
        // One level: folding Home already leaves just that level.
        assertEquals(listOf(TrailFold.Whole, TrailFold.HomeFolded), fileTrailFolds(fileBreadcrumbs("/")))
        // Two levels still differ: folding Home keeps both, the last-level-only fold keeps one.
        assertEquals(TrailFold.entries, fileTrailFolds(fileBreadcrumbs("/home")))
        assertEquals(TrailFold.entries, fileTrailFolds(deep))
    }

    @Test fun noTrailMeansOnlyHomeAndItNeverFolds() {
        for (fold in TrailFold.entries) {
            val view = fileTrailView(emptyList(), fold)
            assertFalse(view.homeInMenu)
            assertFalse(view.hasMenu)
            assertTrue(view.shown.isEmpty())
        }
    }

    @Test fun aFoldedViewIsAlwaysNarrowerThanTheWholeOne() {
        // Measured as the row draws it: a chip is its label plus chrome, "…" is one short chip, an arrow sits between chips.
        fun chip(label: String) = label.length * 30 + 120
        fun width(view: FileTrailView): Int {
            val chips = (if (view.homeInMenu) 0 else 1) + (if (view.hasMenu) 1 else 0) + view.shown.size
            val labelsWidth = (if (view.homeInMenu) 0 else chip("Home")) + (if (view.hasMenu) 140 else 0) + view.shown.sumOf { chip(it.label) }
            return labelsWidth + (chips - 1).coerceAtLeast(0) * 72 + 24
        }
        val widths = TrailFold.entries.map { width(fileTrailView(deep, it)) }
        assertTrue(widths.toString(), widths[0] > widths[1] && widths[1] > widths[2])
        // One pixel too narrow for the whole trail and folding Home is enough: "… › Documents › Atelier relaunch".
        assertEquals(TrailFold.HomeFolded, chooseTrailFold(TrailFold.entries, widths, widths[0] - 1))
        assertEquals(TrailFold.Whole, chooseTrailFold(TrailFold.entries, widths, widths[0]))
    }
}
