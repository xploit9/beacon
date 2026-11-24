package dev.hermes.android

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * S1 (task_3798d58b0101): BeaconActivity has no configChanges, so a theme, font or locale change relaunches it and the new activity keeps the
 * old one's model. The model learns "in front" only from onStart, through the activity's vm field, which composition sets.
 */
class ForegroundAfterRelaunchDeviceTest {
    private val shell = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun sh(command: String): String = shell.executeShellCommand(command).use { fd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText()
    }
    private val held = BeaconActivity::class.java.getDeclaredField("vm").apply { isAccessible = true }
    private val flag = BeaconViewModel::class.java.getDeclaredField("foreground").apply { isAccessible = true }
    private val trace = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var resumed: Activity? = null
    private val callback = ActivityLifecycleCallback { activity, stage ->
        if (activity !is BeaconActivity) return@ActivityLifecycleCallback
        if (stage == Stage.RESUMED) resumed = activity
        if (stage == Stage.STARTED || stage == Stage.STOPPED || stage == Stage.RESUMED) {
            val model = held.get(activity) as BeaconViewModel?
            trace += "${activity.hashCode().toString(16)} $stage vm=${model != null}${model?.let { " fg=${flag.getBoolean(it)}" } ?: ""}"
        }
    }
    // The device's own values, put back exactly: a shared emulator may run another night mode or an override size or density (an unfolded one).
    private val night = sh("cmd uimode night").substringAfter(':').trim()
    private fun override(what: String) = Regex("Override $what: (\\S+)").find(sh("wm $what"))?.groupValues?.get(1) ?: "reset"
    private val size = override("size")
    private val density = override("density")
    private var changed = false

    @After fun restore() {
        ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
        // Android applies them a moment later; the next test must not start inside that relaunch.
        if (changed) { sh("cmd uimode night $night"); sh("wm density $density"); sh("wm size $size"); Thread.sleep(3_000) }
        Log.i("S1Relaunch", trace.joinToString(" | "))
    }

    private fun foregroundOf(activity: Activity) = flag.getBoolean(ViewModelProvider(activity as BeaconActivity)[BeaconViewModel::class.java])
    private fun waitFor(what: String, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (!check() && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        assertTrue(what, check())
    }
    private fun launchComposed(): ActivityScenario<BeaconActivity> {
        assumeTrue("night mode $night can be put back", night in setOf("yes", "no", "auto"))
        sh("input keyevent KEYCODE_WAKEUP"); sh("wm dismiss-keyguard")
        changed = true
        sh("cmd uimode night no")
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        val scenario = ActivityScenario.launch(BeaconActivity::class.java)
        waitFor("the first activity composed") { resumed?.let { held.get(it) != null } == true }
        return scenario
    }

    /** A theme change while the chat is open (Android: uiMode is not a resize, so the window is not kept). */
    @Test fun aThemeChangeWhileOpenLeavesTheModelInFront() = launchComposed().use {
        val first = resumed
        sh("cmd uimode night yes")
        waitFor("a new activity composed") { resumed.let { it != null && it !== first && held.get(it) != null } }
        Thread.sleep(500)
        assertTrue("in front after a theme change while open: $trace", foregroundOf(resumed!!))
    }

    /** Control: Activity.recreate() on the open chat. */
    @Test fun aRecreateWhileOpenLeavesTheModelInFront() = launchComposed().use { scenario ->
        val first = resumed
        scenario.recreate()
        waitFor("a new activity composed") { resumed.let { it != null && it !== first && held.get(it) != null } }
        Thread.sleep(500)
        assertTrue("in front after recreate(): $trace", foregroundOf(resumed!!))
    }

    /** Display size (density) changes while the chat is open, as some folds do. */
    @Test fun aDensityChangeWhileOpenLeavesTheModelInFront() = launchComposed().use {
        val first = resumed
        sh("wm density 480")
        waitFor("a new activity composed") { resumed.let { it != null && it !== first && held.get(it) != null } }
        Thread.sleep(500)
        assertTrue("in front after a density change while open: $trace", foregroundOf(resumed!!))
    }

    /** Only the screen size changes (a fold) while Beacon is in the background. */
    @Test fun aSizeChangeWhileAwayLeavesTheModelInFrontOnReturn() = launchComposed().use { scenario ->
        val first = resumed
        scenario.moveToState(Lifecycle.State.CREATED)
        sh("wm size 1600x2400")
        Thread.sleep(1_000)
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("a new activity composed") { resumed.let { it != null && it !== first && held.get(it) != null } }
        Thread.sleep(500)
        assertTrue("in front after a size change while away: $trace", foregroundOf(resumed!!))
    }

    /** The theme changes while Beacon is in the background (Android relaunches it when it shows again). */
    @Test fun aThemeChangeWhileAwayLeavesTheModelInFrontOnReturn() = launchComposed().use { scenario ->
        val first = resumed
        scenario.moveToState(Lifecycle.State.CREATED)
        sh("cmd uimode night yes")
        Thread.sleep(1_000)
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("a new activity composed") { resumed.let { it != null && it !== first && held.get(it) != null } }
        Thread.sleep(500)
        assertTrue("in front after a theme change while away: $trace", foregroundOf(resumed!!))
    }
}
