package dev.hermes.android.ui

import dev.hermes.android.core.json
import dev.hermes.android.data.AppPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProactiveSettingsTest {
    @Test fun settingsBodyKeepsTheWatchContractAndAssistantModelPin() {
        val body = proactiveSettingsBody(true, "call", 5, "22:15", "06:45", AppPreferences(assistantModel = "model", assistantProvider = "provider"))

        assertEquals(true, body.getBoolean("enabled"))
        assertEquals("call", body.getString("delivery"))
        assertEquals(5, body.getInt("check_minutes"))
        assertEquals("22:15", body.getString("quiet_start"))
        assertEquals("06:45", body.getString("quiet_end"))
        assertEquals("model", body.getString("model"))
        assertEquals("provider", body.getString("provider"))
        assertEquals(7, body.length())
    }

    @Test fun settingsValidationAcceptsOnlySupportedDeliveryIntervalsAndTimes() {
        fun valid(delivery: String = "notify", minutes: Int = 15, start: String = "22:00", end: String = "07:00") =
            proactiveSettingsValid(json("enabled" to true, "delivery" to delivery, "check_minutes" to minutes, "quiet_start" to start, "quiet_end" to end))

        assertTrue(valid())
        assertTrue(valid("call", 5, "00:00", "23:59"))
        assertFalse(valid(minutes = 10))
        assertFalse(valid(delivery = "email"))
        assertFalse(valid(start = "24:00"))
        assertFalse(valid(end = "7:00"))
    }

    @Test fun healthUsesTheSingleWatchCheckStatusAndPlainWords() {
        assertEquals(ProactiveHealthState.Checking, proactiveHealthState(null, true))
        assertEquals(ProactiveHealthState.Ok, proactiveHealthState(json("health" to json("state" to "ok")), false))
        assertEquals(ProactiveHealthState.NotYet, proactiveHealthState(json("health" to json("state" to "unknown")), false))
        assertEquals(ProactiveHealthState.Failed, proactiveHealthState(json("health" to json("state" to "error")), false))
        // At night the watch rests: "Quiet hours" with the last check, not "Not yet" (28 Oct 2026).
        val quiet = json("timezone" to "Asia/Dubai", "health" to json("state" to "quiet", "checked_at" to 1792497600L, "last_state" to "ok"))
        assertEquals(ProactiveHealthState.Quiet, proactiveHealthState(quiet, false))
        // 1792497600 is 20 Oct 2026, 16:00 in Dubai: not today, so it carries its day.
        assertEquals("20 Oct, 16:00", proactiveLastCheck(quiet))
        val now = java.time.Instant.now().epochSecond
        assertEquals(java.time.Instant.ofEpochSecond(now).atZone(java.time.ZoneId.of("Asia/Dubai")).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
            proactiveLastCheck(json("timezone" to "Asia/Dubai", "health" to json("state" to "ok", "checked_at" to now))))
        assertEquals(null, proactiveLastCheck(json("health" to json("state" to "unknown"))))
    }

    @Test fun learnedNotesIgnoreBlankEntriesAndMissingLists() {
        assertEquals(listOf("Client moved the review"), proactiveLearnedNotes(json(
            "learned" to JSONArray(listOf("Client moved the review", "", "  ")),
        )))
        assertTrue(proactiveLearnedNotes(json()).isEmpty())
    }

    @Test fun aSuggestionReadsAsTheSenderThenTheSubjectThenHermesOwnWords() {
        val from = "Sent by {0}"
        val about = "About {0}"
        assertEquals("Sent by Daniel Reyes", EmailRuleSuggestion("Daniel Reyes", "Client moved the review", "x").text(from, about))
        assertEquals("About Weekly deals", EmailRuleSuggestion(topic = "Weekly deals", rule = "x").text(from, about))
        assertEquals("Hermes words", EmailRuleSuggestion(rule = "Hermes words").text(from, about))
        assertEquals("", EmailRuleSuggestion().text(from, about))
        // The words come in the app's language: the template is the translated one, the name is data.
        assertEquals("من داني", EmailRuleSuggestion("داني").text("من {0}", "بخصوص {0}"))
        assertEquals(EmailRuleSuggestion("Daniel Reyes", "Review", "Sent by Daniel Reyes"),
            EmailRuleSuggestion.from(json("sender" to " Daniel Reyes ", "topic" to "Review", "rule" to "Sent by Daniel Reyes", "source" to "email")))
        assertEquals(EmailRuleSuggestion(), EmailRuleSuggestion.from(json()))
    }

    @Test fun earlierRatingsProposalKeepsKnownVerdictsAndSourcesAndAtMostFiveRules() {
        fun row(verdict: String, source: String, sender: String) = json("verdict" to verdict, "source" to source, "sender" to sender, "topic" to "", "rule" to "Sent by $sender")
        val rows = JSONArray(listOf(
            row("important", "email", "A"), row("not_important", "email", "B"), row("maybe", "email", "C"), row("important", "", "D"),
            row("important", "github", "E"), row("not_important", "email", "F"), row("important", "email", "G"), row("important", "email", "H"),
        ))
        val proposal = proactiveLearnedProposal(json("learned_proposal" to rows))
        assertEquals(listOf("A", "B", "E", "F", "G"), proposal.map { it.suggestion.sender })
        assertEquals(listOf("important", "not_important", "important", "not_important", "important"), proposal.map { it.verdict })
        assertEquals("github", proposal[2].source)
        assertTrue(proactiveLearnedProposal(json()).isEmpty())
        assertTrue(importanceActions == setOf("important", "not_important"))
    }

    @Test fun sourceLabelsIgnoreInvalidEntriesAndPreserveCatalogueOrder() {
        assertEquals(listOf("Gmail", "Calendar", "GitHub"), proactiveSources(json(
            "sources" to JSONArray(listOf("Gmail", " Calendar ", "GitHub", "Gmail", "", "  ", 12, JSONObject.NULL)),
        )))
        assertTrue(proactiveSources(json()).isEmpty())
        assertTrue(proactiveSources(json("sources" to "Gmail")).isEmpty())
    }
}
