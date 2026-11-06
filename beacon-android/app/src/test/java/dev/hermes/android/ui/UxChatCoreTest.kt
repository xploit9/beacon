package dev.hermes.android.ui

import dev.hermes.android.tasks.scheduleTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** The chat core's UX fixes: the phone's own clock and day patterns, schedule wording and the daily chat menu. */
class UxChatCoreTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun String.plainSpace() = replace(Char(0x202F), ' ')

    // JVM unit tests run with isReturnDefaultValues, so the platform gives no pattern and today's English ones apply.
    @Test fun patternsFallBackToEnglishWhereThePlatformHasNone() {
        assertEquals("h:mm a", nativeClockPattern(Locale.US, is24Hour = false))
        assertEquals("HH:mm", nativeClockPattern(Locale.US, is24Hour = true))
        assertEquals("MMM d", nativeDayPattern(Locale.US, withYear = false, withWeekday = false))
        assertEquals("EEE, MMM d", nativeDayPattern(Locale.US, withYear = false, withWeekday = true))
        assertEquals("MMM d, yyyy", nativeDayPattern(Locale.US, withYear = true, withWeekday = false))
    }

    @Test fun timesFollowThePhonesTwelveOrTwentyFourHourSetting() {
        assertEquals("10:30 AM", messageTimeLabel(1_776_421_800L, utc, Locale.US)!!.plainSpace())
        assertEquals("10:30", messageTimeLabel(1_776_421_800L, utc, Locale.US, is24Hour = true))
        assertEquals("15:30", messageTimeLabel(1_776_421_800L, TimeZone.getTimeZone("Asia/Karachi"), Locale.US, is24Hour = true))
        val now = java.time.Instant.parse("2026-10-14T12:00:00Z").toEpochMilli()
        assertEquals("09:41", chatListTime(java.time.Instant.parse("2026-10-14T09:41:00Z").toEpochMilli(), now, utc, Locale.US, is24Hour = true))
    }

    @Test fun schedulesShowTwentyFourHourUnlessTheCallerPassesThePhoneSetting() {
        assertTrue(scheduleTime("2026-10-14T06:00:00Z", "Asia/Dubai", "none").endsWith("10:00"))
        assertTrue(scheduleTime("2026-10-14T06:00:00Z", "Asia/Dubai", "none", is24Hour = false, locale = Locale.US).plainSpace().endsWith("10:00 AM"))
    }

    @Test fun conversationActivityCountsOnlyWorkersAndProcesses() {
        val activity = BackgroundActivitySummary(agents = 2, processes = 1)
        assertEquals("2 agents · 1 process", backgroundActivityStatus(activity))
    }

    // Every text the background line translates is one whole template from BackgroundActivityStrings (the list the catalog test checks),
    // never a bare word like "schedule" that an old catalog entry could answer with another meaning.
    @Test fun theBackgroundLineAsksOnlyForItsListedTemplates() {
        val asked = mutableSetOf<String>()
        listOf(
            BackgroundActivitySummary(agents = 1, processes = 1, available = false),
            BackgroundActivitySummary(agents = 2, processes = 2),
            BackgroundActivitySummary(available = false),
        ).forEach { activity -> backgroundActivityStatus(activity) { text -> asked += text; text } }
        assertEquals(BackgroundActivityStrings.toSet(), asked)
        assertEquals("Prozesse: 3", backgroundActivityStatus(BackgroundActivitySummary(processes = 3)) { if (it == "{0} processes") "Prozesse: {0}" else it })
    }

    @Test fun countsKeepTheNumberInsideTheTemplate() {
        assertEquals("1 message", chatPreviewLine(JSONObject().put("message_count", 1), null))
        assertEquals("Nachrichten: 3", chatPreviewLine(JSONObject().put("message_count", 3), null, "Tú", manyMessages = "Nachrichten: {0}"))
    }

    @Test fun skipApprovalsIsTheOnlyNameForTheYoloMode() {
        assertEquals("Skip approvals", approvalChoiceLabel("yolo"))
        assertEquals("Manual", approvalChoiceLabel("manual"))
        assertEquals("Auto", approvalChoiceLabel("smart"))
    }

    @Test fun theChatMenuKeepsDailyActionsAndTucksTheRestUnderMore() {
        val daily = conversationActionGroups.values.flatten()
        assertTrue(daily.containsAll(listOf("New conversation", "Rename", "Pin", "Archive", "Branch", "Delete conversation")))
        assertTrue(conversationMoreActions.containsAll(listOf("Toolsets", "Undo last turn", "Usage")))
        assertTrue("Shortening the history stays under More, by either name", conversationMoreActions.any { it == "Compress context" || it == "Shorten history" })
        // The menu draws Delete last, apart from the daily groups, whichever group lists it.
        assertEquals(listOf("Delete conversation"), conversationMenuGroups(conversationActionGroups.values.toList()).destructive)
        assertTrue(conversationMenuGroups(conversationActionGroups.values.toList()).daily.flatten().none { it in destructiveConversationActions })
    }

    @Test fun unavailableKnownWorkStaysVisibleWithoutASpinner() {
        val unknown = BackgroundActivitySummary(available = false, items = listOf(BackgroundActivityItem("worker", "Background agent", "", "Status unknown")))
        assertTrue(unknown.active); assertFalse(unknown.working)
        for (running in listOf(BackgroundActivitySummary(agents = 1), BackgroundActivitySummary(processes = 1)))
            assertTrue(running.working)
    }
}
