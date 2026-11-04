package dev.hermes.android.phone

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import androidx.core.content.ContextCompat
import java.util.Calendar

/** The phone's calendar provider. Every query is bounded and parameterised; ids reach it as numbers only. */
internal class CalendarProviderBackend(context: Context) : CalendarBackend {
    private val resolver = context.applicationContext.contentResolver

    override fun calendars(): List<CalendarInfo> {
        val rows = ArrayList<CalendarInfo>()
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
        resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, "${CalendarContract.Calendars.VISIBLE} = 1", null, "${CalendarContract.Calendars._ID} ASC")?.use { c ->
            while (c.moveToNext() && rows.size < 50) {
                val account = c.getString(2).orEmpty()
                rows += CalendarInfo(c.getLong(0), c.getString(1).orEmpty(), account, c.getInt(4) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                    primary = account.isNotEmpty() && c.getString(3) == account)
            }
        }
        return rows
    }

    private fun alerts(ids: Collection<Long>): Map<Long, List<Int>> {
        if (ids.isEmpty()) return emptyMap()
        val found = HashMap<Long, MutableList<Int>>()
        val marks = ids.joinToString(",") { "?" }
        resolver.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            "${CalendarContract.Reminders.EVENT_ID} IN ($marks)", ids.map { it.toString() }.toTypedArray(), null)?.use { c ->
            while (c.moveToNext()) {
                val method = c.getInt(2)
                if (method != CalendarContract.Reminders.METHOD_ALERT && method != CalendarContract.Reminders.METHOD_DEFAULT) continue
                // MINUTES_DEFAULT (-1) means the calendar's own default; it has no fixed number to report.
                val minutes = c.getInt(1).takeIf { it >= 0 } ?: continue
                found.getOrPut(c.getLong(0)) { ArrayList() } += minutes
            }
        }
        return found.mapValues { (_, v) -> v.distinct().sorted() }
    }

    override fun events(fromMillis: Long, toMillis: Long, query: String?, limit: Int): EventPage {
        val projection = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.START_DAY, CalendarContract.Instances.END_DAY)
        val cursor = if (query.isNullOrBlank()) CalendarContract.Instances.query(resolver, projection, fromMillis, toMillis)
            else CalendarContract.Instances.query(resolver, projection, fromMillis, toMillis, query)
        val rows = ArrayList<EventInfo>()
        var more = false
        cursor?.use { c ->
            while (c.moveToNext()) {
                if (rows.size >= limit) { more = true; break }
                val allDay = c.getInt(5) == 1
                // The Instances table gives an all-day instance's BEGIN and END at local midnight, while an all-day event is stored at UTC
                // midnight; its first and last day (Julian days, in the phone's own zone) are the dates the owner sees, so rows follow
                // the stored form and the executor never shifts a day in a zone ahead of UTC.
                val (begin, end) = if (allDay) PhoneTime.allDayInstance(c.getInt(8), c.getInt(9)) else c.getLong(3) to c.getLong(4)
                rows += EventInfo(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), begin, end, allDay,
                    c.getString(6).orEmpty(), c.getString(7).orEmpty().take(PhoneLimits.NOTES), emptyList())
            }
        }
        val withAlerts = alerts(rows.map { it.id }.toSet())
        return EventPage(rows.map { it.copy(alertMinutes = withAlerts[it.id].orEmpty()) }, more)
    }

    override fun event(id: Long): EventInfo? {
        val projection = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND, CalendarContract.Events.ALL_DAY, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.RRULE, CalendarContract.Events.RDATE, CalendarContract.Events.DURATION, CalendarContract.Events.DELETED)
        resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), projection, null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.getInt(11) == 1) return null
            val start = c.getLong(3)
            val end = if (c.isNull(4)) start + durationMillis(c.getString(10)) else c.getLong(4)
            val repeating = !c.getString(8).isNullOrBlank() || !c.getString(9).isNullOrBlank()
            return EventInfo(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), start, end, c.getInt(5) == 1, c.getString(6).orEmpty(), c.getString(7).orEmpty(),
                alerts(listOf(id))[id].orEmpty(), repeating)
        }
        return null
    }

    /** An RFC 5545 duration such as PT1H, P3600S or P1D, or one hour when it can't be read. */
    private fun durationMillis(text: String?): Long {
        val match = Regex("P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?").matchEntire(text.orEmpty()) ?: return 3_600_000L
        val (w, d, h, m, s) = (1..5).map { match.groupValues[it].toLongOrNull() ?: 0L }
        val total = ((w * 7 + d) * 24 + h) * 3600 + m * 60 + s
        return if (total > 0) total * 1000 else 3_600_000L
    }

    private fun reminder(minutes: Int) = ContentValues().apply {
        put(CalendarContract.Reminders.MINUTES, minutes)
        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
    }

    override fun insert(event: NewEvent): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, event.calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DTSTART, event.startMillis)
            put(CalendarContract.Events.DTEND, event.endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, event.timeZone)
            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
            event.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            event.notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        val batch = ArrayList<ContentProviderOperation>()
        batch += ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(values).build()
        event.alertMinutes.forEach { minutes ->
            batch += ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI).withValues(reminder(minutes))
                .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0).build()
        }
        val results = resolver.applyBatch(CalendarContract.AUTHORITY, batch)
        return results.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull() ?: throw PhoneFailure("unavailable", "The calendar didn't save that.")
    }

    override fun update(id: Long, patch: EventPatch) {
        val values = ContentValues().apply {
            patch.title?.let { put(CalendarContract.Events.TITLE, it) }
            patch.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            patch.notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            patch.startMillis?.let { put(CalendarContract.Events.DTSTART, it) }
            patch.endMillis?.let { put(CalendarContract.Events.DTEND, it) }
            patch.allDay?.let { put(CalendarContract.Events.ALL_DAY, if (it) 1 else 0) }
            patch.timeZone?.let { put(CalendarContract.Events.EVENT_TIMEZONE, it) }
        }
        val batch = ArrayList<ContentProviderOperation>()
        if (values.size() > 0) batch += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)).withValues(values).build()
        patch.alertMinutes?.let { alerts ->
            batch += ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI).withSelection("${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(id.toString())).build()
            alerts.forEach { minutes ->
                batch += ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                    .withValues(reminder(minutes).apply { put(CalendarContract.Reminders.EVENT_ID, id) }).build()
            }
        }
        if (batch.isNotEmpty()) resolver.applyBatch(CalendarContract.AUTHORITY, batch)
    }

    override fun delete(id: Long): Boolean = resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0
}

