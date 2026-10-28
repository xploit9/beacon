package dev.hermes.android.ui

import dev.hermes.android.tasks.scheduleState
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallStrings
import dev.hermes.android.voice.CallToolReceipt
import dev.hermes.android.voice.CallToolStrings
import dev.hermes.android.voice.activityLabel
import dev.hermes.android.voice.activitySubject
import dev.hermes.android.voice.activityTemplate
import dev.hermes.android.voice.callStatusLabel
import dev.hermes.android.voice.statusLabel
import dev.hermes.android.voice.statusTemplate
import dev.hermes.android.voice.transcriptLabel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The call screen's fixed labels reach uiText through variables, so TranslationKeysTest cannot see them; CallStrings is their list. */
class UxSystemTest {
    private val states = listOf("connecting", "reconnecting", "thinking", "speaking", "listening", "idle", "ending")

    @Test fun everyFixedCallStatusAndTranscriptLabelIsListed() {
        val statuses = states.flatMap { state -> listOf(false, true).flatMap { muted -> listOf(false, true).map { held ->
            callStatusLabel(CallState(active = true, state = state, muted = muted, held = held))
        } } } + callStatusLabel(CallState(active = true, remoteSpeaking = true)) + callStatusLabel(CallState(active = true, userSpeaking = true))
        statuses.forEach { assertTrue("\"$it\" is not in CallStrings", it in CallStrings) }
        listOf("partial", "unavailable", "failed", "finalizing", "saved").forEach { status ->
            val label = CallState(transcriptStatus = status).transcriptLabel()
            assertTrue("\"$label\" is not in CallStrings", label in CallStrings)
        }
    }

    @Test fun everyScheduleStateIsListed() {
        val jobs = listOf(
            JSONObject().put("state", "paused"), JSONObject().put("state", "completed"), JSONObject().put("state", "running"),
            JSONObject().put("last_status", "error"), JSONObject().put("next_run_at", "2026-10-14T06:00:00Z"),
            JSONObject().put("next_run_at", "2026-10-14T06:00:00Z").put("scheduler_heartbeat_age_s", 500), JSONObject(),
        )
        assertEquals(ScheduleStateStrings.toSet(), jobs.map(::scheduleState).toSet())
    }

    @Test fun callToolTextsKeepTheNameOutsideTheTranslatedWords() {
        val service = CallToolReceipt("request-1", "mcp__gmail__search", "running")
        assertEquals("Checking {0}…", service.activityTemplate())
        assertEquals("Using {0}…", service.statusTemplate())
        assertEquals("Gmail", service.activitySubject())
        assertEquals("Checking Gmail…", service.activityLabel())
        assertEquals("Using Gmail…", service.statusLabel())
        val file = CallToolReceipt("request-2", "write_file", "ready")
        val other = CallToolReceipt("request-3", "send_email", "running")
        assertEquals("Using Send email…", other.statusLabel())
        assertTrue(listOf(service, file, other).flatMap { listOfNotNull(it.activityTemplate(), it.statusTemplate()) }.all { it in CallToolStrings })
    }

    @Test fun callStringsAreUniqueAndNotBlank() {
        assertTrue(CallStrings.all { it.isNotBlank() && it == it.trim() })
        assertEquals(CallStrings.size, CallStrings.toSet().size)
    }

    @Test fun everyCallStringIsTranslatedInEveryLanguage() {
        val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
            "Run this check from the Beacon app, Android project or repository root."
        }
        val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText())
        val missing = catalog.keys().asSequence().filter { it != "en" }.flatMap { language ->
            val strings = catalog.getJSONObject(language)
            CallStrings.filter { strings.optString(normalized(it)).isBlank() }.map { "$language \"$it\"" }
        }.toList()
        assertTrue("These call labels show in English in another language. Add their translations to native-locales.json:\n" + missing.joinToString("\n"), missing.isEmpty())
    }
}
