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
        assertEquals("Checked 16:00", proactiveHealthText(json(
            "timezone" to "Asia/Dubai", "health" to json("state" to "ok", "checked_at" to 1792497600L),
        )))
        assertEquals("Couldn't check: Sign in expired", proactiveHealthText(json(
            "health" to json("state" to "error", "message" to "Sign in expired"),
        )))
        assertEquals("Not checked yet", proactiveHealthText(json("health" to json("state" to "unknown"))))
        assertEquals(ProactiveHealthState.Checking, proactiveHealthState(null, true))
        assertEquals(ProactiveHealthState.Ok, proactiveHealthState(json("health" to json("state" to "ok")), false))
        assertEquals(ProactiveHealthState.NotYet, proactiveHealthState(json("health" to json("state" to "unknown")), false))
        assertEquals(ProactiveHealthState.Failed, proactiveHealthState(json("health" to json("state" to "error")), false))
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
