package dev.hermes.android.uiux.drawer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.AppState
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.data.BotModeBot
import dev.hermes.android.data.BotModeSpace
import dev.hermes.android.data.NativeBotModeController
import dev.hermes.android.data.NativeBotModeState
import dev.hermes.android.preferences
import dev.hermes.android.ui.NativeBotModeSidebar
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.SessionList
import dev.hermes.android.ui.SidebarNavigation
import dev.hermes.android.ui.navigationMore
import dev.hermes.android.ui.navigationShown
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Switching Bot Mode on and off must not move anything under the drawer's header (owner, 8 Nov 2026: "I dont want thing jerking around").
 * The real drawer is drawn as the app draws it (Bot Mode's roster list, or the chat history list, each with the same navigation above it),
 * the mode is switched several times, and in every state the position and size of the header, New chat/New thread (or New bot/New space),
 * every tile and the More row must be the same as in the first state, at a normal and a large font, and in a right-to-left layout.
 */
class BotModeDrawerNoMoveUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val twoBots = listOf(BotModeBot("default", "Default", "Your main assistant"), BotModeBot("coder", "Coder", "Reviews code"))
    private val oneSpace = listOf(BotModeSpace("room-1", "Review room", listOf("default", "coder"), updatedAt = 1_700_000_000L))

    @Suppress("UNCHECKED_CAST")
    private fun ui(vm: BeaconViewModel) = BeaconViewModel::class.java.getDeclaredField("_ui").apply { isAccessible = true }.get(vm) as MutableStateFlow<AppState>

    @Suppress("UNCHECKED_CAST")
    private fun roster(vm: BeaconViewModel) = NativeBotModeController::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(vm.botMode) as MutableStateFlow<NativeBotModeState>

    /** x, y, width and height in pixels, unclipped. */
    private fun rect(tag: String): List<Float> {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return listOf(node.positionInRoot.x, node.positionInRoot.y, node.size.width.toFloat(), node.size.height.toFloat())
    }

    /** What the drawer holds under the header, slot by slot, for the mode and font scale: tag per slot. */
    private fun slots(botMode: Boolean, fontScale: Float): Map<String, String> = buildMap {
        put("header", if (botMode) "bot-mode-header" else "navigation-profile")
        put("action-1", if (botMode) "bot-mode-sidebar-new-bot" else "navigation-new-chat")
        put("action-2", if (botMode) "bot-mode-sidebar-new-chat" else "navigation-new-thread")
        navigationShown(fontScale, botMode).forEachIndexed { index, name -> put("tile-$index", "navigation-${name.lowercase()}") }
        if (navigationMore(fontScale, botMode).isNotEmpty()) put("more", "navigation-more")
    }

    private fun measure(botMode: Boolean, fontScale: Float): Map<String, List<Float>> = slots(botMode, fontScale).mapValues { (_, tag) -> rect(tag) }

    private fun switchMode(vm: BeaconViewModel, botMode: Boolean) {
        compose.runOnIdle { ui(vm).value = ui(vm).value.copy(botModeEnabled = botMode) }
        compose.waitForIdle()
        // The roster is read once when the Bot Mode list opens (with no Hermes behind it, that clears it); an injected list stands for one Hermes answered.
        if (botMode) {
            compose.runOnIdle { roster(vm).value = NativeBotModeState(bots = twoBots, spaces = oneSpace, loaded = true) }
            compose.waitForIdle()
        }
    }

    private fun withDrawers(language: String, rtl: Boolean, fontScale: Float, block: (BeaconViewModel) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val savedPreferences = runBlocking { app.preferences.data.first() }
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        try {
            runBlocking {
                app.readTranslations()
                app.preferences.edit {
                    it[stringPreferencesKey("origin")] = ""
                    it[booleanPreferencesKey("setup_complete")] = true
                    it[booleanPreferencesKey("setup_active")] = false
                }
            }
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
                ui(vm).value = AppState(origin = "", tab = "Chat", connected = false, initialChatReady = true, restoringSession = false, setupComplete = true, botModeEnabled = false)
            }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    NativeLocalization(language) { BeaconTheme {
                        val current by vm.ui.collectAsState()
                        // As BeaconActivity draws it: the Bot Mode roster list, or the chat history list, each with the navigation above it.
                        Box(Modifier.requiredSize(340.dp, 720.dp)) {
                            if (current.botModeEnabled) NativeBotModeSidebar(vm, Modifier.fillMaxSize(), beforeRoster = { SidebarNavigation(vm) })
                            else SessionList(vm, Modifier.fillMaxSize().testTag("navigation-menu"), beforeHistory = { SidebarNavigation(vm) })
                        }
                    } }
                }
            }
            compose.waitForIdle()
            block(vm)
        } finally {
            compose.runOnUiThread { store.clear() }
            runBlocking { app.preferences.updateData { savedPreferences } }
        }
    }

    private fun assertNothingMoves(language: String, rtl: Boolean, fontScale: Float, toggles: Int, pauseMillis: Long = 0) = withDrawers(language, rtl, fontScale) { vm ->
        val baseline = measure(botMode = false, fontScale)
        val report = StringBuilder("font scale $fontScale, $language${if (rtl) " (right-to-left)" else ""}\nnormal: $baseline\n")
        var botMode = false
        repeat(toggles) { step ->
            if (pauseMillis > 0) Thread.sleep(pauseMillis)
            botMode = !botMode
            switchMode(vm, botMode)
            if (pauseMillis > 0) Thread.sleep(pauseMillis)
            compose.onNodeWithTag(if (botMode) "bot-mode-header" else "navigation-profile").assertExists()
            compose.onAllNodesWithTag(if (botMode) "navigation-profile" else "bot-mode-header").assertCountEquals(0)
            val now = measure(botMode, fontScale)
            report.append("toggle ${step + 1} (${if (botMode) "Bot Mode" else "normal"}): $now\n")
            assertEquals("The same slots are drawn in both modes", baseline.keys, now.keys)
            baseline.forEach { (slot, expected) ->
                val actual = now.getValue(slot)
                expected.indices.forEach { i ->
                    assertEquals("$slot moved after toggle ${step + 1} (${if (botMode) "Bot Mode" else "normal"}) at font scale $fontScale: expected $expected, was $actual", expected[i], actual[i], 0.5f)
                }
            }
        }
        // Kept for the report: the measured bounds in both modes.
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val dir = File(requireNotNull(app.getExternalFilesDir(null)), "botmode-shots").apply { mkdirs() }
        File(dir, "bounds-$language-$fontScale.txt").writeText(report.toString())
    }

    @Test fun nothingBelowTheHeaderMovesWhenBotModeSwitchesAtNormalText() = assertNothingMoves("en", rtl = false, fontScale = 1f, toggles = 5)

    @Test fun nothingBelowTheHeaderMovesAtALargeFontWithTheMoreRow() = assertNothingMoves("en", rtl = false, fontScale = 1.3f, toggles = 5)

    @Test fun nothingBelowTheHeaderMovesAtTheLargestFont() = assertNothingMoves("en", rtl = false, fontScale = 2f, toggles = 3)

    @Test fun nothingBelowTheHeaderMovesInArabicRightToLeft() = assertNothingMoves("ar", rtl = true, fontScale = 1f, toggles = 3)

    @Test fun nothingBelowTheHeaderMovesInArabicRightToLeftAtALargeFont() = assertNothingMoves("ar", rtl = true, fontScale = 1.3f, toggles = 3)

    /** One font scale per test: a Compose test sets its content once. */
    private fun headerMatchesThePill(fontScale: Float) = withDrawers("en", false, fontScale) { vm ->
        val pill = rect("navigation-profile")
        switchMode(vm, true)
        val header = rect("bot-mode-header")
        assertEquals("Header height at font scale $fontScale", pill[3], header[3], 0.5f)
        assertEquals("Header width at font scale $fontScale", pill[2], header[2], 0.5f)
        assertEquals("Header left at font scale $fontScale", pill[0], header[0], 0.5f)
        assertEquals("Header top at font scale $fontScale", pill[1], header[1], 0.5f)
        val density = ApplicationProvider.getApplicationContext<BeaconApplication>().resources.displayMetrics.density
        assertTrue("The header is at least 56 dp tall: ${header[3]} px", header[3] >= 56f * density - 0.5f)
    }

    @Test fun theHeaderIsExactlyAsBigAsTheProfilePillAtFontScale085() = headerMatchesThePill(0.85f)
    @Test fun theHeaderIsExactlyAsBigAsTheProfilePillAtFontScale100() = headerMatchesThePill(1f)
    @Test fun theHeaderIsExactlyAsBigAsTheProfilePillAtFontScale115() = headerMatchesThePill(1.15f)
    @Test fun theHeaderIsExactlyAsBigAsTheProfilePillAtFontScale130() = headerMatchesThePill(1.3f)
    @Test fun theHeaderIsExactlyAsBigAsTheProfilePillAtFontScale160() = headerMatchesThePill(1.6f)
    @Test fun theHeaderIsExactlyAsBigAsTheProfilePillAtFontScale200() = headerMatchesThePill(2f)

    /** The screen video of three toggles (owner request): drawn slowly while the device records, only in the explicit synthetic probe run. */
    @Test fun threeTogglesAreRecordedOnTheScreen() {
        assumeTrue("Needs the synthetic UI probe flag", InstrumentationRegistry.getArguments().getString("native_ui_probe") == "1")
        val video = "/data/local/tmp/botmode-three-toggles.mp4"
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = automation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        shell("rm -f $video")
        // screenrecord ends cleanly on SIGINT; its time limit is only a safety net.
        val recording = automation.executeShellCommand("screenrecord --time-limit 40 --bit-rate 4000000 $video")
        try {
            Thread.sleep(1500)
            assertNothingMoves("en", rtl = false, fontScale = 1f, toggles = 3, pauseMillis = 1500)
            Thread.sleep(1500)
        } finally {
            shell("pkill -2 screenrecord")
            Thread.sleep(2500)
            runCatching { recording.close() }
        }
        val size = shell("stat -c %s $video").toLongOrNull() ?: 0L
        assertTrue("The recording was written ($size bytes)", size > 10_000)
    }
}
