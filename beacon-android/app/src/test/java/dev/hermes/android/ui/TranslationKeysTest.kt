package dev.hermes.android.ui

import dev.hermes.android.data.ErrorStrings
import dev.hermes.android.tasks.DeliveryNameStrings
import dev.hermes.android.tasks.ScheduleBuilderStrings
import dev.hermes.android.updates.PluginRowStrings
import dev.hermes.android.updates.UpdateFailureStrings
import dev.hermes.android.voice.CallToolStrings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Text the UI passes to uiText needs an English entry in native-locales.json, or no language can translate it. Literals typed
 * inside uiText, form, field and notice calls and the values of an `optionLabels = mapOf(...)` are read from the source; fixed
 * labels that reach uiText through a variable are listed in a `...Strings` value next to their source, and every entry of
 * those lists is checked here.
 */
class TranslationKeysTest {
    // Gradle runs unit tests from the app module; the other roots cover the Android project and the repository.
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }

    // The opening of a uiText or assistantText call; its argument is read up to the matching parenthesis.
    private val call = Regex("""\b(?:uiText|assistantText)\(""")
    // Plain arguments that NativeForm, Choice and the snackbar run through uiText: a form's title, a select's label, a notice.
    private val firstArgument = Regex("""\b(?:ActionForm|Choice|notice)\(""")
    private val fieldLabel = Regex("""\bField\(""")
    private val namedText = Regex("""\b(?:help|explanation|actionLabel|section)\s*=\s*""")
    // A Choice or Field option label: the text after `to` in each `"id" to "Text"` pair of the map.
    private val optionLabels = Regex("""\boptionLabels\s*=\s*mapOf\(""")
    private val pairKey = Regex("""^\s*"(?:[^"\\\n]|\\.)*"\s+to\s+""")
    // Kana, Chinese characters and Hangul; only the Japanese, Korean and Chinese catalogs may hold them.
    private val cjk = Regex("""[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]""")
    private fun writtenInTheRightScript(language: String, value: String) = language in setOf("ja", "ko", "zh", "zh-Hant") || !cjk.containsMatchIn(value)
    private val branch = Regex("""^(?:if|when)\b""")
    private val quoted = Regex(""""((?:[^"\\\n]|\\.)*)"""")
    private val template = Regex("""(?<!\\)\$[A-Za-z_{]""")
    private fun unescape(raw: String) = Regex("""\\(u[0-9a-fA-F]{4}|.)""").replace(raw) { match ->
        val code = match.groupValues[1]
        when { code.length == 5 -> code.drop(1).toInt(16).toChar().toString(); code == "n" -> "\n"; code == "t" -> "\t"; code == "r" -> "\r"; code == "b" -> "\b"; else -> code }
    }
    /** The arguments of a call whose opening parenthesis comes just before [start], split at top-level commas; parentheses and commas inside string literals do not count. */
    private fun arguments(text: String, start: Int): List<String> {
        val found = mutableListOf<String>()
        var depth = 1
        var from = start
        var i = start
        var inString = false
        while (i < text.length && depth > 0) {
            val c = text[i]
            when {
                inString && c == '\\' -> i++
                inString -> inString = c != '"'
                c == '"' -> inString = true
                c == '(' || c == '{' || c == '[' -> depth++
                c == ')' || c == '}' || c == ']' -> depth--
                c == ',' && depth == 1 -> { found += text.substring(from, i); from = i + 1 }
            }
            i++
        }
        return found + text.substring(from, (i - 1).coerceAtLeast(from))
    }
    /**
     * The fixed texts a call can show: its argument when that is one literal, or every result literal of an `if` or `when`
     * argument. A literal compared against, used as a `when` condition or passed to another call is code, not text.
     */
    private fun shown(argument: String): List<String> {
        val whole = quoted.matchEntire(argument.trim())
        val found = when {
            whole != null -> listOf(whole)
            branch.containsMatchIn(argument.trimStart()) -> quoted.findAll(argument).filter { match ->
                val before = argument.take(match.range.first).trimEnd()
                val after = argument.drop(match.range.last + 1).trimStart()
                listOf("==", "!=", "(", ",").none { before.endsWith(it) } && listOf("->", "==", "!=", ",", ".", ")").none { after.startsWith(it) }
            }.toList()
            else -> emptyList()
        }
        // A "$name" template is not a fixed key, so it is left out.
        return found.map { it.groupValues[1] }.filterNot { template.containsMatchIn(it) }
    }
    /** Each literal in [text] with where it is called. assistantText passes its literal straight to uiText. */
    private fun literalsIn(text: String, name: String): List<Pair<String, String>> {
        // [pick] chooses which of a call's arguments are the shown text.
        fun read(open: Regex, pick: (List<String>) -> List<String>) = open.findAll(text).flatMap { match ->
            val line = text.take(match.range.first).count { c -> c == '\n' } + 1
            pick(arguments(text, match.range.last + 1)).flatMap(::shown).map { unescape(it) to "$name:$line" }
        }.toList()
        return (read(call) { listOf(it.first()) } + read(firstArgument) { listOf(it.first()) } + read(fieldLabel) { listOfNotNull(it.getOrNull(1)) } + read(namedText) { listOf(it.first()) } +
            read(optionLabels) { pairs -> pairs.map { it.replaceFirst(pairKey, "") } }).filter { it.first.isNotBlank() }
    }
    private fun literals(): List<Pair<String, String>> = File(app, "src/main").walkTopDown().filter { it.extension == "kt" }.sortedBy { it.path }.toList().flatMap { literalsIn(it.readText(), it.name) }

    /** The catalog lines that lack a translation, as "language where text". English falls back to the literal itself; every other language needs an entry, or uiText's alias for it. */
    private fun untranslated(found: List<Pair<String, String>>): List<String> {
        val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText())
        return catalog.keys().asSequence().filter { it != "en" }.flatMap { language ->
            val strings = catalog.getJSONObject(language)
            fun translated(key: String) = strings.optString(key).let { it.isNotBlank() && writtenInTheRightScript(language, it) }
            found.filterNot { (text, _) -> translated(normalized(text)) || translated(normalized(nativeAliases[text] ?: text)) }
                .map { (text, at) -> "$language $at \"$text\"" }
        }.distinct().toList()
    }

    /** The Settings overview shows each fixed summary part through uiText; a few snapshots reach every one of them. */
    private fun settingsSummaryTexts(): List<String> {
        val snapshots = listOf(
            SettingsSnapshot(connected = true, profile = "work", sendKey = "enter", showThinking = true, theme = "dark", installedVersion = "1.0.0", voiceProviderId = "gemini", voiceKeySaved = true, keepConnectedEnabled = true),
            SettingsSnapshot(sendKey = "ctrl_enter", showThinking = false, theme = "light", updateReady = true, voiceProviderId = "gemini", voiceKeySaved = false, keepConnectedEnabled = false),
            SettingsSnapshot(sendKey = "shift+enter", theme = "system", updateName = "1.1.0", installedVersion = "1.0.0"),
        )
        return snapshots.flatMap { snapshot -> settingsSearchRoutes.flatMap { route -> settingsSummary(route, snapshot) } }.filter { it.translate }.map { it.text }.distinct()
    }

    @Test fun branchLiteralsCountAndCodeComparisonsDoNot() {
        assertEquals(listOf("Checking for updates…", "You're up to date"), shown("""if (checking) "Checking for updates…" else "You're up to date""""))
        assertEquals(listOf("Done", "Failed"), shown("""when (state.outcome) { "success" -> "Done"; "failed", "refused" -> "Failed"; else -> label }"""))
        assertEquals(listOf("Answer"), shown("""if (call.state == "ringing") "Answer" else label"""))
        assertEquals(listOf("Pin"), shown(""" "Pin" """))
        assertEquals(emptyList<String>(), shown("""label"""))
        assertEquals(emptyList<String>(), shown("""if (x) "Hello ${'$'}name" else other"""))
    }

    @Test fun formFieldChoiceAndNoticeLiteralsAreRead() {
        val code = """
            vm.notice("Saved")
            vm.notice("Moved to " + name)
            vm.openForm(ActionForm("Delete project?", "/api/x", listOf(Field("title", "Project name", help = "Shown in the list")), explanation = "This can't be undone.", actionLabel = "Delete", destructive = true))
            ActionForm(if (job == null) "Create schedule" else "Edit schedule", path, fields)
            Field("section", "Start hour", FieldType.CHOICE, section = "Fixed window")
            Field("note", "Hello ${'$'}name")
            Choice("Theme", value, options, { })
            Choice("Delivery", value, options, { }, Modifier, optionLabels = mapOf("notify" to "Notify", "call" to "Call"), enabled = on)
            Choice("Model", key, keys, { }, optionLabels = mapOf("" to "Profile default") + models)
            Field("night", "Even at night", optionLabels = mapOf("a" to label, "b" to "Time to go"))
        """.trimIndent()
        assertEquals(
            setOf("Saved", "Delete project?", "Project name", "Shown in the list", "This can't be undone.", "Delete", "Create schedule", "Edit schedule", "Start hour", "Fixed window", "Theme", "Delivery", "Notify", "Call", "Model", "Profile default", "Even at night", "Time to go"),
            literalsIn(code, "Sample.kt").map { it.first }.toSet(),
        )
    }

    @Test fun aValueInChineseCharactersFitsOnlyAChineseJapaneseOrKoreanCatalog() {
        assertFalse(writtenInTheRightScript("es", "Servidores MCP 服务器"))
        assertFalse(writtenInTheRightScript("ru", "ファイル"))
        assertTrue(writtenInTheRightScript("es", "Servidores MCP"))
        assertTrue(writtenInTheRightScript("ru", "Серверы MCP"))
        listOf("ja" to "ファイル", "ko" to "파일", "zh" to "文件", "zh-Hant" to "檔案").forEach { (language, value) -> assertTrue(language, writtenInTheRightScript(language, value)) }
    }

    @Test fun everyLiteralPassedToUiTextIsTranslatedInEveryLanguage() {
        val found = literals()
        assertTrue("Found only ${found.size} uiText literals, so the scan is broken.", found.size > 100)
        val missing = untranslated(found)
        assertTrue("These uiText, form and notice literals show in English in another language. Add their translations to native-locales.json:\n" +
            missing.joinToString("\n"), missing.isEmpty())
    }

    @Test fun everyFixedLabelBehindAVariableIsTranslatedInEveryLanguage() {
        val lists = mapOf(
            "BackgroundActivityStrings" to BackgroundActivityStrings, "ChatStrings" to ChatStrings, "ConversationStrings" to ConversationStrings,
            "HistoryStrings" to HistoryStrings, "KanbanStrings" to KanbanStrings, "SettingsStrings" to SettingsStrings, "settingsSummary" to settingsSummaryTexts(),
            "ScheduleStateStrings" to ScheduleStateStrings, "ApprovalStrings" to ApprovalStrings, "NavigationStrings" to NavigationStrings,
            "UpdateFailureStrings" to UpdateFailureStrings, "PluginRowStrings" to PluginRowStrings, "CallToolStrings" to CallToolStrings,
            "ErrorStrings" to ErrorStrings, "DeliveryNameStrings" to DeliveryNameStrings, "ScheduleBuilderStrings" to ScheduleBuilderStrings, "HistoryRetentionStrings" to HistoryRetentionStrings, "VoiceStyleStrings" to VoiceStyleStrings,
        )
        lists.forEach { (name, texts) -> assertTrue("$name is empty, so nothing is checked.", texts.isNotEmpty()) }
        val missing = untranslated(lists.flatMap { (name, texts) -> texts.map { it to name } })
        assertTrue("These fixed labels show in English in another language. Add their translations to native-locales.json:\n" +
            missing.joinToString("\n"), missing.isEmpty())
    }

    @Test fun visibleTextGoesThroughUiText() {
        // A capitalised literal handed straight to Text or a setup button, or as an icon's description, skips translation.
        val raw = Regex("""\b(?:Text|SetupTextAction|SetupPrimaryButton)\(\s*"[A-Z]|\bIcon\([^"]*?,\s*"[A-Z]""")
        val found = File(app, "src/main").walkTopDown().filter { it.extension == "kt" }.sortedBy { it.path }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line -> "${file.name}:${index + 1}".takeIf { raw.containsMatchIn(line) && !line.trimEnd().endsWith("// verbatim") } }
        }.toList()
        assertTrue("Wrap these in uiText(...), or end the line with // verbatim when the text must stay as written:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
