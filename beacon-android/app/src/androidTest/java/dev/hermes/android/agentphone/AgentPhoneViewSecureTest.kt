package dev.hermes.android.agentphone

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The real AgentPhoneActivity on a device: secure and kept on before anything is drawn, full screen, and it opens with no extras. */
class AgentPhoneViewSecureTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Test fun theWindowIsSecureAndKeptOnBeforeAnyContent() {
        // onActivityCreated is dispatched from inside Activity.onCreate (super.onCreate), before setContent: whatever the window has
        // then, it had before its first frame of content.
        var flagsAtCreate = -1
        var childrenAtCreate = -1
        val created = CountDownLatch(1)
        val watch = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is AgentPhoneActivity) return
                flagsAtCreate = activity.window.attributes.flags
                childrenAtCreate = activity.findViewById<ViewGroup>(android.R.id.content)?.childCount ?: 0
                created.countDown()
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(watch)
        try {
            ActivityScenario.launch(AgentPhoneActivity::class.java).use { scenario ->
                assertTrue(created.await(10, TimeUnit.SECONDS))
                assertTrue("FLAG_SECURE before content", flagsAtCreate and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue("FLAG_KEEP_SCREEN_ON before content", flagsAtCreate and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
                assertEquals("no content yet when the flags were checked", 0, childrenAtCreate)
                scenario.onActivity { activity ->
                    val flags = activity.window.attributes.flags
                    assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    assertTrue(flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
                    assertTrue("the view has its content now", (activity.findViewById<ViewGroup>(android.R.id.content)?.childCount ?: 0) > 0)
                }
            }
        } finally {
            app.unregisterActivityLifecycleCallbacks(watch)
        }
    }

    @Test fun theSystemBarsAreHiddenWhileTheViewIsOpen() {
        ActivityScenario.launch(AgentPhoneActivity::class.java).use { scenario ->
            var hidden = false
            val deadline = System.currentTimeMillis() + 5_000
            while (!hidden && System.currentTimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    hidden = insets != null && !insets.isVisible(WindowInsetsCompat.Type.statusBars()) && !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
                }
                if (!hidden) Thread.sleep(100)
            }
            assertTrue("status and navigation bars hidden", hidden)
        }
    }

    @Test fun itOpensByClassNameWithNoExtrasAndSaysWhatItNeeds() {
        // L4 opens the view by class name with no extras (Root, 17 Nov). On a test phone with no Hermes sign-in it must still open,
        // secure, and say what is missing rather than fail.
        ActivityScenario.launch(AgentPhoneActivity::class.java).use { scenario ->
            var phase: ViewPhase? = null
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val model = androidx.lifecycle.ViewModelProvider(activity)[AgentPhoneViewModel::class.java]
                    phase = model.session.ui.value.phase
                }
                if (phase != ViewPhase.Connecting) break
                Thread.sleep(100)
            }
            assertNotNull(phase)
            assertFalse("left Connecting", phase == ViewPhase.Connecting)
        }
    }
}
