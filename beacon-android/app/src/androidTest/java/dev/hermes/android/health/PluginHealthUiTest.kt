package dev.hermes.android.health

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.ui.HealthLine
import dev.hermes.android.ui.HealthTarget
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.PluginHealth
import dev.hermes.android.ui.PluginHealthRows
import dev.hermes.android.ui.SettingsSection
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.pluginHealthOf
import dev.hermes.android.ui.uiText
import dev.hermes.android.uiux.gaps.showPage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The Plugin rows on a device, from a synthetic answer: every verdict, Check now, and the right-to-left and large-text layouts. */
class PluginHealthUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun answer(
        dashboardCurrent: Boolean = true, gatewayRunning: Boolean = true, gatewayBusy: Boolean = false,
        callsReady: Boolean = true, callsReason: String = "", pushReady: Boolean = true,
    ) = JSONObject()
        .put("plugin", JSONObject().put("running", "1.1.23").put("installed", "1.1.23").put("gateway", "1.1.23"))
        .put("dashboard", JSONObject().put("current", dashboardCurrent))
        .put("gateway", JSONObject().put("known", true).put("running", gatewayRunning).put("busy", gatewayBusy).put("current", true))
        .put("calls", JSONObject().put("ready", callsReady).put("provider", "gemini").put("reason", callsReason))
        .put("push", JSONObject().put("ready", pushReady).put("reason", if (pushReady) "" else "no_sender"))

    private fun health(json: JSONObject = answer(), keepConnected: Boolean = false): PluginHealth = requireNotNull(pluginHealthOf(json, keepConnected, voiceKeySaved = true))

    @Test fun aHealthySectionShowsEveryRowAndCheckNowReportsTheRoundTrip() {
        var checks = 0
        var trip by mutableStateOf<Long?>(null)
        compose.showPage { SettingsSection("Beacon plugin") { PluginHealthRows(health(), trip, false) { checks++; trip = 84 } } }
        compose.onNodeWithTag("plugin-health-status").assertIsDisplayed().assertTextContains("Healthy")
        compose.onNodeWithTag("plugin-health-version").assertTextContains("1.1.23")
        compose.onNodeWithTag("plugin-health-dashboard").assertTextContains("Up to date")
        compose.onNodeWithTag("plugin-health-gateway").assertTextContains("Running")
        compose.onNodeWithTag("plugin-health-calls").assertTextContains("Ready")
        compose.onNodeWithTag("plugin-health-notifications").assertTextContains("Ready")
        compose.onNodeWithText("Hermes answered in 84 ms").assertDoesNotExist()
        compose.onNodeWithTag("plugin-health-check").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, checks) }
        compose.onNodeWithText("Hermes answered in 84 ms").assertIsDisplayed()
    }

    @Test fun eachFaultNamesItsReason() {
        val broken = health(answer(dashboardCurrent = false, gatewayRunning = false, callsReady = false, callsReason = "timeout", pushReady = false))
        compose.showPage { SettingsSection("Beacon plugin") { PluginHealthRows(broken, null, false) {} } }
        compose.onNodeWithTag("plugin-health-status").assertTextContains("Not working")
        compose.onNodeWithTag("plugin-health-dashboard").assertTextContains("Needs restart")
        compose.onNodeWithTag("plugin-health-gateway").assertTextContains("Stopped")
        compose.onNodeWithTag("plugin-health-calls").assertTextContains("Hermes took too long")
        compose.onNodeWithTag("plugin-health-notifications").assertTextContains("Turn on Keep connected")
    }

    @Test fun keepConnectedStandsInForPushAndABusyGatewayIsInProgressNotAFault() {
        compose.showPage { SettingsSection("Beacon plugin") { PluginHealthRows(health(answer(gatewayBusy = true, pushReady = false), keepConnected = true), null, false) {} } }
        compose.onNodeWithTag("plugin-health-notifications").assertTextContains("Keep connected is on")
        compose.onNodeWithTag("plugin-health-gateway").assertTextContains("Working")
        compose.onNodeWithTag("plugin-health-status").assertTextContains("Healthy")
    }

    @Test fun checkNowSaysCheckingWhileItRunsAndCannotBeTappedTwice() {
        var checks = 0
        compose.showPage { SettingsSection("Beacon plugin") { PluginHealthRows(health(), null, true) { checks++ } } }
        compose.onNodeWithTag("plugin-health-check").assertTextContains("Checking…").performClick()
        compose.runOnIdle { assertEquals(0, checks) }
    }

    @Test fun rowsThatNeedTheOwnerOpenWhereTheyAreFixedAndTheRestAreNotButtons() {
        val opened = mutableListOf<HealthTarget>()
        val trouble = health(answer(dashboardCurrent = false, gatewayRunning = false, callsReady = true, pushReady = false), keepConnected = false).copy(
            calls = pluginHealthOf(answer(), false, voiceKeySaved = false)!!.calls,
        )
        compose.showPage { SettingsSection("Beacon plugin") { PluginHealthRows(trouble, null, false, { opened += it }) {} } }
        compose.onNodeWithTag("plugin-health-dashboard").performClick()
        compose.onNodeWithTag("plugin-health-gateway").performClick()
        compose.onNodeWithTag("plugin-health-calls").performClick()
        compose.onNodeWithTag("plugin-health-notifications").performClick()
        compose.runOnIdle { assertEquals(listOf(HealthTarget.AppUpdates, HealthTarget.CheckNow, HealthTarget.VoiceCalls, HealthTarget.Notifications), opened) }
        // Health and Version only report; every tappable row is a 48dp button.
        compose.onNodeWithTag("plugin-health-status").assertHasNoClickAction()
        compose.onNodeWithTag("plugin-health-version").assertHasNoClickAction()
        for (tag in listOf("plugin-health-dashboard", "plugin-health-gateway", "plugin-health-calls", "plugin-health-notifications")) {
            compose.onNodeWithTag(tag).assertHasClickAction().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun keepConnectedOffAloneReadsHealthyAndStillOffersTheSwitch() {
        val opened = mutableListOf<HealthTarget>()
        compose.showPage { SettingsSection("Beacon plugin") { PluginHealthRows(health(answer(pushReady = false)), null, false, { opened += it }) {} } }
        compose.onNodeWithTag("plugin-health-status").assertTextContains("Healthy")
        compose.onNodeWithTag("plugin-health-notifications").assertTextContains("Turn on Keep connected").performClick()
        compose.runOnIdle { assertEquals(listOf(HealthTarget.Notifications), opened) }
    }

    @Test fun finishingACheckBringsItsAnswerIntoViewOnASmallScreen() {
        var signal by mutableStateOf(0)
        var trip by mutableStateOf<Long?>(null)
        compose.showPage {
            Column(Modifier.height(260.dp).verticalScroll(rememberScrollState()).testTag("box")) {
                SettingsSection("Beacon plugin") { PluginHealthRows(health(), trip, false, resultSignal = signal) {} }
            }
        }
        // Position and size are the layout's own: boundsInRoot is clipped to the scroll box, so it would call a row below the fold "inside".
        val boxNode = compose.onNodeWithTag("box").fetchSemanticsNode()
        val boxTop = boxNode.positionInRoot.y
        val boxBottom = boxTop + boxNode.size.height
        fun inside(tag: String): Boolean = compose.onNodeWithTag(tag).fetchSemanticsNode().let { node ->
            val top = node.positionInRoot.y
            top >= boxTop - 1 && top + node.size.height <= boxBottom + 1
        }
        assertFalse("Check now starts below the fold", inside("plugin-health-check"))
        compose.runOnIdle { trip = 84; signal = 1 }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Hermes answered in 84 ms").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { inside("plugin-health-check") }
        compose.onNodeWithText("Hermes answered in 84 ms").assertIsDisplayed()
    }

    /** Rows that ask something of the owner, in the four looks the review compares; the pictures are pulled from the app's files afterwards. */
    @Test fun rowsInTroubleKeepTheirTargetsInEveryLookAndAreSavedForReview() {
        val trouble = PluginHealth(
            "1.1.23",
            dashboard = HealthLine(SettingsStatus.Attention, "Needs restart", HealthTarget.AppUpdates),
            gateway = HealthLine(SettingsStatus.Working, "Working"),
            calls = HealthLine(SettingsStatus.Error, "Hermes took too long", HealthTarget.CheckNow),
            notifications = HealthLine(SettingsStatus.Attention, "Not set up", HealthTarget.Notifications),
        )
        var look by mutableStateOf("dark")
        compose.setContent {
            val rtl = look == "ar-dark"
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(base.density, fontScale = if (look == "large-light") 1.3f else 1f),
            ) {
                NativeLocalization(if (rtl) "ar" else "en") {
                    dev.hermes.android.BeaconTheme(dark = look == "dark" || rtl) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                                SettingsSection(uiText("Beacon plugin")) { PluginHealthRows(trouble, 84, false) {} }
                            }
                        }
                    }
                }
            }
        }
        val dir = File(compose.activity.filesDir, "plugin-health/synthetic").apply { mkdirs() }
        val saved = JSONArray()
        for (name in listOf("dark", "light", "ar-dark", "large-light")) {
            compose.runOnIdle { look = name }
            if (name == "ar-dark") compose.waitUntil(5_000) { compose.onAllNodesWithText("لوحة تحكم Hermes").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            for (tag in listOf("plugin-health-dashboard", "plugin-health-calls", "plugin-health-notifications")) compose.onNodeWithTag(tag).assertHasClickAction().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("plugin-health-gateway").assertHasNoClickAction()
            Thread.sleep(800)
            compose.waitForIdle()
            val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(dir, "$name-trouble.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            saved.put(JSONObject().put("case", "synthetic").put("file", "$name-trouble.png"))
        }
        File(compose.activity.filesDir, "plugin-health/synthetic.json").writeText(saved.toString(2), Charsets.UTF_8)
    }

    @Test fun theBusyGatewayShowsWorkingNotACheckMark() {
        compose.showPage { SettingsSection("Beacon plugin") { PluginHealthRows(health(answer(gatewayBusy = true)), null, false) {} } }
        compose.onNodeWithTag("plugin-health-gateway").assertTextContains("Working").assertHasNoClickAction()
        compose.onNodeWithTag("plugin-health-status").assertTextContains("Healthy")
    }

    @Test fun arabicInLargeTextKeepsEveryRowAndTheCheckNowTarget() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                NativeLocalization("ar") {
                    dev.hermes.android.BeaconTheme(dark = false) { SettingsSection("Beacon plugin") { PluginHealthRows(health(), 84, false) {} } }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("لوحة تحكم Hermes").fetchSemanticsNodes().isNotEmpty() }
        for (tag in listOf("plugin-health-status", "plugin-health-version", "plugin-health-dashboard", "plugin-health-gateway", "plugin-health-calls", "plugin-health-notifications")) {
            compose.onNodeWithTag(tag).assertExists().assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("plugin-health-check").assertHeightIsAtLeast(48.dp).assertExists()
        compose.onNodeWithText("ردّ Hermes خلال 84 مللي ثانية").assertExists()
    }
}
