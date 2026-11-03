package dev.hermes.android.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Rig2 = Rig

/** Hermes phone requests: parsing, the owner's tap for writes, expiry, single run and the off switches, with fake tools and a fake plugin. */
class HermesPhoneRequestsTest {
    private val reads = setOf("phone_calendars", "phone_calendar_list", "phone_contacts_search")
    private val all = reads + setOf("phone_calendar_create", "phone_calendar_update", "phone_calendar_delete", "phone_reminder_create",
        "phone_alarm_set", "phone_timer_set", "phone_call", "phone_message_draft")

    private class FakeTools(val all: Set<String>, val reads: Set<String>) : PhoneToolPort {
        var access = true
        var ask = true
        var enabledNow: Set<String> = all
        var failRun = false
        var failPrepareFor: String? = null
        /** Tools the phone would refuse, by the failure it gives. */
        val refused = mutableMapOf<String, PhoneFailure>()
        var answer: JSONObject = JSONObject().put("ok", true)
        val runs = mutableListOf<Triple<String, JSONObject, PhoneDecision?>>()
        /** The cards the owner tapped on that were run as they were shown. */
        val ranPrepared = mutableListOf<PhonePrepared>()
        val prepared = mutableListOf<String>()
        /** What the activity list was told: tool, the line's text and the outcome. */
        val records = mutableListOf<Triple<String, String, String>>()
        /** When set, a card's lookups and the activity list must be reached from a thread whose name starts with this, never from the caller's. */
        var onlyOn: String? = null
        private fun guard(what: String) { onlyOn?.let { check(Thread.currentThread().name.startsWith(it)) { "$what ran on ${Thread.currentThread().name}" } } }
        override val names get() = all
        override fun isWrite(name: String) = name !in reads
        override fun enabled() = enabledNow
        override fun hermesAccess() = access
        override fun askBeforeReads() = ask
        override suspend fun prepare(name: String, args: JSONObject): PhonePrepared {
            guard("prepare")
            if (name == failPrepareFor) error("cannot describe")
            refused[name]?.let { throw it }
            prepared += name
            return PhonePrepared(name, args, PhoneCard(listOf(PhoneLine("{0}", listOf("$name ${args.optString("title")}".trim())))))
        }
        override suspend fun run(name: String, args: JSONObject, decision: PhoneDecision?): JSONObject {
            runs += Triple(name, args, decision)
            if (failRun) error("boom")
            return answer
        }
        override suspend fun runPrepared(prepared: PhonePrepared, decision: PhoneDecision?): JSONObject {
            ranPrepared += prepared
            return run(prepared.name, prepared.runningArguments(), decision)
        }
        override fun record(name: String, line: PhoneLine, outcome: String) { guard("record"); records += Triple(name, line.text(), outcome) }
        override fun plainLine(name: String) = PhoneLine("Plain $name")
    }

    private class FakeLink(val events: MutableList<String>) : PhoneLink {
        var page = JSONObject().put("requests", JSONArray())
        var refuse: PhoneRequestGone? = null
        var failResults = 0
        /** The next this-many answers to the plugin fail with a network error. */
        var failDecide = 0
        val decisions = mutableListOf<Pair<String, Boolean>>()
        val results = mutableListOf<Pair<String, JSONObject>>()
        val states = mutableListOf<JSONObject>()
        override suspend fun pending() = page
        override suspend fun decide(id: String, approved: Boolean) {
            refuse?.let { throw it }
            if (failDecide > 0) { failDecide--; throw java.io.IOException("offline") }
            decisions += id to approved
            events += "decide:$approved"
        }
        override suspend fun result(id: String, result: JSONObject) {
            if (failResults > 0) { failResults--; throw java.io.IOException("offline") }
            results += id to result
            events += "result"
        }
        override suspend fun state(state: JSONObject) { states += state }
    }

