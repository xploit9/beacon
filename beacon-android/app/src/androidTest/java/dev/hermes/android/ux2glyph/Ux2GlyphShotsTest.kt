package dev.hermes.android.ux2glyph

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.core.Release
import dev.hermes.android.ui.AppearanceContent
import dev.hermes.android.ui.BeaconPresence
import dev.hermes.android.ui.BeaconPresenceCard
import dev.hermes.android.ui.MissingBeaconPluginState
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.NativeSkins
import dev.hermes.android.ui.ReasoningSkeleton
import dev.hermes.android.ui.SettingsList
import dev.hermes.android.ui.SettingsSection
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.SettingsStatusHero
import dev.hermes.android.ui.SettingsValueRow
import dev.hermes.android.ui.rememberAppearanceSwatches
import dev.hermes.android.ui.uiText
import dev.hermes.android.updates.AppUpdateContent
import dev.hermes.android.updates.AppUpdateUi
import dev.hermes.android.updates.PluginRow
import dev.hermes.android.updates.PluginStatus
import dev.hermes.android.updates.StepRow
import dev.hermes.android.updates.StepState
import dev.hermes.android.updates.UpdatePhase
import dev.hermes.android.updates.UpdateStep
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Lane ux2-glyph on a device: the five places the one working glyph, the System theme word and the plugin card show, each in the four
 * looks of the pass (dark, light, Arabic dark with a real right-to-left layout, light at 1.3x text). Everything is drawn from facts alone,
 * with no Hermes and no network. The pictures land in files/ux2-glyph/ and are pulled by the runner of the lane.
 *
 * Notifications are silenced while the screen is captured, so no banner covers the page; the do-not-disturb setting is put back afterwards.
 */
class Ux2GlyphShotsTest {
    // A sleeping screen resumes no activity (run 2 found the emulator asleep), so the screen is woken before the activity launches.
    @get:Rule(order = 0) val wake = object : org.junit.rules.ExternalResource() {
        override fun before() {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            val until = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < until && "mWakefulness=Awake" !in shell("dumpsys power")) Thread.sleep(250)
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private val mb = 1024L * 1024
    private val now = 1_000_000_000_000L
    private val release = Release(123, "1.1.23", "dev.hermes.android.stable", "https://github.com/xploit9/beacon/releases/download/v1.1.23/Beacon-1.1.23.apk",
        "b".repeat(64), 40 * mb, "a".repeat(64), "1.1.23", "- Updates show every step", "2026-11-01T09:00:00Z")

    private class Look(val tag: String, val dark: Boolean, val language: String, val rtl: Boolean, val fontScale: Float, val system: String, val pluginCard: String)
    private class Shot(val name: String, val content: @Composable () -> Unit)

    private var zenBefore = ""
    private var scaleBefore = ""
    private val shot = mutableStateOf(0)
    // `--arg motion=on` turns the phone's animations on for the run, so the dust core is drawn moving; the setting is put back afterwards.
    private val motion = InstrumentationRegistry.getArguments().getString("motion") == "on"

    private fun shell(command: String): String = runCatching {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString().trim() }
    }.getOrDefault("")

    @Before fun silenceNotifications() {
        zenBefore = shell("settings get global zen_mode")
        shell("cmd notification set_dnd on")
        shell("cmd statusbar collapse")
        compose.activity.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        scaleBefore = shell("settings get global animator_duration_scale")
        if (motion) shell("settings put global animator_duration_scale 1")
    }

