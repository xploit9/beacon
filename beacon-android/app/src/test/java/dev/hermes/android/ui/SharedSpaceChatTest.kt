package dev.hermes.android.ui

import dev.hermes.android.data.BotModeEvent
import dev.hermes.android.data.BotModeMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedSpaceChatTest {
    private val texts = TypingTexts("{0} bots", "{0} is typing…", "{0} and {1} are typing…", "{0} bots are typing…")
    private val roster = listOf(BotModeMember("coder", "coder", "coder"), BotModeMember("academic", "academic", "academic"), BotModeMember("default", "default", "hermes"))

    @Test fun theHeaderNamesWhoIsTypingForZeroOneTwoAndMoreBots() {
        assertEquals("3 bots", typingSubtitle(emptyList(), 3, texts))
        assertEquals("academic is typing…", typingSubtitle(listOf("academic"), 3, texts))
        assertEquals("academic and coder are typing…", typingSubtitle(listOf("academic", "coder"), 3, texts))
        assertEquals("4 bots are typing…", typingSubtitle(listOf("a", "b", "c", "d"), 6, texts))
        assertEquals("", typingSubtitle(emptyList(), 0, texts))
    }

    private fun message(id: String, speaker: String, failed: Boolean = false) = BotModeEvent(id, speaker, "text $id", failed)

    @Test fun aRunIsMarkedAtItsFirstAndLastMessage() {
        val rows = spaceRows(listOf(message("1", "coder"), message("2", "coder"), message("3", "coder"), message("4", "academic"), message("5", "You"), message("6", "You")), emptyMap())
            .filterIsInstance<SpaceMessageRow>()
        assertEquals(listOf(true, false, false, true, true, false), rows.map { it.first })
        assertEquals(listOf(false, false, true, true, false, true), rows.map { it.last })
    }

    @Test fun aDayDividerOrAFailureEndsTheRun() {
        val rows = spaceRows(listOf(message("1", "coder"), message("2", "coder"), message("3", "coder", failed = true), message("4", "coder")), mapOf("2" to "Today"))
        assertEquals(listOf("event:1", "day:2", "event:2", "event:3", "event:4"), rows.map { it.key })
        val messages = rows.filterIsInstance<SpaceMessageRow>()
        assertEquals(listOf(true, true, true), messages.map { it.first })
        assertEquals(listOf(true, true, true), messages.map { it.last })
        assertTrue(rows[3] is SpaceNoticeRow)
    }

    @Test fun mentionsAreFoundByHermesRulesButAnAddressIsNot() {
        val text = "ping @coder, mail a@b.com, then @all and @@x and @academic."
        assertEquals(listOf("coder", "all", "academic."), mentionSpans(text).map { it.handle })
        val first = mentionSpans(text).first()
        assertEquals("@coder", text.substring(first.start, first.end))
        assertTrue(mentionSpans("no mention here, just a@b.com").isEmpty())
    }

    @Test fun onlyAHandleHermesWouldRouteCountsAsAMention() {
        val handles = mentionHandles(roster)
        assertEquals(setOf("coder", "academic", "hermes", "all", "everyone", "user"), handles)
        // "@academic." names no bot for Hermes (the dot belongs to the handle), so it is not styled; "@Coder" is the same bot.
        assertEquals(listOf("coder", "Coder"), mentionSpans("@coder, @Coder, @academic.").filter { it.handle.lowercase() in handles }.map { it.handle })
    }

    @Test fun theChipsFollowTheMentionBeingTyped() {
        assertEquals(listOf("coder", "academic", "hermes", "all"), mentionChips(roster, null))
        assertEquals(listOf("academic", "all"), mentionChips(roster, "a"))
        assertEquals(listOf("coder"), mentionChips(roster, "CO"))
        assertEquals(emptyList<String>(), mentionChips(roster, "zz"))
        assertEquals("", mentionQuery("hello @"))
        assertEquals("ac", mentionQuery("hello @ac"))
        assertNull(mentionQuery("hello @ac and"))
        assertNull(mentionQuery("mail a@b"))
        // A mention ended by a new line is finished: no chips, and a chip tap never rewrites the line above.
        assertNull(mentionQuery("hello @ac\n"))
        assertEquals("thanks @coder\n@academic " to 24, insertMention("thanks @coder\n", 14, 14, "academic"))
    }

    @Test fun aChipInsertsItsHandleAtTheCursorAndReplacesAHalfTypedOne() {
        assertEquals("@coder " to 7, insertMention("", 0, 0, "coder"))
        assertEquals("Plan this @coder " to 17, insertMention("Plan this", 9, 9, "coder"))
        assertEquals("Plan @academic please" to 14, insertMention("Plan @ac please", 8, 8, "academic"))
        assertEquals("hi @coder " to 10, insertMention("hi @co", 6, 6, "coder"))
        assertFalse(insertMention("a", 1, 1, "all").first.contains("  "))
    }

    @Test fun aBotIsNamedByItsRoomNameThenItsProfile() {
        val named = listOf(BotModeMember("m1", "coder", "coder", "Code Wizard"), BotModeMember("m2", "academic", "academic"))
        assertEquals("Code Wizard", spaceMemberTitle("m1", named, emptyList()))
        assertEquals("academic", spaceMemberTitle("m2", named, emptyList()))
        assertEquals("academic", spaceMemberTitle("academic", named, emptyList()))
        assertEquals("ghost", spaceMemberTitle("ghost", named, emptyList()))
    }
}
