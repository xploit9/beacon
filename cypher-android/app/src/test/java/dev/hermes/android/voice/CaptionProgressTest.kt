package dev.hermes.android.voice
import org.junit.Assert.*
import org.junit.Test
class CaptionProgressTest {
    @Test fun successiveDeltasProduceFullCaptionInsteadOfOnlyLastFragment() {
        val first=accumulatedCaption("","Hello ")
        val second=accumulatedCaption(first,"from ")
        assertEquals("Hello from Cypher",accumulatedCaption(second,"Cypher"))
    }
    @Test fun boundNeverSplitsUnicodeSurrogatePair() {
        assertEquals("abc",accumulatedCaption("abc","\uD83D\uDE00",4))
    }
}
