package dev.hermes.android.ux2text

import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.data.ownCommandEntries
import dev.hermes.android.ui.SlashCommandStrings
import dev.hermes.android.ui.computerOnlyCommands
import dev.hermes.android.ui.isBuiltInCommand
import dev.hermes.android.ui.mobileSlashCommands
import dev.hermes.android.ui.nativeAliases
import dev.hermes.android.ui.normalized
import dev.hermes.android.ui.slashCommandText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** UX-51: every built-in command Hermes offers a client reads as short user text, in all 16 languages; only custom commands keep Hermes' words. */
class CommandCoverageTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText(Charsets.UTF_8))
    private val languages = catalog.keys().asSequence().filter { it != "en" }.toList()

    private fun translated(language: String, text: String): String =
        catalog.getJSONObject(language).let { strings -> strings.optString(normalized(text)).ifBlank { strings.optString(normalized(nativeAliases[text] ?: text)) } }

    /** The built-ins of Hermes' registry that its command catalog (`commands.catalog`) lists for a client, with the terminal-only /redraw left out as the phone does (1 Nov 2026). */
    private val offered = listOf(
        "new", "clear", "history", "save", "retry", "prompt", "undo", "rewind", "title", "handoff", "branch", "worktree", "compress", "rollback", "snapshot", "export", "import",
        "stop", "bg", "btw", "agents", "journey", "queue", "steer", "goal", "heartbeat", "refine", "review", "loop", "plan", "moa", "subgoal", "status", "egress", "context",
        "whoami", "profile", "resume", "sessions", "config", "model", "codex-runtime", "personality", "statusbar", "battery", "timestamps", "diff", "verbose", "focus", "footer",
        "yolo", "approvals", "reasoning", "fast", "skin", "indicator", "voice", "wake", "busy", "autoprune", "tools", "toolsets", "skills", "memory", "bundles", "pet", "hatch",
        "learn", "init", "cron", "suggestions", "blueprint", "curator", "kanban", "reload", "reload-mcp", "reload-skills", "browser", "plugins", "help", "palette", "usage",
        "subscription", "login", "topup", "insights", "platforms", "copy", "paste", "image", "update", "version", "debug", "quit", "density", "logs", "mouse",
    )
    /** Messaging-only commands: the catalog leaves them out, so a phone never lists them. */
    private val gatewayOnly = listOf("start", "topic", "discard", "pause", "approve", "deny", "sethome", "commands", "restart", "platform")

    @Test fun everyBuiltInTheCatalogOffersReadsAsShortUserText() {
        val missing = offered.filterNot { isBuiltInCommand(it) }
        assertTrue("Built-ins without a short text: $missing", missing.isEmpty())
        // The row shows the short text and never Hermes' own description, whatever Hermes sends.
        for (name in offered) assertTrue(name, slashCommandText(name, "Hermes' own description (usage: /$name)") in SlashCommandStrings)
    }

    @Test fun theTwoCommandsTheReviewerNamedHaveTheirExactWords() {
        assertEquals("Write a prompt in your editor", slashCommandText("prompt", "Compose your next prompt in \$EDITOR (markdown), then send it"))
        assertEquals("Hand off to a messaging app", slashCommandText("handoff", "Hand off this session to a messaging platform (Telegram, Discord, etc.)"))
        // It opens an editor on the computer, so the phone leaves it out of the list (it keeps its short text for the check above).
        assertTrue(mobileSlashCommands(listOf(json("name" to "/prompt", "description" to "Compose your next prompt in \$EDITOR"))).isEmpty())
        assertEquals("/handoff", mobileSlashCommands(listOf(json("name" to "/handoff", "description" to "Hand off this session to a messaging platform"))).single().string("name"))
    }

    @Test fun messagingOnlyCommandsAreNotListedSoTheyHaveNoText() {
        gatewayOnly.forEach { assertFalse(it, isBuiltInCommand(it)) }
        assertFalse(isBuiltInCommand("redraw"))
    }

    @Test fun aCustomCommandKeepsHermesWordsAndTheSameObject() {
        val custom = json("name" to "standup", "description" to "Post the daily standup note")
        assertSame(custom, mobileSlashCommands(listOf(custom)).single())
        assertEquals("Post the daily standup note", slashCommandText("standup", "Post the daily standup note"))
        assertFalse(isBuiltInCommand("standup"))
        // A built-in's own description is replaced; its usage text never reaches the row.
        assertEquals("Manage schedules", mobileSlashCommands(listOf(json("name" to "cron", "description" to "Manage scheduled tasks (usage: /cron [list|add])"))).single().string("description"))
    }

    @Test fun everyTextIsShortAndHasNoMechanics() {
        for (text in SlashCommandStrings) {
            assertTrue(text, text.length <= 40)
            assertFalse(text, listOf("(", ")", ":", "/", "\$").any { it in text })
        }
    }

    @Test fun everyTextIsTranslatedIntoAllFifteenOtherLanguagesAndNoneIsAnEnglishCopy() {
        assertEquals(15, languages.size)
        val problems = SlashCommandStrings.flatMap { text ->
            languages.mapNotNull { language ->
                val value = translated(language, text)
                when {
                    value.isBlank() -> "$language \"$text\" has no translation"
                    value.equals(text, ignoreCase = true) -> "$language \"$text\" is still English"
                    value.length > 60 -> "$language \"$text\" is too long: \"$value\""
                    else -> null
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun germanStaysInformalAndArabicIsWrittenInArabic() {
        val formal = Regex("""\b(Sie|Ihr\w*|Ihnen)\b""")
        val arabicLetters = Regex("""[؀-ۿ]""")
        for (text in SlashCommandStrings) {
            assertFalse("de \"$text\"", formal.containsMatchIn(translated("de", text)))
            assertTrue("ar \"$text\"", arabicLetters.containsMatchIn(translated("ar", text)))
        }
    }

    // ---- Slop list, Root 5 (13 Nov 2026): Hermes built-ins that only work on the computer are not listed on a phone

    @Test fun computerOnlyBuiltInsAreNotListedOnAPhone() {
        assertEquals(setOf("prompt", "worktree", "statusbar", "battery", "timestamps", "verbose", "focus", "indicator", "palette", "quit", "wake", "pet", "hatch",
            "copy", "paste", "image", "browser", "skin", "density", "mouse"), computerOnlyCommands)
        val listed = mobileSlashCommands(offered.map { json("name" to it, "description" to "Hermes' own description") }).map { it.string("name") }
        assertEquals(offered.filterNot { it in computerOnlyCommands }, listed)
        // The names come as Hermes sends them, with a slash and in any case.
        assertTrue(mobileSlashCommands(listOf(json("name" to "/Copy", "description" to "Copy the last assistant response to clipboard"))).isEmpty())
        // footer and voice work from any client (not cli_only in Hermes), so they stay.
        for (kept in listOf("footer", "voice")) assertEquals(kept, mobileSlashCommands(listOf(json("name" to kept, "description" to "Toggle it"))).single().string("name"))
    }

    @Test fun theOwnersOwnCommandIsNeverHiddenEvenWithAHiddenName() {
        // Hermes lists a quick command under "User commands", a plugin command under "Plugin commands" and a skill in `skills`.
        val catalog = JSONObject("""{"pairs":[["/copy","Copy the last assistant response to clipboard"],["/copy","Copy the standup note to the team channel (usage: /copy)"],["/focus","Plugin focus timer"],["/image","Make a picture"],["/standup","Post the daily standup note"]],
            "categories":[{"name":"Info","pairs":[["/copy","Copy the last assistant response to clipboard"]]},{"name":"User commands","pairs":[["/copy","Copy the standup note to the team channel (usage: /copy)"],["/standup","Post the daily standup note"]]},{"name":"Plugin commands","pairs":[["/focus","Plugin focus timer"]]}],
            "skills":{"/image":{"usage":0}}}""")
        val own = ownCommandEntries(catalog)
        assertEquals(setOf("/copy" to "Copy the standup note to the team channel (usage: /copy)", "/standup" to "Post the daily standup note", "/focus" to "Plugin focus timer", "/image" to "Make a picture"), own)
        val pairs = catalog.getJSONArray("pairs")
        val rows = (0 until pairs.length()).map { i -> pairs.getJSONArray(i).let { pair -> json("name" to pair.getString(0).removePrefix("/"), "description" to pair.getString(1)).apply { if (pair.getString(0) to pair.getString(1) in own) put("own", true) } } }
        val listed = mobileSlashCommands(rows)
        // Hermes' own /copy is gone; the owner's /copy, the plugin's /focus and the skill /image stay, each with its own words.
        assertEquals(listOf("copy", "focus", "image", "standup"), listed.map { it.string("name") })
        assertEquals("Copy the standup note to the team channel", listed[0].string("description"))
        assertEquals("Plugin focus timer", listed[1].string("description"))
        assertEquals("Make a picture", listed[2].string("description"))
        // A catalog saved before this marker existed has no `own`: nothing crashes, and Hermes' own rows are still the only ones judged by name.
        assertTrue(ownCommandEntries(JSONObject()).isEmpty())
    }

    @Test fun theHideListMatchesHermesRegistryWhereTheHermesSourceIsInstalled() {
        val source = File(System.getenv("LOCALAPPDATA").orEmpty(), "hermes/hermes-agent/hermes_cli/commands.py")
        assumeTrue("Hermes is not installed here", source.isFile)
        val registry = source.readText(Charsets.UTF_8).substringAfter("COMMAND_REGISTRY: list[CommandDef]").split("CommandDef(").drop(1)
        fun entry(name: String) = registry.firstOrNull { Regex("""^\s*"$name"""").containsMatchIn(it) }
        // density and mouse are terminal-UI extras (tui_gateway/command_discovery.py), not registry commands.
        for (name in computerOnlyCommands - setOf("density", "mouse")) assertTrue("$name is cli_only in Hermes", entry(name)?.contains("cli_only=True") == true)
        for (name in listOf("footer", "voice")) assertTrue("$name is not cli_only, so it stays listed", entry(name)?.contains("cli_only=True") == false)
        val extras = File(source.parentFile.parentFile, "tui_gateway/command_discovery.py").readText(Charsets.UTF_8)
        for (name in listOf("density", "mouse")) assertTrue(name, "(\"/$name\"" in extras)
    }

    @Test fun aNewHermesBuiltInIsCaughtWhereTheHermesSourceIsInstalled() {
        val source = File(System.getenv("LOCALAPPDATA").orEmpty(), "hermes/hermes-agent/hermes_cli/commands.py")
        assumeTrue("Hermes is not installed here", source.isFile)
        val registry = source.readText(Charsets.UTF_8).substringAfter("COMMAND_REGISTRY: list[CommandDef]").split("CommandDef(").drop(1)
        val names = registry.mapNotNull { Regex("""^\s*"([a-z0-9-]+)"""").find(it)?.groupValues?.get(1) }
        assertTrue("The registry was not read", names.size > 50)
        val offeredNow = registry.filter { "gateway_only=True" !in it }.mapNotNull { Regex("""^\s*"([a-z0-9-]+)"""").find(it)?.groupValues?.get(1) }
        val unknown = offeredNow.filter { it != "redraw" && !isBuiltInCommand(it) }
        assertTrue("Hermes built-ins with no short text yet: $unknown", unknown.isEmpty())
    }
}
