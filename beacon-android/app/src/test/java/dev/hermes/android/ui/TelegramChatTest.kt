package dev.hermes.android.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import dev.hermes.android.Message
import dev.hermes.android.core.json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** The Telegram-style chat surfaces: bubble grouping, plain text, header line, avatars, chat-list previews, times and unread marks. */
class TelegramChatTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val lock = String(Character.toChars(0x1F512))
    private fun message(id: String, role: String, text: String = "text") = Message(id, role, text)
    private fun corners(shape: RoundedCornerShape) = listOf(shape.topStart, shape.topEnd, shape.bottomEnd, shape.bottomStart).map { it.toPx(Size(200f, 200f), Density(1f)) }

    @Test fun consecutiveMessagesOfOneSenderFormOneRunWithTheTailOnItsLastBubble() {
        val groups = bubbleGroups(listOf(message("u1", "user"), message("a1", "assistant"), message("a2", "assistant"), message("a3", "assistant"), message("u2", "user")), emptySet())
        assertEquals(BubbleGroup(startsRun = true, joinedTop = false, tail = true), groups.getValue("u1"))
        assertEquals(BubbleGroup(startsRun = true, joinedTop = false, tail = false), groups.getValue("a1"))
        assertEquals(BubbleGroup(startsRun = false, joinedTop = true, tail = false), groups.getValue("a2"))
        assertEquals(BubbleGroup(startsRun = false, joinedTop = true, tail = true), groups.getValue("a3"))
        assertEquals(BubbleGroup(startsRun = true, joinedTop = false, tail = true), groups.getValue("u2"))
    }

    @Test fun aNewDayOrACallTranscriptEndsTheRunEvenForTheSameSender() {
        val messages = listOf(message("a1", "assistant"), message("a2", "assistant"), message("call:1", "assistant"), message("a3", "assistant"), message("a4", "assistant"))
        val groups = bubbleGroups(messages, dayStartIds = setOf("a2"), standaloneIds = setOf("call:1"))
        assertEquals(BubbleGroup(true, false, true), groups.getValue("a1"))
        assertEquals(BubbleGroup(true, false, true), groups.getValue("a2"))
        assertEquals(BubbleGroup(true, false, true), groups.getValue("call:1"))
        assertEquals(BubbleGroup(true, false, false), groups.getValue("a3"))
        assertEquals(BubbleGroup(false, true, true), groups.getValue("a4"))
    }

    @Test fun aToolOnlyRoundStaysInItsRunButTakesNoCornerOrTail() {
        val tools = listOf(JSONObject().put("name", "terminal"))
        val groups = bubbleGroups(listOf(message("a1", "assistant"), Message("a2", "assistant", "", tools = tools), message("a3", "assistant"), Message("a4", "assistant", "", tools = tools)), emptySet())
        assertEquals(BubbleGroup(true, false, false), groups.getValue("a1"))
        assertEquals(BubbleGroup(false, false, false), groups.getValue("a2"))
        assertEquals(BubbleGroup(false, true, true), groups.getValue("a3"))
        assertEquals(BubbleGroup(false, false, false), groups.getValue("a4"))
    }

    @Test fun bubbleCornersAreRoundOnTheFarSideAndTightWhereBubblesMeet() {
        assertEquals(listOf(18f, 18f, 4f, 18f), corners(bubbleShape(own = true, joinedTop = false, tail = true)))
        assertEquals(listOf(18f, 6f, 6f, 18f), corners(bubbleShape(own = true, joinedTop = true, tail = false)))
        assertEquals(listOf(18f, 18f, 18f, 4f), corners(bubbleShape(own = false, joinedTop = false, tail = true)))
        assertEquals(listOf(6f, 18f, 18f, 6f), corners(bubbleShape(own = false, joinedTop = true, tail = false)))
    }

    @Test fun onlyPlainWordsShrinkTheBubbleToTheirWidth() {
        listOf("Done.", "Thanks, see you at 5", "Okay – that works (for me)").forEach { assertTrue(it, isPlainChatText(it)) }
        listOf("**bold**", "snake_case", "see https://example.com", "- one", "1. one", "line one\nline two", "a [link](x)", "`code`", "# Heading", "MEDIA:/tmp/a.png", "x".repeat(241)).forEach { assertFalse(it, isPlainChatText(it)) }
    }

    @Test fun theHeaderLineShowsWorkWhileATurnStreamsElseTheCallOrTheModel() {
        assertEquals("thinking…", chatHeaderSubtitle(streaming = true, hasReplyText = false, inCall = true, model = "gpt", assistantChat = true))
        assertEquals("typing…", chatHeaderSubtitle(streaming = true, hasReplyText = true, inCall = false, model = "gpt", assistantChat = false))
        assertEquals("Voice conversation", chatHeaderSubtitle(streaming = false, hasReplyText = false, inCall = true, model = "gpt", assistantChat = true))
        assertEquals("gpt", chatHeaderSubtitle(streaming = false, hasReplyText = false, inCall = false, model = "gpt", assistantChat = true))
        assertEquals("Text and voice, together", chatHeaderSubtitle(streaming = false, hasReplyText = false, inCall = false, model = "", assistantChat = true))
        assertEquals("", chatHeaderSubtitle(streaming = false, hasReplyText = false, inCall = false, model = "", assistantChat = false))
    }

    @Test fun anEmojiTitleGivesAnEmojiAvatarAndOtherTitlesTheirInitialOnAColourFixedByTheChatId() {
        assertEquals(lock, leadingEmoji("$lock Security news"))
        assertEquals(lock, leadingEmoji("   $lock Security news"))
        assertNull(leadingEmoji("Security news"))
        assertNull(leadingEmoji(""))
        assertEquals(lock, chatAvatarSpec("$lock Security news", "chat-1").emoji)
        val plain = chatAvatarSpec("cyber news", "chat-1")
        assertNull(plain.emoji)
        assertEquals("C", plain.initial)
        assertEquals("Colour follows the chat id, not the title", plain.color, chatAvatarSpec("Renamed", "chat-1").color)
        assertEquals("#", chatAvatarSpec("  ?? ", "chat-2").initial)
        assertEquals("É", chatAvatarSpec("élan", "chat-3").initial)
        assertEquals((0..40).map { chatAvatarSpec("t", "chat-$it").color }, (0..40).map { chatAvatarSpec("t", "chat-$it").color })
        assertTrue("Chats spread over more than one colour", (0..40).map { chatAvatarSpec("t", "chat-$it").color }.toSet().size > 1)
    }

    @Test fun aThreadTitleIsTheOptionalEmojiThenTheNameAndItsAvatarDropsTheEmojiFromTheText() {
        assertEquals("$lock Cyber news", threadTitle(lock, "  Cyber news "))
        assertEquals("Trip", threadTitle("", " Trip "))
        assertEquals(lock, leadingEmoji(threadTitle(lock, "News")))
        assertEquals("Cyber news", plainTitle("$lock Cyber news"))
        assertEquals("Plain", plainTitle("Plain"))
        assertEquals("An emoji alone stays whole", lock, plainTitle(lock))
        assertEquals(12, threadEmojis.size)
        assertEquals(threadEmojis.size, threadEmojis.toSet().size)
        assertTrue(threadEmojis.all { leadingEmoji(it) != null })
    }

    @Test fun viewingAChatKeepsItsLineAndMovesTheSeenMarkForwardOnly() {
        val first = ChatGlances().viewed("default", "c1", "hello", "assistant", at = 1_000, seen = 2_000)
        assertEquals(ChatGlance(seen = 2_000, preview = "hello", role = "assistant", at = 1_000), first.rows.getValue("default:c1"))
        assertSame("Nothing new changes nothing", first, first.viewed("default", "c1", "", "", at = 0, seen = 1_500))
        val later = first.viewed("default", "c1", "thanks", "user", at = 3_000, seen = 3_000)
        assertEquals(ChatGlance(seen = 3_000, preview = "thanks", role = "user", at = 3_000), later.rows.getValue("default:c1"))
        assertEquals("A blank line keeps the known one", "thanks", later.viewed("default", "c1", "", "", at = 0, seen = 4_000).rows.getValue("default:c1").preview)
        assertEquals(setOf("default:c1"), later.viewed("default", "c1", "", "", 0, 4_000).rows.keys)
    }

    @Test fun glancesAreStoredPerProfileBoundedAndAnUnreadableRowIsNone() {
        val glances = ChatGlances(
            account = "a", base = mapOf("default" to 5_000L, "coder" to 9L),
            rows = mapOf("default:c1" to ChatGlance(10, "hi", "user", 9), "coder:c2" to ChatGlance(1, "x", "assistant", 1)),
        )
        val decoded = requireNotNull(decodeGlances("default", glances.encodeFor("default")))
        assertEquals(mapOf("default" to 5_000L), decoded.base)
        assertEquals(mapOf("default:c1" to ChatGlance(10, "hi", "user", 9)), decoded.rows)
        assertTrue(requireNotNull(decodeGlances("coder", glances.copy(base = emptyMap()).encodeFor("coder"))).base.isEmpty())
        assertNull(decodeGlances("default", "not json"))
        assertEquals(ChatGlances(), decodeGlances("default", "{}"))
        val many = ChatGlances(rows = (1..400).associate { "default:c$it" to ChatGlance(seen = it.toLong()) })
        assertEquals(GLANCE_ROWS_MAX, requireNotNull(decodeGlances("default", many.encodeFor("default"))).rows.size)
        assertEquals("Stored values only fill gaps", ChatGlance(seen = 7), ChatGlances(rows = mapOf("default:c1" to ChatGlance(seen = 7))).merged(ChatGlances(rows = mapOf("default:c1" to ChatGlance(seen = 1), "default:c2" to ChatGlance(seen = 2)))).rows.getValue("default:c1"))
        assertNotNull(ChatGlances().withBase("default", 3).base["default"])
        assertEquals("The first baseline stays", 3L, ChatGlances().withBase("default", 3).withBase("default", 9).base["default"])
    }

    @Test fun aChatIsUnreadWhenSomethingNewArrivedAfterTheOwnerLastLooked() {
        val seenAt = ChatGlance(seen = 2_000, preview = "ok", role = "assistant", at = 2_000)
        assertFalse("The open chat is never unread", chatUnread(9_000, seenAt, baselineMs = 1_000, open = true))
        assertFalse("No time, no news", chatUnread(0, seenAt, baselineMs = 1_000, open = false))
        assertTrue(chatUnread(2_500, seenAt, baselineMs = 1_000, open = false))
        assertFalse(chatUnread(2_000, seenAt, baselineMs = 1_000, open = false))
        assertTrue("A chat never opened here counts against the baseline", chatUnread(5_000, null, baselineMs = 4_000, open = false))
        assertFalse("A first install is not all unread", chatUnread(3_000, null, baselineMs = 4_000, open = false))
        assertFalse("Nothing is unread until the baseline is known", chatUnread(5_000, null, baselineMs = Long.MAX_VALUE, open = false))
        val ownLast = ChatGlance(seen = 1_000, preview = "thanks", role = "user", at = 2_000)
        assertFalse("The owner's own latest message is not news", chatUnread(3_000, ownLast, baselineMs = 0, open = false))
        assertTrue("Anything newer than what this phone knows is", chatUnread(9_000, ownLast, baselineMs = 0, open = false))
    }

    @Test fun theListKeepsWhatTheNewestBubbleShowsAndWhoItIsFrom() {
        val post = dev.hermes.android.Message("p", "user", "[Cron delivery: News]\nThree new advisories.", displayKind = "cron_delivery")
        assertEquals("Three new advisories." to "assistant", chatListLine(post))
        val stopped = dev.hermes.android.Message("s", "assistant", "Operation interrupted: the model stopped", interrupted = true)
        assertEquals("Stopped" to "assistant", chatListLine(stopped))
        assertEquals("Hello" to "user", chatListLine(dev.hermes.android.Message("u", "user", "Hello")))
        assertEquals("Here it is: chart.png" to "assistant", chatListLine(dev.hermes.android.Message("m", "assistant", "Here it is: MEDIA:C:\\Users\\Owner\\out\\chart.png")))
        assertEquals("" to "", chatListLine(null))
        assertEquals("Saved: chart final.png", chatPreviewText("Saved: MEDIA:\"C:\\Users\\Jane Doe\\out\\chart final.png\""))
        assertEquals("See y.png", chatPreviewText("See media:/home/x/y.png"))
        assertEquals("Ordinary words stay", "Big on social media: news daily", chatPreviewText("Big on social media: news daily"))
    }

    @Test fun aScheduledPostReadsAsThePostInTheListNeverAsTheOwners() {
        assertEquals("Three new advisories.", chatPreviewText("[Cron delivery: Cybersecurity news]\nThree new advisories."))
        assertEquals("[not a label] text", chatPreviewText("[not a label] text"))
        assertEquals("An attached file shows by name, never by its path", "Please check this image: Thanks",
            chatPreviewText("Please check this image:\n@image:C:\\SyntheticOwner\\Documents\\photo.png\nThanks"))
        val row = json("title" to "Cybersecurity news", "preview" to "[Cron delivery: Cybersecurity news]\nThree new advisories.", "message_count" to 1)
        assertEquals("Three new advisories.", chatPreviewLine(row, null))
    }

    @Test fun theListLineIsTheNewestKnownMessageElseHermesPreviewUnlessItOnlyRepeatsTheTitle() {
        val row = json("title" to "Trip", "preview" to "Compare hotels", "message_count" to 4)
        assertEquals("Hello", chatPreviewLine(row, ChatGlance(preview = "Hello", role = "assistant")))
        assertEquals("You: Hello", chatPreviewLine(row, ChatGlance(preview = "Hello", role = "user")))
        assertEquals("Du: Hello", chatPreviewLine(row, ChatGlance(preview = "Hello", role = "user"), you = "Du"))
        assertEquals("Compare hotels", chatPreviewLine(row, null))
        assertEquals("4 messages", chatPreviewLine(json("title" to "Compare the plans", "preview" to "Compare the plans", "message_count" to 4), null))
        assertEquals("4 messages", chatPreviewLine(json("title" to "Compare the two plans in detail", "preview" to "Compare the two plans...", "message_count" to 4), null))
        assertEquals("1 message", chatPreviewLine(json("title" to "A", "preview" to "", "message_count" to 1), null))
        assertEquals("", chatPreviewLine(json("title" to "A"), null))
    }

    @Test fun aPreviewHasNoMarkdownMarksOrLineBreaksAndIsBounded() {
        assertEquals("Bold and link and code", chatPreviewText("**Bold** and [link](https://a.b) and `code`"))
        assertEquals("Title one quote", chatPreviewText("# Title\n- one\n> quote"))
        assertEquals(GLANCE_PREVIEW_MAX, chatPreviewText("x".repeat(500)).length)
        assertEquals("", chatPreviewText("  \n "))
    }

    @Test fun theListTimeIsTheClockTodayTheWeekdayThisWeekThenTheDate() {
        val now = java.time.Instant.parse("2026-10-26T12:00:00Z").toEpochMilli()
        // Newer JDKs put a narrow no-break space before AM/PM.
        fun at(iso: String) = chatListTime(java.time.Instant.parse(iso).toEpochMilli(), now, utc, Locale.US).replace(Char(0x202F), ' ')
        assertEquals("9:41 AM", at("2026-10-26T09:41:00Z"))
        assertEquals("Sun", at("2026-10-25T09:41:00Z"))
        assertEquals("Tue", at("2026-10-20T10:00:00Z"))
        assertEquals("Oct 19", at("2026-10-19T10:00:00Z"))
        assertEquals("Dec 30, 2025", at("2025-12-30T10:00:00Z"))
        assertEquals("", chatListTime(0, now, utc, Locale.US))
    }

    @Test fun aScheduledResultFiledInAThreadReadsAsAPostNotAsTheOwnersMessage() {
        val text = "[Cron delivery: Cyber news]\nTop story: patch Tuesday.\n\nSecond line"
        assertEquals(ChannelPost("Cyber news", "Top story: patch Tuesday.\n\nSecond line"), channelPost("cron_delivery", "user", text))
        assertEquals(ChannelPost("", "No label here"), channelPost("cron_delivery", "user", "No label here"))
        assertEquals(ChannelPost("Job", ""), channelPost("cron_delivery", "user", "[Cron delivery: Job]"))
        assertNull("Only the cron_delivery kind is a post", channelPost("", "user", text))
        assertNull(channelPost("model_switch", "user", text))
        assertNull(channelPost("cron_delivery", "assistant", text))
        val post = Message("p1", "user", text, displayKind = "cron_delivery")
        assertFalse(isOwnMessage(post))
        assertTrue(isOwnMessage(message("u1", "user")))
        // A post sits on the assistant's side, in the assistant's run.
        val groups = bubbleGroups(listOf(message("u1", "user"), post, message("a1", "assistant")), emptySet())
        assertEquals(BubbleGroup(true, false, true), groups.getValue("u1"))
        assertEquals(BubbleGroup(true, false, false), groups.getValue("p1"))
        assertEquals(BubbleGroup(false, true, true), groups.getValue("a1"))
        assertEquals("cron_delivery", Message.from(json("id" to "p2", "role" to "user", "text" to text, "display_kind" to "cron_delivery"), 0).displayKind)
    }

    @Test fun aRowsActivityIsItsNewestRealTime() {
        assertEquals(1_790_000_000_000L, rowActivityMillis(json("last_active" to 1_790_000_000.5, "started_at" to 1_789_000_000)))
        assertEquals("Milliseconds are kept", 1_790_000_000_000L, rowActivityMillis(json("last_active" to 1_790_000_000_000L)))
        assertEquals(1_789_000_000_000L, rowActivityMillis(json("started_at" to 1_789_000_000)))
        assertEquals("A tiny placeholder is no time", 0L, rowActivityMillis(json("last_active" to 2)))
        assertEquals(0L, rowActivityMillis(JSONObject()))
    }
}