    private class Rig(val tools: FakeTools, val link: FakeLink, val seen: MutableMap<String, Long>, val clockValue: LongArray, val saved: IntArray) {
        var unlocked = true
        var canSave = true
        /** The threads that saved a handled id. */
        val savedOn = mutableListOf<String>()
        var io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined
        fun handled() = HandledRequests(seen, { saved[0]++; savedOn += Thread.currentThread().name; canSave }, { clockValue[0] })
        fun engine(handled: HandledRequests = handled()) = HermesPhoneEngine(tools, link, handled, { clockValue[0] }, { unlocked }, pause = {}, io = io)
    }

    private fun rig(): Rig {
        val events = mutableListOf<String>()
        val tools = FakeTools(all, reads)
        val link = FakeLink(events)
        return Rig(tools, link, HashMap(), longArrayOf(1_000_000L), intArrayOf(0))
    }
    private val Rig.events get() = link.events

    private fun id(n: Int) = n.toString(16).padStart(32, '0')
    private fun row(n: Int, tool: String, seconds: Int = 100, args: JSONObject = JSONObject().put("title", "Dentist")) =
        JSONObject().put("id", id(n)).put("tool", tool).put("args", args).put("expires_in_seconds", seconds)
    private fun page(vararg rows: JSONObject) = JSONObject().put("requests", JSONArray(rows.toList()))

    // ---- parsing ---------------------------------------------------------------------------------------------------------

    @Test fun readsOnlyWellFormedRequests() {
        val now = 5_000L
        val good = HermesPhoneRequest.parse(row(1, "phone_timer_set", 90, JSONObject().put("seconds", 60)), now, all)
        assertNotNull(good)
        assertEquals(id(1), good!!.id)
        assertEquals("phone_timer_set", good.tool)
        assertEquals(now + 90_000, good.deadline)
        assertEquals(60, good.args.getInt("seconds"))
        assertEquals(90, good.secondsLeft(now))
        assertEquals(0, good.secondsLeft(now + 200_000))
        // The most a request can wait is two minutes, whatever the plugin says.
        assertNull(HermesPhoneRequest.parse(row(2, "phone_timer_set", 121), now, all))
        assertEquals(now + 120_000, HermesPhoneRequest.parse(row(2, "phone_timer_set", 120), now, all)!!.deadline)
        val withoutArgs = row(3, "phone_timer_set").also { it.remove("args") }
        val withoutTime = row(3, "phone_timer_set").also { it.remove("expires_in_seconds") }
        for (bad in listOf(
            row(3, "phone_timer_set", 0), row(3, "phone_timer_set", -5), row(3, "format_disk"), row(3, "phone_timer_set").put("id", "NOT-AN-ID"),
            row(3, "phone_timer_set").put("id", id(3) + "0"), row(3, "phone_timer_set").put("args", "text"), withoutArgs, withoutTime,
            row(3, "phone_timer_set").put("args", JSONObject().put("body", "x".repeat(9000))),
        )) assertNull(bad.toString(), HermesPhoneRequest.parse(bad, now, all))
    }

    @Test fun takesAtMostEightDistinctRequests() {
        val rows = (1..12).map { row(it, "phone_calendars") } + row(1, "phone_calendars")
        val parsed = HermesPhoneRequest.parseAll(JSONObject().put("requests", JSONArray(rows)), 0, all)
        assertEquals((1..8).map { id(it) }, parsed.map { it.id })
        assertTrue(HermesPhoneRequest.parseAll(JSONObject(), 0, all).isEmpty())
        assertTrue(HermesPhoneRequest.parseAll(JSONObject().put("requests", "x"), 0, all).isEmpty())
    }

    // ---- the tap ---------------------------------------------------------------------------------------------------------

