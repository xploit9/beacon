package dev.hermes.android.uxtour

import androidx.compose.ui.unit.dp
import dev.hermes.android.ui.ChoiceStyle
import dev.hermes.android.ui.FileCrumb
import dev.hermes.android.ui.LargeTextScale
import dev.hermes.android.ui.ReasoningSkeletonChips
import dev.hermes.android.ui.ReasoningSkeletonHeight
import dev.hermes.android.ui.ReasoningSkeletonWidth
import dev.hermes.android.ui.SessionUsageFigures
import dev.hermes.android.ui.collapseFileTrail
import dev.hermes.android.ui.fileBreadcrumbs
import dev.hermes.android.ui.isLargeText
import dev.hermes.android.ui.resolveChoiceStyle
import dev.hermes.android.ui.sessionUsageUnreported
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX-39 (large text), UX-46 (usage), UX-49 (breadcrumb) and UX-50 (reasoning skeleton): the decisions behind the screens. */
class LargeTextAndTrailTest {
    private val theme = listOf("Follow device", "Dark", "Light")

    @Test fun largeTextStartsAtOnePointThree() {
        assertEquals(1.3f, LargeTextScale, 0f)
        assertFalse(isLargeText(1f))
        assertFalse(isLargeText(1.29f))
        assertTrue(isLargeText(1.3f))
        assertTrue(isLargeText(2f))
    }

    @Test fun autoChoiceOpensAMenuFromOnePointThreeSoNoLabelBreaksMidWord() {
        assertEquals(ChoiceStyle.Segmented, resolveChoiceStyle(ChoiceStyle.Auto, theme, 1f))
        assertEquals(ChoiceStyle.Segmented, resolveChoiceStyle(ChoiceStyle.Auto, theme, 1.29f))
        assertEquals(ChoiceStyle.Menu, resolveChoiceStyle(ChoiceStyle.Auto, theme, 1.3f))
        assertEquals(ChoiceStyle.Menu, resolveChoiceStyle(ChoiceStyle.Auto, theme, 1.5f))
        // A style the caller fixed is never overridden.
        assertEquals(ChoiceStyle.Segmented, resolveChoiceStyle(ChoiceStyle.Segmented, theme, 2f))
        assertEquals(ChoiceStyle.Menu, resolveChoiceStyle(ChoiceStyle.Menu, theme, 1f))
    }

    @Test fun zeroTokensUnderRepliesMeanHermesDidNotCountThem() {
        fun figures(input: Long?, output: Long?, window: Long? = null) = SessionUsageFigures(input, output, window, null, emptySet())
        assertTrue(sessionUsageUnreported(figures(0, 0), hasReplies = true))
        assertTrue(sessionUsageUnreported(figures(null, null, window = 32_000), hasReplies = true))
        assertTrue(sessionUsageUnreported(figures(0, null), hasReplies = true))
        // A chat with no replies yet has nothing to count, and any real figure is shown as it is.
        assertFalse(sessionUsageUnreported(figures(0, 0), hasReplies = false))
        assertFalse(sessionUsageUnreported(figures(1_234, 0), hasReplies = true))
        assertFalse(sessionUsageUnreported(figures(0, 560), hasReplies = true))
        assertFalse(sessionUsageUnreported(figures(1_234, 560), hasReplies = true))
    }

    @Test fun aLongFolderTrailKeepsTheLastTwoLevelsAndFoldsTheRest() {
        val trail = fileBreadcrumbs("/home/me/work/project/src")
        assertEquals(listOf("/", "home", "me", "work", "project", "src"), trail.map(FileCrumb::label))
        val folded = collapseFileTrail(trail)
        assertEquals(listOf("/", "home", "me", "work"), folded.hidden.map(FileCrumb::label))
        assertEquals(listOf("project", "src"), folded.shown.map(FileCrumb::label))
        // The folded levels still open the folders they name.
        assertEquals("/home/me/work", folded.hidden.last().path)
        assertEquals("/home/me/work/project/src", folded.shown.last().path)
        // Nothing is lost, and the chip tags (index in the whole trail) line up with the full trail.
        assertEquals(trail, folded.hidden + folded.shown)
        assertEquals(trail.lastIndex, folded.hidden.size + folded.shown.lastIndex)
    }

    @Test fun windowsTrailsFoldTheSameWayAndShortTrailsStayWhole() {
        val windows = collapseFileTrail(fileBreadcrumbs("D:\\work\\notes\\a\\b"))
        assertEquals(listOf("D:", "work", "notes"), windows.hidden.map(FileCrumb::label))
        assertEquals(listOf("a", "b"), windows.shown.map(FileCrumb::label))
        // Folding one level would only swap its name for "…", so a trail of three chips (or fewer) shows whole.
        listOf("/", "/home", "/home/me").forEach { path ->
            val trail = fileBreadcrumbs(path)
            val folded = collapseFileTrail(trail)
            assertEquals(path, trail, folded.shown)
            assertTrue(path, folded.hidden.isEmpty())
        }
        assertEquals(emptyList<FileCrumb>(), collapseFileTrail(emptyList()).shown)
        // Four chips is the first trail that folds, and it folds two.
        val four = collapseFileTrail(fileBreadcrumbs("/home/me/work"))
        assertEquals(listOf("/", "home"), four.hidden.map(FileCrumb::label))
        assertEquals(listOf("me", "work"), four.shown.map(FileCrumb::label))
    }

    @Test fun theLoadingReasoningControlDrawsTheShapeOfTheLoadedChips() {
        assertEquals(4, ReasoningSkeletonChips)
        assertEquals(72.dp, ReasoningSkeletonWidth)
        assertEquals(32.dp, ReasoningSkeletonHeight)
    }
}
