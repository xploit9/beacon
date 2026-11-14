package dev.hermes.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner-Hermes check of F9, finding K1: a command's report showed Hermes' raw Markdown in the notice, a long report (/context) filled a notice that
 * closed by itself, and a command picked from the list left "/status " in the composer.
 */
class CommandReportTest {
    @Test fun aShortOneLineReportStaysANoticeInPlainWords() {
        assertFalse(commandReportIsLong("Session title set: **Trip plan**"))
        assertEquals("Session title set: Trip plan", commandNoticeText("Session title set: **Trip plan**"))
        assertEquals("Model: gpt-6-luna", commandNoticeText("Model: `gpt-6-luna`"))
        assertEquals("Done", commandNoticeText("## Done"))
        assertEquals("Reasoning: high", commandNoticeText("Reasoning: __high__"))
    }

    @Test fun aReportLongerThanAboutTwoLinesOpensInTheDialog() {
        assertTrue(commandReportIsLong("**Context Window**\nUsed: 12,000 tokens"))
        assertTrue(commandReportIsLong("x".repeat(1100)))
        assertTrue(commandReportIsLong("y".repeat(CommandNoticeMaxChars + 1)))
        assertFalse(commandReportIsLong("z".repeat(CommandNoticeMaxChars)))
        assertFalse("surrounding blank lines are not a second line", commandReportIsLong("\nTitle set\n"))
    }

    @Test fun theComposerEmptiesAfterACommandThatRanEvenWithTheListsTrailingSpace() {
        assertTrue(commandRanClearsDraft("/status ", "/status"))
        assertTrue(commandRanClearsDraft("/status", "/status"))
        assertTrue(commandRanClearsDraft("  /title Trip  ", "/title Trip"))
        assertFalse("words typed after it are kept", commandRanClearsDraft("/status and more", "/status"))
        assertFalse("another draft is kept", commandRanClearsDraft("Hello", "/status"))
    }

    /** Review R29 Nice 2: a command passes a blank success notice, so a failed screen-copy save showed ". Refresh this page.". */
    @Test fun aBlankNoticeWithAFailedScreenSaveIsOnlyThePlainSentence() {
        assertEquals("Refresh this page.", refreshPageNotice(""))
        assertEquals("Refresh this page.", refreshPageNotice("  "))
        assertEquals("Saved. Refresh this page.", refreshPageNotice("Saved"))
        val locales = JSONObject(File(listOf(".", "app", "beacon-android/app").first { File(it, "src/main/assets/native-locales.json").isFile }, "src/main/assets/native-locales.json").readText())
        for (language in locales.keys()) {
            val shown = locales.getJSONObject(language).optString("refresh this page.")
            assertTrue("$language: $shown", shown.isNotBlank() && (language == "en" || shown != "Refresh this page."))
        }
    }
}
