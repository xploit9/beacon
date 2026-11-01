package dev.hermes.android.uxtour

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.data.NativeServerState
import dev.hermes.android.ui.SlashCommandStrings
import dev.hermes.android.ui.codeDirection
import dev.hermes.android.ui.isBuiltInCommand
import dev.hermes.android.ui.mobileSlashCommands
import dev.hermes.android.ui.nativeAliases
import dev.hermes.android.ui.normalized
import dev.hermes.android.ui.serverComponentIcon
import dev.hermes.android.ui.serverConnectedCounts
import dev.hermes.android.ui.serverMessageRestatesHero
import dev.hermes.android.ui.serverUpdateIsCurrent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** UX-40, UX-41, UX-42 (server pages), UX-45, UX-46, UX-49, UX-51 (words): the text and the logic around it. */
class ServerAndCommandTextTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText(Charsets.UTF_8))
    private val languages = catalog.keys().asSequence().filter { it != "en" }.toList()

    /** What uiText finds in [language] for [text]: its own entry, else the entry of its alias. */
    private fun translated(language: String, text: String): String =
        catalog.getJSONObject(language).let { strings -> strings.optString(normalized(text)).ifBlank { strings.optString(normalized(nativeAliases[text] ?: text)) } }

    @Test fun logLinesAndCommandsKeepTheirDirectionInAnArabicLayout() {
        val style = TextStyle().codeDirection()
        assertEquals(TextDirection.Ltr, style.textDirection)
        assertEquals(TextAlign.Left, style.textAlign)
    }

    @Test fun storageAndPlatformsHaveTheirOwnIconsAndNoOtherComponentSharesThem() {
        assertSame(Icons.Rounded.Storage, serverComponentIcon("storage"))
        assertSame(Icons.Rounded.Hub, serverComponentIcon("platforms"))
        assertNotSame(serverComponentIcon("storage"), serverComponentIcon("platforms"))
        assertSame(Icons.Rounded.Memory, serverComponentIcon("something-new"))
        listOf("gateway", "agent", "dashboard").forEach { assertNotSame(it, serverComponentIcon("storage"), serverComponentIcon(it)) }
    }

    @Test fun thePlatformCountSplitsIntoItsTwoNumbersAndNothingElseDoes() {
        assertEquals(1 to 1, serverConnectedCounts("1 / 1 connected"))
        assertEquals(0 to 3, serverConnectedCounts("0/3 connected"))
        assertEquals(2 to 5, serverConnectedCounts("  2 / 5 connected "))
        assertNull(serverConnectedCounts("running"))
        assertNull(serverConnectedCounts("1 / 1 connected today"))
        assertNull(serverConnectedCounts(""))
    }

    @Test fun aSentenceThatOnlyRestatesTheUpToDateHeroIsLeftOut() {
        assertTrue(serverMessageRestatesHero("You're on the latest version.", current = true))
        assertTrue(serverMessageRestatesHero("Already up to date", current = true))
        assertTrue(serverMessageRestatesHero("Hermes is up-to-date.", current = true))
        // The same sentence under a hero that says something else is news, and other sentences always show.
        assertFalse(serverMessageRestatesHero("You're on the latest version.", current = false))
        assertFalse(serverMessageRestatesHero("Update failed: disk full", current = true))
        assertFalse(serverMessageRestatesHero("Restart Hermes to finish", current = true))
    }

    @Test fun theHeroIsCurrentOnlyWhenAResultWasReadAndNothingIsWaitingOrRunning() {
        val current = NativeServerState(update = json("update_available" to false, "message" to "You're on the latest version."))
        assertTrue(serverUpdateIsCurrent(current))
        assertFalse(serverUpdateIsCurrent(NativeServerState()))
        assertFalse(serverUpdateIsCurrent(current.copy(update = json("update_available" to true))))
        assertFalse(serverUpdateIsCurrent(current.copy(applying = true)))
        assertFalse(serverUpdateIsCurrent(current.copy(requestedAt = "2026-11-01T08:00:00Z")))
    }

    @Test fun everyBuiltInCommandDescriptionIsTranslatedInEveryLanguage() {
        assertEquals(16, languages.size + 1)
        val missing = SlashCommandStrings.flatMap { text -> languages.filter { translated(it, text).isBlank() }.map { "$it \"$text\"" } }
        assertTrue("Built-in command texts without a translation:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test fun onlyTheBuiltInCommandsTranslateAndACustomOneKeepsItsDescription() {
        listOf("new", "/new", "NEW", "reset", "clear", "history", "model", "usage", "help", "tools").forEach { assertTrue(it, isBuiltInCommand(it)) }
        listOf("deploy", "/standup", "", "redraw").forEach { assertFalse(it, isBuiltInCommand(it)) }
        // What the phone shows for the built-ins is exactly one of the translated texts; a custom command's text is Hermes' own.
        val rows = mobileSlashCommands(listOf(
            json("name" to "/new", "description" to "Start a new session (usage: /new)"),
            json("name" to "/deploy", "description" to "Ship the build to staging"),
            json("name" to "/status", "description" to "Show the current state"),
        ))
        assertEquals(listOf("New chat", "Ship the build to staging", "Show status"), rows.map { it.string("description") })
        assertTrue(rows.filter { isBuiltInCommand(it.string("name")) }.all { it.string("description") in SlashCommandStrings })
        assertFalse(rows[1].string("description") in SlashCommandStrings)
    }

    @Test fun theStringsThisPassAddedHaveARealTranslationInAllFifteenOtherLanguages() {
        val added = listOf(
            "Hasn't checked yet", "Tokens in", "Tokens out", "Hermes didn't report usage for this chat.", "Show full path",
            "{0} in {1}", "{0} ({1})", "Storage", "Platforms", "{0} of {1} connected",
        )
        assertEquals(15, languages.size)
        val missing = languages.flatMap { language -> added.filter { translated(language, it).isBlank() }.map { "$language \"$it\"" } }
        assertTrue("Strings without a translation:\n" + missing.joinToString("\n"), missing.isEmpty())
        // The words that fill the placeholders must survive in every language.
        for (language in languages) for (text in added.filter { "{" in it }) {
            val value = translated(language, text)
            listOf("{0}", "{1}").filter { it in text }.forEach { assertTrue("$language \"$text\" lost $it: \"$value\"", it in value) }
        }
        // English words are only acceptable in the languages that keep them (a name, a unit); the plain sentences must differ.
        for (language in languages) for (text in listOf("Hasn't checked yet", "Hermes didn't report usage for this chat.", "Show full path", "Tokens in", "Tokens out")) {
            assertFalse("$language left \"$text\" in English", translated(language, text).equals(text, ignoreCase = true))
        }
    }

    @Test fun theReplacedLabelsStayInTheCatalogForTheOtherScreensThatStillUseThem() {
        // "Not yet" (watch sources), "Input" and "Output" (tool cards) and "No purpose" keep their translations.
        listOf("Not yet", "Input", "Output").forEach { text -> languages.forEach { assertTrue("$it \"$text\"", translated(it, text).isNotBlank()) } }
    }
}
