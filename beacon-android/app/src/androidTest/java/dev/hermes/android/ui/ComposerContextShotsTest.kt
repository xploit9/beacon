package dev.hermes.android.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.AppState
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.ChatState
import dev.hermes.android.Message
import dev.hermes.android.core.json
import dev.hermes.android.core.Scope
import dev.hermes.android.uiux.gaps.appFlow
import dev.hermes.android.uiux.gaps.chatFlow
import dev.hermes.android.uiux.gaps.withClientlessVm
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Fresh 360 dp composer shots of the context ring: English light, English dark, Arabic dark, large text, and the Usage page after a tap. */
class ComposerContextShotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val shots by lazy { File(requireNotNull(app.getExternalFilesDir(null)), "ctx-125-shots").apply { mkdirs() } }
    private val session = "synthetic-chat"

    private enum class Look(val key: String, val dark: Boolean, val arabic: Boolean, val fontScale: Float) {
        Light("en-light", false, false, 1f), Dark("en-dark", true, false, 1f), ArabicDark("ar-dark", true, true, 1f), LargeLight("large-light", false, false, 1.3f)
    }
    private val look = mutableStateOf(Look.Light)
    private var priorAnimatorScale = "null"

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()
    }

    @Before fun prepare() {
        runBlockingRead()
        priorAnimatorScale = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 0")
    }

    private fun runBlockingRead() = kotlinx.coroutines.runBlocking { app.readTranslations() }

    @After fun restoreAnimations() {
        shell(if (priorAnimatorScale == "null" || priorAnimatorScale.isBlank()) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $priorAnimatorScale")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(400, 4000)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) { "No screenshot" }
        File(shots, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun composerContextIndicator() = withClientlessVm(compose) { vm ->
        compose.runOnIdle {
            val owner = Scope("https://hermes.example.com", "synthetic", vm.preferences.value.assistantProfile)
            BeaconViewModel::class.java.getDeclaredField("cachedOwnerScope").apply { isAccessible = true }.set(vm, owner)
            BeaconViewModel::class.java.getDeclaredField("sourceScope").apply { isAccessible = true }.set(vm, owner.session(session))
            vm.chatFlow.value = ChatState(sessionId = session, title = "Travel planning", raw = json("model" to "deepseek-v4.1-flash"),
                messages = listOf(
                    Message.from(json("role" to "user", "content" to "Plan a quiet weekend in Lisbon.", "row_id" to 1), 0, session),
                    Message.from(json("role" to "assistant", "content" to "Here is a calm two-day plan: two walks, one museum and an early dinner.", "row_id" to 2), 1, session),
                ))
            vm.appFlow.value = AppState(origin = "https://hermes.example.com", owner = "synthetic", profile = "default", authenticated = true,
                connected = true, loading = true, restoringSession = false, bootstrap = json("optional_features" to json("plugin" to "available")))
            vm.storeData("native-usage:${vm.ui.value.profile}:$session",
                json("input_tokens" to 18_400, "output_tokens" to 2_300, "context_window" to 1_000_000, "context_used" to 384_000))
        }
        compose.setContent {
            val current = look.value
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, current.fontScale),
                LocalLayoutDirection provides if (current.arabic) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                NativeLocalization(if (current.arabic) "ar" else "en") {
                    BeaconTheme(dark = current.dark) {
                        Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().statusBarsPadding().testTag("ctx-shot-host")) {
                                val ui by vm.ui.collectAsStateWithLifecycle()
                                if (ui.tab == "More") MoreScreen(vm) else ChatScreen(vm)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        for (next in Look.entries) {
            look.value = next
            compose.waitForIdle()
            capture("composer-context-${next.key}")
        }
        look.value = Look.Light
        compose.waitForIdle()
        compose.onNodeWithTag("composer-context").performClick()
        compose.waitUntil(5_000) { vm.ui.value.morePage == "Session usage" }
        compose.waitForIdle()
        capture("composer-context-usage-from-tap")
    }
}
