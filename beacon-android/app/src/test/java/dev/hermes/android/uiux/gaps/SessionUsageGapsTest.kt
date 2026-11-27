package dev.hermes.android.uiux.gaps

import dev.hermes.android.core.json
import androidx.compose.ui.graphics.Color
import dev.hermes.android.ui.ContextUsageCategory
import dev.hermes.android.ui.contextCategoryBaseColor
import dev.hermes.android.ui.contextCategoryShare
import dev.hermes.android.ui.contextUsageBreakdown
import dev.hermes.android.ui.formatTokenCount
import dev.hermes.android.ui.nativeContrast
import dev.hermes.android.ui.readableInk
import dev.hermes.android.ui.sessionUsageFigures
import dev.hermes.android.ui.usageContextStatus
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
            "context_window" to 400_000, "context_length" to 40_000, "context_max" to 4_000,
            "total" to 5, "total_tokens" to 50, "last_prompt_tokens" to 5, "context_tokens" to 50, "context_used" to 500,
            "context_percent" to 38,
        ))
        assertEquals("input_tokens leads the dashboard's prompt_tokens alias", 10L, figures.input)
        assertEquals("output_tokens leads the dashboard's completion_tokens alias", 200L, figures.output)
        assertEquals("context_max leads context_length and context_window", 4_000L, figures.window)
        assertEquals("context_used leads context_tokens and last_prompt_tokens", 500L, figures.used)
        assertEquals("Hermes' own percent is preferred", 38, figures.percent)
        assertEquals("Only the key a figure used is consumed; the others stay for Details",
            setOf("input_tokens", "output_tokens", "context_max", "context_used", "context_percent"), figures.consumed)
    }

    @Test fun aLaterNameIsReadWhenTheEarlierOnesAreNotNumbers() {
        val text = sessionUsageFigures(json("input_tokens" to "many", "input" to JSONObject.NULL, "prompt_tokens" to "7", "prompt" to 9))
        assertEquals("A word and a null are skipped, the next name is read", 7L, text.input)
        assertEquals("The key that was read is consumed, the skipped ones are not", setOf("prompt_tokens"), text.consumed)
        assertEquals("A padded string is not a number, so the next name is read", 9L, sessionUsageFigures(json("input_tokens" to " 7 ", "prompt_tokens" to 9)).input)
        val array = sessionUsageFigures(json("output_tokens" to JSONArray()))
        assertNull("An array is not a figure", array.output)
        assertTrue(array.consumed.isEmpty())
    }

    @Test fun numbersAsTextAndDecimalsAreRead() {
        val figures = sessionUsageFigures(json("input_tokens" to "1200", "output_tokens" to 99.9, "context_length" to "128000", "context_tokens" to 3.0))
        assertEquals(1200L, figures.input)
        assertEquals("A decimal count keeps its whole part", 99L, figures.output)
        assertEquals(128000L, figures.window)
        assertEquals(3L, figures.used)
        assertEquals(0, figures.percent)
    }

    @Test fun thePercentRoundsLikeTheDashboardAndNeverLeavesZeroToOneHundred() {
        assertEquals(33, sessionUsageFigures(json("context_length" to 3, "context_tokens" to 1)).percent)
        assertEquals("999 of 1000 rounds up like the dashboard", 100, sessionUsageFigures(json("context_length" to 1000, "context_tokens" to 999)).percent)
        assertEquals(0, sessionUsageFigures(json("context_length" to 100, "context_tokens" to 0)).percent)
        assertEquals("A negative count reads as empty", 0, sessionUsageFigures(json("context_length" to 100, "context_tokens" to -5)).percent)
        assertEquals(100, sessionUsageFigures(json("context_length" to 100, "context_tokens" to 100)).percent)
        assertEquals("Beyond the window reads as full", 100, sessionUsageFigures(json("context_length" to 100, "context_tokens" to 10_000)).percent)
    }

    @Test fun thePercentNeedsBothFigures() {
        assertNull(sessionUsageFigures(json("total_tokens" to 500)).percent)
        assertNull(sessionUsageFigures(json("context_window" to 500)).percent)
        assertNull("A window of zero cannot be divided by", sessionUsageFigures(json("context_window" to 0, "context_tokens" to 500)).percent)
        assertNull("A negative window cannot be divided by", sessionUsageFigures(json("context_window" to -10, "context_tokens" to 5)).percent)
        assertEquals("A zero context_max falls through to Hermes' context_length", 128_000L,
            sessionUsageFigures(json("context_max" to 0, "context_length" to 128_000)).window)
    }

    /** The owner's screenshot: 8.7M lifetime tokens were read as current occupancy under a 1M window. */
    @Test fun hermesOccupancyWinsOverTheLifetimeTotal() {
        val figures = sessionUsageFigures(json("total_tokens" to 8_700_000, "context_tokens" to 384_000, "context_length" to 1_000_000,
            "input_tokens" to 24_000, "output_tokens" to 30_000))
        assertEquals(384_000L, figures.used)
        assertEquals(1_000_000L, figures.window)
        assertEquals(38, figures.percent)
        assertFalse("A lifetime total is never occupancy", "total_tokens" in figures.consumed)
        assertNull("Without an occupancy field there is no bar", sessionUsageFigures(json("total_tokens" to 8_700_000, "context_length" to 1_000_000)).used)
        assertEquals("context_used also leads the lifetime total", 384_000L,
            sessionUsageFigures(json("context_used" to 384_000, "total_tokens" to 8_700_000, "context_max" to 1_000_000)).used)
    }

    @Test fun hermesOwnPercentAndEstimateAreKept() {
        val reported = sessionUsageFigures(json("context_used" to 300_000, "context_max" to 1_000_000, "context_percent" to 38, "context_estimated" to true))
        assertEquals("Hermes' percent wins over the rounded used/window", 38, reported.percent)
        assertTrue(reported.estimated)
        assertEquals("An out-of-range reported percent is clamped", 100,
            sessionUsageFigures(json("context_used" to 1, "context_max" to 2, "context_percent" to 150)).percent)
        assertEquals("A negative reported percent is clamped", 0,
            sessionUsageFigures(json("context_used" to 1, "context_max" to 2, "context_percent" to -1)).percent)
        assertTrue("A local estimate is marked without the explicit flag, as the dashboard derives it",
            sessionUsageFigures(json("context_used" to 1, "context_max" to 2, "context_source" to "local_estimate")).estimated)
        assertTrue(sessionUsageFigures(json("context_used" to 1, "context_max" to 2, "context_source" to "provider_usage_plus_estimate")).estimated)
        assertFalse(sessionUsageFigures(json("context_used" to 1, "context_max" to 2, "context_source" to "provider_usage")).estimated)
    }

    @Test fun theCountersUseTheDashboardsFirstNonZeroAlias() {
        assertEquals("An uncached input of zero falls to the prompt total", 7L, sessionUsageFigures(json("input_tokens" to 0, "prompt_tokens" to 7)).input)
        assertEquals("A non-zero input stays the input", 5L, sessionUsageFigures(json("input_tokens" to 5, "prompt_tokens" to 7)).input)
        assertEquals("The same rule for output", 9L, sessionUsageFigures(json("output_tokens" to 0, "completion_tokens" to 9)).output)
    }

    @Test fun aFullWindowIsNeverDerivedFromTheTwoTokenTotals() {
        // The owner's screenshot: 628K in and 67K out under a window Hermes reports as fully used.
        val withoutUsed = sessionUsageFigures(json("input_tokens" to 628_000, "output_tokens" to 67_000, "context_window" to 1_000_000))
        assertNull("No used figure means no context share, even with both token totals", withoutUsed.percent)
        assertEquals("Only Hermes' used figure fills the window",
            100, sessionUsageFigures(json("input_tokens" to 628_000, "output_tokens" to 67_000, "context_window" to 1_000_000, "context_used" to 1_000_000)).percent)
    }

    @Test fun theContextStatusStaysCalmUntilTheWindowIsNearlyOrFullyUsed() {
        assertNull(usageContextStatus(null))
        assertNull(usageContextStatus(0))
        assertNull(usageContextStatus(70))
        assertNull(usageContextStatus(89))
        assertEquals("Nearly full", usageContextStatus(90))
        assertEquals("Nearly full", usageContextStatus(99))
        assertEquals("Context full", usageContextStatus(100))
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

    @Test fun everyLanguageKeepsBothPlaceholdersOfTheUsedOfWindowLabel() {
        for (language in GapsAssets.languages - "en") {
            val text = GapsAssets.translated(language, "{0} of {1} used")
            assertTrue("$language has no translation of the used-of-window label", text.isNotBlank())
            assertTrue("$language drops {0}: \"$text\"", text.contains("{0}"))
            assertTrue("$language drops {1}: \"$text\"", text.contains("{1}"))
        }
    }

    @Test fun theUsagePageStringsAreTranslatedInEveryLanguage() {
        val shown = listOf("Session usage", "Refresh usage", "Open a chat first", "Tokens in", "Tokens out", "Context window", "Context used",
            "Cumulative tokens for this chat.", "{0} of {1} used", "{0}% of context", "Nearly full", "Context full", "Details", "Expanded", "Collapsed", "Retry",
            "estimated", "The context breakdown isn't available for this chat.")
        val missing = shown.flatMap { text -> GapsAssets.languages.filter { it != "en" && GapsAssets.translated(it, text).isBlank() }.map { "$it: $text" } }
        assertTrue("Session usage text without a translation:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test fun theBreakdownKeepsHermesOrderTokensAndRoundedShares() {
        assertEquals(17, contextCategoryShare(64_000, 384_000))
        assertEquals(25, contextCategoryShare(96_000, 384_000))
        assertEquals(100, contextCategoryShare(384_000, 384_000))
        assertEquals("No total means no invented share", 0, contextCategoryShare(0, 0))
    }

    @Test fun theBreakdownReadsHermesOrderLabelsTokensAndEstimate() {
        val usage = json("context_breakdown" to json(
            "categories" to JSONArray()
                .put(json("id" to "system_prompt", "label" to "System prompt", "tokens" to 64_000, "color" to "var(--context-usage-system)"))
                .put(json("id" to "conversation", "label" to "Conversation", "tokens" to 176_000, "color" to "var(--context-usage-conversation)")),
            "context_estimated" to true,
        ))
        val breakdown = requireNotNull(contextUsageBreakdown(usage))
        assertEquals(listOf("system_prompt", "conversation"), breakdown.categories.map { it.id })
        assertEquals(listOf("System prompt", "Conversation"), breakdown.categories.map { it.label })
        assertEquals(240_000L, breakdown.total)
        assertTrue(breakdown.estimated)
        assertNull("No categories is not a breakdown", contextUsageBreakdown(json("categories" to JSONArray())))
    }

    @Test fun hermesCategoryColoursAreDistinctAndReadableInLightAndDark() {
        val tokens = listOf("system" to "system_prompt", "tools" to "tool_definitions", "rules" to "rules", "skills" to "skills",
            "mcp" to "mcp", "subagents" to "subagent_definitions", "memory" to "memory", "conversation" to "conversation")
        val light = Color(0xFFF4EEE5)
        val dark = Color(0xFF17171A)
        val mapped = tokens.map { (token, id) -> contextCategoryBaseColor(ContextUsageCategory(id, id, 1, "var(--context-usage-$token)")) }
        assertEquals("Every Hermes category has its own hue", tokens.size, mapped.distinct().size)
        for ((index, color) in mapped.withIndex()) {
            assertTrue("${tokens[index].first} is readable on a light surface", nativeContrast(readableInk(color, min = 3.05f) { listOf(light) }, light) >= 3f)
            assertTrue("${tokens[index].first} is readable on a dark surface", nativeContrast(readableInk(color, min = 3.05f) { listOf(dark) }, dark) >= 3f)
        }
    }
}
