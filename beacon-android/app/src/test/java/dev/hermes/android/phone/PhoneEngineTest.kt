package dev.hermes.android.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneEngineTest {
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }
    private fun run(rig: Rig, name: String, args: JSONObject, origin: PhoneOrigin = PhoneOrigin.LiveCall, decision: PhoneDecision? = null) =
        runBlocking { rig.engine.run(name, args, origin, decision) }
    private fun ready() = Rig().also { it.settings.everything() }
    private val start = "2025-11-04T15:00:00Z"

    // ---- the contract ----

    @Test fun theContractNamesAreExactlyTheToolsAndWritesAreMarked() {
        assertEquals(setOf("phone_calendars", "phone_calendar_list", "phone_contacts_search", "phone_calendar_create", "phone_calendar_update", "phone_calendar_delete",
            "phone_reminder_create", "phone_alarm_set", "phone_timer_set", "phone_call", "phone_message_draft"), PhoneTools.names)
        val writes = PhoneTools.names.filter(PhoneTools::isWrite).toSet()
        assertEquals(PhoneTools.names - setOf("phone_calendars", "phone_calendar_list", "phone_contacts_search"), writes)
        assertTrue(PhoneTools.launchesApp("phone_call") && PhoneTools.launchesApp("phone_alarm_set") && !PhoneTools.launchesApp("phone_calendar_create"))
    }

    // ---- off by default, permissions ----

    @Test fun everythingIsOffUntilTheOwnerTurnsItOn() {
        val rig = Rig()
        assertTrue(rig.engine.enabledTools().isEmpty())
        for (name in PhoneTools.names) assertEquals(name, "off", run(rig, name, obj(), decision = Approved).optString("error"))
        assertTrue(rig.calendar.inserted.isEmpty() && rig.actions.dialed.isEmpty())
    }

    @Test fun aSwitchOnWithoutTheAndroidPermissionIsAPermissionErrorAndThenOffForGood() {
        val rig = Rig()
        rig.settings.on += PhoneCapability.Calendar
        val error = run(rig, "phone_calendars", obj())
        assertEquals("permission", error.getString("error"))
        assertTrue(error.getString("message").isNotBlank())
        assertTrue(rig.engine.enabledTools().isEmpty())
        // Android allowing it again does not switch it back on; only the owner does.
        rig.settings.allowed += PhoneCapability.Calendar
        assertTrue(rig.engine.enabledTools().isEmpty())
        assertEquals("off", run(rig, "phone_calendars", obj()).getString("error"))
        rig.settings.on += PhoneCapability.Calendar
        assertEquals(setOf("phone_calendars", "phone_calendar_list", "phone_calendar_create", "phone_calendar_update", "phone_calendar_delete", "phone_reminder_create"), rig.engine.enabledTools())
    }

    @Test fun aPermissionTakenAwayIsSeenWhereverTheSwitchIsReadAndTurnsOnlyThatSwitchOff() {
        val declared = ready()
        declared.settings.allowed -= PhoneCapability.Calendar
        // A call setting up its tools sees it ...
        assertFalse("phone_calendars" in declared.engine.enabledTools())
        assertFalse(PhoneCapability.Calendar in declared.settings.on)
        declared.settings.allowed += PhoneCapability.Calendar
        assertFalse("allowed again, still off", "phone_calendars" in declared.engine.enabledTools())
        assertEquals("off", run(declared, "phone_calendars", obj()).getString("error"))
        // ... and so does a request that arrives first.
        val requested = ready()
        requested.settings.allowed -= PhoneCapability.Contacts
        assertEquals("permission", run(requested, "phone_contacts_search", obj("query" to "mo")).getString("error"))
        assertFalse(PhoneCapability.Contacts in requested.settings.on)
        requested.settings.allowed += PhoneCapability.Contacts
        assertEquals("off", run(requested, "phone_contacts_search", obj("query" to "mo")).getString("error"))
        // A switch whose permission is still there is left alone.
        assertTrue(PhoneCapability.Alarms in requested.settings.on && PhoneCapability.Calendar in requested.settings.on)
        val prepare = runCatching { runBlocking { requested.engine.prepare("phone_contacts_search", obj("query" to "mo"), PhoneOrigin.LiveCall) } }.exceptionOrNull() as PhoneFailure
        assertEquals("off", prepare.code)
    }

    @Test fun turningACapabilityOffStopsItAtOnce() {
        val rig = ready()
        assertTrue(run(rig, "phone_calendars", obj()).has("calendars"))
        rig.settings.on -= PhoneCapability.Calendar
        assertEquals("off", run(rig, "phone_calendars", obj()).getString("error"))
        assertFalse("phone_calendars" in rig.engine.enabledTools())
    }

    @Test fun anUnknownToolIsInvalid() {
        assertEquals("invalid", run(ready(), "phone_format_disk", obj()).getString("error"))
    }

    @Test fun hermesCannotUsePhoneAccessUntilTheOwnerAllowsIt() {
        val rig = ready()
        assertTrue(rig.engine.enabledTools(PhoneOrigin.Hermes).isEmpty())
        assertEquals("off", run(rig, "phone_calendars", obj(), PhoneOrigin.Hermes, Approved).getString("error"))
        rig.settings.hermes = true
        assertEquals(rig.engine.enabledTools(), rig.engine.enabledTools(PhoneOrigin.Hermes))
        // Calls and the overlay never depend on the Hermes switch.
        assertTrue(run(rig, "phone_calendars", obj(), PhoneOrigin.Overlay).has("calendars"))
    }

    @Test fun aHermesReadWaitsForATapWhileAskBeforeReadsIsOn() {
        val rig = ready().also { it.settings.hermes = true }
        assertTrue(rig.engine.needsApproval("phone_calendars", PhoneOrigin.Hermes))
        assertFalse(rig.engine.needsApproval("phone_calendars", PhoneOrigin.LiveCall))
        assertEquals("denied", run(rig, "phone_calendars", obj(), PhoneOrigin.Hermes).getString("error"))
        assertTrue(run(rig, "phone_calendars", obj(), PhoneOrigin.Hermes, Approved).has("calendars"))
        rig.settings.askReads = false
        assertTrue(run(rig, "phone_calendars", obj(), PhoneOrigin.Hermes).has("calendars"))
    }

    // ---- writes need the owner's tap ----

    @Test fun everyWriteIsRefusedWithoutAnApprovedTap() {
        val rig = ready()
        val calls = mapOf(
            "phone_calendar_create" to obj("title" to "Dentist", "start" to start),
            "phone_reminder_create" to obj("title" to "Pills", "at" to start),
            "phone_alarm_set" to obj("hour" to 7, "minute" to 30),
            "phone_timer_set" to obj("seconds" to 600),
            "phone_call" to obj("number" to "+966501234567"),
            "phone_message_draft" to obj("to" to "+966501234567", "body" to "Hi"),
        )
        for ((name, args) in calls) {
            assertEquals("$name without a decision", "denied", run(rig, name, args).getString("error"))
            assertEquals("$name declined", "denied", run(rig, name, args, decision = Declined).getString("error"))
        }
        rig.events().let { assertTrue(it.isEmpty()) }
        assertTrue(rig.calendar.inserted.isEmpty() && rig.actions.alarms.isEmpty() && rig.actions.timers.isEmpty() && rig.actions.dialed.isEmpty() && rig.actions.drafts.isEmpty())
    }

    private fun Rig.events() = calendar.inserted + calendar.updated + calendar.deleted

    @Test fun updateAndDeleteAreRefusedWithoutATapAndLeaveTheEventAlone() {
        val rig = ready()
        rig.calendar.events[5] = EventInfo(5, 1, "Dentist", 1_000_000, 4_600_000, false, "", "", emptyList())
        assertEquals("denied", run(rig, "phone_calendar_update", obj("id" to "5", "title" to "Doctor")).getString("error"))
        assertEquals("denied", run(rig, "phone_calendar_delete", obj("id" to "5"), decision = Declined).getString("error"))
        assertTrue(rig.calendar.updated.isEmpty() && rig.calendar.deleted.isEmpty() && rig.calendar.events.containsKey(5))
    }

    @Test fun aTapOnALockedPhoneDoesNotApprove() {
        val rig = ready().also { it.settings.hermes = true }
        rig.locked = true
        assertEquals("denied", run(rig, "phone_timer_set", obj("seconds" to 60), PhoneOrigin.Hermes, Approved).getString("error"))
        assertTrue(rig.actions.timers.isEmpty())
        rig.locked = false
        assertTrue(run(rig, "phone_timer_set", obj("seconds" to 60), PhoneOrigin.Hermes, Approved).getBoolean("set"))
        // A Hermes read that needs no tap (the owner turned "Ask before Hermes reads" off) is not held up by the lock.
        rig.settings.askReads = false
        rig.locked = true
        assertTrue(run(rig, "phone_calendars", obj(), PhoneOrigin.Hermes).has("calendars"))
    }

    // Whether a call or the overlay may use phone tools on a locked phone is the conversation's own trust (CallTrust, tested in
    // LockedStartCallTest): a call that started unlocked keeps reading the calendar with the screen off. The engine's lock is the tap's.
    @Test fun theEnginesLockOnlyStopsATapFromApprovingAndNeverAReadOfAConversationThatStartedUnlocked() {
        val rig = ready()
        rig.locked = true
        for (origin in listOf(PhoneOrigin.LiveCall, PhoneOrigin.Overlay)) {
            assertTrue("$origin", run(rig, "phone_calendars", obj(), origin).has("calendars"))
            assertEquals("$origin", "denied", run(rig, "phone_timer_set", obj("seconds" to 60), origin, Approved).getString("error"))
        }
        assertTrue(rig.actions.timers.isEmpty())
        rig.locked = false
        assertTrue(run(rig, "phone_timer_set", obj("seconds" to 60), PhoneOrigin.Overlay, Approved).getBoolean("set"))
    }

    @Test fun aLockedPhoneStopsAnApprovedCardTooButNotAHermesReadItMayRunWithoutAsking() {
        val rig = ready().also { it.settings.hermes = true; it.settings.askReads = false }
        val prepared = runBlocking { rig.engine.prepare("phone_timer_set", obj("seconds" to 60), PhoneOrigin.LiveCall) }
        rig.locked = true
        // A tap on a locked phone does not approve (the card was drawn while it was unlocked).
        assertEquals("denied", runBlocking { rig.engine.runPrepared(prepared, PhoneOrigin.LiveCall, Approved) }.getString("error"))
        assertTrue(rig.actions.timers.isEmpty())
        assertTrue("Hermes' own reads follow its switch, not the lock", run(rig, "phone_calendars", obj(), PhoneOrigin.Hermes).has("calendars"))
    }

    @Test fun anApprovedTapRunsTheTool() {
        val rig = ready()
        assertEquals("100", run(rig, "phone_calendar_create", obj("title" to "Dentist", "start" to start, "alert_minutes" to JSONArray(listOf(10, 60))), decision = Approved).getString("id"))
        assertEquals(listOf(10, 60), rig.calendar.inserted.single().alertMinutes)
        assertTrue(run(rig, "phone_alarm_set", obj("hour" to 7, "minute" to 30, "days" to JSONArray(listOf("mo", "FR"))), decision = Approved).getBoolean("set"))
        assertEquals(Triple(7, 30, listOf("MO", "FR")), rig.actions.alarms.single())
        assertTrue(run(rig, "phone_timer_set", obj("seconds" to 90), decision = Approved).getBoolean("set"))
        assertEquals(listOf(90), rig.actions.timers)
        assertTrue(run(rig, "phone_message_draft", obj("to" to "050 123 4567", "body" to "On my way"), decision = Approved).getBoolean("opened"))
        assertEquals("0501234567" to "On my way", rig.actions.drafts.single())
    }

    // ---- calendar ----

    @Test fun createUsesTheMainWritableCalendarAndRefusesAReadOnlyOne() {
        val rig = ready()
        run(rig, "phone_calendar_create", obj("title" to "Dentist", "start" to start), decision = Approved)
        assertEquals(1L, rig.calendar.inserted.single().calendarId)
        assertEquals("invalid", run(rig, "phone_calendar_create", obj("title" to "X", "start" to start, "calendar_id" to "2"), decision = Approved).getString("error"))
        assertEquals("not_found", run(rig, "phone_calendar_create", obj("title" to "X", "start" to start, "calendar_id" to "99"), decision = Approved).getString("error"))
    }

    @Test fun anEventEndsAnHourAfterItStartsAndAnAllDayEventUsesDates() {
        val rig = ready()
        run(rig, "phone_calendar_create", obj("title" to "Dentist", "start" to start), decision = Approved)
        val timed = rig.calendar.inserted.single()
        assertEquals(60 * 60_000L, timed.endMillis - timed.startMillis)
        assertEquals("UTC", timed.timeZone)
        run(rig, "phone_calendar_create", obj("title" to "Trip", "start" to "2025-11-10", "end" to "2025-11-12", "all_day" to true), decision = Approved)
        val allDay = rig.calendar.inserted.last()
        assertTrue(allDay.allDay)
        assertEquals("UTC", allDay.timeZone)
        assertEquals(3 * 86_400_000L, allDay.endMillis - allDay.startMillis)
    }

    @Test fun aReminderIsAShortEventWithAnAlert() {
        val rig = ready()
        run(rig, "phone_reminder_create", obj("title" to "Pills", "at" to start, "alert_minutes_before" to 5), decision = Approved)
        val event = rig.calendar.inserted.single()
        assertEquals(listOf(5), event.alertMinutes)
        assertEquals(15 * 60_000L, event.endMillis - event.startMillis)
        run(rig, "phone_reminder_create", obj("title" to "Pills", "at" to start), decision = Approved)
        assertEquals(listOf(0), rig.calendar.inserted.last().alertMinutes)
    }

    @Test fun listReturnsBoundedEventsWithTheContractFields() {
        val rig = ready()
        val long = "n".repeat(900)
        rig.calendar.events[5] = EventInfo(5, 1, "Dentist", java.time.Instant.parse(start).toEpochMilli(), java.time.Instant.parse("2025-11-04T16:00:00Z").toEpochMilli(), false, "Clinic", long, listOf(10))
        val result = run(rig, "phone_calendar_list", obj("from" to "2025-11-04T00:00:00Z", "to" to "2025-11-05T00:00:00Z"))
        val event = result.getJSONArray("events").getJSONObject(0)
        assertEquals(setOf("id", "calendar_id", "title", "start", "end", "all_day", "location", "notes", "alert_minutes"), event.keys().asSequence().toSet())
        assertEquals("5", event.getString("id"))
        assertEquals("2025-11-04T15:00:00Z", event.getString("start"))
        assertEquals(500, event.getString("notes").length)
        assertFalse(result.getBoolean("truncated"))
    }

    @Test fun listCapsTheNumberOfEventsAndSaysSo() {
        val rig = ready()
        repeat(60) { rig.calendar.events[it + 10L] = EventInfo(it + 10L, 1, "E$it", java.time.Instant.parse(start).toEpochMilli() + it, java.time.Instant.parse(start).toEpochMilli() + it + 1, false, "", "", emptyList()) }
        val result = run(rig, "phone_calendar_list", obj("from" to "2025-11-04T00:00:00Z", "to" to "2025-11-05T00:00:00Z", "limit" to 50))
        assertEquals(50, result.getJSONArray("events").length())
        assertTrue(result.getBoolean("truncated"))
        assertEquals(20, run(rig, "phone_calendar_list", obj("from" to "2025-11-04T00:00:00Z", "to" to "2025-11-05T00:00:00Z")).getJSONArray("events").length())
    }

    @Test fun updateChangesOnlyWhatWasGivenAndKeepsTheDuration() {
        val rig = ready()
        rig.calendar.events[5] = EventInfo(5, 1, "Dentist", java.time.Instant.parse(start).toEpochMilli(), java.time.Instant.parse("2025-11-04T16:30:00Z").toEpochMilli(), false, "", "", emptyList())
        run(rig, "phone_calendar_update", obj("id" to "5", "start" to "2025-11-05T09:00:00Z"), decision = Approved)
        val (id, patch) = rig.calendar.updated.single()
        assertEquals(5L, id)
        assertEquals(java.time.Instant.parse("2025-11-05T09:00:00Z").toEpochMilli(), patch.startMillis)
        assertEquals(java.time.Instant.parse("2025-11-05T10:30:00Z").toEpochMilli(), patch.endMillis)
        assertEquals(null, patch.title)
        assertEquals("invalid", run(rig, "phone_calendar_update", obj("id" to "5"), decision = Approved).getString("error"))
    }

    @Test fun aRepeatingEventKeepsItsTimeButMayChangeItsTitle() {
        val rig = ready()
        rig.calendar.events[6] = EventInfo(6, 1, "Standup", 1_000_000, 2_000_000, false, "", "", emptyList(), repeating = true)
        assertEquals("invalid", run(rig, "phone_calendar_update", obj("id" to "6", "start" to start), decision = Approved).getString("error"))
        assertEquals("6", run(rig, "phone_calendar_update", obj("id" to "6", "title" to "Daily"), decision = Approved).getString("id"))
    }

    @Test fun deleteFindsTheEventOrSaysNotFound() {
        val rig = ready()
        rig.calendar.events[5] = EventInfo(5, 1, "Dentist", 1_000_000, 2_000_000, false, "", "", emptyList())
        assertTrue(run(rig, "phone_calendar_delete", obj("id" to "5"), decision = Approved).getBoolean("deleted"))
        assertEquals("not_found", run(rig, "phone_calendar_delete", obj("id" to "5"), decision = Approved).getString("error"))
    }

    @Test fun anEventInAReadOnlyCalendarCannotBeChanged() {
        val rig = ready()
        rig.calendar.events[8] = EventInfo(8, 2, "Holiday", 1_000_000, 2_000_000, true, "", "", emptyList())
        assertEquals("invalid", run(rig, "phone_calendar_delete", obj("id" to "8"), decision = Approved).getString("error"))
        assertTrue(rig.calendar.deleted.isEmpty())
    }

    // ---- contacts ----

    @Test fun contactsReturnOnlyNamesAndNumbersAndAreCapped() {
        val rig = ready()
        val found = run(rig, "phone_contacts_search", obj("query" to "o")).getJSONArray("contacts")
        assertTrue(found.length() in 1..5)
        val first = found.getJSONObject(0)
        assertEquals(setOf("id", "name", "phones"), first.keys().asSequence().toSet())
        assertEquals(setOf("label", "number"), first.getJSONArray("phones").getJSONObject(0).keys().asSequence().toSet())
        assertEquals("invalid", run(rig, "phone_contacts_search", obj("query" to "o", "limit" to 11)).getString("error"))
        assertEquals("invalid", run(rig, "phone_contacts_search", obj("query" to "")).getString("error"))
    }

    // ---- calls ----

    @Test fun anOrdinaryNumberIsDialedDirectlyAfterTheTap() {
        val rig = ready()
        val result = run(rig, "phone_call", obj("number" to "+966 50 123 4567"), decision = Approved)
        assertTrue(result.getBoolean("placed"))
        assertEquals("+966501234567" to true, rig.actions.dialed.single())
    }

    @Test fun emergencyAndShortServiceNumbersOnlyOpenTheDialer() {
        val rig = ready()
        rig.actions.emergency = setOf("911", "000")
        for (number in listOf("911", "000", "112", "997", "100", "10000")) {
            val result = run(rig, "phone_call", obj("number" to number), decision = Approved)
            assertFalse("$number is not placed", result.optBoolean("placed", true))
            assertTrue(result.getBoolean("opened_dialer"))
        }
        assertTrue(rig.actions.dialed.all { !it.second })
        // A full-length number stays a direct call.
        assertTrue(run(rig, "phone_call", obj("number" to "0501234567"), decision = Approved).getBoolean("placed"))
    }

    @Test fun aShortOrEmergencyNumberWrittenWithAPlusStillOnlyOpensTheDialer() {
        val rig = ready()
        rig.actions.emergency = setOf("911")
        for (number in listOf("+911", "+112", "+12345")) {
            val result = run(rig, "phone_call", obj("number" to number), decision = Approved)
            assertFalse("$number is not placed", result.optBoolean("placed", true))
            assertTrue(result.getBoolean("opened_dialer"))
        }
        assertTrue(rig.actions.dialed.all { !it.second })
        // The card says so too.
        assertTrue(prepare(rig, "phone_call", obj("number" to "+999")).card.describe().contains("Opens the dialer"))
        // A saved contact whose number is a short code gets no countdown: the countdown is only for calls Beacon places.
        rig.contacts.people += ContactInfo("13", "Desk", listOf(ContactPhone("Work", "+112")))
        assertFalse(prepare(rig, "phone_call", obj("number" to "+112")).card.savedContact)
    }

    @Test fun aCallByContactShowsAndDialsTheNumberFromTheContact() {
        val rig = ready()
        val prepared = runBlocking { rig.engine.prepare("phone_call", obj("contact_id" to "7"), PhoneOrigin.LiveCall) }
        assertEquals("Call Mona Test +966501234567", prepared.card.describe())
        assertEquals("\u2066+966501234567\u2069", prepared.card.target)
        assertEquals("+966501234567", prepared.runningArguments().getString("number"))
        runBlocking { rig.engine.runPrepared(prepared, PhoneOrigin.LiveCall, Approved) }
        assertEquals("+966501234567" to true, rig.actions.dialed.single())
    }

    @Test fun aCallByContactNeedsContactsAndAKnownContactWithANumber() {
        val rig = ready()
        assertEquals("not_found", run(rig, "phone_call", obj("contact_id" to "404"), decision = Approved).getString("error"))
        assertEquals("not_found", run(rig, "phone_call", obj("contact_id" to "9"), decision = Approved).getString("error"))
        rig.settings.on -= PhoneCapability.Contacts
        assertEquals("off", run(rig, "phone_call", obj("contact_id" to "7"), decision = Approved).getString("error"))
        assertEquals("invalid", run(rig, "phone_call", obj("number" to "123456", "contact_id" to "7"), decision = Approved).getString("error"))
        assertEquals("invalid", run(rig, "phone_call", obj(), decision = Approved).getString("error"))
    }

    @Test fun noAppForTheRequestIsUnavailable() {
        val rig = ready()
        rig.actions.handled = false
        assertEquals("unavailable", run(rig, "phone_timer_set", obj("seconds" to 5), decision = Approved).getString("error"))
    }

    // ---- bounds ----

    @Test fun argumentsAreValidatedAndBounded() {
        val rig = ready()
        fun bad(name: String, args: JSONObject) = assertEquals("$name $args", "invalid", run(rig, name, args, decision = Approved).optString("error"))
        bad("phone_calendar_create", obj("title" to "T".repeat(201), "start" to start))
        bad("phone_calendar_create", obj("title" to "  ", "start" to start))
        bad("phone_calendar_create", obj("title" to "T", "start" to "tomorrow"))
        bad("phone_calendar_create", obj("title" to "T", "start" to start, "notes" to "n".repeat(501)))
        bad("phone_calendar_create", obj("title" to "T", "start" to start, "location" to "l".repeat(201)))
        bad("phone_calendar_create", obj("title" to "T", "start" to start, "end" to "2025-11-04T14:00:00Z"))
        bad("phone_calendar_create", obj("title" to "T", "start" to start, "attendees" to "Sam"))
        bad("phone_calendar_create", obj("title" to "T", "start" to start, "alert_minutes" to JSONArray(listOf(1, 2, 3, 4, 5, 6))))
        bad("phone_calendar_create", obj("title" to "T", "start" to start, "alert_minutes" to JSONArray(listOf(-1))))
        bad("phone_calendar_create", obj("title" to "T", "start" to start, "alert_minutes" to JSONArray(listOf(40_321))))
        bad("phone_calendar_create", obj("title" to "T", "start" to start, "calendar_id" to "1 OR 1=1"))
        bad("phone_calendar_create", obj("title" to 5, "start" to start))
        bad("phone_calendar_delete", obj("id" to "5; DROP TABLE events"))
        bad("phone_calendar_list", obj("from" to "2025-11-04T00:00:00Z", "to" to "2027-01-01T00:00:00Z"))
        bad("phone_calendar_list", obj("from" to "2025-11-05T00:00:00Z", "to" to "2025-11-04T00:00:00Z"))
        bad("phone_calendar_list", obj("from" to "2025-11-04T00:00:00Z", "to" to "2025-11-05T00:00:00Z", "limit" to 51))
        bad("phone_calendar_list", obj("from" to "2025-11-04T00:00:00Z", "to" to "2025-11-05T00:00:00Z", "limit" to 0))
        bad("phone_alarm_set", obj("hour" to 24, "minute" to 0))
        bad("phone_alarm_set", obj("hour" to 7, "minute" to 60))
        bad("phone_alarm_set", obj("hour" to 7, "minute" to 0, "days" to JSONArray(listOf("XX"))))
        bad("phone_alarm_set", obj("hour" to 7.5, "minute" to 0))
        bad("phone_timer_set", obj("seconds" to 0))
        bad("phone_timer_set", obj("seconds" to 86_401))
        bad("phone_call", obj("number" to "123;456"))
        bad("phone_call", obj("number" to "*21#"))
        bad("phone_call", obj("number" to "12"))
        bad("phone_message_draft", obj("to" to "+966501234567", "body" to "b".repeat(1001)))
        bad("phone_message_draft", obj("to" to "+966,501234567", "body" to "hi"))
        assertTrue(rig.events().isEmpty() && rig.actions.dialed.isEmpty() && rig.actions.drafts.isEmpty() && rig.actions.timers.isEmpty())
    }

    @Test fun anUpperBoundExactlyAtTheLimitIsAccepted() {
        val rig = ready()
        run(rig, "phone_calendar_create", obj("title" to "T".repeat(200), "start" to start, "notes" to "n".repeat(500), "location" to "l".repeat(200), "alert_minutes" to JSONArray(listOf(0, 1, 2, 3, 40_320))), decision = Approved)
        assertEquals(1, rig.calendar.inserted.size)
        assertTrue(run(rig, "phone_timer_set", obj("seconds" to 86_400), decision = Approved).getBoolean("set"))
        assertTrue(run(rig, "phone_message_draft", obj("to" to "+966501234567", "body" to "b".repeat(1000)), decision = Approved).getBoolean("opened"))
    }

    // ---- rate limits ----

    @Test fun thirtyReadsAMinuteThenBusyAndTheWindowSlides() {
        val rig = ready()
        repeat(30) { assertTrue(run(rig, "phone_calendars", obj()).has("calendars")) }
        assertEquals("busy", run(rig, "phone_calendars", obj()).getString("error"))
        // Another origin has its own allowance.
        assertTrue(run(rig, "phone_calendars", obj(), PhoneOrigin.Overlay).has("calendars"))
        rig.now += 61_000
        assertTrue(run(rig, "phone_calendars", obj()).has("calendars"))
    }

    @Test fun tenApprovedWritesAMinuteThenBusy() {
        val rig = ready()
        repeat(10) { assertTrue(run(rig, "phone_timer_set", obj("seconds" to 5), decision = Approved).getBoolean("set")) }
        val eleventh = run(rig, "phone_timer_set", obj("seconds" to 5), decision = Approved)
        assertEquals("busy", eleventh.getString("error"))
        assertEquals(10, rig.actions.timers.size)
        // Reads are a separate allowance, and a refused write does not use one up.
        assertTrue(run(rig, "phone_calendars", obj()).has("calendars"))
        rig.now += 61_000
        assertTrue(run(rig, "phone_timer_set", obj("seconds" to 5), decision = Approved).getBoolean("set"))
    }

    @Test fun aNoCountsAgainstTheWriteAllowanceLikeAYes() {
        val rig = ready()
        repeat(10) { assertEquals("denied", run(rig, "phone_timer_set", obj("seconds" to 5), decision = Declined).getString("error")) }
        // Ten no's used the minute's writes up: the next request, even a yes, waits for the window to slide.
        assertEquals("busy", run(rig, "phone_timer_set", obj("seconds" to 5), decision = Approved).getString("error"))
        assertTrue(rig.actions.timers.isEmpty())
        rig.now += 61_000
        assertTrue(run(rig, "phone_timer_set", obj("seconds" to 5), decision = Approved).getBoolean("set"))
    }

    @Test fun aNoOnACardCountsToTooAndAFullAllowanceShowsNoFurtherCard() {
        val rig = ready()
        repeat(10) {
            val card = runBlocking { rig.engine.prepare("phone_call", obj("number" to "+15555550199"), PhoneOrigin.LiveCall) }
            assertEquals("denied", runBlocking { rig.engine.runPrepared(card, PhoneOrigin.LiveCall, Declined) }.getString("error"))
        }
        val next = runCatching { runBlocking { rig.engine.prepare("phone_call", obj("number" to "+15555550199"), PhoneOrigin.LiveCall) } }.exceptionOrNull() as PhoneFailure
        assertEquals("busy", next.code)
        // Reads and the other origins have their own allowance.
        assertTrue(runBlocking { rig.engine.prepare("phone_calendars", obj(), PhoneOrigin.LiveCall) }.card.lines.isNotEmpty())
        assertTrue(runBlocking { rig.engine.prepare("phone_call", obj("number" to "+15555550199"), PhoneOrigin.Overlay) }.card.lines.isNotEmpty())
        assertTrue(rig.actions.dialed.isEmpty())
        rig.now += 61_000
        assertTrue(runBlocking { rig.engine.prepare("phone_call", obj("number" to "+15555550199"), PhoneOrigin.LiveCall) }.card.lines.isNotEmpty())
    }

    // ---- describe ----

    @Test fun describeNamesTheCalendarAndTheTime() {
        val rig = ready()
        val line = runBlocking { rig.engine.prepare("phone_calendar_create", obj("title" to "Dentist", "start" to start), PhoneOrigin.LiveCall) }.card.describe()
        assertEquals("Add 'Dentist' Tue 4 Nov 3:00 PM – 4:00 PM to Personal", line)
        assertEquals("Add 'Dentist' Tue 4 Nov 3:00 PM – 4:00 PM", rig.engine.describeQuick("phone_calendar_create", obj("title" to "Dentist", "start" to start)))
    }

    @Test fun describeCoversEveryToolInOneShortLine() {
        val rig = ready()
        rig.calendar.events[5] = EventInfo(5, 1, "Dentist", java.time.Instant.parse(start).toEpochMilli(), java.time.Instant.parse("2025-11-04T16:00:00Z").toEpochMilli(), false, "", "", emptyList())
        fun card(name: String, args: JSONObject) = runBlocking { rig.engine.prepare(name, args, PhoneOrigin.LiveCall) }.card.describe()
        assertEquals("Delete 'Dentist' Tue 4 Nov 3:00 PM – 4:00 PM", card("phone_calendar_delete", obj("id" to "5")))
        assertEquals("Change 'Dentist'; Title: Doctor", card("phone_calendar_update", obj("id" to "5", "title" to "Doctor")))
        assertEquals("Remind you: 'Pills' Tue 4 Nov 3:00 PM; In calendar: Personal; Alert at the start", card("phone_reminder_create", obj("title" to "Pills", "at" to start)))
        assertEquals("Set an alarm for 7:30 AM; Repeats: Mon, Fri", card("phone_alarm_set", obj("hour" to 7, "minute" to 30, "days" to JSONArray(listOf("MO", "FR")))))
        assertEquals("Start a timer for 1 h 5 min", card("phone_timer_set", obj("seconds" to 3900)))
        assertEquals("Call Mona Test +966501234567", card("phone_call", obj("number" to "+966501234567")))
        assertEquals("Call 112; Opens the dialer. You press call.", card("phone_call", obj("number" to "112")))
        assertEquals("Write to Sam Example +15550102000; Opens a draft. You press send.", card("phone_message_draft", obj("to" to "+15550102000", "body" to "hi")))
        assertEquals("Search contacts for 'mona'", card("phone_contacts_search", obj("query" to "mona")))
    }

    @Test fun aCardNeverShowsAnythingTheModelSuppliedAsATrustedName() {
        val rig = ready()
        // A call by number shows the contact's real name or none; the model cannot supply a name for the card.
        val unknown = runBlocking { rig.engine.prepare("phone_call", obj("number" to "+442071234567"), PhoneOrigin.LiveCall) }
        assertEquals("Call +442071234567", unknown.card.describe())
        assertEquals("invalid", run(rig, "phone_call", obj("number" to "+442071234567", "name" to "Mom"), decision = Approved).getString("error"))
    }

    // ---- a number in a right-to-left text ----

    @Test fun aNumberOnACardIsKeptLeftToRightAndNowhereElse() {
        val rig = ready()
        fun isolated(number: String) = "\u2066" + number + "\u2069"
        // No saved contact: the headline carries the number; the contract's one line and the activity line keep it plain.
        val unknown = prepare(rig, "phone_call", obj("number" to "+442071234567")).card
        assertEquals(isolated("+442071234567"), unknown.headline.args.single())
        assertEquals("Call " + isolated("+442071234567"), unknown.headline.text())
        assertEquals("Call +442071234567", unknown.describe())
        assertEquals("Call +442071234567", unknown.lines.first().text())
        assertEquals("", unknown.target)
        // A saved contact: the headline is the name, and the number sits in the target.
        rig.contacts.people += ContactInfo("12", "Mom", listOf(ContactPhone("Mobile", "050 123 4567")))
        val saved = prepare(rig, "phone_call", obj("number" to "0501234567")).card
        assertEquals("Call Mom", saved.headline.text())
        assertEquals(isolated("0501234567"), saved.target)
        assertEquals("Call Mom 0501234567", saved.describe())
        // A message to a number shows it the same way, with the draft's words untouched.
        val message = prepare(rig, "phone_message_draft", obj("to" to "+990501234567", "body" to "hi")).card
        assertEquals("Write to " + isolated("+990501234567"), message.headline.text())
        assertEquals("Write to +990501234567", message.describe().substringBefore(";"))
        assertEquals("hi", message.preview)
        val messageToMom = prepare(rig, "phone_message_draft", obj("to" to "0501234567", "body" to "hi")).card
        assertEquals(isolated("0501234567"), messageToMom.target)
        assertEquals("Write to Mom", messageToMom.headline.text())
    }

    // ---- activity log ----

    @Test fun everyRequestLeavesAnActivityLine() {
        val rig = ready()
        run(rig, "phone_timer_set", obj("seconds" to 60), decision = Approved)
        run(rig, "phone_timer_set", obj("seconds" to 60), decision = Declined)
        run(rig, "phone_calendars", obj())
        run(rig, "phone_alarm_set", obj("hour" to 99, "minute" to 0), decision = Approved)
        rig.settings.on -= PhoneCapability.Contacts
        run(rig, "phone_contacts_search", obj("query" to "x"), PhoneOrigin.Overlay)
        assertEquals(listOf("done", "denied", "done", "invalid", "off"), rig.log.entries.map { it.outcome })
        assertEquals(listOf(PhoneOrigin.LiveCall, PhoneOrigin.LiveCall, PhoneOrigin.LiveCall, PhoneOrigin.LiveCall, PhoneOrigin.Overlay), rig.log.entries.map { it.origin })
        assertEquals("Start a timer for 1 min", rig.log.entries.first().line.text())
        assertEquals("phone_timer_set", rig.log.entries.first().tool)
    }

    // ---- results ----

    @Test fun aLongResultIsTrimmedToFitAndSaysSo() {
        val rig = ready()
        val at = java.time.Instant.parse(start).toEpochMilli()
        repeat(50) { n -> rig.calendar.events[n + 10L] = EventInfo(n + 10L, 1, "T".repeat(200), at + n, at + n + 1, false, "L".repeat(200), "N".repeat(500), listOf(1, 2, 3, 4, 5)) }
        val result = run(rig, "phone_calendar_list", obj("from" to "2025-11-04T00:00:00Z", "to" to "2025-11-05T00:00:00Z", "limit" to 50))
        assertTrue(result.toString().length <= PhoneLimits.RESULT_CHARS)
        assertTrue(result.getJSONArray("events").length() < 50)
        assertTrue(result.getBoolean("truncated"))
    }

    // ---- cards show the whole notes and message ----

    private fun prepare(rig: Rig, name: String, args: JSONObject, origin: PhoneOrigin = PhoneOrigin.LiveCall) = runBlocking { rig.engine.prepare(name, args, origin) }

    @Test fun aCardShowsTheWholeNotesAndTheWholeMessage() {
        val rig = ready()
        val notes = "n".repeat(500)
        assertEquals(notes, prepare(rig, "phone_calendar_create", obj("title" to "Dentist", "start" to start, "notes" to notes)).card.preview)
        assertEquals(notes, prepare(rig, "phone_reminder_create", obj("title" to "Pills", "at" to start, "notes" to notes)).card.preview)
        rig.calendar.events[5] = EventInfo(5, 1, "Dentist", java.time.Instant.parse(start).toEpochMilli(), java.time.Instant.parse("2025-11-04T16:00:00Z").toEpochMilli(), false, "", "", emptyList())
        val update = prepare(rig, "phone_calendar_update", obj("id" to "5", "notes" to notes)).card
        assertEquals(notes, update.preview)
        assertTrue("the notes are not also cut into a line", update.lines.none { it.text().contains("nnnnnnnn") })
        val cleared = prepare(rig, "phone_calendar_update", obj("id" to "5", "notes" to "")).card
        assertEquals("", cleared.preview)
        assertTrue(cleared.lines.any { it.template == "No notes" })
        val body = "b".repeat(1000)
        assertEquals(body, prepare(rig, "phone_message_draft", obj("to" to "+966501234567", "body" to body)).card.preview)
    }

    // ---- nothing about the phone is told before the tap ----

    @Test fun beforeTheTapAChangeThePhoneCannotShowGetsOneNeutralAnswer() {
        val rig = ready()
        rig.calendar.events[8] = EventInfo(8, 2, "Holiday", 1_000_000, 2_000_000, true, "", "", emptyList())
        rig.calendar.events[6] = EventInfo(6, 1, "Standup", 1_000_000, 2_000_000, false, "", "", emptyList(), repeating = true)
        fun failure(name: String, args: JSONObject) = runBlocking { try { rig.engine.prepare(name, args, PhoneOrigin.LiveCall); null } catch (failure: PhoneFailure) { failure.code to failure.message } }
        val unknown = failure("phone_calendar_delete", obj("id" to "404"))
        assertEquals("not_found", unknown!!.first)
        // An event that is there but in a read-only calendar, a repeating event's time, an unknown or read-only calendar and a contact that is
        // missing or has no number all read exactly like an event that does not exist.
        for ((name, args) in listOf(
            "phone_calendar_delete" to obj("id" to "8"),
            "phone_calendar_update" to obj("id" to "404", "title" to "X"),
            "phone_calendar_update" to obj("id" to "6", "start" to start),
            "phone_calendar_create" to obj("title" to "X", "start" to start, "calendar_id" to "2"),
            "phone_calendar_create" to obj("title" to "X", "start" to start, "calendar_id" to "99"),
            "phone_reminder_create" to obj("title" to "X", "at" to start, "calendar_id" to "99"),
            "phone_call" to obj("contact_id" to "404"),
            "phone_call" to obj("contact_id" to "9"),
        )) assertEquals("$name $args", unknown, failure(name, args))
        // A plain mistake is still reported as it is.
        assertEquals("invalid", failure("phone_calendar_delete", obj("id" to "x"))!!.first)
        assertEquals("invalid", failure("phone_calendar_update", obj("id" to "5"))!!.first)
        assertEquals("invalid", failure("phone_call", obj())!!.first)
        // A found event still gets its card.
        assertEquals("Delete 'Standup' Thu 1 Jan 12:16 AM – 12:33 AM; Every repeat is deleted", prepare(rig, "phone_calendar_delete", obj("id" to "6")).card.describe())
    }

    // ---- a number that only looks like a saved contact ----

    @Test fun aNumberThatOnlyLooksLikeASavedContactIsNotOne() {
        val rig = ready()
        rig.contacts.people += ContactInfo("12", "Mom", listOf(ContactPhone("Mobile", "050 123 4567")))
        fun card(number: String) = prepare(rig, "phone_call", obj("number" to number)).card
        // The phone's own lookup offers Mom for a number with another country code. The card must not say "Call Mom" or count down for it.
        assertTrue("Mom" in rig.contacts.matches("+990501234567").map { it.name })
        val lookalike = card("+990501234567")
        assertFalse(lookalike.savedContact)
        assertEquals("Call +990501234567", lookalike.describe())
        assertEquals("", lookalike.target)
        assertEquals(0, PhoneTools.countdownSeconds("phone_call", PhoneOrigin.LiveCall, lookalike.savedContact))
        val same = card("0501234567")
        assertTrue(same.savedContact)
        assertEquals("Call Mom", same.headline.text())
        assertEquals("\u20660501234567\u2069", same.target)
        assertEquals(3, PhoneTools.countdownSeconds("phone_call", PhoneOrigin.LiveCall, same.savedContact))
        // The spacing a contact was saved with does not matter, the digits do.
        assertTrue(card("(050) 123-4567").savedContact)
        assertFalse(card("0501234568").savedContact)
        // The same strictness for a message: no name for a look-alike.
        assertEquals("Write to +990501234567", prepare(rig, "phone_message_draft", obj("to" to "+990501234567", "body" to "hi")).card.lines.first().text())
        assertEquals("Write to Mom 0501234567", prepare(rig, "phone_message_draft", obj("to" to "0501234567", "body" to "hi")).card.lines.first().text())
    }

    // ---- text from outside is made safe ----

    @Test fun aCardNeverShowsHiddenOrBidirectionalCharactersLineBreaksOrAHugeName() {
        val rig = ready()
        val evil = "Den‮tist​⁦ now⁩\nline2\r\n"
        val create = prepare(rig, "phone_calendar_create", obj("title" to evil, "start" to start, "location" to "Cl‮inic\n2", "notes" to "a‮b\nc"))
        assertEquals("Add 'Dentist now line2' Tue 4 Nov 3:00 PM – 4:00 PM to Personal", create.card.lines.first().text())
        assertEquals("Place: Clinic 2", create.card.lines[1].text())
        assertEquals("ab\nc", create.card.preview)
        // What is saved is what the card showed.
        assertEquals("Dentist now line2", create.runningArguments().getString("title"))
        run(rig, "phone_calendar_create", obj("title" to evil, "start" to start), decision = Approved)
        assertEquals("Dentist now line2", rig.calendar.inserted.single().title)
        // Names that come from the phone itself are cleaned and clipped too.
        rig.calendar.calendars[0] = CalendarInfo(1, "Per‮sonal ".repeat(40), "Owner account", writable = true, primary = true)
        rig.calendar.events[5] = EventInfo(5, 1, "A‮" + "x".repeat(300), java.time.Instant.parse(start).toEpochMilli(), java.time.Instant.parse("2025-11-04T16:00:00Z").toEpochMilli(), false, "", "", emptyList())
        val delete = prepare(rig, "phone_calendar_delete", obj("id" to "5")).card.lines.first()
        assertEquals(120, delete.args[0].length)
        assertTrue(delete.args[0].endsWith("…") && delete.args[0].none { it == '‮' })
        val calendarName = prepare(rig, "phone_calendar_create", obj("title" to "T", "start" to start)).card.lines.first().args[2]
        assertTrue(calendarName.length <= 120 && calendarName.none { it == '‮' })
    }

    // ---- all-day dates are the owner's calendar dates ----

    @Test fun anAllDayEventKeepsTheOwnersCalendarDateInAnyTimeZone() {
        for (zone in listOf("Asia/Riyadh", "America/New_York", "Pacific/Auckland", "UTC")) {
            val rig = Rig(zone).also { it.settings.everything() }
            // A date, or a date-time in any offset, names the day as written.
            for (text in listOf("2025-11-10", "2025-11-10T00:00:00+03:00", "2025-11-10T00:00:00Z", "2025-11-10T23:30:00-05:00")) {
                run(rig, "phone_calendar_create", obj("title" to "Trip", "start" to text, "end" to "2025-11-11", "all_day" to true), decision = Approved)
                val event = rig.calendar.inserted.last()
                assertEquals("$zone $text", java.time.LocalDate.of(2025, 11, 10), PhoneTime.utcDate(event.startMillis))
                assertEquals("$zone $text", java.time.LocalDate.of(2025, 11, 12), PhoneTime.utcDate(event.endMillis))
                assertTrue(event.allDay && event.timeZone == "UTC")
            }
            // The list gives the same dates back, one day earlier or later in no zone.
            val listed = run(rig, "phone_calendar_list", obj("from" to "2025-11-01T00:00:00Z", "to" to "2025-12-01T00:00:00Z")).getJSONArray("events").getJSONObject(0)
            assertEquals(zone, "2025-11-10", listed.getString("start"))
            assertEquals(zone, "2025-11-11", listed.getString("end"))
            assertTrue(listed.getBoolean("all_day"))
            // The card says the same days.
            assertEquals(zone, "Add 'Trip' Mon 10 Nov – Tue 11 Nov to Personal",
                prepare(rig, "phone_calendar_create", obj("title" to "Trip", "start" to "2025-11-10", "end" to "2025-11-11", "all_day" to true)).card.lines.first().text())
        }
    }

    @Test fun movingAnAllDayEventKeepsItsDatesInAZoneAheadOfUtc() {
        val rig = Rig("Asia/Riyadh").also { it.settings.everything() }
        val first = PhoneTime.utcMidnight(java.time.LocalDate.of(2025, 11, 10))
        rig.calendar.events[5] = EventInfo(5, 1, "Trip", first, first + 2 * 86_400_000L, true, "", "", emptyList())
        run(rig, "phone_calendar_update", obj("id" to "5", "start" to "2025-11-20T00:00:00+03:00", "end" to "2025-11-21T00:00:00+03:00"), decision = Approved)
        val patch = rig.calendar.updated.single().second
        assertEquals(java.time.LocalDate.of(2025, 11, 20), PhoneTime.utcDate(patch.startMillis!!))
        assertEquals(java.time.LocalDate.of(2025, 11, 22), PhoneTime.utcDate(patch.endMillis!!))
        assertEquals("UTC", patch.timeZone)
    }

    // ---- a card that was cancelled before it ran, and one that had already run ----

    @Test fun aCardWhoseConversationEndedBeforeTheTapIsLoggedCancelledButNotOneThatAlreadyRan() {
        val rig = ready()
        val waiting = runBlocking { rig.engine.prepare("phone_timer_set", obj("seconds" to 30), PhoneOrigin.LiveCall) }
        rig.engine.recordCancelled(waiting, PhoneOrigin.LiveCall)
        assertEquals(listOf("cancelled"), rig.log.entries.map { it.outcome })
        rig.log.entries.clear()
        val ran = runBlocking { rig.engine.prepare("phone_timer_set", obj("seconds" to 40), PhoneOrigin.LiveCall) }
        assertTrue(runBlocking { rig.engine.runPrepared(ran, PhoneOrigin.LiveCall, Approved) }.getBoolean("set"))
        rig.engine.recordCancelled(ran, PhoneOrigin.LiveCall)
        assertEquals("done is the only line", listOf("done"), rig.log.entries.map { it.outcome })
    }

    // ---- an action that started is logged as done ----

    @Test fun anActionThatHasStartedIsLoggedDoneEvenIfTheConversationIsCancelled() {
        val rig = ready()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        rig.actions.duringTimer = { started.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val engine = PhoneEngine(rig.settings, rig.calendar, rig.contacts, rig.actions, rig.log, PhoneRateLimiter({ rig.now }), { rig.now },
                { java.time.ZoneId.of("UTC") }, { java.util.Locale.US }, pool.asCoroutineDispatcher()) { false }
            runBlocking {
                val job = launch(Dispatchers.Default) { engine.run("phone_timer_set", obj("seconds" to 5), PhoneOrigin.LiveCall, Approved) }
                withContext(Dispatchers.IO) { started.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                job.cancel()
                release.countDown()
                job.join()
            }
            assertEquals(listOf(5), rig.actions.timers)
            assertEquals(listOf("done"), rig.log.entries.map { it.outcome })
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
