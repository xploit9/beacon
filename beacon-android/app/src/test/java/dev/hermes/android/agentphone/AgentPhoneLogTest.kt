package dev.hermes.android.agentphone

import dev.hermes.android.data.parseAgentPhoneActivity
import dev.hermes.android.phone.PhoneDayName
import dev.hermes.android.phone.PhoneLine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** "What {name} did on its phone": the rows the plugin writes (approvals-v1 §3, §6, §6b; L3's tool in emu-join) and how they read. */
class AgentPhoneLogTest {
    @Test fun anOwnersHoldNamesTheReasonInTheActivityLog() {
        val held = dev.hermes.android.data.AgentPhoneActivity(1, "", "open_app", "", false, "phone_in_use")
        assertEquals("You're using the phone", logLine(held).outcome)
    }

    private val zone = ZoneId.of("Europe/London")
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 11, day, hour, 5, 0, 0, zone).toEpochSecond()
    private fun row(seq: Int, at: Long, app: String, action: String, label: String, approved: Any, outcome: String) =
        JSONObject().put("seq", seq).put("at", at).put("app", app).put("action", action).put("label", label).put("approved", approved).put("outcome", outcome)

    private val rows = parseAgentPhoneActivity(JSONObject().put("activity", JSONArray(listOf(
        row(7, at(17, 9), "Kanto Foods", "tap", "Place order", true, "done"),
        row(6, at(17, 8), "Messages", "tap", "Send", false, "declined"),
        row(5, at(16, 20), "Android", "answer", "Allow USB debugging?", false, "said_no"),
        row(4, at(16, 19), "Kanto Foods", "save", "Saved: Kanto Foods · k${'@'}example.com", true, "saved"),
        row(3, at(15, 12), "Kanto Foods", "fill", "payment", false, "filled"),
        row(2, at(15, 11), "Android", "save", "Save the login to Beacon", false, "asked_owner"),
        row(1, at(15, 10), "Settings", "tap", "", 0, "blocked"),
        JSONObject().put("at", at(15, 9)).put("app", "no seq"),
        JSONObject().put("seq", 9).put("app", "no time"),
    )))).filter { it.seq > 0 }

    @Test fun aRowWithoutATimeIsLeftOutAndOneWithoutANumberCantBeNamedByANotice() {
        val all = parseAgentPhoneActivity(JSONObject().put("activity", JSONArray(listOf(
            JSONObject().put("at", at(15, 9)).put("app", "no seq"), JSONObject().put("seq", 9).put("app", "no time")))))
        assertEquals(listOf(0L), all.map { it.seq })
    }

    @Test fun rowsAreReadWithTheirNumberInTheServersOrderNewestFirst() {
        assertEquals(listOf(7L, 6, 5, 4, 3, 2, 1), rows.map { it.seq })
        val first = rows.first()
        assertEquals(at(17, 9) * 1000, first.atMillis)
        assertEquals(listOf("Kanto Foods", "tap", "Place order", "done"), listOf(first.app, first.action, first.label, first.outcome))
        assertTrue(first.approved)
        assertFalse(rows.last().approved)
    }

    @Test fun eachRowReadsAsTheOwnerWouldSayIt() {
        val lines = rows.associate { it.seq to logLine(it) }
        // Idea 6: a done row says what was done and where, and only that ("Tapped Place order at Kanto Foods", not "Ordered").
        assertEquals(LogLine(PhoneLine("Tapped {0} at {1}", listOf("Place order", "Kanto Foods")), "", "", appInTitle = true), lines[7])
        // A row that didn't run names the control and where, and its outcome; it never says it tapped.
        assertEquals(LogLine(PhoneLine("{0} at {1}", listOf("Send", "Messages")), "", "Not allowed", appInTitle = true), lines[6])
        // Android's debugging question, without its jargon (P3-4); the sentence already says it was blocked.
        assertEquals(LogLine(PhoneLine("Blocked an app that tried to take control"), "", ""), lines[5])
        // A save row is its own sentence: the app is not put in front of it, and "Saved" isn't said twice.
        assertEquals(LogLine(PhoneLine("Saved: {0} · {1}", listOf("Kanto Foods", "k${'@'}example.com")), "", ""), lines[4])
        assertEquals(LogLine(PhoneLine("Filled a saved card"), "", ""), lines[3])
        // A fill the tool wrote as plainly done reads the same: no "Done" after a sentence that already says what happened.
        assertEquals(LogLine(PhoneLine("Filled a saved login"), "", ""), logLine(rows.first { it.seq == 3L }.copy(label = "login", outcome = "done")))
        assertEquals(LogLine(PhoneLine("Save the login to Beacon"), "", "Asked you"), lines[2])
        assertEquals(LogLine(PhoneLine("Tapped"), "", "Blocked"), lines[1])
        for (outcome in listOf("done", "declined", "blocked", "said_no", "asked_owner", "not_saved", "saved", "filled")) assertTrue(outcome, logOutcomeWords(outcome).isNotBlank())
        assertEquals("", logOutcomeWords("something_new"))
    }

    @Test fun theResultFilterAndTheSearchNarrowTheList() {
        val text: (AgentPhoneLogRow) -> String = { row -> logLine(row).title.text() + " " + row.app }
        assertEquals(listOf(7L, 4), filterLog(rows, LogFilter(result = LogResult.Approved), text).map { it.seq })
        assertEquals(listOf(7L, 4, 3), filterLog(rows, LogFilter(result = LogResult.Done), text).map { it.seq })
        assertEquals(listOf(6L, 5, 2, 1), filterLog(rows, LogFilter(result = LogResult.NotDone), text).map { it.seq })
        assertEquals(listOf(7L, 4, 3), filterLog(rows, LogFilter("kanto"), text).map { it.seq })
        assertEquals(listOf(7L), filterLog(rows, LogFilter("ORDER kanto"), text).map { it.seq })
        assertTrue(filterLog(rows, LogFilter("nothing like it"), text).isEmpty())
        assertFalse(LogFilter().active)
        assertTrue(LogFilter(" x").active)
    }

    @Test fun rowsAreGroupedByTheDayTheyHappenedOnInThePhonesZone() {
        val days = groupLogByDay(rows, zone)
        assertEquals(listOf(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 15)), days.map { it.day })
        assertEquals(listOf(listOf(7L, 6), listOf(5L, 4), listOf(3L, 2, 1)), days.map { day -> day.rows.map { it.seq } })
        val today = LocalDate.of(2026, 11, 17)
        assertEquals(listOf(PhoneDayName.Today, PhoneDayName.Yesterday, PhoneDayName.Date), days.map { logDayName(it.day, today) })
    }

    @Test fun everyActionThePluginWritesHasAVerb() {
        for (action in listOf("tap", "long_press", "swipe", "type", "key", "fill", "save", "answer", "open_link", "back", "home", "recents"))
            assertTrue(action, AgentPhoneLogVerbs[action].orEmpty().isNotBlank())
        for (word in AgentPhoneLogVerbs.values) assertTrue(word in AgentPhoneLogWords)
    }

    @Test fun anOpenedAppALinkAndTheNavigationKeysReadAsWords() {
        // D2b: the plugin's open_app row carries the app's name; a link's row never names its address; the navigation keys use the
        // words of the phone view's own bar ("Home screen", not "Home", which is the Files page's home folder).
        val read = { action: String, app: String -> logLine(parseAgentPhoneActivity(JSONObject().put("activity", JSONArray(listOf(
            row(1, at(17, 9), app, action, "", false, "done"))))).single()) }
        assertEquals(LogLine(PhoneLine("Opened {0}", listOf("Clock")), "", "", appInTitle = true), read("open_app", "Clock"))
        assertEquals(LogLine(PhoneLine("Opened a link"), "", ""), read("open_link", ""))
        assertEquals(LogLine(PhoneLine("Back"), "", ""), read("back", ""))
        assertEquals(LogLine(PhoneLine("Home screen"), "", ""), read("home", ""))
        assertEquals(LogLine(PhoneLine("Recent apps"), "", ""), read("recents", ""))
        assertEquals(LogLine(PhoneLine("Used the phone"), "", ""), read("open_app", ""))
        assertTrue("Opened {0}" in AgentPhoneLogWords)
    }

    // Q2 R3-F3: rows read "com.google.android.apps.nexuslauncher · swipe". The plugin names the launcher by its fixed word, which every
    // row, card and notice shows in the owner's language; an app's own name is never translated.
    @Test fun theHomeScreenIsNamedInTheOwnersLanguageAndAnAppByItsOwnName() {
        val arabic = { text: String -> if (text == "Home screen") "الشاشة الرئيسية" else text }
        assertEquals("الشاشة الرئيسية", appWords(HOME_SCREEN, arabic))
        assertEquals("Clock", appWords("Clock", arabic))
        assertTrue(HOME_SCREEN in AgentPhoneLogWords)  // the words test holds it to every language
        val row = parseAgentPhoneActivity(JSONObject().put("activity", JSONArray(listOf(row(9, at(17, 10), HOME_SCREEN, "swipe", "workspace", false, "done"))))).single()
        val shown = logLine(row.copy(app = appWords(row.app, arabic)))
        assertEquals(listOf("workspace"), shown.title.args)
        assertFalse("com.google" in shown.title.text())
    }
}
