package dev.hermes.android.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class LiveCallMotionCaptureTest {
    @get:Rule val rule = createAndroidComposeRule<LiveCallShowcaseActivity>()

    @Test fun controlledFrameClockMotionWithExplicitlyMockedMedia() {
        val images = mutableListOf<Bitmap>()
        val previousAutoAdvance = rule.mainClock.autoAdvance
        fun frame(): Bitmap = rule.onNodeWithContentDescription("${dev.hermes.android.data.currentAssistantName} voice activity").captureToImage().asAndroidBitmap().also(images::add)
        try {
            rule.mainClock.autoAdvance = false
            rule.runOnIdle { assertEquals(Lifecycle.State.RESUMED, rule.activity.lifecycle.currentState); rule.activity.motion(true) }
            rule.mainClock.advanceTimeBy(32)
            rule.runOnIdle { rule.activity.motion(false) }
            rule.mainClock.advanceTimeBy(160)
            val movingA = frame()
            rule.mainClock.advanceTimeBy(420)
            val movingB = frame()
            assertFalse("Positive control: the active wave must animate", movingA.sameAs(movingB))
            rule.runOnIdle { rule.activity.motion(true) }
            rule.mainClock.advanceTimeBy(160)
            val reducedA = frame()
            rule.mainClock.advanceTimeBy(420)
            val reducedB = frame()
            assertTrue("Reduced motion must stop the same visible wave", reducedA.sameAs(reducedB))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val folder = File(context.filesDir, "ui-motion-evidence").apply { check(isDirectory || mkdirs()) }
            listOf("moving-a", "moving-b", "reduced-a", "reduced-b").zip(images).forEach { (name, image) ->
                File(folder, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally {
            rule.mainClock.autoAdvance = previousAutoAdvance
            images.forEach(Bitmap::recycle)
            rule.runOnIdle { rule.activity.finishCall() }
        }
    }
}
