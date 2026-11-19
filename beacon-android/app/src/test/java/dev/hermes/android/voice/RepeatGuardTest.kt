package dev.hermes.android.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner, 4 Nov 2026, and device round 2: a sentence said again in a later turn. REVIEW-E4 P2-2: only sentences of earlier turns are
 * compared, a sentence is dropped only when it covers most of an earlier one, and one that diverges gets back every piece it held.
 * Captions only (REVIEW-E5 P2-4): the guard returns what to show.
 */
class RepeatGuardTest {
    private val line = "I'll draft that email to Carlos asking about arriving two weeks early."

    @Test fun aSentenceOfAnEarlierTurnSaidAgainIsHeldThenDropped() {
        val guard = RepeatGuard()
        assertEquals(line, guard.words(line)); guard.turnEnded()
        // The repeat arrives in pieces, as Gemini sends it; none of it is shown, and the guard reports it is holding.
        assertEquals("", guard.words("I'll draft that email to "))
        assertTrue(guard.repeating)
        assertEquals("", guard.words("Carlos asking about arriving two weeks early.")); assertFalse(guard.repeating)
        assertEquals("The draft is ready.", guard.words("The draft is ready."))
    }

    // REVIEW-E4 proofR, inverted: a new sentence that starts like an earlier one is held while it matches, then given back whole.
    @Test fun aNewSentenceThatStartsLikeAnEarlierOneIsGivenBackWhole() {
        val guard = RepeatGuard()
        assertEquals("You have a meeting at 9.", guard.words("You have a meeting at 9.")); guard.turnEnded()
        val steps = listOf("You have a meeting", " at", " 2.").map { chunk -> guard.words(chunk) to guard.repeating }
        assertEquals(listOf("", "", "You have a meeting at 2."), steps.map { it.first })
        assertEquals(listOf(true, true, false), steps.map { it.second })
    }

    @Test fun sentencesOfTheSameTurnAreNeverCompared() {
        val guard = RepeatGuard()
        val turn = "You have a meeting at 9. You have a meeting at 9."
        assertEquals(turn, guard.words(turn))
        assertFalse(guard.repeating)
    }

    @Test fun aRepeatCutShortThatCoversMostOfTheEarlierSentenceIsDropped() {
        val guard = RepeatGuard()
        guard.words(line); guard.turnEnded()
        assertEquals("", guard.words("I'll draft that email to Carlos asking about arriving two weeks"))
        assertEquals("dropped at the turn's end", "", guard.turnEnded())
    }

    @Test fun theStartOfAnEarlierSentenceAloneIsGivenBackAtTheTurnsEnd() {
        val guard = RepeatGuard()
        guard.words(line); guard.turnEnded()
        assertEquals("", guard.words("I'll draft that"))
        assertEquals("I'll draft that", guard.turnEnded())
    }

    @Test fun theOwnerSpeakingLetsTheSentenceBeSaidAgain() {
        val guard = RepeatGuard()
        guard.words(line); guard.turnEnded()
        guard.ownerSpoke()
        assertEquals(line, guard.words(line))
    }

    @Test fun shortRepliesAreNeverHeldBack() {
        val guard = RepeatGuard()
        assertEquals("Okay.", guard.words("Okay.")); guard.turnEnded()
        assertEquals("Okay.", guard.words("Okay."))
    }

    // REVIEW-E4 P2-2: the Devanagari danda ends a sentence, so a turn in Hindi or Bengali is not one long "sentence".
    @Test fun theDandaEndsASentence() {
        val guard = RepeatGuard()
        val said = "\u0906\u091c \u0915\u0940 \u092c\u0948\u0920\u0915 \u0928\u094c \u092c\u091c\u0947 \u0936\u0941\u0930\u0942 \u0939\u094b\u0917\u0940\u0964"
        val fresh = "\u092e\u094c\u0938\u092e \u0905\u091a\u094d\u091b\u093e \u0939\u0948\u0964"
        guard.words(said); guard.turnEnded()
        assertEquals("only the new sentence is shown; the one said before is held back", fresh, guard.words("$fresh $said"))
    }
}
