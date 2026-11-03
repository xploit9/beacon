package dev.hermes.android.phone

import android.content.ContextWrapper
import dev.hermes.android.voice.PhoneVoiceBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Strix vuln-0014 (the brief's 0014): an owner's word that looks like a template hole ({0}, {1}, {2}) is the owner's text, never a place
 * to fill again. Every way a card line is written (the contract's one line, the line the card and the notification draw, and the
 * phone_card a live call shows) fills the holes in one pass, so the text the owner taps is the data that runs.
 */
class PhoneCardHolesTest {
    private val context = ContextWrapper(null)
    @After fun reset() { PhoneTools.useEngine(null) }
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }
    private fun ready() = Rig().also { it.settings.everything(); PhoneTools.useEngine(it.engine) }
    private fun prepare(rig: Rig, name: String, args: JSONObject) = runBlocking { rig.engine.prepare(name, args, PhoneOrigin.LiveCall) }
    private val start = "2025-11-04T15:00:00Z"
    private val open = "⁨"
    private val close = "⁩"
    private val hole = Regex("""\{(\d+)}""")
    private fun reference(line: PhoneLine, wrap: (String) -> String = { it }) =
        hole.replace(line.template) { found -> line.args.getOrNull(found.groupValues[1].toInt())?.let(wrap) ?: found.value }
    private fun isolated(text: String) = if (text.firstOrNull()?.let { it in '⁦'..'⁨' } == true) text else "$open$text$close"

    /** Every card the engine builds from owner data, with the data it carries. */
    private fun cards(rig: Rig): List<Pair<PhoneCard, List<String>>> {
        rig.calendar.calendars[0] = rig.calendar.calendars[0].copy(name = "{2}")
        rig.contacts.people += ContactInfo("11", "{1}", listOf(ContactPhone("Mobile", "+1 555 010 3000")))
        rig.calendar.events[5] = EventInfo(5, 1, "{1}", Instant.parse(start).toEpochMilli(), Instant.parse("2025-11-04T16:00:00Z").toEpochMilli(), false, "", "", emptyList())
        return listOf(
            prepare(rig, "phone_calendar_create", obj("title" to "{1}", "start" to start, "location" to "{0}")).card to listOf("{1}", "{2}", "{0}"),
            prepare(rig, "phone_calendar_create", obj("title" to "Note {2}", "start" to start)).card to listOf("Note {2}", "{2}"),
            prepare(rig, "phone_reminder_create", obj("title" to "{1}", "at" to start)).card to listOf("{1}", "{2}"),
            prepare(rig, "phone_calendar_delete", obj("id" to "5")).card to listOf("{1}"),
            prepare(rig, "phone_calendar_update", obj("id" to "5", "title" to "{0}")).card to listOf("{1}", "{0}"),
            prepare(rig, "phone_call", obj("contact_id" to "11")).card to listOf("{1}"),
            prepare(rig, "phone_call", obj("number" to "+15550103000")).card to listOf("{1}"),
            prepare(rig, "phone_message_draft", obj("to" to "+15550103000", "body" to "hi")).card to listOf("{1}"),
        )
    }

    @Test fun everyLineOfEveryCardFillsEachHoleOnceFromTheOwnersWords() {
        val rig = ready()
        for ((card, values) in cards(rig)) {
            for (line in card.lines + card.headline) {
                assertEquals(line.template, reference(line), line.text())
                assertEquals(line.template, reference(line, ::isolated), line.shown())
            }
            assertEquals(card.lines.joinToString("; ") { reference(it) }, card.describe())
            // Each word of the owner's that the card carries is on it as written.
            val carried = card.lines.flatMap { it.args }
            for (value in values) assertTrue("$value in ${card.describe()}", carried.any { it.contains(value) } || card.describe().contains(value))
        }
    }

    @Test fun aNameThatLooksLikeAHoleIsShownAsTheNameAndNotAsTheOtherFields() {
        val rig = ready()
        val all = cards(rig)
        // A calendar event named "{1}": the card names the event, not the time that fills the second hole.
        val create = all[0].first.headline.text()
        assertTrue(create, create.startsWith("Add '{1}' Tue 4 Nov 3:00 PM"))
        assertTrue(create, create.endsWith(" to {2}"))
        assertEquals("Delete '{1}' Tue 4 Nov 3:00 PM – 4:00 PM", all[3].first.headline.text())
        assertEquals("Change '{1}'", all[4].first.headline.text())
        // A contact named "{1}": one number, once, after the name.
        assertEquals("Call {1} +15550103000", all[5].first.lines.first().text())
        assertEquals("Call {1}", all[5].first.headline.text())
        assertEquals("Write to {1} +15550103000", all[7].first.lines.first().text())
        // A word that only has a hole inside it keeps the rest.
        assertTrue(all[1].first.headline.text(), all[1].first.headline.text().startsWith("Add 'Note {2}' "))
    }

    @Test fun theCardsAndTheNotificationDrawTheNameWithItsHolesLiteral() {
        val rig = ready()
        val all = cards(rig)
        assertTrue(all[0].first.headline.shown(), all[0].first.headline.shown().startsWith("Add '$open{1}$close' "))
        assertTrue(all[0].first.headline.shown(), all[0].first.headline.shown().endsWith(" to $open{2}$close"))
        assertEquals("Call $open{1}$close", all[5].first.headline.shown())
        // The quick line a notification starts from is the same one pass.
        val quick = rig.engine.describeQuick("phone_calendar_create", obj("title" to "{1}", "start" to start))
        assertTrue(quick, quick.startsWith("Add '{1}' Tue 4 Nov 3:00 PM"))
    }

    @Test fun aTranslatedTemplateThatMovesTheHolesStillFillsThemOnce() {
        // A translation may put the holes in another order; the words still go in once each.
        assertEquals("x {0}{0} {1}", PhoneLine("{1} {0} {2}", listOf("{0}{0}", "x", "{1}")).text())
        assertEquals("${open}x$close ${open}{0}{0}$close ${open}{1}$close", PhoneLine("{1} {0} {2}", listOf("{0}{0}", "x", "{1}")).shown())
        assertEquals("{1} and {0}", PhoneLine("{0} and {1}", listOf("{1}", "{0}")).text())
    }

    @Test fun theCardAShownToALiveCallCarriesTheNameVerbatim() = runBlocking<Unit> {
        val rig = ready()
        cards(rig)
        val events = CopyOnWriteArrayList<JSONObject>()
        val bridge = PhoneVoiceBridge(context, PhoneOrigin.LiveCall, 5_000) { it.shown() }
        val job = launch(Dispatchers.Default) { bridge.call("h1", "phone_call", obj("contact_id" to "11"), events::add) }
        withTimeout(5_000) { while (events.none { it.optString("type") == "tool_approval" }) delay(5) }
        val card = events.first { it.optString("type") == "tool_approval" }.getJSONObject("phone_card")
        assertEquals("Call $open{1}$close", card.getString("action"))
        // The countdown card names the saved contact and the number apart.
        assertEquals("+15550103000", events.first { it.optString("type") == "tool_approval" }.getJSONObject("arguments").getString("number"))
        job.cancel()
        job.join()
        val create = launch(Dispatchers.Default) { bridge.call("h2", "phone_calendar_create", obj("title" to "{1}", "start" to start), events::add) }
        withTimeout(5_000) { while (events.count { it.optString("type") == "tool_approval" } < 2) delay(5) }
        val written = events.last { it.optString("type") == "tool_approval" }.getJSONObject("phone_card")
        assertTrue(written.getString("action"), written.getString("action").startsWith("Add '$open{1}$close' "))
        assertTrue(written.getString("action"), written.getString("action").endsWith(" to $open{2}$close"))
        create.cancel()
        create.join()
    }
}
