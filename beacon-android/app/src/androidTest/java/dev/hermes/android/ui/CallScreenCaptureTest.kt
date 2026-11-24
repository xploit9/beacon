package dev.hermes.android.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.agentphone.*
import dev.hermes.android.BeaconTheme
import dev.hermes.android.IncomingCallContent
import dev.hermes.android.voice.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private class CallScene(private val realTime: Boolean = false) {
    var ringing by mutableStateOf(true)
    var call by mutableStateOf(CallState())
    var returnedToChat by mutableStateOf(false)
    @Composable fun Show() {
        BeaconTheme(dark = true) {
                Surface(Modifier.fillMaxSize().testTag("capture-surface"), color = CallBackground, contentColor = Color.White) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                        when {
                            ringing -> IncomingCallContent("", ringFraction(), "", "Answer", true, onAnswer = {
                                ringing = false
                                call = CallState(active = true, state = "connecting", startedAt = System.currentTimeMillis(), callId = "demo")
                            }, onDecline = { ringing = false }, modifier = Modifier.widthIn(max = 520.dp), fullScreen = true)
                            call.active -> LiveCallSurface(call, CallUiActions(
                                mute = { call = call.copy(muted = !call.muted) }, camera = {}, flip = {}, allowBubble = {},
                                end = { call = call.copy(active = false, state = "ending") }, route = {}),
                                rememberNativeMotionEnabled(), onConversation = {}) { }
                            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                TextButton({ returnedToChat = true }) { Text("Chat") }
                                if (returnedToChat) Text("Your meeting starts at three.")
                            }
                        }
                    }
                }
                CallEndNotice(call)
            }
    }

    @Composable private fun ringFraction(): Float {
        if (!realTime) return .82f
        val start = remember { SystemClock.elapsedRealtime() }
        var fraction by remember { mutableFloatStateOf(1f) }
        LaunchedEffect(start) {
            while (true) {
                fraction = (1f - (SystemClock.elapsedRealtime() - start) / 45_000f).coerceIn(0f, 1f)
                delay(250)
            }
        }
        return fraction
    }
}

/** Only generated conversation data is composed, including when screenrecord is running. No networking or audio. */
class CallScreenCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun answerConnectTalkMuteAndEndOnTheDialer() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val capture = InstrumentationRegistry.getArguments().getString("callMedia")?.takeIf { it == "phone" || it == "unfolded" }
        val folder = target.getExternalFilesDir("callscreen-123")
        val scene = CallScene()
        compose.setContent { scene.Show() }
        fun photograph(state: String) {
            if (capture == null) return
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(checkNotNull(folder), "$capture-$state.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        try {
            photograph("incoming")
            compose.onNodeWithTag("beacon-incoming-answer").performClick()
            compose.onNodeWithTag("beacon-call-status").assertTextEquals("Connecting")
            photograph("connecting")
            compose.runOnIdle { scene.call = scene.call.copy(state = "listening", routes = listOf(AudioRoute("speaker", "Speaker")), route = "speaker") }
            compose.onNodeWithTag("beacon-call-status").assertTextEquals("Listening")
            compose.runOnIdle { scene.call = scene.call.copy(state = "speaking", remoteSpeaking = true, remoteAudioLevel = .55f,
                transcripts = listOf(CallTranscript("sample-line", "assistant", "Your meeting starts at three."))) }
            compose.onNodeWithTag("beacon-live-captions").assertIsDisplayed()
            photograph("live")
            compose.onNodeWithTag("beacon-mute").performClick()
            assertTrue(compose.runOnIdle { scene.call.muted })
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("beacon-hang-up").performClick()
            compose.mainClock.advanceTimeBy(96)
            compose.onNodeWithTag("beacon-call-ended").assertIsDisplayed()
            photograph("ended")
            compose.mainClock.advanceTimeBy(1600)
            compose.onNodeWithTag("beacon-call-ended").assertDoesNotExist()
            compose.onNodeWithText("Chat").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }


    @Test fun watchASyntheticClockDuringTheCall() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val width = 320
        val height = 600
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(30, 31, 35))
        val ink = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 25f
        }
        canvas.drawText("Clock", 20f, 58f, ink)
        ink.textSize = 52f
        canvas.drawText("10:09", 32f, 242f, ink)
        ink.textSize = 19f
        canvas.drawText("Saturday", 36f, 275f, ink)
        val frame = ShownFrame(bitmap.asImageBitmap(), width, height, 0, 1)
        var call by mutableStateOf(CallState(active = true, state = "listening", callId = "fixture"))
        var tookControl = false
        var asked = ""
        compose.setContent {
            BeaconTheme(dark = true) {
                CallPhonePanel(call, { call = call.copy(muted = !call.muted) }, { call = call.copy(active = false) }, {}, {},
                    sendText = { asked = it; true }) {
                    AgentPhoneViewScreen(ViewUi(phase = ViewPhase.Live, hasFrame = true), frame, "Beacon", ViewActions(),
                        onTakeControl = { tookControl = true }, stageColor = CallBackground)
                }
            }
        }
        compose.onNodeWithTag("agent-phone-frame").assertIsDisplayed()
        compose.onNodeWithTag("agent-phone-take-control").assertIsDisplayed()
        compose.onNodeWithText("Mute").performClick()
        assertTrue(compose.runOnIdle { call.muted })
        val size = InstrumentationRegistry.getArguments().getString("callMedia")
        if (size in setOf("phone", "unfolded")) {
            val folder = checkNotNull(instrumentation.targetContext.getExternalFilesDir("callscreen-123"))
            File(folder, "$size-watching.png").outputStream().use {
                assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            val video = File(folder, "$size-watching.mp4")
            val record = instrumentation.uiAutomation.executeShellCommand("screenrecord --bit-rate 3000000 --time-limit 7 ${video.absolutePath}")
            try {
                SystemClock.sleep(1200)
                compose.onNodeWithText("Unmute").performClick()
                compose.runOnIdle { call = call.copy(state = "speaking", remoteSpeaking = true, remoteAudioLevel = .5f) }
            } finally { ParcelFileDescriptor.AutoCloseInputStream(record).use { it.readBytes() } }
            assertTrue(video.length() > 1024)
        }
        compose.onNodeWithTag("call-phone-ask").performClick()
        compose.onNodeWithTag("call-phone-message").performTextInput("What time is it?")
        compose.onNodeWithTag("call-phone-send").performClick()
        assertTrue(asked == "What time is it?" && !tookControl)
        compose.onNodeWithTag("agent-phone-take-control").performClick()
        assertTrue(tookControl)
    }

}


