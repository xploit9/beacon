package dev.hermes.android.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** REVIEW-E4 P3-5: the notes filter strips the marked part (words before it stay) and finds a mark split across two pieces. */
class NotesFilterTest {
    @Test fun wordsBeforeAMarkStayAndTheNotesGo() {
        val notes = NotesFilter()
        assertEquals("Hermes is on it.", notes.text("Hermes is on it.  *   **Analysis:** The user"))
        assertEquals("", notes.text(" is asking to search past conversations."))
    }

    @Test fun speechThatRunsOnAfterTheNotesComesBack() {
        val notes = NotesFilter()
        assertEquals("", notes.text("*   **Response:** Inform the user that the results will appear in the chat."))
        assertEquals("Actually, it is ready.", notes.text("Actually, it is ready."))
    }

    @Test fun aMarkSplitAcrossTwoPiecesIsFound() {
        val notes = NotesFilter()
        assertEquals("Done. ", notes.text("Done. *"))
        assertEquals("", notes.text("* Analysis:** the owner wants a summary"))
    }

    @Test fun aRuleAndABlankLineAreMarksAndAHyphenIsNot() {
        val notes = NotesFilter()
        assertEquals("well", notes.text("well-"))
        assertEquals("-known.", notes.text("known."))
        assertEquals("It's taking a moment.", notes.text("It's taking a moment.---\n\nIn the meantime,"))
        notes.turnEnded()
        assertEquals("Next turn.", notes.text("Next turn."))
    }

    // REVIEW-E4-2 proofN, inverted: notes that end with a sentence and a space, then speech: the speech is shown.
    @Test fun speechAfterNotesThatEndWithASpaceIsShown() {
        val notes = NotesFilter()
        assertEquals("Searching now.", notes.text("Searching now. **Plan:** look in the notes. "))
        assertEquals("I found three notes about groceries.", notes.text("I found three notes about groceries."))
    }

    // REVIEW-E4-2 P3-C: a bullet after the sentence's end, or a small letter after it (an abbreviation), keeps the notes going; a line break
    // does not (speech is never eaten), and a letter of a script without Latin capitals ends them too.
    @Test fun aBulletOrASmallLetterKeepsTheNotesGoing() {
        val notes = NotesFilter()
        assertEquals("", notes.text("**Analysis:** The owner asked for notes.\n  *   **Response:** Say it takes approx. ten minutes."))
        assertEquals("Hermes is on it.", notes.text("\nHermes is on it."))
        notes.turnEnded()
        val arabic = "\u0648\u062c\u062f\u062a \u062b\u0644\u0627\u062b \u0645\u0644\u0627\u062d\u0638\u0627\u062a."
        assertEquals("", notes.text("**Plan:** search the notes. "))
        assertEquals(arabic, notes.text(arabic))
    }
}
