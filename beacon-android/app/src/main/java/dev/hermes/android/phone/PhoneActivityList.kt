package dev.hermes.android.phone

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * What Settings > Phone access > Activity shows, free of Android: the search and filters, the day groups and the page. The log itself
 * (PhoneActivityLog) keeps its storage; this only decides which of its entries are listed and how they are grouped.
 */

/** How an entry ended, in the three groups the Result filter offers. */
internal enum class PhoneResult { Done, SaidNo, DidntRun }

/** "done" is Done, "denied" is the owner's no, and everything else (expired, cancelled, switched off, refused, failed) did not run. */
internal fun phoneResultOf(outcome: String): PhoneResult = when (outcome) {
    "done" -> PhoneResult.Done
    "denied" -> PhoneResult.SaidNo
    else -> PhoneResult.DidntRun
}

/** The capability whose switch the entry's tool belongs to; null for a tool this version does not know. */
internal fun PhoneActivityEntry.capability(): PhoneCapability? = PhoneSpecs.get(tool)?.capability

/** The search text and the three choices; a null choice means all. */
internal data class PhoneActivityFilter(
    val query: String = "",
    val capability: PhoneCapability? = null,
    val result: PhoneResult? = null,
    val origin: PhoneOrigin? = null,
) {
    /** Anything narrows the list. */
    val active: Boolean get() = query.isNotBlank() || capability != null || result != null || origin != null
}

/**
 * The entries of [entries] that pass [filter], newest first. The search matches the words of the action as the owner reads them,
 * [lineText] being the line in the phone's language: every word typed must appear in it, whatever the case.
 */
internal fun filterActivity(entries: List<PhoneActivityEntry>, filter: PhoneActivityFilter, lineText: (PhoneLine) -> String): List<PhoneActivityEntry> {
    val words = filter.query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return entries.sortedByDescending { it.at }.filter { entry ->
        (filter.capability == null || entry.capability() == filter.capability) &&
            (filter.result == null || phoneResultOf(entry.outcome) == filter.result) &&
            (filter.origin == null || entry.origin == filter.origin) &&
            (words.isEmpty() || lineText(entry.line).lowercase().let { text -> words.all { it in text } })
    }
}

/** One day of the list: its entries are newest first. */
internal data class PhoneActivityDay(val day: LocalDate, val entries: List<PhoneActivityEntry>)

/** [entries] (newest first) cut into the days they happened on, in [zone]; a day's group holds its entries in the same order. */
internal fun groupActivityByDay(entries: List<PhoneActivityEntry>, zone: ZoneId): List<PhoneActivityDay> {
    val days = ArrayList<PhoneActivityDay>()
    for (entry in entries) {
        val day = Instant.ofEpochMilli(entry.at).atZone(zone).toLocalDate()
        val last = days.lastOrNull()
        if (last != null && last.day == day) days[days.lastIndex] = last.copy(entries = last.entries + entry) else days += PhoneActivityDay(day, listOf(entry))
    }
    return days
}

/** What a day's heading reads: today, yesterday, or its date. */
internal enum class PhoneDayName { Today, Yesterday, Date }

internal fun phoneDayName(day: LocalDate, today: LocalDate): PhoneDayName = when (day) {
    today -> PhoneDayName.Today
    today.minusDays(1) -> PhoneDayName.Yesterday
    else -> PhoneDayName.Date
}

/** The list shows this many rows first, and this many more each time the owner asks for more. */
internal const val ACTIVITY_PAGE = 20

/** How many rows tall the Activity list is at most; past that it scrolls inside itself. */
internal const val ACTIVITY_VISIBLE_ROWS = 10

/** The first [pages] pages of [entries]: [ACTIVITY_PAGE] rows each, so the list never runs on without the owner asking. */
internal fun activityPage(entries: List<PhoneActivityEntry>, pages: Int): List<PhoneActivityEntry> = entries.take(ACTIVITY_PAGE * pages.coerceAtLeast(1))