/** A normal Android frame clock drives the dust while the platform recorder captures a real-time transition. */
class CallScreenRecordingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    // ActivityScenario.onActivity waits for idle. A continuously animated call need never be idle.
    private fun onMain(block: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try { block() } catch (error: Throwable) { failure = error } finally { done.countDown() }
        }
        check(done.await(5, TimeUnit.SECONDS)) { "Call display did not update" }
        failure?.let { throw it }
    }

    private fun action(label: String) {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { child -> find(child)?.let { return it } }
            return null
        }
        val end = SystemClock.elapsedRealtime() + 6000
        while (SystemClock.elapsedRealtime() < end) {
            val found = instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
            if (generateSequence(found) { it.parent }.firstOrNull { it.isEnabled && it.isClickable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            SystemClock.sleep(80)
        }
        error("Call action unavailable: $label")
    }

    @Test fun recordIncomingToEndedWithThePlatformFrameClock() {
        val size = InstrumentationRegistry.getArguments().getString("callMedia")
        org.junit.Assume.assumeTrue(size == "phone" || size == "unfolded")
        val scene = CallScene(realTime = true)
        val folder = checkNotNull(instrumentation.targetContext.getExternalFilesDir("callscreen-123"))
        val output = File(folder, "$size-call.mp4")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var host: ComponentActivity
            scenario.onActivity { host = it; it.setContent { scene.Show() } }
            SystemClock.sleep(800)
            val record = instrumentation.uiAutomation.executeShellCommand("screenrecord --bit-rate 5000000 --time-limit 20 ${output.absolutePath}")
            try {
                SystemClock.sleep(2400)
                action("Answer")
                SystemClock.sleep(1800)
                onMain {
                    check(scene.call.active)
                    scene.call = scene.call.copy(state = "listening", routes = listOf(AudioRoute("speaker", "Speaker")), route = "speaker")
                }
                SystemClock.sleep(1600)
                onMain { scene.call = scene.call.copy(state = "speaking", remoteSpeaking = true, remoteAudioLevel = .55f,
                    transcripts = listOf(CallTranscript("sample", "assistant", "Your meeting starts at three."))) }
                SystemClock.sleep(2200)
                action("Mute")
                SystemClock.sleep(1300)
                onMain { check(scene.call.muted) }
                action("End call")
                SystemClock.sleep(1800)
                onMain { check(!scene.call.active) }
                action("Chat")
                SystemClock.sleep(1000)
                onMain { check(scene.returnedToChat) }
            } finally {
                try { ParcelFileDescriptor.AutoCloseInputStream(record).use { it.readBytes() } }
                finally { onMain { host.setContent { } } }
            }
        }
        assertTrue(output.length() > 1024)
    }
}
