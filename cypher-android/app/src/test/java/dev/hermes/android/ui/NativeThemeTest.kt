package dev.hermes.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeThemeTest {
    @Test fun beaconEmbersAreSparseStableAndDimAwayFromTheLight() {
        val embers = beaconEmbers(width = 1080f, height = 2400f, spacing = 90f, maxRadius = 4f)
        val cells = (1080 / 90 + 1) * (2400 / 90 + 1)
        assertTrue("sparse", embers.size in cells / 6..cells / 2)
        assertEquals("the same size draws the same field", embers, beaconEmbers(1080f, 2400f, 90f, 4f))
        assertTrue("inside the chat", embers.all { it.x in 0f..1080f && it.y in 0f..2400f && it.radius in 0f..4f && it.alpha in 0f..0.2f })
        val top = embers.filter { it.y < 600f }.map { it.alpha }.average()
        val bottom = embers.filter { it.y > 1800f }.map { it.alpha }.average()
        assertTrue("brighter near the light", top > bottom)
        assertTrue(beaconEmbers(0f, 0f, 90f, 4f).size <= 1)
    }
}
