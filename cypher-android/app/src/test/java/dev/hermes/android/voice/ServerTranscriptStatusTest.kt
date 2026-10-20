package dev.hermes.android.voice

import org.junit.Assert.*
import org.junit.Test

class ServerTranscriptStatusTest {
    private val active = CallState(active = true, callId = "current")
    @Test fun labelsDescribeTextSynchronizationAndNeverAudioRecording() {
        assertEquals("",active.transcriptLabel())
        assertEquals("Some of this call wasn't saved to the chat",active.copy(transcriptStatus="partial").transcriptLabel())
        assertEquals("",active.copy(transcriptStatus="syncing").transcriptLabel())
        assertEquals("Saved to the chat",active.copy(transcriptStatus="saved").transcriptLabel())
    }
    @Test fun warningsStayVisibleAndNonfatalOnlyForTheAuthorizedActiveCall() {
        val current = active.copy(state = "listening", transcriptStatus = "syncing", muted = true)
        val message = "Cypher cannot work ahead right now. You can continue the call."
        val warned = current.withServerWarning(message, "", "current")
        assertEquals(current.copy(error = message), warned)
        assertTrue(warned.active)
        assertSame(current, current.withServerWarning(message, "older", "current"))
        assertSame(current, current.withServerWarning(message, "", "older"))
        for (invalid in listOf("", " ", "x".repeat(241)))
            assertSame(current, current.withServerWarning(invalid, "current", "current"))
        val ended = current.copy(active = false)
        assertSame(ended, ended.withServerWarning(message, "current", "current"))
    }
}