    @After fun restoreNotifications() {
        // 0 = off; anything else is put back as it was.
        shell(if (zenBefore == "0" || zenBefore.isBlank() || zenBefore == "null") "cmd notification set_dnd off" else "settings put global zen_mode $zenBefore")
        if (motion) {
            shell(if (scaleBefore.isBlank() || scaleBefore == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $scaleBefore")
            val now = shell("settings get global animator_duration_scale")
            check(now == scaleBefore) { "the animation scale was $scaleBefore and is now $now" }
        }
    }

    private val stepper = AppUpdateUi(phase = UpdatePhase.Downloading, release = release, latest = release, checkedAt = now,
        plugin = PluginRow(PluginStatus.Updating, "1.1.22", "1.1.23"),
        pluginSteps = listOf(StepRow(UpdateStep.PluginDownload, StepState.Done), StepRow(UpdateStep.PluginInstall, StepState.Active), StepRow(UpdateStep.PluginGateway),
            StepRow(UpdateStep.PluginDashboard), StepRow(UpdateStep.PluginDone)),
        appSteps = listOf(StepRow(UpdateStep.AppDownload), StepRow(UpdateStep.AppVerify), StepRow(UpdateStep.AppInstall), StepRow(UpdateStep.AppDone)))

    private val shots = listOf(
        Shot("1-appearance") { AppearanceContent("system", NativeSkins.first(), rememberAppearanceSwatches(), {}, {}) },
        Shot("2-missing-plugin") { Column(Modifier.fillMaxWidth().padding(top = 24.dp)) { MissingBeaconPluginState() } },
        Shot("3-old-plugin") { Column(Modifier.fillMaxWidth().padding(top = 24.dp)) { MissingBeaconPluginState(update = true) } },
        Shot("4-stepper-active") { AppUpdateContent(stepper, "1.1.22", false, now, onCheck = {}, onUpdate = {}, onRetry = {}, onCancel = {}, onAllow = {}, onReview = {}) },
        Shot("7-presence-working") { Column(Modifier.fillMaxWidth().padding(top = 16.dp)) { BeaconPresenceCard("Beacon", "Hasn't checked yet", BeaconPresence.Working, false, false, true, {}) } },
        Shot("5-reasoning-skeleton") { Column(Modifier.fillMaxWidth().padding(16.dp)) { ReasoningSkeleton() } },
        Shot("6-settings-working") {
            SettingsList {
                item { SettingsStatusHero(SettingsStatus.Working, uiText("Beacon plugin"), line = uiText("Checking…"), icon = Icons.Rounded.Hub) }
                item {
                    SettingsSection(testTag = "ux2-working-section") {
                        SettingsValueRow(uiText("Beacon plugin"), icon = Icons.Rounded.Hub, value = "1.1.22 → 1.1.23", status = SettingsStatus.Working, statusLabel = uiText("Checking…"), testTag = "ux2-working-row")
                    }
                }
            }
        },
    )

    private fun capture(name: String) {
        compose.waitForIdle()
        // The screen shows the new page a few frames after the tree has it; a picture taken at once can be the page before (or a blank one).
        Thread.sleep(800)
        compose.waitForIdle()
        val directory = java.io.File(compose.activity.filesDir, "ux2-glyph").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun shoot(look: Look) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, look.fontScale),
                LocalLayoutDirection provides if (look.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                NativeLocalization(look.language) { BeaconTheme(dark = look.dark) { Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.systemBarsPadding().widthIn(max = 411.dp)) { shots[shot.value].content() }
                } } }
            }
        }
        if (look.language != "en") compose.waitUntil(10_000) { (compose.activity.application as BeaconApplication).cachedTranslations != null }
        shots.forEachIndexed { index, one ->
            shot.value = index
            compose.waitForIdle()
            checkFacts(one, look)
            capture("${one.name}-${look.tag}")
        }
    }

    /** What every look must hold; the words are the catalog's own for the look's language. */
    private fun checkFacts(shot: Shot, look: Look) {
        when (shot.name) {
            // The third theme word is System in every language, whether the row shows segments or (at 1.3x) the menu row.
            "1-appearance" -> {
                compose.onNodeWithTag("appearance-settings").assertIsDisplayed()
                check(compose.onAllNodes(hasText(look.system)).fetchSemanticsNodes().isNotEmpty()) { "${look.tag}: the theme row does not say ${look.system}" }
                check(compose.onAllNodes(hasText(if (look.language == "en") "Follow device" else "اتباع الجهاز")).fetchSemanticsNodes().isEmpty()) { "${look.tag}: Follow device is still there" }
                // Segments at normal size, the menu row at 1.3x: never both.
                val segments = compose.onAllNodesWithTag("choice-option:Theme:dark").fetchSemanticsNodes().size
                check(if (look.fontScale >= 1.3f) segments == 0 else if (look.language == "en") segments == 1 else segments <= 1) { "${look.tag}: $segments theme segments" }
            }
            "2-missing-plugin", "3-old-plugin" -> {
                compose.onNodeWithTag("missing-plugin-illustration").assertIsDisplayed()
                compose.onNodeWithTag("missing-plugin-install").assertIsDisplayed()
                if (shot.name == "2-missing-plugin") check(compose.onAllNodes(hasText(look.pluginCard)).fetchSemanticsNodes().isNotEmpty()) { "${look.tag}: the card does not say ${look.pluginCard}" }
            }
            "4-stepper-active" -> compose.onNodeWithTag("update-step:PluginInstall").assertIsDisplayed()
            "5-reasoning-skeleton" -> compose.onNodeWithTag("reasoning-skeleton").assertIsDisplayed()
            "7-presence-working" -> compose.onNodeWithTag("navigation-beacon").assertIsDisplayed()
            "6-settings-working" -> compose.onNodeWithTag("ux2-working-row").assertIsDisplayed()
        }
    }

    @Test(timeout = 300_000) fun fivePlacesInDark() = shoot(Look("dark", true, "en", false, 1f, "System", "Add the Beacon plugin to Hermes"))
    @Test(timeout = 300_000) fun fivePlacesInLight() = shoot(Look("light", false, "en", false, 1f, "System", "Add the Beacon plugin to Hermes"))
    @Test(timeout = 300_000) fun fivePlacesInArabicDarkRightToLeft() = shoot(Look("ar-dark", true, "ar", true, 1f, "النظام", "أضِف ملحق Beacon إلى Hermes"))
    @Test(timeout = 300_000) fun fivePlacesAtLargeTextInLight() = shoot(Look("large-light", false, "en", false, 1.3f, "System", "Add the Beacon plugin to Hermes"))
}
