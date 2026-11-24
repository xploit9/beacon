package dev.hermes.android.health

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.core.Release
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.PluginHealthRows
import dev.hermes.android.ui.PluginRepairContent
import dev.hermes.android.ui.SettingsDivider
import dev.hermes.android.ui.SettingsSection
import dev.hermes.android.ui.pluginHealthOf
import dev.hermes.android.updates.AppUpdateContent
import dev.hermes.android.updates.AppUpdateUi
import dev.hermes.android.updates.PluginProbe
import dev.hermes.android.updates.PluginRow
import dev.hermes.android.updates.RepairFinding
import dev.hermes.android.updates.RepairUi
import dev.hermes.android.updates.UpdatePhase
import dev.hermes.android.updates.pluginRowFor
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * A stock Hermes (no PyYAML) answers with plugin 1.2.2 and blank versions. Connection must show settled rows with Repair Beacon,
 * and App updates must offer the plugin update. Both are drawn from the real mappings fed with that answer, in four looks.
 * Pictures land in files/blank-plugin-version/.
 */
class BlankPluginVersionShotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val release = Release(123, "1.2.3", "dev.hermes.android.stable", "https://github.com/xploit9/beacon/releases/download/v1.2.3/Beacon-1.2.3.apk",
        "b".repeat(64), 40L * 1024 * 1024, "a".repeat(64), "1.2.3", "- Beacon repairs itself from the app", "2026-11-22T09:00:00Z")
    private val blankAnswer = JSONObject()
        .put("plugin", JSONObject().put("running", "").put("installed", "").put("gateway", ""))
        .put("dashboard", JSONObject().put("current", true))
        .put("gateway", JSONObject().put("known", true).put("running", true).put("busy", false).put("current", true))
        .put("calls", JSONObject().put("ready", true).put("provider", "gemini").put("reason", ""))
        .put("push", JSONObject().put("ready", true).put("reason", ""))
    private val page = mutableStateOf("connection")

    private var zenBefore = ""
    private fun shell(command: String): String = runCatching {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString().trim() }
    }.getOrDefault("")

    // No banner or open shade over the pictures; do-not-disturb is put back afterwards.
    @Before fun silenceNotifications() {
        zenBefore = shell("settings get global zen_mode")
        shell("cmd notification set_dnd on")
        shell("cmd statusbar collapse")
        compose.activity.getSystemService(android.app.NotificationManager::class.java).cancelAll()
    }

    @After fun restoreNotifications() {
        shell(if (zenBefore == "0" || zenBefore.isBlank() || zenBefore == "null") "cmd notification set_dnd off" else "settings put global zen_mode $zenBefore")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val dir = File(compose.activity.filesDir, "blank-plugin-version").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun shoot(tag: String, dark: Boolean, language: String, rtl: Boolean, scale: Float) {
        val health = requireNotNull(pluginHealthOf(blankAnswer, keepConnected = true, voiceKeySaved = true))
        val row = pluginRowFor(PluginProbe.Unreadable, "1.2.3")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                NativeLocalization(language) { BeaconTheme(dark = dark) { Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.systemBarsPadding().widthIn(max = 411.dp)) {
                        if (page.value == "connection") Column {
                            SettingsSection("Beacon plugin") {
                                PluginHealthRows(health, null, false) {}
                                SettingsDivider()
                                PluginRepairContent(RepairUi(), {}, RepairFinding.OutOfDate)
                            }
                        } else AppUpdateContent(AppUpdateUi(phase = UpdatePhase.Available, release = release, latest = release, checkedAt = 1_000_000_000_000L, plugin = row),
                            "1.2.2", false, 1_000_000_000_000L, onCheck = {}, onUpdate = {}, onRetry = {}, onCancel = {}, onAllow = {})
                    }
                } } }
            }
        }
        if (language != "en") compose.waitUntil(10_000) { (compose.activity.application as BeaconApplication).cachedTranslations != null }
        page.value = "connection"; compose.waitForIdle()
        compose.onNodeWithTag("plugin-health-status").assertIsDisplayed()
        compose.onNodeWithTag("plugin-health-version").assertIsDisplayed()
        compose.onNodeWithTag("plugin-repair").assertIsDisplayed()
        if (language == "en") {
            compose.onNodeWithTag("plugin-health-version").assertTextContains("Unknown")
            compose.onNodeWithTag("plugin-repair").assertTextContains("Repair Beacon")
        }
        capture("connection-$tag")
        page.value = "updates"; compose.waitForIdle()
        compose.onNodeWithTag("update-hero").assertIsDisplayed()
        capture("app-updates-$tag")
    }

    @Test(timeout = 120_000) fun blankVersionInDark() = shoot("dark", true, "en", false, 1f)
    @Test(timeout = 120_000) fun blankVersionInArabicRightToLeft() = shoot("ar-dark", true, "ar", true, 1f)
    @Test(timeout = 120_000) fun blankVersionAtLargeText() = shoot("large-light", false, "en", false, 1.3f)
    @Test(timeout = 120_000) fun blankVersionInArabicAtLargeText() = shoot("ar-large", false, "ar", true, 1.3f)
}
