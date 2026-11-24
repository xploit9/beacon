package dev.hermes.android.agentphone

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconTheme
import dev.hermes.android.data.AgentPhoneInstall
import dev.hermes.android.data.AgentPhoneInstallPhase
import dev.hermes.android.data.AgentPhoneInstallState
import dev.hermes.android.data.AgentPhoneLicence
import dev.hermes.android.data.AgentPhoneTerms
import dev.hermes.android.ui.AgentPhoneSetupScreen
import dev.hermes.android.ui.AgentPhoneSetupActions
import dev.hermes.android.ui.AgentPhoneSetupState
import dev.hermes.android.ui.AgentPhoneStage
import dev.hermes.android.ui.NativeLocalization
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * F02 probe (opt-in, `-e setup_frames 1`): the agent-phone setup's accept-to-progress transition on synthetic state, no phone and no
 * server. Terms with a licence of `licence_kb` KB (default 6) → accept in flight (the button's dust turns) for 1.5 s → the download page
 * with progress readings every 150 ms for 4 s. The window's frames are read from `dumpsys gfxinfo framestats` and logged under SETUPFRAMES
 * (frames, median and p90/p99 duration in ms, and how many missed 16.7 ms). Relative numbers only: compare runs on the same emulator.
 */
class AgentPhoneSetupFramesProbeTest {
    private var state by mutableStateOf(AgentPhoneSetupState())

    @Test fun acceptToProgressFrames() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("opt-in probe", args.getString("setup_frames") == "1")
        val kb = args.getString("licence_kb")?.toIntOrNull() ?: 6
        val licence = buildString { var n = 0; while (length < kb * 1024) append("Section ${n++}. The licensor grants the licensee a limited, non-exclusive right to use the software. ") }
        state = AgentPhoneSetupState(stage = AgentPhoneStage.Terms, terms = AgentPhoneTerms(listOf(AgentPhoneLicence("synthetic", licence)), 1_000_000_000L, null, null))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // `floor=1`: one line of text that changes with every reading, the emulator's own cost of a changed frame.
                if (args.getString("floor") == "1") activity.setContent { androidx.compose.material3.Text("${state.install.doneBytes} ${state.accepting}") }
                else activity.setContent { NativeLocalization("en") { BeaconTheme(dark = true) { AgentPhoneSetupScreen(state, AgentPhoneSetupActions()) } } }
            }
            Thread.sleep(2_500)
            // Where a changed frame's time goes, from the platform's own per-frame breakdown (ms): recompose + animation callbacks,
            // measure/layout, draw recording, sync and the GPU command issue. Only frames over 16.7 ms are summed.
            val parts = listOf("animation" to android.view.FrameMetrics.ANIMATION_DURATION, "layout" to android.view.FrameMetrics.LAYOUT_MEASURE_DURATION,
                "draw" to android.view.FrameMetrics.DRAW_DURATION, "sync" to android.view.FrameMetrics.SYNC_DURATION,
                "issue" to android.view.FrameMetrics.COMMAND_ISSUE_DURATION, "total" to android.view.FrameMetrics.TOTAL_DURATION)
            val sums = LongArray(parts.size); var slow = 0; var seen = 0
            val listener = android.view.Window.OnFrameMetricsAvailableListener { _, m, _ ->
                seen++
                if (m.getMetric(android.view.FrameMetrics.TOTAL_DURATION) > 16_700_000L) { slow++; parts.forEachIndexed { i, (_, id) -> sums[i] += m.getMetric(id) } }
            }
            scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(android.os.Looper.getMainLooper())) }
            instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo $pkg reset").close()
            Thread.sleep(300)
            instrumentation.runOnMainSync { state = state.copy(accepting = true) }
            Thread.sleep(1_500)
            instrumentation.runOnMainSync { state = state.copy(accepting = false, stage = AgentPhoneStage.Download, install = progress(0)) }
            for (i in 1..26) { Thread.sleep(150); instrumentation.runOnMainSync { state = state.copy(install = progress(i)) } }
            Thread.sleep(300)
            val text = instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo $pkg framestats").use { out ->
                java.io.BufferedReader(java.io.InputStreamReader(android.os.ParcelFileDescriptor.AutoCloseInputStream(out))).readText()
            }
            // The platform's own summary of the window since the reset: total and janky frames and the duration percentiles.
            val keep = Regex("^(Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missing Vsync|Number Slow UI thread).*$", RegexOption.MULTILINE)
            Log.i("SETUPFRAMES", "parts frames=$seen slow=$slow mean_ms_of_slow " + parts.mapIndexed { i, (n, _) -> "$n=${if (slow == 0) 0 else sums[i] / slow / 1_000_000}" }.joinToString(" "))
            Log.i("SETUPFRAMES", "kb=$kb " + keep.findAll(text).joinToString(" | ") { it.value.trim() })
        }
    }

    private fun progress(step: Int) = AgentPhoneInstall(AgentPhoneInstallState.Running, AgentPhoneInstallPhase.Download, 40_000_000L * step, 1_000_000_000L)
}
