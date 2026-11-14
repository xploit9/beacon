package dev.hermes.android.ui

import androidx.compose.ui.test.junit4.createComposeRule
import dev.hermes.android.phone.PhoneTime
import dev.hermes.android.tasks.ScheduleFormat
import dev.hermes.android.tasks.scheduleNextRun
import dev.hermes.android.tasks.scheduleTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * UX review P1-9 on the phone's own ICU: a screen shown in Arabic draws every date in Arabic, with the locale's own joiner. The test host keeps
 * the device in English (as the review's Bot Mode drawer capture did), so a date that followed the device and not the shown language fails here.
 */
class ArabicDatesUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anArabicScreenDrawsSchedulesTheDrawerAndTheApprovalTimeInArabicWithoutAnEnglishComma() {
        val shown = linkedMapOf<String, String>()
        compose.setContent {
            NativeLocalization("ar") {
                val locale = uiLocale()
                shown["locale"] = locale.language
                shown["schedule last run"] = scheduleTime("2026-11-12T12:01:00Z", "UTC", "", is24Hour = false, locale = locale)
                shown["schedule far next run"] = scheduleNextRun("2026-12-20T09:00:00Z", "", now = Instant.parse("2026-11-01T00:00:00Z"), zone = ZoneOffset.UTC, is24Hour = false, locale = locale)
                shown["schedule once"] = ScheduleFormat(false, locale).dayAndClock(LocalDateTime.of(2026, 11, 12, 15, 0))
                shown["bot mode space row"] = chatListTime(1_700_000_000_000L, now = 1_762_268_400_000L, zone = TimeZone.getTimeZone("UTC"), locale = locale)
                shown["phone approval time"] = PhoneTime.dayTime(1_762_268_400_000L, ZoneOffset.UTC, locale)
            }
        }
        compose.waitForIdle()
        assertEquals("the shown language decides, not the device's English", "ar", shown["locale"])
        for ((what, text) in shown) if (what != "locale") {
            assertFalse("$what has no English words (month, weekday, AM/PM): $text", Regex("[A-Za-z]{2,}").containsMatchIn(text))
            assertFalse("$what has no English \", \" joiner: $text", text.contains(", "))
        }
    }
}
