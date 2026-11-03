package dev.hermes.android.phone

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The phone tools against the phone's real calendar and contacts providers, in a calendar and a contact this test creates and removes.
 * It switches Calendar & reminders and Contacts on for the run and puts the owner's choices back afterwards.
 */
class PhoneToolsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val approved = PhoneDecision(true)
    private var calendarId = -1L
    private var contactId = -1L
    private val before = PhoneCapability.entries.associateWith { PhoneAccess.switchedOn(context, it) }
    private val account = "beacon-phone-test"

    @Before fun setUp() {
        for (permission in listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR", "android.permission.READ_CONTACTS", "android.permission.CALL_PHONE")) grant(permission)
        PhoneAccess.setSwitchedOn(context, PhoneCapability.Calendar, true)
        PhoneAccess.setSwitchedOn(context, PhoneCapability.Contacts, true)
        PhoneAccess.setSwitchedOn(context, PhoneCapability.Calls, false)
        calendarId = createCalendar()
    }

    @After fun tearDown() {
        if (calendarId > 0) resolver.delete(syncAdapter(CalendarContract.Calendars.CONTENT_URI), "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))
        if (contactId > 0) withShell("android.permission.WRITE_CONTACTS") {
            resolver.delete(syncAdapter(ContactsContract.RawContacts.CONTENT_URI), "${ContactsContract.RawContacts.CONTACT_ID} = ?", arrayOf(contactId.toString()))
        }
        before.forEach { (capability, on) -> PhoneAccess.setSwitchedOn(context, capability, on) }
    }

    /** `pm grant`, read to its end so the permission is in place before the test goes on. */
    private fun grant(permission: String) {
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
    }

    private fun syncAdapter(uri: android.net.Uri) = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account).appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()

    private fun createCalendar(): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "Beacon phone test")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Beacon phone test")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0x2266AA)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
        }
        val uri = resolver.insert(syncAdapter(CalendarContract.Calendars.CONTENT_URI), values)
        return ContentUris.parseId(requireNotNull(uri) { "The test calendar could not be created" })
    }

    private fun withShell(vararg permissions: String, block: () -> Unit) {
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(*permissions)
        try { block() } finally { automation.dropShellPermissionIdentity() }
    }

    private fun run(name: String, args: JSONObject, decision: PhoneDecision? = null) = runBlocking { PhoneTools.run(context, name, args, PhoneOrigin.LiveCall, decision) }

    private fun listed(query: String): JSONArray {
        val from = Instant.now().minus(1, ChronoUnit.DAYS)
        val result = run("phone_calendar_list", JSONObject().put("from", from.toString()).put("to", from.plus(30, ChronoUnit.DAYS).toString()).put("query", query).put("limit", 50))
        assertFalse(result.toString(), result.has("error"))
        return result.getJSONArray("events")
    }

    @Test fun calendarsListsTheTestCalendarAsWritable() {
        val row = run("phone_calendars", JSONObject()).getJSONArray("calendars").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }.first { it.getString("id") == calendarId.toString() }
        assertEquals("Beacon phone test", row.getString("name"))
        assertTrue(row.getBoolean("writable"))
    }

    @Test fun anEventIsCreatedListedUpdatedAndDeletedInTheTestCalendar() {
        val start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
        val create = JSONObject().put("title", "Beacon test dentist").put("start", start.toString()).put("end", start.plus(1, ChronoUnit.HOURS).toString())
            .put("location", "Clinic").put("notes", "bring forms").put("calendar_id", calendarId.toString()).put("alert_minutes", JSONArray(listOf(10)))
        assertEquals("a write without a tap is refused", "denied", run("phone_calendar_create", create).getString("error"))
        assertEquals(0, listed("Beacon test").length())
        val id = run("phone_calendar_create", create, approved).getString("id")

        val found = listed("Beacon test dentist")
        assertEquals(1, found.length())
        val event = found.getJSONObject(0)
        assertEquals(id, event.getString("id"))
        assertEquals(calendarId.toString(), event.getString("calendar_id"))
        assertEquals("Clinic", event.getString("location"))
        assertEquals("bring forms", event.getString("notes"))
        assertEquals(JSONArray(listOf(10)).toString(), event.getJSONArray("alert_minutes").toString())
        assertFalse(event.getBoolean("all_day"))
        assertEquals(start, java.time.OffsetDateTime.parse(event.getString("start")).toInstant())

        assertEquals(id, run("phone_calendar_update", JSONObject().put("id", id).put("title", "Beacon test doctor").put("alert_minutes", JSONArray(listOf(5, 30))), approved).getString("id"))
        val changed = listed("Beacon test doctor").getJSONObject(0)
        assertEquals("Beacon test doctor", changed.getString("title"))
        assertEquals(JSONArray(listOf(5, 30)).toString(), changed.getJSONArray("alert_minutes").toString())
        assertEquals("the place stays", "Clinic", changed.getString("location"))

        assertEquals("denied", run("phone_calendar_delete", JSONObject().put("id", id)).getString("error"))
        assertTrue(run("phone_calendar_delete", JSONObject().put("id", id), approved).getBoolean("deleted"))
        assertEquals(0, listed("Beacon test").length())
        assertEquals("not_found", run("phone_calendar_delete", JSONObject().put("id", id), approved).getString("error"))
    }

    @Test fun anAllDayEventUsesDatesAndAMovedEventKeepsItsLength() {
        val day = Instant.now().plus(3, ChronoUnit.DAYS).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        val id = run("phone_calendar_create", JSONObject().put("title", "Beacon test trip").put("start", day.toString()).put("end", day.plusDays(1).toString())
            .put("all_day", true).put("calendar_id", calendarId.toString()), approved).getString("id")
        val trip = listed("Beacon test trip").getJSONObject(0)
        assertTrue(trip.getBoolean("all_day"))
        assertEquals(day.toString(), trip.getString("start"))
        assertEquals(day.plusDays(1).toString(), trip.getString("end"))
        run("phone_calendar_delete", JSONObject().put("id", id), approved)
    }

    @Test fun aReminderIsAnEventWithAnAlertRow() {
        val at = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES)
        val id = run("phone_reminder_create", JSONObject().put("title", "Beacon test pills").put("at", at.toString()).put("alert_minutes_before", 5).put("notes", "with water")
            .put("calendar_id", calendarId.toString()), approved).getString("id").toLong()
        resolver.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD), "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(id.toString()), null)!!.use { rows ->
            assertEquals(1, rows.count)
            rows.moveToFirst()
            assertEquals(5, rows.getInt(0))
            assertEquals(CalendarContract.Reminders.METHOD_ALERT, rows.getInt(1))
        }
        resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.HAS_ALARM), null, null, null)!!.use { rows ->
            rows.moveToFirst()
            assertEquals("Beacon test pills", rows.getString(0))
            assertEquals(calendarId, rows.getLong(1))
            assertEquals(1, rows.getInt(2))
        }
        run("phone_calendar_delete", JSONObject().put("id", id.toString()), approved)
    }

    @Test fun contactsSearchFindsATestContactByNameAndNumberOnly() {
        insertContact("BeaconTestPerson", "+15555550123")
        val contacts = run("phone_contacts_search", JSONObject().put("query", "BeaconTestPerson")).getJSONArray("contacts")
        assertEquals(1, contacts.length())
        val person = contacts.getJSONObject(0)
        assertEquals("BeaconTestPerson", person.getString("name"))
        assertEquals(setOf("id", "name", "phones"), person.keys().asSequence().toSet())
        assertEquals("+15555550123", person.getJSONArray("phones").getJSONObject(0).getString("number").filter { it.isDigit() || it == '+' })
        assertEquals(0, run("phone_contacts_search", JSONObject().put("query", "NoSuchBeaconPerson")).getJSONArray("contacts").length())

        // The call card for that contact shows who and which number, and nothing is dialed without the tap.
        PhoneAccess.setSwitchedOn(context, PhoneCapability.Calls, true)
        val prepared = runBlocking { PhoneTools.prepare(context, "phone_call", JSONObject().put("contact_id", person.getString("id")), PhoneOrigin.LiveCall) }
        assertTrue(prepared.card.describe().contains("BeaconTestPerson"))
        assertEquals("+15555550123", prepared.card.target.filter { it.isDigit() || it == '+' })
    }

    @Test fun aNumberThatOnlyLooksLikeASavedContactIsNotOneOnARealContact() {
        insertContact("BeaconTestPerson", "+15555550123")
        PhoneAccess.setSwitchedOn(context, PhoneCapability.Calls, true)
        fun card(number: String) = runBlocking { PhoneTools.prepare(context, "phone_call", JSONObject().put("number", number), PhoneOrigin.LiveCall) }.card
        // The exact number, however it is spaced, is the saved contact: its name on the card and the countdown.
        val saved = card("+1 555 555 0123")
        assertTrue(saved.savedContact)
        assertEquals("Call BeaconTestPerson", saved.headline.text())
        assertEquals(3, PhoneTools.countdownSeconds("phone_call", PhoneOrigin.LiveCall, saved.savedContact))
        // Android's own lookup also matches a number that merely ends the same (another country code). That is not the contact.
        val lookalike = card("+99 555 555 0123")
        assertFalse(lookalike.savedContact)
        assertFalse(lookalike.describe().contains("BeaconTestPerson"))
        assertEquals(0, PhoneTools.countdownSeconds("phone_call", PhoneOrigin.LiveCall, lookalike.savedContact))
    }

    @Test fun offAndMissingPermissionAreReportedNotRun() {
        PhoneAccess.setSwitchedOn(context, PhoneCapability.Contacts, false)
        assertEquals("off", run("phone_contacts_search", JSONObject().put("query", "x")).getString("error"))
        assertFalse("phone_contacts_search" in PhoneTools.enabledTools(context))
        assertTrue("phone_calendars" in PhoneTools.enabledTools(context))
    }

    @Test fun theActivityListRecordsWhatHappened() {
        val log = PhoneActivityLog.of(context)
        val before = log.recent().size
        run("phone_calendars", JSONObject())
        run("phone_calendar_delete", JSONObject().put("id", "1"))
        val added = log.recent().take(2)
        assertEquals(listOf("denied", "done"), added.map { it.outcome })
        assertTrue(log.recent().size >= minOf(before + 2, PhoneActivityLog.LIMIT))
    }

    private fun insertContact(name: String, number: String) {
        var made = false
        withShell("android.permission.WRITE_CONTACTS", "android.permission.READ_CONTACTS") {
            val batch = ArrayList<ContentProviderOperation>()
            batch += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build()
            batch += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build()
            batch += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number).withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build()
            resolver.applyBatch(ContactsContract.AUTHORITY, batch)
            resolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID), "${ContactsContract.Contacts.DISPLAY_NAME} = ?", arrayOf(name), null)?.use { if (it.moveToFirst()) { contactId = it.getLong(0); made = true } }
        }
        assumeTrue("The test contact could not be created on this device", made)
    }
}