    @Test fun aWriteWaitsForTheTapEvenWhenReadsAreNotAsked() = runBlocking {
        val rig = rig(); rig.tools.ask = false
        rig.link.page = page(row(1, "phone_calendar_create"), row(2, "phone_call", args = JSONObject().put("number", "+15550100")), row(3, "phone_message_draft"))
        val cards = rig.engine().refresh()
        assertEquals(listOf(id(1), id(2), id(3)), cards.map { it.request.id })
        assertTrue(rig.tools.runs.isEmpty())
        assertTrue(rig.link.decisions.isEmpty())
        assertEquals("phone_calendar_create Dentist", cards[0].card.describe())
    }

    @Test fun aReadWaitsForTheTapWhileAskingAndRunsAtOnceWhenNot() = runBlocking {
        val rig = rig()
        rig.link.page = page(row(1, "phone_calendar_list"))
        assertEquals(1, rig.engine().refresh().size)
        assertTrue(rig.tools.runs.isEmpty())
        rig.tools.ask = false
        val cards = rig.engine().refresh()
        assertTrue(cards.isEmpty())
        // No tap, so no approval is passed: the read runs on the owner's standing choice.
        assertEquals(1, rig.tools.runs.size)
        assertEquals("phone_calendar_list", rig.tools.runs[0].first)
        assertNull(rig.tools.runs[0].third)
        assertTrue("a read with no tap has no card to run", rig.tools.ranPrepared.isEmpty())
        assertEquals(listOf("decide:true", "result"), rig.events)
        assertEquals(JSONObject().put("ok", true).toString(), rig.link.results.single().second.toString())
    }

    @Test fun theTapRunsTheCardThatWasShownAndDoesNotLookTheRequestUpAgain() = runBlocking {
        val rig = rig()
        rig.link.page = page(row(1, "phone_calendar_create"))
        val engine = rig.engine()
        val card = engine.refresh().single()
        assertEquals(PhoneOutcome.Ran, engine.approve(card))
        // What ran is the very card the owner tapped on, so the tap and the action cannot differ.
        assertSame(card.prepared, rig.tools.ranPrepared.single())
        assertEquals("the card was made once, before the tap", listOf("phone_calendar_create"), rig.tools.prepared)
        assertEquals(PhoneDecision(true), rig.tools.runs.single().third)
    }

    @Test fun anApprovedWriteRunsWithTheOwnersApprovalAndReportsItsResult() = runBlocking {
        val rig = rig()
        rig.tools.answer = JSONObject().put("id", "42")
        rig.link.page = page(row(1, "phone_calendar_create"))
        val engine = rig.engine()
        val card = engine.refresh().single()
        assertEquals(PhoneOutcome.Ran, engine.approve(card))
        assertEquals(PhoneDecision(true), rig.tools.runs.single().third)
        // The plugin hears the claim before the phone acts, then the result.
        assertEquals(listOf("decide:true", "result"), rig.events)
        assertEquals("42", rig.link.results.single().second.getString("id"))
    }

    @Test fun aLockedPhoneCannotAllowAndKeepsTheRequestForLater() = runBlocking {
        val rig = rig(); rig.unlocked = false
        rig.link.page = page(row(1, "phone_alarm_set"))
        val engine = rig.engine()
        val card = engine.refresh().single()
        assertEquals(PhoneOutcome.Locked, engine.approve(card))
        assertTrue(rig.tools.runs.isEmpty() && rig.link.decisions.isEmpty())
        assertFalse(rig.seen.containsKey(card.request.id))
        rig.unlocked = true
        assertEquals(PhoneOutcome.Ran, engine.approve(card))
        assertEquals(1, rig.tools.runs.size)
    }

    @Test fun denyingNeverRunsAndAnswersOnce() = runBlocking {
        val rig = rig()
        rig.link.page = page(row(1, "phone_calendar_delete", args = JSONObject().put("id", "9")))
        val engine = rig.engine()
        val card = engine.refresh().single()
        assertEquals(PhoneOutcome.Denied, engine.deny(card.request))
        assertEquals(PhoneOutcome.Handled, engine.deny(card.request))
        assertEquals(PhoneOutcome.Handled, engine.approve(card))
        assertEquals(listOf(id(1) to false), rig.link.decisions)
        assertTrue(rig.tools.runs.isEmpty() && rig.link.results.isEmpty())
    }

