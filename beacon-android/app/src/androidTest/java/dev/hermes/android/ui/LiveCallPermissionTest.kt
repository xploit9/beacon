package dev.hermes.android.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual OS permission denial; all media and identities remain explicitly synthetic. */
@RunWith(AndroidJUnit4::class)
class LiveCallPermissionTest {
    @Test fun deniedOverlayPermissionKeepsCallAndWithdrawsCamera() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use{it.readText()}
        shell("appops set ${instrumentation.targetContext.packageName} SYSTEM_ALERT_WINDOW deny")
        ActivityScenario.launch<LiveCallShowcaseActivity>(Intent(instrumentation.targetContext,LiveCallShowcaseActivity::class.java)).use { scenario ->
            Thread.sleep(600)
            val id=LiveCallShowcaseActivity.source.value.callId
            val scope=LiveCallShowcaseActivity.source.value.conversationScope
            scenario.onActivity { LiveCallShowcaseActivity.source.value=LiveCallShowcaseActivity.source.value.copy(cameraSharing=true) }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 3")).use { it.readBytes() }
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (LiveCallShowcaseActivity.source.value.cameraSharing && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            assertNotEquals("Home must not ask for overlay permission", "com.android.settings", instrumentation.uiAutomation.rootInActiveWindow.packageName.toString())
            assertFalse(LiveCallShowcaseActivity.source.value.cameraSharing)
            Thread.sleep(700)
            assertTrue(LiveCallShowcaseActivity.source.value.active)
            assertTrue(LiveCallShowcaseActivity.source.value.minimized)
            assertEquals(id,LiveCallShowcaseActivity.source.value.callId)
            assertEquals(scope,LiveCallShowcaseActivity.source.value.conversationScope)
            assertFalse(android.provider.Settings.canDrawOverlays(instrumentation.targetContext))
            val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"ui-permission-evidence").apply{mkdirs()}
            FileOutputStream(File(folder,"permission-denied-fallback.png")).use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
            scenario.onActivity{it.finishCall()}
        }
        shell("appops set ${instrumentation.targetContext.packageName} SYSTEM_ALERT_WINDOW allow")
    }
}
