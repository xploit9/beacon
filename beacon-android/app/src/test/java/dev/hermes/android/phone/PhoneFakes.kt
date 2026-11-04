package dev.hermes.android.phone

import kotlinx.coroutines.Dispatchers
import java.time.ZoneId
import java.util.Locale

/** In-memory stand-ins for the phone's providers and settings, so the executor's rules run on the JVM. */
internal class FakeSettings : PhoneSettings {
    val on = mutableSetOf<PhoneCapability>()
    val allowed = mutableSetOf<PhoneCapability>()
    var hermes = false
    var askReads = true
    var askActs = true
    override fun enabled(capability: PhoneCapability) = capability in on
    override fun granted(capability: PhoneCapability) = capability in allowed
    override fun switchOff(capability: PhoneCapability) { on -= capability }
    override fun hermesAllowed() = hermes
    override fun askBeforeHermesReads() = askReads
    override fun askBeforeHermesActs() = askActs
    fun everything() { on += PhoneCapability.entries; allowed += PhoneCapability.entries }
}

internal class FakeCalendar : CalendarBackend {
    val calendars = mutableListOf(CalendarInfo(1, "Personal", "Owner account", writable = true, primary = true), CalendarInfo(2, "Holidays", "holidays", writable = false, primary = false))
    val events = linkedMapOf<Long, EventInfo>()
    val inserted = mutableListOf<NewEvent>()
    val updated = mutableListOf<Pair<Long, EventPatch>>()
    val deleted = mutableListOf<Long>()
    var listQueries = 0
    private var next = 100L
    override fun calendars() = calendars.toList()
    override fun events(fromMillis: Long, toMillis: Long, query: String?, limit: Int): EventPage {
        listQueries++
        val hits = events.values.filter { it.startMillis in fromMillis until toMillis && (query == null || query.lowercase() in it.title.lowercase()) }.sortedBy { it.startMillis }
        return EventPage(hits.take(limit), hits.size > limit)
    }
    override fun event(id: Long) = events[id]
    override fun insert(event: NewEvent): Long {
        inserted += event
        val id = next++
        events[id] = EventInfo(id, event.calendarId, event.title, event.startMillis, event.endMillis, event.allDay, event.location.orEmpty(), event.notes.orEmpty(), event.alertMinutes)
        return id
    }
    override fun update(id: Long, patch: EventPatch) { updated += id to patch }
    override fun delete(id: Long): Boolean { deleted += id; return events.remove(id) != null }
}

internal class FakeContacts : ContactsBackend {
    val people = mutableListOf(
        ContactInfo("7", "Mona Test", listOf(ContactPhone("Mobile", "+966 50 123 4567"), ContactPhone("Home", "011 555 0100"))),
        ContactInfo("8", "Sam Example", listOf(ContactPhone("Mobile", "+1 555 010 2000"))),
        ContactInfo("9", "No Number", emptyList()),
    )
    override fun search(query: String, limit: Int) = people.filter { query.lowercase() in it.name.lowercase() }.take(limit)
    override fun byId(id: Long) = people.firstOrNull { it.id == id.toString() }
    /** Like Android's lookup, looser than "the same number": two numbers match when their last seven digits agree. */
    override fun matches(number: String): List<ContactMatch> {
        val tail = number.filter(Char::isDigit).takeLast(7)
        return people.flatMap { p -> p.phones.filter { it.number.filter(Char::isDigit).takeLast(7) == tail }.map { ContactMatch(p.name, it.number) } }
    }
}

internal class FakeActions : ActionsBackend {
    val alarms = mutableListOf<Triple<Int, Int, List<String>>>()
    val timers = mutableListOf<Int>()
    val dialed = mutableListOf<Pair<String, Boolean>>()
    val drafts = mutableListOf<Pair<String, String>>()
    var emergency = emptySet<String>()
    var handled = true
    /** Runs inside a timer, so a test can hold the action half-way. */
    var duringTimer: () -> Unit = {}
    override fun setAlarm(hour: Int, minute: Int, label: String?, days: List<String>): Boolean { if (handled) alarms += Triple(hour, minute, days); return handled }
    override fun setTimer(seconds: Int, label: String?): Boolean { duringTimer(); if (handled) timers += seconds; return handled }
    override fun dial(number: String, direct: Boolean): Boolean { if (handled) dialed += number to direct; return handled }
    override fun draftMessage(number: String, body: String): Boolean { if (handled) drafts += number to body; return handled }
    override fun isEmergency(number: String) = number in emergency
}

internal class FakeLog : PhoneLog {
    val entries = mutableListOf<PhoneActivityEntry>()
    override fun add(entry: PhoneActivityEntry) { entries += entry }
}

/** A whole executor on fakes, at a clock the test moves. 2025-11-04 12:00 UTC is a Tuesday. */
internal class Rig(zone: String = "UTC") {
    val settings = FakeSettings()
    val calendar = FakeCalendar()
    val contacts = FakeContacts()
    val actions = FakeActions()
    val log = FakeLog()
    var now = java.time.Instant.parse("2025-11-04T12:00:00Z").toEpochMilli()
    var locked = false
    val engine = PhoneEngine(settings, calendar, contacts, actions, log, PhoneRateLimiter({ now }), { now }, { ZoneId.of(zone) }, { Locale.US }, Dispatchers.Unconfined) { locked }
}

internal val Approved = PhoneDecision(true)
internal val Declined = PhoneDecision(false)