    // ---- expiry and single run -----------------------------------------------------------------------------------------------

    @Test fun anExpiredRequestIsNeverRunLate() = runBlocking {
        val rig = rig()
        rig.link.page = page(row(1, "phone_timer_set", 100))
        val engine = rig.engine()
        val card = engine.refresh().single()
        rig.clockValue[0] += 100_000
        assertEquals(PhoneOutcome.Expired, engine.approve(card))
        assertTrue(rig.tools.runs.isEmpty() && rig.link.decisions.isEmpty())
        // The last second runs out while the card is open.
        rig.link.page = page(row(2, "phone_timer_set", 1))
        val last = rig.engine().refresh().single()
        rig.clockValue[0] += 1_000
        assertEquals(PhoneOutcome.Expired, rig.engine().approve(last))
        assertTrue(rig.tools.runs.isEmpty() && rig.link.decisions.isEmpty())
    }

    @Test fun aClaimThePluginRefusesNeverRuns() = runBlocking {
        val rig = rig()
        rig.link.page = page(row(1, "phone_timer_set"), row(2, "phone_timer_set"))
        val engine = rig.engine()
        val cards = engine.refresh()
        rig.link.refuse = PhoneRequestGone(expired = true)
        assertEquals(PhoneOutcome.Expired, engine.approve(cards[0]))
        rig.link.refuse = PhoneRequestGone(expired = false)
        assertEquals(PhoneOutcome.Gone, engine.approve(cards[1]))
        assertEquals(PhoneOutcome.Gone, engine.deny(HermesPhoneRequest(id(3), "phone_timer_set", JSONObject(), rig.clockValue[0] + 50_000)))
        assertTrue(rig.tools.runs.isEmpty())
        // Only the request the plugin said ran out of time is logged as expired; one another phone took is not this phone's doing.
        assertEquals(listOf("expired"), rig.tools.records.map { it.third })
    }

    @Test fun aRequestRunsAtMostOnceEvenAfterARestart() = runBlocking {
        val rig = rig()
        rig.link.page = page(row(1, "phone_timer_set"))
        val engine = rig.engine()
        val card = engine.refresh().single()
        assertEquals(PhoneOutcome.Ran, engine.approve(card))
        assertEquals(PhoneOutcome.Handled, engine.approve(card))
        // The app restarts: a new engine reads the saved ids and the plugin still lists the request.
        val restarted = rig.engine(rig.handled())
        assertTrue(restarted.refresh().isEmpty())
        assertEquals(PhoneOutcome.Handled, restarted.approve(card))
        assertEquals(1, rig.tools.runs.size)
        assertEquals(1, rig.link.decisions.size)
    }

    @Test fun twoTapsAtOnceRunOnce() = runBlocking {
        val rig = rig()
        rig.link.page = page(row(1, "phone_timer_set"))
        val engine = rig.engine()
        val card = engine.refresh().single()
        val outcomes = (1..8).map { Thread { runBlocking { engine.approve(card) } } }
        outcomes.forEach { it.start() }; outcomes.forEach { it.join() }
        assertEquals(1, rig.tools.runs.size)
    }

    @Test fun aRequestThatCannotBeRecordedIsNotRun() = runBlocking {
        val rig = rig(); rig.canSave = false
        rig.link.page = page(row(1, "phone_timer_set"))
        val engine = rig.engine()
        val card = engine.refresh().single()
        assertEquals(PhoneOutcome.Handled, engine.approve(card))
        assertTrue(rig.tools.runs.isEmpty() && rig.link.decisions.isEmpty())
        assertFalse(rig.handled().has(card.request.id))
    }

