package dev.hermes.android.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class RichInteractionTest {
    @Test fun structuredDefaultWaitsForParsingThenHonorsPreference() {
        assertFalse(structuredTreeDefault(false, "on", 20, 12))
        assertTrue(structuredTreeDefault(true, "on", 1, 12))
        assertTrue(structuredTreeDefault(true, "auto", 12, 12))
        assertFalse(structuredTreeDefault(true, "auto", 11, 12))
        assertFalse(structuredTreeDefault(true, "off", 20, 12))
    }

    @Test fun returningToUnitZoomClearsPanAndUnitZoomCannotPan() {
        val old = Offset(20f, -30f)
        val pan = Offset(4f, 5f)
        assertEquals(Offset.Zero, imagePanOffset(1f, old, pan))
        assertEquals(Offset.Zero, imagePanOffset(1f, Offset.Zero, pan))
        assertEquals(Offset(24f, -25f), imagePanOffset(2f, old, pan))
    }
}
