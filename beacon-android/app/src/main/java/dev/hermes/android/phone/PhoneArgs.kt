package dev.hermes.android.phone

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun invalid(message: String) = PhoneFailure("invalid", message)

/** Bounds of the contract: every count and length a conversation can ask for. */
internal object PhoneLimits {
    const val TITLE = 200
    const val LOCATION = 200
    const val NOTES = 500
    const val LABEL = 100
    const val BODY = 1000
    const val QUERY = 100
    const val ID_DIGITS = 18
    const val ALERTS = 5
    const val ALERT_MAX_MINUTES = 40_320
    const val EVENTS_MAX = 50
    const val EVENTS_DEFAULT = 20
    const val CONTACTS_MAX = 10
    const val CONTACTS_DEFAULT = 5
    const val PHONES_PER_CONTACT = 5
    const val RANGE_DAYS = 366L
    const val RESULT_CHARS = 48_000
    const val REMINDER_MINUTES = 15L
    const val DEFAULT_EVENT_MINUTES = 60L
    const val NUMBER_MIN_DIGITS = 3
    const val NUMBER_MAX_DIGITS = 20
    val YEARS = 1971..2100
}

/** Times: ISO-8601 with an offset, or without one in the phone's own zone. All-day events use dates. */
internal object PhoneTime {
    fun instant(text: String, zone: ZoneId): Instant {
        val value = text.trim()
        if (value.isEmpty() || value.length > 40) throw invalid("That time isn't valid. Use a date and time like 2026-11-04T15:00.")
        val parsed = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value).atZone(zone).toInstant() }.getOrNull()
            ?: runCatching { LocalDate.parse(value).atStartOfDay(zone).toInstant() }.getOrNull()
            ?: throw invalid("That time isn't valid. Use a date and time like 2026-11-04T15:00.")
        return inRange(parsed)
    }

    /**
     * The calendar day of [text] as it is written: a date, or the date part of a date-time in the offset it carries. An all-day event
     * keeps the day the owner means, whatever time zone the phone or the text is in.
     */
    fun date(text: String): LocalDate {
        val value = text.trim()
        val day = runCatching { LocalDate.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value).toLocalDate() }.getOrNull()
            ?: throw invalid("That time isn't valid. Use a date and time like 2026-11-04T15:00.")
        return inRange(day)
    }

    private fun inRange(value: Instant): Instant {
        if (value.atZone(ZoneOffset.UTC).year !in PhoneLimits.YEARS) throw invalid("That date is out of range.")
        return value
    }
    private fun inRange(value: LocalDate): LocalDate {
        if (value.year !in PhoneLimits.YEARS) throw invalid("That date is out of range.")
        return value
    }

    fun iso(millis: Long, zone: ZoneId): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(zone).withNano(0))
    /** An all-day event's day as stored (UTC midnight). */
    fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
    fun utcMidnight(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    /** The calendar day of a Julian day number, which is how the Instances table names an all-day instance's days (1970-01-01 is 2440588). */
    fun julianDay(day: Int): LocalDate = LocalDate.ofEpochDay(day - 2_440_588L)
    /** An all-day instance given as its first and last Julian day, in the stored form of an all-day event: UTC midnight to the UTC midnight after the last day. */
    fun allDayInstance(firstDay: Int, lastDay: Int): Pair<Long, Long> =
        utcMidnight(julianDay(firstDay)) to utcMidnight(julianDay(maxOf(firstDay, lastDay)).plusDays(1))

    private fun clean(text: String) = text.replace(' ', ' ').replace(' ', ' ')
    fun day(date: LocalDate, locale: Locale): String = clean(DateTimeFormatter.ofPattern("EEE d MMM", locale).format(date))
    /** "Tue 4 Nov 3:00 PM", in the phone's language and clock style. */
    fun dayTime(millis: Long, zone: ZoneId, locale: Locale): String {
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        return clean(DateTimeFormatter.ofPattern("EEE d MMM", locale).format(at) + " " + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(at))
    }
    /** "3:00 PM", in the phone's language and clock style. */
    fun clock(millis: Long, zone: ZoneId, locale: Locale): String =
        clean(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(millis).atZone(zone)))
    /** Whether both moments fall on the same calendar day in [zone]. */
    fun sameDay(first: Long, second: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(first).atZone(zone).toLocalDate() == Instant.ofEpochMilli(second).atZone(zone).toLocalDate()
    fun time(hour: Int, minute: Int, locale: Locale): String =
        clean(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(java.time.LocalTime.of(hour, minute)))
}

