package dev.hermes.android.ui

import dev.hermes.android.core.json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** CTRX-125: the composer ring's display rule and its one spoken sentence in every language. */
class ContextIndicatorTest {
    private fun figures(input: Long? = 1, output: Long? = 1, window: Long? = 32_000, used: Long? = 8_000) =
        SessionUsageFigures(input, output, window, used, emptySet())

    @Test fun knownUsedAndWindowBecomeAShare() {
        val share = composerContextUsage(figures(input = 18_400, output = 2_300, window = 1_000_000, used = 384_000), hasReplies = true)
        assertEquals(38, share?.percent)
        assertEquals(384_000L, share?.used)
        assertEquals(1_000_000L, share?.window)
    }

    @Test fun usedWithoutAWindowIsNotAShare() {
        assertNull(composerContextUsage(figures(window = null, used = 384_000), hasReplies = true))
    }

    @Test fun aWindowWithoutUsedIsNotAShare() {
        assertNull(composerContextUsage(figures(window = 1_000_000, used = null), hasReplies = true))
    }

    @Test fun anUnreportedCountedChatShowsNothing() {
        assertNull(composerContextUsage(figures(input = 0, output = 0, window = 32_000, used = 4_200), hasReplies = true))
    }

    @Test fun aFullWindowIsOneHundredPercent() {
        assertEquals(100, composerContextUsage(figures(window = 1_000_000, used = 1_000_000), hasReplies = true)?.percent)
    }

    /** The owner's screenshot: 8.7M lifetime tokens under a 1M window is not 100% of the context. */
    @Test fun theLifetimeTotalIsNeverTheRingShare() {
        val usage = sessionUsageFigures(json("input_tokens" to 24_000, "output_tokens" to 30_000,
            "total_tokens" to 8_700_000, "context_tokens" to 384_000, "context_length" to 1_000_000))
        val share = composerContextUsage(usage, hasReplies = true)
        assertEquals(38, share?.percent)
        assertEquals(384_000L, share?.used)
        assertEquals(1_000_000L, share?.window)
        assertNull("A lifetime total alone is no share", composerContextUsage(sessionUsageFigures(json("input_tokens" to 24_000, "output_tokens" to 30_000,
            "total_tokens" to 8_700_000, "context_length" to 1_000_000)), hasReplies = true))
    }

    @Test fun hermesReportedPercentAndEstimateReachTheRingAndTheCaption() {
        val usage = sessionUsageFigures(json("input_tokens" to 24_000, "output_tokens" to 30_000,
            "context_used" to 300_000, "context_max" to 1_000_000, "context_percent" to 38, "context_estimated" to true))
        assertEquals(38, composerContextUsage(usage, hasReplies = true)?.percent)
        assertTrue(usage.estimated)
    }

    @Test fun theVisibleShareUsesTheShownLanguagesDigits() {
        assertEquals("38%", formatContextPercent(38, Locale.US))
        assertEquals("100%", formatContextPercent(100, Locale.US))
    }

    @Test fun theArabicShareIsNonBlankAndKeepsItsPercentSign() {
        val arabic = formatContextPercent(38, Locale.forLanguageTag("ar"))
        assertTrue("Arabic share is blank", arabic.isNotBlank())
        // This JDK appends U+061C after the Arabic percent sign U+066A; ignore the bidi mark for the visible check.
        val visible = arabic.trim { it.isWhitespace() || it == '\u061C' }
        assertTrue("Arabic share lost its percent sign: $arabic", visible.endsWith('\u066A') || visible.endsWith('%'))
    }

    @Test fun theSpokenSentenceHasARealTranslationInAllFifteenLanguages() {
        val text = "Context {0} used, {1} of {2} tokens"
        val languages = listOf("ar", "it", "ja", "ru", "es", "de", "zh", "zh-Hant", "pt", "ko", "fr", "cs", "tr", "pl", "vi")
        val catalog = catalog()
        val missing = languages.filter { catalog.optJSONObject(it)?.optString(normalized(text)).orEmpty().isBlank() }
        assertTrue("Spoken context text without a translation:\n" + missing.joinToString("\n"), missing.isEmpty())
        for (language in languages) {
            val value = catalog.optJSONObject(language)?.optString(normalized(text)).orEmpty()
            for (placeholder in listOf("{0}", "{1}", "{2}")) assertTrue("$language lost $placeholder: $value", placeholder in value)
            assertFalse("$language left the sentence in English", value.equals(text, ignoreCase = true))
            // A lossy write turns non-ASCII into '?'; the English source has none, so a real translation has none.
            assertFalse("$language has question marks where text belongs: $value", '?' in value)
            assertFalse("$language has a replacement character: $value", '\uFFFD' in value)
        }
    }

    private fun catalog(): JSONObject {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map(::File).firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
            "app module not found"
        }
        return JSONObject(File(module, "src/main/assets/native-locales.json").readText(Charsets.UTF_8).removePrefix("\uFEFF"))
    }
}
