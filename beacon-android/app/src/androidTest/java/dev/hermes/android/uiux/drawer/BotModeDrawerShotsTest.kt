package dev.hermes.android.uiux.drawer

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Review screenshots of the drawer in normal chats and in Bot Mode, each in dark, light, Arabic dark (a real
 * right-to-left layout) and light at 1.3x text. Synthetic roster only; PNGs go under the app's external files `botmode-shots`.
 * Run it with `--arg native_ui_probe=1` through device-test.py.
 */
class BotModeDrawerShotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val shots by lazy { File(requireNotNull(app.getExternalFilesDir(null)), "botmode-shots").apply { mkdirs() } }

    private enum class Look(val key: String, val dark: Boolean, val arabic: Boolean, val fontScale: Float) {
        Dark("dark", true, false, 1f), Light("light", false, false, 1f), ArabicDark("ar-dark", true, true, 1f), LargeLight("large-light", false, false, 1.3f)
    }
    private enum class Screen(val key: String, val botMode: Boolean) { Normal("normal", false), BotMode("bot-mark", true) }
    private val look = mutableStateOf(Look.Dark)

    private val bots = listOf(BotModeBot("default", "Default", "Your main assistant"), BotModeBot("coder", "Coder", "Reviews code"), BotModeBot("writer", "Writer", "Drafts and polishes your text"))
    private val spaces = listOf(BotModeSpace("room-1", "Review room", listOf("default", "coder"), updatedAt = 1_700_000_000L))

    private var priorAnimatorScale = "null"
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }

    @Before fun prepare() {
        assumeTrue("Needs the synthetic UI probe flag", InstrumentationRegistry.getArguments().getString("native_ui_probe") == "1")
        // The Arabic catalog is read before the first frame, so a shot never catches the English fallback.
        runBlocking { app.readTranslations() }
        priorAnimatorScale = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 0")
    }

    @After fun restoreAnimations() {
        if (InstrumentationRegistry.getArguments().getString("native_ui_probe") != "1") return
        shell(if (priorAnimatorScale == "null" || priorAnimatorScale.isBlank()) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $priorAnimatorScale")
    }

    @Suppress("UNCHECKED_CAST")
    private fun ui(vm: BeaconViewModel) = BeaconViewModel::class.java.getDeclaredField("_ui").apply { isAccessible = true }.get(vm) as MutableStateFlow<AppState>

    @Suppress("UNCHECKED_CAST")
    private fun roster(vm: BeaconViewModel) = NativeBotModeController::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(vm.botMode) as MutableStateFlow<NativeBotModeState>

    private fun show(vm: BeaconViewModel, next: Screen, nextLook: Look) {
        look.value = nextLook
        compose.runOnIdle { ui(vm).value = ui(vm).value.copy(botModeEnabled = next.botMode) }
        compose.waitForIdle()
        if (next.botMode) {
            compose.runOnIdle { roster(vm).value = NativeBotModeState(bots = bots, spaces = spaces, loaded = true) }
            compose.waitForIdle()
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(400, 4000)
    }

    private var previousShot: Bitmap? = null

    /** Every state differs from the one before it, so a picture equal to the previous one is a frame that was not drawn yet: retake it. */
    private fun shoot(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var bitmap = requireNotNull(automation.takeScreenshot()) { "No screenshot" }
        var attempts = 0
        while (previousShot?.sameAs(bitmap) == true && attempts++ < 10) {
            bitmap.recycle()
            automation.waitForIdle(300, 3000)
            Thread.sleep(500)
            bitmap = requireNotNull(automation.takeScreenshot()) { "No screenshot" }
        }
        assertTrue("$name is a new picture, not the one before it", previousShot?.sameAs(bitmap) != true)
        File(shots, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        previousShot?.recycle()
        previousShot = bitmap
    }

    @Test fun theDrawerInNormalChatsAndInBotModeWithEachHeader() {
        val store = ViewModelStore()
        val savedPreferences = runBlocking { app.preferences.data.first() }
        lateinit var vm: BeaconViewModel
        try {
            runBlocking {
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
                val current = look.value
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, current.fontScale),
                    LocalLayoutDirection provides if (current.arabic) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    NativeLocalization(if (current.arabic) "ar" else "en") {
                        BeaconTheme(dark = current.dark) {
                            val state by vm.ui.collectAsState()
                            Surface(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                                    Box(Modifier.width(340.dp).fillMaxHeight()) {
                                        if (state.botModeEnabled) NativeBotModeSidebar(vm, Modifier.fillMaxSize(), beforeRoster = { SidebarNavigation(vm) })
                                        else SessionList(vm, Modifier.fillMaxSize().testTag("navigation-menu"), beforeHistory = { SidebarNavigation(vm) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            for (next in Screen.entries) for (nextLook in Look.entries) {
                show(vm, next, nextLook)
                shoot("drawer-${next.key}-${nextLook.key}")
            }
        } finally {
            compose.runOnUiThread { store.clear() }
            runBlocking { app.preferences.updateData { savedPreferences } }
        }
    }
}
