package dev.hermes.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The check that a search hit lies in words the owner can see reads the query the way Hermes' message search does. */
class VisibleSearchMatchTest {
    private val text = "What color is the Square in this image"

    @Test fun aTermIsReadWithoutCaseAndAnyOfTheTermsKeepsTheHit() {
        assertTrue(visibleSearchMatch(text, "square"))
        assertTrue(visibleSearchMatch(text, "COLOR square"))
        // Hermes relaxes a query nothing matched to ANY of its terms (hermes_state_search._or_relaxed_query), so one visible term is enough.
        assertTrue(visibleSearchMatch(text, "color rectangle"))
        // A hit whose words are only in Hermes' own note has none of them in what the owner sees.
        assertFalse(visibleSearchMatch(text, "rectangle circle"))
    }

    @Test fun aPrefixWildcardAndAQuotedPhraseAreRead() {
        assertTrue(visibleSearchMatch(text, "squ*"))
        assertTrue(visibleSearchMatch(text, "\"color is the\""))
        assertFalse(visibleSearchMatch(text, "\"color the is\""))
    }

    @Test fun orAcceptsAnyTermAndNotIsNotAskedFor() {
        assertTrue(visibleSearchMatch(text, "rectangle OR square"))
        assertFalse(visibleSearchMatch(text, "rectangle OR circle"))
        assertTrue(visibleSearchMatch(text, "square NOT circle"))
    }

    @Test fun aQueryWithNoTermsAsksForNothing() {
        assertTrue(visibleSearchMatch(text, "  "))
        assertTrue(visibleSearchMatch(text, "*"))
    }

    /** R9 Should 2: Hermes turns `+{}():"^@/#&|~[]<>,;!?$=\'` into spaces before it searches (hermes_state_search._FTS5_SPECIAL_CHARS), so `invoice?` finds "Where is the invoice". */
    @Test fun punctuationHermesStripsIsNotPartOfTheTerm() {
        val own = "Where is the invoice"
        assertTrue(visibleSearchMatch(own, "invoice?"))
        assertTrue(visibleSearchMatch("Hi there", "Hi,"))
        assertTrue(visibleSearchMatch("what time is it", "what time!"))
        assertTrue(visibleSearchMatch("the plan for tomorrow", "plan/tomorrow"))
        assertTrue(visibleSearchMatch("it's fine", "it's"))
        // Only the stripped characters go; a hit that holds none of the cleaned terms still has nothing visible.
        assertFalse(visibleSearchMatch(own, "receipt?"))
    }

    @Test fun aRelaxedMultiWordQueryKeepsAnyVisibleTerm() {
        val own = "Where is the invoice"
        assertTrue(visibleSearchMatch(own, "invoice receipt"))
        assertTrue(visibleSearchMatch(own, "receipt, invoice?"))
        assertFalse(visibleSearchMatch(own, "receipt refund"))
    }

    @Test fun aQuotedPhraseKeepsItsPunctuationAsHermesHoldsQuotedPhrasesApart() {
        assertTrue(visibleSearchMatch("Is this the invoice? Yes", "\"the invoice? Yes\""))
        assertFalse(visibleSearchMatch("Is this the invoice yes", "\"the invoice? Yes\""))
    }
}
