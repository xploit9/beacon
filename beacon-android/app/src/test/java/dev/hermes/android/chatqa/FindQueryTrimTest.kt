package dev.hermes.android.chatqa

import dev.hermes.android.ui.CallTranscriptGroup
import dev.hermes.android.ui.callTranscriptGroupsMatchingSearch
import dev.hermes.android.ui.findRanges
import dev.hermes.android.voice.CallTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHATQA-121 D11 regression (was FindTrailingSpaceProofTest): Find in chat matches the words, not a trailing space a keyboard adds after a
 * picked word. ChatScreen's findQuery is trimmed (it feeds the message matches) and call cards trim too, as the highlight always did.
 */
class FindQueryTrimTest {
    @Test fun aTrailingSpaceStillFindsACallLineThatEndsWithTheWord() {
        val group = CallTranscriptGroup("call-1", listOf(CallTranscript("call-1:user:1", "user", "book a table for dinner")))
        assertEquals(1, callTranscriptGroupsMatchingSearch(listOf(group), "dinner").size)
        assertEquals(1, callTranscriptGroupsMatchingSearch(listOf(group), "dinner ").size)
        assertEquals(1, callTranscriptGroupsMatchingSearch(listOf(group), "  dinner  ").size)
    }

    @Test fun theHighlightAgreesWithTheMatch() {
        assertTrue(findRanges("Let's meet for dinner", "dinner ").isNotEmpty())
        assertEquals(0, callTranscriptGroupsMatchingSearch(listOf(CallTranscriptGroup("call-1", listOf(CallTranscript("call-1:user:1", "user", "lunch")))), "dinner ").size)
    }
}
