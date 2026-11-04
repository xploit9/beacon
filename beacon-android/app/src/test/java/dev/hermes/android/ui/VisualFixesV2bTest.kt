package dev.hermes.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The full-pass visual fixes, part B: the words and rules that are not a layout. */
class VisualFixesV2bTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun source(name: String) = File(app, "src/main/java/dev/hermes/android/ui/$name").readText()

    private fun failed(error: String, status: String = "error") = JSONObject().put("id", "j").put("state", "scheduled").put("next_run_at", "2026-11-01T08:00:00Z")
        .put("last_status", status).put("last_error", error)

    // Item 3: a Failed chip shows a short reason.

    @Test fun aFailedScheduleShowsTheFirstLineOfItsError() {
        assertEquals("Gateway timed out", scheduleFailureReason(failed("Gateway timed out")))
        assertEquals("Gateway timed out", scheduleFailureReason(failed("\n  Gateway timed out  \nTraceback (most recent call last):\n  File x")))
    }

    @Test fun aLongReasonIsCutShort() {
        val reason = scheduleFailureReason(failed("x".repeat(200)))
        assertEquals(FAILURE_REASON_MAX, reason.length)
        assertTrue(reason.endsWith("…"))
        assertEquals("short", scheduleFailureReason(failed("short")))
        assertEquals("", scheduleFailureReason(failed("[blocked_config] delivery platform 'phone' is not a known channel")))
        assertEquals("", scheduleFailureReason(failed("delivery_platform_unknown")))
        assertEquals("Couldn't reach the server, it's down", scheduleFailureReason(failed("Couldn't reach the server, it's down")))
    }

    @Test fun noReasonIsShownWhenThereIsNoneOrTheJobDidNotFail() {
        assertEquals("", scheduleFailureReason(failed("")))
        assertEquals("", scheduleFailureReason(failed("   \n ")))
        assertEquals("", scheduleFailureReason(JSONObject().put("id", "j").put("state", "scheduled").put("next_run_at", "2026-11-01T08:00:00Z").put("last_status", "ok")))
        // A job that ran well since carries no error, whatever an old one said.
        assertEquals("", scheduleFailureReason(failed("Gateway timed out").put("last_status", "ok").put("last_error", "")))
        // A paused job is Paused, not Failed.
        assertEquals("", scheduleFailureReason(failed("Gateway timed out").put("enabled", false)))
    }

    @Test fun aDeliveryFailureIsShownOnceBelowTheScheduleNotAgainOnTheChip() {
        val delivery = failed("That thread is no longer available", status = "delivery_failed").put("last_delivery_error", "That thread is no longer available")
        assertEquals("", scheduleFailureReason(delivery))
    }

    // Item 4: one relative-time format.

    // 4 Nov 2026: Activity rows sit under a heading for their day, so each says its clock time ("3:00 PM") instead of how long ago.
    @Test fun phoneAccessActivitySaysTheClockTimeUnderItsDay() {
        val phone = source("PhoneAccessSettings.kt")
        assertTrue(phone.contains("PhoneTime.clock(entry.at"))
        assertFalse("The phone's own abbreviation (\"2 hr. ago\") is gone", phone.contains("getRelativeTimeSpanString"))
        assertEquals(Ago.Hours to 2L, deliveryAgo(2 * 3_600_000L + 5, 5))
        assertEquals(Ago.Minutes to 5L, deliveryAgo(5 * 60_000L + 7, 7))
    }

    // Item 5: wording.

    @Test fun notificationsSaysLastContactNotHeardFromHermes() {
        val page = source("NotificationSettings.kt")
        assertTrue(page.contains("uiText(\"Last contact\") to agoText(now, heardAt)"))
        assertFalse(page.contains("uiText(\"Heard from Hermes\")"))
    }

    @Test fun keepConnectedBeingOffIsWordedOneWayWhereverItShows() {
        // The hero, the plugin's Notifications row and the Notifications action all say it the same way: "Keep connected is off", then "Turn on".
        assertTrue(source("NotificationSettings.kt").contains("uiText(\"Keep connected is off\")"))
        assertTrue(source("NotificationSettings.kt").contains("SettingsAction(uiText(\"Turn on\")"))
        assertTrue(source("PluginHealth.kt").contains("HealthLine(SettingsStatus.Off, \"Keep connected is off\""))
        assertFalse(source("PluginHealth.kt").contains("Turn on Keep connected"))
        assertTrue("Keep connected is off" in PluginHealthStrings)
        assertFalse("Turn on Keep connected" in PluginHealthStrings)
        // The Settings hub row and its search words say it the same way.
        for (state in listOf(true, false)) {
            val pill = settingsSummary("Notifications & Keep connected", SettingsSnapshot(keepConnectedEnabled = state)).single().text
            assertEquals(if (state) "Keep connected is on" else "Keep connected is off", pill)
            assertTrue(pill in settingsPillWords)
        }
        assertFalse(settingsPillWords.any { it == "Keep connected on" || it == "Keep connected off" })
    }

    @Test fun approvalsSaysWhatManualAndAutoDo() {
        val code = source("NativeApprovalMenu.kt")
        assertTrue(code.contains("uiText(\"Manual asks every time. Auto lets safe actions through.\")"))
        assertTrue(code.contains("profile-approvals-caption"))
    }

    // Item 6: the plugin section shows its internal rows only when one needs attention.

    @Test fun onlyAnAttentionOrErrorRowNeedsAttention() {
        assertFalse(HealthLine(SettingsStatus.Ok, "Running").needsAttention)
        assertFalse(HealthLine(SettingsStatus.Working, "Working").needsAttention)
        assertFalse(HealthLine(SettingsStatus.Off, "Keep connected is off").needsAttention)
        assertTrue(HealthLine(SettingsStatus.Attention, "Needs restart", HealthTarget.AppUpdates).needsAttention)
        assertTrue(HealthLine(SettingsStatus.Error, "Stopped", HealthTarget.CheckNow).needsAttention)
    }

    @Test fun theDashboardAndGatewayRowsAreOnlyDrawnWhenTheyNeedAttention() {
        val code = source("PluginHealth.kt")
        assertTrue(code.contains("health.dashboard?.takeIf { it.needsAttention }"))
        assertTrue(code.contains("health.gateway?.takeIf { it.needsAttention }"))
    }

    // Item 2: Arabic small text.

    @Test fun smallTextInARightToLeftLayoutIsTwelveAndAHalfSp() {
        assertEquals(12.5f, RtlMetadataSize.value, 0f)
        assertTrue(source("NativeTheme.kt").contains("if (rtl) RtlMetadataSize else small.fontSize"))
    }

    // Item 1: row titles sit against the start edge beside the avatar.

    @Test fun everyRowOfTheListsAlignsItsTextToTheStartEdge() {
        assertTrue(source("Components.kt").contains("typography.titleMedium.copy(fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium).rowStart()"))
        val bots = source("BotTeammates.kt")
        assertEquals("Up next (title, bot) and Spaces (title, preview) use it too", 4, Regex("""\.rowStart\(\)""").findAll(bots).count())
    }
}
