package dev.hermes.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Owner, 4 Nov: when a reply ends the gold specks "shoot up aggressively". After speaking, the swarm sits above the crown; the
 * listening magnet pulled every speck home at once and drew each one's frame jump three times longer as a streak, so ending a reply
 * flashed a burst of long lines. The specks now glide home at the resting pace and the streaks build up only as they arrive.
 */
class BeaconDustStreakTest {
    private fun length(out: FloatArray) = hypot(out[2] - out[0], out[3] - out[1])

    @Test fun aSpeckThatJumpsFarNeverDrawsALongLine() {
        val out = FloatArray(4)
        listeningStreak(500f, 200f, 500f, 320f, cap = 14f, settle = 1f, out = out, at = 0)
        assertTrue("a 120 px jump drew ${length(out)} px", length(out) <= 14.01f)
        assertEquals("the streak ends on the speck", 320f, out[3], .001f)
    }

    @Test fun aSmallStepKeepsItsStreak() {
        val out = FloatArray(4)
        listeningStreak(500f, 200f, 502f, 201f, cap = 14f, settle = 1f, out = out, at = 0)
        assertEquals(hypot(2f, 1f) * 3f, length(out), .01f)
    }

    @Test fun whileTheSwarmGlidesHomeThereAreNoStreaksAndThePullIsGentle() {
        val out = FloatArray(4)
        listeningStreak(500f, 200f, 503f, 204f, cap = 14f, settle = 0f, out = out, at = 0)
        assertEquals(0f, length(out), .001f)
        assertEquals("the resting pace right after a reply", 3.2f, listeningPull(0f), .001f)
        assertTrue(listeningPull(LISTEN_SETTLE_MILLIS / 2) in 3.2f..9f)
        assertEquals("the magnet's full pull once home", 9f, listeningPull(LISTEN_SETTLE_MILLIS), .001f)
        assertEquals("reduced motion is already home", 9f, listeningPull(Float.MAX_VALUE), .001f)
    }
}
