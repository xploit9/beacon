package dev.hermes.android.voice
import org.junit.Assert.*
import org.junit.Test
class CaptionIntegrationTest {
    @Test fun stablePartialIdReplacesWithCompleteAccumulatedCaptionAndKeepsInterruptedPrefix() {
        val id="call:assistant:0"
        var caption=accumulatedCaption("","Hello ")
        var events=emptyList<CallTranscript>().acceptTranscript(CallTranscript(id,"assistant",caption,false))
        caption=accumulatedCaption(caption,"from Beacon")
        events=events.acceptTranscript(CallTranscript(id,"assistant",caption,false))
        assertEquals(1,events.size);assertEquals("Hello from Beacon",events.single().text)
        events=events.acceptTranscript(CallTranscript(id,"assistant",caption,false,interrupted=true))
        assertEquals(caption,events.single().text);assertTrue(events.single().interrupted)
    }
}