    @Test fun handledIdsAreKeptForAnHourAndBounded() {
        val seen = HashMap<String, Long>()
        val clock = longArrayOf(10_000_000L)
        val handled = HandledRequests(seen, { true }, { clock[0] })
        assertTrue(handled.take(id(1)))
        assertFalse(handled.take(id(1)))
        clock[0] += 3_599_000
        assertFalse(handled.take(id(1)))
        clock[0] += 2_000
        assertTrue(handled.take(id(1)))
        for (n in 100..400) { clock[0]++; handled.take(id(n)) }
        assertTrue(seen.size <= 200)
        // A clock that moved backwards cannot keep an id alive from the future.
        clock[0] -= 1_000_000
        assertTrue(handled.take(id(401)))
        assertEquals(1, seen.size)
    }

    // ---- the switches ----------------------------------------------------------------------------------------------------

    @Test fun whatIsSwitchedOffIsAnsweredOffAndNeverRun() = runBlocking {
        val rig = rig()
        rig.tools.access = false
        rig.link.page = page(row(1, "phone_calendar_list"), row(2, "phone_alarm_set"))
        assertTrue(rig.engine().refresh().isEmpty())
        assertTrue(rig.tools.runs.isEmpty())
        assertEquals(listOf(id(1) to true, id(2) to true), rig.link.decisions)
        assertTrue(rig.link.results.all { it.second.getString("error") == "off" })
        assertEquals(2, rig.link.results.size)
        // One capability off while the rest are on.
        val second = rig()
        second.tools.enabledNow = all - "phone_call"
        second.link.page = page(row(3, "phone_call", args = JSONObject().put("number", "+15550100")), row(4, "phone_timer_set"))
        val cards = second.engine().refresh()
        assertEquals(listOf(id(4)), cards.map { it.request.id })
        assertEquals("off", second.link.results.single().second.getString("error"))
        assertTrue(second.tools.runs.isEmpty())
    }

    @Test fun aSwitchTurnedOffAfterTheCardAppearedStopsTheRequest() = runBlocking {
        val rig = rig()
        rig.link.page = page(row(1, "phone_calendar_update", args = JSONObject().put("id", "5").put("title", "Moved")))
        val engine = rig.engine()
        val card = engine.refresh().single()
        rig.tools.enabledNow = all - "phone_calendar_update"
        assertEquals(PhoneOutcome.Off, engine.approve(card))
        assertTrue(rig.tools.runs.isEmpty())
        assertEquals("off", rig.link.results.single().second.getString("error"))
        rig.tools.access = false
        rig.link.page = page(row(2, "phone_timer_set"))
        val later = rig.engine().refresh()
        assertTrue(later.isEmpty() && rig.tools.runs.isEmpty())
    }

    @Test fun aToolThatFailsReportsUnavailableInsteadOfCrashing() = runBlocking {
        val rig = rig(); rig.tools.failRun = true
        rig.link.page = page(row(1, "phone_timer_set"))
        val engine = rig.engine()
        assertEquals(PhoneOutcome.Ran, engine.approve(engine.refresh().single()))
        assertEquals("unavailable", rig.link.results.single().second.getString("error"))
    }

    @Test fun theResultIsRetriedOnceAndBounded() = runBlocking {
        val rig = rig(); rig.link.failResults = 1
        rig.link.page = page(row(1, "phone_calendar_list"), row(2, "phone_calendar_list"))
        rig.tools.ask = false
        rig.engine().refresh()
        assertEquals(2, rig.link.results.size)
        val failing = rig(); failing.link.failResults = 5
        failing.link.page = page(row(1, "phone_timer_set"))
        val engine = failing.engine()
        assertEquals(PhoneOutcome.Ran, engine.approve(engine.refresh().single()))
        assertTrue(failing.link.results.isEmpty())
        val big = rig(); big.tools.ask = false
        big.tools.answer = JSONObject().put("events", "x".repeat(20_000))
        big.link.page = page(row(1, "phone_calendar_list"))
        big.engine().refresh()
        assertEquals("unavailable", big.link.results.single().second.getString("error"))
    }

