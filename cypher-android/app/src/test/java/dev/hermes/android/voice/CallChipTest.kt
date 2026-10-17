package dev.hermes.android.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class CallChipTest {
    @Test fun systemChipReflectsActualCallState() {
        val call=CallState(active=true,state="listening")
        assertEquals("Listen",call.chipLabel())
        assertEquals("Speak",call.copy(remoteSpeaking=true).chipLabel())
        assertEquals("Muted",call.copy(muted=true).chipLabel())
        assertEquals("Hold",call.copy(muted=true,held=true).chipLabel())
        assertEquals("Start",call.copy(state="connecting").chipLabel())
        assertEquals("Ended",call.copy(active=false).chipLabel())
    }
}
