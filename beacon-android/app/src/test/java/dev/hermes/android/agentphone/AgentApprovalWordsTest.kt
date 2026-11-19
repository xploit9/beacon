package dev.hermes.android.agentphone

import dev.hermes.android.ui.normalized
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** (L6's AgentPhoneWordsTest, renamed at the join: L4 has a test of that name.) Every word L6 shows: in all 15 other languages, short and plain, with no jargon, holes kept (EMU-DESIGN §0.1, §8; owner's rules). */
class AgentApprovalWordsTest {
    private val catalog: JSONObject = run {
        val app = requireNotNull(listOf(".", "app", "beacon-android/app").map(::File).firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
            "Run this check from the Beacon app, Android project or repository root."
        }
        JSONObject(File(app, "src/main/assets/native-locales.json").readText())
    }
    private val languages = catalog.keys().asSequence().toList()

    /** Everything the dialog, the list and the notifications pass through the catalog. */
    private val words = (AgentApprovalWords + AgentApprovalCardWords + AgentApprovalDialogWords + AgentApprovalNoticeWords + AgentPhoneLogWords +
        AgentPhoneLogScreenWords + listOf("Pay {0} at {1} with {2}?", "Fill {0} in {1}?")).distinct()

    /** A word that is honestly the same in some language: German and French say "Code" too. */
    private val sameEverywhere = setOf("Code")

    @Test fun everyWordIsInEveryLanguage() {
        assertEquals(16, languages.size)
        // English shows the app's own words as written (NativeLocalization never reads the English catalog).
        val missing = (languages - "en").flatMap { language -> words.filter { catalog.getJSONObject(language).optString(normalized(it)).isBlank() }.map { "$language: $it" } }
        assertTrue("missing:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test fun noLanguageLeavesAWordInEnglish() {
        val english = words.filter { it !in sameEverywhere }
        val left = (languages - "en").flatMap { language -> english.filter { catalog.getJSONObject(language).optString(normalized(it)) == it }.map { "$language: $it" } }
        assertTrue("left in English:\n" + left.joinToString("\n"), left.isEmpty())
    }

    @Test fun everyHoleSurvivesTranslation() {
        val hole = Regex("""\{(?:\d|name)\}""")
        for (word in words) {
            val holes = hole.findAll(word).map { it.value }.sorted().toList()
            if (holes.isEmpty()) continue
            for (language in languages) {
                val shown = catalog.getJSONObject(language).optString(normalized(word))
                // English has no entry for a word another lane already shipped (the app shows its own English as written).
                if (language == "en" && shown.isBlank()) continue
                assertEquals("$language: $word -> $shown", holes, hole.findAll(shown).map { it.value }.sorted().toList())
            }
        }
    }

    @Test fun noScreenSaysAnyJargon() {
        val jargon = Regex("""\b(sdk|avd|adb|emulator|emulated|container|kvm|grpc|api|lease|token)\b""", RegexOption.IGNORE_CASE)
        for (word in words) assertFalse(word, jargon.containsMatchIn(word))
    }

    @Test fun titlesAndButtonsStayShort() {
        for (word in words) assertTrue("too long: $word", word.length <= 120)
        for (title in AgentApprovalWords.filter { it.startsWith("{name} ") && !it.contains('.') }) assertTrue(title, title.split(' ').size <= 8)
    }
}
