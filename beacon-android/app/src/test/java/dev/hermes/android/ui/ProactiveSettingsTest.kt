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

    @Test fun sourceLabelsIgnoreInvalidEntriesAndPreserveCatalogueOrder() {
        assertEquals(listOf("Gmail", "Calendar", "GitHub"), proactiveSources(json(
            "sources" to JSONArray(listOf("Gmail", " Calendar ", "GitHub", "Gmail", "", "  ", 12, JSONObject.NULL)),
        )))
        assertTrue(proactiveSources(json()).isEmpty())
        assertTrue(proactiveSources(json("sources" to "Gmail")).isEmpty())
    }
}
