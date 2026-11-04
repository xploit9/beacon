package dev.hermes.android.share

import dev.hermes.android.push.ReplyStrings
import dev.hermes.android.ui.normalized
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every line the reply notification and the share chooser show has a translation in each of the 15 languages, with {0} kept where the English has it. */
class ReplyShareTranslationsTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText())
    private val languages = catalog.keys().asSequence().filter { it != "en" }.toList()
    private val lines = (ReplyStrings.all + ShareStrings.all).distinct()
    private val cjk = Regex("""[぀-ヿ㐀-鿿가-힯]""")

    @Test fun thereAreFifteenLanguagesBesidesEnglish() = assertEquals(15, languages.size)

    @Test fun everyLineIsTranslatedInEveryLanguage() {
        val missing = languages.flatMap { language -> lines.filter { catalog.getJSONObject(language).optString(normalized(it)).isBlank() }.map { "$language: $it" } }
        assertTrue("untranslated: $missing", missing.isEmpty())
    }

    @Test fun thePlaceholderSurvivesAndTheScriptIsRight() {
        for (language in languages) for (line in lines) {
            val translated = catalog.getJSONObject(language).optString(normalized(line))
            assertEquals("$language / $line", "{0}" in line, "{0}" in translated)
            if (language !in setOf("ja", "ko", "zh", "zh-Hant")) assertTrue("$language / $line has no ${cjk.pattern} characters", !cjk.containsMatchIn(translated))
        }
    }

    @Test fun aTranslationIsNotJustTheEnglishLine() {
        // Names such as Beacon stay; a whole line left in English is a missed translation (the one-word "Reply" differs in every language here).
        // French spells "image" the way English does.
        val spelledAlike = setOf("fr: 1 image", "fr: {0} images")
        val same = languages.flatMap { language -> lines.filter { catalog.getJSONObject(language).optString(normalized(it)) == it }.map { "$language: $it" } }.filterNot { it in spelledAlike }
        assertTrue("left in English: $same", same.isEmpty())
    }
}