    @Test fun oneRequestThatCannotBeShownDoesNotHideTheOthers() = runBlocking {
        val rig = rig(); rig.tools.failPrepareFor = "phone_call"
        rig.link.page = page(row(1, "phone_call", args = JSONObject().put("number", "+15550100")), row(2, "phone_timer_set"))
        assertEquals(listOf(id(2)), rig.engine().refresh().map { it.request.id })
    }

    // ---- what Hermes is told about this phone -----------------------------------------------------------------------------------

    @Test fun thePluginIsToldOnlyWhatChangedAndNothingForAPhoneThatNeverTurnedItOn() = runBlocking {
        val rig = rig(); rig.tools.access = false
        val engine = rig.engine()
        assertNull(engine.syncState(null))
        assertTrue(rig.link.states.isEmpty())
        rig.tools.access = true; rig.tools.ask = false; rig.tools.enabledNow = setOf("phone_timer_set", "phone_call", "not_a_tool")
        val sent = engine.syncState(null)
        assertNotNull(sent)
        val state = rig.link.states.single()
        assertTrue(state.getBoolean("hermes_access"))
        assertFalse(state.getBoolean("ask_reads"))
        assertEquals(listOf("phone_call", "phone_timer_set"), (0 until state.getJSONArray("tools").length()).map { state.getJSONArray("tools").getString(it) })
        assertEquals(sent, engine.syncState(sent))
        assertEquals(1, rig.link.states.size)
        rig.tools.access = false
        val off = engine.syncState(sent)
        assertEquals(2, rig.link.states.size)
        assertFalse(rig.link.states.last().getBoolean("hermes_access"))
        assertEquals(off, engine.syncState(off))
        assertEquals(2, rig.link.states.size)
    }

    // ---- the card: one builder, off the main thread, whole ----------------------------------------------------------------------

    /** The phone's own executor behind the port, so the cards come from the very code a live call uses. */
    private class EnginePort(val phone: Rig2) : PhoneToolPort {
        override val names get() = PhoneSpecs.names
        override fun isWrite(name: String) = PhoneSpecs.isWrite(name)
        override fun enabled() = phone.engine.enabledTools(PhoneOrigin.Hermes)
        override fun hermesAccess() = phone.settings.hermes
        override fun askBeforeReads() = phone.settings.askReads
        override suspend fun prepare(name: String, args: JSONObject) = phone.engine.prepare(name, args, PhoneOrigin.Hermes)
        override suspend fun run(name: String, args: JSONObject, decision: PhoneDecision?) = phone.engine.run(name, args, PhoneOrigin.Hermes, decision)
        override suspend fun runPrepared(prepared: PhonePrepared, decision: PhoneDecision?) = phone.engine.runPrepared(prepared, PhoneOrigin.Hermes, decision)
        override fun record(name: String, line: PhoneLine, outcome: String) = phone.engine.record(name, line, PhoneOrigin.Hermes, outcome)
        override fun plainLine(name: String) = PhoneLine(phone.engine.fallbackLine(name))
    }

