package dev.hermes.android.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeThemeTest {
    @Test fun cachedDotPatternKeepsItsOriginalGridAndRadius() {
        val dots = mutableListOf<Rect>()
        forEachNativeDot(width = 12f, height = 12f, step = 12f) { dots += it }
        assertEquals(4, dots.size)
        assertEquals(-0.1f, dots.first().left, 0.01f)
        assertEquals(-0.1f, dots.first().top, 0.01f)
        assertEquals(14.1f, dots.last().right, 0.01f)
        assertEquals(14.1f, dots.last().bottom, 0.01f)
    }
}
