package dev.hermes.android.phone

import dev.hermes.android.ui.PhoneAccessLabels
import dev.hermes.android.ui.PhoneAccessStrings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PhoneSupportTest {
    // ---- activity list ----

    private fun entry(n: Int, outcome: String = "done") = PhoneActivityEntry(n.toLong(), PhoneOrigin.Hermes, "phone_timer_set", PhoneLine("Start a timer for {0}", listOf("$n min")), outcome)

    @Test fun theActivityListKeepsTheLastTwoHundredNewestFirst() {
        val log = PhoneActivityLog(null)
        repeat(250) { log.add(entry(it)) }
        val recent = log.recent()
        assertEquals(PhoneActivityLog.LIMIT, recent.size)
        assertEquals(249L, recent.first().at)
        assertEquals(50L, recent.last().at)
    }

    @Test fun theActivityListSurvivesARestartAndClearEmptiesIt() {
        val dir = Files.createTempDirectory("phone-activity").toFile()
        try {
            val file = File(dir, "phone-activity.json")
            PhoneActivityLog(file).add(entry(1, "denied"))
            val reread = PhoneActivityLog(file).recent().single()
            assertEquals("denied", reread.outcome)
            assertEquals(PhoneOrigin.Hermes, reread.origin)
            assertEquals("Start a timer for 1 min", reread.line.text())
            PhoneActivityLog(file).clear()
            assertTrue(PhoneActivityLog(file).recent().isEmpty())
            assertFalse(file.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun anActivityLineIsBounded() {
        val log = PhoneActivityLog(null)
        log.add(PhoneActivityEntry(1, PhoneOrigin.LiveCall, "t", PhoneLine("x".repeat(5000), List(20) { "y".repeat(5000) }), "z".repeat(500)))
        val kept = log.recent().single()
        assertTrue(kept.line.template.length <= 240 && kept.line.args.size <= 6 && kept.line.args.all { it.length <= 240 } && kept.outcome.length <= 24)
    }

    // ---- rate limiter ----

    @Test fun theLimiterCountsReadsAndWritesSeparatelyPerOriginAndSlides() {
        var now = 0L
        val limiter = PhoneRateLimiter({ now })
        repeat(30) { assertTrue(limiter.tryAcquire(PhoneOrigin.Hermes, false)) }
        assertFalse(limiter.tryAcquire(PhoneOrigin.Hermes, false))
        assertTrue(limiter.tryAcquire(PhoneOrigin.Hermes, true))
        assertTrue(limiter.tryAcquire(PhoneOrigin.LiveCall, false))
        repeat(9) { assertTrue(limiter.tryAcquire(PhoneOrigin.Hermes, true)) }
        assertFalse(limiter.tryAcquire(PhoneOrigin.Hermes, true))
        assertFalse(limiter.wouldAllow(PhoneOrigin.Hermes, true))
        now = 60_001
        assertTrue(limiter.wouldAllow(PhoneOrigin.Hermes, true) && limiter.tryAcquire(PhoneOrigin.Hermes, false))
    }

    // ---- numbers and times ----

    @Test fun numbersKeepOnlyDigitsAndAPlusAndRefuseDialStrings() {
        assertEquals("+966501234567", PhoneNumbers.normalize(" +966 (50) 123-4567 "))
        assertEquals("0501234567", PhoneNumbers.normalize("050.123.4567"))
        for (bad in listOf("", "12", "+", "1+2345", "++966501234567", "123,456", "123;456", "*21#", "tel:123456", "12345678901234567890123", "٠٥٠١٢٣٤٥٦٧")) {
            try { PhoneNumbers.normalize(bad); throw AssertionError("'$bad' must be refused") } catch (_: PhoneFailure) { }
        }
    }

    @Test fun shortAndEmergencyNumbersNeedTheDialer() {
        for (number in listOf("911", "112", "999", "997", "10000")) assertTrue(number, PhoneNumbers.needsDialer(number, false))
        assertTrue(PhoneNumbers.needsDialer("0501234567", true))
        assertFalse(PhoneNumbers.needsDialer("0501234567", false))
        // A leading + does not make a short code a full number.
        for (number in listOf("+112", "+911", "+999", "+1234", "+12345")) assertTrue(number, PhoneNumbers.needsDialer(number, false))
        assertFalse(PhoneNumbers.needsDialer("+966501234567", false))
        assertFalse(PhoneNumbers.needsDialer("123456", false))
    }

    @Test fun twoNumbersAreTheSameOnlyWhenTheyAreWrittenTheSameIgnoringSpacing() {
        assertTrue(PhoneNumbers.same("+966 50 123 4567", "+966501234567"))
        assertTrue(PhoneNumbers.same("(050) 123-4567", "0501234567"))
        assertFalse("a country code makes it another number", PhoneNumbers.same("0501234567", "+966501234567"))
        assertFalse(PhoneNumbers.same("+990501234567", "0501234567"))
        assertFalse(PhoneNumbers.same("0501234567", "05012345678"))
        assertFalse("not a number at all", PhoneNumbers.same("*21#", "*21#"))
        assertFalse(PhoneNumbers.same("", ""))
    }

    // ---- text from outside ----

    @Test fun invisibleAndBidirectionalCharactersAreDroppedAndBreaksBecomeSpaces() {
        assertEquals("Dentist", PhoneText.line("Den‮tist"))
        assertEquals("ab", PhoneText.line("a​b⁠﻿⁦⁩"))
        assertEquals("a b c", PhoneText.line("a\nb\r\n c"))
        assertEquals("a b", PhoneText.line("a b"))
        assertEquals("a b", PhoneText.line("a\u0007 \u0000b"))
        assertEquals("", PhoneText.line("‮​"))
        // Notes and messages keep their own line breaks and nothing else.
        assertEquals("a\nb\tc", PhoneText.plain("a‮\r\nb\tc​", keepBreaks = true))
    }

    @Test fun joinersStayInsideWordsAndEmojiButNotAtTheEdges() {
        val persian = "می‌خواهم"
        assertEquals(persian, PhoneText.line(persian))
        val family = "👨‍👩"
        assertEquals(family, PhoneText.line(family))
        assertEquals("ab", PhoneText.line("‍a‌‍b‌"))
        assertEquals("a b", PhoneText.line("a ‍ b"))
    }

    @Test fun aLongNameIsClippedWithAnEllipsisWithoutSplittingAnEmoji() {
        val long = PhoneText.line("x".repeat(300))
        assertEquals(PhoneText.LINE, long.length)
        assertTrue(long.endsWith("…"))
        val emoji = PhoneText.line("😀".repeat(100))
        assertTrue(emoji.length <= PhoneText.LINE && emoji.endsWith("…") && !emoji.dropLast(1).last().isHighSurrogate())
        assertEquals("short", PhoneText.line("  short  "))
    }

    // ---- all-day events ----

    @Test fun anAllDayInstanceFromTheCalendarIsStoredAtUtcMidnightWhateverTheZone() {
        assertEquals(java.time.LocalDate.of(1970, 1, 1), PhoneTime.julianDay(2_440_588))
        assertEquals(java.time.LocalDate.of(2000, 1, 1), PhoneTime.julianDay(2_451_545))
        val nov10 = (java.time.LocalDate.of(2025, 11, 10).toEpochDay() + 2_440_588L).toInt()
        val (start, end) = PhoneTime.allDayInstance(nov10, nov10 + 1)
        assertEquals(PhoneTime.utcMidnight(java.time.LocalDate.of(2025, 11, 10)), start)
        assertEquals(PhoneTime.utcMidnight(java.time.LocalDate.of(2025, 11, 12)), end)
        assertEquals("a one-day event", PhoneTime.utcMidnight(java.time.LocalDate.of(2025, 11, 11)), PhoneTime.allDayInstance(nov10, nov10).second)
        assertEquals("an end before the start is one day", PhoneTime.utcMidnight(java.time.LocalDate.of(2025, 11, 11)), PhoneTime.allDayInstance(nov10, nov10 - 1).second)
        // A day as written, in any zone or offset.
        for (text in listOf("2025-11-10", "2025-11-10T00:00:00+03:00", "2025-11-10T23:59:00-11:00", "2025-11-10T12:00:00Z", "2025-11-10T08:30")) {
            assertEquals(text, java.time.LocalDate.of(2025, 11, 10), PhoneTime.date(text))
        }
        try { PhoneTime.date("tomorrow"); throw AssertionError("a word is not a date") } catch (_: PhoneFailure) { }
    }

    // ---- translations ----

    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })

    private fun norm(text: String) = text.lowercase().replace("…", "...").replace(Regex("\\s+"), " ").trim()

    @Test fun everyCardLineTheExecutorCanWriteIsTranslatedInEveryLanguage() {
        val source = File(app, "src/main/java/dev/hermes/android/phone/PhoneEngine.kt").readText()
        val quoted = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        val templates = Regex("PhoneLine\\(\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(source).map { it.groupValues[1] }.toList()
        val fallbacks = source.substringAfter("fun fallbackLine").substringBefore("/** Validates").let { block -> Regex("-> \"((?:[^\"\\\\]|\\\\.)*)\"").findAll(block).map { it.groupValues[1] }.toList() }
        val found = (templates + fallbacks).distinct()
        assertTrue("Found only ${found.size} card lines, so the scan is broken.", found.size > 30)
        val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText())
        val missing = catalog.keys().asSequence().filter { it != "en" }.flatMap { language ->
            val strings = catalog.getJSONObject(language)
            found.filter { strings.optString(norm(it)).isBlank() }.map { "$language \"$it\"" }
        }.toList()
        assertTrue("These phone card lines show in English in another language:\n" + missing.joinToString("\n"), missing.isEmpty())
        assertTrue(quoted.containsMatchIn("\"x\""))
    }

    @Test fun cardLinesKeepTheirHolesInEveryLanguage() {
        val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText())
        val source = File(app, "src/main/java/dev/hermes/android/phone/PhoneEngine.kt").readText()
        val templates = Regex("PhoneLine\\(\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(source).map { it.groupValues[1] }.filter { it.contains("{") }.distinct().toList()
        val holes = Regex("\\{\\d}")
        val broken = catalog.keys().asSequence().filter { it != "en" }.flatMap { language ->
            val strings = catalog.getJSONObject(language)
            templates.filter { holes.findAll(it).map { m -> m.value }.toSet() != holes.findAll(strings.optString(norm(it))).map { m -> m.value }.toSet() }.map { "$language \"$it\"" }
        }.toList()
        assertTrue("These translations lost or changed a {0} hole:\n" + broken.joinToString("\n"), broken.isEmpty())
    }

    @Test fun thePageWordsAreTranslatedInEveryLanguage() {
        val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText())
        val words = (PhoneAccessStrings + PhoneAccessLabels).distinct()
        val missing = catalog.keys().asSequence().filter { it != "en" }.flatMap { language ->
            words.filter { catalog.getJSONObject(language).optString(norm(it)).isBlank() }.map { "$language \"$it\"" }
        }.toList()
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
    }
}
