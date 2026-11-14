package dev.hermes.android.ui

import dev.hermes.android.core.json
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.AppPreferences
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Live check 8 Nov 2026 (F2): a pick in Settings > Assistant > Watch for me > Model did not save. The row's pick and save path are
 * proven here without a device; what the plugin answers for a watch that is already on is proven in the plugin's own tests.
 */
class WatchModelSaveTest {
    private val assistant = AppPreferences(assistantModel = "gpt-6.1-sol", assistantProvider = "opencodex")
    private val groups = listOf(
        json("provider_id" to "nous", "provider" to "Nous Portal (api)", "models" to JSONArray()
            .put(json("id" to "claude-haiku-5-5", "label" to "claude-haiku-5-5")).put(json("id" to "claude-sonnet-5", "label" to "claude-sonnet-5"))),
        json("provider_id" to "opencodex", "provider" to "OpenCodeX", "models" to JSONArray().put(json("id" to "gpt-6.1-sol", "label" to "gpt-6.1-sol"))),
    )

    /** What the plugin answers after a save: the saved pair, as its status route returns it. */
    private fun status(body: org.json.JSONObject) = json("enabled" to true,
        "watch_model" to body.optString("watch_model"), "watch_provider" to body.optString("watch_provider"))

    // The menu's option keys carry a tab ("provider\tmodel"). The key a pick hands to onSelect is the key the save sends, and the key
    // the row re-reads from the plugin's answer, so the check mark lands on the pick (leads 3 and 4 of the F2 brief).
    @Test fun aPickedMenuKeyIsSavedAsTheWatchPairAndComesBackAsTheSameKey() {
        val options = proactiveModelLabels(groups)
        val picked = options.keys.first { it.endsWith("claude-haiku-5-5") }
        assertEquals("nous\tclaude-haiku-5-5", picked)
        assertEquals("Nous Portal · Claude Haiku 5.5", options[picked])

        val assistantKey = proactiveModelKey(assistant.assistantModel, assistant.assistantProvider)
        val choice = proactiveWatchModelChoice(picked, assistantKey)
        assertEquals(picked, choice)
        val body = proactiveSettingsBody(true, "notify", 15, "22:00", "07:00", assistant, watchModelKey = choice)
        assertEquals("claude-haiku-5-5", body.getString("watch_model"))
        assertEquals("nous", body.getString("watch_provider"))
        // The assistant's own model stays the saved one (nightly upkeep keeps it); every save still says the watch is on.
        assertEquals("gpt-6.1-sol", body.getString("model"))
        assertEquals("opencodex", body.getString("provider"))
        assertTrue(body.getBoolean("enabled"))

        val answer = status(body)
        val reread = proactiveWatchModelChoice(proactiveModelKey(answer.getString("watch_model"), answer.getString("watch_provider")), assistantKey)
        assertEquals(picked, reread)
        assertTrue("the row lists the pick", reread in listOf("") + options.keys)
    }

    @Test fun sameAsAssistantComesBackAsTheBlankKeyTheMenuListsFirst() {
        val assistantKey = proactiveModelKey(assistant.assistantModel, assistant.assistantProvider)
        val body = proactiveSettingsBody(true, "notify", 15, "22:00", "07:00", assistant, watchModelKey = "")
        val answer = status(body)
        assertEquals("", proactiveWatchModelChoice(proactiveModelKey(answer.getString("watch_model"), answer.getString("watch_provider")), assistantKey))
    }

    // What the owner saw in the live check: the save was refused with this plugin message and the row went back to what Hermes has.
    // The same words show on Settings (the notice host sits at the app root, over every screen); screenshot 43 of LIVE-1134 shows them.
    @Test fun aRefusedSaveReadsAsItsMessageForTheNotice() {
        val refused = ApiFailure(409, "Choose an owned assistant conversation before enabling Proactive", plugin = true)
        assertEquals("Send Beacon a message first, then turn this on.", proactiveErrorMessage(refused, "Couldn't save. Try again."))
        assertEquals("Settings change refused", proactiveErrorMessage(ApiFailure(503, "Settings change refused", plugin = true), "fallback"))
        assertEquals("Couldn't save. Try again.", proactiveErrorMessage(RuntimeException(), "Couldn't save. Try again."))
    }

    // O1 (live check 8 Nov): "A few times a day, 10:00–22:00" was 24-hour beside quiet hours written "10:00 PM" / "7:00 AM".
    private fun plain(text: String) = text.replace("⁨", "").replace("⁩", "")

    @Test fun theCheckinWindowFollowsThePhonesClock() {
        assertEquals("10:00–22:00", plain(checkinWindowLabel(is24Hour = true, locale = Locale.US)))
        assertEquals("10:00 AM–10:00 PM", plain(checkinWindowLabel(is24Hour = false, locale = Locale.US)))
        // The same writer the quiet hours use, so the two never disagree on one screen.
        val pattern = nativeClockPattern(Locale.US, is24Hour = false)
        assertEquals("10:00 PM", proactiveClockLabel("22:00", pattern, Locale.US))
        assertTrue(plain(checkinWindowLabel(is24Hour = false, locale = Locale.US)).endsWith(proactiveClockLabel("22:00", pattern, Locale.US)))
    }

    @Test fun theCheckinSentenceNamesTheWindowInThePhonesClockInAnyLanguage() {
        val english = "Beacon calls you to check in between 10:00 and 22:00. Do Not Disturb applies."
        assertEquals(english, checkinSentenceInPhoneClock(english, is24Hour = true, locale = Locale.US))
        assertEquals("Beacon calls you to check in between 10:00 AM and 10:00 PM. Do Not Disturb applies.",
            plain(checkinSentenceInPhoneClock(english, is24Hour = false, locale = Locale.US)))
        val italian = "Beacon ti chiama per sapere come stai tra le 10:00 e le 22:00. Vale Non disturbare."
        assertEquals(italian, checkinSentenceInPhoneClock(italian, is24Hour = true, locale = Locale.US))
        assertEquals("Beacon ti chiama per sapere come stai tra le 10:00 AM e le 10:00 PM. Vale Non disturbare.",
            plain(checkinSentenceInPhoneClock(italian, is24Hour = false, locale = Locale.US)))
    }
}
