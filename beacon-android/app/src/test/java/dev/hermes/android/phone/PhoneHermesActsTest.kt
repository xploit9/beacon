package dev.hermes.android.phone

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Ask before Hermes acts": which requests still need the owner's tap, and that every other safety rule holds when it is off. */
class PhoneHermesActsTest {
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }
    private fun ready() = Rig().also { it.settings.everything(); it.settings.hermes = true; it.settings.askActs = false }
    private fun run(rig: Rig, name: String, args: JSONObject, origin: PhoneOrigin = PhoneOrigin.Hermes, decision: PhoneDecision? = null) =
        runBlocking { rig.engine.run(name, args, origin, decision) }
    private val start = "2025-11-04T15:00:00Z"
    private val writes = mapOf(
        "phone_calendar_create" to obj("title" to "Dentist", "start" to start),
        "phone_reminder_create" to obj("title" to "Pills", "at" to start),
        "phone_alarm_set" to obj("hour" to 7, "minute" to 30),
        "phone_timer_set" to obj("seconds" to 600),
        "phone_message_draft" to obj("to" to "+966501234567", "body" to "Hi"),
    )

    @Test fun askingIsTheDefaultSoEveryHermesWriteStillNeedsTheTap() {
        val rig = Rig().also { it.settings.everything(); it.settings.hermes = true }
        assertTrue(rig.settings.askActs)
        for (name in PhoneTools.names.filter(PhoneTools::isWrite)) assertTrue(name, rig.engine.needsApproval(name, PhoneOrigin.Hermes))
        for ((name, args) in writes) assertEquals(name, "denied", run(rig, name, args).getString("error"))
        assertTrue(rig.calendar.inserted.isEmpty() && rig.actions.alarms.isEmpty() && rig.actions.timers.isEmpty() && rig.actions.drafts.isEmpty())
    }

    @Test fun withAskingOffEveryWriteButAPhoneCallLosesItsTapInChatsCallsAndTheAssistant() {
        val rig = ready()
        // Owner, 4 Nov: one choice for one Hermes, whether it is asked in a chat, a call or from the side button.
        for (origin in PhoneOrigin.entries) {
            for (name in writes.keys) assertFalse("$origin $name", rig.engine.needsApproval(name, origin))
            assertTrue("$origin: a phone call keeps its tap or countdown", rig.engine.needsApproval("phone_call", origin))
        }
        // The switch only counts while Hermes may use phone access (it is hidden otherwise): then calls and the assistant ask again.
        rig.settings.hermes = false
        for (origin in listOf(PhoneOrigin.LiveCall, PhoneOrigin.Overlay)) for (name in PhoneTools.names.filter(PhoneTools::isWrite)) assertTrue("$origin $name", rig.engine.needsApproval(name, origin))
        rig.settings.hermes = true
        // Reads follow their own switch.
        assertTrue(rig.engine.needsApproval("phone_calendars", PhoneOrigin.Hermes))
        rig.settings.askReads = false
        assertFalse(rig.engine.needsApproval("phone_calendars", PhoneOrigin.Hermes))
        rig.settings.askActs = true
        assertFalse("turning acting back on does not touch reads", rig.engine.needsApproval("phone_calendars", PhoneOrigin.Hermes))
        assertTrue(rig.engine.needsApproval("phone_alarm_set", PhoneOrigin.Hermes))
    }

    @Test fun aHermesChangeRunsAtOnceAndIsLoggedExactlyAsATappedOneIs() {
        val rig = ready()
        assertEquals("100", run(rig, "phone_calendar_create", writes.getValue("phone_calendar_create")).getString("id"))
        assertEquals("Dentist", rig.calendar.inserted.single().title)
        assertTrue(run(rig, "phone_reminder_create", writes.getValue("phone_reminder_create")).has("id"))
        assertTrue(run(rig, "phone_alarm_set", writes.getValue("phone_alarm_set")).getBoolean("set"))
        assertTrue(run(rig, "phone_timer_set", writes.getValue("phone_timer_set")).getBoolean("set"))
        assertTrue(run(rig, "phone_message_draft", writes.getValue("phone_message_draft")).getBoolean("opened"))
        assertEquals(listOf("phone_calendar_create", "phone_reminder_create", "phone_alarm_set", "phone_timer_set", "phone_message_draft"), rig.log.entries.map { it.tool })
        assertTrue(rig.log.entries.all { it.outcome == "done" && it.origin == PhoneOrigin.Hermes })
        // The line is the card's, with the calendar looked up, not the generic fallback.
        assertEquals("Add 'Dentist' Tue 4 Nov 3:00 PM – 4:00 PM to Personal", rig.log.entries.first().line.text())
        assertEquals(Triple(7, 30, emptyList<String>()), rig.actions.alarms.single())
        assertEquals(listOf(600), rig.actions.timers)
    }

    @Test fun aHermesCallStillNeedsItsTap() {
        val rig = ready()
        val call = obj("number" to "+966501234567")
        assertEquals("denied", run(rig, "phone_call", call).getString("error"))
        assertTrue(rig.actions.dialed.isEmpty())
        assertEquals("a no is still a no", "denied", run(rig, "phone_call", call, decision = Declined).getString("error"))
        assertTrue(run(rig, "phone_call", call, decision = Approved).getBoolean("placed"))
        assertEquals(listOf("+966501234567" to true), rig.actions.dialed)
    }

    @Test fun updateAndDeleteRunAtOnceAndAMissingEventGetsTheOneNeutralAnswer() {
        val rig = ready()
        rig.calendar.events[5] = EventInfo(5, 1, "Dentist", 1_000_000, 4_600_000, false, "", "", emptyList())
        assertEquals("5", run(rig, "phone_calendar_update", obj("id" to "5", "title" to "Doctor")).getString("id"))
        assertEquals("Doctor", rig.calendar.updated.single().second.title)
        // What is on the phone is not told by trying: a missing event and a calendar that cannot be changed read the same.
        val missing = run(rig, "phone_calendar_delete", obj("id" to "99"))
        assertEquals("not_found", missing.getString("error"))
        assertEquals("That isn't available on this phone.", missing.getString("message"))
        val readOnly = EventInfo(6, 2, "Holiday", 1_000_000, 4_600_000, false, "", "", emptyList()).also { rig.calendar.events[6] = it }
        assertEquals(missing.toString(), run(rig, "phone_calendar_update", obj("id" to readOnly.id.toString(), "title" to "x")).toString())
        assertTrue(rig.calendar.deleted.isEmpty())
        assertEquals("not_found", rig.log.entries.last().outcome)
        // A plain mistake in the arguments is still reported as it is.
        assertEquals("invalid", run(rig, "phone_calendar_update", obj("id" to "5")).getString("error"))
    }

    @Test fun nothingRunsWhileThePhoneIsLocked() {
        val rig = ready()
        rig.locked = true
        for ((name, args) in writes) {
            val answer = run(rig, name, args)
            assertEquals(name, "unavailable", answer.getString("error"))
            assertEquals("The phone is locked. Unlock it, then ask again.", answer.getString("message"))
        }
        assertTrue(rig.calendar.inserted.isEmpty() && rig.actions.alarms.isEmpty() && rig.actions.timers.isEmpty() && rig.actions.drafts.isEmpty())
        assertEquals(List(writes.size) { "locked" }, rig.log.entries.map { it.outcome })
        rig.locked = false
        assertTrue(run(rig, "phone_timer_set", writes.getValue("phone_timer_set")).getBoolean("set"))
        // A locked phone does not stop a read the owner already said needs no tap, as before.
        rig.settings.askReads = false
        rig.locked = true
        assertTrue(run(rig, "phone_calendars", obj()).has("calendars"))
    }

    @Test fun aSwitchedOffCapabilityAndAMissingAndroidPermissionStillRefuse() {
        val rig = ready()
        rig.settings.on -= PhoneCapability.Alarms
        assertEquals("off", run(rig, "phone_alarm_set", writes.getValue("phone_alarm_set")).getString("error"))
        rig.settings.allowed -= PhoneCapability.Calendar
        assertEquals("permission", run(rig, "phone_calendar_create", writes.getValue("phone_calendar_create")).getString("error"))
        assertFalse("a permission taken away also turns the switch off for good", PhoneCapability.Calendar in rig.settings.on)
        assertTrue(rig.actions.alarms.isEmpty() && rig.calendar.inserted.isEmpty())
        // Hermes access off refuses everything, with acting free or not.
        rig.settings.hermes = false
        assertEquals("off", run(rig, "phone_timer_set", writes.getValue("phone_timer_set")).getString("error"))
        assertTrue(rig.actions.timers.isEmpty())
        assertEquals(listOf("off", "permission", "off"), rig.log.entries.map { it.outcome })
    }

    @Test fun theRateLimitStillStopsAChangeThatNeedsNoTap() {
        val rig = ready()
        repeat(PhoneRateLimiter.WRITES) { assertTrue("$it", run(rig, "phone_timer_set", writes.getValue("phone_timer_set")).getBoolean("set")) }
        assertEquals("busy", run(rig, "phone_timer_set", writes.getValue("phone_timer_set")).getString("error"))
        assertEquals(PhoneRateLimiter.WRITES, rig.actions.timers.size)
        // The window is per origin and a minute long.
        rig.now += PhoneRateLimiter.WINDOW_MS + 1
        assertTrue(run(rig, "phone_timer_set", writes.getValue("phone_timer_set")).getBoolean("set"))
    }

    @Test fun aHermesCallCountsDownOnlyForASavedContactOnceAskingIsOffAndOnlyWhileTheCardIsFreshAndTheScreenUnlocked() {
        assertEquals(PhoneCountdown.SECONDS, PhoneCountdown.forHermes("phone_call", savedContact = true, asking = false))
        assertEquals("asking is on", 0, PhoneCountdown.forHermes("phone_call", savedContact = true, asking = true))
        assertEquals("not a saved contact", 0, PhoneCountdown.forHermes("phone_call", savedContact = false, asking = false))
        for (name in PhoneTools.names - "phone_call") assertEquals(name, 0, PhoneCountdown.forHermes(name, savedContact = true, asking = false))
        // The live call and the assistant keep their own rule, which never counts Hermes.
        assertEquals(0, PhoneCountdown.secondsFor("phone_call", PhoneOrigin.Hermes, savedContact = true))
        assertEquals(PhoneCountdown.SECONDS, PhoneCountdown.secondsFor("phone_call", PhoneOrigin.Overlay, savedContact = true))
        val asked = 100_000L
        assertTrue(PhoneCountdown.startsAtFirstDraw(3, asked, asked, locked = false))
        assertTrue(PhoneCountdown.startsAtFirstDraw(3, asked, asked + PhoneCountdown.STALE_MS, locked = false))
        assertFalse("a card that waited too long needs the tap", PhoneCountdown.startsAtFirstDraw(3, asked, asked + PhoneCountdown.STALE_MS + 1, locked = false))
        assertFalse("a locked phone places no call by itself", PhoneCountdown.startsAtFirstDraw(3, asked, asked, locked = true))
        assertFalse("a card with no countdown never counts", PhoneCountdown.startsAtFirstDraw(0, asked, asked, locked = false))
        assertFalse("never stamped", PhoneCountdown.startsAtFirstDraw(3, 0, 5_000, locked = false))
    }

    @Test fun callsAndTheAssistantActWithoutATapToo_andAskAgainOnceHermesMayNotUsePhoneAccess() {
        val rig = ready()
        for (origin in listOf(PhoneOrigin.LiveCall, PhoneOrigin.Overlay)) {
            assertTrue("$origin", run(rig, "phone_timer_set", writes.getValue("phone_timer_set"), origin).getBoolean("set"))
        }
        assertEquals(listOf(600, 600), rig.actions.timers)
        assertEquals(listOf(PhoneOrigin.LiveCall, PhoneOrigin.Overlay), rig.log.entries.map { it.origin })
        rig.settings.hermes = false
        for (origin in listOf(PhoneOrigin.LiveCall, PhoneOrigin.Overlay)) {
            assertEquals("$origin", "denied", run(rig, "phone_timer_set", writes.getValue("phone_timer_set"), origin).getString("error"))
        }
        assertEquals(2, rig.actions.timers.size)
    }
}
