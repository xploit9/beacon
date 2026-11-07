package dev.hermes.android.ux2text

import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.ui.SlashCommandStrings
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
        assertEquals("/prompt", mobileSlashCommands(listOf(json("name" to "/prompt", "description" to "Compose your next prompt in \$EDITOR"))).single().string("name"))
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
