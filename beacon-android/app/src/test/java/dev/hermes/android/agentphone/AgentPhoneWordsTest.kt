package dev.hermes.android.agentphone

import dev.hermes.android.data.AgentPhoneLineStrings
import dev.hermes.android.ui.AGENT_PHONE_ROUTE
import dev.hermes.android.ui.AgentPhonePageStrings
import dev.hermes.android.ui.AgentPhoneSetupStrings
import dev.hermes.android.ui.normalized
import dev.hermes.android.ui.settingsDescriptions
import dev.hermes.android.ui.settingsKeywordsOf
import dev.hermes.android.ui.settingsLabel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The words of {name}'s phone (§0.1, §8, §10 "words snapshot"): no SDK/AVD/adb/emulator/container/KVM/gRPC word on any screen outside
 * the two fix lines that need one; every line in all 16 languages, keeping its holes; Google's terms never through the translator.
 */
class AgentPhoneWordsTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private val catalog by lazy { JSONObject(File(app, "src/main/assets/native-locales.json").readText(Charsets.UTF_8)) }
    private val languages = listOf("en", "it", "ja", "ru", "es", "de", "zh", "zh-Hant", "pt", "ko", "fr", "cs", "tr", "pl", "vi", "ar")
    private val sources = listOf("src/main/java/dev/hermes/android/ui/AgentPhoneSetup.kt", "src/main/java/dev/hermes/android/ui/AgentPhoneSettings.kt", "src/main/java/dev/hermes/android/data/AgentPhoneApi.kt",
        "src/main/java/dev/hermes/android/ui/AgentPhoneWelcome.kt")
        .map { File(app, it).readText(Charsets.UTF_8) }

    /** The two L1a fix lines that name what the owner has to change, by design (§8: "no container/KVM outside fix lines"). */
    private val fixLines = setOf(
        "Give the container read and write access to /dev/kvm, or run Hermes on a computer instead.",
        "Turn on virtualisation (KVM) on this computer.",
    )
    private val jargon = Regex("""\b(sdk|avd|adb|emulators?|containers?|kvm|grpc|qemu|apk)\b""", RegexOption.IGNORE_CASE)
    private fun jargonIn(texts: Collection<String>) = texts.filter { it !in fixLines && jargon.containsMatchIn(it) }

    /** Every string the screens can show: the literals in the three files (not test tags, routes or ids) and the lists passed by a variable. */
    private fun shown(): Set<String> {
        val literal = Regex(""""((?:[^"\\\n]|\\.)*)"""")
        val code = Regex("""^[A-Za-z0-9_.:/\-{}$ ]*$""")
        val fromSource = sources.flatMap { text -> literal.findAll(text).map { it.groupValues[1] }.toList() }
            .filter { it.isNotBlank() && !(code.matches(it) && ' ' !in it) && !it.startsWith("agent-phone") && !it.startsWith("https://") && !it.startsWith("dev.hermes") }
        return (fromSource + AgentPhoneLineStrings + AgentPhoneSetupStrings + AgentPhonePageStrings + listOf(settingsLabel(AGENT_PHONE_ROUTE), settingsDescriptions.getValue(AGENT_PHONE_ROUTE))).toSet()
    }

    /** The UI's own words: every literal in the three files that is a sentence or a label, plus the lists. */
    private fun words(): List<String> = (sources.flatMap { text -> Regex("""uiText\("((?:[^"\\\n]|\\.)*)"\)""").findAll(text).map { it.groupValues[1] }.toList() } +
        AgentPhoneLineStrings + AgentPhoneSetupStrings + AgentPhonePageStrings +
        listOf(settingsLabel(AGENT_PHONE_ROUTE), settingsDescriptions.getValue(AGENT_PHONE_ROUTE), settingsKeywordsOf(AGENT_PHONE_ROUTE))).distinct()

    @Test fun theCheckerFindsAPlantedWord() {
        assertEquals(listOf("Start the emulator first."), jargonIn(listOf("Start the emulator first.", "Turn on Hermes sign-in first.")))
        assertEquals(listOf("Ask adb to wait."), jargonIn(listOf("Ask adb to wait.")))
        assertTrue(jargonIn(fixLines).isEmpty())
    }

    @Test fun noScreenSaysSdkAvdAdbEmulatorContainerOrKvmOutsideTheFixLines() {
        val found = jargonIn(shown())
        assertTrue("jargon on a screen: $found", found.isEmpty())
    }

    @Test fun everyLineIsInAllSixteenLanguagesKeepingItsHoles() {
        val holes = Regex("""\{name\}|\{\d\}""")
        val missing = mutableListOf<String>()
        for (english in words()) for (language in languages) {
            val value = catalog.getJSONObject(language).optString(normalized(english))
            if (value.isBlank()) { missing += "$language: $english"; continue }
            val want = holes.findAll(english).map { it.value }.sorted().toList()
            val got = holes.findAll(value).map { it.value }.sorted().toList()
            if (want != got) missing += "$language keeps $got of $want: $english"
        }
        assertTrue("missing or broken: ${missing.take(40)} (${missing.size})", missing.isEmpty())
    }

    @Test fun aTranslatedSentenceIsNotTheEnglishOneAndIsInItsOwnScript() {
        val cjk = Regex("""[぀-ヿ㐀-鿿가-힯]""")
        val arabic = Regex("""[؀-ۿ]""")
        val wrong = mutableListOf<String>()
        for (english in words().filter { it.split(' ').size >= 4 }) for (language in languages - "en") {
            val value = catalog.getJSONObject(language).optString(normalized(english))
            if (value == english) wrong += "$language kept the English: $english"
            if (language !in setOf("ja", "ko", "zh", "zh-Hant") && cjk.containsMatchIn(value)) wrong += "$language has CJK: $english"
            if (language in setOf("ja", "ko", "zh", "zh-Hant") && !cjk.containsMatchIn(value)) wrong += "$language has no CJK: $english"
            if (language == "ar" && !arabic.containsMatchIn(value)) wrong += "ar is not Arabic: $english"
        }
        assertTrue(wrong.take(30).joinToString("\n"), wrong.isEmpty())
    }

    @Test fun theEnglishCatalogSaysTheLinesExactly() {
        // English shows the code's own words and never reads the catalog; older entries may differ in case only ("on" for On).
        val off = words().filter { !catalog.getJSONObject("en").optString(normalized(it)).equals(it, ignoreCase = true) }
        assertTrue("en entries differ from the code: $off", off.isEmpty())
    }

    @Test fun googlesTermsNeverGoThroughTheTranslator() {
        val setup = sources[0]
        assertTrue("the licence text is shown", setup.contains("Text(licence.text"))
        assertTrue("never translated", !Regex("""uiText\([^)]*licence""").containsMatchIn(setup))
        assertTrue("left to right in every language", setup.contains("LocalLayoutDirection provides LayoutDirection.Ltr"))
    }

    @Test fun thePhonePageSaysTheTwoHonestLinesUnderAskBeforeAndNoOtherRuleLines() {
        // The owner decided both lines (design Q3 and Q11); Root's 17 Nov removal was wrong and is undone (PHONEFIX-121, P2-6).
        // The code and loopback rule lines stay off the phone page; the local-programs line stays in Before you start's disclaimer.
        val page = sources[1]
        for (line in listOf("Payment questions cover {name}'s phone tool.", "Scheduled orders can't use cards from your vault."))
            assertTrue("the phone page must say: $line", page.contains("uiText(\"$line\")"))
        val ask = page.indexOf("uiText(\"Ask before\")")
        val section = page.lastIndexOf("SettingsSection(uiText(\"Using the phone\")", ask)
        assertTrue("the lines are the footer of the section that holds Ask before", section in 0 until ask && page.indexOf("footer = honestLines", section) in section until ask)
        for (line in listOf("Codes fill only from", "Apps on {name}'s phone can reach programs on this computer that don't ask for a sign-in."))
            assertFalse(line, (page + sources[3]).contains(line))
        assertTrue(sources[0].contains("\"Apps on the phone can reach programs on your Hermes computer that don't ask for a sign-in.\""))
    }

    // REVIEW-FIX-UI-121 (lean): the checks page's title and line pairs are typed once, in one table.
    @Test fun theChecksWordsAreTypedOnce() {
        val setup = File(app, "src/main/java/dev/hermes/android/ui/AgentPhoneSetup.kt").readText(Charsets.UTF_8)
        for (word in listOf("\"Not ready yet\"", "\"Almost ready\"", "\"Nothing to fix.\"", "\"This can take a minute or two.\""))
            assertEquals(word, 1, Regex(Regex.escape(word)).findAll(setup).count())
    }

    @Test fun oneNamedHelperFillsTheAssistantsName() {
        // Ponytail (UX-121): one String.named() for every agent-phone screen, and no screen word with {0} for the name.
        val files = File(app, "src/main/java/dev/hermes/android").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && (it.name.startsWith("AgentPhone") || it.name.startsWith("AgentApproval")) }.toList()
        val helpers = files.flatMap { file -> Regex("""fun String\.named\(""").findAll(file.readText()).map { file.name }.toList() }
        assertEquals(listOf("AgentPhoneSetup.kt"), helpers)
        assertTrue(files.filter { "{0}'s phone" in it.readText() || "{0} has its phone" in it.readText() }.map { it.name }.toString(),
            files.none { "{0}'s phone" in it.readText() || "{0} has its phone" in it.readText() })
    }

    @Test fun setupSaysStepsNotMechanicsAndKeepsThePrerequisites() {
        // P2-1: no mechanics in the setup's words; the owner's prerequisites stay as three short rows the owner confirms.
        val setup = sources[0]
        for (gone in listOf("Steps tick themselves", "Logins you type are offered", "We check the computer", "can't see the screen while"))
            assertFalse(gone, setup.contains(gone))
        val before = setup.substringAfter("private fun BeforeContent()").substringBefore("@Composable private fun ConfirmRow")
        assertEquals(3, Regex("""NeedRow\(""").findAll(before).count())
        assertTrue(setup.contains("uiText(\"This computer has these\")"))
    }
}
