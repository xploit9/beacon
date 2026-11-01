package dev.hermes.android.ux2text

import dev.hermes.android.ui.LargeTextScale
import dev.hermes.android.ui.isLargeText
import dev.hermes.android.ui.logFiltersWrap
import dev.hermes.android.ui.serverLogFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX-49: at a text scale of 1.3 or more the log filters wrap onto lines, so no chip sits half out of view. */
class LogFilterWrapTest {
    @Test fun theFiltersWrapFromOnePointThree() {
        for (scale in listOf(0.85f, 1f, 1.15f, 1.29f, 1.2999f)) assertFalse("$scale", logFiltersWrap(scale))
        for (scale in listOf(1.3f, 1.31f, 1.5f, 2f)) assertTrue("$scale", logFiltersWrap(scale))
    }

    @Test fun theWrapThresholdIsTheAppsLargeTextThreshold() {
        assertEquals(1.3f, LargeTextScale, 0f)
        for (scale in listOf(1f, 1.1f, 1.2f, 1.29f, 1.3f, 1.4f, 2f)) assertEquals("$scale", isLargeText(scale), logFiltersWrap(scale))
    }

    @Test fun everyLogFileHasAChipToWrap() {
        assertEquals(listOf("errors", "agent", "gateway", "gui", "desktop", "mcp"), serverLogFiles)
    }
}
