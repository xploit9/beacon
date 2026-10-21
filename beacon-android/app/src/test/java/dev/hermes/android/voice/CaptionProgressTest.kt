package dev.hermes.android.voice
import org.junit.Assert.*
import org.junit.Test
class CaptionProgressTest {
    @Test fun successiveDeltasProduceFullCaptionInsteadOfOnlyLastFragment() {
        val first=accumulatedCaption("","Hello ")
        val second=accumulatedCaption(first,"from ")
        assertEquals("Hello from Beacon",accumulatedCaption(second,"Beacon"))
    }
    @Test fun boundNeverSplitsUnicodeSurrogatePair() {
        assertEquals("abc",accumulatedCaption("abc","\uD83D\uDE00",4))
    }
}
