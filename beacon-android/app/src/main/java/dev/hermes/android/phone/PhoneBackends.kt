package dev.hermes.android.phone

/** The seams between the executor's rules and the phone's providers, so the rules are tested on the JVM and the providers on a device. */

internal data class CalendarInfo(val id: Long, val name: String, val account: String, val writable: Boolean, val primary: Boolean)

/** An event as the calendar stores it: an all-day event's [startMillis] and [endMillis] are UTC midnights and the end is the day after the last day. */
internal data class EventInfo(
    val id: Long, val calendarId: Long, val title: String, val startMillis: Long, val endMillis: Long, val allDay: Boolean,
    val location: String, val notes: String, val alertMinutes: List<Int>, val repeating: Boolean = false, val calendarName: String = "",
)

internal data class EventPage(val events: List<EventInfo>, val truncated: Boolean)

internal data class NewEvent(
    val calendarId: Long, val title: String, val startMillis: Long, val endMillis: Long, val allDay: Boolean, val timeZone: String,
    val location: String?, val notes: String?, val alertMinutes: List<Int>,
)

/** Only what changed is set; [alertMinutes] replaces all alerts when not null. */
internal data class EventPatch(
    val title: String? = null, val startMillis: Long? = null, val endMillis: Long? = null, val allDay: Boolean? = null, val timeZone: String? = null,
    val location: String? = null, val notes: String? = null, val alertMinutes: List<Int>? = null,
)

internal interface CalendarBackend {
    fun calendars(): List<CalendarInfo>
    fun events(fromMillis: Long, toMillis: Long, query: String?, limit: Int): EventPage
    fun event(id: Long): EventInfo?
    fun insert(event: NewEvent): Long
    fun update(id: Long, patch: EventPatch)
    fun delete(id: Long): Boolean
}

internal data class ContactPhone(val label: String, val number: String)
internal data class ContactInfo(val id: String, val name: String, val phones: List<ContactPhone>)

/** A contact the phone's lookup offered for a number, with the number it has saved. */
internal data class ContactMatch(val name: String, val number: String)

internal interface ContactsBackend {
    fun search(query: String, limit: Int): List<ContactInfo>
    fun byId(id: Long): ContactInfo?
    /**
     * The contacts the phone's own lookup finds for [number]. Android matches loosely (a number saved without its country code
     * matches the same number with another one), so whether a hit really is that number is for the executor to decide.
     */
    fun matches(number: String): List<ContactMatch>
}

internal interface ActionsBackend {
    /** Each returns false when no app on the phone handles the request. */
    fun setAlarm(hour: Int, minute: Int, label: String?, days: List<String>): Boolean
    fun setTimer(seconds: Int, label: String?): Boolean
    fun dial(number: String, direct: Boolean): Boolean
    fun draftMessage(number: String, body: String): Boolean
    /** Whether the platform calls [number] an emergency number. */
    fun isEmergency(number: String): Boolean
    /** [number] spaced the way the phone writes it, or null; the engine keeps it only when it holds the same digits. */
    fun format(number: String): String? = null
}
