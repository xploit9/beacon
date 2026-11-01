package dev.hermes.android.uxstrings

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards native-locales.json: a label that stands alone never ends in a colon, and the keys 1.1.23 added are
 * translated rather than left as English. The shared terms are English words the catalog already keeps in those languages.
 */
class CatalogCopyTest {
    // Gradle runs unit tests from the app module; the other roots cover the Android project and the repository.
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText())
    private val trailingColon = Regex("[:：]\\s*\$")

    // The 51 keys 1.1.23 added; each language has all of them.
    private val newKeys = listOf(
        "add a voice key in beacon", "allow the microphone to talk", "ask about this screen", "ask beacon", "ask by voice",
        "attach screen", "beacon assistant", "busy", "calls", "check that beacon answers", "checking beacon on your hermes...",
        "continue: {0}", "couldn't restart the dashboard. try again.", "couldn't send. try again.", "couldn't start the call",
        "dashboard", "didn't catch that", "do it on the pc instead", "hermes answered in {0} ms",
        "hermes didn't answer. try again.", "hermes is updating", "hermes took too long", "install beacon on your hermes",
        "installing", "needs restart", "not ready", "not set up", "not working", "open in beacon", "plugin", "plugin not installed",
        "question for you", "restarting dashboard", "restarting hermes", "screen attached",
        "server, signed-in account, and phones address sign out plugin health", "sign in to beacon", "slow to start",
        "stop listening", "talk to beacon", "the microphone didn't start. try again.", "the voice service didn't answer. try again.",
        "turn on keep connected", "type a message", "unlock", "unlock to see", "verifying", "voice typing isn't available",
        "waiting for you in beacon", "what's on my screen?", "your sign-in expired. sign in again to call.",
    )

    // English words the catalog keeps unchanged in these languages (for example "Plugin Hermes", "Dashboard Beacon").
    private val sharedTerms = mapOf(
        "plugin" to setOf("it", "es", "de", "pt", "fr", "cs", "vi"),
        "dashboard" to setOf("it", "de", "cs"),
    )

    private fun normalized(value: String) = value.lowercase().replace("…", "...").replace(Regex("\\s+"), " ").trim()
    private fun languages() = catalog.keys().asSequence().filter { it != "en" }.toList()

    // Words that stand alone, as a button or a menu header, never end in a colon. Prefixes the app follows with a value
    // ("Switched to" + a model name) keep theirs where the language reads "label: value".
    private val standalone = listOf("open", "show", "show conversations from")

    @Test fun aStandaloneLabelNeverEndsInAColon() {
        assertTrue("The catalog has ${languages().size} languages, so the scan is broken.", languages().size >= 15)
        val wrong = languages().flatMap { language ->
            val strings = catalog.getJSONObject(language)
            standalone.filter { key -> trailingColon.containsMatchIn(strings.optString(key)) }
                .map { key -> "$language \"$key\" -> \"${strings.getString(key)}\"" }
        }
        assertTrue("These labels end in a colon, though nothing follows them:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    // "On" is a status word beside a switch ("On" / "Off"), never the preposition or a calendar word (ja once read "曜日").
    @Test fun onIsAStatusWordInEveryLanguage() {
        val notAStatus = setOf("on", "в", "在", "v", "曜日")
        val wrong = languages().map { it to catalog.getJSONObject(it).optString("on") }.filter { (_, value) -> value.isBlank() || value.lowercase() in notAStatus }
        assertTrue("\"On\" must read as a status in every language: $wrong", wrong.isEmpty())
    }

    @Test fun newKeysAreTranslatedInEveryLanguage() {
        val copies = mutableListOf<String>()
        newKeys.forEach { key ->
            languages().forEach { language ->
                val strings = catalog.getJSONObject(language)
                assertTrue("$language has no translation for \"$key\"", strings.has(key))
                val value = strings.getString(key)
                if (normalized(value) == normalized(key) && language !in sharedTerms[key].orEmpty()) copies += "$language \"$key\" -> \"$value\""
            }
        }
        assertTrue("These new strings are the English text. Translate them, or add a shared term with its reason:\n" + copies.joinToString("\n"), copies.isEmpty())
    }
}
