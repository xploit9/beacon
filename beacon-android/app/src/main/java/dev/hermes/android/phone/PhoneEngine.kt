package dev.hermes.android.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.format.TextStyle
import java.util.Locale

private const val CALL = "phone_call"

/**
 * The rules of phone access, free of Android: which tool may run for whom, argument checks and bounds, approval, rate limits, the
 * activity log and the bounded result. The phone's providers sit behind [CalendarBackend], [ContactsBackend] and [ActionsBackend].
 */
internal class PhoneEngine(
    private val settings: PhoneSettings,
    private val calendar: CalendarBackend,
    private val contacts: ContactsBackend,
    private val actions: ActionsBackend,
    private val log: PhoneLog,
    private val limiter: PhoneRateLimiter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val locale: () -> Locale = { Locale.getDefault() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * True while the phone is locked: a tap that approves something counts only on an unlocked phone. Whether a live call or the assistant
     * overlay may use phone tools at all is the conversation's own trust (voice/CallTrust.kt), checked before a request gets here.
     */
    private val locked: () -> Boolean = { false },
) {
    fun enabledTools(origin: PhoneOrigin? = null): Set<String> {
        if (origin == PhoneOrigin.Hermes && !settings.hermesAllowed()) return emptySet()
        return PhoneSpecs.all.filter { usable(it.capability) }.map { it.name }.toSet()
    }

    /** On and allowed by Android. A switch whose permission was taken away is turned off for good, so allowing it again in Android never turns it back on. */
    private fun usable(capability: PhoneCapability): Boolean {
        if (!settings.enabled(capability)) return false
        if (settings.granted(capability)) return true
        settings.switchOff(capability)
        return false
    }

    /**
     * The one rule for the owner's tap (Settings › Assistant › Approvals). A phone call always waits (for the tap or the countdown,
     * see [PhoneCountdown.forHermes]). Any other change needs it unless the owner chose Never, in a chat, a call or the assistant alike:
     * they are one Hermes. A Hermes chat's read needs it while the choice is Always; calls and the assistant read freely.
     */
    fun needsApproval(name: String, origin: PhoneOrigin): Boolean = when {
        name == CALL -> true
        PhoneSpecs.isWrite(name) -> settings.askBeforeHermesActs()
        origin != PhoneOrigin.Hermes -> false
        else -> settings.askBeforeHermesReads()
    }

    /** Why [name] cannot run for [origin] right now, or null when it can. */
    fun unavailable(name: String, origin: PhoneOrigin): PhoneFailure? {
        val spec = PhoneSpecs.get(name) ?: return PhoneFailure("invalid", "Unknown phone tool.")
        if (origin == PhoneOrigin.Hermes && !settings.hermesAllowed()) return PhoneFailure("off", "Phone access isn't turned on for Hermes.")
        return capabilityFailure(spec.capability)
    }

    private fun capabilityFailure(capability: PhoneCapability): PhoneFailure? = when {
        !settings.enabled(capability) -> PhoneFailure("off", "${label(capability)} is turned off in Phone access.")
        !settings.granted(capability) -> { settings.switchOff(capability); PhoneFailure("permission", "Allow ${label(capability)} for Beacon in Android settings.") }
        else -> null
    }

    private fun label(capability: PhoneCapability) = when (capability) {
        PhoneCapability.Calendar -> "Calendar & reminders"
        PhoneCapability.Alarms -> "Alarms & timers"
        PhoneCapability.Contacts -> "Contacts"
        PhoneCapability.Calls -> "Phone calls"
        PhoneCapability.Messages -> "Messages"
    }

    /** One short line for notifications and cards, from the arguments alone (no provider is read). */
    fun describeQuick(name: String, args: JSONObject): String = runCatching { build(name, args, live = false).card.describe() }
        .getOrElse { PhoneLine(fallbackLine(name)).text() }

    fun fallbackLine(name: String): String = when (name) {
        "phone_calendar_create" -> "Add an event"
        "phone_calendar_update" -> "Change an event"
        "phone_calendar_delete" -> "Delete an event"
        "phone_reminder_create" -> "Add a reminder"
        "phone_alarm_set" -> "Set an alarm"
        "phone_timer_set" -> "Start a timer"
        "phone_call" -> "Place a call"
        "phone_message_draft" -> "Write a message"
        "phone_calendars", "phone_calendar_list" -> "Read your calendar"
        "phone_contacts_search" -> "Search your contacts"
        else -> "Phone request"
    }

    /** The one-line card with the names looked up, for a notification; no switch or origin is checked, and a failure reads as the quick line. */
    suspend fun describeLive(name: String, args: JSONObject): String = runCatching { withContext(io) { build(name, args, live = true) }.card.describe() }
        .getOrElse { if (it is CancellationException) throw it else describeQuick(name, args) }

    /**
     * Validates [args], looks up the names the card needs, and returns what will run with the card that shows it. This is the one card
     * for a live call, the overlay and Hermes. The arguments are checked before the phone is read, so a plain mistake is reported as it
     * is; once they pass, a change that cannot be shown (no such event, calendar or contact, or one that cannot be changed) gets one
     * neutral answer, so that nothing about what is on the phone is told before the owner taps.
     */
    suspend fun prepare(name: String, args: JSONObject, origin: PhoneOrigin): PhonePrepared {
        unavailable(name, origin)?.let { throw it }
        val write = PhoneSpecs.isWrite(name)
        // Cards the owner said no to count against the allowance, so a full allowance shows no further card.
        if (!limiter.wouldAllow(origin, write)) throw busy()
        return withContext(io) {
            build(name, args, live = false)
            try { build(name, args, live = true) }
            catch (failure: PhoneFailure) { throw if (write) PhoneFailure("not_found", "That isn't available on this phone.") else failure }
        }
    }

    suspend fun run(name: String, args: JSONObject, origin: PhoneOrigin, decision: PhoneDecision?): JSONObject {
        var line = PhoneLine(fallbackLine(name))
        return try {
            unavailable(name, origin)?.let { throw it }
            val spec = PhoneSpecs.get(name)!!
            if (!limiter.wouldAllow(origin, spec.write)) throw busy()
            // A change without the owner's tap is refused before anything on the phone is read: the reply then says nothing about
            // whether that event or contact exists. The log line comes from the arguments alone.
            val tapNeeded = needsApproval(name, origin)
            if (spec.write && tapNeeded && decision?.approved != true) {
                line = runCatching { build(name, args, live = false).card.lines.first() }.getOrDefault(line)
                throw refused(decision, origin, write = true)
            }
            val prepared = withContext(io) {
                if (!spec.write || tapNeeded) build(name, args, live = true)
                else {
                    // The owner chose not to be asked: a change that cannot be made still gets the one neutral answer, so a request
                    // cannot find out what is on the phone by trying.
                    line = build(name, args, live = false).card.lines.first()
                    try { build(name, args, live = true) } catch (failure: PhoneFailure) { throw PhoneFailure("not_found", "That isn't available on this phone.") }
                }
            }
            line = prepared.card.lines.first()
            execute(prepared, origin, decision)
        } catch (failure: PhoneFailure) {
            record(name, line, origin, failure.logged)
            failure.toJson()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (denied: SecurityException) {
            record(name, line, origin, "permission")
            PhoneFailure("permission", "Android didn't allow that.").toJson()
        } catch (other: Exception) {
            record(name, line, origin, "unavailable")
            PhoneFailure("unavailable", "The phone couldn't do that.").toJson()
        }
    }

    /** Runs exactly [prepared], the arguments its card showed. */
    suspend fun runPrepared(prepared: PhonePrepared, origin: PhoneOrigin, decision: PhoneDecision?): JSONObject {
        val line = prepared.card.lines.first()
        return try {
            unavailable(prepared.name, origin)?.let { throw it }
            execute(prepared, origin, decision)
        } catch (failure: PhoneFailure) {
            record(prepared.name, line, origin, failure.logged)
            failure.toJson()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (denied: SecurityException) {
            record(prepared.name, line, origin, "permission")
            PhoneFailure("permission", "Android didn't allow that.").toJson()
        } catch (other: Exception) {
            record(prepared.name, line, origin, "unavailable")
            PhoneFailure("unavailable", "The phone couldn't do that.").toJson()
        }
    }

    private suspend fun execute(prepared: PhonePrepared, origin: PhoneOrigin, decision: PhoneDecision?): JSONObject {
        val name = prepared.name
        val line = prepared.card.lines.first()
        if (needsApproval(name, origin)) {
            if (decision?.approved != true) throw refused(decision, origin, PhoneSpecs.isWrite(name))
            if (locked()) throw PhoneFailure("denied", "The phone is locked. Unlock it, then approve again.")
        } else if (PhoneSpecs.isWrite(name) && locked()) {
            // Nothing is changed on a locked phone, with or without a tap.
            throw PhoneFailure("unavailable", "The phone is locked. Unlock it, then ask again.", logged = "locked")
        }
        if (!limiter.tryAcquire(origin, PhoneSpecs.isWrite(name))) throw busy()
        // Once the action starts it finishes and is logged as done, even if the conversation that asked is cancelled meanwhile.
        val result = withContext(NonCancellable + io) { perform(prepared).also { prepared.performed = true; record(name, line, origin, "done") } }
        return bounded(result)
    }

    private fun busy() = PhoneFailure("busy", "Too many phone requests. Try again in a minute.")

    /**
     * Why a request that needs the owner's tap is not run: it had none, or the owner said no. A no takes its place in the allowance
     * like a yes, so a conversation cannot keep putting the same card in front of the owner.
     */
    private fun refused(decision: PhoneDecision?, origin: PhoneOrigin, write: Boolean): PhoneFailure {
        if (decision == null) return PhoneFailure("denied", "This needs the owner's tap on the screen first.")
        limiter.tryAcquire(origin, write)
        return PhoneFailure("denied", "The owner said no.")
    }

    /** Logs a result the engine did not produce itself, such as a card the owner denied or let expire. */
    fun record(name: String, line: PhoneLine, origin: PhoneOrigin, outcome: String) {
        runCatching { log.add(PhoneActivityEntry(clock(), origin, name.take(40), line, outcome)) }
    }

    /** Logs a card whose conversation ended before it was answered. An action that already started is logged done and nothing more. */
    fun recordCancelled(prepared: PhonePrepared, origin: PhoneOrigin) {
        if (!prepared.performed) record(prepared.name, prepared.card.lines.first(), origin, "cancelled")
    }

    // ---- building: validation, lookups, the card ----

    private class Built(val args: JSONObject, val card: PhoneCard)

    private fun build(name: String, raw: JSONObject, live: Boolean): PhonePrepared {
        if (PhoneSpecs.get(name) == null) throw PhoneFailure("invalid", "Unknown phone tool.")
        val built = when (name) {
            "phone_calendars" -> { PhoneArgs(raw, emptySet()); Built(JSONObject(), PhoneCard(listOf(PhoneLine("Read your calendars")))) }
            "phone_calendar_list" -> buildList(raw)
            "phone_contacts_search" -> buildSearch(raw)
            "phone_calendar_create" -> buildCreate(raw, live, reminder = false)
            "phone_reminder_create" -> buildCreate(raw, live, reminder = true)
            "phone_calendar_update" -> buildUpdate(raw, live)
            "phone_calendar_delete" -> buildDelete(raw, live)
            "phone_alarm_set" -> buildAlarm(raw)
            "phone_timer_set" -> buildTimer(raw)
            "phone_call" -> buildCall(raw, live)
            else -> buildMessage(raw, live)
        }
        return PhonePrepared(name, built.args, built.card)
    }

    private fun time(millis: Long) = PhoneTime.dayTime(millis, zone(), locale())
    private fun dayText(date: LocalDate) = PhoneTime.day(date, locale())
    /**
     * The span the phone will write, start and end as they are written: dates for an all-day event, else "Tue 4 Nov 3:00 PM – 4:00 PM"
     * (the end names its day only when it is another one). A card that showed the start alone would not show a 300-day event.
     */
    private fun whenText(startMillis: Long, endMillis: Long, allDay: Boolean): String = if (allDay) {
        val first = PhoneTime.utcDate(startMillis)
        val last = PhoneTime.utcDate(endMillis).minusDays(1).let { if (it < first) first else it }
        if (last == first) dayText(first) else dayText(first) + " – " + dayText(last)
    } else if (endMillis <= startMillis) time(startMillis)
    else time(startMillis) + " – " + if (PhoneTime.sameDay(startMillis, endMillis, zone())) PhoneTime.clock(endMillis, zone(), locale()) else time(endMillis)
    /** Text from a conversation or the phone on one card line: no control or invisible characters, no line breaks, clipped. */
    private fun shown(text: String, max: Int = PhoneText.LINE) = PhoneText.line(text, max)

    private fun buildList(raw: JSONObject): Built {
        val pa = PhoneArgs(raw, setOf("from", "to", "query", "limit"))
        val from = PhoneTime.instant(pa.text("from", 40, required = true)!!, zone())
        val to = PhoneTime.instant(pa.text("to", 40, required = true)!!, zone())
        if (!to.isAfter(from)) throw invalid("The end must be after the start.")
        if (Duration.between(from, to).toDays() > PhoneLimits.RANGE_DAYS) throw invalid("Ask for at most ${PhoneLimits.RANGE_DAYS} days at a time.")
        val query = pa.text("query", PhoneLimits.QUERY)
        val limit = pa.int("limit", 1..PhoneLimits.EVENTS_MAX) ?: PhoneLimits.EVENTS_DEFAULT
        val args = JSONObject().put("from", PhoneTime.iso(from.toEpochMilli(), zone())).put("to", PhoneTime.iso(to.toEpochMilli(), zone())).put("limit", limit)
        query?.let { args.put("query", it) }
        val lines = mutableListOf(PhoneLine("Read events {0} to {1}", listOf(time(from.toEpochMilli()), time(to.toEpochMilli()))))
        query?.let { lines += PhoneLine("Matching: {0}", listOf(shown(it, 60))) }
        return Built(args, PhoneCard(lines))
    }

    private fun buildSearch(raw: JSONObject): Built {
        val pa = PhoneArgs(raw, setOf("query", "limit"))
        val query = pa.text("query", PhoneLimits.QUERY, required = true)!!
        val limit = pa.int("limit", 1..PhoneLimits.CONTACTS_MAX) ?: PhoneLimits.CONTACTS_DEFAULT
        return Built(JSONObject().put("query", query).put("limit", limit), PhoneCard(listOf(PhoneLine("Search contacts for '{0}'", listOf(shown(query, 60))))))
    }

    private fun writableCalendar(id: Long?, live: Boolean): CalendarInfo? {
        if (!live) return null
        val all = calendar.calendars()
        if (id != null) {
            val found = all.firstOrNull { it.id == id } ?: throw PhoneFailure("not_found", "That calendar isn't on this phone.")
            if (!found.writable) throw invalid("That calendar can't take events.")
            return found
        }
        return all.firstOrNull { it.writable && it.primary } ?: all.firstOrNull { it.writable } ?: throw PhoneFailure("not_found", "No calendar on this phone can take events.")
    }

    private fun alertLine(minutes: List<Int>?): PhoneLine? = when {
        minutes == null -> null
        minutes.isEmpty() -> PhoneLine("No alerts")
        minutes == listOf(0) -> PhoneLine("Alert at the start")
        else -> PhoneLine("Alert {0} min before", listOf(minutes.joinToString(", ")))
    }

    private fun buildCreate(raw: JSONObject, live: Boolean, reminder: Boolean): Built {
        val pa = PhoneArgs(raw, if (reminder) setOf("title", "at", "notes", "alert_minutes_before", "calendar_id")
            else setOf("title", "start", "end", "all_day", "location", "notes", "calendar_id", "alert_minutes"))
        val title = pa.text("title", PhoneLimits.TITLE, required = true)!!
        val notes = pa.text("notes", PhoneLimits.NOTES, multiline = true)
        val calendarId = pa.id("calendar_id")
        val args = JSONObject().put("title", title)
        val span: TimeSpan
        val alerts: List<Int>
        if (reminder) {
            val at = PhoneTime.instant(pa.text("at", 40, required = true)!!, zone())
            val before = pa.int("alert_minutes_before", 0..PhoneLimits.ALERT_MAX_MINUTES) ?: 0
            span = TimeSpan(at.toEpochMilli(), at.plusSeconds(PhoneLimits.REMINDER_MINUTES * 60).toEpochMilli(), false)
            alerts = listOf(before)
        } else {
            val allDay = pa.bool("all_day") ?: false
            span = timeSpan(pa.text("start", 40, required = true)!!, pa.text("end", 40), allDay, PhoneLimits.DEFAULT_EVENT_MINUTES)
            alerts = pa.minutes("alert_minutes") ?: emptyList()
            pa.text("location", PhoneLimits.LOCATION)?.let { args.put("location", it) }
        }
        val target = writableCalendar(calendarId, live)
        args.put("all_day", span.allDay).put("start", span.startText(zone())).put("end", span.endText(zone()))
        notes?.let { args.put("notes", it) }
        (target?.id ?: calendarId)?.let { args.put("calendar_id", it.toString()) }
        args.put("alert_minutes", JSONArray(alerts))
        // A reminder's length is the phone's own, not something a request sets, so its card names the time alone.
        val whenText = if (reminder) time(span.startMillis) else whenText(span.startMillis, span.endMillis, span.allDay)
        val shownTitle = shown(title, PhoneLimits.TITLE)
        val head = when {
            reminder -> PhoneLine("Remind you: '{0}' {1}", listOf(shownTitle, whenText))
            target != null -> PhoneLine("Add '{0}' {1} to {2}", listOf(shownTitle, whenText, shown(target.name)))
            else -> PhoneLine("Add '{0}' {1}", listOf(shownTitle, whenText))
        }
        val lines = mutableListOf(head)
        if (reminder && target != null) lines += PhoneLine("In calendar: {0}", listOf(shown(target.name)))
        args.optString("location").takeIf { it.isNotBlank() }?.let { lines += PhoneLine("Place: {0}", listOf(shown(it, PhoneLimits.LOCATION))) }
        // An event with no alerts says nothing about them; a reminder always names its alert.
        if (reminder || alerts.isNotEmpty()) alertLine(alerts)?.let { lines += it }
        return Built(args, PhoneCard(lines, preview = notes.orEmpty()))
    }

    private data class TimeSpan(val startMillis: Long, val endMillis: Long, val allDay: Boolean) {
        fun startText(zone: ZoneId) = if (allDay) PhoneTime.utcDate(startMillis).toString() else PhoneTime.iso(startMillis, zone)
        fun endText(zone: ZoneId) = if (allDay) PhoneTime.utcDate(endMillis).minusDays(1).let { if (it < PhoneTime.utcDate(startMillis)) PhoneTime.utcDate(startMillis) else it }.toString()
            else PhoneTime.iso(endMillis, zone)
    }

    private fun timeSpan(startText: String, endText: String?, allDay: Boolean, defaultMinutes: Long): TimeSpan {
        if (allDay) {
            val first = PhoneTime.date(startText)
            val last = endText?.let { PhoneTime.date(it) } ?: first
            if (last < first) throw invalid("The end must be on or after the start.")
            if (ChronoUnit.DAYS.between(first, last) + 1 > PhoneLimits.RANGE_DAYS) throw invalid("An event can last at most ${PhoneLimits.RANGE_DAYS} days.")
            return TimeSpan(PhoneTime.utcMidnight(first), PhoneTime.utcMidnight(last.plusDays(1)), true)
        }
        val start = PhoneTime.instant(startText, zone())
        val end = endText?.let { PhoneTime.instant(it, zone()) } ?: start.plusSeconds(defaultMinutes * 60)
        if (!end.isAfter(start)) throw invalid("The end must be after the start.")
        if (Duration.between(start, end).toDays() > PhoneLimits.RANGE_DAYS) throw invalid("An event can last at most ${PhoneLimits.RANGE_DAYS} days.")
        return TimeSpan(start.toEpochMilli(), end.toEpochMilli(), false)
    }

    private fun existingEvent(id: Long, live: Boolean): EventInfo? {
        if (!live) return null
        val event = calendar.event(id) ?: throw PhoneFailure("not_found", "That event isn't on this phone.")
        val owner = calendar.calendars().firstOrNull { it.id == event.calendarId }
        if (owner != null && !owner.writable) throw invalid("That event is in a calendar that can't be changed.")
        return event.copy(calendarName = owner?.name.orEmpty())
    }

    private fun buildUpdate(raw: JSONObject, live: Boolean): Built {
        val pa = PhoneArgs(raw, setOf("id", "title", "start", "end", "all_day", "location", "notes", "alert_minutes"))
        val id = pa.id("id", required = true)!!
        val title = pa.text("title", PhoneLimits.TITLE)
        val location = pa.text("location", PhoneLimits.LOCATION, blankOk = true)
        val notes = pa.text("notes", PhoneLimits.NOTES, blankOk = true, multiline = true)
        val alerts = pa.minutes("alert_minutes")
        val startText = pa.text("start", 40)
        val endText = pa.text("end", 40)
        val allDayGiven = pa.bool("all_day")
        val timeGiven = startText != null || endText != null || allDayGiven != null
        if (title == null && location == null && notes == null && alerts == null && !timeGiven) throw invalid("Say what to change.")
        val old = existingEvent(id, live)
        val args = JSONObject().put("id", id.toString())
        title?.let { args.put("title", it) }
        location?.let { args.put("location", it) }
        notes?.let { args.put("notes", it) }
        alerts?.let { args.put("alert_minutes", JSONArray(it)) }
        var span: TimeSpan? = null
        if (timeGiven) {
            if (old?.repeating == true) throw invalid("A repeating event's time can't be changed here.")
            val allDay = allDayGiven ?: old?.allDay ?: false
            if (old != null && allDay != old.allDay && startText == null) throw invalid("Give a new start when you change all-day.")
            span = if (old == null) {
                // Without the stored event only what was given can be shown.
                if (startText != null) timeSpan(startText, endText, allDay, PhoneLimits.DEFAULT_EVENT_MINUTES) else null
            } else if (allDay) {
                val first = startText?.let { PhoneTime.date(it) } ?: PhoneTime.utcDate(old.startMillis)
                val oldLast = PhoneTime.utcDate(old.endMillis).minusDays(1).let { if (it < PhoneTime.utcDate(old.startMillis)) PhoneTime.utcDate(old.startMillis) else it }
                val last = endText?.let { PhoneTime.date(it) } ?: if (startText != null) first else oldLast
                timeSpan(first.toString(), last.toString(), true, PhoneLimits.DEFAULT_EVENT_MINUTES)
            } else {
                val oldMinutes = ((old.endMillis - old.startMillis) / 60_000L).takeIf { it > 0 && !old.allDay } ?: PhoneLimits.DEFAULT_EVENT_MINUTES
                val start = startText?.let { PhoneTime.instant(it, zone()).toEpochMilli() } ?: old.startMillis
                val endDefault = if (startText != null) java.time.Instant.ofEpochMilli(start).plusSeconds(oldMinutes * 60).toString() else java.time.Instant.ofEpochMilli(old.endMillis).toString()
                timeSpan(java.time.Instant.ofEpochMilli(start).toString(), endText ?: endDefault, false, oldMinutes)
            }
            span?.let { args.put("all_day", it.allDay).put("start", it.startText(zone())).put("end", it.endText(zone())) }
        }
        val head = if (old != null) PhoneLine("Change '{0}'", listOf(shown(old.title))) else PhoneLine("Change an event")
        val lines = mutableListOf(head)
        title?.let { lines += PhoneLine("Title: {0}", listOf(shown(it, PhoneLimits.TITLE))) }
        span?.let { lines += PhoneLine("When: {0}", listOf(whenText(it.startMillis, it.endMillis, it.allDay))) }
        location?.let { lines += if (it.isEmpty()) PhoneLine("No place") else PhoneLine("Place: {0}", listOf(shown(it, PhoneLimits.LOCATION))) }
        // New notes are shown whole under the lines, like the notes of a new event; an empty one clears them.
        if (notes != null && notes.isEmpty()) lines += PhoneLine("No notes")
        alertLine(alerts)?.let { lines += it }
        return Built(args, PhoneCard(lines, preview = notes.orEmpty()))
    }

    private fun buildDelete(raw: JSONObject, live: Boolean): Built {
        val pa = PhoneArgs(raw, setOf("id"))
        val id = pa.id("id", required = true)!!
        val event = existingEvent(id, live)
        val line = if (event != null) {
            PhoneLine("Delete '{0}' {1}", listOf(shown(event.title), whenText(event.startMillis, event.endMillis, event.allDay)))
        } else PhoneLine("Delete an event")
        val lines = mutableListOf(line)
        if (event?.repeating == true) lines += PhoneLine("Every repeat is deleted")
        return Built(JSONObject().put("id", id.toString()), PhoneCard(lines))
    }

    private fun buildAlarm(raw: JSONObject): Built {
        val pa = PhoneArgs(raw, setOf("hour", "minute", "label", "days"))
        val hour = pa.int("hour", 0..23, required = true)!!
        val minute = pa.int("minute", 0..59, required = true)!!
        val label = pa.text("label", PhoneLimits.LABEL)
        val days = pa.days("days")
        val args = JSONObject().put("hour", hour).put("minute", minute)
        label?.let { args.put("label", it) }
        days?.takeIf { it.isNotEmpty() }?.let { args.put("days", JSONArray(it)) }
        val lines = mutableListOf(PhoneLine("Set an alarm for {0}", listOf(PhoneTime.time(hour, minute, locale()))))
        if (!days.isNullOrEmpty()) lines += PhoneLine("Repeats: {0}", listOf(days.joinToString(", ") { PhoneArgs.dayOfWeek(it).getDisplayName(TextStyle.SHORT, locale()) }))
        label?.let { lines += PhoneLine("Label: {0}", listOf(shown(it, PhoneLimits.LABEL))) }
        return Built(args, PhoneCard(lines))
    }

    private fun buildTimer(raw: JSONObject): Built {
        val pa = PhoneArgs(raw, setOf("seconds", "label"))
        val seconds = pa.int("seconds", 1..86_400, required = true)!!
        val label = pa.text("label", PhoneLimits.LABEL)
        val args = JSONObject().put("seconds", seconds)
        label?.let { args.put("label", it) }
        val lines = mutableListOf(PhoneLine("Start a timer for {0}", listOf(length(seconds))))
        label?.let { lines += PhoneLine("Label: {0}", listOf(shown(it, PhoneLimits.LABEL))) }
        return Built(args, PhoneCard(lines))
    }

    private fun length(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(h.takeIf { it > 0 }?.let { "$it h" }, m.takeIf { it > 0 }?.let { "$it min" }, s.takeIf { it > 0 }?.let { "$it s" }).joinToString(" ")
    }

    /** The number as the card writes it: the phone's own spacing when that keeps every digit, else the number itself. */
    private fun numberDisplay(number: String): String = actions.format(number)?.takeIf { f -> f.filter { it.isDigit() } == number.filter { it.isDigit() } && f.none { it.isLetter() } } ?: number

    /**
     * [number] as a card shows it: kept left to right (between U+2066 and U+2069) wherever the card sits in a right-to-left text, so the
     * plus sign and the groups stay where they are written. Only what the owner reads carries the marks: the contract's one line and the
     * activity log keep the plain number.
     */
    private fun isolated(number: String): String = "\u2066$number\u2069"

    private fun contactsActive() = settings.enabled(PhoneCapability.Contacts) && settings.granted(PhoneCapability.Contacts)

    /**
     * The name of the contact who has exactly [number] saved, or null. The phone's lookup also offers look-alike numbers (one saved
     * without a country code matches the same digits with another), so a hit counts only when the saved number is the same number:
     * "Call Mom" on a card, and the countdown that goes with it, must never dial a different number.
     */
    private fun savedName(number: String): String? =
        contacts.matches(number).firstOrNull { PhoneNumbers.same(it.number, number) }?.let { PhoneText.line(it.name, 100) }?.ifBlank { null }

    private fun buildCall(raw: JSONObject, live: Boolean): Built {
        val pa = PhoneArgs(raw, setOf("number", "contact_id"))
        val numberText = pa.text("number", 40)
        val contactId = pa.id("contact_id")
        if ((numberText == null) == (contactId == null)) throw invalid("Give a number or a contact_id, not both.")
        var number: String? = numberText?.let(PhoneNumbers::normalize)
        var name: String? = null
        if (contactId != null) {
            capabilityFailure(PhoneCapability.Contacts)?.let { throw PhoneFailure(it.code, "Calling by contact needs Contacts. " + it.message) }
            if (live) {
                val contact = contacts.byId(contactId) ?: throw PhoneFailure("not_found", "That contact isn't on this phone.")
                val first = contact.phones.firstOrNull() ?: throw PhoneFailure("not_found", "That contact has no phone number.")
                number = PhoneNumbers.normalize(first.number)
                name = PhoneText.line(contact.name, 100).ifBlank { null }
            }
        } else if (live && contactsActive()) {
            name = savedName(number!!)
        }
        if (number == null) return Built(JSONObject().put("contact_id", contactId.toString()), PhoneCard(listOf(PhoneLine("Call a contact"))))
        val shown = numberDisplay(number)
        val dialer = PhoneNumbers.needsDialer(number, live && emergency(number))
        val extras = if (dialer) listOf(PhoneLine("Opens the dialer. You press call.")) else emptyList()
        val head = if (name != null) PhoneLine("Call {0} {1}", listOf(name, shown)) else PhoneLine("Call {0}", listOf(shown))
        val headline = if (name != null) PhoneLine("Call {0}", listOf(name)) else PhoneLine("Call {0}", listOf(isolated(shown)))
        return Built(JSONObject().put("number", number), PhoneCard(listOf(head) + extras, target = if (name != null) isolated(shown) else "", headline = headline,
            savedContact = name != null && !dialer))
    }

    /** The platform's emergency list is written without the international "+", so a number is checked both ways. */
    private fun emergency(number: String): Boolean = actions.isEmergency(number) || actions.isEmergency(number.removePrefix("+"))

    private fun buildMessage(raw: JSONObject, live: Boolean): Built {
        val pa = PhoneArgs(raw, setOf("to", "body"))
        val number = PhoneNumbers.normalize(pa.text("to", 40, required = true)!!)
        val body = pa.text("body", PhoneLimits.BODY, required = true, multiline = true)!!
        val name = if (live && contactsActive()) savedName(number) else null
        val shown = numberDisplay(number)
        val head = if (name != null) PhoneLine("Write to {0} {1}", listOf(name, shown)) else PhoneLine("Write to {0}", listOf(shown))
        val headline = if (name != null) PhoneLine("Write to {0}", listOf(name)) else PhoneLine("Write to {0}", listOf(isolated(shown)))
        return Built(JSONObject().put("to", number).put("body", body), PhoneCard(listOf(head, PhoneLine("Opens a draft. You press send.")),
            target = if (name != null) isolated(shown) else "", preview = body, headline = headline))
    }

    // ---- performing ----

    private fun perform(prepared: PhonePrepared): JSONObject {
        val a = prepared.args
        return when (prepared.name) {
            "phone_calendars" -> JSONObject().put("calendars", JSONArray().also { rows ->
                calendar.calendars().forEach { rows.put(JSONObject().put("id", it.id.toString()).put("name", it.name.take(100)).put("account", it.account.take(100)).put("writable", it.writable).put("primary", it.primary)) }
            })
            "phone_calendar_list" -> {
                val page = calendar.events(PhoneTime.instant(a.getString("from"), zone()).toEpochMilli(), PhoneTime.instant(a.getString("to"), zone()).toEpochMilli(),
                    a.optString("query").takeIf { it.isNotBlank() }, a.getInt("limit"))
                JSONObject().put("events", JSONArray().also { rows -> page.events.forEach { rows.put(eventJson(it)) } }).put("truncated", page.truncated)
            }
            "phone_contacts_search" -> {
                val found = contacts.search(a.getString("query"), a.getInt("limit"))
                JSONObject().put("contacts", JSONArray().also { rows ->
                    found.forEach { c ->
                        rows.put(JSONObject().put("id", c.id).put("name", c.name.take(100)).put("phones", JSONArray().also { phones ->
                            c.phones.take(PhoneLimits.PHONES_PER_CONTACT).forEach { phones.put(JSONObject().put("label", it.label.take(30)).put("number", it.number.take(40))) }
                        }))
                    }
                })
            }
            "phone_calendar_create", "phone_reminder_create" -> {
                val calendarId = a.optString("calendar_id").toLongOrNull() ?: writableCalendar(null, true)!!.id
                val allDay = a.optBoolean("all_day")
                val span = if (allDay) TimeSpan(PhoneTime.utcMidnight(LocalDate.parse(a.getString("start"))), PhoneTime.utcMidnight(LocalDate.parse(a.getString("end")).plusDays(1)), true)
                    else TimeSpan(PhoneTime.instant(a.getString("start"), zone()).toEpochMilli(), PhoneTime.instant(a.getString("end"), zone()).toEpochMilli(), false)
                val alerts = a.optJSONArray("alert_minutes")?.let { rows -> (0 until rows.length()).map { rows.getInt(it) } }.orEmpty()
                val id = calendar.insert(NewEvent(calendarId, a.getString("title"), span.startMillis, span.endMillis, span.allDay, if (span.allDay) "UTC" else zone().id,
                    a.optString("location").takeIf { it.isNotBlank() }, a.optString("notes").takeIf { it.isNotBlank() }, alerts))
                JSONObject().put("id", id.toString())
            }
            "phone_calendar_update" -> {
                val id = a.getString("id").toLong()
                val old = calendar.event(id) ?: throw PhoneFailure("not_found", "That event isn't on this phone.")
                if (old.repeating && (a.has("start") || a.has("end") || a.has("all_day"))) throw invalid("A repeating event's time can't be changed here.")
                val timed = a.has("start")
                val allDay = a.optBoolean("all_day", old.allDay)
                val span = if (!timed) null else if (allDay) TimeSpan(PhoneTime.utcMidnight(LocalDate.parse(a.getString("start"))), PhoneTime.utcMidnight(LocalDate.parse(a.getString("end")).plusDays(1)), true)
                    else TimeSpan(PhoneTime.instant(a.getString("start"), zone()).toEpochMilli(), PhoneTime.instant(a.getString("end"), zone()).toEpochMilli(), false)
                calendar.update(id, EventPatch(
                    title = a.optString("title").takeIf { a.has("title") }, startMillis = span?.startMillis, endMillis = span?.endMillis, allDay = span?.allDay,
                    timeZone = span?.let { if (it.allDay) "UTC" else zone().id }, location = a.optString("location").takeIf { a.has("location") },
                    notes = a.optString("notes").takeIf { a.has("notes") },
                    alertMinutes = a.optJSONArray("alert_minutes")?.let { rows -> (0 until rows.length()).map { rows.getInt(it) } },
                ))
                JSONObject().put("id", id.toString())
            }
            "phone_calendar_delete" -> {
                if (!calendar.delete(a.getString("id").toLong())) throw PhoneFailure("not_found", "That event isn't on this phone.")
                JSONObject().put("deleted", true)
            }
            "phone_alarm_set" -> {
                val days = a.optJSONArray("days")?.let { rows -> (0 until rows.length()).map { rows.getString(it) } }.orEmpty()
                if (!actions.setAlarm(a.getInt("hour"), a.getInt("minute"), a.optString("label").takeIf { it.isNotBlank() }, days)) throw noApp()
                JSONObject().put("set", true)
            }
            "phone_timer_set" -> {
                if (!actions.setTimer(a.getInt("seconds"), a.optString("label").takeIf { it.isNotBlank() })) throw noApp()
                JSONObject().put("set", true)
            }
            "phone_call" -> {
                val number = a.getString("number")
                val dialer = PhoneNumbers.needsDialer(number, emergency(number))
                if (!actions.dial(number, direct = !dialer)) throw noApp()
                if (dialer) JSONObject().put("placed", false).put("opened_dialer", true).put("message", "Opened the dialer; the owner presses call.")
                else JSONObject().put("placed", true)
            }
            else -> {
                if (!actions.draftMessage(a.getString("to"), a.getString("body"))) throw noApp()
                JSONObject().put("opened", true)
            }
        }
    }

    private fun noApp() = PhoneFailure("unavailable", "No app on this phone can do that.")

    private fun eventJson(e: EventInfo): JSONObject {
        val z = zone()
        val start = if (e.allDay) PhoneTime.utcDate(e.startMillis).toString() else PhoneTime.iso(e.startMillis, z)
        val end = if (e.allDay) PhoneTime.utcDate(e.endMillis).minusDays(1).let { if (it < PhoneTime.utcDate(e.startMillis)) PhoneTime.utcDate(e.startMillis) else it }.toString() else PhoneTime.iso(e.endMillis, z)
        return JSONObject().put("id", e.id.toString()).put("calendar_id", e.calendarId.toString()).put("title", e.title.take(PhoneLimits.TITLE))
            .put("start", start).put("end", end).put("all_day", e.allDay).put("location", e.location.take(PhoneLimits.LOCATION))
            .put("notes", e.notes.take(PhoneLimits.NOTES)).put("alert_minutes", JSONArray(e.alertMinutes.take(PhoneLimits.ALERTS)))
    }

    /** A result that fits [PhoneLimits.RESULT_CHARS]; a long list loses its last rows and says so. */
    private fun bounded(result: JSONObject): JSONObject {
        for (key in listOf("events", "contacts")) {
            val rows = result.optJSONArray(key) ?: continue
            var kept = rows.length()
            while (kept > 0 && result.toString().length > PhoneLimits.RESULT_CHARS) {
                kept--
                val trimmed = JSONArray()
                for (i in 0 until kept) trimmed.put(rows.get(i))
                result.put(key, trimmed)
                result.put("truncated", true)
            }
        }
        return result
    }
}
