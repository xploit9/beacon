package dev.hermes.android.uiux.assistantvoice

import dev.hermes.android.core.json
import dev.hermes.android.ui.ProactiveHealthState
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.VoiceKeyCheck
import dev.hermes.android.ui.checkupCapabilitiesKnown
import dev.hermes.android.ui.checkupJob
import dev.hermes.android.ui.checkupsOn
import dev.hermes.android.ui.devicesShown
import dev.hermes.android.ui.ownerNameDraft
import dev.hermes.android.ui.proactiveHealthStatus
import dev.hermes.android.ui.proactiveSourcesSummary
import dev.hermes.android.ui.voiceKeyCheckStatus
import dev.hermes.android.ui.voiceModelCardText
import dev.hermes.android.ui.voiceModelDisplayName
import dev.hermes.android.voice.GeminiSpeech
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Assistant, Voice & calls and Devices pages (uiux-20261031, lane assistant-voice): the pure rules behind what they show. */
class AssistantVoiceSettingsTest {
    private fun crons(vararg jobs: org.json.JSONObject) = json("jobs" to JSONArray(jobs.toList()))

    @Test fun checkUpCallsAreOnOnlyWhileThePluginsOwnTaskExistsAndRuns() {
        val owners = json("id" to "task-a", "name" to "Morning summary", "enabled" to true)
        val checkups = json("id" to "checkups", "script" to "/home/hermes/.hermes/scripts/beacon-checkups.py", "enabled" to true, "state" to "scheduled")
        assertEquals("checkups", checkupJob(crons(owners, checkups))?.optString("id"))
        assertTrue(checkupsOn(crons(owners, checkups)))
        // Paused from Schedules: the task is still there, but the calls are off.
        assertFalse(checkupsOn(crons(owners, json("id" to "checkups", "script" to "beacon-checkups.py", "enabled" to false, "state" to "paused"))))
        // No task, or no list yet: off, and turning on runs the plugin's own install (which reuses an existing task).
        assertNull(checkupJob(crons(owners)))
        assertFalse(checkupsOn(crons(owners)))
        assertFalse(checkupsOn(null))
    }

    @Test fun capabilitiesShowOnlyWhenAnAnswerCameBack() {
        assertFalse(checkupCapabilitiesKnown(null))
        assertFalse(checkupCapabilitiesKnown(json("_loading" to true)))
        assertFalse(checkupCapabilitiesKnown(json("_loading" to true, "_error" to "Offline")))
        assertFalse(checkupCapabilitiesKnown(json("_error" to "Offline")))
        assertTrue(checkupCapabilitiesKnown(json("checkups_ready" to false)))
        assertTrue(checkupCapabilitiesKnown(json("timezone" to "Asia/Dubai")))
        // A saved answer whose refresh failed stays on screen under the error.
        assertTrue(checkupCapabilitiesKnown(json("checkups_ready" to true, "_error" to "Offline", "_stale" to true)))
    }

    @Test fun sourcesReadAsTheFirstTwoAndACount() {
        assertEquals("", proactiveSourcesSummary(emptyList()))
        assertEquals("File", proactiveSourcesSummary(listOf("File")))
        assertEquals("File, Personal", proactiveSourcesSummary(listOf("File", "Personal")))
        assertEquals("File, Personal +3", proactiveSourcesSummary(listOf("File", "Personal", "Gmail", "Calendar", "GitHub")))
    }

    @Test fun theOwnerNameDraftKeepsASpaceBeingTypedButOnlyNameCharacters() {
        assertEquals("Maya ", ownerNameDraft("Maya "))
        assertEquals("Maya O'Neil", ownerNameDraft("Maya! O'Neil2"))
        assertEquals(40, ownerNameDraft("A".repeat(45)).length)
    }

    @Test fun moreThanFourOtherPhonesCollapseToThree() {
        assertEquals(4, devicesShown(List(4) { it }, showAll = false).size)
        assertEquals(listOf(0, 1, 2), devicesShown(List(5) { it }, showAll = false))
        assertEquals(5, devicesShown(List(5) { it }, showAll = true).size)
    }

    @Test fun longModelNamesMoveTheirPreviewPartToTheDetail() {
        assertEquals("Gemini 2.5 Flash Native Audio" to "Preview 09 2025", voiceModelCardText("gemini-2.5-flash-native-audio-preview-09-2025"))
        assertEquals("Gemini 3.1 Flash Live" to "Preview", voiceModelCardText("models/gemini-3.1-flash-live-preview"))
        assertEquals("Gemini 3.8 Live" to "", voiceModelCardText("models/gemini-3.8-live"))
        assertEquals("GPT Realtime 2.1" to "", voiceModelCardText("gpt-realtime-2.1"))
    }

    @Test fun theGeminiSpokenRepliesRowNamesTheDedicatedSpeechModel() {
        // Kept from the 1.1.21 disclosure: the chosen Live model listens and this model says the replies.
        assertEquals("Gemini 3.8 Flash Lite TTS", voiceModelDisplayName(GeminiSpeech.MODEL))
    }

    @Test fun watchHealthAndKeyChecksReadAsSettingsStatuses() {
        assertEquals(SettingsStatus.Working, proactiveHealthStatus(ProactiveHealthState.Checking))
        assertEquals(SettingsStatus.Ok, proactiveHealthStatus(ProactiveHealthState.Ok))
        assertEquals(SettingsStatus.Off, proactiveHealthStatus(ProactiveHealthState.Quiet))
        assertEquals(SettingsStatus.Off, proactiveHealthStatus(ProactiveHealthState.NotYet))
        assertEquals(SettingsStatus.Error, proactiveHealthStatus(ProactiveHealthState.Failed))
        assertNull(voiceKeyCheckStatus(VoiceKeyCheck.None))
        assertEquals(SettingsStatus.Working, voiceKeyCheckStatus(VoiceKeyCheck.Checking))
        assertEquals(SettingsStatus.Ok, voiceKeyCheckStatus(VoiceKeyCheck.Works))
        assertEquals(SettingsStatus.Error, voiceKeyCheckStatus(VoiceKeyCheck.Rejected))
        assertEquals(SettingsStatus.Attention, voiceKeyCheckStatus(VoiceKeyCheck.Unreachable))
        assertEquals(SettingsStatus.Error, voiceKeyCheckStatus(VoiceKeyCheck.ProviderError))
    }
}
