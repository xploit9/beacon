package dev.hermes.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The call transcript card's title is built in code: its fixed words are translated, the provider's name is not. */
class NativeExperienceTest {
    @Test fun theTitleNamesTheCallItsMinutesAndTheProvider() {
        assertEquals("Call · 3 min · Gemini", callTranscriptCardTitle(180, "gemini"))
        assertEquals("Call · 2 min · OpenAI", callTranscriptCardTitle(100, "openai"))
        assertEquals("Call · 1 min · Grok", callTranscriptCardTitle(5, "grok"))
        assertEquals("Call · 2 min · Custom", callTranscriptCardTitle(90, "custom"))
    }

    @Test fun aMissingDurationOrProviderLeavesItsPartOut() {
        assertEquals("Call", callTranscriptCardTitle(null, null))
        assertEquals("Call", callTranscriptCardTitle(0, ""))
        assertEquals("Call · 4 min", callTranscriptCardTitle(240, null))
        assertEquals("Call · Gemini", callTranscriptCardTitle(null, "gemini"))
    }

    @Test fun theFixedWordsGoThroughTheTranslatorAndTheProviderStaysAsItIs() {
        val german = mapOf("Call" to "Anruf", "{0} min" to "{0} Min.")
        assertEquals("Anruf · 3 Min. · Gemini", callTranscriptCardTitle(180, "gemini") { german.getValue(it) })
        assertEquals("Anruf · Grok", callTranscriptCardTitle(null, "grok") { german.getValue(it) })
    }

    @Test fun theTranslatorIsAskedForExactlyTheTitleWords() {
        val asked = mutableSetOf<String>()
        callTranscriptCardTitle(600, "gemini") { asked += it; it }
        assertEquals(CallTitleStrings.toSet(), asked)
    }
}