/**
 * Text that came from a conversation or from the phone's providers (event titles, calendar and contact names), made safe to show on a
 * card and to store: control, line-break and invisible format characters (right-to-left overrides, zero-width spaces) are dropped.
 * A zero-width joiner or non-joiner stays only between two visible characters, where emoji and Persian or Indic words need it.
 */
internal object PhoneText {
    /** The longest a name from the phone shows on one card line. */
    const val LINE = 120
    private val hidden = setOf(Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SURROGATE).map { it.toInt() }.toSet()
    private fun joiner(cp: Int) = cp == 0x200C || cp == 0x200D
    private fun visible(cp: Int) = !Character.isWhitespace(cp) && Character.getType(cp) !in hidden

    /** [text] without the characters above; line breaks and tabs stay when [keepBreaks], else they become spaces. */
    fun plain(text: String, keepBreaks: Boolean = false): String {
        val cps = text.codePoints().toArray()
        val out = StringBuilder(text.length)
        for ((i, cp) in cps.withIndex()) {
            when {
                cp == '\n'.code || cp == '\t'.code -> out.append(if (keepBreaks) cp.toChar() else ' ')
                cp == 0x2028 || cp == 0x2029 -> out.append(if (keepBreaks) '\n' else ' ')
                cp == '\r'.code -> if (!keepBreaks) out.append(' ')
                joiner(cp) -> if (i > 0 && i < cps.lastIndex && visible(cps[i - 1]) && visible(cps[i + 1])) out.appendCodePoint(cp)
                Character.getType(cp) in hidden -> Unit
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    /** One short line: [plain], spaces collapsed, clipped to [max] characters with an ellipsis. */
    fun line(text: String, max: Int = LINE): String {
        val flat = plain(text).replace(Regex("\\s+"), " ").trim()
        if (flat.length <= max) return flat
        var cut = max - 1
        if (cut > 0 && Character.isHighSurrogate(flat[cut - 1])) cut--
        return flat.take(cut).trimEnd() + "…"
    }
}

/** Argument readers: unknown keys, wrong types and out-of-bound values are all refused, never coerced. */
internal class PhoneArgs(private val args: JSONObject, allowed: Set<String>) {
    init {
        val extra = args.keys().asSequence().filter { it !in allowed }.toList()
        if (extra.isNotEmpty()) throw invalid("Unexpected ${extra.first().take(40)}.")
    }
    fun has(key: String) = args.has(key) && !args.isNull(key)
    /** Text up to [max] characters without control or invisible format characters; only [multiline] text (notes, a message) keeps its line breaks. */
    fun text(key: String, max: Int, required: Boolean = false, blankOk: Boolean = false, multiline: Boolean = false): String? {
        if (!has(key)) { if (required) throw invalid("Missing $key."); return null }
        val value = args.get(key) as? String ?: throw invalid("$key must be text.")
        val cleaned = PhoneText.plain(value, keepBreaks = multiline).trim()
        if (cleaned.isEmpty() && !blankOk) { if (required) throw invalid("Missing $key."); return null }
        if (cleaned.length > max) throw invalid("$key is too long (up to $max characters).")
        return cleaned
    }
    fun int(key: String, range: IntRange, required: Boolean = false): Int? {
        if (!has(key)) { if (required) throw invalid("Missing $key."); return null }
        val raw = args.get(key)
        val value = when (raw) {
            is Int -> raw.toLong()
            is Long -> raw
            is Double -> raw.takeIf { it == Math.rint(it) && it.isFinite() }?.toLong()
            else -> null
        } ?: throw invalid("$key must be a whole number.")
        if (value !in range.first.toLong()..range.last.toLong()) throw invalid("$key must be from ${range.first} to ${range.last}.")
        return value.toInt()
    }
    fun bool(key: String): Boolean? {
        if (!has(key)) return null
        return args.get(key) as? Boolean ?: throw invalid("$key must be true or false.")
    }
    fun minutes(key: String): List<Int>? {
        if (!has(key)) return null
        val array = args.get(key) as? JSONArray ?: throw invalid("$key must be a list.")
        if (array.length() > PhoneLimits.ALERTS) throw invalid("At most ${PhoneLimits.ALERTS} alerts.")
        val out = linkedSetOf<Int>()
        for (i in 0 until array.length()) {
            val raw = array.get(i)
            val value = when (raw) { is Int -> raw.toLong(); is Long -> raw; is Double -> raw.takeIf { it == Math.rint(it) && it.isFinite() }?.toLong(); else -> null }
                ?: throw invalid("Alerts are whole minutes.")
            if (value !in 0..PhoneLimits.ALERT_MAX_MINUTES.toLong()) throw invalid("An alert is from 0 to ${PhoneLimits.ALERT_MAX_MINUTES} minutes before.")
            out += value.toInt()
        }
        return out.toList().sorted()
    }
    fun days(key: String): List<String>? {
        if (!has(key)) return null
        val array = args.get(key) as? JSONArray ?: throw invalid("$key must be a list.")
        if (array.length() > 7) throw invalid("At most 7 days.")
        val out = linkedSetOf<String>()
        for (i in 0 until array.length()) {
            val day = (array.get(i) as? String)?.trim()?.uppercase()?.takeIf { it in DAY_CODES } ?: throw invalid("Days are MO, TU, WE, TH, FR, SA, SU.")
            out += day
        }
        return DAY_CODES.filter { it in out }
    }
    /** A database row id: digits only, so nothing but a number reaches a query. */
    fun id(key: String, required: Boolean = false): Long? {
        if (!has(key)) { if (required) throw invalid("Missing $key."); return null }
        val raw = args.get(key)
        val text = when (raw) { is String -> raw.trim(); is Int -> raw.toString(); is Long -> raw.toString(); else -> throw invalid("$key must be an id.") }
        if (text.isEmpty() || text.length > PhoneLimits.ID_DIGITS || !text.all { it in '0'..'9' }) throw invalid("$key isn't a valid id.")
        return text.toLong()
    }
    companion object {
        val DAY_CODES = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
        fun dayOfWeek(code: String): DayOfWeek = DayOfWeek.of(DAY_CODES.indexOf(code) + 1)
    }
}

/** Phone numbers: an optional plus and digits only. Dial-string characters (pause, wait, star, hash) are refused. */
internal object PhoneNumbers {
    fun normalize(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 40) throw invalid("That number isn't valid.")
        if (text.any { !(it in '0'..'9' || it in " +-().") }) throw invalid("That number isn't valid.")
        val plus = text.startsWith("+")
        val digits = text.filter { it in '0'..'9' }
        if (text.count { it == '+' } > 1 || (text.contains('+') && !plus)) throw invalid("That number isn't valid.")
        if (digits.length !in PhoneLimits.NUMBER_MIN_DIGITS..PhoneLimits.NUMBER_MAX_DIGITS) throw invalid("That number isn't valid.")
        return (if (plus) "+" else "") + digits
    }

    /** Whether two numbers are the same number as written, ignoring spacing and punctuation: no loose or look-alike matching. */
    fun same(a: String, b: String): Boolean = runCatching { normalize(a) == normalize(b) }.getOrDefault(false)

    /**
     * Numbers a call must never place by itself: short service codes (three to five digits, with or without a leading +: no
     * international number is that short), or anything the platform calls an emergency number.
     */
    fun needsDialer(number: String, platformEmergency: Boolean): Boolean {
        val digits = number.count { it in '0'..'9' }
        return platformEmergency || digits <= 5
    }
}
