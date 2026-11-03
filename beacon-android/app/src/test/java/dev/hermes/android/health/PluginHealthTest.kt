package dev.hermes.android.health

import dev.hermes.android.ui.PLUGIN_HEALTH_PATH
import dev.hermes.android.ui.PluginHealthStrings
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.normalized
import dev.hermes.android.ui.pluginHealthOf
import dev.hermes.android.ui.settingsDescriptions
import dev.hermes.android.ui.settingsLabel
import dev.hermes.android.ui.settingsRowMatches
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** How the plugin's `/mobile/health` answer becomes the Plugin section of Settings › Connection. */
class PluginHealthTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }

    private fun answer(
        running: String = "1.1.23", installed: String = "1.1.23", gateway: String = "1.1.23",
        dashboardCurrent: Boolean? = true, gatewayKnown: Boolean = true, gatewayRunning: Boolean = true, gatewayBusy: Boolean = false, gatewayCurrent: Boolean? = true,
        callsReady: Boolean = true, callsReason: String = "", pushReady: Boolean = true, pushReason: String = "",
    ) = JSONObject()
        .put("plugin", JSONObject().put("running", running).put("installed", installed).put("gateway", gateway))
        .put("dashboard", JSONObject().put("current", dashboardCurrent ?: JSONObject.NULL))
        .put("gateway", JSONObject().put("known", gatewayKnown).put("running", gatewayRunning).put("busy", gatewayBusy).put("current", gatewayCurrent ?: JSONObject.NULL))
        .put("calls", JSONObject().put("ready", callsReady).put("provider", "gemini").put("reason", callsReason))
        .put("push", JSONObject().put("ready", pushReady).put("reason", pushReason))

    @Test fun aHealthyPluginReadsHealthyRowByRow() {
        val health = pluginHealthOf(answer(), keepConnected = false, voiceKeySaved = true)!!
        assertEquals("1.1.23", health.version)
        assertEquals("Up to date", health.dashboard?.text)
        assertEquals("Running", health.gateway?.text)
        assertEquals("Ready", health.calls.text)
        assertEquals("Ready", health.notifications.text)
        assertEquals(SettingsStatus.Ok, health.overall)
        assertEquals("Healthy", health.overallText)
    }

    @Test fun theVersionShownIsTheInstalledOneAndAnUnreadableVersionHidesTheSection() {
        assertEquals("1.1.24", pluginHealthOf(answer(installed = "1.1.24"), false)!!.version)
        assertEquals("1.1.23", pluginHealthOf(answer(installed = "", running = "1.1.23"), false)!!.version)
        assertNull(pluginHealthOf(answer(installed = "", running = ""), false))
        assertNull(pluginHealthOf(answer(installed = "not a version", running = "x"), false))
    }

    @Test fun aDashboardOrGatewayRunningOldCodeNeedsARestart() {
        val dashboard = pluginHealthOf(answer(dashboardCurrent = false), false)!!
        assertEquals(SettingsStatus.Attention, dashboard.dashboard?.status)
        assertEquals("Needs restart", dashboard.dashboard?.text)
        assertEquals(SettingsStatus.Attention, dashboard.overall)
        assertEquals("Needs attention", dashboard.overallText)
        val gateway = pluginHealthOf(answer(gatewayCurrent = false), false)!!
        assertEquals("Needs restart", gateway.gateway?.text)
        assertEquals(SettingsStatus.Attention, gateway.gateway?.status)
    }

    @Test fun theGatewayIsStoppedBusyOrRunning() {
        val stopped = pluginHealthOf(answer(gatewayRunning = false), false)!!
        assertEquals("Stopped", stopped.gateway?.text)
        assertEquals(SettingsStatus.Error, stopped.overall)
        assertEquals("Not working", stopped.overallText)
        val busy = pluginHealthOf(answer(gatewayBusy = true), false)!!
        assertEquals("Working", busy.gateway?.text)
        // A gateway at work is normal, not a fault, and not a success either: it is in progress, so no check mark.
        assertEquals(SettingsStatus.Working, busy.gateway?.status)
        assertEquals(SettingsStatus.Ok, busy.overall)
    }

    @Test fun whatThePluginCannotObserveIsNotShown() {
        val unknown = pluginHealthOf(answer(dashboardCurrent = null, gatewayKnown = false), false)!!
        assertNull(unknown.dashboard)
        assertNull(unknown.gateway)
        // Nothing else turns the pill: rows that are not shown say nothing.
        assertEquals(SettingsStatus.Ok, unknown.overall)
        val gatewayCurrentUnknown = pluginHealthOf(answer(gatewayCurrent = null), false)!!
        assertEquals("Running", gatewayCurrentUnknown.gateway?.text)
    }

    @Test fun callsSayWhyTheyAreNotReady() {
        fun calls(ready: Boolean, reason: String, key: Boolean? = true) = pluginHealthOf(answer(callsReady = ready, callsReason = reason), false, key)!!.calls
        assertEquals("Slow to start" to SettingsStatus.Attention, calls(true, "slow").let { it.text to it.status })
        assertEquals("Hermes is updating" to SettingsStatus.Attention, calls(false, "updating").let { it.text to it.status })
        assertEquals("Hermes took too long" to SettingsStatus.Error, calls(false, "timeout").let { it.text to it.status })
        assertEquals("Hermes is busy" to SettingsStatus.Working, calls(false, "busy").let { it.text to it.status })
        assertEquals("Not ready" to SettingsStatus.Error, calls(false, "unavailable").let { it.text to it.status })
        assertEquals("Not ready" to SettingsStatus.Error, calls(false, "").let { it.text to it.status })
        // The phone's own key decides too: Hermes being ready does not make a call without a key ready.
        assertEquals("Add key" to SettingsStatus.Attention, calls(true, "", key = false).let { it.text to it.status })
        assertEquals("Ready", calls(true, "", key = null).text)
    }

    @Test fun aCheckThatIsStillRunningDoesNotTurnThePillRed() {
        val health = pluginHealthOf(answer(callsReady = false, callsReason = "busy"), false)!!
        assertEquals(SettingsStatus.Working, health.calls.status)
        assertEquals(SettingsStatus.Ok, health.overall)
    }

    @Test fun notificationsAreReadyByPushOrByKeepConnected() {
        fun notifications(pushReady: Boolean, reason: String, keep: Boolean) = pluginHealthOf(answer(pushReady = pushReady, pushReason = reason), keep)!!.notifications
        assertEquals("Ready" to SettingsStatus.Ok, notifications(true, "", false).let { it.text to it.status })
        assertEquals("Keep connected is on" to SettingsStatus.Ok, notifications(false, "no_sender", true).let { it.text to it.status })
        // Keep connected off is the owner's choice: the hint shows, but the row is Off, not Attention.
        assertEquals("Keep connected is off" to SettingsStatus.Off, notifications(false, "no_sender", false).let { it.text to it.status })
        assertEquals("Not set up" to SettingsStatus.Attention, notifications(false, "no_phone", false).let { it.text to it.status })
        assertEquals("Keep connected is on", notifications(false, "no_phone", true).text)
    }

    @Test fun theWorstRowDecidesThePill() {
        val health = pluginHealthOf(answer(dashboardCurrent = false, gatewayRunning = false, callsReady = false, callsReason = "timeout"), false)!!
        assertEquals(SettingsStatus.Error, health.overall)
        val mixed = pluginHealthOf(answer(dashboardCurrent = false), true)!!
        assertEquals(SettingsStatus.Attention, mixed.overall)
    }

    @Test fun anAnswerWithoutItsRequiredPartsIsNotRead() {
        assertNull(pluginHealthOf(null, false))
        assertNull(pluginHealthOf(JSONObject(), false))
        assertNull(pluginHealthOf(JSONObject().put("plugin", JSONObject().put("installed", "1.1.23")), false))
        val noCalls = answer().also { it.remove("calls") }
        assertNull(pluginHealthOf(noCalls, false))
        val noPush = answer().also { it.remove("push") }
        assertNull(pluginHealthOf(noPush, false))
        // Wrong types are treated as not observed, not as true.
        val odd = answer().also { it.put("dashboard", JSONObject().put("current", "yes")) }
        assertNull(pluginHealthOf(odd, false)!!.dashboard)
    }

    @Test fun theAppAsksForTheRouteThePluginServes() {
        val source = File(app, "../../hermes-plugin/beacon/health_api.py").readText()
        assertTrue(source.contains("@router.get('/mobile/health')"))
        assertEquals("/api/plugins/beacon/mobile/health", PLUGIN_HEALTH_PATH)
    }

    @Test fun everyWordTheSectionCanShowIsTranslatedInEveryLanguage() {
        val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText())
        val cjk = Regex("""[぀-ヿ㐀-鿿가-힯]""")
        assertTrue(PluginHealthStrings.size >= 25)
        val missing = catalog.keys().asSequence().filter { it != "en" }.flatMap { language ->
            val strings = catalog.getJSONObject(language)
            PluginHealthStrings.filter { word ->
                val value = strings.optString(normalized(word))
                value.isBlank() || language !in setOf("ja", "ko", "zh", "zh-Hant") && cjk.containsMatchIn(value)
            }.map { "$language \"$it\"" }
        }.toList()
        assertTrue("These plugin health words are missing in a language:\n" + missing.joinToString("\n"), missing.isEmpty())
        // The only placeholder is the round-trip time, kept in every language.
        catalog.keys().asSequence().filter { it != "en" }.forEach { language ->
            assertTrue(language, catalog.getJSONObject(language).optString(normalized("Hermes answered in {0} ms")).contains("{0}"))
        }
    }

    @Test fun everyWordTheMappingCanReturnIsInTheTranslatedList() {
        val shown = mutableSetOf<String>()
        listOf(
            answer(), answer(dashboardCurrent = false, gatewayRunning = false), answer(gatewayBusy = true), answer(gatewayCurrent = false),
            answer(callsReason = "slow"), answer(callsReady = false, callsReason = "updating"), answer(callsReady = false, callsReason = "timeout"),
            answer(callsReady = false, callsReason = "busy"), answer(callsReady = false, callsReason = "unavailable"),
            answer(pushReady = false, pushReason = "no_sender"), answer(pushReady = false, pushReason = "no_phone"),
        ).forEach { json ->
            for (keep in listOf(false, true)) for (key in listOf(null, true, false)) {
                val health = pluginHealthOf(json, keep, key)!!
                shown += listOfNotNull(health.dashboard, health.gateway, health.calls, health.notifications).map { it.text } + health.overallText
            }
        }
        assertTrue("These words are not in PluginHealthStrings: " + (shown - PluginHealthStrings.toSet()), PluginHealthStrings.containsAll(shown))
    }

    @Test fun searchingPluginHealthFindsConnection() {
        fun finds(query: String) = settingsRowMatches("Connection", settingsLabel("Connection"), settingsDescriptions.getValue("Connection"), "", query)
        assertTrue(finds("plugin health"))
        assertTrue(finds("Plugin"))
        assertFalse(finds("steer"))
        assertNotNull(settingsDescriptions["Connection"])
    }
}
