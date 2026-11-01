package dev.hermes.android.uxsettings

import androidx.compose.ui.unit.dp
import dev.hermes.android.ui.HealthLine
import dev.hermes.android.ui.HealthTarget
import dev.hermes.android.ui.PluginHealth
import dev.hermes.android.ui.PluginHealthStrings
import dev.hermes.android.ui.SettingsSnapshot
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.SettingsStrings
import dev.hermes.android.ui.normalized
import dev.hermes.android.ui.pluginHealthOf
import dev.hermes.android.ui.settingsDescriptions
import dev.hermes.android.ui.settingsHubRoutes
import dev.hermes.android.ui.settingsKeywordsOf
import dev.hermes.android.ui.settingsLabel
import dev.hermes.android.ui.settingsPillIconSize
import dev.hermes.android.ui.settingsRowMatches
import dev.hermes.android.ui.settingsSegmentsFit
import dev.hermes.android.ui.settingsStatusIcon
import dev.hermes.android.ui.settingsSummary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The 1 Nov UI/UX pass over Settings › Connection (Beacon plugin health), Chat, Appearance and Notifications: what the health verdict says,
 * where each row leads, how the pills and choices size themselves, and the words every language shows.
 */
class UxSettingsTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun source(name: String) = File(app, "src/main/java/dev/hermes/android/ui/$name").readText()
    private val catalog by lazy { JSONObject(File(app, "src/main/assets/native-locales.json").readText()) }
    private val languages by lazy { catalog.keys().asSequence().filter { it != "en" }.toList() }

    private fun answer(
        dashboardCurrent: Boolean? = true, gatewayRunning: Boolean = true, gatewayBusy: Boolean = false, gatewayCurrent: Boolean? = true,
        callsReady: Boolean = true, callsReason: String = "", pushReady: Boolean = true, pushReason: String = "",
    ) = JSONObject()
        .put("plugin", JSONObject().put("running", "1.1.23").put("installed", "1.1.23").put("gateway", "1.1.23"))
        .put("dashboard", JSONObject().put("current", dashboardCurrent ?: JSONObject.NULL))
        .put("gateway", JSONObject().put("known", true).put("running", gatewayRunning).put("busy", gatewayBusy).put("current", gatewayCurrent ?: JSONObject.NULL))
        .put("calls", JSONObject().put("ready", callsReady).put("provider", "gemini").put("reason", callsReason))
        .put("push", JSONObject().put("ready", pushReady).put("reason", pushReason))

    private fun health(json: JSONObject = answer(), keep: Boolean = false, key: Boolean? = true): PluginHealth = requireNotNull(pluginHealthOf(json, keep, key))

    // The health verdict

    @Test fun keepConnectedBeingOffIsTheOwnersChoiceAndOnItsOwnLeavesHealthHealthy() {
        // The wizard starts with Keep connected off and this server has no push sender: the plain, healthy setup.
        val owner = health(answer(pushReady = false, pushReason = "no_sender"), keep = false)
        assertEquals("Turn on Keep connected", owner.notifications.text)
        assertEquals(SettingsStatus.Off, owner.notifications.status)
        assertEquals(SettingsStatus.Ok, owner.overall)
        assertEquals("Healthy", owner.overallText)
        // The hint stays and still leads to the page where the switch is.
        assertEquals(HealthTarget.Notifications, owner.notifications.target)
    }

    @Test fun aRealFaultStillTurnsTheVerdictWhateverKeepConnectedSays() {
        assertEquals(SettingsStatus.Attention, health(answer(dashboardCurrent = false), keep = false).overall)
        assertEquals(SettingsStatus.Attention, health(answer(pushReady = false, pushReason = "no_phone"), keep = false).overall)
        assertEquals("A phone the plugin has never seen is not set up", "Not set up", health(answer(pushReady = false, pushReason = "no_phone"), keep = false).notifications.text)
        assertEquals(SettingsStatus.Error, health(answer(gatewayRunning = false), keep = false).overall)
        assertEquals("Not working", health(answer(callsReady = false, callsReason = "timeout"), keep = false).overallText)
        // With Keep connected on, the phone has its own path, so the same push gap is fine.
        assertEquals(SettingsStatus.Ok, health(answer(pushReady = false, pushReason = "no_phone"), keep = true).overall)
    }

    @Test fun aBusyGatewayIsInProgressNotAFaultAndNeverShownWithACheckMark() {
        val busy = health(answer(gatewayBusy = true))
        assertEquals("Working", busy.gateway?.text)
        assertEquals(SettingsStatus.Working, busy.gateway?.status)
        // A check mark only marks an OK state: Working has no still glyph at all, and the word "Busy" is gone.
        assertNull(settingsStatusIcon(SettingsStatus.Working))
        assertNotNull(settingsStatusIcon(SettingsStatus.Ok))
        assertFalse("Busy" in PluginHealthStrings)
        assertEquals(SettingsStatus.Ok, busy.overall)
        // Hermes busy with a call check reads the same way, in its own words.
        val callsBusy = health(answer(callsReady = false, callsReason = "busy"))
        assertEquals("Hermes is busy" to SettingsStatus.Working, callsBusy.calls.text to callsBusy.calls.status)
        assertFalse("Checking… claims an activity that isn't happening", callsBusy.calls.text == "Checking…")
    }

    // Where a row leads

    @Test fun everyRowThatNeedsTheOwnerLeadsToWhereItIsFixed() {
        fun target(line: HealthLine?) = line?.target
        assertEquals(HealthTarget.AppUpdates, target(health(answer(dashboardCurrent = false)).dashboard))
        assertEquals(HealthTarget.AppUpdates, target(health(answer(gatewayCurrent = false)).gateway))
        assertEquals(HealthTarget.VoiceCalls, target(health(answer(), key = false).calls))
        assertEquals(HealthTarget.Notifications, target(health(answer(pushReady = false, pushReason = "no_sender")).notifications))
        assertEquals(HealthTarget.Notifications, target(health(answer(pushReady = false, pushReason = "no_phone")).notifications))
        // Not ready and too slow run Check now, as does a stopped gateway: there is nothing to open, only to read again.
        for (reason in listOf("timeout", "unavailable", "", "updating")) assertEquals(reason, HealthTarget.CheckNow, target(health(answer(callsReady = false, callsReason = reason)).calls))
        assertEquals(HealthTarget.CheckNow, target(health(answer(callsReason = "slow")).calls))
        assertEquals(HealthTarget.CheckNow, target(health(answer(gatewayRunning = false)).gateway))
    }

    @Test fun rowsThatAreFineOrInProgressAskNothingAndRowsInTroubleAlwaysLead() {
        val everyAnswer = listOf(
            answer(), answer(dashboardCurrent = false), answer(dashboardCurrent = null), answer(gatewayRunning = false), answer(gatewayBusy = true), answer(gatewayCurrent = false),
            answer(callsReason = "slow"), answer(callsReady = false, callsReason = "updating"), answer(callsReady = false, callsReason = "timeout"),
            answer(callsReady = false, callsReason = "busy"), answer(callsReady = false, callsReason = "unavailable"),
            answer(pushReady = false, pushReason = "no_sender"), answer(pushReady = false, pushReason = "no_phone"),
        )
        var checked = 0
        for (json in everyAnswer) for (keep in listOf(false, true)) for (key in listOf(null, true, false)) {
            val shown = health(json, keep, key)
            for (line in listOfNotNull(shown.dashboard, shown.gateway, shown.calls, shown.notifications)) {
                checked++
                when (line.status) {
                    SettingsStatus.Ok, SettingsStatus.Working -> assertEquals("${line.text} asks nothing", HealthTarget.None, line.target)
                    SettingsStatus.Attention, SettingsStatus.Error -> assertNotEquals("${line.text} is in trouble and must lead somewhere", HealthTarget.None, line.target)
                    SettingsStatus.Off -> assertEquals("Only Keep connected is Off", "Turn on Keep connected", line.text)
                }
            }
        }
        assertTrue(checked > 200)
    }

    @Test fun theNavigationTargetsExistAsSettingsPages() {
        // The rows open Notifications, Voice & calls (route Beacon) and App updates through BeaconViewModel.more; each is a page the Settings router serves.
        assertTrue(source("MoreScreen.kt").contains("page in setOf(\"Appearance\", \"Assistant\", \"Connection\", \"Beacon\", \"Notifications\", \"App updates\", \"Licenses\")"))
        val code = source("PluginHealth.kt")
        assertTrue(code.contains("HealthTarget.Notifications -> vm.more(\"Notifications\")"))
        assertTrue(code.contains("HealthTarget.VoiceCalls -> vm.more(\"Beacon\")"))
        assertTrue(code.contains("HealthTarget.AppUpdates -> vm.more(\"App updates\")"))
        assertTrue(code.contains("HealthTarget.CheckNow -> read(true)"))
        assertEquals("Voice & calls", settingsLabel("Beacon"))
        assertEquals("Notifications", settingsLabel("Notifications"))
    }

    // The words of the section

    @Test fun theSectionUsesTheOwnersWordsAndOneTermForTheServerPart() {
        val code = source("PluginHealth.kt")
        for (label in listOf("Beacon plugin", "Health", "Version", "Hermes dashboard", "Hermes gateway", "Calls", "Notifications", "Check now", "The Beacon plugin isn't installed"))
            assertTrue(label, code.contains("uiText(\"$label\")"))
        // Rows in the owner's order: Health, Version, dashboard, gateway, Calls, Notifications, Check now.
        val order = listOf("plugin-health-status", "plugin-health-version", "plugin-health-dashboard", "plugin-health-gateway", "plugin-health-calls", "plugin-health-notifications", "plugin-health-check")
        val at = order.map { code.indexOf("\"$it\"") }
        assertTrue(at.toString(), at.all { it > 0 } && at == at.sorted())
        for (old in listOf("Plugin", "Dashboard", "Gateway", "Busy", "Plugin not installed")) assertFalse("$old is no longer a word of the section", old in PluginHealthStrings)
        assertFalse("Beacon on Hermes is not the owner's term", PluginHealthStrings.any { it.contains("Beacon on Hermes") })
    }

    @Test fun beaconPluginComesBeforeDevicesOnTheConnectionPage() {
        val page = source("MoreScreen.kt")
        val connection = page.substring(page.indexOf("private fun ConnectionSettings"))
        assertTrue(connection.indexOf("PluginHealthSection(vm, pluginAbsent)") in 1 until connection.indexOf("DevicesSettingsSection(vm, pluginAbsent)"))
    }

    // Pills and the address

    @Test fun thePillGlyphGrowsWithItsLabelAndNeverShrinksBelowSixteen() {
        assertEquals(16.dp, settingsPillIconSize(12.dp))
        assertEquals(16.dp, settingsPillIconSize(16.dp))
        // A 16sp label line at 1.3x text is 20.8dp: the glyph follows it.
        assertEquals(20.8f, settingsPillIconSize(16.dp * 1.3f).value, 0.01f)
        assertTrue(settingsPillIconSize(16.dp * 2f) > settingsPillIconSize(16.dp * 1.3f))
        assertTrue("The pill reads its glyph from the label's line height", source("SettingsKit.kt").contains("settingsPillIconSize(line)"))
    }

    @Test fun theServerAddressIsOneLineCutInTheMiddleInTheHeroAndTheRow() {
        val page = source("MoreScreen.kt")
        assertTrue(page.contains("lineMaxLines = 1, lineOverflow = TextOverflow.MiddleEllipsis"))
        assertTrue(page.contains("valueTestTag = \"connection-server-value\", testTag = \"connection-server-row\", valueOverflow = TextOverflow.MiddleEllipsis"))
        // The row's value is one line by default, and the hero's line takes the overflow the caller names.
        val kit = source("SettingsKit.kt")
        assertTrue(kit.contains("maxLines = lineMaxLines, overflow = lineOverflow"))
        assertTrue(kit.contains("maxLines = valueMaxLines, overflow = valueOverflow"))
    }

    @Test fun segmentsFitOnlyWhenEveryWordHasItsOwnWidthInsideItsSegment() {
        // Two segments on a 296dp row: 148dp each, minus 52dp of padding, check and gap = 96dp for the word.
        assertTrue(settingsSegmentsFit(95.dp, 2, 296.dp))
        assertFalse(settingsSegmentsFit(97.dp, 2, 296.dp))
        // The same word fits two segments on a wide phone but not three on a narrow one.
        assertTrue(settingsSegmentsFit(110.dp, 2, 347.dp))
        assertFalse(settingsSegmentsFit(110.dp, 3, 296.dp))
        assertFalse(settingsSegmentsFit(0.dp, 0, 296.dp))
    }

    // Chat, Appearance, Notifications

    @Test fun chatAsksWhatHappensWhenYouSendWhileBeaconReplies() {
        val page = source("MoreScreen.kt")
        assertTrue(page.contains("SettingsSection(uiText(\"When you send\"), testTag = \"settings-section:Sending\")"))
        assertTrue(page.contains("assistantText(\"If you send while Beacon replies\")"))
        assertTrue(page.contains("optionLabels = mapOf(\"steer\" to \"Change the reply\", \"queue\" to \"Send after\")"))
        // What is stored does not change, and the option tags keep the label saved layouts and tests know.
        assertTrue(page.contains("val busyOptions = listOf(\"steer\", \"queue\")"))
        assertTrue(page.contains("choiceTags(\"While Beacon is replying\", busyOptions)"))
        // The progress word "Sending" is not the section title any more.
        assertFalse(page.contains("SettingsSection(uiText(\"Sending\"))"))
        assertTrue("Change the reply" in SettingsStrings && "Send after" in SettingsStrings)
        assertFalse("Steer" in SettingsStrings || "Queue" in SettingsStrings)
    }

    @Test fun theOldWordsStillFindChatInSearch() {
        fun finds(query: String) = settingsRowMatches("Chat", "Chat", settingsDescriptions.getValue("Chat"), "", query)
        for (query in listOf("steer", "queue", "change the reply", "send after", "while replying", "sending")) assertTrue(query, finds(query))
        assertFalse(finds("keep connected"))
    }

    @Test fun appearanceLetsTheThemeChoicePickSegmentsOrAMenu() {
        val page = source("MoreScreen.kt")
        assertTrue(page.contains("optionTestTags = choiceTags(\"Theme\", themes), style = ChoiceStyle.Auto"))
        assertFalse(page.contains("choiceTags(\"Theme\", themes), style = ChoiceStyle.Segmented"))
    }

    @Test fun notificationsIsTheTitleAndKeepConnectedStaysSearchable() {
        for (route in listOf("Notifications", "Notifications & Keep connected")) assertEquals("Notifications", settingsLabel(route))
        assertTrue("Notifications & Keep connected" in settingsHubRoutes)
        // The hub row still tells the state, so Keep connected stays in its summary.
        val on = settingsSummary("Notifications & Keep connected", SettingsSnapshot(keepConnectedEnabled = true)).single()
        val off = settingsSummary("Notifications & Keep connected", SettingsSnapshot(keepConnectedEnabled = false)).single()
        assertEquals("Keep connected on" to SettingsStatus.Ok, on.text to on.status)
        assertEquals("Keep connected off" to SettingsStatus.Off, off.text to off.status)
        // And search finds the row by the old title, its words and the new title.
        val route = "Notifications & Keep connected"
        for (query in listOf("keep connected", "Keep connected", "notifications", "delivery", "alerts")) {
            assertTrue(query, settingsRowMatches(route, settingsLabel(route), settingsDescriptions.getValue(route), settingsKeywordsOf(route), query))
        }
        assertFalse(settingsRowMatches("Appearance", "Appearance", settingsDescriptions.getValue("Appearance"), settingsKeywordsOf("Appearance"), "keep connected"))
    }

    // Translations

    private val newWords = listOf(
        "Beacon plugin", "Hermes dashboard", "Hermes gateway", "The Beacon plugin isn't installed", "When you send", "If you send while Beacon replies",
        "Change the reply", "Send after", "Turn it on to get calls and alerts while Beacon is closed.",
        "Notification delivery keep connected", "while replying steer queue change the reply send after",
    )

    @Test fun everyNewWordIsReallyTranslatedInEveryLanguage() {
        assertEquals(15, languages.size)
        val missing = mutableListOf<String>()
        for (language in languages) {
            val strings = catalog.getJSONObject(language)
            for (word in newWords) {
                val value = strings.optString(normalized(word))
                if (value.isBlank()) missing += "$language missing \"$word\""
                else if (normalized(value) == normalized(word)) missing += "$language copies English \"$word\""
            }
        }
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
    }

    @Test fun theWordsThatNameTheAssistantKeepItsNameSoARenamedAssistantShowsItsOwn() {
        // assistantText swaps the Latin word Beacon for the assistant's chosen name, so every translation must still contain it.
        for (language in languages) assertTrue(language, catalog.getJSONObject(language).getString(normalized("If you send while Beacon replies")).contains("Beacon"))
    }

    @Test fun arabicReadsNaturallyWithTheServerNamesLeftInLatin() {
        val ar = catalog.getJSONObject("ar")
        assertEquals("عند الإرسال", ar.getString(normalized("When you send")))
        assertEquals("لوحة تحكم Hermes", ar.getString(normalized("Hermes dashboard")))
        assertEquals("بوابة Hermes", ar.getString(normalized("Hermes gateway")))
        // The progress word keeps its own meaning ("sending in progress") now that the section has its own title.
        assertEquals("جارٍ الإرسال", ar.getString(normalized("Sending")))
        assertNotEquals(ar.getString(normalized("Sending")), ar.getString(normalized("When you send")))
    }

    @Test fun healthWordsAndSearchWordsAreInTheCheckedLists() {
        assertTrue(PluginHealthStrings.containsAll(listOf("Beacon plugin", "Hermes dashboard", "Hermes gateway", "Working", "Hermes is busy", "The Beacon plugin isn't installed")))
        val keywords = SettingsStrings.filter { it.startsWith("Notification delivery") || it.startsWith("while replying") }
        assertEquals(listOf("while replying steer queue change the reply send after", "Notification delivery keep connected").sorted(), keywords.sorted())
    }
}