/** The phone's contacts, phone numbers only: no emails, addresses or photos are ever read. */
internal class ContactsProviderBackend(context: Context) : ContactsBackend {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL)

    private fun label(type: Int, custom: String?): String = ContactsContract.CommonDataKinds.Phone.getTypeLabel(app.resources, type, custom).toString().take(30)

    private fun read(cursor: android.database.Cursor, limit: Int): List<ContactInfo> {
        val order = LinkedHashMap<Long, Pair<String, MutableList<ContactPhone>>>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id !in order && order.size >= limit) continue
                val entry = order.getOrPut(id) { c.getString(1).orEmpty() to ArrayList() }
                val number = c.getString(2).orEmpty().trim().take(40)
                val digits = number.filter { it.isDigit() }
                if (number.isEmpty() || entry.second.size >= PhoneLimits.PHONES_PER_CONTACT || entry.second.any { p -> p.number.filter { it.isDigit() } == digits }) continue
                entry.second += ContactPhone(label(c.getInt(3), c.getString(4)), number)
            }
        }
        return order.map { (id, v) -> ContactInfo(id.toString(), v.first.take(100), v.second) }
    }

    override fun search(query: String, limit: Int): List<ContactInfo> {
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
        val cursor = resolver.query(uri, columns, null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC, ${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC") ?: return emptyList()
        return read(cursor, limit)
    }

    override fun byId(id: Long): ContactInfo? {
        val cursor = resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columns, "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id.toString()),
            "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, ${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC") ?: return null
        return read(cursor, 1).firstOrNull()
    }

    override fun matches(number: String): List<ContactMatch> {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val found = ArrayList<ContactMatch>()
        resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.NUMBER), null, null, null)?.use { c ->
            while (c.moveToNext() && found.size < 10) found += ContactMatch(c.getString(0).orEmpty(), c.getString(1).orEmpty())
        }
        return found
    }
}

/** Alarms, timers, calls and message drafts: each is an Android intent that the owner's own apps handle. */
internal class IntentActionsBackend(context: Context) : ActionsBackend {
    private val app = context.applicationContext

    private fun start(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(app.packageManager) == null) return false
        return try { app.startActivity(intent); true } catch (_: ActivityNotFoundException) { false }
    }

    override fun setAlarm(hour: Int, minute: Int, label: String?, days: List<String>): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (days.isNotEmpty()) intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { dayConstant(it) }))
        return start(intent)
    }

    private fun dayConstant(code: String) = when (code) {
        "MO" -> Calendar.MONDAY; "TU" -> Calendar.TUESDAY; "WE" -> Calendar.WEDNESDAY; "TH" -> Calendar.THURSDAY
        "FR" -> Calendar.FRIDAY; "SA" -> Calendar.SATURDAY; else -> Calendar.SUNDAY
    }

    override fun setTimer(seconds: Int, label: String?): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return start(intent)
    }

    override fun dial(number: String, direct: Boolean): Boolean {
        // A direct call needs Android's phone permission; without it nothing is placed, not even the dialer.
        if (direct && ContextCompat.checkSelfPermission(app, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) throw SecurityException("The phone permission is missing")
        // Owner, 4 Nov 2026: with several calling apps and no default, ACTION_CALL opened Android's "Complete action using" picker under
        // the assistant and nothing was called. Telecom places it with the phone's own dialer; the intent stays as the fallback.
        if (direct) app.getSystemService(android.telecom.TelecomManager::class.java)?.let { telecom ->
            try { telecom.placeCall(Uri.fromParts("tel", number, null), android.os.Bundle()); return true }
            catch (e: SecurityException) { throw e } catch (_: Exception) { }
        }
        return start(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
    }

    override fun draftMessage(number: String, body: String): Boolean =
        start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", body))

    override fun format(number: String): String? = runCatching {
        PhoneNumberUtils.formatNumber(number, java.util.Locale.getDefault().country.ifBlank { "US" })
    }.getOrNull()

    @Suppress("DEPRECATION")
    override fun isEmergency(number: String): Boolean = runCatching { PhoneNumberUtils.isEmergencyNumber(number) }.getOrDefault(false)
}