    @Test fun aCardIsLookedUpAndRecordedOffTheCallersThreadNeverOnTheMainOne() {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "phone-io") }
        try {
            val rig = rig(); rig.io = pool.asCoroutineDispatcher(); rig.tools.onlyOn = "phone-io"
            rig.tools.refused["phone_calendars"] = PhoneFailure("invalid", "no")
            rig.link.page = page(row(1, "phone_calendar_delete", args = JSONObject().put("id", "9")), row(2, "phone_timer_set"), row(3, "phone_calendars"))
            val engine = rig.engine()
            // Called from the test's own thread, as the screen's thread would call it: any lookup, id save or log write that ran here fails.
            val cards = runBlocking { engine.refresh() }
            assertEquals(listOf(id(1), id(2)), cards.map { it.request.id })
            assertEquals(listOf("phone_calendar_delete", "phone_timer_set"), rig.tools.prepared)
            assertEquals(PhoneOutcome.Denied, runBlocking { engine.deny(cards[0].request) })
            assertEquals(PhoneOutcome.Ran, runBlocking { engine.approve(cards[1]) })
            runBlocking { engine.expired(HermesPhoneRequest(id(4), "phone_timer_set", JSONObject(), 0)) }
            // (With coroutine debugging on, a thread name carries a coroutine suffix.)
            assertTrue(rig.savedOn.isNotEmpty() && rig.savedOn.all { it.startsWith("phone-io") })
            assertEquals(listOf("invalid", "denied", "expired"), rig.tools.records.map { it.third })
        } finally { pool.shutdownNow() }
    }

    @Test fun theHermesCardIsTheCardALiveCallShowsForTheSameRequest() = runBlocking<Unit> {
        val phone = Rig2().also { it.settings.everything(); it.settings.hermes = true }
        val at = java.time.Instant.parse("2025-11-04T15:00:00Z").toEpochMilli()
        phone.calendar.events[5] = EventInfo(5, 1, "Dentist", at, at + 3_600_000, false, "", "", emptyList())
        val notes = "n".repeat(500)
        val asked = listOf(
            "phone_call" to JSONObject().put("contact_id", "7"),
            "phone_calendar_delete" to JSONObject().put("id", "5"),
            "phone_calendar_create" to JSONObject().put("title", "Dentist").put("start", "2025-11-04T15:00:00Z").put("notes", notes),
            "phone_message_draft" to JSONObject().put("to", "+966501234567").put("body", "On my way"),
        )
        val base = rig()
        base.link.page = page(*asked.mapIndexed { index, (tool, args) -> row(index + 1, tool, args = args) }.toTypedArray())
        val engine = HermesPhoneEngine(EnginePort(phone), base.link, base.handled(), { base.clockValue[0] }, { true }, pause = {})
        val cards = engine.refresh()
        assertEquals(asked.size, cards.size)
        asked.forEachIndexed { index, (tool, args) ->
            assertEquals(tool, phone.engine.prepare(tool, args, PhoneOrigin.LiveCall).card, cards[index].card)
        }
        // Not the thin quick line: the contact's name and number, the event's title and time, the whole notes.
        assertEquals("Call Mona Test", cards[0].card.headline.text())
        // The number is kept left to right wherever the card sits in a right-to-left text.
        assertEquals("\u2066+966501234567\u2069", cards[0].card.target)
        assertEquals("Delete 'Dentist' Tue 4 Nov 3:00 PM – 4:00 PM", cards[1].card.lines.first().text())
        assertEquals(notes, cards[2].card.preview)
        assertEquals("On my way", cards[3].card.preview)
    }

    // ---- a request the phone would refuse ---------------------------------------------------------------------------------------

    @Test fun aRequestThePhoneWouldRefuseIsAnsweredWithItsErrorAndGetsNoCard() = runBlocking {
        val rig = rig()
        rig.tools.refused["phone_calendar_delete"] = PhoneFailure("not_found", "That isn't available on this phone.")
        rig.link.page = page(row(1, "phone_calendar_delete", args = JSONObject().put("id", "404")), row(2, "phone_timer_set"))
        val engine = rig.engine()
        assertEquals(listOf(id(2)), engine.refresh().map { it.request.id })
        assertEquals(listOf(id(1) to true), rig.link.decisions)
        val answer = rig.link.results.single()
        assertEquals(id(1), answer.first)
        assertEquals("not_found", answer.second.getString("error"))
        assertEquals("That isn't available on this phone.", answer.second.getString("message"))
        assertTrue(rig.tools.runs.isEmpty())
        assertEquals(listOf(Triple("phone_calendar_delete", "Plain phone_calendar_delete", "not_found")), rig.tools.records)
        // Listed again, it is not answered twice, and the other request keeps its card.
        assertEquals(listOf(id(2)), engine.refresh().map { it.request.id })
        assertEquals(1, rig.link.results.size)
    }

    // ---- a network blip ---------------------------------------------------------------------------------------------------------

    @Test fun aBlipWhenAnsweringKeepsTheCardTappableAndTheRequestStillRunsOnce() = runBlocking {
        val rig = rig()
        rig.link.page = page(row(1, "phone_timer_set"))
        val engine = rig.engine()
        val card = engine.refresh().single()
        rig.link.failDecide = 1
        assertEquals(PhoneOutcome.Retry, engine.approve(card))
        assertTrue(rig.tools.runs.isEmpty() && rig.link.results.isEmpty())
        assertFalse("the request is given back", rig.handled().has(card.request.id))
        assertEquals(PhoneOutcome.Ran, engine.approve(card))
        assertEquals(1, rig.tools.runs.size)
        assertEquals(listOf(id(1) to true), rig.link.decisions)
        // One claim, one run: another tap does nothing.
        assertEquals(PhoneOutcome.Handled, engine.approve(card))
        assertEquals(1, rig.tools.runs.size)
    }

    @Test fun aBlipWhenDenyingKeepsTheCardAndALostClaimIsNotGivenBack() = runBlocking {
        val rig = rig()
        rig.link.page = page(row(1, "phone_timer_set"), row(2, "phone_timer_set"))
        val engine = rig.engine()
        val cards = engine.refresh()
        rig.link.failDecide = 1
        assertEquals(PhoneOutcome.Retry, engine.deny(cards[0].request))
        assertTrue(rig.tools.records.isEmpty())
        assertEquals(PhoneOutcome.Denied, engine.deny(cards[0].request))
        assertEquals(listOf("denied"), rig.tools.records.map { it.third })
        // A request the plugin no longer has is over: it stays taken, it is not offered for another tap.
        rig.link.refuse = PhoneRequestGone(expired = false)
        assertEquals(PhoneOutcome.Gone, engine.approve(cards[1]))
        assertTrue(rig.handled().has(cards[1].request.id))
    }

    @Test fun aGivenBackIdCanBeTakenAgainAndForgettingAnUnknownOneIsHarmless() {
        val seen = HashMap<String, Long>()
        val saves = intArrayOf(0)
        val handled = HandledRequests(seen, { saves[0]++; true }, { 5_000L })
        assertTrue(handled.take(id(1)))
        handled.forget(id(1))
        handled.forget(id(2))
        assertFalse(handled.has(id(1)))
        assertTrue(handled.take(id(1)))
        assertEquals(3, saves[0])
    }

    // ---- the activity list ---------------------------------------------------------------------------------------------------------

    @Test fun denialsSwitchedOffAndExpiredRequestsAreInTheActivityList() = runBlocking {
        val rig = rig()
        rig.link.page = page(row(1, "phone_calendar_delete", args = JSONObject().put("id", "9")), row(2, "phone_timer_set"), row(3, "phone_alarm_set"))
        val engine = rig.engine()
        val cards = engine.refresh()
        engine.deny(cards[0].request)
        rig.tools.enabledNow = all - "phone_alarm_set"
        assertEquals(PhoneOutcome.Off, engine.approve(cards[2]))
        engine.expired(cards[1].request)
        // A request that was already answered does not also read as expired.
        engine.expired(cards[0].request)
        assertEquals(listOf("denied", "off", "expired"), rig.tools.records.map { it.third })
        assertEquals(listOf("phone_calendar_delete", "phone_alarm_set Dentist", "phone_timer_set Dentist"), rig.tools.records.map { it.second })
        // Switched off before any card was shown: logged too.
        val off = rig()
        off.tools.access = false
        off.link.page = page(row(4, "phone_calendar_list"))
        off.engine().refresh()
        assertEquals(listOf("off"), off.tools.records.map { it.third })
    }
}
