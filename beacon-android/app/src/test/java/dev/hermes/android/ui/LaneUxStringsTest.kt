package dev.hermes.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lane UX: the words the review asked to change, in every language, and the one rule the rules list shares with the rule sheet. */
class LaneUxStringsTest {
    private val catalog: JSONObject = run {
        val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
            "Run this check from the Beacon app, Android project or repository root."
        }
        JSONObject(File(app, "src/main/assets/native-locales.json").readText())
    }
    private val languages = catalog.keys().asSequence().toList()

    /** The sixteen catalogs: English and the fifteen other languages. */
    @Test fun theCatalogStillHasEveryLanguage() {
        assertEquals(setOf("en", "it", "ja", "ru", "es", "de", "zh", "zh-Hant", "pt", "ko", "fr", "cs", "tr", "pl", "vi", "ar"), languages.toSet())
    }

    @Test fun everyChangedOrNewTextIsInEveryLanguage() {
        val texts = listOf(
            "Hermes wants to use your phone", "Call saved contacts after 3 seconds", "Write message drafts", "Only what you turned on above",
            "Beacon asks before it changes anything", "Voice call", "Mark as important", "Mark as not important", "Clear earlier ratings",
            "Couldn't clear earlier ratings. Try again.", "Starting screen share…",
        )
        for (language in languages) {
            val strings = catalog.getJSONObject(language)
            for (text in texts) assertTrue("$language is missing \"$text\"", strings.optString(normalized(text)).isNotBlank())
        }
        // English keeps the wording the app shows; the other languages do not leave it in English.
        for (text in texts) assertEquals(text, catalog.getJSONObject("en").getString(normalized(text)))
        for (language in languages - "en") for (text in texts) {
            assertFalse("$language shows \"$text\" in English", catalog.getJSONObject(language).getString(normalized(text)) == text)
        }
    }

    @Test fun arabicSaysMinutesAndKanbanTheWayTheReviewAsked() {
        val arabic = catalog.getJSONObject("ar")
        assertEquals("بعد {0} د", arabic.getString("in {0} min"))
        assertEquals("كانبان", arabic.getString("kanban"))
        assertEquals("اتصل بجهات الاتصال المحفوظة بعد 3 ثوانٍ", arabic.getString(normalized("Call saved contacts after 3 seconds")))
        // The sentence that names the board reads the same word as the tile.
        assertFalse(arabic.getString("create a kanban board in your hermes dashboard, then refresh it here.").contains("Kanban"))
        // The short minute is the same word everywhere Arabic shows minutes.
        assertEquals("{0} د", arabic.getString("{0} min"))
    }

    @Test fun everyLanguageKeepsTheMinutePlaceholder() {
        // English shows the app's own words, so it has no entry for this one.
        for (language in languages - "en") assertTrue("$language lost {0} in \"in {0} min\"", catalog.getJSONObject(language).optString("in {0} min").contains("{0}"))
    }

    @Test fun aSaveRefusedBecauseTheRulesAreFullIsRecognisedByTheLimitInItsMessage() {
        assertTrue(ruleLimitReached(IllegalStateException("Proactive request limit reached")))
        assertTrue(ruleLimitReached(IllegalStateException("LIMIT")))
        assertFalse(ruleLimitReached(IllegalStateException("Unavailable")))
        assertFalse(ruleLimitReached(IllegalStateException()))
    }

    @Test fun aRuleInTheListMayTakeSixLines() {
        assertEquals(6, RULE_TEXT_LINES)
    }
}
