package dev.hermes.android.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** Review R25 Nice 2: a call start that finds the microphone turned off says so in the owner's line instead of "Call ended unexpectedly". */
class CallStartFailureLineTest {
    private val translate = { english: String -> "[$english]" }

    @Test fun aRefusalKeepsTheLineItWasGivenInTheOwnersLanguage() {
        // CallStartRefused carries callText(...): already translated and named, so it is shown as it is.
        assertEquals("Autorizza il microfono per chiamare Maya.", callStartFailureLine(CallStartRefused("Autorizza il microfono per chiamare Maya."), translate))
    }

    @Test fun aKnownCallLineIsTranslatedAndAnythingElseStaysGeneric() {
        assertEquals("[$CALL_START_FAILED]", callStartFailureLine(IllegalStateException(CALL_START_FAILED), translate))
        assertEquals("[Call ended unexpectedly]", callStartFailureLine(IllegalStateException("Socket closed by peer"), translate))
        assertEquals("[Call ended unexpectedly]", callStartFailureLine(RuntimeException(), translate))
    }
}
