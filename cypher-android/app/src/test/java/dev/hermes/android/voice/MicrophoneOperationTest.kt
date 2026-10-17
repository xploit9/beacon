package dev.hermes.android.voice

import org.junit.Assert.*
import org.junit.Test

class MicrophoneOperationTest {
    @Test fun newerMuteOrHoldIntentRejectsQueuedAudioFromPreviousIntent() {
        val gate = MicrophoneIntentGate()
        val initial = gate.snapshot()
        assertTrue(initial.enabled)
        val muted = gate.request(muted = true)
        assertFalse(gate.current(initial))
        assertFalse(muted.enabled)
        val held = gate.request(muted = false, held = true)
        assertFalse(gate.current(muted))
        assertFalse(held.enabled)
        val resumed = gate.request(held = false)
        assertFalse(gate.current(held))
        assertTrue(resumed.enabled)
        assertSame(resumed, gate.snapshot())
        assertTrue(gate.current(resumed))
    }
}
