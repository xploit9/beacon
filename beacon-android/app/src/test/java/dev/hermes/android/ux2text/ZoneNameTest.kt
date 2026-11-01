package dev.hermes.android.ux2text

import dev.hermes.android.core.json
import dev.hermes.android.tasks.ScheduleFormat
import dev.hermes.android.ui.scheduleCardClock
import dev.hermes.android.ui.scheduleZoneCity
import dev.hermes.android.ui.scheduleZoneName
import dev.hermes.android.ui.scheduleZoneNamed
import dev.hermes.android.ui.scheduleZoneNamer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** N-8: Hermes' zone is named in the app language (ICU's exemplar city), and falls back to the IANA city. */
class ZoneNameTest {
    private val arabic = mapOf("Europe/Lisbon" to "لشبونة", "Asia/Dubai" to "دبي")
    private val lisbon = "Europe/Lisbon"

    @Test fun theLocalizedExemplarCityWinsWhenThereIsOne() {
        assertEquals("لشبونة", scheduleZoneName(lisbon) { arabic[it] })
        assertEquals("دبي", scheduleZoneName("Asia/Dubai") { arabic[it] })
        assertEquals("Lisboa", scheduleZoneName(lisbon) { " Lisboa " })
    }

    @Test fun withoutOneTheIanaCityIsUsed() {
        assertEquals("Lisbon", scheduleZoneName(lisbon))
        assertEquals("Lisbon", scheduleZoneName(lisbon) { null })
        assertEquals("Lisbon", scheduleZoneName(lisbon) { "" })
        assertEquals("Lisbon", scheduleZoneName(lisbon) { "  " })
        assertEquals("Lisbon", scheduleZoneName(lisbon) { error("no zone names on this device") })
        assertEquals("Buenos Aires", scheduleZoneName("America/Argentina/Buenos_Aires") { null })
        assertEquals("UTC", scheduleZoneName("UTC") { null })
        assertEquals(scheduleZoneCity(lisbon), scheduleZoneName(lisbon))
    }

    @Test fun theCardsZoneLabelFollowsTheNamer() {
        val twelveHour = ScheduleFormat(false, Locale.US, { it }, dayPattern = "d MMM")
        val at = Instant.parse("2026-01-15T08:00:00Z")
        val rule = json("kind" to "cron", "expr" to "0 8 * * *")
        val english = scheduleCardClock(rule, "", lisbon, ZoneId.of("Asia/Dubai"), at, twelveHour)
        assertEquals("Lisbon", english.zoneLabel)
        val inArabic = scheduleCardClock(rule, "", lisbon, ZoneId.of("Asia/Dubai"), at, twelveHour) { scheduleZoneName(it) { id -> arabic[id] } }
        assertEquals("لشبونة", inArabic.zoneLabel)
        // Next, Last and the description name the zone through the phone-language templates.
        assertEquals("… (لشبونة)", scheduleZoneNamed("…", inArabic.zoneLabel, "{0} ({1})"))
        assertEquals("08:00 في لشبونة", scheduleZoneNamed("08:00", inArabic.zoneLabel, "{0} في {1}"))
        // A zone with the phone's own clock still names itself through the namer.
        val same = scheduleCardClock(rule, "", lisbon, ZoneId.of(lisbon), at, twelveHour) { scheduleZoneName(it) { id -> arabic[id] } }
        assertEquals("لشبونة", same.zoneLabel)
    }

    @Test fun theIcuNamerNeverFailsWhereThereIsNoIcu() {
        // The unit-test JVM has no Android ICU; the namer then reads the IANA city, as it does for any zone ICU has no name for.
        val namer = scheduleZoneNamer(Locale.forLanguageTag("ar"))
        assertEquals("Lisbon", namer(lisbon))
        assertEquals("UTC", namer("UTC"))
    }
}
