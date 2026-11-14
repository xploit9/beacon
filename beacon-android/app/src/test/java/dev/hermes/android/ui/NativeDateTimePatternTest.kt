package dev.hermes.android.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Every date and time the app draws comes from the locale it is shown in. The UX review (P1-9) found a fixed ", " between a date and its time
 * (Arabic read "12 نوفمبر ,2026") and a date in English patterns beside Arabic words. The platform owns the pattern, the joiner included.
 */
class NativeDateTimePatternTest {
    private val ar = Locale.forLanguageTag("ar")

    @Test fun theLocaleOwnsTheJoinerBetweenTheDayAndTheClock() {
        val asked = mutableListOf<String>()
        val pattern = nativeDateTimePattern(ar, is24Hour = false, withYear = true, withWeekday = false) { _, skeleton -> asked += skeleton; "d MMM y، h:mm a" }
        assertEquals("d MMM y، h:mm a", pattern)
        assertEquals("one skeleton for the day and the clock together, so the platform picks the joiner", listOf("yMMMdhm"), asked)
    }

    @Test fun theWeekdayYearAndClockStyleChangeTheSkeletonAndNothingElse() {
        val asked = mutableListOf<String>()
        fun ask(is24Hour: Boolean, withYear: Boolean, withWeekday: Boolean) { nativeDateTimePattern(ar, is24Hour, withYear, withWeekday) { _, skeleton -> asked += skeleton; null } }
        ask(true, withYear = false, withWeekday = false); ask(false, withYear = false, withWeekday = true); ask(true, withYear = true, withWeekday = true)
        assertEquals(listOf("MMMdHm", "EEEMMMdhm", "EEEyMMMdHm"), asked)
    }

    @Test fun aPlatformWithNoPatternKeepsTodaysEnglishOneWhichIsOnlyTheJvmTestPath() {
        assertEquals("MMM d, yyyy, h:mm a", nativeDateTimePattern(Locale.US, is24Hour = false, withYear = true, withWeekday = false) { _, _ -> null })
        assertEquals("MMM d, HH:mm", nativeDateTimePattern(Locale.US, is24Hour = true, withYear = false, withWeekday = false) { _, _ -> null })
        assertEquals("a blank answer is no answer", "EEE, MMM d, h:mm a", nativeDateTimePattern(Locale.US, is24Hour = false, withYear = false, withWeekday = true) { _, _ -> " " })
    }

    @Test fun theShownLanguageDecidesTheDateLocaleAndTheDeviceKeepsItsRegionWhenItIsThatLanguage() {
        assertSame("the device's own English keeps its region (12 Nov, not Nov 12)", Locale.UK, uiLocaleFor("en", Locale.UK))
        assertEquals("a shown language the device is not set to wins, so an Arabic screen never draws English dates", "ar", uiLocaleFor("ar", Locale.US).language)
        assertSame("no language is no override", Locale.UK, uiLocaleFor("", Locale.UK))
        assertSame(Locale.TAIWAN, uiLocaleFor("zh-Hant", Locale.TAIWAN))
        assertEquals("zh-Hant", uiLocaleFor("zh-Hant", Locale.US).toLanguageTag())
    }
}
