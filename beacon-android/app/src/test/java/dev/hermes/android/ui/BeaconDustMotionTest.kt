package dev.hermes.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BeaconDustMotionTest {
    @Test fun aSlowFrameMovesTheDustAsFarAsTheTimeThatPassed() {
        assertEquals(.016f, frameSeconds(16_000_000L), 1e-6f)
        // A frame a busy phone or the emulator took 150 ms over moves 150 ms on, not a clipped 50: slow frames never turn into slow motion.
        assertEquals(.15f, frameSeconds(150_000_000L), 1e-6f)
        assertEquals(.5f, frameSeconds(500_000_000L), 1e-6f)
    }

    @Test fun aPauseOrAClockThatWentBackMovesNothing() {
        assertEquals(0f, frameSeconds(500_000_001L), 0f)
        assertEquals(0f, frameSeconds(60_000_000_000L), 0f)
        assertEquals(0f, frameSeconds(0L), 0f)
        assertEquals(0f, frameSeconds(-16_000_000L), 0f)
    }
}
