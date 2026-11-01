package dev.hermes.android.uiux.gaps

import dev.hermes.android.core.json
import dev.hermes.android.ui.formatTokenCount
import dev.hermes.android.ui.sessionUsageFigures
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** Session usage: the figures, the context percent and the token text, beyond what CatalogPresentationTest already pins. */
class SessionUsageGapsTest {
    @Test fun eachFigureReadsItsFirstKnownNameInTheDocumentedOrder() {
        val figures = sessionUsageFigures(json(
            "input" to 1, "input_tokens" to 10, "prompt_tokens" to 100,
            "output" to 2, "completion_tokens" to 20, "output_tokens" to 200,
            "context_length" to 4, "context_window" to 40, "max_context" to 400,
            "total" to 5, "total_tokens" to 50, "context_used" to 500, "context_usage" to 5000,
        ))
        assertEquals("input_tokens leads input, prompt_tokens and prompt", 10L, figures.input)
        assertEquals("output_tokens leads output and completion_tokens", 200L, figures.output)
        assertEquals("context_window leads context_max, max_context and context_length", 40L, figures.window)
        assertEquals("context_usage leads context_used, total_tokens and total", 5000L, figures.used)
        assertEquals("Only the key a figure used is consumed; the others stay for Details",
            setOf("input_tokens", "output_tokens", "context_window", "context_usage"), figures.consumed)
    }

    @Test fun aLaterNameIsReadWhenTheEarlierOnesAreNotNumbers() {
        val text = sessionUsageFigures(json("input_tokens" to "many", "input" to JSONObject.NULL, "prompt_tokens" to "7", "prompt" to 9))
        assertEquals("A word and a null are skipped, the next name is read", 7L, text.input)
        assertEquals("The key that was read is consumed, the skipped ones are not", setOf("prompt_tokens"), text.consumed)
        assertEquals("A padded string is not a number, so the next name is read", 9L, sessionUsageFigures(json("prompt_tokens" to " 7 ", "prompt" to 9)).input)
        val array = sessionUsageFigures(json("output_tokens" to JSONArray()))
        assertNull("An array is not a figure", array.output)
        assertTrue(array.consumed.isEmpty())
    }

    @Test fun numbersAsTextAndDecimalsAreRead() {
        val figures = sessionUsageFigures(json("input_tokens" to "1200", "output_tokens" to 99.9, "context_window" to "128000", "total_tokens" to 3.0))
        assertEquals(1200L, figures.input)
        assertEquals("A decimal count keeps its whole part", 99L, figures.output)
        assertEquals(128000L, figures.window)
        assertEquals(3L, figures.used)
        assertEquals(0, figures.percent)
    }

    @Test fun thePercentIsFlooredAndNeverLeavesZeroToOneHundred() {
        assertEquals(33, sessionUsageFigures(json("context_window" to 3, "total_tokens" to 1)).percent)
        assertEquals("999 of 1000 is not rounded up to full", 99, sessionUsageFigures(json("context_window" to 1000, "total_tokens" to 999)).percent)
        assertEquals(0, sessionUsageFigures(json("context_window" to 100, "total_tokens" to 0)).percent)
        assertEquals("A negative count reads as empty", 0, sessionUsageFigures(json("context_window" to 100, "total_tokens" to -5)).percent)
        assertEquals(100, sessionUsageFigures(json("context_window" to 100, "total_tokens" to 100)).percent)
        assertEquals("Beyond the window reads as full", 100, sessionUsageFigures(json("context_window" to 100, "total_tokens" to 10_000)).percent)
    }

    @Test fun thePercentNeedsBothFigures() {
        assertNull(sessionUsageFigures(json("total_tokens" to 500)).percent)
        assertNull(sessionUsageFigures(json("context_window" to 500)).percent)
        assertNull("A window of zero cannot be divided by", sessionUsageFigures(json("context_window" to 0, "total_tokens" to 500)).percent)
        assertNull("A negative window cannot be divided by", sessionUsageFigures(json("context_window" to -10, "total_tokens" to 5)).percent)
    }

    @Test fun nothingIsConsumedFromAnUnknownPayload() {
        val figures = sessionUsageFigures(json("model" to "m", "note" to "x", "nested" to json("input_tokens" to 4)))
        assertTrue(figures.input == null && figures.output == null && figures.window == null && figures.used == null)
        assertTrue("A nested object is not searched", figures.consumed.isEmpty())
        assertNull(figures.percent)
    }

    @Test fun tokenCountsRoundAtTheirBoundaries() {
        val us = Locale.US
        assertEquals("999", formatTokenCount(999, us))
        assertEquals("1K", formatTokenCount(1000, us))
        assertEquals("1.5K", formatTokenCount(1500, us))
        assertEquals("10K", formatTokenCount(10_000, us))
        assertEquals("From ten thousand the decimal is dropped", "12K", formatTokenCount(12_499, us))
        assertEquals("999K", formatTokenCount(999_999, us))
        assertEquals("1M", formatTokenCount(1_000_000, us))
        assertEquals("2.5M", formatTokenCount(2_500_000, us))
    }

    @Test fun tokenCountsFollowThePhonesNumberFormat() {
        assertEquals("1,2K", formatTokenCount(1234, Locale.GERMANY))
        assertEquals("1,2M", formatTokenCount(1_200_000, Locale.FRANCE))
        assertEquals("1.2K", formatTokenCount(1234, Locale.UK))
    }

    @Test fun everyLanguageKeepsThePercentPlaceholderOfTheContextBar() {
        assertEquals(16, GapsAssets.languages.size)
        for (language in GapsAssets.languages) {
            val text = GapsAssets.translated(language, "{0}% of context")
            assertTrue("$language has no translation of the context bar text", text.isNotBlank())
            assertTrue("$language drops the {0} the percent is written into: \"$text\"", text.contains("{0}"))
        }
    }

    @Test fun theUsagePageStringsAreTranslatedInEveryLanguage() {
        val shown = listOf("Session usage", "Refresh usage", "Open a chat first", "Input", "Output", "Context", "Details", "Retry")
        val missing = shown.flatMap { text -> GapsAssets.languages.filter { it != "en" && GapsAssets.translated(it, text).isBlank() }.map { "$it: $text" } }
        assertTrue("Session usage text without a translation:\n" + missing.joinToString("\n"), missing.isEmpty())
    }
}
